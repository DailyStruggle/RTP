package io.github.dailystruggle.rtp_glide.Tasks;

import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp_glide.RTP_Glide;
import io.github.dailystruggle.rtp_glide.configuration.Configs;
import io.github.dailystruggle.rtp_glide.customEvents.PlayerGlideEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Task to setup glide for a player
 */
public class SetupGlide extends RTPRunnable {
    private static RTP_Glide plugin = null;
    private final Player player;
    private final Configs Configs;

    /**
     * Set the plugin instance
     * @param plugin the plugin instance
     */
    public static void setPlugin( RTP_Glide plugin ) {
        SetupGlide.plugin = plugin;
    }

    /**
     * Constructor for SetupGlide task
     * @param player the player to setup glide for
     * @param Configs the configurations instance
     */
    public SetupGlide( Player player, Configs Configs ) {
        this.player = player;
        this.Configs = Configs;
    }

    @Override
    public void run() {
        Location location = player.getLocation();
        int relative = ( int ) Configs.worlds.getWorldSetting( player.getWorld().getName(),"relative",75 );
        int max = ( int ) Configs.worlds.getWorldSetting( player.getWorld().getName(),"max",320 );
        int toY = location.getBlockY() + relative;
        if( toY > max ) toY = max;
        location.setY( toY );
        player.teleport( location );
        plugin.getGlidingPlayers().add( player.getUniqueId() );
        player.setFlying( false );
        player.setGliding( true );
        Bukkit.getPluginManager().callEvent( new PlayerGlideEvent( player) );
    }
}


