package io.github.dailystruggle.rtp.common.selection.region.cache;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-078 phase 2 nomenclature guard: production sources and javadoc describe cache
 * residency as hot / cold / backlog, never as {@code L1} / {@code L2} / {@code L3}.
 *
 * <p>Scans shipped {@code .java} sources and the shipped {@code .yml} config/locale tree.
 * {@code .bak} working copies, tests, and generated build output are excluded, and public
 * config keys and database column names are untouched by the rename so nothing here
 * asserts against them.
 */
class CacheNomenclatureGuardTest {

    private static final Pattern RETIRED_TIER = Pattern.compile("\\bL[123]\\b");

    @Test
    @DisplayName("REQ-RTP-S-002 / ADR-078: no L1/L2/L3 tier vocabulary remains in production sources")
    void productionSourcesUseHotColdBacklog() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : sourceRoots()) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".java")) continue;
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    Matcher m = RETIRED_TIER.matcher(content);
                    if (m.find()) {
                        offenders.add(file + " -> " + m.group());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "retired L1/L2/L3 cache vocabulary found in production sources: " + offenders);
    }

    @Test
    @DisplayName("REQ-RTP-F-013 / ADR-078: no L1/L2/L3 tier vocabulary remains in shipped yml resources")
    void shippedResourcesUseHotColdBacklog() throws IOException {
        Path root = resourceRoot();
        if (!Files.isDirectory(root)) return;
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (!file.getFileName().toString().endsWith(".yml")) continue;
                String content = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = RETIRED_TIER.matcher(content);
                if (m.find()) {
                    offenders.add(file + " -> " + m.group());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "retired L1/L2/L3 cache vocabulary found in shipped yml resources: " + offenders);
    }

    /** Shipped config/locale resource root, resolved from either the module dir or the repo root. */
    private static Path resourceRoot() {
        Path fromRepoRoot = Path.of("rtp-plugin/src/main/resources");
        return Files.isDirectory(fromRepoRoot)
                ? fromRepoRoot
                : Path.of("..").resolve("rtp-plugin/src/main/resources");
    }

    /** Module main-source roots, resolved from either the module dir or the repo root. */
    private static List<Path> sourceRoots() {
        List<Path> roots = new ArrayList<>();
        for (String relative : new String[] {
                "rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region",
                "rtp-core/src/main/java/io/github/dailystruggle/rtp/common/commands/admin",
                "addons/LeafRTPGroupAddon/src/main/java"}) {
            Path fromRepoRoot = Path.of(relative);
            roots.add(Files.isDirectory(fromRepoRoot) ? fromRepoRoot : Path.of("..").resolve(relative));
        }
        return roots;
    }
}
