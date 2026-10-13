package io.github.dailystruggle.rtp.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ADR-077 - RegionFileReader length-bounded default methods")
class RegionFileReaderDefaultsTest {

    /** Records the array each delegate call receives; reports generated iff the array is non-null. */
    private static final class RecordingReader implements RegionFileReader {
        byte[] lastBytes;
        int calls;

        @Override
        public AnvilReader.ChunkEntry readChunk(byte[] regionBytes, int rx, int rz) {
            lastBytes = regionBytes;
            calls++;
            return null;
        }

        @Override
        public boolean isChunkGenerated(byte[] regionBytes, int rx, int rz) {
            lastBytes = regionBytes;
            calls++;
            return regionBytes != null;
        }
    }

    @Test
    @DisplayName("readChunk passes the buffer through uncopied when the length matches")
    void readChunkExactLengthNoCopy() throws IOException {
        RecordingReader reader = new RecordingReader();
        byte[] buf = {1, 2, 3, 4};
        assertNull(reader.readChunk(buf, buf.length, 0, 0));
        assertSame(buf, reader.lastBytes);
    }

    @Test
    @DisplayName("readChunk trims a longer pooled buffer to the region length")
    void readChunkTrimsPooledBuffer() throws IOException {
        RecordingReader reader = new RecordingReader();
        byte[] buf = {1, 2, 3, 4};
        reader.readChunk(buf, 2, 0, 0);
        assertNotSame(buf, reader.lastBytes);
        assertArrayEquals(new byte[]{1, 2}, reader.lastBytes);
    }

    @Test
    @DisplayName("readChunk forwards a null buffer to the format reader")
    void readChunkNullBuffer() throws IOException {
        RecordingReader reader = new RecordingReader();
        reader.readChunk(null, 7, 0, 0);
        assertNull(reader.lastBytes);
        assertTrue(reader.calls == 1);
    }

    @Test
    @DisplayName("readChunk rejects a region length outside the buffer")
    void readChunkRejectsOutOfRangeLength() {
        RecordingReader reader = new RecordingReader();
        byte[] buf = new byte[4];
        assertThrows(IllegalArgumentException.class, () -> reader.readChunk(buf, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> reader.readChunk(buf, 5, 0, 0));
        assertTrue(reader.calls == 0);
    }

    @Test
    @DisplayName("isChunkGenerated fails closed on null buffer or out-of-range length")
    void isChunkGeneratedFailsClosed() {
        RecordingReader reader = new RecordingReader();
        byte[] buf = new byte[4];
        assertFalse(reader.isChunkGenerated(null, 0, 0, 0));
        assertFalse(reader.isChunkGenerated(buf, -1, 0, 0));
        assertFalse(reader.isChunkGenerated(buf, 5, 0, 0));
        assertTrue(reader.calls == 0, "out-of-range input must never reach the format reader");
    }

    @Test
    @DisplayName("isChunkGenerated delegates with the exact or trimmed buffer")
    void isChunkGeneratedDelegates() {
        RecordingReader reader = new RecordingReader();
        byte[] buf = {9, 8, 7};
        assertTrue(reader.isChunkGenerated(buf, buf.length, 1, 2));
        assertSame(buf, reader.lastBytes);
        assertTrue(reader.isChunkGenerated(buf, 1, 1, 2));
        assertArrayEquals(new byte[]{9}, reader.lastBytes);
    }
}
