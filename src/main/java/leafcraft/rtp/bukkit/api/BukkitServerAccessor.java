package leafcraft.rtp.bukkit.api;

import leafcraft.rtp.api.RTPServerAccessor;
import leafcraft.rtp.api.substitutions.RTPWorld;
import leafcraft.rtp.bukkit.RTPBukkitPlugin;
import leafcraft.rtp.bukkit.api.substitutions.BukkitRTPWorld;
import org.bukkit.Bukkit;

import java.util.UUID;

public class BukkitServerAccessor implements RTPServerAccessor {
    private String version = null;
    private Integer intVersion = null;

    @Override
    public String getServerVersion() {
        if(version == null) {
            version = RTPBukkitPlugin.getInstance().getServer().getClass().getPackage().getName();
            version = version.replaceAll("[-+^.a-zA-Z]*","");
        }

        return version;
    }

    @Override
    public Integer getServerIntVersion() {
        if(intVersion == null) {
            String[] splitVersion = getServerVersion().split("_");
            if(splitVersion.length == 0) {
                intVersion = 0;
            }
            else if (splitVersion.length == 1) {
                intVersion = Integer.valueOf(splitVersion[0]);
            }
            else {
                intVersion = Integer.valueOf(splitVersion[1]);
            }
        }
        return intVersion;
    }

    @Override
    public RTPWorld getRTPWorld(String name) {
        return new BukkitRTPWorld(Bukkit.getWorld(name));
    }

    @Override
    public RTPWorld getRTPWorld(UUID id) {
        return new BukkitRTPWorld(Bukkit.getWorld(id));
    }

    @Override
    public RTPWorld getDefaultRTPWorld() {
        return new BukkitRTPWorld(Bukkit.getWorlds().get(0));
    }

    @Override
    public long overTime() {
        return 0;
    }
}
