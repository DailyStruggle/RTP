package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Branch coverage for lifecycle placeholder resolution and sanitization (ADR-093 Section 4).
 */
class ActionPlaceholderSanitizerBranchesTest {

  private RTPServerAccessor savedAccessor;

  @BeforeEach
  void saveAccessor() {
    savedAccessor = RTPRuntime.serverAccessor();
    RTPRuntime.setServerAccessor(null);
  }

  @AfterEach
  void restoreAccessor() {
    RTPRuntime.setServerAccessor(savedAccessor);
  }

  private static RTPCommandSender sender(UUID id, String name) {
    RTPCommandSender s = mock(RTPCommandSender.class);
    when(s.uuid()).thenReturn(id);
    when(s.name()).thenReturn(name);
    return s;
  }

  @Test
  @DisplayName("substitute returns empty for null/blank commands and the raw command for null/empty tokens")
  void substituteGuardClauses() {
    assertEquals("", ActionPlaceholderSanitizer.substitute(null, Map.of("a", "b")));
    assertEquals("", ActionPlaceholderSanitizer.substitute("   ", Map.of("a", "b")));
    assertEquals("say [x]", ActionPlaceholderSanitizer.substitute("say [x]", null));
    assertEquals("say [x]", ActionPlaceholderSanitizer.substitute("say [x]", Map.of()));
  }

  @Test
  @DisplayName("hasMissingTarget handles blank commands, null token maps and resolved targets")
  void hasMissingTargetGuards() {
    assertFalse(ActionPlaceholderSanitizer.hasMissingTarget(null, Map.of()));
    assertFalse(ActionPlaceholderSanitizer.hasMissingTarget("  ", Map.of()));
    assertTrue(ActionPlaceholderSanitizer.hasMissingTarget("tp [target]", null));
    assertFalse(ActionPlaceholderSanitizer.hasMissingTarget("tp [TARGET]", Map.of("target", "Bob")));
    assertFalse(ActionPlaceholderSanitizer.hasMissingTarget("say [player]", null));
  }

  @Test
  @DisplayName("containsUnresolvedPrefix rejects blank inputs and matches exact or underscore-suffixed keys")
  void containsUnresolvedPrefixBranches() {
    assertFalse(ActionPlaceholderSanitizer.containsUnresolvedPrefix(null, "target"));
    assertFalse(ActionPlaceholderSanitizer.containsUnresolvedPrefix("  ", "target"));
    assertFalse(ActionPlaceholderSanitizer.containsUnresolvedPrefix("[target]", null));
    assertFalse(ActionPlaceholderSanitizer.containsUnresolvedPrefix("[target]", "  "));
    assertTrue(ActionPlaceholderSanitizer.containsUnresolvedPrefix("tp [target]", "TARGET"));
    assertTrue(ActionPlaceholderSanitizer.containsUnresolvedPrefix("tp [target_name_2]", "target"));
    assertFalse(ActionPlaceholderSanitizer.containsUnresolvedPrefix("tp [targets] [other]", "target"));
  }

  @Test
  @DisplayName("_uuid suffix resolves command senders, ignores non-UUID values and unknown bases")
  void uuidSuffixBranches() {
    UUID id = UUID.randomUUID();
    assertEquals("tag " + id,
        ActionPlaceholderSanitizer.substitute("tag [winner_uuid]", Map.of("winner", sender(id, "Alex"))));
    assertEquals("tag [winner_uuid]",
        ActionPlaceholderSanitizer.substitute("tag [winner_uuid]", Map.of("winner", "Alex")));
    assertEquals("tag [ghost_uuid]",
        ActionPlaceholderSanitizer.substitute("tag [ghost_uuid]", Map.of("winner", "Alex")));
    assertEquals("tag [ghost_name]",
        ActionPlaceholderSanitizer.substitute("tag [ghost_name]", Map.of("winner", "Alex")));
  }

  @Test
  @DisplayName("Keys with a leading or trailing underscore skip numbered fallback resolution")
  void underscoreEdgeKeys() {
    Map<String, Object> tokens = Map.of("lead", "x", "trail", "y");
    assertEquals("a [_lead] b [trail_]",
        ActionPlaceholderSanitizer.substitute("a [_lead] b [trail_]", tokens));
  }

  @Test
  @DisplayName("Numbered _uuid placeholders resolve UUIDs and senders and keep other values verbatim")
  void numberedUuidFallback() {
    UUID id = UUID.randomUUID();
    UUID senderId = UUID.randomUUID();
    assertEquals("tag " + id,
        ActionPlaceholderSanitizer.substitute("tag [sender_uuid_1]", Map.of("sender_1", id)));
    assertEquals("tag " + senderId,
        ActionPlaceholderSanitizer.substitute("tag [sender_uuid_1]", Map.of("sender_1", sender(senderId, "Bob"))));
    assertEquals("tag [sender_uuid_1]",
        ActionPlaceholderSanitizer.substitute("tag [sender_uuid_1]", Map.of("sender_1", "Bob")));
    assertEquals("tag [sender_uuid_1]",
        ActionPlaceholderSanitizer.substitute("tag [sender_uuid_1]", Map.of("sender_2", id)));
    assertEquals("tag [item_1]",
        ActionPlaceholderSanitizer.substitute("tag [item_1]", Map.of("item_2", "sword")));
  }

  @Test
  @DisplayName("Player-entity keys prefer the sender name; other keys use the sender UUID")
  void playerEntityKeysPreferName() {
    String[] entityKeys = {
        "violator", "violator_name", "winner", "winner_name", "victim", "victim_name",
        "custom_name", "player_x", "target_x", "sender_x", "violator_x", "winner_x", "victim_x"
    };
    for (String key : entityKeys) {
      UUID id = UUID.randomUUID();
      String result = ActionPlaceholderSanitizer.substitute(
          "say [" + key + "]", Map.of(key, sender(id, "Name_" + key.length())));
      assertEquals("say Name_" + key.length(), result, "key " + key + " should resolve to the sender name");
    }

    UUID otherId = UUID.randomUUID();
    assertEquals("say " + otherId,
        ActionPlaceholderSanitizer.substitute("say [other]", Map.of("other", sender(otherId, "Named"))));
  }

  @Test
  @DisplayName("Senders with unsafe or null names fall back to their UUID")
  void senderUnsafeNameFallsBackToUuid() {
    UUID unsafeId = UUID.randomUUID();
    UUID nullId = UUID.randomUUID();
    assertEquals("say " + unsafeId,
        ActionPlaceholderSanitizer.substitute("say [player]", Map.of("player", sender(unsafeId, "bad;name"))));
    assertEquals("say " + nullId,
        ActionPlaceholderSanitizer.substitute("say [player]", Map.of("player", sender(nullId, null))));
  }

  @Test
  @DisplayName("Null token values resolve to empty")
  void nullValueResolvesEmpty() {
    Map<String, Object> tokens = new HashMap<>();
    tokens.put("player", null);
    assertEquals("say !", ActionPlaceholderSanitizer.substitute("say [player]!", tokens));
  }

  @Test
  @DisplayName("UUID player tokens fall back to the UUID without an accessor or a safe online name")
  void uuidNameLookupFallbacks() {
    UUID id = UUID.randomUUID();
    assertEquals("say " + id, ActionPlaceholderSanitizer.substitute("say [player]", Map.of("player", id)));

    RTPServerAccessor accessor = mock(RTPServerAccessor.class);
    RTPRuntime.setServerAccessor(accessor);
    assertEquals("say " + id, ActionPlaceholderSanitizer.substitute("say [player]", Map.of("player", id)));

    RTPPlayer nullName = mock(RTPPlayer.class);
    when(nullName.name()).thenReturn(null);
    when(accessor.getPlayer(id)).thenReturn(nullName);
    assertEquals("say " + id, ActionPlaceholderSanitizer.substitute("say [player]", Map.of("player", id)));

    RTPPlayer unsafeName = mock(RTPPlayer.class);
    when(unsafeName.name()).thenReturn("x y;z");
    when(accessor.getPlayer(id)).thenReturn(unsafeName);
    assertEquals("say " + id, ActionPlaceholderSanitizer.substitute("say [player]", Map.of("player", id)));

    RTPPlayer safeName = mock(RTPPlayer.class);
    when(safeName.name()).thenReturn("Steve");
    when(accessor.getPlayer(id)).thenReturn(safeName);
    assertEquals("say Steve", ActionPlaceholderSanitizer.substitute("say [player]", Map.of("player", id)));
  }

  @Test
  @DisplayName("Collections skip null and unsafe items when joining")
  void collectionSkipsNullAndUnsafeItems() {
    Map<String, Object> tokens = new LinkedHashMap<>();
    tokens.put("items", Arrays.asList(null, "ok", "bad;item", "fine"));
    assertEquals("say ok fine", ActionPlaceholderSanitizer.substitute("say [items]", tokens));
  }
}
