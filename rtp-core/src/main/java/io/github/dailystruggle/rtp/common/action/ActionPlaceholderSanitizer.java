package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sanitizer and resolver for lifecycle placeholders (ADR-093 Section 4).
 *
 * <p>Placeholders ([player], [player_name], [player_uuid], [violator], [winner], [victim], [session_id])
 * resolve to sanitized player usernames, UUIDs, or safe target strings to prevent command-injection vulnerabilities.
 */
public final class ActionPlaceholderSanitizer {

  private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\[([a-zA-Z0-9_]+)\\]");
  private static final Pattern SAFE_TOKEN_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+(?: [a-zA-Z0-9_-]+)*+$");

  public static final Set<String> COMMON_TOKENS = Set.of(
      "session_id", "players", "participants", "all", "sender", "challenger", "target", "opponent",
      "sender_name", "sender_uuid", "player_name", "player_uuid", "target_name", "target_uuid", "clusters");
  public static final Map<String, Set<String>> STAGE_TOKENS = Map.of(
      "onStart", Set.of(),
      "onBoundaryViolation", Set.of("violator"),
      "onExpire", Set.of(),
      "onDeath", Set.of("victim", "winner"),
      "onEnqueue", Set.of("player"),
      "onCancel", Set.of("player", "canceller"),
      "onWait", Set.of("player"));
  public static final Set<String> OUTSIDE_ACTION = Set.of("player", "violator", "world");
  public static final Set<String> SUBCOMMAND = Set.of("player", "action_id");
  public static final Set<String> PRE_GATE = Set.of("player");
  public static final Set<String> FOR_EACH = Set.of("player");
  public static final Set<String> GATE_COMMAND = Set.of("player", "violator");
  public static final Set<String> FAN_OUT = Set.of("player", "player_name", "player_uuid");

  private static final Set<String> KNOWN_TOKENS = knownTokenSet();

  private ActionPlaceholderSanitizer() {}

  /** Extracts distinct placeholder names in normalized form, ignoring non-token bracket text. */
  public static Set<String> placeholders(String text) {
    if (text == null || text.isBlank()) return Set.of();
    Set<String> result = new LinkedHashSet<>();
    Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
    while (matcher.find()) result.add(matcher.group(1).toLowerCase(java.util.Locale.ROOT));
    return Collections.unmodifiableSet(result);
  }

  /** Checks direct, suffix-derived, numbered, and named-cluster tokens against one declared scope. */
  public static boolean inScope(String token, Set<String> scope) {
    if (token == null || token.isBlank() || scope == null || scope.isEmpty()) return false;
    String normalized = token.toLowerCase(java.util.Locale.ROOT);
    if (scope.contains(normalized)) return true;

    String withoutNumber = normalized.replaceFirst("_\\d+$", "");
    if (!withoutNumber.equals(normalized) && inScope(withoutNumber, scope)) return true;

    for (String suffix : Set.of("_name", "_uuid")) {
      if (normalized.endsWith(suffix)
          && inScope(normalized.substring(0, normalized.length() - suffix.length()), scope)) return true;
    }

    return (normalized.matches("player_\\d+") && scope.contains("players"))
        || ((normalized.startsWith("cluster_") || normalized.startsWith("group_"))
        && (scope.contains("clusters") || scope.contains("cluster") || scope.contains("group")));
  }

  /** Canonical tokens recognized by the action engine, excluding arbitrary metadata keys. */
  public static Set<String> knownTokens() {
    return KNOWN_TOKENS;
  }

  private static Set<String> knownTokenSet() {
    Set<String> tokens = new HashSet<>(COMMON_TOKENS);
    tokens.addAll(Set.of("player", "victim", "winner", "violator", "canceller", "world", "action_id"));
    return Collections.unmodifiableSet(tokens);
  }

  /**
   * Replaces placeholders in {@code command} with sanitized values from {@code contextTokens}.
   *
   * @param command       the command template string (e.g. "tag [player] add rtp_session_[session_id]")
   * @param contextTokens tokens map (e.g. player -> UUID/RTPPlayer/name, session_id -> hash/uuid)
   * @return escaped and substituted command string
   */
  public static String substitute(String command, Map<String, Object> contextTokens) {
    if (command == null || command.isBlank()) return "";
    if (contextTokens == null || contextTokens.isEmpty()) return command;

    Matcher matcher = PLACEHOLDER_PATTERN.matcher(command);
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
      String key = matcher.group(1).toLowerCase();
      String replacement = resolveToken(key, contextTokens);
      if (replacement == null) {
        replacement = matcher.group(0); // Keep verbatim if unmapped
      }
      matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(sb);
    return sb.toString();
  }

  /**
   * Checks whether a command template references target placeholders (e.g. [target], [target_name],
   * [target_uuid], [target_name_1]) that are missing or resolve to empty in {@code contextTokens}.
   *
   * @param command       the command template string
   * @param contextTokens tokens map
   * @return true if target placeholders are required by {@code command} but missing or blank in {@code contextTokens}
   */
  public static boolean hasMissingTarget(String command, Map<String, Object> contextTokens) {
    if (command == null || command.isBlank()) return false;
    Matcher matcher = PLACEHOLDER_PATTERN.matcher(command);
    while (matcher.find()) {
      String key = matcher.group(1).toLowerCase();
      if (key.equals("target") || key.startsWith("target_")) {
        String resolved = (contextTokens != null) ? resolveToken(key, contextTokens) : null;
        if (resolved == null || resolved.isBlank() || resolved.equalsIgnoreCase("any")) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Checks whether the substituted command string still contains unmapped placeholders
   * matching the specified prefix (e.g. "target").
   */
  public static boolean containsUnresolvedPrefix(String text, String prefix) {
    if (text == null || text.isBlank() || prefix == null || prefix.isBlank()) return false;
    Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
    while (matcher.find()) {
      String key = matcher.group(1).toLowerCase();
      if (key.equals(prefix.toLowerCase()) || key.startsWith(prefix.toLowerCase() + "_")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks whether the substituted text still contains any unresolved placeholder matching [a-zA-Z0-9_]+.
   *
   * @param text the command or message string
   * @return true if an unmapped placeholder is present
   */
  public static boolean containsAnyUnresolvedPlaceholder(String text) {
    if (text == null || text.isBlank()) return false;
    Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
    return matcher.find();
  }

  private static String resolveToken(String key, Map<String, Object> contextTokens) {
    // 1. Direct match in tokens map
    if (contextTokens.containsKey(key)) {
      boolean preferName = isPlayerEntityKey(key);
      return formatValue(contextTokens.get(key), preferName);
    }

    // 2. Specialized token suffixes: _name and _uuid
    if (key.endsWith("_name")) {
      String baseKey = key.substring(0, key.length() - 5);
      if (contextTokens.containsKey(baseKey)) {
        return formatValue(contextTokens.get(baseKey), true);
      }
    } else if (key.endsWith("_uuid")) {
      String baseKey = key.substring(0, key.length() - 5);
      if (contextTokens.containsKey(baseKey)) {
        Object val = contextTokens.get(baseKey);
        if (val instanceof UUID u) {
          return u.toString();
        } else if (val instanceof RTPCommandSender s) {
          return s.uuid().toString();
        }
      }
    }

    // 3. Numbered patterns with suffix: e.g. sender_name_1 -> base sender_1
    int lastUnderscore = key.lastIndexOf('_');
    if (lastUnderscore > 0 && lastUnderscore < key.length() - 1) {
      String numSuffix = key.substring(lastUnderscore); // e.g. "_1"
      String prefix = key.substring(0, lastUnderscore); // e.g. "sender_name"
      if (prefix.endsWith("_name")) {
        String basePrefix = prefix.substring(0, prefix.length() - 5); // e.g. "sender"
        String fallbackKey = basePrefix + numSuffix; // e.g. "sender_1"
        if (contextTokens.containsKey(fallbackKey)) {
          return formatValue(contextTokens.get(fallbackKey), true);
        }
      } else if (prefix.endsWith("_uuid")) {
        String basePrefix = prefix.substring(0, prefix.length() - 5);
        String fallbackKey = basePrefix + numSuffix;
        if (contextTokens.containsKey(fallbackKey)) {
          Object val = contextTokens.get(fallbackKey);
          if (val instanceof UUID u) {
            return u.toString();
          } else if (val instanceof RTPCommandSender s) {
            return s.uuid().toString();
          }
        }
      }
      // Other numbered keys (prefix + numSuffix == key) were already tried by the direct lookup.
    }

    return null;
  }

  private static boolean isPlayerEntityKey(String key) {
    if (key == null) return false;
    return key.equals("player") || key.equals("player_name")
        || key.equals("target") || key.equals("target_name")
        || key.equals("sender") || key.equals("sender_name")
        || key.equals("violator") || key.equals("violator_name")
        || key.equals("winner") || key.equals("winner_name")
        || key.equals("victim") || key.equals("victim_name")
        || key.endsWith("_name")
        || key.startsWith("player_") || key.startsWith("target_")
        || key.startsWith("sender_") || key.startsWith("violator_")
        || key.startsWith("winner_") || key.startsWith("victim_");
  }

  private static String formatValue(Object val, boolean preferNameOnly) {
    if (val == null) return "";

    if (val instanceof RTPCommandSender sender) {
      if (preferNameOnly) {
        String name = sender.name();
        if (name != null && SAFE_TOKEN_PATTERN.matcher(name).matches()) {
          return name;
        }
      }
      return sender.uuid().toString();
    }

    if (val instanceof UUID uuid) {
      if (preferNameOnly) {
        RTPServerAccessor accessor = RTPRuntime.serverAccessor();
        if (accessor != null) {
          RTPCommandSender sender = accessor.getPlayer(uuid);
          if (sender != null) {
            String name = sender.name();
            if (name != null && SAFE_TOKEN_PATTERN.matcher(name).matches()) {
              return name;
            }
          }
        }
      }
      return uuid.toString();
    }

    if (val instanceof java.util.Collection<?> coll) {
      StringBuilder listSb = new StringBuilder();
      for (Object item : coll) {
        if (item == null) continue;
        String formatted = formatValue(item, preferNameOnly);
        if (!formatted.isBlank()) {
          if (!listSb.isEmpty()) listSb.append(' ');
          listSb.append(formatted);
        }
      }
      return listSb.toString();
    }

    String s = val.toString().trim();
    // Strict sanitization: allow alphanumeric, underscores, hyphens, and single spaces
    if (SAFE_TOKEN_PATTERN.matcher(s).matches()) {
      return s;
    }
    return ""; // Reject unsanitized token
  }
}
