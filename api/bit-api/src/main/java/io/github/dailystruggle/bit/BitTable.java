package io.github.dailystruggle.bit;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Platform-neutral aligned CSV representation of BIT component results. */
public final class BitTable {
    public static final String COMPONENT = "component";
    public static final String TIER = "tier";
    public static final String SIDE_EFFECT = "sideEffect";
    public static final String HEALTH = "health";
    public static final String ERRORS = "E";
    public static final String WARNINGS = "W";
    public static final String SEVERITY = "severity";
    public static final String ID = "id";
    public static final String CODE = "code";
    public static final String PATH = "path";
    public static final String MESSAGE = "message";
    public static final List<String> COLUMNS = List.of(
            COMPONENT, TIER, SIDE_EFFECT, HEALTH, ERRORS, WARNINGS, SEVERITY, ID, CODE, PATH, MESSAGE);

    private BitTable() {}

    /** Creates one row per finding, or one summary row when there are no findings or no result. */
    public static List<Row> rows(BitComponent component, BitResult result) {
        Objects.requireNonNull(component, "component must not be null");
        if (result == null) return List.of(summaryRow(component, "NOT_RUN", 0, 0));

        BitReport report = result.report();
        int errors = count(report, Level.ERROR);
        int warnings = count(report, Level.WARNING);
        if (report.findings().isEmpty()) {
            return List.of(summaryRow(component, report.health().name(), errors, warnings));
        }

        List<Row> rows = new ArrayList<>(report.findings().size());
        for (BitFinding finding : report.findings()) {
            LinkedHashMap<String, String> cells = baseCells(component, report.health().name(), errors, warnings);
            cells.put(SEVERITY, finding.severity().name());
            cells.put(ID, Integer.toString(finding.codeId()));
            cells.put(CODE, finding.code());
            cells.put(PATH, singleLine(finding.path()));
            cells.put(MESSAGE, singleLine(finding.message()));
            rows.add(new Row(cells, finding.severity().name()));
        }
        return List.copyOf(rows);
    }

    /** Renders a header and aligned rows; padding is placed after delimiters for CSV readers. */
    public static List<String> render(List<Row> rows, List<String> columns, Decorator decorator) {
        Objects.requireNonNull(rows, "rows must not be null");
        Objects.requireNonNull(columns, "columns must not be null");
        Objects.requireNonNull(decorator, "decorator must not be null");
        List<String> selectedColumns = List.copyOf(columns);
        int[] widths = new int[selectedColumns.size()];
        for (int columnIndex = 0; columnIndex < selectedColumns.size(); columnIndex++) {
            String column = Objects.requireNonNull(selectedColumns.get(columnIndex), "column must not be null");
            widths[columnIndex] = csvCell(column).length();
            for (Row row : rows) {
                String value = csvCell(row.cells().getOrDefault(column, ""));
                widths[columnIndex] = Math.max(widths[columnIndex], value.length());
            }
        }

        List<String> output = new ArrayList<>(rows.size() + 1);
        output.add(renderRow(null, selectedColumns, widths, decorator));
        for (Row row : rows) output.add(renderRow(row, selectedColumns, widths, decorator));
        return List.copyOf(output);
    }

    private static String renderRow(Row row, List<String> columns, int[] widths, Decorator decorator) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) output.append(", ");
            String column = columns.get(index);
            String value = csvCell(row == null ? column : row.cells().getOrDefault(column, ""));
            output.append(" ".repeat(widths[index] - value.length()));
            output.append(decorator.decorate(column, value, row));
        }
        return output.toString();
    }

    private static Row summaryRow(BitComponent component, String health, int errors, int warnings) {
        LinkedHashMap<String, String> cells = baseCells(component, health, errors, warnings);
        return new Row(cells, health);
    }

    private static LinkedHashMap<String, String> baseCells(
            BitComponent component, String health, int errors, int warnings) {
        LinkedHashMap<String, String> cells = new LinkedHashMap<>();
        cells.put(COMPONENT, component.id());
        cells.put(TIER, component.tier().name());
        cells.put(SIDE_EFFECT, component.sideEffect().name());
        cells.put(HEALTH, health);
        cells.put(ERRORS, Integer.toString(errors));
        cells.put(WARNINGS, Integer.toString(warnings));
        cells.put(SEVERITY, "");
        cells.put(ID, "");
        cells.put(CODE, "");
        cells.put(PATH, "");
        cells.put(MESSAGE, "");
        return cells;
    }

    private static int count(BitReport report, Level level) {
        return (int) report.findings().stream().filter(finding -> finding.severity() == level).count();
    }

    private static String singleLine(String value) {
        return value.replaceAll("[\\r\\n]+", " ");
    }

    private static String csvCell(String value) {
        String singleLine = singleLine(value);
        boolean quote = singleLine.indexOf(',') >= 0
                || singleLine.indexOf('"') >= 0
                || (!singleLine.isEmpty()
                        && (Character.isWhitespace(singleLine.charAt(0))
                                || Character.isWhitespace(singleLine.charAt(singleLine.length() - 1))));
        if (!quote) return singleLine;
        return '"' + singleLine.replace("\"", "\"\"") + '"';
    }

    /** One visible result row. The tone is its health or finding severity name. */
    public record Row(Map<String, String> cells, String tone) {
        public Row {
            Objects.requireNonNull(cells, "cells must not be null");
            cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
            tone = Objects.requireNonNull(tone, "tone must not be null");
        }
    }

    @FunctionalInterface
    public interface Decorator {
        Decorator NONE = (column, plainCell, row) -> plainCell;

        /** {@code row} is null for the header; {@code plainCell} includes CSV quoting. */
        String decorate(String column, String plainCell, Row row);
    }
}
