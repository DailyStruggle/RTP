package io.github.dailystruggle.bytecode.transpile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.BinaryOperator;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptExpr;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptFunction;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptIrEvaluator;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptLut;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptModule;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptParam;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptStmt;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptType;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.EffectKind;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.EffectProfile;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.VoxelEffectContext;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Gate 1 tests for {@code leafgen-ADR-009} Tier-1 stateful voxel-effect IR primitives,
 * live-registry tag/property/fluid LUTs, compiler-inferred {@link EffectProfile},
 * and OpenCL C99 lowering.
 */
final class StatefulVoxelEffectIrTest {

    @Test
    void executesVoxelEffectPrimitivesAndTagPropertyLuts() {
        // Slot 0 = air, Slot 1 = stone (tag bit 1), Slot 2 = water (fluid=1), Slot 3 = dripstone[waterlogged=true]
        ConceptLut tagLut = BytecodeConceptIr.buildTagMaskLut("TAG_MASK_LUT", new int[] {0, 1, 0, 0});
        ConceptLut propLut = BytecodeConceptIr.buildPropertyTransitionLut("PROP_TRANS_LUT", new int[] {0, 1, 2, 3});
        ConceptLut fluidLut = BytecodeConceptIr.buildFluidFlagLut("FLUID_FLAG_LUT", new int[] {0, 0, 1, 1});

        ConceptExpr lx = new ConceptExpr.BinaryOp(
                BinaryOperator.BIT_AND,
                new ConceptExpr.Var("x", ConceptType.I32),
                new ConceptExpr.IntConst(15),
                ConceptType.I32);
        ConceptExpr lz = new ConceptExpr.BinaryOp(
                BinaryOperator.BIT_AND,
                new ConceptExpr.Var("z", ConceptType.I32),
                new ConceptExpr.IntConst(15),
                ConceptType.I32);
        ConceptExpr y = new ConceptExpr.Var("y", ConceptType.I32);

        ConceptFunction fn = new ConceptFunction(
                "test/SyntheticFeature",
                "place",
                "(IIIJJ)I",
                List.of(
                        new ConceptParam("x", ConceptType.I32, 0),
                        new ConceptParam("y", ConceptType.I32, 1),
                        new ConceptParam("z", ConceptType.I32, 2),
                        new ConceptParam("s0", ConceptType.I64, 3),
                        new ConceptParam("s1", ConceptType.I64, 5)),
                ConceptType.I32,
                Map.of(
                        "cur", ConceptType.I32,
                        "isFluid", ConceptType.I32,
                        "rng", ConceptType.I64),
                List.of(
                        new ConceptStmt.Assign("cur", ConceptType.I32, new ConceptExpr.VoxelRead(lx, y, lz)),
                        new ConceptStmt.Assign(
                                "isFluid",
                                ConceptType.I32,
                                new ConceptExpr.LutLoad("FLUID_FLAG_LUT", new ConceptExpr.Var("cur", ConceptType.I32), ConceptType.I32)),
                        new ConceptStmt.Assign(
                                "rng",
                                ConceptType.I64,
                                new ConceptExpr.RngStep(
                                        "xoroshiro128pp",
                                        new ConceptExpr.Var("s0", ConceptType.I64),
                                        new ConceptExpr.Var("s1", ConceptType.I64),
                                        null,
                                        ConceptType.I64)),
                        new ConceptStmt.BitSetWrite("carveMask", lx, new ConceptExpr.BoolConst(true)),
                        new ConceptStmt.If(
                                new ConceptExpr.BinaryOp(
                                        BinaryOperator.NE,
                                        new ConceptExpr.Var("isFluid", ConceptType.I32),
                                        new ConceptExpr.IntConst(0),
                                        ConceptType.BOOL),
                                List.of(
                                        new ConceptStmt.VoxelWrite(
                                                lx,
                                                y,
                                                lz,
                                                new ConceptExpr.IntConst(3),
                                                ConceptStmt.VoxelWrite.FLAG_FLUID_MUTATION
                                                        | ConceptStmt.VoxelWrite.FLAG_POST_PROCESS),
                                        new ConceptStmt.MarkPostProcess(lx, y, lz)),
                                List.of()),
                        new ConceptStmt.Return(new ConceptExpr.HeightRead(0, lx, lz))),
                false);

        ConceptModule module = new ConceptModule(
                "SyntheticVoxelFeature",
                List.of(tagLut, propLut, fluidLut),
                List.of(),
                fn,
                null);

        Map<Long, Integer> voxels = new HashMap<>();
        voxels.put(pack(4, 60, 7), 2); // water
        List<Long> postProcessed = new ArrayList<>();
        BitSet mask = new BitSet();

        VoxelEffectContext ctx = new VoxelEffectContext() {
            @Override
            public int readVoxel(int x, int y, int z) {
                return voxels.getOrDefault(pack(x, y, z), 0);
            }

            @Override
            public void writeVoxel(int x, int y, int z, int slot, int flags) {
                voxels.put(pack(x, y, z), slot);
            }

            @Override
            public int readHeight(int heightKind, int x, int z) {
                return 64;
            }

            @Override
            public void markPostProcess(int x, int y, int z) {
                postProcessed.add(pack(x, y, z));
            }

            @Override
            public boolean readBitSet(String setName, int index) {
                return mask.get(index);
            }

            @Override
            public void writeBitSet(String setName, int index, boolean value) {
                mask.set(index, value);
            }
        };

        Object result = ConceptIrEvaluator.executeVoxelEffect(module, ctx, 4, 60, 7, 12345L, 67890L);
        assertEquals(64, result);
        assertEquals(3, voxels.get(pack(4, 60, 7)));
        assertTrue(mask.get(4));
        assertFalse(postProcessed.isEmpty());

        EffectProfile profile = ConceptOptimizerPass.analyzeEffectProfile(module);
        assertEquals(EffectKind.CHUNK_LOCAL_MUTATOR, profile.kind());
        assertEquals(0, profile.maxWriteRadius());
        assertTrue(profile.readsFluids());
        assertTrue(profile.writesFluids());
        assertTrue(profile.marksPostProcess());
        assertTrue(profile.gpuEligible());

        String cl = new OpenClBackendEmitter().emit(module, ConceptBackendEmitter.EmitOptions.defaults()).sourceCode();
        assertTrue(cl.contains("leaf_voxel_read("), cl);
        assertTrue(cl.contains("leaf_voxel_write("), cl);
        assertTrue(cl.contains("leaf_mark_post_process("), cl);
        assertTrue(cl.contains("leaf_bitset_write("), cl);
        assertTrue(cl.contains("leaf_height_read("), cl);
    }

    @Test
    void classifiesHaloMutatorAndOpaqueEffectProfiles() {
        ConceptFunction haloFn = new ConceptFunction(
                "test/HaloTree",
                "place",
                "(III)V",
                List.of(
                        new ConceptParam("x", ConceptType.I32, 0),
                        new ConceptParam("y", ConceptType.I32, 1),
                        new ConceptParam("z", ConceptType.I32, 2)),
                ConceptType.VOID,
                Map.of(),
                List.of(
                        new ConceptStmt.PosSetAdd(
                                "trunkPositions",
                                new ConceptExpr.Var("x", ConceptType.I32),
                                new ConceptExpr.Var("y", ConceptType.I32),
                                new ConceptExpr.Var("z", ConceptType.I32)),
                        new ConceptStmt.VoxelWrite(
                                new ConceptExpr.Var("x", ConceptType.I32),
                                new ConceptExpr.Var("y", ConceptType.I32),
                                new ConceptExpr.Var("z", ConceptType.I32),
                                new ConceptExpr.IntConst(5),
                                ConceptStmt.VoxelWrite.FLAG_NONE)),
                false);
        ConceptModule haloModule = new ConceptModule("HaloTree", List.of(), List.of(), haloFn, null);
        EffectProfile haloProfile = ConceptOptimizerPass.analyzeEffectProfile(haloModule);
        assertEquals(EffectKind.HALO_MUTATOR, haloProfile.kind());
        assertEquals(1, haloProfile.maxWriteRadius());
        assertFalse(haloProfile.gpuEligible());

        ConceptModule opaqueModule = new ConceptModule(
                "OpaqueFeature",
                List.of(),
                List.of(),
                haloFn,
                null,
                null,
                List.of(new BytecodeSsaLifter.UnmappedReason("BlockEntity", "loadAdditional", 0, 0xB6, "unmodeled NBT")),
                Set.of(),
                Set.of());
        EffectProfile opaqueProfile = ConceptOptimizerPass.analyzeEffectProfile(opaqueModule);
        assertEquals(EffectKind.OPAQUE, opaqueProfile.kind());
        assertFalse(opaqueProfile.gpuEligible());
    }

    private static long pack(int x, int y, int z) {
        return (((long) x & 0xFFFFFL) << 40) | (((long) y & 0xFFFFFL) << 20) | ((long) z & 0xFFFFFL);
    }

    /** Mirrors Mojang's Xoroshiro128PlusPlus constructor zero-seed guard and nextLong() transition. */
    public static final class SyntheticXoroshiro128PP {
        private long seedLo;
        private long seedHi;

        public SyntheticXoroshiro128PP(long seedLo, long seedHi) {
            this.seedLo = seedLo;
            this.seedHi = seedHi;
            if ((this.seedLo | this.seedHi) == 0L) {
                this.seedLo = -7046029254386353131L;
                this.seedHi = 7640891576956012809L;
            }
        }

        public long nextLong() {
            long l = this.seedLo;
            long m = this.seedHi;
            long n = Long.rotateLeft(l + m, 17) + l;
            m ^= l;
            this.seedLo = Long.rotateLeft(l, 49) ^ m ^ (m << 21);
            this.seedHi = Long.rotateLeft(m, 28);
            return n;
        }

        public float nextFloat() {
            return (float) (this.nextLong() >>> 40) * 5.9604645E-8F;
        }
    }

    public static final class SyntheticVerticalGradientProbe {
        private final long factorySeedLo;
        private final long factorySeedHi;

        public SyntheticVerticalGradientProbe(long factorySeedLo, long factorySeedHi) {
            this.factorySeedLo = factorySeedLo;
            this.factorySeedHi = factorySeedHi;
        }

        public double compute(int x, int y, int z) {
            long posSeed = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
            posSeed = posSeed * posSeed * 42317861L + posSeed * 11L;
            long lo = (posSeed >> 16) ^ this.factorySeedLo;
            SyntheticXoroshiro128PP rng = new SyntheticXoroshiro128PP(lo, this.factorySeedHi);
            return rng.nextFloat();
        }
    }

    @Test
    void liftsConditionalConstPutfieldOnMutableLocalObjectWithoutCollapsingXoroshiroState() {
        SyntheticVerticalGradientProbe probe =
                new SyntheticVerticalGradientProbe(0x123456789ABCDEF0L, 0x0FEDCBA987654321L);
        ConceptModule lifted = BytecodeSsaLifter.liftInstanceGraph(
                probe, "compute", null, BytecodeSsaLifter.GraphLiftOptions.defaults());
        assertTrue(lifted != null && lifted.isPureCompute(), () -> "Unmapped: " + (lifted == null ? "null" : lifted.unmappedReasons()));

        VoxelEffectContext noopCtx = new VoxelEffectContext() {
            @Override
            public int readVoxel(int x, int y, int z) {
                return 0;
            }

            @Override
            public void writeVoxel(int x, int y, int z, int slot, int flags) {
            }
        };
        int distinct = 0;
        double prev = Double.NaN;
        for (int x = 0; x < 8; x++) {
            for (int y = -63; y <= -60; y++) {
                double expected = probe.compute(x, y, x * 3 - 5);
                Object actualObj = ConceptIrEvaluator.executeVoxelEffect(
                        lifted, noopCtx, x, y, x * 3 - 5);
                double actual = ((Number) actualObj).doubleValue();
                assertEquals(expected, actual, 1e-7, "Mismatch at (" + x + "," + y + ")");
                if (Double.isNaN(prev) || Math.abs(actual - prev) > 1e-6) {
                    distinct++;
                    prev = actual;
                }
            }
        }
        assertTrue(distinct > 10, "Positional Xoroshiro RNG must vary across coordinates, got distinct=" + distinct);

        // Also verify zero-seed fallback branch still triggers when (seedLo | seedHi) == 0L
        SyntheticVerticalGradientProbe zeroProbe = new SyntheticVerticalGradientProbe(0L, 0L);
        ConceptModule liftedZero = BytecodeSsaLifter.liftInstanceGraph(
                zeroProbe, "compute", null, BytecodeSsaLifter.GraphLiftOptions.defaults());
        assertEquals(
                zeroProbe.compute(0, 0, 0),
                ((Number) ConceptIrEvaluator.executeVoxelEffect(
                        liftedZero, noopCtx, 0, 0, 0)).doubleValue(),
                1e-7);
    }

    @Test
    void multiChannelInterpolatedDensityCarvesZeroCrossingNoodleCaveAndEmitsOpenCl() {
        // Channel 0: solid base terrain (+1.0 everywhere)
        LiftedWorldgenCapabilityTables.DensityFieldEvaluator baseCh = (x, y, z) -> 1.0f;
        // Channel 1: noodle ridge crossing zero between x=0 (-0.06) and x=4 (+0.06)
        LiftedWorldgenCapabilityTables.DensityFieldEvaluator ridgeCh = (x, y, z) -> (x <= 0) ? -0.06f : 0.06f;

        // Per-voxel combiner: min(base, -0.05 + 1.5 * abs(ridge))
        // At x=0 and x=4: -0.05 + 1.5*0.06 = +0.04 > 0 (solid at all corners!)
        // At midpoint x=2: lerp(-0.06, +0.06) = 0.0 -> -0.05 + 0 = -0.05 < 0 (CARVED NOODLE CAVE!)
        LiftedWorldgenCapabilityTables.InterpolatedDensityPlan plan =
                new LiftedWorldgenCapabilityTables.InterpolatedDensityPlan(
                        new LiftedWorldgenCapabilityTables.DensityFieldEvaluator[] {baseCh, ridgeCh},
                        (ch, bx, by, bz) -> Math.min(ch[0], -0.05 + 1.5 * Math.abs(ch[1])),
                        "fmin(ch[0], (-0.05f + 1.5f * fabs(ch[1])))",
                        "fmin(ch[0], (-0.05 + 1.5 * fabs(ch[1])))");

        LiftedWorldgenCapabilityTables.DensityFieldEvaluator evalWithPlan =
                new LiftedWorldgenCapabilityTables.DensityFieldEvaluator() {
                    @Override
                    public float sample(int bx, int by, int bz) {
                        return (float) Math.min(1.0, -0.05 + 1.5 * Math.abs(ridgeCh.sample(bx, by, bz)));
                    }

                    @Override
                    public LiftedWorldgenCapabilityTables.InterpolatedDensityPlan interpolatedPlan() {
                        return plan;
                    }
                };

        LiftedWorldgenCapabilityTables.DimensionCapabilitySpec spec =
                new LiftedWorldgenCapabilityTables.DimensionCapabilitySpec(
                        0, 2, 0, 32, 4, 8, -64, 1, 0, false, 0, -64,
                        new int[0], null, null, null, null, null, null, null, null, null);

        LiftedWorldgenCapabilityTables.LcaCapabilityChunkFrame frame =
                LiftedWorldgenCapabilityTables.synthesizeDimensionChunkLca(
                        spec, 0, 0, 8675309L, evalWithPlan, null, true, null,
                        null, null, null, false, false, false, null, 0,
                        WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_EXACT);

        // Corners x=0 and x=4 must be solid (slot 1), midpoint x=2 must be carved air (slot 0)!
        assertEquals(1, frame.getVoxel(0, (4 << 8) | (0 << 4) | 0));
        assertEquals(0, frame.getVoxel(0, (4 << 8) | (0 << 4) | 2),
                "Multi-channel interpolated density must carve zero-crossing noodle cave at midpoint x=2");
        assertEquals(1, frame.getVoxel(0, (4 << 8) | (0 << 4) | 4));
    }
}
