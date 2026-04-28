package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.FakeChunkColumnProbe;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.GenericVerticalAdjustorKeys;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link LinearAdjustor#adjustFromProbe}. The probe is a fake in-memory
 * {@link io.github.dailystruggle.rtp.api.world.ChunkColumnProbe}; no chunk I/O is performed.
 *
 * <p>Acceptance predicate mirrors the legacy {@code adjust(chunk)} path:
 * {@code !isAir(y-1) && isAir(y) && isAir(y+1) && none-in-unsafeBlocks(y-1,y,y+1)}.
 * These tests cover scan-mode dispatch, probe-window/minY-maxY gating, sky-light
 * fallthrough, and world-coordinate emission at the chunk center (lx=8, lz=8).
 */
public class LinearAdjustorProbeTest {

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir.toFile());
  }

  private LinearAdjustor adj(int direction, int minY, int maxY) {
    LinearAdjustor a = new LinearAdjustor(new ArrayList<>());
    a.set(GenericVerticalAdjustorKeys.direction, direction);
    a.set(GenericVerticalAdjustorKeys.minY, (long) minY);
    a.set(GenericVerticalAdjustorKeys.maxY, (long) maxY);
    a.set(GenericVerticalAdjustorKeys.requireSkyLight, false);
    return a;
  }

  /**
   * Bottom-up: first acceptable Y is 64 (solid floor at 63, air at 64/65). The
   * multi-column probe sweep visits {@code testCoords[0] = (7, 7)} first, so
   * the returned global x/z reports that column — matching the live
   * {@code adjust(RTPChunk,...)} path; uniform fake data means every column
   * accepts at the same Y, so the column choice is deterministic.
   */
  @Test
  void bottomUp_acceptsFirstAirPair() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(2, 3, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);

    RTPCoords r = adj(0, 60, 80).adjustFromProbe(probe, "world");

    assertNotNull(r);
    assertEquals(64, r.y());
    assertEquals(2 * 16 + 7, r.x());
    assertEquals(3 * 16 + 7, r.z());
    assertEquals("world", r.worldName());
  }

  /**
   * Top-down: scans from maxY down. The only valid landing triple is the
   * {solid=64, air=65, air=66} window; everything above 66 is solid through 81
   * (the ceiling above the scan window), so Y=65 is the first accepted candidate.
   */
  @Test
  void topDown_acceptsHighestAirPair() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 64);
    probe.setAirRange(65, 66);
    probe.setSolidRange(67, 128); // solid ceiling through the top of the window (and above)

    RTPCoords r = adj(1, 60, 80).adjustFromProbe(probe, "w");
    assertNotNull(r);
    assertEquals(65, r.y());
  }

  /** Middle-out: expands from midpoint; gap at midpoint is chosen first. */
  @Test
  void middleOut_prefersCenter() {
    int minY = 60;
    int maxY = 80;
    int middle = minY + (maxY - minY) / 2; // 70
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 128);
    // Open a valid landing triple centered on 70: solid 69, air 70/71.
    probe.setAir(70);
    probe.setAir(71);

    RTPCoords r = adj(2, minY, maxY).adjustFromProbe(probe, "w");
    assertNotNull(r);
    assertEquals(middle, r.y());
  }

  /** Edges-in: walks inward from the edge; the edge-adjacent gap wins. */
  @Test
  void edgesIn_prefersOuter() {
    int minY = 60;
    int maxY = 80;
    int middle = minY + (maxY - minY) / 2; // 70
    int maxDistance = (maxY - minY) / 2; // 10 → outer candidate = middle+10=80, then middle-10=60
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 128);
    int target = middle - maxDistance; // 60
    probe.setSolid(target - 1);
    probe.setAir(target);
    probe.setAir(target + 1);
    // Make middle+maxDistance invalid by keeping surrounding solid (no adjustment).

    RTPCoords r = adj(3, minY, maxY).adjustFromProbe(probe, "w");
    assertNotNull(r);
    // Edges-in probes middle+maxDistance first, then middle-maxDistance → Y=60.
    assertEquals(target, r.y());
  }

  /** Shuffled (default branch): hidden valid Y is found deterministically with seeded rng. */
  @Test
  void shuffled_findsHiddenY() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 128);
    probe.setAir(70);
    probe.setAir(71);
    probe.setSolid(69); // floor

    LinearAdjustor a = adj(4, 60, 80);
    a.setRng(new java.util.Random(42L));
    RTPCoords r = a.adjustFromProbe(probe, "w");
    assertNotNull(r);
    assertEquals(70, r.y());
  }

  /**
   * requireSkyLight=true with a lit chunk and sky-exposed head cell → probe answers
   * directly (no fall-back). Matches the legacy {@code adjust(chunk)} behavior where
   * {@code skyLight > 7} gates acceptance at {@code y+1}.
   */
  @Test
  void requireSkyLight_litAndSkyExposed_accepts() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    probe.setLightOn(true);
    probe.setDefaultSkyLight(15);

    LinearAdjustor a = adj(0, 60, 80);
    a.set(GenericVerticalAdjustorKeys.requireSkyLight, true);

    RTPCoords r = a.adjustFromProbe(probe, "w");
    assertNotNull(r);
    assertEquals(64, r.y());
  }

  /**
   * requireSkyLight=true but every cell reports sky-light ≤ 7 (e.g. under an overhang) →
   * no Y in the window passes the gate and the probe returns null (NO-MATCH, not a
   * fallback signal — there's nothing the authoritative path would find either on the
   * center column).
   */
  @Test
  void requireSkyLight_litButDarkColumn_rejectsAll() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    probe.setLightOn(true);
    probe.setDefaultSkyLight(5); // below the > 7 threshold at every Y

    LinearAdjustor a = adj(0, 60, 80);
    a.set(GenericVerticalAdjustorKeys.requireSkyLight, true);

    assertNull(a.adjustFromProbe(probe, "w"));
  }

  /**
   * requireSkyLight=true, {@code isLightOn=false}, AND the probe has no heightmap →
   * defer (return null) so the live vert method can finish lighting. This exercises
   * the "no trustworthy sky-light source" branch of the tiered gate.
   */
  @Test
  void requireSkyLight_lightingNotFinalized_returnsNull() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    probe.setLightOn(false);
    probe.setDefaultSkyLight(15); // would accept if trusted, but isLightOn=false overrides
    // Note: heightmapTopY defaults to OptionalInt.empty() — no proxy available.

    LinearAdjustor a = adj(0, 60, 80);
    a.set(GenericVerticalAdjustorKeys.requireSkyLight, true);

    assertNull(a.adjustFromProbe(probe, "w"));
  }

  /**
   * requireSkyLight=true, {@code isLightOn=false}, BUT the chunk has a
   * {@code MOTION_BLOCKING_NO_LEAVES} heightmap and every cell above the
   * reported top is air (verification passes). The adjustor should synthesize
   * sky-access from the heightmap proxy and accept any candidate whose
   * {@code y+1} is strictly above the top, instead of deferring to the live
   * vert method. Validates the "open column" branch of the tiered gate.
   */
  @Test
  void lightOff_verifiedOpenHeightmap_acceptsViaProxy() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    probe.setLightOn(false);
    probe.setDefaultSkyLight(0); // would normally reject under strict skyLightAt
    probe.setHeightmapTop(63); // top-of-solid; everything above must be air

    LinearAdjustor a = adj(0, 60, 80);
    a.set(GenericVerticalAdjustorKeys.requireSkyLight, true);

    RTPCoords r = a.adjustFromProbe(probe, "w");
    assertNotNull(r, "verified-open heightmap should permit accept despite isLightOn=false");
    assertEquals(64, r.y());
  }

  /**
   * requireSkyLight=true, {@code isLightOn=false}, heightmap present BUT a
   * non-air block sits above the reported top (overhang / cave roof / structure
   * ceiling / player edit / older-version chunk where noise no longer
   * correlates). Verification fails → defer to the live vert method (return
   * null) rather than incorrectly trusting the heightmap proxy. Validates the
   * "verify before trust" guard the user requested.
   */
  @Test
  void lightOff_overhangAboveHeightmap_returnsNull() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    probe.withBlock(90, "minecraft:stone"); // floating overhang above the reported top
    probe.setLightOn(false);
    probe.setDefaultSkyLight(15);
    probe.setHeightmapTop(63);

    LinearAdjustor a = adj(0, 60, 80);
    a.set(GenericVerticalAdjustorKeys.requireSkyLight, true);

    assertNull(a.adjustFromProbe(probe, "w"),
        "overhang above heightmap top must invalidate the proxy and force live fallback");
  }

  /**
   * When {@code requireSkyLight=false}, the probe's {@code isLightOn} is irrelevant —
   * the scan proceeds and accepts the first air-pair regardless of stale lighting data.
   */
  @Test
  void requireSkyLightFalse_ignoresIsLightOn() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    probe.setLightOn(false);
    probe.setDefaultSkyLight(0);

    RTPCoords r = adj(0, 60, 80).adjustFromProbe(probe, "w");
    assertNotNull(r);
    assertEquals(64, r.y());
  }

  /** Probe window narrower than adjustor range → null (fall back to full parse). */
  @Test
  void probeWindowTooNarrow_returnsNull() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 65, 70);
    probe.setSolidRange(65, 70);
    assertNull(adj(0, 60, 80).adjustFromProbe(probe, "w"));
  }

  /**
   * Unsafe blocks in the {y-1,y,y+1} triple reject the candidate. The project's
   * default {@code safety.yml} ships with {@code LAVA} (uppercase, unqualified) in
   * {@code unsafeBlocks}, so we use that exact string to drive rejection.
   */
  @Test
  void unsafeBlockRejects() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    // Floor at 63 is "LAVA" (present in default unsafeBlocks). Y=64 should be rejected.
    // Y=65 has a stone floor at 64 → acceptable.
    probe.withBlock(63, "LAVA");
    probe.withBlock(64, "minecraft:stone");

    RTPCoords r = adj(0, 60, 80).adjustFromProbe(probe, "w");
    assertNotNull(r);
    assertEquals(65, r.y());
  }

  /**
   * Regression: probe ids returned by the anvil column probe are
   * lowercase namespaced (e.g. {@code "minecraft:water"}), while the
   * yml-loaded {@code unsafeBlocks} set is uppercase / namespace-stripped
   * (e.g. {@code "WATER"}). Before the canonicalisation fix in
   * {@code LinearAdjustor.acceptY}, the raw lookup never matched and
   * water at the feet-Y was silently accepted, putting players in lakes.
   * This guard fixes feet to {@code minecraft:water} and asserts the
   * adjustor rejects Y=64 and walks up to a stone-floored Y instead.
   */
  @Test
  void lowercaseNamespacedFluidAtFeetRejects() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    // Vanilla anvil-probe form: lowercase namespaced. Y=64 sits on water.
    probe.withBlock(63, "minecraft:water");
    // Provide a stone shelf so the scan can find a higher candidate.
    probe.withBlock(64, "minecraft:stone");

    RTPCoords r = adj(0, 60, 80).adjustFromProbe(probe, "w");
    assertNotNull(r,
        "scan should find a higher accept after rejecting the water-floored y=64");
    assertEquals(65, r.y(),
        "expected the stone-floored y=65, not the water-floored y=64");
  }

  /** Same as above but with lava instead of water — covers the other common fluid. */
  @Test
  void lowercaseNamespacedLavaAtFeetRejects() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    probe.withBlock(63, "minecraft:lava");
    probe.withBlock(64, "minecraft:stone");

    RTPCoords r = adj(0, 60, 80).adjustFromProbe(probe, "w");
    assertNotNull(r);
    assertEquals(65, r.y());
  }

  /** No acceptable Y on the center column → null (NO-MATCH). */
  @Test
  void allSolidReturnsNull() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 128);
    assertNull(adj(0, 60, 80).adjustFromProbe(probe, "w"));
  }

  // ---------------------------------------------------------------------------
  // PR-20: adjustFromProbeWithReason rejection-reason attribution.
  // One test per ProbeRejectReason so ScanTask's sub-counters have a pinned
  // contract. Adding a new null-return branch to LinearAdjustor without a
  // matching reason here will fail one of these — which is the point.
  // ---------------------------------------------------------------------------

  /** Window too narrow → {@code WINDOW}. Mirrors {@link #probeWindowTooNarrow_returnsNull}. */
  @Test
  void adjustFromProbeWithReason_windowMismatch_returnsWindow() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 65, 70);
    probe.setSolidRange(65, 70);
    var r = adj(0, 60, 80).adjustFromProbeWithReason(probe, "w");
    assertNull(r.picked());
    assertEquals(
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors
            .VerticalAdjustor.ProbeRejectReason.WINDOW,
        r.reason());
  }

  /**
   * requireSkyLight=true, isLightOn=false, no heightmap → {@code LIGHT_GATE}.
   * Mirrors {@link #requireSkyLight_lightingNotFinalized_returnsNull}.
   */
  @Test
  void adjustFromProbeWithReason_unverifiedLight_returnsLightGate() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    probe.setLightOn(false);
    // No heightmap set → computeHeightmapSkyFloor returns MAX_VALUE → LIGHT_GATE.

    LinearAdjustor a = adj(0, 60, 80);
    a.set(GenericVerticalAdjustorKeys.requireSkyLight, true);

    var r = a.adjustFromProbeWithReason(probe, "w");
    assertNull(r.picked());
    assertEquals(
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors
            .VerticalAdjustor.ProbeRejectReason.LIGHT_GATE,
        r.reason());
  }

  /** No acceptable Y on center column → {@code SCAN_MISS}. Mirrors {@link #allSolidReturnsNull}. */
  @Test
  void adjustFromProbeWithReason_scanMiss_returnsScanMiss() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 128);
    var r = adj(0, 60, 80).adjustFromProbeWithReason(probe, "w");
    assertNull(r.picked());
    assertEquals(
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors
            .VerticalAdjustor.ProbeRejectReason.SCAN_MISS,
        r.reason());
  }

  /** Success path → {@code NONE} and non-null {@code picked()}. */
  @Test
  void adjustFromProbeWithReason_success_returnsNone() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(2, 3, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    var r = adj(0, 60, 80).adjustFromProbeWithReason(probe, "world");
    assertNotNull(r.picked());
    assertEquals(64, r.picked().y());
    assertEquals(
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors
            .VerticalAdjustor.ProbeRejectReason.NONE,
        r.reason());
  }
}
