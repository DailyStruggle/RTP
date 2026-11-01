package io.github.dailystruggle.rtp.neoforge.player;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Closed-namespace constants for the {@code rtp.onevent.*} permission family
 * (NeoForge analogue of {@code FabricOnEventPermissions}).
 *
 * <p>Single source of truth for the six declared on-event nodes
 * ({@code join}, {@code firstjoin}, {@code respawn}, {@code changeworld},
 * {@code move}, {@code teleport}) and their fully-qualified node strings.
 * Mirrors {@code plugin.yml}; do not reorder casually because the
 * {@code OP_GRANTS} set is iterated in declaration order by the
 * effective-permissions resolver (callers should treat ordering as
 * informational only).
 *
 * <p>See {@code rtp-fabric-ADR-011-effective-permissions-enumeration.md} for the
 * cross-platform rationale.
 */
public final class NeoForgeOnEventPermissions {

    public static final String PREFIX = "rtp.onevent.";

    /** Tails declared in {@code plugin.yml} under {@code rtp.onevent.*}. */
    public static final List<String> EVENT_NAMES = List.of(
            "join",
            "firstjoin",
            "respawn",
            "changeworld",
            "move",
            "teleport"
    );

    /** Fully-qualified {@code rtp.onevent.<event>} nodes, op-implies-all grant target. */
    public static final Set<String> OP_GRANTS;

    static {
        Set<String> s = new LinkedHashSet<>(EVENT_NAMES.size());
        for (String e : EVENT_NAMES) s.add(PREFIX + e);
        OP_GRANTS = Collections.unmodifiableSet(s);
    }

    private NeoForgeOnEventPermissions() {}
}
