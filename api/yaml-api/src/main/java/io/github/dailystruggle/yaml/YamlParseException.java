package io.github.dailystruggle.yaml;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlParseException;

/**
 * Thrown when {@code :yaml-api} encounters a syntactic error or unsupported YAML construct.
 *
 * @governedBy ADR-115, yaml-api-ADR-001, yaml-api-ADR-003
 */
public class YamlParseException extends RtpYamlParseException {

    private static final long serialVersionUID = 1L;

    public YamlParseException(String messageKey, String detail, int line, int column) {
        super(messageKey, detail, line, column);
    }
}
