package io.github.dailystruggle.rtp.neoforge.version;

import io.github.dailystruggle.rtp.common.RTP;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Process-wide holder of the active {@link NeoForgeVersionAdapter}.
 *
 * <p>Set exactly once by {@code RTPNeoForgeMod} during server start, after it
 * classifies the running MC version and instantiates the matching carrier's
 * adapter. Lookups before the bootstrap has run throw
 * {@link IllegalStateException} per S-006 (no silent no-op on early-API calls).</p>
 *
 * <p>Internal NeoForge platform glue — not part of the public {@code rtp-api}
 * surface. See rtp-neoforge-ADR-001.</p>
 */
public final class NeoForgeVersionAdapterRegistry {

    private static final AtomicReference<NeoForgeVersionAdapter> ACTIVE = new AtomicReference<>();

    private NeoForgeVersionAdapterRegistry() {}

    /**
     * Installs the adapter selected for the running MC version. Must be called
     * exactly once before any {@code rtp-neoforge-common} code that touches a
     * version-volatile call site. A second non-null install throws
     * {@link IllegalStateException}; passing {@code null} clears the
     * registration (test harness only).
     */
    public static void install(NeoForgeVersionAdapter adapter) {
        if (adapter == null) {
            ACTIVE.set(null);
            return;
        }
        if (!ACTIVE.compareAndSet(null, adapter)) {
            NeoForgeVersionAdapter current = ACTIVE.get();
            throw new IllegalStateException(
                    "NeoForgeVersionAdapter already installed: "
                            + (current == null ? "<cleared>" : current.mcVersion())
                            + "; refusing to overwrite with " + adapter.mcVersion());
        }
        try {
            RTP.log(Level.INFO, "[RTP][NeoForge] Active version adapter: " + adapter.mcVersion()
                    + " (" + adapter.getClass().getName() + ")");
        } catch (Throwable ignored) {
            // RTP may not be available yet during very early bootstrap; the install
            // call itself must not fail because of logging.
        }
    }

    /**
     * Returns the active adapter, or throws {@link IllegalStateException} if the
     * bootstrap has not yet installed one (REQ-RTP-S-006 — fail loud, do not
     * silently no-op).
     */
    public static NeoForgeVersionAdapter require() {
        NeoForgeVersionAdapter a = ACTIVE.get();
        if (a == null) {
            throw new IllegalStateException(
                    "NeoForgeVersionAdapter not yet installed — RTPNeoForgeMod must run server-start"
                            + " before any rtp-neoforge version-sensitive call site (see rtp-neoforge-ADR-001).");
        }
        return a;
    }

    /**
     * Returns the active adapter, or {@code null} if not yet installed. Prefer
     * {@link #require()} on hot paths — null returns are reserved for defensive
     * shutdown and test plumbing.
     */
    public static NeoForgeVersionAdapter peek() {
        return ACTIVE.get();
    }

    /** Returns whether an adapter has been installed. */
    public static boolean isInstalled() {
        return ACTIVE.get() != null;
    }

    /**
     * Tests-only — clears the active adapter. Production code shall not call
     * this; it exists so tests can simulate cold bootstrap.
     */
    public static void clearForTesting() {
        ACTIVE.set(null);
    }

    /** @return the MC version string of the active adapter, or {@code "<none>"} if none. */
    public static String activeVersion() {
        NeoForgeVersionAdapter a = ACTIVE.get();
        return a == null ? "<none>" : Objects.toString(a.mcVersion(), "<unknown>");
    }
}
