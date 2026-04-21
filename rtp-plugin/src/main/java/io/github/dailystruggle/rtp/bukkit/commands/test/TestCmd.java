package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Parent node for the {@code rtp test …} runtime test suite.
 *
 * <p>See {@code docs/dev/RUNTIME_TEST_SUITE_PLAN.md} for the full design,
 * roadmap, and requirements traceability. This initial drop ships only
 * {@link TestStressCmd}; further subcommands ({@code queue}, {@code safety},
 * {@code verifiers}, {@code memory}, {@code platform}, {@code full}) are
 * intentionally deferred to follow-up PRs to keep each change scoped.
 *
 * <p>Lives in {@code rtp-plugin} (not {@code rtp-core}) because
 * {@link TestStressCmd} uses {@link
 * io.github.dailystruggle.commandsapi.bukkit.LocalParameters.OnlinePlayerParameter},
 * which depends on {@code org.bukkit.*}. Per architecture rules, {@code rtp-core}
 * must remain platform-agnostic.
 */
public class TestCmd extends BaseRTPCmdImpl {

  public TestCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addSubCommand(new TestStressCmd(this));
    addSubCommand(new TestCancelCmd(this));
    addSubCommand(new TestSchedulerCmd(this));
    addSubCommand(new TestReloadSafetyCmd(this));
    addSubCommand(new TestCommandsCmd(this));
    addSubCommand(new LiveCommandDispatcherTestJob(this));
    addSubCommand(new TestApiCompatCmd(this));
    addSubCommand(new TestChunkTicketCmd(this));
    addSubCommand(new TestDisconnectMidflightCmd(this));
    addSubCommand(new TestAnvilPrefilterCmd(this));
    addSubCommand(new TestBiomeSourceCmd(this));
    addSubCommand(new TestAsyncChunkLoadCmd(this));
    addSubCommand(new AsyncReplyTestJob(this));
    addSubCommand(new QueueStarvationTestJob(this));
    addSubCommand(new EconomyIsolationTestJob(this));
    addSubCommand(new FoliaOwnershipTestJob(this));

    // `full` is the umbrella entry point (see RUNTIME_TEST_SUITE_PLAN.md §3.2).
    // It is wired in last so `findChild` in TestFullCmd can resolve every
    // sibling. `all` is registered as an alias by pointing the same
    // instance at an additional key; TreeCommand uses upper-case keys
    // (see TreeCommand#addSubCommand).
    TestFullCmd fullCmd = new TestFullCmd(this);
    addSubCommand(fullCmd);
    commandLookup.put("ALL", fullCmd);
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
    return "runtime self-test suite for operators (see RUNTIME_TEST_SUITE_PLAN.md)";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;
    // Bare `rtp test` - no-op; CommandsAPI will surface help via the `help` subtree.
    return true;
  }
}
