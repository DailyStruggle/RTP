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
 * Platform-neutral {@code /rtp} root command, shared by <em>every</em> platform.
 *
 * <p>This is the single shared root: it assembles the entire command tree out
 * of {@code rtp-core} and owns the teleport-dispatch entry points, so adding a
 * verb or parameter is a one-file change. It is consumed both by the platforms
 * dispatched through the Brigadier bridge (commands-api-ADR-001: Fabric /
 * NeoForge) and by the Bukkit family, whose legacy command-map registration is
 * handled by commands-api's {@code BukkitCommandRegistrar} (commands-api-ADR-003)
 * wrapping this neutral root rather than the root subclassing a Bukkit command
 * type.</p>
 *
 * <p>The tree is built from:</p>
 *
 * <ul>
 *   <li>the platform-neutral parameters and subcommands, via
 *       {@link CoreCommandTreeBuilder} (region / biome / toggletargetperms +
 *       reload / gui / config / scan / info / version / clear);</li>
 *   <li>the {@code player} / {@code world} parameters, sourced from
 *       {@link ServerAccessorCommandParameters} (backed entirely by
 *       {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor}); and</li>
 *   <li>optionally the {@code /rtp menu} surface (ADR-035 / ADR-044 / ADR-050),
 *       wired through {@link MenuWiringSupport} with a platform-supplied
 *       {@link MenuRenderer} and {@link MenuRedeemSubcommand.AnvilInputOpener}.</li>
 * </ul>
 *
 * <p>The remaining genuinely platform-bound behaviours are supplied through
 * small seams rather than per-platform subclasses:</p>
 *
 * <ul>
 *   <li><b>Outcome events</b> ({@link #successEvent}/{@link #failEvent}) fan out
 *       through the static {@link RTPCommandEvents} registry; the Bukkit
 *       bootstrap subscribes a callback that fires its plugin-event-bus
 *       {@code TeleportCommandSuccessEvent} / {@code TeleportCommandFailEvent},
 *       while Fabric / NeoForge register nothing.</li>
 *   <li><b>Reply rendering</b> — an optional {@code Function<UUID, Consumer<String>>}
 *       reply-renderer lets a platform wrap the message sink (the Bukkit family
 *       renders {@code /rtp help} rows as clickable chat). When absent the
 *       neutral message path is used.</li>
 *   <li><b>Teleport-path sender checks</b> ({@link #addSenderCheck}) gate the
 *       <em>teleport</em> invocation (e.g. the cross-server network waitlist);
 *       subcommands bypass them.</li>
 * </ul>
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
   * Self-discovering constructor for a platform that supplies a custom reply
   * renderer (e.g. the Bukkit family's {@code /rtp help} clickable-chat sink).
   * The menu bindings are resolved through {@link MenuBindingSupport} exactly as
   * for {@link #CoreRtpRoot()}.
   *
   * @param replyRenderer optional per-caller reply-renderer, or {@code null} to
   *                      use the neutral message path
   */
  public CoreRtpRoot(@Nullable Function<UUID, Consumer<String>> replyRenderer) {
    this(MenuBindingSupport.discoverRenderer(),
         MenuBindingSupport.discoverAnvilOpener(),
         replyRenderer);
  }

  /**
   * Explicit-injection constructor for platforms / tests that supply the menu
   * seam directly rather than through {@link MenuBindingSupport} discovery.
   *
   * @param menuRenderer the platform menu renderer, or {@code null} to disable
   *                     the menu open-page path
   * @param anvilOpener  the platform anvil / chat-prompt input opener, or
   *                     {@code null} when unavailable
   */
  public CoreRtpRoot(@Nullable MenuRenderer menuRenderer,
                     @Nullable MenuRedeemSubcommand.AnvilInputOpener anvilOpener) {
    this(menuRenderer, anvilOpener, null);
  }

  /**
   * Assembles the shared {@code /rtp} tree and (when a renderer is supplied) the
   * {@code /rtp menu} surface.
   *
   * @param menuRenderer  the platform menu renderer, or {@code null} to disable
   *                      the menu open-page path (the redeem path then degrades
   *                      to the configurable {@code menuInvalid} message)
   * @param anvilOpener   the platform anvil / chat-prompt input opener, or
   *                      {@code null} when unavailable
   * @param replyRenderer optional per-caller reply-renderer (e.g. the Bukkit
   *                      help-row clickable chat sink), or {@code null} to use
   *                      the neutral message path
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
