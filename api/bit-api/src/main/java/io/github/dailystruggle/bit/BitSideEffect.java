package io.github.dailystruggle.bit;

/** Runtime requirements or side effects a component may have when it runs. */
public enum BitSideEffect {
    SAFE,
    NEEDS_WORLD,
    NEEDS_PLAYER,
    MUTATING,
    HEAVY
}
