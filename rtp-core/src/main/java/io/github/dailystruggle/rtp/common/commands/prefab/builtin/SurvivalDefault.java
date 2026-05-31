package io.github.dailystruggle.rtp.common.commands.prefab.builtin;

import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;

import java.util.Map;

/**
 * Identity overlay - documents the current shipped defaults and serves as a
 * "reset to defaults" target. Both overlay maps are intentionally empty;
 * {@code PrefabIdentityOverlayTest} (session 2) asserts that applying this
 * prefab to a tree of the on-jar {@code performance.yml} +
 * {@code regions/default.yml} produces an identity transform, guarding
 * against silent default drift.
 */
public final class SurvivalDefault {

    public static final Prefab INSTANCE = new Prefab(
            "survival-default",
            "menuPrefabSurvivalDefaultRow",
            "menuPrefabSurvivalDefaultHover",
            "Reset to the shipped defaults (identity overlay).",
            Map.of(),
            Map.of(),
            Map.of(),
            false
    );

    private SurvivalDefault() {
    }
}
