package io.github.dailystruggle.bytecode.transpile;

import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptIrEvaluator;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.BinaryOperator;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptBatchSpec;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptExpr;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptFunction;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptKernel3D;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptLut;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptModule;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptParam;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptStmt;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptType;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.MathIntrinsicKind;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.UnaryOperator;
import io.github.dailystruggle.bytecode.transpile.ConceptOptimizerPass.Q32LoweringPlan;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Bidirectional Java 17 and {@code Jminor v2} shorthand backend emitter implementing
 * {@link ConceptBackendEmitter}.
 *
 * <p>Lowers SSA-lifted, {@link ConceptOptimizerPass}-canonicalized {@link ConceptModule} DAGs
 * into either verified Java 17 source ({@link #BACKEND_ID_JAVA}) or token-compressed
 * {@code Jminor v2} shorthand IR ({@link #BACKEND_ID_JMINOR}) sharing identical LUT deduplication,
 * {@link GpuExactArithmeticMode} lowering ({@code NATIVE_FP64}, {@code DOWNCAST_FP32},
 * {@code INT64_Q32_FP32}), and {@link BatchLayout} 3D section/corner batching with
 * {@link OpenClBackendEmitter}.
 *
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy corpus-atlas-ADR-002
 * @trace REQ-CFG-ATLAS-001
 */
public final class JavaConceptBackendEmitter implements ConceptBackendEmitter {

    public static final String BACKEND_ID_JAVA = "java";
    public static final String BACKEND_ID_JMINOR = "jminor";

    private final boolean jminorMode;

    public JavaConceptBackendEmitter() {
        this(false);
    }

    public JavaConceptBackendEmitter(boolean jminorMode) {
        this.jminorMode = jminorMode;
    }

    public static JavaConceptBackendEmitter javaBackend() {
        return new JavaConceptBackendEmitter(false);
    }

    public static JavaConceptBackendEmitter jminorBackend() {
        return new JavaConceptBackendEmitter(true);
    }

    @Override
    public String backendId() {
        return jminorMode ? BACKEND_ID_JMINOR : BACKEND_ID_JAVA;
    }

    @Override
    public String fileExtension() {
        return jminorMode ? ".jminor" : ".java";
    }

    @Override
    public TranspiledArtifact emit(ConceptModule module, EmitOptions options) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(options, "options");

        ConceptModule lowered = ConceptOptimizerPass.deduplicateLookupTables(module);
        GpuExactArithmeticMode arithmeticMode = options.gpuExactArithmeticMode();
        Q32LoweringPlan q32Plan = arithmeticMode.usesInt64Q32()
                ? ConceptOptimizerPass.analyzeQ32Lowering(lowered)
                : Q32LoweringPlan.empty();

        Map<String, ConceptFunction> fnByKey = new LinkedHashMap<>();
        Map<String, ConceptFunction> fnByName = new LinkedHashMap<>();
        registerFunctionLookup(lowered, fnByKey, fnByName);

        StringBuilder sb = new StringBuilder(2048);
        String moduleHash = BytecodeConceptIr.canonicalModuleHash(lowered);
        String entryHash = BytecodeConceptIr.canonicalHash(lowered.entryFunction());

        if (jminorMode) {
            sb.append("// Jminor v2 canonical concept projection for ")
                    .append(lowered.moduleName())
                    .append(" (module_hash=")
                    .append(moduleHash)
                    .append(")\n");
            for (ConceptLut lut : lowered.lookupTables()) {
                sb.append("@lut(symbol=")
                        .append(lut.symbolName())
                        .append(", type=")
                        .append(mapType(lut.elementType(), options))
                        .append(", len=")
                        .append(lut.values().size())
                        .append(")\n");
            }
            for (ConceptFunction helper : lowered.helperFunctions()) {
                emitJminorFunction(sb, helper, options, q32Plan, fnByKey, fnByName);
                sb.append('\n');
            }
            emitJminorFunction(sb, lowered.entryFunction(), options, q32Plan, fnByKey, fnByName);
            String kernelName = lowered.kernel3D() != null ? lowered.kernel3D().kernelName() : null;
            return new TranspiledArtifact(
                    backendId(),
                    fileExtension(),
                    lowered.moduleName(),
                    lowered.entryFunction().name(),
                    kernelName,
                    arithmeticMode.toFloatingPointPrecision(),
                    sb.toString(),
                    lowered);
        }

        String className = sanitizeClassName(lowered.moduleName());
        sb.append("// Auto-generated canonical Java 17 from ConceptModule: ")
                .append(lowered.moduleName())
                .append(" (canonical_hash=")
                .append(entryHash)
                .append(", arithmetic_mode=")
                .append(arithmeticMode.name())
                .append(", batch_layout=")
                .append(options.batchLayout().name())
                .append(")\n")
                .append("public final class ")
                .append(className)
                .append(" {\n\n")
                .append("    private ")
                .append(className)
                .append("() {\n    }\n\n");

        if (arithmeticMode.usesInt64Q32()) {
            emitJavaQ32Helpers(sb);
            sb.append('\n');
        }

        for (ConceptLut lut : lowered.lookupTables()) {
            if (arithmeticMode.usesInt64Q32()
                    && lut.elementType() == ConceptType.F64
                    && q32Plan.q32LutSymbol(lut.symbolName()) != null
                    && !q32Plan.isFp32ReferencedLut(lut.symbolName())) {
                continue;
            }
            emitJavaLut(sb, lut, options);
            sb.append('\n');
        }
        if (arithmeticMode.usesInt64Q32()) {
            for (ConceptLut q32Lut : q32Plan.synthesizedQ32Luts()) {
                emitJavaLut(sb, q32Lut, options);
                sb.append('\n');
            }
        }

        for (ConceptFunction helper : lowered.helperFunctions()) {
            emitJavaFunction(sb, helper, options, q32Plan, fnByKey, fnByName, false);
            sb.append('\n');
        }

        emitJavaFunction(sb, lowered.entryFunction(), options, q32Plan, fnByKey, fnByName, true);

        String kernelName = null;
        if (options.emit3DKernel() && (lowered.kernel3D() != null || options.batchLayout() != BatchLayout.SINGLE_POINT)) {
            ConceptBatchSpec batchSpec = ConceptOptimizerPass.buildBatchSpec(lowered, options.batchLayout());
            sb.append('\n');
            emitJavaBatchFunction(sb, lowered, batchSpec, options, q32Plan);
            kernelName = batchSpec.kernelName();
        }

        sb.append("}\n");

        return new TranspiledArtifact(
                backendId(),
                fileExtension(),
                lowered.moduleName(),
                lowered.entryFunction().name(),
                kernelName,
                arithmeticMode.toFloatingPointPrecision(),
                sb.toString(),
                lowered);
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

    private static void emitJavaQ32Helpers(StringBuilder sb) {
        sb.append("    private static long q32_from_i32(int v) {\n")
                .append("        return ((long) v) << 32;\n")
                .append("    }\n")
                .append("    private static long q32_from_i64(long v) {\n")
                .append("        return v << 32;\n")
                .append("    }\n")
                .append("    private static long q32_from_f32(float v) {\n")
                .append("        float fl = (float) Math.floor(v);\n")
                .append("        float fr = v - fl;\n")
                .append("        long iv = (long) fl;\n")
                .append("        if (fr >= 1.0f) {\n")
                .append("            iv += 1L;\n")
                .append("            fr = 0.0f;\n")
                .append("        } else if (fr < 0.0f) {\n")
                .append("            iv -= 1L;\n")
                .append("            fr = 0.0f;\n")
                .append("        }\n")
                .append("        long frac = ((long) (fr * 4294967296.0f)) & 0xFFFFFFFFL;\n")
                .append("        return (iv << 32) | frac;\n")
                .append("    }\n")
                .append("    private static float q32_to_f32(long q) {\n")
                .append("        long hi = q >> 32;\n")
                .append("        long lo = q & 0xFFFFFFFFL;\n")
                .append("        return (float) hi + (float) lo * 0x1.0p-32f;\n")
                .append("    }\n")
                .append("    private static long q32_add(long a, long b) {\n")
                .append("        return a + b;\n")
                .append("    }\n")
                .append("    private static long q32_sub(long a, long b) {\n")
                .append("        return a - b;\n")
                .append("    }\n")
                .append("    private static long q32_neg(long a) {\n")
                .append("        return -a;\n")
                .append("    }\n")
                .append("    private static long q32_abs(long a) {\n")
                .append("        return a < 0L ? -a : a;\n")
                .append("    }\n")
                .append("    private static long q32_mul(long a, long b) {\n")
                .append("        long hi = Math.multiplyHigh(a, b);\n")
                .append("        long lo = a * b;\n")
                .append("        long rlo = lo + 0x80000000L;\n")
                .append("        long rhi = hi + (Long.compareUnsigned(rlo, lo) < 0 ? 1L : 0L);\n")
                .append("        return (rhi << 32) | (rlo >>> 32);\n")
                .append("    }\n")
                .append("    private static long q32_div(long a, long b) {\n")
                .append("        return b == 0L ? 0L : q32_from_f32(q32_to_f32(a) / q32_to_f32(b));\n")
                .append("    }\n")
                .append("    private static long q32_floor(long q) {\n")
                .append("        return q & 0xFFFFFFFF00000000L;\n")
                .append("    }\n")
                .append("    private static long q32_ceil(long q) {\n")
                .append("        return (q + 0xFFFFFFFFL) & 0xFFFFFFFF00000000L;\n")
                .append("    }\n")
                .append("    private static long q32_floor_i64(long q) {\n")
                .append("        return q >> 32;\n")
                .append("    }\n")
                .append("    private static int q32_floor_i32(long q) {\n")
                .append("        return (int) (q >> 32);\n")
                .append("    }\n")
                .append("    private static long q32_trunc_i64(long q) {\n")
                .append("        return q >= 0L ? (q >> 32) : -((-q) >>> 32);\n")
                .append("    }\n")
                .append("    private static int q32_trunc_i32(long q) {\n")
                .append("        return (int) q32_trunc_i64(q);\n")
                .append("    }\n");
    }

    private static String sanitizeClassName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "SynthesizedConceptKernel";
        }
        String simple = raw.contains(".") ? raw.substring(raw.lastIndexOf('.') + 1) : raw;
        simple = simple.contains("/") ? simple.substring(simple.lastIndexOf('/') + 1) : simple;
        StringBuilder cleaned = new StringBuilder(simple.length() + 8);
        for (int i = 0; i < simple.length(); i++) {
            char c = simple.charAt(i);
            if (Character.isJavaIdentifierPart(c)) {
                cleaned.append(c);
            } else {
                cleaned.append('_');
            }
        }
        if (cleaned.isEmpty() || !Character.isJavaIdentifierStart(cleaned.charAt(0))) {
            cleaned.insert(0, "Kernel_");
        }
        return cleaned + "Canonical";
    }

    private static void emitJavaLut(StringBuilder sb, ConceptLut lut, EmitOptions options) {
        String elemType = mapType(lut.elementType(), options);
        String initMethod = "__init_" + lut.symbolName();
        sb.append("    private static ")
                .append(elemType)
                .append("[] ")
                .append(initMethod)
                .append("() {\n        return new ")
                .append(elemType)
                .append("[] {\n            ");
        List<Number> vals = lut.values();
        for (int i = 0; i < vals.size(); i++) {
            Number v = vals.get(i);
            if (lut.elementType() == ConceptType.I64) {
                sb.append(v.longValue()).append('L');
            } else if (lut.elementType() == ConceptType.F32
                    || (lut.elementType() == ConceptType.F64 && isFp32(options))) {
                sb.append(formatFloatLiteral(v.floatValue()));
            } else if (lut.elementType() == ConceptType.F64) {
                sb.append(formatDouble(v.doubleValue()));
            } else {
                sb.append(v.intValue());
            }
            if (i + 1 < vals.size()) {
                sb.append(", ");
                if ((i + 1) % 16 == 0) {
                    sb.append("\n            ");
                }
            }
        }
        sb.append("\n        };\n    }\n    private static final ")
                .append(elemType)
                .append("[] ")
                .append(lut.symbolName())
                .append(" = ")
                .append(initMethod)
                .append("();\n");
    }

    private static void emitJavaFunction(
            StringBuilder sb,
            ConceptFunction fn,
            EmitOptions options,
            Q32LoweringPlan q32Plan,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName,
            boolean isPublic) {
        boolean q32Mode = options.gpuExactArithmeticMode().usesInt64Q32();
        sb.append("    ")
                .append(isPublic ? "public" : "private")
                .append(" static ")
                .append(mapReturnType(fn, options, q32Plan))
                .append(' ')
                .append(fn.name())
                .append('(');
        List<ConceptParam> params = fn.parameters();
        Set<String> declared = new HashSet<>();
        Set<String> predeclaredAtMethodScope = new HashSet<>();
        for (int i = 0; i < params.size(); i++) {
            ConceptParam p = params.get(i);
            declared.add(p.name());
            predeclaredAtMethodScope.add(p.name());
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(mapParamType(fn, p, i, options, q32Plan)).append(' ').append(p.name());
        }
        sb.append(") {\n");
        for (ConceptStmt stmt : fn.body()) {
            if (stmt instanceof ConceptStmt.VarDecl vd) {
                declared.add(vd.name());
            }
        }
        for (var entry : fn.localTypes().entrySet()) {
            if (declared.contains(entry.getKey())) {
                continue;
            }
            predeclaredAtMethodScope.add(entry.getKey());
            boolean varQ32 = q32Mode && entry.getValue() == ConceptType.F64 && q32Plan.isQ32Var(fn, entry.getKey());
            sb.append("        ")
                    .append(varQ32 ? "long" : mapType(entry.getValue(), options))
                    .append(' ')
                    .append(entry.getKey())
                    .append(" = ")
                    .append(varQ32 ? "0L" : defaultLiteral(entry.getValue(), options))
                    .append(";\n");
        }
        emitStatements(
                sb, fn, fn.body(), options, q32Plan, fnByKey, fnByName, 2, false, predeclaredAtMethodScope);
        sb.append("    }\n");
    }

    private static void emitJminorFunction(
            StringBuilder sb,
            ConceptFunction fn,
            EmitOptions options,
            Q32LoweringPlan q32Plan,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName) {
        String fnHash = BytecodeConceptIr.canonicalHash(fn);
        sb.append("@effects(pure=")
                .append(fn.pureCompute())
                .append(", locals=")
                .append(fn.localTypes().size())
                .append(", canonical_hash=\"")
                .append(fnHash)
                .append("\")\n");
        sb.append("public static fn ")
                .append(fn.name())
                .append('(');
        List<ConceptParam> params = fn.parameters();
        for (int i = 0; i < params.size(); i++) {
            ConceptParam p = params.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(mapParamType(fn, p, i, options, q32Plan)).append(' ').append(p.name());
        }
        sb.append(") -> ")
                .append(mapReturnType(fn, options, q32Plan))
                .append(" {\n");
        emitStatements(sb, fn, fn.body(), options, q32Plan, fnByKey, fnByName, 1, true);
        sb.append("}\n");
    }

    private static void emitJavaBatchFunction(
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
                emitJavaGrid3DFunction(sb, kernel, options, q32Plan);
            }
            case CORNER_GRID_4X8X4 -> emitJavaCornerGrid4x8x4Function(sb, spec, options, q32Plan);
            case SECTION_4096_VOXEL -> emitJavaSection4096VoxelFunction(sb, spec, options, q32Plan);
            case SECTION_4096_LCA_NIBBLE_PACKED, CORNER_SLICE_5X49_PACKED ->
                    emitJavaLcaPackedBatchFunction(sb, spec, options, q32Plan);
            case DENSITY_VOLUME_BATCH_F32 -> emitJavaDensityVolumeBatchFunction(sb, spec, options, q32Plan);
            case SECTION_PALETTE_FILL_U2 -> emitJavaSectionPaletteFillU2Function(sb, spec, options, q32Plan);
        }
    }

    private static void emitJavaDensityVolumeBatchFunction(
            StringBuilder sb,
            ConceptBatchSpec spec,
            EmitOptions options,
            Q32LoweringPlan q32Plan) {
        ConceptFunction target = spec.targetFunction();
        boolean q32Ret = options.gpuExactArithmeticMode().usesInt64Q32()
                && q32Plan.isQ32Return(target);
        sb.append("    public static void ")
                .append(spec.kernelName())
                .append("(\n")
                .append("            float[] outValues,\n")
                .append("            int[] batchOriginsXyz,\n")
                .append("            int batchCount,\n")
                .append("            int sizeX,\n")
                .append("            int sizeY,\n")
                .append("            int sizeZ,\n")
                .append("            int stepX,\n")
                .append("            int stepY,\n")
                .append("            int stepZ");
        for (ConceptParam extra : spec.extraUniformParams()) {
            sb.append(",\n            ")
                    .append(mapExtraUniformType(target, extra, options, q32Plan))
                    .append(' ')
                    .append(extra.name());
        }
        sb.append(") {\n")
                .append("        int volumeSize = sizeX * sizeY * sizeZ;\n")
                .append("        for (int b = 0; b < batchCount; b++) {\n")
                .append("            int ox = batchOriginsXyz[b * 3];\n")
                .append("            int oy = batchOriginsXyz[b * 3 + 1];\n")
                .append("            int oz = batchOriginsXyz[b * 3 + 2];\n")
                .append("            int base = b * volumeSize;\n")
                .append("            for (int lz = 0; lz < sizeZ; lz++) {\n")
                .append("                int bz = oz + lz * stepZ;\n")
                .append("                for (int lx = 0; lx < sizeX; lx++) {\n")
                .append("                    int bx = ox + lx * stepX;\n")
                .append("                    int colBase = base + (lx + lz * sizeX) * sizeY;\n")
                .append("                    for (int ly = 0; ly < sizeY; ly++) {\n")
                .append("                        int by = oy + ly * stepY;\n");
        String callExpr = buildIntCoordTargetCall(target, "bx", "by", "bz", options, q32Plan);
        if (q32Ret) {
            sb.append("                        outValues[colBase + ly] = q32_to_f32(").append(callExpr).append(");\n");
        } else {
            sb.append("                        outValues[colBase + ly] = (float) (").append(callExpr).append(");\n");
        }
        sb.append("                    }\n")
                .append("                }\n")
                .append("            }\n")
                .append("        }\n")
                .append("    }\n");
    }

    private static void emitJavaSectionPaletteFillU2Function(
            StringBuilder sb,
            ConceptBatchSpec spec,
            EmitOptions options,
            Q32LoweringPlan q32Plan) {
        ConceptFunction target = spec.targetFunction();
        boolean q32Ret = options.gpuExactArithmeticMode().usesInt64Q32()
                && q32Plan.isQ32Return(target);
        sb.append("    public static void ")
                .append(spec.kernelName())
                .append("(\n")
                .append("            int[] outSectionWords,\n")
                .append("            short[] outSectionMeta,\n")
                .append("            int[] batchOriginsXyz,\n")
                .append("            int batchCount,\n")
                .append("            int sectionCount,\n")
                .append("            int seaLevel,\n")
                .append("            int deepFluidY");
        for (ConceptParam extra : spec.extraUniformParams()) {
            sb.append(",\n            ")
                    .append(mapExtraUniformType(target, extra, options, q32Plan))
                    .append(' ')
                    .append(extra.name());
        }
        sb.append(") {\n")
                .append("        for (int b = 0; b < batchCount; b++) {\n")
                .append("            int ox = batchOriginsXyz[b * 3];\n")
                .append("            int oy = batchOriginsXyz[b * 3 + 1];\n")
                .append("            int oz = batchOriginsXyz[b * 3 + 2];\n")
                .append("            for (int s = 0; s < sectionCount; s++) {\n")
                .append("                int wgId = b * sectionCount + s;\n")
                .append("                int wordBase = wgId << 8;\n")
                .append("                int baseY = oy + (s << 4);\n")
                .append("                int nonEmpty = 0;\n")
                .append("                int fluid = 0;\n")
                .append("                int mask = 0;\n")
                .append("                for (int ly = 0; ly < 16; ly++) {\n")
                .append("                    int by = baseY + ly;\n")
                .append("                    for (int lz = 0; lz < 16; lz++) {\n")
                .append("                        int bz = oz + lz;\n")
                .append("                        int packed = 0;\n")
                .append("                        for (int lx = 0; lx < 16; lx++) {\n")
                .append("                            int bx = ox + lx;\n");
        String callExpr = buildIntCoordTargetCall(target, "bx", "by", "bz", options, q32Plan);
        if (q32Ret) {
            sb.append("                            float d = q32_to_f32(").append(callExpr).append(");\n");
        } else {
            sb.append("                            float d = (float) (").append(callExpr).append(");\n");
        }
        sb.append("                            int code;\n")
                .append("                            if (d > 0.0f) {\n")
                .append("                                code = 1;\n")
                .append("                                nonEmpty++;\n")
                .append("                            } else if (by < deepFluidY) {\n")
                .append("                                code = 3;\n")
                .append("                                nonEmpty++;\n")
                .append("                                fluid++;\n")
                .append("                            } else if (by < seaLevel) {\n")
                .append("                                code = 2;\n")
                .append("                                nonEmpty++;\n")
                .append("                                fluid++;\n")
                .append("                            } else {\n")
                .append("                                code = 0;\n")
                .append("                            }\n")
                .append("                            mask |= (1 << code);\n")
                .append("                            packed |= (code << (lx << 1));\n")
                .append("                        }\n")
                .append("                        outSectionWords[wordBase + (ly << 4) + lz] = packed;\n")
                .append("                    }\n")
                .append("                }\n")
                .append("                int metaBase = wgId << 2;\n")
                .append("                outSectionMeta[metaBase] = (short) nonEmpty;\n")
                .append("                outSectionMeta[metaBase + 1] = (short) fluid;\n")
                .append("                outSectionMeta[metaBase + 2] = (short) mask;\n")
                .append("                outSectionMeta[metaBase + 3] = 0;\n")
                .append("            }\n")
                .append("        }\n")
                .append("    }\n");
    }

    private static void emitJavaGrid3DFunction(
            StringBuilder sb,
            ConceptKernel3D kernel,
            EmitOptions options,
            Q32LoweringPlan q32Plan) {
        ConceptFunction target = kernel.targetFunction();
        boolean q32GridCoords = options.gpuExactArithmeticMode().usesInt64Q32()
                && hasAnyQ32CoordParam(target, q32Plan);
        String coordScalar = q32GridCoords ? "long" : (isFp32(options) ? "float" : "double");
        String outElemType = mapReturnType(target, options, q32Plan);

        sb.append("    public static void ")
                .append(kernel.kernelName())
                .append("(\n")
                .append("            ")
                .append(outElemType)
                .append("[] ")
                .append(kernel.outputBufferParam())
                .append(",\n")
                .append("            int sizeX,\n")
                .append("            int sizeY,\n")
                .append("            int sizeZ,\n")
                .append("            ")
                .append(coordScalar)
                .append(' ')
                .append(kernel.originXParam())
                .append(",\n")
                .append("            ")
                .append(coordScalar)
                .append(' ')
                .append(kernel.originYParam())
                .append(",\n")
                .append("            ")
                .append(coordScalar)
                .append(' ')
                .append(kernel.originZParam())
                .append(",\n")
                .append("            ")
                .append(coordScalar)
                .append(' ')
                .append(kernel.scaleParam());

        for (ConceptParam extra : kernel.extraUniformParams()) {
            sb.append(",\n            ")
                    .append(mapExtraUniformType(target, extra, options, q32Plan))
                    .append(' ')
                    .append(extra.name());
        }
        sb.append(") {\n");
        sb.append("        for (int gy = 0; gy < sizeY; gy++) {\n");
        sb.append("            for (int gz = 0; gz < sizeZ; gz++) {\n");
        sb.append("                for (int gx = 0; gx < sizeX; gx++) {\n");
        sb.append("                    int linearIdx = (gy * sizeZ + gz) * sizeX + gx;\n");

        List<String> callArgs = new ArrayList<>();
        List<ConceptParam> targetParams = target.parameters();
        String[] coordExprs = q32GridCoords
                ? new String[] {
                    "q32_add(" + kernel.originXParam() + ", q32_mul(q32_from_i32(gx), " + kernel.scaleParam() + "))",
                    "q32_add(" + kernel.originYParam() + ", q32_mul(q32_from_i32(gy), " + kernel.scaleParam() + "))",
                    "q32_add(" + kernel.originZParam() + ", q32_mul(q32_from_i32(gz), " + kernel.scaleParam() + "))"
                }
                : new String[] {
                    kernel.originXParam() + " + gx * " + kernel.scaleParam(),
                    kernel.originYParam() + " + gy * " + kernel.scaleParam(),
                    kernel.originZParam() + " + gz * " + kernel.scaleParam()
                };
        for (int i = 0; i < targetParams.size(); i++) {
            ConceptParam tp = targetParams.get(i);
            if (i < 3) {
                String gridVar = i == 0 ? "gx" : i == 1 ? "gy" : "gz";
                String originVar = i == 0 ? kernel.originXParam()
                        : i == 1 ? kernel.originYParam() : kernel.originZParam();
                if (q32GridCoords) {
                    if (tp.type() == ConceptType.I64) {
                        callArgs.add("((long) " + gridVar + " + q32_trunc_i64(" + originVar + "))");
                    } else if (tp.type() == ConceptType.I32) {
                        callArgs.add("(" + gridVar + " + q32_trunc_i32(" + originVar + "))");
                    } else if (tp.type() == ConceptType.F64 && q32Plan.isQ32Param(target, i)) {
                        callArgs.add(coordExprs[i]);
                    } else {
                        callArgs.add("q32_to_f32(" + coordExprs[i] + ")");
                    }
                } else {
                    if (tp.type() == ConceptType.I64) {
                        callArgs.add("((long) " + gridVar + " + (long) " + originVar + ")");
                    } else if (tp.type() == ConceptType.I32) {
                        callArgs.add("(" + gridVar + " + (int) " + originVar + ")");
                    } else if (tp.type() == ConceptType.F32 && !isFp32(options)) {
                        callArgs.add("(float) (" + coordExprs[i] + ")");
                    } else {
                        callArgs.add(coordExprs[i]);
                    }
                }
            } else {
                callArgs.add(tp.name());
            }
        }

        sb.append("                    ")
                .append(kernel.outputBufferParam())
                .append("[linearIdx] = ")
                .append(target.name())
                .append('(')
                .append(String.join(", ", callArgs))
                .append(");\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n");
    }

    private static void emitJavaCornerGrid4x8x4Function(
            StringBuilder sb,
            ConceptBatchSpec spec,
            EmitOptions options,
            Q32LoweringPlan q32Plan) {
        ConceptFunction target = spec.targetFunction();
        String outElemType = mapReturnType(target, options, q32Plan);

        sb.append("    public static void ")
                .append(spec.kernelName())
                .append("(\n")
                .append("            ")
                .append(outElemType)
                .append("[] out_corners,\n")
                .append("            int[] batch_origins,\n")
                .append("            int batch_count,\n")
                .append("            int corners_x,\n")
                .append("            int corners_y,\n")
                .append("            int corners_z");
        for (ConceptParam extra : spec.extraUniformParams()) {
            sb.append(",\n            ")
                    .append(mapExtraUniformType(target, extra, options, q32Plan))
                    .append(' ')
                    .append(extra.name());
        }
        sb.append(") {\n")
                .append("        int corners_per_batch = corners_x * corners_y * corners_z;\n")
                .append("        for (int batch_idx = 0; batch_idx < batch_count; batch_idx++) {\n")
                .append("            int orig_x = batch_origins[batch_idx * 4];\n")
                .append("            int orig_y = batch_origins[batch_idx * 4 + 1];\n")
                .append("            int orig_z = batch_origins[batch_idx * 4 + 2];\n")
                .append("            for (int corner_idx = 0; corner_idx < corners_per_batch; corner_idx++) {\n")
                .append("                int cx = corner_idx % corners_x;\n")
                .append("                int rem = corner_idx / corners_x;\n")
                .append("                int cz = rem % corners_z;\n")
                .append("                int cy = rem / corners_z;\n")
                .append("                int bx = orig_x + cx * ").append(spec.stepX()).append(";\n")
                .append("                int by = orig_y + cy * ").append(spec.stepY()).append(";\n")
                .append("                int bz = orig_z + cz * ").append(spec.stepZ()).append(";\n")
                .append("                out_corners[batch_idx * corners_per_batch + corner_idx] = ")
                .append(buildIntCoordTargetCall(target, "bx", "by", "bz", options, q32Plan))
                .append(";\n")
                .append("            }\n")
                .append("        }\n")
                .append("    }\n");
    }

    private static void emitJavaSection4096VoxelFunction(
            StringBuilder sb,
            ConceptBatchSpec spec,
            EmitOptions options,
            Q32LoweringPlan q32Plan) {
        ConceptFunction target = spec.targetFunction();
        String outElemType = mapReturnType(target, options, q32Plan);

        sb.append("    public static void ")
                .append(spec.kernelName())
                .append("(\n")
                .append("            ")
                .append(outElemType)
                .append("[] out_voxels,\n")
                .append("            int[] section_origins,\n")
                .append("            int section_count");
        for (ConceptParam extra : spec.extraUniformParams()) {
            sb.append(",\n            ")
                    .append(mapExtraUniformType(target, extra, options, q32Plan))
                    .append(' ')
                    .append(extra.name());
        }
        sb.append(") {\n")
                .append("        for (int sec_idx = 0; sec_idx < section_count; sec_idx++) {\n")
                .append("            int orig_x = section_origins[sec_idx * 4];\n")
                .append("            int orig_y = section_origins[sec_idx * 4 + 1];\n")
                .append("            int orig_z = section_origins[sec_idx * 4 + 2];\n")
                .append("            int base_out = sec_idx * 4096;\n")
                .append("            for (int v_idx = 0; v_idx < 4096; v_idx++) {\n")
                .append("                int lx = v_idx & 15;\n")
                .append("                int lz = (v_idx >> 4) & 15;\n")
                .append("                int ly = (v_idx >> 8) & 15;\n")
                .append("                int bx = orig_x + lx;\n")
                .append("                int by = orig_y + ly;\n")
                .append("                int bz = orig_z + lz;\n")
                .append("                out_voxels[base_out + v_idx] = ")
                .append(buildIntCoordTargetCall(target, "bx", "by", "bz", options, q32Plan))
                .append(";\n")
                .append("            }\n")
                .append("        }\n")
                .append("    }\n");
    }

    private static void emitJavaLcaPackedBatchFunction(
            StringBuilder sb,
            ConceptBatchSpec spec,
            EmitOptions options,
            Q32LoweringPlan q32Plan) {
        ConceptFunction target = spec.targetFunction();
        String callExpr = buildIntCoordTargetCall(target, "bx", "by", "bz", options, q32Plan);
        String nibbleExpr;
        if (target.returnType().isInteger()) {
            nibbleExpr = "(int) (" + callExpr + ")";
        } else if (target.returnType() == ConceptType.F64 && q32Plan.isQ32Return(target)) {
            nibbleExpr = "((" + callExpr + ") > 0L) ? 1 : 0";
        } else if (options.gpuExactArithmeticMode().usesNativeFp64() && target.returnType() == ConceptType.F64) {
            nibbleExpr = "((" + callExpr + ") > 0.0) ? 1 : 0";
        } else {
            nibbleExpr = "((" + callExpr + ") > 0.0f) ? 1 : 0";
        }

        sb.append("    public static void ")
                .append(spec.kernelName())
                .append("(\n")
                .append("            int[] out_lca_frames,\n")
                .append("            int[] section_origins,\n")
                .append("            int section_count");
        for (ConceptParam extra : spec.extraUniformParams()) {
            sb.append(",\n            ")
                    .append(mapExtraUniformType(target, extra, options, q32Plan))
                    .append(' ')
                    .append(extra.name());
        }
        sb.append(") {\n")
                .append("        for (int sec_idx = 0; sec_idx < section_count; sec_idx++) {\n")
                .append("            int orig_x = section_origins[sec_idx * 4];\n")
                .append("            int orig_y = section_origins[sec_idx * 4 + 1];\n")
                .append("            int orig_z = section_origins[sec_idx * 4 + 2];\n")
                .append("            int sec_offset = sec_idx * 516;\n")
                .append("            out_lca_frames[sec_offset] = 0;\n")
                .append("            out_lca_frames[sec_offset + 1] = 0;\n")
                .append("            out_lca_frames[sec_offset + 2] = 0;\n")
                .append("            out_lca_frames[sec_offset + 3] = 0;\n")
                .append("            for (int wg = 0; wg < 16; wg++) {\n")
                .append("                int[] local_words = new int[32];\n")
                .append("                for (int lid = 0; lid < 256; lid++) {\n")
                .append("                    int v_idx = (wg << 8) | lid;\n")
                .append("                    int lx = v_idx & 15;\n")
                .append("                    int lz = (v_idx >> 4) & 15;\n")
                .append("                    int ly = (v_idx >> 8) & 15;\n")
                .append("                    int bx = orig_x + lx;\n")
                .append("                    int by = orig_y + ly;\n")
                .append("                    int bz = orig_z + lz;\n")
                .append("                    int st = ").append(nibbleExpr).append(";\n")
                .append("                    local_words[lid >> 3] |= ((st & 0xF) << ((lx & 7) << 2));\n")
                .append("                }\n")
                .append("                int first_nib = local_words[0] & 0xF;\n")
                .append("                int uniform_w = first_nib * 0x11111111;\n")
                .append("                int diff_mask = 0;\n")
                .append("                for (int w_idx = 0; w_idx < 32; w_idx++) {\n")
                .append("                    int w = local_words[w_idx];\n")
                .append("                    out_lca_frames[sec_offset + 4 + (wg << 5) + w_idx] = w;\n")
                .append("                    if (w != uniform_w) {\n")
                .append("                        diff_mask |= (1 << (w_idx >> 3));\n")
                .append("                    }\n")
                .append("                }\n")
                .append("                int meta_byte = first_nib | ((diff_mask & 0xF) << 4);\n")
                .append("                out_lca_frames[sec_offset + (wg >> 2)] |= (meta_byte << ((wg & 3) << 3));\n")
                .append("            }\n")
                .append("        }\n")
                .append("    }\n");
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
            case I64 -> "((long) " + coordVar + ")";
            case F32 -> "((float) " + coordVar + ")";
            case F64 -> {
                if (options.gpuExactArithmeticMode().usesInt64Q32() && q32Plan.isQ32Param(target, paramIndex)) {
                    yield "q32_from_i32(" + coordVar + ")";
                }
                yield options.gpuExactArithmeticMode().usesNativeFp64()
                        ? "((double) " + coordVar + ")"
                        : "((float) " + coordVar + ")";
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

    private static void emitStatements(
            StringBuilder sb,
            ConceptFunction fn,
            List<ConceptStmt> stmts,
            EmitOptions options,
            Q32LoweringPlan q32Plan,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName,
            int indentLevel,
            boolean compactGuards) {
        emitStatements(sb, fn, stmts, options, q32Plan, fnByKey, fnByName, indentLevel, compactGuards, Set.of());
    }

    private static void emitStatements(
            StringBuilder sb,
            ConceptFunction fn,
            List<ConceptStmt> stmts,
            EmitOptions options,
            Q32LoweringPlan q32Plan,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName,
            int indentLevel,
            boolean compactGuards,
            Set<String> predeclaredAtMethodScope) {
        String indent = "    ".repeat(indentLevel);
        boolean q32Mode = options.gpuExactArithmeticMode().usesInt64Q32();
        Set<String> declaredInBlock = new HashSet<>(predeclaredAtMethodScope);
        for (ConceptStmt stmt : stmts) {
            if (stmt instanceof ConceptStmt.VarDecl decl) {
                boolean varQ32 = q32Mode && decl.type() == ConceptType.F64 && q32Plan.isQ32Var(fn, decl.name());
                if (declaredInBlock.contains(decl.name())) {
                    if (decl.initializer() != null) {
                        sb.append(indent)
                                .append(decl.name())
                                .append(" = ")
                                .append(emitExpr(
                                        decl.initializer(), fn, options, q32Plan, fnByKey, fnByName, varQ32))
                                .append(";\n");
                    }
                } else {
                    declaredInBlock.add(decl.name());
                    sb.append(indent)
                            .append(varQ32 ? "long" : mapType(decl.type(), options))
                            .append(' ')
                            .append(decl.name());
                    if (decl.initializer() != null) {
                        sb.append(" = ").append(emitExpr(
                                decl.initializer(), fn, options, q32Plan, fnByKey, fnByName, varQ32));
                    }
                    sb.append(";\n");
                }
            } else if (stmt instanceof ConceptStmt.Assign assign) {
                boolean varQ32 = q32Mode && assign.type() == ConceptType.F64 && q32Plan.isQ32Var(fn, assign.name());
                sb.append(indent)
                        .append(assign.name())
                        .append(" = ")
                        .append(emitExpr(assign.value(), fn, options, q32Plan, fnByKey, fnByName, varQ32))
                        .append(";\n");
            } else if (stmt instanceof ConceptStmt.If ifStmt) {
                if (compactGuards
                        && ifStmt.elseBody().isEmpty()
                        && ifStmt.thenBody().size() == 1
                        && ifStmt.thenBody().get(0) instanceof ConceptStmt.Return retGuard) {
                    boolean retQ32 = q32Mode && fn.returnType() == ConceptType.F64 && q32Plan.isQ32Return(fn);
                    sb.append(indent)
                            .append("if (")
                            .append(emitExpr(ifStmt.condition(), fn, options, q32Plan, fnByKey, fnByName, false))
                            .append(") return")
                            .append(retGuard.value() != null
                                    ? " " + emitExpr(retGuard.value(), fn, options, q32Plan, fnByKey, fnByName, retQ32)
                                    : "")
                            .append(";\n");
                    continue;
                }
                sb.append(indent)
                        .append("if (")
                        .append(emitExpr(ifStmt.condition(), fn, options, q32Plan, fnByKey, fnByName, false))
                        .append(") {\n");
                emitStatements(
                        sb,
                        fn,
                        ifStmt.thenBody(),
                        options,
                        q32Plan,
                        fnByKey,
                        fnByName,
                        indentLevel + 1,
                        compactGuards,
                        declaredInBlock);
                if (!ifStmt.elseBody().isEmpty()) {
                    sb.append(indent).append("} else {\n");
                    emitStatements(
                            sb,
                            fn,
                            ifStmt.elseBody(),
                            options,
                            q32Plan,
                            fnByKey,
                            fnByName,
                            indentLevel + 1,
                            compactGuards,
                            declaredInBlock);
                }
                sb.append(indent).append("}\n");
            } else if (stmt instanceof ConceptStmt.While whileStmt) {
                sb.append(indent)
                        .append("while (")
                        .append(emitExpr(whileStmt.condition(), fn, options, q32Plan, fnByKey, fnByName, false))
                        .append(") {\n");
                emitStatements(
                        sb,
                        fn,
                        whileStmt.body(),
                        options,
                        q32Plan,
                        fnByKey,
                        fnByName,
                        indentLevel + 1,
                        compactGuards,
                        declaredInBlock);
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
        boolean q32Mode = options.gpuExactArithmeticMode().usesInt64Q32();

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
        if (expr instanceof ConceptExpr.IntConst ic) {
            if (expectQ32) {
                return (((long) ic.value()) << 32) + "L";
            }
            return Integer.toString(ic.value());
        }
        if (expr instanceof ConceptExpr.LongConst lc) {
            if (expectQ32) {
                return (lc.value() << 32) + "L";
            }
            return lc.value() + "L";
        }
        if (expr instanceof ConceptExpr.FloatConst fc) {
            if (expectQ32) {
                return ConceptOptimizerPass.doubleToQ32(fc.value()) + "L";
            }
            return formatFloatLiteral(fc.value());
        }
        if (expr instanceof ConceptExpr.DoubleConst dc) {
            if (expectQ32) {
                return ConceptOptimizerPass.doubleToQ32(dc.value()) + "L";
            }
            if (isFp32(options)) {
                return formatFloatLiteral((float) dc.value());
            }
            return formatDouble(dc.value());
        }
        if (expr instanceof ConceptExpr.BoolConst bc) {
            return Boolean.toString(bc.value());
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
        if (expr instanceof ConceptExpr.BinaryOp b) {
            if (q32Mode) {
                if (b.op().isComparison() && q32Plan.shouldPromoteComparisonToQ32(fn, b)) {
                    String lq = emitExpr(b.left(), fn, options, q32Plan, fnByKey, fnByName, true);
                    String rq = emitExpr(b.right(), fn, options, q32Plan, fnByKey, fnByName, true);
                    return "(" + lq + " " + b.op().symbol() + " " + rq + ")";
                }
                if (expectQ32 && b.type() == ConceptType.F64
                        && (b.op() == BinaryOperator.ADD
                                || b.op() == BinaryOperator.SUB
                                || b.op() == BinaryOperator.MUL
                                || b.op() == BinaryOperator.DIV
                                || b.op() == BinaryOperator.REM)) {
                    return emitQ32BinaryOp(b, fn, options, q32Plan, fnByKey, fnByName);
                }
                if (!expectQ32 && q32Plan.shouldPromoteAddSubToQ32(fn, b)) {
                    return "q32_to_f32(" + emitQ32BinaryOp(b, fn, options, q32Plan, fnByKey, fnByName) + ")";
                }
            }
            String left = emitExpr(b.left(), fn, options, q32Plan, fnByKey, fnByName, false);
            String right = emitExpr(b.right(), fn, options, q32Plan, fnByKey, fnByName, false);
            String emitted = "(" + left + " " + b.op().symbol() + " " + right + ")";
            return expectQ32 ? convertToQ32(emitted, b.type()) : emitted;
        }
        if (expr instanceof ConceptExpr.Cast c) {
            if (q32Mode) {
                if ((c.targetType() == ConceptType.I32 || c.targetType() == ConceptType.I64)
                        && c.operand().type() == ConceptType.F64) {
                    if (c.operand() instanceof ConceptExpr.MathIntrinsic m
                            && m.kind() == MathIntrinsicKind.FLOOR
                            && !m.arguments().isEmpty()
                            && m.arguments().get(0).type() == ConceptType.F64) {
                        String argQ32 = emitExpr(m.arguments().get(0), fn, options, q32Plan, fnByKey, fnByName, true);
                        String floored = (c.targetType() == ConceptType.I32 ? "q32_floor_i32(" : "q32_floor_i64(")
                                + argQ32 + ")";
                        return expectQ32 ? convertToQ32(floored, c.targetType()) : floored;
                    }
                    String opQ32 = emitExpr(c.operand(), fn, options, q32Plan, fnByKey, fnByName, true);
                    String trunc = (c.targetType() == ConceptType.I32 ? "q32_trunc_i32(" : "q32_trunc_i64(")
                            + opQ32 + ")";
                    return expectQ32 ? convertToQ32(trunc, c.targetType()) : trunc;
                }
                if (expectQ32 && c.targetType() == ConceptType.F64) {
                    if (c.operand().type() == ConceptType.F64) {
                        return emitExpr(c.operand(), fn, options, q32Plan, fnByKey, fnByName, true);
                    }
                    String opStr = emitExpr(c.operand(), fn, options, q32Plan, fnByKey, fnByName, false);
                    return convertToQ32(opStr, c.operand().type());
                }
                if (!expectQ32 && c.targetType() == ConceptType.F32
                        && c.operand().type() == ConceptType.F64
                        && q32Plan.isQ32Expr(fn, c.operand())) {
                    return "q32_to_f32(" + emitExpr(c.operand(), fn, options, q32Plan, fnByKey, fnByName, true) + ")";
                }
            }
            if (c.targetType() == c.operand().type()) {
                return emitExpr(c.operand(), fn, options, q32Plan, fnByKey, fnByName, expectQ32);
            }
            String emitted = "((" + mapType(c.targetType(), options) + ") "
                    + emitExpr(c.operand(), fn, options, q32Plan, fnByKey, fnByName, false) + ")";
            return expectQ32 ? convertToQ32(emitted, c.targetType()) : emitted;
        }
        if (expr instanceof ConceptExpr.Ternary t) {
            boolean branchQ32 = q32Mode && expectQ32 && t.type() == ConceptType.F64;
            String emitted = "(" + emitExpr(t.condition(), fn, options, q32Plan, fnByKey, fnByName, false)
                    + " ? " + emitExpr(t.thenExpr(), fn, options, q32Plan, fnByKey, fnByName, branchQ32)
                    + " : " + emitExpr(t.elseExpr(), fn, options, q32Plan, fnByKey, fnByName, branchQ32) + ")";
            return (expectQ32 && !branchQ32) ? convertToQ32(emitted, t.type()) : emitted;
        }
        if (expr instanceof ConceptExpr.LutLoad lut) {
            String idxStr = emitExpr(lut.index(), fn, options, q32Plan, fnByKey, fnByName, false);
            if (q32Mode && expectQ32) {
                if (lut.elementType() == ConceptType.F64) {
                    String q32Sym = q32Plan.q32LutSymbol(lut.lutSymbol());
                    if (q32Sym != null) {
                        return q32Sym + "[" + idxStr + "]";
                    }
                }
                return convertToQ32(lut.lutSymbol() + "[" + idxStr + "]", lut.elementType());
            }
            return lut.lutSymbol() + "[" + idxStr + "]";
        }
        if (expr instanceof ConceptExpr.MathIntrinsic m) {
            return emitMathIntrinsic(m, fn, options, q32Plan, fnByKey, fnByName, expectQ32);
        }
        if (expr instanceof ConceptExpr.Call call) {
            ConceptFunction targetFn = resolveFunction(call, fnByKey, fnByName);
            List<String> args = new ArrayList<>(call.arguments().size());
            for (int i = 0; i < call.arguments().size(); i++) {
                boolean argQ32 = q32Mode && targetFn != null && q32Plan.isQ32Param(targetFn, i);
                args.add(emitExpr(call.arguments().get(i), fn, options, q32Plan, fnByKey, fnByName, argQ32));
            }
            String callStr = call.methodName() + "(" + String.join(", ", args) + ")";
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
        if (expr instanceof ConceptExpr.CompareTemp cmp) {
            if (q32Mode && (q32Plan.isQ32Expr(fn, cmp.left()) || q32Plan.isQ32Expr(fn, cmp.right()))) {
                return "Long.compare("
                        + emitExpr(cmp.left(), fn, options, q32Plan, fnByKey, fnByName, true) + ", "
                        + emitExpr(cmp.right(), fn, options, q32Plan, fnByKey, fnByName, true) + ")";
            }
            if (cmp.left().type() == ConceptType.F32 || isFp32(options)) {
                return "Float.compare("
                        + emitExpr(cmp.left(), fn, options, q32Plan, fnByKey, fnByName, false) + ", "
                        + emitExpr(cmp.right(), fn, options, q32Plan, fnByKey, fnByName, false) + ")";
            }
            if (cmp.left().type() == ConceptType.I64) {
                return "Long.compare("
                        + emitExpr(cmp.left(), fn, options, q32Plan, fnByKey, fnByName, false) + ", "
                        + emitExpr(cmp.right(), fn, options, q32Plan, fnByKey, fnByName, false) + ")";
            }
            return "Double.compare("
                    + emitExpr(cmp.left(), fn, options, q32Plan, fnByKey, fnByName, false) + ", "
                    + emitExpr(cmp.right(), fn, options, q32Plan, fnByKey, fnByName, false) + ")";
        }
        throw new IllegalStateException("Unhandled ConceptExpr: " + expr);
    }

    private static String emitMathIntrinsic(
            ConceptExpr.MathIntrinsic m,
            ConceptFunction fn,
            EmitOptions options,
            Q32LoweringPlan q32Plan,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName,
            boolean expectQ32) {
        boolean q32Mode = options.gpuExactArithmeticMode().usesInt64Q32();
        if (q32Mode) {
            List<ConceptExpr> rawArgs = m.arguments();
            if (m.kind() == MathIntrinsicKind.FLOOR && !rawArgs.isEmpty() && rawArgs.get(0).type() == ConceptType.F64) {
                String argQ32 = emitExpr(rawArgs.get(0), fn, options, q32Plan, fnByKey, fnByName, true);
                return expectQ32 ? "q32_floor(" + argQ32 + ")" : "q32_to_f32(q32_floor(" + argQ32 + "))";
            }
            if (m.kind() == MathIntrinsicKind.CEIL && !rawArgs.isEmpty() && rawArgs.get(0).type() == ConceptType.F64) {
                String argQ32 = emitExpr(rawArgs.get(0), fn, options, q32Plan, fnByKey, fnByName, true);
                return expectQ32 ? "q32_ceil(" + argQ32 + ")" : "q32_to_f32(q32_ceil(" + argQ32 + "))";
            }
            if (m.kind() == MathIntrinsicKind.ROUND && !rawArgs.isEmpty() && rawArgs.get(0).type() == ConceptType.F64) {
                String argQ32 = emitExpr(rawArgs.get(0), fn, options, q32Plan, fnByKey, fnByName, true);
                String shifted = "q32_add(" + argQ32 + ", 2147483648L)";
                if (m.type() == ConceptType.I64) {
                    return "q32_floor_i64(" + shifted + ")";
                }
                if (m.type() == ConceptType.I32) {
                    return "q32_floor_i32(" + shifted + ")";
                }
                return expectQ32 ? "q32_floor(" + shifted + ")" : "q32_to_f32(q32_floor(" + shifted + "))";
            }
            if (m.kind() == MathIntrinsicKind.IS_FINITE && !rawArgs.isEmpty() && q32Plan.isQ32Expr(fn, rawArgs.get(0))) {
                return "true";
            }
            if (m.kind() == MathIntrinsicKind.IS_NAN && !rawArgs.isEmpty() && q32Plan.isQ32Expr(fn, rawArgs.get(0))) {
                return "false";
            }
            if (expectQ32 && m.type() == ConceptType.F64) {
                String q32Op = switch (m.kind()) {
                    case ABS -> "q32_abs(" + emitExpr(rawArgs.get(0), fn, options, q32Plan, fnByKey, fnByName, true) + ")";
                    case MIN -> "Math.min(" + emitExpr(rawArgs.get(0), fn, options, q32Plan, fnByKey, fnByName, true)
                            + ", " + emitExpr(rawArgs.get(1), fn, options, q32Plan, fnByKey, fnByName, true) + ")";
                    case MAX -> "Math.max(" + emitExpr(rawArgs.get(0), fn, options, q32Plan, fnByKey, fnByName, true)
                            + ", " + emitExpr(rawArgs.get(1), fn, options, q32Plan, fnByKey, fnByName, true) + ")";
                    case FMA -> "q32_add(q32_mul("
                            + emitExpr(rawArgs.get(0), fn, options, q32Plan, fnByKey, fnByName, true) + ", "
                            + emitExpr(rawArgs.get(1), fn, options, q32Plan, fnByKey, fnByName, true) + "), "
                            + emitExpr(rawArgs.get(2), fn, options, q32Plan, fnByKey, fnByName, true) + ")";
                    default -> null;
                };
                if (q32Op != null) {
                    return q32Op;
                }
            }
        }

        List<String> args = new ArrayList<>(m.arguments().size());
        for (ConceptExpr a : m.arguments()) {
            args.add(emitExpr(a, fn, options, q32Plan, fnByKey, fnByName, false));
        }
        String a0 = args.isEmpty() ? "0.0" : args.get(0);
        String a1 = args.size() > 1 ? args.get(1) : "0.0";
        String a2 = args.size() > 2 ? args.get(2) : "0.0";
        boolean fp32 = isFp32(options);
        boolean arg0Float = fp32 || (!m.arguments().isEmpty() && m.arguments().get(0).type() == ConceptType.F32);
        String castPrefix = (fp32 && m.type().isFloatingPoint()) || m.type() == ConceptType.F32 ? "(float) " : "";
        String emitted = switch (m.kind()) {
            case FLOOR -> castPrefix + "Math.floor(" + a0 + ")";
            case CEIL -> castPrefix + "Math.ceil(" + a0 + ")";
            case ROUND -> "Math.round(" + a0 + ")";
            case SQRT -> castPrefix + "Math.sqrt(" + a0 + ")";
            case ABS -> "Math.abs(" + a0 + ")";
            case SIN -> castPrefix + "Math.sin(" + a0 + ")";
            case COS -> castPrefix + "Math.cos(" + a0 + ")";
            case TAN -> castPrefix + "Math.tan(" + a0 + ")";
            case ATAN2 -> castPrefix + "Math.atan2(" + a0 + ", " + a1 + ")";
            case EXP -> castPrefix + "Math.exp(" + a0 + ")";
            case LOG -> castPrefix + "Math.log(" + a0 + ")";
            case POW -> castPrefix + "Math.pow(" + a0 + ", " + a1 + ")";
            case MIN -> "Math.min(" + a0 + ", " + a1 + ")";
            case MAX -> "Math.max(" + a0 + ", " + a1 + ")";
            case FMA -> m.type() == ConceptType.F32
                    ? "Math.fma((float) (" + a0 + "), (float) (" + a1 + "), (float) (" + a2 + "))"
                    : castPrefix + "Math.fma(" + a0 + ", " + a1 + ", " + a2 + ")";
            case IS_NAN -> (arg0Float ? "Float.isNaN(" : "Double.isNaN(") + a0 + ")";
            case IS_FINITE -> (arg0Float ? "Float.isFinite(" : "Double.isFinite(") + a0 + ")";
            case POPCOUNT -> "Long.bitCount((long) (" + a0 + "))";
            case CLZ -> "Long.numberOfLeadingZeros((long) (" + a0 + "))";
            case CTZ -> "Long.numberOfTrailingZeros((long) (" + a0 + "))";
        };
        return expectQ32 ? convertToQ32(emitted, m.type()) : emitted;
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
                    return "q32_mul(" + lq + ", " + ConceptOptimizerPass.doubleToQ32(inv) + "L)";
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
        boolean fp32 = isFp32(options);
        return switch (type) {
            case VOID -> "void";
            case BOOL -> "boolean";
            case I32 -> "int";
            case I64 -> "long";
            case F32 -> "float";
            case F64 -> fp32 ? "float" : "double";
            case I32_ARRAY, GLOBAL_BUFFER_I32 -> "int[]";
            case I64_ARRAY -> "long[]";
            case F64_ARRAY, GLOBAL_BUFFER_F64 -> fp32 ? "float[]" : "double[]";
            case GLOBAL_BUFFER_F32 -> "float[]";
        };
    }

    private static String defaultLiteral(ConceptType type, EmitOptions options) {
        boolean fp32 = isFp32(options);
        return switch (type) {
            case BOOL -> "false";
            case I32 -> "0";
            case I64 -> "0L";
            case F32 -> "0.0f";
            case F64 -> fp32 ? "0.0f" : "0.0";
            default -> "0";
        };
    }

    private static boolean isFp32(EmitOptions options) {
        return !options.gpuExactArithmeticMode().usesNativeFp64();
    }

    private static String formatDouble(double d) {
        if (Double.isNaN(d)) {
            return "Double.NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Double.POSITIVE_INFINITY" : "Double.NEGATIVE_INFINITY";
        }
        String s = Double.toString(d);
        if (!s.contains(".") && !s.contains("E") && !s.contains("e")) {
            return s + ".0";
        }
        return s;
    }

    private static String formatFloatLiteral(float f) {
        if (Float.isNaN(f)) {
            return "Float.NaN";
        }
        if (Float.isInfinite(f)) {
            return f > 0 ? "Float.POSITIVE_INFINITY" : "Float.NEGATIVE_INFINITY";
        }
        String s = Float.toString(f);
        if (!s.contains(".") && !s.contains("E") && !s.contains("e")) {
            return s + ".0f";
        }
        return s + "f";
    }

    private static final Logger LOGGER = Logger.getLogger(JavaConceptBackendEmitter.class.getName());
    private static final ConcurrentHashMap<String, Double3DEvaluator> COMPILED_EVALUATOR_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, SurfaceRuleEvaluator> COMPILED_SURFACE_CACHE =
            new ConcurrentHashMap<>();

    /**
     * Compiled 3D integer-coordinate scalar evaluator produced from a pure {@link ConceptModule}.
     */
    @FunctionalInterface
    public interface Double3DEvaluator {
        double evaluate(int x, int y, int z);

        /**
         * Evaluates a vertical column {@code (x, baseY + i * stepY, z)} for {@code i in [0, countY)}
         * into {@code out[outOffset + i]}. Implementations backed by {@link JvmBytecodeConceptEmitter}
         * override this with a native bytecode loop that hoists Y-invariant 2D computations out of the
         * Y loop.
         */
        default void evaluateColumnY(
                int x, int baseY, int stepY, int countY, int z, float[] out, int outOffset) {
            for (int i = 0; i < countY; i++) {
                out[outOffset + i] = (float) evaluate(x, baseY + i * stepY, z);
            }
        }
    }

    /**
     * Compiled surface-rule / material-rule evaluator produced from a lifted {@link ConceptModule}
     * via {@link BytecodeSsaLifter#liftInstanceGraph}. Returns the interned palette ordinal
     * ({@code 0} when no rule matched).
     */
    @FunctionalInterface
    public interface SurfaceRuleEvaluator {
        int evaluate(
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
                float temperature);

        default int evaluate(
                int blockX,
                int blockY,
                int blockZ,
                int stoneDepthAbove,
                int stoneDepthBelow,
                int waterHeight,
                int surfaceDepth,
                int biomeId,
                int prelimSurfaceY) {
            return evaluate(
                    blockX,
                    blockY,
                    blockZ,
                    stoneDepthAbove,
                    stoneDepthBelow,
                    waterHeight,
                    surfaceDepth,
                    biomeId,
                    prelimSurfaceY,
                    0,
                    0,
                    0,
                    0.0,
                    0.5f);
        }
    }

    private static final MethodType SURFACE_EVAL_MT = MethodType.methodType(
            int.class,
            int.class,
            int.class,
            int.class,
            int.class,
            int.class,
            int.class,
            int.class,
            int.class,
            int.class,
            int.class,
            int.class,
            int.class,
            double.class,
            float.class);

    /**
     * Compiles a lifted surface-rule {@link ConceptModule} into a zero-allocation
     * {@link SurfaceRuleEvaluator} backed by in-memory {@code javac} bytecode (cached by canonical
     * IR hash), falling back cleanly to {@link ConceptIrEvaluator#evaluateEntry} when running on a
     * JRE without {@code javac}.
     */
    public static SurfaceRuleEvaluator compileSurfaceRuleEvaluator(ConceptModule module) {
        if (module == null || module.entryFunction() == null) {
            return (bx, by, bz, sda, sdb, wh, sd, bid, py, st, gx, gz, sec, temp) -> 0;
        }
        ConceptFunction entry = module.entryFunction();
        if (entry.body().size() == 1 && entry.body().get(0) instanceof ConceptStmt.Return ret) {
            if (ret.value() instanceof ConceptExpr.IntConst ic) {
                final int c = ic.value();
                return (bx, by, bz, sda, sdb, wh, sd, bid, py, st, gx, gz, sec, temp) -> c;
            }
        }
        String hash = BytecodeConceptIr.canonicalModuleHash(module);
        return COMPILED_SURFACE_CACHE.computeIfAbsent(hash, h -> compileSurfaceRuleEvaluatorUncached(module, h));
    }

    private static int surfaceParamSlotIndex(String paramName) {
        return switch (paramName) {
            case "blockX", "x", "p0" -> 0;
            case "blockY", "y", "p1" -> 1;
            case "blockZ", "z", "p2" -> 2;
            case "stoneDepthAbove" -> 3;
            case "stoneDepthBelow" -> 4;
            case "waterHeight" -> 5;
            case "surfaceDepth" -> 6;
            case "biomeId" -> 7;
            case "prelimSurfaceY" -> 8;
            case "steep" -> 9;
            case "surfaceGradientX" -> 10;
            case "surfaceGradientZ" -> 11;
            case "surfaceSecondary" -> 12;
            case "temperature" -> 13;
            default -> -1;
        };
    }

    private static SurfaceRuleEvaluator compileSurfaceRuleEvaluatorUncached(ConceptModule module, String hash) {
        ConceptFunction entry = module.entryFunction();
        List<ConceptParam> params = entry.parameters();
        int[] slotMap = new int[params.size()];
        for (int i = 0; i < params.size(); i++) {
            slotMap[i] = surfaceParamSlotIndex(params.get(i).name());
        }
        SurfaceRuleEvaluator irFallback = buildSurfaceIrEvaluator(module, params, slotMap);
        for (int s : slotMap) {
            if (s < 0) {
                return irFallback;
            }
        }
        // Prefer direct ClassFile v50.0 assembly (zero javac, static-setter LUT injection eliminating 64 KB <clinit>
        // overflow, compact per-helper bytecode below HotSpot's 8 KB HugeMethodLimit, and 2D (x, z) helper memoization).
        try {
            Class<?> retCls = javaClassForConceptType(entry.returnType());
            Class<?>[] paramClasses = new Class<?>[params.size()];
            Class<?>[] adaptedParamClasses = new Class<?>[params.size()];
            for (int i = 0; i < params.size(); i++) {
                paramClasses[i] = javaClassForConceptType(params.get(i).type());
                adaptedParamClasses[i] = SURFACE_EVAL_MT.parameterType(slotMap[i]);
            }
            MethodHandle rawMh = JvmBytecodeConceptEmitter.assembleEntryMethodHandle(
                    module, "Surf_" + hash, retCls, paramClasses);
            MethodHandle coercedMh = rawMh.asType(MethodType.methodType(int.class, adaptedParamClasses));
            MethodHandle permuted = MethodHandles.permuteArguments(coercedMh, SURFACE_EVAL_MT, slotMap);
            return (bx, by, bz, sda, sdb, wh, sd, bid, py, st, gx, gz, sec, temp) -> {
                try {
                    return (int) permuted.invokeExact(bx, by, bz, sda, sdb, wh, sd, bid, py, st, gx, gz, sec, temp);
                } catch (Throwable t) {
                    throw new IllegalStateException("Direct bytecode surface rule evaluation fault", t);
                }
            };
        } catch (Throwable directEx) {
            LOGGER.log(
                    Level.WARNING,
                    "[JavaConceptBackendEmitter S-004] Direct bytecode surface assembly deferred for "
                            + module.moduleName()
                            + " (" + directEx.getClass().getSimpleName() + ": " + directEx.getMessage() + "); falling back to in-memory javac",
                    directEx);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return irFallback;
        }
        try {
            String uniqueModName = "IrSurface_" + hash;
            ConceptModule renamed = new ConceptModule(
                    uniqueModName,
                    module.lookupTables(),
                    module.helperFunctions(),
                    entry,
                    null,
                    null,
                    module.unmappedReasons(),
                    module.liftedMethodSymbols(),
                    module.liftedFieldSymbols());
            EmitOptions opts = new EmitOptions(
                    FloatingPointPrecision.NATIVE_FP64,
                    false,
                    ConceptOptimizerPass.OptimizationConfig.defaults(),
                    GpuExactArithmeticMode.NATIVE_FP64,
                    BatchLayout.SINGLE_POINT);
            TranspiledArtifact art = javaBackend().emit(renamed, opts);
            String className = sanitizeClassName(uniqueModName);
            Map<String, ByteArrayOutputStream> compiledBytes = new LinkedHashMap<>();
            StandardJavaFileManager stdFm = compiler.getStandardFileManager(null, null, null);
            try (JavaFileManager memFm = new ForwardingJavaFileManager<>(stdFm) {
                @Override
                public JavaFileObject getJavaFileForOutput(
                        Location location, String name, JavaFileObject.Kind kind, FileObject sibling) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    compiledBytes.put(name, baos);
                    return new SimpleJavaFileObject(
                            URI.create("mem:///" + name.replace('.', '/') + kind.extension), kind) {
                        @Override
                        public OutputStream openOutputStream() {
                            return baos;
                        }
                    };
                }
            }) {
                JavaFileObject srcObj = new SimpleJavaFileObject(
                        URI.create("string:///" + className + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return art.sourceCode();
                    }
                };
                javax.tools.DiagnosticCollector<JavaFileObject> diags = new javax.tools.DiagnosticCollector<>();
                Boolean ok = compiler.getTask(null, memFm, diags, List.of("-proc:none"), null, List.of(srcObj)).call();
                if (!Boolean.TRUE.equals(ok) || !compiledBytes.containsKey(className)) {
                    LOGGER.log(
                            Level.WARNING,
                            "[JavaConceptBackendEmitter S-004] In-memory javac surface rule compilation rejected "
                                    + module.moduleName()
                                    + " ("
                                    + formatCompilerDiagnostics(diags)
                                    + "); falling back to ConceptIrEvaluator");
                    return irFallback;
                }
            }
            ClassLoader memCl = new ClassLoader(JavaConceptBackendEmitter.class.getClassLoader()) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    ByteArrayOutputStream b = compiledBytes.get(name);
                    if (b != null) {
                        byte[] bytes = b.toByteArray();
                        return defineClass(name, bytes, 0, bytes.length);
                    }
                    return super.findClass(name);
                }
            };
            Class<?> compiledCls = memCl.loadClass(className);
            Class<?> retCls = javaClassForConceptType(entry.returnType());
            Class<?>[] paramClasses = new Class<?>[params.size()];
            Class<?>[] adaptedParamClasses = new Class<?>[params.size()];
            for (int i = 0; i < params.size(); i++) {
                paramClasses[i] = javaClassForConceptType(params.get(i).type());
                adaptedParamClasses[i] = SURFACE_EVAL_MT.parameterType(slotMap[i]);
            }
            MethodHandle rawMh = MethodHandles.publicLookup()
                    .findStatic(compiledCls, entry.name(), MethodType.methodType(retCls, paramClasses));
            MethodHandle coercedMh = rawMh.asType(MethodType.methodType(int.class, adaptedParamClasses));
            MethodHandle permuted = MethodHandles.permuteArguments(coercedMh, SURFACE_EVAL_MT, slotMap);
            return (bx, by, bz, sda, sdb, wh, sd, bid, py, st, gx, gz, sec, temp) -> {
                try {
                    return (int) permuted.invokeExact(bx, by, bz, sda, sdb, wh, sd, bid, py, st, gx, gz, sec, temp);
                } catch (Throwable t) {
                    throw new IllegalStateException("Compiled surface rule evaluation fault", t);
                }
            };
        } catch (Throwable t) {
            LOGGER.log(
                    Level.WARNING,
                    "[JavaConceptBackendEmitter] In-memory javac surface rule compilation failed for "
                            + module.moduleName()
                            + "; falling back to ConceptIrEvaluator",
                    t);
            return irFallback;
        }
    }

    private static Class<?> javaClassForConceptType(ConceptType t) {
        return switch (t) {
            case BOOL -> boolean.class;
            case I32 -> int.class;
            case I64 -> long.class;
            case F32 -> float.class;
            case F64 -> double.class;
            case VOID -> void.class;
            case I32_ARRAY, GLOBAL_BUFFER_I32 -> int[].class;
            case I64_ARRAY -> long[].class;
            case F64_ARRAY, GLOBAL_BUFFER_F64 -> double[].class;
            case GLOBAL_BUFFER_F32 -> float[].class;
        };
    }

    private static SurfaceRuleEvaluator buildSurfaceIrEvaluator(
            ConceptModule module, List<ConceptParam> params, int[] slotMap) {
        return (bx, by, bz, sda, sdb, wh, sd, bid, py, st, gx, gz, sec, temp) -> {
            Object[] args = new Object[params.size()];
            for (int i = 0; i < params.size(); i++) {
                ConceptType pt = params.get(i).type();
                int slot = slotMap[i];
                Object raw = switch (slot) {
                    case 0 -> bx;
                    case 1 -> by;
                    case 2 -> bz;
                    case 3 -> sda;
                    case 4 -> sdb;
                    case 5 -> wh;
                    case 6 -> sd;
                    case 7 -> bid;
                    case 8 -> py;
                    case 9 -> st;
                    case 10 -> gx;
                    case 11 -> gz;
                    case 12 -> sec;
                    case 13 -> temp;
                    default -> 0;
                };
                args[i] = coerceIrArg(raw, pt);
            }
            Object res = ConceptIrEvaluator.evaluateEntry(module, args);
            return res instanceof Number n ? n.intValue() : 0;
        };
    }

    private static Object coerceIrArg(Object raw, ConceptType pt) {
        if (!(raw instanceof Number n)) {
            return raw;
        }
        return switch (pt) {
            case BOOL -> n.intValue() != 0;
            case I32 -> n.intValue();
            case I64 -> n.longValue();
            case F32 -> n.floatValue();
            case F64 -> n.doubleValue();
            case VOID -> null;
            case I32_ARRAY, GLOBAL_BUFFER_I32, I64_ARRAY, F64_ARRAY, GLOBAL_BUFFER_F64, GLOBAL_BUFFER_F32 -> raw;
        };
    }

    /**
     * Compiles a pure {@link ConceptModule} through {@link #javaBackend()} in memory via the
     * system {@link JavaCompiler} (cached by canonical IR hash), falling back cleanly to
     * {@link ConceptIrEvaluator#evaluateEntry} when running on a JRE without {@code javac}.
     */
    public static Double3DEvaluator compileToDouble3D(ConceptModule module) {
        Objects.requireNonNull(module, "module");
        ConceptFunction entry = module.entryFunction();
        if (entry == null) {
            return (x, y, z) -> 0.0;
        }
        if (entry.body().size() == 1 && entry.body().get(0) instanceof ConceptStmt.Return ret) {
            if (ret.value() instanceof ConceptExpr.DoubleConst dc) {
                final double c = dc.value();
                return (x, y, z) -> c;
            }
            if (ret.value() instanceof ConceptExpr.FloatConst fc) {
                final double c = fc.value();
                return (x, y, z) -> c;
            }
            if (ret.value() instanceof ConceptExpr.IntConst ic) {
                final double c = ic.value();
                return (x, y, z) -> c;
            }
        }
        String hash = BytecodeConceptIr.canonicalModuleHash(module);
        return COMPILED_EVALUATOR_CACHE.computeIfAbsent(hash, h -> compileToDouble3DUncached(module, h));
    }

    /**
     * Returns {@code true} if the module's entry function does not depend on its second coordinate
     * parameter ({@code y}), enabling O(1) per-column vertical caching.
     */
    public static boolean isColumnConstantY(ConceptModule module) {
        if (module == null || module.entryFunction() == null) {
            return false;
        }
        ConceptFunction entry = module.entryFunction();
        List<ConceptParam> params = entry.parameters();
        if (params.size() < 2) {
            return true;
        }
        String yParam = params.get(1).name();
        return !stmtsReferenceVar(entry.body(), yParam);
    }

    private static boolean stmtsReferenceVar(List<ConceptStmt> stmts, String varName) {
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.Assign a && exprReferencesVar(a.value(), varName)) {
                return true;
            } else if (s instanceof ConceptStmt.VarDecl d
                    && d.initializer() != null
                    && exprReferencesVar(d.initializer(), varName)) {
                return true;
            } else if (s instanceof ConceptStmt.Return r
                    && r.value() != null
                    && exprReferencesVar(r.value(), varName)) {
                return true;
            } else if (s instanceof ConceptStmt.If iff) {
                if (exprReferencesVar(iff.condition(), varName)
                        || stmtsReferenceVar(iff.thenBody(), varName)
                        || stmtsReferenceVar(iff.elseBody(), varName)) {
                    return true;
                }
            } else if (s instanceof ConceptStmt.While wh) {
                if (exprReferencesVar(wh.condition(), varName)
                        || stmtsReferenceVar(wh.body(), varName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean exprReferencesVar(ConceptExpr expr, String varName) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof ConceptExpr.Var v) {
            return varName.equals(v.name());
        }
        if (expr instanceof ConceptExpr.UnaryOp u) {
            return exprReferencesVar(u.operand(), varName);
        }
        if (expr instanceof ConceptExpr.BinaryOp b) {
            return exprReferencesVar(b.left(), varName) || exprReferencesVar(b.right(), varName);
        }
        if (expr instanceof ConceptExpr.Cast c) {
            return exprReferencesVar(c.operand(), varName);
        }
        if (expr instanceof ConceptExpr.Ternary t) {
            return exprReferencesVar(t.condition(), varName)
                    || exprReferencesVar(t.thenExpr(), varName)
                    || exprReferencesVar(t.elseExpr(), varName);
        }
        if (expr instanceof ConceptExpr.LutLoad l) {
            return exprReferencesVar(l.index(), varName);
        }
        if (expr instanceof ConceptExpr.MathIntrinsic m) {
            for (ConceptExpr a : m.arguments()) {
                if (exprReferencesVar(a, varName)) {
                    return true;
                }
            }
            return false;
        }
        if (expr instanceof ConceptExpr.Call c) {
            for (ConceptExpr a : c.arguments()) {
                if (exprReferencesVar(a, varName)) {
                    return true;
                }
            }
            return false;
        }
        if (expr instanceof ConceptExpr.CompareTemp cmp) {
            return exprReferencesVar(cmp.left(), varName) || exprReferencesVar(cmp.right(), varName);
        }
        return false;
    }

    private static Double3DEvaluator compileToDouble3DUncached(ConceptModule module, String hash) {
        ConceptFunction entry = module.entryFunction();
        int paramCount = entry.parameters().size();
        Double3DEvaluator irFallback = buildIrEvaluator(module, paramCount);
        if (paramCount != 0 && paramCount != 3) {
            return irFallback;
        }
        for (ConceptParam p : entry.parameters()) {
            if (p.type() != ConceptType.I32) {
                return irFallback;
            }
        }
        ConceptType retType = entry.returnType();
        if (retType != ConceptType.F64 && retType != ConceptType.F32 && retType != ConceptType.I32) {
            return irFallback;
        }
        // Primary path: direct JVM .class bytecode assembly (zero javac, zero <clinit> LUT bloat, immediate C2 JIT).
        try {
            return JvmBytecodeConceptEmitter.assembleToDouble3D(module, hash);
        } catch (Throwable t) {
            LOGGER.log(
                    Level.WARNING,
                    "[JavaConceptBackendEmitter S-004] Direct JVM bytecode assembly failed for "
                            + module.moduleName()
                            + "; falling back to in-memory javac",
                    t);
        }
        Double3DEvaluator javacCompiled = compileToDouble3DViaJavac(module, hash);
        return javacCompiled != null ? javacCompiled : irFallback;
    }

    /**
     * Compiles a pure {@link ConceptModule} to a {@link Double3DEvaluator} via in-memory
     * {@code javac}, logging all compiler diagnostics ({@code S-004}) on failure.
     */
    static Double3DEvaluator compileToDouble3DViaJavac(ConceptModule module, String hash) {
        ConceptFunction entry = module.entryFunction();
        int paramCount = entry.parameters().size();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null || (paramCount != 0 && paramCount != 3)) {
            return null;
        }
        ConceptType retType = entry.returnType();
        try {
            String uniqueModName = "IrEval_" + hash;
            ConceptModule renamed = new ConceptModule(
                    uniqueModName,
                    module.lookupTables(),
                    module.helperFunctions(),
                    entry,
                    null,
                    null,
                    module.unmappedReasons(),
                    module.liftedMethodSymbols(),
                    module.liftedFieldSymbols());
            EmitOptions opts = new EmitOptions(
                    FloatingPointPrecision.NATIVE_FP64,
                    false,
                    ConceptOptimizerPass.OptimizationConfig.defaults(),
                    GpuExactArithmeticMode.NATIVE_FP64,
                    BatchLayout.SINGLE_POINT);
            TranspiledArtifact art = javaBackend().emit(renamed, opts);
            String className = sanitizeClassName(uniqueModName);
            Map<String, ByteArrayOutputStream> compiledBytes = new LinkedHashMap<>();
            StandardJavaFileManager stdFm = compiler.getStandardFileManager(null, null, null);
            try (JavaFileManager memFm = new ForwardingJavaFileManager<>(stdFm) {
                @Override
                public JavaFileObject getJavaFileForOutput(
                        Location location, String name, JavaFileObject.Kind kind, FileObject sibling) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    compiledBytes.put(name, baos);
                    return new SimpleJavaFileObject(
                            URI.create("mem:///" + name.replace('.', '/') + kind.extension), kind) {
                        @Override
                        public OutputStream openOutputStream() {
                            return baos;
                        }
                    };
                }
            }) {
                JavaFileObject srcObj = new SimpleJavaFileObject(
                        URI.create("string:///" + className + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return art.sourceCode();
                    }
                };
                javax.tools.DiagnosticCollector<JavaFileObject> diags = new javax.tools.DiagnosticCollector<>();
                Boolean ok = compiler.getTask(null, memFm, diags, List.of("-proc:none"), null, List.of(srcObj)).call();
                if (!Boolean.TRUE.equals(ok) || !compiledBytes.containsKey(className)) {
                    LOGGER.log(
                            Level.WARNING,
                            "[JavaConceptBackendEmitter S-004] In-memory javac rejected "
                                    + module.moduleName()
                                    + " ("
                                    + formatCompilerDiagnostics(diags)
                                    + "); falling back to ConceptIrEvaluator");
                    return null;
                }
            }
            ClassLoader memCl = new ClassLoader(JavaConceptBackendEmitter.class.getClassLoader()) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    ByteArrayOutputStream b = compiledBytes.get(name);
                    if (b != null) {
                        byte[] bytes = b.toByteArray();
                        return defineClass(name, bytes, 0, bytes.length);
                    }
                    return super.findClass(name);
                }
            };
            Class<?> compiledCls = memCl.loadClass(className);
            Class<?> retCls = switch (retType) {
                case F64 -> double.class;
                case F32 -> float.class;
                default -> int.class;
            };
            MethodType mt = paramCount == 0
                    ? MethodType.methodType(retCls)
                    : MethodType.methodType(retCls, int.class, int.class, int.class);
            MethodHandle rawMh = MethodHandles.publicLookup().findStatic(compiledCls, entry.name(), mt);
            if (paramCount == 0) {
                MethodHandle asDouble0 = rawMh.asType(MethodType.methodType(double.class));
                double c = (double) asDouble0.invokeExact();
                return (x, y, z) -> c;
            }
            MethodHandle asDouble3 = rawMh.asType(MethodType.methodType(double.class, int.class, int.class, int.class));
            return (x, y, z) -> {
                try {
                    return (double) asDouble3.invokeExact(x, y, z);
                } catch (Throwable t) {
                    throw new IllegalStateException("Compiled JavaConceptBackendEmitter evaluation fault", t);
                }
            };
        } catch (Throwable t) {
            LOGGER.log(
                    Level.WARNING,
                    "[JavaConceptBackendEmitter S-004] In-memory javac compilation failed for " + module.moduleName()
                            + "; falling back to ConceptIrEvaluator",
                    t);
            return null;
        }
    }

    private static String formatCompilerDiagnostics(
            javax.tools.DiagnosticCollector<JavaFileObject> diags) {
        if (diags == null || diags.getDiagnostics().isEmpty()) {
            return "no diagnostics";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (javax.tools.Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            if (shown >= 5) {
                sb.append(" (+").append(diags.getDiagnostics().size() - shown).append(" more)");
                break;
            }
            if (shown > 0) {
                sb.append("; ");
            }
            sb.append(d.getKind())
                    .append("@L")
                    .append(d.getLineNumber())
                    .append(':')
                    .append(d.getMessage(java.util.Locale.ROOT));
            shown++;
        }
        return sb.toString();
    }

    private static Double3DEvaluator buildIrEvaluator(ConceptModule module, int paramCount) {
        if (paramCount == 0) {
            double c = ((Number) ConceptIrEvaluator.evaluateEntry(module)).doubleValue();
            return (x, y, z) -> c;
        }
        if (paramCount == 1) {
            return (x, y, z) -> ((Number) ConceptIrEvaluator.evaluateEntry(module, x)).doubleValue();
        }
        if (paramCount == 2) {
            return (x, y, z) -> ((Number) ConceptIrEvaluator.evaluateEntry(module, x, y)).doubleValue();
        }
        return (x, y, z) -> ((Number) ConceptIrEvaluator.evaluateEntry(module, x, y, z)).doubleValue();
    }
}
