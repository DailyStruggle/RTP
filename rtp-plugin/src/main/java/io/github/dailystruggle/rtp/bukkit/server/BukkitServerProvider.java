package io.github.dailystruggle.rtp.bukkit.server;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class BukkitServerProvider {
    public static class ServerModel {
        public final String accessorClassName;
        public final String schedulerClassName;
        /**
         * Canonical platform brand resolved at the entrypoint ("Folia", "Paper", or
         * "Spigot"). This is the authoritative server-type decision: it is made here,
         * once, from the same probes that select the accessor/scheduler pair, and is
         * published onto the accessor at wire time so the accessor does not have to
         * re-detect the platform via independent class lookups.
         */
        public final String platform;

        public ServerModel(String accessorClassName, String schedulerClassName) {
            this(accessorClassName, schedulerClassName, null);
        }

        public ServerModel(String accessorClassName, String schedulerClassName, String platform) {
            this.accessorClassName = accessorClassName;
            this.schedulerClassName = schedulerClassName;
            this.platform = platform;
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
     * Basic Folia-aware scheduler shipped in the Paper adapter (and therefore in the free / lite
     * jar). Used on Folia only when the tuned {@code rtp-folia} adapter is absent from the
     * classpath - i.e. the free build, which excludes {@code io/.../folia/**} (ADR-024 / ADR-061).
     */
    private static final String BASIC_FOLIA_SCHEDULER =
            "io.github.dailystruggle.rtp.paper.scheduling.FoliaAwareScheduler";

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, BukkitServerProvider.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Resolve the accessor/scheduler pair for a Folia host. Prefer the tuned {@code rtp-folia}
     * adapter (LeafRTP-Pro); when it is not bundled (free build), fall back to the Paper accessor
     * driven by the basic {@link #BASIC_FOLIA_SCHEDULER} so the free build still runs on Folia.
     */
    private static ServerModel foliaModel(String tunedAccessor, String tunedScheduler, String paperAccessor) {
        if (classExists(tunedScheduler)) {
            return new ServerModel(tunedAccessor, tunedScheduler, "Folia");
        }
        return new ServerModel(paperAccessor, BASIC_FOLIA_SCHEDULER, "Folia");
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
        // Resolve the server type once, here at the entrypoint, from the same probes
        // that drive accessor/scheduler selection below. The Folia branches publish
        // "Folia" through foliaModel(...); the non-Folia branches fall through to this
        // value when constructing their ServerModel.
        String platform = isPaper() ? "Paper" : "Spigot";

        if (version.contains("26.1")) {
            if (isFolia()) {
                return foliaModel(
                        "io.github.dailystruggle.rtp.folia_v26_1_R1.server.ServerAccessorImpl",
                        "io.github.dailystruggle.rtp.folia_v26_1_R1.scheduling.FoliaSchedulerImpl",
                        "io.github.dailystruggle.rtp.paper_v26_1_R1.server.ServerAccessorImpl");
            } else if (isPaper()) {
                accessorClassName = "io.github.dailystruggle.rtp.paper_v26_1_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.paper_v26_1_R1.scheduling.BukkitSchedulerImpl";
            } else {
                accessorClassName = "io.github.dailystruggle.rtp.bukkitplatform.v26_1_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.bukkitplatform.v26_1_R1.scheduling.BukkitSchedulerImpl";
            }
        } else if (version.contains("1.21")) {
            if (isFolia()) {
                return foliaModel(
                        "io.github.dailystruggle.rtp.folia_v1_21_R1.server.ServerAccessorImpl",
                        "io.github.dailystruggle.rtp.folia_v1_21_R1.scheduling.FoliaSchedulerImpl",
                        "io.github.dailystruggle.rtp.paper_v1_21_R1.server.ServerAccessorImpl");
            } else if (isPaper()) {
                accessorClassName = "io.github.dailystruggle.rtp.paper_v1_21_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.paper_v1_21_R1.scheduling.BukkitSchedulerImpl";
            } else {
                accessorClassName = "io.github.dailystruggle.rtp.bukkitplatform.v1_21_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.bukkitplatform.v1_21_R1.scheduling.BukkitSchedulerImpl";
            }
        } else if (version.contains("1.20")) {
            if (isFolia()) {
                return foliaModel(
                        "io.github.dailystruggle.rtp.folia_v1_20_R1.server.ServerAccessorImpl",
                        "io.github.dailystruggle.rtp.folia_v1_20_R1.scheduling.FoliaSchedulerImpl",
                        "io.github.dailystruggle.rtp.paper_v1_20_R1.server.ServerAccessorImpl");
            } else if (isPaper()) {
                accessorClassName = "io.github.dailystruggle.rtp.paper_v1_20_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.paper_v1_20_R1.scheduling.BukkitSchedulerImpl";
            } else {
                accessorClassName = "io.github.dailystruggle.rtp.bukkitplatform.v1_20_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.bukkitplatform.v1_20_R1.scheduling.BukkitSchedulerImpl";
            }
        } else {
            if (isFolia()) {
                return foliaModel(
                        "io.github.dailystruggle.rtp.folia_v1_20_R1.server.ServerAccessorImpl",
                        "io.github.dailystruggle.rtp.folia_v1_20_R1.scheduling.FoliaSchedulerImpl",
                        "io.github.dailystruggle.rtp.paper_v1_20_R1.server.ServerAccessorImpl");
            } else if (isPaper()) {
                accessorClassName = "io.github.dailystruggle.rtp.paper_v1_20_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.paper_v1_20_R1.scheduling.BukkitSchedulerImpl";
            } else {
                accessorClassName = "io.github.dailystruggle.rtp.bukkitplatform.v1_20_R1.server.ServerAccessorImpl";
                schedulerClassName = "io.github.dailystruggle.rtp.bukkitplatform.v1_20_R1.scheduling.BukkitSchedulerImpl";
            }
        }
        return new ServerModel(accessorClassName, schedulerClassName, platform);
    }
}
