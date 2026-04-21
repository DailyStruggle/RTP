package io.github.dailystruggle.rtp_iris_integration;

import com.volmit.iris.core.tools.IrisToolbelt;
import com.volmit.iris.engine.platform.PlatformChunkGenerator;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

/** Main class for RTP_Iris_integration addon */
public final class RTP_Iris_integration extends JavaPlugin {
  private static final Pattern invalidCharacters = Pattern.compile("[ :,]");

  /** Default constructor for RTP_Iris_integration */
  public RTP_Iris_integration() {}

  // BIOME_AND_BAD_LOCATION_VISITOR_PLAN.md §4 step 7: the world-level biome
  // enumeration override (formerly `setBiomesGetter(RTP_Iris_integration::getBiomes)`)
  // has been retired. `LocationGenerator` now evaluates the whitelist/blacklist
  // directly without needing a materialised world-level biome set, so the Iris
  // addon no longer needs to publish one. The per-coordinate resolver below
  // (`setBiomeGetter`) is retained — it is still load-bearing for surfacing
  // namespaced Iris biome names (`iris:volcanic_ash_plains`) on candidate
  // evaluation; the adapter-default Anvil-first pre-step covers populated
  // chunks when the addon is absent.

  private static String getBiome(Location location) {
    PlatformChunkGenerator access = IrisToolbelt.access(location.getWorld());
    if (access == null) return location.getWorld().getBiome(location).name().toUpperCase();
    String s = access.getEngine().getBiome(location).getName().toUpperCase();
    s = invalidCharacters.matcher(s).replaceAll("_");
    return s;
  }

  @Override
  public void onEnable() {
    // Plugin startup logic
    RTP.serverAccessor.setBiomeGetter(
        rtpLocation -> {
          org.bukkit.World bukkitWorld = Bukkit.getWorld(rtpLocation.world().id());
          if (bukkitWorld == null) return "PLAINS";
          return getBiome(
              new Location(bukkitWorld, rtpLocation.x(), rtpLocation.y(), rtpLocation.z()));
        });
    Bukkit.getScheduler()
        .scheduleSyncDelayedTask(
            this,
            () -> {
              RTP.baseCommand.addParameter("biome", new IrisBiomeParameter());
              RTP.configs.reload();
            });
  }

  @Override
  public void onDisable() {
    // Plugin shutdown logic
  }
}
