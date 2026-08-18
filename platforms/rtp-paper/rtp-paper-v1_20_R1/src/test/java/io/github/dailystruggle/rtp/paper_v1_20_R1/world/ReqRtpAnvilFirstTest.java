package io.github.dailystruggle.rtp.paper_v1_20_R1.world;

import io.github.dailystruggle.rtp.bukkitplatform.world.BukkitRTPWorld;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Regression guard for ADR-016 §13.1-§13.2: the Anvil-first chunk-data
 * precedence rule requires every Bukkit-family adapter in scope (Spigot,
 * Paper, Folia) to consult the on-disk {@code .mca} palette <i>before</i>
 * any live {@code world.getBiome(...)}/{@code world.getChunkAt(...)} call.
 *
 * <p>Prior to ADR-016 §13, {@code PaperRTPWorld} carried a
 * {@code getChunkAt(int, int)} override that short-circuited directly to
 * {@code world.getChunkAtAsync(cx, cz)} with no Anvil probe - silently
 * bypassing the pre-filter and defeating the §13.1 precedence rule. The
 * override has since been removed; Paper now inherits the
 * {@link BukkitRTPWorld} implementation verbatim, which routes every
 * candidate through {@code anvilProbeSupport.probeAndPublish(...)} before
 * falling through to the reflective async live-load (which resolves to
 * Paper's native {@code World#getChunkAtAsync(int, int)}).
 *
 * <p>This test pins that invariant: if a future refactor re-introduces a
 * {@code PaperRTPWorld#getChunkAt} override (or any other direct override
 * of a chunk/biome getter on {@code BukkitRTPWorld}), the test fails
 * loudly with a reference back to ADR-016 §13.2.
 */
class ReqRtpAnvilFirstTest {

    /**
     * {@code PaperRTPWorld} must not declare its own {@code getChunkAt}
     * method - it must inherit the parent Spigot implementation that
     * threads through the Anvil pre-filter (ADR-016 §13.2).
     */
    @Test
    void paperRTPWorld_doesNotOverrideGetChunkAt() throws NoSuchMethodException {
        Method m = PaperRTPWorld.class.getMethod("getChunkAt", int.class, int.class);
        assertSame(BukkitRTPWorld.class, m.getDeclaringClass(),
                "ADR-016 §13.2: PaperRTPWorld must inherit BukkitRTPWorld#getChunkAt "
                        + "so every candidate is threaded through the Anvil pre-filter "
                        + "before the live async chunk load. Re-adding a Paper-side "
                        + "override bypasses the §13.1 chunk-data precedence rule.");
    }

    /**
     * The same invariant applies to {@code getBiome} - a Paper override that
     * reads {@code world.getBiome(...)} directly would defeat the
     * Anvil-first biome resolution path established in
     * {@code BukkitRTPWorld#getBiome}.
     */
    @Test
    void paperRTPWorld_doesNotOverrideGetBiome() throws NoSuchMethodException {
        Method m = PaperRTPWorld.class.getMethod("getBiome", int.class, int.class, int.class);
        assertEquals(BukkitRTPWorld.class, m.getDeclaringClass(),
                "ADR-016 §13.2: PaperRTPWorld must inherit BukkitRTPWorld#getBiome "
                        + "so the Anvil cache is consulted before any live world.getBiome(...) "
                        + "call.");
    }
}
