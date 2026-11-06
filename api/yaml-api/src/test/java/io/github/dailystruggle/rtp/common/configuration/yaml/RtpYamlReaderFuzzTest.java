package io.github.dailystruggle.rtp.common.configuration.yaml;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.IOException;
import java.io.StringReader;
import org.junit.jupiter.api.DisplayName;

@DisplayName("RtpYamlReader Coverage-Guided Fuzz Tests")
class RtpYamlReaderFuzzTest {

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz RtpYamlReader.parse: only RtpYamlParseException may escape")
    void fuzzParse(String source) {
        if (source == null) {
            return;
        }
        try {
            RtpYamlMapping mapping = RtpYamlReader.parse(source);
            assertNotNull(mapping);
        } catch (RtpYamlParseException expected) {
            // Expected fail-closed syntax/limit rejection
        }

        try {
            RtpYamlMapping mapping = RtpYamlReader.parse(new StringReader(source));
            assertNotNull(mapping);
        } catch (RtpYamlParseException | IOException expected) {
            // Expected fail-closed rejection
        }
    }
}
