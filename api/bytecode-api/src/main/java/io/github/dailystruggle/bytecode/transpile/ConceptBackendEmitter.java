package io.github.dailystruggle.bytecode.transpile;

import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptModule;
import io.github.dailystruggle.bytecode.transpile.ConceptOptimizerPass.OptimizationConfig;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extensible single-interface backend emitter SPI and registry for lowering optimized
 * {@link BytecodeConceptIr} modules into target languages (OpenCL C99, Rust, WGSL, C, etc.).
 *
 * <p>Adding a new target language requires only implementing {@link ConceptBackendEmitter} and
 * registering it via {@link #registerBackend(ConceptBackendEmitter)}; bytecode lifting and
 * whole-program optimization remain completely unchanged.
 *
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy corpus-atlas-ADR-002
 * @trace REQ-CFG-ATLAS-001
 */
public interface ConceptBackendEmitter {

    /**
     * Floating-point precision mode for emitted kernels and functions.
     */
    enum FloatingPointPrecision {
        /**
         * Preserves 1:1 JVM {@code double} ({@code F64}) precision (enables {@code cl_khr_fp64}
         * in OpenCL and emits {@code f64} in Rust).
         */
        NATIVE_FP64,

        /**
         * Downcasts {@code F64} operations and literals to 32-bit {@code float} ({@code F32})
         * for high-throughput consumer GPU execution.
         */
        DOWNCAST_FP32
    }

    /**
     * Arithmetic lowering mode for emitted kernels and device functions.
     */
    enum GpuExactArithmeticMode {
        /**
         * Preserves 1:1 IEEE-754 64-bit floating-point ({@code F64} / {@code cl_khr_fp64}).
         */
        NATIVE_FP64,

        /**
         * Downcasts all {@code F64} operations and literals to 32-bit {@code float} ({@code F32}).
         */
        DOWNCAST_FP32,

        /**
         * Lowers large-coordinate {@code F64} scaling, floor, wrap, and integer extraction chains
         * to 64-bit Q32.32 fixed-point integer arithmetic while lowering bounded fractional
         * interpolation and blending to native 32-bit {@code float} (zero {@code FP64} instructions).
         */
        INT64_Q32_FP32;

        public FloatingPointPrecision toFloatingPointPrecision() {
            return this == NATIVE_FP64
                    ? FloatingPointPrecision.NATIVE_FP64
                    : FloatingPointPrecision.DOWNCAST_FP32;
        }

        public static GpuExactArithmeticMode fromFloatingPointPrecision(FloatingPointPrecision precision) {
            Objects.requireNonNull(precision, "precision");
            return precision == FloatingPointPrecision.NATIVE_FP64
                    ? NATIVE_FP64
                    : DOWNCAST_FP32;
        }

        public boolean usesNativeFp64() {
            return this == NATIVE_FP64;
        }

        public boolean usesInt64Q32() {
            return this == INT64_Q32_FP32;
        }
    }

    /**
     * Data-parallel 3D evaluation batch layout for emitted kernels and CPU batch drivers.
     */
    enum BatchLayout {
        /**
         * Standard 3D grid evaluation over {@code (dim_x, dim_y, dim_z)} from an origin and step scale.
         */
        SINGLE_POINT,

        /**
         * Batched {@code 4x8x4} cell-corner lattice evaluation for {@code batch_count} origin tiles
         * ({@code bx = orig.x + (cx << 2)}, {@code by = orig.y + (cy << 3)}, {@code bz = orig.z + (cz << 2)}).
         */
        CORNER_GRID_4X8X4,

        /**
         * Batched {@code 16x16x16} ({@code 4096}-voxel) 3D section evaluation writing one output
         * element per voxel in {@code (ly << 8) | (lz << 4) | lx} order.
         */
        SECTION_4096_VOXEL,

        /**
         * Batched {@code 16x16x16} ({@code 4096}-voxel) 3D section evaluation packing 4-bit nibbles
         * with 16-workgroup uniformity metadata ({@code 516} 32-bit words per section).
         */
        SECTION_4096_LCA_NIBBLE_PACKED,

        /**
         * Batched {@code 5x49} corner-slice evaluation packing IEEE-754 {@code double} (2 words per
         * corner in {@code NATIVE_FP64}) or {@code float} (1 word per corner in {@code FP32} /
         * {@code INT64_Q32_FP32}) into {@code 516}-word slice frames.
         */
        CORNER_SLICE_5X49_PACKED,

        /**
         * Batched 3D {@code DensityVolume} evaluation writing {@code float} samples in exact
         * stride-1 {@code (lx + lz * size_x) * size_y + ly} order for {@code batch_count} volumes
         * with configurable {@code (size_x, size_y, size_z)} and {@code (step_x, step_y, step_z)}.
         */
        DENSITY_VOLUME_BATCH_F32,

        /**
         * Fused 3D section-palette terrain fill writing 2-bit palette codes ({@code 0=AIR},
         * {@code 1=DEFAULT_BLOCK}, {@code 2=DEFAULT_FLUID}, {@code 3=DEEP_FLUID}) packed in exact
         * {@code (ly << 8) | (lz << 4) | lx} order ({@code 256} {@code uint32} words = {@code 1024 B}
         * per {@code 16x16x16} section) plus per-section {@code ushort4(nonEmpty, fluid, codeMask, 0)}
         * metadata headers for compact zero-allocation DMA writeback.
         */
        SECTION_PALETTE_FILL_U2
    }

    /**
     * Backend code generation options.
     */
    record EmitOptions(
            FloatingPointPrecision fpPrecision,
            boolean emit3DKernel,
            OptimizationConfig optimizationConfig,
            GpuExactArithmeticMode gpuExactArithmeticMode,
            BatchLayout batchLayout
    ) {
        public EmitOptions {
            Objects.requireNonNull(fpPrecision, "fpPrecision");
            Objects.requireNonNull(optimizationConfig, "optimizationConfig");
            Objects.requireNonNull(gpuExactArithmeticMode, "gpuExactArithmeticMode");
            Objects.requireNonNull(batchLayout, "batchLayout");
        }

        public EmitOptions(
                FloatingPointPrecision fpPrecision,
                boolean emit3DKernel,
                OptimizationConfig optimizationConfig) {
            this(
                    fpPrecision,
                    emit3DKernel,
                    optimizationConfig,
                    GpuExactArithmeticMode.fromFloatingPointPrecision(fpPrecision),
                    BatchLayout.SINGLE_POINT);
        }

        public static EmitOptions defaults() {
            return new EmitOptions(
                    FloatingPointPrecision.NATIVE_FP64,
                    true,
                    OptimizationConfig.defaults(),
                    GpuExactArithmeticMode.NATIVE_FP64,
                    BatchLayout.SINGLE_POINT);
        }

        public static EmitOptions gpuFp64() {
            return defaults();
        }

        public static EmitOptions gpuFp32() {
            return new EmitOptions(
                    FloatingPointPrecision.DOWNCAST_FP32,
                    true,
                    OptimizationConfig.defaults(),
                    GpuExactArithmeticMode.DOWNCAST_FP32,
                    BatchLayout.SINGLE_POINT);
        }

        public static EmitOptions gpuInt64Q32Fp32() {
            return new EmitOptions(
                    FloatingPointPrecision.DOWNCAST_FP32,
                    true,
                    OptimizationConfig.defaults(),
                    GpuExactArithmeticMode.INT64_Q32_FP32,
                    BatchLayout.SINGLE_POINT);
        }

        public static EmitOptions of(GpuExactArithmeticMode arithmeticMode, BatchLayout batchLayout) {
            return of(arithmeticMode, batchLayout, OptimizationConfig.defaults());
        }

        public static EmitOptions of(
                GpuExactArithmeticMode arithmeticMode,
                BatchLayout batchLayout,
                OptimizationConfig optimizationConfig) {
            Objects.requireNonNull(arithmeticMode, "arithmeticMode");
            Objects.requireNonNull(batchLayout, "batchLayout");
            return new EmitOptions(
                    arithmeticMode.toFloatingPointPrecision(),
                    true,
                    optimizationConfig,
                    arithmeticMode,
                    batchLayout);
        }

        public EmitOptions withArithmeticMode(GpuExactArithmeticMode arithmeticMode) {
            Objects.requireNonNull(arithmeticMode, "arithmeticMode");
            return new EmitOptions(
                    arithmeticMode.toFloatingPointPrecision(),
                    emit3DKernel,
                    optimizationConfig,
                    arithmeticMode,
                    batchLayout);
        }

        public EmitOptions withBatchLayout(BatchLayout layout) {
            Objects.requireNonNull(layout, "layout");
            return new EmitOptions(
                    fpPrecision,
                    emit3DKernel,
                    optimizationConfig,
                    gpuExactArithmeticMode,
                    layout);
        }
    }

    /**
     * Emitted source artifact from a {@link ConceptBackendEmitter}.
     */
    record TranspiledArtifact(
            String backendId,
            String fileExtension,
            String moduleName,
            String entryFunctionName,
            String kernel3DName,
            FloatingPointPrecision fpPrecision,
            String sourceCode,
            ConceptModule optimizedModule
    ) {
        public TranspiledArtifact {
            Objects.requireNonNull(backendId, "backendId");
            Objects.requireNonNull(fileExtension, "fileExtension");
            Objects.requireNonNull(moduleName, "moduleName");
            Objects.requireNonNull(entryFunctionName, "entryFunctionName");
            Objects.requireNonNull(fpPrecision, "fpPrecision");
            Objects.requireNonNull(sourceCode, "sourceCode");
            Objects.requireNonNull(optimizedModule, "optimizedModule");
        }
    }

    /**
     * Unique lowercase identifier for this target backend (e.g. {@code "opencl"}, {@code "rust"}).
     */
    String backendId();

    /**
     * Default source file extension including leading dot (e.g. {@code ".cl"}, {@code ".rs"}).
     */
    String fileExtension();

    /**
     * Lowers an optimized {@link ConceptModule} into target language source code.
     */
    TranspiledArtifact emit(ConceptModule module, EmitOptions options);

    // -------------------------------------------------------------------------
    // Extensible Backend Registry & End-to-End Transpile Pipeline
    // -------------------------------------------------------------------------

    Map<String, ConceptBackendEmitter> REGISTRY = DefaultRegistry.init();

    static void registerBackend(ConceptBackendEmitter emitter) {
        Objects.requireNonNull(emitter, "emitter");
        REGISTRY.put(emitter.backendId().toLowerCase(), emitter);
    }

    static ConceptBackendEmitter resolveBackend(String backendId) {
        Objects.requireNonNull(backendId, "backendId");
        ConceptBackendEmitter emitter = REGISTRY.get(backendId.toLowerCase());
        if (emitter == null) {
            throw new IllegalArgumentException(
                    "Unknown ConceptBackendEmitter '" + backendId + "'. Registered: " + REGISTRY.keySet());
        }
        return emitter;
    }

    static Set<String> registeredBackends() {
        return Collections.unmodifiableSet(new LinkedHashMap<>(REGISTRY).keySet());
    }

    /**
     * End-to-end pipeline: lifts JVM {@code .class} bytes into {@link BytecodeConceptIr}, runs
     * whole-program optimization ({@link ConceptOptimizerPass}), and emits target code via the
     * requested {@link ConceptBackendEmitter}.
     */
    static TranspiledArtifact transpile(
            Collection<byte[]> classBytesCollection,
            String entryClassName,
            String entryMethodName,
            String backendId,
            EmitOptions options) {
        Objects.requireNonNull(options, "options");
        ConceptBackendEmitter emitter = resolveBackend(backendId);
        ConceptModule lifted = BytecodeSsaLifter.liftModule(
                classBytesCollection,
                entryClassName,
                entryMethodName);
        ConceptModule optimized = ConceptOptimizerPass.optimize(lifted, options);
        return emitter.emit(optimized, options);
    }

    final class DefaultRegistry {
        private DefaultRegistry() {
        }

        static Map<String, ConceptBackendEmitter> init() {
            Map<String, ConceptBackendEmitter> map = new ConcurrentHashMap<>();
            OpenClBackendEmitter opencl = new OpenClBackendEmitter();
            RustBackendEmitter rust = new RustBackendEmitter();
            JavaConceptBackendEmitter java = JavaConceptBackendEmitter.javaBackend();
            JavaConceptBackendEmitter jminor = JavaConceptBackendEmitter.jminorBackend();
            map.put(opencl.backendId(), opencl);
            map.put(rust.backendId(), rust);
            map.put(java.backendId(), java);
            map.put(jminor.backendId(), jminor);
            return map;
        }
    }
}
