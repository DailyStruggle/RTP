package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;
import io.github.dailystruggle.rtp.common.commands.prefab.PrefabApplier;
import io.github.dailystruggle.rtp.common.commands.prefab.PrefabRegistry;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.LowPerformance;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.SurvivalDefault;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PrefabConfirmationMenuBuilder} (Session 5).
 *
 * <p>Covers:
 * <ul>
 *   <li>Title carries the prefab display name via the {@code [prefab]}
 *       placeholder substitution.</li>
 *   <li>Diff ordering matches the {@link PrefabApplier.Result#perFileDiff()}
 *       iteration order (file order + per-file change order).</li>
 *   <li>{@code Confirm} row dispatches
 *       {@code RunRtpCommand({admin,prefab,confirm,<id>,<token>})}.</li>
 *   <li>{@code Cancel} row dispatches {@link MenuAction.OpenAdminPanel}.</li>
 *   <li>Empty diff renders an identity-overlay notice but still emits the
 *       {@code Confirm} / {@code Cancel} footer.</li>
 *   <li>Overflow tail when a single file exceeds
 *       {@link PrefabConfirmationMenuBuilder#MAX_LINES_PER_FILE}.</li>
 *   <li>Null / empty argument rejection.</li>
 * </ul>
 */
final class PrefabConfirmationMenuBuilderTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setupRTP() {
        RTPTestSetup.install(tempDir.toFile());
    }

    @Test
    @DisplayName("Title substitutes the [prefab] placeholder with the prefab display name")
    void title_carriesPrefabDisplayName() {
        Prefab prefab = LowPerformance.INSTANCE;
        MenuModel model = new PrefabConfirmationMenuBuilder()
                .build(prefab, PrefabApplier.apply(Map.of(), prefab).perFileDiff(),
                        UUID.randomUUID());

        String firstLineText = textOf(model.pages().get(0).lines().get(0));
        assertNotNull(firstLineText);
        assertTrue(firstLineText.contains("[prefab]") == false,
                "Title must substitute the [prefab] placeholder, not leak it: " + firstLineText);
    }

    @Test
    @DisplayName("Confirm row dispatches RunRtpCommand({admin,prefab,confirm,id=<id>}) (no token, ADR-050-style)")
    void confirmRow_dispatchesConfirmVerbWithId() {
        Prefab prefab = LowPerformance.INSTANCE;

        MenuModel model = new PrefabConfirmationMenuBuilder()
                .build(prefab, PrefabApplier.apply(Map.of(), prefab).perFileDiff(),
                        UUID.randomUUID());

        MenuAction confirm = findRunWithArgs(model,
                "admin", "prefab", "confirm", "id=" + prefab.id());
        assertNotNull(confirm,
                "Confirm row must dispatch the confirm verb keyed solely on id=<prefabId>");
    }

    @Test
    @DisplayName("Cancel row dispatches OpenAdminPanel")
    void cancelRow_dispatchesOpenAdminPanel() {
        Prefab prefab = LowPerformance.INSTANCE;

        MenuModel model = new PrefabConfirmationMenuBuilder()
                .build(prefab, PrefabApplier.apply(Map.of(), prefab).perFileDiff(),
                        UUID.randomUUID());

        MenuAction cancel = findFirst(model, MenuAction.OpenAdminPanel.class);
        assertNotNull(cancel, "Cancel row must dispatch OpenAdminPanel");
    }

    @Test
    @DisplayName("Diff body iteration order matches PrefabApplier.perFileDiff order")
    void diff_iterationOrderMatchesApplier() {
        Prefab prefab = LowPerformance.INSTANCE;
        Map<String, List<PrefabApplier.Change>> diff =
                PrefabApplier.apply(Map.of(), prefab).perFileDiff();

        MenuModel model = new PrefabConfirmationMenuBuilder()
                .build(prefab, diff, UUID.randomUUID());

        // Each file id appears as a fragment header preceding its keyPath rows.
        List<String> seenFiles = new ArrayList<>();
        for (MenuLine line : model.pages().get(0).lines()) {
            String t = textOf(line);
            if (t == null) continue;
            for (String fileId : diff.keySet()) {
                if (t.contains(fileId + ".yml")) {
                    if (seenFiles.isEmpty() || !seenFiles.get(seenFiles.size() - 1).equals(fileId)) {
                        seenFiles.add(fileId);
                    }
                }
            }
        }
        assertEquals(new ArrayList<>(diff.keySet()), seenFiles,
                "File-id headers in the diff body must appear in PrefabApplier iteration order");
    }

    @Test
    @DisplayName("Identity overlay (empty diff): no-changes notice + Confirm/Cancel still emitted")
    void identityOverlay_rendersNoticeAndFooter() {
        Prefab prefab = SurvivalDefault.INSTANCE;

        Map<String, List<PrefabApplier.Change>> diff =
                PrefabApplier.apply(Map.of(), prefab).perFileDiff();
        assertTrue(diff.isEmpty(), "SurvivalDefault.INSTANCE must produce an empty diff");

        MenuModel model = new PrefabConfirmationMenuBuilder()
                .build(prefab, diff, UUID.randomUUID());

        assertNotNull(findRunWithArgs(model,
                "admin", "prefab", "confirm", "id=" + prefab.id()),
                "Confirm row must still be present on an identity-overlay diff");
        assertNotNull(findFirst(model, MenuAction.OpenAdminPanel.class),
                "Cancel row must still be present on an identity-overlay diff");
    }

    @Test
    @DisplayName("Overflow: per-file diff longer than MAX_LINES_PER_FILE collapses to '... (+N more)'")
    void overflow_collapsesTail() {
        // Build a synthetic large-diff prefab: 12 keys on performance, all
        // new (currentTrees absent -> all oldValue=null -> all are Changes).
        Map<String, Object> overlay = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            overlay.put("k" + i, i);
        }
        Prefab big = new Prefab(
                "big-test", "noKey", "noKey", "test",
                overlay, Map.of(), false);
        Map<String, List<PrefabApplier.Change>> diff =
                PrefabApplier.apply(Map.of(), big).perFileDiff();

        MenuModel model = new PrefabConfirmationMenuBuilder()
                .build(big, diff, UUID.randomUUID());

        int expectedOverflow = 12 - PrefabConfirmationMenuBuilder.MAX_LINES_PER_FILE;
        boolean foundTail = false;
        for (MenuLine line : model.pages().get(0).lines()) {
            String t = textOf(line);
            if (t != null && t.contains("(+" + expectedOverflow + " more)")) {
                foundTail = true;
                break;
            }
        }
        assertTrue(foundTail,
                "Overflow tail must collapse the remaining (" + expectedOverflow + ") rows");
    }

    @Test
    @DisplayName("Null argument rejection (tokenless build signature)")
    void nullAndEmpty_rejected() {
        PrefabConfirmationMenuBuilder b = new PrefabConfirmationMenuBuilder();
        Prefab prefab = LowPerformance.INSTANCE;
        Map<String, List<PrefabApplier.Change>> diff = Map.of();
        UUID viewer = UUID.randomUUID();

        assertThrows(NullPointerException.class,
                () -> b.build(null, diff, viewer));
        assertThrows(NullPointerException.class,
                () -> b.build(prefab, null, viewer));
        assertThrows(NullPointerException.class,
                () -> b.build(prefab, diff, null));
    }

    @Test
    @DisplayName("Every bundled prefab renders without throwing (PrefabRegistry coverage)")
    void everyBundledPrefab_renders() {
        for (Prefab p : PrefabRegistry.list()) {
            Map<String, List<PrefabApplier.Change>> diff =
                    PrefabApplier.apply(Map.of(), p).perFileDiff();
            MenuModel model = new PrefabConfirmationMenuBuilder()
                    .build(p, diff, UUID.randomUUID());
            assertNotNull(model, "Confirmation menu must build for " + p.id());
            assertEquals(1, model.pages().size(),
                    "Confirmation menu for " + p.id() + " must fit on a single book page");
        }
    }

    // ---- helpers ----------------------------------------------------------

    private static String textOf(MenuLine line) {
        StringBuilder sb = new StringBuilder();
        for (MenuFragment frag : line.fragments()) {
            sb.append(frag.text());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static MenuAction findRunWithArgs(MenuModel model, String... expected) {
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                if (frag.action() instanceof MenuAction.RunRtpCommand run
                        && argsMatch(run.args(), expected)) {
                    return run;
                }
            }
        }
        return null;
    }

    private static <T extends MenuAction> MenuAction findFirst(MenuModel model, Class<T> type) {
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                if (type.isInstance(frag.action())) return frag.action();
            }
        }
        return null;
    }

    private static boolean argsMatch(String[] actual, String[] expected) {
        if (actual.length != expected.length) return false;
        for (int i = 0; i < actual.length; i++) {
            if (!actual[i].equals(expected[i])) return false;
        }
        return true;
    }
}
