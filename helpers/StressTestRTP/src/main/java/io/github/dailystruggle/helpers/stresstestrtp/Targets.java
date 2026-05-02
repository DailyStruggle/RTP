package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

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
        public Entry(String label, String template) {
            this.label = label;
            this.template = template;
        }
        public String render(String playerName) {
            return template.replace("{player}", playerName);
        }
    }

    private Targets() {}

    /** Returns the configured target commands in declaration order. Never empty. */
    public static List<Entry> load(FileConfiguration cfg) {
        return load(cfg, null);
    }

    /**
     * Returns the configured target commands in declaration order. The
     * {@code logger} parameter is accepted for backwards compatibility with
     * earlier filtering implementations but is no longer used here — target
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
                    if (!cmd.isEmpty()) out.add(new Entry(label, cmd));
                } else if (o instanceof java.util.Map<?, ?> m) {
                    Object lbl = m.get("label");
                    Object cmd = m.get("command");
                    if (cmd != null && !cmd.toString().isEmpty()) {
                        out.add(new Entry(lbl == null ? "target" + idx : lbl.toString(), cmd.toString()));
                    }
                } else if (o instanceof String s && !s.isEmpty()) {
                    // Bare-string entry: derive a label from the first whitespace-separated token.
                    String label = s.split("\\s+", 2)[0];
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

    /** Compact human-readable description for status / summary output. */
    public static String describe(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(entries.get(i).label).append("=`").append(entries.get(i).template).append('`');
        }
        return sb.toString();
    }
}
