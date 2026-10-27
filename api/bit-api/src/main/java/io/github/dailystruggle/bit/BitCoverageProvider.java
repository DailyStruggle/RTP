package io.github.dailystruggle.bit;

/**
 * SPI for computing BIT component surface and live bytecode probe coverage (ADR-121, ADR-103).
 *
 * @governedBy ADR-073
 * @governedBy ADR-115
 */
public interface BitCoverageProvider {
    String id();

    boolean isAvailable();

    BitCoverageSnapshot snapshot(BitRunner runner);
}
