package io.github.dailystruggle.rtp.common.hooks;

import io.github.dailystruggle.rtp.anvil.AnvilPrefilter;
import io.github.dailystruggle.rtp.anvil.ChunkCoord;
import io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Core {@link AnvilPrefilterRegistry.Provider} backed by on-disk {@code .mca} reads (ADR-016):
 * backlog bins are classified through {@link AnvilPrefilter#probeBatchSyncDetailed}, one
 * region-file open per bin.
 *
 * <p>Opt-in via {@code -D}{@value #ENABLE_PROPERTY}{@code =true}; never replaces an addon-bound
 * provider. Worlds without {@link RTPWorld#anvilWorldFolder()} and a disabled
 * {@code SafetyKeys.anvilPrefilterEnabled} answer UNKNOWN (live load). Blocking I/O: called only
 * from {@code AnvilIoPool} or the async pulse, never the tick thread (S-005). Stateless.</p>
 */
public final class AnvilBatchPrefilterProvider implements AnvilPrefilterRegistry.Provider {

  /** JVM flag that binds this provider at core init. */
  public static final String ENABLE_PROPERTY = "rtp.backlog.anvilProvider";

  /** Binds a new instance when {@value #ENABLE_PROPERTY} is set and no provider is bound. */
  public static void bindIfEnabled(AnvilPrefilterRegistry registry) {
    if (registry == null || !Boolean.getBoolean(ENABLE_PROPERTY)) return;
    if (registry.current() != null) return;
    registry.bind(new AnvilBatchPrefilterProvider());
    RTP.log(java.util.logging.Level.INFO,
        "[RTP] Anvil backlog batch prefilter bound (" + ENABLE_PROPERTY + "=true)");
  }

  @Override
  public Decision classify(RTPWorld world, int cx, int cz) {
    Decision d = classifyBatch(world, List.of(new ChunkCoord(cx, cz))).get(new ChunkCoord(cx, cz));
    return (d == null) ? Decision.UNKNOWN : d;
  }

  @Override
  public Map<ChunkCoord, Decision> classifyBatch(RTPWorld world, List<ChunkCoord> chunks) {
    Map<ChunkCoord, Decision> out = new LinkedHashMap<>();
    if (world == null || chunks == null || chunks.isEmpty() || !enabled()) return out;
    Path folder = world.anvilWorldFolder();
    if (folder == null) return out;
    String dim = world.anvilDimensionSubpath();
    Map<ChunkCoord, AnvilPrefilter.ProbeResult> probes =
        AnvilPrefilter.probeBatchSyncDetailed(folder, dim, chunks, unsafeBlocks(), reconciler());
    for (Map.Entry<ChunkCoord, AnvilPrefilter.ProbeResult> e : probes.entrySet()) {
      out.put(e.getKey(), switch (e.getValue().verdict()) {
        case ACCEPT -> Decision.ACCEPT;
        case REJECT -> Decision.REJECT;
        default -> Decision.UNKNOWN;
      });
    }
    return out;
  }

  private static UnaryOperator<String> reconciler() {
    return s -> (RTP.serverAccessor != null)
        ? RTP.serverAccessor.reconcilePaletteIdentifier(s)
        : io.github.dailystruggle.rtp.anvil.PaletteIdentifierNormalizer.normalize(s);
  }

  @SuppressWarnings("unchecked")
  private static boolean enabled() {
    try {
      if (RTP.configs == null) return true;
      ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
      if (safety == null) return true;
      Object raw = safety.getConfigValue(SafetyKeys.anvilPrefilterEnabled, Boolean.TRUE);
      if (raw instanceof Boolean b) return b;
      return raw == null || Boolean.parseBoolean(raw.toString());
    } catch (Throwable t) {
      return true;
    }
  }

  /** {@code BlocksKeys.unsafeBlocks} snapshot; empty (never reject) on lookup failure. */
  private static Set<String> unsafeBlocks() {
    try {
      if (RTP.configs == null) return Collections.emptySet();
      Object raw = RTP.configs.getConfigValue(BlocksKeys.unsafeBlocks, new java.util.ArrayList<>());
      if (raw instanceof Collection<?> c) {
        Set<String> out = new HashSet<>(c.size());
        for (Object o : c) if (o != null) out.add(o.toString());
        return out;
      }
    } catch (Throwable t) {
      // Fall through to empty.
    }
    return Collections.emptySet();
  }
}
