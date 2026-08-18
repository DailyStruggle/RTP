package io.github.dailystruggle.rtp.api.safety;

import io.github.dailystruggle.rtp.api.block.BlockStateString;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure-string grammar parser for safety-list tokens (ADR-017 §1).
 * Rejections are reported via {@link ParseResult#rejected()} (REQ-RTP-S-004). Stateless and thread-safe.
 */
public final class SafetyTokenParser {

  // Grammar identifier production: [A-Za-z0-9_]+
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
  // Grammar namespace: [a-z0-9_.-]+   (validated case-insensitively then lower-cased)
  private static final Pattern NAMESPACE = Pattern.compile("[a-zA-Z0-9_.\\-]+");
  // Grammar path: [a-z0-9_/.-]+
  private static final Pattern PATH = Pattern.compile("[a-zA-Z0-9_/.\\-]+");
  // Property key (reused identifier shape). Values are unconstrained except for commas /
  // brackets / equals; they round-trip as lowercase strings.
  private static final Pattern PROPERTY_KEY = Pattern.compile("[A-Za-z0-9_]+");

  private SafetyTokenParser() {
    // Utility.
  }

  /**
   * Parse a single raw token into a {@link ParseResult}.
   *
   * @param raw raw token; may be null or whitespace
   * @return populated {@link ParseResult}
   */
  public static ParseResult parse(String raw) {
    List<SafetyToken> accepted = new ArrayList<>(1);
    List<Rejection> rejected = new ArrayList<>(1);
    parseOne(raw, accepted, rejected);
    return new ParseResult(accepted, rejected);
  }

  /**
   * Parse every element of the supplied collection. {@code null} and whitespace-only
   * entries are silently dropped (the YAML loader already skips empty list elements;
   * reporting them would be noise).
   *
   * @param rawTokens raw tokens; may be {@code null}.
   * @return combined {@link ParseResult}. Never {@code null}.
   */
  public static ParseResult parseAll(Collection<String> rawTokens) {
    if (rawTokens == null || rawTokens.isEmpty()) {
      return ParseResult.empty();
    }
    List<SafetyToken> accepted = new ArrayList<>(rawTokens.size());
    List<Rejection> rejected = new ArrayList<>();
    for (String raw : rawTokens) {
      if (raw == null) continue;
      String trimmed = raw.trim();
      if (trimmed.isEmpty()) continue;
      parseOne(raw, accepted, rejected);
    }
    return new ParseResult(accepted, rejected);
  }

  private static void parseOne(String raw, List<SafetyToken> out, List<Rejection> rejected) {
    if (raw == null) {
      rejected.add(new Rejection("", "token is null"));
      return;
    }
    String source = raw;
    String token = raw.trim();
    if (token.isEmpty()) {
      rejected.add(new Rejection(source, "token is empty or whitespace-only"));
      return;
    }

    // Structural head/body split, shared with the schematic decoder (ADR-058 Amendment 1).
    String head;
    String body;
    try {
      BlockStateString.Split split = BlockStateString.split(token);
      head = split.head();
      body = split.body();
    } catch (IllegalArgumentException e) {
      rejected.add(new Rejection(source, e.getMessage()));
      return;
    }

    // Parse predicates first so that we can feed them into the factory methods.
    List<StatePredicate> predicates = Collections.emptyList();
    if (body != null) {
      List<StatePredicate> parsed = parsePredicates(source, body, rejected);
      if (parsed == null) {
        // parsePredicates already recorded a rejection.
        return;
      }
      predicates = parsed;
    }

    // Tag vs material dispatch.
    if (head.charAt(0) == '#') {
      parseTagHead(source, head, predicates, out, rejected);
    } else {
      parseMaterialHead(source, head, predicates, out, rejected);
    }
  }

  private static void parseMaterialHead(String source, String head,
                                        List<StatePredicate> predicates,
                                        List<SafetyToken> out, List<Rejection> rejected) {
    if (SafetyToken.WILDCARD.equals(head)) {
      if (predicates.isEmpty()) {
        rejected.add(new Rejection(source,
            "bare '*' is not a valid safety token; wildcard requires at least one predicate "
                + "(e.g. '*[waterlogged=true]')"));
        return;
      }
      out.add(SafetyToken.material(SafetyToken.WILDCARD, predicates, source));
      return;
    }
    if (!IDENTIFIER.matcher(head).matches()) {
      rejected.add(new Rejection(source,
          "material identifier '" + head + "' does not match [A-Za-z0-9_]+"));
      return;
    }
    String normalized = head.toUpperCase(Locale.ROOT);
    out.add(SafetyToken.material(normalized, predicates, source));
  }

  private static void parseTagHead(String source, String head,
                                   List<StatePredicate> predicates,
                                   List<SafetyToken> out, List<Rejection> rejected) {
    // head starts with '#'
    String body = head.substring(1);
    int colon = body.indexOf(':');
    if (colon <= 0 || colon == body.length() - 1) {
      rejected.add(new Rejection(source,
          "tag must be '#namespace:path' with non-empty namespace and path"));
      return;
    }
    String ns = body.substring(0, colon);
    String path = body.substring(colon + 1);
    if (!NAMESPACE.matcher(ns).matches()) {
      rejected.add(new Rejection(source,
          "tag namespace '" + ns + "' does not match [a-z0-9_.-]+"));
      return;
    }
    if (!PATH.matcher(path).matches()) {
      rejected.add(new Rejection(source,
          "tag path '" + path + "' does not match [a-z0-9_/.-]+"));
      return;
    }
    String normalized = ns.toLowerCase(Locale.ROOT) + ":" + path.toLowerCase(Locale.ROOT);
    out.add(SafetyToken.tag(normalized, predicates, source));
  }

  /**
   * Parse the comma-separated predicate body (the text between {@code [} and {@code ]}).
   *
   * @return the parsed predicate list (always singleton for a valid token, since all
   *     predicates inside one {@code [ ... ]} collapse into one AND-combined
   *     {@link StatePredicate}), or {@code null} if a rejection was recorded.
   */
  private static List<StatePredicate> parsePredicates(String source, String body,
                                                      List<Rejection> rejected) {
    String trimmed = body.trim();
    if (trimmed.isEmpty()) {
      rejected.add(new Rejection(source, "empty '[]' body; expected 'key=value[,key=value...]'"));
      return null;
    }
    Map<String, String> kv = new LinkedHashMap<>();
    List<StatePredicate.NumericComparison> comparisons = new ArrayList<>();
    // Track (key|operator) signatures so a range like 'level>=2,level<=5' is allowed while
    // a genuine duplicate ('waterlogged=true,waterlogged=false', 'level>=2,level>=3') is not.
    Set<String> seen = new HashSet<>();
    // Simple split on ',' - values that contain commas are out of scope (ADR-017 &sect;1).
    String[] parts = trimmed.split(",", -1);
    for (String rawPart : parts) {
      String part = rawPart.trim();
      if (part.isEmpty()) {
        rejected.add(new Rejection(source, "empty predicate between commas in '[" + body + "]'"));
        return null;
      }
      // Locate the comparison operator: the first '=', '<' or '>'. A '<' or '>' followed
      // by '=' is the two-character form ('<=' / '>=').
      int opIdx = -1;
      for (int i = 0; i < part.length(); i++) {
        char c = part.charAt(i);
        if (c == '=' || c == '<' || c == '>') {
          opIdx = i;
          break;
        }
      }
      if (opIdx <= 0) {
        rejected.add(new Rejection(source,
            "malformed predicate '" + part + "'; expected 'key=value' or 'key>=n' "
                + "(operators: =, >=, <=, >, <) with non-empty sides"));
        return null;
      }
      char opChar = part.charAt(opIdx);
      StatePredicate.Comparator comparator = null;
      int valueStart;
      if (opChar == '=') {
        valueStart = opIdx + 1;
      } else {
        boolean twoChar = opIdx + 1 < part.length() && part.charAt(opIdx + 1) == '=';
        if (opChar == '>') {
          comparator = twoChar ? StatePredicate.Comparator.GE : StatePredicate.Comparator.GT;
        } else {
          comparator = twoChar ? StatePredicate.Comparator.LE : StatePredicate.Comparator.LT;
        }
        valueStart = opIdx + (twoChar ? 2 : 1);
      }
      String key = part.substring(0, opIdx).trim();
      String value = part.substring(valueStart).trim();
      if (!PROPERTY_KEY.matcher(key).matches()) {
        rejected.add(new Rejection(source,
            "predicate key '" + key + "' does not match [A-Za-z0-9_]+"));
        return null;
      }
      if (value.isEmpty()) {
        rejected.add(new Rejection(source, "predicate '" + key + "' has empty value"));
        return null;
      }
      String lcKey = key.toLowerCase(Locale.ROOT);
      if (comparator == null) {
        // String-equality predicate (existing behaviour).
        // Reserved characters in values would break the string form produced by
        // BlockData.getAsString(); reject them explicitly.
        if (value.indexOf('[') >= 0 || value.indexOf(']') >= 0
            || value.indexOf('=') >= 0 || value.indexOf('<') >= 0 || value.indexOf('>') >= 0) {
          rejected.add(new Rejection(source,
              "predicate value '" + value + "' contains a reserved character "
                  + "('[', ']', '=', '<', or '>')"));
          return null;
        }
        if (!seen.add(lcKey + "|=")) {
          rejected.add(new Rejection(source,
              "duplicate predicate key '" + lcKey + "' within one token"));
          return null;
        }
        kv.put(lcKey, value.toLowerCase(Locale.ROOT));
      } else {
        // Numeric range predicate (ADR-017 §1 extension): the bound must be an integer.
        long bound;
        try {
          bound = Long.parseLong(value);
        } catch (NumberFormatException e) {
          rejected.add(new Rejection(source,
              "numeric predicate '" + key + comparator.symbol() + value
                  + "' requires an integer bound; '" + value + "' is not an integer"));
          return null;
        }
        if (!seen.add(lcKey + "|" + comparator.symbol())) {
          rejected.add(new Rejection(source,
              "duplicate predicate '" + lcKey + comparator.symbol() + "' within one token"));
          return null;
        }
        comparisons.add(new StatePredicate.NumericComparison(lcKey, comparator, bound));
      }
    }
    return Collections.singletonList(new StatePredicate(kv, comparisons, source));
  }

  /**
   * Outcome of a parse operation. Carries both accepted tokens and a per-token rejection
   * record (so callers can emit a single startup WARN per rejection - see ADR-017 &sect;3).
   */
  public static final class ParseResult {
    private static final ParseResult EMPTY = new ParseResult(Collections.emptyList(),
        Collections.emptyList());

    private final List<SafetyToken> accepted;
    private final List<Rejection> rejected;

    /**
     * Construct a result. Both lists are defensively copied and made unmodifiable.
     *
     * @param accepted the parsed tokens.
     * @param rejected the rejected raw tokens with reasons.
     */
    public ParseResult(List<SafetyToken> accepted, List<Rejection> rejected) {
      this.accepted = Collections.unmodifiableList(new ArrayList<>(
          Objects.requireNonNull(accepted, "accepted")));
      this.rejected = Collections.unmodifiableList(new ArrayList<>(
          Objects.requireNonNull(rejected, "rejected")));
    }

    /** @return an empty result (no accepted, no rejected). */
    public static ParseResult empty() {
      return EMPTY;
    }

    /** @return unmodifiable list of accepted tokens. */
    public List<SafetyToken> accepted() {
      return accepted;
    }

    /** @return unmodifiable list of rejections, each with the raw token and a reason. */
    public List<Rejection> rejected() {
      return rejected;
    }

    /** @return {@code true} iff any token was rejected. */
    public boolean hasRejections() {
      return !rejected.isEmpty();
    }
  }

  /** One parse-time rejection: the raw token and the reason it was dropped. */
  public static final class Rejection {
    private final String rawToken;
    private final String reason;

    /**
     * @param rawToken the raw token as supplied by the caller (verbatim, never trimmed).
     * @param reason human-readable reason suitable for a WARN log line.
     */
    public Rejection(String rawToken, String reason) {
      this.rawToken = Objects.requireNonNull(rawToken, "rawToken");
      this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** @return the raw token string as supplied. */
    public String rawToken() {
      return rawToken;
    }

    /** @return the reason the token was rejected. */
    public String reason() {
      return reason;
    }

    @Override
    public String toString() {
      return "Rejection{'" + rawToken + "': " + reason + "}";
    }
  }
}
