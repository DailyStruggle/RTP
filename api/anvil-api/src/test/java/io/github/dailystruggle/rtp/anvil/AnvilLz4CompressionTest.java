package io.github.dailystruggle.rtp.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import net.jpountz.lz4.LZ4BlockOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Anvil compression mode 4 ({@code region-file-compression=lz4}, MC 24w04a+). Vanilla's
 * {@code RegionFileVersion.VERSION_LZ4} writes lz4-java's {@code LZ4BlockOutputStream}
 * format, not the LZ4 frame format.
 */
@DisplayName("ADR-016: Anvil mode-4 (LZ4) chunks decode")
class AnvilLz4CompressionTest {

    private static final int Y = 64;

    private static byte[] sectorBuffer(byte[] payload, int mode) {
        int declared = payload.length + 1;
        byte[] out = new byte[((5 + payload.length + 4095) / 4096) * 4096];
        out[0] = (byte) (declared >>> 24);
        out[1] = (byte) (declared >>> 16);
        out[2] = (byte) (declared >>> 8);
        out[3] = (byte) declared;
        out[4] = (byte) mode;
        System.arraycopy(payload, 0, out, 5, payload.length);
        return out;
    }

    @Test
    @DisplayName("LZ4Block-compressed chunk decodes to the same view as its NBT")
    void lz4BlockChunkDecodes() throws IOException {
        LinkedHashMap<String, Object> sec = AnvilTestFixtures.sectionWithBiomes(
                (byte) (Y >> 4), List.of("minecraft:stone"), null, List.of("minecraft:plains"), null);
        byte[] nbt = Nbt.writeNamedRoot("", AnvilTestFixtures.chunkRoot(4671, new long[37], List.of(sec)));

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (LZ4BlockOutputStream lz4 = new LZ4BlockOutputStream(compressed)) {
            lz4.write(nbt);
        }

        AnvilChunkView view = AnvilReader.readChunkViewFromSectors(sectorBuffer(compressed.toByteArray(), 4), 0, 0);
        assertNotNull(view);
        assertEquals("minecraft:plains", view.getBiomeAt(8, Y, 8));
    }
}
