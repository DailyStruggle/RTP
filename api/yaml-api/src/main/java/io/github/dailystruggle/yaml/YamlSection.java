package io.github.dailystruggle.yaml;

import net.leaf.yaml.RtpYamlMapping;
import net.leaf.yaml.RtpYamlSection;

/**
 * Pure-Java read/mutate handle on a YAML mapping section ({@code :yaml-api}).
 *
 * @governedBy ADR-115, yaml-api-ADR-001, yaml-api-ADR-002, yaml-api-ADR-003
 */
public class YamlSection extends RtpYamlSection {

    public YamlSection(RtpYamlMapping node) {
        super(node);
    }

    public YamlSection(RtpYamlMapping node, String name, String path) {
        super(node, name, path);
    }
}
