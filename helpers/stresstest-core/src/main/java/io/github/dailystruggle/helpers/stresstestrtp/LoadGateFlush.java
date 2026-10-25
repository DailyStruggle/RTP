package io.github.dailystruggle.helpers.stresstestrtp;

import java.lang.reflect.Method;

/**
 * Asks LeafRTP to log its {@code [RTP][load-gate]} summaries at a phase boundary (ADR-110, ADR-122).
 */
final class LoadGateFlush {

    static final String GATE_CLASS = "io.github.dailystruggle.rtp.common.selection.region.LiveLoadGate";

    private static volatile Method flush;
    private static volatile boolean resolved;

    private LoadGateFlush() {}

    static int flush(String tag) {
        Method m = resolve(Sched.boundHost());
        if (m == null) return -1;
        try {
            Object n = m.invoke(null, tag);
            return n instanceof Integer i ? i : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Method resolve(StressHost host) {
        if (resolved) return flush;
        synchronized (LoadGateFlush.class) {
            if (resolved) return flush;
            Class<?> c = load(LoadGateFlush.class.getClassLoader());
            if (c == null && host != null) {
                try {
                    for (StressHost.LoadedPlugin p : host.loadedPlugins()) {
                        if (p == null || p.classLoader() == null) continue;
                        c = load(p.classLoader());
                        if (c != null) break;
                    }
                } catch (Throwable ignored) {
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
