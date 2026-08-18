package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.rtp.common.commands.test.TestCancelCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSchedulerCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestConfigSetCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestApiCompatCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestChunkTicketCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestAnvilPrefilterCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSemaphore;
import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.commands.test.TestCancelCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSchedulerCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestConfigSetCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestApiCompatCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestChunkTicketCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestAnvilPrefilterCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSemaphore;
import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.test.TestCancelCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSchedulerCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestConfigSetCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestApiCompatCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestChunkTicketCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestAnvilPrefilterCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSemaphore;
import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.test.TestCancelCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSchedulerCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestConfigSetCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestApiCompatCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestChunkTicketCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestAnvilPrefilterCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSemaphore;
import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.test.TestCancelCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSchedulerCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestConfigSetCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestApiCompatCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestChunkTicketCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestAnvilPrefilterCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSemaphore;
import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import java.util.List;
import io.github.dailystruggle.rtp.common.commands.test.TestCancelCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSchedulerCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestConfigSetCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestApiCompatCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestChunkTicketCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestAnvilPrefilterCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSemaphore;
import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import java.util.Map;
import io.github.dailystruggle.rtp.common.commands.test.TestCancelCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSchedulerCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestConfigSetCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestApiCompatCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestChunkTicketCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestAnvilPrefilterCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSemaphore;
import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import java.util.UUID;
import io.github.dailystruggle.rtp.common.commands.test.TestCancelCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSchedulerCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestConfigSetCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestApiCompatCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestChunkTicketCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestAnvilPrefilterCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSemaphore;
import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import java.util.concurrent.CompletableFuture;
import io.github.dailystruggle.rtp.common.commands.test.TestCancelCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSchedulerCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestConfigSetCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestApiCompatCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestChunkTicketCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestAnvilPrefilterCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSemaphore;
import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import java.util.function.Consumer;
import io.github.dailystruggle.rtp.common.commands.test.TestCancelCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSchedulerCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestConfigSetCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestApiCompatCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestChunkTicketCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestAnvilPrefilterCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSemaphore;
import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import java.util.function.Predicate;
import io.github.dailystruggle.rtp.common.commands.test.TestCancelCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSchedulerCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestConfigSetCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestApiCompatCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestChunkTicketCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestAnvilPrefilterCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSemaphore;
import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import java.util.logging.Level;
import io.github.dailystruggle.rtp.common.commands.test.TestCancelCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSchedulerCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestConfigSetCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestApiCompatCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestChunkTicketCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestAnvilPrefilterCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSemaphore;
import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import org.jetbrains.annotations.NotNull;
import io.github.dailystruggle.rtp.common.commands.test.TestCancelCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSchedulerCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestConfigSetCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestApiCompatCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestChunkTicketCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestAnvilPrefilterCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSemaphore;
import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import org.jetbrains.annotations.Nullable;

/**
 * Parent node for the {@code rtp test …} runtime test suite.
 *
 * <p>See {@code docs/dev/RUNTIME_TEST_SUITE_PLAN.md} for the full design,
 * roadmap, and requirements traceability.
 *
 * <p>Lives in {@code rtp-plugin} (not {@code rtp-core}) because several
 * subcommands depend on {@code org.bukkit.*} or {@code rtp-spigot} symbols.
 * Per architecture rules, {@code rtp-core} must remain platform-agnostic.
 *
 * <p><b>Cross-platform constructor split.</b> The constructor registers only
 * the subcommands that are safe to class-load on every platform that ships
 * {@code rtp-plugin} - currently Bukkit and Fabric (the JAR is multi-loader
 * per ADR-022 §2). Subcommands that hard-import Bukkit / Spigot types
 * ({@link TestStressCmd}, {@link TestChunkProbePerfCmd}, {@link TestFullCmd},
 * {@link AsyncReplyTestJob}) are registered by the {@link BukkitTestCmd}
 * subclass instead, so loading {@code TestCmd} on Fabric does not trigger
 * a {@code NoClassDefFoundError} on {@code org.bukkit.*} /
 * {@code commandsapi.bukkit.*} / {@code rtp.bukkitplatform.*}.
 *
 * <p>Bukkit callers MUST instantiate {@link BukkitTestCmd}; Fabric callers
 * (e.g. {@code RTPFabricMod}) instantiate this class directly.
 */
public class TestCmd extends BaseRTPCmdImpl {

  public TestCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    // --- Platform-neutral subcommands (safe to class-load on Bukkit + Fabric). ---
    addSubCommand(new TestCancelCmd(this));
    addSubCommand(new TestSchedulerCmd(this));
    addSubCommand(new TestReloadSafetyCmd(this));
    addSubCommand(new TestConfigSetCmd(this));
    addSubCommand(new TestCommandsCmd(this));
    addSubCommand(new TestApiCompatCmd(this));
    addSubCommand(new TestChunkTicketCmd(this));
    addSubCommand(new TestDisconnectMidflightCmd(this));
    addSubCommand(new TestAnvilPrefilterCmd(this));
    addSubCommand(new TestBiomeSourceCmd(this));
    addSubCommand(new TestAsyncChunkLoadCmd(this));
    addSubCommand(new QueueStarvationTestJob(this));
    addSubCommand(new EconomyIsolationTestJob(this));
    addSubCommand(new FoliaOwnershipTestJob(this));
    addSubCommand(new DisconnectTestJob(this));
    addSubCommand(new SafetyVerifierTestJob(this));
    addSubCommand(new NetworkSimulationTestJob(this));

    // Bukkit-bound subcommands and the `full`/`all` umbrella are registered
    // by BukkitTestCmd (see its Javadoc) so this class stays class-load-safe
    // on Fabric. `full` references SendMessage (rtp-spigot), so even the
    // umbrella has to stay Bukkit-only for now - Fabric users still get every
    // platform-neutral subcommand individually.
    registerPlatformSpecificChildren();
  }

  /**
   * Hook for platform-specific subclasses to register subcommands whose
   * class-loading would fail on the current platform. Default implementation
   * is a no-op (Fabric path); {@link BukkitTestCmd} overrides it.
   */
  protected void registerPlatformSpecificChildren() {
    // no-op by default - Fabric and any other non-Bukkit platform.
  }

  @Override
  public String name() {
    return "test";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "runtime self-test suite for operators";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;
    // Bare `rtp test` - no-op; CommandsAPI will surface help via the `help` subtree.
    return true;
  }

  // --- Per-caller test-isolation semaphore wiring ---------------------------
  //
  // Every dispatch through this parent acquires a per-caller permit
  // ({@link TestSemaphore}) before the child subcommand parses its
  // arguments. On contention (the same caller already has a test in
  // flight) the dispatch is rescheduled via
  // {@link io.github.dailystruggle.rtp.api.scheduling.RTPScheduler#runTaskLater}
  // - never via thread parking - preserving REQ-RTP-S-005. The permit is
  // released only after the child's args-form future completes AND the
  // caller's {@link ActiveTestJobs} entries have drained, so async tails
  // (e.g. {@code stress}, {@code queue-starvation}) cannot overlap with
  // a subsequent test by the same caller.
  //
  // Bounded retry budget: at most {@link #DISPATCH_RETRY_LIMIT} reschedules
  // (~60s by default) before logging S-004 WARNING and giving up. Cancel
  // ({@link ActiveTestJobs#cancelOwned(UUID)}) force-releases the permit,
  // so a queued retry will succeed on the next tick.

  /**
   * One reschedule = 20 ticks (1s on the canonical 50ms cadence). Keeps
   * the contention loop loose enough that a typical short subcommand
   * (e.g. {@code commands}, {@code api-compat}) frees the permit before
   * the next retry, but tight enough that a player retrying after cancel
   * does not perceive a long stall.
   */
  static final long DISPATCH_RETRY_DELAY_TICKS = 20L;

  /**
   * Maximum reschedules before giving up with an S-004 WARNING. Default
   * 60 ≈ 60s wall-clock, matching {@code TestFullCmd.DRAIN_TIMEOUT_MILLIS}.
   * System-property overridable so operators can tune contention budget
   * without recompiling.
   */
  static final int DISPATCH_RETRY_LIMIT =
      Integer.getInteger("rtp.test.dispatch.retryLimit", 60);

  /**
   * Top-level dispatch entry point. Wraps {@link
   * io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand#onCommand
   * TreeCommand.onCommand} in a per-caller {@link TestSemaphore} acquire,
   * with bounded {@code runTaskLater} reschedules on contention.
   *
   * <p>The child's name is read from {@code args[i]} so the permit holder
   * is recorded as the actual subcommand the caller invoked (matching
   * {@code rtp test cancel} reporting). If {@code args[i]} is absent or
   * doesn't resolve to a child, we delegate without acquiring - bare
   * {@code rtp test} (which surfaces help) is not a test, and a malformed
   * subcommand path falls through to {@link
   * io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand#msgInvalidCommand
   * msgInvalidCommand}.
   */
  @Override
  public CompletableFuture<Boolean> onCommand(
      @NotNull UUID callerId,
      @NotNull Predicate<String> permissionCheckMethod,
      @NotNull Consumer<String> messageMethod,
      @NotNull String[] args,
      int i,
      @Nullable Map<String, CommandParameter> tempParameters) {
    String subName = resolveSubName(args, i);
    if (subName == null) {
      // Bare `rtp test` or unresolved subcommand - let the default
      // TreeCommand path handle help / msgInvalidCommand without taking
      // the permit. Bare invocations are not tests.
      return defaultOnCommand(
          callerId, permissionCheckMethod, messageMethod, args, i, tempParameters);
    }
    // `cancel` is the in-band stop switch for stuck tests - it must
    // never queue behind the very permit holder it's trying to abort.
    // Bypass the semaphore entirely and dispatch immediately, otherwise
    // the cancel itself would sit on the retry loop for ~60s waiting on
    // the very job the operator is trying to kill.
    if ("cancel".equalsIgnoreCase(subName)) {
      return defaultOnCommand(
          callerId, permissionCheckMethod, messageMethod, args, i, tempParameters);
    }
    return acquireAndDispatch(
        callerId, permissionCheckMethod, messageMethod, args, i, tempParameters, subName, 0);
  }

  /** Resolves the child name from {@code args[i]}, or {@code null} if absent/unresolved. */
  @Nullable
  private String resolveSubName(String[] args, int i) {
    for (int j = i; j < args.length; j++) {
      String arg = args[j];
      if (arg == null) continue;
      // Skip parameter tokens (e.g. iterations:5) - child names never contain delimiters.
      if (arg.indexOf('=') >= 0 || arg.indexOf(':') >= 0) continue;
      CommandsAPICommand child = commandLookup.get(arg.toUpperCase());
      if (child != null) return child.name();
      return null;
    }
    return null;
  }

  /**
   * Acquire-or-reschedule loop. Each attempt either:
   * <ul>
   *   <li>Acquires the permit and delegates to {@code super.onCommand},
   *       chaining release on completion + {@link ActiveTestJobs}
   *       drain; or</li>
   *   <li>On contention, reschedules itself via {@code runTaskLater} up
   *       to {@link #DISPATCH_RETRY_LIMIT} times, then logs S-004
   *       WARNING and falls through to the default dispatch (so the
   *       caller still sees a response).</li>
   * </ul>
   */
  private CompletableFuture<Boolean> acquireAndDispatch(
      UUID callerId,
      Predicate<String> permissionCheckMethod,
      Consumer<String> messageMethod,
      String[] args,
      int i,
      Map<String, CommandParameter> tempParameters,
      String subName,
      int attempt) {
    if (TestSemaphore.tryAcquire(callerId, subName)) {
      return dispatchWithPermit(
          callerId, permissionCheckMethod, messageMethod, args, i, tempParameters, subName);
    }
    if (attempt >= DISPATCH_RETRY_LIMIT) {
      TestSemaphore.Holder holder = TestSemaphore.holderOf(callerId);
      String holderName = holder == null ? "<unknown>" : holder.subName;
      RTP.log(
          Level.WARNING,
          "[RTP test] dispatch of '"
              + subName
              + "' for caller "
              + callerId
              + " gave up after "
              + DISPATCH_RETRY_LIMIT
              + " retries; permit still held by '"
              + holderName
              + "' (REQ-RTP-S-004)");
      // Falling through without the permit means the child WILL run and
      // can race the in-flight holder. That's the lesser of two evils:
      // silently dropping the command would itself violate S-004.
      return defaultOnCommand(
          callerId, permissionCheckMethod, messageMethod, args, i, tempParameters);
    }
    // Notify the caller once, on first contention, so they know why the
    // command appears to stall. Subsequent retries are silent.
    if (attempt == 0) {
      TestSemaphore.Holder holder = TestSemaphore.holderOf(callerId);
      String holderName = holder == null ? "<unknown>" : holder.subName;
      String msg =
          "[RTP test] queued behind '" + holderName + "'; will retry every 1s up to ~"
              + DISPATCH_RETRY_LIMIT + "s";
      messageMethod.accept(msg);
      RTP.log(Level.INFO, msg);
    }
    CompletableFuture<Boolean> result = new CompletableFuture<>();
    Runnable retry =
        () ->
            acquireAndDispatch(
                    callerId,
                    permissionCheckMethod,
                    messageMethod,
                    args,
                    i,
                    tempParameters,
                    subName,
                    attempt + 1)
                .whenComplete(
                    (ok, err) -> {
                      if (err != null) result.completeExceptionally(err);
                      else result.complete(ok);
                    });
    try {
      RTP.scheduler.runTaskLater(retry, DISPATCH_RETRY_DELAY_TICKS);
    } catch (Throwable t) {
      // Scheduler unavailable (e.g. headless tests). Run inline so we
      // don't silently drop the command.
      retry.run();
    }
    return result;
  }

  /**
   * Permit is held; delegate to the default {@link
   * io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand}
   * args-form dispatch and arrange release on completion.
   *
   * <p>Release is gated on BOTH the args-form future completing AND the
   * caller's {@link ActiveTestJobs} entries draining. Synchronous
   * subcommands (no jobs registered) drain inline via the listener's
   * fast path; async-tailed subcommands drain when their last job
   * unregisters. {@link ActiveTestJobs#cancelOwned(UUID)} also force-
   * releases the permit, so cancel always frees the queue.
   */
  private CompletableFuture<Boolean> dispatchWithPermit(
      UUID callerId,
      Predicate<String> permissionCheckMethod,
      Consumer<String> messageMethod,
      String[] args,
      int i,
      Map<String, CommandParameter> tempParameters,
      String subName) {
    CompletableFuture<Boolean> dispatched;
    try {
      dispatched =
          defaultOnCommand(
              callerId, permissionCheckMethod, messageMethod, args, i, tempParameters);
    } catch (Throwable t) {
      // Synchronous failure inside dispatch - release immediately and
      // surface the error per S-004.
      TestSemaphore.release(callerId, subName);
      RTP.log(
          Level.WARNING,
          "[RTP test] '" + subName + "' threw during dispatch: " + t + " (REQ-RTP-S-004)",
          t);
      CompletableFuture<Boolean> failed = new CompletableFuture<>();
      failed.completeExceptionally(t);
      return failed;
    }
    if (dispatched == null) {
      TestSemaphore.release(callerId, subName);
      return CompletableFuture.completedFuture(false);
    }
    return dispatched.whenComplete(
        (ok, err) -> {
          // Wait for any async tail to drain before releasing the permit.
          // addOnEmptyListener fires inline if the caller has no active
          // jobs (the common case for synchronous subcommands), or on
          // the thread that removes the last job otherwise.
          ActiveTestJobs.addOnEmptyListener(
              callerId,
              () -> TestSemaphore.release(callerId, subName));
        });
  }
}
