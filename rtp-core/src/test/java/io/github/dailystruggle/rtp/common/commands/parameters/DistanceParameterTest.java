package io.github.dailystruggle.rtp.common.commands.parameters;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.*;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.util.DistanceParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DistanceParameter and Shape Sub-Parameter Parsing & Execution Tests")
public class DistanceParameterTest {

  @TempDir
  File tempDir;

  private MockRTPServerAccessor accessor;

  private static class TestRTPCmd extends BaseRTPCmdImpl implements RTPCmd {
    TestRTPCmd() { super(null); }

    @Override
    public boolean onCommand(UUID senderId, Map<String, List<String>> args, CommandsAPICommand next) {
      return onCommand(senderId, args, next, null);
    }

    @Override
    public boolean onCommand(UUID senderId, Map<String, List<String>> args, CommandsAPICommand next,
                             java.util.function.Consumer<String> messageMethod) {
      if (next != null) return true;
      return compute(senderId, args, next, messageMethod);
    }

    @Override public String name() { return "rtp"; }
    @Override public String permission() { return "rtp.use"; }
    @Override public String description() { return "rtp"; }
    @Override public void successEvent(RTPCommandSender sender, RTPPlayer player) {}
    @Override public void failEvent(RTPCommandSender sender, String msg) {}
  }

  @BeforeEach
  void setUp() {
    accessor = RTPTestSetup.install(tempDir);
    if (RTP.selectionAPI == null) {
      RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();
    }

    RTP.addShape(new Circle());
    RTP.addShape(new Ellipse());
    RTP.addShape(new Square());
    RTP.addShape(new Rectangle());
    RTP.addShape(new Circle_Normal());
    RTP.addShape(new Square_Normal());

    io.github.dailystruggle.rtp.common.mock.MockRTPWorld world =
        new io.github.dailystruggle.rtp.common.mock.MockRTPWorld("default_world");
    accessor.addWorld(world);
    Square shape = new Square();
    io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor vert =
        new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(new ArrayList<>());
    io.github.dailystruggle.rtp.common.selection.region.RegionSettings settings =
        new io.github.dailystruggle.rtp.common.selection.region.RegionSettings(
            "default", world, shape, vert,
            false, false,
            10L, 1000L, 0L, 5, 0.0, 1L, "", false);
    Region defRegion = new Region("default", settings);
    RTP.selectionAPI.permRegionLookup.put("default", defRegion);
    RTP.selectionAPI.permRegionLookup.put("DEFAULT", defRegion);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 1. Parsing & Helper Methods Tests
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("DistanceParameter parses units accurately to chunks and blocks")
  void testDistanceParameterUnits() {
    DistanceParser.ParsedDistance bDist = DistanceParameter.parse("4096b");
    assertNotNull(bDist);
    assertEquals(4096.0, bDist.toBlocks(), 0.001);
    assertEquals(256.0, bDist.toChunks(), 0.001);

    DistanceParser.ParsedDistance cDist = DistanceParameter.parse("256c");
    assertNotNull(cDist);
    assertEquals(256.0, cDist.toChunks(), 0.001);
    assertEquals(4096.0, cDist.toBlocks(), 0.001);

    DistanceParser.ParsedDistance rDist = DistanceParameter.parse("4r");
    assertNotNull(rDist);
    assertEquals(128.0, rDist.toChunks(), 0.001); // 4 * 32 = 128 chunks = 2048 blocks
    assertEquals(2048.0, rDist.toBlocks(), 0.001);

    DistanceParser.ParsedDistance kmDist = DistanceParameter.parse("5km");
    assertNotNull(kmDist);
    assertEquals(5000.0, kmDist.toBlocks(), 0.001);
    assertEquals(312.5, kmDist.toChunks(), 0.001);

    DistanceParser.ParsedDistance miDist = DistanceParameter.parse("1mi");
    assertNotNull(miDist);
    assertEquals(1609.344, miDist.toBlocks(), 0.001);

    DistanceParser.ParsedDistance ftDist = DistanceParameter.parse("100ft");
    assertNotNull(ftDist);
    assertEquals(30.48, ftDist.toBlocks(), 0.001);

    assertEquals(256.0, DistanceParameter.parseToChunks("4096b", 0.0), 0.001);
    assertEquals(4096.0, DistanceParameter.parseToBlocks("256c", 0.0), 0.001);
    assertEquals(10.0, DistanceParameter.parseToChunks("invalid", 10.0), 0.001);
    assertEquals(20.0, DistanceParameter.parseToBlocks("invalid", 20.0), 0.001);
  }

  @Test
  @DisplayName("DistanceParameter isRelevant accepts distance strings and relative coords")
  void testDistanceParameterRelevance() {
    DistanceParameter param = new DistanceParameter("test.perm", "desc", (uuid, s) -> true);
    UUID uuid = UUID.randomUUID();

    assertTrue(param.isRelevant.apply(uuid, "4096b"));
    assertTrue(param.isRelevant.apply(uuid, "256c"));
    assertTrue(param.isRelevant.apply(uuid, "4r"));
    assertTrue(param.isRelevant.apply(uuid, "5km"));
    assertTrue(param.isRelevant.apply(uuid, "100ft"));
    assertTrue(param.isRelevant.apply(uuid, "256"));
    assertTrue(param.isRelevant.apply(uuid, "~"));
    assertTrue(param.isRelevant.apply(uuid, "~10"));
    assertTrue(param.isRelevant.apply(uuid, "-~"));

    assertFalse(param.isRelevant.apply(uuid, ""));
    assertFalse(param.isRelevant.apply(uuid, null));
    assertFalse(param.isRelevant.apply(uuid, "abc"));
    assertFalse(param.isRelevant.apply(uuid, "10unknownunit"));
    assertFalse(param.isRelevant.apply(uuid, "~abc"));
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 2. Tab-Completion Tests
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("DistanceParameter values() includes primary units and options")
  void testTabCompletionSuggestions() {
    DistanceParameter param = new DistanceParameter(
        "test.perm", "desc", (uuid, s) -> true, 64, 128, 256, 512, 1024);

    Set<String> values = param.values();
    assertNotNull(values);
    // Verifies provided curated scale options are present
    assertTrue(values.contains("64"));
    assertTrue(values.contains("128"));
    assertTrue(values.contains("256"));
    assertTrue(values.contains("512"));
    assertTrue(values.contains("1024"));

    // Verifies primary units are present as required
    assertTrue(values.contains("64c"));
    assertTrue(values.contains("128c"));
    assertTrue(values.contains("1024b"));
    assertTrue(values.contains("2048b"));
    assertTrue(values.contains("2r"));
    assertTrue(values.contains("4r"));
  }

  @Test
  @DisplayName("DistanceParameter for coordinates includes relative tokens and primary units")
  void testCoordinateTabCompletionSuggestions() {
    DistanceParameter coordParam = new DistanceParameter(
        "test.perm", "center coord", (uuid, s) -> true, "~", "-~", "0");

    Set<String> values = coordParam.values();
    assertTrue(values.contains("~"));
    assertTrue(values.contains("-~"));
    assertTrue(values.contains("0"));
    assertTrue(values.contains("64c"));
    assertTrue(values.contains("1024b"));
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 3. Shape Sub-Parameter Class Verifications
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("All memory shapes replace dimension and coordinate parameters with DistanceParameter")
  void testShapesUseDistanceParameter() {
    // 1. Square
    Square square = new Square();
    Map<String, CommandParameter> sqParams = square.getParameters();
    assertInstanceOf(DistanceParameter.class, sqParams.get("radius"));
    assertInstanceOf(DistanceParameter.class, sqParams.get("centerradius"));
    assertInstanceOf(DistanceParameter.class, sqParams.get("centerx"));
    assertInstanceOf(DistanceParameter.class, sqParams.get("centerz"));

    // 2. Circle
    Circle circle = new Circle();
    Map<String, CommandParameter> cParams = circle.getParameters();
    assertInstanceOf(DistanceParameter.class, cParams.get("radius"));
    assertInstanceOf(DistanceParameter.class, cParams.get("centerradius"));
    assertInstanceOf(DistanceParameter.class, cParams.get("centerx"));
    assertInstanceOf(DistanceParameter.class, cParams.get("centerz"));

    // 3. Square_Normal
    Square_Normal sqNorm = new Square_Normal();
    Map<String, CommandParameter> sqnParams = sqNorm.getParameters();
    assertInstanceOf(DistanceParameter.class, sqnParams.get("radius"));
    assertInstanceOf(DistanceParameter.class, sqnParams.get("centerradius"));
    assertInstanceOf(DistanceParameter.class, sqnParams.get("centerx"));
    assertInstanceOf(DistanceParameter.class, sqnParams.get("centerz"));

    // 4. Circle_Normal
    Circle_Normal cNorm = new Circle_Normal();
    Map<String, CommandParameter> cnParams = cNorm.getParameters();
    assertInstanceOf(DistanceParameter.class, cnParams.get("radius"));
    assertInstanceOf(DistanceParameter.class, cnParams.get("centerradius"));
    assertInstanceOf(DistanceParameter.class, cnParams.get("centerx"));
    assertInstanceOf(DistanceParameter.class, cnParams.get("centerz"));

    // 5. Ellipse
    Ellipse ellipse = new Ellipse();
    Map<String, CommandParameter> elParams = ellipse.getParameters();
    assertInstanceOf(DistanceParameter.class, elParams.get("radius"));
    assertInstanceOf(DistanceParameter.class, elParams.get("radius2"));
    assertInstanceOf(DistanceParameter.class, elParams.get("centerradius"));
    assertInstanceOf(DistanceParameter.class, elParams.get("centerradius2"));
    assertInstanceOf(DistanceParameter.class, elParams.get("centerx"));
    assertInstanceOf(DistanceParameter.class, elParams.get("centerz"));

    // 6. Rectangle
    Rectangle rect = new Rectangle();
    Map<String, CommandParameter> rectParams = rect.getParameters();
    assertInstanceOf(DistanceParameter.class, rectParams.get("width"));
    assertInstanceOf(DistanceParameter.class, rectParams.get("height"));
    assertInstanceOf(DistanceParameter.class, rectParams.get("length"));
    assertInstanceOf(DistanceParameter.class, rectParams.get("centerx"));
    assertInstanceOf(DistanceParameter.class, rectParams.get("centerz"));
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 4. Command Execution with Unit Suffixes
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("/rtp command input with radius:4096b converts accurately to chunk units (256 chunks)")
  void testCommandExecutionWithBlockUnit() {
    UUID playerId = UUID.randomUUID();
    accessor.addPlayer(new MockRTPPlayer(playerId, "testPlayer", null));

    Region region = RTP.selectionAPI.getRegion("default");
    assertNotNull(region);
    region.set(RegionKeys.shape, new Square());

    TestRTPCmd cmd = new TestRTPCmd();
    Map<String, List<String>> args = new HashMap<>();
    args.put("region", List.of("default"));
    args.put("shape", List.of("SQUARE"));
    args.put("radius", List.of("4096b"));

    try {
      cmd.onCommand(playerId, args, null);
    } catch (Throwable ignored) {
    }

    Region executedRegion = RTP.selectionAPI.tempRegions.get(playerId);
    assertNotNull(executedRegion);
    Square shape = (Square) executedRegion.getShape();
    assertNotNull(shape);
    assertEquals(256L, shape.getNumber(GenericMemoryShapeParams.radius, 0L).longValue(),
        "4096b must convert to 256 chunks");
  }

  @Test
  @DisplayName("/rtp command input with radius:256c converts accurately to chunk units (256 chunks)")
  void testCommandExecutionWithChunkUnit() {
    UUID playerId = UUID.randomUUID();
    accessor.addPlayer(new MockRTPPlayer(playerId, "testPlayer", null));

    Region region = RTP.selectionAPI.getRegion("default");
    assertNotNull(region);

    TestRTPCmd cmd = new TestRTPCmd();
    Map<String, List<String>> args = new HashMap<>();
    args.put("region", List.of("default"));
    args.put("shape", List.of("CIRCLE"));
    args.put("radius", List.of("256c"));

    try {
      cmd.onCommand(playerId, args, null);
    } catch (Throwable ignored) {
    }

    Region executedRegion = RTP.selectionAPI.tempRegions.get(playerId);
    assertNotNull(executedRegion);
    Shape<?> shape = executedRegion.getShape();
    assertNotNull(shape);
    assertInstanceOf(Circle.class, shape);
    assertEquals(256L, ((Circle) shape).getNumber(GenericMemoryShapeParams.radius, 0L).longValue(),
        "256c must convert to 256 chunks");
  }

  @Test
  @DisplayName("/rtp command input with radius:4r converts accurately to chunk units (128 chunks)")
  void testCommandExecutionWithRegionUnit() {
    UUID playerId = UUID.randomUUID();
    accessor.addPlayer(new MockRTPPlayer(playerId, "testPlayer", null));

    Region region = RTP.selectionAPI.getRegion("default");
    assertNotNull(region);

    TestRTPCmd cmd = new TestRTPCmd();
    Map<String, List<String>> args = new HashMap<>();
    args.put("region", List.of("default"));
    args.put("shape", List.of("SQUARE"));
    args.put("radius", List.of("4r"));

    try {
      cmd.onCommand(playerId, args, null);
    } catch (Throwable ignored) {
    }

    Region executedRegion = RTP.selectionAPI.tempRegions.get(playerId);
    assertNotNull(executedRegion);
    Shape<?> shape = executedRegion.getShape();
    assertNotNull(shape);
    assertInstanceOf(Square.class, shape);
    assertEquals(128L, ((Square) shape).getNumber(GenericMemoryShapeParams.radius, 0L).longValue(),
        "4r must convert to 128 chunks (4 * 32 chunks)");
  }

  @Test
  @DisplayName("/rtp command input with radius:5km converts accurately to chunk units (312.5 chunks)")
  void testCommandExecutionWithKilometerUnit() {
    UUID playerId = UUID.randomUUID();
    accessor.addPlayer(new MockRTPPlayer(playerId, "testPlayer", null));

    Region region = RTP.selectionAPI.getRegion("default");
    assertNotNull(region);

    TestRTPCmd cmd = new TestRTPCmd();
    Map<String, List<String>> args = new HashMap<>();
    args.put("region", List.of("default"));
    args.put("shape", List.of("CIRCLE"));
    args.put("radius", List.of("5km"));

    try {
      cmd.onCommand(playerId, args, null);
    } catch (Throwable ignored) {
    }

    Region executedRegion = RTP.selectionAPI.tempRegions.get(playerId);
    assertNotNull(executedRegion);
    Shape<?> shape = executedRegion.getShape();
    assertNotNull(shape);
    assertInstanceOf(Circle.class, shape);
    assertEquals(312.5, ((Circle) shape).getNumber(GenericMemoryShapeParams.radius, 0.0).doubleValue(), 0.001,
        "5km (5000 blocks) must convert to 312.5 chunks");
  }

  @Test
  @DisplayName("/rtp command input with length:512c on Rectangle sets height accurately")
  void testCommandExecutionWithRectangleLength() {
    UUID playerId = UUID.randomUUID();
    accessor.addPlayer(new MockRTPPlayer(playerId, "testPlayer", null));

    Region region = RTP.selectionAPI.getRegion("default");
    assertNotNull(region);

    TestRTPCmd cmd = new TestRTPCmd();
    Map<String, List<String>> args = new HashMap<>();
    args.put("region", List.of("default"));
    args.put("shape", List.of("RECTANGLE"));
    args.put("length", List.of("512c"));

    try {
      cmd.onCommand(playerId, args, null);
    } catch (Throwable ignored) {
    }

    Region executedRegion = RTP.selectionAPI.tempRegions.get(playerId);
    assertNotNull(executedRegion);
    Shape<?> shape = executedRegion.getShape();
    assertNotNull(shape);
    assertInstanceOf(Rectangle.class, shape);
    assertEquals(512L, ((Rectangle) shape).getNumber(RectangleParams.height, 0L).longValue(),
        "length:512c on Rectangle must set height to 512 chunks");
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 5. SubspaceShape Unitless Verification
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("SubspaceShape remains strictly unitless lattice cell coordinates")
  void testSubspaceShapeRemainsUnitless() {
    Region region = RTP.selectionAPI.getRegion("default");
    assertNotNull(region);
    io.github.dailystruggle.rtp.common.selection.region.RTPLocation anchor =
        new io.github.dailystruggle.rtp.common.selection.region.RTPLocation(
            new io.github.dailystruggle.rtp.api.world.RTPCoords("default_world", 100, 64, 200), 1);

    SubspaceShape subspace = new SubspaceShape(anchor, 32, region);
    assertEquals(32, subspace.getBlockRadius());
    assertEquals(2, subspace.getChunkRadius()); // ceil(32/16) = 2
    assertEquals(64, subspace.getFootprintBlocks());

    // Coordinates are directly projected without unit conversions or suffixes
    assertEquals(110, subspace.projectX(10));
    assertEquals(195, subspace.projectZ(-5));
  }
}
