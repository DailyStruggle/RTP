package io.github.dailystruggle.bytecode.transpile;

import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.BinaryOperator;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptExpr;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptFunction;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptLut;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptModule;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptParam;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptStmt;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptType;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.MathIntrinsicKind;
import io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.BatchLayout;
import io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.EmitOptions;
import io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.FloatingPointPrecision;
import io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.GpuExactArithmeticMode;
import io.github.dailystruggle.bytecode.transpile.WorldgenKernelSwapper.WorldgenFidelityPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link IrIdiomRewriteRegistry}, {@link IrPatternDiagnostic}, and unified
 * {@link OpenClBackendEmitter} LUT compaction, cross-module deduplication, {@code __constant}
 * overflow spill, and GPU {@code finalDensity} corner lowering ({@code leafgen-ADR-008}).
 *
 * @governedBy ADR-115
 * @governedBy leafgen-ADR-008
 * @trace REQ-LEAFGEN-IR-001
 * @trace REQ-LEAFGEN-IR-002
 * @trace REQ-LEAFGEN-IR-003
 * @trace REQ-LEAFGEN-IR-004
 */
class IrIdiomRewriteRegistryTest {

    @Test
    @DisplayName("Isomorphic Vanilla and custom mod Perlin SSA graphs share structural fingerprint and rewrites")
    void isomorphicGraphsReceiveIdenticalStructuralRewrites() {
        ConceptModule vanillaNoise = buildSyntheticPerlinModule("net/minecraft/world/NoiseA", "permA", "sample");
        ConceptModule moddedNoise = buildSyntheticPerlinModule("com/example/mod/CustomTerrainB", "permB", "evalDensity");

        IrIdiomRewriteRegistry.RewriteResult r1 = ConceptOptimizerPass.optimizeWithDiagnostics(
                vanillaNoise,
                ConceptOptimizerPass.OptimizationConfig.forJvmBytecode(),
                IrIdiomRewriteRegistry.RewriteContext.exact("NOISE"));
        IrIdiomRewriteRegistry.RewriteResult r2 = ConceptOptimizerPass.optimizeWithDiagnostics(
                moddedNoise,
                ConceptOptimizerPass.OptimizationConfig.forJvmBytecode(),
                IrIdiomRewriteRegistry.RewriteContext.exact("NOISE"));

        assertEquals(r1.structuralFingerprint(), r2.structuralFingerprint(),
                "Isomorphic SSA graphs must share the same class-name-independent fingerprint");
        assertTrue(r1.recognizedIdioms().contains(IrIdiomRewriteRegistry.IDIOM_COMPACT_BOUNDED_I32_LUT));
        assertTrue(r1.recognizedIdioms().contains(IrIdiomRewriteRegistry.IDIOM_PERLIN_OCTAVE_KERNEL));
        assertEquals(r1.recognizedIdioms(), r2.recognizedIdioms());
        assertEquals(IrIdiomRewriteRegistry.CompactStorageKind.U8,
                r1.compactLutHints().get("permA").storageKind());
        assertEquals(768, r1.compactLutHints().get("permA").savedBytes());
    }

    @Test
    @DisplayName("Unmapped IR emits NATIVE_DELEGATED IrPatternDiagnostic with fingerprint and reason")
    void unmappedModuleEmitsStructuredDiagnostic() {
        ConceptFunction fn = new ConceptFunction(
                "com/example/ModNoise",
                "sample",
                "(III)D",
                List.of(
                        new ConceptParam("x", ConceptType.I32, 0),
                        new ConceptParam("y", ConceptType.I32, 1),
                        new ConceptParam("z", ConceptType.I32, 2)),
                ConceptType.F64,
                Map.of(),
                List.of(new ConceptStmt.Return(new ConceptExpr.DoubleConst(0.0))),
                false);
        ConceptModule unmapped = new ConceptModule(
                "ModNoise",
                List.of(),
                List.of(),
                fn,
                null,
                null,
                List.of(new BytecodeSsaLifter.UnmappedReason("com.example.ModNoise", "sample", 0, 186, "unsupported INVOKEDYNAMIC")),
                Set.of(),
                Set.of());

        IrIdiomRewriteRegistry.RewriteResult result = IrIdiomRewriteRegistry.defaults()
                .apply(unmapped, IrIdiomRewriteRegistry.RewriteContext.exact("SURFACE"));
        assertTrue(result.hasNativeDelegatedDiagnostics());
        IrPatternDiagnostic diag = result.diagnostics().stream()
                .filter(d -> d.disposition() == IrPatternDiagnostic.Disposition.NATIVE_DELEGATED)
                .findFirst()
                .orElseThrow();
        assertEquals("SURFACE", diag.stageId());
        assertTrue(diag.symbol().contains("com.example.ModNoise#sample"));
        assertEquals(16, diag.structuralFingerprint().length());
    }

    @Test
    @DisplayName("OpenClBackendEmitter compacts U8 LUTs, deduplicates across modules, and spills past 48 KB")
    void openClConstantAllocatorCompactsDeduplicatesAndSpills() {
        ConceptModule modA = buildSyntheticPerlinModule("ModA", "perm_a", "sample");
        ConceptModule modB = buildSyntheticPerlinModule("ModB", "perm_b", "sample");

        // Tiny 300 B budget: first 256 B perm table fits in __constant uchar, duplicate perm_b deduplicates,
        // third distinct 256 B table spills cleanly to OpenCL C 1.2 switch helper!
        OpenClBackendEmitter.ConstantLutAllocator alloc = new OpenClBackendEmitter.ConstantLutAllocator(300, 0);
        OpenClBackendEmitter emitter = new OpenClBackendEmitter();
        EmitOptions opts = new EmitOptions(
                FloatingPointPrecision.DOWNCAST_FP32,
                false,
                ConceptOptimizerPass.OptimizationConfig.forJvmBytecode(),
                GpuExactArithmeticMode.DOWNCAST_FP32,
                BatchLayout.SINGLE_POINT);

        var artA = emitter.emitWithAllocator(modA, opts, "fd_", alloc, false);
        var artB = emitter.emitWithAllocator(modB, opts, "sr_", alloc, false);

        assertTrue(artA.sourceCode().contains("__constant uchar fd_perm_a[256]"),
                "256-byte [0..255] LUT must compact from int to uchar");
        assertTrue(artA.sourceCode().contains("((int)fd_perm_a["),
                "Compacted uchar LUT read must promote to signed int");
        assertEquals(1, alloc.deduplicatedLutCount(), "Identical LUT across modules must deduplicate");
        assertFalse(artB.sourceCode().contains("__constant uchar sr_perm_b[256]"));
        assertTrue(artB.sourceCode().contains("((int)fd_perm_a["),
                "Deduplicated second module must alias the already-emitted LUT symbol");
        assertEquals(256, alloc.usedConstantBytes());

        // Now emit a third module with a distinct permutation table that overflows the 300 B budget.
        List<Number> shiftedPerm = new ArrayList<>(256);
        for (int i = 0; i < 256; i++) {
            shiftedPerm.add((i * 7 + 13) & 255);
        }
        ConceptFunction fnC = new ConceptFunction(
                "ModC",
                "sample",
                "(III)D",
                List.of(
                        new ConceptParam("x", ConceptType.I32, 0),
                        new ConceptParam("y", ConceptType.I32, 1),
                        new ConceptParam("z", ConceptType.I32, 2)),
                ConceptType.F64,
                Map.of(),
                List.of(new ConceptStmt.Return(new ConceptExpr.Cast(
                        ConceptType.F64,
                        new ConceptExpr.LutLoad(
                                "perm_c",
                                new ConceptExpr.BinaryOp(
                                        BinaryOperator.BIT_AND,
                                        new ConceptExpr.Var("x", ConceptType.I32),
                                        new ConceptExpr.IntConst(255),
                                        ConceptType.I32),
                                ConceptType.I32)))),
                true);
        ConceptModule modC = new ConceptModule(
                "ModC",
                List.of(new ConceptLut("perm_c", "ModC", "perm_c", ConceptType.I32, shiftedPerm)),
                List.of(),
                fnC,
                null);

        var artC = emitter.emitWithAllocator(modC, opts, "ov_", alloc, false);
        assertEquals(1, alloc.spilledLutCount(), "LUT exceeding constant budget must spill cleanly");
        assertTrue(artC.sourceCode().contains("inline int ov_perm_c_spill(int idx)"));
        assertTrue(artC.sourceCode().contains("ov_perm_c_spill("));
        assertTrue(alloc.usedConstantBytes() <= 300);
    }

    @Test
    @DisplayName("DimensionCapabilitySpec retains finalDensityModule and emits GPU __local s_corners evaluation")
    void dimensionCapabilityEmitsGpuIrCornersSection() {
        ConceptModule densityMod = buildSyntheticPerlinModule("DensityRoot", "perm_fd", "compute");
        LiftedWorldgenCapabilityTables.DimensionCapabilitySpec spec =
                new LiftedWorldgenCapabilityTables.DimensionCapabilitySpec(
                        -64, 24, -64, 384, 4, 8, 63, 1, 2, false, 0, -54,
                        new int[0], null,
                        new LiftedWorldgenCapabilityTables.CarverSpec[0],
                        new LiftedWorldgenCapabilityTables.BiomeFeatureSpec[0],
                        new LiftedWorldgenCapabilityTables.OreVeinSpec[0],
                        LiftedWorldgenCapabilityTables.RouterVeinSpec.DISABLED,
                        LiftedWorldgenCapabilityTables.GpuRouterNoiseTable.EMPTY,
                        new LiftedWorldgenCapabilityTables.LiftedPlacedFeatureSpec[0],
                        new long[0],
                        densityMod);

        assertTrue(spec.hasFinalDensityModule());
        String clSrc = OpenClBackendEmitter.emitDimensionCapabilityKernelSource(
                spec, false, WorldgenFidelityPolicy.VANILLA_LIKE,
                WorldgenKernelSwapper.GpuExactArithmeticMode.INT64_Q32_FP32);
        assertTrue(clSrc.contains("eval_lifted_final_density_ir("));
        assertTrue(clSrc.contains("__local float s_corners[1225];"));
        assertTrue(clSrc.contains("if (has_gpu_corners != 0)"));
        assertTrue(clSrc.contains("skip_biome_io"));
    }

    @Test
    @DisplayName("IrIdiomRewriteRegistry outlines isomorphic single-LUT octave helpers into one canonical helper")
    void outlinesIsomorphicSingleLutHelpers() {
        List<Number> perm0 = new ArrayList<>(256);
        List<Number> perm1 = new ArrayList<>(256);
        for (int i = 0; i < 256; i++) {
            perm0.add((i * 17 + 3) & 255);
            perm1.add((i * 31 + 11) & 255);
        }
        ConceptFunction oct0 = buildLutHelper("NoiseOct", "oct_0", "perm_0");
        ConceptFunction oct1 = buildLutHelper("NoiseOct", "oct_1", "perm_1");
        ConceptExpr sum = new ConceptExpr.BinaryOp(
                BinaryOperator.ADD,
                new ConceptExpr.Call("NoiseOct", "oct_0", "(I)D", List.of(new ConceptExpr.Var("x", ConceptType.I32)), ConceptType.F64),
                new ConceptExpr.Call("NoiseOct", "oct_1", "(I)D", List.of(new ConceptExpr.Var("x", ConceptType.I32)), ConceptType.F64),
                ConceptType.F64);
        ConceptFunction entry = new ConceptFunction(
                "NoiseOct", "sample", "(III)D",
                List.of(
                        new ConceptParam("x", ConceptType.I32, 0),
                        new ConceptParam("y", ConceptType.I32, 1),
                        new ConceptParam("z", ConceptType.I32, 2)),
                ConceptType.F64, Map.of(), List.of(new ConceptStmt.Return(sum)), true);
        ConceptModule mod = new ConceptModule(
                "NoiseOct",
                List.of(
                        new ConceptLut("perm_0", "NoiseOct", "perm_0", ConceptType.I32, perm0),
                        new ConceptLut("perm_1", "NoiseOct", "perm_1", ConceptType.I32, perm1)),
                List.of(oct0, oct1),
                entry,
                null);

        IrIdiomRewriteRegistry.RewriteResult res = ConceptOptimizerPass.optimizeWithDiagnostics(
                mod,
                ConceptOptimizerPass.OptimizationConfig.forJvmBytecode(),
                IrIdiomRewriteRegistry.RewriteContext.exact("NOISE"));
        assertTrue(res.recognizedIdioms().contains(IrIdiomRewriteRegistry.IDIOM_OUTLINE_ISOMORPHIC_LUT_HELPERS));
        assertEquals(1, res.module().helperFunctions().size(), "2 isomorphic LUT helpers must outline into 1 canonical helper");
        assertEquals(1, res.module().lookupTables().size(), "2 octave LUTs must merge into 1 flat LUT");
        assertEquals(512, res.module().lookupTables().get(0).values().size());
        // Verify numerical parity via JavaConceptBackendEmitter
        var origEval = JavaConceptBackendEmitter.compileToDouble3D(mod);
        var optEval = JavaConceptBackendEmitter.compileToDouble3D(res.module());
        for (int x = -10; x <= 20; x++) {
            assertEquals(origEval.evaluate(x, 0, 0), optEval.evaluate(x, 0, 0), 0.0,
                    "Outlined LUT helper must preserve 0.0 ULP numerical parity at x=" + x);
        }
    }

    private static ConceptFunction buildLutHelper(String owner, String fnName, String lutSym) {
        ConceptExpr xVar = new ConceptExpr.Var("x", ConceptType.I32);
        ConceptExpr idx = new ConceptExpr.BinaryOp(BinaryOperator.BIT_AND, xVar, new ConceptExpr.IntConst(255), ConceptType.I32);
        return new ConceptFunction(
                owner,
                fnName,
                "(I)D",
                List.of(new ConceptParam("x", ConceptType.I32, 0)),
                ConceptType.F64,
                Map.of(),
                List.of(new ConceptStmt.Return(new ConceptExpr.Cast(
                        ConceptType.F64,
                        new ConceptExpr.LutLoad(lutSym, idx, ConceptType.I32)))),
                true);
    }

    private static ConceptModule buildSyntheticPerlinModule(String owner, String lutSym, String fnName) {
        List<Number> perm = new ArrayList<>(256);
        for (int i = 0; i < 256; i++) {
            perm.add((i * 17 + 3) & 255);
        }
        ConceptExpr xVar = new ConceptExpr.Var("x", ConceptType.I32);
        ConceptExpr xF64 = new ConceptExpr.Cast(ConceptType.F64, xVar);
        ConceptExpr floored = new ConceptExpr.MathIntrinsic(MathIntrinsicKind.FLOOR, List.of(xF64), ConceptType.F64);
        ConceptExpr t = new ConceptExpr.BinaryOp(BinaryOperator.SUB, xF64, floored, ConceptType.F64);
        // Quintic fade: t * (t * 6.0 - 15.0) + 10.0, with algebraic no-op (* 1.0)
        ConceptExpr fade = new ConceptExpr.BinaryOp(
                BinaryOperator.MUL,
                new ConceptExpr.BinaryOp(
                        BinaryOperator.ADD,
                        new ConceptExpr.BinaryOp(
                                BinaryOperator.MUL,
                                t,
                                new ConceptExpr.BinaryOp(
                                        BinaryOperator.SUB,
                                        new ConceptExpr.BinaryOp(BinaryOperator.MUL, t, new ConceptExpr.DoubleConst(6.0), ConceptType.F64),
                                        new ConceptExpr.DoubleConst(15.0),
                                        ConceptType.F64),
                                ConceptType.F64),
                        new ConceptExpr.DoubleConst(10.0),
                        ConceptType.F64),
                new ConceptExpr.DoubleConst(1.0),
                ConceptType.F64);
        ConceptExpr maskedIdx = new ConceptExpr.BinaryOp(
                BinaryOperator.BIT_AND,
                new ConceptExpr.BinaryOp(BinaryOperator.BIT_AND, xVar, new ConceptExpr.IntConst(255), ConceptType.I32),
                new ConceptExpr.IntConst(255),
                ConceptType.I32);
        ConceptExpr lutVal = new ConceptExpr.Cast(
                ConceptType.F64,
                new ConceptExpr.LutLoad(lutSym, maskedIdx, ConceptType.I32));
        ConceptExpr sum = new ConceptExpr.BinaryOp(BinaryOperator.ADD, fade, lutVal, ConceptType.F64);

        ConceptFunction entry = new ConceptFunction(
                owner,
                fnName,
                "(III)D",
                List.of(
                        new ConceptParam("x", ConceptType.I32, 0),
                        new ConceptParam("y", ConceptType.I32, 1),
                        new ConceptParam("z", ConceptType.I32, 2)),
                ConceptType.F64,
                Map.of(),
                List.of(new ConceptStmt.Return(sum)),
                true);
        return new ConceptModule(
                owner,
                List.of(new ConceptLut(lutSym, owner, lutSym, ConceptType.I32, perm)),
                List.of(),
                entry,
                null);
    }
}
