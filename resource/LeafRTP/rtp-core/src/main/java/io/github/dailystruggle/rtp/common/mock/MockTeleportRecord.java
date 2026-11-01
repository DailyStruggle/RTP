package io.github.dailystruggle.rtp.common.mock;

import java.util.UUID;

/**
 * One BIT mock landing as measured by the teleport pipeline (ADR-121 section 11). Data only;
 * {@code attempts} and {@code durationMillis} are the pipeline's own {@code TeleportData} values.
 */
public record MockTeleportRecord(
    UUID mockId,
    String world,
    int x,
    int y,
    int z,
    String region,
    long attempts,
    long durationMillis,
    long completedAtMillis) {}
