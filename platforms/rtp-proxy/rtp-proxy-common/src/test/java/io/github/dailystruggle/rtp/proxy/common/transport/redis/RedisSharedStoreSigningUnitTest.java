package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.EnrolOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.EnrolmentEnvelope;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueEnvelope;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist.WaitEnvelope;
import io.github.dailystruggle.rtp.proxy.common.spi.RedeemOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.transport.CanonicalEnvelopes;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespConnection;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis-tier shared-store signing hardening (rtp-proxy-ADR-010) against a
 * mocked RESP connection: v2 token HMAC covers regionKey, redeem verifies
 * before the Lua CAS, queue envelopes are signed at flush and verified on
 * dequeue, shared waitlist entries are signed at enrol and verified on drain.
 */
class RedisSharedStoreSigningUnitTest {

    private RespPool pool;
    private RespConnection conn;
    private HmacVerifier verifier;
    private RedisNetworkStateBinding binding;
    private RedisNetworkRequestQueue queue;
    private RedisNetworkWaitlist waitlist;

    @BeforeEach
    void setUp() throws Exception {
        pool = mock(RespPool.class);
        conn = mock(RespConnection.class);
        when(pool.getResource()).thenAnswer(inv -> {
            if (Thread.currentThread().getName().startsWith("rtp-redis-sub-")) {
                // Subscriber thread: block-free mock that never delivers.
                return mock(RespConnection.class);
            }
            return conn;
        });
        when(conn.scriptLoad(anyString())).thenAnswer(inv -> {
            String script = inv.getArgument(0);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(script.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        });
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 3);
        verifier = HmacVerifier.forTesting(secret, 1, 1);
        binding = new RedisNetworkStateBinding(pool, 1000L, verifier, 1);
        queue = new RedisNetworkRequestQueue(pool, 0, verifier, 1);
        waitlist = new RedisNetworkWaitlist(pool, 0, verifier, 1);
    }

    @AfterEach
    void tearDown() {
        if (binding != null) binding.close();
        if (queue != null) queue.close();
        if (waitlist != null) waitlist.close();
    }

    private Map<String, String> signedTokenRow(String tokenId, String serverId, UUID pid, String region) {
        long now = System.currentTimeMillis();
        Map<String, String> row = new HashMap<>();
        row.put("tokenId", tokenId);
        row.put("serverId", serverId);
        row.put("playerId", pid.toString());
        row.put("expiresAtMs", Long.toString(now + 60_000L));
        row.put("createdAtMs", Long.toString(now));
        row.put("state", "CLAIMED");
        row.put("regionKey", region);
        row.put("hmac", CanonicalEnvelopes.signToken(verifier, 1, tokenId, serverId, pid.toString(),
                row.get("expiresAtMs"), row.get("createdAtMs"), "CLAIMED", region));
        return row;
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: claim signs the v2 canonical token including regionKey")
    @SuppressWarnings("unchecked")
    void claimSignsRegionKey() throws Exception {
        when(conn.evalsha(anyString(), anyList(), anyList())).thenReturn(1L);
        UUID pid = UUID.randomUUID();
        ReservationToken t = binding.claim("srv-a", pid, Duration.ofSeconds(30), Optional.of("east"))
                .get(2, TimeUnit.SECONDS);
        ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
        verify(conn).evalsha(anyString(), anyList(), args.capture());
        List<String> a = args.getValue();
        assertTrue(CanonicalEnvelopes.verifyToken(verifier, 1, t.tokenId(), "srv-a", pid.toString(),
                a.get(3), a.get(4), "CLAIMED", "east", a.get(6)));
        assertTrue(!CanonicalEnvelopes.verifyToken(verifier, 1, t.tokenId(), "srv-a", pid.toString(),
                a.get(3), a.get(4), "CLAIMED", "west", a.get(6)), "regionKey must be bound by the HMAC");
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: findReservation drops a row whose regionKey was tampered")
    void findReservationRejectsTamperedRegion() throws Exception {
        UUID pid = UUID.randomUUID();
        Map<String, String> row = signedTokenRow("tok-11111111", "srv-a", pid, "east");
        when(conn.get("rtp:net:tokactive:" + pid)).thenReturn("tok-11111111");
        when(conn.hgetAll("rtp:net:tok:tok-11111111")).thenReturn(row);
        assertTrue(binding.findReservation(pid).get(2, TimeUnit.SECONDS).isPresent());

        row.put("regionKey", "nether");
        assertTrue(binding.findReservation(pid).get(2, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-004 / REQ-RTP-PROXY-007: redeem of tampered token returns HMAC_INVALID without EVALSHA")
    void redeemRejectsTamperedToken() throws Exception {
        UUID pid = UUID.randomUUID();
        Map<String, String> row = signedTokenRow("tok-22222222", "srv-a", pid, "east");
        row.put("regionKey", "nether");
        when(conn.hgetAll("rtp:net:tok:tok-22222222")).thenReturn(row);
        assertEquals(RedeemOutcome.HMAC_INVALID,
                binding.redeem("tok-22222222", pid, "srv-a").get(2, TimeUnit.SECONDS));
        verify(conn, never()).evalsha(anyString(), anyList(), anyList());
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-004 / REQ-RTP-PROXY-007: redeem of verified token passes the verified hmac to the Lua CAS")
    @SuppressWarnings("unchecked")
    void redeemPassesVerifiedHmacToLua() throws Exception {
        UUID pid = UUID.randomUUID();
        Map<String, String> row = signedTokenRow("tok-33333333", "srv-a", pid, "");
        when(conn.hgetAll("rtp:net:tok:tok-33333333")).thenReturn(row);
        when(conn.evalsha(anyString(), anyList(), anyList())).thenReturn("REDEEMED");
        assertEquals(RedeemOutcome.REDEEMED,
                binding.redeem("tok-33333333", pid, "srv-a").get(2, TimeUnit.SECONDS));
        ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
        verify(conn).evalsha(anyString(), anyList(), args.capture());
        assertEquals(row.get("hmac"), args.getValue().get(5));

        when(conn.evalsha(anyString(), anyList(), anyList())).thenReturn("HMAC_INVALID");
        assertEquals(RedeemOutcome.HMAC_INVALID,
                binding.redeem("tok-33333333", pid, "srv-a").get(2, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: claim with delimiter-injected regionKey is rejected before EVALSHA")
    void claimDelimiterInjectionRejected() {
        ExecutionException ex = assertThrows(ExecutionException.class, () ->
                binding.claim("srv-a", UUID.randomUUID(), Duration.ofSeconds(30), Optional.of("a=b"))
                        .get(2, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    private static List<Object> dequeueReply(List<String> enqueueArgv, String hintOverride) {
        return new ArrayList<>(List.of(
                "correlationId", enqueueArgv.get(0),
                "playerId", enqueueArgv.get(1),
                "regionKey", enqueueArgv.get(2),
                "serverHint", hintOverride != null ? hintOverride : enqueueArgv.get(3),
                "createdAtMs", enqueueArgv.get(4),
                "dequeuedAtMs", "99",
                "hmac", enqueueArgv.get(7)));
    }

    @Test
    @DisplayName("REQ-RTP-NET-008 / REQ-RTP-PROXY-007: signed envelope verifies on dequeue; tampered one is dropped")
    @SuppressWarnings("unchecked")
    void queueEnvelopeSignedAndVerified() throws Exception {
        when(conn.evalsha(anyString(), anyList(), anyList())).thenReturn(List.of(1L, 0L));
        UUID pid = UUID.randomUUID();
        UUID cid = UUID.randomUUID();
        assertEquals(EnrolOutcome.ACCEPTED, queue.enrol(new EnrolmentEnvelope(
                pid, cid, Optional.of("east"), Optional.of("srv-a"), 1234L)).get(2, TimeUnit.SECONDS));
        ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
        verify(conn).evalsha(anyString(), anyList(), argv.capture());
        List<String> enq = argv.getValue();
        assertEquals(8, enq.size(), "8-tuple per envelope incl. hmac");

        when(conn.evalsha(anyString(), anyList(), anyList())).thenReturn(dequeueReply(enq, null));
        Optional<QueueEnvelope> ok = queue.dequeueReady(Duration.ofMillis(50)).get(2, TimeUnit.SECONDS);
        assertTrue(ok.isPresent());
        assertEquals(pid, ok.get().playerId());

        when(conn.evalsha(anyString(), anyList(), anyList())).thenReturn(dequeueReply(enq, "srv-evil"));
        assertTrue(queue.dequeueReady(Duration.ofMillis(50)).get(2, TimeUnit.SECONDS).isEmpty());
        assertTrue(queue.dequeueReady(Duration.ofMillis(50), "proxy-1").get(2, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    @DisplayName("REQ-RTP-NET-008 / REQ-RTP-PROXY-007: unsigned (injected) envelope is dropped on dequeue")
    void unsignedEnvelopeDropped() throws Exception {
        List<Object> reply = List.of(
                "correlationId", UUID.randomUUID().toString(),
                "playerId", UUID.randomUUID().toString(),
                "regionKey", "", "serverHint", "", "createdAtMs", "1",
                "dequeuedAtMs", "2", "hmac", "");
        when(conn.evalsha(anyString(), anyList(), anyList())).thenReturn(reply);
        assertTrue(queue.dequeueReady(Duration.ofMillis(50)).get(2, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    @DisplayName("REQ-RTP-NET-008: duplicate-player enrol reported by enqueue_batch.lua resolves REJECTED")
    void duplicateEnrolRejected() throws Exception {
        when(conn.evalsha(anyString(), anyList(), anyList())).thenReturn(List.of(0L, 1L));
        EnrolmentEnvelope env = new EnrolmentEnvelope(
                UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), Optional.empty(), 1L);
        assertEquals(EnrolOutcome.REJECTED, queue.enrol(env).get(2, TimeUnit.SECONDS));
        // Batch flush skips with WARNING but stays ACCEPTED (backend buffer must not re-enqueue forever).
        assertEquals(EnrolOutcome.ACCEPTED, queue.flushPending(List.of(env)).get(2, TimeUnit.SECONDS));
    }

    /** Enrols {@code env} through the signed waitlist and returns the JSON payload handed to the Lua script. */
    @SuppressWarnings("unchecked")
    private String enrolAndCaptureJson(WaitEnvelope env) throws Exception {
        when(conn.evalsha(anyString(), anyList(), anyList())).thenReturn("ACCEPTED");
        assertEquals(NetworkWaitlist.EnrolOutcome.ACCEPTED, waitlist.enrol(env).get(2, TimeUnit.SECONDS));
        ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
        verify(conn).evalsha(anyString(), anyList(), argv.capture());
        return argv.getValue().get(2);
    }

    @Test
    @DisplayName("REQ-RTP-NET-015 / REQ-RTP-PROXY-007: signed waitlist entry drains, also after the TTL refresh rewrites enrolledAtMs")
    void waitlistEntrySignedAndVerifiedOnDrain() throws Exception {
        UUID pid = UUID.randomUUID();
        WaitEnvelope env = new WaitEnvelope(pid, UUID.randomUUID(), Optional.of("east"),
                Optional.of("srv-a"), "lobby", 1000L);
        String json = enrolAndCaptureJson(env);
        assertTrue(json.contains("\"hmac\":\""), "entry carries an hmac field: " + json);

        // Same in-place rewrite as waitlist_refresh_ttl.lua.
        String refreshed = json.replace("\"enrolledAtMs\":1000", "\"enrolledAtMs\":987654321");
        when(conn.evalsha(anyString(), anyList(), anyList()))
                .thenReturn(List.of(refreshed), List.of(refreshed));
        Map<String, List<WaitEnvelope>> out = waitlist.drainBatch(Map.of("srv-a", 5), 5)
                .get(2, TimeUnit.SECONDS);
        assertEquals(1, out.getOrDefault("srv-a", List.of()).size());
        assertEquals(pid, out.get("srv-a").get(0).playerId());
    }

    @Test
    @DisplayName("REQ-RTP-NET-015 / REQ-RTP-PROXY-007: tampered and unsigned waitlist entries are removed, never dispatched")
    @SuppressWarnings("unchecked")
    void tamperedAndUnsignedWaitlistEntriesPurged() throws Exception {
        WaitEnvelope env = new WaitEnvelope(UUID.randomUUID(), UUID.randomUUID(), Optional.of("east"),
                Optional.of("srv-a"), "lobby", 1000L);
        String tampered = enrolAndCaptureJson(env).replace("\"srv-a\"", "\"srv-evil\"");
        WaitEnvelope injectedEnv = new WaitEnvelope(UUID.randomUUID(), UUID.randomUUID(), Optional.empty(),
                Optional.of("srv-a"), "lobby", 1000L);
        String unsigned = RedisNetworkWaitlist.encode(injectedEnv);

        when(conn.evalsha(anyString(), anyList(), anyList()))
                .thenReturn(List.of(tampered, unsigned), List.of(tampered, unsigned));
        Map<String, List<WaitEnvelope>> out = waitlist.drainBatch(Map.of("srv-a", 5, "srv-evil", 5), 5)
                .get(2, TimeUnit.SECONDS);
        assertTrue(out.isEmpty(), "no unverified entry may reach the dispatcher: " + out);

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
        verify(conn, org.mockito.Mockito.atLeast(1)).evalsha(anyString(), anyList(), argv.capture());
        List<String> purge = argv.getAllValues().get(argv.getAllValues().size() - 1);
        assertEquals(List.of(env.correlationId().toString(), env.playerId().toString(), tampered,
                injectedEnv.correlationId().toString(), injectedEnv.playerId().toString(), unsigned), purge);
    }

    @Test
    @DisplayName("REQ-RTP-NET-015 / REQ-RTP-PROXY-007: delimiter-injected waitlist field fails enrol without EVALSHA")
    void waitlistEnrolDelimiterInjectionRejected() throws Exception {
        WaitEnvelope env = new WaitEnvelope(UUID.randomUUID(), UUID.randomUUID(), Optional.empty(),
                Optional.empty(), "lobby=x", 1L);
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> waitlist.enrol(env).get(2, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        verify(conn, never()).evalsha(anyString(), any(List.class), any(List.class));
    }

    @Test
    @DisplayName("REQ-RTP-NET-008: delimiter-injected serverHint is rejected at enrol without EVALSHA")
    void enrolDelimiterInjectionRejected() throws Exception {
        EnrolmentEnvelope env = new EnrolmentEnvelope(
                UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), Optional.of("srv\u0000x"), 1L);
        assertEquals(EnrolOutcome.REJECTED, queue.enrol(env).get(2, TimeUnit.SECONDS));
        verify(conn, never()).evalsha(anyString(), any(List.class), any(List.class));
    }
}
