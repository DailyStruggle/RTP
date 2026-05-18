package io.github.dailystruggle.rtp.bukkit.metrics;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.metrics.api.MetricsBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for {@link MetricsBindingDispatcher} (CHECKLIST-metrics-and-multiserver.md
 * row B9). Verifies:
 * <ul>
 *   <li>{@code install()} replaces {@link RTP#metrics}'s default
 *       {@link MetricsBinding#NOOP} with a non-NOOP binding;</li>
 *   <li>{@code install()} is idempotent (a second call is a no-op);</li>
 *   <li>{@code uninstall()} restores the NOOP binding and clears the installed flag.</li>
 * </ul>
 *
 * <p>The test classpath of {@code rtp-plugin} declares {@code paper-api} (via MockBukkit)
 * <b>before</b> {@code spigot-api}, so {@code Bukkit#getTPS} is reflectively reachable
 * and the dispatcher's Paper probe succeeds. That exercises the production-realistic
 * branch (the binding is {@code PaperMetricsBinding}) and avoids the Spigot path's
 * dependency on a live {@code RTP.scheduler}. Both paths share the same
 * {@code setBinding} / {@code getBinding} contract, which is what we are asserting.
 */
class MetricsBindingDispatcherTest {

    @AfterEach
    void tearDown() {
        // Defensive: leave the static state at NOOP for downstream tests.
        MetricsBindingDispatcher.uninstall();
    }

    @Test
    void install_replacesNoopBinding_andIsIdempotent() {
        assertFalse(MetricsBindingDispatcher.isInstalled(),
                "precondition: dispatcher starts uninstalled");
        assertSame(MetricsBinding.NOOP, RTP.metrics.getBinding(),
                "precondition: RTP.metrics defaults to MetricsBinding.NOOP");

        MetricsBindingDispatcher.install();

        assertTrue(MetricsBindingDispatcher.isInstalled(),
                "install() must flip the installed flag");
        MetricsBinding afterFirst = RTP.metrics.getBinding();
        assertNotSame(MetricsBinding.NOOP, afterFirst,
                "install() must replace the NOOP sentinel with a real binding "
                        + "(Paper or Spigot path)");

        // Idempotency: second install() must not re-create the binding.
        MetricsBindingDispatcher.install();
        assertSame(afterFirst, RTP.metrics.getBinding(),
                "install() must be idempotent; second call must not replace the binding");
    }

    @Test
    void uninstall_restoresNoopBinding() {
        MetricsBindingDispatcher.install();
        assertTrue(MetricsBindingDispatcher.isInstalled(),
                "precondition: dispatcher installed before uninstall");

        MetricsBindingDispatcher.uninstall();

        assertFalse(MetricsBindingDispatcher.isInstalled(),
                "uninstall() must clear the installed flag");
        assertSame(MetricsBinding.NOOP, RTP.metrics.getBinding(),
                "uninstall() must restore MetricsBinding.NOOP");
    }

    @Test
    void uninstall_isSafeWhenNotInstalled() {
        // Sanity: calling uninstall on a fresh dispatcher must not throw.
        MetricsBindingDispatcher.uninstall();
        assertFalse(MetricsBindingDispatcher.isInstalled());
        assertSame(MetricsBinding.NOOP, RTP.metrics.getBinding());
    }
}
