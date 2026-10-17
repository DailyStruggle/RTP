package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.CurveHash;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Polygon;
import io.github.dailystruggle.rtp.common.selection.region.util.DistanceParser;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Snapshot-side curve data for shapes with a JavaScript curve helper (ADR-106 §4.3, §4.4, §4.7):
 * typed setting metadata, the per-region {@code curve} block, {@code curveCode} entries and the
 * curve-space {@code hazardRuns} layer. Pure in-memory reads of the shape (S-005).
 */
final class EditorCurveModel {

    /** Encoded hazard-run bytes per region; above it the region keeps the 2D RLE layer (§4.7). */
    static final int MAX_HAZARD_RUN_BYTES = 256 * 1024;
    /** Suggestions listed per setting in the schema. */
    static final int MAX_SUGGESTIONS = 32;
    /** Polygon setting carrying the vertex list ({@code [[x, z], ...]}); not an enum key. */
    static final String VERTICES = "vertices";
    static final String HAZARD_CAP = "hazard-cap";

    private static final int UNIQUE_PLACEMENT = LocationGenerator.FailTypes.uniquePlacement.ordinal();
    private static final int MISC = LocationGenerator.FailTypes.misc.ordinal();
    private static final Pattern WORD_BOUNDARY = Pattern.compile("\\b");

    private EditorCurveModel() {
    }

    /** Registered name of a shape as used for {@code curveCode} keys (factory {@code .YML} suffix dropped). */
    static String registeredName(MemoryShape<?> shape) {
        String name = shape.name == null ? shape.getClass().getSimpleName() : shape.name;
        return name.endsWith(".YML") ? name.substring(0, name.length() - 4) : name;
    }

    /**
     * Setting names the helper reads: every data key quoted in the helper source (the helper
     * convention is {@code params['key']} / {@code num(params, 'key', def)}), plus
     * {@link #VERTICES} for polygons whose helper reads it. Empty without a helper.
     */
    static List<String> curveParams(MemoryShape<?> shape, String js) {
        List<String> out = new ArrayList<>();
        if (js == null) return out;
        for (Enum<?> key : shape.myClass.getEnumConstants()) {
            if (mentions(js, key.name())) out.add(key.name());
        }
        if (shape instanceof Polygon && mentions(js, VERTICES)) out.add(VERTICES);
        return out;
    }

    /** Literal-key equivalent of {@code ['"]key['"]|\.key\b}; transparent bounds keep {@code \b} exact. */
    private static boolean mentions(String js, String key) {
        int len = js.length();
        Matcher boundary = null;
        for (int i = js.indexOf(key, 1); i >= 1; i = i < len ? js.indexOf(key, i + 1) : -1) {
            char before = js.charAt(i - 1);
            int end = i + key.length();
            if (isQuote(before) && end < len && isQuote(js.charAt(end))) return true;
            if (before == '.') {
                if (boundary == null) boundary = WORD_BOUNDARY.matcher(js).useTransparentBounds(true);
                if (boundary.region(end, len).lookingAt()) return true;
            }
        }
        return false;
    }

    private static boolean isQuote(char c) {
        return c == '\'' || c == '"';
    }

    /** The settings in {@code keys}, normalised as {@link #normalise(Object)} does. */
    static Map<String, Object> params(MemoryShape<?> shape, List<String> keys) {
        Map<String, Object> byName = new HashMap<>();
        shape.getData().forEach((k, v) -> byName.put(((Enum<?>) k).name(), v));
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : keys) {
            if (VERTICES.equals(key) && shape instanceof Polygon polygon) {
                List<int[]> vertices = polygon.getVertices();
                if (vertices != null && !vertices.isEmpty()) out.put(key, new ArrayList<>(vertices));
                continue;
            }
            Object v = normalise(byName.get(key));
            if (v != null) out.put(key, v);
        }
        return out;
    }

    /**
     * Value as the helper receives it: numbers and booleans as-is, distance strings with a unit in
     * chunks, numeric / boolean strings parsed, enums by name, anything else as its string. Never
     * logs (unlike {@code FactoryValue.getNumber}, which also caches back into the shape).
     */
    static Object normalise(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return compact(n);
        if (v instanceof Boolean) return v;
        if (v instanceof Enum<?> e) return e.name();
        String s = String.valueOf(v).trim();
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) return Boolean.parseBoolean(s);
        String coerced = s.replace(",", ".");
        DistanceParser.ParsedDistance parsed = DistanceParser.parse(coerced, null);
        if (parsed != null && parsed.explicitUnit()) return compact(parsed.toChunks());
        try {
            return compact(Double.parseDouble(coerced));
        } catch (NumberFormatException ignored) {
            return s;
        }
    }

    private static Object compact(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) < 0x1p53) return (long) d;
            return Double.isFinite(d) ? d : null;
        }
        return n;
    }

    /**
     * Per-region {@code curve: {shape, params, state, hash}} (ADR-106 §4.4).
     *
     * @throws RuntimeException when the shape's curve can't be evaluated (caller falls back)
     */
    static Map<String, Object> curve(MemoryShape<?> shape, String js) {
        Map<String, Object> curve = new LinkedHashMap<>();
        curve.put("shape", registeredName(shape));
        curve.put("params", params(shape, curveParams(shape, js)));
        curve.put("state", numericState(shape.curveState()));
        curve.put("hash", CurveHash.of(shape));
        return curve;
    }

    /** {@code curveState()} restricted to finite numbers (the contract allows nothing else). */
    static Map<String, Object> numericState(Map<String, Object> state) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (state == null) return out;
        new TreeMap<>(state).forEach((k, v) -> {
            if (v instanceof Number n) {
                Object c = compact(n);
                if (c != null) out.put(k, c);
            }
        });
        return out;
    }

    /** {@code curveCode} entry {@code {sha256, js}}: lowercase hex SHA-256 of the UTF-8 source. */
    static Map<String, Object> codeEntry(String js) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("sha256", EditorHttpTransport.computeSha256(js));
        entry.put("js", js);
        return entry;
    }

    // ---------------------------------------------------------------------------------------------
    // Hazard runs (§4.7)
    // ---------------------------------------------------------------------------------------------

    /**
     * Learned bad runs in curve-position space as an unsigned LEB128 stream of
     * {@code [deltaStart, len, cause + 1]} triples, {@code deltaStart} counted from the previous
     * run's end (the first from 0). {@code uniquePlacement} spacing marks are left out; overlaps are
     * clipped so runs stay disjoint and ascending.
     *
     * @return encoded bytes, or {@code null} when they exceed {@link #MAX_HAZARD_RUN_BYTES}
     */
    static byte[] encodeHazardRuns(long[] keys, long[] sums, byte[] causes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int n = Math.min(keys.length, sums.length);
        long prevEnd = 0;
        long prevSum = 0;
        for (int i = 0; i < n; i++) {
            long len = sums[i] - prevSum;
            prevSum = sums[i];
            int cause = (i < causes.length) ? (causes[i] & 0xFF) : MISC;
            if (cause == UNIQUE_PLACEMENT || len <= 0) continue;
            long start = keys[i];
            long end = start + len;
            if (start < 0 || end < 0) continue;
            if (start < prevEnd) start = prevEnd;
            if (end <= start) continue;
            writeVarint(out, start - prevEnd);
            writeVarint(out, end - start);
            writeVarint(out, cause + 1L);
            prevEnd = end;
            if (out.size() > MAX_HAZARD_RUN_BYTES) return null;
        }
        return out.toByteArray();
    }

    /** {@link #encodeHazardRuns(long[], long[], byte[])} over the shape's published snapshot. */
    static byte[] encodeHazardRuns(MemoryShape<?> shape) {
        return encodeHazardRuns(shape.badKeysSnapshot(), shape.badPrefixSumsSnapshot(), shape.badCausesSnapshot());
    }

    /** Decodes a run stream back to {@code [start, len, cause + 1]} triples (absolute starts). */
    static List<long[]> decodeHazardRuns(byte[] runs) {
        List<long[]> out = new ArrayList<>();
        int[] pos = {0};
        long prevEnd = 0;
        while (pos[0] < runs.length) {
            long start = prevEnd + readVarint(runs, pos);
            long len = readVarint(runs, pos);
            long value = readVarint(runs, pos);
            out.add(new long[]{start, len, value});
            prevEnd = start + len;
        }
        return out;
    }

    /** Encodes ascending, disjoint {@code [start, len, value]} triples (absolute starts) as a run stream. */
    static byte[] encodeRuns(List<long[]> runs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long prevEnd = 0;
        for (long[] r : runs) {
            writeVarint(out, r[0] - prevEnd);
            writeVarint(out, r[1]);
            writeVarint(out, r[2]);
            prevEnd = r[0] + r[1];
        }
        return out.toByteArray();
    }

    /**
     * Hazard delta (ADR-106 §5.3): runs of {@code before} missing from {@code after} go to
     * {@code remove}, runs of {@code after} missing from {@code before} to {@code add}, matched as whole
     * {@code [start, len, value]} triples. Both inputs ascending and disjoint, so both outputs are too,
     * and {@code before - remove + add == after}.
     */
    static void diffRuns(List<long[]> before, List<long[]> after, List<long[]> add, List<long[]> remove) {
        int i = 0;
        int j = 0;
        while (i < before.size() || j < after.size()) {
            long[] a = i < before.size() ? before.get(i) : null;
            long[] b = j < after.size() ? after.get(j) : null;
            if (a != null && b != null && Arrays.equals(a, b)) {
                i++;
                j++;
            } else if (b == null || (a != null && (a[0] < b[0] || (a[0] == b[0] && !Arrays.equals(a, b))))) {
                remove.add(a);
                i++;
                if (b != null && a[0] == b[0]) {
                    add.add(b);
                    j++;
                }
            } else {
                add.add(b);
                j++;
            }
        }
    }

    private static void writeVarint(ByteArrayOutputStream out, long v) {
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static long readVarint(byte[] b, int[] pos) {
        long v = 0;
        int shift = 0;
        while (true) {
            if (pos[0] >= b.length || shift > 63) throw new IllegalArgumentException("truncated varint");
            int x = b[pos[0]++] & 0xFF;
            v |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) return v;
            shift += 7;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Typed settings (§4.3)
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code kind} of a declared parameter: {@code distance | integer | number | boolean | enum},
     * matched by class name up the hierarchy so the commands-api and rtp-core variants (and add-on
     * subclasses) agree; {@code null} for other parameter types.
     */
    static String kindOf(CommandParameter p) {
        for (Class<?> c = p.getClass(); c != null && c != CommandParameter.class; c = c.getSuperclass()) {
            switch (c.getSimpleName()) {
                case "DistanceParameter": return "distance";
                case "IntegerParameter": return "integer";
                case "FloatParameter": return "number";
                case "BooleanParameter": return "boolean";
                case "EnumParameter": return "enum";
                default: break;
            }
        }
        return null;
    }

    /**
     * Suggested values in a stable order (parameter sets are often hash-based): numbers ascending,
     * then the rest alphabetically; at most {@link #MAX_SUGGESTIONS}.
     */
    static List<String> suggestions(CommandParameter p) {
        Set<String> values;
        try {
            values = p.values();
        } catch (RuntimeException e) {
            return List.of();
        }
        if (values == null) return List.of();
        List<String> list = new ArrayList<>();
        for (String v : values) if (v != null && !v.isBlank()) list.add(v);
        list.sort((a, b) -> {
            Double da = parseOrNull(a), db = parseOrNull(b);
            if (da != null && db != null) return Double.compare(da, db) != 0 ? Double.compare(da, db) : a.compareTo(b);
            if (da != null) return -1;
            if (db != null) return 1;
            return a.compareTo(b);
        });
        return list.size() > MAX_SUGGESTIONS ? new ArrayList<>(list.subList(0, MAX_SUGGESTIONS)) : list;
    }

    private static Double parseOrNull(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
