package io.github.dailystruggle.rtp.bukkit.server;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class BukkitServerProvider {
    public static class ServerModel {
        public final String accessorClassName;
        public final String schedulerClassName;

        public ServerModel(String accessorClassName, String schedulerClassName) {
            this.accessorClassName = accessorClassName;
            this.schedulerClassName = schedulerClassName;
        }
    }

    /** Class-probe for Paper. Independent of any plugin instance. */
    public static boolean isPaper() {
        try {
            Class.forName("io.papermc.paper.configuration.PaperConfigurations");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Class-probe for Folia. Independent of any plugin instance. */
    public static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Backwards-compatible overload. The full bootstrap ({@code RTPBukkitPlugin})
     * passes its plugin instance for parameter-symmetry with prior versions; the
     * lite bootstrap ({@code RTPBukkitLitePlugin}) calls the {@link JavaPlugin}
     * variant. Both delegate to the same probe-based resolution -- ADR-024 §"three
     * layers": the plugin instance carries no detection state.
     */
    public static ServerModel resolveServerModel(JavaPlugin plugin) {
        String version = Bukkit.getBukkitVersion();
        String accessorClassName;
        String schedulerClassName;

        if (version.contains("26.1")) {
            if (isFolia()) {
                accessorClassName = "io.github.dailystruggle.rtp.folia_v26_1_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.folia_v26_1_R1.scheduling.FoliaSchedulerImpl";
            } else if (isPaper()) {
                accessorClassName = "io.github.dailystruggle.rtp.paper_v26_1_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.paper_v26_1_R1.scheduling.BukkitSchedulerImpl";
            } else {
                accessorClassName = "io.github.dailystruggle.rtp.spigot_v26_1_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.spigot_v26_1_R1.scheduling.BukkitSchedulerImpl";
            }
        } else if (version.contains("1.21")) {
            if (isFolia()) {
                accessorClassName = "io.github.dailystruggle.rtp.folia_v1_21_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.folia_v1_21_R1.scheduling.FoliaSchedulerImpl";
            } else if (isPaper()) {
                accessorClassName = "io.github.dailystruggle.rtp.paper_v1_21_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.paper_v1_21_R1.scheduling.BukkitSchedulerImpl";
            } else {
                accessorClassName = "io.github.dailystruggle.rtp.spigot_v1_21_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.spigot_v1_21_R1.scheduling.BukkitSchedulerImpl";
            }
        } else if (version.contains("1.20")) {
            if (isFolia()) {
                accessorClassName = "io.github.dailystruggle.rtp.folia_v1_20_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.folia_v1_20_R1.scheduling.FoliaSchedulerImpl";
            } else if (isPaper()) {
                accessorClassName = "io.github.dailystruggle.rtp.paper_v1_20_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.paper_v1_20_R1.scheduling.BukkitSchedulerImpl";
            } else {
                accessorClassName = "io.github.dailystruggle.rtp.spigot_v1_20_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.spigot_v1_20_R1.scheduling.BukkitSchedulerImpl";
            }
        } else {
            if (isFolia()) {
                accessorClassName = "io.github.dailystruggle.rtp.folia_v1_20_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.folia_v1_20_R1.scheduling.FoliaSchedulerImpl";
            } else if (isPaper()) {
                accessorClassName = "io.github.dailystruggle.rtp.paper_v1_20_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.paper_v1_20_R1.scheduling.BukkitSchedulerImpl";
            } else {
                accessorClassName = "io.github.dailystruggle.rtp.spigot_v1_20_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.spigot_v1_20_R1.scheduling.BukkitSchedulerImpl";
            }
        }
        return new ServerModel(accessorClassName, schedulerClassName);
    }
}
