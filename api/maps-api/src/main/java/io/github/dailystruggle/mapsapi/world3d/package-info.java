/**
 * Platform-neutral 3D world block, {@code .hca v2} / {@code .dlsh} sub-chunk slice, and
 * client-side drawing kernel delivery SPI ({@code maps-api-ADR-003}, {@code anvil-api-ADR-003}).
 *
 * <p>Enables standalone 3D world map engines such as {@code LeafMap} to deliver both the
 * client-side 2D/3D voxel rendering code and packet-budgeted $16 \times 16 \times 16$ sub-chunk
 * frames (or local zero-parse memory-mapped archive descriptors) to consumers like the
 * {@code LeafConfig} World Map panel without cross-plugin compile-time coupling.
 */
package io.github.dailystruggle.mapsapi.world3d;
