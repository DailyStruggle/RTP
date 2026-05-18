package io.github.dailystruggle.rtp.bukkit.bukkitListeners;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionConfigLoader;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class OnWorldLoadUnload implements Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        String worldName = event.getWorld().getName();
        // Proactively trigger configuration instantiation in a background task, then rebind any
        // region that was previously fallback-bound because this world wasn't loaded yet.
        RTP.scheduler.runTaskAsynchronously(() -> {
            RTP.configs.getWorldParser(worldName);
            rebindFallbackRegionsForWorld(worldName);
        });
    }

    /**
     * One-shot scan over every dormant (world-fallback-bound) region, attempting to rebind each
     * one against its configured world if that world is already resolvable via
     * {@link RTP#serverAccessor}. Used at plugin-enable time so that {@link WorldLoadEvent}s
     * which fired before this listener was registered (e.g. worlds loaded by bukkit.yml or by a
     * previously-enabled plugin's {@code onEnable}) still activate dormant regions without
     * waiting for another event.
     */
    public static void rebindFallbackRegionsForAllLoadedWorlds() {
        for (Region region : RTP.selectionAPI.permRegionLookup.values()) {
            if (!region.worldFallbackBound) continue;
            String configured = region.configuredWorldName;
            if (configured == null) continue;
            rebindFallbackRegionsForWorld(configured);
        }
    }

    /**
     * Re-resolves and rebinds any region that was created with the "world fallback" marker for
     * {@code worldName}. Invoked from {@link #onWorldLoad} so that configured worlds generated
     * by external plugins (e.g. Multiverse) eventually drive the region onto its intended world
     * without losing the cached-location rows the DB holds for that world.
     */
    private static void rebindFallbackRegionsForWorld(String worldName) {
        @SuppressWarnings("unchecked")
        MultiConfigParser<RegionKeys> regionParsers =
            (MultiConfigParser<RegionKeys>) RTP.configs.multiConfigParserMap.get(RegionKeys.class);
        if (regionParsers == null) return;

        for (Region region : RTP.selectionAPI.permRegionLookup.values()) {
            if (!region.worldFallbackBound) continue;
            String configured = region.configuredWorldName;
            if (configured == null || !configured.equalsIgnoreCase(worldName)) continue;

            ConfigParser<RegionKeys> regionParser = regionParsers.getParser(region.name);
            if (regionParser == null) continue;

            RegionSettings rebuilt = RegionConfigLoader.load(regionParser);
            if (rebuilt.world() == null
                || !rebuilt.world().name().equalsIgnoreCase(worldName)) {
                // The loader still couldn't resolve the configured world; leave the region
                // bound to its fallback and try again on a later WorldLoadEvent.
                continue;
            }

            region.rebindWorld(rebuilt);
            RTP.log(
                Level.INFO,
                "[RTP] Region '" + region.name + "' rebound to world '" + worldName
                    + "' after automatic world load.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        if (event.isCancelled()) return;
        String worldName = event.getWorld().getName();

        // 1. Remove the world's parser from MultiConfigParser internal maps
        MultiConfigParser<WorldKeys> worldParsers = (MultiConfigParser<WorldKeys>) RTP.configs.multiConfigParserMap.get(WorldKeys.class);
        if (worldParsers != null) {
            worldParsers.removeParser(worldName);
        }

        // 2. Clear references and shut down regions associated with this world
        List<String> regionsToShutdown = new ArrayList<>();
        RTP.selectionAPI.permRegionLookup.forEach((name, region) -> {
            // Dormant regions (awaiting WorldLoadEvent for their configured world) have a
            // null world; they cannot be bound to the world being unloaded, so skip them.
            io.github.dailystruggle.rtp.api.world.RTPWorld<?> rw = region.getWorld();
            if (rw != null && rw.name().equals(worldName)) {
                regionsToShutdown.add(name);
            }
        });

        for (String regionName : regionsToShutdown) {
            Region region = RTP.selectionAPI.permRegionLookup.get(regionName);
            if (region != null) {
                // Manually clear queues and close reservations without deleting from DB
                region.queueManager.keptLocations.setCallbacks(null, null); // Disable DB removal

                io.github.dailystruggle.rtp.common.selection.region.RTPLocation loc;
                while ((loc = region.queueManager.keptLocations.poll()) != null) {
                    if (loc.reservation() != null) loc.reservation().close();
                }

                region.queueManager.perPlayerLocationQueue.forEach((uuid, queue) -> {
                    io.github.dailystruggle.rtp.common.selection.region.RTPLocation pLoc;
                    while ((pLoc = queue.poll()) != null) {
                        if (pLoc.reservation() != null) pLoc.reservation().close();
                    }
                });
                region.queueManager.perPlayerLocationQueue.clear();

                RTP.selectionAPI.permRegionLookup.remove(regionName);
                region.shutDown();
            }
        }
    }
}
