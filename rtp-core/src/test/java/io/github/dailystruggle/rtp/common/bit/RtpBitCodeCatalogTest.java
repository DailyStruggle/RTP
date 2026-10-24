package io.github.dailystruggle.rtp.common.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitCodeNamespace;
import io.github.dailystruggle.bit.BitCodeRegistry;
import io.github.dailystruggle.bit.BitTier;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("REQ-API-F-009: BIT code catalog rendering and owner discovery")
class RtpBitCodeCatalogTest {
    @TempDir
    Path tempDir;

    @Test
    void markdownRendersNamespacesAndEscapesCells() {
        BitCodeRegistry registry = registryWithCode("owner|system\nsubsystem", "", "replace | with\nnothing");

        String markdown = RtpBitCodeCatalog.markdown(registry);

        assertTrue(markdown.contains("## Namespaces"));
        assertTrue(markdown.contains("| `TEST` |"));
        assertTrue(markdown.contains("## `TEST`"));
        assertTrue(markdown.contains("| 50000 | `TEST_SAMPLE` | WARNING | PBIT | owner\\|system<br>subsystem | - | replace \\| with<br>nothing |"));
    }

    @Test
    void declaredOwnersRejectsAMissingClassDirectory() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RtpBitCodeCatalog.declaredOwners(tempDir.resolve("missing"), getClass().getClassLoader()));

        assertTrue(failure.getMessage().contains("class output directory does not exist"));
    }

    @Test
    void declaredOwnersSkipsModuleAndPackageDescriptors() throws Exception {
        Path classes = Files.createDirectory(tempDir.resolve("classes"));
        Path packageDirectory = Files.createDirectories(classes.resolve("test"));
        String descriptor = "Lio/github/dailystruggle/bit/BitCode;";
        Files.writeString(classes.resolve("module-info.class"), descriptor);
        Files.writeString(packageDirectory.resolve("package-info.class"), descriptor);

        assertEquals(List.of(), RtpBitCodeCatalog.declaredOwners(classes, getClass().getClassLoader()));
    }

    @Test
    void declaredOwnersRejectsDescriptorClassThatCannotBeLoaded() throws Exception {
        Path classes = Files.createDirectory(tempDir.resolve("unloadable"));
        Path packageDirectory = Files.createDirectories(classes.resolve("test"));
        Files.writeString(packageDirectory.resolve("MissingOwner.class"), "Lio/github/dailystruggle/bit/BitCode;");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> RtpBitCodeCatalog.declaredOwners(classes, getClass().getClassLoader()));

        assertTrue(failure.getMessage().contains("BIT owner class could not be loaded: test.MissingOwner"));
    }

    @Test
    void jsonPrettyPrinterPreservesEscapedQuotesAndBackslashes() {
        String remediation = "A \"quoted\" path \\ folder";
        BitCodeRegistry registry = registryWithCode("", "", remediation);

        String json = RtpBitCodeCatalog.json(registry);

        assertTrue(json.contains("A \\\"quoted\\\" path \\\\ folder"));
    }

    @Test
    void emptyRetiredListRendersInlineWithoutWhitespaceOnlyLines() {
        String json = RtpBitCodeCatalog.json(registryWithCode("", "", ""));

        assertTrue(json.contains("\"retired\": []"), json);
        assertTrue(json.lines().noneMatch(line -> !line.isEmpty() && line.isBlank()),
                "generated catalog must not contain whitespace-only lines");
    }

    private static BitCodeRegistry registryWithCode(String subsystem, String messageKey, String remediation) {
        BitCodeRegistry registry = new BitCodeRegistry();
        BitCodeNamespace namespace = registry.reserve("TEST");
        namespace.register(50000, "TEST_SAMPLE", Level.WARNING, BitTier.PBIT,
                subsystem, messageKey, remediation);
        return registry;
    }
}
