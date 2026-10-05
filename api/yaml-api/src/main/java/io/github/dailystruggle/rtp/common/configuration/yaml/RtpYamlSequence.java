package io.github.dailystruggle.rtp.common.configuration.yaml;

import java.util.ArrayList;
import java.util.List;

/**
 * Sequence node: an ordered list of child nodes.
 *
 * <p>Block style ({@code - item} per line) is the default. Single-line flow
 * sequences ({@code [a, b, c]}, nestable) are accepted on parse for Chunky
 * parity (ADR-034 polygon {@code vertices}); {@link #isFlowStyle()} records
 * that so the writer can re-emit the compact form.</p>
 */
public final class RtpYamlSequence extends RtpYamlNode {

    private final List<RtpYamlNode> items = new ArrayList<>();
    private boolean flowStyle;

    public List<RtpYamlNode> items() {
        return items;
    }

    public void add(RtpYamlNode node) {
        items.add(node);
    }

    public int size() {
        return items.size();
    }

    public RtpYamlNode get(int i) {
        return items.get(i);
    }

    /** True when the sequence was read from (or should be written in) flow style {@code [a, b]}. */
    public boolean isFlowStyle() {
        return flowStyle;
    }

    /**
     * Request flow-style emission. Advisory: the writer falls back to block
     * style when an item cannot be represented inline (mappings, block
     * comments, plain scalars containing flow indicators).
     */
    public void setFlowStyle(boolean flowStyle) {
        this.flowStyle = flowStyle;
    }
}
