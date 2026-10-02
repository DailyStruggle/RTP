package io.github.dailystruggle.rtp.api.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BookSpec branch and contract coverage")
class BookSpecCoverageTest {

    @Test
    @DisplayName("null constructor parameters fallback to defaults")
    void testDefaults() {
        BookSpec spec = new BookSpec(null, null);
        assertEquals("RTP", spec.title());
        assertNotNull(spec.pages());
        assertTrue(spec.pages().isEmpty());

        BookSpec.Page page = new BookSpec.Page(null);
        assertNotNull(page.lines());
        assertTrue(page.lines().isEmpty());

        BookSpec.Line line = new BookSpec.Line(null);
        assertNotNull(line.fragments());
        assertTrue(line.fragments().isEmpty());
    }

    @Test
    @DisplayName("equals, hashCode, and toString branches")
    void testEqualsHashCodeToString() {
        BookSpec s1 = new BookSpec("Title", List.of());
        BookSpec s2 = new BookSpec("Title", List.of());
        BookSpec sDiffTitle = new BookSpec("Other", List.of());
        BookSpec.Fragment frag = new BookSpec.Fragment("text", "hover", "/cmd");
        BookSpec.Line line = new BookSpec.Line(List.of(frag));
        BookSpec.Page page = new BookSpec.Page(List.of(line));
        BookSpec sDiffPages = new BookSpec("Title", List.of(page));

        assertEquals(s1, s1);
        assertEquals(s1, s2);
        assertEquals(s1.hashCode(), s2.hashCode());
        assertNotEquals(s1, sDiffTitle);
        assertNotEquals(s1, sDiffPages);
        assertNotEquals(s1, null);
        assertNotEquals(s1, "other");

        assertEquals("BookSpec{title='Title', pages=0}", s1.toString());
        assertEquals("BookSpec{title='Title', pages=1}", sDiffPages.toString());
    }
}
