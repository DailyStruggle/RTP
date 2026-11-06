package io.github.dailystruggle.yaml;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlMapping;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;

/**
 * Top-level pure-Java YAML document handle with comment preservation and atomic save ({@code :yaml-api}).
 *
 * @governedBy ADR-115, yaml-api-ADR-001, yaml-api-ADR-002, yaml-api-ADR-003
 */
public final class YamlConfig extends RtpYamlConfig {

    private YamlConfig(File file, RtpYamlMapping root) {
        super(file, root);
    }

    public YamlConfig(String path) {
        super(path);
    }

    public YamlConfig(File file) {
        super(file);
    }

    public YamlConfig() {
        super();
    }

    public static YamlConfig load(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        YamlConfig cfg = new YamlConfig(file);
        cfg.load();
        return cfg;
    }

    public static YamlConfig parse(Reader reader) throws IOException {
        return new YamlConfig(null, RtpYamlReader.parse(reader));
    }

    public static YamlConfig parse(String source) {
        return new YamlConfig(null, RtpYamlReader.parse(source));
    }

    public static YamlConfig empty(File file) {
        return new YamlConfig(file, new RtpYamlMapping());
    }
}
