package io.github.dailystruggle.rtp.common.action;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sanitizer and resolver for lifecycle placeholders (ADR-093 Section 4).
 *
 * <p>Placeholders ([player], [violator], [winner], [victim], [session_id]) strictly
 * resolve to UUIDs or sanitized target strings to prevent command-injection vulnerabilities.
 */
public final class ActionPlaceholderSanitizer {

  private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\[([a-zA-Z0-9_]+)\\]");
  private static final Pattern SAFE_TOKEN_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-]+( [a-zA-Z0-9_\\-]+)*$");

  private ActionPlaceholderSanitizer() {}

  /**
   * Replaces placeholders in {@code command} with sanitized values from {@code contextTokens}.
   *
   * @param command       the command template string (e.g. "tag [player] add rtp_session_[session_id]")
   * @param contextTokens tokens map (e.g. player -> UUID, session_id -> hash/uuid)
   * @return escaped and substituted command string
   */
  public static String substitute(String command, Map<String, Object> contextTokens) {
    if (command == null || command.isBlank()) return "";
    if (contextTokens == null || contextTokens.isEmpty()) return command;

    Matcher matcher = PLACEHOLDER_PATTERN.matcher(command);
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
      String key = matcher.group(1).toLowerCase();
      Object val = contextTokens.get(key);
      String replacement;
      if (val instanceof UUID uuid) {
        replacement = uuid.toString();
      } else if (val instanceof java.util.Collection<?> coll) {
        StringBuilder listSb = new StringBuilder();
        for (Object item : coll) {
          if (item == null) continue;
          String s = item.toString().trim();
          if (SAFE_TOKEN_PATTERN.matcher(s).matches()) {
            if (!listSb.isEmpty()) listSb.append(' ');
            listSb.append(s);
          }
        }
        replacement = listSb.toString();
      } else if (val != null) {
        String s = val.toString().trim();
        // Strict sanitization: allow alphanumeric, underscores, hyphens, and single spaces
        if (SAFE_TOKEN_PATTERN.matcher(s).matches()) {
          replacement = s;
        } else {
          replacement = ""; // Reject unsanitized token
        }
      } else {
        replacement = matcher.group(0); // Keep verbatim if unmapped
      }
      matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(sb);
    return sb.toString();
  }
}
