package io.github.dailystruggle.rtp.bukkit;

import io.github.dailystruggle.effectsapi.EffectFactory;
import io.github.dailystruggle.effectsapi.EffectsAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.bukkit.commands.RTPCmdBukkit;
import io.github.dailystruggle.rtp.bukkit.events.*;
import io.github.dailystruggle.rtp.bukkit.spigotListeners.*;
import io.github.dailystruggle.rtp.spigot.server.AsyncTeleportProcessing;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.ChunkUnloadProcessor;
import io.github.dailystruggle.rtp.spigot.server.DatabaseProcessing;
import io.github.dailystruggle.rtp.spigot.server.FillTaskProcessing;
import io.github.dailystruggle.rtp.spigot.server.SyncTeleportProcessing;
import io.github.dailystruggle.rtp.spigot.tools.SendMessage;
import io.github.dailystruggle.rtp.bukkit.tools.softdepends.ChunkyBorderChecker;
import io.github.dailystruggle.rtp.bukkit.tools.softdepends.PAPI_expansion;
import io.github.dailystruggle.rtp.bukkit.tools.softdepends.VaultChecker;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.database.options.H2DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.MySQLDatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.PostgreSQLDatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.SQLiteDatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import java.io.File;
import java.nio.file.FileSystemException;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** A Random Teleportation Spigot/Paper plugin, optimized for operators */
@SuppressWarnings("unused")
public final class RTPBukkitPlugin extends JavaPlugin {
  private static final EffectsAPI effectsAPI = null;
  private static RTPBukkitPlugin instance = null;
  private static Metrics metrics;
  public BukkitTask commandTimer = null;
  public BukkitTask commandProcessing = null;

  /**
   * @return the single plugin instance initialized at bukkit startup, faster than bukkit api
   */
  public static RTPBukkitPlugin getInstance() {
    return instance;
  }

  /**
   * bukkit-specific method to find the correct region for the player's location and permissions
   *
   * @param player bukkit player
   * @return region object
   */
  public static Region getRegion(Player player) {
    return RTP.selectionAPI.getRegion(RTP.serverAccessor.getPlayer(player.getUniqueId()));
  }

  boolean isPaper() {
    try {
      Class.forName("io.papermc.paper.configuration.PaperConfigurations");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  boolean isFolia() {
    try {
      Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  /** on class load by bukkit */
  @Override
  public void onLoad() {
    // prepare sqlite capability
    try {
      Class.forName("org.sqlite.JDBC");
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException();
    }
  }

  /** whenever bukkit feels like enabling this plugin */
  @Override
  public void onEnable() {
    metrics = new Metrics(this, 12277);

    if (instance == null) {
      instance = this;
      String version = Bukkit.getBukkitVersion(); // e.g., "1.21-R0.1-SNAPSHOT"

      String accessorClassName;
      String schedulerClassName;

      if (version.contains("26.1")) {
        if (isFolia()) {
          accessorClassName =
              "io.github.dailystruggle.rtp.folia_v26_1_R1.server.ServerAccessorImpl";
          schedulerClassName =
              "io.github.dailystruggle.rtp.folia_v26_1_R1.scheduling.FoliaSchedulerImpl";
        } else if (isPaper()) {
          accessorClassName =
              "io.github.dailystruggle.rtp.paper_v26_1_R1.server.ServerAccessorImpl";
          schedulerClassName =
              "io.github.dailystruggle.rtp.paper_v26_1_R1.scheduling.BukkitSchedulerImpl";
        } else {
          accessorClassName =
              "io.github.dailystruggle.rtp.spigot_v26_1_R1.server.ServerAccessorImpl";
          schedulerClassName =
              "io.github.dailystruggle.rtp.spigot_v26_1_R1.scheduling.BukkitSchedulerImpl";
        }
      } else if (version.contains("1.21")) {
        if (isFolia()) {
          accessorClassName =
              "io.github.dailystruggle.rtp.folia_v1_21_R1.server.ServerAccessorImpl";
          schedulerClassName =
              "io.github.dailystruggle.rtp.folia_v1_21_R1.scheduling.FoliaSchedulerImpl";
        } else if (isPaper()) {
          accessorClassName =
              "io.github.dailystruggle.rtp.paper_v1_21_R1.server.ServerAccessorImpl";
          schedulerClassName =
              "io.github.dailystruggle.rtp.paper_v1_21_R1.scheduling.BukkitSchedulerImpl";
        } else {
          accessorClassName =
              "io.github.dailystruggle.rtp.spigot_v1_21_R1.server.ServerAccessorImpl";
          schedulerClassName =
              "io.github.dailystruggle.rtp.spigot_v1_21_R1.scheduling.BukkitSchedulerImpl";
        }
      } else if (version.contains("1.20")) {
        if (isFolia()) {
          accessorClassName =
              "io.github.dailystruggle.rtp.folia_v1_20_R1.server.ServerAccessorImpl";
          schedulerClassName =
              "io.github.dailystruggle.rtp.folia_v1_20_R1.scheduling.FoliaSchedulerImpl";
        } else if (isPaper()) {
          accessorClassName =
              "io.github.dailystruggle.rtp.paper_v1_20_R1.server.ServerAccessorImpl";
          schedulerClassName =
              "io.github.dailystruggle.rtp.paper_v1_20_R1.scheduling.BukkitSchedulerImpl";
        } else {
          accessorClassName =
              "io.github.dailystruggle.rtp.spigot_v1_20_R1.server.ServerAccessorImpl";
          schedulerClassName =
              "io.github.dailystruggle.rtp.spigot_v1_20_R1.scheduling.BukkitSchedulerImpl";
        }
      } else {
        if (isFolia()) {
          accessorClassName =
              "io.github.dailystruggle.rtp.folia_v1_20_R1.server.ServerAccessorImpl";
          schedulerClassName =
              "io.github.dailystruggle.rtp.folia_v1_20_R1.scheduling.FoliaSchedulerImpl";
        } else if (isPaper()) {
          accessorClassName =
              "io.github.dailystruggle.rtp.paper_v1_20_R1.server.ServerAccessorImpl";
          schedulerClassName =
              "io.github.dailystruggle.rtp.paper_v1_20_R1.scheduling.BukkitSchedulerImpl";
        } else {
          accessorClassName =
              "io.github.dailystruggle.rtp.spigot_v1_20_R1.server.ServerAccessorImpl";
          schedulerClassName =
              "io.github.dailystruggle.rtp.spigot_v1_20_R1.scheduling.BukkitSchedulerImpl";
        }
      }

      try {
        RTP.serverAccessor =
            (io.github.dailystruggle.rtp.api.server.RTPServerAccessor)
                Class.forName(accessorClassName).getDeclaredConstructor().newInstance();
        RTP.scheduler =
            (io.github.dailystruggle.rtp.api.scheduling.RTPScheduler)
                Class.forName(schedulerClassName)
                    .getDeclaredConstructor(JavaPlugin.class)
                    .newInstance(this);
      } catch (Exception e) {
        e.printStackTrace();
        onDisable();
        return;
      }

      RTP rtp = new RTP(); // constructor updates API instance

      File databaseDirectory = RTP.configs.pluginDirectory;
      databaseDirectory =
          new File(databaseDirectory.getAbsolutePath() + File.separator + "database");
      boolean mkdirs = databaseDirectory.mkdirs();
      if (!mkdirs && !databaseDirectory.exists()) {
        RTP.log(
            Level.SEVERE,
            "unable to make directories",
            new FileSystemException("unable to make directories"));
        onDisable();
        return;
      }

      RTP.configs.reloadConfigs();

      ConfigParser<ConfigKeys> configParser =
          (ConfigParser<ConfigKeys>) RTP.configs.getParser(ConfigKeys.class);
      Map<String, Object> databaseMap = configParser.getMap(ConfigKeys.database);

      String type = String.valueOf(databaseMap.getOrDefault("type", "sqlite"));
      String host = String.valueOf(databaseMap.getOrDefault("host", "127.0.0.1"));
      int port = ((Number) databaseMap.getOrDefault("port", 3306)).intValue();
      String name = String.valueOf(databaseMap.getOrDefault("name", "rtp"));
      String username = String.valueOf(databaseMap.getOrDefault("username", "root"));
      String password = String.valueOf(databaseMap.getOrDefault("password", "password"));

      File dbStateFile = new File(databaseDirectory, ".db_state");
      String previousType;
      if (dbStateFile.exists()) {
        try {
          previousType = new String(java.nio.file.Files.readAllBytes(dbStateFile.toPath())).trim();
        } catch (Exception e) {
          previousType = type;
        }
      } else {
        File teleportDataDir = new File(databaseDirectory, "teleportData");
        String[] list = teleportDataDir.list((dir, filename) -> filename.endsWith(".yml"));
        if (teleportDataDir.exists()
            && teleportDataDir.isDirectory()
            && list != null
            && list.length > 0) {
          previousType = "yaml";
        } else {
          previousType = type;
        }
      }

      switch (type.toLowerCase()) {
        case "yaml":
          rtp.databaseAccessor = new YamlFileDatabase(databaseDirectory);
          break;
        case "h2":
          rtp.databaseAccessor = new H2DatabaseAccessor();
          break;
        case "mysql":
          rtp.databaseAccessor = new MySQLDatabaseAccessor(host, port, name, username, password);
          break;
        case "postgresql":
          rtp.databaseAccessor =
              new PostgreSQLDatabaseAccessor(host, port, name, username, password);
          break;
        case "sqlite":
        default:
          rtp.databaseAccessor =
              new SQLiteDatabaseAccessor(
                  "jdbc:sqlite:" + databaseDirectory.getAbsolutePath() + File.separator + "RTP.db");
          break;
      }

      RTP.handleMigration(previousType, type);
      try {
        java.nio.file.Files.write(dbStateFile.toPath(), type.getBytes());
      } catch (Exception e) {
        e.printStackTrace();
      }

      RTP.configs.reloadRegions();

      RTP.scheduler.runTaskLater(() -> RTP.getInstance().databaseAccessor.startup(), 1);
    }

    ChunkyBorderChecker.loadChunky();
    RTP.getInstance().startupTasks.execute(Long.MAX_VALUE);

    RTPCmdBukkit mainCommand = new RTPCmdBukkit(this);
    RTP.baseCommand = mainCommand;


    org.bukkit.command.PluginCommand rtpCommand = getCommand("rtp");
    if (rtpCommand != null) {
      rtpCommand.setExecutor(mainCommand);
      rtpCommand.setTabCompleter(mainCommand);

    } else {

    }

    org.bukkit.command.PluginCommand wildCommand = getCommand("wild");
    if (wildCommand != null) {
      wildCommand.setExecutor(mainCommand);
      wildCommand.setTabCompleter(mainCommand);

    } else {

    }

    RTP.scheduler.runTaskLater(
        () -> {
          while (RTP.getInstance().startupTasks.size() > 0) {
            RTP.getInstance().startupTasks.execute(Long.MAX_VALUE);
          }
        },
        1);

    RTP.scheduler.runTaskLater(() -> RTP.serverAccessor.start(this), 1);
    //        setupEffects();
    //        if( RTP.serverAccessor.getServerIntVersion()>12 ) {
    //            BukkitTask task = Bukkit.getScheduler().runTask( this, this::setupEffects );
    //        }
    RTP.scheduler.runTaskLater(this::setupBukkitEvents, 1);
    RTP.scheduler.runTaskLater(this::setupIntegrations, 1);
    RTP.scheduler.runTaskLater(this::setupEffects, 1);

    if (!isFolia()) {
      RTP.scheduler.runTaskTimer(new ChunkUnloadProcessor(), 1, 1);
    }

    SendMessage.sendMessage(Bukkit.getConsoleSender(), "");

    while (RTP.getInstance().startupTasks.size() > 0) {
      RTP.getInstance().startupTasks.execute(Long.MAX_VALUE);
    }

    if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
      new PAPI_expansion().register();
    }

  }

  /** whenever bukkit feels like disabling this plugin */
  @Override
  public void onDisable() {
    if (commandTimer != null) commandTimer.cancel();
    if (commandProcessing != null) commandProcessing.cancel();

    try {
      AsyncTeleportProcessing.kill();
    } catch (NoClassDefFoundError ignored) {
      // catch plugin replaced, no use for old logs
    }
    try {
      SyncTeleportProcessing.kill();
    } catch (NoClassDefFoundError ignored) {
      // catch plugin replaced, no use for old logs
    }
    try {
      FillTaskProcessing.kill();
    } catch (NoClassDefFoundError ignored) {
      // catch plugin replaced, no use for old logs
    }
    try {
      DatabaseProcessing.kill();
    } catch (NoClassDefFoundError ignored) {
      // catch plugin replaced, no use for old logs
    }

    //        onChunkLoad.shutdown();
    metrics = null;

    try {
      RTP.stop();
    } catch (NoClassDefFoundError ignored) {
      // catch plugin replaced, no use for old logs
    }

    List<BukkitTask> pendingTasks =
        Bukkit.getScheduler().getPendingTasks().stream()
            .filter(
                b ->
                    b.getOwner().getName().equalsIgnoreCase("RTP")
                        && !b.isSync()
                        && !b.isCancelled())
            .collect(Collectors.toList());
    for (BukkitTask pendingTask : pendingTasks) {
      pendingTask.cancel();
    }

    try {
      if (RTP.getInstance() != null && RTP.getInstance().databaseAccessor != null) {
        Map<String, Object> referenceData = new HashMap<>();
        referenceData.put("time", System.currentTimeMillis());
        referenceData.put("UUID", new UUID(0, 0).toString());
        RTP.getInstance().databaseAccessor.setValue("referenceData", referenceData);
        RTP.getInstance().databaseAccessor.processQueries(Long.MAX_VALUE);
      }
    } catch (NoClassDefFoundError ignored) {
      // catch plugin replaced, no use for old logs
    }

    super.onDisable();
  }

  private void setupBukkitEvents() {
    ConfigParser<PerformanceKeys> performance =
        (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);

    boolean onEventParsing;
    Object o = performance.getConfigValue(PerformanceKeys.onEventParsing, false);
    if (o instanceof Boolean) onEventParsing = (Boolean) o;
    else onEventParsing = Boolean.parseBoolean(o.toString());

    if (onEventParsing) Bukkit.getPluginManager().registerEvents(new OnEventTeleports(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerChangeWorld(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerDamage(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerJoin(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerMove(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerQuit(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerRespawn(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerTeleport(), this);
    Bukkit.getPluginManager().registerEvents(new OnWorldLoadUnload(), this);
    if (RTP.serverAccessor.getServerIntVersion() < 13)
      Bukkit.getPluginManager().registerEvents(new OnChunkUnload(), this);

    if (RTP.serverAccessor.getServerIntVersion() > 12) EffectsAPI.init(this);
  }

  private void setupEffects() {
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
            Player player = Bukkit.getPlayer(task.player().uuid());
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
            Player player = Bukkit.getPlayer(task.player().uuid());
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
            Player player = Bukkit.getPlayer(task.player().uuid());
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
            Player player = Bukkit.getPlayer(task.player().uuid());
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
            Player player = Bukkit.getPlayer(task.player().uuid());
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
            Player player = Bukkit.getPlayer(task.player().uuid());
            if (player == null) return;

            RTP.getInstance()
                .miscAsyncTasks
                .add(
                    () -> {
                      String title = lang.getConfigValue(MessagesKeys.title, "").toString();
                      String subtitle = lang.getConfigValue(MessagesKeys.subtitle, "").toString();

                      int fadeIn = lang.getNumber(MessagesKeys.fadeIn, 0).intValue();
                      int stay = lang.getNumber(MessagesKeys.stay, 0).intValue();
                      int fadeOut = lang.getNumber(MessagesKeys.fadeOut, 0).intValue();

                      SendMessage.title(player, title, subtitle, fadeIn, stay, fadeOut);

                      String actionbar = lang.getConfigValue(MessagesKeys.actionbar, "").toString();
                      SendMessage.actionbar(player, actionbar);
                    });
          }

          if (task.player() != null) {
            boolean effectParsing;
            Object data = parser.getData(PerformanceKeys.effectParsing);
            if (data instanceof Boolean) effectParsing = (Boolean) data;
            else {
              effectParsing = Boolean.parseBoolean(data.toString());
              parser.set(PerformanceKeys.effectParsing, effectParsing);
            }

            if (!effectParsing) return;

            Player player = Bukkit.getPlayer(task.player().uuid());
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
          Player player = Bukkit.getPlayer(uuid);

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
          Player player = Bukkit.getPlayer(uuid);
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
          Player player = Bukkit.getPlayer(uuid);
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

    //        RTP.getInstance().miscAsyncTasks.add( () -> {
    //            if ( Boolean.parseBoolean( parser.getData().getOrDefault(
    // PerformanceKeys.effectParsing, false ).toString()) ) {
    //                EffectFactory.addPermissions( "rtp.effect.presetup" );
    //            }
    //        } );
    //
    //        RTP.getInstance().miscSyncTasks.add( () -> {
    //            if ( Boolean.parseBoolean( parser.getData().getOrDefault(
    // PerformanceKeys.effectParsing, false ).toString()) ) {
    //                EffectFactory.addPermissions( "rtp.effect.postsetup" );
    //            }
    //        } );
    //
    //        RTP.getInstance().miscSyncTasks.add( () -> {
    //            if ( Boolean.parseBoolean( parser.getData().getOrDefault(
    // PerformanceKeys.effectParsing, false ).toString()) ) {
    //                EffectFactory.addPermissions( "rtp.effect.preload" );
    //            }
    //        } );
    //
    //        RTP.getInstance().miscSyncTasks.add( () -> {
    //            if ( Boolean.parseBoolean( parser.getData().getOrDefault(
    // PerformanceKeys.effectParsing, false ).toString()) ) {
    //                EffectFactory.addPermissions( "rtp.effect.postload" );
    //            }
    //        } );
    //
    //        RTP.getInstance().miscSyncTasks.add( () -> {
    //            if ( Boolean.parseBoolean( parser.getData().getOrDefault(
    // PerformanceKeys.effectParsing, false ).toString()) ) {
    //                EffectFactory.addPermissions( "rtp.effect.preteleport" );
    //            }
    //        } );
    //
    //        RTP.getInstance().miscSyncTasks.add( () -> {
    //            if ( Boolean.parseBoolean( parser.getData().getOrDefault(
    // PerformanceKeys.effectParsing, false ).toString()) ) {
    //                EffectFactory.addPermissions( "rtp.effect.postteleport" );
    //            }
    //        } );
    //
    //        RTP.getInstance().miscSyncTasks.add( () -> {
    //            if ( Boolean.parseBoolean( parser.getData().getOrDefault(
    // PerformanceKeys.effectParsing, false ).toString()) ) {
    //                EffectFactory.addPermissions( "rtp.effect.cancel" );
    //            }
    //        } );
    //
    //        RTP.getInstance().miscSyncTasks.add( () -> {
    //            if ( Boolean.parseBoolean( parser.getData().getOrDefault(
    // PerformanceKeys.effectParsing, false ).toString()) ) {
    //                EffectFactory.addPermissions( "rtp.effect.queuepush" );
    //            }
    //        } );
  }

  public void setupIntegrations() {
    if (RTP.economy == null && Bukkit.getServer().getPluginManager().getPlugin("Vault") != null) {
      VaultChecker.setupEconomy();
      VaultChecker.setupPermissions();
      if (VaultChecker.getEconomy() != null) RTP.economy = new VaultChecker();
      else RTP.economy = null;
    }
  }
}
