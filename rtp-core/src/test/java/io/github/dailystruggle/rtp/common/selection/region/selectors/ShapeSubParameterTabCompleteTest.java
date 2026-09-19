package io.github.dailystruggle.rtp.common.selection.region.selectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle_Normal;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Rectangle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square_Normal;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression test for shape and vertical adjustor sub-parameter tab-completion,
 * verifying parameter maps and suggestions on {@code ShapeParameter.subParams}.
 */
public class ShapeSubParameterTabCompleteTest {

    @Test
    @DisplayName("Square exposes V2 curated sub-parameters")
    void squareSubParameters() {
        Map<String, CommandParameter> p = new Square().getParameters();
        assertCommonShapeKeys(p, "Square");
        assertTrue(p.containsKey("radius"));
        assertTrue(p.containsKey("centerradius"));
        assertTrue(p.containsKey("weight"));
        assertTrue(p.containsKey("expand"));
        assertTrue(p.containsKey("uniqueplacements"));
        assertTrue(p.get("radius").values().contains("256"),
                "radius must offer the V2 curated 256 suggestion");
    }

    @Test
    @DisplayName("Circle exposes V2 curated sub-parameters")
    void circleSubParameters() {
        Map<String, CommandParameter> p = new Circle().getParameters();
        assertCommonShapeKeys(p, "Circle");
        assertTrue(p.containsKey("radius"));
        assertTrue(p.containsKey("centerradius"));
        assertTrue(p.containsKey("weight"));
        assertTrue(p.containsKey("expand"));
    }

    @Test
    @DisplayName("Rectangle exposes V2 curated sub-parameters")
    void rectangleSubParameters() {
        Map<String, CommandParameter> p = new Rectangle().getParameters();
        assertFalse(p.isEmpty(), "Rectangle sub-parameters must not be empty");
        assertTrue(p.containsKey("mode"));
        assertTrue(p.containsKey("width"));
        assertTrue(p.containsKey("height"));
        assertTrue(p.containsKey("rotation"));
        assertTrue(p.containsKey("centerx"));
        assertTrue(p.containsKey("centerz"));
        assertTrue(p.containsKey("uniqueplacements"));
        assertTrue(p.get("rotation").values().contains("45"),
                "rotation must offer the V2 curated 45 suggestion");
    }

    @Test
    @DisplayName("Circle_Normal / Square_Normal expose normal-distribution sub-parameters")
    void normalDistributionSubParameters() {
        for (Map<String, CommandParameter> p : new Map[] {
                new Circle_Normal().getParameters(),
                new Square_Normal().getParameters()
        }) {
            assertCommonShapeKeys(p, "Normal-distribution shape");
            assertTrue(p.containsKey("mean"));
            assertTrue(p.containsKey("deviation"));
            assertTrue(p.containsKey("expand"));
            assertTrue(p.containsKey("uniqueplacements"));
        }
    }

    @Test
    @DisplayName("JumpAdjustor exposes V2 curated sub-parameters")
    void jumpAdjustorSubParameters() {
        Map<String, CommandParameter> p =
                new JumpAdjustor(Collections.emptyList()).getParameters();
        assertFalse(p.isEmpty(), "JumpAdjustor sub-parameters must not be empty");
        assertTrue(p.containsKey("maxy"));
        assertTrue(p.containsKey("miny"));
        assertTrue(p.containsKey("step"));
        assertTrue(p.containsKey("requireskylight"));
        assertTrue(p.get("maxy").values().contains("127"));
    }

    @Test
    @DisplayName("LinearAdjustor exposes V2 curated sub-parameters")
    void linearAdjustorSubParameters() {
        Map<String, CommandParameter> p =
                new LinearAdjustor(Collections.emptyList()).getParameters();
        assertFalse(p.isEmpty(), "LinearAdjustor sub-parameters must not be empty");
        assertTrue(p.containsKey("maxy"));
        assertTrue(p.containsKey("miny"));
        assertTrue(p.containsKey("direction"));
        assertTrue(p.containsKey("requireskylight"));
        assertTrue(p.get("direction").values().contains("0"),
                "direction must offer the V2 curated 0..3 suggestions");
    }

    private static void assertCommonShapeKeys(Map<String, CommandParameter> p, String label) {
        assertNotNull(p, label + " sub-parameters must not be null");
        assertFalse(p.isEmpty(), label + " sub-parameters must not be empty");
        assertTrue(p.containsKey("mode"), label + " must expose 'mode'");
        assertTrue(p.containsKey("centerx"), label + " must expose 'centerx'");
        assertTrue(p.containsKey("centerz"), label + " must expose 'centerz'");
    }
}
