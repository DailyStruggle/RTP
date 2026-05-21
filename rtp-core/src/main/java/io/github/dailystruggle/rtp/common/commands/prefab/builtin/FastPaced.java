package io.github.dailystruggle.rtp.common.commands.prefab.builtin;

import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;

import java.util.Map;

/**
 * Snappier-feeling teleports on a normal server: opposite-axis counterpart
 * to {@code lightweight}. Enlarges the per-region cache and shortens the
 * pulse period at the cost of more chunk activity. Distinct from
 * {@code high-performance} (dedicated hardware / large player counts);
 * {@code fast-paced} targets responsiveness on a typical server. Does not
 * touch {@code backlogCacheCap}.
 */
public final class FastPaced {

    public static final Prefab INSTANCE = new Prefab(
            "fast-paced",
            "menuPrefabFastPacedRow",
            "menuPrefabFastPacedHover",
            "Snappier teleports on a normal server (opposite axis to lightweight).",
            Map.of(
                    "period", 10,
                    "syncAllottedTime", 40
            ),
            Map.of("default", Map.of(
                    "cacheCap", 100,
                    "activeChunkCap", 20
            )),
            false
    );

    private FastPaced() {
    }
}
