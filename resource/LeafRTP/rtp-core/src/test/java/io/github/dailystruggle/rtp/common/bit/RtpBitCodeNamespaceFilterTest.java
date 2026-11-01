package io.github.dailystruggle.rtp.common.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitCodeNamespace;
import io.github.dailystruggle.bit.BitCodeRegistry;
import io.github.dailystruggle.bit.BitTier;
import java.lang.System.Logger.Level;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-F-009 addon BIT catalogs are namespace-local")
class RtpBitCodeNamespaceFilterTest {
    @Test
    void markdownAndLockOperationsOnlyIncludeTheRequestedNamespace() {
        BitCodeRegistry registry = new BitCodeRegistry();
        BitCodeNamespace demo = registry.reserve("DEMO", "demo-addon");
        demo.register(1, "DEMO_FIRST", Level.WARNING, BitTier.PBIT, "demo", "", "Fix demo.");
        BitCodeNamespace other = registry.reserve("OTHER", "other-addon");
        other.register(1, "OTHER_FIRST", Level.ERROR, BitTier.PBIT, "other", "", "Fix other.");
        List<RtpBitCodeLock.Entry> demoLock = List.of(
                new RtpBitCodeLock.Entry("DEMO", 1, "DEMO_FIRST", Level.WARNING));

        String markdown = RtpBitCodeCatalog.markdown(registry, "DEMO", "Demo Addon BIT Codes");
        List<String> violations = RtpBitCodeLock.violations(registry, demoLock, "DEMO");
        List<RtpBitCodeLock.Entry> updated = RtpBitCodeLock.update(registry, demoLock, "DEMO");

        assertTrue(markdown.contains("# Demo Addon BIT Codes"));
        assertTrue(markdown.contains("DEMO_FIRST"));
        assertTrue(!markdown.contains("OTHER_FIRST"));
        assertEquals(List.of(), violations);
        assertEquals(demoLock, updated);
    }
}
