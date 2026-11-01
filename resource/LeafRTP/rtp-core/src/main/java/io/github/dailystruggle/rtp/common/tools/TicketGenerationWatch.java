package io.github.dailystruggle.rtp.common.tools;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Tracks one ticket's outstanding generation footprint without loading any chunks. */
public final class TicketGenerationWatch implements AutoCloseable {
  private final BooleanSupplier footprintResident;
  private final LongSupplier nanoTime;
  private final long expiresAtNanos;
  private final Runnable release;
  private final AtomicBoolean closed = new AtomicBoolean();

  public TicketGenerationWatch(GenerationBudget budget, BooleanSupplier footprintResident) {
    this(
        budget,
        footprintResident,
        System::nanoTime,
        TimeUnit.MILLISECONDS.toNanos(GenerationBudget.RESERVATION_TTL_MILLIS));
  }

  TicketGenerationWatch(
      GenerationBudget budget,
      BooleanSupplier footprintResident,
      LongSupplier nanoTime,
      long ttlNanos) {
    Objects.requireNonNull(budget, "budget");
    this.footprintResident = Objects.requireNonNull(footprintResident, "footprintResident");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    this.expiresAtNanos = nanoTime.getAsLong() + Math.max(1L, ttlNanos);
    if (footprintResident.getAsBoolean()) {
      closed.set(true);
      release = () -> {};
    } else {
      release = budget.begin();
    }
  }

  /** Returns true while the budget slot is held and polling should continue. */
  public boolean poll() {
    if (closed.get()) return false;
    if (nanoTime.getAsLong() - expiresAtNanos >= 0L) {
      close();
      return false;
    }
    try {
      if (footprintResident.getAsBoolean()) {
        close();
        return false;
      }
    } catch (RuntimeException | Error failure) {
      close();
      throw failure;
    }
    return !closed.get();
  }

  public boolean isActive() {
    return !closed.get();
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) release.run();
  }
}
