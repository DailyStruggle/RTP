package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.mock.ConfigurableMockChunk;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests covering {@link VerticalAdjustor} base class methods, defaults,
 * factory registration, equality comparisons, and default probe fallbacks.
 */
public class VerticalAdjustorBaseTest {

    @TempDir
    Path tempDir;

    private MockRTPWorld world;

    enum DummyKeys {
        minY,
        maxY,
        customObj
    }

    static class DummyAdjustor extends VerticalAdjustor<DummyKeys> {
        private final RTPCoords probeCoords;

        public DummyAdjustor(String name, EnumMap<DummyKeys, Object> def, @Nullable RTPCoords probeCoords) {
            super(DummyKeys.class, name, new ArrayList<>(), def);
            this.probeCoords = probeCoords;
        }

        @Override
        public @Nullable RTPCoords adjust(@NotNull RTPChunk input) {
            return null;
        }

        @Override
        public boolean adjust(@NotNull RTPChunk input, @NotNull MutableRTPCoords output) {
            return false;
        }

        @Override
        public boolean testPlacement(@NotNull RTPCoords coords) {
            for (var v : verifiers) {
                if (!v.test(coords)) return false;
            }
            return true;
        }

        @Override
        public Map<String, CommandParameter> getParameters() {
            return Collections.emptyMap();
        }

        @Override
        public int minY() {
            return getNumber(DummyKeys.minY, 0).intValue();
        }

        @Override
        public int maxY() {
            return getNumber(DummyKeys.maxY, 256).intValue();
        }

        @Override
        public @Nullable RTPCoords adjustFromProbe(@NotNull ChunkColumnProbe probe, @NotNull String worldName) {
            return probeCoords;
        }
    }

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        world = new MockRTPWorld("test_world");
    }

    @Test
    @DisplayName("Constructor registers adjustor in global factory if not already present")
    @SuppressWarnings("unchecked")
    void constructor_registersInFactory() {
        Factory<VerticalAdjustor<?>> factory =
                (Factory<VerticalAdjustor<?>>) RTP.factoryMap.get(RTP.factoryNames.vert);
        assertNotNull(factory);

        String uniqueName = "dummy_test_" + UUID.randomUUID().toString().replace("-", "");
        assertFalse(factory.contains(uniqueName));

        EnumMap<DummyKeys, Object> def = new EnumMap<>(DummyKeys.class);
        def.put(DummyKeys.minY, 10L);
        def.put(DummyKeys.maxY, 200L);
        def.put(DummyKeys.customObj, new ArrayList<>());

        DummyAdjustor adjustor = new DummyAdjustor(uniqueName, def, null);
        assertTrue(factory.contains(uniqueName));
        assertNotNull(factory.get(uniqueName));

        // Creating another with same name should not fail
        DummyAdjustor adjustor2 = new DummyAdjustor(uniqueName, def, null);
        assertTrue(factory.contains(uniqueName));
    }

    @Test
    @DisplayName("Default adjustColumn returns null")
    void defaultAdjustColumn_returnsNull() {
        EnumMap<DummyKeys, Object> def = new EnumMap<>(DummyKeys.class);
        DummyAdjustor adj = new DummyAdjustor("dummy_col", def, null);
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        assertNull(adj.adjustColumn(chunk, 7, 7));
    }

    @Test
    @DisplayName("Default requiresSkyLight returns false")
    void defaultRequiresSkyLight_returnsFalse() {
        EnumMap<DummyKeys, Object> def = new EnumMap<>(DummyKeys.class);
        DummyAdjustor adj = new DummyAdjustor("dummy_sky", def, null);
        assertFalse(adj.requiresSkyLight());
    }

    @Test
    @DisplayName("adjustFromProbeWithReason delegates to adjustFromProbe when non-null and returns OK")
    void adjustFromProbeWithReason_returnsOkWhenCoordsFound() {
        EnumMap<DummyKeys, Object> def = new EnumMap<>(DummyKeys.class);
        RTPCoords expected = new RTPCoords("test_world", 10, 64, 10);
        DummyAdjustor adj = new DummyAdjustor("dummy_probe_ok", def, expected);

        FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 256).withDefaultBlock("minecraft:air");
        VerticalAdjustor.AdjustResult res = adj.adjustFromProbeWithReason(probe, "test_world");

        assertEquals(VerticalAdjustor.ProbeRejectReason.NONE, res.reason());
        assertEquals(expected, res.picked());
    }

    @Test
    @DisplayName("adjustFromProbeWithReason returns SCAN_MISS_REJECT when adjustFromProbe returns null")
    void adjustFromProbeWithReason_returnsScanMissWhenNull() {
        EnumMap<DummyKeys, Object> def = new EnumMap<>(DummyKeys.class);
        DummyAdjustor adj = new DummyAdjustor("dummy_probe_null", def, null);

        FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 256).withDefaultBlock("minecraft:air");
        VerticalAdjustor.AdjustResult res = adj.adjustFromProbeWithReason(probe, "test_world");

        assertEquals(VerticalAdjustor.ProbeRejectReason.SCAN_MISS, res.reason());
        assertNull(res.picked());
    }

    @Test
    @DisplayName("equals behaves correctly across identical and differing data")
    void equals_comparesAdjustorDataAccurately() {
        EnumMap<DummyKeys, Object> def1 = new EnumMap<>(DummyKeys.class);
        def1.put(DummyKeys.minY, 10L);
        def1.put(DummyKeys.maxY, 200L);
        def1.put(DummyKeys.customObj, Collections.singletonList("a"));

        DummyAdjustor adj1 = new DummyAdjustor("dummy_eq_1", def1, null);

        EnumMap<DummyKeys, Object> def2 = new EnumMap<>(DummyKeys.class);
        def2.put(DummyKeys.minY, 10); // integer vs long
        def2.put(DummyKeys.maxY, 200.0); // double vs long
        def2.put(DummyKeys.customObj, Collections.singletonList("A")); // case-insensitive toString check

        DummyAdjustor adj2 = new DummyAdjustor("dummy_eq_2", def2, null);

        assertEquals(adj1, adj2, "Adjustors with matching numerical values and case-insensitive strings should be equal");

        // Different number
        EnumMap<DummyKeys, Object> defDiffNum = new EnumMap<>(DummyKeys.class);
        defDiffNum.put(DummyKeys.minY, 15L);
        defDiffNum.put(DummyKeys.maxY, 200L);
        defDiffNum.put(DummyKeys.customObj, Collections.singletonList("a"));
        DummyAdjustor adjDiffNum = new DummyAdjustor("dummy_diff_num", defDiffNum, null);
        assertNotEquals(adj1, adjDiffNum);

        // Different non-numeric object toString
        EnumMap<DummyKeys, Object> defDiffObj = new EnumMap<>(DummyKeys.class);
        defDiffObj.put(DummyKeys.minY, 10L);
        defDiffObj.put(DummyKeys.maxY, 200L);
        defDiffObj.put(DummyKeys.customObj, Collections.singletonList("b"));
        DummyAdjustor adjDiffObj = new DummyAdjustor("dummy_diff_obj", defDiffObj, null);
        assertNotEquals(adj1, adjDiffObj);

        // Different class / object
        assertNotEquals(adj1, "not an adjustor");
        assertNotEquals(null, adj1);
    }
}
