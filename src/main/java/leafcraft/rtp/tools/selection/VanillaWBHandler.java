package leafcraft.rtp.tools.selection;

import leafcraft.rtp.API.selection.WorldBorderInterface;
import leafcraft.rtp.RTP;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;

public class VanillaWBHandler implements WorldBorderInterface {

    @Override
    public Boolean isInside(World world, Location location) {
        WorldBorder border = world.getWorldBorder();

        if(RTP.getServerIntVersion() > 8) return border.isInside(location);
        else {
            double size = border.getSize();
            Location center = border.getCenter();
            double x = location.getX() - center.getX(), z = location.getZ() - center.getZ();
            return !((x > size || (-x) > size) || (z > size || (-z) > size));
        }
    }

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
