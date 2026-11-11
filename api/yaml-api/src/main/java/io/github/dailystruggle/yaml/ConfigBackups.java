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
            io.github.dailystruggle.rtp.common.configuration.ConfigBackups.DEFAULT_BAK_RETENTION;
    public static final String BAK_INFIX =
            io.github.dailystruggle.rtp.common.configuration.ConfigBackups.BAK_INFIX;

    private ConfigBackups() {}

    public static Path backup(File target, int retention) throws IOException {
        return io.github.dailystruggle.rtp.common.configuration.ConfigBackups.backup(target, retention);
    }

    public static List<Path> listBaks(File target) {
        return io.github.dailystruggle.rtp.common.configuration.ConfigBackups.listBaks(target);
    }

    public static Path restoreLatest(File target) throws IOException {
        return io.github.dailystruggle.rtp.common.configuration.ConfigBackups.restoreLatest(target);
    }

    public static int pruneBaks(File target, int keep) {
        return io.github.dailystruggle.rtp.common.configuration.ConfigBackups.pruneBaks(target, keep);
    }
}
