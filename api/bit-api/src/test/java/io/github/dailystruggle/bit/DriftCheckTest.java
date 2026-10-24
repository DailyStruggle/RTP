package io.github.dailystruggle.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DriftCheckTest {
    @Test
    void extractsGroupedSwitchLabelsAndComparesBothDirections() {
        String source = "switch (key) { case \"CONSOLE\", \"PLAYER\" -> dispatch(); default -> ignore(); }";
        List<String> labels = SwitchLabels.extract(source, "key");

        assertEquals(List.of("CONSOLE", "PLAYER"), labels);
        assertTrue(DriftCheck.diff(new Vocabulary("keys", labels), labels).isEmpty());
        assertEquals(List.of("runtime label is not declared: EXTRA"),
                DriftCheck.diff(new Vocabulary("keys", List.of("CONSOLE")), List.of("CONSOLE", "EXTRA")));
    }

    @Test
    void extractorIgnoresBracesInsideStringsAndComments() {
        String source = "switch (gate) { // }\n case \"spatial\" -> { call(\"{\"); } default -> {} }";
        assertEquals(List.of("spatial"), SwitchLabels.extract(source, "gate"));
        assertTrue(SwitchLabels.extract(source, "missing").isEmpty());
    }

    @Test
    void extractorHandlesMultilineCaseAndSkipsUnterminatedCaseLine() {
        String source = """
                switch (key) {
                    case
                        "FIRST" -> dispatch();
                    case MULTILINE
                        -> dispatch();
                    case UNTERMINATED
                    case SECOND:
                        dispatch();
                }
                """;

        assertEquals(List.of("FIRST", "MULTILINE", "SECOND"), SwitchLabels.extract(source, "key"));
    }
}
