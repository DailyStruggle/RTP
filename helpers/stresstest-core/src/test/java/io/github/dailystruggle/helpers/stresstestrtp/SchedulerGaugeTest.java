package io.github.dailystruggle.helpers.stresstestrtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SchedulerGaugeTest {

    @Test
    @DisplayName("window row reports last/max/mean in-flight, at-cap fraction and queue sizes")
    void windowRow() {
        SchedulerGauge.Window w = new SchedulerGauge.Window();
        w.add(2, 8, 0);
        w.add(8, 8, 3);
        w.add(8, 8, 5);
        w.add(6, 8, 1);
        assertEquals("1000,6,8,6.00,0.500,8,1,5,4", w.row(1000L));
        assertEquals(SchedulerGauge.HEADER.split(",").length, w.row(1L).split(",").length);
    }

    @Test
    @DisplayName("empty window does not divide by zero")
    void emptyWindow() {
        assertEquals("5,0,0,0.00,0.000,0,0,0,0", new SchedulerGauge.Window().row(5L));
    }

    public static final class Inner {
        public int getInFlightRegionalTasks() { return 1; }
    }

    public static final class Decorator {
        private final Object d;
        Decorator(Object d) { this.d = d; }
        public Object delegate() { return d; }
    }

    @Test
    @DisplayName("unwrap peels decorators to the object exposing the in-flight getter")
    void unwrapDecorators() {
        Inner inner = new Inner();
        assertSame(inner, SchedulerGauge.unwrap(new Decorator(new Decorator(inner))));
        assertSame(inner, SchedulerGauge.unwrap(inner));
        Object plain = new Object();
        assertSame(plain, SchedulerGauge.unwrap(plain));
    }
}
