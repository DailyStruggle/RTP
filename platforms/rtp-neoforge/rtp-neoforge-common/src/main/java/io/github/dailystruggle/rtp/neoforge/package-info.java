/**
 * NeoForge platform adapter for RTP (Phase N1 skeleton).
 *
 * <p>In-scope per {@code ADR-033} (NeoForge platform in-scope) and the
 * subproject ADR {@code rtp-neoforge-ADR-001-platform-in-scope}. This package
 * is a sibling of {@code io.github.dailystruggle.rtp.fabric} and mirrors its
 * shape, but NeoForge is a genuinely distinct adapter (annotation-driven event
 * bus, {@code RegisterCommandsEvent} command trampoline, {@code neoforge.mods.toml}
 * metadata, ModDevGradle toolchain) — see {@code docs/dev/NEOFORGE_NOTES.md} §2.</p>
 *
 * <h2>Architectural invariants</h2>
 * <ul>
 *   <li>No {@code org.bukkit.*} and no {@code net.fabricmc.*} imports here.</li>
 *   <li>Shared business logic stays in {@code rtp-core} / {@code rtp-api}; this
 *       package is NeoForge platform glue only.</li>
 *   <li>NeoForge is single-main-thread (no Folia-style regions); all S-005
 *       reasoning carries over from Fabric unchanged. Async chunk generation
 *       must route through the platform async path — never a synchronous
 *       tick-thread chunk load.</li>
 *   <li>Public {@code rtp-api} entry points throw {@link java.lang.IllegalStateException}
 *       (not null / no-op) when called before core load (S-006).</li>
 * </ul>
 *
 * <h2>Phase N1 status</h2>
 * <p>This is the bring-up skeleton: the {@code @Mod} entry point
 * ({@link io.github.dailystruggle.rtp.neoforge.RTPNeoForgeMod}) wires the
 * lifecycle and a server-thread {@link io.github.dailystruggle.rtp.neoforge.scheduling.NeoForgeScheduler}.
 * The {@code RTPServerAccessor} implementation, command Brigadier bridge wiring,
 * event bridge, database handler, anvil/ticket parity, and the S-005/S-006
 * REQ-traceable guards are filled in by the platform maintainer in subsequent
 * Phase N1/N2 sessions (see {@code platforms/rtp-neoforge/REQUIREMENTS.md} and
 * {@code docs/dev/scratch/PROPOSAL-neoforge-bringup.md}).</p>
 */
package io.github.dailystruggle.rtp.neoforge;
