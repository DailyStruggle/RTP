/**
 * Root package for the Fabric platform adapter.
 *
 * <p><strong>Phase 1 skeleton — no implementations yet.</strong> Real platform
 * glue (server accessor, world/chunk/player wrappers, scheduler, database
 * handler, event bridge, permission resolver, Brigadier registrar) lands in
 * Phase 2 Steps A through H per
 * {@code docs/dev/MULTI_PLATFORM_PLAN.md}.</p>
 *
 * <p><strong>Architectural invariants</strong> (per
 * {@code docs/adr/ADR-022-fabric-platform-in-scope.md} §4): code in this
 * package and its sub-packages shall import Fabric APIs only. {@code
 * org.bukkit.*} imports are forbidden here. Shared, platform-free logic
 * lives in {@code rtp-core} / {@code rtp-api} / {@code commands-api} /
 * {@code effects-api} and is consumed via project dependencies.</p>
 *
 * <p>The {@code ModInitializer} entry-point class for this adapter does
 * <em>not</em> live in this module — it lives in {@code rtp-plugin} as
 * {@code io.github.dailystruggle.rtp.fabric.RTPFabricMod}, alongside the
 * Bukkit entry-point class, per the single-JAR multi-loader packaging
 * decision in ADR-022 §2.</p>
 */
package io.github.dailystruggle.rtp.fabric;
