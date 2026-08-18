package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandParameter;

/**
 * Platform-supplied source for platform-bound command parameters ({@code player} and {@code world}).
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
