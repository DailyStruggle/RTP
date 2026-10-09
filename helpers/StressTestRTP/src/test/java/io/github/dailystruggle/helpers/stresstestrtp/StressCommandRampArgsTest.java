package io.github.dailystruggle.helpers.stresstestrtp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StressCommandRampArgsTest {

    @Test
    @DisplayName("ramp rates: single value gives a flat one-stage run")
    void flatRate() {
        assertArrayEquals(new double[]{100.0}, StressCommand.parseRates("100"));
    }

    @Test
    @DisplayName("ramp rates: comma list keeps order and tolerates spaces")
    void list() {
        assertArrayEquals(new double[]{20.0, 50.0, 100.5}, StressCommand.parseRates("20, 50,100.5"));
    }

    @Test
    @DisplayName("ramp rates: empty, non-numeric, zero, negative or empty entries are rejected")
    void invalid() {
        assertNull(StressCommand.parseRates(""));
        assertNull(StressCommand.parseRates(null));
        assertNull(StressCommand.parseRates("abc"));
        assertNull(StressCommand.parseRates("0"));
        assertNull(StressCommand.parseRates("-5"));
        assertNull(StressCommand.parseRates("10,,20"));
        assertNull(StressCommand.parseRates("NaN"));
        assertNull(StressCommand.parseRates("Infinity"));
    }
}
