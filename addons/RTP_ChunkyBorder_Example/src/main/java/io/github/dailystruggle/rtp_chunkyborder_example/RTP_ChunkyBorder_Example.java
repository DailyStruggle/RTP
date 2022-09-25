package io.github.dailystruggle.rtp_chunkyborder_example;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp_chunkyborder_example.shapes.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.popcraft.chunkyborder.BorderData;
import org.popcraft.chunkyborder.ChunkyBorderProvider;

import java.util.Optional;

public final class RTP_ChunkyBorder_Example extends JavaPlugin {

    @Override
    public void onEnable() {


        // Plugin startup logic
        RTP.serverAccessor.setWorldBorderFunction(worldName -> {
            Optional<BorderData> borderDataOptional = ChunkyBorderProvider.get().getBorder(worldName);
            if(borderDataOptional.isPresent()) {
                BorderData borderData = borderDataOptional.get();
                Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
                org.popcraft.chunky.shape.Shape border = borderData.getBorder();
                Shape<GenericMemoryShapeParams> shape = (Shape<GenericMemoryShapeParams>) factory.get(border.name());
                if(!(shape instanceof ChunkyRTPShape)) {
                    shape = new ChunkyRTPShape(border);
                    RTPAPI.addShape(shape);
                }
                double radius = Math.min(borderData.getRadiusX(), borderData.getRadiusZ())*0.9;
                shape.set(GenericMemoryShapeParams.radius,radius);
                Shape<GenericMemoryShapeParams> finalShape = shape;
                return new WorldBorder(() -> finalShape, rtpLocation -> border.isBounding(rtpLocation.x()/16.0, rtpLocation.z()/16.0));
            }
            RTPWorld rtpWorld = RTP.serverAccessor.getRTPWorld(worldName);
            if (rtpWorld instanceof BukkitRTPWorld bukkitRTPWorld) {
                World world = bukkitRTPWorld.world();
                org.bukkit.WorldBorder worldBorder = world.getWorldBorder();
                return new WorldBorder(
                        () -> (Shape<?>) RTP.factoryMap.get(RTP.factoryNames.shape).get("SQUARE"),
                        rtpLocation -> worldBorder.isInside(new Location(world, rtpLocation.x(), rtpLocation.y(), rtpLocation.z())));
            }

            return null;
        });
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
