package io.github.dailystruggle.rtp.api.configuration.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Table-driven coverage for the {@code messages/<concern>.yml} key enums (ADR-071).
 *
 * <p>These enums are the platform-neutral source of truth for locale keys, so every constant
 * must round-trip through {@code valueOf}/{@code name} and preserve a stable ordinal. Exercises
 * the synthetic {@code values()}/{@code valueOf(String)} of each enum and the fallback contract.
 */
class MessageEnumsTest {

    private static <E extends Enum<E>> void assertRoundTrips(Class<E> type, E[] values) {
        assertTrue(values.length > 0, type.getSimpleName() + " must declare at least one key");
        for (int i = 0; i < values.length; i++) {
            E value = values[i];
            assertNotNull(value.name(), "name must not be null");
            assertEquals(i, value.ordinal(), "ordinal must match declaration order");
            E parsed = Enum.valueOf(type, value.name());
            assertSame(value, parsed, "valueOf must return the same singleton");
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> Enum.valueOf(type, "definitely_not_a_real_key"),
                "valueOf must reject unknown keys");
    }

    @Test
    void commandMessagesRoundTrip() {
        assertRoundTrips(CommandMessages.class, CommandMessages.values());
    }

    @Test
    void networkMessagesRoundTrip() {
        assertRoundTrips(NetworkMessages.class, NetworkMessages.values());
    }

    @Test
    void placeholderMessagesRoundTrip() {
        assertRoundTrips(PlaceholderMessages.class, PlaceholderMessages.values());
    }

    @Test
    void playerMessagesRoundTrip() {
        assertRoundTrips(PlayerMessages.class, PlayerMessages.values());
    }

    @Test
    void systemMessagesRoundTrip() {
        assertRoundTrips(SystemMessages.class, SystemMessages.values());
    }

    @Test
    void knownAnchorKeysResolve() {
        // Guard a couple of stable, documented keys against accidental rename.
        assertEquals("scanStart", CommandMessages.valueOf("scanStart").name());
        assertNotNull(CommandMessages.menuInvalid);
    }
}
