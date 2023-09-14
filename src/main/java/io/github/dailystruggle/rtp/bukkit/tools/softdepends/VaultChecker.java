package io.github.dailystruggle.rtp.bukkit.tools.softdepends;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPEconomy;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;

public class VaultChecker implements RTPEconomy {
    private static Economy econ = null;

    public static void setupEconomy() {
        if ( Bukkit.getServer().getPluginManager().getPlugin( "Vault" ) == null ) {
            return;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServer().getServicesManager().getRegistration( Economy.class );
        if ( rsp == null ) return;
        econ = rsp.getProvider();
    }

    public static void setupPermissions() {
        if ( Bukkit.getPluginManager().getPlugin( "Vault" ) == null ) {
            return;
        }
        RegisteredServiceProvider<Permission> rsp = Bukkit.getServer().getServicesManager().getRegistration( Permission.class );
        if ( rsp == null ) return;
    }

    public static Economy getEconomy() {
        return econ;
    }

    @Override
    public void give( UUID playerId, double money ) {
        if ( playerId.equals( CommandsAPI.serverId) ) return;
        if ( econ == null ) {
            RTP.economy = null;
            return;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer( playerId );
        if ( !player.isOnline() ) return;
        EconomyResponse economyResponse = econ.depositPlayer( player, money );
        economyResponse.transactionSuccess();
    }

    @Override
    public boolean take( UUID playerId, double money ) {
        if ( playerId.equals( CommandsAPI.serverId) ) return true;

        if ( econ == null ) {
            RTP.economy = null;
            return true;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer( playerId );
        if ( !player.isOnline() ) return false;
        EconomyResponse economyResponse = econ.withdrawPlayer( player, money );
        return economyResponse.transactionSuccess();
    }

    @Override
    public double bal( UUID playerId ) {
        if ( playerId.equals( CommandsAPI.serverId) ) return Double.MAX_VALUE;
        if ( econ == null ) {
            RTP.economy = null;
            return 0;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer( playerId );
        if ( !player.isOnline() ) return 0;
        return econ.getBalance( player );
    }
}
