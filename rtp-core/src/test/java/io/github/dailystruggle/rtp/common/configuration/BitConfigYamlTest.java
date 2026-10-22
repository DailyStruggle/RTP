package io.github.dailystruggle.rtp.common.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-F-009 BIT config uses runtime-supported YAML")
class BitConfigYamlTest {
    @Test
    void parsesTheShippedDefaultAndEveryLocaleVariant() throws IOException {
        Path resources = repositoryRoot().resolve("rtp-plugin/src/main/resources");
        Path baseline = resources.resolve("advanced/bit.yml");
        assertTrue(Files.isRegularFile(baseline), "shipped advanced/bit.yml must exist");

        List<Path> configFiles = new ArrayList<>();
        configFiles.add(baseline);
        try (var files = Files.walk(resources.resolve("lang"))) {
            configFiles.addAll(files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().replace('\\', '/').endsWith("/advanced/bit.yml"))
                    .sorted()
                    .toList());
        }

        for (Path file : configFiles) {
            Map<?, ?> values = RtpYamlConfig.parse(Files.readString(file)).getValues(false);
            assertEquals(Boolean.FALSE, values.get("strictActions"), file.toString());
            assertEquals(200, values.get("gateOutcomeSamples"), file.toString());
            Object overrides = values.get("overrides");
            assertTrue(overrides == null
                            || overrides instanceof String text && text.isBlank()
                            || overrides instanceof Map<?, ?> map && map.isEmpty()
                            || overrides instanceof RtpYamlSection section && section.getMapValues(false).isEmpty(),
                    "default overrides must be empty: " + file);
        }
        assertTrue(configFiles.size() > 1, "locale variants must be included in the parser check");
    }

    private static Path repositoryRoot() throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("rtp-plugin/src/main/resources"))) {
            current = current.getParent();
        }
        if (current == null) throw new IOException("Could not locate repository root from the test working directory");
        return current;
    }
}
