package io.github.dailystruggle.rtp.softdepends;

import br.net.fabiozumbi12.RedProtect.Bukkit.RedProtect;
import org.bukkit.Location;

public class RedProtectChecker {
    public static boolean isInClaim(Location location) {
        try {
            return RedProtect.get().getAPI().getRegion(location)!=null;
        } catch (NoClassDefFoundError | Exception e) {
            return false;
        }
    }
}
