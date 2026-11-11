package io.github.dailystruggle.rtp.tags;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.github.dailystruggle.rtp.api.safety.SafetyTokenParser;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Tags and SafetyTokenParser Coverage-Guided Fuzz Tests")
class TagsParserFuzzTest {

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz TinyJsonReader.parse: only JsonParseException may escape")
    void fuzzTinyJsonReader(String json) {
        try {
            TinyJsonReader.parse(json);
        } catch (TinyJsonReader.JsonParseException expected) {
            // Expected fail-closed rejection for malformed tag JSON
        }
    }

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz SafetyTokenParser.parse: must be total, no exception may escape")
    void fuzzSafetyTokenParser(String raw) {
        SafetyTokenParser.ParseResult result = SafetyTokenParser.parse(raw);
        assertNotNull(result);
        assertNotNull(result.accepted());
        assertNotNull(result.rejected());
    }
}
