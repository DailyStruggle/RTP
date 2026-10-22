package io.github.dailystruggle.effectsapi.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ADR-121 section 3 effect stage vocabulary (EffectStage)")
class EffectStageTest {

  @Test
  @DisplayName("tokens are lower-case, unique and looked up case-insensitively")
  void tokensRoundTrip() {
    assertEquals(EffectStage.values().length, EffectStage.tokens().size());
    for (EffectStage stage : EffectStage.values()) {
      assertEquals(stage.name().toLowerCase(), stage.token());
      assertEquals(stage, EffectStage.fromToken(" " + stage.name() + " ").orElseThrow());
    }
    assertTrue(EffectStage.fromToken("postteleprot").isEmpty());
    assertTrue(EffectStage.fromToken(null).isEmpty());
    assertTrue(EffectStage.fromToken("").isEmpty());
  }

  @Test
  @DisplayName("join stages are declared but not dispatched")
  void joinStagesAreNotDispatched() {
    assertFalse(EffectStage.FIRSTJOIN.dispatched());
    assertFalse(EffectStage.JOIN.dispatched());
    assertTrue(EffectStage.POSTTELEPORT.dispatched());
    assertTrue(EffectStage.DEATH.dispatched());
  }

  @Test
  @DisplayName("EffectsGroupKeys#when Javadoc lists exactly the declared tokens")
  void groupKeysJavadocMatchesDeclaredStages() throws IOException {
    Path source = Path.of("src/main/java/io/github/dailystruggle/effectsapi/common/EffectsGroupKeys.java");
    if (!Files.isRegularFile(source)) {
      source = Path.of("api/effects-api").resolve(source);
    }
    String text = Files.readString(source, StandardCharsets.UTF_8);
    Matcher list = Pattern.compile("\\{@code (firstjoin[^}]*)}", Pattern.DOTALL).matcher(text);
    assertTrue(list.find(), "stage list in EffectsGroupKeys Javadoc");
    Set<String> documented = new LinkedHashSet<>();
    Arrays.stream(list.group(1).replace("*", " ").split("/"))
        .map(String::trim).filter(s -> !s.isEmpty()).forEach(documented::add);
    assertEquals(EffectStage.tokens(), documented);
  }
}
