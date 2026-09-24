package io.github.dailystruggle.rtp.common.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ActionPlaceholderSanitizerTest {

  @Test
  @DisplayName("UUID placeholders are replaced safely")
  void testUuidPlaceholders() {
    UUID pId = UUID.randomUUID();
    String cmd = "tag [player] add rtp_session_[session_id]";
    Map<String, Object> tokens = Map.of(
        "player", pId,
        "session_id", "1234abcd");

    String result = ActionPlaceholderSanitizer.substitute(cmd, tokens);
    assertEquals("tag " + pId + " add rtp_session_1234abcd", result);
  }

  @Test
  @DisplayName("Unsanitized substrings containing semicolons or spaces are stripped")
  void testSanitizerInjectionPrevention() {
    String cmd = "broadcast [winner] won!";
    Map<String, Object> tokens = Map.of(
        "winner", "attacker; op baduser");

    String result = ActionPlaceholderSanitizer.substitute(cmd, tokens);
    assertEquals("broadcast  won!", result, "Dangerous token should be blanked out");
  }

  @Test
  @DisplayName("Unmapped placeholders remain intact")
  void testUnmappedPlaceholders() {
    String cmd = "give [player] [item] 1";
    Map<String, Object> tokens = Map.of("player", "Steve");

    String result = ActionPlaceholderSanitizer.substitute(cmd, tokens);
    assertEquals("give Steve [item] 1", result);
  }
}
