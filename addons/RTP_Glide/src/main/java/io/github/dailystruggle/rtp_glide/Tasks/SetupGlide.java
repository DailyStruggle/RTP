package io.github.dailystruggle.rtp_glide.Tasks;

import io.github.dailystruggle.rtp_glide.RTP_Glide;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class SetupGlide extends BukkitRunnable {
    private static RTP_Glide plugin = null;
    private final Player player;

    public static void setPlugin(RTP_Glide plugin) {
        SetupGlide.plugin = plugin;
    }

    public SetupGlide(Player player) {
        this.player = player;
    }

    @Override
    public void run() {
        setupGlideNow(player);
    }

    public static void setupGlideNow(Player player) {
        player.teleport(player.getLocation().add(0,100,0));
        plugin.getGlidingPlayers().add(player.getUniqueId());
        player.setFlying(false);
        player.setGliding(true);
    }
}
