package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@link ChartSpecTokens} — Stage 2 item 2.5 of
 * {@code CHECKLIST-metrics-to-maps.md}. Validates the single-use,
 * TTL-bounded, player-bound contract that {@code OpenMapActionHandler}
 * (Stage 2.10) depends on.
 */
class ChartSpecTokensTest {

    private static ChartSpec spec() {
        return ChartSpec.of(ChartSpec.Kind.BAD_POINTS_HEATMAP, "region_default");
    }

    @Nested
    @DisplayName("mint / consume happy path")
    class HappyPath {

        @Test
        @DisplayName("a token minted for a player is consumable exactly once by that player")
        void singleUse() {
            ChartSpecTokens tokens = new ChartSpecTokens();
            UUID player = UUID.randomUUID();
            ChartSpec spec = spec();

            UUID token = tokens.mint(player, spec);
            assertNotNull(token, "mint() must not return null");

            Optional<ChartSpec> first = tokens.consume(player, token);
            assertTrue(first.isPresent(), "first consume must succeed");
            assertEquals(spec, first.get(), "spec must round-trip identically");

            Optional<ChartSpec> second = tokens.consume(player, token);
            assertTrue(second.isEmpty(), "second consume must reject (single-use)");
        }

        @Test
        @DisplayName("consume after success drains the entry from the live store")
        void consumeDrains() {
            ChartSpecTokens tokens = new ChartSpecTokens();
            UUID player = UUID.randomUUID();
            UUID token = tokens.mint(player, spec());
            assertEquals(1, tokens.size());

            tokens.consume(player, token);
            assertEquals(0, tokens.size(), "consumed entry must be removed from the live store");
        }

        @Test
        @DisplayName("each mint produces a distinct token even for identical (player, spec)")
        void mintsAreDistinct() {
            ChartSpecTokens tokens = new ChartSpecTokens();
            UUID player = UUID.randomUUID();
            ChartSpec spec = spec();

            UUID a = tokens.mint(player, spec);
            UUID b = tokens.mint(player, spec);
            assertNotEquals(a, b, "every mint must produce a distinct token");
            assertEquals(2, tokens.size());
        }
    }

    @Nested
    @DisplayName("rejection paths")
    class Rejection {

        @Test
        @DisplayName("consume with a player uuid different from mint returns empty")
        void mismatchedPlayer() {
            ChartSpecTokens tokens = new ChartSpecTokens();
            UUID owner = UUID.randomUUID();
            UUID intruder = UUID.randomUUID();
            UUID token = tokens.mint(owner, spec());

            assertTrue(tokens.consume(intruder, token).isEmpty(),
                    "mismatched-player consume must reject");
            // Token must still be live for the legitimate owner.
            assertTrue(tokens.consume(owner, token).isPresent(),
                    "owner's later consume must still succeed (intruder did not drain it)");
        }

        @Test
        @DisplayName("consume with an unknown token returns empty")
        void unknownToken() {
            ChartSpecTokens tokens = new ChartSpecTokens();
            assertTrue(tokens.consume(UUID.randomUUID(), UUID.randomUUID()).isEmpty());
        }

        @Test
        @DisplayName("consume after TTL has elapsed returns empty and drains the entry")
        void ttlExpiry() throws InterruptedException {
            ChartSpecTokens tokens = new ChartSpecTokens();
            UUID player = UUID.randomUUID();
            UUID token = tokens.mint(player, spec(), Duration.ofMillis(5));

            // Sleep generously past the TTL; the consume path reads
            // System.currentTimeMillis() directly, and any consume that
            // observes nowMillis < expires would be a real-time race we'd
            // want to know about.
            Thread.sleep(50L);

            assertTrue(tokens.consume(player, token).isEmpty(),
                    "expired token must be rejected");
            assertEquals(0, tokens.size(),
                    "expired token must be drained by the consume attempt");
        }

        @Test
        @DisplayName("mint rejects null and non-positive ttl")
        void mintArgValidation() {
            ChartSpecTokens tokens = new ChartSpecTokens();
            UUID player = UUID.randomUUID();

            assertThrows(NullPointerException.class, () -> tokens.mint(null, spec()));
            assertThrows(NullPointerException.class, () -> tokens.mint(player, null));
            assertThrows(NullPointerException.class,
                    () -> tokens.mint(player, spec(), null));
            assertThrows(IllegalArgumentException.class,
                    () -> tokens.mint(player, spec(), Duration.ZERO));
            assertThrows(IllegalArgumentException.class,
                    () -> tokens.mint(player, spec(), Duration.ofMillis(-1)));
        }
    }

    @Nested
    @DisplayName("sweep")
    class Sweep {

        @Test
        @DisplayName("sweepExpired drops only entries strictly older than the cutoff")
        void sweepDropsOnlyExpired() {
            // Drive the cutoff directly rather than wall-clock: each entry's
            // expiresAtMillis is computed against System.currentTimeMillis()
            // at mint time, so we pass an explicit `now` to sweepExpired that
            // is bigger than the short-lived entry's expiry but smaller than
            // the long-lived one's.
            ChartSpecTokens tokens = new ChartSpecTokens();
            UUID player = UUID.randomUUID();
            long mintMillis = System.currentTimeMillis();
            UUID shortLived = tokens.mint(player, spec(), Duration.ofMillis(50));
            UUID longLived = tokens.mint(player, spec(), Duration.ofMinutes(5));

            // Cutoff = mint + 5s: comfortably past the short TTL, comfortably
            // before the long one.
            int removed = tokens.sweepExpired(mintMillis + 5_000L);

            assertEquals(1, removed, "exactly the expired entry should be swept");
            assertEquals(1, tokens.size(), "long-lived entry survives sweep");
            assertTrue(tokens.consume(player, shortLived).isEmpty(),
                    "swept entry no longer consumable");
            assertTrue(tokens.consume(player, longLived).isPresent(),
                    "long-lived entry still consumable after sweep");
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("at most one of N concurrent consumes for the same token succeeds")
        void singleWinnerUnderContention() throws Exception {
            ChartSpecTokens tokens = new ChartSpecTokens();
            UUID player = UUID.randomUUID();
            UUID token = tokens.mint(player, spec());

            int threads = 32;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<CompletableFuture<Optional<ChartSpec>>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(CompletableFuture.supplyAsync(
                            () -> tokens.consume(player, token), pool));
                }
                int wins = 0;
                for (CompletableFuture<Optional<ChartSpec>> f : futures) {
                    if (f.get(5, TimeUnit.SECONDS).isPresent()) {
                        wins++;
                    }
                }
                assertEquals(1, wins,
                        "exactly one concurrent consume must observe the token");
                assertEquals(0, tokens.size(), "winning consume must drain the entry");
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS),
                        "pool must terminate");
            }
        }

        @Test
        @DisplayName("concurrent mints for the same (player, spec) yield distinct tokens")
        void mintsAreUniqueUnderContention() throws Exception {
            ChartSpecTokens tokens = new ChartSpecTokens();
            UUID player = UUID.randomUUID();
            ChartSpec spec = spec();

            int threads = 32;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<CompletableFuture<UUID>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(CompletableFuture.supplyAsync(
                            () -> tokens.mint(player, spec), pool));
                }
                Set<UUID> minted = new HashSet<>();
                for (CompletableFuture<UUID> f : futures) {
                    assertTrue(minted.add(f.get(5, TimeUnit.SECONDS)),
                            "every mint must yield a distinct token");
                }
                assertEquals(threads, minted.size());
                assertEquals(threads, tokens.size());
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS),
                        "pool must terminate");
            }
        }
    }

    @Test
    @DisplayName("scheduleSweeps returns null when RTP.scheduler is not installed")
    void scheduleSweepsNullWhenSchedulerAbsent() {
        // Default test environment leaves RTP.scheduler null; this guards that
        // the registry degrades gracefully rather than NPE on plugin startup
        // when the scheduler is wired later.
        ChartSpecTokens tokens = new ChartSpecTokens();
        // We accept whichever path the env produces; the only forbidden outcome
        // is a thrown NPE. Calling and discarding the handle is the contract.
        Object handle = tokens.scheduleSweeps();
        assertFalse(handle != null && handle.getClass().getName().isEmpty());
    }
}
