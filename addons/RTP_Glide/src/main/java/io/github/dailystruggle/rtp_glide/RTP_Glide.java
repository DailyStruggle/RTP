package io.github.dailystruggle.rtp_glide;

import io.github.dailystruggle.rtp_glide.Listeners.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;

public final class RTP_Glide extends JavaPlugin {
    private static final ConcurrentSkipListSet<UUID> glidingPlayers = new ConcurrentSkipListSet<>();

    @Override
    public void onEnable() {
        // Plugin startup logic
        getServer().getPluginManager().registerEvents(new OnRandomTeleport(this),this);
        getServer().getPluginManager().registerEvents(new OnGlideToggle(this),this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        glidingPlayers.clear();
    }

    public ConcurrentSkipListSet<UUID> getGlidingPlayers() {
        return glidingPlayers;
    }

    public static boolean isTeleportGliding(UUID uuid) {
        return glidingPlayers.contains(uuid);
    }

    public static boolean isTeleportGliding(Entity entity) {
        return glidingPlayers.contains(entity.getUniqueId());
    }
}
