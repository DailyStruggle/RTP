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
 * Resolves the per-player effect-token list for a given pipeline stage by
 * reading the {@code effects/} {@link MultiConfigParser} registered in
 * {@link io.github.dailystruggle.rtp.common.configuration.Configs#reloadConfigs()}.
 *
 * <p>See <em>effects-api-ADR-005</em>. The resolver is stateless; it queries
 * {@link RTP#configs} on every call so a {@code /rtp reload} (which atomically
 * swaps {@code multiConfigParserMap}) is honored without any cache invalidation
 * on this layer. Callers must invoke once per teleport, never cache the result
 * across pipeline stages.
 *
 * <p>Output: a {@link Collection} of synthetic permission-style strings of the
 * form {@code <prefix>.<EFFECT>.<arg1>.<arg2>...} that feeds directly into
 * {@code EffectFactory.buildEffects(prefix, Collection&lt;String&gt;)} —
 * <strong>no factory changes are required</strong>. The handler typically
 * unions these with the player's real {@code getEffectivePermissions()} so
 * permission-driven and config-driven effects co-exist on Bukkit.
 *
 * <p>Resolution algorithm (see ADR-005):
 * <ol>
 *   <li>Iterate every group file (each is a {@link ConfigParser} entry under
 *       the {@code effects/} {@link MultiConfigParser}).</li>
 *   <li>Filter to those whose {@code when:} value matches the requested stage
 *       (case-insensitive).</li>
 *   <li>Skip the {@code default} group during the matching pass — it is the
 *       implicit fallback applied last.</li>
 *   <li>For each remaining group, gate by {@code permission:} (via
 *       {@link RTPPlayer#hasPermission(String)}) or by membership in the
 *       {@code players:} allowlist (UUID or name match).</li>
 *   <li>If <em>no</em> non-default group matched, fall back to the stage's
 *       {@code default} group (or {@code default-<stage>} when present).</li>
 *   <li>For every selected group, depth-first walk {@code inherit:} (cycle-
 *       guarded) and emit each parent's tokens before its own.</li>
 * </ol>
 *
 * <p>S-005: pure in-memory parser-map lookup — no chunk I/O, no file I/O.
 * S-006: never returns {@code null}; an empty list signals "no applicable
 * groups". S-007: parse errors surface via {@link RTP#log} at WARNING.
 */
public final class EffectsResolver {

  private EffectsResolver() {}

  /**
   * Synthesise prefixed effect tokens for {@code (stage, player)} by walking
   * the loaded {@code effects/} group files. Returns an empty list when the
   * {@code effects/} parser hasn't been registered yet (e.g. before
   * {@code Configs.reloadConfigs()} has run for the first time) — caller
   * still proceeds with permission-derived effects.
   *
   * @param stage  pipeline stage token (e.g. {@code "postteleport"});
   *               compared case-insensitively against each group's {@code when:}.
   * @param player target player; gating reads {@code uuid()}, {@code name()},
   *               and {@code hasPermission(...)}.
   * @param prefix effect-prefix to prepend to every token (e.g.
   *               {@code "rtp.effect.postteleport"}); the trailing dot is
   *               appended if absent so the result feeds straight into
   *               {@code EffectFactory.buildEffects}.
   * @return ordered, de-duplicated list of synthetic permission strings;
   *         empty when nothing applies. Never {@code null}.
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
   * {@code default-<S>} (matches the project's on-disk convention —
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
   * Convenience: build the union of permission-derived nodes and config-resolved
   * tokens for a given stage. Useful for handlers that previously only fed
   * {@code player.getEffectivePermissions()} into {@code EffectFactory.buildEffects}.
   *
   * @param stage  pipeline stage (e.g. {@code "postteleport"})
   * @param player target player
   * @param prefix permission prefix (e.g. {@code "rtp.effect.postteleport"})
   * @param permissionNodes player's effective permissions; may be {@code null} or empty
   * @return union list ready for {@code EffectFactory.buildEffects(prefix, …)}
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
