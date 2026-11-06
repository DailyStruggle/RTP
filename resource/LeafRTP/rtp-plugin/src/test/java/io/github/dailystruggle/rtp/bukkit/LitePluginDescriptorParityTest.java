package io.github.dailystruggle.rtp.bukkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-100 / ADR-108 - the Free and Supporter assemblies share a single unified
 * {@code src/main/resources/plugin.yml} descriptor and {@link RTPBukkitPlugin}
 * entrypoint, with no duplicate {@code src/lite/} overlay.
 */
@DisplayName("ADR-100 - single unified plugin.yml descriptor and permissions integrity")
class LitePluginDescriptorParityTest {

    private static final Path MAIN_DESCRIPTOR = Paths.get("src", "main", "resources", "plugin.yml");
    private static final Path LITE_DESCRIPTOR = Paths.get("src", "lite", "resources", "plugin.yml");
    private static final Path LITE_BOOTSTRAP = Paths.get(
            "src", "lite", "java", "io", "github", "dailystruggle", "rtp", "bukkit", "lite", "RTPBukkitLitePlugin.java");

    @Test
    @DisplayName("plugin.yml declares RTPBukkitPlugin and legacy src/lite overlay is absent")
    void singleUnifiedDescriptorAndBootstrap() throws IOException {
        Map<String, Object> main = load(MAIN_DESCRIPTOR);
        assertEquals("io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin", main.get("main"),
                "plugin.yml must point to the unified RTPBukkitPlugin entrypoint");
        assertFalse(Files.exists(LITE_DESCRIPTOR),
                "legacy duplicate src/lite/resources/plugin.yml must not exist");
        assertFalse(Files.exists(LITE_BOOTSTRAP),
                "legacy duplicate RTPBukkitLitePlugin.java must not exist");
    }

    @Test
    @DisplayName("rtp.onevent.* children are lower-case nodes declared in the same descriptor")
    void oneventChildrenAreDeclaredLowerCase() throws IOException {
        Map<String, Object> perms = permissions(load(MAIN_DESCRIPTOR));
        Object aggregate = perms.get("rtp.onevent.*");
        assertInstanceOf(Map.class, aggregate, MAIN_DESCRIPTOR + ": rtp.onevent.* missing");
        Object children = ((Map<?, ?>) aggregate).get("children");
        assertInstanceOf(Map.class, children, MAIN_DESCRIPTOR + ": rtp.onevent.* has no children");
        for (Object child : ((Map<?, ?>) children).keySet()) {
            String node = String.valueOf(child);
            // Core checks rtp.onevent.<event> with a lower-case event token.
            assertEquals(node.toLowerCase(Locale.ROOT), node,
                    MAIN_DESCRIPTOR + ": rtp.onevent.* child is not lower-case: " + node);
            assertTrue(perms.containsKey(node),
                    MAIN_DESCRIPTOR + ": rtp.onevent.* child is not declared: " + node);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> permissions(Map<String, Object> descriptor) {
        Object perms = descriptor.get("permissions");
        assertInstanceOf(Map.class, perms, "permissions: block missing");
        return (Map<String, Object>) perms;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "descriptor not found: " + path.toAbsolutePath());
        try (InputStream in = Files.newInputStream(path)) {
            Object parsed = new Yaml().load(in);
            assertInstanceOf(Map.class, parsed, path + " is not a YAML mapping");
            return (Map<String, Object>) parsed;
        }
    }
}
