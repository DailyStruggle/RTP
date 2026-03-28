package io.github.dailystruggle.rtp_glide;

import io.github.dailystruggle.rtp_glide.Commands.Glide;
import io.github.dailystruggle.rtp_glide.Commands.Reload;
import io.github.dailystruggle.rtp_glide.Commands.TabComplete;
import io.github.dailystruggle.rtp_glide.Listeners.*;
import io.github.dailystruggle.rtp_glide.Tasks.SetupGlide;
import io.github.dailystruggle.rtp_glide.configuration.Configs;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Main class for RTP_Glide addon
 */
public final class RTP_Glide extends JavaPlugin {
    /**
     * Default constructor for RTP_Glide
     */
    public RTP_Glide() {
    }
    private static final ConcurrentSkipListSet<UUID> glidingPlayers = new ConcurrentSkipListSet<>();
    private static Configs Configs;

    @Override
    public void onEnable() {
        Configs = new Configs( this );
        // Plugin startup logic
        Glide glide = new Glide( this, Configs );
        Objects.requireNonNull( getCommand( "glide") ).setExecutor( glide );
        Objects.requireNonNull( getCommand( "glide") ).setTabCompleter( new TabComplete() );

        glide.addCommandHandle( "reload","glide.reload",new Reload( Configs) );

        if( Bukkit.getPluginManager().getPlugin( "RTP" ) != null )
            getServer().getPluginManager().registerEvents( new OnRandomTeleport( this, Configs ), this );
        getServer().getPluginManager().registerEvents( new OnGlideToggle( this ),this );

        SetupGlide.setPlugin( this );
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        glidingPlayers.clear();
        Configs = null;
    }

    /**
     * Get the set of players currently gliding
     * @return the set of player UUIDs
     */
    public ConcurrentSkipListSet<UUID> getGlidingPlayers() {
        return glidingPlayers;
    }

    /**
     * Check if a player is currently gliding due to teleportation
     * @param uuid the player's UUID
     * @return true if gliding, false otherwise
     */
    public static boolean isTeleportGliding( UUID uuid ) {
        return glidingPlayers.contains( uuid );
    }

    /**
     * Check if an entity is currently gliding due to teleportation
     * @param entity the entity
     * @return true if gliding, false otherwise
     */
    public static boolean isTeleportGliding( Entity entity ) {
        return glidingPlayers.contains( entity.getUniqueId() );
    }
}


