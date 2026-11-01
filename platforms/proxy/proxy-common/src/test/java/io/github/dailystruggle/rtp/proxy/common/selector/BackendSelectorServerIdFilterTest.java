package io.github.dailystruggle.rtp.proxy.common.selector;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpRequest;
import io.github.dailystruggle.rtp.proxy.common.spi.TriggerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@code serverIdFilter} parameter added to
 * {@link io.github.dailystruggle.rtp.proxy.common.spi.BackendSelector#choose(RtpRequest, NetworkSnapshot, Optional)}.
 *
 * <p>This parameter is plumbing for the deferred D6/D7 (B)
 * {@code <server>=<region>} qualified-region rollout and is never set by any
 * caller; the tests pin its contract so the downstream rollout does not
 * need to retest the selector itself.</p>
 */
class BackendSelectorServerIdFilterTest {

    private static final long NOW = 1_700_000_000_000L;

    private static BackendHeartbeat hb(String id, double mspt) {
        return new BackendHeartbeat(
                id, 1, PluginState.READY, true, NOW,
                mspt, 0, 20, 0L, 0L, 0,
                List.of(), List.of());
    }

    private static RtpRequest req() {
        return new RtpRequest(UUID.randomUUID(), TriggerType.COMMAND,
                Optional.empty(), Optional.empty(), Optional.empty(),
                UUID.randomUUID());
    }

    private static NetworkSnapshot snap(BackendHeartbeat... rows) {
        Map<String, BackendHeartbeat> m = new LinkedHashMap<>();
        for (BackendHeartbeat r : rows) m.put(r.serverId(), r);
        return new NetworkSnapshot(NOW, m);
    }

    @Test
    @DisplayName("serverIdFilter present: only the matching backend qualifies")
    void filterPinsServerId() {
        BackendHeartbeat lighter = hb("a", 5);
        BackendHeartbeat heavier = hb("b", 40);
        var sel = new WeightedAverageBackendSelector(LoadBalancerConfig.defaults());
        // Without filter, "a" wins on score.
        assertEquals(Optional.of("a"),
                sel.choose(req(), snap(lighter, heavier), Optional.empty()));
        // With filter "b", only "b" qualifies even though it scores higher.
        assertEquals(Optional.of("b"),
                sel.choose(req(), snap(lighter, heavier), Optional.of("b")));
    }

    @Test
    @DisplayName("serverIdFilter matches no backend -> empty result")
    void filterMatchesNothing() {
        BackendHeartbeat a = hb("a", 5);
        BackendHeartbeat b = hb("b", 5);
        var sel = new WeightedAverageBackendSelector(LoadBalancerConfig.defaults());
        assertTrue(sel.choose(req(), snap(a, b), Optional.of("c")).isEmpty());
    }

    @Test
    @DisplayName("serverIdFilter empty is equivalent to two-arg choose(...)")
    void emptyFilterEquivalent() {
        BackendHeartbeat a = hb("a", 5);
        BackendHeartbeat b = hb("b", 40);
        var sel = new WeightedAverageBackendSelector(LoadBalancerConfig.defaults());
        assertEquals(sel.choose(req(), snap(a, b)),
                sel.choose(req(), snap(a, b), Optional.empty()));
    }

    @Test
    @DisplayName("serverIdFilter is case-sensitive (exact match)")
    void caseSensitive() {
        BackendHeartbeat a = hb("Alpha", 5);
        var sel = new WeightedAverageBackendSelector(LoadBalancerConfig.defaults());
        assertEquals(Optional.of("Alpha"),
                sel.choose(req(), snap(a), Optional.of("Alpha")));
        assertTrue(sel.choose(req(), snap(a), Optional.of("alpha")).isEmpty());
    }

    @Test
    @DisplayName("null serverIdFilter is rejected with NPE")
    void nullFilterRejected() {
        BackendHeartbeat a = hb("a", 5);
        var sel = new WeightedAverageBackendSelector(LoadBalancerConfig.defaults());
        assertThrows(NullPointerException.class,
                () -> sel.choose(req(), snap(a), null));
    }
}
