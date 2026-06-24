package io.github.dailystruggle.rtp.paper_v1_21_R1.server;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.paper_v1_21_R1.world.PaperRTPWorld;
import io.github.dailystruggle.rtp.paperplatform.server.PaperServerAccessor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class ServerAccessorImpl extends PaperServerAccessor {
  @Override
  public void releaseAllChunkTickets() {
    Object plugin = getPlugin();
    if (!(plugin instanceof Plugin)) return;
    for (org.bukkit.World world : Bukkit.getWorlds()) {
      world.removePluginChunkTickets((Plugin) plugin);
    }
  }

  @Override
  public @NotNull java.util.Set<String> getBiomes() {
    // Emit both bare upper-cased (`BADLANDS`) and raw namespaced
    // (`minecraft:badlands`) forms so Brigadier-suggested namespaced ids
    // pass /rtp's biome param validator.
    java.util.Set<String> out = new java.util.HashSet<>();
    org.bukkit.Registry.BIOME.stream().forEach(biome -> {
      org.bukkit.NamespacedKey k = biome.getKey();
      out.add(k.getKey().toUpperCase());
      out.add(k.getNamespace() + ":" + k.getKey());
    });
    return out;
  }

  @Override
  public @Nullable RTPWorld<?> getRTPWorld(String name) {
    if (worldMapStr.containsKey(name)) {
      RTPWorld<?> rtpWorld = worldMapStr.get(name);
      if (rtpWorld.isInactive()) {
        worldMapStr.remove(name);
        worldMap.remove(rtpWorld.id());
      } else return rtpWorld;
    }
    World world = Bukkit.getWorld(name);
    if (world == null) return null;
    RTPWorld<?> rtpWorld = new PaperRTPWorld(world);
    worldMap.put(world.getUID(), rtpWorld);
    worldMapStr.put(name, rtpWorld);
    return rtpWorld;
  }

  @Override
  public @Nullable RTPWorld<?> getRTPWorld(UUID id) {
    if (worldMap.containsKey(id)) {
      RTPWorld<?> rtpWorld = worldMap.get(id);
      if (rtpWorld.isInactive()) {
        worldMap.remove(id);
        worldMapStr.remove(rtpWorld.name());
      } else return rtpWorld;
    }
    World world = Bukkit.getWorld(id);
    if (world == null) return null;
    RTPWorld<?> rtpWorld = new PaperRTPWorld(world);
    worldMap.put(id, rtpWorld);
    worldMapStr.put(world.getName(), rtpWorld);
    return rtpWorld;
  }
}
