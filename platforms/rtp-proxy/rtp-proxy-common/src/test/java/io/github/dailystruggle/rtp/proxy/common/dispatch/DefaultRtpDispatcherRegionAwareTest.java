package io.github.dailystruggle.rtp.proxy.common.dispatch;

import io.github.dailystruggle.rtp.proxy.common.selector.LoadBalancerConfig;
import io.github.dailystruggle.rtp.proxy.common.selector.RegionAwareSelector;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendSelector;
import io.github.dailystruggle.rtp.proxy.common.spi.DispatchOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.MessageKey;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxyHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxySender;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpRequest;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;
import io.github.dailystruggle.rtp.proxy.common.spi.TransferOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.TriggerType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Region-aware fallback (rtp-proxy-ADR-004 *Region-Pair Scoring* follow-up):
 * verifies that {@link DefaultRtpDispatcher} consults the injected
 * {@link RegionAwareSelector} for a regionless {@link RtpRequest} and
 * rewrites the request with the picked regionKey before the existing
 * {@link BackendSelector} claim path runs.
 *
 * <p>Asserts the two contractual outcomes the user signed off on:</p>
 * <ol>
 *   <li>A regionless {@code /rtp} now flows through the curve-driven
 *       scoring table even when no lobby pre-decided the region (proxy
 *       handles the no-lobby case the user described).</li>
 *   <li>An explicitly region-pinned request is honoured verbatim - the
 *       region-aware selector does NOT overwrite a lobby's pick.</li>
 * </ol>
 */
class DefaultRtpDispatcherRegionAwareTest {

    private static BackendHeartbeat hb(String id,
                                       Set<String> regions,
                                       Map<String, Integer> kept) {
        return new BackendHeartbeat(
                id, 1, PluginState.READY, true,
                System.currentTimeMillis(), 5.0, 0, 100,
                0L, 1L, 0,
                List.of(), List.of(),
                /*killSwitch*/ false, 0, 0, regions, kept);
    }

    private static RtpRequest regionlessRequest(UUID player) {
        return new RtpRequest(player, TriggerType.COMMAND,
                Optional.empty(), Optional.empty(), Optional.empty(),
                UUID.randomUUID());
    }

    private static RtpRequest pinnedRequest(UUID player, String regionKey) {
        return new RtpRequest(player, TriggerType.COMMAND,
                Optional.of(regionKey), Optional.empty(), Optional.empty(),
                UUID.randomUUID());
    }

    @Test
    void regionless_request_is_enriched_by_RegionAwareSelector_before_claim() throws Exception {
        UUID player = UUID.randomUUID();
        // backend-a's "default" pool is warm (64 ready); backend-b's "east"
        // is nearly empty (4 of 64). The synthesized KEPT_REGION term with
        // exponential(k=5) heavily penalises shallow pools (penalty
        // contributes to the score; lowest score wins), so the region-aware
        // selector picks (backend-a, default).
        FakeTransport transport = new FakeTransport(List.of(
                hb("backend-a", Set.of("default"), Map.of("default", 64)),
                hb("backend-b", Set.of("east"),    Map.of("east", 4))));
        FakeSender sender = new FakeSender(player);
        AtomicReference<RtpRequest> selectorSawRequest = new AtomicReference<>();
        AtomicReference<Optional<String>> selectorSawFilter = new AtomicReference<>();
        BackendSelector picker = new BackendSelector() {
            @Override
            public Optional<String> choose(RtpRequest req, NetworkSnapshot snap) {
                selectorSawRequest.set(req);
                selectorSawFilter.set(Optional.empty());
                return Optional.of("backend-a"); // would be wrong; region-aware path overrides via filter
            }
            @Override
            public Optional<String> choose(RtpRequest req, NetworkSnapshot snap, Optional<String> filter) {
                selectorSawRequest.set(req);
                selectorSawFilter.set(filter);
                return filter; // honour the pin so we observe the region-aware decision end-to-end
            }
        };
        RegionAwareSelector regionAware = new RegionAwareSelector(LoadBalancerConfig.defaults());
        DefaultRtpDispatcher d = new DefaultRtpDispatcher(
                picker, transport, sender, Runnable::run,
                DefaultRtpDispatcher.DEFAULT_RESERVATION_TTL,
                StatusSink.NO_OP, null, "proxy", regionAware);

        DispatchOutcome outcome = d.dispatch(regionlessRequest(player)).get();

        assertInstanceOf(DispatchOutcome.Routed.class, outcome);
        DispatchOutcome.Routed routed = (DispatchOutcome.Routed) outcome;
        assertEquals("backend-a", routed.serverId(),
                "region-aware fallback must drive the pick to the warm-pool backend");

        RtpRequest seen = selectorSawRequest.get();
        assertNotNull(seen, "BackendSelector.choose must have been invoked");
        assertTrue(seen.regionKey().isPresent(),
                "the request reaching BackendSelector must have a regionKey populated by the fallback");
        assertEquals("default", seen.regionKey().get(),
                "regionKey must equal the RegionAwareSelector's pick");
        Optional<String> filter = selectorSawFilter.get();
        assertNotNull(filter);
        assertTrue(filter.isPresent() && "backend-a".equals(filter.get()),
                "serverIdFilter must pin the BackendSelector to the region-aware-chosen backend");
    }

    @Test
    void explicit_regionKey_pin_is_NOT_overwritten_by_RegionAwareSelector() throws Exception {
        UUID player = UUID.randomUUID();
        FakeTransport transport = new FakeTransport(List.of(
                hb("backend-a", Set.of("default"), Map.of("default", 4)),
                hb("backend-b", Set.of("east"),    Map.of("east", 64))));
        FakeSender sender = new FakeSender(player);
        AtomicReference<RtpRequest> selectorSawRequest = new AtomicReference<>();
        BackendSelector picker = (req, snap) -> {
            selectorSawRequest.set(req);
            return Optional.of("backend-a");
        };
        RegionAwareSelector regionAware = new RegionAwareSelector(LoadBalancerConfig.defaults());
        DefaultRtpDispatcher d = new DefaultRtpDispatcher(
                picker, transport, sender, Runnable::run,
                DefaultRtpDispatcher.DEFAULT_RESERVATION_TTL,
                StatusSink.NO_OP, null, "proxy", regionAware);

        DispatchOutcome outcome = d.dispatch(pinnedRequest(player, "default")).get();

        assertInstanceOf(DispatchOutcome.Routed.class, outcome);
        RtpRequest seen = selectorSawRequest.get();
        assertNotNull(seen);
        assertTrue(seen.regionKey().isPresent());
        assertEquals("default", seen.regionKey().get(),
                "an explicit regionKey on the inbound request must be passed through unchanged");
    }

    // ---- fakes ----

    private static final class FakeTransport implements NetworkTransport {
        final NetworkSnapshot snapshot;

        FakeTransport(List<BackendHeartbeat> backends) {
            LinkedHashMap<String, BackendHeartbeat> map = new LinkedHashMap<>();
            for (BackendHeartbeat b : backends) map.put(b.serverId(), b);
            this.snapshot = new NetworkSnapshot(System.currentTimeMillis(), map);
        }

        @Override
        public CompletableFuture<NetworkSnapshot> readSnapshot() {
            return CompletableFuture.completedFuture(snapshot);
        }

        @Override
        public CompletableFuture<ReservationToken> claim(String serverId, UUID playerId, Duration ttl) {
            String tokenId = UUID.randomUUID().toString();
            return CompletableFuture.completedFuture(new ReservationToken(
                    tokenId, serverId, playerId,
                    System.currentTimeMillis() + ttl.toMillis(),
                    ReservationToken.State.CLAIMED));
        }

        @Override
        public CompletableFuture<Void> release(String tokenId, ReleaseReason reason) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> publishProxyHeartbeat(ProxyHeartbeat row) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public Subscription subscribeBackendHeartbeats(Consumer<BackendHeartbeat> sink) {
            return new Subscription() {
                @Override public void close() { }
                @Override public boolean isClosed() { return false; }
            };
        }

        @Override public void close() { }
    }

    private static final class FakeSender implements ProxySender {
        final UUID self;

        FakeSender(UUID self) { this.self = self; }

        @Override
        public CompletableFuture<TransferOutcome> sendTo(UUID playerId, String serverId, ReservationToken token) {
            return CompletableFuture.completedFuture(TransferOutcome.SUCCESS);
        }

        @Override
        public boolean isConnected(UUID playerId) { return self.equals(playerId); }

        @Override
        public void sendMessage(UUID playerId, MessageKey key, Map<String, String> placeholders) { }
    }
}
