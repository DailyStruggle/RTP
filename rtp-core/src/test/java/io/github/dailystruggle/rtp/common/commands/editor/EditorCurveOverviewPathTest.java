package io.github.dailystruggle.rtp.common.commands.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Walk-path overview of the editor page (ADR-106 section 4.6): the page's own sampling / pen
 * functions, lifted from docs/editor/index.html and run in GraalJS against the built-in
 * SquareOptimizedDualLayer helper. Samples use a power-of-4 stride (no chords across Hilbert
 * tiles) and points outside centerRadius &lt;= cheb &lt;= radius are never drawn.
 */
@DisplayName("ADR-106: editor walk-path overview samples contiguously and clips to the region domain")
class EditorCurveOverviewPathTest {

    private static final String[] PAGE_FUNCTIONS = {"curveOverviewStride", "curveOverviewPositions", "curveOverviewPen",
            "curveOverviewFactor", "curveOverviewMerge"};

    /** Mirrors prepareCurveModel + the runner: helper -1 / off-range = NaN, null xz = NaN pair. */
    private static final String DRIVER =
            "(function (h, paramsJson, stateJson, withBack, mergeBy) {\n"
                    + "  var params = JSON.parse(paramsJson), state = JSON.parse(stateJson);\n"
                    + "  var range = h.range(params, state);\n"
                    + "  var stride = curveOverviewStride(range);\n"
                    + "  var pos = curveOverviewPositions(range, stride);\n"
                    + "  var xz = new Float64Array(pos.length * 2), back = new Float64Array(pos.length);\n"
                    + "  for (var i = 0; i < pos.length; i++) {\n"
                    + "    var v = h.locationToXZ(pos[i], params, state);\n"
                    + "    if (v === null || v === undefined) { xz[2 * i] = NaN; xz[2 * i + 1] = NaN; back[i] = NaN; continue; }\n"
                    + "    xz[2 * i] = v[0]; xz[2 * i + 1] = v[1];\n"
                    + "    var b = h.xzToLocation(v[0], v[1], params, state);\n"
                    + "    back[i] = b < 0 || !(b < range) ? NaN : b;\n"
                    + "  }\n"
                    + "  var pen = curveOverviewPen(xz, withBack ? back : null, stride);\n"
                    + "  var mg = curveOverviewMerge(xz, pen, stride, mergeBy);\n"
                    + "  var mx = [];\n"
                    + "  for (var c = 0; c < mg.pen.length; c++) { var i = mg.idx ? mg.idx[c] : c; mx.push(xz[2 * i] === xz[2 * i] ? xz[2 * i] : null, xz[2 * i + 1] === xz[2 * i + 1] ? xz[2 * i + 1] : null); }\n"
                    + "  return JSON.stringify({ range: range, stride: stride, n: pos.length, last: pos.length ? pos[pos.length - 1] : -1,\n"
                    + "    xz: mx, pen: Array.from(mg.pen) });\n"
                    + "})";

    private static Context context;
    private static Value driver;
    private static Value square;
    private static Value circle;
    private static Value strideFn;
    private static Value factorFn;
    private static int overviewSamples;

    private static String page() throws IOException {
        Path page = Paths.get("docs", "editor", "index.html");
        if (!Files.exists(page)) page = Paths.get("..", "docs", "editor", "index.html");
        assertTrue(Files.exists(page), "docs/editor/index.html must exist at " + page.toAbsolutePath());
        return Files.readString(page, StandardCharsets.UTF_8);
    }

    /** Source of a top-level page function, from its declaration to the matching closing brace. */
    private static String functionSource(String html, String name) {
        int start = html.indexOf("function " + name + "(");
        assertTrue(start >= 0, "page function missing: " + name);
        int open = html.indexOf('{', html.indexOf(')', start));
        int depth = 0;
        for (int i = open; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return html.substring(start, i + 1);
        }
        throw new AssertionError("unbalanced braces in " + name);
    }

    @BeforeAll
    static void startEngine() throws IOException {
        String html = page();
        Matcher cap = Pattern.compile("const CURVE_OVERVIEW_SAMPLES = (\\d+);").matcher(html);
        assertTrue(cap.find(), "overview sample cap declared");
        overviewSamples = Integer.parseInt(cap.group(1));
        assertEquals(65536, overviewSamples, "overview cap kept at 65,536");

        Matcher px = Pattern.compile("const CURVE_OVERVIEW_MIN_BLOCK_PX = (\\d+);").matcher(html);
        assertTrue(px.find(), "overview block size declared");
        assertEquals(8, Integer.parseInt(px.group(1)), "merged blocks at least 8 px on screen");
        StringBuilder src = new StringBuilder("const CURVE_OVERVIEW_SAMPLES = " + overviewSamples + ";\n"
                + "const CURVE_OVERVIEW_MIN_BLOCK_PX = " + px.group(1) + ";\n");
        for (String fn : PAGE_FUNCTIONS) src.append(functionSource(html, fn)).append('\n');

        context = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
        context.eval("js", src.toString());
        strideFn = context.getBindings("js").getMember("curveOverviewStride");
        factorFn = context.getBindings("js").getMember("curveOverviewFactor");
        driver = context.eval("js", DRIVER);
        square = helper("SquareOptimizedDualLayer");
        circle = helper("CircleOptimizedDualLayer");
    }

    private static Value helper(String name) throws IOException {
        try (InputStream in = EditorCurveOverviewPathTest.class.getResourceAsStream("/editor-curve/" + name + ".js")) {
            assertNotNull(in, "built-in " + name + " helper");
            return context.eval("js", "(" + new String(in.readAllBytes(), StandardCharsets.UTF_8) + "\n)");
        }
    }

    @AfterAll
    static void stopEngine() {
        if (context != null) context.close();
    }

    private static boolean powerOfFour(long v) {
        return v > 0 && (v & (v - 1)) == 0 && Long.numberOfTrailingZeros(v) % 2 == 0;
    }

    private record Overview(long range, long stride, int n, double last, double[] xz, int[] pen) {
        boolean kept(int i) {
            return pen[i] != 0;
        }

        long cheb(int i) {
            return (long) Math.max(Math.abs(xz[2 * i]), Math.abs(xz[2 * i + 1]));
        }
    }

    private static Overview square(int radius, int centerRadius, int p, boolean withBack) {
        return overview(square, radius, centerRadius, 0, 0, p, withBack, 1);
    }

    private static Overview overview(Value h, int radius, int centerRadius, int cx, int cz, int p, boolean withBack, int mergeBy) {
        String params = "{\"radius\":" + radius + ",\"centerRadius\":" + centerRadius + ",\"centerX\":" + cx
                + ",\"centerZ\":" + cz + "}";
        JsonObject o = JsonParser.parseString(driver.execute(h, params, "{\"p\":" + p + "}", withBack, mergeBy).asString())
                .getAsJsonObject();
        JsonArray xzJson = o.getAsJsonArray("xz"), penJson = o.getAsJsonArray("pen");
        double[] xz = new double[xzJson.size()];
        for (int i = 0; i < xz.length; i++) {
            JsonElement e = xzJson.get(i);
            xz[i] = e.isJsonNull() ? Double.NaN : e.getAsDouble();
        }
        int[] pen = new int[penJson.size()];
        for (int i = 0; i < pen.length; i++) pen[i] = penJson.get(i).getAsInt();
        return new Overview(o.get("range").getAsLong(), o.get("stride").getAsLong(), o.get("n").getAsInt(),
                o.get("last").getAsDouble(), xz, pen);
    }

    @Test
    @DisplayName("Stride is the smallest power of 4 keeping the overview within its sample cap")
    void strideIsMinimalPowerOfFour() {
        for (double range : new double[]{0, 1, 4096, 65536, 65537, 245760, 1_000_000, 3.0e9, Math.pow(2, 62)}) {
            long stride = (long) strideFn.execute(range).asDouble();
            assertTrue(powerOfFour(stride), "stride " + stride + " for range " + range + " is a power of 4");
            assertTrue(Math.ceil(range / stride) <= overviewSamples, "range " + range + " fits the cap");
            if (stride > 1) assertTrue(range / (stride / 4) > overviewSamples, "stride " + stride + " minimal for " + range);
        }
    }

    @Test
    @DisplayName("SQUARE r=256c cr=64c p=8: power-of-4 samples, every kept point inside the annulus, no chords")
    void squareOverviewIsContiguousAndInDomain() {
        Overview ov = square(256, 64, 8, true);
        assertEquals(245760, ov.range());
        assertEquals(4, ov.stride(), "range / 65536 = 3.75 rounds up to the next power of 4");
        assertEquals(ov.range() / ov.stride(), ov.n());
        assertEquals(ov.range() - ov.stride() / 2, ov.last(), 0.0, "one sample per whole 4^k block, at its middle, up to the end");

        int kept = 0, moves = 0;
        long maxStep = 0;
        for (int i = 0; i < ov.n(); i++) {
            if (!ov.kept(i)) continue;
            kept++;
            long cheb = ov.cheb(i);
            assertTrue(cheb >= 64 && cheb <= 256, "kept sample " + i + " cheb " + cheb + " inside [64, 256]");
            if (ov.pen()[i] == 2) moves++;
            else {
                int j = i - 1;
                while (!ov.kept(j)) j--;
                long step = (long) Math.max(Math.abs(ov.xz()[2 * i] - ov.xz()[2 * j]), Math.abs(ov.xz()[2 * i + 1] - ov.xz()[2 * j + 1]));
                maxStep = Math.max(maxStep, step);
            }
        }
        System.out.println("[DEBUG_LOG] SQUARE aligned: kept=" + kept + "/" + ov.n() + " moves=" + moves + " maxStep=" + maxStep);
        assertEquals(ov.n(), kept, "aligned annulus: every sample is on the domain");
        assertTrue(maxStep <= 2 * (long) Math.sqrt(ov.stride()), "joined samples are neighbouring Hilbert blocks, max step " + maxStep);
        assertTrue(moves <= 4, "the SQUARE walk is drawn as (nearly) one continuous path, subpaths " + moves);
    }

    @Test
    @DisplayName("SQUARE with off-tile radii: centre and outer-ring overshoot are clipped, unclipped fallback still draws")
    void squareOverviewClipsCentreAndOvershoot() {
        Overview clipped = square(250, 60, 8, true);
        int rejected = 0, kept = 0;
        for (int i = 0; i < clipped.n(); i++) {
            if (!clipped.kept(i)) { rejected++; continue; }
            kept++;
            long cheb = clipped.cheb(i);
            assertTrue(cheb >= 60 && cheb <= 250, "kept sample " + i + " cheb " + cheb + " inside [60, 250]");
            if (i > 0 && !clipped.kept(i - 1)) assertEquals(2, clipped.pen()[i], "pen lifts across a rejected sample at " + i);
        }
        assertTrue(rejected > 0, "tiles straddling the centre / outer edge contribute off-domain samples");
        assertTrue(kept > rejected, "most of the walk is still drawn");

        // No domain check (non-invertible helper): every on-curve sample is still drawn
        Overview raw = square(250, 60, 8, false);
        boolean outside = false;
        for (int i = 0; i < raw.n(); i++) {
            assertTrue(raw.kept(i), "unclipped fallback keeps on-curve sample " + i);
            long cheb = raw.cheb(i);
            outside |= cheb < 60 || cheb > 250;
        }
        assertTrue(outside, "without the domain check the overshoot would be drawn");
    }

    @Test
    @DisplayName("Zoomed out, SQUARE p=8 merges to one point per P-bin: the drawn path is the tile ring walk")
    void mergedOverviewWalksTheBins() {
        assertEquals(16, (long) factorFn.execute(4, 1.0, 61440).asDouble(), "8 px per merged block at 1 px per chunk");
        assertEquals(1, (long) factorFn.execute(4, 4.0, 61440).asDouble(), "2x2 blocks already 8 px wide");

        Overview bins = overview(square, 256, 64, 0, 0, 8, true, 16);
        assertEquals(245760 / 64, bins.n() == 0 ? 0 : bins.pen().length, "one merged point per 8x8 tile");
        int adjacent = 0, joins = 0;
        long[] seen = new long[bins.pen().length];
        for (int c = 0; c < bins.pen().length; c++) {
            assertTrue(bins.kept(c), "aligned annulus: every tile drawn, tile " + c);
            long tx = Math.floorDiv((long) bins.xz()[2 * c], 8), tz = Math.floorDiv((long) bins.xz()[2 * c + 1], 8);
            seen[c] = (tx << 32) ^ (tz & 0xFFFFFFFFL);
            if (c > 0 && bins.pen()[c] == 1) {
                joins++;
                long px = Math.floorDiv((long) bins.xz()[2 * c - 2], 8), pz = Math.floorDiv((long) bins.xz()[2 * c - 1], 8);
                if (Math.abs(tx - px) + Math.abs(tz - pz) == 1) adjacent++;
            }
        }
        assertEquals(seen.length, java.util.Arrays.stream(seen).distinct().count(), "each merged point lies in its own tile");
        assertTrue(adjacent >= joins - 2 * 32, "joined tiles are side neighbours except at ring changes: " + adjacent + "/" + joins);
    }

    @Test
    @DisplayName("CIRCLE r=319c cr=55c off-centre: kept samples lie inside the annulus, tile corners past the disk are clipped")
    void circleOverviewClipsToTheDisk() {
        int r = 319, cr = 55, cx = 262, cz = -3;
        Overview ov = overview(circle, r, cr, cx, cz, 8, true, 1);
        int kept = 0, rejected = 0;
        for (int i = 0; i < ov.pen().length; i++) {
            if (!ov.kept(i)) { rejected++; continue; }
            kept++;
            double dx = ov.xz()[2 * i] - cx, dz = ov.xz()[2 * i + 1] - cz, d2 = dx * dx + dz * dz;
            assertTrue(d2 >= (double) cr * cr && d2 <= (double) r * r, "kept sample " + i + " at distance " + Math.sqrt(d2));
        }
        assertTrue(rejected > 0 && kept > rejected, "corners clipped, disk drawn: kept " + kept + " rejected " + rejected);

        Overview merged = overview(circle, r, cr, cx, cz, 8, true, 16);
        for (int c = 0; c < merged.pen().length; c++) {
            if (!merged.kept(c)) continue;
            double dx = merged.xz()[2 * c] - cx, dz = merged.xz()[2 * c + 1] - cz, d2 = dx * dx + dz * dz;
            assertTrue(d2 >= (double) cr * cr && d2 <= (double) r * r, "merged point " + c + " at distance " + Math.sqrt(d2));
        }
    }

    @Test
    @DisplayName("Page wiring: model caches stride + pen flags, strokes reuse them, exact view drops off-domain ends")
    void pageWiring() throws IOException {
        String html = page();
        String prepare = functionSource(html, "prepareCurveModel");
        assertTrue(prepare.contains("const stride = curveOverviewStride(r.range);"), "power-of-4 stride");
        assertTrue(prepare.contains("curveOverviewPositions(r.range, stride)"), "positions from the stride");
        assertTrue(prepare.contains("await curveDomainBack(m, xz)"), "domain round trip once per model");
        assertTrue(prepare.contains("pen: curveOverviewPen(xz, back, stride)"), "pen flags cached in the model");
        assertFalse(prepare.contains("Math.floor(i * step)"), "no fractional stride sampling left");

        String back = functionSource(html, "curveDomainBack");
        assertTrue(back.contains("if (!m.invertible) return null;"), "no domain check without an inverse");
        assertTrue(back.contains("CurveSandbox.toLoc("), "domain check through the helper's toLoc");

        String check = functionSource(html, "checkCurve");
        assertTrue(check.contains("back[j] !== back[j] || back[j] === pos[i]"),
                "an off-domain round trip (NaN) does not make a clipped helper non-invertible");

        String stroke = functionSource(html, "strokeCurveOverview");
        assertTrue(stroke.contains("ov.pen"), "overview stroke follows cached pen flags");
        assertTrue(stroke.contains("curveOverviewFactor(ov.stride, s16"), "merge factor follows the zoom");
        assertFalse(stroke.contains("CurveSandbox"), "no sandbox call per frame");

        String view = functionSource(html, "computeCurveView");
        assertTrue(view.contains("CurveSandbox.toLoc(shape, params, state, ends)"), "off-rectangle segment ends checked");
        assertTrue(view.contains("if (!(locs[(z - r.z0) * w + (x - r.x0)] < m.range))"), "in-rectangle segment ends checked");
    }
}
