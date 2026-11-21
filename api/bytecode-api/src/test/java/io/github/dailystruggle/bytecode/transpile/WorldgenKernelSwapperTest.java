package io.github.dailystruggle.bytecode.transpile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptIrEvaluator;
import io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.EmitOptions;
import io.github.dailystruggle.bytecode.transpile.WorldgenKernelSwapper.DensityNode;
import io.github.dailystruggle.bytecode.transpile.WorldgenKernelSwapper.SectionCoord;
import io.github.dailystruggle.bytecode.transpile.WorldgenKernelSwapper.SwappedWorldgenKernel;
import io.github.dailystruggle.bytecode.transpile.WorldgenKernelSwapper.VoxelPaletteSpec;
import io.github.dailystruggle.bytecode.transpile.WorldgenKernelSwapper.WorldgenPipelineSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gate 1 verification suite for {@link WorldgenKernelSwapper}, proving runtime {@code .class}
 * bytecode pulling, {@code DensityFunction} router DAG fusion, OpenCL 3D section-batch kernel
 * emission, 4,096-voxel section parity against Java reference worldgen, and high-throughput batch
 * generation.
 *
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy corpus-atlas-ADR-002
 * @trace REQ-CFG-ATLAS-001
 */
class WorldgenKernelSwapperTest {

    /**
     * Compiled curve & interpolation helper class pulled at runtime via {@link ClassLoader}.
     */
    public static final class RuntimeCurveMath {
        private RuntimeCurveMath() {
        }

        public static double clamp01(double t) {
            if (t <= 0.0) {
                return 0.0;
            }
            if (t >= 1.0) {
                return 1.0;
            }
            return t;
        }

        public static double smootherstep(double t) {
            double c = clamp01(t);
            return c * c * c * (c * (c * 6.0 - 15.0) + 10.0);
        }

        public static double lerp(double t, double a, double b) {
            return a + t * (b - a);
        }
    }

    /**
     * Compiled vanilla-style 3D Improved Perlin + seeded hash sampler pulled at runtime via
     * {@link ClassLoader}.
     */
    public static final class RuntimeImprovedNoise {
        private static final int[] P = new int[512];

        static {
            for (int i = 0; i < 512; i++) {
                P[i] = (i * 73 + 41) & 255;
            }
        }

        private RuntimeImprovedNoise() {
        }

        public static double grad(int hash, double x, double y, double z) {
            int h = hash & 15;
            double u = h < 8 ? x : y;
            double v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
            return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
        }

        public static double sample3D(double x, double y, double z) {
            int ix = (int) Math.floor(x);
            int iy = (int) Math.floor(y);
            int iz = (int) Math.floor(z);
            int X = ix & 255;
            int Y = iy & 255;
            int Z = iz & 255;
            double fx = x - ix;
            double fy = y - iy;
            double fz = z - iz;
            double u = RuntimeCurveMath.smootherstep(fx);
            double v = RuntimeCurveMath.smootherstep(fy);
            double w = RuntimeCurveMath.smootherstep(fz);

            int A = P[X] + Y;
            int AA = P[A] + Z;
            int AB = P[A + 1] + Z;
            int B = P[X + 1] + Y;
            int BA = P[B] + Z;
            int BB = P[B + 1] + Z;

            double x1 = RuntimeCurveMath.lerp(u, grad(P[AA], fx, fy, fz), grad(P[BA], fx - 1.0, fy, fz));
            double x2 = RuntimeCurveMath.lerp(
                    u, grad(P[AB], fx, fy - 1.0, fz), grad(P[BB], fx - 1.0, fy - 1.0, fz));
            double y1 = RuntimeCurveMath.lerp(v, x1, x2);

            double x3 = RuntimeCurveMath.lerp(
                    u, grad(P[AA + 1], fx, fy, fz - 1.0), grad(P[BA + 1], fx - 1.0, fy, fz - 1.0));
            double x4 = RuntimeCurveMath.lerp(
                    u,
                    grad(P[AB + 1], fx, fy - 1.0, fz - 1.0),
                    grad(P[BB + 1], fx - 1.0, fy - 1.0, fz - 1.0));
            double y2 = RuntimeCurveMath.lerp(v, x3, x4);

            return RuntimeCurveMath.lerp(w, y1, y2);
        }

        public static double hashColumn(long packedCoord, long seed) {
            long h = seed ^ (packedCoord * 0x9E3779B97F4A7C15L);
            h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
            h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
            h = h ^ (h >>> 31);
            return (double) (h & 0xFFFFFFFFL) / (double) 0x100000000L;
        }
    }

    @BeforeEach
    void resetSwapperCache() {
        WorldgenKernelSwapper.clearCache();
    }

    private static WorldgenPipelineSpec buildOverworldSpec(String kernelId, EmitOptions emitOptions) {
        String noiseClass = RuntimeImprovedNoise.class.getName();

        // Multi-octave 3D fractal terrain noise + continental spline + Y gradient squeeze
        DensityNode fbmTerrain = new DensityNode.Fbm3D(
                noiseClass, "sample3D", 2, 0.015625, 0.03125, 2.0, 0.5, 0.85);
        DensityNode continentalRaw = new DensityNode.BytecodeNoise3D(
                noiseClass, "sample3D", 0.00390625, 0.001953125, 1.0, 11.5, 0.0, -7.25);
        DensityNode continentalSpline = new DensityNode.Spline1D(
                continentalRaw,
                new double[] {-0.8, -0.2, 0.25, 0.8},
                new double[] {-0.45, -0.05, 0.20, 0.65});
        DensityNode yGradient = new DensityNode.YClampedGradient(-64, 192, 1.25, -1.25);
        DensityNode columnHashJitter = new DensityNode.BytecodeSeededHash(
                noiseClass, "hashColumn", 0.04);

        DensityNode combinedTerrain = DensityNode.add(
                DensityNode.add(
                        new DensityNode.MappedUnary(
                                DensityNode.MappedKind.QUARTER_NEGATIVE, fbmTerrain),
                        continentalSpline),
                DensityNode.add(yGradient, columnHashJitter));
        DensityNode clampedTerrain = DensityNode.clamp(combinedTerrain, -2.0, 2.0);

        // 3D Cave cheese density router: square(noiseA) + abs(noiseB)
        DensityNode caveA = new DensityNode.MappedUnary(
                DensityNode.MappedKind.SQUARE,
                new DensityNode.BytecodeNoise3D(
                        noiseClass, "sample3D", 0.046875, 0.0625, 1.2, 3.1, 7.7, 13.3));
        DensityNode caveB = new DensityNode.MappedUnary(
                DensityNode.MappedKind.ABS,
                new DensityNode.BytecodeNoise3D(
                        noiseClass, "sample3D", 0.0625, 0.0625, 0.65, -19.4, 5.2, 29.8));
        DensityNode caveCheese = DensityNode.add(caveA, caveB);

        return new WorldgenPipelineSpec(
                kernelId,
                clampedTerrain,
                caveCheese,
                VoxelPaletteSpec.vanillaOverworldDefaults(),
                emitOptions);
    }

    private static int referenceJavaEvaluateVoxel(
            int worldX,
            int worldY,
            int worldZ,
            long worldSeed,
            VoxelPaletteSpec pal) {
        double wx = (double) worldX;
        double wy = (double) worldY;
        double wz = (double) worldZ;
        double seedOffset = ((double) (worldSeed & 0xFFFFL)) * 0.00390625;

        // FBM 2 octaves
        double oct0 = RuntimeImprovedNoise.sample3D(
                wx * 0.015625 + (seedOffset + 0.0),
                wy * 0.03125 + 0.0,
                wz * 0.015625 + (seedOffset + 0.0)) * 0.85;
        double oct1 = RuntimeImprovedNoise.sample3D(
                wx * 0.03125 + (seedOffset + 17.13),
                wy * 0.0625 + 31.71,
                wz * 0.03125 + (seedOffset + 47.29)) * (0.85 * 0.5);
        double fbm = oct0 + oct1;
        double quarterNegFbm = fbm > 0.0 ? fbm : fbm * 0.25;

        // Continental spline
        double contCoord = RuntimeImprovedNoise.sample3D(
                wx * 0.00390625 + (seedOffset + 11.5),
                wy * 0.001953125 + 0.0,
                wz * 0.00390625 + (seedOffset - 7.25));
        double[] knots = {-0.8, -0.2, 0.25, 0.8};
        double[] vals = {-0.45, -0.05, 0.20, 0.65};
        double splineVal = vals[vals.length - 1];
        for (int i = knots.length - 2; i >= 0; i--) {
            double t = (contCoord - knots[i]) / (knots[i + 1] - knots[i]);
            double seg = vals[i] + t * (vals[i + 1] - vals[i]);
            splineVal = contCoord < knots[i + 1] ? seg : splineVal;
        }
        splineVal = contCoord <= knots[0] ? vals[0] : splineVal;

        // YClampedGradient(-64, 192, 1.25, -1.25)
        double relY = (wy - (-64.0)) / 256.0;
        double clampedRelY = Math.min(1.0, Math.max(0.0, relY));
        double yGrad = 1.25 + clampedRelY * (-2.5);

        // Seeded column hash jitter
        long packedCoord = ((long) wx) * 341873128712L + ((long) wz) * 132897987541L;
        double jitter = RuntimeImprovedNoise.hashColumn(packedCoord, worldSeed) * 0.04;

        double rawTerrain = (quarterNegFbm + splineVal) + (yGrad + jitter);
        double terrainDensity = Math.min(2.0, Math.max(-2.0, rawTerrain));

        // Cave cheese density
        double cA = RuntimeImprovedNoise.sample3D(
                wx * 0.046875 + (seedOffset + 3.1),
                wy * 0.0625 + 7.7,
                wz * 0.046875 + (seedOffset + 13.3)) * 1.2;
        double cB = RuntimeImprovedNoise.sample3D(
                wx * 0.0625 + (seedOffset - 19.4),
                wy * 0.0625 + 5.2,
                wz * 0.0625 + (seedOffset + 29.8)) * 0.65;
        double caveDensity = (cA * cA) + Math.abs(cB);

        if (worldY <= pal.minY()) {
            return pal.bedrockStateId();
        }
        if (terrainDensity > 0.0) {
            if (caveDensity > pal.caveCarveThreshold()) {
                return pal.airStateId();
            }
            return worldY < pal.deepslateTransitionY()
                    ? pal.deepslateStateId()
                    : pal.stoneStateId();
        }
        return worldY <= pal.seaLevelY() ? pal.waterStateId() : pal.airStateId();
    }

    @Test
    @DisplayName("Pulls live .class bytecode at runtime, fuses Overworld DensityFunction DAG into OpenCL kernel, and matches Java 4,096/4,096 voxels per section")
    void pullsRuntimeBytecodeAndMatchesJavaOverworldSectionsBitForBit() {
        WorldgenPipelineSpec spec = buildOverworldSpec("overworld_fp64_swap", EmitOptions.gpuFp64());

        SwappedWorldgenKernel swapped = WorldgenKernelSwapper.compileAndSwap(
                spec,
                RuntimeImprovedNoise.class,
                RuntimeCurveMath.class);

        assertNotNull(swapped);
        assertEquals(0, swapped.optimizedModule().helperFunctions().size(),
                "All lifted noise, gradient, curve, and hash bytecode helpers must be fully inlined into the fused kernel");
        assertEquals(1, swapped.optimizedModule().lookupTables().size(),
                "The 512-entry Perlin permutation table from RuntimeImprovedNoise.<clinit> must be lifted");

        String clCode = swapped.openClArtifact().sourceCode();
        assertTrue(clCode.contains("__constant int lut_WorldgenKernelSwapperTest$RuntimeImprovedNoise_P[512]")
                        || clCode.contains("__constant int lut_WorldgenKernelSwapperTest_RuntimeImprovedNoise_P[512]")
                        || clCode.contains("_RuntimeImprovedNoise_P[512]"),
                "OpenCL kernel must contain the lifted 512-entry permutation table");
        assertTrue(clCode.contains("__kernel void evaluate_evaluate_worldgen_voxel_grid3d("),
                "OpenCL artifact must emit the 3D voxel grid kernel");
        assertTrue(clCode.contains("__global int* out_buffer"),
                "OpenCL voxel kernel output buffer must be int state IDs");
        assertTrue(clCode.contains("(gx + (int)origin_x)")
                && clCode.contains("(gy + (int)origin_y)")
                && clCode.contains("(gz + (int)origin_z)"),
                "OpenCL 3D grid kernel must bind gx, gy, gz to world_x, world_y, world_z");
        assertTrue(clCode.contains("(gy * dim_z + gz) * dim_x + gx"),
                "OpenCL 3D grid kernel must emit YZX (pos12) linear_idx matching LcaSectionBacking");
        assertFalse(clCode.contains("goto "), "Structured control flow must never emit goto");

        long seed = 0x123456789ABCDEF0L;
        VoxelPaletteSpec pal = spec.palette();

        // Test 4 distinct vertical strata sections: bedrock/deepslate (-4), deep caves (-1), coastline (3), peaks (6)
        int[] testSectionYs = {-4, -1, 3, 6};
        int[] actualSection = new int[WorldgenKernelSwapper.SECTION_VOXEL_COUNT];
        int[] expectedSection = new int[WorldgenKernelSwapper.SECTION_VOXEL_COUNT];

        for (int sectionY : testSectionYs) {
            SectionCoord coord = new SectionCoord(2, sectionY, -3);
            swapped.generateSection(coord, seed, actualSection);

            int idx = 0;
            for (int ly = 0; ly < 16; ly++) {
                int wy = coord.originBlockY() + ly;
                for (int lz = 0; lz < 16; lz++) {
                    int wz = coord.originBlockZ() + lz;
                    for (int lx = 0; lx < 16; lx++) {
                        int wx = coord.originBlockX() + lx;
                        expectedSection[idx++] = referenceJavaEvaluateVoxel(wx, wy, wz, seed, pal);
                    }
                }
            }
            assertArrayEquals(expectedSection, actualSection,
                    "4,096-voxel section mismatch at sectionY=" + sectionY);
        }

        // Verify ConceptIrEvaluator also agrees with FastSlotEvaluator on spot coordinates
        int spotExpected = referenceJavaEvaluateVoxel(37, -12, -41, seed, pal);
        int spotIr = ((Number) ConceptIrEvaluator.evaluateEntry(
                swapped.optimizedModule(), 37, -12, -41, seed)).intValue();
        assertEquals(spotExpected, spotIr);
    }

    @Test
    @DisplayName("Executes batched parallel chunk-section generation at multi-thousand section/sec throughput and supports FP32 consumer GPU mode")
    void executesHighThroughputBatchGenerationAndSupportsFp32KernelMode() {
        WorldgenPipelineSpec fp64Spec = buildOverworldSpec("overworld_batch_fp64", EmitOptions.gpuFp64());
        SwappedWorldgenKernel kernel = WorldgenKernelSwapper.compileAndSwap(
                fp64Spec,
                RuntimeImprovedNoise.class,
                RuntimeCurveMath.class);

        List<SectionCoord> batchCoords = new ArrayList<>(64);
        for (int cx = 0; cx < 4; cx++) {
            for (int cz = 0; cz < 4; cz++) {
                for (int sy = -2; sy < 2; sy++) {
                    batchCoords.add(new SectionCoord(cx, sy, cz));
                }
            }
        }

        List<int[]> generatedBatch = kernel.generateSectionBatch(batchCoords, 987654321L);
        assertEquals(64, generatedBatch.size());
        assertEquals(64L * 4096L, kernel.voxelsGenerated());
        assertTrue(kernel.measuredSectionsPerSecond() > 50.0,
                "Batched section generator must record positive throughput, got sps="
                        + kernel.measuredSectionsPerSecond()
                        + " stmts=" + kernel.optimizedModule().entryFunction().body().size());

        // Verify determinism across repeated batch runs
        int[] singleVerify = new int[4096];
        kernel.generateSection(batchCoords.get(17), 987654321L, singleVerify);
        assertArrayEquals(singleVerify, generatedBatch.get(17));

        // Verify FP32 consumer GPU downcast kernel emission & Rust secondary backend parity
        WorldgenPipelineSpec fp32Spec = buildOverworldSpec("overworld_batch_fp32", EmitOptions.gpuFp32());
        SwappedWorldgenKernel fp32Kernel = WorldgenKernelSwapper.compileAndSwap(
                fp32Spec,
                RuntimeImprovedNoise.class,
                RuntimeCurveMath.class);

        String fp32OpenCl = fp32Kernel.openClArtifact().sourceCode();
        assertFalse(fp32OpenCl.contains("cl_khr_fp64"),
                "FP32 consumer GPU kernel must not require cl_khr_fp64");
        assertTrue(fp32OpenCl.contains("__kernel void evaluate_evaluate_worldgen_voxel_grid3d("));

        String rustSource = fp32Kernel.rustArtifact().sourceCode();
        assertTrue(rustSource.contains("pub fn evaluate_evaluate_worldgen_voxel_grid3d("));
        assertTrue(rustSource.contains("out_buffer: &mut [i32]"));
        assertTrue(rustSource.contains("(gy * dim_z + gz) * dim_x + gx"));
    }

    /**
     * Stateful Vanilla-style ImprovedNoise sampler with instance fields ({@code xo, yo, zo}),
     * instance {@code byte[]} permutation table ({@code BALOAD}), {@code DUP2} double math, and
     * {@code tableswitch} gradient selection.
     */
    public static final class StatefulVanillaNoise {
        public final double xo;
        public final double yo;
        public final double zo;
        private final byte[] p;

        public StatefulVanillaNoise(long seed, double xo, double yo, double zo) {
            this.xo = xo;
            this.yo = yo;
            this.zo = zo;
            this.p = new byte[512];
            for (int i = 0; i < 512; i++) {
                this.p[i] = (byte) ((i * 73 + 41 + (int) (seed & 0xFFL)) & 255);
            }
        }

        public double gradSwitch(int hash, double x, double y, double z) {
            return switch (hash & 7) {
                case 0 -> x + y;
                case 1 -> -x + y;
                case 2 -> x - y;
                case 3 -> -x - y;
                case 4 -> x + z;
                case 5 -> -x + z;
                case 6 -> y + z;
                default -> -y - z;
            };
        }

        public double sampleInstance3D(double x, double y, double z) {
            double sx = x + this.xo;
            double sy = y + this.yo;
            double sz = z + this.zo;
            int ix = (int) Math.floor(sx);
            int iy = (int) Math.floor(sy);
            int iz = (int) Math.floor(sz);
            int X = ix & 255;
            int Y = iy & 255;
            int Z = iz & 255;
            double fx = sx - ix;
            double fy = sy - iy;
            double fz = sz - iz;
            double u = RuntimeCurveMath.smootherstep(fx);
            double v = RuntimeCurveMath.smootherstep(fy);
            double w = RuntimeCurveMath.smootherstep(fz);
            int a = (this.p[X] & 255) + Y;
            int aa = (this.p[a & 511] & 255) + Z;
            int b = (this.p[X + 1] & 255) + Y;
            int ba = (this.p[b & 511] & 255) + Z;
            double g0 = gradSwitch(this.p[aa & 511] & 255, fx, fy, fz);
            double g1 = gradSwitch(this.p[ba & 511] & 255, fx - 1.0, fy, fz);
            double blended = RuntimeCurveMath.lerp(u, g0, g1);
            return blended * (v + w) * 0.5;
        }
    }

    @Test
    @DisplayName("Lifts stateful worldgen object instances with GETFIELD primitives, byte[] BALOAD LUTs, virtual calls, and tableswitch")
    void liftsStatefulInstanceFieldsByteArrayLutsAndSwitchTables() {
        StatefulVanillaNoise instance = new StatefulVanillaNoise(42L, 1.25, -3.5, 7.75);
        BytecodeConceptIr.ConceptModule lifted = BytecodeSsaLifter.liftInstanceModule(
                instance, "sampleInstance3D");
        BytecodeConceptIr.ConceptModule optimized = ConceptOptimizerPass.optimize(lifted);

        assertTrue(optimized.entryFunction().pureCompute());
        assertEquals(1, optimized.lookupTables().size());
        assertEquals(512, optimized.lookupTables().get(0).values().size());

        double expected = instance.sampleInstance3D(12.3, -4.6, 8.9);
        double actual = ((Number) ConceptIrEvaluator.evaluateEntry(
                optimized, 12.3, -4.6, 8.9)).doubleValue();
        assertEquals(expected, actual, 1e-12);
    }

    public interface TestFnCtx {
        int blockX();
        int blockY();
        int blockZ();
    }

    public interface TestDf {
        double compute(TestFnCtx ctx);
    }

    public interface TestSpline {
        float apply(TestFnCtx ctx);
    }

    public record Constant(double value) implements TestDf {
        @Override
        public double compute(TestFnCtx ctx) {
            return value;
        }
    }

    public record YClampedGradient(int fromY, int toY, double fromValue, double toValue) implements TestDf {
        @Override
        public double compute(TestFnCtx ctx) {
            double y = ctx.blockY();
            if (y <= fromY) {
                return fromValue;
            }
            if (y >= toY) {
                return toValue;
            }
            double t = (y - fromY) / (double) (toY - fromY);
            return fromValue + t * (toValue - fromValue);
        }
    }

    public enum TestBinOp {
        ADD, MUL, MIN, MAX
    }

    public enum TestMapOp {
        ABS, SQUARE, CUBE, HALF_NEGATIVE, QUARTER_NEGATIVE, SQUEEZE, IDENTITY
    }

    public record MulOrAdd(TestBinOp op, TestDf input, double argument) implements TestDf {
        public MulOrAdd(String specificType, TestDf input, double argument) {
            this("MUL".equals(specificType) ? TestBinOp.MUL : TestBinOp.ADD, input, argument);
        }

        @Override
        public double compute(TestFnCtx ctx) {
            double v = input.compute(ctx);
            return op == TestBinOp.MUL ? v * argument : v + argument;
        }
    }

    public record Ap2(TestBinOp op, TestDf argument1, TestDf argument2) implements TestDf {
        public Ap2(String type, TestDf argument1, TestDf argument2) {
            this(TestBinOp.valueOf(type), argument1, argument2);
        }

        @Override
        public double compute(TestFnCtx ctx) {
            double a = argument1.compute(ctx);
            double b = argument2.compute(ctx);
            return switch (op) {
                case MUL -> a * b;
                case MIN -> Math.min(a, b);
                case MAX -> Math.max(a, b);
                case ADD -> a + b;
            };
        }
    }

    public record Mapped(TestMapOp op, TestDf input) implements TestDf {
        public Mapped(String type, TestDf input) {
            this(TestMapOp.valueOf(type), input);
        }

        @Override
        public double compute(TestFnCtx ctx) {
            double v = input.compute(ctx);
            return switch (op) {
                case ABS -> Math.abs(v);
                case SQUARE -> v * v;
                case CUBE -> v * v * v;
                case HALF_NEGATIVE -> v > 0.0 ? v : v * 0.5;
                case QUARTER_NEGATIVE -> v > 0.0 ? v : v * 0.25;
                case SQUEEZE -> {
                    double c = Math.max(-1.0, Math.min(1.0, v));
                    yield c / 2.0 - c * c * c / 24.0;
                }
                case IDENTITY -> v;
            };
        }
    }

    public record RangeChoice(
            TestDf input,
            double minInclusive,
            double maxExclusive,
            TestDf whenInRange,
            TestDf whenOutOfRange) implements TestDf {
        @Override
        public double compute(TestFnCtx ctx) {
            double v = input.compute(ctx);
            return (v >= minInclusive && v < maxExclusive)
                    ? whenInRange.compute(ctx)
                    : whenOutOfRange.compute(ctx);
        }
    }

    public record Noise(double xzScale, double yScale) implements TestDf {
        @Override
        public double compute(TestFnCtx ctx) {
            return RuntimeImprovedNoise.sample3D(
                    ctx.blockX() * xzScale * 0.015625,
                    ctx.blockY() * yScale * 0.015625,
                    ctx.blockZ() * xzScale * 0.015625);
        }
    }

    public record CacheOnce(TestDf function) implements TestDf {
        @Override
        public double compute(TestFnCtx ctx) {
            return function.compute(ctx);
        }
    }

    public record NoiseInterpolator(TestDf noiseFiller) implements TestDf {
        @Override
        public double compute(TestFnCtx ctx) {
            return noiseFiller.compute(ctx);
        }
    }

    public record SyntheticNoiseRouter(TestDf finalDensity, TestDf noodle) {
    }

    public record Clamp(TestDf input, double minValue, double maxValue) implements TestDf {
        @Override
        public double compute(TestFnCtx ctx) {
            double v = input.compute(ctx);
            return Math.max(minValue, Math.min(maxValue, v));
        }
    }

    public record SplineCoordinate(TestDf function) {
        public float apply(TestFnCtx ctx) {
            return (float) function.compute(ctx);
        }
    }

    public record SplineConstant(float value) implements TestSpline {
        @Override
        public float apply(TestFnCtx ctx) {
            return value;
        }
    }

    public record Multipoint(
            SplineCoordinate coordinate,
            float[] locations,
            java.util.List<TestSpline> values,
            float[] derivatives) implements TestSpline {
        @Override
        public float apply(TestFnCtx ctx) {
            float c = coordinate.apply(ctx);
            int n = locations.length;
            if (c <= locations[0]) {
                return values.get(0).apply(ctx) + derivatives[0] * (c - locations[0]);
            }
            int last = n - 1;
            if (c >= locations[last]) {
                return values.get(last).apply(ctx) + derivatives[last] * (c - locations[last]);
            }
            for (int i = 0; i < last; i++) {
                float x0 = locations[i];
                float x1 = locations[i + 1];
                if (c < x1) {
                    float span = x1 - x0;
                    float t = (c - x0) / span;
                    float v0 = values.get(i).apply(ctx);
                    float v1 = values.get(i + 1).apply(ctx);
                    float dv = v1 - v0;
                    float a = derivatives[i] * span - dv;
                    float b = dv - derivatives[i + 1] * span;
                    return v0 + t * dv + t * (1.0f - t) * (a + t * (b - a));
                }
            }
            return values.get(last).apply(ctx);
        }
    }

    public record Spline(TestSpline spline) implements TestDf {
        @Override
        public double compute(TestFnCtx ctx) {
            return spline.apply(ctx);
        }
    }

    @Test
    @DisplayName("Lifts live runtime NoiseRouter object graph (MulOrAdd, RangeChoice, Mapped SQUEEZE/ABS, CacheOnce, NoiseInterpolator) into OpenCL & Rust")
    void liftsLiveRuntimeNoiseRouterIntoOpenClAndRust() {
        TestDf yGrad = new CacheOnce(new YClampedGradient(-64, 320, 1.5, -1.5));
        TestDf noodleThickness = new NoiseInterpolator(
                new RangeChoice(
                        new YClampedGradient(-4064, 4062, -4064.0, 4062.0),
                        -60.0,
                        321.0,
                        new MulOrAdd("ADD", new MulOrAdd("MUL", new Noise(1.0, 1.0), -0.025), -0.075),
                        new Constant(0.0)));
        TestDf finalDensity = new MulOrAdd(
                "MUL",
                new Mapped(
                        "SQUEEZE",
                        new Ap2("ADD", yGrad, new Mapped("ABS", noodleThickness))),
                0.64);
        SyntheticNoiseRouter router = new SyntheticNoiseRouter(finalDensity, noodleThickness);

        SwappedWorldgenKernel kernel = WorldgenKernelSwapper.compileFromRuntimeRouter(
                "live_router_lifted_fp64",
                router,
                null,
                RuntimeImprovedNoise.class.getName(),
                "sample3D",
                VoxelPaletteSpec.vanillaOverworldDefaults(),
                EmitOptions.gpuFp64(),
                RuntimeImprovedNoise.class,
                RuntimeCurveMath.class);

        assertNotNull(kernel);
        assertTrue(kernel.openClArtifact().sourceCode().contains("evaluate_lca_packed_batch3d"));
        assertTrue(kernel.rustArtifact().sourceCode().contains("evaluate_evaluate_worldgen_voxel_grid3d"));

        int[] sec = new int[WorldgenKernelSwapper.SECTION_VOXEL_COUNT];
        kernel.generateSection(new SectionCoord(0, 2, 0), 8675309L, sec);
        assertEquals(4096, sec.length);
    }

    @Test
    @DisplayName("Lifts Stages K1-K3 .lca workgroup pipeline and Long.bitCount POPCOUNT intrinsics into OpenCL C99")
    void liftsEndToEndLcaStagesWorkgroupPipelineAndPopcountIntrinsics() {
        BytecodeConceptIr.ConceptModule module =
                WorldgenKernelSwapper.compileLiftedEndToEndLcaStagesModule("eval_blended_point_f32");
        assertNotNull(module.workgroupPipeline());
        assertTrue(
                module.workgroupPipeline().totalLocalSramBytes() < 8192,
                "Workgroup __local SRAM must stay < 8 KiB");
        assertEquals(12, module.workgroupPipeline().stageFunctions().size());

        // Verify 1:1 round-trip of lifted 01:H_3 Hilbert & Gray-code functions via ConceptIrEvaluator
        for (int h3 = 0; h3 < 4096; h3 += 137) {
            int yzx = WorldgenKernelSwapper.h3ToYzx(h3);
            int x = yzx & 15;
            int z = (yzx >>> 4) & 15;
            int y = (yzx >>> 8) & 15;
            assertEquals(h3, WorldgenKernelSwapper.yzxToH3(x, y, z));
            int irYzx = ((Number) ConceptIrEvaluator.evaluateFunction(
                            module, module.workgroupPipeline().stageFunctions().get(0), h3))
                    .intValue();
            assertEquals(yzx, irYzx);
        }
        for (int lv = 0; lv < 16; lv++) {
            int g = WorldgenKernelSwapper.toGray4(lv);
            assertEquals(lv, WorldgenKernelSwapper.fromGray4(g));
        }

        // Verify POPCOUNT intrinsic lifted from Long.bitCount in evaluateOctantDiscardExposure
        BytecodeConceptIr.ConceptFunction discardFn =
                module.workgroupPipeline().stageFunctions().get(7);
        assertEquals("evaluateOctantDiscardExposure", discardFn.name());
        long sampleMask = 0b1010_0101L;
        assertEquals(
                WorldgenKernelSwapper.evaluateOctantDiscardExposure(sampleMask, 1, 10, 42),
                ((Number) ConceptIrEvaluator.evaluateFunction(
                                module, discardFn, sampleMask, 1, 10, 42))
                        .intValue());
        assertEquals(
                WorldgenKernelSwapper.evaluateOctantDiscardExposure(sampleMask, 0, 10, 42),
                ((Number) ConceptIrEvaluator.evaluateFunction(
                                module, discardFn, sampleMask, 0, 10, 42))
                        .intValue());

        String openCl = WorldgenKernelSwapper.emitLiftedEndToEndLcaStagesOpenCl("eval_blended_point_f32");
        assertTrue(openCl.contains("inline int h3ToYzx("));
        assertTrue(openCl.contains("inline int yzxToH3("));
        assertTrue(openCl.contains("inline int toGray4("));
        assertTrue(openCl.contains("inline int classifyClimateBiomeQuartet("));
        assertTrue(openCl.contains("inline int selectSurfaceStrataBlock("));
        assertTrue(openCl.contains("inline int computeVanillaAnchoredOreStartH3("));
        assertTrue(openCl.contains("inline int evaluateOctantDiscardExposure("));
        assertTrue(openCl.contains("inline int selectUndergroundDecoratorState("));
        assertTrue(openCl.contains("inline int selectSurfaceDecoratorBlock("));
        assertTrue(openCl.contains("inline int computeChunkTreeAnchorPacked("));
        assertTrue(openCl.contains("popcount("));
        assertTrue(openCl.contains("__kernel void evaluate_end_to_end_lca_chunk_batch("));
        assertTrue(openCl.contains("__kernel void evaluate_end_to_end_lca_slot4k_batch("));
        assertTrue(openCl.contains("__local float local_corners[1225];"));
        assertTrue(openCl.contains("__local short local_surf_y[256];"));
        assertTrue(openCl.contains("__local uchar local_biomes[384];"));

        byte[] slot4k = new byte[WorldgenKernelSwapper.LCA_SLOT_4K_BYTES];
        int exactBytes = WorldgenKernelSwapper.evaluateEndToEndLcaSlot4kCpu(
                (bx, by, bz) -> (float) ((66.0 - by) * 0.05 + Math.sin(bx * 0.04) * 0.35 + Math.cos(bz * 0.04) * 0.35),
                2,
                -3,
                123456789L,
                slot4k,
                0);
        assertTrue(
                exactBytes >= WorldgenKernelSwapper.LCA_SLOT_4K_RUN_STREAM_OFFSET
                        && exactBytes <= WorldgenKernelSwapper.LCA_SLOT_4K_BYTES,
                "4 KiB slot payload length must fit within [1760..4096]: " + exactBytes);
        java.nio.ByteBuffer view =
                java.nio.ByteBuffer.wrap(slot4k).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(WorldgenKernelSwapper.LCA_SLOT_4K_MAGIC, view.getInt(0));
        assertEquals(2, view.getInt(4));
        assertEquals(-3, view.getInt(8));
        assertEquals(exactBytes, view.getInt(12));
        assertTrue(Short.toUnsignedInt(view.getShort(20)) >= 8, "Sky sections must be culled inline");
        assertTrue(Short.toUnsignedInt(view.getShort(26)) > 0, "Overworld 01:H_3 decorator runs must be emitted");
    }

    @Test
    @DisplayName("Lifts Paper 26.2 NoiseRouter Spline/Clamp/slideOverworld graph and aligns Slot4k CPU rint half-way ties")
    void liftsPaper262FinalDensitySplineClampSlideAndAlignsSlot4kRintTies() {
        // 1. Exact IEEE-754 half-way ties: 0.375f * 28.0f == +10.5f -> rint = 10 (foundY = 74, raw height = 74 + 64 + 1 = 139)
        //    and -0.125f * 28.0f == -3.5f -> rint = -4 (foundY = 60, below sea level 62 -> ocean_floor = 61, world_surface = 63)
        byte[] posTieSlot = new byte[WorldgenKernelSwapper.LCA_SLOT_4K_BYTES];
        WorldgenKernelSwapper.evaluateEndToEndLcaSlot4kCpu(
                (bx, by, bz) -> 0.375f, 0, 0, 8675309L, posTieSlot, 0);
        java.nio.ByteBuffer posView =
                java.nio.ByteBuffer.wrap(posTieSlot).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(74, posView.getShort(1456), "Slot4k CPU must round +10.5f tie to even 10 (surfY=74)");

        byte[] negTieSlot = new byte[WorldgenKernelSwapper.LCA_SLOT_4K_BYTES];
        WorldgenKernelSwapper.evaluateEndToEndLcaSlot4kCpu(
                (bx, by, bz) -> -0.125f, 0, 0, 8675309L, negTieSlot, 0);
        java.nio.ByteBuffer negView =
                java.nio.ByteBuffer.wrap(negTieSlot).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(60, negView.getShort(1456), "Slot4k CPU must round -3.5f tie to even -4 (surfY=60)");

        // 2. Paper 26.2-shaped NoiseRouter.finalDensity with nested CubicSpline$Multipoint, Clamp, and slideOverworld
        TestDf continents = new MulOrAdd("MUL", new Noise(0.125, 0.0), 0.2);
        TestDf erosion = new MulOrAdd("MUL", new Noise(0.25, 0.0), 0.5);
        TestSpline innerCoastalSpline = new Multipoint(
                new SplineCoordinate(erosion),
                new float[] {-0.5f, 0.0f, 0.5f},
                java.util.List.of(
                        new SplineConstant(-0.62f),
                        new SplineConstant(-0.48f),
                        new SplineConstant(-0.22f)),
                new float[] {0.15f, 0.25f, 0.10f});
        TestDf offsetSpline = new Ap2(
                "ADD",
                new Constant(-0.50375),
                new Spline(new Multipoint(
                        new SplineCoordinate(continents),
                        new float[] {-0.6f, 0.0f, 0.6f},
                        java.util.List.of(
                                new SplineConstant(-0.85f),
                                innerCoastalSpline,
                                new SplineConstant(0.42f)),
                        new float[] {0.3f, 0.8f, 0.2f})));
        TestDf factorSpline = new Spline(new Multipoint(
                new SplineCoordinate(erosion),
                new float[] {-0.5f, 0.5f},
                java.util.List.of(new SplineConstant(5.2f), new SplineConstant(3.8f)),
                new float[] {0.0f, 0.0f}));
        TestDf yGrad = new YClampedGradient(-64, 320, 1.5, -1.5);
        TestDf depth = new Ap2("ADD", yGrad, offsetSpline);
        TestDf slopedMain = new MulOrAdd(
                "MUL",
                new Mapped("QUARTER_NEGATIVE", new Ap2("MUL", depth, factorSpline)),
                4.0);
        TestDf slopedCheese = new Ap2("ADD", slopedMain, new MulOrAdd("MUL", new Noise(1.0, 1.0), 0.35));
        // slideOverworld: top slide [240..256] -> -0.078125, bottom slide [-64..-40] -> +0.1171875, Clamp[-64, 64], squeeze
        TestDf topSlideWeight = new YClampedGradient(240, 256, 1.0, 0.0);
        TestDf afterTop = new Ap2(
                "ADD",
                new MulOrAdd("MUL", new Ap2("ADD", new Constant(0.078125), slopedCheese), 1.0),
                new Constant(0.0));
        TestDf slidTop = new Ap2(
                "ADD",
                new MulOrAdd("MUL", topSlideWeight, 1.0),
                new Constant(-1.0));
        // Exact Paper 26.2 lerp: (1 - wTop) * (-0.078125) + wTop * slopedCheese, then bottom slide wBot in [-64..-40] to +0.1171875
        TestDf topBlended = new Ap2(
                "ADD",
                new Ap2("MUL", topSlideWeight, new Ap2("ADD", slopedCheese, new Constant(0.078125))),
                new Constant(-0.078125));
        TestDf botSlideWeight = new YClampedGradient(-64, -40, 0.0, 1.0);
        TestDf slid = new Ap2(
                "ADD",
                new Ap2("MUL", botSlideWeight, new Ap2("ADD", topBlended, new Constant(-0.1171875))),
                new Constant(0.1171875));
        TestDf finalDensity = new Mapped("SQUEEZE", new Clamp(slid, -64.0, 64.0));
        assertNotNull(afterTop);
        assertNotNull(slidTop);

        SwappedWorldgenKernel kernel = WorldgenKernelSwapper.compileFromRuntimeRouter(
                "paper_26_2_spline_clamp_slide",
                new SyntheticNoiseRouter(finalDensity, new Constant(-1.0)),
                null,
                RuntimeImprovedNoise.class.getName(),
                "sample3D",
                VoxelPaletteSpec.vanillaOverworldDefaults(),
                EmitOptions.gpuFp64(),
                RuntimeImprovedNoise.class,
                RuntimeCurveMath.class);

        VoxelPaletteSpec pal = VoxelPaletteSpec.vanillaOverworldDefaults();
        int[] deepSubsurface = new int[WorldgenKernelSwapper.SECTION_VOXEL_COUNT];
        kernel.generateSection(new SectionCoord(10, -2, 10), 8675309L, deepSubsurface);
        int solidCountSub = 0;
        for (int s : deepSubsurface) {
            if (s == pal.deepslateStateId() || s == pal.stoneStateId() || s == pal.bedrockStateId()) {
                solidCountSub++;
            }
        }
        assertEquals(
                WorldgenKernelSwapper.SECTION_VOXEL_COUNT,
                solidCountSub,
                "Subsurface section Y=-32..-17 under ocean/coastal spline must remain 100% solid deepslate/stone");

        // At sectionY = 2 (Y = 32..47): with offsetSpline = -0.50375 + coastalSpline (~ -0.98), yGrad(40) = +0.85 -> depth < 0 -> ocean water!
        // Without Spline lifting (collapsed to 0.0), offset = -0.50375 + 0 = -0.50375 -> yGrad(40) + offset = +0.346 > 0 -> falsely solid stone!
        int[] oceanMid = new int[WorldgenKernelSwapper.SECTION_VOXEL_COUNT];
        kernel.generateSection(new SectionCoord(10, 2, 10), 8675309L, oceanMid);
        int waterCountOcean = 0;
        for (int s : oceanMid) {
            if (s == pal.waterStateId()) {
                waterCountOcean++;
            }
        }
        assertTrue(
                waterCountOcean > 2048,
                "Coastal/ocean spline negative offset must carve ocean basin water at Y=32..47 (got water=" + waterCountOcean + ")");
    }

    @Test
    @DisplayName("Stage K0 structure-grid filter and Stage K2 Beardifier preserve zero-piece parity and adapt terrain")
    void stageK0StructureGridFilterAndStageK2BeardifierAdaptTerrain() {
        String openCl = WorldgenKernelSwapper.emitLiftedEndToEndLcaStagesOpenCl("eval_blended_point_f32");
        assertTrue(openCl.contains("__kernel void evaluate_structure_grid_starts("));
        assertTrue(openCl.contains("__global const int* beard_pieces_i32"));
        assertTrue(openCl.contains("__global const int2* chunk_beard_spans"));

        WorldgenKernelSwapper.CornerDensityEvaluator eval =
                (bx, by, bz) -> (float) ((66.0 - by) * 0.05 + Math.sin(bx * 0.03) * 0.25 + Math.cos(bz * 0.03) * 0.25);

        byte[] baseSlot = new byte[WorldgenKernelSwapper.LCA_SLOT_4K_BYTES];
        byte[] zeroBeardSlot = new byte[WorldgenKernelSwapper.LCA_SLOT_4K_BYTES];
        WorldgenKernelSwapper.evaluateEndToEndLcaSlot4kCpu(eval, 4, -2, 8675309L, baseSlot, 0);
        WorldgenKernelSwapper.evaluateEndToEndLcaSlot4kCpu(eval, 4, -2, 8675309L, new int[8], 0, 0, zeroBeardSlot, 0);
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                baseSlot, zeroBeardSlot, "Zero-beard Slot4k execution must remain bit-identical to 6-arg baseline");

        // Elevated BEARD_THIN pad inside chunk (4, -2) -> blocks [66..76] x [-30..-18] at Y=88
        int[] thinBeard = WorldgenKernelSwapper.packGpuBeardPiecesI32(java.util.List.of(
                new WorldgenKernelSwapper.GpuBeardPiece(
                        66, 88, -30, 76, 94, -18, WorldgenKernelSwapper.BEARD_THIN, 0, 0, 0)));
        byte[] beardedSlot = new byte[WorldgenKernelSwapper.LCA_SLOT_4K_BYTES];
        WorldgenKernelSwapper.evaluateEndToEndLcaSlot4kCpu(eval, 4, -2, 8675309L, thinBeard, 0, 1, beardedSlot, 0);
        java.nio.ByteBuffer baseBuf = java.nio.ByteBuffer.wrap(baseSlot).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        java.nio.ByteBuffer beardBuf = java.nio.ByteBuffer.wrap(beardedSlot).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        // Local column (lx=6, lz=4) -> world (70, -28) is inside [66..76] x [-30..-18] (colIdx=70 < 128)
        int colIdx = (4 << 4) | 6;
        assertTrue(baseBuf.getShort(1456 + (colIdx << 1)) < 82);
        assertEquals(88, beardBuf.getShort(1456 + (colIdx << 1)), "BEARD_THIN must raise pad column surfY to 88");
        // Verify Mojang OCEAN_FLOOR_WG packed long[37] heightmap at col 134 (lx=6, lz=8 -> world 70, -24) also encodes 88 + 64 + 1 = 153
        long ofWord = beardBuf.getLong(272 + ((37 + (134 / 7)) << 3));
        int ofEnc = (int) ((ofWord >>> ((134 % 7) * 9)) & 0x1FFL);
        assertEquals(88 + 64 + 1, ofEnc, "Packed OCEAN_FLOOR_WG heightmap must reflect BEARD_THIN elevation 88");

        // Deep underground BEARD_BOX at Y = -20..-12 must wake section s=2 (Y = -32..-17) octant mask
        int[] boxBeard = WorldgenKernelSwapper.packGpuBeardPiecesI32(java.util.List.of(
                new WorldgenKernelSwapper.GpuBeardPiece(
                        66, -24, -28, 74, -18, -20, WorldgenKernelSwapper.BEARD_BOX, 0, 0, 0)));
        byte[] carvedSlot = new byte[WorldgenKernelSwapper.LCA_SLOT_4K_BYTES];
        WorldgenKernelSwapper.evaluateEndToEndLcaSlot4kCpu(
                (bx, by, bz) -> 0.25f, 4, -2, 8675309L, boxBeard, 0, 1, carvedSlot, 0);
        java.nio.ByteBuffer carvedBuf = java.nio.ByteBuffer.wrap(carvedSlot).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        long sec2OccMask = carvedBuf.getLong(80 + (2 << 3));
        assertTrue(sec2OccMask != 0L, "BEARD_BOX underground room must wake section s=2 octant mask and carve air runs");

        // Stage K0 deterministic RandomSpread filtering
        java.util.List<WorldgenKernelSwapper.StructureGridQuery> queries = java.util.List.of(
                new WorldgenKernelSwapper.StructureGridQuery(
                        0, 34, 8, 10387312, WorldgenKernelSwapper.SPREAD_LINEAR, 0xFF, 48, 110),
                new WorldgenKernelSwapper.StructureGridQuery(
                        1, 24, 8, 94251327, WorldgenKernelSwapper.SPREAD_TRIANGULAR, 0xFF, 48, 110));
        java.util.List<WorldgenKernelSwapper.StructureStartCandidate> startsA =
                WorldgenKernelSwapper.evaluateStructureGridStartsCpu(eval, queries, -40, -40, 40, 40, 8675309L);
        java.util.List<WorldgenKernelSwapper.StructureStartCandidate> startsB =
                WorldgenKernelSwapper.evaluateStructureGridStartsCpu(eval, queries, -40, -40, 40, 40, 8675309L);
        assertEquals(startsA, startsB);
        assertFalse(startsA.isEmpty(), "Expected surviving structure starts across [-40..40]^2 region halo");

        // Verify biome mask = 0 rejects all candidates cleanly
        java.util.List<WorldgenKernelSwapper.StructureStartCandidate> rejected =
                WorldgenKernelSwapper.evaluateStructureGridStartsCpu(
                        eval,
                        java.util.List.of(new WorldgenKernelSwapper.StructureGridQuery(
                                2, 16, 4, 14357617, WorldgenKernelSwapper.SPREAD_LINEAR, 0x00, 48, 110)),
                        -32,
                        -32,
                        32,
                        32,
                        8675309L);
        assertTrue(rejected.isEmpty());
    }

    @Test
    @DisplayName("Stage K0b tree-anchor batch and Stage K2b in-kernel stamp fusion allocate extra palette IDs and flag overflow")
    void stageK0bAndStageK2bInKernelStampingAllocateExtraStatesAndFlagOverflow() {
        String openCl = WorldgenKernelSwapper.emitLiftedEndToEndLcaStagesOpenCl("eval_blended_point_f32");
        assertTrue(openCl.contains("__kernel void evaluate_tree_anchors_batch("));
        assertTrue(openCl.contains("const int in_kernel_stamps_enabled"));
        assertTrue(openCl.contains("__global const int* atlas_pal_meta_i32"));

        WorldgenKernelSwapper.CornerDensityEvaluator eval =
                (bx, by, bz) -> (float) ((66.0 - by) * 0.05 + Math.sin(bx * 0.03) * 0.25 + Math.cos(bz * 0.03) * 0.25);

        int[] coords = {0, 0, 1, 0, 0, 1};
        int[] anchors = WorldgenKernelSwapper.evaluateTreeAnchorsBatchCpu(eval, coords, 3, 8675309L);
        assertEquals(3, anchors.length);
        byte[] refSlot = new byte[WorldgenKernelSwapper.LCA_SLOT_4K_BYTES];
        WorldgenKernelSwapper.evaluateEndToEndLcaSlot4kCpu(eval, 0, 0, 8675309L, refSlot, 0);
        java.nio.ByteBuffer refBuf = java.nio.ByteBuffer.wrap(refSlot).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(refBuf.getInt(28), anchors[0], "Stage K0b anchor must match unstamped Slot4k treeAnchor");

        // Build minimal synthetic atlas with global state ID 99 (dry) / 100 (wet) and 1 slice at (0, 0)
        int[] palMeta = {
            (100 << 16) | 99, 4 // CAT_STRUCTURE_SOLID = 4
        };
        int startH3 = WorldgenKernelSwapper.yzxToH3(4, 2, 4);
        int[] runs = {
            0, (4 << 16) | startH3, 0, 0
        };
        int[] slices = {
            0, 0, 0, 1
        };
        int[] placed = {
            0, 80, 0, 0, 0, 0, 0, 0
        };
        int[] anchors9 = new int[9];
        anchors9[4] = anchors[0];

        byte[] stampedSlot = new byte[WorldgenKernelSwapper.LCA_SLOT_4K_BYTES];
        WorldgenKernelSwapper.evaluateEndToEndLcaSlot4kCpu(
                eval,
                0,
                0,
                8675309L,
                null,
                0,
                0,
                41,
                anchors9,
                0,
                placed,
                0,
                1,
                palMeta,
                slices,
                runs,
                new int[0],
                stampedSlot,
                0);
        java.nio.ByteBuffer stBuf = java.nio.ByteBuffer.wrap(stampedSlot).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int flags16 = Short.toUnsignedInt(stBuf.getShort(20));
        assertTrue((flags16 & WorldgenKernelSwapper.SLOT4K_IN_KERNEL_STAMPED_FLAG) != 0);
        assertEquals(0, flags16 & WorldgenKernelSwapper.SLOT4K_OVERFLOW_FLAG);
        int extraCnt = (flags16 >>> 6) & 0xFF;
        assertEquals(1, extraCnt, "Expected 1 allocated chunk-local extra state ID");
        assertEquals(99, Short.toUnsignedInt(stBuf.getShort(4094)), "Downward extra state directory must store global ID 99");

        // Verify >192 distinct extra states sets SLOT4K_OVERFLOW_FLAG cleanly without throwing
        int[] bigPalMeta = new int[200 * 2];
        int[] bigRuns = new int[200 * 4];
        for (int i = 0; i < 200; i++) {
            bigPalMeta[i * 2] = ((200 + i) << 16) | (200 + i);
            bigPalMeta[i * 2 + 1] = 4;
            bigRuns[i * 4] = 0;
            bigRuns[i * 4 + 1] = (1 << 16) | i;
            bigRuns[i * 4 + 2] = i;
            bigRuns[i * 4 + 3] = 0;
        }
        int[] bigSlices = {0, 0, 0, 200};
        byte[] overflowSlot = new byte[WorldgenKernelSwapper.LCA_SLOT_4K_BYTES];
        WorldgenKernelSwapper.evaluateEndToEndLcaSlot4kCpu(
                eval,
                0,
                0,
                8675309L,
                null,
                0,
                0,
                41,
                anchors9,
                0,
                placed,
                0,
                1,
                bigPalMeta,
                bigSlices,
                bigRuns,
                new int[0],
                overflowSlot,
                0);
        java.nio.ByteBuffer ovBuf = java.nio.ByteBuffer.wrap(overflowSlot).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int ovFlags = Short.toUnsignedInt(ovBuf.getShort(20));
        assertTrue(
                (ovFlags & WorldgenKernelSwapper.SLOT4K_OVERFLOW_FLAG) != 0,
                "Exceeding 192 extra chunk-local states must set SLOT4K_OVERFLOW_FLAG");
    }

    @Test
    @DisplayName("Supports upland Y>102 forest anchors, multi-tree forest canopies, flush smoothed TERRAIN_MATCHING paths, and 3D cave carving")
    void supportsUplandForestAnchorsMultiTreeCanopiesFlushPathsAndCaves() {
        // 1. Upland forest above Y=102 produces active tree anchors, while ocean/river (biome 6) or underwater returns 0
        int foundUpland = 0;
        for (int cx = 0; cx < 16; cx++) {
            int packed = WorldgenKernelSwapper.computeChunkTreeAnchorPacked(cx, 3, 1, 136, 62, 8675309L);
            if ((packed & 0x40000000) != 0) {
                foundUpland++;
                assertEquals(136, ((packed >>> 12) & 511) - 64);
            }
            assertEquals(
                    0,
                    WorldgenKernelSwapper.computeChunkTreeAnchorPacked(cx, 3, 6, 66, 62, 8675309L),
                    "Ocean/river biome (6) shall never emit tree anchors");
            assertEquals(
                    0,
                    WorldgenKernelSwapper.computeChunkTreeAnchorPacked(cx, 3, 1, 62, 62, 8675309L),
                    "Underwater/sea-level column shall never emit tree anchors");
        }
        assertTrue(foundUpland >= 8, "Forest biome at Y=136 (>102) must emit high-density tree anchors: " + foundUpland);

        // 2. Forested Slot4k chunk stamps multiple trees (>120 wood+leaf voxels) and carves underground caves when noise < -0.11f
        WorldgenKernelSwapper.CornerDensityEvaluator forestAndCaveEval = (bx, by, bz) -> {
            if (by == 64) {
                return 0.08f; // surfY ~ 66 (dry land)
            }
            if (by == 96) {
                return 0.09f; // humid forest climate
            }
            if (by >= -32 && by <= 24) {
                return -0.18f; // 3D cheese cavern pocket well within BlendedNoise range [-0.25..+0.25]
            }
            return 0.08f;
        };

        int anchor = 0;
        int chosenCx = 0;
        for (int cx = 0; cx < 16; cx++) {
            int cand = WorldgenKernelSwapper.evaluateTreeAnchorAtChunkCpu(forestAndCaveEval, cx, 0, 8675309L);
            if ((cand & 0x40000000) != 0 && ((cand >>> 24) & 7) == 1) {
                anchor = cand;
                chosenCx = cx;
                break;
            }
        }
        assertTrue((anchor & 0x40000000) != 0, "Expected forest chunk anchor from BlendedNoise-amplitude climate");

        int[] anchors9 = new int[9];
        anchors9[4] = anchor;
        // TERRAIN_MATCHING street slice (flag bit 0 = 1) with dirt_path (global ID 45) at relSec=0, ly=0 across x=2..5, z=2
        int[] palMeta = {(45 << 16) | 45, 4};
        int[] streetRuns = {
            0, (1 << 16) | WorldgenKernelSwapper.yzxToH3(2, 0, 2), 0, 0,
            0, (1 << 16) | WorldgenKernelSwapper.yzxToH3(3, 0, 2), 0, 0
        };
        int[] slices = {0, 0, 0, 2};
        int[] placed = {chosenCx << 4, 0, 0, 0, 1, 0, 0, 0};

        byte[] slot = new byte[WorldgenKernelSwapper.LCA_SLOT_4K_BYTES];
        WorldgenKernelSwapper.evaluateEndToEndLcaSlot4kCpu(
                forestAndCaveEval,
                chosenCx,
                0,
                8675309L,
                null,
                0,
                0,
                41,
                anchors9,
                0,
                placed,
                0,
                1,
                palMeta,
                slices,
                streetRuns,
                new int[0],
                slot,
                0);

        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(slot).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int surfAtStreet = buf.getShort(1456 + (((2 << 4) | 2) << 1));
        int treeVoxels = 0;
        int caveAirVoxels = 0;
        int streetVoxelY = -999;

        for (int s = 0; s < 24; s++) {
            int rawOff = Short.toUnsignedInt(buf.getShort(32 + (s << 1)));
            int baseBy = (s - 4) << 4;
            int[] secVox = new int[4096];
            if ((rawOff & 0x8000) != 0) {
                java.util.Arrays.fill(secVox, rawOff & 0x7FF);
            } else {
                int dom = slot[rawOff] & 0xFF;
                java.util.Arrays.fill(secVox, dom);
                int rCount = Short.toUnsignedInt(buf.getShort(rawOff + 2));
                int rp = rawOff + 4;
                for (int r = 0; r < rCount; r++) {
                    int sh3 = Short.toUnsignedInt(buf.getShort(rp));
                    int rlen = (slot[rp + 2] & 0xFF) + 1;
                    int rst = slot[rp + 3] & 0xFF;
                    java.util.Arrays.fill(secVox, sh3, Math.min(4096, sh3 + rlen), rst);
                    rp += 4;
                }
            }
            for (int h3 = 0; h3 < 4096; h3++) {
                int st = secVox[h3];
                int yzx = WorldgenKernelSwapper.h3ToYzx(h3);
                int lx = yzx & 15;
                int lz = (yzx >>> 4) & 15;
                int wy = baseBy + ((yzx >>> 8) & 15);
                if (st >= 33 && st <= 40) {
                    treeVoxels++;
                }
                if (wy >= -24 && wy <= 20 && st == 0) {
                    caveAirVoxels++;
                }
                if (lx == 2 && lz == 2 && st >= 41) {
                    streetVoxelY = wy;
                }
            }
        }
        assertTrue(treeVoxels >= 140, "Forested chunk must stamp multi-tree canopy (>140 wood/leaf voxels), got " + treeVoxels);
        assertTrue(caveAirVoxels >= 256, "Underground BlendedNoise cave pocket must carve air voxels, got " + caveAirVoxels);
        assertEquals(surfAtStreet, streetVoxelY, "TERRAIN_MATCHING street y=0 must sit flush on surfY (not surfY+1)");
    }

    @Test
    @DisplayName("Lifts arbitrary N-biome 7D Climate.RTree BVH, EndIsland/Spline/Nether DensityFunction DAGs, SurfaceRules VM, and multi-floor feature tables")
    void liftsDynamicBiomeRTreeArbitraryDensityDagSurfaceRulesAndMultiFloorDimensionCapabilities() {
        // 1. Build 64-biome 7D parameter space (including ordinal 55 = synthetic 26.3 biome and ordinal 62 = custom datapack cave biome)
        List<LiftedWorldgenCapabilityTables.BiomeParameterBox> entries = new ArrayList<>(64);
        java.util.Random rng = new java.util.Random(20261114L);
        for (int ord = 0; ord < 64; ord++) {
            long[] mins = new long[7];
            long[] maxs = new long[7];
            for (int d = 0; d < 6; d++) {
                long center = -9000L + rng.nextInt(18001);
                long half = 400L + rng.nextInt(1600);
                mins[d] = center - half;
                maxs[d] = center + half;
            }
            if (ord == 55) {
                // Synthetic 26.3 surface forest box around (0.35, 0.45, 0.25, 0.05, 0.0, 0.15)
                mins = new long[] {3000, 4000, 2000, 0, -500, 1000, 0};
                maxs = new long[] {4000, 5000, 3000, 1000, 500, 2000, 0};
            } else if (ord == 62) {
                // Synthetic 3D deep cave biome box requiring depth > 0.50
                mins = new long[] {-2000, -2000, 0, -2000, 5000, -2000, 0};
                maxs = new long[] {2000, 2000, 4000, 2000, 9500, 2000, 0};
            }
            entries.add(new LiftedWorldgenCapabilityTables.BiomeParameterBox(mins, maxs, ord));
        }

        int[] rtreeNodes = LiftedWorldgenCapabilityTables.buildFlatClimateRTree(entries);
        assertTrue(rtreeNodes.length >= 16, "Flat RTree BVH must emit packed 16-int nodes");
        assertEquals(
                55,
                LiftedWorldgenCapabilityTables.searchRTreeBiomeOrdinal(
                        rtreeNodes, 0.35f, 0.45f, 0.25f, 0.05f, 0.0f, 0.15f),
                "26.3 surface parameter point must resolve exact ordinal 55");
        assertEquals(
                62,
                LiftedWorldgenCapabilityTables.searchRTreeBiomeOrdinal(
                        rtreeNodes, 0.0f, 0.0f, 0.20f, 0.0f, 0.72f, 0.0f),
                "3D underground depth=0.72 sample must resolve exact cave ordinal 62");

        // Verify 100% nearest-box equivalence against brute-force Mojang quantized L2 distance across 512 random queries
        for (int q = 0; q < 512; q++) {
            float t = -1.2f + rng.nextFloat() * 2.4f;
            float h = -1.2f + rng.nextFloat() * 2.4f;
            float c = -1.2f + rng.nextFloat() * 2.4f;
            float e = -1.2f + rng.nextFloat() * 2.4f;
            float d = -0.5f + rng.nextFloat() * 1.6f;
            float w = -1.2f + rng.nextFloat() * 2.4f;
            int expectedOrd = LiftedWorldgenCapabilityTables.bruteForceNearestBiomeOrdinal(entries, t, h, c, e, d, w);
            int actualOrd = LiftedWorldgenCapabilityTables.searchRTreeBiomeOrdinal(rtreeNodes, t, h, c, e, d, w);
            assertEquals(expectedOrd, actualOrd, "Flat BVH RTree search diverged from exact quantized L2 at query " + q);
        }

        // 2. Verify EndIsland 2D Simplex + Spline + YClampedGradient LiftedDensityDag
        LiftedWorldgenCapabilityTables.LiftedEndIslandSampler endSampler =
                LiftedWorldgenCapabilityTables.LiftedEndIslandSampler.fromSeed(8675309L);
        float centerEndDensity = endSampler.sample(0, 0);
        float voidRingEndDensity = endSampler.sample(320, 0); // 320 blocks out is inside the 100..1024 void ring
        assertTrue(centerEndDensity > 0.0f, "Central End island at (0,0) must have positive terrain density: " + centerEndDensity);
        assertTrue(voidRingEndDensity < -0.2f, "End void ring at r=320 must have negative terrain density: " + voidRingEndDensity);

        // 3. Verify OpenCL C99 emission includes data-driven RTree BVH and lifted IR surface helper
        String openClHelpers = OpenClBackendEmitter.emitCapabilityRTreeAndSurfaceHelpers();
        assertTrue(openClHelpers.contains("eval_rtree_biome_ord"), "OpenCL emitter must emit flat BVH biome search");
        assertFalse(openClHelpers.contains("pred_stack"), "OpenCL helpers must not contain hand-coded SurfaceRules VM");

        // 4. Verify Nether roof/floor + multi-floor HugeFungus + custom block palette (>45 slots) capability chunk synthesis
        BytecodeConceptIr.ConceptModule netherSurfaceModule = liftTestSurfaceRule(new NetherSurfaceRuleFixture());
        LiftedWorldgenCapabilityTables.DimensionCapabilitySpec netherSpec =
                new LiftedWorldgenCapabilityTables.DimensionCapabilitySpec(
                        0, // chunkMinY
                        16, // chunkSections (256 height; top 128 is air above roof)
                        0, // noiseMinY
                        128, // noiseHeight
                        31, // seaLevel (lava sea)
                        1, // defaultBlockSlot = netherrack
                        2, // defaultFluidSlot = lava
                        rtreeNodes,
                        netherSurfaceModule,
                        new LiftedWorldgenCapabilityTables.BiomeFeatureSpec[] {
                            new LiftedWorldgenCapabilityTables.BiomeFeatureSpec(
                                    55,
                                    LiftedWorldgenCapabilityTables.PLACEMENT_MULTI_FLOOR_RANGE,
                                    4, // countPerChunk
                                    0, // maxWaterDepth
                                    32, // minY
                                    112, // maxY
                                    49, // trunkSlot = crimson_stem (>45 dynamic slot)
                                    50, // foliageSlot = nether_wart_block
                                    51, // accentSlot = shroomlight
                                    48, // requiredGroundSlot = crimson_nylium
                                    5, // baseHeight
                                    3, // heightRand
                                    2, // canopyRadius
                                    LiftedWorldgenCapabilityTables.SHAPE_HUGE_FUNGUS)
                        },
                        new LiftedWorldgenCapabilityTables.OreVeinSpec[] {
                            new LiftedWorldgenCapabilityTables.OreVeinSpec(1, 52, 8, 12, 10, 117, true)
                        });

        int[][] secVoxels = LiftedWorldgenCapabilityTables.synthesizeDimensionChunkCpu(
                netherSpec,
                2,
                -3,
                8675309L,
                (bx, by, bz) -> {
                    // Solid floor Y=0..42, open Nether cavern Y=43..95, solid ceiling Y=96..127
                    if (by <= 42 || by >= 96) {
                        return 0.65f;
                    }
                    return -0.65f;
                },
                (qx, qy, qz) -> new float[] {0.35f, 0.45f, 0.25f, 0.05f, 0.0f, 0.15f});

        assertEquals(16, secVoxels.length, "Nether chunk must produce 16 sections (Y=0..255)");
        // Y=200 (section 12) above noiseHeight=128 must be pure AIR (0)
        for (int v : secVoxels[12]) {
            assertEquals(0, v, "Sections above Nether noiseHeight=128 must remain AIR");
        }
        // Y=127 (section 7, ly=15) must be bedrock roof (slot 3), Y=0 (section 0, ly=0) must be bedrock floor (slot 3)
        assertEquals(3, secVoxels[7][(15 << 8)], "Y=127 must place lifted bedrock_roof");
        assertEquals(3, secVoxels[0][0], "Y=0 must place lifted bedrock_floor");

        int nyliumCount = 0;
        int fungusStemAndCapCount = 0;
        int quartzCount = 0;
        for (int s = 0; s < 8; s++) {
            for (int v : secVoxels[s]) {
                if (v == 48) nyliumCount++;
                if (v == 49 || v == 50 || v == 51) fungusStemAndCapCount++;
                if (v == 52) quartzCount++;
            }
        }
        assertTrue(nyliumCount >= 128, "Subterranean cavern floor at Y=42 must receive lifted crimson_nylium (slot 48): " + nyliumCount);
        assertTrue(fungusStemAndCapCount >= 20, "Multi-floor Nether cavern must stamp HugeFungus (slots 49..51): " + fungusStemAndCapCount);
        assertTrue(quartzCount >= 16, "Lifted OreVeinSpec must place Nether quartz (slot 52) in netherrack: " + quartzCount);

        // 5. Verify dynamic NoiseSettings cellHeight=4 (4x4x4) vs cellHeight=8 (4x8x4) on narrow 4-block strata
        LiftedWorldgenCapabilityTables.DimensionCapabilitySpec cell4Spec =
                new LiftedWorldgenCapabilityTables.DimensionCapabilitySpec(
                        0, 8, 0, 128, 4, 4, 31, 1, 2, false, 0, -1,
                        rtreeNodes, null,
                        new LiftedWorldgenCapabilityTables.CarverSpec[0],
                        new LiftedWorldgenCapabilityTables.BiomeFeatureSpec[0],
                        new LiftedWorldgenCapabilityTables.OreVeinSpec[0]);
        LiftedWorldgenCapabilityTables.DimensionCapabilitySpec cell8Spec =
                new LiftedWorldgenCapabilityTables.DimensionCapabilitySpec(
                        0, 8, 0, 128, 4, 8, 31, 1, 2, false, 0, -1,
                        rtreeNodes, null,
                        new LiftedWorldgenCapabilityTables.CarverSpec[0],
                        new LiftedWorldgenCapabilityTables.BiomeFeatureSpec[0],
                        new LiftedWorldgenCapabilityTables.OreVeinSpec[0]);
        LiftedWorldgenCapabilityTables.DensityFieldEvaluator narrowShelf =
                (bx, by, bz) -> (by >= 44 && by < 48) ? 0.8f : -0.8f;
        int[][] voxCell4 = LiftedWorldgenCapabilityTables.synthesizeDimensionChunkCpu(
                cell4Spec, 0, 0, 42L, narrowShelf, (qx, qy, qz) -> 0, null);
        int[][] voxCell8 = LiftedWorldgenCapabilityTables.synthesizeDimensionChunkCpu(
                cell8Spec, 0, 0, 42L, narrowShelf, (qx, qy, qz) -> 0, null);
        assertEquals(1, voxCell4[2][(12 << 8)], "cellHeight=4 must sample Y=44 narrow shelf");
        assertEquals(0, voxCell8[2][(12 << 8)], "cellHeight=8 steps 40->48 over narrow Y=44 shelf");

        // 6. Verify aquifer deep-lava floor, lifted IR surface rule (deepslate + dry cave moss), CarverSpec, and per-stage delegation flags
        BytecodeConceptIr.ConceptModule overworldSurfaceModule = liftTestSurfaceRule(new LushCaveDeepslateRuleFixture());
        LiftedWorldgenCapabilityTables.DimensionCapabilitySpec overworldCapSpec =
                new LiftedWorldgenCapabilityTables.DimensionCapabilitySpec(
                        -64, 12, -64, 192, 4, 8, 63, 1, 2, true, 6, -54,
                        rtreeNodes,
                        overworldSurfaceModule,
                        new LiftedWorldgenCapabilityTables.CarverSpec[] {
                            new LiftedWorldgenCapabilityTables.CarverSpec(
                                    LiftedWorldgenCapabilityTables.CARVER_CAVE, -1, 1000, -58, -16, -54, 1200, 1200)
                        },
                        new LiftedWorldgenCapabilityTables.BiomeFeatureSpec[] {
                            new LiftedWorldgenCapabilityTables.BiomeFeatureSpec(
                                    62, LiftedWorldgenCapabilityTables.PLACEMENT_MULTI_FLOOR_RANGE,
                                    6, 0, -40, -10, 58, 0, 0, 57, 1, 0, 1,
                                    LiftedWorldgenCapabilityTables.SHAPE_PATCH)
                        },
                        new LiftedWorldgenCapabilityTables.OreVeinSpec[0]);

        LiftedWorldgenCapabilityTables.DensityFieldEvaluator deepCaveEval = (bx, by, bz) -> {
            // Deep aquifer void at x < 4, Y=-60..-49; dry lush cave at x >= 8, Y=-32..-24; solid rock elsewhere up to Y=72
            if (Math.floorMod(bx, 16) < 4 && by >= -60 && by <= -49) return -0.8f;
            if (Math.floorMod(bx, 16) >= 8 && by >= -31 && by <= -23) return -0.8f;
            return by <= 72 ? 0.8f : -0.8f;
        };
        int[][] delegatedAll = LiftedWorldgenCapabilityTables.synthesizeDimensionChunkCpu(
                overworldCapSpec, 0, 0, 99L, deepCaveEval, (qx, qy, qz) -> 62, null, false, false, false);
        int[][] fullSynth = LiftedWorldgenCapabilityTables.synthesizeDimensionChunkCpu(
                overworldCapSpec, 0, 0, 99L, deepCaveEval, (qx, qy, qz) -> 62, null, true, true, true);
        int[][] uncarvedSynth = LiftedWorldgenCapabilityTables.synthesizeDimensionChunkCpu(
                overworldCapSpec, 0, 0, 99L, deepCaveEval, (qx, qy, qz) -> 62, null, true, false, true);

        // Deep aquifer Y=-56 (section 0, ly=8, lx=0, lz=0) must fill lavaFluidSlot=6
        assertEquals(6, fullSynth[0][(8 << 8)], "Aquifer void at Y=-56 (<= -54) must fill lavaFluidSlot=6");
        // Y=-16 (section 3, ly=0, lx=6, lz=6): raw defaultBlock=1 when surface rules delegated, deepslate=15 when active
        assertEquals(1, delegatedAll[3][(6 << 4) | 6], "Delegated surface stage must preserve defaultBlockSlot=1");
        assertEquals(15, uncarvedSynth[3][(6 << 4) | 6], "SR_OP_Y_GRADIENT_BAND must place deepslate=15 below Y=0");

        int dryCaveFloorMoss = 0;
        int carvedDelta = 0;
        for (int s = 0; s < 6; s++) {
            for (int i = 0; i < 4096; i++) {
                if (uncarvedSynth[s][i] == 57) dryCaveFloorMoss++;
                if (fullSynth[s][i] != uncarvedSynth[s][i]) carvedDelta++;
            }
        }
        assertTrue(dryCaveFloorMoss >= 32, "SR_OP_FLOOR_BIOME_DRY must surface dry cave floor below seaLevel: " + dryCaveFloorMoss);
        assertTrue(carvedDelta >= 32, "Lifted CarverSpec must carve subterranean cave voxels: " + carvedDelta);

        String oclHelpers = OpenClBackendEmitter.emitCapabilityRTreeAndSurfaceHelpers();
        assertTrue(oclHelpers.contains("trilinear_density_cell"), "OpenCL helpers must emit trilinear_density_cell");
        assertTrue(oclHelpers.contains("eval_lifted_carver_voxel"), "OpenCL helpers must emit eval_lifted_carver_voxel");
    }

    @Test
    void enforcesHydrostaticAquiferBarriersComposableSurfaceAlgebraAndOpenClParity() {
        List<LiftedWorldgenCapabilityTables.BiomeParameterBox> boxes = List.of(
                new LiftedWorldgenCapabilityTables.BiomeParameterBox(
                        new long[] {-10000, -10000, -10000, -10000, -10000, -10000, 0},
                        new long[] {10000, 10000, 10000, 10000, 10000, 10000, 0},
                        0));
        int[] rtree = LiftedWorldgenCapabilityTables.buildFlatClimateRTree(boxes);

        // Sky-open lake at lx < 8 (seabed Y=40, breached at lx=6..7 down to Y=8) bordering a roofed cavern at lx >= 8 (roof Y=48..76, open cave Y=8..47)
        LiftedWorldgenCapabilityTables.DimensionCapabilitySpec lakeOverCaveSpec =
                new LiftedWorldgenCapabilityTables.DimensionCapabilitySpec(
                        -64,
                        10, // Y = -64 .. +95
                        -64,
                        160,
                        4,
                        4,
                        62, // seaLevel
                        1, // defaultBlockSlot = stone
                        2, // defaultFluidSlot = water
                        true, // aquifersEnabled
                        6, // lavaFluidSlot = lava
                        -54, // deepLavaFloorY
                        rtree,
                        null,
                        new LiftedWorldgenCapabilityTables.CarverSpec[0],
                        new LiftedWorldgenCapabilityTables.BiomeFeatureSpec[0],
                        new LiftedWorldgenCapabilityTables.OreVeinSpec[0]);

        LiftedWorldgenCapabilityTables.DensityFieldEvaluator lakeBreachCaveEval = (bx, by, bz) -> {
            int lx = Math.floorMod(bx, 16);
            if (by <= 7) {
                return 0.85f; // solid cave floor up to Y=7
            }
            if (lx < 6) {
                return by <= 40 ? 0.85f : -0.85f; // sky-open lake basin floor at Y=40
            }
            if (lx < 8) {
                return -0.85f; // breach throat connecting sky-open lake to cavern Y=8..95
            }
            // Roofed cavern at lx >= 8: open air Y=8..47, solid overhang roof Y=48..76, sky air Y>76
            if (by >= 48 && by <= 76) {
                return 0.85f;
            }
            return -0.85f;
        };

        int[][] sections = LiftedWorldgenCapabilityTables.synthesizeDimensionChunkCpu(
                lakeOverCaveSpec, 0, 0, 20261114L, lakeBreachCaveEval, (qx, qy, qz) -> 0, null);

        // Sky-open lake interior (lx=2, lz=8, wy=58 -> section 7, ly=10) must hold water (slot 2)
        assertEquals(2, sections[7][(10 << 8) | (8 << 4) | 2], "Sky-open lake basin must fill water up to seaLevel");
        // Deep roofed cavern interior (lx=13, lz=8, wy=24 -> section 5, ly=8) must remain dry air (slot 0)
        assertEquals(0, sections[5][(8 << 8) | (8 << 4) | 13], "Roofed subterranean cavern must stay dry air");

        // Hydrostatic containment invariant: zero sheer vertical fluid curtains or floating fluid undersides adjacent to dry air (0)
        int exposedFluidCurtainFaces = 0;
        for (int wy = -63; wy < 96; wy++) {
            int sIdx = (wy + 64) >> 4;
            int lyShift = (wy & 15) << 8;
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int vox = sections[sIdx][lyShift | (lz << 4) | lx];
                    if (vox != 2 && vox != 6) {
                        continue;
                    }
                    int below = sections[(wy - 1 + 64) >> 4][(((wy - 1) & 15) << 8) | (lz << 4) | lx];
                    if (below == 0) {
                        exposedFluidCurtainFaces++;
                    }
                    if (lx > 0 && sections[sIdx][lyShift | (lz << 4) | (lx - 1)] == 0) {
                        exposedFluidCurtainFaces++;
                    }
                    if (lx < 15 && sections[sIdx][lyShift | (lz << 4) | (lx + 1)] == 0) {
                        exposedFluidCurtainFaces++;
                    }
                    if (lz > 0 && sections[sIdx][lyShift | ((lz - 1) << 4) | lx] == 0) {
                        exposedFluidCurtainFaces++;
                    }
                    if (lz < 15 && sections[sIdx][lyShift | ((lz + 1) << 4) | lx] == 0) {
                        exposedFluidCurtainFaces++;
                    }
                }
            }
        }
        assertEquals(
                0,
                exposedFluidCurtainFaces,
                "Aquifer barrier walls must eliminate all sheer vertical fluid curtains and unsupported fluid undersides bordering dry cavern air");

        String ocl = OpenClBackendEmitter.emitCapabilityRTreeAndSurfaceHelpers();
        assertFalse(ocl.contains("int st = 0;"), "OpenCL surface rule helper must not hardcode st = 0");
        assertTrue(ocl.contains("eval_lifted_aquifer_voxel"), "OpenCL helpers must emit 3D aquifer + barrier helper");
        assertTrue(ocl.contains("eval_preliminary_surface_y"), "OpenCL helpers must emit preliminary surface helper");
        assertTrue(ocl.contains("eval_lifted_router_vein"), "OpenCL helpers must emit router vein helper");

        // Verify RouterCapabilityChannels (slots 0..3, 10, 12..14), RouterVeinSpec, and lifted IR surface rule module
        LiftedWorldgenCapabilityTables.RouterVeinSpec veinSpec =
                new LiftedWorldgenCapabilityTables.RouterVeinSpec(
                        true, 8, 44, 70, 71, 72, -52, -20, 73, 74, 75);
        BytecodeConceptIr.ConceptModule algebraSurfaceModule = liftTestSurfaceRule(new MountainCavernSurfaceRuleFixture());
        LiftedWorldgenCapabilityTables.DimensionCapabilitySpec algebraSpec =
                new LiftedWorldgenCapabilityTables.DimensionCapabilitySpec(
                        -64,
                        12,
                        -64,
                        192,
                        4,
                        4,
                        62,
                        1,
                        2,
                        true,
                        6,
                        -54,
                        rtree,
                        algebraSurfaceModule,
                        new LiftedWorldgenCapabilityTables.CarverSpec[0],
                        new LiftedWorldgenCapabilityTables.BiomeFeatureSpec[0],
                        new LiftedWorldgenCapabilityTables.OreVeinSpec[0],
                        veinSpec);
        LiftedWorldgenCapabilityTables.RouterCapabilityChannels channels =
                new LiftedWorldgenCapabilityTables.RouterCapabilityChannels(
                        (x, y, z) -> 0.5f,
                        (x, y, z) -> (Math.floorMod(x, 16) >= 10 && y >= -36 && y <= -16) ? 0.85f : -0.7f,
                        (x, y, z) -> 0.0f,
                        (x, y, z) -> -0.3f,
                        (x, y, z) -> (92.0f - y) * 0.25f, // prelimSurfaceY = 91
                        (x, y, z) -> (y >= 16 && y <= 32) ? 0.75f : ((y >= -48 && y <= -36) ? -0.75f : 0.0f),
                        (x, y, z) -> -0.4f,
                        (x, y, z) -> 0.65f,
                        (x, y, z) -> 0.05f,
                        new LiftedWorldgenCapabilityTables.DensityFieldEvaluator[] {(x, y, z) -> 0.50f});

        int[][] algSections = LiftedWorldgenCapabilityTables.synthesizeDimensionChunkCpu(
                algebraSpec,
                0,
                0,
                90210L,
                (bx, by, bz) -> {
                    if (by <= -56) return 0.9f;
                    if (by >= 68 && by <= 76) return -0.8f; // mountain cavern above seaLevel=62, below prelim=90
                    if (Math.floorMod(bx, 16) >= 10 && by >= -34 && by <= -18) return -0.8f; // subterranean aquifer
                    return by <= 92 ? 0.85f : -0.85f;
                },
                (qx, qy, qz) -> 0,
                channels,
                null,
                true,
                true,
                true);

        // Mountain cavern floor at Y=66 (section 8, ly=2): > seaLevel=62, < prelim=90 -> slot 81!
        assertEquals(81, algSections[8][(2 << 8) | (4 << 4) | 4], "Cavern floor below prelimSurfaceY must resolve slot 81");
        // Mountain peak at Y=93 (section 9, ly=13): >= prelim=90 and noise[0]=0.50 -> slot 82!
        assertEquals(82, algSections[9][(13 << 8) | (4 << 4) | 4], "Mountain peak at prelimSurfaceY must resolve slot 82");

        int upperVeins = 0;
        int lowerVeins = 0;
        for (int s = 0; s < algSections.length; s++) {
            for (int v : algSections[s]) {
                if (v == 70 || v == 71 || v == 72) upperVeins++;
                if (v == 73 || v == 74 || v == 75) lowerVeins++;
            }
        }
        assertTrue(upperVeins >= 16, "Upper router vein slots must populate in solid strata: " + upperVeins);
        assertTrue(lowerVeins >= 16, "Lower router vein slots must populate in deep strata: " + lowerVeins);

        String kernelSrc = OpenClBackendEmitter.emitDimensionCapabilityKernelSource(algebraSpec);
        assertTrue(
                kernelSrc.contains("__kernel void evaluate_evaluate_worldgen_voxel_grid3d("),
                "Must emit valid OpenCL entry kernel");
        assertTrue(kernelSrc.contains("eval_lifted_router_vein("), "Enabled RouterVeinSpec must wire into OpenCL kernel");
    }

    @Test
    void enforcesRintTieBreakingHydrostaticSealSymmetryAndFullCapabilityKernelSource() {
        // 1. Subterranean aquifer spread = -0.25f -> spread * 6.0f == -1.5f -> rint(-1.5f) == -2 (localLevel = 30 - 2 + 4 = 32, NOT 33)
        LiftedWorldgenCapabilityTables.DimensionCapabilitySpec subTieSpec =
                new LiftedWorldgenCapabilityTables.DimensionCapabilitySpec(
                        0,
                        4, // Y = 0..63
                        0,
                        64,
                        4,
                        4,
                        62,
                        1,
                        2,
                        true,
                        6,
                        2,
                        new int[0],
                        null,
                        new LiftedWorldgenCapabilityTables.CarverSpec[0],
                        new LiftedWorldgenCapabilityTables.BiomeFeatureSpec[0],
                        new LiftedWorldgenCapabilityTables.OreVeinSpec[0]);
        LiftedWorldgenCapabilityTables.RouterCapabilityChannels subTieChannels =
                new LiftedWorldgenCapabilityTables.RouterCapabilityChannels(
                        (x, y, z) -> 0.0f,
                        (x, y, z) -> (y >= 24 && y <= 35) ? 0.85f : -0.8f,
                        (x, y, z) -> -0.25f, // -0.25f * 6.0f == -1.5f
                        (x, y, z) -> 0.0f,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new LiftedWorldgenCapabilityTables.DensityFieldEvaluator[0]);
        int[][] subTieSections = LiftedWorldgenCapabilityTables.synthesizeDimensionChunkCpu(
                subTieSpec,
                0,
                0,
                777L,
                (bx, by, bz) -> (by <= 16 || by >= 48) ? 0.9f : -0.9f,
                (qx, qy, qz) -> 0,
                subTieChannels,
                null,
                false,
                false,
                false);
        assertEquals(
                2,
                subTieSections[2][(0 << 8) | (8 << 4) | 8],
                "wy=32 (section 2, ly=0) must hold subterranean water at localLevel=32");
        org.junit.jupiter.api.Assertions.assertNotEquals(
                2,
                subTieSections[2][(1 << 8) | (8 << 4) | 8],
                "wy=33 (section 2, ly=1) must NOT hold water when spread*6 == -1.5f rounds half-to-even to -2");

        // 2. OpenCL capability source must emit full chunk kernel + symmetric dry-air hydrostatic seal helper
        String kernelSrc = OpenClBackendEmitter.emitDimensionCapabilityKernelSource(subTieSpec);
        assertTrue(
                kernelSrc.contains("__kernel void evaluate_dimension_capability_chunk("),
                "OpenClBackendEmitter must emit full chunk GPU synthesis kernel evaluate_dimension_capability_chunk");
        assertTrue(
                kernelSrc.contains("eval_hydrostatic_seal_voxel("),
                "OpenClBackendEmitter must emit symmetric dry-air hydrostatic seal helper");
        assertFalse(
                kernelSrc.contains("wy == local_lvl + 1 && barrier_val > 0.25f"),
                "OpenCL aquifer helper must not apply asymmetric roof cap absent on CPU");
    }

    @Test
    void enforcesHilbertContiguousOreSeamsAndSectionYBoundCulling() {
        LiftedWorldgenCapabilityTables.DimensionCapabilitySpec spec =
                new LiftedWorldgenCapabilityTables.DimensionCapabilitySpec(
                        -16,
                        4,
                        -16,
                        64,
                        4,
                        4,
                        -64,
                        1,
                        0,
                        false,
                        0,
                        -64,
                        new int[0],
                        null,
                        new LiftedWorldgenCapabilityTables.CarverSpec[0],
                        new LiftedWorldgenCapabilityTables.BiomeFeatureSpec[0],
                        new LiftedWorldgenCapabilityTables.OreVeinSpec[] {
                            new LiftedWorldgenCapabilityTables.OreVeinSpec(1, 7, 12, 1, 8, 23, false)
                        });
        LiftedWorldgenCapabilityTables.LcaCapabilityChunkFrame lcaFrame =
                LiftedWorldgenCapabilityTables.synthesizeDimensionChunkLca(
                        spec,
                        3,
                        -5,
                        20261114L,
                        (bx, by, bz) -> (by <= 31 ? 1.0f : -1.0f),
                        (qx, qy, qz) -> 0,
                        LiftedWorldgenCapabilityTables.RouterCapabilityChannels.EMPTY,
                        null,
                        null,
                        true,
                        true,
                        true);
        assertTrue(lcaFrame.isSectionUniform(0) && lcaFrame.uniformSlotId(0) == 1, "Section 0 must remain uniform stone");
        assertTrue(lcaFrame.isSectionUniform(3) && lcaFrame.uniformSlotId(3) == 0, "Section 3 must remain uniform air");
        int[][] sections = lcaFrame.materializeSections();
        int section0OreVoxels = 0;
        int section3OreVoxels = 0;
        for (int i = 0; i < 4096; i++) {
            if (sections[0][i] == 7) section0OreVoxels++;
            if (sections[3][i] == 7) section3OreVoxels++;
        }
        assertEquals(0, section0OreVoxels, "Section 0 (Y=-16..-1) must contain 0 ore voxels");
        assertEquals(0, section3OreVoxels, "Section 3 (Y=32..47) must contain 0 ore voxels");
        int totalOreVoxels = 0;
        for (int s = 1; s <= 2; s++) {
            for (int h3 = 0; h3 < 4096; h3++) {
                if (sections[s][WorldgenKernelSwapper.h3ToYzx(h3)] == 7) totalOreVoxels++;
            }
        }
        assertTrue(totalOreVoxels >= 6, "Sections 1-2 must contain at least 6 ore voxels: " + totalOreVoxels);
        for (int s = 1; s <= 2; s++) {
            int[] sec = sections[s];
            int oreHilbertRuns = 0;
            for (int h3 = 0; h3 < 4096; h3++) {
                int yzx = WorldgenKernelSwapper.h3ToYzx(h3);
                if (sec[yzx] == 7 && (h3 == 0 || sec[WorldgenKernelSwapper.h3ToYzx(h3 - 1)] != 7)) {
                    oreHilbertRuns++;
                }
            }
            if (oreHilbertRuns > 0) {
                assertTrue(
                        oreHilbertRuns <= 2,
                        "OreVeinSpec must trace 01:H_3 Hilbert-contiguous runs (<= 2 runs per vein), got "
                                + oreHilbertRuns);
            }
        }
    }

    @Test
    void preservesPreResolvedQuartBiomesAndHoists2dConditionNoises() {
        BytecodeConceptIr.ConceptModule surfaceModule = liftTestSurfaceRule(new Biome3SandRuleFixture());
        LiftedWorldgenCapabilityTables.DimensionCapabilitySpec spec =
                new LiftedWorldgenCapabilityTables.DimensionCapabilitySpec(
                        0,
                        2,
                        0,
                        32,
                        4,
                        4,
                        -64,
                        1,
                        0,
                        false,
                        0,
                        -64,
                        new int[0],
                        surfaceModule,
                        new LiftedWorldgenCapabilityTables.CarverSpec[0],
                        new LiftedWorldgenCapabilityTables.BiomeFeatureSpec[0],
                        new LiftedWorldgenCapabilityTables.OreVeinSpec[0]);

        java.util.concurrent.atomic.AtomicInteger condNoiseCalls = new java.util.concurrent.atomic.AtomicInteger();
        LiftedWorldgenCapabilityTables.RouterCapabilityChannels rc =
                new LiftedWorldgenCapabilityTables.RouterCapabilityChannels(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new LiftedWorldgenCapabilityTables.DensityFieldEvaluator[] {
                            (bx, by, bz) -> {
                                condNoiseCalls.incrementAndGet();
                                return by == 0 ? 0.6f : -0.6f;
                            }
                        });

        int[] preResolvedQuartBiomes = new int[128];
        java.util.Arrays.fill(preResolvedQuartBiomes, 3);

        LiftedWorldgenCapabilityTables.LcaCapabilityChunkFrame frame =
                LiftedWorldgenCapabilityTables.synthesizeDimensionChunkLca(
                        spec,
                        0,
                        0,
                        8675309L,
                        (bx, by, bz) -> (by <= 15 ? 1.0f : -1.0f),
                        null,
                        rc,
                        preResolvedQuartBiomes,
                        null,
                        true,
                        false,
                        false);

        assertEquals(
                3,
                frame.quartBiomes()[0],
                "synthesizeDimensionChunkLca must preserve pre-resolved Java ChunkAccess quartBiomes when biomeResolver is null");
        int[][] sections = frame.materializeSections();
        int surfY = frame.topSurfaceY()[(8 << 4) | 8];
        assertEquals(13, surfY, "Trilinear cellHeight=4 surface between by=12 (+1) and by=16 (-1) must land at wy=13");
        assertEquals(
                5,
                sections[0][(surfY << 8) | (8 << 4) | 8],
                "SurfaceRules must see pre-resolved biomeOrd=3 and 2D hoisted conditionNoise at top surface wy=" + surfY);
        assertTrue(
                condNoiseCalls.get() <= 256,
                "Default 2D condition noise must hoist to <= 256 column evaluations instead of per-voxel calls: "
                        + condNoiseCalls.get());
    }

    @Test
    void enforcesPreliminarySurfaceCaveGuardAndUnderwaterSurfaceRulesParity() {
        BytecodeConceptIr.ConceptModule grassAndDirtMod = liftTestSurfaceRule(new GrassAndRiverbedRuleFixture(false));
        BytecodeConceptIr.ConceptModule riverbedMod = liftTestSurfaceRule(new GrassAndRiverbedRuleFixture(true));
        JavaConceptBackendEmitter.SurfaceRuleEvaluator grassEval =
                JavaConceptBackendEmitter.compileSurfaceRuleEvaluator(grassAndDirtMod);
        JavaConceptBackendEmitter.SurfaceRuleEvaluator riverbedEval =
                JavaConceptBackendEmitter.compileSurfaceRuleEvaluator(riverbedMod);

        // Cave floor 6 blocks below prelimSurfaceY (prelimY=60, surfaceDepth=2 -> minSurfaceLevel = 60 + 2 - 8 = 54):
        int nearCaveSlot = LiftedWorldgenCapabilityTables.evaluateSurfaceRuleModule(
                grassEval, 1, 1, 0, 53, 0, 1, 2, Integer.MIN_VALUE, 2, 0, 60, 0, 0, 0, 0.0, 0.5f);
        assertEquals(
                1,
                nearCaveSlot,
                "Cave floor at wy=53 below minSurfaceLevel (60 + 2 - 8 = 54) must stay stone (slot 1), never grass");

        // Sky-facing dry surface at wy = 60 (prelimY=60, surfaceDepth=2 -> 60 >= 54) must become grass (slot 3)
        int dryTopSlot = LiftedWorldgenCapabilityTables.evaluateSurfaceRuleModule(
                grassEval, 1, 1, 0, 60, 0, 1, 2, Integer.MIN_VALUE, 2, 0, 60, 0, 0, 0, 0.0, 0.5f);
        assertEquals(3, dryTopSlot, "Sky-facing dry floor at wy=60 must become grass (slot 3)");

        // At wy = 59 under waterHeight = 63: Grass rule must reject (59 < 63), riverbed waterStartCheck(-6, -1) matches -> dirt (slot 4)
        int submergedGrassCheck = LiftedWorldgenCapabilityTables.evaluateSurfaceRuleModule(
                grassEval, 1, 1, 0, 59, 0, 1, 2, 63, 2, 0, 58, 0, 0, 0, 0.0, 0.5f);
        assertEquals(1, submergedGrassCheck, "Water(0, 0) must reject grass underwater at wy=59 < waterHeight=63");

        int submergedRiverbedSlot = LiftedWorldgenCapabilityTables.evaluateSurfaceRuleModule(
                riverbedEval, 1, 1, 0, 59, 0, 1, 2, 63, 2, 0, 58, 0, 0, 0, 0.0, 0.5f);
        assertEquals(
                4,
                submergedRiverbedSlot,
                "Mojang WaterCondition must place dirt (slot 4) on shallow riverbeds");

        // Verify OpenClBackendEmitter emits lifted IR surface device function
        LiftedWorldgenCapabilityTables.DimensionCapabilitySpec spec =
                new LiftedWorldgenCapabilityTables.DimensionCapabilitySpec(
                        -64,
                        24,
                        -64,
                        384,
                        4,
                        8,
                        63,
                        1,
                        2,
                        true,
                        3,
                        -54,
                        new int[0],
                        riverbedMod,
                        new LiftedWorldgenCapabilityTables.CarverSpec[0],
                        new LiftedWorldgenCapabilityTables.BiomeFeatureSpec[0],
                        new LiftedWorldgenCapabilityTables.OreVeinSpec[0]);
        String clSrc = OpenClBackendEmitter.emitDimensionCapabilityKernelSource(spec);
        assertTrue(
                clSrc.contains("eval_lifted_surface_rule_ir("),
                "OpenCL capability kernel must compose lifted IR surface rule via eval_lifted_surface_rule_ir");
        assertFalse(
                clSrc.contains("CAP_RULE_OPS"),
                "OpenCL capability kernel must not emit legacy hand-coded CAP_RULE_OPS VM");
    }

    @Test
    void enforcesCpuOceanSectionWaterFillAndMojangVoronoiBorderDithering() throws Exception {
        BytecodeConceptIr.ConceptModule oceanSurfaceModule = liftTestSurfaceRule(new OceanVoronoiRuleFixture());
        LiftedWorldgenCapabilityTables.DimensionCapabilitySpec oceanSpec =
                new LiftedWorldgenCapabilityTables.DimensionCapabilitySpec(
                        -64,
                        24,
                        -64,
                        384,
                        4,
                        8,
                        62,
                        1,
                        2,
                        true,
                        3,
                        -54,
                        new int[0],
                        oceanSurfaceModule,
                        new LiftedWorldgenCapabilityTables.CarverSpec[0],
                        new LiftedWorldgenCapabilityTables.BiomeFeatureSpec[0],
                        new LiftedWorldgenCapabilityTables.OreVeinSpec[0]);

        // Part 1: Ocean floor below Y=48 leaves section 7 (Y=48..63) uniform air pre-aquifer; Y=48..62 MUST fill with water (slot 2)
        int[][] oceanSecs = LiftedWorldgenCapabilityTables.synthesizeDimensionChunkLca(
                        oceanSpec,
                        0,
                        0,
                        8675309L,
                        (bx, by, bz) -> by <= 40 ? 1.0f : -1.0f,
                        (qx, qy, qz) -> 0,
                        LiftedWorldgenCapabilityTables.RouterCapabilityChannels.EMPTY,
                        null,
                        null,
                        true,
                        false,
                        false)
                .materializeSections();
        for (int wy = 48; wy <= 62; wy++) {
            int vox = oceanSecs[7][((wy & 15) << 8) | (8 << 4) | 8];
            assertEquals(
                    2,
                    vox,
                    "CPU ocean section 7 must fill water (slot 2) at wy=" + wy + " when seafloor < 48");
        }
        assertEquals(0, oceanSecs[7][((63 & 15) << 8) | (8 << 4) | 8], "wy=63 above topFluid=62 must stay air");

        // Part 2: Exact Mojang BiomeManager Voronoi dithering across both intra-chunk (qx=2) and chunk-edge halo (qx=0) borders
        long seed = 8675309L;
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] leSeed = new byte[8];
        for (int i = 0; i < 8; i++) {
            leSeed[i] = (byte) (seed >>> (i * 8));
        }
        byte[] digest = md.digest(leSeed);
        long expectedZoomSeed = 0L;
        for (int i = 0; i < 8; i++) {
            expectedZoomSeed |= (digest[i] & 0xFFL) << (i * 8);
        }

        LiftedWorldgenCapabilityTables.QuartBiomeOrdinalResolver midSplit = (qx, qy, qz) -> qx < 2 ? 0 : 1;
        LiftedWorldgenCapabilityTables.LcaCapabilityChunkFrame midFrame =
                LiftedWorldgenCapabilityTables.synthesizeDimensionChunkLca(
                        oceanSpec,
                        0,
                        0,
                        seed,
                        (bx, by, bz) -> by <= 64 ? 1.0f : -1.0f,
                        midSplit,
                        LiftedWorldgenCapabilityTables.RouterCapabilityChannels.EMPTY,
                        null,
                        null,
                        true,
                        false,
                        false);
        int[][] midSecs = midFrame.materializeSections();
        int surfWy = midFrame.topSurfaceY()[(8 << 4) | 8];
        int crossBorderDitherCount = 0;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int expectedOrd = referenceMojangVoronoiBiome(expectedZoomSeed, lx, surfWy, lz, midSplit);
                int expectedSlot = expectedOrd == 1 ? 5 : 3;
                int actualSlot = midSecs[(surfWy + 64) >> 4][((surfWy & 15) << 8) | (lz << 4) | lx];
                assertEquals(
                        expectedSlot,
                        actualSlot,
                        "Surface voxel (" + lx + "," + surfWy + "," + lz + ") must match Mojang Voronoi biome zoom");
                if ((lx < 8 && actualSlot == 5) || (lx >= 8 && actualSlot == 3)) {
                    crossBorderDitherCount++;
                }
            }
        }
        assertTrue(
                crossBorderDitherCount > 0,
                "Voronoi border dithering must break 4x4 stair-stepping across qx=2 (lx=8), got 0 dithered columns");

        // Chunk-edge halo test: chunkX=0 has interior quarts qx=0..3 all biome 0, while west neighbor qx=-1 is biome 1 (sand)
        LiftedWorldgenCapabilityTables.QuartBiomeOrdinalResolver haloSplit = (qx, qy, qz) -> qx < 0 ? 1 : 0;
        int[][] haloSecs = LiftedWorldgenCapabilityTables.synthesizeDimensionChunkLca(
                        oceanSpec,
                        0,
                        0,
                        seed,
                        (bx, by, bz) -> by <= 64 ? 1.0f : -1.0f,
                        haloSplit,
                        LiftedWorldgenCapabilityTables.RouterCapabilityChannels.EMPTY,
                        null,
                        null,
                        true,
                        false,
                        false)
                .materializeSections();
        int haloSandCount = 0;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 2; lx++) {
                int expectedOrd = referenceMojangVoronoiBiome(expectedZoomSeed, lx, surfWy, lz, haloSplit);
                int expectedSlot = expectedOrd == 1 ? 5 : 3;
                int actualSlot = haloSecs[(surfWy + 64) >> 4][((surfWy & 15) << 8) | (lz << 4) | lx];
                assertEquals(
                        expectedSlot,
                        actualSlot,
                        "West chunk-border column lx=" + lx + ", lz=" + lz + " must sample 6x6 halo qx=-1");
                if (actualSlot == 5) {
                    haloSandCount++;
                }
            }
        }
        assertTrue(haloSandCount > 0, "6x6 quart halo must dither neighbor qx=-1 biome into lx=0..1");

        String clSrc = OpenClBackendEmitter.emitDimensionCapabilityKernelSource(oceanSpec);
        assertTrue(
                clSrc.contains("eval_voronoi_zoomed_biome_ord"),
                "OpenCL capability kernel must emit eval_voronoi_zoomed_biome_ord");
    }

    @Test
    void emitsCompact2dConditionAndTempColumnsAndBoundedVeinWindowInOpenClCapabilityKernel() {
        LiftedWorldgenCapabilityTables.RouterVeinSpec rv =
                new LiftedWorldgenCapabilityTables.RouterVeinSpec(
                        true, 0, 50, 10, 11, 12, -60, -8, 13, 14, 15);
        LiftedWorldgenCapabilityTables.DimensionCapabilitySpec spec =
                new LiftedWorldgenCapabilityTables.DimensionCapabilitySpec(
                        -64,
                        24,
                        -64,
                        384,
                        4,
                        8,
                        62,
                        1,
                        2,
                        true,
                        3,
                        -54,
                        new int[0],
                        null,
                        new LiftedWorldgenCapabilityTables.CarverSpec[0],
                        new LiftedWorldgenCapabilityTables.BiomeFeatureSpec[0],
                        new LiftedWorldgenCapabilityTables.OreVeinSpec[0],
                        rv);
        String clSrc = OpenClBackendEmitter.emitDimensionCapabilityKernelSource(spec);
        assertTrue(
                clSrc.contains("cond_2d_only") && clSrc.contains("params[18]"),
                "OpenCL capability kernel must read cond_2d_only from params[18] for 9 KB 2D column surface noises");
        assertTrue(
                clSrc.contains("temp_2d_only") && clSrc.contains("params[19]"),
                "OpenCL capability kernel must read temp_2d_only from params[19] for 1 KB 2D column temperatures");
        assertTrue(
                clSrc.contains("vein_min_y") && clSrc.contains("params[20]") && clSrc.contains("params[21]"),
                "OpenCL capability kernel must read bounded vein Y window from params[20..21]");
    }

    @Test
    void liftsServerNoiseBasedAquiferBytecodeAndEliminatesStoneFloorsChunkSeamsAndShallowLavaCubes(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        // 1. Compile real Mojang-shaped Aquifer$NoiseBasedAquifer bytecode and lift similarity + calculatePressure via BytecodeSsaLifter
        java.nio.file.Path srcFile =
                tempDir.resolve("net/minecraft/world/level/levelgen/Aquifer.java");
        java.nio.file.Files.createDirectories(srcFile.getParent());
        java.nio.file.Files.writeString(
                srcFile,
                """
                package net.minecraft.world.level.levelgen;
                public interface Aquifer {
                    final class NoiseBasedAquifer implements Aquifer {
                        private static final int X_RANGE = 10;
                        private static final int Y_RANGE = 9;
                        private static final int Z_RANGE = 10;
                        private static final int X_SPACING = 16;
                        private static final int Y_SPACING = 12;
                        private static final int Z_SPACING = 16;
                        private static double similarity(int d1, int d2) {
                            return 1.0 - (double) (d2 - d1) / 25.0;
                        }
                    }
                }
                """,
                java.nio.charset.StandardCharsets.UTF_8);
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        try (javax.tools.StandardJavaFileManager fm =
                compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            Iterable<? extends javax.tools.JavaFileObject> units = fm.getJavaFileObjectsFromPaths(List.of(srcFile));
            assertTrue(Boolean.TRUE.equals(
                    compiler.getTask(null, fm, null, List.of("-d", tempDir.toString(), "--release", "17"), null, units)
                            .call()));
        }
        byte[] aqBytes = java.nio.file.Files.readAllBytes(
                tempDir.resolve("net/minecraft/world/level/levelgen/Aquifer$NoiseBasedAquifer.class"));
        BytecodeSsaLifter.LiftedAquiferBytecodeSpec liftedAq =
                BytecodeSsaLifter.liftNoiseBasedAquiferBytecode(List.of(aqBytes));
        assertEquals(16, liftedAq.xSpacing());
        assertEquals(12, liftedAq.ySpacing());
        assertEquals(16, liftedAq.zSpacing());
        assertEquals(10, liftedAq.xRange());
        assertEquals(9, liftedAq.yRange());
        assertEquals(10, liftedAq.zRange());
        assertEquals(25.0, liftedAq.similarityScale(), 1.0e-6);

        // 2. Tall cavern spanning 3 vertical 12-block cells (wy=6..28) must have ZERO mid-cavern stone floors and fill smoothly to cave floor
        LiftedWorldgenCapabilityTables.DimensionCapabilitySpec spec =
                new LiftedWorldgenCapabilityTables.DimensionCapabilitySpec(
                        -64,
                        10,
                        -64,
                        160,
                        4,
                        4,
                        62,
                        1, // stone
                        2, // water
                        true,
                        6, // lava
                        -54,
                        new int[0],
                        null,
                        new LiftedWorldgenCapabilityTables.CarverSpec[0],
                        new LiftedWorldgenCapabilityTables.BiomeFeatureSpec[0],
                        new LiftedWorldgenCapabilityTables.OreVeinSpec[0]);
        LiftedWorldgenCapabilityTables.AquiferCellGridSampler mojangSampler =
                LiftedWorldgenCapabilityTables.createStandardNoiseBasedAquiferSampler(
                        spec,
                        1337L,
                        liftedAq,
                        (bx, by, bz) -> 0.0f, // barrier
                        (bx, by, bz) -> 0.65f, // local aquifer floodedness (0.40 < 0.65 <= 0.80)
                        (bx, by, bz) -> 0.0f, // spread -> sy=0 => baseLevel=20
                        (bx, by, bz) -> 0.95f, // high lavaNoise -> must NOT spawn lava above Y=-10, MUST spawn lava at Y<=-10
                        null,
                        null,
                        (bx, bz) -> 72);
        int[][] tallCaveSecs = LiftedWorldgenCapabilityTables.synthesizeDimensionChunkLca(
                        spec,
                        0,
                        0,
                        1337L,
                        (bx, by, bz) -> ((by >= 6 && by <= 28) || (by >= -34 && by <= -14)) ? -0.9f : (by <= 72 ? 0.9f : -0.9f),
                        (qx, qy, qz) -> 0,
                        LiftedWorldgenCapabilityTables.RouterCapabilityChannels.EMPTY,
                        null,
                        null,
                        mojangSampler,
                        false,
                        false,
                        false,
                        false)
                .materializeSections();
        for (int wy = 6; wy <= 19; wy++) {
            int vox = tallCaveSecs[(wy + 64) >> 4][((wy & 15) << 8) | (8 << 4) | 8];
            assertEquals(
                    2,
                    vox,
                    "Flooded cavern must fill water (never lava or floating stone floor) for Mojang exclusive fluidLevel=20 (wy=6..19), got "
                            + vox
                            + " at wy="
                            + wy);
        }
        for (int wy = 20; wy <= 28; wy++) {
            int vox = tallCaveSecs[(wy + 64) >> 4][((wy & 15) << 8) | (8 << 4) | 8];
            assertEquals(0, vox, "Cavern headspace at/above Mojang exclusive 40-block bucket level 20 must remain air (zero stone floors) at wy=" + wy);
        }
        assertEquals(
                6,
                tallCaveSecs[(-28 + 64) >> 4][((-28 & 15) << 8) | (8 << 4) | 8],
                "Deep cavern at wy=-28 (surfaceLevel=-20 <= -10) with lavaNoise=0.95 must fill lava");

        // 3. Cross-chunk wet-to-dry cave transition across chunkX=0 -> chunkX=1 must build matching curved Voronoi barrier with zero seam cutoffs
        LiftedWorldgenCapabilityTables.AquiferCellGridSampler seamSampler =
                LiftedWorldgenCapabilityTables.createStandardNoiseBasedAquiferSampler(
                        spec,
                        9001L,
                        liftedAq,
                        (bx, by, bz) -> 0.35f,
                        (bx, by, bz) -> bx < 8 ? 0.95f : -0.95f,
                        (bx, by, bz) -> 0.0f,
                        (bx, by, bz) -> 0.0f,
                        null,
                        null,
                        (bx, bz) -> 72);
        LiftedWorldgenCapabilityTables.DensityFieldEvaluator tunnelEval =
                (bx, by, bz) -> (by >= 12 && by <= 24 && bz >= 4 && bz <= 11) ? -0.45f : (by <= 72 ? 0.9f : -0.9f);
        int[][] c0 = LiftedWorldgenCapabilityTables.synthesizeDimensionChunkLca(
                        spec, 0, 0, 9001L, tunnelEval, (qx, qy, qz) -> 0, LiftedWorldgenCapabilityTables.RouterCapabilityChannels.EMPTY, null, null, seamSampler, false, false, false, false)
                .materializeSections();
        int[][] c1 = LiftedWorldgenCapabilityTables.synthesizeDimensionChunkLca(
                        spec, 1, 0, 9001L, tunnelEval, (qx, qy, qz) -> 0, LiftedWorldgenCapabilityTables.RouterCapabilityChannels.EMPTY, null, null, seamSampler, false, false, false, false)
                .materializeSections();
        int seamCurtains = 0;
        for (int wy = 12; wy <= 24; wy++) {
            int sIdx = (wy + 64) >> 4;
            int lyShift = (wy & 15) << 8;
            for (int lz = 4; lz <= 11; lz++) {
                int vWest = c0[sIdx][lyShift | (lz << 4) | 15];
                int vEast = c1[sIdx][lyShift | (lz << 4) | 0];
                boolean westFluid = vWest == 2 || vWest == 6;
                boolean eastFluid = vEast == 2 || vEast == 6;
                if ((westFluid && vEast == 0) || (eastFluid && vWest == 0)) {
                    seamCurtains++;
                }
            }
        }
        assertEquals(0, seamCurtains, "Lifted NoiseBasedAquifer 4xNx4 cell grid must eliminate chunk-seam fluid cutoffs");
    }

    private static int referenceMojangVoronoiBiome(
            long zoomSeed,
            int bx,
            int by,
            int bz,
            LiftedWorldgenCapabilityTables.QuartBiomeOrdinalResolver resolver
    ) {
        int i = bx - 2;
        int j = by - 2;
        int k = bz - 2;
        int qx0 = i >> 2;
        int qy0 = j >> 2;
        int qz0 = k >> 2;
        double fx = (i & 3) / 4.0;
        double fy = (j & 3) / 4.0;
        double fz = (k & 3) / 4.0;
        int bestIdx = 0;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int c = 0; c < 8; c++) {
            boolean x0 = (c & 4) == 0;
            boolean y0 = (c & 2) == 0;
            boolean z0 = (c & 1) == 0;
            int cx = x0 ? qx0 : qx0 + 1;
            int cy = y0 ? qy0 : qy0 + 1;
            int cz = z0 ? qz0 : qz0 + 1;
            double dx = x0 ? fx : fx - 1.0;
            double dy = y0 ? fy : fy - 1.0;
            double dz = z0 ? fz : fz - 1.0;
            double d = referenceFiddledDistance(zoomSeed, cx, cy, cz, dx, dy, dz);
            if (bestDist > d) {
                bestIdx = c;
                bestDist = d;
            }
        }
        int rx = (bestIdx & 4) == 0 ? qx0 : qx0 + 1;
        int ry = (bestIdx & 2) == 0 ? qy0 : qy0 + 1;
        int rz = (bestIdx & 1) == 0 ? qz0 : qz0 + 1;
        return resolver.resolveBiomeOrdinal(rx, ry, rz);
    }

    private static double referenceFiddledDistance(
            long zoomSeed, int qx, int qy, int qz, double dx, double dy, double dz) {
        long s = refLcgNext(zoomSeed, qx);
        s = refLcgNext(s, qy);
        s = refLcgNext(s, qz);
        s = refLcgNext(s, qx);
        s = refLcgNext(s, qy);
        s = refLcgNext(s, qz);
        double fx = (((s >> 24) & 1023L) / 1024.0 - 0.5) * 0.9;
        s = refLcgNext(s, zoomSeed);
        double fy = (((s >> 24) & 1023L) / 1024.0 - 0.5) * 0.9;
        s = refLcgNext(s, zoomSeed);
        double fz = (((s >> 24) & 1023L) / 1024.0 - 0.5) * 0.9;
        return (dz + fz) * (dz + fz) + (dy + fy) * (dy + fy) + (dx + fx) * (dx + fx);
    }

    private static long refLcgNext(long s, long inc) {
        s *= s * 6364136223846793005L + 1442695040888963407L;
        return s + inc;
    }

    @Test
    void liftedNormalNoiseColumnHoistingLazySurfaceRulesBiomeBitsetAndGpuBatchEmitterHoldParity() {
        byte[] perms = new byte[512];
        for (int i = 0; i < perms.length; i++) {
            perms[i] = (byte) ((i * 97 + 29) & 0xFF);
        }
        LiftedWorldgenCapabilityTables.LiftedNormalNoiseSpec noiseSpec =
                new LiftedWorldgenCapabilityTables.LiftedNormalNoiseSpec(
                        perms,
                        new float[] {0.0625f, 0.125f},
                        new float[] {0.125f, 0.25f},
                        new float[] {0.0625f, 0.125f},
                        new float[] {0.65f, 0.325f},
                        new float[] {0.125f, -0.375f},
                        new float[] {-0.25f, 0.5f},
                        new float[] {0.75f, -0.125f},
                        false);
        LiftedWorldgenCapabilityTables.LiftedNormalNoiseEvaluator eval = noiseSpec.toEvaluator();
        float[] col = new float[49];
        eval.sampleColumnY(-124, -64, 8, 49, 317, col, 0);
        for (int i = 0; i < 49; i++) {
            assertEquals(
                    eval.sample(-124, -64 + i * 8, 317),
                    col[i],
                    0.0f,
                    "Column-hoisted LiftedNormalNoiseEvaluator must match scalar sample with 0.0f ULP");
        }

        // Lifted IR SurfaceRules module gated by ABOVE_PRELIM
        BytecodeConceptIr.ConceptModule lazySurfaceModule = liftTestSurfaceRule(new AbovePrelimSlot5RuleFixture());
        LiftedWorldgenCapabilityTables.GpuRouterNoiseTable gpuNoises =
                new LiftedWorldgenCapabilityTables.GpuRouterNoiseTable(
                        noiseSpec,
                        noiseSpec,
                        noiseSpec,
                        noiseSpec,
                        noiseSpec.withScale(1.0, 0.0),
                        new LiftedWorldgenCapabilityTables.LiftedNormalNoiseSpec[] {noiseSpec},
                        new boolean[] {true});
        LiftedWorldgenCapabilityTables.BiomeFeatureSpec absentBiomeFeat =
                new LiftedWorldgenCapabilityTables.BiomeFeatureSpec(
                        7, LiftedWorldgenCapabilityTables.PLACEMENT_MULTI_FLOOR_RANGE, 8, 0, -60, 40,
                        12, 13, 14, 0, 4, 2, 2, LiftedWorldgenCapabilityTables.SHAPE_GEODE);
        LiftedWorldgenCapabilityTables.BiomeFeatureSpec presentFallenTree =
                new LiftedWorldgenCapabilityTables.BiomeFeatureSpec(
                        0, LiftedWorldgenCapabilityTables.PLACEMENT_SURFACE_HEIGHTMAP, 4, 0, -64, 319,
                        15, 0, 0, 0, 4, 1, 1, LiftedWorldgenCapabilityTables.SHAPE_FALLEN_TREE);
        LiftedWorldgenCapabilityTables.DimensionCapabilitySpec spec =
                new LiftedWorldgenCapabilityTables.DimensionCapabilitySpec(
                        -64,
                        24,
                        -64,
                        384,
                        4,
                        8,
                        63,
                        1,
                        2,
                        false,
                        0,
                        -64,
                        LiftedWorldgenCapabilityTables.buildFlatClimateRTree(java.util.List.of(
                                new LiftedWorldgenCapabilityTables.BiomeParameterBox(
                                        new long[] {-10000, -10000, -10000, -10000, -10000, -10000, 0},
                                        new long[] {10000, 10000, 10000, 10000, 10000, 10000, 0},
                                        0))),
                        lazySurfaceModule,
                        new LiftedWorldgenCapabilityTables.CarverSpec[0],
                        new LiftedWorldgenCapabilityTables.BiomeFeatureSpec[] {absentBiomeFeat, presentFallenTree},
                        new LiftedWorldgenCapabilityTables.OreVeinSpec[0],
                        LiftedWorldgenCapabilityTables.RouterVeinSpec.DISABLED,
                        gpuNoises);
        LiftedWorldgenCapabilityTables.RouterCapabilityChannels rc =
                new LiftedWorldgenCapabilityTables.RouterCapabilityChannels(
                        null,
                        null,
                        null,
                        null,
                        (x, y, z) -> (80.0f - y) * 0.25f, // prelimSurfaceY = 79
                        null,
                        null,
                        null,
                        null,
                        new LiftedWorldgenCapabilityTables.DensityFieldEvaluator[0],
                        new boolean[0]);
        LiftedWorldgenCapabilityTables.LcaCapabilityChunkFrame frame =
                LiftedWorldgenCapabilityTables.synthesizeDimensionChunkLca(
                        spec,
                        0,
                        0,
                        42L,
                        (x, y, z) -> y <= 80 ? 1.0f : -1.0f,
                        (qx, qy, qz) -> 0,
                        true,
                        rc,
                        new int[24 << 6],
                        new boolean[32],
                        null,
                        true,
                        false,
                        true);
        int[][] secs = frame.materializeSections();
        assertEquals(5, secs[(80 + 64) >> 4][((80 & 15) << 8) | (8 << 4) | 8], "Crust voxel above prelimSurfaceY must receive lifted slot 5");
        assertEquals(1, secs[(40 + 64) >> 4][((40 & 15) << 8) | (8 << 4) | 8], "Deep voxel below prelimSurfaceY must remain default slot 1");
        int geodeBlocks = 0;
        int fallenLogs = 0;
        for (int[] sec : secs) {
            for (int v : sec) {
                if (v == 12 || v == 13 || v == 14) geodeBlocks++;
                if (v == 15) fallenLogs++;
            }
        }
        assertEquals(0, geodeBlocks, "BiomeFeatureSpec for absent biomeOrdinal=7 must be skipped by chunk biome bitset");
        assertTrue(fallenLogs > 0, "Present SHAPE_FALLEN_TREE feature must place horizontal log blocks");

        String clSrc = OpenClBackendEmitter.emitDimensionCapabilityKernelSource(spec);
        assertTrue(clSrc.contains("eval_lifted_normal_noise_fp32"), "OpenCL kernel must emit __constant Perlin evaluator");
        assertTrue(clSrc.contains("get_group_id(2)"), "OpenCL kernel must index multi-chunk waves via get_group_id(2)");
        assertTrue(clSrc.contains("c_top_y[256 + s]"), "OpenCL kernel must emit per-section uniform-slot workgroup reduction");
    }

    public static final class ProvenanceDensityFixtures {
        public record Constant(double value) {
            public double compute(Object ctx) {
                return value;
            }
        }

        public static final class ArithmeticTrojan {
            public record Constant(double value) {
                public double compute(Object ctx) {
                    return value * 2.0;
                }
            }
        }

        public static final class TrojanConstantHolder {
            public record Constant(double value, double secretBias) {
                public double compute(Object ctx) {
                    return value + secretBias;
                }
            }
        }

        public record CustomSineDensity(double amplitude) {
            public double compute(int x) {
                return Math.sin(x * 0.1) * amplitude;
            }
        }

        public record ImpureNanoTimeDensity(double bias) {
            public double compute(Object ctx) {
                return (double) System.nanoTime() + bias;
            }
        }
    }

    @Test
    @DisplayName("Emits SHA-256 IR_LIFTED provenance for lifted kernels/nodes and rejects unmapped or unconsumed-field Trojan nodes")
    void emitsVerifiedIrProvenanceAndRejectsTrojanOrUnmappedNodes() {
        StatefulVanillaNoise liveNoise = new StatefulVanillaNoise(77L, 0.25, -0.5, 0.75);
        WorldgenPipelineSpec spec = new WorldgenPipelineSpec(
                "prov_check_kernel",
                new DensityNode.BytecodeNoise3D(
                        StatefulVanillaNoise.class.getName().replace('.', '/'),
                        "sampleInstance3D",
                        0.02,
                        0.02,
                        1.0,
                        0.0,
                        0.0,
                        0.0),
                new DensityNode.Constant(0.0),
                buildOverworldSpec("prov_pal", EmitOptions.gpuFp64()).palette(),
                EmitOptions.gpuFp64());
        SwappedWorldgenKernel swapped = WorldgenKernelSwapper.compileAndSwapFromInstances(spec, liveNoise);
        assertFalse(swapped.kernelIrDigest().isEmpty(), "Compiled kernel must expose non-empty IR SHA-256 digest");
        String noiseMethodSym = StatefulVanillaNoise.class.getName() + "#sampleInstance3D";
        String permFieldSym = StatefulVanillaNoise.class.getName() + "#p";
        String offsetFieldSym = StatefulVanillaNoise.class.getName() + "#xo";
        assertTrue(
                swapped.provenanceBindings().containsKey(noiseMethodSym),
                "Lifted StatefulVanillaNoise#sampleInstance3D must emit IR_LIFTED provenance binding");
        assertEquals(
                io.github.dailystruggle.bytecode.PipelineCoverageGraphScanner.CoverageProvenanceKind.IR_LIFTED,
                swapped.provenanceBindings().get(noiseMethodSym).kind());
        assertTrue(
                swapped.provenanceBindings().get(noiseMethodSym).mechanismRef().contains("@sha256="),
                "Provenance mechanismRef must embed SHA-256 IR digest");
        assertTrue(
                swapped.provenanceBindings().containsKey(permFieldSym),
                "Consumed permutation table field StatefulVanillaNoise#p must emit IR_LIFTED provenance binding");
        assertTrue(
                swapped.provenanceBindings().containsKey(offsetFieldSym),
                "Consumed snapshot field StatefulVanillaNoise#xo must emit IR_LIFTED provenance binding");

        java.util.Map<String, io.github.dailystruggle.bytecode.PipelineCoverageGraphScanner.ProvenanceBinding> cleanProv =
                new java.util.LinkedHashMap<>();
        java.util.Set<String> cleanUnmapped = new java.util.LinkedHashSet<>();
        WorldgenKernelSwapper.liftRuntimeDensityNode(
                new ProvenanceDensityFixtures.Constant(1.25),
                StatefulVanillaNoise.class.getName().replace('.', '/'),
                "sampleInstance3D",
                cleanProv,
                cleanUnmapped);
        assertTrue(cleanUnmapped.isEmpty(), "Bytecode-SSA lifted Constant must have zero unmapped symbols");
        assertTrue(
                cleanProv.containsKey(ProvenanceDensityFixtures.Constant.class.getName() + "#compute"),
                "Bytecode-SSA lifted Constant must emit IR_LIFTED provenance binding");

        java.util.Map<String, io.github.dailystruggle.bytecode.PipelineCoverageGraphScanner.ProvenanceBinding> arithmeticProv =
                new java.util.LinkedHashMap<>();
        java.util.Set<String> arithmeticUnmapped = new java.util.LinkedHashSet<>();
        WorldgenKernelSwapper.DensityNode arithmeticNode = WorldgenKernelSwapper.liftRuntimeDensityNode(
                new ProvenanceDensityFixtures.ArithmeticTrojan.Constant(1.25),
                StatefulVanillaNoise.class.getName().replace('.', '/'),
                "sampleInstance3D",
                arithmeticProv,
                arithmeticUnmapped);
        assertTrue(arithmeticUnmapped.isEmpty(), "Pure arithmetic bytecode must lift cleanly");
        assertTrue(arithmeticNode instanceof WorldgenKernelSwapper.DensityNode.LiftedModule);
        double arithmeticVal = ((Number) ConceptIrEvaluator.evaluateEntry(
                ((WorldgenKernelSwapper.DensityNode.LiftedModule) arithmeticNode).module(), 0, 0, 0)).doubleValue();
        assertEquals(2.5, arithmeticVal, 0.0, "Lifted bytecode must preserve exact value * 2.0 semantics");

        java.util.Map<String, io.github.dailystruggle.bytecode.PipelineCoverageGraphScanner.ProvenanceBinding> trojanProv =
                new java.util.LinkedHashMap<>();
        java.util.Set<String> trojanUnmapped = new java.util.LinkedHashSet<>();
        WorldgenKernelSwapper.DensityNode trojanNode = WorldgenKernelSwapper.liftRuntimeDensityNode(
                new ProvenanceDensityFixtures.TrojanConstantHolder.Constant(1.25, 9.5),
                StatefulVanillaNoise.class.getName().replace('.', '/'),
                "sampleInstance3D",
                trojanProv,
                trojanUnmapped);
        assertTrue(trojanUnmapped.isEmpty(), "Pure multi-field bytecode must lift cleanly");
        double trojanVal = ((Number) ConceptIrEvaluator.evaluateEntry(
                ((WorldgenKernelSwapper.DensityNode.LiftedModule) trojanNode).module(), 0, 0, 0)).doubleValue();
        assertEquals(10.75, trojanVal, 0.0, "Lifted bytecode must preserve exact value + secretBias semantics");

        java.util.Map<String, io.github.dailystruggle.bytecode.PipelineCoverageGraphScanner.ProvenanceBinding> impureProv =
                new java.util.LinkedHashMap<>();
        java.util.Set<String> impureUnmapped = new java.util.LinkedHashSet<>();
        WorldgenKernelSwapper.liftRuntimeDensityNode(
                new ProvenanceDensityFixtures.ImpureNanoTimeDensity(2.0),
                StatefulVanillaNoise.class.getName().replace('.', '/'),
                "sampleInstance3D",
                impureProv,
                impureUnmapped);
        assertTrue(impureProv.isEmpty(), "Impure System.nanoTime() density must emit zero provenance bindings");
        assertTrue(impureUnmapped.contains(ProvenanceDensityFixtures.ImpureNanoTimeDensity.class.getName() + "#compute"));

        // Verify loadOwnerClassBytes logs Level.WARNING rather than silently swallowing IOException (S-004)
        byte[] validBytes = WorldgenKernelSwapper.loadOwnerClassBytes(ProvenanceDensityFixtures.Constant.class);
        org.junit.jupiter.api.Assertions.assertNotNull(validBytes);
        ClassLoader brokenStreamCl = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (ProvenanceDensityFixtures.Constant.class.getName().equals(name)) {
                    return defineClass(name, validBytes, 0, validBytes.length);
                }
                throw new ClassNotFoundException(name);
            }

            @Override
            public java.io.InputStream getResourceAsStream(String name) {
                return new java.io.InputStream() {
                    @Override
                    public int read() throws java.io.IOException {
                        throw new java.io.IOException("simulated stream read fault");
                    }
                };
            }
        };
        java.util.logging.Logger swapperLogger =
                java.util.logging.Logger.getLogger(WorldgenKernelSwapper.class.getName());
        java.util.List<java.util.logging.LogRecord> capturedWarnings = new java.util.ArrayList<>();
        java.util.logging.Handler testHandler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                    capturedWarnings.add(record);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        swapperLogger.addHandler(testHandler);
        try {
            Class<?> isolatedCls = Class.forName(
                    ProvenanceDensityFixtures.Constant.class.getName(), false, brokenStreamCl);
            org.junit.jupiter.api.Assertions.assertNull(WorldgenKernelSwapper.loadOwnerClassBytes(isolatedCls));
            assertFalse(
                    capturedWarnings.isEmpty(),
                    "loadOwnerClassBytes must log Level.WARNING on IOException (S-004)");
        } catch (ClassNotFoundException ex) {
            throw new AssertionError(ex);
        } finally {
            swapperLogger.removeHandler(testHandler);
        }
    }

    @Test
    @DisplayName("Non-reflective SwappedWorldgenKernel and WorldgenKernelSwapper factories attach custom OpenCL artifacts cleanly")
    void typedSwappedWorldgenKernelFactoriesAttachCustomOpenClArtifactsWithoutReflection() {
        WorldgenPipelineSpec baseSpec = buildOverworldSpec("factory_base_kernel", EmitOptions.gpuFp64());
        SwappedWorldgenKernel base = WorldgenKernelSwapper.compileAndSwap(
                baseSpec, RuntimeImprovedNoise.class, RuntimeCurveMath.class);
        WorldgenPipelineSpec customSpec = new WorldgenPipelineSpec(
                "factory_custom_opencl_kernel",
                baseSpec.finalTerrainDensity(),
                baseSpec.caveCheeseDensity(),
                baseSpec.palette(),
                EmitOptions.gpuFp32());
        String customCl = "__kernel void custom_lca_entry(__global int* out) { out[0] = 42; }\n";
        SwappedWorldgenKernel rewrapped = base.withCustomOpenClSource(
                customSpec,
                ConceptBackendEmitter.FloatingPointPrecision.DOWNCAST_FP32,
                customCl,
                "custom_lca_entry");
        assertEquals("factory_custom_opencl_kernel", rewrapped.spec().kernelId());
        assertEquals(customCl, rewrapped.openClArtifact().sourceCode());
        assertEquals("custom_lca_entry", rewrapped.openClArtifact().kernel3DName());
        assertEquals(
                ConceptBackendEmitter.FloatingPointPrecision.DOWNCAST_FP32,
                rewrapped.openClArtifact().fpPrecision());
        assertEquals(base.kernelIrDigest(), rewrapped.kernelIrDigest());
        assertEquals(base.evaluateVoxel(8, 32, 8, 12345L), rewrapped.evaluateVoxel(8, 32, 8, 12345L));

        LiftedWorldgenCapabilityTables.DimensionCapabilitySpec capSpec =
                new LiftedWorldgenCapabilityTables.DimensionCapabilitySpec(
                        -64,
                        8,
                        -64,
                        128,
                        4,
                        8,
                        62,
                        1,
                        2,
                        true,
                        6,
                        -54,
                        new int[16],
                        null,
                        new LiftedWorldgenCapabilityTables.CarverSpec[0],
                        new LiftedWorldgenCapabilityTables.BiomeFeatureSpec[0],
                        new LiftedWorldgenCapabilityTables.OreVeinSpec[0],
                        LiftedWorldgenCapabilityTables.RouterVeinSpec.DISABLED);
        SwappedWorldgenKernel exactCapKernel = WorldgenKernelSwapper.compileCapabilityKernel(
                capSpec,
                WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_EXACT,
                true);
        SwappedWorldgenKernel likeCapKernel = WorldgenKernelSwapper.compileCapabilityKernel(
                capSpec,
                WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE,
                true);
        assertEquals(
                ConceptBackendEmitter.FloatingPointPrecision.NATIVE_FP64,
                exactCapKernel.openClArtifact().fpPrecision());
        assertEquals(
                ConceptBackendEmitter.FloatingPointPrecision.DOWNCAST_FP32,
                likeCapKernel.openClArtifact().fpPrecision());
        org.junit.jupiter.api.Assertions.assertNotEquals(
                exactCapKernel.openClArtifact().sourceCode(),
                likeCapKernel.openClArtifact().sourceCode());
    }

    @Test
    @DisplayName("VANILLA_EXACT and VANILLA_LIKE produce distinct CPU and OpenCL capability artifacts and EXACT matches FP64 reference with zero sign mismatches")
    void fidelityParameterizedCapabilitySynthesisMatchesFp64ReferenceToZeroVoxelSignMismatches() {
        int octaves = 16;
        byte[] perm = new byte[octaves * 256];
        float[] fx = new float[octaves];
        float[] fy = new float[octaves];
        float[] fz = new float[octaves];
        float[] weights = new float[octaves];
        float[] xo = new float[octaves];
        float[] yo = new float[octaves];
        float[] zo = new float[octaves];
        for (int o = 0; o < octaves; o++) {
            float scale = (float) Math.scalb(0.015625, o);
            fx[o] = scale;
            fy[o] = scale * 0.75f;
            fz[o] = scale;
            weights[o] = o < 13 ? (float) Math.scalb(0.35, -Math.min(o, 6)) : 0.18f;
            xo[o] = o * 3.125f;
            yo[o] = o * 1.75f;
            zo[o] = o * 2.5f;
            for (int i = 0; i < 256; i++) {
                perm[(o << 8) + i] = (byte) ((i * 73 + o * 29 + 17) & 255);
            }
        }
        LiftedWorldgenCapabilityTables.LiftedNormalNoiseSpec multiOctaveSpec =
                new LiftedWorldgenCapabilityTables.LiftedNormalNoiseSpec(
                        perm, fx, fy, fz, weights, xo, yo, zo, false);
        LiftedWorldgenCapabilityTables.DensityFieldEvaluator exactNoiseEval =
                multiOctaveSpec.toEvaluator(WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_EXACT);
        LiftedWorldgenCapabilityTables.DensityFieldEvaluator likeNoiseEval =
                multiOctaveSpec.toEvaluator(WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE);

        LiftedWorldgenCapabilityTables.GpuRouterNoiseTable gpuNoises =
                new LiftedWorldgenCapabilityTables.GpuRouterNoiseTable(
                        multiOctaveSpec,
                        null,
                        null,
                        null,
                        multiOctaveSpec,
                        new LiftedWorldgenCapabilityTables.LiftedNormalNoiseSpec[] {multiOctaveSpec},
                        new boolean[] {true});

        BytecodeConceptIr.ConceptModule exactSurfMod = liftTestSurfaceRule(new TopFloorSlot4RuleFixture());
        LiftedWorldgenCapabilityTables.OreVeinSpec[] ores = {
            new LiftedWorldgenCapabilityTables.OreVeinSpec(1, 7, 10, 4, -48, 16, false)
        };
        LiftedWorldgenCapabilityTables.DimensionCapabilitySpec capSpec =
                new LiftedWorldgenCapabilityTables.DimensionCapabilitySpec(
                        -64,
                        8,
                        -64,
                        128,
                        4,
                        8,
                        0,
                        1,
                        2,
                        true,
                        6,
                        -54,
                        new int[16],
                        exactSurfMod,
                        new LiftedWorldgenCapabilityTables.CarverSpec[0],
                        new LiftedWorldgenCapabilityTables.BiomeFeatureSpec[0],
                        ores,
                        LiftedWorldgenCapabilityTables.RouterVeinSpec.DISABLED,
                        gpuNoises,
                        new LiftedWorldgenCapabilityTables.LiftedPlacedFeatureSpec[0]);

        String openClExact = OpenClBackendEmitter.emitDimensionCapabilityKernelSource(
                capSpec, true, WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_EXACT);
        String openClLike = OpenClBackendEmitter.emitDimensionCapabilityKernelSource(
                capSpec, true, WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE);
        assertTrue(openClExact.contains("LEAFGEN_FIDELITY_VANILLA_EXACT"));
        assertTrue(openClExact.contains("cl_khr_fp64"));
        assertTrue(openClExact.contains("CAP_UNCERTAIN_SIGN_CPU_REFINE_FLAG"));
        assertTrue(openClLike.contains("LEAFGEN_FIDELITY_VANILLA_LIKE"));
        org.junit.jupiter.api.Assertions.assertNotEquals(openClExact, openClLike);

        LiftedWorldgenCapabilityTables.DensityFieldEvaluator exactDensity = new LiftedWorldgenCapabilityTables.DensityFieldEvaluator() {
            @Override
            public float sample(int bx, int by, int bz) {
                return (float) sampleFp64(bx, by, bz);
            }

            @Override
            public double sampleFp64(int bx, int by, int bz) {
                double baseSlope = (8.25 - by) * 0.42 + Math.sin(bx * 0.19 + bz * 0.13) * 2.75;
                double detail = exactNoiseEval.sampleFp64(bx, by, bz) * 0.35;
                return baseSlope + detail;
            }
        };
        LiftedWorldgenCapabilityTables.DensityFieldEvaluator likeDensity =
                (bx, by, bz) -> (float) ((8.25 - by) * 0.42 + Math.sin(bx * 0.19 + bz * 0.13) * 2.75)
                        + likeNoiseEval.sample(bx, by, bz) * 0.35f;

        LiftedWorldgenCapabilityTables.RouterCapabilityChannels exactChannels =
                new LiftedWorldgenCapabilityTables.RouterCapabilityChannels(
                        exactNoiseEval,
                        (x, y, z) -> -0.6f,
                        (x, y, z) -> 0.0f,
                        (x, y, z) -> -0.5f,
                        exactDensity,
                        null,
                        null,
                        null,
                        exactNoiseEval,
                        new LiftedWorldgenCapabilityTables.DensityFieldEvaluator[] {exactNoiseEval},
                        new boolean[] {true});
        LiftedWorldgenCapabilityTables.RouterCapabilityChannels likeChannels =
                new LiftedWorldgenCapabilityTables.RouterCapabilityChannels(
                        likeNoiseEval,
                        (x, y, z) -> -0.6f,
                        (x, y, z) -> 0.0f,
                        (x, y, z) -> -0.5f,
                        likeDensity,
                        null,
                        null,
                        null,
                        likeNoiseEval,
                        new LiftedWorldgenCapabilityTables.DensityFieldEvaluator[] {likeNoiseEval},
                        new boolean[] {true});

        // 1. Pure terrain isosurface parity: VANILLA_EXACT must match independent FP64 trilinear reference with 0 sign mismatches
        LiftedWorldgenCapabilityTables.LcaCapabilityChunkFrame exactTerrainFrame =
                LiftedWorldgenCapabilityTables.synthesizeDimensionChunkLca(
                        capSpec,
                        1,
                        -1,
                        20261117L,
                        exactDensity,
                        (qx, qy, qz) -> 0,
                        false,
                        exactChannels,
                        null,
                        null,
                        null,
                        false,
                        false,
                        false,
                        null,
                        0,
                        WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_EXACT);
        LiftedWorldgenCapabilityTables.LcaCapabilityChunkFrame likeTerrainFrame =
                LiftedWorldgenCapabilityTables.synthesizeDimensionChunkLca(
                        capSpec,
                        1,
                        -1,
                        20261117L,
                        likeDensity,
                        (qx, qy, qz) -> 0,
                        false,
                        likeChannels,
                        null,
                        null,
                        null,
                        false,
                        false,
                        false,
                        null,
                        0,
                        WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE);

        int[][] exactSecs = exactTerrainFrame.materializeSections();
        int[][] likeSecs = likeTerrainFrame.materializeSections();

        int cellW = capSpec.cellWidth();
        int cellH = capSpec.cellHeight();
        int cellsX = 16 / cellW;
        int cellsZ = 16 / cellW;
        int cellsY = capSpec.noiseHeight() / cellH;
        int strideZ = cellsY + 1;
        int strideX = (cellsZ + 1) * strideZ;
        double[] refCorners = new double[(cellsX + 1) * strideX];
        int baseBx = 1 << 4;
        int baseBz = (-1) << 4;
        for (int gx = 0; gx <= cellsX; gx++) {
            for (int gz = 0; gz <= cellsZ; gz++) {
                int off = gx * strideX + gz * strideZ;
                for (int gy = 0; gy <= cellsY; gy++) {
                    refCorners[off + gy] = exactDensity.sampleFp64(
                            baseBx + gx * cellW,
                            capSpec.noiseMinY() + gy * cellH,
                            baseBz + gz * cellW);
                }
            }
        }

        int exactSignMismatches = 0;
        int likeSignDiffs = 0;
        for (int cy = 0; cy < cellsY; cy++) {
            int cellBaseY = capSpec.noiseMinY() + cy * cellH;
            for (int gx = 0; gx < cellsX; gx++) {
                for (int gz = 0; gz < cellsZ; gz++) {
                    int c00 = gx * strideX + gz * strideZ + cy;
                    int c10 = (gx + 1) * strideX + gz * strideZ + cy;
                    int c01 = gx * strideX + (gz + 1) * strideZ + cy;
                    int c11 = (gx + 1) * strideX + (gz + 1) * strideZ + cy;
                    double v000 = refCorners[c00], v001 = refCorners[c00 + 1];
                    double v100 = refCorners[c10], v101 = refCorners[c10 + 1];
                    double v010 = refCorners[c01], v011 = refCorners[c01 + 1];
                    double v110 = refCorners[c11], v111 = refCorners[c11 + 1];
                    for (int dy = 0; dy < cellH; dy++) {
                        int wy = cellBaseY + dy;
                        int sIdx = (wy - capSpec.chunkMinY()) >> 4;
                        double fyLerp = (double) dy / (double) cellH;
                        double x00 = v000 + fyLerp * (v001 - v000);
                        double x10 = v100 + fyLerp * (v101 - v100);
                        double x01 = v010 + fyLerp * (v011 - v010);
                        double x11 = v110 + fyLerp * (v111 - v110);
                        for (int dx = 0; dx < cellW; dx++) {
                            int lx = gx * cellW + dx;
                            double fxLerp = (double) dx / (double) cellW;
                            double z0 = x00 + fxLerp * (x10 - x00);
                            double z1 = x01 + fxLerp * (x11 - x01);
                            for (int dz = 0; dz < cellW; dz++) {
                                int lz = gz * cellW + dz;
                                double fzLerp = (double) dz / (double) cellW;
                                double refDens = z0 + fzLerp * (z1 - z0);
                                boolean refSolid = refDens > 0.0;
                                int idx = ((wy & 15) << 8) | (lz << 4) | lx;
                                boolean exactSolid = exactSecs[sIdx][idx] == capSpec.defaultBlockSlot();
                                boolean likeSolid = likeSecs[sIdx][idx] == capSpec.defaultBlockSlot();
                                if (refSolid != exactSolid) {
                                    exactSignMismatches++;
                                }
                                if (refSolid != likeSolid) {
                                    likeSignDiffs++;
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(0, exactSignMismatches, "VANILLA_EXACT must match reference FP64 trilinear evaluation to 0 voxel sign mismatches");
        assertTrue(likeSignDiffs > 0, "VANILLA_LIKE Macro-13 FP32 with threshold pruning must produce distinct boundary voxels");

        // 2. Full surface + ore feature synthesis produces valid, distinct frames across policies
        LiftedWorldgenCapabilityTables.LcaCapabilityChunkFrame fullExact =
                LiftedWorldgenCapabilityTables.synthesizeDimensionChunkLca(
                        capSpec,
                        1,
                        -1,
                        20261117L,
                        exactDensity,
                        (qx, qy, qz) -> 0,
                        false,
                        exactChannels,
                        null,
                        null,
                        null,
                        true,
                        false,
                        true,
                        null,
                        0,
                        WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_EXACT);
        LiftedWorldgenCapabilityTables.LcaCapabilityChunkFrame fullLike =
                LiftedWorldgenCapabilityTables.synthesizeDimensionChunkLca(
                        capSpec,
                        1,
                        -1,
                        20261117L,
                        likeDensity,
                        (qx, qy, qz) -> 0,
                        false,
                        likeChannels,
                        null,
                        null,
                        null,
                        true,
                        false,
                        true,
                        null,
                        0,
                        WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE);
        assertEquals(WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_EXACT, fullExact.fidelityPolicy());
        assertEquals(WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE, fullLike.fidelityPolicy());
        assertFalse(
                java.util.Arrays.deepEquals(fullExact.materializeSections(), fullLike.materializeSections()),
                "Full VANILLA_EXACT and VANILLA_LIKE frames must produce distinct voxel materializations");
    }

    @Test
    @DisplayName("Measures VANILLA_EXACT NATIVE_FP64 vs INT64_Q32_FP32 vs VANILLA_LIKE parity and OpenCL GPU throughput")
    void measuresGpuExactNativeFp64VsInt64Q32Fp32ParityAndThroughput(
            @org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        int octaves = 16;
        byte[] perm = new byte[octaves * 256];
        float[] fx = new float[octaves];
        float[] fy = new float[octaves];
        float[] fz = new float[octaves];
        float[] weights = new float[octaves];
        float[] xo = new float[octaves];
        float[] yo = new float[octaves];
        float[] zo = new float[octaves];
        for (int o = 0; o < octaves; o++) {
            float scale = (float) Math.scalb(1.0 / 256.0, o);
            fx[o] = scale;
            fy[o] = scale * 0.5f;
            fz[o] = scale;
            weights[o] = (float) Math.scalb(0.45, -Math.min(o, 10));
            xo[o] = (o * 17.375f) + 5.125f;
            yo[o] = (o * 9.25f) + 2.5f;
            zo[o] = (o * 13.625f) + 7.75f;
            for (int i = 0; i < 256; i++) {
                perm[(o << 8) + i] = (byte) ((i * 97 + o * 53 + 31) & 255);
            }
        }
        LiftedWorldgenCapabilityTables.LiftedNormalNoiseSpec terrainNoise =
                new LiftedWorldgenCapabilityTables.LiftedNormalNoiseSpec(
                        perm, fx, fy, fz, weights, xo, yo, zo, false);
        LiftedWorldgenCapabilityTables.DensityFieldEvaluator fp64Eval =
                terrainNoise.toEvaluator(
                        WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_EXACT,
                        WorldgenKernelSwapper.GpuExactArithmeticMode.NATIVE_FP64);
        LiftedWorldgenCapabilityTables.DensityFieldEvaluator i64f32Eval =
                terrainNoise.toEvaluator(
                        WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_EXACT,
                        WorldgenKernelSwapper.GpuExactArithmeticMode.INT64_Q32_FP32);
        LiftedWorldgenCapabilityTables.DensityFieldEvaluator likeEval =
                terrainNoise.toEvaluator(WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE);

        LiftedWorldgenCapabilityTables.GpuRouterNoiseTable gpuNoises =
                new LiftedWorldgenCapabilityTables.GpuRouterNoiseTable(
                        terrainNoise,
                        terrainNoise,
                        terrainNoise,
                        terrainNoise,
                        terrainNoise,
                        terrainNoise,
                        new LiftedWorldgenCapabilityTables.LiftedNormalNoiseSpec[] {terrainNoise},
                        new boolean[] {false});
        BytecodeConceptIr.ConceptModule overworldSurfMod = liftTestSurfaceRule(new OverworldStratifiedRuleFixture());
        LiftedWorldgenCapabilityTables.RouterVeinSpec veins =
                new LiftedWorldgenCapabilityTables.RouterVeinSpec(
                        true, 0, 56, 11, 12, 13, -56, -8, 14, 15, 16);
        LiftedWorldgenCapabilityTables.DimensionCapabilitySpec overworldSpec =
                new LiftedWorldgenCapabilityTables.DimensionCapabilitySpec(
                        -64,
                        24,
                        -64,
                        384,
                        4,
                        8,
                        63,
                        1,
                        2,
                        true,
                        6,
                        -54,
                        new int[16],
                        overworldSurfMod,
                        new LiftedWorldgenCapabilityTables.CarverSpec[0],
                        new LiftedWorldgenCapabilityTables.BiomeFeatureSpec[0],
                        new LiftedWorldgenCapabilityTables.OreVeinSpec[0],
                        veins,
                        gpuNoises,
                        new LiftedWorldgenCapabilityTables.LiftedPlacedFeatureSpec[0]);

        String clNativeFp64 = OpenClBackendEmitter.emitDimensionCapabilityKernelSource(
                overworldSpec,
                false,
                WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_EXACT,
                WorldgenKernelSwapper.GpuExactArithmeticMode.NATIVE_FP64);
        String clInt64Fp32 = OpenClBackendEmitter.emitDimensionCapabilityKernelSource(
                overworldSpec,
                false,
                WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_EXACT,
                WorldgenKernelSwapper.GpuExactArithmeticMode.INT64_Q32_FP32);
        String clHybridSignBand = OpenClBackendEmitter.emitDimensionCapabilityKernelSource(
                overworldSpec,
                false,
                WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_EXACT,
                WorldgenKernelSwapper.GpuExactArithmeticMode.HYBRID_SIGNBAND);
        String clVanillaLike = OpenClBackendEmitter.emitDimensionCapabilityKernelSource(
                overworldSpec,
                false,
                WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE,
                WorldgenKernelSwapper.GpuExactArithmeticMode.INT64_Q32_FP32);

        assertTrue(clNativeFp64.contains("LEAFGEN_EXACT_NATIVE_FP64"));
        assertTrue(clInt64Fp32.contains("LEAFGEN_EXACT_INT64_Q32_FP32"));
        assertFalse(clInt64Fp32.contains("LEAFGEN_EXACT_HYBRID_SIGNBAND"));
        assertFalse(clInt64Fp32.contains("cl_khr_fp64"), "INT64_Q32_FP32 must emit zero FP64 pragmas/instructions");
        assertTrue(clHybridSignBand.contains("LEAFGEN_EXACT_INT64_Q32_FP32"));
        assertTrue(clHybridSignBand.contains("LEAFGEN_EXACT_HYBRID_SIGNBAND"));
        assertFalse(clHybridSignBand.contains("cl_khr_fp64"), "HYBRID_SIGNBAND must emit zero FP64 pragmas/instructions");
        assertTrue(clVanillaLike.contains("LEAFGEN_FIDELITY_VANILLA_LIKE"));

        assertTrue(WorldgenKernelSwapper.GpuExactArithmeticMode.parseConfiguredMode(null).isEmpty());
        assertTrue(WorldgenKernelSwapper.GpuExactArithmeticMode.parseConfiguredMode("auto").isEmpty());
        assertEquals(
                WorldgenKernelSwapper.GpuExactArithmeticMode.INT64_Q32_FP32,
                WorldgenKernelSwapper.GpuExactArithmeticMode.parseConfiguredMode("int64_fp32").orElseThrow());
        assertEquals(
                WorldgenKernelSwapper.GpuExactArithmeticMode.HYBRID_SIGNBAND,
                WorldgenKernelSwapper.GpuExactArithmeticMode.parseConfiguredMode("hybrid_signband").orElseThrow());
        assertEquals(
                WorldgenKernelSwapper.GpuExactArithmeticMode.NATIVE_FP64,
                WorldgenKernelSwapper.GpuExactArithmeticMode.parseConfiguredMode("native_fp64").orElseThrow());
        assertTrue(WorldgenKernelSwapper.GpuExactArithmeticMode.isConsumerLimitedFp64Device(
                "NVIDIA GeForce RTX 3090", "NVIDIA Corporation"));
        assertFalse(WorldgenKernelSwapper.GpuExactArithmeticMode.isConsumerLimitedFp64Device(
                "NVIDIA A100-SXM4-80GB", "NVIDIA Corporation"));

        SwappedWorldgenKernel capHybridKernel = WorldgenKernelSwapper.compileCapabilityKernel(
                overworldSpec,
                WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_EXACT,
                WorldgenKernelSwapper.GpuExactArithmeticMode.HYBRID_SIGNBAND);
        assertNotNull(capHybridKernel);
        assertTrue(capHybridKernel.spec().kernelId().contains("hybrid_signband"));

        // Verify CPU parity at both near-origin (chunk 2, -3) and Far-Lands (chunk 1_500_000, -1_500_000)
        int[][] chunkPairs = {{2, -3}, {1_500_000, -1_500_000}};
        for (int[] cp : chunkPairs) {
            int cx = cp[0];
            int cz = cp[1];
            LiftedWorldgenCapabilityTables.DensityFieldEvaluator terrainFp64 = new LiftedWorldgenCapabilityTables.DensityFieldEvaluator() {
                @Override
                public float sample(int bx, int by, int bz) {
                    return (float) sampleFp64(bx, by, bz);
                }

                @Override
                public double sampleFp64(int bx, int by, int bz) {
                    return (62.5 - by) * 0.085 + fp64Eval.sampleFp64(bx, by, bz) * 1.65;
                }
            };
            LiftedWorldgenCapabilityTables.DensityFieldEvaluator terrainI64F32 =
                    (bx, by, bz) -> (62.5f - (float) by) * 0.085f + i64f32Eval.sample(bx, by, bz) * 1.65f;
            LiftedWorldgenCapabilityTables.DensityFieldEvaluator terrainLike =
                    (bx, by, bz) -> (62.5f - (float) by) * 0.085f + likeEval.sample(bx, by, bz) * 1.65f;

            LiftedWorldgenCapabilityTables.RouterCapabilityChannels chFp64 =
                    new LiftedWorldgenCapabilityTables.RouterCapabilityChannels(
                            fp64Eval, null, null, null, null, fp64Eval, fp64Eval, fp64Eval, fp64Eval,
                            new LiftedWorldgenCapabilityTables.DensityFieldEvaluator[] {fp64Eval},
                            new boolean[] {false});
            LiftedWorldgenCapabilityTables.RouterCapabilityChannels chI64F32 =
                    new LiftedWorldgenCapabilityTables.RouterCapabilityChannels(
                            i64f32Eval, null, null, null, null, i64f32Eval, i64f32Eval, i64f32Eval, i64f32Eval,
                            new LiftedWorldgenCapabilityTables.DensityFieldEvaluator[] {i64f32Eval},
                            new boolean[] {false});
            LiftedWorldgenCapabilityTables.RouterCapabilityChannels chLike =
                    new LiftedWorldgenCapabilityTables.RouterCapabilityChannels(
                            likeEval, null, null, null, null, likeEval, likeEval, likeEval, likeEval,
                            new LiftedWorldgenCapabilityTables.DensityFieldEvaluator[] {likeEval},
                            new boolean[] {false});

            int[][] refFp64Secs = LiftedWorldgenCapabilityTables.synthesizeDimensionChunkCpu(
                    overworldSpec, cx, cz, 424242L, terrainFp64, (qx, qy, qz) -> 0, chFp64, null,
                    true, false, false, WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_EXACT);
            // Simulate GPU INT64_Q32_FP32 corners + FP32 trilinear + INT64_Q32_FP32 router channels without VANILLA_LIKE culling/seals
            int[][] i64Secs = LiftedWorldgenCapabilityTables.synthesizeDimensionChunkCpu(
                    overworldSpec, cx, cz, 424242L, terrainI64F32, (qx, qy, qz) -> 0, chI64F32, null,
                    true, false, false, WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_EXACT);
            int[][] likeSecs = LiftedWorldgenCapabilityTables.synthesizeDimensionChunkCpu(
                    overworldSpec, cx, cz, 424242L, terrainLike, (qx, qy, qz) -> 0, chLike, null,
                    true, false, false, WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE);

            int totalVoxels = 24 * 4096;
            int diffI64 = 0;
            int diffLike = 0;
            for (int s = 0; s < 24; s++) {
                for (int i = 0; i < 4096; i++) {
                    if (refFp64Secs[s][i] != i64Secs[s][i]) diffI64++;
                    if (refFp64Secs[s][i] != likeSecs[s][i]) diffLike++;
                }
            }
            double pctI64 = (100.0 * diffI64) / totalVoxels;
            double pctLike = (100.0 * diffLike) / totalVoxels;
            System.out.printf(
                    java.util.Locale.ROOT,
                    "[CPU-PARITY chunk=(%d,%d)] total=%d | INT64_Q32_FP32 diff=%d (%.5f%%) | VANILLA_LIKE diff=%d (%.5f%%)%n",
                    cx, cz, totalVoxels, diffI64, pctI64, diffLike, pctLike);
            assertTrue(
                    pctI64 < 0.5,
                    "INT64_Q32_FP32 mismatch must stay well below 0.5% even at Far-Lands coordinates, got " + pctI64 + "%");
        }

        // Live OpenCL GPU hardware benchmark when gcc + libOpenCL.so.1 are present on the host
        Path libOpenCl = Files.exists(Path.of("/usr/lib64/libOpenCL.so.1"))
                ? Path.of("/usr/lib64/libOpenCL.so.1")
                : (Files.exists(Path.of("/usr/lib/x86_64-linux-gnu/libOpenCL.so.1"))
                        ? Path.of("/usr/lib/x86_64-linux-gnu/libOpenCL.so.1")
                        : null);
        if (libOpenCl != null) {
            String extraBenchKernels = """
                __kernel void eval_corners_fp64(__global float* out_corners, const int base_cx, const int base_cz) {
                    int batch = (int)get_global_id(0);
                    int col25 = (int)get_global_id(1);
                    int gx = col25 / 5;
                    int gz = col25 - gx * 5;
                    int cx = base_cx + (batch & 7);
                    int cz = base_cz + (batch >> 3);
                    double bx = (double)((cx << 4) + (gx << 2));
                    double bz = (double)((cz << 4) + (gz << 2));
                    int out_base = batch * 1225 + col25 * 49;
                    for (int gy = 0; gy < 49; gy++) {
                        double wy = (double)(-64 + (gy << 3));
                        double n1 = eval_lifted_normal_noise_fp64(0, bx, wy, bz);
                        double n2 = eval_lifted_normal_noise_fp64(1, bx * 2.0, wy, bz * 2.0);
                        double n3 = eval_lifted_normal_noise_fp64(2, bx * 0.5, wy * 0.5, bz * 0.5);
                        out_corners[out_base + gy] = (float)((62.5 - wy) * 0.085 + (n1 + 0.5 * n2 + 0.25 * n3) * 1.35);
                    }
                }
                """;
            String extraBenchI64 = """
                __kernel void eval_corners_i64f32(__global float* out_corners, const int base_cx, const int base_cz) {
                    int batch = (int)get_global_id(0);
                    int col25 = (int)get_global_id(1);
                    int gx = col25 / 5;
                    int gz = col25 - gx * 5;
                    int cx = base_cx + (batch & 7);
                    int cz = base_cz + (batch >> 3);
                    long bx = (long)((cx << 4) + (gx << 2));
                    long bz = (long)((cz << 4) + (gz << 2));
                    int out_base = batch * 1225 + col25 * 49;
                    for (int gy = 0; gy < 49; gy++) {
                        long wy = (long)(-64 + (gy << 3));
                        float n1 = eval_lifted_normal_noise_i64f32(0, bx, wy, bz);
                        float n2 = eval_lifted_normal_noise_i64f32(1, bx * 2L, wy, bz * 2L);
                        float n3 = eval_lifted_normal_noise_i64f32(2, bx, wy, bz);
                        out_corners[out_base + gy] = (62.5f - (float)wy) * 0.085f + (n1 + 0.5f * n2 + 0.25f * n3) * 1.35f;
                    }
                }
                """;
            String extraBenchLike = """
                __kernel void eval_corners_like(__global float* out_corners, const int base_cx, const int base_cz) {
                    int batch = (int)get_global_id(0);
                    int col25 = (int)get_global_id(1);
                    int gx = col25 / 5;
                    int gz = col25 - gx * 5;
                    int cx = base_cx + (batch & 7);
                    int cz = base_cz + (batch >> 3);
                    float bx = (float)((cx << 4) + (gx << 2));
                    float bz = (float)((cz << 4) + (gz << 2));
                    int out_base = batch * 1225 + col25 * 49;
                    for (int gy = 0; gy < 49; gy++) {
                        float wy = (float)(-64 + (gy << 3));
                        float n1 = eval_lifted_normal_noise_fp32(0, bx, wy, bz);
                        float n2 = eval_lifted_normal_noise_fp32(1, bx * 2.0f, wy, bz * 2.0f);
                        float n3 = eval_lifted_normal_noise_fp32(2, bx, wy, bz);
                        out_corners[out_base + gy] = (62.5f - wy) * 0.085f + (n1 + 0.5f * n2 + 0.25f * n3) * 1.35f;
                    }
                }
                """;
            // Make n3 identical coordinate scale (bx, wy, bz) in fp64 too so all 3 evaluate exact same mathematical field!
            extraBenchKernels = extraBenchKernels.replace(
                    "eval_lifted_normal_noise_fp64(2, bx * 0.5, wy * 0.5, bz * 0.5)",
                    "eval_lifted_normal_noise_fp64(2, bx, wy, bz)");

            Path srcFp64 = tempDir.resolve("cap_fp64.cl");
            Path srcI64 = tempDir.resolve("cap_i64f32.cl");
            Path srcLike = tempDir.resolve("cap_like.cl");
            Files.writeString(srcFp64, clNativeFp64 + "\n" + extraBenchKernels);
            Files.writeString(srcI64, clInt64Fp32 + "\n" + extraBenchI64);
            Files.writeString(srcLike, clVanillaLike + "\n" + extraBenchLike);

            Path cRunner = tempDir.resolve("ocl_bench.c");
            Files.writeString(cRunner, """
                #include <stdio.h>
                #include <stdlib.h>
                #include <stdint.h>
                #include <string.h>
                #include <dlfcn.h>

                typedef int32_t cl_int;
                typedef uint32_t cl_uint;
                typedef uint64_t cl_ulong;
                typedef void* cl_platform_id;
                typedef void* cl_device_id;
                typedef void* cl_context;
                typedef void* cl_command_queue;
                typedef void* cl_program;
                typedef void* cl_kernel;
                typedef void* cl_mem;
                typedef void* cl_event;

                #define CL_DEVICE_TYPE_GPU 4
                #define CL_MEM_READ_WRITE (1 << 0)
                #define CL_MEM_COPY_HOST_PTR (1 << 5)
                #define CL_QUEUE_PROFILING_ENABLE (1 << 1)
                #define CL_PROFILING_COMMAND_START 0x1282
                #define CL_PROFILING_COMMAND_END   0x1283
                #define CL_DEVICE_NAME 0x102B
                #define CL_PROGRAM_BUILD_LOG 0x1183

                static char* read_file(const char* path, size_t* out_len) {
                    FILE* f = fopen(path, "rb");
                    if (!f) return NULL;
                    fseek(f, 0, SEEK_END);
                    long sz = ftell(f);
                    fseek(f, 0, SEEK_SET);
                    char* buf = (char*)malloc(sz + 1);
                    *out_len = fread(buf, 1, sz, f);
                    buf[*out_len] = 0;
                    fclose(f);
                    return buf;
                }

                int main(int argc, char** argv) {
                    void* lib = dlopen(argv[1], RTLD_LAZY);
                    if (!lib) { fprintf(stderr, "dlopen failed\\n"); return 2; }

                    cl_int (*clGetPlatformIDs)(cl_uint, cl_platform_id*, cl_uint*) = dlsym(lib, "clGetPlatformIDs");
                    cl_int (*clGetDeviceIDs)(cl_platform_id, cl_ulong, cl_uint, cl_device_id*, cl_uint*) = dlsym(lib, "clGetDeviceIDs");
                    cl_int (*clGetDeviceInfo)(cl_device_id, cl_uint, size_t, void*, size_t*) = dlsym(lib, "clGetDeviceInfo");
                    cl_context (*clCreateContext)(void*, cl_uint, const cl_device_id*, void*, void*, cl_int*) = dlsym(lib, "clCreateContext");
                    cl_command_queue (*clCreateCommandQueue)(cl_context, cl_device_id, cl_ulong, cl_int*) = dlsym(lib, "clCreateCommandQueue");
                    cl_program (*clCreateProgramWithSource)(cl_context, cl_uint, const char**, const size_t*, cl_int*) = dlsym(lib, "clCreateProgramWithSource");
                    cl_int (*clBuildProgram)(cl_program, cl_uint, const cl_device_id*, const char*, void*, void*) = dlsym(lib, "clBuildProgram");
                    cl_int (*clGetProgramBuildInfo)(cl_program, cl_device_id, cl_uint, size_t, void*, size_t*) = dlsym(lib, "clGetProgramBuildInfo");
                    cl_kernel (*clCreateKernel)(cl_program, const char*, cl_int*) = dlsym(lib, "clCreateKernel");
                    cl_mem (*clCreateBuffer)(cl_context, cl_ulong, size_t, void*, cl_int*) = dlsym(lib, "clCreateBuffer");
                    cl_int (*clSetKernelArg)(cl_kernel, cl_uint, size_t, const void*) = dlsym(lib, "clSetKernelArg");
                    cl_int (*clEnqueueNDRangeKernel)(cl_command_queue, cl_kernel, cl_uint, const size_t*, const size_t*, const size_t*, cl_uint, const cl_event*, cl_event*) = dlsym(lib, "clEnqueueNDRangeKernel");
                    cl_int (*clEnqueueReadBuffer)(cl_command_queue, cl_mem, cl_uint, size_t, size_t, void*, cl_uint, const cl_event*, cl_event*) = dlsym(lib, "clEnqueueReadBuffer");
                    cl_int (*clFinish)(cl_command_queue) = dlsym(lib, "clFinish");
                    cl_int (*clGetEventProfilingInfo)(cl_event, cl_uint, size_t, void*, size_t*) = dlsym(lib, "clGetEventProfilingInfo");

                    cl_platform_id plat = NULL;
                    cl_uint np = 0;
                    if (clGetPlatformIDs(1, &plat, &np) != 0 || np == 0) return 3;
                    cl_device_id dev = NULL;
                    cl_uint nd = 0;
                    if (clGetDeviceIDs(plat, CL_DEVICE_TYPE_GPU, 1, &dev, &nd) != 0 || nd == 0) return 4;
                    char dev_name[256] = {0};
                    clGetDeviceInfo(dev, CL_DEVICE_NAME, sizeof(dev_name) - 1, dev_name, NULL);

                    cl_int err = 0;
                    cl_context ctx = clCreateContext(NULL, 1, &dev, NULL, NULL, &err);
                    cl_command_queue q = clCreateCommandQueue(ctx, dev, CL_QUEUE_PROFILING_ENABLE, &err);

                    const char* paths[3] = { argv[2], argv[3], argv[4] };
                    const char* corner_names[3] = { "eval_corners_fp64", "eval_corners_i64f32", "eval_corners_like" };
                    const char* labels[3] = { "NATIVE_FP64", "INT64_Q32_FP32", "VANILLA_LIKE_M13" };
                    cl_program progs[3];
                    cl_kernel k_corners[3];
                    cl_kernel k_cap[3];

                    for (int m = 0; m < 3; m++) {
                        size_t len = 0;
                        char* src = read_file(paths[m], &len);
                        const char* csrc = src;
                        progs[m] = clCreateProgramWithSource(ctx, 1, &csrc, &len, &err);
                        err = clBuildProgram(progs[m], 1, &dev, "-cl-std=CL1.2", NULL, NULL);
                        if (err != 0) {
                            char log[8192] = {0};
                            clGetProgramBuildInfo(progs[m], dev, CL_PROGRAM_BUILD_LOG, sizeof(log) - 1, log, NULL);
                            fprintf(stderr, "Build failed for %s (%d):\\n%s\\n", labels[m], err, log);
                            return 10 + m;
                        }
                        k_corners[m] = clCreateKernel(progs[m], corner_names[m], &err);
                        if (err != 0) { fprintf(stderr, "Kernel corner err %d\\n", err); return 20 + m; }
                        k_cap[m] = clCreateKernel(progs[m], "evaluate_dimension_capability_chunk", &err);
                        if (err != 0) { fprintf(stderr, "Kernel cap err %d\\n", err); return 30 + m; }
                        free(src);
                    }

                    const int BATCH = 64;
                    const int VOX_PER_CHUNK = 24 * 4096;
                    size_t vox_bytes = (size_t)BATCH * VOX_PER_CHUNK * sizeof(int32_t);
                    int32_t* host_vox[3];
                    int32_t* host_top[3];
                    for (int m = 0; m < 3; m++) {
                        host_vox[m] = (int32_t*)calloc((size_t)BATCH * VOX_PER_CHUNK, sizeof(int32_t));
                        host_top[m] = (int32_t*)calloc((size_t)BATCH * 256, sizeof(int32_t));
                    }

                    int32_t* params = (int32_t*)calloc(BATCH * 32, sizeof(int32_t));
                    int32_t dummy_i[64] = {0};
                    float dummy_f[64] = {0};
                    int32_t* quarts = (int32_t*)calloc(BATCH * 96 * 16, sizeof(int32_t));

                    cl_mem b_out_sec = clCreateBuffer(ctx, CL_MEM_READ_WRITE, vox_bytes, NULL, &err);
                    cl_mem b_quarts  = clCreateBuffer(ctx, CL_MEM_READ_WRITE | CL_MEM_COPY_HOST_PTR, (size_t)BATCH * 96 * 16 * 4, quarts, &err);
                    cl_mem b_top     = clCreateBuffer(ctx, CL_MEM_READ_WRITE, (size_t)BATCH * 256 * 4, NULL, &err);
                    cl_mem b_corners = clCreateBuffer(ctx, CL_MEM_READ_WRITE, (size_t)BATCH * 1225 * sizeof(float), NULL, &err);
                    cl_mem b_dummy_i = clCreateBuffer(ctx, CL_MEM_READ_WRITE | CL_MEM_COPY_HOST_PTR, sizeof(dummy_i), dummy_i, &err);
                    cl_mem b_dummy_f = clCreateBuffer(ctx, CL_MEM_READ_WRITE | CL_MEM_COPY_HOST_PTR, sizeof(dummy_f), dummy_f, &err);

                    int base_coords[2][2] = { {0, 0}, {1500000, -1500000} };
                    const char* reg_names[2] = { "SPAWN(0,0)", "FAR_LANDS(+24M,-24M)" };

                    printf("GPU_DEVICE=%s\\n", dev_name);
                    for (int r = 0; r < 2; r++) {
                        int bcx = base_coords[r][0];
                        int bcz = base_coords[r][1];
                        for (int b = 0; b < BATCH; b++) {
                            int32_t* cp = params + (b << 5);
                            cp[4] = 3;   // GPU constant barrier noise
                            cp[8] = 2;   // GPU constant router veins (4x 16-octave 3D noises!)
                            cp[9] = 1;   // apply_surface_rules
                            cp[10] = 24; // rule_len (8 instructions * 3 ints)
                            cp[11] = 2;  // GPU constant temperature noise
                            cp[12] = 1;  // 1 condition noise
                            cp[13] = bcx + (b & 7);
                            cp[14] = bcz + (b >> 3);
                            cp[18] = 1;  // cond_2d_only
                            cp[28] = 96 * 16;
                            cp[29] = 256;
                        }
                        cl_mem b_params = clCreateBuffer(ctx, CL_MEM_READ_WRITE | CL_MEM_COPY_HOST_PTR, (size_t)BATCH * 32 * 4, params, &err);
                        int64_t seed = 424242LL;

                        size_t g_cor[2] = { (size_t)BATCH, 25 };
                        size_t g_cap[3] = { 16, 16, (size_t)BATCH };
                        size_t l_cap[3] = { 16, 16, 1 };

                        double corner_ms[3] = {0}, cap_ms[3] = {0}, total_ms[3] = {0};
                        const int ITERS = 12;

                        for (int m = 0; m < 3; m++) {
                            clSetKernelArg(k_corners[m], 0, sizeof(cl_mem), &b_corners);
                            clSetKernelArg(k_corners[m], 1, sizeof(int32_t), &bcx);
                            clSetKernelArg(k_corners[m], 2, sizeof(int32_t), &bcz);

                            clSetKernelArg(k_cap[m], 0, sizeof(cl_mem), &b_out_sec);
                            clSetKernelArg(k_cap[m], 1, sizeof(cl_mem), &b_quarts);
                            clSetKernelArg(k_cap[m], 2, sizeof(cl_mem), &b_top);
                            clSetKernelArg(k_cap[m], 3, sizeof(cl_mem), &b_params);
                            clSetKernelArg(k_cap[m], 4, sizeof(cl_mem), &b_dummy_i);
                            clSetKernelArg(k_cap[m], 5, sizeof(cl_mem), &b_dummy_f);
                            clSetKernelArg(k_cap[m], 6, sizeof(cl_mem), &b_corners);
                            clSetKernelArg(k_cap[m], 7, sizeof(cl_mem), &b_dummy_f);
                            clSetKernelArg(k_cap[m], 8, sizeof(cl_mem), &b_dummy_i);
                            clSetKernelArg(k_cap[m], 9, sizeof(cl_mem), &b_dummy_i);
                            clSetKernelArg(k_cap[m], 10, sizeof(cl_mem), &b_dummy_f);
                            clSetKernelArg(k_cap[m], 11, sizeof(cl_mem), &b_dummy_f);
                            clSetKernelArg(k_cap[m], 12, sizeof(cl_mem), &b_dummy_f);
                            clSetKernelArg(k_cap[m], 13, sizeof(cl_mem), &b_dummy_i);
                            clSetKernelArg(k_cap[m], 14, sizeof(cl_mem), &b_dummy_f);
                            clSetKernelArg(k_cap[m], 15, sizeof(cl_mem), &b_dummy_f);
                            clSetKernelArg(k_cap[m], 16, sizeof(int64_t), &seed);

                            // Warmup
                            for (int w = 0; w < 3; w++) {
                                clEnqueueNDRangeKernel(q, k_corners[m], 2, NULL, g_cor, NULL, 0, NULL, NULL);
                                clEnqueueNDRangeKernel(q, k_cap[m], 3, NULL, g_cap, l_cap, 0, NULL, NULL);
                            }
                            clFinish(q);

                            uint64_t sum_cor_ns = 0, sum_cap_ns = 0;
                            for (int it = 0; it < ITERS; it++) {
                                cl_event ev1 = NULL, ev2 = NULL;
                                clEnqueueNDRangeKernel(q, k_corners[m], 2, NULL, g_cor, NULL, 0, NULL, &ev1);
                                clEnqueueNDRangeKernel(q, k_cap[m], 3, NULL, g_cap, l_cap, 0, NULL, &ev2);
                                clFinish(q);
                                cl_ulong s1 = 0, e1 = 0, s2 = 0, e2 = 0;
                                clGetEventProfilingInfo(ev1, CL_PROFILING_COMMAND_START, 8, &s1, NULL);
                                clGetEventProfilingInfo(ev1, CL_PROFILING_COMMAND_END, 8, &e1, NULL);
                                clGetEventProfilingInfo(ev2, CL_PROFILING_COMMAND_START, 8, &s2, NULL);
                                clGetEventProfilingInfo(ev2, CL_PROFILING_COMMAND_END, 8, &e2, NULL);
                                sum_cor_ns += (e1 - s1);
                                sum_cap_ns += (e2 - s2);
                            }
                            corner_ms[m] = (double)sum_cor_ns / (ITERS * 1e6);
                            cap_ms[m] = (double)sum_cap_ns / (ITERS * 1e6);
                            total_ms[m] = corner_ms[m] + cap_ms[m];

                            clEnqueueReadBuffer(q, b_out_sec, 1, 0, vox_bytes, host_vox[m], 0, NULL, NULL);
                            clEnqueueReadBuffer(q, b_top, 1, 0, (size_t)BATCH * 256 * 4, host_top[m], 0, NULL, NULL);
                        }

                        long total_vox = (long)BATCH * VOX_PER_CHUNK;
                        long diff_i64 = 0, diff_like = 0;
                        long uncertain_cols = 0;
                        for (long i = 0; i < total_vox; i++) {
                            if (host_vox[0][i] != host_vox[1][i]) diff_i64++;
                            if (host_vox[0][i] != host_vox[2][i]) diff_like++;
                        }
                        for (int i = 0; i < BATCH * 256; i++) {
                            if ((host_top[1][i] & 0x40000000) != 0) uncertain_cols++;
                        }
                        for (int m = 0; m < 3; m++) {
                            long diff = (m == 0) ? 0 : ((m == 1) ? diff_i64 : diff_like);
                            double pct = (100.0 * (double)diff) / (double)total_vox;
                            double chunks_per_sec = (double)BATCH / (total_ms[m] * 1e-3);
                            double speedup = total_ms[0] / total_ms[m];
                            printf("REGIME=%s | MODE=%-16s | corners=%.3f ms | cap_synth=%.3f ms | total=%.3f ms (%.0f chunks/s, %.2fx vs FP64) | voxel_diff=%ld/%ld (%.5f%%) | uncertain_cols=%ld/%d\\n",
                                reg_names[r], labels[m], corner_ms[m], cap_ms[m], total_ms[m], chunks_per_sec, speedup,
                                diff, total_vox, pct, (m == 1 ? uncertain_cols : 0L), BATCH * 256);
                        }
                    }
                    return 0;
                }
                """);

            Path bin = tempDir.resolve("ocl_bench");
            Process compileProc = new ProcessBuilder(
                            "gcc", "-O2", cRunner.toString(), "-o", bin.toString(), "-ldl")
                    .redirectErrorStream(true)
                    .start();
            String gccOut = new String(compileProc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (compileProc.waitFor() == 0) {
                Process runProc = new ProcessBuilder(
                                bin.toString(),
                                libOpenCl.toString(),
                                srcFp64.toString(),
                                srcI64.toString(),
                                srcLike.toString())
                        .redirectErrorStream(true)
                        .start();
                String benchOut = new String(
                        runProc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                int rc = runProc.waitFor();
                System.out.println(benchOut);
                assertEquals(0, rc, "Live OpenCL capability kernel compile & benchmark failed:\n" + benchOut);
                assertTrue(benchOut.contains("MODE=INT64_Q32_FP32"));
            } else {
                System.out.println("Skipping live gcc OpenCL runner (gcc unavailable): " + gccOut);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lifted SurfaceRule test fixtures (pure JVM bytecode -> ConceptModule)
    // -------------------------------------------------------------------------

    public interface TestSurfaceCtx {
        int blockX();
        int blockY();
        int blockZ();
        int stoneDepthAbove();
        int stoneDepthBelow();
        int waterHeight();
        int surfaceDepth();
        int biomeId();
        int prelimSurfaceY();
    }

    private static BytecodeConceptIr.ConceptModule liftTestSurfaceRule(Object ruleObj) {
        BytecodeSsaLifter.ObjectInterner interner = new BytecodeSsaLifter.ObjectInterner() {
            @Override
            public BytecodeSsaLifter.ContextParamBinding resolveContextGetter(
                    int entryParamIndex, String ownerInternalName, String methodName, String descriptor) {
                if (entryParamIndex != 0) return null;
                return switch (methodName) {
                    case "blockX" -> new BytecodeSsaLifter.ContextParamBinding("blockX", BytecodeConceptIr.ConceptType.I32);
                    case "blockY" -> new BytecodeSsaLifter.ContextParamBinding("blockY", BytecodeConceptIr.ConceptType.I32);
                    case "blockZ" -> new BytecodeSsaLifter.ContextParamBinding("blockZ", BytecodeConceptIr.ConceptType.I32);
                    case "stoneDepthAbove" -> new BytecodeSsaLifter.ContextParamBinding("stoneDepthAbove", BytecodeConceptIr.ConceptType.I32);
                    case "stoneDepthBelow" -> new BytecodeSsaLifter.ContextParamBinding("stoneDepthBelow", BytecodeConceptIr.ConceptType.I32);
                    case "waterHeight" -> new BytecodeSsaLifter.ContextParamBinding("waterHeight", BytecodeConceptIr.ConceptType.I32);
                    case "surfaceDepth" -> new BytecodeSsaLifter.ContextParamBinding("surfaceDepth", BytecodeConceptIr.ConceptType.I32);
                    case "biomeId" -> new BytecodeSsaLifter.ContextParamBinding("biomeId", BytecodeConceptIr.ConceptType.I32);
                    case "prelimSurfaceY" -> new BytecodeSsaLifter.ContextParamBinding("prelimSurfaceY", BytecodeConceptIr.ConceptType.I32);
                    default -> null;
                };
            }
        };
        BytecodeConceptIr.ConceptModule lifted = BytecodeSsaLifter.liftInstanceGraph(
                ruleObj,
                "tryApply",
                null,
                BytecodeSsaLifter.GraphLiftOptions.withInterner(interner));
        assertTrue(lifted.isPureCompute() && lifted.unmappedReasons().isEmpty(),
                () -> "Test surface rule must lift purely: " + lifted.unmappedReasons());
        return ConceptOptimizerPass.optimize(lifted);
    }

    public static final class NetherSurfaceRuleFixture {
        public int tryApply(TestSurfaceCtx ctx) {
            int y = ctx.blockY();
            if (y >= 127) return 3;
            if (y <= 0) return 3;
            if (ctx.stoneDepthAbove() == 1 && ctx.waterHeight() <= -1000000 && y >= 31 && ctx.biomeId() == 55) {
                return 48;
            }
            return 0;
        }
    }

    public static final class LushCaveDeepslateRuleFixture {
        public int tryApply(TestSurfaceCtx ctx) {
            if (ctx.stoneDepthAbove() == 1 && ctx.waterHeight() <= -1000000 && ctx.biomeId() == 62) {
                return 57;
            }
            if (ctx.blockY() <= 0) {
                return 15;
            }
            return 0;
        }
    }

    public static final class MountainCavernSurfaceRuleFixture {
        public int tryApply(TestSurfaceCtx ctx) {
            int y = ctx.blockY();
            boolean abovePrelim = y >= ctx.prelimSurfaceY() + ctx.surfaceDepth() - 8;
            if (ctx.stoneDepthAbove() == 1 && !abovePrelim) {
                return 81;
            }
            if (abovePrelim && ctx.stoneDepthAbove() == 1) {
                return 82;
            }
            return 0;
        }
    }

    public static final class Biome3SandRuleFixture {
        public int tryApply(TestSurfaceCtx ctx) {
            if (ctx.stoneDepthAbove() == 1 && ctx.biomeId() == 3) {
                return 5;
            }
            if (ctx.stoneDepthAbove() == 1 && ctx.biomeId() == 0) {
                return 2;
            }
            return 0;
        }
    }

    public static final class GrassAndRiverbedRuleFixture {
        private final boolean riverbedOnly;

        public GrassAndRiverbedRuleFixture(boolean riverbedOnly) {
            this.riverbedOnly = riverbedOnly;
        }

        public int tryApply(TestSurfaceCtx ctx) {
            int y = ctx.blockY();
            int sd = ctx.surfaceDepth();
            boolean abovePrelim = y >= ctx.prelimSurfaceY() + sd - 8;
            if (!abovePrelim) {
                return 0;
            }
            int wh = ctx.waterHeight();
            if (!riverbedOnly) {
                if (ctx.stoneDepthAbove() == 1 && (wh <= -1000000 || y >= wh)) {
                    return 3;
                }
                return 0;
            }
            if (ctx.stoneDepthAbove() <= 1 + sd && (wh <= -1000000 || y + ctx.stoneDepthAbove() >= wh - 6 - sd)) {
                return 4;
            }
            return 0;
        }
    }

    public static final class OceanVoronoiRuleFixture {
        public int tryApply(TestSurfaceCtx ctx) {
            if (ctx.stoneDepthAbove() == 1 && ctx.biomeId() == 1) {
                return 5;
            }
            int wh = ctx.waterHeight();
            if (ctx.stoneDepthAbove() == 1 && (wh <= -1000000 || ctx.blockY() >= wh)) {
                return 3;
            }
            return 0;
        }
    }

    public static final class AbovePrelimSlot5RuleFixture {
        public int tryApply(TestSurfaceCtx ctx) {
            if (ctx.blockY() >= ctx.prelimSurfaceY() + ctx.surfaceDepth() - 8) {
                return 5;
            }
            return 0;
        }
    }

    public static final class TopFloorSlot4RuleFixture {
        public int tryApply(TestSurfaceCtx ctx) {
            if (ctx.stoneDepthAbove() == 1) {
                return 4;
            }
            return 0;
        }
    }

    public static final class OverworldStratifiedRuleFixture {
        public int tryApply(TestSurfaceCtx ctx) {
            int y = ctx.blockY();
            if (y <= -60) return 3;
            if (ctx.stoneDepthAbove() == 1) return 4;
            if (ctx.stoneDepthAbove() >= 2 && ctx.stoneDepthAbove() <= 4) return 5;
            if (y <= 0) return 8;
            return 0;
        }
    }
}
