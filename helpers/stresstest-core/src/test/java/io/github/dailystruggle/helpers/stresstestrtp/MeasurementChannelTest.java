package io.github.dailystruggle.helpers.stresstestrtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeasurementChannelTest {

    private static Attempt attempt(String label) {
        return new Attempt(UUID.randomUUID(), "bot", "world", label,
                System.currentTimeMillis(), 0, 0, 20.0, 50.0, 512);
    }

    /** Rows on disk after forcing the recorder's buffered writer out. */
    private static List<String> rows(MetricsRecorder rec, Path csv) throws Exception {
        rec.flushRows();
        List<String> lines = Files.readAllLines(csv);
        return lines.subList(1, lines.size()); // drop header
    }

    @Test
    @DisplayName("landing classifier: fixed verdicts for the block column")
    void landingVerdicts() {
        assertEquals(LandingInspector.Verdict.SAFE, LandingInspector.classify(
                "STONE", "AIR", "AIR", true, true));
        assertEquals(LandingInspector.Verdict.LAVA, LandingInspector.classify(
                "STONE", "LAVA", "AIR", true, true));
        assertEquals(LandingInspector.Verdict.WATER, LandingInspector.classify(
                "SAND", "WATER", "AIR", true, true));
        assertEquals(LandingInspector.Verdict.SUFFOCATING, LandingInspector.classify(
                "STONE", "AIR", "STONE", true, false));
        assertEquals(LandingInspector.Verdict.NO_FLOOR, LandingInspector.classify(
                "AIR", "AIR", "AIR", true, true));
        assertEquals(LandingInspector.Verdict.HAZARD, LandingInspector.classify(
                "MAGMA_BLOCK", "AIR", "AIR", true, true));
    }

    @Test
    @DisplayName("Lily pads, carpets, snow and scaffolding are standable; thin feet blocks do not suffocate")
    void thinAndTopFaceBlocksAreStandable() {
        assertEquals(LandingInspector.Verdict.SAFE, LandingInspector.classify(
                "LILY_PAD", "AIR", "AIR", true, true));
        assertEquals(LandingInspector.Verdict.SAFE, LandingInspector.classify(
                "SNOW", "AIR", "AIR", true, true));
        assertEquals(LandingInspector.Verdict.SAFE, LandingInspector.classify(
                "SCAFFOLDING", "AIR", "AIR", true, true));
        assertEquals(LandingInspector.Verdict.SAFE, LandingInspector.classify(
                "MANGROVE_ROOTS", "MOSS_CARPET", "AIR", false, true));
        assertEquals(LandingInspector.Verdict.SAFE, LandingInspector.classify(
                "GRASS_BLOCK", "WHITE_CARPET", "AIR", false, true));
        assertEquals(LandingInspector.Verdict.SAFE, LandingInspector.classify(
                "WATER", "LILY_PAD", "AIR", false, true));
        assertEquals(LandingInspector.Verdict.SUFFOCATING, LandingInspector.classify(
                "STONE", "MOSS_CARPET", "STONE", false, false));
        assertEquals(LandingInspector.Verdict.SUFFOCATING, LandingInspector.classify(
                "STONE", "MANGROVE_ROOTS", "AIR", false, true));
    }

    @Test
    @DisplayName("Folia CPU scope matches region scheduler thread names only")
    void regionThreadNames() {
        assertTrue(CpuSampler.isRegionThreadName("Folia Region Scheduler Thread #3"));
        assertFalse(CpuSampler.isRegionThreadName("Server thread"));
        assertFalse(CpuSampler.isRegionThreadName("Worker-Main-2"));
        assertFalse(CpuSampler.isRegionThreadName(null));
    }

    @Test
    @DisplayName("LeafRTP Post counts as success only when the player is at the destination")
    void arrivalCheck() {
        assertEquals("", DirectTeleportProbe.arrivalFailure(
                new StressLocation("world", 100.5, 70, 200.5), "world", 100, 200));
        assertEquals("NOT_AT_DESTINATION", DirectTeleportProbe.arrivalFailure(
                new StressLocation("world", 3.5, 70, -8.5), "world", 100, 200));
        assertEquals("NOT_AT_DESTINATION", DirectTeleportProbe.arrivalFailure(
                new StressLocation("world_nether", 100.5, 70, 200.5), "world", 100, 200));
        assertEquals("NOT_AT_DESTINATION", DirectTeleportProbe.arrivalFailure(null, "world", 100, 200));
    }

    @Test
    @DisplayName("held direct row is written once the external sighting at its destination arrives")
    void heldRowReleasedByMatchingSighting() throws Exception {
        Path csv = Files.createTempFile("stresstest-channel-", ".csv");
        csv.toFile().deleteOnExit();
        MetricsRecorder rec = new MetricsRecorder(csv);
        TeleportProbe probe = new TeleportProbe(null, rec);
        UUID player = UUID.randomUUID();

        Attempt a = attempt("rtp");
        rec.onDispatch(a);
        probe.expect(a, player);
        assertTrue(probe.attributeDirect(player, 1000, -2000, true, "", null));
        assertEquals(1, rec.successCount());
        assertTrue(rec.isDeferred(a));
        assertEquals(0, rows(rec, csv).size());

        // A sighting somewhere else belongs to another teleport.
        assertFalse(probe.noteExternal(player, new StressLocation("world", 50, 64, 50)));
        assertTrue(rec.isDeferred(a));

        assertTrue(probe.noteExternal(player, new StressLocation("world", 1000.5, 64, -1999.5)));
        assertFalse(rec.isDeferred(a));
        assertTrue(a.externalSeenEpochMs > 0);
        List<String> written = rows(rec, csv);
        assertEquals(1, written.size());
        assertTrue(written.get(0).contains("PLUGIN_EVENT"));
    }

    @Test
    @DisplayName("failed direct row is written at once; competitor rows carry the shared latency")
    void failedAndCompetitorRows() throws Exception {
        Path csv = Files.createTempFile("stresstest-channel-", ".csv");
        csv.toFile().deleteOnExit();
        MetricsRecorder rec = new MetricsRecorder(csv);
        TeleportProbe probe = new TeleportProbe(null, rec);

        UUID p1 = UUID.randomUUID();
        Attempt failed = attempt("rtp");
        rec.onDispatch(failed);
        probe.expect(failed, p1);
        probe.attributeDirect(p1, 10, 10, false, "NOT_AT_DESTINATION", null);
        assertFalse(rec.isDeferred(failed));
        assertEquals(0, rec.successCount());
        assertTrue(rows(rec, csv).get(0).contains("NOT_AT_DESTINATION"));

        UUID p2 = UUID.randomUUID();
        Attempt competitor = attempt("betterrtp");
        rec.onDispatch(competitor);
        probe.expect(competitor, p2);
        probe.noteExternal(p2, new StressLocation("world", 5, 64, 5));
        assertTrue(competitor.externalSeenEpochMs > 0);
        assertTrue(competitor.externalLatencyMs() >= 0);
    }

    @Test
    @DisplayName("held rows are flushed when the wait ends without a sighting")
    void heldRowFlushedWithoutSighting() throws Exception {
        Path csv = Files.createTempFile("stresstest-channel-", ".csv");
        csv.toFile().deleteOnExit();
        MetricsRecorder rec = new MetricsRecorder(csv);
        Attempt a = attempt("rtp");
        rec.onDispatch(a);
        rec.onComplete(a, true, "", 1, 1, AttributionSource.PLUGIN_EVENT, true);
        rec.flushDeferred(false);
        assertTrue(rec.isDeferred(a)); // still inside the wait
        rec.flushDeferred(true);
        assertFalse(rec.isDeferred(a));
        String row = rows(rec, csv).get(0);
        // external_latency_ms is the first column after region_tps_5s_at_dispatch.
        String[] header = MetricsRecorder.CSV_HEADER.split(",");
        int col = List.of(header).indexOf("external_latency_ms");
        assertEquals("-1", row.split(",", -1)[col]);
    }
}
