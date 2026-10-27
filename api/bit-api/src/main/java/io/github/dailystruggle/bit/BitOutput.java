package io.github.dailystruggle.bit;

import java.util.function.Consumer;

/**
 * Formats BIT results without owning the destination or user-facing transport.
 *
 * @governedBy ADR-073
 * @governedBy ADR-115
 */
public interface BitOutput {
    void write(BitResult result, Consumer<String> sink);

    void writeNotRun(BitComponent component, Consumer<String> sink);

    void writeEmpty(String selector, Consumer<String> sink);
}
