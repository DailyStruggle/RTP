package io.github.dailystruggle.bit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts simple string or enum labels from a named Java switch for drift tests. */
public final class SwitchLabels {
    private static final Pattern CASE = Pattern.compile("(?m)\\bcase\\s+(.+?)\\s*(?:->|:)");
    private static final Pattern LABEL = Pattern.compile("\"([^\"]+)\"|\\b([A-Z][A-Z0-9_]*)\\b");

    private SwitchLabels() {}

    public static List<String> extract(String source, String switchExpression) {
        if (source == null || switchExpression == null || switchExpression.isBlank()) return List.of();
        Pattern switchPattern = Pattern.compile("switch\\s*\\(\\s*" + Pattern.quote(switchExpression.trim()) + "\\s*\\)\\s*\\{");
        Matcher switchMatcher = switchPattern.matcher(source);
        if (!switchMatcher.find()) return List.of();
        int open = switchMatcher.end() - 1;
        int close = matchingBrace(source, open);
        if (close < 0) return List.of();
        String body = source.substring(open + 1, close);
        List<String> labels = new ArrayList<>();
        Matcher cases = CASE.matcher(body);
        while (cases.find()) {
            Matcher label = LABEL.matcher(cases.group(1));
            while (label.find()) labels.add(label.group(1) != null ? label.group(1) : label.group(2));
        }
        return List.copyOf(labels);
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
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (!quoted && !character && current == '/' && next == '/') {
                lineComment = true;
                i++;
                continue;
            }
            if (!quoted && !character && current == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (escaped) {
                escaped = false;
                continue;
            }
            if ((quoted || character) && current == '\\') {
                escaped = true;
                continue;
            }
            if (!character && current == '"') quoted = !quoted;
            else if (!quoted && current == '\'') character = !character;
            else if (!quoted && !character && current == '{') depth++;
            else if (!quoted && !character && current == '}' && --depth == 0) return i;
        }
        return -1;
    }
}
