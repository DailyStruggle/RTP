package io.github.dailystruggle.rtp.fabric.unobf.player;

import java.util.Set;

/**
 * Unobf mirror of {@code io.github.dailystruggle.rtp.fabric.player.FabricDefaultPermissions}.
 * Kept duplicated because {@code rtp-fabric-common-unobf} does not depend on
 * {@code rtp-fabric-common} (separate Loom mapping classpaths).
 *
 * <p>Source of truth: {@code rtp-plugin/src/main/resources/plugin.yml}. Keep in
 * sync with the obf-side {@code FabricDefaultPermissions} when permissions change.
 */
public final class FabricDefaultPermissionsUnobf {

    public enum Verdict { TRUE, FALSE, OP }

    private static final Set<String> DEFAULT_TRUE = Set.of(
            "rtp.see",
            "rtp.use"
    );

    private static final Set<String> DEFAULT_FALSE = Set.of(
            "rtp.delay",
            "rtp.cooldown",
            "rtp.personalqueue",
            "rtp.onevent.join",
            "rtp.onevent.firstjoin",
            "rtp.onevent.respawn",
            "rtp.onevent.changeworld",
            "rtp.onevent.move",
            "rtp.onevent.teleport",
            "rtp.onevent.*"
    );

    private FabricDefaultPermissionsUnobf() {}

    public static Verdict resolve(String node) {
        if (node == null || node.isEmpty()) return Verdict.TRUE;
        if (DEFAULT_TRUE.contains(node)) return Verdict.TRUE;
        if (DEFAULT_FALSE.contains(node)) return Verdict.FALSE;
        return Verdict.OP;
    }
}
