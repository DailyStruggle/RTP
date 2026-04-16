package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.TrackedMockWorld;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@code addPluginChunkTicket} and {@code removePluginChunkTicket}
 * are perfectly paired throughout the teleport pipeline, leaving zero orphaned
 * tickets after each operation.
 *
 * <p>A {@link TrackedMockWorld} intercepts every {@code setForceLoadedImpl} call
 * and maintains a live counter of outstanding tickets.  Tests assert that the
 * counter is {@code > 0} while a reservation is held and exactly {@code 0} after
 * it is released — both in the happy path and when an exception is thrown
 * mid-validation.
 *
 * <p>Requirements covered: REQ-SPIGOT-ARCH-001, REQ-SPIGOT-ARCH-005,
 * REQ-PAPER-ARCH-001, REQ-PAPER-ARCH-005.
 */
public class ChunkTicketLifecycleTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private TrackedMockWorld world;
    private Region region;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        accessor.setLocationGenerator(new LocationGenerator());

        world = new TrackedMockWorld("ticket_lifecycle_world");
        accessor.addWorld(world);

        Circle circle = new Circle();
        circle.setRng(new Random(42L));
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());

        RegionSettings settings = new RegionSettings(
                "ticket_lifecycle_region",
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

        region = new Region("ticket_lifecycle_region", settings);
    }

    // -----------------------------------------------------------------------
    // Test 1 – happy path: ticket is held during reservation, released on close
    // -----------------------------------------------------------------------

    /**
     * Asserts that:
     * <ol>
     *   <li>While a {@link ChunkReservation} is open the active ticket count is {@code > 0},
     *       proving the ticket was actually requested from the world.</li>
     *   <li>After {@link ChunkReservation#close()} the active ticket count drops back to
     *       exactly {@code 0}, proving no orphaned tickets remain.</li>
     * </ol>
     *
     * <p>The pipeline is exercised end-to-end: {@link LocationGenerator#getLocation} finds a
     * valid location, then a {@link ChunkReservation} is opened for that chunk to simulate
     * the force-load ticket that the real teleport pipeline holds during validation.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void ticket_is_held_during_reservation_and_released_on_close() throws ExecutionException, InterruptedException {
        LocationGenerator.setRng(new Random(42L));

        // Run the full pipeline to obtain a valid location
        GenerationResult result = LocationGenerator.getLocation(region, (Set<String>) null);
        assertNotNull(result, "GenerationResult must not be null");
        assertNotNull(result.coords(), "Pipeline must find a valid location with TrackedMockWorld");

        // Derive the chunk coordinates from the result and open a reservation,
        // mirroring what the real teleport pipeline does during validation.
        int cx = result.coords().x() >> 4;
        int cz = result.coords().z() >> 4;
        ChunkSet chunkSet = world.getChunkAtAsync(cx, cz).get();
        assertNotNull(chunkSet, "ChunkSet must be available from TrackedMockWorld");

        assertEquals(0, world.getActiveTicketCount(),
                "Precondition: no tickets should be active before opening the reservation");

        ChunkReservation reservation = new ChunkReservation(chunkSet, world);

        // Ticket must be active while the reservation is open
        assertTrue(world.getActiveTicketCount() > 0,
                "Active ticket count must be > 0 while ChunkReservation is held");

        // Release the reservation — ticket must be freed
        reservation.close();

        assertEquals(0, world.getActiveTicketCount(),
                "Active ticket count must return to 0 after ChunkReservation.close()");
    }

    // -----------------------------------------------------------------------
    // Test 2 – try-with-resources: ticket is released even when exception thrown
    // -----------------------------------------------------------------------

    /**
     * Simulates a pipeline failure (a {@link RuntimeException} thrown inside a
     * custom validator) and asserts that the {@link ChunkReservation}
     * try-with-resources block still releases the ticket, leaving the active
     * ticket count at exactly {@code 0}.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void ticket_is_released_even_when_validator_throws() throws ExecutionException, InterruptedException {
        // Obtain a ChunkSet directly so we can construct a reservation manually,
        // mirroring what LocationGenerator does internally.
        int cx = 0;
        int cz = 0;
        ChunkSet chunkSet = world.getChunkAtAsync(cx, cz).get();

        assertNotNull(chunkSet, "ChunkSet must be available from TrackedMockWorld");

        // Ticket count before any reservation
        assertEquals(0, world.getActiveTicketCount(),
                "Precondition: no tickets should be active before the test");

        RuntimeException simulatedFailure = new RuntimeException("Simulated validator failure");
        RuntimeException caught = null;

        try (ChunkReservation reservation = new ChunkReservation(chunkSet, world)) {
            // Ticket must be active inside the try block
            assertTrue(world.getActiveTicketCount() > 0,
                    "Active ticket count must be > 0 inside the try-with-resources block");

            // Simulate a validator throwing an exception mid-pipeline
            throw simulatedFailure;

        } catch (RuntimeException e) {
            caught = e;
            // The finally / AutoCloseable.close() must have already run at this point
        }

        // Verify the exception was the one we threw (not a test infrastructure issue)
        assertSame(simulatedFailure, caught, "The caught exception must be the simulated validator failure");

        // Ticket must be released despite the exception
        assertEquals(0, world.getActiveTicketCount(),
                "Active ticket count must return to 0 after ChunkReservation.close() via try-with-resources, even when an exception was thrown");
    }
}
