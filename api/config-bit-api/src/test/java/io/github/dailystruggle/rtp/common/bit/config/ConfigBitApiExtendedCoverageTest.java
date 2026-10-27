package io.github.dailystruggle.rtp.common.bit.config;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.Vocabulary;
import io.github.dailystruggle.rtp.common.configuration.ConfigDirectives;
import io.github.dailystruggle.rtp.common.selection.region.util.SpatialUnit;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("config-bit-api Comprehensive Coverage Suite")
class ConfigBitApiExtendedCoverageTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("MessageKeyParity ResourceReader.directory and ResourceReader.classpath (file + jar URLs)")
    void messageKeyParityResourceReadersAndCategories() throws IOException {
        Path resRoot = tempDir.resolve("res");
        Files.createDirectories(resRoot.resolve("advanced/messages"));
        Files.createDirectories(resRoot.resolve("lang/es/advanced/messages"));
        Files.createDirectories(resRoot.resolve("lang/shape"));

        Files.writeString(resRoot.resolve("config.yml"), "delay: 5\n", StandardCharsets.UTF_8);
        Files.writeString(resRoot.resolve(".config.lang.yml"), "delay: delay\n", StandardCharsets.UTF_8);
        Files.writeString(resRoot.resolve("advanced/safety.yml"), "water: false\n", StandardCharsets.UTF_8);
        Files.writeString(resRoot.resolve("advanced/.safety.lang.yml"), "water: water\n", StandardCharsets.UTF_8);
        Files.writeString(resRoot.resolve("advanced/messages/system.yml"), "reload: \"ok\"\n", StandardCharsets.UTF_8);
        Files.writeString(resRoot.resolve("advanced/messages/.system.lang.yml"), "reload: reload\n", StandardCharsets.UTF_8);

        Files.writeString(resRoot.resolve("lang/es/config.yml"), "retraso: 5\n", StandardCharsets.UTF_8);
        Files.writeString(resRoot.resolve("lang/es/.config.lang.yml"), "delay: retraso\n", StandardCharsets.UTF_8);
        Files.writeString(resRoot.resolve("lang/es/advanced/safety.yml"), "agua: false\n", StandardCharsets.UTF_8);
        Files.writeString(resRoot.resolve("lang/es/advanced/.safety.lang.yml"), "water: agua\n", StandardCharsets.UTF_8);
        // Missing value file for system in es -> LOCALE_VALUES_MISSING
        Files.writeString(resRoot.resolve("lang/es/advanced/messages/.system.lang.yml"), "reload: recargar\n", StandardCharsets.UTF_8);

        MessageKeyParity.ResourceReader dirReader = MessageKeyParity.ResourceReader.directory(resRoot);
        assertTrue(dirReader.isDirectory(""));
        assertTrue(dirReader.isDirectory("lang"));
        assertFalse(dirReader.isDirectory("nonexistent_dir"));
        assertTrue(dirReader.list("nonexistent_dir").isEmpty());
        assertTrue(dirReader.read("nonexistent.yml").isEmpty());

        List<String> cats = MessageKeyParity.categories(dirReader);
        assertEquals(List.of("advanced/messages/system", "advanced/safety", "config"), cats);
        assertEquals(List.of("es"), MessageKeyParity.locales(dirReader));

        List<BitFinding> findings = MessageKeyParity.bundledFindings(dirReader, cats, MessageParityCodes.DEFAULT);
        assertEquals(1, findings.size());
        assertEquals(MessageParityCodes.CFG_LOCALE_VALUES_MISSING.key(), findings.get(0).code());

        // Test classpath reader over exploded directory
        try (URLClassLoader fileCl = new URLClassLoader(new URL[]{resRoot.toUri().toURL()}, null)) {
            MessageKeyParity.ResourceReader cpReader = MessageKeyParity.ResourceReader.classpath(fileCl);
            assertTrue(cpReader.exists("config.yml"));
            assertFalse(cpReader.exists("absent.yml"));
            assertTrue(cpReader.isDirectory("lang"));
            assertEquals(List.of("es"), MessageKeyParity.locales(cpReader));
            assertEquals(1, MessageParityCodes.DEFAULT.bundledFindings(cpReader).size());
        }

        // Test classpath reader over a JAR file
        Path jarFile = tempDir.resolve("bundle.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarFile))) {
            addJarEntry(jos, "lang/", null);
            addJarEntry(jos, "lang/fr/", null);
            addJarEntry(jos, "lang/fr/config.yml", "delai: 5\n");
            addJarEntry(jos, "config.yml", "delay: 5\n");
        }
        try (URLClassLoader jarCl = new URLClassLoader(new URL[]{jarFile.toUri().toURL()}, null)) {
            MessageKeyParity.ResourceReader jarReader = MessageKeyParity.ResourceReader.classpath(jarCl);
            assertTrue(jarReader.exists("config.yml"));
            assertEquals(List.of("fr"), MessageKeyParity.locales(jarReader));
        }

        // Corrupt YAML in topLevel throws IllegalArgumentException
        MessageKeyParity.ResourceReader badReader = MessageKeyParity.ResourceReader.of(
                Map.of("bad.yml", "a: [unclosed\n"));
        assertThrows(IllegalArgumentException.class, () -> MessageKeyParity.topLevel(badReader, "bad.yml"));

        // Empty reader categories
        MessageKeyParity.ResourceReader emptyReader = MessageKeyParity.ResourceReader.of(Map.of());
        assertTrue(MessageKeyParity.categories(emptyReader).isEmpty());
        assertTrue(MessageParityCodes.DEFAULT.bundledFindings(null, null).isEmpty());
        assertTrue(MessageParityCodes.DEFAULT.operatorFindings(null, "sys", null).isEmpty());
    }

    private static void addJarEntry(JarOutputStream jos, String name, String content) throws IOException {
        jos.putNextEntry(new JarEntry(name));
        if (content != null) {
            jos.write(content.getBytes(StandardCharsets.UTF_8));
        }
        jos.closeEntry();
    }

    @Test
    @DisplayName("DirectiveValidator covers all scalar, list, map, section, unit, and classpath branches")
    void directiveValidatorAllBranches() throws IOException {
        DirectiveValidator validator = new DirectiveValidator(DirectiveCodes.DEFAULT);
        assertEquals(DirectiveCodes.DEFAULT, validator.codes());
        assertTrue(DirectiveValidator.validate(null, "a: 1\n", DirectiveCodes.DEFAULT).isEmpty());
        assertTrue(DirectiveValidator.validate(
                ConfigSnapshot.of(Map.of("a.yml", "a: 1\n")).document("a.yml").orElseThrow(),
                null,
                DirectiveCodes.DEFAULT).isEmpty());

        // Unparsable bundled YAML produces CFG_MALFORMED
        ConfigSnapshot.Document doc0 = ConfigSnapshot.of(Map.of("a.yml", "a: 1\n")).document("a.yml").orElseThrow();
        List<BitFinding> badBundled = validator.check(doc0, "a: [unclosed\n");
        assertEquals(1, badBundled.size());
        assertEquals(DirectiveCodes.CFG_MALFORMED.key(), badBundled.get(0).code());

        String bundled = """
                # @type: boolean
                # @pattern: "^(true|false)$"
                boolKey: true

                # @type: number
                # @range: [0.0, 10.0]
                numKey: 5.5

                # @type: integer
                # @unit: seconds
                # @pattern: "^[0-9]+s?$"
                durKey: 10s

                # @type: integer
                # @options: ["auto", "10"]
                intOptKey: 10

                # @type: list<integer>
                # @range: [1, 100]
                intList:
                  - 10
                  - 20

                # @type: map
                mapKey:
                  k: v

                # @type: enum
                # @options: ["FAST", "SAFE"]
                modeKey: "FAST"

                # @range: [1, 5]
                untypedRangeKey: 3
                """;

        // 1. Valid disk values
        ConfigSnapshot validSnap = ConfigSnapshot.of(Map.of("test.yml", """
                boolKey: "false"
                numKey: 3.25
                durKey: "30s"
                intOptKey: "auto"
                intList:
                  - 5
                  - 50
                mapKey:
                  nested: 1
                modeKey: "SAFE"
                untypedRangeKey: 4
                """));
        assertTrue(validator.check(validSnap.document("test.yml").orElseThrow(), bundled).isEmpty());

        // 2. Type mismatches and violations across all branches
        ConfigSnapshot invalidSnap = ConfigSnapshot.of(Map.of("test.yml", """
                boolKey: "notABoolean"
                numKey: "notANumber"
                durKey: true
                intOptKey: "unknownOption"
                intList:
                  nestedSectionInsteadOfList: 1
                mapKey:
                  - "listInsteadOfMap"
                modeKey:
                  - "listInsteadOfScalar"
                untypedRangeKey: 99
                boolKeyTpo: true
                """));
        List<BitFinding> findings = validator.check(invalidSnap.document("test.yml").orElseThrow(), bundled);
        assertTrue(findings.size() >= 7);

        // 3. List with nested structure inside element and non-whole integer
        ConfigSnapshot listNestedSnap = ConfigSnapshot.of(Map.of("test.yml", """
                numKey: 5.5
                intList:
                  - [1, 2]
                """));
        List<BitFinding> listNestedFindings = validator.check(
                listNestedSnap.document("test.yml").orElseThrow(), bundled);
        assertEquals(1, listNestedFindings.size());
        assertEquals("intList[0]", listNestedFindings.get(0).path());

        // Non-whole integer on integer type
        String intBundled = "# @type: integer\ncount: 5\n";
        ConfigSnapshot floatForInt = ConfigSnapshot.of(Map.of("test.yml", "count: 5.5\n"));
        List<BitFinding> floatFindings = validator.check(
                floatForInt.document("test.yml").orElseThrow(), intBundled);
        assertEquals(1, floatFindings.size());
        assertEquals(DirectiveCodes.CFG_TYPE_MISMATCH.key(), floatFindings.get(0).code());

        // Validate via ClassLoader
        Path cpDir = tempDir.resolve("cp-dir");
        Files.createDirectories(cpDir);
        Files.writeString(cpDir.resolve("test.yml"), intBundled, StandardCharsets.UTF_8);
        try (URLClassLoader cl = new URLClassLoader(new URL[]{cpDir.toUri().toURL()}, null)) {
            assertEquals(1, validator.check(floatForInt, "test.yml", cl).size());
            assertTrue(validator.check(floatForInt, "missing.yml", cl).isEmpty());
            assertTrue(validator.check(null, "test.yml", cl).isEmpty());
        }
    }

    @Test
    @DisplayName("DirectiveSchemaParser.parse(List) and DirectiveSchemaParser.from(ConfigDirectives)")
    void directiveSchemaParserLinesAndEmpty() {
        assertEquals(DirectiveSchemaParser.EMPTY, DirectiveSchemaParser.from(null));
        assertEquals(DirectiveSchemaParser.EMPTY, DirectiveSchemaParser.from(ConfigDirectives.parse("# Just a plain summary comment")));
        assertEquals(DirectiveSchemaParser.EMPTY, DirectiveSchemaParser.parse((String) null));
        assertEquals(DirectiveSchemaParser.EMPTY, DirectiveSchemaParser.parse("   "));
        assertEquals(DirectiveSchemaParser.EMPTY, DirectiveSchemaParser.parse((List<String>) null));
        assertEquals(DirectiveSchemaParser.EMPTY, DirectiveSchemaParser.parse(List.of()));

        DirectiveSchemaParser fromLines = DirectiveSchemaParser.parse(
                List.of("# Summary", "# @type: integer", "# @range: [1, 10]"));
        assertEquals("integer", fromLines.type());
        assertTrue(fromLines.hasRange());
        assertFalse(fromLines.hasPattern());
    }

    @Test
    @DisplayName("ConfigSnapshot handles unparsable documents, unparsable lang maps, shared parent lang maps, and accessors")
    void configSnapshotEdgeCases() {
        ConfigSnapshot empty = ConfigSnapshot.read(tempDir.resolve("does-not-exist"));
        assertTrue(empty.documents().isEmpty());

        ConfigSnapshot snap = ConfigSnapshot.of(Map.of(
                "./definitions/regions/default.yml", "monde: overworld\n",
                "/definitions/.regions.lang.yml", "world: monde\n",
                "broken.yml", "a: [unclosed\n",
                ".broken.lang.yml", "b: [unclosed\n"));

        assertTrue(snap.root().isEmpty());
        assertTrue(snap.text("broken.yml").isPresent());
        assertTrue(snap.unparsable().containsKey("broken.yml"));
        assertTrue(snap.unparsable().containsKey(".broken.lang.yml"));
        assertFalse(snap.unparsableKeys().isEmpty());
        assertTrue(snap.exists("broken.yml"));
        assertTrue(snap.files().contains("broken.yml"));
        assertSame(snap, snap.withOverlay(Map.of()));
        assertSame(snap, snap.withOverlay(null));

        ConfigSnapshot.Document regionDoc = snap.document("definitions/regions/default.yml").orElseThrow();
        assertEquals("overworld", regionDoc.get("world"));
        assertEquals("monde", regionDoc.localizedKey("world"));
        assertEquals("unmappedKey", regionDoc.localizedKey("unmappedKey"));
        assertEquals(1, snap.documentsIn("definitions/regions").size());
    }

    @Test
    @DisplayName("ConfigConstraint and ReferenceSettingCheck cover nested blocks, equalAllowed, and fractional distances")
    void configConstraintAndReferenceSettingEdgeCases() {
        assertTrue(new ConfigConstraint(
                "config.yml", null, "min", "max", true, null, DirectiveCodes.CFG_OUT_OF_RANGE)
                .evaluate(null).isEmpty());

        ConfigConstraint nestedEqual = new ConfigConstraint(
                "config.yml", "outer.inner", "min", "max", true,
                SpatialUnit.BLOCK, DirectiveCodes.CFG_OUT_OF_RANGE);

        ConfigSnapshot failing = ConfigSnapshot.of(Map.of(
                "config.yml", """
                        outer:
                          inner:
                            min: 20.5b
                            max: 10.25b
                        """));
        List<BitFinding> findings = nestedEqual.evaluate(failing);
        assertEquals(1, findings.size());
        assertEquals("outer.inner.min", findings.get(0).path());
        assertTrue(findings.get(0).message().contains("10.25"));

        // Non-map intermediate block, reference values, and booleans are safely skipped
        ConfigSnapshot skipped = ConfigSnapshot.of(Map.of(
                "config.yml", """
                        outer: "scalarInsteadOfMap"
                        """));
        assertTrue(nestedEqual.evaluate(skipped).isEmpty());

        ConfigSnapshot refAndBool = ConfigSnapshot.of(Map.of(
                "config.yml", """
                        outer:
                          inner:
                            min: "@config"
                            max: true
                        """));
        assertTrue(nestedEqual.evaluate(refAndBool).isEmpty());
        assertNull(ConfigConstraint.asMap("scalar"));
        assertEquals(Map.of("a", 1), ConfigConstraint.asMap(Map.of("a", 1)));

        // ReferenceSettingCheck constructors and methods
        Vocabulary vocab = new Vocabulary("files", List.of("config", "economy"));
        ReferenceSettingCheck check = new ReferenceSettingCheck(vocab, ReferenceSettingCheck.Codes.DEFAULT);
        assertEquals(vocab, check.referenceFiles());
        assertEquals(ReferenceSettingCheck.Codes.DEFAULT, check.codes());
        assertNull(ReferenceSettingCheck.referencedFile(123));
        assertNull(ReferenceSettingCheck.referencedFile("@"));
        assertNull(ReferenceSettingCheck.referencedFile("@   "));
        assertFalse(ReferenceSettingCheck.isReference(null));

        assertTrue(check.evaluate(null, List.of(), Set.of()).isEmpty());
        assertTrue(check.evaluate(failing, null, Set.of()).isEmpty());
        List<BitFinding> out = new ArrayList<>();
        check.checkDocument(null, null, (f, s) -> Optional.empty(), out);
        assertTrue(out.isEmpty());

        // Check @config resolution when config.yml has defaults section
        ConfigSnapshot refSnap = ConfigSnapshot.of(Map.of(
                "config.yml", "defaults:\n  shape: CIRCLE\n",
                "region.yml", "shape: \"@config\"\nmissingInDefaults: \"@config\"\nmissingFileRef: \"@economy\"\n"));
        List<BitFinding> refFindings = check.checkDocument(
                refSnap.document("region.yml").orElseThrow(),
                Set.of("nonexistentSection"),
                ReferenceSettingCheck.snapshotResolver(refSnap));
        assertEquals(1, refFindings.size());
        assertEquals("missingInDefaults", refFindings.get(0).path());
    }
}
