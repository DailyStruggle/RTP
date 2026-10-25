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

  @Test
  @DisplayName("RTP-95: SendMessage.log routes WARNING and SEVERE through Bukkit.getLogger() at their level")
  void logPreservesWarningAndSevereLevelsOnBukkitLogger() {
    be.seeseemelk.mockbukkit.MockBukkit.mock();
    java.util.List<java.util.logging.LogRecord> records = new java.util.ArrayList<>();
    java.util.logging.Handler handler = new java.util.logging.Handler() {
      @Override
      public void publish(java.util.logging.LogRecord record) {
        if (record != null) {
          records.add(record);
        }
      }

      @Override
      public void flush() {}

      @Override
      public void close() {}
    };
    handler.setLevel(java.util.logging.Level.ALL);
    java.util.logging.Logger logger = org.bukkit.Bukkit.getLogger();
    logger.addHandler(handler);
    try {
      SendMessage.log(java.util.logging.Level.WARNING, "&eWarning line");
      SendMessage.log(java.util.logging.Level.SEVERE, "&cSevere line");
      RuntimeException boom = new RuntimeException("boom");
      SendMessage.log(java.util.logging.Level.WARNING, "&eWarning with throwable", boom);
      SendMessage.log(java.util.logging.Level.SEVERE, "&cSevere with throwable", boom);

      org.junit.jupiter.api.Assertions.assertTrue(
          records.stream().anyMatch(r ->
              r.getLevel().equals(java.util.logging.Level.WARNING)
                  && "Warning line".equals(r.getMessage())),
          "Expected WARNING record for 'Warning line' on Bukkit.getLogger()");
      org.junit.jupiter.api.Assertions.assertTrue(
          records.stream().anyMatch(r ->
              r.getLevel().equals(java.util.logging.Level.SEVERE)
                  && "Severe line".equals(r.getMessage())),
          "Expected SEVERE record for 'Severe line' on Bukkit.getLogger()");
      org.junit.jupiter.api.Assertions.assertTrue(
          records.stream().anyMatch(r ->
              r.getLevel().equals(java.util.logging.Level.WARNING)
                  && "Warning with throwable".equals(r.getMessage())
                  && r.getThrown() == boom),
          "Expected WARNING record with throwable on Bukkit.getLogger()");
      org.junit.jupiter.api.Assertions.assertTrue(
          records.stream().anyMatch(r ->
              r.getLevel().equals(java.util.logging.Level.SEVERE)
                  && "Severe with throwable".equals(r.getMessage())
                  && r.getThrown() == boom),
          "Expected SEVERE record with throwable on Bukkit.getLogger()");
    } finally {
      logger.removeHandler(handler);
      be.seeseemelk.mockbukkit.MockBukkit.unmock();
    }
  }
}