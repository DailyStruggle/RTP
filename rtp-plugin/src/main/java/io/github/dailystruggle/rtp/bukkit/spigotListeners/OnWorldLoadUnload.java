package io.github.dailystruggle.rtp.bukkit.spigotListeners;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.ArrayList;
import java.util.List;

public class OnWorldLoadUnload implements Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        String worldName = event.getWorld().getName();
        // Proactively trigger configuration instantiation in a background task
        RTP.scheduler.runTaskAsynchronously(() -> {
            RTP.configs.getWorldParser(worldName);
        });
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
            if (region.getWorld().name().equals(worldName)) {
                regionsToShutdown.add(name);
            }
        });

        for (String regionName : regionsToShutdown) {
            Region region = RTP.selectionAPI.permRegionLookup.remove(regionName);
            if (region != null) {
                region.shutDown();
            }
        }
    }
}
