package io.github.dailystruggle.rtp.fabric.utils;

import io.github.dailystruggle.rtp.common.RTP;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

/**
 * Fabric counterpart to {@code io.github.dailystruggle.rtp.bukkit.utils.JarUtils}.
 *
 * <p>Seeds {@code <dataFolder>/docs/} with the bundled {@code docs/**} tree from
 * the running mod jar on first launch (and re-seeds when the recorded
 * {@code .version} file no longer matches the running mod version). Mirrors the
 * Bukkit utility one-to-one for Step E3-4 of {@code MULTI_PLATFORM_PLAN.md}; the
 * only deliberate divergences are:
 *
 * <ul>
 *   <li>Routes diagnostics through {@link RTP#log(Level, String)} /
 *       {@link RTP#log(Level, String, Throwable)} instead of the Bukkit
 *       {@code SendMessage.log} sink. {@code SendMessage} lives in
 *       {@code rtp-bukkit-common}'s {@code bukkitplatform} package and is not
 *       reachable from the Fabric entrypoint (rtp-fabric-ADR-002 section 4 forbids
 *       {@code org.bukkit.*} reach-through).</li>
 *   <li>Has no {@code JavaPlugin} dependency - the jar is discovered via
 *       {@code getProtectionDomain().getCodeSource().getLocation()} exactly as
 *       on Bukkit, which works the same under Fabric Loom remapping because
 *       the running jar is whichever {@code ProtectionDomain} this class was
 *       loaded from.</li>
 * </ul>
 *
 * <p>Idempotent: existing files are preserved unless the recorded version
 * differs from the running version (then they are overwritten so docs stay
 * in lockstep with the shipped jar). Failures are fail-soft and logged at
 * {@code WARNING} - docs extraction is a convenience, never a hard
 * prerequisite for {@code /rtp} (REQ-RTP-S-004: visible failure, no silent
 * swallow; the mod continues to run without extracted docs).
 *
 * @see io.github.dailystruggle.rtp.bukkit.utils.JarUtils
 */
public final class FabricJarUtils {

    private FabricJarUtils() {
        // utility - no instances
    }

    /**
     * Extract every {@code docs/**} entry from the running mod jar into
     * {@code <dataFolder>/docs/}, recording the running mod version in
     * {@code <dataFolder>/docs/.version}.
     *
     * @param dataFolder destination directory (typically the Fabric config
     *                   subdir, e.g. {@code <fabric-config>/rtp/}). May not
     *                   yet exist; sub-directories are created on demand.
     * @param version    the running mod version (used to gate
     *                   force-overwrite of stale extracted docs).
     */
    public static void extractDocs(File dataFolder, String version) {
        if (dataFolder == null) {
            RTP.log(Level.WARNING, "[RTP] FabricJarUtils.extractDocs called with null dataFolder; skipping.");
            return;
        }
        if (version == null) {
            version = "unknown";
        }

        File docsDir = new File(dataFolder, "docs");
        File docVersionFile = new File(docsDir, ".version");
        String currentVersion = version;
        String lastVersion = "";

        if (docVersionFile.exists()) {
            try {
                lastVersion = new String(Files.readAllBytes(docVersionFile.toPath())).trim();
            } catch (Exception ignored) {
                // best effort - treat as missing version marker
            }
        }

        boolean forceOverwrite = (!lastVersion.isBlank() && !currentVersion.equals(lastVersion));

        try {
            URI uri = FabricJarUtils.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            File jarFile = new File(uri);
            if (!jarFile.isFile()) {
                // Running from a classes directory (dev / unit-test) - there
                // is no jar to walk. Silently skip; this is the documented
                // dev-environment fallback and not an error.
                return;
            }
            try (JarFile jar = new JarFile(jarFile)) {
                Enumeration<JarEntry> entries = jar.entries();
                boolean extracted = false;
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.startsWith("docs/") || name.equals("docs/")) continue;
                    File outFile = new File(dataFolder, name);
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                        continue;
                    }
                    File parent = outFile.getParentFile();
                    if (parent != null) parent.mkdirs();
                    if (outFile.exists() && !forceOverwrite) continue;
                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        extracted = true;
                    }
                }
                if (extracted) {
                    if (forceOverwrite) {
                        RTP.log(Level.INFO,
                                "[RTP] Documentation updated to version: " + currentVersion);
                    } else {
                        RTP.log(Level.INFO,
                                "[RTP] Documentation extracted to: " + docsDir.getAbsolutePath());
                    }
                    try {
                        if (!docsDir.exists()) docsDir.mkdirs();
                        Files.write(docVersionFile.toPath(), currentVersion.getBytes());
                    } catch (Exception e) {
                        RTP.log(Level.WARNING, "[RTP] Failed to save documentation version", e);
                    }
                }
            }
        } catch (Exception e) {
            RTP.log(Level.WARNING, "[RTP] Failed to extract documentation", e);
        }
    }
}
