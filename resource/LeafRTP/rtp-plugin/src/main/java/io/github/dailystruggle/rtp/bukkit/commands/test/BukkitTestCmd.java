package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.test.TestAccessorCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestWorldOpsCmd;
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
 * <p>The {@code full} sweep is registered here because {@link TestFullCmd}
 * only exists on Bukkit. The SAFE {@code all} command is platform-neutral.
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
    addSubCommand(new TestWorldOpsCmd(this));
    addSubCommand(new TestAccessorCmd(this));
    addSubCommand(new TestHologramCmd(this));

    addSubCommand(new TestFullCmd(this));
  }
}
