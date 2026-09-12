package io.github.dailystruggle.rtp.common.benchmark;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares benchmark simulation runs against a committed baseline JSON (ADR-080, ENTERPRISE_READINESS.md item 26).
 *
 * <p>Supports:
 * <ul>
 *   <li>Configurable tolerance via system property {@code rtp.simulation.regressionTolerance} (default: 25.0%).</li>
 *   <li>Warn or Fail mode via system property {@code rtp.simulation.failOnRegression} (default: false, warn).</li>
 *   <li>Updating baseline via system property {@code rtp.simulation.updateBaseline} (default: false).</li>
 *   <li>Baseline JSON path via system property {@code rtp.simulation.baselinePath} or default classpath resource.</li>
 * </ul>
 */
public final class SimulationBaselineRegressionComparator {

  public static final String DEFAULT_BASELINE_RESOURCE = "/benchmarks/simulation-baseline.json";
  public static final String PROP_BASELINE_PATH = "rtp.simulation.baselinePath";
  public static final String PROP_TOLERANCE = "rtp.simulation.regressionTolerance";
  public static final String PROP_FAIL_ON_REGRESSION = "rtp.simulation.failOnRegression";
  public static final String PROP_UPDATE_BASELINE = "rtp.simulation.updateBaseline";

  public record BaselineEntry(
      String stem,
      String section,
      String subject,
      String metric,
      double baselineValue,
      String provenance
  ) {
    public String key() {
      return stem + "::" + section + "::" + subject + "::" + metric;
    }
  }

  public record RegressionResult(
      String stem,
      String section,
      String subject,
      String metric,
      double baselineValue,
      double currentValue,
      double pctDiff,
      boolean isTiming,
      String provenance
  ) {
    public String toLogString() {
      String type = isTiming ? "TIMING" : "DETERMINISTIC";
      return String.format("[%s REGRESSION %s] %s | %s | %s | %s: baseline=%.3f, current=%.3f (change=%+.2f%%)",
          type, provenance, stem, section, subject, metric, baselineValue, currentValue, pctDiff);
    }
  }

  private static final Map<String, List<SimulationReport.Row>> RUN_REGISTRY = new ConcurrentHashMap<>();

  public static void registerReportRows(String stem, List<SimulationReport.Row> rows) {
    RUN_REGISTRY.put(stem, new ArrayList<>(rows));
  }

  public static Map<String, List<SimulationReport.Row>> getRunRegistry() {
    return Collections.unmodifiableMap(RUN_REGISTRY);
  }

  /**
   * Parses baseline entries from an input JSON string.
   */
  public static Map<String, BaselineEntry> parseBaselineJson(String json) {
    Map<String, BaselineEntry> entries = new LinkedHashMap<>();
    if (json == null || json.isBlank()) {
      return entries;
    }

    // Pattern to match each entry object in "benchmarks": [ ... ]
    // Each entry has: stem, section, subject, metric, value, provenance
    Pattern entryPattern = Pattern.compile("\\{[^{}]*\"stem\"\\s*:\\s*\"([^\"]+)\"[^{}]*\\}", Pattern.DOTALL);
    Matcher entryMatcher = entryPattern.matcher(json);

    while (entryMatcher.find()) {
      String block = entryMatcher.group(0);
      String stem = extractField(block, "stem");
      String section = extractField(block, "section");
      String subject = extractField(block, "subject");
      String metric = extractField(block, "metric");
      String valStr = extractField(block, "value");
      String prov = extractField(block, "provenance");

      if (stem != null && metric != null && valStr != null) {
        try {
          double val = Double.parseDouble(valStr);
          BaselineEntry entry = new BaselineEntry(
              stem,
              section != null ? section : "",
              subject != null ? subject : "",
              metric,
              val,
              prov != null ? prov : "UNKNOWN"
          );
          entries.put(entry.key(), entry);
        } catch (NumberFormatException ignored) {
        }
      }
    }

    return entries;
  }

  /**
   * Parses a single benchmark json report (as written by SimulationReport) and loads its rows into the run registry.
   */
  public static void parseJsonReportIntoRegistry(String json) {
    if (json == null || json.isBlank()) return;
    String fileStem = extractField(json, "fileStem");
    if (fileStem == null || fileStem.isBlank()) return;

    List<SimulationReport.Row> rows = new ArrayList<>();
    Pattern rowPattern = Pattern.compile("\\{[^{}]*\"section\"[^{}]*\\}", Pattern.DOTALL);
    Matcher m = rowPattern.matcher(json);
    while (m.find()) {
      String block = m.group(0);
      String section = extractField(block, "section");
      String subject = extractField(block, "subject");
      String metric = extractField(block, "metric");
      String value = extractField(block, "value");
      String tierStr = extractField(block, "tier");

      SimulationReport.Provenance tier = SimulationReport.Provenance.MEASURED;
      if (tierStr != null) {
        try {
          tier = SimulationReport.Provenance.valueOf(tierStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {}
      }

      if (metric != null && value != null) {
        rows.add(new SimulationReport.Row(
            section != null ? section : "",
            subject != null ? subject : "",
            metric,
            value,
            tier
        ));
      }
    }

    if (!rows.isEmpty()) {
      registerReportRows(fileStem, rows);
    }
  }

  private static String extractField(String block, String fieldName) {
    Pattern p = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(?:\"([^\"]*)\"|([0-9eE.-]+))");
    Matcher m = p.matcher(block);
    if (m.find()) {
      return m.group(1) != null ? m.group(1) : m.group(2);
    }
    return null;
  }

  /**
   * Generates formatted baseline JSON from benchmark rows.
   */
  public static String generateBaselineJson(Map<String, List<SimulationReport.Row>> registry) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("  \"version\": 1,\n");
    sb.append("  \"comment\": \"ADR-080 simulation benchmark committed baseline (ENTERPRISE_READINESS.md item 26)\",\n");
    sb.append("  \"generated\": \"").append(java.time.OffsetDateTime.now()).append("\",\n");
    sb.append("  \"benchmarks\": [\n");

    List<String> sortedStems = new ArrayList<>(registry.keySet());
    Collections.sort(sortedStems);

    boolean first = true;
    for (String stem : sortedStems) {
      List<SimulationReport.Row> rows = registry.get(stem);
      for (SimulationReport.Row r : rows) {
        // Try parsing value as double
        try {
          double v = Double.parseDouble(r.value());
          if (!first) {
            sb.append(",\n");
          }
          first = false;
          sb.append("    {\n");
          sb.append("      \"stem\": ").append(jsonQuote(stem)).append(",\n");
          sb.append("      \"section\": ").append(jsonQuote(r.section())).append(",\n");
          sb.append("      \"subject\": ").append(jsonQuote(r.subject())).append(",\n");
          sb.append("      \"metric\": ").append(jsonQuote(r.metric())).append(",\n");
          sb.append(String.format(Locale.ROOT, "      \"value\": %.4f,\n", v));
          sb.append("      \"provenance\": ").append(jsonQuote(r.tier().name())).append("\n");
          sb.append("    }");
        } catch (NumberFormatException ignored) {
          // Non-numeric rows (e.g. string labels) are omitted from numeric regression checks
        }
      }
    }

    sb.append("\n  ]\n");
    sb.append("}\n");
    return sb.toString();
  }

  private static String jsonQuote(String s) {
    if (s == null) return "\"\"";
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
  }

  /**
   * Loads baseline JSON from path or resource.
   */
  public static String loadBaselineJson() {
    String customPath = System.getProperty(PROP_BASELINE_PATH);
    if (customPath != null && !customPath.isBlank()) {
      Path p = Paths.get(customPath);
      if (Files.exists(p)) {
        try {
          return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
          System.out.println("[DEBUG_LOG] Failed to read custom baseline file: " + e.getMessage());
        }
      }
    }

    // Fallback to source file if running in local project tree
    Path localSrcPath = Paths.get("src/test/resources/benchmarks/simulation-baseline.json");
    if (Files.exists(localSrcPath)) {
      try {
        return Files.readString(localSrcPath, StandardCharsets.UTF_8);
      } catch (IOException ignored) {}
    }

    // Try classpath resource
    try (InputStream is = SimulationBaselineRegressionComparator.class.getResourceAsStream(DEFAULT_BASELINE_RESOURCE)) {
      if (is != null) {
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
      }
    } catch (IOException ignored) {}

    return null;
  }

  /**
   * Evaluates regressions between the current run registry and the baseline.
   *
   * @param tolerancePct percentage threshold (e.g. 25.0)
   * @return list of detected regressions
   */
  public static List<RegressionResult> checkRegressions(
      Map<String, BaselineEntry> baseline,
      Map<String, List<SimulationReport.Row>> current,
      double tolerancePct
  ) {
    List<RegressionResult> regressions = new ArrayList<>();
    if (baseline == null || baseline.isEmpty()) {
      return regressions;
    }

    for (Map.Entry<String, List<SimulationReport.Row>> e : current.entrySet()) {
      String stem = e.getKey();
      for (SimulationReport.Row r : e.getValue()) {
        String key = stem + "::" + r.section() + "::" + r.subject() + "::" + r.metric();
        BaselineEntry base = baseline.get(key);
        if (base == null) {
          continue;
        }

        try {
          double curVal = Double.parseDouble(r.value());
          double baseVal = base.baselineValue();

          // Regression direction:
          // For latency, time (ns, us, ms, s), memory bytes, allocations, ops/candidate: HIGHER is worse.
          // For throughput, speedup: LOWER is worse.
          // If metric indicates speed/throughput:
          boolean isSpeedMetric = r.metric().toLowerCase(Locale.ROOT).contains("speedup")
              || r.metric().toLowerCase(Locale.ROOT).contains("throughput")
              || r.metric().toLowerCase(Locale.ROOT).contains("/sec");

          boolean isTiming = r.metric().toLowerCase(Locale.ROOT).contains("ns")
              || r.metric().toLowerCase(Locale.ROOT).contains("ms")
              || r.metric().toLowerCase(Locale.ROOT).contains("us")
              || r.metric().toLowerCase(Locale.ROOT).contains("time")
              || r.metric().toLowerCase(Locale.ROOT).contains("latency");

          double diffPct;
          boolean regressed = false;

          if (isSpeedMetric) {
            // Drop in speed is regression: (baseVal - curVal) / baseVal
            if (baseVal > 1e-9 && curVal < baseVal) {
              diffPct = ((baseVal - curVal) / baseVal) * 100.0;
              if (diffPct > tolerancePct) {
                regressed = true;
              }
            } else {
              diffPct = 0;
            }
          } else {
            // Increase in cost/alloc/count is regression: (curVal - baseVal) / baseVal
            if (baseVal > 1e-9 && curVal > baseVal) {
              diffPct = ((curVal - baseVal) / baseVal) * 100.0;
              if (diffPct > tolerancePct) {
                regressed = true;
              }
            } else {
              diffPct = 0;
            }
          }

          if (regressed) {
            regressions.add(new RegressionResult(
                stem,
                r.section(),
                r.subject(),
                r.metric(),
                baseVal,
                curVal,
                diffPct,
                isTiming,
                r.tier().name()
            ));
          }
        } catch (NumberFormatException ignored) {}
      }
    }

    return regressions;
  }

  /**
   * Main gate execution: compares against baseline, logs outcomes, and optionally updates baseline or fails.
   */
  public static void evaluateAndReport() {
    boolean updateBaseline = Boolean.getBoolean(PROP_UPDATE_BASELINE);
    boolean failOnRegression = Boolean.getBoolean(PROP_FAIL_ON_REGRESSION);

    double tolerance = 25.0;
    String tolProp = System.getProperty(PROP_TOLERANCE);
    if (tolProp != null && !tolProp.isBlank()) {
      try {
        tolerance = Double.parseDouble(tolProp);
      } catch (NumberFormatException ignored) {}
    }

    Map<String, List<SimulationReport.Row>> current = getRunRegistry();
    if (current.isEmpty()) {
      // In Gradle forked test execution, check if per-benchmark json reports were written to reportDir
      String dir = System.getProperty("rtp.simulation.reportDir");
      if (dir != null && !dir.isBlank()) {
        Path reportDirPath = Paths.get(dir);
        if (Files.isDirectory(reportDirPath)) {
          try (var stream = Files.list(reportDirPath)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json")
                && !p.getFileName().toString().equals("simulation-baseline.json")
                && !p.getFileName().toString().equals("simulation-results.json"))
            .forEach(p -> {
              try {
                String content = Files.readString(p, StandardCharsets.UTF_8);
                parseJsonReportIntoRegistry(content);
              } catch (IOException ignored) {}
            });
          } catch (IOException ignored) {}
        }
      }
      current = getRunRegistry();
    }

    if (current.isEmpty()) {
      System.out.println("[DEBUG_LOG] [SimulationBaseline] No benchmark rows registered for baseline evaluation.");
      return;
    }

    if (updateBaseline) {
      String json = generateBaselineJson(current);
      String targetPath = System.getProperty(PROP_BASELINE_PATH);
      Path outPath;
      if (targetPath != null && !targetPath.isBlank()) {
        outPath = Paths.get(targetPath);
      } else {
        // Try to locate project src/test/resources/benchmarks/simulation-baseline.json
        Path candidate = Paths.get("rtp-core/src/test/resources/benchmarks/simulation-baseline.json");
        if (Files.exists(candidate.getParent())) {
          outPath = candidate;
        } else {
          outPath = Paths.get("src/test/resources/benchmarks/simulation-baseline.json");
        }
      }
      try {
        if (outPath.getParent() != null) {
          Files.createDirectories(outPath.getParent());
        }
        Files.writeString(outPath, json, StandardCharsets.UTF_8);
        System.out.println("[DEBUG_LOG] [SimulationBaseline] Committed baseline updated successfully at " + outPath.toAbsolutePath());
      } catch (IOException e) {
        System.out.println("[DEBUG_LOG] [SimulationBaseline] Failed to update baseline JSON: " + e.getMessage());
      }
      return;
    }

    String baselineJson = loadBaselineJson();
    if (baselineJson == null || baselineJson.isBlank()) {
      System.out.println("[DEBUG_LOG] [SimulationBaseline] No baseline JSON found (run with -Drtp.simulation.updateBaseline=true to generate). Skipping regression check.");
      return;
    }

    Map<String, BaselineEntry> baseline = parseBaselineJson(baselineJson);
    System.out.println("[DEBUG_LOG] [SimulationBaseline] Loaded " + baseline.size() + " baseline entries. Checking regressions against tolerance " + tolerance + "%...");

    List<RegressionResult> regressions = checkRegressions(baseline, current, tolerance);

    if (regressions.isEmpty()) {
      System.out.println("[DEBUG_LOG] [SimulationBaseline] OK: Zero regressions detected across " + baseline.size() + " baseline metrics (tolerance: " + tolerance + "%).");
    } else {
      System.out.println("[DEBUG_LOG] [SimulationBaseline] " + regressions.size() + " potential regression(s) detected:");
      for (RegressionResult r : regressions) {
        System.out.println("[DEBUG_LOG] " + r.toLogString());
      }

      if (failOnRegression) {
        throw new AssertionError("Simulation benchmark regression gate failed! "
            + regressions.size() + " metrics exceeded " + tolerance + "% regression threshold.");
      } else {
        System.out.println("[DEBUG_LOG] [SimulationBaseline] WARN: Regressions detected above tolerance, but failOnRegression is disabled. Run with -Drtp.simulation.failOnRegression=true to enforce strict gating.");
      }
    }
  }
}
