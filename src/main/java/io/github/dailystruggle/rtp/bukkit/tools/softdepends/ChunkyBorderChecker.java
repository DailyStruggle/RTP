package io.github.dailystruggle.rtp.bukkit.tools.softdepends;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tools.ChunkyRTPShape;
import org.bukkit.Location;
import org.bukkit.World;
import org.popcraft.chunkyborder.BorderData;
import org.popcraft.chunkyborder.ChunkyBorder;
import org.popcraft.chunkyborder.ChunkyBorderProvider;

import java.util.Optional;

public class ChunkyBorderChecker {
    //stored object reference to skip plugin getting sometimes
    private static ChunkyBorder chunkyBorder = null;

    /**
     * getPAPI - function to if PAPI exists and fill the above object reference accordingly
     */
    private static void getChunky() {
        try {
            chunkyBorder = ChunkyBorderProvider.get();
        } catch ( Throwable t ) {
            chunkyBorder = null;
        }
    }

    public static void loadChunky() {
        //if I don't have a correct object reference, try to get one.
        getChunky();

        // chunkyborder initialization
        if ( chunkyBorder != null ) {
            RTP.serverAccessor.setWorldBorderFunction( worldName -> {
                RTPWorld rtpWorld = RTP.serverAccessor.getRTPWorld(worldName);
                WorldBorder vanillaBorder = null;
                WorldBorder chunkyBorder = null;
                long radiusVanilla = Long.MAX_VALUE;
                if (rtpWorld instanceof BukkitRTPWorld) {
                    BukkitRTPWorld bukkitRTPWorld = (BukkitRTPWorld) rtpWorld;
                    World world = bukkitRTPWorld.world();
                    org.bukkit.WorldBorder worldBorder = world.getWorldBorder();
                    radiusVanilla = ((long) (worldBorder.getSize() * 0.9d)) / 16;
                    vanillaBorder = new WorldBorder(
                            () -> {
                                Shape<?> shape = (Shape<?>) RTP.factoryMap.get(RTP.factoryNames.shape).get("SQUARE");
                                Square square = (Square) shape;
                                square.set(GenericMemoryShapeParams.radius, ((long) (worldBorder.getSize() * 0.9d)) / 16);
                                square.set(GenericMemoryShapeParams.centerRadius, 0L);
                                square.set(GenericMemoryShapeParams.centerX, worldBorder.getCenter().getBlockX() / 16);
                                square.set(GenericMemoryShapeParams.centerZ, worldBorder.getCenter().getBlockZ() / 16);
                                square.set(GenericMemoryShapeParams.expand, false);
                                square.set(GenericMemoryShapeParams.weight, 1);
                                square.set(GenericMemoryShapeParams.mode, Mode.NEAREST);
                                square.set(GenericMemoryShapeParams.uniquePlacements, false);
                                return shape;
                            },
                            rtpLocation -> {
                                if (RTP.serverAccessor.getServerIntVersion() > 10)
                                    return worldBorder.isInside(new Location(world, rtpLocation.x(), rtpLocation.y(), rtpLocation.z()));
                                Location center = worldBorder.getCenter();
                                double radius = worldBorder.getSize() / 2;
                                RTPLocation c = new RTPLocation(rtpWorld, center.getBlockX(), center.getBlockY(), center.getBlockZ());
                                return c.distanceSquaredXZ(rtpLocation) < Math.pow(radius, 2);
                            }
                    );
                }

                try {
                    Optional<BorderData> borderDataOptional = ChunkyBorderProvider.get().getBorder(worldName);
                    if (borderDataOptional.isPresent()) {
                        BorderData borderData = borderDataOptional.get();

                        Factory<?> factory = RTP.factoryMap.get(RTP.factoryNames.shape);
                        org.popcraft.chunky.shape.Shape border = borderData.getBorder();
                        FactoryValue<?> value = factory.get("chunky_" + border.name());
                        if (!(value instanceof Shape<?>))
                            throw new IllegalStateException("Shape factory is not using shape class");
                        if (!value.myClass.equals(RectangleParams.class))
                            throw new IllegalStateException("chunky shape ` " + "chunky_" + border.name() + " ` not using rectangle as its superclass");
                        @SuppressWarnings("unchecked") Shape<RectangleParams> shape = (Shape<RectangleParams>) value;
                        if (!(shape instanceof ChunkyRTPShape)) {
                            shape = new ChunkyRTPShape("chunky_" + border.name());
                            RTPAPI.addShape(shape);
                        }
                        long radiusX = (long) ((borderData.getRadiusX() * 0.9d)/16);
                        long radiusZ = (long) ((borderData.getRadiusZ() * 0.9d)/16);

                        radiusX = Math.min(radiusX,radiusVanilla);
                        radiusZ = Math.min(radiusZ,radiusVanilla);

                        shape.set(RectangleParams.width, radiusX);
                        shape.set(RectangleParams.height, radiusZ);
                        shape.set(RectangleParams.centerX, borderData.getCenterX() / 16);
                        shape.set(RectangleParams.centerZ, borderData.getCenterZ() / 16);
                        shape.set(RectangleParams.mode, Mode.NEAREST);
                        shape.set(RectangleParams.rotation, 0);
                        shape.set(RectangleParams.uniquePlacements, false);
                        Shape<RectangleParams> finalShape = shape;
                        chunkyBorder = new WorldBorder(() -> finalShape, rtpLocation -> border.isBounding(rtpLocation.x() / 16.0, rtpLocation.z() / 16.0));
                    }
                } catch (Throwable ignored) {

                }

                if(chunkyBorder != null) return chunkyBorder;
                return vanillaBorder;
            } );
        }
    }
}
