package io.github.dailystruggle.rtp.bukkit.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitCodeRegistry;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodeCatalog;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-F-009 complete BIT catalog and probe coverage")
class BitCodeCatalogTest {
    private static final Set<String> KNOWN_WITHOUT_FIRING_TESTS = Set.of(
            "PROBE_ASYNC_REPLY_MOCKED_PIPELINE",
            "PROBE_ASYNC_REPLY_PIPELINE_NOT_REACHED",
            "PROBE_ASYNC_REPLY_PIPELINE_THROWN",
            "PROBE_ASYNC_REPLY_PLAYER_UNKNOWN",
            "PROBE_ASYNC_REPLY_PROCESSING_LEAK",
            "PROBE_ASYNC_REPLY_THREAD_UNSAFE",
            "PROBE_ASYNC_REPLY_WORLD_UNAVAILABLE",
            "PROBE_DISCONNECT_MIDFLIGHT_NO_WORLD",
            "PROBE_FOLIA_OWNERSHIP",
            "PROBE_FOLIA_OWNERSHIP_UNSUPPORTED",
            "PROBE_FOLIA_OWNERSHIP_WORLD_UNAVAILABLE",
            "PROBE_HOLOGRAM_CANCELLED",
            "PROBE_HOLOGRAM_CLOSE_FAILED",
            "PROBE_HOLOGRAM_CONSOLE_STATUS",
            "PROBE_HOLOGRAM_LOCATION_UNAVAILABLE",
            "PROBE_HOLOGRAM_NULL_HANDLE",
            "PROBE_HOLOGRAM_PLAYER_UNKNOWN",
            "PROBE_HOLOGRAM_SCHEDULER_UNAVAILABLE",
            "PROBE_HOLOGRAM_SPAWN_FAILED",
            "PROBE_HOLOGRAM_UPDATE_FAILED",
            "PROBE_QUEUE_STARVATION_CANCELLED",
            "PROBE_QUEUE_STARVATION_COMPUTE_THROWN",
            "PROBE_QUEUE_STARVATION_FAILURES",
            "PROBE_QUEUE_STARVATION_REGION_UNAVAILABLE",
            "PROBE_QUEUE_STARVATION_STALLS",
            "PROBE_STRESS_PLAYER_CANCELLED",
            "PROBE_STRESS_PROCESSING_LEAK",
            "PROBE_STRESS_REJECTED",
            "PROBE_STRESS_SET_LOCATION",
            "PROBE_STRESS_UNAVAILABLE",
            "TEST_ACCESSOR_BIOME",
            "TEST_ACCESSOR_FORMAT",
            "TEST_ACCESSOR_MENU",
            "TEST_ACCESSOR_MESSAGING",
            "TEST_ACCESSOR_SENDER",
            "TEST_ACCESSOR_SUBSYSTEM",
            "TEST_ACCESSOR_TAGS",
            "TEST_ACCESSOR_THREAD",
            "TEST_ACCESSOR_VERSION",
            "TEST_ACCESSOR_WORLD",
            "TEST_ACCESSOR_WORLD_BORDER",
            "TEST_CHUNK_TICKET_RELEASE",
            "TEST_CHUNK_TICKET_SENTINEL_DIAGNOSTICS",
            "TEST_CHUNK_TICKET_SENTINEL_UNTRACK",
            "TEST_CHUNK_TICKET_TRACKER_LEAK",
            "TEST_ECONOMY_ISOLATION_UNAVAILABLE",
            "TEST_NETWORK_UNAVAILABLE",
            "TEST_COMMANDS_NO_TREE");

    @Test
    void pluginFieldsHaveExplicitOwnersAndRegisterInTheCatalog() throws Exception {
        Path root = repositoryRoot();
        List<Class<?>> discovered = RtpBitCodeCatalog.declaredOwners(
                root.resolve("rtp-plugin/build/classes/java/main"), getClass().getClassLoader());
        assertEquals(BukkitBitCodeOwners.OWNERS.stream().sorted(Comparator.comparing(Class::getName)).toList(), discovered,
                "BukkitBitCodeOwners.OWNERS must list exactly the plugin classes declaring static BitCode fields");

        BitCodeRegistry registry = BitCodeCatalogExport.registry();
        List<Class<?>> owners = new ArrayList<>(RtpBitCodes.OWNERS);
        owners.addAll(BukkitBitCodeOwners.OWNERS);
        for (Class<?> owner : owners) {
            for (Field field : owner.getDeclaredFields()) {
                if (field.getType() != BitCode.class || !Modifier.isStatic(field.getModifiers())) continue;
                BitCode code = (BitCode) field.get(null);
                assertSame(code, registry.byId(code.id()).orElse(null), owner.getSimpleName() + "." + field.getName());
                assertSame(code, registry.byKey(code.key()).orElse(null), owner.getSimpleName() + "." + field.getName());
            }
        }
        assertEveryCatalogKeyIsInIdOrder(registry);
    }

    @Test
    void committedCatalogFilesMatchRegistryOutput() throws Exception {
        BitCodeCatalogExport.check(repositoryRoot());
    }

    @Test
    void everyNonEmptyMessageKeyResolvesInBundledMessageYaml() throws Exception {
        Path messages = repositoryRoot().resolve("rtp-plugin/src/main/resources/advanced/messages");
        List<String> yaml = new ArrayList<>();
        try (Stream<Path> files = Files.walk(messages)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".yml"))
                    .filter(path -> !path.getFileName().toString().endsWith(".lang.yml")).toList()) {
                yaml.add(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        for (BitCode code : BitCodeCatalogExport.registry().codes()) {
            if (code.messageKey().isBlank()) continue;
            String key = code.messageKey().substring(code.messageKey().lastIndexOf('.') + 1);
            Pattern yamlKey = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*:");
            assertTrue(yaml.stream().anyMatch(content -> yamlKey.matcher(content).find()),
                    code.key() + " references missing messages.yml key " + code.messageKey());
        }
    }

    @Test
    void everyCodeHasAFiringTestOrIsListedAsAnExplicitGap() throws Exception {
        Path root = repositoryRoot();
        List<Path> testSources = new ArrayList<>();
        collectJavaSources(root.resolve("rtp-core/src/test/java"), testSources);
        collectJavaSources(root.resolve("rtp-plugin/src/test/java"), testSources);

        List<Class<?>> owners = new ArrayList<>(RtpBitCodes.OWNERS);
        owners.addAll(BukkitBitCodeOwners.OWNERS);
        Set<String> uncovered = new TreeSet<>();
        for (Class<?> owner : owners) {
            for (Field field : owner.getDeclaredFields()) {
                if (field.getType() != BitCode.class || !Modifier.isStatic(field.getModifiers())) continue;
                BitCode code = (BitCode) field.get(null);
                if (!hasFiringTest(code, owner, field, testSources)) uncovered.add(code.key());
            }
        }
        assertEquals(KNOWN_WITHOUT_FIRING_TESTS, uncovered,
                "Update KNOWN_WITHOUT_FIRING_TESTS only with reviewed gaps; discovered uncovered codes: " + uncovered);
    }

    private static boolean hasFiringTest(BitCode code, Class<?> owner, Field field, List<Path> testSources)
            throws IOException {
        String fieldReference = owner.getSimpleName() + "." + field.getName();
        Pattern ownerWideTest = Pattern.compile("(?s)assert(?:EveryCodeFails|AllCodesFail)\\s*\\(\\s*"
                + Pattern.quote(owner.getSimpleName()) + "\\.class\\s*(?:,|\\))");
        for (Path testSource : testSources) {
            String source = Files.readString(testSource, StandardCharsets.UTF_8);
            if (ownerWideTest.matcher(source).find()) return true;
            boolean inKnownGapList = false;
            for (String line : source.split("\\R")) {
                if (line.contains("KNOWN_WITHOUT_FIRING_TESTS = Set.of(")) {
                    inKnownGapList = true;
                    continue;
                }
                if (inKnownGapList) {
                    if (line.trim().endsWith(");")) inKnownGapList = false;
                    continue;
                }
                if (line.contains(".containsId(") || line.contains(".byId(") || line.contains(".byKey(")) continue;
                if (line.contains("\"" + code.key() + "\"") || line.contains(fieldReference)) return true;
            }
        }
        return false;
    }

    private static void assertEveryCatalogKeyIsInIdOrder(BitCodeRegistry registry) {
        String json = RtpBitCodeCatalog.json(registry);
        int previousIndex = -1;
        for (BitCode code : registry.codes()) {
            int currentIndex = json.indexOf("\"key\": \"" + code.key() + "\"");
            assertTrue(currentIndex > previousIndex, code.key() + " must appear once in id order");
            previousIndex = currentIndex;
        }
    }

    private static void collectJavaSources(Path directory, List<Path> sources) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            sources.addAll(files.filter(path -> path.toString().endsWith(".java")).toList());
        }
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
