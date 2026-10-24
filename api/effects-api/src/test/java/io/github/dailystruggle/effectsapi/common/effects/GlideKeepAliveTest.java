package io.github.dailystruggle.effectsapi.common.effects;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RTP-70: GLIDE keep-alive holds the glide on Fabric/NeoForge until landing")
class GlideKeepAliveTest {

    /** Mimics vanilla without an elytra: the platform tick clears the glide flag. */
    private static class FakeGlider implements GlideKeepAlive.Glider {
        final UUID id = UUID.randomUUID();
        boolean gone;
        boolean landed = true; // stale onGround until the client acknowledges the lift
        boolean gliding = true;
        int starts;
        int stops;
        int fallResets;

        void vanillaTick() {
            gliding = false;
        }

        @Override public @NotNull UUID id() { return id; }
        @Override public boolean isGone() { return gone; }
        @Override public boolean isLanded() { return landed; }
        @Override public boolean isGliding() { return gliding; }
        @Override public void startGliding() { gliding = true; starts++; }
        @Override public void stopGliding() { gliding = false; stops++; }
        @Override public void resetFallDistance() { fallResets++; }
    }

    @AfterEach
    void tearDown() {
        GlideKeepAlive.clear();
    }

    @Test
    @DisplayName("re-applies the glide and resets fall distance every tick while airborne")
    void reappliesGlideWhileAirborne() {
        FakeGlider g = new FakeGlider();
        GlideKeepAlive.track(g, Integer.MAX_VALUE);
        g.landed = false;
        for (int i = 0; i < 100; i++) {
            g.vanillaTick();
            GlideKeepAlive.tick();
            assertTrue(g.gliding, "glide must be restored after tick " + i);
        }
        assertEquals(100, g.starts);
        assertEquals(100, g.fallResets);
        assertTrue(GlideKeepAlive.isTracked(g.id));
    }

    @Test
    @DisplayName("stale onGround right after the lift does not end the glide")
    void staleGroundBeforeFirstAirborneSampleIsIgnored() {
        FakeGlider g = new FakeGlider();
        GlideKeepAlive.track(g, Integer.MAX_VALUE);
        for (int i = 0; i < 3; i++) {
            g.vanillaTick();
            GlideKeepAlive.tick();
        }
        assertTrue(GlideKeepAlive.isTracked(g.id));
        assertTrue(g.gliding);
        assertEquals(0, g.stops);
    }

    @Test
    @DisplayName("landing after an airborne sample stops the glide and drops the entry")
    void landingEndsGlide() {
        FakeGlider g = new FakeGlider();
        GlideKeepAlive.track(g, Integer.MAX_VALUE);
        g.landed = false;
        GlideKeepAlive.tick();
        g.landed = true;
        GlideKeepAlive.tick();
        assertFalse(GlideKeepAlive.isTracked(g.id));
        assertEquals(1, g.stops);
    }

    @Test
    @DisplayName("landing timeout forces a landing")
    void timeoutForcesLanding() {
        FakeGlider g = new FakeGlider();
        g.landed = false;
        GlideKeepAlive.track(g, 5);
        for (int i = 0; i < 5; i++) GlideKeepAlive.tick();
        assertTrue(GlideKeepAlive.isTracked(g.id));
        GlideKeepAlive.tick();
        assertFalse(GlideKeepAlive.isTracked(g.id));
        assertEquals(1, g.stops);
    }

    @Test
    @DisplayName("a lift that never leaves the ground is given up after the grace window")
    void failedLiftIsDropped() {
        FakeGlider g = new FakeGlider();
        GlideKeepAlive.track(g, Integer.MAX_VALUE);
        for (int i = 0; i < GlideKeepAlive.LIFT_GRACE_TICKS; i++) GlideKeepAlive.tick();
        assertTrue(GlideKeepAlive.isTracked(g.id));
        GlideKeepAlive.tick();
        assertFalse(GlideKeepAlive.isTracked(g.id));
    }

    @Test
    @DisplayName("removed players are dropped without touching them; a throwing glider is dropped")
    void goneAndFailingGlidersAreDropped() {
        FakeGlider gone = new FakeGlider();
        gone.gone = true;
        GlideKeepAlive.track(gone, Integer.MAX_VALUE);
        FakeGlider broken = new FakeGlider() {
            @Override public boolean isLanded() { throw new NoSuchMethodError("onGround"); }
        };
        GlideKeepAlive.track(broken, Integer.MAX_VALUE);

        GlideKeepAlive.tick();

        assertFalse(GlideKeepAlive.isTracked(gone.id));
        assertFalse(GlideKeepAlive.isTracked(broken.id));
        assertEquals(0, gone.stops);
    }
}
