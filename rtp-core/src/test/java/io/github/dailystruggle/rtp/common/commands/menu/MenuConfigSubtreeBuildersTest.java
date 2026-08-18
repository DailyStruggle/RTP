package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Surface tests for {@code CommandTreeMenuBuilder.buildConfigSelector} and
 * {@code buildConfigFile}.
 *
 * <p>These are the curated config subtree's first two pages
 * (selector → per-file). The per-key page (page 3) reuses
 * {@code buildParamPicker} verbatim.
 */
@DisplayName("Config subtree builders test")
class MenuConfigSubtreeBuildersTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setupRTP() {
        RTPTestSetup.install(tempDir.toFile());
    }

    // ------------------------------------------------------------------------
    // buildConfigSelector
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("buildConfigSelector: Back + Search + header + one OpenConfigFile row per file")
    void buildConfigSelector_layout() {
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();

        List<String> files = List.of("performance.yml", "safety.yml", "config.yml");
        MenuModel model = builder.buildConfigSelector(UUID.randomUUID(), files);

        assertEquals(1, model.pages().size(), "selector is single-page");
        List<MenuLine> lines = model.pages().get(0).lines();
        // 1 Back + 1 Search + 1 header + 3 multiconfig rows (regions, worlds, effects) + 3 file rows = 9
        assertEquals(9, lines.size(),
                "selector layout: back + search + header + regions + worlds + effects + N file rows");

        // Row 0 → Back via OpenMenu(empty path) (root /rtp menu page).
        MenuFragment back = lines.get(0).fragments().get(0);
        assertInstanceOf(MenuAction.OpenMenu.class, back.action(),
                "first row must be Back navigation");
        assertEquals(0, ((MenuAction.OpenMenu) back.action()).path().length,
                "Back must target the root menu page (empty path)");

        // Row 1 → Search via OpenConfigSearchPrompt.
        assertInstanceOf(MenuAction.OpenConfigSearchPrompt.class,
                lines.get(1).fragments().get(0).action(),
                "second row must be the search-configs entry point");

        // Row 2 → header (no action).
        assertEquals(null, lines.get(2).fragments().get(0).action(),
                "header row must be non-clickable");

        // Rows 3..5 → Regions / Worlds / Effects multiconfig submenu entry points.
        MenuAction regionsAction = lines.get(3).fragments().get(0).action();
        assertInstanceOf(MenuAction.OpenMultiConfigSelector.class, regionsAction,
                "row 3 must be the Regions multiconfig submenu entry");
        assertEquals("regions",
                ((MenuAction.OpenMultiConfigSelector) regionsAction).parserKind());
        MenuAction worldsAction = lines.get(4).fragments().get(0).action();
        assertInstanceOf(MenuAction.OpenMultiConfigSelector.class, worldsAction,
                "row 4 must be the Worlds multiconfig submenu entry");
        assertEquals("worlds",
                ((MenuAction.OpenMultiConfigSelector) worldsAction).parserKind());
        MenuAction effectsAction = lines.get(5).fragments().get(0).action();
        assertInstanceOf(MenuAction.OpenMultiConfigSelector.class, effectsAction,
                "row 5 must be the Effects multiconfig submenu entry");
        assertEquals("effects",
                ((MenuAction.OpenMultiConfigSelector) effectsAction).parserKind());

        // Rows 6..8 → one OpenConfigFile per file, in encounter order.
        for (int i = 0; i < files.size(); i++) {
            MenuFragment row = lines.get(6 + i).fragments().get(0);
            assertInstanceOf(MenuAction.OpenConfigFile.class, row.action(),
                    "file row " + i + " must be OpenConfigFile");
            assertEquals(files.get(i),
                    ((MenuAction.OpenConfigFile) row.action()).fileName(),
                    "file row " + i + " preserves the file name");
        }
    }

    @Test
    @DisplayName("buildConfigSelector: empty file list still yields a usable page (back + header only)")
    void buildConfigSelector_emptyFileList() {
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();

        MenuModel model = builder.buildConfigSelector(UUID.randomUUID(), List.of());

        List<MenuLine> lines = model.pages().get(0).lines();
        // back + search + header + regions + worlds + effects = 6
        assertEquals(6, lines.size(),
                "empty file list still produces back + search + header + regions/worlds/effects rows");
        assertInstanceOf(MenuAction.OpenMenu.class,
                lines.get(0).fragments().get(0).action());
        assertInstanceOf(MenuAction.OpenConfigSearchPrompt.class,
                lines.get(1).fragments().get(0).action());
    }

    @Test
    @DisplayName("buildConfigSelector: null/empty file-name entries are skipped (defensive)")
    void buildConfigSelector_skipsBlankEntries() {
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();

        // The list contains a null and an empty string; both must be filtered
        // out by the builder so we never try to construct
        // OpenConfigFile("") (which would throw IAE per the rtp-api contract).
        java.util.List<String> mixed = new java.util.ArrayList<>();
        mixed.add("real.yml");
        mixed.add(null);
        mixed.add("");
        mixed.add("other.yml");

        MenuModel model = builder.buildConfigSelector(UUID.randomUUID(), mixed);
        List<MenuLine> lines = model.pages().get(0).lines();
        // back + search + header + regions + worlds + effects + 2 valid file rows = 8
        assertEquals(8, lines.size());
        assertEquals("real.yml",
                ((MenuAction.OpenConfigFile) lines.get(6).fragments().get(0).action()).fileName());
        assertEquals("other.yml",
                ((MenuAction.OpenConfigFile) lines.get(7).fragments().get(0).action()).fileName());
    }

    @Test
    @DisplayName("buildConfigSelector: null inputs rejected")
    void buildConfigSelector_nullsRejected() {
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();
        assertThrows(NullPointerException.class,
                () -> builder.buildConfigSelector(null, List.of()));
        assertThrows(NullPointerException.class,
                () -> builder.buildConfigSelector(UUID.randomUUID(), null));
    }

    // ------------------------------------------------------------------------
    // buildConfigSelector - recursive directory walk (ADR-071 rule 7)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("buildConfigSelector(root): child-dir folder nodes recurse via OpenConfigSelector(subDir)")
    void buildConfigSelector_rootListsChildDirs() {
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();

        MenuModel model = builder.buildConfigSelector(UUID.randomUUID(), "",
                List.of("advanced", "messages"), List.of("config.yml", "safety.yml"));
        List<MenuLine> lines = model.pages().get(0).lines();
        // back + search + header + regions + worlds + effects + 2 dir nodes + 2 file rows = 10
        assertEquals(10, lines.size());

        // Rows 6..7 → folder nodes that recurse into the named sub-directory.
        MenuAction advAction = lines.get(6).fragments().get(0).action();
        assertInstanceOf(MenuAction.OpenConfigSelector.class, advAction,
                "child-dir row must recurse via OpenConfigSelector");
        assertEquals("advanced",
                ((MenuAction.OpenConfigSelector) advAction).subDir());
        MenuAction msgAction = lines.get(7).fragments().get(0).action();
        assertEquals("messages",
                ((MenuAction.OpenConfigSelector) msgAction).subDir());

        // Rows 8..9 → root files.
        assertEquals("config.yml",
                ((MenuAction.OpenConfigFile) lines.get(8).fragments().get(0).action()).fileName());
        assertEquals("safety.yml",
                ((MenuAction.OpenConfigFile) lines.get(9).fragments().get(0).action()).fileName());
    }

    @Test
    @DisplayName("buildConfigSelector(subDir): Back returns to parent; no search/multiconfig rows; files open")
    void buildConfigSelector_nestedDirectory() {
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();

        MenuModel model = builder.buildConfigSelector(UUID.randomUUID(), "advanced",
                List.of(), List.of("performance.yml", "logging.yml"));
        List<MenuLine> lines = model.pages().get(0).lines();
        // back + header + 2 file rows = 4 (no search, no regions/worlds/effects)
        assertEquals(4, lines.size());

        // Row 0 → Back to the parent directory (root) via OpenConfigSelector("").
        MenuAction back = lines.get(0).fragments().get(0).action();
        assertInstanceOf(MenuAction.OpenConfigSelector.class, back,
                "nested page Back must return to the parent directory selector");
        assertEquals("", ((MenuAction.OpenConfigSelector) back).subDir());

        // Row 1 → non-clickable header naming the directory.
        assertEquals(null, lines.get(1).fragments().get(0).action());

        // Rows 2..3 → the directory's files.
        assertEquals("performance.yml",
                ((MenuAction.OpenConfigFile) lines.get(2).fragments().get(0).action()).fileName());
        assertEquals("logging.yml",
                ((MenuAction.OpenConfigFile) lines.get(3).fragments().get(0).action()).fileName());
    }

    @Test
    @DisplayName("buildConfigSelector(deep subDir): Back returns up one level, not to root")
    void buildConfigSelector_deepDirectoryBackToParent() {
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();

        MenuModel model = builder.buildConfigSelector(UUID.randomUUID(), "advanced/sub",
                List.of(), List.of("x.yml"));
        MenuAction back = model.pages().get(0).lines().get(0).fragments().get(0).action();
        assertEquals("advanced",
                ((MenuAction.OpenConfigSelector) back).subDir(),
                "Back from a two-level directory must go up exactly one level");
    }

    // ------------------------------------------------------------------------
    // buildConfigFile
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("buildConfigFile: Back→OpenConfigSelector + header + one OpenConfigKey row per loaded key")
    void buildConfigFile_layout() {
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();

        @SuppressWarnings("unchecked")
        ConfigParser<PerformanceKeys> parser =
                (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        assertNotNull(parser, "RTPTestSetup must wire up the performance parser");

        // The menu surfaces only keys actually loaded from disk (per the
        // lite-parity rule). Seed the full enum here so the layout assertions
        // below are about menu shape, not config IO. A separate test
        // ({@code buildConfigFile_omitsKeysAbsentFromLoadedYaml}) covers the
        // sparse case.
        java.util.EnumMap<PerformanceKeys, Object> full =
                new java.util.EnumMap<>(PerformanceKeys.class);
        for (PerformanceKeys k : PerformanceKeys.values()) {
            full.put(k, "seed");
        }
        parser.setData(full);

        MenuModel model = builder.buildConfigFile(UUID.randomUUID(), "performance.yml", parser);
        assertTrue(model.pages().size() >= 1, "must produce at least one page");

        // Every page must lead with Back→OpenConfigSelector and a non-clickable
        // header (pagination guarantee for the 32767-char Paper book limit).
        for (int p = 0; p < model.pages().size(); p++) {
            List<MenuLine> ls = model.pages().get(p).lines();
            assertInstanceOf(MenuAction.OpenConfigSelector.class,
                    ls.get(0).fragments().get(0).action(),
                    "page " + p + " row 0 must be Back→config selector");
            assertEquals(null, ls.get(1).fragments().get(0).action(),
                    "page " + p + " row 1 must be non-clickable header");
        }

        // Aggregate OpenConfigKey rows across all pages, skipping back+header
        // on each page. Order matches PerformanceKeys.values() declaration order.
        List<MenuAction.OpenConfigKey> keyRows = new java.util.ArrayList<>();
        List<MenuFragment> keyFragments = new java.util.ArrayList<>();
        for (MenuPage page : model.pages()) {
            List<MenuLine> ls = page.lines();
            for (int i = 2; i < ls.size(); i++) {
                MenuFragment f = ls.get(i).fragments().get(0);
                assertInstanceOf(MenuAction.OpenConfigKey.class, f.action(),
                        "key row must be OpenConfigKey");
                keyRows.add((MenuAction.OpenConfigKey) f.action());
                keyFragments.add(f);
            }
        }
        // `version` is a schema marker, intentionally filtered out of the
        // editable key list (2026-05-22 polish). Build the expected list by
        // dropping it.
        java.util.List<PerformanceKeys> expectedList = new java.util.ArrayList<>();
        for (PerformanceKeys k : PerformanceKeys.values()) {
            if ("VERSION".equalsIgnoreCase(k.name())) continue;
            expectedList.add(k);
        }
        PerformanceKeys[] expected = expectedList.toArray(new PerformanceKeys[0]);
        assertEquals(expected.length, keyRows.size(),
                "aggregate: one OpenConfigKey per editable enum constant (version excluded)");
        for (int i = 0; i < expected.length; i++) {
            assertEquals("performance.yml", keyRows.get(i).fileName());
            assertEquals(expected[i].name(), keyRows.get(i).paramName(),
                    "key row " + i + " preserves the enum-key name");
            assertTrue(keyFragments.get(i).text().contains(expected[i].name()),
                    "row label must include the key name; got: " + keyFragments.get(i).text());
        }
    }

    @Test
    @DisplayName("buildConfigFile: empty-enum parser degrades to a hint row (v3.7.4)")
    void buildConfigFile_emptyEnumHint() {
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();

        // Stand up a ConfigParser over an empty enum on the fly. Re-using the
        // tempDir wired by RTPTestSetup keeps file IO contained.
        ConfigParser<EmptyKeys> empty = new ConfigParser<>(
                EmptyKeys.class, "empty.yml", "1.0",
                tempDir.toFile(),
                tempDir.toFile(),
                RTP.configs.fileDatabase);

        MenuModel model = builder.buildConfigFile(UUID.randomUUID(), "empty.yml", empty);
        List<MenuLine> lines = model.pages().get(0).lines();
        // back + header + hint row
        assertEquals(3, lines.size());
        // The hint row is non-clickable.
        assertEquals(null, lines.get(2).fragments().get(0).action(),
                "empty-file hint must be non-clickable");
        // And there are zero OpenConfigKey actions on the page (v3.7.4).
        boolean anyKeyRow = false;
        for (MenuLine line : lines) {
            for (MenuFragment f : line.fragments()) {
                if (f.action() instanceof MenuAction.OpenConfigKey) {
                    anyKeyRow = true;
                }
            }
        }
        assertFalse(anyKeyRow, "empty-enum parser must not emit OpenConfigKey rows");
    }

    @Test
    @DisplayName("buildConfigFile: null/empty inputs rejected")
    void buildConfigFile_nullsRejected() {
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();

        @SuppressWarnings("unchecked")
        ConfigParser<PerformanceKeys> parser =
                (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);

        assertThrows(NullPointerException.class,
                () -> builder.buildConfigFile(null, "performance.yml", parser));
        assertThrows(NullPointerException.class,
                () -> builder.buildConfigFile(UUID.randomUUID(), null, parser));
        assertThrows(NullPointerException.class,
                () -> builder.buildConfigFile(UUID.randomUUID(), "performance.yml", null));
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildConfigFile(UUID.randomUUID(), "", parser));
    }

    @Test
    @DisplayName("buildConfigFile: each key row carries its YAML block comment as hover text")
    void buildConfigFile_hoverFromYamlComment() throws Exception {
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();

        // Stand up a self-contained parser over an on-disk YAML carrying a
        // known block comment, so the hover assertion is deterministic and
        // independent of the shared test fixture's file IO. The version stamp
        // matches the parser's so no update()/re-extract pass runs (which
        // could overlay the shipped jar comments).
        Path file = tempDir.resolve("performance.yml");
        java.nio.file.Files.writeString(file,
                "version: 1.0\n"
                        + "# how many tries before giving up\n"
                        + "maxAttempts: 5\n"
                        + "period: 2\n");
        io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase db =
                new io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase(tempDir.toFile());
        ConfigParser<PerformanceKeys> parser = new ConfigParser<>(
                PerformanceKeys.class, "performance.yml", "1.0",
                tempDir.toFile(), tempDir.toFile(), db);

        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection root =
                parser.getYamlRoot();
        assertNotNull(root, "parser must have a loaded YAML root");
        assertNotNull(root.getComment("maxAttempts"),
                "fixture YAML must carry the maxAttempts comment");

        MenuModel model = builder.buildConfigFile(
                UUID.randomUUID(), "performance.yml", parser);

        String hover = null;
        for (MenuPage page : model.pages()) {
            List<MenuLine> ls = page.lines();
            for (int i = 2; i < ls.size(); i++) {
                MenuFragment f = ls.get(i).fragments().get(0);
                if (f.action() instanceof MenuAction.OpenConfigKey ock
                        && "maxAttempts".equals(ock.paramName())) {
                    hover = f.hover();
                }
            }
        }
        assertNotNull(hover, "maxAttempts row must carry hover text from its comment");
        assertEquals("how many tries before giving up", hover,
                "hover must be the comment with the leading '#' marker stripped");
    }

    /** Empty-enum fixture for the v3.7.4 empty-file degradation test. */
    private enum EmptyKeys { }

    // ------------------------------------------------------------------------
    // buildConfigFile: file-driven visibility (lite-style sparse YAML)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("buildConfigFile: keys absent from the YAML are not surfaced in the menu (lite parity)")
    void buildConfigFile_omitsKeysAbsentFromLoadedYaml() {
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();

        @SuppressWarnings("unchecked")
        ConfigParser<PerformanceKeys> parser =
                (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        assertNotNull(parser, "RTPTestSetup must wire up the performance parser");

        // Simulate a packaging variant (lite jar / hand-trimmed admin YAML)
        // that deliberately omits a single key from the on-disk file. The
        // in-memory parser state is the ground truth that the menu must
        // mirror - iterating the raw `PerformanceKeys` enum constants would
        // re-expose the omitted key purely because it exists in Java.
        PerformanceKeys[] all = PerformanceKeys.values();
        // Need at least two keys so that "omit one" is meaningful.
        org.junit.jupiter.api.Assumptions.assumeTrue(all.length >= 2,
                "PerformanceKeys must have >= 2 constants for this test");
        // Pick an omitted key that is *not* the schema-marker `version` -
        // the builder filters `version` unconditionally (2026-05-22 polish),
        // so omitting it from the file makes "only version is missing" a
        // no-op from the menu's perspective.
        PerformanceKeys omitted = null;
        for (PerformanceKeys k : all) {
            if (!"VERSION".equalsIgnoreCase(k.name())) { omitted = k; break; }
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(omitted != null,
                "PerformanceKeys must have at least one non-version key");
        java.util.EnumMap<PerformanceKeys, Object> sparse =
                new java.util.EnumMap<>(PerformanceKeys.class);
        for (PerformanceKeys k : all) {
            if (k == omitted) continue;
            sparse.put(k, "seed");
        }
        parser.setData(sparse);

        try {
            MenuModel model = builder.buildConfigFile(
                    UUID.randomUUID(), "performance.yml", parser);

            int rowsForOmitted = 0;
            int totalKeyRows = 0;
            for (MenuPage page : model.pages()) {
                List<MenuLine> ls = page.lines();
                for (int i = 2; i < ls.size(); i++) {
                    MenuFragment f = ls.get(i).fragments().get(0);
                    if (f.action() instanceof MenuAction.OpenConfigKey ock) {
                        totalKeyRows++;
                        if (omitted.name().equals(ock.paramName())) {
                            rowsForOmitted++;
                        }
                    }
                }
            }
            assertEquals(0, rowsForOmitted,
                    "omitted key '" + omitted.name() + "' must not appear in the menu");
            // The menu also filters the `version` schema marker; exclude it
            // from the expected loaded-key count.
            int expectedRows = 0;
            for (PerformanceKeys k : sparse.keySet()) {
                if (!"VERSION".equalsIgnoreCase(k.name())) expectedRows++;
            }
            assertEquals(expectedRows, totalKeyRows,
                    "menu must show exactly one row per loaded editable key (version excluded), not per enum constant");
        } finally {
            // Leave the wired parser in a clean state for any other tests
            // sharing this RTP.configs install in the same class.
            RTP.configs.reloadConfigs();
        }
    }
}
