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
    return biomeRegistry.stream().map(biome -> biome.getKey().getKey().toUpperCase()).collect(java.util.stream.Collectors.toSet());
  }

}
