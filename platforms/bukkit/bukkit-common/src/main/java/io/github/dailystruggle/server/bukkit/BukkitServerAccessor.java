package io.github.dailystruggle.server.bukkit;

import io.github.dailystruggle.rtp.bukkitplatform.server.BukkitPluginServerAccessor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Shared Bukkit / Paper / Folia {@link io.github.dailystruggle.server.api.ServerAccessor}
 * implementation for standalone plugins ({@code :bukkit-common}).
 *
 * @governedBy ADR-049, ADR-115, ADR-116
 */
public class BukkitServerAccessor extends BukkitPluginServerAccessor {

    protected BukkitServerAccessor() {
        super();
    }

    public BukkitServerAccessor(JavaPlugin plugin) {
        super(plugin);
    }

    public BukkitServerAccessor(Plugin plugin) {
        super(plugin);
    }

    public BukkitServerAccessor(JavaPlugin plugin, String namespace) {
        super(plugin, namespace);
    }

    public BukkitServerAccessor(Plugin plugin, String namespace) {
        super(plugin, namespace);
    }
}
