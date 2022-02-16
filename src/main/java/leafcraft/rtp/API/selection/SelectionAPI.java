package leafcraft.rtp.API.selection;

import leafcraft.rtp.API.selection.region.WorldBorderInterface;
import leafcraft.rtp.API.selection.region.WorldBorderParameters;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;

public class SelectionAPI {
    //todo: replace with simple main thread check
    public enum SyncState {
        SYNC,
        ASYNC,
        ASYNC_URGENT,
    }

    //semaphore needed in case of async usage
    //storage for region verifiers to use for ALL regions
    private static final Semaphore regionVerifiersLock = new Semaphore(1);
    private static final ArrayList<MethodHandle> regionVerifiers = new ArrayList<>();

    //storage for worldborder stuff to use for ALL regions
    private static final Semaphore wbLock = new Semaphore(1);
    private static final ArrayList<WorldBorderInterface> wbCheckers = new ArrayList<>();

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
     * @param worldBorderInterface world border stuff to reference.
     */
    public static void addWBInterface(WorldBorderInterface worldBorderInterface) {
        try {
            wbLock.acquire();
        } catch (InterruptedException e) {
            wbLock.release();
            return;
        }
        wbCheckers.add(worldBorderInterface);
        wbLock.release();
    }

    public static void clearWBInterfaces() {
        try {
            wbLock.acquire();
        } catch (InterruptedException e) {
            wbLock.release();
            return;
        }
        wbCheckers.clear();
        wbLock.release();
    }

    public static WorldBorderParameters getWBParameters(Location location) {
        if(wbCheckers.size() == 0) {
            WorldBorder wb = Bukkit.getWorlds().get(0).getWorldBorder();
            return new WorldBorderParameters((int)wb.getSize()/2,wb.getCenter());
        }

        World world = location.getWorld();
        long radius = Bukkit.getMaxWorldSize();
        WorldBorderParameters params = wbCheckers.get(0).getParameters(world);

        //use center of most restricted worldborder
        for(WorldBorderInterface worldBorderInterface : wbCheckers) {
            WorldBorderParameters cmpParams = worldBorderInterface.getParameters(world);
            long cmpRadius = cmpParams.radius();
            if(cmpRadius < radius) {
                params = worldBorderInterface.getParameters(world);
                radius = cmpRadius;
            }
        }
        return params;
    }


}
