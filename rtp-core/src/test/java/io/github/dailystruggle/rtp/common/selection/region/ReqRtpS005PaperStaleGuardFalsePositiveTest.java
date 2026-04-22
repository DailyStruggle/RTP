package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.TrackedMockWorld;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-S-005 / ADR-015 (Paper chunk-system-v2 follow-up).
 *
 * <p>On Paper chunk-system-v2, {@code World#getChunkAtAsync(cx, cz)} returns a
 * {@code Chunk} reference without a plugin ticket; a just-loaded chunk is
 * eligible for immediate native GC on the scheduler thread that resolved the
 * future. A stale-chunk guard that queries {@code world.isChunkLoaded(cx, cz)}
 * on the server's default TTL therefore reports {@code false} on every
 * candidate and rejects loads that RTP itself just performed — the user-reported
 * symptom was {@code cause=nullChunk reason=staleChunkBeforeVert fails=10} with
 * zero successful generations.
 *
 * <p>This test simulates that mechanic by installing a {@code TrackedMockWorld}
 * whose {@code isChunkLoaded} returns {@code true} only while the
 * force-loaded ticket counter is non-zero — i.e. only while a
 * {@code ChunkReservation} is held. It then runs
 * {@link LocationGenerator#getLocation(Region, Set)} and asserts:
 *
 * <ol>
 *   <li>{@code MockRTPChunk.isSafe} is invoked at least once — proving the
 *       guard did <b>not</b> false-positive the candidate (because the
 *       reservation pinned the chunk across the guard check).</li>
 *   <li>All reservations are released on exit — the active ticket count
 *       returns to zero, confirming {@code finally { reservation.close(); }}
 *       runs on every loop-exit path.</li>
 * </ol>
 */
@DisplayName("REQ-RTP-S-005 / ADR-015: Paper chunk-system-v2 reservation prevents guard false-positive")
public class ReqRtpS005PaperStaleGuardFalsePositiveTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private TrackedMockWorld world;
    private Region region;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        accessor.setLocationGenerator(new LocationGenerator());

        world = new TrackedMockWorld("paper_stale_guard_world");
        accessor.addWorld(world);

        // Paper chunk-system-v2 simulation: the chunk is "loaded" only while
        // RTP holds a force-loaded ticket on it. Without a reservation the
        // server reports every chunk unloaded, matching the user's Paper 26.1
        // reproduction log (`reason=staleChunkBeforeVert fails=10`).
        world.isChunkLoadedPredicate = key -> world.getActiveTicketCount() > 0;

        // Force safetyCheck loop to run.
        @SuppressWarnings("unchecked")
        ConfigParser<SafetyKeys> safety =
                (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
        try {
            java.lang.reflect.Field dataField = FactoryValue.class.getDeclaredField("data");
            dataField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.EnumMap<SafetyKeys, Object> data =
                    (java.util.EnumMap<SafetyKeys, Object>) dataField.get(safety);
            data.put(SafetyKeys.safetyRadius, 1);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to override safetyRadius for test", e);
        }

        Circle circle = new Circle();
        circle.setRng(new Random(42L));
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());

        RegionSettings settings = new RegionSettings(
                "paper_stale_guard_region",
                world,
                circle,
                vert,
                false,
                false,
                10L,
                5,
                0.0,
                1L,
                "",
                false);

        region = new Region("paper_stale_guard_region", settings);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("Paper v2: reservation held across stale-guard => safetyCheck runs; tickets drained on exit")
    void reservation_pins_chunk_across_stale_guard() {
        LocationGenerator.setRng(new Random(42L));

        GenerationResult result = LocationGenerator.getLocation(region, (Set<String>) null);

        // With the ChunkReservation in place (ADR-015 Paper follow-up), the
        // stale-chunk guard tests RTP-owned liveness, not server-default TTL:
        // isChunkLoadedPredicate sees activeTicketCount > 0 and returns true,
        // so vert.adjust + safetyCheck execute. If the fix regressed,
        // isSafeCallCount would remain at zero and we would fall back to the
        // user's reported `fails=N reason=staleChunkBeforeVert` pattern.
        assertTrue(world.isSafeCallCount.get() > 0,
                "ADR-015 Paper follow-up: with a ChunkReservation bracketing the "
                        + "stale-chunk guard, block evaluation (MockRTPChunk.isSafe) must run "
                        + "at least once — the reservation pins the chunk so the guard does "
                        + "not false-positive on Paper chunk-system-v2 semantics.");

        // REQ-RTP-S-002 bounded scope: every reservation opened in the loop
        // must be released in the `finally` on the way out (normal break,
        // continue, or exception). The counter must return to zero.
        assertEquals(0, world.getActiveTicketCount(),
                "REQ-RTP-S-002: every ChunkReservation opened by "
                        + "LocationGenerator.getLocation(Region, Set<String>) must be "
                        + "released before the method returns. Non-zero residual "
                        + "indicates a leak via an exit path that bypasses the finally.");
    }
}
