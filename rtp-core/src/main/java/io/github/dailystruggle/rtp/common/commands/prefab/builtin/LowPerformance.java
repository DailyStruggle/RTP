package io.github.dailystruggle.rtp.common.commands.prefab.builtin;

import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;

import java.util.Map;

/**
 * Underpowered hosts and shared-CPU VPSes. Longer pulse period, smaller
 * per-region cache, login cache off. Does not touch {@code backlogCacheCap}
 * (pro-vs-lite assembly-time knob; see {@link Prefab} class javadoc).
 */
public final class LowPerformance {

    public static final Prefab INSTANCE = new Prefab(
            "low-performance",
            "menuPrefabLowPerformanceRow",
            "menuPrefabLowPerformanceHover",
            "Underpowered hosts / shared-CPU VPSes: longer pulse, smaller caches, login cache off.",
            Map.of(
                    "period", 60,
                    "syncAllottedTime", 20,
                    "asyncAllottedTime", 30,
                    "minTPS", 19.5,
                    "loginCacheEnabled", false
            ),
            Map.of("default", Map.of(
                    "cacheCap", 10,
                    "activeChunkCap", 4
            )),
            false
    );

    private LowPerformance() {
    }
}
