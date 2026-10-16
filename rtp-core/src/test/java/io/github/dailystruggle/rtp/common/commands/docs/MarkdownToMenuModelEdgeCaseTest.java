package io.github.dailystruggle.rtp.common.commands.docs;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MarkdownToMenuModel block, inline, and soft-wrap edge cases (ADR-045, ADR-104)")
class MarkdownToMenuModelEdgeCaseTest {

    private static final DocsLoweringOptions NARROW = new DocsLoweringOptions(16, 48, false, 2048L);

    private static MenuLine firstLine(String md) {
        return MarkdownToMenuModel.lower("T", md, DocsLoweringOptions.defaults()).pages().get(0).lines().get(0);
    }

    @Test
    @DisplayName("Null source produces single empty-document page")
    void nullSource() {
        MenuModel model = MarkdownToMenuModel.lower("Null", null, DocsLoweringOptions.defaults());
        assertEquals(1, model.pages().size());
        assertTrue(model.pages().get(0).lines().get(0).fragments().get(0).text().contains("empty document"));
    }

    @Test
    @DisplayName("Source with only an empty code fence produces single empty-document page")
    void emptyCodeFenceOnly() {
        MenuModel model = MarkdownToMenuModel.lower("Fence", "```\n```", DocsLoweringOptions.defaults());
        assertEquals(1, model.pages().size());
        assertTrue(model.pages().get(0).lines().get(0).fragments().get(0).text().contains("empty document"));
    }

    @Test
    @DisplayName("H3 and H4 subheadings stay on the current page")
    void subheadingsStayOnPage() {
        String md = "### Sub Three\r\n#### Sub Four\rBody";
        MenuModel model = MarkdownToMenuModel.lower("Sub", md, DocsLoweringOptions.defaults());
        assertEquals(1, model.pages().size());
        List<MenuLine> lines = model.pages().get(0).lines();
        assertEquals("&5&lSub Three", lines.get(0).fragments().get(0).text());
        assertEquals("&5&lSub Four", lines.get(1).fragments().get(0).text());
    }

    @Test
    @DisplayName("All three horizontal rule spellings lower to a divider line")
    void horizontalRules() {
        MenuPage page = MarkdownToMenuModel.lower("HR", "---\n***\n___", DocsLoweringOptions.defaults()).pages().get(0);
        assertEquals(3, page.lines().size());
        for (MenuLine line : page.lines()) {
            assertTrue(line.fragments().get(0).text().startsWith("&8\u2500"));
        }
    }

    @Test
    @DisplayName("Blockquote lowers with quote bar and stripped marker")
    void blockquote() {
        MenuLine line = firstLine(">   Quoted text");
        assertEquals("&7\u258e &8Quoted text", line.fragments().get(0).text());
    }

    @Test
    @DisplayName("Blank lines add one spacer only after content and never stack")
    void blankLineSpacers() {
        String md = "\nFirst\n\n\nSecond\n# Head\n\nAfter";
        MenuModel model = MarkdownToMenuModel.lower("Blank", md, DocsLoweringOptions.defaults());
        assertEquals(2, model.pages().size());

        List<MenuLine> p1 = model.pages().get(0).lines();
        assertEquals(3, p1.size());
        assertTrue(p1.get(1).fragments().isEmpty());

        List<MenuLine> p2 = model.pages().get(1).lines();
        assertEquals(3, p2.size());
        assertTrue(p2.get(1).fragments().isEmpty());
    }

    @Test
    @DisplayName("Bracket text without link syntax is soft-wrapped as a plain paragraph")
    void bracketWithoutLink() {
        MenuLine line = firstLine("array[0] value");
        assertEquals(1, line.fragments().size());
        assertEquals("&0array[0] value", line.fragments().get(0).text());
    }

    @Test
    @DisplayName("Bold and inline code are restyled inside paragraph text")
    void boldAndInlineCode() {
        MenuLine line = firstLine("Use **bold** and `code`");
        assertEquals("&0Use &0&lbold&r&0 and &8[code]&r&0", line.fragments().get(0).text());
    }

    @Test
    @DisplayName("Long paragraphs soft-wrap at word boundaries and keep over-long words intact")
    void softWrap() {
        String md = "Supercalifragilisticexpialidocious word then more words to wrap here";
        List<MenuLine> lines = MarkdownToMenuModel.lower("Wrap", md, NARROW).pages().get(0).lines();
        assertTrue(lines.size() > 2);
        assertEquals("&0Supercalifragilisticexpialidocious", lines.get(0).fragments().get(0).text());
        for (int i = 1; i < lines.size(); i++) {
            String text = lines.get(i).fragments().get(0).text().substring(2);
            assertTrue(text.length() <= 16, "wrapped line too long: " + text);
        }
    }

    @Test
    @DisplayName("Link-only line with http URL lowers to OpenExternalUrl and no surrounding text")
    void httpLinkOnly() {
        MenuLine line = firstLine("[Site](http://example.com)");
        assertEquals(1, line.fragments().size());
        MenuFragment frag = line.fragments().get(0);
        assertEquals("&1Site", frag.text());
        assertEquals("http://example.com", frag.hover());
        MenuAction.OpenExternalUrl action = assertInstanceOf(MenuAction.OpenExternalUrl.class, frag.action());
        assertEquals(URI.create("http://example.com"), action.uri());
    }

    @Test
    @DisplayName("Malformed external URL keeps the link text with no action")
    void malformedExternalUrl() {
        MenuFragment frag = firstLine("[Bad](https://exa mple.com)").fragments().get(0);
        assertEquals("&1Bad", frag.text());
        assertNull(frag.action());
    }

    @Test
    @DisplayName("Anchored doc links strip the anchor and other relative links have no action")
    void anchoredAndNonDocLinks() {
        MenuLine line = firstLine("[Guide](guide.md#section) then [Image](image.png)");
        List<MenuFragment> frags = line.fragments();
        assertEquals(3, frags.size());

        MenuAction.RunRtpCommand docs = assertInstanceOf(MenuAction.RunRtpCommand.class, frags.get(0).action());
        assertEquals("docs", docs.args()[0]);
        assertEquals("guide.md", docs.args()[1]);

        assertEquals("&0 then ", frags.get(1).text());

        assertEquals("&1Image", frags.get(2).text());
        assertNull(frags.get(2).action());
    }
}
