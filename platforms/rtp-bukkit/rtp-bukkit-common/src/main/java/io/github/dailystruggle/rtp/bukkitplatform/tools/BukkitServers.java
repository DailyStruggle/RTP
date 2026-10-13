package io.github.dailystruggle.rtp.bukkitplatform.tools;

import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Null-tolerant reads of Bukkit values annotated {@code @NotNull} that are null before server
 * bootstrap and under unit-test mocks ({@code Bukkit.getServer()}, a mocked
 * {@code Player#getLocation()}).
 */
public final class BukkitServers {
  private BukkitServers() {}

  /** {@code true} when no Bukkit server is bound (pre-bootstrap or unit tests). */
  public static boolean absent() {
    return Optional.ofNullable(Bukkit.getServer()).isEmpty();
  }

  /** Current location of {@code player}, empty when the implementation returns none. */
  public static Optional<Location> location(Player player) {
    return Optional.ofNullable(player.getLocation());
  }
}
