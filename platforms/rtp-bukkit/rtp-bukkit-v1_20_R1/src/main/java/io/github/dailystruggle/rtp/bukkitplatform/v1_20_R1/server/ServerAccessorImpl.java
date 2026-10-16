package io.github.dailystruggle.rtp.bukkitplatform.v1_20_R1.server;


import io.github.dailystruggle.rtp.bukkitplatform.server.AbstractServerAccessor;

import org.jetbrains.annotations.NotNull;

public class ServerAccessorImpl extends AbstractServerAccessor {
  @Override
  public @NotNull java.util.Set<String> getBiomes() {
    // Emit both forms: bare upper-cased path (`BADLANDS`) and raw namespaced
    // id (`minecraft:badlands`). Brigadier/Paper tab-completion advertises
    // the namespaced form, and the `BiomeParameter#isRelevant` check on the
    // /rtp command was rejecting clicks on those suggestions when only the
    // bare form was present (see RTPCmdBukkit biome lambda).
    // Plain iteration: Registry#stream() only exists from 1.20, and this
    // carrier also serves 1.18-1.19 (ADR-113).
    java.util.Set<String> out = new java.util.HashSet<>();
    for (org.bukkit.block.Biome biome : org.bukkit.Registry.BIOME) {
      org.bukkit.NamespacedKey k = biome.getKey();
      out.add(k.getKey().toUpperCase());
      out.add(k.getNamespace() + ":" + k.getKey());
    }
    return out;
  }

}
