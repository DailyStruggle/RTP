package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandParameter;

/**
 * Platform-supplied source for the two {@code /rtp} command parameters that
 * are genuinely platform-bound: {@code player} and {@code world}.
 *
 * <p>Every other top-level parameter ({@code region}, {@code biome},
 * {@code toggletargetperms}) is platform-agnostic and is constructed once in
 * {@link CoreCommandTreeBuilder}. The {@code player} and {@code world}
 * parameters cannot be: their validation predicate <em>and</em> their
 * tab-complete {@code values()} reach for platform-specific online-player /
 * world enumeration APIs (e.g. {@code Bukkit.getPlayer} /
 * {@code Bukkit.getWorld} on the Bukkit family, the platform server-accessor's
 * online-player snapshot on Fabric / NeoForge). Each platform root supplies
 * its own implementations through this seam so the common builder can wire a
 * byte-for-byte equivalent command tree without leaking platform imports into
 * {@code rtp-core}.</p>
 */
public interface PlatformCommandParameters {

  /**
   * Supplies the {@code player} (target-player) parameter for the {@code /rtp}
   * root. Implementations gate on {@code rtp.other}, verify the named target
   * exists, and honour the {@code rtp.notme} self-opt-out; {@code values()}
   * surfaces the live online-player snapshot for tab-completion.
   *
   * @return the platform-specific {@code player} command parameter
   */
  CommandParameter playerParameter();

  /**
   * Supplies the top-level {@code world} parameter for the {@code /rtp} root.
   * Implementations verify the named world exists and gate on
   * {@code rtp.worlds.<world>}.
   *
   * @return the platform-specific {@code world} command parameter
   */
  CommandParameter worldParameter();
}
