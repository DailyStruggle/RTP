package io.github.dailystruggle.rtp.softdepends;

import br.net.fabiozumbi12.RedProtect.Bukkit.RedProtect;
import org.bukkit.Location;

public class RedProtectChecker {
    private static boolean exists = true;
    public static boolean isInClaim(Location location) {
        if(exists) {
            try {
                return RedProtect.get().getAPI().getRegion(location) != null;
            } catch (Throwable t) {
                exists = false;
                t.printStackTrace();
            }
        }
        return false;
    }
}
