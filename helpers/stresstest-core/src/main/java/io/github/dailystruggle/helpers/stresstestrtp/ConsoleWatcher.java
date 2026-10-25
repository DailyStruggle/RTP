package io.github.dailystruggle.helpers.stresstestrtp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Scans server console output for short-circuit failure messages emitted by
 * RTP-style plugins ("you're already teleporting!", "on cooldown", etc.) and
 * fail-attributes the most plausible in-flight attempt so its slot is freed
 * immediately rather than waiting for {@code attempt-timeout-ms}.
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
        handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record == null) return;
                if (!runner.isRunning()) return;
                String msg = record.getMessage();
                if (msg == null || msg.isEmpty()) return;
                String src = record.getLoggerName();
                if (src != null && (src.contains("StressTestRTP")
                        || src.equals(host.logger().getName()))) {
                    return;
                }
                if (msg.contains("[StressTestRTP]")) return;
                String stripped = AMPERSAND_CODE.matcher(
                        SECTION_CODE.matcher(msg).replaceAll("")).replaceAll("");
                boolean matched = false;
                for (Pattern p : patterns) {
                    if (p.matcher(stripped).find()) { matched = true; break; }
                }
                if (!matched) return;
                handleMatch(stripped);
            }
            @Override public void flush() {}
            @Override public void close() throws SecurityException {}
        };
        Logger root = Logger.getLogger("");
        root.addHandler(handler);
    }

    public void stop() {
        if (handler == null) return;
        try {
            Logger.getLogger("").removeHandler(handler);
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
