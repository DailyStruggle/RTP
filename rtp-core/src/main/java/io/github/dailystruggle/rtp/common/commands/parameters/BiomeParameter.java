package io.github.dailystruggle.rtp.common.commands.parameters;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/** Command parameter for biomes */
public class BiomeParameter extends CommandParameter {
  /**
   * Constructor for BiomeParameter
   *
   * @param permission the permission required for the parameter
   * @param description the description of the parameter
   * @param isRelevant the function to check if the parameter is relevant
   */
  public BiomeParameter(
      String permission, String description, BiFunction<UUID, String, Boolean> isRelevant) {
    super(permission, description, isRelevant);
    subParamMap.putIfAbsent("DEFAULT", new ConcurrentHashMap<>());
  }

  // todo: store and update
  @Override
  public Set<String> values() {
    return RTP.serverAccessor.getBiomes();
  }

  /**
   * Biome-first menu / tab-completion source (ADR-063). Rather than offering
   * the whole-server biome set, surface only biomes actually observed within a
   * region the sender can reach, so a selection resolves to a region that
   * contains the biome and the teleport stays bounded. The list is the union
   * of {@code MemoryShape.getObservedBiomes()} over accessible regions, sourced
   * from chunk-I/O-free spatial memory. Falls back to {@link #values()} only
   * when no observed-biome data exists yet (cold-data state) so suggestions are
   * never worse than before the background sampler has run.
   */
  @Override
  public Set<String> relevantValues(UUID senderId) {
    Set<String> observed =
        io.github.dailystruggle.rtp.common.commands.menu.BiomeMenuSource.observedBiomes(senderId);
    if (observed != null && !observed.isEmpty()) return observed;
    return super.relevantValues(senderId);
  }

  @Override
  public Map<String, CommandParameter> subParams(String parameter) {
    return subParamMap.get("DEFAULT");
  }

  /**
   * Add multiple command parameters
   *
   * @param params the map of parameters to add
   */
  public void put(Map<String, CommandParameter> params) {
    subParamMap.put("DEFAULT", params);
  }

  /**
   * Add a command parameter
   *
   * @param name the name of the parameter
   * @param param the parameter to add
   */
  public void put(String name, CommandParameter param) {
    subParamMap.get("DEFAULT").put(name, param);
  }
}
