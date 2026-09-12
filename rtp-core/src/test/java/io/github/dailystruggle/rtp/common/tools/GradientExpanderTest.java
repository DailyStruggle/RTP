package io.github.dailystruggle.rtp.common.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GradientExpander} (ENTERPRISE_READINESS item 19, {@code tools} package).
 *
 * <p>{@code GradientExpander} is a pure, platform-neutral static utility that lowers
 * MiniMessage {@code <gradient>}/{@code <transition>}/{@code <rainbow>} tags into
 * per-character legacy hex color codes ({@code \u00a7x\u00a7r\u00a7r...}) for the
 * no-Adventure path. Every case here drives the only public entry point,
 * {@link GradientExpander#expand(String)}, and asserts on the emitted legacy codes.
 */
public class GradientExpanderTest {

    private static final char SECTION = '\u00a7';

    /** Reconstructs the {@code \u00a7xRRGGBB} legacy hex marker the class emits. */
    private static String legacyHex(int rgb) {
        String h = String.format("%06x", rgb & 0xFFFFFF);
        StringBuilder sb = new StringBuilder().append(SECTION).append('x');
        for (char c : h.toCharArray()) {
            sb.append(SECTION).append(c);
        }
        return sb.toString();
    }

    // ---- null / no-tag passthrough ----

    @Test
    void nullInputYieldsEmptyString() {
        assertEquals("", GradientExpander.expand(null));
    }

    @Test
    void plainTextWithoutTagsIsUnchanged() {
        String in = "hello world " + SECTION + "acolored";
        assertEquals(in, GradientExpander.expand(in), "no gradient tag => verbatim passthrough");
    }

    // ---- gradient endpoints ----

    @Test
    void twoStopGradientColorsFirstAndLastCharAtEndpoints() {
        String out = GradientExpander.expand("<gradient:#ff0000:#0000ff>AB</gradient>");
        // First visible char resolves to the start color, last to the end color.
        assertTrue(out.contains(legacyHex(0xff0000) + "A"),
                "first char must carry the start color: " + out);
        assertTrue(out.contains(legacyHex(0x0000ff) + "B"),
                "last char must carry the end color: " + out);
        assertTrue(out.endsWith(SECTION + "r"),
                "a colorized expansion must be terminated with a legacy reset");
    }

    @Test
    void singleVisibleCharGradientUsesStartColor() {
        String out = GradientExpander.expand("<gradient:#112233:#445566>Z</gradient>");
        assertTrue(out.contains(legacyHex(0x112233) + "Z"),
                "a one-char gradient pins t=0 (start color): " + out);
    }

    // ---- named colors + default fallback ----

    @Test
    void namedColorsAreResolved() {
        String out = GradientExpander.expand("<gradient:red:blue>AB</gradient>");
        assertTrue(out.contains(legacyHex(0xFF5555) + "A"), "named 'red' start: " + out);
        assertTrue(out.contains(legacyHex(0x5555FF) + "B"), "named 'blue' end: " + out);
    }

    @Test
    void gradientWithFewerThanTwoStopsFallsBackToWhiteToBlack() {
        String out = GradientExpander.expand("<gradient:red>AB</gradient>");
        // Single stop < 2 => default white(255,255,255) -> black(0,0,0).
        assertTrue(out.contains(legacyHex(0xFFFFFF) + "A"), "fallback start is white: " + out);
        assertTrue(out.contains(legacyHex(0x000000) + "B"), "fallback end is black: " + out);
    }

    @Test
    void bareGradientTagFallsBackToWhiteToBlack() {
        String out = GradientExpander.expand("<gradient>AB</gradient>");
        assertTrue(out.contains(legacyHex(0xFFFFFF) + "A"), out);
        assertTrue(out.contains(legacyHex(0x000000) + "B"), out);
    }

    @Test
    void unrecognisedColorArgIsSkipped() {
        // "notacolor" parses as neither hex nor named nor float => skipped, leaving
        // one usable stop and thus the white->black fallback.
        String out = GradientExpander.expand("<gradient:notacolor:#00ff00>AB</gradient>");
        assertTrue(out.contains(legacyHex(0xFFFFFF) + "A"), out);
        assertTrue(out.contains(legacyHex(0x000000) + "B"), out);
    }

    @Test
    void malformedHexArgIsRejected() {
        // "#zzzzzz" is 7 chars but not valid hex => parseColor returns null => skipped.
        String out = GradientExpander.expand("<gradient:#zzzzzz:#ffffff>AB</gradient>");
        assertTrue(out.contains(legacyHex(0xFFFFFF) + "A"), out);
        assertTrue(out.contains(legacyHex(0x000000) + "B"), out);
    }

    // ---- three-stop interpolation ----

    @Test
    void threeStopGradientHitsMiddleStopExactly() {
        String out = GradientExpander.expand(
                "<gradient:#000000:#ffffff:#000000>ABC</gradient>");
        // t for 3 chars: 0, 0.5, 1 => stop0, stop1(middle), stop2.
        assertTrue(out.contains(legacyHex(0x000000) + "A"), out);
        assertTrue(out.contains(legacyHex(0xffffff) + "B"), "middle char hits the middle stop: " + out);
        assertTrue(out.contains(legacyHex(0x000000) + "C"), out);
    }

    // ---- phase (cyclic wrap) ----

    @Test
    void nonZeroPhaseWrapsGradientButStillColorizes() {
        String out = GradientExpander.expand("<gradient:#ff0000:#0000ff:0.5>ABCD</gradient>");
        assertTrue(out.indexOf(SECTION + "x") >= 0, "phase gradient still emits hex codes: " + out);
        assertTrue(out.endsWith(SECTION + "r"), out);
        for (char c : new char[] {'A', 'B', 'C', 'D'}) {
            assertTrue(out.indexOf(c) >= 0, "visible char '" + c + "' preserved: " + out);
        }
    }

    // ---- transition: one uniform color for every char ----

    @Test
    void transitionAppliesOneColorToEveryChar() {
        // phase 0 => interpolate at t=0 => start color for all chars.
        String out = GradientExpander.expand("<transition:#ff0000:#0000ff:0>AB</transition>");
        assertTrue(out.contains(legacyHex(0xff0000) + "A"), out);
        assertTrue(out.contains(legacyHex(0xff0000) + "B"),
                "transition uses the same color for every char: " + out);
        assertTrue(out.endsWith(SECTION + "r"), out);
    }

    @Test
    void transitionPhaseIsClampedToEnd() {
        // phase 2.0 clamps to 1.0 => end color.
        String out = GradientExpander.expand("<transition:#ff0000:#0000ff:2.0>A</transition>");
        assertTrue(out.contains(legacyHex(0x0000ff) + "A"),
                "transition phase > 1 clamps to the end color: " + out);
    }

    @Test
    void transitionWithDefaultStops() {
        String out = GradientExpander.expand("<transition>AB</transition>");
        assertTrue(out.contains(legacyHex(0xFFFFFF) + "A"), out);
        assertTrue(out.contains(legacyHex(0xFFFFFF) + "B"), out);
    }

    // ---- rainbow ----

    @Test
    void rainbowColorizesEveryCharAndResets() {
        String out = GradientExpander.expand("<rainbow>ABCDE</rainbow>");
        assertTrue(out.indexOf(SECTION + "x") >= 0, "rainbow emits legacy hex: " + out);
        assertTrue(out.endsWith(SECTION + "r"), out);
        for (char c : new char[] {'A', 'B', 'C', 'D', 'E'}) {
            assertTrue(out.indexOf(c) >= 0, out);
        }
    }

    @Test
    void rainbowReverseAndPhaseAreAccepted() {
        String out = GradientExpander.expand("<rainbow:!120>ABCDE</rainbow>");
        assertTrue(out.indexOf(SECTION + "x") >= 0, "reverse+phase rainbow still colorizes: " + out);
        assertTrue(out.endsWith(SECTION + "r"), out);
    }

    @Test
    void rainbowWithInvalidPhaseArgIsTolerated() {
        String out = GradientExpander.expand("<rainbow:notanumber>AB</rainbow>");
        assertTrue(out.indexOf(SECTION + "x") >= 0, out);
    }

    @Test
    void rainbowSingleCharUsesHueZero() {
        String out = GradientExpander.expand("<rainbow>Q</rainbow>");
        assertTrue(out.indexOf(SECTION + "x") >= 0, out);
        assertTrue(out.indexOf('Q') >= 0, out);
    }

    // ---- empty inner text: no color, no reset ----

    @Test
    void emptyInnerTextProducesNoColorCodes() {
        String out = GradientExpander.expand("<gradient:#ff0000:#0000ff></gradient>");
        assertEquals("", out, "empty gradient body => empty expansion, no dangling reset");
    }

    @Test
    void rainbowEmptyBodyProducesNothing() {
        assertEquals("", GradientExpander.expand("<rainbow></rainbow>"));
    }

    // ---- pre-existing legacy codes in the body are preserved, not doubled ----

    @Test
    void existingLegacyColorCodeSuppressesGradientColoringForThatRun() {
        // A nested legacy color code (\u00a7c) marks the run as already-colored, so
        // the gradient does not inject its own hex before those chars.
        String out = GradientExpander.expand("<gradient:#ff0000:#0000ff>" + SECTION + "cXY</gradient>");
        assertTrue(out.contains(SECTION + "cXY"),
                "the nested legacy code and its chars survive verbatim: " + out);
    }

    @Test
    void legacyFormatCodeIsCopiedVerbatim() {
        // \u00a7l (bold) is a format code, not a color; it is copied through and does
        // not flip the nested-colored state, so following chars still get gradient hex.
        String out = GradientExpander.expand("<gradient:#ff0000:#0000ff>" + SECTION + "lAB</gradient>");
        assertTrue(out.contains(SECTION + "l"), "bold format code preserved: " + out);
        assertTrue(out.indexOf(SECTION + "x") >= 0, "gradient still colorizes around a format code: " + out);
    }

    @Test
    void legacyResetInBodyReenablesColoring() {
        // \u00a7r resets nested-colored back to false mid-run.
        String out = GradientExpander.expand("<gradient:#ff0000:#0000ff>" + SECTION + "cX" + SECTION + "rY</gradient>");
        assertTrue(out.contains(SECTION + "cX"), out);
        // After the reset, Y is colorized again by the gradient.
        assertTrue(out.contains(SECTION + "x"), out);
    }

    // ---- nested tags ----

    @Test
    void nestedRainbowInsideGradientIsExpandedNotPrinted() {
        String out = GradientExpander.expand(
                "<gradient:#ff0000:#0000ff>pre<rainbow>IN</rainbow>post</gradient>");
        assertFalse(out.contains("<rainbow>"), "inner tag markup must be lowered, not printed: " + out);
        assertFalse(out.contains("</rainbow>"), out);
        // Each visible char carries its own hex prefix, so the words are not
        // contiguous - assert the individual characters survive in order instead.
        for (char c : "preINpost".toCharArray()) {
            assertTrue(out.indexOf(c) >= 0, "visible char '" + c + "' preserved: " + out);
        }
    }

    // ---- surrounding text is preserved around the tag ----

    @Test
    void textAroundTagIsPreserved() {
        String out = GradientExpander.expand("before <rainbow>MID</rainbow> after");
        assertTrue(out.startsWith("before "), out);
        assertTrue(out.endsWith(" after"), out);
    }

    @Test
    void unknownTagNameLeavesInnerButStripsMarkup() {
        // The TAG_PATTERN only matches gradient/transition/rainbow, so a foreign
        // tag is left untouched entirely.
        String in = "<bogus:1>ABC</bogus>";
        assertEquals(in, GradientExpander.expand(in), "non-color tag is not a match => verbatim");
    }
}
