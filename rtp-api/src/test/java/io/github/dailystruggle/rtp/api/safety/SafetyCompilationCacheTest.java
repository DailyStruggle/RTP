package io.github.dailystruggle.rtp.api.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SafetyCompilationCache}.
 *
 * <p>Verifies the caching contract (ADR-017):</p>
 * <ul>
 *   <li>Same input set → same compiled result instance (memoization).</li>
 *   <li>Different input sets → different compiled instances.</li>
 *   <li>{@code null} / empty inputs short-circuit to {@link CompiledUnsafeSet#EMPTY}
 *       without touching the cache.</li>
 *   <li>Rejected tokens are surfaced to the caller-supplied sink exactly once per
 *       distinct key (REQ-RTP-S-004 "never silent" without log spam).</li>
 *   <li>A misbehaving sink does not break the pipeline.</li>
 * </ul>
 */
@DisplayName("REQ-RTP-S-001 / ADR-017 § SafetyCompilationCache")
class SafetyCompilationCacheTest {

  @BeforeEach
  void clearCache() {
    SafetyCompilationCache.clear();
  }

  @Test
  @DisplayName("null input returns CompiledUnsafeSet.EMPTY without caching")
  void nullInputEmpty() {
    CompiledUnsafeSet result = SafetyCompilationCache.getOrCompile(null);
    assertSame(CompiledUnsafeSet.EMPTY, result);
    assertEquals(0, SafetyCompilationCache.size());
  }

  @Test
  @DisplayName("empty collection returns CompiledUnsafeSet.EMPTY without caching")
  void emptyInputEmpty() {
    CompiledUnsafeSet result = SafetyCompilationCache.getOrCompile(Collections.emptyList());
    assertSame(CompiledUnsafeSet.EMPTY, result);
    assertEquals(0, SafetyCompilationCache.size());
  }

  @Test
  @DisplayName("Collection of only null entries returns EMPTY and does not cache")
  void onlyNullEntriesEmpty() {
    List<String> onlyNulls = new ArrayList<>();
    onlyNulls.add(null);
    onlyNulls.add(null);
    CompiledUnsafeSet result = SafetyCompilationCache.getOrCompile(onlyNulls);
    assertSame(CompiledUnsafeSet.EMPTY, result);
    assertEquals(0, SafetyCompilationCache.size());
  }

  @Test
  @DisplayName("Same input contents return the same compiled instance")
  void memoization() {
    List<String> input1 = Arrays.asList("LAVA", "FIRE");
    List<String> input2 = Arrays.asList("LAVA", "FIRE");

    CompiledUnsafeSet a = SafetyCompilationCache.getOrCompile(input1);
    CompiledUnsafeSet b = SafetyCompilationCache.getOrCompile(input2);

    assertNotNull(a);
    assertSame(a, b, "Identical input contents must produce the same cached instance");
    assertEquals(1, SafetyCompilationCache.size());
  }

  @Test
  @DisplayName("Different input sets produce different compiled instances")
  void differentInputsDifferentInstances() {
    CompiledUnsafeSet a = SafetyCompilationCache.getOrCompile(Arrays.asList("LAVA"));
    CompiledUnsafeSet b = SafetyCompilationCache.getOrCompile(Arrays.asList("FIRE"));
    assertNotNull(a);
    assertNotNull(b);
    // Different keys → different cached instances.
    assertFalse(a == b);
    assertEquals(2, SafetyCompilationCache.size());
  }

  @Test
  @DisplayName("Caller mutating input post-lookup does not invalidate the cached entry")
  void mutationAfterLookupDoesNotAffectCache() {
    LinkedHashSet<String> mutable = new LinkedHashSet<>();
    mutable.add("LAVA");
    mutable.add("FIRE");

    CompiledUnsafeSet first = SafetyCompilationCache.getOrCompile(mutable);
    mutable.add("MAGMA_BLOCK"); // mutate post-compile — must not invalidate

    // Re-query with the original contents in a fresh collection — should still be the same instance.
    CompiledUnsafeSet second = SafetyCompilationCache.getOrCompile(Arrays.asList("LAVA", "FIRE"));
    assertSame(first, second);
  }

  @Test
  @DisplayName("Rejection sink fires for malformed tokens exactly once per distinct key")
  void rejectionSinkFiresOnce() {
    List<SafetyTokenParser.Rejection> seen = new ArrayList<>();
    List<String> input = Arrays.asList("LAVA", "*", "OAK_SLAB[");

    CompiledUnsafeSet first = SafetyCompilationCache.getOrCompile(input, seen::add);
    int rejectionsAfterFirst = seen.size();
    assertTrue(rejectionsAfterFirst >= 1,
        "At least one token must be rejected (bare '*' and unterminated bracket)");

    // Second call with the same input — rejection sink must NOT fire again (cache hit).
    CompiledUnsafeSet second = SafetyCompilationCache.getOrCompile(input, seen::add);
    assertSame(first, second);
    assertEquals(rejectionsAfterFirst, seen.size(),
        "Sink must not re-fire on cache hit — REQ-RTP-S-004 without log spam");
  }

  @Test
  @DisplayName("Misbehaving sink throwing a RuntimeException does not break the pipeline")
  void misbehavingSinkTolerated() {
    List<String> input = Arrays.asList("LAVA", "*"); // '*' alone is rejected
    CompiledUnsafeSet result =
        SafetyCompilationCache.getOrCompile(input, r -> {
          throw new RuntimeException("boom");
        });
    assertNotNull(result);
    assertTrue(result.plainMaterials().contains("LAVA"));
  }

  @Test
  @DisplayName("clear() evicts all entries")
  void clearEvicts() {
    SafetyCompilationCache.getOrCompile(Arrays.asList("LAVA"));
    SafetyCompilationCache.getOrCompile(Arrays.asList("FIRE"));
    assertEquals(2, SafetyCompilationCache.size());
    SafetyCompilationCache.clear();
    assertEquals(0, SafetyCompilationCache.size());
  }

  @Test
  @DisplayName("Well-formed compiled set contains expected buckets")
  void wellFormedCompilation() {
    List<String> input = Arrays.asList(
        "LAVA",                           // plain material
        "#minecraft:leaves",              // plain tag
        "OAK_SLAB[waterlogged=true]",     // material with state predicate
        "*[waterlogged=true]"             // wildcard state predicate
    );
    CompiledUnsafeSet c = SafetyCompilationCache.getOrCompile(input);
    assertTrue(c.plainMaterials().contains("LAVA"));
    assertTrue(c.plainTags().contains("minecraft:leaves"));
    assertTrue(c.materialStatePredicates().containsKey("OAK_SLAB"));
    assertTrue(c.hasWildcardStatePredicate());
  }
}
