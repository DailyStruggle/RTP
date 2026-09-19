package io.github.dailystruggle.rtp.common.commands.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure-function tests for {@link InfoBookBuilder}. Exercises pagination and scope-to-parameters mapping in
 * isolation; the full {@code build()} happy-path requires a wired
 * {@code RTP.configs} + {@code serverAccessor} and is covered by an
 * integration-style test elsewhere.
 */
class InfoBookBuilderTest {

    @Test
    @DisplayName("scopeToParameters: GLOBAL -> empty map, WORLD/REGION -> singleton {name: [value]}")
    void scopeToParametersShape() {
        Map<String, List<String>> global =
                InfoBookBuilder.scopeToParameters(MenuAction.InfoScopeToken.global());
        assertTrue(global.isEmpty(), "GLOBAL must map to an empty parameter map");

        Map<String, List<String>> world =
                InfoBookBuilder.scopeToParameters(MenuAction.InfoScopeToken.world("the_end"));
        assertEquals(1, world.size());
        assertEquals(List.of("the_end"), world.get("world"));
        assertNull(world.get("region"));

        Map<String, List<String>> region =
                InfoBookBuilder.scopeToParameters(MenuAction.InfoScopeToken.region("default"));
        assertEquals(1, region.size());
        assertEquals(List.of("default"), region.get("region"));
        assertNull(region.get("world"));
    }

    @Test
    @DisplayName("paginate: empty input -> single empty page (footer is added later)")
    void paginateEmpty() {
        List<MenuPage> pages = InfoBookBuilder.paginate(Collections.emptyList());
        assertEquals(1, pages.size());
        assertTrue(pages.get(0).lines().isEmpty());
    }

    @Test
    @DisplayName("paginate: short input -> one page with one line per captured line")
    void paginateShort() {
        List<String> lines = List.of("one", "two", "three");
        List<MenuPage> pages = InfoBookBuilder.paginate(lines);
        assertEquals(1, pages.size());
        assertEquals(3, pages.get(0).lines().size());
        assertEquals("one", pages.get(0).lines().get(0).fragments().get(0).text());
        assertEquals("two", pages.get(0).lines().get(1).fragments().get(0).text());
        assertEquals("three", pages.get(0).lines().get(2).fragments().get(0).text());
    }

    @Test
    @DisplayName("paginate: empty strings preserved as blank lines (vertical structure parity)")
    void paginatePreservesBlanks() {
        List<String> lines = List.of("a", "", "b");
        List<MenuPage> pages = InfoBookBuilder.paginate(lines);
        assertEquals(1, pages.size());
        assertEquals(3, pages.get(0).lines().size());
        assertFalse(pages.get(0).lines().get(0).fragments().isEmpty());
        assertTrue(pages.get(0).lines().get(1).fragments().isEmpty(),
                "Empty string must become a blank MenuLine");
        assertFalse(pages.get(0).lines().get(2).fragments().isEmpty());
    }

    @Test
    @DisplayName("paginate: input longer than LINES_PER_PAGE splits across pages, no line dropped")
    void paginateOverflow() {
        int total = InfoBookBuilder.LINES_PER_PAGE * 2 + 5;
        List<String> lines = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            lines.add("line-" + i);
        }
        List<MenuPage> pages = InfoBookBuilder.paginate(lines);
        // total / LINES_PER_PAGE rounded up
        int expected = (total + InfoBookBuilder.LINES_PER_PAGE - 1) / InfoBookBuilder.LINES_PER_PAGE;
        assertEquals(expected, pages.size());
        // First two pages are at the cap.
        assertEquals(InfoBookBuilder.LINES_PER_PAGE, pages.get(0).lines().size());
        assertEquals(InfoBookBuilder.LINES_PER_PAGE, pages.get(1).lines().size());
        // Last page carries the remainder (5 lines).
        assertEquals(5, pages.get(pages.size() - 1).lines().size());
        // No line was dropped.
        int rendered = 0;
        for (MenuPage page : pages) {
            rendered += page.lines().size();
        }
        assertEquals(total, rendered);
    }

    @Test
    @DisplayName("paginate: null entries in input are silently skipped (defensive)")
    void paginateSkipsNulls() {
        List<String> lines = new ArrayList<>();
        lines.add("first");
        lines.add(null);
        lines.add("second");
        List<MenuPage> pages = InfoBookBuilder.paginate(lines);
        assertEquals(1, pages.size());
        // Only two visible lines; the null entry was discarded.
        assertEquals(2, pages.get(0).lines().size());
        assertEquals("first", pages.get(0).lines().get(0).fragments().get(0).text());
        assertEquals("second", pages.get(0).lines().get(1).fragments().get(0).text());
    }

    // ADR-050 Stage 3β.D.2b (2026-05-24): deleted `constructorRejectsNulls`
    // and `defaultTokenTtlAlignment` - the `MenuTokenRegistry` ctor param
    // and `DEFAULT_TOKEN_TTL` constant are gone (renderer emits concrete
    // `/rtp menu ...` commands; no TTL applies).
}
