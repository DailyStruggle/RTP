package io.github.dailystruggle.rtp.proxy.common.transport;

import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseSink;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkStateBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@link ReservationTokenReaper}'s in-process
 * {@link ReleaseSink} hook fires once per reaped token with reason
 * {@link ReleaseReason#TTL_EXPIRED}, even when the sink itself throws, and
 * defaults to {@link ReleaseSink#NO_OP} when callers use the legacy ctors.
 */
class ReservationTokenReaperReleaseSinkTest {

    private InMemoryNetworkStateBinding binding;
    private AtomicReference<Instant> clock;

    @BeforeEach
    void setUp() {
        binding = new InMemoryNetworkStateBinding();
        clock = new AtomicReference<>(Instant.ofEpochMilli(1_000_000L));
        binding.setClock(clock::get);
    }

    @AfterEach
    void tearDown() {
        if (binding != null) binding.close();
    }

    private String claimExpired(String backend, UUID player) throws Exception {
        var token = binding.claim(backend, player, Duration.ofMillis(1)).get(2, TimeUnit.SECONDS);
        return token.tokenId();
    }

    @Test
    @DisplayName("sink fires once per reaped token with TTL_EXPIRED reason")
    void sinkFiresPerReapedToken() throws Exception {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        String t1 = claimExpired("backend-a", p1);
        String t2 = claimExpired("backend-b", p2);
        clock.set(Instant.ofEpochMilli(1_001_000L));

        List<String> tokens = new CopyOnWriteArrayList<>();
        List<ReleaseReason> reasons = new CopyOnWriteArrayList<>();
        ReleaseSink sink = (tokenId, reason) -> {
            tokens.add(tokenId);
            reasons.add(reason);
        };

        try (ReservationTokenReaper reaper =
                     new ReservationTokenReaper(binding, Duration.ofSeconds(30), clock::get, sink)) {
            assertEquals(2, reaper.reapNow(), "both expired tokens must be reaped");
            assertEquals(2, tokens.size(), "sink must fire once per reaped token");
            assertTrue(tokens.contains(t1) && tokens.contains(t2),
                    "sink must observe both token ids: got " + tokens);
            assertEquals(List.of(ReleaseReason.TTL_EXPIRED, ReleaseReason.TTL_EXPIRED), reasons,
                    "every sink call must carry TTL_EXPIRED");
        }
    }

    @Test
    @DisplayName("sink fires zero times when nothing is reaped")
    void sinkSilentWhenNothingExpired() throws Exception {
        UUID alive = UUID.randomUUID();
        binding.claim("backend-c", alive, Duration.ofSeconds(60)).get(2, TimeUnit.SECONDS);

        AtomicInteger calls = new AtomicInteger();
        ReleaseSink sink = (tokenId, reason) -> calls.incrementAndGet();

        try (ReservationTokenReaper reaper =
                     new ReservationTokenReaper(binding, Duration.ofSeconds(30), clock::get, sink)) {
            assertEquals(0, reaper.reapNow(), "no expired tokens => no reap");
            assertEquals(0, calls.get(), "sink must not fire when reap returns empty");
        }
    }

    @Test
    @DisplayName("throwing sink does not kill the sweep; all tokens still observed")
    void throwingSinkDoesNotSwallow() throws Exception {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        claimExpired("b1", p1);
        claimExpired("b2", p2);
        claimExpired("b3", p3);
        clock.set(Instant.ofEpochMilli(1_001_000L));

        List<String> observed = new ArrayList<>();
        ReleaseSink throwing = (tokenId, reason) -> {
            observed.add(tokenId);
            throw new RuntimeException("intentional sink failure for " + tokenId);
        };

        try (ReservationTokenReaper reaper =
                     new ReservationTokenReaper(binding, Duration.ofSeconds(30), clock::get, throwing)) {
            assertEquals(3, reaper.reapNow(),
                    "reaper must return full count despite sink throwing every call (S-004)");
            assertEquals(3, observed.size(),
                    "every reaped token must reach the sink even when prior calls threw");
        }
    }

    @Test
    @DisplayName("legacy 2-arg ctor defaults to ReleaseSink.NO_OP (no NPE, no leakage)")
    void legacyTwoArgCtorUsesNoOpSink() {
        try (ReservationTokenReaper reaper =
                     new ReservationTokenReaper(binding, Duration.ofSeconds(30))) {
            assertNotNull(reaper);
            // reapNow with an empty binding must be a clean no-op even though
            // no explicit sink was supplied.
            assertEquals(0, reaper.reapNow());
        }
    }

    @Test
    @DisplayName("legacy 3-arg ctor (with clock) defaults to ReleaseSink.NO_OP")
    void legacyThreeArgCtorUsesNoOpSink() throws Exception {
        UUID p = UUID.randomUUID();
        claimExpired("backend-z", p);
        clock.set(Instant.ofEpochMilli(1_001_000L));
        try (ReservationTokenReaper reaper =
                     new ReservationTokenReaper(binding, Duration.ofSeconds(30), clock::get)) {
            assertEquals(1, reaper.reapNow(),
                    "3-arg ctor still reaps; legacy callers see NO_OP sink behaviour transparently");
        }
    }

    @Test
    @DisplayName("4-arg ctor rejects a null ReleaseSink")
    void nullSinkRejected() {
        assertThrows(NullPointerException.class,
                () -> new ReservationTokenReaper(binding, Duration.ofSeconds(30), clock::get, null));
    }

    @Test
    @DisplayName("ReleaseSink.NO_OP is a true no-op (safe for high-fanout reaps)")
    void noOpSinkIsActuallyNoOp() {
        // Smoke test: NO_OP must accept any (non-null) inputs without throwing.
        ReleaseSink.NO_OP.onRelease("t1", ReleaseReason.TTL_EXPIRED);
        ReleaseSink.NO_OP.onRelease("t2", ReleaseReason.OPERATOR);
        ReleaseSink.NO_OP.onRelease("t3", ReleaseReason.CONSUMED);
    }
}
