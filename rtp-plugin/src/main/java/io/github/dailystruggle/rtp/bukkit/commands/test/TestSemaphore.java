package io.github.dailystruggle.rtp.bukkit.commands.test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-caller serialization permit for {@code rtp test *} subcommand
 * dispatch. At most one test may be in flight per caller UUID at any
 * time, preventing intra-caller test overlap (notably between
 * {@code rtp test full} steps and any concurrent standalone
 * {@code rtp test <sub>} invocation issued by the same operator).
 *
 * <p>Granularity is <em>per-caller</em> (decision locked in
 * {@code RTP_TEST_ISOLATION_PLAN.md}): two distinct operators may run
 * tests concurrently, but a single operator (or the console) cannot.
 *
 * <p>Thread-safety: backed by a {@link ConcurrentHashMap}. Acquire and
 * release are CAS-style operations on the per-owner mapping; a
 * mismatched release is a no-op.
 *
 * <p>S-006 note: this class is package-private and only invoked from
 * {@code TestCmd} after the test command tree has been registered, so
 * the &quot;before core load&quot; null-guard required of public
 * {@code rtp-api} entry points does not apply here.
 */
final class TestSemaphore {

  /** Description of the current permit holder. */
  static final class Holder {
    final UUID ownerCallerId;
    final String subName;
    final long acquiredAtNanos;

    Holder(UUID ownerCallerId, String subName) {
      this.ownerCallerId = ownerCallerId;
      this.subName = subName;
      this.acquiredAtNanos = System.nanoTime();
    }
  }

  private static final Map<UUID, Holder> HOLDERS = new ConcurrentHashMap<>();

  private TestSemaphore() {}

  /**
   * Attempts to acquire the per-caller permit. Returns {@code true} if
   * the permit was free and is now held by {@code (callerId, subName)};
   * {@code false} if another in-flight test already owns it for this
   * caller.
   */
  static boolean tryAcquire(UUID callerId, String subName) {
    if (callerId == null) throw new IllegalArgumentException("callerId");
    if (subName == null) throw new IllegalArgumentException("subName");
    Holder candidate = new Holder(callerId, subName);
    return HOLDERS.putIfAbsent(callerId, candidate) == null;
  }

  /**
   * Releases the permit iff it is still held by the expected
   * {@code (owner, subName)} pair. Mismatches (wrong owner, wrong
   * subcommand, or already released) are silent no-ops, matching the
   * cancel-friendly contract used by {@link ActiveTestJobs}.
   *
   * @return {@code true} if the permit was released by this call.
   */
  static boolean release(UUID expectedOwner, String expectedSubName) {
    if (expectedOwner == null) return false;
    Holder current = HOLDERS.get(expectedOwner);
    if (current == null) return false;
    if (expectedSubName != null && !expectedSubName.equals(current.subName)) return false;
    return HOLDERS.remove(expectedOwner, current);
  }

  /**
   * Force-releases the caller's permit regardless of which subcommand
   * holds it. Used by {@link ActiveTestJobs#cancelOwned(UUID)} so a
   * mid-sweep {@code rtp test cancel} frees the permit even when the
   * holder's exact {@code subName} is not known to the canceller.
   *
   * @return {@code true} if a permit was released.
   */
  static boolean releaseOwned(UUID owner) {
    if (owner == null) return false;
    return HOLDERS.remove(owner) != null;
  }

  /** Returns the current holder for {@code callerId}, or {@code null}. */
  static Holder holderOf(UUID callerId) {
    if (callerId == null) return null;
    return HOLDERS.get(callerId);
  }

  /** Test-only: clears every permit. */
  static void clearAllForTesting() {
    HOLDERS.clear();
  }
}
