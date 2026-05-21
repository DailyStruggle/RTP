package io.github.dailystruggle.rtp.bukkit.effects;

import io.github.dailystruggle.effectsapi.bukkit.BukkitEffectsInitializer;
import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.bukkit.events.*;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import io.github.dailystruggle.rtp.effects.EffectsResolver;
import io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class BukkitEffectsHandler {
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
     *   <li>{@code player.getEffectivePermissions()} — historical
     *       permission-driven path; preserved verbatim for backwards compat.</li>
     *   <li>{@link EffectsResolver#resolveTokens(String, RTPPlayer, String)}
     *       — declarative {@code effects/<group>.yml} groups whose
     *       {@code when:} matches this stage and whose {@code permission:} /
     *       {@code players:} gating the player passes (effects-api-ADR-005).</li>
     * </ol>
     *
     * <p>S-005: resolution is in-memory; the effect itself is dispatched via
     * {@code BukkitEffectsInitializer.runEffect} which already routes to the
     * main thread.
     */
    private static void dispatchEffects(JavaPlugin plugin, String prefix, Player player) {
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
        RTPPlayer rp = (RTP.serverAccessor != null)
                ? RTP.serverAccessor.getPlayer(player.getUniqueId())
                : null;
        Collection<String> union = (rp != null)
                ? EffectsResolver.resolveUnioned(stage, rp, prefix, nodes)
                : nodes;
        if (union.isEmpty()) return;

        // 3) Build and dispatch.
        List<Effect<?>> effects = EffectFactory.buildEffects(prefix, union);
        for (Effect<?> effect : effects) {
            effect.setTarget(player);
            // Schedule on a thread that legally owns the player so that
            // platform-restricted ops inside Effect#run (NoteEffect playSound,
            // FireworkEffect spawn, etc.) don't trip Paper's AsyncCatcher
            // (REQ-RTP-S-005). On Folia, Bukkit.getScheduler().runTask throws
            // UnsupportedOperationException and BukkitEffectsInitializer's
            // catch-block then falls back to running the effect directly on
            // the calling async pipeline thread, which is the failure mode
            // this routes around. We prefer RTP.scheduler.scheduleTeleport
            // (Folia entity-scheduler aware; "scheduleTeleport" is the
            // historic name for a generic "run runnable on a thread owning
            // the player after N ticks" path) and fall back to the legacy
            // runEffect helper when no RTPPlayer is available (Paper/Spigot).
            dispatchEffectForPlayer(plugin, effect, rp);
        }
    }

    /**
     * Run {@code effect} on a thread that legally owns its target player.
     * Folia: routes through {@link RTP#scheduler}'s
     * {@code scheduleTeleport(RTPPlayer, RTPRunnable, delayTicks)} entry
     * point (per-entity scheduler hop). Paper/Spigot or when the
     * {@link RTPPlayer} view is unavailable: falls back to the legacy
     * {@link BukkitEffectsInitializer#runEffect(org.bukkit.plugin.Plugin, Effect)} helper,
     * whose {@code Bukkit.getScheduler().runTask} path remains the correct
     * main-thread dispatch on those platforms.
     */
    private static void dispatchEffectForPlayer(JavaPlugin plugin, Effect<?> effect, RTPPlayer rp) {
        if (RTP.scheduler != null && rp != null) {
            try {
                RTP.scheduler.scheduleTeleport(
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
        int dot = prefix.lastIndexOf('.');
        return (dot >= 0 && dot < prefix.length() - 1) ? prefix.substring(dot + 1) : prefix;
    }

    public static void setupEffects(JavaPlugin plugin) {
        // Per effects-api-ADR-003: EffectFactory no longer registers Bukkit
        // effects in a static block — the platform initializer must be invoked
        // explicitly. Idempotent.
        BukkitEffectsInitializer.registerAll();
        Configs configs = RTP.configs;
        FactoryValue<PerformanceKeys> parser = configs.getParser(PerformanceKeys.class);

        TeleportPipelineTask.setupPreActions.add(
                task -> {
                    PreSetupTeleportEvent event = new PreSetupTeleportEvent(task);
                    Bukkit.getPluginManager().callEvent(event);
                    if (event.isCancelled()) task.setCancelled(true);
                    if (task.player() != null) {
                        if (!Boolean.parseBoolean(
                                parser.getData().getOrDefault(PerformanceKeys.effectParsing, false).toString()))
                            return;
                        Player player = resolveBukkitPlayer(task.player().uuid(), "presetup");
                        if (player == null) return;
                        RTP.getInstance()
                                .miscAsyncTasks
                                .add(() -> dispatchEffects(plugin, "rtp.effect.presetup", player));
                    }
                });

        TeleportPipelineTask.setupPostActions.add(
                (task, aBoolean) -> {
                    if (!aBoolean) return;
                    PostSetupTeleportEvent event = new PostSetupTeleportEvent(task);
                    Bukkit.getPluginManager().callEvent(event);
                    if (task.player() != null) {
                        if (!Boolean.parseBoolean(
                                parser.getData().getOrDefault(PerformanceKeys.effectParsing, false).toString()))
                            return;
                        Player player = resolveBukkitPlayer(task.player().uuid(), "postsetup");
                        if (player == null) return;
                        RTP.getInstance()
                                .miscAsyncTasks
                                .add(() -> dispatchEffects(plugin, "rtp.effect.postsetup", player));
                    }
                });

        TeleportPipelineTask.loadPreActions.add(
                task -> {
                    PreLoadChunksEvent event = new PreLoadChunksEvent(task);
                    Bukkit.getPluginManager().callEvent(event);

                    if (task.player() != null) {
                        if (!Boolean.parseBoolean(
                                parser.getData().getOrDefault(PerformanceKeys.effectParsing, false).toString()))
                            return;
                        Player player = resolveBukkitPlayer(task.player().uuid(), "preload");
                        if (player == null) return;
                        RTP.getInstance()
                                .miscAsyncTasks
                                .add(() -> dispatchEffects(plugin, "rtp.effect.preload", player));
                    }
                });

        TeleportPipelineTask.loadPostActions.add(
                task -> {
                    PostLoadChunksEvent event = new PostLoadChunksEvent(task);
                    Bukkit.getPluginManager().callEvent(event);

                    if (task.player() != null) {
                        if (!Boolean.parseBoolean(
                                parser.getData().getOrDefault(PerformanceKeys.effectParsing, false).toString()))
                            return;
                        Player player = resolveBukkitPlayer(task.player().uuid(), "postload");
                        if (player == null) return;
                        RTP.getInstance()
                                .miscAsyncTasks
                                .add(() -> dispatchEffects(plugin, "rtp.effect.postload", player));
                    }
                });

        TeleportPipelineTask.teleportPreActions.add(
                task -> {
                    PreTeleportEvent event = new PreTeleportEvent(task);
                    Bukkit.getPluginManager().callEvent(event);

                    if (task.player() != null) {
                        if (!Boolean.parseBoolean(
                                parser.getData().getOrDefault(PerformanceKeys.effectParsing, false).toString()))
                            return;
                        Player player = resolveBukkitPlayer(task.player().uuid(), "preteleport");
                        if (player == null) return;
                        RTP.getInstance()
                                .miscAsyncTasks
                                .add(() -> dispatchEffects(plugin, "rtp.effect.preteleport", player));
                    }
                });

        TeleportPipelineTask.teleportPostActions.add(
                task -> {
                    PostTeleportEvent event = new PostTeleportEvent(task);
                    Bukkit.getPluginManager().callEvent(event);

                    ConfigParser<MessagesKeys> lang =
                            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);

                    if (task.player() != null) {
                        Player player = resolveBukkitPlayer(task.player().uuid(), "postteleport-title");
                        if (player == null) return;

                        /*
                         * Title/subtitle/actionbar must be dispatched on the player's region/main
                         * thread. Player.sendTitle from an async thread is silently dropped on modern
                         * Spigot/Paper and throws on Folia, which is why titles weren't appearing
                         * in-game. Resolve config values up-front (cheap, thread-safe) and hand the
                         * Bukkit API calls to the scheduler.
                         */
                        String title = lang.getConfigValue(MessagesKeys.title, "").toString();
                        String subtitle = lang.getConfigValue(MessagesKeys.subtitle, "").toString();

                        int fadeIn = lang.getNumber(MessagesKeys.fadeIn, 0).intValue();
                        int stay = lang.getNumber(MessagesKeys.stay, 0).intValue();
                        int fadeOut = lang.getNumber(MessagesKeys.fadeOut, 0).intValue();

                        String actionbar = lang.getConfigValue(MessagesKeys.actionbar, "").toString();

                        RTP.scheduler.runTask(() -> {
                            SendMessage.title(player, title, subtitle, fadeIn, stay, fadeOut);
                            SendMessage.actionbar(player, actionbar);
                        });

                        ConfigParser<ConfigKeys> configParser =
                                (ConfigParser<ConfigKeys>) RTP.configs.getParser(ConfigKeys.class);

                        /*
                         * Folia note: command dispatch (Bukkit.dispatchCommand and player.performCommand)
                         * is handled by the global region. Region/entity threads throw
                         * IllegalStateException("Dispatching command async"). Always route to the
                         * global region scheduler via RTP.scheduler.runTask(Runnable).
                         * Collect all commands and dispatch them in a single scheduled task to
                         * reduce per-command scheduling overhead.
                         */
                        List<String> consoleCommandsToRun = new ArrayList<>();
                        Object consoleCommandsObj =
                                configParser.getConfigValue(ConfigKeys.consoleCommands, new ArrayList<>());
                        if (consoleCommandsObj instanceof List<?> consoleCommands) {
                            for (Object cmd : consoleCommands) {
                                if (cmd == null) continue;
                                String command = cmd.toString().replace("[player]", player.getName());
                                if (command.isBlank()) continue;
                                consoleCommandsToRun.add(command);
                            }
                        }

                        List<String> playerCommandsToRun = new ArrayList<>();
                        Object playerCommandsObj =
                                configParser.getConfigValue(ConfigKeys.playerCommands, new ArrayList<>());
                        if (playerCommandsObj instanceof List<?> playerCommands) {
                            for (Object cmd : playerCommands) {
                                if (cmd == null) continue;
                                String command = cmd.toString().replace("[player]", player.getName());
                                if (command.isBlank()) continue;
                                playerCommandsToRun.add(command);
                            }
                        }

                        if (!consoleCommandsToRun.isEmpty() || !playerCommandsToRun.isEmpty()) {
                            RTP.scheduler.runTask(() -> {
                                for (String c : consoleCommandsToRun) {
                                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
                                }
                                for (String c : playerCommandsToRun) {
                                    player.performCommand(c);
                                }
                            });
                        }
                    }

                    if (task.player() != null) {
                        boolean effectParsing;
                        Object data = parser.getData(PerformanceKeys.effectParsing);
                        if (data instanceof Boolean) effectParsing = (Boolean) data;
                        else if (data == null) {
                            effectParsing = false;
                        } else {
                            effectParsing = Boolean.parseBoolean(data.toString());
                            parser.set(PerformanceKeys.effectParsing, effectParsing);
                        }

                        if (!effectParsing) return;

                        Player player = resolveBukkitPlayer(task.player().uuid(), "postteleport");
                        if (player == null) return;
                        RTP.getInstance()
                                .miscAsyncTasks
                                .add(() -> dispatchEffects(plugin, "rtp.effect.postteleport", player));
                    }
                });

        RTPTeleportCancel.postActions.add(
                task -> {
                    UUID uuid = task.getPlayerId();
                    Player player = resolveBukkitPlayer(uuid, "cancel");

                    if (player == null) return;

                    TeleportCancelEvent event = new TeleportCancelEvent(uuid);
                    Bukkit.getPluginManager().callEvent(event);

                    RTP.getInstance()
                            .miscAsyncTasks
                            .add(
                                    () -> {
                                        if (!Boolean.parseBoolean(
                                                parser
                                                        .getData()
                                                        .getOrDefault(PerformanceKeys.effectParsing, false)
                                                        .toString())) return;
                                        dispatchEffects(plugin, "rtp.effect.cancel", player);
                                    });
                });

        Region.onPlayerQueuePush.add(
                (region, uuid) -> {
                    Player player = resolveBukkitPlayer(uuid, "queuepush");
                    if (player == null) return;

                    PlayerQueuePushEvent event = new PlayerQueuePushEvent(region, uuid);
                    Bukkit.getPluginManager().callEvent(event);

                    RTP.getInstance()
                            .miscAsyncTasks
                            .add(
                                    () -> {
                                        if (!Boolean.parseBoolean(
                                                parser
                                                        .getData()
                                                        .getOrDefault(PerformanceKeys.effectParsing, false)
                                                        .toString())) return;
                                        dispatchEffects(plugin, "rtp.effect.queuepush", player);
                                    });
                });

        Region.onPlayerQueuePop.add(
                (region, uuid) -> {
                    Player player = resolveBukkitPlayer(uuid, "queuepop");
                    if (player == null) return;

                    PlayerQueuePopEvent event = new PlayerQueuePopEvent(region, uuid);
                    Bukkit.getPluginManager().callEvent(event);

                    RTP.getInstance()
                            .miscAsyncTasks
                            .add(
                                    () -> {
                                        if (!Boolean.parseBoolean(
                                                parser
                                                        .getData()
                                                        .getOrDefault(PerformanceKeys.effectParsing, false)
                                                        .toString())) return;
                                        dispatchEffects(plugin, "rtp.effect.queuepop", player);
                                    });
                });
    }
}
