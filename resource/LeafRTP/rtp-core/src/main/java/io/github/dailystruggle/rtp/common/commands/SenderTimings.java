package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.tools.ParsePermissions;
import java.util.concurrent.TimeUnit;

/** Resolves sender-specific cooldowns and warmup delays using Bukkit-compatible precedence. */
public final class SenderTimings {
  private SenderTimings() {}

  /** Returns the sender's effective cooldown in milliseconds. */
  public static long cooldownMillis(RTPCommandSender sender) {
    return resolveMillis(
        sender, "rtp.nocooldown", "rtp.noCooldown", "rtp.cooldown.", ConfigKeys.teleportCooldown);
  }

  /** Returns the sender's effective warmup delay in milliseconds. */
  public static long delayMillis(RTPCommandSender sender) {
    return resolveMillis(
        sender, "rtp.nodelay", "rtp.noDelay", "rtp.delay.", ConfigKeys.teleportDelay);
  }

  private static long resolveMillis(
      RTPCommandSender sender, String bypass, String bypassAlias, String permissionPrefix,
      ConfigKeys configKey) {
    if (sender == null) return 0L;
    if (sender.hasPermission(bypass) || sender.hasPermission(bypassAlias)) return 0L;

    int permissionOverride = ParsePermissions.getInt(sender, permissionPrefix);
    if (permissionOverride >= 0) return TimeUnit.SECONDS.toMillis(permissionOverride);

    Configs configs = ActiveConfigs.get();
    if (configs == null) return 0L;
    Object value = configs.getConfigValue(configKey, 0);
    return ConfigParser.parseSecondsDurationMillis(value, 0L);
  }
}
