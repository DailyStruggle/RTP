package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Polygon;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Walk path of edited, unapplied geometry for the web editor (ADR-104 §4.3, §4.6): the page sends
 * {@code {"type":"walkpath","region":..,"sig":..,"shape":{"name":..,key: YAML scalar,"vertices":[[x,z],..]}}}
 * over the loopback channel and receives {@code {"type":"walkpath","region":..,"sig":..,"data":{..}}} or
 * {@code {..,"error":".."}}. The shape is built from the registered factory prototype exactly as a
 * region file would build it, so add-on shapes work and the page carries no curve model.
 *
 * <p>{@code curve-state} (ADR-106 §5.3) shares the build and limits: the page sends
 * {@code {"type":"curve-state","reqId":..,"region":..,"shape":{..}}} for edited settings and receives
 * {@code {"type":"curve-state","reqId":..,"region":..,"shape":..,"params":{..},"state":{..},"hash":..,"range":..}}
 * - the temporary shape's helper inputs as the snapshot's {@code curve} block carries them, its
 * {@code CurveHash} and range, so the page can replace its estimated helper inputs with the exact
 * ones - or {@code {..,"error":".."}}.
 *
 * <p>Pure math on a detached shape (no world access, no memory file): safe on the feed's async timer
 * (S-005). Input is untrusted: bounded sizes, scalar values only, failures become error replies.
 */
final class EditorWalkPathPreview {

    static final String TYPE = "walkpath";
    static final String CURVE_STATE_TYPE = "curve-state";
    /** Relay frame cap (ADR-106 §5.5); a curve-state reply is a few hundred bytes. */
    static final int MAX_CURVE_STATE_REPLY_CHARS = 32 * 1024;
    static final int MAX_MESSAGE_CHARS = 64 * 1024;
    static final int MAX_VERTICES = 1024;
    static final int MAX_SHAPE_KEYS = 32;
    static final int MAX_SIG_CHARS = 4096;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");
    private static final Pattern REGION = Pattern.compile("[^\\u0000-\\u001f\"\\\\]{1,128}");
    private static final Pattern REQ_ID = Pattern.compile("[A-Za-z0-9_.:\\-]{1,64}");

    private EditorWalkPathPreview() {
    }

    /** A well-formed request: region name, the page's geometry signature (echoed back), shape spec. */
    record Request(String region, String sig, Map<?, ?> shape) {
    }

    /** A well-formed curve-state request: page request id (echoed back), region name, shape spec. */
    record CurveStateRequest(String reqId, String region, Map<?, ?> shape) {
    }

    /** Parses a page message; {@code null} unless it is a well-formed walkpath request. */
    static Request parse(String text) {
        Map<?, ?> m = message(text, TYPE);
        if (m == null) return null;
        if (!(m.get("region") instanceof String region) || !REGION.matcher(region).matches()) return null;
        if (!(m.get("sig") instanceof String sig) || sig.length() > MAX_SIG_CHARS) return null;
        if (!(m.get("shape") instanceof Map<?, ?> shape)) return null;
        return new Request(region, sig, shape);
    }

    /** Parses a page message; {@code null} unless it is a well-formed curve-state request. */
    static CurveStateRequest parseCurveState(String text) {
        Map<?, ?> m = message(text, CURVE_STATE_TYPE);
        if (m == null) return null;
        if (!(m.get("reqId") instanceof String reqId) || !REQ_ID.matcher(reqId).matches()) return null;
        if (!(m.get("region") instanceof String region) || !REGION.matcher(region).matches()) return null;
        if (!(m.get("shape") instanceof Map<?, ?> shape)) return null;
        return new CurveStateRequest(reqId, region, shape);
    }

    private static Map<?, ?> message(String text, String type) {
        if (text == null || text.length() > MAX_MESSAGE_CHARS) return null;
        Object root;
        try {
            root = EditorLoopbackJson.parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return (root instanceof Map<?, ?> m && type.equals(m.get("type"))) ? m : null;
    }

    /**
     * Curve-state reply for one request; never throws. A shape without a curve helper is an error
     * reply (the page keeps its sketch / walkpath fallback).
     */
    static String replyCurveState(CurveStateRequest req) {
        String head = "{\"type\":\"" + CURVE_STATE_TYPE + "\",\"reqId\":" + EditorLoopbackJson.quote(req.reqId())
                + ",\"region\":" + EditorLoopbackJson.quote(req.region()) + ",";
        try {
            MemoryShape<?> shape = build(req.shape());
            String js = shape.toJavaScript();
            if (js == null) {
                return head + "\"error\":" + EditorLoopbackJson.quote("shape has no curve helper") + "}";
            }
            // The plugin's own normalised params: the page hashes exactly what the plugin hashed
            Map<String, Object> body = new LinkedHashMap<>(EditorCurveModel.curve(shape, js));
            body.put("range", shape.getRange());
            String json = EditorSessionManager.mapToJson(body);
            String reply = head + json.substring(1);
            if (reply.length() > MAX_CURVE_STATE_REPLY_CHARS) {
                return head + "\"error\":" + EditorLoopbackJson.quote("curve state too large") + "}";
            }
            return reply;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return head + "\"error\":" + EditorLoopbackJson.quote(String.valueOf(e.getMessage())) + "}";
        } catch (RuntimeException e) {
            RTP.log(java.util.logging.Level.FINE, "[editor] curve-state preview failed", e);
            return head + "\"error\":" + EditorLoopbackJson.quote("shape could not be built: " + e.getClass().getSimpleName()) + "}";
        }
    }

    /** Reply JSON for one request; never throws. */
    static String reply(Request req) {
        return reply(req, json -> true);
    }

    /**
     * Reply JSON that {@code fits} accepts (the channel frame cap, ADR-106 §5.5): the 2,048-point
     * sketch is thinned by keeping every 2nd, 4th, ... point, recorded as {@code data.thinned}, until
     * it fits; an error reply if even 64 points do not. Never throws.
     */
    static String reply(Request req, Predicate<String> fits) {
        String head = "{\"type\":\"" + TYPE + "\",\"region\":" + EditorLoopbackJson.quote(req.region())
                + ",\"sig\":" + EditorLoopbackJson.quote(req.sig()) + ",";
        try {
            MemoryShape<?> shape = build(req.shape());
            Map<String, Object> data = EditorSessionManager.getInstance().generateWalkPathPayload(shape);
            String json = head + "\"data\":" + EditorSessionManager.mapToJson(data) + "}";
            if (fits.test(json) || !(data.get("points") instanceof List<?> points)) return json;
            for (int step = 2; points.size() / step >= 64; step *= 2) {
                List<Object> kept = new ArrayList<>(points.size() / step + 1);
                for (int i = 0; i < points.size(); i += step) kept.add(points.get(i));
                Map<String, Object> thinned = new LinkedHashMap<>(data);
                thinned.put("points", kept);
                thinned.put("thinned", step);
                json = head + "\"data\":" + EditorSessionManager.mapToJson(thinned) + "}";
                if (fits.test(json)) return json;
            }
            return head + "\"error\":" + EditorLoopbackJson.quote("walk path too large for one channel frame") + "}";
        } catch (IllegalArgumentException | IllegalStateException e) {
            return head + "\"error\":" + EditorLoopbackJson.quote(String.valueOf(e.getMessage())) + "}";
        } catch (RuntimeException e) {
            RTP.log(java.util.logging.Level.FINE, "[editor] walk path preview failed", e);
            return head + "\"error\":" + EditorLoopbackJson.quote("shape could not be built: " + e.getClass().getSimpleName()) + "}";
        }
    }

    /**
     * Detached shape for {@code spec}: the factory prototype's clone with the scalar settings applied
     * and, for polygons, the vertex list installed.
     *
     * @throws IllegalArgumentException for unknown names, bad values or a shape without a 1D curve
     * @throws IllegalStateException    when the shape registry is not loaded (S-006)
     */
    @SuppressWarnings("unchecked") // heterogeneous factoryMap holds the shape Factory under a raw value type
    static MemoryShape<?> build(Map<?, ?> spec) {
        if (!(spec.get("name") instanceof String name) || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("shape name missing or invalid");
        }
        if (spec.size() > MAX_SHAPE_KEYS) throw new IllegalArgumentException("too many shape settings");
        Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
        if (factory == null) throw new IllegalStateException("shape registry not loaded");
        Object prototype = factory.get(name);
        if (!(prototype instanceof Shape<?> proto)) throw new IllegalArgumentException("shape '" + name + "' is not registered");

        Map<String, Object> settings = new LinkedHashMap<>();
        List<int[]> vertices = null;
        for (Map.Entry<?, ?> e : spec.entrySet()) {
            if (!(e.getKey() instanceof String key) || "name".equals(key)) continue;
            Object v = e.getValue();
            if ("vertices".equals(key)) {
                vertices = vertices(v);
            } else if (v instanceof String s && s.length() <= 64) {
                settings.put(key, s);
            } else if (v instanceof Number || v instanceof Boolean) {
                settings.put(key, v);
            } else {
                throw new IllegalArgumentException("'" + key + "' must be a scalar");
            }
        }
        Shape<?> shape = proto.clone();
        shape.setData(settings);
        if (shape instanceof Polygon polygon) {
            if (vertices == null) throw new IllegalArgumentException("POLYGON needs vertices");
            polygon.setVertices(vertices);
        }
        if (!(shape instanceof MemoryShape<?> ms)) {
            throw new IllegalArgumentException("shape '" + name + "' has no walk path");
        }
        return ms;
    }

    private static List<int[]> vertices(Object v) {
        if (!(v instanceof List<?> list)) throw new IllegalArgumentException("'vertices' must be a list of [x, z]");
        if (list.size() > MAX_VERTICES) throw new IllegalArgumentException("too many vertices (max " + MAX_VERTICES + ")");
        List<int[]> out = new ArrayList<>(list.size());
        for (Object p : list) {
            if (!(p instanceof List<?> pair) || pair.size() != 2
                    || !(pair.get(0) instanceof Number x) || !(pair.get(1) instanceof Number z)) {
                throw new IllegalArgumentException("vertex " + out.size() + " is not an [x, z] pair");
            }
            double dx = x.doubleValue(), dz = z.doubleValue();
            if (Math.abs(dx) > 30_000_000 || Math.abs(dz) > 30_000_000) {
                throw new IllegalArgumentException("vertex " + out.size() + " is outside the world");
            }
            out.add(new int[]{(int) Math.round(dx), (int) Math.round(dz)});
        }
        return out;
    }
}
