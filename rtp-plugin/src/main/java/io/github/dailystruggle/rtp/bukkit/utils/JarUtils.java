package io.github.dailystruggle.rtp.bukkit.utils;

import io.github.dailystruggle.rtp.spigot.tools.SendMessage;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

public class JarUtils {
    public static void extractDocs(File dataFolder, String version) {
        File docsDir = new File(dataFolder, "docs");
        File docVersionFile = new File(docsDir, ".version");
        String currentVersion = version;
        String lastVersion = "";

        if (docVersionFile.exists()) {
            try {
                lastVersion = new String(Files.readAllBytes(docVersionFile.toPath())).trim();
            } catch (Exception ignored) {
            }
        }

        boolean forceOverwrite = !currentVersion.equals(lastVersion);

        try {
            URI uri = JarUtils.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            try (JarFile jar = new JarFile(new File(uri))) {
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
                    outFile.getParentFile().mkdirs();
                    if (outFile.exists() && !forceOverwrite) continue;
                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, outFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        extracted = true;
                    }
                }
                if (extracted) {
                    if (forceOverwrite) {
                        SendMessage.log(Level.INFO, "#55FF55Documentation updated to version: #AAFFAA" + currentVersion);
                    } else {
                        SendMessage.log(Level.INFO, "#55FF55Documentation extracted to: #AAFFAA" + docsDir.getAbsolutePath());
                    }
                    try {
                        Files.write(docVersionFile.toPath(), currentVersion.getBytes());
                    } catch (Exception e) {
                        SendMessage.log(Level.WARNING, "#FFAA00Failed to save documentation version", e);
                    }
                }
            }
        } catch (Exception e) {
            SendMessage.log(Level.WARNING, "#FFAA00Failed to extract documentation", e);
        }
    }
}
