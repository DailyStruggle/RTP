package leafcraft.rtp.API.selection.worldborder;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.function.*;

public class WorldBorder {
    private final Function<World, Long> getRadius;
    private final Function<World, Location> getCenter;
    private final Function<World, String> getShape;

    public WorldBorder(Function<World, Long> getRadius, Function<World, Location> getCenter, Function<World, String> getShape) {
        this.getRadius = getRadius;
        this.getCenter = getCenter;
        this.getShape = getShape;
    }

    public Long getRadius(World world) {
        return getRadius.apply(world);
    }

    public Location getCenter(World world) {
        return getCenter.apply(world);
    }

    public String getShape(World world) {
        return getShape.apply(world);
    }
}
