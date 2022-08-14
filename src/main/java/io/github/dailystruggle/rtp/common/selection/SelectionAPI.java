package io.github.dailystruggle.rtp.common.selection;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.common.serverSide.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;

public class SelectionAPI {
    private final Factory<Region> regionFactory = new Factory<>();

    /**
     * pipe of selection tasks to be done.
     * will be done in the order given, trying urgent tasks first
     * A failed selection will go to the back of the line.
     */
    public final ConcurrentLinkedQueue<Runnable> selectionPipeline = new ConcurrentLinkedQueue<>();
    public final ConcurrentLinkedQueue<Runnable> selectionPipelineUrgent = new ConcurrentLinkedQueue<>();

    public final ConcurrentHashMap<UUID, Region> tempRegions = new ConcurrentHashMap<>();

    public final ConcurrentHashMap<String, Region> permRegionLookup = new ConcurrentHashMap<>();

    //semaphore needed in case of async usage
    //storage for region verifiers to use for ALL regions
    private final Semaphore regionVerifiersLock = new Semaphore(1);
    private final List<Predicate<RTPLocation>> regionVerifiers = new ArrayList<>();

    //storage for worldborder stuff to use for ALL regions
    public WorldBorder worldBorder;

    /**
     * addGlobalRegionVerifier - add a region verifier to use for ALL regions
     * @param locationCheck verifier method to reference.
     *                 param: world name, 3D point
     *                 return: boolean - true on good location, false on bad location
     */
    public void addGlobalRegionVerifier(Predicate<RTPLocation> locationCheck) {
        try {
            regionVerifiersLock.acquire();
        } catch (InterruptedException e) {
            regionVerifiersLock.release();
            return;
        }
        regionVerifiers.add(locationCheck);
        regionVerifiersLock.release();
    }

    public void clearGlobalRegionVerifiers() {
        try {
            regionVerifiersLock.acquire();
        } catch (InterruptedException e) {
            regionVerifiersLock.release();
            return;
        }
        regionVerifiers.clear();
        regionVerifiersLock.release();
    }

    public boolean checkGlobalRegionVerifiers(RTPLocation location) {
        try {
            regionVerifiersLock.acquire();
        } catch (InterruptedException e) {
            regionVerifiersLock.release();
            return false;
        }

        for(Predicate<RTPLocation> verifier : regionVerifiers) {
            try {
                //if invalid placement, stop and return invalid
                //clone location to prevent methods from messing with the data
                if(!verifier.test(location)) return false;
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }
        regionVerifiersLock.release();
        return true;
    }

    /**
     * getFromString a region by name
     * @param regionName - name of region, case-insensitive
     * @return region object by that name, or null on bad lookup
     */
    @Nullable
    public Region getRegion(String regionName) {
        return permRegionLookup.get(regionName.toUpperCase());
    }


    /**
     * getFromString a region by name
     * @param regionName - name of region, case-insensitive
     * @return region object by that name, or null on bad lookup
     */
    @NotNull
    public Region getRegionExceptionally(String regionName) {
        String regionNameCaps = regionName.toUpperCase();
        Region res = permRegionLookup.get(regionName);
        if(res == null) throw new IllegalStateException("region:" + regionName + " does not exist");
        return res;
    }

    /**
     * getFromString a region by name
     * @param regionName - name of region
     * @return region by that name, or null if none
     */
    @NotNull
    public Region getRegionOrDefault(String regionName) {
        return getRegionOrDefault(regionName,"DEFAULT");
    }

    /**
     * getFromString a region by name
     * @param regionName - name of region
     * @return region by that name, or null if none
     */
    @NotNull
    public Region getRegionOrDefault(String regionName, String defaultName) {
        regionName = regionName.toUpperCase();
        defaultName = defaultName.toUpperCase();
        Region region = permRegionLookup.getOrDefault(regionName, permRegionLookup.get(defaultName));
        if(region == null) throw new IllegalStateException("neither '" + regionName + "' nor '" + defaultName + "' are known regions\n" + permRegionLookup);
        return Objects.requireNonNull(region);
    }

    /**
     * add or update a region by name
     * @param regionName - name of region
     * @param params - mapped parameters, based on parameters in default.yml
     */
    public void setRegion(String regionName, Map<String,String> params) {
        //todo: implement
//        params.put("region",regionName);
//
//        String worldName = (String) Configs.regions.getRegionSetting(regionName,"world","");
//        if (worldName == null || worldName.equals("") || !Configs.worlds.checkWorldExists(worldName)) {
//            return null;
//        }
//
//        RegionParams randomSelectParams = new RegionParams(RTP.getInstance().getRTPWorld(worldName),params);
//        if(permRegions.containsKey(randomSelectParams)) {
//            permRegions.getFromString(randomSelectParams).shutdown();
//        }
//
//        Configs.regions.setRegion(regionName,randomSelectParams);
//        return permRegions.put(randomSelectParams,
//                new Region(regionName,randomSelectParams.params));
    }

    public Set<String> regionNames() {
        return permRegionLookup.keySet();
    }

    public void compute() {
        RTPServerAccessor serverAccessor = RTP.serverAccessor;

        int req = RTP.minRTPExecutions;

        while(selectionPipelineUrgent.size() > 0 && (serverAccessor.overTime()<0 || req>0)) {
            if(selectionPipelineUrgent.size()>0)
                selectionPipelineUrgent.poll().run();
            req--;
        }

        while(selectionPipeline.size() > 0 && (serverAccessor.overTime()<0 || req>0)) {
            if(selectionPipeline.size()>0)
                selectionPipeline.poll().run();
            req--;
        }
    }

    public Region tempRegion(Map<String,String> regionParams,
                             @Nullable String baseRegionName) {
        if(baseRegionName == null || baseRegionName.isBlank() || !permRegionLookup.containsKey(baseRegionName.toUpperCase()))
            baseRegionName = "default";
        baseRegionName = baseRegionName.toUpperCase();
        Region baseRegion = Objects.requireNonNull(permRegionLookup.get(baseRegionName));
        EnumMap<RegionKeys, Object> data = baseRegion.getData();
        for(RegionKeys key : RegionKeys.values()) {
            if(regionParams.containsKey(key.name())) {
                String val = regionParams.get(key.name());
                data.put(key,val);
            }
        }

        //todo: fill in factory values

        Region clone = baseRegion.clone();
        clone.setData(data);
        return clone;
    }

    public Region getRegion(RTPPlayer player) {
        //get region from world name, check for overrides

        Set<String> worldsAttempted = new HashSet<>();
        String worldName = player.getLocation().world().name();
        MultiConfigParser<WorldKeys> worldParsers = (MultiConfigParser<WorldKeys>) RTP.getInstance().configs.multiConfigParserMap.get(WorldKeys.class);
        ConfigParser<WorldKeys> worldParser = worldParsers.getParser(worldName);
        boolean requirePermission = Boolean.parseBoolean(worldParser.getConfigValue(WorldKeys.requirePermission,false).toString());

        while(requirePermission && !player.hasPermission("rtp.worlds."+worldName)) {
            if(worldsAttempted.contains(worldName)) throw new IllegalStateException("infinite override loop detected at world - " + worldName);
            worldsAttempted.add(worldName);

            worldName = String.valueOf(worldParser.getConfigValue(WorldKeys.override,"default"));
            worldParser = worldParsers.getParser(worldName);
            requirePermission = Boolean.parseBoolean(worldParser.getConfigValue(WorldKeys.requirePermission,false).toString());
        }

        String regionName = String.valueOf(worldParser.getConfigValue(WorldKeys.region, "default"));
        MultiConfigParser<RegionKeys> regionParsers = (MultiConfigParser<RegionKeys>) RTP.getInstance().configs.multiConfigParserMap.get(RegionKeys.class);
        ConfigParser<RegionKeys> regionParser = regionParsers.getParser(regionName);
        requirePermission = Boolean.parseBoolean(regionParser.getConfigValue(RegionKeys.requirePermission,false).toString());

        Set<String> regionsAttempted = new HashSet<>();
        while(requirePermission && !player.hasPermission("rtp.regions."+regionName)) {
            if(regionsAttempted.contains(regionName)) throw new IllegalStateException("infinite override loop detected at region - " + regionName);
            regionsAttempted.add(regionName);

            regionName = String.valueOf(regionParser.getConfigValue(RegionKeys.override,"default"));
            regionParser = regionParsers.getParser(regionName);
            requirePermission = Boolean.parseBoolean(regionParser.getConfigValue(RegionKeys.requirePermission,false).toString());
        }
        return getRegion(regionName);
    }

    public Region getRegion(RTPWorld world) {
        //get region from world name, check for overrides
        String worldName = world.name();
        MultiConfigParser<WorldKeys> worldParsers = (MultiConfigParser<WorldKeys>) RTP.getInstance().configs.multiConfigParserMap.get(WorldKeys.class);
        ConfigParser<WorldKeys> worldParser = worldParsers.getParser(worldName);
        String regionName = String.valueOf(worldParser.getConfigValue(WorldKeys.region, "default"));
        return permRegionLookup.get(regionName);
    }
}
