package io.github.dailystruggle.rtp.common.configuration.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RtpYamlScalar value coercion")
class RtpYamlScalarTest {

    private static Object plain(String raw) {
        return new RtpYamlScalar(raw, RtpYamlScalar.Style.PLAIN).value();
    }

    @Test
    @DisplayName("Null raw value and null style default to empty/PLAIN")
    void nullDefaults() {
        RtpYamlScalar sc = new RtpYamlScalar(null, null);
        assertEquals("", sc.rawValue());
        assertEquals(RtpYamlScalar.Style.PLAIN, sc.style());
        assertEquals("", sc.value());
    }

    @Test
    @DisplayName("Non-plain scalars are always returned as their raw string")
    void quotedAlwaysString() {
        assertEquals("42", new RtpYamlScalar("42", RtpYamlScalar.Style.SINGLE).value());
        assertEquals("true", new RtpYamlScalar("true", RtpYamlScalar.Style.DOUBLE).value());
    }

    @Test
    @DisplayName("null / ~ coerce to a Java null")
    void nullTokens() {
        assertNull(plain("null"));
        assertNull(plain("~"));
    }

    @Test
    @DisplayName("Boolean tokens coerce to Boolean")
    void booleans() {
        assertEquals(Boolean.TRUE, plain("true"));
        assertEquals(Boolean.FALSE, plain("false"));
    }

    @Test
    @DisplayName("Integers within int range coerce to Integer; larger to Long")
    void integers() {
        assertEquals(Integer.class, plain("42").getClass());
        assertEquals(42, plain("42"));
        assertEquals(Long.class, plain("3000000000").getClass());
        assertEquals(3000000000L, plain("3000000000"));
    }

    @Test
    @DisplayName("Doubles require a '.' or exponent marker")
    void doubles() {
        assertEquals(1.5, plain("1.5"));
        assertEquals(1000.0, plain("1e3"));
        // A bare token without '.'/'e' that isn't an int stays a string.
        assertEquals("v1", plain("v1"));
    }

    @Test
    @DisplayName("Unparseable numeric-looking tokens fall back to the raw string")
    void unparseableFallsThrough() {
        assertEquals("1e", plain("1e"));
        assertEquals("hello", plain("hello"));
    }
}
