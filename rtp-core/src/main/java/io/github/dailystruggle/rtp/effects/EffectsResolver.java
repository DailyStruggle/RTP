package io.github.dailystruggle.rtp.effects;

import io.github.dailystruggle.effectsapi.common.EffectsGroupKeys;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Resolves the per-player effect-token list for a pipeline stage from {@code effects/} config files.
 *
 * <p>Queries {@link RTP#configs} dynamically so reloads take effect immediately (effects-api-ADR-005).
 * Produces synthetic permission-style strings matching {@code <prefix>.<EFFECT>.<args>}.
 */
public final class EffectsResolver {

  private EffectsResolver() {}

  /**
   * Synthesizes prefixed effect tokens for {@code (stage, player)} by walking loaded group files.
   *
   * @param stage  pipeline stage token (e.g. {@code "postteleport"})
   * @param player target player
   * @param prefix effect-prefix to prepend to every token (e.g. {@code "rtp.effect.postteleport"})
   * @return ordered, de-duplicated list of synthetic permission strings; never null
   */
  public static List<String> resolveTokens(String stage, RTPPlayer player, String prefix) {
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(prefix, "prefix");

    if (RTP.configs == null) return Collections.emptyList();
    MultiConfigParser<EffectsGroupKeys> mcp;
    try {
      mcp = (MultiConfigParser<EffectsGroupKeys>)
          RTP.configs.multiConfigParserMap.get(EffectsGroupKeys.class);
    } catch (ClassCastException cce) {
      RTP.log(Level.WARNING, "[effects] effects/ parser type mismatch", cce);
      return Collections.emptyList();
    }
    if (mcp == null) return Collections.emptyList();

    Map<String, ConfigParser<EffectsGroupKeys>> groupsByName = new LinkedHashMap<>();
    for (ConfigParser<EffectsGroupKeys> cp : mcp.configParserFactory.map.values()) {
      String groupName = stripYml(cp.name);
      groupsByName.put(groupName.toLowerCase(Locale.ROOT), cp);
    }
    if (groupsByName.isEmpty()) return Collections.emptyList();

    String stageLc = stage.toLowerCase(Locale.ROOT);

    // 1) Find non-default groups for this stage that the player passes gating on.
    List<String> matched = new ArrayList<>();
    for (Map.Entry<String, ConfigParser<EffectsGroupKeys>> e : groupsByName.entrySet()) {
      String groupName = e.getKey();
      ConfigParser<EffectsGroupKeys> cp = e.getValue();
      if (isDefaultGroupName(groupName)) continue;
      String when = stringValue(cp, EffectsGroupKeys.when);
      if (when == null || !when.equalsIgnoreCase(stageLc)) continue;
      if (!gates(player, cp)) continue;
      matched.add(groupName);
    }

    // 2) If nothing matched, fall back to a default-for-stage group.
    if (matched.isEmpty()) {
      String def = findDefaultForStage(groupsByName, stageLc);
      if (def != null) matched.add(def);
    }

    if (matched.isEmpty()) return Collections.emptyList();

    // 3) Walk inherit: depth-first, accumulate tokens.
    LinkedHashSet<String> tokens = new LinkedHashSet<>();
    for (String name : matched) {
      Set<String> visiting = new HashSet<>();
      collectTokens(name, groupsByName, stageLc, visiting, tokens);
    }
    if (tokens.isEmpty()) return Collections.emptyList();

    String pfx = prefix.endsWith(".") ? prefix : prefix + ".";
    List<String> out = new ArrayList<>(tokens.size());
    for (String t : tokens) {
      if (t == null || t.isBlank()) continue;
      out.add(pfx + t);
    }
    return out;
  }

  // ---- internals ----

  private static void collectTokens(
      String groupName,
      Map<String, ConfigParser<EffectsGroupKeys>> groupsByName,
      String stageLc,
      Set<String> visiting,
      LinkedHashSet<String> out) {
    String key = groupName.toLowerCase(Locale.ROOT);
    if (!visiting.add(key)) {
      RTP.log(Level.WARNING,
          "[effects] inherit: cycle detected at group '" + groupName + "'; truncating");
      return;
    }
    ConfigParser<EffectsGroupKeys> cp = groupsByName.get(key);
    if (cp == null) {
      RTP.log(Level.WARNING,
          "[effects] inherit: references undefined group '" + groupName + "'");
      visiting.remove(key);
      return;
    }

    // Resolve inherit list. Absent => implicit [stage's default] (only when
    // this group itself isn't a default, to avoid cycles).
    List<String> parents = listValue(cp, EffectsGroupKeys.inherit);
    if (parents == null) {
      // Implicit default-for-stage parent, but only for non-default groups.
      if (!isDefaultGroupName(key)) {
        String def = findDefaultForStage(groupsByName, stageLc);
        if (def != null && !def.equals(key)) parents = Collections.singletonList(def);
        else parents = Collections.emptyList();
      } else {
        parents = Collections.emptyList();
      }
    }
    for (String parent : parents) {
      if (parent == null || parent.isBlank()) continue;
      collectTokens(parent, groupsByName, stageLc, visiting, out);
    }

    // Then emit this group's own tokens.
    List<String> effects = listValue(cp, EffectsGroupKeys.effects);
    if (effects != null) {
      for (String tok : effects) {
        if (tok == null) continue;
        String s = tok.toString().trim();
        if (!s.isEmpty()) out.add(s);
      }
    }

    visiting.remove(key);
  }

  /**
   * A group is treated as "default for stage S" iff its name is exactly
   * {@code default} and its {@code when:} == S, or its name is
   * {@code default-<S>} (matches the project's on-disk convention -
   * {@code default-pre.yml}, {@code default-cancel.yml}).
   */
  private static String findDefaultForStage(
      Map<String, ConfigParser<EffectsGroupKeys>> groupsByName, String stageLc) {
    // Prefer the literal `default` if its when: matches.
    ConfigParser<EffectsGroupKeys> defParser = groupsByName.get("default");
    if (defParser != null) {
      String w = stringValue(defParser, EffectsGroupKeys.when);
      if (w != null && w.equalsIgnoreCase(stageLc)) return "default";
    }
    // Fall back to default-<stage> by name match.
    String suffix = "default-" + stageLc;
    if (groupsByName.containsKey(suffix)) return suffix;
    // Last resort: any group whose name starts with "default-" and whose
    // when: matches the stage.
    for (Map.Entry<String, ConfigParser<EffectsGroupKeys>> e : groupsByName.entrySet()) {
      String n = e.getKey();
      if (!n.startsWith("default")) continue;
      String w = stringValue(e.getValue(), EffectsGroupKeys.when);
      if (w != null && w.equalsIgnoreCase(stageLc)) return n;
    }
    return null;
  }

  private static boolean isDefaultGroupName(String groupNameLc) {
    return groupNameLc.equals("default") || groupNameLc.startsWith("default-");
  }

  private static boolean gates(RTPPlayer player, ConfigParser<EffectsGroupKeys> cp) {
    String perm = stringValue(cp, EffectsGroupKeys.permission);
    List<String> players = listValue(cp, EffectsGroupKeys.players);
    boolean permGated = perm != null && !perm.isBlank();
    boolean playersGated = players != null && !players.isEmpty();

    // Ungated group (no permission, no players list): always matches.
    if (!permGated && !playersGated) return true;

    if (permGated) {
      try {
        if (player.hasPermission(perm.trim())) return true;
      } catch (Throwable t) {
        // Permission backends throw on platforms without one (Fabric without
        // fabric-permissions-api); treat as "no" rather than failing the lookup.
      }
    }
    if (playersGated) {
      UUID uuid = player.uuid();
      String name = player.name();
      String uuidStr = uuid != null ? uuid.toString() : null;
      for (String entry : players) {
        if (entry == null) continue;
        String e = entry.trim();
        if (e.isEmpty()) continue;
        if (uuidStr != null && e.equalsIgnoreCase(uuidStr)) return true;
        if (name != null && e.equalsIgnoreCase(name)) return true;
      }
    }
    return false;
  }

  private static String stringValue(ConfigParser<EffectsGroupKeys> cp, EffectsGroupKeys key) {
    Object o = cp.getConfigValue(key, null);
    if (o == null) return null;
    String s = o.toString().trim();
    return s.isEmpty() ? null : s;
  }

  @SuppressWarnings("unchecked")
  private static List<String> listValue(ConfigParser<EffectsGroupKeys> cp, EffectsGroupKeys key) {
    Object o = cp.getConfigValue(key, null);
    if (o == null) return null;
    if (o instanceof List<?> raw) {
      List<String> out = new ArrayList<>(raw.size());
      for (Object e : raw) {
        if (e == null) continue;
        out.add(e.toString());
      }
      return out;
    }
    // Tolerate a single-string scalar: treat as a one-element list.
    String s = o.toString().trim();
    if (s.isEmpty()) return Collections.emptyList();
    return Collections.singletonList(s);
  }

  private static String stripYml(String fileName) {
    if (fileName == null) return "";
    String s = fileName;
    if (s.toLowerCase(Locale.ROOT).endsWith(".yml")) {
      s = s.substring(0, s.length() - 4);
    }
    return s;
  }

  /**
   * Builds union of permission-derived nodes and config-resolved tokens for a given stage.
   * Returns collection ready for {@code EffectFactory.buildEffects(prefix, ...)}.
   */
  public static Collection<String> resolveUnioned(
      String stage, RTPPlayer player, String prefix, Collection<String> permissionNodes) {
    LinkedHashSet<String> union = new LinkedHashSet<>();
    if (permissionNodes != null) {
      for (String n : permissionNodes) {
        if (n != null) union.add(n);
      }
    }
    union.addAll(resolveTokens(stage, player, prefix));
    return union;
  }
}
