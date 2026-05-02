package io.github.dailystruggle.rtp.api.hooks;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Registry of third-party predicates that veto candidate teleport coordinates before
 * a player is sent there.
 *
 * <p>This is the canonical extension point for claim-plugin integrations
 * (GriefPrevention, GriefDefender, WorldGuard, Lands, HuskTowns, RedProtect, Towny,
 * Factions) and for any addon that wants to refuse locations based on its own rules
 * (REQ-RTP-S-003, REQ-API-F-003). It is the API-level facade in front of
 * {@code rtp-core}'s {@code GlobalRegionVerifiers}; the legacy static entry points on
 * {@code GlobalRegionVerifiers} continue to work for source compatibility (ADR-026).
 *
 * <p><b>Threading.</b> Sync verifiers run on the verification chain that already
 * services teleport candidates and shall complete quickly without blocking I/O. Async
 * verifiers shall return a {@link CompletableFuture} and may perform I/O off the main
 * thread, but shall not block a region/tick thread (REQ-RTP-S-005, see Folia threading
 * rules in {@code .junie/AGENTS.md}).
 *
 * <p><b>Failure mode.</b> A verifier that throws is logged and treated as if it
 * returned {@code false} (location rejected); RTP will not silently swallow the
 * failure (REQ-RTP-S-004).
 */
public interface RegionVerifierRegistry {

  /**
   * Register a synchronous verifier. Returns {@code true} for a valid (allowed)
   * location, {@code false} to veto.
   *
   * @param verifier non-null predicate over candidate coordinates
   */
  void register(Predicate<RTPCoords> verifier);

  /**
   * Register an asynchronous verifier whose result completes with the same
   * "true = allow, false = veto" semantics.
   *
   * @param verifier non-null function returning a non-null future
   */
  void registerAsync(Function<RTPCoords, CompletableFuture<Boolean>> verifier);

  /**
   * Remove every registered verifier (sync and async). Intended for test harnesses
   * and {@code Configs.onReload} flows that re-register a fresh set of integrations.
   */
  void clear();

  /**
   * @return the number of currently registered verifiers (sync + async).
   */
  int size();
}
