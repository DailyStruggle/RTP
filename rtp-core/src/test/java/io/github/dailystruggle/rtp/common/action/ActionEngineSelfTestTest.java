package io.github.dailystruggle.rtp.common.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ActionEngineSelfTestTest {
  private static final String CASE_TEMPLATE = """
      cases:
        smoke:
          players: 2
          script: []
          expected:
            - say bundled 00000000
          definition:
            placement: false
            confinement:
              boundary: NONE
              duration: 30s
            lifecycle:
              onStart:
                - gate:
                    players: ">= 2"
                  run:
                    - CONSOLE: "say bundled [session_id]"
      """;

  @Test
  void matchingBundledDefinitionIsHealthy() {
    BitReport report = run(CASE_TEMPLATE);

    assertEquals(BitHealth.HEALTHY, report.health(), report.findings().toString());
    assertTrue(report.findings().isEmpty());
  }

  @Test
  void commandMismatchFailsActionSubsystem() {
    String mismatch = CASE_TEMPLATE.replace("say bundled 00000000", "say incorrect");

    BitReport report = run(mismatch);

    assertEquals(BitHealth.FAILED, report.health());
    assertTrue(report.findings().stream().anyMatch(finding ->
        finding.code().equals("ACTION_SELFTEST_MISMATCH") && finding.codeId() == 2107));
  }

  @Test
  void malformedCaseIsReportedAsInvalid() {
    BitReport report = run("cases:\n  broken:\n    players: 2\n    expected: []\n");

    assertEquals(BitHealth.FAILED, report.health());
    assertTrue(report.findings().stream().anyMatch(finding ->
        finding.code().equals("ACTION_SELFTEST_CASE_INVALID") && finding.codeId() == 2108));
  }

  private static BitReport run(String yaml) {
    return ActionEngineSelfTest.run(
        new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test-action-engine-selftest.yml");
  }
}
