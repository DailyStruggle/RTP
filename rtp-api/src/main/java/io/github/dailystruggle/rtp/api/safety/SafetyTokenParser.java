package io.github.dailystruggle.rtp.api.safety;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pure-string grammar parser for the safety-list tokens defined by
 * <a href="../../../../../../../../../../docs/adr/ADR-017-block-tags-and-state-predicates-in-safety-lists.md">ADR-017</a>
 * &sect;1.
 *
 * <p>Accepted token shapes:</p>
 * <ul>
 *   <li>{@code LAVA} — bare material (upper-snake {@code Material.name()}).</li>
 *   <li>{@code #minecraft:leaves} — tag reference.</li>
 *   <li>{@code OAK_SLAB[waterlogged=true]} — material with state predicate(s).</li>
 *   <li>{@code #minecraft:slabs[waterlogged=true]} — tag with state predicate(s).</li>
 *   <li>{@code *[waterlogged=true]} — wildcard material with state predicate(s).</li>
 * </ul>
 *
 * <p>Rejected tokens (all reported as {@link ParseResult#rejected()} entries with a
 * reason — never silent per REQ-RTP-S-004):</p>
 * <ul>
 *   <li>Empty or whitespace-only strings.</li>
 *   <li>Bare {@code *} (wildcard requires at least one predicate).</li>
 *   <li>Unbalanced {@code [} / {@code ]} brackets.</li>
 *   <li>Empty or malformed {@code [...]} body (e.g. {@code OAK_SLAB[]}, {@code OAK_SLAB[=true]}, {@code OAK_SLAB[waterlogged]}).</li>
 *   <li>Tag with an empty namespace or path (e.g. {@code #:leaves}, {@code #minecraft:}).</li>
 *   <li>Material identifier that fails {@code [A-Za-z0-9_]+} (other than the {@code *} wildcard).</li>
 * </ul>
 *
 * <p>The parser performs <strong>no</strong> reconciliation against a running Bukkit
 * registry. Unknown materials and unknown tags pass through — it is the caller's job to
 * expand tags and reconcile unknown materials (typically via
 * {@link io.github.dailystruggle.rtp.api.configuration.PaletteIdentifierNormalizer}).</p>
 *
 * <p>Case normalization: material identifiers are uppercased under {@link Locale#ROOT}
 * (matching {@code Material.name()}); tag namespaces/paths are lowercased; property keys
 * and values are lowercased (per ADR-017 &sect;1 "case-insensitive lowercase strings").</p>
 *
 * <p>This class is stateless and thread-safe.</p>
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
   * Parse a single raw token.
   *
   * <p>Prefer {@link #parseAll(Collection)} for batch parsing at config load; this
   * single-token form is exposed for targeted unit testing and for the command-surface
   * round-trip test required by ADR-017 &sect;6.</p>
   *
   * @param raw the raw token; may be {@code null} or whitespace.
   * @return a populated {@link ParseResult} with either one accepted token or one rejection.
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

    // Split into head and optional bracketed body.
    int open = token.indexOf('[');
    int close = token.lastIndexOf(']');
    String head;
    String body = null;
    if (open < 0 && close < 0) {
      head = token;
    } else if (open >= 0 && close > open && close == token.length() - 1) {
      head = token.substring(0, open);
      body = token.substring(open + 1, close);
    } else {
      rejected.add(new Rejection(source,
          "unbalanced or misplaced '[' / ']' brackets; expected 'IDENT[pred,...]'"));
      return;
    }

    if (head.isEmpty()) {
      rejected.add(new Rejection(source, "token has no identifier before '['"));
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
    // Simple split on ',' — values that contain commas are out of scope (ADR-017 &sect;1).
    String[] parts = trimmed.split(",", -1);
    for (String rawPart : parts) {
      String part = rawPart.trim();
      if (part.isEmpty()) {
        rejected.add(new Rejection(source, "empty predicate between commas in '[" + body + "]'"));
        return null;
      }
      int eq = part.indexOf('=');
      if (eq <= 0 || eq == part.length() - 1) {
        rejected.add(new Rejection(source,
            "malformed predicate '" + part + "'; expected 'key=value' with non-empty sides"));
        return null;
      }
      String key = part.substring(0, eq).trim();
      String value = part.substring(eq + 1).trim();
      if (!PROPERTY_KEY.matcher(key).matches()) {
        rejected.add(new Rejection(source,
            "predicate key '" + key + "' does not match [A-Za-z0-9_]+"));
        return null;
      }
      if (value.isEmpty()) {
        rejected.add(new Rejection(source, "predicate '" + key + "=' has empty value"));
        return null;
      }
      // Reserved characters in values would break the string form produced by
      // BlockData.getAsString(); reject them explicitly.
      if (value.indexOf('[') >= 0 || value.indexOf(']') >= 0 || value.indexOf('=') >= 0) {
        rejected.add(new Rejection(source,
            "predicate value '" + value + "' contains a reserved character ('[', ']', or '=')"));
        return null;
      }
      String lcKey = key.toLowerCase(Locale.ROOT);
      if (kv.containsKey(lcKey)) {
        rejected.add(new Rejection(source,
            "duplicate predicate key '" + lcKey + "' within one token"));
        return null;
      }
      kv.put(lcKey, value.toLowerCase(Locale.ROOT));
    }
    return Collections.singletonList(new StatePredicate(kv, source));
  }

  /**
   * Outcome of a parse operation. Carries both accepted tokens and a per-token rejection
   * record (so callers can emit a single startup WARN per rejection — see ADR-017 &sect;3).
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
