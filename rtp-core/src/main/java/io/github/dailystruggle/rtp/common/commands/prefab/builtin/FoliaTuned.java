package io.github.dailystruggle.rtp.common.commands.prefab.builtin;

import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;

import java.util.Map;

/**
 * Folia regional scheduler tuning: many small regions instead of one big
 * tick budget. Pulses faster per-region-thread and caps concurrent
 * region-owned chunk tickets. Does not touch {@code backlogCacheCap}.
 */
public final class FoliaTuned {

    public static final Prefab INSTANCE = new Prefab(
            "folia-tuned",
            "menuPrefabFoliaTunedRow",
            "menuPrefabFoliaTunedHover",
            "Folia regional scheduler: faster per-region pulses, fewer concurrent chunk tickets.",
            Map.of(
                    "period", 5,
                    "syncAllottedTime", 10,
                    "asyncAllottedTime", 25,
                    "minTPS", 19.0
            ),
            Map.of("default", Map.of(
                    "cacheCap", 15,
                    "activeChunkCap", 4
            )),
            false
    );

    private FoliaTuned() {
    }
}
