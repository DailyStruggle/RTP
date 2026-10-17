package io.github.dailystruggle.rtp.common.commands.prefab;

/**
 * Permission node shared by the {@code prefab} verb and every sub-verb. Kept out of
 * {@code PrefabCommand} so the sub-verbs do not reference their parent (no class cycle).
 */
public final class PrefabPermission {

    public static final String PERMISSION = "rtp.admin.prefab";

    private PrefabPermission() {
    }
}
