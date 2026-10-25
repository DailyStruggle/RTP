package io.github.dailystruggle.helpers.stresstestrtp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Resolves the configured target commands (ADR-122 Decision 1).
 */
public final class Targets {

    public static final class Entry {
        public final String label;
        public final String template;
        public final Boolean asPlayer;
        public Entry(String label, String template) {
            this(label, template, null);
        }
        public Entry(String label, String template, Boolean asPlayer) {
            this.label = label;
            this.template = template;
            this.asPlayer = asPlayer;
        }
        public boolean dispatchAsPlayer(boolean globalDefault) {
            return asPlayer == null ? globalDefault : asPlayer;
        }
        public String render(String playerName) {
            return template.replace("{player}", playerName);
        }
    }

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private Targets() {}

    public static List<Entry> load(StressConfig cfg) {
        return load(cfg, null);
    }

    public static List<Entry> load(StressConfig cfg, @SuppressWarnings("unused") Logger logger) {
        List<Entry> out = new ArrayList<>();
        Object raw = cfg != null ? cfg.get("target-commands") : null;
        if (raw instanceof List<?> list) {
            int idx = 0;
            for (Object o : list) {
                idx++;
                if (o instanceof StressConfig sec) {
                    String label = sec.getString("label", "target" + idx);
                    String cmd = sec.getString("command", "");
                    if (!cmd.isEmpty()) out.add(new Entry(label, cmd, parseSender(sec.getString("sender")).orElse(null)));
                } else if (o instanceof java.util.Map<?, ?> m) {
                    Object lbl = m.get("label");
                    Object cmd = m.get("command");
                    if (cmd != null && !cmd.toString().isEmpty()) {
                        Object sender = m.get("sender");
                        out.add(new Entry(lbl == null ? "target" + idx : lbl.toString(), cmd.toString(),
                                parseSender(sender == null ? null : sender.toString()).orElse(null)));
                    }
                } else if (o instanceof String s && !s.isEmpty()) {
                    String label = WHITESPACE.split(s, 2)[0];
                    out.add(new Entry(label, s));
                }
            }
        }
        if (out.isEmpty()) {
            String legacy = cfg != null ? cfg.getString("target-command", "rtp player:{player}") : "rtp player:{player}";
            out.add(new Entry("default", legacy));
        }
        return Collections.unmodifiableList(out);
    }

    static java.util.Optional<Boolean> parseSender(String raw) {
        if (raw == null) return java.util.Optional.empty();
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.equals("player")) return java.util.Optional.of(Boolean.TRUE);
        if (s.equals("console")) return java.util.Optional.of(Boolean.FALSE);
        return java.util.Optional.empty();
    }

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
