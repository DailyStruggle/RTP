package io.github.dailystruggle.rtp.bukkit.server;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import org.bukkit.Bukkit;

public class BukkitServerProvider {
    public static class ServerModel {
        public final String accessorClassName;
        public final String schedulerClassName;

        public ServerModel(String accessorClassName, String schedulerClassName) {
            this.accessorClassName = accessorClassName;
            this.schedulerClassName = schedulerClassName;
        }
    }

    public static ServerModel resolveServerModel(RTPBukkitPlugin plugin) {
        String version = Bukkit.getBukkitVersion();
        String accessorClassName;
        String schedulerClassName;

        if (version.contains("26.1")) {
            if (plugin.isFolia()) {
                accessorClassName = "io.github.dailystruggle.rtp.folia_v26_1_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.folia_v26_1_R1.scheduling.FoliaSchedulerImpl";
            } else if (plugin.isPaper()) {
                accessorClassName = "io.github.dailystruggle.rtp.paper_v26_1_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.paper_v26_1_R1.scheduling.BukkitSchedulerImpl";
            } else {
                accessorClassName = "io.github.dailystruggle.rtp.spigot_v26_1_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.spigot_v26_1_R1.scheduling.BukkitSchedulerImpl";
            }
        } else if (version.contains("1.21")) {
            if (plugin.isFolia()) {
                accessorClassName = "io.github.dailystruggle.rtp.folia_v1_21_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.folia_v1_21_R1.scheduling.FoliaSchedulerImpl";
            } else if (plugin.isPaper()) {
                accessorClassName = "io.github.dailystruggle.rtp.paper_v1_21_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.paper_v1_21_R1.scheduling.BukkitSchedulerImpl";
            } else {
                accessorClassName = "io.github.dailystruggle.rtp.spigot_v1_21_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.spigot_v1_21_R1.scheduling.BukkitSchedulerImpl";
            }
        } else if (version.contains("1.20")) {
            if (plugin.isFolia()) {
                accessorClassName = "io.github.dailystruggle.rtp.folia_v1_20_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.folia_v1_20_R1.scheduling.FoliaSchedulerImpl";
            } else if (plugin.isPaper()) {
                accessorClassName = "io.github.dailystruggle.rtp.paper_v1_20_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.paper_v1_20_R1.scheduling.BukkitSchedulerImpl";
            } else {
                accessorClassName = "io.github.dailystruggle.rtp.spigot_v1_20_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.spigot_v1_20_R1.scheduling.BukkitSchedulerImpl";
            }
        } else {
            if (plugin.isFolia()) {
                accessorClassName = "io.github.dailystruggle.rtp.folia_v1_20_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.folia_v1_20_R1.scheduling.FoliaSchedulerImpl";
            } else if (plugin.isPaper()) {
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
