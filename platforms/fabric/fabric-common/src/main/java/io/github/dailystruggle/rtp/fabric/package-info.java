/**
 * Root package for the Fabric platform adapter.
 *
 * <p><strong>Architectural invariants</strong> (per
 * {@code rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md} section 4): code in this
 * package and its sub-packages shall import Fabric APIs only. {@code
 * org.bukkit.*} imports are forbidden here. Shared, platform-free logic
 * lives in {@code rtp-core} / {@code rtp-api} / {@code commands-api} /
 * {@code effects-api} and is consumed via project dependencies.</p>
 *
 * <p>The {@code ModInitializer} entry-point class for this adapter does
 * <em>not</em> live in this module - it lives in {@code rtp-plugin} as
 * {@code io.github.dailystruggle.rtp.fabric.RTPFabricMod}, alongside the
 * Bukkit entry-point class, per the single-JAR multi-loader packaging
 * decision in ADR-022 section 2.</p>
 */
package io.github.dailystruggle.rtp.fabric;
