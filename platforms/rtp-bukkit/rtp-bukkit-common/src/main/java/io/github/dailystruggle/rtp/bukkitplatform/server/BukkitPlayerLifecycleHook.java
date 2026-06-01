package io.github.dailystruggle.rtp.bukkitplatform.server;

import io.github.dailystruggle.rtp.api.server.DispatchingPlayerLifecycleHook;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Bukkit / Paper / Folia implementation of
 * {@link io.github.dailystruggle.rtp.api.server.PlayerLifecycleHook} backed by
 * {@link DispatchingPlayerLifecycleHook}. Registers a Bukkit {@link Listener}
 * for {@link PlayerJoinEvent} and {@link PlayerQuitEvent} and fans the events
 * out to subscribers as UUID callbacks.
 *
 * <p>Listener priority is {@link EventPriority#MONITOR} (read-only observer
 * semantics; we never cancel and never modify event state). Both handlers are
 * registered with {@code ignoreCancelled = false} because join / quit events
 * are not cancellable on any supported Bukkit version, and even on platforms
 * where {@link PlayerQuitEvent} might be skipped (server shutdown corner
 * cases) we want maximum delivery coverage for downstream cleanup.
 *
 * <p>Introduced by <a href="../../../../../../../../../docs/adr/ADR-049-network-mode-platform-neutral-lift.md">ADR-049</a>.
 *
 * @since 3.0.0-beta.4
 */
public final class BukkitPlayerLifecycleHook extends DispatchingPlayerLifecycleHook implements Listener {

    /**
     * Register the listener against the supplied plugin. Safe to call once per
     * plugin lifecycle; calling twice would deliver every event twice.
     *
     * @param plugin the owning plugin; must not be {@code null}
     */
    public void register(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        fireJoin(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        fireQuit(event.getPlayer().getUniqueId());
    }
}
