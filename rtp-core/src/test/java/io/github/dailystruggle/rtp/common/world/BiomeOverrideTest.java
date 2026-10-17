package io.github.dailystruggle.rtp.common.world;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class BiomeOverrideTest {

    @AfterEach
    void reset() {
        BiomeOverride.set(null);
    }

    @Test
    void emptyByDefault() {
        BiomeOverride.set(null);
        assertNull(BiomeOverride.get());
    }

    @Test
    void installedResolverIsReturned() {
        java.util.function.Function<io.github.dailystruggle.rtp.api.world.RTPLocation, String> stub = loc -> "STUB";
        BiomeOverride.set(stub);
        assertSame(stub, BiomeOverride.get());
    }

    @Test
    void nullClearsResolver() {
        BiomeOverride.set(loc -> "STUB");
        BiomeOverride.set(null);
        assertNull(BiomeOverride.get());
    }
}
