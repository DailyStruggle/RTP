package io.github.dailystruggle.rtp.common.commands.docs;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MarkdownToMenuModel lowering tests (ADR-045, ADR-104)")
class MarkdownToMenuModelTest {

    @Test
    @DisplayName("Empty or blank source produces single empty-document page")
    void emptySource() {
        MenuModel model = MarkdownToMenuModel.lower("Empty", "", DocsLoweringOptions.defaults());
        assertEquals("Empty", model.title());
        assertEquals(1, model.pages().size());
        assertTrue(model.pages().get(0).lines().get(0).fragments().get(0).text().contains("empty document"));
    }

    @Test
    @DisplayName("H1 and H2 headings create page breaks")
    void headingPageBreaks() {
        String md = """
                # Page One
                Intro text on page 1.

                ## Page Two
                Text on page 2.
                """;
        MenuModel model = MarkdownToMenuModel.lower("Test", md, DocsLoweringOptions.defaults());
        assertEquals(2, model.pages().size());
        assertTrue(model.pages().get(0).lines().get(0).fragments().get(0).text().contains("Page One"));
        assertTrue(model.pages().get(1).lines().get(0).fragments().get(0).text().contains("Page Two"));
    }

    @Test
    @DisplayName("Code blocks truncate when over maxCodeLineWidth")
    void codeBlockTruncation() {
        String md = """
                ```
                short line
                this is a very very long line of code that exceeds the configured code line width limit
                ```
                """;
        DocsLoweringOptions opts = new DocsLoweringOptions(56, 30, false, 2048L);
        MenuModel model = MarkdownToMenuModel.lower("Code", md, opts);
        MenuPage page = model.pages().get(0);
        assertTrue(page.lines().size() >= 2);
        MenuFragment longCodeFrag = page.lines().get(1).fragments().get(0);
        assertTrue(longCodeFrag.text().endsWith("\u2026"));
        assertNotNull(longCodeFrag.hover());
    }

    @Test
    @DisplayName("Lists are parsed with bullet and numeric markers")
    void listParsing() {
        String md = """
                - Unordered item
                * Star item
                1. Ordered item
                """;
        MenuModel model = MarkdownToMenuModel.lower("Lists", md, DocsLoweringOptions.defaults());
        MenuPage page = model.pages().get(0);
        assertEquals(3, page.lines().size());
        assertTrue(page.lines().get(0).fragments().get(0).text().contains("\u2022"));
        assertTrue(page.lines().get(1).fragments().get(0).text().contains("\u2022"));
        assertTrue(page.lines().get(2).fragments().get(0).text().contains("1."));
    }

    @Test
    @DisplayName("Absolute and relative links lower to OpenExternalUrl and RunRtpCommand")
    void linkParsing() {
        String md = "Check out [Website](https://dailystruggle.github.io/RTP) or [Admin](admin/QUICK_START.md).";
        MenuModel model = MarkdownToMenuModel.lower("Links", md, DocsLoweringOptions.defaults());
        MenuLine line = model.pages().get(0).lines().get(0);

        boolean foundExternal = false;
        boolean foundDoc = false;

        for (MenuFragment frag : line.fragments()) {
            if (frag.action() instanceof MenuAction.OpenExternalUrl u) {
                assertEquals(URI.create("https://dailystruggle.github.io/RTP"), u.uri());
                foundExternal = true;
            } else if (frag.action() instanceof MenuAction.RunRtpCommand cmd) {
                assertEquals("docs", cmd.args()[0]);
                assertEquals("admin/QUICK_START.md", cmd.args()[1]);
                foundDoc = true;
            }
        }

        assertTrue(foundExternal, "Must contain OpenExternalUrl action");
        assertTrue(foundDoc, "Must contain RunRtpCommand docs action");
    }
}
