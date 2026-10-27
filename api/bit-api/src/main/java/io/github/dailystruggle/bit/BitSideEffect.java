package io.github.dailystruggle.bit;

/**
 * Runtime requirements or side effects a component may have when it runs.
 *
 * @governedBy ADR-073
 * @governedBy ADR-115
 */
public enum BitSideEffect {
    SAFE,
    NEEDS_WORLD,
    NEEDS_PLAYER,
    MUTATING,
    HEAVY
}
