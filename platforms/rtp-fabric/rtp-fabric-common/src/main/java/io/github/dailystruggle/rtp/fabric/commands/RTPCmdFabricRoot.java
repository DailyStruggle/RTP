package io.github.dailystruggle.rtp.fabric.commands;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;
import io.github.dailystruggle.rtp.common.commands.config.ConfigCmd;
import io.github.dailystruggle.rtp.common.commands.info.InfoCmd;
import io.github.dailystruggle.rtp.common.commands.parameters.BiomeParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.BooleanParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.FloatParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.ShapeParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.VertParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.WorldParameter;
import io.github.dailystruggle.rtp.common.commands.reload.ReloadCmd;
import io.github.dailystruggle.rtp.common.commands.scan.ScanCmd;
import io.github.dailystruggle.rtp.fabric.server.FabricServerAccessor;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Fabric root {@code /rtp} command (Phase 2 Step G — G1 minimal scope).
 *
 * <p><b>G1 scope:</b> bare-minimum {@code /rtp} that triggers the default RTP
 * teleport on the caller. <em>No parameters, no subcommands.</em> Brigadier
 * registration via {@link RTPCmdFabric#register} from
 * {@code RTPFabricMod.onInitialize()} using
 * {@code CommandRegistrationCallback.EVENT}.
 *
 * <p><b>Why minimal:</b> the user explicitly scoped Step G to "let permissions
 * be deferred and focus on getting `/rtp` to work" so a Fabric server can be
 * smoke-tested end-to-end before parameter/subcommand parity work begins. See
 * {@code MULTI_PLATFORM_PLAN.md} Step G status block.
 *
 * <p><b>Pure delegation.</b> All teleport logic lives in
 * {@link RTPCmd#compute(UUID, Map, CommandsAPICommand, java.util.function.Consumer)};
 * this class exists solely to provide a concrete, named, parameter-free
 * {@link CommandsAPICommand} the Brigadier adapter can convert.
 *
 * <h2>TODO — Full Bukkit parity (deferred follow-up Step G2)</h2>
 *
 * The Bukkit equivalent ({@code RTPCmdBukkit}, ~115 line ctor) registers:
 * <ul>
 *   <li><b>Parameters:</b> {@code region} (with nested {@code world} / {@code price}
 *       / {@code worldborderoverride} / {@code shape} / {@code vert}),
 *       {@code biome}, {@code player} (target player),
 *       {@code world}, {@code toggletargetperms}.</li>
 *   <li><b>Subcommands:</b> {@code reload}, {@code help}, {@code config},
 *       {@code scan}, {@code info}, {@code test}.</li>
 * </ul>
 *
 * Parity work cannot reuse {@code RTPCmdBukkit} verbatim: its parameter
 * validators call {@code Bukkit.getWorld(s)} / {@code Bukkit.getPlayer(s)}
 * directly, which would violate ADR-022 §4. The Fabric port must route
 * through {@code RTP.serverAccessor.getRTPWorld(s)} /
 * {@code RTP.serverAccessor.getPlayer(s)} instead.
 *
 * <p>Permission-based parameter gating ({@code rtp.region}, {@code rtp.biome},
 * {@code rtp.world.*}, {@code rtp.other}) blocks on Step F
 * (fabric-permissions-api integration). The current G1 root is permissive —
 * any player can run {@code /rtp} on themselves.
 */
public final class RTPCmdFabricRoot extends BaseRTPCmdImpl implements RTPCmd {

    public RTPCmdFabricRoot() {
        super(null);

        // ---- Parameters (parity with RTPCmdBukkit lines 51–144) ----
        // Validators route world/player lookups through RTP.serverAccessor.* so
        // no org.bukkit.* leaks into rtp-fabric (ADR-022 §4 / S-005 nuance).
        // Permission checks use RTP.serverAccessor.getSender(uuid).hasPermission(...);
        // until Step F lands the BrigadierBridgeContext predicate is permissive,
        // but the validator shape stays correct so perms work for free once
        // fabric-permissions-api is wired.

        // region (with nested world / price / worldborderoverride / shape / vert)
        RegionParameter regionParameter =
            new RegionParameter(
                "rtp.region",
                "select a region to teleport to",
                (uuid, s) ->
                    RTP.selectionAPI.regionNames().contains(s)
                        && RTP.serverAccessor.getSender(uuid).hasPermission("rtp.regions." + s));
        regionParameter.put(
            "world",
            new WorldParameter(
                "rtp.params",
                "override teleport world for this region",
                (uuid, s) ->
                    RTP.serverAccessor.getRTPWorld(s) != null
                        && RTP.serverAccessor.getSender(uuid).hasPermission("rtp.worlds." + s)));
        regionParameter.put(
            "price",
            new FloatParameter(
                "rtp.params",
                "override teleport cost for this region",
                (uuid, s) -> {
                    try {
                        Double.parseDouble(s);
                        return true;
                    } catch (NumberFormatException exception) {
                        return false;
                    }
                }));
        regionParameter.put(
            "worldborderoverride",
            new BooleanParameter(
                "rtp.params",
                "override world-border respect for this region",
                (uuid, s) -> s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")));
        regionParameter.put(
            "shape",
            new ShapeParameter(
                "rtp.params",
                "override region shape",
                (uuid, s) -> RTP.factoryMap.get(RTP.factoryNames.shape).contains(s)));
        regionParameter.put(
            "vert",
            new VertParameter(
                "rtp.params",
                "modify y selection",
                (uuid, s) -> RTP.factoryMap.get(RTP.factoryNames.vert).contains(s)));
        addParameter("region", regionParameter);

        // biome — preserve Locale.ROOT upper-casing (Turkish-i JVM bug;
        // mirrors RTPCmdBukkit line 109).
        addParameter(
            "biome",
            new BiomeParameter(
                "rtp.biome",
                "select a biome to teleport to",
                (uuid, s) -> {
                    RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
                    // Accept namespaced biome ids (e.g. `minecraft:badlands`) as well as
                    // bare enum names. `ServerAccessor#getBiomes()` now emits both forms
                    // (parity with Bukkit/Folia/Paper), so a single membership probe over
                    // the user-typed string + its case/namespace variants covers both
                    // grammars. Downstream `BiomeNames#matches` handles the namespace
                    // equivalence at filter time.
                    if (s == null) return false;
                    int colon = s.indexOf(':');
                    String bareKey = (colon >= 0) ? s.substring(colon + 1) : s;
                    String upper = bareKey.toUpperCase(Locale.ROOT);
                    java.util.Set<String> biomes = RTP.serverAccessor.getBiomes();
                    boolean known = biomes.contains(s)
                            || biomes.contains(upper)
                            || biomes.contains(bareKey.toLowerCase(Locale.ROOT))
                            || biomes.contains("minecraft:" + bareKey.toLowerCase(Locale.ROOT));
                    return known
                            && (sender.hasPermission("rtp.biome.*")
                                || sender.hasPermission("rtp.biome." + bareKey)
                                || sender.hasPermission("rtp.biome." + s));
                }));

        // player — target-player parameter. Fabric has no commands-api
        // OnlinePlayerParameter analog, so we register a minimal
        // CommandParameter subclass: validator covers existence + the
        // `rtp.notme` self-opt-out (parity with RTPCmdBukkit lines 116–125).
        //
        // values() returns the live online-player snapshot via
        // FabricServerAccessor.getOnlinePlayerNames() so Brigadier surfaces
        // tab-completion (CHECKLIST-fabric-tabcompletion-audit P3,
        // commands-api-ADR-001 addendum 2026-05-06). When the accessor is
        // not yet bound (e.g., very early init), falls back to the empty
        // set; Brigadier still accepts the typed value because validation
        // continues to flow through the BiFunction above on execute.
        addParameter(
            "player",
            new CommandParameter(
                "rtp.other",
                "teleport someone else",
                (uuid, s) -> {
                    RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
                    if (!sender.hasPermission("rtp.other")) return false;
                    RTPPlayer target = RTP.serverAccessor.getPlayer(s);
                    if (target == null || !target.name().equalsIgnoreCase(s)) return false;
                    RTPCommandSender targetSender = RTP.serverAccessor.getSender(target.uuid());
                    return targetSender == null || !targetSender.hasPermission("rtp.notme");
                }) {
                @Override
                public Set<String> values() {
                    return RTP.serverAccessor instanceof FabricServerAccessor f
                        ? f.getOnlinePlayerNames()
                        : Collections.emptySet();
                }
            });

        // world — top-level world parameter (parity with RTPCmdBukkit line 130).
        addParameter(
            "world",
            new WorldParameter(
                "rtp.world",
                "select a world to teleport to",
                (uuid, s) ->
                    RTP.serverAccessor.getRTPWorld(s) != null
                        && RTP.serverAccessor.getSender(uuid).hasPermission("rtp.worlds." + s)));

        // toggletargetperms (parity with RTPCmdBukkit line 140).
        addParameter(
            "toggletargetperms",
            new BooleanParameter(
                "rtp.params",
                "check player's perms when running this command",
                (uuid, s) ->
                    RTP.serverAccessor.getSender(uuid).hasPermission("rtp.params")
                        && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))));

        // ---- Subcommands (parity with RTPCmdBukkit lines 146–151) ----
        // TestCmd is intentionally deferred — the Bukkit TestCmd lives in
        // rtp-plugin/.../bukkit/commands/test/TestCmd.java and depends on
        // Bukkit-only types. A platform-neutral lift is tracked under Step G2
        // follow-ups in MULTI_PLATFORM_PLAN.md.
        addSubCommand(new ReloadCmd(this));
        // /rtp help intentionally NOT registered: commands-api's TreeCommand
        // auto-emits a complete built-in help listing when no HELP
        // subcommand exists (TreeCommand line 231), which lists every
        // registered subcommand rather than only those that happen to
        // have a matching MessagesKeys enum value (the bug the removed
        // HelpCmd had).
        addSubCommand(new ConfigCmd(this));
        addSubCommand(new ScanCmd(this));
        addSubCommand(new InfoCmd(this));
        addSubCommand(new io.github.dailystruggle.rtp.common.commands.admin.ClearCacheCmd(this));

        // /rtp menu - mirror of RTPCmdBukkit:215-230. ADR-050 Stage 3β.D.2b
        // (2026-05-24) deleted the token registry; clicks carry concrete
        // /rtp menu ... commands resolved by MenuWiringSupport.attachTo.
        // Fabric platform pieces:
        //   * permissionProbe - routes through RTP.serverAccessor's ADR-048
        //     Phase B menuPermissionProbe override (FabricServerAccessor
        //     -> FabricEffectivePermissionsResolver per rtp-fabric-ADR-011).
        //   * renderer        - ChatMenuRenderer (rtp-fabric-ADR-012 §1):
        //     single rtp-core class, no per-version carrier split needed
        //     because the only platform coupling is the run-command sink
        //     installed on FabricServerAccessor.sendMessageWithRunCommand.
        //   * anvilOpener     - FabricChatPromptCallback (rtp-fabric-ADR-012
        //     §3): TTL-bounded chat-prompt substitute for the Paper anvil
        //     GUI; degrades to menuInvalid when ServerMessageEvents is not
        //     on the runtime classpath.
        final java.util.function.Function<UUID, java.util.function.Predicate<String>>
            menuPermissionProbe = viewer -> perm -> {
                if (perm == null || perm.isEmpty()) return true;
                if (viewer.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
                    return true;
                }
                return RTP.serverAccessor.menuPermissionProbe(viewer).test(perm);
            };
        // rtp-fabric-ADR-012 §4 un-defer (Step I Session 3): prefer the
        // written-book modal where the active carrier supports it (1.21+ and
        // the deobf 26.x line), falling back transparently to the chat
        // renderer on 1.20.x (where FabricVersionAdapter.openBookMenu keeps
        // its default false).
        final io.github.dailystruggle.rtp.api.menu.MenuRenderer menuRenderer =
            new io.github.dailystruggle.rtp.fabric.menu.FabricBookMenuRenderer(
                new io.github.dailystruggle.rtp.common.commands.menu.ChatMenuRenderer());
        final io.github.dailystruggle.rtp.common.commands.menu.MenuRedeemSubcommand.AnvilInputOpener
            anvilOpener =
                new io.github.dailystruggle.rtp.fabric.menu.FabricChatPromptCallback();
        io.github.dailystruggle.rtp.common.commands.menu.MenuWiringSupport.attachTo(
            this,
            new io.github.dailystruggle.rtp.common.commands.menu.MenuPlatformBindings(
                menuPermissionProbe, menuRenderer, anvilOpener));
    }

    @Override
    public String name() {
        return "rtp";
    }

    @Override
    public boolean onCommand(UUID senderId,
                             Map<String, List<String>> parameterValues,
                             CommandsAPICommand nextCommand) {
        // Defer to RTPCmd.compute (the canonical teleport dispatcher).
        // RTPCmd's interface default for the 4-arg overload would route here
        // anyway; making this explicit avoids any super-call ambiguity.
        RTP.log(Level.FINER, "[RTP][trace] RTPCmdFabricRoot.onCommand(4-arg) ENTER senderId=" + senderId
                + " params=" + parameterValues
                + " nextCommand=" + (nextCommand == null ? "null" : nextCommand.name())
                + " thread=" + Thread.currentThread().getName());
        if (nextCommand != null) {
            RTP.log(Level.FINER, "[RTP][trace] RTPCmdFabricRoot.onCommand returning early (nextCommand != null)");
            return true;
        }
        boolean res;
        try {
            res = compute(senderId, parameterValues, nextCommand, null);
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP][trace] RTPCmdFabricRoot.compute threw: " + t.getMessage(), t);
            throw t;
        }
        RTP.log(Level.FINER, "[RTP][trace] RTPCmdFabricRoot.compute returned " + res);
        return res;
    }

    @Override
    public void successEvent(RTPCommandSender sender, RTPPlayer player) {
        // Intentional no-op on Fabric (by design, not deferred). The Bukkit
        // overrides fire TeleportCommandSuccessEvent / TeleportCommandFailEvent
        // on the Bukkit plugin event bus for third-party plugin observability;
        // those events are org.bukkit.event.Event subclasses and cannot be
        // reused on Fabric. Fabric has no equivalent plugin-event-bus consumer
        // surface in scope, and RTP's in-house runnable-collection hooks
        // (RTPRunnable / TeleportData) already cover internal observability,
        // so firing nothing here is the correct Fabric behaviour. See
        // MULTI_PLATFORM_PLAN.md Step G2 (resolved 2026-05-24).
    }

    @Override
    public void failEvent(RTPCommandSender sender, String msg) {
        // Intentional no-op on Fabric (by design) — see successEvent().
    }
}
