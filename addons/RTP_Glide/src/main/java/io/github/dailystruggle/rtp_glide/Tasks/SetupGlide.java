package io.github.dailystruggle.rtp_glide.Tasks;

import io.github.dailystruggle.rtp_glide.RTP_Glide;
import io.github.dailystruggle.rtp_glide.configuration.Configs;
import io.github.dailystruggle.rtp_glide.customEvents.PlayerGlideEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class SetupGlide extends BukkitRunnable {
    private static RTP_Glide plugin = null;
    private final Player player;
    private final Configs configs;

    public static void setPlugin(RTP_Glide plugin) {
        SetupGlide.plugin = plugin;
    }

    public SetupGlide(Player player, Configs configs) {
        this.player = player;
        this.configs = configs;
    }

    @Override
    public void run() {
        Location location = player.getLocation();
        int relative = (int) configs.worlds.getWorldSetting(player.getWorld().getName(),"relative",75);
        int max = (int) configs.worlds.getWorldSetting(player.getWorld().getName(),"max",320);
        int toY = location.getBlockY() + relative;
        if(toY > max) toY = max;
        location.setY(toY);
        player.teleport(location);
        plugin.getGlidingPlayers().add(player.getUniqueId());
        player.setFlying(false);
        player.setGliding(true);
        Bukkit.getPluginManager().callEvent(new PlayerGlideEvent(player));
    }

    public static void setupGlideNow(Player player) {

    }
}
