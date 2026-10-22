package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** SAFE initiated test of bundled action definitions through the real parser, gates, and session. */
public final class ActionEngineSelfTest {
  private static final String COMPONENT_ID = "action-engine-selftest";
  private static final String RESOURCE = "bit/action-engine-selftest.yml";
  private static final AtomicReference<ClassLoader> RESOURCE_LOADER = new AtomicReference<>();

  public static final BitCode ACTION_SELFTEST_MISMATCH = RtpBitCodes.ACTION.register(2107,
      "ACTION_SELFTEST_MISMATCH", Level.ERROR, BitTier.PBIT, "action", "",
      "Make the bundled expected commands match the real action parser, gates, and session output.");
  public static final BitCode ACTION_SELFTEST_CASE_INVALID = RtpBitCodes.ACTION.register(2108,
      "ACTION_SELFTEST_CASE_INVALID", Level.ERROR, BitTier.PBIT, "action", "",
      "Repair the self-test case structure and use a supported deterministic simulation script.");

  private static final BitComponent COMPONENT = new BitComponent(
      COMPONENT_ID, "action", BitTier.PBIT, BitSideEffect.SAFE, 10_000, ActionEngineSelfTest::checkBundled);

  private ActionEngineSelfTest() {}

  /** Registers the bundled action verification component using the addon resource loader. */
  public static void register(ClassLoader classLoader) {
    if (classLoader == null) throw new IllegalArgumentException("classLoader must not be null");
    RESOURCE_LOADER.set(classLoader);
    RtpBit.RUNNER.register(COMPONENT);
  }

  /** Removes the component and releases the addon class loader on unload. */
  public static void unregister() {
    RtpBit.RUNNER.unregister(COMPONENT_ID);
    RESOURCE_LOADER.set(null);
  }

  /** Runs a supplied case document; exposed for focused regression tests. */
  public static BitReport run(InputStream input, String source) {
    if (input == null) {
      return invalid(source, "", "Self-test resource could not be opened.");
    }
    try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
      RtpYamlConfig yaml = RtpYamlConfig.parse(reader);
      RtpYamlSection cases = yaml.getConfigurationSection("cases");
      if (cases == null || cases.getKeys(false).isEmpty()) {
        return invalid(source, "cases", "The case document must contain a non-empty cases mapping.");
      }

      List<BitFinding> findings = new ArrayList<>();
      for (String caseName : cases.getKeys(false)) {
        String path = "cases." + caseName;
        RtpYamlSection testCase = cases.getConfigurationSection(caseName);
        if (caseName.isEmpty() || testCase == null) {
          findings.add(invalidFinding(source, path, "Each case needs a name and a mapping value."));
          continue;
        }
        runCase(source, caseName, testCase, findings);
      }
      return findings.isEmpty()
          ? new BitReport(BitReport.CURRENT_SCHEMA, BitHealth.HEALTHY, List.of())
          : new BitReport(BitReport.CURRENT_SCHEMA, BitHealth.FAILED, findings);
    } catch (IOException | RuntimeException failure) {
      return invalid(source, "", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
    }
  }

  private static BitReport checkBundled() {
    ClassLoader loader = RESOURCE_LOADER.get();
    if (loader == null) loader = ActionEngineSelfTest.class.getClassLoader();
    try (InputStream input = loader.getResourceAsStream(RESOURCE)) {
      return run(input, RESOURCE);
    } catch (IOException failure) {
      return invalid(RESOURCE, "", failure.getMessage() == null ? "Unable to close the self-test resource." : failure.getMessage());
    }
  }

  private static void runCase(String source, String caseName, RtpYamlSection caseSection, List<BitFinding> findings) {
    String path = "cases." + caseName;
    Map<String, Object> testCase = caseSection.getValues(false);
    Object definitionValue = testCase.get("definition");
    Object expectedValue = testCase.get("expected");
    int players = parsePlayers(testCase.get("players"));
    List<String> script = parseStringList(testCase.get("script"));
    List<String> expected = parseStringList(expectedValue);
    if (!(definitionValue instanceof RtpYamlSection definitionSection)
        || players < 1 || players > 16
        || expectedValue == null
        || expected == null
        || script == null) {
      findings.add(invalidFinding(source, path,
          "Case requires a definition mapping, players in 1..16, and string-list script/expected values."));
      return;
    }

    try {
      ActionDefinition definition = ActionConfigLoader.parseDefinition(caseName, definitionSection);
      ActionSimulator.Result result = ActionSimulator.simulate(definition, players, script);
      if (!result.commands().equals(expected)) {
        findings.add(BitFinding.of(ACTION_SELFTEST_MISMATCH, source, path,
            "Recorded action commands differ from the bundled expectation. Expected " + expected
                + " but recorded " + result.commands() + ".",
            "expected commands equal recorded commands", "Update the action definition or expected output."));
      }
    } catch (RuntimeException failure) {
      findings.add(invalidFinding(source, path,
          failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
    }
  }

  private static int parsePlayers(Object value) {
    if (value == null) return 2;
    if (value instanceof Number number) {
      double parsed = number.doubleValue();
      if (!Double.isFinite(parsed) || parsed != Math.rint(parsed) || parsed < 1 || parsed > 16) return -1;
      return (int) parsed;
    }
    try {
      return Integer.parseInt(value.toString().trim());
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }

  private static List<String> parseStringList(Object value) {
    if (value == null) return List.of();
    if (value instanceof String string) {
      if (string.isBlank()) return List.of();
      return List.of(string.split(",", -1)).stream().map(String::trim).toList();
    }
    if (!(value instanceof List<?> values)) return null;
    List<String> result = new ArrayList<>(values.size());
    for (Object item : values) {
      if (!(item instanceof String string)) return null;
      result.add(string);
    }
    return List.copyOf(result);
  }

  private static BitReport invalid(String source, String path, String message) {
    return new BitReport(BitReport.CURRENT_SCHEMA, BitHealth.FAILED,
        List.of(invalidFinding(source, path, message)));
  }

  private static BitFinding invalidFinding(String source, String path, String message) {
    return BitFinding.of(ACTION_SELFTEST_CASE_INVALID, Level.ERROR, source, path, message,
        "valid action self-test case", ACTION_SELFTEST_CASE_INVALID.remediation());
  }
}
