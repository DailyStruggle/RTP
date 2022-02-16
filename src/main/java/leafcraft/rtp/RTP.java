package leafcraft.rtp;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.API.Commands.SubCommand;
import leafcraft.rtp.API.RTPAPI;
import leafcraft.rtp.API.selection.SelectionAPI;
import leafcraft.rtp.commands.*;
import leafcraft.rtp.customEventListeners.*;
import leafcraft.rtp.spigotEventListeners.*;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.SendMessage;
import leafcraft.rtp.tools.TPS;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import leafcraft.rtp.tools.softdepends.FactionsChecker;
import leafcraft.rtp.tools.softdepends.LandsChecker;
import leafcraft.rtp.tools.softdepends.PAPI_expansion;
import leafcraft.rtp.tools.softdepends.VaultChecker;
import org.bstats.bukkit.Metrics;
import org.bukkit.*;
import org.bukkit.command.CommandExecutor;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.*;

/**
 * A Random Teleportation Spigot/Paper plugin, optimized for operators
 */
@SuppressWarnings("unused")
public final class RTP extends JavaPlugin {
    private static RTPCmd rtpCmd;
    private static final SubCommand subCommands = new SubCommandImpl("rtp.use", null);
    private static Configs configs = null;
    private static Cache cache = null;
    private static RTP plugin = null;
    private static Metrics metrics;

    @Override
    public void onEnable() {
        PaperLib.suggestPaper(this);
        metrics = new Metrics(this,12277);

        RTP.plugin = this;
        RTP.configs = new Configs();
        RTP.cache = new Cache();

        //initialize server version
        RTPAPI.getServerIntVersion();

        try {
            Objects.requireNonNull(getCommand("wild")).setExecutor(rtpCmd);
            Objects.requireNonNull(getCommand("rtp")).setExecutor(rtpCmd);
        }
        catch (NullPointerException ignored) { }

        initDefaultCommands();
        rtpCmd = new RTPCmd(subCommands);
        try {
            TabComplete tabComplete = new TabComplete(subCommands);
            Objects.requireNonNull(getCommand("rtp")).setTabCompleter(tabComplete);
            Objects.requireNonNull(getCommand("wild")).setTabCompleter(tabComplete);
            Objects.requireNonNull(getCommand("rtp")).setExecutor(rtpCmd);
            Objects.requireNonNull(getCommand("wild")).setExecutor(rtpCmd);
        }
        catch (NullPointerException ignored) { }

        if(configs.config.onEventParsing) {
            getServer().getPluginManager().registerEvents(new OnEvent(),this);
        }

        if(configs.config.effectParsing) {
            getServer().getPluginManager().registerEvents(new TeleportEffects(),this);
        }

        if(configs.config.checkChunks) {
            getServer().getPluginManager().registerEvents(new OnChunkLoad(),this);
        }

        getServer().getPluginManager().registerEvents(new OnPlayerDeath(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerRespawn(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerJoin(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerChangeWorld(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerMove(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerTeleport(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerDamage(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerQuit(),this);
        getServer().getPluginManager().registerEvents(new OnRandomPreTeleport(),this);
        getServer().getPluginManager().registerEvents(new OnRandomTeleport(),this);
        getServer().getPluginManager().registerEvents(new OnTeleportCancel(),this);
        getServer().getPluginManager().registerEvents(new PlayerQueueListener(),this);

        Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(this, new TPS(), 100L, 1L);

        VaultChecker.setupEconomy();
        VaultChecker.setupPermissions();

        if(Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PAPI_expansion(this,configs,cache).register();
        }

        LandsChecker.landsSetup(this);
    }

    @Override
    public void onDisable() {
//        onChunkLoad.shutdown();
        if(cache != null) {
            cache.shutdown();
        }
        cache = null;
        configs = null;
        metrics = null;

        super.onDisable();
    }

    /**
     * get a region by name
     * @param regionName - name of region
     * @return region by that name, or null if none
     */
    @Nullable
    public static TeleportRegion getRegion(String regionName) {
        Map<String,String> params = new HashMap<>();
        params.put("region",regionName);

        String worldName = (String) configs.regions.getRegionSetting(regionName,"world","");
        if (worldName == null || worldName.equals("") || !configs.worlds.checkWorldExists(worldName)) {
            return null;
        }

        RandomSelectParams randomSelectParams = new RandomSelectParams(Objects.requireNonNull(Bukkit.getWorld(worldName)),params);
        if(!cache.permRegions.containsKey(randomSelectParams)) return null;
        return cache.permRegions.get(randomSelectParams);
    }

    /**
     * add or update a region by name
     * @param regionName - name of region
     * @param params - mapped parameters, based on parameters in regions.yml
     * @return the corresponding TeleportRegion
     */
    @Nullable
    public static TeleportRegion setRegion(String regionName, Map<String,String> params) {
        params.put("region",regionName);

        String worldName = (String) configs.regions.getRegionSetting(regionName,"world","");
        if (worldName == null || worldName.equals("") || !configs.worlds.checkWorldExists(worldName)) {
            return null;
        }

        RandomSelectParams randomSelectParams = new RandomSelectParams(Objects.requireNonNull(Bukkit.getWorld(worldName)),params);
        if(cache.permRegions.containsKey(randomSelectParams)) {
            cache.permRegions.get(randomSelectParams).shutdown();
        }

        configs.regions.setRegion(regionName,randomSelectParams);
        return cache.permRegions.put(randomSelectParams,
                new TeleportRegion(regionName,randomSelectParams.params));
    }

    /**
     * addLocationCheck
     * add any location check, after plugin initialization
     * @param methodHandle - a method that takes an org.bukkit.Location and returns a boolean
     */
    public static void addLocationCheck(@NotNull MethodHandle methodHandle) {
        configs.addLocationCheck(methodHandle);
    }

    /**
     * setSubCommand
     * set subommand such that /rtp [name] calls the command executor given by getCommandExecutor
     * @param name - command name
     * @param subCommand - container for command
     */
    public static void setSubCommand(@NotNull String name, @NotNull SubCommand subCommand) {
        rtpCmd.setSubCommand(name,subCommand);
    }

    /**
     * setSubCommand
     * set subommand such that /rtp [name] calls the command executor given, if the player has permission
     * @param name - command name
     * @param permission - command permission node
     * @param commandExecutor - executor to call
     */
    public static void setSubCommand(@NotNull String name, @NotNull String permission, @NotNull CommandExecutor commandExecutor) {
        SubCommand subCommand = new SubCommandImpl(permission,commandExecutor);
        rtpCmd.setSubCommand(name,subCommand);
    }

    @NotNull
    public static RTP getPlugin() {
        return plugin;
    }

    @NotNull
    public static Configs getConfigs() {
        return configs;
    }

    @NotNull
    public static Cache getCache() {
        return cache;
    }

    private static void initDefaultCommands() {
        //load rtp commands and permission nodes into map
        subCommands.setSubParam("world", "rtp.world", SubCommand.ParamType.WORLD);
        subCommands.setSubParam("region", "rtp.region", SubCommand.ParamType.REGION);
        subCommands.setSubParam("player", "rtp.other", SubCommand.ParamType.PLAYER);
        subCommands.setSubParam("shape", "rtp.world", SubCommand.ParamType.SHAPE);
        subCommands.setSubParam("radius", "rtp.params", SubCommand.ParamType.NONE);
        subCommands.setSubParam("centerRadius", "rtp.params", SubCommand.ParamType.NONE);
        subCommands.setSubParam("centerX", "rtp.params", SubCommand.ParamType.NONE);
        subCommands.setSubParam("centerZ", "rtp.params", SubCommand.ParamType.NONE);
        subCommands.setSubParam("weight", "rtp.params", SubCommand.ParamType.NONE);
        subCommands.setSubParam("minY", "rtp.params", SubCommand.ParamType.COORDINATE);
        subCommands.setSubParam("maxY", "rtp.params", SubCommand.ParamType.COORDINATE);
        subCommands.setSubParam("requireSkyLight", "rtp.params", SubCommand.ParamType.BOOLEAN);
        subCommands.setSubParam("worldBorderOverride", "rtp.params", SubCommand.ParamType.BOOLEAN);
        subCommands.setSubParam("biome", "rtp.biome", SubCommand.ParamType.BIOME);
        subCommands.setSubParam("near", "rtp.near", SubCommand.ParamType.PLAYER);

        subCommands.setSubCommand("help", new SubCommandImpl("rtp.see", new Help()));
        subCommands.setSubCommand("reload", new SubCommandImpl("rtp.reload", new Reload()));
        subCommands.setSubCommand("setRegion", new SubCommandImpl("rtp.setRegion", new SetRegion()));
        subCommands.setSubCommand("setWorld", new SubCommandImpl("rtp.setWorld", new SetWorld()));
        subCommands.setSubCommand("fill", new SubCommandImpl("rtp.fill", new Fill()));
        subCommands.setSubCommand("info", new SubCommandImpl("rtp.info", new Info()));

        SubCommandImpl setRegion = (SubCommandImpl) Objects.requireNonNull(subCommands.getSubCommand("setRegion"));
        setRegion.setSubParam("region", "rtp.setRegion", SubCommand.ParamType.REGION);
        setRegion.setSubParam("world", "rtp.setRegion", SubCommand.ParamType.WORLD);
        setRegion.setSubParam("shape", "rtp.setRegion", SubCommand.ParamType.SHAPE);
        setRegion.setSubParam("mode", "rtp.setRegion", SubCommand.ParamType.MODE);
        setRegion.setSubParam("radius", "rtp.setRegion", SubCommand.ParamType.NONE);
        setRegion.setSubParam("centerRadius", "rtp.setRegion", SubCommand.ParamType.NONE);
        setRegion.setSubParam("centerX", "rtp.setRegion", SubCommand.ParamType.COORDINATE);
        setRegion.setSubParam("centerZ", "rtp.setRegion", SubCommand.ParamType.COORDINATE);
        setRegion.setSubParam("weight", "rtp.setRegion", SubCommand.ParamType.NONE);
        setRegion.setSubParam("minY", "rtp.setRegion", SubCommand.ParamType.COORDINATE);
        setRegion.setSubParam("maxY", "rtp.setRegion", SubCommand.ParamType.COORDINATE);
        setRegion.setSubParam("requireSkyLight", "rtp.setRegion", SubCommand.ParamType.BOOLEAN);
        setRegion.setSubParam("requirePermission", "rtp.setRegion", SubCommand.ParamType.BOOLEAN);
        setRegion.setSubParam("worldBorderOverride", "rtp.setRegion", SubCommand.ParamType.BOOLEAN);
        setRegion.setSubParam("uniquePlacements", "rtp.setRegion", SubCommand.ParamType.BOOLEAN);
        setRegion.setSubParam("expand", "rtp.setRegion", SubCommand.ParamType.BOOLEAN);
        setRegion.setSubParam("queueLen", "rtp.setRegion", SubCommand.ParamType.NONE);
        setRegion.setSubParam("price", "rtp.setRegion", SubCommand.ParamType.NONE);

        SubCommandImpl setWorld = (SubCommandImpl) Objects.requireNonNull(subCommands.getSubCommand("setWorld"));
        setWorld.setSubParam("world", "rtp.setWorld", SubCommand.ParamType.WORLD);
        setWorld.setSubParam("name", "rtp.setWorld", SubCommand.ParamType.NONE);
        setWorld.setSubParam("region", "rtp.setWorld", SubCommand.ParamType.REGION);
        setWorld.setSubParam("override", "rtp.setWorld", SubCommand.ParamType.WORLD);

        SubCommandImpl fill = (SubCommandImpl) Objects.requireNonNull(subCommands.getSubCommand("fill"));
        fill.setSubParam("region", "rtp.fill", SubCommand.ParamType.REGION);
        fill.setSubCommand("start", new SubCommandImpl("rtp.fill", null)); // null because this is handled by Fill
        Objects.requireNonNull(fill.getSubCommand("start")).setSubParam("region", "rtp.fill", SubCommand.ParamType.REGION);
        fill.setSubCommand("cancel", new SubCommandImpl("rtp.fill", null));
        Objects.requireNonNull(fill.getSubCommand("cancel")).setSubParam("region", "rtp.fill", SubCommand.ParamType.REGION);
        fill.setSubCommand("pause", new SubCommandImpl("rtp.fill", null));
        Objects.requireNonNull(fill.getSubCommand("pause")).setSubParam("region", "rtp.fill", SubCommand.ParamType.REGION);
        fill.setSubCommand("resume", new SubCommandImpl("rtp.fill", null));
        Objects.requireNonNull(fill.getSubCommand("resume")).setSubParam("region", "rtp.fill", SubCommand.ParamType.REGION);

        SubCommandImpl info = (SubCommandImpl) Objects.requireNonNull(subCommands.getSubCommand("info"));
        info.setSubParam("region", "rtp.info", SubCommand.ParamType.REGION);
        info.setSubParam("world", "rtp.info", SubCommand.ParamType.WORLD);

        if (configs.config.effectParsing) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(RTP.getPlugin(), () -> {
                List<Permission> permissionList = new ArrayList<>();
                
                //adding 600+ sounds takes too long at startup
                try {
                    permissionList.add(new Permission("rtp.effect.command.sound"));
                    permissionList.add(new Permission("rtp.effect.teleport.sound"));
                    permissionList.add(new Permission("rtp.effect.preTeleport.sound"));

                    for (FireworkEffect.Type type : FireworkEffect.Type.values()) {
                        permissionList.add(new Permission("rtp.effect.command.firework." + type.name()));
                        permissionList.add(new Permission("rtp.effect.teleport.firework." + type.name()));
                        permissionList.add(new Permission("rtp.effect.preTeleport.firework." + type.name()));
                    }

                    for (Instrument instrument : Instrument.values()) {
                        permissionList.add(new Permission("rtp.effect.command.note." + instrument.name()));
                        permissionList.add(new Permission("rtp.effect.teleport.note." + instrument.name()));
                        permissionList.add(new Permission("rtp.effect.preTeleport.note." + instrument.name()));
                    }

                    if(RTPAPI.getServerIntVersion() > 8) {
                        for (Particle particle : Particle.values()) {
                            permissionList.add(new Permission("rtp.effect.command.particle." + particle.name()));
                            permissionList.add(new Permission("rtp.effect.teleport.particle." + particle.name()));
                            permissionList.add(new Permission("rtp.effect.preTeleport.particle." + particle.name()));
                        }
                    }

                    for (PotionEffectType effect : PotionEffectType.values()) {
                        permissionList.add(new Permission("rtp.effect.command.potion." + effect.getName()));
                        permissionList.add(new Permission("rtp.effect.teleport.potion." + effect.getName()));
                        permissionList.add(new Permission("rtp.effect.preTeleport.potion." + effect.getName()));
                    }
                }
                catch (NullPointerException | IllegalArgumentException permissionException) {
                    SendMessage.sendMessage(Bukkit.getConsoleSender(),"[RTP] - failed to initialize effect permissions. This will not affect gameplay.");
                    return;
                }

                try {
                    for(Permission permission : permissionList) {
                        Bukkit.getPluginManager().addPermission(permission);
                    }
                }
                catch (NullPointerException | IllegalArgumentException  permissionException) {
                    SendMessage.sendMessage(Bukkit.getConsoleSender(),"[RTP] - failed to initialize effect permissions. This will not affect gameplay.");
                    return;
                }

                permissionList.clear();
            },40);
        }
    }
}
