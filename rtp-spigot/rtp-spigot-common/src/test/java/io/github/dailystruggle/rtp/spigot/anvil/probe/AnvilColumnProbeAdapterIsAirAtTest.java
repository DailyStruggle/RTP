package io.github.dailystruggle.rtp.spigot.anvil.probe;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import io.github.dailystruggle.rtp.anvil.BiomePaletteSection;
import io.github.dailystruggle.rtp.anvil.ColumnProbe;
import io.github.dailystruggle.rtp.anvil.PaletteSection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression test for {@link AnvilColumnProbeAdapter#isAirAt(int)}.
 *
 * <p>Captures the April 2026 ScanTask regression where {@code adjustNull == activeChecks}
 * across every gauge tick (see {@code docs/architecture/05-scan-task-crawler.md}) with
 * {@code requireSkyLight: false}. The root cause: the adapter runs every palette identifier
 * through {@link io.github.dailystruggle.rtp.spigot.anvil.PaletteNormalizer#reconcile},
 * which converts {@code "minecraft:air"} to {@code "AIR"} via {@code Material.matchMaterial}.
 * The default {@code ChunkColumnProbe.isAirAt} implementation in {@code rtp-api} then
 * did a case-sensitive comparison against lowercase {@code "air"} and reported every air
 * block as non-air, causing {@code JumpAdjustor.acceptProbeY} / {@code LinearAdjustor.acceptY}
 * to find no acceptable Y on any center column and route 100% of probes back through the
 * full-load path.</p>
 *
 * <p>The adapter now overrides {@code isAirAt} to recognize reconciled air forms
 * ({@code AIR}, {@code CAVE_AIR}, {@code VOID_AIR}) in addition to the raw
 * {@code minecraft:*} lowercase path segments the default implementation handles.</p>
 */
@DisplayName("AnvilColumnProbeAdapter.isAirAt — reconciled air regression")
class AnvilColumnProbeAdapterIsAirAtTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * Build a probe whose center column alternates air and stone across a single
     * section (section Y = 0, covering world-Y 0..15).
     */
    private static ColumnProbe buildAirStoneProbe() {
        // Section 0: single-entry palette "minecraft:air" (vanilla omits the data array
        // for single-entry palettes; blockIdAt short-circuits to palette[0]).
        PaletteSection airSection =
                new PaletteSection(0, Collections.singletonList("minecraft:air"), null);
        // Section 1: single-entry palette "minecraft:stone" covering world-Y 16..31.
        PaletteSection stoneSection =
                new PaletteSection(1, Collections.singletonList("minecraft:stone"), null);

        List<PaletteSection> sections = new ArrayList<>(2);
        sections.add(airSection);
        sections.add(stoneSection);

        return new ColumnProbe(
                /* minY = */ 0,
                /* maxY = */ 31,
                /* heightmapTopY = */ Integer.MIN_VALUE,
                sections,
                /* biomeSections = */ Collections.<BiomePaletteSection>emptyList(),
                /* isLightOn = */ true);
    }

    @Test
    @DisplayName("reconciled \"AIR\" is recognized as air")
    void airSectionReportsAir() {
        ColumnProbe probe = buildAirStoneProbe();
        AnvilColumnProbeAdapter adapter = new AnvilColumnProbeAdapter(probe, 0, 0);

        // Sanity: blockAt returns the reconciled upper-case form.
        String raw = adapter.blockAt(5);
        assertTrue("AIR".equals(raw),
                "expected reconciled \"AIR\", got \"" + raw + "\"");

        // The bug: default ChunkColumnProbe.isAirAt returned false here. With the
        // override, every Y in section 0 is correctly reported as air.
        for (int y = 0; y <= 15; y++) {
            assertTrue(adapter.isAirAt(y),
                    "expected isAirAt(" + y + ")=true for reconciled AIR");
        }
    }

    @Test
    @DisplayName("reconciled \"STONE\" is not misreported as air")
    void stoneSectionReportsNonAir() {
        ColumnProbe probe = buildAirStoneProbe();
        AnvilColumnProbeAdapter adapter = new AnvilColumnProbeAdapter(probe, 0, 0);

        for (int y = 16; y <= 31; y++) {
            assertFalse(adapter.isAirAt(y),
                    "expected isAirAt(" + y + ")=false for reconciled STONE");
        }
    }

    @Test
    @DisplayName("out-of-window Y (null blockAt) is not air")
    void outOfWindowIsNotAir() {
        ColumnProbe probe = buildAirStoneProbe();
        AnvilColumnProbeAdapter adapter = new AnvilColumnProbeAdapter(probe, 0, 0);

        assertFalse(adapter.isAirAt(-1));
        assertFalse(adapter.isAirAt(100));
    }
}
