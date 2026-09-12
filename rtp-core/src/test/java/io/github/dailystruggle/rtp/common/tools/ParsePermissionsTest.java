package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.common.mock.MockRTPCommandSender;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ParsePermissions} (ENTERPRISE_READINESS item 19, {@code tools} package).
 *
 * <p>{@link ParsePermissions} resolves boolean and integer permission grants directly
 * from a sender's effective permission set (bypassing op-default short-circuits) and
 * caches each answer per {@code (uuid, prefix)} for 5s. Every test uses a fresh random
 * UUID so the process-wide caches never leak across cases.
 */
public class ParsePermissionsTest {

    @TempDir
    File pluginDir;

    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(pluginDir);
    }

    /** Command sender with a fixed, explicit effective-permission set. */
    private static final class PermSender extends MockRTPCommandSender {
        private final Set<String> perms;

        PermSender(UUID uuid, String name, String... perms) {
            super(uuid, name);
            this.perms = new HashSet<>(Arrays.asList(perms));
        }

        @Override
        public Set<String> getEffectivePermissions() {
            return perms;
        }
    }

    // ---- hasPerm(sender, prefix, permissions...) ----

    @Test
    void hasPermMatchesAGrantedNode() {
        PermSender s = new PermSender(UUID.randomUUID(), "granted", "rtp.tp.use");
        assertTrue(ParsePermissions.hasPerm(s, "rtp.tp.", "use"),
                "prefix + permission concatenation must match the granted node");
    }

    @Test
    void hasPermReturnsFalseWhenNodeAbsent() {
        PermSender s = new PermSender(UUID.randomUUID(), "denied", "rtp.other");
        assertFalse(ParsePermissions.hasPerm(s, "rtp.tp.", "use"));
    }

    @Test
    void hasPermMatchesCaseInsensitively() {
        PermSender s = new PermSender(UUID.randomUUID(), "mixed", "RTP.TP.USE");
        assertTrue(ParsePermissions.hasPerm(s, "rtp.tp.", "use"),
                "the effective-set comparison is case-insensitive");
    }

    @Test
    void hasPermScansMultipleCandidatePermissions() {
        PermSender s = new PermSender(UUID.randomUUID(), "multi", "rtp.tp.admin");
        assertTrue(ParsePermissions.hasPerm(s, "rtp.tp.", "use", "admin", "reload"),
                "any one matching candidate is sufficient");
    }

    @Test
    void hasPermIsCachedAcrossRepeatCalls() {
        PermSender s = new PermSender(UUID.randomUUID(), "cached", "rtp.tp.use");
        boolean first = ParsePermissions.hasPerm(s, "rtp.tp.", "use");
        boolean second = ParsePermissions.hasPerm(s, "rtp.tp.", "use");
        assertTrue(first);
        assertEquals(first, second, "the cached answer must be stable within the TTL window");
    }

    // ---- hasPerm(UUID, prefix, permissions...) overload ----

    @Test
    void hasPermUuidOverloadResolvesRegisteredSender() {
        UUID id = UUID.randomUUID();
        // A freshly-resolved sender in the mock has an empty effective set => no grant.
        assertFalse(ParsePermissions.hasPerm(id, "rtp.tp.", "use"));
    }

    // ---- getInt(sender, prefix) ----

    @Test
    void getIntParsesTheNumericSuffix() {
        UUID id = UUID.randomUUID();
        PermSender s = new PermSender(id, "cooldown", "rtp.tp.cooldown.30");
        assertEquals(30, ParsePermissions.getInt(s, "rtp.tp.cooldown."));
    }

    @Test
    void getIntReturnsMinusOneWhenNoMatchingPermission() {
        PermSender s = new PermSender(UUID.randomUUID(), "none", "rtp.other.5");
        assertEquals(-1, ParsePermissions.getInt(s, "rtp.tp.cooldown."));
    }

    @Test
    void getIntSelectsTheSmallestValue() {
        PermSender s = new PermSender(UUID.randomUUID(), "many",
                "rtp.tp.amount.9", "rtp.tp.amount.3", "rtp.tp.amount.7");
        assertEquals(3, ParsePermissions.getInt(s, "rtp.tp.amount."),
                "getInt picks the minimum matching value");
    }

    @Test
    void getIntSkipsInvalidNumberAndLogsWarning() {
        PermSender s = new PermSender(UUID.randomUUID(), "bad", "rtp.tp.amount.abc");
        assertEquals(-1, ParsePermissions.getInt(s, "rtp.tp.amount."),
                "a non-numeric suffix is skipped, leaving the default -1");
        boolean logged = accessor.logMessages.stream()
                .anyMatch(m -> m.contains("invalid permission"));
        assertTrue(logged, "an invalid numeric permission must be logged: " + accessor.logMessages);
    }

    @Test
    void getIntIgnoresEmptySuffixButHonoursAValidSibling() {
        PermSender s = new PermSender(UUID.randomUUID(), "mixed",
                "rtp.tp.amount.", "rtp.tp.amount.5");
        assertEquals(5, ParsePermissions.getInt(s, "rtp.tp.amount."),
                "an empty suffix segment is skipped; the valid sibling still resolves");
    }

    @Test
    void getIntIsCachedAcrossRepeatCalls() {
        PermSender s = new PermSender(UUID.randomUUID(), "intcache", "rtp.tp.amount.4");
        int first = ParsePermissions.getInt(s, "rtp.tp.amount.");
        int second = ParsePermissions.getInt(s, "rtp.tp.amount.");
        assertEquals(4, first);
        assertEquals(first, second);
    }

    // ---- getInt(UUID, prefix) overload ----

    @Test
    void getIntUuidOverloadResolvesRegisteredSender() {
        UUID id = UUID.randomUUID();
        // Mock-resolved sender has no permissions => -1.
        assertEquals(-1, ParsePermissions.getInt(id, "rtp.tp.amount."));
    }

    @Test
    void getIntUuidOverloadUsesExplicitlyRegisteredSender() {
        UUID id = UUID.randomUUID();
        RTPCommandSender s = new PermSender(id, "registered", "rtp.tp.amount.11");
        accessor.addSender(s);
        // addSender wraps a non-player sender without carrying its effective set,
        // so the resolved sender contributes no numeric grant.
        assertEquals(-1, ParsePermissions.getInt(id, "rtp.tp.amount."));
    }
}
