package leafcraft.rtp.spigotEventListeners;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.selection.TeleportRegion;
import leafcraft.rtp.tools.selection.Translate;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

public class OnChunkLoad implements Listener {


    @EventHandler(priority = EventPriority.HIGH)
    public void onChunkLoad(ChunkLoadEvent event) {
        Configs configs = RTP.getConfigs();
        World world = event.getWorld();


        Location location = event.getChunk().getBlock(7,96,7).getLocation();
        Biome biome = Biome.PLAINS;
        if(RTP.getServerIntVersion() < 17) {
            if(RTP.validBiomeLookup.get()) {
                try {
                    //noinspection deprecation
                    biome = world.getBiome(location.getBlockX(), location.getBlockZ());
                    if (configs.config.biomeWhitelist != configs.config.biomes.contains(biome)) return;
                } catch (Exception | Error e) {
                    RTP.validBiomeLookup.set(false);
                    new IllegalStateException("broken biome lookup. Please fix your world gen plugins or datapacks. Continuing without biome checks after this...").printStackTrace();
                    e.printStackTrace();
                }
            }
        }

        for(TeleportRegion region : RTP.getCache().permRegions.values()) {
            if(!world.getUID().equals(region.world.getUID())) continue;
            if(RTP.getServerIntVersion() >= 17) {
                int midpoint = (region.minY + region.maxY)/2;
                if(RTP.validBiomeLookup.get()) {
                    try {
                        biome = world.getBiome(location.getBlockX(), midpoint, location.getBlockZ());
                        if(configs.config.biomeWhitelist != configs.config.biomes.contains(biome)) continue;
                    } catch (Exception | Error e) {
                        RTP.validBiomeLookup.set(false);
                        new IllegalStateException("broken biome lookup. Please fix your world gen plugins or datapacks. Continuing without biome checks after this...").printStackTrace();
                        e.printStackTrace();
                    }
                }
            }

            long regionLocation = (long) ((region.shape.equals(TeleportRegion.Shapes.SQUARE)) ?
                                Translate.xzToSquareLocation(region.cr,event.getChunk().getX(),event.getChunk().getZ(),region.cx,region.cz) :
                                Translate.xzToCircleLocation(region.cr,event.getChunk().getX(),event.getChunk().getZ(),region.cx,region.cz));
            if(!region.isInBounds(regionLocation)) continue;
            if(region.isKnownBad(regionLocation)) continue;
            int y = region.getFirstNonAir(event.getChunk());
            y = region.getLastNonAir(event.getChunk(),y);

            if(biome == null) continue;
            if(region.checkLocation(event.getChunk(),y)) {
                region.addBiomeLocation(regionLocation,biome);
            }
            else {
                region.removeBiomeLocation(regionLocation,biome);
                region.addBadLocation(regionLocation);
            }
        }
    }
}
