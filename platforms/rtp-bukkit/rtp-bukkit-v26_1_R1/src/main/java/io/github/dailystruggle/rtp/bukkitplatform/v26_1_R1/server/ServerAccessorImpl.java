package io.github.dailystruggle.rtp.bukkitplatform.v26_1_R1.server;


import io.github.dailystruggle.rtp.bukkitplatform.server.AbstractServerAccessor;

import org.jetbrains.annotations.NotNull;

public class ServerAccessorImpl extends AbstractServerAccessor {
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

}
