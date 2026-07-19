package io.github.dailystruggle.rtp.common.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for {@link LegacyColorStrip}.
 *
 * <p>Locks down the contract relied on by the {@code rtp menu} config-search
 * feature: each supported color/format syntax must strip to the expected
 * plain text, and the offset map returned by {@link LegacyColorStrip#strip2}
 * must round-trip stripped indices back to the correct raw input characters.
 */
class LegacyColorStripTest {

    private static final char S = '\u00A7';

    @Test
    void nullAndEmpty() {
        assertEquals("", LegacyColorStrip.strip(null));
        assertEquals("", LegacyColorStrip.strip(""));
        LegacyColorStrip.StripResult empty = LegacyColorStrip.strip2("");
        assertEquals("", empty.stripped);
        assertEquals(0, empty.strippedToRaw.length);
    }

    @Test
    void stripsSectionLegacyCodes() {
        String raw = S + "aHello " + S + "lWorld" + S + "r!";
        assertEquals("Hello World!", LegacyColorStrip.strip(raw));
    }

    @Test
    void stripsAmpersandLegacyCodes() {
        assertEquals("Hello World!", LegacyColorStrip.strip("&aHello &lWorld&r!"));
    }

    @Test
    void stripsBukkitHexRun() {
        // §x§r§r§g§g§b§b followed by content
        String hex = S + "x" + S + "1" + S + "2" + S + "3" + S + "4" + S + "5" + S + "6";
        assertEquals("hi", LegacyColorStrip.strip(hex + "hi"));
    }

    @Test
    void stripsAmpersandHexLiteral() {
        assertEquals("teleport", LegacyColorStrip.strip("&#aabbccteleport"));
    }

    @Test
    void stripsBareHashHexLiteral() {
        assertEquals("teleport", LegacyColorStrip.strip("#aabbccteleport"));
    }

    @Test
    void doesNotStripLoneAmpersandOrHash() {
        // & followed by a non-code char must be preserved. (B is a hex digit
        // and is a recognised legacy code, so we use 'z' which is not.)
        assertEquals("A&z C#z not-hex", LegacyColorStrip.strip("A&z C#z not-hex"));
    }

    @Test
    void offsetMapRoundTrips() {
        // Raw:  & a H e l l  o  _  & l W ...
        // Idx:  0 1 2 3 4 5  6  7  8 9 10
        String raw = "&aHello &lWorld";
        LegacyColorStrip.StripResult r = LegacyColorStrip.strip2(raw);
        assertEquals("Hello World", r.stripped);
        assertEquals(r.stripped.length(), r.strippedToRaw.length);
        // Each stripped char must map to an index in raw whose char matches.
        for (int i = 0; i < r.stripped.length(); i++) {
            int rawIdx = r.strippedToRaw[i];
            assertEquals(r.stripped.charAt(i), raw.charAt(rawIdx),
                    "mismatch at stripped idx " + i + " (raw idx " + rawIdx + ")");
        }
    }

    @Test
    void offsetMapWithMixedHexAndLegacy() {
        // Mix of §-codes, &#hex literal, plain text.
        String raw = S + "7" + "find " + "&#00ff00" + "this" + S + "r" + " value";
        LegacyColorStrip.StripResult r = LegacyColorStrip.strip2(raw);
        assertEquals("find this value", r.stripped);
        for (int i = 0; i < r.stripped.length(); i++) {
            int rawIdx = r.strippedToRaw[i];
            assertEquals(r.stripped.charAt(i), raw.charAt(rawIdx));
        }
    }

    @Test
    void caseInsensitiveSubstringSearchUsesStripped() {
        // Demonstrates the intended downstream usage: case-insensitive
        // substring match against the stripped form, with raw offsets
        // recoverable for highlight rendering.
        String raw = "&7You are not allowed to RTP &c&lhere&r.";
        LegacyColorStrip.StripResult r = LegacyColorStrip.strip2(raw);
        assertEquals("You are not allowed to RTP here.", r.stripped);
        int idx = r.stripped.toLowerCase().indexOf("here");
        assertEquals(27, idx);
        // Project back to raw indices.
        int rawStart = r.strippedToRaw[idx];
        int rawEnd = r.strippedToRaw[idx + 3]; // last matched char
        assertEquals('h', raw.charAt(rawStart));
        assertEquals('e', raw.charAt(rawEnd));
    }

    private static final char ZWSP = '\u200B';

    @Test
    void escapeNullAndEmpty() {
        assertEquals(null, LegacyColorStrip.escape(null));
        assertEquals("", LegacyColorStrip.escape(""));
    }

    @Test
    void escapeLeavesPlainTextUntouched() {
        String s = "how many tries before giving up";
        assertEquals(s, LegacyColorStrip.escape(s));
    }

    @Test
    void escapeAmpersandLegacyCode() {
        // & followed by a code gets a zero-width space inserted so the renderer
        // no longer translates it; the visible glyphs "&a" are preserved.
        assertEquals("&" + ZWSP + "aHi", LegacyColorStrip.escape("&aHi"));
    }

    @Test
    void escapeSectionLegacyCode() {
        assertEquals(S + "" + ZWSP + "aHi", LegacyColorStrip.escape(S + "aHi"));
    }

    @Test
    void escapeAmpersandHexLiteral() {
        assertEquals("&" + ZWSP + "#aabbccX", LegacyColorStrip.escape("&#aabbccX"));
    }

    @Test
    void escapeBareHashHexLiteral() {
        assertEquals("#" + ZWSP + "aabbccX", LegacyColorStrip.escape("#aabbccX"));
    }

    @Test
    void escapeBukkitHexRun() {
        String hex = S + "x" + S + "1" + S + "2" + S + "3" + S + "4" + S + "5" + S + "6";
        assertEquals(S + "" + ZWSP + hex.substring(1) + "hi",
                LegacyColorStrip.escape(hex + "hi"));
    }

    @Test
    void escapeLeavesLoneAmpersandOrHashUntouched() {
        String s = "A&z C#z not-hex";
        assertEquals(s, LegacyColorStrip.escape(s));
    }

    @Test
    void escapedCodesNoLongerStripAsColor() {
        // After escaping, the original code characters are visible literal text:
        // stripping the escaped form must not delete the 'a'/'6' glyphs.
        String escaped = LegacyColorStrip.escape("avoid yellow (&e/&6) here");
        String stripped = LegacyColorStrip.strip(escaped);
        org.junit.jupiter.api.Assertions.assertTrue(stripped.contains("&"),
                "ampersand must survive: " + stripped);
        org.junit.jupiter.api.Assertions.assertTrue(stripped.contains("e/"),
                "code letters must survive: " + stripped);
        org.junit.jupiter.api.Assertions.assertTrue(stripped.contains("6)"),
                "code letters must survive: " + stripped);
    }

    @Test
    void strippedToRawIsStrictlyMonotonic() {
        String raw = "&a&l&#112233ABC" + S + "xyz"; // §xyz is *not* a hex run (only 2 hex chars after x)
        LegacyColorStrip.StripResult r = LegacyColorStrip.strip2(raw);
        assertNotNull(r);
        for (int i = 1; i < r.strippedToRaw.length; i++) {
            // Each retained character is at a strictly greater raw offset.
            assert r.strippedToRaw[i] > r.strippedToRaw[i - 1]
                    : "offset map not monotonic at " + i;
        }
    }
}
