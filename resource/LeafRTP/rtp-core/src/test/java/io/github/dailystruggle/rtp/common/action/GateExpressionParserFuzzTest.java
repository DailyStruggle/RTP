package io.github.dailystruggle.rtp.common.action;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Gate Expression Parser Coverage-Guided Fuzz Tests")
class GateExpressionParserFuzzTest {

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz GateExpressionParser.matches: must be total, no exception may escape")
    void fuzzMatches(String expr) {
        GateExpressionParser.matches(expr, 0.0);
        GateExpressionParser.matches(expr, 45.0);
    }

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz GateExpressionParser.parseDurationSeconds: must be total, no exception may escape")
    void fuzzParseDurationSeconds(String text) {
        GateExpressionParser.parseDurationSeconds(text, 300L);
    }
}
