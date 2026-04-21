package io.github.dailystruggle.rtp.api.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.safety.SafetyTokenParser.ParseResult;
import io.github.dailystruggle.rtp.api.safety.SafetyTokenParser.Rejection;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Grammar tests for {@link SafetyTokenParser}.
 *
 * <p>Verifies the token grammar defined by ADR-017 &sect;1 and the
 * never-silent rejection contract required by REQ-RTP-S-004: every malformed
 * token must surface as a {@link Rejection} with a human-readable reason.</p>
 */
@DisplayName("REQ-RTP-S-001 / ADR-017 § SafetyTokenParser")
class SafetyTokenParserTest {

  private static SafetyToken soleAccepted(ParseResult r) {
    assertFalse(r.hasRejections(), () -> "unexpected rejections: " + r.rejected());
    assertEquals(1, r.accepted().size(), () -> "expected exactly one accepted token: "
        + r.accepted());
    return r.accepted().get(0);
  }

  private static Rejection soleRejection(ParseResult r) {
    assertTrue(r.accepted().isEmpty(), () -> "expected no accepted tokens: " + r.accepted());
    assertEquals(1, r.rejected().size(), () -> "expected exactly one rejection: "
        + r.rejected());
    return r.rejected().get(0);
  }

  @Nested
  @DisplayName("Accepted shapes")
  class Accepted {

    @Test
    @DisplayName("bare material upper-cases and round-trips")
    void bareMaterial() {
      SafetyToken t = soleAccepted(SafetyTokenParser.parse("lava"));
      assertEquals(SafetyToken.Kind.MATERIAL, t.kind());
      assertEquals("LAVA", t.identifier());
      assertFalse(t.isPredicated());
      assertFalse(t.isWildcard());
      assertEquals("lava", t.sourceToken());
    }

    @Test
    @DisplayName("already-upper material round-trips unchanged")
    void upperMaterial() {
      SafetyToken t = soleAccepted(SafetyTokenParser.parse("OAK_LEAVES"));
      assertEquals("OAK_LEAVES", t.identifier());
    }

    @Test
    @DisplayName("tag reference lower-cases namespace:path")
    void tagReference() {
      SafetyToken t = soleAccepted(SafetyTokenParser.parse("#Minecraft:LEAVES"));
      assertEquals(SafetyToken.Kind.TAG, t.kind());
      assertEquals("minecraft:leaves", t.identifier());
      assertFalse(t.isPredicated());
    }

    @Test
    @DisplayName("material with single predicate lowercases key and value")
    void materialWithSinglePredicate() {
      SafetyToken t = soleAccepted(SafetyTokenParser.parse("OAK_SLAB[WATERLOGGED=True]"));
      assertEquals("OAK_SLAB", t.identifier());
      assertTrue(t.isPredicated());
      assertEquals(1, t.predicates().size());
      StatePredicate p = t.predicates().get(0);
      assertEquals(1, p.properties().size());
      assertEquals("true", p.properties().get("waterlogged"));
    }

    @Test
    @DisplayName("tag with single predicate lowercases both sides")
    void tagWithSinglePredicate() {
      SafetyToken t = soleAccepted(SafetyTokenParser.parse("#minecraft:slabs[waterlogged=true]"));
      assertEquals(SafetyToken.Kind.TAG, t.kind());
      assertEquals("minecraft:slabs", t.identifier());
      assertEquals("true", t.predicates().get(0).properties().get("waterlogged"));
    }

    @Test
    @DisplayName("wildcard material with predicate is accepted")
    void wildcardWithPredicate() {
      SafetyToken t = soleAccepted(SafetyTokenParser.parse("*[waterlogged=true]"));
      assertTrue(t.isWildcard());
      assertEquals(SafetyToken.WILDCARD, t.identifier());
      assertEquals("true", t.predicates().get(0).properties().get("waterlogged"));
    }

    @Test
    @DisplayName("multiple predicates are AND-combined into one StatePredicate")
    void multiplePredicatesCombine() {
      SafetyToken t = soleAccepted(
          SafetyTokenParser.parse("OAK_SLAB[waterlogged=true,type=top]"));
      assertEquals(1, t.predicates().size(), "all predicates in one [] collapse to one StatePredicate");
      StatePredicate p = t.predicates().get(0);
      assertEquals(2, p.properties().size());
      assertEquals("true", p.properties().get("waterlogged"));
      assertEquals("top", p.properties().get("type"));
    }

    @Test
    @DisplayName("leading/trailing whitespace on the raw token is tolerated")
    void whitespaceTrim() {
      SafetyToken t = soleAccepted(SafetyTokenParser.parse("   LAVA   "));
      assertEquals("LAVA", t.identifier());
    }

    @Test
    @DisplayName("predicate keys and values with whitespace are trimmed")
    void predicateInnerWhitespace() {
      SafetyToken t = soleAccepted(
          SafetyTokenParser.parse("OAK_SLAB[ waterlogged = true , type = top ]"));
      StatePredicate p = t.predicates().get(0);
      assertEquals("true", p.properties().get("waterlogged"));
      assertEquals("top", p.properties().get("type"));
    }
  }

  @Nested
  @DisplayName("Rejected shapes (never-silent, REQ-RTP-S-004)")
  class Rejected {

    @Test
    @DisplayName("null raw token is rejected with reason")
    void nullRaw() {
      Rejection r = soleRejection(SafetyTokenParser.parse(null));
      assertTrue(r.reason().contains("null"));
    }

    @Test
    @DisplayName("empty/whitespace raw token is rejected")
    void emptyRaw() {
      Rejection r = soleRejection(SafetyTokenParser.parse("   "));
      assertTrue(r.reason().toLowerCase().contains("empty"));
    }

    @Test
    @DisplayName("bare wildcard '*' is rejected")
    void bareWildcardRejected() {
      Rejection r = soleRejection(SafetyTokenParser.parse("*"));
      assertTrue(r.reason().contains("wildcard"),
          () -> "reason should mention wildcard: " + r.reason());
    }

    @Test
    @DisplayName("unbalanced brackets are rejected")
    void unbalancedBrackets() {
      Rejection r1 = soleRejection(SafetyTokenParser.parse("OAK_SLAB[waterlogged=true"));
      assertTrue(r1.reason().contains("bracket") || r1.reason().contains("'['"),
          () -> "reason: " + r1.reason());
      Rejection r2 = soleRejection(SafetyTokenParser.parse("OAK_SLAB]"));
      assertNotNull(r2.reason());
    }

    @Test
    @DisplayName("empty [] body is rejected")
    void emptyBracketBody() {
      Rejection r = soleRejection(SafetyTokenParser.parse("OAK_SLAB[]"));
      assertTrue(r.reason().contains("empty"),
          () -> "reason: " + r.reason());
    }

    @Test
    @DisplayName("predicate without '=' is rejected")
    void predicateMissingEquals() {
      Rejection r = soleRejection(SafetyTokenParser.parse("OAK_SLAB[waterlogged]"));
      assertTrue(r.reason().contains("key=value") || r.reason().contains("="),
          () -> "reason: " + r.reason());
    }

    @Test
    @DisplayName("predicate with empty key or value is rejected")
    void predicateEmptySides() {
      assertTrue(SafetyTokenParser.parse("OAK_SLAB[=true]").hasRejections());
      assertTrue(SafetyTokenParser.parse("OAK_SLAB[waterlogged=]").hasRejections());
    }

    @Test
    @DisplayName("duplicate predicate keys inside one token are rejected")
    void duplicatePredicateKey() {
      Rejection r = soleRejection(
          SafetyTokenParser.parse("OAK_SLAB[waterlogged=true,waterlogged=false]"));
      assertTrue(r.reason().contains("duplicate"),
          () -> "reason: " + r.reason());
    }

    @Test
    @DisplayName("tag without namespace is rejected")
    void tagMissingNamespace() {
      assertTrue(SafetyTokenParser.parse("#:leaves").hasRejections());
      assertTrue(SafetyTokenParser.parse("#minecraft:").hasRejections());
      assertTrue(SafetyTokenParser.parse("#leaves").hasRejections(),
          "tag without ':' must be rejected (not silently reinterpreted)");
    }

    @Test
    @DisplayName("material with illegal characters is rejected")
    void illegalMaterialChar() {
      assertTrue(SafetyTokenParser.parse("OAK-SLAB").hasRejections(),
          "dashes are not part of the identifier grammar");
      assertTrue(SafetyTokenParser.parse("OAK SLAB").hasRejections(),
          "spaces are not part of the identifier grammar");
    }

    @Test
    @DisplayName("predicate value containing reserved characters is rejected")
    void predicateReservedCharInValue() {
      assertTrue(SafetyTokenParser.parse("OAK_SLAB[waterlogged=[true]]").hasRejections());
      assertTrue(SafetyTokenParser.parse("OAK_SLAB[waterlogged=t=r]").hasRejections());
    }
  }

  @Nested
  @DisplayName("Batch parsing semantics")
  class Batch {

    @Test
    @DisplayName("parseAll(null) returns the empty singleton")
    void parseAllNullReturnsEmpty() {
      assertSame(ParseResult.empty(), SafetyTokenParser.parseAll(null));
      assertSame(ParseResult.empty(), SafetyTokenParser.parseAll(Collections.emptyList()));
    }

    @Test
    @DisplayName("parseAll skips null/blank entries without rejection entries")
    void parseAllSkipsBlanks() {
      ParseResult r = SafetyTokenParser.parseAll(Arrays.asList(
          "LAVA", null, "", "   ", "FIRE"));
      assertEquals(2, r.accepted().size());
      assertTrue(r.rejected().isEmpty(),
          () -> "blank entries must be silently skipped (YAML loader noise): " + r.rejected());
    }

    @Test
    @DisplayName("parseAll preserves accepted/rejected separation across a mixed list")
    void parseAllMixed() {
      ParseResult r = SafetyTokenParser.parseAll(Arrays.asList(
          "LAVA",
          "OAK_SLAB[waterlogged=true]",
          "#minecraft:leaves",
          "*[waterlogged=true]",
          "*",                 // rejected
          "OAK_SLAB[]",        // rejected
          "#leaves"));         // rejected
      assertEquals(4, r.accepted().size());
      assertEquals(3, r.rejected().size());
    }

    @Test
    @DisplayName("accepted and rejected lists are unmodifiable")
    void resultListsUnmodifiable() {
      ParseResult r = SafetyTokenParser.parse("LAVA");
      List<SafetyToken> acc = r.accepted();
      List<Rejection> rej = r.rejected();
      // Java Collections.unmodifiableList throws UnsupportedOperationException on mutation.
      org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
          () -> acc.add(null));
      org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
          () -> rej.add(null));
    }
  }
}
