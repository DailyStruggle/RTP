package io.github.dailystruggle.rtp.bukkit.spigotListeners;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.ChunkSet;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.substitutions.RTPLocation;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.io.File;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

//get queued location
public final class OnPlayerRespawn implements Listener {

    public OnPlayerRespawn() {
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        World toWorld = player.getWorld();
        String toWorldName = toWorld.getName();

        UUID id = player.getUniqueId();

        RTP instance = RTP.getInstance();
        MultiConfigParser<WorldKeys> worlds = (MultiConfigParser<WorldKeys>) instance.configs.multiConfigParserMap.get(WorldKeys.class);
        ConfigParser<WorldKeys> toWorldParser =  worlds.getParser(toWorldName);

        Set<String> worldAttempts = new HashSet<>();
        while (!player.hasPermission("rtp.worlds." + toWorldName) && (Boolean) toWorldParser.getConfigValue(WorldKeys.requirePermission, true)) {
            toWorldName = String.valueOf(toWorldParser.getConfigValue(WorldKeys.override, "world"));
            if(toWorldName == null || toWorldName.isBlank()) return;
            if(worldAttempts.contains(toWorldName)) {
                throw new IllegalStateException("infinite world override loop");
            }
            worldAttempts.add(toWorldName);
            toWorld = Bukkit.getWorld(toWorldName);
            if(toWorld == null) return;
            toWorldParser = worlds.getParser(toWorldName);
        }

        if(player.hasPermission("rtp.personalQueue")) {
            String toRegionName = String.valueOf(toWorldParser.getConfigValue(WorldKeys.region,"default"));
            SelectionAPI selectionAPI = instance.selectionAPI;
            Region region = selectionAPI.getRegion(toRegionName);

            /*
            in the event that the region by this (case-ignored) name doesn't exist, denote a configuration problem
            this sort of problem needs to be fixed before a production release, so throw a descriptive exception
             */
            if(region == null) throw new IllegalArgumentException("invalid region:" + toRegionName +
                    " given by worlds" + File.separator + toWorldParser.name + ".yml");
            region.cachePipeline.add(() -> {
                RTPLocation location = region.getLocation(Region.defaultBiomes);
                if(location != null) {
                    ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.getInstance().configs.getParser(PerformanceKeys.class);
                    long radius = perf.getNumber(PerformanceKeys.viewDistanceSelect,0L).longValue();

                    ChunkSet chunkSet = region.chunks(location, radius);

                    chunkSet.whenComplete(aBoolean -> {
                        if(aBoolean) {
                            ConcurrentHashMap<UUID, ConcurrentLinkedQueue<RTPLocation>> queueMap = region.perPlayerLocationQueue;
                            ConcurrentLinkedQueue<RTPLocation> queue = queueMap.getOrDefault(id, new ConcurrentLinkedQueue<>());
                            queue.add(location);
                            queueMap.putIfAbsent(id,queue);
                            region.locAssChunks.put(location,chunkSet);
                        }
                        else chunkSet.keep(false);
                    });
                }
            });
        }
    }
}
