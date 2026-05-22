package io.github.dailystruggle.rtp.common.configuration.yaml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for the RTP-supported YAML subset.
 *
 * <p>Subset (per ADR-025 §Migration / 2026-05-15 revision):</p>
 * <ul>
 *   <li>Block-style mappings ({@code key: value} or {@code key:} +
 *       indented child).</li>
 *   <li>Block-style sequences ({@code - item} at consistent indent).</li>
 *   <li>Plain, single-quoted, and double-quoted scalars.</li>
 *   <li>{@code #} comments (whole-line only — inline trailing comments
 *       are also accepted on parse for legacy files, but are dropped
 *       and not written back; this matches ADR-042's block-only
 *       contract on the write side).</li>
 * </ul>
 *
 * <p>Rejected (each raises {@link RtpYamlParseException} with a stable
 * {@code messageKey}):</p>
 * <ul>
 *   <li>Anchors ({@code &name}) — {@code rtpYaml.unsupported.anchor}</li>
 *   <li>Aliases ({@code *name}) — {@code rtpYaml.unsupported.alias}</li>
 *   <li>Merge keys ({@code <<:}) — {@code rtpYaml.unsupported.mergeKey}</li>
 *   <li>Flow mappings ({@code {a: b}}) — {@code rtpYaml.unsupported.flowMap}</li>
 *   <li>Flow sequences ({@code [a, b]}) — {@code rtpYaml.unsupported.flowSeq}</li>
 *   <li>Tags ({@code !!str}) — {@code rtpYaml.unsupported.tag}</li>
 *   <li>Document separators ({@code ---}, {@code ...}) — {@code rtpYaml.unsupported.docSep}</li>
 *   <li>Block scalars ({@code |}, {@code >}) — {@code rtpYaml.unsupported.blockScalar}</li>
 * </ul>
 */
public final class RtpYamlReader {

    /** A pre-lexed source line with its indent + payload classification. */
    private static final class RawLine {
        final int lineNo;       // 1-based
        final int indent;       // number of leading spaces (tabs rejected)
        final String content;   // line text after leading spaces; "" if blank
        final boolean blank;    // whitespace-only
        final boolean comment;  // first non-space char is '#'

        RawLine(int lineNo, int indent, String content, boolean blank, boolean comment) {
            this.lineNo = lineNo;
            this.indent = indent;
            this.content = content;
            this.blank = blank;
            this.comment = comment;
        }
    }

    private final List<RawLine> lines;
    private int pos;
    /**
     * Shared pending-comment buffer. Survives across recursive calls so
     * comments left over when an inner scope returns due to indent-
     * decrease belong to whatever the outer scope sees next. Entries are
     * stripped comment text; the sentinel {@link #BLANK_LINE_SENTINEL}
     * represents a preserved blank line inside the block.
     */
    private final List<String> pendingComments = new ArrayList<>();

    /** Sentinel value placed in a comment list to mark a blank source line. */
    static final String BLANK_LINE_SENTINEL = "\u0000BLANK";

    private RtpYamlReader(List<RawLine> lines) {
        this.lines = lines;
        this.pos = 0;
    }

    public static RtpYamlMapping parse(String source) {
        // Strip a UTF-8 BOM (U+FEFF) if present at position 0. Files written
        // by PowerShell `Add-Content -Encoding utf8` (and several other
        // common Windows tools) prepend a BOM by default; without this
        // strip, the BOM lands as a literal character at line 1, column 0
        // and the lexer rejects it as "missing ':' in mapping entry".
        if (source != null && !source.isEmpty() && source.charAt(0) == '\uFEFF') {
            source = source.substring(1);
        }
        List<RawLine> raw = lex(source);
        RtpYamlReader r = new RtpYamlReader(raw);
        RtpYamlMapping root = new RtpYamlMapping();
        root.setSourcePosition(1, 0);
        // Capture any document-leading comment block as the root mapping's
        // own blockComments. parseMappingBody fills pendingComments with
        // whatever it sees before the first key; we transfer it here.
        r.parseMappingBody(root, 0);
        // Any pending comments left after parsing the root mapping (e.g.,
        // a trailing block separated from the last entry by a blank line)
        // become root trailing comments.
        if (!r.pendingComments.isEmpty()) {
            trimSentinels(r.pendingComments);
            root.trailingComments().addAll(r.pendingComments);
            r.pendingComments.clear();
        }
        while (r.pos < r.lines.size()) {
            RawLine ln = r.lines.get(r.pos);
            if (ln.blank) {
                if (!root.trailingComments().isEmpty()
                        && !BLANK_LINE_SENTINEL.equals(root.trailingComments().get(root.trailingComments().size() - 1))) {
                    root.trailingComments().add(BLANK_LINE_SENTINEL);
                }
                r.pos++;
                continue;
            }
            if (ln.comment) {
                root.trailingComments().add(stripCommentMarker(ln.content));
                r.pos++;
                continue;
            }
            throw new RtpYamlParseException("rtpYaml.syntax.trailingContent",
                    "unexpected content after top-level mapping", ln.lineNo, ln.indent);
        }
        trimSentinels(root.trailingComments());
        return root;
    }

    /**
     * Remove leading blank-line sentinels from a comment list. Leading
     * blanks are layout-separation from the previous entry, not part of
     * the attached comment. Trailing sentinels are preserved so a
     * blank line between the comment and its key round-trips faithfully.
     */
    private static void trimSentinels(List<String> list) {
        while (!list.isEmpty() && BLANK_LINE_SENTINEL.equals(list.get(0))) {
            list.remove(0);
        }
    }

    public static RtpYamlMapping parse(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = (reader instanceof BufferedReader) ? (BufferedReader) reader : new BufferedReader(reader)) {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) >= 0) sb.append(buf, 0, n);
        }
        return parse(sb.toString());
    }

    /* ----------------------------- Lexer ----------------------------- */

    private static List<RawLine> lex(String source) {
        List<RawLine> out = new ArrayList<>();
        // Normalise line endings, split preserving empties.
        String[] split = source.split("\r\n|\r|\n", -1);
        // Drop a trailing empty produced by a final newline; keeps line count honest.
        int count = split.length;
        if (count > 0 && split[count - 1].isEmpty()) count--;
        for (int i = 0; i < count; i++) {
            String raw = split[i];
            int lineNo = i + 1;
            // Reject tabs in indentation explicitly — YAML disallows them and our
            // emitter only produces spaces, so this guard prevents silent corruption.
            int indent = 0;
            while (indent < raw.length() && raw.charAt(indent) == ' ') indent++;
            if (indent < raw.length() && raw.charAt(indent) == '\t') {
                throw new RtpYamlParseException("rtpYaml.syntax.tabIndent",
                        "tabs in indentation are not allowed", lineNo, indent);
            }
            String content = raw.substring(indent);
            boolean blank = content.isEmpty();
            boolean comment = !blank && content.charAt(0) == '#';
            // Reject document separators eagerly.
            if (!blank && (content.equals("---") || content.startsWith("--- "))) {
                throw new RtpYamlParseException("rtpYaml.unsupported.docSep",
                        "document separators are not supported", lineNo, indent);
            }
            if (!blank && (content.equals("...") || content.startsWith("... "))) {
                throw new RtpYamlParseException("rtpYaml.unsupported.docSep",
                        "document separators are not supported", lineNo, indent);
            }
            out.add(new RawLine(lineNo, indent, content, blank, comment));
        }
        return out;
    }

    /* ---------------------------- Parsing ---------------------------- */

    /**
     * Parse mapping entries at indent {@code expectedIndent}. Consumes
     * blank/comment lines, attaching the latest comment-run to the next
     * entry.
     */
    private void parseMappingBody(RtpYamlMapping mapping, int expectedIndent) {
        while (pos < lines.size()) {
            RawLine ln = lines.get(pos);
            if (ln.blank) {
                // Preserve the blank as a sentinel inside the pending
                // comment block so writers can re-emit the separator
                // between detached comments and the next key. Coalesce
                // consecutive blanks. Leading blanks (no pending content
                // yet) are dropped.
                if (!pendingComments.isEmpty()
                        && !BLANK_LINE_SENTINEL.equals(pendingComments.get(pendingComments.size() - 1))) {
                    pendingComments.add(BLANK_LINE_SENTINEL);
                }
                pos++;
                continue;
            }
            if (ln.comment) {
                pendingComments.add(stripCommentMarker(ln.content));
                pos++;
                continue;
            }
            if (ln.indent < expectedIndent) {
                // Caller's scope ended. Leave pendingComments alone — they
                // belong to whatever the outer scope sees next (or to the
                // root's trailing-comment slot if we're at EOF).
                return;
            }
            if (ln.indent > expectedIndent) {
                throw new RtpYamlParseException("rtpYaml.syntax.unexpectedIndent",
                        "unexpected indentation", ln.lineNo, ln.indent);
            }
            // A list item at mapping scope is an error; mappings have key: ... entries.
            if (ln.content.startsWith("- ") || ln.content.equals("-")) {
                throw new RtpYamlParseException("rtpYaml.syntax.listAtMapping",
                        "sequence item where mapping entry expected", ln.lineNo, ln.indent);
            }
            // Parse one entry: key, then either inline value or child block.
            ParsedKey pk = parseKey(ln);
            if (pk.isMergeKey) {
                throw new RtpYamlParseException("rtpYaml.unsupported.mergeKey",
                        "merge keys (<<:) are not supported", ln.lineNo, ln.indent);
            }
            pos++;
            // Snapshot pendingComments now — they belong to THIS key. Then
            // clear the shared buffer so a recursive parseChildBlock can't
            // see them and steal them for a deeper key.
            List<String> attached = new ArrayList<>(pendingComments);
            trimSentinels(attached);
            pendingComments.clear();
            String afterColon = pk.afterColon;
            RtpYamlNode child;
            if (afterColon.isEmpty()) {
                // Look ahead for an indented child block; otherwise this is an
                // empty-scalar value (null).
                child = parseChildBlock(ln.indent);
            } else {
                child = parseInlineScalar(afterColon, ln.lineNo, ln.indent + pk.keyLength + 2);
            }
            child.setSourcePosition(ln.lineNo, ln.indent);
            child.setBlockComments(attached);
            mapping.put(pk.key, child);
        }
        // Reached EOF inside this mapping body. Leave pendingComments
        // for the outer caller (or for parse()'s trailing-comment logic
        // on the root). Inner mappings should not steal these.
    }

    /**
     * After consuming a {@code key:} line with no inline value, decide
     * whether the child is a mapping, a sequence, or an empty scalar
     * (when the next non-blank line is at a shallower indent or EOF).
     */
    private RtpYamlNode parseChildBlock(int parentIndent) {
        // Skip blanks/comments to peek at indent of the next content line.
        int peek = pos;
        List<String> bufferedComments = new ArrayList<>();
        while (peek < lines.size()) {
            RawLine ln = lines.get(peek);
            if (ln.blank) { peek++; bufferedComments.clear(); continue; }
            if (ln.comment) { peek++; bufferedComments.add(stripCommentMarker(ln.content)); continue; }
            break;
        }
        if (peek >= lines.size()) {
            return new RtpYamlScalar("", RtpYamlScalar.Style.PLAIN);
        }
        RawLine next = lines.get(peek);
        if (next.indent <= parentIndent) {
            return new RtpYamlScalar("", RtpYamlScalar.Style.PLAIN);
        }
        // Decide: sequence if next starts with "- " or equals "-"; else mapping.
        if (next.content.startsWith("- ") || next.content.equals("-")) {
            RtpYamlSequence seq = new RtpYamlSequence();
            seq.setSourcePosition(next.lineNo, next.indent);
            parseSequenceBody(seq, next.indent);
            return seq;
        } else {
            RtpYamlMapping map = new RtpYamlMapping();
            map.setSourcePosition(next.lineNo, next.indent);
            parseMappingBody(map, next.indent);
            return map;
        }
    }

    private void parseSequenceBody(RtpYamlSequence seq, int expectedIndent) {
        while (pos < lines.size()) {
            RawLine ln = lines.get(pos);
            if (ln.blank) {
                if (!pendingComments.isEmpty()
                        && !BLANK_LINE_SENTINEL.equals(pendingComments.get(pendingComments.size() - 1))) {
                    pendingComments.add(BLANK_LINE_SENTINEL);
                }
                pos++; continue;
            }
            if (ln.comment) { pendingComments.add(stripCommentMarker(ln.content)); pos++; continue; }
            if (ln.indent < expectedIndent) return;
            if (ln.indent > expectedIndent) {
                throw new RtpYamlParseException("rtpYaml.syntax.unexpectedIndent",
                        "unexpected indentation in sequence", ln.lineNo, ln.indent);
            }
            if (!(ln.content.startsWith("- ") || ln.content.equals("-"))) {
                // End of this sequence (a non-list-item at the same indent).
                return;
            }
            pos++;
            // Snapshot pendingComments for THIS list item, then clear so
            // recursive descent into the item's child does not steal them.
            List<String> attachedItem = new ArrayList<>(pendingComments);
            trimSentinels(attachedItem);
            pendingComments.clear();
            String after = ln.content.equals("-") ? "" : ln.content.substring(2);
            RtpYamlNode item;
            if (after.isEmpty()) {
                item = parseChildBlock(ln.indent);
            } else if (looksLikeKey(after)) {
                // List item that's a mapping: rebuild as an inline mapping
                // entry, plus any indented continuation.
                RtpYamlMapping itemMap = new RtpYamlMapping();
                itemMap.setSourcePosition(ln.lineNo, ln.indent + 2);
                // Synthesize a single-entry mapping line for parseKey.
                ParsedKey pk = parseKey(new RawLine(ln.lineNo, ln.indent + 2, after, false, false));
                RtpYamlNode child;
                if (pk.afterColon.isEmpty()) {
                    child = parseChildBlock(ln.indent + 2);
                } else {
                    child = parseInlineScalar(pk.afterColon, ln.lineNo, ln.indent + 2 + pk.keyLength + 2);
                }
                child.setSourcePosition(ln.lineNo, ln.indent + 2);
                itemMap.put(pk.key, child);
                // Continued mapping entries at the same indent as this item's content.
                parseMappingBody(itemMap, ln.indent + 2);
                item = itemMap;
            } else {
                item = parseInlineScalar(after, ln.lineNo, ln.indent + 2);
            }
            item.setBlockComments(attachedItem);
            seq.add(item);
        }
    }

    /* ---------------------------- Helpers ---------------------------- */

    private static final class ParsedKey {
        final String key;
        final int keyLength;     // raw length of the key token (no quotes adjustment)
        final String afterColon; // text after ": " or ":"; never null
        final boolean isMergeKey;

        ParsedKey(String key, int keyLength, String afterColon, boolean isMergeKey) {
            this.key = key;
            this.keyLength = keyLength;
            this.afterColon = afterColon;
            this.isMergeKey = isMergeKey;
        }
    }

    private static ParsedKey parseKey(RawLine ln) {
        String c = ln.content;
        // Reject anchors/aliases/tags/flow at line start.
        char first = c.charAt(0);
        if (first == '&') throw new RtpYamlParseException("rtpYaml.unsupported.anchor",
                "anchors are not supported", ln.lineNo, ln.indent);
        if (first == '*') throw new RtpYamlParseException("rtpYaml.unsupported.alias",
                "aliases are not supported", ln.lineNo, ln.indent);
        if (first == '!') throw new RtpYamlParseException("rtpYaml.unsupported.tag",
                "tags are not supported", ln.lineNo, ln.indent);
        if (first == '{') throw new RtpYamlParseException("rtpYaml.unsupported.flowMap",
                "flow mappings are not supported", ln.lineNo, ln.indent);
        if (first == '[') throw new RtpYamlParseException("rtpYaml.unsupported.flowSeq",
                "flow sequences are not supported", ln.lineNo, ln.indent);

        // Merge-key form: "<<:"
        if (c.startsWith("<<:")) {
            return new ParsedKey("<<", 2, "", true);
        }

        // Find the colon delimiter, respecting quoted keys.
        int colonAt;
        int keyLen;
        String keyText;
        if (first == '"' || first == '\'') {
            // Quoted key: read until matching quote.
            int i = 1;
            char q = first;
            while (i < c.length()) {
                char ch = c.charAt(i);
                if (q == '"' && ch == '\\') { i += 2; continue; }
                if (q == '\'' && ch == '\'' && i + 1 < c.length() && c.charAt(i + 1) == '\'') { i += 2; continue; }
                if (ch == q) break;
                i++;
            }
            if (i >= c.length() || c.charAt(i) != q) {
                throw new RtpYamlParseException("rtpYaml.syntax.unterminatedQuote",
                        "unterminated quoted key", ln.lineNo, ln.indent);
            }
            keyText = unquote(c.substring(0, i + 1));
            keyLen = i + 1;
            colonAt = c.indexOf(':', i + 1);
        } else {
            colonAt = c.indexOf(':');
            if (colonAt < 0) {
                throw new RtpYamlParseException("rtpYaml.syntax.missingColon",
                        "missing ':' in mapping entry", ln.lineNo, ln.indent);
            }
            keyText = c.substring(0, colonAt).trim();
            keyLen = colonAt;
        }
        if (colonAt < 0) {
            throw new RtpYamlParseException("rtpYaml.syntax.missingColon",
                    "missing ':' in mapping entry", ln.lineNo, ln.indent);
        }
        String afterColon = c.substring(colonAt + 1);
        // Strip leading single space (the standard ': ' form).
        if (!afterColon.isEmpty() && afterColon.charAt(0) == ' ') afterColon = afterColon.substring(1);
        // Strip a same-line trailing comment (block-only contract on write,
        // but tolerate during parse of legacy files).
        afterColon = stripInlineComment(afterColon);
        afterColon = afterColon.stripTrailing();
        return new ParsedKey(keyText, keyLen, afterColon, false);
    }

    private static boolean looksLikeKey(String s) {
        if (s.isEmpty()) return false;
        char first = s.charAt(0);
        if (first == '"' || first == '\'') {
            // Quoted; check that a ':' appears after the matching quote.
            int i = 1;
            char q = first;
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (q == '"' && ch == '\\') { i += 2; continue; }
                if (q == '\'' && ch == '\'' && i + 1 < s.length() && s.charAt(i + 1) == '\'') { i += 2; continue; }
                if (ch == q) break;
                i++;
            }
            if (i >= s.length()) return false;
            return i + 1 < s.length() && s.charAt(i + 1) == ':';
        }
        // Plain: a ':' followed by space or EOL indicates a key.
        int idx = s.indexOf(':');
        if (idx < 0) return false;
        return idx == s.length() - 1 || s.charAt(idx + 1) == ' ';
    }

    private static RtpYamlScalar parseInlineScalar(String text, int line, int column) {
        if (text.isEmpty()) return new RtpYamlScalar("", RtpYamlScalar.Style.PLAIN);
        char first = text.charAt(0);
        if (first == '&') throw new RtpYamlParseException("rtpYaml.unsupported.anchor",
                "anchors are not supported", line, column);
        if (first == '*') throw new RtpYamlParseException("rtpYaml.unsupported.alias",
                "aliases are not supported", line, column);
        if (first == '!') throw new RtpYamlParseException("rtpYaml.unsupported.tag",
                "tags are not supported", line, column);
        if (first == '{') throw new RtpYamlParseException("rtpYaml.unsupported.flowMap",
                "flow mappings are not supported", line, column);
        if (first == '[') throw new RtpYamlParseException("rtpYaml.unsupported.flowSeq",
                "flow sequences are not supported", line, column);
        if (first == '|' || first == '>') throw new RtpYamlParseException("rtpYaml.unsupported.blockScalar",
                "block scalars are not supported", line, column);
        if (first == '"' || first == '\'') {
            // Quoted scalar: span must be the entire trimmed value.
            int i = 1;
            char q = first;
            while (i < text.length()) {
                char ch = text.charAt(i);
                if (q == '"' && ch == '\\') { i += 2; continue; }
                if (q == '\'' && ch == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') { i += 2; continue; }
                if (ch == q) break;
                i++;
            }
            if (i >= text.length()) {
                throw new RtpYamlParseException("rtpYaml.syntax.unterminatedQuote",
                        "unterminated quoted scalar", line, column);
            }
            String body = text.substring(1, i);
            String unescaped = (q == '"') ? unescapeDouble(body) : body.replace("''", "'");
            return new RtpYamlScalar(unescaped, q == '"' ? RtpYamlScalar.Style.DOUBLE : RtpYamlScalar.Style.SINGLE);
        }
        return new RtpYamlScalar(text, RtpYamlScalar.Style.PLAIN);
    }

    /** Strip an unquoted trailing {@code # ...} comment from a scalar tail. */
    private static String stripInlineComment(String s) {
        boolean inS = false, inD = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inD) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inD = false;
                continue;
            }
            if (inS) {
                if (c == '\'') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\'') { i++; continue; }
                    inS = false;
                }
                continue;
            }
            if (c == '"') { inD = true; continue; }
            if (c == '\'') { inS = true; continue; }
            if (c == '#' && i > 0 && (s.charAt(i - 1) == ' ' || s.charAt(i - 1) == '\t')) {
                return s.substring(0, i);
            }
        }
        return s;
    }

    private static String stripCommentMarker(String content) {
        // content starts with '#'; drop it and one optional leading space.
        if (content.length() == 1) return "";
        return content.charAt(1) == ' ' ? content.substring(2) : content.substring(1);
    }

    private static String unquote(String quoted) {
        if (quoted.length() < 2) return quoted;
        char q = quoted.charAt(0);
        String body = quoted.substring(1, quoted.length() - 1);
        if (q == '"') return unescapeDouble(body);
        if (q == '\'') return body.replace("''", "'");
        return quoted;
    }

    private static String unescapeDouble(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case '0': sb.append('\0'); break;
                    default: sb.append('\\').append(n); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // Convenience for tests
    public static RtpYamlMapping parseString(String s) { return parse(s); }
    public static RtpYamlMapping parseFromReader(StringReader r) throws IOException { return parse(r); }
}
