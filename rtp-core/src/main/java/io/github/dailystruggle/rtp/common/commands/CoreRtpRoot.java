package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.menu.MenuBindingSupport;
import io.github.dailystruggle.rtp.common.commands.menu.MenuPlatformBindings;
import io.github.dailystruggle.rtp.common.commands.menu.MenuRedeemSubcommand;
import io.github.dailystruggle.rtp.common.commands.menu.MenuWiringSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Platform-neutral {@code /rtp} root command shared across all server platforms.
 *
 * <p>Assembles parameters and subcommands via {@link CoreCommandTreeBuilder} and
 * wires optional menu surfaces via {@link MenuWiringSupport}.</p>
 */
public class CoreRtpRoot extends BaseRTPCmdImpl implements RTPCmd {

  private final Semaphore senderChecksGuard = new Semaphore(1);
  private final List<Predicate<RTPCommandSender>> senderChecks = new ArrayList<>();

  /**
   * Optional platform reply-renderer: maps a caller UUID to the {@link Consumer}
   * that delivers reply lines for that caller. {@code null} on platforms (Fabric
   * / NeoForge) that use the neutral message path.
   */
  private final @Nullable Function<UUID, Consumer<String>> replyRenderer;

  /**
   * Self-discovering constructor used by every platform that needs only the
   * neutral message path (Fabric / NeoForge). The menu renderer and anvil /
   * chat-prompt opener are resolved through {@link MenuBindingSupport} (the
   * {@code MenuRendererProvider} / {@code AnvilInputOpenerProvider}
   * {@link java.util.ServiceLoader} SPIs), so no platform names a renderer class
   * in its root.
   */
  public CoreRtpRoot() {
    this(MenuBindingSupport.discoverRenderer(),
         MenuBindingSupport.discoverAnvilOpener(),
         null);
  }

  /**
   * Self-discovering constructor with custom reply renderer.
   *
   * @param replyRenderer optional per-caller reply-renderer, or {@code null}
   */
  public CoreRtpRoot(@Nullable Function<UUID, Consumer<String>> replyRenderer) {
    this(MenuBindingSupport.discoverRenderer(),
         MenuBindingSupport.discoverAnvilOpener(),
         replyRenderer);
  }

  /**
   * Explicit-injection constructor without custom reply renderer.
   */
  public CoreRtpRoot(@Nullable MenuRenderer menuRenderer,
                     @Nullable MenuRedeemSubcommand.AnvilInputOpener anvilOpener) {
    this(menuRenderer, anvilOpener, null);
  }

  /**
   * Primary constructor assembling the command tree and optional menu surfaces.
   */
  public CoreRtpRoot(@Nullable MenuRenderer menuRenderer,
                     @Nullable MenuRedeemSubcommand.AnvilInputOpener anvilOpener,
                     @Nullable Function<UUID, Consumer<String>> replyRenderer) {
    super(null);
    this.replyRenderer = replyRenderer;

    // Platform-neutral parameters (region / biome / toggletargetperms) and
    // every common subcommand are assembled once by rtp-core's
    // CoreCommandTreeBuilder. The `player` / `world` parameters are sourced from
    // the platform server-accessor (ServerAccessorCommandParameters) so no
    // platform-native enumeration leaks into the tree.
    CoreCommandTreeBuilder.attachCommonParameters(this, new ServerAccessorCommandParameters());
    CoreCommandTreeBuilder.attachCommonSubcommands(this);

    // /rtp menu (ADR-050: clicks carry concrete /rtp menu ... commands resolved
    // by MenuWiringSupport.attachTo). The permission probe is identical on every
    // platform - it routes through the accessor's menuPermissionProbe override
    // (ADR-048 Phase B) - so it is built here rather than duplicated per platform.
    // Only the renderer and the anvil / chat-prompt opener are platform-specific
    // and are injected.
    if (menuRenderer != null || anvilOpener != null) {
      final Function<UUID, Predicate<String>> menuPermissionProbe =
          viewer -> perm -> {
            if (perm == null || perm.isEmpty()) return true;
            if (viewer.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
              return true;
            }
            return RTP.serverAccessor.menuPermissionProbe(viewer).test(perm);
          };
      MenuWiringSupport.attachTo(
          this,
          new MenuPlatformBindings(menuPermissionProbe, menuRenderer, anvilOpener));
    }
  }

  /**
   * Register a teleport-path sender check (e.g. the cross-server network
   * waitlist guard). Checks gate only the bare-teleport invocation; subcommand
   * dispatch bypasses them.
   *
   * @param senderCheck predicate over the resolved {@link RTPCommandSender}
   */
  public void addSenderCheck(Predicate<RTPCommandSender> senderCheck) {
    try {
      senderChecksGuard.acquire();
      senderChecks.add(senderCheck);
    } catch (InterruptedException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    } finally {
      senderChecksGuard.release();
    }
  }

  /**
   * Legacy {@code String[]}-args command entry, supplied to commands-api's
   * {@code BukkitCommandRegistrar} as its {@code StringCommandDispatcher}. It
   * runs the teleport-path sender checks (skipped for subcommand dispatch) and
   * then routes to the platform-neutral {@link RTPCmd} guard path. The caller
   * UUID is already resolved by the registrar ({@code CommandsAPI.serverId} for
   * console). Platforms dispatching through the Brigadier bridge never call this.
   */
  public boolean dispatchString(UUID senderId, String label, String[] args) {
    boolean isSubcommand = args != null
        && args.length > 0
        && getCommandLookup().containsKey(args[0].toUpperCase(java.util.Locale.ROOT));

    RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);

    if (!isSubcommand) {
      boolean valid = true;
      for (Predicate<RTPCommandSender> senderCheck : senderChecks) {
        valid &= senderCheck.test(sender);
      }
      if (!valid) {
        return false;
      }
    }

    return onCommand(sender, this, label, args);
  }

  @Override
  public boolean onCommand(UUID senderId,
                           Map<String, List<String>> parameterValues,
                           CommandsAPICommand nextCommand) {
    return onCommand(senderId, parameterValues, nextCommand, null);
  }

  @Override
  public boolean onCommand(UUID senderId,
                           Map<String, List<String>> parameterValues,
                           CommandsAPICommand nextCommand,
                           Consumer<String> messageMethod) {
    // Defer to RTPCmd.compute (the canonical teleport dispatcher).
    if (nextCommand != null) {
      return true;
    }

    // Teleport-path sender checks (e.g. cross-server waitlist). Empty on
    // platforms that register none.
    if (!senderChecks.isEmpty()) {
      RTPCommandSender checkSender = RTP.serverAccessor.getSender(senderId);
      boolean valid = true;
      for (Predicate<RTPCommandSender> senderCheck : senderChecks) {
        valid &= senderCheck.test(checkSender);
      }
      if (!valid) {
        return false;
      }
    }

    // When a platform reply-renderer is installed (e.g. the Bukkit help-row
    // clickable chat sink) it owns reply delivery; otherwise the neutral
    // message path (RTP.serverAccessor.sendMessage) applies.
    Consumer<String> effective =
        (replyRenderer != null) ? replyRenderer.apply(senderId) : messageMethod;

    try {
      return compute(senderId, parameterValues, nextCommand, effective);
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP] CoreRtpRoot.compute threw: " + t.getMessage(), t);
      throw t;
    }
  }

  @Override
  public void successEvent(RTPCommandSender sender, RTPPlayer player) {
    // Fan out through the in-house callback registry. Platforms with a
    // plugin-event bus (the Bukkit family) subscribe at startup and publish
    // their TeleportCommandSuccessEvent; Fabric / NeoForge register nothing.
    RTPCommandEvents.fireSuccess(sender, player);
  }

  @Override
  public void failEvent(RTPCommandSender sender, String msg) {
    RTPCommandEvents.fireFail(sender, msg);
  }
}
