package io.github.dailystruggle.rtp.common.bit;

import io.github.dailystruggle.bit.BitCli;
import io.github.dailystruggle.bit.BitSelector;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.action.ActionConfigLoader;
import io.github.dailystruggle.rtp.common.action.ActionManager;
import io.github.dailystruggle.rtp.common.action.ActionSimulator;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Headless command-line entry point for RTP PBIT checks (ADR-121).
 */
public final class RtpBitCli {
    private RtpBitCli() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, message -> RTP.log(Level.INFO, message),
                message -> RTP.log(Level.WARNING, message));
        if (exitCode != 0) System.exit(exitCode);
    }

    static int run(String[] args, Consumer<String> output, Consumer<String> errors) {
        File configDirectory = null;
        String actionId = null;
        int players = 2;
        List<String> script = List.of();
        boolean playersSpecified = false;
        boolean scriptSpecified = false;
        boolean json = false;
        boolean strict = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> {
                    if (++i >= args.length || args[i].isBlank() || configDirectory != null) {
                        return usage(errors, "--config requires one directory");
                    }
                    configDirectory = new File(args[i]);
                }
                case "--action" -> {
                    if (++i >= args.length || args[i].isBlank() || actionId != null) {
                        return usage(errors, "--action requires one action id");
                    }
                    actionId = args[i];
                }
                case "--players" -> {
                    playersSpecified = true;
                    if (++i >= args.length) return usage(errors, "--players requires a number from 1 to 16");
                    try {
                        players = Integer.parseInt(args[i]);
                    } catch (NumberFormatException failure) {
                        return usage(errors, "--players requires a number from 1 to 16");
                    }
                    if (players < 1 || players > 16) return usage(errors, "--players must be between 1 and 16");
                }
                case "--script" -> {
                    scriptSpecified = true;
                    if (++i >= args.length) return usage(errors, "--script requires comma-separated events");
                    script = parseScript(args[i]);
                    if (script == null) return usage(errors, "--script supports violate,die,expire");
                }
                case "--json" -> json = true;
                case "--strict" -> strict = true;
                default -> {
                    return usage(errors, "unknown argument: " + args[i]);
                }
            }
        }
        if (configDirectory == null || !configDirectory.isDirectory()) {
            return usage(errors, "--config must name an existing directory");
        }
        if (actionId == null && (playersSpecified || scriptSpecified)) {
            return usage(errors, "--players and --script require --action");
        }

        try {
            loadSeverityPolicy(configDirectory, strict);
        } catch (Exception failure) {
            errors.accept("Unable to read BIT configuration: " + failure.getMessage());
            return 2;
        }

        File actionsDirectory = actionDirectory(configDirectory);
        ActionManager actionManager = new ActionManager();
        ActionConfigLoader.loadActionsDirectory(actionsDirectory, actionManager);
        RtpBitComponents.registerDefaults();
        int bitExitCode = BitCli.run(RtpBit.RUNNER, BitSelector.parse("tier:pbit"), Runnable::run, json, output);
        if (actionId == null) return bitExitCode;

        var selectedDefinition = actionManager.getAction(actionId).orElse(null);
        if (selectedDefinition == null) {
            errors.accept("Unknown action definition: " + actionId);
            return Math.max(bitExitCode, 2);
        }
        ActionSimulator.Result result = ActionSimulator.simulate(selectedDefinition, players, script);
        result.lines().forEach(output);
        return bitExitCode;
    }

    private static void loadSeverityPolicy(File configDirectory, boolean strict) throws Exception {
        File bitConfig = new File(configDirectory, "advanced/bit.yml");
        if (!bitConfig.isFile()) {
            BitSeverityPolicy.load(strict, Map.of());
            return;
        }
        RtpYamlConfig yaml;
        try (Reader reader = new InputStreamReader(new FileInputStream(bitConfig), StandardCharsets.UTF_8)) {
            yaml = RtpYamlConfig.parse(reader);
        }
        Map<?, ?> values = yaml.getValues(false);
        Object configuredStrict = values.get("strictActions");
        boolean strictActions = strict || (configuredStrict instanceof Boolean value
                ? value : Boolean.parseBoolean(String.valueOf(configuredStrict)));
        Object rawOverrides = values.get("overrides");
        BitSeverityPolicy.load(strictActions, rawOverrides instanceof Map<?, ?> overrides ? overrides : Map.of());
    }

    private static File actionDirectory(File configDirectory) {
        if (new File(configDirectory, "config.yml").isFile()
                || new File(configDirectory, "definitions").isDirectory()) {
            return new File(configDirectory, "definitions/actions");
        }
        return configDirectory;
    }

    private static int usage(Consumer<String> errors, String message) {
        errors.accept(message);
        errors.accept("Usage: RtpBitCli --config <directory> [--json] [--strict]"
                + " [--action <id> [--players <n>] [--script <violate,die,expire>]]");
        return 2;
    }

    private static List<String> parseScript(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> parsed = new ArrayList<>();
        for (String token : value.split(",", -1)) {
            String event = token.trim().toLowerCase(Locale.ROOT);
            if (!List.of("violate", "die", "expire").contains(event)) return null;
            parsed.add(event);
        }
        return List.copyOf(parsed);
    }
}
