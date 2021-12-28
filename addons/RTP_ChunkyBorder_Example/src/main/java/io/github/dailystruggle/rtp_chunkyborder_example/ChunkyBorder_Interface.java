package io.github.dailystruggle.rtp_chunkyborder_example;

import leafcraft.rtp.API.selection.WorldBorderInterface;
import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.popcraft.chunkyborder.BorderData;
import org.popcraft.chunkyborder.ChunkyBorder;
import org.popcraft.chunkyborder.ChunkyBorderBukkit;

import java.util.Locale;
import java.util.Optional;

public class ChunkyBorder_Interface implements WorldBorderInterface {
    private ChunkyBorder chunkyBorder;

    public ChunkyBorder_Interface() {
        chunkyBorder = Bukkit.getServer().getServicesManager().load(ChunkyBorder.class);
    }

    @Override
    public Integer getRadius(World world) {
        int radius = (int)world.getWorldBorder().getSize()/2;
        Optional<BorderData> borderDataOptional = chunkyBorder.getBorder(world.getName());
        if(borderDataOptional.isPresent()) {
            radius = Math.min(radius,(int)Math.min(
                    borderDataOptional.get().getRadiusX(),
                    borderDataOptional.get().getRadiusZ()
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
            }
        }
        return shape;
    }
}
