package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.CircleOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.CurveHash;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ADR-104: walk path of edited geometry is computed by the plugin from the registered shape")
class EditorWalkPathPreviewTest {

    @SuppressWarnings("unchecked")
    private static Factory<Shape<?>> shapes() {
        Factory<Shape<?>> shapes = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
        if (shapes == null) {
            shapes = new Factory<>();
            RTP.factoryMap.put(RTP.factoryNames.shape, shapes);
        }
        return shapes;
    }

    @Test
    @DisplayName("Malformed or foreign messages are not walkpath requests")
    void parseRejects() {
        assertNull(EditorWalkPathPreview.parse(null));
        assertNull(EditorWalkPathPreview.parse("{not json"));
        assertNull(EditorWalkPathPreview.parse("{\"type\":\"focus\",\"world\":\"w\"}"));
        assertNull(EditorWalkPathPreview.parse("{\"type\":\"walkpath\",\"region\":\"r\",\"sig\":\"s\"}"), "shape required");
        assertNull(EditorWalkPathPreview.parse("{\"type\":\"walkpath\",\"region\":\"\",\"sig\":\"s\",\"shape\":{}}"), "region required");
        assertNull(EditorWalkPathPreview.parse("{\"type\":\"walkpath\",\"region\":\"r\",\"sig\":\"s\",\"shape\":{}}"
                + " ".repeat(EditorWalkPathPreview.MAX_MESSAGE_CHARS)), "size bound");
        assertNotNull(EditorWalkPathPreview.parse("{\"type\":\"walkpath\",\"region\":\"default\",\"sig\":\"s\",\"shape\":{\"name\":\"CIRCLE\"}}"));
    }

    @Test
    @DisplayName("Registered shape + edited unit-suffixed geometry -> block-unit seed polyline, sig echoed")
    void replyBuildsRegisteredShape() {
        Factory<Shape<?>> shapes = shapes();
        shapes.add("walkpath_preview_test", new Square());
        try {
            EditorWalkPathPreview.Request req = EditorWalkPathPreview.parse(
                    "{\"type\":\"walkpath\",\"region\":\"default\",\"sig\":\"abc|1\",\"shape\":"
                            + "{\"name\":\"WALKPATH_PREVIEW_TEST\",\"radius\":\"16c\",\"centerRadius\":\"0c\",\"centerX\":\"100\",\"centerZ\":\"0\"}}");
            assertNotNull(req);
            String reply = EditorWalkPathPreview.reply(req);
            Object parsed = EditorLoopbackJson.parse(reply);
            assertTrue(parsed instanceof Map<?, ?>, reply);
            Map<?, ?> m = (Map<?, ?>) parsed;
            assertTrue("walkpath".equals(m.get("type")) && "default".equals(m.get("region")) && "abc|1".equals(m.get("sig")), reply);
            assertNull(m.get("error"), reply);
            Map<?, ?> data = (Map<?, ?>) m.get("data");
            assertNotNull(data, reply);
            assertTrue("block".equals(data.get("units")), reply);
            List<?> points = (List<?>) data.get("points");
            assertTrue(points != null && points.size() > 1, "polyline points: " + reply.substring(0, Math.min(400, reply.length())));
        } finally {
            shapes.remove("walkpath_preview_test");
        }
    }

    @Test
    @DisplayName("Unknown shape and non-scalar values become error replies, never exceptions")
    void replyErrors() {
        shapes();
        String unknown = EditorWalkPathPreview.reply(EditorWalkPathPreview.parse(
                "{\"type\":\"walkpath\",\"region\":\"r\",\"sig\":\"s\",\"shape\":{\"name\":\"NO_SUCH_SHAPE_XYZ\"}}"));
        assertTrue(unknown.contains("\"error\":") && unknown.contains("not registered"), unknown);

        Factory<Shape<?>> shapes = shapes();
        shapes.add("walkpath_preview_test2", new Square());
        try {
            String bad = EditorWalkPathPreview.reply(EditorWalkPathPreview.parse(
                    "{\"type\":\"walkpath\",\"region\":\"r\",\"sig\":\"s\",\"shape\":{\"name\":\"WALKPATH_PREVIEW_TEST2\",\"radius\":{\"x\":1}}}"));
            assertTrue(bad.contains("\"error\":") && bad.contains("scalar"), bad);
        } finally {
            shapes.remove("walkpath_preview_test2");
        }
    }

    private static String curveStateMsg(String reqId, String shape) {
        return "{\"type\":\"curve-state\",\"reqId\":\"" + reqId + "\",\"region\":\"default\",\"shape\":" + shape + "}";
    }

    @Test
    @DisplayName("ADR-106: malformed curve-state requests are rejected; walkpath and curve-state parsers don't cross")
    void curveStateParseRejects() {
        String ok = curveStateMsg("r-1", "{\"name\":\"CIRCLE\"}");
        assertNotNull(EditorWalkPathPreview.parseCurveState(ok));
        assertNull(EditorWalkPathPreview.parse(ok), "curve-state is not a walkpath request");
        assertNull(EditorWalkPathPreview.parseCurveState(
                "{\"type\":\"walkpath\",\"region\":\"default\",\"sig\":\"s\",\"shape\":{\"name\":\"CIRCLE\"}}"));
        assertNull(EditorWalkPathPreview.parseCurveState(null));
        assertNull(EditorWalkPathPreview.parseCurveState("{\"type\":\"curve-state\",\"region\":\"default\",\"shape\":{}}"), "reqId required");
        assertNull(EditorWalkPathPreview.parseCurveState(curveStateMsg("<script>", "{}")), "reqId charset");
        assertNull(EditorWalkPathPreview.parseCurveState(curveStateMsg("x".repeat(65), "{}")), "reqId length");
        assertNull(EditorWalkPathPreview.parseCurveState(curveStateMsg("r", "[]")), "shape must be an object");
        assertNull(EditorWalkPathPreview.parseCurveState(curveStateMsg("r", "{}")
                + " ".repeat(EditorWalkPathPreview.MAX_MESSAGE_CHARS)), "size bound");
    }

    @Test
    @DisplayName("ADR-106: curve-state answers the edited shape's state, hash and range from a temporary shape")
    void curveStateReply() {
        Factory<Shape<?>> shapes = shapes();
        shapes.add("curve_state_test", new CircleOptimizedDualLayer());
        try {
            String spec = "{\"name\":\"CURVE_STATE_TEST\",\"radius\":\"64c\",\"centerRadius\":\"8c\",\"centerX\":\"-300\",\"centerZ\":\"40\"}";
            EditorWalkPathPreview.CurveStateRequest req = EditorWalkPathPreview.parseCurveState(curveStateMsg("edit-7", spec));
            assertNotNull(req);
            String reply = EditorWalkPathPreview.replyCurveState(req);
            assertTrue(reply.length() < 512, "small frame: " + reply);
            Map<?, ?> m = (Map<?, ?>) EditorLoopbackJson.parse(reply);
            assertEquals("curve-state", m.get("type"), reply);
            assertEquals("edit-7", m.get("reqId"));
            assertEquals("default", m.get("region"));
            assertNull(m.get("error"), reply);
            assertFalse(reply.contains("points"), "no path in a curve-state reply");

            MemoryShape<?> expected = EditorWalkPathPreview.build(req.shape());
            Map<?, ?> state = (Map<?, ?>) m.get("state");
            assertEquals(expected.getPointEdgeChunks(), ((Number) state.get("p")).intValue(), reply);
            assertEquals(64L, ((Number) state.get("rEff")).longValue(), reply);
            assertEquals(CurveHash.of(expected), m.get("hash"));
            assertEquals(expected.getRange(), ((Number) m.get("range")).longValue());

            // A larger edit changes range and hash
            String bigger = EditorWalkPathPreview.replyCurveState(EditorWalkPathPreview.parseCurveState(curveStateMsg("edit-8",
                    "{\"name\":\"CURVE_STATE_TEST\",\"radius\":\"1024c\",\"centerRadius\":\"8c\"}")));
            Map<?, ?> b = (Map<?, ?>) EditorLoopbackJson.parse(bigger);
            assertNotEquals(m.get("hash"), b.get("hash"), bigger);
            assertTrue(((Number) b.get("range")).longValue() > ((Number) m.get("range")).longValue(), bigger);
        } finally {
            shapes.remove("curve_state_test");
        }
    }

    @Test
    @DisplayName("ADR-106: curve-state errors (unknown shape, non-scalar, no helper, too many keys) are replies, never exceptions")
    void curveStateErrors() {
        Factory<Shape<?>> shapes = shapes();
        String unknown = EditorWalkPathPreview.replyCurveState(EditorWalkPathPreview.parseCurveState(
                curveStateMsg("a", "{\"name\":\"NO_SUCH_SHAPE_XYZ\"}")));
        assertTrue(unknown.contains("\"reqId\":\"a\"") && unknown.contains("not registered"), unknown);

        shapes.add("curve_state_nohelper", new Square("CURVE_STATE_NOHELPER") {
            @Override
            public String toJavaScript() {
                return null;
            }
        });
        shapes.add("curve_state_test2", new Square());
        try {
            String none = EditorWalkPathPreview.replyCurveState(EditorWalkPathPreview.parseCurveState(
                    curveStateMsg("b", "{\"name\":\"CURVE_STATE_NOHELPER\"}")));
            assertTrue(none.contains("\"error\":") && none.contains("no curve helper"), none);
            assertFalse(none.contains("\"hash\""), none);

            String nonScalar = EditorWalkPathPreview.replyCurveState(EditorWalkPathPreview.parseCurveState(
                    curveStateMsg("c", "{\"name\":\"CURVE_STATE_TEST2\",\"radius\":[1]}")));
            assertTrue(nonScalar.contains("\"error\":") && nonScalar.contains("scalar"), nonScalar);

            StringBuilder many = new StringBuilder("{\"name\":\"CURVE_STATE_TEST2\"");
            for (int i = 0; i < EditorWalkPathPreview.MAX_SHAPE_KEYS + 1; i++) many.append(",\"k").append(i).append("\":1");
            many.append('}');
            String tooMany = EditorWalkPathPreview.replyCurveState(EditorWalkPathPreview.parseCurveState(curveStateMsg("d", many.toString())));
            assertTrue(tooMany.contains("too many shape settings"), tooMany);
        } finally {
            shapes.remove("curve_state_nohelper");
            shapes.remove("curve_state_test2");
        }
    }
}
