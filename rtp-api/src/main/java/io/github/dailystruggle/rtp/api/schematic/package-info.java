/**
 * Platform-neutral SPI for region-specific schematic ({@code .schem} / structure NBT)
 * pasting at a confirmed arrival {@link io.github.dailystruggle.rtp.api.world.RTPLocation}.
 *
 * <p>See ADR-058 (Region-Specific Schematic Paste). The contract enforces a strict
 * load-async then paste-on-region-thread split: {@link io.github.dailystruggle.rtp.api.schematic.SchematicPaster#load}
 * performs blocking file I/O off any tick/region thread (S-005), while
 * {@link io.github.dailystruggle.rtp.api.schematic.SchematicPaster#paste} performs only the
 * block writes and MUST be invoked by the caller on the thread owning the target region.
 *
 * <p>Each backend adapter exposes a swappable static holder for the active paster,
 * mirroring the existing biome-getter idiom (e.g. {@code BukkitRTPWorld.setBiomeGetter}),
 * so an addon can override the platform paster without forking the adapter. The default
 * holder is {@link io.github.dailystruggle.rtp.api.schematic.NoOpSchematicPaster}, never
 * {@code null}, satisfying S-006.
 */
package io.github.dailystruggle.rtp.api.schematic;
