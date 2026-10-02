package io.github.dailystruggle.rtp.common.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DispatchModeTest {

    @Test
    void parse_recognizedValues() {
        assertEquals(DispatchMode.DIRECT_DB, DispatchMode.parse("direct-db"));
        assertEquals(DispatchMode.DIRECT_DB, DispatchMode.parse("direct_db"));
        assertEquals(DispatchMode.DIRECT_DB, DispatchMode.parse("directdb"));
        assertEquals(DispatchMode.DIRECT_DB, DispatchMode.parse("DIRECT-DB"));
        assertEquals(DispatchMode.DIRECT_DB, DispatchMode.parse("direct"));

        assertEquals(DispatchMode.PROXY_BROKER, DispatchMode.parse("proxy-broker"));
        assertEquals(DispatchMode.PROXY_BROKER, DispatchMode.parse("proxy_broker"));
        assertEquals(DispatchMode.PROXY_BROKER, DispatchMode.parse("proxybroker"));
        assertEquals(DispatchMode.PROXY_BROKER, DispatchMode.parse("PROXY-BROKER"));
        assertEquals(DispatchMode.PROXY_BROKER, DispatchMode.parse("broker"));
        assertEquals(DispatchMode.PROXY_BROKER, DispatchMode.parse("proxy"));

        assertEquals(DispatchMode.AUTO, DispatchMode.parse("auto"));
        assertEquals(DispatchMode.AUTO, DispatchMode.parse("AUTO"));
    }

    @Test
    void parse_nullOrUnknown_defaultsToAuto() {
        assertEquals(DispatchMode.AUTO, DispatchMode.parse(null));
        assertEquals(DispatchMode.AUTO, DispatchMode.parse(""));
        assertEquals(DispatchMode.AUTO, DispatchMode.parse("   "));
        assertEquals(DispatchMode.AUTO, DispatchMode.parse("unknown-mode"));
    }

    @Test
    void resolve_explicitModes_preserved() {
        assertEquals(DispatchMode.DIRECT_DB,
                DispatchMode.resolve(DispatchMode.DIRECT_DB, "sql", true));
        assertEquals(DispatchMode.DIRECT_DB,
                DispatchMode.resolve(DispatchMode.DIRECT_DB, "proxy-direct", true));
        assertEquals(DispatchMode.PROXY_BROKER,
                DispatchMode.resolve(DispatchMode.PROXY_BROKER, "sql", false));
    }

    @Test
    void resolve_autoWithProxyDirectOrPluginMessage_resolvesToProxyBroker() {
        assertEquals(DispatchMode.PROXY_BROKER,
                DispatchMode.resolve(DispatchMode.AUTO, "proxy-direct", false));
        assertEquals(DispatchMode.PROXY_BROKER,
                DispatchMode.resolve(DispatchMode.AUTO, "plugin-message", false));
        assertEquals(DispatchMode.PROXY_BROKER,
                DispatchMode.resolve(DispatchMode.AUTO, "proxy-cache", false));
    }

    @Test
    void resolve_autoWithDurableStores_resolvesByProxyPresence() {
        // When proxy companion is active (hasActiveProxy = true) -> PROXY_BROKER
        assertEquals(DispatchMode.PROXY_BROKER,
                DispatchMode.resolve(DispatchMode.AUTO, "sql", true));
        assertEquals(DispatchMode.PROXY_BROKER,
                DispatchMode.resolve(DispatchMode.AUTO, "redis", true));

        // When no proxy companion is active (hasActiveProxy = false) -> DIRECT_DB
        assertEquals(DispatchMode.DIRECT_DB,
                DispatchMode.resolve(DispatchMode.AUTO, "sql", false));
        assertEquals(DispatchMode.DIRECT_DB,
                DispatchMode.resolve(DispatchMode.AUTO, "redis", false));
        assertEquals(DispatchMode.DIRECT_DB,
                DispatchMode.resolve(DispatchMode.AUTO, "in-memory", false));
    }
}
