package io.github.dailystruggle.bytecode.transpile;

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
import io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.BatchLayout;
import io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.EmitOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Target-independent compiler integration and optimization pass over {@link BytecodeConceptIr}.
 *
 * <p>Performs single-exit return normalization, cross-method static call graph inlining,
 * stack-merge ternary recovery, constant folding, single-assignment copy propagation, dead local
 * elimination, constant LUT deduplication, Q32.32 coordinate-chain lowering analysis, and
 * batched 3D data-parallel kernel promotion.
 *
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy corpus-atlas-ADR-002
 * @trace REQ-CFG-ATLAS-001
 */
public final class ConceptOptimizerPass {

    private ConceptOptimizerPass() {
    }

    /**
     * Configuration options for the target-independent optimization pipeline.
     */
    public record OptimizationConfig(
            boolean inlineHelperCalls,
            boolean foldTernaryExpressions,
            boolean foldConstants,
            boolean promote3DKernel,
            boolean hoistYInvariantExpressions
    ) {
        public OptimizationConfig(
                boolean inlineHelperCalls,
                boolean foldTernaryExpressions,
                boolean foldConstants,
                boolean promote3DKernel) {
            this(inlineHelperCalls, foldTernaryExpressions, foldConstants, promote3DKernel, true);
        }

        public static OptimizationConfig defaults() {
            return new OptimizationConfig(true, true, true, true, true);
        }

        /**
         * Optimization preset for direct JVM bytecode emission ({@link JvmBytecodeConceptEmitter}):
         * disables eager AST helper pre-inlining so HotSpot C2 compiles every method below its
         * 8,000-byte {@code HugeMethodLimit} and inlines natively, while keeping constant folding,
         * pure {@code Call} CSE, and Y-invariant hoisting enabled.
         */
        public static OptimizationConfig forJvmBytecode() {
            return new OptimizationConfig(false, true, true, false, true);
        }
    }

    /**
     * Runs the full integration and optimization pipeline on a lifted {@link ConceptModule}.
     */
    public static ConceptModule optimize(ConceptModule module) {
        return optimize(module, OptimizationConfig.defaults());
    }

    /**
     * Runs the optimization and generic lowering preparation passes configured by
     * {@link EmitOptions} on a lifted {@link ConceptModule}.
     */
    public static ConceptModule optimize(ConceptModule module, EmitOptions options) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(options, "options");
        return optimize(module, options.optimizationConfig());
    }

    /**
     * Runs the configured optimization passes on a lifted {@link ConceptModule}.
     */
    public static ConceptModule optimize(ConceptModule module, OptimizationConfig config) {
        return optimizeWithDiagnostics(
                        module,
                        config,
                        IrIdiomRewriteRegistry.RewriteContext.exact("IR_OPTIMIZER"))
                .module();
    }

    /**
     * Runs standard SSA optimization followed by structural idiom pattern-matching and rewriting
     * via {@link IrIdiomRewriteRegistry}, returning both the rewritten {@link ConceptModule} and
     * all emitted {@link IrPatternDiagnostic} records and LUT compaction hints.
     */
    public static IrIdiomRewriteRegistry.RewriteResult optimizeWithDiagnostics(
            ConceptModule module,
            OptimizationConfig config,
            IrIdiomRewriteRegistry.RewriteContext rewriteContext
    ) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(config, "config");

        Map<String, ConceptLut> lutsBySymbol = new LinkedHashMap<>();
        for (ConceptLut lut : module.lookupTables()) {
            lutsBySymbol.putIfAbsent(lut.symbolName(), lut);
        }

        Map<String, ConceptFunction> optimizedHelpersByKey = new LinkedHashMap<>();
        Map<String, ConceptFunction> optimizedHelpersByName = new LinkedHashMap<>();

        // Optimize helpers bottom-up in topological callee-first order so leaf helpers fold and inline first.
        List<ConceptFunction> rawHelpers = module.helperFunctions();
        List<ConceptFunction> topoHelpers = topologicalSortHelpers(rawHelpers);
        for (ConceptFunction helper : topoHelpers) {
            ConceptFunction optHelper = optimizeFunction(
                    helper,
                    optimizedHelpersByKey,
                    optimizedHelpersByName,
                    lutsBySymbol,
                    config);
            optimizedHelpersByKey.put(optHelper.key(), optHelper);
            optimizedHelpersByName.put(optHelper.name(), optHelper);
        }

        ConceptFunction optEntry = optimizeFunction(
                module.entryFunction(),
                optimizedHelpersByKey,
                optimizedHelpersByName,
                lutsBySymbol,
                config);

        BytecodeConceptIr.ConceptWorkgroupPipeline optWg = null;
        if (module.workgroupPipeline() != null) {
            BytecodeConceptIr.ConceptWorkgroupPipeline rawWg = module.workgroupPipeline();
            List<ConceptFunction> optStages = new ArrayList<>(rawWg.stageFunctions().size());
            for (ConceptFunction st : rawWg.stageFunctions()) {
                optStages.add(optimizeFunction(
                        st, optimizedHelpersByKey, optimizedHelpersByName, lutsBySymbol, config));
            }
            optWg = new BytecodeConceptIr.ConceptWorkgroupPipeline(
                    rawWg.kernelName(),
                    rawWg.workgroupSize(),
                    rawWg.cornerEvalFunctionName(),
                    rawWg.localBuffers(),
                    optStages);
        }

        List<ConceptFunction> retainedHelpers = new ArrayList<>();
        if (!config.inlineHelperCalls()) {
            for (ConceptFunction helper : rawHelpers) {
                retainedHelpers.add(optimizedHelpersByKey.getOrDefault(helper.key(), helper));
            }
        } else {
            Set<String> remainingCalls = new HashSet<>();
            collectCalledKeysFromStmts(optEntry.body(), remainingCalls);
            if (optWg != null) {
                for (ConceptFunction st : optWg.stageFunctions()) {
                    collectCalledKeysFromStmts(st.body(), remainingCalls);
                    remainingCalls.add(st.key());
                    remainingCalls.add(st.name());
                }
            }
            // Transitively retain helpers called by retained helpers.
            boolean grew = true;
            while (grew) {
                grew = false;
                for (ConceptFunction helper : rawHelpers) {
                    ConceptFunction optH = optimizedHelpersByKey.getOrDefault(helper.key(), helper);
                    if (remainingCalls.contains(optH.key()) || remainingCalls.contains(optH.name())) {
                        int before = remainingCalls.size();
                        collectCalledKeysFromStmts(optH.body(), remainingCalls);
                        if (remainingCalls.size() > before) {
                            grew = true;
                        }
                    }
                }
            }
            for (ConceptFunction helper : rawHelpers) {
                ConceptFunction optH = optimizedHelpersByKey.getOrDefault(helper.key(), helper);
                if (remainingCalls.contains(optH.key()) || remainingCalls.contains(optH.name())) {
                    retainedHelpers.add(optH);
                }
            }
        }

        ConceptKernel3D kernel3D = module.kernel3D();
        if (config.promote3DKernel() && kernel3D == null && optEntry.returnType() != ConceptType.VOID) {
            kernel3D = buildKernel3D(optEntry);
        }

        ConceptModule optimized = new ConceptModule(
                module.moduleName(),
                module.lookupTables(),
                retainedHelpers,
                optEntry,
                kernel3D,
                optWg,
                module.unmappedReasons(),
                module.liftedMethodSymbols(),
                module.liftedFieldSymbols());
        ConceptModule cleaned = pruneUnusedLookupTables(deduplicateLookupTables(optimized));
        return IrIdiomRewriteRegistry.defaults().apply(
                cleaned,
                rewriteContext != null
                        ? rewriteContext
                        : IrIdiomRewriteRegistry.RewriteContext.exact("IR_OPTIMIZER"));
    }

    private static List<ConceptFunction> topologicalSortHelpers(List<ConceptFunction> rawHelpers) {
        if (rawHelpers.size() <= 1) {
            return rawHelpers;
        }
        Map<String, ConceptFunction> byKey = new LinkedHashMap<>();
        Map<String, ConceptFunction> byName = new LinkedHashMap<>();
        for (ConceptFunction fn : rawHelpers) {
            byKey.put(fn.key(), fn);
            byName.putIfAbsent(fn.name(), fn);
        }
        List<ConceptFunction> ordered = new ArrayList<>(rawHelpers.size());
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (int i = rawHelpers.size() - 1; i >= 0; i--) {
            topoVisit(rawHelpers.get(i), byKey, byName, visited, visiting, ordered);
        }
        return ordered;
    }

    private static void topoVisit(
            ConceptFunction fn,
            Map<String, ConceptFunction> byKey,
            Map<String, ConceptFunction> byName,
            Set<String> visited,
            Set<String> visiting,
            List<ConceptFunction> ordered) {
        String key = fn.key();
        if (visited.contains(key) || visiting.contains(key)) {
            return;
        }
        visiting.add(key);
        Set<String> called = new LinkedHashSet<>();
        collectCalledKeysFromStmts(fn.body(), called);
        for (String ref : called) {
            ConceptFunction callee = byKey.get(ref);
            if (callee == null) {
                callee = byName.get(ref);
            }
            if (callee != null && !callee.key().equals(key)) {
                topoVisit(callee, byKey, byName, visited, visiting, ordered);
            }
        }
        visiting.remove(key);
        visited.add(key);
        ordered.add(fn);
    }

    private static ConceptFunction optimizeFunction(
            ConceptFunction fn,
            Map<String, ConceptFunction> helpersByKey,
            Map<String, ConceptFunction> helpersByName,
            Map<String, ConceptLut> lutsBySymbol,
            OptimizationConfig config) {
        Map<String, ConceptType> localTypes = new LinkedHashMap<>(fn.localTypes());
        List<ConceptStmt> stmts = stripVarDecls(fn.body());
        Set<String> paramNames = new HashSet<>();
        for (ConceptParam p : fn.parameters()) {
            paramNames.add(p.name());
        }

        if (config.foldTernaryExpressions()) {
            stmts = foldDiamondsToTernaries(stmts);
        }
        if (config.foldConstants()) {
            stmts = foldConstantsInStmts(stmts, lutsBySymbol);
        }
        if (config.inlineHelperCalls() && (!helpersByKey.isEmpty() || !helpersByName.isEmpty())) {
            int[] inlineCounter = {0, 0};
            Set<String> activeStack = new HashSet<>();
            activeStack.add(fn.key());
            for (int inlinePass = 0; inlinePass < 16; inlinePass++) {
                int before = inlineCounter[0];
                stmts = inlineCallsInStmts(
                        stmts,
                        localTypes,
                        helpersByKey,
                        helpersByName,
                        inlineCounter,
                        activeStack,
                        config);
                if (config.foldConstants()) {
                    stmts = foldConstantsInStmts(stmts, lutsBySymbol);
                }
                stmts = propagateSingleUsePhiTemporaries(stmts);
                if (inlineCounter[0] == before) {
                    break;
                }
            }
        }

        for (int pass = 0; pass < 8; pass++) {
            List<ConceptStmt> prev = stmts;
            if (config.foldTernaryExpressions()) {
                stmts = foldDiamondsToTernaries(stmts);
            }
            if (config.foldConstants()) {
                stmts = foldConstantsInStmts(stmts, lutsBySymbol);
            }
            stmts = propagateSingleUsePhiTemporaries(stmts);
            if (config.foldConstants()) {
                stmts = foldConstantsInStmts(stmts, lutsBySymbol);
            }
            Set<String> usedVars = new HashSet<>();
            collectUsedVariablesInStmts(stmts, usedVars);
            stmts = removeDeadAssignments(stmts, usedVars, paramNames);
            if (stmts.equals(prev)) {
                break;
            }
        }
        if (config.foldTernaryExpressions()) {
            stmts = foldDiamondsToTernaries(stmts);
        }
        if (!helpersByKey.isEmpty() || !helpersByName.isEmpty()) {
            stmts = eliminateCommonPureCalls(stmts, localTypes, helpersByKey, helpersByName);
        }
        if (config.hoistYInvariantExpressions()) {
            stmts = hoistYInvariantStatements(stmts, fn.parameters());
        }

        // RebuildUsedVars after dead assignment pruning
        Set<String> finalUsedVars = new HashSet<>();
        collectAssignedAndUsedVariables(stmts, finalUsedVars);

        Map<String, ConceptType> prunedLocals = new LinkedHashMap<>();
        List<ConceptStmt> finalBody = new ArrayList<>();
        for (ConceptParam p : fn.parameters()) {
            prunedLocals.put(p.name(), p.type());
        }
        for (Map.Entry<String, ConceptType> entry : localTypes.entrySet()) {
            String varName = entry.getKey();
            if (!paramNames.contains(varName) && finalUsedVars.contains(varName)) {
                prunedLocals.put(varName, entry.getValue());
                finalBody.add(new ConceptStmt.VarDecl(
                        varName,
                        entry.getValue(),
                        defaultZero(entry.getValue())));
            }
        }
        finalBody.addAll(stmts);

        return new ConceptFunction(
                fn.ownerClass(),
                fn.name(),
                fn.descriptor(),
                fn.parameters(),
                fn.returnType(),
                prunedLocals,
                finalBody,
                fn.pureCompute());
    }

    private static List<ConceptStmt> stripVarDecls(List<ConceptStmt> stmts) {
        List<ConceptStmt> out = new ArrayList<>(stmts.size());
        for (ConceptStmt s : stmts) {
            if (!(s instanceof ConceptStmt.VarDecl)) {
                out.add(s);
            }
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Pass 1: Diamond If/Else -> Ternary Expression Recovery
    // -------------------------------------------------------------------------

    private static List<ConceptStmt> foldDiamondsToTernaries(List<ConceptStmt> stmts) {
        List<ConceptStmt> out = new ArrayList<>(stmts.size());
        for (ConceptStmt stmt : stmts) {
            if (stmt instanceof ConceptStmt.If ifStmt) {
                List<ConceptStmt> thenOpt = foldDiamondsToTernaries(ifStmt.thenBody());
                List<ConceptStmt> elseOpt = foldDiamondsToTernaries(ifStmt.elseBody());
                if (!thenOpt.isEmpty() && thenOpt.size() == elseOpt.size()
                        && allMatchingAssigns(thenOpt, elseOpt)) {
                    for (int idx = 0; idx < thenOpt.size(); idx++) {
                        ConceptStmt.Assign thenAssign = (ConceptStmt.Assign) thenOpt.get(idx);
                        ConceptStmt.Assign elseAssign = (ConceptStmt.Assign) elseOpt.get(idx);
                        if (thenAssign.value().equals(elseAssign.value())) {
                            out.add(new ConceptStmt.Assign(
                                    thenAssign.name(),
                                    thenAssign.type(),
                                    thenAssign.value()));
                        } else {
                            out.add(new ConceptStmt.Assign(
                                    thenAssign.name(),
                                    thenAssign.type(),
                                    new ConceptExpr.Ternary(
                                            ifStmt.condition(),
                                            thenAssign.value(),
                                            elseAssign.value(),
                                            thenAssign.type())));
                        }
                    }
                } else {
                    out.add(new ConceptStmt.If(ifStmt.condition(), thenOpt, elseOpt));
                }
            } else if (stmt instanceof ConceptStmt.While whileStmt) {
                out.add(new ConceptStmt.While(
                        whileStmt.condition(),
                        foldDiamondsToTernaries(whileStmt.body())));
            } else {
                out.add(stmt);
            }
        }
        return out;
    }

    private static boolean allMatchingAssigns(List<ConceptStmt> thenOpt, List<ConceptStmt> elseOpt) {
        for (int i = 0; i < thenOpt.size(); i++) {
            if (!(thenOpt.get(i) instanceof ConceptStmt.Assign ta)
                    || !(elseOpt.get(i) instanceof ConceptStmt.Assign ea)
                    || !ta.name().equals(ea.name())) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Pass 2: Cross-Method Static Call Inlining with Single-Exit Normalization
    // -------------------------------------------------------------------------

    private static List<ConceptStmt> inlineCallsInStmts(
            List<ConceptStmt> stmts,
            Map<String, ConceptType> callerLocals,
            Map<String, ConceptFunction> helpersByKey,
            Map<String, ConceptFunction> helpersByName,
            int[] inlineCounter,
            Set<String> activeStack,
            OptimizationConfig config) {
        List<ConceptStmt> out = new ArrayList<>();
        for (ConceptStmt stmt : stmts) {
            if (stmt instanceof ConceptStmt.Assign assign) {
                List<ConceptStmt> preStmts = new ArrayList<>();
                ConceptExpr rewritten = inlineCallsInExpr(
                        assign.value(),
                        preStmts,
                        callerLocals,
                        helpersByKey,
                        helpersByName,
                        inlineCounter,
                        activeStack);
                out.addAll(preStmts);
                out.add(new ConceptStmt.Assign(assign.name(), assign.type(), rewritten));
            } else if (stmt instanceof ConceptStmt.Return ret && ret.value() != null) {
                List<ConceptStmt> preStmts = new ArrayList<>();
                ConceptExpr rewritten = inlineCallsInExpr(
                        ret.value(),
                        preStmts,
                        callerLocals,
                        helpersByKey,
                        helpersByName,
                        inlineCounter,
                        activeStack);
                out.addAll(preStmts);
                out.add(new ConceptStmt.Return(rewritten));
            } else if (stmt instanceof ConceptStmt.If ifStmt) {
                List<ConceptStmt> condPre = new ArrayList<>();
                ConceptExpr condRewritten = inlineCallsInExpr(
                        ifStmt.condition(),
                        condPre,
                        callerLocals,
                        helpersByKey,
                        helpersByName,
                        inlineCounter,
                        activeStack);
                out.addAll(condPre);
                List<ConceptStmt> thenInlined = inlineCallsInStmts(
                        ifStmt.thenBody(),
                        callerLocals,
                        helpersByKey,
                        helpersByName,
                        inlineCounter,
                        activeStack,
                        config);
                List<ConceptStmt> elseInlined = inlineCallsInStmts(
                        ifStmt.elseBody(),
                        callerLocals,
                        helpersByKey,
                        helpersByName,
                        inlineCounter,
                        activeStack,
                        config);
                out.add(new ConceptStmt.If(condRewritten, thenInlined, elseInlined));
            } else if (stmt instanceof ConceptStmt.While whileStmt) {
                List<ConceptStmt> condPre = new ArrayList<>();
                ConceptExpr condRewritten = inlineCallsInExpr(
                        whileStmt.condition(),
                        condPre,
                        callerLocals,
                        helpersByKey,
                        helpersByName,
                        inlineCounter,
                        activeStack);
                List<ConceptStmt> bodyInlined = inlineCallsInStmts(
                        whileStmt.body(),
                        callerLocals,
                        helpersByKey,
                        helpersByName,
                        inlineCounter,
                        activeStack,
                        config);
                if (condPre.isEmpty()) {
                    out.add(new ConceptStmt.While(condRewritten, bodyInlined));
                } else {
                    out.addAll(condPre);
                    List<ConceptStmt> bodyWithCond = new ArrayList<>(bodyInlined);
                    bodyWithCond.addAll(condPre);
                    out.add(new ConceptStmt.While(condRewritten, bodyWithCond));
                }
            } else {
                out.add(stmt);
            }
        }
        return out;
    }

    private static ConceptExpr inlineCallsInExpr(
            ConceptExpr expr,
            List<ConceptStmt> preStmts,
            Map<String, ConceptType> callerLocals,
            Map<String, ConceptFunction> helpersByKey,
            Map<String, ConceptFunction> helpersByName,
            int[] inlineCounter,
            Set<String> activeStack) {
        if (expr instanceof ConceptExpr.BinaryOp bin) {
            ConceptExpr l = inlineCallsInExpr(
                    bin.left(), preStmts, callerLocals, helpersByKey, helpersByName, inlineCounter, activeStack);
            ConceptExpr r = inlineCallsInExpr(
                    bin.right(), preStmts, callerLocals, helpersByKey, helpersByName, inlineCounter, activeStack);
            return new ConceptExpr.BinaryOp(bin.op(), l, r, bin.type());
        }
        if (expr instanceof ConceptExpr.UnaryOp u) {
            ConceptExpr op = inlineCallsInExpr(
                    u.operand(), preStmts, callerLocals, helpersByKey, helpersByName, inlineCounter, activeStack);
            return new ConceptExpr.UnaryOp(u.op(), op, u.type());
        }
        if (expr instanceof ConceptExpr.Cast cast) {
            ConceptExpr op = inlineCallsInExpr(
                    cast.operand(), preStmts, callerLocals, helpersByKey, helpersByName, inlineCounter, activeStack);
            return new ConceptExpr.Cast(cast.targetType(), op);
        }
        if (expr instanceof ConceptExpr.Ternary tern) {
            ConceptExpr c = inlineCallsInExpr(
                    tern.condition(), preStmts, callerLocals, helpersByKey, helpersByName, inlineCounter, activeStack);
            ConceptExpr t = inlineCallsInExpr(
                    tern.thenExpr(), preStmts, callerLocals, helpersByKey, helpersByName, inlineCounter, activeStack);
            ConceptExpr e = inlineCallsInExpr(
                    tern.elseExpr(), preStmts, callerLocals, helpersByKey, helpersByName, inlineCounter, activeStack);
            return new ConceptExpr.Ternary(c, t, e, tern.type());
        }
        if (expr instanceof ConceptExpr.LutLoad load) {
            ConceptExpr idx = inlineCallsInExpr(
                    load.index(), preStmts, callerLocals, helpersByKey, helpersByName, inlineCounter, activeStack);
            return new ConceptExpr.LutLoad(load.lutSymbol(), idx, load.elementType());
        }
        if (expr instanceof ConceptExpr.MathIntrinsic math) {
            List<ConceptExpr> newArgs = new ArrayList<>(math.arguments().size());
            for (ConceptExpr arg : math.arguments()) {
                newArgs.add(inlineCallsInExpr(
                        arg, preStmts, callerLocals, helpersByKey, helpersByName, inlineCounter, activeStack));
            }
            return new ConceptExpr.MathIntrinsic(math.kind(), newArgs, math.type());
        }
        if (expr instanceof ConceptExpr.Call call) {
            List<ConceptExpr> inlinedArgs = new ArrayList<>(call.arguments().size());
            for (ConceptExpr arg : call.arguments()) {
                inlinedArgs.add(inlineCallsInExpr(
                        arg, preStmts, callerLocals, helpersByKey, helpersByName, inlineCounter, activeStack));
            }
            ConceptFunction target = helpersByKey.get(call.targetKey());
            if (target == null) {
                ConceptFunction byName = helpersByName.get(call.methodName());
                if (byName != null && byName.parameters().size() == inlinedArgs.size()) {
                    target = byName;
                }
            }
            int calleeStmtCount = target == null ? 0 : countStmtsUpTo(target.body(), MAX_INLINE_HELPER_STMTS + 1);
            if (target == null
                    || !target.pureCompute()
                    || target.parameters().size() != inlinedArgs.size()
                    || inlineCounter[0] >= MAX_INLINES_PER_FUNCTION
                    || activeStack.contains(target.key())
                    || containsReturnInsideWhile(target.body())
                    || calleeStmtCount > MAX_INLINE_HELPER_STMTS
                    || (inlineCounter.length > 1
                            && inlineCounter[1] + calleeStmtCount > MAX_INLINED_STMTS_PER_FUNCTION)) {
                return new ConceptExpr.Call(
                        call.ownerClass(),
                        call.methodName(),
                        call.descriptor(),
                        inlinedArgs,
                        call.returnType());
            }

            int siteId = ++inlineCounter[0];
            if (inlineCounter.length > 1) {
                inlineCounter[1] += calleeStmtCount;
            }
            String prefix = "inl" + siteId + "_";
            Map<String, String> renameMap = new HashMap<>();
            for (Map.Entry<String, ConceptType> local : target.localTypes().entrySet()) {
                String mapped = prefix + local.getKey();
                renameMap.put(local.getKey(), mapped);
                callerLocals.put(mapped, local.getValue());
            }
            for (int i = 0; i < target.parameters().size() && i < inlinedArgs.size(); i++) {
                ConceptParam p = target.parameters().get(i);
                String mappedParam = renameMap.computeIfAbsent(p.name(), k -> prefix + k);
                callerLocals.put(mappedParam, p.type());
                preStmts.add(new ConceptStmt.Assign(mappedParam, p.type(), inlinedArgs.get(i)));
            }

            String retVar = prefix + "ret";
            callerLocals.put(retVar, target.returnType());

            List<ConceptStmt> calleeStmts = stripVarDecls(target.body());
            List<ConceptStmt> renamedStmts = renameVariablesInStmts(calleeStmts, renameMap);
            List<ConceptStmt> singleExitStmts = normalizeReturnsToAssign(
                    renamedStmts,
                    retVar,
                    target.returnType());
            preStmts.addAll(singleExitStmts);
            return new ConceptExpr.Var(retVar, target.returnType());
        }
        return expr;
    }

    private static final int MAX_INLINES_PER_FUNCTION = 64;
    private static final int MAX_INLINE_HELPER_STMTS = 256;
    private static final int MAX_INLINED_STMTS_PER_FUNCTION = 900;

    private record CallCseKey(String targetKey, List<ConceptExpr> args) {}

    private static List<ConceptStmt> eliminateCommonPureCalls(
            List<ConceptStmt> stmts,
            Map<String, ConceptType> localTypes,
            Map<String, ConceptFunction> helpersByKey,
            Map<String, ConceptFunction> helpersByName) {
        int[] cseSeq = {0};
        return eliminateCommonPureCallsInBlock(
                stmts,
                localTypes,
                helpersByKey,
                helpersByName,
                new HashMap<>(),
                new HashMap<>(),
                cseSeq);
    }

    private static List<ConceptStmt> eliminateCommonPureCallsInBlock(
            List<ConceptStmt> stmts,
            Map<String, ConceptType> localTypes,
            Map<String, ConceptFunction> helpersByKey,
            Map<String, ConceptFunction> helpersByName,
            Map<CallCseKey, String> available,
            Map<String, Set<String>> depsByVar,
            int[] cseSeq) {
        List<ConceptStmt> out = new ArrayList<>(stmts.size());
        for (ConceptStmt stmt : stmts) {
            if (stmt instanceof ConceptStmt.Assign a) {
                ConceptExpr rewritten = rewriteExprWithCallCse(
                        a.value(), out, localTypes, helpersByKey, helpersByName, available, depsByVar, cseSeq, true);
                out.add(new ConceptStmt.Assign(a.name(), a.type(), rewritten));
                invalidateCseDeps(a.name(), available, depsByVar);
            } else if (stmt instanceof ConceptStmt.Return ret) {
                ConceptExpr rewritten = ret.value() == null
                        ? null
                        : rewriteExprWithCallCse(
                                ret.value(),
                                out,
                                localTypes,
                                helpersByKey,
                                helpersByName,
                                available,
                                depsByVar,
                                cseSeq,
                                true);
                out.add(new ConceptStmt.Return(rewritten));
            } else if (stmt instanceof ConceptStmt.If ifStmt) {
                ConceptExpr cond = rewriteExprWithCallCse(
                        ifStmt.condition(),
                        out,
                        localTypes,
                        helpersByKey,
                        helpersByName,
                        available,
                        depsByVar,
                        cseSeq,
                        true);
                List<ConceptStmt> thenOut = eliminateCommonPureCallsInBlock(
                        ifStmt.thenBody(),
                        localTypes,
                        helpersByKey,
                        helpersByName,
                        new HashMap<>(available),
                        new HashMap<>(depsByVar),
                        cseSeq);
                List<ConceptStmt> elseOut = eliminateCommonPureCallsInBlock(
                        ifStmt.elseBody(),
                        localTypes,
                        helpersByKey,
                        helpersByName,
                        new HashMap<>(available),
                        new HashMap<>(depsByVar),
                        cseSeq);
                out.add(new ConceptStmt.If(cond, thenOut, elseOut));
                Set<String> mutated = new HashSet<>();
                collectAssignedVarsInStmts(thenOut, mutated);
                collectAssignedVarsInStmts(elseOut, mutated);
                for (String m : mutated) {
                    invalidateCseDeps(m, available, depsByVar);
                }
            } else if (stmt instanceof ConceptStmt.While whileStmt) {
                Set<String> loopMutated = new HashSet<>();
                collectAssignedVarsInStmts(whileStmt.body(), loopMutated);
                for (String m : loopMutated) {
                    invalidateCseDeps(m, available, depsByVar);
                }
                ConceptExpr cond = rewriteExprWithCallCse(
                        whileStmt.condition(),
                        out,
                        localTypes,
                        helpersByKey,
                        helpersByName,
                        available,
                        depsByVar,
                        cseSeq,
                        false);
                List<ConceptStmt> bodyOut = eliminateCommonPureCallsInBlock(
                        whileStmt.body(),
                        localTypes,
                        helpersByKey,
                        helpersByName,
                        new HashMap<>(available),
                        new HashMap<>(depsByVar),
                        cseSeq);
                out.add(new ConceptStmt.While(cond, bodyOut));
            } else {
                out.add(stmt);
            }
        }
        return out;
    }

    private static ConceptExpr rewriteExprWithCallCse(
            ConceptExpr expr,
            List<ConceptStmt> out,
            Map<String, ConceptType> localTypes,
            Map<String, ConceptFunction> helpersByKey,
            Map<String, ConceptFunction> helpersByName,
            Map<CallCseKey, String> available,
            Map<String, Set<String>> depsByVar,
            int[] cseSeq,
            boolean allowEmit) {
        if (expr instanceof ConceptExpr.BinaryOp bin) {
            ConceptExpr left = rewriteExprWithCallCse(
                    bin.left(), out, localTypes, helpersByKey, helpersByName, available, depsByVar, cseSeq, allowEmit);
            boolean shortCircuit =
                    bin.op() == BinaryOperator.LOGICAL_AND || bin.op() == BinaryOperator.LOGICAL_OR;
            ConceptExpr right = rewriteExprWithCallCse(
                    bin.right(),
                    out,
                    localTypes,
                    helpersByKey,
                    helpersByName,
                    available,
                    depsByVar,
                    cseSeq,
                    allowEmit && !shortCircuit);
            return new ConceptExpr.BinaryOp(bin.op(), left, right, bin.type());
        }
        if (expr instanceof ConceptExpr.UnaryOp u) {
            ConceptExpr op = rewriteExprWithCallCse(
                    u.operand(), out, localTypes, helpersByKey, helpersByName, available, depsByVar, cseSeq, allowEmit);
            return new ConceptExpr.UnaryOp(u.op(), op, u.type());
        }
        if (expr instanceof ConceptExpr.Cast c) {
            ConceptExpr op = rewriteExprWithCallCse(
                    c.operand(), out, localTypes, helpersByKey, helpersByName, available, depsByVar, cseSeq, allowEmit);
            return new ConceptExpr.Cast(c.targetType(), op);
        }
        if (expr instanceof ConceptExpr.Ternary t) {
            ConceptExpr cond = rewriteExprWithCallCse(
                    t.condition(), out, localTypes, helpersByKey, helpersByName, available, depsByVar, cseSeq, allowEmit);
            ConceptExpr thenE = rewriteExprWithCallCse(
                    t.thenExpr(), out, localTypes, helpersByKey, helpersByName, available, depsByVar, cseSeq, false);
            ConceptExpr elseE = rewriteExprWithCallCse(
                    t.elseExpr(), out, localTypes, helpersByKey, helpersByName, available, depsByVar, cseSeq, false);
            return new ConceptExpr.Ternary(cond, thenE, elseE, t.type());
        }
        if (expr instanceof ConceptExpr.LutLoad l) {
            ConceptExpr idx = rewriteExprWithCallCse(
                    l.index(), out, localTypes, helpersByKey, helpersByName, available, depsByVar, cseSeq, allowEmit);
            return new ConceptExpr.LutLoad(l.lutSymbol(), idx, l.elementType());
        }
        if (expr instanceof ConceptExpr.MathIntrinsic m) {
            List<ConceptExpr> newArgs = new ArrayList<>(m.arguments().size());
            for (ConceptExpr a : m.arguments()) {
                newArgs.add(rewriteExprWithCallCse(
                        a, out, localTypes, helpersByKey, helpersByName, available, depsByVar, cseSeq, allowEmit));
            }
            return new ConceptExpr.MathIntrinsic(m.kind(), newArgs, m.type());
        }
        if (expr instanceof ConceptExpr.Call call) {
            List<ConceptExpr> newArgs = new ArrayList<>(call.arguments().size());
            for (ConceptExpr a : call.arguments()) {
                newArgs.add(rewriteExprWithCallCse(
                        a, out, localTypes, helpersByKey, helpersByName, available, depsByVar, cseSeq, allowEmit));
            }
            ConceptFunction target = helpersByKey.get(call.targetKey());
            if (target == null) {
                ConceptFunction byName = helpersByName.get(call.methodName());
                if (byName != null && byName.parameters().size() == newArgs.size()) {
                    target = byName;
                }
            }
            ConceptExpr.Call rewrittenCall = new ConceptExpr.Call(
                    call.ownerClass(), call.methodName(), call.descriptor(), newArgs, call.returnType());
            if (target == null || !target.pureCompute() || call.returnType() == ConceptType.VOID) {
                return rewrittenCall;
            }
            CallCseKey key = new CallCseKey(target.key(), newArgs);
            String existing = available.get(key);
            if (existing != null) {
                return new ConceptExpr.Var(existing, call.returnType());
            }
            if (!allowEmit) {
                return rewrittenCall;
            }
            String tmp = "cse_" + (++cseSeq[0]);
            localTypes.put(tmp, call.returnType());
            out.add(new ConceptStmt.Assign(tmp, call.returnType(), rewrittenCall));
            Map<String, Integer> argReads = new HashMap<>();
            for (ConceptExpr a : newArgs) {
                countReadsInExpr(a, argReads);
            }
            available.put(key, tmp);
            depsByVar.put(tmp, new HashSet<>(argReads.keySet()));
            return new ConceptExpr.Var(tmp, call.returnType());
        }
        return expr;
    }

    private static void invalidateCseDeps(
            String mutatedVar, Map<CallCseKey, String> available, Map<String, Set<String>> depsByVar) {
        if (available.isEmpty()) {
            return;
        }
        available.entrySet().removeIf(e -> {
            String tmp = e.getValue();
            if (mutatedVar.equals(tmp)) {
                return true;
            }
            Set<String> deps = depsByVar.get(tmp);
            return deps != null && deps.contains(mutatedVar);
        });
    }

    private static void collectAssignedVarsInStmts(List<ConceptStmt> stmts, Set<String> out) {
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.Assign a) {
                out.add(a.name());
            } else if (s instanceof ConceptStmt.If ifStmt) {
                collectAssignedVarsInStmts(ifStmt.thenBody(), out);
                collectAssignedVarsInStmts(ifStmt.elseBody(), out);
            } else if (s instanceof ConceptStmt.While whileStmt) {
                collectAssignedVarsInStmts(whileStmt.body(), out);
            }
        }
    }

    private static int countStmtsUpTo(List<ConceptStmt> stmts, int cap) {
        int[] count = {0};
        countStmtsBounded(stmts, cap, count);
        return count[0];
    }

    private static boolean countStmtsBounded(List<ConceptStmt> stmts, int maxStmts, int[] count) {
        for (ConceptStmt s : stmts) {
            if (++count[0] > maxStmts) {
                return true;
            }
            if (s instanceof ConceptStmt.If ifStmt) {
                if (countStmtsBounded(ifStmt.thenBody(), maxStmts, count)
                        || countStmtsBounded(ifStmt.elseBody(), maxStmts, count)) {
                    return true;
                }
            } else if (s instanceof ConceptStmt.While w) {
                if (countStmtsBounded(w.body(), maxStmts, count)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsReturnInsideWhile(List<ConceptStmt> stmts) {
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.While w) {
                if (containsAnyReturn(w.body())) {
                    return true;
                }
            } else if (s instanceof ConceptStmt.If ifStmt) {
                if (containsReturnInsideWhile(ifStmt.thenBody()) || containsReturnInsideWhile(ifStmt.elseBody())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsAnyReturn(List<ConceptStmt> stmts) {
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.Return) {
                return true;
            }
            if (s instanceof ConceptStmt.If ifStmt) {
                if (containsAnyReturn(ifStmt.thenBody()) || containsAnyReturn(ifStmt.elseBody())) {
                    return true;
                }
            } else if (s instanceof ConceptStmt.While w) {
                if (containsAnyReturn(w.body())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Converts structured statements that may contain early {@code return} guards into a
     * single-exit statement list that assigns the return value to {@code retVar}.
     */
    private static List<ConceptStmt> normalizeReturnsToAssign(
            List<ConceptStmt> stmts,
            String retVar,
            ConceptType retType) {
        List<ConceptStmt> out = new ArrayList<>();
        for (int i = 0; i < stmts.size(); i++) {
            ConceptStmt s = stmts.get(i);
            if (s instanceof ConceptStmt.Return ret) {
                if (ret.value() != null) {
                    out.add(new ConceptStmt.Assign(retVar, retType, ret.value()));
                }
                return out;
            }
            if (s instanceof ConceptStmt.If ifStmt) {
                boolean thenAlwaysReturns = endsWithReturn(ifStmt.thenBody());
                boolean elseAlwaysReturns = !ifStmt.elseBody().isEmpty() && endsWithReturn(ifStmt.elseBody());
                if (thenAlwaysReturns && !elseAlwaysReturns && i + 1 < stmts.size()) {
                    List<ConceptStmt> normThen = normalizeReturnsToAssign(ifStmt.thenBody(), retVar, retType);
                    List<ConceptStmt> combinedElse = new ArrayList<>(ifStmt.elseBody());
                    combinedElse.addAll(stmts.subList(i + 1, stmts.size()));
                    List<ConceptStmt> normElse = normalizeReturnsToAssign(combinedElse, retVar, retType);
                    out.add(new ConceptStmt.If(ifStmt.condition(), normThen, normElse));
                    return out;
                }
                if (elseAlwaysReturns && !thenAlwaysReturns && i + 1 < stmts.size()) {
                    List<ConceptStmt> combinedThen = new ArrayList<>(ifStmt.thenBody());
                    combinedThen.addAll(stmts.subList(i + 1, stmts.size()));
                    List<ConceptStmt> normThen = normalizeReturnsToAssign(combinedThen, retVar, retType);
                    List<ConceptStmt> normElse = normalizeReturnsToAssign(ifStmt.elseBody(), retVar, retType);
                    out.add(new ConceptStmt.If(ifStmt.condition(), normThen, normElse));
                    return out;
                }
                List<ConceptStmt> normThen = normalizeReturnsToAssign(ifStmt.thenBody(), retVar, retType);
                List<ConceptStmt> normElse = normalizeReturnsToAssign(ifStmt.elseBody(), retVar, retType);
                out.add(new ConceptStmt.If(ifStmt.condition(), normThen, normElse));
                if (thenAlwaysReturns && elseAlwaysReturns) {
                    return out;
                }
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private static boolean endsWithReturn(List<ConceptStmt> stmts) {
        if (stmts.isEmpty()) {
            return false;
        }
        ConceptStmt last = stmts.get(stmts.size() - 1);
        if (last instanceof ConceptStmt.Return) {
            return true;
        }
        if (last instanceof ConceptStmt.If ifStmt) {
            return endsWithReturn(ifStmt.thenBody())
                    && !ifStmt.elseBody().isEmpty()
                    && endsWithReturn(ifStmt.elseBody());
        }
        return false;
    }

    private static List<ConceptStmt> renameVariablesInStmts(
            List<ConceptStmt> stmts,
            Map<String, String> renameMap) {
        List<ConceptStmt> out = new ArrayList<>(stmts.size());
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.Assign assign) {
                out.add(new ConceptStmt.Assign(
                        renameMap.getOrDefault(assign.name(), assign.name()),
                        assign.type(),
                        renameVariablesInExpr(assign.value(), renameMap)));
            } else if (s instanceof ConceptStmt.If ifStmt) {
                out.add(new ConceptStmt.If(
                        renameVariablesInExpr(ifStmt.condition(), renameMap),
                        renameVariablesInStmts(ifStmt.thenBody(), renameMap),
                        renameVariablesInStmts(ifStmt.elseBody(), renameMap)));
            } else if (s instanceof ConceptStmt.While whileStmt) {
                out.add(new ConceptStmt.While(
                        renameVariablesInExpr(whileStmt.condition(), renameMap),
                        renameVariablesInStmts(whileStmt.body(), renameMap)));
            } else if (s instanceof ConceptStmt.Return ret) {
                out.add(new ConceptStmt.Return(
                        ret.value() != null ? renameVariablesInExpr(ret.value(), renameMap) : null));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private static ConceptExpr renameVariablesInExpr(ConceptExpr expr, Map<String, String> renameMap) {
        if (expr instanceof ConceptExpr.Var v) {
            return new ConceptExpr.Var(renameMap.getOrDefault(v.name(), v.name()), v.type());
        }
        if (expr instanceof ConceptExpr.BinaryOp bin) {
            return new ConceptExpr.BinaryOp(
                    bin.op(),
                    renameVariablesInExpr(bin.left(), renameMap),
                    renameVariablesInExpr(bin.right(), renameMap),
                    bin.type());
        }
        if (expr instanceof ConceptExpr.UnaryOp u) {
            return new ConceptExpr.UnaryOp(
                    u.op(),
                    renameVariablesInExpr(u.operand(), renameMap),
                    u.type());
        }
        if (expr instanceof ConceptExpr.Cast cast) {
            return new ConceptExpr.Cast(
                    cast.targetType(),
                    renameVariablesInExpr(cast.operand(), renameMap));
        }
        if (expr instanceof ConceptExpr.Ternary tern) {
            return new ConceptExpr.Ternary(
                    renameVariablesInExpr(tern.condition(), renameMap),
                    renameVariablesInExpr(tern.thenExpr(), renameMap),
                    renameVariablesInExpr(tern.elseExpr(), renameMap),
                    tern.type());
        }
        if (expr instanceof ConceptExpr.LutLoad load) {
            return new ConceptExpr.LutLoad(
                    load.lutSymbol(),
                    renameVariablesInExpr(load.index(), renameMap),
                    load.elementType());
        }
        if (expr instanceof ConceptExpr.MathIntrinsic math) {
            List<ConceptExpr> args = new ArrayList<>(math.arguments().size());
            for (ConceptExpr a : math.arguments()) {
                args.add(renameVariablesInExpr(a, renameMap));
            }
            return new ConceptExpr.MathIntrinsic(math.kind(), args, math.type());
        }
        if (expr instanceof ConceptExpr.Call call) {
            List<ConceptExpr> args = new ArrayList<>(call.arguments().size());
            for (ConceptExpr a : call.arguments()) {
                args.add(renameVariablesInExpr(a, renameMap));
            }
            return new ConceptExpr.Call(
                    call.ownerClass(),
                    call.methodName(),
                    call.descriptor(),
                    args,
                    call.returnType());
        }
        return expr;
    }

    // -------------------------------------------------------------------------
    // Pass 3: Constant Folding & Single-Use Temporary Copy Propagation
    // -------------------------------------------------------------------------

    private static List<ConceptStmt> foldConstantsInStmts(
            List<ConceptStmt> stmts,
            Map<String, ConceptLut> lutsBySymbol) {
        List<ConceptStmt> out = new ArrayList<>(stmts.size());
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.Assign assign) {
                out.add(new ConceptStmt.Assign(
                        assign.name(),
                        assign.type(),
                        foldConstantsInExpr(assign.value(), lutsBySymbol)));
            } else if (s instanceof ConceptStmt.If ifStmt) {
                ConceptExpr cond = foldConstantsInExpr(ifStmt.condition(), lutsBySymbol);
                if (cond instanceof ConceptExpr.BoolConst bc) {
                    List<ConceptStmt> branch = bc.value() ? ifStmt.thenBody() : ifStmt.elseBody();
                    List<ConceptStmt> folded = foldConstantsInStmts(branch, lutsBySymbol);
                    out.addAll(folded);
                    if (endsWithReturn(folded)) {
                        break;
                    }
                } else {
                    List<ConceptStmt> foldedThen = foldConstantsInStmts(ifStmt.thenBody(), lutsBySymbol);
                    List<ConceptStmt> foldedElse = foldConstantsInStmts(ifStmt.elseBody(), lutsBySymbol);
                    if (foldedThen.isEmpty() && foldedElse.isEmpty()) {
                        continue;
                    }
                    out.add(new ConceptStmt.If(cond, foldedThen, foldedElse));
                    if (endsWithReturn(foldedThen) && !foldedElse.isEmpty() && endsWithReturn(foldedElse)) {
                        break;
                    }
                }
            } else if (s instanceof ConceptStmt.While whileStmt) {
                ConceptExpr cond = foldConstantsInExpr(whileStmt.condition(), lutsBySymbol);
                if (cond instanceof ConceptExpr.BoolConst bc && !bc.value()) {
                    continue;
                }
                out.add(new ConceptStmt.While(
                        cond,
                        foldConstantsInStmts(whileStmt.body(), lutsBySymbol)));
            } else if (s instanceof ConceptStmt.Return ret) {
                out.add(new ConceptStmt.Return(
                        ret.value() != null ? foldConstantsInExpr(ret.value(), lutsBySymbol) : null));
                break;
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private static ConceptExpr foldConstantsInExpr(
            ConceptExpr expr,
            Map<String, ConceptLut> lutsBySymbol) {
        if (expr instanceof ConceptExpr.Cast cast) {
            ConceptExpr inner = foldConstantsInExpr(cast.operand(), lutsBySymbol);
            if (inner.type() == cast.targetType()) {
                return inner;
            }
            ConceptExpr folded = foldConstCast(inner, cast.targetType());
            if (folded != null) {
                return folded;
            }
            return new ConceptExpr.Cast(cast.targetType(), inner);
        }
        if (expr instanceof ConceptExpr.UnaryOp u) {
            ConceptExpr inner = foldConstantsInExpr(u.operand(), lutsBySymbol);
            ConceptExpr folded = foldConstUnary(u.op(), inner, u.type());
            if (folded != null) {
                return folded;
            }
            return new ConceptExpr.UnaryOp(u.op(), inner, u.type());
        }
        if (expr instanceof ConceptExpr.BinaryOp bin) {
            ConceptExpr l = foldConstantsInExpr(bin.left(), lutsBySymbol);
            ConceptExpr r = foldConstantsInExpr(bin.right(), lutsBySymbol);
            ConceptExpr folded = foldConstBinary(bin.op(), l, r, bin.type());
            if (folded != null) {
                return folded;
            }
            return new ConceptExpr.BinaryOp(bin.op(), l, r, bin.type());
        }
        if (expr instanceof ConceptExpr.Ternary tern) {
            ConceptExpr cond = foldConstantsInExpr(tern.condition(), lutsBySymbol);
            ConceptExpr t = foldConstantsInExpr(tern.thenExpr(), lutsBySymbol);
            ConceptExpr e = foldConstantsInExpr(tern.elseExpr(), lutsBySymbol);
            if (cond instanceof ConceptExpr.BoolConst bc) {
                return bc.value() ? t : e;
            }
            if (t.equals(e)) {
                return t;
            }
            return new ConceptExpr.Ternary(cond, t, e, tern.type());
        }
        if (expr instanceof ConceptExpr.MathIntrinsic math) {
            List<ConceptExpr> args = new ArrayList<>(math.arguments().size());
            for (ConceptExpr a : math.arguments()) {
                args.add(foldConstantsInExpr(a, lutsBySymbol));
            }
            ConceptExpr folded = foldConstMath(math.kind(), args, math.type());
            if (folded != null) {
                return folded;
            }
            return new ConceptExpr.MathIntrinsic(math.kind(), args, math.type());
        }
        if (expr instanceof ConceptExpr.LutLoad load) {
            ConceptExpr idxExpr = foldConstantsInExpr(load.index(), lutsBySymbol);
            if (idxExpr instanceof ConceptExpr.IntConst ic && lutsBySymbol != null) {
                ConceptLut lut = lutsBySymbol.get(load.lutSymbol());
                if (lut != null && ic.value() >= 0 && ic.value() < lut.values().size()) {
                    Number v = lut.values().get(ic.value());
                    return switch (load.elementType()) {
                        case I32 -> new ConceptExpr.IntConst(v.intValue());
                        case I64 -> new ConceptExpr.LongConst(v.longValue());
                        case F32 -> new ConceptExpr.FloatConst(v.floatValue());
                        case F64 -> new ConceptExpr.DoubleConst(v.doubleValue());
                        case BOOL -> new ConceptExpr.BoolConst(v.intValue() != 0);
                        default -> new ConceptExpr.LutLoad(load.lutSymbol(), idxExpr, load.elementType());
                    };
                }
            }
            return new ConceptExpr.LutLoad(load.lutSymbol(), idxExpr, load.elementType());
        }
        if (expr instanceof ConceptExpr.Call call) {
            List<ConceptExpr> args = new ArrayList<>(call.arguments().size());
            for (ConceptExpr a : call.arguments()) {
                args.add(foldConstantsInExpr(a, lutsBySymbol));
            }
            return new ConceptExpr.Call(
                    call.ownerClass(),
                    call.methodName(),
                    call.descriptor(),
                    args,
                    call.returnType());
        }
        return expr;
    }

    private static ConceptExpr foldConstCast(ConceptExpr inner, ConceptType target) {
        if (inner instanceof ConceptExpr.IntConst ic) {
            int v = ic.value();
            return switch (target) {
                case BOOL -> new ConceptExpr.BoolConst(v != 0);
                case I32 -> ic;
                case I64 -> new ConceptExpr.LongConst((long) v);
                case F32 -> new ConceptExpr.FloatConst((float) v);
                case F64 -> new ConceptExpr.DoubleConst((double) v);
                default -> null;
            };
        }
        if (inner instanceof ConceptExpr.LongConst lc) {
            long v = lc.value();
            return switch (target) {
                case BOOL -> new ConceptExpr.BoolConst(v != 0L);
                case I32 -> new ConceptExpr.IntConst((int) v);
                case I64 -> lc;
                case F32 -> new ConceptExpr.FloatConst((float) v);
                case F64 -> new ConceptExpr.DoubleConst((double) v);
                default -> null;
            };
        }
        if (inner instanceof ConceptExpr.FloatConst fc) {
            float v = fc.value();
            return switch (target) {
                case BOOL -> new ConceptExpr.BoolConst(v != 0.0f);
                case I32 -> new ConceptExpr.IntConst((int) v);
                case I64 -> new ConceptExpr.LongConst((long) v);
                case F32 -> fc;
                case F64 -> new ConceptExpr.DoubleConst((double) v);
                default -> null;
            };
        }
        if (inner instanceof ConceptExpr.DoubleConst dc) {
            double v = dc.value();
            return switch (target) {
                case BOOL -> new ConceptExpr.BoolConst(v != 0.0);
                case I32 -> new ConceptExpr.IntConst((int) v);
                case I64 -> new ConceptExpr.LongConst((long) v);
                case F32 -> new ConceptExpr.FloatConst((float) v);
                case F64 -> dc;
                default -> null;
            };
        }
        if (inner instanceof ConceptExpr.BoolConst bc) {
            int v = bc.value() ? 1 : 0;
            return switch (target) {
                case BOOL -> bc;
                case I32 -> new ConceptExpr.IntConst(v);
                case I64 -> new ConceptExpr.LongConst(v);
                case F32 -> new ConceptExpr.FloatConst(v);
                case F64 -> new ConceptExpr.DoubleConst(v);
                default -> null;
            };
        }
        return null;
    }

    private static ConceptExpr foldConstUnary(UnaryOperator op, ConceptExpr inner, ConceptType type) {
        if (op == UnaryOperator.NEG) {
            if (inner instanceof ConceptExpr.IntConst ic) {
                return new ConceptExpr.IntConst(-ic.value());
            }
            if (inner instanceof ConceptExpr.LongConst lc) {
                return new ConceptExpr.LongConst(-lc.value());
            }
            if (inner instanceof ConceptExpr.FloatConst fc) {
                return new ConceptExpr.FloatConst(-fc.value());
            }
            if (inner instanceof ConceptExpr.DoubleConst dc) {
                return new ConceptExpr.DoubleConst(-dc.value());
            }
        } else if (op == UnaryOperator.LOGICAL_NOT) {
            if (inner instanceof ConceptExpr.BoolConst bc) {
                return new ConceptExpr.BoolConst(!bc.value());
            }
            if (inner instanceof ConceptExpr.UnaryOp u2 && u2.op() == UnaryOperator.LOGICAL_NOT) {
                return u2.operand();
            }
        } else if (op == UnaryOperator.BIT_NOT) {
            if (inner instanceof ConceptExpr.IntConst ic) {
                return new ConceptExpr.IntConst(~ic.value());
            }
            if (inner instanceof ConceptExpr.LongConst lc) {
                return new ConceptExpr.LongConst(~lc.value());
            }
        }
        return null;
    }

    private static ConceptExpr foldConstBinary(
            BinaryOperator op,
            ConceptExpr l,
            ConceptExpr r,
            ConceptType resultType) {
        if (op == BinaryOperator.LOGICAL_AND) {
            if (l instanceof ConceptExpr.BoolConst bl) {
                return bl.value() ? r : new ConceptExpr.BoolConst(false);
            }
            if (r instanceof ConceptExpr.BoolConst br) {
                return br.value() ? l : new ConceptExpr.BoolConst(false);
            }
            return null;
        }
        if (op == BinaryOperator.LOGICAL_OR) {
            if (l instanceof ConceptExpr.BoolConst bl) {
                return bl.value() ? new ConceptExpr.BoolConst(true) : r;
            }
            if (r instanceof ConceptExpr.BoolConst br) {
                return br.value() ? new ConceptExpr.BoolConst(true) : l;
            }
            return null;
        }
        // Both sides constant
        if (l instanceof ConceptExpr.BoolConst bl && r instanceof ConceptExpr.BoolConst br) {
            if (op == BinaryOperator.EQ) {
                return new ConceptExpr.BoolConst(bl.value() == br.value());
            }
            if (op == BinaryOperator.NE) {
                return new ConceptExpr.BoolConst(bl.value() != br.value());
            }
            return null;
        }
        if (l instanceof ConceptExpr.IntConst li && r instanceof ConceptExpr.IntConst ri) {
            int a = li.value();
            int b = ri.value();
            if ((op == BinaryOperator.DIV || op == BinaryOperator.REM) && b == 0) {
                return null;
            }
            return switch (op) {
                case ADD -> new ConceptExpr.IntConst(a + b);
                case SUB -> new ConceptExpr.IntConst(a - b);
                case MUL -> new ConceptExpr.IntConst(a * b);
                case DIV -> new ConceptExpr.IntConst(a / b);
                case REM -> new ConceptExpr.IntConst(a % b);
                case BIT_AND -> new ConceptExpr.IntConst(a & b);
                case BIT_OR -> new ConceptExpr.IntConst(a | b);
                case BIT_XOR -> new ConceptExpr.IntConst(a ^ b);
                case SHL -> new ConceptExpr.IntConst(a << b);
                case SHR -> new ConceptExpr.IntConst(a >> b);
                case USHR -> new ConceptExpr.IntConst(a >>> b);
                case EQ -> new ConceptExpr.BoolConst(a == b);
                case NE -> new ConceptExpr.BoolConst(a != b);
                case LT -> new ConceptExpr.BoolConst(a < b);
                case LE -> new ConceptExpr.BoolConst(a <= b);
                case GT -> new ConceptExpr.BoolConst(a > b);
                case GE -> new ConceptExpr.BoolConst(a >= b);
                default -> null;
            };
        }
        if (l instanceof ConceptExpr.LongConst ll && (r instanceof ConceptExpr.LongConst || r instanceof ConceptExpr.IntConst)) {
            long a = ll.value();
            long b = r instanceof ConceptExpr.LongConst rl ? rl.value() : ((ConceptExpr.IntConst) r).value();
            int shift = r instanceof ConceptExpr.IntConst ri ? ri.value() : (int) b;
            if ((op == BinaryOperator.DIV || op == BinaryOperator.REM) && b == 0L) {
                return null;
            }
            return switch (op) {
                case ADD -> new ConceptExpr.LongConst(a + b);
                case SUB -> new ConceptExpr.LongConst(a - b);
                case MUL -> new ConceptExpr.LongConst(a * b);
                case DIV -> new ConceptExpr.LongConst(a / b);
                case REM -> new ConceptExpr.LongConst(a % b);
                case BIT_AND -> new ConceptExpr.LongConst(a & b);
                case BIT_OR -> new ConceptExpr.LongConst(a | b);
                case BIT_XOR -> new ConceptExpr.LongConst(a ^ b);
                case SHL -> new ConceptExpr.LongConst(a << shift);
                case SHR -> new ConceptExpr.LongConst(a >> shift);
                case USHR -> new ConceptExpr.LongConst(a >>> shift);
                case EQ -> new ConceptExpr.BoolConst(a == b);
                case NE -> new ConceptExpr.BoolConst(a != b);
                case LT -> new ConceptExpr.BoolConst(a < b);
                case LE -> new ConceptExpr.BoolConst(a <= b);
                case GT -> new ConceptExpr.BoolConst(a > b);
                case GE -> new ConceptExpr.BoolConst(a >= b);
                default -> null;
            };
        }
        if (l instanceof ConceptExpr.FloatConst lf && r instanceof ConceptExpr.FloatConst rf) {
            float a = lf.value();
            float b = rf.value();
            return switch (op) {
                case ADD -> new ConceptExpr.FloatConst(a + b);
                case SUB -> new ConceptExpr.FloatConst(a - b);
                case MUL -> new ConceptExpr.FloatConst(a * b);
                case DIV -> new ConceptExpr.FloatConst(a / b);
                case REM -> new ConceptExpr.FloatConst(a % b);
                case EQ -> new ConceptExpr.BoolConst(a == b);
                case NE -> new ConceptExpr.BoolConst(a != b);
                case LT -> new ConceptExpr.BoolConst(a < b);
                case LE -> new ConceptExpr.BoolConst(a <= b);
                case GT -> new ConceptExpr.BoolConst(a > b);
                case GE -> new ConceptExpr.BoolConst(a >= b);
                default -> null;
            };
        }
        if (l instanceof ConceptExpr.DoubleConst ld && r instanceof ConceptExpr.DoubleConst rd) {
            double a = ld.value();
            double b = rd.value();
            return switch (op) {
                case ADD -> new ConceptExpr.DoubleConst(a + b);
                case SUB -> new ConceptExpr.DoubleConst(a - b);
                case MUL -> new ConceptExpr.DoubleConst(a * b);
                case DIV -> new ConceptExpr.DoubleConst(a / b);
                case REM -> new ConceptExpr.DoubleConst(a % b);
                case EQ -> new ConceptExpr.BoolConst(a == b);
                case NE -> new ConceptExpr.BoolConst(a != b);
                case LT -> new ConceptExpr.BoolConst(a < b);
                case LE -> new ConceptExpr.BoolConst(a <= b);
                case GT -> new ConceptExpr.BoolConst(a > b);
                case GE -> new ConceptExpr.BoolConst(a >= b);
                default -> null;
            };
        }
        // Safe exact integer algebraic identities (never applied to FP to preserve -0.0/NaN semantics)
        if (resultType == ConceptType.I32 && r instanceof ConceptExpr.IntConst ri) {
            if ((op == BinaryOperator.ADD || op == BinaryOperator.SUB
                    || op == BinaryOperator.BIT_OR || op == BinaryOperator.BIT_XOR)
                    && ri.value() == 0) {
                return l;
            }
            if (op == BinaryOperator.MUL && ri.value() == 1) {
                return l;
            }
        }
        if (resultType == ConceptType.I32 && l instanceof ConceptExpr.IntConst li) {
            if ((op == BinaryOperator.ADD || op == BinaryOperator.BIT_OR || op == BinaryOperator.BIT_XOR)
                    && li.value() == 0) {
                return r;
            }
            if (op == BinaryOperator.MUL && li.value() == 1) {
                return r;
            }
        }
        if (resultType == ConceptType.I64 && r instanceof ConceptExpr.LongConst rl) {
            if ((op == BinaryOperator.ADD || op == BinaryOperator.SUB
                    || op == BinaryOperator.BIT_OR || op == BinaryOperator.BIT_XOR)
                    && rl.value() == 0L) {
                return l;
            }
            if (op == BinaryOperator.MUL && rl.value() == 1L) {
                return l;
            }
        }
        return null;
    }

    private static ConceptExpr foldConstMath(
            MathIntrinsicKind kind,
            List<ConceptExpr> args,
            ConceptType returnType) {
        if (args.isEmpty()) {
            return null;
        }
        for (ConceptExpr a : args) {
            if (!(a instanceof ConceptExpr.IntConst
                    || a instanceof ConceptExpr.LongConst
                    || a instanceof ConceptExpr.FloatConst
                    || a instanceof ConceptExpr.DoubleConst)) {
                return null;
            }
        }
        ConceptExpr a0 = args.get(0);
        ConceptExpr a1 = args.size() > 1 ? args.get(1) : null;
        ConceptExpr a2 = args.size() > 2 ? args.get(2) : null;
        return switch (kind) {
            case FLOOR -> new ConceptExpr.DoubleConst(Math.floor(constAsDouble(a0)));
            case CEIL -> new ConceptExpr.DoubleConst(Math.ceil(constAsDouble(a0)));
            case ROUND -> returnType == ConceptType.I32 || a0 instanceof ConceptExpr.FloatConst
                    ? new ConceptExpr.IntConst(Math.round(constAsFloat(a0)))
                    : new ConceptExpr.LongConst(Math.round(constAsDouble(a0)));
            case SQRT -> new ConceptExpr.DoubleConst(Math.sqrt(constAsDouble(a0)));
            case SIN -> new ConceptExpr.DoubleConst(Math.sin(constAsDouble(a0)));
            case COS -> new ConceptExpr.DoubleConst(Math.cos(constAsDouble(a0)));
            case TAN -> new ConceptExpr.DoubleConst(Math.tan(constAsDouble(a0)));
            case ATAN2 -> a1 != null ? new ConceptExpr.DoubleConst(Math.atan2(constAsDouble(a0), constAsDouble(a1))) : null;
            case EXP -> new ConceptExpr.DoubleConst(Math.exp(constAsDouble(a0)));
            case LOG -> new ConceptExpr.DoubleConst(Math.log(constAsDouble(a0)));
            case POW -> a1 != null ? new ConceptExpr.DoubleConst(Math.pow(constAsDouble(a0), constAsDouble(a1))) : null;
            case FMA -> a1 != null && a2 != null
                    ? (returnType == ConceptType.F32
                            ? new ConceptExpr.FloatConst(Math.fma(constAsFloat(a0), constAsFloat(a1), constAsFloat(a2)))
                            : new ConceptExpr.DoubleConst(Math.fma(constAsDouble(a0), constAsDouble(a1), constAsDouble(a2))))
                    : null;
            case ABS -> switch (returnType) {
                case I32 -> new ConceptExpr.IntConst(Math.abs(constAsInt(a0)));
                case I64 -> new ConceptExpr.LongConst(Math.abs(constAsLong(a0)));
                case F32 -> new ConceptExpr.FloatConst(Math.abs(constAsFloat(a0)));
                default -> new ConceptExpr.DoubleConst(Math.abs(constAsDouble(a0)));
            };
            case MIN -> a1 == null ? null : switch (returnType) {
                case I32 -> new ConceptExpr.IntConst(Math.min(constAsInt(a0), constAsInt(a1)));
                case I64 -> new ConceptExpr.LongConst(Math.min(constAsLong(a0), constAsLong(a1)));
                case F32 -> new ConceptExpr.FloatConst(Math.min(constAsFloat(a0), constAsFloat(a1)));
                default -> new ConceptExpr.DoubleConst(Math.min(constAsDouble(a0), constAsDouble(a1)));
            };
            case MAX -> a1 == null ? null : switch (returnType) {
                case I32 -> new ConceptExpr.IntConst(Math.max(constAsInt(a0), constAsInt(a1)));
                case I64 -> new ConceptExpr.LongConst(Math.max(constAsLong(a0), constAsLong(a1)));
                case F32 -> new ConceptExpr.FloatConst(Math.max(constAsFloat(a0), constAsFloat(a1)));
                default -> new ConceptExpr.DoubleConst(Math.max(constAsDouble(a0), constAsDouble(a1)));
            };
            case IS_NAN -> new ConceptExpr.BoolConst(a0 instanceof ConceptExpr.FloatConst fc
                    ? Float.isNaN(fc.value()) : Double.isNaN(constAsDouble(a0)));
            case IS_FINITE -> new ConceptExpr.BoolConst(a0 instanceof ConceptExpr.FloatConst fc
                    ? Float.isFinite(fc.value()) : Double.isFinite(constAsDouble(a0)));
            case POPCOUNT -> new ConceptExpr.IntConst(a0 instanceof ConceptExpr.LongConst lc
                    ? Long.bitCount(lc.value()) : Integer.bitCount(constAsInt(a0)));
            case CLZ -> new ConceptExpr.IntConst(a0 instanceof ConceptExpr.LongConst lc
                    ? Long.numberOfLeadingZeros(lc.value()) : Integer.numberOfLeadingZeros(constAsInt(a0)));
            case CTZ -> new ConceptExpr.IntConst(a0 instanceof ConceptExpr.LongConst lc
                    ? Long.numberOfTrailingZeros(lc.value()) : Integer.numberOfTrailingZeros(constAsInt(a0)));
        };
    }

    private static int constAsInt(ConceptExpr e) {
        if (e instanceof ConceptExpr.IntConst ic) return ic.value();
        if (e instanceof ConceptExpr.LongConst lc) return (int) lc.value();
        if (e instanceof ConceptExpr.FloatConst fc) return (int) fc.value();
        return (int) ((ConceptExpr.DoubleConst) e).value();
    }

    private static long constAsLong(ConceptExpr e) {
        if (e instanceof ConceptExpr.LongConst lc) return lc.value();
        if (e instanceof ConceptExpr.IntConst ic) return ic.value();
        if (e instanceof ConceptExpr.FloatConst fc) return (long) fc.value();
        return (long) ((ConceptExpr.DoubleConst) e).value();
    }

    private static float constAsFloat(ConceptExpr e) {
        if (e instanceof ConceptExpr.FloatConst fc) return fc.value();
        if (e instanceof ConceptExpr.IntConst ic) return ic.value();
        if (e instanceof ConceptExpr.LongConst lc) return lc.value();
        return (float) ((ConceptExpr.DoubleConst) e).value();
    }

    private static double constAsDouble(ConceptExpr e) {
        if (e instanceof ConceptExpr.DoubleConst dc) return dc.value();
        if (e instanceof ConceptExpr.FloatConst fc) return fc.value();
        if (e instanceof ConceptExpr.IntConst ic) return ic.value();
        return ((ConceptExpr.LongConst) e).value();
    }

    private static List<ConceptStmt> propagateSingleUsePhiTemporaries(List<ConceptStmt> stmts) {
        Map<String, Integer> assignCounts = new HashMap<>();
        Map<String, Integer> readCounts = new HashMap<>();
        countVarAssignsAndReads(stmts, assignCounts, readCounts);

        Map<String, ConceptExpr> inlineableValues = new HashMap<>();
        collectInlineableValues(stmts, assignCounts, readCounts, inlineableValues);
        if (inlineableValues.isEmpty()) {
            return stmts;
        }
        return substituteVariablesInStmts(stmts, inlineableValues);
    }

    private static void collectInlineableValues(
            List<ConceptStmt> stmts,
            Map<String, Integer> assignCounts,
            Map<String, Integer> readCounts,
            Map<String, ConceptExpr> inlineableValues) {
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.Assign assign) {
                String name = assign.name();
                if (assignCounts.getOrDefault(name, 0) != 1) {
                    continue;
                }
                ConceptExpr rhs = assign.value();
                int reads = readCounts.getOrDefault(name, 0);
                boolean isConst = rhs instanceof ConceptExpr.IntConst
                        || rhs instanceof ConceptExpr.LongConst
                        || rhs instanceof ConceptExpr.FloatConst
                        || rhs instanceof ConceptExpr.DoubleConst
                        || rhs instanceof ConceptExpr.BoolConst;
                boolean isDirectVarCopy = rhs instanceof ConceptExpr.Var v
                        && !inlineableValues.containsKey(v.name());
                boolean canInlineCopy = isDirectVarCopy && (reads == 1 || name.startsWith("inl"));
                boolean canInlineCompound = (name.startsWith("_phi_")
                        || name.endsWith("_ret")
                        || name.startsWith("inl"))
                        && reads == 1
                        && exprNodeCount(rhs) <= 24;
                if (isConst || canInlineCopy || canInlineCompound) {
                    Map<String, Integer> rhsReads = new HashMap<>();
                    countReadsInExpr(rhs, rhsReads);
                    boolean rhsImmutable = true;
                    for (String rhsVar : rhsReads.keySet()) {
                        if (assignCounts.getOrDefault(rhsVar, 0) > 1) {
                            rhsImmutable = false;
                            break;
                        }
                    }
                    if (rhsImmutable) {
                        inlineableValues.put(name, rhs);
                    }
                }
            } else if (s instanceof ConceptStmt.If ifStmt) {
                collectInlineableValues(ifStmt.thenBody(), assignCounts, readCounts, inlineableValues);
                collectInlineableValues(ifStmt.elseBody(), assignCounts, readCounts, inlineableValues);
            }
        }
    }

    private static int exprNodeCount(ConceptExpr expr) {
        if (expr instanceof ConceptExpr.BinaryOp b) {
            return 1 + exprNodeCount(b.left()) + exprNodeCount(b.right());
        }
        if (expr instanceof ConceptExpr.UnaryOp u) {
            return 1 + exprNodeCount(u.operand());
        }
        if (expr instanceof ConceptExpr.Cast c) {
            return 1 + exprNodeCount(c.operand());
        }
        if (expr instanceof ConceptExpr.Ternary t) {
            return 1 + exprNodeCount(t.condition()) + exprNodeCount(t.thenExpr()) + exprNodeCount(t.elseExpr());
        }
        if (expr instanceof ConceptExpr.LutLoad l) {
            return 2 + exprNodeCount(l.index());
        }
        if (expr instanceof ConceptExpr.MathIntrinsic m) {
            int sum = 1;
            for (ConceptExpr a : m.arguments()) {
                sum += exprNodeCount(a);
            }
            return sum;
        }
        if (expr instanceof ConceptExpr.Call c) {
            int sum = 4;
            for (ConceptExpr a : c.arguments()) {
                sum += exprNodeCount(a);
            }
            return sum;
        }
        return 1;
    }

    private static void countVarAssignsAndReads(
            List<ConceptStmt> stmts,
            Map<String, Integer> assigns,
            Map<String, Integer> reads) {
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.Assign a) {
                assigns.merge(a.name(), 1, Integer::sum);
                countReadsInExpr(a.value(), reads);
            } else if (s instanceof ConceptStmt.If ifStmt) {
                countReadsInExpr(ifStmt.condition(), reads);
                countVarAssignsAndReads(ifStmt.thenBody(), assigns, reads);
                countVarAssignsAndReads(ifStmt.elseBody(), assigns, reads);
            } else if (s instanceof ConceptStmt.While whileStmt) {
                countReadsInExpr(whileStmt.condition(), reads);
                countVarAssignsAndReads(whileStmt.body(), assigns, reads);
            } else if (s instanceof ConceptStmt.Return ret && ret.value() != null) {
                countReadsInExpr(ret.value(), reads);
            }
        }
    }

    private static void countReadsInExpr(ConceptExpr expr, Map<String, Integer> reads) {
        if (expr instanceof ConceptExpr.Var v) {
            reads.merge(v.name(), 1, Integer::sum);
        } else if (expr instanceof ConceptExpr.BinaryOp bin) {
            countReadsInExpr(bin.left(), reads);
            countReadsInExpr(bin.right(), reads);
        } else if (expr instanceof ConceptExpr.UnaryOp u) {
            countReadsInExpr(u.operand(), reads);
        } else if (expr instanceof ConceptExpr.Cast c) {
            countReadsInExpr(c.operand(), reads);
        } else if (expr instanceof ConceptExpr.Ternary t) {
            countReadsInExpr(t.condition(), reads);
            countReadsInExpr(t.thenExpr(), reads);
            countReadsInExpr(t.elseExpr(), reads);
        } else if (expr instanceof ConceptExpr.LutLoad l) {
            countReadsInExpr(l.index(), reads);
        } else if (expr instanceof ConceptExpr.MathIntrinsic m) {
            for (ConceptExpr a : m.arguments()) {
                countReadsInExpr(a, reads);
            }
        } else if (expr instanceof ConceptExpr.Call c) {
            for (ConceptExpr a : c.arguments()) {
                countReadsInExpr(a, reads);
            }
        }
    }

    private static List<ConceptStmt> substituteVariablesInStmts(
            List<ConceptStmt> stmts,
            Map<String, ConceptExpr> subs) {
        List<ConceptStmt> out = new ArrayList<>(stmts.size());
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.Assign a) {
                if (subs.containsKey(a.name())) {
                    continue;
                }
                out.add(new ConceptStmt.Assign(
                        a.name(),
                        a.type(),
                        substituteVariablesInExpr(a.value(), subs)));
            } else if (s instanceof ConceptStmt.If ifStmt) {
                out.add(new ConceptStmt.If(
                        substituteVariablesInExpr(ifStmt.condition(), subs),
                        substituteVariablesInStmts(ifStmt.thenBody(), subs),
                        substituteVariablesInStmts(ifStmt.elseBody(), subs)));
            } else if (s instanceof ConceptStmt.While whileStmt) {
                out.add(new ConceptStmt.While(
                        substituteVariablesInExpr(whileStmt.condition(), subs),
                        substituteVariablesInStmts(whileStmt.body(), subs)));
            } else if (s instanceof ConceptStmt.Return ret) {
                out.add(new ConceptStmt.Return(
                        ret.value() != null ? substituteVariablesInExpr(ret.value(), subs) : null));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private static ConceptExpr substituteVariablesInExpr(
            ConceptExpr expr,
            Map<String, ConceptExpr> subs) {
        if (expr instanceof ConceptExpr.Var v) {
            ConceptExpr replacement = subs.get(v.name());
            return replacement != null ? substituteVariablesInExpr(replacement, subs) : v;
        }
        if (expr instanceof ConceptExpr.BinaryOp bin) {
            return new ConceptExpr.BinaryOp(
                    bin.op(),
                    substituteVariablesInExpr(bin.left(), subs),
                    substituteVariablesInExpr(bin.right(), subs),
                    bin.type());
        }
        if (expr instanceof ConceptExpr.UnaryOp u) {
            return new ConceptExpr.UnaryOp(
                    u.op(),
                    substituteVariablesInExpr(u.operand(), subs),
                    u.type());
        }
        if (expr instanceof ConceptExpr.Cast c) {
            return new ConceptExpr.Cast(
                    c.targetType(),
                    substituteVariablesInExpr(c.operand(), subs));
        }
        if (expr instanceof ConceptExpr.Ternary t) {
            return new ConceptExpr.Ternary(
                    substituteVariablesInExpr(t.condition(), subs),
                    substituteVariablesInExpr(t.thenExpr(), subs),
                    substituteVariablesInExpr(t.elseExpr(), subs),
                    t.type());
        }
        if (expr instanceof ConceptExpr.LutLoad l) {
            return new ConceptExpr.LutLoad(
                    l.lutSymbol(),
                    substituteVariablesInExpr(l.index(), subs),
                    l.elementType());
        }
        if (expr instanceof ConceptExpr.MathIntrinsic m) {
            List<ConceptExpr> args = new ArrayList<>(m.arguments().size());
            for (ConceptExpr a : m.arguments()) {
                args.add(substituteVariablesInExpr(a, subs));
            }
            return new ConceptExpr.MathIntrinsic(m.kind(), args, m.type());
        }
        if (expr instanceof ConceptExpr.Call c) {
            List<ConceptExpr> args = new ArrayList<>(c.arguments().size());
            for (ConceptExpr a : c.arguments()) {
                args.add(substituteVariablesInExpr(a, subs));
            }
            return new ConceptExpr.Call(
                    c.ownerClass(),
                    c.methodName(),
                    c.descriptor(),
                    args,
                    c.returnType());
        }
        return expr;
    }

    private static List<ConceptStmt> removeDeadAssignments(
            List<ConceptStmt> stmts,
            Set<String> usedVars,
            Set<String> paramNames) {
        List<ConceptStmt> out = new ArrayList<>(stmts.size());
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.Assign a) {
                if (!usedVars.contains(a.name()) && !paramNames.contains(a.name())) {
                    continue;
                }
                out.add(a);
            } else if (s instanceof ConceptStmt.If ifStmt) {
                out.add(new ConceptStmt.If(
                        ifStmt.condition(),
                        removeDeadAssignments(ifStmt.thenBody(), usedVars, paramNames),
                        removeDeadAssignments(ifStmt.elseBody(), usedVars, paramNames)));
            } else if (s instanceof ConceptStmt.While whileStmt) {
                out.add(new ConceptStmt.While(
                        whileStmt.condition(),
                        removeDeadAssignments(whileStmt.body(), usedVars, paramNames)));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private static void collectUsedVariablesInStmts(List<ConceptStmt> stmts, Set<String> used) {
        Map<String, Integer> reads = new HashMap<>();
        countVarAssignsAndReads(stmts, new HashMap<>(), reads);
        used.addAll(reads.keySet());
    }

    private static void collectAssignedAndUsedVariables(List<ConceptStmt> stmts, Set<String> vars) {
        Map<String, Integer> assigns = new HashMap<>();
        Map<String, Integer> reads = new HashMap<>();
        countVarAssignsAndReads(stmts, assigns, reads);
        vars.addAll(assigns.keySet());
        vars.addAll(reads.keySet());
    }

    private static void collectCalledKeysFromStmts(List<ConceptStmt> stmts, Set<String> out) {
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.Assign a) {
                collectCalledKeysFromExpr(a.value(), out);
            } else if (s instanceof ConceptStmt.If ifStmt) {
                collectCalledKeysFromExpr(ifStmt.condition(), out);
                collectCalledKeysFromStmts(ifStmt.thenBody(), out);
                collectCalledKeysFromStmts(ifStmt.elseBody(), out);
            } else if (s instanceof ConceptStmt.While whileStmt) {
                collectCalledKeysFromExpr(whileStmt.condition(), out);
                collectCalledKeysFromStmts(whileStmt.body(), out);
            } else if (s instanceof ConceptStmt.Return ret && ret.value() != null) {
                collectCalledKeysFromExpr(ret.value(), out);
            }
        }
    }

    private static void collectCalledKeysFromExpr(ConceptExpr expr, Set<String> out) {
        if (expr instanceof ConceptExpr.Call c) {
            out.add(c.targetKey());
            out.add(c.methodName());
            for (ConceptExpr a : c.arguments()) {
                collectCalledKeysFromExpr(a, out);
            }
        } else if (expr instanceof ConceptExpr.BinaryOp b) {
            collectCalledKeysFromExpr(b.left(), out);
            collectCalledKeysFromExpr(b.right(), out);
        } else if (expr instanceof ConceptExpr.UnaryOp u) {
            collectCalledKeysFromExpr(u.operand(), out);
        } else if (expr instanceof ConceptExpr.Cast c) {
            collectCalledKeysFromExpr(c.operand(), out);
        } else if (expr instanceof ConceptExpr.Ternary t) {
            collectCalledKeysFromExpr(t.condition(), out);
            collectCalledKeysFromExpr(t.thenExpr(), out);
            collectCalledKeysFromExpr(t.elseExpr(), out);
        } else if (expr instanceof ConceptExpr.LutLoad l) {
            collectCalledKeysFromExpr(l.index(), out);
        } else if (expr instanceof ConceptExpr.MathIntrinsic m) {
            for (ConceptExpr a : m.arguments()) {
                collectCalledKeysFromExpr(a, out);
            }
        }
    }

    private static ConceptKernel3D buildKernel3D(ConceptFunction entry) {
        List<ConceptParam> extraUniforms = new ArrayList<>();
        List<ConceptParam> params = entry.parameters();
        if (params.size() >= 4) {
            for (int i = 3; i < params.size(); i++) {
                extraUniforms.add(params.get(i));
            }
        } else if (params.size() == 2 && params.get(1).type() == ConceptType.I64) {
            extraUniforms.add(params.get(1));
        }
        return new ConceptKernel3D(
                "evaluate_" + entry.name() + "_grid3d",
                entry,
                "out_buffer",
                "origin_x",
                "origin_y",
                "origin_z",
                "step_scale",
                extraUniforms);
    }

    private static ConceptExpr defaultZero(ConceptType type) {
        return switch (type) {
            case BOOL -> new ConceptExpr.BoolConst(false);
            case I32 -> new ConceptExpr.IntConst(0);
            case I64 -> new ConceptExpr.LongConst(0L);
            case F32 -> new ConceptExpr.FloatConst(0.0f);
            default -> new ConceptExpr.DoubleConst(0.0);
        };
    }

    private static List<ConceptStmt> hoistYInvariantStatements(
            List<ConceptStmt> stmts,
            List<ConceptParam> params) {
        if (params == null) {
            return stmts;
        }
        return hoistLoopInvariantAssigns(stmts);
    }

    private static List<ConceptStmt> hoistLoopInvariantAssigns(List<ConceptStmt> stmts) {
        List<ConceptStmt> out = new ArrayList<>(stmts.size());
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.While w) {
                List<ConceptStmt> optBody = hoistLoopInvariantAssigns(w.body());
                Map<String, Integer> bodyAssigns = new HashMap<>();
                countVarAssignsAndReads(optBody, bodyAssigns, new HashMap<>());
                Set<String> loopMutated = new HashSet<>(bodyAssigns.keySet());

                List<ConceptStmt> hoistedBeforeLoop = new ArrayList<>();
                List<ConceptStmt> keptInLoop = new ArrayList<>();
                boolean fixedPoint = true;
                Set<String> hoistedNames = new HashSet<>();
                while (fixedPoint) {
                    fixedPoint = false;
                    for (ConceptStmt bs : optBody) {
                        if (bs instanceof ConceptStmt.Assign a
                                && !hoistedNames.contains(a.name())
                                && bodyAssigns.getOrDefault(a.name(), 0) == 1) {
                            Set<String> reads = new HashSet<>();
                            collectReadsFromExpr(a.value(), reads);
                            boolean dependsOnLoop = false;
                            for (String r : reads) {
                                if (loopMutated.contains(r) && !hoistedNames.contains(r)) {
                                    dependsOnLoop = true;
                                    break;
                                }
                            }
                            if (!dependsOnLoop) {
                                hoistedNames.add(a.name());
                                fixedPoint = true;
                            }
                        }
                    }
                }
                for (ConceptStmt bs : optBody) {
                    if (bs instanceof ConceptStmt.Assign a && hoistedNames.contains(a.name())) {
                        hoistedBeforeLoop.add(a);
                    } else {
                        keptInLoop.add(bs);
                    }
                }
                out.addAll(hoistedBeforeLoop);
                out.add(new ConceptStmt.While(w.condition(), keptInLoop));
            } else if (s instanceof ConceptStmt.If ifStmt) {
                out.add(new ConceptStmt.If(
                        ifStmt.condition(),
                        hoistLoopInvariantAssigns(ifStmt.thenBody()),
                        hoistLoopInvariantAssigns(ifStmt.elseBody())));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private static void collectReadsFromExpr(ConceptExpr expr, Set<String> out) {
        Map<String, Integer> reads = new HashMap<>();
        countReadsInExpr(expr, reads);
        out.addAll(reads.keySet());
    }

    // -------------------------------------------------------------------------
    // Generic Constant LUT Deduplication Pass
    // -------------------------------------------------------------------------

    /**
     * Collapses identical {@link ConceptLut} constant tables by bit-exact content hash and
     * rewrites all {@link ConceptExpr.LutLoad} references across the module to canonical symbols.
     */
    public static ConceptModule deduplicateLookupTables(ConceptModule module) {
        Objects.requireNonNull(module, "module");
        List<ConceptLut> rawLuts = module.lookupTables();
        if (rawLuts.size() <= 1) {
            return module;
        }

        Map<String, String> canonicalByContent = new LinkedHashMap<>();
        Map<String, String> symbolRemap = new HashMap<>();
        List<ConceptLut> dedupLuts = new ArrayList<>(rawLuts.size());

        for (ConceptLut lut : rawLuts) {
            String contentKey = lut.contentKey();
            String existingSymbol = canonicalByContent.get(contentKey);
            if (existingSymbol == null) {
                canonicalByContent.put(contentKey, lut.symbolName());
                dedupLuts.add(lut);
            } else if (!existingSymbol.equals(lut.symbolName())) {
                symbolRemap.put(lut.symbolName(), existingSymbol);
            }
        }

        if (symbolRemap.isEmpty() && dedupLuts.size() == rawLuts.size()) {
            return module;
        }

        List<ConceptFunction> remappedHelpers = new ArrayList<>(module.helperFunctions().size());
        for (ConceptFunction helper : module.helperFunctions()) {
            remappedHelpers.add(remapLutSymbolsInFunction(helper, symbolRemap));
        }

        ConceptFunction remappedEntry = remapLutSymbolsInFunction(module.entryFunction(), symbolRemap);

        ConceptKernel3D remappedKernel = null;
        if (module.kernel3D() != null) {
            ConceptKernel3D k = module.kernel3D();
            ConceptFunction target = k.targetFunction().key().equals(remappedEntry.key())
                    ? remappedEntry
                    : remapLutSymbolsInFunction(k.targetFunction(), symbolRemap);
            remappedKernel = new ConceptKernel3D(
                    k.kernelName(),
                    target,
                    k.outputBufferParam(),
                    k.originXParam(),
                    k.originYParam(),
                    k.originZParam(),
                    k.scaleParam(),
                    k.extraUniformParams());
        }

        BytecodeConceptIr.ConceptWorkgroupPipeline remappedWg = null;
        if (module.workgroupPipeline() != null) {
            BytecodeConceptIr.ConceptWorkgroupPipeline wg = module.workgroupPipeline();
            List<ConceptFunction> remappedStages = new ArrayList<>(wg.stageFunctions().size());
            for (ConceptFunction st : wg.stageFunctions()) {
                remappedStages.add(remapLutSymbolsInFunction(st, symbolRemap));
            }
            remappedWg = new BytecodeConceptIr.ConceptWorkgroupPipeline(
                    wg.kernelName(),
                    wg.workgroupSize(),
                    wg.cornerEvalFunctionName(),
                    wg.localBuffers(),
                    remappedStages);
        }

        return new ConceptModule(
                module.moduleName(),
                dedupLuts,
                remappedHelpers,
                remappedEntry,
                remappedKernel,
                remappedWg,
                module.unmappedReasons(),
                module.liftedMethodSymbols(),
                module.liftedFieldSymbols());
    }

    private static ConceptModule pruneUnusedLookupTables(ConceptModule module) {
        if (module.lookupTables().isEmpty()) {
            return module;
        }
        Set<String> usedLuts = new HashSet<>();
        collectReferencedLutsInStmts(module.entryFunction().body(), usedLuts);
        for (ConceptFunction helper : module.helperFunctions()) {
            collectReferencedLutsInStmts(helper.body(), usedLuts);
        }
        if (module.workgroupPipeline() != null) {
            for (ConceptFunction st : module.workgroupPipeline().stageFunctions()) {
                collectReferencedLutsInStmts(st.body(), usedLuts);
            }
        }
        if (module.kernel3D() != null && module.kernel3D().targetFunction() != null) {
            collectReferencedLutsInStmts(module.kernel3D().targetFunction().body(), usedLuts);
        }
        List<ConceptLut> retained = new ArrayList<>(module.lookupTables().size());
        for (ConceptLut lut : module.lookupTables()) {
            if (usedLuts.contains(lut.symbolName())) {
                retained.add(lut);
            }
        }
        if (retained.size() == module.lookupTables().size()) {
            return module;
        }
        return new ConceptModule(
                module.moduleName(),
                retained,
                module.helperFunctions(),
                module.entryFunction(),
                module.kernel3D(),
                module.workgroupPipeline(),
                module.unmappedReasons(),
                module.liftedMethodSymbols(),
                module.liftedFieldSymbols());
    }

    private static void collectReferencedLutsInStmts(List<ConceptStmt> stmts, Set<String> out) {
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.VarDecl d && d.initializer() != null) {
                collectReferencedLutsInExpr(d.initializer(), out);
            } else if (s instanceof ConceptStmt.Assign a) {
                collectReferencedLutsInExpr(a.value(), out);
            } else if (s instanceof ConceptStmt.If ifStmt) {
                collectReferencedLutsInExpr(ifStmt.condition(), out);
                collectReferencedLutsInStmts(ifStmt.thenBody(), out);
                collectReferencedLutsInStmts(ifStmt.elseBody(), out);
            } else if (s instanceof ConceptStmt.While w) {
                collectReferencedLutsInExpr(w.condition(), out);
                collectReferencedLutsInStmts(w.body(), out);
            } else if (s instanceof ConceptStmt.Return r && r.value() != null) {
                collectReferencedLutsInExpr(r.value(), out);
            } else if (s instanceof ConceptStmt.StoreBuffer3D st) {
                collectReferencedLutsInExpr(st.linearIndex(), out);
                collectReferencedLutsInExpr(st.value(), out);
            }
        }
    }

    private static void collectReferencedLutsInExpr(ConceptExpr expr, Set<String> out) {
        if (expr instanceof ConceptExpr.LutLoad l) {
            out.add(l.lutSymbol());
            collectReferencedLutsInExpr(l.index(), out);
        } else if (expr instanceof ConceptExpr.BinaryOp b) {
            collectReferencedLutsInExpr(b.left(), out);
            collectReferencedLutsInExpr(b.right(), out);
        } else if (expr instanceof ConceptExpr.UnaryOp u) {
            collectReferencedLutsInExpr(u.operand(), out);
        } else if (expr instanceof ConceptExpr.Cast c) {
            collectReferencedLutsInExpr(c.operand(), out);
        } else if (expr instanceof ConceptExpr.Ternary t) {
            collectReferencedLutsInExpr(t.condition(), out);
            collectReferencedLutsInExpr(t.thenExpr(), out);
            collectReferencedLutsInExpr(t.elseExpr(), out);
        } else if (expr instanceof ConceptExpr.MathIntrinsic m) {
            for (ConceptExpr a : m.arguments()) {
                collectReferencedLutsInExpr(a, out);
            }
        } else if (expr instanceof ConceptExpr.Call c) {
            for (ConceptExpr a : c.arguments()) {
                collectReferencedLutsInExpr(a, out);
            }
        } else if (expr instanceof ConceptExpr.CompareTemp cmp) {
            collectReferencedLutsInExpr(cmp.left(), out);
            collectReferencedLutsInExpr(cmp.right(), out);
        }
    }

    private static ConceptFunction remapLutSymbolsInFunction(
            ConceptFunction fn,
            Map<String, String> symbolRemap) {
        return new ConceptFunction(
                fn.ownerClass(),
                fn.name(),
                fn.descriptor(),
                fn.parameters(),
                fn.returnType(),
                fn.localTypes(),
                remapLutSymbolsInStmts(fn.body(), symbolRemap),
                fn.pureCompute());
    }

    private static List<ConceptStmt> remapLutSymbolsInStmts(
            List<ConceptStmt> stmts,
            Map<String, String> symbolRemap) {
        List<ConceptStmt> out = new ArrayList<>(stmts.size());
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.VarDecl decl) {
                out.add(new ConceptStmt.VarDecl(
                        decl.name(),
                        decl.type(),
                        decl.initializer() != null ? remapLutSymbolsInExpr(decl.initializer(), symbolRemap) : null));
            } else if (s instanceof ConceptStmt.Assign assign) {
                out.add(new ConceptStmt.Assign(
                        assign.name(),
                        assign.type(),
                        remapLutSymbolsInExpr(assign.value(), symbolRemap)));
            } else if (s instanceof ConceptStmt.If ifStmt) {
                out.add(new ConceptStmt.If(
                        remapLutSymbolsInExpr(ifStmt.condition(), symbolRemap),
                        remapLutSymbolsInStmts(ifStmt.thenBody(), symbolRemap),
                        remapLutSymbolsInStmts(ifStmt.elseBody(), symbolRemap)));
            } else if (s instanceof ConceptStmt.While whileStmt) {
                out.add(new ConceptStmt.While(
                        remapLutSymbolsInExpr(whileStmt.condition(), symbolRemap),
                        remapLutSymbolsInStmts(whileStmt.body(), symbolRemap)));
            } else if (s instanceof ConceptStmt.Return ret) {
                out.add(new ConceptStmt.Return(
                        ret.value() != null ? remapLutSymbolsInExpr(ret.value(), symbolRemap) : null));
            } else if (s instanceof ConceptStmt.StoreBuffer3D store) {
                out.add(new ConceptStmt.StoreBuffer3D(
                        store.bufferName(),
                        remapLutSymbolsInExpr(store.linearIndex(), symbolRemap),
                        remapLutSymbolsInExpr(store.value(), symbolRemap)));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private static ConceptExpr remapLutSymbolsInExpr(
            ConceptExpr expr,
            Map<String, String> symbolRemap) {
        if (expr instanceof ConceptExpr.LutLoad lut) {
            String canonical = symbolRemap.getOrDefault(lut.lutSymbol(), lut.lutSymbol());
            return new ConceptExpr.LutLoad(
                    canonical,
                    remapLutSymbolsInExpr(lut.index(), symbolRemap),
                    lut.elementType());
        }
        if (expr instanceof ConceptExpr.BinaryOp b) {
            return new ConceptExpr.BinaryOp(
                    b.op(),
                    remapLutSymbolsInExpr(b.left(), symbolRemap),
                    remapLutSymbolsInExpr(b.right(), symbolRemap),
                    b.type());
        }
        if (expr instanceof ConceptExpr.UnaryOp u) {
            return new ConceptExpr.UnaryOp(
                    u.op(),
                    remapLutSymbolsInExpr(u.operand(), symbolRemap),
                    u.type());
        }
        if (expr instanceof ConceptExpr.Cast c) {
            return new ConceptExpr.Cast(
                    c.targetType(),
                    remapLutSymbolsInExpr(c.operand(), symbolRemap));
        }
        if (expr instanceof ConceptExpr.Ternary t) {
            return new ConceptExpr.Ternary(
                    remapLutSymbolsInExpr(t.condition(), symbolRemap),
                    remapLutSymbolsInExpr(t.thenExpr(), symbolRemap),
                    remapLutSymbolsInExpr(t.elseExpr(), symbolRemap),
                    t.type());
        }
        if (expr instanceof ConceptExpr.MathIntrinsic m) {
            List<ConceptExpr> args = new ArrayList<>(m.arguments().size());
            for (ConceptExpr a : m.arguments()) {
                args.add(remapLutSymbolsInExpr(a, symbolRemap));
            }
            return new ConceptExpr.MathIntrinsic(m.kind(), args, m.type());
        }
        if (expr instanceof ConceptExpr.Call c) {
            List<ConceptExpr> args = new ArrayList<>(c.arguments().size());
            for (ConceptExpr a : c.arguments()) {
                args.add(remapLutSymbolsInExpr(a, symbolRemap));
            }
            return new ConceptExpr.Call(
                    c.ownerClass(),
                    c.methodName(),
                    c.descriptor(),
                    args,
                    c.returnType());
        }
        return expr;
    }

    // -------------------------------------------------------------------------
    // Generic Batched 3D Evaluation Spec Promotion
    // -------------------------------------------------------------------------

    /**
     * Derives a target-independent {@link ConceptBatchSpec} from a {@link ConceptModule} and
     * {@link BatchLayout} so both CPU and GPU emitters lower identical batch kernels.
     */
    public static ConceptBatchSpec buildBatchSpec(ConceptModule module, BatchLayout layout) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(layout, "layout");
        ConceptFunction target = module.kernel3D() != null
                ? module.kernel3D().targetFunction()
                : module.entryFunction();
        List<ConceptParam> extraUniforms = module.kernel3D() != null
                ? module.kernel3D().extraUniformParams()
                : buildKernel3D(target).extraUniformParams();

        return switch (layout) {
            case SINGLE_POINT -> new ConceptBatchSpec(
                    BatchLayout.SINGLE_POINT,
                    module.kernel3D() != null
                            ? module.kernel3D().kernelName()
                            : "evaluate_" + target.name() + "_grid3d",
                    target,
                    1,
                    1,
                    1,
                    0,
                    extraUniforms);
            case CORNER_GRID_4X8X4 -> new ConceptBatchSpec(
                    BatchLayout.CORNER_GRID_4X8X4,
                    "evaluate_" + target.name() + "_corner_grid_4x8x4",
                    target,
                    4,
                    8,
                    4,
                    0,
                    extraUniforms);
            case SECTION_4096_VOXEL -> new ConceptBatchSpec(
                    BatchLayout.SECTION_4096_VOXEL,
                    "evaluate_" + target.name() + "_section_4096",
                    target,
                    1,
                    1,
                    1,
                    4096,
                    extraUniforms);
            case SECTION_4096_LCA_NIBBLE_PACKED -> new ConceptBatchSpec(
                    BatchLayout.SECTION_4096_LCA_NIBBLE_PACKED,
                    "evaluate_lca_packed_batch3d",
                    target,
                    1,
                    1,
                    1,
                    4096,
                    extraUniforms);
            case CORNER_SLICE_5X49_PACKED -> new ConceptBatchSpec(
                    BatchLayout.CORNER_SLICE_5X49_PACKED,
                    "evaluate_lca_packed_batch3d",
                    target,
                    4,
                    8,
                    4,
                    245,
                    extraUniforms);
        };
    }

    // -------------------------------------------------------------------------
    // Generic INT64 Q32.32 + FP32 Lowering Analysis
    // -------------------------------------------------------------------------

    /**
     * Converts a finite IEEE-754 {@code double} constant into a signed 64-bit Q32.32 fixed-point
     * integer ({@code round(value * 2^32)}).
     */
    public static long doubleToQ32(double value) {
        if (!Double.isFinite(value)) {
            return 0L;
        }
        return Math.round(value * 4294967296.0);
    }

    /**
     * Target-independent analysis result partitioning {@link ConceptType#F64} variables,
     * parameters, helper return values, and lookup tables into 64-bit Q32.32 fixed-point
     * coordinate chains versus native 32-bit FP32 fractional/interpolation computations.
     */
    public record Q32LoweringPlan(
            Map<String, Set<String>> q32VarsByFunctionKey,
            Map<String, Set<Integer>> q32ParamIndicesByFunctionKey,
            Set<String> q32ReturningFunctionKeys,
            Map<String, String> q32LutSymbolByOriginal,
            List<ConceptLut> synthesizedQ32Luts,
            Set<String> fp32ReferencedLutSymbols
    ) {
        public static Q32LoweringPlan empty() {
            return new Q32LoweringPlan(
                    Map.of(),
                    Map.of(),
                    Set.of(),
                    Map.of(),
                    List.of(),
                    Set.of());
        }

        public boolean hasAnyQ32() {
            if (!q32ReturningFunctionKeys.isEmpty() || !synthesizedQ32Luts.isEmpty()) {
                return true;
            }
            for (Set<String> vars : q32VarsByFunctionKey.values()) {
                if (!vars.isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        public boolean isQ32Var(ConceptFunction fn, String varName) {
            if (fn == null || varName == null) {
                return false;
            }
            Set<String> vars = q32VarsByFunctionKey.get(fn.key());
            return vars != null && vars.contains(varName);
        }

        public boolean isQ32Param(ConceptFunction fn, int paramIndex) {
            if (fn == null) {
                return false;
            }
            Set<Integer> indices = q32ParamIndicesByFunctionKey.get(fn.key());
            return indices != null && indices.contains(paramIndex);
        }

        public boolean isQ32Return(ConceptFunction fn) {
            return fn != null && q32ReturningFunctionKeys.contains(fn.key());
        }

        public String q32LutSymbol(String originalLutSymbol) {
            return q32LutSymbolByOriginal.get(originalLutSymbol);
        }

        public boolean isFp32ReferencedLut(String originalLutSymbol) {
            return fp32ReferencedLutSymbols.contains(originalLutSymbol);
        }

        public boolean isQ32Call(ConceptExpr.Call call) {
            return q32ReturningFunctionKeys.contains(call.targetKey());
        }

        /**
         * Returns {@code true} if {@code expr} naturally produces a Q32.32 {@code long} value
         * under this lowering plan.
         */
        public boolean isQ32Expr(ConceptFunction fn, ConceptExpr expr) {
            if (expr == null) {
                return false;
            }
            if (expr instanceof ConceptExpr.Var v) {
                return v.type() == ConceptType.F64 && isQ32Var(fn, v.name());
            }
            if (expr instanceof ConceptExpr.MathIntrinsic m) {
                if ((m.kind() == MathIntrinsicKind.FLOOR
                        || m.kind() == MathIntrinsicKind.CEIL
                        || m.kind() == MathIntrinsicKind.ROUND)
                        && m.type() == ConceptType.F64
                        && !m.arguments().isEmpty()
                        && m.arguments().get(0).type() == ConceptType.F64) {
                    return true;
                }
                if ((m.kind() == MathIntrinsicKind.ABS
                        || m.kind() == MathIntrinsicKind.MIN
                        || m.kind() == MathIntrinsicKind.MAX
                        || m.kind() == MathIntrinsicKind.FMA)
                        && m.type() == ConceptType.F64) {
                    for (ConceptExpr arg : m.arguments()) {
                        if (isQ32Expr(fn, arg)) {
                            return true;
                        }
                    }
                }
                return false;
            }
            if (expr instanceof ConceptExpr.UnaryOp u) {
                return u.type() == ConceptType.F64
                        && u.op() == UnaryOperator.NEG
                        && isQ32Expr(fn, u.operand());
            }
            if (expr instanceof ConceptExpr.BinaryOp b) {
                if (b.type() != ConceptType.F64) {
                    return false;
                }
                if (b.op() == BinaryOperator.ADD
                        || b.op() == BinaryOperator.SUB
                        || b.op() == BinaryOperator.MUL
                        || b.op() == BinaryOperator.DIV) {
                    return isQ32Expr(fn, b.left()) || isQ32Expr(fn, b.right());
                }
                return false;
            }
            if (expr instanceof ConceptExpr.Ternary t) {
                return t.type() == ConceptType.F64
                        && (isQ32Expr(fn, t.thenExpr()) || isQ32Expr(fn, t.elseExpr()));
            }
            if (expr instanceof ConceptExpr.Call c) {
                return c.returnType() == ConceptType.F64 && isQ32Call(c);
            }
            return false;
        }

        /**
         * Returns {@code true} when an {@code F64} {@code ADD}/{@code SUB} expression evaluated
         * in an FP32 context should perform exact 64-bit Q32 subtraction/addition before
         * narrowing to {@code float} (e.g. {@code sx - floor(sx)} or {@code sx - (double) ix}).
         */
        public boolean shouldPromoteAddSubToQ32(ConceptFunction fn, ConceptExpr.BinaryOp bin) {
            if (bin.type() != ConceptType.F64) {
                return false;
            }
            if (bin.op() != BinaryOperator.SUB && bin.op() != BinaryOperator.ADD) {
                return false;
            }
            boolean leftQ32 = isQ32Expr(fn, bin.left());
            boolean rightQ32 = isQ32Expr(fn, bin.right());
            if (!leftQ32 && !rightQ32) {
                return false;
            }
            ConceptExpr other = leftQ32 ? bin.right() : bin.left();
            return isQ32Expr(fn, other) || isCheapExactQ32Operand(other);
        }

        /**
         * Returns {@code true} when a relational/equality comparison involves at least one Q32
         * coordinate value and should compare exact 64-bit Q32 values.
         */
        public boolean shouldPromoteComparisonToQ32(ConceptFunction fn, ConceptExpr.BinaryOp bin) {
            if (!bin.op().isComparison()) {
                return false;
            }
            if (bin.op() == BinaryOperator.LOGICAL_AND || bin.op() == BinaryOperator.LOGICAL_OR) {
                return false;
            }
            return isQ32Expr(fn, bin.left()) || isQ32Expr(fn, bin.right());
        }

        private static boolean isCheapExactQ32Operand(ConceptExpr expr) {
            if (expr instanceof ConceptExpr.DoubleConst
                    || expr instanceof ConceptExpr.FloatConst
                    || expr instanceof ConceptExpr.IntConst
                    || expr instanceof ConceptExpr.LongConst) {
                return true;
            }
            if (expr instanceof ConceptExpr.LutLoad lut) {
                return lut.elementType() == ConceptType.F64 || lut.elementType() == ConceptType.F32;
            }
            if (expr instanceof ConceptExpr.Cast c) {
                return c.targetType() == ConceptType.F64
                        && (c.operand().type() == ConceptType.I32 || c.operand().type() == ConceptType.I64);
            }
            if (expr instanceof ConceptExpr.UnaryOp u && u.op() == UnaryOperator.NEG) {
                return isCheapExactQ32Operand(u.operand());
            }
            return false;
        }
    }

    /**
     * Performs whole-module demand-driven dataflow analysis to identify large-coordinate
     * {@code F64} floor/wrap/integer-extraction chains for {@code INT64_Q32_FP32} lowering while
     * keeping bounded fractional interpolation and octave blending in native FP32.
     */
    public static Q32LoweringPlan analyzeQ32Lowering(ConceptModule module) {
        Objects.requireNonNull(module, "module");

        List<ConceptFunction> allFunctions = new ArrayList<>();
        allFunctions.addAll(module.helperFunctions());
        allFunctions.add(module.entryFunction());
        if (module.workgroupPipeline() != null) {
            allFunctions.addAll(module.workgroupPipeline().stageFunctions());
        }

        Map<String, ConceptFunction> fnByKey = new LinkedHashMap<>();
        Map<String, ConceptFunction> fnByName = new LinkedHashMap<>();
        Map<String, Set<String>> q32VarsByFn = new LinkedHashMap<>();
        Map<String, Set<Integer>> q32ParamsByFn = new LinkedHashMap<>();
        Set<String> q32ReturningFnKeys = new LinkedHashSet<>();

        for (ConceptFunction fn : allFunctions) {
            fnByKey.put(fn.key(), fn);
            fnByName.putIfAbsent(fn.name(), fn);
            q32VarsByFn.put(fn.key(), new LinkedHashSet<>());
            q32ParamsByFn.put(fn.key(), new LinkedHashSet<>());
        }

        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 64) {
            changed = false;
            for (ConceptFunction fn : allFunctions) {
                Set<String> q32Vars = q32VarsByFn.get(fn.key());
                if (seedAndPropagateQ32InStmts(
                        fn,
                        fn.body(),
                        q32Vars,
                        q32ParamsByFn,
                        q32ReturningFnKeys,
                        fnByKey,
                        fnByName)) {
                    changed = true;
                }
                Set<Integer> paramIdx = q32ParamsByFn.get(fn.key());
                List<ConceptParam> params = fn.parameters();
                for (int i = 0; i < params.size(); i++) {
                    ConceptParam p = params.get(i);
                    if (p.type() == ConceptType.F64 && q32Vars.contains(p.name())) {
                        if (paramIdx.add(i)) {
                            changed = true;
                        }
                    }
                }
            }
        }

        // Freeze preliminary plan to classify F64 LUT loads accurately across Q32 vs FP32 contexts.
        Map<String, Set<String>> frozenVars = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : q32VarsByFn.entrySet()) {
            frozenVars.put(e.getKey(), Set.copyOf(e.getValue()));
        }
        Map<String, Set<Integer>> frozenParams = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Integer>> e : q32ParamsByFn.entrySet()) {
            frozenParams.put(e.getKey(), Set.copyOf(e.getValue()));
        }
        Set<String> frozenReturns = Set.copyOf(q32ReturningFnKeys);

        Q32LoweringPlan preliminary = new Q32LoweringPlan(
                frozenVars,
                frozenParams,
                frozenReturns,
                Map.of(),
                List.of(),
                Set.of());

        Set<String> q32LutSymbols = new LinkedHashSet<>();
        Set<String> fp32LutSymbols = new LinkedHashSet<>();
        for (ConceptFunction fn : allFunctions) {
            classifyLutUsageInStmts(
                    fn,
                    fn.body(),
                    preliminary,
                    fnByKey,
                    fnByName,
                    q32LutSymbols,
                    fp32LutSymbols);
        }

        Map<String, String> q32LutSymbolByOriginal = new LinkedHashMap<>();
        Map<String, String> canonicalQ32ByContent = new LinkedHashMap<>();
        List<ConceptLut> synthesizedQ32Luts = new ArrayList<>();

        for (ConceptLut lut : module.lookupTables()) {
            if (lut.elementType() == ConceptType.F64 && q32LutSymbols.contains(lut.symbolName())) {
                String candidateSymbol = lut.symbolName() + "_q32";
                ConceptLut scaled = lut.toQ32ScaledLut(candidateSymbol);
                String contentKey = scaled.contentKey();
                String existing = canonicalQ32ByContent.get(contentKey);
                if (existing == null) {
                    canonicalQ32ByContent.put(contentKey, scaled.symbolName());
                    synthesizedQ32Luts.add(scaled);
                    q32LutSymbolByOriginal.put(lut.symbolName(), scaled.symbolName());
                } else {
                    q32LutSymbolByOriginal.put(lut.symbolName(), existing);
                }
            }
        }

        return new Q32LoweringPlan(
                Collections.unmodifiableMap(frozenVars),
                Collections.unmodifiableMap(frozenParams),
                frozenReturns,
                Collections.unmodifiableMap(q32LutSymbolByOriginal),
                List.copyOf(synthesizedQ32Luts),
                Set.copyOf(fp32LutSymbols));
    }

    private static boolean seedAndPropagateQ32InStmts(
            ConceptFunction fn,
            List<ConceptStmt> stmts,
            Set<String> q32Vars,
            Map<String, Set<Integer>> q32ParamsByFn,
            Set<String> q32ReturningFnKeys,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName) {
        boolean changed = false;
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.VarDecl decl) {
                if (decl.initializer() != null) {
                    if (seedQ32FromSinksInExpr(
                            decl.initializer(), q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                        changed = true;
                    }
                    if (decl.type() == ConceptType.F64 && isFloorCeilRoundF64(decl.initializer())) {
                        if (q32Vars.add(decl.name())) {
                            changed = true;
                        }
                    }
                    if (decl.type() == ConceptType.F64 && q32Vars.contains(decl.name())) {
                        if (propagateQ32DemandBackward(
                                decl.initializer(), q32Vars, q32ReturningFnKeys, fnByKey, fnByName)) {
                            changed = true;
                        }
                    }
                }
            } else if (s instanceof ConceptStmt.Assign assign) {
                if (seedQ32FromSinksInExpr(
                        assign.value(), q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                    changed = true;
                }
                if (assign.type() == ConceptType.F64 && isFloorCeilRoundF64(assign.value())) {
                    if (q32Vars.add(assign.name())) {
                        changed = true;
                    }
                }
                if (assign.type() == ConceptType.F64 && q32Vars.contains(assign.name())) {
                    if (propagateQ32DemandBackward(
                            assign.value(), q32Vars, q32ReturningFnKeys, fnByKey, fnByName)) {
                        changed = true;
                    }
                }
            } else if (s instanceof ConceptStmt.If ifStmt) {
                if (seedQ32FromSinksInExpr(
                        ifStmt.condition(), q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                    changed = true;
                }
                if (seedAndPropagateQ32InStmts(
                        fn, ifStmt.thenBody(), q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                    changed = true;
                }
                if (seedAndPropagateQ32InStmts(
                        fn, ifStmt.elseBody(), q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                    changed = true;
                }
            } else if (s instanceof ConceptStmt.While whileStmt) {
                if (seedQ32FromSinksInExpr(
                        whileStmt.condition(), q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                    changed = true;
                }
                if (seedAndPropagateQ32InStmts(
                        fn, whileStmt.body(), q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                    changed = true;
                }
            } else if (s instanceof ConceptStmt.Return ret && ret.value() != null) {
                if (seedQ32FromSinksInExpr(
                        ret.value(), q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                    changed = true;
                }
                if (q32ReturningFnKeys.contains(fn.key()) && fn.returnType() == ConceptType.F64) {
                    if (propagateQ32DemandBackward(
                            ret.value(), q32Vars, q32ReturningFnKeys, fnByKey, fnByName)) {
                        changed = true;
                    }
                }
            } else if (s instanceof ConceptStmt.StoreBuffer3D store) {
                if (seedQ32FromSinksInExpr(
                        store.linearIndex(), q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                    changed = true;
                }
                if (seedQ32FromSinksInExpr(
                        store.value(), q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static boolean isFloorCeilRoundF64(ConceptExpr expr) {
        return expr instanceof ConceptExpr.MathIntrinsic m
                && m.type() == ConceptType.F64
                && (m.kind() == MathIntrinsicKind.FLOOR
                        || m.kind() == MathIntrinsicKind.CEIL
                        || m.kind() == MathIntrinsicKind.ROUND)
                && !m.arguments().isEmpty()
                && m.arguments().get(0).type() == ConceptType.F64;
    }

    private static boolean seedQ32FromSinksInExpr(
            ConceptExpr expr,
            Set<String> q32Vars,
            Map<String, Set<Integer>> q32ParamsByFn,
            Set<String> q32ReturningFnKeys,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName) {
        boolean changed = false;
        if (expr instanceof ConceptExpr.MathIntrinsic m) {
            if ((m.kind() == MathIntrinsicKind.FLOOR
                    || m.kind() == MathIntrinsicKind.CEIL
                    || m.kind() == MathIntrinsicKind.ROUND)
                    && !m.arguments().isEmpty()
                    && m.arguments().get(0).type() == ConceptType.F64) {
                if (propagateQ32DemandBackward(
                        m.arguments().get(0), q32Vars, q32ReturningFnKeys, fnByKey, fnByName)) {
                    changed = true;
                }
            }
            for (ConceptExpr a : m.arguments()) {
                if (seedQ32FromSinksInExpr(a, q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                    changed = true;
                }
            }
        } else if (expr instanceof ConceptExpr.Cast c) {
            if ((c.targetType() == ConceptType.I32 || c.targetType() == ConceptType.I64)
                    && c.operand().type() == ConceptType.F64) {
                if (propagateQ32DemandBackward(
                        c.operand(), q32Vars, q32ReturningFnKeys, fnByKey, fnByName)) {
                    changed = true;
                }
            }
            if (seedQ32FromSinksInExpr(c.operand(), q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                changed = true;
            }
        } else if (expr instanceof ConceptExpr.Call c) {
            ConceptFunction target = resolveTargetFunction(c, fnByKey, fnByName);
            if (target != null) {
                Set<Integer> q32Params = q32ParamsByFn.getOrDefault(target.key(), Set.of());
                for (int i = 0; i < c.arguments().size(); i++) {
                    if (q32Params.contains(i)) {
                        if (propagateQ32DemandBackward(
                                c.arguments().get(i), q32Vars, q32ReturningFnKeys, fnByKey, fnByName)) {
                            changed = true;
                        }
                    }
                }
            }
            for (ConceptExpr a : c.arguments()) {
                if (seedQ32FromSinksInExpr(a, q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                    changed = true;
                }
            }
        } else if (expr instanceof ConceptExpr.BinaryOp b) {
            if (seedQ32FromSinksInExpr(b.left(), q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                changed = true;
            }
            if (seedQ32FromSinksInExpr(b.right(), q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                changed = true;
            }
        } else if (expr instanceof ConceptExpr.UnaryOp u) {
            if (seedQ32FromSinksInExpr(u.operand(), q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                changed = true;
            }
        } else if (expr instanceof ConceptExpr.Ternary t) {
            if (seedQ32FromSinksInExpr(t.condition(), q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                changed = true;
            }
            if (seedQ32FromSinksInExpr(t.thenExpr(), q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                changed = true;
            }
            if (seedQ32FromSinksInExpr(t.elseExpr(), q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                changed = true;
            }
        } else if (expr instanceof ConceptExpr.LutLoad l) {
            if (seedQ32FromSinksInExpr(l.index(), q32Vars, q32ParamsByFn, q32ReturningFnKeys, fnByKey, fnByName)) {
                changed = true;
            }
        }
        return changed;
    }

    private static boolean propagateQ32DemandBackward(
            ConceptExpr expr,
            Set<String> q32Vars,
            Set<String> q32ReturningFnKeys,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName) {
        if (expr instanceof ConceptExpr.Var v) {
            return v.type() == ConceptType.F64 && q32Vars.add(v.name());
        }
        if (expr instanceof ConceptExpr.BinaryOp b) {
            if (b.type() == ConceptType.F64
                    && (b.op() == BinaryOperator.ADD
                            || b.op() == BinaryOperator.SUB
                            || b.op() == BinaryOperator.MUL
                            || b.op() == BinaryOperator.DIV)) {
                boolean l = propagateQ32DemandBackward(b.left(), q32Vars, q32ReturningFnKeys, fnByKey, fnByName);
                boolean r = propagateQ32DemandBackward(b.right(), q32Vars, q32ReturningFnKeys, fnByKey, fnByName);
                return l || r;
            }
            return false;
        }
        if (expr instanceof ConceptExpr.UnaryOp u) {
            if (u.type() == ConceptType.F64 && u.op() == UnaryOperator.NEG) {
                return propagateQ32DemandBackward(u.operand(), q32Vars, q32ReturningFnKeys, fnByKey, fnByName);
            }
            return false;
        }
        if (expr instanceof ConceptExpr.Ternary t) {
            if (t.type() == ConceptType.F64) {
                boolean l = propagateQ32DemandBackward(t.thenExpr(), q32Vars, q32ReturningFnKeys, fnByKey, fnByName);
                boolean r = propagateQ32DemandBackward(t.elseExpr(), q32Vars, q32ReturningFnKeys, fnByKey, fnByName);
                return l || r;
            }
            return false;
        }
        if (expr instanceof ConceptExpr.MathIntrinsic m) {
            if (m.type() == ConceptType.F64
                    && (m.kind() == MathIntrinsicKind.FLOOR
                            || m.kind() == MathIntrinsicKind.CEIL
                            || m.kind() == MathIntrinsicKind.ROUND
                            || m.kind() == MathIntrinsicKind.ABS
                            || m.kind() == MathIntrinsicKind.MIN
                            || m.kind() == MathIntrinsicKind.MAX
                            || m.kind() == MathIntrinsicKind.FMA)) {
                boolean changed = false;
                for (ConceptExpr a : m.arguments()) {
                    if (propagateQ32DemandBackward(a, q32Vars, q32ReturningFnKeys, fnByKey, fnByName)) {
                        changed = true;
                    }
                }
                return changed;
            }
            return false;
        }
        if (expr instanceof ConceptExpr.Call c && c.returnType() == ConceptType.F64 && q32ReturningFnKeys != null) {
            ConceptFunction target = resolveTargetFunction(c, fnByKey, fnByName);
            if (target != null) {
                return q32ReturningFnKeys.add(target.key());
            }
        }
        return false;
    }

    private static void classifyLutUsageInStmts(
            ConceptFunction fn,
            List<ConceptStmt> stmts,
            Q32LoweringPlan plan,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName,
            Set<String> q32Luts,
            Set<String> fp32Luts) {
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.VarDecl decl && decl.initializer() != null) {
                boolean q32Target = decl.type() == ConceptType.F64 && plan.isQ32Var(fn, decl.name());
                classifyLutUsageInExpr(fn, decl.initializer(), q32Target, plan, fnByKey, fnByName, q32Luts, fp32Luts);
            } else if (s instanceof ConceptStmt.Assign assign) {
                boolean q32Target = assign.type() == ConceptType.F64 && plan.isQ32Var(fn, assign.name());
                classifyLutUsageInExpr(fn, assign.value(), q32Target, plan, fnByKey, fnByName, q32Luts, fp32Luts);
            } else if (s instanceof ConceptStmt.If ifStmt) {
                classifyLutUsageInExpr(fn, ifStmt.condition(), false, plan, fnByKey, fnByName, q32Luts, fp32Luts);
                classifyLutUsageInStmts(fn, ifStmt.thenBody(), plan, fnByKey, fnByName, q32Luts, fp32Luts);
                classifyLutUsageInStmts(fn, ifStmt.elseBody(), plan, fnByKey, fnByName, q32Luts, fp32Luts);
            } else if (s instanceof ConceptStmt.While whileStmt) {
                classifyLutUsageInExpr(fn, whileStmt.condition(), false, plan, fnByKey, fnByName, q32Luts, fp32Luts);
                classifyLutUsageInStmts(fn, whileStmt.body(), plan, fnByKey, fnByName, q32Luts, fp32Luts);
            } else if (s instanceof ConceptStmt.Return ret && ret.value() != null) {
                boolean q32Target = fn.returnType() == ConceptType.F64 && plan.isQ32Return(fn);
                classifyLutUsageInExpr(fn, ret.value(), q32Target, plan, fnByKey, fnByName, q32Luts, fp32Luts);
            } else if (s instanceof ConceptStmt.StoreBuffer3D store) {
                classifyLutUsageInExpr(fn, store.linearIndex(), false, plan, fnByKey, fnByName, q32Luts, fp32Luts);
                classifyLutUsageInExpr(fn, store.value(), false, plan, fnByKey, fnByName, q32Luts, fp32Luts);
            }
        }
    }

    private static void classifyLutUsageInExpr(
            ConceptFunction fn,
            ConceptExpr expr,
            boolean expectQ32,
            Q32LoweringPlan plan,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName,
            Set<String> q32Luts,
            Set<String> fp32Luts) {
        if (expr instanceof ConceptExpr.LutLoad lut) {
            classifyLutUsageInExpr(fn, lut.index(), false, plan, fnByKey, fnByName, q32Luts, fp32Luts);
            if (lut.elementType() == ConceptType.F64) {
                if (expectQ32) {
                    q32Luts.add(lut.lutSymbol());
                } else {
                    fp32Luts.add(lut.lutSymbol());
                }
            }
        } else if (expr instanceof ConceptExpr.BinaryOp b) {
            boolean childQ32;
            if (b.op().isComparison()) {
                childQ32 = plan.shouldPromoteComparisonToQ32(fn, b);
            } else if (expectQ32
                    && (b.op() == BinaryOperator.ADD
                            || b.op() == BinaryOperator.SUB
                            || b.op() == BinaryOperator.MUL
                            || b.op() == BinaryOperator.DIV)) {
                childQ32 = true;
            } else {
                childQ32 = plan.shouldPromoteAddSubToQ32(fn, b);
            }
            classifyLutUsageInExpr(fn, b.left(), childQ32, plan, fnByKey, fnByName, q32Luts, fp32Luts);
            classifyLutUsageInExpr(fn, b.right(), childQ32, plan, fnByKey, fnByName, q32Luts, fp32Luts);
        } else if (expr instanceof ConceptExpr.UnaryOp u) {
            boolean childQ32 = expectQ32 && u.op() == UnaryOperator.NEG && u.type() == ConceptType.F64;
            classifyLutUsageInExpr(fn, u.operand(), childQ32, plan, fnByKey, fnByName, q32Luts, fp32Luts);
        } else if (expr instanceof ConceptExpr.Cast c) {
            boolean childQ32 = (c.targetType() == ConceptType.I32 || c.targetType() == ConceptType.I64)
                    && c.operand().type() == ConceptType.F64;
            classifyLutUsageInExpr(fn, c.operand(), childQ32, plan, fnByKey, fnByName, q32Luts, fp32Luts);
        } else if (expr instanceof ConceptExpr.Ternary t) {
            classifyLutUsageInExpr(fn, t.condition(), false, plan, fnByKey, fnByName, q32Luts, fp32Luts);
            boolean branchQ32 = expectQ32 && t.type() == ConceptType.F64;
            classifyLutUsageInExpr(fn, t.thenExpr(), branchQ32, plan, fnByKey, fnByName, q32Luts, fp32Luts);
            classifyLutUsageInExpr(fn, t.elseExpr(), branchQ32, plan, fnByKey, fnByName, q32Luts, fp32Luts);
        } else if (expr instanceof ConceptExpr.MathIntrinsic m) {
            boolean argQ32 = switch (m.kind()) {
                case FLOOR, CEIL, ROUND -> !m.arguments().isEmpty() && m.arguments().get(0).type() == ConceptType.F64;
                case ABS, MIN, MAX, FMA -> expectQ32 && m.type() == ConceptType.F64;
                default -> false;
            };
            for (ConceptExpr a : m.arguments()) {
                classifyLutUsageInExpr(fn, a, argQ32, plan, fnByKey, fnByName, q32Luts, fp32Luts);
            }
        } else if (expr instanceof ConceptExpr.Call c) {
            ConceptFunction target = resolveTargetFunction(c, fnByKey, fnByName);
            for (int i = 0; i < c.arguments().size(); i++) {
                boolean argQ32 = target != null && plan.isQ32Param(target, i);
                classifyLutUsageInExpr(fn, c.arguments().get(i), argQ32, plan, fnByKey, fnByName, q32Luts, fp32Luts);
            }
        }
    }

    private static ConceptFunction resolveTargetFunction(
            ConceptExpr.Call call,
            Map<String, ConceptFunction> fnByKey,
            Map<String, ConceptFunction> fnByName) {
        ConceptFunction fn = fnByKey.get(call.targetKey());
        if (fn != null) {
            return fn;
        }
        return fnByName.get(call.methodName());
    }
}
