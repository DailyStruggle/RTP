package io.github.dailystruggle.rtp.api.platform;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import io.github.dailystruggle.rtp.api.world.RTPLocation;

import java.util.concurrent.CompletableFuture;

/**
 * Platform-neutral SPI marker for anything that <i>creates the arrival platform</i> at a
 * confirmed teleport destination.
 *
 * <p>An RTP "platform" is the safe footprint a teleporting player lands on. The built-in
 * behaviour is the emergency block disc written by {@code RTPWorld#platform(RTPLocation)};
 * a platform creator is the general extension point for replacing or enriching that
 * footprint - a pasted schematic, a procedurally generated pad, a lobby structure, etc.
 *
 * <p>This base interface deliberately carries no platform or format types so it can live
 * in {@code rtp-api} and be implemented by addons that depend on {@code rtp-api} alone.
 * Concrete contracts add their own typed methods on top of it; the bundled specialisation
 * is {@link io.github.dailystruggle.rtp.api.schematic.SchematicPaster} (load-async /
 * paste-on-region-thread, file-backed). Addons register a creator through the
 * {@code RTPHooks#platformCreator()} registry (ADR-026, ADR-058).
 *
 * <p><b>Contract.</b> Implementations never throw to abort a teleport; every non-success
 * condition is reported through the concrete contract's result type so the core invocation
 * point can audit it (S-004) and proceed with the teleport unmodified. Any blocking work
 * (file or network I/O) runs off the region/tick thread (S-005); only block writes run on
 * the region-owning thread.
 */
@PublicApi
public interface PlatformCreator {

  /**
   * A short, stable identifier for this creator, used in S-004 audit log lines so an
   * operator can see which platform creator handled (or declined) a teleport.
   *
   * @return a non-null identifier; defaults to the implementation's simple class name
   */
  default String creatorName() {
    return getClass().getSimpleName();
  }

  /**
   * Phase 1 of the two-phase platform-creation contract: do any heavy or blocking work
   * (file read + decode, network fetch, structure generation) <i>off</i> the region/tick
   * thread, returning a handle for {@link #createPlatform(RTPLocation, Object)} to consume.
   *
   * <p>This split is not schematic-specific: <b>every</b> non-trivial creator needs it, because
   * RTP must not perform blocking I/O on the region/tick thread (S-005). RTP invokes
   * {@code prepare} during the pipeline's load phase, <b>on a non-region thread</b>, and never
   * blocks the pipeline on the returned future (it is joined opportunistically before the paste).
   *
   * <p>Because RTP already calls this off the region/tick thread, implementations should do their
   * blocking work <i>inline</i> and return {@link CompletableFuture#completedFuture(Object)}; they
   * MUST NOT spin up their own {@code Executor} / thread pool / {@code CompletableFuture.supplyAsync}
   * on a backend JVM. Any genuinely deferred async work must be scheduled through RTP's scheduler,
   * never a raw thread pool.
   *
   * <p>Implementations MUST NOT throw to abort the teleport and MUST NOT touch the world or load
   * chunks here (S-005); report any failure by completing the future with {@code null} (RTP then
   * writes its default emergency platform). Creators with nothing to pre-load may leave this at
   * the default, which returns a future completed with {@code null}.
   *
   * @param at the confirmed, safe arrival location; never {@code null}
   * @return a future completing with an opaque handle for {@link #createPlatform(RTPLocation,
   *     Object)}, or with {@code null} when there is nothing to prepare / preparation failed;
   *     never {@code null}
   */
  default CompletableFuture<?> prepare(RTPLocation at) {
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Phase 2 of the two-phase platform-creation contract: write the arrival platform at
   * {@code at}, replacing RTP's built-in emergency block disc, consuming the handle produced
   * by {@link #prepare(RTPLocation)}.
   *
   * <p>RTP calls this once, on the thread that owns the destination region (Folia region
   * thread; Bukkit/Paper main thread), immediately before the player is moved. Implementations
   * may write blocks here but MUST NOT perform blocking file or network I/O (S-005); do that in
   * {@link #prepare(RTPLocation)} and consume the result through {@code prepared}.
   *
   * <p>Implementations MUST NOT throw to abort the teleport: return {@code false} to decline
   * (RTP then writes its default emergency platform), and report any failure cause through
   * logging so the fallback is auditable (S-004).
   *
   * @param at       the confirmed, safe arrival location; never {@code null}
   * @param prepared the handle returned by {@link #prepare(RTPLocation)}, possibly {@code null}
   * @return {@code true} if this creator built the platform (suppressing the default emergency
   *     disc); {@code false} to fall back to the default platform
   */
  default boolean createPlatform(RTPLocation at, Object prepared) {
    return createPlatform(at);
  }

  /**
   * Convenience single-phase entry point for creators with nothing to pre-load: write the
   * arrival platform at {@code at} on the region-owning thread. Equivalent to
   * {@link #createPlatform(RTPLocation, Object) createPlatform(at, null)}; the two-phase
   * {@link #prepare(RTPLocation)} / {@link #createPlatform(RTPLocation, Object)} pair is the
   * path RTP actually drives.
   *
   * @param at the confirmed, safe arrival location; never {@code null}
   * @return {@code true} if this creator built the platform; {@code false} to fall back
   */
  default boolean createPlatform(RTPLocation at) {
    return false;
  }
}
