package io.github.dailystruggle.rtp.common.action;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionSessionResult;
import io.github.dailystruggle.rtp.api.group.GroupPlacementRequest;
import io.github.dailystruggle.rtp.api.group.GroupPlacementResult;
import io.github.dailystruggle.rtp.api.group.GroupPlacementService;
import io.github.dailystruggle.rtp.api.group.GroupProfileSpec;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.GroupPlacementDispatcher;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Renders the sample scripted-action test results as PNG charts using the SAME in-JVM Java 2D
 * pipeline the project already uses for its other visual test artifacts ({@code
 * BacklogStrategyComparisonTest} via {@link BufferedImage} + {@link Graphics2D} + {@link ImageIO}),
 * rather than an external Python/matplotlib step.
 *
 * <p>Every coordinate plotted is produced by the SHIPPED code path (real {@code
 * definitions/actions/*.yml} -&gt; {@link ActionConfigLoader} -&gt; {@link ActionManager} -&gt; real
 * {@link GroupPlacementDispatcher} -&gt; {@code SubspaceShape}); the recording {@link
 * GroupPlacementService} captures the exact request/result, and the lifecycle phase timings are
 * measured with {@link System#nanoTime()} around the real trigger/disarm calls. No values are
 * modelled or re-derived.
 *
 * <p>Note: {@code on-join}/{@code on-death} are intentionally excluded here because join/respawn
 * teleports are served by the existing (non-subspace) on-event teleport path, not the subspace
 * action engine.
 */
class SampleActionPlacementChartTest {

  @TempDir File tempDir;

  /** Wraps the real dispatcher and captures the exact request built by ActionManager + its result. */
  private static final class RecordingGroupService implements GroupPlacementService {
    private final GroupPlacementService delegate;
    volatile GroupPlacementRequest lastRequest;
    volatile GroupPlacementResult lastResult;

    RecordingGroupService(GroupPlacementService delegate) {
      this.delegate = delegate;
    }

    @Override
    public CompletableFuture<GroupPlacementResult> place(GroupPlacementRequest request) {
      this.lastRequest = request;
      return delegate.place(request).thenApply(r -> {
        this.lastResult = r;
        return r;
      });
    }
  }

  /** One action's real, engine-produced result plus measured lifecycle timings. */
  private static final class ActionResult {
    String id;
    String shape;
    int radiusBlocks;
    int minSep;
    int participants;
    int anchorX;
    int anchorZ;
    final List<int[]> points = new ArrayList<>();
    String onStart = "";
    double triggerUs; // trigger -> placement -> arm -> onStart (measured)
    double disarmUs; // disarm + cleanup (measured)

    double totalUs() {
      return triggerUs + disarmUs;
    }
  }

  private MockRTPServerAccessor accessor;
  private RecordingGroupService recording;
  private ActionManager manager;
  private GroupPlacementService originalGroupService;

  // The deterministic anchor the region queue resolves to (region.getLocation GenerationResult).
  private static final int QUEUE_ANCHOR_X = 100;
  private static final int QUEUE_ANCHOR_Z = 100;

  private static File shippedResourcesDir() {
    File[] candidates = {
      new File("../rtp-plugin/src/main/resources"), new File("rtp-plugin/src/main/resources"),
    };
    for (File c : candidates) {
      if (new File(c, "definitions/actions").isDirectory()) {
        return c;
      }
    }
    throw new IllegalStateException(
        "Could not locate shipped definitions/actions dir; cwd=" + new File(".").getAbsolutePath());
  }

  @BeforeEach
  void setUp() {
    accessor = RTPTestSetup.install(tempDir);
    RTPWorld<?> world = accessor.getRTPWorld("world");

    // Real dispatcher behind a recording service so ActionManager exercises production placement.
    recording = new RecordingGroupService(new GroupPlacementDispatcher());
    originalGroupService = RTP.groupPlacementService;
    RTP.groupPlacementService = recording;

    // Region "default" (referenced by every shipped action). candidateValidator accepts any
    // column so the geometry of SubspaceShape is what shapes the result; getLocation supplies the
    // deterministic region-queue anchor.
    Region region = mock(Region.class);
    ChunkReservation ticket = mock(ChunkReservation.class);
    GenerationResult anchorGen =
        new GenerationResult(
            new RTPCoords("world", QUEUE_ANCHOR_X, 64, QUEUE_ANCHOR_Z), 1, null, ticket);
    org.mockito.Mockito.doReturn(world).when(region).getWorld();
    when(region.getLocation(anySet())).thenReturn(CompletableFuture.completedFuture(anchorGen));
    when(region.candidateValidator())
        .thenReturn(
            (x, z) ->
                new io.github.dailystruggle.rtp.common.selection.region.RTPLocation(
                    new RTPCoords("world", x, 64, z), 1));

    // Seed spatial memory with a single claim-boundary chunk (FailTypes.safetyExternal, ADR-079)
    // so ClaimHazardAnchorSource recalls a real perimeter anchor for the nearclaim chart.
    io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape<?>
        memShape =
            mock(
                io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes
                    .MemoryShape.class);
    byte safetyExternal =
        (byte)
            io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes
                .safetyExternal
                .ordinal();
    when(memShape.badCausesSnapshot()).thenReturn(new byte[] {safetyExternal});
    when(memShape.badKeysSnapshot()).thenReturn(new long[] {0L});
    when(memShape.locationToXZ(org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(new int[] {40, 40}); // chunk (40,40) -> block (648,648)
    when(memShape.contains(
            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(true);
    when(memShape.isKnownBad(
            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(false);
    org.mockito.Mockito.doReturn(memShape).when(region).getShape();

    RTP.selectionAPI.permRegionLookup.put("default", region);

    manager = new ActionManager();
    ActionConfigLoader.loadActions(shippedResourcesDir(), manager);
  }

  @AfterEach
  void tearDown() {
    RTP.selectionAPI.permRegionLookup.remove("default");
    RTP.groupPlacementService = originalGroupService;
    RTPTestSetup.cleanUp();
  }

  @Test
  @DisplayName("Renders real engine placement + lifecycle charts for every shipped action via Java 2D")
  void renderActionCharts() throws Exception {
    // Each entry: action id, participant count, and (for entity anchor) live context coords.
    record Case(String id, int participants, ActionContext ctx) {}
    List<Case> cases =
        List.of(
            new Case("scatter", 4, ActionContext.EMPTY), // group scatter, regionQueue anchor
            new Case(
                "nearplayer",
                1,
                ActionContext.of(
                    Map.of("anchorWorld", "world", "anchorX", 500, "anchorY", 64, "anchorZ", 500))),
            new Case("nearclaim", 1, ActionContext.EMPTY),
            new Case("location", 1, ActionContext.EMPTY));

    List<ActionResult> results = new ArrayList<>();

    // Warm-up pass to let JIT compile the dynamic dispatch and class-loading paths
    // so benchmark measurements reflect steady-state engine performance.
    for (Case c : cases) {
      UUID warmPid = UUID.randomUUID();
      accessor.addPlayer(new MockRTPPlayer(warmPid, "Warmup", new RTPLocation(accessor.getRTPWorld("world"), 0, 64, 0)));
      ActionSessionResult warmRes = manager.trigger(c.id(), List.of(warmPid), c.ctx()).join();
      if (warmRes.success()) {
        manager.disarm(warmRes.sessionId());
      }
    }

    for (Case c : cases) {
      List<UUID> participants = new ArrayList<>();
      for (int i = 0; i < c.participants(); i++) {
        UUID pid = UUID.randomUUID();
        participants.add(pid);
        accessor.addPlayer(
            new MockRTPPlayer(
                pid, "P" + i, new RTPLocation(accessor.getRTPWorld("world"), 0, 64, 0)));
      }

      int cmdsBefore = accessor.getExecutedCommands().size();

      long t0 = System.nanoTime();
      ActionSessionResult res = manager.trigger(c.id(), participants, c.ctx()).join();
      long t1 = System.nanoTime();
      assertTrue(
          res.success(),
          "shipped action '" + c.id() + "' must place successfully: " + res.failureReason());

      GroupPlacementRequest req = recording.lastRequest;
      GroupPlacementResult result = recording.lastResult;
      assertNotNull(req, "request for '" + c.id() + "' must be captured");
      assertNotNull(result, "result for '" + c.id() + "' must be captured");
      assertTrue(result.isSuccess());

      GroupProfileSpec profile = req.profileSpec();
      // Resolve the true anchor exactly as the dispatcher does (regionQueue -> pre-warmed queue,
      // claimHazard -> safetyExternal perimeter recall, entity/fixed -> supplied coordinate).
      GenerationResult anchorGr =
          io.github.dailystruggle.rtp.common.selection.region.SubspaceAnchorResolver.resolveAnchor(
                  (Region) RTP.selectionAPI.getRegion(req.regionName()), req.anchorSource())
              .join();
      RTPCoords anchor = (anchorGr != null) ? anchorGr.coords() : null;

      ActionResult ar = new ActionResult();
      ar.id = c.id();
      ar.shape = profile.distribution();
      ar.radiusBlocks = profile.radius();
      ar.minSep = profile.minSeparation();
      ar.participants = result.placements().size();
      ar.anchorX = (anchor != null) ? anchor.x() : QUEUE_ANCHOR_X;
      ar.anchorZ = (anchor != null) ? anchor.z() : QUEUE_ANCHOR_Z;
      for (RTPLocation loc : result.placements().values()) {
        assertNotNull(loc);
        ar.points.add(new int[] {loc.x(), loc.z()});
      }

      // Capture the real onStart command line dispatched to a participant (PLAYER "msg ...").
      List<String> executed = accessor.getExecutedCommands();
      for (int i = cmdsBefore; i < executed.size(); i++) {
        String line = executed.get(i);
        if (line != null && line.toLowerCase(java.util.Locale.ROOT).startsWith("msg")) {
          ar.onStart = line;
          break;
        }
      }

      long t2 = System.nanoTime();
      manager.disarm(res.sessionId());
      long t3 = System.nanoTime();

      ar.triggerUs = (t1 - t0) / 1000.0;
      ar.disarmUs = (t3 - t2) / 1000.0;
      results.add(ar);
    }

    renderPlacementCharts(results);
    renderLifecycleChart(results);
    renderComparisonChart(results);
  }

  // --- Java 2D chart rendering (mirrors BacklogStrategyComparisonTest) ------------------------

  private static final Color BG = new Color(13, 17, 23);
  private static final Color PANEL = new Color(22, 27, 34);
  private static final Color BORDER = new Color(48, 54, 61);
  private static final Color GRID = new Color(33, 38, 45);
  private static final Color TEXT = new Color(201, 209, 217);
  private static final Color SUBTLE = new Color(139, 148, 158);
  private static final Color ACCENT = new Color(88, 166, 255);
  private static final Color ACCENT2 = new Color(63, 185, 80);
  private static final Color ANCHOR_C = new Color(240, 136, 62);

  /** One combined image: a 2x2 grid of per-action placement scatter panels. */
  private void renderPlacementCharts(List<ActionResult> results) throws Exception {
    int panelW = 460;
    int panelH = 460;
    int pad = 24;
    int headerH = 90;
    int cols = 2;
    int rows = (results.size() + cols - 1) / cols;

    int totalW = panelW * cols + pad * (cols + 1);
    int totalH = headerH + rows * (panelH + pad) + pad;

    BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = newCanvas(img, totalW, totalH);

    g.setColor(Color.WHITE);
    g.setFont(new Font("SansSerif", Font.BOLD, 22));
    g.drawString("Scripted Action Placement Geometry (real engine output)", pad, 40);
    g.setFont(new Font("SansSerif", Font.PLAIN, 13));
    g.setColor(SUBTLE);
    g.drawString(
        "Coordinates produced by the shipped ActionConfigLoader -> ActionManager -> "
            + "GroupPlacementDispatcher -> SubspaceShape path (ADR-093 / ADR-095)",
        pad,
        64);

    for (int i = 0; i < results.size(); i++) {
      int r = i / cols;
      int c = i % cols;
      int x = pad + c * (panelW + pad);
      int y = headerH + r * (panelH + pad);
      renderPlacementPanel(g, x, y, panelW, panelH, results.get(i));
    }

    g.dispose();
    writeAll(img, "action_placements_chart.png");
  }

  private void renderPlacementPanel(
      Graphics2D g, int x, int y, int w, int h, ActionResult a) {
    g.setColor(PANEL);
    g.fillRoundRect(x, y, w, h, 12, 12);
    g.setColor(BORDER);
    g.drawRoundRect(x, y, w, h, 12, 12);

    Color accent = "circle".equalsIgnoreCase(a.shape) ? ACCENT2 : ACCENT;
    g.setFont(new Font("SansSerif", Font.BOLD, 15));
    g.setColor(accent);
    g.drawString(a.id + "  (" + a.shape + ", r=" + a.radiusBlocks + " blk)", x + 16, y + 26);
    g.setFont(new Font("SansSerif", Font.PLAIN, 11));
    g.setColor(SUBTLE);
    g.drawString(
        "anchor=(" + a.anchorX + "," + a.anchorZ + ")  N=" + a.participants + "  minSep=" + a.minSep,
        x + 16,
        y + 44);

    int viewX = x + 16;
    int viewY = y + 56;
    int viewW = w - 32;
    int viewH = h - 72;
    g.setColor(BG);
    g.fillRect(viewX, viewY, viewW, viewH);
    g.setColor(BORDER);
    g.drawRect(viewX, viewY, viewW, viewH);

    // World-space viewport centered on anchor spanning the footprint plus a margin.
    double span = a.radiusBlocks * 2.4 + 32;
    double minX = a.anchorX - span / 2.0;
    double minZ = a.anchorZ - span / 2.0;

    // Footprint guide (square SUBSPACE box or circle radius) around the anchor.
    g.setColor(GRID);
    g.setStroke(new BasicStroke(1.0f));
    int ax = worldToPx(a.anchorX, minX, span, viewX, viewW);
    int az = worldToPx(a.anchorZ, minZ, span, viewY, viewH);
    if ("circle".equalsIgnoreCase(a.shape)) {
      int rpx = (int) (a.radiusBlocks / span * viewW);
      g.drawOval(ax - rpx, az - rpx, rpx * 2, rpx * 2);
    } else {
      int half = (int) (a.radiusBlocks / span * viewW);
      g.drawRect(ax - half, az - half, half * 2, half * 2);
    }

    // Anchor marker (crosshair).
    g.setColor(ANCHOR_C);
    g.setStroke(new BasicStroke(1.5f));
    g.drawLine(ax - 6, az, ax + 6, az);
    g.drawLine(ax, az - 6, ax, az + 6);

    // Placed participants.
    g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 220));
    for (int[] p : a.points) {
      int px = worldToPx(p[0], minX, span, viewX, viewW);
      int pz = worldToPx(p[1], minZ, span, viewY, viewH);
      g.fillOval(px - 4, pz - 4, 8, 8);
      g.setColor(BG);
      g.drawOval(px - 4, pz - 4, 8, 8);
      g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 220));
    }
  }

  private static int worldToPx(double world, double min, double span, int viewOrigin, int viewSize) {
    return viewOrigin + (int) ((world - min) / span * viewSize);
  }

  /** Horizontal stacked bars of the measured lifecycle phases per action. */
  private void renderLifecycleChart(List<ActionResult> results) throws Exception {
    int pad = 24;
    int headerH = 96;
    int rowH = 58;
    int labelW = 130;
    int barMaxW = 620;
    int totalW = pad * 2 + labelW + barMaxW + 220;
    int totalH = headerH + results.size() * rowH + pad;

    BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = newCanvas(img, totalW, totalH);

    g.setColor(Color.WHITE);
    g.setFont(new Font("SansSerif", Font.BOLD, 22));
    g.drawString("Scripted Action Lifecycle (measured, off-tick)", pad, 40);
    g.setFont(new Font("SansSerif", Font.PLAIN, 13));
    g.setColor(SUBTLE);
    g.drawString(
        "System.nanoTime() around real trigger->place->arm->onStart and disarm calls (off-tick). "
            + "Values in microseconds (\u03bcs) [1 ms = 1,000 \u03bcs, 1 tick = 50 ms = 50,000 \u03bcs].",
        pad,
        64);

    double maxTotal = 0.01;
    for (ActionResult a : results) {
      maxTotal = Math.max(maxTotal, a.totalUs());
    }

    // Legend.
    g.setFont(new Font("SansSerif", Font.PLAIN, 12));
    int lx = pad + labelW;
    g.setColor(ACCENT);
    g.fillRect(lx, 78, 12, 12);
    g.setColor(TEXT);
    g.drawString("trigger + place + arm + onStart", lx + 18, 89);
    g.setColor(ACCENT2);
    g.fillRect(lx + 240, 78, 12, 12);
    g.setColor(TEXT);
    g.drawString("disarm + cleanup", lx + 258, 89);

    int y = headerH;
    for (ActionResult a : results) {
      g.setFont(new Font("SansSerif", Font.BOLD, 13));
      g.setColor(TEXT);
      g.drawString(a.id, pad, y + rowH / 2 + 4);

      int barX = pad + labelW;
      int barY = y + 14;
      int barH = 22;
      int wTrig = (int) (a.triggerUs / maxTotal * barMaxW);
      int wDis = Math.max(2, (int) (a.disarmUs / maxTotal * barMaxW));

      g.setColor(ACCENT);
      g.fillRect(barX, barY, wTrig, barH);
      g.setColor(ACCENT2);
      g.fillRect(barX + wTrig, barY, wDis, barH);

      g.setFont(new Font("Monospaced", Font.PLAIN, 12));
      g.setColor(TEXT);
      g.drawString(
          String.format("%.1f \u03bcs total (%.3f ms)", a.totalUs(), a.totalUs() / 1000.0),
          barX + wTrig + wDis + 10,
          barY + 16);

      // onStart message beneath the bar (truncated).
      g.setFont(new Font("SansSerif", Font.PLAIN, 11));
      g.setColor(SUBTLE);
      String msg = a.onStart.isEmpty() ? "(no onStart)" : "onStart -> " + a.onStart;
      if (msg.length() > 78) {
        msg = msg.substring(0, 75) + "...";
      }
      g.drawString(msg, barX, barY + barH + 15);

      y += rowH;
    }

    g.dispose();
    writeAll(img, "action_lifecycle_chart.png");
  }

  /** Simple comparison bar chart of total lifecycle cost per action. */
  private void renderComparisonChart(List<ActionResult> results) throws Exception {
    int pad = 24;
    int headerH = 90;
    int chartH = 320;
    int barGap = 40;
    int barW = 90;
    int totalW = pad * 2 + results.size() * (barW + barGap) + barGap;
    int totalH = headerH + chartH + 85;

    BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = newCanvas(img, totalW, totalH);

    g.setColor(Color.WHITE);
    g.setFont(new Font("SansSerif", Font.BOLD, 22));
    g.drawString("Action Lifecycle Cost Comparison", pad, 40);
    g.setFont(new Font("SansSerif", Font.PLAIN, 13));
    g.setColor(SUBTLE);
    g.drawString(
        "Total measured off-tick cost per shipped action in microseconds (\u03bcs) [1 ms = 1,000 \u03bcs, 1 tick = 50 ms = 50,000 \u03bcs].",
        pad,
        64);

    double maxTotal = 0.01;
    for (ActionResult a : results) {
      maxTotal = Math.max(maxTotal, a.totalUs());
    }

    int baseY = headerH + chartH;
    g.setColor(BORDER);
    g.drawLine(pad, baseY, totalW - pad, baseY);

    int x = pad + barGap;
    for (ActionResult a : results) {
      int barH = (int) (a.totalUs() / maxTotal * (chartH - 20));
      Color accent = "circle".equalsIgnoreCase(a.shape) ? ACCENT2 : ACCENT;
      g.setColor(accent);
      g.fillRect(x, baseY - barH, barW, barH);
      g.setColor(TEXT);
      g.setFont(new Font("Monospaced", Font.PLAIN, 12));
      g.drawString(String.format("%.1f \u03bcs", a.totalUs()), x + 8, baseY - barH - 22);
      g.setColor(SUBTLE);
      g.setFont(new Font("Monospaced", Font.PLAIN, 11));
      g.drawString(String.format("(%.3f ms)", a.totalUs() / 1000.0), x + 8, baseY - barH - 8);
      g.setColor(TEXT);
      g.setFont(new Font("SansSerif", Font.BOLD, 12));
      g.drawString(a.id, x + 4, baseY + 20);
      g.setFont(new Font("SansSerif", Font.PLAIN, 11));
      g.setColor(SUBTLE);
      g.drawString("N=" + a.participants, x + 4, baseY + 35);
      x += barW + barGap;
    }

    g.dispose();
    writeAll(img, "action_comparison_chart.png");
  }

  private static Graphics2D newCanvas(BufferedImage img, int w, int h) {
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setColor(BG);
    g.fillRect(0, 0, w, h);
    return g;
  }

  /** Writes the image to the same artifact locations the project's other chart tests use. */
  private static void writeAll(BufferedImage img, String name) throws Exception {
    File[] outputs = {
      new File("build/reports/" + name), new File("docs/assets/img/" + name),
    };
    for (File out : outputs) {
      if (out.getParentFile() != null) {
        out.getParentFile().mkdirs();
      }
      ImageIO.write(img, "PNG", out);
    }
    System.out.printf("[DEBUG_LOG] Rendered action chart via Java 2D: %s%n", name);
  }
}
