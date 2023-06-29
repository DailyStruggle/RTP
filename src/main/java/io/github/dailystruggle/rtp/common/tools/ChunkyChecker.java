package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import org.popcraft.chunky.Chunky;
import org.popcraft.chunky.ChunkyProvider;
import org.popcraft.chunky.shape.ShapeType;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

//todo: add command to copy the current chunky selection
public class ChunkyChecker {
    //stored object reference to skip plugin getting sometimes
    private static Chunky chunky = null;

    /**
     * getPAPI - function to if PAPI exists and fill the above object reference accordingly
     */
    private static void getChunky() {
        try {
            chunky = ChunkyProvider.get();
        } catch (Throwable t) {
            chunky = null;
        }
    }

    public static void loadChunky() {
        //if I don't have a correct object reference, try to get one.
        getChunky();

        if (chunky != null) {
            for (Field field : ShapeType.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType().equals(String.class)) {
                    String s;
                    try {
                        s = (String) field.get(null);
                    } catch (Throwable t) {
                        continue;
                    }

                    s = "chunky_" + s;

                    Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
                    Shape<RectangleParams> shape = (Shape<RectangleParams>) factory.get(s);
                    if (shape == null) {
                        shape = new ChunkyRTPShape(s);
                        RTPAPI.addShape(shape);
                    }
                }
            }
        }
    }
}
