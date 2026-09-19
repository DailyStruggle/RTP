package io.github.dailystruggle.rtp.api.hooks;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Registry of predicates that veto candidate teleport coordinates (S-003, ADR-026).
 * Extension point for claim plugins and custom veto logic.
 *
 * <p><b>Threading:</b> Sync verifiers must not block. Async verifiers must return
 * a future and never block a tick thread (S-005). Throwing vetoes the location (S-004).
 */
@PublicApi
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
