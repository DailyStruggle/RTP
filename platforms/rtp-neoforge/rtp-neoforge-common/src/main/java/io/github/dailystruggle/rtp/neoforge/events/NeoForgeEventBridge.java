package io.github.dailystruggle.rtp.neoforge.events;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.selection.region.LoginCacheTask;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.neoforge.server.NeoForgePlayerLifecycleHook;
import io.github.dailystruggle.rtp.neoforge.server.NeoForgeServerAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Wires NeoForge game-bus player/world events into the
 * {@link NeoForgeServerAccessor} maps, the join-time RTP path
 * ({@link NeoForgeOnEventTeleports}), and the login reserve cache (ADR-023).
 * NeoForge analogue of {@code FabricEventBridge}.
 *
 * <p><b>Mojmap-at-runtime simplification.</b> Unlike Fabric's obf-carrier
 * bridge - which proxies callbacks reflectively and routes everything through
 * {@code Object}-typed entry points to avoid pinning intermediary class names -
 * NeoForge ships Mojang-mapped names at runtime, so this bridge uses plain
 * {@code @SubscribeEvent} handlers with typed {@link ServerPlayer} /
 * {@link ServerLevel} parameters. No reflection, no proxies.</p>
 *
 * <p><b>Scope (N2.3).</b> Player join/quit (register/unregister + on-join RTP +
 * lifecycle-hook fan-out + login-reserve refill) and per-world load/unload.
 * Server lifecycle (start/stop), the per-tick scheduler drain, and command
 * registration are owned by {@code RTPNeoForgeMod}. The native PvP combat tag
 * (ADR-055) is a later follow-up.</p>
 *
 * <p><b>Memory hygiene (REQ-RTP-S-004).</b> Player disconnect drops the wrapper
 * from the accessor map; world unload removes the world entry. No
 * {@code org.bukkit.*} imports.</p>
 */
public final class NeoForgeEventBridge {

    private final NeoForgeServerAccessor accessor;

    public NeoForgeEventBridge(NeoForgeServerAccessor accessor) {
        this.accessor = accessor;
    }

    /** Register all game-bus callbacks. Called from {@code RTPNeoForgeMod}'s constructor. */
    public void register() {
        NeoForge.EVENT_BUS.register(this);
    }

    // -------------------------------------------------------------------------
    // Player session
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        try {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            accessor.registerPlayer(player);
            // Re-send the client command tree now that the player is registered
            // so /rtp is visible on first join. Vanilla sends the tree once,
            // inside PlayerList.placeNewPlayer (Commands.sendCommands), which runs
            // BEFORE this event fires; at that earlier point the player is not yet
            // in the accessor's maps, so /rtp's requires() predicate resolves a
            // null sender and strips the node, leaving /rtp invisible until some
            // later resend (relog, op change, /reload).
            //
            // Defer the resend by one tick. Vanilla fires this
            // PlayerLoggedInEvent from the MIDDLE of PlayerList.placeNewPlayer,
            // which has ALREADY called Commands.sendCommands(player) once for
            // this login. Calling sendCommands again synchronously here re-runs
            // the full requires()/usage traversal mid-login -- and now that the
            // player is registered (so /rtp's requires() resolves true) it walks
            // the entire authorized /rtp subtree inline on the login path, which
            // hangs the join. Hopping to the next server tick runs the resend
            // AFTER placeNewPlayer finishes, off the critical login path, while
            // still guaranteeing /rtp is visible on first join.
            final MinecraftServer server = accessor.getServer();
            if (server != null && RTP.scheduler != null) {
                final UUID joinedId = player.getUUID();
                RTP.scheduler.runTaskLater(() -> {
                    try {
                        ServerPlayer online = server.getPlayerList().getPlayer(joinedId);
                        if (online != null) {
                            server.getCommands().sendCommands(online);
                        }
                    } catch (Throwable t) {
                        RTP.log(Level.WARNING,
                                "[RTP][NeoForge] deferred command-tree resend on join failed: "
                                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                }, 1L);
            }
            // On-event (join / first-join) teleports are DISABLED on NeoForge for
            // now. The gate in NeoForgeOnEventTeleports.onJoin routes through
            // ParsePermissions.hasPerm -> RTPCommandSender.hasPermission, and the
            // current NeoForgeRTPPlayer.hasPermission implementation is an op-level
            // scan that returns true for EVERY node when the player is a server
            // operator. That makes rtp.onevent.firstjoin / rtp.onevent.join
            // implicitly granted to all operators, so every op join triggers a
            // teleport (the symptom: join freezes for operators). Bukkit avoids
            // this because those permission nodes default to non-op, so an op does
            // not inherit them unless they are granted explicitly.
            //
            // Until an explicit permission resolver lands (N2.5) that can tell an
            // explicit grant apart from an op-implicit "true", on-event teleports
            // must NOT fire merely because the joining player is an operator. Leave
            // the dispatch disabled; re-enable once hasPermission only returns true
            // for explicitly-applied permissions.
            //
            // if (server != null) {
            //     NeoForgeOnEventTeleports.onJoin(server, player);
            // }
            // ADR-049 - fan a UUID join event out to platform-agnostic subscribers
            // (e.g. the network-mode reservation-redeem path).
            NeoForgePlayerLifecycleHook hook = accessor.getNeoForgePlayerLifecycleHook();
            if (hook != null) hook.fireJoinFromPlayer(player);
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP][NeoForge] PlayerLoggedIn handler failed", t);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        try {
            if (event.getEntity() instanceof ServerPlayer player) {
                // ADR-049 - fan UUID quit out before the accessor forgets the
                // player wrapper, so subscribers can still resolve the UUID.
                NeoForgePlayerLifecycleHook hook = accessor.getNeoForgePlayerLifecycleHook();
                if (hook != null) hook.fireQuitFromPlayer(player);
                accessor.unregisterPlayer(player.getUUID());
            }
            // ADR-023 - Login Reserve Cache refill on quit. Mirror of OnPlayerQuit
            // (Bukkit): for every region with a login buffer, dispatch one async
            // promotion to top it up. Fail-soft: never throw from the handler.
            refillLoginReserveOnQuit();
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP][NeoForge] PlayerLoggedOut handler failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // World cache
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event) {
        try {
            if (event.getLevel() instanceof ServerLevel level) {
                accessor.registerWorld(level);
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP][NeoForge] LevelEvent.Load handler failed", t);
        }
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        try {
            if (event.getLevel() instanceof ServerLevel level) {
                accessor.unregisterWorld(level);
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP][NeoForge] LevelEvent.Unload handler failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // ADR-023 - login reserve cache (called by RTPNeoForgeMod at server-start)
    // -------------------------------------------------------------------------

    /**
     * ADR-023 - initialise the Login Reserve Cache on the default-world
     * (overworld) region when {@code PerformanceKeys.loginCacheEnabled=true}.
     * Sized to {@code loginCacheCap} (or the server max-players count when
     * {@code loginCacheCap=0}). Mirror of {@code RTPBukkitPlugin.initLoginReserveCache()}
     * / the Fabric path; fail-soft on every failure path because join-time RTP
     * must keep working even if the reserve cannot be allocated.
     */
    @SuppressWarnings("unchecked")
    public void initLoginReserveCache(MinecraftServer server) {
        if (server == null) return;
        try {
            ConfigParser<PerformanceKeys> perf =
                    (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
            if (perf == null) return;
            Object enabledObj = perf.getConfigValue(PerformanceKeys.loginCacheEnabled, false);
            boolean enabled = (enabledObj instanceof Boolean)
                    ? (Boolean) enabledObj
                    : Boolean.parseBoolean(String.valueOf(enabledObj));
            if (!enabled) return;

            long configuredCap = perf.getNumber(PerformanceKeys.loginCacheCap, 0L).longValue();
            int cap;
            if (configuredCap > 0) {
                cap = (int) Math.min(configuredCap, Integer.MAX_VALUE);
            } else {
                int snapCap = 0;
                try {
                    snapCap = RTP.metrics.snapshot().softCap;
                } catch (Throwable ignored) {
                    // snapshot() is non-throwing by contract; defensive.
                }
                cap = (snapCap > 0) ? snapCap : resolveMaxPlayers(server);
            }
            if (cap <= 0) {
                RTP.log(Level.WARNING, "[ADR-023] login cache enabled but resolved cap <= 0; skipping");
                return;
            }

            String defaultWorldName = resolveOverworldName(server);
            if (defaultWorldName == null) {
                RTP.log(Level.WARNING,
                        "[ADR-023] login cache enabled but overworld dimension could not be resolved; skipping");
                return;
            }

            Region region = RTP.selectionAPI.permRegionLookup.values().stream()
                    .filter(r -> r.getWorld() != null && defaultWorldName.equals(r.getWorld().name()))
                    .findFirst()
                    .orElse(null);
            if (region == null) {
                RTP.log(Level.WARNING,
                        "[ADR-023] login cache enabled but no region attached to default world '"
                                + defaultWorldName + "'; skipping");
                return;
            }

            region.queueManager.enableLoginCache(cap);
            RTP.log(Level.INFO,
                    "[ADR-023] login reserve cache enabled on region '" + region.name
                            + "' cap=" + cap + " (default world '" + defaultWorldName + "')");

            int online;
            try {
                int snapOnline = RTP.metrics.snapshot().playerCount;
                online = (snapOnline > 0) ? snapOnline : server.getPlayerCount();
            } catch (Throwable ignored) {
                online = server.getPlayerCount();
            }
            int target = Math.max(0, cap - online);
            if (target > 0) {
                new LoginCacheTask(region).promoteUpTo(target);
                RTP.log(Level.FINE, "[ADR-023] startup burst dispatched count=" + target);
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[ADR-023] login reserve cache init failed: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
    }

    /**
     * ADR-023 - refill the login reserve cache on player disconnect. Dispatches
     * one async {@code LoginCacheTask.promoteUpTo(1)} per region whose
     * {@code loginLocations} buffer is non-null. Fail-soft per region.
     */
    private static void refillLoginReserveOnQuit() {
        try {
            if (RTP.getInstance() == null || RTP.selectionAPI == null) return;
            for (Region region : RTP.selectionAPI.permRegionLookup.values()) {
                try {
                    if (region == null || region.queueManager == null) continue;
                    if (region.queueManager.loginLocations == null) continue;
                    new LoginCacheTask(region).promoteUpTo(1);
                } catch (Throwable t) {
                    RTP.log(Level.WARNING,
                            "[ADR-023] login reserve refill failed for region '"
                                    + (region == null ? "null" : region.name) + "': "
                                    + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[ADR-023] login reserve refill: outer failure "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static int resolveMaxPlayers(MinecraftServer server) {
        try {
            return server.getPlayerList().getMaxPlayers();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String resolveOverworldName(MinecraftServer server) {
        try {
            ServerLevel overworld = server.overworld();
            if (overworld != null) {
                return io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds
                        .locationString(overworld.dimension());
            }
        } catch (Throwable ignored) {
            // best-effort
        }
        return null;
    }
}
