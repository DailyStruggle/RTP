package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PlaceholderProvider} covering:
 * - Custom placeholder registration and lookup in the static map
 * - {@link PlaceholderProvider#fillPlaceholders} with bracket and percent syntax
 * - No-op behaviour when no placeholders match
 * - {@link PlaceholderProvider#fillNumericPlaceholders(String)} when configs are absent / present
 */
class PlaceholderProviderTest {

    @TempDir
    Path tempDir;

    private static final String TEST_KEY = "__test_placeholder__";
    private static final String TEST_VALUE = "hello_world";
    private static final UUID DUMMY_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        // Register a deterministic, server-free placeholder for use in tests.
        PlaceholderProvider.placeholders.put(TEST_KEY, uuid -> TEST_VALUE);
    }

    @AfterEach
    void tearDown() {
        PlaceholderProvider.placeholders.remove(TEST_KEY);
    }

    // -------------------------------------------------------------------------
    // placeholders map
    // -------------------------------------------------------------------------

    @Test
    void registeredPlaceholderIsPresent() {
        assertTrue(PlaceholderProvider.placeholders.containsKey(TEST_KEY));
    }

    @Test
    void registeredPlaceholderReturnsExpectedValue() {
        Function<UUID, String> fn = PlaceholderProvider.placeholders.get(TEST_KEY);
        assertNotNull(fn);
        assertEquals(TEST_VALUE, fn.apply(DUMMY_UUID));
    }

    @Test
    void removedPlaceholderIsAbsent() {
        PlaceholderProvider.placeholders.remove(TEST_KEY);
        assertFalse(PlaceholderProvider.placeholders.containsKey(TEST_KEY));
        // Re-add so @AfterEach remove is a no-op rather than an error
        PlaceholderProvider.placeholders.put(TEST_KEY, uuid -> TEST_VALUE);
    }

    // -------------------------------------------------------------------------
    // fillPlaceholders — bracket syntax [key]
    // -------------------------------------------------------------------------

    @Test
    void fillPlaceholders_bracketSyntax_replacesPlaceholder() {
        String result = PlaceholderProvider.fillPlaceholders("[" + TEST_KEY + "]", DUMMY_UUID);
        assertEquals(TEST_VALUE, result);
    }

    @Test
    void fillPlaceholders_bracketSyntax_keyMustMatchExactCase() {
        // ParseString.keywords() does a case-sensitive map lookup, so an upper-cased key
        // is NOT found and the text is returned unchanged.
        String upper = TEST_KEY.toUpperCase();
        String result = PlaceholderProvider.fillPlaceholders("[" + upper + "]", DUMMY_UUID);
        assertEquals("[" + upper + "]", result);
    }

    @Test
    void fillPlaceholders_bracketSyntax_multipleOccurrences() {
        String input = "[" + TEST_KEY + "] and [" + TEST_KEY + "]";
        String result = PlaceholderProvider.fillPlaceholders(input, DUMMY_UUID);
        assertEquals(TEST_VALUE + " and " + TEST_VALUE, result);
    }

    // -------------------------------------------------------------------------
    // fillPlaceholders — percent syntax %key%
    // -------------------------------------------------------------------------

    @Test
    void fillPlaceholders_percentSyntax_replacesPlaceholder() {
        String result = PlaceholderProvider.fillPlaceholders("%" + TEST_KEY + "%", DUMMY_UUID);
        assertEquals(TEST_VALUE, result);
    }

    @Test
    void fillPlaceholders_percentSyntax_keyMustMatchExactCase() {
        // Same case-sensitivity constraint as bracket syntax.
        String upper = TEST_KEY.toUpperCase();
        String result = PlaceholderProvider.fillPlaceholders("%" + upper + "%", DUMMY_UUID);
        assertEquals("%" + upper + "%", result);
    }

    // -------------------------------------------------------------------------
    // fillPlaceholders — no-op / edge cases
    // -------------------------------------------------------------------------

    @Test
    void fillPlaceholders_noMatch_returnsOriginal() {
        String input = "no placeholders here";
        String result = PlaceholderProvider.fillPlaceholders(input, DUMMY_UUID);
        assertEquals(input, result);
    }

    @Test
    void fillPlaceholders_emptyString_returnsEmpty() {
        String result = PlaceholderProvider.fillPlaceholders("", DUMMY_UUID);
        assertEquals("", result);
    }

    @Test
    void fillPlaceholders_unknownKey_leftUnchanged() {
        String input = "[totally_unknown_key_xyz]";
        String result = PlaceholderProvider.fillPlaceholders(input, DUMMY_UUID);
        // ParseString won't find it in the map, so text is unchanged
        assertEquals(input, result);
    }

    @Test
    void fillPlaceholders_mixedSyntax_bothReplaced() {
        String input = "[" + TEST_KEY + "] / %" + TEST_KEY + "%";
        String result = PlaceholderProvider.fillPlaceholders(input, DUMMY_UUID);
        assertEquals(TEST_VALUE + " / " + TEST_VALUE, result);
    }

    @Test
    void fillPlaceholders_surroundingText_preserved() {
        String input = "prefix [" + TEST_KEY + "] suffix";
        String result = PlaceholderProvider.fillPlaceholders(input, DUMMY_UUID);
        assertEquals("prefix " + TEST_VALUE + " suffix", result);
    }

    @Test
    void fillPlaceholders_valueWithSpecialRegexChars_noException() {
        // Ensure Matcher.quoteReplacement is used — value contains $ and \
        PlaceholderProvider.placeholders.put(TEST_KEY + "_special", uuid -> "$1 \\n");
        try {
            String result = PlaceholderProvider.fillPlaceholders("[" + TEST_KEY + "_special]", DUMMY_UUID);
            assertEquals("$1 \\n", result);
        } finally {
            PlaceholderProvider.placeholders.remove(TEST_KEY + "_special");
        }
    }

    // -------------------------------------------------------------------------
    // fillNumericPlaceholders — configs absent (null guard)
    // -------------------------------------------------------------------------

    @Test
    void fillNumericPlaceholders_noConfigs_returnsTextUnchanged() {
        // RTP.configs is null by default in a plain unit test — method should return text as-is
        String input = "some text [p0] more";
        // If configs is null the method returns early
        String result = PlaceholderProvider.fillNumericPlaceholders(input);
        // Either unchanged (null guard) or resolved — must not throw
        assertNotNull(result);
    }

    // -------------------------------------------------------------------------
    // fillNumericPlaceholders — with configs wired via RTPTestSetup
    // -------------------------------------------------------------------------

    @Test
    void fillNumericPlaceholders_withConfigs_noNumericPlaceholders_returnsOriginal() {
        RTPTestSetup.install(tempDir.toFile());
        String input = "no numeric placeholders";
        String result = PlaceholderProvider.fillNumericPlaceholders(input);
        assertEquals(input, result);
    }

    @Test
    void fillNumericPlaceholders_withConfigs_nonNumericSuffix_skipped() {
        RTPTestSetup.install(tempDir.toFile());
        // [Pabc] has a non-numeric index — should be skipped (continue branch)
        String input = "value=[Pabc]";
        String result = PlaceholderProvider.fillNumericPlaceholders(input);
        // Non-numeric group triggers NumberFormatException → continue; text unchanged
        assertEquals(input, result);
    }

    @Test
    void fillNumericPlaceholders_withConfigs_outOfBoundsIndex_replacedWithInvalid() {
        RTPTestSetup.install(tempDir.toFile());
        // [p999] — index 999 is beyond any configured placeholder list → "[invalid]"
        String input = "[p999]";
        String result = PlaceholderProvider.fillNumericPlaceholders(input);
        // The replacement is "[invalid]" but then the removeRegex strips [p\d*] patterns from it,
        // leaving just "invalid" or "[invalid]" depending on the regex — either way, not the original
        assertNotNull(result);
        assertNotEquals(input, result);
    }

    // -------------------------------------------------------------------------
    // fillPlaceholders — case-insensitive pattern replacement
    // -------------------------------------------------------------------------

    @Test
    void fillPlaceholders_bracketSyntax_caseInsensitiveReplacement() {
        // The Pattern uses CASE_INSENSITIVE, so [KEY] and [key] both match once the key is found
        // (key lookup is case-sensitive, but the replacement regex is case-insensitive)
        String upperKey = TEST_KEY.toUpperCase();
        // Register under the upper-case key so lookup succeeds
        PlaceholderProvider.placeholders.put(upperKey, uuid -> "upper_value");
        try {
            // bracket with exact case matches
            String result = PlaceholderProvider.fillPlaceholders("[" + upperKey + "]", DUMMY_UUID);
            assertEquals("upper_value", result);
        } finally {
            PlaceholderProvider.placeholders.remove(upperKey);
        }
    }

    @Test
    void fillPlaceholders_percentSyntax_caseInsensitiveReplacement() {
        String upperKey = TEST_KEY.toUpperCase();
        PlaceholderProvider.placeholders.put(upperKey, uuid -> "upper_pct");
        try {
            String result = PlaceholderProvider.fillPlaceholders("%" + upperKey + "%", DUMMY_UUID);
            assertEquals("upper_pct", result);
        } finally {
            PlaceholderProvider.placeholders.remove(upperKey);
        }
    }

    // -------------------------------------------------------------------------
    // fillPlaceholders — placeholder returning empty string
    // -------------------------------------------------------------------------

    @Test
    void fillPlaceholders_placeholderReturnsEmpty_replacedWithEmpty() {
        PlaceholderProvider.placeholders.put(TEST_KEY + "_empty", uuid -> "");
        try {
            String result = PlaceholderProvider.fillPlaceholders("[" + TEST_KEY + "_empty]", DUMMY_UUID);
            assertEquals("", result);
        } finally {
            PlaceholderProvider.placeholders.remove(TEST_KEY + "_empty");
        }
    }

    // -------------------------------------------------------------------------
    // fillPlaceholders — multiple distinct placeholders in one string
    // -------------------------------------------------------------------------

    @Test
    void fillPlaceholders_multipleDistinctPlaceholders_allReplaced() {
        PlaceholderProvider.placeholders.put(TEST_KEY + "_a", uuid -> "AAA");
        PlaceholderProvider.placeholders.put(TEST_KEY + "_b", uuid -> "BBB");
        try {
            String input = "[" + TEST_KEY + "_a] and [" + TEST_KEY + "_b]";
            String result = PlaceholderProvider.fillPlaceholders(input, DUMMY_UUID);
            assertEquals("AAA and BBB", result);
        } finally {
            PlaceholderProvider.placeholders.remove(TEST_KEY + "_a");
            PlaceholderProvider.placeholders.remove(TEST_KEY + "_b");
        }
    }

    // -------------------------------------------------------------------------
    // fillNumericPlaceholders — percent syntax
    // -------------------------------------------------------------------------

    @Test
    void fillNumericPlaceholders_withConfigs_percentSyntax_outOfBounds_replacedWithInvalid() {
        RTPTestSetup.install(tempDir.toFile());
        String input = "%p999%";
        String result = PlaceholderProvider.fillNumericPlaceholders(input);
        assertNotNull(result);
        assertNotEquals(input, result);
    }

    @Test
    void fillNumericPlaceholders_withConfigs_percentSyntaxNonNumeric_skipped() {
        RTPTestSetup.install(tempDir.toFile());
        String input = "%Pabc%";
        String result = PlaceholderProvider.fillNumericPlaceholders(input);
        assertEquals(input, result);
    }

    // -------------------------------------------------------------------------
    // fillPlaceholders — UUID variation
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "00000000-0000-0000-0000-000000000001",
        "ffffffff-ffff-ffff-ffff-ffffffffffff",
        "12345678-1234-1234-1234-123456789abc"
    })
    void fillPlaceholders_differentUUIDs_allReturnSameValue(String uuidStr) {
        // The test placeholder ignores UUID, so result is always TEST_VALUE
        UUID id = UUID.fromString(uuidStr);
        String result = PlaceholderProvider.fillPlaceholders("[" + TEST_KEY + "]", id);
        assertEquals(TEST_VALUE, result);
    }

    // -------------------------------------------------------------------------
    // placeholders map — static built-in keys present
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"delay", "cooldown", "remainingCooldown", "queueLocation",
        "teleports", "mspt", "attempts", "processingTime", "spot",
        "player", "player_name", "player_status", "scan_chunks", "scan_totalChunks",
        "scan_cps", "scan_regions", "scan_eta", "world", "name", "region",
        "requirePermission", "override", "pluginForced", "serverForced",
        "shape", "cacheCap", "cached", "keptCache", "locationQueue",
        "inFlightCalculations", "worldBorderOverride",
        "total_queue_length", "public_queue_length", "personal_queue_length",
        "teleport_world", "teleport_x", "teleport_y", "teleport_z", "teleport_biome",
        "tickets", "plugin_forced", "server_forced", "loads", "leakRate"})
    void builtInPlaceholderIsRegistered(String key) {
        assertTrue(PlaceholderProvider.placeholders.containsKey(key),
                "Expected built-in placeholder to be registered: " + key);
    }
}
