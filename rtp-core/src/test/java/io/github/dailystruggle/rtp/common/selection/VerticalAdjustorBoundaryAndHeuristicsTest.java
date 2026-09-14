package io.github.dailystruggle.rtp.common.selection;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.ConfigurableMockChunk;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.GenericVerticalAdjustorKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.fixed.FixedAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.fixed.FixedAdjustorKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustorKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("REQ-CORE-F-004: Vertical Adjustor Heuristics and Boundary Conditions")
public class VerticalAdjustorBoundaryAndHeuristicsTest {

    @TempDir
    File tempDir;

    private MockRTPWorld world;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        world = new MockRTPWorld("test_world");
    }

    private ConfigurableMockChunk createMockChunk(int solidTopY) {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        chunk.setSolidSafe(solidTopY);
        return chunk;
    }

    @Test
    @DisplayName("FixedAdjustor: Bounds, mid-air check, and probe heuristics")
    void testFixedAdjustorBoundaries() {
        FixedAdjustor adj = new FixedAdjustor(new ArrayList<>());
        adj.set(FixedAdjustorKeys.y, 100L);

        assertEquals(100, adj.minY());
        assertEquals(101, adj.maxY());
        assertNotNull(adj.getParameters());

        ConfigurableMockChunk chunk = createMockChunk(60);
        MutableRTPCoords out = new MutableRTPCoords("test_world", 0, 0, 0);

        boolean success = adj.adjust(chunk, out);
        assertTrue(success);
        assertEquals(100, out.y);

        RTPCoords coords = adj.adjust(chunk);
        assertNotNull(coords);
        assertEquals(100, coords.y());

        // When configured Y hits terrain/solid block (e.g. y=60 is solid), should reject
        adj.set(FixedAdjustorKeys.y, 60L);
        assertFalse(adj.adjust(chunk, out));
        assertNull(adj.adjust(chunk));
    }

    @Test
    @DisplayName("LinearAdjustor: Direction top-down (direction <= 0) vs bottom-up (direction > 0)")
    void testLinearAdjustorHeuristics() {
        LinearAdjustor down = new LinearAdjustor(new ArrayList<>());
        down.set(GenericVerticalAdjustorKeys.minY, 50L);
        down.set(GenericVerticalAdjustorKeys.maxY, 120L);
        down.set(GenericVerticalAdjustorKeys.direction, 0L); // top-down

        assertEquals(50, down.minY());
        assertEquals(120, down.maxY());
        assertNotNull(down.getParameters());

        // Solid top at y=64 -> safe stand at y=65
        ConfigurableMockChunk chunk = createMockChunk(64);
        MutableRTPCoords out = new MutableRTPCoords("test_world", 0, 0, 0);

        boolean successDown = down.adjust(chunk, out);
        assertTrue(successDown);
        assertEquals(65, out.y);

        // Linear adjustor bottom-up
        LinearAdjustor up = new LinearAdjustor(new ArrayList<>());
        up.set(GenericVerticalAdjustorKeys.minY, 50L);
        up.set(GenericVerticalAdjustorKeys.maxY, 120L);
        up.set(GenericVerticalAdjustorKeys.direction, 0L); // 0 is bottom-up in LinearAdjustor

        MutableRTPCoords outUp = new MutableRTPCoords("test_world", 0, 0, 0);
        boolean successUp = up.adjust(chunk, outUp);
        assertTrue(successUp);
        assertEquals(65, outUp.y);

        // AdjustColumn probe
        RTPCoords col = up.adjustColumn(chunk, 2, 2);
        assertNotNull(col);
        assertEquals(65, col.y());

        // Inverted bounds (minY > maxY): should gracefully return false / null
        LinearAdjustor inverted = new LinearAdjustor(new ArrayList<>());
        inverted.set(GenericVerticalAdjustorKeys.minY, 150L);
        inverted.set(GenericVerticalAdjustorKeys.maxY, 50L);
        inverted.set(GenericVerticalAdjustorKeys.direction, 0L);

        MutableRTPCoords outInv = new MutableRTPCoords("test_world", 0, 0, 0);
        assertFalse(inverted.adjust(chunk, outInv));
        assertNull(inverted.adjust(chunk));
        assertNull(inverted.adjustColumn(chunk, 2, 2));
    }

    @Test
    @DisplayName("JumpAdjustor: Step size, probe leaps, and bounds")
    void testJumpAdjustorHeuristics() {
        JumpAdjustor jump = new JumpAdjustor(new ArrayList<>());
        jump.set(JumpAdjustorKeys.minY, 50L);
        jump.set(JumpAdjustorKeys.maxY, 120L);
        jump.set(JumpAdjustorKeys.step, 1L);
        jump.set(JumpAdjustorKeys.requireSkyLight, false);

        assertEquals(50, jump.minY());
        assertEquals(120, jump.maxY());
        assertNotNull(jump.getParameters());

        ConfigurableMockChunk chunk = createMockChunk(70);
        MutableRTPCoords out = new MutableRTPCoords("test_world", 0, 0, 0);

        boolean success = jump.adjust(chunk, out);
        assertTrue(success);
        assertEquals(71, out.y);

        RTPCoords coords = jump.adjust(chunk);
        assertNotNull(coords);
        assertEquals(71, coords.y());

        RTPCoords col = jump.adjustColumn(chunk, 5, 5);
        assertNotNull(col);
        assertEquals(71, col.y());

        // Inverted bounds: minY > maxY
        JumpAdjustor invJump = new JumpAdjustor(new ArrayList<>());
        invJump.set(JumpAdjustorKeys.minY, 200L);
        invJump.set(JumpAdjustorKeys.maxY, 50L);
        invJump.set(JumpAdjustorKeys.step, 4L);
        invJump.set(JumpAdjustorKeys.requireSkyLight, false);

        MutableRTPCoords outInv = new MutableRTPCoords("test_world", 0, 0, 0);
        assertFalse(invJump.adjust(chunk, outInv));
        assertNull(invJump.adjust(chunk));
        assertNull(invJump.adjustColumn(chunk, 5, 5));
    }
}
