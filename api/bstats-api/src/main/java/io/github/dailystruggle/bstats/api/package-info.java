/**
 * Dependency-free bStats v2 client (bstats-api-ADR-001).
 *
 * <p>Entry point: {@link io.github.dailystruggle.bstats.api.BStatsService#builder}.
 * Hosts supply a {@link io.github.dailystruggle.bstats.api.BStatsScheduler}, an
 * optional {@link io.github.dailystruggle.bstats.api.BStatsLog}, and per-submission
 * {@link io.github.dailystruggle.bstats.api.ServerInfo}; the chosen
 * {@link io.github.dailystruggle.bstats.api.BStatsPlatform} maps it onto the
 * endpoint's wire fields. Chart types mirror {@code org.bstats.charts}.
 *
 * <p>No platform, plugin or third-party imports: any plugin can shade or
 * {@code compileOnly} this package.
 */
package io.github.dailystruggle.bstats.api;
