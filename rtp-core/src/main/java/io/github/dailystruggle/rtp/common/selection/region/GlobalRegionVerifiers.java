package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;
import java.util.logging.Level;

public class GlobalRegionVerifiers {
    private static final Semaphore regionVerifiersLock = new Semaphore(1);
    private static final List<Predicate<RTPCoords>> regionVerifiers = new ArrayList<>();

    /**
     * addGlobalRegionVerifier - add a region verifier to use for ALL regions
     *
     * @param locationCheck verifier method to reference. param: world name, 3D point return: boolean
     *     - true on good location, false on bad location
     */
    public static void addGlobalRegionVerifier(Predicate<RTPCoords> locationCheck) {
        try {
            regionVerifiersLock.acquire();
        } catch (InterruptedException e) {
            regionVerifiersLock.release();
            return;
        }
        regionVerifiers.add(locationCheck);
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

    public static boolean checkGlobalRegionVerifiers(RTPCoords location) {
        try {
            regionVerifiersLock.acquire();
        } catch (InterruptedException e) {
            regionVerifiersLock.release();
            return false;
        }

        for (int i = 0; i < regionVerifiers.size(); i++) {
            Predicate<RTPCoords> verifier = regionVerifiers.get(i);
            try {
                // if invalid placement, stop and return invalid
                // clone location to prevent methods from messing with the data
                if (!verifier.test(location)) {
                    regionVerifiersLock.release();
                    return false;
                }
            } catch (Throwable throwable) {
                RTP.log(Level.WARNING, throwable.getMessage(), throwable);
            }
        }
        regionVerifiersLock.release();
        return true;
    }

    public static boolean checkGlobalRegionVerifiers(MutableRTPCoords location) {
        return checkGlobalRegionVerifiers(location.toImmutable());
    }
}
