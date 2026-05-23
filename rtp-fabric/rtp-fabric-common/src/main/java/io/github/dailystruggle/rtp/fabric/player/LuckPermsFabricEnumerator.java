package io.github.dailystruggle.rtp.fabric.player;

import io.github.dailystruggle.rtp.common.RTP;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Reflective facade over LuckPerms-Fabric for enumerating granted permission
 * nodes on a per-player basis. Implements the LP-primary path of
 * {@code rtp-fabric-ADR-011}: the only Fabric permissions foundation today
 * that exposes a node-enumeration surface is LuckPerms (via
 * {@code net.luckperms.api.LuckPermsProvider#get()} -> {@code User#getNodes()}).
 *
 * <p>All access is reflective so {@code rtp-fabric-common} does not gain a
 * hard dependency on {@code net.luckperms:api}: if the LP API jar is absent
 * at runtime (i.e. the admin has not installed LuckPerms-Fabric), every call
 * returns an empty set and the caller falls back to the closed-namespace
 * registry probe.
 *
 * <p>Thread-safety: the cached {@link #available} flag is memoised on first
 * call. The reflective {@code Method} handles are immutable post-init.
 * {@link #grantedNodes(UUID)} never throws; any failure is logged once at
 * {@code WARNING} and the call returns {@link Collections#emptySet()}.
 */
public final class LuckPermsFabricEnumerator {

    private static volatile Boolean available;          // tri-state: null=unprobed, TRUE/FALSE memoised
    private static volatile Method providerGet;          // LuckPermsProvider#get()
    private static volatile Method getUserManager;       // LuckPerms#getUserManager()
    private static volatile Method getUser;              // UserManager#getUser(UUID)
    private static volatile Method getNodes;             // User#getNodes() -> Collection<Node>
    private static volatile Method nodeGetKey;           // Node#getKey()
    private static volatile Method nodeGetValue;         // Node#getValue() -> boolean

    private LuckPermsFabricEnumerator() {}

    /**
     * @return {@code true} iff the LuckPerms-Fabric API is loaded in this JVM
     *         and the provider is initialised. Memoised after first call.
     */
    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) return cached;
        synchronized (LuckPermsFabricEnumerator.class) {
            if (available != null) return available;
            try {
                Class<?> providerCls = Class.forName("net.luckperms.api.LuckPermsProvider");
                providerGet = providerCls.getMethod("get");
                // Probe a real call to confirm the provider is registered.
                Object lp = providerGet.invoke(null);
                if (lp == null) {
                    available = Boolean.FALSE;
                    return false;
                }
                getUserManager = lp.getClass().getMethod("getUserManager");
                // Resolve via the interface so we don't bind to a runtime impl class.
                Class<?> umCls = Class.forName("net.luckperms.api.model.user.UserManager");
                getUser = umCls.getMethod("getUser", UUID.class);
                Class<?> userCls = Class.forName("net.luckperms.api.model.user.User");
                getNodes = userCls.getMethod("getNodes");
                Class<?> nodeCls = Class.forName("net.luckperms.api.node.Node");
                nodeGetKey = nodeCls.getMethod("getKey");
                nodeGetValue = nodeCls.getMethod("getValue");
                available = Boolean.TRUE;
                return true;
            } catch (Throwable t) {
                // Expected when LuckPerms-Fabric is not installed; quiet at FINE.
                available = Boolean.FALSE;
                return false;
            }
        }
    }

    /**
     * @return the set of node keys granted with value=true for {@code uuid}, lowercased.
     *         Empty if LuckPerms is unavailable, the user is not cached yet
     *         (LP loads {@code User} async post-login), or any reflective call fails.
     *         Never returns {@code null} and never throws.
     */
    public static Set<String> grantedNodes(UUID uuid) {
        if (uuid == null || !isAvailable()) return Collections.emptySet();
        try {
            Object lp = providerGet.invoke(null);
            if (lp == null) return Collections.emptySet();
            Object um = getUserManager.invoke(lp);
            if (um == null) return Collections.emptySet();
            Object user = getUser.invoke(um, uuid);
            if (user == null) return Collections.emptySet();
            Object nodes = getNodes.invoke(user);
            if (!(nodes instanceof Collection<?> coll) || coll.isEmpty()) return Collections.emptySet();
            Set<String> out = new LinkedHashSet<>();
            for (Object n : coll) {
                if (n == null) continue;
                Object val = nodeGetValue.invoke(n);
                if (!(val instanceof Boolean b) || !b) continue;
                Object key = nodeGetKey.invoke(n);
                if (key == null) continue;
                out.add(key.toString().toLowerCase(Locale.ROOT));
            }
            return out;
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP] LuckPerms node enumeration failed for " + uuid
                            + " (" + t.getClass().getSimpleName() + "): " + t.getMessage());
            return Collections.emptySet();
        }
    }
}
