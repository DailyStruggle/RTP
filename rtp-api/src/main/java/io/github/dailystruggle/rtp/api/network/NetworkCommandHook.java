package io.github.dailystruggle.rtp.api.network;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pre-dispatch hook consulted by the {@code /rtp} command pipeline before
 * the local teleport selection runs. Lets the network-mode adapter (Bukkit's
 * {@code NetworkModeBootstrap}, Fabric's pending equivalent, etc.) intercept
 * a request and route it across the cross-server wait queue without leaking
 * proxy SPI types into {@code rtp-core}.
 *
 * <p>Default install ({@link #LOCAL_ONLY}) returns {@link RoutingResult.Local}
 * unconditionally, so plain single-server deployments incur zero behavioural
 * change. Network-mode adapters install a richer impl that consults
 * the live network snapshot, the per-backend region inventory, and the
 * enrolment buffer.</p>
 *
 * <p>S-004 contract: implementations <em>must not</em> swallow teleport
 * intent silently. On failure they must either return {@link RoutingResult.Local}
 * (graceful fallback to the local pipeline) or {@link RoutingResult.Reject}
 * with a localized reason key. Throwing is permitted; the caller catches
 * and degrades to local with a WARNING log entry.</p>
 *
 * <p>Threading: invoked from the {@code /rtp} command thread (server main
 * on Bukkit/Paper, region-owned on Folia, dispatcher on Fabric). Must be
 * non-blocking and side-effect-free on the {@link RoutingResult.Local} path;
 * {@link RoutingResult.CrossServer} implementations may offer a record to
 * the async enrolment buffer as their only side effect.</p>
 *
 * @since L6 (rtp-proxy-ADR-014)
 */
@FunctionalInterface
public interface NetworkCommandHook {

  /**
   * Decide whether this {@code /rtp} call should be served locally,
   * enrolled on the cross-server wait queue, or rejected outright.
   *
   * @param playerId the invoking player; never {@code null}
   * @param args     the parsed argument map from commands-api (e.g.
   *                 {@code {"region": ["east"], "biome": ["plains"]}}).
   *                 Never {@code null}; may be empty
   * @return a {@link RoutingResult} describing the chosen path; never
   *         {@code null}
   */
  RoutingResult route(UUID playerId, Map<String, List<String>> args);

  /**
   * Default install used when no network adapter is active. Always returns
   * {@link RoutingResult.Local}, preserving the pre-L6 single-server
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
     * @param messageKey   localized message key (typically a {@code MessagesKeys}
     *                     enum {@code name()}) the caller should emit; never
     *                     {@code null}
     * @param placeholder  optional placeholder value (e.g. the region name)
     *                     substituted into the message via {@code [region]}
     *                     or similar; {@code null}/empty for none
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
