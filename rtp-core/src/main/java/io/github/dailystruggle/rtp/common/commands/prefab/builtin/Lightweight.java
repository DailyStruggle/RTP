package io.github.dailystruggle.rtp.common.commands.prefab.builtin;

import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;

import java.util.Map;

/**
 * Shrinks region cache and chunk-ticket footprint for small servers. This is
 * the pro-portable subset of {@code rtp-plugin/src/lite/resources/regions/default.yml};
 * the lite assembly continues to ship {@code backlogCacheCap: 0} at assembly
 * time, and the runtime prefab does not duplicate that. See ADR-024.
 */
public final class Lightweight {

    public static final Prefab INSTANCE = new Prefab(
            "lightweight",
            "menuPrefabLightweightRow",
            "menuPrefabLightweightHover",
            "Small servers: shrink region cache + chunk-ticket footprint.",
            Map.of(),
            Map.of("default", Map.of(
                    "cacheCap", 25,
                    "activeChunkCap", 6
            )),
            false
    );

    private Lightweight() {
    }
}
