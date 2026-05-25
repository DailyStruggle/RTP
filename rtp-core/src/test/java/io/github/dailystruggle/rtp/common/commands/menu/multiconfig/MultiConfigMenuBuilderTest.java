package io.github.dailystruggle.rtp.common.commands.menu.multiconfig;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 6 (CHECKLIST-multiconfig-menu.md) — surface tests for
 * {@link MultiConfigMenuBuilder}.
 *
 * <p>Covers the three public methods (selector / entry / confirm-remove),
 * the {@link MultiConfigRemovalGuards} integration (locked rows render
 * gray + non-clickable with the registered hover reason), the
 * remove-mode toggle row flipping entry-row actions, and the
 * {@link MultiConfigMenuBuilder#nextDefaultName} synthesiser.
 *
 * <p>Tests exercise the real {@code RTP.configs.multiConfigParserMap}'s
 * regions parser (provisioned by {@link RTPTestSetup}); the bundled
 * {@code default.yml} resource ensures the parser has at least the
 * {@code "default"} entry.
 */
@DisplayName("CHECKLIST-multiconfig-menu step 6: MultiConfigMenuBuilder")
final class MultiConfigMenuBuilderTest {

    private Path tempDir;
    private MultiConfigParser<RegionKeys> regions;

    @BeforeEach
    void setUp() throws java.io.IOException {
        // Manually-created temp directory: JUnit's @TempDir tries to delete
        // the tree in tear-down, but the ConfigParser opens YAML file
        // handles that Windows keeps locked after the test method returns,
        // producing DirectoryNotEmptyException noise that hides real
        // failures. Skip the JUnit deletion and leave the OS to reap the
        // junit-* temp tree on the next reboot.
        tempDir = Files.createTempDirectory("rtp-multiconfig-menu-test-");
        RTPTestSetup.install(tempDir.toFile());
        @SuppressWarnings("unchecked")
        MultiConfigParser<RegionKeys> r = (MultiConfigParser<RegionKeys>)
                RTP.configs.multiConfigParserMap.get(RegionKeys.class);
        assertNotNull(r, "regions MultiConfigParser must be provisioned by RTPTestSetup");
        this.regions = r;
        // The MultiConfigParser populates its entry set from on-disk files,
        // not from packaged resources, so a fresh @TempDir starts empty.
        // Seed the canonical "default" entry by constructing a typed
        // ConfigParser and registering it via the typed addParser overload.
        // The String overload no-ops on an empty factory (it tries to clone
        // DEFAULT.YML, finds nothing, returns silently).
        seed("default");
        // Always start with a clean guard registry so guard expectations are
        // hermetic regardless of test ordering.
        MultiConfigRemovalGuards.clear();
    }

    private void seed(String name) {
        ConfigParser<RegionKeys> p = new ConfigParser<>(
                RegionKeys.class,
                name,
                "1.0",
                regions.myDirectory,
                regions.fileDatabase);
        regions.addParser(p);
    }

    @AfterEach
    void tearDown() {
        MultiConfigRemovalGuards.clear();
    }

    // ------------------------------------------------------------------------
    // buildSelector
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("buildSelector")
    final class BuildSelector {

        @Test
        @DisplayName("renders Back + toggle + Add + one row per entry (alphabetical)")
        void layout() {
            // Add a couple of extra entries so we can assert sort order.
            seed("zeta");
            seed("alpha");
            MultiConfigMenuBuilder builder = new MultiConfigMenuBuilder();

            MenuModel model = builder.buildSelector(
                    "regions", regions, /*removeMode*/ false, UUID.randomUUID());

            assertEquals(1, model.pages().size(), "selector is single-page");

            // Expect at least: title, hint, blank, back, toggle, add, blank,
            // and N entry rows (with default + alpha + zeta = >=3).
            assertTrue(findFirst(model, MenuAction.OpenAdminPanel.class) != null,
                    "Back row must dispatch OpenAdminPanel");

            // Toggle in off-state dispatches OpenMultiConfigSelector with the
            // "!toggle:" sentinel prefix so the dispatcher flips remove-mode
            // for this viewer before re-rendering the selector. Without the
            // prefix the dispatcher would just re-open the selector with the
            // current (off) state - exactly the regression this test guards.
            MenuAction.OpenMultiConfigSelector toggle = findFirstByType(
                    model, MenuAction.OpenMultiConfigSelector.class);
            assertNotNull(toggle, "toggle row must mint OpenMultiConfigSelector");
            assertEquals("!toggle:regions", toggle.parserKind());

            // Add row mints PromptAnvilInput so the admin can type a custom
            // name (the synthesized default<N> is just the prefill). On
            // confirm the anvil session submits
            //   /rtp menu multiaddkind=regions multiadd=<typedName>
            // (lowercase per commands-api's TreeCommand parser, which
            //  lowercases param-name tokens before paramLookup)
            // which the dispatcher routes to MultiConfigMutate(ADD).
            MenuAction.PromptAnvilInput add = findFirstByType(
                    model, MenuAction.PromptAnvilInput.class);
            assertNotNull(add, "Add row must mint PromptAnvilInput");
            assertEquals("multiadd", add.paramName(),
                    "Add row paramName must be 'multiadd' (matches MenuRedeemSubcommand.PARAM_MULTIADD)");
            assertTrue(add.prefill().startsWith("default"),
                    "Add row prefill must start with 'default': " + add.prefill());
            assertEquals(1, add.parentPath().length,
                    "Add row parentPath must carry exactly the parserKind segment");
            assertEquals("multiaddkind=regions", add.parentPath()[0],
                    "Add row parentPath segment must encode the parserKind as name=value");
            // No MultiConfigMutate(ADD) row anymore: ADD only happens after
            // the anvil confirm. Guard against accidental regression.
            assertNull(findMutate(model, MenuAction.MultiConfigMutate.Op.ADD),
                    "off-mode selector must not pre-mint a MultiConfigMutate(ADD) row");

            // Entry rows dispatch OpenMultiConfigEntry; assert alphabetical
            // order by walking the fragments and collecting entry names.
            List<String> entryRowOrder = collectEntryRowOrder(model);
            // Existing entries must contain at least default/alpha/zeta.
            assertTrue(entryRowOrder.contains("default"));
            assertTrue(entryRowOrder.contains("alpha"));
            assertTrue(entryRowOrder.contains("zeta"));
            assertSortedCaseInsensitive(entryRowOrder);
        }

        @Test
        @DisplayName("locked entries render gray + non-clickable in remove-mode (with guard reason)")
        void lockedRowInRemoveMode_renderedGrayAndNonClickable() {
            MultiConfigRemovalGuards.register("regions", new MultiConfigRemovalGuard() {
                @Override public boolean isLocked(String entryName) {
                    return "default".equalsIgnoreCase(entryName);
                }
                @Override public String reason(String entryName) {
                    return "default region cannot be removed";
                }
            });
            MultiConfigMenuBuilder builder = new MultiConfigMenuBuilder();

            MenuModel model = builder.buildSelector(
                    "regions", regions, /*removeMode*/ true, UUID.randomUUID());

            // The locked-row label is "&8<entry> (locked)" - match the
            // " (locked)" suffix to disambiguate from the title/hover noise
            // that also happens to contain the word "default".
            MenuLine defaultRow = findRowContaining(model, "default (locked)");
            assertNotNull(defaultRow, "locked default row must be present");
            MenuFragment frag = defaultRow.fragments().get(0);
            assertNull(frag.action(),
                    "locked default row must be non-clickable in remove-mode");
            assertEquals("default region cannot be removed", frag.hover(),
                    "locked row hover must surface the guard's registered reason");
            assertTrue(frag.text().contains("&8"),
                    "locked row must use parchment-safe gray (&8): " + frag.text());
        }

        @Test
        @DisplayName("remove-mode flips clickable entry rows to MultiConfigMutate(REMOVE)")
        void removeMode_clickableEntriesDispatchRemove() {
            // No guard registered: every entry is removable.
            seed("temp1");
            MultiConfigMenuBuilder builder = new MultiConfigMenuBuilder();

            MenuModel model = builder.buildSelector(
                    "regions", regions, /*removeMode*/ true, UUID.randomUUID());

            MenuAction.MultiConfigMutate remove = findMutateFor(
                    model, MenuAction.MultiConfigMutate.Op.REMOVE, "temp1");
            assertNotNull(remove, "remove-mode must dispatch MultiConfigMutate(REMOVE) for clickable entries");
            assertEquals("regions", remove.parserKind());

            // No OpenMultiConfigEntry rows should be present in remove-mode.
            assertNull(findFirst(model, MenuAction.OpenMultiConfigEntry.class),
                    "remove-mode must not surface OpenMultiConfigEntry rows");
        }

        @Test
        @DisplayName("off-mode entry rows dispatch OpenMultiConfigEntry")
        void offMode_clickableEntriesDispatchOpenEntry() {
            MultiConfigMenuBuilder builder = new MultiConfigMenuBuilder();

            MenuModel model = builder.buildSelector(
                    "regions", regions, /*removeMode*/ false, UUID.randomUUID());

            MenuAction.OpenMultiConfigEntry open = findOpenEntryFor(model, "default");
            assertNotNull(open, "off-mode must dispatch OpenMultiConfigEntry for entries");
            assertEquals("regions", open.parserKind());
            assertEquals("default", open.entryName());
        }
    }

    // ------------------------------------------------------------------------
    // buildEntry
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("buildEntry")
    final class BuildEntry {

        @Test
        @DisplayName("renders Back + header + key rows + Remove row")
        void layout() {
            MultiConfigMenuBuilder builder = new MultiConfigMenuBuilder();

            MenuModel model = builder.buildEntry(
                    "regions", "default", regions, UUID.randomUUID());

            // Back dispatches OpenMultiConfigSelector for the same kind.
            MenuAction.OpenMultiConfigSelector back = findFirstByType(
                    model, MenuAction.OpenMultiConfigSelector.class);
            assertNotNull(back, "Back row must dispatch OpenMultiConfigSelector");
            assertEquals("regions", back.parserKind());

            // Remove row dispatches MultiConfigMutate(REMOVE) when no guard
            // is registered.
            MenuAction.MultiConfigMutate remove = findMutateFor(
                    model, MenuAction.MultiConfigMutate.Op.REMOVE, "default");
            assertNotNull(remove, "Remove row must dispatch MultiConfigMutate(REMOVE)");
        }

        @Test
        @DisplayName("locked entry: Remove row gray + non-clickable + hover reason")
        void lockedEntry_removeRowNonClickable() {
            MultiConfigRemovalGuards.register("regions", new MultiConfigRemovalGuard() {
                @Override public boolean isLocked(String entryName) {
                    return "default".equalsIgnoreCase(entryName);
                }
                @Override public String reason(String entryName) {
                    return "default region cannot be removed";
                }
            });
            MultiConfigMenuBuilder builder = new MultiConfigMenuBuilder();

            MenuModel model = builder.buildEntry(
                    "regions", "default", regions, UUID.randomUUID());

            // No REMOVE action should be present.
            assertNull(findMutateFor(model, MenuAction.MultiConfigMutate.Op.REMOVE, "default"),
                    "locked entry must not surface a clickable REMOVE action");

            // A row whose text contains "[remove]" and "(locked)" should exist.
            MenuLine lockedRemove = findRowContaining(model, "[remove]");
            assertNotNull(lockedRemove, "locked entry page must still surface a [remove] row");
            MenuFragment frag = lockedRemove.fragments().get(0);
            assertNull(frag.action(), "locked Remove row must be non-clickable");
            assertEquals("default region cannot be removed", frag.hover(),
                    "locked Remove row hover must surface the guard reason");
        }

        @Test
        @DisplayName("unknown entry: empty-state hint + no key rows")
        void unknownEntry_rendersHint() {
            MultiConfigMenuBuilder builder = new MultiConfigMenuBuilder();

            MenuModel model = builder.buildEntry(
                    "regions", "no-such-entry-xyz", regions, UUID.randomUUID());

            MenuLine hint = findRowContaining(model, "no-such-entry-xyz");
            assertNotNull(hint, "unknown entry must surface a hint row mentioning the name");
        }

        @Test
        @DisplayName("null and empty args rejected")
        void nullAndEmpty_rejected() {
            MultiConfigMenuBuilder builder = new MultiConfigMenuBuilder();
            UUID viewer = UUID.randomUUID();

            assertThrows(NullPointerException.class,
                    () -> builder.buildEntry(null, "default", regions, viewer));
            assertThrows(NullPointerException.class,
                    () -> builder.buildEntry("regions", null, regions, viewer));
            assertThrows(NullPointerException.class,
                    () -> builder.buildEntry("regions", "default", null, viewer));
            assertThrows(NullPointerException.class,
                    () -> builder.buildEntry("regions", "default", regions, null));
            assertThrows(IllegalArgumentException.class,
                    () -> builder.buildEntry("regions", "", regions, viewer));
        }
    }

    // ------------------------------------------------------------------------
    // buildConfirmRemove
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("buildConfirmRemove")
    final class BuildConfirmRemove {

        @Test
        @DisplayName("renders Confirm + Cancel rows; Confirm mints REMOVE, Cancel returns to selector")
        void layout() {
            MultiConfigMenuBuilder builder = new MultiConfigMenuBuilder();

            MenuModel model = builder.buildConfirmRemove(
                    "regions", "alpha", UUID.randomUUID());

            MenuAction.MultiConfigMutate confirm = findMutateFor(
                    model, MenuAction.MultiConfigMutate.Op.REMOVE, "alpha");
            assertNotNull(confirm, "Confirm row must mint MultiConfigMutate(REMOVE) for the entry");
            assertEquals("regions", confirm.parserKind());

            MenuAction.OpenMultiConfigSelector cancel = findFirstByType(
                    model, MenuAction.OpenMultiConfigSelector.class);
            assertNotNull(cancel, "Cancel row must dispatch OpenMultiConfigSelector");
            assertEquals("regions", cancel.parserKind());
        }

        @Test
        @DisplayName("null and empty args rejected")
        void nullAndEmpty_rejected() {
            MultiConfigMenuBuilder builder = new MultiConfigMenuBuilder();
            UUID viewer = UUID.randomUUID();

            assertThrows(NullPointerException.class,
                    () -> builder.buildConfirmRemove(null, "default", viewer));
            assertThrows(NullPointerException.class,
                    () -> builder.buildConfirmRemove("regions", null, viewer));
            assertThrows(NullPointerException.class,
                    () -> builder.buildConfirmRemove("regions", "default", null));
            assertThrows(IllegalArgumentException.class,
                    () -> builder.buildConfirmRemove("regions", "", viewer));
        }
    }

    // ------------------------------------------------------------------------
    // nextDefaultName
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("nextDefaultName")
    final class NextDefaultName {

        @Test
        @DisplayName("returns 'default1' when nothing is taken")
        void emptyExisting_returnsDefault1() {
            assertEquals("default1", MultiConfigMenuBuilder.nextDefaultName(Set.of()));
            assertEquals("default1", MultiConfigMenuBuilder.nextDefaultName(null));
        }

        @Test
        @DisplayName("increments past taken slots, case-insensitively")
        void increments_caseInsensitive() {
            Set<String> taken = new HashSet<>();
            taken.add("DEFAULT1");
            taken.add("default2");
            assertEquals("default3", MultiConfigMenuBuilder.nextDefaultName(taken));
        }

        @Test
        @DisplayName("'default' alone is treated as not blocking 'default1'")
        void plainDefault_doesNotBlockDefault1() {
            assertEquals("default1",
                    MultiConfigMenuBuilder.nextDefaultName(Set.of("default")));
        }
    }

    // ------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------

    private static MenuAction findFirst(MenuModel model, Class<? extends MenuAction> type) {
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                if (type.isInstance(frag.action())) return frag.action();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends MenuAction> T findFirstByType(MenuModel model, Class<T> type) {
        return (T) findFirst(model, type);
    }

    private static MenuAction.MultiConfigMutate findMutate(MenuModel model,
                                                           MenuAction.MultiConfigMutate.Op op) {
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                if (frag.action() instanceof MenuAction.MultiConfigMutate m
                        && m.op() == op) {
                    return m;
                }
            }
        }
        return null;
    }

    private static MenuAction.MultiConfigMutate findMutateFor(MenuModel model,
                                                              MenuAction.MultiConfigMutate.Op op,
                                                              String entryName) {
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                if (frag.action() instanceof MenuAction.MultiConfigMutate m
                        && m.op() == op
                        && entryName.equals(m.entryName())) {
                    return m;
                }
            }
        }
        return null;
    }

    private static MenuAction.OpenMultiConfigEntry findOpenEntryFor(MenuModel model,
                                                                    String entryName) {
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                if (frag.action() instanceof MenuAction.OpenMultiConfigEntry o
                        && entryName.equals(o.entryName())) {
                    return o;
                }
            }
        }
        return null;
    }

    private static MenuLine findRowContaining(MenuModel model, String needle) {
        for (MenuLine line : model.pages().get(0).lines()) {
            StringBuilder sb = new StringBuilder();
            for (MenuFragment frag : line.fragments()) sb.append(frag.text());
            if (sb.toString().contains(needle)) return line;
        }
        return null;
    }

    /**
     * Collect the alphabetised entry names rendered as
     * {@link MenuAction.OpenMultiConfigEntry} rows in the selector page.
     */
    private static List<String> collectEntryRowOrder(MenuModel model) {
        List<String> out = new java.util.ArrayList<>();
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                if (frag.action() instanceof MenuAction.OpenMultiConfigEntry o) {
                    out.add(o.entryName());
                }
            }
        }
        return out;
    }

    private static void assertSortedCaseInsensitive(List<String> values) {
        for (int i = 1; i < values.size(); i++) {
            String prev = values.get(i - 1);
            String cur = values.get(i);
            assertFalse(String.CASE_INSENSITIVE_ORDER.compare(prev, cur) > 0,
                    "entry rows must be sorted case-insensitively: " + values);
        }
    }
}
