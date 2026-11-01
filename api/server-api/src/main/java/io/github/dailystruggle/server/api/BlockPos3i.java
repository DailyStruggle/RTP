package io.github.dailystruggle.server.api;

/**
 * Immutable 3D integer block coordinate used by {@link ServerAccessor} bulk block operations
 * (ADR-049, ADR-115).
 *
 * @param x block X coordinate
 * @param y block Y coordinate
 * @param z block Z coordinate
 * @governedBy ADR-049
 * @governedBy ADR-115
 */
public record BlockPos3i(int x, int y, int z) {}
