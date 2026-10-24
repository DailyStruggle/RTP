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

  private static final String NAME_SUFFIX = "_name";
  private static final String UUID_SUFFIX = "_uuid";
  private static final String PLAYER = "player";
  private static final String PLAYER_PREFIX = PLAYER + "_";
  private static final String PLAYER_NAME = PLAYER + NAME_SUFFIX;
  private static final String PLAYER_UUID = PLAYER + UUID_SUFFIX;
  private static final String TARGET = "target";
  private static final String TARGET_PREFIX = TARGET + "_";
  private static final String TARGET_NAME = TARGET + NAME_SUFFIX;
  private static final String TARGET_UUID = TARGET + UUID_SUFFIX;
  private static final String VIOLATOR = "violator";
  private static final String VIOLATOR_NAME = VIOLATOR + NAME_SUFFIX;
  private static final String WINNER = "winner";
  private static final String WINNER_NAME = WINNER + NAME_SUFFIX;
  private static final String VICTIM = "victim";
  private static final String VICTIM_NAME = VICTIM + NAME_SUFFIX;

  private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\[(\\w+)\\]");
  private static final Pattern SAFE_TOKEN_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+(?: [a-zA-Z0-9_-]+)*+$");

  public static final Set<String> COMMON_TOKENS = Set.of(
      "session_id", "players", "participants", "all", "sender", "challenger", TARGET, "opponent",
      "sender_name", "sender_uuid", PLAYER_NAME, PLAYER_UUID, TARGET_NAME, TARGET_UUID, "clusters");
  public static final Map<String, Set<String>> STAGE_TOKENS = Map.of(
      "onStart", Set.of(),
      "onBoundaryViolation", Set.of(VIOLATOR),
      "onExpire", Set.of(),
      "onDeath", Set.of(VICTIM, WINNER),
      "onEnqueue", Set.of(PLAYER),
      "onCancel", Set.of(PLAYER, "canceller"),
      "onWait", Set.of(PLAYER));
  public static final Set<String> OUTSIDE_ACTION = Set.of(PLAYER, VIOLATOR, "world");
  public static final Set<String> SUBCOMMAND = Set.of(PLAYER, "action_id");
  public static final Set<String> PRE_GATE = Set.of(PLAYER);
  public static final Set<String> FOR_EACH = Set.of(PLAYER);
  public static final Set<String> GATE_COMMAND = Set.of(PLAYER, VIOLATOR);
  public static final Set<String> FAN_OUT = Set.of(PLAYER, PLAYER_NAME, PLAYER_UUID);

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

    String withoutNumber = withoutNumericSuffix(normalized);
    if (!withoutNumber.equals(normalized) && inScope(withoutNumber, scope)) return true;

    for (String suffix : Set.of(NAME_SUFFIX, UUID_SUFFIX)) {
      if (normalized.endsWith(suffix)
          && inScope(normalized.substring(0, normalized.length() - suffix.length()), scope)) return true;
    }

    return (normalized.startsWith(PLAYER_PREFIX) && hasNumericSuffix(normalized, PLAYER_PREFIX.length())
            && scope.contains("players"))
        || ((normalized.startsWith("cluster_") || normalized.startsWith("group_"))
        && (scope.contains("clusters") || scope.contains("cluster") || scope.contains("group")));
  }

  private static String withoutNumericSuffix(String value) {
    int separator = value.lastIndexOf('_');
    if (separator < 0 || !hasNumericSuffix(value, separator + 1)) return value;
    return value.substring(0, separator);
  }

  private static boolean hasNumericSuffix(String value, int start) {
    if (start >= value.length()) return false;
    for (int index = start; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < '0' || character > '9') return false;
    }
    return true;
  }

  /** Canonical tokens recognized by the action engine, excluding arbitrary metadata keys. */
  public static Set<String> knownTokens() {
    return KNOWN_TOKENS;
  }

  private static Set<String> knownTokenSet() {
    Set<String> tokens = new HashSet<>(COMMON_TOKENS);
    tokens.addAll(Set.of(PLAYER, VICTIM, WINNER, VIOLATOR, "canceller", "world", "action_id"));
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
      if (key.equals(TARGET) || key.startsWith(TARGET_PREFIX)) {
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
    if (contextTokens.containsKey(key)) {
      return formatValue(contextTokens.get(key), isPlayerEntityKey(key));
    }
    String suffixed = resolveSuffix(key, contextTokens, NAME_SUFFIX, true);
    if (suffixed != null) return suffixed;
    suffixed = resolveSuffix(key, contextTokens, UUID_SUFFIX, false);
    if (suffixed != null) return suffixed;
    return resolveNumberedSuffix(key, contextTokens);
  }

  private static String resolveSuffix(
      String key, Map<String, Object> contextTokens, String suffix, boolean preferName) {
    if (!key.endsWith(suffix)) return null;
    String baseKey = key.substring(0, key.length() - suffix.length());
    return resolveSuffixValue(baseKey, contextTokens, preferName);
  }

  private static String resolveNumberedSuffix(String key, Map<String, Object> contextTokens) {
    int lastUnderscore = key.lastIndexOf('_');
    if (lastUnderscore <= 0 || lastUnderscore >= key.length() - 1) return null;
    String prefix = key.substring(0, lastUnderscore);
    String suffix = tokenSuffix(prefix);
    if (suffix == null) return null;
    String numberedBase = prefix.substring(0, prefix.length() - suffix.length())
        + key.substring(lastUnderscore);
    return resolveSuffixValue(numberedBase, contextTokens, NAME_SUFFIX.equals(suffix));
  }

  private static String tokenSuffix(String key) {
    if (key.endsWith(NAME_SUFFIX)) return NAME_SUFFIX;
    if (key.endsWith(UUID_SUFFIX)) return UUID_SUFFIX;
    return null;
  }

  private static String resolveSuffixValue(
      String baseKey, Map<String, Object> contextTokens, boolean preferName) {
    if (!contextTokens.containsKey(baseKey)) return null;
    Object value = contextTokens.get(baseKey);
    return preferName ? formatValue(value, true) : formatUuid(value);
  }

  private static String formatUuid(Object value) {
    if (value instanceof UUID uuid) return uuid.toString();
    if (value instanceof RTPCommandSender sender) return sender.uuid().toString();
    return null;
  }

  private static boolean isPlayerEntityKey(String key) {
    if (key == null) return false;
    return key.equals(PLAYER) || key.equals(PLAYER_NAME)
        || key.equals(TARGET) || key.equals(TARGET_NAME)
        || key.equals("sender") || key.equals("sender_name")
        || key.equals(VIOLATOR) || key.equals(VIOLATOR_NAME)
        || key.equals(WINNER) || key.equals(WINNER_NAME)
        || key.equals(VICTIM) || key.equals(VICTIM_NAME)
        || key.endsWith(NAME_SUFFIX)
        || key.startsWith(PLAYER_PREFIX) || key.startsWith(TARGET_PREFIX)
        || key.startsWith("sender_") || key.startsWith("violator_")
        || key.startsWith("winner_") || key.startsWith("victim_");
  }

  private static String formatValue(Object val, boolean preferNameOnly) {
    if (val == null) return "";
    if (val instanceof RTPCommandSender sender) return formatSender(sender, preferNameOnly);
    if (val instanceof UUID uuid) return formatPlayerUuid(uuid, preferNameOnly);
    if (val instanceof java.util.Collection<?> coll) return formatCollection(coll, preferNameOnly);
    return sanitizeValue(val.toString());
  }

  private static String formatSender(RTPCommandSender sender, boolean preferNameOnly) {
    String name = preferNameOnly ? safeName(sender.name()) : null;
    return name == null ? sender.uuid().toString() : name;
  }

  private static String formatPlayerUuid(UUID uuid, boolean preferNameOnly) {
    if (uuid == null) return "";
    String name = preferNameOnly ? onlinePlayerName(uuid) : null;
    return name == null ? uuid.toString() : name;
  }

  private static String onlinePlayerName(UUID uuid) {
    RTPServerAccessor accessor = RTPRuntime.serverAccessor();
    if (accessor == null) return null;
    RTPCommandSender sender = accessor.getPlayer(uuid);
    return sender == null ? null : safeName(sender.name());
  }

  private static String safeName(String name) {
    return name != null && SAFE_TOKEN_PATTERN.matcher(name).matches() ? name : null;
  }

  private static String formatCollection(java.util.Collection<?> coll, boolean preferNameOnly) {
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

  private static String sanitizeValue(String value) {
    String sanitized = value.trim();
    // Strict sanitization: allow alphanumeric, underscores, hyphens, and single spaces
    return SAFE_TOKEN_PATTERN.matcher(sanitized).matches() ? sanitized : "";
  }
}
