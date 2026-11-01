package io.github.dailystruggle.rtp.common.importer;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlParseException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seam for importing third-party random teleport plugin configurations
 * into LeafRTP region, world, and global configurations (ADR-066).
 */
public interface ForeignConfigImporter {

    /**
     * Unique identifier for this importer source (e.g. "ezrtp", "justrtp").
     *
     * @return lowercase source name
     */
    String sourceName();

    /**
     * Common directory aliases for this plugin in the server's {@code plugins/} folder.
     * Used by {@link ForeignConfigImporterRegistry#resolveSourceDir} to auto-discover plugin directories.
     *
     * @return list of directory aliases, defaults to {@code [sourceName()]}
     */
    default List<String> directoryAliases() {
        return List.of(sourceName());
    }

    /**
     * Key indicator file names that must exist in the candidate directory for this plugin
     * (e.g. {@code ["config.yml"]}).
     *
     * @return list of candidate file names
     */
    default List<String> indicatorFiles() {
        return List.of("config.yml");
    }

    /**
     * Checks whether this importer can handle the configuration at the given plugin folder.
     *
     * @param sourcePluginDir candidate directory, e.g. {@code plugins/EzRTP}
     * @return true if candidate directory contains required configuration files
     */
    boolean canImport(Path sourcePluginDir);

    /**
     * Imports configuration from the source directory into the destination directory.
     * Default overwrite behavior is false (refuses to clobber existing files).
     *
     * @param sourcePluginDir directory of the foreign plugin (e.g. {@code plugins/EzRTP})
     * @param destinationDir  destination root directory of LeafRTP (e.g. {@code plugins/RTP})
     * @return result of the import operation
     */
    default ImportResult importConfiguration(Path sourcePluginDir, Path destinationDir) {
        return importConfiguration(sourcePluginDir, destinationDir, false);
    }

    /**
     * Imports configuration from the source directory into the destination directory.
     *
     * @param sourcePluginDir directory of the foreign plugin (e.g. {@code plugins/EzRTP})
     * @param destinationDir  destination root directory of LeafRTP (e.g. {@code plugins/RTP})
     * @param overwrite       whether to overwrite existing configuration files
     * @return result of the import operation
     */
    ImportResult importConfiguration(Path sourcePluginDir, Path destinationDir, boolean overwrite);

    /**
     * Safely load and pre-process a foreign YAML file, handling empty brackets (e.g. {@code []}, {@code {}})
     * that standard competitor configs frequently include but which our strict YAML subset rejects.
     */
    default RtpYamlConfig loadForeignYaml(Path file, List<String> warnings) {
        if (file == null || !Files.exists(file)) return null;
        List<Integer> sourceLines = new ArrayList<>();
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String preprocessed = sanitizeForeignYaml(raw, sourceLines);
            return RtpYamlConfig.parse(preprocessed);
        } catch (RtpYamlParseException e) {
            // Report the user's line; the column is dropped because sanitizing can re-indent.
            int line = e.line() >= 1 && e.line() <= sourceLines.size() ? sourceLines.get(e.line() - 1) : e.line();
            String msg = String.valueOf(e.getMessage());
            String detail = msg.startsWith("line ") && msg.contains(": ") ? msg.substring(msg.indexOf(": ") + 2) : msg;
            warnings.add("Failed to parse " + file.getFileName() + " line " + line + ": " + detail
                    + " (file skipped; its settings were not imported)");
            return null;
        } catch (Throwable e) {
            warnings.add("Failed to parse " + file.getFileName() + ": " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " (file skipped; its settings were not imported)");
            return null;
        }
    }

    /**
     * Sanitizes foreign YAML text so it can be parsed by RtpYamlReader:
     * - Strips unquoted comments immediately following ':' or ': ' (e.g. 'key: # comment')
     * - Replaces ': []' and ': {}' with ':'
     * - Unfolds inline flow sequences (e.g. 'slots: [1, 2]') into standard block sequences
     * - Folds multi-line plain scalars onto their first line, leaving blank lines so line numbers hold
     * - Indents sequence items written at their parent key's indent (also under a {@code - key:} item)
     */
    static String sanitizeForeignYaml(String raw) {
        return sanitizeForeignYaml(raw, new ArrayList<>());
    }

    /**
     * As {@link #sanitizeForeignYaml(String)}; fills {@code sourceLines} with the 1-based source line
     * of each output line, since unfolding a flow sequence adds lines.
     */
    static String sanitizeForeignYaml(String raw, List<Integer> sourceLines) {
        sourceLines.clear();
        if (raw == null || raw.isEmpty()) return "";
        String[] lines = ForeignYamlPatterns.LINE_BREAK.split(raw, -1);
        Pattern emptyBrackets = ForeignYamlPatterns.EMPTY_BRACKETS;
        Pattern immediateComment = ForeignYamlPatterns.IMMEDIATE_COMMENT;
        Pattern flowSeq = ForeignYamlPatterns.FLOW_SEQ;

        List<String> pass1 = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            Matcher mFlow = flowSeq.matcher(line);
            if (isEmptyBracketLine(trimmed)) {
                pass1.add("");
                sourceLines.add(i + 1);
            } else if (mFlow.matches() && !isInsideQuotes(line, mFlow.start(3))) {
                addFlowSequence(mFlow, i + 1, pass1, sourceLines);
            } else {
                pass1.add(normalizeKeyLine(line, emptyBrackets, immediateComment));
                sourceLines.add(i + 1);
            }
        }

        foldPlainContinuations(pass1);

        // YAML allows a block sequence at its parent key's indent (`Biomes:\n- desert`, and under a
        // sequence-item key: `- vip:\n  - Build_World:`). RtpYamlReader wants children strictly deeper
        // than the key's content column, so such a sequence and everything inside it is shifted right.
        // Frames nest: each holds the original indent of a shifted sequence and the total shift inside it.
        List<String> resultLines = new ArrayList<>(pass1.size());
        List<int[]> frames = new ArrayList<>();
        int openKeyColumn = -1; // output column of the previous line's key when its value is empty

        for (String line : pass1) {
            int indent = leadingSpaces(line);
            String content = line.substring(indent);
            if (content.isEmpty() || content.startsWith("#")) {
                int shift = frames.isEmpty() || indent < frames.get(frames.size() - 1)[0]
                        ? 0 : frames.get(frames.size() - 1)[1];
                resultLines.add(shift > 0 ? " ".repeat(shift) + line : line);
                continue;
            }
            boolean item = isSequenceItem(content);
            while (!frames.isEmpty()) {
                int seqIndent = frames.get(frames.size() - 1)[0];
                if (indent < seqIndent || (indent == seqIndent && !item)) {
                    frames.remove(frames.size() - 1);
                } else {
                    break;
                }
            }
            int shift = frames.isEmpty() ? 0 : frames.get(frames.size() - 1)[1];
            if (item && openKeyColumn >= 0 && indent + shift <= openKeyColumn) {
                shift += openKeyColumn + 2 - (indent + shift);
                frames.add(new int[]{indent, shift});
            }
            int outIndent = indent + shift;
            String keyPart = item ? content.substring(1).stripLeading() : content;
            int itemOffset = item ? content.length() - keyPart.length() : 0;
            boolean keyLine = keyPart.stripTrailing().endsWith(":") && !keyPart.startsWith("\"") && !keyPart.startsWith("'");
            openKeyColumn = keyLine ? outIndent + itemOffset : -1;
            resultLines.add(shift > 0 ? " ".repeat(shift) + line : line);
        }
        return String.join("\n", resultLines);
    }

    private static boolean isEmptyBracketLine(String trimmed) {
        return trimmed.equals("{}") || trimmed.equals("[]") || trimmed.startsWith("{}#") || trimmed.startsWith("{} #")
                || trimmed.startsWith("[]#") || trimmed.startsWith("[] #");
    }

    private static void addFlowSequence(Matcher mFlow, int sourceLine, List<String> pass1, List<Integer> sourceLines) {
        String indent = mFlow.group(1);
        String key = mFlow.group(2);
        String inside = mFlow.group(3).trim();
        String comment = mFlow.group(4);
        if (inside.isEmpty()) {
            pass1.add(indent + key + (comment != null ? " " + comment.trim() : ""));
            sourceLines.add(sourceLine);
            return;
        }
        pass1.add(indent + key + (comment != null && !comment.isBlank() ? " " + comment.trim() : ""));
        sourceLines.add(sourceLine);
        for (String item : inside.split(",")) {
            pass1.add(indent + "  - " + item.trim());
            sourceLines.add(sourceLine);
        }
    }

    private static String normalizeKeyLine(String line, Pattern emptyBrackets, Pattern immediateComment) {
        String result = line;
        Matcher mEmpty = emptyBrackets.matcher(result);
        if (mEmpty.matches() && !isInsideQuotes(result, mEmpty.start(2))) {
            String trailing = mEmpty.group(3);
            result = mEmpty.group(1) + (trailing.isEmpty() ? "" : " " + trailing.trim());
        }
        Matcher mComm = immediateComment.matcher(result);
        if (mComm.matches() && !result.stripLeading().startsWith("-") && !isInsideQuotes(result, mComm.group(1).length())) {
            result = mComm.group(1);
        }
        return result;
    }

    /**
     * Joins the continuation lines of multi-line plain scalars ({@code key: some\n  text}) onto the
     * first line with single spaces, as YAML folds them. Each continuation becomes a blank line so
     * the line count, and so the source-line map, is unchanged. Lines that look like keys or sequence
     * items are left alone and reach the parser as written.
     */
    private static void foldPlainContinuations(List<String> lines) {
        int i = 0;
        while (i < lines.size()) {
            i = foldPlainScalarAt(lines, i) + 1;
        }
    }

    /** Folds the continuation lines of the scalar at {@code i}; returns the last index consumed. */
    private static int foldPlainScalarAt(List<String> lines, int i) {
        String line = lines.get(i);
        int indent = leadingSpaces(line);
        String content = line.substring(indent);
        if (content.isEmpty() || content.startsWith("#") || !startsPlainScalar(content)) return i;
        StringBuilder folded = null;
        int j = i + 1;
        while (j < lines.size()) {
            String next = lines.get(j);
            int nextIndent = leadingSpaces(next);
            String nextContent = next.substring(nextIndent).stripTrailing();
            if (nextContent.isEmpty() || nextContent.startsWith("#") || nextIndent <= indent
                    || isSequenceItem(nextContent) || looksLikeKey(nextContent)) {
                break;
            }
            if (folded == null) folded = new StringBuilder(line.stripTrailing());
            folded.append(' ').append(nextContent);
            lines.set(j, "");
            j++;
        }
        if (folded == null) return i;
        lines.set(i, folded.toString());
        return j - 1;
    }

    /** True when the line's value (after {@code key: } or {@code - }) is a non-empty plain scalar. */
    private static boolean startsPlainScalar(String content) {
        String value;
        if (isSequenceItem(content)) {
            value = content.substring(1).stripLeading();
            if (looksLikeKey(value)) value = afterKey(value);
        } else if (looksLikeKey(content)) {
            value = afterKey(content);
        } else {
            return false;
        }
        if (value.isEmpty()) return false;
        char c = value.charAt(0);
        return "\"'[{|>&*!#".indexOf(c) < 0;
    }

    private static String afterKey(String content) {
        int colon = content.indexOf(": ");
        return colon < 0 ? "" : content.substring(colon + 2).strip();
    }

    /** Plain or quoted {@code key:} followed by a space or end of line. */
    private static boolean looksLikeKey(String content) {
        if (content.isEmpty()) return false;
        char first = content.charAt(0);
        if (first == '"' || first == '\'') {
            int close = content.indexOf(first, 1);
            return close > 0 && close + 1 < content.length() && content.charAt(close + 1) == ':';
        }
        int colon = content.indexOf(':');
        return colon > 0 && (colon == content.length() - 1 || content.charAt(colon + 1) == ' ');
    }

    private static boolean isSequenceItem(String content) {
        return content.startsWith("- ") || content.equals("-");
    }

    private static boolean isInsideQuotes(String s, int target) {
        boolean inD = false;
        boolean inS = false;
        int end = Math.min(target, s.length());
        int i = 0;
        while (i < end) {
            char c = s.charAt(i);
            int step = 1;
            if (inD) {
                if (c == '\\') step = 2;
                else if (c == '"') inD = false;
            } else if (inS) {
                if (c == '\'') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\'') step = 2;
                    else inS = false;
                }
            } else {
                if (c == '"') inD = true;
                else if (c == '\'') inS = true;
            }
            i += step;
        }
        return inD || inS;
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n;
    }
}

/** Precompiled patterns for {@link ForeignConfigImporter#sanitizeForeignYaml}; kept off the public interface. */
final class ForeignYamlPatterns {
    static final Pattern LINE_BREAK = Pattern.compile("\r?\n");
    static final Pattern EMPTY_BRACKETS = Pattern.compile("^(\\s*+[^#:\\r\\n\\s][^#:\\r\\n]*+:)\\s*+(\\[\\]|\\{\\})(.*)$");
    static final Pattern IMMEDIATE_COMMENT = Pattern.compile("^(\\s*+[^#:\\r\\n\\s][^#:\\r\\n]*+:)\\s*+#.*$");
    static final Pattern FLOW_SEQ = Pattern.compile("^(\\s*+)([^#:\\r\\n\\s][^#:\\r\\n]*+:)\\s*+\\[([^\\]]*+)\\]\\s*+(#.*+)?$");

    private ForeignYamlPatterns() {}
}
