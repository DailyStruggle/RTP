package leafcraft.rtp.tools.softdepends;

import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Objects;

public class VaultChecker {
    private static Economy econ = null;
    private static Permission perms = null;
    private static Chat chat = null;

    public static boolean setupEconomy() {
        if (Bukkit.getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return true;
    }

    public static void setupChat() {
        if(Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return;
        }
        RegisteredServiceProvider<Chat> rsp = Bukkit.getServer().getServicesManager().getRegistration(Chat.class);
        chat = Objects.requireNonNull(rsp).getProvider();
    }

    public static void setupPermissions() {
        if(Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return;
        }
        RegisteredServiceProvider<Permission> rsp = Bukkit.getServer().getServicesManager().getRegistration(Permission.class);
        perms = Objects.requireNonNull(rsp).getProvider();
    }

    public static Economy getEconomy() {
        return econ;
    }

    public static Permission getPermissions() {
        return perms;
    }

    public static Chat getChat() {
        return chat;
    }
}
