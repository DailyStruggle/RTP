package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.fixed;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Vertical adjustor that places the player at a single configured Y level in
 * mid-air, with no terrain scan.
 *
 * <p>Intended for skyblock-style worlds where the platform tool (see the
 * Bukkit / Folia world adapters' {@code platform} method) builds a foothold
 * around the player after teleport. Sibling of
 * {@link io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor}
 * and
 * {@link io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor}:
 * those scan for solid ground in pregenerated terrain; this adjustor returns
 * a fixed Y and lets the platform tool create the foothold instead.</p>
 *
 * <p>Acceptance rule: the destination cell {@code (x, y, z)} and the head
 * cell {@code (x, y+1, z)} must both be air. Any non-air block in either
 * cell is treated as unsafe (the player would suffocate or be displaced),
 * and the adjustor reports no acceptable Y for that chunk so the
 * {@link io.github.dailystruggle.rtp.common.tasks.ScanTask} pipeline can
 * roll a different one. No ground-column or sky-light gating is performed —
 * mid-air placement has neither.</p>
 *
 * <p>S-005 compliance: the live {@link #adjust(RTPChunk, MutableRTPCoords)}
 * path uses only the supplied {@link RTPChunk}'s already-loaded data; the
 * probe path uses only the supplied {@link ChunkColumnProbe}. Neither path
 * triggers a synchronous chunk load.</p>
 */
public class FixedAdjustor extends VerticalAdjustor<FixedAdjustorKeys> {
  /** Command parameters for tab-completion of {@code /rtp vert:fixed}. */
  protected static final Map<String, CommandParameter> subParameters = new ConcurrentHashMap<>();

  private static final EnumMap<FixedAdjustorKeys, Object> defaults =
      new EnumMap<>(FixedAdjustorKeys.class);

  /**
   * Single chunk-local column used for placement. Mid-air placement does not
   * benefit from sweeping multiple {@code (x, z)} offsets the way
   * {@code LinearAdjustor} / {@code JumpAdjustor} do (no terrain to dodge),
   * so a single deterministic column keeps the destination predictable.
   * The {@code (7, 7)} offset matches the first column those adjustors
   * sweep, keeping the chunk-center bias consistent across engines.
   */
  private static final List<List<Integer>> testCoords =
      Collections.singletonList(Arrays.asList(7, 7));

  static {
    defaults.put(FixedAdjustorKeys.y, 64);

    // Curated tab-completion suggestions for /rtp vert:fixed <TAB>.
    subParameters.put(
        "y",
        new IntegerParameter(
            "rtp.params",
            "fixed Y-level for mid-air placement",
            (sender, s) -> true,
            -64, 0, 64, 128, 256));
  }

  /**
   * Constructs a FixedAdjustor with the given placement verifiers.
   *
   * @param verifiers list of placement verifiers applied after Y selection
   */
  public FixedAdjustor(List<Predicate<RTPCoords>> verifiers) {
    super(FixedAdjustorKeys.class, "fixed", verifiers, defaults);
  }

  @Override
  public List<String> keys() {
    return Arrays.stream(FixedAdjustorKeys.values()).map(Enum::name).collect(Collectors.toList());
  }

  @Override
  public boolean requiresSkyLight() {
    return false;
  }

  @Override
  public @Nullable RTPCoords adjust(@NotNull RTPChunk chunk) {
    MutableRTPCoords output = new MutableRTPCoords(chunk.getWorld().name(), 0, 0, 0);
    if (adjust(chunk, output)) return output.toImmutable();
    return null;
  }

  @Override
  public boolean adjust(@NotNull RTPChunk chunk, @NotNull MutableRTPCoords output) {
    int y = getNumber(FixedAdjustorKeys.y, 64L).intValue();

    // Clamp to the world's vertical bounds — a configured Y outside the
    // world (e.g. 320 in a 1.16-style world) would otherwise produce coords
    // the platform pipeline cannot honour.
    int worldMax = chunk.getWorld().getMaxHeight();
    if (y >= worldMax) return false;
    // Head cell is y+1, so we also need y+1 < worldMax.
    if (y + 1 >= worldMax) return false;

    int lx = testCoords.get(0).get(0);
    int lz = testCoords.get(0).get(1);

    // Mid-air rule: every non-air block at the destination is unsafe.
    // The configured Y intentionally lies above any pregenerated terrain
    // (skyblock-style worlds), so a non-air hit usually means the user
    // mis-configured Y into terrain — better to reject and let ScanTask
    // pick a different chunk than to silently teleport into a wall.
    if (!chunk.isAir(lx, y, lz)) return false;
    if (!chunk.isAir(lx, y + 1, lz)) return false;

    int globalX = (chunk.x() << 4) + lx;
    int globalZ = (chunk.z() << 4) + lz;
    output.setWorldName(chunk.getWorld().name());
    output.setXZ(globalX, globalZ);
    output.setY(y);
    return true;
  }

  @Override
  public @Nullable RTPCoords adjustFromProbe(
      @NotNull ChunkColumnProbe probe, @NotNull String worldName) {
    return adjustFromProbeWithReason(probe, worldName).picked();
  }

  @Override
  public @NotNull AdjustResult adjustFromProbeWithReason(
      @NotNull ChunkColumnProbe probe, @NotNull String worldName) {
    int y = getNumber(FixedAdjustorKeys.y, 64L).intValue();

    // Probe window must cover the chosen Y and its head cell. If it doesn't,
    // fall back to the live path rather than guess — same contract used by
    // LinearAdjustor / JumpAdjustor.
    if (probe.minY() > y || probe.maxY() < y + 1) return AdjustResult.WINDOW_REJECT;

    int lx = testCoords.get(0).get(0);
    int lz = testCoords.get(0).get(1);

    if (!probe.isAirAt(lx, lz, y)) return AdjustResult.SCAN_MISS_REJECT;
    if (!probe.isAirAt(lx, lz, y + 1)) return AdjustResult.SCAN_MISS_REJECT;

    int globalX = (probe.chunkX() << 4) + lx;
    int globalZ = (probe.chunkZ() << 4) + lz;
    MutableRTPCoords out = new MutableRTPCoords(worldName, globalX, y, globalZ);
    return AdjustResult.ok(out.toImmutable());
  }

  @Override
  public boolean testPlacement(@NotNull RTPCoords coords) {
    for (int i = 0; i < verifiers.size(); i++) {
      Predicate<RTPCoords> rtpLocationPredicate = verifiers.get(i);
      if (!rtpLocationPredicate.test(coords)) return false;
    }
    return true;
  }

  @Override
  public Map<String, CommandParameter> getParameters() {
    return subParameters;
  }

  @Override
  public int minY() {
    return getNumber(FixedAdjustorKeys.y, 64).intValue();
  }

  @Override
  public int maxY() {
    return getNumber(FixedAdjustorKeys.y, 64).intValue() + 1;
  }
}
