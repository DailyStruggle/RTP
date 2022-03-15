package leafcraft.rtp.API.selection;

import leafcraft.rtp.API.selection.worldborder.WorldBorder;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public class SelectionAPI {
    private static final ConcurrentHashMap<RandomSelectParams, TeleportRegion> tempRegions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<RandomSelectParams, TeleportRegion> permRegions = new ConcurrentHashMap<>();

    //semaphore needed in case of async usage
    //storage for region verifiers to use for ALL regions
    private static final Semaphore regionVerifiersLock = new Semaphore(1);
    private static final ArrayList<MethodHandle> regionVerifiers = new ArrayList<>();

    //storage for worldborder stuff to use for ALL regions
    private static final Semaphore wbLock = new Semaphore(1);
    private static final List<WorldBorder> wbCheckers = new ArrayList<WorldBorder>();

    /**
     * addGlobalRegionVerifier - add a region verifier to use for ALL regions
     * @param verifier verifier method to reference.
     *                 param: org.Bukkit.Location
     *                 return: boolean - true on good location, false on bad location
     */
    public static void addGlobalRegionVerifier(MethodHandle verifier) {
        try {
            regionVerifiersLock.acquire();
        } catch (InterruptedException e) {
            regionVerifiersLock.release();
            return;
        }
        regionVerifiers.add(verifier);
        regionVerifiersLock.release();
    }

    public static void clearGlobalRegionVerifiers() {
        try {
            regionVerifiersLock.acquire();
        } catch (InterruptedException e) {
            regionVerifiersLock.release();
            return;
        }
        regionVerifiers.clear();
        regionVerifiersLock.release();
    }

    public static boolean checkGlobalRegionVerifiers(Location location) {
        try {
            regionVerifiersLock.acquire();
        } catch (InterruptedException e) {
            regionVerifiersLock.release();
            return false;
        }

        for(MethodHandle methodHandle : regionVerifiers) {
            try {
                //if invalid placement, stop and return invalid
                //clone location to prevent methods from messing with the data
                if(!(boolean)methodHandle.invokeExact(location.clone())) return false;
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }
        regionVerifiersLock.release();
        return true;
    }

    /**
     * addWBInterface - add a world border verifier to use for ALL regions
     * @param worldBorder world border stuff to reference.
     */
    public static void addWorldBorder(WorldBorder worldBorder) {
        try {
            wbLock.acquire();
        } catch (InterruptedException e) {
            wbLock.release();
            return;
        }
        wbCheckers.add(worldBorder);
        wbLock.release();
    }

    public static WorldBorder getWorldBorder(Location location) {
        if(wbCheckers.size() == 0) {
            return new WorldBorder(
                    world -> (long)world.getWorldBorder().getSize()/2,
                    world -> world.getWorldBorder().getCenter(),
                    world -> "Square"
            );
        }

        World world = location.getWorld();
        long radius = Bukkit.getMaxWorldSize();

        WorldBorder selection = wbCheckers.get(0);

        //use center of most restricted worldborder
        for(WorldBorder worldBorder : wbCheckers) {
            long cmpRadius = worldBorder.getRadius(world);
            if(cmpRadius < radius) {
                radius = cmpRadius;
                selection = worldBorder;
            }
        }
        return selection;
    }

    /**
     * get a region by name
     * @param regionName - name of region
     * @return region by that name, or null if none
     */
    @Nullable
    public static TeleportRegion getRegion(String regionName) {
        Map<String,String> params = new HashMap<>();
        params.put("region",regionName);

        String worldName = (String) Configs.regions.getRegionSetting(regionName,"world","");
        if (worldName == null || worldName.equals("") || !Configs.worlds.checkWorldExists(worldName)) {
            return null;
        }

        RandomSelectParams randomSelectParams = new RandomSelectParams(Objects.requireNonNull(Bukkit.getWorld(worldName)),params);
        if(!permRegions.containsKey(randomSelectParams)) return null;
        return permRegions.get(randomSelectParams);
    }

    /**
     * add or update a region by name
     * @param regionName - name of region
     * @param params - mapped parameters, based on parameters in regions.yml
     * @return the corresponding TeleportRegion
     */
    @Nullable
    public static TeleportRegion setRegion(String regionName, Map<String,String> params) {
        params.put("region",regionName);

        String worldName = (String) Configs.regions.getRegionSetting(regionName,"world","");
        if (worldName == null || worldName.equals("") || !Configs.worlds.checkWorldExists(worldName)) {
            return null;
        }

        RandomSelectParams randomSelectParams = new RandomSelectParams(Objects.requireNonNull(Bukkit.getWorld(worldName)),params);
        if(permRegions.containsKey(randomSelectParams)) {
            permRegions.get(randomSelectParams).shutdown();
        }

        Configs.regions.setRegion(regionName,randomSelectParams);
        return permRegions.put(randomSelectParams,
                new TeleportRegion(regionName,randomSelectParams.params));
    }
}
