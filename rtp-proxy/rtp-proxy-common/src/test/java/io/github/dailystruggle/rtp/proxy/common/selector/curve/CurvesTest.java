package io.github.dailystruggle.rtp.proxy.common.selector.curve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the curve catalogue from rtp-proxy-ADR-004 (linear, exponential,
 * logarithmic, sigmoid, step, power). Verifies endpoint anchoring
 * ({@code f(0)=0, f(1)=1} for monotone curves), monotonicity, input
 * clamping, and config parsing.
 */
class CurvesTest {

    private static final double EPS = 1e-9;

    @Nested
    @DisplayName("Endpoint anchoring: monotone curves map {0,1} -> {0,1}")
    class Endpoints {
        @Test void linear()      { assertEndpoints(Curves.linear()); }
        @Test void exponential() { assertEndpoints(Curves.exponential(3.0)); }
        @Test void logarithmic() { assertEndpoints(Curves.logarithmic(9.0)); }
        @Test void sigmoid()     { assertEndpoints(Curves.sigmoid(8.0, 0.5)); }
        @Test void power()       { assertEndpoints(Curves.power(2.0)); }

        private void assertEndpoints(Curve c) {
            assertEquals(0.0, c.apply(0.0), EPS, c.id() + " f(0)");
            assertEquals(1.0, c.apply(1.0), EPS, c.id() + " f(1)");
        }
    }

    @Nested
    @DisplayName("Monotonicity across [0,1]")
    class Monotonic {
        @Test void all() {
            Curve[] curves = {
                    Curves.linear(),
                    Curves.exponential(3.0),
                    Curves.logarithmic(9.0),
                    Curves.sigmoid(8.0, 0.5),
                    Curves.power(2.0)
            };
            for (Curve c : curves) {
                double prev = c.apply(0.0);
                for (int i = 1; i <= 100; i++) {
                    double x = i / 100.0;
                    double y = c.apply(x);
                    assertTrue(y + EPS >= prev,
                            c.id() + " not monotonic at x=" + x + ": " + prev + " -> " + y);
                    prev = y;
                }
            }
        }
    }

    @Test
    @DisplayName("Input outside [0,1] is clamped")
    void clamping() {
        for (Curve c : new Curve[]{
                Curves.linear(), Curves.exponential(3.0), Curves.logarithmic(9.0),
                Curves.sigmoid(8.0, 0.5), Curves.power(2.0), Curves.step(0.5)}) {
            assertEquals(c.apply(0.0), c.apply(-5.0), EPS, c.id() + " negative input");
            assertEquals(c.apply(1.0), c.apply(99.0), EPS, c.id() + " above-1 input");
        }
    }

    @Test
    @DisplayName("Step curve flips at threshold")
    void stepThreshold() {
        Curve s = Curves.step(0.5);
        assertEquals(0.0, s.apply(0.49));
        assertEquals(1.0, s.apply(0.50));
        assertEquals(1.0, s.apply(0.51));
    }

    @Test
    @DisplayName("Exponential is convex (f(0.5) < 0.5)")
    void exponentialConvex() {
        assertTrue(Curves.exponential(3.0).apply(0.5) < 0.5);
    }

    @Test
    @DisplayName("Logarithmic is concave (f(0.5) > 0.5)")
    void logarithmicConcave() {
        assertTrue(Curves.logarithmic(9.0).apply(0.5) > 0.5);
    }

    @Test
    @DisplayName("Sigmoid is centered at x0 = 0.5 (renormalized)")
    void sigmoidMidpoint() {
        // After renormalization the midpoint maps to ~0.5.
        assertEquals(0.5, Curves.sigmoid(8.0, 0.5).apply(0.5), 1e-3);
    }

    @Test
    @DisplayName("Power p=2 yields x^2")
    void powerSquared() {
        Curve p2 = Curves.power(2.0);
        assertEquals(0.25, p2.apply(0.5), EPS);
        assertEquals(0.81, p2.apply(0.9), EPS);
    }

    @Test
    @DisplayName("Parameter bounds are clamped (k=999 -> k=20, k=0 -> k=0.1)")
    void parameterClamping() {
        // No exception; result remains well-formed (monotonic, [0,1]).
        assertEquals(1.0, Curves.exponential(999).apply(1.0), EPS);
        assertEquals(0.0, Curves.exponential(999).apply(0.0), EPS);
        assertEquals(1.0, Curves.exponential(0).apply(1.0), EPS);
    }

    @Nested
    @DisplayName("fromConfig YAML parsing")
    class FromConfig {
        @Test void defaultIsLinear() {
            assertEquals("linear", Curves.fromConfig(Map.of()).id());
        }
        @Test void linear() {
            assertEquals("linear", Curves.fromConfig(Map.of("type", "linear")).id());
        }
        @Test void sigmoidWithParams() {
            Curve c = Curves.fromConfig(Map.of("type", "sigmoid", "k", 4.0, "x0", 0.25));
            assertEquals("sigmoid", c.id());
            assertEquals(0.0, c.apply(0.0), EPS);
            assertEquals(1.0, c.apply(1.0), EPS);
        }
        @Test void aliasesAccepted() {
            assertEquals("exponential", Curves.fromConfig(Map.of("type", "EXP")).id());
            assertEquals("logarithmic", Curves.fromConfig(Map.of("type", "log")).id());
            assertEquals("sigmoid",     Curves.fromConfig(Map.of("type", "s-curve")).id());
            assertEquals("power",       Curves.fromConfig(Map.of("type", "POW")).id());
        }
        @Test void unknownTypeThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> Curves.fromConfig(Map.of("type", "bogus")));
        }
    }
}
