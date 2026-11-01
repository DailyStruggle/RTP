package io.github.dailystruggle.rtp.common.tasks.teleport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every persistent side-effect sink of the teleport pipeline shall go through
 * {@code SyntheticSideEffects}, so a BIT mock player leaves no persistent trace (ADR-121 section 11)
 * and mock landings never reach the ADR-104 landing heatmap.
 */
class TeleportPipelineSinkSourceTest {

  private static final String REL =
      "src/main/java/io/github/dailystruggle/rtp/common/tasks/teleport/TeleportPipelineTask.java";

  /** Direct sink calls that bypass the gate. */
  private static final String[] FORBIDDEN = {
      ".cacheValue(", ".recordSuccess(", "LandingHeatmap.record(", "updateSharedLastTeleportTime("
  };

  private static String source() throws IOException {
    Path p = Path.of(REL);
    if (!Files.exists(p)) {
      p = Path.of("rtp-core").resolve(REL);
    }
    assertTrue(Files.exists(p), "TeleportPipelineTask.java not found at " + p.toAbsolutePath());
    return Files.readString(p, StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("ADR-121 s11 / ADR-104: TeleportPipelineTask makes no direct persistent sink call")
  void noDirectSinkCalls() throws IOException {
    String[] lines = source().split("\n", -1);
    List<String> hits = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      for (String needle : FORBIDDEN) {
        if (lines[i].contains(needle)) {
          hits.add((i + 1) + ": " + lines[i].trim());
        }
      }
    }
    assertTrue(hits.isEmpty(), "direct sink calls must route through SyntheticSideEffects: " + hits);
  }

  @Test
  @DisplayName("ADR-121 s11 / ADR-104: TeleportPipelineTask routes sinks through SyntheticSideEffects")
  void referencesGate() throws IOException {
    String src = source();
    for (String gate : new String[] {
        "SyntheticSideEffects.persistTeleportData(",
        "SyntheticSideEffects.recordLimitUse(",
        "SyntheticSideEffects.recordSharedLastTeleport(",
        "SyntheticSideEffects.recordLanding("}) {
      assertTrue(src.contains(gate), "expected gate call " + gate);
    }
  }
}
