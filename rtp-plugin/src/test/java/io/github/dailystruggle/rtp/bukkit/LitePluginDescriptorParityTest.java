package io.github.dailystruggle.rtp.bukkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ADR-100 - the lite and Pro assemblies are at parity, so the lite
 * {@code plugin.yml} overlay shall differ from the Pro descriptor only in its
 * {@code main:} entry point. Bukkit reads permission defaults and children from
 * the descriptor, so any drift silently changes who can run a subcommand or
 * receive auto-RTP on the lite jar.
 */
@DisplayName("ADR-100 - lite plugin.yml matches the Pro plugin.yml except for main")
class LitePluginDescriptorParityTest {

    private static final Path MAIN_DESCRIPTOR = Paths.get("src", "main", "resources", "plugin.yml");
    private static final Path LITE_DESCRIPTOR = Paths.get("src", "lite", "resources", "plugin.yml");

    @Test
    @DisplayName("permissions: block is identical (nodes, defaults, descriptions, children)")
    void permissionsBlockMatches() throws IOException {
        Map<String, Object> mainPerms = permissions(load(MAIN_DESCRIPTOR));
        Map<String, Object> litePerms = permissions(load(LITE_DESCRIPTOR));

        List<String> diffs = new ArrayList<>();
        Set<String> nodes = new TreeSet<>(mainPerms.keySet());
        nodes.addAll(litePerms.keySet());
        for (String node : nodes) {
            Object m = mainPerms.get(node);
            Object l = litePerms.get(node);
            if (m == null) {
                diffs.add("lite-only node: " + node);
            } else if (l == null) {
                diffs.add("missing from lite: " + node);
            } else if (!Objects.equals(m, l)) {
                diffs.add(node + "\n      main: " + m + "\n      lite: " + l);
            }
        }
        if (!diffs.isEmpty()) {
            fail("rtp-plugin/src/lite/resources/plugin.yml permissions: drifted from "
                    + "src/main/resources/plugin.yml (ADR-100):\n  " + String.join("\n  ", diffs));
        }
    }

    @Test
    @DisplayName("every other top-level key except main is identical")
    void otherTopLevelKeysMatch() throws IOException {
        Map<String, Object> main = new LinkedHashMap<>(load(MAIN_DESCRIPTOR));
        Map<String, Object> lite = new LinkedHashMap<>(load(LITE_DESCRIPTOR));
        Object mainEntry = main.remove("main");
        Object liteEntry = lite.remove("main");

        assertNotEquals(mainEntry, liteEntry, "lite descriptor must name its own entry point");
        assertEquals(main, lite, "lite plugin.yml differs from the Pro descriptor outside main:");
    }

    @Test
    @DisplayName("rtp.onevent.* children are lower-case nodes declared in the same descriptor")
    void oneventChildrenAreDeclaredLowerCase() throws IOException {
        for (Path descriptor : List.of(MAIN_DESCRIPTOR, LITE_DESCRIPTOR)) {
            Map<String, Object> perms = permissions(load(descriptor));
            Object aggregate = perms.get("rtp.onevent.*");
            assertInstanceOf(Map.class, aggregate, descriptor + ": rtp.onevent.* missing");
            Object children = ((Map<?, ?>) aggregate).get("children");
            assertInstanceOf(Map.class, children, descriptor + ": rtp.onevent.* has no children");
            for (Object child : ((Map<?, ?>) children).keySet()) {
                String node = String.valueOf(child);
                // Core checks rtp.onevent.<event> with a lower-case event token.
                assertEquals(node.toLowerCase(Locale.ROOT), node,
                        descriptor + ": rtp.onevent.* child is not lower-case: " + node);
                assertTrue(perms.containsKey(node),
                        descriptor + ": rtp.onevent.* child is not declared: " + node);
            }
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
