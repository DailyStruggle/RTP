package io.github.dailystruggle.rtp.common.configuration.yaml;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Mapping node: an ordered collection of {@code key: value} pairs.
 *
 * <p>Insertion order is preserved via {@link LinkedHashMap} — important
 * for idempotent re-emit per ADR-042. Keys are strings (RTP's YAML
 * subset does not permit non-scalar mapping keys).</p>
 *
 * <p>Block comments above the mapping itself live on this node;
 * comments above individual entries live on the entry's child node
 * (scalar / mapping / sequence).</p>
 */
public final class RtpYamlMapping extends RtpYamlNode {

    private final Map<String, RtpYamlNode> entries = new LinkedHashMap<>();

    /**
     * Trailing comment lines (block comments that follow the last entry
     * but are still inside the mapping's indent scope, used at the end
     * of file to preserve a trailing comment block).
     */
    private final java.util.List<String> trailingComments = new java.util.ArrayList<>();

    public Set<String> keys() {
        return entries.keySet();
    }

    public Map<String, RtpYamlNode> entries() {
        return entries;
    }

    public RtpYamlNode get(String key) {
        return entries.get(key);
    }

    public void put(String key, RtpYamlNode value) {
        entries.put(key, value);
    }

    public boolean containsKey(String key) {
        return entries.containsKey(key);
    }

    public void remove(String key) {
        entries.remove(key);
    }

    public java.util.List<String> trailingComments() {
        return trailingComments;
    }
}
