package io.github.dailystruggle.bytecode.transpile;

import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.BinaryOperator;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptBatchSpec;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptExpr;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptFunction;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptKernel3D;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptLocalBuffer;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptLut;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptModule;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptParam;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptStmt;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptType;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptWorkgroupPipeline;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.MathIntrinsicKind;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.UnaryOperator;
import io.github.dailystruggle.bytecode.transpile.ConceptOptimizerPass.Q32LoweringPlan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * OpenCL C99 GPU backend emitter implementing {@link ConceptBackendEmitter}.
 *
 * <p>Emits deduplicated {@code __constant} lookup tables, {@code inline} device helper functions,
 * and batched 3D work-item kernels ({@link BatchLayout#SINGLE_POINT},
 * {@link BatchLayout#CORNER_GRID_4X8X4}, {@link BatchLayout#SECTION_4096_VOXEL}, and
 * {@link BatchLayout#SECTION_4096_LCA_NIBBLE_PACKED}) across all three generic arithmetic modes
 * ({@link GpuExactArithmeticMode#NATIVE_FP64}, {@link GpuExactArithmeticMode#DOWNCAST_FP32}, and
 * {@link GpuExactArithmeticMode#INT64_Q32_FP32}) with bit-exact JVM unsigned shift ({@code >>>})
 * semantics via {@code ulong}/{@code uint} casts.
 *
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy corpus-atlas-ADR-002
 * @trace REQ-CFG-ATLAS-001
 */
public final class OpenClBackendEmitter implements ConceptBackendEmitter {

    public static final String BACKEND_ID = "opencl";

    /**
     * Conservative {@code __constant} address-space budget ({@code 48 KiB}) leaving safe headroom
     * below the {@code 64 KiB} hardware constant bank on consumer GPUs before spilling excess LUTs.
     */
    public static final int MAX_CONSTANT_BUDGET_BYTES = 49_152;

    /** Bit flag in {@code cparams[10]} requesting GPU workgroup IR evaluation of cell corners. */
    public static final int CAP_IR_FLAG_GPU_CORNERS = 1;

    /** Bit flag in {@code cparams[10]} skipping biome RTree writes/IO on {@code NOISE}-only passes. */
    public static final int CAP_IR_FLAG_SKIP_BIOME_IO = 2;

    /** Bit flag in {@code cparams[10]} adding host-computed sparse structure beard delta from {@code c_corners} onto GPU IR corners. */
    public static final int CAP_IR_FLAG_ADD_HOST_BEARD_DELTA = 4;

    /** Bit flag in {@code cparams[10]} executing lifted {@code CarverSpec} ellipsoid carving on GPU. */
    public static final int CAP_IR_FLAG_GPU_CARVERS = 8;

    /** Flag bit encoded into {@code c_top_y[col]} in {@code VANILLA_EXACT} when any voxel in the column fell inside the sign-uncertainty band. */
    public static final int CAP_UNCERTAIN_SIGN_CPU_REFINE_FLAG = 0x40000000;

    /** Flag bit encoded into {@code c_top_y[0]} when GPU carver segment capacity overflowed and host fallback is required. */
    public static final int CAP_CARVER_OVERFLOW_FALLBACK_FLAG = 0x20000000;

    private record LutBinding(
            String emittedSymbol,
            IrIdiomRewriteRegistry.CompactStorageKind storageKind,
            boolean spilled,
            int spillWordOffset
    ) {
    }

    private record EmitScope(
            String symbolPrefix,
            ConstantLutAllocator lutAllocator,
            boolean passGlobalSpillParam,
            Map<String, Integer> callCountByFnName,
            Map<String, Integer> memoSlotByFnName,
            int memo2dKeepMask,
            String memoElemType
    ) {
    }

    private record DiamondMemoPlan(
            List<ConceptFunction> memoHelpers,
            Map<String, Integer> fnNameToSlot,
            int keep2dMask,
            String elemType
    ) {
        static final DiamondMemoPlan EMPTY = new DiamondMemoPlan(List.of(), Map.of(), 0, null);

        boolean isActive() {
            return elemType != null && !memoHelpers.isEmpty();
        }

        int slotCount() {
            return memoHelpers.size();
        }
    }

    private record FinalDensitySectionResult(String sourceCode, DiamondMemoPlan memoPlan) {
    }

    private static final ThreadLocal<EmitScope> ACTIVE_EMIT_SCOPE = new ThreadLocal<>();

    /**
     * Compiled capability OpenCL source paired with any packed {@code __global const int[]} words
     * spilled past the {@code 48 KiB} {@code __constant} address-space budget ({@code REQ-LEAFGEN-IR-004}).
     */
    public record CapabilityKernelProgram(
            String sourceCode,
            int[] spilledLutWords,
            int usedConstantBytes,
            int spilledLutCount,
            int deduplicatedLutCount
    ) {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CapabilityKernelProgram other)) {
                return false;
            }
            return java.util.Objects.equals(this.sourceCode, other.sourceCode)
                    && java.util.Arrays.equals(this.spilledLutWords, other.spilledLutWords)
                    && this.usedConstantBytes == other.usedConstantBytes
                    && this.spilledLutCount == other.spilledLutCount
                    && this.deduplicatedLutCount == other.deduplicatedLutCount;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + java.util.Objects.hashCode(this.sourceCode);
            result = 31 * result + java.util.Arrays.hashCode(this.spilledLutWords);
            result = 31 * result + this.usedConstantBytes;
            result = 31 * result + this.spilledLutCount;
            result = 31 * result + this.deduplicatedLutCount;
            return result;
        }

        @Override
        public String toString() {
            return "CapabilityKernelProgram[sourceCode=" + this.sourceCode
                    + ", spilledLutWords=" + java.util.Arrays.toString(this.spilledLutWords)
                    + ", usedConstantBytes=" + this.usedConstantBytes
                    + ", spilledLutCount=" + this.spilledLutCount
                    + ", deduplicatedLutCount=" + this.deduplicatedLutCount
                    + "]";
        }
    }

    /**
     * Cross-module {@code __constant} lookup-table allocator performing bounded {@code I32}
     * compaction ({@code uchar}/{@code char}/{@code short}), content-based deduplication, and
     * either packed {@code __global const int*} device-buffer spill (for capability kernels) or
     * OpenCL C 1.2 lookup helpers whenever cumulative constant bytes exceed
     * {@link #MAX_CONSTANT_BUDGET_BYTES}.
     */
    public static final class ConstantLutAllocator {
        private final int maxConstantBytes;
        private final String globalSpillBufferName;
        private int usedConstantBytes;
        private int spilledLutCount;
        private int deduplicatedLutCount;
        private int compactedBytesSaved;
        private final Map<String, LutBinding> payloadKeyToBinding = new LinkedHashMap<>();
        private final Map<String, LutBinding> symbolToBinding = new LinkedHashMap<>();
        private int[] spilledWordsBuf = new int[0];
        private int spilledWordsLen = 0;

        public ConstantLutAllocator(int maxConstantBytes, int initialUsedBytes) {
            this(maxConstantBytes, initialUsedBytes, null);
        }

        public ConstantLutAllocator(int maxConstantBytes, int initialUsedBytes, String globalSpillBufferName) {
            this.maxConstantBytes = Math.max(0, maxConstantBytes);
            this.usedConstantBytes = Math.max(0, initialUsedBytes);
            this.globalSpillBufferName =
                    (globalSpillBufferName == null || globalSpillBufferName.isBlank()) ? null : globalSpillBufferName;
        }

        public int usedConstantBytes() {
            return usedConstantBytes;
        }

        public int spilledLutCount() {
            return spilledLutCount;
        }

        public int deduplicatedLutCount() {
            return deduplicatedLutCount;
        }

        public int compactedBytesSaved() {
            return compactedBytesSaved;
        }

        public int[] spilledWordsArray() {
            if (spilledWordsLen == 0) {
                return new int[0];
            }
            return java.util.Arrays.copyOf(spilledWordsBuf, spilledWordsLen);
        }

        private void appendSpillWord(int word) {
            if (spilledWordsLen >= spilledWordsBuf.length) {
                int next = spilledWordsBuf.length == 0 ? 1024 : (spilledWordsBuf.length << 1);
                spilledWordsBuf = java.util.Arrays.copyOf(spilledWordsBuf, next);
            }
            spilledWordsBuf[spilledWordsLen++] = word;
        }

        private int packIntoGlobalSpill(IrIdiomRewriteRegistry.CompactStorageKind kind, List<Number> vals) {
            int baseOffset = spilledWordsLen;
            int n = vals.size();
            switch (kind) {
                case U8, I8 -> {
                    for (int i = 0; i < n; i += 4) {
                        int w = (vals.get(i).intValue() & 0xFF);
                        if (i + 1 < n) w |= (vals.get(i + 1).intValue() & 0xFF) << 8;
                        if (i + 2 < n) w |= (vals.get(i + 2).intValue() & 0xFF) << 16;
                        if (i + 3 < n) w |= (vals.get(i + 3).intValue() & 0xFF) << 24;
                        appendSpillWord(w);
                    }
                    if (n == 0) appendSpillWord(0);
                }
                case I16 -> {
                    for (int i = 0; i < n; i += 2) {
                        int w = (vals.get(i).intValue() & 0xFFFF);
                        if (i + 1 < n) w |= (vals.get(i + 1).intValue() & 0xFFFF) << 16;
                        appendSpillWord(w);
                    }
                    if (n == 0) appendSpillWord(0);
                }
                case I32 -> {
                    for (int i = 0; i < n; i++) {
                        appendSpillWord(vals.get(i).intValue());
                    }
                    if (n == 0) appendSpillWord(0);
                }
                case F32 -> {
                    for (int i = 0; i < n; i++) {
                        appendSpillWord(Float.floatToRawIntBits(vals.get(i).floatValue()));
                    }
                    if (n == 0) appendSpillWord(0);
                }
                case I64 -> {
                    for (int i = 0; i < n; i++) {
                        long v = vals.get(i).longValue();
                        appendSpillWord((int) v);
                        appendSpillWord((int) (v >>> 32));
                    }
                    if (n == 0) {
                        appendSpillWord(0);
                        appendSpillWord(0);
                    }
                }
                case F64 -> {
                    for (int i = 0; i < n; i++) {
                        long bits = Double.doubleToRawLongBits(vals.get(i).doubleValue());
                        appendSpillWord((int) bits);
                        appendSpillWord((int) (bits >>> 32));
                    }
                    if (n == 0) {
                        appendSpillWord(0);
                        appendSpillWord(0);
                    }
                }
            }
            return baseOffset;
        }

        void allocateAndEmit(StringBuilder sb, ConceptLut lut, EmitOptions options, String symbolPrefix) {
            boolean nativeFp64 = options.gpuExactArithmeticMode().usesNativeFp64();
            IrIdiomRewriteRegistry.CompactLutHint hint =
                    IrIdiomRewriteRegistry.analyzeLutCompaction(lut, nativeFp64);
            IrIdiomRewriteRegistry.CompactStorageKind kind = hint.storageKind();
            String payloadKey = buildPayloadKey(kind, lut.values());
            String scopedKey = symbolPrefix + lut.symbolName();

            LutBinding existing = payloadKeyToBinding.get(payloadKey);
            if (existing != null) {
                symbolToBinding.put(scopedKey, existing);
                deduplicatedLutCount++;
                return;
            }

            String emittedSym = symbolPrefix + lut.symbolName();
            if (usedConstantBytes + hint.compactBytes() <= maxConstantBytes) {
                emitCompactConstantArray(sb, emittedSym, kind, lut.values());
                usedConstantBytes += hint.compactBytes();
                compactedBytesSaved += hint.savedBytes();
                LutBinding binding = new LutBinding(emittedSym, kind, false, -1);
                payloadKeyToBinding.put(payloadKey, binding);
                symbolToBinding.put(scopedKey, binding);
            } else if (globalSpillBufferName != null) {
                int wordOff = packIntoGlobalSpill(kind, lut.values());
                spilledLutCount++;
                LutBinding binding = new LutBinding(globalSpillBufferName, kind, true, wordOff);
                payloadKeyToBinding.put(payloadKey, binding);
                symbolToBinding.put(scopedKey, binding);
            } else {
                String spillFn = emittedSym + "_spill";
                emitSpillLookupHelper(sb, spillFn, kind, lut.values());
                spilledLutCount++;
                LutBinding binding = new LutBinding(spillFn, kind, true, -1);
                payloadKeyToBinding.put(payloadKey, binding);
                symbolToBinding.put(scopedKey, binding);
            }
        }

        String emitLutRead(String lutSymbol, String idxStr, String symbolPrefix) {
            LutBinding binding = symbolToBinding.get(symbolPrefix + lutSymbol);
            if (binding == null) {
                binding = symbolToBinding.get(lutSymbol);
            }
            if (binding == null) {
                return symbolPrefix + lutSymbol + "[" + idxStr + "]";
            }
            if (binding.spilled()) {
                if (binding.spillWordOffset() >= 0) {
                    String g = binding.emittedSymbol();
                    int off = binding.spillWordOffset();
                    String i = "(" + idxStr + ")";
                    return switch (binding.storageKind()) {
                        case U8 -> "((int)(((" + g + "[" + off + " + (" + i + " >> 2)]) >> ((" + i + " & 3) << 3)) & 0xFF))";
                        case I8 -> "((int)(char)(((" + g + "[" + off + " + (" + i + " >> 2)]) >> ((" + i + " & 3) << 3)) & 0xFF))";
                        case I16 -> "((int)(short)(((" + g + "[" + off + " + (" + i + " >> 1)]) >> ((" + i + " & 1) << 4)) & 0xFFFF))";
                        case I32 -> "(" + g + "[" + off + " + " + i + "])";
                        case F32 -> "as_float(" + g + "[" + off + " + " + i + "])";
                        case I64 -> "((long)(((ulong)(uint)" + g + "[" + off + " + (" + i + " << 1)]) | (((ulong)(uint)"
                                + g + "[" + off + " + (" + i + " << 1) + 1]) << 32)))";
                        case F64 -> "as_double(((ulong)(uint)" + g + "[" + off + " + (" + i + " << 1)]) | (((ulong)(uint)"
                                + g + "[" + off + " + (" + i + " << 1) + 1]) << 32))";
                    };
                }
                return binding.emittedSymbol() + "(" + idxStr + ")";
            }
            if (binding.storageKind().requiresIntPromotion()) {
                return "((int)" + binding.emittedSymbol() + "[" + idxStr + "])";
            }
            return binding.emittedSymbol() + "[" + idxStr + "]";
        }

        private static String buildPayloadKey(
                IrIdiomRewriteRegistry.CompactStorageKind kind, List<Number> vals) {
            StringBuilder kb = new StringBuilder(vals.size() * 6 + 16);
            kb.append(kind.name()).append(':').append(vals.size()).append(':');
            for (Number v : vals) {
                if (kind == IrIdiomRewriteRegistry.CompactStorageKind.F64) {
                    kb.append(Double.doubleToRawLongBits(v.doubleValue())).append(',');
                } else if (kind == IrIdiomRewriteRegistry.CompactStorageKind.F32) {
                    kb.append(Float.floatToRawIntBits(v.floatValue())).append(',');
                } else if (kind == IrIdiomRewriteRegistry.CompactStorageKind.I64) {
                    kb.append(v.longValue()).append(',');
                } else {
                    kb.append(v.intValue()).append(',');
                }
            }
            return IrPatternDiagnostic.fingerprintText(kb.toString()) + ":" + kind.name() + ":" + vals.size();
        }

        private static void emitCompactConstantArray(
                StringBuilder sb,
                String emittedSym,
                IrIdiomRewriteRegistry.CompactStorageKind kind,
                List<Number> vals) {
            sb.append("__constant ")
                    .append(kind.openClType())
                    .append(' ')
                    .append(emittedSym)
                    .append('[')
                    .append(Math.max(1, vals.size()))
                    .append("] = {\n    ");
            if (vals.isEmpty()) {
                sb.append("0");
            } else {
                for (int i = 0; i < vals.size(); i++) {
                    sb.append(formatLutLiteral(vals.get(i), kind));
                    if (i + 1 < vals.size()) {
                        sb.append(", ");
                        if ((i + 1) % 16 == 0) {
                            sb.append("\n    ");
                        }
                    }
                }
            }
            sb.append("\n};\n");
        }

        private static void emitSpillLookupHelper(
                StringBuilder sb,
                String spillFn,
                IrIdiomRewriteRegistry.CompactStorageKind kind,
                List<Number> vals) {
            String retType = kind.requiresIntPromotion() ? "int" : kind.openClType();
            String zeroLit = switch (kind) {
                case F64 -> "0.0";
                case F32 -> "0.0f";
                case I64 -> "0L";
                default -> "0";
            };
            sb.append("inline ")
                    .append(retType)
                    .append(' ')
                    .append(spillFn)
                    .append("(int idx) {\n    switch (idx) {\n");
            for (int i = 0; i < vals.size(); i++) {
                sb.append("        case ")
                        .append(i)
                        .append(": return ")
                        .append(formatLutLiteral(vals.get(i), kind))
                        .append(";\n");
            }
            sb.append("        default: return ").append(zeroLit).append(";\n    }\n}\n");
        }

        private static String formatLutLiteral(Number v, IrIdiomRewriteRegistry.CompactStorageKind kind) {
            return switch (kind) {
                case I64 -> formatLong(v.longValue());
                case F32 -> formatFloat(v.floatValue());
                case F64 -> formatDouble(v.doubleValue());
                default -> formatInt(v.intValue());
            };
        }
    }

    @Override
    public String backendId() {
        return BACKEND_ID;
    }

    @Override
    public String fileExtension() {
        return ".cl";
    }

    @Override
    public TranspiledArtifact emit(ConceptModule module, EmitOptions options) {
        return emitWithAllocator(
                module, options, "", new ConstantLutAllocator(MAX_CONSTANT_BUDGET_BYTES, 0), true);
    }

    /**
     * Emits OpenCL C99 code for {@code module} using a caller-provided {@link ConstantLutAllocator}
     * and optional symbol prefix for cross-module LUT deduplication and {@code __constant} spill.
     */
    public TranspiledArtifact emitWithAllocator(
            ConceptModule module,
            EmitOptions options,
            String symbolPrefix,
            ConstantLutAllocator lutAllocator,
            boolean emitKernels) {
        return emitWithAllocatorAndMemo(module, options, symbolPrefix, lutAllocator, emitKernels, DiamondMemoPlan.EMPTY);
    }

    private TranspiledArtifact emitWithAllocatorAndMemo(
            ConceptModule module,
            EmitOptions options,
            String symbolPrefix,
            ConstantLutAllocator lutAllocator,
            boolean emitKernels,
            DiamondMemoPlan memoPlan) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(options, "options");
        String effPrefix = symbolPrefix == null ? "" : symbolPrefix;
        ConstantLutAllocator effAllocator = lutAllocator != null
                ? lutAllocator
                : new ConstantLutAllocator(MAX_CONSTANT_BUDGET_BYTES, 0);
        DiamondMemoPlan effMemo = memoPlan != null ? memoPlan : DiamondMemoPlan.EMPTY;

        ConceptModule lowered = ConceptOptimizerPass.deduplicateLookupTables(module);
        GpuExactArithmeticMode arithmeticMode = options.gpuExactArithmeticMode();
        Q32LoweringPlan q32Plan = arithmeticMode.usesInt64Q32()
                ? ConceptOptimizerPass.analyzeQ32Lowering(lowered)
                : Q32LoweringPlan.empty();

        Map<String, ConceptFunction> fnByKey = new LinkedHashMap<>();
        Map<String, ConceptFunction> fnByName = new LinkedHashMap<>();
        registerFunctionLookup(lowered, fnByKey, fnByName);
        Map<String, Integer> callCountByFnName = countFunctionCallSites(lowered, fnByKey, fnByName);

        boolean passGlobalSpillParam = effAllocator.globalSpillBufferName != null;
        EmitScope prevScope = ACTIVE_EMIT_SCOPE.get();
        ACTIVE_EMIT_SCOPE.set(new EmitScope(
                effPrefix,
                effAllocator,
                passGlobalSpillParam,
                callCountByFnName,
                effMemo.fnNameToSlot(),
                effMemo.keep2dMask(),
                effMemo.elemType()));
        try {
            StringBuilder sb = new StringBuilder(2048);
            sb.append("// Auto-generated OpenCL C99 kernel from JVM bytecode module: ")
                    .append(lowered.moduleName())
                    .append('\n');
            if (arithmeticMode == GpuExactArithmeticMode.NATIVE_FP64) {
                sb.append("#pragma OPENCL EXTENSION cl_khr_fp64 : enable\n");
                sb.append("#pragma OPENCL FP_CONTRACT OFF\n\n");
            } else if (arithmeticMode == GpuExactArithmeticMode.INT64_Q32_FP32) {
                sb.append("// Floating-point mode: INT64_Q32_FP32 (64-bit Q32.32 coordinate arithmetic + FP32 interpolation)\n");
                sb.append("#pragma OPENCL FP_CONTRACT OFF\n\n");
                emitQ32Helpers(sb);
                sb.append('\n');
            } else {
                sb.append("// Floating-point mode: DOWNCAST_FP32\n");
                sb.append("#pragma OPENCL FP_CONTRACT OFF\n\n");
            }

            for (ConceptLut lut : lowered.lookupTables()) {
                if (arithmeticMode.usesInt64Q32()
                        && lut.elementType() == ConceptType.F64
                        && q32Plan.q32LutSymbol(lut.symbolName()) != null
                        && !q32Plan.isFp32ReferencedLut(lut.symbolName())) {
                    continue;
                }
                effAllocator.allocateAndEmit(sb, lut, options, effPrefix);
                sb.append('\n');
            }
            if (arithmeticMode.usesInt64Q32()) {
                for (ConceptLut q32Lut : q32Plan.synthesizedQ32Luts()) {
                    effAllocator.allocateAndEmit(sb, q32Lut, options, effPrefix);
                    sb.append('\n');
                }
            }

            // Emit forward prototypes so non-inlined helper call graphs compile cleanly in any order.
            for (ConceptFunction helper : lowered.helperFunctions()) {
                emitDeviceFunctionPrototype(sb, helper, options, q32Plan);
            }
            if (effMemo.isActive()) {
                for (ConceptFunction mh : effMemo.memoHelpers()) {
                    Integer slot = effMemo.fnNameToSlot().get(mh.name());
                    if (slot != null) {
                        emitMemoWrapperHelper(sb, mh, slot, effPrefix, passGlobalSpillParam, effAllocator.globalSpillBufferName, effMemo.elemType());
                    }
                }
            }
            if (!lowered.helperFunctions().isEmpty()) {
                sb.append('\n');
            }

            for (ConceptFunction helper : lowered.helperFunctions()) {
                emitDeviceFunction(sb, helper, options, q32Plan, fnByKey, fnByName);
                sb.append('\n');
            }

            if (lowered.workgroupPipeline() == null
                    || !lowered.workgroupPipeline().stageFunctions().contains(lowered.entryFunction())) {
                emitDeviceFunction(sb, lowered.entryFunction(), options, q32Plan, fnByKey, fnByName);
            }

            String kernelName = null;
            if (emitKernels
                    && options.emit3DKernel()
                    && (lowered.kernel3D() != null || options.batchLayout() != BatchLayout.SINGLE_POINT)) {
                ConceptBatchSpec batchSpec = ConceptOptimizerPass.buildBatchSpec(lowered, options.batchLayout());
                sb.append('\n');
                emitBatchKernel(sb, lowered, batchSpec, options, q32Plan);
                kernelName = batchSpec.kernelName();
            }
            if (emitKernels && lowered.workgroupPipeline() != null) {
                sb.append('\n');
                emitWorkgroupPipeline(sb, lowered.workgroupPipeline(), options, q32Plan, fnByKey, fnByName);
                kernelName = lowered.workgroupPipeline().kernelName();
            }

            return new TranspiledArtifact(
                    BACKEND_ID,
                    fileExtension(),
                    lowered.moduleName(),
                    effPrefix + lowered.entryFunction().name(),
                    kernelName,
                    arithmeticMode.toFloatingPointPrecision(),
                    sb.toString(),
                    lowered);
        } finally {
            if (prevScope == null) {
                ACTIVE_EMIT_SCOPE.remove();
            } else {
                ACTIVE_EMIT_SCOPE.set(prevScope);
            }
        }
    }

    private static void registerFunctionLookup(
            ConceptModule module,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName) {
        for (ConceptFunction helper : module.helperFunctions()) {
            fnByKey.put(helper.key(), helper);
            fnByName.putIfAbsent(helper.name(), helper);
        }
        ConceptFunction entry = module.entryFunction();
        fnByKey.put(entry.key(), entry);
        fnByName.putIfAbsent(entry.name(), entry);
        if (module.workgroupPipeline() != null) {
            for (ConceptFunction st : module.workgroupPipeline().stageFunctions()) {
                fnByKey.put(st.key(), st);
                fnByName.putIfAbsent(st.name(), st);
            }
        }
    }

    private static void emitQ32Helpers(StringBuilder sb) {
        sb.append("#ifndef LEAFGEN_Q32_HELPERS_DEFINED\n")
                .append("#define LEAFGEN_Q32_HELPERS_DEFINED 1\n")
                .append("inline long q32_from_i32(int v) {\n")
                .append("    return (long)((ulong)(long)v << 32);\n")
                .append("}\n")
                .append("inline long q32_from_i64(long v) {\n")
                .append("    return (long)((ulong)v << 32);\n")
                .append("}\n")
                .append("inline long q32_from_f32(float v) {\n")
                .append("    float fl = floor(v);\n")
                .append("    float fr = v - fl;\n")
                .append("    long iv = (long)fl;\n")
                .append("    if (fr >= 1.0f) {\n")
                .append("        iv += 1L;\n")
                .append("        fr = 0.0f;\n")
                .append("    } else if (fr < 0.0f) {\n")
                .append("        iv -= 1L;\n")
                .append("        fr = 0.0f;\n")
                .append("    }\n")
                .append("    ulong frac = (ulong)(uint)(fr * 4294967296.0f);\n")
                .append("    return (long)(((ulong)iv << 32) | frac);\n")
                .append("}\n")
                .append("inline float q32_to_f32(long q) {\n")
                .append("    long hi = q >> 32;\n")
                .append("    uint lo = (uint)((ulong)q & 0xFFFFFFFFUL);\n")
                .append("    return (float)hi + (float)lo * 0x1.0p-32f;\n")
                .append("}\n")
                .append("inline long q32_add(long a, long b) {\n")
                .append("    return (long)((ulong)a + (ulong)b);\n")
                .append("}\n")
                .append("inline long q32_sub(long a, long b) {\n")
                .append("    return (long)((ulong)a - (ulong)b);\n")
                .append("}\n")
                .append("inline long q32_neg(long a) {\n")
                .append("    return (long)(-(ulong)a);\n")
                .append("}\n")
                .append("inline long q32_abs(long a) {\n")
                .append("    return (a < 0L) ? (long)(-(ulong)a) : a;\n")
                .append("}\n")
                .append("inline long q32_mul(long a, long b) {\n")
                .append("    long hi = mul_hi(a, b);\n")
                .append("    ulong lo = (ulong)a * (ulong)b;\n")
                .append("    ulong rlo = lo + 0x80000000UL;\n")
                .append("    long rhi = hi + ((rlo < lo) ? 1L : 0L);\n")
                .append("    return (long)(((ulong)rhi << 32) | (rlo >> 32));\n")
                .append("}\n")
                .append("inline long q32_div(long a, long b) {\n")
                .append("    if (b == 0L) {\n")
                .append("        return 0L;\n")
                .append("    }\n")
                .append("    return q32_from_f32(q32_to_f32(a) / q32_to_f32(b));\n")
                .append("}\n")
                .append("inline long q32_floor(long q) {\n")
                .append("    return (long)((ulong)q & 0xFFFFFFFF00000000UL);\n")
                .append("}\n")
                .append("inline long q32_ceil(long q) {\n")
                .append("    return (long)(((ulong)q + 0xFFFFFFFFUL) & 0xFFFFFFFF00000000UL);\n")
                .append("}\n")
                .append("inline long q32_floor_i64(long q) {\n")
                .append("    return q >> 32;\n")
                .append("}\n")
                .append("inline int q32_floor_i32(long q) {\n")
                .append("    return (int)(q >> 32);\n")
                .append("}\n")
                .append("inline long q32_trunc_i64(long q) {\n")
                .append("    return (q >= 0L) ? (q >> 32) : -(long)((-(ulong)q) >> 32);\n")
                .append("}\n")
                .append("inline int q32_trunc_i32(long q) {\n")
                .append("    return (int)q32_trunc_i64(q);\n")
                .append("}\n")
                .append("#endif\n");
    }

    private static Map<String, Integer> countFunctionCallSites(
            ConceptModule module,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ConceptFunction h : module.helperFunctions()) {
            tallyCallsInStmts(h.body(), fnByKey, fnByName, counts);
        }
        if (module.entryFunction() != null) {
            tallyCallsInStmts(module.entryFunction().body(), fnByKey, fnByName, counts);
        }
        return counts;
    }

    private static void tallyCallsInStmts(
            List<ConceptStmt> stmts,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName,
            Map<String, Integer> counts) {
        if (stmts == null) return;
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.VarDecl vd && vd.initializer() != null) {
                tallyCallsInExpr(vd.initializer(), fnByKey, fnByName, counts);
            } else if (s instanceof ConceptStmt.Assign a) {
                tallyCallsInExpr(a.value(), fnByKey, fnByName, counts);
            } else if (s instanceof ConceptStmt.If iff) {
                tallyCallsInExpr(iff.condition(), fnByKey, fnByName, counts);
                tallyCallsInStmts(iff.thenBody(), fnByKey, fnByName, counts);
                tallyCallsInStmts(iff.elseBody(), fnByKey, fnByName, counts);
            } else if (s instanceof ConceptStmt.While wh) {
                tallyCallsInExpr(wh.condition(), fnByKey, fnByName, counts);
                tallyCallsInStmts(wh.body(), fnByKey, fnByName, counts);
            } else if (s instanceof ConceptStmt.Return r && r.value() != null) {
                tallyCallsInExpr(r.value(), fnByKey, fnByName, counts);
            }
        }
    }

    private static void tallyCallsInExpr(
            ConceptExpr e,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName,
            Map<String, Integer> counts) {
        if (e == null) return;
        if (e instanceof ConceptExpr.Call c) {
            ConceptFunction target = resolveFunction(c, fnByKey, fnByName);
            if (target != null) {
                counts.merge(target.name(), 1, Integer::sum);
            }
            for (ConceptExpr a : c.arguments()) tallyCallsInExpr(a, fnByKey, fnByName, counts);
        } else if (e instanceof ConceptExpr.UnaryOp u) {
            tallyCallsInExpr(u.operand(), fnByKey, fnByName, counts);
        } else if (e instanceof ConceptExpr.Cast c) {
            tallyCallsInExpr(c.operand(), fnByKey, fnByName, counts);
        } else if (e instanceof ConceptExpr.BinaryOp b) {
            tallyCallsInExpr(b.left(), fnByKey, fnByName, counts);
            tallyCallsInExpr(b.right(), fnByKey, fnByName, counts);
        } else if (e instanceof ConceptExpr.Ternary t) {
            tallyCallsInExpr(t.condition(), fnByKey, fnByName, counts);
            tallyCallsInExpr(t.thenExpr(), fnByKey, fnByName, counts);
            tallyCallsInExpr(t.elseExpr(), fnByKey, fnByName, counts);
        } else if (e instanceof ConceptExpr.LutLoad l) {
            tallyCallsInExpr(l.index(), fnByKey, fnByName, counts);
        } else if (e instanceof ConceptExpr.MathIntrinsic m) {
            for (ConceptExpr a : m.arguments()) tallyCallsInExpr(a, fnByKey, fnByName, counts);
        }
    }

    private static int countStmtNodes(List<ConceptStmt> stmts) {
        if (stmts == null) return 0;
        int n = 0;
        for (ConceptStmt s : stmts) {
            n++;
            if (s instanceof ConceptStmt.If iff) {
                n += countStmtNodes(iff.thenBody()) + countStmtNodes(iff.elseBody());
            } else if (s instanceof ConceptStmt.While wh) {
                n += countStmtNodes(wh.body());
            }
        }
        return n;
    }

    private static boolean shouldMarkNoInline(ConceptFunction fn, EmitScope scope) {
        if (scope == null || scope.symbolPrefix() == null || scope.symbolPrefix().isEmpty() || fn == null) {
            return false;
        }
        int calls = scope.callCountByFnName() != null
                ? scope.callCountByFnName().getOrDefault(fn.name(), 0)
                : 0;
        if (calls < 2) {
            return false;
        }
        int stmts = countStmtNodes(fn.body());
        return stmts >= 24 || (calls >= 3 && containsNestedCall(fn.body()));
    }

    private static void emitMemoWrapperHelper(
            StringBuilder sb,
            ConceptFunction mh,
            int slot,
            String prefix,
            boolean passGlobalSpill,
            String globalSpillName,
            String elemType) {
        sb.append("static inline ")
                .append(elemType)
                .append(" memo_")
                .append(prefix)
                .append(mh.name())
                .append("(int p0, int p1, int p2");
        if (passGlobalSpill) {
            sb.append(", ").append(openClGlobalPtrParam("int", true, globalSpillName));
        }
        sb.append(", uint* memo_mask, ").append(elemType).append("* memo_val) {\n")
                .append("    if ((*memo_mask & (1u << ").append(slot).append(")) != 0u) return memo_val[").append(slot).append("];\n")
                .append("    ").append(elemType).append(" _v = ").append(prefix).append(mh.name())
                .append("(p0, p1, p2");
        if (passGlobalSpill) {
            sb.append(", ").append(globalSpillName);
        }
        sb.append(", memo_mask, memo_val);\n")
                .append("    memo_val[").append(slot).append("] = _v;\n")
                .append("    *memo_mask |= (1u << ").append(slot).append(");\n")
                .append("    return _v;\n")
                .append("}\n");
    }

    private static boolean containsNestedCall(List<ConceptStmt> stmts) {
        if (stmts == null) {
            return false;
        }
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.VarDecl vd && vd.initializer() != null && exprHasCall(vd.initializer())) {
                return true;
            } else if (s instanceof ConceptStmt.Assign a && exprHasCall(a.value())) {
                return true;
            } else if (s instanceof ConceptStmt.Return r && r.value() != null && exprHasCall(r.value())) {
                return true;
            } else if (s instanceof ConceptStmt.If iff) {
                if (exprHasCall(iff.condition())
                        || containsNestedCall(iff.thenBody())
                        || containsNestedCall(iff.elseBody())) {
                    return true;
                }
            } else if (s instanceof ConceptStmt.While wh) {
                if (exprHasCall(wh.condition()) || containsNestedCall(wh.body())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean exprHasCall(ConceptExpr e) {
        if (e == null) {
            return false;
        }
        if (e instanceof ConceptExpr.Call) {
            return true;
        }
        if (e instanceof ConceptExpr.UnaryOp u) {
            return exprHasCall(u.operand());
        }
        if (e instanceof ConceptExpr.Cast c) {
            return exprHasCall(c.operand());
        }
        if (e instanceof ConceptExpr.BinaryOp b) {
            return exprHasCall(b.left()) || exprHasCall(b.right());
        }
        if (e instanceof ConceptExpr.Ternary t) {
            return exprHasCall(t.condition()) || exprHasCall(t.thenExpr()) || exprHasCall(t.elseExpr());
        }
        if (e instanceof ConceptExpr.LutLoad l) {
            return exprHasCall(l.index());
        }
        if (e instanceof ConceptExpr.MathIntrinsic m) {
            for (ConceptExpr a : m.arguments()) {
                if (exprHasCall(a)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void emitDeviceFunctionPrototype(
            StringBuilder sb,
            ConceptFunction fn,
            EmitOptions options,
            Q32LoweringPlan q32Plan) {
        EmitScope scope = ACTIVE_EMIT_SCOPE.get();
        String prefix = scope != null ? scope.symbolPrefix() : "";
        boolean passGlobalSpill = scope != null && scope.passGlobalSpillParam();
        String linkage = prefix.isEmpty()
                ? "inline "
                : (shouldMarkNoInline(fn, scope) ? "static __attribute__((noinline)) " : "static inline ");
        sb.append(linkage)
                .append(mapReturnType(fn, options, q32Plan))
                .append(' ')
                .append(prefix)
                .append(fn.name())
                .append('(');
        List<ConceptParam> params = fn.parameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            ConceptParam p = params.get(i);
            sb.append(mapParamType(fn, p, i, options, q32Plan)).append(' ').append(p.name());
        }
        if (passGlobalSpill) {
            if (!params.isEmpty()) {
                sb.append(", ");
            }
            sb.append(openClGlobalPtrParam("int", true, scope.lutAllocator().globalSpillBufferName));
        }
        if (scope != null && scope.memoElemType() != null) {
            if (!params.isEmpty() || passGlobalSpill) {
                sb.append(", ");
            }
            sb.append("uint* memo_mask, ").append(scope.memoElemType()).append("* memo_val");
        }
        sb.append(");\n");
    }

    private static void emitDeviceFunction(
            StringBuilder sb,
            ConceptFunction fn,
            EmitOptions options,
            Q32LoweringPlan q32Plan,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName) {
        EmitScope scope = ACTIVE_EMIT_SCOPE.get();
        String prefix = scope != null ? scope.symbolPrefix() : "";
        boolean passGlobalSpill = scope != null && scope.passGlobalSpillParam();
        String linkage = prefix.isEmpty()
                ? "inline "
                : (shouldMarkNoInline(fn, scope) ? "static __attribute__((noinline)) " : "static inline ");
        sb.append(linkage)
                .append(mapReturnType(fn, options, q32Plan))
                .append(' ')
                .append(prefix)
                .append(fn.name())
                .append('(');
        List<ConceptParam> params = fn.parameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            ConceptParam p = params.get(i);
            sb.append(mapParamType(fn, p, i, options, q32Plan)).append(' ').append(p.name());
        }
        if (passGlobalSpill) {
            if (!params.isEmpty()) {
                sb.append(", ");
            }
            sb.append(openClGlobalPtrParam("int", true, scope.lutAllocator().globalSpillBufferName));
        }
        if (scope != null && scope.memoElemType() != null) {
            if (!params.isEmpty() || passGlobalSpill) {
                sb.append(", ");
            }
            sb.append("uint* memo_mask, ").append(scope.memoElemType()).append("* memo_val");
        }
        sb.append(") {\n");
        if (passGlobalSpill) {
            sb.append("    (void)").append(scope.lutAllocator().globalSpillBufferName).append(";\n");
        }
        if (scope != null && scope.memoElemType() != null) {
            sb.append("    (void)memo_mask; (void)memo_val;\n");
        }
        emitStatements(sb, fn, fn.body(), options, q32Plan, fnByKey, fnByName, 1);
        sb.append("}\n");
    }

    private static void emitBatchKernel(
            StringBuilder sb,
            ConceptModule module,
            ConceptBatchSpec spec,
            EmitOptions options,
            Q32LoweringPlan q32Plan) {
        switch (spec.layout()) {
            case SINGLE_POINT -> {
                ConceptKernel3D kernel = module.kernel3D() != null
                        ? module.kernel3D()
                        : new ConceptKernel3D(
                                spec.kernelName(),
                                spec.targetFunction(),
                                "out_buffer",
                                "origin_x",
                                "origin_y",
                                "origin_z",
                                "step_scale",
                                spec.extraUniformParams());
                emitKernel3D(sb, kernel, options, q32Plan);
            }
            case CORNER_GRID_4X8X4 -> emitCornerGrid4x8x4Kernel(sb, spec, options, q32Plan);
            case SECTION_4096_VOXEL -> emitSection4096VoxelKernel(sb, spec, options, q32Plan);
            case SECTION_4096_LCA_NIBBLE_PACKED -> emitLcaPackedBatchKernel3D(sb, spec, options, q32Plan);
            case CORNER_SLICE_5X49_PACKED -> emitCornerSlice5x49PackedKernel(sb, spec, options, q32Plan);
        }
    }

    private static void emitKernel3D(
            StringBuilder sb,
            ConceptKernel3D kernel,
            EmitOptions options,
            Q32LoweringPlan q32Plan) {
        ConceptFunction target = kernel.targetFunction();
        boolean q32GridCoords = options.gpuExactArithmeticMode().usesInt64Q32()
                && hasAnyQ32CoordParam(target, q32Plan);
        String coordScalarType = q32GridCoords ? "long" : mapType(ConceptType.F64, options);
        String outElemType = mapReturnType(target, options, q32Plan);

        sb.append("__kernel void ")
                .append(kernel.kernelName())
                .append("(\n")
                .append("    __global ")
                .append(outElemType)
                .append("* ")
                .append(kernel.outputBufferParam())
                .append(",\n")
                .append("    const int dim_x,\n")
                .append("    const int dim_y,\n")
                .append("    const int dim_z,\n")
                .append("    const ")
                .append(coordScalarType)
                .append(' ')
                .append(kernel.originXParam())
                .append(",\n")
                .append("    const ")
                .append(coordScalarType)
                .append(' ')
                .append(kernel.originYParam())
                .append(",\n")
                .append("    const ")
                .append(coordScalarType)
                .append(' ')
                .append(kernel.originZParam())
                .append(",\n")
                .append("    const ")
                .append(coordScalarType)
                .append(' ')
                .append(kernel.scaleParam());

        for (ConceptParam extra : kernel.extraUniformParams()) {
            sb.append(",\n    const ")
                    .append(mapExtraUniformType(target, extra, options, q32Plan))
                    .append(' ')
                    .append(extra.name());
        }
        sb.append(")\n{\n");
        sb.append("    const int gx = (int)get_global_id(0);\n");
        sb.append("    const int gy = (int)get_global_id(1);\n");
        sb.append("    const int gz = (int)get_global_id(2);\n");
        sb.append("    if (gx >= dim_x || gy >= dim_y || gz >= dim_z) {\n");
        sb.append("        return;\n");
        sb.append("    }\n");
        sb.append("    const int linear_idx = (gy * dim_z + gz) * dim_x + gx;\n");

        List<String> callArgs = new ArrayList<>();
        List<ConceptParam> targetParams = target.parameters();
        String[] coordExprs = q32GridCoords
                ? new String[] {
                    "q32_add(" + kernel.originXParam() + ", q32_mul(q32_from_i32(gx), " + kernel.scaleParam() + "))",
                    "q32_add(" + kernel.originYParam() + ", q32_mul(q32_from_i32(gy), " + kernel.scaleParam() + "))",
                    "q32_add(" + kernel.originZParam() + ", q32_mul(q32_from_i32(gz), " + kernel.scaleParam() + "))"
                }
                : new String[] {
                    "(" + kernel.originXParam() + " + ((" + coordScalarType + ")gx) * " + kernel.scaleParam() + ")",
                    "(" + kernel.originYParam() + " + ((" + coordScalarType + ")gy) * " + kernel.scaleParam() + ")",
                    "(" + kernel.originZParam() + " + ((" + coordScalarType + ")gz) * " + kernel.scaleParam() + ")"
                };

        if (targetParams.size() == 2 && targetParams.get(1).type() == ConceptType.I64) {
            ConceptParam p0 = targetParams.get(0);
            String p0Arg;
            if (q32GridCoords) {
                if (p0.type() == ConceptType.I64) {
                    p0Arg = "q32_trunc_i64(" + coordExprs[0] + ")";
                } else if (p0.type().isInteger()) {
                    p0Arg = "q32_trunc_i32(" + coordExprs[0] + ")";
                } else if (p0.type() == ConceptType.F64 && q32Plan.isQ32Param(target, 0)) {
                    p0Arg = coordExprs[0];
                } else {
                    p0Arg = "q32_to_f32(" + coordExprs[0] + ")";
                }
            } else {
                p0Arg = p0.type().isInteger()
                        ? "((long)" + coordExprs[0] + ")"
                        : coordExprs[0];
            }
            callArgs.add(p0Arg);
            callArgs.add(targetParams.get(1).name());
        } else {
            for (int i = 0; i < targetParams.size(); i++) {
                ConceptParam tp = targetParams.get(i);
                if (i < 3) {
                    String gridVar = i == 0 ? "gx" : i == 1 ? "gy" : "gz";
                    String originVar = i == 0 ? kernel.originXParam()
                            : i == 1 ? kernel.originYParam() : kernel.originZParam();
                    if (q32GridCoords) {
                        if (tp.type() == ConceptType.I64) {
                            callArgs.add("((long)" + gridVar + " + q32_trunc_i64(" + originVar + "))");
                        } else if (tp.type() == ConceptType.I32) {
                            callArgs.add("(" + gridVar + " + q32_trunc_i32(" + originVar + "))");
                        } else if (tp.type() == ConceptType.F64 && q32Plan.isQ32Param(target, i)) {
                            callArgs.add(coordExprs[i]);
                        } else {
                            callArgs.add("q32_to_f32(" + coordExprs[i] + ")");
                        }
                    } else {
                        if (tp.type() == ConceptType.I64) {
                            callArgs.add("((long)(" + gridVar + " + (int)" + originVar + "))");
                        } else if (tp.type() == ConceptType.I32) {
                            callArgs.add("(" + gridVar + " + (int)" + originVar + ")");
                        } else if (tp.type() == ConceptType.F32 && options.gpuExactArithmeticMode().usesNativeFp64()) {
                            callArgs.add("((float)" + coordExprs[i] + ")");
                        } else {
                            callArgs.add(coordExprs[i]);
                        }
                    }
                } else {
                    callArgs.add(tp.name());
                }
            }
        }

        sb.append("    ")
                .append(kernel.outputBufferParam())
                .append("[linear_idx] = ")
                .append(target.name())
                .append('(')
                .append(String.join(", ", callArgs))
                .append(");\n");
        sb.append("}\n");

        if (target.returnType() == ConceptType.I32
                && targetParams.size() == 4
                && targetParams.get(0).type() == ConceptType.I32
                && targetParams.get(1).type() == ConceptType.I32
                && targetParams.get(2).type() == ConceptType.I32
                && targetParams.get(3).type() == ConceptType.I64) {
            sb.append('\n');
            ConceptBatchSpec lcaSpec = new ConceptBatchSpec(
                    BatchLayout.SECTION_4096_LCA_NIBBLE_PACKED,
                    "evaluate_lca_packed_batch3d",
                    target,
                    1,
                    1,
                    1,
                    4096,
                    kernel.extraUniformParams());
            emitLcaPackedBatchKernel3D(sb, lcaSpec, options, q32Plan);
        }
    }

    private static void emitCornerGrid4x8x4Kernel(
            StringBuilder sb,
            ConceptBatchSpec spec,
            EmitOptions options,
            Q32LoweringPlan q32Plan) {
        ConceptFunction target = spec.targetFunction();
        String outElemType = mapReturnType(target, options, q32Plan);

        sb.append("__kernel void ")
                .append(spec.kernelName())
                .append("(\n")
                .append("    __global ")
                .append(outElemType)
                .append("* restrict out_corners,\n")
                .append("    __global const int4* restrict batch_origins,\n")
                .append("    const int batch_count,\n")
                .append("    const int corners_x,\n")
                .append("    const int corners_y,\n")
                .append("    const int corners_z");
        for (ConceptParam extra : spec.extraUniformParams()) {
            sb.append(",\n    const ")
                    .append(mapExtraUniformType(target, extra, options, q32Plan))
                    .append(' ')
                    .append(extra.name());
        }
        sb.append(")\n{\n")
                .append("    const int corner_idx = (int)get_global_id(0);\n")
                .append("    const int batch_idx = (int)get_global_id(1);\n")
                .append("    const int corners_per_batch = corners_x * corners_y * corners_z;\n")
                .append("    if (batch_idx >= batch_count || corner_idx >= corners_per_batch) {\n")
                .append("        return;\n")
                .append("    }\n")
                .append("    const int cx = corner_idx % corners_x;\n")
                .append("    const int rem = corner_idx / corners_x;\n")
                .append("    const int cz = rem % corners_z;\n")
                .append("    const int cy = rem / corners_z;\n")
                .append("    const int4 orig = batch_origins[batch_idx];\n")
                .append("    const int bx = orig.x + cx * ").append(spec.stepX()).append(";\n")
                .append("    const int by = orig.y + cy * ").append(spec.stepY()).append(";\n")
                .append("    const int bz = orig.z + cz * ").append(spec.stepZ()).append(";\n")
                .append("    out_corners[batch_idx * corners_per_batch + corner_idx] = ")
                .append(buildIntCoordTargetCall(target, "bx", "by", "bz", options, q32Plan))
                .append(";\n")
                .append("}\n");
    }

    private static void emitSection4096VoxelKernel(
            StringBuilder sb,
            ConceptBatchSpec spec,
            EmitOptions options,
            Q32LoweringPlan q32Plan) {
        ConceptFunction target = spec.targetFunction();
        String outElemType = mapReturnType(target, options, q32Plan);

        sb.append("__kernel void ")
                .append(spec.kernelName())
                .append("(\n")
                .append("    __global ")
                .append(outElemType)
                .append("* restrict out_voxels,\n")
                .append("    __global const int4* restrict section_origins,\n")
                .append("    const int section_count");
        for (ConceptParam extra : spec.extraUniformParams()) {
            sb.append(",\n    const ")
                    .append(mapExtraUniformType(target, extra, options, q32Plan))
                    .append(' ')
                    .append(extra.name());
        }
        sb.append(")\n{\n")
                .append("    const int v_idx = (int)get_global_id(0);\n")
                .append("    const int sec_idx = (int)get_global_id(1);\n")
                .append("    if (v_idx >= 4096 || sec_idx >= section_count) {\n")
                .append("        return;\n")
                .append("    }\n")
                .append("    const int4 orig = section_origins[sec_idx];\n")
                .append("    const int lx = v_idx & 15;\n")
                .append("    const int lz = (v_idx >> 4) & 15;\n")
                .append("    const int ly = (v_idx >> 8) & 15;\n")
                .append("    const int bx = orig.x + lx;\n")
                .append("    const int by = orig.y + ly;\n")
                .append("    const int bz = orig.z + lz;\n")
                .append("    out_voxels[sec_idx * 4096 + v_idx] = ")
                .append(buildIntCoordTargetCall(target, "bx", "by", "bz", options, q32Plan))
                .append(";\n")
                .append("}\n");
    }

    private static void emitLcaPackedBatchKernel3D(
            StringBuilder sb,
            ConceptBatchSpec spec,
            EmitOptions options,
            Q32LoweringPlan q32Plan) {
        ConceptFunction target = spec.targetFunction();
        String callExpr = buildIntCoordTargetCall(target, "bx", "by", "bz", options, q32Plan);
        String nibbleValueExpr;
        if (target.returnType().isInteger()) {
            nibbleValueExpr = "(int)(" + callExpr + ")";
        } else if (target.returnType() == ConceptType.F64 && q32Plan.isQ32Return(target)) {
            nibbleValueExpr = "((" + callExpr + ") > 0L) ? 1 : 0";
        } else if (options.gpuExactArithmeticMode().usesNativeFp64() && target.returnType() == ConceptType.F64) {
            nibbleValueExpr = "((" + callExpr + ") > 0.0) ? 1 : 0";
        } else {
            nibbleValueExpr = "((" + callExpr + ") > 0.0f) ? 1 : 0";
        }

        sb.append("__kernel void ")
                .append(spec.kernelName())
                .append("(\n")
                .append("    __global uint* restrict out_lca_frames,\n")
                .append("    __constant int4* restrict section_origins,\n")
                .append("    const int section_count");
        for (ConceptParam extra : spec.extraUniformParams()) {
            sb.append(",\n    const ")
                    .append(mapExtraUniformType(target, extra, options, q32Plan))
                    .append(' ')
                    .append(extra.name());
        }
        sb.append("\n)\n{\n")
                .append("    const int v_idx = (int)get_global_id(0);\n")
                .append("    const int sec_idx = (int)get_global_id(1);\n")
                .append("    if (v_idx >= 4096 || sec_idx >= section_count) {\n")
                .append("        return;\n")
                .append("    }\n")
                .append("    const int lid = (int)get_local_id(0);\n")
                .append("    const int wg = (v_idx >> 8) & 15;\n")
                .append("    __local uint local_words[32];\n")
                .append("    __local uint local_diff_mask;\n")
                .append("    if (lid < 32) {\n")
                .append("        local_words[lid] = 0u;\n")
                .append("    }\n")
                .append("    if (lid == 0) {\n")
                .append("        local_diff_mask = 0u;\n")
                .append("    }\n")
                .append("    barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);\n")
                .append("    const int4 orig = section_origins[sec_idx];\n")
                .append("    const int lx = v_idx & 15;\n")
                .append("    const int lz = (v_idx >> 4) & 15;\n")
                .append("    const int ly = (v_idx >> 8) & 15;\n")
                .append("    const int bx = orig.x + lx;\n")
                .append("    const int by = orig.y + ly;\n")
                .append("    const int bz = orig.z + lz;\n")
                .append("    const int st = ")
                .append(nibbleValueExpr)
                .append(";\n")
                .append("    const uint nib = ((uint)(st & 0xF)) << ((lx & 7) << 2);\n")
                .append("    atomic_or(&local_words[lid >> 3], nib);\n")
                .append("    barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);\n")
                .append("    if (lid < 32) {\n")
                .append("        const uint w = local_words[lid];\n")
                .append("        const int sec_offset = sec_idx * 516;\n")
                .append("        out_lca_frames[sec_offset + 4 + (wg << 5) + lid] = w;\n")
                .append("        const uint first_nib = local_words[0] & 0xFu;\n")
                .append("        const uint uniform_w = first_nib * 0x11111111u;\n")
                .append("        if (w != uniform_w) {\n")
                .append("            atomic_or(&local_diff_mask, 1u << (lid >> 3));\n")
                .append("        }\n")
                .append("    }\n")
                .append("    barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);\n")
                .append("    if (lid == 0) {\n")
                .append("        const int sec_offset = sec_idx * 516;\n")
                .append("        const uint first_nib = local_words[0] & 0xFu;\n")
                .append("        ((__global uchar*)(out_lca_frames + sec_offset))[wg] = ")
                .append("(uchar)(first_nib | ((local_diff_mask & 0xFu) << 4));\n")
                .append("    }\n")
                .append("}\n");
    }

    private static void emitCornerSlice5x49PackedKernel(
            StringBuilder sb,
            ConceptBatchSpec spec,
            EmitOptions options,
            Q32LoweringPlan q32Plan) {
        ConceptFunction target = spec.targetFunction();
        String callExpr = buildIntCoordTargetCall(target, "bx", "by", "bz", options, q32Plan);
        boolean emitFp64Bits = options.gpuExactArithmeticMode().usesNativeFp64()
                && target.returnType() == ConceptType.F64;
        boolean q32Ret = target.returnType() == ConceptType.F64 && q32Plan.isQ32Return(target);

        sb.append("__kernel void ")
                .append(spec.kernelName())
                .append("(\n")
                .append("    __global uint* restrict out_lca_frames,\n")
                .append("    __constant int4* restrict section_origins,\n")
                .append("    const int section_count,\n")
                .append("    const long world_seed");
        for (ConceptParam extra : spec.extraUniformParams()) {
            sb.append(",\n    const ")
                    .append(mapExtraUniformType(target, extra, options, q32Plan))
                    .append(' ')
                    .append(extra.name());
        }
        sb.append("\n)\n{\n")
                .append("    const int tid = (int)get_global_id(0);\n")
                .append("    const int s = (int)get_global_id(1);\n")
                .append("    if (tid >= 245 || s >= section_count) {\n")
                .append("        return;\n")
                .append("    }\n")
                .append("    const int4 orig = section_origins[s];\n")
                .append("    const int cx = s % 5;\n")
                .append("    const int cz = tid / 49;\n")
                .append("    const int cy = tid - cz * 49;\n")
                .append("    const int bx = orig.x + (cx << 2);\n")
                .append("    const int by = orig.y + (cy << 3);\n")
                .append("    const int bz = orig.z + (cz << 2);\n");
        if (emitFp64Bits) {
            sb.append("    const double val = ").append(callExpr).append(";\n")
                    .append("    const ulong bits = as_ulong(val);\n")
                    .append("    out_lca_frames[s * 516 + (tid << 1)] = (uint)(bits & 0xFFFFFFFFUL);\n")
                    .append("    out_lca_frames[s * 516 + (tid << 1) + 1] = (uint)(bits >> 32);\n");
        } else if (q32Ret) {
            sb.append("    const float val = q32_to_f32(").append(callExpr).append(");\n")
                    .append("    out_lca_frames[s * 516 + tid] = as_uint(val);\n");
        } else {
            sb.append("    const float val = (float)(").append(callExpr).append(");\n")
                    .append("    out_lca_frames[s * 516 + tid] = as_uint(val);\n");
        }
        sb.append("}\n");
    }

    private static String buildIntCoordTargetCall(
            ConceptFunction target,
            String xVar,
            String yVar,
            String zVar,
            EmitOptions options,
            Q32LoweringPlan q32Plan) {
        List<ConceptParam> params = target.parameters();
        List<String> args = new ArrayList<>(params.size());
        String[] coords = {xVar, yVar, zVar};
        if (params.size() == 2 && params.get(1).type() == ConceptType.I64) {
            args.add(formatIntCoordArg(coords[0], params.get(0), 0, target, options, q32Plan));
            args.add(params.get(1).name());
        } else {
            for (int i = 0; i < params.size(); i++) {
                ConceptParam p = params.get(i);
                if (i < 3) {
                    args.add(formatIntCoordArg(coords[i], p, i, target, options, q32Plan));
                } else {
                    args.add(p.name());
                }
            }
        }
        return target.name() + "(" + String.join(", ", args) + ")";
    }

    private static String formatIntCoordArg(
            String coordVar,
            ConceptParam param,
            int paramIndex,
            ConceptFunction target,
            EmitOptions options,
            Q32LoweringPlan q32Plan) {
        return switch (param.type()) {
            case I32, BOOL -> coordVar;
            case I64 -> "((long)" + coordVar + ")";
            case F32 -> "((float)" + coordVar + ")";
            case F64 -> {
                if (options.gpuExactArithmeticMode().usesInt64Q32() && q32Plan.isQ32Param(target, paramIndex)) {
                    yield "q32_from_i32(" + coordVar + ")";
                }
                yield options.gpuExactArithmeticMode().usesNativeFp64()
                        ? "((double)" + coordVar + ")"
                        : "((float)" + coordVar + ")";
            }
            default -> coordVar;
        };
    }

    private static boolean hasAnyQ32CoordParam(ConceptFunction target, Q32LoweringPlan q32Plan) {
        int limit = Math.min(3, target.parameters().size());
        for (int i = 0; i < limit; i++) {
            if (q32Plan.isQ32Param(target, i)) {
                return true;
            }
        }
        return false;
    }

    private static void emitWorkgroupPipeline(
            StringBuilder sb,
            ConceptWorkgroupPipeline wg,
            EmitOptions options,
            Q32LoweringPlan q32Plan,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName) {
        for (ConceptFunction st : wg.stageFunctions()) {
            emitDeviceFunction(sb, st, options, q32Plan, fnByKey, fnByName);
            sb.append('\n');
        }
        String cornerFn = wg.cornerEvalFunctionName();
        sb.append("// Bytecode-lifted Stages K1 -> K2 -> K3 Workgroup Pipeline (local SRAM: ")
                .append(wg.totalLocalSramBytes())
                .append(" B < 8192 B)\n");
        sb.append("__kernel void ")
                .append(wg.kernelName())
                .append("(\n")
                .append("        __global ushort* out_section_offsets,\n")
                .append("        __global ulong* out_octree_masks,\n")
                .append("        __global ulong* out_heightmaps_long37,\n")
                .append("        __global uchar* out_biome_rle,\n")
                .append("        __global uchar* out_sky_gray_planes,\n")
                .append("        __global uint* out_slab_crc32c,\n")
                .append("        __global const int2* chunk_coords_xz,\n")
                .append("        const int chunk_count,\n")
                .append("        const long world_seed) {\n");
        for (ConceptLocalBuffer buf : wg.localBuffers()) {
            String elemType = buf.elementByteWidth() == 1
                    ? "uchar"
                    : (buf.elementByteWidth() == 2 ? "short" : mapType(buf.elementType(), options));
            sb.append("    __local ")
                    .append(elemType)
                    .append(' ')
                    .append(buf.name())
                    .append('[')
                    .append(buf.length())
                    .append("];\n");
        }
        sb.append("    int lid = (int)get_local_id(0);\n")
                .append("    int lsize = (int)get_local_size(0);\n")
                .append("    int c = (int)get_group_id(0);\n")
                .append("    if (c >= chunk_count) return;\n")
                .append("    int2 cpos = chunk_coords_xz[c];\n")
                .append("    int base_bx = cpos.x << 4, base_bz = cpos.y << 4;\n")
                .append("    // Stage K1: 5x5x49 Corner Density + 2D prelimSurfaceLevel + 4x4x4 Climate Biome LUT\n")
                .append("    for (int idx = lid; idx < 1225; idx += lsize) {\n")
                .append("        int cx = idx / 245;\n")
                .append("        int rem = idx - cx * 245;\n")
                .append("        int cz = rem / 49;\n")
                .append("        int cy = rem - cz * 49;\n")
                .append("        local_corners[idx] = ")
                .append(cornerFn)
                .append("(base_bx + (cx << 2), -64 + (cy << 3), base_bz + (cz << 2));\n")
                .append("    }\n")
                .append("    barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);\n")
                .append("    if (lid < 16) {\n")
                .append("        int tx = lid & 3, tz = (lid >> 2) & 3;\n")
                .append("        float n = local_corners[(tx * 5 + tz) * 49 + 16];\n")
                .append("        float e = local_corners[(tx * 5 + tz) * 49 + 20];\n")
                .append("        short sy = (short)clamp(64 + (int)rint(n * 28.0f), 48, 110);\n")
                .append("        for (int dz = 0; dz < 4; dz++) {\n")
                .append("            for (int dx = 0; dx < 4; dx++) {\n")
                .append("                local_surf_y[((tz << 2 | dz) << 4) | (tx << 2 | dx)] = sy;\n")
                .append("            }\n")
                .append("        }\n")
                .append("        for (int s = 0; s < 24; s++) {\n")
                .append("            int bId = classifyClimateBiomeQuartet(n, e, n - e, e * 0.5f, (s - 4) << 2);\n")
                .append("            local_biomes[s * 16 + lid] = (uchar)(bId & 7);\n")
                .append("        }\n")
                .append("    }\n")
                .append("    barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);\n")
                .append("    // Stage K2: octreeOccupancyMask64 + Surface Strata + 01:H_3 Ore Seams + 4x long[37] Heightmaps\n")
                .append("    if (lid < 24) {\n")
                .append("        int s = lid;\n")
                .append("        int base_by = (s - 4) << 4;\n")
                .append("        int dom_biome = (int)local_biomes[s * 16];\n")
                .append("        if (base_by >= 112) {\n")
                .append("            out_section_offsets[c * 24 + s] = (ushort)encodeUniformSectionHeader(0, 1, dom_biome & 3);\n")
                .append("            out_octree_masks[c * 24 + s] = 0UL;\n")
                .append("            out_biome_rle[c * 24 + s] = (uchar)(0x80 | (dom_biome & 7));\n")
                .append("            out_sky_gray_planes[c * 24 + s] = (uchar)toGray4(15);\n")
                .append("        } else {\n")
                .append("            ulong mask = 0UL;\n")
                .append("            for (int oct = 0; oct < 64; oct++) {\n")
                .append("                int yzx = h3ToYzx(oct << 6);\n")
                .append("                int tx = (yzx & 15) >> 2, tz = ((yzx >> 4) & 15) >> 2, octY = (yzx >> 8) >> 2;\n")
                .append("                int cellY = (s << 1) + (octY >> 1);\n")
                .append("                float c0 = local_corners[(tx * 5 + tz) * 49 + cellY];\n")
                .append("                float c1 = local_corners[((tx + 1) * 5 + (tz + 1)) * 49 + cellY + 1];\n")
                .append("                if ((c0 > 0.0f) != (c1 > 0.0f)) mask |= (1UL << oct);\n")
                .append("            }\n")
                .append("            int ore_h3 = computeVanillaAnchoredOreStartH3(cpos.x, cpos.y, s, 101, world_seed);\n")
                .append("            int ore_oct = (ore_h3 >> 6) & 63;\n")
                .append("            int keep_ore = evaluateOctantDiscardExposure((long)mask, ore_oct, 10, ore_h3);\n")
                .append("            out_octree_masks[c * 24 + s] = mask;\n")
                .append("            int surf_blk = selectSurfaceStrataBlock(dom_biome, base_by, (int)local_surf_y[0], 63);\n")
                .append("            int dom = (base_by < 0) ? 2 : (keep_ore != 0 ? 1 : surf_blk);\n")
                .append("            int sky2 = (base_by > (int)local_surf_y[0]) ? 1 : 0;\n")
                .append("            out_section_offsets[c * 24 + s] = (mask == 0UL && s >= 7)\n")
                .append("                    ? (ushort)encodeUniformSectionHeader(dom, sky2, dom_biome & 3)\n")
                .append("                    : (ushort)((s + 1) << 6);\n")
                .append("            out_biome_rle[c * 24 + s] = (uchar)((dom_biome & 7) << 5 | 31);\n")
                .append("            out_sky_gray_planes[c * 24 + s] = (uchar)toGray4(sky2 != 0 ? 15 : 0);\n")
                .append("        }\n")
                .append("    }\n")
                .append("    if (lid < 37) {\n")
                .append("        int w = lid, base_col = w * 7;\n")
                .append("        ulong packed_ws = 0UL, packed_of = 0UL, packed_mb = 0UL, packed_mbnl = 0UL;\n")
                .append("        for (int k = 0; k < 7; k++) {\n")
                .append("            int col = base_col + k;\n")
                .append("            if (col < 256) {\n")
                .append("                int sy = (int)local_surf_y[col];\n")
                .append("                ulong enc_ws = (ulong)clamp(max(sy, 63) - (-64) + 1, 0, 511);\n")
                .append("                ulong enc_of = (ulong)clamp(sy - (-64) + 1, 0, 511);\n")
                .append("                packed_ws |= (enc_ws << (k * 9));\n")
                .append("                packed_of |= (enc_of << (k * 9));\n")
                .append("                packed_mb |= (enc_ws << (k * 9));\n")
                .append("                packed_mbnl |= (enc_ws << (k * 9));\n")
                .append("            }\n")
                .append("        }\n")
                .append("        int hm_base = c * 148;\n")
                .append("        out_heightmaps_long37[hm_base + w] = packed_ws;\n")
                .append("        out_heightmaps_long37[hm_base + 37 + w] = packed_of;\n")
                .append("        out_heightmaps_long37[hm_base + 74 + w] = packed_mb;\n")
                .append("        out_heightmaps_long37[hm_base + 111 + w] = packed_mbnl;\n")
                .append("    }\n")
                .append("    barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);\n")
                .append("    // Stage K3: 256B DMA Slab CRC32C Summary\n")
                .append("    if (lid == 0) {\n")
                .append("        uint crc = 0x4C434153u ^ (uint)cpos.x ^ ((uint)cpos.y << 16);\n")
                .append("        for (int s = 0; s < 24; s++) {\n")
                .append("            crc = (crc * 31u) ^ (uint)out_section_offsets[c * 24 + s] ^ (uint)out_octree_masks[c * 24 + s];\n")
                .append("        }\n")
                .append("        out_slab_crc32c[c] = crc;\n")
                .append("    }\n")
                .append("}\n\n");

        // Emit Stage K0 (RandomSpread structure-grid filter) and Stage K2 Beardifier helpers (leafgen-ADR-003)
        sb.append("inline int floor_div_i32(int a, int b) {\n")
                .append("    int q = a / b;\n")
                .append("    int r = a % b;\n")
                .append("    if ((r != 0) && ((r < 0) != (b < 0))) q--;\n")
                .append("    return q;\n")
                .append("}\n\n")
                .append("inline int lcg_next_int_48(ulong* st, int bound) {\n")
                .append("    if (bound <= 1) {\n")
                .append("        *st = ((*st) * 0x5DEECE66DUL + 0xBUL) & 0xFFFFFFFFFFFFUL;\n")
                .append("        return 0;\n")
                .append("    }\n")
                .append("    if ((bound & -bound) == bound) {\n")
                .append("        *st = ((*st) * 0x5DEECE66DUL + 0xBUL) & 0xFFFFFFFFFFFFUL;\n")
                .append("        uint bits = (uint)((*st) >> 17);\n")
                .append("        return (int)(((ulong)(uint)bound * (ulong)bits) >> 31);\n")
                .append("    }\n")
                .append("    int bits, val;\n")
                .append("    do {\n")
                .append("        *st = ((*st) * 0x5DEECE66DUL + 0xBUL) & 0xFFFFFFFFFFFFUL;\n")
                .append("        bits = (int)((uint)((*st) >> 17));\n")
                .append("        val = bits % bound;\n")
                .append("    } while (bits - val + (bound - 1) < 0);\n")
                .append("    return val;\n")
                .append("}\n\n")
                .append("__kernel void evaluate_structure_grid_starts(\n")
                .append("        __global const int* queries_i32,\n")
                .append("        const int query_count,\n")
                .append("        const int min_chunk_x,\n")
                .append("        const int min_chunk_z,\n")
                .append("        const int max_chunk_x,\n")
                .append("        const int max_chunk_z,\n")
                .append("        const long world_seed,\n")
                .append("        __global int* out_candidates_i32,\n")
                .append("        __global volatile int* out_candidate_count,\n")
                .append("        const int max_candidates) {\n")
                .append("    int cell_id = (int)get_global_id(0);\n")
                .append("    int q = (int)get_global_id(1);\n")
                .append("    if (q < 0 || q >= query_count) return;\n")
                .append("    int qbase = q * 8;\n")
                .append("    int set_idx = queries_i32[qbase];\n")
                .append("    int spacing = queries_i32[qbase + 1];\n")
                .append("    int separation = queries_i32[qbase + 2];\n")
                .append("    int salt = queries_i32[qbase + 3];\n")
                .append("    int spread_type = queries_i32[qbase + 4];\n")
                .append("    int biome_mask = queries_i32[qbase + 5];\n")
                .append("    int min_sy = queries_i32[qbase + 6];\n")
                .append("    int max_sy = queries_i32[qbase + 7];\n")
                .append("    if (spacing <= 0) return;\n")
                .append("    int min_gx = floor_div_i32(min_chunk_x, spacing);\n")
                .append("    int max_gx = floor_div_i32(max_chunk_x, spacing);\n")
                .append("    int min_gz = floor_div_i32(min_chunk_z, spacing);\n")
                .append("    int max_gz = floor_div_i32(max_chunk_z, spacing);\n")
                .append("    int span_x = max_gx - min_gx + 1;\n")
                .append("    int span_z = max_gz - min_gz + 1;\n")
                .append("    if (span_x <= 0 || span_z <= 0 || cell_id >= span_x * span_z) return;\n")
                .append("    int gx = min_gx + (cell_id % span_x);\n")
                .append("    int gz = min_gz + (cell_id / span_x);\n")
                .append("    long rseed = (long)gx * 341873128712L + (long)gz * 132897987541L + world_seed + (long)salt;\n")
                .append("    ulong lcg = ((ulong)rseed ^ 0x5DEECE66DUL) & 0xFFFFFFFFFFFFUL;\n")
                .append("    int bound = max(1, spacing - separation);\n")
                .append("    int ox, oz;\n")
                .append("    if (spread_type == 1) {\n")
                .append("        int rx0 = lcg_next_int_48(&lcg, bound);\n")
                .append("        int rx1 = lcg_next_int_48(&lcg, bound);\n")
                .append("        int rz0 = lcg_next_int_48(&lcg, bound);\n")
                .append("        int rz1 = lcg_next_int_48(&lcg, bound);\n")
                .append("        ox = (rx0 + rx1) / 2;\n")
                .append("        oz = (rz0 + rz1) / 2;\n")
                .append("    } else {\n")
                .append("        ox = lcg_next_int_48(&lcg, bound);\n")
                .append("        oz = lcg_next_int_48(&lcg, bound);\n")
                .append("    }\n")
                .append("    int cand_cx = gx * spacing + ox;\n")
                .append("    int cand_cz = gz * spacing + oz;\n")
                .append("    if (cand_cx < min_chunk_x || cand_cx > max_chunk_x || cand_cz < min_chunk_z || cand_cz > max_chunk_z) return;\n")
                .append("    int bx = (cand_cx << 4) + 8;\n")
                .append("    int bz = (cand_cz << 4) + 8;\n")
                .append("    float n = ").append(cornerFn).append("(bx, 64, bz);\n")
                .append("    float e = ").append(cornerFn).append("(bx, 96, bz);\n")
                .append("    int sy = clamp(64 + (int)rint(n * 28.0f), 48, 110);\n")
                .append("    int biome_id = classifyClimateBiomeQuartet(n, e, n - e, e * 0.5f, sy) & 7;\n")
                .append("    if (sy < min_sy || sy > max_sy || ((biome_mask & (1 << biome_id)) == 0)) return;\n")
                .append("    int out_idx = atomic_inc(out_candidate_count);\n")
                .append("    if (out_idx >= 0 && out_idx < max_candidates) {\n")
                .append("        int ob = out_idx * 8;\n")
                .append("        out_candidates_i32[ob] = q;\n")
                .append("        out_candidates_i32[ob + 1] = set_idx;\n")
                .append("        out_candidates_i32[ob + 2] = cand_cx;\n")
                .append("        out_candidates_i32[ob + 3] = cand_cz;\n")
                .append("        out_candidates_i32[ob + 4] = sy;\n")
                .append("        out_candidates_i32[ob + 5] = biome_id;\n")
                .append("        out_candidates_i32[ob + 6] = gx;\n")
                .append("        out_candidates_i32[ob + 7] = gz;\n")
                .append("    }\n")
                .append("}\n\n")
                .append("__kernel void evaluate_tree_anchors_batch(\n")
                .append("        __global const int2* chunk_coords_xz,\n")
                .append("        const int chunk_count,\n")
                .append("        const long world_seed,\n")
                .append("        __global int* out_anchors_i32) {\n")
                .append("    int gid = (int)get_global_id(0);\n")
                .append("    if (gid < 0 || gid >= chunk_count) return;\n")
                .append("    int2 cpos = chunk_coords_xz[gid];\n")
                .append("    int probe = computeChunkTreeAnchorPacked(cpos.x, cpos.y, 1, 70, 62, world_seed);\n")
                .append("    if ((probe & 0x40000000) == 0) {\n")
                .append("        out_anchors_i32[gid] = 0;\n")
                .append("        return;\n")
                .append("    }\n")
                .append("    int base_bx = cpos.x << 4, base_bz = cpos.y << 4;\n")
                .append("    int ax = probe & 15, az = (probe >> 4) & 15;\n")
                .append("    int tx = ax >> 2, tz = az >> 2;\n")
                .append("    float fx = (float)(ax & 3) * 0.25f, fz = (float)(az & 3) * 0.25f;\n")
                .append("    float c00 = ").append(cornerFn).append("(base_bx + (tx << 2), 64, base_bz + (tz << 2));\n")
                .append("    float c10 = ").append(cornerFn).append("(base_bx + ((tx + 1) << 2), 64, base_bz + (tz << 2));\n")
                .append("    float c01 = ").append(cornerFn).append("(base_bx + (tx << 2), 64, base_bz + ((tz + 1) << 2));\n")
                .append("    float c11 = ").append(cornerFn).append("(base_bx + ((tx + 1) << 2), 64, base_bz + ((tz + 1) << 2));\n")
                .append("    float e00 = ").append(cornerFn).append("(base_bx + (tx << 2), 96, base_bz + (tz << 2));\n")
                .append("    float ncol = (c00 * (1.0f - fx) + c10 * fx) * (1.0f - fz) + (c01 * (1.0f - fx) + c11 * fx) * fz;\n")
                .append("    int ay = clamp(64 + (int)rint(ncol * 28.0f), 48, 110);\n")
                .append("    int tile_sy = clamp(64 + (int)rint(c00 * 28.0f), 48, 110);\n")
                .append("    int surf_sec = clamp((ay + 64) >> 4, 0, 23);\n")
                .append("    float ct = c00 * 4.0f, ch = e00 * 4.0f;\n")
                .append("    float cc = (ay <= 62 || tile_sy <= 62) ? -0.30f : (ct - ch * 0.5f);\n")
                .append("    int dom_biome = classifyClimateBiomeQuartet(ct, ch, cc, ch * 0.5f, (surf_sec - 4) << 2) & 7;\n")
                .append("    out_anchors_i32[gid] = computeChunkTreeAnchorPacked(cpos.x, cpos.y, dom_biome, ay, 62, world_seed);\n")
                .append("}\n\n")
                .append("inline int mix_anchor_hash_i32(int cx, int cz, long seed) {\n")
                .append("    ulong h = (ulong)seed ^ ((ulong)(long)cx * 0x9E3779B97F4A7C15UL) ^ ((ulong)(long)cz * 0xBF58476D1CE4E5B9UL);\n")
                .append("    h = (h ^ (h >> 30)) * 0xBF58476D1CE4E5B9UL;\n")
                .append("    h = (h ^ (h >> 27)) * 0x94D049BB133111EBUL;\n")
                .append("    return (int)(h ^ (h >> 31));\n")
                .append("}\n\n")
                .append("inline int eval_processor_pal_idx(\n")
                .append("        int base_pal_idx,\n")
                .append("        __global const int* rules_i32,\n")
                .append("        int rule_start,\n")
                .append("        int rule_count,\n")
                .append("        int wx, int wy, int wz,\n")
                .append("        long world_seed) {\n")
                .append("    for (int r = 0; r < rule_count; r++) {\n")
                .append("        int rb = (rule_start + r) * 6;\n")
                .append("        if (rules_i32[rb] != base_pal_idx) continue;\n")
                .append("        float prob = as_float(rules_i32[rb + 2]);\n")
                .append("        if (prob >= 1.0f) return rules_i32[rb + 1];\n")
                .append("        if (prob <= 0.0f) continue;\n")
                .append("        ulong salt = ((ulong)(uint)rules_i32[rb + 4] << 32) | (ulong)(uint)rules_i32[rb + 3];\n")
                .append("        ulong h = (ulong)world_seed ^ salt ^ ((ulong)r * 0x9E3779B97F4A7C15UL);\n")
                .append("        h ^= (ulong)(long)wx * 0xBF58476D1CE4E5B9UL;\n")
                .append("        h ^= (ulong)(long)wy * 0x94D049BB133111EBUL;\n")
                .append("        h ^= (ulong)(long)wz * 0xD6E8FEB86659FD93UL;\n")
                .append("        h = (h ^ (h >> 30)) * 0xBF58476D1CE4E5B9UL;\n")
                .append("        h = (h ^ (h >> 27)) * 0x94D049BB133111EBUL;\n")
                .append("        h = h ^ (h >> 31);\n")
                .append("        float sample = (float)((h >> 40) & 0xFFFFFFUL) * 0x1.0p-24f;\n")
                .append("        if (sample < prob) return rules_i32[rb + 1];\n")
                .append("    }\n")
                .append("    return base_pal_idx;\n")
                .append("}\n\n");
        sb.append("inline float compute_beard_contribution_f32(\n")
                .append("        int bx, int by, int bz,\n")
                .append("        __global const int* beard_i32,\n")
                .append("        int beard_start,\n")
                .append("        int beard_count) {\n")
                .append("    float sum = 0.0f;\n")
                .append("    for (int i = 0; i < beard_count; i++) {\n")
                .append("        int p = (beard_start + i) * 8;\n")
                .append("        int w6 = beard_i32[p + 6];\n")
                .append("        int t_adj = w6 & 255;\n")
                .append("        if (t_adj == 0) continue;\n")
                .append("        int g_delta = (w6 << 16) >> 24;\n")
                .append("        int min_x = beard_i32[p], min_y = beard_i32[p + 1] + g_delta, min_z = beard_i32[p + 2];\n")
                .append("        int max_x = beard_i32[p + 3], max_y = beard_i32[p + 4], max_z = beard_i32[p + 5];\n")
                .append("        int dx = max(0, max(min_x - bx, bx - max_x));\n")
                .append("        int dz = max(0, max(min_z - bz, bz - max_z));\n")
                .append("        int dy = max(0, max(min_y - by, by - max_y));\n")
                .append("        if (dx > 12 || dz > 12 || dy > 12) continue;\n")
                .append("        int d2 = dx * dx + dy * dy + dz * dz;\n")
                .append("        if (d2 >= 144) continue;\n")
                .append("        float t = 1.0f - (float)d2 * 0x1.c71c72p-8f;\n")
                .append("        float w = (t * t) * t;\n")
                .append("        if (t_adj == 1) {\n")
                .append("            int mid_y = (min_y + max_y) >> 1;\n")
                .append("            sum += ((by <= mid_y) ? 0.85f : -0.45f) * w;\n")
                .append("        } else if (t_adj == 2) {\n")
                .append("            if (by <= min_y) sum += 1.35f * w;\n")
                .append("            else if (dx <= 2 && dz <= 2 && by <= max_y) sum -= 0.90f * w;\n")
                .append("        } else if (t_adj == 3) {\n")
                .append("            if (dx == 0 && dz == 0 && by >= min_y && by <= max_y) sum -= 2.25f;\n")
                .append("            else sum += ((by < min_y) ? 1.10f : -0.65f) * w;\n")
                .append("        } else if (t_adj == 4) {\n")
                .append("            if (dx == 0 && dz == 0 && by >= min_y && by <= max_y) sum += 1.60f;\n")
                .append("            else sum += 0.95f * w;\n")
                .append("        }\n")
                .append("    }\n")
                .append("    return sum;\n")
                .append("}\n\n")
                .append("inline int adjust_surf_y_for_beard(\n")
                .append("        int wx, int wz, int found_y,\n")
                .append("        __global const int* beard_i32,\n")
                .append("        int beard_start,\n")
                .append("        int beard_count) {\n")
                .append("    int sy = found_y;\n")
                .append("    for (int i = 0; i < beard_count; i++) {\n")
                .append("        int p = (beard_start + i) * 8;\n")
                .append("        int w6 = beard_i32[p + 6];\n")
                .append("        int t_adj = w6 & 255;\n")
                .append("        int g_delta = (w6 << 16) >> 24;\n")
                .append("        int min_x = beard_i32[p], min_y = beard_i32[p + 1] + g_delta, min_z = beard_i32[p + 2];\n")
                .append("        int max_x = beard_i32[p + 3], max_y = beard_i32[p + 4], max_z = beard_i32[p + 5];\n")
                .append("        if (t_adj == 2) {\n")
                .append("            int hdx = max(0, max(min_x - wx, wx - max_x));\n")
                .append("            int hdz = max(0, max(min_z - wz, wz - max_z));\n")
                .append("            if (hdx == 0 && hdz == 0) {\n")
                .append("                sy = clamp(min_y, -60, 310);\n")
                .append("            } else if (hdx <= 4 && hdz <= 4) {\n")
                .append("                int d = max(hdx, hdz);\n")
                .append("                sy = clamp(sy + ((min_y - sy) * (5 - d)) / 5, -60, 310);\n")
                .append("            }\n")
                .append("        } else if (t_adj == 4 && wx >= min_x && wx <= max_x && wz >= min_z && wz <= max_z && max_y > sy) {\n")
                .append("            sy = clamp(max_y, -60, 310);\n")
                .append("        }\n")
                .append("    }\n")
                .append("    return sy;\n")
                .append("}\n\n")
                .append("inline int box_intersects_beard(\n")
                .append("        int x0, int y0, int z0, int x1, int y1, int z1,\n")
                .append("        __global const int* beard_i32,\n")
                .append("        int beard_start,\n")
                .append("        int beard_count) {\n")
                .append("    for (int i = 0; i < beard_count; i++) {\n")
                .append("        int p = (beard_start + i) * 8;\n")
                .append("        int w6 = beard_i32[p + 6];\n")
                .append("        int t_adj = w6 & 255;\n")
                .append("        if (t_adj == 0) continue;\n")
                .append("        int pad = (t_adj == 2) ? 12 : 6;\n")
                .append("        int g_delta = (w6 << 16) >> 24;\n")
                .append("        int min_x = beard_i32[p] - pad, min_y = beard_i32[p + 1] + g_delta - pad, min_z = beard_i32[p + 2] - pad;\n")
                .append("        int max_x = beard_i32[p + 3] + pad, max_y = beard_i32[p + 4] + pad, max_z = beard_i32[p + 5] + pad;\n")
                .append("        if (x1 >= min_x && x0 <= max_x && y1 >= min_y && y0 <= max_y && z1 >= min_z && z0 <= max_z) return 1;\n")
                .append("    }\n")
                .append("    return 0;\n")
                .append("}\n\n")
                .append("inline float compute_voxel_beard_delta_f32(\n")
                .append("        int bx, int by, int bz,\n")
                .append("        __global const int* beard_i32,\n")
                .append("        int beard_start,\n")
                .append("        int beard_count) {\n")
                .append("    float delta = 0.0f;\n")
                .append("    for (int i = 0; i < beard_count; i++) {\n")
                .append("        int p = (beard_start + i) * 8;\n")
                .append("        int w6 = beard_i32[p + 6];\n")
                .append("        int t_adj = w6 & 255;\n")
                .append("        if (t_adj == 0) continue;\n")
                .append("        int g_delta = (w6 << 16) >> 24;\n")
                .append("        int min_x = beard_i32[p], min_y = beard_i32[p + 1] + g_delta, min_z = beard_i32[p + 2];\n")
                .append("        int max_x = beard_i32[p + 3], max_y = beard_i32[p + 4], max_z = beard_i32[p + 5];\n")
                .append("        if (bx < min_x - 8 || bx > max_x + 8 || by < min_y - 8 || by > max_y + 8 || bz < min_z - 8 || bz > max_z + 8) continue;\n")
                .append("        int inside_xz = (bx >= min_x && bx <= max_x && bz >= min_z && bz <= max_z);\n")
                .append("        if (t_adj == 3 && inside_xz && by >= min_y && by <= max_y) {\n")
                .append("            delta -= 4.0f;\n")
                .append("        } else if (t_adj == 2 && inside_xz) {\n")
                .append("            if (by <= min_y && by >= min_y - 6) delta += 2.0f;\n")
                .append("            else if (by > min_y && by <= max_y) delta -= 4.0f;\n")
                .append("        } else if (t_adj == 4 && inside_xz && by >= min_y && by <= max_y) {\n")
                .append("            delta += 2.5f;\n")
                .append("        } else if (t_adj == 1 && inside_xz && by >= min_y - 4 && by <= max_y + 4) {\n")
                .append("            delta += 1.5f;\n")
                .append("        }\n")
                .append("    }\n")
                .append("    return delta;\n")
                .append("}\n\n");

        // Emit bounded 4 KiB per-chunk slot kernel (UMA zero-copy DDR5 & PCIe async DMA compatible)
        sb.append("__kernel void evaluate_end_to_end_lca_slot4k_batch(\n")
                .append("        __global uchar* out_slots_4k,\n")
                .append("        __global const int2* chunk_coords_xz,\n")
                .append("        const int chunk_count,\n")
                .append("        const long world_seed,\n")
                .append("        __global const int* beard_pieces_i32,\n")
                .append("        __global const int2* chunk_beard_spans,\n")
                .append("        const int in_kernel_stamps_enabled,\n")
                .append("        const int base_state_count,\n")
                .append("        __global const int* chunk_tree_anchors_9,\n")
                .append("        __global const int* placed_stamps_i32,\n")
                .append("        __global const int2* chunk_stamp_spans,\n")
                .append("        __global const int* atlas_pal_meta_i32,\n")
                .append("        __global const int* atlas_slices_i32,\n")
                .append("        __global const int* atlas_runs_i32,\n")
                .append("        __global const int* atlas_rules_i32) {\n");
        for (ConceptLocalBuffer buf : wg.localBuffers()) {
            String elemType = buf.elementByteWidth() == 1
                    ? "uchar"
                    : (buf.elementByteWidth() == 2 ? "short" : mapType(buf.elementType(), options));
            sb.append("    __local ")
                    .append(elemType)
                    .append(' ')
                    .append(buf.name())
                    .append('[')
                    .append(buf.length())
                    .append("];\n");
        }
        sb.append("    __local uchar local_voxels[4096];\n")
                .append("    __local uchar local_ov_prio[4096];\n")
                .append("    __local ushort local_ov_glob[4096];\n")
                .append("    __local short local_ws[256];\n")
                .append("    __local short local_of[256];\n")
                .append("    __local short local_mb[256];\n")
                .append("    __local short local_mbnl[256];\n")
                .append("    __local ushort local_extra_u16[192];\n")
                .append("    __local int shared_meta[8];\n")
                .append("    __local ulong shared_mask[2];\n")
                .append("    __local int shared_seam_start[12];\n")
                .append("    __local int shared_seam_end[12];\n")
                .append("    __local int shared_seam_state[12];\n")
                .append("    int lid = (int)get_local_id(0);\n")
                .append("    int lsize = (int)get_local_size(0);\n")
                .append("    int c = (int)get_group_id(0);\n")
                .append("    if (c >= chunk_count) return;\n")
                .append("    __global uchar* slot = out_slots_4k + ((ulong)c * 4096UL);\n")
                .append("    __global ulong* slot_u64 = (__global ulong*)slot;\n")
                .append("    for (int z = lid; z < 512; z += lsize) { slot_u64[z] = 0UL; }\n")
                .append("    int2 cpos = chunk_coords_xz[c];\n")
                .append("    int2 bspan = chunk_beard_spans[c];\n")
                .append("    int b_start = bspan.x, b_count = bspan.y;\n")
                .append("    int base_bx = cpos.x << 4, base_bz = cpos.y << 4;\n")
                .append("    for (int idx = lid; idx < 1225; idx += lsize) {\n")
                .append("        int cx = idx / 245;\n")
                .append("        int rem = idx - cx * 245;\n")
                .append("        int cz = rem / 49;\n")
                .append("        int cy = rem - cz * 49;\n")
                .append("        int cbx = base_bx + (cx << 2), cby = -64 + (cy << 3), cbz = base_bz + (cz << 2);\n")
                .append("        float cval = ")
                .append(cornerFn)
                .append("(cbx, cby, cbz);\n")
                .append("        if (b_count > 0) cval += compute_beard_contribution_f32(cbx, cby, cbz, beard_pieces_i32, b_start, b_count);\n")
                .append("        local_corners[idx] = cval;\n")
                .append("    }\n")
                .append("    barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);\n")
                .append("    if (lid < 16) {\n")
                .append("        int tx = lid & 3, tz = (lid >> 2) & 3;\n")
                .append("        int c00 = (tx * 5 + tz) * 49;\n")
                .append("        int c10 = ((tx + 1) * 5 + tz) * 49;\n")
                .append("        int c01 = (tx * 5 + (tz + 1)) * 49;\n")
                .append("        int c11 = ((tx + 1) * 5 + (tz + 1)) * 49;\n")
                .append("        float n = local_corners[c00 + 16];\n")
                .append("        float e = local_corners[c00 + 20];\n")
                .append("        for (int dz = 0; dz < 4; dz++) {\n")
                .append("            float fz = (float)dz * 0.25f;\n")
                .append("            for (int dx = 0; dx < 4; dx++) {\n")
                .append("                float fx = (float)dx * 0.25f;\n")
                .append("                float ncol = (local_corners[c00 + 16] * (1.0f - fx) + local_corners[c10 + 16] * fx) * (1.0f - fz)\n")
                .append("                           + (local_corners[c01 + 16] * (1.0f - fx) + local_corners[c11 + 16] * fx) * fz;\n")
                .append("                int found_y = clamp(64 + (int)rint(ncol * 28.0f), 48, 110);\n")
                .append("                if (b_count > 0) {\n")
                .append("                    found_y = adjust_surf_y_for_beard(base_bx + (tx << 2 | dx), base_bz + (tz << 2 | dz), found_y, beard_pieces_i32, b_start, b_count);\n")
                .append("                }\n")
                .append("                local_surf_y[((tz << 2 | dz) << 4) | (tx << 2 | dx)] = (short)found_y;\n")
                .append("            }\n")
                .append("        }\n")
                .append("        int tile_sy = (int)local_surf_y[((tz << 2) << 4) | (tx << 2)];\n")
                .append("        float ct = n * 4.0f, ch = e * 4.0f;\n")
                .append("        float cc = (tile_sy <= 62) ? -0.30f : (ct - ch * 0.5f);\n")
                .append("        for (int s = 0; s < 24; s++) {\n")
                .append("            local_biomes[s * 16 + lid] = (uchar)(classifyClimateBiomeQuartet(ct, ch, cc, ch * 0.5f, (s - 4) << 2) & 7);\n")
                .append("        }\n")
                .append("    }\n")
                .append("    barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);\n");
        sb.append("""
                    __global ushort* slot_sec_off = (__global ushort*)(slot + 32);
                    __global ulong* slot_masks = (__global ulong*)(slot + 80);
                    __global ulong* slot_hms = (__global ulong*)(slot + 272);
                    __global short* slot_surfy = (__global short*)(slot + 1456);
                    for (int col = lid; col < 256; col += lsize) { slot_surfy[col] = local_surf_y[col]; }
                    if (in_kernel_stamps_enabled != 0) {
                        for (int col = lid; col < 256; col += lsize) {
                            int sy = (int)local_surf_y[col];
                            short iws = (short)clamp(max(sy, 62) - (-64) + 1, 0, 511);
                            short iof = (short)clamp(sy - (-64) + 1, 0, 511);
                            local_ws[col] = iws; local_of[col] = iof; local_mb[col] = iws; local_mbnl[col] = iws;
                        }
                        if (lid == 0) {
                            int max_sy = -64, min_sy = 320;
                            for (int i = 0; i < 256; i++) {
                                int sy = (int)local_surf_y[i];
                                if (sy > max_sy) max_sy = sy;
                                if (sy < min_sy) min_sy = sy;
                            }
                            int sec_ov_mask = 0;
                            int anch_base = c * 9;
                            for (int ni = 0; ni < 9; ni++) {
                                int anch = chunk_tree_anchors_9[anch_base + ni];
                                if ((anch & 0x60000000) == 0x40000000) {
                                    int ay = min(216, ((anch >> 12) & 511) - 64);
                                    int abiome = (anch >> 24) & 7;
                                    int lo_y = (ni == 4 && (abiome == 1 || abiome == 5)) ? min(ay, min_sy) : ay;
                                    int hi_y = (ni == 4 && (abiome == 1 || abiome == 5)) ? max(ay, max_sy) : ay;
                                    int s0 = clamp((lo_y + 1 + 64) >> 4, 0, 23);
                                    int s1 = clamp((min(224, hi_y + 8) + 64) >> 4, 0, 23);
                                    for (int ss = s0; ss <= s1; ss++) sec_ov_mask |= (1 << ss);
                                }
                            }
                            int2 sspan = chunk_stamp_spans[c];
                            for (int pi = 0; pi < sspan.y; pi++) {
                                int pb = (sspan.x + pi) * 8;
                                int orig_y = placed_stamps_i32[pb + 1];
                                int sl_idx = placed_stamps_i32[pb + 3];
                                int sflags = placed_stamps_i32[pb + 4];
                                int r_start = atlas_slices_i32[sl_idx * 4 + 2];
                                int r_cnt = atlas_slices_i32[sl_idx * 4 + 3];
                                for (int ri = 0; ri < r_cnt; ri++) {
                                    int rel_sec = atlas_runs_i32[(r_start + ri) * 4];
                                    int y_lo = (((sflags & 1) != 0) ? (min_sy - 1 + orig_y) : orig_y) + (rel_sec << 4);
                                    int y_hi = (((sflags & 1) != 0) ? (max_sy + 1 + orig_y) : orig_y) + (rel_sec << 4) + 15;
                                    if (y_hi >= -64 && y_lo <= 319) {
                                        int s0 = clamp((y_lo + 64) >> 4, 0, 23);
                                        int s1 = clamp((y_hi + 64) >> 4, 0, 23);
                                        for (int ss = s0; ss <= s1; ss++) sec_ov_mask |= (1 << ss);
                                    }
                                }
                            }
                            shared_meta[0] = max_sy;
                            shared_meta[1] = min_sy;
                            shared_meta[2] = sec_ov_mask;
                            shared_meta[7] = 1760;
                        }
                        barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
                        int max_sy = shared_meta[0], min_sy = shared_meta[1], sec_ov_mask = shared_meta[2];
                        int sea_level = 62, uniform_secs = 0, active_octs = 0, skipped_octs = 0, total_dec_runs = 0;
                        int extra_cnt = 0, overflow_flag = 0;
                        int seam_start[12], seam_end[12], seam_state[12];
                        for (int s = 0; s < 24; s++) {
                            int base_by = (s - 4) << 4, top_by = base_by + 15;
                            int dom_biome = (int)local_biomes[s * 16] & 7;
                            int sec_has_ov = (sec_ov_mask >> s) & 1;
                            if (sec_has_ov == 0) {
                                if (lid == 0) {
                                    slot[1712 + s] = (uchar)(0x80 | dom_biome);
                                    int sec_has_beard = (b_count > 0) ? box_intersects_beard(base_bx, base_by, base_bz, base_bx + 15, top_by, base_bz + 15, beard_pieces_i32, b_start, b_count) : 0;
                                    if (!sec_has_beard && base_by > max(max_sy + 1, sea_level + 1)) {
                                        slot_sec_off[s] = (ushort)0x8000u; slot_masks[s] = 0UL; slot[1736 + s] = (uchar)toGray4(15);
                                        uniform_secs++; skipped_octs += 64;
                                    } else {
                                        ulong mask = 0UL;
                                        for (int oct = 0; oct < 64; oct++) {
                                            int yzx = h3ToYzx(oct << 6);
                                            int tx = (yzx & 15) >> 2, tz = ((yzx >> 4) & 15) >> 2, oct_y = (yzx >> 8) >> 2;
                                            int cell_y = (s << 1) + (oct_y >> 1);
                                            float c000 = local_corners[(tx * 5 + tz) * 49 + cell_y];
                                            float c111 = local_corners[((tx + 1) * 5 + (tz + 1)) * 49 + cell_y + 1];
                                            float c100 = local_corners[((tx + 1) * 5 + tz) * 49 + cell_y];
                                            float c011 = local_corners[(tx * 5 + (tz + 1)) * 49 + cell_y + 1];
                                            float c_min = fmin(fmin(c000, c111), fmin(c100, c011));
                                            int oct_top = base_by + (oct_y << 2) + 3, oct_bot = base_by + (oct_y << 2);
                                            int near_surf = (oct_top >= min_sy - 3 && oct_bot <= max(max_sy + 1, sea_level));
                                            int deep_cave = (s >= 1 && oct_top < min_sy - 5 && oct_bot > -58 && c_min < -0.12890625f);
                                            int beard_wake = sec_has_beard ? box_intersects_beard(base_bx + (tx << 2), oct_bot, base_bz + (tz << 2), base_bx + (tx << 2) + 3, oct_top, base_bz + (tz << 2) + 3, beard_pieces_i32, b_start, b_count) : 0;
                                            if (near_surf || deep_cave || beard_wake) mask |= (1UL << oct);
                                        }
                                        int act_cnt = (int)popcount(mask); active_octs += act_cnt; skipped_octs += (64 - act_cnt);
                                        int dom = (base_by < 0) ? 2 : ((top_by < min_sy) ? 1 : 0);
                                        int seam_cnt = 0; ulong seam_oct_mask = 0UL;
                                        if (base_by <= max_sy) {
                                            for (int ord = 0; ord < 12; ord++) {
                                                int dst = selectUndergroundDecoratorState(s, ord, dom_biome);
                                                if (dst == 0) continue;
                                                int sh3 = computeVanillaAnchoredOreStartH3(cpos.x, cpos.y, s, 100 + ord * 37, world_seed);
                                                int oidx = (sh3 >> 6) & 63;
                                                int disc = (ord == 5 || ord == 6) ? 10 : ((ord == 3) ? 5 : 0);
                                                if (evaluateOctantDiscardExposure((long)mask, oidx, disc, sh3) == 0) continue;
                                                int slen = (ord >= 7) ? 16 : 8, eh3 = min(4096, sh3 + slen);
                                                seam_start[seam_cnt] = sh3; seam_end[seam_cnt] = eh3; seam_state[seam_cnt] = dst; seam_cnt++;
                                                seam_oct_mask |= (1UL << oidx) | (1UL << (((eh3 - 1) >> 6) & 63));
                                            }
                                        }
                                        int slot_lim = 4096 - (extra_cnt << 1), run_cur = shared_meta[7];
                                        int non_dom_runs = 0, sec_hdr = run_cur; ulong occ_mask = 0UL;
                                        if (sec_hdr + 4 <= slot_lim) {
                                            int wp = sec_hdr + 4, cur_start = -1, cur_len = 0, cur_st = dom;
                                            for (int oct = 0; oct < 64; oct++) {
                                                int byzx = h3ToYzx(oct << 6);
                                                int ox = byzx & 12, oz = (byzx >> 4) & 12, oy = (byzx >> 8) & 12;
                                                int oct_act = (int)((mask >> oct) & 1UL), has_sm = (int)((seam_oct_mask >> oct) & 1UL);
                                                if (!oct_act && !has_sm) {
                                                    int wy = base_by + oy, syc = (int)local_surf_y[oz * 16 + ox];
                                                    int ust = (wy > max(syc, sea_level)) ? 0 : ((wy <= syc) ? ((wy < 0) ? 2 : 1) : 5);
                                                    if (ust == dom) {
                                                        if (cur_len > 0) {
                                                            if (wp + 4 <= slot_lim) {
                                                                slot[wp] = (uchar)(cur_start & 255); slot[wp + 1] = (uchar)((cur_start >> 8) & 255);
                                                                slot[wp + 2] = (uchar)(cur_len - 1); slot[wp + 3] = (uchar)cur_st; wp += 4; non_dom_runs++;
                                                                if (cur_st >= 6) total_dec_runs++;
                                                                for (int ro = cur_start >> 6; ro <= ((cur_start + cur_len - 1) >> 6); ro++) occ_mask |= (1UL << ro);
                                                            } else overflow_flag = 1;
                                                            cur_len = 0; cur_st = dom;
                                                        }
                                                        continue;
                                                    }
                                                    for (int loc = 0; loc < 64; loc++) {
                                                        int h3 = (oct << 6) | loc;
                                                        if (cur_len > 0 && cur_st == ust && cur_len < 256 && cur_start + cur_len == h3) cur_len++;
                                                        else {
                                                            if (cur_len > 0) {
                                                                if (wp + 4 <= slot_lim) {
                                                                    slot[wp] = (uchar)(cur_start & 255); slot[wp + 1] = (uchar)((cur_start >> 8) & 255);
                                                                    slot[wp + 2] = (uchar)(cur_len - 1); slot[wp + 3] = (uchar)cur_st; wp += 4; non_dom_runs++;
                                                                    if (cur_st >= 6) total_dec_runs++;
                                                                    for (int ro = cur_start >> 6; ro <= ((cur_start + cur_len - 1) >> 6); ro++) occ_mask |= (1UL << ro);
                                                                } else overflow_flag = 1;
                                                            }
                                                            cur_start = h3; cur_len = 1; cur_st = ust;
                                                        }
                                                    }
                                                    continue;
                                                }
                                                int tx = ox >> 2, tz = oz >> 2, cell_y = (s << 1) + (oy >> 3);
                                                float y_f0 = ((oy & 7) == 0) ? 0.0f : 0.5f;
                                                int tbiome = (int)local_biomes[s * 16 + (tz << 2 | tx)] & 7;
                                                for (int loc = 0; loc < 64; loc++) {
                                                    int h3 = (oct << 6) | loc, st;
                                                    if (!oct_act) {
                                                        int wy = base_by + oy, syc = (int)local_surf_y[oz * 16 + ox];
                                                        st = (wy > max(syc, sea_level)) ? 0 : ((wy <= syc) ? ((wy < 0) ? 2 : 1) : 5);
                                                    } else {
                                                        int yzx = h3ToYzx(h3);
                                                        int lx = yzx & 15, lz = (yzx >> 4) & 15, ly = (yzx >> 8) & 15;
                                                        int wy = base_by + ly, sy = (int)local_surf_y[lz * 16 + lx];
                                                        float fx = (float)(lx & 3) * 0.25f, fz = (float)(lz & 3) * 0.25f, fy = y_f0 + (float)(ly & 3) * 0.125f;
                                                        int c00 = (tx * 5 + tz) * 49 + cell_y, c10 = ((tx + 1) * 5 + tz) * 49 + cell_y;
                                                        int c01 = (tx * 5 + (tz + 1)) * 49 + cell_y, c11 = ((tx + 1) * 5 + (tz + 1)) * 49 + cell_y;
                                                        float d0 = (local_corners[c00] * (1.0f - fx) + local_corners[c10] * fx) * (1.0f - fz) + (local_corners[c01] * (1.0f - fx) + local_corners[c11] * fx) * fz;
                                                        float d1 = (local_corners[c00 + 1] * (1.0f - fx) + local_corners[c10 + 1] * fx) * (1.0f - fz) + (local_corners[c01 + 1] * (1.0f - fx) + local_corners[c11 + 1] * fx) * fz;
                                                        float raw_c = d0 + (d1 - d0) * fy;
                                                        float dens = (wy < sy - 5 && wy > -58 && raw_c < -0.1328125f) ? (raw_c + 0.1328125f) : (((float)(sy - wy) * 0.16f) + ((wy < sy - 4) ? (raw_c * 0.30f) : 0.08f));
                                                        if (sec_has_beard) dens += compute_voxel_beard_delta_f32(base_bx + lx, wy, base_bz + lz, beard_pieces_i32, b_start, b_count);
                                                        if (dens > 0.0f) st = selectSurfaceStrataBlock(tbiome, wy, sy, sea_level);
                                                        else if (wy <= sea_level && wy > sy) st = 5;
                                                        else if (wy < -54) st = 19;
                                                        else st = 0;
                                                        if (st == 0 && wy == sy + 1 && sy >= sea_level) {
                                                            int th = computeVanillaAnchoredOreStartH3(cpos.x, cpos.y, s, (lz << 4) | lx, world_seed);
                                                            st = selectSurfaceDecoratorBlock(tbiome, sy, sea_level, th);
                                                        } else if (st == 5 && wy == sy + 1 && sy < sea_level - 1) {
                                                            int th = computeVanillaAnchoredOreStartH3(cpos.x, cpos.y, s, 512 + ((lz << 4) | lx), world_seed);
                                                            int aq = selectSurfaceDecoratorBlock(tbiome, sy, sea_level, th);
                                                            if (aq != 0) st = aq;
                                                        }
                                                    }
                                                    if ((st == 1 || st == 2) && has_sm) {
                                                        for (int m = 0; m < seam_cnt; m++) { if (h3 >= seam_start[m] && h3 < seam_end[m]) st = seam_state[m]; }
                                                    }
                                                    if (st == dom) {
                                                        if (cur_len > 0) {
                                                            if (wp + 4 <= slot_lim) {
                                                                slot[wp] = (uchar)(cur_start & 255); slot[wp + 1] = (uchar)((cur_start >> 8) & 255);
                                                                slot[wp + 2] = (uchar)(cur_len - 1); slot[wp + 3] = (uchar)cur_st; wp += 4; non_dom_runs++;
                                                                if (cur_st >= 6) total_dec_runs++;
                                                                for (int ro = cur_start >> 6; ro <= ((cur_start + cur_len - 1) >> 6); ro++) occ_mask |= (1UL << ro);
                                                            } else overflow_flag = 1;
                                                            cur_len = 0; cur_st = dom;
                                                        }
                                                    } else if (cur_len > 0 && cur_st == st && cur_len < 256 && cur_start + cur_len == h3) cur_len++;
                                                    else {
                                                        if (cur_len > 0) {
                                                            if (wp + 4 <= slot_lim) {
                                                                slot[wp] = (uchar)(cur_start & 255); slot[wp + 1] = (uchar)((cur_start >> 8) & 255);
                                                                slot[wp + 2] = (uchar)(cur_len - 1); slot[wp + 3] = (uchar)cur_st; wp += 4; non_dom_runs++;
                                                                if (cur_st >= 6) total_dec_runs++;
                                                                for (int ro = cur_start >> 6; ro <= ((cur_start + cur_len - 1) >> 6); ro++) occ_mask |= (1UL << ro);
                                                            } else overflow_flag = 1;
                                                        }
                                                        cur_start = h3; cur_len = 1; cur_st = st;
                                                    }
                                                }
                                            }
                                            if (cur_len > 0) {
                                                if (wp + 4 <= slot_lim) {
                                                    slot[wp] = (uchar)(cur_start & 255); slot[wp + 1] = (uchar)((cur_start >> 8) & 255);
                                                    slot[wp + 2] = (uchar)(cur_len - 1); slot[wp + 3] = (uchar)cur_st; wp += 4; non_dom_runs++;
                                                    if (cur_st >= 6) total_dec_runs++;
                                                    for (int ro = cur_start >> 6; ro <= ((cur_start + cur_len - 1) >> 6); ro++) occ_mask |= (1UL << ro);
                                                } else overflow_flag = 1;
                                            }
                                            if (non_dom_runs == 0) {
                                                int sky2 = (base_by > max_sy) ? 1 : 0;
                                                slot_sec_off[s] = (ushort)(0x8000u | (dom & 0x7FF)); slot_masks[s] = 0UL;
                                                slot[1736 + s] = (uchar)toGray4(sky2 != 0 ? 15 : 0); uniform_secs++;
                                            } else {
                                                uchar gray = (uchar)toGray4((top_by >= min_sy) ? 15 : 0);
                                                slot[sec_hdr] = (uchar)dom; slot[sec_hdr + 1] = gray;
                                                slot[sec_hdr + 2] = (uchar)(non_dom_runs & 255); slot[sec_hdr + 3] = (uchar)((non_dom_runs >> 8) & 255);
                                                slot_sec_off[s] = (ushort)sec_hdr; slot_masks[s] = occ_mask; slot[1736 + s] = gray; shared_meta[7] = wp;
                                            }
                                        } else { overflow_flag = 1; }
                                    }
                                }
                            } else {
                                barrier(CLK_LOCAL_MEM_FENCE);
                                if (lid == 0) {
                                    slot[1712 + s] = (uchar)(0x80 | dom_biome);
                                    int sb_flag = (b_count > 0) ? box_intersects_beard(base_bx, base_by, base_bz, base_bx + 15, top_by, base_bz + 15, beard_pieces_i32, b_start, b_count) : 0;
                                    shared_meta[3] = sb_flag;
                                    if (!sb_flag && base_by > max(max_sy + 1, sea_level + 1)) {
                                        shared_mask[0] = 0UL; shared_meta[4] = 0; shared_mask[1] = 0UL; skipped_octs += 64;
                                    } else {
                                        ulong mask = 0UL;
                                        for (int oct = 0; oct < 64; oct++) {
                                            int yzx = h3ToYzx(oct << 6);
                                            int tx = (yzx & 15) >> 2, tz = ((yzx >> 4) & 15) >> 2, oct_y = (yzx >> 8) >> 2;
                                            int cell_y = (s << 1) + (oct_y >> 1);
                                            float c000 = local_corners[(tx * 5 + tz) * 49 + cell_y];
                                            float c111 = local_corners[((tx + 1) * 5 + (tz + 1)) * 49 + cell_y + 1];
                                            float c100 = local_corners[((tx + 1) * 5 + tz) * 49 + cell_y];
                                            float c011 = local_corners[(tx * 5 + (tz + 1)) * 49 + cell_y + 1];
                                            float c_min = fmin(fmin(c000, c111), fmin(c100, c011));
                                            int oct_top = base_by + (oct_y << 2) + 3, oct_bot = base_by + (oct_y << 2);
                                            int near_surf = (oct_top >= min_sy - 3 && oct_bot <= max(max_sy + 1, sea_level));
                                            int deep_cave = (s >= 1 && oct_top < min_sy - 5 && oct_bot > -58 && c_min < -0.12890625f);
                                            int beard_wake = sb_flag ? box_intersects_beard(base_bx + (tx << 2), oct_bot, base_bz + (tz << 2), base_bx + (tx << 2) + 3, oct_top, base_bz + (tz << 2) + 3, beard_pieces_i32, b_start, b_count) : 0;
                                            if (near_surf || deep_cave || beard_wake) mask |= (1UL << oct);
                                        }
                                        int act_cnt = (int)popcount(mask); active_octs += act_cnt; skipped_octs += (64 - act_cnt);
                                        int sm_cnt = 0; ulong sm_mask = 0UL;
                                        if (base_by <= max_sy) {
                                            for (int ord = 0; ord < 12; ord++) {
                                                int dst = selectUndergroundDecoratorState(s, ord, dom_biome);
                                                if (dst == 0) continue;
                                                int sh3 = computeVanillaAnchoredOreStartH3(cpos.x, cpos.y, s, 100 + ord * 37, world_seed);
                                                int oidx = (sh3 >> 6) & 63;
                                                int disc = (ord == 5 || ord == 6) ? 10 : ((ord == 3) ? 5 : 0);
                                                if (evaluateOctantDiscardExposure((long)mask, oidx, disc, sh3) == 0) continue;
                                                int slen = (ord >= 7) ? 16 : 8, eh3 = min(4096, sh3 + slen);
                                                shared_seam_start[sm_cnt] = sh3; shared_seam_end[sm_cnt] = eh3; shared_seam_state[sm_cnt] = dst; sm_cnt++;
                                                sm_mask |= (1UL << oidx) | (1UL << (((eh3 - 1) >> 6) & 63));
                                            }
                                        }
                                        shared_mask[0] = mask; shared_meta[4] = sm_cnt; shared_mask[1] = sm_mask;
                                    }
                                }
                                barrier(CLK_LOCAL_MEM_FENCE);
                                int sb_flag = shared_meta[3], sm_cnt = shared_meta[4];
                                ulong mask = shared_mask[0], sm_mask = shared_mask[1];
                                for (int oct = lid; oct < 64; oct += lsize) {
                                    int byzx = h3ToYzx(oct << 6);
                                    int ox = byzx & 12, oz = (byzx >> 4) & 12, oy = (byzx >> 8) & 12;
                                    int oct_act = (int)((mask >> oct) & 1UL), has_sm = (int)((sm_mask >> oct) & 1UL);
                                    int tx = ox >> 2, tz = oz >> 2, cell_y = (s << 1) + (oy >> 3);
                                    float y_f0 = ((oy & 7) == 0) ? 0.0f : 0.5f;
                                    int tbiome = (int)local_biomes[s * 16 + (tz << 2 | tx)] & 7;
                                    for (int loc = 0; loc < 64; loc++) {
                                        int h3 = (oct << 6) | loc, st;
                                        if (!oct_act) {
                                            int wy = base_by + oy, syc = (int)local_surf_y[oz * 16 + ox];
                                            st = (wy > max(syc, sea_level)) ? 0 : ((wy <= syc) ? ((wy < 0) ? 2 : 1) : 5);
                                        } else {
                                            int yzx = h3ToYzx(h3);
                                            int lx = yzx & 15, lz = (yzx >> 4) & 15, ly = (yzx >> 8) & 15;
                                            int wy = base_by + ly, sy = (int)local_surf_y[lz * 16 + lx];
                                            float fx = (float)(lx & 3) * 0.25f, fz = (float)(lz & 3) * 0.25f, fy = y_f0 + (float)(ly & 3) * 0.125f;
                                            int c00 = (tx * 5 + tz) * 49 + cell_y, c10 = ((tx + 1) * 5 + tz) * 49 + cell_y;
                                            int c01 = (tx * 5 + (tz + 1)) * 49 + cell_y, c11 = ((tx + 1) * 5 + (tz + 1)) * 49 + cell_y;
                                            float d0 = (local_corners[c00] * (1.0f - fx) + local_corners[c10] * fx) * (1.0f - fz) + (local_corners[c01] * (1.0f - fx) + local_corners[c11] * fx) * fz;
                                            float d1 = (local_corners[c00 + 1] * (1.0f - fx) + local_corners[c10 + 1] * fx) * (1.0f - fz) + (local_corners[c01 + 1] * (1.0f - fx) + local_corners[c11 + 1] * fx) * fz;
                                            float raw_c = d0 + (d1 - d0) * fy;
                                            float dens = (wy < sy - 5 && wy > -58 && raw_c < -0.1328125f) ? (raw_c + 0.1328125f) : (((float)(sy - wy) * 0.16f) + ((wy < sy - 4) ? (raw_c * 0.30f) : 0.08f));
                                            if (sb_flag) dens += compute_voxel_beard_delta_f32(base_bx + lx, wy, base_bz + lz, beard_pieces_i32, b_start, b_count);
                                            if (dens > 0.0f) st = selectSurfaceStrataBlock(tbiome, wy, sy, sea_level);
                                            else if (wy <= sea_level && wy > sy) st = 5;
                                            else if (wy < -54) st = 19;
                                            else st = 0;
                                            if (st == 0 && wy == sy + 1 && sy >= sea_level) {
                                                int th = computeVanillaAnchoredOreStartH3(cpos.x, cpos.y, s, (lz << 4) | lx, world_seed);
                                                st = selectSurfaceDecoratorBlock(tbiome, sy, sea_level, th);
                                            } else if (st == 5 && wy == sy + 1 && sy < sea_level - 1) {
                                                int th = computeVanillaAnchoredOreStartH3(cpos.x, cpos.y, s, 512 + ((lz << 4) | lx), world_seed);
                                                int aq = selectSurfaceDecoratorBlock(tbiome, sy, sea_level, th);
                                                if (aq != 0) st = aq;
                                            }
                                        }
                                        if ((st == 1 || st == 2) && has_sm) {
                                            for (int m = 0; m < sm_cnt; m++) { if (h3 >= shared_seam_start[m] && h3 < shared_seam_end[m]) st = shared_seam_state[m]; }
                                        }
                                        local_voxels[h3] = (uchar)st;
                                        local_ov_prio[h3] = 0;
                                    }
                                }
                                barrier(CLK_LOCAL_MEM_FENCE);
                                if (lid == 0) {
                                    int any_ov_written = 0;
                                    int2 sspan = chunk_stamp_spans[c];
                                    for (int pi = 0; pi < sspan.y; pi++) {
                                        int pb = (sspan.x + pi) * 8;
                                        int orig_x = placed_stamps_i32[pb], orig_y = placed_stamps_i32[pb + 1], orig_z = placed_stamps_i32[pb + 2];
                                        int sl_idx = placed_stamps_i32[pb + 3], sflags = placed_stamps_i32[pb + 4];
                                        int dcx = atlas_slices_i32[sl_idx * 4], dcz = atlas_slices_i32[sl_idx * 4 + 1];
                                        int r_start = atlas_slices_i32[sl_idx * 4 + 2], r_cnt = atlas_slices_i32[sl_idx * 4 + 3];
                                        int slice_min_wx = orig_x + (dcx << 4), slice_min_wz = orig_z + (dcz << 4);
                                        for (int ri = 0; ri < r_cnt; ri++) {
                                            int rb = (r_start + ri) * 4;
                                            int rel_sec = atlas_runs_i32[rb];
                                            int y_lo = (((sflags & 1) != 0) ? (min_sy - 1 + orig_y) : orig_y) + (rel_sec << 4);
                                            int y_hi = (((sflags & 1) != 0) ? (max_sy + 1 + orig_y) : orig_y) + (rel_sec << 4) + 15;
                                            if (y_hi < base_by || y_lo > top_by) continue;
                                            int w1 = atlas_runs_i32[rb + 1];
                                            int start_h3 = w1 & 0xFFFF, run_len = (w1 >> 16) & 0xFFFF;
                                            int base_pal_idx = atlas_runs_i32[rb + 2];
                                            int w3 = atlas_runs_i32[rb + 3];
                                            int rule_start = w3 & 0xFFFFF, rule_cnt = (w3 >> 20) & 0xFF;
                                            int end_h3 = min(4096, start_h3 + run_len);
                                            for (int h3 = start_h3; h3 < end_h3; h3++) {
                                                int yzx = h3ToYzx(h3);
                                                int wx = slice_min_wx + (yzx & 15), wz = slice_min_wz + ((yzx >> 4) & 15);
                                                if ((wx >> 4) != cpos.x || (wz >> 4) != cpos.y) continue;
                                                int lx = wx & 15, lz = wz & 15;
                                                int col = (lz << 4) | lx;
                                                int col_floor = (int)local_surf_y[col];
                                                int c_l = (int)local_surf_y[(lz << 4) | max(0, lx - 1)];
                                                int c_r = (int)local_surf_y[(lz << 4) | min(15, lx + 1)];
                                                int c_n = (int)local_surf_y[(max(0, lz - 1) << 4) | lx];
                                                int c_s = (int)local_surf_y[(min(15, lz + 1) << 4) | lx];
                                                int sm_floor = (2 * col_floor + c_l + c_r + c_n + c_s + 3) / 6;
                                                int rel_y = (rel_sec << 4) + ((yzx >> 8) & 15);
                                                int wy = ((sflags & 1) != 0) ? (sm_floor + orig_y + rel_y) : (orig_y + rel_y);
                                                if (((sflags & 1) != 0) && rel_y == 0 && sm_floor > col_floor) {
                                                    int fill_y = sm_floor + orig_y - 1;
                                                    if (fill_y >= base_by && fill_y <= top_by) {
                                                        int fh3 = yzxToH3(lx, fill_y & 15, lz);
                                                        if (4 >= (int)local_ov_prio[fh3]) { local_ov_prio[fh3] = 4; local_ov_glob[fh3] = 4; any_ov_written = 1; }
                                                    }
                                                }
                                                if (wy < base_by || wy > top_by) continue;
                                                int p_idx = (rule_cnt > 0) ? eval_processor_pal_idx(base_pal_idx, atlas_rules_i32, rule_start, rule_cnt, wx, wy, wz, world_seed) : base_pal_idx;
                                                int pm0 = atlas_pal_meta_i32[p_idx * 2], pm1 = atlas_pal_meta_i32[p_idx * 2 + 1];
                                                int cat = pm1 & 255;
                                                int glob_st, prio;
                                                if (cat >= 4) {
                                                    int subm = (wy <= 62 && col_floor < 62);
                                                    glob_st = subm ? ((pm0 >> 16) & 0xFFFF) : (pm0 & 0xFFFF);
                                                    if (glob_st == 0 && subm && ((sflags & 2) != 0)) continue;
                                                    prio = (glob_st == 0) ? 4 : 5;
                                                } else {
                                                    if (wy < -64 || wy > 224) continue;
                                                    glob_st = pm0 & 0xFFFF;
                                                    prio = 3 - cat;
                                                }
                                                int th3 = yzxToH3(lx, wy & 15, lz);
                                                if (prio >= (int)local_ov_prio[th3]) {
                                                    local_ov_prio[th3] = (uchar)prio;
                                                    local_ov_glob[th3] = (ushort)glob_st;
                                                    any_ov_written = 1;
                                                }
                                            }
                                        }
                                    }
                                    int anch_base = c * 9;
                                    for (int dcz = -1; dcz <= 1; dcz++) {
                                        for (int dcx = -1; dcx <= 1; dcx++) {
                                            int anch = chunk_tree_anchors_9[anch_base + (dcz + 1) * 3 + (dcx + 1)];
                                            if ((anch & 0x60000000) != 0x40000000) continue;
                                            int ncx = cpos.x + dcx, ncz = cpos.y + dcz;
                                            int abiome0 = (anch >> 24) & 7;
                                            int t_sub_cnt = (dcx == 0 && dcz == 0 && (abiome0 == 1 || abiome0 == 5)) ? 4 : 1;
                                            int ptr_x0 = -99, ptr_z0 = -99, ptr_x1 = -99, ptr_z1 = -99, ptr_x2 = -99, ptr_z2 = -99;
                                            for (int t_sub = 0; t_sub < t_sub_cnt; t_sub++) {
                                                int ax, az, base_wy, abiome, ahash;
                                                if (t_sub == 0) {
                                                    ax = anch & 15; az = (anch >> 4) & 15;
                                                    base_wy = min(216, ((anch >> 12) & 511) - 64) + 1;
                                                    abiome = abiome0;
                                                    ahash = mix_anchor_hash_i32(ncx, ncz, world_seed);
                                                    ptr_x0 = ax; ptr_z0 = az;
                                                } else {
                                                    ahash = mix_anchor_hash_i32(ncx * 31 + t_sub * 17, ncz * 31 - t_sub * 13, world_seed);
                                                    ax = 2 + ((ahash & 0x7FFF) % 12);
                                                    az = 2 + ((((int)((uint)ahash >> 8)) & 0x7FFF) % 12);
                                                    if ((abs(ax - ptr_x0) <= 2 && abs(az - ptr_z0) <= 2)
                                                            || (t_sub >= 2 && abs(ax - ptr_x1) <= 2 && abs(az - ptr_z1) <= 2)
                                                            || (t_sub >= 3 && abs(ax - ptr_x2) <= 2 && abs(az - ptr_z2) <= 2)) continue;
                                                    int q_sy = (int)local_surf_y[(az << 4) | ax];
                                                    if (q_sy <= sea_level || q_sy > 216) continue;
                                                    int q_sec = clamp((q_sy + 64) >> 4, 0, 23);
                                                    abiome = (int)local_biomes[q_sec * 16 + (((az >> 2) << 2) | (ax >> 2))] & 7;
                                                    if (abiome != 1 && abiome != 5) continue;
                                                    base_wy = q_sy + 1;
                                                    if (t_sub == 1) { ptr_x1 = ax; ptr_z1 = az; }
                                                    else if (t_sub == 2) { ptr_x2 = ax; ptr_z2 = az; }
                                                }
                                                int log_st, leaf_st, trunk_h;
                                                if (abiome == 5) { log_st = 35; leaf_st = 36; trunk_h = 6 + (ahash & 1); }
                                                else if (abiome == 3) { log_st = 39; leaf_st = 40; trunk_h = 5; }
                                                else if (abiome == 1 && (ahash & 3) == 0) { log_st = 37; leaf_st = 38; trunk_h = 5 + (((int)((uint)ahash >> 3)) & 1); }
                                                else { log_st = 33; leaf_st = 34; trunk_h = 5 + (((int)((uint)ahash >> 2)) & 1); }
                                                int top_tr = min(223, base_wy + trunk_h - 1);
                                                if (dcx == 0 && dcz == 0) {
                                                    int wy0 = max(base_by, base_wy), wy1 = min(top_by, top_tr);
                                                    for (int wy = wy0; wy <= wy1; wy++) {
                                                        int th3 = yzxToH3(ax, wy & 15, az);
                                                        if (3 >= (int)local_ov_prio[th3]) { local_ov_prio[th3] = 3; local_ov_glob[th3] = (ushort)log_st; any_ov_written = 1; }
                                                    }
                                                }
                                                int c_start = max(base_wy + 2, top_tr - 2), c_end = min(224, top_tr + 1);
                                                int wy0 = max(base_by, c_start), wy1 = min(top_by, c_end);
                                                int world_ax = (ncx << 4) + ax, world_az = (ncz << 4) + az;
                                                for (int wy = wy0; wy <= wy1; wy++) {
                                                    int rad = (wy >= top_tr) ? 1 : 2, ly = wy & 15;
                                                    for (int dz = -rad; dz <= rad; dz++) {
                                                        int wz = world_az + dz;
                                                        if ((wz >> 4) != cpos.y) continue;
                                                        int lz = wz & 15;
                                                        for (int dx = -rad; dx <= rad; dx++) {
                                                            if (abs(dx) == rad && abs(dz) == rad && wy == c_end) continue;
                                                            int wx = world_ax + dx;
                                                            if ((wx >> 4) != cpos.x) continue;
                                                            int th3 = yzxToH3(wx & 15, ly, lz);
                                                            if (1 >= (int)local_ov_prio[th3]) { local_ov_prio[th3] = 1; local_ov_glob[th3] = (ushort)leaf_st; any_ov_written = 1; }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    int did_apply = 0;
                                    if (any_ov_written) {
                                        for (int h3 = 0; h3 < 4096; h3++) {
                                            int prio = (int)local_ov_prio[h3];
                                            if (prio == 0) continue;
                                            int cur = (int)local_voxels[h3];
                                            if (prio >= 4 || cur == 0 || (prio == 3 && cur >= 25 && cur < base_state_count)) {
                                                int glob_st = (int)local_ov_glob[h3];
                                                int loc_st = glob_st;
                                                if (glob_st >= base_state_count) {
                                                    int found = -1;
                                                    for (int k = 0; k < extra_cnt; k++) {
                                                        if ((int)local_extra_u16[k] == glob_st) { found = k; break; }
                                                    }
                                                    if (found >= 0) loc_st = base_state_count + found;
                                                    else if (extra_cnt < 192 && base_state_count + extra_cnt <= 255 && shared_meta[7] + ((extra_cnt + 1) << 1) <= 4096) {
                                                        local_extra_u16[extra_cnt] = (ushort)glob_st;
                                                        loc_st = base_state_count + extra_cnt;
                                                        extra_cnt++;
                                                    } else { overflow_flag = 1; loc_st = 0; }
                                                }
                                                local_voxels[h3] = (uchar)loc_st;
                                                did_apply = 1;
                                                if (glob_st != 0) {
                                                    int yzx = h3ToYzx(h3);
                                                    int col = (((yzx >> 4) & 15) << 4) | (yzx & 15);
                                                    int wy = base_by + ((yzx >> 8) & 15);
                                                    short cand_rel = (short)clamp((wy + 1) - (-64), 0, 511);
                                                    if (cand_rel > local_ws[col]) local_ws[col] = cand_rel;
                                                    if (cand_rel > local_mb[col]) local_mb[col] = cand_rel;
                                                    if (prio != 1) {
                                                        if (cand_rel > local_mbnl[col]) local_mbnl[col] = cand_rel;
                                                        if (prio == 5 && cand_rel > local_of[col]) local_of[col] = cand_rel;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    int dom = (base_by < 0) ? 2 : ((top_by < min_sy) ? 1 : 0);
                                    int slot_lim = 4096 - (extra_cnt << 1), run_cur = shared_meta[7];
                                    int non_dom_runs = 0, sec_hdr = run_cur; ulong occ_mask = 0UL;
                                    if (sec_hdr + 4 <= slot_lim) {
                                        int wp = sec_hdr + 4, idx = 0;
                                        while (idx < 4096) {
                                            int st = (int)local_voxels[idx];
                                            if (st == dom) { idx++; continue; }
                                            int rlen = 1;
                                            while (idx + rlen < 4096 && rlen < 256 && (int)local_voxels[idx + rlen] == st) rlen++;
                                            if (wp + 4 <= slot_lim) {
                                                slot[wp] = (uchar)(idx & 255); slot[wp + 1] = (uchar)((idx >> 8) & 255);
                                                slot[wp + 2] = (uchar)(rlen - 1); slot[wp + 3] = (uchar)st; wp += 4; non_dom_runs++;
                                                if (st >= 6) total_dec_runs++;
                                                for (int ro = idx >> 6; ro <= ((idx + rlen - 1) >> 6); ro++) occ_mask |= (1UL << ro);
                                            } else { overflow_flag = 1; }
                                            idx += rlen;
                                        }
                                        if (non_dom_runs == 0) {
                                            int sky2 = (base_by > max_sy) ? 1 : 0;
                                            slot_sec_off[s] = (ushort)(0x8000u | (dom & 0x7FF)); slot_masks[s] = 0UL;
                                            slot[1736 + s] = (uchar)toGray4(sky2 != 0 ? 15 : 0); uniform_secs++;
                                        } else {
                                            uchar gray = (uchar)toGray4((top_by >= min_sy) ? 15 : 0);
                                            uchar sky_tag = (uchar)(gray | (did_apply ? 0x80 : 0));
                                            slot[sec_hdr] = (uchar)dom; slot[sec_hdr + 1] = gray;
                                            slot[sec_hdr + 2] = (uchar)(non_dom_runs & 255); slot[sec_hdr + 3] = (uchar)((non_dom_runs >> 8) & 255);
                                            slot_sec_off[s] = (ushort)sec_hdr; slot_masks[s] = occ_mask; slot[1736 + s] = sky_tag; shared_meta[7] = wp;
                                        }
                                    } else { overflow_flag = 1; }
                                }
                                barrier(CLK_LOCAL_MEM_FENCE);
                            }
                        }
                        barrier(CLK_LOCAL_MEM_FENCE);
                        if (lid < 37) {
                            int w = lid, base_col = w * 7;
                            ulong p_ws = 0UL, p_of = 0UL, p_mb = 0UL, p_mbnl = 0UL;
                            for (int k = 0; k < 7; k++) {
                                int col = base_col + k;
                                if (col < 256) {
                                    int sh = k * 9;
                                    p_ws |= (((ulong)(local_ws[col] & 511)) << sh);
                                    p_of |= (((ulong)(local_of[col] & 511)) << sh);
                                    p_mb |= (((ulong)(local_mb[col] & 511)) << sh);
                                    p_mbnl |= (((ulong)(local_mbnl[col] & 511)) << sh);
                                }
                            }
                            slot_hms[w] = p_ws; slot_hms[37 + w] = p_of; slot_hms[74 + w] = p_mb; slot_hms[111 + w] = p_mbnl;
                        }
                        barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
                        if (lid == 0) {
                            int run_cursor = shared_meta[7];
                            __global ushort* slot_u16 = (__global ushort*)slot;
                            for (int k = 0; k < extra_cnt; k++) { slot_u16[2047 - k] = local_extra_u16[k]; }
                            int tree_anchor = chunk_tree_anchors_9[c * 9 + 4] & ~0x20000000;
                            uint fnv = 0x811C9DC5u;
                            for (int bi = 32; bi < run_cursor; bi++) { fnv ^= (uint)slot[bi]; fnv *= 0x01000193u; }
                            for (int bi = 4096 - (extra_cnt << 1); bi < 4096; bi++) { fnv ^= (uint)slot[bi]; fnv *= 0x01000193u; }
                            __global uint* hdr32 = (__global uint*)slot;
                            __global ushort* hdr16 = (__global ushort*)slot;
                            hdr32[0] = 0x4C435334u;
                            hdr32[1] = (uint)cpos.x;
                            hdr32[2] = (uint)cpos.y;
                            hdr32[3] = (uint)run_cursor;
                            hdr32[4] = fnv;
                            hdr16[10] = (ushort)((uniform_secs & 0x3F) | ((extra_cnt & 0xFF) << 6) | 0x4000u | (overflow_flag ? 0x8000u : 0u));
                            hdr16[11] = (ushort)active_octs;
                            hdr16[12] = (ushort)skipped_octs;
                            hdr16[13] = (ushort)total_dec_runs;
                            hdr32[7] = (uint)tree_anchor;
                        }
                        return;
                    }
                """);
        sb.append("    if (lid < 37) {\n")
                .append("        int w = lid, base_col = w * 7;\n")
                .append("        ulong p_ws = 0UL, p_of = 0UL, p_mb = 0UL, p_mbnl = 0UL;\n")
                .append("        for (int k = 0; k < 7; k++) {\n")
                .append("            int col = base_col + k;\n")
                .append("            if (col < 256) {\n")
                .append("                int sy = (int)local_surf_y[col];\n")
                .append("                ulong enc_ws = (ulong)clamp(max(sy, 62) - (-64) + 1, 0, 511);\n")
                .append("                ulong enc_of = (ulong)clamp(sy - (-64) + 1, 0, 511);\n")
                .append("                int sh = k * 9;\n")
                .append("                p_ws |= (enc_ws << sh);\n")
                .append("                p_of |= (enc_of << sh);\n")
                .append("                p_mb |= (enc_ws << sh);\n")
                .append("                p_mbnl |= (enc_ws << sh);\n")
                .append("            }\n")
                .append("        }\n")
                .append("        slot_hms[w] = p_ws;\n")
                .append("        slot_hms[37 + w] = p_of;\n")
                .append("        slot_hms[74 + w] = p_mb;\n")
                .append("        slot_hms[111 + w] = p_mbnl;\n")
                .append("    }\n")
                .append("    barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);\n")
                .append("    if (lid == 0) {\n")
                .append("        int max_sy = -64, min_sy = 320;\n")
                .append("        for (int i = 0; i < 256; i++) {\n")
                .append("            int sy = (int)local_surf_y[i];\n")
                .append("            if (sy > max_sy) max_sy = sy;\n")
                .append("            if (sy < min_sy) min_sy = sy;\n")
                .append("        }\n")
                .append("        int sea_level = 62, run_cursor = 1760;\n")
                .append("        int uniform_secs = 0, active_octs = 0, skipped_octs = 0, total_dec_runs = 0;\n")
                .append("        int seam_start[12], seam_end[12], seam_state[12];\n")
                .append("        for (int s = 0; s < 24; s++) {\n")
                .append("            int base_by = (s - 4) << 4, top_by = base_by + 15;\n")
                .append("            int dom_biome = (int)local_biomes[s * 16] & 7;\n")
                .append("            slot[1712 + s] = (uchar)(0x80 | dom_biome);\n")
                .append("            int sec_has_beard = (b_count > 0) ? box_intersects_beard(base_bx, base_by, base_bz, base_bx + 15, top_by, base_bz + 15, beard_pieces_i32, b_start, b_count) : 0;\n")
                .append("            if (!sec_has_beard && base_by > max(max_sy + 1, sea_level + 1)) {\n")
                .append("                slot_sec_off[s] = (ushort)0x8000u;\n")
                .append("                slot_masks[s] = 0UL;\n")
                .append("                slot[1736 + s] = (uchar)toGray4(15);\n")
                .append("                uniform_secs++; skipped_octs += 64;\n")
                .append("                continue;\n")
                .append("            }\n")
                .append("            ulong mask = 0UL;\n")
                .append("            for (int oct = 0; oct < 64; oct++) {\n")
                .append("                int yzx = h3ToYzx(oct << 6);\n")
                .append("                int tx = (yzx & 15) >> 2, tz = ((yzx >> 4) & 15) >> 2, oct_y = (yzx >> 8) >> 2;\n")
                .append("                int cell_y = (s << 1) + (oct_y >> 1);\n")
                .append("                float c000 = local_corners[(tx * 5 + tz) * 49 + cell_y];\n")
                .append("                float c111 = local_corners[((tx + 1) * 5 + (tz + 1)) * 49 + cell_y + 1];\n")
                .append("                float c100 = local_corners[((tx + 1) * 5 + tz) * 49 + cell_y];\n")
                .append("                float c011 = local_corners[(tx * 5 + (tz + 1)) * 49 + cell_y + 1];\n")
                .append("                float c_min = fmin(fmin(c000, c111), fmin(c100, c011));\n")
                .append("                int oct_top = base_by + (oct_y << 2) + 3, oct_bot = base_by + (oct_y << 2);\n")
                .append("                int near_surf = (oct_top >= min_sy - 3 && oct_bot <= max(max_sy + 1, sea_level));\n")
                .append("                int deep_cave = (s >= 1 && oct_top < min_sy - 5 && oct_bot > -58 && c_min < -0.12890625f);\n")
                .append("                int beard_wake = sec_has_beard ? box_intersects_beard(base_bx + (tx << 2), oct_bot, base_bz + (tz << 2), base_bx + (tx << 2) + 3, oct_top, base_bz + (tz << 2) + 3, beard_pieces_i32, b_start, b_count) : 0;\n")
                .append("                if (near_surf || deep_cave || beard_wake) mask |= (1UL << oct);\n")
                .append("            }\n")
                .append("            int act_cnt = (int)popcount(mask);\n")
                .append("            active_octs += act_cnt; skipped_octs += (64 - act_cnt);\n")
                .append("            int dom = (base_by < 0) ? 2 : ((top_by < min_sy) ? 1 : 0);\n")
                .append("            int seam_cnt = 0; ulong seam_oct_mask = 0UL;\n")
                .append("            if (base_by <= max_sy) {\n")
                .append("                for (int ord = 0; ord < 12; ord++) {\n")
                .append("                    int dst = selectUndergroundDecoratorState(s, ord, dom_biome);\n")
                .append("                    if (dst == 0) continue;\n")
                .append("                    int sh3 = computeVanillaAnchoredOreStartH3(cpos.x, cpos.y, s, 100 + ord * 37, world_seed);\n")
                .append("                    int oidx = (sh3 >> 6) & 63;\n")
                .append("                    int disc = (ord == 5 || ord == 6) ? 10 : ((ord == 3) ? 5 : 0);\n")
                .append("                    if (evaluateOctantDiscardExposure((long)mask, oidx, disc, sh3) == 0) continue;\n")
                .append("                    int slen = (ord >= 7) ? 16 : 8;\n")
                .append("                    int eh3 = min(4096, sh3 + slen);\n")
                .append("                    seam_start[seam_cnt] = sh3; seam_end[seam_cnt] = eh3; seam_state[seam_cnt] = dst; seam_cnt++;\n")
                .append("                    seam_oct_mask |= (1UL << oidx) | (1UL << (((eh3 - 1) >> 6) & 63));\n")
                .append("                }\n")
                .append("            }\n")
                .append("            int non_dom_runs = 0, sec_hdr = run_cursor; ulong occ_mask = 0UL;\n")
                .append("            if (sec_hdr + 4 <= 4096) {\n")
                .append("                int wp = sec_hdr + 4, cur_start = -1, cur_len = 0, cur_st = dom;\n")
                .append("                for (int oct = 0; oct < 64; oct++) {\n")
                .append("                    int byzx = h3ToYzx(oct << 6);\n")
                .append("                    int ox = byzx & 12, oz = (byzx >> 4) & 12, oy = (byzx >> 8) & 12;\n")
                .append("                    int oct_act = (int)((mask >> oct) & 1UL);\n")
                .append("                    int has_sm = (int)((seam_oct_mask >> oct) & 1UL);\n")
                .append("                    if (!oct_act && !has_sm) {\n")
                .append("                        int wy = base_by + oy, syc = (int)local_surf_y[oz * 16 + ox];\n")
                .append("                        int ust = (wy > max(syc, sea_level)) ? 0 : ((wy <= syc) ? ((wy < 0) ? 2 : 1) : 5);\n")
                .append("                        if (ust == dom) {\n")
                .append("                            if (cur_len > 0) {\n")
                .append("                                if (wp + 4 <= 4096) {\n")
                .append("                                    slot[wp] = (uchar)(cur_start & 255); slot[wp + 1] = (uchar)((cur_start >> 8) & 255);\n")
                .append("                                    slot[wp + 2] = (uchar)(cur_len - 1); slot[wp + 3] = (uchar)cur_st; wp += 4; non_dom_runs++;\n")
                .append("                                    if (cur_st >= 6) total_dec_runs++;\n")
                .append("                                    for (int ro = cur_start >> 6; ro <= ((cur_start + cur_len - 1) >> 6); ro++) occ_mask |= (1UL << ro);\n")
                .append("                                }\n")
                .append("                                cur_len = 0; cur_st = dom;\n")
                .append("                            }\n")
                .append("                            continue;\n")
                .append("                        }\n")
                .append("                        for (int loc = 0; loc < 64; loc++) {\n")
                .append("                            int h3 = (oct << 6) | loc;\n")
                .append("                            if (cur_len > 0 && cur_st == ust && cur_len < 256 && cur_start + cur_len == h3) cur_len++;\n")
                .append("                            else {\n")
                .append("                                if (cur_len > 0 && wp + 4 <= 4096) {\n")
                .append("                                    slot[wp] = (uchar)(cur_start & 255); slot[wp + 1] = (uchar)((cur_start >> 8) & 255);\n")
                .append("                                    slot[wp + 2] = (uchar)(cur_len - 1); slot[wp + 3] = (uchar)cur_st; wp += 4; non_dom_runs++;\n")
                .append("                                    if (cur_st >= 6) total_dec_runs++;\n")
                .append("                                    for (int ro = cur_start >> 6; ro <= ((cur_start + cur_len - 1) >> 6); ro++) occ_mask |= (1UL << ro);\n")
                .append("                                }\n")
                .append("                                cur_start = h3; cur_len = 1; cur_st = ust;\n")
                .append("                            }\n")
                .append("                        }\n")
                .append("                        continue;\n")
                .append("                    }\n")
                .append("                    int tx = ox >> 2, tz = oz >> 2, cell_y = (s << 1) + (oy >> 3);\n")
                .append("                    float y_f0 = ((oy & 7) == 0) ? 0.0f : 0.5f;\n")
                .append("                    int tbiome = (int)local_biomes[s * 16 + (tz << 2 | tx)] & 7;\n")
                .append("                    for (int loc = 0; loc < 64; loc++) {\n")
                .append("                        int h3 = (oct << 6) | loc, st;\n")
                .append("                        if (!oct_act) {\n")
                .append("                            int wy = base_by + oy, syc = (int)local_surf_y[oz * 16 + ox];\n")
                .append("                            st = (wy > max(syc, sea_level)) ? 0 : ((wy <= syc) ? ((wy < 0) ? 2 : 1) : 5);\n")
                .append("                        } else {\n")
                .append("                            int yzx = h3ToYzx(h3);\n")
                .append("                            int lx = yzx & 15, lz = (yzx >> 4) & 15, ly = (yzx >> 8) & 15;\n")
                .append("                            int wy = base_by + ly, sy = (int)local_surf_y[lz * 16 + lx];\n")
                .append("                            float fx = (float)(lx & 3) * 0.25f, fz = (float)(lz & 3) * 0.25f, fy = y_f0 + (float)(ly & 3) * 0.125f;\n")
                .append("                            int c00 = (tx * 5 + tz) * 49 + cell_y, c10 = ((tx + 1) * 5 + tz) * 49 + cell_y;\n")
                .append("                            int c01 = (tx * 5 + (tz + 1)) * 49 + cell_y, c11 = ((tx + 1) * 5 + (tz + 1)) * 49 + cell_y;\n")
                .append("                            float d0 = (local_corners[c00] * (1.0f - fx) + local_corners[c10] * fx) * (1.0f - fz)\n")
                .append("                                     + (local_corners[c01] * (1.0f - fx) + local_corners[c11] * fx) * fz;\n")
                .append("                            float d1 = (local_corners[c00 + 1] * (1.0f - fx) + local_corners[c10 + 1] * fx) * (1.0f - fz)\n")
                .append("                                     + (local_corners[c01 + 1] * (1.0f - fx) + local_corners[c11 + 1] * fx) * fz;\n")
                .append("                            float raw_c = d0 + (d1 - d0) * fy;\n")
                .append("                            float dens = (wy < sy - 5 && wy > -58 && raw_c < -0.1328125f) ? (raw_c + 0.1328125f) : (((float)(sy - wy) * 0.16f) + ((wy < sy - 4) ? (raw_c * 0.30f) : 0.08f));\n")
                .append("                            if (sec_has_beard) dens += compute_voxel_beard_delta_f32(base_bx + lx, wy, base_bz + lz, beard_pieces_i32, b_start, b_count);\n")
                .append("                            if (dens > 0.0f) st = selectSurfaceStrataBlock(tbiome, wy, sy, sea_level);\n")
                .append("                            else if (wy <= sea_level && wy > sy) st = 5;\n")
                .append("                            else if (wy < -54) st = 19;\n")
                .append("                            else st = 0;\n")
                .append("                            if (st == 0 && wy == sy + 1 && sy >= sea_level) {\n")
                .append("                                int th = computeVanillaAnchoredOreStartH3(cpos.x, cpos.y, s, (lz << 4) | lx, world_seed);\n")
                .append("                                st = selectSurfaceDecoratorBlock(tbiome, sy, sea_level, th);\n")
                .append("                            } else if (st == 5 && wy == sy + 1 && sy < sea_level - 1) {\n")
                .append("                                int th = computeVanillaAnchoredOreStartH3(cpos.x, cpos.y, s, 512 + ((lz << 4) | lx), world_seed);\n")
                .append("                                int aq = selectSurfaceDecoratorBlock(tbiome, sy, sea_level, th);\n")
                .append("                                if (aq != 0) st = aq;\n")
                .append("                            }\n")
                .append("                        }\n")
                .append("                        if ((st == 1 || st == 2) && has_sm) {\n")
                .append("                            for (int m = 0; m < seam_cnt; m++) {\n")
                .append("                                if (h3 >= seam_start[m] && h3 < seam_end[m]) st = seam_state[m];\n")
                .append("                            }\n")
                .append("                        }\n")
                .append("                        if (st == dom) {\n")
                .append("                            if (cur_len > 0) {\n")
                .append("                                if (wp + 4 <= 4096) {\n")
                .append("                                    slot[wp] = (uchar)(cur_start & 255); slot[wp + 1] = (uchar)((cur_start >> 8) & 255);\n")
                .append("                                    slot[wp + 2] = (uchar)(cur_len - 1); slot[wp + 3] = (uchar)cur_st; wp += 4; non_dom_runs++;\n")
                .append("                                    if (cur_st >= 6) total_dec_runs++;\n")
                .append("                                    for (int ro = cur_start >> 6; ro <= ((cur_start + cur_len - 1) >> 6); ro++) occ_mask |= (1UL << ro);\n")
                .append("                                }\n")
                .append("                                cur_len = 0; cur_st = dom;\n")
                .append("                            }\n")
                .append("                        } else if (cur_len > 0 && cur_st == st && cur_len < 256 && cur_start + cur_len == h3) cur_len++;\n")
                .append("                        else {\n")
                .append("                            if (cur_len > 0 && wp + 4 <= 4096) {\n")
                .append("                                slot[wp] = (uchar)(cur_start & 255); slot[wp + 1] = (uchar)((cur_start >> 8) & 255);\n")
                .append("                                slot[wp + 2] = (uchar)(cur_len - 1); slot[wp + 3] = (uchar)cur_st; wp += 4; non_dom_runs++;\n")
                .append("                                if (cur_st >= 6) total_dec_runs++;\n")
                .append("                                for (int ro = cur_start >> 6; ro <= ((cur_start + cur_len - 1) >> 6); ro++) occ_mask |= (1UL << ro);\n")
                .append("                            }\n")
                .append("                            cur_start = h3; cur_len = 1; cur_st = st;\n")
                .append("                        }\n")
                .append("                    }\n")
                .append("                }\n")
                .append("                if (cur_len > 0 && wp + 4 <= 4096) {\n")
                .append("                    slot[wp] = (uchar)(cur_start & 255); slot[wp + 1] = (uchar)((cur_start >> 8) & 255);\n")
                .append("                    slot[wp + 2] = (uchar)(cur_len - 1); slot[wp + 3] = (uchar)cur_st; wp += 4; non_dom_runs++;\n")
                .append("                    if (cur_st >= 6) total_dec_runs++;\n")
                .append("                    for (int ro = cur_start >> 6; ro <= ((cur_start + cur_len - 1) >> 6); ro++) occ_mask |= (1UL << ro);\n")
                .append("                }\n")
                .append("                if (non_dom_runs == 0) {\n")
                .append("                    int sky2 = (base_by > max_sy) ? 1 : 0;\n")
                .append("                    slot_sec_off[s] = (ushort)(0x8000u | (dom & 0x7FF));\n")
                .append("                    slot_masks[s] = 0UL;\n")
                .append("                    slot[1736 + s] = (uchar)toGray4(sky2 != 0 ? 15 : 0);\n")
                .append("                    uniform_secs++;\n")
                .append("                } else {\n")
                .append("                    uchar gray = (uchar)toGray4((top_by >= min_sy) ? 15 : 0);\n")
                .append("                    slot[sec_hdr] = (uchar)dom; slot[sec_hdr + 1] = gray;\n")
                .append("                    slot[sec_hdr + 2] = (uchar)(non_dom_runs & 255); slot[sec_hdr + 3] = (uchar)((non_dom_runs >> 8) & 255);\n")
                .append("                    slot_sec_off[s] = (ushort)sec_hdr; slot_masks[s] = occ_mask;\n")
                .append("                    slot[1736 + s] = gray; run_cursor = wp;\n")
                .append("                }\n")
                .append("            } else {\n")
                .append("                int sky2 = (base_by > max_sy) ? 1 : 0;\n")
                .append("                slot_sec_off[s] = (ushort)(0x8000u | (dom & 0x7FF));\n")
                .append("                slot_masks[s] = 0UL; slot[1736 + s] = (uchar)toGray4(sky2 != 0 ? 15 : 0); uniform_secs++;\n")
                .append("            }\n")
                .append("        }\n")
                .append("        int probe_anch = computeChunkTreeAnchorPacked(cpos.x, cpos.y, 1, 70, sea_level, world_seed);\n")
                .append("        int tree_anchor = 0;\n")
                .append("        if ((probe_anch & 0x40000000) != 0) {\n")
                .append("            int ax = probe_anch & 15, az = (probe_anch >> 4) & 15;\n")
                .append("            int ay = (int)local_surf_y[(az << 4) | ax];\n")
                .append("            int surf_sec = clamp((ay + 64) >> 4, 0, 23);\n")
                .append("            int a_tile = ((az >> 2) << 2) | (ax >> 2);\n")
                .append("            int a_biome = (ay <= sea_level) ? 6 : ((int)local_biomes[surf_sec * 16 + a_tile] & 7);\n")
                .append("            tree_anchor = computeChunkTreeAnchorPacked(cpos.x, cpos.y, a_biome, ay, sea_level, world_seed);\n")
                .append("        }\n")
                .append("        uint fnv = 0x811C9DC5u;\n")
                .append("        for (int bi = 32; bi < run_cursor; bi++) { fnv ^= (uint)slot[bi]; fnv *= 0x01000193u; }\n")
                .append("        __global uint* hdr32 = (__global uint*)slot;\n")
                .append("        __global ushort* hdr16 = (__global ushort*)slot;\n")
                .append("        hdr32[0] = 0x4C435334u;\n")
                .append("        hdr32[1] = (uint)cpos.x;\n")
                .append("        hdr32[2] = (uint)cpos.y;\n")
                .append("        hdr32[3] = (uint)run_cursor;\n")
                .append("        hdr32[4] = fnv;\n")
                .append("        hdr16[10] = (ushort)uniform_secs;\n")
                .append("        hdr16[11] = (ushort)active_octs;\n")
                .append("        hdr16[12] = (ushort)skipped_octs;\n")
                .append("        hdr16[13] = (ushort)total_dec_runs;\n")
                .append("        hdr32[7] = (uint)tree_anchor;\n")
                .append("    }\n")
                .append("}\n");
    }

    private static void emitStatements(
            StringBuilder sb,
            ConceptFunction fn,
            List<ConceptStmt> stmts,
            EmitOptions options,
            Q32LoweringPlan q32Plan,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName,
            int indentLevel) {
        String indent = "    ".repeat(indentLevel);
        boolean q32Mode = options.gpuExactArithmeticMode().usesInt64Q32();
        for (ConceptStmt stmt : stmts) {
            if (stmt instanceof ConceptStmt.VarDecl decl) {
                boolean varQ32 = q32Mode && decl.type() == ConceptType.F64 && q32Plan.isQ32Var(fn, decl.name());
                sb.append(indent)
                        .append(varQ32 ? "long" : mapType(decl.type(), options))
                        .append(' ')
                        .append(decl.name());
                if (decl.initializer() != null) {
                    sb.append(" = ").append(emitExpr(
                            decl.initializer(), fn, options, q32Plan, fnByKey, fnByName, varQ32));
                }
                sb.append(";\n");
            } else if (stmt instanceof ConceptStmt.Assign assign) {
                boolean varQ32 = q32Mode && assign.type() == ConceptType.F64 && q32Plan.isQ32Var(fn, assign.name());
                sb.append(indent)
                        .append(assign.name())
                        .append(" = ")
                        .append(emitExpr(assign.value(), fn, options, q32Plan, fnByKey, fnByName, varQ32))
                        .append(";\n");
            } else if (stmt instanceof ConceptStmt.If ifStmt) {
                sb.append(indent)
                        .append("if (")
                        .append(emitExpr(ifStmt.condition(), fn, options, q32Plan, fnByKey, fnByName, false))
                        .append(") {\n");
                emitStatements(sb, fn, ifStmt.thenBody(), options, q32Plan, fnByKey, fnByName, indentLevel + 1);
                if (!ifStmt.elseBody().isEmpty()) {
                    sb.append(indent).append("} else {\n");
                    emitStatements(sb, fn, ifStmt.elseBody(), options, q32Plan, fnByKey, fnByName, indentLevel + 1);
                }
                sb.append(indent).append("}\n");
            } else if (stmt instanceof ConceptStmt.While whileStmt) {
                sb.append(indent)
                        .append("while (")
                        .append(emitExpr(whileStmt.condition(), fn, options, q32Plan, fnByKey, fnByName, false))
                        .append(") {\n");
                emitStatements(sb, fn, whileStmt.body(), options, q32Plan, fnByKey, fnByName, indentLevel + 1);
                sb.append(indent).append("}\n");
            } else if (stmt instanceof ConceptStmt.Return ret) {
                sb.append(indent).append("return");
                if (ret.value() != null) {
                    boolean retQ32 = q32Mode && fn.returnType() == ConceptType.F64 && q32Plan.isQ32Return(fn);
                    sb.append(' ').append(emitExpr(
                            ret.value(), fn, options, q32Plan, fnByKey, fnByName, retQ32));
                }
                sb.append(";\n");
            } else if (stmt instanceof ConceptStmt.StoreBuffer3D store) {
                sb.append(indent)
                        .append(store.bufferName())
                        .append('[')
                        .append(emitExpr(store.linearIndex(), fn, options, q32Plan, fnByKey, fnByName, false))
                        .append("] = ")
                        .append(emitExpr(store.value(), fn, options, q32Plan, fnByKey, fnByName, false))
                        .append(";\n");
            }
        }
    }

    private static String emitExpr(
            ConceptExpr expr,
            ConceptFunction fn,
            EmitOptions options,
            Q32LoweringPlan q32Plan,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName,
            boolean expectQ32) {
        GpuExactArithmeticMode mode = options.gpuExactArithmeticMode();
        boolean q32Mode = mode.usesInt64Q32();

        if (expr instanceof ConceptExpr.Var v) {
            if (q32Mode) {
                boolean varIsQ32 = v.type() == ConceptType.F64 && q32Plan.isQ32Var(fn, v.name());
                if (expectQ32) {
                    if (varIsQ32) {
                        return v.name();
                    }
                    return convertToQ32(v.name(), v.type());
                }
                if (varIsQ32) {
                    return "q32_to_f32(" + v.name() + ")";
                }
            }
            return v.name();
        }
        if (expr instanceof ConceptExpr.IntConst c) {
            if (expectQ32) {
                return formatLong(((long) c.value()) << 32);
            }
            return formatInt(c.value());
        }
        if (expr instanceof ConceptExpr.LongConst c) {
            if (expectQ32) {
                return formatLong(c.value() << 32);
            }
            return formatLong(c.value());
        }
        if (expr instanceof ConceptExpr.FloatConst c) {
            if (expectQ32) {
                return formatLong(ConceptOptimizerPass.doubleToQ32(c.value()));
            }
            return formatFloat(c.value());
        }
        if (expr instanceof ConceptExpr.DoubleConst c) {
            if (expectQ32) {
                return formatLong(ConceptOptimizerPass.doubleToQ32(c.value()));
            }
            if (!mode.usesNativeFp64()) {
                return formatFloat((float) c.value());
            }
            return formatDouble(c.value());
        }
        if (expr instanceof ConceptExpr.BoolConst c) {
            return c.value() ? "1" : "0";
        }
        if (expr instanceof ConceptExpr.UnaryOp u) {
            if (q32Mode && u.op() == UnaryOperator.NEG && u.type() == ConceptType.F64) {
                if (expectQ32) {
                    return "q32_neg(" + emitExpr(u.operand(), fn, options, q32Plan, fnByKey, fnByName, true) + ")";
                }
                if (q32Plan.isQ32Expr(fn, u.operand())) {
                    return "q32_to_f32(q32_neg("
                            + emitExpr(u.operand(), fn, options, q32Plan, fnByKey, fnByName, true) + "))";
                }
            }
            String emitted = "(" + u.op().symbol()
                    + emitExpr(u.operand(), fn, options, q32Plan, fnByKey, fnByName, false) + ")";
            return expectQ32 ? convertToQ32(emitted, u.type()) : emitted;
        }
        if (expr instanceof ConceptExpr.Cast cast) {
            if (q32Mode) {
                if ((cast.targetType() == ConceptType.I32 || cast.targetType() == ConceptType.I64)
                        && cast.operand().type() == ConceptType.F64) {
                    if (cast.operand() instanceof ConceptExpr.MathIntrinsic m
                            && m.kind() == MathIntrinsicKind.FLOOR
                            && !m.arguments().isEmpty()
                            && m.arguments().get(0).type() == ConceptType.F64) {
                        String argQ32 = emitExpr(m.arguments().get(0), fn, options, q32Plan, fnByKey, fnByName, true);
                        String floored = (cast.targetType() == ConceptType.I32 ? "q32_floor_i32(" : "q32_floor_i64(")
                                + argQ32 + ")";
                        return expectQ32 ? convertToQ32(floored, cast.targetType()) : floored;
                    }
                    String opQ32 = emitExpr(cast.operand(), fn, options, q32Plan, fnByKey, fnByName, true);
                    String trunc = (cast.targetType() == ConceptType.I32 ? "q32_trunc_i32(" : "q32_trunc_i64(")
                            + opQ32 + ")";
                    return expectQ32 ? convertToQ32(trunc, cast.targetType()) : trunc;
                }
                if (expectQ32 && cast.targetType() == ConceptType.F64) {
                    if (cast.operand().type() == ConceptType.F64) {
                        return emitExpr(cast.operand(), fn, options, q32Plan, fnByKey, fnByName, true);
                    }
                    String opStr = emitExpr(cast.operand(), fn, options, q32Plan, fnByKey, fnByName, false);
                    return convertToQ32(opStr, cast.operand().type());
                }
                if (!expectQ32 && cast.targetType() == ConceptType.F32
                        && cast.operand().type() == ConceptType.F64
                        && q32Plan.isQ32Expr(fn, cast.operand())) {
                    return "q32_to_f32(" + emitExpr(cast.operand(), fn, options, q32Plan, fnByKey, fnByName, true) + ")";
                }
            }
            String emitted = "((" + mapType(cast.targetType(), options) + ")("
                    + emitExpr(cast.operand(), fn, options, q32Plan, fnByKey, fnByName, false) + "))";
            return expectQ32 ? convertToQ32(emitted, cast.targetType()) : emitted;
        }
        if (expr instanceof ConceptExpr.Ternary tern) {
            boolean branchQ32 = q32Mode && expectQ32 && tern.type() == ConceptType.F64;
            String emitted = "((" + emitExpr(tern.condition(), fn, options, q32Plan, fnByKey, fnByName, false) + ") ? ("
                    + emitExpr(tern.thenExpr(), fn, options, q32Plan, fnByKey, fnByName, branchQ32) + ") : ("
                    + emitExpr(tern.elseExpr(), fn, options, q32Plan, fnByKey, fnByName, branchQ32) + "))";
            return (expectQ32 && !branchQ32) ? convertToQ32(emitted, tern.type()) : emitted;
        }
        if (expr instanceof ConceptExpr.LutLoad load) {
            String idxStr = emitExpr(load.index(), fn, options, q32Plan, fnByKey, fnByName, false);
            EmitScope scope = ACTIVE_EMIT_SCOPE.get();
            String prefix = scope != null ? scope.symbolPrefix() : "";
            ConstantLutAllocator alloc = scope != null ? scope.lutAllocator() : null;
            if (q32Mode && expectQ32) {
                if (load.elementType() == ConceptType.F64) {
                    String q32Sym = q32Plan.q32LutSymbol(load.lutSymbol());
                    if (q32Sym != null) {
                        return alloc != null
                                ? alloc.emitLutRead(q32Sym, idxStr, prefix)
                                : (prefix + q32Sym + "[" + idxStr + "]");
                    }
                }
                String rawRead = alloc != null
                        ? alloc.emitLutRead(load.lutSymbol(), idxStr, prefix)
                        : (prefix + load.lutSymbol() + "[" + idxStr + "]");
                return convertToQ32(rawRead, load.elementType());
            }
            return alloc != null
                    ? alloc.emitLutRead(load.lutSymbol(), idxStr, prefix)
                    : (prefix + load.lutSymbol() + "[" + idxStr + "]");
        }
        if (expr instanceof ConceptExpr.MathIntrinsic math) {
            if (q32Mode) {
                String q32Math = tryEmitQ32MathIntrinsic(math, fn, options, q32Plan, fnByKey, fnByName, expectQ32);
                if (q32Math != null) {
                    return q32Math;
                }
            }
            List<String> args = new ArrayList<>(math.arguments().size());
            for (ConceptExpr a : math.arguments()) {
                args.add(emitExpr(a, fn, options, q32Plan, fnByKey, fnByName, false));
            }
            boolean fp = math.type().isFloatingPoint();
            String fnName = switch (math.kind()) {
                case FLOOR -> "floor";
                case CEIL -> "ceil";
                case ROUND -> "rint";
                case SQRT -> "sqrt";
                case ABS -> fp ? "fabs" : "abs";
                case SIN -> "sin";
                case COS -> "cos";
                case TAN -> "tan";
                case ATAN2 -> "atan2";
                case EXP -> "exp";
                case LOG -> "log";
                case POW -> "pow";
                case MIN -> fp ? "fmin" : "min";
                case MAX -> fp ? "fmax" : "max";
                case FMA -> "fma";
                case IS_FINITE -> "isfinite";
                case IS_NAN -> "isnan";
                case POPCOUNT -> "popcount";
                case CLZ -> "clz";
                case CTZ -> "ctz";
            };
            String emitted;
            if (math.kind() == MathIntrinsicKind.POPCOUNT
                    || math.kind() == MathIntrinsicKind.CLZ
                    || math.kind() == MathIntrinsicKind.CTZ) {
                emitted = "((int)" + fnName + "(" + String.join(", ", args) + "))";
            } else if (math.kind() == MathIntrinsicKind.ROUND
                    && (math.type() == ConceptType.I64 || math.type() == ConceptType.I32)
                    && args.size() == 1) {
                boolean nativeFp64 = options.gpuExactArithmeticMode().usesNativeFp64();
                String halfLit = (math.type() == ConceptType.I64 && nativeFp64) ? "0.5" : "0.5f";
                String castPrefix = math.type() == ConceptType.I64 ? "(long)" : "(int)";
                emitted = "(" + castPrefix + "floor((" + args.get(0) + ") + " + halfLit + "))";
            } else {
                emitted = fnName + "(" + String.join(", ", args) + ")";
            }
            return expectQ32 ? convertToQ32(emitted, math.type()) : emitted;
        }
        if (expr instanceof ConceptExpr.Call call) {
            ConceptFunction targetFn = resolveFunction(call, fnByKey, fnByName);
            EmitScope scope = ACTIVE_EMIT_SCOPE.get();
            List<String> args = new ArrayList<>(call.arguments().size());
            for (int i = 0; i < call.arguments().size(); i++) {
                boolean argQ32 = q32Mode && targetFn != null && q32Plan.isQ32Param(targetFn, i);
                args.add(emitExpr(call.arguments().get(i), fn, options, q32Plan, fnByKey, fnByName, argQ32));
            }
            String prefix = (scope != null && targetFn != null) ? scope.symbolPrefix() : "";
            boolean useMemoWrapper = false;
            if (scope != null
                    && scope.memoElemType() != null
                    && targetFn != null
                    && !q32Mode
                    && fn.parameters().size() == 3
                    && fn.parameters().get(0).type() == ConceptType.I32
                    && fn.parameters().get(1).type() == ConceptType.I32
                    && fn.parameters().get(2).type() == ConceptType.I32
                    && call.arguments().size() == 3) {
                Integer memoSlot = scope.memoSlotByFnName().get(targetFn.name());
                if (memoSlot != null
                        && call.arguments().get(0) instanceof ConceptExpr.Var v0
                        && v0.name().equals(fn.parameters().get(0).name())
                        && call.arguments().get(2) instanceof ConceptExpr.Var v2
                        && v2.name().equals(fn.parameters().get(2).name())) {
                    boolean is2dSlot = (scope.memo2dKeepMask() & (1 << memoSlot)) != 0;
                    boolean yMatches = call.arguments().get(1) instanceof ConceptExpr.Var v1
                            && v1.name().equals(fn.parameters().get(1).name());
                    if (is2dSlot || yMatches) {
                        useMemoWrapper = true;
                    }
                }
            }
            String targetName = targetFn != null
                    ? ((useMemoWrapper ? "memo_" : "") + prefix + targetFn.name())
                    : call.methodName();
            if (scope != null && targetFn != null && scope.passGlobalSpillParam()) {
                args.add(scope.lutAllocator().globalSpillBufferName);
            }
            if (scope != null && targetFn != null && scope.memoElemType() != null) {
                args.add("memo_mask");
                args.add("memo_val");
            }
            String callStr = targetName + "(" + String.join(", ", args) + ")";
            if (q32Mode) {
                boolean returnsQ32 = targetFn != null && q32Plan.isQ32Return(targetFn);
                if (expectQ32 && !returnsQ32) {
                    return convertToQ32(callStr, call.returnType());
                }
                if (!expectQ32 && returnsQ32) {
                    return "q32_to_f32(" + callStr + ")";
                }
            }
            return callStr;
        }
        if (expr instanceof ConceptExpr.BinaryOp bin) {
            if (q32Mode) {
                if (bin.op().isComparison() && q32Plan.shouldPromoteComparisonToQ32(fn, bin)) {
                    String lq = emitExpr(bin.left(), fn, options, q32Plan, fnByKey, fnByName, true);
                    String rq = emitExpr(bin.right(), fn, options, q32Plan, fnByKey, fnByName, true);
                    return "(" + lq + " " + bin.op().symbol() + " " + rq + ")";
                }
                if (expectQ32 && bin.type() == ConceptType.F64
                        && (bin.op() == BinaryOperator.ADD
                                || bin.op() == BinaryOperator.SUB
                                || bin.op() == BinaryOperator.MUL
                                || bin.op() == BinaryOperator.DIV
                                || bin.op() == BinaryOperator.REM)) {
                    return emitQ32BinaryOp(bin, fn, options, q32Plan, fnByKey, fnByName);
                }
                if (!expectQ32 && q32Plan.shouldPromoteAddSubToQ32(fn, bin)) {
                    return "q32_to_f32(" + emitQ32BinaryOp(bin, fn, options, q32Plan, fnByKey, fnByName) + ")";
                }
            }
            String l = emitExpr(bin.left(), fn, options, q32Plan, fnByKey, fnByName, false);
            String r = emitExpr(bin.right(), fn, options, q32Plan, fnByKey, fnByName, false);
            if (bin.op() == BinaryOperator.USHR) {
                if (bin.type() == ConceptType.I64) {
                    return "((long)(((ulong)(" + l + ")) >> (" + r + ")))";
                }
                return "((int)(((uint)(" + l + ")) >> (" + r + ")))";
            }
            String emitted;
            if (bin.op() == BinaryOperator.REM && bin.type().isFloatingPoint()) {
                emitted = "fmod(" + l + ", " + r + ")";
            } else {
                emitted = "(" + l + " " + bin.op().symbol() + " " + r + ")";
            }
            return expectQ32 ? convertToQ32(emitted, bin.type()) : emitted;
        }
        throw new IllegalStateException("Unsupported ConceptExpr in OpenCL emitter: " + expr);
    }

    private static String tryEmitQ32MathIntrinsic(
            ConceptExpr.MathIntrinsic math,
            ConceptFunction fn,
            EmitOptions options,
            Q32LoweringPlan q32Plan,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName,
            boolean expectQ32) {
        List<ConceptExpr> args = math.arguments();
        if (math.kind() == MathIntrinsicKind.FLOOR && !args.isEmpty() && args.get(0).type() == ConceptType.F64) {
            String argQ32 = emitExpr(args.get(0), fn, options, q32Plan, fnByKey, fnByName, true);
            return expectQ32 ? "q32_floor(" + argQ32 + ")" : "q32_to_f32(q32_floor(" + argQ32 + "))";
        }
        if (math.kind() == MathIntrinsicKind.CEIL && !args.isEmpty() && args.get(0).type() == ConceptType.F64) {
            String argQ32 = emitExpr(args.get(0), fn, options, q32Plan, fnByKey, fnByName, true);
            return expectQ32 ? "q32_ceil(" + argQ32 + ")" : "q32_to_f32(q32_ceil(" + argQ32 + "))";
        }
        if (math.kind() == MathIntrinsicKind.ROUND && !args.isEmpty() && args.get(0).type() == ConceptType.F64) {
            String argQ32 = emitExpr(args.get(0), fn, options, q32Plan, fnByKey, fnByName, true);
            String shifted = "q32_add(" + argQ32 + ", 2147483648L)";
            if (math.type() == ConceptType.I64) {
                return "q32_floor_i64(" + shifted + ")";
            }
            if (math.type() == ConceptType.I32) {
                return "q32_floor_i32(" + shifted + ")";
            }
            return expectQ32 ? "q32_floor(" + shifted + ")" : "q32_to_f32(q32_floor(" + shifted + "))";
        }
        if (math.kind() == MathIntrinsicKind.IS_FINITE && !args.isEmpty() && q32Plan.isQ32Expr(fn, args.get(0))) {
            return "1";
        }
        if (math.kind() == MathIntrinsicKind.IS_NAN && !args.isEmpty() && q32Plan.isQ32Expr(fn, args.get(0))) {
            return "0";
        }
        if (expectQ32 && math.type() == ConceptType.F64) {
            return switch (math.kind()) {
                case ABS -> "q32_abs(" + emitExpr(args.get(0), fn, options, q32Plan, fnByKey, fnByName, true) + ")";
                case MIN -> "min(" + emitExpr(args.get(0), fn, options, q32Plan, fnByKey, fnByName, true)
                        + ", " + emitExpr(args.get(1), fn, options, q32Plan, fnByKey, fnByName, true) + ")";
                case MAX -> "max(" + emitExpr(args.get(0), fn, options, q32Plan, fnByKey, fnByName, true)
                        + ", " + emitExpr(args.get(1), fn, options, q32Plan, fnByKey, fnByName, true) + ")";
                case FMA -> "q32_add(q32_mul("
                        + emitExpr(args.get(0), fn, options, q32Plan, fnByKey, fnByName, true) + ", "
                        + emitExpr(args.get(1), fn, options, q32Plan, fnByKey, fnByName, true) + "), "
                        + emitExpr(args.get(2), fn, options, q32Plan, fnByKey, fnByName, true) + ")";
                default -> null;
            };
        }
        return null;
    }

    private static String emitQ32BinaryOp(
            ConceptExpr.BinaryOp bin,
            ConceptFunction fn,
            EmitOptions options,
            Q32LoweringPlan q32Plan,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName) {
        String lq = emitExpr(bin.left(), fn, options, q32Plan, fnByKey, fnByName, true);
        if (bin.op() == BinaryOperator.DIV) {
            Double constDiv = extractNonZeroFiniteConst(bin.right());
            if (constDiv != null) {
                double inv = 1.0 / constDiv;
                double absInv = Math.abs(inv);
                if (Double.isFinite(inv) && absInv >= 0x1.0p-30 && absInv < 0x1.0p30) {
                    return "q32_mul(" + lq + ", " + formatLong(ConceptOptimizerPass.doubleToQ32(inv)) + ")";
                }
            }
            String rq = emitExpr(bin.right(), fn, options, q32Plan, fnByKey, fnByName, true);
            return "q32_div(" + lq + ", " + rq + ")";
        }
        String rq = emitExpr(bin.right(), fn, options, q32Plan, fnByKey, fnByName, true);
        return switch (bin.op()) {
            case ADD -> "q32_add(" + lq + ", " + rq + ")";
            case SUB -> "q32_sub(" + lq + ", " + rq + ")";
            case MUL -> "q32_mul(" + lq + ", " + rq + ")";
            case REM -> "q32_sub(" + lq + ", q32_mul(q32_floor(q32_div(" + lq + ", " + rq + ")), " + rq + "))";
            default -> "q32_add(" + lq + ", " + rq + ")";
        };
    }

    private static Double extractNonZeroFiniteConst(ConceptExpr expr) {
        if (expr instanceof ConceptExpr.DoubleConst d && Double.isFinite(d.value()) && d.value() != 0.0) {
            return d.value();
        }
        if (expr instanceof ConceptExpr.FloatConst f && Float.isFinite(f.value()) && f.value() != 0.0f) {
            return (double) f.value();
        }
        return null;
    }

    private static String convertToQ32(String exprStr, ConceptType type) {
        return switch (type) {
            case I32, BOOL -> "q32_from_i32(" + exprStr + ")";
            case I64 -> "q32_from_i64(" + exprStr + ")";
            default -> "q32_from_f32(" + exprStr + ")";
        };
    }

    private static ConceptFunction resolveFunction(
            ConceptExpr.Call call,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName) {
        ConceptFunction fn = fnByKey.get(call.targetKey());
        if (fn != null) {
            return fn;
        }
        return fnByName.get(call.methodName());
    }

    private static String mapReturnType(
            ConceptFunction fn,
            EmitOptions options,
            Q32LoweringPlan q32Plan) {
        if (options.gpuExactArithmeticMode().usesInt64Q32()
                && fn.returnType() == ConceptType.F64
                && q32Plan.isQ32Return(fn)) {
            return "long";
        }
        return mapType(fn.returnType(), options);
    }

    private static String mapParamType(
            ConceptFunction fn,
            ConceptParam param,
            int paramIndex,
            EmitOptions options,
            Q32LoweringPlan q32Plan) {
        if (options.gpuExactArithmeticMode().usesInt64Q32()
                && param.type() == ConceptType.F64
                && q32Plan.isQ32Param(fn, paramIndex)) {
            return "long";
        }
        return mapType(param.type(), options);
    }

    private static String mapExtraUniformType(
            ConceptFunction target,
            ConceptParam extra,
            EmitOptions options,
            Q32LoweringPlan q32Plan) {
        List<ConceptParam> params = target.parameters();
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i).name().equals(extra.name())) {
                return mapParamType(target, extra, i, options, q32Plan);
            }
        }
        return mapType(extra.type(), options);
    }

    private static String mapType(ConceptType type, EmitOptions options) {
        return switch (type) {
            case VOID -> "void";
            case BOOL, I32 -> "int";
            case I64 -> "long";
            case F32 -> "float";
            case F64 -> options.gpuExactArithmeticMode().usesNativeFp64()
                    ? "double"
                    : "float";
            default -> "long";
        };
    }

    private static String formatInt(int v) {
        if (v == Integer.MIN_VALUE) {
            return "(-2147483647 - 1)";
        }
        return Integer.toString(v);
    }

    private static String formatLong(long v) {
        if (v == Long.MIN_VALUE) {
            return "(-9223372036854775807L - 1L)";
        }
        return v + "L";
    }

    private static String formatDouble(double d) {
        if (Double.isNaN(d)) {
            return "NAN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "INFINITY" : "(-INFINITY)";
        }
        String s = Double.toString(d);
        if (!s.contains(".") && !s.contains("E") && !s.contains("e")) {
            return s + ".0";
        }
        return s;
    }

    private static String openClConstantArrayPrefix(String cType, String name, int length) {
        return "__constant " + cType + " " + name + "[" + length + "] = ";
    }

    private static String openClGlobalPtr(String cType, boolean isConst) {
        return isConst ? ("__global const " + cType + "*") : ("__global " + cType + "*");
    }

    private static String openClGlobalPtrParam(String cType, boolean isConst, String name) {
        return openClGlobalPtr(cType, isConst) + " " + name;
    }

    private static String openClGlobalPtrDecl(String cType, boolean isConst, String name, String initExpr) {
        return "    " + openClGlobalPtr(cType, isConst) + " " + name + " = " + initExpr + ";\n";
    }

    private static String openClKernelHeader(String workgroupAttr, String kernelName, String... params) {
        StringBuilder sb = new StringBuilder(256);
        if (workgroupAttr != null && !workgroupAttr.isEmpty()) {
            sb.append(workgroupAttr).append('\n');
        }
        sb.append("__kernel void ").append(kernelName).append("(\n");
        for (int i = 0; i < params.length; i++) {
            sb.append("    ").append(params[i]);
            if (i + 1 < params.length) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append(") {\n");
        return sb.toString();
    }

    private static String formatFloat(float f) {
        if (Float.isNaN(f)) {
            return "NAN";
        }
        if (Float.isInfinite(f)) {
            return f > 0 ? "INFINITY" : "(-INFINITY)";
        }
        String s = Float.toString(f);
        if (!s.contains(".") && !s.contains("E") && !s.contains("e")) {
            return s + ".0f";
        }
        return s + "f";
    }

    /**
     * Emits version- and dimension-agnostic OpenCL C99 device functions for lifted 7D
     * {@code Climate.RTree} BVH biome lookup ({@code eval_rtree_biome_ord}) and lifted
     * {@code SurfaceRules} VM execution ({@code eval_lifted_surface_rule}) with zero hardcoded
     * biome or block literals.
     */
      public static String emitCapabilityRTreeAndSurfaceHelpers() {
          return """
                  #pragma OPENCL FP_CONTRACT OFF

                  inline int mix_column_seed_i32(long seed, int x, int z) {
                      ulong h = (ulong)seed ^ ((ulong)((long)x) * 0x9E3779B97F4A7C15UL) ^ ((ulong)((long)z) * 0xBF58476D1CE4E5B9UL);
                      h = (h ^ (h >> 30)) * 0xBF58476D1CE4E5B9UL;
                      h = (h ^ (h >> 27)) * 0x94D049BB133111EBUL;
                      return (int)(h ^ (h >> 31));
                  }

                  inline int compute_smooth_surface_depth_i32(long world_seed, int bx, int bz, int col_hash) {
                      long s64 = world_seed ^ 0x243F6A8885A308D3L;
                      int gx6 = bx >> 6, gz6 = bz >> 6, fx6 = bx & 63, fz6 = bz & 63;
                      int c00 = mix_column_seed_i32(s64, gx6, gz6) & 0xFFF;
                      int c10 = mix_column_seed_i32(s64, gx6 + 1, gz6) & 0xFFF;
                      int c01 = mix_column_seed_i32(s64, gx6, gz6 + 1) & 0xFFF;
                      int c11 = mix_column_seed_i32(s64, gx6 + 1, gz6 + 1) & 0xFFF;
                      int nx0 = c00 * (64 - fx6) + c10 * fx6;
                      int nx1 = c01 * (64 - fx6) + c11 * fx6;
                      int b64 = (nx0 * (64 - fz6) + nx1 * fz6) >> 12;

                      long s32 = world_seed ^ 0x6A09E667F3BCC908L;
                      int gx5 = bx >> 5, gz5 = bz >> 5, fx5 = bx & 31, fz5 = bz & 31;
                      int d00 = mix_column_seed_i32(s32, gx5, gz5) & 0xFFF;
                      int d10 = mix_column_seed_i32(s32, gx5 + 1, gz5) & 0xFFF;
                      int d01 = mix_column_seed_i32(s32, gx5, gz5 + 1) & 0xFFF;
                      int d11 = mix_column_seed_i32(s32, gx5 + 1, gz5 + 1) & 0xFFF;
                      int mx0 = d00 * (32 - fx5) + d10 * fx5;
                      int mx1 = d01 * (32 - fx5) + d11 * fx5;
                      int b32 = (mx0 * (32 - fz5) + mx1 * fz5) >> 10;

                      int combined = b64 + b32;
                      if (combined < 220) {
                          return 0;
                      }
                      int jitter = (int)(((uint)col_hash >> 5) & 255u);
                      return 2 + (int)(((uint)(combined + jitter) * 3u) >> 13);
                  }

                  inline long rtree_axis_gap(int q, int lo, int hi) {
                      return (q < lo) ? ((long)lo - (long)q) : ((q > hi) ? ((long)q - (long)hi) : 0L);
                  }

                  inline long rtree_node_dist_sq7("""
                  + openClGlobalPtrParam("int", true, "nodes")
                  + """
                  , int base, int q0, int q1, int q2, int q3, int q4, int q5) {
                      long d0 = rtree_axis_gap(q0, nodes[base + 2], nodes[base + 9]);
                      long d1 = rtree_axis_gap(q1, nodes[base + 3], nodes[base + 10]);
                      long d2 = rtree_axis_gap(q2, nodes[base + 4], nodes[base + 11]);
                      long d3 = rtree_axis_gap(q3, nodes[base + 5], nodes[base + 12]);
                      long d4 = rtree_axis_gap(q4, nodes[base + 6], nodes[base + 13]);
                      long d5 = rtree_axis_gap(q5, nodes[base + 7], nodes[base + 14]);
                      long d6 = rtree_axis_gap(0,  nodes[base + 8], nodes[base + 15]);
                      return d0*d0 + d1*d1 + d2*d2 + d3*d3 + d4*d4 + d5*d5 + d6*d6;
                  }

                  inline int eval_rtree_biome_ord_hinted("""
                  + openClGlobalPtrParam("int", true, "rtree_nodes")
                  + """
                  , int node_count, int qt, int qh, int qc, int qe, int qd, int qw, int* io_hint_leaf) {
                      if (node_count <= 0) return 0;
                      long best_dist = 0x7FFFFFFFFFFFFFFFL;
                      int best_order = 0x7FFFFFFF;
                      int best_ord = 0;
                      int best_leaf = *io_hint_leaf;
                      if (best_leaf >= 0 && best_leaf < node_count) {
                          int hbase = best_leaf << 4;
                          int hw1 = rtree_nodes[hbase + 1];
                          if (hw1 <= 0) {
                              best_dist = rtree_node_dist_sq7(rtree_nodes, hbase, qt, qh, qc, qe, qd, qw);
                              best_order = -1 - hw1;
                              best_ord = rtree_nodes[hbase];
                              if (best_dist == 0L && best_order == 0) {
                                  return best_ord;
                              }
                          }
                      }
                      long root_dist = rtree_node_dist_sq7(rtree_nodes, 0, qt, qh, qc, qe, qd, qw);
                      if (root_dist > best_dist) {
                          return best_ord;
                      }
                      int stack_idx[32];
                      long stack_dist[32];
                      stack_idx[0] = 0;
                      stack_dist[0] = root_dist;
                      int sp = 1;
                      while (sp > 0) {
                          sp--;
                          int nidx = stack_idx[sp];
                          long ndist = stack_dist[sp];
                          if (ndist > best_dist || nidx < 0 || nidx >= node_count) continue;
                          int base = nidx << 4;
                          int w0 = rtree_nodes[base];
                          int w1 = rtree_nodes[base + 1];
                          if (w1 <= 0) {
                              int ord_rank = -1 - w1;
                              if (ndist < best_dist || (ndist == best_dist && ord_rank < best_order)) {
                                  best_dist = ndist;
                                  best_order = ord_rank;
                                  best_ord = w0;
                                  best_leaf = nidx;
                              }
                              continue;
                          }
                          int start_sp = sp;
                          for (int i = 0; i < w1; i++) {
                              int cidx = w0 + i;
                              if (cidx < 0 || cidx >= node_count) continue;
                              long cdist = rtree_node_dist_sq7(rtree_nodes, cidx << 4, qt, qh, qc, qe, qd, qw);
                              if (cdist <= best_dist && sp < 32) {
                                  stack_idx[sp] = cidx;
                                  stack_dist[sp] = cdist;
                                  sp++;
                              }
                          }
                          for (int i = start_sp + 1; i < sp; i++) {
                              int k_idx = stack_idx[i];
                              long k_dist = stack_dist[i];
                              int j = i - 1;
                              while (j >= start_sp && stack_dist[j] < k_dist) {
                                  stack_idx[j + 1] = stack_idx[j];
                                  stack_dist[j + 1] = stack_dist[j];
                                  j--;
                              }
                              stack_idx[j + 1] = k_idx;
                              stack_dist[j + 1] = k_dist;
                          }
                      }
                      *io_hint_leaf = best_leaf;
                      return best_ord;
                  }

                  inline int eval_rtree_biome_ord("""
                  + openClGlobalPtrParam("int", true, "rtree_nodes")
                  + """
                  , int node_count, int qt, int qh, int qc, int qe, int qd, int qw) {
                      int hint = -1;
                      return eval_rtree_biome_ord_hinted(rtree_nodes, node_count, qt, qh, qc, qe, qd, qw, &hint);
                  }

                  inline float trilinear_density_cell(float v000, float v001, float v100, float v101, float v010, float v011, float v110, float v111, int dx, int dy, int dz, int cell_w, int cell_h) {
                      float fx = (float)dx / (float)(cell_w > 0 ? cell_w : 4);
                      float fy = (float)dy / (float)(cell_h > 0 ? cell_h : 8);
                      float fz = (float)dz / (float)(cell_w > 0 ? cell_w : 4);
                      float x00 = v000 + (v001 - v000) * fy;
                      float x10 = v100 + (v101 - v100) * fy;
                      float x01 = v010 + (v011 - v010) * fy;
                      float x11 = v110 + (v111 - v110) * fy;
                      float z0 = x00 + (x10 - x00) * fx;
                      float z1 = x01 + (x11 - x01) * fx;
                      return z0 + (z1 - z0) * fz;
                  }

                  inline int eval_lifted_carver_voxel(int cur_slot, int wy, int chunk_min_y, int lava_level_y, int lava_slot, float dist_sq_norm) {
                      if (wy <= chunk_min_y || dist_sq_norm >= 1.0f || cur_slot == 0) {
                          return cur_slot;
                      }
                      return (lava_slot > 0 && wy <= lava_level_y) ? lava_slot : 0;
                  }

                  inline int eval_preliminary_surface_y("""
                  + openClGlobalPtrParam("float", true, "col_prelim_dens")
                  + """
                  , int count, int noise_min_y, int cell_h, int fallback_y) {
                      if (col_prelim_dens == 0 || count <= 0) return fallback_y;
                      for (int i = count - 1; i >= 0; i--) {
                          if (col_prelim_dens[i] > 0.390625f) {
                              return noise_min_y + i * cell_h;
                          }
                      }
                      return fallback_y;
                  }

                  inline int eval_hydrostatic_seal_voxel(int cur_slot, int above_slot, int nx0_slot, int nx1_slot, int nz0_slot, int nz1_slot, int default_block, int default_fluid, int lava_slot) {
                      if (cur_slot != 0) return cur_slot;
                      int af = (default_fluid > 0 && above_slot == default_fluid) || (lava_slot > 0 && above_slot == lava_slot);
                      int x0f = (default_fluid > 0 && nx0_slot == default_fluid) || (lava_slot > 0 && nx0_slot == lava_slot);
                      int x1f = (default_fluid > 0 && nx1_slot == default_fluid) || (lava_slot > 0 && nx1_slot == lava_slot);
                      int z0f = (default_fluid > 0 && nz0_slot == default_fluid) || (lava_slot > 0 && nz0_slot == lava_slot);
                      int z1f = (default_fluid > 0 && nz1_slot == default_fluid) || (lava_slot > 0 && nz1_slot == lava_slot);
                      return (af || x0f || x1f || z0f || z1f) ? default_block : 0;
                  }

                  inline int eval_lifted_aquifer_voxel(float dens, int bx, int wy, int bz, int default_block, int default_fluid, int lava_slot, int sea_level, int deep_lava_y, int prelim_y, float barrier_val, float flooded_val, float spread_val, float lava_val, int adj_dry_air_mask) {
                      (void)bx; (void)bz; (void)barrier_val; (void)adj_dry_air_mask;
                      if (dens > 0.0f) return default_block;
                      if (wy <= deep_lava_y && lava_slot > 0) return lava_slot;
                      if (prelim_y <= sea_level && wy <= sea_level && default_fluid > 0) {
                          return (wy < prelim_y - 2) ? default_block : default_fluid;
                      }
                      if (flooded_val > 0.35f) {
                          int gy_center = (wy >= 0 ? (wy / 12) : ((wy - 11) / 12)) * 12 + 6;
                          int local_lvl = gy_center + (int)rint(spread_val * 6.0f) + 4;
                          int cap_lvl = prelim_y - 6 < sea_level ? prelim_y - 6 : sea_level;
                          if (local_lvl > cap_lvl) local_lvl = cap_lvl;
                          if (wy <= local_lvl) {
                              return (fabs(lava_val) > 0.70f && wy < sea_level - 16 && lava_slot > 0) ? lava_slot : default_fluid;
                          }
                      }
                      return 0;
                  }

                  inline int h3_to_yzx_cap(int h3) {
                      ulong lut = 0x3267BFEA9DC84510UL;
                      int oct6 = (h3 >> 6) & 63;
                      int loc6 = h3 & 63;
                      int oct_y = (oct6 >> 4) & 3;
                      int oct_xz = (int)((lut >> ((oct6 & 15) << 2)) & 15UL);
                      int ly = (loc6 >> 4) & 3;
                      int l_xz = (int)((lut >> ((loc6 & 15) << 2)) & 15UL);
                      int x = ((oct_xz & 3) << 2) | (l_xz & 3);
                      int y = (oct_y << 2) | ly;
                      int z = (((oct_xz >> 2) & 3) << 2) | ((l_xz >> 2) & 3);
                      return (y << 8) | (z << 4) | x;
                  }

                  inline int yzx_to_h3_cap(int x, int y, int z) {
                      ulong inv_lut = 0xA965B874CD23FE10UL;
                      int oct_idx = (((z >> 2) & 3) << 2) | ((x >> 2) & 3);
                      int loc_idx = ((z & 3) << 2) | (x & 3);
                      int oct_h2 = (int)((inv_lut >> (oct_idx << 2)) & 15UL);
                      int loc_h2 = (int)((inv_lut >> (loc_idx << 2)) & 15UL);
                      int oct6 = (((y >> 2) & 3) << 4) | oct_h2;
                      int loc6 = ((y & 3) << 4) | loc_h2;
                      return (oct6 << 6) | loc6;
                  }

                  inline int eval_lifted_router_vein(int cur_slot, int default_block, int wy, float vein_toggle, float vein_ridged, float vein_gap, int upper_min_y, int upper_max_y, int upper_ore, int upper_raw, int upper_filler, int lower_min_y, int lower_max_y, int lower_ore, int lower_raw, int lower_filler, int pos_hash) {
                      if (cur_slot != default_block) return cur_slot;
                      int in_upper = (wy >= upper_min_y && wy <= upper_max_y) ? 1 : 0;
                      int in_lower = (wy >= lower_min_y && wy <= lower_max_y) ? 1 : 0;
                      if (in_upper == 0 && in_lower == 0) return cur_slot;
                      float abs_t = fabs(vein_toggle);
                      if (abs_t <= 0.4f || vein_ridged >= 0.0f) return cur_slot;
                      int ore_slot = 0;
                      int raw_slot = 0;
                      int filler_slot = 0;
                      if (vein_toggle > 0.4f) {
                          if (in_upper == 0) return cur_slot;
                          ore_slot = upper_ore;
                          raw_slot = upper_raw;
                          filler_slot = upper_filler;
                      } else {
                          if (in_lower == 0) return cur_slot;
                          ore_slot = lower_ore;
                          raw_slot = lower_raw;
                          filler_slot = lower_filler;
                      }
                      float richness = fmin(1.0f, fmax(0.0f, (abs_t - 0.4f) * 5.0f));
                      if (fabs(vein_gap) > 0.3f && ore_slot > 0 && ((pos_hash & 255) < (int)(48.0f + richness * 180.0f))) {
                          return (raw_slot > 0 && richness > 0.8f && (((uint)pos_hash >> 8) & 31u) == 0u) ? raw_slot : ore_slot;
                      }
                      return filler_slot > 0 ? filler_slot : cur_slot;
                  }

                  inline int floor_div_pos_i32(int a, int b) {
                      int q = a / b;
                      int r = a - q * b;
                      return (r < 0) ? (q - 1) : q;
                  }

                  inline float eval_aquifer_pressure_fp32(int wy, float bar_val, int lvl_a, int slot_a, int lvl_b, int slot_b, int default_fluid, int lava_slot) {
                      int f_a = (slot_a > 0 && wy <= lvl_a) ? slot_a : 0;
                      int f_b = (slot_b > 0 && wy <= lvl_b) ? slot_b : 0;
                      if (lava_slot > 0 && default_fluid > 0 && ((f_a == lava_slot && f_b == default_fluid) || (f_a == default_fluid && f_b == lava_slot))) {
                          return 2.0f;
                      }
                      int diff = abs(lvl_a - lvl_b);
                      if (diff == 0) return 0.0f;
                      float avg = 0.5f * (float)(lvl_a + lvl_b);
                      float rel_y = (float)wy + 0.5f - avg;
                      float half_diff = (float)diff * 0.5f;
                      float dist_edge = half_diff - fabs(rel_y);
                      float q;
                      if (rel_y > 0.0f) {
                          q = (dist_edge > 0.0f) ? (dist_edge / 1.5f) : (dist_edge / 2.5f);
                      } else {
                          float sh = 3.0f + dist_edge;
                          q = (sh > 0.0f) ? (sh / 3.0f) : (sh / 10.0f);
                      }
                      float b = (q >= -2.0f && q <= 2.0f) ? bar_val : 0.0f;
                      return 2.0f * (b + q);
                  }

                  inline int eval_noise_based_aquifer_voxel("""
                  + openClGlobalPtrParam("float", true, "cells")
                  + """
                  , int min_gx, int min_gy, int min_gz, int size_x, int size_y, int size_z, int x_sp, int y_sp, int z_sp, int skip_above_y, float sim_scale, int bx, int wy, int bz, float dens, float bar_val, int default_block, int default_fluid, int lava_slot, int sea_level, int deep_lava_y) {
                      if (wy > skip_above_y) {
                          if (lava_slot > 0 && wy <= deep_lava_y) return lava_slot;
                          return (default_fluid > 0 && wy <= sea_level) ? default_fluid : 0;
                      }
                      if (lava_slot > 0 && wy <= deep_lava_y) return lava_slot;
                      int base_gx = floor_div_pos_i32(bx - 5, x_sp) - 1;
                      int base_gy = floor_div_pos_i32(wy + 1, y_sp) - 1;
                      int base_gz = floor_div_pos_i32(bz - 5, z_sp) - 1;
                      int i1 = 0, i2 = 0, i3 = 0;
                      int d1 = 2147483647, d2 = 2147483647, d3 = 2147483647;
                      for (int ox = 0; ox <= 2; ox++) {
                          int rx = clamp((base_gx + ox) - min_gx, 0, size_x - 1);
                          for (int oy = 0; oy <= 1; oy++) {
                              int ry = clamp((base_gy + oy) - min_gy, 0, size_y - 1);
                              for (int oz = 0; oz <= 2; oz++) {
                                  int rz = clamp((base_gz + oz) - min_gz, 0, size_z - 1);
                                  int idx = (ry * size_z + rz) * size_x + rx;
                                  int cb = idx * 5;
                                  int dx = (int)cells[cb] - bx;
                                  int dy = (int)cells[cb + 1] - wy;
                                  int dz = (int)cells[cb + 2] - bz;
                                  int dist2 = dx * dx + dy * dy + dz * dz;
                                  if (dist2 <= d1) { d3 = d2; i3 = i2; d2 = d1; i2 = i1; d1 = dist2; i1 = idx; }
                                  else if (dist2 <= d2) { d3 = d2; i3 = i2; d2 = dist2; i2 = idx; }
                                  else if (dist2 <= d3) { d3 = dist2; i3 = idx; }
                              }
                          }
                      }
                      int cb1 = i1 * 5;
                      int lvl1 = (int)cells[cb1 + 3];
                      int slot1 = (int)cells[cb1 + 4];
                      int f1_wy = (slot1 > 0 && wy <= lvl1) ? slot1 : 0;
                      float sim12 = 1.0f - ((float)(d2 - d1) / sim_scale);
                      if (f1_wy == 0 && sim12 <= 0.0f) return 0;
                      if (f1_wy == default_fluid && default_fluid > 0 && lava_slot > 0 && (wy - 1) <= deep_lava_y) return lava_slot;
                      int cb2 = i2 * 5;
                      int lvl2 = (int)cells[cb2 + 3];
                      int slot2 = (int)cells[cb2 + 4];
                      if (dens + sim12 * eval_aquifer_pressure_fp32(wy, bar_val, lvl1, slot1, lvl2, slot2, default_fluid, lava_slot) > 0.0f) return default_block;
                      int cb3 = i3 * 5;
                      int lvl3 = (int)cells[cb3 + 3];
                      int slot3 = (int)cells[cb3 + 4];
                      float sim13 = 1.0f - ((float)(d3 - d1) / sim_scale);
                      if (sim13 > 0.0f && dens + sim12 * sim13 * eval_aquifer_pressure_fp32(wy, bar_val, lvl1, slot1, lvl3, slot3, default_fluid, lava_slot) > 0.0f) return default_block;
                      float sim23 = 1.0f - ((float)(d3 - d2) / sim_scale);
                      if (sim12 > 0.0f && sim23 > 0.0f && dens + sim12 * sim23 * eval_aquifer_pressure_fp32(wy, bar_val, lvl2, slot2, lvl3, slot3, default_fluid, lava_slot) > 0.0f) return default_block;
                      return f1_wy;
                  }

                  inline long voronoi_lcg_next(long s, long inc) {
                      ulong us = (ulong)s;
                      us = us * (us * 6364136223846793005UL + 1442695040888963407UL) + (ulong)inc;
                      return (long)us;
                  }

                  inline float voronoi_get_fiddle(long s) {
                      int bits = (int)((s >> 24) & 1023L);
                      return (((float)bits * (1.0f / 1024.0f)) - 0.5f) * 0.9f;
                  }

                  inline float voronoi_fiddled_dist(long zoom_seed, int qx, int qy, int qz, float dx, float dy, float dz) {
                      long s = voronoi_lcg_next(zoom_seed, (long)qx);
                      s = voronoi_lcg_next(s, (long)qy);
                      s = voronoi_lcg_next(s, (long)qz);
                      s = voronoi_lcg_next(s, (long)qx);
                      s = voronoi_lcg_next(s, (long)qy);
                      s = voronoi_lcg_next(s, (long)qz);
                      float fx = voronoi_get_fiddle(s);
                      s = voronoi_lcg_next(s, zoom_seed);
                      float fy = voronoi_get_fiddle(s);
                      s = voronoi_lcg_next(s, zoom_seed);
                      float fz = voronoi_get_fiddle(s);
                      float ex = dx + fx;
                      float ey = dy + fy;
                      float ez = dz + fz;
                      return ez * ez + ey * ey + ex * ex;
                  }

                  inline int eval_voronoi_zoomed_biome_ord("""
                  + openClGlobalPtrParam("int", true, "halo_biomes")
                  + """
                  , int quarts_y, int chunk_base_qx, int chunk_base_qy, int chunk_base_qz, long zoom_seed, int bx, int wy, int bz) {
                      int i = bx - 2;
                      int j = wy - 2;
                      int k = bz - 2;
                      int qx0 = i >> 2;
                      int qy0 = j >> 2;
                      int qz0 = k >> 2;
                      int hx0 = clamp(qx0 - chunk_base_qx + 1, 0, 4);
                      int hz0 = clamp(qz0 - chunk_base_qz + 1, 0, 4);
                      int hy0 = clamp(qy0 - chunk_base_qy, 0, quarts_y - 1);
                      int hy1 = clamp(qy0 + 1 - chunk_base_qy, 0, quarts_y - 1);
                      int row00 = hy0 * 36 + hz0 * 6 + hx0;
                      int row01 = hy0 * 36 + (hz0 + 1) * 6 + hx0;
                      int row10 = hy1 * 36 + hz0 * 6 + hx0;
                      int row11 = hy1 * 36 + (hz0 + 1) * 6 + hx0;
                      int b000 = halo_biomes[row00];
                      int b100 = halo_biomes[row00 + 1];
                      int b001 = halo_biomes[row01];
                      int b101 = halo_biomes[row01 + 1];
                      int b010 = halo_biomes[row10];
                      int b110 = halo_biomes[row10 + 1];
                      int b011 = halo_biomes[row11];
                      int b111 = halo_biomes[row11 + 1];
                      if (b000 == b100 && b000 == b001 && b000 == b101
                              && b000 == b010 && b000 == b110 && b000 == b011 && b000 == b111) {
                          return b000;
                      }
                      float fx = (float)(i & 3) * 0.25f;
                      float fy = (float)(j & 3) * 0.25f;
                      float fz = (float)(k & 3) * 0.25f;
                      int best_c = 0;
                      float best_d = 1.0e30f;
                      for (int c = 0; c < 8; c++) {
                          int x0 = (c & 4) == 0 ? 1 : 0;
                          int y0 = (c & 2) == 0 ? 1 : 0;
                          int z0 = (c & 1) == 0 ? 1 : 0;
                          int cx = x0 != 0 ? qx0 : (qx0 + 1);
                          int cy = y0 != 0 ? qy0 : (qy0 + 1);
                          int cz = z0 != 0 ? qz0 : (qz0 + 1);
                          float dx = x0 != 0 ? fx : (fx - 1.0f);
                          float dy = y0 != 0 ? fy : (fy - 1.0f);
                          float dz = z0 != 0 ? fz : (fz - 1.0f);
                          float d = voronoi_fiddled_dist(zoom_seed, cx, cy, cz, dx, dy, dz);
                          if (best_d > d) {
                              best_c = c;
                              best_d = d;
                          }
                      }
                      switch (best_c) {
                          case 0: return b000;
                          case 1: return b001;
                          case 2: return b010;
                          case 3: return b011;
                          case 4: return b100;
                          case 5: return b101;
                          case 6: return b110;
                          default: return b111;
                      }
                  }

                  """;
      }

      /**
       * Emits a complete, compilable OpenCL 1.2 C99 capability kernel program embedding the lifted
       * {@link LiftedWorldgenCapabilityTables.DimensionCapabilitySpec} parameters and exercising all
       * capability inline helpers ({@code eval_rtree_biome_ord}, {@code eval_preliminary_surface_y},
       * {@code eval_lifted_aquifer_voxel}, {@code eval_lifted_router_vein}, {@code eval_lifted_surface_rule}).
       */
    private static String hexFloat(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) {
            return "0.0f";
        }
        return Float.toHexString(v) + "f";
    }

    private static String hexDouble(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "0.0";
        }
        return Double.toHexString(v);
    }

    private static int estimateCapabilityNoiseConstantBytes(
            LiftedWorldgenCapabilityTables.GpuRouterNoiseTable table,
            boolean exactMode,
            boolean useNativeFp64) {
        // Base fixed tables: CAP_GRAD_X/Y/Z (192 B) + spans/cond ids (~512 B headroom).
        int bytes = 1024;
        if (table == null || !table.fitsConstantBudget()) {
            return bytes;
        }
        int octaves = table.totalOctaveCount();
        if (octaves <= 0) {
            return bytes;
        }
        // CAP_NOISE_PERM (256 B/oct) + CAP_NOISE_PARAMS (28 B/oct)
        bytes += octaves * (256 + 28);
        if (exactMode) {
            // CAP_NOISE_Q32 (48 B/oct)
            bytes += octaves * 48;
            if (useNativeFp64) {
                // CAP_NOISE_PARAMS_F64 (56 B/oct)
                bytes += octaves * 56;
            }
        }
        return bytes;
    }

    private static int registerGpuNoiseSpec(
            LiftedWorldgenCapabilityTables.LiftedNormalNoiseSpec noiseSpec,
            java.util.List<LiftedWorldgenCapabilityTables.LiftedNormalNoiseSpec> specsOut,
            int[] totalOctavesInOut
    ) {
        if (noiseSpec == null || noiseSpec.octaveCount() <= 0) {
            return -1;
        }
        int idx = specsOut.size();
        specsOut.add(noiseSpec);
        totalOctavesInOut[0] += noiseSpec.octaveCount();
        return idx;
    }

    private static String emitCapabilityConstantNoiseTables(
            LiftedWorldgenCapabilityTables.GpuRouterNoiseTable table,
            int[] outIds6,
            int[] outCondIds,
            int[] outCondIs3d,
            WorldgenKernelSwapper.WorldgenFidelityPolicy fidelityPolicy,
            WorldgenKernelSwapper.GpuExactArithmeticMode gpuExactMode
    ) {
        boolean exactMode = fidelityPolicy != null && fidelityPolicy.isExact();
        WorldgenKernelSwapper.GpuExactArithmeticMode effExactMode =
                gpuExactMode == null
                        ? WorldgenKernelSwapper.GpuExactArithmeticMode.NATIVE_FP64
                        : gpuExactMode;
        boolean useNativeFp64 = exactMode && effExactMode.usesNativeFp64();
        boolean useHybridSignBand = exactMode && effExactMode.refinesBoundarySignBand();
        java.util.Arrays.fill(outIds6, -1);
        java.util.Arrays.fill(outCondIds, -1);
        java.util.List<LiftedWorldgenCapabilityTables.LiftedNormalNoiseSpec> specs = new java.util.ArrayList<>();
        int[] totalOctaves = {0};
        if (table != null && table.fitsConstantBudget()) {
            outIds6[0] = registerGpuNoiseSpec(table.barrierNoise(), specs, totalOctaves);
            if (table.hasGpuVeinNoises()) {
                outIds6[1] = registerGpuNoiseSpec(table.veinToggleNoise(), specs, totalOctaves);
                outIds6[2] = registerGpuNoiseSpec(table.veinRidgedNoise(), specs, totalOctaves);
                outIds6[3] = registerGpuNoiseSpec(table.veinGapNoise(), specs, totalOctaves);
                outIds6[5] = registerGpuNoiseSpec(table.veinRidgedNoiseB(), specs, totalOctaves);
            }
            outIds6[4] = registerGpuNoiseSpec(table.temperatureNoise(), specs, totalOctaves);
            LiftedWorldgenCapabilityTables.LiftedNormalNoiseSpec[] cns = table.conditionNoises();
            boolean[] c3d = table.conditionNoiseIs3d();
            for (int i = 0; i < outCondIds.length && i < cns.length; i++) {
                outCondIds[i] = registerGpuNoiseSpec(cns[i], specs, totalOctaves);
                outCondIs3d[i] = (c3d != null && i < c3d.length && c3d[i]) ? 1 : 0;
            }
        }

        StringBuilder sb = new StringBuilder(Math.max(1024, totalOctaves[0] * 1100));
        if (exactMode) {
            sb.append("\n#define LEAFGEN_FIDELITY_VANILLA_EXACT 1\n");
            if (useNativeFp64) {
                sb.append("#define LEAFGEN_EXACT_NATIVE_FP64 1\n");
                sb.append("#ifdef cl_khr_fp64\n#pragma OPENCL EXTENSION cl_khr_fp64 : enable\n#endif\n");
            } else {
                sb.append("#define LEAFGEN_EXACT_INT64_Q32_FP32 1\n");
                if (useHybridSignBand) {
                    sb.append("#define LEAFGEN_EXACT_HYBRID_SIGNBAND 1\n");
                }
            }
        } else {
            sb.append("\n#define LEAFGEN_FIDELITY_VANILLA_LIKE 1\n");
            sb.append("#define CAP_MACRO13_OCTAVE_CAP 13\n");
        }
        sb.append("#define CAP_UNCERTAIN_SIGN_CPU_REFINE_FLAG 0x40000000\n");
        sb.append("#define CAP_CARVER_OVERFLOW_FALLBACK_FLAG 0x20000000\n");
        sb.append("#define CAP_EXACT_SIGN_UNCERTAINTY_EPS 5.0e-6f\n");
        sb.append('\n').append(openClConstantArrayPrefix("float", "CAP_GRAD_X", 16))
                .append("{1.0f,-1.0f,1.0f,-1.0f,1.0f,-1.0f,1.0f,-1.0f,0.0f,0.0f,0.0f,0.0f,1.0f,0.0f,-1.0f,0.0f};\n");
        sb.append(openClConstantArrayPrefix("float", "CAP_GRAD_Y", 16))
                .append("{1.0f,1.0f,-1.0f,-1.0f,0.0f,0.0f,0.0f,0.0f,1.0f,-1.0f,1.0f,-1.0f,1.0f,-1.0f,1.0f,-1.0f};\n");
        sb.append(openClConstantArrayPrefix("float", "CAP_GRAD_Z", 16))
                .append("{0.0f,0.0f,0.0f,0.0f,1.0f,1.0f,-1.0f,-1.0f,1.0f,1.0f,-1.0f,-1.0f,0.0f,1.0f,0.0f,-1.0f};\n");
        sb.append("#define CAP_NOISE_COUNT ").append(specs.size()).append("\n");
        sb.append("#define CAP_COND_TABLE_LEN ").append(Math.max(1, outCondIds.length)).append("\n");

        if (specs.isEmpty() || totalOctaves[0] <= 0) {
            sb.append(openClConstantArrayPrefix("uchar", "CAP_NOISE_PERM", 1)).append("{0};\n");
            sb.append(openClConstantArrayPrefix("float", "CAP_NOISE_PARAMS", 1)).append("{0.0f};\n");
            if (exactMode) {
                sb.append(openClConstantArrayPrefix("long", "CAP_NOISE_Q32", 6)).append("{0L,0L,0L,0L,0L,0L};\n");
                if (useNativeFp64) {
                    sb.append("#ifdef cl_khr_fp64\n")
                            .append(openClConstantArrayPrefix("double", "CAP_NOISE_PARAMS_F64", 1))
                            .append("{0.0};\n#endif\n");
                }
            }
            sb.append(openClConstantArrayPrefix("int", "CAP_NOISE_SPANS", 3)).append("{0, 0, 0};\n");
        } else {
            sb.append(openClConstantArrayPrefix("uchar", "CAP_NOISE_PERM", totalOctaves[0] * 256)).append('{');
            boolean firstPerm = true;
            for (LiftedWorldgenCapabilityTables.LiftedNormalNoiseSpec ns : specs) {
                byte[] p = ns.permTable();
                for (byte b : p) {
                    if (!firstPerm) sb.append(',');
                    firstPerm = false;
                    sb.append(b & 255);
                }
            }
            sb.append("};\n");

            sb.append(openClConstantArrayPrefix("float", "CAP_NOISE_PARAMS", totalOctaves[0] * 7)).append('{');
            boolean firstParam = true;
            for (LiftedWorldgenCapabilityTables.LiftedNormalNoiseSpec ns : specs) {
                int n = ns.octaveCount();
                float[] fx = ns.freqX();
                float[] fy = ns.freqY();
                float[] fz = ns.freqZ();
                float[] w = ns.weights();
                float[] xo = ns.xo();
                float[] yo = ns.yo();
                float[] zo = ns.zo();
                for (int k = 0; k < n; k++) {
                    if (!firstParam) sb.append(',');
                    firstParam = false;
                    sb.append(hexFloat(fx[k])).append(',')
                            .append(hexFloat(fy[k])).append(',')
                            .append(hexFloat(fz[k])).append(',')
                            .append(hexFloat(w[k])).append(',')
                            .append(hexFloat(xo[k])).append(',')
                            .append(hexFloat(yo[k])).append(',')
                            .append(hexFloat(zo[k]));
                }
            }
            sb.append("};\n");

            if (exactMode) {
                sb.append(openClConstantArrayPrefix("long", "CAP_NOISE_Q32", totalOctaves[0] * 6)).append('{');
                boolean firstQ32 = true;
                for (LiftedWorldgenCapabilityTables.LiftedNormalNoiseSpec ns : specs) {
                    int n = ns.octaveCount();
                    float[] fx = ns.freqX();
                    float[] fy = ns.freqY();
                    float[] fz = ns.freqZ();
                    float[] xo = ns.xo();
                    float[] yo = ns.yo();
                    float[] zo = ns.zo();
                    for (int k = 0; k < n; k++) {
                        if (!firstQ32) sb.append(',');
                        firstQ32 = false;
                        long sx = Math.round(((double) fx[k]) * 4294967296.0);
                        long sy = Math.round(((double) fy[k]) * 4294967296.0);
                        long sz = Math.round(((double) fz[k]) * 4294967296.0);
                        long ox = Math.round((((double) xo[k]) - Math.floor(((double) xo[k]) / 256.0) * 256.0) * 4294967296.0);
                        long oy = Math.round((((double) yo[k]) - Math.floor(((double) yo[k]) / 256.0) * 256.0) * 4294967296.0);
                        long oz = Math.round((((double) zo[k]) - Math.floor(((double) zo[k]) / 256.0) * 256.0) * 4294967296.0);
                        sb.append(sx).append("L,")
                                .append(sy).append("L,")
                                .append(sz).append("L,")
                                .append(ox).append("L,")
                                .append(oy).append("L,")
                                .append(oz).append("L");
                    }
                }
                sb.append("};\n");

                if (useNativeFp64) {
                    sb.append("#ifdef cl_khr_fp64\n")
                            .append(openClConstantArrayPrefix("double", "CAP_NOISE_PARAMS_F64", totalOctaves[0] * 7))
                            .append('{');
                    boolean firstParam64 = true;
                    for (LiftedWorldgenCapabilityTables.LiftedNormalNoiseSpec ns : specs) {
                        int n = ns.octaveCount();
                        float[] fx = ns.freqX();
                        float[] fy = ns.freqY();
                        float[] fz = ns.freqZ();
                        float[] w = ns.weights();
                        float[] xo = ns.xo();
                        float[] yo = ns.yo();
                        float[] zo = ns.zo();
                        for (int k = 0; k < n; k++) {
                            if (!firstParam64) sb.append(',');
                            firstParam64 = false;
                            sb.append(hexDouble(fx[k])).append(',')
                                    .append(hexDouble(fy[k])).append(',')
                                    .append(hexDouble(fz[k])).append(',')
                                    .append(hexDouble(w[k])).append(',')
                                    .append(hexDouble(xo[k])).append(',')
                                    .append(hexDouble(yo[k])).append(',')
                                    .append(hexDouble(zo[k]));
                        }
                    }
                    sb.append("};\n#endif\n");
                }
            }

            sb.append(openClConstantArrayPrefix("int", "CAP_NOISE_SPANS", specs.size() * 3)).append('{');
            int octCursor = 0;
            for (int i = 0; i < specs.size(); i++) {
                if (i > 0) sb.append(',');
                LiftedWorldgenCapabilityTables.LiftedNormalNoiseSpec ns = specs.get(i);
                int activeOctaves = exactMode ? ns.octaveCount() : Math.min(13, ns.octaveCount());
                sb.append(octCursor).append(',').append(activeOctaves).append(',').append(ns.columnConstantY() ? 1 : 0);
                octCursor += ns.octaveCount();
            }
            sb.append("};\n");
        }

        sb.append(openClConstantArrayPrefix("int", "CAP_COND_NOISE_IDS", Math.max(1, outCondIds.length))).append('{');
        if (outCondIds.length == 0) {
            sb.append("-1");
        } else {
            for (int i = 0; i < outCondIds.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(outCondIds[i]);
            }
        }
        sb.append("};\n");

        sb.append(openClConstantArrayPrefix("int", "CAP_COND_NOISE_3D", Math.max(1, outCondIs3d.length))).append('{');
        if (outCondIs3d.length == 0) {
            sb.append("0");
        } else {
            for (int i = 0; i < outCondIs3d.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(outCondIs3d[i]);
            }
        }
        sb.append("};\n");

        sb.append("""
                inline int cap_fast_floor_f(float v) {
                    int xi = (int)v;
                    return v < (float)xi ? (xi - 1) : xi;
                }
                inline float cap_grad_dot_f(int hash, float x, float y, float z) {
                    int idx = hash & 15;
                    return CAP_GRAD_X[idx] * x + CAP_GRAD_Y[idx] * y + CAP_GRAD_Z[idx] * z;
                }
                inline float cap_sample_octave_fp32(int off, float d, float e, float f) {
                    int i = cap_fast_floor_f(d);
                    int j = cap_fast_floor_f(e);
                    int k = cap_fast_floor_f(f);
                    float g = d - (float)i;
                    float h = e - (float)j;
                    float l = f - (float)k;
                    int p0 = (int)CAP_NOISE_PERM[off + (i & 255)];
                    int p1 = (int)CAP_NOISE_PERM[off + ((i + 1) & 255)];
                    int p00 = (int)CAP_NOISE_PERM[off + ((p0 + j) & 255)];
                    int p01 = (int)CAP_NOISE_PERM[off + ((p0 + j + 1) & 255)];
                    int p10 = (int)CAP_NOISE_PERM[off + ((p1 + j) & 255)];
                    int p11 = (int)CAP_NOISE_PERM[off + ((p1 + j + 1) & 255)];
                    float gm1 = g - 1.0f;
                    float hm1 = h - 1.0f;
                    float lm1 = l - 1.0f;
                    float c000 = cap_grad_dot_f((int)CAP_NOISE_PERM[off + ((p00 + k) & 255)], g, h, l);
                    float c100 = cap_grad_dot_f((int)CAP_NOISE_PERM[off + ((p10 + k) & 255)], gm1, h, l);
                    float c010 = cap_grad_dot_f((int)CAP_NOISE_PERM[off + ((p01 + k) & 255)], g, hm1, l);
                    float c110 = cap_grad_dot_f((int)CAP_NOISE_PERM[off + ((p11 + k) & 255)], gm1, hm1, l);
                    float c001 = cap_grad_dot_f((int)CAP_NOISE_PERM[off + ((p00 + k + 1) & 255)], g, h, lm1);
                    float c101 = cap_grad_dot_f((int)CAP_NOISE_PERM[off + ((p10 + k + 1) & 255)], gm1, h, lm1);
                    float c011 = cap_grad_dot_f((int)CAP_NOISE_PERM[off + ((p01 + k + 1) & 255)], g, hm1, lm1);
                    float c111 = cap_grad_dot_f((int)CAP_NOISE_PERM[off + ((p11 + k + 1) & 255)], gm1, hm1, lm1);
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
                inline float eval_lifted_normal_noise_fp32(int noise_id, float x, float y, float z) {
                    if (noise_id < 0 || noise_id >= CAP_NOISE_COUNT) return 0.0f;
                    int start_oct = CAP_NOISE_SPANS[noise_id * 3];
                    int oct_cnt = CAP_NOISE_SPANS[noise_id * 3 + 1];
                    float eff_y = (CAP_NOISE_SPANS[noise_id * 3 + 2] != 0) ? 0.0f : y;
                    float sum = 0.0f;
                    for (int k = 0; k < oct_cnt; k++) {
                        int oct = start_oct + k;
                        int pb = oct * 7;
                        float d = x * CAP_NOISE_PARAMS[pb] + CAP_NOISE_PARAMS[pb + 4];
                        float e = eff_y * CAP_NOISE_PARAMS[pb + 1] + CAP_NOISE_PARAMS[pb + 5];
                        float f = z * CAP_NOISE_PARAMS[pb + 2] + CAP_NOISE_PARAMS[pb + 6];
                        sum += cap_sample_octave_fp32(oct << 8, d, e, f) * CAP_NOISE_PARAMS[pb + 3];
                    }
                    return sum;
                }
                """);
        if (exactMode) {
            sb.append("""
                    inline float cap_sample_octave_q32_fp32(int off, long qx, long qy, long qz) {
                        int wrap24_x = ((int)(qx >> 32)) & 0xFFFFFF;
                        int wrap24_y = ((int)(qy >> 32)) & 0xFFFFFF;
                        int wrap24_z = ((int)(qz >> 32)) & 0xFFFFFF;
                        int i = wrap24_x & 255;
                        int j = wrap24_y & 255;
                        int k = wrap24_z & 255;
                        float g = (float)((uint)(qx & 0xFFFFFFFFL)) * 0x1.0p-32f;
                        float h = (float)((uint)(qy & 0xFFFFFFFFL)) * 0x1.0p-32f;
                        float l = (float)((uint)(qz & 0xFFFFFFFFL)) * 0x1.0p-32f;
                        int p0 = (int)CAP_NOISE_PERM[off + i];
                        int p1 = (int)CAP_NOISE_PERM[off + ((i + 1) & 255)];
                        int p00 = (int)CAP_NOISE_PERM[off + ((p0 + j) & 255)];
                        int p01 = (int)CAP_NOISE_PERM[off + ((p0 + j + 1) & 255)];
                        int p10 = (int)CAP_NOISE_PERM[off + ((p1 + j) & 255)];
                        int p11 = (int)CAP_NOISE_PERM[off + ((p1 + j + 1) & 255)];
                        float gm1 = g - 1.0f;
                        float hm1 = h - 1.0f;
                        float lm1 = l - 1.0f;
                        float c000 = cap_grad_dot_f((int)CAP_NOISE_PERM[off + ((p00 + k) & 255)], g, h, l);
                        float c100 = cap_grad_dot_f((int)CAP_NOISE_PERM[off + ((p10 + k) & 255)], gm1, h, l);
                        float c010 = cap_grad_dot_f((int)CAP_NOISE_PERM[off + ((p01 + k) & 255)], g, hm1, l);
                        float c110 = cap_grad_dot_f((int)CAP_NOISE_PERM[off + ((p11 + k) & 255)], gm1, hm1, l);
                        float c001 = cap_grad_dot_f((int)CAP_NOISE_PERM[off + ((p00 + k + 1) & 255)], g, h, lm1);
                        float c101 = cap_grad_dot_f((int)CAP_NOISE_PERM[off + ((p10 + k + 1) & 255)], gm1, h, lm1);
                        float c011 = cap_grad_dot_f((int)CAP_NOISE_PERM[off + ((p01 + k + 1) & 255)], g, hm1, lm1);
                        float c111 = cap_grad_dot_f((int)CAP_NOISE_PERM[off + ((p11 + k + 1) & 255)], gm1, hm1, lm1);
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
                    inline float eval_lifted_normal_noise_i64f32(int noise_id, long bx, long wy, long bz) {
                        if (noise_id < 0 || noise_id >= CAP_NOISE_COUNT) return 0.0f;
                        int start_oct = CAP_NOISE_SPANS[noise_id * 3];
                        int oct_cnt = CAP_NOISE_SPANS[noise_id * 3 + 1];
                        long eff_y = (CAP_NOISE_SPANS[noise_id * 3 + 2] != 0) ? 0L : wy;
                        float sum = 0.0f;
                        for (int k = 0; k < oct_cnt; k++) {
                            int oct = start_oct + k;
                            int qb = oct * 6;
                            int pb = oct * 7;
                            long qx = bx * CAP_NOISE_Q32[qb] + CAP_NOISE_Q32[qb + 3];
                            long qy = eff_y * CAP_NOISE_Q32[qb + 1] + CAP_NOISE_Q32[qb + 4];
                            long qz = bz * CAP_NOISE_Q32[qb + 2] + CAP_NOISE_Q32[qb + 5];
                            sum += cap_sample_octave_q32_fp32(oct << 8, qx, qy, qz) * CAP_NOISE_PARAMS[pb + 3];
                        }
                        return sum;
                    }
                    """);
            if (useNativeFp64) {
                sb.append("""
                        #if defined(LEAFGEN_EXACT_NATIVE_FP64) && defined(cl_khr_fp64)
                        inline double cap_grad_dot_f64(int hash, double x, double y, double z) {
                            int idx = hash & 15;
                            return (double)CAP_GRAD_X[idx] * x + (double)CAP_GRAD_Y[idx] * y + (double)CAP_GRAD_Z[idx] * z;
                        }
                        inline double cap_sample_octave_fp64(int off, double d, double e, double f) {
                            double fl_d = floor(d);
                            double fl_e = floor(e);
                            double fl_f = floor(f);
                            int i = ((int)((long)fl_d)) & 255;
                            int j = ((int)((long)fl_e)) & 255;
                            int k = ((int)((long)fl_f)) & 255;
                            double g = d - fl_d;
                            double h = e - fl_e;
                            double l = f - fl_f;
                            int p0 = (int)CAP_NOISE_PERM[off + i];
                            int p1 = (int)CAP_NOISE_PERM[off + ((i + 1) & 255)];
                            int p00 = (int)CAP_NOISE_PERM[off + ((p0 + j) & 255)];
                            int p01 = (int)CAP_NOISE_PERM[off + ((p0 + j + 1) & 255)];
                            int p10 = (int)CAP_NOISE_PERM[off + ((p1 + j) & 255)];
                            int p11 = (int)CAP_NOISE_PERM[off + ((p1 + j + 1) & 255)];
                            double gm1 = g - 1.0;
                            double hm1 = h - 1.0;
                            double lm1 = l - 1.0;
                            double c000 = cap_grad_dot_f64((int)CAP_NOISE_PERM[off + ((p00 + k) & 255)], g, h, l);
                            double c100 = cap_grad_dot_f64((int)CAP_NOISE_PERM[off + ((p10 + k) & 255)], gm1, h, l);
                            double c010 = cap_grad_dot_f64((int)CAP_NOISE_PERM[off + ((p01 + k) & 255)], g, hm1, l);
                            double c110 = cap_grad_dot_f64((int)CAP_NOISE_PERM[off + ((p11 + k) & 255)], gm1, hm1, l);
                            double c001 = cap_grad_dot_f64((int)CAP_NOISE_PERM[off + ((p00 + k + 1) & 255)], g, h, lm1);
                            double c101 = cap_grad_dot_f64((int)CAP_NOISE_PERM[off + ((p10 + k + 1) & 255)], gm1, h, lm1);
                            double c011 = cap_grad_dot_f64((int)CAP_NOISE_PERM[off + ((p01 + k + 1) & 255)], g, hm1, lm1);
                            double c111 = cap_grad_dot_f64((int)CAP_NOISE_PERM[off + ((p11 + k + 1) & 255)], gm1, hm1, lm1);
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
                        inline double eval_lifted_normal_noise_fp64(int noise_id, double x, double y, double z) {
                            if (noise_id < 0 || noise_id >= CAP_NOISE_COUNT) return 0.0;
                            int start_oct = CAP_NOISE_SPANS[noise_id * 3];
                            int oct_cnt = CAP_NOISE_SPANS[noise_id * 3 + 1];
                            double eff_y = (CAP_NOISE_SPANS[noise_id * 3 + 2] != 0) ? 0.0 : y;
                            double sum = 0.0;
                            for (int k = 0; k < oct_cnt; k++) {
                                int oct = start_oct + k;
                                int pb = oct * 7;
                                double d = x * CAP_NOISE_PARAMS_F64[pb] + CAP_NOISE_PARAMS_F64[pb + 4];
                                double e = eff_y * CAP_NOISE_PARAMS_F64[pb + 1] + CAP_NOISE_PARAMS_F64[pb + 5];
                                double f = z * CAP_NOISE_PARAMS_F64[pb + 2] + CAP_NOISE_PARAMS_F64[pb + 6];
                                sum += cap_sample_octave_fp64(oct << 8, d, e, f) * CAP_NOISE_PARAMS_F64[pb + 3];
                            }
                            return sum;
                        }
                        #endif
                        inline float eval_lifted_normal_noise_mode(int noise_id, int bx, int wy, int bz) {
                        #if defined(LEAFGEN_EXACT_NATIVE_FP64) && defined(cl_khr_fp64)
                            return (float)eval_lifted_normal_noise_fp64(noise_id, (double)bx, (double)wy, (double)bz);
                        #else
                            return eval_lifted_normal_noise_i64f32(noise_id, (long)bx, (long)wy, (long)bz);
                        #endif
                        }
                        """);
            } else {
                sb.append("""
                        inline float eval_lifted_normal_noise_mode(int noise_id, int bx, int wy, int bz) {
                            return eval_lifted_normal_noise_i64f32(noise_id, (long)bx, (long)wy, (long)bz);
                        }
                        """);
            }
        } else {
            sb.append("""
                    inline float eval_lifted_normal_noise_mode(int noise_id, int bx, int wy, int bz) {
                        return eval_lifted_normal_noise_fp32(noise_id, (float)bx, (float)wy, (float)bz);
                    }
                    """);
        }
        return sb.toString();
    }

    /**
     * Emits OpenCL C99 device functions and LUTs for a lifted surface-rule {@link ConceptModule}
     * via {@link #emit(ConceptModule, EmitOptions)} plus a thin {@code eval_lifted_surface_rule_ir}
     * adapter binding the voxel surface context parameters.
     */
    public static String emitSurfaceRuleDeviceSection(
            ConceptModule surfaceRuleModule,
            WorldgenKernelSwapper.WorldgenFidelityPolicy effPolicy,
            WorldgenKernelSwapper.GpuExactArithmeticMode effExactMode
    ) {
        return emitSurfaceRuleDeviceSection(
                surfaceRuleModule,
                effPolicy,
                effExactMode,
                new ConstantLutAllocator(MAX_CONSTANT_BUDGET_BYTES, 0));
    }

    /**
     * Emits the lifted {@code SurfaceRules} IR device section using a shared
     * {@link ConstantLutAllocator} and {@code sr_} symbol prefix so it shares {@code __constant}
     * LUTs cleanly with {@link #emitFinalDensityDeviceSection}.
     */
    public static String emitSurfaceRuleDeviceSection(
            ConceptModule surfaceRuleModule,
            WorldgenKernelSwapper.WorldgenFidelityPolicy effPolicy,
            WorldgenKernelSwapper.GpuExactArithmeticMode effExactMode,
            ConstantLutAllocator sharedAllocator
    ) {
        StringBuilder sb = new StringBuilder(512);
        boolean passSpill = sharedAllocator != null && sharedAllocator.globalSpillBufferName != null;
        String spillParam = passSpill
                ? (",\n    " + openClGlobalPtrParam("int", true, sharedAllocator.globalSpillBufferName))
                : "";
        if (surfaceRuleModule == null
                || surfaceRuleModule.entryFunction() == null
                || !surfaceRuleModule.isPureCompute()
                || !surfaceRuleModule.unmappedReasons().isEmpty()) {
            sb.append("inline int eval_lifted_surface_rule_ir(\n")
                    .append("    int current_slot, int default_block_slot,\n")
                    .append("    int blockX, int blockY, int blockZ,\n")
                    .append("    int stoneDepthAbove, int stoneDepthBelow, int waterHeight,\n")
                    .append("    int surfaceDepth, int biomeId, int prelimSurfaceY,\n")
                    .append("    int steep, int surfaceGradientX, int surfaceGradientZ,\n")
                    .append("    float surfaceSecondary, float temperature")
                    .append(spillParam)
                    .append("\n) {\n")
                    .append(passSpill ? ("    (void)" + sharedAllocator.globalSpillBufferName + ";\n") : "")
                    .append("    return current_slot;\n")
                    .append("}\n");
            return sb.toString();
        }
        boolean exactMode = effPolicy != null && effPolicy.isExact();
        boolean useNativeFp64 = exactMode
                && effExactMode != null
                && effExactMode.usesNativeFp64();
        FloatingPointPrecision precision = useNativeFp64
                ? FloatingPointPrecision.NATIVE_FP64
                : FloatingPointPrecision.DOWNCAST_FP32;
        GpuExactArithmeticMode arithMode = useNativeFp64
                ? GpuExactArithmeticMode.NATIVE_FP64
                : GpuExactArithmeticMode.DOWNCAST_FP32;
        EmitOptions emitOpts = new EmitOptions(
                precision,
                false,
                ConceptOptimizerPass.OptimizationConfig.defaults(),
                arithMode,
                BatchLayout.SINGLE_POINT);
        TranspiledArtifact artifact = new OpenClBackendEmitter()
                .emitWithAllocator(surfaceRuleModule, emitOpts, "sr_", sharedAllocator, false);
        for (String line : artifact.sourceCode().split("\\R")) {
            if (line.startsWith("#pragma OPENCL") || line.startsWith("// Auto-generated OpenCL") || line.startsWith("// Floating-point mode:")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        ConceptFunction entry = surfaceRuleModule.entryFunction();
        sb.append("inline int eval_lifted_surface_rule_ir(\n");
        sb.append("    int current_slot, int default_block_slot,\n");
        sb.append("    int blockX, int blockY, int blockZ,\n");
        sb.append("    int stoneDepthAbove, int stoneDepthBelow, int waterHeight,\n");
        sb.append("    int surfaceDepth, int biomeId, int prelimSurfaceY,\n");
        sb.append("    int steep, int surfaceGradientX, int surfaceGradientZ,\n");
        sb.append("    float surfaceSecondary, float temperature").append(spillParam).append("\n");
        sb.append(") {\n");
        sb.append("    if (current_slot != default_block_slot) return current_slot;\n");
        sb.append("    int res = (int)sr_").append(entry.name()).append('(');
        List<ConceptParam> params = entry.parameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            ConceptParam p = params.get(i);
            String expr = switch (p.name()) {
                case "blockX", "x", "p0" -> "blockX";
                case "blockY", "y", "p1" -> "blockY";
                case "blockZ", "z", "p2" -> "blockZ";
                case "stoneDepthAbove" -> "stoneDepthAbove";
                case "stoneDepthBelow" -> "stoneDepthBelow";
                case "waterHeight" -> "waterHeight";
                case "surfaceDepth" -> "surfaceDepth";
                case "biomeId" -> "biomeId";
                case "prelimSurfaceY" -> "prelimSurfaceY";
                case "steep" -> "steep";
                case "surfaceGradientX" -> "surfaceGradientX";
                case "surfaceGradientZ" -> "surfaceGradientZ";
                case "surfaceSecondary" -> useNativeFp64 ? "(double)surfaceSecondary" : "surfaceSecondary";
                case "temperature" -> "temperature";
                default -> "0";
            };
            if (p.type() == ConceptType.F64 && !useNativeFp64) {
                sb.append("(float)(").append(expr).append(')');
            } else if (p.type() == ConceptType.F64) {
                sb.append("(double)(").append(expr).append(')');
            } else if (p.type() == ConceptType.F32) {
                sb.append("(float)(").append(expr).append(')');
            } else if (p.type() == ConceptType.I64) {
                sb.append("(long)(").append(expr).append(')');
            } else {
                sb.append(expr);
            }
        }
        if (passSpill) {
            if (!params.isEmpty()) {
                sb.append(", ");
            }
            sb.append(sharedAllocator.globalSpillBufferName);
        }
        sb.append(");\n");
        sb.append("    return res > 0 ? res : current_slot;\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Emits the lifted {@code finalDensity} {@link ConceptModule} IR device section using a fresh
     * {@link ConstantLutAllocator}.
     */
    public static String emitFinalDensityDeviceSection(
            ConceptModule finalDensityModule,
            WorldgenKernelSwapper.WorldgenFidelityPolicy effPolicy,
            WorldgenKernelSwapper.GpuExactArithmeticMode effExactMode
    ) {
        return emitFinalDensityDeviceSection(
                finalDensityModule,
                effPolicy,
                effExactMode,
                new ConstantLutAllocator(MAX_CONSTANT_BUDGET_BYTES, 0));
    }

    /**
     * Emits the lifted {@code finalDensity} {@link ConceptModule} IR device section with compacted
     * and deduplicated {@code __constant} LUTs ({@code <= 48 KiB} budget with overflow spill) and
     * {@code fd_}-prefixed helper symbols, exposing {@code eval_lifted_final_density_ir(bx, by, bz)}.
     */
    public static String emitFinalDensityDeviceSection(
            ConceptModule finalDensityModule,
            WorldgenKernelSwapper.WorldgenFidelityPolicy effPolicy,
            WorldgenKernelSwapper.GpuExactArithmeticMode effExactMode,
            ConstantLutAllocator sharedAllocator
    ) {
        return emitFinalDensityDeviceSectionWithPlan(
                finalDensityModule, effPolicy, effExactMode, sharedAllocator, false).sourceCode();
    }

    private static DiamondMemoPlan analyzeDiamondMemoPlan(
            ConceptModule module, GpuExactArithmeticMode arithMode, Q32LoweringPlan q32Plan) {
        if (module == null
                || module.entryFunction() == null
                || module.helperFunctions().isEmpty()) {
            return DiamondMemoPlan.EMPTY;
        }
        boolean fp64 = arithMode == GpuExactArithmeticMode.NATIVE_FP64;
        boolean int64Q32 = arithMode == GpuExactArithmeticMode.INT64_Q32_FP32;
        String elemType = fp64 ? "double" : "float";
        int maxSlots = 32; // fits in single 32-bit uint memo_mask

        Map<String, ConceptFunction> fnByKey = new LinkedHashMap<>();
        Map<String, ConceptFunction> fnByName = new LinkedHashMap<>();
        registerFunctionLookup(module, fnByKey, fnByName);
        Map<String, Integer> callCounts = countFunctionCallSites(module, fnByKey, fnByName);

        Set<String> yInvNames = new LinkedHashSet<>();
        boolean changed = true;
        for (int iter = 0; iter < 16 && changed; iter++) {
            changed = false;
            for (ConceptFunction h : module.helperFunctions()) {
                if (yInvNames.contains(h.name())) {
                    continue;
                }
                if (!isEligibleCol2dSignature(h, fp64, int64Q32, q32Plan)) {
                    continue;
                }
                String yParam = h.parameters().get(1).name();
                if (!usesYOutsideYInvCalls(h.body(), yParam, yInvNames, fnByKey, fnByName)) {
                    yInvNames.add(h.name());
                    changed = true;
                }
            }
        }

        // Rank eligible 3-int-param helpers by (2D-bonus + diamond fan-in * body weight) so the
        // highest-reuse climate/spline/density DAG nodes (continents, erosion, ridges, offset,
        // factor, jaggedness, sloped_cheese, caves) get private bitmask memo slots first.
        List<ConceptFunction> candidates = new ArrayList<>();
        for (ConceptFunction h : module.helperFunctions()) {
            if (!isEligibleCol2dSignature(h, fp64, int64Q32, q32Plan)) {
                continue;
            }
            boolean is2d = yInvNames.contains(h.name());
            int calls = callCounts.getOrDefault(h.name(), 0);
            int stmts = countStmtNodes(h.body());
            boolean nonTrivial = stmts >= 3 || containsNestedCall(h.body());
            if (!nonTrivial) {
                continue;
            }
            // Memoize any Y-invariant helper (reused across all Y levels in the thread band)
            // OR any 3D helper with diamond fan-in >= 2.
            if (is2d || calls >= 2) {
                candidates.add(h);
            }
        }
        if (candidates.isEmpty()) {
            return DiamondMemoPlan.EMPTY;
        }
        candidates.sort((a, b) -> {
            int scoreA = (yInvNames.contains(a.name()) ? 1000 : 0)
                    + callCounts.getOrDefault(a.name(), 0) * 50
                    + Math.min(200, countStmtNodes(a.body()));
            int scoreB = (yInvNames.contains(b.name()) ? 1000 : 0)
                    + callCounts.getOrDefault(b.name(), 0) * 50
                    + Math.min(200, countStmtNodes(b.body()));
            return Integer.compare(scoreB, scoreA);
        });
        List<ConceptFunction> selected = candidates.size() > maxSlots
                ? candidates.subList(0, maxSlots)
                : candidates;
        Map<String, Integer> fnNameToSlot = new LinkedHashMap<>();
        int keep2dMask = 0;
        for (int i = 0; i < selected.size(); i++) {
            ConceptFunction mh = selected.get(i);
            fnNameToSlot.put(mh.name(), i);
            if (yInvNames.contains(mh.name())) {
                keep2dMask |= (1 << i);
            }
        }
        return new DiamondMemoPlan(List.copyOf(selected), Collections.unmodifiableMap(fnNameToSlot), keep2dMask, elemType);
    }

    private static boolean isEligibleCol2dSignature(
            ConceptFunction fn, boolean fp64, boolean int64Q32, Q32LoweringPlan q32Plan) {
        if (fn.parameters().size() != 3) {
            return false;
        }
        if (fn.parameters().get(0).type() != ConceptType.I32
                || fn.parameters().get(1).type() != ConceptType.I32
                || fn.parameters().get(2).type() != ConceptType.I32) {
            return false;
        }
        if (int64Q32 && q32Plan != null) {
            if (q32Plan.isQ32Return(fn)
                    || q32Plan.isQ32Param(fn, 0)
                    || q32Plan.isQ32Param(fn, 1)
                    || q32Plan.isQ32Param(fn, 2)) {
                return false;
            }
        }
        return fp64
                ? (fn.returnType() == ConceptType.F64)
                : (fn.returnType() == ConceptType.F64 || fn.returnType() == ConceptType.F32);
    }

    private static boolean usesYOutsideYInvCalls(
            List<ConceptStmt> stmts,
            String yParam,
            Set<String> yInvNames,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName) {
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.VarDecl vd && vd.initializer() != null) {
                if (exprUsesYOutsideYInvCalls(vd.initializer(), yParam, yInvNames, fnByKey, fnByName)) return true;
            } else if (s instanceof ConceptStmt.Assign a) {
                if (exprUsesYOutsideYInvCalls(a.value(), yParam, yInvNames, fnByKey, fnByName)) return true;
            } else if (s instanceof ConceptStmt.If iff) {
                if (exprUsesYOutsideYInvCalls(iff.condition(), yParam, yInvNames, fnByKey, fnByName)
                        || usesYOutsideYInvCalls(iff.thenBody(), yParam, yInvNames, fnByKey, fnByName)
                        || usesYOutsideYInvCalls(iff.elseBody(), yParam, yInvNames, fnByKey, fnByName)) return true;
            } else if (s instanceof ConceptStmt.While wh) {
                if (exprUsesYOutsideYInvCalls(wh.condition(), yParam, yInvNames, fnByKey, fnByName)
                        || usesYOutsideYInvCalls(wh.body(), yParam, yInvNames, fnByKey, fnByName)) return true;
            } else if (s instanceof ConceptStmt.Return r && r.value() != null) {
                if (exprUsesYOutsideYInvCalls(r.value(), yParam, yInvNames, fnByKey, fnByName)) return true;
            } else if (s instanceof ConceptStmt.StoreBuffer3D st) {
                if (exprUsesYOutsideYInvCalls(st.linearIndex(), yParam, yInvNames, fnByKey, fnByName)
                        || exprUsesYOutsideYInvCalls(st.value(), yParam, yInvNames, fnByKey, fnByName)) return true;
            }
        }
        return false;
    }

    private static boolean exprUsesYOutsideYInvCalls(
            ConceptExpr e,
            String yParam,
            Set<String> yInvNames,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName) {
        if (e instanceof ConceptExpr.Var v) {
            return yParam.equals(v.name());
        }
        if (e instanceof ConceptExpr.UnaryOp u) {
            return exprUsesYOutsideYInvCalls(u.operand(), yParam, yInvNames, fnByKey, fnByName);
        }
        if (e instanceof ConceptExpr.Cast c) {
            return exprUsesYOutsideYInvCalls(c.operand(), yParam, yInvNames, fnByKey, fnByName);
        }
        if (e instanceof ConceptExpr.BinaryOp b) {
            return exprUsesYOutsideYInvCalls(b.left(), yParam, yInvNames, fnByKey, fnByName)
                    || exprUsesYOutsideYInvCalls(b.right(), yParam, yInvNames, fnByKey, fnByName);
        }
        if (e instanceof ConceptExpr.Ternary t) {
            return exprUsesYOutsideYInvCalls(t.condition(), yParam, yInvNames, fnByKey, fnByName)
                    || exprUsesYOutsideYInvCalls(t.thenExpr(), yParam, yInvNames, fnByKey, fnByName)
                    || exprUsesYOutsideYInvCalls(t.elseExpr(), yParam, yInvNames, fnByKey, fnByName);
        }
        if (e instanceof ConceptExpr.LutLoad l) {
            return exprUsesYOutsideYInvCalls(l.index(), yParam, yInvNames, fnByKey, fnByName);
        }
        if (e instanceof ConceptExpr.MathIntrinsic m) {
            for (ConceptExpr a : m.arguments()) {
                if (exprUsesYOutsideYInvCalls(a, yParam, yInvNames, fnByKey, fnByName)) return true;
            }
            return false;
        }
        if (e instanceof ConceptExpr.Call c) {
            ConceptFunction target = resolveFunction(c, fnByKey, fnByName);
            boolean targetYInv = target != null && yInvNames.contains(target.name());
            for (int i = 0; i < c.arguments().size(); i++) {
                if (targetYInv && i == 1) {
                    continue; // callee ignores arg[1] (Y)
                }
                if (exprUsesYOutsideYInvCalls(c.arguments().get(i), yParam, yInvNames, fnByKey, fnByName)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private static void collectTopoYInvFromStmts(
            List<ConceptStmt> stmts,
            Set<String> yInvNames,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName,
            Set<String> visited,
            List<ConceptFunction> topoOut,
            int maxSlots) {
        if (topoOut.size() >= maxSlots || stmts == null) {
            return;
        }
        for (ConceptStmt s : stmts) {
            if (topoOut.size() >= maxSlots) return;
            if (s instanceof ConceptStmt.VarDecl vd && vd.initializer() != null) {
                collectTopoYInvFromExpr(vd.initializer(), yInvNames, fnByKey, fnByName, visited, topoOut, maxSlots);
            } else if (s instanceof ConceptStmt.Assign a) {
                collectTopoYInvFromExpr(a.value(), yInvNames, fnByKey, fnByName, visited, topoOut, maxSlots);
            } else if (s instanceof ConceptStmt.If iff) {
                collectTopoYInvFromExpr(iff.condition(), yInvNames, fnByKey, fnByName, visited, topoOut, maxSlots);
                collectTopoYInvFromStmts(iff.thenBody(), yInvNames, fnByKey, fnByName, visited, topoOut, maxSlots);
                collectTopoYInvFromStmts(iff.elseBody(), yInvNames, fnByKey, fnByName, visited, topoOut, maxSlots);
            } else if (s instanceof ConceptStmt.While wh) {
                collectTopoYInvFromExpr(wh.condition(), yInvNames, fnByKey, fnByName, visited, topoOut, maxSlots);
                collectTopoYInvFromStmts(wh.body(), yInvNames, fnByKey, fnByName, visited, topoOut, maxSlots);
            } else if (s instanceof ConceptStmt.Return r && r.value() != null) {
                collectTopoYInvFromExpr(r.value(), yInvNames, fnByKey, fnByName, visited, topoOut, maxSlots);
            }
        }
    }

    private static void collectTopoYInvFromExpr(
            ConceptExpr e,
            Set<String> yInvNames,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName,
            Set<String> visited,
            List<ConceptFunction> topoOut,
            int maxSlots) {
        if (topoOut.size() >= maxSlots || e == null) return;
        if (e instanceof ConceptExpr.Call c) {
            for (ConceptExpr a : c.arguments()) {
                collectTopoYInvFromExpr(a, yInvNames, fnByKey, fnByName, visited, topoOut, maxSlots);
            }
            ConceptFunction target = resolveFunction(c, fnByKey, fnByName);
            if (target != null && visited.add(target.name())) {
                collectTopoYInvFromStmts(target.body(), yInvNames, fnByKey, fnByName, visited, topoOut, maxSlots);
                if (topoOut.size() < maxSlots
                        && yInvNames.contains(target.name())
                        && (target.body().size() >= 2 || containsNestedCall(target.body()))) {
                    topoOut.add(target);
                }
            }
        } else if (e instanceof ConceptExpr.UnaryOp u) {
            collectTopoYInvFromExpr(u.operand(), yInvNames, fnByKey, fnByName, visited, topoOut, maxSlots);
        } else if (e instanceof ConceptExpr.Cast c) {
            collectTopoYInvFromExpr(c.operand(), yInvNames, fnByKey, fnByName, visited, topoOut, maxSlots);
        } else if (e instanceof ConceptExpr.BinaryOp b) {
            collectTopoYInvFromExpr(b.left(), yInvNames, fnByKey, fnByName, visited, topoOut, maxSlots);
            collectTopoYInvFromExpr(b.right(), yInvNames, fnByKey, fnByName, visited, topoOut, maxSlots);
        } else if (e instanceof ConceptExpr.Ternary t) {
            collectTopoYInvFromExpr(t.condition(), yInvNames, fnByKey, fnByName, visited, topoOut, maxSlots);
            collectTopoYInvFromExpr(t.thenExpr(), yInvNames, fnByKey, fnByName, visited, topoOut, maxSlots);
            collectTopoYInvFromExpr(t.elseExpr(), yInvNames, fnByKey, fnByName, visited, topoOut, maxSlots);
        } else if (e instanceof ConceptExpr.LutLoad l) {
            collectTopoYInvFromExpr(l.index(), yInvNames, fnByKey, fnByName, visited, topoOut, maxSlots);
        } else if (e instanceof ConceptExpr.MathIntrinsic m) {
            for (ConceptExpr a : m.arguments()) {
                collectTopoYInvFromExpr(a, yInvNames, fnByKey, fnByName, visited, topoOut, maxSlots);
            }
        }
    }

    private static FinalDensitySectionResult emitFinalDensityDeviceSectionWithPlan(
            ConceptModule finalDensityModule,
            WorldgenKernelSwapper.WorldgenFidelityPolicy effPolicy,
            WorldgenKernelSwapper.GpuExactArithmeticMode effExactMode,
            ConstantLutAllocator sharedAllocator,
            boolean enableDiamondMemo
    ) {
        StringBuilder sb = new StringBuilder(1024);
        boolean exactMode = effPolicy != null && effPolicy.isExact();
        boolean useNativeFp64 = exactMode
                && effExactMode != null
                && effExactMode.usesNativeFp64();
        boolean passSpill = sharedAllocator != null && sharedAllocator.globalSpillBufferName != null;
        String spillParam = passSpill
                ? (", " + openClGlobalPtrParam("int", true, sharedAllocator.globalSpillBufferName))
                : "";
        if (finalDensityModule == null
                || finalDensityModule.entryFunction() == null
                || !finalDensityModule.isPureCompute()
                || !finalDensityModule.unmappedReasons().isEmpty()) {
            sb.append("inline float eval_lifted_final_density_ir(int bx, int by, int bz").append(spillParam).append(") {\n")
                    .append("    (void)bx; (void)by; (void)bz;")
                    .append(passSpill ? (" (void)" + sharedAllocator.globalSpillBufferName + ";") : "")
                    .append("\n    return 0.0f;\n")
                    .append("}\n");
            if (useNativeFp64) {
                sb.append("#if defined(LEAFGEN_EXACT_NATIVE_FP64) && defined(cl_khr_fp64)\n")
                        .append("inline double eval_lifted_final_density_ir_f64(int bx, int by, int bz").append(spillParam).append(") {\n")
                        .append("    (void)bx; (void)by; (void)bz;")
                        .append(passSpill ? (" (void)" + sharedAllocator.globalSpillBufferName + ";") : "")
                        .append("\n    return 0.0;\n")
                        .append("}\n")
                        .append("#endif\n");
            }
            return new FinalDensitySectionResult(sb.toString(), DiamondMemoPlan.EMPTY);
        }
        GpuExactArithmeticMode arithMode = useNativeFp64
                ? GpuExactArithmeticMode.NATIVE_FP64
                : (exactMode && effExactMode == WorldgenKernelSwapper.GpuExactArithmeticMode.INT64_Q32_FP32
                        ? GpuExactArithmeticMode.INT64_Q32_FP32
                        : GpuExactArithmeticMode.DOWNCAST_FP32);
        FloatingPointPrecision precision = arithMode.toFloatingPointPrecision();
        EmitOptions emitOpts = new EmitOptions(
                precision,
                false,
                ConceptOptimizerPass.OptimizationConfig.forJvmBytecode(),
                arithMode,
                BatchLayout.SINGLE_POINT);
        ConceptModule preOptForMemo = enableDiamondMemo && arithMode.usesInt64Q32()
                ? ConceptOptimizerPass.optimize(finalDensityModule, emitOpts.optimizationConfig())
                : finalDensityModule;
        Q32LoweringPlan preQ32Plan = arithMode.usesInt64Q32()
                ? ConceptOptimizerPass.analyzeQ32Lowering(preOptForMemo)
                : Q32LoweringPlan.empty();
        DiamondMemoPlan memoPlan = enableDiamondMemo
                ? analyzeDiamondMemoPlan(preOptForMemo, arithMode, preQ32Plan)
                : DiamondMemoPlan.EMPTY;
        TranspiledArtifact artifact = new OpenClBackendEmitter()
                .emitWithAllocatorAndMemo(preOptForMemo, emitOpts, "fd_", sharedAllocator, false, memoPlan);
        ConceptModule lowered = artifact.optimizedModule();
        Q32LoweringPlan q32Plan = arithMode.usesInt64Q32()
                ? ConceptOptimizerPass.analyzeQ32Lowering(lowered)
                : Q32LoweringPlan.empty();

        for (String line : artifact.sourceCode().split("\\R")) {
            if (line.startsWith("#pragma OPENCL")
                    || line.startsWith("// Auto-generated OpenCL")
                    || line.startsWith("// Floating-point mode:")) {
                continue;
            }
            sb.append(line).append('\n');
        }

        String memoParams = memoPlan.isActive()
                ? (", uint* memo_mask, " + memoPlan.elemType() + "* memo_val")
                : "";
        ConceptFunction entry = lowered.entryFunction();
        StringBuilder callArgs = new StringBuilder(64);
        List<ConceptParam> params = entry.parameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                callArgs.append(", ");
            }
            ConceptParam p = params.get(i);
            String rawCoord = switch (i) {
                case 0 -> "bx";
                case 1 -> "by";
                case 2 -> "bz";
                default -> "0";
            };
            if (arithMode.usesInt64Q32() && q32Plan.isQ32Param(entry, i)) {
                callArgs.append("q32_from_i32(").append(rawCoord).append(')');
            } else if (p.type() == ConceptType.F64 && useNativeFp64) {
                callArgs.append("(double)").append(rawCoord);
            } else if (p.type() == ConceptType.F64 || p.type() == ConceptType.F32) {
                callArgs.append("(float)").append(rawCoord);
            } else if (p.type() == ConceptType.I64) {
                callArgs.append("(long)").append(rawCoord);
            } else {
                callArgs.append(rawCoord);
            }
        }
        if (passSpill) {
            if (!params.isEmpty()) {
                callArgs.append(", ");
            }
            callArgs.append(sharedAllocator.globalSpillBufferName);
        }
        if (memoPlan.isActive()) {
            if (!params.isEmpty() || passSpill) {
                callArgs.append(", ");
            }
            callArgs.append("memo_mask, memo_val");
        }
        String rawCall = "fd_" + entry.name() + "(" + callArgs + ")";
        boolean returnsQ32 = arithMode.usesInt64Q32() && q32Plan.isQ32Return(entry);
        String floatCall = returnsQ32 ? ("q32_to_f32(" + rawCall + ")") : ("(float)(" + rawCall + ")");
        sb.append("inline float eval_lifted_final_density_ir(int bx, int by, int bz")
                .append(spillParam)
                .append(memoParams)
                .append(") {\n")
                .append("    (void)bx; (void)by; (void)bz;")
                .append(passSpill ? (" (void)" + sharedAllocator.globalSpillBufferName + ";") : "")
                .append(memoPlan.isActive() ? " (void)memo_mask; (void)memo_val;" : "")
                .append("\n    return ").append(floatCall).append(";\n")
                .append("}\n");
        if (useNativeFp64) {
            sb.append("#if defined(LEAFGEN_EXACT_NATIVE_FP64) && defined(cl_khr_fp64)\n")
                    .append("inline double eval_lifted_final_density_ir_f64(int bx, int by, int bz")
                    .append(spillParam)
                    .append(memoParams)
                    .append(") {\n")
                    .append("    (void)bx; (void)by; (void)bz;")
                    .append(passSpill ? (" (void)" + sharedAllocator.globalSpillBufferName + ";") : "")
                    .append(memoPlan.isActive() ? " (void)memo_mask; (void)memo_val;" : "")
                    .append("\n    return (double)(").append(rawCall).append(");\n")
                    .append("}\n")
                    .append("#endif\n");
        }
        return new FinalDensitySectionResult(sb.toString(), memoPlan);
    }

    public static String emitDimensionCapabilityKernelSource(
            LiftedWorldgenCapabilityTables.DimensionCapabilitySpec spec) {
        return emitDimensionCapabilityKernelSource(
                spec, true, WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE);
    }

    public static String emitDimensionCapabilityKernelSource(
            LiftedWorldgenCapabilityTables.DimensionCapabilitySpec spec,
            boolean includeGpuRtree) {
        return emitDimensionCapabilityKernelSource(
                spec, includeGpuRtree, WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE);
    }

    public static String emitDimensionCapabilityKernelSource(
            LiftedWorldgenCapabilityTables.DimensionCapabilitySpec spec,
            boolean includeGpuRtree,
            WorldgenKernelSwapper.WorldgenFidelityPolicy fidelityPolicy) {
        return emitDimensionCapabilityKernelSource(
                spec,
                includeGpuRtree,
                fidelityPolicy,
                WorldgenKernelSwapper.GpuExactArithmeticMode.NATIVE_FP64);
    }

    public static String emitDimensionCapabilityKernelSource(
            LiftedWorldgenCapabilityTables.DimensionCapabilitySpec spec,
            boolean includeGpuRtree,
            WorldgenKernelSwapper.WorldgenFidelityPolicy fidelityPolicy,
            WorldgenKernelSwapper.GpuExactArithmeticMode gpuExactMode) {
        return emitDimensionCapabilityKernelProgram(spec, includeGpuRtree, fidelityPolicy, gpuExactMode).sourceCode();
    }

    /**
     * Emits the complete OpenCL capability kernel program along with any packed
     * {@code __global const int[]} words spilled past {@link #MAX_CONSTANT_BUDGET_BYTES}.
     */
    public static CapabilityKernelProgram emitDimensionCapabilityKernelProgram(
            LiftedWorldgenCapabilityTables.DimensionCapabilitySpec spec,
            boolean includeGpuRtree,
            WorldgenKernelSwapper.WorldgenFidelityPolicy fidelityPolicy,
            WorldgenKernelSwapper.GpuExactArithmeticMode gpuExactMode) {
        WorldgenKernelSwapper.WorldgenFidelityPolicy effPolicy =
                fidelityPolicy == null
                        ? WorldgenKernelSwapper.WorldgenFidelityPolicy.VANILLA_LIKE
                        : fidelityPolicy;
        WorldgenKernelSwapper.GpuExactArithmeticMode effExactMode =
                gpuExactMode == null
                        ? WorldgenKernelSwapper.GpuExactArithmeticMode.NATIVE_FP64
                        : gpuExactMode;
        boolean exactMode = effPolicy.isExact();
        boolean useNativeFp64 = exactMode && effExactMode.usesNativeFp64();
        boolean useHybridSignBand = exactMode && effExactMode.refinesBoundarySignBand();
        LiftedWorldgenCapabilityTables.RouterVeinSpec rv =
                spec.routerVeinSpec() != null
                        ? spec.routerVeinSpec()
                        : LiftedWorldgenCapabilityTables.RouterVeinSpec.DISABLED;
        LiftedWorldgenCapabilityTables.GpuRouterNoiseTable gpuNoises = spec.gpuNoiseTable();
        int condLen = (gpuNoises != null && gpuNoises.conditionNoises() != null)
                ? gpuNoises.conditionNoises().length
                : 0;
        int[] noiseIds6 = new int[6];
        int[] condNoiseIds = new int[condLen];
        int[] condNoise3d = new int[condLen];
        String constantNoiseSrc = emitCapabilityConstantNoiseTables(
                gpuNoises, noiseIds6, condNoiseIds, condNoise3d, effPolicy, effExactMode);
        int estimatedCapNoiseBytes = estimateCapabilityNoiseConstantBytes(gpuNoises, exactMode, useNativeFp64);
        ConstantLutAllocator sharedLutAllocator =
                new ConstantLutAllocator(MAX_CONSTANT_BUDGET_BYTES, estimatedCapNoiseBytes, "g_lut_spill");
        int cellWPre = Math.max(1, spec.cellWidth());
        int cellHPre = Math.max(1, spec.cellHeight());
        int cellsXPre = Math.max(1, (16 + cellWPre - 1) / cellWPre);
        int cellsZPre = Math.max(1, (16 + cellWPre - 1) / cellWPre);
        int cellsYPre = Math.max(1, Math.max(1, spec.noiseHeight()) / cellHPre);
        int cols2dPre = (cellsXPre + 1) * (cellsZPre + 1);
        boolean gpuCornersSupported = spec.hasFinalDensityModule()
                && (cols2dPre * (cellsYPre + 1)) <= 4096;
        FinalDensitySectionResult fdRes = emitFinalDensityDeviceSectionWithPlan(
                spec.finalDensityModule(),
                effPolicy,
                effExactMode,
                sharedLutAllocator,
                gpuCornersSupported && cols2dPre <= 256);
        String finalDensityIrSrc = fdRes.sourceCode();
        DiamondMemoPlan memoPlan = fdRes.memoPlan();
        String surfaceRuleIrSrc = emitSurfaceRuleDeviceSection(
                spec.surfaceRuleModule(), effPolicy, effExactMode, sharedLutAllocator);
        int barrierNoiseId = noiseIds6[0];
        int veinToggleId = noiseIds6[1];
        int veinRidgedId = noiseIds6[2];
        int veinGapId = noiseIds6[3];
        int tempNoiseId = noiseIds6[4];
        int veinRidgedBId = noiseIds6[5];

        int chunkMinY = spec.chunkMinY();
        int secCount = Math.max(1, spec.chunkSections());
        int noiseMinY = spec.noiseMinY();
        int noiseHeight = Math.max(1, spec.noiseHeight());
        int noiseTopY = noiseMinY + noiseHeight - 1;
        int cellW = Math.max(1, spec.cellWidth());
        int cellH = Math.max(1, spec.cellHeight());
        int cellsX = Math.max(1, (16 + cellW - 1) / cellW);
        int cellsZ = Math.max(1, (16 + cellW - 1) / cellW);
        int cellsY = Math.max(1, noiseHeight / cellH);
        int strideZ = cellsY + 1;
        int strideX = (cellsZ + 1) * strideZ;
        int cornersPerChunk = (cellsX + 1) * strideX;
        int minBoundY = Math.max(chunkMinY, noiseMinY);
        int maxBoundY = Math.min(chunkMinY + (secCount << 4) - 1, noiseTopY);
        int sealMinY = Math.max(chunkMinY, minBoundY - 1);
        LiftedWorldgenCapabilityTables.CarverSpec[] carverSpecsArr = spec.carverSpecs();
        int carverCount = (carverSpecsArr != null) ? carverSpecsArr.length : 0;
        String carverConstSrc = emitCapabilityCarverConstants(carverSpecsArr);

        String sourceCode = emitCapabilityRTreeAndSurfaceHelpers()
                + constantNoiseSrc
                + carverConstSrc
                + finalDensityIrSrc
                + surfaceRuleIrSrc
                + "\n"
                + openClKernelHeader(
                        null,
                        "evaluate_evaluate_worldgen_voxel_grid3d",
                        openClGlobalPtrParam("int", false, "out_voxels"),
                        "const int dim_x",
                        "const int dim_y",
                        "const int dim_z",
                        "const float origin_x",
                        "const float origin_y",
                        "const float origin_z",
                        "const float scale",
                        "const long world_seed")
                + "    const int gx = (int)get_global_id(0);\n"
                + "    const int gy = (int)get_global_id(1);\n"
                + "    const int gz = (int)get_global_id(2);\n"
                + "    if (gx >= dim_x || gy >= dim_y || gz >= dim_z) return;\n"
                + "    const int idx = (gy * dim_z + gz) * dim_x + gx;\n"
                + "    const int bx = (int)floor(origin_x + (float)gx * scale);\n"
                + "    const int wy = (int)floor(origin_y + (float)gy * scale);\n"
                + "    const int bz = (int)floor(origin_z + (float)gz * scale);\n"
                + "    const int default_block = " + spec.defaultBlockSlot() + ";\n"
                + "    const int default_fluid = " + spec.defaultFluidSlot() + ";\n"
                + "    const int lava_slot = " + spec.lavaFluidSlot() + ";\n"
                + "    const int sea_level = " + spec.seaLevel() + ";\n"
                + "    const int deep_lava_y = " + spec.deepLavaFloorY() + ";\n"
                + "    const int noise_min_y = " + noiseMinY + ";\n"
                + "    const int noise_top_y = " + noiseTopY + ";\n"
                + "    const int prelim_y = eval_preliminary_surface_y((" + openClGlobalPtr("float", true) + ")0, 0, noise_min_y, "
                + cellH + ", noise_top_y);\n"
                + "    const int pos_hash = mix_column_seed_i32(world_seed, bx, bz) ^ (wy * 31);\n"
                + "    int vox = eval_lifted_aquifer_voxel(0.0f, bx, wy, bz, default_block, default_fluid, lava_slot, sea_level, deep_lava_y, prelim_y, 0.0f, 0.0f, 0.0f, 0.0f, 0);\n"
                + (rv.enabled()
                        ? ("    vox = eval_lifted_router_vein(vox, default_block, wy, 0.0f, 1.0f, 0.0f, "
                                + rv.upperMinY() + ", " + rv.upperMaxY() + ", " + rv.upperOreSlot() + ", "
                                + rv.upperRawBlockSlot() + ", " + rv.upperFillerSlot() + ", "
                                + rv.lowerMinY() + ", " + rv.lowerMaxY() + ", " + rv.lowerOreSlot() + ", "
                                + rv.lowerRawBlockSlot() + ", " + rv.lowerFillerSlot() + ", pos_hash);\n")
                        : "")
                + "    vox = eval_hydrostatic_seal_voxel(vox, 0, 0, 0, 0, 0, default_block, default_fluid, lava_slot);\n"
                + "    vox = eval_lifted_surface_rule_ir(vox, default_block, bx, wy, bz, 1, 2, -2147483647, 3, 0, prelim_y, 0, 0, 0, 0.0f, 0.5f, "
                + "(" + openClGlobalPtrParam("int", true, "").trim() + ")0);\n"
                + "    out_voxels[idx] = vox;\n"
                + "}\n\n"
                + openClKernelHeader(
                        "__attribute__((reqd_work_group_size(16, 16, 1)))",
                        "evaluate_dimension_capability_chunk",
                        openClGlobalPtrParam("int", false, "out_sections"),
                        openClGlobalPtrParam("int", false, "io_quart_biomes"),
                        openClGlobalPtrParam("int", false, "out_top_surface_y"),
                        openClGlobalPtrParam("int", true, "params"),
                        openClGlobalPtrParam("int", true, "rtree_nodes"),
                        openClGlobalPtrParam("float", true, "quart_climate6"),
                        openClGlobalPtrParam("float", true, "corners"),
                        openClGlobalPtrParam("float", true, "prelim_col_dens"),
                        openClGlobalPtrParam("int", true, "prelim_base_y"),
                        openClGlobalPtrParam("int", true, "prelim_fallback_y"),
                        openClGlobalPtrParam("float", true, "barrier_grid"),
                        openClGlobalPtrParam("float", true, "sub_aquifer_cells"),
                        openClGlobalPtrParam("float", true, "vein_triples"),
                        openClGlobalPtrParam("int", true, "rule_ops"),
                        openClGlobalPtrParam("float", true, "temp_voxels"),
                        openClGlobalPtrParam("float", true, "cond_noise_grid"),
                        "const long world_seed")
                + "    __local int s_raw_top_y[256];\n"
                + "    __local int s_prelim_y[256];\n"
                + "    __local int s_layer_vox[256];\n"
                + (gpuCornersSupported
                        ? ((useNativeFp64 && cornersPerChunk <= 2048)
                                ? ("#if defined(LEAFGEN_EXACT_NATIVE_FP64) && defined(cl_khr_fp64)\n"
                                        + "    __local double s_corners[" + cornersPerChunk + "];\n"
                                        + "#else\n"
                                        + "    __local float s_corners[" + cornersPerChunk + "];\n"
                                        + "#endif\n")
                                : ("    __local float s_corners[" + cornersPerChunk + "];\n"))
                        : "")
                + "    __local int s_est_basin_floor;\n"
                + "    __local int s_chunk_active_top_y;\n"
                + (carverCount > 0
                        ? ("    __local float s_carv_wx[256];\n"
                                + "    __local float s_carv_wy[256];\n"
                                + "    __local float s_carv_wz[256];\n"
                                + "    __local float s_carv_irxz2[256];\n"
                                + "    __local float s_carv_iry2[256];\n"
                                + "    __local float s_carv_rxz[256];\n"
                                + "    __local float s_carv_ry[256];\n"
                                + "    __local int s_carv_lava[256];\n"
                                + "    __local int s_carv_count;\n")
                        : "")
                + "    const int lx = (int)get_local_id(0);\n"
                + "    const int lz = (int)get_local_id(1);\n"
                + "    const int batch_idx = (int)get_group_id(2);\n"
                + "    const int col = (lz << 4) | lx;\n"
                + openClGlobalPtrDecl("int", true, "cparams", "params + (batch_idx << 5)")
                + "    const int rtree_node_count = cparams[0];\n"
                + "    const int has_quart_climate = cparams[1];\n"
                + "    const int has_prelim_channel = cparams[2];\n"
                + "    const int prelim_stride = cparams[3];\n"
                + "    const int has_barrier_noise = cparams[4];\n"
                + "    const int barrier_stride = cparams[5];\n"
                + "    const int has_sub_aquifer = cparams[6];\n"
                + "    const int sub_min_gy = cparams[7];\n"
                + "    const int eval_veins = cparams[8];\n"
                + "    const int apply_surface_rules = cparams[9];\n"
                + "    const int ir_flags = cparams[10];\n"
                + "    const int has_gpu_corners = (ir_flags & 1);\n"
                + "    const int skip_biome_io = (ir_flags & 2);\n"
                + "    const int add_beard_delta = (ir_flags & 4);\n"
                + "    const int apply_gpu_carvers = (ir_flags & 8);\n"
                + "    const int has_temp_channel = cparams[11];\n"
                + "    const int cond_noise_count = cparams[12];\n"
                + "    const int chunk_x = cparams[13];\n"
                + "    const int chunk_z = cparams[14];\n"
                + "    const int has_halo_6x6 = cparams[15];\n"
                + "    const long zoom_seed = (has_halo_6x6 != 0) ? (long)(((ulong)(uint)cparams[16]) | (((ulong)(uint)cparams[17]) << 32)) : 0L;\n"
                + "    const int cond_2d_only = cparams[18];\n"
                + "    const int temp_2d_only = cparams[19];\n"
                + "    const int vein_min_y = cparams[20];\n"
                + "    const int vein_span_y = cparams[21];\n"
                + "    const int sub_grid_size_y = cparams[22];\n"
                + "    const int sub_skip_above_y = cparams[23];\n"
                + "    const int sub_min_gx = cparams[24];\n"
                + "    const int sub_min_gz = cparams[25];\n"
                + "    const int sub_packed_dims = cparams[26];\n"
                + "    const int sub_grid_size_x = (sub_packed_dims & 255) > 0 ? (sub_packed_dims & 255) : 4;\n"
                + "    const int sub_grid_size_z = ((sub_packed_dims >> 8) & 255) > 0 ? ((sub_packed_dims >> 8) & 255) : 4;\n"
                + "    const int sub_x_sp = ((sub_packed_dims >> 16) & 255) > 0 ? ((sub_packed_dims >> 16) & 255) : 16;\n"
                + "    const int sub_y_sp = ((sub_packed_dims >> 24) & 255) > 0 ? ((sub_packed_dims >> 24) & 255) : 12;\n"
                + "    const float sub_sim_scale = (cparams[27] != 0) ? as_float(cparams[27]) : 25.0f;\n"
                + "    const int chunk_min_y = " + chunkMinY + ";\n"
                + "    const int sec_count = " + secCount + ";\n"
                + "    const int noise_min_y = " + noiseMinY + ";\n"
                + "    const int noise_top_y = " + noiseTopY + ";\n"
                + "    const int cell_w = " + cellW + ";\n"
                + "    const int cell_h = " + cellH + ";\n"
                + "    const int cells_y = " + cellsY + ";\n"
                + "    const int stride_z = " + strideZ + ";\n"
                + "    const int stride_x = " + strideX + ";\n"
                + "    const int sea_level = " + spec.seaLevel() + ";\n"
                + "    const int default_block = " + spec.defaultBlockSlot() + ";\n"
                + "    const int default_fluid = " + spec.defaultFluidSlot() + ";\n"
                + "    const int lava_slot = " + spec.lavaFluidSlot() + ";\n"
                + "    const int aquifers_enabled = " + (spec.aquifersEnabled() ? 1 : 0) + ";\n"
                + "    const int deep_lava_enabled = " + ((spec.aquifersEnabled() && spec.lavaFluidSlot() > 0) ? 1 : 0) + ";\n"
                + "    const int deep_lava_y = " + spec.deepLavaFloorY() + ";\n"
                + "    const int min_bound_y = " + minBoundY + ";\n"
                + "    const int max_bound_y = " + maxBoundY + ";\n"
                + "    const int seal_min_y = " + sealMinY + ";\n"
                + "    const int barrier_noise_id = " + barrierNoiseId + ";\n"
                + "    const int vein_toggle_id = " + veinToggleId + ";\n"
                + "    const int vein_ridged_id = " + veinRidgedId + ";\n"
                + "    const int vein_ridged_b_id = " + veinRidgedBId + ";\n"
                + "    const int vein_gap_id = " + veinGapId + ";\n"
                + "    const int temp_noise_id = " + tempNoiseId + ";\n"
                + "    const int total_voxels = sec_count << 12;\n"
                + "    const int quarts_y = sec_count << 2;\n"
                + "    const int total_quarts = (has_halo_6x6 != 0) ? (quarts_y * 36) : (quarts_y << 4);\n"
                + "    const int quarts_stride = (cparams[28] > 0) ? cparams[28] : total_quarts;\n"
                + "    const int top_y_stride = (cparams[29] > 0) ? cparams[29] : 256;\n"
                + openClGlobalPtrDecl("int", false, "c_out_sections", "out_sections + batch_idx * total_voxels")
                + openClGlobalPtrDecl("int", false, "c_quart_biomes", "io_quart_biomes + batch_idx * quarts_stride")
                + openClGlobalPtrDecl("int", false, "c_top_y", "out_top_surface_y + batch_idx * top_y_stride")
                + openClGlobalPtrDecl("float", true, "c_clim6", "quart_climate6 + batch_idx * (quarts_stride * 6)")
                + openClGlobalPtrDecl("float", true, "c_corners", "corners + batch_idx * " + cornersPerChunk)
                + openClGlobalPtrDecl("float", true, "c_prelim_dens", "prelim_col_dens + cparams[30]")
                + openClGlobalPtrDecl("int", true, "c_prelim_base", "prelim_base_y + (batch_idx << 8)")
                + openClGlobalPtrDecl("int", true, "c_prelim_fb", "prelim_fallback_y + (batch_idx << 8)")
                + openClGlobalPtrDecl("float", true, "c_sub_aq", "sub_aquifer_cells + cparams[31]")
                + openClGlobalPtrDecl("float", true, "c_barrier", "barrier_grid + batch_idx * ((has_barrier_noise == 2) ? 256 : (256 * barrier_stride))")
                + openClGlobalPtrDecl("float", true, "c_veins", "vein_triples + batch_idx * ((vein_span_y > 0 ? (vein_span_y << 8) : total_voxels) * 3)")
                + openClGlobalPtrDecl("float", true, "c_temp", "temp_voxels + batch_idx * ((temp_2d_only != 0) ? 256 : total_voxels)")
                + openClGlobalPtrDecl("float", true, "c_cond", "cond_noise_grid + batch_idx * (((cond_2d_only != 0) ? 256 : total_voxels) * cond_noise_count)")
                + "    for (int vi = col; vi < total_voxels; vi += 256) {\n"
                + "        c_out_sections[vi] = 0;\n"
                + "    }\n"
                + (gpuCornersSupported
                        ? ((memoPlan.isActive()
                                ? ("    if (has_gpu_corners != 0) {\n"
                                        + "        int bands_per_col = 256 / " + cols2dPre + ";\n"
                                        + "        if (bands_per_col < 1) bands_per_col = 1;\n"
                                        + "        int active_threads = " + cols2dPre + " * bands_per_col;\n"
                                        + "        if (col < active_threads) {\n"
                                        + "            int col2d = col / bands_per_col;\n"
                                        + "            int band_y = col - col2d * bands_per_col;\n"
                                        + "            int gx_c = col2d / " + (cellsZ + 1) + ";\n"
                                        + "            int gz_c = col2d - gx_c * " + (cellsZ + 1) + ";\n"
                                        + "            int c_bx = (chunk_x << 4) + gx_c * cell_w;\n"
                                        + "            int c_bz = (chunk_z << 4) + gz_c * cell_w;\n"
                                        + "            int y_per_band = (" + strideZ + " + bands_per_col - 1) / bands_per_col;\n"
                                        + "            int gy_start = band_y * y_per_band;\n"
                                        + "            int gy_end = gy_start + y_per_band;\n"
                                        + "            if (gy_end > " + strideZ + ") gy_end = " + strideZ + ";\n"
                                        + "            uint memo_mask = 0u;\n"
                                        + "            " + memoPlan.elemType() + " memo_val[" + memoPlan.slotCount() + "];\n"
                                        + "            int base_ci = gx_c * stride_x + gz_c * stride_z;\n"
                                        + "            for (int gy_c = gy_start; gy_c < gy_end; gy_c++) {\n"
                                        + "                memo_mask &= " + Integer.toUnsignedString(memoPlan.keep2dMask()) + "u;\n"
                                        + "                int c_by = noise_min_y + gy_c * cell_h;\n"
                                        + "                int ci_pos = base_ci + gy_c;\n"
                                        + ((useNativeFp64 && cornersPerChunk <= 2048)
                                                ? ("#if defined(LEAFGEN_EXACT_NATIVE_FP64) && defined(cl_khr_fp64)\n"
                                                        + "                double cv = eval_lifted_final_density_ir_f64(c_bx, c_by, c_bz, rule_ops, &memo_mask, memo_val);\n"
                                                        + "                if (add_beard_delta != 0) cv += (double)c_corners[ci_pos];\n"
                                                        + "                s_corners[ci_pos] = cv;\n"
                                                        + "#else\n"
                                                        + "                float cv = eval_lifted_final_density_ir(c_bx, c_by, c_bz, rule_ops, &memo_mask, memo_val);\n"
                                                        + "                if (add_beard_delta != 0) cv += c_corners[ci_pos];\n"
                                                        + "                s_corners[ci_pos] = cv;\n"
                                                        + "#endif\n")
                                                : ("                float cv = eval_lifted_final_density_ir(c_bx, c_by, c_bz, rule_ops, &memo_mask, memo_val);\n"
                                                        + "                if (add_beard_delta != 0) cv += c_corners[ci_pos];\n"
                                                        + "                s_corners[ci_pos] = cv;\n"))
                                        + "            }\n"
                                        + "        }\n"
                                        + "    } else {\n")
                                : ("    if (has_gpu_corners != 0) {\n"
                                        + "        for (int ci = col; ci < " + cornersPerChunk + "; ci += 256) {\n"
                                        + "            int gx_c = ci / stride_x;\n"
                                        + "            int rem_c = ci - gx_c * stride_x;\n"
                                        + "            int gz_c = rem_c / stride_z;\n"
                                        + "            int gy_c = rem_c - gz_c * stride_z;\n"
                                        + "            int c_bx = (chunk_x << 4) + gx_c * cell_w;\n"
                                        + "            int c_by = noise_min_y + gy_c * cell_h;\n"
                                        + "            int c_bz = (chunk_z << 4) + gz_c * cell_w;\n"
                                        + ((useNativeFp64 && cornersPerChunk <= 2048)
                                                ? ("#if defined(LEAFGEN_EXACT_NATIVE_FP64) && defined(cl_khr_fp64)\n"
                                                        + "            double cv = eval_lifted_final_density_ir_f64(c_bx, c_by, c_bz, rule_ops);\n"
                                                        + "            if (add_beard_delta != 0) cv += (double)c_corners[ci];\n"
                                                        + "            s_corners[ci] = cv;\n"
                                                        + "#else\n"
                                                        + "            float cv = eval_lifted_final_density_ir(c_bx, c_by, c_bz, rule_ops);\n"
                                                        + "            if (add_beard_delta != 0) cv += c_corners[ci];\n"
                                                        + "            s_corners[ci] = cv;\n"
                                                        + "#endif\n")
                                                : ("            float cv = eval_lifted_final_density_ir(c_bx, c_by, c_bz, rule_ops);\n"
                                                        + "            if (add_beard_delta != 0) cv += c_corners[ci];\n"
                                                        + "            s_corners[ci] = cv;\n"))
                                        + "        }\n"
                                        + "    } else {\n"))
                                + "        for (int ci = col; ci < " + cornersPerChunk + "; ci += 256) {\n"
                                + ((useNativeFp64 && cornersPerChunk <= 2048)
                                        ? ("#if defined(LEAFGEN_EXACT_NATIVE_FP64) && defined(cl_khr_fp64)\n"
                                                + "            s_corners[ci] = (double)c_corners[ci];\n"
                                                + "#else\n"
                                                + "            s_corners[ci] = c_corners[ci];\n"
                                                + "#endif\n")
                                        : "            s_corners[ci] = c_corners[ci];\n")
                                + "        }\n"
                                + "    }\n"
                                + "    barrier(CLK_LOCAL_MEM_FENCE);\n")
                        : "    (void)has_gpu_corners; (void)add_beard_delta;\n")
                + (includeGpuRtree
                        ? ("    if (skip_biome_io == 0 && has_quart_climate != 0 && rtree_node_count > 0) {\n"
                                + "        int q_slice_stride = (has_halo_6x6 != 0) ? 36 : 16;\n"
                                + "        int y_bands = 256 / q_slice_stride;\n"
                                + "        int band_idx = col / q_slice_stride;\n"
                                + "        int slice_col = col - band_idx * q_slice_stride;\n"
                                + "        if (band_idx < y_bands) {\n"
                                + "            int qy_per_band = (quarts_y + y_bands - 1) / y_bands;\n"
                                + "            int qy_start = band_idx * qy_per_band;\n"
                                + "            int qy_end = qy_start + qy_per_band;\n"
                                + "            if (qy_end > quarts_y) qy_end = quarts_y;\n"
                                + "            int hint_leaf = -1;\n"
                                + "            int prev_valid = 0;\n"
                                + "            int p0 = 0, p1 = 0, p2 = 0, p3 = 0, p4 = 0, p5 = 0, prev_ord = 0;\n"
                                + "            for (int qy = qy_start; qy < qy_end; qy++) {\n"
                                + "                int qi = qy * q_slice_stride + slice_col;\n"
                                + "                int qb = qi * 6;\n"
                                + "                int q0 = (int)(c_clim6[qb] * 10000.0f);\n"
                                + "                int q1 = (int)(c_clim6[qb + 1] * 10000.0f);\n"
                                + "                int q2 = (int)(c_clim6[qb + 2] * 10000.0f);\n"
                                + "                int q3 = (int)(c_clim6[qb + 3] * 10000.0f);\n"
                                + "                int q4 = (int)(c_clim6[qb + 4] * 10000.0f);\n"
                                + "                int q5 = (int)(c_clim6[qb + 5] * 10000.0f);\n"
                                + "                if (prev_valid != 0 && q0 == p0 && q1 == p1 && q2 == p2 && q3 == p3 && q4 == p4 && q5 == p5) {\n"
                                + "                    c_quart_biomes[qi] = prev_ord;\n"
                                + "                } else {\n"
                                + "                    prev_ord = eval_rtree_biome_ord_hinted(rtree_nodes, rtree_node_count, q0, q1, q2, q3, q4, q5, &hint_leaf);\n"
                                + "                    c_quart_biomes[qi] = prev_ord;\n"
                                + "                    p0 = q0; p1 = q1; p2 = q2; p3 = q3; p4 = q4; p5 = q5;\n"
                                + "                    prev_valid = 1;\n"
                                + "                }\n"
                                + "            }\n"
                                + "        }\n"
                                + "    }\n"
                                + "    barrier(CLK_GLOBAL_MEM_FENCE);\n")
                        : "    (void)rtree_nodes; (void)c_clim6; (void)has_quart_climate; (void)rtree_node_count; (void)rule_ops; (void)skip_biome_io;\n    barrier(CLK_GLOBAL_MEM_FENCE);\n")
                + "    const int bx = (chunk_x << 4) + lx;\n"
                + "    const int bz = (chunk_z << 4) + lz;\n"
                + "    const int col_hash = mix_column_seed_i32(world_seed, bx, bz);\n"
                + "    const int gx = lx / cell_w;\n"
                + "    const int dx = lx - gx * cell_w;\n"
                + "    const int gz = lz / cell_w;\n"
                + "    const int dz = lz - gz * cell_w;\n"
                + "    const float inv_cell_w = 1.0f / (float)cell_w;\n"
                + "    const float inv_cell_h = 1.0f / (float)cell_h;\n"
                + "    const float fx = (float)dx * inv_cell_w;\n"
                + "    const float fz = (float)dz * inv_cell_w;\n"
                + "    int raw_top_y = chunk_min_y - 1;\n"
                + (exactMode ? "    int uncertain_sign_flag = 0;\n" : "")
                + "    for (int cy = cells_y - 1; cy >= 0; cy--) {\n"
                + "        int c00 = gx * stride_x + gz * stride_z + cy;\n"
                + "        int c10 = (gx + 1) * stride_x + gz * stride_z + cy;\n"
                + "        int c01 = gx * stride_x + (gz + 1) * stride_z + cy;\n"
                + "        int c11 = (gx + 1) * stride_x + (gz + 1) * stride_z + cy;\n"
                + (gpuCornersSupported
                        ? ((useNativeFp64 && cornersPerChunk <= 2048)
                                ? ("#if defined(LEAFGEN_EXACT_NATIVE_FP64) && defined(cl_khr_fp64)\n"
                                        + "        double v000 = s_corners[c00], v001 = s_corners[c00 + 1];\n"
                                        + "        double v100 = s_corners[c10], v101 = s_corners[c10 + 1];\n"
                                        + "        double v010 = s_corners[c01], v011 = s_corners[c01 + 1];\n"
                                        + "        double v110 = s_corners[c11], v111 = s_corners[c11 + 1];\n"
                                        + "#else\n"
                                        + "        float v000 = s_corners[c00], v001 = s_corners[c00 + 1];\n"
                                        + "        float v100 = s_corners[c10], v101 = s_corners[c10 + 1];\n"
                                        + "        float v010 = s_corners[c01], v011 = s_corners[c01 + 1];\n"
                                        + "        float v110 = s_corners[c11], v111 = s_corners[c11 + 1];\n"
                                        + "#endif\n")
                                : ("        float v000 = s_corners[c00], v001 = s_corners[c00 + 1];\n"
                                        + "        float v100 = s_corners[c10], v101 = s_corners[c10 + 1];\n"
                                        + "        float v010 = s_corners[c01], v011 = s_corners[c01 + 1];\n"
                                        + "        float v110 = s_corners[c11], v111 = s_corners[c11 + 1];\n"))
                        : ("        float v000 = c_corners[c00], v001 = c_corners[c00 + 1];\n"
                                + "        float v100 = c_corners[c10], v101 = c_corners[c10 + 1];\n"
                                + "        float v010 = c_corners[c01], v011 = c_corners[c01 + 1];\n"
                                + "        float v110 = c_corners[c11], v111 = c_corners[c11 + 1];\n"))
                + "        int cell_base_y = noise_min_y + cy * cell_h;\n"
                + (!exactMode
                        ? ("        float min_c = fmin(fmin(fmin(v000, v001), fmin(v100, v101)), fmin(fmin(v010, v011), fmin(v110, v111)));\n"
                                + "        float max_c = fmax(fmax(fmax(v000, v001), fmax(v100, v101)), fmax(fmax(v010, v011), fmax(v110, v111)));\n"
                                + "        if (min_c > 0.0f && eval_veins == 0) {\n"
                                + "            for (int dy = cell_h - 1; dy >= 0; dy--) {\n"
                                + "                int wy = cell_base_y + dy;\n"
                                + "                int s_idx = (wy - chunk_min_y) >> 4;\n"
                                + "                if (s_idx < 0 || s_idx >= sec_count) continue;\n"
                                + "                if (wy > raw_top_y) raw_top_y = wy;\n"
                                + "                c_out_sections[(s_idx << 12) | ((wy & 15) << 8) | col] = default_block;\n"
                                + "            }\n"
                                + "            continue;\n"
                                + "        }\n"
                                + "        if (max_c <= 0.0f && aquifers_enabled == 0 && deep_lava_enabled == 0 && (default_fluid <= 0 || cell_base_y > sea_level)) {\n"
                                + "            continue;\n"
                                + "        }\n")
                        : "")
                + "        for (int dy = cell_h - 1; dy >= 0; dy--) {\n"
                + "            int wy = cell_base_y + dy;\n"
                + "            int s_idx = (wy - chunk_min_y) >> 4;\n"
                + "            if (s_idx < 0 || s_idx >= sec_count) continue;\n"
                + (useNativeFp64
                        ? ("            float dens;\n"
                                + "            int solid_sign;\n"
                                + "#if defined(LEAFGEN_EXACT_NATIVE_FP64) && defined(cl_khr_fp64)\n"
                                + "            double fy64 = (double)dy / (double)cell_h;\n"
                                + "            double fx64 = (double)dx / (double)cell_w;\n"
                                + "            double fz64 = (double)dz / (double)cell_w;\n"
                                + "            double x00_64 = (double)v000 + ((double)v001 - (double)v000) * fy64;\n"
                                + "            double x10_64 = (double)v100 + ((double)v101 - (double)v100) * fy64;\n"
                                + "            double x01_64 = (double)v010 + ((double)v011 - (double)v010) * fy64;\n"
                                + "            double x11_64 = (double)v110 + ((double)v111 - (double)v110) * fy64;\n"
                                + "            double z0_64 = x00_64 + (x10_64 - x00_64) * fx64;\n"
                                + "            double z1_64 = x01_64 + (x11_64 - x01_64) * fx64;\n"
                                + "            double dens64 = z0_64 + (z1_64 - z0_64) * fz64;\n"
                                + "            dens = (float)dens64;\n"
                                + "            solid_sign = (dens64 > 0.0) ? 1 : 0;\n"
                                + "#else\n"
                                + "            float fy = (float)dy * inv_cell_h;\n"
                                + "            float x00 = v000 + (v001 - v000) * fy;\n"
                                + "            float x10 = v100 + (v101 - v100) * fy;\n"
                                + "            float x01 = v010 + (v011 - v010) * fy;\n"
                                + "            float x11 = v110 + (v111 - v110) * fy;\n"
                                + "            float z0 = x00 + (x10 - x00) * fx;\n"
                                + "            float z1 = x01 + (x11 - x01) * fx;\n"
                                + "            dens = z0 + (z1 - z0) * fz;\n"
                                + "            float corner_mag = fabs(v000) + fabs(v001) + fabs(v100) + fabs(v101) + fabs(v010) + fabs(v011) + fabs(v110) + fabs(v111);\n"
                                + "            if (corner_mag > 1.0e-6f && fabs(dens) <= fmax(CAP_EXACT_SIGN_UNCERTAINTY_EPS, corner_mag * CAP_EXACT_SIGN_UNCERTAINTY_EPS)) {\n"
                                + "                uncertain_sign_flag = CAP_UNCERTAIN_SIGN_CPU_REFINE_FLAG;\n"
                                + "            }\n"
                                + "            solid_sign = (dens > 0.0f) ? 1 : 0;\n"
                                + "#endif\n"
                                + "            int flat_idx = (s_idx << 12) | ((wy & 15) << 8) | col;\n"
                                + "            int vox = 0;\n"
                                + "            if (solid_sign != 0) {\n")
                        : (useHybridSignBand
                                ? ("            float fy = (float)dy * inv_cell_h;\n"
                                        + "            float x00 = v000 + (v001 - v000) * fy;\n"
                                        + "            float x10 = v100 + (v101 - v100) * fy;\n"
                                        + "            float x01 = v010 + (v011 - v010) * fy;\n"
                                        + "            float x11 = v110 + (v111 - v110) * fy;\n"
                                        + "            float z0 = x00 + (x10 - x00) * fx;\n"
                                        + "            float z1 = x01 + (x11 - x01) * fx;\n"
                                        + "            float dens = z0 + (z1 - z0) * fz;\n"
                                        + "            float corner_mag = fabs(v000) + fabs(v001) + fabs(v100) + fabs(v101) + fabs(v010) + fabs(v011) + fabs(v110) + fabs(v111);\n"
                                        + "            if (corner_mag > 1.0e-6f && fabs(dens) <= fmax(CAP_EXACT_SIGN_UNCERTAINTY_EPS, corner_mag * CAP_EXACT_SIGN_UNCERTAINTY_EPS)) {\n"
                                        + "                uncertain_sign_flag = CAP_UNCERTAIN_SIGN_CPU_REFINE_FLAG;\n"
                                        + "            }\n"
                                        + "            int flat_idx = (s_idx << 12) | ((wy & 15) << 8) | col;\n"
                                        + "            int vox = 0;\n"
                                        + "            if (dens > 0.0f) {\n")
                                : ("            float fy = (float)dy * inv_cell_h;\n"
                                        + "            float x00 = v000 + (v001 - v000) * fy;\n"
                                        + "            float x10 = v100 + (v101 - v100) * fy;\n"
                                        + "            float x01 = v010 + (v011 - v010) * fy;\n"
                                        + "            float x11 = v110 + (v111 - v110) * fy;\n"
                                        + "            float z0 = x00 + (x10 - x00) * fx;\n"
                                        + "            float z1 = x01 + (x11 - x01) * fx;\n"
                                        + "            float dens = z0 + (z1 - z0) * fz;\n"
                                        + "            int flat_idx = (s_idx << 12) | ((wy & 15) << 8) | col;\n"
                                        + "            int vox = 0;\n"
                                        + "            if (dens > 0.0f) {\n")))
                + "                vox = default_block;\n"
                + "                if (wy > raw_top_y) raw_top_y = wy;\n"
                + "                if (eval_veins != 0 && ((wy >= " + rv.upperMinY() + " && wy <= " + rv.upperMaxY()
                + ") || (wy >= " + rv.lowerMinY() + " && wy <= " + rv.lowerMaxY() + "))) {\n"
                + "                    if (eval_veins == 2 && vein_toggle_id >= 0) {\n"
                + "                        float v_tog = eval_lifted_normal_noise_mode(vein_toggle_id, bx, wy, bz);\n"
                + "                        if (fabs(v_tog) > 0.4f) {\n"
                + "                            float v_rid_a = eval_lifted_normal_noise_mode(vein_ridged_id, bx, wy, bz);\n"
                + "                            float v_rid = (vein_ridged_b_id >= 0)\n"
                + "                                ? ("
                + Float.toHexString(gpuNoises != null ? gpuNoises.veinRidgedOffset() : -0.08f)
                + "f + fmax(fabs(v_rid_a), fabs(eval_lifted_normal_noise_mode(vein_ridged_b_id, bx, wy, bz))))\n"
                + "                                : v_rid_a;\n"
                + "                            float v_gap = eval_lifted_normal_noise_mode(vein_gap_id, bx, wy, bz);\n"
                + "                            vox = eval_lifted_router_vein(vox, default_block, wy, v_tog, v_rid, v_gap, "
                + rv.upperMinY() + ", " + rv.upperMaxY() + ", " + rv.upperOreSlot() + ", "
                + rv.upperRawBlockSlot() + ", " + rv.upperFillerSlot() + ", "
                + rv.lowerMinY() + ", " + rv.lowerMaxY() + ", " + rv.lowerOreSlot() + ", "
                + rv.lowerRawBlockSlot() + ", " + rv.lowerFillerSlot() + ", col_hash ^ (wy * 31));\n"
                + "                        }\n"
                + "                    } else if (vein_span_y <= 0 || (wy >= vein_min_y && wy < vein_min_y + vein_span_y)) {\n"
                + "                        int vb = (vein_span_y > 0) ? ((((wy - vein_min_y) << 8) | col) * 3) : (flat_idx * 3);\n"
                + "                        vox = eval_lifted_router_vein(vox, default_block, wy, c_veins[vb], c_veins[vb + 1], c_veins[vb + 2], "
                + rv.upperMinY() + ", " + rv.upperMaxY() + ", " + rv.upperOreSlot() + ", "
                + rv.upperRawBlockSlot() + ", " + rv.upperFillerSlot() + ", "
                + rv.lowerMinY() + ", " + rv.lowerMaxY() + ", " + rv.lowerOreSlot() + ", "
                + rv.lowerRawBlockSlot() + ", " + rv.lowerFillerSlot() + ", col_hash ^ (wy * 31));\n"
                + "                    }\n"
                + "                }\n"
                + "            } else if (has_sub_aquifer == 2 && aquifers_enabled != 0) {\n"
                + "                float bar_v = (has_barrier_noise == 3 && barrier_noise_id >= 0) ? eval_lifted_normal_noise_mode(barrier_noise_id, bx, wy, bz) : ((has_barrier_noise == 2) ? c_barrier[col] : ((has_barrier_noise != 0 && wy >= min_bound_y && wy < min_bound_y + barrier_stride) ? c_barrier[col * barrier_stride + (wy - min_bound_y)] : 0.0f));\n"
                + "                vox = eval_noise_based_aquifer_voxel(c_sub_aq, sub_min_gx, sub_min_gy, sub_min_gz, sub_grid_size_x, sub_grid_size_y, sub_grid_size_z, sub_x_sp, sub_y_sp, sub_x_sp, sub_skip_above_y, sub_sim_scale, bx, wy, bz, dens, bar_v, default_block, default_fluid, lava_slot, sea_level, deep_lava_y);\n"
                + "            } else if (deep_lava_enabled != 0 && wy <= deep_lava_y) {\n"
                + "                vox = lava_slot;\n"
                + "            } else if (aquifers_enabled == 0 && default_fluid > 0 && wy <= sea_level) {\n"
                + "                vox = default_fluid;\n"
                + "            }\n"
                + "            c_out_sections[flat_idx] = vox;\n"
                + "        }\n"
                + "    }\n"
                + "    s_raw_top_y[col] = raw_top_y;\n"
                + "    if (has_prelim_channel == 2) {\n"
                + "        s_prelim_y[col] = c_prelim_base[col];\n"
                + "    } else if (has_prelim_channel != 0) {\n"
                + "        s_prelim_y[col] = eval_preliminary_surface_y(c_prelim_dens + col * prelim_stride, prelim_stride, c_prelim_base[col], 1, c_prelim_fb[col]);\n"
                + "    } else {\n"
                + "        s_prelim_y[col] = raw_top_y;\n"
                + "    }\n"
                + "    barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);\n"
                + "    if (col == 0) {\n"
                + "        int max_sub = -2147483647;\n"
                + "        int max_raw = sea_level;\n"
                + "        for (int c = 0; c < 256; c++) {\n"
                + "            int rty = s_raw_top_y[c];\n"
                + "            if (rty <= sea_level && rty > max_sub) max_sub = rty;\n"
                + "            if (rty > max_raw) max_raw = rty;\n"
                + "        }\n"
                + "        s_est_basin_floor = (max_sub > -2147483647) ? max_sub : min_bound_y;\n"
                + "        int act_top = max_raw + 1;\n"
                + "        if (act_top > max_bound_y) act_top = max_bound_y;\n"
                + "        if (act_top < min_bound_y) act_top = min_bound_y;\n"
                + "        s_chunk_active_top_y = act_top;\n"
                + "    }\n"
                + "    barrier(CLK_LOCAL_MEM_FENCE);\n"
                + "    const int active_top_y = s_chunk_active_top_y;\n"
                + "    if (aquifers_enabled != 0) {\n"
                + "        if (has_sub_aquifer != 2 && default_fluid > 0 && raw_top_y <= sea_level) {\n"
                + "            int stop_y = (has_prelim_channel != 0) ? (s_prelim_y[col] - 2) : (s_est_basin_floor - 1);\n"
                + "            if (stop_y < min_bound_y) stop_y = min_bound_y;\n"
                + "            for (int wy = max_bound_y; wy >= min_bound_y; wy--) {\n"
                + "                int flat_idx = (((wy - chunk_min_y) >> 4) << 12) | ((wy & 15) << 8) | col;\n"
                + "                if (c_out_sections[flat_idx] != 0) break;\n"
                + "                if (wy <= sea_level) {\n"
                + "                    if (wy < stop_y) {\n"
                + "                        float bar = (has_barrier_noise == 3 && barrier_noise_id >= 0) ? eval_lifted_normal_noise_mode(barrier_noise_id, bx, wy, bz) : ((has_barrier_noise == 2) ? c_barrier[col] : ((has_barrier_noise != 0 && wy >= min_bound_y && wy < min_bound_y + barrier_stride) ? c_barrier[col * barrier_stride + (wy - min_bound_y)] : 0.5f));\n"
                + "                        int plug_t = (bar >= 0.0f) ? 2 : 1;\n"
                + "                        for (int py = 0; py < plug_t && (wy - py) >= min_bound_y; py++) {\n"
                + "                            int p_idx = (((wy - py - chunk_min_y) >> 4) << 12) | (((wy - py) & 15) << 8) | col;\n"
                + "                            if (c_out_sections[p_idx] == 0) c_out_sections[p_idx] = default_block;\n"
                + "                        }\n"
                + "                        break;\n"
                + "                    }\n"
                + "                    c_out_sections[flat_idx] = default_fluid;\n"
                + "                }\n"
                + "            }\n"
                + "        }\n"
                + "        barrier(CLK_GLOBAL_MEM_FENCE);\n"
                + "        if (" + (exactMode ? "(has_sub_aquifer != 2)" : "1") + ") {\n"
                + "        if (has_sub_aquifer == 1 && (default_fluid > 0 || lava_slot > 0)) {\n"
                + "            int roof_cap_y = raw_top_y - 6;\n"
                + "            if (sea_level < roof_cap_y) roof_cap_y = sea_level;\n"
                + "            int start_wy = (max_bound_y < roof_cap_y) ? max_bound_y : roof_cap_y;\n"
                + "            for (int wy = start_wy; wy > deep_lava_y && wy >= min_bound_y; wy--) {\n"
                + "                int flat_idx = (((wy - chunk_min_y) >> 4) << 12) | ((wy & 15) << 8) | col;\n"
                + "                if (c_out_sections[flat_idx] != 0) continue;\n"
                + "                int gy_cell = (wy >= 0) ? (wy / 12) : ((wy - 11) / 12);\n"
                + "                int gy_center = gy_cell * 12 + 6;\n"
                + "                int sb = (gy_cell - sub_min_gy) * 3;\n"
                + "                float flooded = c_sub_aq[sb];\n"
                + "                if (flooded > 0.35f) {\n"
                + "                    float spread = c_sub_aq[sb + 1];\n"
                + "                    int local_lvl = gy_center + (int)rint(spread * 6.0f) + 4;\n"
                + "                    if (local_lvl > roof_cap_y) local_lvl = roof_cap_y;\n"
                + "                    if (wy <= local_lvl) {\n"
                + "                        float lava_val = c_sub_aq[sb + 2];\n"
                + "                        int f_slot = (fabs(lava_val) > 0.70f && local_lvl <= -10 && wy <= -10 && lava_slot > 0) ? lava_slot : default_fluid;\n"
                + "                        if (f_slot > 0) c_out_sections[flat_idx] = f_slot;\n"
                + "                    }\n"
                + "                }\n"
                + "            }\n"
                + "        }\n"
                + "        if (has_sub_aquifer != 0) {\n"
                + "            int carry_fluid = 0;\n"
                + "            for (int wy = active_top_y; wy >= min_bound_y; wy--) {\n"
                + "                int flat_idx = (((wy - chunk_min_y) >> 4) << 12) | ((wy & 15) << 8) | col;\n"
                + "                int cur = c_out_sections[flat_idx];\n"
                + "                int cur_fl = ((default_fluid > 0 && cur == default_fluid) || (lava_slot > 0 && cur == lava_slot)) ? 1 : 0;\n"
                + "                if (cur_fl != 0) { carry_fluid = cur; }\n"
                + "                else if (cur == 0 && carry_fluid > 0) { c_out_sections[flat_idx] = carry_fluid; }\n"
                + "                else { carry_fluid = 0; }\n"
                + "            }\n"
                + "        }\n"
                + "        barrier(CLK_GLOBAL_MEM_FENCE);\n"
                + "        ulong seal_bits0 = 0UL, seal_bits1 = 0UL, seal_bits2 = 0UL, seal_bits3 = 0UL;\n"
                + "        ulong seal_bits4 = 0UL, seal_bits5 = 0UL, seal_bits6 = 0UL, seal_bits7 = 0UL;\n"
                + "        int any_sealed = 0;\n"
                + "        int above_vox = 0;\n"
                + "        for (int wy = active_top_y; wy >= seal_min_y; wy--) {\n"
                + "            int rel_y = wy - chunk_min_y;\n"
                + "            int sec_off = ((rel_y >> 4) << 12) | ((wy & 15) << 8);\n"
                + "            int cur_vox = c_out_sections[sec_off | col];\n"
                + "            if (cur_vox == 0) {\n"
                + "                int nx0 = (wy >= min_bound_y && lx > 0) ? c_out_sections[sec_off | (col - 1)] : 0;\n"
                + "                int nx1 = (wy >= min_bound_y && lx < 15) ? c_out_sections[sec_off | (col + 1)] : 0;\n"
                + "                int nz0 = (wy >= min_bound_y && lz > 0) ? c_out_sections[sec_off | (col - 16)] : 0;\n"
                + "                int nz1 = (wy >= min_bound_y && lz < 15) ? c_out_sections[sec_off | (col + 16)] : 0;\n"
                + "                if (eval_hydrostatic_seal_voxel(0, above_vox, nx0, nx1, nz0, nz1, default_block, default_fluid, lava_slot) != 0 && rel_y >= 0 && rel_y < 512) {\n"
                + "                    int w_idx = rel_y >> 6;\n"
                + "                    ulong bit = 1UL << (rel_y & 63);\n"
                + "                    if (w_idx == 0) seal_bits0 |= bit;\n"
                + "                    else if (w_idx == 1) seal_bits1 |= bit;\n"
                + "                    else if (w_idx == 2) seal_bits2 |= bit;\n"
                + "                    else if (w_idx == 3) seal_bits3 |= bit;\n"
                + "                    else if (w_idx == 4) seal_bits4 |= bit;\n"
                + "                    else if (w_idx == 5) seal_bits5 |= bit;\n"
                + "                    else if (w_idx == 6) seal_bits6 |= bit;\n"
                + "                    else seal_bits7 |= bit;\n"
                + "                    any_sealed = 1;\n"
                + "                }\n"
                + "            }\n"
                + "            above_vox = cur_vox;\n"
                + "        }\n"
                + "        barrier(CLK_GLOBAL_MEM_FENCE);\n"
                + "        if (any_sealed != 0) {\n"
                + "            for (int wy = active_top_y; wy >= seal_min_y; wy--) {\n"
                + "                int rel_y = wy - chunk_min_y;\n"
                + "                if (rel_y < 0 || rel_y >= 512) continue;\n"
                + "                int w_idx = rel_y >> 6;\n"
                + "                ulong mask = (w_idx == 0) ? seal_bits0 : ((w_idx == 1) ? seal_bits1 : ((w_idx == 2) ? seal_bits2 : ((w_idx == 3) ? seal_bits3 : ((w_idx == 4) ? seal_bits4 : ((w_idx == 5) ? seal_bits5 : ((w_idx == 6) ? seal_bits6 : seal_bits7))))));\n"
                + "                if ((mask & (1UL << (rel_y & 63))) != 0UL) {\n"
                + "                    c_out_sections[(((rel_y >> 4) << 12) | ((wy & 15) << 8) | col)] = default_block;\n"
                + "                }\n"
                + "            }\n"
                + "        }\n"
                + "        barrier(CLK_GLOBAL_MEM_FENCE);\n"
                + "        }\n"
                + "    }\n"
                + "    int top_y = chunk_min_y - 1;\n"
                + "    for (int wy = active_top_y; wy >= min_bound_y; wy--) {\n"
                + "        int cur = c_out_sections[(((wy - chunk_min_y) >> 4) << 12) | ((wy & 15) << 8) | col];\n"
                + "        if (cur != 0 && (default_fluid == 0 || cur != default_fluid) && (lava_slot == 0 || cur != lava_slot)) {\n"
                + "            top_y = wy;\n"
                + "            break;\n"
                + "        }\n"
                + "    }\n"
                + "    s_layer_vox[col] = top_y;\n"
                + (exactMode
                        ? "    c_top_y[col] = (top_y & 0xFFFF) | uncertain_sign_flag;\n"
                        : "    c_top_y[col] = (apply_gpu_carvers != 0) ? (top_y & 0xFFFF) : top_y;\n")
                + "    barrier(CLK_LOCAL_MEM_FENCE);\n"
                + "    if (apply_surface_rules != 0) {\n"
                + "        int hx0 = s_layer_vox[(lz << 4) | (lx > 0 ? lx - 1 : 0)];\n"
                + "        int hx1 = s_layer_vox[(lz << 4) | (lx < 15 ? lx + 1 : 15)];\n"
                + "        int hz0 = s_layer_vox[((lz > 0 ? lz - 1 : 0) << 4) | lx];\n"
                + "        int hz1 = s_layer_vox[((lz < 15 ? lz + 1 : 15) << 4) | lx];\n"
                + "        int steep = (abs(hx1 - hx0) >= 4 || abs(hz1 - hz0) >= 4) ? 1 : 0;\n"
                + "        int surface_depth = compute_smooth_surface_depth_i32(world_seed, bx, bz, col_hash);\n"
                + "        int col_prelim_y = s_prelim_y[col];\n"
                + "        int floor_depth = -1;\n"
                + "        int fluid_above = 0;\n"
                + "        int water_height = -2147483647;\n"
                + "        for (int wy = active_top_y; wy >= min_bound_y; wy--) {\n"
                + "            int flat_idx = (((wy - chunk_min_y) >> 4) << 12) | ((wy & 15) << 8) | col;\n"
                + "            int cur = c_out_sections[flat_idx];\n"
                + "            if (cur == 0) {\n"
                + "                floor_depth = -1;\n"
                + "                fluid_above = 0;\n"
                + "                water_height = -2147483647;\n"
                + "                continue;\n"
                + "            }\n"
                + "            if ((default_fluid != 0 && cur == default_fluid) || (lava_slot > 0 && cur == lava_slot)) {\n"
                + "                floor_depth = -1;\n"
                + "                if (fluid_above == 0) water_height = wy + 1;\n"
                + "                fluid_above = 1;\n"
                + "                continue;\n"
                + "            }\n"
                + "            floor_depth++;\n"
                + "            if (cur != default_block) {\n"
                + "                continue;\n"
                + "            }\n"
                + "            int ceil_depth = 0;\n"
                + (exactMode
                        ? "            const int ceil_probe_max = (wy > min_bound_y) ? (wy - min_bound_y) : 1;\n"
                        : "            const int ceil_probe_max = 24;\n")
                + "            for (int cd = 1; cd <= ceil_probe_max; cd++) {\n"
                + "                int by_p = wy - cd;\n"
                + "                if (by_p < min_bound_y) { ceil_depth = ceil_probe_max; break; }\n"
                + "                int bv = c_out_sections[(((by_p - chunk_min_y) >> 4) << 12) | ((by_p & 15) << 8) | col];\n"
                + "                if (bv == 0 || (default_fluid != 0 && bv == default_fluid) || (lava_slot > 0 && bv == lava_slot)) { break; }\n"
                + "                ceil_depth = cd;\n"
                + "            }\n"
                + "            int qy_rel = (wy - chunk_min_y) >> 2;\n"
                + "            int biome_ord = (has_halo_6x6 != 0)\n"
                + "                ? eval_voronoi_zoomed_biome_ord(c_quart_biomes, quarts_y, chunk_x << 2, chunk_min_y >> 2, chunk_z << 2, zoom_seed, bx, wy, bz)\n"
                + "                : c_quart_biomes[(qy_rel << 4) | ((lz >> 2) << 2) | (lx >> 2)];\n"
                + "            float temp_val = 0.5f;\n"
                + "            if (has_temp_channel == 2 && temp_noise_id >= 0) {\n"
                + "                temp_val = eval_lifted_normal_noise_mode(temp_noise_id, bx, (temp_2d_only != 0) ? 0 : wy, bz);\n"
                + "            } else if (has_temp_channel == 1 && c_temp != 0) {\n"
                + "                temp_val = c_temp[(temp_2d_only != 0) ? col : flat_idx];\n"
                + "            }\n"
                + "            c_out_sections[flat_idx] = eval_lifted_surface_rule_ir(\n"
                + "                cur, default_block, bx, wy, bz,\n"
                + "                floor_depth + 1, ceil_depth + 1, water_height,\n"
                + "                surface_depth, biome_ord, col_prelim_y,\n"
                + "                steep, hx1 - hx0, hz1 - hz0, 0.0f, temp_val, rule_ops);\n"
                + "        }\n"
                + "    }\n"
                + (carverCount > 0
                        ? emitGpuCarversStageSnippet(carverCount, exactMode)
                        : "    (void)apply_gpu_carvers;\n")
                + "    if (top_y_stride >= 256 + sec_count) {\n"
                + "        barrier(CLK_GLOBAL_MEM_FENCE);\n"
                + "        for (int s = 0; s < sec_count; s++) {\n"
                + "            int sec_base = (s << 12) | col;\n"
                + "            int first_v = c_out_sections[sec_base];\n"
                + "            int col_slot = first_v;\n"
                + "            for (int ly = 1; ly < 16; ly++) {\n"
                + "                if (c_out_sections[sec_base | (ly << 8)] != first_v) {\n"
                + "                    col_slot = -1;\n"
                + "                    break;\n"
                + "                }\n"
                + "            }\n"
                + "            s_layer_vox[col] = col_slot;\n"
                + "            barrier(CLK_LOCAL_MEM_FENCE);\n"
                + "            for (int stride = 128; stride > 0; stride >>= 1) {\n"
                + "                if (col < stride) {\n"
                + "                    int a = s_layer_vox[col];\n"
                + "                    int b = s_layer_vox[col + stride];\n"
                + "                    s_layer_vox[col] = (a >= 0 && a == b) ? a : -1;\n"
                + "                }\n"
                + "                barrier(CLK_LOCAL_MEM_FENCE);\n"
                + "            }\n"
                + "            if (col == 0) {\n"
                + "                c_top_y[256 + s] = s_layer_vox[0];\n"
                + "            }\n"
                + "            barrier(CLK_LOCAL_MEM_FENCE);\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
        return new CapabilityKernelProgram(
                sourceCode,
                sharedLutAllocator.spilledWordsArray(),
                sharedLutAllocator.usedConstantBytes(),
                sharedLutAllocator.spilledLutCount(),
                sharedLutAllocator.deduplicatedLutCount());
    }

    private static String emitCapabilityCarverConstants(LiftedWorldgenCapabilityTables.CarverSpec[] carvers) {
        if (carvers == null || carvers.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append(openClConstantArrayPrefix("float", "CAP_CARVER_ROTOR_COS", 16)).append('{');
        for (int k = 0; k < 16; k++) {
            if (k > 0) sb.append(',');
            sb.append(formatFloat((float) Math.cos((k - 7.5) * 0.08)));
        }
        sb.append("};\n");
        sb.append(openClConstantArrayPrefix("float", "CAP_CARVER_ROTOR_SIN", 16)).append('{');
        for (int k = 0; k < 16; k++) {
            if (k > 0) sb.append(',');
            sb.append(formatFloat((float) Math.sin((k - 7.5) * 0.08)));
        }
        sb.append("};\n");
        sb.append(openClConstantArrayPrefix("int", "CAP_CARVER_SPECS", carvers.length * 9)).append('{');
        for (int i = 0; i < carvers.length; i++) {
            LiftedWorldgenCapabilityTables.CarverSpec c = carvers[i];
            if (i > 0) sb.append(',');
            sb.append(c.kind()).append(',')
                    .append(c.biomeOrdinal()).append(',')
                    .append(c.probabilityPermille()).append(',')
                    .append(c.minY()).append(',')
                    .append(c.maxY()).append(',')
                    .append(c.lavaLevelY()).append(',')
                    .append(c.horizScalePermille()).append(',')
                    .append(c.vertScalePermille()).append(',')
                    .append(Math.max(1, c.rangeChunks()));
        }
        sb.append("};\n");
        return sb.toString();
    }

    private static String emitGpuCarversStageSnippet(int carverCount, boolean exactMode) {
        return "    if (apply_gpu_carvers != 0) {\n"
                + "        if (col == 0) s_carv_count = 0;\n"
                + "        barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);\n"
                + "        const int chunk_base_bx = chunk_x << 4;\n"
                + "        const int chunk_base_bz = chunk_z << 4;\n"
                + "        const int carve_fluid = (lava_slot > 0) ? lava_slot : default_fluid;\n"
                + "        for (int ci = 0; ci < " + carverCount + "; ci++) {\n"
                + "            int cb = ci * 9;\n"
                + "            int c_kind = CAP_CARVER_SPECS[cb];\n"
                + "            int c_prob = CAP_CARVER_SPECS[cb + 2];\n"
                + "            if (c_prob <= 0) continue;\n"
                + "            int c_lo_y = max(min_bound_y + 2, CAP_CARVER_SPECS[cb + 3]);\n"
                + "            int c_hi_y = min(max_bound_y - 2, CAP_CARVER_SPECS[cb + 4]);\n"
                + "            if (c_hi_y < c_lo_y) continue;\n"
                + "            int c_lava_y = CAP_CARVER_SPECS[cb + 5];\n"
                + "            float h_scale = fmax(0.5f, (float)CAP_CARVER_SPECS[cb + 6] * 0.001f);\n"
                + "            float v_scale = fmax(0.5f, (float)CAP_CARVER_SPECS[cb + 7] * 0.001f);\n"
                + "            int c_max_range = CAP_CARVER_SPECS[cb + 8];\n"
                + "            float rxz = ((c_kind == 2) ? 2.0f : 2.6f) * h_scale;\n"
                + "            float ry = ((c_kind == 2) ? 6.5f : 2.5f) * v_scale;\n"
                + "            float inv_rxz2 = 1.0f / (rxz * rxz);\n"
                + "            float inv_ry2 = 1.0f / (ry * ry);\n"
                + "            int steps = (c_kind == 2) ? 16 : 14;\n"
                + "            float max_reach = (float)steps * 2.2f + rxz;\n"
                + "            int c_range = (int)ceil((max_reach + 15.0f) * 0.0625f);\n"
                + "            if (c_range > c_max_range) c_range = c_max_range;\n"
                + "            if (c_range < 1) c_range = 1;\n"
                + "            int span_c = (c_range << 1) + 1;\n"
                + "            int total_cand = span_c * span_c;\n"
                + "            long carver_seed = world_seed ^ ((long)(ci + 1) * (long)0x94D049BB133111EBUL);\n"
                + "            for (int cand = col; cand < total_cand; cand += 256) {\n"
                + "                int dcz = (cand / span_c) - c_range;\n"
                + "                int dcx = (cand - (dcz + c_range) * span_c) - c_range;\n"
                + "                int ocx = chunk_x + dcx;\n"
                + "                int ocz = chunk_z + dcz;\n"
                + "                int h = mix_column_seed_i32(carver_seed, ocx, ocz);\n"
                + "                int abs_h = (h == (-2147483647 - 1)) ? 0 : (int)abs(h);\n"
                + "                if ((abs_h % 1000) >= c_prob) continue;\n"
                + "                float wx = (float)((ocx << 4) + (int)(((uint)h >> 4) & 15u));\n"
                + "                float wz = (float)((ocz << 4) + (int)(((uint)h >> 8) & 15u));\n"
                + "                if (wx + max_reach < (float)chunk_base_bx || wx - max_reach > (float)(chunk_base_bx + 15)\n"
                + "                        || wz + max_reach < (float)chunk_base_bz || wz - max_reach > (float)(chunk_base_bz + 15)) continue;\n"
                + "                int h12 = (int)((uint)h >> 12);\n"
                + "                float wy = (float)(c_lo_y + (h12 % (c_hi_y - c_lo_y + 1)));\n"
                + "                float yaw = ((float)(h & 255) / 256.0f) * 6.28318530718f;\n"
                + "                float pitch = (float)((int)(((uint)h >> 16) & 31u) - 16) * 0.02f;\n"
                + "                float dir_x = cos(yaw);\n"
                + "                float dir_z = sin(yaw);\n"
                + "                float sin_pitch_step = sin(pitch) * 1.2f;\n"
                + "                for (int st = 0; st < steps; st++) {\n"
                + "                    if (wx + rxz >= (float)chunk_base_bx && wx - rxz <= (float)(chunk_base_bx + 15)\n"
                + "                            && wz + rxz >= (float)chunk_base_bz && wz - rxz <= (float)(chunk_base_bz + 15)) {\n"
                + "                        int slot = atomic_inc(&s_carv_count);\n"
                + "                        if (slot < 256) {\n"
                + "                            s_carv_wx[slot] = wx;\n"
                + "                            s_carv_wy[slot] = wy;\n"
                + "                            s_carv_wz[slot] = wz;\n"
                + "                            s_carv_irxz2[slot] = inv_rxz2;\n"
                + "                            s_carv_iry2[slot] = inv_ry2;\n"
                + "                            s_carv_rxz[slot] = rxz;\n"
                + "                            s_carv_ry[slot] = ry;\n"
                + "                            s_carv_lava[slot] = c_lava_y;\n"
                + "                        }\n"
                + "                    }\n"
                + "                    int rotor_idx = mix_column_seed_i32((long)h, st, ci) & 15;\n"
                + "                    float rc_cos = CAP_CARVER_ROTOR_COS[rotor_idx];\n"
                + "                    float rc_sin = CAP_CARVER_ROTOR_SIN[rotor_idx];\n"
                + "                    float ndx = dir_x * rc_cos - dir_z * rc_sin;\n"
                + "                    float ndz = dir_x * rc_sin + dir_z * rc_cos;\n"
                + "                    dir_x = ndx; dir_z = ndz;\n"
                + "                    wx += dir_x * 2.2f;\n"
                + "                    wz += dir_z * 2.2f;\n"
                + "                    wy = fmax((float)c_lo_y, fmin((float)c_hi_y, wy + sin_pitch_step));\n"
                + "                }\n"
                + "            }\n"
                + "        }\n"
                + "        barrier(CLK_LOCAL_MEM_FENCE);\n"
                + "        int total_segs = s_carv_count;\n"
                + "        if (total_segs > 256) {\n"
                + "            if (col == 0) c_top_y[0] |= CAP_CARVER_OVERFLOW_FALLBACK_FLAG;\n"
                + "        } else if (total_segs > 0) {\n"
                + "            float fbx = (float)bx + 0.5f;\n"
                + "            float fbz = (float)bz + 0.5f;\n"
                + "            int col_carved_surface = 0;\n"
                + "            for (int si = 0; si < total_segs; si++) {\n"
                + "                float dx = fbx - s_carv_wx[si];\n"
                + "                float dz = fbz - s_carv_wz[si];\n"
                + "                float rxz_s = s_carv_rxz[si];\n"
                + "                if (fabs(dx) > rxz_s || fabs(dz) > rxz_s) continue;\n"
                + "                float dxz2 = (dx * dx + dz * dz) * s_carv_irxz2[si];\n"
                + "                if (dxz2 >= 1.0f) continue;\n"
                + "                float wy_s = s_carv_wy[si];\n"
                + "                float ry_s = s_carv_ry[si];\n"
                + "                float iry2_s = s_carv_iry2[si];\n"
                + "                int lava_y_s = s_carv_lava[si];\n"
                + "                int min_vy = max(min_bound_y + 2, (int)floor(wy_s - ry_s));\n"
                + "                int max_vy = min(max_bound_y - 2, (int)ceil(wy_s + ry_s));\n"
                + "                for (int vy = max_vy; vy >= min_vy; vy--) {\n"
                + "                    float dy = ((float)vy + 0.5f) - wy_s;\n"
                + "                    float dsq = dxz2 + dy * dy * iry2_s;\n"
                + "                    if (dsq >= 1.0f) continue;\n"
                + "                    int ab_y = vy + 1;\n"
                + "                    if (ab_y <= max_bound_y) {\n"
                + "                        int ab_v = c_out_sections[(((ab_y - chunk_min_y) >> 4) << 12) | ((ab_y & 15) << 8) | col];\n"
                + "                        if ((default_fluid > 0 && ab_v == default_fluid) || (lava_slot > 0 && ab_v == lava_slot)) break;\n"
                + "                    }\n"
                + "                    int sec_off = (((vy - chunk_min_y) >> 4) << 12) | ((vy & 15) << 8);\n"
                + "                    int cur = c_out_sections[sec_off | col];\n"
                + "                    if (cur == 0 || (default_fluid > 0 && cur == default_fluid) || (lava_slot > 0 && cur == lava_slot)) continue;\n"
                + "                    int lat_fl = 0;\n"
                + "                    if (lx > 0) { int nv = c_out_sections[sec_off | (col - 1)]; if ((default_fluid > 0 && nv == default_fluid) || (lava_slot > 0 && nv == lava_slot)) lat_fl = 1; }\n"
                + "                    if (lat_fl == 0 && lx < 15) { int nv = c_out_sections[sec_off | (col + 1)]; if ((default_fluid > 0 && nv == default_fluid) || (lava_slot > 0 && nv == lava_slot)) lat_fl = 1; }\n"
                + "                    if (lat_fl == 0 && lz > 0) { int nv = c_out_sections[sec_off | (col - 16)]; if ((default_fluid > 0 && nv == default_fluid) || (lava_slot > 0 && nv == lava_slot)) lat_fl = 1; }\n"
                + "                    if (lat_fl == 0 && lz < 15) { int nv = c_out_sections[sec_off | (col + 16)]; if ((default_fluid > 0 && nv == default_fluid) || (lava_slot > 0 && nv == lava_slot)) lat_fl = 1; }\n"
                + "                    if (lat_fl != 0) continue;\n"
                + "                    c_out_sections[sec_off | col] = eval_lifted_carver_voxel(cur, vy, min_bound_y + 1, lava_y_s, carve_fluid, dsq);\n"
                + "                    if (vy >= top_y - 1) col_carved_surface = 1;\n"
                + "                }\n"
                + "            }\n"
                + "            if (col_carved_surface != 0) {\n"
                + "                int new_top = chunk_min_y - 1;\n"
                + "                for (int wy = active_top_y; wy >= min_bound_y; wy--) {\n"
                + "                    int cv = c_out_sections[(((wy - chunk_min_y) >> 4) << 12) | ((wy & 15) << 8) | col];\n"
                + "                    if (cv != 0 && (default_fluid == 0 || cv != default_fluid) && (lava_slot == 0 || cv != lava_slot)) { new_top = wy; break; }\n"
                + "                }\n"
                + "                top_y = new_top;\n"
                + (exactMode
                        ? "                c_top_y[col] = (top_y & 0xFFFF) | uncertain_sign_flag;\n"
                        : "                c_top_y[col] = (top_y & 0xFFFF);\n")
                + "            }\n"
                + "        }\n"
                + "        barrier(CLK_GLOBAL_MEM_FENCE);\n"
                + "    }\n";
    }
}
