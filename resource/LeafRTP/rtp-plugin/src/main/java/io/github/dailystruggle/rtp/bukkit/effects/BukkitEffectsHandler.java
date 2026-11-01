package io.github.dailystruggle.rtp.bukkit.effects;

import io.github.dailystruggle.effectsapi.bukkit.BukkitEffectsInitializer;
import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.bukkit.events.*;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.effects.EffectDispatch;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.mock.SyntheticSideEffects;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.teleport.DeathTeleports;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import io.github.dailystruggle.rtp.common.tasks.tick.MiscTasks;
import io.github.dailystruggle.rtp.effects.EffectsResolver;
import io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class BukkitEffectsHandler {
    private BukkitEffectsHandler() {
    }

    /**
     * Publish {@code event} on the Bukkit bus unless {@code player} is a registered BIT mock
     * (ADR-121 section 11). Every RTP event in this class goes through this gate.
     *
     * @return {@code true} when the event was published
     */
    static boolean fire(RTPPlayer player, Event event) {
        if (!SyntheticSideEffects.publishesEvents(player)) return false;
        Bukkit.getPluginManager().callEvent(event);
        return true;
    }

    /** UUID-keyed variant of {@link #fire(RTPPlayer, Event)}; a {@code null} id publishes. */
    static boolean fire(UUID playerId, Event event) {
        if (SyntheticSideEffects.isSynthetic(playerId)) return false;
        Bukkit.getPluginManager().callEvent(event);
        return true;
    }

    /**
     * Resolve a Bukkit {@link Player} from a UUID and emit an observable FINE-level
     * log entry when the lookup returns {@code null}. Silently-skipped effects for
     * synthetic / offline UUIDs were previously indistinguishable from skipped real
     * players; the FINE log makes the skip auditable without spamming WARN. Returns
     * {@code null} when the UUID does not resolve to a live Bukkit entity, in which
     * case the caller must short-circuit (no real entity = no effect target).
     */
    private static Player resolveBukkitPlayer(UUID uuid, String stage) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            RTP.log(Level.FINE,
                    "[effects] skipping " + stage + " for uuid=" + uuid
                            + " (no live Bukkit entity; synthetic or offline)");
        }
        return player;
    }

    /**
     * Build, retarget, and dispatch every effect that applies to {@code player}
     * at {@code prefix} (e.g. {@code "rtp.effect.postteleport"}).
     *
     * <p>Sources unioned every call so that {@code /rtp reload} (which
     * atomically swaps the {@code multiConfigParserMap}) is honored without
     * any local cache invalidation:
     * <ol>
     *   <li>{@code player.getEffectivePermissions()} - historical
     *       permission-driven path; preserved verbatim for backwards compat.</li>
     *   <li>{@link EffectsResolver#resolveTokens(String, RTPPlayer, String)}
     *       - declarative {@code effects/<group>.yml} groups whose
     *       {@code when:} matches this stage and whose {@code permission:} /
     *       {@code players:} gating the player passes (effects-api-ADR-005).</li>
     * </ol>
     *
     * <p>S-005: resolution is in-memory; effects run only through the platform
     * scheduler, never inline after a scheduling failure.
     */
    private static void dispatchEffects(JavaPlugin plugin, String prefix, Player player) {
        dispatchEffects(plugin, prefix, player, null, null, null);
    }

    private static void dispatchEffects(JavaPlugin plugin, String prefix, Player player,
                                        org.bukkit.Location explicitLocation, org.bukkit.Location destinationLocation,
                                        TeleportPipelineTask pipelineTask) {
        // 1) Convert permission-attachment infos to flat node strings (the same
        //    transformation BukkitEffectsInitializer.buildEffects(prefix, perms)
        //    performs internally).
        Set<String> nodes = new LinkedHashSet<>();
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (info != null && info.getValue()) {
                nodes.add(info.getPermission());
            }
        }

        // 2) Look up an RTPPlayer view for the resolver's gating
        //    (hasPermission / uuid() / name()). Falls back to a permission-only
        //    union when the platform accessor isn't ready.
        String stage = stageOf(prefix);
        RTPPlayer rp = (RTPRuntime.serverAccessor() != null)
                ? RTPRuntime.serverAccessor().getPlayer(player.getUniqueId())
                : null;
        Collection<String> union = (rp != null)
                ? EffectsResolver.resolveUnioned(stage, rp, prefix, nodes)
                : nodes;
        if (union.isEmpty()) return;

        Object target;
        if (explicitLocation != null) {
            target = new io.github.dailystruggle.effectsapi.common.spi.EffectTarget(
                    io.github.dailystruggle.effectsapi.bukkit.BukkitHandles.wrap(player),
                    explicitLocation,
                    destinationLocation);
        } else {
            target = player;
        }

        // 3) Build and dispatch.
        List<Effect<?>> effects = EffectFactory.buildEffects(prefix, union);
        EffectDispatch.forEachIsolated(prefix, effects, effect -> {
            effect.setTarget(target);
            if (effect instanceof io.github.dailystruggle.effectsapi.common.effects.DeathEffect) {
                DeathTeleports.effectInFlight().add(player.getUniqueId());
                if (pipelineTask != null) {
                    pipelineTask.deathEffectTriggered = true;
                }
            }
            // Schedule on a thread that legally owns the player so that
            // platform-restricted ops inside Effect#run (NoteEffect playSound,
            // FireworkEffect spawn, etc.) don't trip Paper's AsyncCatcher
            // (REQ-RTP-S-005). On Folia, Bukkit.getScheduler().runTask throws
            // UnsupportedOperationException; BukkitEffectsInitializer reports
            // the rejection and drops the effect rather than running it on
            // the calling async pipeline thread. We prefer RTPRuntime.scheduler().runTaskForPlayer
            // (Folia entity-scheduler aware; "runTaskForPlayer" is the
            // historic name for a generic "run runnable on a thread owning
            // the player after N ticks" path) and fall back to the legacy
            // runEffect helper when no RTPPlayer is available (Paper/Spigot).
            dispatchEffectForPlayer(plugin, effect, rp);
        });
    }

    /**
     * Run {@code effect} on a thread that legally owns its target player.
     * Folia: routes through {@link RTPRuntime#scheduler()}'s
     * {@code runTaskForPlayer(RTPPlayer, RTPRunnable, delayTicks)} entry
     * point (per-entity scheduler hop). Paper/Spigot or when the
     * {@link RTPPlayer} view is unavailable: falls back to the legacy
     * {@link BukkitEffectsInitializer#runEffect(org.bukkit.plugin.Plugin, Effect)} helper,
     * which schedules on Bukkit's main thread or reports the rejection and
     * drops the effect; it never runs the effect on the calling thread.
     */
    private static void dispatchEffectForPlayer(JavaPlugin plugin, Effect<?> effect, RTPPlayer rp) {
        if (RTPRuntime.scheduler() != null && rp != null) {
            try {
                RTPRuntime.scheduler().runTaskForPlayer(
                        rp,
                        new io.github.dailystruggle.rtp.common.tasks.RTPRunnable((Runnable) effect),
                        1L);
                return;
            } catch (Throwable t) {
                RTP.log(Level.FINE,
                        "[effects] RTP.scheduler dispatch failed ("
                                + t.getClass().getName() + ": " + t.getMessage()
                                + "); falling back to BukkitEffectsInitializer.runEffect");
            }
        }
        BukkitEffectsInitializer.runEffect(plugin, effect);
    }

    /**
     * Extract the pipeline stage token (e.g. {@code "postteleport"}) from a
     * permission prefix of the shape {@code "rtp.effect.<stage>"}.
     */
    private static String stageOf(String prefix) {
        return EffectDispatch.stageOf(prefix);
    }

    private static boolean effectParsingEnabled(FactoryValue<PerformanceKeys> parser) {
        return EffectDispatch.isEffectParsingEnabled(parser);
    }

    public static void setupEffects(JavaPlugin plugin) {
        EffectDispatch.installWarnSink();
        // Per effects-api-ADR-003: EffectFactory no longer registers Bukkit
        // effects in a static block - the platform initializer must be invoked
        // explicitly. Idempotent.
        BukkitEffectsInitializer.registerAll();
        Configs configs = ActiveConfigs.get();
        FactoryValue<PerformanceKeys> parser = configs.getParser(PerformanceKeys.class);

        TeleportPipelineTask.setupPreActions.add(
                task -> {
                    PreSetupTeleportEvent event = new PreSetupTeleportEvent(task);
                    if (fire(task.player(), event) && event.isCancelled()) task.setCancelled(true);
                    if (task.player() != null && effectParsingEnabled(parser)) {
                        Player player = resolveBukkitPlayer(task.player().uuid(), "presetup");
                        if (player == null) return;
                        MiscTasks.async()
                                .add(() -> dispatchEffects(plugin, "rtp.effect.presetup", player));
                    }
                });

        TeleportPipelineTask.setupPostActions.add(
                (task, aBoolean) -> {
                    if (!aBoolean) return;
                    fire(task.player(), new PostSetupTeleportEvent(task));
                    if (task.player() != null && effectParsingEnabled(parser)) {
                        Player player = resolveBukkitPlayer(task.player().uuid(), "postsetup");
                        if (player == null) return;
                        MiscTasks.async()
                                .add(() -> dispatchEffects(plugin, "rtp.effect.postsetup", player));
                    }
                });

        TeleportPipelineTask.loadPreActions.add(
                task -> {
                    fire(task.player(), new PreLoadChunksEvent(task));

                    if (task.player() != null && effectParsingEnabled(parser)) {
                        Player player = resolveBukkitPlayer(task.player().uuid(), "preload");
                        if (player == null) return;
                        MiscTasks.async()
                                .add(() -> dispatchEffects(plugin, "rtp.effect.preload", player));
                    }
                });

        TeleportPipelineTask.loadPostActions.add(
                task -> {
                    fire(task.player(), new PostLoadChunksEvent(task));

                    if (task.player() != null && effectParsingEnabled(parser)) {
                        Player player = resolveBukkitPlayer(task.player().uuid(), "postload");
                        if (player == null) return;
                        MiscTasks.async()
                                .add(() -> dispatchEffects(plugin, "rtp.effect.postload", player));
                    }
                });

        TeleportPipelineTask.teleportPreActions.add(
                task -> {
                    fire(task.player(), new PreTeleportEvent(task));

                    if (task.player() != null && effectParsingEnabled(parser)) {
                        Player player = resolveBukkitPlayer(task.player().uuid(), "preteleport");
                        if (player == null) return;
                        org.bukkit.Location originLocation = player.getLocation();
                        org.bukkit.Location destinationLocation = null;
                        if (task.coords() != null) {
                            org.bukkit.World destWorld = Bukkit.getWorld(task.coords().worldName());
                            if (destWorld != null) {
                                destinationLocation = new org.bukkit.Location(destWorld, task.coords().x(), task.coords().y(), task.coords().z());
                            }
                        }
                        final org.bukkit.Location finalDest = destinationLocation;
                        // Synchronously resolve effects for preteleport so that any DeathEffect
                        // marks pipelineTask.deathEffectTriggered before runTeleport() executes.
                        dispatchEffects(plugin, "rtp.effect.preteleport", player, originLocation, finalDest, task);
                    }
                });

        TeleportPipelineTask.teleportPostActions.add(
                task -> {
                    fire(task.player(), new PostTeleportEvent(task));


                    if (task.player() != null) {
                        Player player = resolveBukkitPlayer(task.player().uuid(), "postteleport-title");
                        if (player == null) return;

                        EffectDispatch.PostTeleportTitle titleSpec =
                                EffectDispatch.resolvePostTeleportTitle(task);
                        if (titleSpec != null) {
                            RTPRuntime.scheduler().runTask(() -> {
                                SendMessage.title(
                                        player,
                                        titleSpec.title(),
                                        titleSpec.subtitle(),
                                        titleSpec.fadeIn(),
                                        titleSpec.stay(),
                                        titleSpec.fadeOut());
                                SendMessage.actionbar(player, titleSpec.actionbar());
                            });
                        }

                        EffectDispatch.PostTeleportCommands commands =
                                EffectDispatch.resolvePostTeleportCommands(task, player.getName(), false);
                        if (commands != null) {
                            RTPRuntime.scheduler().runTask(() -> {
                                for (String c : commands.consoleCommands()) {
                                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
                                }
                                for (String c : commands.playerCommands()) {
                                    player.performCommand(c);
                                }
                            });
                        }
                    }

                    if (task.player() != null && effectParsingEnabled(parser)) {
                        Player player = resolveBukkitPlayer(task.player().uuid(), "postteleport");
                        if (player == null) return;
                        MiscTasks.async()
                                .add(() -> dispatchEffects(plugin, "rtp.effect.postteleport", player));
                    }
                });

        RTPTeleportCancel.postActions.add(
                task -> {
                    UUID uuid = task.getPlayerId();
                    Player player = resolveBukkitPlayer(uuid, "cancel");

                    if (player == null) return;

                    fire(uuid, new TeleportCancelEvent(uuid));

                    MiscTasks.async()
                            .add(
                                    () -> {
                                        if (effectParsingEnabled(parser)) {
                                            dispatchEffects(plugin, "rtp.effect.cancel", player);
                                        }
                                    });
                });

        Region.onPlayerQueuePush.add(
                (region, uuid) -> {
                    Player player = resolveBukkitPlayer(uuid, "queuepush");
                    if (player == null) return;

                    fire(uuid, new PlayerQueuePushEvent(region, uuid));

                    MiscTasks.async()
                            .add(
                                    () -> {
                                        if (effectParsingEnabled(parser)) {
                                            dispatchEffects(plugin, "rtp.effect.queuepush", player);
                                        }
                                    });
                });

        Region.onPlayerQueuePop.add(
                (region, uuid) -> {
                    Player player = resolveBukkitPlayer(uuid, "queuepop");
                    if (player == null) return;

                    fire(uuid, new PlayerQueuePopEvent(region, uuid));

                    MiscTasks.async()
                            .add(
                                    () -> {
                                        if (effectParsingEnabled(parser)) {
                                            dispatchEffects(plugin, "rtp.effect.queuepop", player);
                                        }
                                    });
                });

        if (plugin != null) {
            Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
                @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
                public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
                    Player player = event.getEntity();
                    MiscTasks.async()
                            .add(
                                    () -> {
                                        if (effectParsingEnabled(parser)) {
                                            dispatchEffects(plugin, "rtp.effect.death", player);
                                        }
                                    });
                }
            }, plugin);
        }
    }
}
