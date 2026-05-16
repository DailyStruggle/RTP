package io.github.dailystruggle.rtp.common.configuration.yaml;

import java.util.ArrayList;
import java.util.List;

/**
 * Sequence node: an ordered list of child nodes (block-style only).
 *
 * <p>Per ADR-025 the YAML subset is block-style only — flow sequences
 * ({@code [a, b, c]}) are rejected at parse time.</p>
 */
public final class RtpYamlSequence extends RtpYamlNode {

    private final List<RtpYamlNode> items = new ArrayList<>();

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
}
