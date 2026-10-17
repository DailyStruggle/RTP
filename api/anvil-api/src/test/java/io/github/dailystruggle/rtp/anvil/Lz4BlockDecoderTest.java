package io.github.dailystruggle.rtp.anvil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import net.jpountz.lz4.LZ4BlockOutputStream;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.xxhash.XXHashFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Differential and hardening tests for the in-house mode-4 decoder. lz4-java (test scope only,
 * pure-Java safe instances) is the reference encoder and hash.
 */
@DisplayName("ADR-016: in-house LZ4Block decoder matches lz4-java and fails closed")
class Lz4BlockDecoderTest {

    private static final int SEED = 0x9747b28c;
    private static final int CAP = AnvilReader.MAX_DECOMPRESSED_CHUNK_BYTES;

    private static byte[] encode(byte[] data, int blockSize, LZ4Compressor compressor) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (LZ4BlockOutputStream out = new LZ4BlockOutputStream(bos, blockSize, compressor,
                XXHashFactory.safeInstance().newStreamingHash32(SEED).asChecksum(), false)) {
            out.write(data);
        }
        return bos.toByteArray();
    }

    private static byte[] encode(byte[] data) throws IOException {
        return encode(data, 1 << 16, LZ4Factory.safeInstance().fastCompressor());
    }

    /** Compressible pseudo-NBT: repeated palette-like strings mixed with small random runs. */
    private static byte[] compressible(int size, long seed) {
        Random r = new Random(seed);
        byte[] words = "minecraft:stone minecraft:dirt minecraft:grass_block Heightmaps sections "
                .getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            out[i] = r.nextInt(8) == 0 ? (byte) r.nextInt(256) : words[(i + r.nextInt(3)) % words.length];
        }
        return out;
    }

    private static byte[] random(int size, long seed) {
        byte[] out = new byte[size];
        new Random(seed).nextBytes(out);
        return out;
    }

    private static void writeIntLE(byte[] b, int i, int v) {
        b[i] = (byte) v;
        b[i + 1] = (byte) (v >>> 8);
        b[i + 2] = (byte) (v >>> 16);
        b[i + 3] = (byte) (v >>> 24);
    }

    /** Hand-built single block + end marker with an arbitrary token and payload. */
    private static byte[] handBlock(int token, int compressedLen, int originalLen, int check, byte[] payload) {
        byte[] magic = "LZ4Block".getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[21 + payload.length + 21];
        System.arraycopy(magic, 0, out, 0, 8);
        out[8] = (byte) token;
        writeIntLE(out, 9, compressedLen);
        writeIntLE(out, 13, originalLen);
        writeIntLE(out, 17, check);
        System.arraycopy(payload, 0, out, 21, payload.length);
        int e = 21 + payload.length;
        System.arraycopy(magic, 0, out, e, 8);
        out[e + 8] = 0x10;
        return out;
    }

    @Test
    @DisplayName("Round-trips lz4-java output across sizes, block sizes, and compressors")
    void differentialRoundTrip() throws IOException {
        int[] sizes = {0, 1, 12, 13, 15, 16, 17, 100, 4096, 65535, 65536, 65537, 200_000};
        int[] blockSizes = {64, 1 << 16, 1 << 20};
        LZ4Compressor[] compressors = {
                LZ4Factory.safeInstance().fastCompressor(), LZ4Factory.safeInstance().highCompressor()};
        for (int size : sizes) {
            for (int blockSize : blockSizes) {
                for (LZ4Compressor c : compressors) {
                    byte[] data = compressible(size, size * 31L + blockSize);
                    byte[] enc = encode(data, blockSize, c);
                    assertArrayEquals(data, Lz4BlockDecoder.decode(enc, 0, enc.length, CAP),
                            "size=" + size + " block=" + blockSize);
                }
            }
        }
    }

    @Test
    @DisplayName("Incompressible data (raw blocks) round-trips")
    void rawBlocksRoundTrip() throws IOException {
        byte[] data = random(150_000, 7);
        byte[] enc = encode(data);
        assertArrayEquals(data, Lz4BlockDecoder.decode(enc, 0, enc.length, CAP));
    }

    @Test
    @DisplayName("Decodes a stream at a non-zero offset inside a larger buffer")
    void offsetWithinBuffer() throws IOException {
        byte[] data = compressible(10_000, 3);
        byte[] enc = encode(data);
        byte[] padded = new byte[enc.length + 37];
        System.arraycopy(enc, 0, padded, 17, enc.length);
        assertArrayEquals(data, Lz4BlockDecoder.decode(padded, 17, enc.length, CAP));
    }

    @Test
    @DisplayName("XXHash32 matches lz4-java for lengths 0..300")
    void xxHash32MatchesReference() {
        byte[] data = random(300, 11);
        for (int len = 0; len <= 300; len++) {
            int expected = XXHashFactory.safeInstance().hash32().hash(data, 0, len, SEED);
            assertEquals(expected, Lz4BlockDecoder.xxHash32(data, 0, len, SEED), "len=" + len);
        }
    }

    @Test
    @DisplayName("Every truncation of a valid stream is rejected as corrupt")
    void truncationRejected() throws IOException {
        byte[] enc = encode(compressible(70_000, 5));
        for (int len = 0; len < enc.length; len++) {
            final int l = len;
            assertThrows(CorruptRegionEntryException.class, () -> Lz4BlockDecoder.decode(enc, 0, l, CAP),
                    "len=" + len);
        }
    }

    @Test
    @DisplayName("Checksum mismatch is rejected")
    void checksumMismatchRejected() throws IOException {
        byte[] enc = encode(compressible(1000, 9));
        enc[17] ^= 0x01;
        assertThrows(CorruptRegionEntryException.class, () -> Lz4BlockDecoder.decode(enc, 0, enc.length, CAP));
    }

    @Test
    @DisplayName("Bad magic and unknown method are rejected")
    void badMagicAndMethodRejected() throws IOException {
        byte[] enc = encode(compressible(1000, 9));
        byte[] badMagic = enc.clone();
        badMagic[0] = 'X';
        assertThrows(CorruptRegionEntryException.class,
                () -> Lz4BlockDecoder.decode(badMagic, 0, badMagic.length, CAP));
        byte[] badMethod = enc.clone();
        badMethod[8] = (byte) (0x30 | (badMethod[8] & 0x0F));
        assertThrows(CorruptRegionEntryException.class,
                () -> Lz4BlockDecoder.decode(badMethod, 0, badMethod.length, CAP));
    }

    @Test
    @DisplayName("Declared size over the cap is rejected before allocation")
    void declaredSizeOverCapRejected() throws IOException {
        byte[] enc = encode(compressible(5000, 13));
        assertThrows(CorruptRegionEntryException.class, () -> Lz4BlockDecoder.decode(enc, 0, enc.length, 4999));
        assertEquals(5000, Lz4BlockDecoder.decode(enc, 0, enc.length, 5000).length);
    }

    @Test
    @DisplayName("Forged huge originalLen is rejected (level bound and payload bound)")
    void forgedHeaderRejected() {
        // Level 15 permits 32 MiB, but the payload is tiny -> raw length mismatch / truncation.
        byte[] forged = handBlock(0x2F, 4, Integer.MAX_VALUE, 0, new byte[4]);
        assertThrows(CorruptRegionEntryException.class, () -> Lz4BlockDecoder.decode(forged, 0, forged.length, CAP));
        // originalLen above 1 << (10 + level).
        byte[] overLevel = handBlock(0x20, 4, 2048, 0, new byte[4]);
        assertThrows(CorruptRegionEntryException.class,
                () -> Lz4BlockDecoder.decode(overLevel, 0, overLevel.length, CAP));
        // Negative lengths.
        byte[] negative = handBlock(0x26, -1, 10, 0, new byte[0]);
        assertThrows(CorruptRegionEntryException.class,
                () -> Lz4BlockDecoder.decode(negative, 0, negative.length, CAP));
    }

    @Test
    @DisplayName("Match offset reaching before the block start is rejected")
    void matchOffsetOutOfWindowRejected() {
        // token: 1 literal, match len 4; literal 'A'; offset 5 (> 1 byte written).
        byte[] payload = {0x10, 'A', 0x05, 0x00};
        byte[] stream = handBlock(0x26, payload.length, 5, 0, payload);
        assertThrows(CorruptRegionEntryException.class, () -> Lz4BlockDecoder.decode(stream, 0, stream.length, CAP));
    }

    @Test
    @DisplayName("Overlapping match (RLE) decodes")
    void overlappingMatchDecodes() throws IOException {
        byte[] data = new byte[10_000];
        Arrays.fill(data, (byte) 'z');
        byte[] enc = encode(data);
        assertArrayEquals(data, Lz4BlockDecoder.decode(enc, 0, enc.length, CAP));
    }

    @Test
    @DisplayName("Missing end-of-stream block is rejected; empty stream decodes to nothing")
    void endOfStreamHandling() throws IOException {
        byte[] empty = encode(new byte[0]);
        assertEquals(21, empty.length);
        assertEquals(0, Lz4BlockDecoder.decode(empty, 0, empty.length, CAP).length);
        byte[] enc = encode(compressible(1000, 17));
        assertThrows(CorruptRegionEntryException.class,
                () -> Lz4BlockDecoder.decode(enc, 0, enc.length - 21, CAP));
    }

    @Test
    @DisplayName("Random byte mutations only ever raise CorruptRegionEntryException")
    void mutationsFailClosed() throws IOException {
        byte[] data = compressible(20_000, 19);
        byte[] enc = encode(data);
        Random r = new Random(23);
        for (int iter = 0; iter < 5000; iter++) {
            byte[] m = enc.clone();
            int flips = 1 + r.nextInt(4);
            for (int i = 0; i < flips; i++) {
                m[r.nextInt(m.length)] = (byte) r.nextInt(256);
            }
            try {
                Lz4BlockDecoder.decode(m, 0, m.length, CAP);
            } catch (CorruptRegionEntryException expected) {
                // fail-closed
            } catch (RuntimeException e) {
                fail("iteration " + iter + " threw " + e);
            }
        }
    }
}
