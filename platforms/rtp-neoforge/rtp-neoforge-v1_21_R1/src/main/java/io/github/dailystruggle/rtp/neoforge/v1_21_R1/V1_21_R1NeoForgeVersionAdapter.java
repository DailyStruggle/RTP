package io.github.dailystruggle.rtp.neoforge.v1_21_R1;

/**
 * Per-MC carrier for the NeoForge 1.21.x line (Phase N1 stub).
 *
 * <p>Mirrors {@code V1_21_R1FabricVersionAdapter}: this is where NM-typed
 * surfaces that drift across MC revs (chunk-ticket flags, teleport transition,
 * level/chunk handles) are isolated, so {@code rtp-neoforge-common} and
 * {@code rtp-core} stay free of version-pinned Minecraft types
 * (NEOFORGE_NOTES.md §5/§9).</p>
 *
 * <p><b>Phase N1 TODO (@leaf_26):</b> implement the NeoForge version-adapter
 * contract once the common-module {@code NeoForgeVersionAdapter} SPI is authored
 * (mirroring {@code FabricVersionAdapter}): async chunk load/generate (S-005),
 * non-persistent chunk tickets (rtp-fabric-ADR-003 / -006 port), and the
 * {@code Entity#teleportTo} / {@code TeleportTransition} call for 1.21.x.</p>
 */
public final class V1_21_R1NeoForgeVersionAdapter {

  private V1_21_R1NeoForgeVersionAdapter() {
  }
}
