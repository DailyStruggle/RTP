package io.github.dailystruggle.rtp.anvil;

/**
 * In-house decoder for Anvil compression mode 4 ({@code region-file-compression=lz4}).
 *
 * <p>Vanilla {@code RegionFileVersion.VERSION_LZ4} writes the lz4-java {@code LZ4BlockOutputStream}
 * container (not the LZ4 frame format): a sequence of blocks, each with a 21-byte header
 * {@code "LZ4Block" | token | compressedLen LE | originalLen LE | checksum LE}, terminated by an
 * empty block. Token high nibble: {@code 0x10} raw, {@code 0x20} LZ4; low nibble {@code L} bounds
 * {@code originalLen <= 1 << (10 + L)}. Checksum: XXHash32 (seed {@code 0x9747b28c}) of the
 * decompressed block, masked to 28 bits (lz4-java {@code asChecksum()} quirk).
 *
 * <p>Pure Java on {@code byte[]} with explicit bounds checks; no {@code Unsafe}, no JNI. Pass 1
 * validates every header and sums declared sizes against the caller's cap before any output
 * allocation, so a forged header cannot force a large allocation. Every malformed input surfaces
 * as {@link CorruptRegionEntryException} (fail-closed to {@link Verdict#UNKNOWN}).
 */
final class Lz4BlockDecoder {

    private static final byte[] MAGIC = {'L', 'Z', '4', 'B', 'l', 'o', 'c', 'k'};
    private static final int HEADER_LENGTH = MAGIC.length + 1 + 4 + 4 + 4;
    private static final int COMPRESSION_LEVEL_BASE = 10;
    private static final int METHOD_RAW = 0x10;
    private static final int METHOD_LZ4 = 0x20;
    private static final int CHECKSUM_SEED = 0x9747b28c;
    private static final int CHECKSUM_MASK = 0x0FFFFFFF;

    private static final int MIN_MATCH = 4;

    private static final int P1 = 0x9E3779B1;
    private static final int P2 = 0x85EBCA77;
    private static final int P3 = 0xC2B2AE3D;
    private static final int P4 = 0x27D4EB2F;
    private static final int P5 = 0x165667B1;

    private Lz4BlockDecoder() {}

    /**
     * Decodes one complete LZ4Block stream occupying {@code src[off, off + len)}.
     *
     * @param maxOut upper bound on total decompressed bytes; exceeded declarations are rejected
     *               before allocation
     * @throws CorruptRegionEntryException on any structural, bounds, or checksum violation, a
     *                                     missing end-of-stream block, or a cap violation
     */
    static byte[] decode(byte[] src, int off, int len, int maxOut) throws CorruptRegionEntryException {
        if (src == null || off < 0 || len < 0 || off > src.length - len) {
            throw new CorruptRegionEntryException("LZ4 input range out of bounds");
        }
        final int end = off + len;

        // Pass 1: header walk -> exact output size, no payload work.
        long total = 0;
        int p = off;
        while (true) {
            int[] h = readHeader(src, p, end);
            int compressedLen = h[1];
            int originalLen = h[2];
            p += HEADER_LENGTH;
            if (originalLen == 0) break; // end-of-stream block
            total += originalLen;
            if (total > maxOut) {
                throw new CorruptRegionEntryException("LZ4 declared decompressed size exceeds " + maxOut + " bytes");
            }
            p += compressedLen; // readHeader guaranteed it fits
        }

        // Pass 2: decode into a single exact-size buffer.
        byte[] out = new byte[(int) total];
        int dp = 0;
        p = off;
        while (true) {
            int[] h = readHeader(src, p, end);
            int method = h[0];
            int compressedLen = h[1];
            int originalLen = h[2];
            int check = h[3];
            p += HEADER_LENGTH;
            if (originalLen == 0) break;
            if (method == METHOD_RAW) {
                System.arraycopy(src, p, out, dp, originalLen);
            } else {
                decodeBlock(src, p, compressedLen, out, dp, originalLen);
            }
            if ((xxHash32(out, dp, originalLen, CHECKSUM_SEED) & CHECKSUM_MASK) != check) {
                throw new CorruptRegionEntryException("LZ4 block checksum mismatch");
            }
            p += compressedLen;
            dp += originalLen;
        }
        return out;
    }

    /**
     * Parses and validates the block header at {@code p}. Returns
     * {@code {method, compressedLen, originalLen, check}}; guarantees the block payload lies in
     * {@code [p + HEADER_LENGTH, end)}. Validation mirrors lz4-java's {@code LZ4BlockInputStream}.
     */
    private static int[] readHeader(byte[] src, int p, int end) throws CorruptRegionEntryException {
        if (end - p < HEADER_LENGTH) {
            throw new CorruptRegionEntryException("LZ4 stream ended before end-of-stream block");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (src[p + i] != MAGIC[i]) {
                throw new CorruptRegionEntryException("LZ4 block magic mismatch");
            }
        }
        int token = src[p + MAGIC.length] & 0xFF;
        int method = token & 0xF0;
        int level = COMPRESSION_LEVEL_BASE + (token & 0x0F);
        if (method != METHOD_RAW && method != METHOD_LZ4) {
            throw new CorruptRegionEntryException("LZ4 unknown block method 0x" + Integer.toHexString(method));
        }
        int compressedLen = intLE(src, p + MAGIC.length + 1);
        int originalLen = intLE(src, p + MAGIC.length + 5);
        int check = intLE(src, p + MAGIC.length + 9);
        if (originalLen < 0 || compressedLen < 0 || originalLen > (1 << level)
                || (originalLen == 0) != (compressedLen == 0)
                || (method == METHOD_RAW && originalLen != compressedLen)) {
            throw new CorruptRegionEntryException("LZ4 block header inconsistent (compressed=" + compressedLen
                    + ", original=" + originalLen + ", level=" + (level - COMPRESSION_LEVEL_BASE) + ")");
        }
        if (originalLen == 0 && check != 0) {
            throw new CorruptRegionEntryException("LZ4 end-of-stream block has non-zero checksum");
        }
        if (compressedLen > end - p - HEADER_LENGTH) {
            throw new CorruptRegionEntryException("LZ4 block payload truncated");
        }
        return new int[] {method, compressedLen, originalLen, check};
    }

    /**
     * Decodes one raw LZ4 block {@code src[sp, sp + srcLen)} into exactly
     * {@code dst[dOff, dOff + dstLen)}. Matches may only reference bytes of this block.
     */
    static void decodeBlock(byte[] src, int sp, int srcLen, byte[] dst, int dOff, int dstLen)
            throws CorruptRegionEntryException {
        final int se = sp + srcLen;
        final int de = dOff + dstLen;
        int dp = dOff;
        while (true) {
            if (sp >= se) throw new CorruptRegionEntryException("LZ4 block truncated at token");
            int token = src[sp++] & 0xFF;

            int litLen = token >>> 4;
            if (litLen == 15) {
                int b;
                do {
                    if (sp >= se) throw new CorruptRegionEntryException("LZ4 block truncated in literal length");
                    b = src[sp++] & 0xFF;
                    litLen += b;
                    if (litLen > dstLen) throw new CorruptRegionEntryException("LZ4 literal length overflow");
                } while (b == 255);
            }
            if (litLen > se - sp || litLen > de - dp) {
                throw new CorruptRegionEntryException("LZ4 literal run out of bounds");
            }
            System.arraycopy(src, sp, dst, dp, litLen);
            sp += litLen;
            dp += litLen;

            if (sp == se) break; // last sequence carries literals only

            if (se - sp < 2) throw new CorruptRegionEntryException("LZ4 block truncated at match offset");
            int offset = (src[sp] & 0xFF) | ((src[sp + 1] & 0xFF) << 8);
            sp += 2;
            if (offset == 0 || offset > dp - dOff) {
                throw new CorruptRegionEntryException("LZ4 match offset " + offset + " out of window");
            }

            int matchLen = token & 0x0F;
            if (matchLen == 15) {
                int b;
                do {
                    if (sp >= se) throw new CorruptRegionEntryException("LZ4 block truncated in match length");
                    b = src[sp++] & 0xFF;
                    matchLen += b;
                    if (matchLen > dstLen) throw new CorruptRegionEntryException("LZ4 match length overflow");
                } while (b == 255);
            }
            matchLen += MIN_MATCH;
            if (matchLen > de - dp) throw new CorruptRegionEntryException("LZ4 match run out of bounds");

            int mp = dp - offset;
            if (offset >= matchLen) {
                System.arraycopy(dst, mp, dst, dp, matchLen);
            } else {
                // Overlapping copy replicates the trailing pattern byte by byte.
                for (int i = 0; i < matchLen; i++) dst[dp + i] = dst[mp + i];
            }
            dp += matchLen;
        }
        if (dp != de) {
            throw new CorruptRegionEntryException("LZ4 block decoded " + (dp - dOff) + " bytes, header declared " + dstLen);
        }
    }

    /** One-shot XXHash32; identical to a single-update streaming hash. */
    static int xxHash32(byte[] b, int off, int len, int seed) {
        final int end = off + len;
        int h;
        if (len >= 16) {
            final int limit = end - 16;
            int v1 = seed + P1 + P2;
            int v2 = seed + P2;
            int v3 = seed;
            int v4 = seed - P1;
            do {
                v1 = round(v1, intLE(b, off));
                v2 = round(v2, intLE(b, off + 4));
                v3 = round(v3, intLE(b, off + 8));
                v4 = round(v4, intLE(b, off + 12));
                off += 16;
            } while (off <= limit);
            h = Integer.rotateLeft(v1, 1) + Integer.rotateLeft(v2, 7)
                    + Integer.rotateLeft(v3, 12) + Integer.rotateLeft(v4, 18);
        } else {
            h = seed + P5;
        }
        h += len;
        while (off <= end - 4) {
            h += intLE(b, off) * P3;
            h = Integer.rotateLeft(h, 17) * P4;
            off += 4;
        }
        while (off < end) {
            h += (b[off] & 0xFF) * P5;
            h = Integer.rotateLeft(h, 11) * P1;
            off++;
        }
        h ^= h >>> 15;
        h *= P2;
        h ^= h >>> 13;
        h *= P3;
        h ^= h >>> 16;
        return h;
    }

    private static int round(int acc, int input) {
        acc += input * P2;
        acc = Integer.rotateLeft(acc, 13);
        return acc * P1;
    }

    private static int intLE(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16) | ((b[i + 3] & 0xFF) << 24);
    }
}
