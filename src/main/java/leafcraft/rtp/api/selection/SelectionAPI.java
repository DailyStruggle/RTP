package leafcraft.rtp.api.selection;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import leafcraft.rtp.api.RTPAPI;
import leafcraft.rtp.api.RTPServerAccessor;
import leafcraft.rtp.api.configuration.ConfigParser;
import leafcraft.rtp.api.configuration.Configs;
import leafcraft.rtp.api.configuration.enums.RegionKeys;
import leafcraft.rtp.api.selection.region.selectors.shapes.Shape;
import leafcraft.rtp.api.selection.worldborder.WorldBorder;
import leafcraft.rtp.api.selection.region.Region;
import leafcraft.rtp.api.substitutions.RTPLocation;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class SelectionAPI {
    /**
     * pipe of selection tasks to be done.
     * will be done in the order given, trying urgent tasks first
     * A failed selection will go to the back of the line.
     */
    public final ConcurrentLinkedQueue<Runnable> selectionPipeline = new ConcurrentLinkedQueue<>();
    public final ConcurrentLinkedQueue<Runnable> selectionPipelineUrgent = new ConcurrentLinkedQueue<>();

    public final ConcurrentHashMap<RegionParams, Region> tempRegions = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<RegionParams, Region> permRegions = new ConcurrentHashMap<>();

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
     * @param regionName - name of region
     * @return region by that name, or null if none
     */
    @Nullable
    public Region getRegion(String regionName) {
        return permRegionLookup.get(regionName);
    }

    /**
     * add or update a region by name
     * @param regionName - name of region
     * @param params - mapped parameters, based on parameters in default.yml
     * @return the previous Region if overwritten
     */
    @Nullable
    public Region setRegion(String regionName, Map<String,String> params) {
        return null;
        //t
//        params.put("region",regionName);
//
//        String worldName = (String) Configs.regions.getRegionSetting(regionName,"world","");
//        if (worldName == null || worldName.equals("") || !Configs.worlds.checkWorldExists(worldName)) {
//            return null;
//        }
//
//        RegionParams randomSelectParams = new RegionParams(RTPAPI.getInstance().getRTPWorld(worldName),params);
//        if(permRegions.containsKey(randomSelectParams)) {
//            permRegions.getFromString(randomSelectParams).shutdown();
//        }
//
//        Configs.regions.setRegion(regionName,randomSelectParams);
//        return permRegions.put(randomSelectParams,
//                new Region(regionName,randomSelectParams.params));
    }

    public Set<String> regionNames() {
        return permRegions.values().stream().map(region -> region.name).collect(Collectors.toSet());
    }

    public void compute() {
        RTPServerAccessor serverAccessor = RTPAPI.getInstance().serverAccessor;

        int req = RTPAPI.minRTPExecutions;

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

    public RTPLocation getRandomLocation(RegionParams rsParams, UUID sender, UUID player) {
        if(!permRegions.containsKey(rsParams) && !tempRegions.containsKey(rsParams)) return null;
        Region region;
        if(permRegions.containsKey(rsParams)) {
            region = permRegions.get(rsParams);
        }
        else {
            region = tempRegions.get(rsParams);
        }

        Set<String> biomes = new HashSet<>();
        if(rsParams.params.containsKey("biome")) {
            String[] biomesStr = rsParams.params.get("biome").split(String.valueOf(CommandsAPI.multiParameterDelimiter));
            biomes = Arrays.stream(biomesStr).collect(Collectors.toSet());
        }
        return region.getLocation(sender,player,biomes);
    }
}
