package io.github.dailystruggle.rtp.fabric.version;

import io.github.dailystruggle.rtp.common.RTP;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Process-wide holder of the active {@link FabricVersionAdapter}.
 *
 * <p>Set exactly once by {@code RTPFabricMod} during {@code onInitialize}, after
 * it has classified the running MC version and reflectively instantiated the
 * matching v-submodule's adapter. Lookups before the bootstrap has run throw
 * {@link IllegalStateException} per S-006 (no silent no-op on early-API calls).</p>
 *
 * <p>This class is intentionally not part of the public {@code rtp-api}
 * surface - it is internal Fabric platform glue, callable only by code under
 * {@code io.github.dailystruggle.rtp.fabric.**}.</p>
 *
 * <p><b>See also:</b> rtp-fabric-ADR-001 section "Runtime selection".</p>
 */
public final class FabricVersionAdapterRegistry {

    private static final AtomicReference<FabricVersionAdapter> ACTIVE = new AtomicReference<>();

    private FabricVersionAdapterRegistry() {}

    /**
     * Installs the adapter selected for the running MC version. Must be called
     * exactly once before any code under {@code rtp-fabric-common} that
     * touches a version-volatile call site. Subsequent calls with a non-null
     * argument throw {@link IllegalStateException}; passing {@code null}
     * clears the registration (used by the test harness only).
     */
    public static void install(FabricVersionAdapter adapter) {
        if (adapter == null) {
            ACTIVE.set(null);
            return;
        }
        if (!ACTIVE.compareAndSet(null, adapter)) {
            FabricVersionAdapter current = ACTIVE.get();
            throw new IllegalStateException(
                    "FabricVersionAdapter already installed: " + (current == null ? "<cleared>" : current.mcVersion())
                            + "; refusing to overwrite with " + adapter.mcVersion());
        }
        try {
            RTP.log(Level.INFO, "[RTP][Fabric] Active version adapter: " + adapter.mcVersion()
                    + " (" + adapter.getClass().getName() + ")");
        } catch (Throwable ignored) {
            // RTP may not be available yet during very early bootstrap; the install
            // call itself must not fail because of logging.
        }
    }

    /**
     * Returns the active adapter, or throws {@link IllegalStateException} if
     * the bootstrap has not yet installed one (REQ-RTP-S-006 - fail loud, do
     * not silently no-op).
     */
    public static FabricVersionAdapter require() {
        FabricVersionAdapter a = ACTIVE.get();
        if (a == null) {
            throw new IllegalStateException(
                    "FabricVersionAdapter not yet installed — RTPFabricMod.onInitialize must run before"
                            + " any rtp-fabric version-sensitive call site (see rtp-fabric-ADR-001).");
        }
        return a;
    }

    /**
     * Returns the active adapter, or {@code null} if not yet installed.
     * Prefer {@link #require()} on hot paths - null returns are reserved for
     * defensive shutdown and test plumbing.
     */
    public static FabricVersionAdapter peek() {
        return ACTIVE.get();
    }

    /** Returns whether an adapter has been installed. */
    public static boolean isInstalled() {
        return ACTIVE.get() != null;
    }

    /**
     * Tests-only - clears the active adapter. Production code shall not call
     * this; it exists so tests can simulate cold bootstrap.
     */
    public static void clearForTesting() {
        ACTIVE.set(null);
    }

    /** @return the MC version string of the active adapter, or {@code "<none>"} if none. */
    public static String activeVersion() {
        FabricVersionAdapter a = ACTIVE.get();
        return a == null ? "<none>" : Objects.toString(a.mcVersion(), "<unknown>");
    }
}
