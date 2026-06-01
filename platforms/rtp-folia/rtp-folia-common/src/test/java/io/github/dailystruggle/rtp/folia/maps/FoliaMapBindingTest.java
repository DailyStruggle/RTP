package io.github.dailystruggle.rtp.folia.maps;

import io.github.dailystruggle.mapsapi.MapBinding;
import io.github.dailystruggle.mapsapi.MapBindingLifecycle;
import io.github.dailystruggle.mapsapi.bukkit.BukkitMapBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lightweight, MockBukkit-free coverage for {@link FoliaMapBinding} per
 * {@code CHECKLIST-maps-api.md} Stage 2.3. The Bukkit-side allocate / render
 * paths are exhaustively covered by {@code BukkitMapBindingTest}; this suite
 * focuses on the Folia-specific narrow surface:
 *
 * <ul>
 *   <li>FoliaMapBinding is a subtype of {@link BukkitMapBinding} and the
 *       {@link MapBinding} / {@link MapBindingLifecycle} contracts.</li>
 *   <li>{@code bindLive} throws a Folia-specific
 *       {@link UnsupportedOperationException} message (so log readers can
 *       tell which platform path is unwired).</li>
 *   <li>{@code dispatchToViewerRegion} returns {@code false} for an offline
 *       viewer rather than throwing -- the contract documented on the
 *       method, and the safety net the future Stage 3 {@code bindLive}
 *       implementation will rely on.</li>
 *   <li>Null arguments to {@code dispatchToViewerRegion} are rejected.</li>
 * </ul>
 */
class FoliaMapBindingTest {

    @Test
    @DisplayName("is-a BukkitMapBinding + MapBinding + MapBindingLifecycle")
    void typeHierarchy() {
        FoliaMapBinding binding = new FoliaMapBinding();
        assertTrue(binding instanceof BukkitMapBinding,
                "FoliaMapBinding must extend BukkitMapBinding (Stage 2.3)");
        assertTrue(binding instanceof MapBinding,
                "FoliaMapBinding must implement MapBinding via inheritance");
        assertTrue(binding instanceof MapBindingLifecycle,
                "FoliaMapBinding must implement MapBindingLifecycle via inheritance");
    }

    @Test
    @DisplayName("bindLive is inherited from BukkitMapBinding (NPE on null handle, contract delegated)")
    void bindLiveIsInherited() {
        FoliaMapBinding binding = new FoliaMapBinding();
        // The inherited path rejects null args via Objects.requireNonNull; the
        // important contract here is that FoliaMapBinding no longer overrides
        // bindLive to throw UnsupportedOperationException -- live charts now
        // work on Folia via the supplier-driven LiveChartRenderer.
        assertThrows(NullPointerException.class,
                () -> binding.bindLive(null, null, null));
    }

    @Test
    @DisplayName("dispatchToViewerRegion returns false for an offline viewer")
    void dispatchOffline() {
        FoliaMapBinding binding = new FoliaMapBinding();
        // No Bukkit server is running in this test; Bukkit.getPlayer(...)
        // returns null and the method must return false rather than throw.
        boolean dispatched = binding.dispatchToViewerRegion(
                UUID.randomUUID(), () -> { throw new AssertionError("must not run"); });
        assertFalse(dispatched,
                "offline viewer must yield a no-op dispatch returning false");
    }

    @Test
    @DisplayName("dispatchToViewerRegion rejects null arguments")
    void dispatchNullArgs() {
        FoliaMapBinding binding = new FoliaMapBinding();
        assertThrows(NullPointerException.class,
                () -> binding.dispatchToViewerRegion(null, () -> {}));
        assertThrows(NullPointerException.class,
                () -> binding.dispatchToViewerRegion(UUID.randomUUID(), null));
    }

    @Test
    @DisplayName("onPlayerQuit on a fresh binding is a safe no-op (inherited from BukkitMapBinding)")
    void quitOnFreshBindingIsNoop() {
        FoliaMapBinding binding = new FoliaMapBinding();
        // Inherits the BukkitMapBinding cache; an unknown-quit must not throw.
        binding.onPlayerQuit(UUID.randomUUID());
    }
}
