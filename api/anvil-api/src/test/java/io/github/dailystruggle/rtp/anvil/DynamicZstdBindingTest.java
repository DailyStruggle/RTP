package io.github.dailystruggle.rtp.anvil;

import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.PbitResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DynamicZstdBinding - PBIT Canary and Host Discovery")
class DynamicZstdBindingTest {

    @Test
    @DisplayName("PBIT canary succeeds when host zstd is on classpath")
    void pbitCanarySucceedsWithHostZstd() {
        assertTrue(DynamicZstdBinding.isAvailable(), "Host Zstd should be available on test classpath");
        assertEquals(BitHealth.HEALTHY, DynamicZstdBinding.health());
        assertEquals(BitHealth.HEALTHY, DynamicZstdBinding.INSTANCE.getHealthStatus());

        PbitResult pbit = DynamicZstdBinding.pbitResult();
        assertNotNull(pbit);
        assertTrue(pbit.passed());
        assertEquals("DynamicZstdBinding", pbit.componentName());
        assertTrue(pbit.durationNanos() > 0);
        assertEquals(pbit, DynamicZstdBinding.INSTANCE.executePbit());

        // Cover PbitResult factory and accessors
        PbitResult okSimple = PbitResult.ok("test", 123L);
        assertTrue(okSimple.passed());
        assertEquals(123L, okSimple.durationNanos());
        assertEquals("test", okSimple.componentName());
        assertNull(okSimple.error());

        Exception ex = new RuntimeException("err");
        PbitResult failed = PbitResult.failed("test", 456L, "msg", ex);
        assertFalse(failed.passed());
        assertEquals("msg", failed.diagnosticMessage());
        assertEquals(ex, failed.error());
    }

    @Test
    @DisplayName("Round-trip compression/decompression via host zstd")
    void roundTripDecompress() throws Exception {
        byte[] original = "Hello LeafRTP Linear World!".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = com.github.luben.zstd.Zstd.compress(original);

        byte[] decompressed = DynamicZstdBinding.decompress(compressed, 0, compressed.length, 1024);
        assertArrayEquals(original, decompressed);

        // Limit smaller than payload throws CorruptRegionEntryException
        org.junit.jupiter.api.Assertions.assertThrows(CorruptRegionEntryException.class,
                () -> DynamicZstdBinding.decompress(compressed, 0, compressed.length, 5));

        // Corrupt frame throws CorruptRegionEntryException
        byte[] corrupt = new byte[] { 0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, 0x01, 0x02, 0x03 };
        org.junit.jupiter.api.Assertions.assertThrows(CorruptRegionEntryException.class,
                () -> DynamicZstdBinding.decompress(corrupt, 0, corrupt.length, 1024));
    }
}
