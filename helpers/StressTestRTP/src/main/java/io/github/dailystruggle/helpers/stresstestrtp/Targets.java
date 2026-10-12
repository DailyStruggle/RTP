package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Resolves the configured target commands. Multiple co-installed RTP-style
 * plugins can be benchmarked in a single run by listing them under
 * {@code target-commands} (each entry has a {@code label} and a
 * {@code command} template with {@code {player}} substitution). The legacy
 * scalar {@code target-command} key is honored as a single-entry fallback
 * with label {@code "default"}.
 *
 * <p>Bukkit allows invoking a specific plugin's command by prefixing it with
 * the plugin name (e.g. {@code rtp:rtp player:Steve} vs.
 * {@code betterrtp:rtp tp Steve}), so the same JAR can drive every RTP-style
 * plugin installed on the server in one run.</p>
 */
public final class Targets {

    public static final class Entry {
        public final String label;
        public final String template;
        /** Per-target sender override: {@code Boolean.TRUE} player,
         *  {@code Boolean.FALSE} console, {@code null} the global
         *  {@code dispatch-as-player} setting. A plugin whose player form
         *  silently refuses (BetterRTP on 26.x) can then be driven by its
         *  console form without moving every other arm off the player path. */
        public final Boolean asPlayer;
        public Entry(String label, String template) {
            this(label, template, null);
        }
        public Entry(String label, String template, Boolean asPlayer) {
            this.label = label;
            this.template = template;
            this.asPlayer = asPlayer;
        }
        /** Effective sender for this target given the global default. */
        public boolean dispatchAsPlayer(boolean globalDefault) {
            return asPlayer == null ? globalDefault : asPlayer;
        }
        public String render(String playerName) {
            return template.replace("{player}", playerName);
        }
    }

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private Targets() {}

    /** Returns the configured target commands in declaration order. Never empty. */
    public static List<Entry> load(FileConfiguration cfg) {
        return load(cfg, null);
    }

    /**
     * Returns the configured target commands in declaration order. The
     * {@code logger} parameter is accepted for backwards compatibility with
     * earlier filtering implementations but is no longer used here - target
     * pruning has moved to {@link Runner}'s post-warm-up step (a target with
     * zero successful warm-up attempts is dropped before measurement starts).
     * Trial-and-error against the live server is more reliable than guessing
     * which plugin owns a {@code pluginName:} prefix and whether its
     * {@code plugin.yml} {@code name:} casing matches. Never empty (falls back
     * to the legacy single-target form).
     */
    public static List<Entry> load(FileConfiguration cfg, @SuppressWarnings("unused") Logger logger) {
        List<Entry> out = new ArrayList<>();
        Object raw = cfg.get("target-commands");
        if (raw instanceof List<?> list) {
            int idx = 0;
            for (Object o : list) {
                idx++;
                if (o instanceof ConfigurationSection sec) {
                    String label = sec.getString("label", "target" + idx);
                    String cmd = sec.getString("command", "");
                    if (!cmd.isEmpty()) out.add(new Entry(label, cmd, parseSender(sec.getString("sender"))));
                } else if (o instanceof java.util.Map<?, ?> m) {
                    Object lbl = m.get("label");
                    Object cmd = m.get("command");
                    if (cmd != null && !cmd.toString().isEmpty()) {
                        Object sender = m.get("sender");
                        out.add(new Entry(lbl == null ? "target" + idx : lbl.toString(), cmd.toString(),
                                parseSender(sender == null ? null : sender.toString())));
                    }
                } else if (o instanceof String s && !s.isEmpty()) {
                    // Bare-string entry: derive a label from the first whitespace-separated token.
                    String label = WHITESPACE.split(s, 2)[0];
                    out.add(new Entry(label, s));
                }
            }
        }
        if (out.isEmpty()) {
            // Back-compat: single scalar key.
            String legacy = cfg.getString("target-command", "rtp player:{player}");
            out.add(new Entry("default", legacy));
        }
        return Collections.unmodifiableList(out);
    }

    /** {@code player} / {@code console} (case-insensitive); anything else defers to the global setting. */
    static Boolean parseSender(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.equals("player")) return Boolean.TRUE;
        if (s.equals("console")) return Boolean.FALSE;
        return null;
    }

    /** Compact human-readable description for status / summary output. */
    public static String describe(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(", ");
            Entry e = entries.get(i);
            sb.append(e.label).append("=`").append(e.template).append('`');
            if (e.asPlayer != null) sb.append(e.asPlayer ? " (player)" : " (console)");
        }
        return sb.toString();
    }
}
