package io.github.dailystruggle.rtp.bukkitplatform.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SendMessageFontTagTest {

  @Test
  @DisplayName("strips MiniMessage font wrappers while preserving legacy codes and other markup")
  void stripsFontTagsOnly() {
    assertEquals(
        "&aBit score",
        SendMessage.stripFontTags("<font:minecraft:uniform>&aBit score</font>"));
    assertEquals(
        "text", SendMessage.stripFontTags("<FONT:minecraft:uniform>text</FONT:ignored>"));
    assertEquals(
        "<rainbow><red>text</red></rainbow> <",
        SendMessage.stripFontTags("<rainbow><red>text</red></rainbow> <"));
    assertEquals("", SendMessage.stripFontTags(null));
    assertEquals("", SendMessage.stripFontTags(""));
  }
}