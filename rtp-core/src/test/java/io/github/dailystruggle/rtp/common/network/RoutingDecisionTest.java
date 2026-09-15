package io.github.dailystruggle.rtp.common.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class RoutingDecisionTest {

    @Test
    @DisplayName("Local singleton is valid and implements RoutingDecision")
    void localSingleton() {
        RoutingDecision.Local local1 = RoutingDecision.Local.INSTANCE;
        RoutingDecision.Local local2 = new RoutingDecision.Local();
        assertNotNull(local1);
        assertEquals(local1, local2);
    }

    @Test
    @DisplayName("CrossServer handles populated and null values gracefully")
    void crossServerNormalization() {
        RoutingDecision.CrossServer cs1 = new RoutingDecision.CrossServer(Optional.of("srv1"), Optional.of("reg1"));
        assertEquals(Optional.of("srv1"), cs1.serverHint());
        assertEquals(Optional.of("reg1"), cs1.regionKey());

        // Null Optionals should normalize to Optional.empty()
        RoutingDecision.CrossServer cs2 = new RoutingDecision.CrossServer(null, null);
        assertEquals(Optional.empty(), cs2.serverHint());
        assertEquals(Optional.empty(), cs2.regionKey());
    }

    @Test
    @DisplayName("LocalFallback handles populated and null reason gracefully")
    void localFallbackNormalization() {
        RoutingDecision.LocalFallback lf1 = new RoutingDecision.LocalFallback(RoutingDecision.FallbackReason.ROUTING_MODE_LOCAL);
        assertEquals(RoutingDecision.FallbackReason.ROUTING_MODE_LOCAL, lf1.reason());

        // Null reason normalizes to UNKNOWN
        RoutingDecision.LocalFallback lf2 = new RoutingDecision.LocalFallback(null);
        assertEquals(RoutingDecision.FallbackReason.UNKNOWN, lf2.reason());
    }

    @Test
    @DisplayName("FallbackReason enum values are complete and distinct")
    void fallbackReasonEnumValues() {
        for (RoutingDecision.FallbackReason reason : RoutingDecision.FallbackReason.values()) {
            assertNotNull(reason.name());
            assertSame(reason, RoutingDecision.FallbackReason.valueOf(reason.name()));
        }
        assertEquals(8, RoutingDecision.FallbackReason.values().length);
    }
}
