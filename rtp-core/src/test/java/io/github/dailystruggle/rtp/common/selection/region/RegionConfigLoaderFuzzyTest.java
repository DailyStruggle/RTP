package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RegionConfigLoader - fuzzy shape and vertical adjustor candidate resolution")
class RegionConfigLoaderFuzzyTest {

    private Object previousShapeFactory;
    private Object previousVertFactory;

    @BeforeAll
    static void setupServer() {
        MockRTPServerAccessor accessor = new MockRTPServerAccessor(new File("target/test-data-fuzzy"));
        RTP.serverAccessor = accessor;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
    }

    @BeforeEach
    void setUpFactories() {
        previousShapeFactory = RTP.factoryMap.get(RTP.factoryNames.shape);
        previousVertFactory = RTP.factoryMap.get(RTP.factoryNames.vert);

        Factory<Shape<?>> shapeFactory = new Factory<>();
        shapeFactory.add("CIRCLE", new Circle());
        shapeFactory.add("SQUARE", new Square());
        RTP.factoryMap.put(RTP.factoryNames.shape, shapeFactory);

        Factory<VerticalAdjustor<?>> vertFactory = new Factory<>();
        vertFactory.add("LINEAR", new LinearAdjustor(new ArrayList<>()));
        vertFactory.add("JUMP", new JumpAdjustor(new ArrayList<>()));
        RTP.factoryMap.put(RTP.factoryNames.vert, vertFactory);
    }

    @AfterEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void restoreFactories() {
        if (previousShapeFactory != null) {
            RTP.factoryMap.put(RTP.factoryNames.shape, (Factory) previousShapeFactory);
        }
        if (previousVertFactory != null) {
            RTP.factoryMap.put(RTP.factoryNames.vert, (Factory) previousVertFactory);
        }
    }

    @Test
    @DisplayName("deserializeShape exact match loads silently")
    void testShapeExactMatch() {
        Map<String, Object> map = new HashMap<>();
        map.put("name", "circle");
        Shape<?> shape = RegionConfigLoader.deserializeShape(map, "testRegion");
        assertNotNull(shape);
        assertInstanceOf(Circle.class, shape);

        Map<String, Object> squareMap = new HashMap<>();
        squareMap.put("name", "SQUARE");
        Shape<?> square = RegionConfigLoader.deserializeShape(squareMap, "testRegion");
        assertNotNull(square);
        assertInstanceOf(Square.class, square);
    }

    @Test
    @DisplayName("deserializeShape autocorrects perceptible typos to matching shape prototype")
    void testShapePerceptibleTypo() {
        // "circl" -> CIRCLE
        Map<String, Object> map1 = new HashMap<>();
        map1.put("name", "circl");
        Shape<?> shape1 = RegionConfigLoader.deserializeShape(map1, "testRegion");
        assertNotNull(shape1);
        assertInstanceOf(Circle.class, shape1);

        // "sqare" -> SQUARE
        Map<String, Object> map2 = new HashMap<>();
        map2.put("name", "sqare");
        Shape<?> shape2 = RegionConfigLoader.deserializeShape(map2, "testRegion");
        assertNotNull(shape2);
        assertInstanceOf(Square.class, shape2);
    }

    @Test
    @DisplayName("deserializeShape imperceptible input falls back to CIRCLE")
    void testShapeImperceptibleFallback() {
        Map<String, Object> map = new HashMap<>();
        map.put("name", "xyz12345");
        Shape<?> shape = RegionConfigLoader.deserializeShape(map, "testRegion");
        assertNotNull(shape);
        assertInstanceOf(Circle.class, shape);
    }

    @Test
    @DisplayName("deserializeVert exact match loads silently")
    void testVertExactMatch() {
        Map<String, Object> map1 = new HashMap<>();
        map1.put("name", "linear");
        VerticalAdjustor<?> vert1 = RegionConfigLoader.deserializeVert(map1, "testRegion");
        assertNotNull(vert1);
        assertInstanceOf(LinearAdjustor.class, vert1);

        Map<String, Object> map2 = new HashMap<>();
        map2.put("name", "JUMP");
        VerticalAdjustor<?> vert2 = RegionConfigLoader.deserializeVert(map2, "testRegion");
        assertNotNull(vert2);
        assertInstanceOf(JumpAdjustor.class, vert2);
    }

    @Test
    @DisplayName("deserializeVert autocorrects perceptible typos")
    void testVertPerceptibleTypo() {
        // "linar" -> LINEAR
        Map<String, Object> map1 = new HashMap<>();
        map1.put("name", "linar");
        VerticalAdjustor<?> vert1 = RegionConfigLoader.deserializeVert(map1, "testRegion");
        assertNotNull(vert1);
        assertInstanceOf(LinearAdjustor.class, vert1);

        // "jum" -> JUMP
        Map<String, Object> map2 = new HashMap<>();
        map2.put("name", "jum");
        VerticalAdjustor<?> vert2 = RegionConfigLoader.deserializeVert(map2, "testRegion");
        assertNotNull(vert2);
        assertInstanceOf(JumpAdjustor.class, vert2);
    }

    @Test
    @DisplayName("deserializeVert imperceptible input falls back to LINEAR")
    void testVertImperceptibleFallback() {
        Map<String, Object> map = new HashMap<>();
        map.put("name", "qwerty999");
        VerticalAdjustor<?> vert = RegionConfigLoader.deserializeVert(map, "testRegion");
        assertNotNull(vert);
        assertInstanceOf(LinearAdjustor.class, vert);
    }
}
