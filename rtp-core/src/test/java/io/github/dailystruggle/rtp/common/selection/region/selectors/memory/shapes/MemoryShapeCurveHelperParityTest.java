package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.EllipseMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.NormalDistributionParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ADR-106 section 4.2 parity gate: every built-in {@code editor-curve/*.js} helper, run in
 * GraalJS with the shape's settings as {@code params} and {@link MemoryShape#curveState()} as
 * {@code state}, reproduces the Java {@code getRange} / {@code locationToXZ} /
 * {@code xzToLocation} and {@link CurveHash}. Fresh shapes also match with {@code state}
 * omitted (the helper's own missing-state rule).
 *
 * <p>Java's legacy {@code xzToLocation} returns arbitrary negative values off the curve; the
 * helper contract normalises any negative result to {@code -1}, so expectations do the same.
 */
@DisplayName("ADR-106: editor curve helpers match the Java shapes")
class MemoryShapeCurveHelperParityTest {

  static {
    MockRTPServerAccessor accessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
    RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
  }

  private static final Gson GSON = new Gson();
  private static final int RANDOM_POSITIONS = 160;
  private static final int RANDOM_PROBES = 160;

  /** Batch driver: one host call per case instead of one per position. */
  private static final String DRIVER =
      "(function (h, paramsJson, stateJson, locsJson, xzJson) {\n"
          + "  var params = JSON.parse(paramsJson);\n"
          + "  var state = stateJson === null ? undefined : JSON.parse(stateJson);\n"
          + "  var locs = JSON.parse(locsJson), xz = JSON.parse(xzJson);\n"
          + "  var out = { range: h.range(params, state), toXZ: [], toLoc: [] };\n"
          + "  for (var i = 0; i < locs.length; i++) out.toXZ.push(h.locationToXZ(locs[i], params, state));\n"
          + "  for (var j = 0; j < xz.length; j += 2) out.toLoc.push(h.xzToLocation(xz[j], xz[j + 1], params, state));\n"
          + "  return JSON.stringify(out);\n"
          + "})";

  private static Context context;
  private static Value driver;
  private static final Map<String, Value> HELPERS = new HashMap<>();

  @BeforeAll
  static void startEngine() {
    // Default builder: no host access, no IO, no threads - the helper sees only plain JS.
    context = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
    driver = context.eval("js", DRIVER);
  }

  @AfterAll
  static void stopEngine() {
    HELPERS.clear();
    if (context != null) context.close();
  }

  // ---------------------------------------------------------------------------------------------
  // Cases
  // ---------------------------------------------------------------------------------------------

  /** A shape configuration; {@code fresh} means no ratcheted / learned state. */
  private record Case(String label, Supplier<MemoryShape<?>> factory, boolean fresh, boolean vertexParams) {
    @Override
    public String toString() {
      return label;
    }
  }

  private static Case fresh(String label, Supplier<MemoryShape<?>> factory) {
    return new Case(label, factory, true, false);
  }

  private static Case stateful(String label, Supplier<MemoryShape<?>> factory) {
    return new Case(label, factory, false, false);
  }

  private static <S extends MemoryShape<GenericMemoryShapeParams>> S generic(
      S shape, long r, long cr, long cx, long cz, boolean expand) {
    shape.set(GenericMemoryShapeParams.radius, r);
    shape.set(GenericMemoryShapeParams.centerRadius, cr);
    shape.set(GenericMemoryShapeParams.centerX, cx);
    shape.set(GenericMemoryShapeParams.centerZ, cz);
    shape.set(GenericMemoryShapeParams.expand, expand);
    return shape;
  }

  private static <S extends MemoryShape<NormalDistributionParams>> S normal(
      S shape, long r, long cr, long cx, long cz) {
    shape.set(NormalDistributionParams.radius, r);
    shape.set(NormalDistributionParams.centerRadius, cr);
    shape.set(NormalDistributionParams.centerX, cx);
    shape.set(NormalDistributionParams.centerZ, cz);
    return shape;
  }

  private static Rectangle rectangle(long w, long h, long rotation, long cx, long cz) {
    Rectangle shape = new Rectangle();
    shape.set(RectangleParams.width, w);
    shape.set(RectangleParams.height, h);
    shape.set(RectangleParams.rotation, rotation);
    shape.set(RectangleParams.centerX, cx);
    shape.set(RectangleParams.centerZ, cz);
    return shape;
  }

  private static Ellipse ellipse(long r, long r2, long cr, long cr2, long rotation, long cx, long cz) {
    Ellipse shape = new Ellipse();
    shape.set(EllipseMemoryShapeParams.radius, r);
    shape.set(EllipseMemoryShapeParams.radius2, r2);
    shape.set(EllipseMemoryShapeParams.centerRadius, cr);
    shape.set(EllipseMemoryShapeParams.centerRadius2, cr2);
    shape.set(EllipseMemoryShapeParams.rotation, rotation);
    shape.set(EllipseMemoryShapeParams.centerX, cx);
    shape.set(EllipseMemoryShapeParams.centerZ, cz);
    return shape;
  }

  private static <S extends MemoryShape<?>> S withBadRuns(S shape, int count) {
    for (long i = 0; i < count; i++) shape.addBadLocation(i * 3L);
    shape.flushAndRebuild(shape.spatialResolution());
    return shape;
  }

  private static Polygon polygon(int[][] vertices) {
    Polygon shape = new Polygon();
    List<int[]> list = new ArrayList<>();
    for (int[] v : vertices) list.add(v);
    shape.setVertices(list);
    return shape;
  }

  static Stream<Arguments> cases() {
    List<Case> c = new ArrayList<>();

    // Dual-layer square (SQUARE): derived and pinned P, centre radii, negative centres, expand.
    c.add(fresh("SquareDual default", () -> new SquareOptimizedDualLayer("SQUARE")));
    c.add(fresh("SquareDual r=40 cr=0", () -> generic(new SquareOptimizedDualLayer(), 40, 0, 0, 0, false)));
    c.add(fresh("SquareDual r=500 cr=16 c=(-37,1250)",
        () -> generic(new SquareOptimizedDualLayer(), 500, 16, -37, 1250, false)));
    c.add(fresh("SquareDual r=2000 cr=100 c=(-1001,-3)",
        () -> generic(new SquareOptimizedDualLayer(), 2000, 100, -1001, -3, false)));
    c.add(fresh("SquareDual r=300 cr=64 expand",
        () -> generic(new SquareOptimizedDualLayer(), 300, 64, 5, -7, true)));
    c.add(fresh("SquareDual r=64 cr=64 empty", () -> generic(new SquareOptimizedDualLayer(), 64, 64, 0, 0, false)));
    for (int p : new int[] {8, 16, 32}) {
      c.add(stateful("SquareDual pinned P=" + p,
          () -> generic(new SquareOptimizedDualLayer("SQ_P" + p, p), 256, 33, -12, 40, false)));
    }
    c.add(stateful("SquareDual ratcheted P", () -> {
      SquareOptimizedDualLayer s = generic(new SquareOptimizedDualLayer(), 2048, 0, 0, 0, false);
      s.getPointEdgeChunks();
      s.set(GenericMemoryShapeParams.radius, 100L);
      return s;
    }));
    c.add(stateful("SquareDual expand + bad runs",
        () -> withBadRuns(generic(new SquareOptimizedDualLayer("SQ_EXP", 32), 128, 16, -3, 9, true), 50)));

    // Dual-layer circle (CIRCLE).
    c.add(fresh("CircleDual default", () -> new CircleOptimizedDualLayer("CIRCLE")));
    c.add(fresh("CircleDual r=40 cr=0", () -> generic(new CircleOptimizedDualLayer(), 40, 0, 0, 0, false)));
    c.add(fresh("CircleDual r=500 cr=16 c=(-37,1250)",
        () -> generic(new CircleOptimizedDualLayer(), 500, 16, -37, 1250, false)));
    c.add(fresh("CircleDual r=2000 cr=300 c=(-1001,-3)",
        () -> generic(new CircleOptimizedDualLayer(), 2000, 300, -1001, -3, false)));
    c.add(fresh("CircleDual r=300 cr=64 expand no bad runs",
        () -> generic(new CircleOptimizedDualLayer(), 300, 64, 5, -7, true)));
    c.add(fresh("CircleDual r=10 cr=20 empty", () -> generic(new CircleOptimizedDualLayer(), 10, 20, 0, 0, false)));
    for (int p : new int[] {8, 16, 32}) {
      c.add(stateful("CircleDual pinned P=" + p,
          () -> generic(new CircleOptimizedDualLayer("CI_P" + p, p), 256, 45, 11, -60, false)));
    }
    c.add(stateful("CircleDual ratcheted P", () -> {
      CircleOptimizedDualLayer s = generic(new CircleOptimizedDualLayer(), 2048, 0, 0, 0, false);
      s.getPointEdgeChunks();
      s.set(GenericMemoryShapeParams.radius, 100L);
      return s;
    }));
    c.add(stateful("CircleDual expand + bad runs (rEff > r)",
        () -> withBadRuns(generic(new CircleOptimizedDualLayer("CI_EXP", 32), 128, 16, -3, 9, true), 5000)));

    // Legacy spirals and Normal variants.
    c.add(fresh("Circle default", Circle::new));
    c.add(fresh("Circle r=500 cr=0 c=(-37,1250)", () -> generic(new Circle(), 500, 0, -37, 1250, false)));
    c.add(fresh("Circle r=120 cr=10 c=(-1001,-3)", () -> generic(new Circle(), 120, 10, -1001, -3, true)));
    c.add(fresh("Square default", Square::new));
    c.add(fresh("Square r=500 cr=0 c=(-37,1250)", () -> generic(new Square(), 500, 0, -37, 1250, false)));
    c.add(fresh("Square r=120 cr=1 c=(-1001,-3)", () -> generic(new Square(), 120, 1, -1001, -3, true)));
    c.add(fresh("Square r=30 cr=30 empty", () -> generic(new Square(), 30, 30, 0, 0, false)));
    c.add(fresh("Circle_Normal default", Circle_Normal::new));
    c.add(fresh("Circle_Normal r=400 cr=0 c=(-37,1250)", () -> normal(new Circle_Normal(), 400, 0, -37, 1250)));
    c.add(fresh("Square_Normal default", Square_Normal::new));
    c.add(fresh("Square_Normal r=400 cr=0 c=(-1001,-3)", () -> normal(new Square_Normal(), 400, 0, -1001, -3)));

    // Rectangle, Ellipse, Polygon.
    c.add(fresh("Rectangle default", Rectangle::new));
    c.add(fresh("Rectangle 100x40 rot=0 c=(-37,1250)", () -> rectangle(100, 40, 0, -37, 1250)));
    for (long rot : new long[] {30, 45, 90, 135}) {
      c.add(fresh("Rectangle 33x77 rot=" + rot, () -> rectangle(33, 77, rot, -1001, -3)));
    }
    c.add(fresh("Ellipse default", Ellipse::new));
    c.add(fresh("Ellipse 200x120 hole 30x10 c=(-37,1250)", () -> ellipse(200, 120, 30, 10, 0, -37, 1250)));
    c.add(fresh("Ellipse 200x120 rot=45", () -> ellipse(200, 120, 30, 10, 45, -1001, -3)));
    int[][] concave = {{-40, -30}, {60, -30}, {60, 50}, {10, 5}, {-40, 50}};
    c.add(fresh("Polygon concave", () -> polygon(concave)));
    c.add(new Case("Polygon concave, settings from params.vertices", () -> polygon(concave), true, true));
    int[][] offset = {{-1200, 300}, {-1100, 300}, {-1150, 420}};
    c.add(fresh("Polygon triangle c<0", () -> polygon(offset)));

    return c.stream().map(Arguments::of);
  }

  // ---------------------------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @DisplayName("helper with params + curveState() matches Java range, both mappings and CurveHash")
  void helperMatchesJava(Case c) {
    MemoryShape<?> shape = c.factory().get();
    Map<String, Object> params = paramsOf(shape, c.vertexParams());
    Result js = run(shape, params, shape.curveState(), new Random(c.label().hashCode()));
    assertParity(c.label(), shape, js);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @DisplayName("helper with state omitted matches a fresh shape (missing-state rule)")
  void missingStateRuleMatchesFreshShape(Case c) {
    if (!c.fresh()) return;
    MemoryShape<?> shape = c.factory().get();
    Map<String, Object> params = paramsOf(shape, c.vertexParams());
    Result js = run(shape, params, null, new Random(~c.label().hashCode()));
    assertParity(c.label() + " (no state)", shape, js);
  }

  @Test
  @DisplayName("state is load-bearing: a ratcheted P changes the curve and the hash")
  void ratchetedPointEdgeNeedsState() {
    SquareOptimizedDualLayer s = generic(new SquareOptimizedDualLayer(), 2048, 0, 0, 0, false);
    s.getPointEdgeChunks();
    s.set(GenericMemoryShapeParams.radius, 100L);
    assertEquals(32, s.curveState().get("p"));
    Result withState = run(s, paramsOf(s, false), s.curveState(), new Random(1));
    Result without = run(s, paramsOf(s, false), null, new Random(1));
    assertEquals(CurveHash.of(s), withState.hash);
    assertNotEquals(CurveHash.of(s), without.hash, "derived P must differ from the ratcheted one");
  }

  @Test
  @DisplayName("state is load-bearing: expansion widens CircleOptimizedDualLayer.xzToLocation via rEff")
  void expandedRadiusNeedsState() {
    CircleOptimizedDualLayer s =
        withBadRuns(generic(new CircleOptimizedDualLayer("CI_EXP2", 32), 128, 16, 0, 0, true), 5000);
    long rEff = ((Number) s.curveState().get("rEff")).longValue();
    assertTrue(rEff > 128L, "bad runs must expand rEff");
    Value helper = helper(s.toJavaScript());
    String params = GSON.toJson(paramsOf(s, false));
    String probe = "[" + (rEff - 1) + ",0]";
    JsonObject with = invoke(helper, params, GSON.toJson(s.curveState()), "[]", probe);
    JsonObject without = invoke(helper, params, null, "[]", probe);
    assertEquals(s.xzToLocation(rEff - 1, 0), with.getAsJsonArray("toLoc").get(0).getAsLong());
    assertEquals(-1L, without.getAsJsonArray("toLoc").get(0).getAsLong(), "rEff defaults to radius");
  }

  @Test
  @DisplayName("every built-in helper evaluates to {range, locationToXZ, xzToLocation} under the cap")
  void builtInHelpersAreWellFormed() {
    for (MemoryShape<?> shape : List.<MemoryShape<?>>of(
        new CircleOptimizedDualLayer(), new SquareOptimizedDualLayer(), new Circle(), new Square(),
        new Rectangle(), new Ellipse(), new Polygon(), new Circle_Normal(), new Square_Normal())) {
      String js = shape.toJavaScript();
      assertNotNull(js, shape.getClass().getSimpleName() + " must ship a helper");
      assertTrue(js.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MemoryShape.MAX_CURVE_HELPER_BYTES);
      assertTrue(js.startsWith("("), "helper must be a bare expression (no leading comment)");
      Value h = helper(js);
      for (String fn : new String[] {"range", "locationToXZ", "xzToLocation"}) {
        assertTrue(h.getMember(fn) != null && h.getMember(fn).canExecute(),
            shape.getClass().getSimpleName() + "." + fn + " must be a function");
      }
      // Off-curve inputs are reported, not thrown.
      JsonObject out = invoke(h, "{}", null, "[-1, 0.5]", "[]");
      assertTrue(out.getAsJsonArray("toXZ").get(0).isJsonNull());
      assertTrue(out.getAsJsonArray("toXZ").get(1).isJsonNull());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Harness
  // ---------------------------------------------------------------------------------------------

  private record Result(long range, long[] positions, Map<Long, int[]> toXZ, long[] probes, long[] toLoc, String hash) {}

  private static Value helper(String js) {
    return HELPERS.computeIfAbsent(js, src -> context.eval(Source.create("js", src)));
  }

  private static JsonObject invoke(Value helper, String params, String state, String locs, String xz) {
    String json = driver.execute(helper, params, state, locs, xz).asString();
    return JsonParser.parseString(json).getAsJsonObject();
  }

  /** Settings as the plugin normalises them: numbers, booleans, enum / string names. */
  private static Map<String, Object> paramsOf(MemoryShape<?> shape, boolean vertexParams) {
    Map<String, Object> params = new LinkedHashMap<>();
    for (Map.Entry<?, Object> e : shape.getData().entrySet()) {
      String key = ((Enum<?>) e.getKey()).name();
      Object v = e.getValue();
      params.put(key, (v instanceof Number || v instanceof Boolean) ? v : String.valueOf(v));
    }
    if (vertexParams && shape instanceof Polygon polygon) {
      params.remove("radius");
      params.remove("centerX");
      params.remove("centerZ");
      params.put("centerRadius", 999); // vertices win over stale settings
      params.put("vertices", polygon.getVertices());
    }
    return params;
  }

  private static Result run(MemoryShape<?> shape, Map<String, Object> params, Map<String, Object> state, Random rnd) {
    long range = shape.getRange();
    Set<Long> positions = new LinkedHashSet<>();
    for (long s : CurveHash.samplePositions(range)) positions.add(s);
    for (long s : new long[] {0, 1, 2, range - 2, range - 1, range, range + 1}) {
      if (s >= 0) positions.add(s);
    }
    for (int i = 0; range > 0 && i < RANDOM_POSITIONS; i++) positions.add(rnd.nextLong(range));
    long[] locs = positions.stream().mapToLong(Long::longValue).toArray();

    // Probe chunks: every mapped chunk, its neighbours, and random chunks in the padded bounding box.
    List<Long> probes = new ArrayList<>();
    long minX = Long.MAX_VALUE, maxX = Long.MIN_VALUE, minZ = Long.MAX_VALUE, maxZ = Long.MIN_VALUE;
    for (long loc : locs) {
      int[] xz = shape.locationToXZ(loc);
      probes.add((long) xz[0]);
      probes.add((long) xz[1]);
      probes.add((long) xz[0] + 1);
      probes.add((long) xz[1] - 1);
      minX = Math.min(minX, xz[0]);
      maxX = Math.max(maxX, xz[0]);
      minZ = Math.min(minZ, xz[1]);
      maxZ = Math.max(maxZ, xz[1]);
    }
    for (int i = 0; i < RANDOM_PROBES; i++) {
      probes.add(minX - 8 + (long) (rnd.nextDouble() * (maxX - minX + 17)));
      probes.add(minZ - 8 + (long) (rnd.nextDouble() * (maxZ - minZ + 17)));
    }
    long[] xzFlat = probes.stream().mapToLong(Long::longValue).toArray();

    JsonObject out = invoke(helper(shape.toJavaScript()), GSON.toJson(params),
        state == null ? null : GSON.toJson(state), GSON.toJson(locs), GSON.toJson(xzFlat));

    long jsRange = out.get("range").getAsLong();
    Map<Long, int[]> toXZ = new HashMap<>();
    JsonArray xzOut = out.getAsJsonArray("toXZ");
    for (int i = 0; i < locs.length; i++) {
      JsonElement e = xzOut.get(i);
      toXZ.put(locs[i], e.isJsonNull() ? null
          : new int[] {e.getAsJsonArray().get(0).getAsInt(), e.getAsJsonArray().get(1).getAsInt()});
    }
    JsonArray locOut = out.getAsJsonArray("toLoc");
    long[] toLoc = new long[locOut.size()];
    for (int i = 0; i < toLoc.length; i++) toLoc[i] = locOut.get(i).getAsLong();
    String hash = CurveHash.of(jsRange, toXZ::get);
    return new Result(jsRange, locs, toXZ, xzFlat, toLoc, hash);
  }

  private static void assertParity(String label, MemoryShape<?> shape, Result js) {
    assertEquals(shape.getRange(), js.range, label + ": range");
    for (long loc : js.positions) {
      int[] expected = shape.locationToXZ(loc);
      int[] actual = js.toXZ.get(loc);
      assertNotNull(actual, label + ": locationToXZ(" + loc + ") returned null");
      assertEquals(expected[0], actual[0], label + ": locationToXZ(" + loc + ").x");
      assertEquals(expected[1], actual[1], label + ": locationToXZ(" + loc + ").z");
    }
    assertEquals(js.probes.length / 2, js.toLoc.length);
    for (int i = 0; i < js.toLoc.length; i++) {
      long x = js.probes[2 * i];
      long z = js.probes[2 * i + 1];
      long java = shape.xzToLocation(x, z);
      assertEquals(java < 0 ? -1L : java, js.toLoc[i], label + ": xzToLocation(" + x + ", " + z + ")");
    }
    assertEquals(CurveHash.of(shape), js.hash, label + ": CurveHash");
  }
}
