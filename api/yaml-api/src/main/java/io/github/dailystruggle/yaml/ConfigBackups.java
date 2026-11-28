package io.github.dailystruggle.yaml;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared disk-backup helper for configuration files ({@code <file>.bak.<epochMillis>}).
 *
 * @governedBy ADR-115, yaml-api-ADR-001
 */
public final class ConfigBackups {

    public static final int DEFAULT_BAK_RETENTION =
            net.leaf.yaml.configuration.ConfigBackups.DEFAULT_BAK_RETENTION;
    public static final String BAK_INFIX =
            net.leaf.yaml.configuration.ConfigBackups.BAK_INFIX;

    private ConfigBackups() {}

    public static Path backup(File target, int retention) throws IOException {
        return net.leaf.yaml.configuration.ConfigBackups.backup(target, retention);
    }

    public static List<Path> listBaks(File target) {
        return net.leaf.yaml.configuration.ConfigBackups.listBaks(target);
    }

    public static Path restoreLatest(File target) throws IOException {
        return net.leaf.yaml.configuration.ConfigBackups.restoreLatest(target);
    }

    public static int pruneBaks(File target, int keep) {
        return net.leaf.yaml.configuration.ConfigBackups.pruneBaks(target, keep);
    }
}
