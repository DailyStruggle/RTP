package io.github.dailystruggle.rtp.neoforge.player;

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
 * Reflective facade over LuckPerms for enumerating granted permission nodes and
 * resolving an individual node verdict on a per-player basis (NeoForge analogue
 * of {@code LuckPermsFabricEnumerator}).
 *
 * <p>LuckPerms exposes the same platform-agnostic API
 * ({@code net.luckperms.api.LuckPermsProvider#get()}) on every platform, so the
 * reflective probe is identical to the Fabric enumerator — only the class doc
 * differs. All access is reflective so {@code rtp-neoforge-common} does not gain
 * a hard dependency on {@code net.luckperms:api}: if the LP API jar is absent at
 * runtime (the admin has not installed LuckPerms), every call returns an empty
 * set / {@code null} verdict and the caller falls back to the closed-namespace
 * registry probe and the on-disk {@code ops.json} check.
 *
 * <p>Thread-safety: the cached {@link #available} flag is memoised on first call.
 * The reflective {@code Method} handles are immutable post-init.
 * {@link #grantedNodes(UUID)} and {@link #checkPermission(UUID, String)} never
 * throw; any failure is logged once at {@code WARNING} and the call degrades to
 * empty / {@code null}.
 */
public final class LuckPermsNeoForgeEnumerator {

    private static volatile Boolean available;          // tri-state: null=unprobed, TRUE/FALSE memoised
    private static volatile Method providerGet;          // LuckPermsProvider#get()
    private static volatile Method getUserManager;       // LuckPerms#getUserManager()
    private static volatile Method getUser;              // UserManager#getUser(UUID)
    private static volatile Method getNodes;             // User#getNodes() -> Collection<Node>
    private static volatile Method nodeGetKey;           // Node#getKey()
    private static volatile Method nodeGetValue;         // Node#getValue() -> boolean
    private static volatile Method getCachedData;        // User#getCachedData()
    private static volatile Method getPermissionData;    // CachedDataManager#getPermissionData()
    private static volatile Method checkPermission;      // CachedPermissionData#checkPermission(String) -> Tristate
    private static volatile Method tristateAsBoolean;    // Tristate#asBoolean()
    private static volatile Object tristateUndefined;    // Tristate.UNDEFINED

    private LuckPermsNeoForgeEnumerator() {}

    /**
     * @return {@code true} iff the LuckPerms API is loaded in this JVM and the
     *         provider is initialised. Memoised after first call.
     */
    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) return cached;
        synchronized (LuckPermsNeoForgeEnumerator.class) {
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
                // Cached-data path for explicit node verdicts (wildcard + inheritance aware).
                Class<?> cdHolderCls = Class.forName("net.luckperms.api.cacheddata.CachedDataManager");
                getCachedData = userCls.getMethod("getCachedData");
                getPermissionData = cdHolderCls.getMethod("getPermissionData");
                Class<?> permDataCls = Class.forName("net.luckperms.api.cacheddata.CachedPermissionData");
                checkPermission = permDataCls.getMethod("checkPermission", String.class);
                Class<?> tristateCls = Class.forName("net.luckperms.api.util.Tristate");
                tristateAsBoolean = tristateCls.getMethod("asBoolean");
                tristateUndefined = Enum.valueOf(tristateCls.asSubclass(Enum.class), "UNDEFINED");
                available = Boolean.TRUE;
                return true;
            } catch (Throwable t) {
                // Expected when LuckPerms is not installed; quiet.
                available = Boolean.FALSE;
                return false;
            }
        }
    }

    /**
     * Resolve a single permission node through LuckPerms' cached data (honours
     * wildcards, inheritance, and explicit denials).
     *
     * @return {@link Boolean#TRUE} / {@link Boolean#FALSE} for an explicit
     *         LuckPerms verdict, or {@code null} when LuckPerms is unavailable,
     *         the user is uncached, the verdict is {@code UNDEFINED}, or any
     *         reflective call fails. Never throws.
     */
    public static Boolean checkPermission(UUID uuid, String node) {
        if (uuid == null || node == null || node.isEmpty() || !isAvailable()) return null;
        try {
            Object lp = providerGet.invoke(null);
            if (lp == null) return null;
            Object um = getUserManager.invoke(lp);
            if (um == null) return null;
            Object user = getUser.invoke(um, uuid);
            if (user == null) return null;
            Object cached = getCachedData.invoke(user);
            if (cached == null) return null;
            Object permData = getPermissionData.invoke(cached);
            if (permData == null) return null;
            Object tristate = checkPermission.invoke(permData, node);
            if (tristate == null || tristate.equals(tristateUndefined)) return null;
            Object asBool = tristateAsBoolean.invoke(tristate);
            return (asBool instanceof Boolean b) ? b : null;
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][NeoForge] LuckPerms permission check failed for " + uuid
                            + " node '" + node + "' (" + t.getClass().getSimpleName() + "): "
                            + t.getMessage());
            return null;
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
                    "[RTP][NeoForge] LuckPerms node enumeration failed for " + uuid
                            + " (" + t.getClass().getSimpleName() + "): " + t.getMessage());
            return Collections.emptySet();
        }
    }
}
