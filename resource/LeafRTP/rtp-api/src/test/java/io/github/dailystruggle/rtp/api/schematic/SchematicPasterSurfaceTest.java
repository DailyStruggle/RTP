package io.github.dailystruggle.rtp.api.schematic;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Surface guard for the ADR-058 {@code SchematicPaster} SPI (platform-neutral
 * pieces only). Verifies the value-object contracts, the {@link NoOpSchematicPaster}
 * default (S-006: never NPE, never silently succeed), and that a simple
 * {@code MockSchematicPaster} honours the load/paste split contract.
 */
class SchematicPasterSurfaceTest {

    private static SchematicSource source() {
        return new SchematicSource("hub", Path.of("schematics", "hub.schem"), "schem");
    }

    @Test
    void sourceRejectsNullNameAndPath() {
        assertThrows(NullPointerException.class,
                () -> new SchematicSource(null, Path.of("a"), "schem"));
        assertThrows(NullPointerException.class,
                () -> new SchematicSource("n", null, "schem"));
    }

    @Test
    void sourceNormalisesNullFormatHintToEmpty() {
        SchematicSource s = new SchematicSource("n", Path.of("a"), null);
        assertEquals("", s.formatHint());
    }

    @Test
    void sourceEqualityFollowsValueContract() {
        assertEquals(source(), source());
        assertEquals(source().hashCode(), source().hashCode());
    }

    @Test
    void pasteOptionsDefaultsAreClaimAwareSkipAirBottomCenter() {
        PasteOptions o = PasteOptions.defaults();
        assertEquals(PasteAnchor.BOTTOM_CENTER, o.anchor());
        assertFalse(o.pasteAir());
        assertTrue(o.claimAware());
    }

    @Test
    void pasteOptionsRejectNullAnchor() {
        assertThrows(NullPointerException.class,
                () -> new PasteOptions(null, false, true));
    }

    @Test
    void pasteResultPlacedOnlyForPasted() {
        assertTrue(PasteResult.PASTED.placed());
        for (PasteResult r : PasteResult.values()) {
            if (r != PasteResult.PASTED) {
                assertFalse(r.placed(), r + " must not count as placed");
            }
        }
    }

    @Test
    void noOpPasterNeverNullNeverSupportsAndReportsUnsupported() {
        SchematicPaster paster = NoOpSchematicPaster.INSTANCE;
        assertSame(NoOpSchematicPaster.INSTANCE, NoOpSchematicPaster.INSTANCE);
        assertFalse(paster.supports(source()));
        CompletableFuture<LoadedSchematic> f = paster.load(source());
        assertNotNull(f);
        assertNull(f.join());
        RTPLocation at = new RTPLocation(null, 0, 64, 0);
        assertEquals(PasteResult.SKIPPED_UNSUPPORTED,
                paster.paste(null, at, PasteOptions.defaults()));
    }

    @Test
    void mockPasterHonoursLoadThenPasteSplit() {
        SchematicPaster paster = new MockSchematicPaster();
        SchematicSource src = source();
        assertTrue(paster.supports(src));
        LoadedSchematic loaded = paster.load(src).join();
        assertNotNull(loaded);
        assertSame(src, loaded.source());
        assertEquals(3, loaded.width());
        assertEquals(1, loaded.height());
        assertEquals(3, loaded.length());
        RTPLocation at = new RTPLocation(null, 10, 70, -4);
        assertEquals(PasteResult.PASTED,
                paster.paste(loaded, at, PasteOptions.defaults()));
    }

    /** Deterministic, file-free test paster (ADR-058 section 6 {@code MockSchematicPaster}). */
    private static final class MockSchematicPaster implements SchematicPaster {
        @Override
        public CompletableFuture<LoadedSchematic> load(SchematicSource source) {
            return CompletableFuture.completedFuture(new LoadedSchematic() {
                @Override public SchematicSource source() { return source; }
                @Override public int width() { return 3; }
                @Override public int height() { return 1; }
                @Override public int length() { return 3; }
            });
        }

        @Override
        public PasteResult paste(LoadedSchematic schematic, RTPLocation at, PasteOptions options) {
            return (schematic != null && at != null) ? PasteResult.PASTED : PasteResult.PASTE_ERROR;
        }

        @Override
        public boolean supports(SchematicSource source) {
            return true;
        }
    }
}
