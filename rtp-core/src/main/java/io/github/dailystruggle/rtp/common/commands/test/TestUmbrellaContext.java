package io.github.dailystruggle.rtp.common.commands.test;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Carrier for the platform-supplied {@code /rtp test ...} umbrella SPI
 * implementations. Holds the {@link TestUmbrellaSender}, the
 * {@link TestUmbrellaScheduler}, and an optional audit-log consumer that
 * receives every audit line the umbrella emits (mirrors REQ-RTP-S-004
 * fidelity used by {@code rtp test full}).
 *
 * <p>Wired statically on {@code RTP.testUmbrellaContext} by each platform
 * plugin at startup (Phase 1.4 / 1.5 / 3.3 of
 * {@code docs/dev/scratch/CHECKLIST-fabric-rtp-test-full.md}). Callers
 * inside {@code rtp-core} obtain the active context via
 * {@link #require()} which enforces S-006 by throwing
 * {@link IllegalStateException} when the umbrella is invoked before core
 * load completes &mdash; never a silent null-return.
 *
 * <p>This class is intentionally immutable and trivially constructible so
 * that test harnesses can install a stub via
 * {@code RTP.testUmbrellaContext = new TestUmbrellaContext(stubSender,
 * stubScheduler, line -> {})} without reflection.
 */
public final class TestUmbrellaContext {

  private final TestUmbrellaSender sender;
  private final TestUmbrellaScheduler scheduler;
  private final Consumer<String> auditSink;

  /**
   * @param sender    non-{@code null} platform sender.
   * @param scheduler non-{@code null} platform scheduler.
   * @param auditSink optional consumer for audit lines; may be
   *                  {@code null} (interpreted as no audit tap).
   */
  public TestUmbrellaContext(TestUmbrellaSender sender,
                             TestUmbrellaScheduler scheduler,
                             Consumer<String> auditSink) {
    this.sender = Objects.requireNonNull(sender, "sender");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.auditSink = auditSink;
  }

  /** @return the installed platform sender; never {@code null}. */
  public TestUmbrellaSender sender() {
    return sender;
  }

  /** @return the installed platform scheduler; never {@code null}. */
  public TestUmbrellaScheduler scheduler() {
    return scheduler;
  }

  /**
   * @return the audit-line consumer if one was installed, otherwise a
   *         no-op consumer. Never {@code null} so callers can chain
   *         {@code context.auditSink().accept(line)} unguarded.
   */
  public Consumer<String> auditSink() {
    return auditSink != null ? auditSink : s -> { };
  }

  /**
   * Returns the active context from {@code RTP.testUmbrellaContext},
   * throwing {@link IllegalStateException} when no platform adapter has
   * wired one yet. Used by leaves moved into {@code rtp-core} during
   * Phase 2 to honor S-006 (no silent no-op).
   *
   * @return the non-{@code null} active context.
   * @throws IllegalStateException if no context has been installed.
   */
  public static TestUmbrellaContext require() {
    TestUmbrellaContext ctx = io.github.dailystruggle.rtp.common.RTP.testUmbrellaContext;
    if (ctx == null) {
      throw new IllegalStateException(
          "TestUmbrellaContext not installed; the platform plugin must"
              + " populate RTP.testUmbrellaContext before /rtp test ... is invoked.");
    }
    return ctx;
  }
}
