package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

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
 * Records what a run measured: every enabled plugin's version, jar SHA-256 and jar mtime, plus a
 * copy of each plugin's {@code *.yml} files as they were at run start. Version strings alone do
 * not identify a dev build, and the live config folder is overwritten between runs, so without
 * this a result cannot be tied to a jar or a shape mode afterwards.
 *
 * <p>{@link #snapshot()} reads the plugin list (any thread that may call the Bukkit plugin
 * manager); {@link #write} does the hashing and copying and must run off the tick thread.
 */
public final class RunProvenance {

    /** Data-folder files larger than this are not configs; skip them. */
    private static final long MAX_YML_BYTES = 1L << 20;
    private static final int MAX_DEPTH = 4;

    private record PluginInfo(String name, String version, Path jar, Path dataFolder) {}

    private final List<PluginInfo> plugins;
    private final long capturedEpochMs;

    private RunProvenance(List<PluginInfo> plugins, long capturedEpochMs) {
        this.plugins = plugins;
        this.capturedEpochMs = capturedEpochMs;
    }

    public static RunProvenance snapshot() {
        List<PluginInfo> out = new ArrayList<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (!p.isEnabled()) continue;
            Path jar = null;
            try {
                jar = Path.of(p.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            } catch (Exception | LinkageError e) {
                // Bundled or remapped plugins may have no code source; recorded as "unknown".
            }
            out.add(new PluginInfo(p.getName(), p.getDescription().getVersion(), jar, p.getDataFolder().toPath()));
        }
        return new RunProvenance(out, System.currentTimeMillis());
    }

    /**
     * Writes {@code <base>-provenance.txt} and {@code <base>-config/<plugin>/...} beside
     * {@code runCsv}; returns the provenance text so the ramp summary can embed it.
     * Per-file failures are written into the text, never dropped.
     */
    public String write(Path runCsv) throws IOException {
        String name = runCsv.getFileName().toString();
        String base = name.endsWith(".csv") ? name.substring(0, name.length() - 4) : name;
        Path txt = runCsv.resolveSibling(base + "-provenance.txt");
        Path cfgRoot = runCsv.resolveSibling(base + "-config");
        StringBuilder sb = new StringBuilder();
        sb.append("captured: ").append(Instant.ofEpochMilli(capturedEpochMs)).append('\n');
        sb.append("server: ").append(Bukkit.getName()).append(' ').append(Bukkit.getVersion()).append('\n');
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
