package io.github.dailystruggle.bit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts simple string or enum labels from a named Java switch for drift tests. */
public final class SwitchLabels {
    private static final Pattern CASE_START = Pattern.compile("\\bcase\\s++");
    private static final Pattern LABEL = Pattern.compile("\"([^\"]+)\"|\\b([A-Z][A-Z0-9_]*)\\b");

    private SwitchLabels() {}

    public static List<String> extract(String source, String switchExpression) {
        if (source == null || switchExpression == null || switchExpression.isBlank()) return List.of();
        @SuppressWarnings("external_pmd:RegexCompiledPerCall")
        Pattern switchPattern = Pattern.compile("switch\\s*\\(\\s*" + Pattern.quote(switchExpression.trim()) + "\\s*\\)\\s*\\{");
        Matcher switchMatcher = switchPattern.matcher(source);
        if (!switchMatcher.find()) return List.of();
        int open = switchMatcher.end() - 1;
        int close = matchingBrace(source, open);
        if (close < 0) return List.of();
        String body = source.substring(open + 1, close);
        List<String> labels = new ArrayList<>();
        Matcher cases = CASE_START.matcher(body);
        int searchFrom = 0;
        while (cases.find(searchFrom)) {
            int labelStart = cases.end();
            int lineEnd = lineEnd(body, labelStart);
            int terminator = caseTerminator(body, labelStart, lineEnd);
            if (terminator < 0) terminator = whitespaceTerminator(body, lineEnd);
            if (terminator < 0) {
                if (lineEnd == body.length()) break;
                searchFrom = lineEnd + lineBreakWidth(body, lineEnd);
                continue;
            }
            Matcher label = LABEL.matcher(body.substring(labelStart, terminator));
            while (label.find()) {
                String stringLabel = label.group(1);
                if (stringLabel == null) stringLabel = label.group(2);
                labels.add(stringLabel);
            }
            searchFrom = terminator + (body.startsWith("->", terminator) ? 2 : 1);
        }
        return List.copyOf(labels);
    }

    private static int lineEnd(String source, int start) {
        int end = start;
        while (end < source.length() && !isLineBreak(source.charAt(end))) end++;
        return end;
    }

    private static int caseTerminator(String source, int start, int end) {
        for (int i = start; i < end; i++) {
            if (source.charAt(i) == ':' || source.startsWith("->", i)) return i;
        }
        return -1;
    }

    private static int whitespaceTerminator(String source, int start) {
        int index = start;
        while (index < source.length() && isRegexWhitespace(source.charAt(index))) index++;
        if (index == source.length()) return -1;
        return source.charAt(index) == ':' || source.startsWith("->", index) ? index : -1;
    }

    private static int lineBreakWidth(String source, int index) {
        return source.charAt(index) == '\r' && index + 1 < source.length() && source.charAt(index + 1) == '\n'
                ? 2
                : 1;
    }

    private static boolean isLineBreak(char value) {
        return value == '\r' || value == '\n' || value == '\u0085' || value == '\u2028' || value == '\u2029';
    }

    private static boolean isRegexWhitespace(char value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\u000B' || value == '\f' || value == '\r';
    }

    private static int matchingBrace(String source, int open) {
        int depth = 0;
        boolean quoted = false;
        boolean character = false;
        boolean escaped = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = open; i < source.length(); i++) {
            char current = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (lineComment) {
                if (current == '\n') lineComment = false;
            } else if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                }
            } else if (!quoted && !character && current == '/' && next == '/') {
                lineComment = true;
            } else if (!quoted && !character && current == '/' && next == '*') {
                blockComment = true;
            } else if (escaped) {
                escaped = false;
            } else if ((quoted || character) && current == '\\') {
                escaped = true;
            } else if (!character && current == '"') quoted = !quoted;
            else if (!quoted && current == '\'') character = !character;
            else if (!quoted && !character && current == '{') depth++;
            else if (!quoted && !character && current == '}' && --depth == 0) return i;
        }
        return -1;
    }
}
