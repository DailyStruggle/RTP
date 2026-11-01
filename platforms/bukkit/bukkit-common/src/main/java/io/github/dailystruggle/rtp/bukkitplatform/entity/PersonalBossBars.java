package io.github.dailystruggle.rtp.bukkitplatform.entity;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

/**
 * Personal boss-bars shared by the Bukkit-family {@code RTPPlayer} wrappers, keyed by
 * {@code "<player-uuid>:<bar-id>"}. Static so a fresh wrapper reconciles against the same bar.
 *
 * <p>A Bukkit {@link BossBar} holds its viewers strongly, so a bar left here after its viewer quits
 * pins the whole {@code ServerPlayer} graph. {@link #clearAll(UUID)} runs from the quit listener so
 * no bar survives its player, whether or not the owner ever reached its clear branch.
 */
public final class PersonalBossBars {
  private static final Map<String, BossBar> BARS = new ConcurrentHashMap<>();

  private PersonalBossBars() {}

  private static String key(UUID uuid, String id) {
    return uuid + ":" + id;
  }

  /** Create or update the bar {@code id} for {@code player}. No-op when either is {@code null}. */
  public static void show(Player player, String id, String title, BarColor color, double progress) {
    if (player == null || id == null) return;
    String key = key(player.getUniqueId(), id);
    BossBar bar = BARS.get(key);
    if (bar == null) {
      bar = Bukkit.createBossBar(title, color, BarStyle.SOLID);
      bar.addPlayer(player);
      BARS.put(key, bar);
    } else {
      bar.setTitle(title);
      bar.setColor(color);
      if (!bar.getPlayers().contains(player)) bar.addPlayer(player);
    }
    bar.setProgress(progress);
  }

  /** Remove the bar {@code id} for {@code uuid}, detaching every viewer. */
  public static void clear(UUID uuid, String id) {
    if (uuid == null || id == null) return;
    BossBar bar = BARS.remove(key(uuid, id));
    if (bar != null) bar.removeAll();
  }

  /** Remove every bar owned by {@code uuid}. Called on quit. */
  public static void clearAll(UUID uuid) {
    if (uuid == null) return;
    String prefix = uuid + ":";
    Iterator<Map.Entry<String, BossBar>> it = BARS.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, BossBar> e = it.next();
      if (!e.getKey().startsWith(prefix)) continue;
      it.remove();
      e.getValue().removeAll();
    }
  }

  static int sizeForTesting() {
    return BARS.size();
  }
}
