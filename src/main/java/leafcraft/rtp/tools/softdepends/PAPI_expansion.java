package leafcraft.rtp.tools.softdepends;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class PAPI_expansion extends PlaceholderExpansion{
	private final RTP plugin;
	private final Cache cache;
	private final Configs configs;

	public PAPI_expansion(RTP plugin, Configs configs, Cache cache){
	    this.plugin = plugin;
        this.cache = cache;
        this.configs = configs;
    }

    @Override
    public boolean persist(){
        return true;
    }

	@Override
    public boolean canRegister(){
        return true;
    }

	@Override
	public @NotNull String getAuthor() {
		return plugin.getDescription().getAuthors().toString();
	}

	@Override
	public @NotNull String getIdentifier() {
		return "rtp";
	}

	@Override
    public @NotNull String getVersion(){
        return "1.3.23";
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        return onPlaceholderRequest(player.getPlayer(),params);
    }

	@Override
    public String onPlaceholderRequest(Player player, @NotNull String identifier){
		if(player == null){
            return "";
        }

        // %rtp_player_status%
        if(identifier.equalsIgnoreCase("player_status")){
            if(cache.doTeleports.containsKey(player.getUniqueId())) {
                return configs.lang.getLog("PLAYER_TELEPORTING");
            }
            else if(cache.setupTeleports.containsKey(player.getUniqueId())) {
                return configs.lang.getLog("PLAYER_SETUP");
            }
            else if(cache.loadChunks.containsKey(player.getUniqueId())) {
                return configs.lang.getLog("PLAYER_LOADING");
            }
            else if(!player.hasPermission("rtp.noCooldown")
                    && (cache.lastTeleportTime.get(player.getUniqueId()) - System.nanoTime()) < TimeUnit.SECONDS.toNanos(configs.config.teleportCooldown)) {
                return configs.lang.getLog("PLAYER_COOLDOWN");
            }

            return configs.lang.getLog("PLAYER_AVAILABLE");
        }

        if(identifier.equalsIgnoreCase("total_queue_length")) {
            TeleportRegion region = getRegion(player);
            if(region==null) return "0";
            return String.valueOf(region.getTotalQueueLength(player));
        }

        if(identifier.equalsIgnoreCase("public_queue_length")) {
            TeleportRegion region = getRegion(player);
            if(region==null) return "0";
            return String.valueOf(region.getPublicQueueLength());
        }

        if(identifier.equalsIgnoreCase("personal_queue_length")) {
            TeleportRegion region = getRegion(player);
            if(region==null) return "0";
            return String.valueOf(region.getPlayerQueueLength(player));
        }

        if(identifier.equalsIgnoreCase("teleport_world")) {
            Location location = getTeleportLocation(player);
            String worldName = Objects.requireNonNull(location.getWorld()).getName();
            worldName = configs.worlds.worldName2Placeholder(worldName);
            return worldName;
        }

        if(identifier.equalsIgnoreCase("teleport_x")) {
            return String.valueOf(getTeleportLocation(player).getBlockX());
        }

        if(identifier.equalsIgnoreCase("teleport_y")) {
            return String.valueOf(getTeleportLocation(player).getBlockY());
        }

        if(identifier.equalsIgnoreCase("teleport_z")) {
            return String.valueOf(getTeleportLocation(player).getBlockZ());
        }

        if(identifier.equalsIgnoreCase("teleport_biome")) {
            Location location = getTeleportLocation(player);
            World world = Objects.requireNonNull(location.getWorld());
            //noinspection deprecation
            return String.valueOf(
                    (RTP.getServerIntVersion() < 17)
                            ? world.getBiome(location.getBlockX(), location.getBlockZ())
                            : world.getBiome(location)
            );
        }

        return null;
    }

    @NotNull
    private Location getTeleportLocation(Player player) {
	    return cache.todoTP.getOrDefault(player.getUniqueId(), cache.lastTP.getOrDefault(player.getUniqueId(),new Location(player.getWorld(),0,0,0)));
    }

    private TeleportRegion getRegion(Player player) {
	    World world = player.getWorld();
        String worldName = world.getName();
        if (!player.hasPermission("rtp.worlds." + worldName) && (Boolean) configs.worlds.getWorldSetting(worldName, "requirePermission", true)) {
            worldName = (String)configs.worlds.getWorldSetting(worldName,"override","world");
            if(!configs.worlds.checkWorldExists(worldName)) return null;
            world = Bukkit.getWorld(worldName);
        }

        RandomSelectParams randomSelectParams = new RandomSelectParams(Objects.requireNonNull(world),null);
        return cache.permRegions.getOrDefault(randomSelectParams,null);
    }
}
