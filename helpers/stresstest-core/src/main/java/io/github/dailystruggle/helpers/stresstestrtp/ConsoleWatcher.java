package io.github.dailystruggle.helpers.stresstestrtp;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Scans server console output for short-circuit failure messages emitted by
 * RTP-style plugins ("you're already teleporting!", "on cooldown", etc.) and
 * fail-attributes the most plausible in-flight attempt so its slot is freed
 * immediately rather than waiting for {@code attempt-timeout-ms}.
 *
 * <p>Also provides the shared JUL root-logger tap primitives ({@link #attachRootLoggerTap},
 * {@link #detachRootLoggerTap}, {@link #formatRecordMessage}, {@link #stripFormatting})
 * reused by {@code LeafSleuth}'s {@code LogAndStallSignatureOracle} ({@code leafsleuth-ADR-006}).
 *
 * @governedBy ADR-122
 * @governedBy leafsleuth-ADR-006
 * @trace REQ-SLEUTH-FUZZ-003
 */
public final class ConsoleWatcher {

    private static final Pattern SECTION_CODE = Pattern.compile("(?i)\\u00A7[0-9A-FK-OR]");
    private static final Pattern AMPERSAND_CODE = Pattern.compile("(?i)&[0-9A-FK-OR]");

    private final StressHost host;
    private final StressConfig config;
    private final Runner runner;

    private final List<Pattern> patterns = new ArrayList<>();
    private Handler handler;

    public ConsoleWatcher(StressHost host, StressConfig config, Runner runner) {
        this.host = host;
        this.config = config;
        this.runner = runner;
        loadPatterns();
    }

    /**
     * Strips Minecraft section ({@code §}) and ampersand ({@code &}) formatting codes from a log string.
     *
     * @param message raw log message
     * @return message with color/formatting codes removed (or empty string if null)
     */
    public static String stripFormatting(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        return AMPERSAND_CODE.matcher(SECTION_CODE.matcher(message).replaceAll("")).replaceAll("");
    }

    /**
     * Formats a JUL {@link LogRecord} message, substituting {@link MessageFormat} parameters when present.
     *
     * @param record JUL log record
     * @return formatted message or {@code null}
     */
    public static String formatRecordMessage(LogRecord record) {
        if (record == null) {
            return null;
        }
        String msg = record.getMessage();
        if (msg == null) {
            return null;
        }
        Object[] params = record.getParameters();
        if (params != null && params.length > 0 && (msg.contains("{0") || msg.contains("{1"))) {
            try {
                return MessageFormat.format(msg, params);
            } catch (IllegalArgumentException ignored) {
                return msg;
            }
        }
        return msg;
    }

    /**
     * Attaches a non-blocking JUL {@link Handler} to the root logger ({@code Logger.getLogger("")})
     * that filters out records below {@code minLevel} (unless {@link LogRecord#getThrown()} is non-null)
     * and suppresses records originating from any logger or bracket prefix matching {@code ignoredTokens}.
     *
     * @param minLevel       minimum log level to forward when no throwable is attached
     * @param ignoredTokens  logger-name or message substrings to ignore (preventing self-feedback loops)
     * @param recordConsumer callback invoked for each accepted {@link LogRecord}
     * @return installed {@link Handler}
     */
    public static Handler attachRootLoggerTap(
            Level minLevel,
            Collection<String> ignoredTokens,
            Consumer<LogRecord> recordConsumer
    ) {
        Objects.requireNonNull(recordConsumer, "recordConsumer");
        Level effectiveLevel = minLevel != null ? minLevel : Level.INFO;
        List<String> tokens = ignoredTokens == null ? List.of() : List.copyOf(ignoredTokens);
        Handler tap = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record == null) {
                    return;
                }
                Level recLevel = record.getLevel();
                if (record.getThrown() == null
                        && recLevel != null
                        && recLevel.intValue() < effectiveLevel.intValue()) {
                    return;
                }
                String src = record.getLoggerName();
                if (src != null) {
                    for (String tok : tokens) {
                        if (tok != null && !tok.isEmpty() && src.contains(tok)) {
                            return;
                        }
                    }
                }
                String msg = record.getMessage();
                if (msg != null) {
                    for (String tok : tokens) {
                        if (tok != null && !tok.isEmpty() && msg.contains("[" + tok + "]")) {
                            return;
                        }
                    }
                }
                recordConsumer.accept(record);
            }

            @Override
            public void flush() {
                // no-op: in-memory log tap
            }

            @Override
            public void close() throws SecurityException {
                // no-op: in-memory log tap
            }
        };
        tap.setLevel(effectiveLevel);
        Logger.getLogger("").addHandler(tap);
        return tap;
    }

    /**
     * Detaches a previously installed root-logger tap {@link Handler}.
     *
     * @param handler handler returned by {@link #attachRootLoggerTap}, or {@code null}
     */
    public static void detachRootLoggerTap(Handler handler) {
        if (handler == null) {
            return;
        }
        Logger.getLogger("").removeHandler(handler);
    }

    @SuppressWarnings("PMD.RegexCompiledPerCall")
    private void loadPatterns() {
        patterns.clear();
        List<String> raw = config.getStringList("console-fail-patterns");
        if (raw.isEmpty()) {
            raw = List.of(
                    "you're already teleporting",
                    "you are already teleporting",
                    "already teleporting",
                    "already rtp'?ing",
                    "whoops!",
                    "have some patience",
                    "on cooldown",
                    "please wait",
                    "cooling down",
                    "cool ?down",
                    "you must wait",
                    "wait \\d+",
                    "invalid command",
                    "no permission",
                    "you don't have permission",
                    "can'?t rtp",
                    "can not rtp",
                    "for another \\d+",
                    "sorry!"
            );
        }
        for (String s : raw) {
            try {
                patterns.add(Pattern.compile(s, Pattern.CASE_INSENSITIVE));
            } catch (Exception ignored) {
                host.logger().warning("StressTestRTP: ignoring invalid console-fail-pattern: " + s);
            }
        }
    }

    public void start() {
        if (handler != null) return;
        if (!config.getBoolean("console-fail-enabled", true)) return;
        List<String> ignored = new ArrayList<>();
        ignored.add("StressTestRTP");
        if (host != null && host.logger() != null && host.logger().getName() != null) {
            ignored.add(host.logger().getName());
        }
        handler = attachRootLoggerTap(Level.INFO, ignored, record -> {
            if (!runner.isRunning()) return;
            String msg = formatRecordMessage(record);
            if (msg == null || msg.isEmpty()) return;
            String stripped = stripFormatting(msg);
            boolean matched = false;
            for (Pattern p : patterns) {
                if (p.matcher(stripped).find()) { matched = true; break; }
            }
            if (!matched) return;
            handleMatch(stripped);
        });
    }

    public void stop() {
        if (handler == null) return;
        try {
            detachRootLoggerTap(handler);
        } catch (Throwable ignored) { /* best-effort */ }
        handler = null;
    }

    private void handleMatch(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        StressPlayer victim = null;
        for (StressPlayer p : host.onlinePlayers()) {
            String n = p.name();
            if (n != null && !n.isEmpty() && lower.contains(n.toLowerCase(Locale.ROOT))) {
                victim = p;
                break;
            }
        }
        if (victim != null) {
            runner.onConsoleFail(victim.uuid(), abbreviate(line));
            return;
        }
        runner.onConsoleFailFallback(abbreviate(line));
    }

    private static String abbreviate(String s) {
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() > 80) t = t.substring(0, 80);
        return "CONSOLE_FAIL:" + t;
    }
}
