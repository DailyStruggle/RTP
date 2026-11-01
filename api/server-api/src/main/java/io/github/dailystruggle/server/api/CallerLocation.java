package io.github.dailystruggle.server.api;

/**
 * Platform-neutral snapshot of a command caller's world and block coordinates (ADR-049, ADR-115).
 *
 * @param worldName canonical world name, or {@code null}
 * @param x block X coordinate
 * @param y block Y coordinate
 * @param z block Z coordinate
 * @governedBy ADR-049
 * @governedBy ADR-115
 */
public record CallerLocation(String worldName, int x, int y, int z) {}
