package io.github.dailystruggle.helpers.stresstestrtp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Records what a run measured: every enabled plugin/mod's version, jar SHA-256 and jar mtime,
 * plus a copy of each plugin's {@code *.yml} files as they were at run start (ADR-122 Decision 1 & 2).
 */
public final class RunProvenance {

    private static final long MAX_YML_BYTES = 1L << 20;
    private static final int MAX_DEPTH = 4;

    private record PluginInfo(String name, String version, Path jar, Path dataFolder) {}

    private final StressHost host;
    private final List<PluginInfo> plugins;
    private final long capturedEpochMs;

    private RunProvenance(StressHost host, List<PluginInfo> plugins, long capturedEpochMs) {
        this.host = host;
        this.plugins = plugins;
        this.capturedEpochMs = capturedEpochMs;
    }

    public static RunProvenance snapshot() {
        return snapshot(Sched.boundHost());
    }

    public static RunProvenance snapshot(StressHost host) {
        List<PluginInfo> out = new ArrayList<>();
        if (host != null) {
            for (StressHost.LoadedPlugin p : host.loadedPlugins()) {
                if (p == null || !p.enabled()) continue;
                out.add(new PluginInfo(p.name(), p.version(), p.jarPath(), p.dataFolder()));
            }
        }
        return new RunProvenance(host, out, System.currentTimeMillis());
    }

    public String write(Path runCsv) throws IOException {
        String name = runCsv.getFileName().toString();
        String base = name.endsWith(".csv") ? name.substring(0, name.length() - 4) : name;
        Path txt = runCsv.resolveSibling(base + "-provenance.txt");
        Path cfgRoot = runCsv.resolveSibling(base + "-config");
        StringBuilder sb = new StringBuilder();
        sb.append("captured: ").append(Instant.ofEpochMilli(capturedEpochMs)).append('\n');
        String serverName = host != null ? host.getServerName() : "unknown";
        String serverVer = host != null ? host.getServerVersion() : "unknown";
        sb.append("server: ").append(serverName).append(' ').append(serverVer).append('\n');
        sb.append("config_snapshot: ").append(cfgRoot.getFileName()).append('\n');
        for (PluginInfo p : plugins) {
            sb.append("plugin: ").append(p.name()).append(" version=").append(p.version());
            if (p.jar() == null || !Files.isRegularFile(p.jar())) {
                sb.append(" jar=unknown");
            } else {
                sb.append(" jar=").append(p.jar().getFileName());
                try {
                    sb.append(" sha256=").append(sha256(p.jar()));
                    sb.append(" mtime=").append(Files.getLastModifiedTime(p.jar()).toInstant());
                } catch (IOException e) {
                    sb.append(" hash_error=").append(e.getClass().getSimpleName());
                }
            }
            sb.append('\n');
            int copied = copyYml(p.dataFolder(), cfgRoot.resolve(p.name()), sb);
            if (copied > 0) sb.append("  config_files: ").append(copied).append('\n');
        }
        Files.writeString(txt, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return sb.toString();
    }

    private static int copyYml(Path src, Path dst, StringBuilder sb) {
        if (src == null || !Files.isDirectory(src)) return 0;
        int n = 0;
        try (Stream<Path> walk = Files.walk(src, MAX_DEPTH)) {
            for (Path f : (Iterable<Path>) walk::iterator) {
                String fn = f.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!fn.endsWith(".yml") && !fn.endsWith(".yaml")) continue;
                if (!Files.isRegularFile(f) || Files.size(f) > MAX_YML_BYTES) continue;
                try {
                    Path to = dst.resolve(src.relativize(f).toString());
                    Files.createDirectories(to.getParent());
                    Files.copy(f, to, StandardCopyOption.REPLACE_EXISTING);
                    n++;
                } catch (IOException e) {
                    sb.append("  copy_error: ").append(src.relativize(f)).append(' ')
                            .append(e.getClass().getSimpleName()).append('\n');
                }
            }
        } catch (IOException | RuntimeException e) {
            sb.append("  walk_error: ").append(e.getClass().getSimpleName()).append('\n');
        }
        return n;
    }

    private static String sha256(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }
}
