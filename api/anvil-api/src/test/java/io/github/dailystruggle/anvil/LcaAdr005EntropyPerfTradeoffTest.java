package io.github.dailystruggle.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Empirical compression-vs-latency tradeoff benchmark for {@code anvil-api-ADR-005}.
 * Evaluates lossless ({@code L_inf = 0}, {@code Zero Zstd}) carryovers from
 * {@code leaf-video-ADR-005} ({@code lcv_a5_rc_*}, {@code MED-2D}, {@code signed12}) and
 * lightweight byte-aligned / LUT alternatives against {@link LcaSectionCodec} baselines
 * on live {@code 26.3} {@code .lca} overworld regions.
 *
 * @governedBy anvil-api-ADR-005
 * @governedBy anvil-api-ADR-004
 * @governedBy ADR-115
 */
@DisplayName("anvil-api-ADR-005: Compression vs. Latency Tradeoff Benchmark")
class LcaAdr005EntropyPerfTradeoffTest {

    private static final int SECTION_VOXELS = 4096;
    private static final long RC_TOP = 1L << 24;
    private static final long RC_BOT = 1L << 16;

    // =========================================================================
    // 1. Faithful Java Port of lcv_adr005_engines.h Carryless 32-Bit Binary RC
    // =========================================================================

    private static final class RangeEnc {
        final byte[] buf;
        int pos;
        long low;
        long range;

        RangeEnc(int cap) {
            this.buf = new byte[cap];
            reset();
        }

        void reset() {
            this.pos = 0;
            this.low = 0L;
            this.range = 0xFFFFFFFFL;
        }

        private void renorm() {
            for (;;) {
                if (((low ^ ((low + range) & 0xFFFFFFFFL)) & 0xFFFFFFFFL) >= RC_TOP) {
                    if (range >= RC_BOT) {
                        break;
                    }
                    range = ((-low) & (RC_BOT - 1L));
                    if (range == 0L) {
                        break;
                    }
                }
                buf[pos++] = (byte) ((low >>> 24) & 0xFFL);
                low = (low << 8) & 0xFFFFFFFFL;
                range = (range << 8) & 0xFFFFFFFFL;
            }
        }

        void encBit(int[] probs, int ctx, int bit) {
            int p = probs[ctx];
            long bound = (range >>> 12) * p;
            if (bit == 0) {
                range = bound;
                p += (4096 - p) >>> 5;
                if (p > 4095) p = 4095;
            } else {
                low = (low + bound) & 0xFFFFFFFFL;
                range = (range - bound) & 0xFFFFFFFFL;
                p -= p >>> 5;
                if (p < 1) p = 1;
            }
            probs[ctx] = p;
            renorm();
        }

        void encBypass(int bit) {
            long bound = (range >>> 12) * 2048L;
            if (bit == 0) {
                range = bound;
            } else {
                low = (low + bound) & 0xFFFFFFFFL;
                range = (range - bound) & 0xFFFFFFFFL;
            }
            renorm();
        }

        void encSigned12(int[] probs, int baseCtx, int val) {
            if (val == 0) {
                encBit(probs, baseCtx, 0);
                return;
            }
            encBit(probs, baseCtx, 1);
            encBit(probs, baseCtx + 1, val < 0 ? 1 : 0);
            int mag = Math.abs(val) - 1;
            int unaryMax = 8;
            int u = Math.min(mag, unaryMax);
            for (int i = 0; i < u; i++) {
                encBit(probs, baseCtx + 2 + i, 1);
            }
            if (mag < unaryMax) {
                encBit(probs, baseCtx + 2 + u, 0);
                return;
            }
            // Exp-Golomb order-0 for remainder >= 8
            int rem = mag - unaryMax;
            int remPlus1 = rem + 1;
            int bits = 31 - Integer.numberOfLeadingZeros(remPlus1);
            for (int i = 0; i < bits; i++) {
                encBit(probs, baseCtx + 11, 1);
            }
            encBit(probs, baseCtx + 11, 0);
            for (int b = bits - 1; b >= 0; b--) {
                encBypass((remPlus1 >>> b) & 1);
            }
        }

        int finish() {
            for (int i = 0; i < 4; i++) {
                buf[pos++] = (byte) ((low >>> 24) & 0xFFL);
                low = (low << 8) & 0xFFFFFFFFL;
            }
            return pos;
        }
    }

    private static final class RangeDec {
        byte[] buf;
        int len;
        int pos;
        long low;
        long range;
        long code;

        void init(byte[] src, int off, int length) {
            this.buf = src;
            this.pos = off;
            this.len = off + length;
            this.low = 0L;
            this.range = 0xFFFFFFFFL;
            this.code = 0L;
            for (int i = 0; i < 4; i++) {
                code = ((code << 8) | getByte()) & 0xFFFFFFFFL;
            }
        }

        private long getByte() {
            return (pos < len) ? (buf[pos++] & 0xFFL) : 0L;
        }

        private void renorm() {
            for (;;) {
                if (((low ^ ((low + range) & 0xFFFFFFFFL)) & 0xFFFFFFFFL) >= RC_TOP) {
                    if (range >= RC_BOT) {
                        break;
                    }
                    range = ((-low) & (RC_BOT - 1L));
                    if (range == 0L) {
                        break;
                    }
                }
                code = ((code << 8) | getByte()) & 0xFFFFFFFFL;
                low = (low << 8) & 0xFFFFFFFFL;
                range = (range << 8) & 0xFFFFFFFFL;
            }
        }

        int decBit(int[] probs, int ctx) {
            int p = probs[ctx];
            long bound = (range >>> 12) * p;
            long diff = (code - low) & 0xFFFFFFFFL;
            int bit;
            if (diff < bound) {
                bit = 0;
                range = bound;
                p += (4096 - p) >>> 5;
                if (p > 4095) p = 4095;
            } else {
                bit = 1;
                low = (low + bound) & 0xFFFFFFFFL;
                range = (range - bound) & 0xFFFFFFFFL;
                p -= p >>> 5;
                if (p < 1) p = 1;
            }
            probs[ctx] = p;
            renorm();
            return bit;
        }

        int decBypass() {
            long bound = (range >>> 12) * 2048L;
            long diff = (code - low) & 0xFFFFFFFFL;
            int bit;
            if (diff < bound) {
                bit = 0;
                range = bound;
            } else {
                bit = 1;
                low = (low + bound) & 0xFFFFFFFFL;
                range = (range - bound) & 0xFFFFFFFFL;
            }
            renorm();
            return bit;
        }

        int decSigned12(int[] probs, int baseCtx) {
            if (decBit(probs, baseCtx) == 0) {
                return 0;
            }
            int sign = decBit(probs, baseCtx + 1);
            int mag = 0;
            while (mag < 8) {
                if (decBit(probs, baseCtx + 2 + mag) == 0) {
                    int v = mag + 1;
                    return sign != 0 ? -v : v;
                }
                mag++;
            }
            int bits = 0;
            while (decBit(probs, baseCtx + 11) != 0) {
                bits++;
            }
            int remPlus1 = 1;
            for (int i = 0; i < bits; i++) {
                remPlus1 = (remPlus1 << 1) | decBypass();
            }
            int v = 8 + (remPlus1 - 1) + 1;
            return sign != 0 ? -v : v;
        }
    }

    // =========================================================================
    // 2. Candidate A: 16x16 Heightmap Codecs (Baseline 1D vs MED-2D RLE vs MED-2D RC)
    // =========================================================================

    private static int med2d(int a, int b, int c) {
        int minAB = Math.min(a, b);
        int maxAB = Math.max(a, b);
        if (c >= maxAB) return minAB;
        if (c <= minAB) return maxAB;
        return a + b - c;
    }

    private static int[] unpackHeightmapCols(long[] longs) {
        int[] cols = new int[256];
        int col = 0;
        for (int w = 0; w < longs.length && col < 256; w++) {
            long word = longs[w];
            int lim = Math.min(7, 256 - col);
            for (int s = 0; s < lim; s++) {
                cols[col++] = (int) ((word >>> (s * 9)) & 0x1FFL);
            }
        }
        return cols;
    }

    private static long[] packHeightmapCols(int[] cols, int numLongs) {
        long[] out = new long[numLongs];
        int col = 0;
        for (int w = 0; w < numLongs; w++) {
            long word = 0L;
            int lim = Math.min(7, 256 - col);
            for (int s = 0; s < lim; s++) {
                word |= ((long) (cols[col++] & 0x1FF)) << (s * 9);
            }
            out[w] = word;
        }
        return out;
    }

    private static byte[] encodeHeightmapMed2dByteRle(long[] longs) {
        int[] cols = unpackHeightmapCols(longs);
        byte[] buf = new byte[2 + 256 * 3];
        int pos = 0;
        buf[pos++] = (byte) 0xFB; // v3 MED-2D Byte-RLE sentinel
        buf[pos++] = (byte) longs.length;
        int zeroRun = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int idx = (z << 4) | x;
                int pred;
                if (z == 0 && x == 0) pred = 0;
                else if (z == 0) pred = cols[idx - 1];
                else if (x == 0) pred = cols[idx - 16];
                else pred = med2d(cols[idx - 1], cols[idx - 16], cols[idx - 17]);
                int d = (cols[idx] - pred) & 0x1FF;
                if (d >= 256) d -= 512;
                if (d == 0) {
                    zeroRun++;
                    if (zeroRun == 128) {
                        buf[pos++] = (byte) (0x80 | 127);
                        zeroRun = 0;
                    }
                } else {
                    if (zeroRun > 0) {
                        buf[pos++] = (byte) (0x80 | (zeroRun - 1));
                        zeroRun = 0;
                    }
                    int zz = (d << 1) ^ (d >> 31);
                    if (zz < 64) {
                        buf[pos++] = (byte) zz;
                    } else {
                        buf[pos++] = (byte) (0x40 | (zz & 0x3F));
                        int extra = zz >>> 6;
                        while (extra >= 0x80) {
                            buf[pos++] = (byte) ((extra & 0x7F) | 0x80);
                            extra >>>= 7;
                        }
                        buf[pos++] = (byte) extra;
                    }
                }
            }
        }
        if (zeroRun > 0) {
            buf[pos++] = (byte) (0x80 | (zeroRun - 1));
        }
        return Arrays.copyOf(buf, pos);
    }

    private static long[] decodeHeightmapMed2dByteRle(byte[] src, int[] colsScratch) {
        int numLongs = src[1] & 0xFF;
        int pos = 2;
        int lim = src.length;
        int idx = 0;
        while (idx < 256 && pos < lim) {
            int tok = src[pos++] & 0xFF;
            if ((tok & 0x80) != 0) {
                int end = Math.min(256, idx + (tok & 0x7F) + 1);
                while (idx < end) {
                    int z = idx >>> 4;
                    int x = idx & 15;
                    int pred;
                    if (z == 0 && x == 0) pred = 0;
                    else if (z == 0) pred = colsScratch[idx - 1];
                    else if (x == 0) pred = colsScratch[idx - 16];
                    else pred = med2d(colsScratch[idx - 1], colsScratch[idx - 16], colsScratch[idx - 17]);
                    colsScratch[idx++] = pred & 0x1FF;
                }
            } else {
                int zz = tok;
                if ((tok & 0x40) != 0) {
                    int extra = 0;
                    int shift = 0;
                    for (;;) {
                        int b = src[pos++] & 0xFF;
                        extra |= (b & 0x7F) << shift;
                        if ((b & 0x80) == 0) break;
                        shift += 7;
                    }
                    zz = (tok & 0x3F) | (extra << 6);
                }
                int d = (zz >>> 1) ^ -(zz & 1);
                int z = idx >>> 4;
                int x = idx & 15;
                int pred;
                if (z == 0 && x == 0) pred = 0;
                else if (z == 0) pred = colsScratch[idx - 1];
                else if (x == 0) pred = colsScratch[idx - 16];
                else pred = med2d(colsScratch[idx - 1], colsScratch[idx - 16], colsScratch[idx - 17]);
                colsScratch[idx++] = (pred + d) & 0x1FF;
            }
        }
        return packHeightmapCols(colsScratch, numLongs);
    }

    private static byte[] encodeHeightmapMed2dRc(long[] longs, RangeEnc rc, int[] probs) {
        int[] cols = unpackHeightmapCols(longs);
        Arrays.fill(probs, 2048);
        rc.reset();
        rc.buf[rc.pos++] = (byte) 0xFC; // v3 MED-2D RC sentinel
        rc.buf[rc.pos++] = (byte) longs.length;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int idx = (z << 4) | x;
                int pred;
                if (z == 0 && x == 0) pred = 0;
                else if (z == 0) pred = cols[idx - 1];
                else if (x == 0) pred = cols[idx - 16];
                else pred = med2d(cols[idx - 1], cols[idx - 16], cols[idx - 17]);
                int d = (cols[idx] - pred) & 0x1FF;
                if (d >= 256) d -= 512;
                rc.encSigned12(probs, 0, d);
            }
        }
        int len = rc.finish();
        return Arrays.copyOf(rc.buf, len);
    }

    private static long[] decodeHeightmapMed2dRc(byte[] src, RangeDec rc, int[] probs, int[] colsScratch) {
        int numLongs = src[1] & 0xFF;
        Arrays.fill(probs, 2048);
        rc.init(src, 2, src.length - 2);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int idx = (z << 4) | x;
                int pred;
                if (z == 0 && x == 0) pred = 0;
                else if (z == 0) pred = colsScratch[idx - 1];
                else if (x == 0) pred = colsScratch[idx - 16];
                else pred = med2d(colsScratch[idx - 1], colsScratch[idx - 16], colsScratch[idx - 17]);
                int d = rc.decSigned12(probs, 0);
                colsScratch[idx] = (pred + d) & 0x1FF;
            }
        }
        return packHeightmapCols(colsScratch, numLongs);
    }

    // =========================================================================
    // 3. Candidate B: Eikonal Light Residuals (GrayPlanes vs Octant Byte-RLE vs Octant RC)
    // =========================================================================

    private static byte classifyOpacityEmission(String canonical) {
        int bracket = canonical.indexOf('[');
        String base = bracket < 0 ? canonical : canonical.substring(0, bracket);
        int emit = 0;
        if (base.equals("minecraft:torch") || base.equals("minecraft:wall_torch")) emit = 14;
        else if (base.equals("minecraft:lantern") || base.equals("minecraft:glowstone")
                || base.equals("minecraft:sea_lantern") || base.equals("minecraft:shroomlight")
                || base.equals("minecraft:jack_o_lantern") || base.equals("minecraft:lava")
                || base.equals("minecraft:fire") || base.equals("minecraft:ochre_froglight")
                || base.equals("minecraft:verdant_froglight") || base.equals("minecraft:pearlescent_froglight")
                || base.equals("minecraft:beacon") || base.equals("minecraft:conduit")) emit = 15;
        else if (base.equals("minecraft:soul_torch") || base.equals("minecraft:soul_wall_torch")
                || base.equals("minecraft:soul_lantern") || base.equals("minecraft:soul_fire")
                || base.equals("minecraft:soul_campfire")) emit = 10;
        else if (base.equals("minecraft:campfire")) emit = 15;
        else if (base.equals("minecraft:glow_lichen")) emit = 7;
        else if (base.equals("minecraft:magma_block")) emit = 3;
        else if (base.equals("minecraft:crying_obsidian")) emit = 10;
        else if (base.equals("minecraft:end_rod")) emit = 14;
        else if (base.equals("minecraft:redstone_torch") || base.equals("minecraft:redstone_wall_torch")) {
            emit = canonical.contains("lit=false") ? 0 : 7;
        } else if (base.equals("minecraft:redstone_lamp") || base.equals("minecraft:furnace")
                || base.equals("minecraft:blast_furnace") || base.equals("minecraft:smoker")) {
            emit = canonical.contains("lit=true") ? 13 : 0;
        }

        int opac;
        if (base.equals("minecraft:air") || base.equals("minecraft:cave_air") || base.equals("minecraft:void_air")
                || base.equals("minecraft:short_grass") || base.equals("minecraft:tall_grass")
                || base.equals("minecraft:fern") || base.equals("minecraft:large_fern")
                || base.equals("minecraft:torch") || base.equals("minecraft:wall_torch")
                || base.equals("minecraft:soul_torch") || base.equals("minecraft:soul_wall_torch")
                || base.equals("minecraft:redstone_torch") || base.equals("minecraft:redstone_wall_torch")
                || base.equals("minecraft:lantern") || base.equals("minecraft:soul_lantern")
                || base.equals("minecraft:glass") || base.equals("minecraft:glass_pane")
                || base.endsWith("_stained_glass") || base.endsWith("_stained_glass_pane")
                || base.equals("minecraft:vine") || base.equals("minecraft:glow_lichen")
                || base.equals("minecraft:snow") || base.equals("minecraft:rail")
                || base.equals("minecraft:dandelion") || base.equals("minecraft:poppy")
                || base.equals("minecraft:sugar_cane") || base.equals("minecraft:seagrass")
                || base.equals("minecraft:tall_seagrass") || base.equals("minecraft:kelp")
                || base.equals("minecraft:kelp_plant") || base.equals("minecraft:dead_bush")
                || base.equals("minecraft:flower_pot") || base.equals("minecraft:ladder")
                || base.equals("minecraft:fire") || base.equals("minecraft:soul_fire")
                || base.endsWith("_fence") || base.endsWith("_fence_gate")
                || base.endsWith("_button") || base.endsWith("_pressure_plate")
                || base.endsWith("_sign") || base.endsWith("_wall_sign")
                || base.endsWith("_carpet") || base.endsWith("_sapling")
                || base.endsWith("_tulip") || base.endsWith("_orchid")) {
            opac = 0;
        } else if (base.equals("minecraft:water") || base.equals("minecraft:ice")
                || base.endsWith("_leaves") || base.equals("minecraft:cobweb")
                || base.equals("minecraft:slime_block") || base.equals("minecraft:honey_block")
                || base.equals("minecraft:lava")) {
            opac = 1;
        } else {
            opac = 15;
        }
        return (byte) ((opac << 4) | (emit & 0x0F));
    }

    private static void predictBlockLightEikonal(byte[] packedLight, byte[] hatL, int[] ring, long[] inQueue) {
        Arrays.fill(hatL, (byte) 0);
        Arrays.fill(inQueue, 0L);
        int head = 0, tail = 0;
        for (int v = 0; v < SECTION_VOXELS; v++) {
            int e = packedLight[v] & 0x0F;
            if (e > 0) {
                hatL[v] = (byte) e;
                ring[tail++] = v;
                inQueue[v >>> 6] |= (1L << (v & 63));
            }
        }
        while (head != tail) {
            int v = ring[head];
            head = (head + 1) & 4095;
            inQueue[v >>> 6] &= ~(1L << (v & 63));
            int lv = hatL[v] & 0xFF;
            if (lv <= 1) continue;
            int x = v & 15;
            int z = (v >>> 4) & 15;
            int y = v >>> 8;
            if (x > 0) tail = relaxLightNeighbor(v - 1, lv, packedLight, hatL, ring, inQueue, tail);
            if (x < 15) tail = relaxLightNeighbor(v + 1, lv, packedLight, hatL, ring, inQueue, tail);
            if (z > 0) tail = relaxLightNeighbor(v - 16, lv, packedLight, hatL, ring, inQueue, tail);
            if (z < 15) tail = relaxLightNeighbor(v + 16, lv, packedLight, hatL, ring, inQueue, tail);
            if (y > 0) tail = relaxLightNeighbor(v - 256, lv, packedLight, hatL, ring, inQueue, tail);
            if (y < 15) tail = relaxLightNeighbor(v + 256, lv, packedLight, hatL, ring, inQueue, tail);
        }
    }

    private static void predictSkyLightEikonal(byte[] packedLight, byte[] topSeed256, byte[] hatL, int[] ring, long[] inQueue) {
        Arrays.fill(hatL, (byte) 0);
        Arrays.fill(inQueue, 0L);
        int head = 0, tail = 0;
        for (int xz = 0; xz < 256; xz++) {
            int s;
            if (topSeed256 != null) {
                s = topSeed256[xz] & 0x0F;
            } else {
                int topV = (15 << 8) | xz;
                int opac = (packedLight[topV] >>> 4) & 0x0F;
                s = (opac == 0) ? 15 : 0;
            }
            for (int y = 15; y >= 0; y--) {
                int v = (y << 8) | xz;
                int opac = (packedLight[v] >>> 4) & 0x0F;
                if (y < 15) {
                    s = Math.max(0, s - opac);
                }
                hatL[v] = (byte) s;
                if (s > 1) {
                    ring[tail] = v;
                    tail = (tail + 1) & 4095;
                    inQueue[v >>> 6] |= (1L << (v & 63));
                }
            }
        }
        while (head != tail) {
            int v = ring[head];
            head = (head + 1) & 4095;
            inQueue[v >>> 6] &= ~(1L << (v & 63));
            int lv = hatL[v] & 0xFF;
            if (lv <= 1) continue;
            int x = v & 15;
            int z = (v >>> 4) & 15;
            int y = v >>> 8;
            if (x > 0) tail = relaxLightNeighbor(v - 1, lv, packedLight, hatL, ring, inQueue, tail);
            if (x < 15) tail = relaxLightNeighbor(v + 1, lv, packedLight, hatL, ring, inQueue, tail);
            if (z > 0) tail = relaxLightNeighbor(v - 16, lv, packedLight, hatL, ring, inQueue, tail);
            if (z < 15) tail = relaxLightNeighbor(v + 16, lv, packedLight, hatL, ring, inQueue, tail);
            if (y > 0) tail = relaxLightNeighbor(v - 256, lv, packedLight, hatL, ring, inQueue, tail);
            if (y < 15) tail = relaxLightNeighbor(v + 256, lv, packedLight, hatL, ring, inQueue, tail);
        }
    }

    private static int relaxLightNeighbor(
            int u, int lv, byte[] packedLight, byte[] hatL, int[] ring, long[] inQueue, int tail
    ) {
        int opac = (packedLight[u] >>> 4) & 0x0F;
        int cand = lv - Math.max(1, opac);
        if (cand > (hatL[u] & 0xFF)) {
            hatL[u] = (byte) cand;
            if ((inQueue[u >>> 6] & (1L << (u & 63))) == 0L) {
                ring[tail] = u;
                tail = (tail + 1) & 4095;
                inQueue[u >>> 6] |= (1L << (u & 63));
            }
        }
        return tail;
    }

    private static final int[] GRAY_4 = {0, 1, 3, 2, 6, 7, 5, 4, 12, 13, 15, 14, 10, 11, 9, 8};
    private static final int[] UNGRAY_4 = new int[16];
    static {
        for (int i = 0; i < 16; i++) UNGRAY_4[GRAY_4[i]] = i;
    }

    private static byte[] encodeGrayPlanesSimple(byte[] yzxNibbles, int[] curveToYzx) {
        byte[] out = new byte[1 + 4 * 516];
        int pos = 1;
        int modes = 0;
        byte[] planeBits = new byte[SECTION_VOXELS];
        byte[] rleBuf = new byte[SECTION_VOXELS];
        for (int b = 0; b < 4; b++) {
            int ones = 0;
            for (int i = 0; i < SECTION_VOXELS; i++) {
                int bit = (GRAY_4[yzxNibbles[curveToYzx[i]] & 0x0F] >>> b) & 1;
                planeBits[i] = (byte) bit;
                ones += bit;
            }
            if (ones == 0 || ones == SECTION_VOXELS) {
                // mode 00: constant plane
                out[pos++] = (byte) (ones == 0 ? 0 : 1);
            } else {
                // mode 01: RLE (header: firstBit in bit 7, runLen-1 in bits 6..0; continuation if >= 128)
                int rPos = 0;
                int i = 0;
                int cur = planeBits[0];
                rleBuf[rPos++] = (byte) cur;
                while (i < SECTION_VOXELS) {
                    int start = i;
                    while (i < SECTION_VOXELS && planeBits[i] == cur) i++;
                    int run = i - start;
                    while (run >= 0x80) {
                        rleBuf[rPos++] = (byte) ((run & 0x7F) | 0x80);
                        run >>>= 7;
                    }
                    rleBuf[rPos++] = (byte) run;
                    cur ^= 1;
                }
                if (rPos < 512) {
                    modes |= (1 << (b * 2));
                    System.arraycopy(rleBuf, 0, out, pos, rPos);
                    pos += rPos;
                } else {
                    modes |= (3 << (b * 2));
                    for (int w = 0; w < 512; w++) {
                        int base = w << 3;
                        out[pos++] = (byte) (
                                planeBits[base]
                                        | (planeBits[base + 1] << 1)
                                        | (planeBits[base + 2] << 2)
                                        | (planeBits[base + 3] << 3)
                                        | (planeBits[base + 4] << 4)
                                        | (planeBits[base + 5] << 5)
                                        | (planeBits[base + 6] << 6)
                                        | (planeBits[base + 7] << 7));
                    }
                }
            }
        }
        out[0] = (byte) modes;
        return Arrays.copyOf(out, pos);
    }

    private static void decodeGrayPlanesSimple(byte[] src, int[] curveToYzx, byte[] outYzx) {
        int modes = src[0] & 0xFF;
        int pos = 1;
        Arrays.fill(outYzx, (byte) 0);
        for (int b = 0; b < 4; b++) {
            int mode = (modes >>> (b * 2)) & 0x03;
            int bitVal = 1 << b;
            if (mode == 0) {
                int v = src[pos++] & 1;
                if (v != 0) {
                    for (int i = 0; i < SECTION_VOXELS; i++) outYzx[i] |= (byte) bitVal;
                }
            } else if (mode == 1) {
                int cur = src[pos++] & 1;
                int i = 0;
                while (i < SECTION_VOXELS) {
                    int run = 0;
                    int shift = 0;
                    for (;;) {
                        int byt = src[pos++] & 0xFF;
                        run |= (byt & 0x7F) << shift;
                        if ((byt & 0x80) == 0) break;
                        shift += 7;
                    }
                    if (cur != 0) {
                        int end = i + run;
                        while (i < end) {
                            outYzx[curveToYzx[i++]] |= (byte) bitVal;
                        }
                    } else {
                        i += run;
                    }
                    cur ^= 1;
                }
            } else {
                for (int w = 0; w < 512; w++) {
                    int byt = src[pos++] & 0xFF;
                    int base = w << 3;
                    if (byt != 0) {
                        for (int k = 0; k < 8; k++) {
                            if (((byt >>> k) & 1) != 0) {
                                outYzx[curveToYzx[base + k]] |= (byte) bitVal;
                            }
                        }
                    }
                }
            }
        }
        for (int i = 0; i < SECTION_VOXELS; i++) {
            outYzx[i] = (byte) UNGRAY_4[outYzx[i] & 0x0F];
        }
    }

    /**
     * Candidate B1: 64-bit octant mask + byte-aligned zero-run + packed 4-bit non-zero residuals.
     * Each active 4x4x4 octant (64 voxels) encodes:
     *   - 8-byte (64-bit) non-zero bitmask
     *   - ceil(popcount / 2) bytes of packed 4-bit non-zero residuals (1..15)
     * Zero range coder, completely branchless-friendly bitscan!
     */
    private static byte[] encodeOctantBitmaskNibbles(byte[] residualYzx) {
        long octMask = 0L;
        long[] subMasks = new long[64];
        byte[] nibs = new byte[SECTION_VOXELS];
        int nibCount = 0;
        for (int oct = 0; oct < 64; oct++) {
            int ox = (oct & 3) << 2;
            int oz = ((oct >>> 2) & 3) << 2;
            int oy = ((oct >>> 4) & 3) << 2;
            long sm = 0L;
            int bitIdx = 0;
            for (int dy = 0; dy < 4; dy++) {
                int yBase = (oy + dy) << 8;
                for (int dz = 0; dz < 4; dz++) {
                    int zBase = yBase | ((oz + dz) << 4) | ox;
                    for (int dx = 0; dx < 4; dx++) {
                        int r = residualYzx[zBase + dx] & 0x0F;
                        if (r != 0) {
                            sm |= (1L << bitIdx);
                            nibs[nibCount++] = (byte) r;
                        }
                        bitIdx++;
                    }
                }
            }
            if (sm != 0L) {
                octMask |= (1L << oct);
                subMasks[oct] = sm;
            }
        }
        int activeOcts = Long.bitCount(octMask);
        byte[] out = new byte[8 + activeOcts * 8 + ((nibCount + 1) >>> 1)];
        int pos = 0;
        putLongLE(out, pos, octMask);
        pos += 8;
        long m = octMask;
        while (m != 0L) {
            int oct = Long.numberOfTrailingZeros(m);
            m &= m - 1L;
            putLongLE(out, pos, subMasks[oct]);
            pos += 8;
        }
        for (int i = 0; i < nibCount; i += 2) {
            int lo = nibs[i] & 0x0F;
            int hi = (i + 1 < nibCount) ? (nibs[i + 1] & 0x0F) : 0;
            out[pos++] = (byte) (lo | (hi << 4));
        }
        return out;
    }

    private static void decodeOctantBitmaskNibbles(byte[] src, byte[] outResidualYzx) {
        Arrays.fill(outResidualYzx, (byte) 0);
        long octMask = getLongLE(src, 0);
        if (octMask == 0L) return;
        int activeOcts = Long.bitCount(octMask);
        int subPos = 8;
        int nibBytePos = 8 + activeOcts * 8;
        int nibToggle = 0;
        int curByte = 0;
        long m = octMask;
        while (m != 0L) {
            int oct = Long.numberOfTrailingZeros(m);
            m &= m - 1L;
            long sm = getLongLE(src, subPos);
            subPos += 8;
            int ox = (oct & 3) << 2;
            int oz = ((oct >>> 2) & 3) << 2;
            int oy = ((oct >>> 4) & 3) << 2;
            while (sm != 0L) {
                int bitIdx = Long.numberOfTrailingZeros(sm);
                sm &= sm - 1L;
                int dx = bitIdx & 3;
                int dz = (bitIdx >>> 2) & 3;
                int dy = bitIdx >>> 4;
                int val;
                if (nibToggle == 0) {
                    curByte = src[nibBytePos++] & 0xFF;
                    val = curByte & 0x0F;
                    nibToggle = 1;
                } else {
                    val = curByte >>> 4;
                    nibToggle = 0;
                }
                outResidualYzx[((oy + dy) << 8) | ((oz + dz) << 4) | (ox + dx)] = (byte) val;
            }
        }
    }

    /**
     * Candidate B2: 64-bit octant mask + 12-context adaptive binary range coder (signed12)
     * over active octants only (zero octants skipped in O(1)).
     */
    private static byte[] encodeOctantSigned12Rc(byte[] residualYzx, RangeEnc rc, int[] probs) {
        long octMask = 0L;
        for (int oct = 0; oct < 64; oct++) {
            int ox = (oct & 3) << 2;
            int oz = ((oct >>> 2) & 3) << 2;
            int oy = ((oct >>> 4) & 3) << 2;
            boolean any = false;
            outer:
            for (int dy = 0; dy < 4; dy++) {
                int yBase = (oy + dy) << 8;
                for (int dz = 0; dz < 4; dz++) {
                    int zBase = yBase | ((oz + dz) << 4) | ox;
                    for (int dx = 0; dx < 4; dx++) {
                        if (residualYzx[zBase + dx] != 0) {
                            any = true;
                            break outer;
                        }
                    }
                }
            }
            if (any) octMask |= (1L << oct);
        }
        rc.reset();
        putLongLE(rc.buf, 0, octMask);
        rc.pos = 8;
        if (octMask == 0L) {
            return Arrays.copyOf(rc.buf, 8);
        }
        Arrays.fill(probs, 2048);
        long m = octMask;
        while (m != 0L) {
            int oct = Long.numberOfTrailingZeros(m);
            m &= m - 1L;
            int ox = (oct & 3) << 2;
            int oz = ((oct >>> 2) & 3) << 2;
            int oy = ((oct >>> 4) & 3) << 2;
            for (int dy = 0; dy < 4; dy++) {
                int yBase = (oy + dy) << 8;
                for (int dz = 0; dz < 4; dz++) {
                    int zBase = yBase | ((oz + dz) << 4) | ox;
                    for (int dx = 0; dx < 4; dx++) {
                        int r = residualYzx[zBase + dx] & 0x0F;
                        int signed = r >= 8 ? (r - 16) : r; // [-8..+7]
                        rc.encSigned12(probs, 0, signed);
                    }
                }
            }
        }
        int len = rc.finish();
        return Arrays.copyOf(rc.buf, len);
    }

    private static void decodeOctantSigned12Rc(byte[] src, RangeDec rc, int[] probs, byte[] outResidualYzx) {
        Arrays.fill(outResidualYzx, (byte) 0);
        long octMask = getLongLE(src, 0);
        if (octMask == 0L) return;
        Arrays.fill(probs, 2048);
        rc.init(src, 8, src.length - 8);
        long m = octMask;
        while (m != 0L) {
            int oct = Long.numberOfTrailingZeros(m);
            m &= m - 1L;
            int ox = (oct & 3) << 2;
            int oz = ((oct >>> 2) & 3) << 2;
            int oy = ((oct >>> 4) & 3) << 2;
            for (int dy = 0; dy < 4; dy++) {
                int yBase = (oy + dy) << 8;
                for (int dz = 0; dz < 4; dz++) {
                    int zBase = yBase | ((oz + dz) << 4) | ox;
                    for (int dx = 0; dx < 4; dx++) {
                        int signed = rc.decSigned12(probs, 0);
                        outResidualYzx[zBase + dx] = (byte) (signed & 0x0F);
                    }
                }
            }
        }
    }

    // =========================================================================
    // 4. Candidate C: LCD2 Entropy Backends (Frozen Huff12 vs RC vs Adaptive Huff12)
    // =========================================================================

    private static byte[] encodeByteStreamBinaryTreeRc(byte[] in, int len, RangeEnc rc, int[] probs256) {
        Arrays.fill(probs256, 2048);
        rc.reset();
        for (int i = 0; i < len; i++) {
            int b = in[i] & 0xFF;
            int ctx = 1;
            for (int bitPos = 7; bitPos >= 0; bitPos--) {
                int bit = (b >>> bitPos) & 1;
                rc.encBit(probs256, ctx, bit);
                ctx = (ctx << 1) | bit;
            }
        }
        int outLen = rc.finish();
        return Arrays.copyOf(rc.buf, outLen);
    }

    private static void decodeByteStreamBinaryTreeRc(byte[] src, int outLen, RangeDec rc, int[] probs256, byte[] dst) {
        Arrays.fill(probs256, 2048);
        rc.init(src, 0, src.length);
        for (int i = 0; i < outLen; i++) {
            int ctx = 1;
            for (int bitPos = 0; bitPos < 8; bitPos++) {
                int bit = rc.decBit(probs256, ctx);
                ctx = (ctx << 1) | bit;
            }
            dst[i] = (byte) (ctx & 0xFF);
        }
    }

    /**
     * Bitfield-context RC for LCD2 cmdStream: each byte is [litCode3 (7..5) | matchMode2 (4..3) | lenCode3 (2..0)].
     * Uses separate 8-node binary tree for litCode3 (ctx 1..7), 4-node tree for matchMode2 (ctx 8..11),
     * and 8-node tree for lenCode3 (ctx 12..19). Total 20 contexts!
     */
    private static byte[] encodeCmdBitfieldRc(byte[] in, int len, RangeEnc rc, int[] probs24) {
        Arrays.fill(probs24, 2048);
        rc.reset();
        for (int i = 0; i < len; i++) {
            int b = in[i] & 0xFF;
            // litCode3 (bits 7..5)
            int c = 1;
            for (int k = 7; k >= 5; k--) {
                int bit = (b >>> k) & 1;
                rc.encBit(probs24, c, bit);
                c = (c << 1) | bit;
            }
            // matchMode2 (bits 4..3)
            c = 1;
            for (int k = 4; k >= 3; k--) {
                int bit = (b >>> k) & 1;
                rc.encBit(probs24, 8 + c, bit);
                c = (c << 1) | bit;
            }
            // lenCode3 (bits 2..0)
            c = 1;
            for (int k = 2; k >= 0; k--) {
                int bit = (b >>> k) & 1;
                rc.encBit(probs24, 12 + c, bit);
                c = (c << 1) | bit;
            }
        }
        int outLen = rc.finish();
        return Arrays.copyOf(rc.buf, outLen);
    }

    private static void decodeCmdBitfieldRc(byte[] src, int outLen, RangeDec rc, int[] probs24, byte[] dst) {
        Arrays.fill(probs24, 2048);
        rc.init(src, 0, src.length);
        for (int i = 0; i < outLen; i++) {
            int c1 = 1;
            for (int k = 0; k < 3; k++) c1 = (c1 << 1) | rc.decBit(probs24, c1);
            int c2 = 1;
            for (int k = 0; k < 2; k++) c2 = (c2 << 1) | rc.decBit(probs24, 8 + c2);
            int c3 = 1;
            for (int k = 0; k < 3; k++) c3 = (c3 << 1) | rc.decBit(probs24, 12 + c3);
            dst[i] = (byte) (((c1 & 7) << 5) | ((c2 & 3) << 3) | (c3 & 7));
        }
    }

    private static int[] buildDecodeTable12FromLens(int[] lens) {
        int[] codes = new int[256];
        int[] blCount = new int[13];
        for (int i = 0; i < 256; i++) {
            int l = lens[i];
            if (l > 0 && l <= 12) blCount[l]++;
        }
        int[] nextCode = new int[13];
        int code = 0;
        for (int bits = 1; bits <= 12; bits++) {
            code = (code + blCount[bits - 1]) << 1;
            nextCode[bits] = code;
        }
        int[] table = new int[4096];
        for (int sym = 0; sym < 256; sym++) {
            int l = lens[sym];
            if (l > 0 && l <= 12) {
                int c = nextCode[l]++;
                codes[sym] = c;
                int entry = (sym << 8) | l;
                int shift = 12 - l;
                int start = c << shift;
                int count = 1 << shift;
                Arrays.fill(table, start, start + count, entry);
            }
        }
        return table;
    }

    /** Compact sparse table header for per-chunk Huffman12: 1B count + count*(1B sym + 4-bit len packed). */
    private static int sparseHuffHeaderBytes(int[] lens) {
        int active = 0;
        for (int l : lens) if (l > 0) active++;
        // Either 128 B flat nibble table or 1 + active + (active+1)/2 sparse
        return Math.min(128, 1 + active + ((active + 1) >>> 1));
    }

    // =========================================================================
    // 5. Utilities & Test Corpus Loader
    // =========================================================================

    private static void putLongLE(byte[] b, int off, long v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
        b[off + 4] = (byte) (v >>> 32);
        b[off + 5] = (byte) (v >>> 40);
        b[off + 6] = (byte) (v >>> 48);
        b[off + 7] = (byte) (v >>> 56);
    }

    private static long getLongLE(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24)
                | ((b[off + 4] & 0xFFL) << 32)
                | ((b[off + 5] & 0xFFL) << 40)
                | ((b[off + 6] & 0xFFL) << 48)
                | ((b[off + 7] & 0xFFL) << 56);
    }

    private static byte[] unpackNibbles4096(byte[] packed2048) {
        byte[] out = new byte[SECTION_VOXELS];
        for (int i = 0; i < 2048; i++) {
            int b = packed2048[i] & 0xFF;
            out[i << 1] = (byte) (b & 0x0F);
            out[(i << 1) | 1] = (byte) (b >>> 4);
        }
        return out;
    }

    private static boolean isUniform(byte[] a) {
        byte first = a[0];
        for (int i = 1; i < a.length; i++) if (a[i] != first) return false;
        return true;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> unwrapList(Object obj) {
        if (obj instanceof Nbt.NbtList nl) return nl.items;
        if (obj instanceof List<?> l) return (List<Object>) l;
        return List.of();
    }

    private record LightSample(byte[] nibs, byte[] packedLight, boolean isSky) {}

    private record Lcd2Streams(int regionIdx, byte[] lit, byte[] cmd, byte[] off, int rawBodyLen, int headerLen, byte[] fullChunkFrame) {}

    private record CorpusData(
            int regionCount,
            int chunkCount,
            long totalModeBWholeChunkBytes,
            List<long[]> heightmaps,
            List<LightSample> skySamples,
            List<LightSample> blkSamples,
            List<Lcd2Streams> lcd2Chunks,
            List<LcaSectionCodec.AtomicDictState> regionDicts
    ) {}

    private static CorpusData loadCorpus() throws IOException {
        Path liveRegionDir = Paths.get(
                System.getProperty("user.home"),
                "Desktop", "GameServers", "Minecraft", "testServer", "RTP-Paper", "26.3",
                "world", "dimensions", "minecraft", "overworld", "region");
        if (Files.isDirectory(liveRegionDir)) {
            List<Path> allRegs = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(liveRegionDir, "*.lca")) {
                for (Path p : ds) allRegs.add(p);
            }
            allRegs.sort(Comparator.comparing(p -> p.getFileName().toString()));
            if (!allRegs.isEmpty()) {
                return loadLiveCorpus(allRegs);
            }
        }
        return buildSyntheticCorpus();
    }

    private static CorpusData loadLiveCorpus(List<Path> allRegs) throws IOException {
        int maxRegions = Math.min(12, allRegs.size());
        int stride = Math.max(1, allRegs.size() / maxRegions);
        List<long[]> heightmaps = new ArrayList<>();
        List<LightSample> skySamples = new ArrayList<>();
        List<LightSample> blkSamples = new ArrayList<>();
        List<Lcd2Streams> lcd2Chunks = new ArrayList<>();
        List<LcaSectionCodec.AtomicDictState> regionDicts = new ArrayList<>();
        Map<String, Byte> lightClassCache = new HashMap<>(4096);
        long totalModeBWholeChunkBytes = 0;
        int regionCount = 0;
        int chunkCount = 0;

        for (int rIdx = 0; rIdx < allRegs.size() && regionCount < maxRegions; rIdx += stride) {
            Path regPath = allRegs.get(rIdx);
            byte[] regBytes = Files.readAllBytes(regPath);
            LcaSectionCodec.AtomicDictState regDict = new LcaSectionCodec.AtomicDictState();
            int regSlot = regionCount++;
            regionDicts.add(regDict);

            try (FileChannel ch = FileChannel.open(regPath, StandardOpenOption.READ)) {
                int chunksInReg = 0;
                for (int rz = 0; rz < 32 && chunksInReg < 24; rz += 5) {
                    for (int rx = 0; rx < 32; rx += 5) {
                        if (chunksInReg >= 24) {
                            break;
                        }
                        if (!LcaRegionReader.INSTANCE.isChunkGenerated(regBytes, rx, rz)) continue;
                        AnvilReader.ChunkEntry entry = LcaRegionReader.INSTANCE.readChunk(regBytes, rx, rz);
                        if (entry == null || entry.root == null) continue;
                        RawChunkFrameSnapshot snap = LcaRegionReader.readRawChunkFrameFromChannel(regPath, ch, rx, rz, null);
                        if (snap == null) continue;

                        chunksInReg++;
                        chunkCount++;

                        // 1. Heightmap
                        long[] mbnl = null;
                        if (entry.root.get("Heightmaps") instanceof Map<?, ?> hmMap
                                && hmMap.get("MOTION_BLOCKING_NO_LEAVES") instanceof long[] hmArr) {
                            mbnl = hmArr;
                        }
                        if (mbnl != null && mbnl.length >= 37) {
                            heightmaps.add(mbnl);
                        }

                        // 2. Non-constant SkyLight & BlockLight sections
                        for (Object secObj : unwrapList(entry.root.get("sections"))) {
                            if (!(secObj instanceof Map<?, ?> secMap)) continue;
                            if (!(secMap.get("block_states") instanceof Map<?, ?> bsMap)) continue;
                            byte[] sky2048 = (secMap.get("SkyLight") instanceof byte[] b && b.length == 2048) ? b : null;
                            byte[] blk2048 = (secMap.get("BlockLight") instanceof byte[] b && b.length == 2048) ? b : null;
                            if (sky2048 == null && blk2048 == null) continue;
                            byte[] skyNibs = sky2048 != null ? unpackNibbles4096(sky2048) : null;
                            byte[] blkNibs = blk2048 != null ? unpackNibbles4096(blk2048) : null;
                            boolean skyNonConst = skyNibs != null && !isUniform(skyNibs);
                            boolean blkNonConst = blkNibs != null && !isUniform(blkNibs);
                            if (!skyNonConst && !blkNonConst) continue;

                            List<Object> palItems = unwrapList(bsMap.get("palette"));
                            if (palItems.isEmpty()) continue;
                            byte[] palLight = new byte[palItems.size()];
                            for (int p = 0; p < palItems.size(); p++) {
                                String canon = AnvilReader.formatCanonicalBlockState(palItems.get(p));
                                if (canon == null) canon = "minecraft:air";
                                palLight[p] = lightClassCache.computeIfAbsent(canon, LcaAdr005EntropyPerfTradeoffTest::classifyOpacityEmission);
                            }
                            byte[] packedLight = new byte[SECTION_VOXELS];
                            if (palLight.length == 1 || !(bsMap.get("data") instanceof long[] packed)) {
                                Arrays.fill(packedLight, palLight[0]);
                            } else {
                                int bits = PackedPaletteDecoder.bitsPerEntry(palLight.length);
                                int perLong = 64 / bits;
                                long mask = (1L << bits) - 1L;
                                int vox = 0;
                                for (int w = 0; w < packed.length && vox < SECTION_VOXELS; w++) {
                                    long word = packed[w];
                                    int lim = Math.min(SECTION_VOXELS, vox + perLong);
                                    while (vox < lim) {
                                        packedLight[vox++] = palLight[(int) (word & mask)];
                                        word >>>= bits;
                                    }
                                }
                            }
                            if (skyNonConst) skySamples.add(new LightSample(skyNibs, packedLight, true));
                            if (blkNonConst) blkSamples.add(new LightSample(blkNibs, packedLight, false));
                        }

                        // 3. Extract raw LCD2 streams from uncompressed ChunkFrame body
                        byte[] cf = snap.chunkFrame();
                        int hdrLen = snap.sectionDirectoryEnd();
                        int bodyLen = cf.length - hdrLen;
                        if (bodyLen > 16) {
                            byte[] lcd2Frame = LcaSectionCodec.encodeAtomicDictBody(cf, hdrLen, bodyLen, regDict, true);
                            totalModeBWholeChunkBytes += hdrLen + lcd2Frame.length;
                            Lcd2Streams extracted = extractLcd2Streams(regSlot, cf, hdrLen, lcd2Frame, regDict);
                            if (extracted != null) {
                                lcd2Chunks.add(extracted);
                            }
                        }
                    }
                }
            }
        }
        return new CorpusData(regionCount, chunkCount, totalModeBWholeChunkBytes,
                heightmaps, skySamples, blkSamples, lcd2Chunks, regionDicts);
    }

    private static Lcd2Streams extractLcd2Streams(
            int regSlot, byte[] cf, int hdrLen, byte[] lcd2Frame, LcaSectionCodec.AtomicDictState dict
    ) throws CorruptRegionEntryException {
        if (lcd2Frame.length < 9 || (lcd2Frame[0] & 0xFF) != 0x4C || (lcd2Frame[1] & 0xFF) != 0x44) return null;
        int flags = lcd2Frame[2] & 0xFF;
        if ((flags & 0x04) != 0) return null; // raw passthrough
        int compBodyLen = ((lcd2Frame[3] & 0xFF) << 16) | ((lcd2Frame[4] & 0xFF) << 8) | (lcd2Frame[5] & 0xFF);
        int limit = Math.min(compBodyLen, lcd2Frame.length);
        int pos = 9;
        int seqCount = LcaSectionCodec.readVarInt(lcd2Frame, pos, limit);
        pos += LcaSectionCodec.varIntLen(seqCount);
        int litTotal = LcaSectionCodec.readVarInt(lcd2Frame, pos, limit);
        pos += LcaSectionCodec.varIntLen(litTotal);
        int offTotal = LcaSectionCodec.readVarInt(lcd2Frame, pos, limit);
        pos += LcaSectionCodec.varIntLen(offTotal);

        byte[] lit = new byte[litTotal];
        if ((flags & 0x01) != 0) {
            int cLen = LcaSectionCodec.readVarInt(lcd2Frame, pos, limit);
            pos += LcaSectionCodec.varIntLen(cLen);
            int[] dec = buildDecodeTable12FromLens(dict.litLen);
            LcaSectionCodec.decodeHuffmanStream12(lcd2Frame, pos, cLen, dec, lit, litTotal);
            pos += cLen;
        } else {
            System.arraycopy(lcd2Frame, pos, lit, 0, litTotal);
            pos += litTotal;
        }

        byte[] cmd = new byte[seqCount];
        if ((flags & 0x02) != 0) {
            int cLen = LcaSectionCodec.readVarInt(lcd2Frame, pos, limit);
            pos += LcaSectionCodec.varIntLen(cLen);
            int[] dec = buildDecodeTable12FromLens(dict.cmdLen);
            LcaSectionCodec.decodeHuffmanStream12(lcd2Frame, pos, cLen, dec, cmd, seqCount);
            pos += cLen;
        } else {
            System.arraycopy(lcd2Frame, pos, cmd, 0, seqCount);
            pos += seqCount;
        }

        byte[] off = new byte[offTotal];
        if ((flags & 0x08) != 0) {
            int cLen = LcaSectionCodec.readVarInt(lcd2Frame, pos, limit);
            pos += LcaSectionCodec.varIntLen(cLen);
            int[] dec = buildDecodeTable12FromLens(dict.offLen);
            LcaSectionCodec.decodeHuffmanStream12(lcd2Frame, pos, cLen, dec, off, offTotal);
        } else {
            System.arraycopy(lcd2Frame, pos, off, 0, offTotal);
        }
        return new Lcd2Streams(regSlot, lit, cmd, off, cf.length - hdrLen, hdrLen, cf);
    }

    private static CorpusData buildSyntheticCorpus() throws IOException {
        Random rng = new Random(0x5EED_0005L);
        List<long[]> heightmaps = new ArrayList<>();
        List<LightSample> skySamples = new ArrayList<>();
        List<LightSample> blkSamples = new ArrayList<>();
        List<Lcd2Streams> lcd2Chunks = new ArrayList<>();
        LcaSectionCodec.AtomicDictState dict = new LcaSectionCodec.AtomicDictState();
        long totalModeB = 0;
        for (int c = 0; c < 32; c++) {
            int[] cols = new int[256];
            int baseH = 64 + (c % 5) * 12;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    cols[(z << 4) | x] = baseH + (x / 3) + (z / 4) + (rng.nextInt(3) - 1);
                }
            }
            heightmaps.add(packHeightmapCols(cols, 37));

            byte[] packedLight = new byte[SECTION_VOXELS];
            for (int y = 0; y < 8; y++) {
                for (int xz = 0; xz < 256; xz++) {
                    packedLight[(y << 8) | xz] = (byte) (15 << 4); // solid ground
                }
            }
            packedLight[(8 << 8) | (8 << 4) | 8] = (byte) 14; // torch
            byte[] hatSky = new byte[SECTION_VOXELS];
            byte[] hatBlk = new byte[SECTION_VOXELS];
            int[] ring = new int[SECTION_VOXELS];
            long[] inQ = new long[64];
            predictSkyLightEikonal(packedLight, null, hatSky, ring, inQ);
            predictBlockLightEikonal(packedLight, hatBlk, ring, inQ);
            // Add realistic small perturbations
            for (int k = 0; k < 24; k++) {
                int idx = rng.nextInt(SECTION_VOXELS);
                hatSky[idx] = (byte) ((hatSky[idx] + (rng.nextBoolean() ? 1 : 15)) & 0x0F);
                hatBlk[idx] = (byte) ((hatBlk[idx] + (rng.nextBoolean() ? 1 : 15)) & 0x0F);
            }
            skySamples.add(new LightSample(hatSky, packedLight, true));
            blkSamples.add(new LightSample(hatBlk, packedLight, false));

            byte[] fakeCf = new byte[2400];
            for (int i = 0; i < fakeCf.length; i++) {
                fakeCf[i] = (byte) ((i % 7 == 0) ? rng.nextInt(256) : (i % 13));
            }
            byte[] lcd2 = LcaSectionCodec.encodeAtomicDictBody(fakeCf, 16, fakeCf.length - 16, dict, true);
            totalModeB += 16 + lcd2.length;
            Lcd2Streams s = extractLcd2Streams(0, fakeCf, 16, lcd2, dict);
            if (s != null) lcd2Chunks.add(s);
        }
        return new CorpusData(1, 32, totalModeB, heightmaps, skySamples, blkSamples, lcd2Chunks, List.of(dict));
    }

    // =========================================================================
    // 6. Main Empirical Benchmark Test
    // =========================================================================

    @Test
    @DisplayName("Empirical Ratio vs. Encode/Decode Latency Benchmark for Candidates A, B, C, D")
    void benchmarkAllAdr005Candidates() throws Exception {
        CorpusData corpus = loadCorpus();
        assertTrue(corpus.chunkCount > 0, "Corpus must contain at least 1 chunk");
        double avgModeBChunkBytes = (double) corpus.totalModeBWholeChunkBytes / corpus.chunkCount;

        // ---------------------------------------------------------------------
        // BASELINE REFERENCE: Whole-Chunk LCD2 Mode B Decode & Encode Latency
        // ---------------------------------------------------------------------
        int warmup = 8;
        int iters = 25;
        List<byte[]> encodedLcd2Frames = new ArrayList<>(corpus.lcd2Chunks.size());
        List<int[]> regLitDec = new ArrayList<>();
        List<int[]> regCmdDec = new ArrayList<>();
        List<int[]> regOffDec = new ArrayList<>();
        for (LcaSectionCodec.AtomicDictState d : corpus.regionDicts) {
            regLitDec.add(buildDecodeTable12FromLens(d.litLen));
            regCmdDec.add(buildDecodeTable12FromLens(d.cmdLen));
            regOffDec.add(buildDecodeTable12FromLens(d.offLen));
        }

        // Pre-encode whole chunks for LCD2 decode timing
        for (Lcd2Streams s : corpus.lcd2Chunks) {
            LcaSectionCodec.AtomicDictState d = corpus.regionDicts.get(s.regionIdx);
            byte[] enc = LcaSectionCodec.encodeAtomicDictBody(s.fullChunkFrame, s.headerLen, s.rawBodyLen, d, false);
            encodedLcd2Frames.add(enc);
        }

        long sink = 0;
        // Measure baseline LCD2 whole-chunk encode
        for (int w = 0; w < warmup; w++) {
            for (Lcd2Streams s : corpus.lcd2Chunks) {
                sink += LcaSectionCodec.encodeAtomicDictBody(
                        s.fullChunkFrame, s.headerLen, s.rawBodyLen, corpus.regionDicts.get(s.regionIdx), false).length;
            }
        }
        long t0 = System.nanoTime();
        for (int it = 0; it < iters; it++) {
            for (Lcd2Streams s : corpus.lcd2Chunks) {
                sink += LcaSectionCodec.encodeAtomicDictBody(
                        s.fullChunkFrame, s.headerLen, s.rawBodyLen, corpus.regionDicts.get(s.regionIdx), false).length;
            }
        }
        double baseLcd2EncUs = (System.nanoTime() - t0) / 1000.0 / (iters * corpus.lcd2Chunks.size());

        // Measure baseline LCD2 whole-chunk decode (Huffman + LZ77)
        List<LcaSectionCodec.ParsedHeaderAndDict> parsedDicts = new ArrayList<>();
        for (LcaSectionCodec.AtomicDictState d : corpus.regionDicts) {
            byte[] arenaCopy = Arrays.copyOf(d.arena, d.usedBytes);
            parsedDicts.add(new LcaSectionCodec.ParsedHeaderAndDict(
                    2, 0, 0, 0, -4, 24, 0, 0, 0L,
                    List.of(), List.of(), List.of(), List.of(), List.of(), arenaCopy));
        }
        for (int w = 0; w < warmup; w++) {
            for (int i = 0; i < encodedLcd2Frames.size(); i++) {
                Lcd2Streams s = corpus.lcd2Chunks.get(i);
                byte[] dec = LcaSectionCodec.decodeAtomicDictBody(
                        encodedLcd2Frames.get(i), 0, 0, 0, encodedLcd2Frames.get(i).length,
                        parsedDicts.get(s.regionIdx));
                sink += dec.length;
            }
        }
        t0 = System.nanoTime();
        for (int it = 0; it < iters; it++) {
            for (int i = 0; i < encodedLcd2Frames.size(); i++) {
                Lcd2Streams s = corpus.lcd2Chunks.get(i);
                byte[] dec = LcaSectionCodec.decodeAtomicDictBody(
                        encodedLcd2Frames.get(i), 0, 0, 0, encodedLcd2Frames.get(i).length,
                        parsedDicts.get(s.regionIdx));
                sink += dec.length;
            }
        }
        double baseLcd2DecUs = (System.nanoTime() - t0) / 1000.0 / (iters * encodedLcd2Frames.size());

        // ---------------------------------------------------------------------
        // STUDY A: 16x16 Heightmaps (Baseline 1D vs A1 MED-2D Byte-RLE vs A2 MED-2D RC)
        // ---------------------------------------------------------------------
        RangeEnc rcEnc = new RangeEnc(65536);
        RangeDec rcDec = new RangeDec();
        int[] probs12 = new int[12];
        int[] colsScratch = new int[256];

        List<byte[]> hmBase = new ArrayList<>(corpus.heightmaps.size());
        List<byte[]> hmA1 = new ArrayList<>(corpus.heightmaps.size());
        List<byte[]> hmA2 = new ArrayList<>(corpus.heightmaps.size());
        long hmBaseModeA = 0, hmA1ModeA = 0, hmA2ModeA = 0;
        long hmBaseModeB = 0, hmA1ModeB = 0, hmA2ModeB = 0;
        LcaSectionCodec.AtomicDictState hmDictBase = new LcaSectionCodec.AtomicDictState();
        LcaSectionCodec.AtomicDictState hmDictA1 = new LcaSectionCodec.AtomicDictState();
        LcaSectionCodec.AtomicDictState hmDictA2 = new LcaSectionCodec.AtomicDictState();

        for (long[] hm : corpus.heightmaps) {
            byte[] b0 = LcaRegionWriter.LcaLiveArchive.encodeDeltaHeightmapLongs(hm);
            byte[] b1 = encodeHeightmapMed2dByteRle(hm);
            byte[] b2 = encodeHeightmapMed2dRc(hm, rcEnc, probs12);
            // Bit-exact L_inf = 0 verification
            assertArrayEquals(hm, LcaSectionCodec.decodeDeltaHeightmapLongs(b0, 0, b0.length), "HM base mismatch");
            assertArrayEquals(hm, decodeHeightmapMed2dByteRle(b1, colsScratch), "HM A1 mismatch");
            assertArrayEquals(hm, decodeHeightmapMed2dRc(b2, rcDec, probs12, colsScratch), "HM A2 mismatch");
            hmBase.add(b0);
            hmA1.add(b1);
            hmA2.add(b2);
            hmBaseModeA += b0.length;
            hmA1ModeA += b1.length;
            hmA2ModeA += b2.length;
            hmBaseModeB += LcaSectionCodec.encodeAtomicDictBody(b0, 0, b0.length, hmDictBase, true).length;
            hmA1ModeB += LcaSectionCodec.encodeAtomicDictBody(b1, 0, b1.length, hmDictA1, true).length;
            hmA2ModeB += LcaSectionCodec.encodeAtomicDictBody(b2, 0, b2.length, hmDictA2, true).length;
        }

        int hmIters = 100;
        double hmBaseEncUs = benchUs(warmup, hmIters, corpus.heightmaps.size(), () -> {
            long s = 0;
            for (long[] hm : corpus.heightmaps) s += LcaRegionWriter.LcaLiveArchive.encodeDeltaHeightmapLongs(hm).length;
            return s;
        });
        double hmA1EncUs = benchUs(warmup, hmIters, corpus.heightmaps.size(), () -> {
            long s = 0;
            for (long[] hm : corpus.heightmaps) s += encodeHeightmapMed2dByteRle(hm).length;
            return s;
        });
        double hmA2EncUs = benchUs(warmup, hmIters, corpus.heightmaps.size(), () -> {
            long s = 0;
            for (long[] hm : corpus.heightmaps) s += encodeHeightmapMed2dRc(hm, rcEnc, probs12).length;
            return s;
        });

        double hmBaseDecUs = benchUs(warmup, hmIters, hmBase.size(), () -> {
            long s = 0;
            for (byte[] b : hmBase) s += LcaSectionCodec.decodeDeltaHeightmapLongs(b, 0, b.length).length;
            return s;
        });
        double hmA1DecUs = benchUs(warmup, hmIters, hmA1.size(), () -> {
            long s = 0;
            for (byte[] b : hmA1) s += decodeHeightmapMed2dByteRle(b, colsScratch).length;
            return s;
        });
        double hmA2DecUs = benchUs(warmup, hmIters, hmA2.size(), () -> {
            long s = 0;
            for (byte[] b : hmA2) s += decodeHeightmapMed2dRc(b, rcDec, probs12, colsScratch).length;
            return s;
        });

        // ---------------------------------------------------------------------
        // STUDY B: Eikonal Light Residuals (v2 GrayPlanes vs ADR-004 GrayPlanes vs B1 Octant-Bitmask vs B2 Octant-RC)
        // ---------------------------------------------------------------------
        List<LightSample> allLight = new ArrayList<>(corpus.skySamples.size() + corpus.blkSamples.size());
        allLight.addAll(corpus.skySamples);
        allLight.addAll(corpus.blkSamples);

        byte[] hatL = new byte[SECTION_VOXELS];
        int[] ring = new int[SECTION_VOXELS];
        long[] inQueue = new long[64];
        byte[] decScratch = new byte[SECTION_VOXELS];

        List<byte[]> lResiduals = new ArrayList<>(allLight.size());
        List<byte[]> lV2Gray = new ArrayList<>(allLight.size());
        List<byte[]> lAdr4Gray = new ArrayList<>(allLight.size());
        List<byte[]> lB1OctBit = new ArrayList<>(allLight.size());
        List<byte[]> lB2OctRc = new ArrayList<>(allLight.size());
        int[] curveH3 = LcaSectionCodec.CURVE_TO_YZX[LcaSectionCodec.CURVE_H3];
        int[] curveYH2 = LcaSectionCodec.CURVE_TO_YZX[LcaSectionCodec.CURVE_Y_H2];

        long lV2ModeA = 0, lAdr4ModeA = 0, lB1ModeA = 0, lB2ModeA = 0;
        long lV2ModeB = 0, lAdr4ModeB = 0, lB1ModeB = 0, lB2ModeB = 0;
        LcaSectionCodec.AtomicDictState lDictV2 = new LcaSectionCodec.AtomicDictState();
        LcaSectionCodec.AtomicDictState lDictAdr4 = new LcaSectionCodec.AtomicDictState();
        LcaSectionCodec.AtomicDictState lDictB1 = new LcaSectionCodec.AtomicDictState();
        LcaSectionCodec.AtomicDictState lDictB2 = new LcaSectionCodec.AtomicDictState();

        for (LightSample ls : allLight) {
            int[] curve = ls.isSky ? curveYH2 : curveH3;
            byte[] v2g = encodeGrayPlanesSimple(ls.nibs, curve);
            decodeGrayPlanesSimple(v2g, curve, decScratch);
            assertArrayEquals(ls.nibs, decScratch, "Light v2 GrayPlanes mismatch");

            if (ls.isSky) {
                predictSkyLightEikonal(ls.packedLight, null, hatL, ring, inQueue);
            } else {
                predictBlockLightEikonal(ls.packedLight, hatL, ring, inQueue);
            }
            byte[] res = new byte[SECTION_VOXELS];
            for (int i = 0; i < SECTION_VOXELS; i++) {
                res[i] = (byte) ((ls.nibs[i] - hatL[i]) & 0x0F);
            }
            lResiduals.add(res);

            byte[] adr4g = encodeGrayPlanesSimple(res, curve);
            decodeGrayPlanesSimple(adr4g, curve, decScratch);
            assertArrayEquals(res, decScratch, "Light ADR-004 GrayPlanes mismatch");

            byte[] b1 = encodeOctantBitmaskNibbles(res);
            decodeOctantBitmaskNibbles(b1, decScratch);
            assertArrayEquals(res, decScratch, "Light B1 OctantBitmask mismatch");

            byte[] b2 = encodeOctantSigned12Rc(res, rcEnc, probs12);
            decodeOctantSigned12Rc(b2, rcDec, probs12, decScratch);
            assertArrayEquals(res, decScratch, "Light B2 OctantRC mismatch");

            // Adaptive per-section best vs baseline GrayPlanes (zero-tax)
            byte[] adr4Best = adr4g.length + 2 < v2g.length ? adr4g : v2g;
            byte[] b1Best = b1.length + 2 < v2g.length ? b1 : v2g;
            byte[] b2Best = b2.length + 2 < v2g.length ? b2 : v2g;

            lV2Gray.add(v2g);
            lAdr4Gray.add(adr4g);
            lB1OctBit.add(b1);
            lB2OctRc.add(b2);

            lV2ModeA += v2g.length;
            lAdr4ModeA += adr4Best.length;
            lB1ModeA += b1Best.length;
            lB2ModeA += b2Best.length;

            lV2ModeB += LcaSectionCodec.encodeAtomicDictBody(v2g, 0, v2g.length, lDictV2, true).length;
            lAdr4ModeB += LcaSectionCodec.encodeAtomicDictBody(adr4Best, 0, adr4Best.length, lDictAdr4, true).length;
            lB1ModeB += LcaSectionCodec.encodeAtomicDictBody(b1Best, 0, b1Best.length, lDictB1, true).length;
            lB2ModeB += LcaSectionCodec.encodeAtomicDictBody(b2Best, 0, b2Best.length, lDictB2, true).length;
        }

        int lightIters = 10;
        double eikonalBfsUsPerSec = benchUs(3, lightIters, allLight.size(), () -> {
            long s = 0;
            for (LightSample ls : allLight) {
                if (ls.isSky) predictSkyLightEikonal(ls.packedLight, null, hatL, ring, inQueue);
                else predictBlockLightEikonal(ls.packedLight, hatL, ring, inQueue);
                s += hatL[0];
            }
            return s;
        });
        double lAdr4DecUsPerSec = benchUs(3, lightIters, lAdr4Gray.size(), () -> {
            long s = 0;
            for (int i = 0; i < lAdr4Gray.size(); i++) {
                decodeGrayPlanesSimple(lAdr4Gray.get(i), allLight.get(i).isSky ? curveYH2 : curveH3, decScratch);
                s += decScratch[0];
            }
            return s;
        });
        double lB1DecUsPerSec = benchUs(3, lightIters, lB1OctBit.size(), () -> {
            long s = 0;
            for (byte[] b : lB1OctBit) {
                decodeOctantBitmaskNibbles(b, decScratch);
                s += decScratch[0];
            }
            return s;
        });
        double lB2DecUsPerSec = benchUs(3, lightIters, lB2OctRc.size(), () -> {
            long s = 0;
            for (byte[] b : lB2OctRc) {
                decodeOctantSigned12Rc(b, rcDec, probs12, decScratch);
                s += decScratch[0];
            }
            return s;
        });

        // ---------------------------------------------------------------------
        // STUDY C: LCD2 Mode B Entropy Stage (Frozen Huff12 vs C1 cmdRC vs C2 FullRC vs C3 PerChunk Huff12)
        // ---------------------------------------------------------------------
        int[] probs24 = new int[24];
        int[] probs256 = new int[256];
        byte[] huffScratch = new byte[1 << 18];
        byte[] decOut = new byte[1 << 18];

        long cBaseLitBytes = 0, cBaseCmdBytes = 0, cBaseOffBytes = 0;
        long c1CmdRcBytes = 0;
        long c2LitRcBytes = 0, c2OffRcBytes = 0;
        long c3LitHuffBytes = 0, c3CmdHuffBytes = 0, c3OffHuffBytes = 0;

        List<byte[]> encBaseLit = new ArrayList<>(), encBaseCmd = new ArrayList<>(), encBaseOff = new ArrayList<>();
        List<byte[]> encC1Cmd = new ArrayList<>();
        List<byte[]> encC2Lit = new ArrayList<>(), encC2Off = new ArrayList<>();
        List<byte[]> encC3Lit = new ArrayList<>(), encC3Cmd = new ArrayList<>(), encC3Off = new ArrayList<>();
        List<int[]> c3LitDec = new ArrayList<>(), c3CmdDec = new ArrayList<>(), c3OffDec = new ArrayList<>();

        for (Lcd2Streams s : corpus.lcd2Chunks) {
            LcaSectionCodec.AtomicDictState d = corpus.regionDicts.get(s.regionIdx);

            // Baseline frozen 12-bit Huffman
            int bl = LcaSectionCodec.encodeHuffmanStream12(s.lit, s.lit.length, d.litCode, d.litLen, huffScratch, 0);
            byte[] blArr = Arrays.copyOf(huffScratch, bl);
            LcaSectionCodec.decodeHuffmanStream12(blArr, 0, bl, regLitDec.get(s.regionIdx), decOut, s.lit.length);
            assertArrayEquals(s.lit, Arrays.copyOf(decOut, s.lit.length), "LCD2 base lit mismatch");
            cBaseLitBytes += Math.min(bl, s.lit.length);
            encBaseLit.add(blArr);

            int bc = LcaSectionCodec.encodeHuffmanStream12(s.cmd, s.cmd.length, d.cmdCode, d.cmdLen, huffScratch, 0);
            byte[] bcArr = Arrays.copyOf(huffScratch, bc);
            LcaSectionCodec.decodeHuffmanStream12(bcArr, 0, bc, regCmdDec.get(s.regionIdx), decOut, s.cmd.length);
            assertArrayEquals(s.cmd, Arrays.copyOf(decOut, s.cmd.length), "LCD2 base cmd mismatch");
            cBaseCmdBytes += Math.min(bc, s.cmd.length);
            encBaseCmd.add(bcArr);

            int bo = LcaSectionCodec.encodeHuffmanStream12(s.off, s.off.length, d.offCode, d.offLen, huffScratch, 0);
            byte[] boArr = Arrays.copyOf(huffScratch, bo);
            LcaSectionCodec.decodeHuffmanStream12(boArr, 0, bo, regOffDec.get(s.regionIdx), decOut, s.off.length);
            assertArrayEquals(s.off, Arrays.copyOf(decOut, s.off.length), "LCD2 base off mismatch");
            cBaseOffBytes += Math.min(bo, s.off.length);
            encBaseOff.add(boArr);

            // C1: Bitfield RC on cmdStream
            byte[] cmdRc = encodeCmdBitfieldRc(s.cmd, s.cmd.length, rcEnc, probs24);
            decodeCmdBitfieldRc(cmdRc, s.cmd.length, rcDec, probs24, decOut);
            assertArrayEquals(s.cmd, Arrays.copyOf(decOut, s.cmd.length), "LCD2 C1 cmdRC mismatch");
            c1CmdRcBytes += Math.min(cmdRc.length, s.cmd.length);
            encC1Cmd.add(cmdRc);

            // C2: Full RC on lit + off (plus C1 cmd)
            byte[] litRc = encodeByteStreamBinaryTreeRc(s.lit, s.lit.length, rcEnc, probs256);
            decodeByteStreamBinaryTreeRc(litRc, s.lit.length, rcDec, probs256, decOut);
            assertArrayEquals(s.lit, Arrays.copyOf(decOut, s.lit.length), "LCD2 C2 litRC mismatch");
            c2LitRcBytes += Math.min(litRc.length, s.lit.length);
            encC2Lit.add(litRc);

            byte[] offRc = encodeByteStreamBinaryTreeRc(s.off, s.off.length, rcEnc, probs256);
            decodeByteStreamBinaryTreeRc(offRc, s.off.length, rcDec, probs256, decOut);
            assertArrayEquals(s.off, Arrays.copyOf(decOut, s.off.length), "LCD2 C2 offRC mismatch");
            c2OffRcBytes += Math.min(offRc.length, s.off.length);
            encC2Off.add(offRc);

            // C3: Per-chunk re-trained 12-bit Huffman (including sparse header cost!)
            int[] fL = new int[256], lL = new int[256], cL = new int[256];
            for (byte b : s.lit) fL[b & 0xFF]++;
            LcaSectionCodec.buildLengthLimitedHuffman12(fL, lL, cL);
            int c3l = LcaSectionCodec.encodeHuffmanStream12(s.lit, s.lit.length, cL, lL, huffScratch, 0);
            int c3lHdr = sparseHuffHeaderBytes(lL);
            c3LitHuffBytes += Math.min(c3l + c3lHdr, Math.min(bl, s.lit.length));
            encC3Lit.add(Arrays.copyOf(huffScratch, c3l));
            c3LitDec.add(buildDecodeTable12FromLens(lL));

            int[] fC = new int[256], lC = new int[256], cC = new int[256];
            for (byte b : s.cmd) fC[b & 0xFF]++;
            LcaSectionCodec.buildLengthLimitedHuffman12(fC, lC, cC);
            int c3c = LcaSectionCodec.encodeHuffmanStream12(s.cmd, s.cmd.length, cC, lC, huffScratch, 0);
            int c3cHdr = sparseHuffHeaderBytes(lC);
            c3CmdHuffBytes += Math.min(c3c + c3cHdr, Math.min(bc, s.cmd.length));
            encC3Cmd.add(Arrays.copyOf(huffScratch, c3c));
            c3CmdDec.add(buildDecodeTable12FromLens(lC));

            int[] fO = new int[256], lO = new int[256], cO = new int[256];
            for (byte b : s.off) fO[b & 0xFF]++;
            LcaSectionCodec.buildLengthLimitedHuffman12(fO, lO, cO);
            int c3o = LcaSectionCodec.encodeHuffmanStream12(s.off, s.off.length, cO, lO, huffScratch, 0);
            int c3oHdr = sparseHuffHeaderBytes(lO);
            c3OffHuffBytes += Math.min(c3o + c3oHdr, Math.min(bo, s.off.length));
            encC3Off.add(Arrays.copyOf(huffScratch, c3o));
            c3OffDec.add(buildDecodeTable12FromLens(lO));
        }

        int nLcd = corpus.lcd2Chunks.size();
        double cBaseHuffDecUs = benchUs(warmup, iters, nLcd, () -> {
            long s = 0;
            for (int i = 0; i < nLcd; i++) {
                Lcd2Streams st = corpus.lcd2Chunks.get(i);
                LcaSectionCodec.decodeHuffmanStream12(encBaseLit.get(i), 0, encBaseLit.get(i).length, regLitDec.get(st.regionIdx), decOut, st.lit.length);
                LcaSectionCodec.decodeHuffmanStream12(encBaseCmd.get(i), 0, encBaseCmd.get(i).length, regCmdDec.get(st.regionIdx), decOut, st.cmd.length);
                LcaSectionCodec.decodeHuffmanStream12(encBaseOff.get(i), 0, encBaseOff.get(i).length, regOffDec.get(st.regionIdx), decOut, st.off.length);
                s += decOut[0];
            }
            return s;
        });
        double c1DecUs = benchUs(warmup, iters, nLcd, () -> {
            long s = 0;
            for (int i = 0; i < nLcd; i++) {
                Lcd2Streams st = corpus.lcd2Chunks.get(i);
                LcaSectionCodec.decodeHuffmanStream12(encBaseLit.get(i), 0, encBaseLit.get(i).length, regLitDec.get(st.regionIdx), decOut, st.lit.length);
                decodeCmdBitfieldRc(encC1Cmd.get(i), st.cmd.length, rcDec, probs24, decOut);
                LcaSectionCodec.decodeHuffmanStream12(encBaseOff.get(i), 0, encBaseOff.get(i).length, regOffDec.get(st.regionIdx), decOut, st.off.length);
                s += decOut[0];
            }
            return s;
        });
        double c2DecUs = benchUs(warmup, iters, nLcd, () -> {
            long s = 0;
            for (int i = 0; i < nLcd; i++) {
                Lcd2Streams st = corpus.lcd2Chunks.get(i);
                decodeByteStreamBinaryTreeRc(encC2Lit.get(i), st.lit.length, rcDec, probs256, decOut);
                decodeCmdBitfieldRc(encC1Cmd.get(i), st.cmd.length, rcDec, probs24, decOut);
                decodeByteStreamBinaryTreeRc(encC2Off.get(i), st.off.length, rcDec, probs256, decOut);
                s += decOut[0];
            }
            return s;
        });
        // C3 decode includes rebuilding the 4K LUT when per-chunk tables are used
        double c3DecNoBuildUs = benchUs(warmup, iters, nLcd, () -> {
            long s = 0;
            for (int i = 0; i < nLcd; i++) {
                Lcd2Streams st = corpus.lcd2Chunks.get(i);
                LcaSectionCodec.decodeHuffmanStream12(encC3Lit.get(i), 0, encC3Lit.get(i).length, c3LitDec.get(i), decOut, st.lit.length);
                LcaSectionCodec.decodeHuffmanStream12(encC3Cmd.get(i), 0, encC3Cmd.get(i).length, c3CmdDec.get(i), decOut, st.cmd.length);
                LcaSectionCodec.decodeHuffmanStream12(encC3Off.get(i), 0, encC3Off.get(i).length, c3OffDec.get(i), decOut, st.off.length);
                s += decOut[0];
            }
            return s;
        });
        double c3TableBuildUs = benchUs(warmup, iters, nLcd, () -> {
            long s = 0;
            for (int i = 0; i < nLcd; i++) {
                Lcd2Streams st = corpus.lcd2Chunks.get(i);
                LcaSectionCodec.AtomicDictState d = corpus.regionDicts.get(st.regionIdx);
                s += buildDecodeTable12FromLens(d.litLen)[0];
                s += buildDecodeTable12FromLens(d.cmdLen)[0];
                s += buildDecodeTable12FromLens(d.offLen)[0];
            }
            return s;
        });

        // ---------------------------------------------------------------------
        // STUDY D: Non-Mutating Cost Estimator (sum(litLen[b])) vs Double-LCD2 Trial
        // ---------------------------------------------------------------------
        // Compare exact double-LCD2 trial time vs single-pass litLen[b] estimator + 1 LCD2 encode
        double doubleLcd2Us = baseLcd2EncUs * 2.0;
        double estCostOnlyUs = benchUs(warmup, iters, nLcd, () -> {
            long s = 0;
            for (Lcd2Streams st : corpus.lcd2Chunks) {
                int[] litLen = corpus.regionDicts.get(st.regionIdx).litLen;
                byte[] cf = st.fullChunkFrame;
                long bits = 0;
                for (int i = st.headerLen; i < cf.length; i++) {
                    int l = litLen[cf[i] & 0xFF];
                    bits += (l > 0 ? l : 12);
                }
                s += bits;
            }
            return s;
        });
        double singleLcd2PlusEstUs = baseLcd2EncUs + estCostOnlyUs * 2.0; // score both candidates, encode winner once

        // ---------------------------------------------------------------------
        // PRINT COMPREHENSIVE EVIDENCE TABLE
        // ---------------------------------------------------------------------
        long cBaseTotal = cBaseLitBytes + cBaseCmdBytes + cBaseOffBytes;
        long c1Total = cBaseLitBytes + c1CmdRcBytes + cBaseOffBytes;
        long c2Total = c2LitRcBytes + c1CmdRcBytes + c2OffRcBytes;
        long c3Total = c3LitHuffBytes + c3CmdHuffBytes + c3OffHuffBytes;
        double lightSecsPerChunk = (double) allLight.size() / corpus.chunkCount;

        System.out.printf(Locale.ROOT,
                "%n==============================================================================================%n"
                        + "=== [anvil-api-ADR-005] EMPIRICAL RATIO VS. LATENCY BENCHMARK (%d regions, %d chunks) ===%n"
                        + "  Baseline Whole-Chunk Mode B : avg %.1f B/chunk | LCD2 Enc=%.2f us/chunk | LCD2 Dec=%.2f us/chunk (sink=%d)%n"
                        + "----------------------------------------------------------------------------------------------%n"
                        + "  [STUDY A] 16x16 Heightmaps (n=%d chunks)%n"
                        + "    Base (1D Delta+RLE)     : ModeA=%,7d B | ModeB=%,7d B | Enc=%6.2f us | Dec=%6.2f us%n"
                        + "    A1   (MED-2D + ByteRLE) : ModeA=%,7d B (%+6.2f%%) | ModeB=%,7d B (%+6.2f%%, whole=%+5.2f%%) | Enc=%6.2f us | Dec=%6.2f us (%+.2f us)%n"
                        + "    A2   (MED-2D + 12CtxRC) : ModeA=%,7d B (%+6.2f%%) | ModeB=%,7d B (%+6.2f%%, whole=%+5.2f%%) | Enc=%6.2f us | Dec=%6.2f us (%+.2f us)%n"
                        + "----------------------------------------------------------------------------------------------%n"
                        + "  [STUDY B] Eikonal Light Residuals (n=%d non-const secs: %d Sky + %d Blk, %.2f secs/chunk)%n"
                        + "    Eikonal BFS Predictor   : %.2f us/sec (%.2f us/chunk)%n"
                        + "    Base v2 (GrayPlanes)    : ModeA=%,8d B | ModeB=%,8d B%n"
                        + "    ADR-004 (Eik+GrayPlanes): ModeA=%,8d B (%+6.2f%%) | ModeB=%,8d B (%+6.2f%%) | ResDec=%6.2f us/sec (%6.2f us/chunk)%n"
                        + "    B1 (Eik+OctantBitmask)  : ModeA=%,8d B (%+6.2f%% vs ADR4) | ModeB=%,8d B (%+6.2f%% vs ADR4, whole=%+5.2f%%) | ResDec=%6.2f us/sec (%+.2f us/chunk)%n"
                        + "    B2 (Eik+OctantSignedRC) : ModeA=%,8d B (%+6.2f%% vs ADR4) | ModeB=%,8d B (%+6.2f%% vs ADR4, whole=%+5.2f%%) | ResDec=%6.2f us/sec (%+.2f us/chunk)%n"
                        + "----------------------------------------------------------------------------------------------%n"
                        + "  [STUDY C] Mode B (LCD2) Entropy Stage (n=%d chunks across %d regions)%n"
                        + "    Base (Frozen 384B Huff) : Total=%,9d B (lit=%,d cmd=%,d off=%,d) | EntDec=%6.2f us/chunk%n"
                        + "    C1   (cmdStream RC only): Total=%,9d B (%+6.2f%%, whole=%+5.2f%%) | EntDec=%6.2f us/chunk (%+.2f us, %.2fx)%n"
                        + "    C2   (Full 3-Stream RC) : Total=%,9d B (%+6.2f%%, whole=%+5.2f%%) | EntDec=%6.2f us/chunk (%+.2f us, %.2fx)%n"
                        + "    C3   (PerChunk Huff12)  : Total=%,9d B (%+6.2f%%, whole=%+5.2f%%) | EntDec=%6.2f us + Build=%6.2f us = %6.2f us/chunk (%+.2f us)%n"
                        + "----------------------------------------------------------------------------------------------%n"
                        + "  [STUDY D] Non-Mutating Cost Estimator (Zero Wire Change, 0 us Decode Impact)%n"
                        + "    Double-LCD2 Trial Enc   : %6.2f us/chunk%n"
                        + "    Est (litLen[b]) + 1 LCD2: %6.2f us/chunk (EstOnly=%.2f us/cand, %.1f%% faster encode)%n"
                        + "==============================================================================================%n",
                corpus.regionCount, corpus.chunkCount, avgModeBChunkBytes, baseLcd2EncUs, baseLcd2DecUs, sink,
                corpus.heightmaps.size(),
                hmBaseModeA, hmBaseModeB, hmBaseEncUs, hmBaseDecUs,
                hmA1ModeA, pct(hmA1ModeA, hmBaseModeA), hmA1ModeB, pct(hmA1ModeB, hmBaseModeB),
                100.0 * (hmA1ModeB - hmBaseModeB) / Math.max(1, corpus.totalModeBWholeChunkBytes),
                hmA1EncUs, hmA1DecUs, hmA1DecUs - hmBaseDecUs,
                hmA2ModeA, pct(hmA2ModeA, hmBaseModeA), hmA2ModeB, pct(hmA2ModeB, hmBaseModeB),
                100.0 * (hmA2ModeB - hmBaseModeB) / Math.max(1, corpus.totalModeBWholeChunkBytes),
                hmA2EncUs, hmA2DecUs, hmA2DecUs - hmBaseDecUs,
                allLight.size(), corpus.skySamples.size(), corpus.blkSamples.size(), lightSecsPerChunk,
                eikonalBfsUsPerSec, eikonalBfsUsPerSec * lightSecsPerChunk,
                lV2ModeA, lV2ModeB,
                lAdr4ModeA, pct(lAdr4ModeA, lV2ModeA), lAdr4ModeB, pct(lAdr4ModeB, lV2ModeB),
                lAdr4DecUsPerSec, lAdr4DecUsPerSec * lightSecsPerChunk,
                lB1ModeA, pct(lB1ModeA, lAdr4ModeA), lB1ModeB, pct(lB1ModeB, lAdr4ModeB),
                100.0 * (lB1ModeB - lAdr4ModeB) / Math.max(1, corpus.totalModeBWholeChunkBytes),
                lB1DecUsPerSec, (lB1DecUsPerSec - lAdr4DecUsPerSec) * lightSecsPerChunk,
                lB2ModeA, pct(lB2ModeA, lAdr4ModeA), lB2ModeB, pct(lB2ModeB, lAdr4ModeB),
                100.0 * (lB2ModeB - lAdr4ModeB) / Math.max(1, corpus.totalModeBWholeChunkBytes),
                lB2DecUsPerSec, (lB2DecUsPerSec - lAdr4DecUsPerSec) * lightSecsPerChunk,
                nLcd, corpus.regionCount,
                cBaseTotal, cBaseLitBytes, cBaseCmdBytes, cBaseOffBytes, cBaseHuffDecUs,
                c1Total, pct(c1Total, cBaseTotal),
                100.0 * (c1Total - cBaseTotal) / Math.max(1, corpus.totalModeBWholeChunkBytes),
                c1DecUs, c1DecUs - cBaseHuffDecUs, c1DecUs / Math.max(0.01, cBaseHuffDecUs),
                c2Total, pct(c2Total, cBaseTotal),
                100.0 * (c2Total - cBaseTotal) / Math.max(1, corpus.totalModeBWholeChunkBytes),
                c2DecUs, c2DecUs - cBaseHuffDecUs, c2DecUs / Math.max(0.01, cBaseHuffDecUs),
                c3Total, pct(c3Total, cBaseTotal),
                100.0 * (c3Total - cBaseTotal) / Math.max(1, corpus.totalModeBWholeChunkBytes),
                c3DecNoBuildUs, c3TableBuildUs, c3DecNoBuildUs + c3TableBuildUs,
                (c3DecNoBuildUs + c3TableBuildUs) - cBaseHuffDecUs,
                doubleLcd2Us, singleLcd2PlusEstUs, estCostOnlyUs,
                100.0 * (doubleLcd2Us - singleLcd2PlusEstUs) / Math.max(0.01, doubleLcd2Us));
    }

    @FunctionalInterface
    private interface LongSupplierThrowing {
        long get() throws Exception;
    }

    private static double benchUs(int warmup, int iters, int count, LongSupplierThrowing fn) throws Exception {
        if (count == 0) return 0.0;
        long acc = 0;
        for (int w = 0; w < warmup; w++) acc += fn.get();
        long t0 = System.nanoTime();
        for (int it = 0; it < iters; it++) acc += fn.get();
        long dt = System.nanoTime() - t0;
        if (acc == Long.MIN_VALUE) System.out.print(""); // prevent DCE
        return dt / 1000.0 / (iters * count);
    }

    private static double pct(long revised, long base) {
        return 100.0 * (revised - base) / Math.max(1, base);
    }

    @Test
    @DisplayName("anvil-api-ADR-006: Empirical A/B benchmark of factored Layer 2 + pure-ID palettes and FEATURE_GRAY_BITPLANES (Mode A & Mode B)")
    void evaluateGrayBitplanesModeAModeBTradeoff() throws Exception {
        byte[] mcaBytes = loadRealFixture("1_21_R1");
        List<int[]> coords = new ArrayList<>();
        List<LinkedHashMap<String, Object>> roots = new ArrayList<>();
        for (int rz = 0; rz < 32 && coords.size() < 64; rz++) {
            for (int rx = 0; rx < 32 && coords.size() < 64; rx++) {
                AnvilReader.ChunkEntry ce = AnvilReader.readChunkEntry(mcaBytes, rx, rz);
                if (ce != null) {
                    coords.add(new int[] {rx, rz});
                    roots.add(ce.root);
                }
            }
        }
        assertFalse(coords.isEmpty());

        // Also build a 16-chunk synthetic stratified structure/schematic corpus (smooth multi-material builds)
        List<int[]> structCoords = new ArrayList<>();
        List<LinkedHashMap<String, Object>> structRoots = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            int rx = i & 3;
            int rz = i >>> 2;
            structCoords.add(new int[] {rx, rz});
            structRoots.add(buildSyntheticStratifiedStructureChunk(rx, rz, 4671));
        }

        runAdr006AbSuite("Real Overworld 1_21_R1 (64 chunks)", coords, roots, 3953);
        runAdr006AbSuite("Synthetic Stratified Structure (16 chunks)", structCoords, structRoots, 4671);
    }

    private static void runAdr006AbSuite(
            String label,
            List<int[]> coords,
            List<LinkedHashMap<String, Object>> roots,
            int dataVersion
    ) throws Exception {
        int n = coords.size();
        String[] variantNames = {
            "v2 Mode A (STATIC_CORE)",
            "v2 Mode B (STATIC_CORE)",
            "v3 Base Mode A (Factored+PureID)",
            "v3 Base Mode B (Factored+PureID)",
            "v3+Gray Mode A (SEC_EXT_0x02)",
            "v3+Gray Mode B (SEC_EXT_0x02)"
        };
        int[] versions = {
            LcaRegionReader.LCA_FORMAT_VERSION_V2,
            LcaRegionReader.LCA_FORMAT_VERSION_V2,
            LcaRegionReader.LCA_FORMAT_VERSION_V3,
            LcaRegionReader.LCA_FORMAT_VERSION_V3,
            LcaRegionReader.LCA_FORMAT_VERSION_V3,
            LcaRegionReader.LCA_FORMAT_VERSION_V3
        };
        int[] lcd2Levels = {0, 1, 0, 1, 0, 1};
        int[] featMasks = {
            LcaV3Codec.DEFAULT_V3_FEATURES,
            LcaV3Codec.DEFAULT_V3_FEATURES,
            LcaV3Codec.DEFAULT_V3_FEATURES,
            LcaV3Codec.DEFAULT_V3_FEATURES,
            LcaV3Codec.DEFAULT_V3_FEATURES | LcaV3Codec.FEATURE_GRAY_BITPLANES,
            LcaV3Codec.DEFAULT_V3_FEATURES | LcaV3Codec.FEATURE_GRAY_BITPLANES
        };

        byte[][] archives = new byte[variantNames.length][];
        double[] encUs = new double[variantNames.length];
        double[] nbtRdUs = new double[variantNames.length];
        double[] viewRdUs = new double[variantNames.length];
        for (int v = 0; v < variantNames.length; v++) {
            final int fv = v;
            LcaV3Codec.setThreadFeatureMask(featMasks[fv]);
            try (LcaRegionWriter.LcaLiveArchive arch = new LcaRegionWriter.LcaLiveArchive(
                    0, 0, -4, 24, dataVersion, lcd2Levels[fv],
                    LcaRegionReader.STORAGE_KIND_REGION_VOXEL,
                    LcaRegionWriter.DEFAULT_ROOT_DICT_CAPACITY_BYTES,
                    versions[fv])) {
                for (int i = 0; i < n; i++) {
                    int[] c = coords.get(i);
                    arch.saveChunkInitial(c[0], c[1], roots.get(i));
                }
                archives[fv] = arch.toByteArray();
                encUs[fv] = benchUs(1, 2, n, () -> {
                    long acc = 0;
                    for (int i = 0; i < n; i++) {
                        int[] c = coords.get(i);
                        acc += arch.saveChunkCoW(c[0], c[1], roots.get(i));
                    }
                    return acc;
                });
            } finally {
                LcaV3Codec.resetThreadFeatureMask();
            }
            // Lossless L_inf = 0 verification on every chunk
            for (int i = 0; i < n; i++) {
                int[] c = coords.get(i);
                AnvilChunkView exp = AnvilReader.toView(roots.get(i));
                AnvilChunkView act = LcaRegionReader.readChunkView(archives[fv], c[0], c[1]);
                assertNotNull(act);
                for (int y = -64; y < 320; y += 37) {
                    assertEquals(exp.blockIdAt(5, y, 7), act.blockIdAt(5, y, 7));
                }
                byte[] nbt = LcaRegionReader.INSTANCE.readChunkNbtBytes(archives[fv], c[0], c[1]);
                assertNotNull(nbt);
            }
            nbtRdUs[fv] = benchUs(1, 3, n, () -> {
                long acc = 0;
                for (int i = 0; i < n; i++) {
                    int[] c = coords.get(i);
                    acc += LcaRegionReader.INSTANCE.readChunkNbtBytes(archives[fv], c[0], c[1]).length;
                }
                return acc;
            });
            viewRdUs[fv] = benchUs(1, 3, n, () -> {
                long acc = 0;
                for (int i = 0; i < n; i++) {
                    int[] c = coords.get(i);
                    acc += LcaRegionReader.readChunkView(archives[fv], c[0], c[1]).sections().size();
                }
                return acc;
            });
        }

        System.out.printf(Locale.ROOT,
                "%n================ [anvil-api-ADR-006 A/B BENCHMARK: %s] ================%n"
                        + "  %-34s | %10s | %8s | %10s | %10s | %10s%n",
                label, "Variant", "Size (B)", "vs v2", "CoW Save", "RawNBT Rd", "View Rd");
        for (int v = 0; v < variantNames.length; v++) {
            long refSize = archives[v % 2].length;
            System.out.printf(Locale.ROOT,
                    "  %-34s | %,10d | %+7.2f%% | %7.2f us | %7.2f us | %7.2f us%n",
                    variantNames[v], archives[v].length, pct(archives[v].length, refSize),
                    encUs[v], nbtRdUs[v], viewRdUs[v]);
        }
        System.out.println("==============================================================================================");
    }

    private static LinkedHashMap<String, Object> buildSyntheticStratifiedStructureChunk(int rx, int rz, int dv) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", dv);
        root.put("xPos", rx);
        root.put("zPos", rz);
        root.put("yPos", -4);
        root.put("Status", "minecraft:full");
        List<Object> sections = new ArrayList<>();
        String[] mats = {
            "minecraft:air", "minecraft:stone_bricks", "minecraft:mossy_stone_bricks", "minecraft:cracked_stone_bricks",
            "minecraft:deepslate_bricks", "minecraft:deepslate_tiles", "minecraft:polished_deepslate", "minecraft:cobbled_deepslate",
            "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:dark_oak_planks", "minecraft:stripped_oak_log[axis=y]",
            "minecraft:glass_pane[east=true,north=false,south=false,waterlogged=false,west=true]",
            "create:cogwheel[axis=y,waterlogged=false]",
            "create:large_cogwheel[axis=y,waterlogged=false]",
            "create:brass_casing"
        };
        for (int sy = 0; sy < 8; sy++) {
            LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
            sec.put("Y", (byte) sy);
            List<Object> pal = new ArrayList<>();
            for (String m : mats) {
                pal.add(LcaSectionCodec.parseCanonicalStateToNbtCompound(m));
            }
            long[] packed = new long[256];
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int yzx = (y << 8) | (z << 4) | x;
                        // Stepped 3D wall/floor bands with smooth adjacent transitions across 16 states
                        int s = ((y < 4) ? 0 : (((y - 4) + ((x + z) >>> 3) + (sy & 3)) % 16));
                        packed[yzx / 16] |= ((long) s) << ((yzx % 16) * 4);
                    }
                }
            }
            LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
            bs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, pal));
            bs.put("data", packed);
            sec.put("block_states", bs);
            LinkedHashMap<String, Object> biomes = new LinkedHashMap<>();
            biomes.put("palette", new Nbt.NbtList(Nbt.TAG_STRING, List.of("terralith:volcanic_peaks")));
            sec.put("biomes", biomes);
            sections.add(sec);
        }
        root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, sections));
        return root;
    }

    private static byte[] loadRealFixture(String dirName) throws IOException {
        String resource = "/anvil/real/" + dirName + "/r.0.0.mca";
        try (InputStream in = LcaAdr005EntropyPerfTradeoffTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Fixture not found on classpath: " + resource);
            }
            return in.readAllBytes();
        }
    }
}
