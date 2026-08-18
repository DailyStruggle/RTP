package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import org.jetbrains.annotations.Nullable;

/**
 * Bukkit-flavoured {@link TestCmd} that registers the four subcommands whose
 * class-loading would fail on Fabric:
 *
 * <ul>
 *   <li>{@link TestStressCmd} - uses {@code commandsapi.bukkit.OnlinePlayerParameter}.</li>
 *   <li>{@link TestChunkProbePerfCmd} - hard-imports {@code org.bukkit.Bukkit}/{@code World}/{@code Chunk}.</li>
 *   <li>{@link TestFullCmd} - uses {@code rtp.bukkitplatform.SendMessage} for child-output auditing.</li>
 *   <li>{@link AsyncReplyTestJob} - uses {@code SendMessage.addInterceptor} + {@code OnlinePlayerParameter}.</li>
 * </ul>
 *
 * <p>The {@code full}/{@code all} umbrella alias is also registered here, since
 * its target ({@link TestFullCmd}) only exists on Bukkit.
 *
 * <p>Bukkit callers ({@code RTPCmdBukkit}) instantiate this class instead of
 * the parent {@link TestCmd}; Fabric callers ({@code RTPFabricMod}) instantiate
 * {@link TestCmd} directly. See {@link TestCmd} Javadoc for the full split
 * rationale.
 */
public class BukkitTestCmd extends TestCmd {

  public BukkitTestCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  protected void registerPlatformSpecificChildren() {
    addSubCommand(new TestStressCmd(this));
    addSubCommand(new TestChunkProbePerfCmd(this));
    addSubCommand(new AsyncReplyTestJob(this));

    // `full` is the umbrella entry point (see RUNTIME_TEST_SUITE_PLAN.md section 3.2).
    // It is wired in last so `findChild` in TestFullCmd can resolve every
    // sibling. `all` is registered as an alias by pointing the same instance
    // at an additional key; TreeCommand uses upper-case keys (see
    // TreeCommand#addSubCommand).
    TestFullCmd fullCmd = new TestFullCmd(this);
    addSubCommand(fullCmd);
    commandLookup.put("ALL", fullCmd);
  }
}
