package leafcraft.rtp.API.selection;

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
