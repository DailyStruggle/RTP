package io.github.dailystruggle.rtp.api.network;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pre-dispatch hook consulted by {@code /rtp} to route requests across servers.
 *
 * <p>S-004: implementations shall not swallow teleport intent silently. On failure,
 * fallback to {@link RoutingResult.Local} or return {@link RoutingResult.Reject}.
 *
 * <p>Threading: invoked on command dispatch thread; must be non-blocking.
 */
@FunctionalInterface
public interface NetworkCommandHook {

  /**
   * Evaluates whether {@code /rtp} should route locally, cross-server, or reject.
   *
   * @param playerId invoking player; never {@code null}
   * @param args     parsed argument map; never {@code null}
   * @return routing decision; never {@code null}
   */
  RoutingResult route(UUID playerId, Map<String, List<String>> args);

  /**
   * Default install used when no network adapter is active. Always returns
   * {@link RoutingResult.Local}, preserving the single-server
   * pipeline.
   */
  NetworkCommandHook LOCAL_ONLY = (playerId, args) -> RoutingResult.LOCAL;

  /**
   * Sealed outcome of {@link NetworkCommandHook#route(UUID, Map)}.
   * Implementations should prefer the static factory helpers
   * ({@link RoutingResult#local()}, {@link RoutingResult#crossServer(UUID, String, String)},
   * {@link RoutingResult#reject(String, String)}) over the constructors.
   */
  sealed interface RoutingResult permits RoutingResult.Local,
                                          RoutingResult.CrossServer,
                                          RoutingResult.Reject {

    /** Singleton {@link Local} instance. */
    Local LOCAL = new Local();

    /** Static factory for the local pass-through outcome. */
    static Local local() { return LOCAL; }

    /**
     * Static factory for the cross-server enrolment outcome.
     *
     * @param correlationId unique enrolment id; never {@code null}
     * @param regionKey     requested region, or {@code null}/empty for none
     * @param serverHint    optional pinned destination server id;
     *                      {@code null}/empty for "selector picks"
     */
    static CrossServer crossServer(UUID correlationId, String regionKey, String serverHint) {
      return new CrossServer(
              Objects.requireNonNull(correlationId, "correlationId"),
              (regionKey == null || regionKey.isEmpty()) ? Optional.empty() : Optional.of(regionKey),
              (serverHint == null || serverHint.isEmpty()) ? Optional.empty() : Optional.of(serverHint));
    }

    /**
     * Static factory for the reject outcome.
     *
     * @param messageKey  localized message key; never {@code null}
     * @param placeholder optional placeholder value (e.g. region name); empty if none
     */
    static Reject reject(String messageKey, String placeholder) {
      return new Reject(
              Objects.requireNonNull(messageKey, "messageKey"),
              placeholder == null ? "" : placeholder);
    }

    /** Pass the request through to the local {@code /rtp} pipeline unchanged. */
    final class Local implements RoutingResult {
      private Local() {}
    }

    /**
     * Enrol the request on the cross-server wait queue. The hook
     * implementation is responsible for the actual enrolment side effect
     * before returning; the caller's only job is to emit the
     * {@code networkQueued} message and short-circuit.
     */
    record CrossServer(UUID correlationId, Optional<String> regionKey, Optional<String> serverHint)
            implements RoutingResult {
      public CrossServer {
        Objects.requireNonNull(correlationId, "correlationId");
        if (regionKey == null) regionKey = Optional.empty();
        if (serverHint == null) serverHint = Optional.empty();
      }
    }

    /**
     * Reject the request with a localized message and do not teleport. Used
     * when the player explicitly asked for a region no live backend
     * advertises, or for an ambiguous region under a strict collision
     * policy. Non-explicit failures (queue full, rate limit) should prefer
     * {@link Local} per the "simple for users" UX rule.
     */
    record Reject(String messageKey, String placeholder) implements RoutingResult {
      public Reject {
        Objects.requireNonNull(messageKey, "messageKey");
        if (placeholder == null) placeholder = "";
      }
    }
  }
}
