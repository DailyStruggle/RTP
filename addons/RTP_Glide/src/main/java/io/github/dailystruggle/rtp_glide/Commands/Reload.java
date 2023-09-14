package io.github.dailystruggle.rtp_glide.Commands;

import io.github.dailystruggle.rtp_glide.configuration.Configs;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class Reload implements CommandExecutor {
    private final Configs Configs;

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
