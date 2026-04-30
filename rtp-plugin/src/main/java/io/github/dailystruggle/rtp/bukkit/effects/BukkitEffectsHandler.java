package io.github.dailystruggle.rtp.bukkit.effects;

import io.github.dailystruggle.effectsapi.EffectFactory;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
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
import io.github.dailystruggle.rtp.spigot.tools.SendMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
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

    public static void setupEffects(RTPBukkitPlugin plugin) {
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
                                .add(
                                        () -> {
                                            EffectFactory.buildEffects(
                                                            "rtp.effect.presetup", player.getEffectivePermissions())
                                                    .forEach(
                                                            effect -> {
                                                                effect.setTarget(player);
                                                                effect.run();
                                                            });
                                        });
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
                                .add(
                                        () -> {
                                            EffectFactory.buildEffects(
                                                            "rtp.effect.postsetup", player.getEffectivePermissions())
                                                    .forEach(
                                                            effect -> {
                                                                effect.setTarget(player);
                                                                effect.run();
                                                            });
                                        });
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
                                .add(
                                        () -> {
                                            EffectFactory.buildEffects(
                                                            "rtp.effect.presetup", player.getEffectivePermissions())
                                                    .forEach(
                                                            effect -> {
                                                                effect.setTarget(player);
                                                                effect.run();
                                                            });
                                        });
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
                                .add(
                                        () -> {
                                            EffectFactory.buildEffects(
                                                            "rtp.effect.postload", player.getEffectivePermissions())
                                                    .forEach(
                                                            effect -> {
                                                                effect.setTarget(player);
                                                                effect.run();
                                                            });
                                        });
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
                                .add(
                                        () -> {
                                            EffectFactory.buildEffects(
                                                            "rtp.effect.preteleport", player.getEffectivePermissions())
                                                    .forEach(
                                                            effect -> {
                                                                effect.setTarget(player);
                                                                effect.run();
                                                            });
                                        });
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
                                .add(
                                        () -> {
                                            EffectFactory.buildEffects(
                                                            "rtp.effect.postteleport", player.getEffectivePermissions())
                                                    .forEach(
                                                            effect -> {
                                                                effect.setTarget(player);
                                                                effect.run();
                                                            });
                                        });
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
                                        EffectFactory.buildEffects(
                                                        "rtp.effect.cancel", player.getEffectivePermissions())
                                                .forEach(
                                                        effect -> {
                                                            effect.setTarget(player);
                                                            effect.run();
                                                        });
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
                                        EffectFactory.buildEffects(
                                                        "rtp.effect.queuepush", player.getEffectivePermissions())
                                                .forEach(
                                                        effect -> {
                                                            effect.setTarget(player);
                                                            effect.run();
                                                        });
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
                                        EffectFactory.buildEffects(
                                                        "rtp.effect.queuepop", player.getEffectivePermissions())
                                                .forEach(
                                                        effect -> {
                                                            effect.setTarget(player);
                                                            effect.run();
                                                        });
                                    });
                });
    }
}
