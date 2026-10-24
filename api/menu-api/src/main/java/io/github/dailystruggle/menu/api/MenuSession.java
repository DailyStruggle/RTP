package io.github.dailystruggle.menu.api;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-viewer menu navigation history, active descriptor/page cursor, and key-value staging cart
 * (ADR-050).
 */
public final class MenuSession {

  /** Immutable snapshot of a navigation frame on the viewer's menu stack. */
  public record NavigationEntry(MenuDescriptor descriptor, int pageIndex) {
    public NavigationEntry {
      Objects.requireNonNull(descriptor, "descriptor");
      if (pageIndex < 0 || pageIndex >= descriptor.pages().size()) {
        throw new IllegalArgumentException(
            "pageIndex out of bounds: " + pageIndex + " (pages=" + descriptor.pages().size() + ")");
      }
    }
  }

  private final UUID viewer;
  private final int maxHistoryDepth;
  private final Deque<NavigationEntry> history = new ArrayDeque<>();
  private final Map<String, LinkedHashMap<String, String>> stagedByScope = new LinkedHashMap<>();

  /** Creates a session for {@code viewer} with a default maximum history depth of {@code 32}. */
  public MenuSession(UUID viewer) {
    this(viewer, 32);
  }

  /**
   * Creates a session for {@code viewer} with a bounded navigation history depth.
   *
   * @param viewer viewer UUID; must not be {@code null}
   * @param maxHistoryDepth maximum navigation stack depth ({@code >= 1})
   */
  public MenuSession(UUID viewer, int maxHistoryDepth) {
    this.viewer = Objects.requireNonNull(viewer, "viewer");
    if (maxHistoryDepth < 1) {
      throw new IllegalArgumentException("maxHistoryDepth must be >= 1, got " + maxHistoryDepth);
    }
    this.maxHistoryDepth = maxHistoryDepth;
  }

  /** Returns the viewer UUID owning this session. */
  public UUID viewer() {
    return viewer;
  }

  /**
   * Pushes a menu descriptor and zero-based page index onto the navigation stack, clamping
   * {@code pageIndex} to the descriptor's page range.
   */
  public synchronized NavigationEntry push(MenuDescriptor descriptor, int pageIndex) {
    Objects.requireNonNull(descriptor, "descriptor");
    int clamped = Math.max(0, Math.min(pageIndex, descriptor.pages().size() - 1));
    NavigationEntry entry = new NavigationEntry(descriptor, clamped);
    while (history.size() >= maxHistoryDepth) {
      history.removeLast();
    }
    history.push(entry);
    return entry;
  }

  /**
   * Pops the current navigation frame and returns the previous frame on the stack, if any.
   *
   * @return the new top-of-stack navigation frame after popping, or {@link Optional#empty()}
   */
  public synchronized Optional<NavigationEntry> pop() {
    if (history.isEmpty()) {
      return Optional.empty();
    }
    history.pop();
    return Optional.ofNullable(history.peek());
  }

  /** Returns the current top-of-stack navigation entry, if any. */
  public synchronized Optional<NavigationEntry> current() {
    return Optional.ofNullable(history.peek());
  }

  /** Returns the current active {@link MenuDescriptor}, if any. */
  public synchronized Optional<MenuDescriptor> currentDescriptor() {
    NavigationEntry top = history.peek();
    return top == null ? Optional.empty() : Optional.of(top.descriptor());
  }

  /** Returns the current zero-based page index, or {@code 0} when no descriptor is open. */
  public synchronized int currentPageIndex() {
    NavigationEntry top = history.peek();
    return top == null ? 0 : top.pageIndex();
  }

  /**
   * Updates the active page index on the current descriptor, clamping to valid page bounds.
   *
   * @throws IllegalStateException if no descriptor is currently open
   */
  public synchronized NavigationEntry setCurrentPageIndex(int pageIndex) {
    NavigationEntry top = history.peek();
    if (top == null) {
      throw new IllegalStateException("No active MenuDescriptor in session for viewer " + viewer);
    }
    if (pageIndex < 0) {
      throw new IllegalArgumentException("pageIndex must be >= 0, got " + pageIndex);
    }
    int clamped = Math.min(pageIndex, top.descriptor().pages().size() - 1);
    NavigationEntry updated = new NavigationEntry(top.descriptor(), clamped);
    history.pop();
    history.push(updated);
    return updated;
  }

  /** Returns {@code true} when there is a previous menu frame to return to via {@link #pop()}. */
  public synchronized boolean canGoBack() {
    return history.size() > 1;
  }

  /** Returns the number of frames currently on the navigation stack. */
  public synchronized int historyDepth() {
    return history.size();
  }

  /** Clears all navigation history. */
  public synchronized void clearHistory() {
    history.clear();
  }

  /** Stages a {@code key=value} entry under {@code scopeName} in insertion order. */
  public synchronized void stage(String scopeName, String key, String value) {
    Objects.requireNonNull(scopeName, "scopeName");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    if (scopeName.isEmpty()) {
      throw new IllegalArgumentException("scopeName must not be empty");
    }
    if (key.isEmpty()) {
      throw new IllegalArgumentException("key must not be empty");
    }
    stagedByScope.computeIfAbsent(scopeName, ignored -> new LinkedHashMap<>()).put(key, value);
  }

  /** Removes a staged key under {@code scopeName}. Returns {@code true} if an entry was removed. */
  public synchronized boolean unstage(String scopeName, String key) {
    Objects.requireNonNull(scopeName, "scopeName");
    Objects.requireNonNull(key, "key");
    LinkedHashMap<String, String> cart = stagedByScope.get(scopeName);
    if (cart == null) {
      return false;
    }
    boolean removed = cart.remove(key) != null;
    if (cart.isEmpty()) {
      stagedByScope.remove(scopeName);
    }
    return removed;
  }

  /** Returns an immutable insertion-ordered snapshot of staged entries for {@code scopeName}. */
  public synchronized Map<String, String> stagedValues(String scopeName) {
    Objects.requireNonNull(scopeName, "scopeName");
    LinkedHashMap<String, String> cart = stagedByScope.get(scopeName);
    if (cart == null || cart.isEmpty()) {
      return Map.of();
    }
    return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(cart));
  }

  /** Returns whether any staged edits exist for {@code scopeName}. */
  public synchronized boolean hasStagedChanges(String scopeName) {
    Objects.requireNonNull(scopeName, "scopeName");
    LinkedHashMap<String, String> cart = stagedByScope.get(scopeName);
    return cart != null && !cart.isEmpty();
  }

  /** Clears staged values for {@code scopeName} and returns the removed snapshot. */
  public synchronized Map<String, String> clearStaged(String scopeName) {
    Objects.requireNonNull(scopeName, "scopeName");
    LinkedHashMap<String, String> removed = stagedByScope.remove(scopeName);
    if (removed == null || removed.isEmpty()) {
      return Map.of();
    }
    return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(removed));
  }

  /** Clears all staged values across all scopes. */
  public synchronized void clearAllStaged() {
    stagedByScope.clear();
  }
}
