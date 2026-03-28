package io.github.dailystruggle.rtp_glide.Commands;

import io.github.dailystruggle.rtp_glide.configuration.Configs;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * Command executor for the /glide reload command
 */
public class Reload implements CommandExecutor {
    private final Configs Configs;

    /**
     * Constructor for Reload command executor
     * @param Configs the configurations instance
     */
    public Reload( Configs Configs ) {
        this.Configs = Configs;
    }

    @Override
    public boolean onCommand( CommandSender sender, Command command, String label, String[] args ) {
        if( !sender.hasPermission( "rtp.reload") ) return false;

        Configs.refresh();

        sender.sendMessage( "[rtp_glide] reloaded." );

        return true;
    }
}


