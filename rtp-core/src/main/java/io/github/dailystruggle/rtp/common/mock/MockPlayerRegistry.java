package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of synthetic ("mock") players used for testing on a live
 * server when real accounts are scarce.
 *
 * <p>Platform server accessors consult this registry from their player-resolution
 * chokepoints ({@code getPlayer(UUID)}, {@code getPlayer(String)},
 * {@code getSender(UUID)}) and message-routing paths <b>before</b> falling back to
 * the native server lookup (e.g. {@code Bukkit.getPlayer(...)}). This lets a single
 * operator drive one or more fake players through the queue and teleport pipeline.
 *
 * <p>The registry is empty by default; nothing is mocked unless a caller explicitly
 * {@link #register(RTPPlayer) registers} a mock. All methods are thread-safe.
 */
public final class MockPlayerRegistry {

  private static final ConcurrentHashMap<UUID, RTPPlayer> MOCKS = new ConcurrentHashMap<>();

  private MockPlayerRegistry() {}

  /**
   * Registers (or replaces) a mock player keyed by its {@link RTPPlayer#uuid()}.
   *
   * @param player the mock player; must not be {@code null}
   */
  public static void register(RTPPlayer player) {
    if (player == null) return;
    UUID id = player.uuid();
    if (id == null) return;
    MOCKS.put(id, player);
  }

  /**
   * Removes a previously registered mock.
   *
   * @param uuid the mock's id
   * @return the removed mock, or {@code null} if none was registered
   */
  public static RTPPlayer unregister(UUID uuid) {
    if (uuid == null) return null;
    return MOCKS.remove(uuid);
  }

  /** Returns the registered mock for {@code uuid}, or {@code null} if none. */
  public static RTPPlayer get(UUID uuid) {
    if (uuid == null) return null;
    return MOCKS.get(uuid);
  }

  /**
   * Returns the registered mock whose {@link RTPPlayer#name()} equals {@code name}
   * (case-insensitive), or {@code null} if none.
   */
  public static RTPPlayer getByName(String name) {
    if (name == null) return null;
    for (RTPPlayer player : MOCKS.values()) {
      if (name.equalsIgnoreCase(player.name())) return player;
    }
    return null;
  }

  /** Returns {@code true} if a mock is registered for {@code uuid}. */
  public static boolean isMock(UUID uuid) {
    return uuid != null && MOCKS.containsKey(uuid);
  }

  /** Returns {@code true} if no mocks are registered. */
  public static boolean isEmpty() {
    return MOCKS.isEmpty();
  }

  /** Returns an unmodifiable snapshot view of all registered mocks. */
  public static Collection<RTPPlayer> all() {
    return Collections.unmodifiableCollection(MOCKS.values());
  }

  /** Removes every registered mock. */
  public static void clear() {
    MOCKS.clear();
  }
}
