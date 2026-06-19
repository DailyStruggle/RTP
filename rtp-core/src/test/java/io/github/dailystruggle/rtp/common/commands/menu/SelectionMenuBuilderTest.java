package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The curated selection page ({@code /rtp menu world|region|biome|prefab})
 * renders a clean colored "pick a X" header (no command echo, not gray), no
 * "type a custom value" row, and one color-prefixed row per supplied entry.
 * The builder is fully generic: header name, entries, and colors are all
 * supplied by the caller.
 */
class SelectionMenuBuilderTest {

    @Test
    void headerIsCleanGreen_noCommandEcho_noTypeRow() {
        MenuModel model = new SelectionMenuBuilder().build(
                List.of(), "world", "world", List.of("nether"), value -> "&a", true);
        assertNotNull(model);

        List<MenuLine> lines = model.pages().get(0).lines();
        // Row 0 is Back, row 1 is the header.
        String header = lines.get(1).fragments().get(0).text();
        assertNotNull(header);
        assertTrue(header.startsWith("&1&l\u25B6 PICK A "),
                "header must be a bold, uppercased heading: " + header);
        assertEquals("&1&l\u25B6 PICK A WORLD", header);
        assertFalse(header.contains("will run"),
                "header must not echo the command it would run: " + header);
        assertFalse(header.contains("&7"),
                "header must not carry the gray styling: " + header);
        // A blank spacer follows the header so it reads as a heading.
        assertEquals(" ", lines.get(2).fragments().get(0).text(),
                "a blank separator must follow the header");

        // No anvil / "type a custom value" row on a curated selection page.
        for (MenuLine line : lines) {
            for (MenuFragment frag : line.fragments()) {
                String t = frag.text();
                assertFalse(t != null && t.contains("type a custom"),
                        "selection page must not show a 'type a custom value' row");
            }
        }
    }

    @Test
    void entryRowsAreColorPrefixedAndStageOntoParentPath() {
        MenuModel model = new SelectionMenuBuilder().build(
                List.of("admin", "prefab", "apply"), "id", "prefab",
                List.of("survival-default"), value -> "&2", false);
        assertNotNull(model);

        boolean saw = false;
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                String t = frag.text();
                if (t != null && t.endsWith("survival-default")
                        && frag.action() instanceof MenuAction.OpenMenu open) {
                    saw = true;
                    assertEquals("&2survival-default", t);
                    // Stages id=value onto the supplied parent path.
                    assertEquals(4, open.path().length);
                    assertEquals("admin", open.path()[0]);
                    assertEquals("prefab", open.path()[1]);
                    assertEquals("apply", open.path()[2]);
                    assertEquals("id=survival-default", open.path()[3]);
                }
            }
        }
        assertTrue(saw, "selection page must list the supplied entry as a staging row");
    }

    @Test
    void destinationEntriesRunTheCommandOnClick() {
        MenuModel model = new SelectionMenuBuilder().build(
                List.of(), "biome", "biome", List.of("desert"), value -> "&2", true);
        boolean saw = false;
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                String t = frag.text();
                if (t != null && t.endsWith("desert")
                        && frag.action() instanceof MenuAction.RunRtpCommand run) {
                    saw = true;
                    assertEquals(1, run.args().length);
                    assertEquals("biome=desert", run.args()[0]);
                }
            }
        }
        assertTrue(saw, "destination entries must run /rtp biome=<value> on click");
    }

    @Test
    void nullColorSupplier_defaultsToDarkGreen() {
        MenuModel model = new SelectionMenuBuilder().build(
                List.of(), "region", "region", List.of("alpha"), null, true);
        boolean saw = false;
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                String t = frag.text();
                if (t != null && t.endsWith("alpha")) {
                    saw = true;
                    assertEquals("&2alpha", t);
                }
            }
        }
        assertTrue(saw);
    }
}
