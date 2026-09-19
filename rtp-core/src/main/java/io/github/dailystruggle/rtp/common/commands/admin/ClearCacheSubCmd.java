package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;

import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp clear cache} child verb. Reuses the full clear-caches behaviour of
 * {@link ClearCacheCmd} (wipe the L1/L2/L3 location caches for every region),
 * only renaming the verb to {@code cache} so it reads naturally under the
 * {@code /rtp clear} parent.
 *
 * <p>Permission: {@code rtp.admin} (inherited from {@link ClearCacheCmd}).
 */
public class ClearCacheSubCmd extends ClearCacheCmd {

  /**
   * Constructs the {@code cache} child.
   *
   * @param parent the {@code /rtp clear} parent command, or {@code null}
   */
  public ClearCacheSubCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "cache";
  }
}
