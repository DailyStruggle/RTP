package io.github.dailystruggle.rtp.common.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrackedObjectTest {

    @Test
    void getLabel_returnsLabel() {
        Object target = new Object();
        TrackedObject tracked = new TrackedObject(target, "myLabel", 1000);
        assertEquals("myLabel", tracked.getLabel());
    }

    @Test
    void getTarget_returnsTarget_whenNotCollected() {
        Object target = new Object();
        TrackedObject tracked = new TrackedObject(target, "label", 1000);
        assertSame(target, tracked.getTarget());
    }

    @Test
    void isCollected_false_whenTargetStillReachable() {
        Object target = new Object();
        TrackedObject tracked = new TrackedObject(target, "label", 1000);
        assertFalse(tracked.isCollected());
    }

    @Test
    void isLeaking_false_withinLifespan() {
        Object target = new Object();
        TrackedObject tracked = new TrackedObject(target, "label", Long.MAX_VALUE);
        assertFalse(tracked.isLeaking());
    }

    @Test
    void isLeaking_true_afterLifespanExpired() throws InterruptedException {
        Object target = new Object();
        TrackedObject tracked = new TrackedObject(target, "label", 1);
        Thread.sleep(10);
        assertTrue(tracked.isLeaking());
    }

    @Test
    void reset_resetsStartTime_soNotLeaking() throws InterruptedException {
        Object target = new Object();
        TrackedObject tracked = new TrackedObject(target, "label", 5);
        Thread.sleep(20);
        assertTrue(tracked.isLeaking());
        tracked.reset();
        assertFalse(tracked.isLeaking());
    }

    @Test
    void getLeakDuration_positiveAfterExpiry() throws InterruptedException {
        Object target = new Object();
        TrackedObject tracked = new TrackedObject(target, "label", 1);
        Thread.sleep(20);
        assertTrue(tracked.getLeakDuration() > 0);
    }

    @Test
    void getLeakDuration_negativeBeforeExpiry() {
        Object target = new Object();
        TrackedObject tracked = new TrackedObject(target, "label", Long.MAX_VALUE / 2);
        assertTrue(tracked.getLeakDuration() < 0);
    }

    @Test
    void matches_true_forSameInstance() {
        Object target = new Object();
        TrackedObject tracked = new TrackedObject(target, "label", 1000);
        assertTrue(tracked.matches(target));
    }

    @Test
    void matches_false_forDifferentInstance() {
        Object target = new Object();
        Object other = new Object();
        TrackedObject tracked = new TrackedObject(target, "label", 1000);
        assertFalse(tracked.matches(other));
    }

    @Test
    void matches_false_forNull() {
        Object target = new Object();
        TrackedObject tracked = new TrackedObject(target, "label", 1000);
        assertFalse(tracked.matches(null));
    }
}
