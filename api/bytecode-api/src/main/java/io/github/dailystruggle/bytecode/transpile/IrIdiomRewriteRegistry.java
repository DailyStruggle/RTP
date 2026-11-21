package io.github.dailystruggle.bytecode.transpile;

import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.BinaryOperator;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptExpr;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptFunction;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptLut;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptModule;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptStmt;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptType;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.MathIntrinsicKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Structural SSA pattern-matching and idiom-rewriting middle-end registry over
 * {@link BytecodeConceptIr}.
 *
 * <p>Operates strictly on SSA expression/statement shapes, numeric ranges, and dataflow—never on
 * Minecraft class names or version strings—so Vanilla releases and third-party mods expressing
 * isomorphic worldgen math automatically receive the same GPU/CPU lowering optimizations, while
 * unrecognized or impure constructs emit deterministic {@link IrPatternDiagnostic} records.
 *
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy leafgen-ADR-008
 * @trace REQ-LEAFGEN-IR-002
 * @trace REQ-LEAFGEN-IR-003
 */
public final class IrIdiomRewriteRegistry {

    public static final String IDIOM_COMPACT_BOUNDED_I32_LUT = "COMPACT_BOUNDED_I32_LUT";
    public static final String IDIOM_PERLIN_OCTAVE_KERNEL = "PERLIN_OCTAVE_KERNEL";
    public static final String IDIOM_ALGEBRAIC_CLAMP_LERP = "ALGEBRAIC_CLAMP_LERP";
    public static final String IDIOM_COLUMN_Y_INVARIANT = "COLUMN_Y_INVARIANT";
    public static final String IDIOM_DEDUP_IDENTICAL_HELPERS = "DEDUP_IDENTICAL_HELPERS";
    public static final String IDIOM_OUTLINE_ISOMORPHIC_LUT_HELPERS = "OUTLINE_ISOMORPHIC_LUT_HELPERS";

    private final List<IdiomRewriteRule> rules;

    private IrIdiomRewriteRegistry(List<IdiomRewriteRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * Compact target storage class inferred purely from numeric bounds of a {@link ConceptLut}.
     */
    public enum CompactStorageKind {
        U8("uchar", 1, true),
        I8("char", 1, true),
        I16("short", 2, true),
        I32("int", 4, false),
        I64("long", 8, false),
        F32("float", 4, false),
        F64("double", 8, false);

        private final String openClType;
        private final int elementBytes;
        private final boolean requiresIntPromotion;

        CompactStorageKind(String openClType, int elementBytes, boolean requiresIntPromotion) {
            this.openClType = openClType;
            this.elementBytes = elementBytes;
            this.requiresIntPromotion = requiresIntPromotion;
        }

        public String openClType() {
            return openClType;
        }

        public int elementBytes() {
            return elementBytes;
        }

        public boolean requiresIntPromotion() {
            return requiresIntPromotion;
        }
    }

    /**
     * Per-LUT compaction metadata produced by {@link #IDIOM_COMPACT_BOUNDED_I32_LUT}.
     */
    public record CompactLutHint(
            String symbolName,
            CompactStorageKind storageKind,
            int elementCount,
            int originalBytes,
            int compactBytes
    ) {
        public int savedBytes() {
            return Math.max(0, originalBytes - compactBytes);
        }
    }

    /**
     * Policy and stage context controlling which rewrites are permitted.
     */
    public record RewriteContext(boolean exactOnly, String stageId) {
        public RewriteContext {
            stageId = (stageId == null || stageId.isBlank()) ? "IR_PIPELINE" : stageId;
        }

        public static RewriteContext exact(String stageId) {
            return new RewriteContext(true, stageId);
        }

        public static RewriteContext vanillaLike(String stageId) {
            return new RewriteContext(false, stageId);
        }
    }

    /**
     * Result of applying a single {@link IdiomRewriteRule}.
     */
    public record RuleApplication(
            ConceptModule module,
            boolean matched,
            Set<String> recognizedIdioms,
            Map<String, CompactLutHint> compactLutHints,
            List<IrPatternDiagnostic> diagnostics
    ) {
        public RuleApplication {
            Objects.requireNonNull(module, "module");
            recognizedIdioms = Set.copyOf(recognizedIdioms);
            compactLutHints = Collections.unmodifiableMap(new LinkedHashMap<>(compactLutHints));
            diagnostics = List.copyOf(diagnostics);
        }

        public static RuleApplication unchanged(ConceptModule module) {
            return new RuleApplication(module, false, Set.of(), Map.of(), List.of());
        }
    }

    /**
     * Pluggable structural SSA pattern-match and rewrite rule.
     */
    public interface IdiomRewriteRule {
        String ruleId();

        RuleApplication apply(ConceptModule module, RewriteContext context);
    }

    /**
     * Aggregate result of running the registered idiom pipeline on a {@link ConceptModule}.
     */
    public record RewriteResult(
            ConceptModule module,
            List<String> matchedRuleIds,
            Set<String> recognizedIdioms,
            Map<String, CompactLutHint> compactLutHints,
            List<IrPatternDiagnostic> diagnostics,
            String structuralFingerprint
    ) {
        public RewriteResult {
            Objects.requireNonNull(module, "module");
            matchedRuleIds = List.copyOf(matchedRuleIds);
            recognizedIdioms = Set.copyOf(recognizedIdioms);
            compactLutHints = Collections.unmodifiableMap(new LinkedHashMap<>(compactLutHints));
            diagnostics = List.copyOf(diagnostics);
            structuralFingerprint = Objects.requireNonNull(structuralFingerprint, "structuralFingerprint");
        }

        public boolean hasNativeDelegatedDiagnostics() {
            for (IrPatternDiagnostic d : diagnostics) {
                if (d.disposition() == IrPatternDiagnostic.Disposition.NATIVE_DELEGATED) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final IrIdiomRewriteRegistry DEFAULT_INSTANCE = new IrIdiomRewriteRegistry(List.of(
            new AlgebraicIdentityAndClampRule(),
            new DeduplicateIdenticalHelpersRule(),
            new OutlineIsomorphicSingleLutHelpersRule(),
            new CompactBoundedI32LutRule(),
            new PerlinOctaveKernelRecognizerRule(),
            new ColumnYInvariantRecognizerRule()
    ));

    /**
     * Returns the default registry pre-populated with canonical worldgen SSA idiom rules.
     */
    public static IrIdiomRewriteRegistry defaults() {
        return DEFAULT_INSTANCE;
    }

    /**
     * Returns a new registry with an additional structural rule appended after the existing rules.
     */
    public IrIdiomRewriteRegistry withRule(IdiomRewriteRule extraRule) {
        Objects.requireNonNull(extraRule, "extraRule");
        List<IdiomRewriteRule> next = new ArrayList<>(this.rules.size() + 1);
        next.addAll(this.rules);
        next.add(extraRule);
        return new IrIdiomRewriteRegistry(next);
    }

    /**
     * Infers the tightest value-preserving OpenCL storage class for a {@link ConceptLut}.
     */
    public static CompactLutHint analyzeLutCompaction(ConceptLut lut, boolean nativeFp64) {
        Objects.requireNonNull(lut, "lut");
        int count = lut.values().size();
        ConceptType elemType = lut.elementType();
        if (elemType == ConceptType.I32) {
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (Number n : lut.values()) {
                int v = n.intValue();
                if (v < min) {
                    min = v;
                }
                if (v > max) {
                    max = v;
                }
            }
            CompactStorageKind kind;
            if (count == 0) {
                kind = CompactStorageKind.I32;
            } else if (min >= 0 && max <= 255) {
                kind = CompactStorageKind.U8;
            } else if (min >= -128 && max <= 127) {
                kind = CompactStorageKind.I8;
            } else if (min >= -32768 && max <= 32767) {
                kind = CompactStorageKind.I16;
            } else {
                kind = CompactStorageKind.I32;
            }
            return new CompactLutHint(lut.symbolName(), kind, count, count * 4, count * kind.elementBytes());
        }
        if (elemType == ConceptType.I64) {
            return new CompactLutHint(lut.symbolName(), CompactStorageKind.I64, count, count * 8, count * 8);
        }
        if (elemType == ConceptType.F64) {
            CompactStorageKind kind = nativeFp64 ? CompactStorageKind.F64 : CompactStorageKind.F32;
            return new CompactLutHint(lut.symbolName(), kind, count, count * 8, count * kind.elementBytes());
        }
        return new CompactLutHint(lut.symbolName(), CompactStorageKind.F32, count, count * 4, count * 4);
    }

    /**
     * Runs all registered structural idiom matchers and rewriters on {@code module}, emitting
     * deterministic {@link IrPatternDiagnostic} records for matched idioms, unmapped constructs,
     * and large unoptimized subgraphs.
     */
    public RewriteResult apply(ConceptModule module, RewriteContext context) {
        Objects.requireNonNull(module, "module");
        RewriteContext ctx = context == null ? RewriteContext.exact("IR_PIPELINE") : context;

        ConceptModule current = module;
        List<String> matchedRules = new ArrayList<>();
        Set<String> recognizedIdioms = new LinkedHashSet<>();
        Map<String, CompactLutHint> lutHints = new LinkedHashMap<>();
        List<IrPatternDiagnostic> diagnostics = new ArrayList<>();

        for (IdiomRewriteRule rule : this.rules) {
            RuleApplication app = rule.apply(current, ctx);
            current = app.module();
            if (app.matched()) {
                matchedRules.add(rule.ruleId());
            }
            recognizedIdioms.addAll(app.recognizedIdioms());
            lutHints.putAll(app.compactLutHints());
            diagnostics.addAll(app.diagnostics());
        }

        String fp = IrPatternDiagnostic.structuralFingerprint(current);
        if (!current.unmappedReasons().isEmpty()) {
            for (BytecodeSsaLifter.UnmappedReason reason : current.unmappedReasons()) {
                String sym = (!reason.concreteClass().isEmpty() && !reason.method().isEmpty())
                        ? (reason.concreteClass() + "#" + reason.method())
                        : current.moduleName();
                diagnostics.add(new IrPatternDiagnostic(
                        ctx.stageId(),
                        sym,
                        fp,
                        IrPatternDiagnostic.Disposition.NATIVE_DELEGATED,
                        reason.toString()));
            }
        } else if (!current.isPureCompute()) {
            diagnostics.add(new IrPatternDiagnostic(
                    ctx.stageId(),
                    current.moduleName(),
                    fp,
                    IrPatternDiagnostic.Disposition.NATIVE_DELEGATED,
                    "module contains impure compute nodes"));
        } else if (recognizedIdioms.isEmpty() && countModuleNodes(current) >= 128) {
            diagnostics.add(new IrPatternDiagnostic(
                    ctx.stageId(),
                    current.moduleName(),
                    fp,
                    IrPatternDiagnostic.Disposition.UNOPTIMIZED_IR,
                    "pure IR graph (" + countModuleNodes(current) + " nodes) matched 0 registered idioms"));
        }

        return new RewriteResult(current, matchedRules, recognizedIdioms, lutHints, diagnostics, fp);
    }

    /**
     * Structural rule 1: Eliminates algebraic no-ops ({@code x * 1}, {@code x + 0}, {@code x - 0},
     * {@code a & 255 & 255}, redundant same-type casts) and folds duplicate min/max clamps.
     */
    private static final class AlgebraicIdentityAndClampRule implements IdiomRewriteRule {
        @Override
        public String ruleId() {
            return IDIOM_ALGEBRAIC_CLAMP_LERP;
        }

        @Override
        public RuleApplication apply(ConceptModule module, RewriteContext context) {
            boolean[] changed = {false};
            List<ConceptFunction> nextHelpers = new ArrayList<>(module.helperFunctions().size());
            for (ConceptFunction h : module.helperFunctions()) {
                nextHelpers.add(rewriteFunction(h, context.exactOnly(), changed));
            }
            ConceptFunction nextEntry = module.entryFunction() != null
                    ? rewriteFunction(module.entryFunction(), context.exactOnly(), changed)
                    : null;
            if (!changed[0]) {
                return RuleApplication.unchanged(module);
            }
            ConceptModule rewritten = new ConceptModule(
                    module.moduleName(),
                    module.lookupTables(),
                    nextHelpers,
                    nextEntry,
                    module.kernel3D(),
                    module.workgroupPipeline(),
                    module.unmappedReasons(),
                    module.liftedMethodSymbols(),
                    module.liftedFieldSymbols());
            String fp = IrPatternDiagnostic.structuralFingerprint(rewritten);
            return new RuleApplication(
                    rewritten,
                    true,
                    Set.of(IDIOM_ALGEBRAIC_CLAMP_LERP),
                    Map.of(),
                    List.of(new IrPatternDiagnostic(
                            context.stageId(),
                            module.moduleName(),
                            fp,
                            IrPatternDiagnostic.Disposition.REWRITTEN,
                            "simplified algebraic identities / redundant masks in SSA IR")));
        }

        private static ConceptFunction rewriteFunction(
                ConceptFunction fn, boolean exactOnly, boolean[] changed) {
            List<ConceptStmt> nextBody = rewriteStmts(fn.body(), exactOnly, changed);
            return new ConceptFunction(
                    fn.ownerClass(),
                    fn.name(),
                    fn.descriptor(),
                    fn.parameters(),
                    fn.returnType(),
                    fn.localTypes(),
                    nextBody,
                    fn.pureCompute());
        }

        private static List<ConceptStmt> rewriteStmts(
                List<ConceptStmt> stmts, boolean exactOnly, boolean[] changed) {
            List<ConceptStmt> out = new ArrayList<>(stmts.size());
            for (ConceptStmt s : stmts) {
                if (s instanceof ConceptStmt.VarDecl vd) {
                    out.add(vd.initializer() == null
                            ? vd
                            : new ConceptStmt.VarDecl(vd.name(), vd.type(), rewriteExpr(vd.initializer(), exactOnly, changed)));
                } else if (s instanceof ConceptStmt.Assign a) {
                    out.add(new ConceptStmt.Assign(a.name(), a.type(), rewriteExpr(a.value(), exactOnly, changed)));
                } else if (s instanceof ConceptStmt.If iff) {
                    out.add(new ConceptStmt.If(
                            rewriteExpr(iff.condition(), exactOnly, changed),
                            rewriteStmts(iff.thenBody(), exactOnly, changed),
                            rewriteStmts(iff.elseBody(), exactOnly, changed)));
                } else if (s instanceof ConceptStmt.While wh) {
                    out.add(new ConceptStmt.While(
                            rewriteExpr(wh.condition(), exactOnly, changed),
                            rewriteStmts(wh.body(), exactOnly, changed)));
                } else if (s instanceof ConceptStmt.Return ret) {
                    out.add(ret.value() == null
                            ? ret
                            : new ConceptStmt.Return(rewriteExpr(ret.value(), exactOnly, changed)));
                } else if (s instanceof ConceptStmt.StoreBuffer3D st) {
                    out.add(new ConceptStmt.StoreBuffer3D(
                            st.bufferName(),
                            rewriteExpr(st.linearIndex(), exactOnly, changed),
                            rewriteExpr(st.value(), exactOnly, changed)));
                } else {
                    out.add(s);
                }
            }
            return out;
        }

        private static ConceptExpr rewriteExpr(ConceptExpr expr, boolean exactOnly, boolean[] changed) {
            if (expr instanceof ConceptExpr.UnaryOp u) {
                ConceptExpr op = rewriteExpr(u.operand(), exactOnly, changed);
                if (u.op() == BytecodeConceptIr.UnaryOperator.NEG
                        && op instanceof ConceptExpr.UnaryOp inner
                        && inner.op() == BytecodeConceptIr.UnaryOperator.NEG) {
                    changed[0] = true;
                    return inner.operand();
                }
                return new ConceptExpr.UnaryOp(u.op(), op, u.type());
            }
            if (expr instanceof ConceptExpr.Cast c) {
                ConceptExpr op = rewriteExpr(c.operand(), exactOnly, changed);
                if (op.type() == c.targetType()) {
                    changed[0] = true;
                    return op;
                }
                return new ConceptExpr.Cast(c.targetType(), op);
            }
            if (expr instanceof ConceptExpr.Ternary t) {
                ConceptExpr cond = rewriteExpr(t.condition(), exactOnly, changed);
                ConceptExpr th = rewriteExpr(t.thenExpr(), exactOnly, changed);
                ConceptExpr el = rewriteExpr(t.elseExpr(), exactOnly, changed);
                if (th.equals(el)) {
                    changed[0] = true;
                    return th;
                }
                if (cond instanceof ConceptExpr.BoolConst bc) {
                    changed[0] = true;
                    return bc.value() ? th : el;
                }
                return new ConceptExpr.Ternary(cond, th, el, t.type());
            }
            if (expr instanceof ConceptExpr.LutLoad l) {
                return new ConceptExpr.LutLoad(
                        l.lutSymbol(), rewriteExpr(l.index(), exactOnly, changed), l.elementType());
            }
            if (expr instanceof ConceptExpr.MathIntrinsic m) {
                List<ConceptExpr> args = new ArrayList<>(m.arguments().size());
                for (ConceptExpr a : m.arguments()) {
                    args.add(rewriteExpr(a, exactOnly, changed));
                }
                if ((m.kind() == MathIntrinsicKind.MIN || m.kind() == MathIntrinsicKind.MAX)
                        && args.size() == 2
                        && args.get(0).equals(args.get(1))) {
                    changed[0] = true;
                    return args.get(0);
                }
                return new ConceptExpr.MathIntrinsic(m.kind(), args, m.type());
            }
            if (expr instanceof ConceptExpr.Call c) {
                List<ConceptExpr> args = new ArrayList<>(c.arguments().size());
                for (ConceptExpr a : c.arguments()) {
                    args.add(rewriteExpr(a, exactOnly, changed));
                }
                return new ConceptExpr.Call(c.ownerClass(), c.methodName(), c.descriptor(), args, c.returnType());
            }
            if (expr instanceof ConceptExpr.BinaryOp b) {
                ConceptExpr l = rewriteExpr(b.left(), exactOnly, changed);
                ConceptExpr r = rewriteExpr(b.right(), exactOnly, changed);
                if (b.op() == BinaryOperator.BIT_AND
                        && r instanceof ConceptExpr.IntConst rc
                        && l instanceof ConceptExpr.BinaryOp lb
                        && lb.op() == BinaryOperator.BIT_AND
                        && lb.right() instanceof ConceptExpr.IntConst lrc
                        && (lrc.value() & rc.value()) == lrc.value()) {
                    changed[0] = true;
                    return l;
                }
                if (b.op() == BinaryOperator.MUL) {
                    if (isExactOne(r)) {
                        changed[0] = true;
                        return l;
                    }
                    if (isExactOne(l)) {
                        changed[0] = true;
                        return r;
                    }
                    if ((!exactOnly && (isExactZero(l) || isExactZero(r)))
                            || (isExactZero(l) && isGuaranteedFinite(r))
                            || (isExactZero(r) && isGuaranteedFinite(l))) {
                        changed[0] = true;
                        return zeroOf(b.type());
                    }
                }
                if (b.op() == BinaryOperator.ADD) {
                    if (isIntOrLongZero(r) || (!exactOnly && isExactZero(r))) {
                        changed[0] = true;
                        return l;
                    }
                    if (isIntOrLongZero(l) || (!exactOnly && isExactZero(l))) {
                        changed[0] = true;
                        return r;
                    }
                }
                if (b.op() == BinaryOperator.SUB) {
                    if (isIntOrLongZero(r) || (!exactOnly && isExactZero(r))) {
                        changed[0] = true;
                        return l;
                    }
                }
                if (b.op() == BinaryOperator.DIV && isExactOne(r)) {
                    changed[0] = true;
                    return l;
                }
                return new ConceptExpr.BinaryOp(b.op(), l, r, b.type());
            }
            return expr;
        }

        private static boolean isExactOne(ConceptExpr e) {
            if (e instanceof ConceptExpr.IntConst ic) {
                return ic.value() == 1;
            }
            if (e instanceof ConceptExpr.LongConst lc) {
                return lc.value() == 1L;
            }
            if (e instanceof ConceptExpr.FloatConst fc) {
                return Float.floatToRawIntBits(fc.value()) == Float.floatToRawIntBits(1.0f);
            }
            if (e instanceof ConceptExpr.DoubleConst dc) {
                return Double.doubleToRawLongBits(dc.value()) == Double.doubleToRawLongBits(1.0);
            }
            return false;
        }

        private static boolean isIntOrLongZero(ConceptExpr e) {
            if (e instanceof ConceptExpr.IntConst ic) {
                return ic.value() == 0;
            }
            if (e instanceof ConceptExpr.LongConst lc) {
                return lc.value() == 0L;
            }
            return false;
        }

        private static boolean isGuaranteedFinite(ConceptExpr e) {
            if (e instanceof ConceptExpr.IntConst || e instanceof ConceptExpr.LongConst) {
                return true;
            }
            if (e instanceof ConceptExpr.FloatConst fc) {
                return Float.isFinite(fc.value());
            }
            if (e instanceof ConceptExpr.DoubleConst dc) {
                return Double.isFinite(dc.value());
            }
            if (e instanceof ConceptExpr.Cast c) {
                ConceptType src = c.operand().type();
                return src == ConceptType.I32 || src == ConceptType.I64 || src == ConceptType.BOOL;
            }
            return e.type() == ConceptType.I32 || e.type() == ConceptType.I64 || e.type() == ConceptType.BOOL;
        }

        private static boolean isExactZero(ConceptExpr e) {
            if (isIntOrLongZero(e)) {
                return true;
            }
            if (e instanceof ConceptExpr.FloatConst fc) {
                return fc.value() == 0.0f;
            }
            if (e instanceof ConceptExpr.DoubleConst dc) {
                return dc.value() == 0.0;
            }
            return false;
        }

        private static ConceptExpr zeroOf(ConceptType type) {
            return switch (type) {
                case I64 -> new ConceptExpr.LongConst(0L);
                case F32 -> new ConceptExpr.FloatConst(0.0f);
                case F64 -> new ConceptExpr.DoubleConst(0.0);
                default -> new ConceptExpr.IntConst(0);
            };
        }
    }

    /**
     * Structural rule 2: Compacts value-bounded {@code I32} lookup tables ({@code [0..255] -> uchar},
     * {@code [-128..127] -> char}, {@code [-32768..32767] -> short}) to fit large octave graphs
     * inside GPU {@code __constant} memory.
     */
    private static final class CompactBoundedI32LutRule implements IdiomRewriteRule {
        @Override
        public String ruleId() {
            return IDIOM_COMPACT_BOUNDED_I32_LUT;
        }

        @Override
        public RuleApplication apply(ConceptModule module, RewriteContext context) {
            if (module.lookupTables().isEmpty()) {
                return RuleApplication.unchanged(module);
            }
            Map<String, CompactLutHint> hints = new LinkedHashMap<>();
            int savedTotal = 0;
            int compactedCount = 0;
            for (ConceptLut lut : module.lookupTables()) {
                CompactLutHint hint = analyzeLutCompaction(lut, context.exactOnly());
                hints.put(lut.symbolName(), hint);
                if (hint.savedBytes() > 0 && lut.elementType() == ConceptType.I32) {
                    savedTotal += hint.savedBytes();
                    compactedCount++;
                }
            }
            if (compactedCount == 0) {
                return new RuleApplication(module, false, Set.of(), hints, List.of());
            }
            String fp = IrPatternDiagnostic.structuralFingerprint(module);
            return new RuleApplication(
                    module,
                    true,
                    Set.of(IDIOM_COMPACT_BOUNDED_I32_LUT),
                    hints,
                    List.of(new IrPatternDiagnostic(
                            context.stageId(),
                            module.moduleName(),
                            fp,
                            IrPatternDiagnostic.Disposition.REWRITTEN,
                            "compacted " + compactedCount + " bounded I32 LUT(s), saving " + savedTotal + " B constant-LUT")));
        }
    }

    /**
     * Structural rule 3: Recognizes Perlin/Simplex fade + permutation-LUT noise evaluation idioms
     * purely from SSA structure (quintic fade constants {@code 6.0 / 15.0 / 10.0} or floor +
     * {@code & 255} LUT indexing).
     */
    private static final class PerlinOctaveKernelRecognizerRule implements IdiomRewriteRule {
        @Override
        public String ruleId() {
            return IDIOM_PERLIN_OCTAVE_KERNEL;
        }

        @Override
        public RuleApplication apply(ConceptModule module, RewriteContext context) {
            boolean has256Lut = false;
            for (ConceptLut lut : module.lookupTables()) {
                if (lut.elementType() == ConceptType.I32 && lut.values().size() >= 256) {
                    has256Lut = true;
                    break;
                }
            }
            boolean[] flags = new boolean[4]; // [0]=hasFloor, [1]=hasMask255, [2]=hasFadeConst6, [3]=hasFadeConst15Or10
            for (ConceptFunction h : module.helperFunctions()) {
                scanStmts(h.body(), flags);
            }
            if (module.entryFunction() != null) {
                scanStmts(module.entryFunction().body(), flags);
            }
            boolean recognized = (has256Lut && flags[1] && flags[0]) || (flags[2] && flags[3]);
            if (!recognized) {
                return RuleApplication.unchanged(module);
            }
            String fp = IrPatternDiagnostic.structuralFingerprint(module);
            return new RuleApplication(
                    module,
                    true,
                    Set.of(IDIOM_PERLIN_OCTAVE_KERNEL),
                    Map.of(),
                    List.of(new IrPatternDiagnostic(
                            context.stageId(),
                            module.moduleName(),
                            fp,
                            IrPatternDiagnostic.Disposition.REWRITTEN,
                            "recognized structural Perlin/octave noise kernel (exactOnly=" + context.exactOnly() + ")")));
        }

        private static void scanStmts(List<ConceptStmt> stmts, boolean[] flags) {
            for (ConceptStmt s : stmts) {
                if (s instanceof ConceptStmt.Assign a) {
                    scanExpr(a.value(), flags);
                } else if (s instanceof ConceptStmt.If iff) {
                    scanExpr(iff.condition(), flags);
                    scanStmts(iff.thenBody(), flags);
                    scanStmts(iff.elseBody(), flags);
                } else if (s instanceof ConceptStmt.While wh) {
                    scanExpr(wh.condition(), flags);
                    scanStmts(wh.body(), flags);
                } else if (s instanceof ConceptStmt.Return ret && ret.value() != null) {
                    scanExpr(ret.value(), flags);
                } else if (s instanceof ConceptStmt.StoreBuffer3D st) {
                    scanExpr(st.linearIndex(), flags);
                    scanExpr(st.value(), flags);
                }
            }
        }

        private static void scanExpr(ConceptExpr e, boolean[] flags) {
            if (e instanceof ConceptExpr.DoubleConst dc) {
                double v = dc.value();
                if (v == 6.0) {
                    flags[2] = true;
                } else if (v == 15.0 || v == -15.0 || v == 10.0) {
                    flags[3] = true;
                }
            } else if (e instanceof ConceptExpr.FloatConst fc) {
                float v = fc.value();
                if (v == 6.0f) {
                    flags[2] = true;
                } else if (v == 15.0f || v == -15.0f || v == 10.0f) {
                    flags[3] = true;
                }
            } else if (e instanceof ConceptExpr.MathIntrinsic m) {
                if (m.kind() == MathIntrinsicKind.FLOOR) {
                    flags[0] = true;
                }
                for (ConceptExpr a : m.arguments()) {
                    scanExpr(a, flags);
                }
            } else if (e instanceof ConceptExpr.BinaryOp b) {
                if (b.op() == BinaryOperator.BIT_AND
                        && ((b.right() instanceof ConceptExpr.IntConst ic && ic.value() == 255)
                                || (b.left() instanceof ConceptExpr.IntConst ic2 && ic2.value() == 255))) {
                    flags[1] = true;
                }
                scanExpr(b.left(), flags);
                scanExpr(b.right(), flags);
            } else if (e instanceof ConceptExpr.UnaryOp u) {
                scanExpr(u.operand(), flags);
            } else if (e instanceof ConceptExpr.Cast c) {
                scanExpr(c.operand(), flags);
            } else if (e instanceof ConceptExpr.Ternary t) {
                scanExpr(t.condition(), flags);
                scanExpr(t.thenExpr(), flags);
                scanExpr(t.elseExpr(), flags);
            } else if (e instanceof ConceptExpr.LutLoad l) {
                scanExpr(l.index(), flags);
            } else if (e instanceof ConceptExpr.Call c) {
                for (ConceptExpr a : c.arguments()) {
                    scanExpr(a, flags);
                }
            }
        }
    }

    /**
     * Structural rule 4: Detects 3-coordinate entry functions whose body (transitively across
     * helper calls) does not depend on the second (Y) coordinate parameter, identifying 2D
     * column-invariant fields for workgroup/column reuse.
     */
    private static final class ColumnYInvariantRecognizerRule implements IdiomRewriteRule {
        @Override
        public String ruleId() {
            return IDIOM_COLUMN_Y_INVARIANT;
        }

        @Override
        public RuleApplication apply(ConceptModule module, RewriteContext context) {
            ConceptFunction entry = module.entryFunction();
            if (entry == null || entry.parameters().size() < 3) {
                return RuleApplication.unchanged(module);
            }
            Map<String, ConceptFunction> fnByKey = new LinkedHashMap<>();
            Map<String, ConceptFunction> fnByName = new LinkedHashMap<>();
            for (ConceptFunction h : module.helperFunctions()) {
                fnByKey.put(h.key(), h);
                fnByName.put(h.name(), h);
            }
            // Fixed-point: identify which helper parameter indices are unused by each helper.
            Map<String, Set<Integer>> unusedParamIdx = new LinkedHashMap<>();
            for (ConceptFunction h : module.helperFunctions()) {
                unusedParamIdx.put(h.name(), new LinkedHashSet<>());
            }
            boolean changed = true;
            for (int iter = 0; iter < 16 && changed; iter++) {
                changed = false;
                for (ConceptFunction h : module.helperFunctions()) {
                    Set<Integer> unused = unusedParamIdx.get(h.name());
                    for (int pi = 0; pi < h.parameters().size(); pi++) {
                        if (unused.contains(pi)) {
                            continue;
                        }
                        if (!referencesVarTransitivelyInStmts(
                                h.body(), h.parameters().get(pi).name(), fnByKey, fnByName, unusedParamIdx)) {
                            unused.add(pi);
                            changed = true;
                        }
                    }
                }
            }
            String yParam = entry.parameters().get(1).name();
            if (referencesVarTransitivelyInStmts(entry.body(), yParam, fnByKey, fnByName, unusedParamIdx)) {
                return RuleApplication.unchanged(module);
            }
            String fp = IrPatternDiagnostic.structuralFingerprint(module);
            return new RuleApplication(
                    module,
                    true,
                    Set.of(IDIOM_COLUMN_Y_INVARIANT),
                    Map.of(),
                    List.of(new IrPatternDiagnostic(
                            context.stageId(),
                            module.moduleName(),
                            fp,
                            IrPatternDiagnostic.Disposition.REWRITTEN,
                            "entry function is transitively Y-invariant (2D column reusable)")));
        }

        private static boolean referencesVarTransitivelyInStmts(
                List<ConceptStmt> stmts,
                String varName,
                Map<String, ConceptFunction> fnByKey,
                Map<String, ConceptFunction> fnByName,
                Map<String, Set<Integer>> unusedParamIdx) {
            for (ConceptStmt s : stmts) {
                if (s instanceof ConceptStmt.VarDecl vd && vd.initializer() != null) {
                    if (referencesVarTransitivelyInExpr(vd.initializer(), varName, fnByKey, fnByName, unusedParamIdx)) {
                        return true;
                    }
                } else if (s instanceof ConceptStmt.Assign a) {
                    if (referencesVarTransitivelyInExpr(a.value(), varName, fnByKey, fnByName, unusedParamIdx)) {
                        return true;
                    }
                } else if (s instanceof ConceptStmt.If iff) {
                    if (referencesVarTransitivelyInExpr(iff.condition(), varName, fnByKey, fnByName, unusedParamIdx)
                            || referencesVarTransitivelyInStmts(iff.thenBody(), varName, fnByKey, fnByName, unusedParamIdx)
                            || referencesVarTransitivelyInStmts(iff.elseBody(), varName, fnByKey, fnByName, unusedParamIdx)) {
                        return true;
                    }
                } else if (s instanceof ConceptStmt.While wh) {
                    if (referencesVarTransitivelyInExpr(wh.condition(), varName, fnByKey, fnByName, unusedParamIdx)
                            || referencesVarTransitivelyInStmts(wh.body(), varName, fnByKey, fnByName, unusedParamIdx)) {
                        return true;
                    }
                } else if (s instanceof ConceptStmt.Return ret) {
                    if (ret.value() != null
                            && referencesVarTransitivelyInExpr(ret.value(), varName, fnByKey, fnByName, unusedParamIdx)) {
                        return true;
                    }
                } else if (s instanceof ConceptStmt.StoreBuffer3D st) {
                    if (referencesVarTransitivelyInExpr(st.linearIndex(), varName, fnByKey, fnByName, unusedParamIdx)
                            || referencesVarTransitivelyInExpr(st.value(), varName, fnByKey, fnByName, unusedParamIdx)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static boolean referencesVarTransitivelyInExpr(
                ConceptExpr e,
                String varName,
                Map<String, ConceptFunction> fnByKey,
                Map<String, ConceptFunction> fnByName,
                Map<String, Set<Integer>> unusedParamIdx) {
            if (e instanceof ConceptExpr.Var v) {
                return varName.equals(v.name());
            }
            if (e instanceof ConceptExpr.UnaryOp u) {
                return referencesVarTransitivelyInExpr(u.operand(), varName, fnByKey, fnByName, unusedParamIdx);
            }
            if (e instanceof ConceptExpr.Cast c) {
                return referencesVarTransitivelyInExpr(c.operand(), varName, fnByKey, fnByName, unusedParamIdx);
            }
            if (e instanceof ConceptExpr.BinaryOp b) {
                return referencesVarTransitivelyInExpr(b.left(), varName, fnByKey, fnByName, unusedParamIdx)
                        || referencesVarTransitivelyInExpr(b.right(), varName, fnByKey, fnByName, unusedParamIdx);
            }
            if (e instanceof ConceptExpr.Ternary t) {
                return referencesVarTransitivelyInExpr(t.condition(), varName, fnByKey, fnByName, unusedParamIdx)
                        || referencesVarTransitivelyInExpr(t.thenExpr(), varName, fnByKey, fnByName, unusedParamIdx)
                        || referencesVarTransitivelyInExpr(t.elseExpr(), varName, fnByKey, fnByName, unusedParamIdx);
            }
            if (e instanceof ConceptExpr.LutLoad l) {
                return referencesVarTransitivelyInExpr(l.index(), varName, fnByKey, fnByName, unusedParamIdx);
            }
            if (e instanceof ConceptExpr.MathIntrinsic m) {
                for (ConceptExpr a : m.arguments()) {
                    if (referencesVarTransitivelyInExpr(a, varName, fnByKey, fnByName, unusedParamIdx)) {
                        return true;
                    }
                }
                return false;
            }
            if (e instanceof ConceptExpr.Call c) {
                String key = c.ownerClass() + "#" + c.methodName() + c.descriptor();
                ConceptFunction target = fnByKey.get(key);
                if (target == null) {
                    target = fnByName.get(c.methodName());
                }
                Set<Integer> calleeUnused = target != null ? unusedParamIdx.get(target.name()) : null;
                for (int i = 0; i < c.arguments().size(); i++) {
                    if (calleeUnused != null && calleeUnused.contains(i)) {
                        continue;
                    }
                    if (referencesVarTransitivelyInExpr(c.arguments().get(i), varName, fnByKey, fnByName, unusedParamIdx)) {
                        return true;
                    }
                }
                return false;
            }
            return false;
        }
    }

    /**
     * Structural rule 5: Deduplicates helper functions with identical SSA signatures, local maps,
     * and statement bodies in bottom-up fixed-point order, rewriting all call sites to a single
     * canonical helper.
     */
    private static final class DeduplicateIdenticalHelpersRule implements IdiomRewriteRule {
        @Override
        public String ruleId() {
            return IDIOM_DEDUP_IDENTICAL_HELPERS;
        }

        @Override
        public RuleApplication apply(ConceptModule module, RewriteContext context) {
            if (module.helperFunctions().size() < 2) {
                return RuleApplication.unchanged(module);
            }
            List<ConceptFunction> helpers = new ArrayList<>(module.helperFunctions());
            ConceptFunction entry = module.entryFunction();
            int totalEliminated = 0;

            for (int pass = 0; pass < 8; pass++) {
                Map<String, ConceptFunction> bodyKeyToCanon = new LinkedHashMap<>();
                Map<String, ConceptFunction> remapByKey = new LinkedHashMap<>();
                Map<String, ConceptFunction> remapByName = new LinkedHashMap<>();
                List<ConceptFunction> kept = new ArrayList<>(helpers.size());

                for (ConceptFunction h : helpers) {
                    String key = helperExactBodyKey(h);
                    ConceptFunction canon = bodyKeyToCanon.get(key);
                    if (canon == null) {
                        bodyKeyToCanon.put(key, h);
                        kept.add(h);
                    } else {
                        remapByKey.put(h.key(), canon);
                        remapByName.put(h.name(), canon);
                    }
                }
                if (remapByKey.isEmpty()) {
                    break;
                }
                totalEliminated += remapByKey.size();
                List<ConceptFunction> rewrittenHelpers = new ArrayList<>(kept.size());
                for (ConceptFunction h : kept) {
                    rewrittenHelpers.add(rewriteCallsInFunction(h, remapByKey, remapByName, Map.of()));
                }
                helpers = rewrittenHelpers;
                if (entry != null) {
                    entry = rewriteCallsInFunction(entry, remapByKey, remapByName, Map.of());
                }
            }
            if (totalEliminated == 0) {
                return RuleApplication.unchanged(module);
            }
            ConceptModule rewritten = new ConceptModule(
                    module.moduleName(),
                    module.lookupTables(),
                    helpers,
                    entry,
                    module.kernel3D(),
                    module.workgroupPipeline(),
                    module.unmappedReasons(),
                    module.liftedMethodSymbols(),
                    module.liftedFieldSymbols());
            String fp = IrPatternDiagnostic.structuralFingerprint(rewritten);
            return new RuleApplication(
                    rewritten,
                    true,
                    Set.of(IDIOM_DEDUP_IDENTICAL_HELPERS),
                    Map.of(),
                    List.of(new IrPatternDiagnostic(
                            context.stageId(),
                            module.moduleName(),
                            fp,
                            IrPatternDiagnostic.Disposition.REWRITTEN,
                            "deduplicated " + totalEliminated + " identical helper function(s)")));
        }

        private static String helperExactBodyKey(ConceptFunction fn) {
            return fn.parameters().toString()
                    + "|" + fn.returnType().name()
                    + "|" + fn.localTypes().toString()
                    + "|" + fn.pureCompute()
                    + "|" + fn.body().toString();
        }
    }

    /**
     * Structural rule 6: Outlines isomorphic helper functions that share identical SSA bodies
     * differing only in a single referenced {@code I32} lookup table (e.g. per-octave noise
     * permutation tables) into one canonical helper parameterized by {@code int __lut_base} over
     * a merged lookup table.
     */
    private static final class OutlineIsomorphicSingleLutHelpersRule implements IdiomRewriteRule {
        @Override
        public String ruleId() {
            return IDIOM_OUTLINE_ISOMORPHIC_LUT_HELPERS;
        }

        @Override
        public RuleApplication apply(ConceptModule module, RewriteContext context) {
            if (module.helperFunctions().size() < 2 || module.lookupTables().size() < 2) {
                return RuleApplication.unchanged(module);
            }
            Map<String, ConceptLut> lutBySym = new LinkedHashMap<>();
            for (ConceptLut lut : module.lookupTables()) {
                lutBySym.putIfAbsent(lut.symbolName(), lut);
            }

            Map<String, List<ConceptFunction>> groups = new LinkedHashMap<>();
            Map<ConceptFunction, String> fnSingleLut = new java.util.IdentityHashMap<>();

            for (ConceptFunction h : module.helperFunctions()) {
                Set<String> refs = new LinkedHashSet<>();
                collectLutRefsInStmts(h.body(), refs);
                if (refs.size() != 1) {
                    continue;
                }
                String lutSym = refs.iterator().next();
                ConceptLut lut = lutBySym.get(lutSym);
                if (lut == null || lut.elementType() != ConceptType.I32 || lut.values().size() < 16) {
                    continue;
                }
                // Guard against name collision with existing param/local
                if (h.localTypes().containsKey("__lut_base")) {
                    continue;
                }
                CompactLutHint hint = analyzeLutCompaction(lut, context.exactOnly());
                List<ConceptStmt> normalizedBody = replaceLutSymbolInStmts(h.body(), lutSym, "__PARAM_LUT__", false);
                String templateKey = h.parameters().toString()
                        + "|" + h.returnType().name()
                        + "|" + h.localTypes().toString()
                        + "|" + h.pureCompute()
                        + "|" + hint.storageKind().name()
                        + "|" + lut.values().size()
                        + "|" + normalizedBody.toString();
                groups.computeIfAbsent(templateKey, k -> new ArrayList<>()).add(h);
                fnSingleLut.put(h, lutSym);
            }

            Map<String, ConceptFunction> remapByKey = new LinkedHashMap<>();
            Map<String, ConceptFunction> remapByName = new LinkedHashMap<>();
            Map<String, Integer> extraIntArgByOldKey = new LinkedHashMap<>();
            Set<ConceptFunction> replacedFns = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            List<ConceptFunction> newCanonHelpers = new ArrayList<>();
            List<ConceptLut> addedLuts = new ArrayList<>();
            int groupIdx = 0;
            int outlinedCount = 0;

            for (List<ConceptFunction> members : groups.values()) {
                if (members.size() < 2) {
                    continue;
                }
                ConceptFunction template = members.get(0);
                String templateLutSym = fnSingleLut.get(template);
                ConceptLut firstLut = lutBySym.get(templateLutSym);
                int stride = firstLut.values().size();

                String mergedLutSym = "lut_outlined_" + groupIdx;
                List<Number> mergedVals = new ArrayList<>(members.size() * stride);
                Map<String, Integer> lutSymToOffset = new LinkedHashMap<>();
                Map<String, Integer> payloadHashToOffset = new LinkedHashMap<>();

                for (ConceptFunction m : members) {
                    String mLutSym = fnSingleLut.get(m);
                    Integer existingOff = lutSymToOffset.get(mLutSym);
                    if (existingOff == null) {
                        ConceptLut mLut = lutBySym.get(mLutSym);
                        String pHash = lutPayloadHash(mLut);
                        Integer dedupOff = payloadHashToOffset.get(pHash);
                        if (dedupOff != null) {
                            existingOff = dedupOff;
                        } else {
                            existingOff = mergedVals.size();
                            mergedVals.addAll(mLut.values());
                            payloadHashToOffset.put(pHash, existingOff);
                        }
                        lutSymToOffset.put(mLutSym, existingOff);
                    }
                }

                addedLuts.add(new ConceptLut(mergedLutSym, template.ownerClass(), mergedLutSym, ConceptType.I32, mergedVals));

                List<BytecodeConceptIr.ConceptParam> canonParams = new ArrayList<>(template.parameters().size() + 1);
                canonParams.addAll(template.parameters());
                int nextSlot = 0;
                for (BytecodeConceptIr.ConceptParam p : template.parameters()) {
                    nextSlot = Math.max(nextSlot, p.jvmSlot() + (p.type() == ConceptType.F64 || p.type() == ConceptType.I64 ? 2 : 1));
                }
                canonParams.add(new BytecodeConceptIr.ConceptParam("__lut_base", ConceptType.I32, nextSlot));

                List<ConceptStmt> canonBody = replaceLutSymbolInStmts(template.body(), templateLutSym, mergedLutSym, true);
                String canonName = template.name() + "_outlined_" + groupIdx;
                String canonDesc = appendIntParamToDescriptor(template.descriptor());
                ConceptFunction canonFn = new ConceptFunction(
                        template.ownerClass(),
                        canonName,
                        canonDesc,
                        canonParams,
                        template.returnType(),
                        template.localTypes(),
                        canonBody,
                        template.pureCompute());
                newCanonHelpers.add(canonFn);

                for (ConceptFunction m : members) {
                    int off = lutSymToOffset.get(fnSingleLut.get(m));
                    remapByKey.put(m.key(), canonFn);
                    remapByName.put(m.name(), canonFn);
                    extraIntArgByOldKey.put(m.key(), off);
                    extraIntArgByOldKey.put(m.name(), off);
                    replacedFns.add(m);
                }
                outlinedCount += members.size() - 1;
                groupIdx++;
            }

            if (outlinedCount == 0) {
                return RuleApplication.unchanged(module);
            }

            List<ConceptFunction> nextHelpers = new ArrayList<>(module.helperFunctions().size() - outlinedCount);
            for (ConceptFunction h : module.helperFunctions()) {
                if (!replacedFns.contains(h)) {
                    nextHelpers.add(rewriteCallsInFunction(h, remapByKey, remapByName, extraIntArgByOldKey));
                }
            }
            for (ConceptFunction canon : newCanonHelpers) {
                nextHelpers.add(rewriteCallsInFunction(canon, remapByKey, remapByName, extraIntArgByOldKey));
            }
            ConceptFunction nextEntry = module.entryFunction() != null
                    ? rewriteCallsInFunction(module.entryFunction(), remapByKey, remapByName, extraIntArgByOldKey)
                    : null;

            List<ConceptLut> allLuts = new ArrayList<>(module.lookupTables().size() + addedLuts.size());
            allLuts.addAll(module.lookupTables());
            allLuts.addAll(addedLuts);
            // Prune LUTs no longer referenced after outlining
            Set<String> liveLuts = new LinkedHashSet<>();
            for (ConceptFunction h : nextHelpers) {
                collectLutRefsInStmts(h.body(), liveLuts);
            }
            if (nextEntry != null) {
                collectLutRefsInStmts(nextEntry.body(), liveLuts);
            }
            List<ConceptLut> prunedLuts = new ArrayList<>(liveLuts.size());
            for (ConceptLut l : allLuts) {
                if (liveLuts.contains(l.symbolName())) {
                    prunedLuts.add(l);
                }
            }

            ConceptModule rewritten = new ConceptModule(
                    module.moduleName(),
                    prunedLuts,
                    nextHelpers,
                    nextEntry,
                    module.kernel3D(),
                    module.workgroupPipeline(),
                    module.unmappedReasons(),
                    module.liftedMethodSymbols(),
                    module.liftedFieldSymbols());
            String fp = IrPatternDiagnostic.structuralFingerprint(rewritten);
            return new RuleApplication(
                    rewritten,
                    true,
                    Set.of(IDIOM_OUTLINE_ISOMORPHIC_LUT_HELPERS),
                    Map.of(),
                    List.of(new IrPatternDiagnostic(
                            context.stageId(),
                            module.moduleName(),
                            fp,
                            IrPatternDiagnostic.Disposition.REWRITTEN,
                            "outlined " + (outlinedCount + groupIdx) + " isomorphic LUT helpers into "
                                    + groupIdx + " canonical helper(s)")));
        }

        private static String lutPayloadHash(ConceptLut lut) {
            StringBuilder sb = new StringBuilder(lut.values().size() * 4);
            for (Number n : lut.values()) {
                sb.append(n.intValue()).append(',');
            }
            return IrPatternDiagnostic.fingerprintText(sb.toString());
        }

        private static String appendIntParamToDescriptor(String desc) {
            int rp = desc != null ? desc.indexOf(')') : -1;
            if (rp < 0) {
                return desc == null ? "(I)D" : desc;
            }
            return desc.substring(0, rp) + "I" + desc.substring(rp);
        }

        private static void collectLutRefsInStmts(List<ConceptStmt> stmts, Set<String> out) {
            for (ConceptStmt s : stmts) {
                if (s instanceof ConceptStmt.VarDecl vd && vd.initializer() != null) {
                    collectLutRefsInExpr(vd.initializer(), out);
                } else if (s instanceof ConceptStmt.Assign a) {
                    collectLutRefsInExpr(a.value(), out);
                } else if (s instanceof ConceptStmt.If iff) {
                    collectLutRefsInExpr(iff.condition(), out);
                    collectLutRefsInStmts(iff.thenBody(), out);
                    collectLutRefsInStmts(iff.elseBody(), out);
                } else if (s instanceof ConceptStmt.While wh) {
                    collectLutRefsInExpr(wh.condition(), out);
                    collectLutRefsInStmts(wh.body(), out);
                } else if (s instanceof ConceptStmt.Return ret && ret.value() != null) {
                    collectLutRefsInExpr(ret.value(), out);
                } else if (s instanceof ConceptStmt.StoreBuffer3D st) {
                    collectLutRefsInExpr(st.linearIndex(), out);
                    collectLutRefsInExpr(st.value(), out);
                }
            }
        }

        private static void collectLutRefsInExpr(ConceptExpr e, Set<String> out) {
            if (e instanceof ConceptExpr.LutLoad l) {
                out.add(l.lutSymbol());
                collectLutRefsInExpr(l.index(), out);
            } else if (e instanceof ConceptExpr.UnaryOp u) {
                collectLutRefsInExpr(u.operand(), out);
            } else if (e instanceof ConceptExpr.Cast c) {
                collectLutRefsInExpr(c.operand(), out);
            } else if (e instanceof ConceptExpr.BinaryOp b) {
                collectLutRefsInExpr(b.left(), out);
                collectLutRefsInExpr(b.right(), out);
            } else if (e instanceof ConceptExpr.Ternary t) {
                collectLutRefsInExpr(t.condition(), out);
                collectLutRefsInExpr(t.thenExpr(), out);
                collectLutRefsInExpr(t.elseExpr(), out);
            } else if (e instanceof ConceptExpr.MathIntrinsic m) {
                for (ConceptExpr a : m.arguments()) collectLutRefsInExpr(a, out);
            } else if (e instanceof ConceptExpr.Call c) {
                for (ConceptExpr a : c.arguments()) collectLutRefsInExpr(a, out);
            }
        }

        private static List<ConceptStmt> replaceLutSymbolInStmts(
                List<ConceptStmt> stmts, String oldSym, String newSym, boolean addBaseOffset) {
            List<ConceptStmt> out = new ArrayList<>(stmts.size());
            for (ConceptStmt s : stmts) {
                if (s instanceof ConceptStmt.VarDecl vd) {
                    out.add(vd.initializer() == null
                            ? vd
                            : new ConceptStmt.VarDecl(
                                    vd.name(), vd.type(), replaceLutSymbolInExpr(vd.initializer(), oldSym, newSym, addBaseOffset)));
                } else if (s instanceof ConceptStmt.Assign a) {
                    out.add(new ConceptStmt.Assign(
                            a.name(), a.type(), replaceLutSymbolInExpr(a.value(), oldSym, newSym, addBaseOffset)));
                } else if (s instanceof ConceptStmt.If iff) {
                    out.add(new ConceptStmt.If(
                            replaceLutSymbolInExpr(iff.condition(), oldSym, newSym, addBaseOffset),
                            replaceLutSymbolInStmts(iff.thenBody(), oldSym, newSym, addBaseOffset),
                            replaceLutSymbolInStmts(iff.elseBody(), oldSym, newSym, addBaseOffset)));
                } else if (s instanceof ConceptStmt.While wh) {
                    out.add(new ConceptStmt.While(
                            replaceLutSymbolInExpr(wh.condition(), oldSym, newSym, addBaseOffset),
                            replaceLutSymbolInStmts(wh.body(), oldSym, newSym, addBaseOffset)));
                } else if (s instanceof ConceptStmt.Return ret) {
                    out.add(ret.value() == null
                            ? ret
                            : new ConceptStmt.Return(replaceLutSymbolInExpr(ret.value(), oldSym, newSym, addBaseOffset)));
                } else if (s instanceof ConceptStmt.StoreBuffer3D st) {
                    out.add(new ConceptStmt.StoreBuffer3D(
                            st.bufferName(),
                            replaceLutSymbolInExpr(st.linearIndex(), oldSym, newSym, addBaseOffset),
                            replaceLutSymbolInExpr(st.value(), oldSym, newSym, addBaseOffset)));
                } else {
                    out.add(s);
                }
            }
            return out;
        }

        private static ConceptExpr replaceLutSymbolInExpr(
                ConceptExpr e, String oldSym, String newSym, boolean addBaseOffset) {
            if (e instanceof ConceptExpr.LutLoad l) {
                ConceptExpr idx = replaceLutSymbolInExpr(l.index(), oldSym, newSym, addBaseOffset);
                if (oldSym.equals(l.lutSymbol())) {
                    ConceptExpr nextIdx = addBaseOffset
                            ? new ConceptExpr.BinaryOp(
                                    BinaryOperator.ADD,
                                    new ConceptExpr.Var("__lut_base", ConceptType.I32),
                                    idx,
                                    ConceptType.I32)
                            : idx;
                    return new ConceptExpr.LutLoad(newSym, nextIdx, l.elementType());
                }
                return new ConceptExpr.LutLoad(l.lutSymbol(), idx, l.elementType());
            }
            if (e instanceof ConceptExpr.UnaryOp u) {
                return new ConceptExpr.UnaryOp(u.op(), replaceLutSymbolInExpr(u.operand(), oldSym, newSym, addBaseOffset), u.type());
            }
            if (e instanceof ConceptExpr.Cast c) {
                return new ConceptExpr.Cast(c.targetType(), replaceLutSymbolInExpr(c.operand(), oldSym, newSym, addBaseOffset));
            }
            if (e instanceof ConceptExpr.BinaryOp b) {
                return new ConceptExpr.BinaryOp(
                        b.op(),
                        replaceLutSymbolInExpr(b.left(), oldSym, newSym, addBaseOffset),
                        replaceLutSymbolInExpr(b.right(), oldSym, newSym, addBaseOffset),
                        b.type());
            }
            if (e instanceof ConceptExpr.Ternary t) {
                return new ConceptExpr.Ternary(
                        replaceLutSymbolInExpr(t.condition(), oldSym, newSym, addBaseOffset),
                        replaceLutSymbolInExpr(t.thenExpr(), oldSym, newSym, addBaseOffset),
                        replaceLutSymbolInExpr(t.elseExpr(), oldSym, newSym, addBaseOffset),
                        t.type());
            }
            if (e instanceof ConceptExpr.MathIntrinsic m) {
                List<ConceptExpr> args = new ArrayList<>(m.arguments().size());
                for (ConceptExpr a : m.arguments()) {
                    args.add(replaceLutSymbolInExpr(a, oldSym, newSym, addBaseOffset));
                }
                return new ConceptExpr.MathIntrinsic(m.kind(), args, m.type());
            }
            if (e instanceof ConceptExpr.Call c) {
                List<ConceptExpr> args = new ArrayList<>(c.arguments().size());
                for (ConceptExpr a : c.arguments()) {
                    args.add(replaceLutSymbolInExpr(a, oldSym, newSym, addBaseOffset));
                }
                return new ConceptExpr.Call(c.ownerClass(), c.methodName(), c.descriptor(), args, c.returnType());
            }
            return e;
        }
    }

    private static ConceptFunction rewriteCallsInFunction(
            ConceptFunction fn,
            Map<String, ConceptFunction> remapByKey,
            Map<String, ConceptFunction> remapByName,
            Map<String, Integer> extraIntArgByOldKey) {
        return new ConceptFunction(
                fn.ownerClass(),
                fn.name(),
                fn.descriptor(),
                fn.parameters(),
                fn.returnType(),
                fn.localTypes(),
                rewriteCallsInStmts(fn.body(), remapByKey, remapByName, extraIntArgByOldKey),
                fn.pureCompute());
    }

    private static List<ConceptStmt> rewriteCallsInStmts(
            List<ConceptStmt> stmts,
            Map<String, ConceptFunction> remapByKey,
            Map<String, ConceptFunction> remapByName,
            Map<String, Integer> extraIntArgByOldKey) {
        List<ConceptStmt> out = new ArrayList<>(stmts.size());
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.VarDecl vd) {
                out.add(vd.initializer() == null
                        ? vd
                        : new ConceptStmt.VarDecl(
                                vd.name(),
                                vd.type(),
                                rewriteCallsInExpr(vd.initializer(), remapByKey, remapByName, extraIntArgByOldKey)));
            } else if (s instanceof ConceptStmt.Assign a) {
                out.add(new ConceptStmt.Assign(
                        a.name(),
                        a.type(),
                        rewriteCallsInExpr(a.value(), remapByKey, remapByName, extraIntArgByOldKey)));
            } else if (s instanceof ConceptStmt.If iff) {
                out.add(new ConceptStmt.If(
                        rewriteCallsInExpr(iff.condition(), remapByKey, remapByName, extraIntArgByOldKey),
                        rewriteCallsInStmts(iff.thenBody(), remapByKey, remapByName, extraIntArgByOldKey),
                        rewriteCallsInStmts(iff.elseBody(), remapByKey, remapByName, extraIntArgByOldKey)));
            } else if (s instanceof ConceptStmt.While wh) {
                out.add(new ConceptStmt.While(
                        rewriteCallsInExpr(wh.condition(), remapByKey, remapByName, extraIntArgByOldKey),
                        rewriteCallsInStmts(wh.body(), remapByKey, remapByName, extraIntArgByOldKey)));
            } else if (s instanceof ConceptStmt.Return ret) {
                out.add(ret.value() == null
                        ? ret
                        : new ConceptStmt.Return(
                                rewriteCallsInExpr(ret.value(), remapByKey, remapByName, extraIntArgByOldKey)));
            } else if (s instanceof ConceptStmt.StoreBuffer3D st) {
                out.add(new ConceptStmt.StoreBuffer3D(
                        st.bufferName(),
                        rewriteCallsInExpr(st.linearIndex(), remapByKey, remapByName, extraIntArgByOldKey),
                        rewriteCallsInExpr(st.value(), remapByKey, remapByName, extraIntArgByOldKey)));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private static ConceptExpr rewriteCallsInExpr(
            ConceptExpr e,
            Map<String, ConceptFunction> remapByKey,
            Map<String, ConceptFunction> remapByName,
            Map<String, Integer> extraIntArgByOldKey) {
        if (e instanceof ConceptExpr.Call c) {
            List<ConceptExpr> args = new ArrayList<>(c.arguments().size() + 1);
            for (ConceptExpr a : c.arguments()) {
                args.add(rewriteCallsInExpr(a, remapByKey, remapByName, extraIntArgByOldKey));
            }
            String fullKey = c.ownerClass() + "#" + c.methodName() + c.descriptor();
            ConceptFunction target = remapByKey.get(fullKey);
            Integer extraArg = extraIntArgByOldKey.get(fullKey);
            if (target == null) {
                target = remapByName.get(c.methodName());
                if (extraArg == null) {
                    extraArg = extraIntArgByOldKey.get(c.methodName());
                }
            }
            if (target != null) {
                if (extraArg != null) {
                    args.add(new ConceptExpr.IntConst(extraArg));
                }
                return new ConceptExpr.Call(
                        target.ownerClass(), target.name(), target.descriptor(), args, target.returnType());
            }
            return new ConceptExpr.Call(c.ownerClass(), c.methodName(), c.descriptor(), args, c.returnType());
        }
        if (e instanceof ConceptExpr.UnaryOp u) {
            return new ConceptExpr.UnaryOp(
                    u.op(), rewriteCallsInExpr(u.operand(), remapByKey, remapByName, extraIntArgByOldKey), u.type());
        }
        if (e instanceof ConceptExpr.Cast c) {
            return new ConceptExpr.Cast(
                    c.targetType(), rewriteCallsInExpr(c.operand(), remapByKey, remapByName, extraIntArgByOldKey));
        }
        if (e instanceof ConceptExpr.BinaryOp b) {
            return new ConceptExpr.BinaryOp(
                    b.op(),
                    rewriteCallsInExpr(b.left(), remapByKey, remapByName, extraIntArgByOldKey),
                    rewriteCallsInExpr(b.right(), remapByKey, remapByName, extraIntArgByOldKey),
                    b.type());
        }
        if (e instanceof ConceptExpr.Ternary t) {
            return new ConceptExpr.Ternary(
                    rewriteCallsInExpr(t.condition(), remapByKey, remapByName, extraIntArgByOldKey),
                    rewriteCallsInExpr(t.thenExpr(), remapByKey, remapByName, extraIntArgByOldKey),
                    rewriteCallsInExpr(t.elseExpr(), remapByKey, remapByName, extraIntArgByOldKey),
                    t.type());
        }
        if (e instanceof ConceptExpr.LutLoad l) {
            return new ConceptExpr.LutLoad(
                    l.lutSymbol(),
                    rewriteCallsInExpr(l.index(), remapByKey, remapByName, extraIntArgByOldKey),
                    l.elementType());
        }
        if (e instanceof ConceptExpr.MathIntrinsic m) {
            List<ConceptExpr> args = new ArrayList<>(m.arguments().size());
            for (ConceptExpr a : m.arguments()) {
                args.add(rewriteCallsInExpr(a, remapByKey, remapByName, extraIntArgByOldKey));
            }
            return new ConceptExpr.MathIntrinsic(m.kind(), args, m.type());
        }
        return e;
    }

    private static int countModuleNodes(ConceptModule module) {
        int count = 0;
        for (ConceptFunction h : module.helperFunctions()) {
            count += countStmts(h.body());
        }
        if (module.entryFunction() != null) {
            count += countStmts(module.entryFunction().body());
        }
        return count;
    }

    private static int countStmts(List<ConceptStmt> stmts) {
        int count = 0;
        for (ConceptStmt s : stmts) {
            count++;
            if (s instanceof ConceptStmt.If iff) {
                count += countStmts(iff.thenBody()) + countStmts(iff.elseBody());
            } else if (s instanceof ConceptStmt.While wh) {
                count += countStmts(wh.body());
            }
        }
        return count;
    }
}
