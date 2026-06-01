package io.github.dailystruggle.rtp.proxy.common;

/**
 * Static holder for the registered {@link RTPProxyAccessor}, mirroring
 * {@code RTP.serverAccessor} in {@code rtp-core}.
 *
 * <p>Bootstrap order (rtp-proxy-ADR-013):
 * <ol>
 *   <li>Adapter constructs its concrete {@code RTPProxyAccessor}.</li>
 *   <li>Adapter calls {@link #setProxyAccessor(RTPProxyAccessor)} BEFORE
 *       loading {@code network.yml} or invoking any other
 *       {@code rtp-proxy-common} entry point.</li>
 *   <li>{@code rtp-proxy-common} consumers (config loader, dispatcher,
 *       publisher) read {@link #proxyAccessor()} and fail-fast with
 *       {@link IllegalStateException} if the slot is still null (S-006).</li>
 * </ol>
 *
 * <p>The slot is {@code volatile} but otherwise unguarded; production
 * registers exactly once during adapter bootstrap, and tests are expected
 * to clear it between cases via {@link #clearProxyAccessor()}.</p>
 */
public final class RtpProxy {

    private static volatile RTPProxyAccessor proxyAccessor;

    private RtpProxy() {
        // static holder; not constructable
    }

    /**
     * Returns the registered accessor.
     *
     * @throws IllegalStateException if no accessor has been registered yet
     *         (S-006). Callers must not silently no-op on a null slot.
     */
    public static RTPProxyAccessor proxyAccessor() {
        RTPProxyAccessor a = proxyAccessor;
        if (a == null) {
            throw new IllegalStateException(
                    "RtpProxy.proxyAccessor has not been registered; "
                            + "the platform adapter must call "
                            + "RtpProxy.setProxyAccessor(...) during bootstrap "
                            + "before any rtp-proxy-common entry point is invoked.");
        }
        return a;
    }

    /** Whether an accessor has been registered. Does not throw. */
    public static boolean isRegistered() {
        return proxyAccessor != null;
    }

    /**
     * Register the platform adapter's accessor. Called once per process at
     * adapter bootstrap.
     *
     * @throws NullPointerException if {@code accessor} is null
     */
    public static void setProxyAccessor(RTPProxyAccessor accessor) {
        if (accessor == null) {
            throw new NullPointerException("accessor");
        }
        proxyAccessor = accessor;
    }

    /**
     * Test-only: clear the registered accessor so the next test can
     * register a different one. Production code must not call this.
     */
    public static void clearProxyAccessor() {
        proxyAccessor = null;
    }
}
