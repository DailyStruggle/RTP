package leafcraft.rtp.tools.softdepends;

import com.comphenix.protocol.ProtocolLib;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class ProtocolLibChecker {
    public static ProtocolLib getProtocolLib() {
        Plugin plugin = Bukkit.getServer().getPluginManager().getPlugin("ProtocolLib");

        // WorldGuard may not be loaded
        if (plugin == null || !(plugin instanceof ProtocolLib)) {
            return null; // Maybe you want throw an exception instead
        }
        return (ProtocolLib) plugin;
    }
}
