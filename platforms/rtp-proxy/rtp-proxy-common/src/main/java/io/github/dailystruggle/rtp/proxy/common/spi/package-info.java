/**
 * Host-independent proxy SPI surfaces consumed by {@code rtp-proxy-velocity},
 * {@code rtp-proxy-bungee}, and the reference implementations in sibling
 * packages.
 *
 * <p>Per rtp-proxy-ADR-001 §Package Boundary Rules, this package may import
 * only {@code rtp-api} and the JDK. Vendor classes
 * ({@code com.velocitypowered.*}, {@code net.md_5.bungee.*},
 * {@code org.bukkit.*}, {@code net.minecraft.*}, {@code net.fabricmc.*}) are
 * forbidden and the boundary is enforced by
 * {@code NoVendorImportsTest}.</p>
 *
 * <p>All asynchronous SPI methods complete on a transport-owned executor;
 * consumers are responsible for hopping back to a host scheduler before
 * touching world or player state (S-005, Folia region threading).</p>
 */
package io.github.dailystruggle.rtp.proxy.common.spi;
