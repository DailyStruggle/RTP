package io.github.dailystruggle.bstats.api;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The shared, server-wide bStats config every bStats-using plugin on the host reads.
 *
 * <p>Two on-disk formats exist: flat YAML ({@code config.yml}, {@code key: value};
 * Bukkit) and {@code config.txt} ({@code key=value}; upstream base library, used by modded hosts).
 * An existing file is read, never rewritten, so the operator's opt-out and the
 * server UUID (which keeps install counts continuous) are preserved. A file is
 * only created when none exists.
 */
public final class BStatsConfig {

    /** File format to create when no bStats config exists yet. */
    public enum Format {
        YAML("config.yml"),
        TEXT("config.txt");

        final String fileName;

        Format(String fileName) {
            this.fileName = fileName;
        }
    }

    private static final String YAML_HEADER =
            "# bStats (https://bStats.org) collects some basic information for plugin authors, like\n"
                    + "# how many people use their plugin and their total player count. It's recommended to keep\n"
                    + "# bStats enabled, but if you're not comfortable with this, you can turn this setting off.\n"
                    + "# There is no performance penalty associated with having metrics enabled, and data sent to\n"
                    + "# bStats is fully anonymous.\n";

    private final boolean enabled;
    private final String serverUuid;
    private final boolean logFailedRequests;

    BStatsConfig(boolean enabled, String serverUuid, boolean logFailedRequests) {
        this.enabled = enabled;
        this.serverUuid = serverUuid;
        this.logFailedRequests = logFailedRequests;
    }

    public boolean enabled() {
        return enabled;
    }

    public String serverUuid() {
        return serverUuid;
    }

    public boolean logFailedRequests() {
        return logFailedRequests;
    }

    /**
     * Directory holding the shared bStats config: the sibling {@code bStats/} folder
     * of the plugin directory (e.g. {@code plugins/bStats}, {@code config/bStats}).
     */
    public static File bStatsDirectory(File pluginDirectory) {
        File parent = (pluginDirectory != null) ? pluginDirectory.getAbsoluteFile().getParentFile() : null;
        return new File(parent != null ? parent : new File("config"), "bStats");
    }

    /**
     * Loads the config from {@code bStatsDir}, preferring {@code config.yml} over
     * {@code config.txt}; creates one in {@code createFormat} when neither holds a
     * server UUID. I/O failure yields an enabled config with an ephemeral UUID.
     */
    public static BStatsConfig load(File bStatsDir, Format createFormat) {
        for (Format f : Format.values()) {
            Map<String, String> kv = read(new File(bStatsDir, f.fileName));
            String uuid = first(kv, "serverUuid", "server-uuid");
            if (uuid != null && !uuid.isBlank()) {
                return new BStatsConfig(
                        !"false".equalsIgnoreCase(kv.getOrDefault("enabled", "true")),
                        uuid,
                        "true".equalsIgnoreCase(first(kv, "logFailedRequests", "log-errors")));
            }
        }
        String uuid = UUID.randomUUID().toString();
        try {
            Files.createDirectories(bStatsDir.toPath());
            File target = new File(bStatsDir, createFormat.fileName);
            if (!target.exists()) {
                Files.writeString(target.toPath(), render(createFormat, uuid), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException ignored) {
            // Ephemeral UUID for this run; the next start retries the write.
        }
        return new BStatsConfig(true, uuid, false);
    }

    /**
     * True when any known bStats config location carries {@code enabled=false}
     * (either format). Checked on every submission so an opt-out takes effect
     * without a restart.
     */
    public static boolean isOptedOut(File pluginDirectory) {
        File[] dirs = {
                bStatsDirectory(pluginDirectory),
                new File(pluginDirectory != null ? pluginDirectory : new File("."), "bStats"),
                new File("plugins", "bStats"),
                new File("config", "bStats"),
                new File(".bStats")
        };
        for (File dir : dirs) {
            for (Format f : Format.values()) {
                String enabled = read(new File(dir, f.fileName)).get("enabled");
                if (enabled != null && "false".equalsIgnoreCase(enabled)) return true;
            }
        }
        return false;
    }

    static String render(Format format, String uuid) {
        if (format == Format.YAML) {
            return YAML_HEADER
                    + "enabled: true\n"
                    + "serverUuid: \"" + uuid + "\"\n"
                    + "logFailedRequests: false\n"
                    + "logSentData: false\n"
                    + "logResponseStatusText: false\n";
        }
        // Upstream base-library key names, so co-installed upstream plugins share the UUID.
        return "# bStats (https://bStats.org) collects some basic information for plugin authors, like\n"
                + "# how many people use their plugin and their total player count. It's recommended to keep\n"
                + "# bStats enabled, but if you're not comfortable with this, you can turn this setting off.\n"
                + "# There is no performance penalty associated with having metrics enabled, and data sent to\n"
                + "# bStats is fully anonymous.\n"
                + "enabled=true\nserver-uuid=" + uuid + "\nlog-errors=false\nlog-sent-data=false\n"
                + "log-response-status-text=false\n";
    }

    /** Flat {@code key: value} / {@code key=value} reader; comments and quotes stripped. */
    static Map<String, String> read(File file) {
        Map<String, String> out = new HashMap<>();
        if (file == null || !file.isFile()) return out;
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return out;
        }
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            int eq = line.indexOf('=');
            int sep = (colon < 0) ? eq : (eq < 0 ? colon : Math.min(colon, eq));
            if (sep <= 0) continue;
            String key = line.substring(0, sep).trim();
            String value = line.substring(sep + 1).trim();
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            out.putIfAbsent(key, value);
        }
        return out;
    }

    private static String first(Map<String, String> kv, String a, String b) {
        String v = kv.get(a);
        return (v != null) ? v : kv.get(b);
    }
}
