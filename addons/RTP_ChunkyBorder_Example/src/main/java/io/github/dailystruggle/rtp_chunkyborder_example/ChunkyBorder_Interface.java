package io.github.dailystruggle.rtp_chunkyborder_example;

import leafcraft.rtp.api.selection.WorldBorderInterface;
import leafcraft.rtp.bukkit.tools.selection.TeleportRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.popcraft.chunkyborder.BorderData;
import org.popcraft.chunkyborder.ChunkyBorder;

import java.util.Locale;
import java.util.Optional;

public class ChunkyBorder_Interface implements WorldBorderInterface {
    private final ChunkyBorder chunkyBorder;

    public ChunkyBorder_Interface() {
        chunkyBorder = Bukkit.getServer().getServicesManager().load(ChunkyBorder.class);
    }

    @Override
    public Boolean isInside(World world, Location location) {
        boolean isInside = world.getWorldBorder().isInside(location);
        Optional<BorderData> borderDataOptional = chunkyBorder.getBorder(world.getName());
        if(borderDataOptional.isPresent() && isInside) {
            BorderData borderData = borderDataOptional.get();
            if (!borderData.getBorder().isBounding(location.getX(), location.getZ())) {
                isInside = false;
            }
        }
        return isInside;
    }

    @Override
    public Integer getRadius(World world) {
        int radius = (int)world.getWorldBorder().getSize()/2;
        Optional<BorderData> borderDataOptional = chunkyBorder.getBorder(world.getName());
        if(borderDataOptional.isPresent()) {
            BorderData borderData = borderDataOptional.get();
            radius = Math.min(radius,(int)Math.min(
                    borderData.getRadiusX(),
                    borderData.getRadiusZ()
            ));
        }
        return radius;
    }

    @Override
    public Location getCenter(World world) {
        Location center = world.getWorldBorder().getCenter();
        Optional<BorderData> borderDataOptional = chunkyBorder.getBorder(world.getName());
        if(borderDataOptional.isPresent()) {
            BorderData borderData = borderDataOptional.get();
            double x = borderData.getCenterX();
            double z = borderData.getCenterZ();
            double y = 64;
            center = new Location(world,x,y,z);
        }
        return center;
    }

    @Override
    public TeleportRegion.Shapes getShape(World world) {
        TeleportRegion.Shapes shape = TeleportRegion.Shapes.SQUARE;
        Optional<BorderData> borderDataOptional = chunkyBorder.getBorder(world.getName());
        if(borderDataOptional.isPresent()) {
            BorderData borderData = borderDataOptional.get();
            switch (borderData.getShape().toUpperCase(Locale.ROOT)) {
                case "CIRCLE" -> shape = TeleportRegion.Shapes.CIRCLE;
                //todo: other chunkyBorder shapes
            }
        }
        return shape;
    }
}
