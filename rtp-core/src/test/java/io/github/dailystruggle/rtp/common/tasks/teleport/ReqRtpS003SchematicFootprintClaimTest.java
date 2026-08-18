package io.github.dailystruggle.rtp.common.tasks.teleport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.schematic.LoadedSchematic;
import io.github.dailystruggle.rtp.api.schematic.PasteOptions;
import io.github.dailystruggle.rtp.api.schematic.SchematicSource;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-RTP-S-003 - region-schematic paste must respect claims (ADR-058). The teleport pipeline
 * runs the schematic's horizontal footprint through {@link GlobalRegionVerifiers} (the sanctioned
 * claim/protection registry the bundled claim integrations register into) before writing any
 * block, and suppresses the paste when any footprint cell is inside a claim. These tests exercise
 * {@link TeleportPipelineTask#schematicFootprintClear} directly.
 */
class ReqRtpS003SchematicFootprintClaimTest {

  private static final String WORLD = "world";

  /** Minimal decoded-schematic stand-in: only the bounding box matters to the footprint guard. */
  private record FixedSizeSchematic(int width, int height, int length) implements LoadedSchematic {
    @Override
    public SchematicSource source() {
      return null;
    }
  }

  @BeforeEach
  void setUp() {
    GlobalRegionVerifiers.clearGlobalRegionVerifiers();
  }

  @AfterEach
  void tearDown() {
    GlobalRegionVerifiers.clearGlobalRegionVerifiers();
  }

  @Test
  @DisplayName("no registered verifiers -> footprint is clear (paste proceeds)")
  void noVerifiersMeansClear() {
    LoadedSchematic schem = new FixedSizeSchematic(5, 1, 5);
    RTPLocation at = new RTPLocation(null, 100, 64, 200);
    assertTrue(TeleportPipelineTask.schematicFootprintClear(
        schem, at, PasteOptions.defaults(), WORLD));
  }

  @Test
  @DisplayName("footprint fully outside every claim -> clear (paste proceeds)")
  void footprintOutsideClaimIsClear() {
    // Reject only the column at x=0,z=0 (far from the footprint around 100,200).
    GlobalRegionVerifiers.addGlobalRegionVerifier(c -> !(c.x() == 0 && c.z() == 0));
    LoadedSchematic schem = new FixedSizeSchematic(5, 1, 5);
    RTPLocation at = new RTPLocation(null, 100, 64, 200);
    assertTrue(TeleportPipelineTask.schematicFootprintClear(
        schem, at, PasteOptions.defaults(), WORLD));
  }

  @Test
  @DisplayName("footprint cell inside a claim -> not clear (paste suppressed, S-003)")
  void footprintInsideClaimIsRejected() {
    // BOTTOM_CENTER anchor: a 5x5 footprint around (100,200) spans x/z 98..102.
    // Reject exactly the arrival center cell -> the footprint intersects the claim.
    GlobalRegionVerifiers.addGlobalRegionVerifier(c -> !(c.x() == 100 && c.z() == 200));
    LoadedSchematic schem = new FixedSizeSchematic(5, 1, 5);
    RTPLocation at = new RTPLocation(null, 100, 64, 200);
    assertFalse(TeleportPipelineTask.schematicFootprintClear(
        schem, at, PasteOptions.defaults(), WORLD));
  }

  @Test
  @DisplayName("claim touching only the footprint edge is still detected")
  void claimAtFootprintEdgeIsRejected() {
    // The +x/+z edge of the 5x5 BOTTOM_CENTER footprint is x=102, z=202.
    GlobalRegionVerifiers.addGlobalRegionVerifier(c -> !(c.x() == 102 && c.z() == 202));
    LoadedSchematic schem = new FixedSizeSchematic(5, 1, 5);
    RTPLocation at = new RTPLocation(null, 100, 64, 200);
    assertFalse(TeleportPipelineTask.schematicFootprintClear(
        schem, at, PasteOptions.defaults(), WORLD));
  }

  @Test
  @DisplayName("an async (I/O-style) claim verifier is honoured")
  void asyncVerifierIsHonoured() {
    // An async verifier rejecting the footprint center must suppress the paste just like a
    // synchronous one (claim plugins may answer off-thread).
    GlobalRegionVerifiers.addGlobalRegionVerifierAsync(c ->
        java.util.concurrent.CompletableFuture.completedFuture(!(c.x() == 100 && c.z() == 200)));
    LoadedSchematic schem = new FixedSizeSchematic(5, 1, 5);
    RTPLocation at = new RTPLocation(null, 100, 64, 200);
    assertFalse(TeleportPipelineTask.schematicFootprintClear(
        schem, at, PasteOptions.defaults(), WORLD));
  }
}
