package leafcraft.rtp.API.selection;

import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Location;
import org.bukkit.World;

public interface WorldBorderInterface {
    /**
     * @param world - which world to use
     * @return radius, in blocks
     */
    Integer getRadius(World world);

    /**
     * @param world - which world to check
     * @return center point
     */
    Location getCenter(World world);

    /**
     * @param world
     * @return
     */
    TeleportRegion.Shapes getShape(World world);
}
