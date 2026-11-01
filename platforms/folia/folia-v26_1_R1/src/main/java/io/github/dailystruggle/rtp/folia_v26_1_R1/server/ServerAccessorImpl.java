package io.github.dailystruggle.rtp.folia_v26_1_R1.server;


import io.github.dailystruggle.rtp.folia.server.AbstractFoliaServerAccessor;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.jetbrains.annotations.NotNull;

public class ServerAccessorImpl extends AbstractFoliaServerAccessor {
  @Override
  public @NotNull java.util.Set<String> getBiomes() {
    Registry<Biome> biomeRegistry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
    // Emit both bare upper-cased (`BADLANDS`) and raw namespaced
    // (`minecraft:badlands`) forms so Brigadier-suggested namespaced ids
    // pass /rtp's biome param validator.
    java.util.Set<String> out = new java.util.HashSet<>();
    biomeRegistry.stream().forEach(biome -> {
      org.bukkit.NamespacedKey k = biome.getKey();
      out.add(k.getKey().toUpperCase());
      out.add(k.getNamespace() + ":" + k.getKey());
    });
    return out;
  }

}
