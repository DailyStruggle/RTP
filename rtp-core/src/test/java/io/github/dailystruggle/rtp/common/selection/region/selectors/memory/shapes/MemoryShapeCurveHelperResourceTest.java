package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-106 section 4.1 helper SPI: resource lookup through the shape class's own loader,
 * superclass inheritance, per-class caching, the 32 KiB cap, {@code curveState()} and the
 * {@link CurveHash} encoding.
 */
@DisplayName("ADR-106: MemoryShape.toJavaScript() resource SPI, curveState() and CurveHash")
class MemoryShapeCurveHelperResourceTest {

  static {
    MockRTPServerAccessor accessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
    RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
  }

  /** Add-on style shape; its helper resource exists only inside {@link InjectingLoader}. */
  public static class AddonCurveShape extends Square {
    public AddonCurveShape() {
      super("ADDON_CURVE");
    }
  }

  /** Ships an over-cap helper; must not fall back to Square's. */
  public static class OversizedCurveShape extends Square {
    public OversizedCurveShape() {
      super("OVERSIZED_CURVE");
    }
  }

  /** Ships a helper of exactly the cap. */
  public static class BoundaryCurveShape extends Square {
    public BoundaryCurveShape() {
      super("BOUNDARY_CURVE");
    }
  }

  /** No resource of its own: inherits Circle.js. */
  public static class InheritingCircle extends Circle {
    public InheritingCircle() {
      super("INHERITING_CIRCLE");
    }
  }

  /** Direct MemoryShape subclass with no resource anywhere up the chain. */
  public static class BareShape extends MemoryShape<GenericMemoryShapeParams> {
    public BareShape() {
      super(GenericMemoryShapeParams.class, "BARE", Circle.defaults);
    }

    @Override
    public long getRange() {
      return 0L;
    }

    @Override
    public long xzToLocation(long x, long z) {
      return -1L;
    }

    @Override
    public long xzToLocation(MutableRTPCoords coords) {
      return -1L;
    }

    @Override
    public int[] locationToXZ(long location) {
      return new int[] {0, 0};
    }

    @Override
    public void locationToXZ(long location, MutableRTPCoords output) {
      output.setXZ(0, 0);
    }

    @Override
    public int[] select() {
      return null;
    }

    @Override
    public Map<String, CommandParameter> getParameters() {
      return Map.of();
    }
  }

  /**
   * Stands in for an add-on jar's loader: defines one class itself (so {@code getClass()}
   * resolves resources here) and serves resources the parent loader does not have.
   */
  static final class InjectingLoader extends ClassLoader {
    private final String className;
    private final Map<String, byte[]> resources;

    InjectingLoader(String className, Map<String, byte[]> resources) {
      super(MemoryShapeCurveHelperResourceTest.class.getClassLoader());
      this.className = className;
      this.resources = resources;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.equals(className)) return super.loadClass(name, resolve);
      synchronized (getClassLoadingLock(name)) {
        Class<?> c = findLoadedClass(name);
        if (c == null) {
          try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (in == null) throw new ClassNotFoundException(name);
            byte[] bytes = in.readAllBytes();
            c = defineClass(name, bytes, 0, bytes.length);
          } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
          }
        }
        if (resolve) resolveClass(c);
        return c;
      }
    }

    @Override
    public InputStream getResourceAsStream(String name) {
      byte[] bytes = resources.get(name);
      return bytes != null ? new ByteArrayInputStream(bytes) : super.getResourceAsStream(name);
    }
  }

  private static MemoryShape<?> loadInjected(Class<?> type, String resource, byte[] content) throws Exception {
    InjectingLoader loader = new InjectingLoader(type.getName(), Map.of(resource, content));
    Class<?> injected = loader.loadClass(type.getName());
    assertNotSame(type, injected, "class must be defined by the add-on loader");
    return (MemoryShape<?>) injected.getConstructor().newInstance();
  }

  private static String resourceText(String name) throws IOException {
    try (InputStream in = MemoryShape.class.getResourceAsStream("/editor-curve/" + name + ".js")) {
      assertNotNull(in, "missing built-in resource editor-curve/" + name + ".js");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // toJavaScript()
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("each built-in shape class ships its own editor-curve/<SimpleClassName>.js")
  void builtInsResolveTheirOwnResource() throws IOException {
    for (MemoryShape<?> shape : java.util.List.<MemoryShape<?>>of(
        new CircleOptimizedDualLayer(), new SquareOptimizedDualLayer(), new Circle(), new Square(),
        new Rectangle(), new Ellipse(), new Polygon(), new Circle_Normal(), new Square_Normal())) {
      String name = shape.getClass().getSimpleName();
      assertEquals(resourceText(name), shape.toJavaScript(), name);
    }
  }

  @Test
  @DisplayName("registered aliases share one class helper; result is cached per class")
  void cachedPerClass() {
    String a = new SquareOptimizedDualLayer("SQUARE").toJavaScript();
    String b = new SquareOptimizedDualLayer("SQUARE_OPTIMIZED_DUAL_LAYER").toJavaScript();
    assertSame(a, b, "one cached source per class, whatever the registered name");
    assertSame(new BareShape().toJavaScript(), new BareShape().toJavaScript());
  }

  @Test
  @DisplayName("a subclass without a resource inherits the nearest superclass helper")
  void subclassInheritsParentHelper() throws IOException {
    assertEquals(resourceText("Circle"), new InheritingCircle().toJavaScript());
    // Polygon extends Square but ships its own helper: the nearest resource wins.
    assertNotEquals(resourceText("Square"), new Polygon().toJavaScript());
    assertEquals(resourceText("Polygon"), new Polygon().toJavaScript());
  }

  @Test
  @DisplayName("no resource up to MemoryShape gives null; curveState defaults to empty")
  void noHelperGivesNull() {
    BareShape bare = new BareShape();
    assertNull(bare.toJavaScript());
    assertTrue(bare.curveState().isEmpty());
  }

  @Test
  @DisplayName("an add-on resource is found through the shape class's own classloader")
  void addOnResourceViaOwnClassLoader() throws Exception {
    String resource = "editor-curve/AddonCurveShape.js";
    assertNull(MemoryShape.class.getClassLoader().getResource(resource),
        "the resource must exist only in the add-on loader");
    // Loaded by the core loader, the add-on class has no resource and inherits Square.js.
    assertEquals(resourceText("Square"), new AddonCurveShape().toJavaScript());

    String js = "({range: function () { return 1; }, locationToXZ: function () { return [0, 0]; },"
        + " xzToLocation: function () { return 0; }})";
    MemoryShape<?> addon = loadInjected(AddonCurveShape.class, resource, js.getBytes(StandardCharsets.UTF_8));
    assertEquals(js, addon.toJavaScript());
  }

  @Test
  @DisplayName("helpers over 32 KiB are refused (null, no fallback to the parent); exactly 32 KiB is kept")
  void oversizedHelperRefused() throws Exception {
    byte[] over = new byte[MemoryShape.MAX_CURVE_HELPER_BYTES + 1];
    Arrays.fill(over, (byte) ' ');
    MemoryShape<?> oversized =
        loadInjected(OversizedCurveShape.class, "editor-curve/OversizedCurveShape.js", over);
    assertNull(oversized.toJavaScript());
    assertNull(oversized.toJavaScript(), "the refusal is cached");

    byte[] exact = new byte[MemoryShape.MAX_CURVE_HELPER_BYTES];
    Arrays.fill(exact, (byte) ' ');
    exact[0] = '(';
    exact[exact.length - 1] = ')';
    MemoryShape<?> boundary =
        loadInjected(BoundaryCurveShape.class, "editor-curve/BoundaryCurveShape.js", exact);
    assertEquals(MemoryShape.MAX_CURVE_HELPER_BYTES,
        boundary.toJavaScript().getBytes(StandardCharsets.UTF_8).length);
  }

  // ---------------------------------------------------------------------------------------------
  // curveState()
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("curveState: dual-layer square {p}, dual-layer circle {p, rEff}, legacy shapes empty")
  void curveStateShape() {
    SquareOptimizedDualLayer square = new SquareOptimizedDualLayer("SQUARE");
    assertEquals(Map.of("p", square.getPointEdgeChunks()), square.curveState());

    CircleOptimizedDualLayer circle = new CircleOptimizedDualLayer("CIRCLE_STATE", 16);
    circle.set(GenericMemoryShapeParams.radius, 300L);
    assertEquals(Map.of("p", 16, "rEff", 300L), circle.curveState());

    assertTrue(new Circle().curveState().isEmpty());
    assertTrue(new Square().curveState().isEmpty());
    assertTrue(new Rectangle().curveState().isEmpty());
  }

  // ---------------------------------------------------------------------------------------------
  // CurveHash
  // ---------------------------------------------------------------------------------------------

  /** Independent reference: FNV-1a over an explicitly built little-endian byte stream. */
  private static String referenceHash(long range, int[][] xz) {
    ByteBuffer buf = ByteBuffer.allocate(8 + 8 * xz.length).order(ByteOrder.LITTLE_ENDIAN);
    buf.putLong(range);
    for (int[] p : xz) {
      buf.putInt(p == null ? Integer.MIN_VALUE : p[0]);
      buf.putInt(p == null ? Integer.MIN_VALUE : p[1]);
    }
    int h = 0x811C9DC5;
    for (byte b : buf.array()) h = (h ^ (b & 0xFF)) * 0x01000193;
    return String.format("%08x", h);
  }

  @Test
  @DisplayName("CurveHash sample positions: none for R=0, all for R<=256, floor(i*R/256) above")
  void samplePositions() {
    assertEquals(0, CurveHash.samplePositions(0).length);
    assertEquals(0, CurveHash.samplePositions(-5).length);
    assertArrayEquals(new long[] {0, 1, 2}, CurveHash.samplePositions(3));
    assertEquals(256, CurveHash.samplePositions(256).length);
    long[] s = CurveHash.samplePositions(1000);
    assertEquals(256, s.length);
    assertEquals(0, s[0]);
    assertEquals(996, s[255]);
    long big = Long.MAX_VALUE - 7;
    long[] b = CurveHash.samplePositions(big);
    assertEquals(BigInteger.valueOf(big).multiply(BigInteger.valueOf(255)).shiftRight(8).longValue(), b[255]);
    for (int i = 1; i < b.length; i++) assertTrue(b[i] > b[i - 1]);
  }

  @Test
  @DisplayName("CurveHash encoding matches an independent little-endian FNV-1a, null as 0x80000000")
  void hashEncoding() {
    assertEquals(referenceHash(0, new int[0][]), CurveHash.of(0, loc -> new int[] {1, 2}));
    int[][] pts = {{-5, 7}, null, {Integer.MAX_VALUE, Integer.MIN_VALUE}};
    assertEquals(referenceHash(3, pts), CurveHash.of(3, loc -> pts[(int) loc]));
    assertEquals(8, CurveHash.of(new SquareOptimizedDualLayer("SQUARE")).length());
  }

  @Test
  @DisplayName("CurveHash.of(shape) tracks settings: a moved centre changes the hash")
  void hashTracksSettings() {
    Circle a = new Circle();
    Circle b = new Circle();
    assertEquals(CurveHash.of(a), CurveHash.of(b));
    b.set(GenericMemoryShapeParams.centerX, -40L);
    assertNotEquals(CurveHash.of(a), CurveHash.of(b));
  }
}
