package io.github.dailystruggle.rtp.fabric.player;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.common.RTP;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Shared resolver implementing the formal {@code effective(player)} set defined by
 * {@code rtp-fabric-ADR-011}:
 *
 * <pre>
 * effective(player) =
 *     DEFAULT_TRUE
 *   ∪ LP_NODES(player.uuid)                              // LuckPerms-Fabric, if present
 *   ∪ REGISTRY_PROBE(player, rtp.effect.*)               // closed-namespace probe
 *   ∪ REGISTRY_PROBE(player, rtp.onevent.*)              // closed-namespace probe
 *   ∪ (player.isOp ? OP_CLOSED_NAMESPACE_GRANTS : ∅)     // op-implies-all, closed-NS only
 * </pre>
 *
 * <p>Reused by {@code FabricRTPPlayer}, {@code FabricRTPPlayerUnobf},
 * {@code V26_1_R1FabricRTPPlayer}, and the console sender on {@code FabricServerAccessor}.
 * Each caller supplies its own UUID, op-flag, and {@code hasPermission} predicate; the
 * resolver does not touch any platform handle directly.
 *
 * <p>{@code EffectFactory.registeredNames()} is reached reflectively so this module
 * does not gain a hard compile-time dependency on {@code effects-api}: if the
 * effects-api jar is absent (lite assembly, test fixtures), the {@code rtp.effect.*}
 * probe simply contributes nothing. The lookup is memoised after first probe.
 */
public final class FabricEffectivePermissionsResolver {

    public static final String EFFECT_PREFIX = "rtp.effect.";

    private static volatile Boolean effectsApiAvailable; // tri-state cache
    private static volatile Method registeredNamesMethod;

    private FabricEffectivePermissionsResolver() {}

    /**
     * Compute the effective set for a player.
     *
     * @param uuid          player UUID; {@code null} yields empty set.
     * @param isOp          server-op flag; gates the op-implies-all closed-namespace expansion.
     * @param hasPermission predicate routing through the platform's three-tier
     *                      {@code hasPermission} chain. Must never throw.
     * @return ordered, mutable {@link LinkedHashSet} of lowercased node strings.
     */
    public static Set<String> resolve(UUID uuid, boolean isOp, Predicate<String> hasPermission) {
        Set<String> out = new LinkedHashSet<>();
        if (uuid == null) return out;

        // 1. DEFAULT_TRUE
        out.addAll(FabricDefaultPermissions.defaultTrue());

        // 2. LP_NODES (LuckPerms-Fabric primary path)
        out.addAll(LuckPermsFabricEnumerator.grantedNodes(uuid));

        // 3. REGISTRY_PROBE(rtp.effect.*) - reflective registry lookup
        Collection<String> effectIds = effectIds();
        if (!effectIds.isEmpty() && hasPermission != null) {
            for (String id : effectIds) {
                if (id == null || id.isEmpty()) continue;
                String node = (EFFECT_PREFIX + id).toLowerCase(Locale.ROOT);
                try {
                    if (hasPermission.test(node)) out.add(node);
                } catch (Throwable ignored) {
                    // Best-effort: hasPermission must not abort enumeration.
                }
            }
        }

        // 4. REGISTRY_PROBE(rtp.onevent.*)
        if (hasPermission != null) {
            for (String event : FabricOnEventPermissions.EVENT_NAMES) {
                String node = FabricOnEventPermissions.PREFIX + event;
                try {
                    if (hasPermission.test(node)) out.add(node);
                } catch (Throwable ignored) {
                    // ignored
                }
            }
        }

        // 5. OP_CLOSED_NAMESPACE_GRANTS - closed namespaces only.
        //
        // NOTE: the rtp.onevent.* family is deliberately EXCLUDED from the
        // op-implies-all expansion. plugin.yml declares every rtp.onevent.<event>
        // node as `default: false` (denied to everyone, including ops), so an
        // operator must be granted the node EXPLICITLY (via a perms mod or
        // LuckPerms) before any on-event teleport fires. Auto-granting it to ops
        // here caused operators to be silently random-teleported on join /
        // respawn / world-change. Only the rtp.effect.* closed namespace is
        // op-implies-all (its plugin.yml default is `op`).
        if (isOp) {
            for (String id : effectIds) {
                if (id == null || id.isEmpty()) continue;
                out.add((EFFECT_PREFIX + id).toLowerCase(Locale.ROOT));
            }
        }

        return out;
    }

    /**
     * Console variant: returns the full op-grant set without consulting LuckPerms
     * or running any {@code hasPermission} probe. Console is op-equivalent on
     * every platform; numeric tails ({@code rtp.delay.<n>} / {@code rtp.cooldown.<n>})
     * are deliberately omitted per ADR-011 limitation #3.
     */
    public static Set<String> resolveConsole() {
        Set<String> out = new LinkedHashSet<>();
        out.addAll(FabricDefaultPermissions.defaultTrue());
        out.addAll(FabricOnEventPermissions.OP_GRANTS);
        for (String id : effectIds()) {
            if (id == null || id.isEmpty()) continue;
            out.add((EFFECT_PREFIX + id).toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Convenience binding for a {@link RTPCommandSender} that exposes {@code hasPermission(String)}. */
    public static Set<String> resolveFor(UUID uuid, boolean isOp, RTPCommandSender sender) {
        if (sender == null) return resolve(uuid, isOp, null);
        return resolve(uuid, isOp, sender::hasPermission);
    }

    // --- internals -------------------------------------------------------

    private static Collection<String> effectIds() {
        if (!effectsApiAvailable()) return Collections.emptyList();
        try {
            Object out = registeredNamesMethod.invoke(null);
            if (out instanceof Collection<?> c) {
                // Cast through Object[] to keep generics tidy; entries are Strings.
                Set<String> ids = new LinkedHashSet<>(c.size());
                for (Object o : c) if (o != null) ids.add(o.toString());
                return ids;
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP] EffectFactory.registeredNames() probe failed ("
                            + t.getClass().getSimpleName() + "): " + t.getMessage());
        }
        return Collections.emptyList();
    }

    private static boolean effectsApiAvailable() {
        Boolean cached = effectsApiAvailable;
        if (cached != null) return cached;
        synchronized (FabricEffectivePermissionsResolver.class) {
            if (effectsApiAvailable != null) return effectsApiAvailable;
            try {
                Class<?> factoryCls = Class.forName("io.github.dailystruggle.effectsapi.common.EffectFactory");
                registeredNamesMethod = factoryCls.getMethod("registeredNames");
                effectsApiAvailable = Boolean.TRUE;
                return true;
            } catch (Throwable t) {
                effectsApiAvailable = Boolean.FALSE;
                return false;
            }
        }
    }
}
