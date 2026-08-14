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
  void nestedRainbowPrefixInsideGradientLeavesNoRawTags() {
    // Regression: a "<rainbow>[RTP]</rainbow>" prefix wrapped inside an outer
    // <gradient> (e.g. messages.yml "<gradient:...>[P0] ...</gradient>") must
    // not pair the inner <rainbow> opener with the outer </gradient> close, nor
    // print raw tag markup. The close tag is now matched by backreference.
    String out = MiniMessageColorExpander.expand(
        "<gradient:#9D7CD8:#5FB3B3><rainbow>[RTP]</rainbow> Teleporting</gradient>");
    assertFalse(out.contains("<rainbow>"), "inner rainbow tag must be consumed: " + out);
    assertFalse(out.contains("</rainbow>"), "inner rainbow close must be consumed: " + out);
    assertFalse(out.contains("<gradient"), "outer gradient tag must be consumed: " + out);
    assertFalse(out.contains("</gradient>"), "dangling gradient close must not survive: " + out);
    assertTrue(out.indexOf('\u00a7') >= 0, "expected legacy hex section codes: " + out);
  }

  @Test
  void nestedRainbowHexMarkerNotLeakedByOuterGradient() {
    // Regression (NeoForge/Fabric): the P0 prefix "<rainbow>[RTP]</rainbow>"
    // wrapped in an outer <gradient> (messages.yml teleportMessage) must not
    // leak the nested rainbow's legacy hex marker ("\u00a7x") as a literal 'x'
    // glyph. Before the fix the outer gradient injected its own color into the
    // middle of each nested "\u00a7x\u00a7r\u00a7r..." marker, so the client
    // rendered a stray 'x' before "[RTP]".
    String msg = "<gradient:#5FB3B3:#9D7CD8><rainbow>[RTP]</rainbow> Teleported</gradient>";
    String out = MiniMessageColorExpander.expand(msg);

    StringBuilder visible = new StringBuilder();
    char[] chars = out.toCharArray();
    int i = 0;
    while (i < chars.length) {
      if (chars[i] == '\u00a7' && i + 1 < chars.length) { i += 2; continue; }
      visible.append(chars[i]);
      i++;
    }
    assertEquals("[RTP] Teleported", visible.toString(),
        "no stray glyphs should leak from the nested rainbow hex markers: " + out);
  }

  @Test
  void gradientLastCharacterGetsEndColorNotStart() {
    // Regression: a plain (phase-less) gradient must colour its LAST visible
    // character with the END stop, not wrap it back to the START stop. The
    // per-character position t reaches exactly 1.0 for the final glyph, and
    // "t % 1f" used to map that endpoint back to 0.0, so e.g. the trailing
    // "s" of "38ms" in the default teleportMessage rendered in the gradient's
    // start colour instead of its end colour.
    String out = MiniMessageColorExpander.expand("<gradient:#5fb3b3:#9d7cd8>abc</gradient>");
    // The last coloured run must be the end stop 9d7cd8, never the start 5fb3b3.
    int lastMarker = out.lastIndexOf("\u00a7x");
    assertTrue(lastMarker >= 0, "expected a legacy hex marker: " + out);
    String tail = out.substring(lastMarker);
    // Reconstruct the 6 hex digits of the final marker.
    StringBuilder hex = new StringBuilder();
    for (int i = 0; i < tail.length() && hex.length() < 6; i++) {
      char c = tail.charAt(i);
      boolean isHex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
      if (isHex) hex.append(c);
    }
    assertEquals("9d7cd8", hex.toString(),
        "final gradient glyph must use the end stop, not the start: " + out);
  }

  @Test
  void nullAndEmptyAreSafe() {
    assertEquals(null, MiniMessageColorExpander.expand(null));
    assertEquals("", MiniMessageColorExpander.expand(""));
  }
}
