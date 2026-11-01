package io.github.dailystruggle.rtp.common.commands.test;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Carrier for platform-supplied {@code /rtp test ...} umbrella SPI implementations.
 * Holds {@link TestUmbrellaSender}, {@link TestUmbrellaScheduler}, and an optional audit sink.
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
   * wired one yet (S-006: no silent no-op).
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
