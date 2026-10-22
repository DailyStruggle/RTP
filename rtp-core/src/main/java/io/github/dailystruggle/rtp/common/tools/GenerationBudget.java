package io.github.dailystruggle.rtp.common.tools;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Count of live chunk loads the server's chunk system is still working on, for hosts
 * whose chunk system serves requests first-come, first-served (vanilla 1.20 / 1.21 on Spigot,
 * Fabric and NeoForge). There, every load RTP queues delays the chunks a joining or moving player
 * needs; a queue deep enough stalls the main thread until the watchdog kills the server.
 *
 * <p>Invariant: a load counts from submission until the server's own chunk future settles, not
 * until RTP's deadline fires. A load RTP stopped waiting for still occupies the chunk workers.
 *
 * <p>{@link #saturated()} tells background work to wait (ADR-087 circuit breaker, exposed through
 * {@code RTPScheduler#isGenerationSaturated}). Loads are never refused here: player-facing loads
 * still go through, they just count toward the budget.
 *
 * <p>{@link #server()} is the process-wide instance: every level of a server shares one chunk
 * worker pool, so one count covers all worlds.
 */
public final class GenerationBudget {
  /** Cap for unprioritised vanilla chunk systems (1.20.x, 1.21.x). */
  public static final int VANILLA_CAP = 2;
  /** Cap for 26.x chunk systems. */
  public static final int MODERN_CAP = 4;
  /** Safety lifetime for an admission reservation whose task is lost before dispatch. */
  public static final long RESERVATION_TTL_MILLIS = 30_000L;

  private static volatile IntSupplier serverCap = () -> VANILLA_CAP;
  private static volatile boolean serverAdmissionEnabled = true;
  private static final GenerationBudget SERVER = new GenerationBudget(() -> serverCap.getAsInt());
  private static final ThreadLocal<Reservation> CURRENT_RESERVATION = new ThreadLocal<>();

  private final IntSupplier cap;
  private final LongSupplier nanoTime;
  private final long reservationTtlNanos;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicLong resetEpoch = new AtomicLong();
  private final Set<Reservation> reservations = ConcurrentHashMap.newKeySet();

  /** The server-wide budget platform world adapters count their live loads in. */
  public static GenerationBudget server() {
    return SERVER;
  }

  /**
   * Sets how the server-wide cap is resolved; read on every check, so a platform can pass a
   * supplier that consults a version adapter installed later. {@code null} restores the default.
   */
  public static void configureServerCap(IntSupplier cap) {
    serverCap = cap == null ? () -> VANILLA_CAP : cap;
  }

  /** Sets whether background admission reservations affect the server-wide budget. */
  public static void configureServerAdmission(boolean enabled) {
    serverAdmissionEnabled = enabled;
  }

  /** @param cap loads in flight at which background work waits; {@code <= 0} never saturates */
  public GenerationBudget(int cap) {
    this(() -> cap);
  }

  /**
   * @param cap read on every check, so a world created before its version adapter is installed
   *     picks up the right cap later
   */
  public GenerationBudget(IntSupplier cap) {
    this(cap, System::nanoTime, TimeUnit.MILLISECONDS.toNanos(RESERVATION_TTL_MILLIS));
  }

  GenerationBudget(IntSupplier cap, LongSupplier nanoTime, long reservationTtlNanos) {
    this.cap = cap;
    this.nanoTime = nanoTime;
    this.reservationTtlNanos = Math.max(1L, reservationTtlNanos);
  }

  /** {@link #MODERN_CAP} for 26.x versions, {@link #VANILLA_CAP} otherwise (including unknown). */
  public static int capFor(String mcVersion) {
    if (mcVersion != null && mcVersion.trim().startsWith("26.")) return MODERN_CAP;
    return VANILLA_CAP;
  }

  /**
   * Counts one load as submitted. The returned release is idempotent; call it when the server's
   * chunk future settles (or when the submission itself failed).
   */
  public Runnable begin() {
    inFlight.incrementAndGet();
    return releaseHandle();
  }

  /**
   * Converts an admission reservation into a live-load count without increasing the total.
   * If the reservation expired, was already consumed, or belongs to another budget, the load
   * receives a normal count instead.
   */
  public Runnable begin(Reservation reservation) {
    if (reservation != null && reservation.owner == this && reservation.consume()) {
      return releaseHandle();
    }
    return begin();
  }

  private Runnable releaseHandle() {
    AtomicBoolean released = new AtomicBoolean(false);
    long epoch = resetEpoch.get();
    return () -> {
      if (released.compareAndSet(false, true) && resetEpoch.get() == epoch) inFlight.decrementAndGet();
    };
  }

  /**
   * Reserves a generation slot before an asynchronous probe can reach the native loader. Returns
   * {@code null} when the cap is full; callers must defer the work and preserve its queue entry.
   */
  public Reservation reserve() {
    if (this == SERVER && !serverAdmissionEnabled) return Reservation.untracked(this);
    expireReservations();
    int limit = cap.getAsInt();
    for (;;) {
      int current = inFlight.get();
      if (limit > 0 && current >= limit) return null;
      if (inFlight.compareAndSet(current, current + 1)) {
        Reservation reservation = new Reservation(
            this, nanoTime.getAsLong() + reservationTtlNanos, resetEpoch.get());
        reservations.add(reservation);
        return reservation;
      }
      limit = cap.getAsInt();
    }
  }

  /** Current reservation inherited by a platform world's load call on this thread. */
  public static Reservation currentReservation() {
    return CURRENT_RESERVATION.get();
  }

  /** Establishes reservation context around a synchronous platform entry point. */
  public static <T> T withReservation(Reservation reservation, Supplier<T> action) {
    Reservation previous = CURRENT_RESERVATION.get();
    if (reservation == null) CURRENT_RESERVATION.remove();
    else CURRENT_RESERVATION.set(reservation);
    try {
      return action.get();
    } finally {
      if (previous == null) CURRENT_RESERVATION.remove();
      else CURRENT_RESERVATION.set(previous);
    }
  }

  /** Reservation held from admission until a native load consumes it or the owner releases it. */
  public static final class Reservation implements AutoCloseable {
    private static final int RESERVED = 0;
    private static final int CONSUMED = 1;
    private static final int RELEASED = 2;

    private final GenerationBudget owner;
    private final long expiresAtNanos;
    private final long epoch;
    private final boolean counted;
    private final AtomicInteger state;

    private Reservation(GenerationBudget owner, long expiresAtNanos, long epoch) {
      this.owner = owner;
      this.expiresAtNanos = expiresAtNanos;
      this.epoch = epoch;
      this.counted = true;
      this.state = new AtomicInteger(RESERVED);
    }

    private Reservation(GenerationBudget owner, boolean counted) {
      this.owner = owner;
      this.expiresAtNanos = 0L;
      this.epoch = owner.resetEpoch.get();
      this.counted = counted;
      this.state = new AtomicInteger(RELEASED);
    }

    private static Reservation untracked(GenerationBudget owner) {
      return new Reservation(owner, false);
    }

    private boolean consume() {
      if (!counted) return false;
      expireIfNeeded(owner.nanoTime.getAsLong());
      if (!state.compareAndSet(RESERVED, CONSUMED)) return false;
      owner.reservations.remove(this);
      return true;
    }

    private void expireIfNeeded(long nowNanos) {
      if (counted && nowNanos - expiresAtNanos >= 0L
          && state.compareAndSet(RESERVED, RELEASED)) {
        owner.reservations.remove(this);
        if (owner.resetEpoch.get() == epoch) owner.inFlight.decrementAndGet();
      }
    }

    @Override
    public void close() {
      if (counted && state.compareAndSet(RESERVED, RELEASED)) {
        owner.reservations.remove(this);
        if (owner.resetEpoch.get() == epoch) owner.inFlight.decrementAndGet();
      }
    }
  }

  /**
   * Releases {@code release} when {@code serverFuture} settles; a {@code null} future (nothing
   * was submitted) releases at once. Returns {@code serverFuture}.
   */
  public static <T> CompletableFuture<T> releaseOn(CompletableFuture<T> serverFuture, Runnable release) {
    if (serverFuture == null) {
      release.run();
      return null;
    }
    serverFuture.whenComplete((v, ex) -> release.run());
    return serverFuture;
  }

  public int inFlight() {
    expireReservations();
    return inFlight.get();
  }

  public int cap() {
    return cap.getAsInt();
  }

  /** Whether background loads should wait until in-flight loads drain below the cap. */
  public boolean saturated() {
    expireReservations();
    int c = cap.getAsInt();
    return c > 0 && inFlight.get() >= c;
  }

  /** Resets counts and outstanding reservations between isolated tests. */
  public void resetForTesting() {
    resetEpoch.incrementAndGet();
    for (Reservation reservation : reservations) reservation.state.set(Reservation.RELEASED);
    reservations.clear();
    inFlight.set(0);
  }

  private void expireReservations() {
    long now = nanoTime.getAsLong();
    for (Reservation reservation : reservations) reservation.expireIfNeeded(now);
  }
}
