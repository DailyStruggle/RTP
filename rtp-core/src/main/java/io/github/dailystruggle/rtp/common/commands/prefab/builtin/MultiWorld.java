package io.github.dailystruggle.rtp.common.commands.prefab.builtin;

import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;

import java.util.Map;

/**
 * One region per world. The only prefab that performs runtime enumeration:
 * sets {@code expandPerWorld = true}, so {@code PrefabApplier} (session 2)
 * delegates to {@code MultiWorldExpander} (session 3) to synthesise
 * {@code regions.<worldName>} entries cloning {@code regions/default.yml}
 * with {@code world: "<worldName>"}. Existing per-world regions are left
 * untouched (idempotent merge). Does not touch {@code backlogCacheCap}.
 */
public final class MultiWorld {

    public static final Prefab INSTANCE = new Prefab(
            "multi-world",
            "menuPrefabMultiWorldRow",
            "menuPrefabMultiWorldHover",
            "Synthesise one region per world from the current default.",
            Map.of(),
            Map.of(),
            Map.of(),
            true
    );

    private MultiWorld() {
    }
}
