package io.github.dailystruggle.rtp.common.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies {@link MiniMessageColorExpander} converts MiniMessage color/format
 * markup into legacy {@code &}-codes for Adventure-less platforms (Fabric,
 * NeoForge), while leaving non-markup content untouched.
 */
class MiniMessageColorExpanderTest {

  @Test
  void namedColorTagBecomesLegacyCode() {
    assertEquals("&ahello&r", MiniMessageColorExpander.expand("<green>hello</green>"));
  }

  @Test
  void hexColorTagBecomesLegacyHex() {
    assertEquals("&#ff0000red&r", MiniMessageColorExpander.expand("<#ff0000>red</#ff0000>"));
  }

  @Test
  void colorPrefixFormsResolve() {
    assertEquals("&c", MiniMessageColorExpander.expand("<color:red>"));
    assertEquals("&c", MiniMessageColorExpander.expand("<c:red>"));
    assertEquals("&#00ff00", MiniMessageColorExpander.expand("<color:#00ff00>"));
  }

  @Test
  void formatTagsBecomeLegacyCodes() {
    assertEquals("&lbold&r", MiniMessageColorExpander.expand("<bold>bold</bold>"));
    assertEquals("&oitalic&r", MiniMessageColorExpander.expand("<i>italic</i>"));
  }

  @Test
  void unsupportedTagsAreDropped() {
    assertEquals("click me", MiniMessageColorExpander.expand("<click:run_command:/rtp>click me</click>"));
    assertEquals("hi", MiniMessageColorExpander.expand("<hover:show_text:'x'>hi</hover>"));
  }

  @Test
  void plainTextUnchanged() {
    assertEquals("just text", MiniMessageColorExpander.expand("just text"));
  }

  @Test
  void legacyCodesUntouched() {
    assertEquals("&ahello", MiniMessageColorExpander.expand("&ahello"));
  }

  @Test
  void rainbowExpandsToPerCharacterHex() {
    String out = MiniMessageColorExpander.expand("<rainbow>RTP</rainbow>");
    // GradientExpander emits section-sign legacy hex (\u00a7x...) per character.
    assertTrue(out.indexOf('\u00a7') >= 0, "expected legacy hex section codes");
    assertFalse(out.contains("<rainbow>"), "rainbow tag should be consumed");
  }

  @Test
  void nullAndEmptyAreSafe() {
    assertEquals(null, MiniMessageColorExpander.expand(null));
    assertEquals("", MiniMessageColorExpander.expand(""));
  }
}
