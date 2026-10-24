package io.github.dailystruggle.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-F-009 BIT CSV table rendering")
class BitTableTest {

    @Test
    void rendersAlignedCsvAndRoundTripsQuotedMessages() {
        BitComponent component = component("chunk-ticket");
        BitReport report = BitReport.of(List.of(new BitFinding(
                Level.ERROR,
                3103,
                "TEST_CHUNK_TICKET_TRACKER_LEAK",
                "rtp test chunk-ticket",
                "chunk-ticket.tracker",
                "residual=1, expected \"zero\"",
                "residual=0",
                "")));
        List<BitTable.Row> rows = BitTable.rows(component, new BitResult(component, report, 10, 1));
        List<String> rendered = BitTable.render(rows, BitTable.COLUMNS, BitTable.Decorator.NONE);

        List<List<String>> parsed = rendered.stream().map(BitTableTest::parseCsvRow).toList();
        assertEquals(BitTable.COLUMNS, parsed.get(0));
        assertEquals("chunk-ticket", parsed.get(1).get(0));
        assertEquals("FAILED", parsed.get(1).get(3));
        assertEquals("1", parsed.get(1).get(4));
        assertEquals("TEST:3103", parsed.get(1).get(7));
        assertEquals("TEST_CHUNK_TICKET_TRACKER_LEAK", parsed.get(1).get(8));
        assertEquals("residual=1, expected \"zero\"", parsed.get(1).get(10));
        assertEquals(
                delimiterPositions(rendered.get(0)),
                delimiterPositions(rendered.get(1)));
    }

    @Test
    void sameLocalIdInDifferentNamespacesRendersUnambiguously() {
        BitComponent component = component("accessor");
        BitReport report = BitReport.of(List.of(
                new BitFinding(Level.ERROR, 1, "DEMO_FAILURE", "test", "", "demo", "", ""),
                new BitFinding(Level.WARNING, 1, "OTHER_FAILURE", "test", "", "other", "", "")));

        List<BitTable.Row> rows = BitTable.rows(component, new BitResult(component, report, 1, 1));

        assertEquals(List.of("DEMO:1", "OTHER:1"), rows.stream()
                .map(row -> row.cells().get(BitTable.ID)).toList());
    }

    @Test
    void reportsNotRunAndSanitizesEmbeddedNewlines() {
        BitComponent component = component("accessor");
        BitTable.Row notRun = BitTable.rows(component, null).get(0);
        assertEquals("NOT_RUN", notRun.cells().get(BitTable.HEALTH));

        BitReport empty = BitReport.of(List.of());
        BitTable.Row completed = BitTable.rows(component, new BitResult(component, empty, 1, 1)).get(0);
        assertEquals("HEALTHY", completed.cells().get(BitTable.HEALTH));

        BitFinding finding = new BitFinding(Level.WARNING, "TEST_NOTE", "test", "a", "line one\nline two", "", "");
        BitReport report = BitReport.of(List.of(finding));
        BitTable.Row row = BitTable.rows(component, new BitResult(component, report, 1, 1)).get(0);
        assertEquals("line one line two", row.cells().get(BitTable.MESSAGE));
    }

    @Test
    void csvQuotesWhitespaceAndQuoteOnlyCells() {
        BitComponent component = component("accessor");
        BitReport report = BitReport.of(List.of(
                new BitFinding(Level.WARNING, "NOTE", "test", " padded ", "say \"go\"", "", ""),
                new BitFinding(Level.WARNING, "NOTE", "test", "trailing ", "plain", "", "")));

        List<String> rendered = BitTable.render(
                BitTable.rows(component, new BitResult(component, report, 1, 1)),
                List.of(BitTable.PATH, BitTable.MESSAGE),
                BitTable.Decorator.NONE);

        assertTrue(rendered.stream().anyMatch(line -> line.contains("\" padded \"")));
        assertTrue(rendered.stream().anyMatch(line -> line.contains("\"trailing \"")));
        assertTrue(rendered.stream().anyMatch(line -> line.contains("\"say \"\"go\"\"\"")));
    }

    @Test
    void decoratorDoesNotAffectColumnWidths() {
        BitComponent component = component("accessor");
        List<BitTable.Row> rows = BitTable.rows(component, null);
        List<String> plain = BitTable.render(rows, BitTable.COLUMNS, BitTable.Decorator.NONE);
        List<String> decorated = BitTable.render(
                rows,
                BitTable.COLUMNS,
                (column, value, row) -> "&a" + value);

        assertEquals(plain, decorated.stream().map(BitTableTest::stripColors).toList());
    }

    private static BitComponent component(String id) {
        return new BitComponent(
                id, "test." + id, BitTier.IBIT, BitSideEffect.SAFE, 1_000, () -> BitReport.of(List.of()));
    }

    private static String stripColors(String value) {
        return value.replaceAll("(?i)&[0-9A-FK-OR]", "");
    }

    private static List<Integer> delimiterPositions(String row) {
        List<Integer> positions = new ArrayList<>();
        int from = 0;
        for (int i = 0; i < BitTable.COLUMNS.size() - 1; i++) {
            int delimiter = row.indexOf(',', from);
            positions.add(delimiter);
            from = delimiter + 1;
        }
        return positions;
    }

    private static List<String> parseCsvRow(String line) {
        List<String> cells = new ArrayList<>();
        int index = 0;
        while (index < line.length()) {
            while (index < line.length() && line.charAt(index) == ' ') index++;
            StringBuilder cell = new StringBuilder();
            if (index < line.length() && line.charAt(index) == '"') {
                index++;
                while (index < line.length()) {
                    char current = line.charAt(index++);
                    if (current == '"') {
                        if (index < line.length() && line.charAt(index) == '"') {
                            cell.append('"');
                            index++;
                        } else {
                            break;
                        }
                    } else {
                        cell.append(current);
                    }
                }
                while (index < line.length() && line.charAt(index) == ' ') index++;
            } else {
                while (index < line.length() && line.charAt(index) != ',') cell.append(line.charAt(index++));
            }
            cells.add(cell.toString());
            if (index < line.length()) {
                assertEquals(',', line.charAt(index++));
            }
        }
        return cells;
    }
}
