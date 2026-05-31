package io.github.dailystruggle.rtp.common.commands.prefab.builtin;

import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;

import java.util.Map;

/**
 * Dedicated hardware and large player counts. Short pulse period, large
 * per-region cache, login cache + post-teleport queueing on. Does not touch
 * {@code backlogCacheCap}.
 */
public final class HighPerformance {

    public static final Prefab INSTANCE = new Prefab(
            "high-performance",
            "menuPrefabHighPerformanceRow",
            "menuPrefabHighPerformanceHover",
            "Dedicated hardware / large player counts: short pulse, large caches, login cache on.",
            Map.of(
                    "period", 10,
                    "syncAllottedTime", 50,
                    "asyncAllottedTime", 50,
                    "loginCacheEnabled", true,
                    "postTeleportQueueing", true
            ),
            Map.of(),
            Map.of("default", Map.of(
                    "cacheCap", 200,
                    "activeChunkCap", 40
            )),
            false
    );

    private HighPerformance() {
    }
}
