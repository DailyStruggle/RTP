package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 4 (PROPOSAL-config-view-as-book.md v3.7.5 / CHECKLIST step 4) — surface
 * tests for {@code CommandTreeMenuBuilder.buildShapeVertTypePicker} (page 3a)
 * and {@code buildShapeVertSubParamPage} (page 3b).
 *
 * <p>The builders are tested in isolation: caller supplies the type-name list
 * and sub-parameter map (in production these come from
 * {@code ShapeParameter.values()} / {@code ShapeParameter.subParams(type)} and
 * {@code factory.get(type).getData()}, but the builder does not reach
 * {@code RTP.factoryMap} directly — that wiring lands in the step-6
 * {@code MenuConfigSubtreeBuilder} impl). Q13 stateless-write contract: every
 * sub-parameter row emits {@code OpenParamPicker} with the same write-command
 * path; the parser's stored {@code name} discriminates which type is active.
 */
@DisplayName("PROPOSAL-config-view-as-book v3.7.5 § shape/vert two-step expansion (step 4)")
class MenuShapeVertExpansionTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setupRTP() {
        RTPTestSetup.install(tempDir.toFile());
    }

    // ------------------------------------------------------------------------
    // buildShapeVertTypePicker (page 3a)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("type picker: Back row + header + one OpenMenu(name:<T>) row per type")
    void typePicker_layout() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder(registry);

        List<String> types = List.of("SQUARE", "CIRCLE");
        List<String> writePath = List.of("config", "regions", "set", "default");

        MenuModel model = builder.buildShapeVertTypePicker(
                UUID.randomUUID(),
                "regions",
                "shape",
                "SQUARE",
                types,
                writePath);

        assertEquals(1, model.pages().size(), "single-page picker");
        List<MenuLine> lines = model.pages().get(0).lines();
        // 1 Back + 1 header + 2 type rows = 4
        assertEquals(4, lines.size());

        // Row 0 → Back via OpenConfigFile (per-file page).
        MenuFragment back = lines.get(0).fragments().get(0);
        assertInstanceOf(MenuAction.OpenConfigFile.class, back.action(),
                "Back must re-open the per-file page");
        assertEquals("regions",
                ((MenuAction.OpenConfigFile) back.action()).fileName());

        // Row 1 → header (no action).
        assertNull(lines.get(1).fragments().get(0).action(),
                "header row must be non-clickable");

        // Rows 2..3 → one OpenMenu(writePath..., "name:<type>") per type, in
        // encounter order; current type is marked but still clickable per the
        // stateless contract (re-picking the active type resets orphan sub-
        // params).
        for (int i = 0; i < types.size(); i++) {
            MenuFragment row = lines.get(2 + i).fragments().get(0);
            assertInstanceOf(MenuAction.OpenMenu.class, row.action(),
                    "type row " + i + " must be OpenMenu (writes name:<type>)");
            String[] path = ((MenuAction.OpenMenu) row.action()).path();
            String[] expected = new String[]{
                    "config", "regions", "set", "default",
                    "name:" + types.get(i)};
            assertArrayEquals(expected, path,
                    "type row " + i + " must write name:<type> at the end of writeCommandPath");
        }
    }

    @Test
    @DisplayName("type picker: current type is marked in the row label (case-insensitive)")
    void typePicker_currentTypeMarker() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder(registry);

        MenuModel model = builder.buildShapeVertTypePicker(
                UUID.randomUUID(),
                "regions",
                "shape",
                "square",                       // lower-case
                List.of("SQUARE", "CIRCLE"),    // upper-case in the list
                List.of("config", "regions", "set", "default"));

        List<MenuLine> lines = model.pages().get(0).lines();
        String activeLabel = lines.get(2).fragments().get(0).text();
        String inactiveLabel = lines.get(3).fragments().get(0).text();
        // Active row uses the &a* prefix; inactive uses &e (per builder).
        assertTrue(activeLabel.contains("*"),
                "active type row must include the '*' marker");
        assertTrue(activeLabel.contains("SQUARE"));
        assertTrue(inactiveLabel.contains("CIRCLE"));
        assertTrue(!inactiveLabel.contains("*"),
                "inactive type row must not include the '*' marker");
    }

    @Test
    @DisplayName("type picker: null currentTypeName is allowed; null/empty entries skipped")
    void typePicker_nullCurrentAndBlankEntries() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder(registry);

        List<String> types = new java.util.ArrayList<>();
        types.add("SQUARE");
        types.add(null);
        types.add("");
        types.add("CIRCLE");

        MenuModel model = builder.buildShapeVertTypePicker(
                UUID.randomUUID(),
                "regions",
                "shape",
                null,                                                  // null current allowed
                types,
                List.of("config", "regions", "set", "default"));

        List<MenuLine> lines = model.pages().get(0).lines();
        // Back + header + 2 real type rows = 4 (null/empty filtered).
        assertEquals(4, lines.size(),
                "null/empty type entries must be filtered defensively");
    }

    @Test
    @DisplayName("type picker: required args rejected when null or empty")
    void typePicker_argRejection() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder(registry);
        UUID id = UUID.randomUUID();

        assertThrows(NullPointerException.class, () -> builder.buildShapeVertTypePicker(
                null, "regions", "shape", "SQUARE", List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> builder.buildShapeVertTypePicker(
                id, null, "shape", "SQUARE", List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> builder.buildShapeVertTypePicker(
                id, "regions", null, "SQUARE", List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> builder.buildShapeVertTypePicker(
                id, "regions", "shape", "SQUARE", null, List.of()));
        assertThrows(NullPointerException.class, () -> builder.buildShapeVertTypePicker(
                id, "regions", "shape", "SQUARE", List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> builder.buildShapeVertTypePicker(
                id, "", "shape", "SQUARE", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> builder.buildShapeVertTypePicker(
                id, "regions", "", "SQUARE", List.of(), List.of()));
    }

    // ------------------------------------------------------------------------
    // buildShapeVertSubParamPage (page 3b)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("sub-param page: Back row + header + one OpenParamPicker per sub-param")
    void subParamPage_layout() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder(registry);

        // Use LinkedHashMap for deterministic iteration order.
        Map<String, Object> subParams = new LinkedHashMap<>();
        subParams.put("radius", 1000);
        subParams.put("centerX", 0);
        subParams.put("centerZ", 0);

        List<String> writePath = List.of("config", "regions", "set", "default");

        MenuModel model = builder.buildShapeVertSubParamPage(
                UUID.randomUUID(),
                "regions",
                "shape",
                "SQUARE",
                subParams,
                writePath);

        assertEquals(1, model.pages().size());
        List<MenuLine> lines = model.pages().get(0).lines();
        // 1 Back + 1 header + 3 sub-param rows = 5
        assertEquals(5, lines.size());

        // Row 0 → Back via OpenConfigKey (re-opens the type-picker, page 3a).
        MenuFragment back = lines.get(0).fragments().get(0);
        assertInstanceOf(MenuAction.OpenConfigKey.class, back.action(),
                "Back must re-open the type picker via OpenConfigKey");
        MenuAction.OpenConfigKey backAction = (MenuAction.OpenConfigKey) back.action();
        assertEquals("regions", backAction.fileName());
        assertEquals("shape", backAction.paramName());

        // Row 1 → header (no action).
        assertNull(lines.get(1).fragments().get(0).action());

        // Rows 2..4 → one OpenParamPicker per sub-param, in insertion order.
        String[] expectedNames = {"radius", "centerX", "centerZ"};
        String[] expectedParentPath = writePath.toArray(new String[0]);
        for (int i = 0; i < expectedNames.length; i++) {
            MenuFragment row = lines.get(2 + i).fragments().get(0);
            assertInstanceOf(MenuAction.OpenParamPicker.class, row.action(),
                    "sub-param row " + i + " must be OpenParamPicker (stateless flat write)");
            MenuAction.OpenParamPicker picker = (MenuAction.OpenParamPicker) row.action();
            assertEquals(expectedNames[i], picker.paramName(),
                    "sub-param row " + i + " must target flat key " + expectedNames[i]);
            assertArrayEquals(expectedParentPath, picker.parentPath(),
                    "sub-param row " + i + " must use writeCommandPath as parentPath");
            assertTrue(row.text().contains(expectedNames[i]),
                    "sub-param row " + i + " label must include the sub-param name");
        }
    }

    @Test
    @DisplayName("sub-param page: empty subParamValues degrades to Back + header + hint")
    void subParamPage_emptyMap() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder(registry);

        MenuModel model = builder.buildShapeVertSubParamPage(
                UUID.randomUUID(),
                "regions",
                "shape",
                "POINT",
                Map.of(),
                List.of("config", "regions", "set", "default"));

        List<MenuLine> lines = model.pages().get(0).lines();
        assertEquals(3, lines.size(),
                "empty sub-params: Back + header + hint");
        assertNull(lines.get(2).fragments().get(0).action(),
                "hint row must be non-clickable");
    }

    @Test
    @DisplayName("sub-param page: null entry keys are skipped (defensive)")
    void subParamPage_skipsBlankKeys() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder(registry);

        Map<String, Object> subParams = new LinkedHashMap<>();
        subParams.put("radius", 1000);
        subParams.put("", "ignored");

        MenuModel model = builder.buildShapeVertSubParamPage(
                UUID.randomUUID(),
                "regions",
                "shape",
                "SQUARE",
                subParams,
                List.of("config", "regions", "set", "default"));

        List<MenuLine> lines = model.pages().get(0).lines();
        // Back + header + 1 real sub-param (empty key skipped) = 3
        assertEquals(3, lines.size(),
                "empty sub-param-name entries must be filtered defensively");
    }

    @Test
    @DisplayName("sub-param page: required args rejected when null or empty")
    void subParamPage_argRejection() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder(registry);
        UUID id = UUID.randomUUID();

        assertThrows(NullPointerException.class, () -> builder.buildShapeVertSubParamPage(
                null, "regions", "shape", "SQUARE", Map.of(), List.of()));
        assertThrows(NullPointerException.class, () -> builder.buildShapeVertSubParamPage(
                id, null, "shape", "SQUARE", Map.of(), List.of()));
        assertThrows(NullPointerException.class, () -> builder.buildShapeVertSubParamPage(
                id, "regions", null, "SQUARE", Map.of(), List.of()));
        assertThrows(NullPointerException.class, () -> builder.buildShapeVertSubParamPage(
                id, "regions", "shape", null, Map.of(), List.of()));
        assertThrows(NullPointerException.class, () -> builder.buildShapeVertSubParamPage(
                id, "regions", "shape", "SQUARE", null, List.of()));
        assertThrows(NullPointerException.class, () -> builder.buildShapeVertSubParamPage(
                id, "regions", "shape", "SQUARE", Map.of(), null));
        assertThrows(IllegalArgumentException.class, () -> builder.buildShapeVertSubParamPage(
                id, "", "shape", "SQUARE", Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> builder.buildShapeVertSubParamPage(
                id, "regions", "", "SQUARE", Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> builder.buildShapeVertSubParamPage(
                id, "regions", "shape", "", Map.of(), List.of()));
    }
}
