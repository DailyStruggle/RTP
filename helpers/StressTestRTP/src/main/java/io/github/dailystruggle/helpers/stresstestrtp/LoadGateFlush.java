package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Asks LeafRTP to log its {@code [RTP][load-gate]} summaries at a phase boundary (ADR-110).
 *
 * <p>The summary is rate-limited and only fires from gate traffic, so short or quiet phases
 * otherwise log nothing. Resolved reflectively: the harness has no compile dependency on
 * rtp-core, and other arms have no such class, so every failure is a silent no-op.
 */
final class LoadGateFlush {

    static final String GATE_CLASS = "io.github.dailystruggle.rtp.common.selection.region.LiveLoadGate";

    private static volatile Method flush;
    private static volatile boolean resolved;

    private LoadGateFlush() {}

    /** Logs every gate's summary tagged {@code tag}; returns gates logged, or -1 if unavailable. */
    static int flush(String tag) {
        Method m = resolve();
        if (m == null) return -1;
        try {
            Object n = m.invoke(null, tag);
            return n instanceof Integer i ? i : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Method resolve() {
        if (resolved) return flush;
        synchronized (LoadGateFlush.class) {
            if (resolved) return flush;
            Class<?> c = load(LoadGateFlush.class.getClassLoader());
            if (c == null) {
                try {
                    for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                        c = load(p.getClass().getClassLoader());
                        if (c != null) break;
                    }
                } catch (Throwable ignored) {
                    // No server (unit tests) or plugin manager unavailable.
                }
            }
            try {
                flush = c == null ? null : c.getMethod("flushSummaries", String.class);
            } catch (Throwable t) {
                flush = null;
            }
            resolved = true;
            return flush;
        }
    }

    private static Class<?> load(ClassLoader cl) {
        try {
            return Class.forName(GATE_CLASS, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }
}
