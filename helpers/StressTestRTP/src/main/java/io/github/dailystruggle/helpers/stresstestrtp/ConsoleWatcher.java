package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

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
 *
 * <p>Why this exists: target plugins reject duplicate / mid-flight / cooldown
 * dispatches by sending the player a chat message — which goes through
 * Bukkit's logger to console — but they do <strong>not</strong> fire
 * {@link org.bukkit.event.player.PlayerTeleportEvent}. From {@link Runner}'s
 * point of view the dispatch then sits "in flight" until the timeout reaper
 * runs (default 5 s), starving the concurrency cap and producing the symptom
 * "a dozen teleports then ages of nothing" that the user reported.
 *
 * <p>Strategy: install a {@link Handler} on the root logger, format each
 * record, and test it against a configurable list of regex patterns. On a
 * match, scan the names of currently-expecting players in {@link TeleportProbe}
 * and pick the one whose name appears in the line. If exactly zero player
 * names match (e.g. a plain "Invalid command" with no player echoed), fall
 * back to attributing the most-recently dispatched in-flight player so the
 * common "rate-limited reply" case is still handled.
 *
 * <p>Threading: the logging handler is invoked on whatever thread emitted the
 * log record (often the main / region thread). All work performed here is
 * map manipulation + a config read, both safe off-thread.
 */
public final class ConsoleWatcher {

    private final Plugin plugin;
    private final FileConfiguration config;
    private final Runner runner;

    private final List<Pattern> patterns = new ArrayList<>();
    private Handler handler;

    public ConsoleWatcher(Plugin plugin, FileConfiguration config, Runner runner) {
        this.plugin = plugin;
        this.config = config;
        this.runner = runner;
        loadPatterns();
    }

    private void loadPatterns() {
        patterns.clear();
        List<String> raw = config.getStringList("console-fail-patterns");
        if (raw.isEmpty()) {
            // Defaults cover the common rejection messages emitted by RTP,
            // RTP-Pro, BetterRTP, EssentialsX, HuskHomes, and similar.
            // All are matched case-insensitively against the formatted log line.
            raw = List.of(
                    "you're already teleporting",
                    "you are already teleporting",
                    "already teleporting",
                    // BetterRTP in-progress: "Whoops! Looks like you are already rtp'ing, have some patience!"
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
                    // BetterRTP cooldown: "Sorry! You can't rtp for another 6 Min(s) and 32 Secs!"
                    "can'?t rtp",
                    "can not rtp",
                    "for another \\d+",
                    // Generic "Sorry! ..." rejection prelude used by BetterRTP and others
                    "sorry!"
            );
        }
        for (String s : raw) {
            try {
                patterns.add(Pattern.compile(s, Pattern.CASE_INSENSITIVE));
            } catch (Exception ignored) {
                plugin.getLogger().warning("StressTestRTP: ignoring invalid console-fail-pattern: " + s);
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
                // Self-feedback guard: ignore log records emitted by this
                // plugin itself. Without this guard, the recorder's own
                // failure-completion log line ("[StressTestRTP] ... ->
                // <player> CONSOLE_FAIL:you're already teleporting ...")
                // bubbles up through the root logger, re-matches the
                // "already teleporting" pattern, and re-enters handleMatch
                // → onConsoleFail → recorder.onComplete → logCompletion,
                // looping until every in-flight slot is consumed and then
                // hammering `lastDispatched` indefinitely. The outward
                // symptom is an indefinite "freeze" with the CPU pegged
                // (fans spinning) because the runner async tick keeps
                // dispatching while the handler thread keeps recursing
                // through pattern matching and CSV writes.
                String src = record.getLoggerName();
                if (src != null && (src.contains("StressTestRTP")
                        || src.equals(plugin.getLogger().getName()))) {
                    return;
                }
                if (msg.contains("[StressTestRTP]")) return;
                // Strip basic Minecraft color/format codes so patterns match
                // regardless of whether the target plugin coloured its reply.
                String stripped = msg.replaceAll("(?i)\\u00A7[0-9A-FK-OR]", "")
                                     .replaceAll("(?i)&[0-9A-FK-OR]", "");
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
        // Attach to the root logger so we see records from any plugin's
        // own Logger (their parents chain up to root). Bukkit's own logger
        // is also a child of root.
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
        // Try to identify the player by name in the message.
        Player victim = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            String n = p.getName();
            if (n != null && !n.isEmpty() && lower.contains(n.toLowerCase(Locale.ROOT))) {
                victim = p;
                break;
            }
        }
        if (victim != null) {
            runner.onConsoleFail(victim.getUniqueId(), abbreviate(line));
            return;
        }
        // Fallback: attribute to the most-recently dispatched in-flight player.
        runner.onConsoleFailFallback(abbreviate(line));
    }

    private static String abbreviate(String s) {
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() > 80) t = t.substring(0, 80);
        return "CONSOLE_FAIL:" + t;
    }
}
