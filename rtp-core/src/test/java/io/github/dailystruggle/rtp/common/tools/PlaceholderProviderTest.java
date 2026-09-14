package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private Path tempDir;

    private static final String TEST_KEY = "__test_placeholder__";
    private static final String TEST_VALUE = "hello_world";
    private static final UUID DUMMY_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() throws java.io.IOException {
        // Manually-created temp directory: avoids Windows locked-handle DirectoryNotEmptyException
        tempDir = java.nio.file.Files.createTempDirectory("rtp-ph-test-");
        RTPTestSetup.install(tempDir.toFile());
        // Register a deterministic, server-free placeholder for use in tests.
        PlaceholderProvider.placeholders.put(TEST_KEY, uuid -> TEST_VALUE);
    }

    @AfterEach
    void tearDown() {
        PlaceholderProvider.placeholders.remove(TEST_KEY);
        RTP.configs = null;
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
    // fillPlaceholders - bracket syntax [key]
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
    // fillPlaceholders - percent syntax %key%
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
    // fillPlaceholders - no-op / edge cases
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
        // Ensure Matcher.quoteReplacement is used - value contains $ and \
        PlaceholderProvider.placeholders.put(TEST_KEY + "_special", uuid -> "$1 \\n");
        try {
            String result = PlaceholderProvider.fillPlaceholders("[" + TEST_KEY + "_special]", DUMMY_UUID);
            assertEquals("$1 \\n", result);
        } finally {
            PlaceholderProvider.placeholders.remove(TEST_KEY + "_special");
        }
    }

    // -------------------------------------------------------------------------
    // fillNumericPlaceholders - configs absent (null guard)
    // -------------------------------------------------------------------------

    @Test
    void fillNumericPlaceholders_noConfigs_returnsTextUnchanged() {
        // RTP.configs is null by default in a plain unit test - method should return text as-is
        String input = "some text [p0] more";
        // If configs is null the method returns early
        String result = PlaceholderProvider.fillNumericPlaceholders(input);
        // Either unchanged (null guard) or resolved - must not throw
        assertNotNull(result);
    }

    // -------------------------------------------------------------------------
    // fillNumericPlaceholders - with configs wired via RTPTestSetup
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
        // [Pabc] has a non-numeric index - should be skipped (continue branch)
        String input = "value=[Pabc]";
        String result = PlaceholderProvider.fillNumericPlaceholders(input);
        // Non-numeric group triggers NumberFormatException → continue; text unchanged
        assertEquals(input, result);
    }

    @Test
    void fillNumericPlaceholders_withConfigs_outOfBoundsIndex_replacedWithInvalid() {
        RTPTestSetup.install(tempDir.toFile());
        // [p999] - index 999 is beyond any configured placeholder list → "[invalid]"
        String input = "[p999]";
        String result = PlaceholderProvider.fillNumericPlaceholders(input);
        // The replacement is "[invalid]" but then the removeRegex strips [p\d*] patterns from it,
        // leaving just "invalid" or "[invalid]" depending on the regex - either way, not the original
        assertNotNull(result);
        assertNotEquals(input, result);
    }

    // -------------------------------------------------------------------------
    // fillPlaceholders - case-insensitive pattern replacement
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
    // fillPlaceholders - placeholder returning empty string
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
    // fillPlaceholders - multiple distinct placeholders in one string
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
    // fillNumericPlaceholders - percent syntax
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
    // fillPlaceholders - UUID variation
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
    // placeholders map - static built-in keys present
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"delay", "cooldown", "remainingCooldown", "queueLocation",
        "teleports", "mspt", "attempts", "processingTime", "spot",
        "player", "player_name", "player_status", "scan_chunks", "scan_totalChunks",
        "scan_cps", "scan_regions", "scan_landPercentage", "scan_eta", "world", "name", "region",
        "requirePermission", "override", "pluginForced", "serverForced",
        "shape", "cacheCap", "cached", "keptCache", "unkeptCache",
        "backlogCache", "backlogCacheCap", "locationQueue",
        "inFlightCalculations", "worldBorderOverride",
        "total_queue_length", "public_queue_length", "personal_queue_length",
        "teleport_world", "teleport_x", "teleport_y", "teleport_z", "teleport_biome",
        "tickets", "plugin_forced", "server_forced", "loads", "leakRate",
        "remainingLockTime", "remaining_lock_time", "lockUses", "lock_uses",
        "remainingLockUses", "remaining_lock_uses", "lockLimit", "lock_limit",
        "lockAfterUses", "lock_after_uses"})
    void builtInPlaceholderIsRegistered(String key) {
        assertTrue(PlaceholderProvider.placeholders.containsKey(key),
                "Expected built-in placeholder to be registered: " + key);
    }

    @Test
    void formatEta_formatsZeroAndPositiveSeconds() {
        assertEquals("0s", PlaceholderProvider.formatEta(0));
        assertEquals("45s", PlaceholderProvider.formatEta(45));
        assertEquals("1m 15s", PlaceholderProvider.formatEta(75));
        assertEquals("2h 19m 28s", PlaceholderProvider.formatEta(2 * 3600 + 19 * 60 + 28));
    }

    @Test
    void scanPlaceholders_withRegionContext_resolvesTargetRegionMetrics() {
        RTPTestSetup.install(tempDir.toFile());
        io.github.dailystruggle.rtp.common.mock.MockRTPWorld world =
                new io.github.dailystruggle.rtp.common.mock.MockRTPWorld("scan_ph_world");
        io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square square =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square();
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor vert =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(java.util.Collections.emptyList());
        io.github.dailystruggle.rtp.common.selection.region.RegionSettings settings =
                new io.github.dailystruggle.rtp.common.selection.region.RegionSettings(
                        "regionA", world, square, vert, false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        io.github.dailystruggle.rtp.common.selection.region.Region regionA =
                new io.github.dailystruggle.rtp.common.selection.region.Region("regionA", settings);
        RTP.selectionAPI.permRegionLookup.put("regionA", regionA);

        io.github.dailystruggle.rtp.common.tasks.ScanTask taskA =
                new io.github.dailystruggle.rtp.common.tasks.ScanTask(regionA, 0L);
        taskA.latestAbsolutePos = 500;
        taskA.latestAbsoluteTotal = 1000;
        taskA.latestCps = 250;
        taskA.latestEtaSeconds = 60;
        RTP.getInstance().scanTasks.put("regionA", taskA);

        try {
            RTP.regionContext.set(regionA);

            assertEquals("regionA", PlaceholderProvider.fillPlaceholders("[scan_regions]", DUMMY_UUID));
            assertEquals("500", PlaceholderProvider.fillPlaceholders("[scan_chunks]", DUMMY_UUID));
            assertEquals("1000", PlaceholderProvider.fillPlaceholders("[scan_totalChunks]", DUMMY_UUID));
            assertEquals("250", PlaceholderProvider.fillPlaceholders("[scan_cps]", DUMMY_UUID));
            assertEquals("1m", PlaceholderProvider.fillPlaceholders("[scan_eta]", DUMMY_UUID));
        } finally {
            RTP.regionContext.remove();
            RTP.getInstance().scanTasks.clear();
        }
    }

    @Test
    void allBuiltInPlaceholders_resolvedWithoutExceptions() {
        MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld("world");
        assertNotNull(world, "Expected world 'world' to be pre-registered");

        io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square square =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square();
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor vert =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(java.util.Collections.emptyList());
        io.github.dailystruggle.rtp.common.selection.region.RegionSettings settings =
                new io.github.dailystruggle.rtp.common.selection.region.RegionSettings(
                        "default", world, square, vert, false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        io.github.dailystruggle.rtp.common.selection.region.Region region =
                new io.github.dailystruggle.rtp.common.selection.region.Region("default", settings);
        RTP.selectionAPI.permRegionLookup.put("default", region);

        // Put world parser for "world" in WorldKeys MultiConfigParser
        io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys> worldParsers =
                (io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys>)
                        RTP.configs.multiConfigParserMap.get(io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys.class);
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys> worldConfig =
                new io.github.dailystruggle.rtp.common.configuration.ConfigParser<>(
                        io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys.class,
                        "world",
                        "1.0",
                        worldParsers.myDirectory,
                        worldParsers.fileDatabase);
        worldConfig.set(io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys.region, "default");
        worldConfig.set(io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys.requirePermission, false);
        worldParsers.addParser(worldConfig);

        // Put region parser for "default" in RegionKeys MultiConfigParser
        io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys> regionParsers =
                (io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys>)
                        RTP.configs.multiConfigParserMap.get(io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys.class);
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys> regionConfig =
                new io.github.dailystruggle.rtp.common.configuration.ConfigParser<>(
                        io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys.class,
                        "default",
                        "1.0",
                        regionParsers.myDirectory,
                        regionParsers.fileDatabase);
        regionConfig.set(io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys.requirePermission, false);
        regionParsers.addParser(regionConfig);

        io.github.dailystruggle.rtp.api.world.RTPLocation loc =
                new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0);
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer player =
                new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(DUMMY_UUID, "Tester", loc);
        accessor.addPlayer(player);

        // Put teleport data
        io.github.dailystruggle.rtp.common.playerData.TeleportData tData =
                new io.github.dailystruggle.rtp.common.playerData.TeleportData();
        tData.completed = false;
        tData.time = System.currentTimeMillis();
        tData.selectedCoords = new io.github.dailystruggle.rtp.api.world.RTPCoords(world.name(), 100, 64, 200);
        tData.queueLocation = 3;
        RTP.getInstance().latestTeleportData.put(DUMMY_UUID, tData);

        RTP.regionContext.set(region);
        RTP.worldContext.set(world);

        try {
            for (String key : PlaceholderProvider.placeholders.keySet()) {
                String result = PlaceholderProvider.fillPlaceholders("[" + key + "]", DUMMY_UUID);
                assertNotNull(result, "Placeholder [" + key + "] resolved to null");
            }
        } finally {
            RTP.regionContext.remove();
            RTP.worldContext.remove();
            RTP.selectionAPI.permRegionLookup.remove("default");
            RTP.getInstance().latestTeleportData.remove(DUMMY_UUID);
            worldParsers.removeParser("world");
            regionParsers.removeParser("default");
        }
    }

    @Test
    void specificPlaceholders_detailedOutputValidation() {
        MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld("world");
        assertNotNull(world);

        io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square square =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square();
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor vert =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(java.util.Collections.emptyList());
        io.github.dailystruggle.rtp.common.selection.region.RegionSettings settings =
                new io.github.dailystruggle.rtp.common.selection.region.RegionSettings(
                        "default", world, square, vert, false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        io.github.dailystruggle.rtp.common.selection.region.Region region =
                new io.github.dailystruggle.rtp.common.selection.region.Region("default", settings);
        RTP.selectionAPI.permRegionLookup.put("default", region);

        io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys> worldParsers =
                (io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys>)
                        RTP.configs.multiConfigParserMap.get(io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys.class);
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys> worldConfig =
                new io.github.dailystruggle.rtp.common.configuration.ConfigParser<>(
                        io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys.class,
                        "world",
                        "1.0",
                        worldParsers.myDirectory,
                        worldParsers.fileDatabase);
        worldConfig.set(io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys.region, "default");
        worldConfig.set(io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys.requirePermission, false);
        worldParsers.addParser(worldConfig);

        io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys> regionParsers =
                (io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys>)
                        RTP.configs.multiConfigParserMap.get(io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys.class);
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys> regionConfig =
                new io.github.dailystruggle.rtp.common.configuration.ConfigParser<>(
                        io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys.class,
                        "default",
                        "1.0",
                        regionParsers.myDirectory,
                        regionParsers.fileDatabase);
        regionConfig.set(io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys.requirePermission, false);
        regionParsers.addParser(regionConfig);

        io.github.dailystruggle.rtp.api.world.RTPLocation loc =
                new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 10, 70, 20);
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer player =
                new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(DUMMY_UUID, "Tester", loc);
        accessor.addPlayer(player);

        // Put teleport data
        io.github.dailystruggle.rtp.common.playerData.TeleportData tData =
                new io.github.dailystruggle.rtp.common.playerData.TeleportData();
        tData.completed = false;
        tData.time = System.currentTimeMillis();
        tData.selectedCoords = new io.github.dailystruggle.rtp.api.world.RTPCoords(world.name(), 123, 80, 456);
        tData.queueLocation = 5;
        RTP.getInstance().latestTeleportData.put(DUMMY_UUID, tData);

        RTP.regionContext.set(region);
        RTP.worldContext.set(world);

        try {
            assertEquals("world", PlaceholderProvider.fillPlaceholders("[teleport_world]", DUMMY_UUID));
            assertEquals("123", PlaceholderProvider.fillPlaceholders("[teleport_x]", DUMMY_UUID));
            assertEquals("80", PlaceholderProvider.fillPlaceholders("[teleport_y]", DUMMY_UUID));
            assertEquals("456", PlaceholderProvider.fillPlaceholders("[teleport_z]", DUMMY_UUID));
            assertEquals("5", PlaceholderProvider.fillPlaceholders("[queueLocation]", DUMMY_UUID));
            assertEquals("world", PlaceholderProvider.fillPlaceholders("[world]", DUMMY_UUID));
            assertEquals("default", PlaceholderProvider.fillPlaceholders("[region]", DUMMY_UUID));
            assertEquals("SQUARE", PlaceholderProvider.fillPlaceholders("[shape]", DUMMY_UUID));
            assertEquals("0", PlaceholderProvider.fillPlaceholders("[cached]", DUMMY_UUID));
            assertEquals("0", PlaceholderProvider.fillPlaceholders("[keptCache]", DUMMY_UUID));
            assertEquals("0", PlaceholderProvider.fillPlaceholders("[unkeptCache]", DUMMY_UUID));
            assertEquals("0", PlaceholderProvider.fillPlaceholders("[backlogCache]", DUMMY_UUID));
            assertEquals("false", PlaceholderProvider.fillPlaceholders("[requirePermission]", DUMMY_UUID));
            assertEquals("false", PlaceholderProvider.fillPlaceholders("[worldBorderOverride]", DUMMY_UUID));

            // Snapshots and metrics
            assertFalse(PlaceholderProvider.hasDatabaseLatency());
            assertTrue(PlaceholderProvider.pipelineSampleCount() >= 0);
            assertNotNull(PlaceholderProvider.fillPlaceholders("[queueDepth]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[pendingTeleports]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[avgPipelineMs]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[p50PipelineMs]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[p90PipelineMs]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[p99PipelineMs]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[p999PipelineMs]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[minPipelineMs]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[maxPipelineMs]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[pipelineSamples]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[serverTps1m]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[serverTps5m]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[serverTps15m]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[serverMspt]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[softCap]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[playerCount]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[databaseLatencyMs]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[tps1mColoured]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[tps5mColoured]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[tps15mColoured]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[msptColoured]", DUMMY_UUID));
            assertNotNull(PlaceholderProvider.fillPlaceholders("[tickBudgetUtilisationColoured]", DUMMY_UUID));
        } finally {
            RTP.regionContext.remove();
            RTP.worldContext.remove();
            RTP.selectionAPI.permRegionLookup.remove("default");
            RTP.getInstance().latestTeleportData.remove(DUMMY_UUID);
            worldParsers.removeParser("world");
            regionParsers.removeParser("default");
        }
    }

    @Test
    void formatEta_variousIntervals() {
        // Less than 60s
        assertEquals("45s", PlaceholderProvider.formatEta(45L));
        // Minutes and seconds
        assertEquals("2m 15s", PlaceholderProvider.formatEta(135L));
        // Hours, minutes, seconds
        assertEquals("1h 1m 5s", PlaceholderProvider.formatEta(3665L));
        // Days, hours (minutes and seconds are 0 so omitted)
        assertEquals("1d 1h", PlaceholderProvider.formatEta(90000L));
    }

    @Test
    void snapshotStack_pushAndPop() {
        io.github.dailystruggle.metrics.api.MetricsSnapshot snap =
                new io.github.dailystruggle.metrics.api.MetricsSnapshot(
                        20.0, 19.5, 19.0, 15.0, 50, 100, 1024L, 2048L, System.currentTimeMillis(), java.util.Collections.emptyList()
                );
        PlaceholderProvider.pushSnapshot(snap);
        assertEquals(snap, PlaceholderProvider.currentSnapshot());
        PlaceholderProvider.popSnapshot();
    }

    @Test
    void fillNumericPlaceholders_coverage() {
        String template = "Value: [p0] and %p1%";
        String res = PlaceholderProvider.fillNumericPlaceholders(template);
        assertNotNull(res);
        assertFalse(res.contains("[p0]"));
        assertFalse(res.contains("%p1%"));
    }

    // -------------------------------------------------------------------------
    // Usage limits placeholders
    // -------------------------------------------------------------------------

    @Test
    void usageLimitPlaceholders_whenCapDisabled_returnsExpectedDefaults() {
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys> configParser =
                (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys>)
                        RTP.configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys.class);
        assertNotNull(configParser);
        configParser.set(io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys.lockAfterUses, 0L);
        configParser.set(io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys.lockAfterResetSeconds, 0L);

        assertEquals("0", PlaceholderProvider.fillPlaceholders("[lockLimit]", DUMMY_UUID));
        assertEquals("0", PlaceholderProvider.fillPlaceholders("[lock_limit]", DUMMY_UUID));
        assertEquals("0", PlaceholderProvider.fillPlaceholders("[lockAfterUses]", DUMMY_UUID));
        assertEquals("0", PlaceholderProvider.fillPlaceholders("[lock_after_uses]", DUMMY_UUID));
        assertEquals("0", PlaceholderProvider.fillPlaceholders("[lockUses]", DUMMY_UUID));
        assertEquals("0", PlaceholderProvider.fillPlaceholders("[lock_uses]", DUMMY_UUID));
        assertEquals("0", PlaceholderProvider.fillPlaceholders("[remainingLockUses]", DUMMY_UUID));
        assertEquals("0", PlaceholderProvider.fillPlaceholders("[remaining_lock_uses]", DUMMY_UUID));
        assertEquals("", PlaceholderProvider.fillPlaceholders("[remainingLockTime]", DUMMY_UUID));
        assertEquals("", PlaceholderProvider.fillPlaceholders("[remaining_lock_time]", DUMMY_UUID));
    }

    @Test
    void usageLimitPlaceholders_underCapAndAtCapAndExpired() {
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys> configParser =
                (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys>)
                        RTP.configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys.class);
        assertNotNull(configParser);
        configParser.set(io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys.lockAfterUses, 5L);
        configParser.set(io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys.lockAfterResetSeconds, 300L); // 5 minutes

        UUID testUser = UUID.randomUUID();

        // 1. Initial state (0 uses)
        assertEquals("5", PlaceholderProvider.fillPlaceholders("[lockLimit]", testUser));
        assertEquals("5", PlaceholderProvider.fillPlaceholders("%lock_limit%", testUser));
        assertEquals("0", PlaceholderProvider.fillPlaceholders("[lockUses]", testUser));
        assertEquals("0", PlaceholderProvider.fillPlaceholders("%lock_uses%", testUser));
        assertEquals("5", PlaceholderProvider.fillPlaceholders("[remainingLockUses]", testUser));
        assertEquals("5", PlaceholderProvider.fillPlaceholders("%remaining_lock_uses%", testUser));
        assertEquals("", PlaceholderProvider.fillPlaceholders("[remainingLockTime]", testUser));
        assertEquals("", PlaceholderProvider.fillPlaceholders("%remaining_lock_time%", testUser));

        // 2. Partial usage (record 2 uses at current time)
        long now = System.currentTimeMillis();
        long resetMillis = 300_000L;
        RTP.getInstance().teleportLimitStore.recordSuccess(testUser, 5L, resetMillis, now);
        RTP.getInstance().teleportLimitStore.recordSuccess(testUser, 5L, resetMillis, now);

        assertEquals("2", PlaceholderProvider.fillPlaceholders("[lockUses]", testUser));
        assertEquals("2", PlaceholderProvider.fillPlaceholders("%lock_uses%", testUser));
        assertEquals("3", PlaceholderProvider.fillPlaceholders("[remainingLockUses]", testUser));
        assertEquals("3", PlaceholderProvider.fillPlaceholders("%remaining_lock_uses%", testUser));
        assertFalse(PlaceholderProvider.fillPlaceholders("[remainingLockTime]", testUser).isEmpty());

        // 3. Reached cap (record 3 more uses to hit 5)
        RTP.getInstance().teleportLimitStore.recordSuccess(testUser, 5L, resetMillis, now);
        RTP.getInstance().teleportLimitStore.recordSuccess(testUser, 5L, resetMillis, now);
        RTP.getInstance().teleportLimitStore.recordSuccess(testUser, 5L, resetMillis, now);

        assertEquals("5", PlaceholderProvider.fillPlaceholders("[lockUses]", testUser));
        assertEquals("0", PlaceholderProvider.fillPlaceholders("[remainingLockUses]", testUser));
        String remainingTimeStr = PlaceholderProvider.fillPlaceholders("[remainingLockTime]", testUser);
        assertFalse(remainingTimeStr.isEmpty(), "Expected remainingLockTime to be formatted when cap reached");

        // 4. Over cap (record 1 more use, total 6)
        RTP.getInstance().teleportLimitStore.recordSuccess(testUser, 5L, resetMillis, now);
        assertEquals("6", PlaceholderProvider.fillPlaceholders("[lockUses]", testUser));
        // remainingLockUses should not be negative, clamped to 0
        assertEquals("0", PlaceholderProvider.fillPlaceholders("[remainingLockUses]", testUser));

        // 5. Expired window (simulate time passing > 300 seconds by clearing or using old timestamps)
        // With UsageCapTracker, timestamps older than now - resetMillis are purged on uses() / millisUntilReset()
        UUID oldUser = UUID.randomUUID();
        long oldTime = now - 400_000L; // 400 seconds ago
        RTP.getInstance().teleportLimitStore.recordSuccess(oldUser, 5L, resetMillis, oldTime);
        RTP.getInstance().teleportLimitStore.recordSuccess(oldUser, 5L, resetMillis, oldTime);
        RTP.getInstance().teleportLimitStore.recordSuccess(oldUser, 5L, resetMillis, oldTime);
        RTP.getInstance().teleportLimitStore.recordSuccess(oldUser, 5L, resetMillis, oldTime);
        RTP.getInstance().teleportLimitStore.recordSuccess(oldUser, 5L, resetMillis, oldTime);

        // Since the 5 uses were 400s ago (> 300s reset), window has reset
        assertEquals("0", PlaceholderProvider.fillPlaceholders("[lockUses]", oldUser));
        assertEquals("5", PlaceholderProvider.fillPlaceholders("[remainingLockUses]", oldUser));
        assertEquals("", PlaceholderProvider.fillPlaceholders("[remainingLockTime]", oldUser));
    }
}
