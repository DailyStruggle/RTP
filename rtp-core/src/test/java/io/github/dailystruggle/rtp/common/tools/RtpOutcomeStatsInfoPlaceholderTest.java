package io.github.dailystruggle.rtp.common.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dailystruggle.rtp.common.metrics.RtpOutcomeStats;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests generation-outcome placeholders (ADR-052) rendered by {@link PlaceholderProvider}.
 */
class RtpOutcomeStatsInfoPlaceholderTest {

  private static final UUID ANY = UUID.randomUUID();

  @BeforeEach
  void resetBefore() {
    RtpOutcomeStats.GLOBAL.reset();
  }

  @AfterEach
  void resetAfter() {
    RtpOutcomeStats.GLOBAL.reset();
  }

  @Test
  @DisplayName("no recorded outcomes renders the documented n/a / none sentinels")
  void emptyState() {
    assertEquals("n/a", PlaceholderProvider.fillPlaceholders("[genSuccessRate]", ANY));
    assertEquals("n/a", PlaceholderProvider.fillPlaceholders("[genFailureRate]", ANY));
    assertEquals("0", PlaceholderProvider.fillPlaceholders("[genOutcomeTotal]", ANY));
    assertEquals("none", PlaceholderProvider.fillPlaceholders("[genFailureBreakdown]", ANY));
    assertEquals("none", PlaceholderProvider.fillPlaceholders("[genTopRejectionCause]", ANY));
    assertEquals("n/a", PlaceholderProvider.fillPlaceholders("[genTopRejectionShare]", ANY));
  }

  @Test
  @DisplayName("rates, total, breakdown and top-cause render from the live accumulator")
  void populatedState() {
    // 3 successes, 3 failures (2 biome, 1 safetyExternal) => 50% success / 50% fail, n=6.
    RtpOutcomeStats.GLOBAL.recordSuccess();
    RtpOutcomeStats.GLOBAL.recordSuccess();
    RtpOutcomeStats.GLOBAL.recordSuccess();
    RtpOutcomeStats.GLOBAL.recordFailure(LocationGenerator.FailTypes.biome);
    RtpOutcomeStats.GLOBAL.recordFailure(LocationGenerator.FailTypes.biome);
    RtpOutcomeStats.GLOBAL.recordFailure(LocationGenerator.FailTypes.safetyExternal);

    assertEquals("50.0%", PlaceholderProvider.fillPlaceholders("[genSuccessRate]", ANY));
    assertEquals("50.0%", PlaceholderProvider.fillPlaceholders("[genFailureRate]", ANY));
    assertEquals("6", PlaceholderProvider.fillPlaceholders("[genOutcomeTotal]", ANY));

    // Busiest-first, zero buckets omitted.
    assertEquals(
        "biome=2, safetyExternal=1",
        PlaceholderProvider.fillPlaceholders("[genFailureBreakdown]", ANY));
    assertEquals("biome", PlaceholderProvider.fillPlaceholders("[genTopRejectionCause]", ANY));
    // 2 of 3 failures are biome => 66.7%.
    assertEquals("66.7%", PlaceholderProvider.fillPlaceholders("[genTopRejectionShare]", ANY));
  }
}
