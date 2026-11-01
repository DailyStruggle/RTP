package io.github.dailystruggle.rtp.common.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RTP-80: ticket generation watch bounds ticket-driven terrain generation")
class TicketGenerationWatchTest {

  @Test
  void countsOnePendingTicketUntilItsFootprintIsResident() {
    GenerationBudget budget = new GenerationBudget(4);
    AtomicBoolean resident = new AtomicBoolean();
    TicketGenerationWatch watch = new TicketGenerationWatch(budget, resident::get);

    assertEquals(1, budget.inFlight());
    assertTrue(watch.poll());
    assertEquals(1, budget.inFlight());

    resident.set(true);
    assertFalse(watch.poll());
    assertEquals(0, budget.inFlight());
    assertFalse(watch.poll());
  }

  @Test
  void cancelReleasesOnce() {
    GenerationBudget budget = new GenerationBudget(4);
    TicketGenerationWatch watch = new TicketGenerationWatch(budget, () -> false);

    watch.close();
    watch.close();

    assertEquals(0, budget.inFlight());
    assertFalse(watch.poll());
  }

  @Test
  void ttlReleasesAStalledFootprint() {
    GenerationBudget budget = new GenerationBudget(4);
    AtomicLong now = new AtomicLong();
    TicketGenerationWatch watch =
        new TicketGenerationWatch(budget, () -> false, now::get, 10L);

    assertTrue(watch.poll());
    now.set(10L);
    assertFalse(watch.poll());
    assertEquals(0, budget.inFlight());
  }

  @Test
  void alreadyResidentFootprintDoesNotConsumeBudget() {
    GenerationBudget budget = new GenerationBudget(4);
    TicketGenerationWatch watch = new TicketGenerationWatch(budget, () -> true);

    assertEquals(0, budget.inFlight());
    assertFalse(watch.isActive());
    assertFalse(watch.poll());
  }
}
