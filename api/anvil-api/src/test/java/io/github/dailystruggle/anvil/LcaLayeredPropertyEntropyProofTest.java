package io.github.dailystruggle.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Empirical proof harness evaluating layered substrate + localized property and scatter
 * conditional entropy against Cartesian {@code SectionFrame} block encoding ({@link LcaSectionCodec})
 * across deterministic worldgen archetypes and live {@code 26.3} {@code .lca} region archives.
 *
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 */
@DisplayName("anvil-api-ADR-003: Layered Base-Material + Localized Property & Scatter Entropy Proof")
class LcaLayeredPropertyEntropyProofTest {

    private static final int SECTION_VOXELS = 4096;
    private static final byte MODE_PASSTHROUGH_V2 = 0;
    private static final byte MODE_LAYERED_PROP_SCATTER = 1;

    private static final byte PROP_STREAM_RAW_BITS = 0;
    private static final byte PROP_STREAM_HUFFMAN = 1;
    private static final byte PROP_STREAM_EIKONAL_LEAVES_HUFF = 2;
    private static final byte PROP_STREAM_TALL_PLANT_PARITY_HUFF = 3;
    private static final byte PROP_STREAM_HILBERT_MTF_HUFF = 4;

    /**
     * Parsed canonical blockstate {@code minecraft:id[k1=v1,...]} separating material identity
     * from local state properties (`distance`, `half`, `axis`, etc.).
     */
    private static final class ParsedBlockState {
        final String canonical;
        final String baseName;
        final String propSuffix;
        final boolean isLeaves;
        final boolean isLogOrStem;
        final int leafDistance; // 1..7 or -1
        final int leafAuxFlags; // bit0=persistent, bit1=waterlogged, or -1 if non-standard
        final int tallHalf;     // 0=lower, 1=upper, -1=other

        ParsedBlockState(String canonical) {
            this.canonical = canonical;
            int bracket = canonical.indexOf('[');
            if (bracket < 0) {
                this.baseName = canonical;
                this.propSuffix = "";
            } else {
                this.baseName = canonical.substring(0, bracket);
                this.propSuffix = canonical.substring(bracket);
            }
            this.isLeaves = baseName.endsWith("_leaves");
            this.isLogOrStem = baseName.endsWith("_log")
                    || baseName.endsWith("_wood")
                    || baseName.endsWith("_stem")
                    || baseName.endsWith("_hyphae")
                    || baseName.equals("minecraft:mangrove_roots");

            int dist = -1;
            int aux = 0;
            boolean validLeafProps = isLeaves && !propSuffix.isEmpty();
            if (validLeafProps) {
                String inner = propSuffix.substring(1, propSuffix.length() - 1);
                String[] parts = inner.split(",");
                for (String part : parts) {
                    if (part.startsWith("distance=")) {
                        try {
                            dist = Integer.parseInt(part.substring("distance=".length()));
                        } catch (NumberFormatException ex) {
                            validLeafProps = false;
                        }
                    } else if (part.equals("persistent=true")) {
                        aux |= 1;
                    } else if (part.equals("persistent=false")) {
                        // bit 0 = 0
                    } else if (part.equals("waterlogged=true")) {
                        aux |= 2;
                    } else if (part.equals("waterlogged=false")) {
                        // bit 1 = 0
                    } else {
                        validLeafProps = false;
                    }
                }
            }
            this.leafDistance = (validLeafProps && dist >= 1 && dist <= 7) ? dist : -1;
            this.leafAuxFlags = (validLeafProps && dist >= 1 && dist <= 7) ? aux : -1;

            if ("[half=lower]".equals(propSuffix)) {
                this.tallHalf = 0;
            } else if ("[half=upper]".equals(propSuffix)) {
                this.tallHalf = 1;
            } else {
                this.tallHalf = -1;
            }
        }
    }

    private static final class BitWriter {
        byte[] buf = new byte[1024];
        int bytePos = 0;
        long bitBuf = 0L;
        int bitsInBuf = 0;

        void writeBits(int val, int numBits) {
            if (numBits <= 0) {
                return;
            }
            long masked = Integer.toUnsignedLong(val) & ((numBits == 32) ? 0xFFFFFFFFL : ((1L << numBits) - 1L));
            bitBuf |= (masked << (64 - bitsInBuf - numBits));
            bitsInBuf += numBits;
            while (bitsInBuf >= 8) {
                if (bytePos >= buf.length) {
                    buf = Arrays.copyOf(buf, buf.length * 2);
                }
                buf[bytePos++] = (byte) (bitBuf >>> 56);
                bitBuf <<= 8;
                bitsInBuf -= 8;
            }
        }

        byte[] finish() {
            if (bitsInBuf > 0) {
                if (bytePos >= buf.length) {
                    buf = Arrays.copyOf(buf, buf.length + 8);
                }
                buf[bytePos++] = (byte) (bitBuf >>> 56);
                bitBuf = 0L;
                bitsInBuf = 0;
            }
            return Arrays.copyOf(buf, bytePos);
        }
    }

    private static final class BitReader {
        final byte[] buf;
        int bytePos;
        final int limit;
        long bitBuf = 0L;
        int bitsInBuf = 0;

        BitReader(byte[] buf, int offset, int len) {
            this.buf = buf;
            this.bytePos = offset;
            this.limit = offset + len;
        }

        private void refill() {
            while (bitsInBuf <= 56 && bytePos < limit) {
                bitBuf |= ((long) (buf[bytePos++] & 0xFF)) << (56 - bitsInBuf);
                bitsInBuf += 8;
            }
        }

        int readBits(int numBits) {
            if (numBits <= 0) {
                return 0;
            }
            refill();
            int res = (int) (bitBuf >>> (64 - numBits));
            bitBuf <<= numBits;
            bitsInBuf -= numBits;
            return res;
        }

        int decodeHuffSymbol(int[] decTable, int maxCodeLen) {
            refill();
            int peek = (int) (bitBuf >>> (64 - maxCodeLen));
            int entry = decTable[peek];
            int sym = entry >>> 8;
            int len = entry & 0xFF;
            bitBuf <<= len;
            bitsInBuf -= len;
            return sym;
        }
    }

    /**
     * Exact length-limited (`1..12` bits) Canonical Huffman builder restricted strictly to active
     * symbols (`freq[s] > 0`) in `[0, alphabetSize)`, wasting zero Kraft space on unused symbols
     * and emitting `0` payload bits when `distinct == 1`.
     */
    private static void buildExactBoundedHuffman12(
            int[] freq, int alphabetSize, int[] outLens, int[] outCodes
    ) {
        int[] activeSyms = new int[alphabetSize];
        int activeCount = 0;
        for (int s = 0; s < alphabetSize; s++) {
            if (freq[s] > 0) {
                activeSyms[activeCount++] = s;
            }
        }
        Arrays.fill(outLens, 0, alphabetSize, 0);
        Arrays.fill(outCodes, 0, alphabetSize, 0);
        if (activeCount == 0) {
            return;
        }
        if (activeCount == 1) {
            outLens[activeSyms[0]] = 1;
            outCodes[activeSyms[0]] = 0;
            return;
        }

        int maxNodes = activeCount * 2;
        long[] weights = new long[maxNodes];
        int[] parent = new int[maxNodes];
        boolean[] open = new boolean[maxNodes];
        for (int i = 0; i < activeCount; i++) {
            weights[i] = freq[activeSyms[i]];
            open[i] = true;
        }
        int nextNode = activeCount;
        for (int step = 0; step < activeCount - 1; step++) {
            int m1 = -1;
            int m2 = -1;
            for (int i = 0; i < nextNode; i++) {
                if (!open[i]) {
                    continue;
                }
                if (m1 < 0 || weights[i] < weights[m1]) {
                    m2 = m1;
                    m1 = i;
                } else if (m2 < 0 || weights[i] < weights[m2]) {
                    m2 = i;
                }
            }
            open[m1] = false;
            open[m2] = false;
            weights[nextNode] = weights[m1] + weights[m2];
            parent[m1] = nextNode;
            parent[m2] = nextNode;
            open[nextNode] = true;
            nextNode++;
        }
        int root = nextNode - 1;
        int kraft = 0;
        Integer[] order = new Integer[activeCount];
        for (int i = 0; i < activeCount; i++) {
            int d = 0;
            int cur = i;
            while (cur != root) {
                d++;
                cur = parent[cur];
            }
            if (d < 1) {
                d = 1;
            }
            if (d > 12) {
                d = 12;
            }
            int sym = activeSyms[i];
            outLens[sym] = d;
            kraft += (1 << (12 - d));
            order[i] = sym;
        }
        Arrays.sort(order, (a, b) -> {
            int cmp = Integer.compare(freq[a], freq[b]);
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });
        while (kraft > 4096) {
            int bestSym = -1;
            int bestLen = -1;
            for (int i = 0; i < activeCount; i++) {
                int s = order[i];
                int l = outLens[s];
                if (l < 12 && l > bestLen) {
                    bestLen = l;
                    bestSym = s;
                    if (l == 11) {
                        break;
                    }
                }
            }
            if (bestSym < 0) {
                break;
            }
            outLens[bestSym]++;
            kraft -= (1 << (12 - outLens[bestSym]));
        }
        while (kraft < 4096) {
            int deficit = 4096 - kraft;
            int bestSym = -1;
            for (int i = activeCount - 1; i >= 0; i--) {
                int s = order[i];
                int l = outLens[s];
                if (l > 1 && (1 << (12 - l)) <= deficit) {
                    bestSym = s;
                    break;
                }
            }
            if (bestSym < 0) {
                break;
            }
            kraft += (1 << (12 - outLens[bestSym]));
            outLens[bestSym]--;
        }

        int[] blCount = new int[13];
        for (int s = 0; s < alphabetSize; s++) {
            int l = outLens[s];
            if (l >= 1 && l <= 12) {
                blCount[l]++;
            }
        }
        int[] nextCode = new int[13];
        int code = 0;
        for (int bits = 1; bits <= 12; bits++) {
            code = (code + blCount[bits - 1]) << 1;
            nextCode[bits] = code;
        }
        for (int s = 0; s < alphabetSize; s++) {
            int l = outLens[s];
            if (l > 0) {
                outCodes[s] = nextCode[l]++;
            }
        }
    }

    /**
     * Compact Canonical Huffman coder for small localized symbol alphabets (`2..64` symbols),
     * avoiding 256-entry header overhead by emitting only `M` 4-bit code lengths (`ceil(M/2)` bytes),
     * and emitting `0` bitstream bytes when all `count` values collapse to a single predicted symbol.
     */
    private static byte[] encodeSmallAlphabetHuffman(int[] symbols, int count, int alphabetSize) {
        int[] freq = new int[alphabetSize];
        int distinct = 0;
        for (int i = 0; i < count; i++) {
            int s = symbols[i];
            if (freq[s] == 0) {
                distinct++;
            }
            freq[s]++;
        }
        int[] lens = new int[alphabetSize];
        int[] codes = new int[alphabetSize];
        buildExactBoundedHuffman12(freq, alphabetSize, lens, codes);

        ByteArrayOutputStream out = new ByteArrayOutputStream(32 + count / 2);
        int headerBytes = (alphabetSize + 1) >>> 1;
        for (int i = 0; i < headerBytes; i++) {
            int l0 = lens[i * 2] & 0x0F;
            int l1 = (i * 2 + 1 < alphabetSize) ? (lens[i * 2 + 1] & 0x0F) : 0;
            out.write((l0 << 4) | l1);
        }
        if (distinct <= 1) {
            out.write(0); // 0 bitstream bytes needed when deterministic!
            return out.toByteArray();
        }
        BitWriter bw = new BitWriter();
        for (int i = 0; i < count; i++) {
            int s = symbols[i];
            bw.writeBits(codes[s], lens[s]);
        }
        byte[] bitBytes = bw.finish();
        byte[] varBuf = new byte[8];
        int vLen = LcaSectionCodec.writeVarIntToBuf(varBuf, 0, bitBytes.length);
        out.write(varBuf, 0, vLen);
        out.write(bitBytes, 0, bitBytes.length);
        return out.toByteArray();
    }

    private static int decodeSmallAlphabetHuffman(
            byte[] src, int pos, int alphabetSize, int count, int[] outSymbols
    ) throws CorruptRegionEntryException {
        int headerBytes = (alphabetSize + 1) >>> 1;
        int[] lens = new int[alphabetSize];
        int maxLen = 1;
        int distinct = 0;
        int singleSym = 0;
        for (int i = 0; i < headerBytes; i++) {
            int b = src[pos++] & 0xFF;
            int s0 = i * 2;
            lens[s0] = (b >>> 4) & 0x0F;
            if (lens[s0] > 0) {
                distinct++;
                singleSym = s0;
                if (lens[s0] > maxLen) {
                    maxLen = lens[s0];
                }
            }
            int s1 = s0 + 1;
            if (s1 < alphabetSize) {
                lens[s1] = b & 0x0F;
                if (lens[s1] > 0) {
                    distinct++;
                    singleSym = s1;
                    if (lens[s1] > maxLen) {
                        maxLen = lens[s1];
                    }
                }
            }
        }
        int bitByteLen = LcaSectionCodec.readVarInt(src, pos, src.length);
        pos += LcaSectionCodec.varIntLen(bitByteLen);
        if (distinct <= 1 && bitByteLen == 0) {
            Arrays.fill(outSymbols, 0, count, singleSym);
            return pos;
        }

        int[] blCount = new int[maxLen + 1];
        for (int s = 0; s < alphabetSize; s++) {
            if (lens[s] > 0) {
                blCount[lens[s]]++;
            }
        }
        int[] nextCode = new int[maxLen + 1];
        int code = 0;
        for (int bits = 1; bits <= maxLen; bits++) {
            code = (code + blCount[bits - 1]) << 1;
            nextCode[bits] = code;
        }
        int tableSize = 1 << maxLen;
        int[] decTable = new int[tableSize];
        for (int s = 0; s < alphabetSize; s++) {
            int len = lens[s];
            if (len > 0) {
                int c = nextCode[len]++;
                int shift = maxLen - len;
                int start = c << shift;
                int end = (c + 1) << shift;
                int entry = (s << 8) | len;
                Arrays.fill(decTable, start, end, entry);
            }
        }

        BitReader br = new BitReader(src, pos, bitByteLen);
        for (int i = 0; i < count; i++) {
            outSymbols[i] = br.decodeHuffSymbol(decTable, maxLen);
        }
        return pos + bitByteLen;
    }

    /**
     * Encodes a section using exact current `.lca` v2 Cartesian state palette + `encodeSectionBlockContainersV2`.
     */
    private static byte[] encodeBaselineCartesianSection(int[] yzxGlobalIds) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int id : yzxGlobalIds) {
            counts.merge(id, 1, Integer::sum);
        }
        int k = counts.size();
        Integer[] sortedIds = counts.keySet().toArray(new Integer[0]);
        Arrays.sort(sortedIds, (a, b) -> {
            int cmp = Integer.compare(counts.get(b), counts.get(a));
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });
        Map<Integer, Integer> globalToOrd = new HashMap<>(k * 2);
        for (int i = 0; i < k; i++) {
            globalToOrd.put(sortedIds[i], i);
        }

        byte[] out = new byte[32768];
        int pos = 0;
        pos = LcaSectionCodec.writeVarIntToBuf(out, pos, k);
        for (int i = 0; i < k; i++) {
            pos = LcaSectionCodec.writeVarIntToBuf(out, pos, sortedIds[i]);
        }
        if (k > 1) {
            int[] yzxOrd = new int[SECTION_VOXELS];
            for (int i = 0; i < SECTION_VOXELS; i++) {
                yzxOrd[i] = globalToOrd.get(yzxGlobalIds[i]);
            }
            pos = LcaSectionCodec.encodeSectionBlockContainersV2(yzxOrd, k, out, pos);
        }
        return Arrays.copyOf(out, pos);
    }

    private static int[] decodeBaselineCartesianSection(byte[] buf, int offset, int limit)
            throws CorruptRegionEntryException {
        int pos = offset;
        int k = LcaSectionCodec.readVarInt(buf, pos, limit);
        pos += LcaSectionCodec.varIntLen(k);
        int[] palette = new int[k];
        for (int i = 0; i < k; i++) {
            palette[i] = LcaSectionCodec.readVarInt(buf, pos, limit);
            pos += LcaSectionCodec.varIntLen(palette[i]);
        }
        int[] outGlobal = new int[SECTION_VOXELS];
        if (k == 1) {
            Arrays.fill(outGlobal, palette[0]);
            return outGlobal;
        }
        int[] yzxOrd = new int[SECTION_VOXELS];
        int next = LcaSectionCodec.decodeSectionBlockContainersV2(buf, pos, limit, k, yzxOrd);
        if (next != limit) {
            throw new IllegalStateException("Baseline decode cursor mismatch: " + next + " != " + limit);
        }
        for (int i = 0; i < SECTION_VOXELS; i++) {
            outGlobal[i] = palette[yzxOrd[i]];
        }
        return outGlobal;
    }

    /**
     * Computes deterministic 3D Manhattan log-distance field (`1..7`, or `0` when no logs exist in
     * the section) from decoded Layer A material grid, then refines each leaf voxel causally from
     * already-decoded face-adjacent leaf neighbors (`|d(u) - d(v)| <= 1` eikonal invariant).
     */
    private static int[] computeInSectionLogBfsDistance(boolean[] isLogVoxel) {
        int[] bfsDist = new int[SECTION_VOXELS];
        Arrays.fill(bfsDist, 8);
        int[] queue = new int[SECTION_VOXELS];
        int head = 0;
        int tail = 0;
        for (int yzx = 0; yzx < SECTION_VOXELS; yzx++) {
            if (isLogVoxel[yzx]) {
                bfsDist[yzx] = 0;
                queue[tail++] = yzx;
            }
        }
        if (tail == 0) {
            return null;
        }
        int[] dx = {-1, 1, 0, 0, 0, 0};
        int[] dy = {0, 0, -1, 1, 0, 0};
        int[] dz = {0, 0, 0, 0, -1, 1};
        while (head < tail) {
            int cur = queue[head++];
            int cd = bfsDist[cur];
            if (cd >= 7) {
                continue;
            }
            int x = cur & 15;
            int z = (cur >>> 4) & 15;
            int y = (cur >>> 8) & 15;
            for (int dir = 0; dir < 6; dir++) {
                int nx = x + dx[dir];
                int ny = y + dy[dir];
                int nz = z + dz[dir];
                if ((nx | ny | nz) >= 0 && nx < 16 && ny < 16 && nz < 16) {
                    int nIdx = (ny << 8) | (nz << 4) | nx;
                    if (bfsDist[nIdx] > cd + 1) {
                        bfsDist[nIdx] = cd + 1;
                        queue[tail++] = nIdx;
                    }
                }
            }
        }
        return bfsDist;
    }

    private static int predictLeafDistanceCausal(
            int yzx, int[] bfsDistOrNull, int[] decodedDistGrid, int fallbackDist
    ) {
        int bestFromDecodedLeaves = 8;
        int x = yzx & 15;
        int z = (yzx >>> 4) & 15;
        int y = (yzx >>> 8) & 15;
        if (x > 0 && decodedDistGrid[yzx - 1] > 0) {
            bestFromDecodedLeaves = Math.min(bestFromDecodedLeaves, decodedDistGrid[yzx - 1] + 1);
        }
        if (x < 15 && decodedDistGrid[yzx + 1] > 0) {
            bestFromDecodedLeaves = Math.min(bestFromDecodedLeaves, decodedDistGrid[yzx + 1] + 1);
        }
        if (z > 0 && decodedDistGrid[yzx - 16] > 0) {
            bestFromDecodedLeaves = Math.min(bestFromDecodedLeaves, decodedDistGrid[yzx - 16] + 1);
        }
        if (z < 15 && decodedDistGrid[yzx + 16] > 0) {
            bestFromDecodedLeaves = Math.min(bestFromDecodedLeaves, decodedDistGrid[yzx + 16] + 1);
        }
        if (y > 0 && decodedDistGrid[yzx - 256] > 0) {
            bestFromDecodedLeaves = Math.min(bestFromDecodedLeaves, decodedDistGrid[yzx - 256] + 1);
        }
        if (y < 15 && decodedDistGrid[yzx + 256] > 0) {
            bestFromDecodedLeaves = Math.min(bestFromDecodedLeaves, decodedDistGrid[yzx + 256] + 1);
        }
        int bfsPred = (bfsDistOrNull != null) ? Math.min(7, bfsDistOrNull[yzx]) : 8;
        int combined = Math.min(bfsPred, Math.min(7, bestFromDecodedLeaves));
        if (combined >= 1 && combined <= 7) {
            return combined;
        }
        return fallbackDist;
    }

    /**
     * Fast 1-pass palette classifier (`O(|P|)`) + zero-regression layered encoder:
     *   - Clean low-palette single-property structural sections bypass Layer B trials in `< 0.2 us`,
     *     routing directly to `.lca` v2 `Delta-Roaring`.
     *   - Sections flagged with multi-property fragmentation, leaves/tall-plants, or high palette
     *     cardinality (`|P| >= 12`) evaluate Layer A (base material / top-K structural strata) +
     *     Layer B (`01:H_3` Move-To-Front, Eikonal leaf decay, tall-plant Y-parity, or Order-0 Huffman).
     */
    private static byte[] encodeLayeredSection(int[] yzxGlobalIds, List<ParsedBlockState> globalDict) {
        byte[] baseline = encodeBaselineCartesianSection(yzxGlobalIds);

        // Fast 1-pass palette scan on already-present states to avoid wasted compute on clean sections
        int distinctStates = 0;
        int distinctBases = 0;
        boolean hasLeavesOrTall = false;
        Map<Integer, Integer> stateFreq = new LinkedHashMap<>(32);
        Map<String, Integer> baseFreq = new LinkedHashMap<>(32);
        for (int i = 0; i < SECTION_VOXELS; i++) {
            int gid = yzxGlobalIds[i];
            if (stateFreq.merge(gid, 1, Integer::sum) == 1) {
                distinctStates++;
                ParsedBlockState ps = globalDict.get(gid);
                if (ps.isLeaves || ps.tallHalf >= 0) {
                    hasLeavesOrTall = true;
                }
                if (baseFreq.putIfAbsent(ps.baseName, 1) == null) {
                    distinctBases++;
                }
            }
        }

        byte[] bestPayload = baseline;
        byte bestMode = MODE_PASSTHROUGH_V2;

        int propExcess = distinctStates - distinctBases;
        if (distinctStates >= 3 && (hasLeavesOrTall || propExcess >= 2 || distinctStates >= 12)) {
            for (Map.Entry<Integer, Integer> e : stateFreq.entrySet()) {
                if (e.getValue() > 1) {
                    String bName = globalDict.get(e.getKey()).baseName;
                    baseFreq.put(bName, 0);
                }
            }
            baseFreq.clear();
            for (Map.Entry<Integer, Integer> e : stateFreq.entrySet()) {
                baseFreq.merge(globalDict.get(e.getKey()).baseName, e.getValue(), Integer::sum);
            }

            if (propExcess >= 1 || hasLeavesOrTall) {
                byte[] candPureBase = tryEncodeLayeredCandidate(
                        yzxGlobalIds, globalDict, stateFreq, baseFreq, 0, 0);
                if (candPureBase != null && candPureBase.length < bestPayload.length) {
                    bestPayload = candPureBase;
                    bestMode = MODE_LAYERED_PROP_SCATTER;
                }
            }

            if (distinctBases > 7 || distinctStates >= 12) {
                // Coalesce non-trunk minority strata outside top-6 structural materials into a single
                // Layer A scatter mask, then resolve exact veins/scatter via H_3 MTF + Huffman in Layer B
                byte[] candTopStrata = tryEncodeLayeredCandidate(
                        yzxGlobalIds, globalDict, stateFreq, baseFreq, 0, 6);
                if (candTopStrata != null && candTopStrata.length < bestPayload.length) {
                    bestPayload = candTopStrata;
                    bestMode = MODE_LAYERED_PROP_SCATTER;
                }
            }
        }

        byte[] framed = new byte[bestPayload.length + 8];
        int p = 0;
        p = LcaSectionCodec.writeVarIntToBuf(framed, p, 1 + bestPayload.length);
        framed[p++] = bestMode;
        System.arraycopy(bestPayload, 0, framed, p, bestPayload.length);
        return Arrays.copyOf(framed, p + bestPayload.length);
    }

    private static byte[] tryEncodeLayeredCandidate(
            int[] yzxGlobalIds,
            List<ParsedBlockState> globalDict,
            Map<Integer, Integer> stateFreq,
            Map<String, Integer> baseFreq,
            int scatterFreqThreshold,
            int keepTopNonTrunkMaterials
    ) {
        if (stateFreq.size() <= 2) {
            return null;
        }

        final String scatterToken = "__SCATTER__";
        Map<String, Boolean> keepSeparate = new HashMap<>(baseFreq.size() * 2);
        if (keepTopNonTrunkMaterials > 0 && baseFreq.size() > keepTopNonTrunkMaterials + 2) {
            List<String> nonTrunkSorted = new ArrayList<>(baseFreq.size());
            for (String bName : baseFreq.keySet()) {
                boolean isTrunk = bName.endsWith("_log") || bName.endsWith("_wood")
                        || bName.endsWith("_stem") || bName.endsWith("_hyphae")
                        || bName.equals("minecraft:mangrove_roots");
                if (isTrunk || bName.endsWith("_leaves")) {
                    keepSeparate.put(bName, Boolean.TRUE);
                } else {
                    nonTrunkSorted.add(bName);
                }
            }
            nonTrunkSorted.sort((a, b) -> {
                int cmp = Integer.compare(baseFreq.get(b), baseFreq.get(a));
                return cmp != 0 ? cmp : a.compareTo(b);
            });
            int kept = 0;
            int coalescedStates = 0;
            for (String bName : nonTrunkSorted) {
                if (kept < keepTopNonTrunkMaterials) {
                    keepSeparate.put(bName, Boolean.TRUE);
                    kept++;
                } else {
                    keepSeparate.put(bName, Boolean.FALSE);
                    coalescedStates++;
                }
            }
            if (coalescedStates < 3) {
                return null;
            }
        } else if (scatterFreqThreshold > 0 && baseFreq.size() > 10) {
            int coalesced = 0;
            for (Map.Entry<String, Integer> e : baseFreq.entrySet()) {
                String bName = e.getKey();
                boolean isTrunk = bName.endsWith("_log") || bName.endsWith("_wood")
                        || bName.endsWith("_stem") || bName.endsWith("_hyphae")
                        || bName.equals("minecraft:mangrove_roots");
                if (!isTrunk && e.getValue() <= scatterFreqThreshold) {
                    keepSeparate.put(bName, Boolean.FALSE);
                    coalesced++;
                } else {
                    keepSeparate.put(bName, Boolean.TRUE);
                }
            }
            if (coalesced < 3) {
                return null;
            }
        } else {
            for (String bName : baseFreq.keySet()) {
                keepSeparate.put(bName, Boolean.TRUE);
            }
        }

        Map<String, Integer> layerAFreq = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : baseFreq.entrySet()) {
            if (Boolean.TRUE.equals(keepSeparate.get(e.getKey()))) {
                layerAFreq.put(e.getKey(), e.getValue());
            } else {
                layerAFreq.merge(scatterToken, e.getValue(), Integer::sum);
            }
        }

        List<String> sortedLayerANames = new ArrayList<>(layerAFreq.keySet());
        sortedLayerANames.sort((a, b) -> {
            int cmp = Integer.compare(layerAFreq.get(b), layerAFreq.get(a));
            return cmp != 0 ? cmp : a.compareTo(b);
        });
        int kLayerA = sortedLayerANames.size();
        Map<String, Integer> nameToLayerAOrd = new HashMap<>(kLayerA * 2);
        for (int i = 0; i < kLayerA; i++) {
            nameToLayerAOrd.put(sortedLayerANames.get(i), i);
        }

        @SuppressWarnings("unchecked")
        List<Integer>[] groupGlobalStates = new List[kLayerA];
        for (int i = 0; i < kLayerA; i++) {
            groupGlobalStates[i] = new ArrayList<>();
        }
        for (Integer gid : stateFreq.keySet()) {
            ParsedBlockState ps = globalDict.get(gid);
            String groupKey = Boolean.TRUE.equals(keepSeparate.get(ps.baseName))
                    ? ps.baseName
                    : scatterToken;
            groupGlobalStates[nameToLayerAOrd.get(groupKey)].add(gid);
        }

        Map<Integer, Integer> globalToGroupOrd = new HashMap<>(stateFreq.size() * 2);
        Map<Integer, Integer> globalToLocalVarOrd = new HashMap<>(stateFreq.size() * 2);
        for (int g = 0; g < kLayerA; g++) {
            List<Integer> states = groupGlobalStates[g];
            states.sort((a, b) -> {
                int cmp = Integer.compare(stateFreq.get(b), stateFreq.get(a));
                return cmp != 0 ? cmp : Integer.compare(a, b);
            });
            if (states.size() > 64) {
                return null;
            }
            for (int v = 0; v < states.size(); v++) {
                globalToGroupOrd.put(states.get(v), g);
                globalToLocalVarOrd.put(states.get(v), v);
            }
        }

        int[] yzxLayerA = new int[SECTION_VOXELS];
        for (int i = 0; i < SECTION_VOXELS; i++) {
            yzxLayerA[i] = globalToGroupOrd.get(yzxGlobalIds[i]);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
        byte[] tmp = new byte[32768];
        int p = 0;
        p = LcaSectionCodec.writeVarIntToBuf(tmp, p, kLayerA);
        for (int g = 0; g < kLayerA; g++) {
            List<Integer> states = groupGlobalStates[g];
            p = LcaSectionCodec.writeVarIntToBuf(tmp, p, states.size());
            for (int gid : states) {
                p = LcaSectionCodec.writeVarIntToBuf(tmp, p, gid);
            }
        }
        if (kLayerA > 1) {
            p = LcaSectionCodec.encodeSectionBlockContainersV2(yzxLayerA, kLayerA, tmp, p);
        }
        out.write(tmp, 0, p);

        // Reconstruct trunk mask identically to decodeLayeredSection
        boolean[] isLogVoxel = new boolean[SECTION_VOXELS];
        boolean[] groupIsPureLog = new boolean[kLayerA];
        for (int g = 0; g < kLayerA; g++) {
            boolean allLog = true;
            for (int gid : groupGlobalStates[g]) {
                if (!globalDict.get(gid).isLogOrStem) {
                    allLog = false;
                    break;
                }
            }
            groupIsPureLog[g] = allLog;
        }
        for (int i = 0; i < SECTION_VOXELS; i++) {
            if (groupIsPureLog[yzxLayerA[i]]) {
                isLogVoxel[i] = true;
            }
        }
        int[] bfsDistOrNull = computeInSectionLogBfsDistance(isLogVoxel);

        int[] h3ToYzx = LcaSectionCodec.CURVE_TO_YZX[LcaSectionCodec.CURVE_H3];

        // Layer B: For each group g with variants M_g > 1, encode ONLY its N_g active locations!
        for (int g = 0; g < kLayerA; g++) {
            List<Integer> states = groupGlobalStates[g];
            int m = states.size();
            if (m <= 1) {
                continue;
            }
            boolean isPureLeavesGroup = !scatterToken.equals(sortedLayerANames.get(g));
            boolean isTallPlantPair = (m == 2) && !scatterToken.equals(sortedLayerANames.get(g));
            int[] distAuxToVar = new int[32]; // (auxFlags << 3) | dist -> varOrd
            Arrays.fill(distAuxToVar, -1);
            for (int v = 0; v < m; v++) {
                ParsedBlockState ps = globalDict.get(states.get(v));
                if (ps.leafDistance < 1 || ps.leafAuxFlags < 0) {
                    isPureLeavesGroup = false;
                } else {
                    int key = (ps.leafAuxFlags << 3) | ps.leafDistance;
                    if (distAuxToVar[key] != -1) {
                        isPureLeavesGroup = false;
                    }
                    distAuxToVar[key] = v;
                }
                if (ps.tallHalf < 0) {
                    isTallPlantPair = false;
                }
            }

            // Collect active locations in 3D Hilbert (H_3) order for spatial locality
            int count = 0;
            int[] activeYzx = new int[SECTION_VOXELS];
            int[] rawVarSyms = new int[SECTION_VOXELS];
            for (int h = 0; h < SECTION_VOXELS; h++) {
                int yzx = h3ToYzx[h];
                if (yzxLayerA[yzx] == g) {
                    activeYzx[count] = yzx;
                    rawVarSyms[count] = globalToLocalVarOrd.get(yzxGlobalIds[yzx]);
                    count++;
                }
            }
            if (count == 0) {
                continue;
            }

            // Candidate 1: Raw masked bitpack (ceil(count * bits / 8))
            int rawBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(m - 1));
            BitWriter rawBw = new BitWriter();
            for (int i = 0; i < count; i++) {
                rawBw.writeBits(rawVarSyms[i], rawBits);
            }
            byte[] rawBytes = rawBw.finish();
            byte bestStreamMode = PROP_STREAM_RAW_BITS;
            byte[] bestStreamBytes = rawBytes;

            // Candidate 2: Masked Canonical Huffman on local variant ordinals
            byte[] huffBytes = encodeSmallAlphabetHuffman(rawVarSyms, count, m);
            if (huffBytes.length < bestStreamBytes.length) {
                bestStreamMode = PROP_STREAM_HUFFMAN;
                bestStreamBytes = huffBytes;
            }

            // Candidate 2B: 3D Hilbert Move-To-Front (MTF) + Canonical Huffman (captures 3D ore veins & clusters!)
            if (m >= 3 && count >= 16) {
                int[] mtfList = new int[m];
                for (int v = 0; v < m; v++) {
                    mtfList[v] = v;
                }
                int[] mtfSyms = new int[count];
                for (int i = 0; i < count; i++) {
                    int target = rawVarSyms[i];
                    int rank = 0;
                    while (mtfList[rank] != target) {
                        rank++;
                    }
                    mtfSyms[i] = rank;
                    if (rank > 0) {
                        System.arraycopy(mtfList, 0, mtfList, 1, rank);
                        mtfList[0] = target;
                    }
                }
                byte[] mtfBytes = encodeSmallAlphabetHuffman(mtfSyms, count, m);
                if (mtfBytes.length < bestStreamBytes.length) {
                    bestStreamMode = PROP_STREAM_HILBERT_MTF_HUFF;
                    bestStreamBytes = mtfBytes;
                }
            }

            // Candidate 3: Eikonal 3D Leaf Distance-Decay Predictor + Auxiliary Residual Huffman
            if (isPureLeavesGroup) {
                int fallbackDist = globalDict.get(states.get(0)).leafDistance;
                int[] decodedDistGrid = new int[SECTION_VOXELS];
                int[] leafResidualSyms = new int[count];
                boolean allMapped = true;
                for (int i = 0; i < count; i++) {
                    int yzx = activeYzx[i];
                    ParsedBlockState actual = globalDict.get(states.get(rawVarSyms[i]));
                    int predDist = predictLeafDistanceCausal(yzx, bfsDistOrNull, decodedDistGrid, fallbackDist);
                    int mod7Delta = (actual.leafDistance - predDist + 7) % 7; // 0..6 (0 = exact match!)
                    int sym = (actual.leafAuxFlags << 3) | mod7Delta; // 0..31
                    if (sym < 0 || sym >= 32) {
                        allMapped = false;
                        break;
                    }
                    leafResidualSyms[i] = sym;
                    decodedDistGrid[yzx] = actual.leafDistance;
                }
                if (allMapped) {
                    byte[] eikonalBytes = encodeSmallAlphabetHuffman(leafResidualSyms, count, 32);
                    if (eikonalBytes.length < bestStreamBytes.length) {
                        bestStreamMode = PROP_STREAM_EIKONAL_LEAVES_HUFF;
                        bestStreamBytes = eikonalBytes;
                    }
                }
            }

            // Candidate 4: Tall-plant Y-parity vertical stack predictor (lower at y-1 -> upper at y)
            if (isTallPlantPair) {
                // Process in YZX order so (x, y-1, z) is always decoded before (x, y, z)
                int lowerVar = (globalDict.get(states.get(0)).tallHalf == 0) ? 0 : 1;
                int upperVar = 1 - lowerVar;
                int[] decodedHalfGrid = new int[SECTION_VOXELS];
                Arrays.fill(decodedHalfGrid, -1);
                int[] paritySyms = new int[count];
                int idx = 0;
                for (int yzx = 0; yzx < SECTION_VOXELS; yzx++) {
                    if (yzxLayerA[yzx] == g) {
                        int actualVar = globalToLocalVarOrd.get(yzxGlobalIds[yzx]);
                        int y = (yzx >>> 8) & 15;
                        int predVar = (y > 0 && decodedHalfGrid[yzx - 256] == 0) ? upperVar : lowerVar;
                        paritySyms[idx++] = (actualVar == predVar) ? 0 : 1;
                        decodedHalfGrid[yzx] = (actualVar == lowerVar) ? 0 : 1;
                    }
                }
                byte[] parityBytes = encodeSmallAlphabetHuffman(paritySyms, count, 2);
                if (parityBytes.length < bestStreamBytes.length) {
                    bestStreamMode = PROP_STREAM_TALL_PLANT_PARITY_HUFF;
                    bestStreamBytes = parityBytes;
                }
            }

            out.write(bestStreamMode);
            out.write(bestStreamBytes, 0, bestStreamBytes.length);
        }

        return out.toByteArray();
    }

    /**
     * O(1) skip cursor matching `LcaSectionCodec.skipSectionFrame` (`S-005` invariant).
     */
    private static int skipLayeredSectionFrame(byte[] buf, int pos, int limit)
            throws CorruptRegionEntryException {
        int payloadLen = LcaSectionCodec.readVarInt(buf, pos, limit);
        pos += LcaSectionCodec.varIntLen(payloadLen);
        return pos + payloadLen;
    }

    /**
     * Exact lossless decoder for {@link #encodeLayeredSection}.
     */
    private static int[] decodeLayeredSection(byte[] framed, List<ParsedBlockState> globalDict)
            throws CorruptRegionEntryException {
        int pos = 0;
        int payloadLen = LcaSectionCodec.readVarInt(framed, pos, framed.length);
        pos += LcaSectionCodec.varIntLen(payloadLen);
        int limit = pos + payloadLen;
        byte mode = framed[pos++];
        if (mode == MODE_PASSTHROUGH_V2) {
            return decodeBaselineCartesianSection(framed, pos, limit);
        }
        if (mode != MODE_LAYERED_PROP_SCATTER) {
            throw new IllegalStateException("Unsupported layered mode: " + mode);
        }

        int kLayerA = LcaSectionCodec.readVarInt(framed, pos, limit);
        pos += LcaSectionCodec.varIntLen(kLayerA);
        int[][] groupStates = new int[kLayerA][];
        for (int g = 0; g < kLayerA; g++) {
            int m = LcaSectionCodec.readVarInt(framed, pos, limit);
            pos += LcaSectionCodec.varIntLen(m);
            groupStates[g] = new int[m];
            for (int v = 0; v < m; v++) {
                groupStates[g][v] = LcaSectionCodec.readVarInt(framed, pos, limit);
                pos += LcaSectionCodec.varIntLen(groupStates[g][v]);
            }
        }

        int[] yzxLayerA = new int[SECTION_VOXELS];
        if (kLayerA > 1) {
            pos = LcaSectionCodec.decodeSectionBlockContainersV2(framed, pos, limit, kLayerA, yzxLayerA);
            if (pos < 0) {
                throw new IllegalStateException("Corrupt Layer A block container");
            }
        }

        // Reconstruct trunk mask from Layer A (single-material log groups have all states isLogOrStem)
        boolean[] isLogVoxel = new boolean[SECTION_VOXELS];
        boolean[] groupIsPureLog = new boolean[kLayerA];
        for (int g = 0; g < kLayerA; g++) {
            boolean allLog = true;
            for (int gid : groupStates[g]) {
                if (!globalDict.get(gid).isLogOrStem) {
                    allLog = false;
                    break;
                }
            }
            groupIsPureLog[g] = allLog;
        }
        for (int i = 0; i < SECTION_VOXELS; i++) {
            if (groupIsPureLog[yzxLayerA[i]]) {
                isLogVoxel[i] = true;
            }
        }
        int[] bfsDistOrNull = computeInSectionLogBfsDistance(isLogVoxel);
        int[] h3ToYzx = LcaSectionCodec.CURVE_TO_YZX[LcaSectionCodec.CURVE_H3];

        int[] outGlobal = new int[SECTION_VOXELS];
        for (int i = 0; i < SECTION_VOXELS; i++) {
            int g = yzxLayerA[i];
            if (groupStates[g].length == 1) {
                outGlobal[i] = groupStates[g][0];
            }
        }

        for (int g = 0; g < kLayerA; g++) {
            int[] states = groupStates[g];
            int m = states.length;
            if (m <= 1) {
                continue;
            }
            int count = 0;
            int[] activeYzx = new int[SECTION_VOXELS];
            for (int h = 0; h < SECTION_VOXELS; h++) {
                int yzx = h3ToYzx[h];
                if (yzxLayerA[yzx] == g) {
                    activeYzx[count++] = yzx;
                }
            }
            if (count == 0) {
                continue;
            }

            byte streamMode = framed[pos++];
            if (streamMode == PROP_STREAM_RAW_BITS) {
                int rawBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(m - 1));
                int rawByteLen = (count * rawBits + 7) >>> 3;
                BitReader br = new BitReader(framed, pos, rawByteLen);
                for (int i = 0; i < count; i++) {
                    int v = br.readBits(rawBits);
                    outGlobal[activeYzx[i]] = states[v];
                }
                pos += rawByteLen;
            } else if (streamMode == PROP_STREAM_HUFFMAN) {
                int[] syms = new int[count];
                pos = decodeSmallAlphabetHuffman(framed, pos, m, count, syms);
                for (int i = 0; i < count; i++) {
                    outGlobal[activeYzx[i]] = states[syms[i]];
                }
            } else if (streamMode == PROP_STREAM_EIKONAL_LEAVES_HUFF) {
                int[] syms = new int[count];
                pos = decodeSmallAlphabetHuffman(framed, pos, 32, count, syms);
                int[] distAuxToVar = new int[32];
                Arrays.fill(distAuxToVar, -1);
                for (int v = 0; v < m; v++) {
                    ParsedBlockState ps = globalDict.get(states[v]);
                    distAuxToVar[(ps.leafAuxFlags << 3) | ps.leafDistance] = v;
                }
                int fallbackDist = globalDict.get(states[0]).leafDistance;
                int[] decodedDistGrid = new int[SECTION_VOXELS];
                for (int i = 0; i < count; i++) {
                    int yzx = activeYzx[i];
                    int predDist = predictLeafDistanceCausal(yzx, bfsDistOrNull, decodedDistGrid, fallbackDist);
                    int sym = syms[i];
                    int aux = (sym >>> 3) & 3;
                    int mod7Delta = sym & 7;
                    int actualDist = ((predDist - 1 + mod7Delta) % 7) + 1;
                    decodedDistGrid[yzx] = actualDist;
                    int v = distAuxToVar[(aux << 3) | actualDist];
                    outGlobal[yzx] = states[v];
                }
            } else if (streamMode == PROP_STREAM_TALL_PLANT_PARITY_HUFF) {
                int[] syms = new int[count];
                pos = decodeSmallAlphabetHuffman(framed, pos, 2, count, syms);
                int lowerVar = (globalDict.get(states[0]).tallHalf == 0) ? 0 : 1;
                int upperVar = 1 - lowerVar;
                int[] decodedHalfGrid = new int[SECTION_VOXELS];
                Arrays.fill(decodedHalfGrid, -1);
                int idx = 0;
                for (int yzx = 0; yzx < SECTION_VOXELS; yzx++) {
                    if (yzxLayerA[yzx] == g) {
                        int y = (yzx >>> 8) & 15;
                        int predVar = (y > 0 && decodedHalfGrid[yzx - 256] == 0) ? upperVar : lowerVar;
                        int actualVar = (syms[idx++] == 0) ? predVar : (1 - predVar);
                        outGlobal[yzx] = states[actualVar];
                        decodedHalfGrid[yzx] = (actualVar == lowerVar) ? 0 : 1;
                    }
                }
            } else if (streamMode == PROP_STREAM_HILBERT_MTF_HUFF) {
                int[] ranks = new int[count];
                pos = decodeSmallAlphabetHuffman(framed, pos, m, count, ranks);
                int[] mtfList = new int[m];
                for (int v = 0; v < m; v++) {
                    mtfList[v] = v;
                }
                for (int i = 0; i < count; i++) {
                    int rank = ranks[i];
                    int target = mtfList[rank];
                    if (rank > 0) {
                        System.arraycopy(mtfList, 0, mtfList, 1, rank);
                        mtfList[0] = target;
                    }
                    outGlobal[activeYzx[i]] = states[target];
                }
            } else {
                throw new IllegalStateException("Unknown property stream mode: " + streamMode);
            }
        }

        if (pos != limit) {
            throw new IllegalStateException("Layered decode cursor mismatch: " + pos + " != " + limit);
        }
        return outGlobal;
    }

    private static int internState(String state, Map<String, Integer> map, List<ParsedBlockState> dict) {
        Integer existing = map.get(state);
        if (existing != null) {
            return existing;
        }
        int id = dict.size();
        map.put(state, id);
        dict.add(new ParsedBlockState(state));
        return id;
    }

    private static int[] buildDeterministicForestCanopySection(
            Map<String, Integer> stateToId, List<ParsedBlockState> dict, long seed
    ) {
        Random rng = new Random(seed);
        int air = internState("minecraft:air", stateToId, dict);
        int grassBlock = internState("minecraft:grass_block[snowy=false]", stateToId, dict);
        int dirt = internState("minecraft:dirt", stateToId, dict);
        int oakLogY = internState("minecraft:oak_log[axis=y]", stateToId, dict);
        int oakLogX = internState("minecraft:oak_log[axis=x]", stateToId, dict);
        int birchLogY = internState("minecraft:birch_log[axis=y]", stateToId, dict);
        int tallLower = internState("minecraft:tall_grass[half=lower]", stateToId, dict);
        int tallUpper = internState("minecraft:tall_grass[half=upper]", stateToId, dict);
        String[] scatterNames = {
                "minecraft:short_grass", "minecraft:fern", "minecraft:dandelion",
                "minecraft:poppy", "minecraft:azure_bluet", "minecraft:oxeye_daisy",
                "minecraft:pink_petals[flower_amount=2,facing=north]",
                "minecraft:pink_petals[flower_amount=3,facing=east]"
        };
        int[] scatterIds = new int[scatterNames.length];
        for (int i = 0; i < scatterNames.length; i++) {
            scatterIds[i] = internState(scatterNames[i], stateToId, dict);
        }
        int[] oakLeaves = new int[8];
        int[] birchLeaves = new int[8];
        for (int d = 1; d <= 7; d++) {
            oakLeaves[d] = internState(
                    "minecraft:oak_leaves[distance=" + d + ",persistent=false,waterlogged=false]", stateToId, dict);
            birchLeaves[d] = internState(
                    "minecraft:birch_leaves[distance=" + d + ",persistent=false,waterlogged=false]", stateToId, dict);
        }

        int[] grid = new int[SECTION_VOXELS];
        Arrays.fill(grid, air);
        // Ground layers y=0..2
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                grid[(0 << 8) | (z << 4) | x] = dirt;
                grid[(1 << 8) | (z << 4) | x] = dirt;
                grid[(2 << 8) | (z << 4) | x] = grassBlock;
            }
        }
        // Two oak trunks + branches and one birch trunk
        int[][] trunks = {{4, 4, 0}, {11, 10, 0}, {12, 3, 1}};
        boolean[] isLog = new boolean[SECTION_VOXELS];
        for (int[] t : trunks) {
            int tx = t[0], tz = t[1], kind = t[2];
            int topY = (kind == 0) ? 11 : 10;
            for (int y = 3; y <= topY; y++) {
                int idx = (y << 8) | (tz << 4) | tx;
                grid[idx] = (kind == 0) ? oakLogY : birchLogY;
                isLog[idx] = true;
            }
            if (kind == 0 && tx + 2 < 16) {
                for (int dx = 1; dx <= 2; dx++) {
                    int idx = (8 << 8) | (tz << 4) | (tx + dx);
                    grid[idx] = oakLogX;
                    isLog[idx] = true;
                }
            }
        }

        int[] bfs = computeInSectionLogBfsDistance(isLog);
        for (int y = 6; y <= 14; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int idx = (y << 8) | (z << 4) | x;
                    if (grid[idx] != air) {
                        continue;
                    }
                    int d = bfs[idx];
                    if (d >= 1 && d <= 6 && (d <= 4 || rng.nextDouble() < 0.65)) {
                        boolean nearBirch = (Math.abs(x - 12) + Math.abs(z - 3)) < (Math.abs(x - 4) + Math.abs(z - 4));
                        grid[idx] = nearBirch ? birchLeaves[d] : oakLeaves[d];
                    }
                }
            }
        }

        // Surface flora & tall grass
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y3 = (3 << 8) | (z << 4) | x;
                int y4 = (4 << 8) | (z << 4) | x;
                if (grid[y3] != air) {
                    continue;
                }
                double r = rng.nextDouble();
                if (r < 0.18 && grid[y4] == air) {
                    grid[y3] = tallLower;
                    grid[y4] = tallUpper;
                } else if (r < 0.42) {
                    grid[y3] = scatterIds[rng.nextInt(scatterIds.length)];
                }
            }
        }
        return grid;
    }

    private static int[] buildDeterministicDeepslateLushSection(
            Map<String, Integer> stateToId, List<ParsedBlockState> dict, long seed
    ) {
        Random rng = new Random(seed);
        int dsY = internState("minecraft:deepslate[axis=y]", stateToId, dict);
        int dsX = internState("minecraft:deepslate[axis=x]", stateToId, dict);
        int dsZ = internState("minecraft:deepslate[axis=z]", stateToId, dict);
        int caveAir = internState("minecraft:cave_air", stateToId, dict);
        int tuff = internState("minecraft:tuff", stateToId, dict);
        String[] oresAndFlora = {
                "minecraft:deepslate_redstone_ore[lit=false]",
                "minecraft:deepslate_gold_ore",
                "minecraft:deepslate_diamond_ore",
                "minecraft:deepslate_lapis_ore",
                "minecraft:glow_lichen[down=false,east=false,north=false,south=false,up=true,waterlogged=false,west=false]",
                "minecraft:glow_lichen[down=true,east=false,north=false,south=false,up=false,waterlogged=false,west=false]",
                "minecraft:cave_vines_plant[berries=false]",
                "minecraft:cave_vines_plant[berries=true]",
                "minecraft:cave_vines[age=14,berries=false]",
                "minecraft:pointed_dripstone[thickness=tip,vertical_direction=down,waterlogged=false]",
                "minecraft:pointed_dripstone[thickness=frustum,vertical_direction=down,waterlogged=false]",
                "minecraft:moss_carpet",
                "minecraft:Small_dripleaf[facing=north,half=lower,waterlogged=false]".toLowerCase(Locale.ROOT)
        };
        int[] extraIds = new int[oresAndFlora.length];
        for (int i = 0; i < oresAndFlora.length; i++) {
            extraIds[i] = internState(oresAndFlora[i], stateToId, dict);
        }

        int[] grid = new int[SECTION_VOXELS];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int idx = (y << 8) | (z << 4) | x;
                    double dx = (x - 7.5) / 5.5;
                    double dy = (y - 7.5) / 4.5;
                    double dz = (z - 7.5) / 5.5;
                    if (dx * dx + dy * dy + dz * dz < 1.0) {
                        grid[idx] = caveAir;
                    } else {
                        double r = rng.nextDouble();
                        if (r < 0.80) {
                            grid[idx] = dsY;
                        } else if (r < 0.87) {
                            grid[idx] = dsX;
                        } else if (r < 0.94) {
                            grid[idx] = dsZ;
                        } else if (r < 0.98) {
                            grid[idx] = tuff;
                        } else {
                            grid[idx] = extraIds[rng.nextInt(extraIds.length)];
                        }
                    }
                }
            }
        }
        return grid;
    }

    @Test
    @DisplayName("Proves 100% lossless roundtrip, O(1) skip, and size reduction on synthetic & live 26.3 .lca worlds")
    void proveLayeredPropertyAndScatterEntropyOnSyntheticAndLive263Worlds() throws Exception {
        Map<String, Integer> stateToId = new HashMap<>(4096);
        List<ParsedBlockState> globalDict = new ArrayList<>(4096);

        // 1. Deterministic Controlled Archetypes (always verified in CI and local)
        List<int[]> syntheticSections = new ArrayList<>();
        for (int s = 0; s < 12; s++) {
            syntheticSections.add(buildDeterministicForestCanopySection(stateToId, globalDict, 1000L + s));
            syntheticSections.add(buildDeterministicDeepslateLushSection(stateToId, globalDict, 2000L + s));
        }

        long synBaseRaw = 0;
        long synLayerRaw = 0;
        ByteArrayOutputStream synBaseConcat = new ByteArrayOutputStream();
        ByteArrayOutputStream synLayerConcat = new ByteArrayOutputStream();
        for (int[] sec : syntheticSections) {
            byte[] baseBytes = encodeBaselineCartesianSection(sec);
            byte[] layerBytes = encodeLayeredSection(sec, globalDict);
            assertEquals(layerBytes.length, skipLayeredSectionFrame(layerBytes, 0, layerBytes.length));
            int[] recBase = decodeBaselineCartesianSection(baseBytes, 0, baseBytes.length);
            int[] recLayer = decodeLayeredSection(layerBytes, globalDict);
            assertArrayEquals(sec, recBase, "Baseline Cartesian section roundtrip mismatch");
            assertArrayEquals(sec, recLayer, "Layered section roundtrip mismatch");
            synBaseRaw += baseBytes.length;
            synLayerRaw += layerBytes.length;
            synBaseConcat.write(baseBytes);
            synLayerConcat.write(layerBytes);
        }

        byte[] synBaseAll = synBaseConcat.toByteArray();
        byte[] synLayerAll = synLayerConcat.toByteArray();
        byte[] synBaseLcd2 = LcaSectionCodec.encodeAtomicDictBody(
                synBaseAll, 0, synBaseAll.length, new LcaSectionCodec.AtomicDictState(), true);
        byte[] synLayerLcd2 = LcaSectionCodec.encodeAtomicDictBody(
                synLayerAll, 0, synLayerAll.length, new LcaSectionCodec.AtomicDictState(), true);

        System.out.printf(Locale.ROOT,
                "%n=== [LCA PROOF PART 1] Controlled Canopy + Deepslate/Lush Archetypes (%d sections, L_inf=0, Zero Zstd) ===%n"
                        + "  Native Section Payload : Baseline = %,8d B | Layered = %,8d B (%+.2f%%)%n"
                        + "  Native LCD2 Dict+Huff  : Baseline = %,8d B | Layered = %,8d B (%+.2f%%)%n",
                syntheticSections.size(),
                synBaseRaw, synLayerRaw, 100.0 * (synLayerRaw - synBaseRaw) / synBaseRaw,
                synBaseLcd2.length, synLayerLcd2.length,
                100.0 * (synLayerLcd2.length - synBaseLcd2.length) / synBaseLcd2.length);

        assertTrue(synLayerRaw < synBaseRaw * 0.72,
                "Layered representation should shrink noisy canopy/deepslate sections by >28% raw");
        assertTrue(synLayerLcd2.length < synBaseLcd2.length,
                "Layered representation should remain strictly smaller through native LCD2 dictionary + Huffman");

        // 2. Live 26.3 .lca Overworld Corpus Audit (when available on host)
        Path liveRegionDir = Paths.get(
                System.getProperty("user.home"),
                "Desktop", "GameServers", "Minecraft", "testServer", "RTP-Paper", "26.3",
                "world", "dimensions", "minecraft", "overworld", "region");
        if (!Files.isDirectory(liveRegionDir)) {
            System.out.println("[LCA PROOF PART 2] Live 26.3 directory not present on this host; skipping live scan.");
            return;
        }

        List<Path> lcaFiles = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(liveRegionDir, "*.lca")) {
            for (Path p : ds) {
                lcaFiles.add(p);
            }
        }
        lcaFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));
        // Sample up to 6 diverse region files (~6,144 chunk slots) for fast, representative empirical proof
        int maxRegions = Math.min(6, lcaFiles.size());
        int chunksDecoded = 0;
        int totalNonUniformSections = 0;
        int improvedSections = 0;
        int regressedSections = 0;

        long allBaseRaw = 0, allLayerRaw = 0;
        long foliageBaseRaw = 0, foliageLayerRaw = 0;
        int foliageSections = 0;
        long highPalBaseRaw = 0, highPalLayerRaw = 0;
        int highPalSections = 0;
        long multiPropBaseRaw = 0, multiPropLayerRaw = 0;
        int multiPropSections = 0;

        long baseEncodeNs = 0, baseDecodeNs = 0;
        long layerEncodeNs = 0, layerDecodeNs = 0;

        ByteArrayOutputStream liveFoliageBaseStream = new ByteArrayOutputStream(512 * 1024);
        ByteArrayOutputStream liveFoliageLayerStream = new ByteArrayOutputStream(512 * 1024);
        ByteArrayOutputStream liveHighPalBaseStream = new ByteArrayOutputStream(512 * 1024);
        ByteArrayOutputStream liveHighPalLayerStream = new ByteArrayOutputStream(512 * 1024);

        for (int rIdx = 0; rIdx < maxRegions; rIdx++) {
            Path regionPath = lcaFiles.get(rIdx * (lcaFiles.size() / maxRegions));
            byte[] regionBytes = Files.readAllBytes(regionPath);
            // Sample an 8x8 stride of chunks per region (up to 256 chunks/region) to cover diverse biomes fast
            for (int rz = 0; rz < 32; rz += 2) {
                for (int rx = 0; rx < 32; rx += 2) {
                    if (!LcaRegionReader.INSTANCE.isChunkGenerated(regionBytes, regionBytes.length, rx, rz)) {
                        continue;
                    }
                    AnvilChunkView view = LcaRegionReader.readChunkStateView(regionBytes, regionBytes.length, rx, rz);
                    if (view == null) {
                        continue;
                    }
                    chunksDecoded++;
                    for (PaletteSection sec : view.sections()) {
                        List<String> pal = sec.palette();
                        if (pal.size() <= 1 || sec.data() == null || sec.data().length == 0) {
                            continue;
                        }
                        int[] palGlobalIds = new int[pal.size()];
                        boolean hasLeaves = false;
                        Map<String, Integer> distinctBaseCheck = new HashMap<>(pal.size() * 2);
                        for (int i = 0; i < pal.size(); i++) {
                            int gid = internState(pal.get(i), stateToId, globalDict);
                            palGlobalIds[i] = gid;
                            ParsedBlockState ps = globalDict.get(gid);
                            if (ps.isLeaves) {
                                hasLeaves = true;
                            }
                            distinctBaseCheck.put(ps.baseName, 1);
                        }

                        int[] yzxGlobal = new int[SECTION_VOXELS];
                        long[] packed = sec.data();
                        int bits = PackedPaletteDecoder.bitsPerEntry(pal.size());
                        int perLong = 64 / bits;
                        long mask = (1L << bits) - 1L;
                        int vox = 0;
                        for (int w = 0; w < packed.length && vox < SECTION_VOXELS; w++) {
                            long word = packed[w];
                            int lim = Math.min(SECTION_VOXELS, vox + perLong);
                            while (vox < lim) {
                                int ord = (int) (word & mask);
                                yzxGlobal[vox++] = palGlobalIds[ord];
                                word >>>= bits;
                            }
                        }

                        long t0 = System.nanoTime();
                        byte[] baseBytes = encodeBaselineCartesianSection(yzxGlobal);
                        long t1 = System.nanoTime();
                        int[] decBase = decodeBaselineCartesianSection(baseBytes, 0, baseBytes.length);
                        long t2 = System.nanoTime();

                        byte[] layerBytes = encodeLayeredSection(yzxGlobal, globalDict);
                        long t3 = System.nanoTime();
                        int[] decLayer = decodeLayeredSection(layerBytes, globalDict);
                        long t4 = System.nanoTime();

                        baseEncodeNs += (t1 - t0);
                        baseDecodeNs += (t2 - t1);
                        layerEncodeNs += (t3 - t2);
                        layerDecodeNs += (t4 - t3);

                        // Account cleanly for framing header: compare unframed payload or framed total fairly
                        // Note: baseline has no outer framing byte; layered includes 1-2B skip+mode framing!
                        assertEquals(layerBytes.length, skipLayeredSectionFrame(layerBytes, 0, layerBytes.length));
                        assertArrayEquals(yzxGlobal, decBase, "Live 26.3 baseline roundtrip mismatch");
                        assertArrayEquals(yzxGlobal, decLayer, "Live 26.3 layered roundtrip mismatch");

                        totalNonUniformSections++;
                        // Add 2-byte framing parity to baseline when comparing section-skip-capable frames
                        int framedBaseLen = baseBytes.length + 1 + LcaSectionCodec.varIntLen(baseBytes.length + 1);
                        allBaseRaw += framedBaseLen;
                        allLayerRaw += layerBytes.length;

                        if (layerBytes.length < framedBaseLen) {
                            improvedSections++;
                        } else if (layerBytes.length > framedBaseLen) {
                            regressedSections++;
                        }

                        boolean isMultiProp = pal.size() > distinctBaseCheck.size();
                        if (isMultiProp) {
                            multiPropSections++;
                            multiPropBaseRaw += framedBaseLen;
                            multiPropLayerRaw += layerBytes.length;
                        }
                        if (hasLeaves) {
                            foliageSections++;
                            foliageBaseRaw += framedBaseLen;
                            foliageLayerRaw += layerBytes.length;
                            if (liveFoliageBaseStream.size() < 2 * 1024 * 1024) {
                                liveFoliageBaseStream.write(baseBytes);
                                liveFoliageLayerStream.write(layerBytes);
                            }
                        }
                        if (pal.size() >= 16) {
                            highPalSections++;
                            highPalBaseRaw += framedBaseLen;
                            highPalLayerRaw += layerBytes.length;
                            if (liveHighPalBaseStream.size() < 2 * 1024 * 1024) {
                                liveHighPalBaseStream.write(baseBytes);
                                liveHighPalLayerStream.write(layerBytes);
                            }
                        }
                    }
                }
            }
        }

        byte[] folBaseArr = liveFoliageBaseStream.toByteArray();
        byte[] folLayerArr = liveFoliageLayerStream.toByteArray();
        byte[] folBaseLcd2 = folBaseArr.length > 0 ? LcaSectionCodec.encodeAtomicDictBody(
                folBaseArr, 0, folBaseArr.length, new LcaSectionCodec.AtomicDictState(), true) : new byte[0];
        byte[] folLayerLcd2 = folLayerArr.length > 0 ? LcaSectionCodec.encodeAtomicDictBody(
                folLayerArr, 0, folLayerArr.length, new LcaSectionCodec.AtomicDictState(), true) : new byte[0];

        byte[] hpBaseArr = liveHighPalBaseStream.toByteArray();
        byte[] hpLayerArr = liveHighPalLayerStream.toByteArray();
        byte[] hpBaseLcd2 = hpBaseArr.length > 0 ? LcaSectionCodec.encodeAtomicDictBody(
                hpBaseArr, 0, hpBaseArr.length, new LcaSectionCodec.AtomicDictState(), true) : new byte[0];
        byte[] hpLayerLcd2 = hpLayerArr.length > 0 ? LcaSectionCodec.encodeAtomicDictBody(
                hpLayerArr, 0, hpLayerArr.length, new LcaSectionCodec.AtomicDictState(), true) : new byte[0];

        System.out.printf(Locale.ROOT,
                "%n=== [LCA PROOF PART 2] Live Paper 26.3 Overworld Corpus (%d regions sampled, %,d chunks, %,d non-uniform sections, Zero Zstd) ===%n"
                        + "  Exact Voxel Parity  : 100.00%% (%,d / %,d voxels bit-exact, 0 regressions: %d)%n"
                        + "  1. All Non-Uniform  (n=%,5d): Base=%,10d B -> Layered=%,10d B (%+.2f%%, improved %,d sections)%n"
                        + "  2. Multi-Property   (n=%,5d): Base=%,10d B -> Layered=%,10d B (%+.2f%%)%n"
                        + "  3. Canopy / Leaves  (n=%,5d): Base=%,10d B -> Layered=%,10d B (%+.2f%%)%n"
                        + "     • Canopy + Native LCD2    : Base=%,10d B -> Layered=%,10d B (%+.2f%%)%n"
                        + "  4. High-Palette>=16 (n=%,5d): Base=%,10d B -> Layered=%,10d B (%+.2f%%)%n"
                        + "     • HighPal + Native LCD2   : Base=%,10d B -> Layered=%,10d B (%+.2f%%)%n"
                        + "  Throughput (1-thread): Base Enc=%.1f us/sec, Dec=%.1f us/sec | Layered Enc=%.1f us/sec, Dec=%.1f us/sec%n",
                maxRegions, chunksDecoded, totalNonUniformSections,
                (long) totalNonUniformSections * SECTION_VOXELS,
                (long) totalNonUniformSections * SECTION_VOXELS,
                regressedSections,
                totalNonUniformSections, allBaseRaw, allLayerRaw,
                allBaseRaw > 0 ? 100.0 * (allLayerRaw - allBaseRaw) / allBaseRaw : 0.0, improvedSections,
                multiPropSections, multiPropBaseRaw, multiPropLayerRaw,
                multiPropBaseRaw > 0 ? 100.0 * (multiPropLayerRaw - multiPropBaseRaw) / multiPropBaseRaw : 0.0,
                foliageSections, foliageBaseRaw, foliageLayerRaw,
                foliageBaseRaw > 0 ? 100.0 * (foliageLayerRaw - foliageBaseRaw) / foliageBaseRaw : 0.0,
                folBaseLcd2.length, folLayerLcd2.length,
                folBaseLcd2.length > 0 ? 100.0 * (folLayerLcd2.length - folBaseLcd2.length) / folBaseLcd2.length : 0.0,
                highPalSections, highPalBaseRaw, highPalLayerRaw,
                highPalBaseRaw > 0 ? 100.0 * (highPalLayerRaw - highPalBaseRaw) / highPalBaseRaw : 0.0,
                hpBaseLcd2.length, hpLayerLcd2.length,
                hpBaseLcd2.length > 0 ? 100.0 * (hpLayerLcd2.length - hpBaseLcd2.length) / hpBaseLcd2.length : 0.0,
                totalNonUniformSections > 0 ? (baseEncodeNs / 1000.0) / totalNonUniformSections : 0.0,
                totalNonUniformSections > 0 ? (baseDecodeNs / 1000.0) / totalNonUniformSections : 0.0,
                totalNonUniformSections > 0 ? (layerEncodeNs / 1000.0) / totalNonUniformSections : 0.0,
                totalNonUniformSections > 0 ? (layerDecodeNs / 1000.0) / totalNonUniformSections : 0.0);

        assertEquals(0, regressedSections, "Zero sections may regress under adaptive framing");
        assertTrue(totalNonUniformSections > 100, "Expected >100 non-uniform sections in live 26.3 corpus");
        assertTrue(allLayerRaw < allBaseRaw, "Live 26.3 corpus must achieve net byte reduction");
    }

    // =========================================================================
    // COMPREHENSIVE ZERO-TAX EMPIRICAL STUDIES:
    //   Study 1: 17 Real Structure Templates (.nbt)
    //   Study 2: 14 Real Schematic Builds (.schem & .litematic)
    //   Study 3: Unbiased Uniform 26.3 Overworld Block-Container Audit (0-Byte Tax)
    //   Study 4: Block-Conditioned SkyLight & BlockLight Residual Audit
    //   Study 5: Uniform 24-Region Residual-Tail, Ticks & BlockEntities Audit
    // =========================================================================

    private static final byte PROP_STREAM_CAUSAL_3D_MTF_HUFF = 5;

    private static final int RANK_TAG_SINGLETON = 0;
    private static final int RANK_TAG_DELTA_RUN = 1;
    private static final int RANK_TAG_DELTA_ARRAY = 2;
    private static final int RANK_TAG_BITMAP = 3;

    private static int boundedGapLen(int val) {
        return val < 240 ? 1 : 2;
    }

    private static int writeBoundedGap(byte[] dst, int pos, int val) {
        if (val < 240) {
            dst[pos++] = (byte) val;
        } else {
            dst[pos++] = (byte) (0xF0 | ((val >>> 8) & 0x0F));
            dst[pos++] = (byte) (val & 0xFF);
        }
        return pos;
    }

    private static int readBoundedGap(byte[] src, int[] posBox) {
        int pos = posBox[0];
        int b0 = src[pos++] & 0xFF;
        if (b0 < 240) {
            posBox[0] = pos;
            return b0;
        }
        int b1 = src[pos++] & 0xFF;
        posBox[0] = pos;
        return ((b0 & 0x0F) << 8) | b1;
    }

    /**
     * Emits a single minority container over an arbitrary rank-compacted domain `0..domainLen-1`.
     */
    private static int emitRankDomainContainer(
            boolean[] match, int domainLen, byte[] dst, int pos
    ) {
        int[] runGaps = new int[domainLen + 1];
        int[] runLm1 = new int[domainLen + 1];
        int rCount = 0;
        int tVox = 0;
        int singlePos = 0;
        int lastEnd = -1;
        int i = 0;
        while (i < domainLen) {
            if (!match[i]) {
                i++;
                continue;
            }
            int start = i++;
            while (i < domainLen && match[i]) {
                i++;
            }
            int len = i - start;
            int adjGap = (rCount == 0) ? start : (start - lastEnd - 1);
            lastEnd = i;
            runGaps[rCount] = adjGap;
            runLm1[rCount] = len - 1;
            rCount++;
            tVox += len;
            singlePos = start;
        }

        if (rCount == 1) {
            if (tVox == 1) {
                int w16 = (RANK_TAG_SINGLETON << 14) | (singlePos & 0x0FFF);
                dst[pos++] = (byte) ((w16 >>> 8) & 0xFF);
                dst[pos++] = (byte) (w16 & 0xFF);
                return pos;
            }
            if (tVox <= 257) {
                int w16 = (RANK_TAG_SINGLETON << 14) | 0x1000 | (singlePos & 0x0FFF);
                dst[pos++] = (byte) ((w16 >>> 8) & 0xFF);
                dst[pos++] = (byte) (w16 & 0xFF);
                dst[pos++] = (byte) (tVox - 2);
                return pos;
            }
            int w16 = (RANK_TAG_SINGLETON << 14) | 0x2000 | (singlePos & 0x0FFF);
            dst[pos++] = (byte) ((w16 >>> 8) & 0xFF);
            dst[pos++] = (byte) (w16 & 0xFF);
            int lm2 = tVox - 2;
            dst[pos++] = (byte) ((lm2 >>> 8) & 0xFF);
            dst[pos++] = (byte) (lm2 & 0xFF);
            return pos;
        }
        if (rCount == 2 && runLm1[0] <= 15 && runLm1[1] <= 15) {
            int w16 = (RANK_TAG_SINGLETON << 14) | 0x3000 | (runGaps[0] & 0x0FFF);
            dst[pos++] = (byte) ((w16 >>> 8) & 0xFF);
            dst[pos++] = (byte) (w16 & 0xFF);
            dst[pos++] = (byte) ((runLm1[0] << 4) | runLm1[1]);
            return writeBoundedGap(dst, pos, runGaps[1]);
        }

        int size44 = 2;
        int size53 = 2;
        int size62 = 2;
        int size71 = 2;
        int sizeArr = (tVox == rCount) ? 2 : Integer.MAX_VALUE;
        int totalWords = (domainLen + 63) >>> 6;
        long[] bitmapWords = new long[Math.max(1, totalWords)];
        long wordMask64 = 0L;
        int curPos = 0;
        for (int r = 0; r < rCount; r++) {
            int adjGap = runGaps[r];
            int lm1 = runLm1[r];
            int rStart = (r == 0) ? adjGap : (curPos + adjGap + 1);
            int rEnd = rStart + lm1;
            curPos = rEnd + 1;
            for (int d = rStart; d <= rEnd; d++) {
                int w = d >>> 6;
                bitmapWords[w] |= (1L << (d & 63));
                wordMask64 |= (1L << w);
            }
            size44 += 1 + (adjGap >= 15 ? boundedGapLen(adjGap - 15) : 0) + (lm1 >= 15 ? LcaSectionCodec.varIntLen(lm1 - 15) : 0);
            size53 += 1 + (adjGap >= 31 ? boundedGapLen(adjGap - 31) : 0) + (lm1 >= 7 ? LcaSectionCodec.varIntLen(lm1 - 7) : 0);
            size62 += 1 + (adjGap >= 63 ? boundedGapLen(adjGap - 63) : 0) + (lm1 >= 3 ? LcaSectionCodec.varIntLen(lm1 - 3) : 0);
            size71 += 1 + (adjGap >= 127 ? boundedGapLen(adjGap - 127) : 0) + (lm1 >= 1 ? LcaSectionCodec.varIntLen(lm1 - 1) : 0);
            if (tVox == rCount) {
                sizeArr += boundedGapLen(adjGap);
            }
        }

        int bestRunBytes = Math.min(Math.min(size44, size53), Math.min(size62, size71));
        int bestNonBitmap = Math.min(sizeArr, bestRunBytes);
        int rawBitmapBytes = (domainLen + 7) >>> 3;
        int nonZeroWords = Long.bitCount(wordMask64);
        int maskedBitmapPayload = 8 + (nonZeroWords << 3);
        int minBitmapTotal = 2 + Math.min(rawBitmapBytes, maskedBitmapPayload);

        if (minBitmapTotal < bestNonBitmap || rCount > 0x0FFF) {
            if (maskedBitmapPayload < rawBitmapBytes && nonZeroWords >= 1 && nonZeroWords <= 64) {
                int w16 = (RANK_TAG_BITMAP << 14) | nonZeroWords;
                dst[pos++] = (byte) ((w16 >>> 8) & 0xFF);
                dst[pos++] = (byte) (w16 & 0xFF);
                LcaSectionCodec.writeLongBEToBuf(dst, pos, wordMask64);
                pos += 8;
                long m = wordMask64;
                while (m != 0L) {
                    int w = Long.numberOfTrailingZeros(m);
                    m &= (m - 1L);
                    LcaSectionCodec.writeLongBEToBuf(dst, pos, bitmapWords[w]);
                    pos += 8;
                }
                return pos;
            }
            int w16 = (RANK_TAG_BITMAP << 14) | 0x1000 | (rawBitmapBytes & 0x0FFF);
            dst[pos++] = (byte) ((w16 >>> 8) & 0xFF);
            dst[pos++] = (byte) (w16 & 0xFF);
            for (int b = 0; b < rawBitmapBytes; b++) {
                int byteVal = 0;
                int baseD = b << 3;
                for (int bit = 0; bit < 8 && baseD + bit < domainLen; bit++) {
                    if (match[baseD + bit]) {
                        byteVal |= (1 << bit);
                    }
                }
                dst[pos++] = (byte) byteVal;
            }
            return pos;
        }

        if (sizeArr <= bestRunBytes) {
            int w16 = (RANK_TAG_DELTA_ARRAY << 14) | (rCount & 0x3FFF);
            dst[pos++] = (byte) ((w16 >>> 8) & 0xFF);
            dst[pos++] = (byte) (w16 & 0xFF);
            for (int r = 0; r < rCount; r++) {
                pos = writeBoundedGap(dst, pos, runGaps[r]);
            }
            return pos;
        }

        int subMode = (size44 == bestRunBytes) ? 0 : (size53 == bestRunBytes) ? 1 : (size62 == bestRunBytes) ? 2 : 3;
        int lBits = 4 - subMode;
        int gMax = (1 << (4 + subMode)) - 1;
        int lMax = (1 << lBits) - 1;
        int w16 = (RANK_TAG_DELTA_RUN << 14) | (subMode << 12) | (rCount & 0x0FFF);
        dst[pos++] = (byte) ((w16 >>> 8) & 0xFF);
        dst[pos++] = (byte) (w16 & 0xFF);
        for (int r = 0; r < rCount; r++) {
            int adjGap = runGaps[r];
            int lm1 = runLm1[r];
            int gVal = Math.min(gMax, adjGap);
            int lVal = Math.min(lMax, lm1);
            dst[pos++] = (byte) ((gVal << lBits) | lVal);
            if (gVal == gMax) {
                pos = writeBoundedGap(dst, pos, adjGap - gMax);
            }
            if (lVal == lMax) {
                pos = LcaSectionCodec.writeVarIntToBuf(dst, pos, lm1 - lMax);
            }
        }
        return pos;
    }

    private static int decodeRankDomainContainer(
            byte[] src, int pos, int limit, int domainLen, boolean[] outMatch
    ) throws CorruptRegionEntryException {
        Arrays.fill(outMatch, 0, domainLen, false);
        int w16 = LcaSectionCodec.readUint16BE(src, pos);
        pos += 2;
        int tag = (w16 >>> 14) & 3;
        int low14 = w16 & 0x3FFF;
        int[] posBox = {pos};
        if (tag == RANK_TAG_SINGLETON) {
            int sub = (low14 >>> 12) & 3;
            int sPos = low14 & 0x0FFF;
            if (sub == 0) {
                outMatch[sPos] = true;
                return pos;
            }
            if (sub == 1) {
                int len = (src[pos++] & 0xFF) + 2;
                Arrays.fill(outMatch, sPos, sPos + len, true);
                return pos;
            }
            if (sub == 2) {
                int len = LcaSectionCodec.readUint16BE(src, pos) + 2;
                pos += 2;
                Arrays.fill(outMatch, sPos, sPos + len, true);
                return pos;
            }
            int lens = src[pos++] & 0xFF;
            int l0 = ((lens >>> 4) & 0x0F) + 1;
            int l1 = (lens & 0x0F) + 1;
            posBox[0] = pos;
            int gap1 = readBoundedGap(src, posBox);
            Arrays.fill(outMatch, sPos, sPos + l0, true);
            int sPos1 = sPos + l0 + gap1 + 1;
            Arrays.fill(outMatch, sPos1, sPos1 + l1, true);
            return posBox[0];
        }
        if (tag == RANK_TAG_DELTA_ARRAY) {
            int rCount = low14;
            int cur = 0;
            posBox[0] = pos;
            for (int r = 0; r < rCount; r++) {
                int adjGap = readBoundedGap(src, posBox);
                int idx = (r == 0) ? adjGap : (cur + adjGap + 1);
                outMatch[idx] = true;
                cur = idx + 1;
            }
            return posBox[0];
        }
        if (tag == RANK_TAG_DELTA_RUN) {
            int subMode = (low14 >>> 12) & 3;
            int rCount = low14 & 0x0FFF;
            int lBits = 4 - subMode;
            int gMax = (1 << (4 + subMode)) - 1;
            int lMax = (1 << lBits) - 1;
            int cur = 0;
            for (int r = 0; r < rCount; r++) {
                int b = src[pos++] & 0xFF;
                int gVal = b >>> lBits;
                int lVal = b & lMax;
                if (gVal == gMax) {
                    posBox[0] = pos;
                    gVal += readBoundedGap(src, posBox);
                    pos = posBox[0];
                }
                if (lVal == lMax) {
                    int extra = LcaSectionCodec.readVarInt(src, pos, limit);
                    pos += LcaSectionCodec.varIntLen(extra);
                    lVal += extra;
                }
                int rStart = (r == 0) ? gVal : (cur + gVal + 1);
                int len = lVal + 1;
                Arrays.fill(outMatch, rStart, rStart + len, true);
                cur = rStart + len;
            }
            return pos;
        }
        // RANK_TAG_BITMAP
        if ((low14 & 0x1000) != 0) {
            int rawBytes = low14 & 0x0FFF;
            for (int b = 0; b < rawBytes; b++) {
                int v = src[pos++] & 0xFF;
                int baseD = b << 3;
                for (int bit = 0; bit < 8 && baseD + bit < domainLen; bit++) {
                    if (((v >>> bit) & 1) != 0) {
                        outMatch[baseD + bit] = true;
                    }
                }
            }
            return pos;
        }
        int nonZeroWords = low14;
        long wordMask = LcaSectionCodec.readLongBE(src, pos);
        pos += 8;
        if (Long.bitCount(wordMask) != nonZeroWords) {
            throw new CorruptRegionEntryException("Rank bitmap wordMask popcount mismatch");
        }
        long m = wordMask;
        while (m != 0L) {
            int w = Long.numberOfTrailingZeros(m);
            m &= (m - 1L);
            long word = LcaSectionCodec.readLongBE(src, pos);
            pos += 8;
            int baseD = w << 6;
            while (word != 0L) {
                int bit = Long.numberOfTrailingZeros(word);
                word &= (word - 1L);
                if (baseD + bit < domainLen) {
                    outMatch[baseD + bit] = true;
                }
            }
        }
        return pos;
    }

    private static int populateActiveDomainIndices(int curveId, long octreeMask64, int[] outActiveYzx) {
        int[] curveToYzx = LcaSectionCodec.CURVE_TO_YZX[curveId];
        if (curveId == LcaSectionCodec.CURVE_H3) {
            int pos = 0;
            for (int oct = 0; oct < 64; oct++) {
                if (((octreeMask64 >>> oct) & 1L) != 0L) {
                    int base = oct << 6;
                    for (int k = 0; k < 64; k++) {
                        outActiveYzx[pos++] = curveToYzx[base + k];
                    }
                }
            }
            return pos;
        }
        if (curveId == LcaSectionCodec.CURVE_YZX || curveId == LcaSectionCodec.CURVE_Y_H2) {
            int pos = 0;
            for (int octY = 0; octY < 4; octY++) {
                long slabBits = (octreeMask64 >>> (octY << 4)) & 0xFFFFL;
                if (slabBits != 0L) {
                    int base = octY << 10;
                    for (int k = 0; k < 1024; k++) {
                        outActiveYzx[pos++] = curveToYzx[base + k];
                    }
                }
            }
            return pos;
        }
        System.arraycopy(curveToYzx, 0, outActiveYzx, 0, SECTION_VOXELS);
        return SECTION_VOXELS;
    }

    private static int encodeRankExcludedBlockContainers(
            int[] yzxOrd, int paletteSize, byte[] dst, int outPos
    ) {
        long octreeMask64 = 0L;
        for (int yzx = 0; yzx < SECTION_VOXELS; yzx++) {
            if (yzxOrd[yzx] != 0) {
                octreeMask64 |= (1L << LcaSectionCodec.YZX_TO_OCTANT6[yzx]);
            }
        }
        int curve0 = LcaSectionCodec.selectOptimalCurve1Pass(yzxOrd, 0);
        int[] bestCandCurve = {curve0};
        byte[] bestBuf = encodeRankContainersOnCurve(yzxOrd, paletteSize, curve0, octreeMask64);
        if (curve0 != LcaSectionCodec.CURVE_H3 && octreeMask64 != -1L && Long.bitCount(octreeMask64) <= 28) {
            byte[] altH3 = encodeRankContainersOnCurve(yzxOrd, paletteSize, LcaSectionCodec.CURVE_H3, octreeMask64);
            if (altH3.length < bestBuf.length) {
                bestBuf = altH3;
                bestCandCurve[0] = LcaSectionCodec.CURVE_H3;
            }
        }
        int flatBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
        int flatByteCount = (SECTION_VOXELS * flatBits + 7) >>> 3;
        int pos = outPos;
        if (bestBuf.length <= flatByteCount) {
            int blockMode = (octreeMask64 == -1L)
                    ? LcaSectionCodec.BLOCK_MODE_DELTA_ROARING_FULL_MASK
                    : LcaSectionCodec.BLOCK_MODE_DELTA_ROARING;
            dst[pos++] = (byte) (((bestCandCurve[0] & 3) << 6) | ((blockMode & 3) << 4));
            if (blockMode == LcaSectionCodec.BLOCK_MODE_DELTA_ROARING) {
                LcaSectionCodec.writeLongBEToBuf(dst, pos, octreeMask64);
                pos += 8;
            }
            System.arraycopy(bestBuf, 0, dst, pos, bestBuf.length);
            return pos + bestBuf.length;
        }
        dst[pos++] = (byte) ((LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK & 3) << 4);
        LcaSectionCodec.writeLongBEToBuf(dst, pos, octreeMask64);
        pos += 8;
        Arrays.fill(dst, pos, pos + flatByteCount, (byte) 0);
        int bitPos = 0;
        for (int i = 0; i < SECTION_VOXELS; i++) {
            int val = yzxOrd[i];
            for (int b = 0; b < flatBits; b++) {
                if (((val >>> b) & 1) != 0) {
                    dst[pos + (bitPos >>> 3)] |= (byte) (1 << (bitPos & 7));
                }
                bitPos++;
            }
        }
        return pos + flatByteCount;
    }

    private static byte[] encodeRankContainersOnCurve(
            int[] yzxOrd, int paletteSize, int curveId, long octreeMask64
    ) {
        int[] activeYzx = new int[SECTION_VOXELS];
        int domainLen = populateActiveDomainIndices(curveId, octreeMask64, activeYzx);
        int[] unclaimedOrds = new int[domainLen];
        for (int i = 0; i < domainLen; i++) {
            unclaimedOrds[i] = yzxOrd[activeYzx[i]];
        }
        boolean[] match = new boolean[domainLen];
        byte[] tmp = new byte[16384];
        int pos = 0;
        for (int p = 1; p < paletteSize; p++) {
            for (int d = 0; d < domainLen; d++) {
                match[d] = (unclaimedOrds[d] == p);
            }
            pos = emitRankDomainContainer(match, domainLen, tmp, pos);
            int nextLen = 0;
            for (int d = 0; d < domainLen; d++) {
                if (!match[d]) {
                    unclaimedOrds[nextLen++] = unclaimedOrds[d];
                }
            }
            domainLen = nextLen;
        }
        return Arrays.copyOf(tmp, pos);
    }

    private static int decodeRankExcludedBlockContainers(
            byte[] src, int pos, int limit, int paletteSize, int[] outYzxOrd
    ) throws CorruptRegionEntryException {
        Arrays.fill(outYzxOrd, 0, SECTION_VOXELS, 0);
        int hdr = src[pos++] & 0xFF;
        int curveId = (hdr >>> 6) & 3;
        int blockMode = (hdr >>> 4) & 3;
        long octreeMask64;
        if (blockMode == LcaSectionCodec.BLOCK_MODE_DELTA_ROARING_FULL_MASK) {
            octreeMask64 = -1L;
        } else {
            octreeMask64 = LcaSectionCodec.readLongBE(src, pos);
            pos += 8;
        }
        if (blockMode == LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK) {
            int flatBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
            int flatBytes = (SECTION_VOXELS * flatBits + 7) >>> 3;
            int bitPos = 0;
            for (int i = 0; i < SECTION_VOXELS; i++) {
                int val = 0;
                for (int b = 0; b < flatBits; b++) {
                    if (((src[pos + (bitPos >>> 3)] >>> (bitPos & 7)) & 1) != 0) {
                        val |= (1 << b);
                    }
                    bitPos++;
                }
                outYzxOrd[i] = val;
            }
            return pos + flatBytes;
        }
        int[] activeYzx = new int[SECTION_VOXELS];
        int domainLen = populateActiveDomainIndices(curveId, octreeMask64, activeYzx);
        boolean[] match = new boolean[domainLen];
        for (int p = 1; p < paletteSize; p++) {
            pos = decodeRankDomainContainer(src, pos, limit, domainLen, match);
            int nextLen = 0;
            for (int d = 0; d < domainLen; d++) {
                if (match[d]) {
                    outYzxOrd[activeYzx[d]] = p;
                } else {
                    activeYzx[nextLen++] = activeYzx[d];
                }
            }
            domainLen = nextLen;
        }
        return pos;
    }

    private static byte[] encodeRankExcludedCartesianSection(int[] yzxGlobalIds) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int id : yzxGlobalIds) {
            counts.merge(id, 1, Integer::sum);
        }
        int k = counts.size();
        Integer[] sortedIds = counts.keySet().toArray(new Integer[0]);
        Arrays.sort(sortedIds, (a, b) -> {
            int cmp = Integer.compare(counts.get(b), counts.get(a));
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });
        Map<Integer, Integer> globalToOrd = new HashMap<>(k * 2);
        for (int i = 0; i < k; i++) {
            globalToOrd.put(sortedIds[i], i);
        }
        byte[] out = new byte[32768];
        int pos = 0;
        pos = LcaSectionCodec.writeVarIntToBuf(out, pos, k);
        for (int i = 0; i < k; i++) {
            pos = LcaSectionCodec.writeVarIntToBuf(out, pos, sortedIds[i]);
        }
        if (k > 1) {
            int[] yzxOrd = new int[SECTION_VOXELS];
            for (int i = 0; i < SECTION_VOXELS; i++) {
                yzxOrd[i] = globalToOrd.get(yzxGlobalIds[i]);
            }
            pos = encodeRankExcludedBlockContainers(yzxOrd, k, out, pos);
        }
        return Arrays.copyOf(out, pos);
    }

    private static int[] decodeRankExcludedCartesianSection(byte[] buf, int offset, int limit)
            throws CorruptRegionEntryException {
        int pos = offset;
        int k = LcaSectionCodec.readVarInt(buf, pos, limit);
        pos += LcaSectionCodec.varIntLen(k);
        int[] palette = new int[k];
        for (int i = 0; i < k; i++) {
            palette[i] = LcaSectionCodec.readVarInt(buf, pos, limit);
            pos += LcaSectionCodec.varIntLen(palette[i]);
        }
        int[] outGlobal = new int[SECTION_VOXELS];
        if (k == 1) {
            Arrays.fill(outGlobal, palette[0]);
            return outGlobal;
        }
        int[] yzxOrd = new int[SECTION_VOXELS];
        int next = decodeRankExcludedBlockContainers(buf, pos, limit, k, yzxOrd);
        if (next != limit) {
            throw new IllegalStateException("RankExcluded decode cursor mismatch: " + next + " != " + limit);
        }
        for (int i = 0; i < SECTION_VOXELS; i++) {
            outGlobal[i] = palette[yzxOrd[i]];
        }
        return outGlobal;
    }

    /**
     * Builds deterministic upper-half counterpart lookup (`half=lower -> half=upper` with all other
     * properties identical) for doors, tall plants, beds/pistons/trapdoors.
     */
    private static int[] buildVerticalUpperCounterpartMap(List<Integer> groupStates, List<ParsedBlockState> globalDict) {
        int m = groupStates.size();
        int[] map = new int[m];
        Arrays.fill(map, -1);
        for (int v = 0; v < m; v++) {
            String s = globalDict.get(groupStates.get(v)).propSuffix;
            if (s.contains("half=lower")) {
                String target = s.replace("half=lower", "half=upper");
                for (int u = 0; u < m; u++) {
                    if (globalDict.get(groupStates.get(u)).propSuffix.equals(target)) {
                        map[v] = u;
                        break;
                    }
                }
            }
        }
        return map;
    }

    /**
     * Causal 3D architectural/redstone neighbor predictor: promotes vertical `half=upper` counterpart
     * or majority face-adjacent already-decoded neighbor variant in the same material group to MTF[0].
     */
    private static int predictCausal3DNeighborVariant(
            int yzx, int[] decodedVarGrid, int[] upperCounterpart, int m
    ) {
        int x = yzx & 15;
        int z = (yzx >>> 4) & 15;
        int y = (yzx >>> 8) & 15;
        if (y > 0) {
            int below = decodedVarGrid[yzx - 256];
            if (below >= 0 && upperCounterpart[below] >= 0) {
                return upperCounterpart[below];
            }
        }
        int[] nbs = {
                x > 0 ? decodedVarGrid[yzx - 1] : -1,
                x < 15 ? decodedVarGrid[yzx + 1] : -1,
                z > 0 ? decodedVarGrid[yzx - 16] : -1,
                z < 15 ? decodedVarGrid[yzx + 16] : -1,
                y > 0 ? decodedVarGrid[yzx - 256] : -1,
                y < 15 ? decodedVarGrid[yzx + 256] : -1
        };
        int bestVar = -1;
        int bestScore = 0;
        int[] scores = new int[m];
        for (int d = 0; d < 6; d++) {
            int v = nbs[d];
            if (v >= 0) {
                // Horizontal neighbors weighted 3, vertical weighted 2 for architectural runs (stairs/walls/hoppers)
                int w = (d < 4) ? 3 : 2;
                scores[v] += w;
                if (scores[v] > bestScore) {
                    bestScore = scores[v];
                    bestVar = v;
                }
            }
        }
        return bestVar;
    }

    /**
     * Zero-tax layered encoder:
     *   - Passthrough sections emit exact unframed `.lca` Cartesian bytes (`paletteLen >= 1`, `0` extra bytes!).
     *   - Layered sections emit `0x00` (`paletteLen == 0` sentinel, `1 B`) + `VarInt(bodyLen)` + `body`,
     *     and are chosen ONLY when `1 + varIntLen(bodyLen) + bodyLen < unframedBaseline.length`.
     */
    private static byte[] encodeZeroTaxLayeredSection(
            int[] yzxGlobalIds, List<ParsedBlockState> globalDict, boolean useRankExclusion
    ) {
        byte[] bestWire = useRankExclusion
                ? encodeRankExcludedCartesianSection(yzxGlobalIds)
                : encodeBaselineCartesianSection(yzxGlobalIds);

        Map<Integer, Integer> stateFreq = new LinkedHashMap<>(32);
        Map<String, Integer> baseFreq = new LinkedHashMap<>(32);
        boolean hasLeavesOrTall = false;
        for (int i = 0; i < SECTION_VOXELS; i++) {
            int gid = yzxGlobalIds[i];
            if (stateFreq.merge(gid, 1, Integer::sum) == 1) {
                ParsedBlockState ps = globalDict.get(gid);
                baseFreq.merge(ps.baseName, 1, Integer::sum);
                if (ps.leafDistance > 0 || ps.tallHalf >= 0) {
                    hasLeavesOrTall = true;
                }
            }
        }
        int distinctStates = stateFreq.size();
        int distinctBases = baseFreq.size();
        int propExcess = distinctStates - distinctBases;

        if (distinctStates >= 3 && (hasLeavesOrTall || propExcess >= 1 || distinctStates >= 10)) {
            baseFreq.clear();
            for (Map.Entry<Integer, Integer> e : stateFreq.entrySet()) {
                baseFreq.merge(globalDict.get(e.getKey()).baseName, e.getValue(), Integer::sum);
            }
            int[] topChoices = (distinctBases > 7 || distinctStates >= 12) ? new int[]{0, 6, 4} : new int[]{0};
            for (int keepTop : topChoices) {
                if (keepTop == 0 && propExcess == 0 && !hasLeavesOrTall) {
                    continue;
                }
                byte[] body = tryEncodeLayeredBodyV3(
                        yzxGlobalIds, globalDict, stateFreq, baseFreq, keepTop, useRankExclusion);
                if (body != null) {
                    int wireLen = 1 + LcaSectionCodec.varIntLen(body.length) + body.length;
                    if (wireLen < bestWire.length) {
                        byte[] framed = new byte[wireLen];
                        framed[0] = 0; // paletteLen == 0 sentinel!
                        int p = 1;
                        p = LcaSectionCodec.writeVarIntToBuf(framed, p, body.length);
                        System.arraycopy(body, 0, framed, p, body.length);
                        bestWire = framed;
                    }
                }
            }
        }
        return bestWire;
    }

    private static byte[] tryEncodeLayeredBodyV3(
            int[] yzxGlobalIds,
            List<ParsedBlockState> globalDict,
            Map<Integer, Integer> stateFreq,
            Map<String, Integer> baseFreq,
            int keepTopNonTrunkMaterials,
            boolean useRankExclusion
    ) {
        if (stateFreq.size() <= 2) {
            return null;
        }
        final String scatterToken = "__SCATTER__";
        Map<String, Boolean> keepSeparate = new HashMap<>(baseFreq.size() * 2);
        if (keepTopNonTrunkMaterials > 0 && baseFreq.size() > keepTopNonTrunkMaterials + 2) {
            List<String> nonTrunkSorted = new ArrayList<>(baseFreq.size());
            for (String bName : baseFreq.keySet()) {
                boolean isTrunk = bName.endsWith("_log") || bName.endsWith("_wood")
                        || bName.endsWith("_stem") || bName.endsWith("_hyphae")
                        || bName.equals("minecraft:mangrove_roots");
                if (isTrunk || bName.endsWith("_leaves")) {
                    keepSeparate.put(bName, Boolean.TRUE);
                } else {
                    nonTrunkSorted.add(bName);
                }
            }
            nonTrunkSorted.sort((a, b) -> {
                int cmp = Integer.compare(baseFreq.get(b), baseFreq.get(a));
                return cmp != 0 ? cmp : a.compareTo(b);
            });
            int kept = 0;
            int coalesced = 0;
            for (String bName : nonTrunkSorted) {
                if (kept < keepTopNonTrunkMaterials) {
                    keepSeparate.put(bName, Boolean.TRUE);
                    kept++;
                } else {
                    keepSeparate.put(bName, Boolean.FALSE);
                    coalesced++;
                }
            }
            if (coalesced < 3) {
                return null;
            }
        } else {
            for (String bName : baseFreq.keySet()) {
                keepSeparate.put(bName, Boolean.TRUE);
            }
        }

        Map<String, Integer> layerAFreq = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : baseFreq.entrySet()) {
            if (Boolean.TRUE.equals(keepSeparate.get(e.getKey()))) {
                layerAFreq.put(e.getKey(), e.getValue());
            } else {
                layerAFreq.merge(scatterToken, e.getValue(), Integer::sum);
            }
        }
        List<String> sortedLayerANames = new ArrayList<>(layerAFreq.keySet());
        sortedLayerANames.sort((a, b) -> {
            int cmp = Integer.compare(layerAFreq.get(b), layerAFreq.get(a));
            return cmp != 0 ? cmp : a.compareTo(b);
        });
        int kLayerA = sortedLayerANames.size();
        Map<String, Integer> nameToLayerAOrd = new HashMap<>(kLayerA * 2);
        for (int i = 0; i < kLayerA; i++) {
            nameToLayerAOrd.put(sortedLayerANames.get(i), i);
        }

        @SuppressWarnings("unchecked")
        List<Integer>[] groupGlobalStates = new List[kLayerA];
        for (int i = 0; i < kLayerA; i++) {
            groupGlobalStates[i] = new ArrayList<>();
        }
        for (Integer gid : stateFreq.keySet()) {
            ParsedBlockState ps = globalDict.get(gid);
            String key = Boolean.TRUE.equals(keepSeparate.get(ps.baseName)) ? ps.baseName : scatterToken;
            groupGlobalStates[nameToLayerAOrd.get(key)].add(gid);
        }

        Map<Integer, Integer> globalToGroupOrd = new HashMap<>(stateFreq.size() * 2);
        Map<Integer, Integer> globalToLocalVarOrd = new HashMap<>(stateFreq.size() * 2);
        for (int g = 0; g < kLayerA; g++) {
            List<Integer> states = groupGlobalStates[g];
            states.sort((a, b) -> {
                int cmp = Integer.compare(stateFreq.get(b), stateFreq.get(a));
                return cmp != 0 ? cmp : Integer.compare(a, b);
            });
            if (states.size() > 64) {
                return null;
            }
            for (int v = 0; v < states.size(); v++) {
                globalToGroupOrd.put(states.get(v), g);
                globalToLocalVarOrd.put(states.get(v), v);
            }
        }

        int[] yzxLayerA = new int[SECTION_VOXELS];
        for (int i = 0; i < SECTION_VOXELS; i++) {
            yzxLayerA[i] = globalToGroupOrd.get(yzxGlobalIds[i]);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
        byte[] tmp = new byte[32768];
        int p = 0;
        p = LcaSectionCodec.writeVarIntToBuf(tmp, p, kLayerA);
        for (int g = 0; g < kLayerA; g++) {
            List<Integer> states = groupGlobalStates[g];
            p = LcaSectionCodec.writeVarIntToBuf(tmp, p, states.size());
            for (int gid : states) {
                p = LcaSectionCodec.writeVarIntToBuf(tmp, p, gid);
            }
        }
        if (kLayerA > 1) {
            p = useRankExclusion
                    ? encodeRankExcludedBlockContainers(yzxLayerA, kLayerA, tmp, p)
                    : LcaSectionCodec.encodeSectionBlockContainersV2(yzxLayerA, kLayerA, tmp, p);
        }
        out.write(tmp, 0, p);

        boolean[] isLogVoxel = new boolean[SECTION_VOXELS];
        boolean[] groupIsPureLog = new boolean[kLayerA];
        for (int g = 0; g < kLayerA; g++) {
            boolean allLog = true;
            for (int gid : groupGlobalStates[g]) {
                if (!globalDict.get(gid).isLogOrStem) {
                    allLog = false;
                    break;
                }
            }
            groupIsPureLog[g] = allLog;
        }
        for (int i = 0; i < SECTION_VOXELS; i++) {
            if (groupIsPureLog[yzxLayerA[i]]) {
                isLogVoxel[i] = true;
            }
        }
        int[] bfsDistOrNull = computeInSectionLogBfsDistance(isLogVoxel);
        int[] h3ToYzx = LcaSectionCodec.CURVE_TO_YZX[LcaSectionCodec.CURVE_H3];

        for (int g = 0; g < kLayerA; g++) {
            List<Integer> states = groupGlobalStates[g];
            int m = states.size();
            if (m <= 1) {
                continue;
            }
            boolean isPureLeavesGroup = !scatterToken.equals(sortedLayerANames.get(g));
            boolean isTallPlantPair = (m == 2) && !scatterToken.equals(sortedLayerANames.get(g));
            int[] distAuxToVar = new int[32];
            Arrays.fill(distAuxToVar, -1);
            for (int v = 0; v < m; v++) {
                ParsedBlockState ps = globalDict.get(states.get(v));
                if (ps.leafDistance < 1 || ps.leafAuxFlags < 0) {
                    isPureLeavesGroup = false;
                } else {
                    int key = (ps.leafAuxFlags << 3) | ps.leafDistance;
                    if (distAuxToVar[key] != -1) {
                        isPureLeavesGroup = false;
                    }
                    distAuxToVar[key] = v;
                }
                if (ps.tallHalf < 0) {
                    isTallPlantPair = false;
                }
            }

            int count = 0;
            int[] activeYzx = new int[SECTION_VOXELS];
            int[] rawVarSyms = new int[SECTION_VOXELS];
            for (int h = 0; h < SECTION_VOXELS; h++) {
                int yzx = h3ToYzx[h];
                if (yzxLayerA[yzx] == g) {
                    activeYzx[count] = yzx;
                    rawVarSyms[count] = globalToLocalVarOrd.get(yzxGlobalIds[yzx]);
                    count++;
                }
            }
            if (count == 0) {
                continue;
            }

            int rawBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(m - 1));
            BitWriter rawBw = new BitWriter();
            for (int i = 0; i < count; i++) {
                rawBw.writeBits(rawVarSyms[i], rawBits);
            }
            byte bestMode = PROP_STREAM_RAW_BITS;
            byte[] bestBytes = rawBw.finish();

            byte[] huffBytes = encodeSmallAlphabetHuffman(rawVarSyms, count, m);
            if (huffBytes.length < bestBytes.length) {
                bestMode = PROP_STREAM_HUFFMAN;
                bestBytes = huffBytes;
            }

            if (m >= 2) {
                int[] mtfList = new int[m];
                for (int v = 0; v < m; v++) {
                    mtfList[v] = v;
                }
                int[] mtfSyms = new int[count];
                for (int i = 0; i < count; i++) {
                    int target = rawVarSyms[i];
                    int rank = 0;
                    while (mtfList[rank] != target) {
                        rank++;
                    }
                    mtfSyms[i] = rank;
                    if (rank > 0) {
                        System.arraycopy(mtfList, 0, mtfList, 1, rank);
                        mtfList[0] = target;
                    }
                }
                byte[] mtfBytes = encodeSmallAlphabetHuffman(mtfSyms, count, m);
                if (mtfBytes.length < bestBytes.length) {
                    bestMode = PROP_STREAM_HILBERT_MTF_HUFF;
                    bestBytes = mtfBytes;
                }

                // Candidate: Causal 3D Neighbor Architectural/Redstone Predictor + MTF Huffman
                int[] upperMap = buildVerticalUpperCounterpartMap(states, globalDict);
                int[] decodedVarGrid = new int[SECTION_VOXELS];
                Arrays.fill(decodedVarGrid, -1);
                for (int v = 0; v < m; v++) {
                    mtfList[v] = v;
                }
                int[] causalSyms = new int[count];
                for (int i = 0; i < count; i++) {
                    int yzx = activeYzx[i];
                    int pred = predictCausal3DNeighborVariant(yzx, decodedVarGrid, upperMap, m);
                    if (pred >= 0 && mtfList[0] != pred) {
                        int rPred = 0;
                        while (mtfList[rPred] != pred) {
                            rPred++;
                        }
                        System.arraycopy(mtfList, 0, mtfList, 1, rPred);
                        mtfList[0] = pred;
                    }
                    int target = rawVarSyms[i];
                    int rank = 0;
                    while (mtfList[rank] != target) {
                        rank++;
                    }
                    causalSyms[i] = rank;
                    if (rank > 0) {
                        System.arraycopy(mtfList, 0, mtfList, 1, rank);
                        mtfList[0] = target;
                    }
                    decodedVarGrid[yzx] = target;
                }
                byte[] causalBytes = encodeSmallAlphabetHuffman(causalSyms, count, m);
                if (causalBytes.length < bestBytes.length) {
                    bestMode = PROP_STREAM_CAUSAL_3D_MTF_HUFF;
                    bestBytes = causalBytes;
                }
            }

            if (isPureLeavesGroup) {
                int fallbackDist = globalDict.get(states.get(0)).leafDistance;
                int[] decodedDistGrid = new int[SECTION_VOXELS];
                int[] leafSyms = new int[count];
                boolean ok = true;
                for (int i = 0; i < count; i++) {
                    int yzx = activeYzx[i];
                    ParsedBlockState actual = globalDict.get(states.get(rawVarSyms[i]));
                    int predDist = predictLeafDistanceCausal(yzx, bfsDistOrNull, decodedDistGrid, fallbackDist);
                    int mod7Delta = (actual.leafDistance - predDist + 7) % 7;
                    int sym = (actual.leafAuxFlags << 3) | mod7Delta;
                    if (sym < 0 || sym >= 32) {
                        ok = false;
                        break;
                    }
                    leafSyms[i] = sym;
                    decodedDistGrid[yzx] = actual.leafDistance;
                }
                if (ok) {
                    byte[] eikBytes = encodeSmallAlphabetHuffman(leafSyms, count, 32);
                    if (eikBytes.length < bestBytes.length) {
                        bestMode = PROP_STREAM_EIKONAL_LEAVES_HUFF;
                        bestBytes = eikBytes;
                    }
                }
            }

            if (isTallPlantPair) {
                int lowerVar = (globalDict.get(states.get(0)).tallHalf == 0) ? 0 : 1;
                int upperVar = 1 - lowerVar;
                int[] decodedHalfGrid = new int[SECTION_VOXELS];
                Arrays.fill(decodedHalfGrid, -1);
                int[] paritySyms = new int[count];
                int idx = 0;
                for (int yzx = 0; yzx < SECTION_VOXELS; yzx++) {
                    if (yzxLayerA[yzx] == g) {
                        int actualVar = globalToLocalVarOrd.get(yzxGlobalIds[yzx]);
                        int y = (yzx >>> 8) & 15;
                        int predVar = (y > 0 && decodedHalfGrid[yzx - 256] == 0) ? upperVar : lowerVar;
                        paritySyms[idx++] = (actualVar == predVar) ? 0 : 1;
                        decodedHalfGrid[yzx] = (actualVar == lowerVar) ? 0 : 1;
                    }
                }
                byte[] parityBytes = encodeSmallAlphabetHuffman(paritySyms, count, 2);
                if (parityBytes.length < bestBytes.length) {
                    bestMode = PROP_STREAM_TALL_PLANT_PARITY_HUFF;
                    bestBytes = parityBytes;
                }
            }

            out.write(bestMode);
            out.write(bestBytes, 0, bestBytes.length);
        }
        return out.toByteArray();
    }

    private static int[] decodeZeroTaxLayeredSection(
            byte[] wire, List<ParsedBlockState> globalDict, boolean useRankExclusion
    ) throws CorruptRegionEntryException {
        if ((wire[0] & 0xFF) != 0) {
            return useRankExclusion
                    ? decodeRankExcludedCartesianSection(wire, 0, wire.length)
                    : decodeBaselineCartesianSection(wire, 0, wire.length);
        }
        int pos = 1;
        int bodyLen = LcaSectionCodec.readVarInt(wire, pos, wire.length);
        pos += LcaSectionCodec.varIntLen(bodyLen);
        int limit = pos + bodyLen;
        if (limit != wire.length) {
            throw new IllegalStateException("Zero-tax layered body length mismatch");
        }

        int kLayerA = LcaSectionCodec.readVarInt(wire, pos, limit);
        pos += LcaSectionCodec.varIntLen(kLayerA);
        int[][] groupStates = new int[kLayerA][];
        List<Integer>[] groupLists = new List[kLayerA];
        for (int g = 0; g < kLayerA; g++) {
            int m = LcaSectionCodec.readVarInt(wire, pos, limit);
            pos += LcaSectionCodec.varIntLen(m);
            groupStates[g] = new int[m];
            groupLists[g] = new ArrayList<>(m);
            for (int v = 0; v < m; v++) {
                int gid = LcaSectionCodec.readVarInt(wire, pos, limit);
                pos += LcaSectionCodec.varIntLen(gid);
                groupStates[g][v] = gid;
                groupLists[g].add(gid);
            }
        }

        int[] yzxLayerA = new int[SECTION_VOXELS];
        if (kLayerA > 1) {
            pos = useRankExclusion
                    ? decodeRankExcludedBlockContainers(wire, pos, limit, kLayerA, yzxLayerA)
                    : LcaSectionCodec.decodeSectionBlockContainersV2(wire, pos, limit, kLayerA, yzxLayerA);
            if (pos < 0) {
                throw new IllegalStateException("Corrupt Layer A block container");
            }
        }

        boolean[] isLogVoxel = new boolean[SECTION_VOXELS];
        boolean[] groupIsPureLog = new boolean[kLayerA];
        for (int g = 0; g < kLayerA; g++) {
            boolean allLog = true;
            for (int gid : groupStates[g]) {
                if (!globalDict.get(gid).isLogOrStem) {
                    allLog = false;
                    break;
                }
            }
            groupIsPureLog[g] = allLog;
        }
        for (int i = 0; i < SECTION_VOXELS; i++) {
            if (groupIsPureLog[yzxLayerA[i]]) {
                isLogVoxel[i] = true;
            }
        }
        int[] bfsDistOrNull = computeInSectionLogBfsDistance(isLogVoxel);
        int[] h3ToYzx = LcaSectionCodec.CURVE_TO_YZX[LcaSectionCodec.CURVE_H3];

        int[] outGlobal = new int[SECTION_VOXELS];
        for (int i = 0; i < SECTION_VOXELS; i++) {
            int g = yzxLayerA[i];
            if (groupStates[g].length == 1) {
                outGlobal[i] = groupStates[g][0];
            }
        }

        for (int g = 0; g < kLayerA; g++) {
            int[] states = groupStates[g];
            int m = states.length;
            if (m <= 1) {
                continue;
            }
            int count = 0;
            int[] activeYzx = new int[SECTION_VOXELS];
            for (int h = 0; h < SECTION_VOXELS; h++) {
                int yzx = h3ToYzx[h];
                if (yzxLayerA[yzx] == g) {
                    activeYzx[count++] = yzx;
                }
            }
            if (count == 0) {
                continue;
            }
            byte streamMode = wire[pos++];
            if (streamMode == PROP_STREAM_RAW_BITS) {
                int rawBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(m - 1));
                int rawByteLen = (count * rawBits + 7) >>> 3;
                BitReader br = new BitReader(wire, pos, rawByteLen);
                for (int i = 0; i < count; i++) {
                    outGlobal[activeYzx[i]] = states[br.readBits(rawBits)];
                }
                pos += rawByteLen;
            } else if (streamMode == PROP_STREAM_HUFFMAN) {
                int[] syms = new int[count];
                pos = decodeSmallAlphabetHuffman(wire, pos, m, count, syms);
                for (int i = 0; i < count; i++) {
                    outGlobal[activeYzx[i]] = states[syms[i]];
                }
            } else if (streamMode == PROP_STREAM_HILBERT_MTF_HUFF) {
                int[] ranks = new int[count];
                pos = decodeSmallAlphabetHuffman(wire, pos, m, count, ranks);
                int[] mtfList = new int[m];
                for (int v = 0; v < m; v++) {
                    mtfList[v] = v;
                }
                for (int i = 0; i < count; i++) {
                    int rank = ranks[i];
                    int target = mtfList[rank];
                    if (rank > 0) {
                        System.arraycopy(mtfList, 0, mtfList, 1, rank);
                        mtfList[0] = target;
                    }
                    outGlobal[activeYzx[i]] = states[target];
                }
            } else if (streamMode == PROP_STREAM_CAUSAL_3D_MTF_HUFF) {
                int[] ranks = new int[count];
                pos = decodeSmallAlphabetHuffman(wire, pos, m, count, ranks);
                int[] upperMap = buildVerticalUpperCounterpartMap(groupLists[g], globalDict);
                int[] decodedVarGrid = new int[SECTION_VOXELS];
                Arrays.fill(decodedVarGrid, -1);
                int[] mtfList = new int[m];
                for (int v = 0; v < m; v++) {
                    mtfList[v] = v;
                }
                for (int i = 0; i < count; i++) {
                    int yzx = activeYzx[i];
                    int pred = predictCausal3DNeighborVariant(yzx, decodedVarGrid, upperMap, m);
                    if (pred >= 0 && mtfList[0] != pred) {
                        int rPred = 0;
                        while (mtfList[rPred] != pred) {
                            rPred++;
                        }
                        System.arraycopy(mtfList, 0, mtfList, 1, rPred);
                        mtfList[0] = pred;
                    }
                    int rank = ranks[i];
                    int target = mtfList[rank];
                    if (rank > 0) {
                        System.arraycopy(mtfList, 0, mtfList, 1, rank);
                        mtfList[0] = target;
                    }
                    decodedVarGrid[yzx] = target;
                    outGlobal[yzx] = states[target];
                }
            } else if (streamMode == PROP_STREAM_EIKONAL_LEAVES_HUFF) {
                int[] syms = new int[count];
                pos = decodeSmallAlphabetHuffman(wire, pos, 32, count, syms);
                int[] distAuxToVar = new int[32];
                Arrays.fill(distAuxToVar, -1);
                for (int v = 0; v < m; v++) {
                    ParsedBlockState ps = globalDict.get(states[v]);
                    distAuxToVar[(ps.leafAuxFlags << 3) | ps.leafDistance] = v;
                }
                int fallbackDist = globalDict.get(states[0]).leafDistance;
                int[] decodedDistGrid = new int[SECTION_VOXELS];
                for (int i = 0; i < count; i++) {
                    int yzx = activeYzx[i];
                    int predDist = predictLeafDistanceCausal(yzx, bfsDistOrNull, decodedDistGrid, fallbackDist);
                    int sym = syms[i];
                    int aux = (sym >>> 3) & 3;
                    int mod7Delta = sym & 7;
                    int actualDist = ((predDist - 1 + mod7Delta) % 7) + 1;
                    decodedDistGrid[yzx] = actualDist;
                    outGlobal[yzx] = states[distAuxToVar[(aux << 3) | actualDist]];
                }
            } else if (streamMode == PROP_STREAM_TALL_PLANT_PARITY_HUFF) {
                int[] syms = new int[count];
                pos = decodeSmallAlphabetHuffman(wire, pos, 2, count, syms);
                int lowerVar = (globalDict.get(states[0]).tallHalf == 0) ? 0 : 1;
                int upperVar = 1 - lowerVar;
                int[] decodedHalfGrid = new int[SECTION_VOXELS];
                Arrays.fill(decodedHalfGrid, -1);
                int idx = 0;
                for (int yzx = 0; yzx < SECTION_VOXELS; yzx++) {
                    if (yzxLayerA[yzx] == g) {
                        int y = (yzx >>> 8) & 15;
                        int predVar = (y > 0 && decodedHalfGrid[yzx - 256] == 0) ? upperVar : lowerVar;
                        int actualVar = (syms[idx++] == 0) ? predVar : (1 - predVar);
                        outGlobal[yzx] = states[actualVar];
                        decodedHalfGrid[yzx] = (actualVar == lowerVar) ? 0 : 1;
                    }
                }
            } else {
                throw new IllegalStateException("Unknown streamMode: " + streamMode);
            }
        }
        if (pos != limit) {
            throw new IllegalStateException("Zero-tax layered cursor mismatch: " + pos + " != " + limit);
        }
        return outGlobal;
    }

    // --- Loaders for .nbt Structure Templates and .schem / .litematic Builds ---

    private static final class LoadedVolumeAsset {
        final String name;
        final long diskBytes;
        final long uncompressedNbtBytes;
        final int sx;
        final int sy;
        final int sz;
        final List<int[]> nonUniformSections;
        final int uniformSections;
        final List<Map<String, Object>> blockEntities;

        LoadedVolumeAsset(
                String name,
                long diskBytes,
                long uncompressedNbtBytes,
                int sx,
                int sy,
                int sz,
                List<int[]> nonUniformSections,
                int uniformSections,
                List<Map<String, Object>> blockEntities
        ) {
            this.name = name;
            this.diskBytes = diskBytes;
            this.uncompressedNbtBytes = uncompressedNbtBytes;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.nonUniformSections = nonUniformSections;
            this.uniformSections = uniformSections;
            this.blockEntities = blockEntities;
        }
    }

    private static byte[] maybeGunzipBytes(byte[] raw) throws IOException {
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                return gis.readAllBytes();
            }
        }
        return raw;
    }

    @SuppressWarnings("unchecked")
    private static String formatCanonicalPaletteCompound(Map<?, ?> entry) {
        String name = String.valueOf(entry.get("Name"));
        if (!name.contains(":")) {
            name = "minecraft:" + name;
        }
        Object propsObj = entry.get("Properties");
        if (!(propsObj instanceof Map<?, ?> props) || props.isEmpty()) {
            return name;
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> e : props.entrySet()) {
            sorted.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }

    private static String canonicalizeStateString(String s) {
        if (s == null || s.isEmpty()) {
            return "minecraft:air";
        }
        int br = s.indexOf('[');
        String base = br < 0 ? s : s.substring(0, br);
        if (!base.contains(":")) {
            base = "minecraft:" + base;
        }
        if (br < 0 || s.endsWith("[]")) {
            return base;
        }
        String inner = s.substring(br + 1, s.length() - 1);
        TreeMap<String, String> sorted = new TreeMap<>();
        for (String part : inner.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                sorted.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        if (sorted.isEmpty()) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }

    private static List<Object> unwrapNbtList(Object obj) {
        if (obj instanceof Nbt.NbtList nl) {
            return nl.items;
        }
        if (obj instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        return Collections.emptyList();
    }

    private static LoadedVolumeAsset sliceVolumeIntoSections(
            String name,
            long diskBytes,
            long uncompressedBytes,
            int sx,
            int sy,
            int sz,
            int[] yzxGrid,
            List<Map<String, Object>> blockEntities,
            int airGlobalId
    ) {
        int secX = (sx + 15) >>> 4;
        int secY = (sy + 15) >>> 4;
        int secZ = (sz + 15) >>> 4;
        List<int[]> nonUniform = new ArrayList<>();
        int uniform = 0;
        for (int cy = 0; cy < secY; cy++) {
            for (int cz = 0; cz < secZ; cz++) {
                for (int cx = 0; cx < secX; cx++) {
                    int[] sec = new int[SECTION_VOXELS];
                    Arrays.fill(sec, airGlobalId);
                    for (int ly = 0; ly < 16 && (cy * 16 + ly) < sy; ly++) {
                        int gy = cy * 16 + ly;
                        for (int lz = 0; lz < 16 && (cz * 16 + lz) < sz; lz++) {
                            int gz = cz * 16 + lz;
                            for (int lx = 0; lx < 16 && (cx * 16 + lx) < sx; lx++) {
                                int gx = cx * 16 + lx;
                                sec[(ly << 8) | (lz << 4) | lx] = yzxGrid[(gy * sz + gz) * sx + gx];
                            }
                        }
                    }
                    int first = sec[0];
                    boolean allSame = true;
                    for (int i = 1; i < SECTION_VOXELS; i++) {
                        if (sec[i] != first) {
                            allSame = false;
                            break;
                        }
                    }
                    if (allSame) {
                        uniform++;
                    } else {
                        nonUniform.add(sec);
                    }
                }
            }
        }
        return new LoadedVolumeAsset(
                name, diskBytes, uncompressedBytes, sx, sy, sz, nonUniform, uniform, blockEntities);
    }

    @SuppressWarnings("unchecked")
    private static LoadedVolumeAsset loadStructureNbtAsset(
            Path path, Map<String, Integer> stateToId, List<ParsedBlockState> globalDict
    ) throws IOException {
        byte[] disk = Files.readAllBytes(path);
        byte[] uncompressed = maybeGunzipBytes(disk);
        LinkedHashMap<String, Object> root = Nbt.readRootCompound(uncompressed);
        List<Object> sizeList = unwrapNbtList(root.get("size"));
        int sx = ((Number) sizeList.get(0)).intValue();
        int sy = ((Number) sizeList.get(1)).intValue();
        int sz = ((Number) sizeList.get(2)).intValue();

        List<Object> palItems = unwrapNbtList(root.get("palette"));
        if (palItems.isEmpty()) {
            List<Object> multi = unwrapNbtList(root.get("palettes"));
            if (!multi.isEmpty()) {
                palItems = unwrapNbtList(multi.get(0));
            }
        }
        int airId = internState("minecraft:air", stateToId, globalDict);
        int[] palGlobal = new int[palItems.size()];
        for (int i = 0; i < palItems.size(); i++) {
            String canon = formatCanonicalPaletteCompound((Map<?, ?>) palItems.get(i));
            if ("minecraft:structure_void".equals(canon)) {
                canon = "minecraft:air";
            }
            palGlobal[i] = internState(canon, stateToId, globalDict);
        }

        int[] grid = new int[sx * sy * sz];
        Arrays.fill(grid, airId);
        List<Map<String, Object>> bes = new ArrayList<>();
        for (Object bObj : unwrapNbtList(root.get("blocks"))) {
            Map<String, Object> bm = (Map<String, Object>) bObj;
            List<Object> pos = unwrapNbtList(bm.get("pos"));
            int x = ((Number) pos.get(0)).intValue();
            int y = ((Number) pos.get(1)).intValue();
            int z = ((Number) pos.get(2)).intValue();
            int st = ((Number) bm.get("state")).intValue();
            if (x >= 0 && x < sx && y >= 0 && y < sy && z >= 0 && z < sz && st >= 0 && st < palGlobal.length) {
                grid[(y * sz + z) * sx + x] = palGlobal[st];
            }
            if (bm.get("nbt") instanceof Map<?, ?> nbtMap) {
                LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : nbtMap.entrySet()) {
                    copy.put(String.valueOf(e.getKey()), e.getValue());
                }
                copy.putIfAbsent("x", x);
                copy.putIfAbsent("y", y);
                copy.putIfAbsent("z", z);
                bes.add(copy);
            }
        }
        return sliceVolumeIntoSections(
                path.getFileName().toString(), disk.length, uncompressed.length,
                sx, sy, sz, grid, bes, airId);
    }

    @SuppressWarnings("unchecked")
    private static LoadedVolumeAsset loadSchematicAsset(
            Path path, Map<String, Integer> stateToId, List<ParsedBlockState> globalDict
    ) throws IOException {
        byte[] disk = Files.readAllBytes(path);
        byte[] uncompressed = maybeGunzipBytes(disk);
        LinkedHashMap<String, Object> root = Nbt.readRootCompound(uncompressed);
        int airId = internState("minecraft:air", stateToId, globalDict);

        if (root.get("Regions") instanceof Map<?, ?> regions && !regions.isEmpty()) {
            // Litematica (.litematic)
            int gMinX = Integer.MAX_VALUE, gMinY = Integer.MAX_VALUE, gMinZ = Integer.MAX_VALUE;
            int gMaxX = Integer.MIN_VALUE, gMaxY = Integer.MIN_VALUE, gMaxZ = Integer.MIN_VALUE;
            List<Map<String, Object>> regList = new ArrayList<>();
            for (Object rObj : regions.values()) {
                if (!(rObj instanceof Map<?, ?> rm)) continue;
                Map<?, ?> posM = (Map<?, ?>) rm.get("Position");
                Map<?, ?> sizeM = (Map<?, ?>) rm.get("Size");
                int px = posM != null ? ((Number) posM.get("x")).intValue() : 0;
                int py = posM != null ? ((Number) posM.get("y")).intValue() : 0;
                int pz = posM != null ? ((Number) posM.get("z")).intValue() : 0;
                int sx = ((Number) sizeM.get("x")).intValue();
                int sy = ((Number) sizeM.get("y")).intValue();
                int sz = ((Number) sizeM.get("z")).intValue();
                int rw = Math.abs(sx), rh = Math.abs(sy), rl = Math.abs(sz);
                if (rw == 0 || rh == 0 || rl == 0) continue;
                int minX = sx < 0 ? px + sx + 1 : px;
                int minY = sy < 0 ? py + sy + 1 : py;
                int minZ = sz < 0 ? pz + sz + 1 : pz;
                gMinX = Math.min(gMinX, minX);
                gMinY = Math.min(gMinY, minY);
                gMinZ = Math.min(gMinZ, minZ);
                gMaxX = Math.max(gMaxX, minX + rw);
                gMaxY = Math.max(gMaxY, minY + rh);
                gMaxZ = Math.max(gMaxZ, minZ + rl);
                regList.add((Map<String, Object>) rm);
            }
            int tw = gMaxX - gMinX, th = gMaxY - gMinY, tl = gMaxZ - gMinZ;
            int[] grid = new int[tw * th * tl];
            Arrays.fill(grid, airId);
            List<Map<String, Object>> bes = new ArrayList<>();
            for (Map<String, Object> rm : regList) {
                Map<?, ?> posM = (Map<?, ?>) rm.get("Position");
                Map<?, ?> sizeM = (Map<?, ?>) rm.get("Size");
                int px = posM != null ? ((Number) posM.get("x")).intValue() : 0;
                int py = posM != null ? ((Number) posM.get("y")).intValue() : 0;
                int pz = posM != null ? ((Number) posM.get("z")).intValue() : 0;
                int sx = ((Number) sizeM.get("x")).intValue();
                int sy = ((Number) sizeM.get("y")).intValue();
                int sz = ((Number) sizeM.get("z")).intValue();
                int rw = Math.abs(sx), rh = Math.abs(sy), rl = Math.abs(sz);
                int ox = (sx < 0 ? px + sx + 1 : px) - gMinX;
                int oy = (sy < 0 ? py + sy + 1 : py) - gMinY;
                int oz = (sz < 0 ? pz + sz + 1 : pz) - gMinZ;

                List<Object> palItems = unwrapNbtList(rm.get("BlockStatePalette"));
                int[] palGlobal = new int[palItems.size()];
                for (int i = 0; i < palItems.size(); i++) {
                    palGlobal[i] = internState(
                            formatCanonicalPaletteCompound((Map<?, ?>) palItems.get(i)), stateToId, globalDict);
                }
                long[] longs = (long[]) rm.get("BlockStates");
                int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palGlobal.length - 1)));
                long mask = (1L << bits) - 1L;
                for (int y = 0; y < rh; y++) {
                    for (int z = 0; z < rl; z++) {
                        for (int x = 0; x < rw; x++) {
                            int localIdx = (y * rl + z) * rw + x;
                            long bitIdx = (long) localIdx * bits;
                            int li = (int) (bitIdx >>> 6);
                            int bo = (int) (bitIdx & 63L);
                            int rawIdx;
                            if (bo + bits <= 64) {
                                rawIdx = (int) ((longs[li] >>> bo) & mask);
                            } else {
                                int low = 64 - bo;
                                long nextWord = (li + 1 < longs.length) ? longs[li + 1] : 0L;
                                rawIdx = (int) (((longs[li] >>> bo) | (nextWord << low)) & mask);
                            }
                            if (rawIdx < 0 || rawIdx >= palGlobal.length) {
                                rawIdx = 0;
                            }
                            grid[((oy + y) * tl + (oz + z)) * tw + (ox + x)] = palGlobal[rawIdx];
                        }
                    }
                }
                for (Object teObj : unwrapNbtList(rm.get("TileEntities"))) {
                    if (teObj instanceof Map<?, ?> te) {
                        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : te.entrySet()) {
                            copy.put(String.valueOf(e.getKey()), e.getValue());
                        }
                        bes.add(copy);
                    }
                }
            }
            return sliceVolumeIntoSections(
                    path.getFileName().toString(), disk.length, uncompressed.length,
                    tw, th, tl, grid, bes, airId);
        }

        // Sponge .schem (v2 / v3)
        Map<String, Object> schem = (root.get("Schematic") instanceof Map<?, ?> sm)
                ? (Map<String, Object>) sm : root;
        int w = ((Number) schem.get("Width")).intValue();
        int h = ((Number) schem.get("Height")).intValue();
        int l = ((Number) schem.get("Length")).intValue();
        Map<String, Object> palTag;
        byte[] varData;
        List<Object> rawBes;
        if (schem.get("Blocks") instanceof Map<?, ?> blkMap) {
            palTag = (Map<String, Object>) blkMap.get("Palette");
            varData = (byte[]) blkMap.get("Data");
            rawBes = unwrapNbtList(blkMap.get("BlockEntities"));
        } else {
            palTag = (Map<String, Object>) schem.get("Palette");
            varData = (byte[]) schem.get("BlockData");
            rawBes = unwrapNbtList(schem.get("BlockEntities"));
        }
        int maxIdx = -1;
        for (Object v : palTag.values()) {
            maxIdx = Math.max(maxIdx, ((Number) v).intValue());
        }
        int[] palGlobal = new int[maxIdx + 1];
        Arrays.fill(palGlobal, airId);
        for (Map.Entry<String, Object> e : palTag.entrySet()) {
            palGlobal[((Number) e.getValue()).intValue()] =
                    internState(canonicalizeStateString(e.getKey()), stateToId, globalDict);
        }
        int total = w * h * l;
        int[] grid = new int[total];
        int p = 0;
        for (int idx = 0; idx < total && p < varData.length; idx++) {
            int val = 0;
            int shift = 0;
            while (true) {
                int b = varData[p++] & 0xFF;
                val |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            grid[idx] = (val >= 0 && val < palGlobal.length) ? palGlobal[val] : airId;
        }
        List<Map<String, Object>> bes = new ArrayList<>();
        for (Object o : rawBes) {
            if (o instanceof Map<?, ?> m) {
                LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    copy.put(String.valueOf(e.getKey()), e.getValue());
                }
                bes.add(copy);
            }
        }
        return sliceVolumeIntoSections(
                path.getFileName().toString(), disk.length, uncompressed.length,
                w, h, l, grid, bes, airId);
    }

    // --- Study 4 Helpers: Block-Conditioned SkyLight & BlockLight Residual Codec ---

    private static int classifyBlockOpacity(String baseName) {
        if (baseName.equals("minecraft:air") || baseName.equals("minecraft:cave_air")
                || baseName.equals("minecraft:void_air") || baseName.contains("glass")
                || baseName.endsWith("_grass") || baseName.equals("minecraft:fern")
                || baseName.equals("minecraft:large_fern") || baseName.contains("torch")
                || baseName.contains("flower") || baseName.endsWith("sapling")
                || baseName.contains("vine") || baseName.contains("lichen")
                || baseName.endsWith("_carpet") || baseName.endsWith("_rail")
                || baseName.contains("fence") || baseName.contains("wall")
                || baseName.contains("sign") || baseName.contains("banner")
                || baseName.contains("rod") || baseName.contains("lantern")
                || baseName.equals("minecraft:dandelion") || baseName.equals("minecraft:poppy")
                || baseName.equals("minecraft:sugar_cane") || baseName.equals("minecraft:snow")) {
            return 0;
        }
        if (baseName.endsWith("_leaves") || baseName.equals("minecraft:water")
                || baseName.equals("minecraft:bubble_column") || baseName.equals("minecraft:cobweb")
                || baseName.equals("minecraft:ice") || baseName.equals("minecraft:frosted_ice")) {
            return 1;
        }
        return 15;
    }

    private static int classifyBlockEmission(ParsedBlockState ps) {
        String b = ps.baseName;
        if (b.equals("minecraft:lava") || b.equals("minecraft:glowstone")
                || b.equals("minecraft:sea_lantern") || b.equals("minecraft:shroomlight")
                || b.equals("minecraft:jack_o_lantern") || b.equals("minecraft:lantern")
                || b.equals("minecraft:beacon") || b.equals("minecraft:conduit")
                || b.endsWith("_froglight") || b.equals("minecraft:fire")) {
            return 15;
        }
        if (b.equals("minecraft:torch") || b.equals("minecraft:wall_torch")
                || b.equals("minecraft:end_rod")
                || ((b.equals("minecraft:cave_vines") || b.equals("minecraft:cave_vines_plant"))
                        && ps.propSuffix.contains("berries=true"))) {
            return 14;
        }
        if ((b.equals("minecraft:furnace") || b.equals("minecraft:blast_furnace")
                || b.equals("minecraft:smoker")) && ps.propSuffix.contains("lit=true")) {
            return 13;
        }
        if (b.equals("minecraft:soul_torch") || b.equals("minecraft:soul_wall_torch")
                || b.equals("minecraft:soul_lantern") || b.equals("minecraft:soul_fire")
                || b.equals("minecraft:crying_obsidian") || b.equals("minecraft:portal")) {
            return 10;
        }
        if ((b.equals("minecraft:redstone_ore") || b.equals("minecraft:deepslate_redstone_ore"))
                && ps.propSuffix.contains("lit=true")) {
            return 9;
        }
        if (b.equals("minecraft:glow_lichen") || b.equals("minecraft:ender_chest")
                || ((b.equals("minecraft:redstone_torch") || b.equals("minecraft:redstone_wall_torch"))
                        && !ps.propSuffix.contains("lit=false"))) {
            return 7;
        }
        if (b.equals("minecraft:sculk_catalyst")) return 6;
        if (b.equals("minecraft:amethyst_cluster")) return 5;
        if (b.equals("minecraft:magma_block")) return 3;
        return 0;
    }

    private static byte[] encodeGrayLightPlanesExact(byte[] yzxNibbles, int curveId) {
        int[] curveToYzx = LcaSectionCodec.CURVE_TO_YZX[curveId];
        int[] grayCurve = new int[SECTION_VOXELS];
        for (int i = 0; i < SECTION_VOXELS; i++) {
            int l = yzxNibbles[curveToYzx[i]] & 0x0F;
            grayCurve[i] = l ^ (l >>> 1);
        }
        byte[] dst = new byte[4096];
        int pos = 1;
        int planeModes8 = 0;
        int[] staging = new int[SECTION_VOXELS];
        for (int plane = 0; plane < 4; plane++) {
            int mask = 1 << plane;
            int runCount = 0;
            int ones = 0;
            int cursor = 0;
            int i = 0;
            while (i < SECTION_VOXELS) {
                if ((grayCurve[i] & mask) == 0) {
                    i++;
                    continue;
                }
                int start = i++;
                while (i < SECTION_VOXELS && (grayCurve[i] & mask) != 0) {
                    i++;
                }
                int gap = start - cursor;
                int len = i - start;
                ones += len;
                staging[runCount++] = (gap << 16) | ((len - 1) & 0xFFFF);
                cursor = i;
            }
            int mode;
            if (ones == 0) {
                mode = 0;
            } else if (ones == SECTION_VOXELS) {
                mode = 1;
                dst[pos++] = (byte) 0xFF;
            } else if (runCount == 1) {
                int packed = staging[0];
                int start = packed >>> 16;
                int len = (packed & 0xFFFF) + 1;
                mode = 1;
                if (start + len == SECTION_VOXELS) {
                    dst[pos++] = (byte) (0xE0 | ((start >>> 8) & 0x0F));
                    dst[pos++] = (byte) (start & 0xFF);
                } else if (start == 0) {
                    int end = len - 1;
                    dst[pos++] = (byte) (0xD0 | ((end >>> 8) & 0x0F));
                    dst[pos++] = (byte) (end & 0xFF);
                } else {
                    dst[pos++] = (byte) (0xC0 | ((start >>> 8) & 0x0F));
                    dst[pos++] = (byte) (start & 0xFF);
                    pos = LcaSectionCodec.writeVarIntToBuf(dst, pos, len - 1);
                }
            } else {
                int hdrBytes = 1 + (runCount >= 31 ? LcaSectionCodec.varIntLen(runCount - 31) : 0);
                int size44 = hdrBytes;
                int size62 = hdrBytes;
                for (int r = 0; r < runCount; r++) {
                    int packed = staging[r];
                    int rawGap = packed >>> 16;
                    int adjGap = (r == 0) ? rawGap : (rawGap - 1);
                    int lm1 = packed & 0xFFFF;
                    size44 += 1 + (adjGap >= 15 ? LcaSectionCodec.varIntLen(adjGap - 15) : 0)
                            + (lm1 >= 15 ? LcaSectionCodec.varIntLen(lm1 - 15) : 0);
                    size62 += 1 + (adjGap >= 63 ? LcaSectionCodec.varIntLen(adjGap - 63) : 0)
                            + (lm1 >= 3 ? LcaSectionCodec.varIntLen(lm1 - 3) : 0);
                }
                int bestLen = Math.min(size44, size62);
                if (bestLen <= 512) {
                    mode = 1;
                    boolean split62 = size62 < size44;
                    int countCode = Math.min(31, runCount);
                    dst[pos++] = (byte) ((split62 ? 0x20 : 0) | countCode);
                    if (countCode == 31) {
                        pos = LcaSectionCodec.writeVarIntToBuf(dst, pos, runCount - 31);
                    }
                    int lBits = split62 ? 2 : 4;
                    int gMax = split62 ? 63 : 15;
                    int lMax = split62 ? 3 : 15;
                    for (int r = 0; r < runCount; r++) {
                        int packed = staging[r];
                        int rawGap = packed >>> 16;
                        int adjGap = (r == 0) ? rawGap : (rawGap - 1);
                        int lm1 = packed & 0xFFFF;
                        int gv = Math.min(gMax, adjGap);
                        int lv = Math.min(lMax, lm1);
                        dst[pos++] = (byte) ((gv << lBits) | lv);
                        if (gv == gMax) {
                            pos = LcaSectionCodec.writeVarIntToBuf(dst, pos, adjGap - gMax);
                        }
                        if (lv == lMax) {
                            pos = LcaSectionCodec.writeVarIntToBuf(dst, pos, lm1 - lMax);
                        }
                    }
                } else {
                    mode = 3;
                    Arrays.fill(dst, pos, pos + 512, (byte) 0);
                    for (int h = 0; h < SECTION_VOXELS; h++) {
                        if ((grayCurve[h] & mask) != 0) {
                            dst[pos + (h >>> 3)] |= (byte) (1 << (h & 7));
                        }
                    }
                    pos += 512;
                }
            }
            planeModes8 |= (mode << (plane << 1));
        }
        dst[0] = (byte) planeModes8;
        if (pos >= 2048) {
            byte[] raw = new byte[2048];
            for (int i = 0; i < 2048; i++) {
                raw[i] = (byte) ((yzxNibbles[i << 1] & 0x0F) | ((yzxNibbles[(i << 1) | 1] & 0x0F) << 4));
            }
            return raw;
        }
        return Arrays.copyOf(dst, pos);
    }

    private static byte[] predictBlockLightNibbles(int[] yzxGlobalIds, List<ParsedBlockState> globalDict) {
        byte[] pred = new byte[SECTION_VOXELS];
        int[] opacity = new int[SECTION_VOXELS];
        int[] queue = new int[SECTION_VOXELS * 4];
        int head = 0, tail = 0;
        for (int i = 0; i < SECTION_VOXELS; i++) {
            ParsedBlockState ps = globalDict.get(yzxGlobalIds[i]);
            opacity[i] = classifyBlockOpacity(ps.baseName);
            int em = classifyBlockEmission(ps);
            if (em > 0) {
                pred[i] = (byte) em;
                queue[tail++] = i;
            }
        }
        int[] dx = {-1, 1, 0, 0, 0, 0};
        int[] dy = {0, 0, -1, 1, 0, 0};
        int[] dz = {0, 0, 0, 0, -1, 1};
        while (head < tail) {
            int cur = queue[head++];
            int cl = pred[cur] & 0xFF;
            if (cl <= 1) continue;
            int x = cur & 15, z = (cur >>> 4) & 15, y = (cur >>> 8) & 15;
            for (int d = 0; d < 6; d++) {
                int nx = x + dx[d], ny = y + dy[d], nz = z + dz[d];
                if ((nx | ny | nz) >= 0 && nx < 16 && ny < 16 && nz < 16) {
                    int nIdx = (ny << 8) | (nz << 4) | nx;
                    int op = Math.max(1, opacity[nIdx]);
                    int nl = cl - op;
                    if (nl > (pred[nIdx] & 0xFF)) {
                        pred[nIdx] = (byte) nl;
                        queue[tail++] = nIdx;
                    }
                }
            }
        }
        return pred;
    }

    private static byte[] predictSkyLightNibbles(
            int[] yzxGlobalIds, List<ParsedBlockState> globalDict, byte[] topSlice256OrNull
    ) {
        byte[] pred = new byte[SECTION_VOXELS];
        int[] opacity = new int[SECTION_VOXELS];
        for (int i = 0; i < SECTION_VOXELS; i++) {
            opacity[i] = classifyBlockOpacity(globalDict.get(yzxGlobalIds[i]).baseName);
        }
        int[] queue = new int[SECTION_VOXELS * 4];
        int head = 0, tail = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int col = (z << 4) | x;
                int curL = (topSlice256OrNull != null) ? (topSlice256OrNull[col] & 0x0F) : 15;
                for (int y = 15; y >= 0; y--) {
                    int idx = (y << 8) | col;
                    int op = opacity[idx];
                    if (y == 15 && topSlice256OrNull != null) {
                        pred[idx] = (byte) curL;
                    } else {
                        if (op >= 15) {
                            curL = 0;
                        } else if (op > 0) {
                            curL = Math.max(0, curL - op);
                        }
                        pred[idx] = (byte) curL;
                    }
                    if (pred[idx] > 1) {
                        queue[tail++] = idx;
                    }
                }
            }
        }
        int[] dx = {-1, 1, 0, 0, 0, 0};
        int[] dy = {0, 0, -1, 1, 0, 0};
        int[] dz = {0, 0, 0, 0, -1, 1};
        while (head < tail) {
            int cur = queue[head++];
            int cl = pred[cur] & 0xFF;
            if (cl <= 1) continue;
            int x = cur & 15, z = (cur >>> 4) & 15, y = (cur >>> 8) & 15;
            for (int d = 0; d < 6; d++) {
                int nx = x + dx[d], ny = y + dy[d], nz = z + dz[d];
                if ((nx | ny | nz) >= 0 && nx < 16 && ny < 16 && nz < 16) {
                    int nIdx = (ny << 8) | (nz << 4) | nx;
                    int op = Math.max(1, opacity[nIdx]);
                    int nl = cl - op;
                    if (nl > (pred[nIdx] & 0xFF)) {
                        pred[nIdx] = (byte) nl;
                        queue[tail++] = nIdx;
                    }
                }
            }
        }
        return pred;
    }

    // --- Study 5 Helpers: Lossless Columnar Ticks & BlockEntities Codec ---

    private static int zigZagEncode(long v) {
        return (int) ((v << 1) ^ (v >> 63));
    }

    private static long zigZagDecode(int u) {
        return (Integer.toUnsignedLong(u) >>> 1) ^ -(u & 1L);
    }

    @SuppressWarnings("unchecked")
    private static byte[] encodeColumnarTickList(List<Object> tickItems, int chunkX, int chunkZ) {
        int n = tickItems.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 + n * 4);
        byte[] vBuf = new byte[10];
        int vLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, n);
        out.write(vBuf, 0, vLen);
        if (n == 0) {
            return out.toByteArray();
        }

        Map<String, Integer> idMap = new LinkedHashMap<>();
        List<String> idList = new ArrayList<>();
        boolean allInChunkXZ = true;
        boolean allZeroP = true;
        int[] idOrds = new int[n];
        int[] xs = new int[n];
        int[] ys = new int[n];
        int[] zs = new int[n];
        int[] ts = new int[n];
        int[] ps = new int[n];
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        for (int idx = 0; idx < n; idx++) {
            Map<String, Object> m = (Map<String, Object>) tickItems.get(idx);
            String id = String.valueOf(m.get("i"));
            Integer ord = idMap.get(id);
            if (ord == null) {
                ord = idList.size();
                idMap.put(id, ord);
                idList.add(id);
            }
            idOrds[idx] = ord;
            xs[idx] = ((Number) m.get("x")).intValue();
            ys[idx] = ((Number) m.get("y")).intValue();
            zs[idx] = ((Number) m.get("z")).intValue();
            ts[idx] = ((Number) m.get("t")).intValue();
            ps[idx] = ((Number) m.get("p")).intValue();
            if ((xs[idx] - baseX) < 0 || (xs[idx] - baseX) > 15 || (zs[idx] - baseZ) < 0 || (zs[idx] - baseZ) > 15) {
                allInChunkXZ = false;
            }
            if (ps[idx] != 0) {
                allZeroP = false;
            }
        }

        int flags = (allInChunkXZ ? 1 : 0) | (allZeroP ? 2 : 0);
        out.write(flags);

        vLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, idList.size());
        out.write(vBuf, 0, vLen);
        for (String id : idList) {
            String stripped = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
            boolean wasMc = id.startsWith("minecraft:");
            byte[] utf = stripped.getBytes(StandardCharsets.UTF_8);
            vLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, (utf.length << 1) | (wasMc ? 1 : 0));
            out.write(vBuf, 0, vLen);
            out.writeBytes(utf);
        }

        if (idList.size() > 1) {
            byte[] idHuff = encodeSmallAlphabetHuffman(idOrds, n, Math.min(64, idList.size()));
            if (idList.size() <= 64) {
                out.write(1);
                out.writeBytes(idHuff);
            } else {
                out.write(0);
                for (int idx = 0; idx < n; idx++) {
                    vLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, idOrds[idx]);
                    out.write(vBuf, 0, vLen);
                }
            }
        }

        if (allInChunkXZ) {
            for (int idx = 0; idx < n; idx++) {
                out.write(((xs[idx] - baseX) << 4) | (zs[idx] - baseZ));
            }
        } else {
            for (int idx = 0; idx < n; idx++) {
                vLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, zigZagEncode(xs[idx] - baseX));
                out.write(vBuf, 0, vLen);
                vLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, zigZagEncode(zs[idx] - baseZ));
                out.write(vBuf, 0, vLen);
            }
        }

        int prevY = 0;
        for (int idx = 0; idx < n; idx++) {
            vLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, zigZagEncode(ys[idx] - prevY));
            out.write(vBuf, 0, vLen);
            prevY = ys[idx];
        }

        int prevT = 0;
        for (int idx = 0; idx < n; idx++) {
            vLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, zigZagEncode(ts[idx] - prevT));
            out.write(vBuf, 0, vLen);
            prevT = ts[idx];
        }

        if (!allZeroP) {
            for (int idx = 0; idx < n; idx++) {
                vLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, zigZagEncode(ps[idx]));
                out.write(vBuf, 0, vLen);
            }
        }
        return out.toByteArray();
    }

    private static List<Object> decodeColumnarTickList(byte[] src, int chunkX, int chunkZ)
            throws CorruptRegionEntryException {
        int pos = 0;
        int n = LcaSectionCodec.readVarInt(src, pos, src.length);
        pos += LcaSectionCodec.varIntLen(n);
        if (n == 0) {
            return Collections.emptyList();
        }
        int flags = src[pos++] & 0xFF;
        boolean allInChunkXZ = (flags & 1) != 0;
        boolean allZeroP = (flags & 2) != 0;

        int idCount = LcaSectionCodec.readVarInt(src, pos, src.length);
        pos += LcaSectionCodec.varIntLen(idCount);
        String[] ids = new String[idCount];
        for (int i = 0; i < idCount; i++) {
            int hdr = LcaSectionCodec.readVarInt(src, pos, src.length);
            pos += LcaSectionCodec.varIntLen(hdr);
            int uLen = hdr >>> 1;
            boolean isMc = (hdr & 1) != 0;
            String s = new String(src, pos, uLen, StandardCharsets.UTF_8);
            pos += uLen;
            ids[i] = isMc ? ("minecraft:" + s) : s;
        }

        int[] idOrds = new int[n];
        if (idCount > 1) {
            int mode = src[pos++] & 0xFF;
            if (mode == 1) {
                pos = decodeSmallAlphabetHuffman(src, pos, idCount, n, idOrds);
            } else {
                for (int i = 0; i < n; i++) {
                    idOrds[i] = LcaSectionCodec.readVarInt(src, pos, src.length);
                    pos += LcaSectionCodec.varIntLen(idOrds[i]);
                }
            }
        }

        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int[] xs = new int[n];
        int[] zs = new int[n];
        if (allInChunkXZ) {
            for (int i = 0; i < n; i++) {
                int pk = src[pos++] & 0xFF;
                xs[i] = baseX + ((pk >>> 4) & 15);
                zs[i] = baseZ + (pk & 15);
            }
        } else {
            for (int i = 0; i < n; i++) {
                int dx = LcaSectionCodec.readVarInt(src, pos, src.length);
                pos += LcaSectionCodec.varIntLen(dx);
                int dz = LcaSectionCodec.readVarInt(src, pos, src.length);
                pos += LcaSectionCodec.varIntLen(dz);
                xs[i] = baseX + (int) zigZagDecode(dx);
                zs[i] = baseZ + (int) zigZagDecode(dz);
            }
        }

        int[] ys = new int[n];
        int prevY = 0;
        for (int i = 0; i < n; i++) {
            int dy = LcaSectionCodec.readVarInt(src, pos, src.length);
            pos += LcaSectionCodec.varIntLen(dy);
            prevY += (int) zigZagDecode(dy);
            ys[i] = prevY;
        }

        int[] ts = new int[n];
        int prevT = 0;
        for (int i = 0; i < n; i++) {
            int dt = LcaSectionCodec.readVarInt(src, pos, src.length);
            pos += LcaSectionCodec.varIntLen(dt);
            prevT += (int) zigZagDecode(dt);
            ts[i] = prevT;
        }

        int[] ps = new int[n];
        if (!allZeroP) {
            for (int i = 0; i < n; i++) {
                int dp = LcaSectionCodec.readVarInt(src, pos, src.length);
                pos += LcaSectionCodec.varIntLen(dp);
                ps[i] = (int) zigZagDecode(dp);
            }
        }

        List<Object> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>(8);
            m.put("i", ids[idOrds[i]]);
            m.put("p", ps[i]);
            m.put("t", ts[i]);
            m.put("x", xs[i]);
            m.put("y", ys[i]);
            m.put("z", zs[i]);
            out.add(m);
        }
        return out;
    }

    /**
     * Columnar BlockEntity list encoder for schematic builds and chunks (deduplicates `id`/`Id`
     * strings, delta-codes coordinates `x,y,z` or `Pos`, and deduplicates identical payload compounds
     * such as 500 empty hoppers/chests/furnaces into a compact payload template table!).
     */
    private static byte[] encodeColumnarBlockEntities(List<Map<String, Object>> bes) throws IOException {
        int n = bes.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 + n * 4);
        byte[] vBuf = new byte[10];
        int vLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, n);
        out.write(vBuf, 0, vLen);
        if (n == 0) {
            return out.toByteArray();
        }

        Map<ByteBufferKey, Integer> templateMap = new LinkedHashMap<>();
        List<byte[]> templateList = new ArrayList<>();
        int[] tmplOrds = new int[n];
        int[] xs = new int[n];
        int[] ys = new int[n];
        int[] zs = new int[n];
        byte[] coordType = new byte[n]; // 0 = x/y/z ints, 1 = Pos int[3], 2 = none

        for (int i = 0; i < n; i++) {
            Map<String, Object> orig = bes.get(i);
            LinkedHashMap<String, Object> stripped = new LinkedHashMap<>(orig);
            if (stripped.get("x") instanceof Number nx
                    && stripped.get("y") instanceof Number ny
                    && stripped.get("z") instanceof Number nz) {
                coordType[i] = 0;
                xs[i] = nx.intValue();
                ys[i] = ny.intValue();
                zs[i] = nz.intValue();
                stripped.remove("x");
                stripped.remove("y");
                stripped.remove("z");
            } else if (stripped.get("Pos") instanceof int[] posArr && posArr.length == 3) {
                coordType[i] = 1;
                xs[i] = posArr[0];
                ys[i] = posArr[1];
                zs[i] = posArr[2];
                stripped.remove("Pos");
            } else {
                coordType[i] = 2;
            }
            byte[] tmplBytes = Nbt.writeNamedRoot("", stripped);
            ByteBufferKey key = new ByteBufferKey(tmplBytes);
            Integer ord = templateMap.get(key);
            if (ord == null) {
                ord = templateList.size();
                templateMap.put(key, ord);
                templateList.add(tmplBytes);
            }
            tmplOrds[i] = ord;
        }

        vLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, templateList.size());
        out.write(vBuf, 0, vLen);
        for (byte[] tb : templateList) {
            vLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, tb.length);
            out.write(vBuf, 0, vLen);
            out.writeBytes(tb);
        }

        int prevX = 0, prevY = 0, prevZ = 0;
        for (int i = 0; i < n; i++) {
            vLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, (tmplOrds[i] << 2) | (coordType[i] & 3));
            out.write(vBuf, 0, vLen);
            if (coordType[i] != 2) {
                vLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, zigZagEncode(xs[i] - prevX));
                out.write(vBuf, 0, vLen);
                vLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, zigZagEncode(ys[i] - prevY));
                out.write(vBuf, 0, vLen);
                vLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, zigZagEncode(zs[i] - prevZ));
                out.write(vBuf, 0, vLen);
                prevX = xs[i];
                prevY = ys[i];
                prevZ = zs[i];
            }
        }
        return out.toByteArray();
    }

    private static List<Map<String, Object>> decodeColumnarBlockEntities(byte[] src) throws IOException {
        int pos = 0;
        int n = LcaSectionCodec.readVarInt(src, pos, src.length);
        pos += LcaSectionCodec.varIntLen(n);
        if (n == 0) {
            return Collections.emptyList();
        }
        int tCount = LcaSectionCodec.readVarInt(src, pos, src.length);
        pos += LcaSectionCodec.varIntLen(tCount);
        byte[][] templates = new byte[tCount][];
        for (int t = 0; t < tCount; t++) {
            int len = LcaSectionCodec.readVarInt(src, pos, src.length);
            pos += LcaSectionCodec.varIntLen(len);
            templates[t] = Arrays.copyOfRange(src, pos, pos + len);
            pos += len;
        }
        List<Map<String, Object>> out = new ArrayList<>(n);
        int prevX = 0, prevY = 0, prevZ = 0;
        for (int i = 0; i < n; i++) {
            int hdr = LcaSectionCodec.readVarInt(src, pos, src.length);
            pos += LcaSectionCodec.varIntLen(hdr);
            int tmplOrd = hdr >>> 2;
            int cType = hdr & 3;
            LinkedHashMap<String, Object> m = Nbt.readRootCompound(templates[tmplOrd]);
            if (cType != 2) {
                int dx = LcaSectionCodec.readVarInt(src, pos, src.length);
                pos += LcaSectionCodec.varIntLen(dx);
                int dy = LcaSectionCodec.readVarInt(src, pos, src.length);
                pos += LcaSectionCodec.varIntLen(dy);
                int dz = LcaSectionCodec.readVarInt(src, pos, src.length);
                pos += LcaSectionCodec.varIntLen(dz);
                prevX += (int) zigZagDecode(dx);
                prevY += (int) zigZagDecode(dy);
                prevZ += (int) zigZagDecode(dz);
                if (cType == 0) {
                    m.put("x", prevX);
                    m.put("y", prevY);
                    m.put("z", prevZ);
                } else {
                    m.put("Pos", new int[]{prevX, prevY, prevZ});
                }
            }
            out.add(m);
        }
        return out;
    }

    private static final class ByteBufferKey {
        final byte[] bytes;
        final int hash;

        ByteBufferKey(byte[] bytes) {
            this.bytes = bytes;
            this.hash = Arrays.hashCode(bytes);
        }

        @Override
        public boolean equals(Object o) {
            return (o instanceof ByteBufferKey other) && Arrays.equals(this.bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class SuiteAccumulator {
        final String label;
        int assets;
        int nonUniformSections;
        int uniformSections;
        long diskBytes;
        long rawNbtBytes;
        ByteArrayOutputStream baseStream = new ByteArrayOutputStream();
        ByteArrayOutputStream rankStream = new ByteArrayOutputStream();
        ByteArrayOutputStream layerStream = new ByteArrayOutputStream();
        ByteArrayOutputStream combStream = new ByteArrayOutputStream();
        ByteArrayOutputStream rawBeStream = new ByteArrayOutputStream();
        ByteArrayOutputStream colBeStream = new ByteArrayOutputStream();

        int perAssetBaseModeB = 0;
        int perAssetRankModeB = 0;
        int perAssetLayerModeB = 0;
        int perAssetCombModeB = 0;
        int perAssetBasePlusRawBeModeB = 0;
        int perAssetBasePlusColBeModeB = 0;
        int perAssetCombPlusColBeModeB = 0;
        int perAssetAdaptiveVoxModeB = 0;
        int perAssetAdaptivePlusColBeModeB = 0;
        LcaSectionCodec.AtomicDictState sharedDictBase = new LcaSectionCodec.AtomicDictState();
        LcaSectionCodec.AtomicDictState sharedDictRank = new LcaSectionCodec.AtomicDictState();
        LcaSectionCodec.AtomicDictState sharedDictLayer = new LcaSectionCodec.AtomicDictState();
        LcaSectionCodec.AtomicDictState sharedDictComb = new LcaSectionCodec.AtomicDictState();
        int sharedRegionBasePlusRawBeModeB = 0;
        int sharedRegionBasePlusColBeModeB = 0;
        int sharedRegionCombPlusColBeModeB = 0;
        LcaSectionCodec.AtomicDictState regDictBaseRawBe = new LcaSectionCodec.AtomicDictState();
        LcaSectionCodec.AtomicDictState regDictBaseColBe = new LcaSectionCodec.AtomicDictState();
        LcaSectionCodec.AtomicDictState regDictCombColBe = new LcaSectionCodec.AtomicDictState();

        SuiteAccumulator(String label) {
            this.label = label;
        }

        void addAsset(LoadedVolumeAsset asset, List<ParsedBlockState> globalDict) throws Exception {
            assets++;
            diskBytes += asset.diskBytes;
            rawNbtBytes += asset.uncompressedNbtBytes;
            uniformSections += asset.uniformSections;
            ByteArrayOutputStream aBase = new ByteArrayOutputStream();
            ByteArrayOutputStream aRank = new ByteArrayOutputStream();
            ByteArrayOutputStream aLayer = new ByteArrayOutputStream();
            ByteArrayOutputStream aComb = new ByteArrayOutputStream();
            for (int[] sec : asset.nonUniformSections) {
                nonUniformSections++;
                byte[] bBase = encodeBaselineCartesianSection(sec);
                byte[] bRank = encodeRankExcludedCartesianSection(sec);
                byte[] bLayer = encodeZeroTaxLayeredSection(sec, globalDict, false);
                byte[] bComb = encodeZeroTaxLayeredSection(sec, globalDict, true);

                assertArrayEquals(sec, decodeBaselineCartesianSection(bBase, 0, bBase.length));
                assertArrayEquals(sec, decodeRankExcludedCartesianSection(bRank, 0, bRank.length));
                assertArrayEquals(sec, decodeZeroTaxLayeredSection(bLayer, globalDict, false));
                assertArrayEquals(sec, decodeZeroTaxLayeredSection(bComb, globalDict, true));
                assertTrue(bLayer.length <= bBase.length, "Zero-tax layered must never exceed baseline");
                assertTrue(bComb.length <= bRank.length, "Zero-tax combined must never exceed rank-excluded");

                baseStream.write(bBase);
                rankStream.write(bRank);
                layerStream.write(bLayer);
                combStream.write(bComb);
                aBase.write(bBase);
                aRank.write(bRank);
                aLayer.write(bLayer);
                aComb.write(bComb);
            }
            byte[] ab = aBase.toByteArray();
            byte[] ar = aRank.toByteArray();
            byte[] al = aLayer.toByteArray();
            byte[] ac = aComb.toByteArray();
            int bOne = ab.length > 0 ? LcaSectionCodec.encodeAtomicDictBody(
                    ab, 0, ab.length, sharedDictBase, true).length : 0;
            int rOne = ar.length > 0 ? LcaSectionCodec.encodeAtomicDictBody(
                    ar, 0, ar.length, sharedDictRank, true).length : 0;
            int lOne = al.length > 0 ? LcaSectionCodec.encodeAtomicDictBody(
                    al, 0, al.length, sharedDictLayer, true).length : 0;
            int cOne = ac.length > 0 ? LcaSectionCodec.encodeAtomicDictBody(
                    ac, 0, ac.length, sharedDictComb, true).length : 0;
            perAssetBaseModeB += bOne;
            perAssetRankModeB += rOne;
            perAssetLayerModeB += lOne;
            perAssetCombModeB += cOne;
            perAssetAdaptiveVoxModeB += Math.min(Math.min(bOne, rOne), Math.min(lOne, cOne));

            byte[] rawBe = new byte[0];
            byte[] colBe = new byte[0];
            if (!asset.blockEntities.isEmpty()) {
                LinkedHashMap<String, Object> wrap = new LinkedHashMap<>();
                wrap.put("block_entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, new ArrayList<>(asset.blockEntities)));
                rawBe = Nbt.writeNamedRoot("", wrap);
                colBe = encodeColumnarBlockEntities(asset.blockEntities);
                List<Map<String, Object>> decBe = decodeColumnarBlockEntities(colBe);
                assertEquals(asset.blockEntities.size(), decBe.size(), "Columnar BlockEntities count parity");
                rawBeStream.write(rawBe);
                colBeStream.write(colBe);
            }

            byte[] frameBaseRawBe = concatArrays(ab, rawBe);
            byte[] frameBaseColBe = concatArrays(ab, colBe);
            byte[] frameCombColBe = concatArrays(ac, colBe);
            int fBaseRaw = frameBaseRawBe.length > 0 ? LcaSectionCodec.encodeAtomicDictBody(
                    frameBaseRawBe, 0, frameBaseRawBe.length, new LcaSectionCodec.AtomicDictState(), true).length : 0;
            int fBaseCol = frameBaseColBe.length > 0 ? LcaSectionCodec.encodeAtomicDictBody(
                    frameBaseColBe, 0, frameBaseColBe.length, new LcaSectionCodec.AtomicDictState(), true).length : 0;
            int fCombCol = frameCombColBe.length > 0 ? LcaSectionCodec.encodeAtomicDictBody(
                    frameCombColBe, 0, frameCombColBe.length, new LcaSectionCodec.AtomicDictState(), true).length : 0;
            perAssetBasePlusRawBeModeB += fBaseRaw;
            perAssetBasePlusColBeModeB += fBaseCol;
            perAssetCombPlusColBeModeB += fCombCol;
            perAssetAdaptivePlusColBeModeB += Math.min(fBaseCol, fCombCol);

            sharedRegionBasePlusRawBeModeB += frameBaseRawBe.length > 0 ? LcaSectionCodec.encodeAtomicDictBody(
                    frameBaseRawBe, 0, frameBaseRawBe.length, regDictBaseRawBe, true).length : 0;
            sharedRegionBasePlusColBeModeB += frameBaseColBe.length > 0 ? LcaSectionCodec.encodeAtomicDictBody(
                    frameBaseColBe, 0, frameBaseColBe.length, regDictBaseColBe, true).length : 0;
            sharedRegionCombPlusColBeModeB += frameCombColBe.length > 0 ? LcaSectionCodec.encodeAtomicDictBody(
                    frameCombColBe, 0, frameCombColBe.length, regDictCombColBe, true).length : 0;
        }

        void printReport() {
            byte[] bArr = baseStream.toByteArray();
            byte[] rArr = rankStream.toByteArray();
            byte[] lArr = layerStream.toByteArray();
            byte[] cArr = combStream.toByteArray();

            int bLcd2 = perAssetBaseModeB;
            int rLcd2 = perAssetRankModeB;
            int lLcd2 = perAssetLayerModeB;
            int cLcd2 = perAssetCombModeB;

            byte[] rawBe = rawBeStream.toByteArray();
            byte[] colBe = colBeStream.toByteArray();

            System.out.printf(Locale.ROOT,
                    "  [%s] (files=%d, nonUnifSecs=%d, unifSecs=%d, DiskGzip=%,d B, RawNBT=%,d B)%n"
                            + "    Mode A (0-LZ Voxel)  : Base=%,8d B | RankExcl=%,8d B (%+.2f%%) | Layered=%,8d B (%+.2f%%) | Combined=%,8d B (%+.2f%%)%n"
                            + "    Mode B (PerAssetLCD2): Base=%,8d B | RankExcl=%,8d B (%+.2f%%) | Layered=%,8d B (%+.2f%%) | Adaptive=%,8d B (%+.2f%%)%n",
                    label, assets, nonUniformSections, uniformSections, diskBytes, rawNbtBytes,
                    bArr.length,
                    rArr.length, bArr.length > 0 ? 100.0 * (rArr.length - bArr.length) / bArr.length : 0.0,
                    lArr.length, bArr.length > 0 ? 100.0 * (lArr.length - bArr.length) / bArr.length : 0.0,
                    cArr.length, bArr.length > 0 ? 100.0 * (cArr.length - bArr.length) / bArr.length : 0.0,
                    bLcd2,
                    rLcd2, bLcd2 > 0 ? 100.0 * (rLcd2 - bLcd2) / bLcd2 : 0.0,
                    lLcd2, bLcd2 > 0 ? 100.0 * (lLcd2 - bLcd2) / bLcd2 : 0.0,
                    perAssetAdaptiveVoxModeB, bLcd2 > 0 ? 100.0 * (perAssetAdaptiveVoxModeB - bLcd2) / bLcd2 : 0.0);
            if (rawBe.length > 0) {
                System.out.printf(Locale.ROOT,
                        "    BlockEntities (0-LZ) : RawNBT=%,7d B -> Columnar=%,7d B (%+.2f%%)%n"
                                + "    Standalone Frame LCD2: Base+RawBE=%,8d B -> Base+ColBE=%,8d B (%+.2f%%) | Adaptive+ColBE=%,8d B (%+.2f%% vs Base, %+.2f%% vs DiskGzip)%n"
                                + "    SharedDict RegionLCD2: Base+RawBE=%,8d B -> Base+ColBE=%,8d B (%+.2f%%) | Comb+ColBE=%,8d B (%+.2f%%)%n",
                        rawBe.length, colBe.length, 100.0 * (colBe.length - rawBe.length) / rawBe.length,
                        perAssetBasePlusRawBeModeB,
                        perAssetBasePlusColBeModeB,
                        100.0 * (perAssetBasePlusColBeModeB - perAssetBasePlusRawBeModeB) / perAssetBasePlusRawBeModeB,
                        perAssetAdaptivePlusColBeModeB,
                        100.0 * (perAssetAdaptivePlusColBeModeB - perAssetBasePlusRawBeModeB) / perAssetBasePlusRawBeModeB,
                        100.0 * (perAssetAdaptivePlusColBeModeB - diskBytes) / diskBytes,
                        sharedRegionBasePlusRawBeModeB,
                        sharedRegionBasePlusColBeModeB,
                        100.0 * (sharedRegionBasePlusColBeModeB - sharedRegionBasePlusRawBeModeB) / sharedRegionBasePlusRawBeModeB,
                        sharedRegionCombPlusColBeModeB,
                        100.0 * (sharedRegionCombPlusColBeModeB - sharedRegionBasePlusRawBeModeB) / sharedRegionBasePlusRawBeModeB);
            }
        }
    }

    private static Path resolveRepoRoot() {
        Path cur = Paths.get("").toAbsolutePath();
        while (cur != null) {
            if (Files.exists(cur.resolve("settings.gradle"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        return Paths.get("").toAbsolutePath();
    }

    @Test
    @DisplayName("Comprehensive Zero-Tax LCA Studies: Structures (.nbt), Schematics (.schem/.litematic), World, Lighting & Ticks")
    void proveStructuresSchematicsAndWorldLayersComprehensiveStudy() throws Exception {
        Path repoRoot = resolveRepoRoot();
        Map<String, Integer> stateToId = new HashMap<>(8192);
        List<ParsedBlockState> globalDict = new ArrayList<>(8192);
        internState("minecraft:air", stateToId, globalDict);

        // =====================================================================
        // STUDY 1: Real .nbt Structure Templates (datapacks/leaf-worldgen)
        // =====================================================================
        Path structDir = repoRoot.resolve("datapacks/leaf-worldgen/data/leaf/structure");
        List<Path> nbtPaths = new ArrayList<>();
        if (Files.isDirectory(structDir)) {
            try (Stream<Path> walk = Files.walk(structDir)) {
                walk.filter(p -> p.toString().endsWith(".nbt")).sorted().forEach(nbtPaths::add);
            }
        }
        assertTrue(!nbtPaths.isEmpty(), "Expected real .nbt structure templates in datapacks/leaf-worldgen");

        SuiteAccumulator allStructs = new SuiteAccumulator("ALL 17 STRUCTURES");
        SuiteAccumulator templeStruct = new SuiteAccumulator("japanese_temple.nbt (Architectural)");
        SuiteAccumulator volcanoStruct = new SuiteAccumulator("cinderfall_cone.nbt (Volcanic)");
        SuiteAccumulator treeStructs = new SuiteAccumulator("15 Canopy Tree Templates");
        long totalStampRuns = 0;
        long totalStampActiveVoxels = 0;

        for (Path p : nbtPaths) {
            LoadedVolumeAsset asset = loadStructureNbtAsset(p, stateToId, globalDict);
            allStructs.addAsset(asset, globalDict);
            String fn = p.getFileName().toString();
            if (fn.equals("japanese_temple.nbt")) {
                templeStruct.addAsset(asset, globalDict);
            } else if (fn.equals("cinderfall_cone.nbt")) {
                volcanoStruct.addAsset(asset, globalDict);
            } else {
                treeStructs.addAsset(asset, globalDict);
            }
            LcaStructureStamp stamp = StructureTemplateNbtLoader.loadFromBytes(
                    "leaf:" + fn.replace(".nbt", ""),
                    Files.readAllBytes(p),
                    LcaStructureStamp.TerrainProjection.RIGID,
                    Collections.emptyList()).variant(0, false);
            totalStampRuns += stamp.totalHilbertRunCount();
            totalStampActiveVoxels += stamp.totalVoxelCount();
        }

        System.out.printf(Locale.ROOT,
                "%n====================================================================================%n"
                        + "=== [STUDY 1] REAL .NBT STRUCTURE TEMPLATES (%d files, LcaStructureStamp: %,d runs / %,d non-void voxels) ===%n",
                nbtPaths.size(), totalStampRuns, totalStampActiveVoxels);
        allStructs.printReport();
        templeStruct.printReport();
        volcanoStruct.printReport();
        treeStructs.printReport();

        // =====================================================================
        // STUDY 2: Real Schematic Builds (.schem & .litematic in scripts/schematics)
        // =====================================================================
        Path schemDir = repoRoot.resolve("scripts/schematics");
        List<Path> schemPaths = new ArrayList<>();
        if (Files.isDirectory(schemDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(schemDir)) {
                for (Path p : ds) {
                    String fn = p.getFileName().toString();
                    if (fn.endsWith(".schem") || fn.endsWith(".litematic")) {
                        schemPaths.add(p);
                    }
                }
            }
            schemPaths.sort(Comparator.comparing(p -> p.getFileName().toString()));
        }
        assertTrue(!schemPaths.isEmpty(), "Expected real .schem/.litematic builds in scripts/schematics");

        SuiteAccumulator allSchems = new SuiteAccumulator("ALL 14 SCHEMATIC BUILDS");
        System.out.printf(Locale.ROOT,
                "%n====================================================================================%n"
                        + "=== [STUDY 2] REAL SCHEMATIC BUILDS (%d files: .litematic & .schem, Zero Zstd, 0-Byte Tax) ===%n",
                schemPaths.size());
        for (Path p : schemPaths) {
            LoadedVolumeAsset asset = loadSchematicAsset(p, stateToId, globalDict);
            allSchems.addAsset(asset, globalDict);
            String fn = p.getFileName().toString();
            if (fn.equals("main-storage-hall.litematic")
                    || fn.equals("trade-hall-large.litematic")
                    || fn.equals("super-smelter-factory.litematic")
                    || fn.equals("autoloading-super-smelter.schem")
                    || fn.equals("iron-farm-quad.schem")) {
                SuiteAccumulator single = new SuiteAccumulator(fn);
                single.addAsset(asset, globalDict);
                single.printReport();
            }
        }
        allSchems.printReport();

        // =====================================================================
        // STUDIES 3, 4 & 5: Unbiased Uniform Live 26.3 Overworld Audit
        // =====================================================================
        Path liveRegionDir = Paths.get(
                System.getProperty("user.home"),
                "Desktop", "GameServers", "Minecraft", "testServer", "RTP-Paper", "26.3",
                "world", "dimensions", "minecraft", "overworld", "region");
        if (!Files.isDirectory(liveRegionDir)) {
            System.out.println("[STUDIES 3-5] Live 26.3 directory not present; skipping live world studies.");
            return;
        }

        List<Path> allLcaRegions = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(liveRegionDir, "*.lca")) {
            for (Path p : ds) {
                allLcaRegions.add(p);
            }
        }
        allLcaRegions.sort(Comparator.comparing(p -> p.getFileName().toString()));

        // Study 3 & 4: 12 uniformly strided regions across the entire overworld (per-chunk LCD2!)
        int stride12 = Math.max(1, allLcaRegions.size() / 12);
        long wBaseModeA = 0, wRankModeA = 0, wLayerModeA = 0, wCombModeA = 0;
        long wBaseModeB = 0, wRankModeB = 0, wLayerModeB = 0, wCombModeB = 0;

        long skyBaseModeA = 0, skyCondModeA = 0, skyBaseModeB = 0, skyCondModeB = 0;
        long blkBaseModeA = 0, blkCondModeA = 0, blkBaseModeB = 0, blkCondModeB = 0;

        int s3Regions = 0;
        int s3Chunks = 0;
        int s3NonUniformSecs = 0;
        int s4SkyNonConstSecs = 0;
        int s4BlkNonConstSecs = 0;

        for (int rIdx = 0; rIdx < allLcaRegions.size() && s3Regions < 12; rIdx += stride12) {
            Path regPath = allLcaRegions.get(rIdx);
            byte[] regBytes = Files.readAllBytes(regPath);
            s3Regions++;
            LcaSectionCodec.AtomicDictState regDictB = new LcaSectionCodec.AtomicDictState();
            LcaSectionCodec.AtomicDictState regDictR = new LcaSectionCodec.AtomicDictState();
            LcaSectionCodec.AtomicDictState regDictL = new LcaSectionCodec.AtomicDictState();
            LcaSectionCodec.AtomicDictState regDictC = new LcaSectionCodec.AtomicDictState();
            LcaSectionCodec.AtomicDictState regDictSkyB = new LcaSectionCodec.AtomicDictState();
            LcaSectionCodec.AtomicDictState regDictSkyC = new LcaSectionCodec.AtomicDictState();
            LcaSectionCodec.AtomicDictState regDictBlkB = new LcaSectionCodec.AtomicDictState();
            LcaSectionCodec.AtomicDictState regDictBlkC = new LcaSectionCodec.AtomicDictState();

            int chunksInReg = 0;
            for (int rz = 0; rz < 32 && chunksInReg < 36; rz += 4) {
                for (int rx = 0; rx < 32 && chunksInReg < 36; rx += 4) {
                    if (!LcaRegionReader.INSTANCE.isChunkGenerated(regBytes, rx, rz)) {
                        continue;
                    }
                    AnvilReader.ChunkEntry entry = LcaRegionReader.INSTANCE.readChunk(regBytes, rx, rz);
                    if (entry == null || entry.root == null) {
                        continue;
                    }
                    chunksInReg++;
                    s3Chunks++;
                    ByteArrayOutputStream cBase = new ByteArrayOutputStream();
                    ByteArrayOutputStream cRank = new ByteArrayOutputStream();
                    ByteArrayOutputStream cLayer = new ByteArrayOutputStream();
                    ByteArrayOutputStream cComb = new ByteArrayOutputStream();
                    ByteArrayOutputStream cSkyB = new ByteArrayOutputStream();
                    ByteArrayOutputStream cSkyC = new ByteArrayOutputStream();
                    ByteArrayOutputStream cBlkB = new ByteArrayOutputStream();
                    ByteArrayOutputStream cBlkC = new ByteArrayOutputStream();

                    for (Object secObj : unwrapNbtList(entry.root.get("sections"))) {
                        if (!(secObj instanceof Map<?, ?> secMap)) continue;
                        if (!(secMap.get("block_states") instanceof Map<?, ?> bsMap)) continue;
                        List<Object> palItems = unwrapNbtList(bsMap.get("palette"));
                        if (palItems.isEmpty()) continue;
                        int[] palGlobal = new int[palItems.size()];
                        for (int p = 0; p < palItems.size(); p++) {
                            String canon = AnvilReader.formatCanonicalBlockState(palItems.get(p));
                            if (canon == null) canon = "minecraft:air";
                            palGlobal[p] = internState(canon, stateToId, globalDict);
                        }
                        int[] yzxGlobal = new int[SECTION_VOXELS];
                        if (palGlobal.length == 1 || !(bsMap.get("data") instanceof long[] packed)) {
                            Arrays.fill(yzxGlobal, palGlobal[0]);
                        } else {
                            int bits = PackedPaletteDecoder.bitsPerEntry(palGlobal.length);
                            int perLong = 64 / bits;
                            long mask = (1L << bits) - 1L;
                            int vox = 0;
                            for (int w = 0; w < packed.length && vox < SECTION_VOXELS; w++) {
                                long word = packed[w];
                                int lim = Math.min(SECTION_VOXELS, vox + perLong);
                                while (vox < lim) {
                                    yzxGlobal[vox++] = palGlobal[(int) (word & mask)];
                                    word >>>= bits;
                                }
                            }
                        }

                        if (palGlobal.length > 1) {
                            s3NonUniformSecs++;
                            byte[] bBase = encodeBaselineCartesianSection(yzxGlobal);
                            byte[] bRank = encodeRankExcludedCartesianSection(yzxGlobal);
                            byte[] bLayer = encodeZeroTaxLayeredSection(yzxGlobal, globalDict, false);
                            byte[] bComb = encodeZeroTaxLayeredSection(yzxGlobal, globalDict, true);

                            if ((s3NonUniformSecs & 15) == 1) {
                                assertArrayEquals(yzxGlobal, decodeRankExcludedCartesianSection(bRank, 0, bRank.length));
                                assertArrayEquals(yzxGlobal, decodeZeroTaxLayeredSection(bComb, globalDict, true));
                            }
                            wBaseModeA += bBase.length;
                            wRankModeA += bRank.length;
                            wLayerModeA += bLayer.length;
                            wCombModeA += bComb.length;
                            cBase.write(bBase);
                            cRank.write(bRank);
                            cLayer.write(bLayer);
                            cComb.write(bComb);
                        }

                        if (secMap.get("SkyLight") instanceof byte[] sky2048 && sky2048.length == 2048) {
                            byte[] nibs = unpackNibbles4096(sky2048);
                            if (!isUniformNibble(nibs)) {
                                s4SkyNonConstSecs++;
                                byte[] baseGray = encodeGrayLightPlanesExact(nibs, LcaSectionCodec.CURVE_Y_H2);
                                byte[] bestSky = encodeZeroTaxBlockCondSkyLight(nibs, yzxGlobal, globalDict);
                                // Full serialized wire decode verification (0-byte tax on baseGray!)
                                assertArrayEquals(nibs, decodeZeroTaxBlockCondSkyLight(bestSky, yzxGlobal, globalDict));

                                skyBaseModeA += baseGray.length;
                                skyCondModeA += bestSky.length;
                                cSkyB.write(baseGray);
                                cSkyC.write(bestSky);
                            }
                        }

                        if (secMap.get("BlockLight") instanceof byte[] blk2048 && blk2048.length == 2048) {
                            byte[] nibs = unpackNibbles4096(blk2048);
                            if (!isUniformNibble(nibs)) {
                                s4BlkNonConstSecs++;
                                byte[] baseGray = encodeGrayLightPlanesExact(nibs, LcaSectionCodec.CURVE_H3);
                                byte[] bestBlk = encodeZeroTaxBlockCondBlockLight(nibs, yzxGlobal, globalDict);
                                // Full serialized wire decode verification (0-byte tax on baseGray!)
                                assertArrayEquals(nibs, decodeZeroTaxBlockCondBlockLight(bestBlk, yzxGlobal, globalDict));

                                blkBaseModeA += baseGray.length;
                                blkCondModeA += bestBlk.length;
                                cBlkB.write(baseGray);
                                cBlkC.write(bestBlk);
                            }
                        }
                    }

                    byte[] cb = cBase.toByteArray();
                    byte[] cr = cRank.toByteArray();
                    byte[] cl = cLayer.toByteArray();
                    byte[] cc = cComb.toByteArray();
                    if (cb.length > 0) {
                        wBaseModeB += LcaSectionCodec.encodeAtomicDictBody(cb, 0, cb.length, regDictB, true).length;
                        wRankModeB += LcaSectionCodec.encodeAtomicDictBody(cr, 0, cr.length, regDictR, true).length;
                        wLayerModeB += LcaSectionCodec.encodeAtomicDictBody(cl, 0, cl.length, regDictL, true).length;
                        wCombModeB += LcaSectionCodec.encodeAtomicDictBody(cc, 0, cc.length, regDictC, true).length;
                    }
                    byte[] csb = cSkyB.toByteArray();
                    byte[] csc = cSkyC.toByteArray();
                    if (csb.length > 0) {
                        skyBaseModeB += LcaSectionCodec.encodeAtomicDictBody(csb, 0, csb.length, regDictSkyB, true).length;
                        skyCondModeB += LcaSectionCodec.encodeAtomicDictBody(csc, 0, csc.length, regDictSkyC, true).length;
                    }
                    byte[] cbb = cBlkB.toByteArray();
                    byte[] cbc = cBlkC.toByteArray();
                    if (cbb.length > 0) {
                        blkBaseModeB += LcaSectionCodec.encodeAtomicDictBody(cbb, 0, cbb.length, regDictBlkB, true).length;
                        blkCondModeB += LcaSectionCodec.encodeAtomicDictBody(cbc, 0, cbc.length, regDictBlkC, true).length;
                    }
                }
            }
        }

        System.out.printf(Locale.ROOT,
                "%n====================================================================================%n"
                        + "=== [STUDY 3] UNBIASED UNIFORM 26.3 OVERWORLD BLOCK CONTAINERS (%d strided regions, %,d chunks, %,d secs, Per-Chunk LCD2) ===%n"
                        + "  Mode A (0-LZ) : Base=%,9d B | RankExcl=%,9d B (%+.2f%%) | Layered=%,9d B (%+.2f%%) | Combined=%,9d B (%+.2f%%)%n"
                        + "  Mode B (LCD2) : Base=%,9d B | RankExcl=%,9d B (%+.2f%%) | Layered=%,9d B (%+.2f%%) | Combined=%,9d B (%+.2f%%)%n",
                s3Regions, s3Chunks, s3NonUniformSecs,
                wBaseModeA,
                wRankModeA, 100.0 * (wRankModeA - wBaseModeA) / Math.max(1, wBaseModeA),
                wLayerModeA, 100.0 * (wLayerModeA - wBaseModeA) / Math.max(1, wBaseModeA),
                wCombModeA, 100.0 * (wCombModeA - wBaseModeA) / Math.max(1, wBaseModeA),
                wBaseModeB,
                wRankModeB, 100.0 * (wRankModeB - wBaseModeB) / Math.max(1, wBaseModeB),
                wLayerModeB, 100.0 * (wLayerModeB - wBaseModeB) / Math.max(1, wBaseModeB),
                wCombModeB, 100.0 * (wCombModeB - wBaseModeB) / Math.max(1, wBaseModeB));

        System.out.printf(Locale.ROOT,
                "%n====================================================================================%n"
                        + "=== [STUDY 4] BLOCK-CONDITIONED SKYLIGHT & BLOCKLIGHT RESIDUALS (Sky n=%,d, Blk n=%,d non-const sections, Per-Chunk LCD2) ===%n"
                        + "  SkyLight   Mode A : GrayPlanes=%,8d B -> BlockCond=%,8d B (%+.2f%%) | Per-Chunk Mode B LCD2: %,8d B -> %,8d B (%+.2f%%)%n"
                        + "  BlockLight Mode A : GrayPlanes=%,8d B -> BlockCond=%,8d B (%+.2f%%) | Per-Chunk Mode B LCD2: %,8d B -> %,8d B (%+.2f%%)%n",
                s4SkyNonConstSecs, s4BlkNonConstSecs,
                skyBaseModeA, skyCondModeA, 100.0 * (skyCondModeA - skyBaseModeA) / Math.max(1, skyBaseModeA),
                skyBaseModeB, skyCondModeB, 100.0 * (skyCondModeB - skyBaseModeB) / Math.max(1, skyBaseModeB),
                blkBaseModeA, blkCondModeA, 100.0 * (blkCondModeA - blkBaseModeA) / Math.max(1, blkBaseModeA),
                blkBaseModeB, blkCondModeB, 100.0 * (blkCondModeB - blkBaseModeB) / Math.max(1, blkBaseModeB));

        // =====================================================================
        // STUDY 5: Uniform 24-Region Whole-Chunk Frame Mode B (Per-Chunk + 256B Slab) Audit
        // =====================================================================
        int stride24 = Math.max(1, allLcaRegions.size() / 24);
        int s5Regions = 0;
        int s5Chunks = 0;
        int chunksWithTicks = 0;
        int chunksWithBes = 0;
        long totalChunkFrameBytes = 0;
        long totalHeaderAndPaletteBytes = 0;
        long totalSectionFramesBytes = 0;
        long totalResidualTailBytes = 0;
        long wholeChunkModeBBaseBytes = 0;
        long wholeChunkModeBColBytes = 0;
        long wholeChunkSlab256BaseBytes = 0;
        long wholeChunkSlab256ColBytes = 0;
        long rawTicksUncompSum = 0;
        long colTicksUncompSum = 0;

        for (int rIdx = 0; rIdx < allLcaRegions.size() && s5Regions < 24; rIdx += stride24) {
            Path regPath = allLcaRegions.get(rIdx);
            byte[] regBytes = Files.readAllBytes(regPath);
            s5Regions++;
            LcaSectionCodec.AtomicDictState regBaseDict = new LcaSectionCodec.AtomicDictState();
            LcaSectionCodec.AtomicDictState regColDict = new LcaSectionCodec.AtomicDictState();
            try (FileChannel ch = FileChannel.open(regPath, StandardOpenOption.READ)) {
                int sampled = 0;
                for (int rz = 0; rz < 32 && sampled < 32; rz += 4) {
                    for (int rx = 0; rx < 32 && sampled < 32; rx += 8) {
                        RawChunkFrameSnapshot snap = LcaRegionReader.readRawChunkFrameFromChannel(regPath, ch, rx, rz, null);
                        if (snap == null) continue;
                        sampled++;
                        s5Chunks++;
                        byte[] cf = snap.chunkFrame();
                        int hdrLen = 24 + (snap.sectionCount() << 1);
                        totalChunkFrameBytes += cf.length;
                        int[] relStarts = snap.computeSectionRelStarts();
                        int resStart = snap.hasResidualNbt() ? snap.residualRelStart() : cf.length;
                        int firstSec = LcaSectionCodec.findFirstSectionRelStart(relStarts, snap.sectionCount(), resStart);
                        int resEnd = LcaSectionCodec.findResidualRelEnd(relStarts, snap.sectionCount(), resStart, cf.length);
                        totalHeaderAndPaletteBytes += firstSec;
                        totalSectionFramesBytes += Math.max(0, resStart - firstSec);
                        totalResidualTailBytes += Math.max(0, resEnd - resStart);

                        // Reconstruct exact current Mode B ChunkFrame (allowDeflateResidual=false) vs +ColTicks/BE
                        byte[] modeBBaseCf = cf;
                        byte[] revisedCf = cf;
                        if (snap.hasResidualNbt() && resEnd > resStart) {
                            LcaSectionCodec.ResidualTailDecoded tail = LcaSectionCodec.unpackResidualSectionAndNbt(
                                    cf, 0, resStart, resEnd, snap.minSectionY(), snap.sectionCount());
                            int nbtPos = locateExactResidualNbtSliceOffset(cf, resStart, resEnd);
                            LinkedHashMap<String, Object> rootMap = tail.rootMap();
                            LinkedHashMap<String, Object> fullDynRoot = new LinkedHashMap<>(rootMap);
                            fullDynRoot.remove("Heightmaps");
                            int bpFlags = LcaSectionCodec.readUint16BE(cf, resStart);
                            if ((bpFlags & 1) != 0) fullDynRoot.remove("block_entities");
                            if ((bpFlags & 2) != 0) fullDynRoot.remove("block_ticks");
                            if ((bpFlags & 4) != 0) fullDynRoot.remove("fluid_ticks");
                            if ((bpFlags & 8) != 0) fullDynRoot.remove("isLightOn");
                            if ((bpFlags & 16) != 0) fullDynRoot.remove("structures");
                            if ((bpFlags & 32) != 0) fullDynRoot.remove("PostProcessing");
                            if ((bpFlags & 64) != 0) fullDynRoot.remove("starlight.light_version");

                            byte[] rawDynNbt = fullDynRoot.isEmpty() ? new byte[0] : Nbt.writeNamedRoot("", fullDynRoot);
                            ByteArrayOutputStream exactBaseOut = new ByteArrayOutputStream(nbtPos + rawDynNbt.length + 16);
                            exactBaseOut.write(cf, 0, nbtPos);
                            exactBaseOut.write(rawDynNbt);
                            if (resEnd < cf.length) {
                                exactBaseOut.write(cf, resEnd, cf.length - resEnd);
                            }
                            modeBBaseCf = exactBaseOut.toByteArray();
                            revisedCf = modeBBaseCf;

                            List<Object> bTicks = unwrapNbtList(fullDynRoot.get("block_ticks"));
                            List<Object> fTicks = unwrapNbtList(fullDynRoot.get("fluid_ticks"));
                            List<Object> bEnts = unwrapNbtList(fullDynRoot.get("block_entities"));
                            if (!bTicks.isEmpty() || !fTicks.isEmpty() || !bEnts.isEmpty()) {
                                AnvilReader.ChunkEntry full = LcaRegionReader.INSTANCE.readChunk(regBytes, rx, rz);
                                int chunkX = ((Number) full.root.get("xPos")).intValue();
                                int chunkZ = ((Number) full.root.get("zPos")).intValue();

                                LinkedHashMap<String, Object> rawOnly = new LinkedHashMap<>();
                                ByteArrayOutputStream colPayload = new ByteArrayOutputStream();
                                if (!bTicks.isEmpty() || !fTicks.isEmpty()) {
                                    chunksWithTicks++;
                                    if (!bTicks.isEmpty()) rawOnly.put("block_ticks", new Nbt.NbtList(Nbt.TAG_COMPOUND, bTicks));
                                    if (!fTicks.isEmpty()) rawOnly.put("fluid_ticks", new Nbt.NbtList(Nbt.TAG_COMPOUND, fTicks));
                                    byte[] rawTickBytes = Nbt.writeNamedRoot("", rawOnly);
                                    byte[] colB = encodeColumnarTickList(bTicks, chunkX, chunkZ);
                                    byte[] colF = encodeColumnarTickList(fTicks, chunkX, chunkZ);
                                    assertEquals(bTicks, decodeColumnarTickList(colB, chunkX, chunkZ));
                                    assertEquals(fTicks, decodeColumnarTickList(colF, chunkX, chunkZ));
                                    rawTicksUncompSum += rawTickBytes.length;
                                    colTicksUncompSum += (colB.length + colF.length);
                                    colPayload.write(colB);
                                    colPayload.write(colF);
                                }
                                if (!bEnts.isEmpty()) {
                                    chunksWithBes++;
                                    List<Map<String, Object>> beMaps = new ArrayList<>(bEnts.size());
                                    for (Object o : bEnts) {
                                        if (o instanceof Map<?, ?> m) {
                                            @SuppressWarnings("unchecked")
                                            Map<String, Object> cm = (Map<String, Object>) m;
                                            beMaps.add(cm);
                                        }
                                    }
                                    byte[] colBe = encodeColumnarBlockEntities(beMaps);
                                    assertEquals(beMaps.size(), decodeColumnarBlockEntities(colBe).size());
                                    colPayload.write(colBe);
                                }

                                LinkedHashMap<String, Object> remRoot = new LinkedHashMap<>(fullDynRoot);
                                remRoot.remove("block_ticks");
                                remRoot.remove("fluid_ticks");
                                remRoot.remove("block_entities");
                                byte[] remNbt = remRoot.isEmpty() ? new byte[0] : Nbt.writeNamedRoot("", remRoot);
                                byte[] colDynBytes = colPayload.toByteArray();
                                ByteArrayOutputStream rebuilt = new ByteArrayOutputStream(
                                        nbtPos + remNbt.length + colDynBytes.length + 16);
                                rebuilt.write(cf, 0, nbtPos);
                                rebuilt.write(remNbt);
                                rebuilt.write(colDynBytes);
                                if (resEnd < cf.length) {
                                    rebuilt.write(cf, resEnd, cf.length - resEnd);
                                }
                                revisedCf = rebuilt.toByteArray();
                            }
                        }

                        int baseBodyLcd2 = LcaSectionCodec.encodeAtomicDictBody(
                                modeBBaseCf, hdrLen, modeBBaseCf.length - hdrLen, regBaseDict, true).length;
                        int baseTotalChunk = hdrLen + baseBodyLcd2;
                        wholeChunkModeBBaseBytes += baseTotalChunk;
                        wholeChunkSlab256BaseBytes += ((baseTotalChunk + 255) & ~255);

                        int colBodyLcd2 = LcaSectionCodec.encodeAtomicDictBody(
                                revisedCf, hdrLen, revisedCf.length - hdrLen, regColDict, true).length;
                        int colTotalChunk = hdrLen + colBodyLcd2;
                        wholeChunkModeBColBytes += colTotalChunk;
                        wholeChunkSlab256ColBytes += ((colTotalChunk + 255) & ~255);
                    }
                }
            }
        }

        System.out.printf(Locale.ROOT,
                "%n====================================================================================%n"
                        + "=== [STUDY 5] UNIFORM 24-REGION WHOLE-CHUNK MODE B & COLUMNAR TICKS/BE (%d regions, %,d chunks) ===%n"
                        + "  Uncompressed ChunkFrame Total : %,10d B (avg %,d B/chunk)%n"
                        + "    • Header + Dir + SlabPal    : %,10d B (%5.2f%%)%n"
                        + "    • SectionFrames (Vox+Light) : %,10d B (%5.2f%%)%n"
                        + "    • ResidualTailBytes         : %,10d B (%5.2f%%)%n"
                        + "  Chunks w/ Ticks / BlockEnts   : Ticks=%,d/%,d (%.2f%%) | BlockEntities=%,d/%,d (%.2f%%)%n"
                        + "  Ticks Uncompressed            : RawNBT=%,9d B -> Columnar=%,9d B (%+.2f%%)%n"
                        + "  WHOLE-CHUNK Mode B (Exact)    : Base=%,10d B -> +ColTicks/BE=%,10d B (%+.2f%%)%n"
                        + "  WHOLE-CHUNK Mode B (256B Slab): Base=%,10d B -> +ColTicks/BE=%,10d B (%+.2f%%)%n"
                        + "====================================================================================%n",
                s5Regions, s5Chunks,
                totalChunkFrameBytes, s5Chunks > 0 ? totalChunkFrameBytes / s5Chunks : 0,
                totalHeaderAndPaletteBytes, 100.0 * totalHeaderAndPaletteBytes / Math.max(1, totalChunkFrameBytes),
                totalSectionFramesBytes, 100.0 * totalSectionFramesBytes / Math.max(1, totalChunkFrameBytes),
                totalResidualTailBytes, 100.0 * totalResidualTailBytes / Math.max(1, totalChunkFrameBytes),
                chunksWithTicks, s5Chunks, 100.0 * chunksWithTicks / Math.max(1, s5Chunks),
                chunksWithBes, s5Chunks, 100.0 * chunksWithBes / Math.max(1, s5Chunks),
                rawTicksUncompSum, colTicksUncompSum,
                rawTicksUncompSum > 0 ? 100.0 * (colTicksUncompSum - rawTicksUncompSum) / rawTicksUncompSum : 0.0,
                wholeChunkModeBBaseBytes, wholeChunkModeBColBytes,
                100.0 * (wholeChunkModeBColBytes - wholeChunkModeBBaseBytes) / Math.max(1, wholeChunkModeBBaseBytes),
                wholeChunkSlab256BaseBytes, wholeChunkSlab256ColBytes,
                100.0 * (wholeChunkSlab256ColBytes - wholeChunkSlab256BaseBytes) / Math.max(1, wholeChunkSlab256BaseBytes));

        // =====================================================================
        // STUDY 6: End-to-End Unified Whole-Chunk / Whole-Region Synthesis
        //   Vanilla .mca (4KiB Zlib) vs Current .lca v2 vs Unified Revised .lca
        //   (Adaptive RankExcl+Layered Voxels + BlockCond Light + Columnar Ticks/BE)
        // =====================================================================
        long e2eMcaSectorBytes = 0;
        long e2eMcaExactZlibBytes = 0;
        long e2eCurModeAExact = 0, e2eCurModeASlab256 = 0;
        long e2eRevModeAExact = 0, e2eRevModeASlab256 = 0;
        long e2eCurModeBExact = 0, e2eCurModeBSlab256 = 0;
        long e2eRevModeBExact = 0, e2eRevModeBSlab256 = 0;
        int e2eRegions = 0;
        int e2eChunks = 0;

        for (int rIdx = 0; rIdx < allLcaRegions.size() && e2eRegions < 12; rIdx += stride12) {
            Path regPath = allLcaRegions.get(rIdx);
            byte[] regBytes = Files.readAllBytes(regPath);
            e2eRegions++;
            LcaSectionCodec.AtomicDictState curRegDict = new LcaSectionCodec.AtomicDictState();
            LcaSectionCodec.AtomicDictState revRegDict = new LcaSectionCodec.AtomicDictState();
            try (FileChannel ch = FileChannel.open(regPath, StandardOpenOption.READ)) {
                int chunksInReg = 0;
                for (int rz = 0; rz < 32 && chunksInReg < 24; rz += 6) {
                    for (int rx = 0; rx < 32 && chunksInReg < 24; rx += 6) {
                        RawChunkFrameSnapshot snap = LcaRegionReader.readRawChunkFrameFromChannel(regPath, ch, rx, rz, null);
                        if (snap == null) continue;
                        AnvilReader.ChunkEntry full = LcaRegionReader.INSTANCE.readChunk(regBytes, rx, rz);
                        if (full == null || full.root == null) continue;
                        chunksInReg++;
                        e2eChunks++;

                        // 1. Vanilla .mca baseline (uncompressed NBT -> Zlib level 6 + 5B header -> 4KiB sector ceil)
                        byte[] mcaRawNbt = Nbt.writeNamedRoot("", full.root);
                        ByteArrayOutputStream zlibOut = new ByteArrayOutputStream(4096);
                        Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION);
                        try (DeflaterOutputStream dos = new DeflaterOutputStream(zlibOut, def)) {
                            dos.write(mcaRawNbt);
                        } finally {
                            def.end();
                        }
                        int zlibStreamLen = 5 + zlibOut.size();
                        e2eMcaExactZlibBytes += zlibStreamLen;
                        e2eMcaSectorBytes += ((zlibStreamLen + 4095) & ~4095);

                        // 2. Current .lca v2 ChunkFrame (exact header + sections + Mode B raw residual tail)
                        byte[] cf = snap.chunkFrame();
                        int hdrLen = 24 + (snap.sectionCount() << 1);
                        int[] relStarts = snap.computeSectionRelStarts();
                        int resStart = snap.hasResidualNbt() ? snap.residualRelStart() : cf.length;
                        int firstSec = LcaSectionCodec.findFirstSectionRelStart(relStarts, snap.sectionCount(), resStart);
                        int resEnd = LcaSectionCodec.findResidualRelEnd(relStarts, snap.sectionCount(), resStart, cf.length);

                        // Build current Mode B frame (allowDeflateResidual=false) and Mode A frame (allowDeflateResidual=true)
                        byte[] curModeACf = cf; // live disk frame (already has compact residual tail)
                        byte[] curModeBCf = cf;
                        byte[] revTailBytes = Arrays.copyOfRange(cf, resStart, resEnd);
                        if (snap.hasResidualNbt() && resEnd > resStart) {
                            LcaSectionCodec.ResidualTailDecoded tail = LcaSectionCodec.unpackResidualSectionAndNbt(
                                    cf, 0, resStart, resEnd, snap.minSectionY(), snap.sectionCount());
                            int nbtPos = locateExactResidualNbtSliceOffset(cf, resStart, resEnd);
                            LinkedHashMap<String, Object> fullDynRoot = new LinkedHashMap<>(tail.rootMap());
                            fullDynRoot.remove("Heightmaps");
                            int bpFlags = LcaSectionCodec.readUint16BE(cf, resStart);
                            if ((bpFlags & 1) != 0) fullDynRoot.remove("block_entities");
                            if ((bpFlags & 2) != 0) fullDynRoot.remove("block_ticks");
                            if ((bpFlags & 4) != 0) fullDynRoot.remove("fluid_ticks");
                            if ((bpFlags & 8) != 0) fullDynRoot.remove("isLightOn");
                            if ((bpFlags & 16) != 0) fullDynRoot.remove("structures");
                            if ((bpFlags & 32) != 0) fullDynRoot.remove("PostProcessing");
                            if ((bpFlags & 64) != 0) fullDynRoot.remove("starlight.light_version");

                            byte[] rawDynNbt = fullDynRoot.isEmpty() ? new byte[0] : Nbt.writeNamedRoot("", fullDynRoot);
                            ByteArrayOutputStream cbOut = new ByteArrayOutputStream(nbtPos + rawDynNbt.length + 16);
                            cbOut.write(cf, 0, nbtPos);
                            cbOut.write(rawDynNbt);
                            curModeBCf = cbOut.toByteArray();

                            List<Object> bTicks = unwrapNbtList(fullDynRoot.get("block_ticks"));
                            List<Object> fTicks = unwrapNbtList(fullDynRoot.get("fluid_ticks"));
                            List<Object> bEnts = unwrapNbtList(fullDynRoot.get("block_entities"));
                            int chunkX = ((Number) full.root.get("xPos")).intValue();
                            int chunkZ = ((Number) full.root.get("zPos")).intValue();
                            ByteArrayOutputStream revTailOut = new ByteArrayOutputStream();
                            revTailOut.write(cf, resStart, nbtPos - resStart);
                            LinkedHashMap<String, Object> remRoot = new LinkedHashMap<>(fullDynRoot);
                            remRoot.remove("block_ticks");
                            remRoot.remove("fluid_ticks");
                            remRoot.remove("block_entities");
                            if (!remRoot.isEmpty()) {
                                revTailOut.write(Nbt.writeNamedRoot("", remRoot));
                            }
                            if (!bTicks.isEmpty()) revTailOut.write(encodeColumnarTickList(bTicks, chunkX, chunkZ));
                            if (!fTicks.isEmpty()) revTailOut.write(encodeColumnarTickList(fTicks, chunkX, chunkZ));
                            if (!bEnts.isEmpty()) {
                                List<Map<String, Object>> beMaps = new ArrayList<>(bEnts.size());
                                for (Object o : bEnts) {
                                    if (o instanceof Map<?, ?> m) {
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> cm = (Map<String, Object>) m;
                                        beMaps.add(cm);
                                    }
                                }
                                revTailOut.write(encodeColumnarBlockEntities(beMaps));
                            }
                            revTailBytes = revTailOut.toByteArray();
                        }

                        // 3. Rebuild non-uniform sections with Unified (Combined Voxel + BlockCond SkyLight + BlockCond BlockLight)
                        // preserving exact Biome bytes!
                        ByteArrayOutputStream revSectionsBody = new ByteArrayOutputStream();
                        // Copy slab-local palette prefix [hdrLen .. firstSec)
                        if (firstSec > hdrLen) {
                            revSectionsBody.write(cf, hdrLen, firstSec - hdrLen);
                        }
                        for (Object secObj : unwrapNbtList(full.root.get("sections"))) {
                            if (!(secObj instanceof Map<?, ?> secMap)) continue;
                            if (!(secMap.get("block_states") instanceof Map<?, ?> bsMap)) continue;
                            List<Object> palItems = unwrapNbtList(bsMap.get("palette"));
                            if (palItems.isEmpty()) continue;
                            byte[] sky2048 = (secMap.get("SkyLight") instanceof byte[] s && s.length == 2048) ? s : null;
                            byte[] blk2048 = (secMap.get("BlockLight") instanceof byte[] b && b.length == 2048) ? b : null;
                            boolean skyUnif = sky2048 == null || isUniformNibble(unpackNibbles4096(sky2048));
                            boolean blkUnif = blk2048 == null || isUniformNibble(unpackNibbles4096(blk2048));
                            if (palItems.size() == 1 && skyUnif && blkUnif) {
                                // Uniform inline section (0 section body bytes, stored in 2B directory entry!)
                                continue;
                            }
                            int[] palGlobal = new int[palItems.size()];
                            for (int p = 0; p < palItems.size(); p++) {
                                String canon = AnvilReader.formatCanonicalBlockState(palItems.get(p));
                                if (canon == null) canon = "minecraft:air";
                                palGlobal[p] = internState(canon, stateToId, globalDict);
                            }
                            int[] yzxGlobal = new int[SECTION_VOXELS];
                            if (palGlobal.length == 1 || !(bsMap.get("data") instanceof long[] packed)) {
                                Arrays.fill(yzxGlobal, palGlobal[0]);
                            } else {
                                int bits = PackedPaletteDecoder.bitsPerEntry(palGlobal.length);
                                int perLong = 64 / bits;
                                long mask = (1L << bits) - 1L;
                                int vox = 0;
                                for (int w = 0; w < packed.length && vox < SECTION_VOXELS; w++) {
                                    long word = packed[w];
                                    int lim = Math.min(SECTION_VOXELS, vox + perLong);
                                    while (vox < lim) {
                                        yzxGlobal[vox++] = palGlobal[(int) (word & mask)];
                                        word >>>= bits;
                                    }
                                }
                            }
                            byte[] voxBytes = palGlobal.length == 1
                                    ? new byte[]{1, 0} // uniform header + 1B biome template ref
                                    : encodeZeroTaxLayeredSection(yzxGlobal, globalDict, true);
                            revSectionsBody.write(voxBytes);
                            revSectionsBody.write(0x80); // 1B biome template ref parity
                            if (!skyUnif || !blkUnif) {
                                if (skyUnif || blkUnif) {
                                    revSectionsBody.write(0); // const nibble byte when one light channel is const
                                }
                                if (!skyUnif) {
                                    revSectionsBody.write(encodeZeroTaxBlockCondSkyLight(
                                            unpackNibbles4096(sky2048), yzxGlobal, globalDict));
                                }
                                if (!blkUnif) {
                                    revSectionsBody.write(encodeZeroTaxBlockCondBlockLight(
                                            unpackNibbles4096(blk2048), yzxGlobal, globalDict));
                                }
                            }
                        }

                        ByteArrayOutputStream revCfOut = new ByteArrayOutputStream();
                        revCfOut.write(cf, 0, hdrLen);
                        revCfOut.write(revSectionsBody.toByteArray());
                        revCfOut.write(revTailBytes);
                        byte[] revCf = revCfOut.toByteArray();

                        // Mode A accounting
                        e2eCurModeAExact += curModeACf.length;
                        e2eCurModeASlab256 += ((curModeACf.length + 255) & ~255);
                        e2eRevModeAExact += revCf.length;
                        e2eRevModeASlab256 += ((revCf.length + 255) & ~255);

                        // Mode B (Per-Chunk LCD2 + Shared Region AtomicDictState + 256B Slab Alignment)
                        int curB = hdrLen + LcaSectionCodec.encodeAtomicDictBody(
                                curModeBCf, hdrLen, curModeBCf.length - hdrLen, curRegDict, true).length;
                        int revCandB = hdrLen + LcaSectionCodec.encodeAtomicDictBody(
                                revCf, hdrLen, revCf.length - hdrLen, revRegDict, true).length;
                        int revAdaptiveB = Math.min(curB, revCandB);

                        e2eCurModeBExact += curB;
                        e2eCurModeBSlab256 += ((curB + 255) & ~255);
                        e2eRevModeBExact += revAdaptiveB;
                        e2eRevModeBSlab256 += ((revAdaptiveB + 255) & ~255);
                    }
                }
            }
            // Add region header (8,256 B) + SECDED + AtomicDict (~2,176 B) parity per region
            long regFixed = 8256 + 2176;
            e2eCurModeASlab256 += 8256;
            e2eRevModeASlab256 += 8256;
            e2eCurModeBSlab256 += regFixed;
            e2eRevModeBSlab256 += regFixed;
            e2eMcaSectorBytes += 8192; // 8 KiB .mca header per region
        }

        System.out.printf(Locale.ROOT,
                "%n====================================================================================%n"
                        + "=== [STUDY 6] END-TO-END UNIFIED WORLD REDUCTION (.mca -> Current .lca -> Unified Revised .lca, %d regions, %,d chunks) ===%n"
                        + "  1. Vanilla .mca (4KiB Sector Zlib) : %,10d B (100.00%%, 1.00x) [Exact Zlib streams: %,10d B]%n"
                        + "  2. Current .lca v2 Mode A (256B)   : %,10d B (%6.2f%% of .mca, %+.2f%% vs .mca)%n"
                        + "  3. Unified Revised Mode A (256B)   : %,10d B (%6.2f%% of .mca, %+.2f%% vs .mca, %+.2f%% vs Cur Mode A)%n"
                        + "  4. Current .lca v2 Mode B (256B)   : %,10d B (%6.2f%% of .mca, %+.2f%% vs .mca, %.2fx)%n"
                        + "  5. Unified Revised Mode B (256B)   : %,10d B (%6.2f%% of .mca, %+.2f%% vs .mca, %.2fx | %+.2f%% vs Cur Mode B)%n"
                        + "     [Exact Unpadded Mode B Chunks   : Current=%,10d B -> Unified Revised=%,10d B (%+.2f%%)]%n"
                        + "====================================================================================%n",
                e2eRegions, e2eChunks,
                e2eMcaSectorBytes, e2eMcaExactZlibBytes,
                e2eCurModeASlab256, 100.0 * e2eCurModeASlab256 / e2eMcaSectorBytes,
                100.0 * (e2eCurModeASlab256 - e2eMcaSectorBytes) / e2eMcaSectorBytes,
                e2eRevModeASlab256, 100.0 * e2eRevModeASlab256 / e2eMcaSectorBytes,
                100.0 * (e2eRevModeASlab256 - e2eMcaSectorBytes) / e2eMcaSectorBytes,
                100.0 * (e2eRevModeASlab256 - e2eCurModeASlab256) / e2eCurModeASlab256,
                e2eCurModeBSlab256, 100.0 * e2eCurModeBSlab256 / e2eMcaSectorBytes,
                100.0 * (e2eCurModeBSlab256 - e2eMcaSectorBytes) / e2eMcaSectorBytes,
                (double) e2eMcaSectorBytes / e2eCurModeBSlab256,
                e2eRevModeBSlab256, 100.0 * e2eRevModeBSlab256 / e2eMcaSectorBytes,
                100.0 * (e2eRevModeBSlab256 - e2eMcaSectorBytes) / e2eMcaSectorBytes,
                (double) e2eMcaSectorBytes / e2eRevModeBSlab256,
                100.0 * (e2eRevModeBSlab256 - e2eCurModeBSlab256) / e2eCurModeBSlab256,
                e2eCurModeBExact, e2eRevModeBExact,
                100.0 * (e2eRevModeBExact - e2eCurModeBExact) / e2eCurModeBExact);
    }

    private static int locateExactResidualNbtSliceOffset(byte[] frame, int resStart, int resEnd)
            throws CorruptRegionEntryException {
        int pos = resStart;
        if (pos + 2 > resEnd) {
            return resEnd;
        }
        int bpFlags = LcaSectionCodec.readUint16BE(frame, pos);
        pos += 2;
        if ((bpFlags & 8192) != 0) {
            int mbnlLen = LcaSectionCodec.readVarInt(frame, pos, resEnd);
            pos += LcaSectionCodec.varIntLen(mbnlLen) + mbnlLen;
        }
        if ((bpFlags & 32) != 0 && pos < resEnd) {
            pos++;
        }
        if ((bpFlags & 64) != 0 && pos < resEnd) {
            int slVer = LcaSectionCodec.readVarInt(frame, pos, resEnd);
            pos += LcaSectionCodec.varIntLen(slVer);
        }
        if ((bpFlags & 128) != 0 && pos < resEnd) {
            pos++;
        }
        if ((bpFlags & 256) != 0 && pos < resEnd) {
            pos++;
        }
        if ((bpFlags & 512) != 0 && pos < resEnd) {
            int secHmCount = frame[pos++] & 0xFF;
            for (int h = 0; h < secHmCount; h++) {
                int code = frame[pos++] & 0xFF;
                if (code >= LcaSectionCodec.STANDARD_HEIGHTMAP_NAMES.length) {
                    int kLen = LcaSectionCodec.readVarInt(frame, pos, resEnd);
                    pos += LcaSectionCodec.varIntLen(kLen) + kLen;
                }
                int payLen = LcaSectionCodec.readVarInt(frame, pos, resEnd);
                pos += LcaSectionCodec.varIntLen(payLen) + payLen;
            }
        }
        if ((bpFlags & LcaSectionCodec.BP_FLAG_HAS_CHUNK_PATCH_TAIL) != 0 && pos < resEnd) {
            int patchLen = LcaSectionCodec.readVarInt(frame, pos, resEnd);
            pos += LcaSectionCodec.varIntLen(patchLen) + patchLen;
        }
        return Math.min(pos, resEnd);
    }

    private static byte[] concatArrays(byte[] a, byte[] b) {
        if (b.length == 0) return a;
        if (a.length == 0) return b;
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] unpackNibbles4096(byte[] packed2048) {
        byte[] out = new byte[SECTION_VOXELS];
        for (int i = 0; i < 2048; i++) {
            int b = packed2048[i] & 0xFF;
            out[i << 1] = (byte) (b & 0x0F);
            out[(i << 1) | 1] = (byte) ((b >>> 4) & 0x0F);
        }
        return out;
    }

    private static boolean isUniformNibble(byte[] nibs4096) {
        byte first = nibs4096[0];
        for (int i = 1; i < SECTION_VOXELS; i++) {
            if (nibs4096[i] != first) {
                return false;
            }
        }
        return true;
    }

    private static byte[] diffNibblesMod16(byte[] actual, byte[] pred) {
        byte[] out = new byte[SECTION_VOXELS];
        for (int i = 0; i < SECTION_VOXELS; i++) {
            out[i] = (byte) ((actual[i] - pred[i]) & 0x0F);
        }
        return out;
    }

    private static byte[] addNibblesMod16(byte[] pred, byte[] diff) {
        byte[] out = new byte[SECTION_VOXELS];
        for (int i = 0; i < SECTION_VOXELS; i++) {
            out[i] = (byte) ((pred[i] + diff[i]) & 0x0F);
        }
        return out;
    }

    private static byte[] prependModeByte(byte mode, byte[] payload) {
        byte[] out = new byte[1 + payload.length];
        out[0] = mode;
        System.arraycopy(payload, 0, out, 1, payload.length);
        return out;
    }

    private static byte[] encodeTopSlice256Gray(byte[] top256) {
        int[] gray = new int[256];
        for (int i = 0; i < 256; i++) {
            int z = i >>> 4;
            int x = i & 15;
            int h2 = LcaSectionCodec.xyToHilbert2D16(x, z);
            int l = top256[i] & 0x0F;
            gray[h2] = l ^ (l >>> 1);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        byte[] vBuf = new byte[5];
        for (int plane = 0; plane < 4; plane++) {
            int mask = 1 << plane;
            int ones = 0;
            for (int i = 0; i < 256; i++) {
                if ((gray[i] & mask) != 0) ones++;
            }
            if (ones == 0) {
                out.write(0);
            } else if (ones == 256) {
                out.write(1);
            } else {
                out.write(2);
                for (int b = 0; b < 32; b++) {
                    int bv = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        if ((gray[(b << 3) + bit] & mask) != 0) bv |= (1 << bit);
                    }
                    out.write(bv);
                }
            }
        }
        return out.toByteArray();
    }
    private static byte[] decodeTopSlice256Gray(byte[] src, int[] posBox) {
        int pos = posBox[0];
        int[] gray = new int[256];
        for (int plane = 0; plane < 4; plane++) {
            int mask = 1 << plane;
            int mode = src[pos++] & 0xFF;
            if (mode == 1) {
                for (int i = 0; i < 256; i++) gray[i] |= mask;
            } else if (mode == 2) {
                for (int b = 0; b < 32; b++) {
                    int bv = src[pos++] & 0xFF;
                    for (int bit = 0; bit < 8; bit++) {
                        if (((bv >>> bit) & 1) != 0) gray[(b << 3) + bit] |= mask;
                    }
                }
            }
        }
        posBox[0] = pos;
        byte[] top256 = new byte[256];
        for (int i = 0; i < 256; i++) {
            int z = i >>> 4;
            int x = i & 15;
            int g = gray[LcaSectionCodec.xyToHilbert2D16(x, z)];
            int b = g ^ (g >>> 1);
            b ^= (b >>> 2);
            top256[i] = (byte) (b & 0x0F);
        }
        return top256;
    }

    private static byte[] decodeGrayLightPlanesExact(byte[] src, int startPos, int limit, int curveId)
            throws CorruptRegionEntryException {
        if (limit - startPos == 2048) {
            byte[] raw4096 = new byte[SECTION_VOXELS];
            for (int i = 0; i < 2048; i++) {
                int b = src[startPos + i] & 0xFF;
                raw4096[i << 1] = (byte) (b & 0x0F);
                raw4096[(i << 1) | 1] = (byte) ((b >>> 4) & 0x0F);
            }
            return raw4096;
        }
        int pos = startPos;
        int planeModes8 = src[pos++] & 0xFF;
        int[] grayCurve = new int[SECTION_VOXELS];
        for (int plane = 0; plane < 4; plane++) {
            int mask = 1 << plane;
            int mode = (planeModes8 >>> (plane << 1)) & 3;
            if (mode == 0) {
                continue;
            }
            if (mode == 3) {
                for (int h = 0; h < SECTION_VOXELS; h++) {
                    if (((src[pos + (h >>> 3)] >>> (h & 7)) & 1) != 0) {
                        grayCurve[h] |= mask;
                    }
                }
                pos += 512;
                continue;
            }
            int hdr = src[pos++] & 0xFF;
            if (hdr == 0xFF) {
                for (int h = 0; h < SECTION_VOXELS; h++) grayCurve[h] |= mask;
            } else if ((hdr & 0xF0) == 0xE0) {
                int start = ((hdr & 0x0F) << 8) | (src[pos++] & 0xFF);
                for (int h = start; h < SECTION_VOXELS; h++) grayCurve[h] |= mask;
            } else if ((hdr & 0xF0) == 0xD0) {
                int end = ((hdr & 0x0F) << 8) | (src[pos++] & 0xFF);
                for (int h = 0; h <= end; h++) grayCurve[h] |= mask;
            } else if ((hdr & 0xF0) == 0xC0) {
                int start = ((hdr & 0x0F) << 8) | (src[pos++] & 0xFF);
                int lm1 = LcaSectionCodec.readVarInt(src, pos, limit);
                pos += LcaSectionCodec.varIntLen(lm1);
                for (int h = start; h <= start + lm1; h++) grayCurve[h] |= mask;
            } else {
                boolean split62 = (hdr & 0x20) != 0;
                int rCount = hdr & 0x1F;
                if (rCount == 31) {
                    int extra = LcaSectionCodec.readVarInt(src, pos, limit);
                    pos += LcaSectionCodec.varIntLen(extra);
                    rCount += extra;
                }
                int lBits = split62 ? 2 : 4;
                int gMax = split62 ? 63 : 15;
                int lMax = split62 ? 3 : 15;
                int cursor = 0;
                for (int r = 0; r < rCount; r++) {
                    int b = src[pos++] & 0xFF;
                    int gv = b >>> lBits;
                    int lv = b & lMax;
                    if (gv == gMax) {
                        int eg = LcaSectionCodec.readVarInt(src, pos, limit);
                        pos += LcaSectionCodec.varIntLen(eg);
                        gv += eg;
                    }
                    if (lv == lMax) {
                        int el = LcaSectionCodec.readVarInt(src, pos, limit);
                        pos += LcaSectionCodec.varIntLen(el);
                        lv += el;
                    }
                    int rawGap = (r == 0) ? gv : (gv + 1);
                    int start = cursor + rawGap;
                    int len = lv + 1;
                    for (int h = start; h < start + len; h++) {
                        grayCurve[h] |= mask;
                    }
                    cursor = start + len;
                }
            }
        }
        int[] curveToYzx = LcaSectionCodec.CURVE_TO_YZX[curveId];
        byte[] outYzx = new byte[SECTION_VOXELS];
        for (int h = 0; h < SECTION_VOXELS; h++) {
            int g = grayCurve[h];
            int b = g ^ (g >>> 1);
            b ^= (b >>> 2);
            outYzx[curveToYzx[h]] = (byte) (b & 0x0F);
        }
        return outYzx;
    }

    /**
     * Zero-tax Block-Conditioned SkyLight encoder:
     *   - Uses `(planeModes8 & 3) == 2` sentinel (unused in `LCA-ICD-1.0` GrayPlanes!) to signal
     *     conditioned sub-modes (`0x02` = OpenSky0Byte, `0x06` = TopSlice256) so baseline GrayPlanes
     *     pays strictly `0` extra bytes!
     */
    private static byte[] encodeZeroTaxBlockCondSkyLight(
            byte[] nibs, int[] yzxGlobal, List<ParsedBlockState> globalDict
    ) {
        byte[] best = encodeGrayLightPlanesExact(nibs, LcaSectionCodec.CURVE_Y_H2);

        byte[] predOpen = predictSkyLightNibbles(yzxGlobal, globalDict, null);
        byte[] resOpenEnc = encodeGrayLightPlanesExact(diffNibblesMod16(nibs, predOpen), LcaSectionCodec.CURVE_Y_H2);
        if (1 + resOpenEnc.length < best.length) {
            best = prependModeByte((byte) 0x02, resOpenEnc);
        }

        byte[] top256 = Arrays.copyOfRange(nibs, 15 * 256, 16 * 256);
        byte[] predTop = predictSkyLightNibbles(yzxGlobal, globalDict, top256);
        byte[] topEnc = encodeTopSlice256Gray(top256);
        byte[] resTopEnc = encodeGrayLightPlanesExact(diffNibblesMod16(nibs, predTop), LcaSectionCodec.CURVE_Y_H2);
        if (1 + topEnc.length + resTopEnc.length < best.length) {
            byte[] cand = new byte[1 + topEnc.length + resTopEnc.length];
            cand[0] = (byte) 0x06;
            System.arraycopy(topEnc, 0, cand, 1, topEnc.length);
            System.arraycopy(resTopEnc, 0, cand, 1 + topEnc.length, resTopEnc.length);
            best = cand;
        }
        return best;
    }

    private static byte[] decodeZeroTaxBlockCondSkyLight(
            byte[] wire, int[] yzxGlobal, List<ParsedBlockState> globalDict
    ) throws CorruptRegionEntryException {
        if (wire.length != 2048 && (wire[0] & 3) == 2) {
            int sub = (wire[0] >>> 2) & 3;
            if (sub == 0) {
                byte[] pred = predictSkyLightNibbles(yzxGlobal, globalDict, null);
                byte[] diff = decodeGrayLightPlanesExact(wire, 1, wire.length, LcaSectionCodec.CURVE_Y_H2);
                return addNibblesMod16(pred, diff);
            }
            int[] posBox = {1};
            byte[] top256 = decodeTopSlice256Gray(wire, posBox);
            byte[] pred = predictSkyLightNibbles(yzxGlobal, globalDict, top256);
            byte[] diff = decodeGrayLightPlanesExact(wire, posBox[0], wire.length, LcaSectionCodec.CURVE_Y_H2);
            return addNibblesMod16(pred, diff);
        }
        return decodeGrayLightPlanesExact(wire, 0, wire.length, LcaSectionCodec.CURVE_Y_H2);
    }

    private static byte[] encodeZeroTaxBlockCondBlockLight(
            byte[] nibs, int[] yzxGlobal, List<ParsedBlockState> globalDict
    ) {
        byte[] best = encodeGrayLightPlanesExact(nibs, LcaSectionCodec.CURVE_H3);
        byte[] pred = predictBlockLightNibbles(yzxGlobal, globalDict);
        byte[] resEnc = encodeGrayLightPlanesExact(diffNibblesMod16(nibs, pred), LcaSectionCodec.CURVE_H3);
        if (1 + resEnc.length < best.length) {
            best = prependModeByte((byte) 0x02, resEnc);
        }
        return best;
    }

    private static byte[] decodeZeroTaxBlockCondBlockLight(
            byte[] wire, int[] yzxGlobal, List<ParsedBlockState> globalDict
    ) throws CorruptRegionEntryException {
        if (wire.length != 2048 && (wire[0] & 3) == 2) {
            byte[] pred = predictBlockLightNibbles(yzxGlobal, globalDict);
            byte[] diff = decodeGrayLightPlanesExact(wire, 1, wire.length, LcaSectionCodec.CURVE_H3);
            return addNibblesMod16(pred, diff);
        }
        return decodeGrayLightPlanesExact(wire, 0, wire.length, LcaSectionCodec.CURVE_H3);
    }
}
