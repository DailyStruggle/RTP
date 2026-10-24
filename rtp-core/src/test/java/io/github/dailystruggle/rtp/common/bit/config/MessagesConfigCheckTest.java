package io.github.dailystruggle.rtp.common.bit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.rtp.api.configuration.enums.SystemMessages;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ADR-121 config PBIT messages owner (REQ-RTP-F-013 locale parity, operator message keys)")
class MessagesConfigCheckTest {
    private static final String CATEGORY = "advanced/messages/system";

    /** Parity-clean tree: baseline + one fr locale for system.yml. */
    private static Map<String, String> cleanTree() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("advanced/messages/system.yml", "reloading: \"Reloading\"\nreloaded: \"Reloaded\"\nversion: 1\n");
        files.put("advanced/messages/.system.lang.yml", "reloading: reloading\nreloaded: reloaded\nversion: version\n");
        files.put("lang/fr/advanced/messages/system.yml", "rechargement: \"Rechargement\"\nreloaded: \"Fini\"\nversion: 1\n");
        files.put("lang/fr/advanced/messages/.system.lang.yml", "reloading: rechargement\nreloaded: reloaded\nversion: version\n");
        files.put("lang/shape/.CIRCLE.lang.yml", "radius: radius\n");
        return files;
    }

    private static List<BitFinding> bundled(Map<String, String> files) {
        return MessagesConfigCheck.bundledFindings(MessageKeyParity.ResourceReader.of(files));
    }

    private static List<BitFinding> withCode(List<BitFinding> findings, String code) {
        return findings.stream().filter(finding -> finding.code().equals(code)).toList();
    }

    @Test
    @DisplayName("ADR-121 parity-clean bundled tree raises no RTP_MESSAGES_* finding; non-locale dirs are skipped")
    void cleanTreeHasNoFindings() {
        List<BitFinding> findings = bundled(cleanTree());
        assertTrue(findings.isEmpty(), findings.toString());
        assertEquals(List.of("fr"), MessageKeyParity.locales(MessageKeyParity.ResourceReader.of(cleanTree())));
    }

    @Test
    @DisplayName("ADR-121 RTP_MESSAGES_BASELINE_UNMAPPED fires for a baseline key without a rename-map row")
    void baselineUnmapped() {
        Map<String, String> files = cleanTree();
        files.put("advanced/messages/.system.lang.yml", "reloading: reloading\nversion: version\n");
        List<BitFinding> findings = withCode(bundled(files), "RTP_MESSAGES_BASELINE_UNMAPPED");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("reloaded", findings.get(0).path());
        assertEquals(Level.WARNING, findings.get(0).severity());
    }

    @Test
    @DisplayName("ADR-121 RTP_MESSAGES_LOCALE_VALUES_MISSING fires for a locale rename map without a value file")
    void localeValuesMissing() {
        Map<String, String> files = cleanTree();
        files.remove("lang/fr/advanced/messages/system.yml");
        List<BitFinding> findings = withCode(bundled(files), "RTP_MESSAGES_LOCALE_VALUES_MISSING");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("lang/fr/advanced/messages/system.yml", findings.get(0).source());

        files.remove("lang/fr/advanced/messages/.system.lang.yml");
        files.put("lang/fr/config.yml", "a: 1\n");
        assertTrue(bundled(files).isEmpty(), "a locale that ships neither file is not a gap");
    }

    @Test
    @DisplayName("ADR-121 RTP_MESSAGES_LOCALE_KEY_MISSING fires under the translated name; WARNING (English fallback)")
    void localeKeyMissing() {
        Map<String, String> files = cleanTree();
        files.put("lang/fr/advanced/messages/system.yml", "reloaded: \"Fini\"\nversion: 1\n");
        List<BitFinding> findings = withCode(bundled(files), "RTP_MESSAGES_LOCALE_KEY_MISSING");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("rechargement", findings.get(0).path());
        assertEquals(Level.WARNING, findings.get(0).severity());
        assertTrue(findings.get(0).message().contains("reloading"));
    }

    @Test
    @DisplayName("ADR-121 RTP_MESSAGES_LOCALE_STALE_ROW fires for a locale row the baseline does not ship")
    void localeStaleRow() {
        Map<String, String> files = cleanTree();
        files.put("lang/fr/advanced/messages/.system.lang.yml",
                "reloading: rechargement\nreloaded: reloaded\nversion: version\nremoved: removed\n");
        List<BitFinding> findings = withCode(bundled(files), "RTP_MESSAGES_LOCALE_STALE_ROW");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("removed", findings.get(0).path());
    }

    @Test
    @DisplayName("ADR-121 RTP_MESSAGES_LOCALE_UNRESOLVED_ROW fires when a rename target is absent from the value file")
    void localeUnresolvedRow() {
        Map<String, String> files = cleanTree();
        files.put("lang/fr/advanced/messages/.system.lang.yml",
                "reloading: rechargement\nreloaded: termine\nversion: version\n");
        List<BitFinding> findings = bundled(files);
        List<BitFinding> unresolved = withCode(findings, "RTP_MESSAGES_LOCALE_UNRESOLVED_ROW");
        assertEquals(1, unresolved.size(), findings.toString());
        assertEquals("reloaded", unresolved.get(0).path());
        assertEquals("termine", unresolved.get(0).expected());
        assertEquals(1, withCode(findings, "RTP_MESSAGES_LOCALE_KEY_MISSING").size(), findings.toString());
    }

    @Test
    @DisplayName("ADR-121 RTP_MESSAGES_KEY_MISSING fires for an operator file lacking a canonical enum key")
    void operatorKeyMissing() {
        Map<String, String> disk = new LinkedHashMap<>();
        StringBuilder system = new StringBuilder();
        for (SystemMessages key : SystemMessages.values()) {
            if (key != SystemMessages.reloaded) system.append(key.name()).append(": \"x\"\n");
        }
        disk.put("advanced/messages/system.yml", system.toString());
        List<BitFinding> findings = MessagesConfigCheck.operatorFindings(ConfigSnapshot.of(disk),
                MessageKeyParity.ResourceReader.of(Map.of()));
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("RTP_MESSAGES_KEY_MISSING", findings.get(0).code());
        assertEquals("reloaded", findings.get(0).path());
        assertEquals(Level.WARNING, findings.get(0).severity());
    }

    @Test
    @DisplayName("ADR-121 RTP_MESSAGES_KEY_UNKNOWN fires with a suggestion; localized keys resolve via the rename map")
    void operatorKeyUnknown() {
        Map<String, String> disk = new LinkedHashMap<>();
        StringBuilder system = new StringBuilder();
        for (SystemMessages key : SystemMessages.values()) {
            String name = key == SystemMessages.reloading ? "rechargement" : key.name();
            system.append(name).append(": \"x\"\n");
        }
        system.append("reloadedd: \"typo\"\n");
        disk.put("advanced/messages/system.yml", system.toString());
        disk.put("advanced/messages/.system.lang.yml", "reloading: rechargement\n");
        List<BitFinding> findings = MessagesConfigCheck.operatorFindings(ConfigSnapshot.of(disk),
                MessageKeyParity.ResourceReader.of(Map.of()));
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("RTP_MESSAGES_KEY_UNKNOWN", findings.get(0).code());
        assertEquals("reloadedd", findings.get(0).path());
        assertTrue(findings.get(0).suggestion().contains("reloaded"), findings.get(0).suggestion());
    }

    @Test
    @DisplayName("ADR-121 absent operator message files and a bare classpath raise nothing")
    void absentInputsAreSilent() {
        List<BitFinding> findings = MessagesConfigCheck.INSTANCE.check(ConfigSnapshot.of(Map.of("config.yml", "a: 1\n")),
                ConfigEnvironment.HEADLESS);
        assertTrue(withCode(findings, "RTP_MESSAGES_KEY_MISSING").isEmpty(), findings.toString());
        assertTrue(withCode(findings, "RTP_MESSAGES_KEY_UNKNOWN").isEmpty(), findings.toString());
    }

    @Test
    @DisplayName("ADR-121 classpath and directory readers list and read the same tree (RTP_MESSAGES_LOCALE_KEY_MISSING)")
    void classpathAndDirectoryReadersAgree(@TempDir Path root) throws IOException {
        Map<String, String> files = cleanTree();
        files.put("lang/fr/advanced/messages/system.yml", "reloaded: \"Fini\"\nversion: 1\n");
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = root.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
        }
        List<BitFinding> fromDirectory = MessagesConfigCheck.bundledFindings(MessageKeyParity.ResourceReader.directory(root));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            List<BitFinding> fromClasspath = MessagesConfigCheck.INSTANCE.check(ConfigSnapshot.of(Map.of()),
                    new ConfigEnvironment(null, null, loader));
            assertEquals(fromDirectory, fromClasspath);
        }
        assertEquals(1, withCode(fromDirectory, "RTP_MESSAGES_LOCALE_KEY_MISSING").size(), fromDirectory.toString());
        assertEquals(List.of(CATEGORY), MessageKeyParity.categories(MessageKeyParity.ResourceReader.directory(root)));
    }

    @Test
    @DisplayName("ADR-121 shipped messages tree and baseline files produce no RTP_MESSAGES_* ERROR or WARNING")
    void shippedDefaultsAreClean() throws IOException {
        Path resources = repositoryRoot().resolve("rtp-plugin/src/main/resources");
        MessageKeyParity.ResourceReader reader = MessageKeyParity.ResourceReader.directory(resources);
        List<BitFinding> findings = MessagesConfigCheck.bundledFindings(reader);
        Map<String, String> disk = new LinkedHashMap<>();
        for (String file : MessageKeyParity.MESSAGE_FILES) {
            String path = MessageKeyParity.MESSAGES_DIR + "/" + file + ".yml";
            disk.put(path, Files.readString(resources.resolve(path), StandardCharsets.UTF_8));
        }
        findings = new java.util.ArrayList<>(findings);
        findings.addAll(MessagesConfigCheck.operatorFindings(ConfigSnapshot.of(disk), reader));
        assertTrue(findings.isEmpty(), findings.toString());
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("could not locate repository root");
        return current;
    }
}
