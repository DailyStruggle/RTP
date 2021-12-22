package leafcraft.rtp.tools.configuration;

import leafcraft.rtp.RTP;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

//route for all config classes
public class Configs {
    public Config config;
    public Lang lang;
    public Regions regions;
    public Worlds worlds;
    public List<MethodHandle> locationChecks;

    public Configs() {
        RTP plugin = RTP.getPlugin();
        lang = new Lang(plugin);
        config = new Config(plugin,lang);
        worlds = new Worlds(plugin,lang);
        regions = new Regions(plugin,lang);
        locationChecks = new ArrayList<>();
    }

    public void refresh() {
        RTP plugin = RTP.getPlugin();
        lang = new Lang(plugin);
        config = new Config(plugin,lang);
        worlds = new Worlds(plugin,lang);
        regions = new Regions(plugin,lang);
    }

    /**
     * Adds a reflective location check
     * @param methodHandle - method to call,
     *               method takes a org.bukkit.Location, returns true if it's a hit
     *               e.g. boolean isInClaim(Location location)
     */
    public void addLocationCheck(@NotNull MethodHandle methodHandle) {
        locationChecks.add(methodHandle);
    }
}
