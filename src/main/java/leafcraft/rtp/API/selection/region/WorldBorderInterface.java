package leafcraft.rtp.API.selection.region;

import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Location;
import org.bukkit.World;

public interface WorldBorderInterface {
    /**
     * @param world - which world to check
     * @return if the selection is within the worldborder
     */
    Boolean isInside(World world, Location location);

    /**
     * @param world - which world to check
     * @return parameters, namely center and radius
     */
    WorldBorderParameters getParameters(World world);
}
