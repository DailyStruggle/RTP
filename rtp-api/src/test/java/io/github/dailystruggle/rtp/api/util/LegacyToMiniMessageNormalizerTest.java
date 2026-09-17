package io.github.dailystruggle.rtp.api.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LegacyToMiniMessageNormalizerTest {

  @Test
  @DisplayName("Plain text without legacy codes passes through untouched")
  void plainTextUntouched() {
    String in = "Hello world! 123";
    assertEquals(in, LegacyToMiniMessageNormalizer.translate(in));
  }

  @Test
  @DisplayName("Existing MiniMessage tags pass through untouched")
  void existingTagsUntouched() {
    String in = "<red>Hello <bold>world</bold></red>";
    assertEquals(in, LegacyToMiniMessageNormalizer.translate(in));
  }

  @Test
  @DisplayName("Single-character legacy codes convert to named tags")
  void legacyCodesConvert() {
    String in = "&cRed &aGreen &lBold &rReset";
    String out = LegacyToMiniMessageNormalizer.translate(in);
    assertEquals("<red>Red <green>Green <bold>Bold <reset>Reset", out);
  }

  @Test
  @DisplayName("Direct hex codes convert to <#rrggbb>")
  void directHexConvert() {
    assertEquals("<#abcdef>hello", LegacyToMiniMessageNormalizer.translate("#abcdefhello"));
    assertEquals("<#abcdef>hello", LegacyToMiniMessageNormalizer.translate("&#abcdefhello"));
    assertEquals("<#abcdef>hello", LegacyToMiniMessageNormalizer.translate("\u00a7#abcdefhello"));
  }

  @Test
  @DisplayName("Gradients with hex stops are not corrupted")
  void gradientHexStopsPreserved() {
    String in = "<gradient:#ff0000:#00ff00>Welcome</gradient>";
    assertEquals(in, LegacyToMiniMessageNormalizer.translate(in));
  }
}
