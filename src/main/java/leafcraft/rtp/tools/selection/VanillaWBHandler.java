package leafcraft.rtp.tools.selection;

import leafcraft.rtp.API.selection.WorldBorderInterface;
import org.bukkit.Location;
import org.bukkit.World;

public class VanillaWBHandler implements WorldBorderInterface {

    @Override
    public Integer getRadius(World world) {
        return (int)(world.getWorldBorder().getSize()/2);
    }

    @Override
    public Location getCenter(World world) {
        return world.getWorldBorder().getCenter();
    }

    @Override
    public TeleportRegion.Shapes getShape(World world) {
        return TeleportRegion.Shapes.SQUARE;
    }
}
