package io.github.dailystruggle.bytecode.transpile;

import java.nio.IntBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Version- and dimension-agnostic runtime capability tables and evaluators for lifted worldgen
 * graphs ({@code Climate.RTree} 7D BVH biome lookup, arbitrary {@code NoiseRouter} / {@code CubicSpline}
 * / {@code EndIslands} density DAGs, lifted {@code SurfaceRules} bytecode, and data-driven
 * {@code BiomeGenerationSettings} vegetation and ore features).
 *
 * <p>Contains zero hardcoded biome names, block names, or dimension bounds so {@code 26.2},
 * {@code 26.3+}, Nether, End, and custom datapack/bytecode dimensions execute through a single
 * pure-primitive SPI across CPU and OpenCL backends.
 *
 * @governedBy ADR-115, ADR-125, leafgen-ADR-001, leafgen-ADR-002, leafgen-ADR-004, leafgen-ADR-005
 * @trace REQ-CFG-ATLAS-001, REQ-SIM-000
 */
public final class LiftedWorldgenCapabilityTables {

    private static final int[] EMPTY_INT_ARRAY = new int[0];

    /** 16 int32 words (64 B) per flat Climate.RTree BVH node. */
    public static final int RTREE_NODE_STRIDE = 16;

    private static final int MAX_BVH_FANOUT = 6;
    private static final float CLIMATE_QUANT_SCALE = 10000.0f;

    /**
     * Router vein specification for 3D router aquifer veins.
     */
    public record RouterVeinSpec(boolean enabled, int upperMinY, int upperMaxY, int upperOreSlot, int upperRawBlockSlot, int upperFillerSlot,
                                 int lowerMinY, int lowerMaxY, int lowerOreSlot, int lowerRawBlockSlot, int lowerFillerSlot) {
        public static final RouterVeinSpec DISABLED = new RouterVeinSpec(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Router capability channels for 3D aquifer evaluation.
     */
    public record RouterCapabilityChannels(
            DensityFieldEvaluator barrierNoise,
            DensityFieldEvaluator fluidLevelFloodednessNoise,
            DensityFieldEvaluator fluidLevelSpreadNoise,
            DensityFieldEvaluator lavaNoise,
            DensityFieldEvaluator initialDensityWithoutJaggedness,
            DensityFieldEvaluator veinToggle,
            DensityFieldEvaluator veinRidged,
            DensityFieldEvaluator veinGap,
            DensityFieldEvaluator temperatureChannel,
            DensityFieldEvaluator[] conditionNoises,
            boolean[] conditionNoiseIs3d
    ) {
        public RouterCapabilityChannels(
                DensityFieldEvaluator barrierNoise,
                DensityFieldEvaluator fluidLevelFloodednessNoise,
                DensityFieldEvaluator fluidLevelSpreadNoise,
                DensityFieldEvaluator lavaNoise,
                DensityFieldEvaluator initialDensityWithoutJaggedness,
                DensityFieldEvaluator veinToggle,
                DensityFieldEvaluator veinRidged,
                DensityFieldEvaluator veinGap,
                DensityFieldEvaluator temperatureChannel,
                DensityFieldEvaluator[] conditionNoises
        ) {
            this(
                    barrierNoise,
                    fluidLevelFloodednessNoise,
                    fluidLevelSpreadNoise,
                    lavaNoise,
                    initialDensityWithoutJaggedness,
                    veinToggle,
                    veinRidged,
                    veinGap,
                    temperatureChannel,
                    conditionNoises,
                    null);
        }

        public static final RouterCapabilityChannels EMPTY = new RouterCapabilityChannels(
                null, null, null, null, null, null, null, null, null, new DensityFieldEvaluator[0], null);

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof RouterCapabilityChannels other)) {
                return false;
            }
            return java.util.Objects.equals(this.barrierNoise, other.barrierNoise)
                    && java.util.Objects.equals(this.fluidLevelFloodednessNoise, other.fluidLevelFloodednessNoise)
                    && java.util.Objects.equals(this.fluidLevelSpreadNoise, other.fluidLevelSpreadNoise)
                    && java.util.Objects.equals(this.lavaNoise, other.lavaNoise)
                    && java.util.Objects.equals(this.initialDensityWithoutJaggedness, other.initialDensityWithoutJaggedness)
                    && java.util.Objects.equals(this.veinToggle, other.veinToggle)
                    && java.util.Objects.equals(this.veinRidged, other.veinRidged)
                    && java.util.Objects.equals(this.veinGap, other.veinGap)
                    && java.util.Objects.equals(this.temperatureChannel, other.temperatureChannel)
                    && java.util.Arrays.equals(this.conditionNoises, other.conditionNoises)
                    && java.util.Arrays.equals(this.conditionNoiseIs3d, other.conditionNoiseIs3d);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + java.util.Objects.hashCode(this.barrierNoise);
            result = 31 * result + java.util.Objects.hashCode(this.fluidLevelFloodednessNoise);
            result = 31 * result + java.util.Objects.hashCode(this.fluidLevelSpreadNoise);
            result = 31 * result + java.util.Objects.hashCode(this.lavaNoise);
            result = 31 * result + java.util.Objects.hashCode(this.initialDensityWithoutJaggedness);
            result = 31 * result + java.util.Objects.hashCode(this.veinToggle);
            result = 31 * result + java.util.Objects.hashCode(this.veinRidged);
            result = 31 * result + java.util.Objects.hashCode(this.veinGap);
            result = 31 * result + java.util.Objects.hashCode(this.temperatureChannel);
            result = 31 * result + java.util.Arrays.hashCode(this.conditionNoises);
            result = 31 * result + java.util.Arrays.hashCode(this.conditionNoiseIs3d);
            return result;
        }

        @Override
        public String toString() {
            return "RouterCapabilityChannels[barrierNoise=" + this.barrierNoise
                    + ", fluidLevelFloodednessNoise=" + this.fluidLevelFloodednessNoise
                    + ", fluidLevelSpreadNoise=" + this.fluidLevelSpreadNoise
                    + ", lavaNoise=" + this.lavaNoise
                    + ", initialDensityWithoutJaggedness=" + this.initialDensityWithoutJaggedness
                    + ", veinToggle=" + this.veinToggle
                    + ", veinRidged=" + this.veinRidged
                    + ", veinGap=" + this.veinGap
                    + ", temperatureChannel=" + this.temperatureChannel
                    + ", conditionNoises=" + java.util.Arrays.toString(this.conditionNoises)
                    + ", conditionNoiseIs3d=" + java.util.Arrays.toString(this.conditionNoiseIs3d)
                    + "]";
        }
    }

    // WorldCarver kinds lifted from BiomeGenerationSettings#getCarvers
    public static final int CARVER_CAVE = 1;
    public static final int CARVER_CANYON = 2;
    public static final int CARVER_NETHER_CAVE = 3;

    // Feature placement modes lifted from PlacementModifier chains
    public static final int PLACEMENT_SURFACE_HEIGHTMAP = 0;
    public static final int PLACEMENT_MULTI_FLOOR_RANGE = 1;

    // Composable PlacementModifier VM opcodes (4-int stride: [op, a, b, c])
    public static final int PL_COUNT = 1;
    public static final int PL_RARITY_FILTER = 2;
    public static final int PL_NOISE_COUNT = 3;
    public static final int PL_COUNT_ON_EVERY_LAYER = 4;
    public static final int PL_IN_SQUARE = 5;
    public static final int PL_HEIGHTMAP = 6;
    public static final int PL_HEIGHT_RANGE = 7;
    public static final int PL_SURFACE_WATER_DEPTH = 8;
    public static final int PL_SURFACE_REL_THRESHOLD = 9;
    public static final int PL_ENV_SCAN = 10;
    public static final int PL_RANDOM_OFFSET = 11;
    public static final int PL_BLOCK_PREDICATE = 12;
    public static final int PL_BIOME_FILTER = 13;
    public static final int PL_NOISE_THRESHOLD_COUNT = 14;

    // Heightmap kinds for PL_HEIGHTMAP and PL_SURFACE_REL_THRESHOLD
    public static final int HM_MOTION_BLOCKING = 0;
    public static final int HM_MOTION_BLOCKING_NO_LEAVES = 1;
    public static final int HM_OCEAN_FLOOR = 2;
    public static final int HM_WORLD_SURFACE = 3;
    public static final int HM_WORLD_SURFACE_WG = 4;

    // HeightProvider distributions for PL_HEIGHT_RANGE
    public static final int HD_UNIFORM = 0;
    public static final int HD_TRAPEZOID = 1;
    public static final int HD_BIASED_TO_BOTTOM = 2;
    public static final int HD_VERY_BIASED_TO_BOTTOM = 3;

    // BlockPredicate RPN opcodes (3-int stride: [op, packedOffset, arg])
    public static final int BP_TRUE = 1;
    public static final int BP_FALSE = 2;
    public static final int BP_NOT = 3;
    public static final int BP_AND = 4;
    public static final int BP_OR = 5;
    public static final int BP_IS_AIR = 10;
    public static final int BP_IS_REPLACEABLE = 11;
    public static final int BP_IS_SOLID = 12;
    public static final int BP_MATCH_SLOT_MASK = 13;
    public static final int BP_MATCH_WATER = 14;
    public static final int BP_MATCH_LAVA = 15;

    // IntProvider & BlockStateProvider kinds
    public static final int INT_CONST = 0;
    public static final int INT_UNIFORM = 1;
    public static final int INT_BIASED_TO_BOTTOM = 2;
    public static final int INT_CLAMPED_NORMAL = 3;
    public static final int INT_WEIGHTED_LIST = 4;
    public static final int INT_TRAPEZOID = 5;

    public static final int STATE_SIMPLE = 0;
    public static final int STATE_WEIGHTED = 1;
    public static final int STATE_ROTATED_PILLAR = 2;
    public static final int STATE_NOISE = 3;
    public static final int STATE_NOISE_THRESHOLD = 4;

    // TrunkPlacer & FoliagePlacer kinds
    public static final int TRUNK_STRAIGHT = 0;
    public static final int TRUNK_FANCY = 1;
    public static final int TRUNK_FORKING = 2;
    public static final int TRUNK_GIANT_2X2 = 3;
    public static final int TRUNK_DARK_OAK = 4;
    public static final int TRUNK_BENDING = 5;
    public static final int TRUNK_CHERRY = 6;
    public static final int TRUNK_MEGA_JUNGLE = 7;

    public static final int FOLIAGE_BLOB = 0;
    public static final int FOLIAGE_FANCY = 1;
    public static final int FOLIAGE_SPRUCE = 2;
    public static final int FOLIAGE_PINE = 3;
    public static final int FOLIAGE_ACACIA = 4;
    public static final int FOLIAGE_BUSH = 5;
    public static final int FOLIAGE_DARK_OAK = 6;
    public static final int FOLIAGE_CHERRY = 7;
    public static final int FOLIAGE_RANDOM_SPREAD = 8;
    public static final int FOLIAGE_MEGA_JUNGLE = 9;
    public static final int FOLIAGE_MEGA_PINE = 10;

    // Composable LiftedFeatureNode kinds
    public static final int FN_NOOP = 0;
    public static final int FN_ORE = 1;
    public static final int FN_RANDOM_SELECTOR = 2;
    public static final int FN_SIMPLE_RANDOM_SELECTOR = 3;
    public static final int FN_RANDOM_BOOLEAN = 4;
    public static final int FN_TREE = 5;
    public static final int FN_FALLEN_TREE = 6;
    public static final int FN_RANDOM_PATCH = 7;
    public static final int FN_SIMPLE_BLOCK = 8;
    public static final int FN_BLOCK_COLUMN = 9;
    public static final int FN_DISK = 10;
    public static final int FN_UNDERWATER_MAGMA = 11;
    public static final int FN_MULTIFACE = 12;
    public static final int FN_SPELEOTHEM = 13;
    public static final int FN_SPELEOTHEM_CLUSTER = 14;
    public static final int FN_LARGE_DRIPSTONE = 15;
    public static final int FN_GEODE = 16;
    public static final int FN_SCULK_PATCH = 17;
    public static final int FN_VEGETATION_PATCH = 18;
    public static final int FN_ROOT_SYSTEM = 19;
    public static final int FN_HUGE_FUNGUS = 20;
    public static final int FN_HUGE_MUSHROOM = 21;
    public static final int FN_CHORUS_PLANT = 22;
    public static final int FN_BLOCK_BLOB = 23;
    public static final int FN_REPLACE_BLOBS = 24;
    public static final int FN_DELTA = 25;
    public static final int FN_SPIKE = 26;
    public static final int FN_AQUATIC_PLANT = 27;
    public static final int FN_SPRING = 28;
    public static final int FN_LAKE = 29;
    public static final int FN_SNOW_AND_FREEZE = 30;
    public static final int FN_SEQUENCE = 31;

    // Structural vegetation shapes lifted from TrunkPlacer / FoliagePlacer / Feature classes
    public static final int SHAPE_CANOPY = 0;
    public static final int SHAPE_CONIFER = 1;
    public static final int SHAPE_FORKED = 2;
    public static final int SHAPE_GIANT_2X2 = 3;
    public static final int SHAPE_HUGE_FUNGUS = 4;
    public static final int SHAPE_CHORUS = 5;
    public static final int SHAPE_END_SPIKE = 6;
    public static final int SHAPE_PATCH = 7;
    public static final int SHAPE_FALLEN_TREE = 8;
    public static final int SHAPE_DRIPSTONE_CLUSTER = 9;
    public static final int SHAPE_GEODE = 10;
    public static final int SHAPE_SCULK_PATCH = 11;
    public static final int SHAPE_VEGETATION_PATCH = 12;
    public static final int SHAPE_SPIKE = 13;
    public static final int SHAPE_BOULDER = 14;
    public static final int SHAPE_MULTIFACE = 15;

    private static final float[] PERLIN_GRAD_X = {
        1.0f, -1.0f, 1.0f, -1.0f, 1.0f, -1.0f, 1.0f, -1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, -1.0f, 0.0f
    };
    private static final float[] PERLIN_GRAD_Y = {
        1.0f, 1.0f, -1.0f, -1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, -1.0f, 1.0f, -1.0f, 1.0f, -1.0f, 1.0f, -1.0f
    };
    private static final float[] PERLIN_GRAD_Z = {
        0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, -1.0f, -1.0f, 0.0f, 1.0f, 0.0f, -1.0f
    };

    private static int fastFloorFp32(float v) {
        int xi = (int) v;
        return v < (float) xi ? xi - 1 : xi;
    }

    private static float gradDotFp32(int hash, float x, float y, float z) {
        int idx = hash & 15;
        return PERLIN_GRAD_X[idx] * x + PERLIN_GRAD_Y[idx] * y + PERLIN_GRAD_Z[idx] * z;
    }

    private static double gradDotFp64(int hash, double x, double y, double z) {
        int idx = hash & 15;
        return (double) PERLIN_GRAD_X[idx] * x
                + (double) PERLIN_GRAD_Y[idx] * y
                + (double) PERLIN_GRAD_Z[idx] * z;
    }

    private static double sampleOctaveFp64(byte[] p, int off, double d, double e, double f) {
        double flD = Math.floor(d);
        double flE = Math.floor(e);
        double flF = Math.floor(f);
        int i = ((int) (long) flD) & 255;
        int j = ((int) (long) flE) & 255;
        int k = ((int) (long) flF) & 255;
        double g = d - flD;
        double h = e - flE;
        double l = f - flF;
        int p0 = p[off + i] & 255;
        int p1 = p[off + ((i + 1) & 255)] & 255;
        int p00 = p[off + ((p0 + j) & 255)] & 255;
        int p01 = p[off + ((p0 + j + 1) & 255)] & 255;
        int p10 = p[off + ((p1 + j) & 255)] & 255;
        int p11 = p[off + ((p1 + j + 1) & 255)] & 255;
        double gm1 = g - 1.0;
        double hm1 = h - 1.0;
        double lm1 = l - 1.0;
        double c000 = gradDotFp64(p[off + ((p00 + k) & 255)] & 255, g, h, l);
        double c100 = gradDotFp64(p[off + ((p10 + k) & 255)] & 255, gm1, h, l);
        double c010 = gradDotFp64(p[off + ((p01 + k) & 255)] & 255, g, hm1, l);
        double c110 = gradDotFp64(p[off + ((p11 + k) & 255)] & 255, gm1, hm1, l);
        double c001 = gradDotFp64(p[off + ((p00 + k + 1) & 255)] & 255, g, h, lm1);
        double c101 = gradDotFp64(p[off + ((p10 + k + 1) & 255)] & 255, gm1, h, lm1);
        double c011 = gradDotFp64(p[off + ((p01 + k + 1) & 255)] & 255, g, hm1, lm1);
        double c111 = gradDotFp64(p[off + ((p11 + k + 1) & 255)] & 255, gm1, hm1, lm1);
        double sx = g * g * g * (g * (g * 6.0 - 15.0) + 10.0);
        double sy = h * h * h * (h * (h * 6.0 - 15.0) + 10.0);
        double sz = l * l * l * (l * (l * 6.0 - 15.0) + 10.0);
        double x00 = c000 + sx * (c100 - c000);
        double x10 = c010 + sx * (c110 - c010);
        double x01 = c001 + sx * (c101 - c001);
        double x11 = c011 + sx * (c111 - c011);
        double y0 = x00 + sy * (x10 - x00);
        double y1 = x01 + sy * (x11 - x01);
        return y0 + sz * (y1 - y0);
    }

    private static float sampleOctaveFp32(byte[] p, int off, float d, float e, float f) {
        int i = fastFloorFp32(d);
        int j = fastFloorFp32(e);
        int k = fastFloorFp32(f);
        float g = d - (float) i;
        float h = e - (float) j;
        float l = f - (float) k;
        int p0 = p[off + (i & 255)] & 255;
        int p1 = p[off + ((i + 1) & 255)] & 255;
        int p00 = p[off + ((p0 + j) & 255)] & 255;
        int p01 = p[off + ((p0 + j + 1) & 255)] & 255;
        int p10 = p[off + ((p1 + j) & 255)] & 255;
        int p11 = p[off + ((p1 + j + 1) & 255)] & 255;
        float gm1 = g - 1.0f;
        float hm1 = h - 1.0f;
        float lm1 = l - 1.0f;
        float c000 = gradDotFp32(p[off + ((p00 + k) & 255)] & 255, g, h, l);
        float c100 = gradDotFp32(p[off + ((p10 + k) & 255)] & 255, gm1, h, l);
        float c010 = gradDotFp32(p[off + ((p01 + k) & 255)] & 255, g, hm1, l);
        float c110 = gradDotFp32(p[off + ((p11 + k) & 255)] & 255, gm1, hm1, l);
        float c001 = gradDotFp32(p[off + ((p00 + k + 1) & 255)] & 255, g, h, lm1);
        float c101 = gradDotFp32(p[off + ((p10 + k + 1) & 255)] & 255, gm1, h, lm1);
        float c011 = gradDotFp32(p[off + ((p01 + k + 1) & 255)] & 255, g, hm1, lm1);
        float c111 = gradDotFp32(p[off + ((p11 + k + 1) & 255)] & 255, gm1, hm1, lm1);
        float sx = g * g * g * (g * (g * 6.0f - 15.0f) + 10.0f);
        float sy = h * h * h * (h * (h * 6.0f - 15.0f) + 10.0f);
        float sz = l * l * l * (l * (l * 6.0f - 15.0f) + 10.0f);
        float x00 = c000 + sx * (c100 - c000);
        float x10 = c010 + sx * (c110 - c010);
        float x01 = c001 + sx * (c101 - c001);
        float x11 = c011 + sx * (c111 - c011);
        float y0 = x00 + sy * (x10 - x00);
        float y1 = x01 + sy * (x11 - x01);
        return y0 + sz * (y1 - y0);
    }

    /**
     * Pure-primitive flattened {@code NormalNoise} ({@code first + second} {@code PerlinNoise} octaves)
     * shared identically between Java {@link LiftedNormalNoiseEvaluator} and OpenCL {@code __constant}
     * kernel emission ({@code #pragma OPENCL FP_CONTRACT OFF}) for 0-ULP cross-lane parity.
     */
    public record LiftedNormalNoiseSpec(
            byte[] permTable,
            float[] freqX,
            float[] freqY,
            float[] freqZ,
            float[] weights,
            float[] xo,
            float[] yo,
            float[] zo,
            boolean columnConstantY
    ) {
        public LiftedNormalNoiseSpec {
            Objects.requireNonNull(permTable, "permTable");
            Objects.requireNonNull(freqX, "freqX");
            Objects.requireNonNull(freqY, "freqY");
            Objects.requireNonNull(freqZ, "freqZ");
            Objects.requireNonNull(weights, "weights");
            Objects.requireNonNull(xo, "xo");
            Objects.requireNonNull(yo, "yo");
            Objects.requireNonNull(zo, "zo");
            int n = weights.length;
            if (permTable.length != n * 256
                    || freqX.length != n
                    || freqY.length != n
                    || freqZ.length != n
                    || xo.length != n
                    || yo.length != n
                    || zo.length != n) {
                throw new IllegalArgumentException("LiftedNormalNoiseSpec octave array length mismatch");
            }
            permTable = permTable.clone();
            freqX = freqX.clone();
            freqY = freqY.clone();
            freqZ = freqZ.clone();
            weights = weights.clone();
            xo = xo.clone();
            yo = yo.clone();
            zo = zo.clone();
        }

        public int octaveCount() {
            return weights.length;
        }

        public LiftedNormalNoiseSpec withScale(double xzScale, double yScale) {
            int n = weights.length;
            float sxz = (float) xzScale;
            float sy = (float) yScale;
            float[] nfx = new float[n];
            float[] nfy = new float[n];
            float[] nfz = new float[n];
            for (int i = 0; i < n; i++) {
                nfx[i] = freqX[i] * sxz;
                nfy[i] = freqY[i] * sy;
                nfz[i] = freqZ[i] * sxz;
            }
            return new LiftedNormalNoiseSpec(
                    permTable,
                    nfx,
                    nfy,
                    nfz,
                    weights,
                    xo,
                    yo,
                    zo,
                    columnConstantY || yScale == 0.0);
        }

        public LiftedNormalNoiseEvaluator toEvaluator() {
            return new LiftedNormalNoiseEvaluator(this);
        }

        public DensityFieldEvaluator toEvaluator(WorldgenKernelSwapper.WorldgenFidelityPolicy fidelityPolicy) {
            return toEvaluator(fidelityPolicy, WorldgenKernelSwapper.GpuExactArithmeticMode.NATIVE_FP64);
        }

        public DensityFieldEvaluator toEvaluator(
                WorldgenKernelSwapper.WorldgenFidelityPolicy fidelityPolicy,
                WorldgenKernelSwapper.GpuExactArithmeticMode exactArithmeticMode
        ) {
            if (fidelityPolicy != null && fidelityPolicy.isExact()) {
                if (exactArithmeticMode == WorldgenKernelSwapper.GpuExactArithmeticMode.INT64_Q32_FP32) {
                    return new LiftedNormalNoiseInt64Fp32Evaluator(this);
                }
                return new LiftedNormalNoiseFp64Evaluator(this);
            }
            return new LiftedNormalNoiseEvaluator(this, 13);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof LiftedNormalNoiseSpec other)) {
                return false;
            }
            return java.util.Arrays.equals(this.permTable, other.permTable)
                    && java.util.Arrays.equals(this.freqX, other.freqX)
                    && java.util.Arrays.equals(this.freqY, other.freqY)
                    && java.util.Arrays.equals(this.freqZ, other.freqZ)
                    && java.util.Arrays.equals(this.weights, other.weights)
                    && java.util.Arrays.equals(this.xo, other.xo)
                    && java.util.Arrays.equals(this.yo, other.yo)
                    && java.util.Arrays.equals(this.zo, other.zo)
                    && this.columnConstantY == other.columnConstantY;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + java.util.Arrays.hashCode(this.permTable);
            result = 31 * result + java.util.Arrays.hashCode(this.freqX);
            result = 31 * result + java.util.Arrays.hashCode(this.freqY);
            result = 31 * result + java.util.Arrays.hashCode(this.freqZ);
            result = 31 * result + java.util.Arrays.hashCode(this.weights);
            result = 31 * result + java.util.Arrays.hashCode(this.xo);
            result = 31 * result + java.util.Arrays.hashCode(this.yo);
            result = 31 * result + java.util.Arrays.hashCode(this.zo);
            result = 31 * result + Boolean.hashCode(this.columnConstantY);
            return result;
        }

        @Override
        public String toString() {
            return "LiftedNormalNoiseSpec[permTable=" + java.util.Arrays.toString(this.permTable)
                    + ", freqX=" + java.util.Arrays.toString(this.freqX)
                    + ", freqY=" + java.util.Arrays.toString(this.freqY)
                    + ", freqZ=" + java.util.Arrays.toString(this.freqZ)
                    + ", weights=" + java.util.Arrays.toString(this.weights)
                    + ", xo=" + java.util.Arrays.toString(this.xo)
                    + ", yo=" + java.util.Arrays.toString(this.yo)
                    + ", zo=" + java.util.Arrays.toString(this.zo)
                    + ", columnConstantY=" + this.columnConstantY
                    + "]";
        }
    }

    /**
     * Generalized {@code INT64} Q32.32 modulo-256 lattice + {@code FP32} Perlin octave evaluator
     * matching {@link OpenClBackendEmitter}'s {@code eval_lifted_normal_noise_i64f32} kernel path.
     */
    public static final class LiftedNormalNoiseInt64Fp32Evaluator implements DensityFieldEvaluator {
        private final LiftedNormalNoiseSpec spec;
        private final byte[] perm;
        private final long[] stepX;
        private final long[] stepY;
        private final long[] stepZ;
        private final long[] offX;
        private final long[] offY;
        private final long[] offZ;
        private final float[] weights;
        private final boolean colConst;

        public LiftedNormalNoiseInt64Fp32Evaluator(LiftedNormalNoiseSpec spec) {
            this.spec = Objects.requireNonNull(spec, "spec");
            this.perm = spec.permTable;
            int n = spec.weights.length;
            this.stepX = new long[n];
            this.stepY = new long[n];
            this.stepZ = new long[n];
            this.offX = new long[n];
            this.offY = new long[n];
            this.offZ = new long[n];
            this.weights = spec.weights;
            this.colConst = spec.columnConstantY;
            for (int k = 0; k < n; k++) {
                this.stepX[k] = Math.round(((double) spec.freqX[k]) * 4294967296.0);
                this.stepY[k] = Math.round(((double) spec.freqY[k]) * 4294967296.0);
                this.stepZ[k] = Math.round(((double) spec.freqZ[k]) * 4294967296.0);
                double wxo = ((double) spec.xo[k]) - Math.floor(((double) spec.xo[k]) / 256.0) * 256.0;
                double wyo = ((double) spec.yo[k]) - Math.floor(((double) spec.yo[k]) / 256.0) * 256.0;
                double wzo = ((double) spec.zo[k]) - Math.floor(((double) spec.zo[k]) / 256.0) * 256.0;
                this.offX[k] = Math.round(wxo * 4294967296.0);
                this.offY[k] = Math.round(wyo * 4294967296.0);
                this.offZ[k] = Math.round(wzo * 4294967296.0);
            }
        }

        private static float sampleOctaveQ32Fp32(byte[] p, int off, long qx, long qy, long qz) {
            int i = ((int) (qx >> 32)) & 255;
            int j = ((int) (qy >> 32)) & 255;
            int k = ((int) (qz >> 32)) & 255;
            float g = (float) Integer.toUnsignedLong((int) qx) * 0x1.0p-32f;
            float h = (float) Integer.toUnsignedLong((int) qy) * 0x1.0p-32f;
            float l = (float) Integer.toUnsignedLong((int) qz) * 0x1.0p-32f;
            int p0 = p[off + i] & 255;
            int p1 = p[off + ((i + 1) & 255)] & 255;
            int p00 = p[off + ((p0 + j) & 255)] & 255;
            int p01 = p[off + ((p0 + j + 1) & 255)] & 255;
            int p10 = p[off + ((p1 + j) & 255)] & 255;
            int p11 = p[off + ((p1 + j + 1) & 255)] & 255;
            float gm1 = g - 1.0f;
            float hm1 = h - 1.0f;
            float lm1 = l - 1.0f;
            float c000 = gradDotFp32(p[off + ((p00 + k) & 255)] & 255, g, h, l);
            float c100 = gradDotFp32(p[off + ((p10 + k) & 255)] & 255, gm1, h, l);
            float c010 = gradDotFp32(p[off + ((p01 + k) & 255)] & 255, g, hm1, l);
            float c110 = gradDotFp32(p[off + ((p11 + k) & 255)] & 255, gm1, hm1, l);
            float c001 = gradDotFp32(p[off + ((p00 + k + 1) & 255)] & 255, g, h, lm1);
            float c101 = gradDotFp32(p[off + ((p10 + k + 1) & 255)] & 255, gm1, h, lm1);
            float c011 = gradDotFp32(p[off + ((p01 + k + 1) & 255)] & 255, g, hm1, lm1);
            float c111 = gradDotFp32(p[off + ((p11 + k + 1) & 255)] & 255, gm1, hm1, lm1);
            float sx = g * g * g * (g * (g * 6.0f - 15.0f) + 10.0f);
            float sy = h * h * h * (h * (h * 6.0f - 15.0f) + 10.0f);
            float sz = l * l * l * (l * (l * 6.0f - 15.0f) + 10.0f);
            float x00 = c000 + sx * (c100 - c000);
            float x10 = c010 + sx * (c110 - c010);
            float x01 = c001 + sx * (c101 - c001);
            float x11 = c011 + sx * (c111 - c011);
            float y0 = x00 + sy * (x10 - x00);
            float y1 = x01 + sy * (x11 - x01);
            return y0 + sz * (y1 - y0);
        }

        @Override
        public float sample(int blockX, int blockY, int blockZ) {
            long lx = blockX;
            long ly = colConst ? 0L : blockY;
            long lz = blockZ;
            float sum = 0.0f;
            int n = weights.length;
            for (int k = 0; k < n; k++) {
                long qx = lx * stepX[k] + offX[k];
                long qy = ly * stepY[k] + offY[k];
                long qz = lz * stepZ[k] + offZ[k];
                sum += sampleOctaveQ32Fp32(perm, k << 8, qx, qy, qz) * weights[k];
            }
            return sum;
        }

        @Override
        public boolean isColumnConstantY() {
            return colConst;
        }

        @Override
        public LiftedNormalNoiseSpec asPureNormalNoiseSpec() {
            return spec;
        }
    }

    /**
     * Full-octave IEEE-754 {@code FP64} Perlin evaluator for {@code VANILLA_EXACT} capability synthesis.
     */
    public static final class LiftedNormalNoiseFp64Evaluator implements DensityFieldEvaluator {
        private final LiftedNormalNoiseSpec backingSpecOrNull;
        private final byte[] perm;
        private final double[] freqX;
        private final double[] freqY;
        private final double[] freqZ;
        private final double[] weights;
        private final double[] xo;
        private final double[] yo;
        private final double[] zo;
        private final boolean colConst;

        public LiftedNormalNoiseFp64Evaluator(LiftedNormalNoiseSpec spec) {
            this.backingSpecOrNull = Objects.requireNonNull(spec, "spec");
            this.perm = spec.permTable;
            int n = spec.weights.length;
            this.freqX = toDoubleArray(spec.freqX, n);
            this.freqY = toDoubleArray(spec.freqY, n);
            this.freqZ = toDoubleArray(spec.freqZ, n);
            this.weights = toDoubleArray(spec.weights, n);
            this.xo = toDoubleArray(spec.xo, n);
            this.yo = toDoubleArray(spec.yo, n);
            this.zo = toDoubleArray(spec.zo, n);
            this.colConst = spec.columnConstantY;
        }

        public LiftedNormalNoiseFp64Evaluator(
                byte[] permTable,
                double[] freqX,
                double[] freqY,
                double[] freqZ,
                double[] weights,
                double[] xo,
                double[] yo,
                double[] zo,
                boolean columnConstantY
        ) {
            Objects.requireNonNull(permTable, "permTable");
            Objects.requireNonNull(weights, "weights");
            int n = weights.length;
            if (permTable.length != n * 256
                    || freqX.length != n
                    || freqY.length != n
                    || freqZ.length != n
                    || xo.length != n
                    || yo.length != n
                    || zo.length != n) {
                throw new IllegalArgumentException("LiftedNormalNoiseFp64Evaluator octave array length mismatch");
            }
            this.backingSpecOrNull = null;
            this.perm = permTable.clone();
            this.freqX = freqX.clone();
            this.freqY = freqY.clone();
            this.freqZ = freqZ.clone();
            this.weights = weights.clone();
            this.xo = xo.clone();
            this.yo = yo.clone();
            this.zo = zo.clone();
            this.colConst = columnConstantY;
        }

        private static double[] toDoubleArray(float[] src, int n) {
            double[] dst = new double[n];
            for (int i = 0; i < n; i++) {
                dst[i] = src[i];
            }
            return dst;
        }

        public double sampleContinuousFp64(double x, double y, double z) {
            double effY = colConst ? 0.0 : y;
            double sum = 0.0;
            int n = weights.length;
            for (int k = 0; k < n; k++) {
                double d = x * freqX[k] + xo[k];
                double e = effY * freqY[k] + yo[k];
                double f = z * freqZ[k] + zo[k];
                sum += sampleOctaveFp64(perm, k << 8, d, e, f) * weights[k];
            }
            return sum;
        }

        @Override
        public double sampleFp64(int blockX, int blockY, int blockZ) {
            return sampleContinuousFp64(blockX, colConst ? 0.0 : blockY, blockZ);
        }

        @Override
        public float sample(int blockX, int blockY, int blockZ) {
            return (float) sampleFp64(blockX, blockY, blockZ);
        }

        @Override
        public void sampleColumnYFp64(
                int blockX, int baseY, int stepY, int countY, int blockZ, double[] outColumn, int outOffset) {
            if (countY <= 0) {
                return;
            }
            if (colConst) {
                double v = sampleFp64(blockX, 0, blockZ);
                Arrays.fill(outColumn, outOffset, outOffset + countY, v);
                return;
            }
            for (int i = 0; i < countY; i++) {
                outColumn[outOffset + i] = sampleFp64(blockX, baseY + i * stepY, blockZ);
            }
        }

        @Override
        public boolean isColumnConstantY() {
            return colConst;
        }

        @Override
        public LiftedNormalNoiseSpec asPureNormalNoiseSpec() {
            return backingSpecOrNull;
        }
    }

    /**
     * Pure-Java {@code FP32} Perlin octave evaluator hoisting column-invariant {@code (X, Z)} lattice
     * and quintic-fade terms across {@link #sampleColumnY} with 0-ULP parity against {@link #sample}.
     */
    public static final class LiftedNormalNoiseEvaluator implements DensityFieldEvaluator {
        private final LiftedNormalNoiseSpec spec;
        private final byte[] perm;
        private final float[] freqX;
        private final float[] freqY;
        private final float[] freqZ;
        private final float[] weights;
        private final float[] xo;
        private final float[] yo;
        private final float[] zo;
        private final boolean colConst;
        private final int activeOctaveCount;

        public LiftedNormalNoiseEvaluator(LiftedNormalNoiseSpec spec) {
            this(spec, Integer.MAX_VALUE);
        }

        public LiftedNormalNoiseEvaluator(LiftedNormalNoiseSpec spec, int maxOctavesCap) {
            this.spec = Objects.requireNonNull(spec, "spec");
            this.perm = spec.permTable;
            this.freqX = spec.freqX;
            this.freqY = spec.freqY;
            this.freqZ = spec.freqZ;
            this.weights = spec.weights;
            this.xo = spec.xo;
            this.yo = spec.yo;
            this.zo = spec.zo;
            this.colConst = spec.columnConstantY;
            this.activeOctaveCount = Math.max(0, Math.min(spec.weights.length, maxOctavesCap));
        }

        public float sampleContinuous(float x, float y, float z) {
            float effY = colConst ? 0.0f : y;
            float sum = 0.0f;
            int n = activeOctaveCount;
            for (int k = 0; k < n; k++) {
                float d = x * freqX[k] + xo[k];
                float e = effY * freqY[k] + yo[k];
                float f = z * freqZ[k] + zo[k];
                sum += sampleOctaveFp32(perm, k << 8, d, e, f) * weights[k];
            }
            return sum;
        }

        @Override
        public float sample(int blockX, int blockY, int blockZ) {
            return sampleContinuous((float) blockX, colConst ? 0.0f : (float) blockY, (float) blockZ);
        }

        @Override
        public void sampleColumnY(
                int blockX, int baseY, int stepY, int countY, int blockZ, float[] outColumn, int outOffset) {
            if (countY <= 0) {
                return;
            }
            if (colConst) {
                float v = sample(blockX, 0, blockZ);
                Arrays.fill(outColumn, outOffset, outOffset + countY, v);
                return;
            }
            Arrays.fill(outColumn, outOffset, outOffset + countY, 0.0f);
            float fbx = (float) blockX;
            float fbz = (float) blockZ;
            int n = activeOctaveCount;
            for (int k = 0; k < n; k++) {
                int off = k << 8;
                float d = fbx * freqX[k] + xo[k];
                float f = fbz * freqZ[k] + zo[k];
                int i = fastFloorFp32(d);
                int m = fastFloorFp32(f);
                float g = d - (float) i;
                float l = f - (float) m;
                float gm1 = g - 1.0f;
                float lm1 = l - 1.0f;
                float sx = g * g * g * (g * (g * 6.0f - 15.0f) + 10.0f);
                float sz = l * l * l * (l * (l * 6.0f - 15.0f) + 10.0f);
                int p0 = perm[off + (i & 255)] & 255;
                int p1 = perm[off + ((i + 1) & 255)] & 255;
                float fy = freqY[k];
                float oy = yo[k];
                float w = weights[k];

                int prevJ = Integer.MIN_VALUE;
                float x000 = 0.0f, y000 = 0.0f, z000 = 0.0f;
                float x100 = 0.0f, y100 = 0.0f, z100 = 0.0f;
                float x010 = 0.0f, y010 = 0.0f, z010 = 0.0f;
                float x110 = 0.0f, y110 = 0.0f, z110 = 0.0f;
                float x001 = 0.0f, y001 = 0.0f, z001 = 0.0f;
                float x101 = 0.0f, y101 = 0.0f, z101 = 0.0f;
                float x011 = 0.0f, y011 = 0.0f, z011 = 0.0f;
                float x111 = 0.0f, y111 = 0.0f, z111 = 0.0f;
                for (int yi = 0; yi < countY; yi++) {
                    float e = (float) (baseY + yi * stepY) * fy + oy;
                    int j = fastFloorFp32(e);
                    float h = e - (float) j;
                    float hm1 = h - 1.0f;
                    if (j != prevJ) {
                        prevJ = j;
                        int p00 = perm[off + ((p0 + j) & 255)] & 255;
                        int p01 = perm[off + ((p0 + j + 1) & 255)] & 255;
                        int p10 = perm[off + ((p1 + j) & 255)] & 255;
                        int p11 = perm[off + ((p1 + j + 1) & 255)] & 255;
                        int h000 = perm[off + ((p00 + m) & 255)] & 15;
                        int h100 = perm[off + ((p10 + m) & 255)] & 15;
                        int h010 = perm[off + ((p01 + m) & 255)] & 15;
                        int h110 = perm[off + ((p11 + m) & 255)] & 15;
                        int h001 = perm[off + ((p00 + m + 1) & 255)] & 15;
                        int h101 = perm[off + ((p10 + m + 1) & 255)] & 15;
                        int h011 = perm[off + ((p01 + m + 1) & 255)] & 15;
                        int h111 = perm[off + ((p11 + m + 1) & 255)] & 15;
                        x000 = PERLIN_GRAD_X[h000] * g;   y000 = PERLIN_GRAD_Y[h000]; z000 = PERLIN_GRAD_Z[h000] * l;
                        x100 = PERLIN_GRAD_X[h100] * gm1; y100 = PERLIN_GRAD_Y[h100]; z100 = PERLIN_GRAD_Z[h100] * l;
                        x010 = PERLIN_GRAD_X[h010] * g;   y010 = PERLIN_GRAD_Y[h010]; z010 = PERLIN_GRAD_Z[h010] * l;
                        x110 = PERLIN_GRAD_X[h110] * gm1; y110 = PERLIN_GRAD_Y[h110]; z110 = PERLIN_GRAD_Z[h110] * l;
                        x001 = PERLIN_GRAD_X[h001] * g;   y001 = PERLIN_GRAD_Y[h001]; z001 = PERLIN_GRAD_Z[h001] * lm1;
                        x101 = PERLIN_GRAD_X[h101] * gm1; y101 = PERLIN_GRAD_Y[h101]; z101 = PERLIN_GRAD_Z[h101] * lm1;
                        x011 = PERLIN_GRAD_X[h011] * g;   y011 = PERLIN_GRAD_Y[h011]; z011 = PERLIN_GRAD_Z[h011] * lm1;
                        x111 = PERLIN_GRAD_X[h111] * gm1; y111 = PERLIN_GRAD_Y[h111]; z111 = PERLIN_GRAD_Z[h111] * lm1;
                    }
                    float c000 = (x000 + y000 * h) + z000;
                    float c100 = (x100 + y100 * h) + z100;
                    float c010 = (x010 + y010 * hm1) + z010;
                    float c110 = (x110 + y110 * hm1) + z110;
                    float c001 = (x001 + y001 * h) + z001;
                    float c101 = (x101 + y101 * h) + z101;
                    float c011 = (x011 + y011 * hm1) + z011;
                    float c111 = (x111 + y111 * hm1) + z111;
                    float sy = h * h * h * (h * (h * 6.0f - 15.0f) + 10.0f);
                    float x00 = c000 + sx * (c100 - c000);
                    float x10 = c010 + sx * (c110 - c010);
                    float x01 = c001 + sx * (c101 - c001);
                    float x11 = c011 + sx * (c111 - c011);
                    float y0 = x00 + sy * (x10 - x00);
                    float y1 = x01 + sy * (x11 - x01);
                    outColumn[outOffset + yi] += (y0 + sz * (y1 - y0)) * w;
                }
            }
        }

        @Override
        public boolean isColumnConstantY() {
            return colConst;
        }

        @Override
        public LiftedNormalNoiseSpec asPureNormalNoiseSpec() {
            return spec;
        }
    }

    /**
     * Device-lowerable {@link LiftedNormalNoiseSpec} bundle for {@code evaluate_dimension_capability_chunk}
     * so OpenCL evaluates barrier, vein, temperature, and {@code SurfaceRules} condition noises lazily in
     * {@code __constant} cache without multi-MB 3D CPU pre-sampling.
     */
    public record GpuRouterNoiseTable(
            LiftedNormalNoiseSpec barrierNoise,
            LiftedNormalNoiseSpec veinToggleNoise,
            LiftedNormalNoiseSpec veinRidgedNoise,
            LiftedNormalNoiseSpec veinRidgedNoiseB,
            float veinRidgedOffset,
            LiftedNormalNoiseSpec veinGapNoise,
            LiftedNormalNoiseSpec temperatureNoise,
            LiftedNormalNoiseSpec[] conditionNoises,
            boolean[] conditionNoiseIs3d
    ) {
        public static final int MAX_CONSTANT_OCTAVES = 208;

        public static final GpuRouterNoiseTable EMPTY = new GpuRouterNoiseTable(
                null, null, null, null, -0.08f, null, null, new LiftedNormalNoiseSpec[0], new boolean[0]);

        public GpuRouterNoiseTable(
                LiftedNormalNoiseSpec barrierNoise,
                LiftedNormalNoiseSpec veinToggleNoise,
                LiftedNormalNoiseSpec veinRidgedNoise,
                LiftedNormalNoiseSpec veinRidgedNoiseB,
                LiftedNormalNoiseSpec veinGapNoise,
                LiftedNormalNoiseSpec temperatureNoise,
                LiftedNormalNoiseSpec[] conditionNoises,
                boolean[] conditionNoiseIs3d
        ) {
            this(
                    barrierNoise,
                    veinToggleNoise,
                    veinRidgedNoise,
                    veinRidgedNoiseB,
                    -0.08f,
                    veinGapNoise,
                    temperatureNoise,
                    conditionNoises,
                    conditionNoiseIs3d);
        }

        public GpuRouterNoiseTable(
                LiftedNormalNoiseSpec barrierNoise,
                LiftedNormalNoiseSpec veinToggleNoise,
                LiftedNormalNoiseSpec veinRidgedNoise,
                LiftedNormalNoiseSpec veinGapNoise,
                LiftedNormalNoiseSpec temperatureNoise,
                LiftedNormalNoiseSpec[] conditionNoises,
                boolean[] conditionNoiseIs3d
        ) {
            this(
                    barrierNoise,
                    veinToggleNoise,
                    veinRidgedNoise,
                    null,
                    -0.08f,
                    veinGapNoise,
                    temperatureNoise,
                    conditionNoises,
                    conditionNoiseIs3d);
        }

        public GpuRouterNoiseTable {
            conditionNoises = conditionNoises != null ? conditionNoises.clone() : new LiftedNormalNoiseSpec[0];
            conditionNoiseIs3d = conditionNoiseIs3d != null ? conditionNoiseIs3d.clone() : new boolean[0];
        }

        public int totalOctaveCount() {
            int total = 0;
            if (barrierNoise != null) total += barrierNoise.octaveCount();
            if (veinToggleNoise != null) total += veinToggleNoise.octaveCount();
            if (veinRidgedNoise != null) total += veinRidgedNoise.octaveCount();
            if (veinRidgedNoiseB != null) total += veinRidgedNoiseB.octaveCount();
            if (veinGapNoise != null) total += veinGapNoise.octaveCount();
            if (temperatureNoise != null) total += temperatureNoise.octaveCount();
            for (LiftedNormalNoiseSpec cn : conditionNoises) {
                if (cn != null) total += cn.octaveCount();
            }
            return total;
        }

        public boolean fitsConstantBudget() {
            int total = totalOctaveCount();
            return total > 0 && total <= MAX_CONSTANT_OCTAVES;
        }

        public boolean hasGpuBarrierNoise() {
            return fitsConstantBudget() && barrierNoise != null && barrierNoise.octaveCount() > 0;
        }

        public boolean hasGpuVeinNoises() {
            return fitsConstantBudget()
                    && veinToggleNoise != null && veinToggleNoise.octaveCount() > 0
                    && veinRidgedNoise != null && veinRidgedNoise.octaveCount() > 0
                    && veinGapNoise != null && veinGapNoise.octaveCount() > 0;
        }

        public boolean hasGpuTemperatureNoise() {
            return fitsConstantBudget() && temperatureNoise != null && temperatureNoise.octaveCount() > 0;
        }

        public boolean allConditionNoisesOnGpu(int expectedCount) {
            if (expectedCount <= 0) {
                return true;
            }
            if (!fitsConstantBudget() || conditionNoises.length < expectedCount) {
                return false;
            }
            for (int i = 0; i < expectedCount; i++) {
                if (conditionNoises[i] == null || conditionNoises[i].octaveCount() <= 0) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof GpuRouterNoiseTable other)) {
                return false;
            }
            return java.util.Objects.equals(this.barrierNoise, other.barrierNoise)
                    && java.util.Objects.equals(this.veinToggleNoise, other.veinToggleNoise)
                    && java.util.Objects.equals(this.veinRidgedNoise, other.veinRidgedNoise)
                    && java.util.Objects.equals(this.veinRidgedNoiseB, other.veinRidgedNoiseB)
                    && Float.compare(this.veinRidgedOffset, other.veinRidgedOffset) == 0
                    && java.util.Objects.equals(this.veinGapNoise, other.veinGapNoise)
                    && java.util.Objects.equals(this.temperatureNoise, other.temperatureNoise)
                    && java.util.Arrays.equals(this.conditionNoises, other.conditionNoises)
                    && java.util.Arrays.equals(this.conditionNoiseIs3d, other.conditionNoiseIs3d);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + java.util.Objects.hashCode(this.barrierNoise);
            result = 31 * result + java.util.Objects.hashCode(this.veinToggleNoise);
            result = 31 * result + java.util.Objects.hashCode(this.veinRidgedNoise);
            result = 31 * result + java.util.Objects.hashCode(this.veinRidgedNoiseB);
            result = 31 * result + Float.hashCode(this.veinRidgedOffset);
            result = 31 * result + java.util.Objects.hashCode(this.veinGapNoise);
            result = 31 * result + java.util.Objects.hashCode(this.temperatureNoise);
            result = 31 * result + java.util.Arrays.hashCode(this.conditionNoises);
            result = 31 * result + java.util.Arrays.hashCode(this.conditionNoiseIs3d);
            return result;
        }

        @Override
        public String toString() {
            return "GpuRouterNoiseTable[barrierNoise=" + this.barrierNoise
                    + ", veinToggleNoise=" + this.veinToggleNoise
                    + ", veinRidgedNoise=" + this.veinRidgedNoise
                    + ", veinRidgedNoiseB=" + this.veinRidgedNoiseB
                    + ", veinRidgedOffset=" + this.veinRidgedOffset
                    + ", veinGapNoise=" + this.veinGapNoise
                    + ", temperatureNoise=" + this.temperatureNoise
                    + ", conditionNoises=" + java.util.Arrays.toString(this.conditionNoises)
                    + ", conditionNoiseIs3d=" + java.util.Arrays.toString(this.conditionNoiseIs3d)
                    + "]";
        }
    }

    private LiftedWorldgenCapabilityTables() {}

    /**
     * 3D scalar density evaluator lifted from {@code NoiseRouter#finalDensity()} or custom bytecode.
     */
    @FunctionalInterface
    public interface DensityFieldEvaluator {
        float sample(int blockX, int blockY, int blockZ);

        /**
         * Full-precision {@code FP64} scalar density sample used under
         * {@link WorldgenKernelSwapper.WorldgenFidelityPolicy#VANILLA_EXACT}.
         */
        default double sampleFp64(int blockX, int blockY, int blockZ) {
            return sample(blockX, blockY, blockZ);
        }

        /**
         * Evaluates {@code countY} regularly spaced {@code FP64} Y samples along column
         * {@code (blockX, blockZ)} without isosurface threshold clamping.
         */
        default void sampleColumnYFp64(
                int blockX,
                int baseY,
                int stepY,
                int countY,
                int blockZ,
                double[] outColumn,
                int outOffset
        ) {
            for (int i = 0; i < countY; i++) {
                outColumn[outOffset + i] = sampleFp64(blockX, baseY + i * stepY, blockZ);
            }
        }

        /**
         * Evaluates {@code countY} regularly spaced Y samples along column {@code (blockX, blockZ)}
         * into {@code outColumn[outOffset .. outOffset + countY - 1]} so column-hoisted evaluators
         * can execute branchless unit-stride SIMD loops.
         */
        default void sampleColumnY(
                int blockX,
                int baseY,
                int stepY,
                int countY,
                int blockZ,
                float[] outColumn,
                int outOffset
        ) {
            for (int i = 0; i < countY; i++) {
                outColumn[outOffset + i] = sample(blockX, baseY + i * stepY, blockZ);
            }
        }

        /**
         * Top-down sign/threshold demand pass: evaluates a Y-column where values {@code <= minUsefulValue}
         * lie below the downstream isosurface interest bound, allowing monotonic {@code MIN} / {@code RangeChoice}
         * / affine nodes to prune dead sky/subsurface sub-ranges algebraically without hardcoding version shapes.
         */
        default void sampleColumnYAboveThreshold(
                int blockX,
                int baseY,
                int stepY,
                int countY,
                int blockZ,
                float minUsefulValue,
                float[] outColumn,
                int outOffset
        ) {
            sampleColumnY(blockX, baseY, stepY, countY, blockZ, outColumn, outOffset);
        }

        /**
         * Returns the pre-interpolated 2D preliminary surface Y at {@code (blockX, blockZ)} when
         * backed by a direct 2D height evaluator, or {@link Integer#MIN_VALUE} when 3D density
         * threshold scanning is required.
         */
        default int resolveDirectSurfaceY(int blockX, int blockZ) {
            return Integer.MIN_VALUE;
        }

        /**
         * Returns {@code true} when this evaluator is invariant with respect to {@code blockY}
         * along any fixed {@code (blockX, blockZ)} column.
         */
        default boolean isColumnConstantY() {
            return false;
        }

        default boolean isQuartAlignedXZ() {
            return false;
        }

        default boolean isTrilinearCellInterpolated(int cellW, int cellH) {
            return false;
        }

        default LiftedNormalNoiseSpec asPureNormalNoiseSpec() {
            return null;
        }

        /**
         * Returns the retained pure {@link BytecodeConceptIr.ConceptModule} IR for this density
         * field when lifted through {@link BytecodeSsaLifter}, or {@code null} if unavailable.
         */
        default BytecodeConceptIr.ConceptModule conceptModule() {
            return null;
        }
    }

    /** Mojang {@code Aquifer$NoiseBasedAquifer} dry-cell fluid-level sentinel ({@code -32512}). */
    public static final int AQUIFER_WAY_BELOW_MIN_Y = -32512;

    private static final int[][] MOJANG_SURFACE_OFFSETS_CHUNKS = {
        {0, 0},
        {-2, -1}, {-1, -1}, {0, -1}, {1, -1},
        {-3, 0}, {-2, 0}, {-1, 0}, {1, 0},
        {-2, 1}, {-1, 1}, {0, 1}, {1, 1}
    };

    /** 2D preliminary surface elevation callback ({@code NoiseChunk#preliminarySurfaceLevel(bx, bz)}). */
    @FunctionalInterface
    public interface PreliminarySurfaceSampler {
        int samplePreliminarySurfaceY(int blockX, int blockZ);
    }

    /**
     * Thread-safe positional cell-center jitter callback ({@code PositionalRandomFactory#at(gx, gy, gz)}),
     * writing {@code (centerX, centerY, centerZ)} into {@code outXyz[0..2]}.
     */
    @FunctionalInterface
    public interface PositionalCellJitter {
        void computeCellCenter(
                int gridX,
                int gridY,
                int gridZ,
                int xSpacing,
                int ySpacing,
                int zSpacing,
                int xRange,
                int yRange,
                int zRange,
                int[] outXyz);
    }

    /**
     * Immutable, thread-isolated per-chunk {@code Aquifer$NoiseBasedAquifer} {@code 4 x gridSizeY x 4}
     * cell cache snapshot ({@code [centerX, centerY, centerZ, fluidLevel, fluidSlot]} per cell).
     */
    public static final class AquiferCellSnapshot {
        private final int minGridX;
        private final int minGridY;
        private final int minGridZ;
        private final int gridSizeX;
        private final int gridSizeY;
        private final int gridSizeZ;
        private final int skipSamplingAboveY;
        private final int xSpacing;
        private final int ySpacing;
        private final int zSpacing;
        private final float similarityScale;
        private final int[] packedCells;
        final short[] uniform18Slot;
        final int[] uniform18Lvl;

        public AquiferCellSnapshot(
                int minGridX,
                int minGridY,
                int minGridZ,
                int gridSizeX,
                int gridSizeY,
                int gridSizeZ,
                int skipSamplingAboveY,
                int xSpacing,
                int ySpacing,
                int zSpacing,
                float similarityScale,
                int[] packedCells
        ) {
            this.minGridX = minGridX;
            this.minGridY = minGridY;
            this.minGridZ = minGridZ;
            this.gridSizeX = gridSizeX;
            this.gridSizeY = gridSizeY;
            this.gridSizeZ = gridSizeZ;
            this.skipSamplingAboveY = skipSamplingAboveY;
            this.xSpacing = Math.max(1, xSpacing);
            this.ySpacing = Math.max(1, ySpacing);
            this.zSpacing = Math.max(1, zSpacing);
            this.similarityScale = similarityScale > 0.0f ? similarityScale : 25.0f;
            this.packedCells = packedCells != null ? packedCells.clone() : new int[0];
            int total = this.packedCells.length / 5;
            this.uniform18Slot = new short[total];
            this.uniform18Lvl = new int[total];
            if (total > 0 && gridSizeX > 0 && gridSizeY > 0 && gridSizeZ > 0) {
                for (int ry0 = 0; ry0 < gridSizeY; ry0++) {
                    int ry1 = Math.min(gridSizeY - 1, ry0 + 1);
                    for (int rz0 = 0; rz0 < gridSizeZ; rz0++) {
                        int rz1 = Math.min(gridSizeZ - 1, rz0 + 1);
                        int rz2 = Math.min(gridSizeZ - 1, rz0 + 2);
                        for (int rx0 = 0; rx0 < gridSizeX; rx0++) {
                            int rx1 = Math.min(gridSizeX - 1, rx0 + 1);
                            int rx2 = Math.min(gridSizeX - 1, rx0 + 2);
                            int baseIdx = (ry0 * gridSizeZ + rz0) * gridSizeX + rx0;
                            if (baseIdx >= total) {
                                continue;
                            }
                            int fLvl = this.packedCells[baseIdx * 5 + 3];
                            int fSlot = this.packedCells[baseIdx * 5 + 4];
                            boolean uni = true;
                            for (int oy = 0; oy <= 1 && uni; oy++) {
                                int yB = (oy == 0 ? ry0 : ry1) * gridSizeZ;
                                for (int oz = 0; oz <= 2 && uni; oz++) {
                                    int rz = oz == 0 ? rz0 : (oz == 1 ? rz1 : rz2);
                                    int zB = (yB + rz) * gridSizeX;
                                    for (int ox = 0; ox <= 2; ox++) {
                                        int rx = ox == 0 ? rx0 : (ox == 1 ? rx1 : rx2);
                                        int idx = zB + rx;
                                        if (idx < total) {
                                            int cb = idx * 5;
                                            if (this.packedCells[cb + 3] != fLvl || this.packedCells[cb + 4] != fSlot) {
                                                uni = false;
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                            if (uni) {
                                this.uniform18Slot[baseIdx] = (short) fSlot;
                                this.uniform18Lvl[baseIdx] = fLvl;
                            } else {
                                this.uniform18Slot[baseIdx] = -1;
                            }
                        }
                    }
                }
            }
        }

        public int minGridX() { return minGridX; }
        public int minGridY() { return minGridY; }
        public int minGridZ() { return minGridZ; }
        public int gridSizeX() { return gridSizeX; }
        public int gridSizeY() { return gridSizeY; }
        public int gridSizeZ() { return gridSizeZ; }
        public int skipSamplingAboveY() { return skipSamplingAboveY; }
        public int xSpacing() { return xSpacing; }
        public int ySpacing() { return ySpacing; }
        public int zSpacing() { return zSpacing; }
        public float similarityScale() { return similarityScale; }
        public int cellCount() { return packedCells.length / 5; }

        public IntBuffer asReadOnlyBuffer() {
            return IntBuffer.wrap(packedCells).asReadOnlyBuffer();
        }

        public float[] toGpuFloatCells() {
            float[] out = new float[packedCells.length];
            for (int i = 0; i < packedCells.length; i++) {
                out[i] = (float) packedCells[i];
            }
            return out;
        }

        public int cellCenterX(int cellIdx) { return packedCells[cellIdx * 5]; }
        public int cellCenterY(int cellIdx) { return packedCells[cellIdx * 5 + 1]; }
        public int cellCenterZ(int cellIdx) { return packedCells[cellIdx * 5 + 2]; }
        public int cellFluidLevel(int cellIdx) { return packedCells[cellIdx * 5 + 3]; }
        public int cellFluidSlot(int cellIdx) { return packedCells[cellIdx * 5 + 4]; }
    }

    /** Stateless thread-safe factory producing isolated per-chunk {@link AquiferCellSnapshot} tables. */
    @FunctionalInterface
    public interface AquiferCellGridSampler {
        AquiferCellSnapshot sampleChunkSnapshot(int chunkX, int chunkZ);
    }

    /**
     * Builds a stateless thread-safe {@link AquiferCellGridSampler} executing Mojang's lifted
     * {@code Aquifer$NoiseBasedAquifer} cell grid, {@code 13}-offset preliminary-surface throat check,
     * Deep Dark dryness check, {@code 16x40x16} randomized spread buckets, and {@code Y <= -10}
     * {@code 64x40x64} lava buckets.
     */
    public static AquiferCellGridSampler createStandardNoiseBasedAquiferSampler(
            DimensionCapabilitySpec spec,
            long worldSeed,
            BytecodeSsaLifter.LiftedAquiferBytecodeSpec liftedBytecode,
            DensityFieldEvaluator barrierNoise,
            DensityFieldEvaluator floodednessNoise,
            DensityFieldEvaluator spreadNoise,
            DensityFieldEvaluator lavaNoise,
            DensityFieldEvaluator erosionNoise,
            DensityFieldEvaluator depthNoise,
            PreliminarySurfaceSampler prelimSampler
    ) {
        return createStandardNoiseBasedAquiferSampler(
                spec,
                worldSeed,
                liftedBytecode,
                barrierNoise,
                floodednessNoise,
                spreadNoise,
                lavaNoise,
                erosionNoise,
                depthNoise,
                prelimSampler,
                null);
    }

    public static AquiferCellGridSampler createStandardNoiseBasedAquiferSampler(
            DimensionCapabilitySpec spec,
            long worldSeed,
            BytecodeSsaLifter.LiftedAquiferBytecodeSpec liftedBytecode,
            DensityFieldEvaluator barrierNoise,
            DensityFieldEvaluator floodednessNoise,
            DensityFieldEvaluator spreadNoise,
            DensityFieldEvaluator lavaNoise,
            DensityFieldEvaluator erosionNoise,
            DensityFieldEvaluator depthNoise,
            PreliminarySurfaceSampler prelimSampler,
            PositionalCellJitter customJitter
    ) {
        Objects.requireNonNull(spec, "spec");
        BytecodeSsaLifter.LiftedAquiferBytecodeSpec aq =
                liftedBytecode != null ? liftedBytecode : BytecodeSsaLifter.LiftedAquiferBytecodeSpec.MOJANG_DEFAULTS;
        final int xSp = Math.max(1, aq.xSpacing());
        final int ySp = Math.max(1, aq.ySpacing());
        final int zSp = Math.max(1, aq.zSpacing());
        final int xRg = Math.max(1, aq.xRange());
        final int yRg = Math.max(1, aq.yRange());
        final int zRg = Math.max(1, aq.zRange());
        final float simScale = (float) aq.similarityScale();
        final int spreadBx = Math.max(1, aq.spreadBucketX());
        final int spreadBy = Math.max(1, aq.spreadBucketY());
        final int spreadBz = Math.max(1, aq.spreadBucketZ());
        final int lavaMaxLvl = aq.lavaMaxFluidLevel();
        final float lavaThresh = (float) aq.lavaNoiseThreshold();
        final int seaLevel = spec.seaLevel();
        final int deepLavaY = spec.deepLavaFloorY();
        final int defaultFluid = spec.defaultFluidSlot();
        final int lavaSlot = spec.lavaFluidSlot();
        final int minGridY = Math.floorDiv(spec.noiseMinY(), ySp) - 1;
        final int maxGridY = Math.floorDiv(spec.noiseMinY() + spec.noiseHeight(), ySp) + 1;
        final int gridSizeY = Math.max(1, maxGridY - minGridY + 1);
        final long aqSeed = worldSeed ^ 0x6A09E667F3BCC908L;

        final java.util.concurrent.atomic.AtomicLongArray sharedPrelimTable =
                new java.util.concurrent.atomic.AtomicLongArray(16384);
        final int aquiferColCacheMask = 4095;
        final java.util.concurrent.atomic.AtomicReferenceArray<AquiferColumnCacheEntry> sharedColCache =
                new java.util.concurrent.atomic.AtomicReferenceArray<>(aquiferColCacheMask + 1);

        return (chunkX, chunkZ) -> {
            int chunkBaseBx = chunkX << 4;
            int chunkBaseBz = chunkZ << 4;
            int minGridX = Math.floorDiv(chunkBaseBx - 5, xSp) - 1;
            int maxGridX = Math.floorDiv(chunkBaseBx + 10, xSp) + 1;
            int gridSizeX = Math.max(1, maxGridX - minGridX + 1);
            int minGridZ = Math.floorDiv(chunkBaseBz - 5, zSp) - 1;
            int maxGridZ = Math.floorDiv(chunkBaseBz + 10, zSp) + 1;
            int gridSizeZ = Math.max(1, maxGridZ - minGridZ + 1);

            int maxPrelimInGrid = seaLevel;
            if (prelimSampler != null) {
                for (int gz = minGridZ; gz <= maxGridZ; gz++) {
                    int pz = gz * zSp + (zRg >> 1);
                    for (int gx = minGridX; gx <= maxGridX; gx++) {
                        int px = gx * xSp + (xRg >> 1);
                        int py = lookupSharedPrelimY(prelimSampler, sharedPrelimTable, px, pz);
                        if (py > maxPrelimInGrid) {
                            maxPrelimInGrid = py;
                        }
                    }
                }
            }
            int skipAboveY = Math.floorDiv((maxPrelimInGrid + 8) + 12, ySp) * ySp + (ySp - 1);

            int totalCells = gridSizeX * gridSizeY * gridSizeZ;
            int[] packed = new int[totalCells * 5];
            int[] xyzScratch = new int[3];

            for (int gz = minGridZ; gz <= maxGridZ; gz++) {
                int zIdx = gz - minGridZ;
                for (int gx = minGridX; gx <= maxGridX; gx++) {
                    int xIdx = gx - minGridX;
                    int cacheSlot = ((gx * 0x9E37) ^ (gz * 0x85EB)) & aquiferColCacheMask;
                    AquiferColumnCacheEntry cached = sharedColCache.getOpaque(cacheSlot);
                    int[] colPacked;
                    if (cached != null && cached.gx == gx && cached.gz == gz) {
                        colPacked = cached.packed5;
                    } else {
                        colPacked = new int[gridSizeY * 5];
                        for (int gy = minGridY; gy <= maxGridY; gy++) {
                            int yIdx = gy - minGridY;
                            int colBase = yIdx * 5;
                            if (customJitter != null) {
                                customJitter.computeCellCenter(gx, gy, gz, xSp, ySp, zSp, xRg, yRg, zRg, xyzScratch);
                            } else {
                                defaultDeterministicAquiferJitter(aqSeed, gx, gy, gz, xSp, ySp, zSp, xRg, yRg, zRg, xyzScratch);
                            }
                            int cx = xyzScratch[0];
                            int cy = xyzScratch[1];
                            int cz = xyzScratch[2];
                            colPacked[colBase] = cx;
                            colPacked[colBase + 1] = cy;
                            colPacked[colBase + 2] = cz;

                            // Global FluidPicker baseline (lava <= deepLavaFloorY, else defaultFluid @ seaLevel)
                            int globalLvl = (lavaSlot > 0 && cy < deepLavaY) ? deepLavaY : seaLevel;
                            int globalSlot = (lavaSlot > 0 && cy < deepLavaY) ? lavaSlot : defaultFluid;
                            if (globalSlot <= 0) {
                                colPacked[colBase + 3] = AQUIFER_WAY_BELOW_MIN_Y;
                                colPacked[colBase + 4] = 0;
                                continue;
                            }

                            if (prelimSampler == null && floodednessNoise == null) {
                                colPacked[colBase + 3] = globalLvl;
                                colPacked[colBase + 4] = globalSlot;
                                continue;
                            }

                            int minAdjSurf = Integer.MAX_VALUE;
                            boolean nearFloodedSurface = false;
                            boolean decidedEarly = false;
                            int finalLvl = globalLvl;
                            int finalSlot = globalSlot;

                            if (prelimSampler != null) {
                                for (int oi = 0; oi < MOJANG_SURFACE_OFFSETS_CHUNKS.length; oi++) {
                                    int offCx = MOJANG_SURFACE_OFFSETS_CHUNKS[oi][0];
                                    int offCz = MOJANG_SURFACE_OFFSETS_CHUNKS[oi][1];
                                    int sx = cx + (offCx << 4);
                                    int sz = cz + (offCz << 4);
                                    int adjSurf = lookupSharedPrelimY(prelimSampler, sharedPrelimTable, sx, sz)
                                            + 8;
                                    boolean isOrigin = (offCx == 0 && offCz == 0);
                                    if (isOrigin && (cy - 12) > adjSurf) {
                                        decidedEarly = true;
                                        break;
                                    }
                                    boolean aboveAdj = (cy + 12) > adjSurf;
                                    if (aboveAdj || isOrigin) {
                                        int surfLvl = (lavaSlot > 0 && adjSurf < deepLavaY) ? deepLavaY : seaLevel;
                                        int surfSlot = (lavaSlot > 0 && adjSurf < deepLavaY) ? lavaSlot : defaultFluid;
                                        if (surfSlot > 0 && adjSurf <= surfLvl) {
                                            if (isOrigin) {
                                                nearFloodedSurface = true;
                                            }
                                            if (aboveAdj) {
                                                finalLvl = surfLvl;
                                                finalSlot = surfSlot;
                                                decidedEarly = true;
                                                break;
                                            }
                                        }
                                    }
                                    if (adjSurf < minAdjSurf) {
                                        minAdjSurf = adjSurf;
                                    }
                                }
                            } else {
                                minAdjSurf = seaLevel + 16;
                            }

                            if (!decidedEarly) {
                                boolean deepDark = erosionNoise != null
                                        && depthNoise != null
                                        && erosionNoise.sample(cx, cy, cz) < -0.225f
                                        && depthNoise.sample(cx, cy, cz) > 0.9f;
                                float floodThresh;
                                float seaThresh;
                                if (deepDark) {
                                    floodThresh = 1.0f;
                                    seaThresh = 1.0f;
                                } else {
                                    float dist = (float) (minAdjSurf + 8 - cy);
                                    float factor = nearFloodedSurface
                                            ? Math.max(0.0f, Math.min(1.0f, 1.0f - (dist / 64.0f)))
                                            : 0.0f;
                                    seaThresh = -0.3f + (1.0f - factor) * 1.1f; // map(factor, 1->0, -0.3->0.8)
                                    floodThresh = -0.8f + (1.0f - factor) * 1.2f; // map(factor, 1->0, -0.8->0.4)
                                }
                                float floodVal = floodednessNoise != null
                                        ? Math.max(-1.0f, Math.min(1.0f, floodednessNoise.sample(cx, cy, cz)))
                                        : -1.0f;
                                if (floodVal > floodThresh) {
                                    if (floodVal > seaThresh) {
                                        finalLvl = globalLvl;
                                    } else {
                                        int sbx = Math.floorDiv(cx, spreadBx);
                                        int sby = Math.floorDiv(cy, spreadBy);
                                        int sbz = Math.floorDiv(cz, spreadBz);
                                        int baseLvl = sby * spreadBy + (spreadBy >> 1);
                                        float sp = spreadNoise != null ? (spreadNoise.sample(sbx, sby, sbz) * 10.0f) : 0.0f;
                                        int quant = Math.floorDiv((int) Math.floor(sp), 3) * 3;
                                        finalLvl = Math.min(minAdjSurf, baseLvl + quant);
                                    }
                                    finalSlot = globalSlot;
                                    if (finalLvl <= lavaMaxLvl
                                            && finalLvl != AQUIFER_WAY_BELOW_MIN_Y
                                            && lavaSlot > 0
                                            && finalSlot != lavaSlot
                                            && lavaNoise != null) {
                                        int lx64 = Math.floorDiv(cx, 64);
                                        int ly40 = Math.floorDiv(cy, spreadBy);
                                        int lz64 = Math.floorDiv(cz, 64);
                                        if (Math.abs(lavaNoise.sample(lx64, ly40, lz64)) > lavaThresh) {
                                            finalSlot = lavaSlot;
                                        }
                                    }
                                } else {
                                    finalLvl = AQUIFER_WAY_BELOW_MIN_Y;
                                    finalSlot = 0;
                                }
                            }

                            colPacked[colBase + 3] = finalLvl;
                            colPacked[colBase + 4] = finalSlot;
                        }
                        sharedColCache.setOpaque(cacheSlot, new AquiferColumnCacheEntry(gx, gz, colPacked));
                    }
                    for (int yIdx = 0; yIdx < gridSizeY; yIdx++) {
                        int colBase = yIdx * 5;
                        int cellBase = ((yIdx * gridSizeZ + zIdx) * gridSizeX + xIdx) * 5;
                        packed[cellBase] = colPacked[colBase];
                        packed[cellBase + 1] = colPacked[colBase + 1];
                        packed[cellBase + 2] = colPacked[colBase + 2];
                        packed[cellBase + 3] = colPacked[colBase + 3];
                        packed[cellBase + 4] = colPacked[colBase + 4];
                    }
                }
            }

            return new AquiferCellSnapshot(
                    minGridX,
                    minGridY,
                    minGridZ,
                    gridSizeX,
                    gridSizeY,
                    gridSizeZ,
                    skipAboveY,
                    xSp,
                    ySp,
                    zSp,
                    simScale,
                    packed);
        };
    }

    private record AquiferColumnCacheEntry(int gx, int gz, int[] packed5) {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof AquiferColumnCacheEntry other)) {
                return false;
            }
            return this.gx == other.gx
                    && this.gz == other.gz
                    && java.util.Arrays.equals(this.packed5, other.packed5);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.gx;
            result = 31 * result + this.gz;
            result = 31 * result + java.util.Arrays.hashCode(this.packed5);
            return result;
        }

        @Override
        public String toString() {
            return "AquiferColumnCacheEntry[gx=" + this.gx
                    + ", gz=" + this.gz
                    + ", packed5=" + java.util.Arrays.toString(this.packed5)
                    + "]";
        }
    }

    private static int lookupSharedPrelimY(
            PreliminarySurfaceSampler prelimSampler,
            java.util.concurrent.atomic.AtomicLongArray sharedPrelimTable,
            int bx,
            int bz
    ) {
        int qx = bx >> 2;
        int qz = bz >> 2;
        int ux = qx + (1 << 20);
        int uz = qz + (1 << 20);
        if (((ux | uz) & ~0x1F_FFFF) == 0) {
            int hiX = ux >>> 7;
            int hiZ = uz >>> 7;
            int mix14 = ((hiX * 0x9E37) ^ (hiZ * 0x85EB)) & 0x3FFF;
            int slot = (((uz & 0x7F) << 7) | (ux & 0x7F)) ^ mix14;
            int tag32 = 1 | (hiX << 1) | (hiZ << 15);
            long entry = sharedPrelimTable.getOpaque(slot);
            if ((int) (entry >>> 32) == tag32) {
                return (int) entry;
            }
            int val = prelimSampler.samplePreliminarySurfaceY(qx << 2, qz << 2);
            sharedPrelimTable.setOpaque(slot, (((long) tag32) << 32) | (val & 0xFFFF_FFFFL));
            return val;
        }
        return prelimSampler.samplePreliminarySurfaceY(qx << 2, qz << 2);
    }

    private static int lookupCachedPrelimY(
            PreliminarySurfaceSampler sampler,
            int[] cache,
            int qOriginX,
            int qOriginZ,
            int qSpanX,
            int qSpanZ,
            int bx,
            int bz
    ) {
        int qx = (bx >> 2) - qOriginX;
        int qz = (bz >> 2) - qOriginZ;
        if (qx >= 0 && qx < qSpanX && qz >= 0 && qz < qSpanZ) {
            int idx = qz * qSpanX + qx;
            int cached = cache[idx];
            if (cached != Integer.MIN_VALUE) {
                return cached;
            }
            int val = sampler.samplePreliminarySurfaceY(bx & ~3, bz & ~3);
            cache[idx] = val;
            return val;
        }
        return sampler.samplePreliminarySurfaceY(bx, bz);
    }

    private static void defaultDeterministicAquiferJitter(
            long seed,
            int gx,
            int gy,
            int gz,
            int xSp,
            int ySp,
            int zSp,
            int xRg,
            int yRg,
            int zRg,
            int[] outXyz
    ) {
        long h = seed;
        h ^= (long) gx * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 27) * 0xBF58476D1CE4E5B9L;
        h ^= (long) gy * 0x94D049BB133111EBL;
        h = Long.rotateLeft(h, 31) * 0x9E3779B97F4A7C15L;
        h ^= (long) gz * 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 30);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 27);
        h *= 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        outXyz[0] = gx * xSp + (int) Long.remainderUnsigned(h & 0xFFFF_FFFFL, xRg);
        outXyz[1] = gy * ySp + (int) Long.remainderUnsigned((h >>> 16) & 0xFFFF_FFFFL, yRg);
        outXyz[2] = gz * zSp + (int) Long.remainderUnsigned((h >>> 32) & 0xFFFF_FFFFL, zRg);
    }

    /**
     * Evaluates Mojang's lifted {@code Aquifer$NoiseBasedAquifer#calculatePressure} equation in FP32.
     */
    public static float evaluateAquiferPressureFp32(
            int wy,
            float barVal,
            int lvlA,
            int slotA,
            int lvlB,
            int slotB,
            int defaultFluidSlot,
            int lavaFluidSlot
    ) {
        int fA = (slotA > 0 && wy <= lvlA) ? slotA : 0;
        int fB = (slotB > 0 && wy <= lvlB) ? slotB : 0;
        if (lavaFluidSlot > 0
                && defaultFluidSlot > 0
                && ((fA == lavaFluidSlot && fB == defaultFluidSlot)
                        || (fA == defaultFluidSlot && fB == lavaFluidSlot))) {
            return 2.0f;
        }
        int diff = Math.abs(lvlA - lvlB);
        if (diff == 0) {
            return 0.0f;
        }
        float avg = 0.5f * (float) (lvlA + lvlB);
        float relY = (float) wy + 0.5f - avg;
        float halfDiff = (float) diff * 0.5f;
        float distFromEdge = halfDiff - Math.abs(relY);
        float q;
        if (relY > 0.0f) {
            q = distFromEdge > 0.0f ? (distFromEdge / 1.5f) : (distFromEdge / 2.5f);
        } else {
            float shifted = 3.0f + distFromEdge;
            q = shifted > 0.0f ? (shifted / 3.0f) : (shifted / 10.0f);
        }
        float b = (q >= -2.0f && q <= 2.0f) ? barVal : 0.0f;
        return 2.0f * (b + q);
    }

    /**
     * Evaluates Mojang's lifted {@code Aquifer$NoiseBasedAquifer#calculatePressure} equation in
     * full IEEE-754 {@code FP64} precision.
     */
    public static double evaluateAquiferPressureFp64(
            int wy,
            double barVal,
            int lvlA,
            int slotA,
            int lvlB,
            int slotB,
            int defaultFluidSlot,
            int lavaFluidSlot
    ) {
        int fA = (slotA > 0 && wy <= lvlA) ? slotA : 0;
        int fB = (slotB > 0 && wy <= lvlB) ? slotB : 0;
        if (lavaFluidSlot > 0
                && defaultFluidSlot > 0
                && ((fA == lavaFluidSlot && fB == defaultFluidSlot)
                        || (fA == defaultFluidSlot && fB == lavaFluidSlot))) {
            return 2.0;
        }
        int diff = Math.abs(lvlA - lvlB);
        if (diff == 0) {
            return 0.0;
        }
        double avg = 0.5 * (double) (lvlA + lvlB);
        double relY = (double) wy + 0.5 - avg;
        double halfDiff = (double) diff * 0.5;
        double distFromEdge = halfDiff - Math.abs(relY);
        double q;
        if (relY > 0.0) {
            q = distFromEdge > 0.0 ? (distFromEdge / 1.5) : (distFromEdge / 2.5);
        } else {
            double shifted = 3.0 + distFromEdge;
            q = shifted > 0.0 ? (shifted / 3.0) : (shifted / 10.0);
        }
        double b = (q >= -2.0 && q <= 2.0) ? barVal : 0.0;
        return 2.0 * (b + q);
    }

    /**
     * Evaluates {@code Aquifer$NoiseBasedAquifer#computeSubstance} in exact IEEE-754 {@code FP64}
     * precision for {@link WorldgenKernelSwapper.WorldgenFidelityPolicy#VANILLA_EXACT}.
     */
    public static int evaluateNoiseBasedAquiferVoxelExactFp64(
            AquiferCellSnapshot snap,
            int bx,
            int wy,
            int bz,
            double dens,
            DensityFieldEvaluator barrierEval,
            int defaultBlockSlot,
            int defaultFluidSlot,
            int lavaFluidSlot,
            int seaLevel,
            int deepLavaFloorY
    ) {
        if (wy > snap.skipSamplingAboveY()) {
            if (lavaFluidSlot > 0 && wy <= deepLavaFloorY) {
                return lavaFluidSlot;
            }
            return (defaultFluidSlot > 0 && wy <= seaLevel) ? defaultFluidSlot : 0;
        }
        if (lavaFluidSlot > 0 && wy <= deepLavaFloorY) {
            return lavaFluidSlot;
        }
        int xSp = snap.xSpacing();
        int ySp = snap.ySpacing();
        int zSp = snap.zSpacing();
        int baseGx = Math.floorDiv(bx - 5, xSp) - 1;
        int baseGy = Math.floorDiv(wy + 1, ySp) - 1;
        int baseGz = Math.floorDiv(bz - 5, zSp) - 1;

        int i1 = 0;
        int i2 = 0;
        int i3 = 0;
        int d1 = Integer.MAX_VALUE;
        int d2 = Integer.MAX_VALUE;
        int d3 = Integer.MAX_VALUE;

        int minGx = snap.minGridX();
        int minGy = snap.minGridY();
        int minGz = snap.minGridZ();
        int sizeX = snap.gridSizeX();
        int sizeY = snap.gridSizeY();
        int sizeZ = snap.gridSizeZ();
        int[] cells = snap.packedCells;

        int rx0 = Math.max(0, Math.min(sizeX - 1, baseGx - minGx));
        int rx1 = Math.max(0, Math.min(sizeX - 1, (baseGx + 1) - minGx));
        int rx2 = Math.max(0, Math.min(sizeX - 1, (baseGx + 2) - minGx));
        int ry0 = Math.max(0, Math.min(sizeY - 1, baseGy - minGy));
        int ry1 = Math.max(0, Math.min(sizeY - 1, (baseGy + 1) - minGy));
        int rz0 = Math.max(0, Math.min(sizeZ - 1, baseGz - minGz));
        int rz1 = Math.max(0, Math.min(sizeZ - 1, (baseGz + 1) - minGz));
        int rz2 = Math.max(0, Math.min(sizeZ - 1, (baseGz + 2) - minGz));

        int[] rxs = {rx0, rx1, rx2};
        int[] rys = {ry0, ry1};
        int[] rzs = {rz0, rz1, rz2};
        for (int oy = 0; oy <= 1; oy++) {
            int yBase = rys[oy] * sizeZ;
            for (int oz = 0; oz <= 2; oz++) {
                int zBase = (yBase + rzs[oz]) * sizeX;
                for (int ox = 0; ox <= 2; ox++) {
                    int idx = zBase + rxs[ox];
                    int cb = idx * 5;
                    int dx = cells[cb] - bx;
                    int dy = cells[cb + 1] - wy;
                    int dz = cells[cb + 2] - bz;
                    int dist2 = dx * dx + dy * dy + dz * dz;
                    if (dist2 <= d1) {
                        d3 = d2;
                        i3 = i2;
                        d2 = d1;
                        i2 = i1;
                        d1 = dist2;
                        i1 = idx;
                    } else if (dist2 <= d2) {
                        d3 = d2;
                        i3 = i2;
                        d2 = dist2;
                        i2 = idx;
                    } else if (dist2 <= d3) {
                        d3 = dist2;
                        i3 = idx;
                    }
                }
            }
        }

        int cb1 = i1 * 5;
        int lvl1 = cells[cb1 + 3];
        int slot1 = cells[cb1 + 4];
        int f1AtWy = (slot1 > 0 && wy <= lvl1) ? slot1 : 0;
        double simScale = snap.similarityScale();
        double sim12 = 1.0 - ((double) (d2 - d1) / simScale);
        if (f1AtWy == 0 && sim12 <= 0.0) {
            return 0;
        }
        if (f1AtWy == defaultFluidSlot && defaultFluidSlot > 0 && lavaFluidSlot > 0 && (wy - 1) <= deepLavaFloorY) {
            return lavaFluidSlot;
        }

        double barVal = barrierEval != null ? barrierEval.sampleFp64(bx, wy, bz) : 0.0;
        int cb2 = i2 * 5;
        int lvl2 = cells[cb2 + 3];
        int slot2 = cells[cb2 + 4];
        double p12 = evaluateAquiferPressureFp64(
                wy, barVal, lvl1, slot1, lvl2, slot2, defaultFluidSlot, lavaFluidSlot);
        if (dens + sim12 * p12 > 0.0) {
            return defaultBlockSlot;
        }

        int cb3 = i3 * 5;
        int lvl3 = cells[cb3 + 3];
        int slot3 = cells[cb3 + 4];
        double sim13 = 1.0 - ((double) (d3 - d1) / simScale);
        if (sim13 > 0.0) {
            double p13 = evaluateAquiferPressureFp64(
                    wy, barVal, lvl1, slot1, lvl3, slot3, defaultFluidSlot, lavaFluidSlot);
            if (dens + sim12 * sim13 * p13 > 0.0) {
                return defaultBlockSlot;
            }
        }
        double sim23 = 1.0 - ((double) (d3 - d2) / simScale);
        if (sim12 > 0.0 && sim23 > 0.0) {
            double p23 = evaluateAquiferPressureFp64(
                    wy, barVal, lvl2, slot2, lvl3, slot3, defaultFluidSlot, lavaFluidSlot);
            if (dens + sim12 * sim23 * p23 > 0.0) {
                return defaultBlockSlot;
            }
        }
        return f1AtWy;
    }

    /**
     * Evaluates Mojang's lifted {@code Aquifer$NoiseBasedAquifer#computeSubstance} 18-cell 3D Voronoi
     * substance & curved pressure-barrier equation on a {@code dens <= 0.0f} voxel in FP32.
     */
    public static int evaluateNoiseBasedAquiferVoxel(
            AquiferCellSnapshot snap,
            int bx,
            int wy,
            int bz,
            float dens,
            DensityFieldEvaluator barrierEval,
            int defaultBlockSlot,
            int defaultFluidSlot,
            int lavaFluidSlot,
            int seaLevel,
            int deepLavaFloorY
    ) {
        if (wy > snap.skipSamplingAboveY()) {
            if (lavaFluidSlot > 0 && wy <= deepLavaFloorY) {
                return lavaFluidSlot;
            }
            return (defaultFluidSlot > 0 && wy <= seaLevel) ? defaultFluidSlot : 0;
        }
        if (lavaFluidSlot > 0 && wy <= deepLavaFloorY) {
            return lavaFluidSlot;
        }
        int xSp = snap.xSpacing();
        int ySp = snap.ySpacing();
        int zSp = snap.zSpacing();
        int baseGx = Math.floorDiv(bx - 5, xSp) - 1;
        int baseGy = Math.floorDiv(wy + 1, ySp) - 1;
        int baseGz = Math.floorDiv(bz - 5, zSp) - 1;

        int i1 = 0;
        int i2 = 0;
        int i3 = 0;
        int d1 = Integer.MAX_VALUE;
        int d2 = Integer.MAX_VALUE;
        int d3 = Integer.MAX_VALUE;

        int minGx = snap.minGridX();
        int minGy = snap.minGridY();
        int minGz = snap.minGridZ();
        int sizeX = snap.gridSizeX();
        int sizeY = snap.gridSizeY();
        int sizeZ = snap.gridSizeZ();
        int[] cells = snap.packedCells;

        int rx0 = Math.max(0, Math.min(sizeX - 1, baseGx - minGx));
        int ry0 = Math.max(0, Math.min(sizeY - 1, baseGy - minGy));
        int rz0 = Math.max(0, Math.min(sizeZ - 1, baseGz - minGz));
        int firstIdx = (ry0 * sizeZ + rz0) * sizeX + rx0;
        if (firstIdx >= 0 && firstIdx < snap.uniform18Slot.length) {
            int uSlot = snap.uniform18Slot[firstIdx];
            if (uSlot >= 0) {
                int uLvl = snap.uniform18Lvl[firstIdx];
                int fUni = (uSlot > 0 && wy <= uLvl) ? uSlot : 0;
                if (fUni == defaultFluidSlot
                        && defaultFluidSlot > 0
                        && lavaFluidSlot > 0
                        && (wy - 1) <= deepLavaFloorY) {
                    return lavaFluidSlot;
                }
                return fUni;
            }
        }
        int rx1 = Math.max(0, Math.min(sizeX - 1, (baseGx + 1) - minGx));
        int rx2 = Math.max(0, Math.min(sizeX - 1, (baseGx + 2) - minGx));
        int ry1 = Math.max(0, Math.min(sizeY - 1, (baseGy + 1) - minGy));
        int rz1 = Math.max(0, Math.min(sizeZ - 1, (baseGz + 1) - minGz));
        int rz2 = Math.max(0, Math.min(sizeZ - 1, (baseGz + 2) - minGz));

        int[] rxs = {rx0, rx1, rx2};
        int[] rys = {ry0, ry1};
        int[] rzs = {rz0, rz1, rz2};
        for (int oy = 0; oy <= 1; oy++) {
            int yBase = rys[oy] * sizeZ;
            for (int oz = 0; oz <= 2; oz++) {
                int zBase = (yBase + rzs[oz]) * sizeX;
                for (int ox = 0; ox <= 2; ox++) {
                    int idx = zBase + rxs[ox];
                    int cb = idx * 5;
                    int dx = cells[cb] - bx;
                    int dy = cells[cb + 1] - wy;
                    int dz = cells[cb + 2] - bz;
                    int dist2 = dx * dx + dy * dy + dz * dz;
                    if (dist2 <= d1) {
                        d3 = d2;
                        i3 = i2;
                        d2 = d1;
                        i2 = i1;
                        d1 = dist2;
                        i1 = idx;
                    } else if (dist2 <= d2) {
                        d3 = d2;
                        i3 = i2;
                        d2 = dist2;
                        i2 = idx;
                    } else if (dist2 <= d3) {
                        d3 = dist2;
                        i3 = idx;
                    }
                }
            }
        }

        int cb1 = i1 * 5;
        int lvl1 = cells[cb1 + 3];
        int slot1 = cells[cb1 + 4];
        int f1AtWy = (slot1 > 0 && wy <= lvl1) ? slot1 : 0;
        float simScale = snap.similarityScale();
        float sim12 = 1.0f - ((float) (d2 - d1) / simScale);
        if (f1AtWy == 0 && sim12 <= 0.0f) {
            return 0;
        }
        if (f1AtWy == defaultFluidSlot && defaultFluidSlot > 0 && lavaFluidSlot > 0 && (wy - 1) <= deepLavaFloorY) {
            return lavaFluidSlot;
        }

        float barVal = barrierEval != null ? barrierEval.sample(bx, wy, bz) : 0.0f;
        int cb2 = i2 * 5;
        int lvl2 = cells[cb2 + 3];
        int slot2 = cells[cb2 + 4];
        float p12 = evaluateAquiferPressureFp32(
                wy, barVal, lvl1, slot1, lvl2, slot2, defaultFluidSlot, lavaFluidSlot);
        if (dens + sim12 * p12 > 0.0f) {
            return defaultBlockSlot;
        }

        int cb3 = i3 * 5;
        int lvl3 = cells[cb3 + 3];
        int slot3 = cells[cb3 + 4];
        float sim13 = 1.0f - ((float) (d3 - d1) / simScale);
        if (sim13 > 0.0f) {
            float p13 = evaluateAquiferPressureFp32(
                    wy, barVal, lvl1, slot1, lvl3, slot3, defaultFluidSlot, lavaFluidSlot);
            if (dens + sim12 * sim13 * p13 > 0.0f) {
                return defaultBlockSlot;
            }
        }
        float sim23 = 1.0f - ((float) (d3 - d2) / simScale);
        if (sim12 > 0.0f && sim23 > 0.0f) {
            float p23 = evaluateAquiferPressureFp32(
                    wy, barVal, lvl2, slot2, lvl3, slot3, defaultFluidSlot, lavaFluidSlot);
            if (dens + sim12 * sim23 * p23 > 0.0f) {
                return defaultBlockSlot;
            }
        }
        return f1AtWy;
    }

    /**
     * Compact in-memory {@code .lca}-aligned chunk representation holding uniform section descriptors,
     * 64-bit {@code 01:H_3} active octant masks, packed {@code 01:H_3} Hilbert runs (matching
     * {@code Slot4k} layout for 1-to-1 OpenCL translation), lazy sparse section voxel arrays, and
     * incrementally resolved column heightmaps.
     */
    public static final class LcaCapabilityChunkFrame {

        private final DimensionCapabilitySpec spec;
        private final WorldgenKernelSwapper.WorldgenFidelityPolicy fidelityPolicy;
        private final int[] uniformSlotIds;
        private final int[] dominantSlotIds;
        private final long[] octantMasks64;
        private final int[][] hilbertRuns;
        private final int[][] sparseSections;
        private final int[] quartBiomes;
        private final int[] topSurfaceY;
        private final int[] oceanFloorY;
        private final int[] worldSurfaceY;
        private final int[] motionBlockingNoLeavesY;
        private boolean hasDeepLava;
        private int[] postProcessingPacked = EMPTY_INT_ARRAY;
        private int postProcessingCount;

        LcaCapabilityChunkFrame(
                DimensionCapabilitySpec spec,
                int[] quartBiomes,
                int[] topSurfaceY
        ) {
            this(spec, quartBiomes, topSurfaceY, WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE);
        }

        LcaCapabilityChunkFrame(
                DimensionCapabilitySpec spec,
                int[] quartBiomes,
                int[] topSurfaceY,
                WorldgenKernelSwapper.WorldgenFidelityPolicy fidelityPolicy
        ) {
            this.spec = Objects.requireNonNull(spec, "spec");
            this.fidelityPolicy = fidelityPolicy == null
                    ? WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE
                    : fidelityPolicy;
            int secCount = Math.max(1, spec.chunkSections());
            this.uniformSlotIds = new int[secCount];
            this.dominantSlotIds = new int[secCount];
            this.octantMasks64 = new long[secCount];
            this.hilbertRuns = new int[secCount][];
            this.sparseSections = new int[secCount][];
            Arrays.fill(this.hilbertRuns, EMPTY_INT_ARRAY);
            this.quartBiomes = quartBiomes != null ? quartBiomes : new int[secCount << 6];
            this.topSurfaceY = topSurfaceY != null ? topSurfaceY : new int[256];
            this.oceanFloorY = new int[256];
            this.worldSurfaceY = new int[256];
            this.motionBlockingNoLeavesY = new int[256];
            Arrays.fill(this.oceanFloorY, spec.chunkMinY());
            Arrays.fill(this.worldSurfaceY, spec.chunkMinY());
            Arrays.fill(this.motionBlockingNoLeavesY, spec.chunkMinY());
        }

        public static int packHilbertRun(int startH3, int runLen, int slotId) {
            return (startH3 & 0xFFF) | (((runLen - 1) & 0xFF) << 12) | ((slotId & 0xFFF) << 20);
        }

        public static int unpackRunStartH3(int packedRun) {
            return packedRun & 0xFFF;
        }

        public static int unpackRunLength(int packedRun) {
            return ((packedRun >>> 12) & 0xFF) + 1;
        }

        public static int unpackRunSlotId(int packedRun) {
            return (packedRun >>> 20) & 0xFFF;
        }

        public DimensionCapabilitySpec spec() {
            return spec;
        }

        public WorldgenKernelSwapper.WorldgenFidelityPolicy fidelityPolicy() {
            return fidelityPolicy;
        }

        public void recordPostProcessVoxel(int sectionIdx, int pos12) {
            int packed = ((sectionIdx & 0xFF) << 12) | (pos12 & 0xFFF);
            if (postProcessingCount >= postProcessingPacked.length) {
                int nextCap = postProcessingPacked.length == 0 ? 16 : postProcessingPacked.length << 1;
                postProcessingPacked = Arrays.copyOf(postProcessingPacked, nextCap);
            }
            postProcessingPacked[postProcessingCount++] = packed;
        }

        public int postProcessVoxelCount() {
            return postProcessingCount;
        }

        public int postProcessVoxelAt(int index) {
            return postProcessingPacked[index];
        }

        public int sectionCount() {
            return uniformSlotIds.length;
        }

        public boolean isSectionUniform(int sectionIdx) {
            return uniformSlotIds[sectionIdx] >= 0;
        }

        public int uniformSlotId(int sectionIdx) {
            return uniformSlotIds[sectionIdx];
        }

        public int[] uniformSlotIds() {
            return uniformSlotIds;
        }

        public int[] dominantSlotIds() {
            return dominantSlotIds;
        }

        public long[] octantMasks64() {
            return octantMasks64;
        }

        public int[][] hilbertRuns() {
            return hilbertRuns;
        }

        public int[] sectionBufferOrNull(int sectionIdx) {
            return sparseSections[sectionIdx];
        }

        public int[] quartBiomes() {
            return quartBiomes;
        }

        public int[] topSurfaceY() {
            return topSurfaceY;
        }

        public int[] oceanFloorY() {
            return oceanFloorY;
        }

        public int[] worldSurfaceY() {
            return worldSurfaceY;
        }

        public int[] motionBlockingNoLeavesY() {
            return motionBlockingNoLeavesY;
        }

        public boolean hasDeepLava() {
            return hasDeepLava;
        }

        public int getVoxel(int sectionIdx, int yzxIdx) {
            int u = uniformSlotIds[sectionIdx];
            return u >= 0 ? u : sparseSections[sectionIdx][yzxIdx];
        }

        public void setVoxel(int sectionIdx, int yzxIdx, int slotId) {
            int u = uniformSlotIds[sectionIdx];
            if (u >= 0) {
                if (slotId == u) {
                    return;
                }
                int[] buf = new int[4096];
                if (u != 0) {
                    Arrays.fill(buf, u);
                }
                buf[yzxIdx] = slotId;
                sparseSections[sectionIdx] = buf;
                uniformSlotIds[sectionIdx] = -1;
                dominantSlotIds[sectionIdx] = u;
            } else {
                sparseSections[sectionIdx][yzxIdx] = slotId;
            }
        }

        void setSectionUniform(int sectionIdx, int slotId) {
            sparseSections[sectionIdx] = null;
            uniformSlotIds[sectionIdx] = slotId;
            dominantSlotIds[sectionIdx] = slotId;
            octantMasks64[sectionIdx] = 0L;
            hilbertRuns[sectionIdx] = EMPTY_INT_ARRAY;
        }

        public int[][] materializeSections() {
            int secCount = uniformSlotIds.length;
            int[][] out = new int[secCount][4096];
            for (int s = 0; s < secCount; s++) {
                int u = uniformSlotIds[s];
                if (u >= 0) {
                    if (u != 0) {
                        Arrays.fill(out[s], u);
                    }
                } else {
                    System.arraycopy(sparseSections[s], 0, out[s], 0, 4096);
                }
            }
            return out;
        }

        public static LcaCapabilityChunkFrame fromFlatBufferWithUniformHints(
                DimensionCapabilitySpec spec,
                int[] flatVoxels,
                int chunkVoxelBase,
                int[] uniformSlotHints,
                int uniformHintBase,
                int[] topSurfaceY,
                int[] quartBiomes,
                int chunkX,
                int chunkZ,
                long worldSeed,
                boolean applyCarvers,
                boolean applyFeatures,
                boolean[] leafSlotMask
        ) {
            return fromFlatBufferWithUniformHints(
                    spec,
                    flatVoxels,
                    chunkVoxelBase,
                    uniformSlotHints,
                    uniformHintBase,
                    topSurfaceY,
                    quartBiomes,
                    chunkX,
                    chunkZ,
                    worldSeed,
                    applyCarvers,
                    applyFeatures,
                    leafSlotMask,
                    null,
                    null);
        }

        public static LcaCapabilityChunkFrame fromFlatBufferWithUniformHints(
                DimensionCapabilitySpec spec,
                int[] flatVoxels,
                int chunkVoxelBase,
                int[] uniformSlotHints,
                int uniformHintBase,
                int[] topSurfaceY,
                int[] quartBiomes,
                int chunkX,
                int chunkZ,
                long worldSeed,
                boolean applyCarvers,
                boolean applyFeatures,
                boolean[] leafSlotMask,
                DensityFieldEvaluator borderDensityEval,
                QuartBiomeOrdinalResolver borderBiomeSampler
        ) {
            return fromFlatBufferWithUniformHints(
                    spec,
                    flatVoxels,
                    chunkVoxelBase,
                    uniformSlotHints,
                    uniformHintBase,
                    topSurfaceY,
                    quartBiomes,
                    chunkX,
                    chunkZ,
                    worldSeed,
                    applyCarvers,
                    applyFeatures,
                    leafSlotMask,
                    borderDensityEval,
                    borderBiomeSampler,
                    null);
        }

        public static LcaCapabilityChunkFrame fromFlatBufferWithUniformHints(
                DimensionCapabilitySpec spec,
                int[] flatVoxels,
                int chunkVoxelBase,
                int[] uniformSlotHints,
                int uniformHintBase,
                int[] topSurfaceY,
                int[] quartBiomes,
                int chunkX,
                int chunkZ,
                long worldSeed,
                boolean applyCarvers,
                boolean applyFeatures,
                boolean[] leafSlotMask,
                DensityFieldEvaluator borderDensityEval,
                QuartBiomeOrdinalResolver borderBiomeSampler,
                int[] beardPiecesI32
        ) {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(flatVoxels, "flatVoxels");
            int secCount = Math.max(1, spec.chunkSections());
            int[] topSurf = (topSurfaceY != null && topSurfaceY.length >= 256)
                    ? Arrays.copyOf(topSurfaceY, 256)
                    : new int[256];
            if (topSurfaceY == null || topSurfaceY.length < 256) {
                Arrays.fill(topSurf, spec.chunkMinY() - 1);
            }
            LcaCapabilityChunkFrame frame = new LcaCapabilityChunkFrame(spec, quartBiomes, topSurf);
            for (int s = 0; s < secCount; s++) {
                int hint = (uniformSlotHints != null && uniformHintBase + s < uniformSlotHints.length)
                        ? uniformSlotHints[uniformHintBase + s]
                        : -1;
                if (hint >= 0) {
                    frame.setSectionUniform(s, hint);
                } else {
                    int[] sec = new int[4096];
                    System.arraycopy(flatVoxels, chunkVoxelBase + (s << 12), sec, 0, 4096);
                    frame.sparseSections[s] = sec;
                    frame.uniformSlotIds[s] = -1;
                    frame.dominantSlotIds[s] = sec[0];
                }
            }
            if (beardPiecesI32 != null && beardPiecesI32.length >= 8) {
                frame.applyExactBeardifierPieceRefinement(chunkX, chunkZ, beardPiecesI32, true);
            }
            if (applyCarvers || applyFeatures) {
                applyPostSurfaceCapabilityPasses(
                        frame,
                        frame.quartBiomes,
                        frame.topSurfaceY,
                        spec,
                        chunkX,
                        chunkZ,
                        worldSeed,
                        applyCarvers,
                        applyFeatures,
                        leafSlotMask,
                        borderDensityEval,
                        borderBiomeSampler,
                        null,
                        0,
                        false);
            }
            frame.finalizeCompactLcaAndHeightmaps(leafSlotMask);
            for (int col = 0; col < 256; col++) {
                frame.topSurfaceY[col] = frame.oceanFloorY[col] - 1;
            }
            return frame;
        }

        public static LcaCapabilityChunkFrame fromFlatBufferWithUniformHints(
                DimensionCapabilitySpec spec,
                int[] flatVoxels,
                int chunkVoxelBase,
                int[] uniformSlotHints,
                int uniformHintBase,
                int[] topSurfaceY,
                int[] quartBiomes,
                int chunkX,
                int chunkZ,
                long worldSeed,
                boolean applyCarvers,
                boolean applyFeatures,
                boolean[] leafSlotMask,
                DensityFieldEvaluator borderDensityEval,
                QuartBiomeOrdinalResolver borderBiomeSampler,
                int[] beardPiecesI32,
                RouterCapabilityChannels routerChannels,
                int neighborNativeFeaturesMask8
        ) {
            return fromFlatBufferWithUniformHints(
                    spec,
                    flatVoxels,
                    chunkVoxelBase,
                    uniformSlotHints,
                    uniformHintBase,
                    topSurfaceY,
                    quartBiomes,
                    chunkX,
                    chunkZ,
                    worldSeed,
                    applyCarvers,
                    applyFeatures,
                    leafSlotMask,
                    borderDensityEval,
                    borderBiomeSampler,
                    beardPiecesI32,
                    routerChannels,
                    neighborNativeFeaturesMask8,
                    WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE);
        }

        public static LcaCapabilityChunkFrame fromFlatBufferWithUniformHints(
                DimensionCapabilitySpec spec,
                int[] flatVoxels,
                int chunkVoxelBase,
                int[] uniformSlotHints,
                int uniformHintBase,
                int[] topSurfaceY,
                int[] quartBiomes,
                int chunkX,
                int chunkZ,
                long worldSeed,
                boolean applyCarvers,
                boolean applyFeatures,
                boolean[] leafSlotMask,
                DensityFieldEvaluator borderDensityEval,
                QuartBiomeOrdinalResolver borderBiomeSampler,
                int[] beardPiecesI32,
                RouterCapabilityChannels routerChannels,
                int neighborNativeFeaturesMask8,
                WorldgenKernelSwapper.WorldgenFidelityPolicy fidelityPolicy
        ) {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(flatVoxels, "flatVoxels");
            WorldgenKernelSwapper.WorldgenFidelityPolicy effPolicy =
                    fidelityPolicy == null
                            ? WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE
                            : fidelityPolicy;
            int secCount = Math.max(1, spec.chunkSections());
            int[] topSurf = (topSurfaceY != null && topSurfaceY.length >= 256)
                    ? Arrays.copyOf(topSurfaceY, 256)
                    : new int[256];
            if (topSurfaceY == null || topSurfaceY.length < 256) {
                Arrays.fill(topSurf, spec.chunkMinY() - 1);
            } else if (effPolicy.isExact()) {
                for (int i = 0; i < 256; i++) {
                    int raw = topSurf[i];
                    if ((raw & 0x60000000) != 0 || (raw >= 0 && raw > 32767)) {
                        topSurf[i] = (short) (raw & 0xFFFF);
                    }
                }
            }
            LcaCapabilityChunkFrame frame = new LcaCapabilityChunkFrame(spec, quartBiomes, topSurf, effPolicy);
            for (int s = 0; s < secCount; s++) {
                int hint = (uniformSlotHints != null && uniformHintBase + s < uniformSlotHints.length)
                        ? uniformSlotHints[uniformHintBase + s]
                        : -1;
                if (hint >= 0) {
                    frame.setSectionUniform(s, hint);
                } else {
                    int[] sec = new int[4096];
                    System.arraycopy(flatVoxels, chunkVoxelBase + (s << 12), sec, 0, 4096);
                    frame.sparseSections[s] = sec;
                    frame.uniformSlotIds[s] = -1;
                    frame.dominantSlotIds[s] = sec[0];
                }
            }
            if (beardPiecesI32 != null && beardPiecesI32.length >= 8) {
                frame.applyExactBeardifierPieceRefinement(chunkX, chunkZ, beardPiecesI32, true);
            }
            if (applyCarvers || applyFeatures) {
                applyPostSurfaceCapabilityPasses(
                        frame,
                        frame.quartBiomes,
                        frame.topSurfaceY,
                        spec,
                        chunkX,
                        chunkZ,
                        worldSeed,
                        applyCarvers,
                        applyFeatures,
                        leafSlotMask,
                        borderDensityEval,
                        borderBiomeSampler,
                        routerChannels,
                        neighborNativeFeaturesMask8,
                        false,
                        effPolicy);
            }
            frame.finalizeCompactLcaAndHeightmaps(leafSlotMask);
            for (int col = 0; col < 256; col++) {
                frame.topSurfaceY[col] = frame.oceanFloorY[col] - 1;
            }
            return frame;
        }

        private static final java.util.Map<BytecodeConceptIr.ConceptModule, JavaConceptBackendEmitter.SurfaceRuleEvaluator>
                CACHED_SIGNBAND_SURFACE_EVALUATORS =
                        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

        private static JavaConceptBackendEmitter.SurfaceRuleEvaluator cachedSurfaceRuleEvaluator(
                BytecodeConceptIr.ConceptModule module) {
            if (module == null) {
                return null;
            }
            JavaConceptBackendEmitter.SurfaceRuleEvaluator cached = CACHED_SIGNBAND_SURFACE_EVALUATORS.get(module);
            if (cached != null) {
                return cached;
            }
            JavaConceptBackendEmitter.SurfaceRuleEvaluator compiled =
                    JavaConceptBackendEmitter.compileSurfaceRuleEvaluator(module);
            CACHED_SIGNBAND_SURFACE_EVALUATORS.put(module, compiled);
            return compiled;
        }

        /**
         * Refines ONLY the sparse columns flagged by {@code CAP_UNCERTAIN_SIGN_CPU_REFINE_FLAG} in
         * {@code VANILLA_EXACT} {@code HYBRID_SIGNBAND} GPU execution using IEEE-754 {@code FP64}
         * corner sampling and trilinear interpolation, keeping >98% of columns strictly on GPU.
         *
         * @return number of columns refined on CPU
         */
        public static int refineUncertainSignColumnsFp64(
                DimensionCapabilitySpec spec,
                int[] flatVoxels,
                int chunkVoxelBase,
                int[] uniformSlotHints,
                int uniformHintBase,
                int[] chunkTopSurf,
                long[] uncertainColMask4,
                int[] quartBiomes,
                int chunkX,
                int chunkZ,
                long worldSeed,
                boolean applySurfaceRules,
                DensityFieldEvaluator densityEval,
                QuartBiomeOrdinalResolver biomeResolver,
                AquiferCellSnapshot aquiferSnap,
                RouterCapabilityChannels routerChannels,
                int[] beardPiecesI32
        ) {
            if (spec == null
                    || flatVoxels == null
                    || chunkTopSurf == null
                    || uncertainColMask4 == null
                    || uncertainColMask4.length < 4
                    || densityEval == null) {
                return 0;
            }
            if ((uncertainColMask4[0] | uncertainColMask4[1] | uncertainColMask4[2] | uncertainColMask4[3]) == 0L) {
                return 0;
            }
            int secCount = Math.max(1, spec.chunkSections());
            int chunkMinY = spec.chunkMinY();
            int noiseMinY = spec.noiseMinY();
            int noiseTopY = noiseMinY + spec.noiseHeight() - 1;
            int minBoundY = Math.max(chunkMinY, noiseMinY);
            int maxBoundY = Math.min(chunkMinY + (secCount << 4) - 1, noiseTopY);
            int cellW = Math.max(1, spec.cellWidth());
            int cellH = Math.max(1, spec.cellHeight());
            int cellsX = Math.max(1, (16 + cellW - 1) / cellW);
            int cellsZ = Math.max(1, (16 + cellW - 1) / cellW);
            int cellsY = Math.max(1, spec.noiseHeight() / cellH);
            int strideZ = cellsY + 1;
            int strideX = (cellsZ + 1) * strideZ;
            int cornersPerChunk = (cellsX + 1) * strideX;
            int chunkBaseBx = chunkX << 4;
            int chunkBaseBz = chunkZ << 4;
            int defaultBlock = spec.defaultBlockSlot();
            int defaultFluid = spec.defaultFluidSlot();
            int lavaSlot = spec.lavaFluidSlot();
            int seaLevel = spec.seaLevel();
            int deepLavaY = spec.deepLavaFloorY();
            boolean aquifersEnabled = spec.aquifersEnabled();
            RouterCapabilityChannels rc = routerChannels != null ? routerChannels : RouterCapabilityChannels.EMPTY;

            // Lazy per-corner FP64 cache: only sample the exact (gx, gz, gy) corners of boundary cells.
            double[] cornerCache = new double[cornersPerChunk];
            boolean[] cornerSampled = new boolean[cornersPerChunk];
            float[] beardCorners = null;
            if (beardPiecesI32 != null && beardPiecesI32.length >= 8) {
                beardCorners = new float[cornersPerChunk];
                applyBeardifierCornersInPlace(
                        beardCorners, chunkX, chunkZ, noiseMinY, cellW, cellH, cellsX, cellsY, cellsZ, beardPiecesI32);
            }

            JavaConceptBackendEmitter.SurfaceRuleEvaluator surfaceEvaluator =
                    (applySurfaceRules && spec.hasSurfaceRuleModule())
                            ? cachedSurfaceRuleEvaluator(spec.surfaceRuleModule())
                            : null;
            long biomeZoomSeed = surfaceEvaluator != null ? obfuscateBiomeZoomSeed(worldSeed) : 0L;
            int quartsY = secCount << 2;
            int[] haloBiomes6x6 = null;
            int refinedColumns = 0;

            for (int w = 0; w < 4; w++) {
                long bits = uncertainColMask4[w];
                while (bits != 0L) {
                    int bitIdx = Long.numberOfTrailingZeros(bits);
                    bits &= (bits - 1L);
                    int col = (w << 6) | bitIdx;
                    int lx = col & 15;
                    int lz = (col >>> 4) & 15;
                    int bx = chunkBaseBx + lx;
                    int bz = chunkBaseBz + lz;
                    int gx = lx / cellW;
                    int gz = lz / cellW;
                    int dx = lx - gx * cellW;
                    int dz = lz - gz * cellW;
                    double fx = (double) dx / (double) cellW;
                    double fz = (double) dz / (double) cellW;
                    int c00Base = gx * strideX + gz * strideZ;
                    int c10Base = (gx + 1) * strideX + gz * strideZ;
                    int c01Base = gx * strideX + (gz + 1) * strideZ;
                    int c11Base = (gx + 1) * strideX + (gz + 1) * strideZ;

                    boolean colChanged = false;
                    for (int cy = cellsY - 1; cy >= 0; cy--) {
                        int cellBaseY = noiseMinY + cy * cellH;
                        // Fast boundary filter: only refine cells near a solid/non-solid transition
                        // (including 1-block neighbor above/below) so uniform deep stone / high air
                        // never triggers redundant FP64 corner evaluations.
                        boolean sawSolid = false;
                        boolean sawNonSolid = false;
                        int probeLo = Math.max(minBoundY, cellBaseY - 1);
                        int probeHi = Math.min(maxBoundY, cellBaseY + cellH);
                        for (int py = probeHi; py >= probeLo; py--) {
                            int pv = readFlatOrUniformVoxel(
                                    flatVoxels, chunkVoxelBase, uniformSlotHints, uniformHintBase, chunkMinY, secCount, py, col);
                            boolean pSolid = pv != 0
                                    && (defaultFluid == 0 || pv != defaultFluid)
                                    && (lavaSlot == 0 || pv != lavaSlot);
                            if (pSolid) {
                                sawSolid = true;
                            } else {
                                sawNonSolid = true;
                            }
                            if (sawSolid && sawNonSolid) {
                                break;
                            }
                        }
                        if (!sawSolid || !sawNonSolid) {
                            continue;
                        }
                        double v000 = getOrSampleCornerFp64(cornerCache, cornerSampled, c00Base + cy, chunkBaseBx + gx * cellW, noiseMinY + cy * cellH, chunkBaseBz + gz * cellW, densityEval, beardCorners);
                        double v001 = getOrSampleCornerFp64(cornerCache, cornerSampled, c00Base + cy + 1, chunkBaseBx + gx * cellW, noiseMinY + (cy + 1) * cellH, chunkBaseBz + gz * cellW, densityEval, beardCorners);
                        double v100 = getOrSampleCornerFp64(cornerCache, cornerSampled, c10Base + cy, chunkBaseBx + (gx + 1) * cellW, noiseMinY + cy * cellH, chunkBaseBz + gz * cellW, densityEval, beardCorners);
                        double v101 = getOrSampleCornerFp64(cornerCache, cornerSampled, c10Base + cy + 1, chunkBaseBx + (gx + 1) * cellW, noiseMinY + (cy + 1) * cellH, chunkBaseBz + gz * cellW, densityEval, beardCorners);
                        double v010 = getOrSampleCornerFp64(cornerCache, cornerSampled, c01Base + cy, chunkBaseBx + gx * cellW, noiseMinY + cy * cellH, chunkBaseBz + (gz + 1) * cellW, densityEval, beardCorners);
                        double v011 = getOrSampleCornerFp64(cornerCache, cornerSampled, c01Base + cy + 1, chunkBaseBx + gx * cellW, noiseMinY + (cy + 1) * cellH, chunkBaseBz + (gz + 1) * cellW, densityEval, beardCorners);
                        double v110 = getOrSampleCornerFp64(cornerCache, cornerSampled, c11Base + cy, chunkBaseBx + (gx + 1) * cellW, noiseMinY + cy * cellH, chunkBaseBz + (gz + 1) * cellW, densityEval, beardCorners);
                        double v111 = getOrSampleCornerFp64(cornerCache, cornerSampled, c11Base + cy + 1, chunkBaseBx + (gx + 1) * cellW, noiseMinY + (cy + 1) * cellH, chunkBaseBz + (gz + 1) * cellW, densityEval, beardCorners);
                        double cornerMag = Math.abs(v000) + Math.abs(v001) + Math.abs(v100) + Math.abs(v101)
                                + Math.abs(v010) + Math.abs(v011) + Math.abs(v110) + Math.abs(v111);
                        double signBandLimit = Math.max(0.02, cornerMag * 0.005);
                        for (int dy = cellH - 1; dy >= 0; dy--) {
                            int wy = cellBaseY + dy;
                            int sIdx = (wy - chunkMinY) >> 4;
                            if (sIdx < 0 || sIdx >= secCount) {
                                continue;
                            }
                            double fy = (double) dy / (double) cellH;
                            double x00 = v000 + (v001 - v000) * fy;
                            double x10 = v100 + (v101 - v100) * fy;
                            double x01 = v010 + (v011 - v010) * fy;
                            double x11 = v110 + (v111 - v110) * fy;
                            double z0 = x00 + (x10 - x00) * fx;
                            double z1 = x01 + (x11 - x01) * fx;
                            double dens64 = z0 + (z1 - z0) * fz;
                            if (Math.abs(dens64) > signBandLimit) {
                                continue;
                            }
                            boolean solid64 = dens64 > 0.0;

                            int cur = readFlatOrUniformVoxel(
                                    flatVoxels, chunkVoxelBase, uniformSlotHints, uniformHintBase, chunkMinY, secCount, wy, col);
                            boolean curSolid = cur != 0
                                    && (defaultFluid == 0 || cur != defaultFluid)
                                    && (lavaSlot == 0 || cur != lavaSlot);
                            if (solid64 == curSolid) {
                                continue;
                            }
                            int nextVox;
                            if (solid64) {
                                nextVox = defaultBlock;
                            } else if (aquifersEnabled && aquiferSnap != null) {
                                nextVox = evaluateNoiseBasedAquiferVoxelExactFp64(
                                        aquiferSnap,
                                        bx,
                                        wy,
                                        bz,
                                        dens64,
                                        rc.barrierNoise(),
                                        defaultBlock,
                                        defaultFluid,
                                        lavaSlot,
                                        seaLevel,
                                        deepLavaY);
                            } else if (aquifersEnabled && lavaSlot > 0 && wy <= deepLavaY) {
                                nextVox = lavaSlot;
                            } else if (!aquifersEnabled && defaultFluid > 0 && wy <= seaLevel) {
                                nextVox = defaultFluid;
                            } else {
                                nextVox = 0;
                            }
                            writeFlatMaterializingUniformSection(
                                    flatVoxels, chunkVoxelBase, uniformSlotHints, uniformHintBase, sIdx, wy, col, nextVox);
                            colChanged = true;
                        }
                    }

                    if (colChanged) {
                        refinedColumns++;
                        int newTop = chunkMinY - 1;
                        for (int wy = maxBoundY; wy >= minBoundY; wy--) {
                            int v = readFlatOrUniformVoxel(
                                    flatVoxels, chunkVoxelBase, uniformSlotHints, uniformHintBase, chunkMinY, secCount, wy, col);
                            if (v != 0 && (defaultFluid == 0 || v != defaultFluid) && (lavaSlot == 0 || v != lavaSlot)) {
                                newTop = wy;
                                break;
                            }
                        }
                        chunkTopSurf[col] = newTop;
                        if (surfaceEvaluator != null) {
                            if (haloBiomes6x6 == null && quartBiomes != null) {
                                haloBiomes6x6 = new int[quartsY * 36];
                                Arrays.fill(haloBiomes6x6, -1);
                                for (int qyRel = 0; qyRel < quartsY; qyRel++) {
                                    int dstLayer = qyRel * 36;
                                    int srcLayer = qyRel << 4;
                                    for (int qz = 0; qz < 4; qz++) {
                                        int dstRow = dstLayer + (qz + 1) * 6 + 1;
                                        int srcRow = srcLayer + (qz << 2);
                                        for (int qx = 0; qx < 4; qx++) {
                                            haloBiomes6x6[dstRow + qx] = Math.max(0, quartBiomes[srcRow + qx]);
                                        }
                                    }
                                }
                            }
                            int posHash = mixColumnSeed(worldSeed, bx, bz);
                            int surfaceDepth = computeSmoothSurfaceDepth(worldSeed, bx, bz, posHash);
                            int hx0 = chunkTopSurf[(lz << 4) | Math.max(0, lx - 1)];
                            int hx1 = chunkTopSurf[(lz << 4) | Math.min(15, lx + 1)];
                            int hz0 = chunkTopSurf[(Math.max(0, lz - 1) << 4) | lx];
                            int hz1 = chunkTopSurf[(Math.min(15, lz + 1) << 4) | lx];
                            int gradX = hx1 - hx0;
                            int gradZ = hz1 - hz0;
                            int steep = (Math.abs(gradX) >= 4 || Math.abs(gradZ) >= 4) ? 1 : 0;
                            int colPrelimY = newTop;
                            if (rc.initialDensityWithoutJaggedness() != null) {
                                int pStep = Math.max(1, cellH);
                                for (int py = maxBoundY; py >= minBoundY; py -= pStep) {
                                    if (rc.initialDensityWithoutJaggedness().sampleFp64(bx, py, bz) > 0.390625) {
                                        colPrelimY = py;
                                        break;
                                    }
                                }
                            }
                            int floorDepth = -1;
                            boolean fluidAbove = false;
                            int waterHeight = Integer.MIN_VALUE;
                            int startWy = Math.min(maxBoundY, Math.max(seaLevel, newTop) + 1);
                            int chunkBaseQx = chunkX << 2;
                            int chunkBaseQy = chunkMinY >> 2;
                            int chunkBaseQz = chunkZ << 2;
                            for (int wy = startWy; wy >= minBoundY; wy--) {
                                int sIdx = (wy - chunkMinY) >> 4;
                                int cur = readFlatOrUniformVoxel(
                                        flatVoxels, chunkVoxelBase, uniformSlotHints, uniformHintBase, chunkMinY, secCount, wy, col);
                                if (cur == 0) {
                                    floorDepth = -1;
                                    fluidAbove = false;
                                    waterHeight = Integer.MIN_VALUE;
                                    continue;
                                }
                                if ((defaultFluid != 0 && cur == defaultFluid) || (lavaSlot > 0 && cur == lavaSlot)) {
                                    floorDepth = -1;
                                    if (!fluidAbove) {
                                        waterHeight = wy + 1;
                                    }
                                    fluidAbove = true;
                                    continue;
                                }
                                floorDepth++;
                                if (cur != defaultBlock) {
                                    continue;
                                }
                                int ceilDepth = 0;
                                int ceilMax = Math.max(1, wy - minBoundY);
                                for (int cd = 1; cd <= ceilMax; cd++) {
                                    int byP = wy - cd;
                                    if (byP < minBoundY) {
                                        ceilDepth = ceilMax;
                                        break;
                                    }
                                    int bv = readFlatOrUniformVoxel(
                                            flatVoxels, chunkVoxelBase, uniformSlotHints, uniformHintBase, chunkMinY, secCount, byP, col);
                                    if (bv == 0 || (defaultFluid != 0 && bv == defaultFluid) || (lavaSlot > 0 && bv == lavaSlot)) {
                                        break;
                                    }
                                    ceilDepth = cd;
                                }
                                int biomeId = haloBiomes6x6 != null
                                        ? resolveVoronoiZoomedBiomeOrdinal(
                                                biomeZoomSeed,
                                                bx,
                                                wy,
                                                bz,
                                                chunkBaseQx,
                                                chunkBaseQy,
                                                chunkBaseQz,
                                                quartsY,
                                                haloBiomes6x6,
                                                biomeResolver)
                                        : 0;
                                float tempVal = rc.temperatureChannel() != null
                                        ? (float) rc.temperatureChannel().sampleFp64(bx, wy, bz)
                                        : 0.5f;
                                int nextSlot = evaluateSurfaceRuleModule(
                                        surfaceEvaluator,
                                        cur,
                                        defaultBlock,
                                        bx,
                                        wy,
                                        bz,
                                        floorDepth + 1,
                                        ceilDepth + 1,
                                        waterHeight,
                                        surfaceDepth,
                                        biomeId,
                                        colPrelimY,
                                        steep,
                                        gradX,
                                        gradZ,
                                        0.0,
                                        tempVal);
                                if (nextSlot != cur) {
                                    writeFlatMaterializingUniformSection(
                                            flatVoxels, chunkVoxelBase, uniformSlotHints, uniformHintBase, sIdx, wy, col, nextSlot);
                                }
                            }
                        }
                    }
                }
            }
            return refinedColumns;
        }

        private static int readFlatOrUniformVoxel(
                int[] flatVoxels,
                int chunkVoxelBase,
                int[] uniformSlotHints,
                int uniformHintBase,
                int chunkMinY,
                int secCount,
                int wy,
                int col
        ) {
            int sIdx = (wy - chunkMinY) >> 4;
            if (sIdx < 0 || sIdx >= secCount) {
                return 0;
            }
            if (uniformSlotHints != null && uniformHintBase + sIdx < uniformSlotHints.length) {
                int hint = uniformSlotHints[uniformHintBase + sIdx];
                if (hint >= 0) {
                    return hint;
                }
            }
            return flatVoxels[chunkVoxelBase + (sIdx << 12) + (((wy & 15) << 8) | col)];
        }

        private static void writeFlatMaterializingUniformSection(
                int[] flatVoxels,
                int chunkVoxelBase,
                int[] uniformSlotHints,
                int uniformHintBase,
                int sIdx,
                int wy,
                int col,
                int nextVox
        ) {
            int secStart = chunkVoxelBase + (sIdx << 12);
            if (uniformSlotHints != null && uniformHintBase + sIdx < uniformSlotHints.length) {
                int hint = uniformSlotHints[uniformHintBase + sIdx];
                if (hint >= 0) {
                    if (hint == nextVox) {
                        return;
                    }
                    // Native GPU readback intentionally skips DMA into recycled flatVoxels for
                    // uniform sections, so materialize hint across all 4,096 voxels before mutating.
                    Arrays.fill(flatVoxels, secStart, secStart + 4096, hint);
                    uniformSlotHints[uniformHintBase + sIdx] = -1;
                }
            }
            flatVoxels[secStart + (((wy & 15) << 8) | col)] = nextVox;
        }

        private static double getOrSampleCornerFp64(
                double[] cache,
                boolean[] sampled,
                int cornerIdx,
                int bx,
                int by,
                int bz,
                DensityFieldEvaluator densityEval,
                float[] beardCorners
        ) {
            if (sampled[cornerIdx]) {
                return cache[cornerIdx];
            }
            double v = densityEval.sampleFp64(bx, by, bz);
            if (beardCorners != null) {
                v += beardCorners[cornerIdx];
            }
            cache[cornerIdx] = v;
            sampled[cornerIdx] = true;
            return v;
        }

        /**
         * Refines sub-cell structure piece interiors ({@code BEARD_BOX}, {@code BEARD_THIN},
         * {@code BEARD_ENCAPSULATE}, {@code BEARD_BURY}) to 1-block precision in {@code O(pieceVolume)}
         * so Ancient City caverns, Trial Chamber shells, and Village house pads match Mojang's
         * {@code Beardifier} without falling back to {@code NoiseChunk}.
         */
        public void applyExactBeardifierPieceRefinement(
                int chunkX, int chunkZ, int[] beardPiecesI32, boolean preserveExistingSurfaceMaterial) {
            if (beardPiecesI32 == null || beardPiecesI32.length < 8) {
                return;
            }
            int chunkMinX = chunkX << 4;
            int chunkMinZ = chunkZ << 4;
            int chunkMaxX = chunkMinX + 15;
            int chunkMaxZ = chunkMinZ + 15;
            int minChunkY = spec.chunkMinY();
            int secCnt = sectionCount();
            int maxChunkY = minChunkY + (secCnt << 4) - 1;
            int minBoundY = Math.max(minChunkY, spec.noiseMinY());
            int maxBoundY = Math.min(maxChunkY, spec.noiseMinY() + spec.noiseHeight() - 1);
            int defBlock = Math.max(1, spec.defaultBlockSlot());
            int defFluid = spec.defaultFluidSlot();
            int lavaSlot = spec.lavaFluidSlot();
            int pieceCount = beardPiecesI32.length >>> 3;

            for (int i = 0; i < pieceCount; i++) {
                int p = i << 3;
                int w6 = beardPiecesI32[p + 6];
                int tAdj = w6 & 255;
                if (tAdj == WorldgenKernelSwapper.BEARD_NONE) {
                    continue;
                }
                int gDelta = (w6 << 16) >> 24;
                int minX = Math.max(chunkMinX, beardPiecesI32[p]);
                int maxX = Math.min(chunkMaxX, beardPiecesI32[p + 3]);
                int minZ = Math.max(chunkMinZ, beardPiecesI32[p + 2]);
                int maxZ = Math.min(chunkMaxZ, beardPiecesI32[p + 5]);
                if (minX > maxX || minZ > maxZ) {
                    continue;
                }
                int rawMinY = beardPiecesI32[p + 1] + gDelta;
                int rawMaxY = beardPiecesI32[p + 4];
                if (tAdj == WorldgenKernelSwapper.BEARD_BOX) {
                    int loY = Math.max(minBoundY, rawMinY);
                    int hiY = Math.min(maxBoundY, rawMaxY);
                    int padFloorLo = Math.max(minBoundY, rawMinY - 3);
                    int padFloorHi = Math.min(maxBoundY, rawMinY - 1);
                    for (int wz = minZ; wz <= maxZ; wz++) {
                        int lz = wz & 15;
                        for (int wx = minX; wx <= maxX; wx++) {
                            int lx = wx & 15;
                            int col = (lz << 4) | lx;
                            for (int wy = padFloorLo; wy <= padFloorHi; wy++) {
                                int s = (wy - minChunkY) >> 4;
                                int idx = ((wy & 15) << 8) | col;
                                int cur = getVoxel(s, idx);
                                if (cur == 0 || (defFluid > 0 && cur == defFluid) || (lavaSlot > 0 && cur == lavaSlot)) {
                                    setVoxel(s, idx, defBlock);
                                }
                            }
                            for (int wy = loY; wy <= hiY; wy++) {
                                int s = (wy - minChunkY) >> 4;
                                setVoxel(s, ((wy & 15) << 8) | col, 0);
                            }
                        }
                    }
                } else if (tAdj == WorldgenKernelSwapper.BEARD_THIN) {
                    int padLoY = Math.max(minBoundY, rawMinY - 5);
                    int padHiY = Math.min(maxBoundY, rawMinY - 1);
                    int clrLoY = Math.max(minBoundY, rawMinY);
                    int clrHiY = Math.min(maxBoundY, rawMaxY);
                    for (int wz = minZ; wz <= maxZ; wz++) {
                        int lz = wz & 15;
                        for (int wx = minX; wx <= maxX; wx++) {
                            int lx = wx & 15;
                            int col = (lz << 4) | lx;
                            int topExistingMat = defBlock;
                            if (preserveExistingSurfaceMaterial && topSurfaceY != null) {
                                int prevTop = topSurfaceY[col];
                                if (prevTop >= minBoundY && prevTop <= maxBoundY) {
                                    int m = getVoxel((prevTop - minChunkY) >> 4, ((prevTop & 15) << 8) | col);
                                    if (m > 0 && m != defFluid && m != lavaSlot) {
                                        topExistingMat = m;
                                    }
                                }
                            }
                            for (int wy = padLoY; wy <= padHiY; wy++) {
                                int s = (wy - minChunkY) >> 4;
                                int idx = ((wy & 15) << 8) | col;
                                int cur = getVoxel(s, idx);
                                if (cur == 0 || (defFluid > 0 && cur == defFluid)) {
                                    setVoxel(s, idx, wy == padHiY ? topExistingMat : defBlock);
                                }
                            }
                            for (int wy = clrLoY; wy <= clrHiY; wy++) {
                                int s = (wy - minChunkY) >> 4;
                                setVoxel(s, ((wy & 15) << 8) | col, 0);
                            }
                            if (preserveExistingSurfaceMaterial && topSurfaceY != null) {
                                topSurfaceY[col] = padHiY;
                            }
                        }
                    }
                } else if (tAdj == WorldgenKernelSwapper.BEARD_ENCAPSULATE || tAdj == WorldgenKernelSwapper.BEARD_BURY) {
                    int loY = Math.max(minBoundY, rawMinY - 2);
                    int hiY = Math.min(maxBoundY, tAdj == WorldgenKernelSwapper.BEARD_ENCAPSULATE ? rawMaxY + 2 : ((rawMinY + rawMaxY) >> 1));
                    for (int wz = minZ; wz <= maxZ; wz++) {
                        int lz = wz & 15;
                        for (int wx = minX; wx <= maxX; wx++) {
                            int lx = wx & 15;
                            int col = (lz << 4) | lx;
                            for (int wy = loY; wy <= hiY; wy++) {
                                int s = (wy - minChunkY) >> 4;
                                int idx = ((wy & 15) << 8) | col;
                                int cur = getVoxel(s, idx);
                                if (cur == 0 || (defFluid > 0 && cur == defFluid) || (lavaSlot > 0 && cur == lavaSlot)) {
                                    setVoxel(s, idx, defBlock);
                                }
                            }
                        }
                    }
                }
            }
        }

        public static LcaCapabilityChunkFrame fromMaterializedSections(
                DimensionCapabilitySpec spec,
                int[][] sections,
                int[] quartBiomes,
                boolean[] leafSlotMask
        ) {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(sections, "sections");
            int[] topSurf = new int[256];
            Arrays.fill(topSurf, spec.chunkMinY() - 1);
            LcaCapabilityChunkFrame frame = new LcaCapabilityChunkFrame(spec, quartBiomes, topSurf);
            int limit = Math.min(frame.sectionCount(), sections.length);
            for (int s = 0; s < limit; s++) {
                int[] src = sections[s];
                if (src == null || src.length < 4096) {
                    frame.setSectionUniform(s, 0);
                } else {
                    frame.sparseSections[s] = src;
                    frame.uniformSlotIds[s] = -1;
                    frame.dominantSlotIds[s] = src[0];
                }
            }
            frame.finalizeCompactLcaAndHeightmaps(leafSlotMask);
            for (int col = 0; col < 256; col++) {
                frame.topSurfaceY[col] = frame.oceanFloorY[col] - 1;
            }
            return frame;
        }

        void finalizeCompactLcaAndHeightmaps(boolean[] leafSlotMask) {
            int secCount = uniformSlotIds.length;
            int[] runScratch = null;
            for (int s = 0; s < secCount; s++) {
                int[] sec = sparseSections[s];
                if (sec == null) {
                    int u = Math.max(0, uniformSlotIds[s]);
                    uniformSlotIds[s] = u;
                    dominantSlotIds[s] = u;
                    octantMasks64[s] = 0L;
                    hilbertRuns[s] = EMPTY_INT_ARRAY;
                    continue;
                }
                int first = sec[0];
                boolean allSame = true;
                for (int i = 1; i < 4096; i++) {
                    if (sec[i] != first) {
                        allSame = false;
                        break;
                    }
                }
                if (allSame) {
                    setSectionUniform(s, Math.max(0, first));
                    continue;
                }
                // Pick majority candidate via Boyer-Moore majority pass over 64 strided samples
                int dom = 0;
                int votes = 0;
                for (int i = 0; i < 4096; i += 64) {
                    int v = sec[i];
                    if (votes == 0) {
                        dom = v;
                        votes = 1;
                    } else if (v == dom) {
                        votes++;
                    } else {
                        votes--;
                    }
                }
                dominantSlotIds[s] = dom;
                if (runScratch == null) {
                    runScratch = new int[4096];
                }
                long octMask = 0L;
                int runCount = 0;
                int curStart = -1;
                int curLen = 0;
                int curSlot = dom;
                for (int h3 = 0; h3 < 4096; h3++) {
                    int v = sec[WorldgenKernelSwapper.h3ToYzx(h3)];
                    if (v != dom) {
                        octMask |= (1L << (h3 >>> 6));
                        if (curLen > 0 && v == curSlot && curLen < 256) {
                            curLen++;
                        } else {
                            if (curLen > 0) {
                                runScratch[runCount++] = packHilbertRun(curStart, curLen, curSlot);
                            }
                            curStart = h3;
                            curLen = 1;
                            curSlot = v;
                        }
                    } else if (curLen > 0) {
                        runScratch[runCount++] = packHilbertRun(curStart, curLen, curSlot);
                        curLen = 0;
                        curSlot = dom;
                    }
                }
                if (curLen > 0) {
                    runScratch[runCount++] = packHilbertRun(curStart, curLen, curSlot);
                }
                octantMasks64[s] = octMask;
                hilbertRuns[s] = runCount == 0 ? EMPTY_INT_ARRAY : Arrays.copyOf(runScratch, runCount);
            }

            int lavaSlot = spec.lavaFluidSlot();
            boolean deepLava = false;
            if (lavaSlot > 0 && secCount > 0) {
                if (uniformSlotIds[0] == lavaSlot) {
                    deepLava = true;
                } else if (sparseSections[0] != null) {
                    int[] sec0 = sparseSections[0];
                    for (int i = 0; i < 4096; i++) {
                        if (sec0[i] == lavaSlot) {
                            deepLava = true;
                            break;
                        }
                    }
                }
            }
            this.hasDeepLava = deepLava;

            int minY = spec.chunkMinY();
            int defaultFluid = spec.defaultFluidSlot();
            long[] nonBlockingMask = spec.nonMotionBlockingSlotMask();
            for (int col = 0; col < 256; col++) {
                int ws = minY;
                int of = minY;
                int mb = minY;
                boolean foundWs = false;
                boolean foundOf = false;
                boolean foundMb = false;
                for (int s = secCount - 1; s >= 0 && !(foundWs && foundOf && foundMb); s--) {
                    int u = uniformSlotIds[s];
                    if (u == 0) {
                        continue;
                    }
                    int secBaseY = minY + (s << 4);
                    if (u > 0) {
                        int wyPlus1 = secBaseY + 16;
                        if (!foundWs) {
                            ws = wyPlus1;
                            foundWs = true;
                        }
                        boolean isFluid = (defaultFluid > 0 && u == defaultFluid) || (lavaSlot > 0 && u == lavaSlot);
                        boolean nonBlocking = bitMaskContains(nonBlockingMask, u);
                        if (!foundOf && !isFluid && !nonBlocking) {
                            of = wyPlus1;
                            foundOf = true;
                        }
                        boolean isLeaf = leafSlotMask != null && u < leafSlotMask.length && leafSlotMask[u];
                        if (!foundMb && !isLeaf && !nonBlocking) {
                            mb = wyPlus1;
                            foundMb = true;
                        }
                        continue;
                    }
                    int[] sec = sparseSections[s];
                    for (int ly = 15; ly >= 0; ly--) {
                        int slot = sec[(ly << 8) | col];
                        if (slot <= 0) {
                            continue;
                        }
                        int wyPlus1 = secBaseY + ly + 1;
                        if (!foundWs) {
                            ws = wyPlus1;
                            foundWs = true;
                        }
                        boolean isFluid = (defaultFluid > 0 && slot == defaultFluid)
                                || (lavaSlot > 0 && slot == lavaSlot);
                        boolean nonBlocking = bitMaskContains(nonBlockingMask, slot);
                        if (!foundOf && !isFluid && !nonBlocking) {
                            of = wyPlus1;
                            foundOf = true;
                        }
                        boolean isLeaf = leafSlotMask != null && slot < leafSlotMask.length && leafSlotMask[slot];
                        if (!foundMb && !isLeaf && !nonBlocking) {
                            mb = wyPlus1;
                            foundMb = true;
                        }
                        if (foundWs && foundOf && foundMb) {
                            break;
                        }
                    }
                }
                worldSurfaceY[col] = ws;
                oceanFloorY[col] = of;
                motionBlockingNoLeavesY[col] = mb;
            }
        }
    }

    /**
     * Evaluates 64 trilinearly interpolated FP32 densities for a {@code 4x4x4} octant into
     * {@code out64[(dy << 4) | (dx << 2) | dz]} using branchless unit-stride loops so HotSpot C2
     * SuperWord emits AVX2 / Zen 4 AVX-512 {@code vfma231ps} instructions.
     */
    private static final float[] CELL_DX_STEP_16 = {
        0.0f, 0.0f, 0.0f, 0.0f,
        1.0f, 1.0f, 1.0f, 1.0f,
        2.0f, 2.0f, 2.0f, 2.0f,
        3.0f, 3.0f, 3.0f, 3.0f
    };
    private static final float[] CELL_DZ_STEP_16 = {
        0.0f, 1.0f, 2.0f, 3.0f,
        0.0f, 1.0f, 2.0f, 3.0f,
        0.0f, 1.0f, 2.0f, 3.0f,
        0.0f, 1.0f, 2.0f, 3.0f
    };

    public static void interpolateCell4xHx4Fp32(
            float c000,
            float c001,
            float c100,
            float c101,
            float c010,
            float c011,
            float c110,
            float c111,
            int cellH,
            float[] outCell
    ) {
        float invH = 1.0f / (float) cellH;
        float d00 = (c001 - c000) * invH;
        float d10 = (c101 - c100) * invH;
        float d01 = (c011 - c010) * invH;
        float d11 = (c111 - c110) * invH;
        float[] dxSteps = CELL_DX_STEP_16;
        float[] dzSteps = CELL_DZ_STEP_16;
        if (outCell == dxSteps || outCell == dzSteps) {
            return;
        }
        for (int dy = 0; dy < cellH; dy++) {
            float x00 = c000 + d00 * dy;
            float x10 = c100 + d10 * dy;
            float x01 = c010 + d01 * dy;
            float x11 = c110 + d11 * dy;
            float dx0 = (x10 - x00) * 0.25f;
            float dx1 = (x11 - x01) * 0.25f;
            int yBase = dy << 4;
            for (int i = 0; i < 16; i++) {
                float dxf = dxSteps[i];
                float z0 = x00 + dx0 * dxf;
                float z1 = x01 + dx1 * dxf;
                float dzStep = (z1 - z0) * 0.25f;
                outCell[yBase + i] = z0 + dzStep * dzSteps[i];
            }
        }
    }

    public static void interpolateOctant64Fp32(
            float c000,
            float c001,
            float c100,
            float c101,
            float c010,
            float c011,
            float c110,
            float c111,
            float[] out64
    ) {
        float d00 = (c001 - c000) * 0.25f;
        float d10 = (c101 - c100) * 0.25f;
        float d01 = (c011 - c010) * 0.25f;
        float d11 = (c111 - c110) * 0.25f;
        for (int dy = 0; dy < 4; dy++) {
            float x00 = c000 + d00 * dy;
            float x10 = c100 + d10 * dy;
            float x01 = c010 + d01 * dy;
            float x11 = c110 + d11 * dy;
            float dx0 = (x10 - x00) * 0.25f;
            float dx1 = (x11 - x01) * 0.25f;
            int yBase = dy << 4;
            for (int dx = 0; dx < 4; dx++) {
                float z0 = x00 + dx0 * dx;
                float z1 = x01 + dx1 * dx;
                float dzStep = (z1 - z0) * 0.25f;
                int base = yBase | (dx << 2);
                out64[base] = z0;
                out64[base + 1] = z0 + dzStep;
                out64[base + 2] = z0 + dzStep * 2.0f;
                out64[base + 3] = z0 + dzStep * 3.0f;
            }
        }
    }

    /**
     * 6D climate channel sampler at quart coordinates {@code (qx, qy, qz)} returning
     * {@code [temperature, humidity, continentalness, erosion, depth, weirdness]}.
     */
    @FunctionalInterface
    public interface QuartClimateSampler {
        float[] sampleQuart6(int quartX, int quartY, int quartZ);

        /**
         * Batches {@code countQuartY} vertical climate quarts along {@code (quartX, quartZ)} into
         * {@code outClimate6} at {@code baseElemOffset + qy * layerElemStride6}.
         */
        default void sampleQuartColumn6(
                int quartX,
                int baseQuartY,
                int countQuartY,
                int quartZ,
                float[] outClimate6,
                int baseElemOffset,
                int layerElemStride6
        ) {
            for (int i = 0; i < countQuartY; i++) {
                float[] c6 = sampleQuart6(quartX, baseQuartY + i, quartZ);
                System.arraycopy(c6, 0, outClimate6, baseElemOffset + i * layerElemStride6, 6);
            }
        }
    }

    /**
     * Direct 3D quart biome-ordinal resolver unifying {@code Climate.RTree} BVH, End island
     * erosion thresholds, and lifted custom {@code BiomeSource} functions.
     */
    @FunctionalInterface
    public interface QuartBiomeOrdinalResolver {
        int resolveBiomeOrdinal(int quartX, int quartY, int quartZ);
    }

    /**
     * 7D quantized climate hyper-rectangle mapped to a runtime {@code Registry<Biome>} ordinal.
     */
    public record BiomeParameterBox(long[] mins7, long[] maxs7, int biomeOrdinal) {
        public BiomeParameterBox {
            Objects.requireNonNull(mins7, "mins7");
            Objects.requireNonNull(maxs7, "maxs7");
            if (mins7.length < 7 || maxs7.length < 7) {
                throw new IllegalArgumentException("Climate parameter box requires 7 intervals");
            }
            mins7 = Arrays.copyOf(mins7, 7);
            maxs7 = Arrays.copyOf(maxs7, 7);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof BiomeParameterBox other)) {
                return false;
            }
            return java.util.Arrays.equals(this.mins7, other.mins7)
                    && java.util.Arrays.equals(this.maxs7, other.maxs7)
                    && this.biomeOrdinal == other.biomeOrdinal;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + java.util.Arrays.hashCode(this.mins7);
            result = 31 * result + java.util.Arrays.hashCode(this.maxs7);
            result = 31 * result + this.biomeOrdinal;
            return result;
        }

        @Override
        public String toString() {
            return "BiomeParameterBox[mins7=" + java.util.Arrays.toString(this.mins7)
                    + ", maxs7=" + java.util.Arrays.toString(this.maxs7)
                    + ", biomeOrdinal=" + this.biomeOrdinal
                    + "]";
        }
    }

    /**
     * Structural vegetation feature lifted from {@code BiomeGenerationSettings#features()}.
     */
    public record BiomeFeatureSpec(
            int biomeOrdinal,
            int placementMode,
            int countPerChunk,
            int maxWaterDepth,
            int minY,
            int maxY,
            int trunkSlot,
            int foliageSlot,
            int accentSlot,
            int requiredGroundSlot,
            int baseHeight,
            int heightRand,
            int canopyRadius,
            int shapeKind
    ) {}

    /**
     * Underground ore or mineral blob lifted from {@code OreConfiguration} + {@code HeightRangePlacement}.
     */
    public record OreVeinSpec(
            int targetBlockSlot,
            int oreBlockSlot,
            int veinSize,
            int countPerChunk,
            int minY,
            int maxY,
            boolean triangleDistribution,
            long[] targetSlotMask,
            long[] allowedBiomeMask
    ) {
        public OreVeinSpec(
                int targetBlockSlot,
                int oreBlockSlot,
                int veinSize,
                int countPerChunk,
                int minY,
                int maxY,
                boolean triangleDistribution
        ) {
            this(
                    targetBlockSlot,
                    oreBlockSlot,
                    veinSize,
                    countPerChunk,
                    minY,
                    maxY,
                    triangleDistribution,
                    singleSlotBitMask(targetBlockSlot),
                    null);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof OreVeinSpec other)) {
                return false;
            }
            return this.targetBlockSlot == other.targetBlockSlot
                    && this.oreBlockSlot == other.oreBlockSlot
                    && this.veinSize == other.veinSize
                    && this.countPerChunk == other.countPerChunk
                    && this.minY == other.minY
                    && this.maxY == other.maxY
                    && this.triangleDistribution == other.triangleDistribution
                    && java.util.Arrays.equals(this.targetSlotMask, other.targetSlotMask)
                    && java.util.Arrays.equals(this.allowedBiomeMask, other.allowedBiomeMask);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.targetBlockSlot;
            result = 31 * result + this.oreBlockSlot;
            result = 31 * result + this.veinSize;
            result = 31 * result + this.countPerChunk;
            result = 31 * result + this.minY;
            result = 31 * result + this.maxY;
            result = 31 * result + Boolean.hashCode(this.triangleDistribution);
            result = 31 * result + java.util.Arrays.hashCode(this.targetSlotMask);
            result = 31 * result + java.util.Arrays.hashCode(this.allowedBiomeMask);
            return result;
        }

        @Override
        public String toString() {
            return "OreVeinSpec[targetBlockSlot=" + this.targetBlockSlot
                    + ", oreBlockSlot=" + this.oreBlockSlot
                    + ", veinSize=" + this.veinSize
                    + ", countPerChunk=" + this.countPerChunk
                    + ", minY=" + this.minY
                    + ", maxY=" + this.maxY
                    + ", triangleDistribution=" + this.triangleDistribution
                    + ", targetSlotMask=" + java.util.Arrays.toString(this.targetSlotMask)
                    + ", allowedBiomeMask=" + java.util.Arrays.toString(this.allowedBiomeMask)
                    + "]";
        }
    }

    public static long[] singleSlotBitMask(int slot) {
        if (slot < 0) {
            return new long[0];
        }
        long[] mask = new long[(slot >>> 6) + 1];
        mask[slot >>> 6] = 1L << (slot & 63);
        return mask;
    }

    public static boolean bitMaskContains(long[] mask, int bit) {
        if (mask == null || bit < 0) {
            return false;
        }
        int w = bit >>> 6;
        return w < mask.length && (mask[w] & (1L << (bit & 63))) != 0L;
    }

    public static boolean bitMasksIntersect(long[] a, long[] b) {
        if (a == null || b == null) {
            return true;
        }
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            if ((a[i] & b[i]) != 0L) {
                return true;
            }
        }
        return false;
    }

    public static int packBlockOffset(int dx, int dy, int dz) {
        int cx = Math.max(-16, Math.min(16, dx)) + 16;
        int cy = Math.max(-16, Math.min(16, dy)) + 16;
        int cz = Math.max(-16, Math.min(16, dz)) + 16;
        return (cx & 63) | ((cy & 63) << 6) | ((cz & 63) << 12);
    }

    /**
     * Pure-primitive lifted {@code IntProvider} ({@code ConstantInt}, {@code UniformInt},
     * {@code BiasedToBottomInt}, {@code ClampedNormalInt}, {@code WeightedListInt}).
     */
    public record LiftedIntProvider(
            int kind,
            int a,
            int b,
            int minBound,
            int maxBound,
            int[] values,
            int[] cumWeights
    ) {
        public static final LiftedIntProvider ZERO = constant(0);
        public static final LiftedIntProvider ONE = constant(1);

        public static LiftedIntProvider constant(int value) {
            return new LiftedIntProvider(INT_CONST, value, value, value, value, EMPTY_INT_ARRAY, EMPTY_INT_ARRAY);
        }

        public static LiftedIntProvider uniform(int minInclusive, int maxInclusive) {
            int lo = Math.min(minInclusive, maxInclusive);
            int hi = Math.max(minInclusive, maxInclusive);
            return new LiftedIntProvider(INT_UNIFORM, lo, hi, lo, hi, EMPTY_INT_ARRAY, EMPTY_INT_ARRAY);
        }

        public static LiftedIntProvider biasedToBottom(int minInclusive, int maxInclusive) {
            int lo = Math.min(minInclusive, maxInclusive);
            int hi = Math.max(minInclusive, maxInclusive);
            return new LiftedIntProvider(INT_BIASED_TO_BOTTOM, lo, hi, lo, hi, EMPTY_INT_ARRAY, EMPTY_INT_ARRAY);
        }

        public static LiftedIntProvider clampedNormal(int meanPermille, int devPermille, int minInclusive, int maxInclusive) {
            int lo = Math.min(minInclusive, maxInclusive);
            int hi = Math.max(minInclusive, maxInclusive);
            return new LiftedIntProvider(INT_CLAMPED_NORMAL, meanPermille, devPermille, lo, hi, EMPTY_INT_ARRAY, EMPTY_INT_ARRAY);
        }

        public static LiftedIntProvider trapezoid(int minInclusive, int maxInclusive, int plateau) {
            int lo = Math.min(minInclusive, maxInclusive);
            int hi = Math.max(minInclusive, maxInclusive);
            int p = Math.max(0, Math.min(hi - lo, plateau));
            return new LiftedIntProvider(INT_TRAPEZOID, lo, hi, lo, hi, new int[] {p}, EMPTY_INT_ARRAY);
        }

        public static LiftedIntProvider weighted(int[] values, int[] cumWeights) {
            if (values == null || cumWeights == null || values.length == 0 || values.length != cumWeights.length) {
                return ONE;
            }
            int lo = values[0];
            int hi = values[0];
            for (int v : values) {
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
            return new LiftedIntProvider(INT_WEIGHTED_LIST, lo, hi, lo, hi, values, cumWeights);
        }

        int sample(DecorRng rng) {
            return switch (kind) {
                case INT_CONST -> a;
                case INT_UNIFORM -> b <= a ? a : a + rng.nextInt(b - a + 1);
                case INT_BIASED_TO_BOTTOM -> b <= a ? a : a + rng.nextInt(rng.nextInt(b - a + 1) + 1);
                case INT_CLAMPED_NORMAL -> {
                    double g = (rng.nextFloat() + rng.nextFloat() + rng.nextFloat() - 1.5) * 2.0;
                    int raw = (int) Math.round((a * 0.001) + (b * 0.001) * g);
                    yield Math.max(minBound, Math.min(maxBound, raw));
                }
                case INT_WEIGHTED_LIST -> {
                    if (cumWeights.length == 0) yield a;
                    int total = cumWeights[cumWeights.length - 1];
                    if (total <= 0) yield values[0];
                    int pick = rng.nextInt(total);
                    for (int i = 0; i < cumWeights.length; i++) {
                        if (pick < cumWeights[i]) {
                            yield values[i];
                        }
                    }
                    yield values[values.length - 1];
                }
                case INT_TRAPEZOID -> {
                    int range = b - a;
                    if (range <= 0) yield a;
                    int plateau = values.length > 0 ? Math.max(0, values[0]) : 0;
                    if (plateau >= range) yield a + rng.nextInt(range + 1);
                    int ramp = (range - plateau) >>> 1;
                    int center = range - ramp;
                    yield a + rng.nextInt(center + 1) + rng.nextInt(ramp + 1);
                }
                default -> a;
            };
        }

        int maxPossibleValue() {
            return switch (kind) {
                case INT_CONST -> a;
                case INT_UNIFORM, INT_BIASED_TO_BOTTOM, INT_TRAPEZOID -> Math.max(a, b);
                case INT_CLAMPED_NORMAL, INT_WEIGHTED_LIST -> maxBound;
                default -> Math.max(a, maxBound);
            };
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof LiftedIntProvider other)) {
                return false;
            }
            return this.kind == other.kind
                    && this.a == other.a
                    && this.b == other.b
                    && this.minBound == other.minBound
                    && this.maxBound == other.maxBound
                    && java.util.Arrays.equals(this.values, other.values)
                    && java.util.Arrays.equals(this.cumWeights, other.cumWeights);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.kind;
            result = 31 * result + this.a;
            result = 31 * result + this.b;
            result = 31 * result + this.minBound;
            result = 31 * result + this.maxBound;
            result = 31 * result + java.util.Arrays.hashCode(this.values);
            result = 31 * result + java.util.Arrays.hashCode(this.cumWeights);
            return result;
        }

        @Override
        public String toString() {
            return "LiftedIntProvider[kind=" + this.kind
                    + ", a=" + this.a
                    + ", b=" + this.b
                    + ", minBound=" + this.minBound
                    + ", maxBound=" + this.maxBound
                    + ", values=" + java.util.Arrays.toString(this.values)
                    + ", cumWeights=" + java.util.Arrays.toString(this.cumWeights)
                    + "]";
        }
    }

    /**
     * Pure-primitive lifted {@code BlockStateProvider} carrying canonical palette slots and
     * optional paired {@code half=upper} slots for 2-block structural double plants.
     */
    public record LiftedStateProvider(
            int kind,
            int primarySlot,
            int axisXSlot,
            int axisZSlot,
            int[] slots,
            int[] cumWeights,
            int[] upperHalfSlots
    ) {
        public LiftedStateProvider(
                int kind,
                int primarySlot,
                int axisXSlot,
                int axisZSlot,
                int[] slots,
                int[] cumWeights
        ) {
            this(kind, primarySlot, axisXSlot, axisZSlot, slots, cumWeights, EMPTY_INT_ARRAY);
        }

        public static final LiftedStateProvider AIR = simple(0);

        public static LiftedStateProvider simple(int slot) {
            return new LiftedStateProvider(STATE_SIMPLE, slot, slot, slot, EMPTY_INT_ARRAY, EMPTY_INT_ARRAY, EMPTY_INT_ARRAY);
        }

        public static LiftedStateProvider simpleDouble(int lowerSlot, int upperSlot) {
            return new LiftedStateProvider(
                    STATE_SIMPLE,
                    lowerSlot,
                    lowerSlot,
                    lowerSlot,
                    EMPTY_INT_ARRAY,
                    EMPTY_INT_ARRAY,
                    upperSlot > 0 ? new int[] {upperSlot} : EMPTY_INT_ARRAY);
        }

        public static LiftedStateProvider rotatedPillar(int axisYSlot, int axisXSlot, int axisZSlot) {
            return new LiftedStateProvider(
                    STATE_ROTATED_PILLAR,
                    axisYSlot,
                    axisXSlot > 0 ? axisXSlot : axisYSlot,
                    axisZSlot > 0 ? axisZSlot : axisYSlot,
                    EMPTY_INT_ARRAY,
                    EMPTY_INT_ARRAY,
                    EMPTY_INT_ARRAY);
        }

        public static LiftedStateProvider weighted(int[] slots, int[] cumWeights) {
            return weightedWithUpper(slots, cumWeights, EMPTY_INT_ARRAY);
        }

        public static LiftedStateProvider weightedWithUpper(int[] slots, int[] cumWeights, int[] upperHalfSlots) {
            if (slots == null || cumWeights == null || slots.length == 0 || slots.length != cumWeights.length) {
                return AIR;
            }
            return new LiftedStateProvider(
                    STATE_WEIGHTED,
                    slots[0],
                    slots[0],
                    slots[0],
                    slots,
                    cumWeights,
                    upperHalfSlots != null ? upperHalfSlots : EMPTY_INT_ARRAY);
        }

        public static LiftedStateProvider noise(int[] slots) {
            return noiseWithUpper(slots, EMPTY_INT_ARRAY);
        }

        public static LiftedStateProvider noiseWithUpper(int[] slots, int[] upperHalfSlots) {
            if (slots == null || slots.length == 0) {
                return AIR;
            }
            return new LiftedStateProvider(
                    STATE_NOISE,
                    slots[0],
                    slots[0],
                    slots[0],
                    slots,
                    EMPTY_INT_ARRAY,
                    upperHalfSlots != null ? upperHalfSlots : EMPTY_INT_ARRAY);
        }

        public static LiftedStateProvider noiseThreshold(
                int thresholdPermille,
                int highChancePermille,
                int defaultSlot,
                int defaultUpperSlot,
                int[] lowSlots,
                int[] lowUpperSlots,
                int[] highSlots,
                int[] highUpperSlots
        ) {
            int nLow = lowSlots != null ? lowSlots.length : 0;
            int nHigh = highSlots != null ? highSlots.length : 0;
            int[] allSlots = new int[1 + nLow + nHigh];
            int[] allUpper = new int[1 + nLow + nHigh];
            allSlots[0] = defaultSlot;
            allUpper[0] = Math.max(0, defaultUpperSlot);
            for (int i = 0; i < nLow; i++) {
                allSlots[1 + i] = lowSlots[i];
                allUpper[1 + i] = (lowUpperSlots != null && i < lowUpperSlots.length) ? Math.max(0, lowUpperSlots[i]) : 0;
            }
            for (int i = 0; i < nHigh; i++) {
                allSlots[1 + nLow + i] = highSlots[i];
                allUpper[1 + nLow + i] = (highUpperSlots != null && i < highUpperSlots.length) ? Math.max(0, highUpperSlots[i]) : 0;
            }
            int hcClamped = Math.max(0, Math.min(1000, highChancePermille));
            int packedMeta = (hcClamped & 0xFFFF) | ((nLow & 0xFF) << 16);
            return new LiftedStateProvider(
                    STATE_NOISE_THRESHOLD,
                    defaultSlot,
                    thresholdPermille,
                    packedMeta,
                    allSlots,
                    EMPTY_INT_ARRAY,
                    allUpper);
        }

        private int upperAt(int idx) {
            return (upperHalfSlots != null && idx >= 0 && idx < upperHalfSlots.length) ? upperHalfSlots[idx] : 0;
        }

        long sampleSlotAndUpper(int wx, int wy, int wz, DecorRng rng) {
            return switch (kind) {
                case STATE_WEIGHTED -> {
                    if (cumWeights.length == 0) yield ((long) upperAt(0) << 32) | (primarySlot & 0xFFFFFFFFL);
                    int total = cumWeights[cumWeights.length - 1];
                    if (total <= 0) yield ((long) upperAt(0) << 32) | (slots[0] & 0xFFFFFFFFL);
                    int pick = rng.nextInt(total);
                    for (int i = 0; i < cumWeights.length; i++) {
                        if (pick < cumWeights[i]) {
                            yield ((long) upperAt(i) << 32) | (slots[i] & 0xFFFFFFFFL);
                        }
                    }
                    int last = slots.length - 1;
                    yield ((long) upperAt(last) << 32) | (slots[last] & 0xFFFFFFFFL);
                }
                case STATE_NOISE -> {
                    if (slots.length == 0) yield ((long) upperAt(0) << 32) | (primarySlot & 0xFFFFFFFFL);
                    double n = sampleDecoratorSmoothNoise2D(0x6A09E667F3BCC908L, wx * 0.03125 + wy * 0.015625, wz * 0.03125 - wy * 0.015625);
                    int idx = Math.max(0, Math.min(slots.length - 1, (int) (((n + 1.0) * 0.5) * slots.length)));
                    yield ((long) upperAt(idx) << 32) | (slots[idx] & 0xFFFFFFFFL);
                }
                case STATE_NOISE_THRESHOLD -> {
                    if (slots.length == 0) yield 0L;
                    double n = sampleDecoratorSmoothNoise2D(0x6A09E667F3BCC908L, wx * 0.025, wz * 0.025);
                    double thresh = axisXSlot * 0.001;
                    int highPermille = axisZSlot & 0xFFFF;
                    int lowCount = (axisZSlot >>> 16) & 0xFF;
                    int highCount = Math.max(0, slots.length - 1 - lowCount);
                    if (n < thresh) {
                        if (lowCount > 0) {
                            int idx = 1 + rng.nextInt(lowCount);
                            yield ((long) upperAt(idx) << 32) | (slots[idx] & 0xFFFFFFFFL);
                        }
                        yield ((long) upperAt(0) << 32) | (primarySlot & 0xFFFFFFFFL);
                    }
                    if (highCount > 0 && rng.nextInt(1000) < highPermille) {
                        int idx = 1 + lowCount + rng.nextInt(highCount);
                        yield ((long) upperAt(idx) << 32) | (slots[idx] & 0xFFFFFFFFL);
                    }
                    yield ((long) upperAt(0) << 32) | (primarySlot & 0xFFFFFFFFL);
                }
                default -> ((long) upperAt(0) << 32) | (primarySlot & 0xFFFFFFFFL);
            };
        }

        int sampleSlot(int wx, int wy, int wz, DecorRng rng) {
            return (int) sampleSlotAndUpper(wx, wy, wz, rng);
        }

        int axisSlot(int axis) {
            if (axis == 0) return axisXSlot > 0 ? axisXSlot : primarySlot;
            if (axis == 2) return axisZSlot > 0 ? axisZSlot : primarySlot;
            return primarySlot;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof LiftedStateProvider other)) {
                return false;
            }
            return this.kind == other.kind
                    && this.primarySlot == other.primarySlot
                    && this.axisXSlot == other.axisXSlot
                    && this.axisZSlot == other.axisZSlot
                    && java.util.Arrays.equals(this.slots, other.slots)
                    && java.util.Arrays.equals(this.cumWeights, other.cumWeights)
                    && java.util.Arrays.equals(this.upperHalfSlots, other.upperHalfSlots);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.kind;
            result = 31 * result + this.primarySlot;
            result = 31 * result + this.axisXSlot;
            result = 31 * result + this.axisZSlot;
            result = 31 * result + java.util.Arrays.hashCode(this.slots);
            result = 31 * result + java.util.Arrays.hashCode(this.cumWeights);
            result = 31 * result + java.util.Arrays.hashCode(this.upperHalfSlots);
            return result;
        }

        @Override
        public String toString() {
            return "LiftedStateProvider[kind=" + this.kind
                    + ", primarySlot=" + this.primarySlot
                    + ", axisXSlot=" + this.axisXSlot
                    + ", axisZSlot=" + this.axisZSlot
                    + ", slots=" + java.util.Arrays.toString(this.slots)
                    + ", cumWeights=" + java.util.Arrays.toString(this.cumWeights)
                    + ", upperHalfSlots=" + java.util.Arrays.toString(this.upperHalfSlots)
                    + "]";
        }
    }

    /**
     * Pure-primitive RPN {@code BlockPredicate} bytecode and slot bitmasks.
     */
    public record LiftedBlockPredicate(int[] ops, long[][] slotMasks) {
        public static final LiftedBlockPredicate ALWAYS_TRUE =
                new LiftedBlockPredicate(new int[] {BP_TRUE, packBlockOffset(0, 0, 0), -1}, new long[0][]);

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof LiftedBlockPredicate other)) {
                return false;
            }
            return java.util.Arrays.equals(this.ops, other.ops)
                    && java.util.Arrays.deepEquals(this.slotMasks, other.slotMasks);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + java.util.Arrays.hashCode(this.ops);
            result = 31 * result + java.util.Arrays.deepHashCode(this.slotMasks);
            return result;
        }

        @Override
        public String toString() {
            return "LiftedBlockPredicate[ops=" + java.util.Arrays.toString(this.ops)
                    + ", slotMasks=" + java.util.Arrays.deepToString(this.slotMasks)
                    + "]";
        }
    }

    /**
     * Single {@code OreConfiguration$TargetBlockState} rule pairing a {@code RuleTest} slot bitmask with its ore slot.
     */
    public record OreTargetRule(long[] targetSlotMask, int oreBlockSlot) {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof OreTargetRule other)) {
                return false;
            }
            return java.util.Arrays.equals(this.targetSlotMask, other.targetSlotMask)
                    && this.oreBlockSlot == other.oreBlockSlot;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + java.util.Arrays.hashCode(this.targetSlotMask);
            result = 31 * result + this.oreBlockSlot;
            return result;
        }

        @Override
        public String toString() {
            return "OreTargetRule[targetSlotMask=" + java.util.Arrays.toString(this.targetSlotMask)
                    + ", oreBlockSlot=" + this.oreBlockSlot
                    + "]";
        }
    }

    /**
     * Single layer inside a lifted {@code BlockColumnConfiguration}.
     */
    public record ColumnLayer(LiftedIntProvider height, LiftedStateProvider state) {}

    /**
     * Weighted or uniform child branch inside {@code RandomFeatureConfiguration} / {@code SimpleRandomFeatureConfiguration}.
     */
    public record SelectorBranch(
            int chancePermille,
            int[] placementOps,
            LiftedIntProvider[] intProviders,
            LiftedBlockPredicate[] predicates,
            LiftedFeatureNode feature
    ) {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof SelectorBranch other)) {
                return false;
            }
            return this.chancePermille == other.chancePermille
                    && java.util.Arrays.equals(this.placementOps, other.placementOps)
                    && java.util.Arrays.equals(this.intProviders, other.intProviders)
                    && java.util.Arrays.equals(this.predicates, other.predicates)
                    && java.util.Objects.equals(this.feature, other.feature);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.chancePermille;
            result = 31 * result + java.util.Arrays.hashCode(this.placementOps);
            result = 31 * result + java.util.Arrays.hashCode(this.intProviders);
            result = 31 * result + java.util.Arrays.hashCode(this.predicates);
            result = 31 * result + java.util.Objects.hashCode(this.feature);
            return result;
        }

        @Override
        public String toString() {
            return "SelectorBranch[chancePermille=" + this.chancePermille
                    + ", placementOps=" + java.util.Arrays.toString(this.placementOps)
                    + ", intProviders=" + java.util.Arrays.toString(this.intProviders)
                    + ", predicates=" + java.util.Arrays.toString(this.predicates)
                    + ", feature=" + this.feature
                    + "]";
        }
    }

    /**
     * Optional tree decorator table (`TrunkVineDecorator`, `LeaveVineDecorator`, `CocoaDecorator`).
     */
    public record TreeDecoratorSpec(
            boolean trunkVine,
            int leaveVineProbPermille,
            int cocoaProbPermille,
            int[] vineFaceSlots,
            int[] cocoaAgeFacingSlots
    ) {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TreeDecoratorSpec other)) {
                return false;
            }
            return this.trunkVine == other.trunkVine
                    && this.leaveVineProbPermille == other.leaveVineProbPermille
                    && this.cocoaProbPermille == other.cocoaProbPermille
                    && java.util.Arrays.equals(this.vineFaceSlots, other.vineFaceSlots)
                    && java.util.Arrays.equals(this.cocoaAgeFacingSlots, other.cocoaAgeFacingSlots);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + Boolean.hashCode(this.trunkVine);
            result = 31 * result + this.leaveVineProbPermille;
            result = 31 * result + this.cocoaProbPermille;
            result = 31 * result + java.util.Arrays.hashCode(this.vineFaceSlots);
            result = 31 * result + java.util.Arrays.hashCode(this.cocoaAgeFacingSlots);
            return result;
        }

        @Override
        public String toString() {
            return "TreeDecoratorSpec[trunkVine=" + this.trunkVine
                    + ", leaveVineProbPermille=" + this.leaveVineProbPermille
                    + ", cocoaProbPermille=" + this.cocoaProbPermille
                    + ", vineFaceSlots=" + java.util.Arrays.toString(this.vineFaceSlots)
                    + ", cocoaAgeFacingSlots=" + java.util.Arrays.toString(this.cocoaAgeFacingSlots)
                    + "]";
        }
    }

    /**
     * Composable pure-primitive AST node representing a lifted {@code ConfiguredFeature}.
     */
    public record LiftedFeatureNode(
            int kind,
            OreTargetRule[] oreTargets,
            int intA,
            int intB,
            int intC,
            int intD,
            int intE,
            boolean flagA,
            boolean flagB,
            boolean flagC,
            LiftedIntProvider intProvA,
            LiftedIntProvider intProvB,
            LiftedIntProvider intProvC,
            LiftedStateProvider stateProvA,
            LiftedStateProvider stateProvB,
            int[] auxSlotsA,
            int[] auxSlotsB,
            long[] slotMaskA,
            long[] slotMaskB,
            ColumnLayer[] columnLayers,
            SelectorBranch[] branches,
            int[] innerPlacementOps,
            LiftedIntProvider[] innerIntProviders,
            LiftedBlockPredicate[] innerPredicates,
            LiftedFeatureNode childFeature,
            TreeDecoratorSpec treeDecorators
    ) {
        public LiftedFeatureNode(
                int kind,
                OreTargetRule[] oreTargets,
                int intA,
                int intB,
                int intC,
                int intD,
                int intE,
                boolean flagA,
                boolean flagB,
                boolean flagC,
                LiftedIntProvider intProvA,
                LiftedIntProvider intProvB,
                LiftedIntProvider intProvC,
                LiftedStateProvider stateProvA,
                LiftedStateProvider stateProvB,
                int[] auxSlotsA,
                int[] auxSlotsB,
                long[] slotMaskA,
                long[] slotMaskB,
                ColumnLayer[] columnLayers,
                SelectorBranch[] branches,
                int[] innerPlacementOps,
                LiftedIntProvider[] innerIntProviders,
                LiftedBlockPredicate[] innerPredicates,
                LiftedFeatureNode childFeature
        ) {
            this(
                    kind,
                    oreTargets,
                    intA,
                    intB,
                    intC,
                    intD,
                    intE,
                    flagA,
                    flagB,
                    flagC,
                    intProvA,
                    intProvB,
                    intProvC,
                    stateProvA,
                    stateProvB,
                    auxSlotsA,
                    auxSlotsB,
                    slotMaskA,
                    slotMaskB,
                    columnLayers,
                    branches,
                    innerPlacementOps,
                    innerIntProviders,
                    innerPredicates,
                    childFeature,
                    null);
        }

        public static final LiftedFeatureNode NOOP = new LiftedFeatureNode(
                FN_NOOP,
                null,
                0,
                0,
                0,
                0,
                0,
                false,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                EMPTY_INT_ARRAY,
                EMPTY_INT_ARRAY,
                null,
                null,
                null,
                null,
                EMPTY_INT_ARRAY,
                null,
                null,
                null);

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof LiftedFeatureNode other)) {
                return false;
            }
            return this.kind == other.kind
                    && java.util.Arrays.equals(this.oreTargets, other.oreTargets)
                    && this.intA == other.intA
                    && this.intB == other.intB
                    && this.intC == other.intC
                    && this.intD == other.intD
                    && this.intE == other.intE
                    && this.flagA == other.flagA
                    && this.flagB == other.flagB
                    && this.flagC == other.flagC
                    && java.util.Objects.equals(this.intProvA, other.intProvA)
                    && java.util.Objects.equals(this.intProvB, other.intProvB)
                    && java.util.Objects.equals(this.intProvC, other.intProvC)
                    && java.util.Objects.equals(this.stateProvA, other.stateProvA)
                    && java.util.Objects.equals(this.stateProvB, other.stateProvB)
                    && java.util.Arrays.equals(this.auxSlotsA, other.auxSlotsA)
                    && java.util.Arrays.equals(this.auxSlotsB, other.auxSlotsB)
                    && java.util.Arrays.equals(this.slotMaskA, other.slotMaskA)
                    && java.util.Arrays.equals(this.slotMaskB, other.slotMaskB)
                    && java.util.Arrays.equals(this.columnLayers, other.columnLayers)
                    && java.util.Arrays.equals(this.branches, other.branches)
                    && java.util.Arrays.equals(this.innerPlacementOps, other.innerPlacementOps)
                    && java.util.Arrays.equals(this.innerIntProviders, other.innerIntProviders)
                    && java.util.Arrays.equals(this.innerPredicates, other.innerPredicates)
                    && java.util.Objects.equals(this.childFeature, other.childFeature)
                    && java.util.Objects.equals(this.treeDecorators, other.treeDecorators);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.kind;
            result = 31 * result + java.util.Arrays.hashCode(this.oreTargets);
            result = 31 * result + this.intA;
            result = 31 * result + this.intB;
            result = 31 * result + this.intC;
            result = 31 * result + this.intD;
            result = 31 * result + this.intE;
            result = 31 * result + Boolean.hashCode(this.flagA);
            result = 31 * result + Boolean.hashCode(this.flagB);
            result = 31 * result + Boolean.hashCode(this.flagC);
            result = 31 * result + java.util.Objects.hashCode(this.intProvA);
            result = 31 * result + java.util.Objects.hashCode(this.intProvB);
            result = 31 * result + java.util.Objects.hashCode(this.intProvC);
            result = 31 * result + java.util.Objects.hashCode(this.stateProvA);
            result = 31 * result + java.util.Objects.hashCode(this.stateProvB);
            result = 31 * result + java.util.Arrays.hashCode(this.auxSlotsA);
            result = 31 * result + java.util.Arrays.hashCode(this.auxSlotsB);
            result = 31 * result + java.util.Arrays.hashCode(this.slotMaskA);
            result = 31 * result + java.util.Arrays.hashCode(this.slotMaskB);
            result = 31 * result + java.util.Arrays.hashCode(this.columnLayers);
            result = 31 * result + java.util.Arrays.hashCode(this.branches);
            result = 31 * result + java.util.Arrays.hashCode(this.innerPlacementOps);
            result = 31 * result + java.util.Arrays.hashCode(this.innerIntProviders);
            result = 31 * result + java.util.Arrays.hashCode(this.innerPredicates);
            result = 31 * result + java.util.Objects.hashCode(this.childFeature);
            result = 31 * result + java.util.Objects.hashCode(this.treeDecorators);
            return result;
        }

        @Override
        public String toString() {
            return "LiftedFeatureNode[kind=" + this.kind
                    + ", oreTargets=" + java.util.Arrays.toString(this.oreTargets)
                    + ", intA=" + this.intA
                    + ", intB=" + this.intB
                    + ", intC=" + this.intC
                    + ", intD=" + this.intD
                    + ", intE=" + this.intE
                    + ", flagA=" + this.flagA
                    + ", flagB=" + this.flagB
                    + ", flagC=" + this.flagC
                    + ", intProvA=" + this.intProvA
                    + ", intProvB=" + this.intProvB
                    + ", intProvC=" + this.intProvC
                    + ", stateProvA=" + this.stateProvA
                    + ", stateProvB=" + this.stateProvB
                    + ", auxSlotsA=" + java.util.Arrays.toString(this.auxSlotsA)
                    + ", auxSlotsB=" + java.util.Arrays.toString(this.auxSlotsB)
                    + ", slotMaskA=" + java.util.Arrays.toString(this.slotMaskA)
                    + ", slotMaskB=" + java.util.Arrays.toString(this.slotMaskB)
                    + ", columnLayers=" + java.util.Arrays.toString(this.columnLayers)
                    + ", branches=" + java.util.Arrays.toString(this.branches)
                    + ", innerPlacementOps=" + java.util.Arrays.toString(this.innerPlacementOps)
                    + ", innerIntProviders=" + java.util.Arrays.toString(this.innerIntProviders)
                    + ", innerPredicates=" + java.util.Arrays.toString(this.innerPredicates)
                    + ", childFeature=" + this.childFeature
                    + ", treeDecorators=" + this.treeDecorators
                    + "]";
        }
    }

    /**
     * Deduplicated step-ordered {@code PlacedFeature} descriptor shared across biomes via {@code allowedBiomeMask}.
     */
    public record LiftedPlacedFeatureSpec(
            int stepIndex,
            int featureSalt,
            long[] allowedBiomeMask,
            int[] placementOps,
            LiftedIntProvider[] intProviders,
            LiftedBlockPredicate[] predicates,
            LiftedFeatureNode rootFeature,
            boolean hasTree
    ) {
        public LiftedPlacedFeatureSpec(
                int stepIndex,
                int featureSalt,
                long[] allowedBiomeMask,
                int[] placementOps,
                LiftedIntProvider[] intProviders,
                LiftedBlockPredicate[] predicates,
                LiftedFeatureNode rootFeature
        ) {
            this(
                    stepIndex,
                    featureSalt,
                    allowedBiomeMask,
                    placementOps,
                    intProviders,
                    predicates,
                    rootFeature,
                    containsTreeFeature(rootFeature, 0));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof LiftedPlacedFeatureSpec other)) {
                return false;
            }
            return this.stepIndex == other.stepIndex
                    && this.featureSalt == other.featureSalt
                    && java.util.Arrays.equals(this.allowedBiomeMask, other.allowedBiomeMask)
                    && java.util.Arrays.equals(this.placementOps, other.placementOps)
                    && java.util.Arrays.equals(this.intProviders, other.intProviders)
                    && java.util.Arrays.equals(this.predicates, other.predicates)
                    && java.util.Objects.equals(this.rootFeature, other.rootFeature)
                    && this.hasTree == other.hasTree;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.stepIndex;
            result = 31 * result + this.featureSalt;
            result = 31 * result + java.util.Arrays.hashCode(this.allowedBiomeMask);
            result = 31 * result + java.util.Arrays.hashCode(this.placementOps);
            result = 31 * result + java.util.Arrays.hashCode(this.intProviders);
            result = 31 * result + java.util.Arrays.hashCode(this.predicates);
            result = 31 * result + java.util.Objects.hashCode(this.rootFeature);
            result = 31 * result + Boolean.hashCode(this.hasTree);
            return result;
        }

        @Override
        public String toString() {
            return "LiftedPlacedFeatureSpec[stepIndex=" + this.stepIndex
                    + ", featureSalt=" + this.featureSalt
                    + ", allowedBiomeMask=" + java.util.Arrays.toString(this.allowedBiomeMask)
                    + ", placementOps=" + java.util.Arrays.toString(this.placementOps)
                    + ", intProviders=" + java.util.Arrays.toString(this.intProviders)
                    + ", predicates=" + java.util.Arrays.toString(this.predicates)
                    + ", rootFeature=" + this.rootFeature
                    + ", hasTree=" + this.hasTree
                    + "]";
        }
    }

    /**
     * Deterministic cave or canyon carver descriptor lifted from {@code BiomeGenerationSettings},
     * carrying Mojang's runtime {@code WorldCarver#getRange()} chunk reach.
     */
    public record CarverSpec(
            int kind,
            int biomeOrdinal,
            int probabilityPermille,
            int minY,
            int maxY,
            int lavaLevelY,
            int horizScalePermille,
            int vertScalePermille,
            int rangeChunks
    ) {
        public CarverSpec(
                int kind,
                int biomeOrdinal,
                int probabilityPermille,
                int minY,
                int maxY,
                int lavaLevelY,
                int horizScalePermille,
                int vertScalePermille
        ) {
            this(
                    kind,
                    biomeOrdinal,
                    probabilityPermille,
                    minY,
                    maxY,
                    lavaLevelY,
                    horizScalePermille,
                    vertScalePermille,
                    4);
        }
    }

    /**
     * Complete pure-primitive capability descriptor for generating chunks in any noise-capable dimension.
     */
    public record DimensionCapabilitySpec(
            int chunkMinY,
            int chunkSections,
            int noiseMinY,
            int noiseHeight,
            int cellWidth,
            int cellHeight,
            int seaLevel,
            int defaultBlockSlot,
            int defaultFluidSlot,
            boolean aquifersEnabled,
            int lavaFluidSlot,
            int deepLavaFloorY,
            int[] flatRTreeNodes,
            BytecodeConceptIr.ConceptModule surfaceRuleModule,
            CarverSpec[] carverSpecs,
            BiomeFeatureSpec[] featureSpecs,
            OreVeinSpec[] oreSpecs,
            RouterVeinSpec routerVeinSpec,
            GpuRouterNoiseTable gpuNoiseTable,
            LiftedPlacedFeatureSpec[] placedFeatureSpecs,
            long[] nonMotionBlockingSlotMask,
            BytecodeConceptIr.ConceptModule finalDensityModule
    ) {
        private static final LiftedPlacedFeatureSpec[] EMPTY_PLACED_SPECS = new LiftedPlacedFeatureSpec[0];
        private static final long[] EMPTY_SLOT_MASK = new long[0];

        public DimensionCapabilitySpec {
            if (routerVeinSpec == null) {
                routerVeinSpec = RouterVeinSpec.DISABLED;
            }
            if (gpuNoiseTable == null) {
                gpuNoiseTable = GpuRouterNoiseTable.EMPTY;
            }
            if (placedFeatureSpecs == null) {
                placedFeatureSpecs = EMPTY_PLACED_SPECS;
            }
            if (nonMotionBlockingSlotMask == null) {
                nonMotionBlockingSlotMask = EMPTY_SLOT_MASK;
            }
        }

        public boolean hasSurfaceRuleModule() {
            return surfaceRuleModule != null
                    && surfaceRuleModule.entryFunction() != null
                    && surfaceRuleModule.isPureCompute()
                    && surfaceRuleModule.unmappedReasons().isEmpty();
        }

        public boolean hasFinalDensityModule() {
            return finalDensityModule != null
                    && finalDensityModule.entryFunction() != null
                    && finalDensityModule.isPureCompute()
                    && finalDensityModule.unmappedReasons().isEmpty();
        }

        public DimensionCapabilitySpec(
                int chunkMinY,
                int chunkSections,
                int noiseMinY,
                int noiseHeight,
                int cellWidth,
                int cellHeight,
                int seaLevel,
                int defaultBlockSlot,
                int defaultFluidSlot,
                boolean aquifersEnabled,
                int lavaFluidSlot,
                int deepLavaFloorY,
                int[] flatRTreeNodes,
                BytecodeConceptIr.ConceptModule surfaceRuleModule,
                CarverSpec[] carverSpecs,
                BiomeFeatureSpec[] featureSpecs,
                OreVeinSpec[] oreSpecs,
                RouterVeinSpec routerVeinSpec,
                GpuRouterNoiseTable gpuNoiseTable,
                LiftedPlacedFeatureSpec[] placedFeatureSpecs,
                long[] nonMotionBlockingSlotMask
        ) {
            this(
                    chunkMinY,
                    chunkSections,
                    noiseMinY,
                    noiseHeight,
                    cellWidth,
                    cellHeight,
                    seaLevel,
                    defaultBlockSlot,
                    defaultFluidSlot,
                    aquifersEnabled,
                    lavaFluidSlot,
                    deepLavaFloorY,
                    flatRTreeNodes,
                    surfaceRuleModule,
                    carverSpecs,
                    featureSpecs,
                    oreSpecs,
                    routerVeinSpec,
                    gpuNoiseTable,
                    placedFeatureSpecs,
                    nonMotionBlockingSlotMask,
                    null);
        }

        public DimensionCapabilitySpec(
                int chunkMinY,
                int chunkSections,
                int noiseMinY,
                int noiseHeight,
                int cellWidth,
                int cellHeight,
                int seaLevel,
                int defaultBlockSlot,
                int defaultFluidSlot,
                boolean aquifersEnabled,
                int lavaFluidSlot,
                int deepLavaFloorY,
                int[] flatRTreeNodes,
                BytecodeConceptIr.ConceptModule surfaceRuleModule,
                CarverSpec[] carverSpecs,
                BiomeFeatureSpec[] featureSpecs,
                OreVeinSpec[] oreSpecs,
                RouterVeinSpec routerVeinSpec,
                GpuRouterNoiseTable gpuNoiseTable,
                LiftedPlacedFeatureSpec[] placedFeatureSpecs
        ) {
            this(
                    chunkMinY,
                    chunkSections,
                    noiseMinY,
                    noiseHeight,
                    cellWidth,
                    cellHeight,
                    seaLevel,
                    defaultBlockSlot,
                    defaultFluidSlot,
                    aquifersEnabled,
                    lavaFluidSlot,
                    deepLavaFloorY,
                    flatRTreeNodes,
                    surfaceRuleModule,
                    carverSpecs,
                    featureSpecs,
                    oreSpecs,
                    routerVeinSpec,
                    gpuNoiseTable,
                    placedFeatureSpecs,
                    EMPTY_SLOT_MASK);
        }

        public DimensionCapabilitySpec(
                int chunkMinY,
                int chunkSections,
                int noiseMinY,
                int noiseHeight,
                int cellWidth,
                int cellHeight,
                int seaLevel,
                int defaultBlockSlot,
                int defaultFluidSlot,
                boolean aquifersEnabled,
                int lavaFluidSlot,
                int deepLavaFloorY,
                int[] flatRTreeNodes,
                BytecodeConceptIr.ConceptModule surfaceRuleModule,
                CarverSpec[] carverSpecs,
                BiomeFeatureSpec[] featureSpecs,
                OreVeinSpec[] oreSpecs,
                RouterVeinSpec routerVeinSpec,
                GpuRouterNoiseTable gpuNoiseTable
        ) {
            this(
                    chunkMinY,
                    chunkSections,
                    noiseMinY,
                    noiseHeight,
                    cellWidth,
                    cellHeight,
                    seaLevel,
                    defaultBlockSlot,
                    defaultFluidSlot,
                    aquifersEnabled,
                    lavaFluidSlot,
                    deepLavaFloorY,
                    flatRTreeNodes,
                    surfaceRuleModule,
                    carverSpecs,
                    featureSpecs,
                    oreSpecs,
                    routerVeinSpec,
                    gpuNoiseTable,
                    EMPTY_PLACED_SPECS);
        }

        public DimensionCapabilitySpec(
                int chunkMinY,
                int chunkSections,
                int noiseMinY,
                int noiseHeight,
                int cellWidth,
                int cellHeight,
                int seaLevel,
                int defaultBlockSlot,
                int defaultFluidSlot,
                boolean aquifersEnabled,
                int lavaFluidSlot,
                int deepLavaFloorY,
                int[] flatRTreeNodes,
                BytecodeConceptIr.ConceptModule surfaceRuleModule,
                CarverSpec[] carverSpecs,
                BiomeFeatureSpec[] featureSpecs,
                OreVeinSpec[] oreSpecs,
                RouterVeinSpec routerVeinSpec
        ) {
            this(
                    chunkMinY,
                    chunkSections,
                    noiseMinY,
                    noiseHeight,
                    cellWidth,
                    cellHeight,
                    seaLevel,
                    defaultBlockSlot,
                    defaultFluidSlot,
                    aquifersEnabled,
                    lavaFluidSlot,
                    deepLavaFloorY,
                    flatRTreeNodes,
                    surfaceRuleModule,
                    carverSpecs,
                    featureSpecs,
                    oreSpecs,
                    routerVeinSpec,
                    GpuRouterNoiseTable.EMPTY);
        }

        public DimensionCapabilitySpec(
                int chunkMinY,
                int chunkSections,
                int noiseMinY,
                int noiseHeight,
                int cellWidth,
                int cellHeight,
                int seaLevel,
                int defaultBlockSlot,
                int defaultFluidSlot,
                boolean aquifersEnabled,
                int lavaFluidSlot,
                int deepLavaFloorY,
                int[] flatRTreeNodes,
                BytecodeConceptIr.ConceptModule surfaceRuleModule,
                CarverSpec[] carverSpecs,
                BiomeFeatureSpec[] featureSpecs,
                OreVeinSpec[] oreSpecs
        ) {
            this(
                    chunkMinY,
                    chunkSections,
                    noiseMinY,
                    noiseHeight,
                    cellWidth,
                    cellHeight,
                    seaLevel,
                    defaultBlockSlot,
                    defaultFluidSlot,
                    aquifersEnabled,
                    lavaFluidSlot,
                    deepLavaFloorY,
                    flatRTreeNodes,
                    surfaceRuleModule,
                    carverSpecs,
                    featureSpecs,
                    oreSpecs,
                    RouterVeinSpec.DISABLED);
        }

        public DimensionCapabilitySpec(
                int chunkMinY,
                int chunkSections,
                int noiseMinY,
                int noiseHeight,
                int seaLevel,
                int defaultBlockSlot,
                int defaultFluidSlot,
                int[] flatRTreeNodes,
                BytecodeConceptIr.ConceptModule surfaceRuleModule,
                BiomeFeatureSpec[] featureSpecs,
                OreVeinSpec[] oreSpecs
        ) {
            this(
                    chunkMinY,
                    chunkSections,
                    noiseMinY,
                    noiseHeight,
                    4,
                    8,
                    seaLevel,
                    defaultBlockSlot,
                    defaultFluidSlot,
                    false,
                    0,
                    noiseMinY - 1,
                    flatRTreeNodes,
                    surfaceRuleModule,
                    new CarverSpec[0],
                    featureSpecs,
                    oreSpecs,
                    RouterVeinSpec.DISABLED);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DimensionCapabilitySpec other)) {
                return false;
            }
            return this.chunkMinY == other.chunkMinY
                    && this.chunkSections == other.chunkSections
                    && this.noiseMinY == other.noiseMinY
                    && this.noiseHeight == other.noiseHeight
                    && this.cellWidth == other.cellWidth
                    && this.cellHeight == other.cellHeight
                    && this.seaLevel == other.seaLevel
                    && this.defaultBlockSlot == other.defaultBlockSlot
                    && this.defaultFluidSlot == other.defaultFluidSlot
                    && this.aquifersEnabled == other.aquifersEnabled
                    && this.lavaFluidSlot == other.lavaFluidSlot
                    && this.deepLavaFloorY == other.deepLavaFloorY
                    && java.util.Arrays.equals(this.flatRTreeNodes, other.flatRTreeNodes)
                    && java.util.Objects.equals(this.surfaceRuleModule, other.surfaceRuleModule)
                    && java.util.Arrays.equals(this.carverSpecs, other.carverSpecs)
                    && java.util.Arrays.equals(this.featureSpecs, other.featureSpecs)
                    && java.util.Arrays.equals(this.oreSpecs, other.oreSpecs)
                    && java.util.Objects.equals(this.routerVeinSpec, other.routerVeinSpec)
                    && java.util.Objects.equals(this.gpuNoiseTable, other.gpuNoiseTable)
                    && java.util.Arrays.equals(this.placedFeatureSpecs, other.placedFeatureSpecs)
                    && java.util.Arrays.equals(this.nonMotionBlockingSlotMask, other.nonMotionBlockingSlotMask)
                    && java.util.Objects.equals(this.finalDensityModule, other.finalDensityModule);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.chunkMinY;
            result = 31 * result + this.chunkSections;
            result = 31 * result + this.noiseMinY;
            result = 31 * result + this.noiseHeight;
            result = 31 * result + this.cellWidth;
            result = 31 * result + this.cellHeight;
            result = 31 * result + this.seaLevel;
            result = 31 * result + this.defaultBlockSlot;
            result = 31 * result + this.defaultFluidSlot;
            result = 31 * result + Boolean.hashCode(this.aquifersEnabled);
            result = 31 * result + this.lavaFluidSlot;
            result = 31 * result + this.deepLavaFloorY;
            result = 31 * result + java.util.Arrays.hashCode(this.flatRTreeNodes);
            result = 31 * result + java.util.Objects.hashCode(this.surfaceRuleModule);
            result = 31 * result + java.util.Arrays.hashCode(this.carverSpecs);
            result = 31 * result + java.util.Arrays.hashCode(this.featureSpecs);
            result = 31 * result + java.util.Arrays.hashCode(this.oreSpecs);
            result = 31 * result + java.util.Objects.hashCode(this.routerVeinSpec);
            result = 31 * result + java.util.Objects.hashCode(this.gpuNoiseTable);
            result = 31 * result + java.util.Arrays.hashCode(this.placedFeatureSpecs);
            result = 31 * result + java.util.Arrays.hashCode(this.nonMotionBlockingSlotMask);
            result = 31 * result + java.util.Objects.hashCode(this.finalDensityModule);
            return result;
        }

        @Override
        public String toString() {
            return "DimensionCapabilitySpec[chunkMinY=" + this.chunkMinY
                    + ", chunkSections=" + this.chunkSections
                    + ", noiseMinY=" + this.noiseMinY
                    + ", noiseHeight=" + this.noiseHeight
                    + ", cellWidth=" + this.cellWidth
                    + ", cellHeight=" + this.cellHeight
                    + ", seaLevel=" + this.seaLevel
                    + ", defaultBlockSlot=" + this.defaultBlockSlot
                    + ", defaultFluidSlot=" + this.defaultFluidSlot
                    + ", aquifersEnabled=" + this.aquifersEnabled
                    + ", lavaFluidSlot=" + this.lavaFluidSlot
                    + ", deepLavaFloorY=" + this.deepLavaFloorY
                    + ", flatRTreeNodes=" + java.util.Arrays.toString(this.flatRTreeNodes)
                    + ", surfaceRuleModule=" + this.surfaceRuleModule
                    + ", carverSpecs=" + java.util.Arrays.toString(this.carverSpecs)
                    + ", featureSpecs=" + java.util.Arrays.toString(this.featureSpecs)
                    + ", oreSpecs=" + java.util.Arrays.toString(this.oreSpecs)
                    + ", routerVeinSpec=" + this.routerVeinSpec
                    + ", gpuNoiseTable=" + this.gpuNoiseTable
                    + ", placedFeatureSpecs=" + java.util.Arrays.toString(this.placedFeatureSpecs)
                    + ", nonMotionBlockingSlotMask=" + java.util.Arrays.toString(this.nonMotionBlockingSlotMask)
                    + ", finalDensityModule=" + this.finalDensityModule
                    + "]";
        }
    }

    public static int quantizeClimateCoord(float value) {
        return (int) (value * CLIMATE_QUANT_SCALE);
    }

    /**
     * Reference brute-force quantized 7D squared-L2 nearest-box lookup matching Mojang's
     * {@code Climate.ParameterList} semantics (lowest entry index wins on exact distance tie).
     */
    public static int bruteForceNearestBiomeOrdinal(
            List<BiomeParameterBox> entries,
            float temp,
            float humid,
            float cont,
            float erosion,
            float depth,
            float weirdness
    ) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        int[] q = {
            quantizeClimateCoord(temp),
            quantizeClimateCoord(humid),
            quantizeClimateCoord(cont),
            quantizeClimateCoord(erosion),
            quantizeClimateCoord(depth),
            quantizeClimateCoord(weirdness),
            0
        };
        long bestDist = Long.MAX_VALUE;
        int bestOrd = entries.get(0).biomeOrdinal();
        for (int i = 0; i < entries.size(); i++) {
            BiomeParameterBox box = entries.get(i);
            long dSq = boxDistanceSq7(q, box.mins7, box.maxs7);
            if (dSq < bestDist) {
                bestDist = dSq;
                bestOrd = box.biomeOrdinal();
            }
        }
        return bestOrd;
    }

    private static long boxDistanceSq7(int[] q7, long[] mins7, long[] maxs7) {
        long sum = 0L;
        for (int d = 0; d < 7; d++) {
            long q = q7[d];
            long gap = 0L;
            if (q < mins7[d]) {
                gap = mins7[d] - q;
            } else if (q > maxs7[d]) {
                gap = q - maxs7[d];
            }
            sum += gap * gap;
        }
        return sum;
    }

    private record IndexedBox(BiomeParameterBox box, int entryOrder) {}

    private static final class BvhBuildNode {
        final int[] mins = new int[7];
        final int[] maxs = new int[7];
        final List<BvhBuildNode> children;
        final int leafBiomeOrd;
        final int leafEntryOrder;

        BvhBuildNode(IndexedBox leaf) {
            for (int d = 0; d < 7; d++) {
                this.mins[d] = clampInt(leaf.box.mins7[d]);
                this.maxs[d] = clampInt(leaf.box.maxs7[d]);
            }
            this.children = List.of();
            this.leafBiomeOrd = leaf.box.biomeOrdinal();
            this.leafEntryOrder = leaf.entryOrder;
        }

        BvhBuildNode(List<BvhBuildNode> children) {
            this.children = children;
            Arrays.fill(this.mins, Integer.MAX_VALUE);
            Arrays.fill(this.maxs, Integer.MIN_VALUE);
            for (BvhBuildNode ch : children) {
                for (int d = 0; d < 7; d++) {
                    if (ch.mins[d] < this.mins[d]) this.mins[d] = ch.mins[d];
                    if (ch.maxs[d] > this.maxs[d]) this.maxs[d] = ch.maxs[d];
                }
            }
            this.leafBiomeOrd = -1;
            this.leafEntryOrder = -1;
        }

        boolean isLeaf() {
            return children.isEmpty();
        }
    }

    private static int clampInt(long v) {
        if (v < Integer.MIN_VALUE / 2) return Integer.MIN_VALUE / 2;
        if (v > Integer.MAX_VALUE / 2) return Integer.MAX_VALUE / 2;
        return (int) v;
    }

    /**
     * Compiles arbitrary runtime {@link BiomeParameterBox} entries into a flat, Contiguous-Child
     * 16-int BVH buffer suitable for stackless/bounded-stack CPU and OpenCL evaluation.
     *
     * <p>Node layout ({@code base = nodeIdx * 16}):
     * <ul>
     *   <li>{@code [0]}: {@code firstChildNodeIdx} (internal) or {@code biomeOrdinal} (leaf)</li>
     *   <li>{@code [1]}: {@code childCount > 0} (internal) or {@code -1 - entryOrder <= -1} (leaf)</li>
     *   <li>{@code [2..8]}: quantized {@code min0..min6}</li>
     *   <li>{@code [9..15]}: quantized {@code max0..max6}</li>
     * </ul>
     */
    public static int[] buildFlatClimateRTree(List<BiomeParameterBox> entries) {
        if (entries == null || entries.isEmpty()) {
            int[] single = new int[RTREE_NODE_STRIDE];
            single[0] = 0;
            single[1] = -1;
            return single;
        }
        List<IndexedBox> indexed = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            indexed.add(new IndexedBox(entries.get(i), i));
        }
        BvhBuildNode root = buildBvhRecursive(indexed);

        // Flatten in BFS / contiguous-children order so children of any internal node occupy [first..first+cnt-1]
        List<BvhBuildNode> flat = new ArrayList<>(indexed.size() * 2);
        flat.add(root);
        int[] childStarts = new int[indexed.size() * 4 + 8];
        for (int cursor = 0; cursor < flat.size(); cursor++) {
            BvhBuildNode cur = flat.get(cursor);
            if (!cur.isLeaf()) {
                if (cursor >= childStarts.length) {
                    childStarts = Arrays.copyOf(childStarts, childStarts.length * 2);
                }
                childStarts[cursor] = flat.size();
                flat.addAll(cur.children);
            }
        }

        int[] out = new int[flat.size() * RTREE_NODE_STRIDE];
        for (int idx = 0; idx < flat.size(); idx++) {
            BvhBuildNode node = flat.get(idx);
            int base = idx * RTREE_NODE_STRIDE;
            if (node.isLeaf()) {
                out[base] = node.leafBiomeOrd;
                out[base + 1] = -1 - node.leafEntryOrder;
            } else {
                out[base] = childStarts[idx];
                out[base + 1] = node.children.size();
            }
            for (int d = 0; d < 7; d++) {
                out[base + 2 + d] = node.mins[d];
                out[base + 9 + d] = node.maxs[d];
            }
        }
        return out;
    }

    private static BvhBuildNode buildBvhRecursive(List<IndexedBox> slice) {
        if (slice.size() == 1) {
            return new BvhBuildNode(slice.get(0));
        }
        if (slice.size() <= MAX_BVH_FANOUT) {
            List<BvhBuildNode> leaves = new ArrayList<>(slice.size());
            for (IndexedBox item : slice) {
                leaves.add(new BvhBuildNode(item));
            }
            return new BvhBuildNode(leaves);
        }
        long[] globalMin = new long[7];
        long[] globalMax = new long[7];
        Arrays.fill(globalMin, Long.MAX_VALUE);
        Arrays.fill(globalMax, Long.MIN_VALUE);
        for (IndexedBox ib : slice) {
            for (int d = 0; d < 7; d++) {
                long mid = (ib.box.mins7[d] + ib.box.maxs7[d]) >> 1;
                if (mid < globalMin[d]) globalMin[d] = mid;
                if (mid > globalMax[d]) globalMax[d] = mid;
            }
        }
        int splitAxis = 0;
        long bestSpan = -1L;
        for (int d = 0; d < 7; d++) {
            long span = globalMax[d] - globalMin[d];
            if (span > bestSpan) {
                bestSpan = span;
                splitAxis = d;
            }
        }
        final int axis = splitAxis;
        List<IndexedBox> sorted = new ArrayList<>(slice);
        sorted.sort(Comparator.<IndexedBox>comparingLong(
                        a -> (a.box.mins7[axis] + a.box.maxs7[axis]))
                .thenComparingInt(a -> a.entryOrder));

        int groupCount = Math.min(MAX_BVH_FANOUT, sorted.size());
        List<BvhBuildNode> children = new ArrayList<>(groupCount);
        for (int g = 0; g < groupCount; g++) {
            int from = (g * sorted.size()) / groupCount;
            int to = ((g + 1) * sorted.size()) / groupCount;
            if (from < to) {
                children.add(buildBvhRecursive(sorted.subList(from, to)));
            }
        }
        return new BvhBuildNode(children);
    }

    /**
     * Searches a flat 16-int {@code Climate.RTree} BVH buffer for the nearest biome ordinal in 7D
     * quantized climate space with zero heap allocations on an inline stack.
     */
    public static int searchRTreeBiomeOrdinal(
            int[] rtreeNodes,
            float temp,
            float humid,
            float cont,
            float erosion,
            float depth,
            float weirdness
    ) {
        return searchRTreeBiomeOrdinalQuantized(
                rtreeNodes,
                quantizeClimateCoord(temp),
                quantizeClimateCoord(humid),
                quantizeClimateCoord(cont),
                quantizeClimateCoord(erosion),
                quantizeClimateCoord(depth),
                quantizeClimateCoord(weirdness));
    }

    public static final class RTreeDepthPartition {
        public final int groupCount;
        public final int[] groupMinD;
        public final int[] groupMaxD;
        public final int[][] groupSubTrees;
        public final long[][] groupInteriorDisjointMasks;

        RTreeDepthPartition(
                int groupCount,
                int[] groupMinD,
                int[] groupMaxD,
                int[][] groupSubTrees,
                long[][] groupInteriorDisjointMasks
        ) {
            this.groupCount = groupCount;
            this.groupMinD = groupMinD;
            this.groupMaxD = groupMaxD;
            this.groupSubTrees = groupSubTrees;
            this.groupInteriorDisjointMasks = groupInteriorDisjointMasks;
        }
    }

    private record RTreeIdentityKey(int[] nodes) {
        @Override
        public boolean equals(Object o) {
            return o instanceof RTreeIdentityKey other && this.nodes == other.nodes;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(nodes);
        }

        @Override
        public String toString() {
            return "RTreeIdentityKey[nodes=" + java.util.Arrays.toString(this.nodes)
                    + "]";
        }
    }

    private record OrderedLeaf6d(int biomeOrd, int encodedNegOrder, int[] mins7, int[] maxs7) {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof OrderedLeaf6d other)) {
                return false;
            }
            return this.biomeOrd == other.biomeOrd
                    && this.encodedNegOrder == other.encodedNegOrder
                    && java.util.Arrays.equals(this.mins7, other.mins7)
                    && java.util.Arrays.equals(this.maxs7, other.maxs7);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.biomeOrd;
            result = 31 * result + this.encodedNegOrder;
            result = 31 * result + java.util.Arrays.hashCode(this.mins7);
            result = 31 * result + java.util.Arrays.hashCode(this.maxs7);
            return result;
        }

        @Override
        public String toString() {
            return "OrderedLeaf6d[biomeOrd=" + this.biomeOrd
                    + ", encodedNegOrder=" + this.encodedNegOrder
                    + ", mins7=" + java.util.Arrays.toString(this.mins7)
                    + ", maxs7=" + java.util.Arrays.toString(this.maxs7)
                    + "]";
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<RTreeIdentityKey, RTreeDepthPartition>
            RTREE_DEPTH_PARTITION_MEMO = new java.util.concurrent.ConcurrentHashMap<>();

    public static RTreeDepthPartition ensureRTreeDepthPartition(int[] rtreeNodes) {
        if (rtreeNodes == null || rtreeNodes.length < RTREE_NODE_STRIDE) {
            return null;
        }
        RTreeIdentityKey memoKey = new RTreeIdentityKey(rtreeNodes);
        RTreeDepthPartition cached = RTREE_DEPTH_PARTITION_MEMO.get(memoKey);
        if (cached != null) {
            return cached.groupCount > 0 ? cached : null;
        }
        int nodeCount = rtreeNodes.length / RTREE_NODE_STRIDE;
        java.util.Map<Long, java.util.List<OrderedLeaf6d>> byDepthInterval = new java.util.LinkedHashMap<>();
        for (int idx = 0; idx < nodeCount; idx++) {
            int base = idx * RTREE_NODE_STRIDE;
            int w1 = rtreeNodes[base + 1];
            if (w1 > 0) {
                continue;
            }
            int minD = rtreeNodes[base + 6];
            int maxD = rtreeNodes[base + 13];
            long key = (((long) minD) << 32) ^ (maxD & 0xFFFF_FFFFL);
            java.util.List<OrderedLeaf6d> bucket = byDepthInterval.get(key);
            if (bucket == null) {
                bucket = new java.util.ArrayList<>();
                byDepthInterval.put(key, bucket);
            }
            if (byDepthInterval.size() > 8) {
                RTREE_DEPTH_PARTITION_MEMO.put(
                        memoKey, new RTreeDepthPartition(0, new int[0], new int[0], new int[0][], new long[0][]));
                return null;
            }
            int[] mins7 = new int[7];
            int[] maxs7 = new int[7];
            for (int d = 0; d < 7; d++) {
                mins7[d] = (d == 4) ? 0 : rtreeNodes[base + 2 + d];
                maxs7[d] = (d == 4) ? 0 : rtreeNodes[base + 9 + d];
            }
            bucket.add(new OrderedLeaf6d(rtreeNodes[base], w1, mins7, maxs7));
        }
        int gCount = byDepthInterval.size();
        if (gCount == 0 || gCount > 8) {
            RTREE_DEPTH_PARTITION_MEMO.put(
                    memoKey, new RTreeDepthPartition(0, new int[0], new int[0], new int[0][], new long[0][]));
            return null;
        }
        int[] minDs = new int[gCount];
        int[] maxDs = new int[gCount];
        int[][] subTrees = new int[gCount][];
        long[][] masks = new long[gCount][];
        int gi = 0;
        for (var entry : byDepthInterval.entrySet()) {
            long key = entry.getKey();
            minDs[gi] = (int) (key >> 32);
            maxDs[gi] = (int) key;
            subTrees[gi] = buildOrdered6dSubRTree(entry.getValue());
            masks[gi] = computeInteriorDisjointLeafMask(subTrees[gi]);
            gi++;
        }
        RTreeDepthPartition part = new RTreeDepthPartition(gCount, minDs, maxDs, subTrees, masks);
        if (RTREE_DEPTH_PARTITION_MEMO.size() >= 32) {
            RTREE_DEPTH_PARTITION_MEMO.clear();
        }
        RTREE_DEPTH_PARTITION_MEMO.put(memoKey, part);
        return part;
    }

    private static int[] buildOrdered6dSubRTree(java.util.List<OrderedLeaf6d> leaves) {
        record BuildNode(int[] mins7, int[] maxs7, OrderedLeaf6d leaf, java.util.List<BuildNode> children) {
            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (!(o instanceof BuildNode other)) {
                    return false;
                }
                return java.util.Arrays.equals(this.mins7, other.mins7)
                        && java.util.Arrays.equals(this.maxs7, other.maxs7)
                        && java.util.Objects.equals(this.leaf, other.leaf)
                        && java.util.Objects.equals(this.children, other.children);
            }

            @Override
            public int hashCode() {
                int result = 1;
                result = 31 * result + java.util.Arrays.hashCode(this.mins7);
                result = 31 * result + java.util.Arrays.hashCode(this.maxs7);
                result = 31 * result + java.util.Objects.hashCode(this.leaf);
                result = 31 * result + java.util.Objects.hashCode(this.children);
                return result;
            }

            @Override
            public String toString() {
                return "BuildNode[mins7=" + java.util.Arrays.toString(this.mins7)
                        + ", maxs7=" + java.util.Arrays.toString(this.maxs7)
                        + ", leaf=" + this.leaf
                        + ", children=" + this.children
                        + "]";
            }
        }
        java.util.function.Function<java.util.List<OrderedLeaf6d>, BuildNode> buildRec =
                new java.util.function.Function<>() {
                    @Override
                    public BuildNode apply(java.util.List<OrderedLeaf6d> slice) {
                        if (slice.size() == 1) {
                            OrderedLeaf6d lf = slice.get(0);
                            return new BuildNode(lf.mins7.clone(), lf.maxs7.clone(), lf, java.util.List.of());
                        }
                        if (slice.size() <= 6) {
                            java.util.List<BuildNode> ch = new java.util.ArrayList<>(slice.size());
                            int[] uMin = slice.get(0).mins7.clone();
                            int[] uMax = slice.get(0).maxs7.clone();
                            for (OrderedLeaf6d lf : slice) {
                                ch.add(new BuildNode(lf.mins7.clone(), lf.maxs7.clone(), lf, java.util.List.of()));
                                for (int d = 0; d < 7; d++) {
                                    if (lf.mins7[d] < uMin[d]) uMin[d] = lf.mins7[d];
                                    if (lf.maxs7[d] > uMax[d]) uMax[d] = lf.maxs7[d];
                                }
                            }
                            return new BuildNode(uMin, uMax, null, ch);
                        }
                        int bestAxis = 0;
                        long bestSpan = -1L;
                        for (int d = 0; d < 6; d++) {
                            if (d == 4) continue;
                            int lo = Integer.MAX_VALUE;
                            int hi = Integer.MIN_VALUE;
                            for (OrderedLeaf6d lf : slice) {
                                int mid = (lf.mins7[d] + lf.maxs7[d]) >> 1;
                                if (mid < lo) lo = mid;
                                if (mid > hi) hi = mid;
                            }
                            long sp = (long) hi - (long) lo;
                            if (sp > bestSpan) {
                                bestSpan = sp;
                                bestAxis = d;
                            }
                        }
                        final int sortAxis = bestAxis;
                        java.util.List<OrderedLeaf6d> sorted = new java.util.ArrayList<>(slice);
                        sorted.sort(java.util.Comparator.comparingInt(
                                a -> (a.mins7[sortAxis] + a.maxs7[sortAxis]) >> 1));
                        int fanout = Math.min(6, sorted.size());
                        java.util.List<BuildNode> ch = new java.util.ArrayList<>(fanout);
                        for (int b = 0; b < fanout; b++) {
                            int s = (b * sorted.size()) / fanout;
                            int e = ((b + 1) * sorted.size()) / fanout;
                            if (s < e) {
                                ch.add(this.apply(sorted.subList(s, e)));
                            }
                        }
                        int[] uMin = ch.get(0).mins7.clone();
                        int[] uMax = ch.get(0).maxs7.clone();
                        for (int ci = 1; ci < ch.size(); ci++) {
                            BuildNode cn = ch.get(ci);
                            for (int d = 0; d < 7; d++) {
                                if (cn.mins7[d] < uMin[d]) uMin[d] = cn.mins7[d];
                                if (cn.maxs7[d] > uMax[d]) uMax[d] = cn.maxs7[d];
                            }
                        }
                        return new BuildNode(uMin, uMax, null, ch);
                    }
                };
        BuildNode root = buildRec.apply(leaves);
        java.util.List<BuildNode> bfs = new java.util.ArrayList<>();
        bfs.add(root);
        int[] childStart = new int[leaves.size() * 2 + 8];
        for (int head = 0; head < bfs.size(); head++) {
            BuildNode cur = bfs.get(head);
            if (cur.leaf == null) {
                if (head >= childStart.length) {
                    childStart = Arrays.copyOf(childStart, childStart.length << 1);
                }
                childStart[head] = bfs.size();
                bfs.addAll(cur.children);
            }
        }
        int[] flat = new int[bfs.size() * RTREE_NODE_STRIDE];
        for (int idx = 0; idx < bfs.size(); idx++) {
            BuildNode cur = bfs.get(idx);
            int base = idx * RTREE_NODE_STRIDE;
            if (cur.leaf != null) {
                flat[base] = cur.leaf.biomeOrd;
                flat[base + 1] = cur.leaf.encodedNegOrder;
            } else {
                flat[base] = childStart[idx];
                flat[base + 1] = cur.children.size();
            }
            for (int d = 0; d < 7; d++) {
                flat[base + 2 + d] = cur.mins7[d];
                flat[base + 9 + d] = cur.maxs7[d];
            }
        }
        return flat;
    }

    private static long[] computeInteriorDisjointLeafMask(int[] rtreeNodes) {
        int nodeCount = rtreeNodes.length / RTREE_NODE_STRIDE;
        long[] mask = new long[(nodeCount + 63) >>> 6];
        int[] stack = new int[64];
        for (int idx = 0; idx < nodeCount; idx++) {
            int base = idx * RTREE_NODE_STRIDE;
            int w1 = rtreeNodes[base + 1];
            if (w1 > 0) continue;
            int myOrder = -1 - w1;
            boolean overlapsEarlier = false;
            stack[0] = 0;
            int sp = 1;
            while (sp > 0 && !overlapsEarlier) {
                int nIdx = stack[--sp];
                if (nIdx < 0 || nIdx >= nodeCount || nIdx == idx) continue;
                int nBase = nIdx * RTREE_NODE_STRIDE;
                int nw1 = rtreeNodes[nBase + 1];
                if (nw1 <= 0) {
                    if ((-1 - nw1) < myOrder && leafStrictInteriorTouchesBox7(rtreeNodes, base, nBase)) {
                        overlapsEarlier = true;
                        break;
                    }
                } else if (boxesOverlap7(rtreeNodes, base, nBase)) {
                    int firstChild = rtreeNodes[nBase];
                    for (int ci = 0; ci < nw1 && sp < stack.length; ci++) {
                        stack[sp++] = firstChild + ci;
                    }
                }
            }
            if (!overlapsEarlier) {
                mask[idx >>> 6] |= (1L << (idx & 63));
            }
        }
        return mask;
    }

    private static boolean leafStrictInteriorTouchesBox7(int[] nodes, int leafBase, int otherBase) {
        for (int d = 0; d < 7; d++) {
            int lMin = nodes[leafBase + 2 + d];
            int lMax = nodes[leafBase + 9 + d];
            int oMin = nodes[otherBase + 2 + d];
            int oMax = nodes[otherBase + 9 + d];
            if (lMin < lMax) {
                if (oMax <= lMin || oMin >= lMax) {
                    return false;
                }
            } else {
                if (oMax < lMin || oMin > lMax) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isPointStrictlyInsideLeaf7(
            int[] nodes, int base, int q0, int q1, int q2, int q3, int q4, int q5) {
        int[] qs = {q0, q1, q2, q3, q4, q5, 0};
        for (int d = 0; d < 7; d++) {
            int lo = nodes[base + 2 + d];
            int hi = nodes[base + 9 + d];
            int q = qs[d];
            if (lo < hi) {
                if (q <= lo || q >= hi) return false;
            } else {
                if (q != lo) return false;
            }
        }
        return true;
    }

    public static int searchSubRTree6dHinted(
            int[] rtreeNodes,
            long[] interiorDisjointMask,
            int qt,
            int qh,
            int qc,
            int qe,
            int qw,
            int[] ioHintLeaf,
            int slotIdx,
            int[] stackScratch64,
            long[] distScratch64,
            long[] outDistAndOrder2
    ) {
        int nodeCount = rtreeNodes.length / RTREE_NODE_STRIDE;
        long bestDist = Long.MAX_VALUE;
        int bestOrder = Integer.MAX_VALUE;
        int bestBiomeOrd = 0;
        int bestLeaf = (ioHintLeaf != null && slotIdx < ioHintLeaf.length) ? ioHintLeaf[slotIdx] : -1;
        if (bestLeaf >= 0 && bestLeaf < nodeCount) {
            int hBase = bestLeaf * RTREE_NODE_STRIDE;
            int hw1 = rtreeNodes[hBase + 1];
            if (hw1 <= 0) {
                bestDist = nodeDistSq7(rtreeNodes, hBase, qt, qh, qc, qe, 0, qw);
                bestOrder = -1 - hw1;
                bestBiomeOrd = rtreeNodes[hBase];
                if (bestDist == 0L
                        && interiorDisjointMask != null
                        && (bestLeaf >>> 6) < interiorDisjointMask.length
                        && ((interiorDisjointMask[bestLeaf >>> 6] >>> (bestLeaf & 63)) & 1L) != 0L
                        && isPointStrictlyInsideLeaf7(rtreeNodes, hBase, qt, qh, qc, qe, 0, qw)) {
                    outDistAndOrder2[0] = 0L;
                    outDistAndOrder2[1] = bestOrder;
                    return bestBiomeOrd;
                }
            }
        }
        long rootDist = nodeDistSq7(rtreeNodes, 0, qt, qh, qc, qe, 0, qw);
        if (rootDist <= bestDist) {
            stackScratch64[0] = 0;
            distScratch64[0] = rootDist;
            int sp = 1;
            while (sp > 0) {
                sp--;
                int nodeIdx = stackScratch64[sp];
                long nodeDist = distScratch64[sp];
                if (nodeDist > bestDist || nodeIdx < 0 || nodeIdx >= nodeCount) continue;
                int base = nodeIdx * RTREE_NODE_STRIDE;
                int w0 = rtreeNodes[base];
                int w1 = rtreeNodes[base + 1];
                if (w1 <= 0) {
                    int entryOrder = -1 - w1;
                    if (nodeDist < bestDist || (nodeDist == bestDist && entryOrder < bestOrder)) {
                        bestDist = nodeDist;
                        bestOrder = entryOrder;
                        bestBiomeOrd = w0;
                        bestLeaf = nodeIdx;
                    }
                    continue;
                }
                int startSp = sp;
                for (int i = 0; i < w1; i++) {
                    int cIdx = w0 + i;
                    if (cIdx < 0 || cIdx >= nodeCount) continue;
                    long cDist = nodeDistSq7(rtreeNodes, cIdx * RTREE_NODE_STRIDE, qt, qh, qc, qe, 0, qw);
                    if (cDist <= bestDist && sp < stackScratch64.length) {
                        stackScratch64[sp] = cIdx;
                        distScratch64[sp] = cDist;
                        sp++;
                    }
                }
                for (int i = startSp + 1; i < sp; i++) {
                    int kIdx = stackScratch64[i];
                    long kDist = distScratch64[i];
                    int j = i - 1;
                    while (j >= startSp && distScratch64[j] < kDist) {
                        stackScratch64[j + 1] = stackScratch64[j];
                        distScratch64[j + 1] = distScratch64[j];
                        j--;
                    }
                    stackScratch64[j + 1] = kIdx;
                    distScratch64[j + 1] = kDist;
                }
            }
        }
        if (ioHintLeaf != null && slotIdx < ioHintLeaf.length) {
            ioHintLeaf[slotIdx] = bestLeaf;
        }
        outDistAndOrder2[0] = bestDist;
        outDistAndOrder2[1] = bestOrder;
        return bestBiomeOrd;
    }

    public static long depthAxisGapSq(int q4, int minD, int maxD) {
        long g = axisGap(q4, minD, maxD);
        return g * g;
    }

    private static final java.util.concurrent.ConcurrentHashMap<RTreeIdentityKey, long[]> RTREE_DISJOINT_LEAF_MEMO =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static long[] ensureRTreeDisjointLeafMask(int[] rtreeNodes) {
        if (rtreeNodes == null || rtreeNodes.length < RTREE_NODE_STRIDE) {
            return new long[0];
        }
        RTreeIdentityKey memoKey = new RTreeIdentityKey(rtreeNodes);
        long[] cached = RTREE_DISJOINT_LEAF_MEMO.get(memoKey);
        if (cached != null) {
            return cached;
        }
        int nodeCount = rtreeNodes.length / RTREE_NODE_STRIDE;
        long[] mask = new long[(nodeCount + 63) >>> 6];
        int[] stack = new int[64];
        for (int idx = 0; idx < nodeCount; idx++) {
            int base = idx * RTREE_NODE_STRIDE;
            int w1 = rtreeNodes[base + 1];
            if (w1 > 0) {
                continue;
            }
            int myOrder = -1 - w1;
            if (myOrder == 0) {
                mask[idx >>> 6] |= (1L << (idx & 63));
                continue;
            }
            boolean overlapsEarlier = false;
            stack[0] = 0;
            int sp = 1;
            while (sp > 0 && !overlapsEarlier) {
                int nIdx = stack[--sp];
                if (nIdx < 0 || nIdx >= nodeCount || nIdx == idx) {
                    continue;
                }
                int nBase = nIdx * RTREE_NODE_STRIDE;
                if (!boxesOverlap7(rtreeNodes, base, nBase)) {
                    continue;
                }
                int nw1 = rtreeNodes[nBase + 1];
                if (nw1 <= 0) {
                    int otherOrder = -1 - nw1;
                    if (otherOrder < myOrder) {
                        overlapsEarlier = true;
                        break;
                    }
                } else {
                    int firstChild = rtreeNodes[nBase];
                    for (int ci = 0; ci < nw1 && sp < stack.length; ci++) {
                        stack[sp++] = firstChild + ci;
                    }
                }
            }
            if (!overlapsEarlier) {
                mask[idx >>> 6] |= (1L << (idx & 63));
            }
        }
        if (RTREE_DISJOINT_LEAF_MEMO.size() >= 32) {
            RTREE_DISJOINT_LEAF_MEMO.clear();
        }
        RTREE_DISJOINT_LEAF_MEMO.put(memoKey, mask);
        return mask;
    }

    private static boolean boxesOverlap7(int[] nodes, int baseA, int baseB) {
        for (int d = 0; d < 7; d++) {
            if (nodes[baseA + 9 + d] < nodes[baseB + 2 + d] || nodes[baseB + 9 + d] < nodes[baseA + 2 + d]) {
                return false;
            }
        }
        return true;
    }

    public static int searchRTreeBiomeOrdinalQuantized(
            int[] rtreeNodes, int qt, int qh, int qc, int qe, int qd, int qw) {
        return searchRTreeBiomeOrdinalQuantizedHinted(rtreeNodes, null, qt, qh, qc, qe, qd, qw, null, null, null);
    }

    public static int searchRTreeBiomeOrdinalQuantizedHinted(
            int[] rtreeNodes,
            int qt,
            int qh,
            int qc,
            int qe,
            int qd,
            int qw,
            int[] ioHintLeaf,
            int[] stackScratch32,
            long[] distScratch32
    ) {
        return searchRTreeBiomeOrdinalQuantizedHinted(
                rtreeNodes,
                ensureRTreeDisjointLeafMask(rtreeNodes),
                qt,
                qh,
                qc,
                qe,
                qd,
                qw,
                ioHintLeaf,
                stackScratch32,
                distScratch32);
    }

    public static int searchRTreeBiomeOrdinalQuantizedHinted(
            int[] rtreeNodes,
            long[] disjointLeafMask,
            int qt,
            int qh,
            int qc,
            int qe,
            int qd,
            int qw,
            int[] ioHintLeaf,
            int[] stackScratch32,
            long[] distScratch32
    ) {
        if (rtreeNodes == null || rtreeNodes.length < RTREE_NODE_STRIDE) {
            return 0;
        }
        int nodeCount = rtreeNodes.length / RTREE_NODE_STRIDE;
        long bestDist = Long.MAX_VALUE;
        int bestOrder = Integer.MAX_VALUE;
        int bestBiomeOrd = 0;
        int bestLeaf = (ioHintLeaf != null && ioHintLeaf.length > 0) ? ioHintLeaf[0] : -1;
        if (bestLeaf >= 0 && bestLeaf < nodeCount) {
            int hBase = bestLeaf * RTREE_NODE_STRIDE;
            int hw1 = rtreeNodes[hBase + 1];
            if (hw1 <= 0) {
                bestDist = nodeDistSq7(rtreeNodes, hBase, qt, qh, qc, qe, qd, qw);
                bestOrder = -1 - hw1;
                bestBiomeOrd = rtreeNodes[hBase];
                if (bestDist == 0L) {
                    boolean disjoint = bestOrder == 0
                            || (disjointLeafMask != null
                                    && (bestLeaf >>> 6) < disjointLeafMask.length
                                    && ((disjointLeafMask[bestLeaf >>> 6] >>> (bestLeaf & 63)) & 1L) != 0L);
                    if (disjoint) {
                        return bestBiomeOrd;
                    }
                }
            }
        }
        long rootDist = nodeDistSq7(rtreeNodes, 0, qt, qh, qc, qe, qd, qw);
        if (rootDist > bestDist) {
            return bestBiomeOrd;
        }
        int[] stack = (stackScratch32 != null && stackScratch32.length >= 64) ? stackScratch32 : new int[64];
        long[] distStack = (distScratch32 != null && distScratch32.length >= 64) ? distScratch32 : new long[64];
        stack[0] = 0;
        distStack[0] = rootDist;
        int sp = 1;

        while (sp > 0) {
            sp--;
            int nodeIdx = stack[sp];
            long nodeDist = distStack[sp];
            if (nodeDist > bestDist || nodeIdx < 0 || nodeIdx >= nodeCount) {
                continue;
            }
            int base = nodeIdx * RTREE_NODE_STRIDE;
            int w0 = rtreeNodes[base];
            int w1 = rtreeNodes[base + 1];
            if (w1 <= 0) {
                int entryOrder = -1 - w1;
                if (nodeDist < bestDist || (nodeDist == bestDist && entryOrder < bestOrder)) {
                    bestDist = nodeDist;
                    bestOrder = entryOrder;
                    bestBiomeOrd = w0;
                    bestLeaf = nodeIdx;
                }
                continue;
            }
            int firstChild = w0;
            int childCount = w1;
            int startSp = sp;
            for (int i = 0; i < childCount; i++) {
                int cIdx = firstChild + i;
                if (cIdx < 0 || cIdx >= nodeCount) {
                    continue;
                }
                long cDist = nodeDistSq7(rtreeNodes, cIdx * RTREE_NODE_STRIDE, qt, qh, qc, qe, qd, qw);
                if (cDist <= bestDist && sp < stack.length) {
                    stack[sp] = cIdx;
                    distStack[sp] = cDist;
                    sp++;
                }
            }
            for (int i = startSp + 1; i < sp; i++) {
                int kIdx = stack[i];
                long kDist = distStack[i];
                int j = i - 1;
                while (j >= startSp && distStack[j] < kDist) {
                    stack[j + 1] = stack[j];
                    distStack[j + 1] = distStack[j];
                    j--;
                }
                stack[j + 1] = kIdx;
                distStack[j + 1] = kDist;
            }
        }
        if (ioHintLeaf != null && ioHintLeaf.length > 0) {
            ioHintLeaf[0] = bestLeaf;
        }
        return bestBiomeOrd;
    }

    private static long nodeDistSq7(
            int[] nodes, int base, int q0, int q1, int q2, int q3, int q4, int q5) {
        long d0 = axisGap(q0, nodes[base + 2], nodes[base + 9]);
        long d1 = axisGap(q1, nodes[base + 3], nodes[base + 10]);
        long d2 = axisGap(q2, nodes[base + 4], nodes[base + 11]);
        long d3 = axisGap(q3, nodes[base + 5], nodes[base + 12]);
        long d4 = axisGap(q4, nodes[base + 6], nodes[base + 13]);
        long d5 = axisGap(q5, nodes[base + 7], nodes[base + 14]);
        long d6 = axisGap(0, nodes[base + 8], nodes[base + 15]);
        return d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3 + d4 * d4 + d5 * d5 + d6 * d6;
    }

    private static long axisGap(int q, int lo, int hi) {
        if (q < lo) return (long) lo - q;
        if (q > hi) return (long) q - hi;
        return 0L;
    }

    /**
     * Lifted Mojang 2D {@code SimplexNoise} + {@code EndIslandDensityFunction} evaluator supporting
     * both direct permutation-table snapshots from live {@code EndIslandDensityFunction} instances
     * and deterministic {@code LegacyRandomSource(seed) + consumeCount(17292)} initialization.
     */
    public static final class LiftedEndIslandSampler {
        private static final double SQRT_3 = Math.sqrt(3.0);
        private static final double F2 = 0.5 * (SQRT_3 - 1.0);
        private static final double G2 = (3.0 - SQRT_3) / 6.0;
        private static final int[][] GRAD3 = {
            {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
            {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
            {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1},
            {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}
        };

        private final int[] p = new int[512];

        private LiftedEndIslandSampler(int[] perm256) {
            for (int i = 0; i < 512; i++) {
                this.p[i] = perm256[i & 255] & 255;
            }
        }

        public static LiftedEndIslandSampler fromPermutation(int[] perm) {
            Objects.requireNonNull(perm, "perm");
            if (perm.length < 256) {
                throw new IllegalArgumentException("Simplex permutation requires >= 256 entries");
            }
            return new LiftedEndIslandSampler(perm);
        }

        public static LiftedEndIslandSampler fromSeed(long worldSeed) {
            long state = (worldSeed ^ 0x5DEECE66DL) & ((1L << 48) - 1);
            // Mojang LegacyRandomSource#consumeCount(17292) before SimplexNoise(RandomSource)
            for (int i = 0; i < 17292; i++) {
                state = (state * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
            }
            // Skip xo, yo, zo (3 * nextDouble = 6 * next(26/27))
            for (int i = 0; i < 6; i++) {
                state = (state * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
            }
            int[] perm = new int[256];
            for (int i = 0; i < 256; i++) {
                perm[i] = i;
            }
            for (int i = 0; i < 256; i++) {
                int bound = 256 - i;
                int j;
                if ((bound & -bound) == bound) {
                    state = (state * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
                    j = (int) ((bound * (state >>> 17)) >> 31);
                } else {
                    int bits;
                    int val;
                    do {
                        state = (state * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
                        bits = (int) (state >>> 17);
                        val = bits % bound;
                    } while (bits - val + (bound - 1) < 0);
                    j = val;
                }
                int swapIdx = i + j;
                int tmp = perm[i];
                perm[i] = perm[swapIdx];
                perm[swapIdx] = tmp;
            }
            return new LiftedEndIslandSampler(perm);
        }

        public double simplex2D(double xin, double yin) {
            double s = (xin + yin) * F2;
            int i = fastFloor(xin + s);
            int j = fastFloor(yin + s);
            double t = (i + j) * G2;
            double x0 = xin - (i - t);
            double y0 = yin - (j - t);
            int i1 = x0 > y0 ? 1 : 0;
            int j1 = x0 > y0 ? 0 : 1;
            double x1 = x0 - i1 + G2;
            double y1 = y0 - j1 + G2;
            double x2 = x0 - 1.0 + 2.0 * G2;
            double y2 = y0 - 1.0 + 2.0 * G2;

            int ii = i & 255;
            int jj = j & 255;
            int gi0 = p[ii + p[jj]] % 12;
            int gi1 = p[ii + i1 + p[jj + j1]] % 12;
            int gi2 = p[ii + 1 + p[jj + 1]] % 12;

            double n0 = cornerContrib2D(gi0, x0, y0);
            double n1 = cornerContrib2D(gi1, x1, y1);
            double n2 = cornerContrib2D(gi2, x2, y2);
            return 70.0 * (n0 + n1 + n2);
        }

        private static double cornerContrib2D(int gi, double x, double y) {
            double t = 0.5 - x * x - y * y;
            if (t < 0.0) {
                return 0.0;
            }
            t *= t;
            int[] g = GRAD3[gi];
            return t * t * (g[0] * x + g[1] * y);
        }

        private static int fastFloor(double x) {
            int xi = (int) x;
            return x < xi ? xi - 1 : xi;
        }

        /**
         * Evaluates Mojang's {@code EndIslandDensityFunction.getHeightValue} normalized to
         * {@code (heightValue - 8.0f) / 128.0f}.
         */
        public float sample(int blockX, int blockZ) {
            return (heightValueAtGrid(blockX >> 3, blockZ >> 3) - 8.0f) * (1.0f / 128.0f);
        }

        public float heightValueAtGrid(int gx, int gz) {
            int hx = gx / 2;
            int hz = gz / 2;
            int ox = gx % 2;
            int oz = gz % 2;
            float f = 100.0f - (float) Math.sqrt((double) gx * gx + (double) gz * gz) * 8.0f;
            f = clampFloat(f, -100.0f, 80.0f);

            for (int dx = -12; dx <= 12; dx++) {
                for (int dz = -12; dz <= 12; dz++) {
                    long nx = (long) hx + dx;
                    long nz = (long) hz + dz;
                    if (nx * nx + nz * nz > 4096L && simplex2D((double) nx, (double) nz) < -0.9) {
                        float r = (float) ((Math.abs(nx) * 3439L + Math.abs(nz) * 147L) % 13L + 9L);
                        float lx = (float) (ox - dx * 2);
                        float lz = (float) (oz - dz * 2);
                        float cand = 100.0f - (float) Math.sqrt(lx * lx + lz * lz) * r;
                        cand = clampFloat(cand, -100.0f, 80.0f);
                        if (cand > f) {
                            f = cand;
                        }
                    }
                }
            }
            return f;
        }

        private static float clampFloat(float v, float lo, float hi) {
            return v < lo ? lo : (Math.min(v, hi));
        }
    }

    /**
     * Evaluates a nested Mojang {@code CubicSpline} lifted from runtime {@code NoiseRouter} graphs.
     */
    public sealed interface LiftedSpline permits ConstantSpline, MultipointSpline {
        float compute(int bx, int by, int bz);

        default void sampleColumnY(
                int bx, int baseY, int stepY, int countY, int bz, float[] outColumn, int outOffset) {
            for (int i = 0; i < countY; i++) {
                outColumn[outOffset + i] = compute(bx, baseY + i * stepY, bz);
            }
        }
    }

    public record ConstantSpline(float value) implements LiftedSpline {
        @Override
        public float compute(int bx, int by, int bz) {
            return value;
        }

        @Override
        public void sampleColumnY(
                int bx, int baseY, int stepY, int countY, int bz, float[] outColumn, int outOffset) {
            if (countY > 0) {
                Arrays.fill(outColumn, outOffset, outOffset + countY, value);
            }
        }
    }

    public record MultipointSpline(
            DensityFieldEvaluator coordinate,
            float[] locations,
            LiftedSpline[] values,
            float[] derivatives
    ) implements LiftedSpline {
        @Override
        public float compute(int bx, int by, int bz) {
            float x = coordinate.sample(bx, by, bz);
            int n = locations.length;
            if (n == 0) {
                return 0.0f;
            }
            int i = findIntervalBefore(locations, x);
            if (i < 0) {
                return values[0].compute(bx, by, bz) + derivatives[0] * (x - locations[0]);
            }
            if (i >= n - 1) {
                return values[n - 1].compute(bx, by, bz) + derivatives[n - 1] * (x - locations[n - 1]);
            }
            float x0 = locations[i];
            float x1 = locations[i + 1];
            float span = x1 - x0;
            if (span <= 1.0e-7f) {
                return values[i].compute(bx, by, bz);
            }
            float t = (x - x0) / span;
            float y0 = values[i].compute(bx, by, bz);
            float y1 = values[i + 1].compute(bx, by, bz);
            float d0 = derivatives[i];
            float d1 = derivatives[i + 1];
            float a = d0 * span - (y1 - y0);
            float b = -d1 * span + (y1 - y0);
            return y0 + t * (y1 - y0) + t * (1.0f - t) * (a * (1.0f - t) + b * t);
        }

        @Override
        public void sampleColumnY(
                int bx, int baseY, int stepY, int countY, int bz, float[] outColumn, int outOffset) {
            sampleColumnYAtDepth(bx, baseY, stepY, countY, bz, outColumn, outOffset, 0);
        }

        private void sampleColumnYAtDepth(
                int bx,
                int baseY,
                int stepY,
                int countY,
                int bz,
                float[] outColumn,
                int outOffset,
                int splineDepth
        ) {
            if (countY <= 0) {
                return;
            }
            int n = locations.length;
            if (n == 0) {
                Arrays.fill(outColumn, outOffset, outOffset + countY, 0.0f);
                return;
            }
            if (coordinate.isColumnConstantY() && splineDepth < 14) {
                float x = coordinate.sample(bx, 0, bz);
                int i = findIntervalBefore(locations, x);
                if (i < 0) {
                    evalChildColumn(values[0], bx, baseY, stepY, countY, bz, outColumn, outOffset, splineDepth + 1);
                    float slopeTerm = derivatives[0] * (x - locations[0]);
                    if (slopeTerm != 0.0f) {
                        for (int yi = 0; yi < countY; yi++) {
                            outColumn[outOffset + yi] += slopeTerm;
                        }
                    }
                    return;
                }
                if (i >= n - 1) {
                    evalChildColumn(values[n - 1], bx, baseY, stepY, countY, bz, outColumn, outOffset, splineDepth + 1);
                    float slopeTerm = derivatives[n - 1] * (x - locations[n - 1]);
                    if (slopeTerm != 0.0f) {
                        for (int yi = 0; yi < countY; yi++) {
                            outColumn[outOffset + yi] += slopeTerm;
                        }
                    }
                    return;
                }
                float x0 = locations[i];
                float x1 = locations[i + 1];
                float span = x1 - x0;
                if (span <= 1.0e-7f) {
                    evalChildColumn(values[i], bx, baseY, stepY, countY, bz, outColumn, outOffset, splineDepth + 1);
                    return;
                }
                float t = (x - x0) / span;
                float omt = 1.0f - t;
                float tOmt = t * omt;
                float d0Span = derivatives[i] * span;
                float negD1Span = -derivatives[i + 1] * span;
                float[] lhs = new float[countY];
                float[] rhs = new float[countY];
                evalChildColumn(values[i], bx, baseY, stepY, countY, bz, lhs, 0, splineDepth + 1);
                evalChildColumn(values[i + 1], bx, baseY, stepY, countY, bz, rhs, 0, splineDepth + 1);
                if (outOffset == 0) {
                    blendHermiteColumnsZeroOffFp32(lhs, rhs, outColumn, t, omt, tOmt, d0Span, negD1Span, countY);
                } else {
                    blendHermiteColumnsOffsetFp32(
                            lhs, rhs, outColumn, outOffset, t, omt, tOmt, d0Span, negD1Span, countY);
                }
                return;
            }
            if (splineDepth < 14) {
                float[] coordCol = new float[countY];
                coordinate.sampleColumnY(bx, baseY, stepY, countY, bz, coordCol, 0);
                for (int yi = 0; yi < countY; yi++) {
                    int wy = baseY + yi * stepY;
                    float x = coordCol[yi];
                    int i = findIntervalBefore(locations, x);
                    if (i < 0) {
                        outColumn[outOffset + yi] = values[0].compute(bx, wy, bz) + derivatives[0] * (x - locations[0]);
                    } else if (i >= n - 1) {
                        outColumn[outOffset + yi] =
                                values[n - 1].compute(bx, wy, bz) + derivatives[n - 1] * (x - locations[n - 1]);
                    } else {
                        float x0 = locations[i];
                        float x1 = locations[i + 1];
                        float span = x1 - x0;
                        if (span <= 1.0e-7f) {
                            outColumn[outOffset + yi] = values[i].compute(bx, wy, bz);
                        } else {
                            float t = (x - x0) / span;
                            float y0 = values[i].compute(bx, wy, bz);
                            float y1 = values[i + 1].compute(bx, wy, bz);
                            float a = derivatives[i] * span - (y1 - y0);
                            float b = -derivatives[i + 1] * span + (y1 - y0);
                            outColumn[outOffset + yi] =
                                    y0 + t * (y1 - y0) + t * (1.0f - t) * (a * (1.0f - t) + b * t);
                        }
                    }
                }
                return;
            }
            for (int yi = 0; yi < countY; yi++) {
                outColumn[outOffset + yi] = compute(bx, baseY + yi * stepY, bz);
            }
        }

        private static void evalChildColumn(
                LiftedSpline child,
                int bx,
                int baseY,
                int stepY,
                int countY,
                int bz,
                float[] out,
                int outOff,
                int nextDepth
        ) {
            if (child instanceof MultipointSpline mp) {
                mp.sampleColumnYAtDepth(bx, baseY, stepY, countY, bz, out, outOff, nextDepth);
            } else {
                child.sampleColumnY(bx, baseY, stepY, countY, bz, out, outOff);
            }
        }

        private static void blendHermiteColumnsZeroOffFp32(
                float[] lhs,
                float[] rhs,
                float[] dst,
                float t,
                float omt,
                float tOmt,
                float d0Span,
                float negD1Span,
                int countY
        ) {
            if (dst == lhs || dst == rhs) {
                return;
            }
            for (int yi = 0; yi < countY; yi++) {
                float y0 = lhs[yi];
                float y1 = rhs[yi];
                float dy = y1 - y0;
                float a = d0Span - dy;
                float b = negD1Span + dy;
                dst[yi] = y0 + t * dy + tOmt * (a * omt + b * t);
            }
        }

        private static void blendHermiteColumnsOffsetFp32(
                float[] lhs,
                float[] rhs,
                float[] dst,
                int outOffset,
                float t,
                float omt,
                float tOmt,
                float d0Span,
                float negD1Span,
                int countY
        ) {
            if (dst == lhs || dst == rhs) {
                return;
            }
            for (int yi = 0; yi < countY; yi++) {
                float y0 = lhs[yi];
                float y1 = rhs[yi];
                float dy = y1 - y0;
                float a = d0Span - dy;
                float b = negD1Span + dy;
                dst[outOffset + yi] = y0 + t * dy + tOmt * (a * omt + b * t);
            }
        }

        private static int findIntervalBefore(float[] locs, float x) {
            int lo = 0;
            int hi = locs.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (x < locs[mid]) {
                    hi = mid;
                } else {
                    lo = mid + 1;
                }
            }
            return lo - 1;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MultipointSpline other)) {
                return false;
            }
            return java.util.Objects.equals(this.coordinate, other.coordinate)
                    && java.util.Arrays.equals(this.locations, other.locations)
                    && java.util.Arrays.equals(this.values, other.values)
                    && java.util.Arrays.equals(this.derivatives, other.derivatives);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + java.util.Objects.hashCode(this.coordinate);
            result = 31 * result + java.util.Arrays.hashCode(this.locations);
            result = 31 * result + java.util.Arrays.hashCode(this.values);
            result = 31 * result + java.util.Arrays.hashCode(this.derivatives);
            return result;
        }

        @Override
        public String toString() {
            return "MultipointSpline[coordinate=" + this.coordinate
                    + ", locations=" + java.util.Arrays.toString(this.locations)
                    + ", values=" + java.util.Arrays.toString(this.values)
                    + ", derivatives=" + java.util.Arrays.toString(this.derivatives)
                    + "]";
        }
    }

    /**
     * Evaluates a compiled {@link JavaConceptBackendEmitter.SurfaceRuleEvaluator} produced from a
     * lifted {@link BytecodeConceptIr.ConceptModule} at a solid voxel.
     *
     * @return replacement dynamic palette slot ID, or {@code currentSlot} when no rule matches
     */
    public static int evaluateSurfaceRuleModule(
            JavaConceptBackendEmitter.SurfaceRuleEvaluator evaluator,
            int currentSlot,
            int defaultBlockSlot,
            int blockX,
            int blockY,
            int blockZ,
            int stoneDepthAbove,
            int stoneDepthBelow,
            int waterHeight,
            int surfaceDepth,
            int biomeId,
            int prelimSurfaceY,
            int steep,
            int surfaceGradientX,
            int surfaceGradientZ,
            double surfaceSecondary,
            float temperature
    ) {
        if (evaluator == null || currentSlot != defaultBlockSlot) {
            return currentSlot;
        }
        int res = evaluator.evaluate(
                blockX,
                blockY,
                blockZ,
                stoneDepthAbove,
                stoneDepthBelow,
                waterHeight,
                surfaceDepth,
                biomeId,
                prelimSurfaceY,
                steep,
                surfaceGradientX,
                surfaceGradientZ,
                surfaceSecondary,
                temperature);
        return res > 0 ? res : currentSlot;
    }

    /**
     * Resolves column preliminary surface Y from lifted {@code NoiseRouter#initialDensityWithoutJaggedness}
     * (slot {@code 10}) using Mojang's {@code 0.390625f} density threshold, falling back to {@code fallbackTopSolidY}
     * when slot {@code 10} is absent.
     */
    public static int computePreliminarySurfaceY(
            DimensionCapabilitySpec spec,
            DensityFieldEvaluator prelimDensityEval,
            int bx,
            int bz,
            int fallbackTopSolidY
    ) {
        return computePreliminarySurfaceY(
                spec,
                prelimDensityEval,
                bx,
                bz,
                fallbackTopSolidY,
                WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE);
    }

    public static int computePreliminarySurfaceY(
            DimensionCapabilitySpec spec,
            DensityFieldEvaluator prelimDensityEval,
            int bx,
            int bz,
            int fallbackTopSolidY,
            WorldgenKernelSwapper.WorldgenFidelityPolicy fidelityPolicy
    ) {
        if (prelimDensityEval == null || spec == null) {
            return fallbackTopSolidY;
        }
        int minY = spec.noiseMinY();
        int topY = minY + spec.noiseHeight() - 1;
        int directY = prelimDensityEval.resolveDirectSurfaceY(bx, bz);
        if (directY != Integer.MIN_VALUE) {
            return Math.max(minY - 1, Math.min(topY, directY));
        }
        int step = Math.max(1, spec.cellHeight());
        int scanCount = Math.max(1, ((topY - minY) / step) + 1);
        int firstScanWy = topY - (scanCount - 1) * step;
        if (fidelityPolicy != null && fidelityPolicy.isExact()) {
            double[] sc64 = new double[Math.max(scanCount, step)];
            prelimDensityEval.sampleColumnYFp64(bx, firstScanWy, step, scanCount, bz, sc64, 0);
            for (int si = scanCount - 1; si >= 0; si--) {
                if (sc64[si] > 0.390625) {
                    int wy = firstScanWy + si * step;
                    int refineTop = Math.min(topY, wy + step - 1);
                    int refineLen = refineTop - wy + 1;
                    if (refineLen > 1) {
                        prelimDensityEval.sampleColumnYFp64(bx, wy, 1, refineLen, bz, sc64, 0);
                        for (int ri = refineLen - 1; ri >= 0; ri--) {
                            if (sc64[ri] > 0.390625) {
                                return wy + ri;
                            }
                        }
                    }
                    return wy;
                }
            }
            double minSample = firstScanWy == minY ? sc64[0] : prelimDensityEval.sampleFp64(bx, minY, bz);
            return minSample > 0.390625 ? minY : (minY - 1);
        }
        float[] sc = new float[Math.max(scanCount, step)];
        prelimDensityEval.sampleColumnY(bx, firstScanWy, step, scanCount, bz, sc, 0);
        for (int si = scanCount - 1; si >= 0; si--) {
            if (sc[si] > 0.390625f) {
                int wy = firstScanWy + si * step;
                int refineTop = Math.min(topY, wy + step - 1);
                int refineLen = refineTop - wy + 1;
                if (refineLen > 1) {
                    prelimDensityEval.sampleColumnY(bx, wy, 1, refineLen, bz, sc, 0);
                    for (int ri = refineLen - 1; ri >= 0; ri--) {
                        if (sc[ri] > 0.390625f) {
                            return wy + ri;
                        }
                    }
                }
                return wy;
            }
        }
        float minSample = firstScanWy == minY ? sc[0] : prelimDensityEval.sample(bx, minY, bz);
        if (minSample > 0.390625f) {
            return minY;
        }
        return minY - 1;
    }

    /**
     * Evaluates 3D {@code NoiseRouter} ore vein channels ({@code veinToggle}, {@code veinRidged},
     * {@code veinGap}, slots {@code 12..14}) at a solid terrain voxel.
     */
    public static int evaluateRouterVeinVoxel(
            RouterVeinSpec veinSpec,
            RouterCapabilityChannels channels,
            int bx,
            int wy,
            int bz,
            int currentSlot,
            int defaultBlockSlot,
            int posHash
    ) {
        if (veinSpec == null
                || !veinSpec.enabled()
                || channels == null
                || currentSlot != defaultBlockSlot
                || channels.veinToggle() == null
                || channels.veinRidged() == null
                || channels.veinGap() == null) {
            return currentSlot;
        }
        boolean inUpperBand = wy >= veinSpec.upperMinY() && wy <= veinSpec.upperMaxY();
        boolean inLowerBand = wy >= veinSpec.lowerMinY() && wy <= veinSpec.lowerMaxY();
        if (!inUpperBand && !inLowerBand) {
            return currentSlot;
        }
        float toggle = channels.veinToggle().sample(bx, wy, bz);
        float absToggle = Math.abs(toggle);
        if (absToggle <= 0.4f) {
            return currentSlot;
        }
        int oreSlot;
        int rawSlot;
        int fillerSlot;
        if (toggle > 0.4f) {
            if (!inUpperBand) {
                return currentSlot;
            }
            oreSlot = veinSpec.upperOreSlot();
            rawSlot = veinSpec.upperRawBlockSlot();
            fillerSlot = veinSpec.upperFillerSlot();
        } else {
            if (!inLowerBand) {
                return currentSlot;
            }
            oreSlot = veinSpec.lowerOreSlot();
            rawSlot = veinSpec.lowerRawBlockSlot();
            fillerSlot = veinSpec.lowerFillerSlot();
        }
        if (channels.veinRidged().sample(bx, wy, bz) >= 0.0f) {
            return currentSlot;
        }
        float gap = channels.veinGap().sample(bx, wy, bz);
        if (Math.abs(gap) > 0.3f && oreSlot > 0) {
            float richness = Math.min(1.0f, Math.max(0.0f, (absToggle - 0.4f) * 5.0f));
            if ((posHash & 255) < (int) (48.0f + richness * 180.0f)) {
                return (rawSlot > 0 && richness > 0.8f && ((posHash >>> 8) & 31) == 0) ? rawSlot : oreSlot;
            }
        }
        return fillerSlot > 0 ? fillerSlot : currentSlot;
    }

    private static boolean routerVeinOverlapsRange(RouterVeinSpec veinSpec, int minWy, int maxWy) {
        if (veinSpec == null || !veinSpec.enabled()) {
            return false;
        }
        return (maxWy >= veinSpec.upperMinY() && minWy <= veinSpec.upperMaxY())
                || (maxWy >= veinSpec.lowerMinY() && minWy <= veinSpec.lowerMaxY());
    }

    /**
     * Evaluates symmetric non-cascading 1-ring hydrostatic containment on a dry-air ({@code 0}) voxel,
     * converting it into {@code defaultBlockSlot} iff {@code aboveSlot} or any horizontal neighbor holds fluid.
     */
    public static int evaluateHydrostaticSealVoxel(
            int curSlot,
            int aboveSlot,
            int nx0Slot,
            int nx1Slot,
            int nz0Slot,
            int nz1Slot,
            int defaultBlockSlot,
            int defaultFluidSlot,
            int lavaFluidSlot
    ) {
        if (curSlot != 0) {
            return curSlot;
        }
        boolean af = (defaultFluidSlot > 0 && aboveSlot == defaultFluidSlot)
                || (lavaFluidSlot > 0 && aboveSlot == lavaFluidSlot);
        boolean x0f = (defaultFluidSlot > 0 && nx0Slot == defaultFluidSlot)
                || (lavaFluidSlot > 0 && nx0Slot == lavaFluidSlot);
        boolean x1f = (defaultFluidSlot > 0 && nx1Slot == defaultFluidSlot)
                || (lavaFluidSlot > 0 && nx1Slot == lavaFluidSlot);
        boolean z0f = (defaultFluidSlot > 0 && nz0Slot == defaultFluidSlot)
                || (lavaFluidSlot > 0 && nz0Slot == lavaFluidSlot);
        boolean z1f = (defaultFluidSlot > 0 && nz1Slot == defaultFluidSlot)
                || (lavaFluidSlot > 0 && nz1Slot == lavaFluidSlot);
        return (af || x0f || x1f || z0f || z1f) ? defaultBlockSlot : 0;
    }

    /**
     * Synthesizes a full {@code chunkSections}-tall chunk ({@code int[chunkSections][4096]} in YZX
     * order) from a lifted {@link DimensionCapabilitySpec} with zero hardcoded dimension, biome, or
     * block assumptions.
     */
    public static int[][] synthesizeDimensionChunkCpu(
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            DensityFieldEvaluator densityEval,
            QuartClimateSampler climateSampler
    ) {
        QuartBiomeOrdinalResolver resolver = (qx, qy, qz) -> {
            if (climateSampler != null && spec.flatRTreeNodes() != null) {
                float[] c6 = climateSampler.sampleQuart6(qx, qy, qz);
                if (c6 != null && c6.length >= 6) {
                    return searchRTreeBiomeOrdinal(
                            spec.flatRTreeNodes(), c6[0], c6[1], c6[2], c6[3], c6[4], c6[5]);
                }
            }
            return 0;
        };
        return synthesizeDimensionChunkCpu(spec, chunkX, chunkZ, worldSeed, densityEval, resolver, null);
    }

    public static int[][] synthesizeDimensionChunkCpu(
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            DensityFieldEvaluator densityEval,
            QuartBiomeOrdinalResolver biomeResolver,
            int[] outQuartBiomes
    ) {
        return synthesizeDimensionChunkCpu(
                spec, chunkX, chunkZ, worldSeed, densityEval, biomeResolver, outQuartBiomes, true, true, true);
    }

    public static int[][] synthesizeDimensionChunkCpu(
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            DensityFieldEvaluator densityEval,
            QuartBiomeOrdinalResolver biomeResolver,
            int[] outQuartBiomes,
            boolean applySurfaceRules,
            boolean applyCarvers,
            boolean applyFeatures
    ) {
        return synthesizeDimensionChunkCpu(
                spec,
                chunkX,
                chunkZ,
                worldSeed,
                densityEval,
                biomeResolver,
                RouterCapabilityChannels.EMPTY,
                outQuartBiomes,
                applySurfaceRules,
                applyCarvers,
                applyFeatures);
    }

    public static int[][] synthesizeDimensionChunkCpu(
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            DensityFieldEvaluator densityEval,
            QuartBiomeOrdinalResolver biomeResolver,
            RouterCapabilityChannels routerChannels,
            int[] outQuartBiomes,
            boolean applySurfaceRules,
            boolean applyCarvers,
            boolean applyFeatures
    ) {
        return synthesizeDimensionChunkCpu(
                spec,
                chunkX,
                chunkZ,
                worldSeed,
                densityEval,
                biomeResolver,
                routerChannels,
                outQuartBiomes,
                applySurfaceRules,
                applyCarvers,
                applyFeatures,
                WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE);
    }

    public static int[][] synthesizeDimensionChunkCpu(
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            DensityFieldEvaluator densityEval,
            QuartBiomeOrdinalResolver biomeResolver,
            RouterCapabilityChannels routerChannels,
            int[] outQuartBiomes,
            boolean applySurfaceRules,
            boolean applyCarvers,
            boolean applyFeatures,
            WorldgenKernelSwapper.WorldgenFidelityPolicy fidelityPolicy
    ) {
        return synthesizeDimensionChunkLca(
                        spec,
                        chunkX,
                        chunkZ,
                        worldSeed,
                        densityEval,
                        biomeResolver,
                        biomeResolver == null,
                        routerChannels,
                        outQuartBiomes,
                        null,
                        null,
                        applySurfaceRules,
                        applyCarvers,
                        applyFeatures,
                        null,
                        0,
                        fidelityPolicy)
                .materializeSections();
    }

    /**
     * Computes Mojang's {@code BiomeManager#obfuscateSeed(long)} Voronoi zoom seed (first 8 little-endian
     * bytes of {@code SHA-256} over the 8 little-endian bytes of {@code worldSeed}).
     */
    public static long obfuscateBiomeZoomSeed(long worldSeed) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] leSeed = new byte[8];
            for (int i = 0; i < 8; i++) {
                leSeed[i] = (byte) (worldSeed >>> (i * 8));
            }
            byte[] digest = sha256.digest(leSeed);
            long zoomSeed = 0L;
            for (int i = 0; i < 8; i++) {
                zoomSeed |= (digest[i] & 0xFFL) << (i * 8);
            }
            return zoomSeed;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 MessageDigest unavailable", ex);
        }
    }

    private static long voronoiLcgNext(long seed, long inc) {
        seed *= seed * 6364136223846793005L + 1442695040888963407L;
        return seed + inc;
    }

    private static double voronoiGetFiddle(long seed) {
        double unit = (double) ((seed >> 24) & 1023L) * (1.0 / 1024.0);
        return (unit - 0.5) * 0.9;
    }

    private static double voronoiFiddledDistance(
            long biomeZoomSeed, int qx, int qy, int qz, double dx, double dy, double dz) {
        long s = voronoiLcgNext(biomeZoomSeed, qx);
        s = voronoiLcgNext(s, qy);
        s = voronoiLcgNext(s, qz);
        s = voronoiLcgNext(s, qx);
        s = voronoiLcgNext(s, qy);
        s = voronoiLcgNext(s, qz);
        double fx = voronoiGetFiddle(s);
        s = voronoiLcgNext(s, biomeZoomSeed);
        double fy = voronoiGetFiddle(s);
        s = voronoiLcgNext(s, biomeZoomSeed);
        double fz = voronoiGetFiddle(s);
        double ex = dx + fx;
        double ey = dy + fy;
        double ez = dz + fz;
        return ez * ez + ey * ey + ex * ex;
    }

    private static int lookupHaloQuartBiomeOrdinal(
            int[] haloBiomes6x6,
            int chunkBaseQx,
            int chunkBaseQy,
            int chunkBaseQz,
            QuartBiomeOrdinalResolver biomeResolver,
            int hx,
            int hy,
            int hz
    ) {
        int idx = hy * 36 + hz * 6 + hx;
        int ord = haloBiomes6x6[idx];
        if (ord >= 0) {
            return ord;
        }
        if (biomeResolver != null) {
            ord = Math.max(
                    0,
                    biomeResolver.resolveBiomeOrdinal(
                            chunkBaseQx - 1 + hx, chunkBaseQy + hy, chunkBaseQz - 1 + hz));
        } else {
            int chx = Math.max(1, Math.min(4, hx));
            int chz = Math.max(1, Math.min(4, hz));
            ord = Math.max(0, haloBiomes6x6[hy * 36 + chz * 6 + chx]);
        }
        haloBiomes6x6[idx] = ord;
        return ord;
    }

    /**
     * Evaluates Mojang's exact {@code BiomeManager#getBiome(BlockPos)} 3D Voronoi border zoom over a
     * {@code 6x6xquartsY} chunk-halo quart cache with an {@code O(1)} {@code 2x2x2} uniform-neighborhood
     * short-circuit.
     */
    public static int resolveVoronoiZoomedBiomeOrdinal(
            long biomeZoomSeed,
            int bx,
            int wy,
            int bz,
            int chunkBaseQx,
            int chunkBaseQy,
            int chunkBaseQz,
            int quartsY,
            int[] haloBiomes6x6,
            QuartBiomeOrdinalResolver biomeResolver
    ) {
        int i = bx - 2;
        int j = wy - 2;
        int k = bz - 2;
        int qx0 = i >> 2;
        int qy0 = j >> 2;
        int qz0 = k >> 2;
        int hx0 = Math.max(0, Math.min(4, qx0 - chunkBaseQx + 1));
        int hz0 = Math.max(0, Math.min(4, qz0 - chunkBaseQz + 1));
        int hy0 = Math.max(0, Math.min(quartsY - 1, qy0 - chunkBaseQy));
        int hy1 = Math.max(0, Math.min(quartsY - 1, qy0 + 1 - chunkBaseQy));

        int b000 = lookupHaloQuartBiomeOrdinal(
                haloBiomes6x6, chunkBaseQx, chunkBaseQy, chunkBaseQz, biomeResolver, hx0, hy0, hz0);
        int b100 = lookupHaloQuartBiomeOrdinal(
                haloBiomes6x6, chunkBaseQx, chunkBaseQy, chunkBaseQz, biomeResolver, hx0 + 1, hy0, hz0);
        int b001 = lookupHaloQuartBiomeOrdinal(
                haloBiomes6x6, chunkBaseQx, chunkBaseQy, chunkBaseQz, biomeResolver, hx0, hy0, hz0 + 1);
        int b101 = lookupHaloQuartBiomeOrdinal(
                haloBiomes6x6, chunkBaseQx, chunkBaseQy, chunkBaseQz, biomeResolver, hx0 + 1, hy0, hz0 + 1);
        int b010 = hy1 == hy0
                ? b000
                : lookupHaloQuartBiomeOrdinal(
                        haloBiomes6x6, chunkBaseQx, chunkBaseQy, chunkBaseQz, biomeResolver, hx0, hy1, hz0);
        int b110 = hy1 == hy0
                ? b100
                : lookupHaloQuartBiomeOrdinal(
                        haloBiomes6x6, chunkBaseQx, chunkBaseQy, chunkBaseQz, biomeResolver, hx0 + 1, hy1, hz0);
        int b011 = hy1 == hy0
                ? b001
                : lookupHaloQuartBiomeOrdinal(
                        haloBiomes6x6, chunkBaseQx, chunkBaseQy, chunkBaseQz, biomeResolver, hx0, hy1, hz0 + 1);
        int b111 = hy1 == hy0
                ? b101
                : lookupHaloQuartBiomeOrdinal(
                        haloBiomes6x6, chunkBaseQx, chunkBaseQy, chunkBaseQz, biomeResolver, hx0 + 1, hy1, hz0 + 1);

        if (b000 == b100
                && b000 == b001
                && b000 == b101
                && b000 == b010
                && b000 == b110
                && b000 == b011
                && b000 == b111) {
            return b000;
        }

        double fx = (i & 3) * 0.25;
        double fy = (j & 3) * 0.25;
        double fz = (k & 3) * 0.25;
        int bestCorner = 0;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int c = 0; c < 8; c++) {
            boolean xZero = (c & 4) == 0;
            boolean yZero = (c & 2) == 0;
            boolean zZero = (c & 1) == 0;
            int cx = xZero ? qx0 : (qx0 + 1);
            int cy = yZero ? qy0 : (qy0 + 1);
            int cz = zZero ? qz0 : (qz0 + 1);
            double dx = xZero ? fx : (fx - 1.0);
            double dy = yZero ? fy : (fy - 1.0);
            double dz = zZero ? fz : (fz - 1.0);
            double dist = voronoiFiddledDistance(biomeZoomSeed, cx, cy, cz, dx, dy, dz);
            if (bestDist > dist) {
                bestCorner = c;
                bestDist = dist;
            }
        }
        return switch (bestCorner) {
            case 0 -> b000;
            case 1 -> b001;
            case 2 -> b010;
            case 3 -> b011;
            case 4 -> b100;
            case 5 -> b101;
            case 6 -> b110;
            default -> b111;
        };
    }

    /**
     * Synthesizes a chunk directly into an in-memory {@link LcaCapabilityChunkFrame} using uniform
     * section descriptors, 8-corner octant culling, 64-lane FP32 SIMD interpolation, {@code 01:H_3}
     * Hilbert-contiguous ore seams, and {@code leafSlotMask}-resolved heightmaps.
     */
    public static LcaCapabilityChunkFrame synthesizeDimensionChunkLca(
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            DensityFieldEvaluator densityEval,
            QuartBiomeOrdinalResolver biomeResolver,
            RouterCapabilityChannels routerChannels,
            int[] outQuartBiomes,
            boolean[] leafSlotMask,
            boolean applySurfaceRules,
            boolean applyCarvers,
            boolean applyFeatures
    ) {
        return synthesizeDimensionChunkLca(
                spec,
                chunkX,
                chunkZ,
                worldSeed,
                densityEval,
                biomeResolver,
                biomeResolver == null,
                routerChannels,
                outQuartBiomes,
                leafSlotMask,
                null,
                applySurfaceRules,
                applyCarvers,
                applyFeatures);
    }

    public static LcaCapabilityChunkFrame synthesizeDimensionChunkLca(
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            DensityFieldEvaluator densityEval,
            QuartBiomeOrdinalResolver biomeResolver,
            RouterCapabilityChannels routerChannels,
            int[] outQuartBiomes,
            boolean[] leafSlotMask,
            AquiferCellGridSampler aquiferSampler,
            boolean applySurfaceRules,
            boolean applyCarvers,
            boolean applyFeatures,
            boolean interiorBiomesPreResolved
    ) {
        return synthesizeDimensionChunkLca(
                spec,
                chunkX,
                chunkZ,
                worldSeed,
                densityEval,
                biomeResolver,
                interiorBiomesPreResolved,
                routerChannels,
                outQuartBiomes,
                leafSlotMask,
                aquiferSampler,
                applySurfaceRules,
                applyCarvers,
                applyFeatures);
    }

    public static LcaCapabilityChunkFrame synthesizeDimensionChunkLca(
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            DensityFieldEvaluator densityEval,
            QuartBiomeOrdinalResolver biomeResolver,
            boolean interiorBiomesPreResolved,
            RouterCapabilityChannels routerChannels,
            int[] outQuartBiomes,
            boolean[] leafSlotMask,
            boolean applySurfaceRules,
            boolean applyCarvers,
            boolean applyFeatures
    ) {
        return synthesizeDimensionChunkLca(
                spec,
                chunkX,
                chunkZ,
                worldSeed,
                densityEval,
                biomeResolver,
                interiorBiomesPreResolved,
                routerChannels,
                outQuartBiomes,
                leafSlotMask,
                null,
                applySurfaceRules,
                applyCarvers,
                applyFeatures);
    }

    /**
     * Synthesizes a chunk directly into an in-memory {@link LcaCapabilityChunkFrame}, optionally
     * preserving pre-resolved interior {@code 4x4x(4*secCount)} Java {@code ChunkAccess} biome quarts
     * while lazily resolving {@code 6x6} perimeter halo quarts via {@code biomeResolver} for seamless
     * cross-chunk Mojang Voronoi biome-border dithering.
     */
    public static LcaCapabilityChunkFrame synthesizeDimensionChunkLca(
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            DensityFieldEvaluator densityEval,
            QuartBiomeOrdinalResolver biomeResolver,
            boolean interiorBiomesPreResolved,
            RouterCapabilityChannels routerChannels,
            int[] outQuartBiomes,
            boolean[] leafSlotMask,
            AquiferCellGridSampler aquiferSampler,
            boolean applySurfaceRules,
            boolean applyCarvers,
            boolean applyFeatures
    ) {
        return synthesizeDimensionChunkLca(
                spec,
                chunkX,
                chunkZ,
                worldSeed,
                densityEval,
                biomeResolver,
                interiorBiomesPreResolved,
                routerChannels,
                outQuartBiomes,
                leafSlotMask,
                aquiferSampler,
                applySurfaceRules,
                applyCarvers,
                applyFeatures,
                null);
    }

    public static LcaCapabilityChunkFrame synthesizeDimensionChunkLca(
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            DensityFieldEvaluator densityEval,
            QuartBiomeOrdinalResolver biomeResolver,
            boolean interiorBiomesPreResolved,
            RouterCapabilityChannels routerChannels,
            int[] outQuartBiomes,
            boolean[] leafSlotMask,
            AquiferCellGridSampler aquiferSampler,
            boolean applySurfaceRules,
            boolean applyCarvers,
            boolean applyFeatures,
            int[] beardPiecesI32
    ) {
        return synthesizeDimensionChunkLca(
                spec,
                chunkX,
                chunkZ,
                worldSeed,
                densityEval,
                biomeResolver,
                interiorBiomesPreResolved,
                routerChannels,
                outQuartBiomes,
                leafSlotMask,
                aquiferSampler,
                applySurfaceRules,
                applyCarvers,
                applyFeatures,
                beardPiecesI32,
                0);
    }

    public static LcaCapabilityChunkFrame synthesizeDimensionChunkLca(
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            DensityFieldEvaluator densityEval,
            QuartBiomeOrdinalResolver biomeResolver,
            boolean interiorBiomesPreResolved,
            RouterCapabilityChannels routerChannels,
            int[] outQuartBiomes,
            boolean[] leafSlotMask,
            AquiferCellGridSampler aquiferSampler,
            boolean applySurfaceRules,
            boolean applyCarvers,
            boolean applyFeatures,
            int[] beardPiecesI32,
            int neighborNativeFeaturesMask8
    ) {
        return synthesizeDimensionChunkLca(
                spec,
                chunkX,
                chunkZ,
                worldSeed,
                densityEval,
                biomeResolver,
                interiorBiomesPreResolved,
                routerChannels,
                outQuartBiomes,
                leafSlotMask,
                aquiferSampler,
                applySurfaceRules,
                applyCarvers,
                applyFeatures,
                beardPiecesI32,
                neighborNativeFeaturesMask8,
                WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE);
    }

    public static LcaCapabilityChunkFrame synthesizeDimensionChunkLca(
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            DensityFieldEvaluator densityEval,
            QuartBiomeOrdinalResolver biomeResolver,
            boolean interiorBiomesPreResolved,
            RouterCapabilityChannels routerChannels,
            int[] outQuartBiomes,
            boolean[] leafSlotMask,
            AquiferCellGridSampler aquiferSampler,
            boolean applySurfaceRules,
            boolean applyCarvers,
            boolean applyFeatures,
            int[] beardPiecesI32,
            int neighborNativeFeaturesMask8,
            WorldgenKernelSwapper.WorldgenFidelityPolicy fidelityPolicy
    ) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(densityEval, "densityEval");
        WorldgenKernelSwapper.WorldgenFidelityPolicy effPolicy =
                fidelityPolicy == null
                        ? WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE
                        : fidelityPolicy;
        boolean exactMode = effPolicy.isExact();
        RouterCapabilityChannels rc = routerChannels != null ? routerChannels : RouterCapabilityChannels.EMPTY;
        AquiferCellSnapshot aquiferSnap = (spec.aquifersEnabled() && aquiferSampler != null)
                ? aquiferSampler.sampleChunkSnapshot(chunkX, chunkZ)
                : null;

        int secCount = spec.chunkSections();
        int chunkBaseBx = chunkX << 4;
        int chunkBaseBz = chunkZ << 4;
        int noiseMinY = spec.noiseMinY();
        int noiseMaxExclY = noiseMinY + spec.noiseHeight();
        int noiseTopY = noiseMaxExclY - 1;

        // 1. Sample 4x4x(4*secCount) 3D biome quarts via capability biome resolver (or preserve pre-resolved Java quarts)
        int quartsY = secCount << 2;
        int[] quartBiomes = (outQuartBiomes != null && outQuartBiomes.length >= 16 * quartsY)
                ? outQuartBiomes
                : new int[16 * quartsY];
        if (!interiorBiomesPreResolved && biomeResolver != null) {
            for (int qyRel = 0; qyRel < quartsY; qyRel++) {
                int worldQy = (spec.chunkMinY() >> 2) + qyRel;
                for (int qz = 0; qz < 4; qz++) {
                    for (int qx = 0; qx < 4; qx++) {
                        quartBiomes[(qyRel << 4) | (qz << 2) | qx] =
                                biomeResolver.resolveBiomeOrdinal((chunkX << 2) + qx, worldQy, (chunkZ << 2) + qz);
                    }
                }
            }
        }

        // 2. Sample dynamic (cellsX+1)x(cellsY+1)x(cellsZ+1) trilinear density grid via column Y batching
        int cellW = Math.max(1, spec.cellWidth());
        int cellH = Math.max(1, spec.cellHeight());
        int cellsX = Math.max(1, (16 + cellW - 1) / cellW);
        int cellsZ = Math.max(1, (16 + cellW - 1) / cellW);
        int cellsY = Math.max(1, spec.noiseHeight() / cellH);
        int strideZ = cellsY + 1;
        int strideX = (cellsZ + 1) * strideZ;
        float[] corners = exactMode ? null : new float[(cellsX + 1) * strideX];
        double[] cornersFp64 = exactMode ? new double[(cellsX + 1) * strideX] : null;
        if (exactMode) {
            for (int gx = 0; gx <= cellsX; gx++) {
                int bx = chunkBaseBx + gx * cellW;
                for (int gz = 0; gz <= cellsZ; gz++) {
                    int bz = chunkBaseBz + gz * cellW;
                    int colOff = gx * strideX + gz * strideZ;
                    densityEval.sampleColumnYFp64(bx, noiseMinY, cellH, cellsY + 1, bz, cornersFp64, colOff);
                }
            }
            if (beardPiecesI32 != null && beardPiecesI32.length >= 8) {
                applyBeardifierCornersInPlaceFp64(
                        cornersFp64, chunkX, chunkZ, noiseMinY, cellW, cellH, cellsX, cellsY, cellsZ, beardPiecesI32);
            }
        } else {
            for (int gx = 0; gx <= cellsX; gx++) {
                int bx = chunkBaseBx + gx * cellW;
                for (int gz = 0; gz <= cellsZ; gz++) {
                    int bz = chunkBaseBz + gz * cellW;
                    int colOff = gx * strideX + gz * strideZ;
                    densityEval.sampleColumnYAboveThreshold(
                            bx, noiseMinY, cellH, cellsY + 1, bz, -0.85f, corners, colOff);
                }
            }
            if (beardPiecesI32 != null && beardPiecesI32.length >= 8) {
                applyBeardifierCornersInPlace(
                        corners, chunkX, chunkZ, noiseMinY, cellW, cellH, cellsX, cellsY, cellsZ, beardPiecesI32);
            }
        }

        int defaultBlock = spec.defaultBlockSlot();
        int defaultFluid = spec.defaultFluidSlot();
        int lavaSlot = spec.lavaFluidSlot();
        boolean aquifers = spec.aquifersEnabled() && lavaSlot > 0;
        int deepLavaFloorY = spec.deepLavaFloorY();
        int seaLevel = spec.seaLevel();
        float invCellW = 1.0f / cellW;
        float invCellH = 1.0f / cellH;
        RouterVeinSpec routerVeins = spec.routerVeinSpec();
        boolean evalVeins = routerVeins != null
                && routerVeins.enabled()
                && rc.veinToggle() != null
                && rc.veinRidged() != null
                && rc.veinGap() != null;

        int[] topSurfaceY = new int[256];
        Arrays.fill(topSurfaceY, spec.chunkMinY() - 1);
        LcaCapabilityChunkFrame frame = new LcaCapabilityChunkFrame(spec, quartBiomes, topSurfaceY, effPolicy);

        int minBoundY = Math.max(spec.chunkMinY(), noiseMinY);
        int maxBoundY = Math.min(spec.chunkMinY() + (secCount << 4) - 1, noiseTopY);

        // Pre-classify section uniform descriptors before cell synthesis in VANILLA_LIKE mode
        if (!exactMode) {
            for (int s = 0; s < secCount; s++) {
                int secLoY = spec.chunkMinY() + (s << 4);
                int secHiY = secLoY + 15;
                if (secHiY < noiseMinY || secLoY > noiseTopY) {
                    frame.setSectionUniform(s, 0);
                    continue;
                }
                if (secLoY >= noiseMinY && secHiY <= noiseTopY && (16 % cellH) == 0) {
                    int cyStart = (secLoY - noiseMinY) / cellH;
                    int cyEnd = (secHiY + 1 - noiseMinY) / cellH;
                    float secMin = Float.POSITIVE_INFINITY;
                    float secMax = Float.NEGATIVE_INFINITY;
                    for (int gx = 0; gx <= cellsX; gx++) {
                        for (int gz = 0; gz <= cellsZ; gz++) {
                            int base = gx * strideX + gz * strideZ;
                            for (int gy = cyStart; gy <= cyEnd; gy++) {
                                float v = corners[base + gy];
                                if (v < secMin) secMin = v;
                                if (v > secMax) secMax = v;
                            }
                        }
                    }
                    if (secMin > 0.0f) {
                        frame.setSectionUniform(s, defaultBlock);
                        continue;
                    }
                    if (secMax <= 0.0f) {
                        if (aquifers && secHiY <= deepLavaFloorY) {
                            frame.setSectionUniform(s, lavaSlot);
                            continue;
                        }
                        if (!spec.aquifersEnabled() && defaultFluid > 0 && secHiY <= seaLevel) {
                            frame.setSectionUniform(s, defaultFluid);
                            continue;
                        }
                        if ((!aquifers || secLoY > deepLavaFloorY)
                                && (aquiferSnap == null || secLoY > Math.max(seaLevel, aquiferSnap.skipSamplingAboveY()))
                                && (spec.aquifersEnabled() || defaultFluid == 0 || secLoY > seaLevel)) {
                            frame.setSectionUniform(s, 0);
                        }
                    }
                }
            }
        } else {
            for (int s = 0; s < secCount; s++) {
                int secLoY = spec.chunkMinY() + (s << 4);
                int secHiY = secLoY + 15;
                if (secHiY < noiseMinY || secLoY > noiseTopY) {
                    frame.setSectionUniform(s, 0);
                }
            }
        }

        int[] rawTopSolidY = new int[256];
        Arrays.fill(rawTopSolidY, spec.chunkMinY() - 1);
        float[] cell4xHx4Scratch = (!exactMode && cellW == 4 && cellH <= 16) ? new float[16 * cellH] : null;
        double invCellWFp64 = 1.0 / (double) cellW;
        double invCellHFp64 = 1.0 / (double) cellH;

        // Fill base terrain + router veins + non-aquifer sea inside [noiseMinY..noiseTopY]; sections outside remain AIR (0)
        for (int cy = cellsY - 1; cy >= 0; cy--) {
            int cellBaseY = noiseMinY + cy * cellH;
            int cellTopY = cellBaseY + cellH - 1;
            int clampedTopWy = Math.min(cellTopY, spec.chunkMinY() + (secCount << 4) - 1);
            int clampedBotWy = Math.max(cellBaseY, spec.chunkMinY());
            if (clampedTopWy < spec.chunkMinY() || clampedBotWy >= spec.chunkMinY() + (secCount << 4)) {
                continue;
            }
            boolean cellVeinsActive = evalVeins && routerVeinOverlapsRange(routerVeins, clampedBotWy, clampedTopWy);

            for (int gx = 0; gx < cellsX; gx++) {
                int maxDx = Math.min(cellW, 16 - gx * cellW);
                if (maxDx <= 0) break;
                for (int gz = 0; gz < cellsZ; gz++) {
                    int maxDz = Math.min(cellW, 16 - gz * cellW);
                    if (maxDz <= 0) break;
                    int c00 = gx * strideX + gz * strideZ + cy;
                    int c10 = (gx + 1) * strideX + gz * strideZ + cy;
                    int c01 = gx * strideX + (gz + 1) * strideZ + cy;
                    int c11 = (gx + 1) * strideX + (gz + 1) * strideZ + cy;
                    if (exactMode) {
                        double dv000 = cornersFp64[c00], dv001 = cornersFp64[c00 + 1];
                        double dv100 = cornersFp64[c10], dv101 = cornersFp64[c10 + 1];
                        double dv010 = cornersFp64[c01], dv011 = cornersFp64[c01 + 1];
                        double dv110 = cornersFp64[c11], dv111 = cornersFp64[c11 + 1];
                        for (int dy = cellH - 1; dy >= 0; dy--) {
                            int wy = cellBaseY + dy;
                            int secIdx = (wy - spec.chunkMinY()) >> 4;
                            if (secIdx < 0 || secIdx >= secCount) {
                                continue;
                            }
                            int lyShift = (wy & 15) << 8;
                            double fyLerp = (double) dy * invCellHFp64;
                            double x00 = dv000 + fyLerp * (dv001 - dv000);
                            double x10 = dv100 + fyLerp * (dv101 - dv100);
                            double x01 = dv010 + fyLerp * (dv011 - dv010);
                            double x11 = dv110 + fyLerp * (dv111 - dv110);
                            boolean wyVeins = cellVeinsActive && routerVeinOverlapsRange(routerVeins, wy, wy);
                            for (int dx = 0; dx < maxDx; dx++) {
                                int lx = gx * cellW + dx;
                                int bx = chunkBaseBx + lx;
                                double fxLerp = (double) dx * invCellWFp64;
                                double z0 = x00 + fxLerp * (x10 - x00);
                                double z1 = x01 + fxLerp * (x11 - x01);
                                for (int dz = 0; dz < maxDz; dz++) {
                                    int lz = gz * cellW + dz;
                                    int col = (lz << 4) | lx;
                                    double dens64 = z0 + ((double) dz * invCellWFp64) * (z1 - z0);
                                    int vox;
                                    if (dens64 > 0.0) {
                                        vox = defaultBlock;
                                        if (wy > rawTopSolidY[col]) {
                                            rawTopSolidY[col] = wy;
                                        }
                                        if (wyVeins) {
                                            int bz = chunkBaseBz + lz;
                                            int ph = mixColumnSeed(worldSeed, bx, bz) ^ (wy * 31);
                                            vox = evaluateRouterVeinVoxel(
                                                    routerVeins, rc, bx, wy, bz, vox, defaultBlock, ph);
                                        }
                                    } else if (aquiferSnap != null) {
                                        vox = evaluateNoiseBasedAquiferVoxelExactFp64(
                                                aquiferSnap,
                                                bx,
                                                wy,
                                                chunkBaseBz + lz,
                                                dens64,
                                                rc.barrierNoise(),
                                                defaultBlock,
                                                defaultFluid,
                                                lavaSlot,
                                                seaLevel,
                                                deepLavaFloorY);
                                    } else if (aquifers && wy <= deepLavaFloorY) {
                                        vox = lavaSlot;
                                    } else if (!spec.aquifersEnabled() && defaultFluid > 0 && wy <= seaLevel) {
                                        vox = defaultFluid;
                                    } else {
                                        vox = 0;
                                    }
                                    frame.setVoxel(secIdx, lyShift | col, vox);
                                }
                            }
                        }
                        continue;
                    }
                    float v000 = corners[c00], v001 = corners[c00 + 1];
                    float v100 = corners[c10], v101 = corners[c10 + 1];
                    float v010 = corners[c01], v011 = corners[c01 + 1];
                    float v110 = corners[c11], v111 = corners[c11 + 1];

                    float minCorner = Math.min(
                            Math.min(Math.min(v000, v001), Math.min(v100, v101)),
                            Math.min(Math.min(v010, v011), Math.min(v110, v111)));
                    float maxCorner = Math.max(
                            Math.max(Math.max(v000, v001), Math.max(v100, v101)),
                            Math.max(Math.max(v010, v011), Math.max(v110, v111)));
                    boolean localCellVeins = cellVeinsActive;
                    if (localCellVeins && maxCorner > 0.0f && rc.veinToggle().isTrilinearCellInterpolated(cellW, cellH)) {
                        int vx0 = chunkBaseBx + gx * cellW;
                        int vx1 = vx0 + cellW;
                        int vz0 = chunkBaseBz + gz * cellW;
                        int vz1 = vz0 + cellW;
                        int vy0 = cellBaseY;
                        int vy1 = cellBaseY + cellH;
                        var vt = rc.veinToggle();
                        if (Math.abs(vt.sample(vx0, vy0, vz0)) <= 0.4f
                                && Math.abs(vt.sample(vx0, vy1, vz0)) <= 0.4f
                                && Math.abs(vt.sample(vx1, vy0, vz0)) <= 0.4f
                                && Math.abs(vt.sample(vx1, vy1, vz0)) <= 0.4f
                                && Math.abs(vt.sample(vx0, vy0, vz1)) <= 0.4f
                                && Math.abs(vt.sample(vx0, vy1, vz1)) <= 0.4f
                                && Math.abs(vt.sample(vx1, vy0, vz1)) <= 0.4f
                                && Math.abs(vt.sample(vx1, vy1, vz1)) <= 0.4f) {
                            localCellVeins = false;
                        }
                    }

                    // Fast Path A: 8-corner solid octant/cell cull (minCorner > 0.0f)
                    if (minCorner > 0.0f) {
                        for (int dx = 0; dx < maxDx; dx++) {
                            int lx = gx * cellW + dx;
                            for (int dz = 0; dz < maxDz; dz++) {
                                int lz = gz * cellW + dz;
                                int col = (lz << 4) | lx;
                                if (clampedTopWy > rawTopSolidY[col]) {
                                    rawTopSolidY[col] = clampedTopWy;
                                }
                            }
                        }
                        for (int dy = cellH - 1; dy >= 0; dy--) {
                            int wy = cellBaseY + dy;
                            int secIdx = (wy - spec.chunkMinY()) >> 4;
                            if (secIdx < 0 || secIdx >= secCount) continue;
                            boolean wyVeins = localCellVeins && routerVeinOverlapsRange(routerVeins, wy, wy);
                            if (!wyVeins && frame.uniformSlotId(secIdx) == defaultBlock) {
                                continue;
                            }
                            int lyShift = (wy & 15) << 8;
                            for (int dx = 0; dx < maxDx; dx++) {
                                int lx = gx * cellW + dx;
                                int bx = chunkBaseBx + lx;
                                for (int dz = 0; dz < maxDz; dz++) {
                                    int lz = gz * cellW + dz;
                                    int vox = defaultBlock;
                                    if (wyVeins) {
                                        int bz = chunkBaseBz + lz;
                                        int ph = mixColumnSeed(worldSeed, bx, bz) ^ (wy * 31);
                                        vox = evaluateRouterVeinVoxel(
                                                routerVeins, rc, bx, wy, bz, vox, defaultBlock, ph);
                                    }
                                    frame.setVoxel(secIdx, lyShift | (lz << 4) | lx, vox);
                                }
                            }
                        }
                        continue;
                    }

                    // Fast Path B: 8-corner empty/fluid octant/cell cull (maxCorner <= 0.0f)
                    if (maxCorner <= 0.0f
                            && (aquiferSnap == null
                                    || (aquifers && cellTopY <= deepLavaFloorY)
                                    || cellBaseY > Math.max(seaLevel, aquiferSnap.skipSamplingAboveY()))) {
                        for (int dy = cellH - 1; dy >= 0; dy--) {
                            int wy = cellBaseY + dy;
                            int secIdx = (wy - spec.chunkMinY()) >> 4;
                            if (secIdx < 0 || secIdx >= secCount) continue;
                            int vox;
                            if (aquifers && wy <= deepLavaFloorY) {
                                vox = lavaSlot;
                            } else if (!spec.aquifersEnabled() && defaultFluid > 0 && wy <= seaLevel) {
                                vox = defaultFluid;
                            } else {
                                vox = 0;
                            }
                            if (frame.uniformSlotId(secIdx) == vox) {
                                continue;
                            }
                            int lyShift = (wy & 15) << 8;
                            for (int dx = 0; dx < maxDx; dx++) {
                                int lx = gx * cellW + dx;
                                for (int dz = 0; dz < maxDz; dz++) {
                                    int lz = gz * cellW + dz;
                                    frame.setVoxel(secIdx, lyShift | (lz << 4) | lx, vox);
                                }
                            }
                        }
                        continue;
                    }

                    // Fast Path C: Unit-stride SIMD cell interpolation for 4xHx4 cells (e.g. Overworld 4x8x4 = 128 floats, Nether 4x4x4 = 64 floats)
                    if (cell4xHx4Scratch != null && maxDx == 4 && maxDz == 4) {
                        interpolateCell4xHx4Fp32(
                                v000, v001, v100, v101, v010, v011, v110, v111, cellH, cell4xHx4Scratch);
                        for (int dy = cellH - 1; dy >= 0; dy--) {
                            int wy = cellBaseY + dy;
                            int secIdx = (wy - spec.chunkMinY()) >> 4;
                            if (secIdx < 0 || secIdx >= secCount) continue;
                            int lyShift = (wy & 15) << 8;
                            int yOff = dy << 4;
                            boolean wyVeins = localCellVeins && routerVeinOverlapsRange(routerVeins, wy, wy);
                            for (int dx = 0; dx < 4; dx++) {
                                int lx = (gx << 2) + dx;
                                int bx = chunkBaseBx + lx;
                                int xOff = yOff | (dx << 2);
                                for (int dz = 0; dz < 4; dz++) {
                                    int lz = (gz << 2) + dz;
                                    int col = (lz << 4) | lx;
                                    float dens = cell4xHx4Scratch[xOff + dz];
                                    int vox;
                                    if (dens > 0.0f) {
                                        vox = defaultBlock;
                                        if (wy > rawTopSolidY[col]) {
                                            rawTopSolidY[col] = wy;
                                        }
                                        if (wyVeins) {
                                            int bz = chunkBaseBz + lz;
                                            int ph = mixColumnSeed(worldSeed, bx, bz) ^ (wy * 31);
                                            vox = evaluateRouterVeinVoxel(
                                                    routerVeins, rc, bx, wy, bz, vox, defaultBlock, ph);
                                        }
                                    } else if (aquiferSnap != null) {
                                        vox = evaluateNoiseBasedAquiferVoxel(
                                                aquiferSnap,
                                                bx,
                                                wy,
                                                chunkBaseBz + lz,
                                                dens,
                                                rc.barrierNoise(),
                                                defaultBlock,
                                                defaultFluid,
                                                lavaSlot,
                                                seaLevel,
                                                deepLavaFloorY);
                                    } else if (aquifers && wy <= deepLavaFloorY) {
                                        vox = lavaSlot;
                                    } else if (!spec.aquifersEnabled() && defaultFluid > 0 && wy <= seaLevel) {
                                        vox = defaultFluid;
                                    } else {
                                        vox = 0;
                                    }
                                    frame.setVoxel(secIdx, lyShift | col, vox);
                                }
                            }
                        }
                        continue;
                    }

                    for (int dy = cellH - 1; dy >= 0; dy--) {
                        int wy = cellBaseY + dy;
                        int secIdx = (wy - spec.chunkMinY()) >> 4;
                        if (secIdx < 0 || secIdx >= secCount) {
                            continue;
                        }
                        int lyShift = (wy & 15) << 8;
                        float fy = dy * invCellH;
                        float x00 = v000 + (v001 - v000) * fy;
                        float x10 = v100 + (v101 - v100) * fy;
                        float x01 = v010 + (v011 - v010) * fy;
                        float x11 = v110 + (v111 - v110) * fy;
                        boolean wyVeins = cellVeinsActive && routerVeinOverlapsRange(routerVeins, wy, wy);

                        for (int dx = 0; dx < maxDx; dx++) {
                            int lx = gx * cellW + dx;
                            int bx = chunkBaseBx + lx;
                            float fx = dx * invCellW;
                            float z0 = x00 + (x10 - x00) * fx;
                            float z1 = x01 + (x11 - x01) * fx;
                            for (int dz = 0; dz < maxDz; dz++) {
                                int lz = gz * cellW + dz;
                                int col = (lz << 4) | lx;
                                float dens = z0 + (z1 - z0) * (dz * invCellW);
                                int vox;
                                if (dens > 0.0f) {
                                    vox = defaultBlock;
                                    if (wy > rawTopSolidY[col]) {
                                        rawTopSolidY[col] = wy;
                                    }
                                    if (wyVeins) {
                                        int bz = chunkBaseBz + lz;
                                        int ph = mixColumnSeed(worldSeed, bx, bz) ^ (wy * 31);
                                        vox = evaluateRouterVeinVoxel(
                                                routerVeins, rc, bx, wy, bz, vox, defaultBlock, ph);
                                    }
                                } else if (aquiferSnap != null) {
                                    vox = evaluateNoiseBasedAquiferVoxel(
                                            aquiferSnap,
                                            bx,
                                            wy,
                                            chunkBaseBz + lz,
                                            dens,
                                            rc.barrierNoise(),
                                            defaultBlock,
                                            defaultFluid,
                                            lavaSlot,
                                            seaLevel,
                                            deepLavaFloorY);
                                } else if (aquifers && wy <= deepLavaFloorY) {
                                    vox = lavaSlot;
                                } else if (!spec.aquifersEnabled() && defaultFluid > 0 && wy <= seaLevel) {
                                    vox = defaultFluid;
                                } else {
                                    vox = 0;
                                }
                                frame.setVoxel(secIdx, lyShift | col, vox);
                            }
                        }
                    }
                }
            }
        }

        // Compute preliminary surface Y per column from router slot 10 (initialDensityWithoutJaggedness)
        int[] prelimSurfaceY = new int[256];
        int estimatedBasinFloorY = minBoundY;
        if (rc.initialDensityWithoutJaggedness() != null) {
            DensityFieldEvaluator prelimEval = rc.initialDensityWithoutJaggedness();
            if (!exactMode && prelimEval.isQuartAlignedXZ()) {
                for (int qz = 0; qz < 4; qz++) {
                    int baseLz = qz << 2;
                    int bz = chunkBaseBz + baseLz;
                    for (int qx = 0; qx < 4; qx++) {
                        int baseLx = qx << 2;
                        int qVal = computePreliminarySurfaceY(
                                spec, prelimEval, chunkBaseBx + baseLx, bz, rawTopSolidY[(baseLz << 4) | baseLx], effPolicy);
                        for (int dz = 0; dz < 4; dz++) {
                            int row = (baseLz + dz) << 4;
                            for (int dx = 0; dx < 4; dx++) {
                                prelimSurfaceY[row | (baseLx + dx)] = qVal;
                            }
                        }
                    }
                }
            } else {
                for (int lz = 0; lz < 16; lz++) {
                    int bz = chunkBaseBz + lz;
                    for (int lx = 0; lx < 16; lx++) {
                        int col = (lz << 4) | lx;
                        prelimSurfaceY[col] = computePreliminarySurfaceY(
                                spec, prelimEval, chunkBaseBx + lx, bz, rawTopSolidY[col], effPolicy);
                    }
                }
            }
        } else {
            int maxShallowSubmergedFloor = Integer.MIN_VALUE;
            for (int col = 0; col < 256; col++) {
                prelimSurfaceY[col] = rawTopSolidY[col];
                if (rawTopSolidY[col] <= seaLevel && rawTopSolidY[col] > maxShallowSubmergedFloor) {
                    maxShallowSubmergedFloor = rawTopSolidY[col];
                }
            }
            if (maxShallowSubmergedFloor > Integer.MIN_VALUE) {
                estimatedBasinFloorY = maxShallowSubmergedFloor;
            }
        }

        // 2b. 3D Hydrostatic & Router Aquifer Resolution (open-sky sea fill + VANILLA_LIKE heuristic basin/containment)
        if (spec.aquifersEnabled()) {
            if (aquiferSnap == null && defaultFluid > 0) {
                for (int lz = 0; lz < 16; lz++) {
                    int bz = chunkBaseBz + lz;
                    for (int lx = 0; lx < 16; lx++) {
                        int col = (lz << 4) | lx;
                        if (rawTopSolidY[col] > seaLevel) {
                            continue;
                        }
                        int basinFloorStopY = rc.initialDensityWithoutJaggedness() != null
                                ? Math.max(minBoundY, prelimSurfaceY[col] - 2)
                                : Math.max(minBoundY, estimatedBasinFloorY - 1);
                        int bx = chunkBaseBx + lx;
                        for (int wy = maxBoundY; wy >= minBoundY; wy--) {
                            int sIdx = (wy - spec.chunkMinY()) >> 4;
                            int secLoY = spec.chunkMinY() + (sIdx << 4);
                            if (frame.uniformSlotId(sIdx) == 0 && secLoY > seaLevel) {
                                wy = secLoY;
                                continue;
                            }
                            int idx = ((wy & 15) << 8) | col;
                            int cur = frame.getVoxel(sIdx, idx);
                            if (cur != 0) {
                                break;
                            }
                            if (wy <= seaLevel) {
                                if (wy < basinFloorStopY) {
                                    float bar = rc.barrierNoise() != null ? rc.barrierNoise().sample(bx, wy, bz) : 0.5f;
                                    int plugThickness = bar >= 0.0f ? 2 : 1;
                                    for (int py = 0; py < plugThickness && (wy - py) >= minBoundY; py++) {
                                        int ps = (wy - py - spec.chunkMinY()) >> 4;
                                        int pi = (((wy - py) & 15) << 8) | col;
                                        if (frame.getVoxel(ps, pi) == 0) {
                                            frame.setVoxel(ps, pi, defaultBlock);
                                        }
                                    }
                                    break;
                                }
                                frame.setVoxel(sIdx, idx, defaultFluid);
                            }
                        }
                    }
                }
            }

            if (!exactMode || aquiferSnap == null) {
            if (aquiferSnap == null && rc.fluidLevelFloodednessNoise() != null && (defaultFluid > 0 || lavaSlot > 0)) {
                for (int lz = 0; lz < 16; lz++) {
                    int bz = chunkBaseBz + lz;
                    int gzCenter = Math.floorDiv(bz, 16) * 16 + 8;
                    for (int lx = 0; lx < 16; lx++) {
                        int col = (lz << 4) | lx;
                        int bx = chunkBaseBx + lx;
                        int gxCenter = Math.floorDiv(bx, 16) * 16 + 8;
                        int roofCapY = Math.min(seaLevel, rawTopSolidY[col] - 6);
                        for (int wy = Math.min(maxBoundY, roofCapY); wy > deepLavaFloorY && wy >= minBoundY; wy--) {
                            int sIdx = (wy - spec.chunkMinY()) >> 4;
                            int uSlot = frame.uniformSlotId(sIdx);
                            if (uSlot > 0) {
                                wy = spec.chunkMinY() + (sIdx << 4);
                                continue;
                            }
                            int idx = ((wy & 15) << 8) | col;
                            if (frame.getVoxel(sIdx, idx) != 0) {
                                continue;
                            }
                            int gyCenter = Math.floorDiv(wy, 12) * 12 + 6;
                            float flooded = rc.fluidLevelFloodednessNoise().sample(gxCenter, gyCenter, gzCenter);
                            if (flooded > 0.35f) {
                                float spread = rc.fluidLevelSpreadNoise() != null
                                        ? rc.fluidLevelSpreadNoise().sample(gxCenter, gyCenter, gzCenter)
                                        : 0.0f;
                                int localLevel = Math.min(roofCapY, gyCenter + (int) Math.rint(spread * 6.0f) + 4);
                                if (wy <= localLevel) {
                                    float lavaVal = rc.lavaNoise() != null
                                            ? rc.lavaNoise().sample(gxCenter, gyCenter, gzCenter)
                                            : 0.0f;
                                    int fSlot = (Math.abs(lavaVal) > 0.70f && localLevel <= -10 && wy <= -10 && lavaSlot > 0)
                                            ? lavaSlot
                                            : defaultFluid;
                                    if (fSlot > 0) {
                                        frame.setVoxel(sIdx, idx, fSlot);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Gravity waterfall continuity: fluid inside an open cave pocket settles down to solid cave rock
            // rather than leaving mid-cavern air gaps that would convert into flat horizontal stone floors.
            if (aquiferSnap != null || rc.fluidLevelFloodednessNoise() != null) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int col = (lz << 4) | lx;
                        int carryFluid = 0;
                        for (int wy = maxBoundY; wy >= minBoundY; wy--) {
                            int sIdx = (wy - spec.chunkMinY()) >> 4;
                            int idx = ((wy & 15) << 8) | col;
                            int cur = frame.getVoxel(sIdx, idx);
                            boolean curIsFluid =
                                    (defaultFluid > 0 && cur == defaultFluid) || (lavaSlot > 0 && cur == lavaSlot);
                            if (curIsFluid) {
                                carryFluid = cur;
                            } else if (cur == 0 && carryFluid > 0) {
                                frame.setVoxel(sIdx, idx, carryFluid);
                            } else {
                                carryFluid = 0;
                            }
                        }
                    }
                }
            }

            List<int[]> sealCoords = new ArrayList<>();
            for (int wy = minBoundY; wy <= maxBoundY; wy++) {
                int sIdx = (wy - spec.chunkMinY()) >> 4;
                int uSlot = frame.uniformSlotId(sIdx);
                if (uSlot >= 0
                        && (defaultFluid == 0 || uSlot != defaultFluid)
                        && (lavaSlot == 0 || uSlot != lavaSlot)) {
                    wy = Math.min(maxBoundY, spec.chunkMinY() + (sIdx << 4) + 15);
                    continue;
                }
                int lyShift = (wy & 15) << 8;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int vox = frame.getVoxel(sIdx, lyShift | (lz << 4) | lx);
                        boolean isFluid = (defaultFluid > 0 && vox == defaultFluid) || (lavaSlot > 0 && vox == lavaSlot);
                        if (!isFluid) {
                            continue;
                        }
                        if (wy > spec.chunkMinY()) {
                            int bs = (wy - 1 - spec.chunkMinY()) >> 4;
                            int bi = (((wy - 1) & 15) << 8) | (lz << 4) | lx;
                            if (frame.getVoxel(bs, bi) == 0) {
                                sealCoords.add(new int[] {bs, bi});
                            }
                        }
                        if (lx > 0 && frame.getVoxel(sIdx, lyShift | (lz << 4) | (lx - 1)) == 0) {
                            sealCoords.add(new int[] {sIdx, lyShift | (lz << 4) | (lx - 1)});
                        }
                        if (lx < 15 && frame.getVoxel(sIdx, lyShift | (lz << 4) | (lx + 1)) == 0) {
                            sealCoords.add(new int[] {sIdx, lyShift | (lz << 4) | (lx + 1)});
                        }
                        if (lz > 0 && frame.getVoxel(sIdx, lyShift | ((lz - 1) << 4) | lx) == 0) {
                            sealCoords.add(new int[] {sIdx, lyShift | ((lz - 1) << 4) | lx});
                        }
                        if (lz < 15 && frame.getVoxel(sIdx, lyShift | ((lz + 1) << 4) | lx) == 0) {
                            sealCoords.add(new int[] {sIdx, lyShift | ((lz + 1) << 4) | lx});
                        }
                    }
                }
            }
            for (int i = 0; i < sealCoords.size(); i++) {
                int[] sc = sealCoords.get(i);
                frame.setVoxel(sc[0], sc[1], defaultBlock);
            }
            }
        }

        if (beardPiecesI32 != null && beardPiecesI32.length >= 8) {
            frame.applyExactBeardifierPieceRefinement(chunkX, chunkZ, beardPiecesI32, false);
        }

        // 3. Scan columns top-down (skipping uniform air/fluid sections in O(1)) for topSurfaceY, steep gradient, and SurfaceRules
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int col = (lz << 4) | lx;
                for (int wy = maxBoundY; wy >= minBoundY; wy--) {
                    int sIdx = (wy - spec.chunkMinY()) >> 4;
                    int uSlot = frame.uniformSlotId(sIdx);
                    if (uSlot == 0
                            || (defaultFluid > 0 && uSlot == defaultFluid)
                            || (lavaSlot > 0 && uSlot == lavaSlot)) {
                        wy = spec.chunkMinY() + (sIdx << 4);
                        continue;
                    }
                    if (uSlot > 0) {
                        topSurfaceY[col] = wy;
                        break;
                    }
                    int cur = frame.getVoxel(sIdx, ((wy & 15) << 8) | col);
                    if (cur != 0
                            && (defaultFluid == 0 || cur != defaultFluid)
                            && (lavaSlot == 0 || cur != lavaSlot)) {
                        topSurfaceY[col] = wy;
                        break;
                    }
                }
            }
        }

        DensityFieldEvaluator[] condNoises = rc.conditionNoises();
        boolean[] condNoiseIs3d = rc.conditionNoiseIs3d();
        float[] condNoiseVals = (!exactMode && condNoises != null && condNoises.length > 0)
                ? new float[condNoises.length]
                : null;
        double[] condNoiseValsFp64 = (exactMode && condNoises != null && condNoises.length > 0)
                ? new double[condNoises.length]
                : null;

        if (applySurfaceRules && spec.hasSurfaceRuleModule()) {
            JavaConceptBackendEmitter.SurfaceRuleEvaluator surfaceEvaluator =
                    JavaConceptBackendEmitter.compileSurfaceRuleEvaluator(spec.surfaceRuleModule());
            long biomeZoomSeed = obfuscateBiomeZoomSeed(worldSeed);
            int chunkBaseQx = chunkX << 2;
            int chunkBaseQy = spec.chunkMinY() >> 2;
            int chunkBaseQz = chunkZ << 2;
            int maxColTopSurf = minBoundY;
            for (int c = 0; c < 256; c++) {
                if (topSurfaceY[c] > maxColTopSurf) {
                    maxColTopSurf = topSurfaceY[c];
                }
            }
            int activeHaloQuartsY = Math.min(
                    quartsY,
                    Math.max(1, ((Math.max(seaLevel, maxColTopSurf) + 4) - spec.chunkMinY()) >> 2) + 1);
            int[] haloBiomes6x6 = new int[quartsY * 36];
            Arrays.fill(haloBiomes6x6, -1);
            for (int qyRel = 0; qyRel < quartsY; qyRel++) {
                int dstLayer = qyRel * 36;
                int srcLayer = qyRel << 4;
                for (int qz = 0; qz < 4; qz++) {
                    int dstRow = dstLayer + (qz + 1) * 6 + 1;
                    int srcRow = srcLayer + (qz << 2);
                    for (int dx = 0; dx < 4; dx++) {
                        haloBiomes6x6[dstRow + dx] = Math.max(0, quartBiomes[srcRow + dx]);
                    }
                }
            }
            for (int hz = 0; hz < 6; hz++) {
                int wQz = chunkBaseQz - 1 + hz;
                for (int hx = 0; hx < 6; hx++) {
                    if (hx >= 1 && hx <= 4 && hz >= 1 && hz <= 4) {
                        continue;
                    }
                    int colQi = hz * 6 + hx;
                    if (biomeResolver != null) {
                        int wQx = chunkBaseQx - 1 + hx;
                        for (int qyRel = 0; qyRel < activeHaloQuartsY; qyRel++) {
                            int ord = Math.max(0, biomeResolver.resolveBiomeOrdinal(wQx, chunkBaseQy + qyRel, wQz));
                            haloBiomes6x6[qyRel * 36 + colQi] = ord;
                        }
                    } else {
                        int srcColQi = Math.max(1, Math.min(4, hz)) * 6 + Math.max(1, Math.min(4, hx));
                        for (int qyRel = 0; qyRel < activeHaloQuartsY; qyRel++) {
                            haloBiomes6x6[qyRel * 36 + colQi] = haloBiomes6x6[qyRel * 36 + srcColQi];
                        }
                    }
                }
            }
            int ceilProbeMax = exactMode ? Math.max(1, maxBoundY - minBoundY + 1) : 24;
            boolean tempColConst = rc.temperatureChannel() != null && rc.temperatureChannel().isColumnConstantY();
            for (int lz = 0; lz < 16; lz++) {
                int bz = chunkBaseBz + lz;
                for (int lx = 0; lx < 16; lx++) {
                    int col = (lz << 4) | lx;
                    int bx = chunkBaseBx + lx;
                    int posHash = mixColumnSeed(worldSeed, bx, bz);
                    int surfaceDepth = computeSmoothSurfaceDepth(worldSeed, bx, bz, posHash);
                    int hx0 = topSurfaceY[(lz << 4) | Math.max(0, lx - 1)];
                    int hx1 = topSurfaceY[(lz << 4) | Math.min(15, lx + 1)];
                    int hz0 = topSurfaceY[(Math.max(0, lz - 1) << 4) | lx];
                    int hz1 = topSurfaceY[(Math.min(15, lz + 1) << 4) | lx];
                    int gradX = hx1 - hx0;
                    int gradZ = hz1 - hz0;
                    int steep = (Math.abs(gradX) >= 4 || Math.abs(gradZ) >= 4) ? 1 : 0;
                    int colPrelimY = prelimSurfaceY[col];
                    float colTempVal = tempColConst
                            ? (exactMode
                                    ? (float) rc.temperatureChannel().sampleFp64(bx, 0, bz)
                                    : rc.temperatureChannel().sample(bx, 0, bz))
                            : 0.5f;

                    int floorDepth = -1;
                    boolean fluidAbove = false;
                    int waterHeight = Integer.MIN_VALUE;
                    int startSurfWy = Math.min(maxBoundY, Math.max(seaLevel, topSurfaceY[col]) + 1);

                    for (int wy = startSurfWy; wy >= minBoundY; wy--) {
                        int sIdx = (wy - spec.chunkMinY()) >> 4;
                        int uSecSlot = frame.uniformSlotId(sIdx);
                        if (uSecSlot == 0) {
                            floorDepth = -1;
                            fluidAbove = false;
                            waterHeight = Integer.MIN_VALUE;
                            wy = spec.chunkMinY() + (sIdx << 4);
                            continue;
                        }
                        int idx = ((wy & 15) << 8) | col;
                        int cur = uSecSlot >= 0 ? uSecSlot : frame.getVoxel(sIdx, idx);
                        if (cur == 0) {
                            floorDepth = -1;
                            fluidAbove = false;
                            waterHeight = Integer.MIN_VALUE;
                            continue;
                        }
                        if ((defaultFluid != 0 && cur == defaultFluid) || (lavaSlot > 0 && cur == lavaSlot)) {
                            floorDepth = -1;
                            if (!fluidAbove) {
                                waterHeight = wy + 1;
                            }
                            fluidAbove = true;
                            continue;
                        }
                        floorDepth++;
                        if (cur != defaultBlock) {
                            continue;
                        }
                        int ceilDepth;
                        if (uSecSlot == defaultBlock && (wy & 15) >= ceilProbeMax) {
                            ceilDepth = ceilProbeMax;
                        } else {
                            ceilDepth = 0;
                            for (int cd = 1; cd <= ceilProbeMax; cd++) {
                                int byProbe = wy - cd;
                                if (byProbe < minBoundY) {
                                    ceilDepth = ceilProbeMax;
                                    break;
                                }
                                int bv = frame.getVoxel(
                                        (byProbe - spec.chunkMinY()) >> 4, ((byProbe & 15) << 8) | col);
                                if (bv == 0
                                        || (defaultFluid != 0 && bv == defaultFluid)
                                        || (lavaSlot > 0 && bv == lavaSlot)) {
                                    break;
                                }
                                ceilDepth = cd;
                            }
                        }
                        int biomeId = resolveVoronoiZoomedBiomeOrdinal(
                                biomeZoomSeed,
                                bx,
                                wy,
                                bz,
                                chunkBaseQx,
                                chunkBaseQy,
                                chunkBaseQz,
                                quartsY,
                                haloBiomes6x6,
                                biomeResolver);
                        float tempVal = tempColConst
                                ? colTempVal
                                : (rc.temperatureChannel() != null
                                        ? (exactMode
                                                ? (float) rc.temperatureChannel().sampleFp64(bx, wy, bz)
                                                : rc.temperatureChannel().sample(bx, wy, bz))
                                        : 0.5f);
                        int nextSlot = evaluateSurfaceRuleModule(
                                surfaceEvaluator,
                                cur,
                                defaultBlock,
                                bx,
                                wy,
                                bz,
                                floorDepth + 1,
                                ceilDepth + 1,
                                waterHeight,
                                surfaceDepth,
                                biomeId,
                                colPrelimY,
                                steep,
                                gradX,
                                gradZ,
                                0.0,
                                tempVal);
                        if (nextSlot != cur) {
                            frame.setVoxel(sIdx, idx, nextSlot);
                        }
                    }
                }
            }
        }

        applyPostSurfaceCapabilityPasses(
                frame,
                quartBiomes,
                topSurfaceY,
                spec,
                chunkX,
                chunkZ,
                worldSeed,
                applyCarvers,
                applyFeatures,
                leafSlotMask,
                densityEval,
                biomeResolver,
                rc,
                neighborNativeFeaturesMask8,
                false,
                effPolicy);
        frame.finalizeCompactLcaAndHeightmaps(leafSlotMask);
        return frame;
    }

    private static final double[] CARVER_ROTOR_COS = new double[16];
    private static final double[] CARVER_ROTOR_SIN = new double[16];

    static {
        for (int k = 0; k < 16; k++) {
            double theta = (k - 7.5) * 0.08;
            CARVER_ROTOR_COS[k] = Math.cos(theta);
            CARVER_ROTOR_SIN[k] = Math.sin(theta);
        }
    }

    /**
     * Applies lifted post-surface {@code CarverSpec}, {@code OreVeinSpec}, and {@code BiomeFeatureSpec}
     * passes in place over synthesized chunk sections (shared across CPU and OpenCL GPU chunk pipelines).
     */
    public static void applyPostSurfaceCapabilityPasses(
            int[][] sections,
            int[] quartBiomes,
            int[] topSurfaceY,
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            boolean applyCarvers,
            boolean applyFeatures
    ) {
        Objects.requireNonNull(sections, "sections");
        Objects.requireNonNull(spec, "spec");
        LcaCapabilityChunkFrame wrapper = new LcaCapabilityChunkFrame(spec, quartBiomes, topSurfaceY);
        int limit = Math.min(wrapper.sectionCount(), sections.length);
        for (int s = 0; s < limit; s++) {
            wrapper.sparseSections[s] = sections[s];
            wrapper.uniformSlotIds[s] = -1;
        }
        applyPostSurfaceCapabilityPasses(
                wrapper, quartBiomes, topSurfaceY, spec, chunkX, chunkZ, worldSeed, applyCarvers, applyFeatures, null);
    }

    public static void applyPostSurfaceCapabilityPasses(
            LcaCapabilityChunkFrame frame,
            int[] quartBiomes,
            int[] topSurfaceY,
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            boolean applyCarvers,
            boolean applyFeatures
    ) {
        applyPostSurfaceCapabilityPasses(
                frame, quartBiomes, topSurfaceY, spec, chunkX, chunkZ, worldSeed, applyCarvers, applyFeatures, null);
    }

    public static void applyPostSurfaceCapabilityPasses(
            LcaCapabilityChunkFrame frame,
            int[] quartBiomes,
            int[] topSurfaceY,
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            boolean applyCarvers,
            boolean applyFeatures,
            boolean[] leafSlotMask
    ) {
        applyPostSurfaceCapabilityPasses(
                frame,
                quartBiomes,
                topSurfaceY,
                spec,
                chunkX,
                chunkZ,
                worldSeed,
                applyCarvers,
                applyFeatures,
                leafSlotMask,
                null,
                null);
    }

    public static void applyPostSurfaceCapabilityPasses(
            LcaCapabilityChunkFrame frame,
            int[] quartBiomes,
            int[] topSurfaceY,
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            boolean applyCarvers,
            boolean applyFeatures,
            boolean[] leafSlotMask,
            DensityFieldEvaluator borderDensityEval,
            QuartBiomeOrdinalResolver borderBiomeSampler
    ) {
        applyPostSurfaceCapabilityPasses(
                frame,
                quartBiomes,
                topSurfaceY,
                spec,
                chunkX,
                chunkZ,
                worldSeed,
                applyCarvers,
                applyFeatures,
                leafSlotMask,
                borderDensityEval,
                borderBiomeSampler,
                null,
                0,
                false);
    }

    public static void applyBorderTreeOverhangPasses(
            LcaCapabilityChunkFrame frame,
            int[] quartBiomes,
            int[] topSurfaceY,
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            boolean[] leafSlotMask,
            DensityFieldEvaluator borderDensityEval,
            QuartBiomeOrdinalResolver borderBiomeSampler,
            RouterCapabilityChannels routerChannels,
            int neighborNativeFeaturesMask8
    ) {
        applyPostSurfaceCapabilityPasses(
                frame,
                quartBiomes,
                topSurfaceY,
                spec,
                chunkX,
                chunkZ,
                worldSeed,
                false,
                true,
                leafSlotMask,
                borderDensityEval,
                borderBiomeSampler,
                routerChannels,
                neighborNativeFeaturesMask8,
                true);
    }

    public static void applyPostSurfaceCapabilityPasses(
            LcaCapabilityChunkFrame frame,
            int[] quartBiomes,
            int[] topSurfaceY,
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            boolean applyCarvers,
            boolean applyFeatures,
            boolean[] leafSlotMask,
            DensityFieldEvaluator borderDensityEval,
            QuartBiomeOrdinalResolver borderBiomeSampler,
            RouterCapabilityChannels routerChannels,
            int neighborNativeFeaturesMask8,
            boolean borderTreeOverhangsOnly
    ) {
        applyPostSurfaceCapabilityPasses(
                frame,
                quartBiomes,
                topSurfaceY,
                spec,
                chunkX,
                chunkZ,
                worldSeed,
                applyCarvers,
                applyFeatures,
                leafSlotMask,
                borderDensityEval,
                borderBiomeSampler,
                routerChannels,
                neighborNativeFeaturesMask8,
                borderTreeOverhangsOnly,
                frame != null
                        ? frame.fidelityPolicy()
                        : WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE);
    }

    public static void applyPostSurfaceCapabilityPasses(
            LcaCapabilityChunkFrame frame,
            int[] quartBiomes,
            int[] topSurfaceY,
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            boolean applyCarvers,
            boolean applyFeatures,
            boolean[] leafSlotMask,
            DensityFieldEvaluator borderDensityEval,
            QuartBiomeOrdinalResolver borderBiomeSampler,
            RouterCapabilityChannels routerChannels,
            int neighborNativeFeaturesMask8,
            boolean borderTreeOverhangsOnly,
            WorldgenKernelSwapper.WorldgenFidelityPolicy fidelityPolicy
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(spec, "spec");
        boolean exactMode = fidelityPolicy != null && fidelityPolicy.isExact();
        int noiseMinY = spec.noiseMinY();
        int noiseTopY = noiseMinY + spec.noiseHeight() - 1;
        int minBoundY = Math.max(spec.chunkMinY(), noiseMinY);
        int maxBoundY = Math.min(spec.chunkMinY() + (spec.chunkSections() << 4) - 1, noiseTopY);

        if (!borderTreeOverhangsOnly && applyCarvers && spec.carverSpecs() != null && spec.carverSpecs().length > 0) {
            applyCarverSpecs(
                    frame,
                    quartBiomes,
                    topSurfaceY,
                    spec,
                    chunkX,
                    chunkZ,
                    worldSeed,
                    minBoundY,
                    maxBoundY,
                    borderBiomeSampler);
        }

        if (applyFeatures) {
            long[] presentBiomes = buildPresentBiomeBitMask(quartBiomes);
            if (spec.placedFeatureSpecs() != null && spec.placedFeatureSpecs().length > 0) {
                executePlacedFeatureSpecs(
                        frame,
                        quartBiomes,
                        presentBiomes,
                        topSurfaceY,
                        leafSlotMask,
                        spec,
                        chunkX,
                        chunkZ,
                        worldSeed,
                        minBoundY,
                        maxBoundY,
                        borderDensityEval,
                        borderBiomeSampler,
                        routerChannels,
                        neighborNativeFeaturesMask8,
                        borderTreeOverhangsOnly);
            } else if (!borderTreeOverhangsOnly) {
                // Legacy OreVeinSpec execution (for direct DimensionCapabilitySpec callers without placedFeatureSpecs)
                if (spec.oreSpecs() != null && spec.oreSpecs().length > 0) {
                OreVeinSpec[] ores = spec.oreSpecs();
                for (int oi = 0; oi < ores.length; oi++) {
                    OreVeinSpec ore = ores[oi];
                    if (ore.allowedBiomeMask() != null && !bitMasksIntersect(ore.allowedBiomeMask(), presentBiomes)) {
                        continue;
                    }
                    int loY = Math.max(minBoundY, ore.minY());
                    int hiY = Math.min(maxBoundY, ore.maxY());
                    if (hiY < loY || ore.countPerChunk() <= 0) {
                        continue;
                    }
                    int span = hiY - loY + 1;
                    int blobLen = Math.max(1, ore.veinSize());
                    long[] targetMask = ore.targetSlotMask();
                    for (int c = 0; c < ore.countPerChunk(); c++) {
                        int h = mixColumnSeed(worldSeed ^ (oi * 0x9E3779B9L), (chunkX << 5) + c, (chunkZ << 5) ^ oi);
                        int ox = h & 15;
                        int oz = (h >>> 4) & 15;
                        int oy;
                        if (ore.triangleDistribution()) {
                            int a = ((h >>> 8) & 0xFF) * span / 256;
                            int b = ((h >>> 16) & 0xFF) * span / 256;
                            oy = loY + ((a + b) >>> 1);
                        } else {
                            oy = loY + (((h >>> 8) & 0xFFFF) * span / 65536);
                        }
                        if (ore.allowedBiomeMask() != null && quartBiomes != null && quartBiomes.length > 0) {
                            int ord = sampleQuartBiomeAt(quartBiomes, spec, ox, oy, oz);
                            if (!bitMaskContains(ore.allowedBiomeMask(), ord)) {
                                continue;
                            }
                        }
                        int sIdx = (oy - spec.chunkMinY()) >> 4;
                        if (sIdx < 0 || sIdx >= frame.sectionCount()) {
                            continue;
                        }
                        int uSlot = frame.uniformSlotId(sIdx);
                        if (uSlot >= 0 && !matchesTargetRule(uSlot, ore.targetBlockSlot(), targetMask)) {
                            continue;
                        }
                        int ly = oy & 15;
                        if (exactMode) {
                            double radius = Math.max(0.75, Math.cbrt(blobLen * 0.24));
                            int radCeil = Math.max(1, (int) Math.ceil(radius));
                            double r2 = radius * radius;
                            for (int dy = -radCeil; dy <= radCeil; dy++) {
                                int wy = oy + dy;
                                if (wy < loY || wy > hiY) {
                                    continue;
                                }
                                int sec = (wy - spec.chunkMinY()) >> 4;
                                if (sec < 0 || sec >= frame.sectionCount()) {
                                    continue;
                                }
                                for (int dz = -radCeil; dz <= radCeil; dz++) {
                                    int lz = oz + dz;
                                    if (lz < 0 || lz >= 16) {
                                        continue;
                                    }
                                    for (int dx = -radCeil; dx <= radCeil; dx++) {
                                        int lx = ox + dx;
                                        if (lx < 0 || lx >= 16) {
                                            continue;
                                        }
                                        if ((double) (dx * dx + dy * dy + dz * dz) > r2) {
                                            continue;
                                        }
                                        int yzx = ((wy & 15) << 8) | (lz << 4) | lx;
                                        int cur = frame.getVoxel(sec, yzx);
                                        if (matchesTargetRule(cur, ore.targetBlockSlot(), targetMask)) {
                                            frame.setVoxel(sec, yzx, ore.oreBlockSlot());
                                        }
                                    }
                                }
                            }
                            continue;
                        }
                        int anchorH3 = WorldgenKernelSwapper.yzxToH3(ox, ly, oz);
                        int octBaseH3 = anchorH3 & ~63;
                        int octBaseWy = spec.chunkMinY() + (sIdx << 4) + ((ly >>> 2) << 2);
                        int minLySub = Math.max(0, loY - octBaseWy);
                        int maxLySub = Math.min(3, hiY - octBaseWy);
                        if (minLySub > maxLySub) {
                            continue;
                        }
                        int validLoH3 = octBaseH3 + (minLySub << 4);
                        int validHiH3Excl = octBaseH3 + ((maxLySub + 1) << 4);
                        int clampedLen = Math.min(blobLen, validHiH3Excl - validLoH3);
                        int startH3 = Math.max(validLoH3, Math.min(anchorH3, validHiH3Excl - clampedLen));
                        int endH3Excl = startH3 + clampedLen;
                        for (int h3 = startH3; h3 < endH3Excl; h3++) {
                            int yzx = WorldgenKernelSwapper.h3ToYzx(h3);
                            int cur = frame.getVoxel(sIdx, yzx);
                            if (matchesTargetRule(cur, ore.targetBlockSlot(), targetMask)) {
                                frame.setVoxel(sIdx, yzx, ore.oreBlockSlot());
                            }
                        }
                    }
                }
            }

            // Legacy BiomeFeatureSpec execution (for direct DimensionCapabilitySpec callers without placedFeatureSpecs)
            if (spec.featureSpecs() != null && spec.featureSpecs().length > 0) {
                BiomeFeatureSpec[] feats = spec.featureSpecs();
                int[] candScratch = new int[16];
                for (int fi = 0; fi < feats.length; fi++) {
                    BiomeFeatureSpec feat = feats[fi];
                    if (feat.countPerChunk() <= 0) {
                        continue;
                    }
                    int reqBiome = feat.biomeOrdinal();
                    if (reqBiome >= 0 && presentBiomes != null && !bitMaskContains(presentBiomes, reqBiome)) {
                        continue;
                    }
                    for (int slot = 0; slot < feat.countPerChunk(); slot++) {
                        int h = mixColumnSeed(
                                worldSeed ^ (fi * 0x517CC1B727220A95L), (chunkX << 4) + slot, (chunkZ << 4) ^ slot);
                        int ax = 2 + (Math.abs(h) % 12);
                        int az = 2 + (Math.abs(h >>> 8) % 12);
                        int groundY = findFeatureGroundY(
                                frame,
                                quartBiomes,
                                spec,
                                feat,
                                ax,
                                az,
                                topSurfaceY[(az << 4) | ax],
                                minBoundY,
                                maxBoundY,
                                h,
                                candScratch);
                        if (groundY == Integer.MIN_VALUE) {
                            continue;
                        }
                        stampFeatureAt(frame, spec, feat, ax, groundY + 1, az, h);
                    }
                }
            }
            }
        }
    }

    private static boolean matchesTargetRule(int slot, int fallbackSlot, long[] targetMask) {
        if (slot < 0) {
            return false;
        }
        if (targetMask != null && targetMask.length > 0) {
            return bitMaskContains(targetMask, slot);
        }
        return slot == fallbackSlot;
    }

    private static long[] buildPresentBiomeBitMask(int[] quartBiomes) {
        if (quartBiomes == null || quartBiomes.length == 0) {
            return null;
        }
        int maxOrd = 0;
        for (int ord : quartBiomes) {
            if (ord > maxOrd) {
                maxOrd = ord;
            }
        }
        long[] mask = new long[(maxOrd >>> 6) + 1];
        for (int ord : quartBiomes) {
            if (ord >= 0) {
                mask[ord >>> 6] |= (1L << (ord & 63));
            }
        }
        return mask;
    }

    private static int sampleQuartBiomeAt(int[] quartBiomes, DimensionCapabilitySpec spec, int lx, int wy, int lz) {
        if (quartBiomes == null || quartBiomes.length == 0) {
            return 0;
        }
        int qx = Math.max(0, Math.min(3, lx >> 2));
        int qz = Math.max(0, Math.min(3, lz >> 2));
        int maxQuartsY = Math.max(1, quartBiomes.length >>> 4);
        int qy = Math.max(0, Math.min(maxQuartsY - 1, (wy - spec.chunkMinY()) >> 2));
        return quartBiomes[(((qy << 2) | qz) << 2) | qx];
    }

    private static void applyCarverSpecs(
            LcaCapabilityChunkFrame frame,
            int[] quartBiomes,
            int[] topSurfaceY,
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            int minBoundY,
            int maxBoundY,
            QuartBiomeOrdinalResolver borderBiomeSampler
    ) {
        CarverSpec[] carvers = spec.carverSpecs();
        int chunkBaseBx = chunkX << 4;
        int chunkBaseBz = chunkZ << 4;
        int defaultFluid = spec.defaultFluidSlot();
        int lavaSlot = spec.lavaFluidSlot();
        int carveFluid = lavaSlot > 0 ? lavaSlot : defaultFluid;
        boolean anySurfaceChanged = false;
        int secCount = frame.sectionCount();

        for (int ci = 0; ci < carvers.length; ci++) {
            CarverSpec carver = carvers[ci];
            if (carver.probabilityPermille() <= 0) {
                continue;
            }
            int loY = Math.max(minBoundY + 2, carver.minY());
            int hiY = Math.min(maxBoundY - 2, carver.maxY());
            if (hiY < loY) {
                continue;
            }
            double hScale = Math.max(0.5, carver.horizScalePermille() / 1000.0);
            double vScale = Math.max(0.5, carver.vertScalePermille() / 1000.0);
            double rxz = (carver.kind() == CARVER_CANYON ? 2.0 : 2.6) * hScale;
            double ry = (carver.kind() == CARVER_CANYON ? 6.5 : 2.5) * vScale;
            double invRxz2 = 1.0 / (rxz * rxz);
            double invRy2 = 1.0 / (ry * ry);
            int steps = carver.kind() == CARVER_CANYON ? 16 : 14;
            double maxHorizontalReach = steps * 2.2 + rxz;
            int chunkRange = Math.max(
                    1,
                    Math.min(
                            Math.max(1, carver.rangeChunks()),
                            (int) Math.ceil((maxHorizontalReach + 15.0) / 16.0)));

            for (int dcz = -chunkRange; dcz <= chunkRange; dcz++) {
                for (int dcx = -chunkRange; dcx <= chunkRange; dcx++) {
                    int ocx = chunkX + dcx;
                    int ocz = chunkZ + dcz;
                    int h = mixColumnSeed(worldSeed ^ ((ci + 1L) * 0x94D049BB133111EBL), ocx, ocz);
                    if ((Math.abs(h) % 1000) >= carver.probabilityPermille()) {
                        continue;
                    }
                    double wx = (ocx << 4) + ((h >>> 4) & 15);
                    double wz = (ocz << 4) + ((h >>> 8) & 15);
                    if (wx + maxHorizontalReach < chunkBaseBx
                            || wx - maxHorizontalReach > chunkBaseBx + 15.0
                            || wz + maxHorizontalReach < chunkBaseBz
                            || wz - maxHorizontalReach > chunkBaseBz + 15.0) {
                        continue;
                    }
                    double wy = loY + (Math.abs(h >>> 12) % (hiY - loY + 1));
                    if (carver.biomeOrdinal() >= 0) {
                        int bOrd;
                        if (dcx == 0 && dcz == 0) {
                            int qyRel = Math.max(0, Math.min((secCount << 2) - 1, ((int) wy - spec.chunkMinY()) >> 2));
                            bOrd = quartBiomes[(qyRel << 4) | ((((int) wz & 15) >> 2) << 2) | (((int) wx & 15) >> 2)];
                        } else if (borderBiomeSampler != null) {
                            bOrd = borderBiomeSampler.resolveBiomeOrdinal(((int) wx) >> 2, ((int) wy) >> 2, ((int) wz) >> 2);
                        } else {
                            bOrd = carver.biomeOrdinal();
                        }
                        if (bOrd != carver.biomeOrdinal()) {
                            continue;
                        }
                    }
                    double yaw = ((h & 0xFF) / 256.0) * (Math.PI * 2.0);
                    double pitch = (((h >>> 16) & 0x1F) - 16) * 0.02;
                    double dirX = Math.cos(yaw);
                    double dirZ = Math.sin(yaw);
                    double sinPitchStep = Math.sin(pitch) * 1.2;

                    for (int step = 0; step < steps; step++) {
                        if (wx + rxz >= chunkBaseBx
                                && wx - rxz <= chunkBaseBx + 15.0
                                && wz + rxz >= chunkBaseBz
                                && wz - rxz <= chunkBaseBz + 15.0) {
                            int minLx = Math.max(0, (int) Math.floor(wx - rxz) - chunkBaseBx);
                            int maxLx = Math.min(15, (int) Math.ceil(wx + rxz) - chunkBaseBx);
                            int minLz = Math.max(0, (int) Math.floor(wz - rxz) - chunkBaseBz);
                            int maxLz = Math.min(15, (int) Math.ceil(wz + rxz) - chunkBaseBz);
                            int minVy = Math.max(minBoundY + 2, (int) Math.floor(wy - ry));
                            int maxVy = Math.min(maxBoundY - 2, (int) Math.ceil(wy + ry));

                            if (minLx <= maxLx && minLz <= maxLz && minVy <= maxVy) {
                                boolean allUniformAir = true;
                                int sLo = Math.max(0, (minVy - spec.chunkMinY()) >> 4);
                                int sHi = Math.min(secCount - 1, (maxVy - spec.chunkMinY()) >> 4);
                                for (int s = sLo; s <= sHi; s++) {
                                    if (frame.uniformSlotId(s) != 0) {
                                        allUniformAir = false;
                                        break;
                                    }
                                }
                                if (!allUniformAir) {
                                    for (int lz = minLz; lz <= maxLz; lz++) {
                                        double dz = (chunkBaseBz + lz + 0.5) - wz;
                                        double dz2 = dz * dz * invRxz2;
                                        if (dz2 >= 1.0) continue;
                                        for (int lx = minLx; lx <= maxLx; lx++) {
                                            double dx = (chunkBaseBx + lx + 0.5) - wx;
                                            double dxz2 = dx * dx * invRxz2 + dz2;
                                            if (dxz2 >= 1.0) continue;
                                            int col = (lz << 4) | lx;
                                            for (int vy = maxVy; vy >= minVy; vy--) {
                                                int sIdx = (vy - spec.chunkMinY()) >> 4;
                                                if (frame.uniformSlotId(sIdx) == 0) {
                                                    int aboveAir = getVoxelAt(frame, spec, lx, vy + 1, lz);
                                                    if ((defaultFluid > 0 && aboveAir == defaultFluid)
                                                            || (lavaSlot > 0 && aboveAir == lavaSlot)) {
                                                        break;
                                                    }
                                                    vy = Math.max(minVy, spec.chunkMinY() + (sIdx << 4));
                                                    continue;
                                                }
                                                double dy = (vy + 0.5) - wy;
                                                if (dxz2 + dy * dy * invRy2 >= 1.0) continue;
                                                int above = getVoxelAt(frame, spec, lx, vy + 1, lz);
                                                if ((defaultFluid > 0 && above == defaultFluid)
                                                        || (lavaSlot > 0 && above == lavaSlot)) {
                                                    break;
                                                }
                                                boolean lateralFluid = false;
                                                if (lx > 0) {
                                                    int n = getVoxelAt(frame, spec, lx - 1, vy, lz);
                                                    lateralFluid |= (defaultFluid > 0 && n == defaultFluid)
                                                            || (lavaSlot > 0 && n == lavaSlot);
                                                }
                                                if (!lateralFluid && lx < 15) {
                                                    int n = getVoxelAt(frame, spec, lx + 1, vy, lz);
                                                    lateralFluid |= (defaultFluid > 0 && n == defaultFluid)
                                                            || (lavaSlot > 0 && n == lavaSlot);
                                                }
                                                if (!lateralFluid && lz > 0) {
                                                    int n = getVoxelAt(frame, spec, lx, vy, lz - 1);
                                                    lateralFluid |= (defaultFluid > 0 && n == defaultFluid)
                                                            || (lavaSlot > 0 && n == lavaSlot);
                                                }
                                                if (!lateralFluid && lz < 15) {
                                                    int n = getVoxelAt(frame, spec, lx, vy, lz + 1);
                                                    lateralFluid |= (defaultFluid > 0 && n == defaultFluid)
                                                            || (lavaSlot > 0 && n == lavaSlot);
                                                }
                                                if (lateralFluid) {
                                                    continue;
                                                }
                                                int idx = ((vy & 15) << 8) | col;
                                                int cur = frame.getVoxel(sIdx, idx);
                                                if (cur == 0
                                                        || (defaultFluid > 0 && cur == defaultFluid)
                                                        || (lavaSlot > 0 && cur == lavaSlot)) {
                                                    continue;
                                                }
                                                frame.setVoxel(
                                                        sIdx,
                                                        idx,
                                                        (carveFluid > 0 && vy <= carver.lavaLevelY()) ? carveFluid : 0);
                                                if (vy >= topSurfaceY[col] - 1) {
                                                    anySurfaceChanged = true;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        int rotorIdx = mixColumnSeed(h, step, ci) & 15;
                        double rcCos = CARVER_ROTOR_COS[rotorIdx];
                        double rcSin = CARVER_ROTOR_SIN[rotorIdx];
                        double nextDirX = dirX * rcCos - dirZ * rcSin;
                        double nextDirZ = dirX * rcSin + dirZ * rcCos;
                        dirX = nextDirX;
                        dirZ = nextDirZ;
                        wx += dirX * 2.2;
                        wz += dirZ * 2.2;
                        wy = Math.max(loY, Math.min(hiY, wy + sinPitchStep));
                    }
                }
            }
        }

        if (anySurfaceChanged) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int col = (lz << 4) | lx;
                    int top = spec.chunkMinY() - 1;
                    for (int wy = maxBoundY; wy >= minBoundY; wy--) {
                        int sIdx = (wy - spec.chunkMinY()) >> 4;
                        int uSlot = frame.uniformSlotId(sIdx);
                        if (uSlot == 0
                                || (defaultFluid > 0 && uSlot == defaultFluid)
                                || (lavaSlot > 0 && uSlot == lavaSlot)) {
                            wy = spec.chunkMinY() + (sIdx << 4);
                            continue;
                        }
                        int cur = frame.getVoxel(sIdx, ((wy & 15) << 8) | col);
                        if (cur != 0
                                && (defaultFluid == 0 || cur != defaultFluid)
                                && (lavaSlot == 0 || cur != lavaSlot)) {
                            top = wy;
                            break;
                        }
                    }
                    topSurfaceY[col] = top;
                }
            }
        }
    }

    private static int findFeatureGroundY(
            LcaCapabilityChunkFrame frame,
            int[] quartBiomes,
            DimensionCapabilitySpec spec,
            BiomeFeatureSpec feat,
            int lx,
            int lz,
            int surfaceTopY,
            int minBoundY,
            int maxBoundY,
            int hash,
            int[] candidates
    ) {
        if (feat.placementMode() == PLACEMENT_SURFACE_HEIGHTMAP) {
            if (surfaceTopY < minBoundY || surfaceTopY + 2 > maxBoundY) {
                return Integer.MIN_VALUE;
            }
            if (spec.defaultFluidSlot() > 0 && surfaceTopY < spec.seaLevel() - feat.maxWaterDepth()) {
                return Integer.MIN_VALUE;
            }
            int qyRel = (surfaceTopY - spec.chunkMinY()) >> 2;
            int bOrd = quartBiomes[(qyRel << 4) | ((lz >> 2) << 2) | (lx >> 2)];
            if (feat.biomeOrdinal() >= 0 && bOrd != feat.biomeOrdinal()) {
                return Integer.MIN_VALUE;
            }
            int groundVox = getVoxelAt(frame, spec, lx, surfaceTopY, lz);
            if (feat.requiredGroundSlot() > 0 && groundVox != feat.requiredGroundSlot()) {
                return Integer.MIN_VALUE;
            }
            int aboveVox = getVoxelAt(frame, spec, lx, surfaceTopY + 1, lz);
            if (aboveVox != 0 && (feat.maxWaterDepth() <= 0 || aboveVox != spec.defaultFluidSlot())) {
                return Integer.MIN_VALUE;
            }
            return surfaceTopY;
        }

        int scanLo = Math.max(minBoundY, feat.minY());
        int scanHi = Math.min(maxBoundY - 3, feat.maxY());
        int candCount = 0;
        for (int wy = scanLo; wy <= scanHi && candCount < candidates.length; wy++) {
            int sIdx = (wy - spec.chunkMinY()) >> 4;
            int uSlot = frame.uniformSlotId(sIdx);
            if (uSlot >= 0) {
                int secTopWy = spec.chunkMinY() + (sIdx << 4) + 15;
                if (uSlot == 0 || (spec.defaultFluidSlot() > 0 && uSlot == spec.defaultFluidSlot())) {
                    wy = secTopWy;
                    continue;
                }
                if (wy < secTopWy) {
                    wy = Math.min(scanHi, secTopWy) - 1;
                    continue;
                }
            }
            int groundVox = getVoxelAt(frame, spec, lx, wy, lz);
            int aboveVox = getVoxelAt(frame, spec, lx, wy + 1, lz);
            if (groundVox == 0 || (spec.defaultFluidSlot() > 0 && groundVox == spec.defaultFluidSlot())) {
                continue;
            }
            if (aboveVox != 0 && (feat.maxWaterDepth() <= 0 || aboveVox != spec.defaultFluidSlot())) {
                continue;
            }
            if (feat.requiredGroundSlot() > 0 && groundVox != feat.requiredGroundSlot()) {
                continue;
            }
            int qyRel = (wy - spec.chunkMinY()) >> 2;
            int bOrd = quartBiomes[(qyRel << 4) | ((lz >> 2) << 2) | (lx >> 2)];
            if (feat.biomeOrdinal() >= 0 && bOrd != feat.biomeOrdinal()) {
                continue;
            }
            candidates[candCount++] = wy;
        }
        if (candCount == 0) {
            return Integer.MIN_VALUE;
        }
        return candidates[Math.abs(hash >>> 16) % candCount];
    }

    private static void stampFeatureAt(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            BiomeFeatureSpec feat,
            int ax,
            int baseWy,
            int az,
            int hash
    ) {
        int shape = feat.shapeKind();
        if (shape == SHAPE_PATCH || shape == SHAPE_MULTIFACE) {
            setVoxelIfAir(frame, spec, ax, baseWy, az, feat.trunkSlot(), false);
            return;
        }
        if (shape == SHAPE_FALLEN_TREE) {
            int len = Math.max(2, Math.min(5, feat.baseHeight() + (feat.heightRand() > 0 ? (Math.abs(hash >>> 20) % (feat.heightRand() + 1)) : 0)));
            boolean alongX = ((hash >>> 3) & 1) == 0;
            for (int i = 0; i < len; i++) {
                int lx = alongX ? (ax + i - (len >> 1)) : ax;
                int lz = alongX ? az : (az + i - (len >> 1));
                if (lx >= 0 && lx <= 15 && lz >= 0 && lz <= 15) {
                    setVoxelIfAir(frame, spec, lx, baseWy, lz, feat.trunkSlot(), false);
                }
            }
            return;
        }
        if (shape == SHAPE_SCULK_PATCH || shape == SHAPE_VEGETATION_PATCH) {
            int r = Math.max(1, Math.min(3, feat.canopyRadius()));
            int groundY = baseWy - 1;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (dx * dx + dz * dz > r * r + 1) continue;
                    int lx = ax + dx;
                    int lz = az + dz;
                    if (lx < 0 || lx > 15 || lz < 0 || lz > 15) continue;
                    int gv = getVoxelAt(frame, spec, lx, groundY, lz);
                    if (gv > 0 && gv != spec.defaultFluidSlot() && gv != spec.lavaFluidSlot()) {
                        int sIdx = (groundY - spec.chunkMinY()) >> 4;
                        if (sIdx >= 0 && sIdx < frame.sectionCount()) {
                            frame.setVoxel(sIdx, ((groundY & 15) << 8) | (lz << 4) | lx, feat.trunkSlot());
                        }
                        if (feat.foliageSlot() > 0 && ((hash ^ (dx * 17 + dz * 31)) & 3) == 0) {
                            setVoxelIfAir(frame, spec, lx, baseWy, lz, feat.foliageSlot(), false);
                        }
                    }
                }
            }
            return;
        }
        if (shape == SHAPE_BOULDER) {
            int r = Math.max(1, Math.min(2, feat.canopyRadius()));
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dx = -r; dx <= r; dx++) {
                        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > r + 1) continue;
                        int lx = ax + dx;
                        int lz = az + dz;
                        int wy = baseWy + dy;
                        if (lx >= 0 && lx <= 15 && lz >= 0 && lz <= 15) {
                            int sIdx = (wy - spec.chunkMinY()) >> 4;
                            if (sIdx >= 0 && sIdx < frame.sectionCount()) {
                                frame.setVoxel(sIdx, ((wy & 15) << 8) | (lz << 4) | lx, feat.trunkSlot());
                            }
                        }
                    }
                }
            }
            return;
        }
        if (shape == SHAPE_GEODE) {
            int r = 3;
            int cy = baseWy + 1;
            int outerSlot = feat.trunkSlot() > 0 ? feat.trunkSlot() : spec.defaultBlockSlot();
            int midSlot = feat.foliageSlot() > 0 ? feat.foliageSlot() : outerSlot;
            int innerSlot = feat.accentSlot() > 0 ? feat.accentSlot() : midSlot;
            for (int dy = -r; dy <= r; dy++) {
                int wy = cy + dy;
                int sIdx = (wy - spec.chunkMinY()) >> 4;
                if (sIdx < 0 || sIdx >= frame.sectionCount()) continue;
                for (int dz = -r; dz <= r; dz++) {
                    int lz = az + dz;
                    if (lz < 0 || lz > 15) continue;
                    for (int dx = -r; dx <= r; dx++) {
                        int lx = ax + dx;
                        if (lx < 0 || lx > 15) continue;
                        int d2 = dx * dx + dy * dy + dz * dz;
                        if (d2 > r * r) continue;
                        int slot = (d2 <= 1) ? 0 : (d2 <= 3 ? innerSlot : (d2 <= 6 ? midSlot : outerSlot));
                        frame.setVoxel(sIdx, ((wy & 15) << 8) | (lz << 4) | lx, slot);
                    }
                }
            }
            return;
        }
        if (shape == SHAPE_DRIPSTONE_CLUSTER || shape == SHAPE_SPIKE || shape == SHAPE_END_SPIKE) {
            int colH = Math.max(2, Math.min(8, feat.baseHeight() + (feat.heightRand() > 0 ? (Math.abs(hash >>> 20) % (feat.heightRand() + 1)) : 0)));
            int tipSlot = feat.foliageSlot() > 0 ? feat.foliageSlot() : feat.trunkSlot();
            for (int i = 0; i < colH; i++) {
                int slot = (i >= colH - 2) ? tipSlot : feat.trunkSlot();
                setVoxelIfAir(frame, spec, ax, baseWy + i, az, slot, false);
            }
            return;
        }
        int randExtra = feat.heightRand() > 0 ? (Math.abs(hash >>> 20) % (feat.heightRand() + 1)) : 0;
        int trunkH = Math.max(3, feat.baseHeight() + randExtra);
        int topWy = baseWy + trunkH - 1;
        int radius = Math.max(1, feat.canopyRadius());

        for (int y = baseWy; y <= topWy + 1; y++) {
            int cur = getVoxelAt(frame, spec, ax, y, az);
            if (cur != 0 && cur != spec.defaultFluidSlot()) {
                trunkH = y - baseWy - 1;
                if (trunkH < 3) {
                    return;
                }
                topWy = baseWy + trunkH - 1;
                break;
            }
        }

        if (feat.shapeKind() == SHAPE_HUGE_FUNGUS) {
            for (int y = baseWy; y <= topWy; y++) {
                setVoxelIfAir(frame, spec, ax, y, az, feat.trunkSlot(), true);
            }
            int capStart = Math.max(baseWy + 2, topWy - 2);
            for (int y = capStart; y <= topWy + 1; y++) {
                int r = (y > topWy) ? Math.max(1, radius - 1) : radius;
                for (int dz = -r; dz <= r; dz++) {
                    for (int dx = -r; dx <= r; dx++) {
                        int lx = ax + dx;
                        int lz = az + dz;
                        if (lx < 0 || lx > 15 || lz < 0 || lz > 15) continue;
                        if (Math.abs(dx) == r && Math.abs(dz) == r && y <= topWy) continue;
                        boolean shell = y > topWy || Math.abs(dx) == r || Math.abs(dz) == r;
                        if (shell) {
                            setVoxelIfAir(frame, spec, lx, y, lz, feat.foliageSlot(), false);
                        } else if (feat.accentSlot() > 0 && ((hash ^ (dx * 7 + dz * 13 + y)) & 3) == 0) {
                            setVoxelIfAir(frame, spec, lx, y, lz, feat.accentSlot(), false);
                        }
                    }
                }
            }
            return;
        }

        if (feat.shapeKind() == SHAPE_CHORUS) {
            for (int y = baseWy; y <= topWy; y++) {
                setVoxelIfAir(frame, spec, ax, y, az, feat.trunkSlot(), true);
            }
            int flower = feat.foliageSlot() > 0 ? feat.foliageSlot() : feat.trunkSlot();
            setVoxelIfAir(frame, spec, ax, topWy + 1, az, flower, true);
            for (int b = 0; b < 2; b++) {
                int dir = ((hash >>> (b * 2)) + b) & 3;
                int bx = ax + (dir == 0 ? 1 : (dir == 1 ? -1 : 0));
                int bz = az + (dir == 2 ? 1 : (dir == 3 ? -1 : 0));
                if (bx >= 0 && bx <= 15 && bz >= 0 && bz <= 15) {
                    setVoxelIfAir(frame, spec, bx, topWy, bz, feat.trunkSlot(), false);
                    setVoxelIfAir(frame, spec, bx, topWy + 1, bz, flower, false);
                }
            }
            return;
        }

        int trunkWidth = feat.shapeKind() == SHAPE_GIANT_2X2 ? 2 : 1;
        for (int y = baseWy; y <= topWy; y++) {
            for (int tx = 0; tx < trunkWidth; tx++) {
                for (int tz = 0; tz < trunkWidth; tz++) {
                    if (ax + tx <= 15 && az + tz <= 15) {
                        setVoxelIfAir(frame, spec, ax + tx, y, az + tz, feat.trunkSlot(), true);
                    }
                }
            }
        }
        if (feat.foliageSlot() <= 0) {
            return;
        }
        int crownLo = feat.shapeKind() == SHAPE_CONIFER ? (baseWy + 2) : Math.max(baseWy + 2, topWy - 2);
        for (int y = crownLo; y <= topWy + 1; y++) {
            int r = (y >= topWy) ? Math.max(1, radius - 1) : radius;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int lx = ax + dx;
                    int lz = az + dz;
                    if (lx < 0 || lx > 15 || lz < 0 || lz > 15) continue;
                    if (Math.abs(dx) == r && Math.abs(dz) == r && ((hash + y) & 1) == 0) continue;
                    if (feat.maxWaterDepth() == 0
                            && spec.defaultFluidSlot() > 0
                            && y <= spec.seaLevel() + 1
                            && getVoxelAt(frame, spec, lx, spec.seaLevel(), lz) == spec.defaultFluidSlot()) {
                        continue;
                    }
                    setVoxelIfAir(frame, spec, lx, y, lz, feat.foliageSlot(), false);
                }
            }
        }
    }

    private static int getVoxelAt(
            LcaCapabilityChunkFrame frame, DimensionCapabilitySpec spec, int lx, int wy, int lz) {
        int secIdx = (wy - spec.chunkMinY()) >> 4;
        if (secIdx < 0 || secIdx >= frame.sectionCount()) {
            return -1;
        }
        return frame.getVoxel(secIdx, ((wy & 15) << 8) | (lz << 4) | lx);
    }

    private static void setVoxelIfAir(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            int lx,
            int wy,
            int lz,
            int stateSlot,
            boolean overwriteFoliage
    ) {
        setVoxelIfAirOrLeaf(frame, spec, null, lx, wy, lz, stateSlot, overwriteFoliage);
    }

    private static boolean setVoxelIfAirOrLeaf(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            boolean[] leafSlotMask,
            int lx,
            int wy,
            int lz,
            int stateSlot,
            boolean overwriteFoliage
    ) {
        if (lx < 0 || lx > 15 || lz < 0 || lz > 15 || stateSlot <= 0) {
            return false;
        }
        int secIdx = (wy - spec.chunkMinY()) >> 4;
        if (secIdx < 0 || secIdx >= frame.sectionCount()) {
            return false;
        }
        int idx = ((wy & 15) << 8) | (lz << 4) | lx;
        int cur = frame.getVoxel(secIdx, idx);
        if (cur == 0 || (overwriteFoliage && isLeafSlot(leafSlotMask, cur))) {
            frame.setVoxel(secIdx, idx, stateSlot);
            return true;
        }
        return false;
    }

    private static boolean isLeafSlot(boolean[] leafSlotMask, int slot) {
        return leafSlotMask != null && slot >= 0 && slot < leafSlotMask.length && leafSlotMask[slot];
    }

    static final class DecorRng {
        private long state;

        void reseed(long worldSeed, int chunkX, int chunkZ, int stepIndex, int salt) {
            long xMul = mix64(worldSeed) | 1L;
            long zMul = mix64(worldSeed ^ 0x9E3779B97F4A7C15L) | 1L;
            long popSeed = ((chunkX << 4) * xMul + (chunkZ << 4) * zMul) ^ worldSeed;
            long decSeed = popSeed + (stepIndex * 10000L) + salt;
            this.state = mix64(decSeed ^ 0x6A09E667F3BCC908L);
            if (this.state == 0L) {
                this.state = 0x9E3779B97F4A7C15L;
            }
        }

        void reseedBlock(long worldSeed, int worldX, int worldY, int worldZ, int stepIndex, int salt) {
            long h = worldSeed ^ (worldX * 0x9E3779B97F4A7C15L)
                    ^ (worldZ * 0xBF58476D1CE4E5B9L)
                    ^ (worldY * 0x94D049BB133111EBL)
                    ^ (stepIndex * 10000L + salt);
            this.state = mix64(h ^ 0x517CC1B727220A95L);
            if (this.state == 0L) {
                this.state = 0x9E3779B97F4A7C15L;
            }
        }

        long nextLong() {
            long z = (state += 0x9E3779B97F4A7C15L);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        int nextInt(int bound) {
            if (bound <= 1) {
                return 0;
            }
            long r = nextLong() >>> 33;
            return (int) ((r * bound) >>> 31);
        }

        boolean nextBoolean() {
            return (nextLong() & 1L) != 0L;
        }

        float nextFloat() {
            return ((int) (nextLong() >>> 40)) * 0x1.0p-24f;
        }

        private static long mix64(long z) {
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }
    }

    private static final class DecorScratch {
        static final int MAX_STREAM_POS = 2048;
        static final int BFS_W = 32;
        static final int BFS_H = 48;
        final DecorRng rng = new DecorRng();
        final DecorRng treeRng = new DecorRng();
        final int[] posA = new int[MAX_STREAM_POS * 3];
        final int[] posB = new int[MAX_STREAM_POS * 3];
        final int[] childPosA = new int[64 * 3];
        final int[] childPosB = new int[64 * 3];
        final boolean[] predStack = new boolean[64];
        final int[] foliageNodes = new int[64 * 4]; // [lx, wy, lz, radiusBonus]
        final int[] placedLogs = new int[512 * 3];
        final int[] placedLeaves = new int[4096 * 4]; // [lx, wy, lz, isAlt]
        final int[] bfsQueue = new int[BFS_W * BFS_H * BFS_W];
        final byte[] bfsGrid = new byte[BFS_W * BFS_H * BFS_W];
        final int[] borderHaloBiomes16 = new int[16];
        final boolean[] lakeGrid = new boolean[2048];
        final int[] acceptedTreeTrunks = new int[256 * 3];
        int acceptedTreeTrunkCount;
        int externalRootTopSurfaceSlot = -1;
        int[] surfaceHaloBiomes6x6;
        long voronoiZoomSeed;
        int voronoiChunkX;
        int voronoiChunkZ;
        QuartBiomeOrdinalResolver activeBiomeSampler;

        DecorScratch() {
            Arrays.fill(bfsGrid, (byte) 7);
        }

        boolean tryAcceptChunkTreeTrunk(int localX, int baseWy, int localZ) {
            for (int i = 0; i < acceptedTreeTrunkCount; i++) {
                int o = i * 3;
                if (Math.abs(localX - acceptedTreeTrunks[o]) <= 1
                        && Math.abs(localZ - acceptedTreeTrunks[o + 2]) <= 1
                        && Math.abs(baseWy - acceptedTreeTrunks[o + 1]) <= 4) {
                    return false;
                }
            }
            if (acceptedTreeTrunkCount < 256) {
                int o = acceptedTreeTrunkCount++ * 3;
                acceptedTreeTrunks[o] = localX;
                acceptedTreeTrunks[o + 1] = baseWy;
                acceptedTreeTrunks[o + 2] = localZ;
            }
            return true;
        }

        int[] ensureSurfaceQuartHalo(int quartsY, int[] quartBiomes) {
            int req = quartsY * 36;
            if (surfaceHaloBiomes6x6 == null || surfaceHaloBiomes6x6.length < req) {
                surfaceHaloBiomes6x6 = new int[req];
            }
            Arrays.fill(surfaceHaloBiomes6x6, 0, req, -1);
            if (quartBiomes != null) {
                int limQ = Math.min(quartsY, quartBiomes.length >>> 4);
                for (int qyRel = 0; qyRel < limQ; qyRel++) {
                    int dstLayer = qyRel * 36;
                    int srcLayer = qyRel << 4;
                    for (int qz = 0; qz < 4; qz++) {
                        int dstRow = dstLayer + (qz + 1) * 6 + 1;
                        int srcRow = srcLayer + (qz << 2);
                        for (int qx = 0; qx < 4; qx++) {
                            surfaceHaloBiomes6x6[dstRow + qx] = Math.max(0, quartBiomes[srcRow + qx]);
                        }
                    }
                }
            }
            return surfaceHaloBiomes6x6;
        }
    }

    private static final ThreadLocal<DecorScratch> DECOR_SCRATCH = ThreadLocal.withInitial(DecorScratch::new);
    private static final int[][] PERIMETER_HALO_SAMPLE_OFFSETS = {
        {-2, 2}, {-2, 8}, {-2, 13},
        {17, 2}, {17, 8}, {17, 13},
        {2, -2}, {8, -2}, {13, -2},
        {2, 17}, {8, 17}, {13, 17},
        {-2, -2}, {17, -2}, {-2, 17}, {17, 17}
    };

    private static final int BORDER_TREE_REACH = 15;

    public static int neighborDirectionBitIndex(int dCx, int dCz) {
        int flat = (dCz + 1) * 3 + (dCx + 1);
        return flat < 4 ? flat : (flat - 1);
    }

    private static int computeMaxTreeCanopyReach(LiftedFeatureNode node, int depth) {
        if (node == null || depth > 6) {
            return 0;
        }
        int best = 0;
        if (node.kind() == FN_TREE) {
            int folRad = node.intProvA() != null ? Math.max(1, node.intProvA().maxPossibleValue()) : 2;
            int folKind = node.intE();
            int trunkKind = node.intA();
            int doubleTrunkPad = (folKind == FOLIAGE_DARK_OAK
                            || folKind == FOLIAGE_MEGA_PINE
                            || folKind == FOLIAGE_MEGA_JUNGLE
                            || trunkKind == TRUNK_GIANT_2X2
                            || trunkKind == TRUNK_DARK_OAK
                            || trunkKind == TRUNK_MEGA_JUNGLE)
                    ? 1
                    : 0;
            int branchReach = switch (trunkKind) {
                case TRUNK_FANCY, TRUNK_CHERRY, TRUNK_MEGA_JUNGLE -> 4;
                case TRUNK_FORKING, TRUNK_BENDING, TRUNK_DARK_OAK -> 2;
                default -> 0;
            };
            int vinePad = (node.treeDecorators() != null && node.treeDecorators().leaveVineProbPermille() > 0) ? 1 : 0;
            best = Math.min(BORDER_TREE_REACH, folRad + doubleTrunkPad + branchReach + vinePad);
        }
        best = Math.max(best, computeMaxTreeCanopyReach(node.childFeature(), depth + 1));
        if (node.branches() != null) {
            for (SelectorBranch br : node.branches()) {
                if (br != null) {
                    best = Math.max(best, computeMaxTreeCanopyReach(br.feature(), depth + 1));
                }
            }
        }
        return best;
    }

    private static boolean containsTreeFeature(LiftedFeatureNode node, int depth) {
        if (node == null || depth > 6) return false;
        if (node.kind() == FN_TREE) return true;
        if (containsTreeFeature(node.childFeature(), depth + 1)) return true;
        if (node.branches() != null) {
            for (SelectorBranch br : node.branches()) {
                if (br != null && containsTreeFeature(br.feature(), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void executePlacedFeatureSpecs(
            LcaCapabilityChunkFrame frame,
            int[] quartBiomes,
            long[] presentBiomes,
            int[] topSurfaceY,
            boolean[] leafSlotMask,
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            long worldSeed,
            int minBoundY,
            int maxBoundY,
            DensityFieldEvaluator borderDensityEval,
            QuartBiomeOrdinalResolver borderBiomeSampler,
            RouterCapabilityChannels routerChannels,
            int neighborNativeFeaturesMask8,
            boolean borderTreeOverhangsOnly
    ) {
        LiftedPlacedFeatureSpec[] specs = spec.placedFeatureSpecs();
        DecorScratch scratch = DECOR_SCRATCH.get();
        DecorRng rng = scratch.rng;
        int chunkBx = chunkX << 4;
        int chunkBz = chunkZ << 4;
        scratch.voronoiZoomSeed = obfuscateBiomeZoomSeed(worldSeed);
        scratch.voronoiChunkX = chunkX;
        scratch.voronoiChunkZ = chunkZ;
        scratch.activeBiomeSampler = borderBiomeSampler;
        scratch.surfaceHaloBiomes6x6 = null;

        boolean anyTreeSpecInChunk = false;
        boolean anySnowFreezeSpec = false;
        for (int i = 0; i < specs.length; i++) {
            LiftedPlacedFeatureSpec pf = specs[i];
            if (pf != null) {
                if (pf.hasTree()) {
                    anyTreeSpecInChunk = true;
                }
                if (pf.rootFeature() != null && pf.rootFeature().kind() == FN_SNOW_AND_FREEZE) {
                    anySnowFreezeSpec = true;
                }
            }
        }

        // Sample 16 perimeter + diagonal-corner quart biomes once per chunk (~1 us total) so absent-biome tree/freeze specs skip in O(1)
        long[] treeHaloPresentBiomes = presentBiomes;
        if (anyTreeSpecInChunk || anySnowFreezeSpec) {
            treeHaloPresentBiomes = presentBiomes != null ? presentBiomes.clone() : new long[2];
            for (int p = 0; p < PERIMETER_HALO_SAMPLE_OFFSETS.length; p++) {
                int ox = PERIMETER_HALO_SAMPLE_OFFSETS[p][0];
                int oz = PERIMETER_HALO_SAMPLE_OFFSETS[p][1];
                int nearLx = Math.max(0, Math.min(15, ox));
                int nearLz = Math.max(0, Math.min(15, oz));
                int estWy = (topSurfaceY != null && topSurfaceY.length >= 256)
                        ? Math.max(minBoundY, topSurfaceY[(nearLz << 4) | nearLx] + 1)
                        : spec.seaLevel() + 1;
                int bOrd = borderBiomeSampler != null
                        ? borderBiomeSampler.resolveBiomeOrdinal((chunkBx + ox) >> 2, estWy >> 2, (chunkBz + oz) >> 2)
                        : sampleQuartBiomeAt(quartBiomes, spec, nearLx, estWy, nearLz);
                scratch.borderHaloBiomes16[p] = bOrd;
                int wordIdx = bOrd >>> 6;
                if (bOrd >= 0) {
                    if (wordIdx >= treeHaloPresentBiomes.length) {
                        treeHaloPresentBiomes = Arrays.copyOf(treeHaloPresentBiomes, wordIdx + 1);
                    }
                    treeHaloPresentBiomes[wordIdx] |= (1L << (bOrd & 63));
                }
            }
        }

        for (int i = 0; i < specs.length; i++) {
            LiftedPlacedFeatureSpec pf = specs[i];
            if (pf == null || pf.rootFeature() == null || pf.rootFeature().kind() == FN_NOOP) {
                continue;
            }
            boolean hasTree = pf.hasTree();
            if (borderTreeOverhangsOnly && !hasTree) {
                continue;
            }
            if (!borderTreeOverhangsOnly && pf.rootFeature().kind() == FN_SNOW_AND_FREEZE) {
                if (pf.allowedBiomeMask() == null || bitMasksIntersect(pf.allowedBiomeMask(), treeHaloPresentBiomes)) {
                    stampSnowAndFreezeChunk(
                            frame,
                            quartBiomes,
                            topSurfaceY,
                            leafSlotMask,
                            spec,
                            pf.allowedBiomeMask(),
                            pf.rootFeature(),
                            minBoundY,
                            maxBoundY,
                            scratch);
                }
                continue;
            }
            if (!hasTree && pf.allowedBiomeMask() != null && !bitMasksIntersect(pf.allowedBiomeMask(), presentBiomes)) {
                continue;
            }
            if (hasTree && pf.allowedBiomeMask() != null && !bitMasksIntersect(pf.allowedBiomeMask(), treeHaloPresentBiomes)) {
                continue;
            }
            if (!borderTreeOverhangsOnly
                    && (pf.allowedBiomeMask() == null || bitMasksIntersect(pf.allowedBiomeMask(), presentBiomes))) {
                if (hasTree) {
                    scratch.acceptedTreeTrunkCount = 0;
                    scratch.externalRootTopSurfaceSlot = -1;
                }
                rng.reseed(worldSeed, chunkX, chunkZ, pf.stepIndex(), pf.featureSalt() + i);
                scratch.posA[0] = 0;
                scratch.posA[1] = minBoundY;
                scratch.posA[2] = 0;
                int outCount = evaluatePlacementStream(
                        frame,
                        quartBiomes,
                        topSurfaceY,
                        leafSlotMask,
                        spec,
                        chunkX,
                        chunkZ,
                        minBoundY,
                        maxBoundY,
                        pf.allowedBiomeMask(),
                        pf.placementOps(),
                        pf.intProviders(),
                        pf.predicates(),
                        rng,
                        scratch.posA,
                        1,
                        scratch.posB,
                        scratch.predStack);
                int[] finalBuf = activeOutBuffer(pf.placementOps(), scratch.posA, scratch.posB);
                for (int p = 0; p < outCount; p++) {
                    int base = p * 3;
                    int px = finalBuf[base];
                    int py = finalBuf[base + 1];
                    int pz = finalBuf[base + 2];
                    DecorRng execRng = rng;
                    if (hasTree) {
                        scratch.treeRng.reseedBlock(
                                worldSeed, chunkBx + px, 0, chunkBz + pz, pf.stepIndex(), pf.featureSalt() + i);
                        execRng = scratch.treeRng;
                    }
                    executeFeatureNode(
                            frame,
                            quartBiomes,
                            topSurfaceY,
                            leafSlotMask,
                            spec,
                            chunkBx,
                            chunkBz,
                            minBoundY,
                            maxBoundY,
                            pf.allowedBiomeMask(),
                            pf.rootFeature(),
                            px,
                            py,
                            pz,
                            execRng,
                            scratch,
                            0);
                }
            }

            if (hasTree) {
                executeNeighborBorderTreeSliceForSpec(
                        frame,
                        quartBiomes,
                        treeHaloPresentBiomes,
                        topSurfaceY,
                        leafSlotMask,
                        spec,
                        pf,
                        i,
                        chunkX,
                        chunkZ,
                        worldSeed,
                        minBoundY,
                        maxBoundY,
                        borderDensityEval,
                        borderBiomeSampler,
                        routerChannels,
                        neighborNativeFeaturesMask8,
                        scratch);
            }
        }
    }

    private static int lookupPerimeterHaloBiome(
            int[] quartBiomes, DimensionCapabilitySpec spec, DecorScratch scratch, int relLx, int wy, int relLz) {
        if (relLx < 0 && relLz < 0) {
            return scratch.borderHaloBiomes16[12];
        }
        if (relLx > 15 && relLz < 0) {
            return scratch.borderHaloBiomes16[13];
        }
        if (relLx < 0 && relLz > 15) {
            return scratch.borderHaloBiomes16[14];
        }
        if (relLx > 15 && relLz > 15) {
            return scratch.borderHaloBiomes16[15];
        }
        if (relLx < 0) {
            return scratch.borderHaloBiomes16[relLz < 5 ? 0 : (relLz < 11 ? 1 : 2)];
        }
        if (relLx > 15) {
            return scratch.borderHaloBiomes16[relLz < 5 ? 3 : (relLz < 11 ? 4 : 5)];
        }
        if (relLz < 0) {
            return scratch.borderHaloBiomes16[relLx < 5 ? 6 : (relLx < 11 ? 7 : 8)];
        }
        if (relLz > 15) {
            return scratch.borderHaloBiomes16[relLx < 5 ? 9 : (relLx < 11 ? 10 : 11)];
        }
        return sampleQuartBiomeAt(quartBiomes, spec, relLx, wy, relLz);
    }

    private static void executeNeighborBorderTreeSliceForSpec(
            LcaCapabilityChunkFrame frame,
            int[] quartBiomes,
            long[] treeHaloPresentBiomes,
            int[] topSurfaceY,
            boolean[] leafSlotMask,
            DimensionCapabilitySpec spec,
            LiftedPlacedFeatureSpec pf,
            int specIndex,
            int chunkX,
            int chunkZ,
            long worldSeed,
            int minBoundY,
            int maxBoundY,
            DensityFieldEvaluator borderDensityEval,
            QuartBiomeOrdinalResolver borderBiomeSampler,
            RouterCapabilityChannels routerChannels,
            int neighborNativeFeaturesMask8,
            DecorScratch scratch
    ) {
        if (pf.allowedBiomeMask() != null && !bitMasksIntersect(pf.allowedBiomeMask(), treeHaloPresentBiomes)) {
            return;
        }
        int treeReach = Math.max(2, computeMaxTreeCanopyReach(pf.rootFeature(), 0));
        int trackReach = Math.min(15, treeReach + 1);
        int targetChunkBx = chunkX << 4;
        int targetChunkBz = chunkZ << 4;
        DecorRng rng = scratch.rng;
        int maxWaterDepth = extractMaxSurfaceWaterDepth(pf.placementOps());
        RouterCapabilityChannels rc = routerChannels != null ? routerChannels : RouterCapabilityChannels.EMPTY;

        for (int dCz = -1; dCz <= 1; dCz++) {
            for (int dCx = -1; dCx <= 1; dCx++) {
                if (dCx == 0 && dCz == 0) {
                    continue;
                }
                if ((neighborNativeFeaturesMask8 & (1 << neighborDirectionBitIndex(dCx, dCz))) != 0) {
                    continue;
                }
                int nCx = chunkX + dCx;
                int nCz = chunkZ + dCz;
                scratch.acceptedTreeTrunkCount = 0;
                rng.reseed(worldSeed, nCx, nCz, pf.stepIndex(), pf.featureSalt() + specIndex);
                scratch.posA[0] = 0;
                scratch.posA[1] = minBoundY;
                scratch.posA[2] = 0;
                int candCount = evaluateNeighborTreeCandidatePositions(
                        spec,
                        nCx,
                        nCz,
                        minBoundY,
                        maxBoundY,
                        pf.placementOps(),
                        pf.intProviders(),
                        rng,
                        scratch.posA,
                        1,
                        scratch.posB);
                int[] candBuf = activeOutBuffer(pf.placementOps(), scratch.posA, scratch.posB);
                for (int p = 0; p < candCount; p++) {
                    int b = p * 3;
                    int nLx = candBuf[b];
                    int nLz = candBuf[b + 2];
                    int relLx = (dCx << 4) + nLx;
                    int relLz = (dCz << 4) + nLz;
                    if (relLx < -trackReach
                            || relLx > 15 + trackReach
                            || relLz < -trackReach
                            || relLz > 15 + trackReach) {
                        continue;
                    }
                    int worldX = targetChunkBx + relLx;
                    int worldZ = targetChunkBz + relLz;
                    int nearLx = Math.max(0, Math.min(15, relLx));
                    int nearLz = Math.max(0, Math.min(15, relLz));
                    int estTopY = topSurfaceY != null && topSurfaceY.length >= 256
                            ? topSurfaceY[(nearLz << 4) | nearLx]
                            : spec.seaLevel();
                    int resolvedGroundY = resolveExternalGroundY(
                            spec,
                            borderDensityEval,
                            worldX,
                            worldZ,
                            estTopY,
                            maxWaterDepth,
                            minBoundY,
                            maxBoundY);
                    if (resolvedGroundY == Integer.MIN_VALUE) {
                        continue;
                    }
                    int wy = resolvedGroundY + 1;
                    if (wy <= minBoundY || wy >= maxBoundY) {
                        continue;
                    }
                    if (pf.allowedBiomeMask() != null) {
                        int bOrd = borderBiomeSampler != null
                                ? borderBiomeSampler.resolveBiomeOrdinal(worldX >> 2, wy >> 2, worldZ >> 2)
                                : lookupPerimeterHaloBiome(quartBiomes, spec, scratch, relLx, wy, relLz);
                        if (!bitMaskContains(pf.allowedBiomeMask(), bOrd)) {
                            continue;
                        }
                    }
                    scratch.externalRootTopSurfaceSlot = evaluateExternalTopSurfaceSlot(
                            spec,
                            rc,
                            borderBiomeSampler,
                            quartBiomes,
                            worldSeed,
                            scratch.voronoiZoomSeed,
                            worldX,
                            resolvedGroundY,
                            worldZ,
                            minBoundY,
                            maxBoundY,
                            scratch);
                    if (relLx < -treeReach
                            || relLx > 15 + treeReach
                            || relLz < -treeReach
                            || relLz > 15 + treeReach) {
                        if (isExternalSoilCompatibleWithAnyTreeBranch(
                                pf.rootFeature(), scratch.externalRootTopSurfaceSlot, spec, leafSlotMask, 0)) {
                            scratch.tryAcceptChunkTreeTrunk(nLx, wy, nLz);
                        }
                        continue;
                    }
                    scratch.treeRng.reseedBlock(
                            worldSeed, worldX, 0, worldZ, pf.stepIndex(), pf.featureSalt() + specIndex);
                    executeFeatureNode(
                            frame,
                            quartBiomes,
                            topSurfaceY,
                            leafSlotMask,
                            spec,
                            targetChunkBx,
                            targetChunkBz,
                            minBoundY,
                            maxBoundY,
                            pf.allowedBiomeMask(),
                            pf.rootFeature(),
                            relLx,
                            wy,
                            relLz,
                            scratch.treeRng,
                            scratch,
                            0);
                }
            }
        }
        scratch.externalRootTopSurfaceSlot = -1;
    }

    private static int evaluateNeighborTreeCandidatePositions(
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            int minBoundY,
            int maxBoundY,
            int[] ops,
            LiftedIntProvider[] intProviders,
            DecorRng rng,
            int[] bufA,
            int initCount,
            int[] bufB
    ) {
        if (ops == null || ops.length < 4) {
            return initCount;
        }
        int curCount = initCount;
        int[] src = bufA;
        int[] dst = bufB;
        int chunkBx = chunkX << 4;
        int chunkBz = chunkZ << 4;
        for (int pc = 0; pc + 3 < ops.length && curCount > 0; pc += 4) {
            int op = ops[pc];
            int a = ops[pc + 1];
            int b = ops[pc + 2];
            int c = ops[pc + 3];
            int nextCount = 0;
            switch (op) {
                case PL_RARITY_FILTER -> {
                    for (int i = 0; i < curCount; i++) {
                        if ((a <= 1 || rng.nextInt(a) == 0) && nextCount < DecorScratch.MAX_STREAM_POS) {
                            int base = i * 3, o = nextCount++ * 3;
                            dst[o] = src[base]; dst[o + 1] = src[base + 1]; dst[o + 2] = src[base + 2];
                        }
                    }
                }
                case PL_COUNT -> {
                    LiftedIntProvider ip = (intProviders != null && a >= 0 && a < intProviders.length)
                            ? intProviders[a] : LiftedIntProvider.constant(Math.max(0, a));
                    for (int i = 0; i < curCount && nextCount < DecorScratch.MAX_STREAM_POS; i++) {
                        int base = i * 3;
                        int px = src[base], py = src[base + 1], pz = src[base + 2];
                        int n = Math.min(DecorScratch.MAX_STREAM_POS, Math.max(0, ip.sample(rng)));
                        for (int k = 0; k < n && nextCount < DecorScratch.MAX_STREAM_POS; k++) {
                            int o = nextCount++ * 3;
                            dst[o] = px; dst[o + 1] = py; dst[o + 2] = pz;
                        }
                    }
                }
                case PL_NOISE_COUNT -> {
                    double factor = Math.max(1.0, b * 0.001);
                    double offset = c * 0.001;
                    for (int i = 0; i < curCount && nextCount < DecorScratch.MAX_STREAM_POS; i++) {
                        int base = i * 3;
                        int px = src[base], py = src[base + 1], pz = src[base + 2];
                        double noiseVal = sampleDecoratorSmoothNoise2D(
                                0x517CC1B727220A95L, ((chunkX << 4) + px) / factor, ((chunkZ << 4) + pz) / factor);
                        int reps = (int) Math.ceil((noiseVal + offset) * a);
                        for (int k = 0; k < reps && nextCount < DecorScratch.MAX_STREAM_POS; k++) {
                            int o = nextCount++ * 3;
                            dst[o] = px; dst[o + 1] = py; dst[o + 2] = pz;
                        }
                    }
                }
                case PL_NOISE_THRESHOLD_COUNT -> {
                    double thresh = a * 0.001;
                    for (int i = 0; i < curCount && nextCount < DecorScratch.MAX_STREAM_POS; i++) {
                        int base = i * 3;
                        int px = src[base], py = src[base + 1], pz = src[base + 2];
                        double noiseVal = sampleDecoratorSmoothNoise2D(
                                0x517CC1B727220A95L, ((chunkX << 4) + px) / 200.0, ((chunkZ << 4) + pz) / 200.0);
                        int reps = Math.max(0, noiseVal < thresh ? b : c);
                        for (int k = 0; k < reps && nextCount < DecorScratch.MAX_STREAM_POS; k++) {
                            int o = nextCount++ * 3;
                            dst[o] = px; dst[o + 1] = py; dst[o + 2] = pz;
                        }
                    }
                }
                case PL_IN_SQUARE -> {
                    for (int i = 0; i < curCount && nextCount < DecorScratch.MAX_STREAM_POS; i++) {
                        int base = i * 3;
                        int o = nextCount++ * 3;
                        dst[o] = rng.nextInt(16);
                        dst[o + 1] = src[base + 1];
                        dst[o + 2] = rng.nextInt(16);
                    }
                }
                case PL_RANDOM_OFFSET -> {
                    LiftedIntProvider xIp = (intProviders != null && a >= 0 && a < intProviders.length)
                            ? intProviders[a] : LiftedIntProvider.ZERO;
                    LiftedIntProvider yIp = (intProviders != null && b >= 0 && b < intProviders.length)
                            ? intProviders[b] : LiftedIntProvider.ZERO;
                    for (int i = 0; i < curCount && nextCount < DecorScratch.MAX_STREAM_POS; i++) {
                        int base = i * 3;
                        int nx = src[base] + xIp.sample(rng);
                        int ny = src[base + 1] + yIp.sample(rng);
                        int nz = src[base + 2] + xIp.sample(rng);
                        if (nx >= 0 && nx <= 15 && nz >= 0 && nz <= 15
                                && ny >= minBoundY && ny <= maxBoundY) {
                            int o = nextCount++ * 3;
                            dst[o] = nx; dst[o + 1] = ny; dst[o + 2] = nz;
                        }
                    }
                }
                default -> {
                    // Heightmap, water-depth, block-predicate, and biome ops are evaluated lazily only for border-reaching points
                    for (int i = 0; i < curCount && nextCount < DecorScratch.MAX_STREAM_POS; i++) {
                        int base = i * 3, o = nextCount++ * 3;
                        dst[o] = src[base]; dst[o + 1] = src[base + 1]; dst[o + 2] = src[base + 2];
                    }
                }
            }
            int[] tmp = src;
            src = dst;
            dst = tmp;
            curCount = nextCount;
        }
        return curCount;
    }

    private static int extractMaxSurfaceWaterDepth(int[] ops) {
        if (ops == null) {
            return 0;
        }
        int maxDepth = 0;
        for (int pc = 0; pc + 3 < ops.length; pc += 4) {
            if (ops[pc] == PL_SURFACE_WATER_DEPTH) {
                maxDepth = Math.max(maxDepth, Math.max(0, ops[pc + 1]));
            }
        }
        return maxDepth;
    }

    private static int resolveExternalGroundY(
            DimensionCapabilitySpec spec,
            DensityFieldEvaluator densityEval,
            int worldX,
            int worldZ,
            int estTopY,
            int maxWaterDepth,
            int minBoundY,
            int maxBoundY
    ) {
        int minAllowedGround = spec.defaultFluidSlot() > 0
                ? Math.max(minBoundY, spec.seaLevel() - 1 - Math.max(0, maxWaterDepth))
                : minBoundY;
        if (densityEval == null) {
            return estTopY >= minAllowedGround
                    ? Math.max(minBoundY, Math.min(maxBoundY - 1, estTopY))
                    : Integer.MIN_VALUE;
        }
        int noiseMinY = spec.noiseMinY();
        int cellW = Math.max(1, spec.cellWidth());
        int cellH = Math.max(1, spec.cellHeight());
        int gx0 = Math.floorDiv(worldX, cellW) * cellW;
        int gz0 = Math.floorDiv(worldZ, cellW) * cellW;
        float fx = (worldX - gx0) / (float) cellW;
        float fz = (worldZ - gz0) / (float) cellW;

        int maxCellIdx = Math.max(0, (maxBoundY - 1 - noiseMinY) / cellH);
        int minCellIdx = Math.max(0, (minAllowedGround - noiseMinY) / cellH);
        if (maxCellIdx < minCellIdx) {
            return Integer.MIN_VALUE;
        }
        int topCornerY = noiseMinY + (maxCellIdx + 1) * cellH;
        float topBilinear = sampleHorizontalCellCornersBilinear(densityEval, gx0, gz0, cellW, fx, fz, topCornerY);
        for (int cy = maxCellIdx; cy >= minCellIdx; cy--) {
            int botCornerY = noiseMinY + cy * cellH;
            float botBilinear = sampleHorizontalCellCornersBilinear(densityEval, gx0, gz0, cellW, fx, fz, botCornerY);
            if (topBilinear > 0.0f || botBilinear > 0.0f) {
                int cellHiLy = Math.min(cellH - 1, (maxBoundY - 1) - botCornerY);
                int cellLoLy = Math.max(0, minAllowedGround - botCornerY);
                for (int ly = cellHiLy; ly >= cellLoLy; ly--) {
                    float fy = ly / (float) cellH;
                    float dens = botBilinear + fy * (topBilinear - botBilinear);
                    if (dens > 0.0f) {
                        return botCornerY + ly;
                    }
                }
            }
            topBilinear = botBilinear;
        }
        return Integer.MIN_VALUE;
    }

    private static float sampleHorizontalCellCornersBilinear(
            DensityFieldEvaluator densityEval, int gx0, int gz0, int cellW, float fx, float fz, int cornerY) {
        float c00 = densityEval.sample(gx0, cornerY, gz0);
        if (fx == 0.0f && fz == 0.0f) {
            return c00;
        }
        float c01 = fz == 0.0f ? c00 : densityEval.sample(gx0, cornerY, gz0 + cellW);
        float c10 = fx == 0.0f ? c00 : densityEval.sample(gx0 + cellW, cornerY, gz0);
        float c11 = (fx == 0.0f) ? c01 : ((fz == 0.0f) ? c10 : densityEval.sample(gx0 + cellW, cornerY, gz0 + cellW));
        float x0 = c00 + fz * (c01 - c00);
        float x1 = c10 + fz * (c11 - c10);
        return x0 + fx * (x1 - x0);
    }

    private static boolean isExternalSoilCompatibleWithAnyTreeBranch(
            LiftedFeatureNode node, int topSlot, DimensionCapabilitySpec spec, boolean[] leafSlotMask, int depth) {
        if (node == null || depth > 6) {
            return false;
        }
        if (topSlot < 0) {
            return true;
        }
        if (node.kind() == FN_TREE && isValidSoilSlot(topSlot, spec, leafSlotMask, node.slotMaskA())) {
            return true;
        }
        if (isExternalSoilCompatibleWithAnyTreeBranch(node.childFeature(), topSlot, spec, leafSlotMask, depth + 1)) {
            return true;
        }
        if (node.branches() != null) {
            for (SelectorBranch br : node.branches()) {
                if (br != null
                        && isExternalSoilCompatibleWithAnyTreeBranch(
                                br.feature(), topSlot, spec, leafSlotMask, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int evaluateExternalTopSurfaceSlot(
            DimensionCapabilitySpec spec,
            RouterCapabilityChannels rc,
            QuartBiomeOrdinalResolver borderBiomeSampler,
            int[] fallbackQuartBiomes,
            long worldSeed,
            long biomeZoomSeed,
            int worldX,
            int groundY,
            int worldZ,
            int minBoundY,
            int maxBoundY,
            DecorScratch scratch
    ) {
        int defaultBlock = spec.defaultBlockSlot();
        if (!spec.hasSurfaceRuleModule()) {
            return defaultBlock;
        }
        JavaConceptBackendEmitter.SurfaceRuleEvaluator surfaceEvaluator =
                JavaConceptBackendEmitter.compileSurfaceRuleEvaluator(spec.surfaceRuleModule());
        int extChunkX = worldX >> 4;
        int extChunkZ = worldZ >> 4;
        int quartsY = Math.max(1, spec.chunkSections() << 2);
        int chunkBaseQx = extChunkX << 2;
        int chunkBaseQy = spec.chunkMinY() >> 2;
        int chunkBaseQz = extChunkZ << 2;
        QuartBiomeOrdinalResolver effResolver = borderBiomeSampler != null
                ? borderBiomeSampler
                : (qx, qy, qz) -> sampleQuartBiomeAt(
                        fallbackQuartBiomes,
                        spec,
                        Math.max(0, Math.min(15, (qx << 2) - (scratch.voronoiChunkX << 4))),
                        qy << 2,
                        Math.max(0, Math.min(15, (qz << 2) - (scratch.voronoiChunkZ << 4))));
        int[] extHalo = new int[quartsY * 36];
        Arrays.fill(extHalo, -1);

        int posHash = mixColumnSeed(worldSeed, worldX, worldZ);
        int surfaceDepth = computeSmoothSurfaceDepth(worldSeed, worldX, worldZ, posHash);
        boolean fluidAbove = spec.defaultFluidSlot() > 0 && groundY < spec.seaLevel();
        int waterHeight = fluidAbove ? spec.seaLevel() : Integer.MIN_VALUE;
        int colPrelimY = groundY - 2;
        if (rc.initialDensityWithoutJaggedness() != null) {
            for (int py = Math.min(maxBoundY, groundY + 4); py >= Math.max(minBoundY, groundY - 12); py--) {
                if (rc.initialDensityWithoutJaggedness().sample(worldX, py, worldZ) > 0.390625f) {
                    colPrelimY = py;
                    break;
                }
            }
        }
        boolean tempColConst = rc.temperatureChannel() != null && rc.temperatureChannel().isColumnConstantY();
        float tempVal = rc.temperatureChannel() != null
                ? rc.temperatureChannel().sample(worldX, tempColConst ? 0 : groundY, worldZ)
                : 0.5f;
        int biomeId = resolveVoronoiZoomedBiomeOrdinal(
                biomeZoomSeed,
                worldX,
                groundY,
                worldZ,
                chunkBaseQx,
                chunkBaseQy,
                chunkBaseQz,
                quartsY,
                extHalo,
                effResolver);
        return evaluateSurfaceRuleModule(
                surfaceEvaluator,
                defaultBlock,
                defaultBlock,
                worldX,
                groundY,
                worldZ,
                1,
                7,
                waterHeight,
                surfaceDepth,
                biomeId,
                colPrelimY,
                0,
                0,
                0,
                0.0,
                tempVal);
    }

    private static int[] activeOutBuffer(int[] ops, int[] posA, int[] posB) {
        if (ops == null || ops.length < 4) {
            return posA;
        }
        return ((ops.length >>> 2) & 1) == 0 ? posA : posB;
    }

    private static int evaluatePlacementStream(
            LcaCapabilityChunkFrame frame,
            int[] quartBiomes,
            int[] topSurfaceY,
            boolean[] leafSlotMask,
            DimensionCapabilitySpec spec,
            int chunkX,
            int chunkZ,
            int minBoundY,
            int maxBoundY,
            long[] allowedBiomeMask,
            int[] ops,
            LiftedIntProvider[] intProviders,
            LiftedBlockPredicate[] predicates,
            DecorRng rng,
            int[] bufA,
            int initCount,
            int[] bufB,
            boolean[] predStack
    ) {
        if (ops == null || ops.length < 4 || initCount <= 0) {
            return initCount;
        }
        int maxCap = Math.min(bufA.length, bufB.length) / 3;
        int[] src = bufA;
        int[] dst = bufB;
        int count = initCount;

        for (int ip = 0; ip + 3 < ops.length; ip += 4) {
            if (count <= 0) {
                return 0;
            }
            int op = ops[ip];
            int a = ops[ip + 1];
            int b = ops[ip + 2];
            int c = ops[ip + 3];
            int nextCount = 0;

            switch (op) {
                case PL_RARITY_FILTER -> {
                    for (int i = 0; i < count; i++) {
                        if (a <= 1 || rng.nextInt(a) == 0) {
                            int s = i * 3;
                            int d = nextCount * 3;
                            dst[d] = src[s];
                            dst[d + 1] = src[s + 1];
                            dst[d + 2] = src[s + 2];
                            if (++nextCount >= maxCap) break;
                        }
                    }
                }
                case PL_COUNT -> {
                    LiftedIntProvider prov = (intProviders != null && a >= 0 && a < intProviders.length)
                            ? intProviders[a]
                            : LiftedIntProvider.constant(Math.max(0, a));
                    for (int i = 0; i < count && nextCount < maxCap; i++) {
                        int reps = Math.max(0, prov.sample(rng));
                        int s = i * 3;
                        for (int r = 0; r < reps && nextCount < maxCap; r++) {
                            int d = nextCount * 3;
                            dst[d] = src[s];
                            dst[d + 1] = src[s + 1];
                            dst[d + 2] = src[s + 2];
                            nextCount++;
                        }
                    }
                }
                case PL_NOISE_COUNT -> {
                    double factor = Math.max(1.0, b * 0.001);
                    double offset = c * 0.001;
                    for (int i = 0; i < count && nextCount < maxCap; i++) {
                        int s = i * 3;
                        int lx = src[s], wy = src[s + 1], lz = src[s + 2];
                        double noiseVal = sampleDecoratorSmoothNoise2D(
                                0x517CC1B727220A95L, ((chunkX << 4) + lx) / factor, ((chunkZ << 4) + lz) / factor);
                        int reps = (int) Math.ceil((noiseVal + offset) * a);
                        for (int r = 0; r < reps && nextCount < maxCap; r++) {
                            int d = nextCount * 3;
                            dst[d] = lx;
                            dst[d + 1] = wy;
                            dst[d + 2] = lz;
                            nextCount++;
                        }
                    }
                }
                case PL_NOISE_THRESHOLD_COUNT -> {
                    double thresh = a * 0.001;
                    for (int i = 0; i < count && nextCount < maxCap; i++) {
                        int s = i * 3;
                        int lx = src[s], wy = src[s + 1], lz = src[s + 2];
                        double noiseVal = sampleDecoratorSmoothNoise2D(
                                0x517CC1B727220A95L, ((chunkX << 4) + lx) / 200.0, ((chunkZ << 4) + lz) / 200.0);
                        int reps = Math.max(0, noiseVal < thresh ? b : c);
                        for (int r = 0; r < reps && nextCount < maxCap; r++) {
                            int d = nextCount * 3;
                            dst[d] = lx;
                            dst[d + 1] = wy;
                            dst[d + 2] = lz;
                            nextCount++;
                        }
                    }
                }
                case PL_COUNT_ON_EVERY_LAYER -> {
                    LiftedIntProvider prov = (intProviders != null && a >= 0 && a < intProviders.length)
                            ? intProviders[a]
                            : LiftedIntProvider.ONE;
                    for (int i = 0; i < count && nextCount < maxCap; i++) {
                        int reps = Math.max(0, prov.sample(rng));
                        for (int r = 0; r < reps && nextCount < maxCap; r++) {
                            int lx = rng.nextInt(16);
                            int lz = rng.nextInt(16);
                            int topY = Math.min(maxBoundY, Math.max(spec.seaLevel() + 8, topSurfaceY[(lz << 4) | lx] + 2));
                            boolean prevEmpty = true;
                            for (int wy = topY; wy >= minBoundY && nextCount < maxCap; wy--) {
                                int v = getVoxelAt(frame, spec, lx, wy, lz);
                                boolean empty = v == 0
                                        || (spec.defaultFluidSlot() > 0 && v == spec.defaultFluidSlot())
                                        || isLeafSlot(leafSlotMask, v);
                                if (!empty && prevEmpty && wy + 1 <= maxBoundY) {
                                    int d = nextCount * 3;
                                    dst[d] = lx;
                                    dst[d + 1] = wy + 1;
                                    dst[d + 2] = lz;
                                    nextCount++;
                                }
                                prevEmpty = empty;
                            }
                        }
                    }
                }
                case PL_IN_SQUARE -> {
                    for (int i = 0; i < count && nextCount < maxCap; i++) {
                        int s = i * 3;
                        int d = nextCount * 3;
                        dst[d] = rng.nextInt(16);
                        dst[d + 1] = src[s + 1];
                        dst[d + 2] = rng.nextInt(16);
                        nextCount++;
                    }
                }
                case PL_HEIGHTMAP -> {
                    for (int i = 0; i < count && nextCount < maxCap; i++) {
                        int s = i * 3;
                        int lx = src[s];
                        int lz = src[s + 2];
                        int freeY = sampleColumnHeightmapFreeY(
                                frame, spec, topSurfaceY, leafSlotMask, lx, lz, a, minBoundY, maxBoundY);
                        if (freeY > minBoundY && freeY <= maxBoundY) {
                            int d = nextCount * 3;
                            dst[d] = lx;
                            dst[d + 1] = freeY;
                            dst[d + 2] = lz;
                            nextCount++;
                        }
                    }
                }
                case PL_HEIGHT_RANGE -> {
                    int rawLo = Math.min(b, c);
                    int rawHi = Math.max(b, c);
                    if (rawHi >= minBoundY && rawLo <= maxBoundY) {
                        int span = Math.max(1, rawHi - rawLo + 1);
                        int distKind = a & 15;
                        int plateau = a >>> 4;
                        for (int i = 0; i < count && nextCount < maxCap; i++) {
                            int wy = switch (distKind) {
                                case HD_TRAPEZOID -> {
                                    int range = rawHi - rawLo;
                                    if (plateau >= range) {
                                        yield rawLo + rng.nextInt(range + 1);
                                    }
                                    int ramp = (range - plateau) >>> 1;
                                    int centerSpan = range - (ramp << 1);
                                    yield rawLo
                                            + (centerSpan > 0 ? rng.nextInt(centerSpan + 1) : 0)
                                            + rng.nextInt(ramp + 1)
                                            + rng.nextInt(ramp + 1);
                                }
                                case HD_BIASED_TO_BOTTOM -> rawLo + rng.nextInt(rng.nextInt(span) + 1);
                                case HD_VERY_BIASED_TO_BOTTOM -> rawLo + rng.nextInt(rng.nextInt(rng.nextInt(span) + 1) + 1);
                                default -> rawLo + rng.nextInt(span);
                            };
                            if (wy >= minBoundY && wy <= maxBoundY) {
                                int s = i * 3;
                                int d = nextCount * 3;
                                dst[d] = src[s];
                                dst[d + 1] = wy;
                                dst[d + 2] = src[s + 2];
                                nextCount++;
                            }
                        }
                    }
                }
                case PL_SURFACE_WATER_DEPTH -> {
                    for (int i = 0; i < count && nextCount < maxCap; i++) {
                        int s = i * 3;
                        int lx = src[s];
                        int lz = src[s + 2];
                        int mbY = sampleColumnHeightmapFreeY(
                                frame, spec, topSurfaceY, leafSlotMask, lx, lz, HM_MOTION_BLOCKING, minBoundY, maxBoundY);
                        int ofY = sampleColumnHeightmapFreeY(
                                frame, spec, topSurfaceY, leafSlotMask, lx, lz, HM_OCEAN_FLOOR, minBoundY, maxBoundY);
                        if ((mbY - ofY) <= a) {
                            int d = nextCount * 3;
                            dst[d] = lx;
                            dst[d + 1] = src[s + 1];
                            dst[d + 2] = lz;
                            nextCount++;
                        }
                    }
                }
                case PL_SURFACE_REL_THRESHOLD -> {
                    for (int i = 0; i < count && nextCount < maxCap; i++) {
                        int s = i * 3;
                        int lx = src[s];
                        int wy = src[s + 1];
                        int lz = src[s + 2];
                        int surfY = sampleColumnHeightmapFreeY(
                                frame, spec, topSurfaceY, leafSlotMask, lx, lz, a, minBoundY, maxBoundY);
                        int rel = wy - surfY;
                        if (rel >= b && rel <= c) {
                            int d = nextCount * 3;
                            dst[d] = lx;
                            dst[d + 1] = wy;
                            dst[d + 2] = lz;
                            nextCount++;
                        }
                    }
                }
                case PL_ENV_SCAN -> {
                    int targetIdx = (short) (c & 0xFFFF);
                    int allowedIdx = (short) ((c >>> 16) & 0xFFFF);
                    LiftedBlockPredicate targetPred = (predicates != null && targetIdx >= 0 && targetIdx < predicates.length)
                            ? predicates[targetIdx]
                            : LiftedBlockPredicate.ALWAYS_TRUE;
                    LiftedBlockPredicate allowedPred = (predicates != null && allowedIdx >= 0 && allowedIdx < predicates.length)
                            ? predicates[allowedIdx]
                            : null;
                    int dir = a >= 0 ? 1 : -1;
                    int maxSteps = Math.max(1, b);
                    for (int i = 0; i < count && nextCount < maxCap; i++) {
                        int s = i * 3;
                        int lx = src[s];
                        int curY = src[s + 1];
                        int lz = src[s + 2];
                        for (int step = 0; step <= maxSteps; step++) {
                            if (curY < minBoundY || curY > maxBoundY) {
                                break;
                            }
                            if (evaluateBlockPredicate(frame, spec, leafSlotMask, targetPred, lx, curY, lz, predStack)) {
                                int d = nextCount * 3;
                                dst[d] = lx;
                                dst[d + 1] = curY;
                                dst[d + 2] = lz;
                                nextCount++;
                                break;
                            }
                            if (allowedPred != null
                                    && !evaluateBlockPredicate(frame, spec, leafSlotMask, allowedPred, lx, curY, lz, predStack)) {
                                break;
                            }
                            curY += dir;
                        }
                    }
                }
                case PL_RANDOM_OFFSET -> {
                    LiftedIntProvider xzProv = (intProviders != null && a >= 0 && a < intProviders.length)
                            ? intProviders[a]
                            : LiftedIntProvider.ZERO;
                    LiftedIntProvider yProv = (intProviders != null && b >= 0 && b < intProviders.length)
                            ? intProviders[b]
                            : LiftedIntProvider.ZERO;
                    for (int i = 0; i < count && nextCount < maxCap; i++) {
                        int s = i * 3;
                        int nx = mirrorChunkCoord(src[s] + xzProv.sample(rng));
                        int ny = src[s + 1] + yProv.sample(rng);
                        int nz = mirrorChunkCoord(src[s + 2] + xzProv.sample(rng));
                        if (ny >= minBoundY && ny <= maxBoundY) {
                            int d = nextCount * 3;
                            dst[d] = nx;
                            dst[d + 1] = ny;
                            dst[d + 2] = nz;
                            nextCount++;
                        }
                    }
                }
                case PL_BLOCK_PREDICATE -> {
                    LiftedBlockPredicate pred = (predicates != null && a >= 0 && a < predicates.length)
                            ? predicates[a]
                            : LiftedBlockPredicate.ALWAYS_TRUE;
                    for (int i = 0; i < count && nextCount < maxCap; i++) {
                        int s = i * 3;
                        int lx = src[s];
                        int wy = src[s + 1];
                        int lz = src[s + 2];
                        if (evaluateBlockPredicate(frame, spec, leafSlotMask, pred, lx, wy, lz, predStack)) {
                            int d = nextCount * 3;
                            dst[d] = lx;
                            dst[d + 1] = wy;
                            dst[d + 2] = lz;
                            nextCount++;
                        }
                    }
                }
                case PL_BIOME_FILTER -> {
                    for (int i = 0; i < count && nextCount < maxCap; i++) {
                        int s = i * 3;
                        int lx = src[s];
                        int wy = src[s + 1];
                        int lz = src[s + 2];
                        int ord = sampleQuartBiomeAt(quartBiomes, spec, lx, wy, lz);
                        if (allowedBiomeMask == null || bitMaskContains(allowedBiomeMask, ord)) {
                            int d = nextCount * 3;
                            dst[d] = lx;
                            dst[d + 1] = wy;
                            dst[d + 2] = lz;
                            nextCount++;
                        }
                    }
                }
                default -> {
                    for (int i = 0; i < count && nextCount < maxCap; i++) {
                        int s = i * 3;
                        int d = nextCount * 3;
                        dst[d] = src[s];
                        dst[d + 1] = src[s + 1];
                        dst[d + 2] = src[s + 2];
                        nextCount++;
                    }
                }
            }
            int[] tmp = src;
            src = dst;
            dst = tmp;
            count = nextCount;
        }
        return count;
    }

    private static int sampleColumnHeightmapFreeY(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            int[] topSurfaceY,
            boolean[] leafSlotMask,
            int lx,
            int lz,
            int hmKind,
            int minBoundY,
            int maxBoundY
    ) {
        int col = (lz << 4) | lx;
        int baseSurf = (topSurfaceY != null && col < topSurfaceY.length) ? topSurfaceY[col] : spec.seaLevel();
        if (hmKind == HM_OCEAN_FLOOR || hmKind == HM_WORLD_SURFACE_WG) {
            if (hmKind == HM_WORLD_SURFACE_WG && spec.defaultFluidSlot() > 0 && baseSurf < spec.seaLevel()) {
                int topWaterY = Math.min(maxBoundY, spec.seaLevel());
                if (getVoxelAt(frame, spec, lx, topWaterY - 1, lz) == spec.defaultFluidSlot()) {
                    return Math.max(minBoundY, Math.min(maxBoundY + 1, topWaterY));
                }
            }
            return Math.max(minBoundY, Math.min(maxBoundY + 1, baseSurf + 1));
        }
        int scanStart = Math.min(maxBoundY, Math.max(spec.seaLevel() + 16, baseSurf + 24));
        int defaultFluid = spec.defaultFluidSlot();
        int lavaFluid = spec.lavaFluidSlot();
        long[] nonBlockingMask = spec.nonMotionBlockingSlotMask();
        for (int wy = scanStart; wy >= minBoundY; wy--) {
            int sIdx = (wy - spec.chunkMinY()) >> 4;
            if (sIdx < 0 || sIdx >= frame.sectionCount()) continue;
            int u = frame.uniformSlotId(sIdx);
            if (u == 0) {
                wy = spec.chunkMinY() + (sIdx << 4);
                continue;
            }
            int v = u >= 0 ? u : frame.getVoxel(sIdx, ((wy & 15) << 8) | col);
            if (v <= 0) continue;
            if (hmKind != HM_WORLD_SURFACE && bitMaskContains(nonBlockingMask, v)) {
                continue;
            }
            if (hmKind == HM_MOTION_BLOCKING_NO_LEAVES && isLeafSlot(leafSlotMask, v)) {
                continue;
            }
            if (hmKind == HM_OCEAN_FLOOR
                    && ((defaultFluid > 0 && v == defaultFluid)
                            || (lavaFluid > 0 && v == lavaFluid)
                            || isLeafSlot(leafSlotMask, v))) {
                continue;
            }
            return wy + 1;
        }
        return minBoundY;
    }

    private static boolean evaluateBlockPredicate(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            boolean[] leafSlotMask,
            LiftedBlockPredicate pred,
            int lx,
            int wy,
            int lz,
            boolean[] stack
    ) {
        if (pred == null || pred.ops() == null || pred.ops().length < 3) {
            return true;
        }
        int[] ops = pred.ops();
        long[][] masks = pred.slotMasks();
        int sp = 0;
        int defaultFluid = spec.defaultFluidSlot();
        int lavaFluid = spec.lavaFluidSlot();

        for (int ip = 0; ip + 2 < ops.length; ip += 3) {
            int op = ops[ip];
            int packedOff = ops[ip + 1];
            int arg = ops[ip + 2];
            switch (op) {
                case BP_TRUE -> stack[sp++] = true;
                case BP_FALSE -> stack[sp++] = false;
                case BP_NOT -> {
                    if (sp > 0) stack[sp - 1] = !stack[sp - 1];
                }
                case BP_AND -> {
                    if (sp >= 2) {
                        boolean b = stack[--sp];
                        stack[sp - 1] = stack[sp - 1] && b;
                    }
                }
                case BP_OR -> {
                    if (sp >= 2) {
                        boolean b = stack[--sp];
                        stack[sp - 1] = stack[sp - 1] || b;
                    }
                }
                default -> {
                    int dx = (packedOff & 63) - 16;
                    int dy = ((packedOff >>> 6) & 63) - 16;
                    int dz = ((packedOff >>> 12) & 63) - 16;
                    int tx = lx + dx;
                    int ty = wy + dy;
                    int tz = lz + dz;
                    int vox = (tx < 0 || tx > 15 || tz < 0 || tz > 15) ? -1 : getVoxelAt(frame, spec, tx, ty, tz);
                    boolean res = switch (op) {
                        case BP_IS_AIR -> vox == 0;
                        case BP_IS_REPLACEABLE -> vox == 0
                                || (defaultFluid > 0 && vox == defaultFluid)
                                || (lavaFluid > 0 && vox == lavaFluid)
                                || (vox > 0 && masks != null && arg >= 0 && arg < masks.length && bitMaskContains(masks[arg], vox));
                        case BP_IS_SOLID -> vox > 0
                                && vox != defaultFluid
                                && vox != lavaFluid
                                && !isLeafSlot(leafSlotMask, vox);
                        case BP_MATCH_SLOT_MASK -> vox >= 0
                                && masks != null
                                && arg >= 0
                                && arg < masks.length
                                && bitMaskContains(masks[arg], vox);
                        case BP_MATCH_WATER -> defaultFluid > 0 && vox == defaultFluid;
                        case BP_MATCH_LAVA -> lavaFluid > 0 && vox == lavaFluid;
                        default -> false;
                    };
                    stack[sp++] = res;
                }
            }
        }
        return sp <= 0 || stack[sp - 1];
    }

    private static void executeFeatureNode(
            LcaCapabilityChunkFrame frame,
            int[] quartBiomes,
            int[] topSurfaceY,
            boolean[] leafSlotMask,
            DimensionCapabilitySpec spec,
            int chunkBx,
            int chunkBz,
            int minBoundY,
            int maxBoundY,
            long[] allowedBiomeMask,
            LiftedFeatureNode node,
            int lx,
            int wy,
            int lz,
            DecorRng rng,
            DecorScratch scratch,
            int depth
    ) {
        if (node == null || depth > 8 || wy < minBoundY || wy > maxBoundY) {
            return;
        }
        boolean outsideChunk = lx < 0 || lx > 15 || lz < 0 || lz > 15;
        if (outsideChunk) {
            int k = node.kind();
            if (k != FN_TREE && k != FN_RANDOM_SELECTOR && k != FN_SIMPLE_RANDOM_SELECTOR && k != FN_RANDOM_BOOLEAN) {
                return;
            }
            if (lx < -BORDER_TREE_REACH || lx > 15 + BORDER_TREE_REACH
                    || lz < -BORDER_TREE_REACH || lz > 15 + BORDER_TREE_REACH) {
                return;
            }
        }
        switch (node.kind()) {
            case FN_ORE -> stampOreVeinNode(frame, spec, node, lx, wy, lz, minBoundY, maxBoundY, rng);
            case FN_RANDOM_SELECTOR -> {
                SelectorBranch[] branches = node.branches();
                if (branches != null) {
                    for (SelectorBranch br : branches) {
                        if (br != null && rng.nextInt(10000) < br.chancePermille()) {
                            runChildBranch(
                                    frame,
                                    quartBiomes,
                                    topSurfaceY,
                                    leafSlotMask,
                                    spec,
                                    chunkBx,
                                    chunkBz,
                                    minBoundY,
                                    maxBoundY,
                                    allowedBiomeMask,
                                    br.placementOps(),
                                    br.intProviders(),
                                    br.predicates(),
                                    br.feature(),
                                    lx,
                                    wy,
                                    lz,
                                    rng,
                                    scratch,
                                    depth + 1);
                            return;
                        }
                    }
                }
                if (node.childFeature() != null) {
                    runChildBranch(
                            frame,
                            quartBiomes,
                            topSurfaceY,
                            leafSlotMask,
                            spec,
                            chunkBx,
                            chunkBz,
                            minBoundY,
                            maxBoundY,
                            allowedBiomeMask,
                            node.innerPlacementOps(),
                            node.innerIntProviders(),
                            node.innerPredicates(),
                            node.childFeature(),
                            lx,
                            wy,
                            lz,
                            rng,
                            scratch,
                            depth + 1);
                }
            }
            case FN_SIMPLE_RANDOM_SELECTOR -> {
                SelectorBranch[] branches = node.branches();
                if (branches != null && branches.length > 0) {
                    SelectorBranch br = branches[rng.nextInt(branches.length)];
                    if (br != null) {
                        runChildBranch(
                                frame,
                                quartBiomes,
                                topSurfaceY,
                                leafSlotMask,
                                spec,
                                chunkBx,
                                chunkBz,
                                minBoundY,
                                maxBoundY,
                                allowedBiomeMask,
                                br.placementOps(),
                                br.intProviders(),
                                br.predicates(),
                                br.feature(),
                                lx,
                                wy,
                                lz,
                                rng,
                                scratch,
                                depth + 1);
                    }
                }
            }
            case FN_RANDOM_BOOLEAN -> {
                SelectorBranch[] branches = node.branches();
                if (branches != null && branches.length >= 2) {
                    SelectorBranch br = rng.nextBoolean() ? branches[0] : branches[1];
                    if (br != null) {
                        runChildBranch(
                                frame,
                                quartBiomes,
                                topSurfaceY,
                                leafSlotMask,
                                spec,
                                chunkBx,
                                chunkBz,
                                minBoundY,
                                maxBoundY,
                                allowedBiomeMask,
                                br.placementOps(),
                                br.intProviders(),
                                br.predicates(),
                                br.feature(),
                                lx,
                                wy,
                                lz,
                                rng,
                                scratch,
                                depth + 1);
                    }
                }
            }
            case FN_TREE -> stampTreeNode(frame, spec, leafSlotMask, node, chunkBx, chunkBz, lx, wy, lz, minBoundY, maxBoundY, rng, scratch);
            case FN_FALLEN_TREE -> stampFallenTreeNode(frame, spec, leafSlotMask, node, chunkBx, chunkBz, lx, wy, lz, minBoundY, maxBoundY, rng);
            case FN_RANDOM_PATCH -> {
                int tries = Math.max(1, node.intA());
                int xzSpread = Math.max(0, node.intB());
                int ySpread = Math.max(0, node.intC());
                int xp1 = xzSpread + 1;
                int yp1 = ySpread + 1;
                for (int t = 0; t < tries; t++) {
                    int nx = mirrorChunkCoord(lx + rng.nextInt(xp1) - rng.nextInt(xp1));
                    int ny = wy + rng.nextInt(yp1) - rng.nextInt(yp1);
                    int nz = mirrorChunkCoord(lz + rng.nextInt(xp1) - rng.nextInt(xp1));
                    if (ny < minBoundY || ny > maxBoundY) {
                        continue;
                    }
                    runChildBranch(
                            frame,
                            quartBiomes,
                            topSurfaceY,
                            leafSlotMask,
                            spec,
                            chunkBx,
                            chunkBz,
                            minBoundY,
                            maxBoundY,
                            allowedBiomeMask,
                            node.innerPlacementOps(),
                            node.innerIntProviders(),
                            node.innerPredicates(),
                            node.childFeature(),
                            nx,
                            ny,
                            nz,
                            rng,
                            scratch,
                            depth + 1);
                }
            }
            case FN_SIMPLE_BLOCK -> {
                if (getVoxelAt(frame, spec, lx, wy, lz) == 0 && wy - 1 >= minBoundY) {
                    int ground = getVoxelAt(frame, spec, lx, wy - 1, lz);
                    boolean ok = node.slotMaskA() == null || node.slotMaskA().length == 0
                            ? (ground > 0 && ground != spec.defaultFluidSlot() && ground != spec.lavaFluidSlot() && !isLeafSlot(leafSlotMask, ground))
                            : bitMaskContains(node.slotMaskA(), ground);
                    if (ok && node.stateProvA() != null) {
                        long pair = node.stateProvA().sampleSlotAndUpper(chunkBx + lx, wy, chunkBz + lz, rng);
                        int slot = (int) pair;
                        int upperSlot = (int) (pair >>> 32);
                        if (slot > 0) {
                            if (upperSlot > 0) {
                                if (wy + 1 <= maxBoundY && getVoxelAt(frame, spec, lx, wy + 1, lz) == 0) {
                                    setVoxelIfAirOrLeaf(frame, spec, leafSlotMask, lx, wy, lz, slot, false);
                                    setVoxelIfAirOrLeaf(frame, spec, leafSlotMask, lx, wy + 1, lz, upperSlot, false);
                                }
                            } else {
                                setVoxelIfAirOrLeaf(frame, spec, leafSlotMask, lx, wy, lz, slot, false);
                            }
                        }
                    }
                }
            }
            case FN_BLOCK_COLUMN -> stampBlockColumnNode(frame, spec, leafSlotMask, node, chunkBx, chunkBz, lx, wy, lz, minBoundY, maxBoundY, rng, scratch);
            case FN_DISK -> stampDiskNode(frame, spec, leafSlotMask, node, chunkBx, chunkBz, lx, wy, lz, minBoundY, maxBoundY, rng, scratch);
            case FN_UNDERWATER_MAGMA -> stampUnderwaterMagmaNode(frame, spec, node, lx, wy, lz, minBoundY, maxBoundY, rng);
            case FN_MULTIFACE -> stampMultifaceNode(frame, spec, topSurfaceY, node, lx, wy, lz, minBoundY, maxBoundY, rng);
            case FN_SPELEOTHEM -> stampSpeleothemNode(frame, spec, node, lx, wy, lz, minBoundY, maxBoundY, rng);
            case FN_SPELEOTHEM_CLUSTER -> stampSpeleothemClusterNode(frame, spec, node, lx, wy, lz, minBoundY, maxBoundY, rng);
            case FN_LARGE_DRIPSTONE -> stampLargeDripstoneNode(frame, spec, node, lx, wy, lz, minBoundY, maxBoundY, rng);
            case FN_GEODE -> stampGeodeNode(frame, spec, leafSlotMask, node, lx, wy, lz, minBoundY, maxBoundY, rng);
            case FN_SCULK_PATCH -> stampSculkPatchNode(frame, spec, node, lx, wy, lz, minBoundY, maxBoundY, rng);
            case FN_VEGETATION_PATCH -> stampVegetationPatchNode(
                    frame, quartBiomes, topSurfaceY, leafSlotMask, spec, allowedBiomeMask, node, chunkBx, chunkBz, lx, wy, lz, minBoundY, maxBoundY, rng, scratch, depth);
            case FN_ROOT_SYSTEM -> {
                stampRootSystemNode(frame, spec, leafSlotMask, node, chunkBx, chunkBz, lx, wy, lz, minBoundY, maxBoundY, rng, scratch, depth);
            }
            case FN_HUGE_FUNGUS -> stampHugeFungusNode(frame, spec, leafSlotMask, node, lx, wy, lz, minBoundY, maxBoundY, rng);
            case FN_HUGE_MUSHROOM -> stampHugeMushroomNode(frame, spec, leafSlotMask, node, chunkBx, chunkBz, lx, wy, lz, minBoundY, maxBoundY, rng);
            case FN_CHORUS_PLANT -> stampChorusNode(frame, spec, leafSlotMask, node, lx, wy, lz, minBoundY, maxBoundY, rng);
            case FN_BLOCK_BLOB -> stampBlockBlobNode(frame, spec, node, chunkBx, chunkBz, lx, wy, lz, minBoundY, maxBoundY, rng);
            case FN_REPLACE_BLOBS -> stampReplaceBlobsNode(frame, spec, node, lx, wy, lz, minBoundY, maxBoundY, rng);
            case FN_DELTA -> stampDeltaNode(frame, spec, node, lx, wy, lz, minBoundY, maxBoundY, rng);
            case FN_SPIKE -> stampSpikeNode(frame, spec, leafSlotMask, node, lx, wy, lz, minBoundY, maxBoundY, rng);
            case FN_AQUATIC_PLANT -> stampAquaticPlantNode(frame, spec, topSurfaceY, leafSlotMask, node, lx, wy, lz, minBoundY, maxBoundY, rng);
            case FN_SPRING -> stampSpringNode(frame, spec, node, lx, wy, lz, minBoundY, maxBoundY);
            case FN_LAKE -> stampLakeNode(frame, spec, leafSlotMask, node, lx, wy, lz, minBoundY, maxBoundY, rng, scratch);
            case FN_SNOW_AND_FREEZE -> stampSnowAndFreezeChunk(
                    frame, quartBiomes, topSurfaceY, leafSlotMask, spec, allowedBiomeMask, node, minBoundY, maxBoundY, scratch);
            case FN_SEQUENCE -> {
                SelectorBranch[] branches = node.branches();
                if (branches != null) {
                    for (SelectorBranch br : branches) {
                        if (br != null) {
                            runChildBranch(
                                    frame,
                                    quartBiomes,
                                    topSurfaceY,
                                    leafSlotMask,
                                    spec,
                                    chunkBx,
                                    chunkBz,
                                    minBoundY,
                                    maxBoundY,
                                    allowedBiomeMask,
                                    br.placementOps(),
                                    br.intProviders(),
                                    br.predicates(),
                                    br.feature(),
                                    lx,
                                    wy,
                                    lz,
                                    rng,
                                    scratch,
                                    depth + 1);
                        }
                    }
                }
            }
            default -> {}
        }
    }

    private static void runChildBranch(
            LcaCapabilityChunkFrame frame,
            int[] quartBiomes,
            int[] topSurfaceY,
            boolean[] leafSlotMask,
            DimensionCapabilitySpec spec,
            int chunkBx,
            int chunkBz,
            int minBoundY,
            int maxBoundY,
            long[] allowedBiomeMask,
            int[] placementOps,
            LiftedIntProvider[] intProviders,
            LiftedBlockPredicate[] predicates,
            LiftedFeatureNode childFeature,
            int lx,
            int wy,
            int lz,
            DecorRng rng,
            DecorScratch scratch,
            int nextDepth
    ) {
        if (childFeature == null || childFeature.kind() == FN_NOOP) {
            return;
        }
        if (placementOps == null || placementOps.length < 4 || lx < 0 || lx > 15 || lz < 0 || lz > 15) {
            executeFeatureNode(
                    frame,
                    quartBiomes,
                    topSurfaceY,
                    leafSlotMask,
                    spec,
                    chunkBx,
                    chunkBz,
                    minBoundY,
                    maxBoundY,
                    allowedBiomeMask,
                    childFeature,
                    lx,
                    wy,
                    lz,
                    rng,
                    scratch,
                    nextDepth);
            return;
        }
        scratch.childPosA[0] = lx;
        scratch.childPosA[1] = wy;
        scratch.childPosA[2] = lz;
        int n = evaluatePlacementStream(
                frame,
                quartBiomes,
                topSurfaceY,
                leafSlotMask,
                spec,
                chunkBx >> 4,
                chunkBz >> 4,
                minBoundY,
                maxBoundY,
                allowedBiomeMask,
                placementOps,
                intProviders,
                predicates,
                rng,
                scratch.childPosA,
                1,
                scratch.childPosB,
                scratch.predStack);
        int[] out = activeOutBuffer(placementOps, scratch.childPosA, scratch.childPosB);
        for (int i = 0; i < n; i++) {
            int b = i * 3;
            executeFeatureNode(
                    frame,
                    quartBiomes,
                    topSurfaceY,
                    leafSlotMask,
                    spec,
                    chunkBx,
                    chunkBz,
                    minBoundY,
                    maxBoundY,
                    allowedBiomeMask,
                    childFeature,
                    out[b],
                    out[b + 1],
                    out[b + 2],
                    rng,
                    scratch,
                    nextDepth);
        }
    }

    private static void stampOreVeinNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            LiftedFeatureNode node,
            int ox,
            int oy,
            int oz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng
    ) {
        OreTargetRule[] rules = node.oreTargets();
        if (rules == null || rules.length == 0) {
            return;
        }
        int veinSize = Math.max(1, node.intA());
        int airDiscardPermille = node.intB();
        if (veinSize == 1) {
            tryPlaceOreVoxel(frame, spec, rules, ox, oy, oz, airDiscardPermille, rng);
            return;
        }
        // Mojang-style 3D capsule segment centered on voxel midpoints (+0.5f) so small veins (size 2..4) cover voxel centers
        float angle = rng.nextFloat() * (float) Math.PI;
        float spread = veinSize / 8.0f;
        float cxBase = ox + 0.5f;
        float czBase = oz + 0.5f;
        float x0 = cxBase + (float) Math.sin(angle) * spread;
        float x1 = cxBase - (float) Math.sin(angle) * spread;
        float z0 = czBase + (float) Math.cos(angle) * spread;
        float z1 = czBase - (float) Math.cos(angle) * spread;
        float y0 = oy + rng.nextInt(3) - 1.5f;
        float y1 = oy + rng.nextInt(3) - 1.5f;
        int blobs = Math.max(2, Math.min(veinSize, 16));
        for (int b = 0; b < blobs; b++) {
            float t = b / (float) (blobs - 1);
            float cx = x0 + (x1 - x0) * t;
            float cy = y0 + (y1 - y0) * t;
            float cz = z0 + (z1 - z0) * t;
            float rad = (((float) Math.sin(Math.PI * t) + 1.0f) * (rng.nextFloat() * veinSize / 16.0f) + 1.0f) * 0.5f;
            int minX = Math.max(0, (int) Math.floor(cx - rad));
            int maxX = Math.min(15, (int) Math.floor(cx + rad));
            int minY = Math.max(minBoundY, (int) Math.floor(cy - rad));
            int maxY = Math.min(maxBoundY, (int) Math.floor(cy + rad));
            int minZ = Math.max(0, (int) Math.floor(cz - rad));
            int maxZ = Math.min(15, (int) Math.floor(cz + rad));
            float invRad = rad > 0.01f ? (1.0f / rad) : 1.0f;
            for (int y = minY; y <= maxY; y++) {
                float dy = (y + 0.5f - cy) * invRad;
                if (dy * dy >= 1.0f) continue;
                for (int z = minZ; z <= maxZ; z++) {
                    float dz = (z + 0.5f - cz) * invRad;
                    if (dy * dy + dz * dz >= 1.0f) continue;
                    for (int x = minX; x <= maxX; x++) {
                        float dx = (x + 0.5f - cx) * invRad;
                        if (dx * dx + dy * dy + dz * dz < 1.0f) {
                            tryPlaceOreVoxel(frame, spec, rules, x, y, z, airDiscardPermille, rng);
                        }
                    }
                }
            }
        }
    }

    private static void tryPlaceOreVoxel(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            OreTargetRule[] rules,
            int lx,
            int wy,
            int lz,
            int airDiscardPermille,
            DecorRng rng
    ) {
        int secIdx = (wy - spec.chunkMinY()) >> 4;
        if (secIdx < 0 || secIdx >= frame.sectionCount()) {
            return;
        }
        int idx = ((wy & 15) << 8) | (lz << 4) | lx;
        int cur = frame.getVoxel(secIdx, idx);
        if (cur <= 0) {
            return;
        }
        for (OreTargetRule rule : rules) {
            if (rule != null && bitMaskContains(rule.targetSlotMask(), cur)) {
                if (airDiscardPermille > 0 && isAdjacentToAirInChunk(frame, spec, lx, wy, lz)) {
                    if (airDiscardPermille >= 1000 || rng.nextInt(1000) < airDiscardPermille) {
                        return;
                    }
                }
                frame.setVoxel(secIdx, idx, rule.oreBlockSlot());
                return;
            }
        }
    }

    private static boolean isAdjacentToAirInChunk(
            LcaCapabilityChunkFrame frame, DimensionCapabilitySpec spec, int lx, int wy, int lz) {
        return getVoxelAt(frame, spec, lx, wy + 1, lz) == 0
                || getVoxelAt(frame, spec, lx, wy - 1, lz) == 0
                || (lx > 0 && getVoxelAt(frame, spec, lx - 1, wy, lz) == 0)
                || (lx < 15 && getVoxelAt(frame, spec, lx + 1, wy, lz) == 0)
                || (lz > 0 && getVoxelAt(frame, spec, lx, wy, lz - 1) == 0)
                || (lz < 15 && getVoxelAt(frame, spec, lx, wy, lz + 1) == 0);
    }

    private static void stampTreeNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            boolean[] leafSlotMask,
            LiftedFeatureNode node,
            int chunkBx,
            int chunkBz,
            int rawLx,
            int baseWy,
            int rawLz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng,
            DecorScratch scratch
    ) {
        if (baseWy - 1 < minBoundY || node.stateProvA() == null) {
            return;
        }
        int trunkKind = node.intA();
        int baseHeight = Math.max(1, node.intB());
        int heightRandA = Math.max(0, node.intC());
        int heightRandB = Math.max(0, node.intD());
        int foliageKind = node.intE();

        int ax = rawLx;
        int az = rawLz;
        boolean rootInChunk = ax >= 0 && ax <= 15 && az >= 0 && az <= 15;

        if (rootInChunk) {
            if (!isValidSoilAt(frame, spec, leafSlotMask, node.slotMaskA(), ax, baseWy - 1, az)) {
                boolean found = false;
                for (int dy = -2; dy <= 2; dy++) {
                    int candY = baseWy + dy;
                    if (candY - 1 >= minBoundY
                            && candY <= maxBoundY
                            && getVoxelAt(frame, spec, ax, candY, az) == 0
                            && isValidSoilAt(frame, spec, leafSlotMask, node.slotMaskA(), ax, candY - 1, az)) {
                        baseWy = candY;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return;
                }
            }
            int rootCur = getVoxelAt(frame, spec, ax, baseWy, az);
            if (rootCur != 0 && !isLeafSlot(leafSlotMask, rootCur)) {
                return;
            }
        } else if (scratch.externalRootTopSurfaceSlot >= 0
                && !isValidSoilSlot(scratch.externalRootTopSurfaceSlot, spec, leafSlotMask, node.slotMaskA())) {
            return;
        }

        int treeHeight = baseHeight
                + (heightRandA > 0 ? rng.nextInt(heightRandA + 1) : 0)
                + (heightRandB > 0 ? rng.nextInt(heightRandB + 1) : 0);
        if (baseWy + treeHeight + 1 > maxBoundY) {
            return;
        }
        if (rootInChunk) {
            for (int y = baseWy; y <= baseWy + treeHeight; y++) {
                int cur = getVoxelAt(frame, spec, ax, y, az);
                if (cur != 0 && !isLeafSlot(leafSlotMask, cur)) {
                    return;
                }
            }
        }
        if (!scratch.tryAcceptChunkTreeTrunk(ax & 15, baseWy, az & 15)) {
            return;
        }

        // Convert grass_block under trunk to dirtProvider (belowTrunkProvider) when in chunk
        int dirtSlot = (node.auxSlotsB() != null && node.auxSlotsB().length > 0) ? node.auxSlotsB()[0] : 0;
        if (rootInChunk && dirtSlot > 0) {
            int sIdx = (baseWy - 1 - spec.chunkMinY()) >> 4;
            if (sIdx >= 0 && sIdx < frame.sectionCount()) {
                frame.setVoxel(sIdx, (((baseWy - 1) & 15) << 8) | (az << 4) | ax, dirtSlot);
            }
        }

        int logCount = 0;
        int foliageNodeCount = 0;
        LiftedStateProvider trunkProv = node.stateProvA();

        switch (trunkKind) {
            case TRUNK_FANCY -> {
                // Continuous FancyTrunkPlacer: central spine connects into crown; upper-crown limbs enclosed in FancyFoliagePlacer
                int totalH = Math.max(5, treeHeight + 2);
                int trunkTop = Math.max(2, (int) Math.floor(totalH * 0.618));
                int spineTop = Math.min(totalH - 2, trunkTop + 1);
                for (int dy = 0; dy <= spineTop && baseWy + dy <= maxBoundY; dy++) {
                    logCount = placeTreeLog(frame, spec, leafSlotMask, trunkProv, chunkBx, chunkBz, ax, baseWy + dy, az, 1, rng, scratch, logCount);
                }
                foliageNodeCount = addFoliageAttachment(scratch, foliageNodeCount, ax, baseWy + spineTop, az, 0);
                int branchCount = Math.max(2, Math.min(5, totalH / 3));
                int minBranchEndY = baseWy + Math.max(3, (int) Math.ceil(totalH * 0.45));
                int maxBranchEndY = Math.max(minBranchEndY, baseWy + totalH - 2);
                for (int b = 0; b < branchCount; b++) {
                    int branchEndY = minBranchEndY + rng.nextInt(maxBranchEndY - minBranchEndY + 1);
                    double angle = rng.nextFloat() * Math.PI * 2.0;
                    int dist = 2 + rng.nextInt(2);
                    int endX = ax + (int) Math.round(Math.cos(angle) * dist);
                    int endZ = az + (int) Math.round(Math.sin(angle) * dist);
                    int startY = Math.max(
                            baseWy + Math.max(2, (int) Math.ceil(totalH * 0.3)),
                            Math.min(baseWy + spineTop, branchEndY - 1));
                    int steps = Math.max(Math.abs(endX - ax), Math.max(Math.abs(branchEndY - startY), Math.abs(endZ - az)));
                    for (int s = 1; s <= steps; s++) {
                        int bx = ax + Math.round((endX - ax) * (s / (float) steps));
                        int by = startY + Math.round((branchEndY - startY) * (s / (float) steps));
                        int bz = az + Math.round((endZ - az) * (s / (float) steps));
                        int axis = Math.abs(endX - ax) >= Math.abs(endZ - az) ? 0 : 2;
                        logCount = placeTreeLog(frame, spec, leafSlotMask, trunkProv, chunkBx, chunkBz, bx, by, bz, axis, rng, scratch, logCount);
                    }
                    foliageNodeCount = addFoliageAttachment(scratch, foliageNodeCount, endX, branchEndY, endZ, 0);
                }
            }
            case TRUNK_FORKING, TRUNK_CHERRY -> {
                int splitAt = Math.max(2, treeHeight - rng.nextInt(3) - 2);
                for (int dy = 0; dy < splitAt; dy++) {
                    logCount = placeTreeLog(frame, spec, leafSlotMask, trunkProv, chunkBx, chunkBz, ax, baseWy + dy, az, 1, rng, scratch, logCount);
                }
                int dirA = rng.nextInt(4);
                int dirB = (dirA + 1 + rng.nextInt(3)) & 3;
                for (int branchIdx = 0; branchIdx < 2; branchIdx++) {
                    int dir = branchIdx == 0 ? dirA : dirB;
                    int dx = dir == 0 ? 1 : (dir == 1 ? -1 : 0);
                    int dz = dir == 2 ? 1 : (dir == 3 ? -1 : 0);
                    int cx = ax;
                    int cz = az;
                    int topY = baseWy + splitAt;
                    int len = Math.max(2, treeHeight - splitAt + (branchIdx == 0 ? 0 : -1));
                    for (int s = 0; s < len; s++) {
                        cx += dx;
                        cz += dz;
                        topY = baseWy + splitAt + s;
                        logCount = placeTreeLog(frame, spec, leafSlotMask, trunkProv, chunkBx, chunkBz, cx, topY, cz, 1, rng, scratch, logCount);
                    }
                    foliageNodeCount = addFoliageAttachment(scratch, foliageNodeCount, cx, topY + 1, cz, 0);
                }
            }
            case TRUNK_DARK_OAK -> {
                // Mojang DarkOakTrunkPlacer: 2x2 trunk with upper-third cardinal lean + perimeter side branches
                int bendDir = rng.nextInt(4);
                int bDx = bendDir == 0 ? 1 : (bendDir == 1 ? -1 : 0);
                int bDz = bendDir == 2 ? 1 : (bendDir == 3 ? -1 : 0);
                int bendStart = treeHeight - rng.nextInt(4);
                int bendRemain = 2 - rng.nextInt(3);
                int curX = ax;
                int curZ = az;
                int topY = baseWy + treeHeight - 1;
                for (int dy = 0; dy < treeHeight; dy++) {
                    if (dy >= bendStart && bendRemain > 0) {
                        curX += bDx;
                        curZ += bDz;
                        bendRemain--;
                    }
                    int y = baseWy + dy;
                    for (int tx = 0; tx < 2; tx++) {
                        for (int tz = 0; tz < 2; tz++) {
                            int lx = curX + tx;
                            int lz = curZ + tz;
                            if (dy == 0 && dirtSlot > 0 && lx >= 0 && lx <= 15 && lz >= 0 && lz <= 15) {
                                int sIdx = (baseWy - 1 - spec.chunkMinY()) >> 4;
                                if (sIdx >= 0 && sIdx < frame.sectionCount()) {
                                    frame.setVoxel(sIdx, (((baseWy - 1) & 15) << 8) | (lz << 4) | lx, dirtSlot);
                                }
                            }
                            logCount = placeTreeLog(frame, spec, leafSlotMask, trunkProv, chunkBx, chunkBz, lx, y, lz, 1, rng, scratch, logCount);
                        }
                    }
                }
                foliageNodeCount = addFoliageAttachment(scratch, foliageNodeCount, curX, topY + 1, curZ, 0);
                for (int dxB = -1; dxB <= 2; dxB++) {
                    for (int dzB = -1; dzB <= 2; dzB++) {
                        if ((dxB >= 0 && dxB <= 1 && dzB >= 0 && dzB <= 1) || rng.nextInt(3) > 0) {
                            continue;
                        }
                        int bLen = rng.nextInt(3) + 2;
                        for (int s = 0; s < bLen; s++) {
                            logCount = placeTreeLog(frame, spec, leafSlotMask, trunkProv, chunkBx, chunkBz, ax + dxB, topY - s - 1, az + dzB, 1, rng, scratch, logCount);
                        }
                        foliageNodeCount = addFoliageAttachment(scratch, foliageNodeCount, curX + dxB, topY + 1, curZ + dzB, -1);
                    }
                }
            }
            case TRUNK_MEGA_JUNGLE -> {
                // Mojang MegaJungleTrunkPlacer: 2x2 trunk + helical 5-step side branches with branch-tip foliage caps
                for (int dy = 0; dy < treeHeight; dy++) {
                    for (int tx = 0; tx < 2; tx++) {
                        for (int tz = 0; tz < 2; tz++) {
                            int lx = ax + tx;
                            int lz = az + tz;
                            if (dy == 0 && dirtSlot > 0 && lx >= 0 && lx <= 15 && lz >= 0 && lz <= 15) {
                                int sIdx = (baseWy - 1 - spec.chunkMinY()) >> 4;
                                if (sIdx >= 0 && sIdx < frame.sectionCount()) {
                                    frame.setVoxel(sIdx, (((baseWy - 1) & 15) << 8) | (lz << 4) | lx, dirtSlot);
                                }
                            }
                            logCount = placeTreeLog(frame, spec, leafSlotMask, trunkProv, chunkBx, chunkBz, lx, baseWy + dy, lz, 1, rng, scratch, logCount);
                        }
                    }
                }
                foliageNodeCount = addFoliageAttachment(scratch, foliageNodeCount, ax, baseWy + treeHeight, az, 1);
                for (int bH = treeHeight - 2 - rng.nextInt(4); bH > treeHeight / 2; bH -= 2 + rng.nextInt(4)) {
                    double angle = rng.nextFloat() * Math.PI * 2.0;
                    double cos = Math.cos(angle);
                    double sin = Math.sin(angle);
                    int bx = ax;
                    int bz = az;
                    int axis = Math.abs(cos) >= Math.abs(sin) ? 0 : 2;
                    for (int step = 0; step < 5; step++) {
                        bx = ax + (int) Math.round(1.5 + cos * step);
                        bz = az + (int) Math.round(1.5 + sin * step);
                        int by = baseWy + bH - 3 + (step / 2);
                        logCount = placeTreeLog(frame, spec, leafSlotMask, trunkProv, chunkBx, chunkBz, bx, by, bz, axis, rng, scratch, logCount);
                    }
                    foliageNodeCount = addFoliageAttachment(scratch, foliageNodeCount, bx, baseWy + bH, bz, -2);
                }
            }
            case TRUNK_GIANT_2X2 -> {
                for (int dy = 0; dy < treeHeight; dy++) {
                    for (int tx = 0; tx < 2; tx++) {
                        for (int tz = 0; tz < 2; tz++) {
                            int lx = ax + tx;
                            int lz = az + tz;
                            if (dy == 0 && dirtSlot > 0 && lx >= 0 && lx <= 15 && lz >= 0 && lz <= 15) {
                                int sIdx = (baseWy - 1 - spec.chunkMinY()) >> 4;
                                if (sIdx >= 0 && sIdx < frame.sectionCount()) {
                                    frame.setVoxel(sIdx, (((baseWy - 1) & 15) << 8) | (lz << 4) | lx, dirtSlot);
                                }
                            }
                            logCount = placeTreeLog(frame, spec, leafSlotMask, trunkProv, chunkBx, chunkBz, lx, baseWy + dy, lz, 1, rng, scratch, logCount);
                        }
                    }
                }
                int giantBonus = foliageKind == FOLIAGE_MEGA_PINE ? 0 : 1;
                foliageNodeCount = addFoliageAttachment(scratch, foliageNodeCount, ax, baseWy + treeHeight, az, giantBonus);
            }
            default -> {
                for (int dy = 0; dy < treeHeight; dy++) {
                    logCount = placeTreeLog(frame, spec, leafSlotMask, trunkProv, chunkBx, chunkBz, ax, baseWy + dy, az, 1, rng, scratch, logCount);
                }
                foliageNodeCount = addFoliageAttachment(scratch, foliageNodeCount, ax, baseWy + treeHeight, az, 0);
            }
        }

        if (node.stateProvB() == null || node.auxSlotsA() == null || node.auxSlotsA().length < 6 || logCount == 0) {
            return;
        }

        int[] primaryDistSlots = node.auxSlotsA(); // length 6 (distance=1..6) or 12 (+ alt distance=1..6)
        boolean hasAltLeaves = primaryDistSlots.length >= 12 && primaryDistSlots[6] > 0;
        int altLeafPermille = (node.auxSlotsB() != null && node.auxSlotsB().length > 1) ? node.auxSlotsB()[1] : 0;

        int leafCount = 0;
        boolean isDoubleTrunk = trunkKind == TRUNK_GIANT_2X2
                || trunkKind == TRUNK_MEGA_JUNGLE
                || trunkKind == TRUNK_DARK_OAK;
        int rawRad = node.intProvA() != null ? node.intProvA().sample(rng) : 2;
        int radBase = (foliageKind == FOLIAGE_MEGA_PINE || foliageKind == FOLIAGE_DARK_OAK)
                ? Math.max(0, rawRad)
                : Math.max(1, rawRad);
        int offBase = node.intProvB() != null ? node.intProvB().sample(rng) : 0;
        int folHeight = node.intProvC() != null ? Math.max(1, node.intProvC().sample(rng)) : 3;

        for (int fn = 0; fn < foliageNodeCount; fn++) {
            int fBase = fn * 4;
            int fx = scratch.foliageNodes[fBase];
            int rawFy = scratch.foliageNodes[fBase + 1];
            int fz = scratch.foliageNodes[fBase + 2];
            int rBonus = scratch.foliageNodes[fBase + 3];
            // FancyFoliagePlacer attachments are already positioned at exact limb/crown tips; avoid shifting +4 into mid-air
            int fy = (foliageKind == FOLIAGE_FANCY || foliageKind == FOLIAGE_DARK_OAK || foliageKind == FOLIAGE_MEGA_JUNGLE)
                    ? rawFy
                    : (rawFy + offBase);

            switch (foliageKind) {
                case FOLIAGE_FANCY -> {
                    int h = Math.max(4, folHeight);
                    for (int yo = -h + 2; yo <= 1; yo++) {
                        int r = Math.max(1, radBase + rBonus + (yo == 1 || yo == -h + 2 ? 0 : 1));
                        leafCount = stampFoliageLayer(frame, spec, fx, fy + yo, fz, r, true, false, hasAltLeaves, altLeafPermille, primaryDistSlots[5], rng, scratch, leafCount);
                    }
                }
                case FOLIAGE_DARK_OAK -> {
                    if (fn == 0) {
                        // Primary 2x2 doubleTrunk canopy
                        leafCount = stampFoliageLayer(frame, spec, fx, fy - 2, fz, 2, false, true, hasAltLeaves, altLeafPermille, primaryDistSlots[5], rng, scratch, leafCount);
                        leafCount = stampFoliageLayer(frame, spec, fx, fy - 1, fz, 3, false, true, hasAltLeaves, altLeafPermille, primaryDistSlots[5], rng, scratch, leafCount);
                        leafCount = stampFoliageLayer(frame, spec, fx, fy, fz, 2, false, true, hasAltLeaves, altLeafPermille, primaryDistSlots[5], rng, scratch, leafCount);
                        if (rng.nextBoolean()) {
                            leafCount = stampFoliageLayer(frame, spec, fx, fy + 1, fz, 1, false, true, hasAltLeaves, altLeafPermille, primaryDistSlots[5], rng, scratch, leafCount);
                        }
                    } else {
                        for (int yo = -1; yo <= 0; yo++) {
                            leafCount = stampFoliageLayer(frame, spec, fx, fy + yo, fz, 2 + yo + Math.max(-1, rBonus), false, false, hasAltLeaves, altLeafPermille, primaryDistSlots[5], rng, scratch, leafCount);
                        }
                    }
                }
                case FOLIAGE_MEGA_JUNGLE -> {
                    int h = Math.max(2, folHeight);
                    boolean doubleCrown = (fn == 0 && trunkKind == TRUNK_MEGA_JUNGLE);
                    for (int yo = -h; yo <= 0; yo++) {
                        int r = Math.max(1, radBase + (doubleCrown ? 1 : 0) + (rBonus < 0 ? 0 : rBonus) - (yo == 0 ? 1 : 0) + (yo == -h ? 1 : 0));
                        leafCount = stampFoliageLayer(frame, spec, fx, fy + yo, fz, r, true, doubleCrown, hasAltLeaves, altLeafPermille, primaryDistSlots[5], rng, scratch, leafCount);
                    }
                }
                case FOLIAGE_MEGA_PINE -> {
                    int crownH = Math.max(1, folHeight);
                    int baseR = Math.max(0, radBase + rBonus);
                    int prevR = 0;
                    for (int yo = -crownH; yo <= 0; yo++) {
                        int distFromTop = -yo;
                        int r = baseR + (int) Math.floor((distFromTop / (float) crownH) * 3.5f);
                        int useR = (distFromTop > 0 && r == prevR && ((fy + yo) & 1) == 0) ? (r + 1) : r;
                        leafCount = stampFoliageLayer(
                                frame,
                                spec,
                                fx,
                                fy + yo,
                                fz,
                                useR,
                                true,
                                isDoubleTrunk && fn == 0,
                                hasAltLeaves,
                                altLeafPermille,
                                primaryDistSlots[5],
                                rng,
                                scratch,
                                leafCount);
                        prevR = r;
                    }
                }
                case FOLIAGE_SPRUCE, FOLIAGE_PINE -> {
                    int crownH = foliageKind == FOLIAGE_SPRUCE ? Math.max(4, treeHeight - 1) : Math.max(3, folHeight + 1);
                    int curR = 0;
                    int maxR = Math.max(2, radBase + rBonus);
                    for (int yo = 0; yo >= -crownH; yo--) {
                        leafCount = stampFoliageLayer(frame, spec, fx, fy + yo, fz, curR, false, isDoubleTrunk && fn == 0, hasAltLeaves, altLeafPermille, primaryDistSlots[5], rng, scratch, leafCount);
                        if (curR >= maxR) {
                            curR = 1;
                        } else {
                            curR++;
                        }
                    }
                }
                case FOLIAGE_ACACIA -> {
                    leafCount = stampFoliageLayer(frame, spec, fx, fy, fz, Math.max(1, radBase - 1), false, false, hasAltLeaves, altLeafPermille, primaryDistSlots[5], rng, scratch, leafCount);
                    leafCount = stampFoliageLayer(frame, spec, fx, fy - 1, fz, radBase + 1, false, false, hasAltLeaves, altLeafPermille, primaryDistSlots[5], rng, scratch, leafCount);
                }
                case FOLIAGE_BUSH -> {
                    for (int yo = -1; yo <= 0; yo++) {
                        leafCount = stampFoliageLayer(frame, spec, fx, fy + yo, fz, Math.max(1, radBase + (yo == -1 ? 1 : 0)), false, false, hasAltLeaves, altLeafPermille, primaryDistSlots[5], rng, scratch, leafCount);
                    }
                }
                default -> {
                    // Mojang BlobFoliagePlacer / CherryFoliagePlacer
                    int h = Math.max(2, folHeight);
                    for (int yo = -h + 1; yo <= 0; yo++) {
                        int r = (yo == 0) ? Math.max(1, radBase + rBonus - 1) : (radBase + rBonus);
                        leafCount = stampFoliageLayer(frame, spec, fx, fy + yo, fz, r, false, false, hasAltLeaves, altLeafPermille, primaryDistSlots[5], rng, scratch, leafCount);
                    }
                }
            }
        }

        // Exact 6-step voxel Manhattan BFS in 32x48x32 tree-local space -> distance=1..6 across tall canopies & chunk borders
        boolean hasInChunkLeaves = false;
        for (int li = 0; li < leafCount; li++) {
            if ((scratch.placedLeaves[li * 4 + 3] & 2) != 0) {
                hasInChunkLeaves = true;
                break;
            }
        }
        if (!rootInChunk && !hasInChunkLeaves) {
            return;
        }
        if (hasInChunkLeaves) {
            assignExactLeafDistances(
                    frame, spec, primaryDistSlots, logCount, leafCount, ax - 16, baseWy - 2, az - 16, scratch);
        }

        if (node.treeDecorators() != null) {
            applyTreeDecorators(
                    frame,
                    spec,
                    node.treeDecorators(),
                    ax,
                    baseWy,
                    az,
                    treeHeight,
                    trunkKind == TRUNK_MEGA_JUNGLE || trunkKind == TRUNK_GIANT_2X2 || trunkKind == TRUNK_DARK_OAK,
                    logCount,
                    leafCount,
                    minBoundY,
                    maxBoundY,
                    rng,
                    scratch);
        }
    }

    private static boolean isValidSoilSlot(
            int v, DimensionCapabilitySpec spec, boolean[] leafSlotMask, long[] soilMask) {
        if (v <= 0 || v == spec.defaultFluidSlot() || v == spec.lavaFluidSlot() || isLeafSlot(leafSlotMask, v)) {
            return false;
        }
        return soilMask == null || soilMask.length == 0 || bitMaskContains(soilMask, v);
    }

    private static boolean isValidSoilAt(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            boolean[] leafSlotMask,
            long[] soilMask,
            int lx,
            int wy,
            int lz
    ) {
        return isValidSoilSlot(getVoxelAt(frame, spec, lx, wy, lz), spec, leafSlotMask, soilMask);
    }

    private static int placeTreeLog(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            boolean[] leafSlotMask,
            LiftedStateProvider trunkProv,
            int chunkBx,
            int chunkBz,
            int lx,
            int wy,
            int lz,
            int axis,
            DecorRng rng,
            DecorScratch scratch,
            int logCount
    ) {
        int slot = axis == 1
                ? trunkProv.sampleSlot(chunkBx + lx, wy, chunkBz + lz, rng)
                : trunkProv.axisSlot(axis);
        boolean inChunk = lx >= 0 && lx <= 15 && lz >= 0 && lz <= 15;
        boolean placedOrVirtual = !inChunk
                || setVoxelIfAirOrLeaf(frame, spec, leafSlotMask, lx, wy, lz, slot, true);
        if (placedOrVirtual && logCount * 3 + 2 < scratch.placedLogs.length) {
            int p = logCount * 3;
            scratch.placedLogs[p] = lx;
            scratch.placedLogs[p + 1] = wy;
            scratch.placedLogs[p + 2] = lz;
            logCount++;
        }
        return logCount;
    }

    private static int addFoliageAttachment(DecorScratch scratch, int count, int lx, int wy, int lz, int rBonus) {
        if (count * 4 + 3 < scratch.foliageNodes.length) {
            int p = count * 4;
            scratch.foliageNodes[p] = lx;
            scratch.foliageNodes[p + 1] = wy;
            scratch.foliageNodes[p + 2] = lz;
            scratch.foliageNodes[p + 3] = rBonus;
            count++;
        }
        return count;
    }

    private static int stampFoliageLayer(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            int cx,
            int wy,
            int cz,
            int radius,
            boolean circular,
            boolean doubleTrunk,
            boolean hasAltLeaves,
            int altLeafPermille,
            int tempLeafSlot,
            DecorRng rng,
            DecorScratch scratch,
            int leafCount
    ) {
        int secIdx = (wy - spec.chunkMinY()) >> 4;
        if (secIdx < 0 || secIdx >= frame.sectionCount() || tempLeafSlot <= 0 || radius < 0) {
            return leafCount;
        }
        int minOff = -radius;
        int maxOff = radius + (doubleTrunk ? 1 : 0);
        for (int dz = minOff; dz <= maxOff; dz++) {
            int lz = cz + dz;
            int absZ = doubleTrunk ? Math.max(Math.abs(dz), Math.abs(dz - 1)) : Math.abs(dz);
            for (int dx = minOff; dx <= maxOff; dx++) {
                int lx = cx + dx;
                int absX = doubleTrunk ? Math.max(Math.abs(dx), Math.abs(dx - 1)) : Math.abs(dx);
                if (circular) {
                    float cxOff = doubleTrunk ? (dx - 0.5f) : dx;
                    float czOff = doubleTrunk ? (dz - 0.5f) : dz;
                    float lim = radius + (doubleTrunk ? 0.85f : 0.5f);
                    if ((cxOff * cxOff + czOff * czOff) > lim * lim) continue;
                } else if (radius > 0 && absX == radius + (doubleTrunk ? 1 : 0) && absZ == radius + (doubleTrunk ? 1 : 0)) {
                    if (rng.nextInt(2) == 0) continue;
                }
                boolean alt = hasAltLeaves && altLeafPermille > 0 && rng.nextInt(1000) < altLeafPermille;
                boolean inChunk = lx >= 0 && lx <= 15 && lz >= 0 && lz <= 15;
                if (inChunk) {
                    int idx = ((wy & 15) << 8) | (lz << 4) | lx;
                    if (frame.getVoxel(secIdx, idx) != 0) {
                        continue;
                    }
                    frame.setVoxel(secIdx, idx, tempLeafSlot);
                }
                if (leafCount * 4 + 3 < scratch.placedLeaves.length) {
                    int p = leafCount * 4;
                    scratch.placedLeaves[p] = lx;
                    scratch.placedLeaves[p + 1] = wy;
                    scratch.placedLeaves[p + 2] = lz;
                    scratch.placedLeaves[p + 3] = (alt ? 1 : 0) | (inChunk ? 2 : 0);
                    leafCount++;
                }
            }
        }
        return leafCount;
    }

    private static void assignExactLeafDistances(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            int[] distSlots,
            int logCount,
            int leafCount,
            int gridOrigX,
            int gridBaseY,
            int gridOrigZ,
            DecorScratch scratch
    ) {
        byte[] grid = scratch.bfsGrid;
        int[] queue = scratch.bfsQueue;
        int qHead = 0;
        int qTail = 0;
        int w = DecorScratch.BFS_W; // 32 (shift 5)
        int h = DecorScratch.BFS_H; // 48

        for (int i = 0; i < leafCount; i++) {
            int p = i * 4;
            int rx = scratch.placedLeaves[p] - gridOrigX;
            int ry = scratch.placedLeaves[p + 1] - gridBaseY;
            int rz = scratch.placedLeaves[p + 2] - gridOrigZ;
            if (rx >= 0 && rx < w && ry >= 0 && ry < h && rz >= 0 && rz < w) {
                grid[(((ry << 5) | rz) << 5) | rx] = (byte) -1;
            }
        }
        for (int i = 0; i < logCount; i++) {
            int p = i * 3;
            int rx = scratch.placedLogs[p] - gridOrigX;
            int ry = scratch.placedLogs[p + 1] - gridBaseY;
            int rz = scratch.placedLogs[p + 2] - gridOrigZ;
            if (rx >= 0 && rx < w && ry >= 0 && ry < h && rz >= 0 && rz < w && qTail < queue.length) {
                int cell = (((ry << 5) | rz) << 5) | rx;
                grid[cell] = 0;
                queue[qTail++] = cell;
            }
        }

        while (qHead < qTail) {
            int cell = queue[qHead++];
            int d = grid[cell];
            if (d >= 6) continue;
            int nextD = d + 1;
            int rx = cell & 31;
            int rz = (cell >>> 5) & 31;
            int ry = cell >>> 10;
            if (rx > 0) qTail = relaxLeafBfs(grid, queue, qTail, cell - 1, nextD);
            if (rx < w - 1) qTail = relaxLeafBfs(grid, queue, qTail, cell + 1, nextD);
            if (rz > 0) qTail = relaxLeafBfs(grid, queue, qTail, cell - 32, nextD);
            if (rz < w - 1) qTail = relaxLeafBfs(grid, queue, qTail, cell + 32, nextD);
            if (ry > 0) qTail = relaxLeafBfs(grid, queue, qTail, cell - 1024, nextD);
            if (ry < h - 1) qTail = relaxLeafBfs(grid, queue, qTail, cell + 1024, nextD);
        }

        for (int i = 0; i < leafCount; i++) {
            int p = i * 4;
            int flags = scratch.placedLeaves[p + 3];
            int lx = scratch.placedLeaves[p];
            int wy = scratch.placedLeaves[p + 1];
            int lz = scratch.placedLeaves[p + 2];
            int rx = lx - gridOrigX;
            int ry = wy - gridBaseY;
            int rz = lz - gridOrigZ;
            boolean inGrid = rx >= 0 && rx < w && ry >= 0 && ry < h && rz >= 0 && rz < w;
            int dist = inGrid ? grid[(((ry << 5) | rz) << 5) | rx] : 7;
            if (inGrid) {
                grid[(((ry << 5) | rz) << 5) | rx] = (byte) 7;
            }
            if ((flags & 2) == 0) continue; // virtual neighbor-chunk leaf
            boolean alt = (flags & 1) != 0;
            int secIdx = (wy - spec.chunkMinY()) >> 4;
            if (secIdx < 0 || secIdx >= frame.sectionCount()) continue;
            int idx = ((wy & 15) << 8) | (lz << 4) | lx;
            if (dist >= 1 && dist <= 6) {
                int slot = distSlots[(alt ? 6 : 0) + (dist - 1)];
                frame.setVoxel(secIdx, idx, slot > 0 ? slot : distSlots[dist - 1]);
            } else {
                // Prune any disconnected leaf >6 blocks from trunk so distance=7 never enters chunk sections
                frame.setVoxel(secIdx, idx, 0);
                scratch.placedLeaves[p + 3] = 0;
            }
        }
        for (int i = 0; i < logCount; i++) {
            int p = i * 3;
            int rx = scratch.placedLogs[p] - gridOrigX;
            int ry = scratch.placedLogs[p + 1] - gridBaseY;
            int rz = scratch.placedLogs[p + 2] - gridOrigZ;
            if (rx >= 0 && rx < w && ry >= 0 && ry < h && rz >= 0 && rz < w) {
                grid[(((ry << 5) | rz) << 5) | rx] = (byte) 7;
            }
        }
    }

    // Cardinal offsets: 0 = north (dz=-1, attach south=1), 1 = south (dz=+1, attach north=0),
    // 2 = west (dx=-1, attach east=3), 3 = east (dx=+1, attach west=2)
    private static final int[] CARDINAL_DX = {0, 0, -1, 1};
    private static final int[] CARDINAL_DZ = {-1, 1, 0, 0};
    private static final int[] OPPOSITE_CARDINAL = {1, 0, 3, 2};

    private static void applyTreeDecorators(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            TreeDecoratorSpec dec,
            int rootLx,
            int baseWy,
            int rootLz,
            int treeHeight,
            boolean doubleTrunk,
            int logCount,
            int leafCount,
            int minBoundY,
            int maxBoundY,
            DecorRng rng,
            DecorScratch scratch
    ) {
        int[] vineSlots = dec.vineFaceSlots(); // [north, south, west, east]
        if (dec.trunkVine() && vineSlots != null && vineSlots.length >= 4) {
            for (int i = 0; i < logCount; i++) {
                int p = i * 3;
                int lx = scratch.placedLogs[p];
                int wy = scratch.placedLogs[p + 1];
                int lz = scratch.placedLogs[p + 2];
                for (int dir = 0; dir < 4; dir++) {
                    if (rng.nextInt(3) > 0) {
                        int vx = lx + CARDINAL_DX[dir];
                        int vz = lz + CARDINAL_DZ[dir];
                        int slot = vineSlots[OPPOSITE_CARDINAL[dir]];
                        if (slot > 0 && vx >= 0 && vx <= 15 && vz >= 0 && vz <= 15 && getVoxelAt(frame, spec, vx, wy, vz) == 0) {
                            int sIdx = (wy - spec.chunkMinY()) >> 4;
                            if (sIdx >= 0 && sIdx < frame.sectionCount()) {
                                frame.setVoxel(sIdx, ((wy & 15) << 8) | (vz << 4) | vx, slot);
                            }
                        }
                    }
                }
            }
        }

        if (dec.leaveVineProbPermille() > 0 && vineSlots != null && vineSlots.length >= 4) {
            int prob = dec.leaveVineProbPermille();
            for (int i = 0; i < leafCount; i++) {
                int p = i * 4;
                if ((scratch.placedLeaves[p + 3] & 2) == 0) {
                    // Advance RNG identically for virtual leaves so chunk borders stay synced
                    for (int dir = 0; dir < 4; dir++) {
                        rng.nextInt(1000);
                    }
                    continue;
                }
                int lx = scratch.placedLeaves[p];
                int wy = scratch.placedLeaves[p + 1];
                int lz = scratch.placedLeaves[p + 2];
                for (int dir = 0; dir < 4; dir++) {
                    if (rng.nextInt(1000) < prob) {
                        int vx = lx + CARDINAL_DX[dir];
                        int vz = lz + CARDINAL_DZ[dir];
                        int slot = vineSlots[OPPOSITE_CARDINAL[dir]];
                        if (slot > 0 && vx >= 0 && vx <= 15 && vz >= 0 && vz <= 15) {
                            for (int hang = 0; hang < 4; hang++) {
                                int hy = wy - hang;
                                if (hy <= minBoundY || getVoxelAt(frame, spec, vx, hy, vz) != 0) {
                                    break;
                                }
                                int sIdx = (hy - spec.chunkMinY()) >> 4;
                                if (sIdx >= 0 && sIdx < frame.sectionCount()) {
                                    frame.setVoxel(sIdx, ((hy & 15) << 8) | (vz << 4) | vx, slot);
                                }
                            }
                        }
                    }
                }
            }
        }

        int[] cocoaSlots = dec.cocoaAgeFacingSlots(); // [age * 4 + facingTowardLog (0=north,1=south,2=west,3=east)]
        if (dec.cocoaProbPermille() > 0 && cocoaSlots != null && cocoaSlots.length >= 12 && treeHeight >= 5) {
            if (rng.nextInt(1000) < dec.cocoaProbPermille()) {
                int maxCocoaY = Math.min(maxBoundY, baseWy + Math.min(5, treeHeight - 2));
                int span = doubleTrunk ? 2 : 1;
                for (int y = baseWy + 2; y <= maxCocoaY; y++) {
                    for (int tx = 0; tx < span; tx++) {
                        for (int tz = 0; tz < span; tz++) {
                            int lx = rootLx + tx;
                            int lz = rootLz + tz;
                            for (int dir = 0; dir < 4; dir++) {
                                if (rng.nextInt(4) == 0) {
                                    int age = rng.nextInt(3);
                                    int cx = lx + CARDINAL_DX[dir];
                                    int cz = lz + CARDINAL_DZ[dir];
                                    int slot = cocoaSlots[age * 4 + OPPOSITE_CARDINAL[dir]];
                                    if (slot > 0 && cx >= 0 && cx <= 15 && cz >= 0 && cz <= 15
                                            && getVoxelAt(frame, spec, cx, y, cz) == 0) {
                                        int sIdx = (y - spec.chunkMinY()) >> 4;
                                        if (sIdx >= 0 && sIdx < frame.sectionCount()) {
                                            frame.setVoxel(sIdx, ((y & 15) << 8) | (cz << 4) | cx, slot);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static int relaxLeafBfs(byte[] grid, int[] queue, int qTail, int nbCell, int nextD) {
        if (grid[nbCell] == -1 && qTail < queue.length) {
            grid[nbCell] = (byte) nextD;
            queue[qTail++] = nbCell;
        }
        return qTail;
    }

    private static void stampFallenTreeNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            boolean[] leafSlotMask,
            LiftedFeatureNode node,
            int chunkBx,
            int chunkBz,
            int lx,
            int wy,
            int lz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng
    ) {
        if (wy - 1 < minBoundY || node.stateProvA() == null) {
            return;
        }
        if (!isValidSoilAt(frame, spec, leafSlotMask, node.slotMaskA(), lx, wy - 1, lz)
                || getVoxelAt(frame, spec, lx, wy, lz) != 0) {
            return;
        }
        LiftedStateProvider trunkProv = node.stateProvA();
        int len = node.intProvA() != null ? Math.max(3, Math.min(8, node.intProvA().sample(rng))) : 5;
        boolean alongX = rng.nextBoolean();
        int sign = rng.nextBoolean() ? 1 : -1;
        int dx = alongX ? sign : 0;
        int dz = alongX ? 0 : sign;
        int axis = alongX ? 0 : 2;
        // Verify horizontal run stays in-chunk on supported terrain before placing
        int endX = lx + dx * (len + 1);
        int endZ = lz + dz * (len + 1);
        if (endX < 0 || endX > 15 || endZ < 0 || endZ > 15) {
            dx = -dx;
            dz = -dz;
            endX = lx + dx * (len + 1);
            endZ = lz + dz * (len + 1);
            if (endX < 0 || endX > 15 || endZ < 0 || endZ > 15) {
                return;
            }
        }
        for (int s = 2; s < 2 + len; s++) {
            int tx = lx + dx * s;
            int tz = lz + dz * s;
            if (getVoxelAt(frame, spec, tx, wy, tz) != 0
                    || !isValidSoilAt(frame, spec, leafSlotMask, null, tx, wy - 1, tz)) {
                return;
            }
        }
        // Upright stump (axis=Y) + 1-block gap + horizontal log (axis=X or axis=Z)
        setVoxelIfAirOrLeaf(
                frame, spec, leafSlotMask, lx, wy, lz, trunkProv.sampleSlot(chunkBx + lx, wy, chunkBz + lz, rng), false);
        int horizSlot = trunkProv.axisSlot(axis);
        for (int s = 2; s < 2 + len; s++) {
            setVoxelIfAirOrLeaf(frame, spec, leafSlotMask, lx + dx * s, wy, lz + dz * s, horizSlot, false);
        }
    }

    private static void stampBlockColumnNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            boolean[] leafSlotMask,
            LiftedFeatureNode node,
            int chunkBx,
            int chunkBz,
            int lx,
            int wy,
            int lz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng,
            DecorScratch scratch
    ) {
        ColumnLayer[] layers = node.columnLayers();
        if (layers == null || layers.length == 0) {
            return;
        }
        int dir = node.intA() >= 0 ? 1 : -1;
        LiftedBlockPredicate allowed = (node.innerPredicates() != null && node.innerPredicates().length > 0)
                ? node.innerPredicates()[0]
                : null;
        int curY = wy;
        for (ColumnLayer layer : layers) {
            if (layer == null || layer.state() == null) continue;
            int h = layer.height() != null ? Math.max(0, layer.height().sample(rng)) : 1;
            for (int s = 0; s < h; s++) {
                if (curY < minBoundY || curY > maxBoundY) return;
                if (allowed != null
                        && !evaluateBlockPredicate(frame, spec, leafSlotMask, allowed, lx, curY, lz, scratch.predStack)) {
                    return;
                }
                int slot = layer.state().sampleSlot(chunkBx + lx, curY, chunkBz + lz, rng);
                if (slot > 0) {
                    int secIdx = (curY - spec.chunkMinY()) >> 4;
                    if (secIdx >= 0 && secIdx < frame.sectionCount()) {
                        frame.setVoxel(secIdx, ((curY & 15) << 8) | (lz << 4) | lx, slot);
                    }
                }
                curY += dir;
            }
        }
    }

    private static void stampDiskNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            boolean[] leafSlotMask,
            LiftedFeatureNode node,
            int chunkBx,
            int chunkBz,
            int lx,
            int wy,
            int lz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng,
            DecorScratch scratch
    ) {
        if (node.stateProvA() == null) return;
        int r = node.intProvA() != null ? Math.max(1, node.intProvA().sample(rng)) : 2;
        int halfH = Math.max(1, node.intA());
        LiftedBlockPredicate target = (node.innerPredicates() != null && node.innerPredicates().length > 0)
                ? node.innerPredicates()[0]
                : null;
        int loY = Math.max(minBoundY, wy - halfH);
        int hiY = Math.min(maxBoundY, wy + halfH);
        int rSq = r * r;
        for (int dz = -r; dz <= r; dz++) {
            int tz = lz + dz;
            if (tz < 0 || tz > 15) continue;
            for (int dx = -r; dx <= r; dx++) {
                int tx = lx + dx;
                if (tx < 0 || tx > 15 || dx * dx + dz * dz > rSq) continue;
                for (int y = hiY; y >= loY; y--) {
                    boolean match = target != null
                            ? evaluateBlockPredicate(frame, spec, leafSlotMask, target, tx, y, tz, scratch.predStack)
                            : (node.slotMaskA() != null && bitMaskContains(node.slotMaskA(), getVoxelAt(frame, spec, tx, y, tz)));
                    if (match) {
                        int slot = node.stateProvA().sampleSlot(chunkBx + tx, y, chunkBz + tz, rng);
                        int secIdx = (y - spec.chunkMinY()) >> 4;
                        if (slot > 0 && secIdx >= 0 && secIdx < frame.sectionCount()) {
                            frame.setVoxel(secIdx, ((y & 15) << 8) | (tz << 4) | tx, slot);
                        }
                    }
                }
            }
        }
    }

    private static void stampUnderwaterMagmaNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            LiftedFeatureNode node,
            int lx,
            int wy,
            int lz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng
    ) {
        int magmaSlot = node.intA();
        int waterSlot = spec.defaultFluidSlot();
        if (magmaSlot <= 0 || waterSlot <= 0) {
            return;
        }
        int searchRange = Math.max(1, node.intB());
        int radius = Math.max(0, node.intC());
        int probPermille = Math.max(1, node.intD());
        // Mojang UnderwaterMagmaFeature#getFloorY: Column.scan(..., insideColumn = isWater) requires origin itself to start in water
        if (getVoxelAt(frame, spec, lx, wy, lz) != waterSlot) {
            return;
        }
        int floorY = Integer.MIN_VALUE;
        for (int step = 1; step <= searchRange && wy - step >= minBoundY; step++) {
            int v = getVoxelAt(frame, spec, lx, wy - step, lz);
            if (v == waterSlot) continue;
            if (v > 0 && v != spec.lavaFluidSlot()) {
                floorY = wy - step;
            }
            break;
        }
        if (floorY == Integer.MIN_VALUE) {
            return;
        }
        for (int dz = -radius; dz <= radius; dz++) {
            int tz = lz + dz;
            if (tz < 0 || tz > 15) continue;
            for (int dx = -radius; dx <= radius; dx++) {
                int tx = lx + dx;
                if (tx < 0 || tx > 15 || rng.nextInt(1000) >= probPermille) continue;
                int cur = getVoxelAt(frame, spec, tx, floorY, tz);
                int above = getVoxelAt(frame, spec, tx, floorY + 1, tz);
                int below = floorY - 1 >= minBoundY ? getVoxelAt(frame, spec, tx, floorY - 1, tz) : cur;
                if (cur > 0
                        && cur != waterSlot
                        && cur != spec.lavaFluidSlot()
                        && above == waterSlot
                        && below > 0
                        && below != waterSlot) {
                    boolean sideExposed = (tx > 0 && isWaterOrAirSlot(getVoxelAt(frame, spec, tx - 1, floorY, tz), waterSlot))
                            || (tx < 15 && isWaterOrAirSlot(getVoxelAt(frame, spec, tx + 1, floorY, tz), waterSlot))
                            || (tz > 0 && isWaterOrAirSlot(getVoxelAt(frame, spec, tx, floorY, tz - 1), waterSlot))
                            || (tz < 15 && isWaterOrAirSlot(getVoxelAt(frame, spec, tx, floorY, tz + 1), waterSlot));
                    if (sideExposed) {
                        continue;
                    }
                    int secIdx = (floorY - spec.chunkMinY()) >> 4;
                    if (secIdx >= 0 && secIdx < frame.sectionCount()) {
                        frame.setVoxel(secIdx, ((floorY & 15) << 8) | (tz << 4) | tx, magmaSlot);
                    }
                    // Mark water voxel at floorY + 1 so LiquidBlock#updateShape -> tryScheduleBubbleBlockColumn runs on FULL promotion
                    int aboveY = floorY + 1;
                    int aboveSecIdx = (aboveY - spec.chunkMinY()) >> 4;
                    if (aboveSecIdx >= 0 && aboveSecIdx < frame.sectionCount()) {
                        frame.recordPostProcessVoxel(aboveSecIdx, ((aboveY & 15) << 8) | (tz << 4) | tx);
                    }
                }
            }
        }
    }

    private static boolean isWaterOrAirSlot(int slot, int waterSlot) {
        return slot <= 0 || slot == waterSlot;
    }

    private static void stampMultifaceNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            int[] topSurfaceY,
            LiftedFeatureNode node,
            int lx,
            int wy,
            int lz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng
    ) {
        int[] faceSlots = node.auxSlotsA(); // [down, up, north, south, west, east]
        if (faceSlots == null || faceSlots.length < 6) {
            return;
        }
        long[] hostMask = node.slotMaskA();
        int searchRange = Math.max(0, Math.min(20, node.intA()));
        int surfCapY = maxBoundY;
        if (topSurfaceY != null) {
            int surf = topSurfaceY[(lz << 4) | lx];
            if (wy <= surf - 4) {
                surfCapY = Math.min(maxBoundY, surf - 4);
            }
        }
        int targetWy = Integer.MIN_VALUE;
        if (wy >= minBoundY
                && wy <= surfCapY
                && getVoxelAt(frame, spec, lx, wy, lz) == 0
                && hasAnyMultifaceAttachment(frame, spec, node, hostMask, faceSlots, lx, wy, lz, minBoundY, maxBoundY)) {
            targetWy = wy;
        } else if (searchRange > 0) {
            int dirFirst = rng.nextBoolean() ? 1 : -1;
            for (int pass = 0; pass < 2 && targetWy == Integer.MIN_VALUE; pass++) {
                int dir = pass == 0 ? dirFirst : -dirFirst;
                for (int step = 1; step <= searchRange; step++) {
                    int cy = wy + dir * step;
                    if (cy < minBoundY || cy > surfCapY) {
                        break;
                    }
                    if (getVoxelAt(frame, spec, lx, cy, lz) == 0
                            && hasAnyMultifaceAttachment(
                                    frame, spec, node, hostMask, faceSlots, lx, cy, lz, minBoundY, maxBoundY)) {
                        targetWy = cy;
                        break;
                    }
                }
            }
        }
        if (targetWy == Integer.MIN_VALUE) {
            return;
        }
        wy = targetWy;
        // Check 6 neighbors: 0=down(y-1), 1=up(y+1), 2=north(z-1), 3=south(z+1), 4=west(x-1), 5=east(x+1)
        int validCount = 0;
        int chosenSlot = 0;
        if (node.flagA() && wy - 1 >= minBoundY && isMultifaceHost(frame, spec, hostMask, lx, wy - 1, lz) && faceSlots[0] > 0) {
            validCount++;
            chosenSlot = faceSlots[0];
        }
        if (node.flagB() && wy + 1 <= maxBoundY && isMultifaceHost(frame, spec, hostMask, lx, wy + 1, lz) && faceSlots[1] > 0) {
            if (++validCount == 1 || rng.nextInt(validCount) == 0) chosenSlot = faceSlots[1];
        }
        if (node.flagC()) {
            if (lz > 0 && isMultifaceHost(frame, spec, hostMask, lx, wy, lz - 1) && faceSlots[2] > 0) {
                if (++validCount == 1 || rng.nextInt(validCount) == 0) chosenSlot = faceSlots[2];
            }
            if (lz < 15 && isMultifaceHost(frame, spec, hostMask, lx, wy, lz + 1) && faceSlots[3] > 0) {
                if (++validCount == 1 || rng.nextInt(validCount) == 0) chosenSlot = faceSlots[3];
            }
            if (lx > 0 && isMultifaceHost(frame, spec, hostMask, lx - 1, wy, lz) && faceSlots[4] > 0) {
                if (++validCount == 1 || rng.nextInt(validCount) == 0) chosenSlot = faceSlots[4];
            }
            if (lx < 15 && isMultifaceHost(frame, spec, hostMask, lx + 1, wy, lz) && faceSlots[5] > 0) {
                if (++validCount == 1 || rng.nextInt(validCount) == 0) chosenSlot = faceSlots[5];
            }
        }
        if (chosenSlot > 0) {
            int secIdx = (wy - spec.chunkMinY()) >> 4;
            if (secIdx >= 0 && secIdx < frame.sectionCount()) {
                frame.setVoxel(secIdx, ((wy & 15) << 8) | (lz << 4) | lx, chosenSlot);
            }
        }
    }

    private static boolean hasAnyMultifaceAttachment(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            LiftedFeatureNode node,
            long[] hostMask,
            int[] faceSlots,
            int lx,
            int wy,
            int lz,
            int minBoundY,
            int maxBoundY
    ) {
        if (node.flagA() && wy - 1 >= minBoundY && faceSlots[0] > 0 && isMultifaceHost(frame, spec, hostMask, lx, wy - 1, lz)) {
            return true;
        }
        if (node.flagB() && wy + 1 <= maxBoundY && faceSlots[1] > 0 && isMultifaceHost(frame, spec, hostMask, lx, wy + 1, lz)) {
            return true;
        }
        if (node.flagC()) {
            return (lz > 0 && faceSlots[2] > 0 && isMultifaceHost(frame, spec, hostMask, lx, wy, lz - 1))
                    || (lz < 15 && faceSlots[3] > 0 && isMultifaceHost(frame, spec, hostMask, lx, wy, lz + 1))
                    || (lx > 0 && faceSlots[4] > 0 && isMultifaceHost(frame, spec, hostMask, lx - 1, wy, lz))
                    || (lx < 15 && faceSlots[5] > 0 && isMultifaceHost(frame, spec, hostMask, lx + 1, wy, lz));
        }
        return false;
    }

    private static boolean isMultifaceHost(
            LcaCapabilityChunkFrame frame, DimensionCapabilitySpec spec, long[] hostMask, int lx, int wy, int lz) {
        int v = getVoxelAt(frame, spec, lx, wy, lz);
        if (v <= 0 || v == spec.defaultFluidSlot() || v == spec.lavaFluidSlot()) {
            return false;
        }
        return hostMask == null || hostMask.length == 0 || bitMaskContains(hostMask, v);
    }

    private static void stampSpeleothemNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            LiftedFeatureNode node,
            int lx,
            int wy,
            int lz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng
    ) {
        // Single pointed_dripstone stalactite/stalagmite (1..2 blocks) attached to ceiling or floor; never stamps multi-block dripstone_block pillars!
        int waterSlot = spec.defaultFluidSlot();
        int curVoxel = getVoxelAt(frame, spec, lx, wy, lz);
        if (curVoxel != 0 && (waterSlot <= 0 || curVoxel != waterSlot)) {
            return;
        }
        int[] upSlots = node.auxSlotsA();   // [tip, frustum, middle, base] pointing UP (stalagmite on floor)
        int[] downSlots = node.auxSlotsB(); // [tip, frustum, middle, base] pointing DOWN (stalactite on ceiling)
        boolean ceiling = wy + 1 <= maxBoundY && isMultifaceHost(frame, spec, node.slotMaskA(), lx, wy + 1, lz);
        boolean floor = wy - 1 >= minBoundY && isMultifaceHost(frame, spec, node.slotMaskA(), lx, wy - 1, lz);
        if (!ceiling && !floor) {
            return;
        }
        boolean growDown = ceiling && (!floor || rng.nextBoolean());
        int[] slots = growDown ? downSlots : upSlots;
        if (slots == null || slots.length == 0 || slots[0] <= 0) {
            return;
        }
        int dir = growDown ? -1 : 1;
        int nextVoxel = getVoxelAt(frame, spec, lx, wy + dir, lz);
        boolean nextEmptyOrWater = (wy + dir >= minBoundY && wy + dir <= maxBoundY)
                && (nextVoxel == 0 || (waterSlot > 0 && nextVoxel == waterSlot));
        int len = (rng.nextInt(4) == 0 && nextEmptyOrWater) ? 2 : 1;
        if (node.intA() > 0 && rng.nextInt(5) == 0) {
            int hostY = wy - dir;
            int hostSec = (hostY - spec.chunkMinY()) >> 4;
            if (hostSec >= 0 && hostSec < frame.sectionCount()) {
                frame.setVoxel(hostSec, ((hostY & 15) << 8) | (lz << 4) | lx, node.intA());
            }
        }
        int sec0 = (wy - spec.chunkMinY()) >> 4;
        if (len == 1) {
            if (sec0 >= 0 && sec0 < frame.sectionCount()) {
                frame.setVoxel(sec0, ((wy & 15) << 8) | (lz << 4) | lx, slots[0]);
            }
        } else {
            int frustum = slots.length > 1 && slots[1] > 0 ? slots[1] : slots[0];
            if (sec0 >= 0 && sec0 < frame.sectionCount()) {
                frame.setVoxel(sec0, ((wy & 15) << 8) | (lz << 4) | lx, frustum);
            }
            int sec1 = (wy + dir - spec.chunkMinY()) >> 4;
            if (sec1 >= 0 && sec1 < frame.sectionCount()) {
                frame.setVoxel(sec1, (((wy + dir) & 15) << 8) | (lz << 4) | lx, slots[0]);
            }
        }
    }

    private static void stampSpeleothemClusterNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            LiftedFeatureNode node,
            int lx,
            int wy,
            int lz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng
    ) {
        int baseSlot = node.intA();
        int[] upSlots = node.auxSlotsA();
        int[] downSlots = node.auxSlotsB();
        int r = node.intProvB() != null ? Math.max(1, Math.min(4, node.intProvB().sample(rng))) : 2;
        int maxH = node.intProvA() != null ? Math.max(2, Math.min(8, node.intProvA().sample(rng))) : 4;
        for (int dz = -r; dz <= r; dz++) {
            int tz = lz + dz;
            if (tz < 0 || tz > 15) continue;
            for (int dx = -r; dx <= r; dx++) {
                int tx = lx + dx;
                if (tx < 0 || tx > 15 || dx * dx + dz * dz > r * r + 1) continue;
                // Find local cave ceiling or floor near wy
                for (int dy = -4; dy <= 4; dy++) {
                    int cy = wy + dy;
                    if (cy <= minBoundY || cy >= maxBoundY || getVoxelAt(frame, spec, tx, cy, tz) != 0) continue;
                    boolean onFloor = isMultifaceHost(frame, spec, node.slotMaskA(), tx, cy - 1, tz);
                    boolean onCeil = isMultifaceHost(frame, spec, node.slotMaskA(), tx, cy + 1, tz);
                    if (!onFloor && !onCeil) continue;
                    int dir = onCeil ? -1 : 1;
                    int[] pSlots = onCeil ? downSlots : upSlots;
                    if (baseSlot > 0) {
                        int hs = (cy - dir - spec.chunkMinY()) >> 4;
                        if (hs >= 0 && hs < frame.sectionCount()) {
                            frame.setVoxel(hs, (((cy - dir) & 15) << 8) | (tz << 4) | tx, baseSlot);
                        }
                    }
                    int colLen = 1 + rng.nextInt(Math.max(1, maxH - Math.abs(dx) - Math.abs(dz)));
                    stampPointedSpikeRun(frame, spec, pSlots, tx, cy, tz, dir, colLen, minBoundY, maxBoundY);
                    break;
                }
            }
        }
    }

    private static void stampLargeDripstoneNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            LiftedFeatureNode node,
            int lx,
            int wy,
            int lz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng
    ) {
        int baseSlot = node.intA();
        if (baseSlot <= 0 || getVoxelAt(frame, spec, lx, wy, lz) != 0) return;
        // Scan up and down for ceiling/floor inside dripstone_caves
        int ceilY = Integer.MIN_VALUE;
        int floorY = Integer.MIN_VALUE;
        for (int step = 1; step <= 16; step++) {
            if (ceilY == Integer.MIN_VALUE && wy + step <= maxBoundY && isMultifaceHost(frame, spec, node.slotMaskA(), lx, wy + step, lz)) {
                ceilY = wy + step - 1;
            }
            if (floorY == Integer.MIN_VALUE && wy - step >= minBoundY && isMultifaceHost(frame, spec, node.slotMaskA(), lx, wy - step, lz)) {
                floorY = wy - step + 1;
            }
        }
        if (floorY != Integer.MIN_VALUE) {
            int len = 2 + rng.nextInt(5);
            for (int i = 0; i < len && floorY + i <= maxBoundY; i++) {
                setVoxelIfAir(frame, spec, lx, floorY + i, lz, baseSlot, false);
            }
        }
        if (ceilY != Integer.MIN_VALUE) {
            int len = 2 + rng.nextInt(5);
            for (int i = 0; i < len && ceilY - i >= minBoundY; i++) {
                setVoxelIfAir(frame, spec, lx, ceilY - i, lz, baseSlot, false);
            }
        }
    }

    private static void stampPointedSpikeRun(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            int[] pSlots,
            int lx,
            int startY,
            int lz,
            int dir,
            int len,
            int minBoundY,
            int maxBoundY
    ) {
        if (pSlots == null || pSlots.length == 0 || pSlots[0] <= 0) return;
        int actual = 0;
        for (int i = 0; i < len; i++) {
            int y = startY + dir * i;
            if (y < minBoundY || y > maxBoundY || getVoxelAt(frame, spec, lx, y, lz) != 0) break;
            actual++;
        }
        for (int i = 0; i < actual; i++) {
            int y = startY + dir * i;
            int rem = actual - 1 - i;
            int slot;
            if (rem == 0) slot = pSlots[0];
            else if (rem == 1 && pSlots.length > 1 && pSlots[1] > 0) slot = pSlots[1];
            else if (i == 0 && actual >= 3 && pSlots.length > 3 && pSlots[3] > 0) slot = pSlots[3];
            else if (pSlots.length > 2 && pSlots[2] > 0) slot = pSlots[2];
            else slot = pSlots[0];
            setVoxelIfAir(frame, spec, lx, y, lz, slot, false);
        }
    }

    private static void stampGeodeNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            boolean[] leafSlotMask,
            LiftedFeatureNode node,
            int rawLx,
            int wy,
            int rawLz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng
    ) {
        int outerSlot = node.intA();
        int middleSlot = node.intB();
        int innerSlot = node.intC();
        int altInnerSlot = node.intD();
        int invalidThreshold = Math.max(0, node.intE());
        if (outerSlot <= 0 || innerSlot <= 0) return;

        int r = 4 + rng.nextInt(2); // radius 4..5 within chunk interior
        int cx = Math.max(r, Math.min(15 - r, rawLx));
        int cz = Math.max(r, Math.min(15 - r, rawLz));
        if (wy - r < minBoundY || wy + r > maxBoundY) {
            return;
        }

        // Mojang GeodeFeature invalidBlocksThreshold host-rock check across 8 octant sample points + center!
        int invalid = 0;
        if (!isSolidGeodeHost(frame, spec, leafSlotMask, cx, wy, cz)) {
            invalid += invalidThreshold + 1;
        }
        int probe = Math.max(2, r - 1);
        int[][] corners = {
            {-probe, -probe, -probe}, {probe, -probe, -probe}, {-probe, probe, -probe}, {probe, probe, -probe},
            {-probe, -probe, probe}, {probe, -probe, probe}, {-probe, probe, probe}, {probe, probe, probe}
        };
        for (int[] c : corners) {
            if (!isSolidGeodeHost(frame, spec, leafSlotMask, cx + c[0], wy + c[1], cz + c[2])) {
                if (++invalid > invalidThreshold) {
                    return;
                }
            }
        }
        if (invalid > invalidThreshold) {
            return;
        }

        boolean hasCrack = rng.nextInt(20) < 19;
        int crackDx = rng.nextBoolean() ? 1 : -1;
        for (int dy = -r; dy <= r; dy++) {
            int y = wy + dy;
            int secIdx = (y - spec.chunkMinY()) >> 4;
            if (secIdx < 0 || secIdx >= frame.sectionCount()) continue;
            for (int dz = -r; dz <= r; dz++) {
                int z = cz + dz;
                for (int dx = -r; dx <= r; dx++) {
                    int x = cx + dx;
                    float jitter = (((dx * 17 + dy * 31 + dz * 13) & 7) - 3.5f) * 0.14f;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) + jitter;
                    if (dist > r + 0.35f) continue;
                    int idx = ((y & 15) << 8) | (z << 4) | x;
                    int cur = frame.getVoxel(secIdx, idx);
                    if (node.slotMaskA() != null && bitMaskContains(node.slotMaskA(), cur)) {
                        continue; // #geode_invalid_blocks (bedrock)
                    }
                    if (hasCrack && dy >= -1 && dy <= 1 && dx * crackDx > 0 && Math.abs(dz) <= 1 && dist > r - 2.2f) {
                        frame.setVoxel(secIdx, idx, 0);
                    } else if (dist > r - 0.85f) {
                        frame.setVoxel(secIdx, idx, outerSlot);
                    } else if (dist > r - 1.75f) {
                        frame.setVoxel(secIdx, idx, middleSlot > 0 ? middleSlot : outerSlot);
                    } else if (dist > r - 2.65f) {
                        int pick = (altInnerSlot > 0 && rng.nextInt(12) == 0) ? altInnerSlot : innerSlot;
                        frame.setVoxel(secIdx, idx, pick);
                    } else {
                        frame.setVoxel(secIdx, idx, 0);
                    }
                }
            }
        }
        // Place amethyst_cluster buds adjacent to budding_amethyst inside the hollow core
        int[] budSlots = node.auxSlotsA();
        if (altInnerSlot > 0 && budSlots != null && budSlots.length > 0 && budSlots[0] > 0) {
            for (int dy = -r + 1; dy <= r - 1; dy++) {
                for (int dz = -r + 1; dz <= r - 1; dz++) {
                    for (int dx = -r + 1; dx <= r - 1; dx++) {
                        if (getVoxelAt(frame, spec, cx + dx, wy + dy, cz + dz) == altInnerSlot) {
                            if (getVoxelAt(frame, spec, cx + dx, wy + dy + 1, cz + dz) == 0 && rng.nextInt(3) == 0) {
                                setVoxelIfAir(frame, spec, cx + dx, wy + dy + 1, cz + dz, budSlots[0], false);
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean isSolidGeodeHost(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            boolean[] leafSlotMask,
            int lx,
            int wy,
            int lz
    ) {
        int v = getVoxelAt(frame, spec, lx, wy, lz);
        return v > 0
                && v != spec.defaultFluidSlot()
                && v != spec.lavaFluidSlot()
                && !isLeafSlot(leafSlotMask, v);
    }

    private static void stampSculkPatchNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            LiftedFeatureNode node,
            int lx,
            int wy,
            int lz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng
    ) {
        int sculkSlot = node.intA();
        if (sculkSlot <= 0 || getVoxelAt(frame, spec, lx, wy, lz) != 0) return;
        if (wy - 1 < minBoundY || !isMultifaceHost(frame, spec, node.slotMaskA(), lx, wy - 1, lz)) return;
        int r = 2 + rng.nextInt(3);
        for (int dz = -r; dz <= r; dz++) {
            int tz = lz + dz;
            if (tz < 0 || tz > 15) continue;
            for (int dx = -r; dx <= r; dx++) {
                int tx = lx + dx;
                if (tx < 0 || tx > 15 || dx * dx + dz * dz > r * r) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    int y = wy + dy;
                    if (y <= minBoundY || y > maxBoundY) continue;
                    if (getVoxelAt(frame, spec, tx, y, tz) == 0
                            && isMultifaceHost(frame, spec, node.slotMaskA(), tx, y - 1, tz)) {
                        int sIdx = (y - 1 - spec.chunkMinY()) >> 4;
                        if (sIdx >= 0 && sIdx < frame.sectionCount()) {
                            frame.setVoxel(sIdx, (((y - 1) & 15) << 8) | (tz << 4) | tx, sculkSlot);
                        }
                        break;
                    }
                }
            }
        }
        if (node.intB() > 0 && rng.nextInt(3) == 0) {
            setVoxelIfAir(frame, spec, lx, wy, lz, node.intB(), false);
        }
    }

    private static void stampVegetationPatchNode(
            LcaCapabilityChunkFrame frame,
            int[] quartBiomes,
            int[] topSurfaceY,
            boolean[] leafSlotMask,
            DimensionCapabilitySpec spec,
            long[] allowedBiomeMask,
            LiftedFeatureNode node,
            int chunkBx,
            int chunkBz,
            int lx,
            int wy,
            int lz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng,
            DecorScratch scratch,
            int curDepth
    ) {
        int groundSlot = node.intA();
        if (groundSlot <= 0 || getVoxelAt(frame, spec, lx, wy, lz) != 0) return;
        int dir = node.intD() >= 0 ? 1 : -1; // -1 = floor (moss/clay), +1 = ceiling (cave_vine patch)
        int r = node.intProvA() != null ? Math.max(1, Math.min(5, node.intProvA().sample(rng))) : 2;
        int depth = Math.max(1, node.intB());
        int vegPermille = node.intC();
        for (int dz = -r; dz <= r; dz++) {
            int tz = lz + dz;
            if (tz < 0 || tz > 15) continue;
            for (int dx = -r; dx <= r; dx++) {
                int tx = lx + dx;
                if (tx < 0 || tx > 15 || dx * dx + dz * dz > r * r + 1) continue;
                for (int dy = -3; dy <= 3; dy++) {
                    int airY = wy + dy;
                    int surfY = airY + dir;
                    if (surfY < minBoundY || surfY > maxBoundY || getVoxelAt(frame, spec, tx, airY, tz) != 0) continue;
                    if (!isMultifaceHost(frame, spec, node.slotMaskA(), tx, surfY, tz)) continue;
                    for (int d = 0; d < depth; d++) {
                        int gy = surfY + dir * d;
                        if (gy < minBoundY || gy > maxBoundY) break;
                        if (isMultifaceHost(frame, spec, node.slotMaskA(), tx, gy, tz)) {
                            int sIdx = (gy - spec.chunkMinY()) >> 4;
                            if (sIdx >= 0 && sIdx < frame.sectionCount()) {
                                frame.setVoxel(sIdx, ((gy & 15) << 8) | (tz << 4) | tx, groundSlot);
                            }
                        }
                    }
                    if (vegPermille > 0 && rng.nextInt(1000) < vegPermille) {
                        if (node.childFeature() != null) {
                            executeFeatureNode(
                                    frame,
                                    quartBiomes,
                                    topSurfaceY,
                                    leafSlotMask,
                                    spec,
                                    chunkBx,
                                    chunkBz,
                                    minBoundY,
                                    maxBoundY,
                                    allowedBiomeMask,
                                    node.childFeature(),
                                    tx,
                                    airY,
                                    tz,
                                    rng,
                                    scratch,
                                    curDepth + 1);
                        } else if (node.stateProvA() != null) {
                            long pair = node.stateProvA().sampleSlotAndUpper(chunkBx + tx, airY, chunkBz + tz, rng);
                            int vSlot = (int) pair;
                            int vUpper = (int) (pair >>> 32);
                            if (vUpper > 0 && airY + 1 <= maxBoundY && getVoxelAt(frame, spec, tx, airY + 1, tz) == 0) {
                                setVoxelIfAirOrLeaf(frame, spec, leafSlotMask, tx, airY, tz, vSlot, false);
                                setVoxelIfAirOrLeaf(frame, spec, leafSlotMask, tx, airY + 1, tz, vUpper, false);
                            } else if (vUpper <= 0) {
                                setVoxelIfAirOrLeaf(frame, spec, leafSlotMask, tx, airY, tz, vSlot, false);
                            }
                        }
                    }
                    break;
                }
            }
        }
    }

    private static void stampRootSystemNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            boolean[] leafSlotMask,
            LiftedFeatureNode node,
            int chunkBx,
            int chunkBz,
            int lx,
            int wy,
            int lz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng,
            DecorScratch scratch,
            int depth
    ) {
        // Scan upward from cave ceiling to surface for azalea tree space, placing rooted_dirt column along the way
        int rootSlot = node.intA();
        int hangingSlot = node.intB();
        int maxSearch = Math.max(8, Math.min(64, node.intC()));
        if (wy + 1 > maxBoundY || getVoxelAt(frame, spec, lx, wy, lz) != 0) return;
        if (!isMultifaceHost(frame, spec, node.slotMaskA(), lx, wy + 1, lz)) return;
        int surfY = Integer.MIN_VALUE;
        for (int step = 1; step <= maxSearch && wy + step + 4 <= maxBoundY; step++) {
            if (getVoxelAt(frame, spec, lx, wy + step, lz) == 0
                    && isValidSoilAt(frame, spec, leafSlotMask, null, lx, wy + step - 1, lz)) {
                surfY = wy + step;
                break;
            }
        }
        if (surfY == Integer.MIN_VALUE) return;
        if (rootSlot > 0) {
            for (int y = wy + 1; y < surfY; y++) {
                if (isMultifaceHost(frame, spec, node.slotMaskA(), lx, y, lz)) {
                    int sIdx = (y - spec.chunkMinY()) >> 4;
                    if (sIdx >= 0 && sIdx < frame.sectionCount()) {
                        frame.setVoxel(sIdx, ((y & 15) << 8) | (lz << 4) | lx, rootSlot);
                    }
                }
            }
        }
        if (hangingSlot > 0) {
            setVoxelIfAir(frame, spec, lx, wy, lz, hangingSlot, false);
        }
        if (node.childFeature() != null) {
            executeFeatureNode(
                    frame, null, null, leafSlotMask, spec, chunkBx, chunkBz, minBoundY, maxBoundY, null, node.childFeature(), lx, surfY, lz, rng, scratch, depth + 1);
        }
    }

    private static void stampHugeFungusNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            boolean[] leafSlotMask,
            LiftedFeatureNode node,
            int rawLx,
            int wy,
            int rawLz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng
    ) {
        int validBase = node.intA();
        int stemSlot = node.intB();
        int hatSlot = node.intC();
        int decorSlot = node.intD();
        int lx = Math.max(2, Math.min(13, rawLx));
        int lz = Math.max(2, Math.min(13, rawLz));
        if (stemSlot <= 0 || wy - 1 < minBoundY) return;
        if (getVoxelAt(frame, spec, lx, wy, lz) == 0 && getVoxelAt(frame, spec, lx, wy - 1, lz) == 0) {
            for (int drop = 1; drop <= 56 && wy - drop - 1 >= minBoundY; drop++) {
                if (getVoxelAt(frame, spec, lx, wy - drop, lz) != 0) {
                    wy = wy - drop + 1;
                    break;
                }
            }
        }
        int ground = getVoxelAt(frame, spec, lx, wy - 1, lz);
        if (validBase > 0 && ground != validBase) return;
        int h = 4 + rng.nextInt(6);
        if (wy + h + 1 > maxBoundY) return;
        for (int y = wy; y <= wy + h; y++) {
            if (getVoxelAt(frame, spec, lx, y, lz) != 0) return;
        }
        for (int y = wy; y < wy + h; y++) {
            setVoxelIfAirOrLeaf(frame, spec, leafSlotMask, lx, y, lz, stemSlot, true);
        }
        int topY = wy + h - 1;
        for (int y = Math.max(wy + 2, topY - 2); y <= topY + 1; y++) {
            int r = y > topY ? 1 : 2;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.abs(dx) == r && Math.abs(dz) == r && y <= topY) continue;
                    boolean shell = y > topY || Math.abs(dx) == r || Math.abs(dz) == r;
                    if (shell && hatSlot > 0) {
                        setVoxelIfAirOrLeaf(frame, spec, leafSlotMask, lx + dx, y, lz + dz, hatSlot, false);
                    } else if (decorSlot > 0 && rng.nextInt(8) == 0) {
                        setVoxelIfAirOrLeaf(frame, spec, leafSlotMask, lx + dx, y, lz + dz, decorSlot, false);
                    }
                }
            }
        }
    }

    private static void stampHugeMushroomNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            boolean[] leafSlotMask,
            LiftedFeatureNode node,
            int chunkBx,
            int chunkBz,
            int rawLx,
            int wy,
            int rawLz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng
    ) {
        if (node.stateProvA() == null || node.stateProvB() == null || wy - 1 < minBoundY) return;
        int lx = Math.max(3, Math.min(12, rawLx));
        int lz = Math.max(3, Math.min(12, rawLz));
        if (!isValidSoilAt(frame, spec, leafSlotMask, node.slotMaskA(), lx, wy - 1, lz)) return;
        int h = 5 + rng.nextInt(3);
        if (wy + h > maxBoundY) return;
        for (int y = wy; y <= wy + h; y++) {
            if (getVoxelAt(frame, spec, lx, y, lz) != 0) return;
        }
        int stemSlot = node.stateProvA().sampleSlot(chunkBx + lx, wy, chunkBz + lz, rng);
        int capSlot = node.stateProvB().sampleSlot(chunkBx + lx, wy + h, chunkBz + lz, rng);
        for (int y = wy; y < wy + h; y++) {
            setVoxelIfAirOrLeaf(frame, spec, leafSlotMask, lx, y, lz, stemSlot, true);
        }
        if (node.flagA()) {
            // Flat brown mushroom cap (radius 3)
            for (int dz = -3; dz <= 3; dz++) {
                for (int dx = -3; dx <= 3; dx++) {
                    if (Math.abs(dx) == 3 && Math.abs(dz) == 3) continue;
                    setVoxelIfAirOrLeaf(frame, spec, leafSlotMask, lx + dx, wy + h, lz + dz, capSlot, false);
                }
            }
        } else {
            // Dome red mushroom cap (radius 2)
            for (int yo = -2; yo <= 0; yo++) {
                int r = yo == 0 ? 1 : 2;
                for (int dz = -r; dz <= r; dz++) {
                    for (int dx = -r; dx <= r; dx++) {
                        if (yo < 0 && Math.abs(dx) < r && Math.abs(dz) < r) continue;
                        if (Math.abs(dx) == r && Math.abs(dz) == r) continue;
                        setVoxelIfAirOrLeaf(frame, spec, leafSlotMask, lx + dx, wy + h + yo, lz + dz, capSlot, false);
                    }
                }
            }
        }
    }

    private static void stampChorusNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            boolean[] leafSlotMask,
            LiftedFeatureNode node,
            int rawLx,
            int wy,
            int rawLz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng
    ) {
        int stemSlot = node.intA();
        int flowerSlot = node.intB() > 0 ? node.intB() : stemSlot;
        int endStoneSlot = node.intC();
        int lx = Math.max(2, Math.min(13, rawLx));
        int lz = Math.max(2, Math.min(13, rawLz));
        if (stemSlot <= 0 || wy - 1 < minBoundY) return;
        int ground = getVoxelAt(frame, spec, lx, wy - 1, lz);
        if (endStoneSlot > 0 && ground != endStoneSlot) return;
        int h = 4 + rng.nextInt(5);
        if (wy + h + 2 > maxBoundY) return;
        for (int y = wy; y < wy + h; y++) {
            setVoxelIfAirOrLeaf(frame, spec, leafSlotMask, lx, y, lz, stemSlot, false);
        }
        int topY = wy + h - 1;
        setVoxelIfAirOrLeaf(frame, spec, leafSlotMask, lx, topY + 1, lz, flowerSlot, false);
        for (int b = 0; b < 2; b++) {
            int dir = rng.nextInt(4);
            int bx = lx + (dir == 0 ? 1 : (dir == 1 ? -1 : 0));
            int bz = lz + (dir == 2 ? 1 : (dir == 3 ? -1 : 0));
            setVoxelIfAirOrLeaf(frame, spec, leafSlotMask, bx, topY, bz, stemSlot, false);
            setVoxelIfAirOrLeaf(frame, spec, leafSlotMask, bx, topY + 1, bz, flowerSlot, false);
        }
    }

    private static void stampBlockBlobNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            LiftedFeatureNode node,
            int chunkBx,
            int chunkBz,
            int rawLx,
            int wy,
            int rawLz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng
    ) {
        if (node.stateProvA() == null || wy - 1 < minBoundY) return;
        int lx = Math.max(2, Math.min(13, rawLx));
        int lz = Math.max(2, Math.min(13, rawLz));
        if (!isMultifaceHost(frame, spec, node.slotMaskA(), lx, wy - 1, lz)) return;
        for (int blob = 0; blob < 3; blob++) {
            int rx = 1 + rng.nextInt(2);
            int ry = 1 + rng.nextInt(2);
            int rz = 1 + rng.nextInt(2);
            float maxDistSq = (rx + ry + rz) * 0.333f + 0.5f;
            maxDistSq *= maxDistSq;
            for (int dy = -ry; dy <= ry; dy++) {
                int y = wy + dy;
                if (y < minBoundY || y > maxBoundY) continue;
                int secIdx = (y - spec.chunkMinY()) >> 4;
                if (secIdx < 0 || secIdx >= frame.sectionCount()) continue;
                for (int dz = -rz; dz <= rz; dz++) {
                    int tz = lz + dz;
                    if (tz < 0 || tz > 15) continue;
                    for (int dx = -rx; dx <= rx; dx++) {
                        int tx = lx + dx;
                        if (tx < 0 || tx > 15) continue;
                        if (dx * dx + dy * dy + dz * dz <= maxDistSq) {
                            int slot = node.stateProvA().sampleSlot(chunkBx + tx, y, chunkBz + tz, rng);
                            if (slot > 0) {
                                frame.setVoxel(secIdx, ((y & 15) << 8) | (tz << 4) | tx, slot);
                            }
                        }
                    }
                }
            }
            lx = Math.max(2, Math.min(13, lx + rng.nextInt(3) - 1));
            lz = Math.max(2, Math.min(13, lz + rng.nextInt(3) - 1));
        }
    }

    private static void stampReplaceBlobsNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            LiftedFeatureNode node,
            int lx,
            int wy,
            int lz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng
    ) {
        int targetSlot = node.intA();
        int replaceSlot = node.intB();
        if (targetSlot <= 0 || replaceSlot <= 0) return;
        int r = node.intProvA() != null ? Math.max(1, Math.min(5, node.intProvA().sample(rng))) : 2;
        for (int dy = -r; dy <= r; dy++) {
            int y = wy + dy;
            if (y < minBoundY || y > maxBoundY) continue;
            int secIdx = (y - spec.chunkMinY()) >> 4;
            if (secIdx < 0 || secIdx >= frame.sectionCount()) continue;
            for (int dz = -r; dz <= r; dz++) {
                int tz = lz + dz;
                if (tz < 0 || tz > 15) continue;
                for (int dx = -r; dx <= r; dx++) {
                    int tx = lx + dx;
                    if (tx < 0 || tx > 15 || Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > r + 1) continue;
                    int idx = ((y & 15) << 8) | (tz << 4) | tx;
                    if (frame.getVoxel(secIdx, idx) == targetSlot) {
                        frame.setVoxel(secIdx, idx, replaceSlot);
                    }
                }
            }
        }
    }

    private static void stampDeltaNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            LiftedFeatureNode node,
            int lx,
            int wy,
            int lz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng
    ) {
        int contentsSlot = node.intA();
        int rimSlot = node.intB();
        if (contentsSlot <= 0 || wy <= minBoundY || getVoxelAt(frame, spec, lx, wy, lz) != 0) return;
        int r = node.intProvA() != null ? Math.max(1, Math.min(4, node.intProvA().sample(rng))) : 2;
        int floorY = wy - 1;
        int secIdx = (floorY - spec.chunkMinY()) >> 4;
        if (secIdx < 0 || secIdx >= frame.sectionCount()) return;
        for (int dz = -r; dz <= r; dz++) {
            int tz = lz + dz;
            if (tz < 1 || tz > 14) continue;
            for (int dx = -r; dx <= r; dx++) {
                int tx = lx + dx;
                if (tx < 1 || tx > 14) continue;
                int man = Math.abs(dx) + Math.abs(dz);
                if (man > r) continue;
                if (getVoxelAt(frame, spec, tx, floorY + 1, tz) != 0) continue;
                int cur = getVoxelAt(frame, spec, tx, floorY, tz);
                if (cur <= 0 || cur == spec.lavaFluidSlot()) continue;
                boolean edge = man == r
                        || getVoxelAt(frame, spec, tx - 1, floorY, tz) == 0
                        || getVoxelAt(frame, spec, tx + 1, floorY, tz) == 0
                        || getVoxelAt(frame, spec, tx, floorY, tz - 1) == 0
                        || getVoxelAt(frame, spec, tx, floorY, tz + 1) == 0;
                frame.setVoxel(secIdx, ((floorY & 15) << 8) | (tz << 4) | tx, edge && rimSlot > 0 ? rimSlot : contentsSlot);
            }
        }
    }

    private static void stampSpikeNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            boolean[] leafSlotMask,
            LiftedFeatureNode node,
            int rawLx,
            int wy,
            int rawLz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng
    ) {
        int primarySlot = node.intA();
        if (primarySlot <= 0 || wy - 1 < minBoundY) return;
        int lx = Math.max(2, Math.min(13, rawLx));
        int lz = Math.max(2, Math.min(13, rawLz));
        if (!node.flagA() && !isMultifaceHost(frame, spec, node.slotMaskA(), lx, wy - 1, lz)) return;
        int h = node.flagA() ? (16 + rng.nextInt(16)) : (7 + rng.nextInt(6));
        int r = node.flagA() ? 2 : 1;
        for (int dy = 0; dy < h && wy + dy <= maxBoundY; dy++) {
            int curR = (!node.flagA() && dy > h / 2) ? 0 : r;
            for (int dz = -curR; dz <= curR; dz++) {
                for (int dx = -curR; dx <= curR; dx++) {
                    if (dx * dx + dz * dz <= curR * curR + (node.flagA() ? 1 : 0)) {
                        setVoxelIfAirOrLeaf(frame, spec, leafSlotMask, lx + dx, wy + dy, lz + dz, primarySlot, true);
                    }
                }
            }
        }
    }

    private static void stampAquaticPlantNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            int[] topSurfaceY,
            boolean[] leafSlotMask,
            LiftedFeatureNode node,
            int lx,
            int wy,
            int lz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng
    ) {
        int waterSlot = spec.defaultFluidSlot();
        int primarySlot = node.intB();
        if (waterSlot <= 0 || primarySlot <= 0) return;

        int kind = node.intA(); // 0 = seagrass, 1 = kelp, 2 = sea_pickle
        if (kind == 0) {
            int tx = mirrorChunkCoord(lx + rng.nextInt(8) - rng.nextInt(8));
            int tz = mirrorChunkCoord(lz + rng.nextInt(8) - rng.nextInt(8));
            int sy = sampleColumnHeightmapFreeY(
                    frame, spec, topSurfaceY, leafSlotMask, tx, tz, HM_OCEAN_FLOOR, minBoundY, maxBoundY);
            if (sy - 1 < minBoundY || sy > maxBoundY || getVoxelAt(frame, spec, tx, sy, tz) != waterSlot) {
                return;
            }
            if (!isMultifaceHost(frame, spec, node.slotMaskA(), tx, sy - 1, tz)) {
                return;
            }
            boolean tall = node.intC() > 0 && node.intD() > 0 && rng.nextInt(1000) < node.intE();
            if (tall) {
                if (sy + 1 <= maxBoundY && getVoxelAt(frame, spec, tx, sy + 1, tz) == waterSlot) {
                    int s0 = (sy - spec.chunkMinY()) >> 4;
                    int s1 = (sy + 1 - spec.chunkMinY()) >> 4;
                    if (s0 >= 0 && s0 < frame.sectionCount() && s1 >= 0 && s1 < frame.sectionCount()) {
                        frame.setVoxel(s0, ((sy & 15) << 8) | (tz << 4) | tx, node.intC());
                        frame.setVoxel(s1, (((sy + 1) & 15) << 8) | (tz << 4) | tx, node.intD());
                    }
                }
            } else {
                int sIdx = (sy - spec.chunkMinY()) >> 4;
                if (sIdx >= 0 && sIdx < frame.sectionCount()) {
                    frame.setVoxel(sIdx, ((sy & 15) << 8) | (tz << 4) | tx, primarySlot);
                }
            }
        } else if (kind == 1) {
            int sy = wy;
            if (sy - 1 < minBoundY || sy > maxBoundY || getVoxelAt(frame, spec, lx, sy, lz) != waterSlot) {
                sy = sampleColumnHeightmapFreeY(
                        frame, spec, topSurfaceY, leafSlotMask, lx, lz, HM_OCEAN_FLOOR, minBoundY, maxBoundY);
            }
            if (sy - 1 < minBoundY || sy > maxBoundY || getVoxelAt(frame, spec, lx, sy, lz) != waterSlot) {
                return;
            }
            if (!isMultifaceHost(frame, spec, node.slotMaskA(), lx, sy - 1, lz)) {
                return;
            }
            int bodySlot = node.intC() > 0 ? node.intC() : primarySlot;
            int len = 1 + rng.nextInt(10);
            int placed = 0;
            for (int i = 0; i < len && sy + i <= maxBoundY; i++) {
                if (getVoxelAt(frame, spec, lx, sy + i, lz) != waterSlot
                        || getVoxelAt(frame, spec, lx, sy + i + 1, lz) != waterSlot) {
                    break;
                }
                placed++;
            }
            for (int i = 0; i < placed; i++) {
                int y = sy + i;
                int sIdx = (y - spec.chunkMinY()) >> 4;
                if (sIdx >= 0 && sIdx < frame.sectionCount()) {
                    frame.setVoxel(sIdx, ((y & 15) << 8) | (lz << 4) | lx, (i == placed - 1) ? primarySlot : bodySlot);
                }
            }
        } else {
            int count = 1 + rng.nextInt(4);
            for (int p = 0; p < count; p++) {
                int tx = mirrorChunkCoord(lx + rng.nextInt(8) - rng.nextInt(8));
                int tz = mirrorChunkCoord(lz + rng.nextInt(8) - rng.nextInt(8));
                int sy = sampleColumnHeightmapFreeY(
                        frame, spec, topSurfaceY, leafSlotMask, tx, tz, HM_OCEAN_FLOOR, minBoundY, maxBoundY);
                if (sy - 1 < minBoundY || sy > maxBoundY || getVoxelAt(frame, spec, tx, sy, tz) != waterSlot) {
                    continue;
                }
                if (!isMultifaceHost(frame, spec, node.slotMaskA(), tx, sy - 1, tz)) {
                    continue;
                }
                int sIdx = (sy - spec.chunkMinY()) >> 4;
                if (sIdx >= 0 && sIdx < frame.sectionCount()) {
                    frame.setVoxel(sIdx, ((sy & 15) << 8) | (tz << 4) | tx, primarySlot);
                }
            }
        }
    }

    private static void stampSpringNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            LiftedFeatureNode node,
            int lx,
            int wy,
            int lz,
            int minBoundY,
            int maxBoundY
    ) {
        int fluidSlot = node.intA();
        if (fluidSlot <= 0 || lx < 1 || lx > 14 || lz < 1 || lz > 14 || wy <= minBoundY || wy >= maxBoundY) {
            return;
        }
        if (!isMultifaceHost(frame, spec, node.slotMaskA(), lx, wy + 1, lz)
                || !isMultifaceHost(frame, spec, node.slotMaskA(), lx, wy, lz)) {
            return;
        }
        if (node.flagA() && !isMultifaceHost(frame, spec, node.slotMaskA(), lx, wy - 1, lz)) {
            return;
        }
        int rocks = 0;
        int holes = 0;
        int[][] dirs = {{-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}, {0, -1, 0}};
        for (int[] d : dirs) {
            int v = getVoxelAt(frame, spec, lx + d[0], wy + d[1], lz + d[2]);
            if (v == 0) holes++;
            else if (node.slotMaskA() == null || bitMaskContains(node.slotMaskA(), v)) rocks++;
        }
        if (rocks == node.intB() && holes == node.intC()) {
            int sIdx = (wy - spec.chunkMinY()) >> 4;
            if (sIdx >= 0 && sIdx < frame.sectionCount()) {
                int pos12 = ((wy & 15) << 8) | (lz << 4) | lx;
                frame.setVoxel(sIdx, pos12, fluidSlot);
                frame.recordPostProcessVoxel(sIdx, pos12);
            }
        }
    }

    private static void stampLakeNode(
            LcaCapabilityChunkFrame frame,
            DimensionCapabilitySpec spec,
            boolean[] leafSlotMask,
            LiftedFeatureNode node,
            int rawLx,
            int wy,
            int rawLz,
            int minBoundY,
            int maxBoundY,
            DecorRng rng,
            DecorScratch scratch
    ) {
        int fluidSlot = node.intA();
        int barrierSlot = node.intB();
        if (fluidSlot <= 0) return;
        int baseY = wy - 4;
        if (baseY <= minBoundY || baseY + 7 > maxBoundY) return;

        // Mojang LakeFeature: 4..7 overlapping random 3D ellipsoids in a 16x16x8 volume (eliminating 3x3 square boxes)
        boolean[] grid = scratch.lakeGrid;
        Arrays.fill(grid, false);
        int blobs = 4 + rng.nextInt(4);
        for (int b = 0; b < blobs; b++) {
            double sx = rng.nextFloat() * 6.0 + 3.0;
            double sy = rng.nextFloat() * 4.0 + 2.0;
            double sz = rng.nextFloat() * 6.0 + 3.0;
            double cx = rng.nextFloat() * (16.0 - sx - 2.0) + 1.0 + sx * 0.5;
            double cy = rng.nextFloat() * (8.0 - sy - 4.0) + 2.0 + sy * 0.5;
            double cz = rng.nextFloat() * (16.0 - sz - 2.0) + 1.0 + sz * 0.5;
            double invRx = 2.0 / sx;
            double invRy = 2.0 / sy;
            double invRz = 2.0 / sz;
            for (int lx = 1; lx < 15; lx++) {
                double dx = (lx - cx) * invRx;
                double dx2 = dx * dx;
                if (dx2 >= 1.0) continue;
                for (int lz = 1; lz < 15; lz++) {
                    double dz = (lz - cz) * invRz;
                    double dxz2 = dx2 + dz * dz;
                    if (dxz2 >= 1.0) continue;
                    int colOff = ((lx << 4) | lz) << 3;
                    for (int ly = 1; ly < 7; ly++) {
                        double dy = (ly - cy) * invRy;
                        if (dxz2 + dy * dy < 1.0) {
                            grid[colOff | ly] = true;
                        }
                    }
                }
            }
        }

        int waterSlot = spec.defaultFluidSlot();
        int lavaSlot = spec.lavaFluidSlot();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int colOff = ((lx << 4) | lz) << 3;
                for (int ly = 0; ly < 8; ly++) {
                    if (grid[colOff | ly]) continue;
                    boolean rim = (lx < 15 && grid[colOff + 128 + ly])
                            || (lx > 0 && grid[colOff - 128 + ly])
                            || (lz < 15 && grid[colOff + 8 + ly])
                            || (lz > 0 && grid[colOff - 8 + ly])
                            || (ly < 7 && grid[colOff + ly + 1])
                            || (ly > 0 && grid[colOff + ly - 1]);
                    if (!rim) continue;
                    int cur = getVoxelAt(frame, spec, lx, baseY + ly, lz);
                    boolean curFluid = (waterSlot > 0 && cur == waterSlot) || (lavaSlot > 0 && cur == lavaSlot);
                    if (ly >= 4 && curFluid) {
                        return;
                    }
                    if (ly < 4 && cur != fluidSlot && !isSolidGeodeHost(frame, spec, leafSlotMask, lx, baseY + ly, lz)) {
                        return;
                    }
                }
            }
        }

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int colOff = ((lx << 4) | lz) << 3;
                for (int ly = 0; ly < 8; ly++) {
                    int y = baseY + ly;
                    int sIdx = (y - spec.chunkMinY()) >> 4;
                    if (sIdx < 0 || sIdx >= frame.sectionCount()) continue;
                    int pos12 = ((y & 15) << 8) | (lz << 4) | lx;
                    if (grid[colOff | ly]) {
                        if (ly < 4) {
                            frame.setVoxel(sIdx, pos12, fluidSlot);
                            frame.recordPostProcessVoxel(sIdx, pos12);
                        } else {
                            frame.setVoxel(sIdx, pos12, 0);
                        }
                    } else if (barrierSlot > 0) {
                        boolean rim = (lx < 15 && grid[colOff + 128 + ly])
                                || (lx > 0 && grid[colOff - 128 + ly])
                                || (lz < 15 && grid[colOff + 8 + ly])
                                || (lz > 0 && grid[colOff - 8 + ly])
                                || (ly < 7 && grid[colOff + ly + 1])
                                || (ly > 0 && grid[colOff + ly - 1]);
                        if (rim && (ly < 4 || (rng.nextInt(2) != 0 && isSolidGeodeHost(frame, spec, leafSlotMask, lx, y, lz)))) {
                            frame.setVoxel(sIdx, pos12, barrierSlot);
                        }
                    }
                }
            }
        }
    }

    private static void stampSnowAndFreezeChunk(
            LcaCapabilityChunkFrame frame,
            int[] quartBiomes,
            int[] topSurfaceY,
            boolean[] leafSlotMask,
            DimensionCapabilitySpec spec,
            long[] snowyBiomeMask,
            LiftedFeatureNode node,
            int minBoundY,
            int maxBoundY,
            DecorScratch scratch
    ) {
        if (snowyBiomeMask == null || snowyBiomeMask.length == 0) {
            return;
        }
        int snowSlot = node.intA();
        int iceSlot = node.intB();
        int snowyGrassSlot = node.intC();
        int grassSlot = node.intD();
        int waterSlot = spec.defaultFluidSlot();
        int[] baseTempPermille = node.auxSlotsA();
        long[] frozenTempModMask = node.slotMaskA();
        long[] hasPrecipMask = node.slotMaskB();
        int quartsY = Math.max(1, spec.chunkSections() << 2);
        int chunkBaseQx = scratch.voronoiChunkX << 2;
        int chunkBaseQy = spec.chunkMinY() >> 2;
        int chunkBaseQz = scratch.voronoiChunkZ << 2;
        int chunkBx = scratch.voronoiChunkX << 4;
        int chunkBz = scratch.voronoiChunkZ << 4;
        int[] halo6x6 = scratch.ensureSurfaceQuartHalo(quartsY, quartBiomes);

        for (int lz = 0; lz < 16; lz++) {
            int bz = chunkBz + lz;
            for (int lx = 0; lx < 16; lx++) {
                int freeY = sampleColumnHeightmapFreeY(
                        frame, spec, topSurfaceY, leafSlotMask, lx, lz, HM_MOTION_BLOCKING, minBoundY, maxBoundY);
                if (freeY <= minBoundY || freeY > maxBoundY) {
                    continue;
                }
                int bx = chunkBx + lx;
                int ord = resolveVoronoiZoomedBiomeOrdinal(
                        scratch.voronoiZoomSeed,
                        bx,
                        freeY,
                        bz,
                        chunkBaseQx,
                        chunkBaseQy,
                        chunkBaseQz,
                        quartsY,
                        halo6x6,
                        scratch.activeBiomeSampler);
                if (!bitMaskContains(snowyBiomeMask, ord)) {
                    continue;
                }
                int belowY = freeY - 1;
                int below = getVoxelAt(frame, spec, lx, belowY, lz);
                float baseTemp = (baseTempPermille != null && ord >= 0 && ord < baseTempPermille.length)
                        ? baseTempPermille[ord] * 0.001f
                        : 0.0f;
                if (bitMaskContains(frozenTempModMask, ord)) {
                    double frozenPatch = sampleDecoratorSmoothNoise2D(0x3C6EF372FE94F82AL, bx * 0.05, bz * 0.05);
                    baseTemp = frozenPatch > 0.45 ? 0.2f : 0.0f;
                }
                float tempJitter = (float) sampleDecoratorSmoothNoise2D(0x243F6A8885A308D3L, bx * 0.125, bz * 0.125) * 8.0f;
                float adjTemp = freeY > 80
                        ? baseTemp - (tempJitter + freeY - 80.0f) * 0.05f / 40.0f
                        : baseTemp;
                if (adjTemp >= 0.15f) {
                    continue;
                }
                if (iceSlot > 0 && waterSlot > 0 && below == waterSlot) {
                    int sIdx = (belowY - spec.chunkMinY()) >> 4;
                    if (sIdx >= 0 && sIdx < frame.sectionCount()) {
                        frame.setVoxel(sIdx, ((belowY & 15) << 8) | (lz << 4) | lx, iceSlot);
                    }
                } else if ((hasPrecipMask == null || hasPrecipMask.length == 0 || bitMaskContains(hasPrecipMask, ord))
                        && snowSlot > 0
                        && below > 0
                        && below != waterSlot
                        && below != spec.lavaFluidSlot()) {
                    setVoxelIfAir(frame, spec, lx, freeY, lz, snowSlot, false);
                    if (snowyGrassSlot > 0 && grassSlot > 0 && below == grassSlot) {
                        int sIdx = (belowY - spec.chunkMinY()) >> 4;
                        if (sIdx >= 0 && sIdx < frame.sectionCount()) {
                            frame.setVoxel(sIdx, ((belowY & 15) << 8) | (lz << 4) | lx, snowyGrassSlot);
                        }
                    }
                }
            }
        }
    }

    /**
     * Applies Y-bounded {@link WorldgenKernelSwapper#computeBeardContribution} deltas in place onto
     * the {@code (cellsX+1) x (cellsZ+1) x (cellsY+1)} corner density buffer ({@code ~2 us} per chunk).
     */
    public static void applyBeardifierCornersInPlace(
            float[] corners,
            int chunkX,
            int chunkZ,
            int noiseMinY,
            int cellW,
            int cellH,
            int cellsX,
            int cellsY,
            int cellsZ,
            int[] beardPiecesI32
    ) {
        if (corners == null || beardPiecesI32 == null || beardPiecesI32.length < 8) {
            return;
        }
        int pieceCount = beardPiecesI32.length >>> 3;
        int minBeardY = Integer.MAX_VALUE;
        int maxBeardY = Integer.MIN_VALUE;
        int[] effectivePieces = beardPiecesI32;
        for (int i = 0; i < pieceCount; i++) {
            int p = i << 3;
            int w6 = beardPiecesI32[p + 6];
            int tAdj = w6 & 255;
            if (tAdj == WorldgenKernelSwapper.BEARD_NONE) {
                continue;
            }
            if (tAdj == WorldgenKernelSwapper.BEARD_THIN) {
                if (effectivePieces == beardPiecesI32) {
                    effectivePieces = beardPiecesI32.clone();
                }
                effectivePieces[p + 1] = beardPiecesI32[p + 1] - 1;
            }
            int gDelta = (w6 << 16) >> 24;
            minBeardY = Math.min(minBeardY, effectivePieces[p + 1] + gDelta - 12);
            maxBeardY = Math.max(maxBeardY, effectivePieces[p + 4] + 12);
        }
        if (minBeardY > maxBeardY) {
            return;
        }
        int gyLo = Math.max(0, (minBeardY - noiseMinY) / Math.max(1, cellH));
        int gyHi = Math.min(cellsY, (maxBeardY - noiseMinY + cellH - 1) / Math.max(1, cellH));
        if (gyLo > gyHi) {
            return;
        }
        int chunkBaseBx = chunkX << 4;
        int chunkBaseBz = chunkZ << 4;
        int strideZ = cellsY + 1;
        int strideX = (cellsZ + 1) * strideZ;
        for (int gx = 0; gx <= cellsX; gx++) {
            int bx = chunkBaseBx + gx * cellW;
            for (int gz = 0; gz <= cellsZ; gz++) {
                int bz = chunkBaseBz + gz * cellW;
                int colOff = gx * strideX + gz * strideZ;
                for (int gy = gyLo; gy <= gyHi; gy++) {
                    int wy = noiseMinY + gy * cellH;
                    float delta = WorldgenKernelSwapper.computeBeardContribution(
                            bx, wy, bz, effectivePieces, 0, pieceCount);
                    if (delta != 0.0f) {
                        corners[colOff + gy] += delta;
                    }
                }
            }
        }
    }

    public static void applyBeardifierCornersInPlaceFp64(
            double[] cornersFp64,
            int chunkX,
            int chunkZ,
            int noiseMinY,
            int cellW,
            int cellH,
            int cellsX,
            int cellsY,
            int cellsZ,
            int[] beardPiecesI32
    ) {
        if (cornersFp64 == null || beardPiecesI32 == null || beardPiecesI32.length < 8) {
            return;
        }
        int pieceCount = beardPiecesI32.length >>> 3;
        int minBeardY = Integer.MAX_VALUE;
        int maxBeardY = Integer.MIN_VALUE;
        int[] effectivePieces = beardPiecesI32;
        for (int i = 0; i < pieceCount; i++) {
            int p = i << 3;
            int w6 = beardPiecesI32[p + 6];
            int tAdj = w6 & 255;
            if (tAdj == WorldgenKernelSwapper.BEARD_NONE) {
                continue;
            }
            if (tAdj == WorldgenKernelSwapper.BEARD_THIN) {
                if (effectivePieces == beardPiecesI32) {
                    effectivePieces = beardPiecesI32.clone();
                }
                effectivePieces[p + 1] = beardPiecesI32[p + 1] - 1;
            }
            int gDelta = (w6 << 16) >> 24;
            minBeardY = Math.min(minBeardY, effectivePieces[p + 1] + gDelta - 12);
            maxBeardY = Math.max(maxBeardY, effectivePieces[p + 4] + 12);
        }
        if (minBeardY > maxBeardY) {
            return;
        }
        int gyLo = Math.max(0, (minBeardY - noiseMinY) / Math.max(1, cellH));
        int gyHi = Math.min(cellsY, (maxBeardY - noiseMinY + cellH - 1) / Math.max(1, cellH));
        if (gyLo > gyHi) {
            return;
        }
        int chunkBaseBx = chunkX << 4;
        int chunkBaseBz = chunkZ << 4;
        int strideZ = cellsY + 1;
        int strideX = (cellsZ + 1) * strideZ;
        for (int gx = 0; gx <= cellsX; gx++) {
            int bx = chunkBaseBx + gx * cellW;
            for (int gz = 0; gz <= cellsZ; gz++) {
                int bz = chunkBaseBz + gz * cellW;
                int colOff = gx * strideX + gz * strideZ;
                for (int gy = gyLo; gy <= gyHi; gy++) {
                    int wy = noiseMinY + gy * cellH;
                    double delta = WorldgenKernelSwapper.computeBeardContribution(
                            bx, wy, bz, effectivePieces, 0, pieceCount);
                    if (delta != 0.0) {
                        cornersFp64[colOff + gy] += delta;
                    }
                }
            }
        }
    }

    private static int mirrorChunkCoord(int c) {
        if (c < 0) {
            int m = -1 - c;
            return m <= 15 ? m : (m & 15);
        }
        if (c > 15) {
            int m = 31 - c;
            return m >= 0 ? m : (c & 15);
        }
        return c;
    }

    private static double sampleDecoratorSmoothNoise2D(long seed, double sx, double sz) {
        double o1 = sampleSmoothLattice2D(seed, sx, sz);
        double o2 = sampleSmoothLattice2D(seed ^ 0x9E3779B97F4A7C15L, sx * 2.17 + 17.31, sz * 2.17 - 11.73);
        return Math.max(-1.0, Math.min(1.0, o1 * 0.85 + o2 * 0.55));
    }

    private static double sampleSmoothLattice2D(long seed, double x, double z) {
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        double fx = x - ix;
        double fz = z - iz;
        double ux = fx * fx * (3.0 - 2.0 * fx);
        double uz = fz * fz * (3.0 - 2.0 * fz);
        double c00 = ((mixColumnSeed(seed, ix, iz) & 0xFFFF) * (1.0 / 32767.5)) - 1.0;
        double c10 = ((mixColumnSeed(seed, ix + 1, iz) & 0xFFFF) * (1.0 / 32767.5)) - 1.0;
        double c01 = ((mixColumnSeed(seed, ix, iz + 1) & 0xFFFF) * (1.0 / 32767.5)) - 1.0;
        double c11 = ((mixColumnSeed(seed, ix + 1, iz + 1) & 0xFFFF) * (1.0 / 32767.5)) - 1.0;
        double nx0 = c00 + (c10 - c00) * ux;
        double nx1 = c01 + (c11 - c01) * ux;
        return nx0 + (nx1 - nx0) * uz;
    }

    /**
     * Deterministic 2-octave ({@code 64x64 + 32x32}) integer 2D bilinear {@code surfaceDepth}
     * matching {@code OpenClBackendEmitter#compute_smooth_surface_depth_i32} bit-for-bit.
     */
    public static int computeSmoothSurfaceDepth(long worldSeed, int bx, int bz, int colHash) {
        long s64 = worldSeed ^ 0x243F6A8885A308D3L;
        int gx6 = bx >> 6, gz6 = bz >> 6, fx6 = bx & 63, fz6 = bz & 63;
        int c00 = mixColumnSeed(s64, gx6, gz6) & 0xFFF;
        int c10 = mixColumnSeed(s64, gx6 + 1, gz6) & 0xFFF;
        int c01 = mixColumnSeed(s64, gx6, gz6 + 1) & 0xFFF;
        int c11 = mixColumnSeed(s64, gx6 + 1, gz6 + 1) & 0xFFF;
        int nx0 = c00 * (64 - fx6) + c10 * fx6;
        int nx1 = c01 * (64 - fx6) + c11 * fx6;
        int b64 = (nx0 * (64 - fz6) + nx1 * fz6) >> 12;

        long s32 = worldSeed ^ 0x6A09E667F3BCC908L;
        int gx5 = bx >> 5, gz5 = bz >> 5, fx5 = bx & 31, fz5 = bz & 31;
        int d00 = mixColumnSeed(s32, gx5, gz5) & 0xFFF;
        int d10 = mixColumnSeed(s32, gx5 + 1, gz5) & 0xFFF;
        int d01 = mixColumnSeed(s32, gx5, gz5 + 1) & 0xFFF;
        int d11 = mixColumnSeed(s32, gx5 + 1, gz5 + 1) & 0xFFF;
        int mx0 = d00 * (32 - fx5) + d10 * fx5;
        int mx1 = d01 * (32 - fx5) + d11 * fx5;
        int b32 = (mx0 * (32 - fz5) + mx1 * fz5) >> 10;

        int combined = b64 + b32;
        if (combined < 220) {
            return 0;
        }
        int jitter = (colHash >>> 5) & 255;
        return 2 + (((combined + jitter) * 3) >>> 13);
    }

    private static int mixColumnSeed(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xBF58476D1CE4E5B9L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return (int) (h ^ (h >>> 31));
    }
}
