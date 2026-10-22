package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPCommandSender;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("REQ-FABRIC-F-003 / REQ-NEOFORGE-F-003: Sender cooldown and delay resolution")
class SenderTimingsTest {
  @TempDir File tempDir;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir);
  }

  @Test
  @DisplayName("RTP-78: bypass permissions force cooldown and delay to zero")
  void bypassPermissionsReturnZero() {
    assertEquals(0L, SenderTimings.cooldownMillis(sender("rtp.nocooldown")));
    assertEquals(0L, SenderTimings.delayMillis(sender("rtp.nodelay")));
  }

  @Test
  @DisplayName("numeric permission nodes override configured cooldown and delay")
  void numericPermissionsOverrideConfig() {
    assertEquals(3_000L, SenderTimings.cooldownMillis(sender("rtp.cooldown.3")));
    assertEquals(2_000L, SenderTimings.delayMillis(sender("rtp.delay.2")));
  }

  @Test
  @DisplayName("configured cooldown and delay apply when no permission override exists")
  void configValuesAreFallback() {
    setConfig(ConfigKeys.teleportCooldown, "7");
    setConfig(ConfigKeys.teleportDelay, "4");

    assertEquals(7_000L, SenderTimings.cooldownMillis(sender()));
    assertEquals(4_000L, SenderTimings.delayMillis(sender()));
  }

  private static TimingSender sender(String... permissions) {
    return new TimingSender(permissions);
  }

  @SuppressWarnings("unchecked")
  private static void setConfig(ConfigKeys key, Object value) {
    ConfigParser<ConfigKeys> parser =
        (ConfigParser<ConfigKeys>) ActiveConfigs.get().getParser(ConfigKeys.class);
    parser.setConfigValue(key.name(), value);
  }

  private static final class TimingSender extends MockRTPCommandSender {
    private final Set<String> permissions;

    private TimingSender(String... permissions) {
      super(UUID.randomUUID(), "TimingSender");
      this.permissions = Arrays.stream(permissions)
          .map(permission -> permission.toLowerCase(Locale.ROOT))
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean hasPermission(String permission) {
      return permission != null && permissions.contains(permission.toLowerCase(Locale.ROOT));
    }

    @Override
    public Set<String> getEffectivePermissions() {
      return permissions;
    }
  }
}
