package io.github.dailystruggle.rtp.common.commands.parameters;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class RegionParameter extends CommandParameter {

  /**
   * Optional supplier of <em>additional</em> values to surface in
   * tab-completion alongside the local {@code RTP.selectionAPI.regionNames()}
   * set. Used in network mode (rtp-proxy-ADR-014) to advertise
   * peer-qualified entries such as {@code backend-a:default} so a player
   * can target a specific remote backend's region without leaving the
   * single {@code /rtp region=} arg slot.
   *
   * <p>Default supplier returns an empty set, so single-server deployments
   * see byte-identical behaviour. Installed by {@code NetworkModeBootstrap}
   * (in {@code rtp-plugin}) via the 4-arg ctor when network mode is enabled.</p>
   */
  private final Supplier<Set<String>> extraValues;

  public RegionParameter(
      String permission, String description, BiFunction<UUID, String, Boolean> isRelevant) {
    this(permission, description, isRelevant, Set::of);
  }

  public RegionParameter(
      String permission,
      String description,
      BiFunction<UUID, String, Boolean> isRelevant,
      Supplier<Set<String>> extraValues) {
    super(permission, description, isRelevant);
    this.extraValues = extraValues == null ? Set::of : extraValues;
    subParamMap.putIfAbsent("DEFAULT", new ConcurrentHashMap<>());
  }

  @Override
  public Set<String> values() {
    Set<String> local = RTP.selectionAPI.regionNames();
    Set<String> extras;
    try {
      extras = extraValues.get();
    } catch (Throwable ignored) {
      // S-004 spirit: a flaky extras supplier should never crash the
      // autocomplete path. Degrade to local-only.
      extras = Set.of();
    }
    if (extras == null || extras.isEmpty()) return local;
    Set<String> merged = new HashSet<>(local.size() + extras.size());
    merged.addAll(local);
    merged.addAll(extras);
    return merged;
  }

  @Override
  public Map<String, CommandParameter> subParams(String parameter) {
    return subParamMap.get("DEFAULT");
  }

  public void put(Map<String, CommandParameter> params) {
    subParamMap.put("DEFAULT", params);
  }

  public void put(String name, CommandParameter param) {
    subParamMap.get("DEFAULT").put(name, param);
  }
}
