package io.github.dailystruggle.rtp.api;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

public class RTPAPI {
    private static Runnable reloadTask = null;

    private static void reload() {
        TreeCommand baseCommand = RTP.baseCommand;
        if (baseCommand == null) {
            RTP.getInstance().miscAsyncTasks.add(reloadTask);
            return;
        }
        CommandsAPICommand reloadCmd = baseCommand.getCommandLookup().get("reload");
        if (reloadCmd != null) reloadCmd.onCommand(CommandsAPI.serverId, new HashMap<>(), null);
        reloadTask = null;
    }

    public static boolean addSubCommand(CommandsAPICommand command) {
        if (RTP.baseCommand != null) {
            RTP.baseCommand.addSubCommand(command);
            return true;
        }
        return false;
    }

    public static boolean addShape(Shape<?> shape) {
        Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
        if (factory == null) return false;

        if (factory.contains(shape.name)) return true;
        factory.add(shape.name, shape.clone());

        if (reloadTask == null) {
            reloadTask = RTPAPI::reload;
            RTP.getInstance().miscAsyncTasks.add(reloadTask);
        }

        return true;
    }

    public static long loadedLocations(String regionName) {
        if (RTP.getInstance() == null) return 0;
        SelectionAPI selectionAPI = RTP.selectionAPI;
        if (selectionAPI == null) return 0;
        Region region = selectionAPI.getRegionOrDefault(regionName);
        if (region == null) return 0;
        return region.getPublicQueueLength();
    }

    public static Boolean prepareLocation(String regionName) {
        if (RTP.getInstance() == null) return false;
        Region region = RTP.selectionAPI.getRegionOrDefault(regionName);
        Map.Entry<RTPLocation, Long> location = region.getLocation(null);
        if(location == null || location.getKey() == null) return false;
        region.locationQueue.add(location);
        return true;
    }

    public static Boolean prepareLocation(String regionName, UUID playerId) {
        if (RTP.getInstance() == null) return false;
        if(playerId == null) return prepareLocation(regionName);
        Region region = RTP.selectionAPI.getRegionOrDefault(regionName);
        Map.Entry<RTPLocation, Long> location = region.getLocation(null);
        if(location == null || location.getKey() == null) return false;
        ConcurrentLinkedQueue<Map.Entry<RTPLocation, Long>> orDefault = region.perPlayerLocationQueue.getOrDefault(playerId, new ConcurrentLinkedQueue<>());
        orDefault.add(location);
        region.perPlayerLocationQueue.put(playerId,orDefault);
        return true;
    }

    public static CompletableFuture<Boolean> prepareLocationAsync(String regionName) {
        if (RTP.getInstance() == null) return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> res = new CompletableFuture<>();
        RTP.getInstance().miscAsyncTasks.add(() -> {
            Region region = RTP.selectionAPI.getRegionOrDefault(regionName);
            Map.Entry<RTPLocation, Long> location = region.getLocation(null);
            if(location == null || location.getKey() == null) res.complete(false);
            else {
                region.locationQueue.add(location);
                res.complete(true);
            }
        });
        return res;
    }

    public static CompletableFuture<Boolean> prepareLocationAsync(String regionName, UUID playerId) {
        if (RTP.getInstance() == null) return CompletableFuture.completedFuture(false);
        if(playerId == null) {
            return prepareLocationAsync(regionName);
        }
        CompletableFuture<Boolean> res = new CompletableFuture<>();
        RTP.getInstance().miscAsyncTasks.add(() -> {
            Region region = RTP.selectionAPI.getRegionOrDefault(regionName);
            Map.Entry<RTPLocation, Long> location = region.getLocation(null);
            if(location == null || location.getKey() == null) res.complete(false);
            else {
                ConcurrentLinkedQueue<Map.Entry<RTPLocation, Long>> orDefault = region.perPlayerLocationQueue.getOrDefault(playerId, new ConcurrentLinkedQueue<>());
                orDefault.add(location);
                region.perPlayerLocationQueue.put(playerId, orDefault);
                res.complete(true);
            }
        });
        return res;
    }

    public static void prepareLocations(String regionName) {
        if (RTP.getInstance() == null) return;
        Region region = RTP.selectionAPI.getRegionOrDefault(regionName);
        region.execute(Long.MAX_VALUE);
    }

    public static void prepareLocationsAsync(String regionName) {
        if (RTP.getInstance() == null) return;
        RTP.getInstance().miscAsyncTasks.add(() -> prepareLocations(regionName));
    }

    public static void prepareLocations() {
        if (RTP.getInstance() == null) return;
        for(Region region : RTP.selectionAPI.permRegionLookup.values()) {
            region.execute(Long.MAX_VALUE);
        }
    }

    public static void prepareLocationsAsync() {
        if (RTP.getInstance() == null) return;
        RTP.getInstance().miscAsyncTasks.add(RTPAPI::prepareLocations);
    }

    public static Optional<RTPLocation> select(String regionName) {
        if (RTP.getInstance() == null) return Optional.empty();
        Region region = RTP.selectionAPI.getRegionOrDefault(regionName);
        Map.Entry<RTPLocation, Long> location;
        if(region.hasLocation(null)) {
            location = region.locationQueue.poll();
        }
        else {
            location = region.getLocation(null);
        }
        if(location == null || location.getKey() == null) return Optional.empty();
        return Optional.of(location.getKey());
    }

    public static Optional<RTPLocation> selectNew(String regionName) {
        if (RTP.getInstance() == null) return Optional.empty();
        Region region = RTP.selectionAPI.getRegionOrDefault(regionName);
        Map.Entry<RTPLocation, Long> location = region.getLocation(null);
        if(location == null || location.getKey() == null) return Optional.empty();
        return Optional.of(location.getKey());
    }

    public static Optional<RTPLocation> selectCached(String regionName) {
        if (RTP.getInstance() == null) return Optional.empty();
        Region region = RTP.selectionAPI.getRegionOrDefault(regionName);
        if(!region.hasLocation(null)) return Optional.empty();
        Map.Entry<RTPLocation, Long> location = region.locationQueue.poll();
        if(location == null || location.getKey() == null) return Optional.empty();
        return Optional.of(location.getKey());
    }

    
}