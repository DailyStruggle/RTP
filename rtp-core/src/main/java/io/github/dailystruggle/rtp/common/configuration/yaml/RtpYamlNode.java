package io.github.dailystruggle.rtp.common.configuration.yaml;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for the comment-aware YAML AST used by the in-house parser.
 *
 * <p>Per ADR-025 (2026-05-15 revision) and ADR-042 (block-only comments),
 * every node carries an ordered list of <em>block-comment lines</em>
 * (the contiguous run of {@code #}-prefixed lines immediately above the
 * node in the source file). Inline trailing comments are not supported
 * and are not represented in this AST.</p>
 *
 * <p>Each node also remembers its 1-based source line position so future
 * surgical-edit writers can locate it without re-tokenising.</p>
 */
public abstract class RtpYamlNode {

    /**
     * Lines of the block comment immediately above this node, in source
     * order. Each entry is the raw comment line content (after the leading
     * {@code #} and one optional space). Empty list when no comment.
     */
    private final List<String> blockComments = new ArrayList<>();

    /**
     * 1-based line number of the first token of this node in the source
     * file. {@code -1} when the node was synthesised rather than parsed.
     */
    private int sourceLine = -1;

    /**
     * Indentation column (0-based) of the first token of this node.
     * {@code -1} when synthesised.
     */
    private int sourceColumn = -1;

    public final List<String> blockComments() {
        return blockComments;
    }

    public final void addBlockComment(String line) {
        blockComments.add(line);
    }

    public final void setBlockComments(List<String> lines) {
        blockComments.clear();
        blockComments.addAll(lines);
    }

    public final int sourceLine() {
        return sourceLine;
    }

    public final int sourceColumn() {
        return sourceColumn;
    }

    final void setSourcePosition(int line, int column) {
        this.sourceLine = line;
        this.sourceColumn = column;
    }
}
