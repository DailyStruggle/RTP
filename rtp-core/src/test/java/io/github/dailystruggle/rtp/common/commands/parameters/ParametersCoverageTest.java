package io.github.dailystruggle.rtp.common.commands.parameters;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ShapeParameter and VertParameter tests")
class ParametersCoverageTest {

    @TempDir
    File tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        RTP.addShape(new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle());
        RTP.addVerticalAdjustor(new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(new java.util.ArrayList<>()));
    }

    @Test
    void testShapeParameter() {
        ShapeParameter shapeParam = new ShapeParameter("rtp.shape", "Shape parameter", (uuid, s) -> true);
        Set<String> values = shapeParam.values();
        assertNotNull(values);

        Map<String, CommandParameter> circleSubParams = shapeParam.subParams("circle");
        assertNotNull(circleSubParams);

        Map<String, CommandParameter> unknownSubParams = shapeParam.subParams("unknown_shape_xyz");
        assertNotNull(unknownSubParams);
        assertTrue(unknownSubParams.isEmpty());
    }

    @Test
    void testVertParameter() {
        VertParameter vertParam = new VertParameter("rtp.vert", "Vert parameter", (uuid, s) -> true);
        Set<String> values = vertParam.values();
        assertNotNull(values);

        Map<String, CommandParameter> linearSubParams = vertParam.subParams("linear");
        assertNotNull(linearSubParams);

        Map<String, CommandParameter> unknownSubParams = vertParam.subParams("unknown_vert_xyz");
        assertNotNull(unknownSubParams);
        assertTrue(unknownSubParams.isEmpty());
    }
}
