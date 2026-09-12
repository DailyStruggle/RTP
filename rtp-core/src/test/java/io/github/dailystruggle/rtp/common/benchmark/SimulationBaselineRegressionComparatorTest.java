package io.github.dailystruggle.rtp.common.benchmark;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class SimulationBaselineRegressionComparatorTest {

  @Test
  @DisplayName("parseBaselineJson correctly parses sample JSON and handles numeric values")
  void testParseBaselineJson() {
    String json = """
        {
          "version": 1,
          "benchmarks": [
            {
              "stem": "sample-stem",
              "section": "SectionA",
              "subject": "Subject1",
              "metric": "latency_ms",
              "value": 12.345,
              "provenance": "MEASURED"
            },
            {
              "stem": "sample-stem",
              "section": "SectionA",
              "subject": "Subject1",
              "metric": "throughput_ops/sec",
              "value": 1000.0,
              "provenance": "DERIVED"
            }
          ]
        }
        """;

    Map<String, SimulationBaselineRegressionComparator.BaselineEntry> baseline =
        SimulationBaselineRegressionComparator.parseBaselineJson(json);

    assertEquals(2, baseline.size());
    assertTrue(baseline.containsKey("sample-stem::SectionA::Subject1::latency_ms"));
    assertTrue(baseline.containsKey("sample-stem::SectionA::Subject1::throughput_ops/sec"));

    SimulationBaselineRegressionComparator.BaselineEntry entry1 =
        baseline.get("sample-stem::SectionA::Subject1::latency_ms");
    assertEquals(12.345, entry1.baselineValue(), 1e-6);
    assertEquals("MEASURED", entry1.provenance());
  }

  @Test
  @DisplayName("checkRegressions flags regressions exceeding tolerance threshold")
  void testCheckRegressions() {
    String json = """
        {
          "version": 1,
          "benchmarks": [
            {
              "stem": "bench",
              "section": "S",
              "subject": "Sub",
              "metric": "latency_ms",
              "value": 100.0,
              "provenance": "MEASURED"
            },
            {
              "stem": "bench",
              "section": "S",
              "subject": "Sub",
              "metric": "throughput_ops/sec",
              "value": 500.0,
              "provenance": "DERIVED"
            }
          ]
        }
        """;

    Map<String, SimulationBaselineRegressionComparator.BaselineEntry> baseline =
        SimulationBaselineRegressionComparator.parseBaselineJson(json);

    // Current run with 30% increase in latency (130.0 > 100.0) -> regression with tolerance 25%
    // and throughput drop by 30% (350.0 < 500.0) -> regression with tolerance 25%
    List<SimulationReport.Row> rows = List.of(
        new SimulationReport.Row("S", "Sub", "latency_ms", "130.0", SimulationReport.Provenance.MEASURED),
        new SimulationReport.Row("S", "Sub", "throughput_ops/sec", "350.0", SimulationReport.Provenance.DERIVED)
    );

    List<SimulationBaselineRegressionComparator.RegressionResult> regressions =
        SimulationBaselineRegressionComparator.checkRegressions(baseline, Map.of("bench", rows), 25.0);

    assertEquals(2, regressions.size());

    // With 35% tolerance, neither should be flagged as regression
    List<SimulationBaselineRegressionComparator.RegressionResult> noRegressions =
        SimulationBaselineRegressionComparator.checkRegressions(baseline, Map.of("bench", rows), 35.0);
    assertTrue(noRegressions.isEmpty());
  }

  @Test
  @DisplayName("generateBaselineJson formats output accurately")
  void testGenerateBaselineJson() {
    List<SimulationReport.Row> rows = List.of(
        new SimulationReport.Row("Sec", "Sub", "count", "42.0", SimulationReport.Provenance.MEASURED)
    );
    String generated = SimulationBaselineRegressionComparator.generateBaselineJson(Map.of("test-stem", rows));
    assertTrue(generated.contains("\"stem\": \"test-stem\""));
    assertTrue(generated.contains("\"metric\": \"count\""));
    assertTrue(generated.contains("42.0000"));
  }
}
