package io.github.dailystruggle.rtp.fabric.player;

import java.util.Set;

/**
 * Default-permission table mirroring {@code plugin.yml} {@code permissions:} defaults
 * for the Fabric platform, used when no fabric-permissions-api implementer
 * (LuckPerms-Fabric, Cyan, Ledger, ...) is registered.
 *
 * <p>On Bukkit/Spigot/Paper/Folia, the server reads {@code plugin.yml} and applies
 * each node's {@code default:} value (true / false / op) automatically. Fabric has
 * no equivalent descriptor - without this table, every {@code hasPermission}
 * call on a permsless server fell through to the ops.json check, which meant
 * baseline player permissions like {@code rtp.see} and {@code rtp.use} were
 * silently denied to non-ops, breaking {@code /rtp} on a vanilla-permissions
 * Fabric install.
 *
 * <p>Verdict semantics ({@link Verdict}):
 * <ul>
 *   <li>{@link Verdict#TRUE}  - granted to everyone (matches {@code default: true}).</li>
 *   <li>{@link Verdict#FALSE} - denied to everyone, including ops (matches {@code default: false}).</li>
 *   <li>{@link Verdict#OP}    - granted to ops only (matches {@code default: op}); caller
 *       falls through to the ops.json scan.</li>
 * </ul>
 *
 * <p>Unknown nodes default to {@link Verdict#OP} so addon-defined or wildcard-expanded
 * children (e.g. {@code rtp.worlds.<world>}) follow the same op-only convention as
 * their parent {@code rtp.worlds.*}.
 *
 * <p>Source of truth: {@code rtp-plugin/src/main/resources/plugin.yml}. When a new
 * permission is added there, mirror it here.
 */
public final class FabricDefaultPermissions {

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

    private FabricDefaultPermissions() {}

    /**
     * Resolve the {@code plugin.yml} default for {@code node}. Never returns null.
     * Unknown nodes resolve to {@link Verdict#OP}.
     */
    public static Verdict resolve(String node) {
        if (node == null || node.isEmpty()) return Verdict.TRUE;
        if (DEFAULT_TRUE.contains(node)) return Verdict.TRUE;
        if (DEFAULT_FALSE.contains(node)) return Verdict.FALSE;
        return Verdict.OP;
    }

    /**
     * Read-only view of the nodes granted to everyone by default
     * (mirrors {@code plugin.yml} entries with {@code default: true}).
     * Consumed by the effective-permissions resolver (rtp-fabric-ADR-011).
     */
    public static Set<String> defaultTrue() {
        return DEFAULT_TRUE;
    }
}
