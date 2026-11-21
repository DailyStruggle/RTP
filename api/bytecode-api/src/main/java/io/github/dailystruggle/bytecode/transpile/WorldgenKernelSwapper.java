package io.github.dailystruggle.bytecode.transpile;

import io.github.dailystruggle.bytecode.PipelineCoverageGraphScanner.CoverageProvenanceKind;
import io.github.dailystruggle.bytecode.PipelineCoverageGraphScanner.ProvenanceBinding;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.BinaryOperator;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptExpr;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptFunction;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptLut;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptModule;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptParam;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptStmt;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptType;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.MathIntrinsicKind;
import io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.EmitOptions;
import io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.FloatingPointPrecision;
import io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.TranspiledArtifact;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Runtime world-generation bytecode pull-and-swap compiler that extracts live generator
 * {@code .class} bytecode and fuses vanilla {@code DensityFunction} router DAGs into batched
 * OpenCL 3D section kernels.
 *
 * <p>Eliminates per-voxel polymorphic {@code DensityFunction} dispatch and heap allocations by:
 * <ol>
 *   <li>Pulling live {@code .class} bytecode at runtime from a {@link ClassLoader} with zero
 *       additional class initialization.</li>
 *   <li>Lifting compiled noise and math primitives into {@link BytecodeConceptIr} via
 *       {@link BytecodeSsaLifter}.</li>
 *   <li>Fusing multi-octave terrain density, vertical gradient squeezing, spline shaping, 3D cave
 *       carving, and block-state materialization into a single {@link ConceptFunction}.</li>
 *   <li>Inlining the entire call graph via {@link ConceptOptimizerPass} and emitting a batched
 *       OpenCL 3D kernel ({@link OpenClBackendEmitter}) alongside a zero-allocation slot-indexed
 *       batch execution engine outputting flat {@code int[4096]} section buffers compatible with
 *       {@code LcaSectionBacking.fromDirectVoxels}.</li>
 * </ol>
 *
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy corpus-atlas-ADR-002
 * @trace REQ-CFG-ATLAS-001
 */
public final class WorldgenKernelSwapper {

    public static final int SECTION_EDGE = 16;
    public static final int SECTION_VOXEL_COUNT = SECTION_EDGE * SECTION_EDGE * SECTION_EDGE;
    public static final String GPU_EXACT_ARITHMETIC_PROPERTY = "leafgen.gpuExactArithmetic";

    private static final Logger LOGGER = Logger.getLogger(WorldgenKernelSwapper.class.getName());
    private static final Map<String, SwappedWorldgenKernel> KERNEL_CACHE = new ConcurrentHashMap<>();

    private WorldgenKernelSwapper() {
    }

    /**
     * Explicit worldgen numerical and structural fidelity policy across CPU and OpenCL backends.
     */
    public enum WorldgenFidelityPolicy {
        /**
         * Full-octave {@code FP64} router evaluation, unpruned trilinear {@code 4x8x4} cell
         * interpolation without octant sign skipping where interior cells can cross {@code 0.0},
         * exact {@code NoiseBasedAquifer} pressure/fluid evaluation, and exact {@code SurfaceRules}
         * condition evaluation.
         */
        VANILLA_EXACT,

        /**
         * High-throughput Macro-13 {@code FP32} evaluation, {@code 4x4x4} octant sign culling,
         * cell-level aquifer snapshots, and {@code 01:H_3} Hilbert seam placement.
         */
        VANILLA_LIKE;

        public boolean isExact() {
            return this == VANILLA_EXACT;
        }
    }

    /**
     * GPU arithmetic strategy for {@link WorldgenFidelityPolicy#VANILLA_EXACT} OpenCL kernels.
     */
    public enum GpuExactArithmeticMode {
        /**
         * Native IEEE-754 {@code FP64} ({@code cl_khr_fp64}) across all octaves and trilinear cells.
         */
        NATIVE_FP64,

        /**
         * Generalized {@code INT64} Q32.32 modulo-{@code 2^24}/{@code 256} lattice reduction +
         * {@code FP32} quintic fade/gradient and trilinear evaluation (zero {@code FP64}
         * instructions on GPU while retaining full octave count and disabling heuristic culling).
         */
        INT64_Q32_FP32,

        /**
         * Consumer-GPU exact hybrid policy: executes {@link #INT64_Q32_FP32} on the OpenCL device
         * (zero {@code FP64} instructions) and refines only the narrow {@code |density| <= eps}
         * boundary sign-band in IEEE-754 {@code FP64} to recover {@code 100.00%} voxel parity.
         */
        HYBRID_SIGNBAND;

        public boolean usesNativeFp64() {
            return this == NATIVE_FP64;
        }

        public boolean refinesBoundarySignBand() {
            return this == HYBRID_SIGNBAND;
        }

        /**
         * Parses {@code -Dleafgen.gpuExactArithmetic=auto|int64_fp32|hybrid_signband|native_fp64},
         * returning {@link Optional#empty()} when {@code auto} (or unset) so callers select the
         * calibrated device mode from empirical throughput and parity measurements.
         */
        public static Optional<GpuExactArithmeticMode> parseConfiguredMode(String rawValue) {
            if (rawValue == null) {
                return Optional.empty();
            }
            String norm = rawValue.trim().toLowerCase(Locale.ROOT);
            if (norm.isEmpty() || "auto".equals(norm)) {
                return Optional.empty();
            }
            return switch (norm) {
                case "int64_fp32", "int64_q32_fp32" -> Optional.of(INT64_Q32_FP32);
                case "hybrid_signband", "hybrid_sign_band", "hybrid" -> Optional.of(HYBRID_SIGNBAND);
                case "native_fp64", "fp64" -> Optional.of(NATIVE_FP64);
                default -> throw new IllegalArgumentException(
                        "Unsupported " + GPU_EXACT_ARITHMETIC_PROPERTY + " value '" + rawValue
                                + "'; expected auto|int64_fp32|hybrid_signband|native_fp64");
            };
        }

        /**
         * Identifies consumer GPUs whose hardware {@code FP64} ALU rate is throttled ({@code 1/32}
         * to {@code 1/64} of {@code FP32}, such as GeForce RTX/GTX, Radeon RX, and Intel Arc/UHD)
         * versus full-rate datacenter {@code FP64} accelerators.
         */
        public static boolean isConsumerLimitedFp64Device(String deviceName, String vendor) {
            String combined = ((deviceName == null ? "" : deviceName)
                            + " "
                            + (vendor == null ? "" : vendor))
                    .toLowerCase(Locale.ROOT);
            if (combined.contains("a100")
                    || combined.contains("h100")
                    || combined.contains("h200")
                    || combined.contains("b200")
                    || combined.contains("v100")
                    || combined.contains("p100")
                    || combined.contains("mi100")
                    || combined.contains("mi210")
                    || combined.contains("mi250")
                    || combined.contains("mi300")
                    || combined.contains("instinct")
                    || combined.contains("data center gpu max")) {
                return false;
            }
            return combined.contains("geforce")
                    || combined.contains("rtx")
                    || combined.contains("gtx")
                    || combined.contains("radeon")
                    || combined.contains("arc")
                    || combined.contains("iris")
                    || combined.contains("uhd")
                    || combined.contains("nvidia")
                    || combined.contains("amd")
                    || combined.contains("intel");
        }
    }

    /**
     * Pure-primitive descriptor for an auxiliary {@code NormalNoise} grid evaluated alongside a
     * primary {@code BlendedNoise} corner batch on OpenCL.
     */
    public record LiftedNormalGridKernelSpec(
            double minInc,
            double maxExc,
            double outVal,
            double xzScale,
            double yScale,
            double addBias,
            double mulScale,
            double valueFactor,
            int firstLen,
            int firstMask,
            double firstInFactor,
            double firstValFactor,
            double[] firstAmp,
            byte[] firstPerm,
            double[] firstXo,
            double[] firstYo,
            double[] firstZo,
            int secondLen,
            int secondMask,
            double secondInFactor,
            double secondValFactor,
            double[] secondAmp,
            byte[] secondPerm,
            double[] secondXo,
            double[] secondYo,
            double[] secondZo) {
        public LiftedNormalGridKernelSpec {
            firstAmp = firstAmp == null ? new double[0] : firstAmp.clone();
            firstPerm = firstPerm == null ? new byte[0] : firstPerm.clone();
            firstXo = firstXo == null ? new double[0] : firstXo.clone();
            firstYo = firstYo == null ? new double[0] : firstYo.clone();
            firstZo = firstZo == null ? new double[0] : firstZo.clone();
            secondAmp = secondAmp == null ? new double[0] : secondAmp.clone();
            secondPerm = secondPerm == null ? new byte[0] : secondPerm.clone();
            secondXo = secondXo == null ? new double[0] : secondXo.clone();
            secondYo = secondYo == null ? new double[0] : secondYo.clone();
            secondZo = secondZo == null ? new double[0] : secondZo.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof LiftedNormalGridKernelSpec other)) {
                return false;
            }
            return Double.compare(this.minInc, other.minInc) == 0
                    && Double.compare(this.maxExc, other.maxExc) == 0
                    && Double.compare(this.outVal, other.outVal) == 0
                    && Double.compare(this.xzScale, other.xzScale) == 0
                    && Double.compare(this.yScale, other.yScale) == 0
                    && Double.compare(this.addBias, other.addBias) == 0
                    && Double.compare(this.mulScale, other.mulScale) == 0
                    && Double.compare(this.valueFactor, other.valueFactor) == 0
                    && this.firstLen == other.firstLen
                    && this.firstMask == other.firstMask
                    && Double.compare(this.firstInFactor, other.firstInFactor) == 0
                    && Double.compare(this.firstValFactor, other.firstValFactor) == 0
                    && java.util.Arrays.equals(this.firstAmp, other.firstAmp)
                    && java.util.Arrays.equals(this.firstPerm, other.firstPerm)
                    && java.util.Arrays.equals(this.firstXo, other.firstXo)
                    && java.util.Arrays.equals(this.firstYo, other.firstYo)
                    && java.util.Arrays.equals(this.firstZo, other.firstZo)
                    && this.secondLen == other.secondLen
                    && this.secondMask == other.secondMask
                    && Double.compare(this.secondInFactor, other.secondInFactor) == 0
                    && Double.compare(this.secondValFactor, other.secondValFactor) == 0
                    && java.util.Arrays.equals(this.secondAmp, other.secondAmp)
                    && java.util.Arrays.equals(this.secondPerm, other.secondPerm)
                    && java.util.Arrays.equals(this.secondXo, other.secondXo)
                    && java.util.Arrays.equals(this.secondYo, other.secondYo)
                    && java.util.Arrays.equals(this.secondZo, other.secondZo);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + Double.hashCode(this.minInc);
            result = 31 * result + Double.hashCode(this.maxExc);
            result = 31 * result + Double.hashCode(this.outVal);
            result = 31 * result + Double.hashCode(this.xzScale);
            result = 31 * result + Double.hashCode(this.yScale);
            result = 31 * result + Double.hashCode(this.addBias);
            result = 31 * result + Double.hashCode(this.mulScale);
            result = 31 * result + Double.hashCode(this.valueFactor);
            result = 31 * result + this.firstLen;
            result = 31 * result + this.firstMask;
            result = 31 * result + Double.hashCode(this.firstInFactor);
            result = 31 * result + Double.hashCode(this.firstValFactor);
            result = 31 * result + java.util.Arrays.hashCode(this.firstAmp);
            result = 31 * result + java.util.Arrays.hashCode(this.firstPerm);
            result = 31 * result + java.util.Arrays.hashCode(this.firstXo);
            result = 31 * result + java.util.Arrays.hashCode(this.firstYo);
            result = 31 * result + java.util.Arrays.hashCode(this.firstZo);
            result = 31 * result + this.secondLen;
            result = 31 * result + this.secondMask;
            result = 31 * result + Double.hashCode(this.secondInFactor);
            result = 31 * result + Double.hashCode(this.secondValFactor);
            result = 31 * result + java.util.Arrays.hashCode(this.secondAmp);
            result = 31 * result + java.util.Arrays.hashCode(this.secondPerm);
            result = 31 * result + java.util.Arrays.hashCode(this.secondXo);
            result = 31 * result + java.util.Arrays.hashCode(this.secondYo);
            result = 31 * result + java.util.Arrays.hashCode(this.secondZo);
            return result;
        }

        @Override
        public String toString() {
            return "LiftedNormalGridKernelSpec[minInc=" + this.minInc
                    + ", maxExc=" + this.maxExc
                    + ", outVal=" + this.outVal
                    + ", xzScale=" + this.xzScale
                    + ", yScale=" + this.yScale
                    + ", addBias=" + this.addBias
                    + ", mulScale=" + this.mulScale
                    + ", valueFactor=" + this.valueFactor
                    + ", firstLen=" + this.firstLen
                    + ", firstMask=" + this.firstMask
                    + ", firstInFactor=" + this.firstInFactor
                    + ", firstValFactor=" + this.firstValFactor
                    + ", firstAmp=" + java.util.Arrays.toString(this.firstAmp)
                    + ", firstPerm=" + java.util.Arrays.toString(this.firstPerm)
                    + ", firstXo=" + java.util.Arrays.toString(this.firstXo)
                    + ", firstYo=" + java.util.Arrays.toString(this.firstYo)
                    + ", firstZo=" + java.util.Arrays.toString(this.firstZo)
                    + ", secondLen=" + this.secondLen
                    + ", secondMask=" + this.secondMask
                    + ", secondInFactor=" + this.secondInFactor
                    + ", secondValFactor=" + this.secondValFactor
                    + ", secondAmp=" + java.util.Arrays.toString(this.secondAmp)
                    + ", secondPerm=" + java.util.Arrays.toString(this.secondPerm)
                    + ", secondXo=" + java.util.Arrays.toString(this.secondXo)
                    + ", secondYo=" + java.util.Arrays.toString(this.secondYo)
                    + ", secondZo=" + java.util.Arrays.toString(this.secondZo)
                    + "]";
        }
    }

    /**
     * Pure-primitive descriptor for a lifted 40-octave {@code BlendedNoise} kernel lowered through
     * {@link OpenClBackendEmitter} for both {@link WorldgenFidelityPolicy#VANILLA_EXACT} and
     * {@link WorldgenFidelityPolicy#VANILLA_LIKE} without reflective constructor/field access.
     */
    public record LiftedBlendedNoiseKernelSpec(
            double xzMultiplier,
            double yMultiplier,
            double xzFactor,
            double yFactor,
            double smearScaleMultiplier,
            int mainActiveMask,
            byte[] mainPerm,
            double[] mainXo,
            double[] mainYo,
            double[] mainZo,
            int minActiveMask,
            byte[] minPerm,
            double[] minXo,
            double[] minYo,
            double[] minZo,
            int maxActiveMask,
            byte[] maxPerm,
            double[] maxXo,
            double[] maxYo,
            double[] maxZo,
            List<LiftedNormalGridKernelSpec> extraGrids) {
        public static final int MACRO13_MAIN_START = 5;
        public static final int MACRO13_LIMIT_START = 11;

        public LiftedBlendedNoiseKernelSpec {
            mainPerm = Objects.requireNonNull(mainPerm, "mainPerm").clone();
            mainXo = Objects.requireNonNull(mainXo, "mainXo").clone();
            mainYo = Objects.requireNonNull(mainYo, "mainYo").clone();
            mainZo = Objects.requireNonNull(mainZo, "mainZo").clone();
            minPerm = Objects.requireNonNull(minPerm, "minPerm").clone();
            minXo = Objects.requireNonNull(minXo, "minXo").clone();
            minYo = Objects.requireNonNull(minYo, "minYo").clone();
            minZo = Objects.requireNonNull(minZo, "minZo").clone();
            maxPerm = Objects.requireNonNull(maxPerm, "maxPerm").clone();
            maxXo = Objects.requireNonNull(maxXo, "maxXo").clone();
            maxYo = Objects.requireNonNull(maxYo, "maxYo").clone();
            maxZo = Objects.requireNonNull(maxZo, "maxZo").clone();
            extraGrids = extraGrids == null ? List.of() : List.copyOf(extraGrids);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof LiftedBlendedNoiseKernelSpec other)) {
                return false;
            }
            return Double.compare(this.xzMultiplier, other.xzMultiplier) == 0
                    && Double.compare(this.yMultiplier, other.yMultiplier) == 0
                    && Double.compare(this.xzFactor, other.xzFactor) == 0
                    && Double.compare(this.yFactor, other.yFactor) == 0
                    && Double.compare(this.smearScaleMultiplier, other.smearScaleMultiplier) == 0
                    && this.mainActiveMask == other.mainActiveMask
                    && java.util.Arrays.equals(this.mainPerm, other.mainPerm)
                    && java.util.Arrays.equals(this.mainXo, other.mainXo)
                    && java.util.Arrays.equals(this.mainYo, other.mainYo)
                    && java.util.Arrays.equals(this.mainZo, other.mainZo)
                    && this.minActiveMask == other.minActiveMask
                    && java.util.Arrays.equals(this.minPerm, other.minPerm)
                    && java.util.Arrays.equals(this.minXo, other.minXo)
                    && java.util.Arrays.equals(this.minYo, other.minYo)
                    && java.util.Arrays.equals(this.minZo, other.minZo)
                    && this.maxActiveMask == other.maxActiveMask
                    && java.util.Arrays.equals(this.maxPerm, other.maxPerm)
                    && java.util.Arrays.equals(this.maxXo, other.maxXo)
                    && java.util.Arrays.equals(this.maxYo, other.maxYo)
                    && java.util.Arrays.equals(this.maxZo, other.maxZo)
                    && java.util.Objects.equals(this.extraGrids, other.extraGrids);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + Double.hashCode(this.xzMultiplier);
            result = 31 * result + Double.hashCode(this.yMultiplier);
            result = 31 * result + Double.hashCode(this.xzFactor);
            result = 31 * result + Double.hashCode(this.yFactor);
            result = 31 * result + Double.hashCode(this.smearScaleMultiplier);
            result = 31 * result + this.mainActiveMask;
            result = 31 * result + java.util.Arrays.hashCode(this.mainPerm);
            result = 31 * result + java.util.Arrays.hashCode(this.mainXo);
            result = 31 * result + java.util.Arrays.hashCode(this.mainYo);
            result = 31 * result + java.util.Arrays.hashCode(this.mainZo);
            result = 31 * result + this.minActiveMask;
            result = 31 * result + java.util.Arrays.hashCode(this.minPerm);
            result = 31 * result + java.util.Arrays.hashCode(this.minXo);
            result = 31 * result + java.util.Arrays.hashCode(this.minYo);
            result = 31 * result + java.util.Arrays.hashCode(this.minZo);
            result = 31 * result + this.maxActiveMask;
            result = 31 * result + java.util.Arrays.hashCode(this.maxPerm);
            result = 31 * result + java.util.Arrays.hashCode(this.maxXo);
            result = 31 * result + java.util.Arrays.hashCode(this.maxYo);
            result = 31 * result + java.util.Arrays.hashCode(this.maxZo);
            result = 31 * result + java.util.Objects.hashCode(this.extraGrids);
            return result;
        }

        @Override
        public String toString() {
            return "LiftedBlendedNoiseKernelSpec[xzMultiplier=" + this.xzMultiplier
                    + ", yMultiplier=" + this.yMultiplier
                    + ", xzFactor=" + this.xzFactor
                    + ", yFactor=" + this.yFactor
                    + ", smearScaleMultiplier=" + this.smearScaleMultiplier
                    + ", mainActiveMask=" + this.mainActiveMask
                    + ", mainPerm=" + java.util.Arrays.toString(this.mainPerm)
                    + ", mainXo=" + java.util.Arrays.toString(this.mainXo)
                    + ", mainYo=" + java.util.Arrays.toString(this.mainYo)
                    + ", mainZo=" + java.util.Arrays.toString(this.mainZo)
                    + ", minActiveMask=" + this.minActiveMask
                    + ", minPerm=" + java.util.Arrays.toString(this.minPerm)
                    + ", minXo=" + java.util.Arrays.toString(this.minXo)
                    + ", minYo=" + java.util.Arrays.toString(this.minYo)
                    + ", minZo=" + java.util.Arrays.toString(this.minZo)
                    + ", maxActiveMask=" + this.maxActiveMask
                    + ", maxPerm=" + java.util.Arrays.toString(this.maxPerm)
                    + ", maxXo=" + java.util.Arrays.toString(this.maxXo)
                    + ", maxYo=" + java.util.Arrays.toString(this.maxYo)
                    + ", maxZo=" + java.util.Arrays.toString(this.maxZo)
                    + ", extraGrids=" + this.extraGrids
                    + "]";
        }
    }

    /**
     * Block-state palette IDs assigned by the fused worldgen voxel kernel.
     */
    public record VoxelPaletteSpec(
            int airStateId,
            int stoneStateId,
            int deepslateStateId,
            int bedrockStateId,
            int waterStateId,
            int minY,
            int deepslateTransitionY,
            int seaLevelY,
            double caveCarveThreshold
    ) {
        public static VoxelPaletteSpec vanillaOverworldDefaults() {
            return new VoxelPaletteSpec(0, 1, 2, 7, 9, -64, 0, 63, 0.58);
        }
    }

    /**
     * Section coordinate in 16x16x16 section units.
     */
    public record SectionCoord(int chunkX, int sectionY, int chunkZ) {
        public int originBlockX() {
            return chunkX << 4;
        }

        public int originBlockY() {
            return sectionY << 4;
        }

        public int originBlockZ() {
            return chunkZ << 4;
        }
    }

    /**
     * Composable vanilla-style {@code DensityFunction} expression DAG node lowered into
     * {@link BytecodeConceptIr} and fused with lifted {@code .class} noise methods.
     */
    public sealed interface DensityNode permits
            DensityNode.Constant,
            DensityNode.WorldCoordY,
            DensityNode.YClampedGradient,
            DensityNode.BytecodeNoise3D,
            DensityNode.BytecodeSeededHash,
            DensityNode.Fbm3D,
            DensityNode.Binary,
            DensityNode.MinMax,
            DensityNode.Clamp,
            DensityNode.MappedUnary,
            DensityNode.RangeChoice,
            DensityNode.Spline1D,
            DensityNode.LiftedModule {

        enum MappedKind {
            ABS,
            SQUARE,
            CUBE,
            HALF_NEGATIVE,
            QUARTER_NEGATIVE,
            SQUEEZE
        }

        record Constant(double value) implements DensityNode {
        }

        record WorldCoordY() implements DensityNode {
        }

        record YClampedGradient(
                int fromY,
                int toY,
                double fromValue,
                double toValue
        ) implements DensityNode {
        }

        record BytecodeNoise3D(
                String ownerClass,
                String methodName,
                double xzScale,
                double yScale,
                double amplitude,
                double offsetX,
                double offsetY,
                double offsetZ
        ) implements DensityNode {
            public BytecodeNoise3D {
                Objects.requireNonNull(ownerClass, "ownerClass");
                Objects.requireNonNull(methodName, "methodName");
            }
        }

        record BytecodeSeededHash(
                String ownerClass,
                String methodName,
                double amplitude
        ) implements DensityNode {
            public BytecodeSeededHash {
                Objects.requireNonNull(ownerClass, "ownerClass");
                Objects.requireNonNull(methodName, "methodName");
            }
        }

        record Fbm3D(
                String ownerClass,
                String methodName,
                int octaves,
                double baseScaleXz,
                double baseScaleY,
                double lacunarity,
                double persistence,
                double amplitude
        ) implements DensityNode {
            public Fbm3D {
                Objects.requireNonNull(ownerClass, "ownerClass");
                Objects.requireNonNull(methodName, "methodName");
                if (octaves < 1 || octaves > 16) {
                    throw new IllegalArgumentException("octaves out of [1, 16]: " + octaves);
                }
            }
        }

        record Binary(
                BinaryOperator op,
                DensityNode left,
                DensityNode right
        ) implements DensityNode {
            public Binary {
                Objects.requireNonNull(op, "op");
                Objects.requireNonNull(left, "left");
                Objects.requireNonNull(right, "right");
            }
        }

        record MinMax(
                boolean isMax,
                DensityNode left,
                DensityNode right
        ) implements DensityNode {
            public MinMax {
                Objects.requireNonNull(left, "left");
                Objects.requireNonNull(right, "right");
            }
        }

        record Clamp(
                DensityNode input,
                double minValue,
                double maxValue
        ) implements DensityNode {
            public Clamp {
                Objects.requireNonNull(input, "input");
            }
        }

        record MappedUnary(
                MappedKind kind,
                DensityNode input
        ) implements DensityNode {
            public MappedUnary {
                Objects.requireNonNull(kind, "kind");
                Objects.requireNonNull(input, "input");
            }
        }

        record RangeChoice(
                DensityNode input,
                double minInclusive,
                double maxExclusive,
                DensityNode whenInRange,
                DensityNode whenOutOfRange
        ) implements DensityNode {
            public RangeChoice {
                Objects.requireNonNull(input, "input");
                Objects.requireNonNull(whenInRange, "whenInRange");
                Objects.requireNonNull(whenOutOfRange, "whenOutOfRange");
            }
        }

        record Spline1D(
                DensityNode coordinate,
                double[] knots,
                double[] values
        ) implements DensityNode {
            public Spline1D {
                Objects.requireNonNull(coordinate, "coordinate");
                Objects.requireNonNull(knots, "knots");
                Objects.requireNonNull(values, "values");
                if (knots.length < 2 || knots.length != values.length) {
                    throw new IllegalArgumentException("Spline knots/values must match and have length >= 2");
                }
                knots = knots.clone();
                values = values.clone();
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (!(o instanceof Spline1D other)) {
                    return false;
                }
                return java.util.Objects.equals(this.coordinate, other.coordinate)
                        && java.util.Arrays.equals(this.knots, other.knots)
                        && java.util.Arrays.equals(this.values, other.values);
            }

            @Override
            public int hashCode() {
                int result = 1;
                result = 31 * result + java.util.Objects.hashCode(this.coordinate);
                result = 31 * result + java.util.Arrays.hashCode(this.knots);
                result = 31 * result + java.util.Arrays.hashCode(this.values);
                return result;
            }

            @Override
            public String toString() {
                return "Spline1D[coordinate=" + this.coordinate
                        + ", knots=" + java.util.Arrays.toString(this.knots)
                        + ", values=" + java.util.Arrays.toString(this.values)
                        + "]";
            }
        }

        record LiftedModule(
                ConceptModule module,
                String uniqueEntryName
        ) implements DensityNode {
            public LiftedModule {
                Objects.requireNonNull(module, "module");
                Objects.requireNonNull(uniqueEntryName, "uniqueEntryName");
            }
        }

        static DensityNode add(DensityNode a, DensityNode b) {
            return new Binary(BinaryOperator.ADD, a, b);
        }

        static DensityNode sub(DensityNode a, DensityNode b) {
            return new Binary(BinaryOperator.SUB, a, b);
        }

        static DensityNode mul(DensityNode a, DensityNode b) {
            return new Binary(BinaryOperator.MUL, a, b);
        }

        static DensityNode min(DensityNode a, DensityNode b) {
            return new MinMax(false, a, b);
        }

        static DensityNode max(DensityNode a, DensityNode b) {
            return new MinMax(true, a, b);
        }

        static DensityNode clamp(DensityNode input, double min, double max) {
            return new Clamp(input, min, max);
        }

        static DensityNode rangeChoice(
                DensityNode input,
                double minInclusive,
                double maxExclusive,
                DensityNode whenInRange,
                DensityNode whenOutOfRange) {
            return new RangeChoice(input, minInclusive, maxExclusive, whenInRange, whenOutOfRange);
        }
    }

    /**
     * Complete specification of a runtime-pulled worldgen density router pipeline.
     */
    public record WorldgenPipelineSpec(
            String kernelId,
            DensityNode finalTerrainDensity,
            DensityNode caveCheeseDensity,
            VoxelPaletteSpec palette,
            EmitOptions emitOptions
    ) {
        public WorldgenPipelineSpec {
            Objects.requireNonNull(kernelId, "kernelId");
            Objects.requireNonNull(finalTerrainDensity, "finalTerrainDensity");
            Objects.requireNonNull(caveCheeseDensity, "caveCheeseDensity");
            Objects.requireNonNull(palette, "palette");
            Objects.requireNonNull(emitOptions, "emitOptions");
        }
    }

    /**
     * Pulls raw {@code .class} bytecode at runtime for the given loaded classes via their defining
     * {@link ClassLoader} without triggering reflection or re-initialization.
     */
    public static List<byte[]> pullClassBytes(Class<?>... classes) {
        if (classes == null || classes.length == 0) {
            return List.of();
        }
        List<byte[]> result = new ArrayList<>(classes.length);
        for (Class<?> clazz : classes) {
            if (clazz == null) {
                continue;
            }
            String resourcePath = clazz.getName().replace('.', '/') + ".class";
            ClassLoader loader = clazz.getClassLoader();
            try (InputStream in = loader != null
                    ? loader.getResourceAsStream(resourcePath)
                    : ClassLoader.getSystemResourceAsStream(resourcePath)) {
                if (in == null) {
                    throw new IllegalStateException("Unable to pull .class resource for " + clazz.getName());
                }
                result.add(in.readAllBytes());
            } catch (IOException e) {
                throw new IllegalStateException("Failed reading .class bytes for " + clazz.getName(), e);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Pulls raw {@code .class} bytecode by binary or internal class names from {@code loader}.
     */
    public static List<byte[]> pullClassBytes(ClassLoader loader, Collection<String> classNames) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(classNames, "classNames");
        List<byte[]> result = new ArrayList<>(classNames.size());
        for (String name : classNames) {
            String resourcePath = name.replace('.', '/') + ".class";
            try (InputStream in = loader.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    throw new IllegalStateException("Unable to locate bytecode resource: " + resourcePath);
                }
                result.add(in.readAllBytes());
            } catch (IOException e) {
                throw new IllegalStateException("Failed reading bytecode resource: " + resourcePath, e);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Extracts live bytecode for {@code runtimeClasses}, fuses the {@link WorldgenPipelineSpec}
     * density router with the lifted bytecode functions, runs whole-program inlining and constant
     * folding, and emits a hot-swappable OpenCL 3D section kernel.
     */
    public static SwappedWorldgenKernel compileAndSwap(
            WorldgenPipelineSpec spec,
            Class<?>... runtimeClasses) {
        return compileAndSwap(spec, pullClassBytes(runtimeClasses));
    }

    /**
     * Compiles and caches a {@link SwappedWorldgenKernel} from live runtime worldgen instances,
     * snapshotting their frozen primitive fields, nested records, and array lookup tables.
     */
    public static SwappedWorldgenKernel compileAndSwapFromInstances(
            WorldgenPipelineSpec spec,
            Object... runtimeInstances) {
        Objects.requireNonNull(spec, "spec");
        BytecodeSsaLifter.InstanceSnapshot snapshot = BytecodeSsaLifter.captureInstanceSnapshot(runtimeInstances);
        return KERNEL_CACHE.computeIfAbsent(
                spec.kernelId(),
                id -> buildSwappedKernel(spec, snapshot.discoveredClassBytes(), snapshot));
    }

    /**
     * Compiles and caches a {@link SwappedWorldgenKernel} from raw {@code .class} byte arrays and a
     * {@link WorldgenPipelineSpec}.
     */
    public static SwappedWorldgenKernel compileAndSwap(
            WorldgenPipelineSpec spec,
            Collection<byte[]> classBytesCollection) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(classBytesCollection, "classBytesCollection");
        return KERNEL_CACHE.computeIfAbsent(
                spec.kernelId(),
                id -> buildSwappedKernel(spec, classBytesCollection, BytecodeSsaLifter.InstanceSnapshot.empty()));
    }

    /**
     * Compiles and caches a {@link SwappedWorldgenKernel} whose OpenCL artifact uses the supplied
     * {@code customOpenClSource}, {@code precision}, and {@code kernel3DName} without reflective
     * constructor or field access.
     */
    public static SwappedWorldgenKernel compileCapabilityKernel(
            WorldgenPipelineSpec spec,
            FloatingPointPrecision precision,
            String customOpenClSource,
            String kernel3DName,
            Class<?>... runtimeClasses) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(precision, "precision");
        Objects.requireNonNull(customOpenClSource, "customOpenClSource");
        String cacheKey = spec.kernelId()
                + "|cap|"
                + precision.name()
                + "|"
                + (kernel3DName == null ? "" : kernel3DName)
                + "|"
                + Integer.toHexString(customOpenClSource.hashCode());
        return KERNEL_CACHE.computeIfAbsent(
                cacheKey,
                id -> buildSwappedKernel(
                                spec,
                                pullClassBytes(runtimeClasses),
                                BytecodeSsaLifter.InstanceSnapshot.empty())
                        .withCustomOpenClSource(spec, precision, customOpenClSource, kernel3DName));
    }

    /**
     * Synthesizes and caches a dimension-capability {@link SwappedWorldgenKernel} for the supplied
     * {@link WorldgenFidelityPolicy}.
     */
    public static SwappedWorldgenKernel compileCapabilityKernel(
            LiftedWorldgenCapabilityTables.DimensionCapabilitySpec capabilitySpec,
            WorldgenFidelityPolicy fidelityPolicy) {
        return compileCapabilityKernel(capabilitySpec, fidelityPolicy, true);
    }

    /**
     * Synthesizes and caches a dimension-capability {@link SwappedWorldgenKernel} for the supplied
     * {@link WorldgenFidelityPolicy} and GPU {@code Climate.RTree} toggle.
     */
    public static SwappedWorldgenKernel compileCapabilityKernel(
            LiftedWorldgenCapabilityTables.DimensionCapabilitySpec capabilitySpec,
            WorldgenFidelityPolicy fidelityPolicy,
            boolean includeGpuRtree) {
        return compileCapabilityKernel(
                capabilitySpec,
                fidelityPolicy,
                GpuExactArithmeticMode.NATIVE_FP64,
                includeGpuRtree);
    }

    /**
     * Synthesizes and caches a dimension-capability {@link SwappedWorldgenKernel} for the supplied
     * {@link WorldgenFidelityPolicy} and {@link GpuExactArithmeticMode} with GPU {@code Climate.RTree}
     * enabled.
     */
    public static SwappedWorldgenKernel compileCapabilityKernel(
            LiftedWorldgenCapabilityTables.DimensionCapabilitySpec capabilitySpec,
            WorldgenFidelityPolicy fidelityPolicy,
            GpuExactArithmeticMode gpuExactMode) {
        return compileCapabilityKernel(capabilitySpec, fidelityPolicy, gpuExactMode, true);
    }

    /**
     * Synthesizes and caches a dimension-capability {@link SwappedWorldgenKernel} for the supplied
     * {@link WorldgenFidelityPolicy}, {@link GpuExactArithmeticMode}, and GPU {@code Climate.RTree}
     * toggle.
     */
    public static SwappedWorldgenKernel compileCapabilityKernel(
            LiftedWorldgenCapabilityTables.DimensionCapabilitySpec capabilitySpec,
            WorldgenFidelityPolicy fidelityPolicy,
            GpuExactArithmeticMode gpuExactMode,
            boolean includeGpuRtree) {
        Objects.requireNonNull(capabilitySpec, "capabilitySpec");
        WorldgenFidelityPolicy effPolicy =
                fidelityPolicy == null ? WorldgenFidelityPolicy.VANILLA_LIKE : fidelityPolicy;
        GpuExactArithmeticMode effExactMode =
                gpuExactMode == null ? GpuExactArithmeticMode.NATIVE_FP64 : gpuExactMode;
        String clSource = OpenClBackendEmitter.emitDimensionCapabilityKernelSource(
                capabilitySpec, includeGpuRtree, effPolicy, effExactMode);
        boolean useNativeFp64 = effPolicy.isExact() && effExactMode.usesNativeFp64();
        FloatingPointPrecision precision = useNativeFp64
                ? FloatingPointPrecision.NATIVE_FP64
                : FloatingPointPrecision.DOWNCAST_FP32;
        EmitOptions emitOpts = useNativeFp64 ? EmitOptions.gpuFp64() : EmitOptions.gpuFp32();
        String kernelId = "leafgen_capability_"
                + effPolicy.name().toLowerCase(Locale.ROOT)
                + "_"
                + (effPolicy.isExact() ? effExactMode.name().toLowerCase(Locale.ROOT) + "_" : "")
                + Integer.toHexString(clSource.hashCode());
        WorldgenPipelineSpec spec = new WorldgenPipelineSpec(
                kernelId,
                new DensityNode.Constant(0.0),
                new DensityNode.Constant(0.0),
                VoxelPaletteSpec.vanillaOverworldDefaults(),
                emitOpts);
        return compileCapabilityKernel(
                spec,
                precision,
                clSource,
                "evaluate_dimension_capability_chunk");
    }

    /**
     * Synthesizes and caches a {@link SwappedWorldgenKernel} for a lifted 40-octave
     * {@link LiftedBlendedNoiseKernelSpec} under {@code fidelityPolicy}.
     */
    public static SwappedWorldgenKernel compileBlendedNoiseKernel(
            LiftedBlendedNoiseKernelSpec blendedSpec,
            WorldgenFidelityPolicy fidelityPolicy) {
        return compileBlendedNoiseKernel(
                blendedSpec, fidelityPolicy, GpuExactArithmeticMode.NATIVE_FP64, null);
    }

    /**
     * Synthesizes and caches a {@link SwappedWorldgenKernel} for a lifted 40-octave
     * {@link LiftedBlendedNoiseKernelSpec} under {@code fidelityPolicy} and {@code gpuExactMode}.
     */
    public static SwappedWorldgenKernel compileBlendedNoiseKernel(
            LiftedBlendedNoiseKernelSpec blendedSpec,
            WorldgenFidelityPolicy fidelityPolicy,
            GpuExactArithmeticMode gpuExactMode) {
        return compileBlendedNoiseKernel(blendedSpec, fidelityPolicy, gpuExactMode, null);
    }

    /**
     * Pure-Java {@code BlendedNoise} evaluator lifted via {@link BytecodeSsaLifter#liftInstanceGraph}
     * into {@link BytecodeConceptIr} and emitted through {@link OpenClBackendEmitter#emit}.
     */
    private static final class BlendedNoiseIrEvaluator {
        private static final double[] GRAD_X = {
            1.0, -1.0, 1.0, -1.0, 1.0, -1.0, 1.0, -1.0,
            0.0, 0.0, 0.0, 0.0, 1.0, 0.0, -1.0, 0.0
        };
        private static final double[] GRAD_Y = {
            1.0, 1.0, -1.0, -1.0, 0.0, 0.0, 0.0, 0.0,
            1.0, -1.0, 1.0, -1.0, 1.0, -1.0, 1.0, -1.0
        };
        private static final double[] GRAD_Z = {
            0.0, 0.0, 0.0, 0.0, 1.0, 1.0, -1.0, -1.0,
            1.0, 1.0, -1.0, -1.0, 0.0, 1.0, 0.0, -1.0
        };
        private static final float[] GRAD_XF = {
            1.0f, -1.0f, 1.0f, -1.0f, 1.0f, -1.0f, 1.0f, -1.0f,
            0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, -1.0f, 0.0f
        };
        private static final float[] GRAD_YF = {
            1.0f, 1.0f, -1.0f, -1.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            1.0f, -1.0f, 1.0f, -1.0f, 1.0f, -1.0f, 1.0f, -1.0f
        };
        private static final float[] GRAD_ZF = {
            0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, -1.0f, -1.0f,
            1.0f, 1.0f, -1.0f, -1.0f, 0.0f, 1.0f, 0.0f, -1.0f
        };

        private final double xzMultiplier;
        private final double yMultiplier;
        private final double xzFactor;
        private final double yFactor;
        private final double smearScaleMultiplier;
        private final int mainActiveMask;
        private final int[] mainPerm;
        private final double[] mainXo;
        private final double[] mainYo;
        private final double[] mainZo;
        private final int minActiveMask;
        private final int[] minPerm;
        private final double[] minXo;
        private final double[] minYo;
        private final double[] minZo;
        private final int maxActiveMask;
        private final int[] maxPerm;
        private final double[] maxXo;
        private final double[] maxYo;
        private final double[] maxZo;
        private final long[] mainStepXZ;
        private final long[] mainStepY;
        private final long[] mainOffX;
        private final long[] mainOffY;
        private final long[] mainOffZ;
        private final long[] limitStepXZ;
        private final long[] limitStepY;
        private final long[] minOffX;
        private final long[] minOffY;
        private final long[] minOffZ;
        private final long[] maxOffX;
        private final long[] maxOffY;
        private final long[] maxOffZ;

        BlendedNoiseIrEvaluator(LiftedBlendedNoiseKernelSpec spec) {
            this.xzMultiplier = spec.xzMultiplier();
            this.yMultiplier = spec.yMultiplier();
            this.xzFactor = spec.xzFactor();
            this.yFactor = spec.yFactor();
            this.smearScaleMultiplier = spec.smearScaleMultiplier();
            this.mainActiveMask = spec.mainActiveMask();
            this.mainPerm = toUnsignedIntPerm(spec.mainPerm());
            this.mainXo = spec.mainXo();
            this.mainYo = spec.mainYo();
            this.mainZo = spec.mainZo();
            this.minActiveMask = spec.minActiveMask();
            this.minPerm = toUnsignedIntPerm(spec.minPerm());
            this.minXo = spec.minXo();
            this.minYo = spec.minYo();
            this.minZo = spec.minZo();
            this.maxActiveMask = spec.maxActiveMask();
            this.maxPerm = toUnsignedIntPerm(spec.maxPerm());
            this.maxXo = spec.maxXo();
            this.maxYo = spec.maxYo();
            this.maxZo = spec.maxZo();
            this.mainStepXZ = new long[8];
            this.mainStepY = new long[8];
            double s = 1.0;
            for (int i = 0; i < 8; i++) {
                this.mainStepXZ[i] = Math.round((this.xzMultiplier / this.xzFactor) * s * 4294967296.0);
                this.mainStepY[i] = Math.round((this.yMultiplier / this.yFactor) * s * 4294967296.0);
                s *= 0.5;
            }
            this.mainOffX = toScaledLong(this.mainXo);
            this.mainOffY = toScaledLong(this.mainYo);
            this.mainOffZ = toScaledLong(this.mainZo);
            this.limitStepXZ = new long[16];
            this.limitStepY = new long[16];
            s = 1.0;
            for (int i = 0; i < 16; i++) {
                this.limitStepXZ[i] = Math.round(this.xzMultiplier * s * 4294967296.0);
                this.limitStepY[i] = Math.round(this.yMultiplier * s * 4294967296.0);
                s *= 0.5;
            }
            this.minOffX = toScaledLong(this.minXo);
            this.minOffY = toScaledLong(this.minYo);
            this.minOffZ = toScaledLong(this.minZo);
            this.maxOffX = toScaledLong(this.maxXo);
            this.maxOffY = toScaledLong(this.maxYo);
            this.maxOffZ = toScaledLong(this.maxZo);
        }

        private static long[] toScaledLong(double[] arr) {
            long[] out = new long[arr.length];
            for (int i = 0; i < arr.length; i++) {
                out[i] = Math.round(arr[i] * 4294967296.0);
            }
            return out;
        }

        private static int[] toUnsignedIntPerm(byte[] perm) {
            int[] out = new int[perm.length];
            for (int i = 0; i < perm.length; i++) {
                out[i] = perm[i] & 0xFF;
            }
            return out;
        }

        private static double wrap(double v) {
            return v - Math.floor(v / 3.3554432E7 + 0.5) * 3.3554432E7;
        }

        private static double gradDot(int hash, double x, double y, double z) {
            int idx = hash & 15;
            return GRAD_X[idx] * x + GRAD_Y[idx] * y + GRAD_Z[idx] * z;
        }

        private static double sampleImproved(
                int[] p,
                int off,
                double xo,
                double yo,
                double zo,
                double x,
                double y,
                double z,
                double yScale,
                double yMax) {
            double d = x + xo;
            double e = y + yo;
            double f = z + zo;
            int i = (int) Math.floor(d);
            int j = (int) Math.floor(e);
            int k = (int) Math.floor(f);
            double g = d - (double) i;
            double h = e - (double) j;
            double l = f - (double) k;
            double m = 0.0;
            if (yScale != 0.0) {
                double n = (yMax >= 0.0 && yMax < h) ? yMax : h;
                m = Math.floor(n / yScale + 1.0e-7) * yScale;
            }
            int p0 = p[off + (i & 255)];
            int p1 = p[off + ((i + 1) & 255)];
            int p00 = p[off + ((p0 + j) & 255)];
            int p01 = p[off + ((p0 + j + 1) & 255)];
            int p10 = p[off + ((p1 + j) & 255)];
            int p11 = p[off + ((p1 + j + 1) & 255)];
            double ly = h - m;
            double c000 = gradDot(p[off + ((p00 + k) & 255)], g, ly, l);
            double c100 = gradDot(p[off + ((p10 + k) & 255)], g - 1.0, ly, l);
            double c010 = gradDot(p[off + ((p01 + k) & 255)], g, ly - 1.0, l);
            double c110 = gradDot(p[off + ((p11 + k) & 255)], g - 1.0, ly - 1.0, l);
            double c001 = gradDot(p[off + ((p00 + k + 1) & 255)], g, ly, l - 1.0);
            double c101 = gradDot(p[off + ((p10 + k + 1) & 255)], g - 1.0, ly, l - 1.0);
            double c011 = gradDot(p[off + ((p01 + k + 1) & 255)], g, ly - 1.0, l - 1.0);
            double c111 = gradDot(p[off + ((p11 + k + 1) & 255)], g - 1.0, ly - 1.0, l - 1.0);
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

        public double eval_blended_point_d(int blockX, int blockY, int blockZ) {
            double hx = (double) blockX * this.xzMultiplier;
            double hy = (double) blockY * this.yMultiplier;
            double hz = (double) blockZ * this.xzMultiplier;
            double mx = hx / this.xzFactor;
            double my = hy / this.yFactor;
            double mz = hz / this.xzFactor;
            double smear = this.yMultiplier * this.smearScaleMultiplier;
            double mainSmear = smear / this.yFactor;

            double mainVal = 0.0;
            double scale = 1.0;
            for (int i = 0; i < 8; i++) {
                if ((this.mainActiveMask & (1 << i)) != 0) {
                    mainVal += sampleImproved(
                            this.mainPerm,
                            i << 8,
                            this.mainXo[i],
                            this.mainYo[i],
                            this.mainZo[i],
                            wrap(mx * scale),
                            wrap(my * scale),
                            wrap(mz * scale),
                            mainSmear * scale,
                            my * scale) / scale;
                }
                scale *= 0.5;
            }
            double alpha = (mainVal / 10.0 + 1.0) * 0.5;
            double minVal = 0.0;
            double maxVal = 0.0;
            scale = 1.0;
            for (int i = 0; i < 16; i++) {
                double wx = wrap(hx * scale);
                double wy = wrap(hy * scale);
                double wz = wrap(hz * scale);
                double ws = smear * scale;
                double yMax = hy * scale;
                if (alpha < 1.0 && (this.minActiveMask & (1 << i)) != 0) {
                    minVal += sampleImproved(
                            this.minPerm,
                            i << 8,
                            this.minXo[i],
                            this.minYo[i],
                            this.minZo[i],
                            wx,
                            wy,
                            wz,
                            ws,
                            yMax) / scale;
                }
                if (alpha > 0.0 && (this.maxActiveMask & (1 << i)) != 0) {
                    maxVal += sampleImproved(
                            this.maxPerm,
                            i << 8,
                            this.maxXo[i],
                            this.maxYo[i],
                            this.maxZo[i],
                            wx,
                            wy,
                            wz,
                            ws,
                            yMax) / scale;
                }
                scale *= 0.5;
            }
            double lo = minVal / 512.0;
            double hi = maxVal / 512.0;
            double blended = alpha < 0.0 ? lo : (alpha > 1.0 ? hi : (lo + alpha * (hi - lo)));
            return blended / 128.0;
        }

        private static int floorF(float v) {
            int i = (int) v;
            return v < (float) i ? i - 1 : i;
        }

        private static float gradDotF(int hash, float x, float y, float z) {
            int idx = hash & 15;
            return GRAD_XF[idx] * x + GRAD_YF[idx] * y + GRAD_ZF[idx] * z;
        }

        private static float sampleImprovedF(
                int[] p,
                int off,
                float xo,
                float yo,
                float zo,
                float x,
                float y,
                float z,
                float yScale,
                float invYScale,
                float yMax,
                float yMaxDivSmear) {
            float d = x + xo;
            float e = y + yo;
            float f = z + zo;
            int i = floorF(d);
            int j = floorF(e);
            int k = floorF(f);
            float g = d - (float) i;
            float h = e - (float) j;
            float l = f - (float) k;
            float m = 0.0f;
            if (yScale != 0.0f) {
                float nDiv = (yMax >= 0.0f && yMax < h) ? yMaxDivSmear : (h * invYScale);
                m = (float) floorF(nDiv + 1.0e-7f) * yScale;
            }
            int p0 = p[off + (i & 255)];
            int p1 = p[off + ((i + 1) & 255)];
            int p00 = p[off + ((p0 + j) & 255)];
            int p01 = p[off + ((p0 + j + 1) & 255)];
            int p10 = p[off + ((p1 + j) & 255)];
            int p11 = p[off + ((p1 + j + 1) & 255)];
            float ly = h - m;
            float c000 = gradDotF(p[off + ((p00 + k) & 255)], g, ly, l);
            float c100 = gradDotF(p[off + ((p10 + k) & 255)], g - 1.0f, ly, l);
            float c010 = gradDotF(p[off + ((p01 + k) & 255)], g, ly - 1.0f, l);
            float c110 = gradDotF(p[off + ((p11 + k) & 255)], g - 1.0f, ly - 1.0f, l);
            float c001 = gradDotF(p[off + ((p00 + k + 1) & 255)], g, ly, l - 1.0f);
            float c101 = gradDotF(p[off + ((p10 + k + 1) & 255)], g - 1.0f, ly, l - 1.0f);
            float c011 = gradDotF(p[off + ((p01 + k + 1) & 255)], g, ly - 1.0f, l - 1.0f);
            float c111 = gradDotF(p[off + ((p11 + k + 1) & 255)], g - 1.0f, ly - 1.0f, l - 1.0f);
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

        private static float sampleImprovedQ32F(
                int[] p,
                int off,
                long fx,
                long fy,
                long fz,
                float yScale,
                float invYScale,
                float yMax,
                float yMaxDivSmear) {
            int i = ((int) (fx >>> 32)) & 255;
            int j = ((int) (fy >>> 32)) & 255;
            int k = ((int) (fz >>> 32)) & 255;
            float g = (float) (fx & 0xFFFFFFFFL) * 0x1.0p-32f;
            float h = (float) (fy & 0xFFFFFFFFL) * 0x1.0p-32f;
            float l = (float) (fz & 0xFFFFFFFFL) * 0x1.0p-32f;
            float m = 0.0f;
            if (yScale != 0.0f) {
                float nDiv = (yMax >= 0.0f && yMax < h) ? yMaxDivSmear : (h * invYScale);
                m = (float) floorF(nDiv + 1.0e-7f) * yScale;
            }
            int p0 = p[off + (i & 255)];
            int p1 = p[off + ((i + 1) & 255)];
            int p00 = p[off + ((p0 + j) & 255)];
            int p01 = p[off + ((p0 + j + 1) & 255)];
            int p10 = p[off + ((p1 + j) & 255)];
            int p11 = p[off + ((p1 + j + 1) & 255)];
            float ly = h - m;
            float c000 = gradDotF(p[off + ((p00 + k) & 255)], g, ly, l);
            float c100 = gradDotF(p[off + ((p10 + k) & 255)], g - 1.0f, ly, l);
            float c010 = gradDotF(p[off + ((p01 + k) & 255)], g, ly - 1.0f, l);
            float c110 = gradDotF(p[off + ((p11 + k) & 255)], g - 1.0f, ly - 1.0f, l);
            float c001 = gradDotF(p[off + ((p00 + k + 1) & 255)], g, ly, l - 1.0f);
            float c101 = gradDotF(p[off + ((p10 + k + 1) & 255)], g - 1.0f, ly, l - 1.0f);
            float c011 = gradDotF(p[off + ((p01 + k + 1) & 255)], g, ly - 1.0f, l - 1.0f);
            float c111 = gradDotF(p[off + ((p11 + k + 1) & 255)], g - 1.0f, ly - 1.0f, l - 1.0f);
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

        public float eval_blended_point_q32_f32(int blockX, int blockY, int blockZ) {
            long lbx = (long) blockX;
            long lby = (long) blockY;
            long lbz = (long) blockZ;
            float smear = (float) (this.yMultiplier * this.smearScaleMultiplier);
            float mainSmear = (float) (this.yMultiplier * this.smearScaleMultiplier / this.yFactor);
            float invMainSmear = mainSmear != 0.0f ? 1.0f / mainSmear : 0.0f;
            float invSmear = smear != 0.0f ? 1.0f / smear : 0.0f;
            float hyF = (float) blockY * (float) this.yMultiplier;
            float myF = (float) blockY * (float) (this.yMultiplier / this.yFactor);
            float yMaxDivSmear = (float) ((double) blockY / this.smearScaleMultiplier);

            float mainVal = 0.0f;
            float fScale = 1.0f;
            float invFScale = 1.0f;
            for (int i = 0; i < 8; i++) {
                if ((this.mainActiveMask & (1 << i)) != 0) {
                    long fx = lbx * this.mainStepXZ[i] + this.mainOffX[i];
                    long fy = lby * this.mainStepY[i] + this.mainOffY[i];
                    long fz = lbz * this.mainStepXZ[i] + this.mainOffZ[i];
                    mainVal += sampleImprovedQ32F(
                            this.mainPerm,
                            i << 8,
                            fx,
                            fy,
                            fz,
                            mainSmear * fScale,
                            invMainSmear * invFScale,
                            myF * fScale,
                            yMaxDivSmear) * invFScale;
                }
                fScale *= 0.5f;
                invFScale *= 2.0f;
            }
            float alpha = (mainVal * 0.1f + 1.0f) * 0.5f;
            float minVal = 0.0f;
            float maxVal = 0.0f;
            fScale = 1.0f;
            invFScale = 1.0f;
            for (int i = 0; i < 16; i++) {
                float ws = smear * fScale;
                float invWs = invSmear * invFScale;
                float yMax = hyF * fScale;
                if (alpha < 1.0f && (this.minActiveMask & (1 << i)) != 0) {
                    long fx = lbx * this.limitStepXZ[i] + this.minOffX[i];
                    long fy = lby * this.limitStepY[i] + this.minOffY[i];
                    long fz = lbz * this.limitStepXZ[i] + this.minOffZ[i];
                    minVal += sampleImprovedQ32F(
                            this.minPerm,
                            i << 8,
                            fx,
                            fy,
                            fz,
                            ws,
                            invWs,
                            yMax,
                            yMaxDivSmear) * invFScale;
                }
                if (alpha > 0.0f && (this.maxActiveMask & (1 << i)) != 0) {
                    long fx = lbx * this.limitStepXZ[i] + this.maxOffX[i];
                    long fy = lby * this.limitStepY[i] + this.maxOffY[i];
                    long fz = lbz * this.limitStepXZ[i] + this.maxOffZ[i];
                    maxVal += sampleImprovedQ32F(
                            this.maxPerm,
                            i << 8,
                            fx,
                            fy,
                            fz,
                            ws,
                            invWs,
                            yMax,
                            yMaxDivSmear) * invFScale;
                }
                fScale *= 0.5f;
                invFScale *= 2.0f;
            }
            float clampedAlpha = alpha < 0.0f ? 0.0f : (alpha > 1.0f ? 1.0f : alpha);
            float lo = minVal * (1.0f / 512.0f);
            float hi = maxVal * (1.0f / 512.0f);
            return (lo + clampedAlpha * (hi - lo)) * (1.0f / 128.0f);
        }

        public float eval_blended_point_f32(int blockX, int blockY, int blockZ) {
            float xzMult = (float) this.xzMultiplier;
            float yMult = (float) this.yMultiplier;
            float hx = (float) blockX * xzMult;
            float hy = (float) blockY * yMult;
            float hz = (float) blockZ * xzMult;
            float invXzFac = (float) (1.0 / this.xzFactor);
            float invYFac = (float) (1.0 / this.yFactor);
            float mx = hx * invXzFac;
            float my = hy * invYFac;
            float mz = hz * invXzFac;
            float smear = (float) (this.yMultiplier * this.smearScaleMultiplier);
            float mainSmear = smear * invYFac;
            float invMainSmear = 1.0f / mainSmear;
            float invSmear = 1.0f / smear;
            float yMaxDivSmear = (float) blockY * (float) (1.0 / this.smearScaleMultiplier);

            float mainVal = 0.0f;
            float scale = 1.0f / (1 << LiftedBlendedNoiseKernelSpec.MACRO13_MAIN_START);
            float invScale = (float) (1 << LiftedBlendedNoiseKernelSpec.MACRO13_MAIN_START);
            for (int i = LiftedBlendedNoiseKernelSpec.MACRO13_MAIN_START; i < 8; i++) {
                if ((this.mainActiveMask & (1 << i)) != 0) {
                    mainVal += sampleImprovedF(
                            this.mainPerm,
                            i << 8,
                            (float) this.mainXo[i],
                            (float) this.mainYo[i],
                            (float) this.mainZo[i],
                            mx * scale,
                            my * scale,
                            mz * scale,
                            mainSmear * scale,
                            invMainSmear * invScale,
                            my * scale,
                            yMaxDivSmear) * invScale;
                }
                scale *= 0.5f;
                invScale *= 2.0f;
            }
            float alpha = (mainVal * 0.1f + 1.0f) * 0.5f;
            float minVal = 0.0f;
            float maxVal = 0.0f;
            scale = 1.0f / (1 << LiftedBlendedNoiseKernelSpec.MACRO13_LIMIT_START);
            invScale = (float) (1 << LiftedBlendedNoiseKernelSpec.MACRO13_LIMIT_START);
            for (int i = LiftedBlendedNoiseKernelSpec.MACRO13_LIMIT_START; i < 16; i++) {
                float wx = hx * scale;
                float wy = hy * scale;
                float wz = hz * scale;
                float ws = smear * scale;
                float invWs = invSmear * invScale;
                float yMax = hy * scale;
                if (alpha < 1.0f && (this.minActiveMask & (1 << i)) != 0) {
                    minVal += sampleImprovedF(
                            this.minPerm,
                            i << 8,
                            (float) this.minXo[i],
                            (float) this.minYo[i],
                            (float) this.minZo[i],
                            wx,
                            wy,
                            wz,
                            ws,
                            invWs,
                            yMax,
                            yMaxDivSmear) * invScale;
                }
                if (alpha > 0.0f && (this.maxActiveMask & (1 << i)) != 0) {
                    maxVal += sampleImprovedF(
                            this.maxPerm,
                            i << 8,
                            (float) this.maxXo[i],
                            (float) this.maxYo[i],
                            (float) this.maxZo[i],
                            wx,
                            wy,
                            wz,
                            ws,
                            invWs,
                            yMax,
                            yMaxDivSmear) * invScale;
                }
                scale *= 0.5f;
                invScale *= 2.0f;
            }
            float clampedAlpha = alpha < 0.0f ? 0.0f : (alpha > 1.0f ? 1.0f : alpha);
            float s = minVal * (1.0f / 512.0f);
            float e = maxVal * (1.0f / 512.0f);
            return (s + clampedAlpha * (e - s)) * (1.0f / 128.0f);
        }
    }

    /**
     * Synthesizes and caches a {@link SwappedWorldgenKernel} for a lifted 40-octave
     * {@link LiftedBlendedNoiseKernelSpec} under {@code fidelityPolicy} and {@code gpuExactMode}
     * via {@link BytecodeSsaLifter#liftInstanceGraph}, {@link ConceptOptimizerPass#optimize}, and
     * {@link OpenClBackendEmitter#emit}.
     */
    public static SwappedWorldgenKernel compileBlendedNoiseKernel(
            LiftedBlendedNoiseKernelSpec blendedSpec,
            WorldgenFidelityPolicy fidelityPolicy,
            GpuExactArithmeticMode gpuExactMode,
            SwappedWorldgenKernel templateOrNull,
            Class<?>... runtimeClasses) {
        Objects.requireNonNull(blendedSpec, "blendedSpec");
        WorldgenFidelityPolicy effPolicy =
                fidelityPolicy == null ? WorldgenFidelityPolicy.VANILLA_EXACT : fidelityPolicy;
        GpuExactArithmeticMode effExactMode =
                gpuExactMode == null ? GpuExactArithmeticMode.NATIVE_FP64 : gpuExactMode;
        BlendedNoiseIrEvaluator evaluator = new BlendedNoiseIrEvaluator(blendedSpec);
        boolean isExact = effPolicy.isExact();
        boolean useNativeFp64 = isExact && effExactMode.usesNativeFp64();
        String entryMethod = !isExact
                ? "eval_blended_point_f32"
                : (useNativeFp64 ? "eval_blended_point_d" : "eval_blended_point_q32_f32");
        String entryDesc = useNativeFp64 ? "(III)D" : "(III)F";

        ConceptModule lifted = BytecodeSsaLifter.liftInstanceGraph(
                evaluator,
                entryMethod,
                entryDesc,
                BytecodeSsaLifter.GraphLiftOptions.defaults());
        ConceptModule optimized = ConceptOptimizerPass.optimize(lifted);

        FloatingPointPrecision precision = useNativeFp64
                ? FloatingPointPrecision.NATIVE_FP64
                : FloatingPointPrecision.DOWNCAST_FP32;
        ConceptBackendEmitter.GpuExactArithmeticMode emitterArithMode = useNativeFp64
                ? ConceptBackendEmitter.GpuExactArithmeticMode.NATIVE_FP64
                : ConceptBackendEmitter.GpuExactArithmeticMode.DOWNCAST_FP32;
        EmitOptions emitOpts = new EmitOptions(
                precision,
                true,
                ConceptOptimizerPass.OptimizationConfig.defaults(),
                emitterArithMode,
                ConceptBackendEmitter.BatchLayout.CORNER_SLICE_5X49_PACKED);

        ConceptModule emitModule = optimized;
        if (!isExact) {
            ConceptModule lcaStages = compileLiftedEndToEndLcaStagesModule(entryMethod);
            List<ConceptFunction> mergedHelpers = new ArrayList<>(optimized.helperFunctions());
            for (ConceptFunction h : lcaStages.helperFunctions()) {
                if (mergedHelpers.stream().noneMatch(e -> e.key().equals(h.key()))) {
                    mergedHelpers.add(h);
                }
            }
            List<ConceptLut> mergedLuts = new ArrayList<>(optimized.lookupTables());
            for (ConceptLut l : lcaStages.lookupTables()) {
                if (mergedLuts.stream().noneMatch(e -> e.symbolName().equals(l.symbolName()))) {
                    mergedLuts.add(l);
                }
            }
            emitModule = new ConceptModule(
                    optimized.moduleName(),
                    mergedLuts,
                    mergedHelpers,
                    optimized.entryFunction(),
                    optimized.kernel3D(),
                    lcaStages.workgroupPipeline(),
                    optimized.unmappedReasons(),
                    optimized.liftedMethodSymbols(),
                    optimized.liftedFieldSymbols());
        }

        TranspiledArtifact clArtifact = new OpenClBackendEmitter().emit(emitModule, emitOpts);
        String clSource = clArtifact.sourceCode();
        String prefix = !isExact
                ? "leafgen_blended_noise_vlike_m13_"
                : switch (effExactMode) {
                    case NATIVE_FP64 -> "leafgen_blended_noise_f64_";
                    case INT64_Q32_FP32 -> "leafgen_blended_noise_i64f32_";
                    case HYBRID_SIGNBAND -> "leafgen_blended_noise_hybrid_";
                };
        String kernelId = prefix + Integer.toHexString(clSource.hashCode());
        WorldgenPipelineSpec spec = new WorldgenPipelineSpec(
                kernelId,
                new DensityNode.Constant(0.0),
                new DensityNode.Constant(0.0),
                VoxelPaletteSpec.vanillaOverworldDefaults(),
                emitOpts);
        if (templateOrNull != null) {
            return SwappedWorldgenKernel.createWithCustomOpenClSource(
                    templateOrNull,
                    spec,
                    precision,
                    clSource,
                    null);
        }
        return compileCapabilityKernel(
                spec,
                precision,
                clSource,
                "evaluate_lca_packed_batch3d",
                runtimeClasses);
    }

    public static void clearCache() {
        KERNEL_CACHE.clear();
    }

    /**
     * Creates a lazy, on-demand {@link ClassLoader} backed by {@code archivePath} (supporting nested
     * {@code META-INF/volmit/runtime.jar.xz} for Iris, {@code addons/} jars for Terra,
     * {@code META-INF/jars/} for Fabric JiJ, {@code META-INF/jarjar/} for NeoForge, and
     * {@code META-INF/versions/} for Paper/Folia) so {@link BytecodeSsaLifter} traces only
     * directly connected classes without scanning unrelated mod or plugin classes.
     */
    public static ClassLoader createLazyArchiveClassLoader(Path archivePath) {
        Objects.requireNonNull(archivePath, "archivePath");
        return new LazyArchiveClassLoader(archivePath, WorldgenKernelSwapper.class.getClassLoader());
    }

    private static final class LazyArchiveClassLoader extends ClassLoader {
        private final Path archivePath;
        private volatile Map<String, byte[]> nestedEntries;

        LazyArchiveClassLoader(Path archivePath, ClassLoader parent) {
            super(parent);
            this.archivePath = archivePath;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (name == null) {
                return null;
            }
            byte[] bytes = ensureEntriesLoaded().get(name);
            if (bytes != null) {
                return new ByteArrayInputStream(bytes);
            }
            return super.getResourceAsStream(name);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            String res = name.replace('.', '/') + ".class";
            byte[] bytes = ensureEntriesLoaded().get(res);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            throw new ClassNotFoundException(name);
        }

        private Map<String, byte[]> ensureEntriesLoaded() {
            Map<String, byte[]> local = nestedEntries;
            if (local != null) {
                return local;
            }
            synchronized (this) {
                if (nestedEntries != null) {
                    return nestedEntries;
                }
                Map<String, byte[]> map = new ConcurrentHashMap<>();
                try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(archivePath))) {
                    indexZipStream(zis, map);
                } catch (IOException ignored) {
                }
                nestedEntries = map;
                return map;
            }
        }

        private void indexZipStream(ZipInputStream zis, Map<String, byte[]> out) throws IOException {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    out.putIfAbsent(name, zis.readAllBytes());
                } else if (name.endsWith(".jar")
                        && (name.startsWith("addons/")
                                || name.startsWith("META-INF/jars/")
                                || name.startsWith("META-INF/jarjar/")
                                || name.startsWith("META-INF/versions/"))) {
                    byte[] innerJar = zis.readAllBytes();
                    try (ZipInputStream innerZis = new ZipInputStream(new ByteArrayInputStream(innerJar))) {
                        indexZipStream(innerZis, out);
                    }
                } else if ("META-INF/volmit/runtime.jar.xz".equals(name)) {
                    byte[] xzBytes = zis.readAllBytes();
                    byte[] unpackedJar = tryDecodeXzWithOuterClasses(xzBytes);
                    if (unpackedJar != null) {
                        try (ZipInputStream innerZis = new ZipInputStream(new ByteArrayInputStream(unpackedJar))) {
                            indexZipStream(innerZis, out);
                        }
                    }
                }
            }
        }

        private byte[] tryDecodeXzWithOuterClasses(byte[] xzBytes) {
            try (URLClassLoader outerLoader = new URLClassLoader(
                    new URL[]{archivePath.toUri().toURL()}, getParent())) {
                Class<?> xzStreamCls = outerLoader.loadClass(
                        "art.arcane.iris.Iris.xz.decoder.XZInputStream");
                try (InputStream decoded = (InputStream) xzStreamCls
                        .getConstructor(InputStream.class)
                        .newInstance(new ByteArrayInputStream(xzBytes))) {
                    return decoded.readAllBytes();
                }
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static SwappedWorldgenKernel buildSwappedKernel(
            WorldgenPipelineSpec spec,
            Collection<byte[]> classBytesCollection,
            BytecodeSsaLifter.InstanceSnapshot snapshot) {
        ConceptFunction fusedEntry = synthesizeFusedVoxelFunction(spec);
        List<ConceptLut> allLuts = new ArrayList<>();
        Map<String, ConceptFunction> liftedHelpers = new LinkedHashMap<>();
        Map<String, ProvenanceBinding> liftedProvenance = new LinkedHashMap<>();

        if (!classBytesCollection.isEmpty()
                || (snapshot != null
                        && (!snapshot.discoveredClassBytes().isEmpty()
                                || !snapshot.classLoaders().isEmpty()))) {
            List<ReferencedMethod> refs = new ArrayList<>();
            collectReferencedMethods(spec.finalTerrainDensity(), refs);
            collectReferencedMethods(spec.caveCheeseDensity(), refs);

            for (ReferencedMethod ref : refs) {
                ConceptModule lifted = BytecodeSsaLifter.liftModule(
                        classBytesCollection,
                        ref.ownerClass(),
                        ref.methodName(),
                        ref.expectedDescriptor(),
                        snapshot);
                ConceptModule optLifted = ConceptOptimizerPass.optimize(lifted);
                liftedProvenance.putAll(BytecodeSsaLifter.collectModuleProvenanceBindings(optLifted, snapshot));
                for (ConceptLut lut : optLifted.lookupTables()) {
                    if (allLuts.stream().noneMatch(existing -> existing.symbolName().equals(lut.symbolName()))) {
                        allLuts.add(lut);
                    }
                }
                ConceptFunction liftedEntry = optLifted.entryFunction();
                liftedHelpers.put(liftedEntry.key(), liftedEntry);
                String requestedKey = ref.ownerClass() + "#" + ref.methodName() + ref.expectedDescriptor();
                if (!liftedEntry.key().equals(requestedKey)) {
                    ConceptFunction aliasFn = coerceOrAliasFunction(
                            liftedEntry,
                            ref.ownerClass(),
                            ref.methodName(),
                            ref.expectedDescriptor(),
                            ConceptType.F64);
                    liftedHelpers.put(aliasFn.key(), aliasFn);
                }
                for (ConceptFunction helper : optLifted.helperFunctions()) {
                    liftedHelpers.put(helper.key(), helper);
                }
            }
        }

        List<DensityNode.LiftedModule> embeddedModules = new ArrayList<>();
        collectLiftedModules(spec.finalTerrainDensity(), embeddedModules);
        collectLiftedModules(spec.caveCheeseDensity(), embeddedModules);
        for (DensityNode.LiftedModule lm : embeddedModules) {
            ConceptModule mod = lm.module();
            liftedProvenance.putAll(BytecodeSsaLifter.collectModuleProvenanceBindings(mod, null));
            for (ConceptLut lut : mod.lookupTables()) {
                if (allLuts.stream().noneMatch(existing -> existing.symbolName().equals(lut.symbolName()))) {
                    allLuts.add(lut);
                }
            }
            for (ConceptFunction helper : mod.helperFunctions()) {
                liftedHelpers.putIfAbsent(helper.key(), helper);
            }
            ConceptFunction entry = mod.entryFunction();
            ConceptFunction uniqueEntry = new ConceptFunction(
                    entry.ownerClass(),
                    lm.uniqueEntryName(),
                    entry.descriptor(),
                    entry.parameters(),
                    entry.returnType(),
                    entry.localTypes(),
                    entry.body(),
                    entry.pureCompute());
            liftedHelpers.putIfAbsent(uniqueEntry.key(), uniqueEntry);
        }

        ConceptModule unoptimized = new ConceptModule(
                spec.kernelId(),
                allLuts,
                new ArrayList<>(liftedHelpers.values()),
                fusedEntry,
                null);

        ConceptModule optimized = ConceptOptimizerPass.optimize(unoptimized);
        ConceptModule jvmOptimized = ConceptOptimizerPass.optimize(
                unoptimized,
                new ConceptOptimizerPass.OptimizationConfig(false, true, true, false));
        TranspiledArtifact openClArtifact = ConceptBackendEmitter.resolveBackend("opencl")
                .emit(optimized, spec.emitOptions());
        TranspiledArtifact rustArtifact = ConceptBackendEmitter.resolveBackend("rust")
                .emit(optimized, spec.emitOptions());

        FastSlotEvaluator fastEvaluator = FastSlotEvaluator.compile(optimized, jvmOptimized);
        String irDigest = BytecodeConceptIr.canonicalModuleHash(optimized);
        return SwappedWorldgenKernel.createWithProvenance(
                spec,
                optimized,
                openClArtifact,
                rustArtifact,
                fastEvaluator,
                irDigest,
                liftedProvenance,
                Set.of());
    }

    private record ReferencedMethod(String ownerClass, String methodName, String expectedDescriptor) {
    }

    private static ConceptFunction coerceOrAliasFunction(
            ConceptFunction target,
            String aliasOwner,
            String aliasName,
            String aliasDescriptor,
            ConceptType aliasReturnType) {
        if ("(DDD)D".equals(aliasDescriptor) && target.parameters().size() != 3) {
            List<ConceptParam> aliasParams = List.of(
                    new ConceptParam("ax", ConceptType.F64, 0),
                    new ConceptParam("ay", ConceptType.F64, 2),
                    new ConceptParam("az", ConceptType.F64, 4));
            List<ConceptExpr> callArgs = new ArrayList<>(target.parameters().size());
            if (target.parameters().size() == 4) {
                ConceptType seedType = target.parameters().get(0).type();
                callArgs.add(seedType == ConceptType.I64
                        ? new ConceptExpr.LongConst(0L)
                        : new ConceptExpr.IntConst(0));
                callArgs.add(new ConceptExpr.Var("ax", ConceptType.F64));
                callArgs.add(new ConceptExpr.Var("ay", ConceptType.F64));
                callArgs.add(new ConceptExpr.Var("az", ConceptType.F64));
            }
            if (!callArgs.isEmpty()) {
                ConceptExpr call = new ConceptExpr.Call(
                        target.ownerClass(),
                        target.name(),
                        target.descriptor(),
                        callArgs,
                        target.returnType());
                ConceptExpr retExpr = target.returnType() == aliasReturnType
                        ? call
                        : new ConceptExpr.Cast(aliasReturnType, call);
                return new ConceptFunction(
                        aliasOwner,
                        aliasName,
                        aliasDescriptor,
                        aliasParams,
                        aliasReturnType,
                        Map.of(
                                "ax", ConceptType.F64,
                                "ay", ConceptType.F64,
                                "az", ConceptType.F64),
                        List.of(new ConceptStmt.Return(retExpr)),
                        target.pureCompute());
            }
        }
        if ("(DDD)D".equals(aliasDescriptor)
                && target.parameters().size() == 3
                && target.parameters().get(0).type() == ConceptType.I32) {
            List<ConceptParam> aliasParams = List.of(
                    new ConceptParam("ax", ConceptType.F64, 0),
                    new ConceptParam("ay", ConceptType.F64, 2),
                    new ConceptParam("az", ConceptType.F64, 4));
            List<ConceptExpr> callArgs = List.of(
                    new ConceptExpr.Cast(ConceptType.I32, new ConceptExpr.Var("ax", ConceptType.F64)),
                    new ConceptExpr.Cast(ConceptType.I32, new ConceptExpr.Var("ay", ConceptType.F64)),
                    new ConceptExpr.Cast(ConceptType.I32, new ConceptExpr.Var("az", ConceptType.F64)));
            ConceptExpr call = new ConceptExpr.Call(
                    target.ownerClass(),
                    target.name(),
                    target.descriptor(),
                    callArgs,
                    target.returnType());
            ConceptExpr retExpr = target.returnType() == aliasReturnType
                    ? call
                    : new ConceptExpr.Cast(aliasReturnType, call);
            return new ConceptFunction(
                    aliasOwner,
                    aliasName,
                    aliasDescriptor,
                    aliasParams,
                    aliasReturnType,
                    Map.of(
                            "ax", ConceptType.F64,
                            "ay", ConceptType.F64,
                            "az", ConceptType.F64),
                    List.of(new ConceptStmt.Return(retExpr)),
                    target.pureCompute());
        }
        List<ConceptStmt> coercedBody = coerceReturnStmts(target.body(), target.returnType(), aliasReturnType);
        return new ConceptFunction(
                aliasOwner,
                aliasName,
                aliasDescriptor,
                target.parameters(),
                aliasReturnType,
                target.localTypes(),
                coercedBody,
                target.pureCompute());
    }

    private static List<ConceptStmt> coerceReturnStmts(
            List<ConceptStmt> stmts,
            ConceptType actualType,
            ConceptType expectedType) {
        if (actualType == expectedType) {
            return stmts;
        }
        List<ConceptStmt> out = new ArrayList<>(stmts.size());
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.Return r && r.value() != null) {
                out.add(new ConceptStmt.Return(new ConceptExpr.Cast(expectedType, r.value())));
            } else if (s instanceof ConceptStmt.If i) {
                out.add(new ConceptStmt.If(
                        i.condition(),
                        coerceReturnStmts(i.thenBody(), actualType, expectedType),
                        coerceReturnStmts(i.elseBody(), actualType, expectedType)));
            } else if (s instanceof ConceptStmt.While w) {
                out.add(new ConceptStmt.While(
                        w.condition(),
                        coerceReturnStmts(w.body(), actualType, expectedType)));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private static void collectReferencedMethods(DensityNode node, List<ReferencedMethod> out) {
        if (node instanceof DensityNode.BytecodeNoise3D n) {
            addRef(out, n.ownerClass().replace('.', '/'), n.methodName(), "(DDD)D");
        } else if (node instanceof DensityNode.BytecodeSeededHash h) {
            addRef(out, h.ownerClass().replace('.', '/'), h.methodName(), "(JJ)D");
        } else if (node instanceof DensityNode.Fbm3D fbm) {
            addRef(out, fbm.ownerClass().replace('.', '/'), fbm.methodName(), "(DDD)D");
        } else if (node instanceof DensityNode.Binary bin) {
            collectReferencedMethods(bin.left(), out);
            collectReferencedMethods(bin.right(), out);
        } else if (node instanceof DensityNode.MinMax mm) {
            collectReferencedMethods(mm.left(), out);
            collectReferencedMethods(mm.right(), out);
        } else if (node instanceof DensityNode.Clamp c) {
            collectReferencedMethods(c.input(), out);
        } else if (node instanceof DensityNode.MappedUnary m) {
            collectReferencedMethods(m.input(), out);
        } else if (node instanceof DensityNode.RangeChoice rc) {
            collectReferencedMethods(rc.input(), out);
            collectReferencedMethods(rc.whenInRange(), out);
            collectReferencedMethods(rc.whenOutOfRange(), out);
        } else if (node instanceof DensityNode.Spline1D s) {
            collectReferencedMethods(s.coordinate(), out);
        }
    }

    private static void collectLiftedModules(DensityNode node, List<DensityNode.LiftedModule> out) {
        if (node instanceof DensityNode.LiftedModule lm) {
            out.add(lm);
        } else if (node instanceof DensityNode.Binary bin) {
            collectLiftedModules(bin.left(), out);
            collectLiftedModules(bin.right(), out);
        } else if (node instanceof DensityNode.MinMax mm) {
            collectLiftedModules(mm.left(), out);
            collectLiftedModules(mm.right(), out);
        } else if (node instanceof DensityNode.Clamp c) {
            collectLiftedModules(c.input(), out);
        } else if (node instanceof DensityNode.MappedUnary m) {
            collectLiftedModules(m.input(), out);
        } else if (node instanceof DensityNode.RangeChoice rc) {
            collectLiftedModules(rc.input(), out);
            collectLiftedModules(rc.whenInRange(), out);
            collectLiftedModules(rc.whenOutOfRange(), out);
        } else if (node instanceof DensityNode.Spline1D s) {
            collectLiftedModules(s.coordinate(), out);
        }
    }

    private static void addRef(List<ReferencedMethod> out, String owner, String name, String expectedDescriptor) {
        ReferencedMethod candidate = new ReferencedMethod(owner, name, expectedDescriptor);
        if (!out.contains(candidate)) {
            out.add(candidate);
        }
    }

    private static ConceptFunction synthesizeFusedVoxelFunction(WorldgenPipelineSpec spec) {
        List<ConceptParam> params = List.of(
                new ConceptParam("world_x", ConceptType.I32, 0),
                new ConceptParam("world_y", ConceptType.I32, 1),
                new ConceptParam("world_z", ConceptType.I32, 2),
                new ConceptParam("world_seed", ConceptType.I64, 3));

        Map<String, ConceptType> localTypes = new LinkedHashMap<>();
        for (ConceptParam p : params) {
            localTypes.put(p.name(), p.type());
        }
        localTypes.put("wx_d", ConceptType.F64);
        localTypes.put("wy_d", ConceptType.F64);
        localTypes.put("wz_d", ConceptType.F64);
        localTypes.put("seed_offset", ConceptType.F64);
        localTypes.put("terrain_density", ConceptType.F64);
        localTypes.put("cave_density", ConceptType.F64);
        localTypes.put("voxel_state", ConceptType.I32);

        ConceptExpr varWxI = new ConceptExpr.Var("world_x", ConceptType.I32);
        ConceptExpr varWyI = new ConceptExpr.Var("world_y", ConceptType.I32);
        ConceptExpr varWzI = new ConceptExpr.Var("world_z", ConceptType.I32);
        ConceptExpr varSeed = new ConceptExpr.Var("world_seed", ConceptType.I64);

        List<ConceptStmt> stmts = new ArrayList<>();
        stmts.add(new ConceptStmt.Assign("wx_d", ConceptType.F64,
                new ConceptExpr.Cast(ConceptType.F64, varWxI)));
        stmts.add(new ConceptStmt.Assign("wy_d", ConceptType.F64,
                new ConceptExpr.Cast(ConceptType.F64, varWyI)));
        stmts.add(new ConceptStmt.Assign("wz_d", ConceptType.F64,
                new ConceptExpr.Cast(ConceptType.F64, varWzI)));

        // Deterministic seed-derived coordinate phase offset: ((double)(world_seed & 0xFFFFL)) * 0.00390625
        ConceptExpr seedLowMask = new ConceptExpr.BinaryOp(
                BinaryOperator.BIT_AND,
                varSeed,
                new ConceptExpr.LongConst(0xFFFFL),
                ConceptType.I64);
        ConceptExpr seedPhase = new ConceptExpr.BinaryOp(
                BinaryOperator.MUL,
                new ConceptExpr.Cast(ConceptType.F64, seedLowMask),
                new ConceptExpr.DoubleConst(0.00390625),
                ConceptType.F64);
        stmts.add(new ConceptStmt.Assign("seed_offset", ConceptType.F64, seedPhase));

        ConceptExpr wxExpr = new ConceptExpr.Var("wx_d", ConceptType.F64);
        ConceptExpr wyExpr = new ConceptExpr.Var("wy_d", ConceptType.F64);
        ConceptExpr wzExpr = new ConceptExpr.Var("wz_d", ConceptType.F64);
        ConceptExpr seedOffExpr = new ConceptExpr.Var("seed_offset", ConceptType.F64);
        int[] dagTempId = {0};
        java.util.IdentityHashMap<DensityNode, ConceptExpr> dagMemo = new java.util.IdentityHashMap<>();

        ConceptExpr terrainExpr = lowerDensityNode(
                spec.finalTerrainDensity(),
                wxExpr,
                wyExpr,
                wzExpr,
                seedOffExpr,
                varSeed,
                stmts,
                localTypes,
                dagTempId,
                dagMemo);
        ConceptExpr caveExpr = lowerDensityNode(
                spec.caveCheeseDensity(),
                wxExpr,
                wyExpr,
                wzExpr,
                seedOffExpr,
                varSeed,
                stmts,
                localTypes,
                dagTempId,
                dagMemo);

        stmts.add(new ConceptStmt.Assign("terrain_density", ConceptType.F64, terrainExpr));
        stmts.add(new ConceptStmt.Assign("cave_density", ConceptType.F64, caveExpr));

        VoxelPaletteSpec pal = spec.palette();
        ConceptExpr isBedrock = new ConceptExpr.BinaryOp(
                BinaryOperator.LE, varWyI, new ConceptExpr.IntConst(pal.minY()), ConceptType.BOOL);
        ConceptExpr isSolid = new ConceptExpr.BinaryOp(
                BinaryOperator.GT,
                new ConceptExpr.Var("terrain_density", ConceptType.F64),
                new ConceptExpr.DoubleConst(0.0),
                ConceptType.BOOL);
        ConceptExpr isCarvedCave = new ConceptExpr.BinaryOp(
                BinaryOperator.GT,
                new ConceptExpr.Var("cave_density", ConceptType.F64),
                new ConceptExpr.DoubleConst(pal.caveCarveThreshold()),
                ConceptType.BOOL);
        ConceptExpr isDeepslate = new ConceptExpr.BinaryOp(
                BinaryOperator.LT,
                varWyI,
                new ConceptExpr.IntConst(pal.deepslateTransitionY()),
                ConceptType.BOOL);
        ConceptExpr isBelowSea = new ConceptExpr.BinaryOp(
                BinaryOperator.LE,
                varWyI,
                new ConceptExpr.IntConst(pal.seaLevelY()),
                ConceptType.BOOL);

        ConceptExpr rockState = new ConceptExpr.Ternary(
                isDeepslate,
                new ConceptExpr.IntConst(pal.deepslateStateId()),
                new ConceptExpr.IntConst(pal.stoneStateId()),
                ConceptType.I32);
        ConceptExpr solidOrCave = new ConceptExpr.Ternary(
                isCarvedCave,
                new ConceptExpr.IntConst(pal.airStateId()),
                rockState,
                ConceptType.I32);
        ConceptExpr nonSolidFluidOrAir = new ConceptExpr.Ternary(
                isBelowSea,
                new ConceptExpr.IntConst(pal.waterStateId()),
                new ConceptExpr.IntConst(pal.airStateId()),
                ConceptType.I32);
        ConceptExpr bodyState = new ConceptExpr.Ternary(
                isSolid,
                solidOrCave,
                nonSolidFluidOrAir,
                ConceptType.I32);
        ConceptExpr finalState = new ConceptExpr.Ternary(
                isBedrock,
                new ConceptExpr.IntConst(pal.bedrockStateId()),
                bodyState,
                ConceptType.I32);

        stmts.add(new ConceptStmt.Assign("voxel_state", ConceptType.I32, finalState));
        stmts.add(new ConceptStmt.Return(new ConceptExpr.Var("voxel_state", ConceptType.I32)));

        return new ConceptFunction(
                "io/github/dailystruggle/bytecode/transpile/WorldgenKernelSwapper",
                "evaluate_worldgen_voxel",
                "(IIIJ)I",
                params,
                ConceptType.I32,
                localTypes,
                stmts,
                true);
    }

    private static ConceptExpr bindDagTemp(
            ConceptExpr expr,
            List<ConceptStmt> stmts,
            Map<String, ConceptType> localTypes,
            int[] dagTempId) {
        if (expr instanceof ConceptExpr.Var
                || expr instanceof ConceptExpr.DoubleConst
                || expr instanceof ConceptExpr.IntConst
                || expr instanceof ConceptExpr.LongConst) {
            return expr;
        }
        String name = "dag_t" + (++dagTempId[0]);
        localTypes.put(name, expr.type());
        stmts.add(new ConceptStmt.Assign(name, expr.type(), expr));
        return new ConceptExpr.Var(name, expr.type());
    }

    private static ConceptExpr lowerDensityNode(
            DensityNode node,
            ConceptExpr wx,
            ConceptExpr wy,
            ConceptExpr wz,
            ConceptExpr seedOffset,
            ConceptExpr rawSeed,
            List<ConceptStmt> stmts,
            Map<String, ConceptType> localTypes,
            int[] dagTempId,
            java.util.IdentityHashMap<DensityNode, ConceptExpr> dagMemo) {
        if (node instanceof DensityNode.Constant c) {
            return new ConceptExpr.DoubleConst(c.value());
        }
        if (node instanceof DensityNode.WorldCoordY) {
            return wy;
        }
        ConceptExpr cached = dagMemo.get(node);
        if (cached != null) {
            return cached;
        }
        ConceptExpr computed;
        if (node instanceof DensityNode.YClampedGradient grad) {
            computed = bindDagTemp(lowerYClampedGradient(grad, wy), stmts, localTypes, dagTempId);
        } else if (node instanceof DensityNode.BytecodeNoise3D n) {
            computed = bindDagTemp(lowerBytecodeNoise3D(n, wx, wy, wz, seedOffset), stmts, localTypes, dagTempId);
        } else if (node instanceof DensityNode.BytecodeSeededHash h) {
            computed = bindDagTemp(lowerBytecodeSeededHash(h, wx, wz, rawSeed), stmts, localTypes, dagTempId);
        } else if (node instanceof DensityNode.Fbm3D fbm) {
            computed = bindDagTemp(
                    lowerFbm3D(fbm, wx, wy, wz, seedOffset, stmts, localTypes, dagTempId),
                    stmts,
                    localTypes,
                    dagTempId);
        } else if (node instanceof DensityNode.Binary bin) {
            ConceptExpr l = lowerDensityNode(
                    bin.left(), wx, wy, wz, seedOffset, rawSeed, stmts, localTypes, dagTempId, dagMemo);
            ConceptExpr r = lowerDensityNode(
                    bin.right(), wx, wy, wz, seedOffset, rawSeed, stmts, localTypes, dagTempId, dagMemo);
            computed = bindDagTemp(
                    new ConceptExpr.BinaryOp(bin.op(), l, r, ConceptType.F64),
                    stmts,
                    localTypes,
                    dagTempId);
        } else if (node instanceof DensityNode.MinMax mm) {
            ConceptExpr l = lowerDensityNode(
                    mm.left(), wx, wy, wz, seedOffset, rawSeed, stmts, localTypes, dagTempId, dagMemo);
            ConceptExpr r = lowerDensityNode(
                    mm.right(), wx, wy, wz, seedOffset, rawSeed, stmts, localTypes, dagTempId, dagMemo);
            computed = bindDagTemp(
                    new ConceptExpr.MathIntrinsic(
                            mm.isMax() ? MathIntrinsicKind.MAX : MathIntrinsicKind.MIN,
                            List.of(l, r),
                            ConceptType.F64),
                    stmts,
                    localTypes,
                    dagTempId);
        } else if (node instanceof DensityNode.Clamp c) {
            ConceptExpr in = lowerDensityNode(
                    c.input(), wx, wy, wz, seedOffset, rawSeed, stmts, localTypes, dagTempId, dagMemo);
            ConceptExpr clampedLow = new ConceptExpr.MathIntrinsic(
                    MathIntrinsicKind.MAX,
                    List.of(new ConceptExpr.DoubleConst(c.minValue()), in),
                    ConceptType.F64);
            ConceptExpr clamped = new ConceptExpr.MathIntrinsic(
                    MathIntrinsicKind.MIN,
                    List.of(new ConceptExpr.DoubleConst(c.maxValue()), clampedLow),
                    ConceptType.F64);
            computed = bindDagTemp(clamped, stmts, localTypes, dagTempId);
        } else if (node instanceof DensityNode.MappedUnary m) {
            computed = bindDagTemp(
                    lowerMappedUnary(m, wx, wy, wz, seedOffset, rawSeed, stmts, localTypes, dagTempId, dagMemo),
                    stmts,
                    localTypes,
                    dagTempId);
        } else if (node instanceof DensityNode.RangeChoice rc) {
            ConceptExpr in = bindDagTemp(
                    lowerDensityNode(
                            rc.input(), wx, wy, wz, seedOffset, rawSeed, stmts, localTypes, dagTempId, dagMemo),
                    stmts,
                    localTypes,
                    dagTempId);
            ConceptExpr geMin = new ConceptExpr.BinaryOp(
                    BinaryOperator.GE,
                    in,
                    new ConceptExpr.DoubleConst(rc.minInclusive()),
                    ConceptType.BOOL);
            ConceptExpr ltMax = new ConceptExpr.BinaryOp(
                    BinaryOperator.LT,
                    in,
                    new ConceptExpr.DoubleConst(rc.maxExclusive()),
                    ConceptType.BOOL);
            ConceptExpr cond = new ConceptExpr.BinaryOp(
                    BinaryOperator.LOGICAL_AND, geMin, ltMax, ConceptType.BOOL);
            ConceptExpr thenExpr = lowerDensityNode(
                    rc.whenInRange(), wx, wy, wz, seedOffset, rawSeed, stmts, localTypes, dagTempId, dagMemo);
            ConceptExpr elseExpr = lowerDensityNode(
                    rc.whenOutOfRange(), wx, wy, wz, seedOffset, rawSeed, stmts, localTypes, dagTempId, dagMemo);
            computed = bindDagTemp(
                    new ConceptExpr.Ternary(cond, thenExpr, elseExpr, ConceptType.F64),
                    stmts,
                    localTypes,
                    dagTempId);
        } else if (node instanceof DensityNode.Spline1D spline) {
            computed = bindDagTemp(
                    lowerSpline1D(spline, wx, wy, wz, seedOffset, rawSeed, stmts, localTypes, dagTempId, dagMemo),
                    stmts,
                    localTypes,
                    dagTempId);
        } else if (node instanceof DensityNode.LiftedModule lm) {
            ConceptFunction entry = lm.module().entryFunction();
            List<ConceptExpr> callArgs = new ArrayList<>(entry.parameters().size());
            ConceptExpr[] coordInts = {
                new ConceptExpr.Var("world_x", ConceptType.I32),
                new ConceptExpr.Var("world_y", ConceptType.I32),
                new ConceptExpr.Var("world_z", ConceptType.I32)
            };
            ConceptExpr[] coordDbls = {wx, wy, wz};
            for (int i = 0; i < entry.parameters().size(); i++) {
                ConceptParam p = entry.parameters().get(i);
                int idx = Math.min(i, 2);
                if (p.type() == ConceptType.I32) {
                    callArgs.add(coordInts[idx]);
                } else if (p.type() == ConceptType.I64) {
                    callArgs.add(new ConceptExpr.Cast(ConceptType.I64, coordInts[idx]));
                } else if (p.type() == ConceptType.F32) {
                    callArgs.add(new ConceptExpr.Cast(ConceptType.F32, coordDbls[idx]));
                } else {
                    callArgs.add(coordDbls[idx]);
                }
            }
            ConceptExpr call = new ConceptExpr.Call(
                    entry.ownerClass(),
                    lm.uniqueEntryName(),
                    entry.descriptor(),
                    callArgs,
                    entry.returnType());
            ConceptExpr asDouble = entry.returnType() == ConceptType.F64
                    ? call
                    : new ConceptExpr.Cast(ConceptType.F64, call);
            computed = bindDagTemp(asDouble, stmts, localTypes, dagTempId);
        } else {
            throw new IllegalStateException("Unsupported DensityNode: " + node);
        }
        dagMemo.put(node, computed);
        return computed;
    }

    private static ConceptExpr lowerYClampedGradient(DensityNode.YClampedGradient grad, ConceptExpr wy) {
        double span = Math.max(1.0, (double) (grad.toY() - grad.fromY()));
        ConceptExpr rel = new ConceptExpr.BinaryOp(
                BinaryOperator.DIV,
                new ConceptExpr.BinaryOp(
                        BinaryOperator.SUB,
                        wy,
                        new ConceptExpr.DoubleConst(grad.fromY()),
                        ConceptType.F64),
                new ConceptExpr.DoubleConst(span),
                ConceptType.F64);
        ConceptExpr t = new ConceptExpr.MathIntrinsic(
                MathIntrinsicKind.MIN,
                List.of(
                        new ConceptExpr.DoubleConst(1.0),
                        new ConceptExpr.MathIntrinsic(
                                MathIntrinsicKind.MAX,
                                List.of(new ConceptExpr.DoubleConst(0.0), rel),
                                ConceptType.F64)),
                ConceptType.F64);
        return new ConceptExpr.BinaryOp(
                BinaryOperator.ADD,
                new ConceptExpr.DoubleConst(grad.fromValue()),
                new ConceptExpr.BinaryOp(
                        BinaryOperator.MUL,
                        t,
                        new ConceptExpr.DoubleConst(grad.toValue() - grad.fromValue()),
                        ConceptType.F64),
                ConceptType.F64);
    }

    private static ConceptExpr lowerBytecodeNoise3D(
            DensityNode.BytecodeNoise3D n,
            ConceptExpr wx,
            ConceptExpr wy,
            ConceptExpr wz,
            ConceptExpr seedOffset) {
        ConceptExpr sx = new ConceptExpr.BinaryOp(
                BinaryOperator.ADD,
                new ConceptExpr.BinaryOp(
                        BinaryOperator.MUL, wx, new ConceptExpr.DoubleConst(n.xzScale()), ConceptType.F64),
                new ConceptExpr.BinaryOp(
                        BinaryOperator.ADD, seedOffset, new ConceptExpr.DoubleConst(n.offsetX()), ConceptType.F64),
                ConceptType.F64);
        ConceptExpr sy = new ConceptExpr.BinaryOp(
                BinaryOperator.ADD,
                new ConceptExpr.BinaryOp(
                        BinaryOperator.MUL, wy, new ConceptExpr.DoubleConst(n.yScale()), ConceptType.F64),
                new ConceptExpr.DoubleConst(n.offsetY()),
                ConceptType.F64);
        ConceptExpr sz = new ConceptExpr.BinaryOp(
                BinaryOperator.ADD,
                new ConceptExpr.BinaryOp(
                        BinaryOperator.MUL, wz, new ConceptExpr.DoubleConst(n.xzScale()), ConceptType.F64),
                new ConceptExpr.BinaryOp(
                        BinaryOperator.ADD, seedOffset, new ConceptExpr.DoubleConst(n.offsetZ()), ConceptType.F64),
                ConceptType.F64);
        ConceptExpr call = new ConceptExpr.Call(
                n.ownerClass().replace('.', '/'),
                n.methodName(),
                "(DDD)D",
                List.of(sx, sy, sz),
                ConceptType.F64);
        return n.amplitude() == 1.0
                ? call
                : new ConceptExpr.BinaryOp(
                        BinaryOperator.MUL, call, new ConceptExpr.DoubleConst(n.amplitude()), ConceptType.F64);
    }

    private static ConceptExpr lowerBytecodeSeededHash(
            DensityNode.BytecodeSeededHash h,
            ConceptExpr wx,
            ConceptExpr wz,
            ConceptExpr rawSeed) {
        ConceptExpr packedCoord = new ConceptExpr.BinaryOp(
                BinaryOperator.ADD,
                new ConceptExpr.BinaryOp(
                        BinaryOperator.MUL,
                        new ConceptExpr.Cast(ConceptType.I64, wx),
                        new ConceptExpr.LongConst(341873128712L),
                        ConceptType.I64),
                new ConceptExpr.BinaryOp(
                        BinaryOperator.MUL,
                        new ConceptExpr.Cast(ConceptType.I64, wz),
                        new ConceptExpr.LongConst(132897987541L),
                        ConceptType.I64),
                ConceptType.I64);
        ConceptExpr call = new ConceptExpr.Call(
                h.ownerClass().replace('.', '/'),
                h.methodName(),
                "(JJ)D",
                List.of(packedCoord, rawSeed),
                ConceptType.F64);
        return h.amplitude() == 1.0
                ? call
                : new ConceptExpr.BinaryOp(
                        BinaryOperator.MUL, call, new ConceptExpr.DoubleConst(h.amplitude()), ConceptType.F64);
    }

    private static ConceptExpr lowerFbm3D(
            DensityNode.Fbm3D fbm,
            ConceptExpr wx,
            ConceptExpr wy,
            ConceptExpr wz,
            ConceptExpr seedOffset,
            List<ConceptStmt> stmts,
            Map<String, ConceptType> localTypes,
            int[] dagTempId) {
        ConceptExpr sum = null;
        double freqXz = fbm.baseScaleXz();
        double freqY = fbm.baseScaleY();
        double amp = fbm.amplitude();
        for (int octave = 0; octave < fbm.octaves(); octave++) {
            DensityNode.BytecodeNoise3D octaveNode = new DensityNode.BytecodeNoise3D(
                    fbm.ownerClass(),
                    fbm.methodName(),
                    freqXz,
                    freqY,
                    amp,
                    octave * 17.13,
                    octave * 31.71,
                    octave * 47.29);
            ConceptExpr term = bindDagTemp(
                    lowerBytecodeNoise3D(octaveNode, wx, wy, wz, seedOffset),
                    stmts,
                    localTypes,
                    dagTempId);
            sum = sum == null
                    ? term
                    : new ConceptExpr.BinaryOp(BinaryOperator.ADD, sum, term, ConceptType.F64);
            freqXz *= fbm.lacunarity();
            freqY *= fbm.lacunarity();
            amp *= fbm.persistence();
        }
        return sum != null ? sum : new ConceptExpr.DoubleConst(0.0);
    }

    private static ConceptExpr lowerMappedUnary(
            DensityNode.MappedUnary m,
            ConceptExpr wx,
            ConceptExpr wy,
            ConceptExpr wz,
            ConceptExpr seedOffset,
            ConceptExpr rawSeed,
            List<ConceptStmt> stmts,
            Map<String, ConceptType> localTypes,
            int[] dagTempId,
            java.util.IdentityHashMap<DensityNode, ConceptExpr> dagMemo) {
        ConceptExpr in = lowerDensityNode(
                m.input(), wx, wy, wz, seedOffset, rawSeed, stmts, localTypes, dagTempId, dagMemo);
        return switch (m.kind()) {
            case ABS -> new ConceptExpr.MathIntrinsic(MathIntrinsicKind.ABS, List.of(in), ConceptType.F64);
            case SQUARE -> new ConceptExpr.BinaryOp(BinaryOperator.MUL, in, in, ConceptType.F64);
            case CUBE -> new ConceptExpr.BinaryOp(
                    BinaryOperator.MUL,
                    new ConceptExpr.BinaryOp(BinaryOperator.MUL, in, in, ConceptType.F64),
                    in,
                    ConceptType.F64);
            case HALF_NEGATIVE -> new ConceptExpr.Ternary(
                    new ConceptExpr.BinaryOp(
                            BinaryOperator.GT, in, new ConceptExpr.DoubleConst(0.0), ConceptType.BOOL),
                    in,
                    new ConceptExpr.BinaryOp(
                            BinaryOperator.MUL, in, new ConceptExpr.DoubleConst(0.5), ConceptType.F64),
                    ConceptType.F64);
            case QUARTER_NEGATIVE -> new ConceptExpr.Ternary(
                    new ConceptExpr.BinaryOp(
                            BinaryOperator.GT, in, new ConceptExpr.DoubleConst(0.0), ConceptType.BOOL),
                    in,
                    new ConceptExpr.BinaryOp(
                            BinaryOperator.MUL, in, new ConceptExpr.DoubleConst(0.25), ConceptType.F64),
                    ConceptType.F64);
            case SQUEEZE -> {
                ConceptExpr clampedLow = new ConceptExpr.MathIntrinsic(
                        MathIntrinsicKind.MAX,
                        List.of(new ConceptExpr.DoubleConst(-1.0), in),
                        ConceptType.F64);
                ConceptExpr c = bindDagTemp(
                        new ConceptExpr.MathIntrinsic(
                                MathIntrinsicKind.MIN,
                                List.of(new ConceptExpr.DoubleConst(1.0), clampedLow),
                                ConceptType.F64),
                        stmts,
                        localTypes,
                        dagTempId);
                ConceptExpr cCube = new ConceptExpr.BinaryOp(
                        BinaryOperator.MUL,
                        new ConceptExpr.BinaryOp(BinaryOperator.MUL, c, c, ConceptType.F64),
                        c,
                        ConceptType.F64);
                yield new ConceptExpr.BinaryOp(
                        BinaryOperator.SUB,
                        new ConceptExpr.BinaryOp(
                                BinaryOperator.DIV, c, new ConceptExpr.DoubleConst(2.0), ConceptType.F64),
                        new ConceptExpr.BinaryOp(
                                BinaryOperator.DIV, cCube, new ConceptExpr.DoubleConst(24.0), ConceptType.F64),
                        ConceptType.F64);
            }
        };
    }

    /**
     * Lifts a live runtime {@code DensityFunction} object graph (including {@code Constant},
     * {@code YClampedGradient}, {@code Ap2}, {@code MulOrAdd}, {@code Mapped}, {@code RangeChoice},
     * {@code Clamp}, {@code Spline}, {@code Noise}, {@code ShiftedNoise}, {@code BlendedNoise}, and
     * wrapper nodes {@code HolderHolder}, {@code CacheOnce}, {@code FlatCache}, {@code Cache2D},
     * {@code CacheAllInCell}, {@code NoiseInterpolator}, {@code Marker}, {@code BlendDensity})
     * directly into a composable {@link DensityNode} DAG for {@link BytecodeConceptIr} lowering.
     */
    public static DensityNode liftRuntimeDensityNode(
            Object runtimeDf,
            String defaultNoiseOwner,
            String defaultNoiseMethod) {
        return liftRuntimeDensityNode(runtimeDf, defaultNoiseOwner, defaultNoiseMethod, null, null);
    }

    /**
     * Standard {@link BytecodeSsaLifter.ObjectInterner} flattening a single-argument voxel
     * {@code FunctionContext} ({@code blockX/blockY/blockZ}) into scalar {@code (x, y, z)} IR
     * parameters.
     */
    public static final BytecodeSsaLifter.ObjectInterner DENSITY_FUNCTION_CONTEXT_INTERNER =
            new BytecodeSsaLifter.ObjectInterner() {
                @Override
                public BytecodeSsaLifter.ContextParamBinding resolveContextGetter(
                        int entryParamIndex,
                        String ownerInternalName,
                        String methodName,
                        String descriptor) {
                    if (entryParamIndex == 0 && "()I".equals(descriptor)) {
                        if ("blockX".equals(methodName)) {
                            return new BytecodeSsaLifter.ContextParamBinding("x", ConceptType.I32);
                        }
                        if ("blockY".equals(methodName)) {
                            return new BytecodeSsaLifter.ContextParamBinding("y", ConceptType.I32);
                        }
                        if ("blockZ".equals(methodName)) {
                            return new BytecodeSsaLifter.ContextParamBinding("z", ConceptType.I32);
                        }
                    }
                    return null;
                }

                @Override
                public List<BytecodeSsaLifter.ContextParamBinding> contextParameters(int entryParamIndex) {
                    if (entryParamIndex == 0) {
                        return List.of(
                                new BytecodeSsaLifter.ContextParamBinding("x", ConceptType.I32),
                                new BytecodeSsaLifter.ContextParamBinding("y", ConceptType.I32),
                                new BytecodeSsaLifter.ContextParamBinding("z", ConceptType.I32));
                    }
                    return List.of();
                }
            };

    /**
     * Lifts a live runtime {@code DensityFunction} object graph into a {@link DensityNode} DAG via
     * {@link BytecodeSsaLifter#liftInstanceGraph} while recording exact
     * {@link CoverageProvenanceKind#IR_LIFTED} bindings in {@code provenanceOut} and any unmapped
     * runtime symbols in {@code unmappedSymbolsOut}. Fails closed on any impure or unliftable node.
     */
    public static DensityNode liftRuntimeDensityNode(
            Object runtimeDf,
            String defaultNoiseOwner,
            String defaultNoiseMethod,
            Map<String, ProvenanceBinding> provenanceOut,
            Set<String> unmappedSymbolsOut) {
        if (runtimeDf == null) {
            return new DensityNode.Constant(0.0);
        }
        String rootName = runtimeDf.getClass().getName();
        BytecodeSsaLifter.GraphLiftOptions opts =
                BytecodeSsaLifter.GraphLiftOptions.withInterner(DENSITY_FUNCTION_CONTEXT_INTERNER);
        ConceptModule lifted;
        try {
            lifted = BytecodeSsaLifter.liftInstanceGraph(runtimeDf, "compute", null, opts);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            if (unmappedSymbolsOut != null) {
                unmappedSymbolsOut.add(rootName + "#compute");
            }
            return new DensityNode.Constant(0.0);
        }
        if (lifted == null || !lifted.isPureCompute() || !lifted.unmappedReasons().isEmpty()) {
            if (unmappedSymbolsOut != null) {
                unmappedSymbolsOut.add(rootName + "#compute");
                if (lifted != null) {
                    for (BytecodeSsaLifter.UnmappedReason reason : lifted.unmappedReasons()) {
                        if (!reason.concreteClass().isEmpty() && !reason.method().isEmpty()) {
                            unmappedSymbolsOut.add(reason.concreteClass() + "#" + reason.method());
                        }
                    }
                }
            }
            return new DensityNode.Constant(0.0);
        }
        ConceptModule optimized = ConceptOptimizerPass.optimize(lifted);
        if (!optimized.isPureCompute() || !optimized.unmappedReasons().isEmpty()) {
            if (unmappedSymbolsOut != null) {
                unmappedSymbolsOut.add(rootName + "#compute");
            }
            return new DensityNode.Constant(0.0);
        }
        if (provenanceOut != null) {
            provenanceOut.putAll(BytecodeSsaLifter.collectModuleProvenanceBindings(optimized, null));
        }
        String uniqueName = "lifted_" + BytecodeConceptIr.canonicalModuleHash(optimized);
        return new DensityNode.LiftedModule(optimized, uniqueName);
    }

    /**
     * Lifts a live runtime {@code NoiseRouter} (or {@code DensityFunction}) and compiles it into a
     * cached {@link SwappedWorldgenKernel} with both OpenCL and Rust artifacts.
     */
    public static SwappedWorldgenKernel compileFromRuntimeRouter(
            String kernelId,
            Object runtimeRouterOrTerrainDf,
            Object runtimeCaveDfOrNull,
            String defaultNoiseOwner,
            String defaultNoiseMethod,
            VoxelPaletteSpec palette,
            EmitOptions emitOptions,
            Object... runtimeInstances) {
        Objects.requireNonNull(kernelId, "kernelId");
        Objects.requireNonNull(defaultNoiseOwner, "defaultNoiseOwner");
        Objects.requireNonNull(defaultNoiseMethod, "defaultNoiseMethod");
        Objects.requireNonNull(palette, "palette");
        Objects.requireNonNull(emitOptions, "emitOptions");

        Map<String, ProvenanceBinding> routerProvenance = new LinkedHashMap<>();
        Set<String> routerUnmapped = new LinkedHashSet<>();
        Object terrainRoot = extractRouterFieldIfPresent(
                runtimeRouterOrTerrainDf, routerProvenance, "finalDensity", "slopedCheese");
        Object caveRoot = runtimeCaveDfOrNull != null
                ? runtimeCaveDfOrNull
                : extractRouterFieldIfPresent(
                        runtimeRouterOrTerrainDf, routerProvenance, "noodle", "entrances");

        DensityNode terrainNode = liftRuntimeDensityNode(
                terrainRoot, defaultNoiseOwner, defaultNoiseMethod, routerProvenance, routerUnmapped);
        DensityNode caveNode = caveRoot != null && caveRoot != terrainRoot
                ? liftRuntimeDensityNode(
                        caveRoot, defaultNoiseOwner, defaultNoiseMethod, routerProvenance, routerUnmapped)
                : new DensityNode.Constant(0.0);

        WorldgenPipelineSpec spec = new WorldgenPipelineSpec(
                kernelId,
                terrainNode,
                caveNode,
                palette,
                emitOptions);
        SwappedWorldgenKernel base = compileAndSwapFromInstances(spec, runtimeInstances);
        return base.withAdditionalProvenance(routerProvenance, routerUnmapped);
    }

    private static Object extractRouterFieldIfPresent(
            Object candidate,
            Map<String, ProvenanceBinding> provenanceOut,
            String... fieldNames) {
        return BytecodeSsaLifter.readFirstNonNullField(candidate, fieldNames);
    }

    static byte[] loadOwnerClassBytes(Class<?> ownerCls) {
        String resourcePath = ownerCls.getName().replace('.', '/') + ".class";
        ClassLoader cl = ownerCls.getClassLoader();
        try (InputStream in = cl != null
                ? cl.getResourceAsStream(resourcePath)
                : ClassLoader.getSystemResourceAsStream(resourcePath)) {
            return in != null ? in.readAllBytes() : null;
        } catch (IOException ex) {
            LOGGER.log(
                    Level.WARNING,
                    "[WorldgenKernelSwapper] Failed to read .class bytes for " + ownerCls.getName(),
                    ex);
            return null;
        }
    }

    private static ConceptExpr lowerSpline1D(
            DensityNode.Spline1D spline,
            ConceptExpr wx,
            ConceptExpr wy,
            ConceptExpr wz,
            ConceptExpr seedOffset,
            ConceptExpr rawSeed,
            List<ConceptStmt> stmts,
            Map<String, ConceptType> localTypes,
            int[] dagTempId,
            java.util.IdentityHashMap<DensityNode, ConceptExpr> dagMemo) {
        ConceptExpr coord = lowerDensityNode(
                spline.coordinate(), wx, wy, wz, seedOffset, rawSeed, stmts, localTypes, dagTempId, dagMemo);
        double[] knots = spline.knots();
        double[] vals = spline.values();
        ConceptExpr expr = new ConceptExpr.DoubleConst(vals[vals.length - 1]);
        for (int i = knots.length - 2; i >= 0; i--) {
            double k0 = knots[i];
            double k1 = knots[i + 1];
            double v0 = vals[i];
            double v1 = vals[i + 1];
            double span = Math.max(1e-9, k1 - k0);
            ConceptExpr t = new ConceptExpr.BinaryOp(
                    BinaryOperator.DIV,
                    new ConceptExpr.BinaryOp(
                            BinaryOperator.SUB, coord, new ConceptExpr.DoubleConst(k0), ConceptType.F64),
                    new ConceptExpr.DoubleConst(span),
                    ConceptType.F64);
            ConceptExpr segVal = new ConceptExpr.BinaryOp(
                    BinaryOperator.ADD,
                    new ConceptExpr.DoubleConst(v0),
                    new ConceptExpr.BinaryOp(
                            BinaryOperator.MUL,
                            t,
                            new ConceptExpr.DoubleConst(v1 - v0),
                            ConceptType.F64),
                    ConceptType.F64);
            ConceptExpr inSegment = new ConceptExpr.BinaryOp(
                    BinaryOperator.LT, coord, new ConceptExpr.DoubleConst(k1), ConceptType.BOOL);
            expr = new ConceptExpr.Ternary(inSegment, segVal, expr, ConceptType.F64);
        }
        ConceptExpr belowFirst = new ConceptExpr.BinaryOp(
                BinaryOperator.LE, coord, new ConceptExpr.DoubleConst(knots[0]), ConceptType.BOOL);
        return new ConceptExpr.Ternary(belowFirst, new ConceptExpr.DoubleConst(vals[0]), expr, ConceptType.F64);
    }

    /**
     * Compiled, hot-swappable worldgen kernel artifact and high-throughput batch section executor.
     */
    public static final class SwappedWorldgenKernel {
        private final WorldgenPipelineSpec spec;
        private final ConceptModule optimizedModule;
        private final TranspiledArtifact openClArtifact;
        private final TranspiledArtifact rustArtifact;
        private final FastSlotEvaluator fastEvaluator;
        private volatile String kernelIrDigest;
        private volatile Map<String, ProvenanceBinding> provenanceBindings;
        private volatile Set<String> unmappedRuntimeSymbols;
        private final AtomicLong sectionsGenerated = new AtomicLong();
        private final AtomicLong voxelsGenerated = new AtomicLong();
        private final AtomicLong totalElapsedNanos = new AtomicLong();

        SwappedWorldgenKernel(
                WorldgenPipelineSpec spec,
                ConceptModule optimizedModule,
                TranspiledArtifact openClArtifact,
                TranspiledArtifact rustArtifact,
                FastSlotEvaluator fastEvaluator) {
            this.spec = spec;
            this.optimizedModule = optimizedModule;
            this.openClArtifact = openClArtifact;
            this.rustArtifact = rustArtifact;
            this.fastEvaluator = fastEvaluator;
            this.kernelIrDigest = optimizedModule != null ? BytecodeConceptIr.canonicalModuleHash(optimizedModule) : "";
            this.provenanceBindings = Map.of();
            this.unmappedRuntimeSymbols = Set.of();
        }

        static SwappedWorldgenKernel createWithProvenance(
                WorldgenPipelineSpec spec,
                ConceptModule optimizedModule,
                TranspiledArtifact openClArtifact,
                TranspiledArtifact rustArtifact,
                FastSlotEvaluator fastEvaluator,
                String kernelIrDigest,
                Map<String, ProvenanceBinding> provenanceBindings,
                Set<String> unmappedRuntimeSymbols) {
            SwappedWorldgenKernel kernel = new SwappedWorldgenKernel(
                    spec, optimizedModule, openClArtifact, rustArtifact, fastEvaluator);
            kernel.kernelIrDigest = kernelIrDigest == null ? "" : kernelIrDigest;
            kernel.provenanceBindings = provenanceBindings == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(provenanceBindings));
            kernel.unmappedRuntimeSymbols = unmappedRuntimeSymbols == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(unmappedRuntimeSymbols));
            return kernel;
        }

        public SwappedWorldgenKernel withAdditionalProvenance(
                Map<String, ProvenanceBinding> extraBindings,
                Set<String> extraUnmapped) {
            Map<String, ProvenanceBinding> merged = new LinkedHashMap<>(this.provenanceBindings);
            if (extraBindings != null) {
                merged.putAll(extraBindings);
            }
            Set<String> mergedUnmapped = new LinkedHashSet<>(this.unmappedRuntimeSymbols);
            if (extraUnmapped != null) {
                mergedUnmapped.addAll(extraUnmapped);
            }
            return createWithProvenance(
                    spec,
                    optimizedModule,
                    openClArtifact,
                    rustArtifact,
                    fastEvaluator,
                    kernelIrDigest,
                    merged,
                    mergedUnmapped);
        }

        /**
         * Returns a new {@link SwappedWorldgenKernel} sharing this kernel's compiled CPU evaluator,
         * optimized IR module, Rust artifact, and provenance bindings while replacing its OpenCL
         * artifact with {@code customOpenClSource} and {@code precision}.
         */
        public SwappedWorldgenKernel withCustomOpenClSource(
                WorldgenPipelineSpec specOverride,
                FloatingPointPrecision precision,
                String customOpenClSource) {
            return withCustomOpenClSource(specOverride, precision, customOpenClSource, null);
        }

        /**
         * Returns a new {@link SwappedWorldgenKernel} sharing this kernel's compiled CPU evaluator,
         * optimized IR module, Rust artifact, and provenance bindings while replacing its OpenCL
         * artifact with {@code customOpenClSource}, {@code precision}, and optional
         * {@code kernel3DNameOverride}.
         */
        public SwappedWorldgenKernel withCustomOpenClSource(
                WorldgenPipelineSpec specOverride,
                FloatingPointPrecision precision,
                String customOpenClSource,
                String kernel3DNameOverride) {
            Objects.requireNonNull(customOpenClSource, "customOpenClSource");
            WorldgenPipelineSpec effSpec = specOverride != null ? specOverride : this.spec;
            TranspiledArtifact origArt = this.openClArtifact;
            FloatingPointPrecision effPrecision = precision != null
                    ? precision
                    : (origArt != null ? origArt.fpPrecision() : effSpec.emitOptions().fpPrecision());
            String effKernel3D = (kernel3DNameOverride != null && !kernel3DNameOverride.isBlank())
                    ? kernel3DNameOverride
                    : (origArt != null ? origArt.kernel3DName() : "evaluate_evaluate_worldgen_voxel_grid3d");
            String backendId = origArt != null ? origArt.backendId() : "opencl";
            String fileExt = origArt != null ? origArt.fileExtension() : ".cl";
            String entryFn = origArt != null ? origArt.entryFunctionName() : "evaluate_worldgen_voxel";
            TranspiledArtifact customArt = new TranspiledArtifact(
                    backendId,
                    fileExt,
                    effSpec.kernelId(),
                    entryFn,
                    effKernel3D,
                    effPrecision,
                    customOpenClSource,
                    this.optimizedModule);
            return createWithProvenance(
                    effSpec,
                    this.optimizedModule,
                    customArt,
                    this.rustArtifact,
                    this.fastEvaluator,
                    this.kernelIrDigest,
                    this.provenanceBindings,
                    this.unmappedRuntimeSymbols);
        }

        /**
         * Static factory alias for {@link #withCustomOpenClSource(WorldgenPipelineSpec, FloatingPointPrecision, String, String)}.
         */
        public static SwappedWorldgenKernel createWithCustomOpenClSource(
                SwappedWorldgenKernel template,
                WorldgenPipelineSpec specOverride,
                FloatingPointPrecision precision,
                String customOpenClSource,
                String kernel3DNameOverride) {
            Objects.requireNonNull(template, "template");
            return template.withCustomOpenClSource(
                    specOverride, precision, customOpenClSource, kernel3DNameOverride);
        }

        public WorldgenPipelineSpec spec() {
            return spec;
        }

        public ConceptModule optimizedModule() {
            return optimizedModule;
        }

        public TranspiledArtifact openClArtifact() {
            return openClArtifact;
        }

        public TranspiledArtifact rustArtifact() {
            return rustArtifact;
        }

        public String kernelIrDigest() {
            return kernelIrDigest;
        }

        public Map<String, ProvenanceBinding> provenanceBindings() {
            return provenanceBindings;
        }

        public Set<String> unmappedRuntimeSymbols() {
            return unmappedRuntimeSymbols;
        }

        public int evaluateVoxel(int worldX, int worldY, int worldZ, long worldSeed) {
            return fastEvaluator.evalVoxel(worldX, worldY, worldZ, worldSeed);
        }

        /**
         * Evaluates all 4,096 voxels of a 16x16x16 section in YZX order
         * ({@code pos12 = (ly << 8) | (lz << 4) | lx}, matching {@code LcaSectionBacking.pos12}
         * and vanilla {@code PalettedContainer}) into {@code outVoxels4096}.
         */
        public void generateSection(
                SectionCoord section,
                long worldSeed,
                int[] outVoxels4096) {
            Objects.requireNonNull(section, "section");
            Objects.requireNonNull(outVoxels4096, "outVoxels4096");
            if (outVoxels4096.length < SECTION_VOXEL_COUNT) {
                throw new IllegalArgumentException("outVoxels4096.length < 4096: " + outVoxels4096.length);
            }
            long startNs = System.nanoTime();
            fastEvaluator.evalSection16x16x16(
                    section.originBlockX(),
                    section.originBlockY(),
                    section.originBlockZ(),
                    worldSeed,
                    outVoxels4096);
            long elapsed = Math.max(1L, System.nanoTime() - startNs);
            sectionsGenerated.incrementAndGet();
            voxelsGenerated.addAndGet(SECTION_VOXEL_COUNT);
            totalElapsedNanos.addAndGet(elapsed);
        }

        /**
         * Evaluates a batch of 16x16x16 sections in parallel across worker threads (or GPU queue),
         * returning flat {@code int[4096]} buffers ready for {@code LcaSectionBacking.fromDirectVoxels}.
         */
        public List<int[]> generateSectionBatch(List<SectionCoord> sections, long worldSeed) {
            Objects.requireNonNull(sections, "sections");
            int count = sections.size();
            if (count == 0) {
                return List.of();
            }
            int[][] results = new int[count][SECTION_VOXEL_COUNT];
            long startNs = System.nanoTime();
            IntStream.range(0, count).parallel().forEach(i -> {
                SectionCoord coord = sections.get(i);
                fastEvaluator.evalSection16x16x16(
                        coord.originBlockX(),
                        coord.originBlockY(),
                        coord.originBlockZ(),
                        worldSeed,
                        results[i]);
            });
            long elapsed = Math.max(1L, System.nanoTime() - startNs);
            sectionsGenerated.addAndGet(count);
            voxelsGenerated.addAndGet((long) count * SECTION_VOXEL_COUNT);
            totalElapsedNanos.addAndGet(elapsed);
            List<int[]> out = new ArrayList<>(count);
            Collections.addAll(out, results);
            return out;
        }

        public long sectionsGenerated() {
            return sectionsGenerated.get();
        }

        public long voxelsGenerated() {
            return voxelsGenerated.get();
        }

        public double measuredSectionsPerSecond() {
            long ns = totalElapsedNanos.get();
            if (ns <= 0L) {
                return 0.0;
            }
            return (sectionsGenerated.get() * 1_000_000_000.0) / ns;
        }

        public double measuredChunksPerSecond(int activeSectionsPerChunk) {
            int denom = Math.max(1, activeSectionsPerChunk);
            return measuredSectionsPerSecond() / denom;
        }
    }

    /**
     * Direct native JVM bytecode evaluator interface implemented by hidden classes synthesized by
     * {@link JvmBytecodeKernelCompiler}.
     */
    public interface NativeVoxelEvaluator {
        int evalVoxel(int worldX, int worldY, int worldZ, long worldSeed, int[] lut0);
    }

    /**
     * Zero-allocation 3-Address Code (TAC) + Value-Numbering CSE + in-memory JVM bytecode compiler
     * for an optimized {@link ConceptModule}.
     */
    static final class FastSlotEvaluator {
        private static final int SLICE_SIZE = SECTION_EDGE * SECTION_EDGE; // 256 voxels per Y-slice
        private static final BinaryOperator[] BIN_OPS = BinaryOperator.values();
        private static final MathIntrinsicKind[] MATH_KINDS = MathIntrinsicKind.values();

        private static final int OP_CONST_I = 1;
        private static final int OP_CONST_F = 2;
        private static final int OP_COPY_I = 3;
        private static final int OP_COPY_F = 4;
        private static final int OP_CAST_I2F = 5;
        private static final int OP_CAST_F2I32 = 6;
        private static final int OP_CAST_F2I64 = 7;
        private static final int OP_CAST_I64_TO_I32 = 8;
        private static final int OP_CAST_I_TO_I64 = 9;
        private static final int OP_CAST_F64_TO_F32 = 10;
        private static final int OP_NEG_I32 = 11;
        private static final int OP_NEG_I64 = 12;
        private static final int OP_NEG_F = 13;
        private static final int OP_NOT_BOOL = 14;
        private static final int OP_BIT_NOT_I = 15;
        private static final int OP_BIN_F = 16;
        private static final int OP_BIN_I32 = 17;
        private static final int OP_BIN_I64 = 18;
        private static final int OP_CMP_F = 19;
        private static final int OP_CMP_I = 20;
        private static final int OP_TERNARY_F = 21;
        private static final int OP_TERNARY_I = 22;
        private static final int OP_LUT_LOAD = 23;
        private static final int OP_MATH = 24;
        private static final int OP_JZ = 25;
        private static final int OP_JMP = 26;
        private static final int OP_RET_I = 27;

        private record TacInsn(
                int op,
                int subOp,
                int dst,
                int srcA,
                int srcB,
                int srcC,
                long longImm,
                double doubleImm
        ) {
        }

        private record ScratchFrame(
                long[] iScalar,
                double[] fScalar
        ) {
            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (!(o instanceof ScratchFrame other)) {
                    return false;
                }
                return java.util.Arrays.equals(this.iScalar, other.iScalar)
                        && java.util.Arrays.equals(this.fScalar, other.fScalar);
            }

            @Override
            public int hashCode() {
                int result = 1;
                result = 31 * result + java.util.Arrays.hashCode(this.iScalar);
                result = 31 * result + java.util.Arrays.hashCode(this.fScalar);
                return result;
            }

            @Override
            public String toString() {
                return "ScratchFrame[iScalar=" + java.util.Arrays.toString(this.iScalar)
                        + ", fScalar=" + java.util.Arrays.toString(this.fScalar)
                        + "]";
            }
        }

        private final TacInsn[] insns;
        private final TacInsn[] seedTape;
        private final TacInsn[] xTape;
        private final TacInsn[] xzTape;
        private final TacInsn[] yTape;
        private final boolean hasSplitTapes;
        private final int slotCount;
        private final int returnSlot;
        private final int[][] intLuts;
        private final int[] primaryLut;
        private final NativeVoxelEvaluator nativeEvaluator;
        private final long[] initISlots;
        private final double[] initFSlots;
        private final ThreadLocal<ScratchFrame> threadScratch;

        private FastSlotEvaluator(
                TacInsn[] insns,
                TacInsn[] seedTape,
                TacInsn[] xTape,
                TacInsn[] xzTape,
                TacInsn[] yTape,
                boolean hasSplitTapes,
                int slotCount,
                int returnSlot,
                int[][] intLuts,
                NativeVoxelEvaluator nativeEvaluator,
                long[] initISlots,
                double[] initFSlots) {
            this.insns = insns;
            this.seedTape = seedTape;
            this.xTape = xTape;
            this.xzTape = xzTape;
            this.yTape = yTape;
            this.hasSplitTapes = hasSplitTapes;
            this.slotCount = slotCount;
            this.returnSlot = returnSlot;
            this.intLuts = intLuts;
            int totalLutLen = 0;
            for (int[] arr : intLuts) {
                totalLutLen += arr.length;
            }
            int[] flatLut = new int[totalLutLen];
            int lutPos = 0;
            for (int[] arr : intLuts) {
                System.arraycopy(arr, 0, flatLut, lutPos, arr.length);
                lutPos += arr.length;
            }
            this.primaryLut = flatLut;
            this.nativeEvaluator = nativeEvaluator;
            this.initISlots = initISlots;
            this.initFSlots = initFSlots;
            this.threadScratch = ThreadLocal.withInitial(() -> new ScratchFrame(
                    initISlots.clone(),
                    initFSlots.clone()));
        }

        static FastSlotEvaluator compile(ConceptModule module) {
            return compile(module, module);
        }

        static FastSlotEvaluator compile(ConceptModule module, ConceptModule jvmModule) {
            ConceptFunction fn = module.entryFunction();
            Map<String, Integer> namedSlots = new LinkedHashMap<>();
            for (ConceptParam p : fn.parameters()) {
                namedSlots.put(p.name(), namedSlots.size());
            }
            for (String local : fn.localTypes().keySet()) {
                namedSlots.putIfAbsent(local, namedSlots.size());
            }
            collectAssignedSlots(fn.body(), namedSlots);
            int retSlot = namedSlots.computeIfAbsent("__ret_slot", k -> namedSlots.size());

            Map<String, Integer> lutMap = new LinkedHashMap<>();
            List<int[]> lutArrays = new ArrayList<>();
            for (ConceptLut lut : module.lookupTables()) {
                lutMap.put(lut.symbolName(), lutArrays.size());
                int[] arr = new int[lut.values().size()];
                for (int i = 0; i < arr.length; i++) {
                    arr[i] = lut.values().get(i).intValue();
                }
                lutArrays.add(arr);
            }

            NativeVoxelEvaluator compiledNative = JvmBytecodeKernelCompiler.tryCompile(
                    jvmModule != null ? jvmModule : module);

            TacBuilder builder = new TacBuilder(namedSlots, lutMap, retSlot);
            builder.emitStmts(fn.body());
            TacProgram compacted = builder.compactAndPrune();
            FastSlotEvaluator evaluator = new FastSlotEvaluator(
                    compacted.insns(),
                    compacted.seedTape(),
                    compacted.xTape(),
                    compacted.xzTape(),
                    compacted.yTape(),
                    compacted.hasSplitTapes(),
                    compacted.slotCount(),
                    compacted.returnSlot(),
                    lutArrays.toArray(new int[0][]),
                    compiledNative,
                    compacted.initISlots(),
                    compacted.initFSlots());
            // Warm up section + scalar execution loops past HotSpot Tier 4 back-edge threshold
            int[] warmupBuf = new int[SECTION_VOXEL_COUNT];
            evaluator.evalSection16x16x16(0, 0, 0, 1L, warmupBuf);
            evaluator.evalSection16x16x16(16, 16, 16, 2L, warmupBuf);
            return evaluator;
        }

        int evalVoxel(int worldX, int worldY, int worldZ, long seed) {
            if (nativeEvaluator != null) {
                return nativeEvaluator.evalVoxel(worldX, worldY, worldZ, seed, primaryLut);
            }
            ScratchFrame frame = threadScratch.get();
            long[] iSlots = frame.iScalar();
            double[] fSlots = frame.fScalar();
            iSlots[0] = worldX;
            iSlots[1] = worldY;
            iSlots[2] = worldZ;
            iSlots[3] = seed;
            return executeScalar(this.insns, iSlots, fSlots);
        }

        void evalSection16x16x16(
                int originX,
                int originY,
                int originZ,
                long seed,
                int[] outVoxels4096) {
            if (nativeEvaluator != null) {
                NativeVoxelEvaluator eval = this.nativeEvaluator;
                int[] lut0 = this.primaryLut;
                int idx = 0;
                for (int ly = 0; ly < SECTION_EDGE; ly++) {
                    int wy = originY + ly;
                    for (int lz = 0; lz < SECTION_EDGE; lz++) {
                        int wz = originZ + lz;
                        for (int lx = 0; lx < SECTION_EDGE; lx++) {
                            outVoxels4096[idx++] = eval.evalVoxel(originX + lx, wy, wz, seed, lut0);
                        }
                    }
                }
                return;
            }
            ScratchFrame frame = threadScratch.get();
            long[] iSlots = frame.iScalar();
            double[] fSlots = frame.fScalar();
            iSlots[3] = seed;
            if (!hasSplitTapes) {
                int idx = 0;
                for (int ly = 0; ly < SECTION_EDGE; ly++) {
                    int wy = originY + ly;
                    for (int lz = 0; lz < SECTION_EDGE; lz++) {
                        int wz = originZ + lz;
                        for (int lx = 0; lx < SECTION_EDGE; lx++) {
                            iSlots[0] = originX + lx;
                            iSlots[1] = wy;
                            iSlots[2] = wz;
                            outVoxels4096[idx++] = executeScalar(this.insns, iSlots, fSlots);
                        }
                    }
                }
                return;
            }

            executeScalar(this.seedTape, iSlots, fSlots);
            for (int lx = 0; lx < SECTION_EDGE; lx++) {
                iSlots[0] = originX + lx;
                executeScalar(this.xTape, iSlots, fSlots);
                for (int lz = 0; lz < SECTION_EDGE; lz++) {
                    iSlots[2] = originZ + lz;
                    executeScalar(this.xzTape, iSlots, fSlots);
                    int colOffset = (lz << 4) | lx;
                    for (int ly = 0; ly < SECTION_EDGE; ly++) {
                        iSlots[1] = originY + ly;
                        outVoxels4096[(ly << 8) | colOffset] = executeScalar(this.yTape, iSlots, fSlots);
                    }
                }
            }
        }

        private int executeScalar(TacInsn[] prog, long[] iSlots, double[] fSlots) {
            int[][] luts = this.intLuts;
            for (int pc = 0, len = prog.length; pc < len; pc++) {
                TacInsn insn = prog[pc];
                int dst = insn.dst;
                int a = insn.srcA;
                int b = insn.srcB;
                int c = insn.srcC;
                switch (insn.op) {
                    case OP_CONST_I -> iSlots[dst] = insn.longImm;
                    case OP_CONST_F -> fSlots[dst] = insn.doubleImm;
                    case OP_COPY_I, OP_CAST_I_TO_I64 -> iSlots[dst] = iSlots[a];
                    case OP_COPY_F -> fSlots[dst] = fSlots[a];
                    case OP_CAST_I2F -> fSlots[dst] = (double) iSlots[a];
                    case OP_CAST_F2I32 -> iSlots[dst] = (int) fSlots[a];
                    case OP_CAST_F2I64 -> iSlots[dst] = (long) fSlots[a];
                    case OP_CAST_I64_TO_I32 -> iSlots[dst] = (int) iSlots[a];
                    case OP_CAST_F64_TO_F32 -> fSlots[dst] = (float) fSlots[a];
                    case OP_NEG_I32 -> iSlots[dst] = -((int) iSlots[a]);
                    case OP_NEG_I64 -> iSlots[dst] = -iSlots[a];
                    case OP_NEG_F -> fSlots[dst] = -fSlots[a];
                    case OP_NOT_BOOL -> iSlots[dst] = iSlots[a] == 0L ? 1L : 0L;
                    case OP_BIT_NOT_I -> iSlots[dst] = ~iSlots[a];
                    case OP_BIN_F -> {
                        double la = fSlots[a];
                        double ra = fSlots[b];
                        fSlots[dst] = switch (BIN_OPS[insn.subOp]) {
                            case ADD -> la + ra;
                            case SUB -> la - ra;
                            case MUL -> la * ra;
                            case DIV -> la / ra;
                            case REM -> la % ra;
                            default -> 0.0;
                        };
                    }
                    case OP_BIN_I32 -> {
                        int la = (int) iSlots[a];
                        int ra = (int) iSlots[b];
                        iSlots[dst] = switch (BIN_OPS[insn.subOp]) {
                            case ADD -> la + ra;
                            case SUB -> la - ra;
                            case MUL -> la * ra;
                            case DIV -> la / ra;
                            case REM -> la % ra;
                            case BIT_AND -> la & ra;
                            case BIT_OR -> la | ra;
                            case BIT_XOR -> la ^ ra;
                            case SHL -> la << (ra & 31);
                            case SHR -> la >> (ra & 31);
                            case USHR -> la >>> (ra & 31);
                            default -> 0;
                        };
                    }
                    case OP_BIN_I64 -> {
                        long la = iSlots[a];
                        long ra = iSlots[b];
                        iSlots[dst] = switch (BIN_OPS[insn.subOp]) {
                            case ADD -> la + ra;
                            case SUB -> la - ra;
                            case MUL -> la * ra;
                            case DIV -> la / ra;
                            case REM -> la % ra;
                            case BIT_AND -> la & ra;
                            case BIT_OR -> la | ra;
                            case BIT_XOR -> la ^ ra;
                            case SHL -> la << (((int) ra) & 63);
                            case SHR -> la >> (((int) ra) & 63);
                            case USHR -> la >>> (((int) ra) & 63);
                            default -> 0L;
                        };
                    }
                    case OP_CMP_F -> {
                        double la = fSlots[a];
                        double ra = fSlots[b];
                        boolean res = switch (BIN_OPS[insn.subOp]) {
                            case EQ -> la == ra;
                            case NE -> la != ra;
                            case LT -> la < ra;
                            case LE -> la <= ra;
                            case GT -> la > ra;
                            case GE -> la >= ra;
                            default -> false;
                        };
                        iSlots[dst] = res ? 1L : 0L;
                    }
                    case OP_CMP_I -> {
                        long la = iSlots[a];
                        long ra = iSlots[b];
                        boolean res = switch (BIN_OPS[insn.subOp]) {
                            case EQ -> la == ra;
                            case NE -> la != ra;
                            case LT -> la < ra;
                            case LE -> la <= ra;
                            case GT -> la > ra;
                            case GE -> la >= ra;
                            case LOGICAL_AND -> la != 0L && ra != 0L;
                            case LOGICAL_OR -> la != 0L || ra != 0L;
                            default -> false;
                        };
                        iSlots[dst] = res ? 1L : 0L;
                    }
                    case OP_TERNARY_F -> fSlots[dst] = iSlots[a] != 0L ? fSlots[b] : fSlots[c];
                    case OP_TERNARY_I -> iSlots[dst] = iSlots[a] != 0L ? iSlots[b] : iSlots[c];
                    case OP_LUT_LOAD -> iSlots[dst] = luts[insn.subOp][(int) iSlots[a]];
                    case OP_MATH -> execMathScalar(insn.subOp, fSlots, iSlots, dst, a, b, c);
                    case OP_JZ -> {
                        if (iSlots[a] == 0L) {
                            pc = insn.dst - 1;
                        }
                    }
                    case OP_JMP -> pc = insn.dst - 1;
                    case OP_RET_I -> {
                        return (int) iSlots[a];
                    }
                    default -> {
                    }
                }
            }
            return (int) iSlots[returnSlot];
        }

        private static void execMathScalar(
                int subOp,
                double[] fSlots,
                long[] iSlots,
                int dst,
                int a,
                int b,
                int c) {
            MathIntrinsicKind kind = MATH_KINDS[subOp];
            double v0 = fSlots[a];
            if (kind == MathIntrinsicKind.IS_FINITE) {
                iSlots[dst] = Double.isFinite(v0) ? 1L : 0L;
                return;
            }
            if (kind == MathIntrinsicKind.IS_NAN) {
                iSlots[dst] = Double.isNaN(v0) ? 1L : 0L;
                return;
            }
            if (kind == MathIntrinsicKind.POPCOUNT) {
                iSlots[dst] = Long.bitCount(iSlots[a]);
                return;
            }
            if (kind == MathIntrinsicKind.CLZ) {
                iSlots[dst] = Long.numberOfLeadingZeros(iSlots[a]);
                return;
            }
            if (kind == MathIntrinsicKind.CTZ) {
                iSlots[dst] = Long.numberOfTrailingZeros(iSlots[a]);
                return;
            }
            fSlots[dst] = switch (kind) {
                case FLOOR -> Math.floor(v0);
                case CEIL -> Math.ceil(v0);
                case ROUND -> Math.rint(v0);
                case SQRT -> Math.sqrt(v0);
                case ABS -> Math.abs(v0);
                case SIN -> Math.sin(v0);
                case COS -> Math.cos(v0);
                case TAN -> Math.tan(v0);
                case EXP -> Math.exp(v0);
                case LOG -> Math.log(v0);
                case ATAN2 -> Math.atan2(v0, fSlots[b]);
                case POW -> Math.pow(v0, fSlots[b]);
                case MIN -> Math.min(v0, fSlots[b]);
                case MAX -> Math.max(v0, fSlots[b]);
                case FMA -> Math.fma(v0, fSlots[b], fSlots[c]);
                default -> 0.0;
            };
        }

        private static void collectAssignedSlots(
                List<ConceptStmt> stmts,
                Map<String, Integer> slotMap) {
            for (ConceptStmt s : stmts) {
                if (s instanceof ConceptStmt.Assign a) {
                    slotMap.putIfAbsent(a.name(), slotMap.size());
                } else if (s instanceof ConceptStmt.VarDecl v) {
                    slotMap.putIfAbsent(v.name(), slotMap.size());
                } else if (s instanceof ConceptStmt.If i) {
                    collectAssignedSlots(i.thenBody(), slotMap);
                    collectAssignedSlots(i.elseBody(), slotMap);
                } else if (s instanceof ConceptStmt.While w) {
                    collectAssignedSlots(w.body(), slotMap);
                }
            }
        }

        private record CseKey(
                int op,
                int subOp,
                int srcA,
                int srcB,
                int srcC,
                long longImm,
                long doubleBits
        ) {
        }

        private record TacProgram(
                TacInsn[] insns,
                TacInsn[] seedTape,
                TacInsn[] xTape,
                TacInsn[] xzTape,
                TacInsn[] yTape,
                boolean hasSplitTapes,
                int slotCount,
                int returnSlot,
                long[] initISlots,
                double[] initFSlots
        ) {
            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (!(o instanceof TacProgram other)) {
                    return false;
                }
                return java.util.Arrays.equals(this.insns, other.insns)
                        && java.util.Arrays.equals(this.seedTape, other.seedTape)
                        && java.util.Arrays.equals(this.xTape, other.xTape)
                        && java.util.Arrays.equals(this.xzTape, other.xzTape)
                        && java.util.Arrays.equals(this.yTape, other.yTape)
                        && this.hasSplitTapes == other.hasSplitTapes
                        && this.slotCount == other.slotCount
                        && this.returnSlot == other.returnSlot
                        && java.util.Arrays.equals(this.initISlots, other.initISlots)
                        && java.util.Arrays.equals(this.initFSlots, other.initFSlots);
            }

            @Override
            public int hashCode() {
                int result = 1;
                result = 31 * result + java.util.Arrays.hashCode(this.insns);
                result = 31 * result + java.util.Arrays.hashCode(this.seedTape);
                result = 31 * result + java.util.Arrays.hashCode(this.xTape);
                result = 31 * result + java.util.Arrays.hashCode(this.xzTape);
                result = 31 * result + java.util.Arrays.hashCode(this.yTape);
                result = 31 * result + Boolean.hashCode(this.hasSplitTapes);
                result = 31 * result + this.slotCount;
                result = 31 * result + this.returnSlot;
                result = 31 * result + java.util.Arrays.hashCode(this.initISlots);
                result = 31 * result + java.util.Arrays.hashCode(this.initFSlots);
                return result;
            }

            @Override
            public String toString() {
                return "TacProgram[insns=" + java.util.Arrays.toString(this.insns)
                        + ", seedTape=" + java.util.Arrays.toString(this.seedTape)
                        + ", xTape=" + java.util.Arrays.toString(this.xTape)
                        + ", xzTape=" + java.util.Arrays.toString(this.xzTape)
                        + ", yTape=" + java.util.Arrays.toString(this.yTape)
                        + ", hasSplitTapes=" + this.hasSplitTapes
                        + ", slotCount=" + this.slotCount
                        + ", returnSlot=" + this.returnSlot
                        + ", initISlots=" + java.util.Arrays.toString(this.initISlots)
                        + ", initFSlots=" + java.util.Arrays.toString(this.initFSlots)
                        + "]";
            }
        }

        private static final class TacBuilder {
            private final Map<String, Integer> namedSlots;
            private final Map<String, Integer> lutMap;
            private final int returnSlot;
            private final List<TacInsn> insns = new ArrayList<>();
            private final Map<CseKey, Integer> cseTable = new LinkedHashMap<>();
            private final Map<Integer, Long> constISlots = new LinkedHashMap<>();
            private final Map<Integer, Double> constFSlots = new LinkedHashMap<>();
            private int nextSlot;
            private int branchDepth;
            private boolean hasJumps;

            TacBuilder(
                    Map<String, Integer> namedSlots,
                    Map<String, Integer> lutMap,
                    int returnSlot) {
                this.namedSlots = namedSlots;
                this.lutMap = lutMap;
                this.returnSlot = returnSlot;
                this.nextSlot = namedSlots.size();
            }

            TacProgram compactAndPrune() {
                if (hasJumps) {
                    long[] initI = new long[nextSlot];
                    double[] initF = new double[nextSlot];
                    for (Map.Entry<Integer, Long> e : constISlots.entrySet()) {
                        initI[e.getKey()] = e.getValue();
                    }
                    for (Map.Entry<Integer, Double> e : constFSlots.entrySet()) {
                        initF[e.getKey()] = e.getValue();
                    }
                    TacInsn[] raw = insns.toArray(new TacInsn[0]);
                    TacInsn[] empty = new TacInsn[0];
                    return new TacProgram(raw, empty, empty, empty, raw, false, nextSlot, returnSlot, initI, initF);
                }

                // Backward liveness pass to prune dead instructions
                boolean[] liveSlot = new boolean[nextSlot];
                for (int p = 0; p < 4 && p < nextSlot; p++) {
                    liveSlot[p] = true;
                }
                liveSlot[returnSlot] = true;

                boolean[] keepInsn = new boolean[insns.size()];
                for (int i = insns.size() - 1; i >= 0; i--) {
                    TacInsn insn = insns.get(i);
                    if (insn.op == OP_RET_I || (insn.dst >= 0 && liveSlot[insn.dst])) {
                        keepInsn[i] = true;
                        if (insn.srcA >= 0) {
                            liveSlot[insn.srcA] = true;
                        }
                        if (insn.srcB >= 0) {
                            liveSlot[insn.srcB] = true;
                        }
                        if (insn.srcC >= 0) {
                            liveSlot[insn.srcC] = true;
                        }
                    }
                }

                // Renumber live slots into a dense [0..denseCount) array
                int[] oldToNew = new int[nextSlot];
                java.util.Arrays.fill(oldToNew, -1);
                int denseCount = 0;
                for (int s = 0; s < 4 && s < nextSlot; s++) {
                    oldToNew[s] = denseCount++;
                }
                for (int s = 4; s < nextSlot; s++) {
                    if (liveSlot[s]) {
                        oldToNew[s] = denseCount++;
                    }
                }

                long[] initI = new long[denseCount];
                double[] initF = new double[denseCount];
                for (Map.Entry<Integer, Long> e : constISlots.entrySet()) {
                    int mapped = oldToNew[e.getKey()];
                    if (mapped >= 0) {
                        initI[mapped] = e.getValue();
                    }
                }
                for (Map.Entry<Integer, Double> e : constFSlots.entrySet()) {
                    int mapped = oldToNew[e.getKey()];
                    if (mapped >= 0) {
                        initF[mapped] = e.getValue();
                    }
                }

                List<TacInsn> compacted = new ArrayList<>(insns.size());
                for (int i = 0; i < insns.size(); i++) {
                    if (!keepInsn[i]) {
                        continue;
                    }
                    TacInsn insn = insns.get(i);
                    compacted.add(new TacInsn(
                            insn.op,
                            insn.subOp,
                            insn.dst >= 0 ? oldToNew[insn.dst] : -1,
                            insn.srcA >= 0 ? oldToNew[insn.srcA] : -1,
                            insn.srcB >= 0 ? oldToNew[insn.srcB] : -1,
                            insn.srcC >= 0 ? oldToNew[insn.srcC] : -1,
                            insn.longImm,
                            insn.doubleImm));
                }

                // Partition compacted instructions by 3D loop-invariance (seed -> X -> XZ -> Y)
                int[] slotAxisMask = new int[denseCount];
                if (denseCount > 0) {
                    slotAxisMask[0] = 1; // world_x
                }
                if (denseCount > 1) {
                    slotAxisMask[1] = 2; // world_y
                }
                if (denseCount > 2) {
                    slotAxisMask[2] = 4; // world_z
                }
                List<TacInsn> seedTape = new ArrayList<>();
                List<TacInsn> xTape = new ArrayList<>();
                List<TacInsn> xzTape = new ArrayList<>();
                List<TacInsn> yTape = new ArrayList<>();

                for (TacInsn insn : compacted) {
                    if (insn.op == OP_RET_I) {
                        yTape.add(insn);
                        continue;
                    }
                    int mask = (insn.srcA >= 0 ? slotAxisMask[insn.srcA] : 0)
                            | (insn.srcB >= 0 ? slotAxisMask[insn.srcB] : 0)
                            | (insn.srcC >= 0 ? slotAxisMask[insn.srcC] : 0);
                    if (insn.dst >= 0) {
                        slotAxisMask[insn.dst] = mask;
                    }
                    if (mask == 0) {
                        seedTape.add(insn);
                    } else if (mask == 1) {
                        xTape.add(insn);
                    } else if ((mask & 2) == 0) {
                        xzTape.add(insn);
                    } else {
                        yTape.add(insn);
                    }
                }

                return new TacProgram(
                        compacted.toArray(new TacInsn[0]),
                        seedTape.toArray(new TacInsn[0]),
                        xTape.toArray(new TacInsn[0]),
                        xzTape.toArray(new TacInsn[0]),
                        yTape.toArray(new TacInsn[0]),
                        true,
                        denseCount,
                        oldToNew[returnSlot],
                        initI,
                        initF);
            }

            void emitStmts(List<ConceptStmt> stmts) {
                for (ConceptStmt s : stmts) {
                    if (s instanceof ConceptStmt.Assign a) {
                        int valSlot = emitExpr(a.value());
                        if (branchDepth == 0) {
                            namedSlots.put(a.name(), valSlot);
                        } else {
                            int dst = namedSlots.get(a.name());
                            if (valSlot != dst) {
                                boolean fp = a.type().isFloatingPoint();
                                insns.add(new TacInsn(
                                        fp ? OP_COPY_F : OP_COPY_I, 0, dst, valSlot, -1, -1, 0L, 0.0));
                            }
                        }
                    } else if (s instanceof ConceptStmt.Return r && r.value() != null) {
                        int valSlot = emitExpr(r.value());
                        insns.add(new TacInsn(OP_RET_I, 0, returnSlot, valSlot, -1, -1, 0L, 0.0));
                    } else if (s instanceof ConceptStmt.If i) {
                        hasJumps = true;
                        cseTable.clear();
                        int condSlot = emitExpr(i.condition());
                        int jzIdx = insns.size();
                        insns.add(new TacInsn(OP_JZ, 0, -1, condSlot, -1, -1, 0L, 0.0));
                        branchDepth++;
                        emitStmts(i.thenBody());
                        int jmpIdx = insns.size();
                        insns.add(new TacInsn(OP_JMP, 0, -1, -1, -1, -1, 0L, 0.0));
                        int elseStart = insns.size();
                        insns.set(jzIdx, new TacInsn(OP_JZ, 0, elseStart, condSlot, -1, -1, 0L, 0.0));
                        cseTable.clear();
                        emitStmts(i.elseBody());
                        branchDepth--;
                        int endPc = insns.size();
                        insns.set(jmpIdx, new TacInsn(OP_JMP, 0, endPc, -1, -1, -1, 0L, 0.0));
                        cseTable.clear();
                    }
                }
            }

            private int emitCseInsn(
                    int op,
                    int subOp,
                    int srcA,
                    int srcB,
                    int srcC,
                    long longImm,
                    double doubleImm) {
                CseKey key = new CseKey(
                        op,
                        subOp,
                        srcA,
                        srcB,
                        srcC,
                        longImm,
                        Double.doubleToRawLongBits(doubleImm));
                Integer existing = cseTable.get(key);
                if (existing != null) {
                    return existing;
                }
                int dst = nextSlot++;
                if (op == OP_CONST_I) {
                    constISlots.put(dst, longImm);
                } else if (op == OP_CONST_F) {
                    constFSlots.put(dst, doubleImm);
                } else {
                    insns.add(new TacInsn(op, subOp, dst, srcA, srcB, srcC, longImm, doubleImm));
                }
                cseTable.put(key, dst);
                return dst;
            }

            private int emitExpr(ConceptExpr expr) {
                if (expr instanceof ConceptExpr.Var v) {
                    Integer slot = namedSlots.get(v.name());
                    if (slot != null) {
                        return slot;
                    }
                    int fallback = emitCseInsn(
                            v.type().isFloatingPoint() ? OP_CONST_F : OP_CONST_I, 0, -1, -1, -1, 0L, 0.0);
                    namedSlots.put(v.name(), fallback);
                    return fallback;
                }
                if (expr instanceof ConceptExpr.Call c) {
                    return emitCseInsn(
                            c.returnType().isFloatingPoint() ? OP_CONST_F : OP_CONST_I, 0, -1, -1, -1, 0L, 0.0);
                }
                if (expr instanceof ConceptExpr.IntConst ic) {
                    return emitCseInsn(OP_CONST_I, 0, -1, -1, -1, ic.value(), 0.0);
                }
                if (expr instanceof ConceptExpr.LongConst lc) {
                    return emitCseInsn(OP_CONST_I, 0, -1, -1, -1, lc.value(), 0.0);
                }
                if (expr instanceof ConceptExpr.FloatConst fc) {
                    return emitCseInsn(OP_CONST_F, 0, -1, -1, -1, 0L, fc.value());
                }
                if (expr instanceof ConceptExpr.DoubleConst dc) {
                    return emitCseInsn(OP_CONST_F, 0, -1, -1, -1, 0L, dc.value());
                }
                if (expr instanceof ConceptExpr.BoolConst bc) {
                    return emitCseInsn(OP_CONST_I, 0, -1, -1, -1, bc.value() ? 1L : 0L, 0.0);
                }
                if (expr instanceof ConceptExpr.Cast c) {
                    int s = emitExpr(c.operand());
                    boolean srcFp = c.operand().type().isFloatingPoint();
                    int op = switch (c.targetType()) {
                        case I32 -> srcFp ? OP_CAST_F2I32 : OP_CAST_I64_TO_I32;
                        case I64 -> srcFp ? OP_CAST_F2I64 : OP_CAST_I_TO_I64;
                        case F32 -> srcFp ? OP_CAST_F64_TO_F32 : OP_CAST_I2F;
                        default -> srcFp ? OP_COPY_F : OP_CAST_I2F;
                    };
                    return emitCseInsn(op, 0, s, -1, -1, 0L, 0.0);
                }
                if (expr instanceof ConceptExpr.UnaryOp u) {
                    int s = emitExpr(u.operand());
                    int op = switch (u.op()) {
                        case NEG -> u.type().isFloatingPoint()
                                ? OP_NEG_F
                                : (u.type() == ConceptType.I32 ? OP_NEG_I32 : OP_NEG_I64);
                        case LOGICAL_NOT -> OP_NOT_BOOL;
                        case BIT_NOT -> OP_BIT_NOT_I;
                    };
                    return emitCseInsn(op, 0, s, -1, -1, 0L, 0.0);
                }
                if (expr instanceof ConceptExpr.BinaryOp b) {
                    int la = emitExpr(b.left());
                    int ra = emitExpr(b.right());
                    boolean fp = b.left().type().isFloatingPoint() || b.right().type().isFloatingPoint();
                    int subOp = b.op().ordinal();
                    if (b.op().isComparison()) {
                        boolean cmpFp = fp
                                && b.op() != BinaryOperator.LOGICAL_AND
                                && b.op() != BinaryOperator.LOGICAL_OR;
                        return emitCseInsn(cmpFp ? OP_CMP_F : OP_CMP_I, subOp, la, ra, -1, 0L, 0.0);
                    }
                    if (fp) {
                        return emitCseInsn(OP_BIN_F, subOp, la, ra, -1, 0L, 0.0);
                    }
                    boolean isI32 = b.type() == ConceptType.I32
                            || (b.left().type() == ConceptType.I32 && b.right().type() == ConceptType.I32);
                    return emitCseInsn(isI32 ? OP_BIN_I32 : OP_BIN_I64, subOp, la, ra, -1, 0L, 0.0);
                }
                if (expr instanceof ConceptExpr.Ternary t) {
                    int cond = emitExpr(t.condition());
                    int tv = emitExpr(t.thenExpr());
                    int ev = emitExpr(t.elseExpr());
                    int op = t.type().isFloatingPoint() ? OP_TERNARY_F : OP_TERNARY_I;
                    return emitCseInsn(op, 0, cond, tv, ev, 0L, 0.0);
                }
                if (expr instanceof ConceptExpr.LutLoad l) {
                    int lutIdx = lutMap.getOrDefault(l.lutSymbol(), 0);
                    int idxSlot = emitExpr(l.index());
                    return emitCseInsn(OP_LUT_LOAD, lutIdx, idxSlot, -1, -1, 0L, 0.0);
                }
                if (expr instanceof ConceptExpr.MathIntrinsic m) {
                    int a0 = emitExpr(m.arguments().get(0));
                    int a1 = m.arguments().size() > 1 ? emitExpr(m.arguments().get(1)) : -1;
                    int a2 = m.arguments().size() > 2 ? emitExpr(m.arguments().get(2)) : -1;
                    return emitCseInsn(OP_MATH, m.kind().ordinal(), a0, a1, a2, 0L, 0.0);
                }
                throw new IllegalStateException("Unsupported expression in TacBuilder: " + expr);
            }
        }
    }

    /**
     * Synthesizes an in-memory JVM {@code .class} (ClassFile v49) implementing
     * {@link NativeVoxelEvaluator} from an optimized {@link ConceptModule} and loads it via
     * {@link MethodHandles.Lookup#defineHiddenClass(byte[], boolean)}.
     */
    static final class JvmBytecodeKernelCompiler {

        private JvmBytecodeKernelCompiler() {
        }

        private record HelperCompiledMethod(
                int methodRefIdx,
                int nameUtf8,
                int descUtf8,
                ConceptType returnType,
                List<ConceptType> paramTypes,
                byte[] bytecode,
                int maxLocals
        ) {
            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (!(o instanceof HelperCompiledMethod other)) {
                    return false;
                }
                return this.methodRefIdx == other.methodRefIdx
                        && this.nameUtf8 == other.nameUtf8
                        && this.descUtf8 == other.descUtf8
                        && java.util.Objects.equals(this.returnType, other.returnType)
                        && java.util.Objects.equals(this.paramTypes, other.paramTypes)
                        && java.util.Arrays.equals(this.bytecode, other.bytecode)
                        && this.maxLocals == other.maxLocals;
            }

            @Override
            public int hashCode() {
                int result = 1;
                result = 31 * result + this.methodRefIdx;
                result = 31 * result + this.nameUtf8;
                result = 31 * result + this.descUtf8;
                result = 31 * result + java.util.Objects.hashCode(this.returnType);
                result = 31 * result + java.util.Objects.hashCode(this.paramTypes);
                result = 31 * result + java.util.Arrays.hashCode(this.bytecode);
                result = 31 * result + this.maxLocals;
                return result;
            }

            @Override
            public String toString() {
                return "HelperCompiledMethod[methodRefIdx=" + this.methodRefIdx
                        + ", nameUtf8=" + this.nameUtf8
                        + ", descUtf8=" + this.descUtf8
                        + ", returnType=" + this.returnType
                        + ", paramTypes=" + this.paramTypes
                        + ", bytecode=" + java.util.Arrays.toString(this.bytecode)
                        + ", maxLocals=" + this.maxLocals
                        + "]";
            }
        }

        static NativeVoxelEvaluator tryCompile(ConceptModule module) {
            try {
                byte[] classBytes = compileClassBytes(module);
                if (classBytes == null) {
                    return null;
                }
                MethodHandles.Lookup hiddenLookup = MethodHandles.lookup()
                        .defineHiddenClass(classBytes, true);
                return (NativeVoxelEvaluator) hiddenLookup.lookupClass()
                        .getDeclaredConstructor()
                        .newInstance();
            } catch (Throwable t) {
                return null;
            }
        }

        private static String jvmTypeDescriptor(ConceptType type) {
            return switch (normType(type)) {
                case I64 -> "J";
                case F32 -> "F";
                case F64 -> "D";
                default -> "I";
            };
        }

        static byte[] compileClassBytes(ConceptModule module) throws IOException {
            ConceptFunction fn = module.entryFunction();
            CpPool cp = new CpPool();

            String thisClassName = "io/github/dailystruggle/bytecode/transpile/WorldgenCompiledKernel";
            String superClassName = "java/lang/Object";
            String ifaceClassName = "io/github/dailystruggle/bytecode/transpile/WorldgenKernelSwapper$NativeVoxelEvaluator";

            int thisClassIdx = cp.classInfo(thisClassName);
            int superClassIdx = cp.classInfo(superClassName);
            int ifaceClassIdx = cp.classInfo(ifaceClassName);
            int codeAttrUtf8 = cp.utf8("Code");
            int initNameUtf8 = cp.utf8("<init>");
            int initDescUtf8 = cp.utf8("()V");
            int objectInitRef = cp.methodRef(superClassName, "<init>", "()V");
            int evalNameUtf8 = cp.utf8("evalVoxel");
            int evalDescUtf8 = cp.utf8("(IIIJ[I)I");

            int runningLutOffset = 0;
            for (ConceptLut lut : module.lookupTables()) {
                cp.lutOffsets.put(lut.symbolName(), runningLutOffset);
                runningLutOffset += lut.values().size();
            }

            Map<String, HelperCompiledMethod> helperMap = new LinkedHashMap<>();
            List<ConceptFunction> helpersList = module.helperFunctions();
            List<String> helperNames = new ArrayList<>(helpersList.size());
            List<String> helperDescs = new ArrayList<>(helpersList.size());
            List<Integer> helperMethodRefs = new ArrayList<>(helpersList.size());
            List<List<ConceptType>> helperParamTypes = new ArrayList<>(helpersList.size());

            int helperIdx = 0;
            for (ConceptFunction helper : helpersList) {
                String helperName = "helper_" + (helperIdx++) + "_" + helper.name().replace('$', '_');
                StringBuilder descSb = new StringBuilder("(");
                List<ConceptType> pTypes = new ArrayList<>(helper.parameters().size());
                for (ConceptParam hp : helper.parameters()) {
                    ConceptType pt = normType(hp.type());
                    pTypes.add(pt);
                    descSb.append(jvmTypeDescriptor(pt));
                }
                descSb.append("[I)").append(jvmTypeDescriptor(helper.returnType()));
                String hDesc = descSb.toString();
                int hNameUtf8 = cp.utf8(helperName);
                int hDescUtf8 = cp.utf8(hDesc);
                int hMethodRef = cp.methodRef(thisClassName, helperName, hDesc);
                ConceptType hRetType = normType(helper.returnType());

                helperNames.add(helperName);
                helperDescs.add(hDesc);
                helperMethodRefs.add(hMethodRef);
                helperParamTypes.add(pTypes);

                HelperCompiledMethod stub = new HelperCompiledMethod(
                        hMethodRef, hNameUtf8, hDescUtf8, hRetType, pTypes, new byte[0], 0);
                helperMap.put(helper.key(), stub);
                helperMap.putIfAbsent(helper.name(), stub);
            }

            List<HelperCompiledMethod> compiledHelpers = new ArrayList<>(helpersList.size());
            for (int i = 0; i < helpersList.size(); i++) {
                ConceptFunction helper = helpersList.get(i);
                String helperName = helperNames.get(i);
                String hDesc = helperDescs.get(i);
                int hMethodRef = helperMethodRefs.get(i);
                List<ConceptType> pTypes = helperParamTypes.get(i);

                Map<String, Integer> hLocalSlots = new LinkedHashMap<>();
                Map<String, ConceptType> hVarTypes = new LinkedHashMap<>(helper.localTypes());
                int[] hNextLocal = {0};
                for (ConceptParam hp : helper.parameters()) {
                    ConceptType pt = normType(hp.type());
                    hLocalSlots.put(hp.name(), hNextLocal[0]);
                    hVarTypes.put(hp.name(), pt);
                    hNextLocal[0] += pt.isWideJvmSlot() ? 2 : 1;
                }
                int hLutSlot = hNextLocal[0]++;

                for (Map.Entry<String, ConceptType> e : helper.localTypes().entrySet()) {
                    if (!hLocalSlots.containsKey(e.getKey())) {
                        hLocalSlots.put(e.getKey(), hNextLocal[0]);
                        hNextLocal[0] += e.getValue().isWideJvmSlot() ? 2 : 1;
                    }
                }
                allocateMissingLocals(helper.body(), hLocalSlots, hVarTypes, hNextLocal);

                int hNameUtf8 = cp.utf8(helperName);
                int hDescUtf8 = cp.utf8(hDesc);
                CodeBuf hCode = new CodeBuf();
                ConceptType hRetType = normType(helper.returnType());
                emitStmts(hCode, cp, helper.body(), hLocalSlots, hVarTypes, hLutSlot, hRetType, helperMap);
                emitDefaultReturn(hCode, hRetType);
                byte[] hBytes = hCode.toByteArray();
                if (hBytes.length > 7500) {
                    return null;
                }
                HelperCompiledMethod compiled = new HelperCompiledMethod(
                        hMethodRef, hNameUtf8, hDescUtf8, hRetType, pTypes, hBytes, hNextLocal[0]);
                compiledHelpers.add(compiled);
                helperMap.put(helper.key(), compiled);
                helperMap.putIfAbsent(helper.name(), compiled);
            }

            Map<String, Integer> localSlots = new LinkedHashMap<>();
            Map<String, ConceptType> varTypes = new LinkedHashMap<>(fn.localTypes());
            localSlots.put("world_x", 1);
            localSlots.put("world_y", 2);
            localSlots.put("world_z", 3);
            localSlots.put("world_seed", 4);
            varTypes.put("world_x", ConceptType.I32);
            varTypes.put("world_y", ConceptType.I32);
            varTypes.put("world_z", ConceptType.I32);
            varTypes.put("world_seed", ConceptType.I64);
            int evalLutSlot = 6;
            int[] nextLocal = {7}; // slot 6 is int[] lut0

            for (Map.Entry<String, ConceptType> e : fn.localTypes().entrySet()) {
                if (!localSlots.containsKey(e.getKey())) {
                    localSlots.put(e.getKey(), nextLocal[0]);
                    nextLocal[0] += e.getValue().isWideJvmSlot() ? 2 : 1;
                }
            }
            allocateMissingLocals(fn.body(), localSlots, varTypes, nextLocal);

            CodeBuf evalCode = new CodeBuf();
            emitStmts(evalCode, cp, fn.body(), localSlots, varTypes, evalLutSlot, ConceptType.I32, helperMap);
            evalCode.write(0x03); // iconst_0 fallback
            evalCode.write(0xAC); // ireturn
            byte[] evalBytecode = evalCode.toByteArray();
            if (evalBytecode.length > 7500) {
                return null;
            }

            ByteArrayOutputStream outBytes = new ByteArrayOutputStream(evalBytecode.length + 1024);
            DataOutputStream out = new DataOutputStream(outBytes);
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(49); // Java 5 classfile format (no StackMapTable required)

            cp.writeTo(out);

            out.writeShort(0x0001 | 0x0010 | 0x0020); // ACC_PUBLIC | ACC_FINAL | ACC_SUPER
            out.writeShort(thisClassIdx);
            out.writeShort(superClassIdx);
            out.writeShort(1);
            out.writeShort(ifaceClassIdx);

            out.writeShort(0); // fields_count = 0
            out.writeShort(2 + compiledHelpers.size()); // methods_count

            // Method 1: public <init>()V
            out.writeShort(0x0001);
            out.writeShort(initNameUtf8);
            out.writeShort(initDescUtf8);
            out.writeShort(1);
            out.writeShort(codeAttrUtf8);
            out.writeInt(17);
            out.writeShort(1);
            out.writeShort(1);
            out.writeInt(5);
            out.writeByte(0x2A); // aload_0
            out.writeByte(0xB7); // invokespecial
            out.writeShort(objectInitRef);
            out.writeByte(0xB1); // return
            out.writeShort(0);
            out.writeShort(0);

            // Method 2: public int evalVoxel(int, int, int, long, int[])
            out.writeShort(0x0001);
            out.writeShort(evalNameUtf8);
            out.writeShort(evalDescUtf8);
            out.writeShort(1);
            out.writeShort(codeAttrUtf8);
            out.writeInt(12 + evalBytecode.length);
            out.writeShort(64); // max_stack
            out.writeShort(nextLocal[0]); // max_locals
            out.writeInt(evalBytecode.length);
            out.write(evalBytecode);
            out.writeShort(0);
            out.writeShort(0);

            // Helper methods: private static helper_N(...)
            for (HelperCompiledMethod hm : compiledHelpers) {
                out.writeShort(0x0002 | 0x0008); // ACC_PRIVATE | ACC_STATIC
                out.writeShort(hm.nameUtf8());
                out.writeShort(hm.descUtf8());
                out.writeShort(1);
                out.writeShort(codeAttrUtf8);
                out.writeInt(12 + hm.bytecode().length);
                out.writeShort(64);
                out.writeShort(hm.maxLocals());
                out.writeInt(hm.bytecode().length);
                out.write(hm.bytecode());
                out.writeShort(0);
                out.writeShort(0);
            }

            out.writeShort(0); // class attributes_count = 0
            out.flush();
            return outBytes.toByteArray();
        }

        private static void emitDefaultReturn(CodeBuf code, ConceptType retType) {
            switch (normType(retType)) {
                case I64 -> {
                    code.write(0x09); // lconst_0
                    code.write(0xAD); // lreturn
                }
                case F32 -> {
                    code.write(0x0B); // fconst_0
                    code.write(0xAE); // freturn
                }
                case F64 -> {
                    code.write(0x0E); // dconst_0
                    code.write(0xAF); // dreturn
                }
                default -> {
                    code.write(0x03); // iconst_0
                    code.write(0xAC); // ireturn
                }
            }
        }

        private static void allocateMissingLocals(
                List<ConceptStmt> stmts,
                Map<String, Integer> localSlots,
                Map<String, ConceptType> varTypes,
                int[] nextLocal) {
            for (ConceptStmt s : stmts) {
                if (s instanceof ConceptStmt.Assign a) {
                    varTypes.putIfAbsent(a.name(), a.type());
                    if (!localSlots.containsKey(a.name())) {
                        localSlots.put(a.name(), nextLocal[0]);
                        nextLocal[0] += a.type().isWideJvmSlot() ? 2 : 1;
                    }
                } else if (s instanceof ConceptStmt.VarDecl v) {
                    varTypes.putIfAbsent(v.name(), v.type());
                    if (!localSlots.containsKey(v.name())) {
                        localSlots.put(v.name(), nextLocal[0]);
                        nextLocal[0] += v.type().isWideJvmSlot() ? 2 : 1;
                    }
                } else if (s instanceof ConceptStmt.If i) {
                    allocateMissingLocals(i.thenBody(), localSlots, varTypes, nextLocal);
                    allocateMissingLocals(i.elseBody(), localSlots, varTypes, nextLocal);
                } else if (s instanceof ConceptStmt.While w) {
                    allocateMissingLocals(w.body(), localSlots, varTypes, nextLocal);
                }
            }
        }

        private static void emitStmts(
                CodeBuf code,
                CpPool cp,
                List<ConceptStmt> stmts,
                Map<String, Integer> localSlots,
                Map<String, ConceptType> varTypes,
                int lutSlot,
                ConceptType fnReturnType,
                Map<String, HelperCompiledMethod> helperMap) {
            for (ConceptStmt s : stmts) {
                if (s instanceof ConceptStmt.Assign a) {
                    ConceptType targetType = normType(a.type());
                    emitExprAs(code, cp, a.value(), targetType, localSlots, varTypes, lutSlot, helperMap);
                    emitStore(code, targetType, localSlots.get(a.name()));
                } else if (s instanceof ConceptStmt.Return r && r.value() != null) {
                    ConceptType retNorm = normType(fnReturnType);
                    emitExprAs(code, cp, r.value(), retNorm, localSlots, varTypes, lutSlot, helperMap);
                    code.write(switch (retNorm) {
                        case I64 -> 0xAD; // lreturn
                        case F32 -> 0xAE; // freturn
                        case F64 -> 0xAF; // dreturn
                        default -> 0xAC; // ireturn
                    });
                } else if (s instanceof ConceptStmt.If i) {
                    emitExprAs(code, cp, i.condition(), ConceptType.I32, localSlots, varTypes, lutSlot, helperMap);
                    int ifeqPc = code.size();
                    code.write(0x99); // ifeq
                    code.writeShort(0);
                    emitStmts(code, cp, i.thenBody(), localSlots, varTypes, lutSlot, fnReturnType, helperMap);
                    if (i.elseBody().isEmpty()) {
                        code.patchShort(ifeqPc + 1, code.size() - ifeqPc);
                    } else {
                        int gotoPc = code.size();
                        code.write(0xA7); // goto
                        code.writeShort(0);
                        code.patchShort(ifeqPc + 1, code.size() - ifeqPc);
                        emitStmts(code, cp, i.elseBody(), localSlots, varTypes, lutSlot, fnReturnType, helperMap);
                        code.patchShort(gotoPc + 1, code.size() - gotoPc);
                    }
                }
            }
        }

        private static void emitExprAs(
                CodeBuf code,
                CpPool cp,
                ConceptExpr expr,
                ConceptType expectedType,
                Map<String, Integer> localSlots,
                Map<String, ConceptType> varTypes,
                int lutSlot,
                Map<String, HelperCompiledMethod> helperMap) {
            ConceptType actual = emitExpr(code, cp, expr, localSlots, varTypes, lutSlot, helperMap);
            emitCoercion(code, actual, normType(expectedType));
        }

        private static ConceptType emitExpr(
                CodeBuf code,
                CpPool cp,
                ConceptExpr expr,
                Map<String, Integer> localSlots,
                Map<String, ConceptType> varTypes,
                int lutSlot,
                Map<String, HelperCompiledMethod> helperMap) {
            if (expr instanceof ConceptExpr.Var v) {
                int slot = localSlots.get(v.name());
                ConceptType t = normType(varTypes.getOrDefault(v.name(), v.type()));
                emitLoad(code, t, slot);
                return t;
            }
            if (expr instanceof ConceptExpr.IntConst ic) {
                int idx = cp.intConst(ic.value());
                code.write(0x13); // ldc_w
                code.writeShort(idx);
                return ConceptType.I32;
            }
            if (expr instanceof ConceptExpr.LongConst lc) {
                int idx = cp.longConst(lc.value());
                code.write(0x14); // ldc2_w
                code.writeShort(idx);
                return ConceptType.I64;
            }
            if (expr instanceof ConceptExpr.FloatConst fc) {
                int idx = cp.floatConst(fc.value());
                code.write(0x13); // ldc_w
                code.writeShort(idx);
                return ConceptType.F32;
            }
            if (expr instanceof ConceptExpr.DoubleConst dc) {
                int idx = cp.doubleConst(dc.value());
                code.write(0x14); // ldc2_w
                code.writeShort(idx);
                return ConceptType.F64;
            }
            if (expr instanceof ConceptExpr.BoolConst bc) {
                code.write(bc.value() ? 0x04 : 0x03); // iconst_1 / iconst_0
                return ConceptType.I32;
            }
            if (expr instanceof ConceptExpr.Cast c) {
                ConceptType target = normType(c.targetType());
                emitExprAs(code, cp, c.operand(), target, localSlots, varTypes, lutSlot, helperMap);
                return target;
            }
            if (expr instanceof ConceptExpr.UnaryOp u) {
                ConceptType t = normType(u.type());
                emitExprAs(code, cp, u.operand(), t, localSlots, varTypes, lutSlot, helperMap);
                switch (u.op()) {
                    case NEG -> code.write(switch (t) {
                        case I32 -> 0x74; // ineg
                        case I64 -> 0x75; // lneg
                        case F32 -> 0x76; // fneg
                        default -> 0x77; // dneg
                    });
                    case LOGICAL_NOT -> {
                        code.write(0x04); // iconst_1
                        code.write(0x82); // ixor
                        return ConceptType.I32;
                    }
                    case BIT_NOT -> {
                        if (t == ConceptType.I64) {
                            int idx = cp.longConst(-1L);
                            code.write(0x14);
                            code.writeShort(idx);
                            code.write(0x83); // lxor
                        } else {
                            code.write(0x02); // iconst_m1
                            code.write(0x82); // ixor
                        }
                    }
                }
                return t;
            }
            if (expr instanceof ConceptExpr.LutLoad l) {
                emitVarInsn(code, 0x19, lutSlot); // aload lutSlot
                emitExprAs(code, cp, l.index(), ConceptType.I32, localSlots, varTypes, lutSlot, helperMap);
                int lutOff = cp.lutOffsets.getOrDefault(l.lutSymbol(), 0);
                if (lutOff != 0) {
                    int idx = cp.intConst(lutOff);
                    code.write(0x13); // ldc_w
                    code.writeShort(idx);
                    code.write(0x60); // iadd
                }
                code.write(0x2E); // iaload
                return ConceptType.I32;
            }
            if (expr instanceof ConceptExpr.Ternary t) {
                ConceptType resType = normType(t.type());
                emitExprAs(code, cp, t.condition(), ConceptType.I32, localSlots, varTypes, lutSlot, helperMap);
                int ifeqPc = code.size();
                code.write(0x99); // ifeq
                code.writeShort(0);
                emitExprAs(code, cp, t.thenExpr(), resType, localSlots, varTypes, lutSlot, helperMap);
                int gotoPc = code.size();
                code.write(0xA7); // goto
                code.writeShort(0);
                code.patchShort(ifeqPc + 1, code.size() - ifeqPc);
                emitExprAs(code, cp, t.elseExpr(), resType, localSlots, varTypes, lutSlot, helperMap);
                code.patchShort(gotoPc + 1, code.size() - gotoPc);
                return resType;
            }
            if (expr instanceof ConceptExpr.BinaryOp b) {
                return emitBinaryOp(code, cp, b, localSlots, varTypes, lutSlot, helperMap);
            }
            if (expr instanceof ConceptExpr.MathIntrinsic m) {
                return emitMathIntrinsic(code, cp, m, localSlots, varTypes, lutSlot, helperMap);
            }
            if (expr instanceof ConceptExpr.Call call) {
                HelperCompiledMethod hm = helperMap.getOrDefault(
                        call.targetKey(), helperMap.get(call.methodName()));
                if (hm == null) {
                    throw new IllegalStateException("Unresolved helper call in JvmBytecodeKernelCompiler: " + call.targetKey());
                }
                List<ConceptExpr> args = call.arguments();
                for (int i = 0; i < args.size(); i++) {
                    emitExprAs(code, cp, args.get(i), hm.paramTypes().get(i), localSlots, varTypes, lutSlot, helperMap);
                }
                emitVarInsn(code, 0x19, lutSlot); // aload lutSlot
                code.write(0xB8); // invokestatic
                code.writeShort(hm.methodRefIdx());
                return hm.returnType();
            }
            throw new IllegalStateException("Unsupported expr in JvmBytecodeKernelCompiler: " + expr);
        }

        private static ConceptType emitBinaryOp(
                CodeBuf code,
                CpPool cp,
                ConceptExpr.BinaryOp b,
                Map<String, Integer> localSlots,
                Map<String, ConceptType> varTypes,
                int lutSlot,
                Map<String, HelperCompiledMethod> helperMap) {
            BinaryOperator op = b.op();
            if (op == BinaryOperator.LOGICAL_AND) {
                emitExprAs(code, cp, b.left(), ConceptType.I32, localSlots, varTypes, lutSlot, helperMap);
                int jz1 = code.size();
                code.write(0x99);
                code.writeShort(0);
                emitExprAs(code, cp, b.right(), ConceptType.I32, localSlots, varTypes, lutSlot, helperMap);
                int jz2 = code.size();
                code.write(0x99);
                code.writeShort(0);
                code.write(0x04); // iconst_1
                int gotoEnd = code.size();
                code.write(0xA7);
                code.writeShort(0);
                int falsePc = code.size();
                code.patchShort(jz1 + 1, falsePc - jz1);
                code.patchShort(jz2 + 1, falsePc - jz2);
                code.write(0x03); // iconst_0
                code.patchShort(gotoEnd + 1, code.size() - gotoEnd);
                return ConceptType.I32;
            }
            if (op == BinaryOperator.LOGICAL_OR) {
                emitExprAs(code, cp, b.left(), ConceptType.I32, localSlots, varTypes, lutSlot, helperMap);
                int jnz1 = code.size();
                code.write(0x9A); // ifne
                code.writeShort(0);
                emitExprAs(code, cp, b.right(), ConceptType.I32, localSlots, varTypes, lutSlot, helperMap);
                int jnz2 = code.size();
                code.write(0x9A); // ifne
                code.writeShort(0);
                code.write(0x03); // iconst_0
                int gotoEnd = code.size();
                code.write(0xA7);
                code.writeShort(0);
                int truePc = code.size();
                code.patchShort(jnz1 + 1, truePc - jnz1);
                code.patchShort(jnz2 + 1, truePc - jnz2);
                code.write(0x04); // iconst_1
                code.patchShort(gotoEnd + 1, code.size() - gotoEnd);
                return ConceptType.I32;
            }
            if (op.isComparison()) {
                ConceptType lt = normType(b.left().type());
                ConceptType rt = normType(b.right().type());
                ConceptType cmpType = (lt == ConceptType.F64 || rt == ConceptType.F64) ? ConceptType.F64
                        : (lt == ConceptType.F32 || rt == ConceptType.F32) ? ConceptType.F32
                        : (lt == ConceptType.I64 || rt == ConceptType.I64) ? ConceptType.I64
                        : ConceptType.I32;
                emitExprAs(code, cp, b.left(), cmpType, localSlots, varTypes, lutSlot, helperMap);
                emitExprAs(code, cp, b.right(), cmpType, localSlots, varTypes, lutSlot, helperMap);
                int jumpTrueOpcode;
                if (cmpType == ConceptType.I32) {
                    jumpTrueOpcode = switch (op) {
                        case EQ -> 0x9F; // if_icmpeq
                        case NE -> 0xA0; // if_icmpne
                        case LT -> 0xA1; // if_icmplt
                        case GE -> 0xA2; // if_icmpge
                        case GT -> 0xA3; // if_icmpgt
                        case LE -> 0xA4; // if_icmple
                        default -> 0x9F;
                    };
                } else {
                    if (cmpType == ConceptType.I64) {
                        code.write(0x94); // lcmp
                    } else if (cmpType == ConceptType.F32) {
                        code.write(0x95); // fcmpl
                    } else {
                        code.write(0x97); // dcmpl
                    }
                    jumpTrueOpcode = switch (op) {
                        case EQ -> 0x99; // ifeq
                        case NE -> 0x9A; // ifne
                        case LT -> 0x9B; // iflt
                        case GE -> 0x9C; // ifge
                        case GT -> 0x9D; // ifgt
                        case LE -> 0x9E; // ifle
                        default -> 0x99;
                    };
                }
                int jTrue = code.size();
                code.write(jumpTrueOpcode);
                code.writeShort(0);
                code.write(0x03); // iconst_0
                int gotoEnd = code.size();
                code.write(0xA7);
                code.writeShort(0);
                code.patchShort(jTrue + 1, code.size() - jTrue);
                code.write(0x04); // iconst_1
                code.patchShort(gotoEnd + 1, code.size() - gotoEnd);
                return ConceptType.I32;
            }

            ConceptType resType = normType(b.type());
            if (resType == ConceptType.I32
                    && (normType(b.left().type()) == ConceptType.I64
                            || normType(b.right().type()) == ConceptType.I64)
                    && op != BinaryOperator.SHL
                    && op != BinaryOperator.SHR
                    && op != BinaryOperator.USHR) {
                resType = ConceptType.I64;
            }
            emitExprAs(code, cp, b.left(), resType, localSlots, varTypes, lutSlot, helperMap);
            if (op == BinaryOperator.SHL || op == BinaryOperator.SHR || op == BinaryOperator.USHR) {
                emitExprAs(code, cp, b.right(), ConceptType.I32, localSlots, varTypes, lutSlot, helperMap);
            } else {
                emitExprAs(code, cp, b.right(), resType, localSlots, varTypes, lutSlot, helperMap);
            }

            int arithOpcode = switch (resType) {
                case F64 -> switch (op) {
                    case ADD -> 0x63; // dadd
                    case SUB -> 0x67; // dsub
                    case MUL -> 0x6B; // dmul
                    case DIV -> 0x6F; // ddiv
                    case REM -> 0x73; // drem
                    default -> throw new IllegalStateException("Bad F64 op: " + op);
                };
                case F32 -> switch (op) {
                    case ADD -> 0x62; // fadd
                    case SUB -> 0x66; // fsub
                    case MUL -> 0x6A; // fmul
                    case DIV -> 0x6E; // fdiv
                    case REM -> 0x72; // frem
                    default -> throw new IllegalStateException("Bad F32 op: " + op);
                };
                case I64 -> switch (op) {
                    case ADD -> 0x61; // ladd
                    case SUB -> 0x65; // lsub
                    case MUL -> 0x69; // lmul
                    case DIV -> 0x6D; // ldiv
                    case REM -> 0x71; // lrem
                    case BIT_AND -> 0x7F; // land
                    case BIT_OR -> 0x81; // lor
                    case BIT_XOR -> 0x83; // lxor
                    case SHL -> 0x79; // lshl
                    case SHR -> 0x7B; // lshr
                    case USHR -> 0x7D; // lushr
                    default -> throw new IllegalStateException("Bad I64 op: " + op);
                };
                default -> switch (op) {
                    case ADD -> 0x60; // iadd
                    case SUB -> 0x64; // isub
                    case MUL -> 0x68; // imul
                    case DIV -> 0x6C; // idiv
                    case REM -> 0x70; // irem
                    case BIT_AND -> 0x7E; // iand
                    case BIT_OR -> 0x80; // ior
                    case BIT_XOR -> 0x82; // ixor
                    case SHL -> 0x78; // ishl
                    case SHR -> 0x7A; // ishr
                    case USHR -> 0x7C; // iushr
                    default -> throw new IllegalStateException("Bad I32 op: " + op);
                };
            };
            code.write(arithOpcode);
            return resType;
        }

        private static ConceptType emitMathIntrinsic(
                CodeBuf code,
                CpPool cp,
                ConceptExpr.MathIntrinsic m,
                Map<String, Integer> localSlots,
                Map<String, ConceptType> varTypes,
                int lutSlot,
                Map<String, HelperCompiledMethod> helperMap) {
            if (m.kind() == MathIntrinsicKind.POPCOUNT
                    || m.kind() == MathIntrinsicKind.CLZ
                    || m.kind() == MathIntrinsicKind.CTZ) {
                emitExprAs(code, cp, m.arguments().get(0), ConceptType.I64, localSlots, varTypes, lutSlot, helperMap);
                String bitMethod = switch (m.kind()) {
                    case POPCOUNT -> "bitCount";
                    case CLZ -> "numberOfLeadingZeros";
                    default -> "numberOfTrailingZeros";
                };
                int mref = cp.methodRef("java/lang/Long", bitMethod, "(J)I");
                code.write(0xB8);
                code.writeShort(mref);
                return ConceptType.I32;
            }
            for (ConceptExpr arg : m.arguments()) {
                emitExprAs(code, cp, arg, ConceptType.F64, localSlots, varTypes, lutSlot, helperMap);
            }
            if (m.kind() == MathIntrinsicKind.IS_FINITE) {
                int mref = cp.methodRef("java/lang/Double", "isFinite", "(D)Z");
                code.write(0xB8);
                code.writeShort(mref);
                return ConceptType.I32;
            }
            if (m.kind() == MathIntrinsicKind.IS_NAN) {
                int mref = cp.methodRef("java/lang/Double", "isNaN", "(D)Z");
                code.write(0xB8);
                code.writeShort(mref);
                return ConceptType.I32;
            }
            String methodName = switch (m.kind()) {
                case FLOOR -> "floor";
                case CEIL -> "ceil";
                case ROUND -> "rint";
                case SQRT -> "sqrt";
                case ABS -> "abs";
                case SIN -> "sin";
                case COS -> "cos";
                case TAN -> "tan";
                case ATAN2 -> "atan2";
                case EXP -> "exp";
                case LOG -> "log";
                case POW -> "pow";
                case MIN -> "min";
                case MAX -> "max";
                case FMA -> "fma";
                default -> "floor";
            };
            String desc = switch (m.arguments().size()) {
                case 1 -> "(D)D";
                case 2 -> "(DD)D";
                default -> "(DDD)D";
            };
            int mref = cp.methodRef("java/lang/Math", methodName, desc);
            code.write(0xB8); // invokestatic
            code.writeShort(mref);
            return ConceptType.F64;
        }

        private static ConceptType normType(ConceptType t) {
            return t == ConceptType.BOOL ? ConceptType.I32 : t;
        }

        private static void emitCoercion(CodeBuf code, ConceptType from, ConceptType to) {
            ConceptType f = normType(from);
            ConceptType target = normType(to);
            if (f == target) {
                return;
            }
            switch (f) {
                case I32 -> code.write(switch (target) {
                    case I64 -> 0x85; // i2l
                    case F32 -> 0x86; // i2f
                    default -> 0x87; // i2d
                });
                case I64 -> code.write(switch (target) {
                    case I32 -> 0x88; // l2i
                    case F32 -> 0x89; // l2f
                    default -> 0x8A; // l2d
                });
                case F32 -> code.write(switch (target) {
                    case I32 -> 0x8B; // f2i
                    case I64 -> 0x8C; // f2l
                    default -> 0x8D; // f2d
                });
                default -> code.write(switch (target) {
                    case I32 -> 0x8E; // d2i
                    case I64 -> 0x8F; // d2l
                    default -> 0x90; // d2f
                });
            }
        }

        private static void emitLoad(CodeBuf code, ConceptType type, int slot) {
            int op = switch (normType(type)) {
                case I64 -> 0x16; // lload
                case F32 -> 0x17; // fload
                case F64 -> 0x18; // dload
                default -> 0x15; // iload
            };
            emitVarInsn(code, op, slot);
        }

        private static void emitStore(CodeBuf code, ConceptType type, int slot) {
            int op = switch (normType(type)) {
                case I64 -> 0x37; // lstore
                case F32 -> 0x38; // fstore
                case F64 -> 0x39; // dstore
                default -> 0x36; // istore
            };
            emitVarInsn(code, op, slot);
        }

        private static void emitVarInsn(CodeBuf code, int op, int slot) {
            if (slot <= 255) {
                code.write(op);
                code.write(slot);
            } else {
                code.write(0xC4); // wide
                code.write(op);
                code.writeShort(slot);
            }
        }

        private static final class CodeBuf {
            private byte[] buf = new byte[4096];
            private int size;

            int size() {
                return size;
            }

            void write(int b) {
                ensure(1);
                buf[size++] = (byte) b;
            }

            void writeShort(int s) {
                ensure(2);
                buf[size++] = (byte) (s >>> 8);
                buf[size++] = (byte) s;
            }

            void patchShort(int pos, int s) {
                buf[pos] = (byte) (s >>> 8);
                buf[pos + 1] = (byte) s;
            }

            byte[] toByteArray() {
                return java.util.Arrays.copyOf(buf, size);
            }

            private void ensure(int extra) {
                if (size + extra > buf.length) {
                    buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, size + extra));
                }
            }
        }

        private static final class CpPool {
            private final ByteArrayOutputStream raw = new ByteArrayOutputStream(1024);
            private final DataOutputStream out = new DataOutputStream(raw);
            private final Map<String, Integer> utf8Cache = new LinkedHashMap<>();
            private final Map<Integer, Integer> intCache = new LinkedHashMap<>();
            private final Map<Long, Integer> longCache = new LinkedHashMap<>();
            private final Map<Integer, Integer> floatCache = new LinkedHashMap<>();
            private final Map<Long, Integer> doubleCache = new LinkedHashMap<>();
            private final Map<String, Integer> classCache = new LinkedHashMap<>();
            private final Map<String, Integer> natCache = new LinkedHashMap<>();
            private final Map<String, Integer> mrefCache = new LinkedHashMap<>();
            private final Map<String, Integer> lutOffsets = new LinkedHashMap<>();
            private int nextIndex = 1;

            int utf8(String s) {
                Integer cached = utf8Cache.get(s);
                if (cached != null) {
                    return cached;
                }
                try {
                    int idx = nextIndex++;
                    out.writeByte(1);
                    out.writeUTF(s);
                    utf8Cache.put(s, idx);
                    return idx;
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }

            int intConst(int v) {
                Integer cached = intCache.get(v);
                if (cached != null) {
                    return cached;
                }
                try {
                    int idx = nextIndex++;
                    out.writeByte(3);
                    out.writeInt(v);
                    intCache.put(v, idx);
                    return idx;
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }

            int floatConst(float v) {
                int bits = Float.floatToRawIntBits(v);
                Integer cached = floatCache.get(bits);
                if (cached != null) {
                    return cached;
                }
                try {
                    int idx = nextIndex++;
                    out.writeByte(4);
                    out.writeFloat(v);
                    floatCache.put(bits, idx);
                    return idx;
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }

            int longConst(long v) {
                Integer cached = longCache.get(v);
                if (cached != null) {
                    return cached;
                }
                try {
                    int idx = nextIndex;
                    nextIndex += 2;
                    out.writeByte(5);
                    out.writeLong(v);
                    longCache.put(v, idx);
                    return idx;
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }

            int doubleConst(double v) {
                long bits = Double.doubleToRawLongBits(v);
                Integer cached = doubleCache.get(bits);
                if (cached != null) {
                    return cached;
                }
                try {
                    int idx = nextIndex;
                    nextIndex += 2;
                    out.writeByte(6);
                    out.writeDouble(v);
                    doubleCache.put(bits, idx);
                    return idx;
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }

            int classInfo(String internalName) {
                Integer cached = classCache.get(internalName);
                if (cached != null) {
                    return cached;
                }
                try {
                    int nameIdx = utf8(internalName);
                    int idx = nextIndex++;
                    out.writeByte(7);
                    out.writeShort(nameIdx);
                    classCache.put(internalName, idx);
                    return idx;
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }

            int nameAndType(String name, String desc) {
                String key = name + ":" + desc;
                Integer cached = natCache.get(key);
                if (cached != null) {
                    return cached;
                }
                try {
                    int nIdx = utf8(name);
                    int dIdx = utf8(desc);
                    int idx = nextIndex++;
                    out.writeByte(12);
                    out.writeShort(nIdx);
                    out.writeShort(dIdx);
                    natCache.put(key, idx);
                    return idx;
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }

            int methodRef(String owner, String name, String desc) {
                String key = owner + "#" + name + desc;
                Integer cached = mrefCache.get(key);
                if (cached != null) {
                    return cached;
                }
                try {
                    int cIdx = classInfo(owner);
                    int natIdx = nameAndType(name, desc);
                    int idx = nextIndex++;
                    out.writeByte(10);
                    out.writeShort(cIdx);
                    out.writeShort(natIdx);
                    mrefCache.put(key, idx);
                    return idx;
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }

            void writeTo(DataOutputStream target) throws IOException {
                out.flush();
                target.writeShort(nextIndex);
                raw.writeTo(target);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Bytecode-Lifted Stages K1 -> K2 -> K3 .lca Workgroup Pipeline (leafgen-ADR-002)
    // -------------------------------------------------------------------------

    private static volatile ConceptModule cachedLcaStagesModule;
    private static final Object LCA_STAGE_LIFT_LOCK = new Object();

    /**
     * Pure-Java reference {@code 01:H_3} 3D Hilbert index to {@code YZX} coordinate mapper lifted
     * directly from {@link WorldgenKernelSwapper} bytecode into {@link BytecodeConceptIr}.
     * Uses a 64-bit register-packed 4x4 2D Hilbert table ({@code 0xD98CEFBA67321540L}).
     */
    public static int h3ToYzx(int h3) {
        long lut = 0x3267BFEA9DC84510L;
        int oct6 = (h3 >>> 6) & 63;
        int loc6 = h3 & 63;
        int octY = (oct6 >>> 4) & 3;
        int octXZ = (int) ((lut >>> ((oct6 & 15) << 2)) & 15L);
        int ly = (loc6 >>> 4) & 3;
        int lXZ = (int) ((lut >>> ((loc6 & 15) << 2)) & 15L);
        int x = ((octXZ & 3) << 2) | (lXZ & 3);
        int y = (octY << 2) | ly;
        int z = (((octXZ >>> 2) & 3) << 2) | ((lXZ >>> 2) & 3);
        return (y << 8) | (z << 4) | x;
    }

    /**
     * Pure-Java reference {@code YZX} coordinate to {@code 01:H_3} 3D Hilbert index mapper lifted
     * directly from {@link WorldgenKernelSwapper} bytecode into {@link BytecodeConceptIr}.
     * Uses a 64-bit register-packed inverse 4x4 2D Hilbert table ({@code 0xA965B874CD23FE10L}).
     */
    public static int yzxToH3(int x, int y, int z) {
        long invLut = 0xA965B874CD23FE10L;
        int octIdx = (((z >>> 2) & 3) << 2) | ((x >>> 2) & 3);
        int locIdx = ((z & 3) << 2) | (x & 3);
        int octH2 = (int) ((invLut >>> (octIdx << 2)) & 15L);
        int locH2 = (int) ((invLut >>> (locIdx << 2)) & 15L);
        int oct6 = (((y >>> 2) & 3) << 4) | octH2;
        int loc6 = ((y & 3) << 4) | locH2;
        return (oct6 << 6) | loc6;
    }

    /**
     * Reflected Binary Gray code encoder ({@code G = L ^ (L >>> 1)}) for 4-bit {@code SkyLight} /
     * {@code BlockLight} nibbles ({@code anvil-api-ADR-003} Section 5).
     */
    public static int toGray4(int lightLevel) {
        int clamped = lightLevel & 15;
        return clamped ^ (clamped >>> 1);
    }

    /**
     * Inverse Reflected Binary Gray code decoder ({@code L = G ^ (G >>> 1) ^ (G >>> 2) ^ (G >>> 3)}).
     */
    public static int fromGray4(int gray) {
        int g = gray & 15;
        int g1 = g ^ (g >>> 2);
        return (g1 ^ (g1 >>> 1)) & 15;
    }

    /**
     * Stage K1 6D Climate R-tree LUT biome classifier lifted into OpenCL device code.
     * Maps {@code (temp, humid, cont, erosion, quartY)} into {@code .lca} region biome palette IDs
     * ({@code 0: plains}, {@code 1: forest}, {@code 2: desert}, {@code 3: badlands},
     * {@code 4: snowy_plains}, {@code 5: taiga}, {@code 6: ocean}, {@code 7: dripstone_caves}).
     */
    public static int classifyClimateBiomeQuartet(
            float temp, float humid, float cont, float erosion, int quartY) {
        if (quartY < -4) {
            return erosion > 0.12f ? 7 : 0;
        }
        if (cont < -0.19f) {
            return 6;
        }
        if (temp > 0.50f) {
            return humid < -0.12f ? 2 : 3;
        }
        if (temp < -0.35f) {
            return humid > -0.05f ? 5 : 4;
        }
        return humid > 0.08f ? 1 : 0;
    }

    /**
     * Stage K2 {@code 10:Y+H_2} tile-coherent surface strata block selector lifted into OpenCL.
     */
    public static int selectSurfaceStrataBlock(int biomeId, int by, int surfY, int seaLevel) {
        if (by < 0) {
            return 2; // minecraft:deepslate
        }
        if (by == surfY && surfY >= seaLevel) {
            if (biomeId == 2) {
                return 14; // minecraft:sand
            }
            if (biomeId == 3) {
                return 16; // minecraft:terracotta
            }
            if (biomeId == 4) {
                return 17; // minecraft:snow_block
            }
            if (biomeId == 5) {
                return 18; // minecraft:podzol
            }
            return 3; // minecraft:grass_block
        }
        if (by < surfY && by >= surfY - 3) {
            if (biomeId == 2) {
                return 15; // minecraft:sandstone
            }
            if (biomeId == 3) {
                return 16; // minecraft:terracotta
            }
            return 4; // minecraft:dirt
        }
        return 1; // minecraft:stone
    }

    /**
     * Stage K2 Vanilla-anchored {@code 01:H_3} Hilbert ore seam origin calculator lifted into OpenCL.
     */
    public static int computeVanillaAnchoredOreStartH3(
            int chunkX, int chunkZ, int sectionIdx, int seamSalt, long worldSeed) {
        long h = worldSeed ^ (chunkX * 341873128712L) ^ (chunkZ * 132897987541L)
                ^ ((long) sectionIdx * 42317861L) ^ seamSalt;
        h = (h ^ (h >>> 30)) * -4658895280553007687L;
        h = (h ^ (h >>> 27)) * -7723592293110705685L;
        h = h ^ (h >>> 31);
        int x = (int) (h & 15L);
        int z = (int) ((h >>> 4) & 15L);
        int u1 = (int) ((h >>> 8) & 7L);
        int u2 = (int) ((h >>> 11) & 7L);
        int y = (u1 + u2) & 15;
        return yzxToH3(x, y, z);
    }

    /**
     * Stage K2 octant-level {@code discardChanceOnAirExposure} evaluator using {@link Long#bitCount}
     * ({@code POPCOUNT} intrinsic) to keep buried ore seams inside solid-rock octants ({@code bit == 0}).
     */
    public static int evaluateOctantDiscardExposure(
            long octreeMask64, int octIdx, int discardPolicyTenths, int seamHash) {
        int activeCount = Long.bitCount(octreeMask64);
        int isBoundaryOctant = (int) ((octreeMask64 >>> (octIdx & 63)) & 1L);
        if (isBoundaryOctant == 0) {
            return 1;
        }
        if (discardPolicyTenths >= 10) {
            return activeCount < 64 ? 0 : 1;
        }
        int roll = (seamHash & 0xFF) * 10;
        return roll >= (discardPolicyTenths * 255) ? 1 : 0;
    }

    /**
     * Stage K3 {@code .lca} Layer-3 inline uniform section directory word encoder
     * ({@code 0x8000 | (skyLight2 << 13) | (biomeTemplate2 << 11) | dictId11}).
     */
    public static int encodeUniformSectionHeader(int dictId, int skyLight2, int biomeTemplate2) {
        return 0x8000 | ((skyLight2 & 3) << 13) | ((biomeTemplate2 & 3) << 11) | (dictId & 0x7FF);
    }

    /**
     * Stage K2 full Overworld underground {@code 01:H_3} seam block-state selector covering mineral
     * ores ({@code 6..12}), stone blobs ({@code granite=13, diorite=20, andesite=21, tuff=22}), and
     * cave dripstone ({@code dripstone_block=23, pointed_dripstone=24}).
     */
    public static int selectUndergroundDecoratorState(int sectionIdx, int seamOrdinal, int biomeId) {
        if (seamOrdinal < 8) {
            if (sectionIdx > 10 && seamOrdinal >= 5) {
                return 0;
            }
            return 6 + seamOrdinal;
        }
        if (seamOrdinal == 8) {
            return sectionIdx >= 4 ? 20 : 22; // diorite above Y=0, tuff in deepslate
        }
        if (seamOrdinal == 9) {
            return sectionIdx >= 3 ? 21 : 22; // andesite or tuff
        }
        if (seamOrdinal == 10) {
            return sectionIdx <= 4 ? 22 : 13; // tuff in deep sections, granite above
        }
        if (seamOrdinal == 11 && biomeId == 7) {
            return (sectionIdx & 1) == 0 ? 23 : 24; // dripstone_block / pointed_dripstone
        }
        return 0;
    }

    /**
     * Stage K2 {@code H_2} tile-coherent surface flora and aquatic kelp/seagrass selector.
     */
    public static int selectSurfaceDecoratorBlock(int biomeId, int surfY, int seaLevel, int tileHash) {
        int roll = tileHash & 63;
        if (surfY < seaLevel - 1) {
            if (roll < 5) {
                return 31; // minecraft:kelp
            }
            if (roll < 11) {
                return 32; // minecraft:seagrass
            }
            return 0;
        }
        if (surfY < seaLevel) {
            return 0;
        }
        if (biomeId == 2 || biomeId == 3) {
            if (roll == 0) {
                return 29; // minecraft:cactus
            }
            if (roll <= 2) {
                return 28; // minecraft:dead_bush
            }
            return 0;
        }
        if (biomeId == 4) {
            return 0; // snowy_plains stays clear or snow-capped
        }
        if (biomeId == 5) {
            return roll < 8 ? 30 : 0; // minecraft:fern
        }
        if (roll == 0) {
            return 26; // minecraft:poppy
        }
        if (roll == 1) {
            return 27; // minecraft:dandelion
        }
        if (roll < (biomeId == 1 ? 14 : 10)) {
            return 25; // minecraft:short_grass
        }
        return 0;
    }

    /**
     * Stage K2 deterministic chunk canopy tree anchor descriptor for Pass 2 lock-free cross-chunk
     * canopy stitching ({@code bit 30 = hasAnchor, bits 26..24 = biomeId, bits 20..12 = surfY+64,
     * bits 7..4 = anchorLocalZ, bits 3..0 = anchorLocalX}).
     */
    public static int computeChunkTreeAnchorPacked(
            int chunkX, int chunkZ, int domBiomeId, int anchorSurfY, int seaLevel, long worldSeed) {
        if (anchorSurfY <= seaLevel || anchorSurfY > 216) {
            return 0;
        }
        if (domBiomeId == 2 || domBiomeId == 4 || domBiomeId == 6 || domBiomeId == 7) {
            return 0;
        }
        long h = worldSeed ^ (chunkX * 341873128712L) ^ (chunkZ * 132897987541L) ^ 0x517CC1B727220A95L;
        h = (h ^ (h >>> 30)) * -4658895280553007687L;
        h = (h ^ (h >>> 27)) * -7723592293110705685L;
        h = h ^ (h >>> 31);
        int roll = (int) ((h >>> 8) & 15L);
        int threshold = domBiomeId == 1 ? 15 : (domBiomeId == 5 ? 14 : (domBiomeId == 3 ? 6 : 4));
        if (roll >= threshold) {
            return 0;
        }
        int lx = (int) (h & 15L);
        int lz = (int) ((h >>> 4) & 15L);
        int encY = Math.max(0, Math.min(511, anchorSurfY + 64));
        return 0x40000000 | ((domBiomeId & 7) << 24) | (encY << 12) | ((lz & 15) << 4) | (lx & 15);
    }

    public static final int LCA_SLOT_4K_BYTES = 4096;
    public static final int LCA_SLOT_4K_MAGIC = 0x4C435334; // "LCS4"
    public static final int LCA_SLOT_4K_RUN_STREAM_OFFSET = 1760;

    /**
     * Functional corner evaluator used by the bounded JVM Hidden-Class CPU lane to produce bit-layout
     * compatible {@code 4 KiB} draft {@code .lca} slots alongside OpenCL GPU/iGPU queues.
     */
    @FunctionalInterface
    public interface CornerDensityEvaluator {
        float eval(int blockX, int blockY, int blockZ);
    }

    public static final int GPU_BEARD_PIECE_BYTES = 32;
    public static final int BEARD_NONE = 0;
    public static final int BEARD_BURY = 1;
    public static final int BEARD_THIN = 2;
    public static final int BEARD_BOX = 3;
    public static final int BEARD_ENCAPSULATE = 4;
    public static final int STRUCTURE_GRID_QUERY_BYTES = 32;
    public static final int STRUCTURE_CANDIDATE_BYTES = 32;
    public static final int SPREAD_LINEAR = 0;
    public static final int SPREAD_TRIANGULAR = 1;

    /**
     * Flat 32-byte ({@code 8 x int32}) structure piece descriptor for GPU/CPU {@code Beardifier} density blending.
     */
    public record GpuBeardPiece(
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            int terrainAdjustment,
            int groundLevelDelta,
            int junctionOffset,
            int junctionCount) {

        public GpuBeardPiece {
            if (terrainAdjustment < BEARD_NONE || terrainAdjustment > BEARD_ENCAPSULATE) {
                throw new IllegalArgumentException("Invalid terrainAdjustment: " + terrainAdjustment);
            }
            if (groundLevelDelta < -128 || groundLevelDelta > 127) {
                throw new IllegalArgumentException("groundLevelDelta out of [-128..127]: " + groundLevelDelta);
            }
        }
    }

    /**
     * Flat 32-byte ({@code 8 x int32}) {@code RandomSpread} structure-set query evaluated in Stage {@code K0}.
     */
    public record StructureGridQuery(
            int structureSetIndex,
            int spacing,
            int separation,
            int salt,
            int spreadType,
            int allowedBiomeMask,
            int minSurfY,
            int maxSurfY) {}

    /**
     * Surviving Stage {@code K0} structure start candidate with analytic surface elevation and climate biome.
     */
    public record StructureStartCandidate(
            int queryIndex,
            int structureSetIndex,
            int chunkX,
            int chunkZ,
            int surfY,
            int climateBiomeId,
            int gridX,
            int gridZ) {}

    public static int[] packGpuBeardPiecesI32(List<GpuBeardPiece> pieces) {
        if (pieces == null || pieces.isEmpty()) {
            return new int[0];
        }
        int[] out = new int[pieces.size() * 8];
        for (int i = 0; i < pieces.size(); i++) {
            GpuBeardPiece p = pieces.get(i);
            int base = i * 8;
            out[base] = p.minX();
            out[base + 1] = p.minY();
            out[base + 2] = p.minZ();
            out[base + 3] = p.maxX();
            out[base + 4] = p.maxY();
            out[base + 5] = p.maxZ();
            out[base + 6] = (p.terrainAdjustment() & 0xFF)
                    | ((p.groundLevelDelta() & 0xFF) << 8)
                    | ((p.junctionOffset() & 0xFFFF) << 16);
            out[base + 7] = p.junctionCount();
        }
        return out;
    }

    public static int[] packStructureGridQueriesI32(List<StructureGridQuery> queries) {
        if (queries == null || queries.isEmpty()) {
            return new int[0];
        }
        int[] out = new int[queries.size() * 8];
        for (int i = 0; i < queries.size(); i++) {
            StructureGridQuery q = queries.get(i);
            int b = i * 8;
            out[b] = q.structureSetIndex();
            out[b + 1] = q.spacing();
            out[b + 2] = q.separation();
            out[b + 3] = q.salt();
            out[b + 4] = q.spreadType();
            out[b + 5] = q.allowedBiomeMask();
            out[b + 6] = q.minSurfY();
            out[b + 7] = q.maxSurfY();
        }
        return out;
    }

    public static List<StructureStartCandidate> unpackStructureStartCandidatesI32(
            int[] rawCandidatesI32, int candidateCount) {
        if (rawCandidatesI32 == null || candidateCount <= 0) {
            return List.of();
        }
        int count = Math.min(candidateCount, rawCandidatesI32.length / 8);
        List<StructureStartCandidate> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int b = i * 8;
            list.add(new StructureStartCandidate(
                    rawCandidatesI32[b],
                    rawCandidatesI32[b + 1],
                    rawCandidatesI32[b + 2],
                    rawCandidatesI32[b + 3],
                    rawCandidatesI32[b + 4],
                    rawCandidatesI32[b + 5],
                    rawCandidatesI32[b + 6],
                    rawCandidatesI32[b + 7]));
        }
        list.sort(java.util.Comparator.comparingInt(StructureStartCandidate::queryIndex)
                .thenComparingInt(StructureStartCandidate::chunkZ)
                .thenComparingInt(StructureStartCandidate::chunkX));
        return java.util.Collections.unmodifiableList(list);
    }

    private static int lcgNextInt48(long[] stateHolder, int bound) {
        if (bound <= 1) {
            stateHolder[0] = (stateHolder[0] * 0x5DEECE66DL + 0xBL) & 0xFFFF_FFFF_FFFFL;
            return 0;
        }
        if ((bound & -bound) == bound) {
            stateHolder[0] = (stateHolder[0] * 0x5DEECE66DL + 0xBL) & 0xFFFF_FFFF_FFFFL;
            long bits = (stateHolder[0] >>> 17) & 0xFFFF_FFFFL;
            return (int) ((bound * bits) >> 31);
        }
        int bits;
        int val;
        do {
            stateHolder[0] = (stateHolder[0] * 0x5DEECE66DL + 0xBL) & 0xFFFF_FFFF_FFFFL;
            bits = (int) (stateHolder[0] >>> 17);
            val = bits % bound;
        } while (bits - val + (bound - 1) < 0);
        return val;
    }

    /**
     * Exact CPU twin of OpenCL Stage {@code K0} ({@code evaluate_structure_grid_starts}).
     */
    public static List<StructureStartCandidate> evaluateStructureGridStartsCpu(
            CornerDensityEvaluator cornerEval,
            List<StructureGridQuery> queries,
            int minChunkX,
            int minChunkZ,
            int maxChunkX,
            int maxChunkZ,
            long worldSeed) {
        Objects.requireNonNull(cornerEval, "cornerEval");
        if (queries == null || queries.isEmpty() || minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            return List.of();
        }
        List<StructureStartCandidate> out = new ArrayList<>();
        long[] lcg = new long[1];
        for (int qIdx = 0; qIdx < queries.size(); qIdx++) {
            StructureGridQuery q = queries.get(qIdx);
            int spacing = q.spacing();
            if (spacing <= 0) {
                continue;
            }
            int minGx = Math.floorDiv(minChunkX, spacing);
            int maxGx = Math.floorDiv(maxChunkX, spacing);
            int minGz = Math.floorDiv(minChunkZ, spacing);
            int maxGz = Math.floorDiv(maxChunkZ, spacing);
            int bound = Math.max(1, spacing - q.separation());
            for (int gz = minGz; gz <= maxGz; gz++) {
                for (int gx = minGx; gx <= maxGx; gx++) {
                    long rSeed = (long) gx * 341873128712L
                            + (long) gz * 132897987541L
                            + worldSeed
                            + (long) q.salt();
                    lcg[0] = (rSeed ^ 0x5DEECE66DL) & 0xFFFF_FFFF_FFFFL;
                    int ox;
                    int oz;
                    if (q.spreadType() == SPREAD_TRIANGULAR) {
                        int rx0 = lcgNextInt48(lcg, bound);
                        int rx1 = lcgNextInt48(lcg, bound);
                        int rz0 = lcgNextInt48(lcg, bound);
                        int rz1 = lcgNextInt48(lcg, bound);
                        ox = (rx0 + rx1) / 2;
                        oz = (rz0 + rz1) / 2;
                    } else {
                        ox = lcgNextInt48(lcg, bound);
                        oz = lcgNextInt48(lcg, bound);
                    }
                    int candCx = gx * spacing + ox;
                    int candCz = gz * spacing + oz;
                    if (candCx < minChunkX || candCx > maxChunkX || candCz < minChunkZ || candCz > maxChunkZ) {
                        continue;
                    }
                    int bx = (candCx << 4) + 8;
                    int bz = (candCz << 4) + 8;
                    float n = cornerEval.eval(bx, 64, bz);
                    float e = cornerEval.eval(bx, 96, bz);
                    int sy = Math.max(48, Math.min(110, 64 + (int) Math.rint(n * 28.0f)));
                    int biomeId = classifyClimateBiomeQuartet(n, e, n - e, e * 0.5f, sy) & 7;
                    if (sy >= q.minSurfY() && sy <= q.maxSurfY() && ((q.allowedBiomeMask() & (1 << biomeId)) != 0)) {
                        out.add(new StructureStartCandidate(
                                qIdx, q.structureSetIndex(), candCx, candCz, sy, biomeId, gx, gz));
                    }
                }
            }
        }
        out.sort(java.util.Comparator.comparingInt(StructureStartCandidate::queryIndex)
                .thenComparingInt(StructureStartCandidate::chunkZ)
                .thenComparingInt(StructureStartCandidate::chunkX));
        return java.util.Collections.unmodifiableList(out);
    }

    public static float computeBeardContribution(
            int bx, int by, int bz, int[] beardPiecesI32, int beardStartIdx, int beardCount) {
        if (beardPiecesI32 == null || beardCount <= 0) {
            return 0.0f;
        }
        float sum = 0.0f;
        for (int i = 0; i < beardCount; i++) {
            int p = (beardStartIdx + i) * 8;
            int w6 = beardPiecesI32[p + 6];
            int tAdj = w6 & 255;
            if (tAdj == BEARD_NONE) {
                continue;
            }
            int gDelta = (w6 << 16) >> 24;
            int minX = beardPiecesI32[p];
            int minY = beardPiecesI32[p + 1] + gDelta;
            int minZ = beardPiecesI32[p + 2];
            int maxX = beardPiecesI32[p + 3];
            int maxY = beardPiecesI32[p + 4];
            int maxZ = beardPiecesI32[p + 5];
            int dx = Math.max(0, Math.max(minX - bx, bx - maxX));
            int dz = Math.max(0, Math.max(minZ - bz, bz - maxZ));
            int dy = Math.max(0, Math.max(minY - by, by - maxY));
            if (dx > 12 || dz > 12 || dy > 12) {
                continue;
            }
            int d2 = dx * dx + dy * dy + dz * dz;
            if (d2 >= 144) {
                continue;
            }
            float t = 1.0f - (float) d2 * 0x1.c71c72p-8f;
            float w = (t * t) * t;
            if (tAdj == BEARD_BURY) {
                int midY = (minY + maxY) >> 1;
                sum += (by <= midY ? 0.85f : -0.45f) * w;
            } else if (tAdj == BEARD_THIN) {
                if (by <= minY) {
                    sum += 1.35f * w;
                } else if (dx <= 2 && dz <= 2 && by <= maxY) {
                    sum -= 0.90f * w;
                }
            } else if (tAdj == BEARD_BOX) {
                if (dx == 0 && dz == 0 && by >= minY && by <= maxY) {
                    sum -= 2.25f;
                } else {
                    sum += (by < minY ? 1.10f : -0.65f) * w;
                }
            } else if (tAdj == BEARD_ENCAPSULATE) {
                if (dx == 0 && dz == 0 && by >= minY && by <= maxY) {
                    sum += 1.60f;
                } else {
                    sum += 0.95f * w;
                }
            }
        }
        return sum;
    }

    private static int adjustSurfYForBeard(
            int wx, int wz, int foundY, int[] beardPiecesI32, int beardStartIdx, int beardCount) {
        int sy = foundY;
        for (int i = 0; i < beardCount; i++) {
            int p = (beardStartIdx + i) * 8;
            int w6 = beardPiecesI32[p + 6];
            int tAdj = w6 & 255;
            int gDelta = (w6 << 16) >> 24;
            int minX = beardPiecesI32[p];
            int minY = beardPiecesI32[p + 1] + gDelta;
            int minZ = beardPiecesI32[p + 2];
            int maxX = beardPiecesI32[p + 3];
            int maxY = beardPiecesI32[p + 4];
            int maxZ = beardPiecesI32[p + 5];
            if (tAdj == BEARD_THIN) {
                int hdx = Math.max(0, Math.max(minX - wx, wx - maxX));
                int hdz = Math.max(0, Math.max(minZ - wz, wz - maxZ));
                if (hdx == 0 && hdz == 0) {
                    sy = Math.max(-60, Math.min(310, minY));
                } else if (hdx <= 4 && hdz <= 4) {
                    int d = Math.max(hdx, hdz);
                    sy = Math.max(-60, Math.min(310, sy + ((minY - sy) * (5 - d)) / 5));
                }
            } else if (tAdj == BEARD_ENCAPSULATE
                    && wx >= minX
                    && wx <= maxX
                    && wz >= minZ
                    && wz <= maxZ
                    && maxY > sy) {
                sy = Math.max(-60, Math.min(310, maxY));
            }
        }
        return sy;
    }

    private static boolean boxIntersectsBeard(
            int x0,
            int y0,
            int z0,
            int x1,
            int y1,
            int z1,
            int[] beardPiecesI32,
            int beardStartIdx,
            int beardCount) {
        for (int i = 0; i < beardCount; i++) {
            int p = (beardStartIdx + i) * 8;
            int w6 = beardPiecesI32[p + 6];
            int tAdj = w6 & 255;
            if (tAdj == 0) {
                continue;
            }
            int pad = (tAdj == BEARD_THIN) ? 12 : 6;
            int gDelta = (w6 << 16) >> 24;
            int minX = beardPiecesI32[p] - pad;
            int minY = beardPiecesI32[p + 1] + gDelta - pad;
            int minZ = beardPiecesI32[p + 2] - pad;
            int maxX = beardPiecesI32[p + 3] + pad;
            int maxY = beardPiecesI32[p + 4] + pad;
            int maxZ = beardPiecesI32[p + 5] + pad;
            if (x1 >= minX && x0 <= maxX && y1 >= minY && y0 <= maxY && z1 >= minZ && z0 <= maxZ) {
                return true;
            }
        }
        return false;
    }

    private static float computeVoxelBeardDelta(
            int bx, int by, int bz, int[] beardPiecesI32, int beardStartIdx, int beardCount) {
        float delta = 0.0f;
        for (int i = 0; i < beardCount; i++) {
            int p = (beardStartIdx + i) * 8;
            int w6 = beardPiecesI32[p + 6];
            int tAdj = w6 & 255;
            if (tAdj == BEARD_NONE) {
                continue;
            }
            int gDelta = (w6 << 16) >> 24;
            int minX = beardPiecesI32[p];
            int minY = beardPiecesI32[p + 1] + gDelta;
            int minZ = beardPiecesI32[p + 2];
            int maxX = beardPiecesI32[p + 3];
            int maxY = beardPiecesI32[p + 4];
            int maxZ = beardPiecesI32[p + 5];
            if (bx < minX - 8 || bx > maxX + 8 || by < minY - 8 || by > maxY + 8 || bz < minZ - 8 || bz > maxZ + 8) {
                continue;
            }
            boolean insideXz = (bx >= minX && bx <= maxX && bz >= minZ && bz <= maxZ);
            if (tAdj == BEARD_BOX && insideXz && by >= minY && by <= maxY) {
                delta -= 4.0f;
            } else if (tAdj == BEARD_THIN && insideXz) {
                if (by <= minY && by >= minY - 6) {
                    delta += 2.0f;
                } else if (by > minY && by <= maxY) {
                    delta -= 4.0f;
                }
            } else if (tAdj == BEARD_ENCAPSULATE && insideXz && by >= minY && by <= maxY) {
                delta += 2.5f;
            } else if (tAdj == BEARD_BURY && insideXz && by >= minY - 4 && by <= maxY + 4) {
                delta += 1.5f;
            }
        }
        return delta;
    }

    /**
     * Executes the lifted {@code Stages K1 -> K2 -> K3} pipeline on the JVM CPU lane into a bounded
     * {@code 4 KiB} ({@code 4,096 B}) draft {@code .lca} slot at {@code slotByteOffset}.
     */
    public static final int SLOT4K_IN_KERNEL_STAMPED_FLAG = 0x4000;
    public static final int SLOT4K_OVERFLOW_FLAG = 0x8000;
    public static final int SLOT4K_MAX_EXTRA_STATES = 192;
    public static final int TREE_ANCHOR_CUSTOM_STAMP_SUPPRESS_BIT = 0x20000000;

    /**
     * Exact CPU twin of OpenCL Stage {@code K0b} ({@code evaluate_tree_anchors_batch}) for a single chunk.
     */
    public static int evaluateTreeAnchorAtChunkCpu(
            CornerDensityEvaluator cornerEval, int chunkX, int chunkZ, long worldSeed) {
        Objects.requireNonNull(cornerEval, "cornerEval");
        int probe = computeChunkTreeAnchorPacked(chunkX, chunkZ, 1, 70, 62, worldSeed);
        if ((probe & 0x40000000) == 0) {
            return 0;
        }
        int baseBx = chunkX << 4;
        int baseBz = chunkZ << 4;
        int ax = probe & 15;
        int az = (probe >>> 4) & 15;
        int tx = ax >>> 2;
        int tz = az >>> 2;
        float fx = (ax & 3) * 0.25f;
        float fz = (az & 3) * 0.25f;
        float c00 = cornerEval.eval(baseBx + (tx << 2), 64, baseBz + (tz << 2));
        float c10 = cornerEval.eval(baseBx + ((tx + 1) << 2), 64, baseBz + (tz << 2));
        float c01 = cornerEval.eval(baseBx + (tx << 2), 64, baseBz + ((tz + 1) << 2));
        float c11 = cornerEval.eval(baseBx + ((tx + 1) << 2), 64, baseBz + ((tz + 1) << 2));
        float e00 = cornerEval.eval(baseBx + (tx << 2), 96, baseBz + (tz << 2));
        float nCol = (c00 * (1.0f - fx) + c10 * fx) * (1.0f - fz)
                + (c01 * (1.0f - fx) + c11 * fx) * fz;
        int ay = Math.max(48, Math.min(110, 64 + (int) Math.rint(nCol * 28.0f)));
        int tileSy = Math.max(48, Math.min(110, 64 + (int) Math.rint(c00 * 28.0f)));
        int surfSec = Math.max(0, Math.min(23, (ay + 64) >> 4));
        float ct = c00 * 4.0f;
        float ch = e00 * 4.0f;
        float cc = (ay <= 62 || tileSy <= 62) ? -0.30f : (ct - ch * 0.5f);
        int domBiome = classifyClimateBiomeQuartet(ct, ch, cc, ch * 0.5f, (surfSec - 4) << 2) & 7;
        return computeChunkTreeAnchorPacked(chunkX, chunkZ, domBiome, ay, 62, worldSeed);
    }

    /**
     * Exact CPU twin of OpenCL Stage {@code K0b} ({@code evaluate_tree_anchors_batch}) across {@code chunkCount} chunks.
     */
    public static int[] evaluateTreeAnchorsBatchCpu(
            CornerDensityEvaluator cornerEval, int[] chunkCoordsXZ, int chunkCount, long worldSeed) {
        Objects.requireNonNull(cornerEval, "cornerEval");
        Objects.requireNonNull(chunkCoordsXZ, "chunkCoordsXZ");
        if (chunkCount <= 0) {
            return new int[0];
        }
        int[] out = new int[chunkCount];
        for (int i = 0; i < chunkCount; i++) {
            out[i] = evaluateTreeAnchorAtChunkCpu(
                    cornerEval, chunkCoordsXZ[i * 2], chunkCoordsXZ[i * 2 + 1], worldSeed);
        }
        return out;
    }

    private static int mixAnchorHashCpu(int cx, int cz, long seed) {
        long h = seed ^ ((long) cx * 0x9E3779B97F4A7C15L) ^ ((long) cz * 0xBF58476D1CE4E5B9L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return (int) (h ^ (h >>> 31));
    }

    private static int evalProcessorPaletteIndexCpu(
            int basePalIdx,
            int[] rulesI32,
            int ruleStart,
            int ruleCount,
            int wx,
            int wy,
            int wz,
            long worldSeed) {
        if (rulesI32 == null || ruleCount <= 0) {
            return basePalIdx;
        }
        for (int r = 0; r < ruleCount; r++) {
            int rb = (ruleStart + r) * 6;
            if (rulesI32[rb] != basePalIdx) {
                continue;
            }
            float prob = Float.intBitsToFloat(rulesI32[rb + 2]);
            if (prob >= 1.0f) {
                return rulesI32[rb + 1];
            }
            if (prob <= 0.0f) {
                continue;
            }
            long salt = (((long) rulesI32[rb + 4] & 0xFFFF_FFFFL) << 32)
                    | ((long) rulesI32[rb + 3] & 0xFFFF_FFFFL);
            long h = worldSeed ^ salt ^ ((long) r * 0x9E3779B97F4A7C15L);
            h ^= (long) wx * 0xBF58476D1CE4E5B9L;
            h ^= (long) wy * 0x94D049BB133111EBL;
            h ^= (long) wz * 0xD6E8FEB86659FD93L;
            h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
            h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
            h = h ^ (h >>> 31);
            float sample = ((h >>> 40) & 0xFFFFFFL) * 0x1.0p-24f;
            if (sample < prob) {
                return rulesI32[rb + 1];
            }
        }
        return basePalIdx;
    }

    public static int evaluateEndToEndLcaSlot4kCpu(
            CornerDensityEvaluator cornerEval,
            int chunkX,
            int chunkZ,
            long worldSeed,
            byte[] outSlots,
            int slotByteOffset) {
        return evaluateEndToEndLcaSlot4kCpu(
                cornerEval, chunkX, chunkZ, worldSeed, null, 0, 0, outSlots, slotByteOffset);
    }

    /**
     * Executes the lifted {@code Stages K1 -> K2 -> K3} pipeline with optional {@link GpuBeardPiece}
     * {@code Beardifier} density fusion ({@code leafgen-ADR-003}).
     */
    public static int evaluateEndToEndLcaSlot4kCpu(
            CornerDensityEvaluator cornerEval,
            int chunkX,
            int chunkZ,
            long worldSeed,
            int[] beardPiecesI32,
            int beardStartIdx,
            int beardCount,
            byte[] outSlots,
            int slotByteOffset) {
        Objects.requireNonNull(cornerEval, "cornerEval");
        Objects.requireNonNull(outSlots, "outSlots");
        if (slotByteOffset < 0 || slotByteOffset + LCA_SLOT_4K_BYTES > outSlots.length) {
            throw new IllegalArgumentException("Invalid 4 KiB slot offset: " + slotByteOffset);
        }
        int effBeardCount = (beardPiecesI32 != null && beardCount > 0) ? beardCount : 0;
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(outSlots, slotByteOffset, LCA_SLOT_4K_BYTES)
                .slice()
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        java.util.Arrays.fill(outSlots, slotByteOffset, slotByteOffset + LCA_SLOT_4K_BYTES, (byte) 0);

        int baseBx = chunkX << 4;
        int baseBz = chunkZ << 4;
        float[] corners = new float[1225];
        for (int cx = 0; cx < 5; cx++) {
            for (int cz = 0; cz < 5; cz++) {
                int colBase = (cx * 5 + cz) * 49;
                for (int cy = 0; cy < 49; cy++) {
                    int cbx = baseBx + (cx << 2);
                    int cby = -64 + (cy << 3);
                    int cbz = baseBz + (cz << 2);
                    float cval = cornerEval.eval(cbx, cby, cbz);
                    if (effBeardCount > 0) {
                        cval += computeBeardContribution(
                                cbx, cby, cbz, beardPiecesI32, beardStartIdx, effBeardCount);
                    }
                    corners[colBase + cy] = cval;
                }
            }
        }

        short[] surfY = new short[256];
        byte[] biomes = new byte[384];
        for (int tile = 0; tile < 16; tile++) {
            int tx = tile & 3;
            int tz = (tile >>> 2) & 3;
            int c00 = (tx * 5 + tz) * 49;
            int c10 = ((tx + 1) * 5 + tz) * 49;
            int c01 = (tx * 5 + (tz + 1)) * 49;
            int c11 = ((tx + 1) * 5 + (tz + 1)) * 49;
            float n = corners[c00 + 16];
            float e = corners[c00 + 20];
            for (int dz = 0; dz < 4; dz++) {
                float fz = dz * 0.25f;
                for (int dx = 0; dx < 4; dx++) {
                    float fx = dx * 0.25f;
                    float nCol = (corners[c00 + 16] * (1.0f - fx) + corners[c10 + 16] * fx) * (1.0f - fz)
                            + (corners[c01 + 16] * (1.0f - fx) + corners[c11 + 16] * fx) * fz;
                    int foundY = Math.max(48, Math.min(110, 64 + (int) Math.rint(nCol * 28.0f)));
                    if (effBeardCount > 0) {
                        foundY = adjustSurfYForBeard(
                                baseBx + (tx << 2 | dx),
                                baseBz + (tz << 2 | dz),
                                foundY,
                                beardPiecesI32,
                                beardStartIdx,
                                effBeardCount);
                    }
                    surfY[((tz << 2 | dz) << 4) | (tx << 2 | dx)] = (short) foundY;
                }
            }
            int tileSy = surfY[((tz << 2) << 4) | (tx << 2)];
            float ct = n * 4.0f;
            float ch = e * 4.0f;
            float cc = (tileSy <= 62) ? -0.30f : (ct - ch * 0.5f);
            for (int s = 0; s < 24; s++) {
                biomes[s * 16 + tile] = (byte) (classifyClimateBiomeQuartet(
                        ct, ch, cc, ch * 0.5f, (s - 4) << 2) & 7);
            }
        }

        int maxSurfY = -64;
        int minSurfY = 320;
        for (int i = 0; i < 256; i++) {
            int sy = surfY[i];
            if (sy > maxSurfY) maxSurfY = sy;
            if (sy < minSurfY) minSurfY = sy;
            buf.putShort(1456 + (i << 1), surfY[i]);
        }

        int seaLevel = 62;
        int runCursor = LCA_SLOT_4K_RUN_STREAM_OFFSET;
        int uniformSections = 0;
        int activeOctants = 0;
        int skippedOctants = 0;
        int totalDecRuns = 0;
        int[] seamStart = new int[12];
        int[] seamEnd = new int[12];
        int[] seamState = new int[12];

        for (int s = 0; s < 24; s++) {
            int baseBy = (s - 4) << 4;
            int topBy = baseBy + 15;
            int domBiome = biomes[s * 16] & 7;
            buf.put(1712 + s, (byte) (0x80 | domBiome));
            boolean secHasBeard = effBeardCount > 0
                    && boxIntersectsBeard(
                            baseBx,
                            baseBy,
                            baseBz,
                            baseBx + 15,
                            topBy,
                            baseBz + 15,
                            beardPiecesI32,
                            beardStartIdx,
                            effBeardCount);

            if (!secHasBeard && baseBy > Math.max(maxSurfY + 1, seaLevel + 1)) {
                short hdr = (short) 0x8000;
                buf.putShort(32 + (s << 1), hdr);
                buf.putLong(80 + (s << 3), 0L);
                buf.put(1736 + s, (byte) toGray4(15));
                uniformSections++;
                skippedOctants += 64;
                continue;
            }

            long mask = 0L;
            for (int oct = 0; oct < 64; oct++) {
                int yzx = h3ToYzx(oct << 6);
                int tx = (yzx & 15) >>> 2;
                int tz = ((yzx >>> 4) & 15) >>> 2;
                int octY = (yzx >>> 8) >>> 2;
                int cellY = (s << 1) + (octY >>> 1);
                float c000 = corners[(tx * 5 + tz) * 49 + cellY];
                float c111 = corners[((tx + 1) * 5 + (tz + 1)) * 49 + cellY + 1];
                float c100 = corners[((tx + 1) * 5 + tz) * 49 + cellY];
                float c011 = corners[(tx * 5 + (tz + 1)) * 49 + cellY + 1];
                float cMin = Math.min(Math.min(c000, c111), Math.min(c100, c011));
                int octTopY = baseBy + (octY << 2) + 3;
                int octBotY = baseBy + (octY << 2);
                boolean nearSurface = octTopY >= minSurfY - 3 && octBotY <= Math.max(maxSurfY + 1, seaLevel);
                boolean deepCave = s >= 1 && octTopY < minSurfY - 5 && octBotY > -58 && cMin < -0.12890625f;
                boolean beardWake = secHasBeard
                        && boxIntersectsBeard(
                                baseBx + (tx << 2),
                                octBotY,
                                baseBz + (tz << 2),
                                baseBx + (tx << 2) + 3,
                                octTopY,
                                baseBz + (tz << 2) + 3,
                                beardPiecesI32,
                                beardStartIdx,
                                effBeardCount);
                if (nearSurface || deepCave || beardWake) {
                    mask |= (1L << oct);
                }
            }

            int activeCnt = Long.bitCount(mask);
            activeOctants += activeCnt;
            skippedOctants += (64 - activeCnt);

            int dom = (baseBy < 0) ? 2 : ((topBy < minSurfY) ? 1 : 0);
            int seamCount = 0;
            long seamOctMask = 0L;
            if (baseBy <= maxSurfY) {
                for (int seamOrd = 0; seamOrd < 12; seamOrd++) {
                    int decState = selectUndergroundDecoratorState(s, seamOrd, domBiome);
                    if (decState == 0) {
                        continue;
                    }
                    int startH3 = computeVanillaAnchoredOreStartH3(
                            chunkX, chunkZ, s, 100 + seamOrd * 37, worldSeed);
                    int octIdx = (startH3 >>> 6) & 63;
                    int discardTenths = (seamOrd == 5 || seamOrd == 6) ? 10 : (seamOrd == 3 ? 5 : 0);
                    if (evaluateOctantDiscardExposure(mask, octIdx, discardTenths, startH3) == 0) {
                        continue;
                    }
                    int seamLen = seamOrd >= 7 ? 16 : 8;
                    int endH3 = Math.min(4096, startH3 + seamLen);
                    seamStart[seamCount] = startH3;
                    seamEnd[seamCount] = endH3;
                    seamState[seamCount] = decState;
                    seamCount++;
                    seamOctMask |= (1L << octIdx);
                    int endOct = ((endH3 - 1) >>> 6) & 63;
                    seamOctMask |= (1L << endOct);
                }
            }

            int nonDomRuns = 0;
            long occMask = 0L;
            int secHeaderPos = runCursor;
            if (secHeaderPos + 4 <= LCA_SLOT_4K_BYTES) {
                int writePtr = secHeaderPos + 4;
                int curStart = -1;
                int curLen = 0;
                int curState = dom;

                for (int oct = 0; oct < 64; oct++) {
                    int baseYzx = h3ToYzx(oct << 6);
                    int ox = baseYzx & 12;
                    int oz = (baseYzx >>> 4) & 12;
                    int oy = (baseYzx >>> 8) & 12;
                    boolean octActive = ((mask >>> oct) & 1L) != 0L;
                    boolean hasSeam = ((seamOctMask >>> oct) & 1L) != 0L;

                    if (!octActive && !hasSeam) {
                        int wy = baseBy + oy;
                        int syCorner = surfY[oz * 16 + ox];
                        int uState = wy > Math.max(syCorner, seaLevel)
                                ? 0
                                : (wy <= syCorner ? (wy < 0 ? 2 : 1) : 5);
                        if (uState == dom) {
                            if (curLen > 0) {
                                if (writePtr + 4 <= LCA_SLOT_4K_BYTES) {
                                    buf.putShort(writePtr, (short) curStart);
                                    buf.put(writePtr + 2, (byte) (curLen - 1));
                                    buf.put(writePtr + 3, (byte) curState);
                                    writePtr += 4;
                                    nonDomRuns++;
                                    if (curState >= 6) totalDecRuns++;
                                    for (int ro = curStart >>> 6; ro <= ((curStart + curLen - 1) >>> 6); ro++) {
                                        occMask |= (1L << ro);
                                    }
                                }
                                curLen = 0;
                                curState = dom;
                            }
                            continue;
                        }
                        for (int loc = 0; loc < 64; loc++) {
                            int h3 = (oct << 6) | loc;
                            if (curLen > 0 && curState == uState && curLen < 256 && curStart + curLen == h3) {
                                curLen++;
                            } else {
                                if (curLen > 0 && writePtr + 4 <= LCA_SLOT_4K_BYTES) {
                                    buf.putShort(writePtr, (short) curStart);
                                    buf.put(writePtr + 2, (byte) (curLen - 1));
                                    buf.put(writePtr + 3, (byte) curState);
                                    writePtr += 4;
                                    nonDomRuns++;
                                    if (curState >= 6) totalDecRuns++;
                                    for (int ro = curStart >>> 6; ro <= ((curStart + curLen - 1) >>> 6); ro++) {
                                        occMask |= (1L << ro);
                                    }
                                }
                                curStart = h3;
                                curLen = 1;
                                curState = uState;
                            }
                        }
                        continue;
                    }

                    int tx = ox >>> 2;
                    int tz = oz >>> 2;
                    int cellY = (s << 1) + (oy >>> 3);
                    float yFrac0 = ((oy & 7) == 0) ? 0.0f : 0.5f;
                    int tileBiome = biomes[s * 16 + (tz << 2 | tx)] & 7;
                    for (int loc = 0; loc < 64; loc++) {
                        int h3 = (oct << 6) | loc;
                        int st;
                        if (!octActive) {
                            int wy = baseBy + oy;
                            int syCorner = surfY[oz * 16 + ox];
                            st = wy > Math.max(syCorner, seaLevel)
                                    ? 0
                                    : (wy <= syCorner ? (wy < 0 ? 2 : 1) : 5);
                        } else {
                            int yzx = h3ToYzx(h3);
                            int lx = yzx & 15;
                            int lz = (yzx >>> 4) & 15;
                            int ly = (yzx >>> 8) & 15;
                            int wy = baseBy + ly;
                            int sy = surfY[lz * 16 + lx];
                            float fx = (lx & 3) * 0.25f;
                            float fz = (lz & 3) * 0.25f;
                            float fy = yFrac0 + (ly & 3) * 0.125f;
                            int c00 = (tx * 5 + tz) * 49 + cellY;
                            int c10 = ((tx + 1) * 5 + tz) * 49 + cellY;
                            int c01 = (tx * 5 + (tz + 1)) * 49 + cellY;
                            int c11 = ((tx + 1) * 5 + (tz + 1)) * 49 + cellY;
                            float d0 = (corners[c00] * (1f - fx) + corners[c10] * fx) * (1f - fz)
                                    + (corners[c01] * (1f - fx) + corners[c11] * fx) * fz;
                            float d1 = (corners[c00 + 1] * (1f - fx) + corners[c10 + 1] * fx) * (1f - fz)
                                    + (corners[c01 + 1] * (1f - fx) + corners[c11 + 1] * fx) * fz;
                            float rawCorner = d0 + (d1 - d0) * fy;
                            float dens = (wy < sy - 5 && wy > -58 && rawCorner < -0.1328125f)
                                    ? (rawCorner + 0.1328125f)
                                    : (((sy - wy) * 0.16f) + (wy < sy - 4 ? rawCorner * 0.30f : 0.08f));
                            if (secHasBeard) {
                                dens += computeVoxelBeardDelta(
                                        baseBx + lx, wy, baseBz + lz, beardPiecesI32, beardStartIdx, effBeardCount);
                            }
                            if (dens > 0.0f) {
                                st = selectSurfaceStrataBlock(tileBiome, wy, sy, seaLevel);
                            } else if (wy <= seaLevel && wy > sy) {
                                st = 5;
                            } else if (wy < -54) {
                                st = 19;
                            } else {
                                st = 0;
                            }
                            if (st == 0 && wy == sy + 1 && sy >= seaLevel) {
                                int tHash = computeVanillaAnchoredOreStartH3(
                                        chunkX, chunkZ, s, (lz << 4) | lx, worldSeed);
                                st = selectSurfaceDecoratorBlock(tileBiome, sy, seaLevel, tHash);
                            } else if (st == 5 && wy == sy + 1 && sy < seaLevel - 1) {
                                int tHash = computeVanillaAnchoredOreStartH3(
                                        chunkX, chunkZ, s, 512 + ((lz << 4) | lx), worldSeed);
                                int aq = selectSurfaceDecoratorBlock(tileBiome, sy, seaLevel, tHash);
                                if (aq != 0) {
                                    st = aq;
                                }
                            }
                        }
                        if ((st == 1 || st == 2) && hasSeam) {
                            for (int m = 0; m < seamCount; m++) {
                                if (h3 >= seamStart[m] && h3 < seamEnd[m]) {
                                    st = seamState[m];
                                }
                            }
                        }
                        if (st == dom) {
                            if (curLen > 0) {
                                if (writePtr + 4 <= LCA_SLOT_4K_BYTES) {
                                    buf.putShort(writePtr, (short) curStart);
                                    buf.put(writePtr + 2, (byte) (curLen - 1));
                                    buf.put(writePtr + 3, (byte) curState);
                                    writePtr += 4;
                                    nonDomRuns++;
                                    if (curState >= 6) totalDecRuns++;
                                    for (int ro = curStart >>> 6; ro <= ((curStart + curLen - 1) >>> 6); ro++) {
                                        occMask |= (1L << ro);
                                    }
                                }
                                curLen = 0;
                                curState = dom;
                            }
                        } else if (curLen > 0 && curState == st && curLen < 256 && curStart + curLen == h3) {
                            curLen++;
                        } else {
                            if (curLen > 0 && writePtr + 4 <= LCA_SLOT_4K_BYTES) {
                                buf.putShort(writePtr, (short) curStart);
                                buf.put(writePtr + 2, (byte) (curLen - 1));
                                buf.put(writePtr + 3, (byte) curState);
                                writePtr += 4;
                                nonDomRuns++;
                                if (curState >= 6) totalDecRuns++;
                                for (int ro = curStart >>> 6; ro <= ((curStart + curLen - 1) >>> 6); ro++) {
                                    occMask |= (1L << ro);
                                }
                            }
                            curStart = h3;
                            curLen = 1;
                            curState = st;
                        }
                    }
                }
                if (curLen > 0 && writePtr + 4 <= LCA_SLOT_4K_BYTES) {
                    buf.putShort(writePtr, (short) curStart);
                    buf.put(writePtr + 2, (byte) (curLen - 1));
                    buf.put(writePtr + 3, (byte) curState);
                    writePtr += 4;
                    nonDomRuns++;
                    if (curState >= 6) totalDecRuns++;
                    for (int ro = curStart >>> 6; ro <= ((curStart + curLen - 1) >>> 6); ro++) {
                        occMask |= (1L << ro);
                    }
                }

                if (nonDomRuns == 0) {
                    int sky2 = baseBy > maxSurfY ? 1 : 0;
                    buf.putShort(32 + (s << 1), (short) (0x8000 | (dom & 0x7FF)));
                    buf.putLong(80 + (s << 3), 0L);
                    buf.put(1736 + s, (byte) toGray4(sky2 != 0 ? 15 : 0));
                    uniformSections++;
                } else {
                    int skyLv = topBy >= minSurfY ? 15 : 0;
                    byte gray = (byte) toGray4(skyLv);
                    buf.put(secHeaderPos, (byte) dom);
                    buf.put(secHeaderPos + 1, gray);
                    buf.putShort(secHeaderPos + 2, (short) nonDomRuns);
                    buf.putShort(32 + (s << 1), (short) secHeaderPos);
                    buf.putLong(80 + (s << 3), occMask);
                    buf.put(1736 + s, gray);
                    runCursor = writePtr;
                }
            } else {
                int sky2 = baseBy > maxSurfY ? 1 : 0;
                buf.putShort(32 + (s << 1), (short) (0x8000 | (dom & 0x7FF)));
                buf.putLong(80 + (s << 3), 0L);
                buf.put(1736 + s, (byte) toGray4(sky2 != 0 ? 15 : 0));
                uniformSections++;
            }
        }

        // Pack 4x Mojang long[37] heightmaps (9 bits/col, 7 cols/long)
        for (int w = 0; w < 37; w++) {
            int baseCol = w * 7;
            long packedWs = 0L;
            long packedOf = 0L;
            long packedMb = 0L;
            long packedMbnl = 0L;
            for (int k = 0; k < 7; k++) {
                int col = baseCol + k;
                if (col < 256) {
                    int sy = surfY[col];
                    int topWaterOrLand = Math.max(sy, seaLevel);
                    long encWs = Math.max(0, Math.min(511, topWaterOrLand - (-64) + 1));
                    long encOf = Math.max(0, Math.min(511, sy - (-64) + 1));
                    int shift = k * 9;
                    packedWs |= (encWs << shift);
                    packedOf |= (encOf << shift);
                    packedMb |= (encWs << shift);
                    packedMbnl |= (encWs << shift);
                }
            }
            buf.putLong(272 + (w << 3), packedWs);
            buf.putLong(272 + ((37 + w) << 3), packedOf);
            buf.putLong(272 + ((74 + w) << 3), packedMb);
            buf.putLong(272 + ((111 + w) << 3), packedMbnl);
        }

        int probeAnchor = computeChunkTreeAnchorPacked(chunkX, chunkZ, 1, 70, seaLevel, worldSeed);
        int treeAnchor = 0;
        if ((probeAnchor & 0x40000000) != 0) {
            int ax = probeAnchor & 15;
            int az = (probeAnchor >>> 4) & 15;
            int ay = surfY[(az << 4) | ax];
            int surfSec = Math.max(0, Math.min(23, (ay + 64) >> 4));
            int aTile = ((az >>> 2) << 2) | (ax >>> 2);
            int aBiome = ay <= seaLevel ? 6 : (biomes[surfSec * 16 + aTile] & 7);
            treeAnchor = computeChunkTreeAnchorPacked(chunkX, chunkZ, aBiome, ay, seaLevel, worldSeed);
        }

        int fnv = 0x811C9DC5;
        for (int bIdx = 32; bIdx < runCursor; bIdx++) {
            fnv ^= (outSlots[slotByteOffset + bIdx] & 0xFF);
            fnv *= 0x01000193;
        }

        buf.putInt(0, LCA_SLOT_4K_MAGIC);
        buf.putInt(4, chunkX);
        buf.putInt(8, chunkZ);
        buf.putInt(12, runCursor);
        buf.putInt(16, fnv);
        buf.putShort(20, (short) uniformSections);
        buf.putShort(22, (short) activeOctants);
        buf.putShort(24, (short) skippedOctants);
        buf.putShort(26, (short) totalDecRuns);
        buf.putInt(28, treeAnchor);
        return runCursor;
    }

    /**
     * Executes the lifted {@code Stages K1 -> K2 -> K2b -> K3} pipeline on the JVM CPU lane with
     * exact bit-for-bit parity against OpenCL {@code evaluate_end_to_end_lca_slot4k_batch} when
     * {@code in_kernel_stamps_enabled != 0} ({@code leafgen-ADR-004}).
     */
    public static int evaluateEndToEndLcaSlot4kCpu(
            CornerDensityEvaluator cornerEval,
            int chunkX,
            int chunkZ,
            long worldSeed,
            int[] beardPiecesI32,
            int beardStartIdx,
            int beardCount,
            int baseStateCount,
            int[] chunkTreeAnchors9,
            int treeAnchors9Offset,
            int[] placedStampsI32,
            int stampStartIdx,
            int stampCount,
            int[] atlasPalMetaI32,
            int[] atlasSlicesI32,
            int[] atlasRunsI32,
            int[] atlasRulesI32,
            byte[] outSlots,
            int slotByteOffset) {
        Objects.requireNonNull(cornerEval, "cornerEval");
        Objects.requireNonNull(outSlots, "outSlots");
        if (slotByteOffset < 0 || slotByteOffset + LCA_SLOT_4K_BYTES > outSlots.length) {
            throw new IllegalArgumentException("Invalid 4 KiB slot offset: " + slotByteOffset);
        }
        int effBeardCount = (beardPiecesI32 != null && beardCount > 0) ? beardCount : 0;
        int effStampCount = (placedStampsI32 != null && stampCount > 0 && atlasSlicesI32 != null && atlasRunsI32 != null && atlasPalMetaI32 != null)
                ? stampCount
                : 0;
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(outSlots, slotByteOffset, LCA_SLOT_4K_BYTES)
                .slice()
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        java.util.Arrays.fill(outSlots, slotByteOffset, slotByteOffset + LCA_SLOT_4K_BYTES, (byte) 0);

        int baseBx = chunkX << 4;
        int baseBz = chunkZ << 4;
        float[] corners = new float[1225];
        for (int cx = 0; cx < 5; cx++) {
            for (int cz = 0; cz < 5; cz++) {
                int colBase = (cx * 5 + cz) * 49;
                for (int cy = 0; cy < 49; cy++) {
                    int cbx = baseBx + (cx << 2);
                    int cby = -64 + (cy << 3);
                    int cbz = baseBz + (cz << 2);
                    float cval = cornerEval.eval(cbx, cby, cbz);
                    if (effBeardCount > 0) {
                        cval += computeBeardContribution(
                                cbx, cby, cbz, beardPiecesI32, beardStartIdx, effBeardCount);
                    }
                    corners[colBase + cy] = cval;
                }
            }
        }

        short[] surfY = new short[256];
        byte[] biomes = new byte[384];
        for (int tile = 0; tile < 16; tile++) {
            int tx = tile & 3;
            int tz = (tile >>> 2) & 3;
            int c00 = (tx * 5 + tz) * 49;
            int c10 = ((tx + 1) * 5 + tz) * 49;
            int c01 = (tx * 5 + (tz + 1)) * 49;
            int c11 = ((tx + 1) * 5 + (tz + 1)) * 49;
            float n = corners[c00 + 16];
            float e = corners[c00 + 20];
            for (int dz = 0; dz < 4; dz++) {
                float fz = dz * 0.25f;
                for (int dx = 0; dx < 4; dx++) {
                    float fx = dx * 0.25f;
                    float nCol = (corners[c00 + 16] * (1.0f - fx) + corners[c10 + 16] * fx) * (1.0f - fz)
                            + (corners[c01 + 16] * (1.0f - fx) + corners[c11 + 16] * fx) * fz;
                    int foundY = Math.max(48, Math.min(110, 64 + (int) Math.rint(nCol * 28.0f)));
                    if (effBeardCount > 0) {
                        foundY = adjustSurfYForBeard(
                                baseBx + (tx << 2 | dx),
                                baseBz + (tz << 2 | dz),
                                foundY,
                                beardPiecesI32,
                                beardStartIdx,
                                effBeardCount);
                    }
                    surfY[((tz << 2 | dz) << 4) | (tx << 2 | dx)] = (short) foundY;
                }
            }
            int tileSy = surfY[((tz << 2) << 4) | (tx << 2)];
            float ct = n * 4.0f;
            float ch = e * 4.0f;
            float cc = (tileSy <= 62) ? -0.30f : (ct - ch * 0.5f);
            for (int s = 0; s < 24; s++) {
                biomes[s * 16 + tile] = (byte) (classifyClimateBiomeQuartet(
                        ct, ch, cc, ch * 0.5f, (s - 4) << 2) & 7);
            }
        }

        int maxSurfY = -64;
        int minSurfY = 320;
        short[] localWs = new short[256];
        short[] localOf = new short[256];
        short[] localMb = new short[256];
        short[] localMbnl = new short[256];
        for (int i = 0; i < 256; i++) {
            int sy = surfY[i];
            if (sy > maxSurfY) maxSurfY = sy;
            if (sy < minSurfY) minSurfY = sy;
            buf.putShort(1456 + (i << 1), surfY[i]);
            short iws = (short) Math.max(0, Math.min(511, Math.max(sy, 62) - (-64) + 1));
            short iof = (short) Math.max(0, Math.min(511, sy - (-64) + 1));
            localWs[i] = iws;
            localOf[i] = iof;
            localMb[i] = iws;
            localMbnl[i] = iws;
        }

        int secOvMask = 0;
        if (chunkTreeAnchors9 != null && treeAnchors9Offset + 9 <= chunkTreeAnchors9.length) {
            for (int ni = 0; ni < 9; ni++) {
                int anch = chunkTreeAnchors9[treeAnchors9Offset + ni];
                if ((anch & 0x60000000) == 0x40000000) {
                    int ay = Math.min(216, ((anch >>> 12) & 511) - 64);
                    int aBiome = (anch >>> 24) & 7;
                    int loY = (ni == 4 && (aBiome == 1 || aBiome == 5)) ? Math.min(ay, minSurfY) : ay;
                    int hiY = (ni == 4 && (aBiome == 1 || aBiome == 5)) ? Math.max(ay, maxSurfY) : ay;
                    int s0 = Math.max(0, Math.min(23, (loY + 1 + 64) >> 4));
                    int s1 = Math.max(0, Math.min(23, (Math.min(224, hiY + 8) + 64) >> 4));
                    for (int ss = s0; ss <= s1; ss++) {
                        secOvMask |= (1 << ss);
                    }
                }
            }
        }
        for (int pi = 0; pi < effStampCount; pi++) {
            int pb = (stampStartIdx + pi) * 8;
            int origY = placedStampsI32[pb + 1];
            int slIdx = placedStampsI32[pb + 3];
            int sflags = placedStampsI32[pb + 4];
            int rStart = atlasSlicesI32[slIdx * 4 + 2];
            int rCnt = atlasSlicesI32[slIdx * 4 + 3];
            for (int ri = 0; ri < rCnt; ri++) {
                int relSec = atlasRunsI32[(rStart + ri) * 4];
                int yLo = (((sflags & 1) != 0) ? (minSurfY - 1 + origY) : origY) + (relSec << 4);
                int yHi = (((sflags & 1) != 0) ? (maxSurfY + 1 + origY) : origY) + (relSec << 4) + 15;
                if (yHi >= -64 && yLo <= 319) {
                    int s0 = Math.max(0, Math.min(23, (yLo + 64) >> 4));
                    int s1 = Math.max(0, Math.min(23, (yHi + 64) >> 4));
                    for (int ss = s0; ss <= s1; ss++) {
                        secOvMask |= (1 << ss);
                    }
                }
            }
        }

        int seaLevel = 62;
        int runCursor = LCA_SLOT_4K_RUN_STREAM_OFFSET;
        int uniformSections = 0;
        int activeOctants = 0;
        int skippedOctants = 0;
        int totalDecRuns = 0;
        int extraCnt = 0;
        boolean overflowFlag = false;
        int[] localExtraU16 = new int[SLOT4K_MAX_EXTRA_STATES];
        int[] seamStart = new int[12];
        int[] seamEnd = new int[12];
        int[] seamState = new int[12];
        int[] localVoxels = new int[4096];
        int[] localOvPrio = new int[4096];
        int[] localOvGlob = new int[4096];

        for (int s = 0; s < 24; s++) {
            int baseBy = (s - 4) << 4;
            int topBy = baseBy + 15;
            int domBiome = biomes[s * 16] & 7;
            buf.put(1712 + s, (byte) (0x80 | domBiome));
            boolean secHasOv = ((secOvMask >>> s) & 1) != 0;
            boolean secHasBeard = effBeardCount > 0
                    && boxIntersectsBeard(
                            baseBx,
                            baseBy,
                            baseBz,
                            baseBx + 15,
                            topBy,
                            baseBz + 15,
                            beardPiecesI32,
                            beardStartIdx,
                            effBeardCount);

            if (!secHasOv) {
                if (!secHasBeard && baseBy > Math.max(maxSurfY + 1, seaLevel + 1)) {
                    buf.putShort(32 + (s << 1), (short) 0x8000);
                    buf.putLong(80 + (s << 3), 0L);
                    buf.put(1736 + s, (byte) toGray4(15));
                    uniformSections++;
                    skippedOctants += 64;
                    continue;
                }
                long mask = 0L;
                for (int oct = 0; oct < 64; oct++) {
                    int yzx = h3ToYzx(oct << 6);
                    int tx = (yzx & 15) >>> 2;
                    int tz = ((yzx >>> 4) & 15) >>> 2;
                    int octY = (yzx >>> 8) >>> 2;
                    int cellY = (s << 1) + (octY >>> 1);
                    float c000 = corners[(tx * 5 + tz) * 49 + cellY];
                    float c111 = corners[((tx + 1) * 5 + (tz + 1)) * 49 + cellY + 1];
                    float c100 = corners[((tx + 1) * 5 + tz) * 49 + cellY];
                    float c011 = corners[(tx * 5 + (tz + 1)) * 49 + cellY + 1];
                    float cMin = Math.min(Math.min(c000, c111), Math.min(c100, c011));
                    int octTopY = baseBy + (octY << 2) + 3;
                    int octBotY = baseBy + (octY << 2);
                    boolean nearSurface = octTopY >= minSurfY - 3 && octBotY <= Math.max(maxSurfY + 1, seaLevel);
                    boolean deepCave = s >= 1 && octTopY < minSurfY - 5 && octBotY > -58 && cMin < -0.12890625f;
                    boolean beardWake = secHasBeard
                            && boxIntersectsBeard(
                                    baseBx + (tx << 2),
                                    octBotY,
                                    baseBz + (tz << 2),
                                    baseBx + (tx << 2) + 3,
                                    octTopY,
                                    baseBz + (tz << 2) + 3,
                                    beardPiecesI32,
                                    beardStartIdx,
                                    effBeardCount);
                    if (nearSurface || deepCave || beardWake) {
                        mask |= (1L << oct);
                    }
                }
                int actCnt = Long.bitCount(mask);
                activeOctants += actCnt;
                skippedOctants += (64 - actCnt);
                int dom = (baseBy < 0) ? 2 : ((topBy < minSurfY) ? 1 : 0);
                int seamCount = 0;
                long seamOctMask = 0L;
                if (baseBy <= maxSurfY) {
                    for (int seamOrd = 0; seamOrd < 12; seamOrd++) {
                        int decState = selectUndergroundDecoratorState(s, seamOrd, domBiome);
                        if (decState == 0) continue;
                        int startH3 = computeVanillaAnchoredOreStartH3(
                                chunkX, chunkZ, s, 100 + seamOrd * 37, worldSeed);
                        int octIdx = (startH3 >>> 6) & 63;
                        int discardTenths = (seamOrd == 5 || seamOrd == 6) ? 10 : (seamOrd == 3 ? 5 : 0);
                        if (evaluateOctantDiscardExposure(mask, octIdx, discardTenths, startH3) == 0) continue;
                        int seamLen = seamOrd >= 7 ? 16 : 8;
                        int endH3 = Math.min(4096, startH3 + seamLen);
                        seamStart[seamCount] = startH3;
                        seamEnd[seamCount] = endH3;
                        seamState[seamCount] = decState;
                        seamCount++;
                        seamOctMask |= (1L << octIdx) | (1L << (((endH3 - 1) >>> 6) & 63));
                    }
                }
                int slotLim = LCA_SLOT_4K_BYTES - (extraCnt << 1);
                int nonDomRuns = 0;
                long occMask = 0L;
                int secHeaderPos = runCursor;
                if (secHeaderPos + 4 <= slotLim) {
                    int writePtr = secHeaderPos + 4;
                    int curStart = -1;
                    int curLen = 0;
                    int curState = dom;
                    for (int oct = 0; oct < 64; oct++) {
                        int baseYzx = h3ToYzx(oct << 6);
                        int ox = baseYzx & 12;
                        int oz = (baseYzx >>> 4) & 12;
                        int oy = (baseYzx >>> 8) & 12;
                        boolean octActive = ((mask >>> oct) & 1L) != 0L;
                        boolean hasSeam = ((seamOctMask >>> oct) & 1L) != 0L;
                        if (!octActive && !hasSeam) {
                            int wy = baseBy + oy;
                            int syCorner = surfY[oz * 16 + ox];
                            int uState = wy > Math.max(syCorner, seaLevel)
                                    ? 0
                                    : (wy <= syCorner ? (wy < 0 ? 2 : 1) : 5);
                            if (uState == dom) {
                                if (curLen > 0) {
                                    if (writePtr + 4 <= slotLim) {
                                        buf.putShort(writePtr, (short) curStart);
                                        buf.put(writePtr + 2, (byte) (curLen - 1));
                                        buf.put(writePtr + 3, (byte) curState);
                                        writePtr += 4;
                                        nonDomRuns++;
                                        if (curState >= 6) totalDecRuns++;
                                        for (int ro = curStart >>> 6; ro <= ((curStart + curLen - 1) >>> 6); ro++) {
                                            occMask |= (1L << ro);
                                        }
                                    } else {
                                        overflowFlag = true;
                                    }
                                    curLen = 0;
                                    curState = dom;
                                }
                                continue;
                            }
                            for (int loc = 0; loc < 64; loc++) {
                                int h3 = (oct << 6) | loc;
                                if (curLen > 0 && curState == uState && curLen < 256 && curStart + curLen == h3) {
                                    curLen++;
                                } else {
                                    if (curLen > 0) {
                                        if (writePtr + 4 <= slotLim) {
                                            buf.putShort(writePtr, (short) curStart);
                                            buf.put(writePtr + 2, (byte) (curLen - 1));
                                            buf.put(writePtr + 3, (byte) curState);
                                            writePtr += 4;
                                            nonDomRuns++;
                                            if (curState >= 6) totalDecRuns++;
                                            for (int ro = curStart >>> 6; ro <= ((curStart + curLen - 1) >>> 6); ro++) {
                                                occMask |= (1L << ro);
                                            }
                                        } else {
                                            overflowFlag = true;
                                        }
                                    }
                                    curStart = h3;
                                    curLen = 1;
                                    curState = uState;
                                }
                            }
                            continue;
                        }
                        int tx = ox >>> 2;
                        int tz = oz >>> 2;
                        int cellY = (s << 1) + (oy >>> 3);
                        float yFrac0 = ((oy & 7) == 0) ? 0.0f : 0.5f;
                        int tileBiome = biomes[s * 16 + (tz << 2 | tx)] & 7;
                        for (int loc = 0; loc < 64; loc++) {
                            int h3 = (oct << 6) | loc;
                            int st;
                            if (!octActive) {
                                int wy = baseBy + oy;
                                int syCorner = surfY[oz * 16 + ox];
                                st = wy > Math.max(syCorner, seaLevel)
                                        ? 0
                                        : (wy <= syCorner ? (wy < 0 ? 2 : 1) : 5);
                            } else {
                                int yzx = h3ToYzx(h3);
                                int lx = yzx & 15;
                                int lz = (yzx >>> 4) & 15;
                                int ly = (yzx >>> 8) & 15;
                                int wy = baseBy + ly;
                                int sy = surfY[lz * 16 + lx];
                                float fx = (lx & 3) * 0.25f;
                                float fz = (lz & 3) * 0.25f;
                                float fy = yFrac0 + (ly & 3) * 0.125f;
                                int c00 = (tx * 5 + tz) * 49 + cellY;
                                int c10 = ((tx + 1) * 5 + tz) * 49 + cellY;
                                int c01 = (tx * 5 + (tz + 1)) * 49 + cellY;
                                int c11 = ((tx + 1) * 5 + (tz + 1)) * 49 + cellY;
                                float d0 = (corners[c00] * (1f - fx) + corners[c10] * fx) * (1f - fz)
                                        + (corners[c01] * (1f - fx) + corners[c11] * fx) * fz;
                                float d1 = (corners[c00 + 1] * (1f - fx) + corners[c10 + 1] * fx) * (1f - fz)
                                        + (corners[c01 + 1] * (1f - fx) + corners[c11 + 1] * fx) * fz;
                                float rawCorner = d0 + (d1 - d0) * fy;
                                float dens = (wy < sy - 5 && wy > -58 && rawCorner < -0.1328125f)
                                        ? (rawCorner + 0.1328125f)
                                        : (((sy - wy) * 0.16f) + (wy < sy - 4 ? rawCorner * 0.30f : 0.08f));
                                if (secHasBeard) {
                                    dens += computeVoxelBeardDelta(
                                            baseBx + lx, wy, baseBz + lz, beardPiecesI32, beardStartIdx, effBeardCount);
                                }
                                if (dens > 0.0f) {
                                    st = selectSurfaceStrataBlock(tileBiome, wy, sy, seaLevel);
                                } else if (wy <= seaLevel && wy > sy) {
                                    st = 5;
                                } else if (wy < -54) {
                                    st = 19;
                                } else {
                                    st = 0;
                                }
                                if (st == 0 && wy == sy + 1 && sy >= seaLevel) {
                                    int tHash = computeVanillaAnchoredOreStartH3(
                                            chunkX, chunkZ, s, (lz << 4) | lx, worldSeed);
                                    st = selectSurfaceDecoratorBlock(tileBiome, sy, seaLevel, tHash);
                                } else if (st == 5 && wy == sy + 1 && sy < seaLevel - 1) {
                                    int tHash = computeVanillaAnchoredOreStartH3(
                                            chunkX, chunkZ, s, 512 + ((lz << 4) | lx), worldSeed);
                                    int aq = selectSurfaceDecoratorBlock(tileBiome, sy, seaLevel, tHash);
                                    if (aq != 0) st = aq;
                                }
                            }
                            if ((st == 1 || st == 2) && hasSeam) {
                                for (int m = 0; m < seamCount; m++) {
                                    if (h3 >= seamStart[m] && h3 < seamEnd[m]) st = seamState[m];
                                }
                            }
                            if (st == dom) {
                                if (curLen > 0) {
                                    if (writePtr + 4 <= slotLim) {
                                        buf.putShort(writePtr, (short) curStart);
                                        buf.put(writePtr + 2, (byte) (curLen - 1));
                                        buf.put(writePtr + 3, (byte) curState);
                                        writePtr += 4;
                                        nonDomRuns++;
                                        if (curState >= 6) totalDecRuns++;
                                        for (int ro = curStart >>> 6; ro <= ((curStart + curLen - 1) >>> 6); ro++) {
                                            occMask |= (1L << ro);
                                        }
                                    } else {
                                        overflowFlag = true;
                                    }
                                    curLen = 0;
                                    curState = dom;
                                }
                            } else if (curLen > 0 && curState == st && curLen < 256 && curStart + curLen == h3) {
                                curLen++;
                            } else {
                                if (curLen > 0) {
                                    if (writePtr + 4 <= slotLim) {
                                        buf.putShort(writePtr, (short) curStart);
                                        buf.put(writePtr + 2, (byte) (curLen - 1));
                                        buf.put(writePtr + 3, (byte) curState);
                                        writePtr += 4;
                                        nonDomRuns++;
                                        if (curState >= 6) totalDecRuns++;
                                        for (int ro = curStart >>> 6; ro <= ((curStart + curLen - 1) >>> 6); ro++) {
                                            occMask |= (1L << ro);
                                        }
                                    } else {
                                        overflowFlag = true;
                                    }
                                }
                                curStart = h3;
                                curLen = 1;
                                curState = st;
                            }
                        }
                    }
                    if (curLen > 0) {
                        if (writePtr + 4 <= slotLim) {
                            buf.putShort(writePtr, (short) curStart);
                            buf.put(writePtr + 2, (byte) (curLen - 1));
                            buf.put(writePtr + 3, (byte) curState);
                            writePtr += 4;
                            nonDomRuns++;
                            if (curState >= 6) totalDecRuns++;
                            for (int ro = curStart >>> 6; ro <= ((curStart + curLen - 1) >>> 6); ro++) {
                                occMask |= (1L << ro);
                            }
                        } else {
                            overflowFlag = true;
                        }
                    }
                    if (nonDomRuns == 0) {
                        int sky2 = baseBy > maxSurfY ? 1 : 0;
                        buf.putShort(32 + (s << 1), (short) (0x8000 | (dom & 0x7FF)));
                        buf.putLong(80 + (s << 3), 0L);
                        buf.put(1736 + s, (byte) toGray4(sky2 != 0 ? 15 : 0));
                        uniformSections++;
                    } else {
                        byte gray = (byte) toGray4(topBy >= minSurfY ? 15 : 0);
                        buf.put(secHeaderPos, (byte) dom);
                        buf.put(secHeaderPos + 1, gray);
                        buf.putShort(secHeaderPos + 2, (short) nonDomRuns);
                        buf.putShort(32 + (s << 1), (short) secHeaderPos);
                        buf.putLong(80 + (s << 3), occMask);
                        buf.put(1736 + s, gray);
                        runCursor = writePtr;
                    }
                } else {
                    overflowFlag = true;
                }
            } else {
                long mask = 0L;
                int seamCount = 0;
                long seamOctMask = 0L;
                if (!secHasBeard && baseBy > Math.max(maxSurfY + 1, seaLevel + 1)) {
                    skippedOctants += 64;
                } else {
                    for (int oct = 0; oct < 64; oct++) {
                        int yzx = h3ToYzx(oct << 6);
                        int tx = (yzx & 15) >>> 2;
                        int tz = ((yzx >>> 4) & 15) >>> 2;
                        int octY = (yzx >>> 8) >>> 2;
                        int cellY = (s << 1) + (octY >>> 1);
                        float c000 = corners[(tx * 5 + tz) * 49 + cellY];
                        float c111 = corners[((tx + 1) * 5 + (tz + 1)) * 49 + cellY + 1];
                        float c100 = corners[((tx + 1) * 5 + tz) * 49 + cellY];
                        float c011 = corners[(tx * 5 + (tz + 1)) * 49 + cellY + 1];
                        float cMin = Math.min(Math.min(c000, c111), Math.min(c100, c011));
                        int octTopY = baseBy + (octY << 2) + 3;
                        int octBotY = baseBy + (octY << 2);
                        boolean nearSurface = octTopY >= minSurfY - 3 && octBotY <= Math.max(maxSurfY + 1, seaLevel);
                        boolean deepCave = s >= 1 && octTopY < minSurfY - 5 && octBotY > -58 && cMin < -0.12890625f;
                        boolean beardWake = secHasBeard
                                && boxIntersectsBeard(
                                        baseBx + (tx << 2),
                                        octBotY,
                                        baseBz + (tz << 2),
                                        baseBx + (tx << 2) + 3,
                                        octTopY,
                                        baseBz + (tz << 2) + 3,
                                        beardPiecesI32,
                                        beardStartIdx,
                                        effBeardCount);
                        if (nearSurface || deepCave || beardWake) {
                            mask |= (1L << oct);
                        }
                    }
                    int actCnt = Long.bitCount(mask);
                    activeOctants += actCnt;
                    skippedOctants += (64 - actCnt);
                    if (baseBy <= maxSurfY) {
                        for (int seamOrd = 0; seamOrd < 12; seamOrd++) {
                            int decState = selectUndergroundDecoratorState(s, seamOrd, domBiome);
                            if (decState == 0) continue;
                            int startH3 = computeVanillaAnchoredOreStartH3(
                                    chunkX, chunkZ, s, 100 + seamOrd * 37, worldSeed);
                            int octIdx = (startH3 >>> 6) & 63;
                            int discardTenths = (seamOrd == 5 || seamOrd == 6) ? 10 : (seamOrd == 3 ? 5 : 0);
                            if (evaluateOctantDiscardExposure(mask, octIdx, discardTenths, startH3) == 0) continue;
                            int seamLen = seamOrd >= 7 ? 16 : 8;
                            int endH3 = Math.min(4096, startH3 + seamLen);
                            seamStart[seamCount] = startH3;
                            seamEnd[seamCount] = endH3;
                            seamState[seamCount] = decState;
                            seamCount++;
                            seamOctMask |= (1L << octIdx) | (1L << (((endH3 - 1) >>> 6) & 63));
                        }
                    }
                }

                for (int oct = 0; oct < 64; oct++) {
                    int baseYzx = h3ToYzx(oct << 6);
                    int ox = baseYzx & 12;
                    int oz = (baseYzx >>> 4) & 12;
                    int oy = (baseYzx >>> 8) & 12;
                    boolean octActive = ((mask >>> oct) & 1L) != 0L;
                    boolean hasSeam = ((seamOctMask >>> oct) & 1L) != 0L;
                    int tx = ox >>> 2;
                    int tz = oz >>> 2;
                    int cellY = (s << 1) + (oy >>> 3);
                    float yFrac0 = ((oy & 7) == 0) ? 0.0f : 0.5f;
                    int tileBiome = biomes[s * 16 + (tz << 2 | tx)] & 7;
                    for (int loc = 0; loc < 64; loc++) {
                        int h3 = (oct << 6) | loc;
                        int st;
                        if (!octActive) {
                            int wy = baseBy + oy;
                            int syCorner = surfY[oz * 16 + ox];
                            st = wy > Math.max(syCorner, seaLevel)
                                    ? 0
                                    : (wy <= syCorner ? (wy < 0 ? 2 : 1) : 5);
                        } else {
                            int yzx = h3ToYzx(h3);
                            int lx = yzx & 15;
                            int lz = (yzx >>> 4) & 15;
                            int ly = (yzx >>> 8) & 15;
                            int wy = baseBy + ly;
                            int sy = surfY[lz * 16 + lx];
                            float fx = (lx & 3) * 0.25f;
                            float fz = (lz & 3) * 0.25f;
                            float fy = yFrac0 + (ly & 3) * 0.125f;
                            int c00 = (tx * 5 + tz) * 49 + cellY;
                            int c10 = ((tx + 1) * 5 + tz) * 49 + cellY;
                            int c01 = (tx * 5 + (tz + 1)) * 49 + cellY;
                            int c11 = ((tx + 1) * 5 + (tz + 1)) * 49 + cellY;
                            float d0 = (corners[c00] * (1f - fx) + corners[c10] * fx) * (1f - fz)
                                    + (corners[c01] * (1f - fx) + corners[c11] * fx) * fz;
                            float d1 = (corners[c00 + 1] * (1f - fx) + corners[c10 + 1] * fx) * (1f - fz)
                                    + (corners[c01 + 1] * (1f - fx) + corners[c11 + 1] * fx) * fz;
                            float rawCorner = d0 + (d1 - d0) * fy;
                            float dens = (wy < sy - 5 && wy > -58 && rawCorner < -0.1328125f)
                                    ? (rawCorner + 0.1328125f)
                                    : (((sy - wy) * 0.16f) + (wy < sy - 4 ? rawCorner * 0.30f : 0.08f));
                            if (secHasBeard) {
                                dens += computeVoxelBeardDelta(
                                        baseBx + lx, wy, baseBz + lz, beardPiecesI32, beardStartIdx, effBeardCount);
                            }
                            if (dens > 0.0f) {
                                st = selectSurfaceStrataBlock(tileBiome, wy, sy, seaLevel);
                            } else if (wy <= seaLevel && wy > sy) {
                                st = 5;
                            } else if (wy < -54) {
                                st = 19;
                            } else {
                                st = 0;
                            }
                            if (st == 0 && wy == sy + 1 && sy >= seaLevel) {
                                int tHash = computeVanillaAnchoredOreStartH3(
                                        chunkX, chunkZ, s, (lz << 4) | lx, worldSeed);
                                st = selectSurfaceDecoratorBlock(tileBiome, sy, seaLevel, tHash);
                            } else if (st == 5 && wy == sy + 1 && sy < seaLevel - 1) {
                                int tHash = computeVanillaAnchoredOreStartH3(
                                        chunkX, chunkZ, s, 512 + ((lz << 4) | lx), worldSeed);
                                int aq = selectSurfaceDecoratorBlock(tileBiome, sy, seaLevel, tHash);
                                if (aq != 0) st = aq;
                            }
                        }
                        if ((st == 1 || st == 2) && hasSeam) {
                            for (int m = 0; m < seamCount; m++) {
                                if (h3 >= seamStart[m] && h3 < seamEnd[m]) st = seamState[m];
                            }
                        }
                        localVoxels[h3] = st;
                        localOvPrio[h3] = 0;
                    }
                }

                boolean anyOvWritten = false;
                for (int pi = 0; pi < effStampCount; pi++) {
                    int pb = (stampStartIdx + pi) * 8;
                    int origX = placedStampsI32[pb];
                    int origY = placedStampsI32[pb + 1];
                    int origZ = placedStampsI32[pb + 2];
                    int slIdx = placedStampsI32[pb + 3];
                    int sflags = placedStampsI32[pb + 4];
                    int dcx = atlasSlicesI32[slIdx * 4];
                    int dcz = atlasSlicesI32[slIdx * 4 + 1];
                    int rStart = atlasSlicesI32[slIdx * 4 + 2];
                    int rCnt = atlasSlicesI32[slIdx * 4 + 3];
                    int sliceMinWx = origX + (dcx << 4);
                    int sliceMinWz = origZ + (dcz << 4);
                    for (int ri = 0; ri < rCnt; ri++) {
                        int rb = (rStart + ri) * 4;
                        int relSec = atlasRunsI32[rb];
                        int yLo = (((sflags & 1) != 0) ? (minSurfY - 1 + origY) : origY) + (relSec << 4);
                        int yHi = (((sflags & 1) != 0) ? (maxSurfY + 1 + origY) : origY) + (relSec << 4) + 15;
                        if (yHi < baseBy || yLo > topBy) continue;
                        int w1 = atlasRunsI32[rb + 1];
                        int startH3 = w1 & 0xFFFF;
                        int runLen = (w1 >>> 16) & 0xFFFF;
                        int basePalIdx = atlasRunsI32[rb + 2];
                        int w3 = atlasRunsI32[rb + 3];
                        int ruleStart = w3 & 0xFFFFF;
                        int ruleCnt = (w3 >>> 20) & 0xFF;
                        int endH3 = Math.min(4096, startH3 + runLen);
                        for (int h3 = startH3; h3 < endH3; h3++) {
                            int yzx = h3ToYzx(h3);
                            int wx = sliceMinWx + (yzx & 15);
                            int wz = sliceMinWz + ((yzx >>> 4) & 15);
                            if ((wx >> 4) != chunkX || (wz >> 4) != chunkZ) continue;
                            int lx = wx & 15;
                            int lz = wz & 15;
                            int col = (lz << 4) | lx;
                            int colFloor = surfY[col];
                            int cL = surfY[(lz << 4) | Math.max(0, lx - 1)];
                            int cR = surfY[(lz << 4) | Math.min(15, lx + 1)];
                            int cN = surfY[(Math.max(0, lz - 1) << 4) | lx];
                            int cS = surfY[(Math.min(15, lz + 1) << 4) | lx];
                            int smFloor = (2 * colFloor + cL + cR + cN + cS + 3) / 6;
                            int relY = (relSec << 4) + ((yzx >>> 8) & 15);
                            int wy = ((sflags & 1) != 0) ? (smFloor + origY + relY) : (origY + relY);
                            if (((sflags & 1) != 0) && relY == 0 && smFloor > colFloor) {
                                int fillY = smFloor + origY - 1;
                                if (fillY >= baseBy && fillY <= topBy) {
                                    int fh3 = yzxToH3(lx, fillY & 15, lz);
                                    if (4 >= localOvPrio[fh3]) {
                                        localOvPrio[fh3] = 4;
                                        localOvGlob[fh3] = 4;
                                        anyOvWritten = true;
                                    }
                                }
                            }
                            if (wy < baseBy || wy > topBy) continue;
                            int pIdx = ruleCnt > 0
                                    ? evalProcessorPaletteIndexCpu(
                                            basePalIdx, atlasRulesI32, ruleStart, ruleCnt, wx, wy, wz, worldSeed)
                                    : basePalIdx;
                            int pm0 = atlasPalMetaI32[pIdx * 2];
                            int pm1 = atlasPalMetaI32[pIdx * 2 + 1];
                            int cat = pm1 & 255;
                            int globSt;
                            int prio;
                            if (cat >= 4) {
                                boolean subm = (wy <= 62 && colFloor < 62);
                                globSt = subm ? ((pm0 >>> 16) & 0xFFFF) : (pm0 & 0xFFFF);
                                if (globSt == 0 && subm && ((sflags & 2) != 0)) continue;
                                prio = (globSt == 0) ? 4 : 5;
                            } else {
                                if (wy < -64 || wy > 224) continue;
                                globSt = pm0 & 0xFFFF;
                                prio = 3 - cat;
                            }
                            int th3 = yzxToH3(lx, wy & 15, lz);
                            if (prio >= localOvPrio[th3]) {
                                localOvPrio[th3] = prio;
                                localOvGlob[th3] = globSt;
                                anyOvWritten = true;
                            }
                        }
                    }
                }

                if (chunkTreeAnchors9 != null && treeAnchors9Offset + 9 <= chunkTreeAnchors9.length) {
                    for (int dcz = -1; dcz <= 1; dcz++) {
                        for (int dcx = -1; dcx <= 1; dcx++) {
                            int anch = chunkTreeAnchors9[treeAnchors9Offset + (dcz + 1) * 3 + (dcx + 1)];
                            if ((anch & 0x60000000) != 0x40000000) continue;
                            int ncx = chunkX + dcx;
                            int ncz = chunkZ + dcz;
                            int aBiome0 = (anch >>> 24) & 7;
                            int treeSubCnt = (dcx == 0 && dcz == 0 && (aBiome0 == 1 || aBiome0 == 5)) ? 4 : 1;
                            int placedTrX0 = -99, placedTrZ0 = -99;
                            int placedTrX1 = -99, placedTrZ1 = -99;
                            int placedTrX2 = -99, placedTrZ2 = -99;
                            for (int tSub = 0; tSub < treeSubCnt; tSub++) {
                                int ax;
                                int az;
                                int baseWy;
                                int aBiome;
                                int aHash;
                                if (tSub == 0) {
                                    ax = anch & 15;
                                    az = (anch >>> 4) & 15;
                                    baseWy = Math.min(216, ((anch >>> 12) & 511) - 64) + 1;
                                    aBiome = aBiome0;
                                    aHash = mixAnchorHashCpu(ncx, ncz, worldSeed);
                                    placedTrX0 = ax;
                                    placedTrZ0 = az;
                                } else {
                                    aHash = mixAnchorHashCpu(ncx * 31 + tSub * 17, ncz * 31 - tSub * 13, worldSeed);
                                    ax = 2 + ((aHash & 0x7FFF) % 12);
                                    az = 2 + (((aHash >>> 8) & 0x7FFF) % 12);
                                    if ((Math.abs(ax - placedTrX0) <= 2 && Math.abs(az - placedTrZ0) <= 2)
                                            || (tSub >= 2 && Math.abs(ax - placedTrX1) <= 2 && Math.abs(az - placedTrZ1) <= 2)
                                            || (tSub >= 3 && Math.abs(ax - placedTrX2) <= 2 && Math.abs(az - placedTrZ2) <= 2)) {
                                        continue;
                                    }
                                    int qSy = surfY[(az << 4) | ax];
                                    if (qSy <= seaLevel || qSy > 216) continue;
                                    int qSec = Math.max(0, Math.min(23, (qSy + 64) >> 4));
                                    aBiome = biomes[qSec * 16 + (((az >>> 2) << 2) | (ax >>> 2))] & 7;
                                    if (aBiome != 1 && aBiome != 5) continue;
                                    baseWy = qSy + 1;
                                    if (tSub == 1) {
                                        placedTrX1 = ax;
                                        placedTrZ1 = az;
                                    } else if (tSub == 2) {
                                        placedTrX2 = ax;
                                        placedTrZ2 = az;
                                    }
                                }
                                int logSt;
                                int leafSt;
                                int trunkH;
                                if (aBiome == 5) {
                                    logSt = 35;
                                    leafSt = 36;
                                    trunkH = 6 + (aHash & 1);
                                } else if (aBiome == 3) {
                                    logSt = 39;
                                    leafSt = 40;
                                    trunkH = 5;
                                } else if (aBiome == 1 && (aHash & 3) == 0) {
                                    logSt = 37;
                                    leafSt = 38;
                                    trunkH = 5 + ((aHash >>> 3) & 1);
                                } else {
                                    logSt = 33;
                                    leafSt = 34;
                                    trunkH = 5 + ((aHash >>> 2) & 1);
                                }
                                int topTr = Math.min(223, baseWy + trunkH - 1);
                                if (dcx == 0 && dcz == 0) {
                                    int wy0 = Math.max(baseBy, baseWy);
                                    int wy1 = Math.min(topBy, topTr);
                                    for (int wy = wy0; wy <= wy1; wy++) {
                                        int th3 = yzxToH3(ax, wy & 15, az);
                                        if (3 >= localOvPrio[th3]) {
                                            localOvPrio[th3] = 3;
                                            localOvGlob[th3] = logSt;
                                            anyOvWritten = true;
                                        }
                                    }
                                }
                                int cStart = Math.max(baseWy + 2, topTr - 2);
                                int cEnd = Math.min(224, topTr + 1);
                                int wy0 = Math.max(baseBy, cStart);
                                int wy1 = Math.min(topBy, cEnd);
                                int worldAx = (ncx << 4) + ax;
                                int worldAz = (ncz << 4) + az;
                                for (int wy = wy0; wy <= wy1; wy++) {
                                    int rad = (wy >= topTr) ? 1 : 2;
                                    int ly = wy & 15;
                                    for (int dz = -rad; dz <= rad; dz++) {
                                        int wz = worldAz + dz;
                                        if ((wz >> 4) != chunkZ) continue;
                                        int lz = wz & 15;
                                        for (int dx = -rad; dx <= rad; dx++) {
                                            if (Math.abs(dx) == rad && Math.abs(dz) == rad && wy == cEnd) continue;
                                            int wx = worldAx + dx;
                                            if ((wx >> 4) != chunkX) continue;
                                            int th3 = yzxToH3(wx & 15, ly, lz);
                                            if (1 >= localOvPrio[th3]) {
                                                localOvPrio[th3] = 1;
                                                localOvGlob[th3] = leafSt;
                                                anyOvWritten = true;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                boolean didApply = false;
                if (anyOvWritten) {
                    for (int h3 = 0; h3 < 4096; h3++) {
                        int prio = localOvPrio[h3];
                        if (prio == 0) continue;
                        int cur = localVoxels[h3];
                        if (prio >= 4 || cur == 0 || (prio == 3 && cur >= 25 && cur < baseStateCount)) {
                            int globSt = localOvGlob[h3];
                            int locSt = globSt;
                            if (globSt >= baseStateCount) {
                                int found = -1;
                                for (int k = 0; k < extraCnt; k++) {
                                    if (localExtraU16[k] == globSt) {
                                        found = k;
                                        break;
                                    }
                                }
                                if (found >= 0) {
                                    locSt = baseStateCount + found;
                                } else if (extraCnt < SLOT4K_MAX_EXTRA_STATES
                                        && baseStateCount + extraCnt <= 255
                                        && runCursor + ((extraCnt + 1) << 1) <= LCA_SLOT_4K_BYTES) {
                                    localExtraU16[extraCnt] = globSt;
                                    locSt = baseStateCount + extraCnt;
                                    extraCnt++;
                                } else {
                                    overflowFlag = true;
                                    locSt = 0;
                                }
                            }
                            localVoxels[h3] = locSt;
                            didApply = true;
                            if (globSt != 0) {
                                int yzx = h3ToYzx(h3);
                                int col = (((yzx >>> 4) & 15) << 4) | (yzx & 15);
                                int wy = baseBy + ((yzx >>> 8) & 15);
                                short candRel = (short) Math.max(0, Math.min(511, (wy + 1) - (-64)));
                                if (candRel > localWs[col]) localWs[col] = candRel;
                                if (candRel > localMb[col]) localMb[col] = candRel;
                                if (prio != 1) {
                                    if (candRel > localMbnl[col]) localMbnl[col] = candRel;
                                    if (prio == 5 && candRel > localOf[col]) localOf[col] = candRel;
                                }
                            }
                        }
                    }
                }

                int dom = (baseBy < 0) ? 2 : ((topBy < minSurfY) ? 1 : 0);
                int slotLim = LCA_SLOT_4K_BYTES - (extraCnt << 1);
                int nonDomRuns = 0;
                long occMask = 0L;
                int secHeaderPos = runCursor;
                if (secHeaderPos + 4 <= slotLim) {
                    int writePtr = secHeaderPos + 4;
                    int idx = 0;
                    while (idx < 4096) {
                        int st = localVoxels[idx];
                        if (st == dom) {
                            idx++;
                            continue;
                        }
                        int rlen = 1;
                        while (idx + rlen < 4096 && rlen < 256 && localVoxels[idx + rlen] == st) {
                            rlen++;
                        }
                        if (writePtr + 4 <= slotLim) {
                            buf.putShort(writePtr, (short) idx);
                            buf.put(writePtr + 2, (byte) (rlen - 1));
                            buf.put(writePtr + 3, (byte) st);
                            writePtr += 4;
                            nonDomRuns++;
                            if (st >= 6) totalDecRuns++;
                            for (int ro = idx >>> 6; ro <= ((idx + rlen - 1) >>> 6); ro++) {
                                occMask |= (1L << ro);
                            }
                        } else {
                            overflowFlag = true;
                        }
                        idx += rlen;
                    }
                    if (nonDomRuns == 0) {
                        int sky2 = baseBy > maxSurfY ? 1 : 0;
                        buf.putShort(32 + (s << 1), (short) (0x8000 | (dom & 0x7FF)));
                        buf.putLong(80 + (s << 3), 0L);
                        buf.put(1736 + s, (byte) toGray4(sky2 != 0 ? 15 : 0));
                        uniformSections++;
                    } else {
                        byte gray = (byte) toGray4(topBy >= minSurfY ? 15 : 0);
                        byte skyTag = (byte) ((gray & 0xFF) | (didApply ? 0x80 : 0));
                        buf.put(secHeaderPos, (byte) dom);
                        buf.put(secHeaderPos + 1, gray);
                        buf.putShort(secHeaderPos + 2, (short) nonDomRuns);
                        buf.putShort(32 + (s << 1), (short) secHeaderPos);
                        buf.putLong(80 + (s << 3), occMask);
                        buf.put(1736 + s, skyTag);
                        runCursor = writePtr;
                    }
                } else {
                    overflowFlag = true;
                }
            }
        }

        for (int w = 0; w < 37; w++) {
            int baseCol = w * 7;
            long packedWs = 0L;
            long packedOf = 0L;
            long packedMb = 0L;
            long packedMbnl = 0L;
            for (int k = 0; k < 7; k++) {
                int col = baseCol + k;
                if (col < 256) {
                    int shift = k * 9;
                    packedWs |= ((long) (localWs[col] & 511)) << shift;
                    packedOf |= ((long) (localOf[col] & 511)) << shift;
                    packedMb |= ((long) (localMb[col] & 511)) << shift;
                    packedMbnl |= ((long) (localMbnl[col] & 511)) << shift;
                }
            }
            buf.putLong(272 + (w << 3), packedWs);
            buf.putLong(272 + ((37 + w) << 3), packedOf);
            buf.putLong(272 + ((74 + w) << 3), packedMb);
            buf.putLong(272 + ((111 + w) << 3), packedMbnl);
        }

        for (int k = 0; k < extraCnt; k++) {
            buf.putShort(LCA_SLOT_4K_BYTES - ((k + 1) << 1), (short) localExtraU16[k]);
        }

        int treeAnchor = (chunkTreeAnchors9 != null && treeAnchors9Offset + 4 < chunkTreeAnchors9.length)
                ? (chunkTreeAnchors9[treeAnchors9Offset + 4] & ~TREE_ANCHOR_CUSTOM_STAMP_SUPPRESS_BIT)
                : 0;
        int fnv = 0x811C9DC5;
        for (int bIdx = 32; bIdx < runCursor; bIdx++) {
            fnv ^= (outSlots[slotByteOffset + bIdx] & 0xFF);
            fnv *= 0x01000193;
        }
        for (int bIdx = LCA_SLOT_4K_BYTES - (extraCnt << 1); bIdx < LCA_SLOT_4K_BYTES; bIdx++) {
            fnv ^= (outSlots[slotByteOffset + bIdx] & 0xFF);
            fnv *= 0x01000193;
        }

        int packedUniformAndFlags = (uniformSections & 0x3F)
                | ((extraCnt & 0xFF) << 6)
                | SLOT4K_IN_KERNEL_STAMPED_FLAG
                | (overflowFlag ? SLOT4K_OVERFLOW_FLAG : 0);

        buf.putInt(0, LCA_SLOT_4K_MAGIC);
        buf.putInt(4, chunkX);
        buf.putInt(8, chunkZ);
        buf.putInt(12, runCursor);
        buf.putInt(16, fnv);
        buf.putShort(20, (short) packedUniformAndFlags);
        buf.putShort(22, (short) activeOctants);
        buf.putShort(24, (short) skippedOctants);
        buf.putShort(26, (short) totalDecRuns);
        buf.putInt(28, treeAnchor);
        return runCursor;
    }

    /**
     * Lifts the pure-Java {@code Stages K1 -> K2 -> K3} reference methods from
     * {@link WorldgenKernelSwapper} bytecode into a structured {@link ConceptModule} with a bounded
     * {@code < 8 KiB} {@link BytecodeConceptIr.ConceptWorkgroupPipeline}.
     */
    public static ConceptModule compileLiftedEndToEndLcaStagesModule(String cornerEvalFunctionName) {
        String effCornerFn = (cornerEvalFunctionName == null || cornerEvalFunctionName.isBlank())
                ? "eval_blended_point_f32"
                : cornerEvalFunctionName;
        ConceptModule cached = cachedLcaStagesModule;
        if (cached != null
                && cached.workgroupPipeline() != null
                && cached.workgroupPipeline().cornerEvalFunctionName().equals(effCornerFn)) {
            return cached;
        }
        synchronized (LCA_STAGE_LIFT_LOCK) {
            cached = cachedLcaStagesModule;
            if (cached != null
                    && cached.workgroupPipeline() != null
                    && cached.workgroupPipeline().cornerEvalFunctionName().equals(effCornerFn)) {
                return cached;
            }
            List<byte[]> classes = pullClassBytes(WorldgenKernelSwapper.class);
            String owner = WorldgenKernelSwapper.class.getName().replace('.', '/');
            String[][] stageMethodSpecs = {
                {"h3ToYzx", "(I)I"},
                {"yzxToH3", "(III)I"},
                {"toGray4", "(I)I"},
                {"fromGray4", "(I)I"},
                {"classifyClimateBiomeQuartet", "(FFFFI)I"},
                {"selectSurfaceStrataBlock", "(IIII)I"},
                {"computeVanillaAnchoredOreStartH3", "(IIIIJ)I"},
                {"evaluateOctantDiscardExposure", "(JIII)I"},
                {"encodeUniformSectionHeader", "(III)I"},
                {"selectUndergroundDecoratorState", "(III)I"},
                {"selectSurfaceDecoratorBlock", "(IIII)I"},
                {"computeChunkTreeAnchorPacked", "(IIIIIJ)I"}
            };
            List<ConceptFunction> stageFns = new ArrayList<>(stageMethodSpecs.length);
            for (String[] ms : stageMethodSpecs) {
                ConceptModule lifted = BytecodeSsaLifter.liftModule(
                        classes, owner, ms[0], ms[1], BytecodeSsaLifter.InstanceSnapshot.empty());
                ConceptModule opt = ConceptOptimizerPass.optimize(
                        lifted,
                        new ConceptOptimizerPass.OptimizationConfig(true, true, true, false, false));
                stageFns.add(opt.entryFunction());
            }
            List<BytecodeConceptIr.ConceptLocalBuffer> localBuffers = List.of(
                    new BytecodeConceptIr.ConceptLocalBuffer("local_corners", ConceptType.F32, 1225, Float.BYTES),
                    new BytecodeConceptIr.ConceptLocalBuffer("local_surf_y", ConceptType.I32, 256, Short.BYTES),
                    new BytecodeConceptIr.ConceptLocalBuffer("local_biomes", ConceptType.I32, 384, Byte.BYTES));
            BytecodeConceptIr.ConceptWorkgroupPipeline wg = new BytecodeConceptIr.ConceptWorkgroupPipeline(
                    "evaluate_end_to_end_lca_chunk_batch",
                    64,
                    effCornerFn,
                    localBuffers,
                    stageFns);
            ConceptModule module = new ConceptModule(
                    "lca_end_to_end_stages_k1_k2_k3",
                    List.of(),
                    List.of(),
                    stageFns.get(0),
                    null,
                    wg);
            cachedLcaStagesModule = module;
            return module;
        }
    }

    /**
     * Emits the OpenCL C99 source for the bytecode-lifted {@code Stages K1 -> K2 -> K3} device
     * functions and {@code evaluate_end_to_end_lca_chunk_batch} workgroup kernel via
     * {@link OpenClBackendEmitter}.
     */
    public static String emitLiftedEndToEndLcaStagesOpenCl(String cornerEvalFunctionName) {
        ConceptModule module = compileLiftedEndToEndLcaStagesModule(cornerEvalFunctionName);
        EmitOptions opts = new EmitOptions(
                ConceptBackendEmitter.FloatingPointPrecision.DOWNCAST_FP32,
                false,
                ConceptOptimizerPass.OptimizationConfig.defaults());
        TranspiledArtifact artifact = new OpenClBackendEmitter().emit(module, opts);
        String src = artifact.sourceCode();
        // Strip duplicate header pragma comment when appending to a corner density module
        int firstFn = src.indexOf("inline ");
        return firstFn >= 0 ? "\n" + src.substring(firstFn) : src;
    }
}
