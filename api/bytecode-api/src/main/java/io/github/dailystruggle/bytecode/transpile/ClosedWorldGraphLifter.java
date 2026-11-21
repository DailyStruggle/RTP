package io.github.dailystruggle.bytecode.transpile;

import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.BinaryOperator;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptExpr;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptFunction;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptLut;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptModule;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptParam;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptStmt;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptType;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.UnaryOperator;
import io.github.dailystruggle.bytecode.transpile.BytecodeSsaLifter.BasicBlock;
import io.github.dailystruggle.bytecode.transpile.BytecodeSsaLifter.BootstrapMethodEntry;
import io.github.dailystruggle.bytecode.transpile.BytecodeSsaLifter.ContextParamBinding;
import io.github.dailystruggle.bytecode.transpile.BytecodeSsaLifter.FieldRef;
import io.github.dailystruggle.bytecode.transpile.BytecodeSsaLifter.GraphLiftOptions;
import io.github.dailystruggle.bytecode.transpile.BytecodeSsaLifter.InstanceNode;
import io.github.dailystruggle.bytecode.transpile.BytecodeSsaLifter.InstanceSnapshot;
import io.github.dailystruggle.bytecode.transpile.BytecodeSsaLifter.InvokeDynamicEntry;
import io.github.dailystruggle.bytecode.transpile.BytecodeSsaLifter.LocalVarEntry;
import io.github.dailystruggle.bytecode.transpile.BytecodeSsaLifter.MethodHandleEntry;
import io.github.dailystruggle.bytecode.transpile.BytecodeSsaLifter.MethodRef;
import io.github.dailystruggle.bytecode.transpile.BytecodeSsaLifter.ObjectInterner;
import io.github.dailystruggle.bytecode.transpile.BytecodeSsaLifter.RawClassFile;
import io.github.dailystruggle.bytecode.transpile.BytecodeSsaLifter.RawMethod;
import io.github.dailystruggle.bytecode.transpile.BytecodeSsaLifter.RawMethodRef;
import io.github.dailystruggle.bytecode.transpile.BytecodeSsaLifter.UnmappedReason;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;

/**
 * Closed-world live object-graph bytecode lifter operating strictly on JVM classfile semantics and
 * structural {@link InstanceSnapshot} nodes.
 *
 * <p>Devirtualizes virtual and interface dispatch on snapshotted field receivers, erases pure
 * structural delegator wrappers by bytecode shape, lowers homogeneous {@code Record[]}/{@code Object[]}
 * arrays to Structure-of-Arrays (SoA) lookup tables, unrolls bounded constant-length
 * {@code List}/{@code Object[]} loops, and fails closed with exact {@link UnmappedReason}
 * diagnostics on any impure side effect, native call, or unresolved dynamic dispatch.
 *
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy corpus-atlas-ADR-002
 * @trace REQ-CFG-ATLAS-001
 * @trace REQ-RTP-S-004
 */
final class ClosedWorldGraphLifter {

    private ClosedWorldGraphLifter() {
    }

    static ConceptModule liftInstanceGraph(
            InstanceSnapshot snapshot,
            String rootClassBinaryOrInternalName,
            String entryMethodName,
            String entryDescriptor,
            GraphLiftOptions options) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(rootClassBinaryOrInternalName, "rootClassBinaryOrInternalName");
        Objects.requireNonNull(entryMethodName, "entryMethodName");
        GraphLiftOptions effOptions = options != null ? options : GraphLiftOptions.defaults();

        GraphLiftContext ctx = new GraphLiftContext(snapshot, effOptions);
        int rootId = snapshot.rootInstanceId();
        InstanceNode rootNode = snapshot.instance(rootId);
        String rootInternal = rootNode != null
                ? rootNode.internalClassName()
                : rootClassBinaryOrInternalName.replace('.', '/');

        RawClassFile rootCls = ctx.resolveClass(rootInternal);
        if (rootCls == null) {
            UnmappedReason missing = new UnmappedReason(
                    rootInternal.replace('/', '.'),
                    entryMethodName,
                    0,
                    0,
                    "missing root class bytes");
            ConceptFunction stub = new ConceptFunction(
                    rootInternal,
                    entryMethodName,
                    entryDescriptor != null ? entryDescriptor : "()V",
                    List.of(),
                    entryDescriptor != null ? BytecodeSsaLifter.parseReturnType(entryDescriptor) : ConceptType.VOID,
                    Map.of(),
                    List.of(),
                    false);
            return new ConceptModule(
                    BytecodeSsaLifter.simpleClassName(rootInternal) + "_" + entryMethodName,
                    List.of(),
                    List.of(),
                    stub,
                    null,
                    null,
                    List.of(missing));
        }

        RawMethodRef entryRef = BytecodeSsaLifter.findAnyMethodOwnerInHierarchy(
                ctx.classesByName, rootCls, entryMethodName, entryDescriptor);
        if (entryRef == null) {
            throw new IllegalArgumentException(
                    "Entry method not found: " + rootInternal + "#" + entryMethodName
                            + (entryDescriptor != null ? entryDescriptor : ""));
        }

        RawMethod entryMethod = entryRef.method();
        String actualDesc = entryMethod.descriptor();
        List<String> rawParamDescs = splitMethodParamDescriptors(actualDesc);
        String rawRetDesc = returnDescriptorSuffix(actualDesc);
        ConceptType liftedRetType = rawRetDesc.startsWith("L")
                ? ConceptType.I32
                : BytecodeSsaLifter.parseReturnType(actualDesc);
        if (rawRetDesc.startsWith("L")) {
            ctx.internedReturnDescriptors.add(rawRetDesc);
        }

        if ((entryMethod.accessFlags() & BytecodeSsaLifter.ACC_NATIVE) != 0 || entryMethod.code() == null) {
            ctx.recordUnmapped(
                    entryRef.owner().thisClass,
                    entryMethod.name(),
                    0,
                    0,
                    "native or abstract entry method");
            ConceptFunction stub = new ConceptFunction(
                    rootCls.thisClass,
                    entryMethod.name(),
                    "()" + toJvmReturnDesc(liftedRetType),
                    List.of(),
                    liftedRetType,
                    Map.of(),
                    List.of(),
                    false);
            return new ConceptModule(
                    BytecodeSsaLifter.simpleClassName(rootCls.thisClass) + "_" + entryMethod.name(),
                    List.of(),
                    List.of(),
                    stub,
                    null,
                    null,
                    List.copyOf(ctx.unmappedReasons));
        }

        boolean isStatic = (entryMethod.accessFlags() & BytecodeSsaLifter.ACC_STATIC) != 0;
        SymVal receiver = isStatic
                ? SymVal.NullRef.INSTANCE
                : (rootNode != null ? new SymVal.InstanceRef(rootId) : new SymVal.UncapturedRef("missing root instance"));

        List<ConceptParam> explicitParams = new ArrayList<>();
        List<SymVal> entryArgs = new ArrayList<>(rawParamDescs.size());
        Map<Integer, LinkedHashMap<String, ContextParamBinding>> orderedCtxBindings = new LinkedHashMap<>();
        List<ContextParamBinding> boundCtxDefaults = effOptions.objectInterner().contextParameters(-1);
        if (!boundCtxDefaults.isEmpty()) {
            LinkedHashMap<String, ContextParamBinding> seededBound = new LinkedHashMap<>();
            for (ContextParamBinding b : boundCtxDefaults) {
                seededBound.put(b.paramName(), b);
            }
            orderedCtxBindings.put(-1, seededBound);
        }

        int jvmSlot = isStatic ? 0 : 1;
        for (int i = 0; i < rawParamDescs.size(); i++) {
            String pDesc = rawParamDescs.get(i);
            char prefix = pDesc.charAt(0);
            if (prefix == 'L') {
                Object fixedParamObj = effOptions.objectInterner().fixedEntryParameterInstance(i);
                if (fixedParamObj != null) {
                    int fixedId = ctx.captureLiveObjectOnDemand(fixedParamObj);
                    entryArgs.add(ctx.instanceIdToSymVal(fixedId));
                } else {
                    entryArgs.add(new SymVal.ContextRef(i));
                    LinkedHashMap<String, ContextParamBinding> seeded = new LinkedHashMap<>();
                    for (ContextParamBinding b : effOptions.objectInterner().contextParameters(i)) {
                        seeded.put(b.paramName(), b);
                    }
                    orderedCtxBindings.put(i, seeded);
                }
                jvmSlot += 1;
            } else if (prefix == '[') {
                entryArgs.add(new SymVal.UncapturedRef("array entry parameter p" + i));
                jvmSlot += 1;
            } else {
                ConceptType pType = ConceptType.fromDescriptorPrefix(pDesc);
                Object fixedParamObj = effOptions.objectInterner().fixedEntryParameterInstance(i);
                ConceptExpr fixedConst = fixedParamObj != null
                        ? BytecodeSsaLifter.primitiveToConstExpr(fixedParamObj)
                        : null;
                if (fixedConst != null) {
                    entryArgs.add(new SymVal.Scalar(fixedConst));
                } else {
                    String pName = resolveDebugParamName(entryMethod, jvmSlot, "p" + i);
                    explicitParams.add(new ConceptParam(pName, pType, explicitParams.size()));
                    entryArgs.add(new SymVal.Scalar(new ConceptExpr.Var(pName, pType)));
                }
                jvmSlot += pType.isWideJvmSlot() ? 2 : 1;
            }
        }

        MethodLiftResult liftedEntry = ctx.liftMethodBody(
                rootCls,
                entryRef.owner(),
                entryMethod,
                receiver,
                entryArgs,
                liftedRetType,
                rawRetDesc.startsWith("L"));

        // Merge dynamically discovered context bindings after any caller-seeded ordering
        for (IndexedContextBinding icb : liftedEntry.usedContextBindings()) {
            orderedCtxBindings
                    .computeIfAbsent(icb.entryParamIndex(), k -> new LinkedHashMap<>())
                    .putIfAbsent(icb.binding().paramName(), icb.binding());
        }

        List<ConceptParam> finalEntryParams = new ArrayList<>(explicitParams);
        Set<String> usedParamNames = new HashSet<>();
        for (ConceptParam ep : explicitParams) {
            usedParamNames.add(ep.name());
        }
        for (LinkedHashMap<String, ContextParamBinding> map : orderedCtxBindings.values()) {
            for (ContextParamBinding b : map.values()) {
                if (usedParamNames.add(b.paramName())) {
                    finalEntryParams.add(new ConceptParam(b.paramName(), b.paramType(), finalEntryParams.size()));
                }
            }
        }

        Map<String, ConceptType> entryLocalTypes = new LinkedHashMap<>(liftedEntry.localTypes());
        for (ConceptParam fp : finalEntryParams) {
            entryLocalTypes.remove(fp.name());
        }

        boolean pureModule = liftedEntry.pureCompute() && ctx.unmappedReasons.isEmpty();
        for (ConceptFunction hf : ctx.emittedHelpers.values()) {
            if (!hf.pureCompute()) {
                pureModule = false;
                break;
            }
        }

        String entryFinalDesc = buildFlatJvmDescriptor(finalEntryParams, liftedRetType);
        ConceptFunction entryFunction = new ConceptFunction(
                rootCls.thisClass,
                entryMethod.name(),
                entryFinalDesc,
                finalEntryParams,
                liftedRetType,
                entryLocalTypes,
                liftedEntry.body(),
                pureModule);

        List<ConceptLut> moduleLuts = new ArrayList<>(ctx.referencedLutsBySymbol.values());
        List<ConceptFunction> helpers = new ArrayList<>(ctx.emittedHelpers.values());
        String moduleName = BytecodeSsaLifter.simpleClassName(rootCls.thisClass) + "_" + entryMethod.name();

        return new ConceptModule(
                moduleName,
                moduleLuts,
                helpers,
                entryFunction,
                null,
                null,
                List.copyOf(ctx.unmappedReasons),
                pureModule ? Set.copyOf(ctx.liftedMethodSymbols) : Set.of(),
                pureModule ? Set.copyOf(ctx.liftedFieldSymbols) : Set.of());
    }

    /**
     * Two-phase closed-world object-graph lifter: symbolically executes {@code factoryMethodName}
     * against a read-only context parameter to materialize the compiled evaluator (including
     * {@code invokedynamic} lambdas and local wrapper objects) without hidden-lambda classloading,
     * then lifts {@code evalMethodName} into a single pure {@link ConceptModule}.
     */
    static ConceptModule liftInstanceGraphWithFactory(
            InstanceSnapshot snapshot,
            String rootClassBinaryOrInternalName,
            String factoryMethodName,
            String factoryDescriptor,
            String evalMethodName,
            String evalDescriptor,
            GraphLiftOptions options) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(rootClassBinaryOrInternalName, "rootClassBinaryOrInternalName");
        Objects.requireNonNull(factoryMethodName, "factoryMethodName");
        Objects.requireNonNull(evalMethodName, "evalMethodName");
        GraphLiftOptions effOptions = options != null ? options : GraphLiftOptions.defaults();

        GraphLiftContext ctx = new GraphLiftContext(snapshot, effOptions);
        int rootId = snapshot.rootInstanceId();
        InstanceNode rootNode = snapshot.instance(rootId);
        String rootInternal = rootNode != null
                ? rootNode.internalClassName()
                : rootClassBinaryOrInternalName.replace('.', '/');

        RawClassFile rootCls = ctx.resolveClass(rootInternal);
        if (rootCls == null) {
            UnmappedReason missing = new UnmappedReason(
                    rootInternal.replace('/', '.'),
                    factoryMethodName,
                    0,
                    0,
                    "missing root class bytes");
            ConceptFunction stub = new ConceptFunction(
                    rootInternal,
                    evalMethodName,
                    "()I",
                    List.of(),
                    ConceptType.I32,
                    Map.of(),
                    List.of(),
                    false);
            return new ConceptModule(
                    BytecodeSsaLifter.simpleClassName(rootInternal) + "_" + evalMethodName,
                    List.of(),
                    List.of(),
                    stub,
                    null,
                    null,
                    List.of(missing));
        }

        RawMethodRef factoryRef = BytecodeSsaLifter.findAnyMethodOwnerInHierarchy(
                ctx.classesByName, rootCls, factoryMethodName, factoryDescriptor);
        if (factoryRef == null || factoryRef.method().code() == null) {
            ctx.recordUnmapped(
                    rootCls.thisClass,
                    factoryMethodName,
                    0,
                    0,
                    "factory method not found or abstract: " + factoryMethodName);
            ConceptFunction stub = new ConceptFunction(
                    rootCls.thisClass,
                    evalMethodName,
                    "()I",
                    List.of(),
                    ConceptType.I32,
                    Map.of(),
                    List.of(),
                    false);
            return new ConceptModule(
                    BytecodeSsaLifter.simpleClassName(rootCls.thisClass) + "_" + evalMethodName,
                    List.of(),
                    List.of(),
                    stub,
                    null,
                    null,
                    List.copyOf(ctx.unmappedReasons));
        }

        RawMethod factoryMethod = factoryRef.method();
        List<String> factoryParamDescs = splitMethodParamDescriptors(factoryMethod.descriptor());
        Map<Integer, LinkedHashMap<String, ContextParamBinding>> orderedCtxBindings = new LinkedHashMap<>();
        List<SymVal> factoryArgs = new ArrayList<>(factoryParamDescs.size());
        for (int i = 0; i < factoryParamDescs.size(); i++) {
            String pDesc = factoryParamDescs.get(i);
            if (pDesc.startsWith("L")) {
                Object fixedObj = effOptions.objectInterner().fixedEntryParameterInstance(i);
                if (fixedObj != null) {
                    int fixedId = ctx.captureLiveObjectOnDemand(fixedObj);
                    factoryArgs.add(ctx.instanceIdToSymVal(fixedId));
                } else {
                    factoryArgs.add(new SymVal.ContextRef(i));
                    LinkedHashMap<String, ContextParamBinding> seeded = new LinkedHashMap<>();
                    for (ContextParamBinding b : effOptions.objectInterner().contextParameters(i)) {
                        seeded.put(b.paramName(), b);
                    }
                    orderedCtxBindings.put(i, seeded);
                }
            } else {
                factoryArgs.add(new SymVal.Scalar(
                        BytecodeSsaLifter.defaultConstForType(ConceptType.fromDescriptorPrefix(pDesc))));
            }
        }
        List<ContextParamBinding> boundCtxDefaults = effOptions.objectInterner().contextParameters(-1);
        if (!boundCtxDefaults.isEmpty()) {
            LinkedHashMap<String, ContextParamBinding> seededBound = new LinkedHashMap<>();
            for (ContextParamBinding b : boundCtxDefaults) {
                seededBound.put(b.paramName(), b);
            }
            orderedCtxBindings.put(-1, seededBound);
        }

        SymVal factoryRecv = (factoryMethod.accessFlags() & BytecodeSsaLifter.ACC_STATIC) != 0
                ? SymVal.NullRef.INSTANCE
                : (rootNode != null ? new SymVal.InstanceRef(rootId) : new SymVal.UncapturedRef("missing root instance"));

        // Stage 1: Symbolically execute factory method to materialize evaluator closure
        ctx.recordLiftedMethod(factoryRef.owner().thisClass, factoryMethod.name());
        ctx.recordLiftedMethod(rootCls.thisClass, factoryMethod.name());
        ReturnInterceptor factoryInterceptor = new ReturnInterceptor(null, null, ConceptType.VOID);
        MethodLiftEngine factoryEngine = new MethodLiftEngine(
                ctx,
                rootCls,
                factoryRef.owner(),
                factoryMethod,
                factoryRecv,
                factoryArgs,
                ConceptType.VOID,
                false);
        factoryEngine.activeReturnInterceptor = factoryInterceptor;
        MethodLiftResult factoryRes = factoryEngine.lift();

        if (!factoryRes.pureCompute() || !ctx.unmappedReasons.isEmpty() || factoryInterceptor.objectReturns.isEmpty()) {
            if (ctx.unmappedReasons.isEmpty()) {
                ctx.recordUnmapped(
                        rootCls.thisClass,
                        factoryMethod.name(),
                        0,
                        0,
                        "factory method did not return a symbolic evaluator object");
            }
            ConceptFunction stub = new ConceptFunction(
                    rootCls.thisClass,
                    evalMethodName,
                    "()I",
                    List.of(),
                    ConceptType.I32,
                    Map.of(),
                    List.of(),
                    false);
            return new ConceptModule(
                    BytecodeSsaLifter.simpleClassName(rootCls.thisClass) + "_" + evalMethodName,
                    List.of(),
                    List.of(),
                    stub,
                    null,
                    null,
                    List.copyOf(ctx.unmappedReasons));
        }

        SymVal compiledEval = factoryInterceptor.objectReturns.get(factoryInterceptor.objectReturns.size() - 1);

        // Stage 2: Resolve and lift evalMethodName on compiledEval
        RawClassFile evalConcreteCls;
        RawMethodRef evalRef;
        SymVal evalReceiver;
        List<SymVal> prefixCapturedArgs = List.of();
        if (compiledEval instanceof SymVal.LambdaClosure lc) {
            evalConcreteCls = ctx.resolveClass(lc.targetOwner());
            evalRef = evalConcreteCls != null
                    ? BytecodeSsaLifter.findAnyMethodOwnerInHierarchy(
                            ctx.classesByName, evalConcreteCls, lc.targetMethodName(), lc.targetDescriptor())
                    : null;
            if (evalRef == null) {
                ctx.recordUnmapped(lc.targetOwner(), lc.targetMethodName(), 0, 0, "unresolved lambda evaluator target");
                ConceptFunction stub = new ConceptFunction(
                        rootCls.thisClass, evalMethodName, "()I", List.of(), ConceptType.I32, Map.of(), List.of(), false);
                return new ConceptModule(
                        BytecodeSsaLifter.simpleClassName(rootCls.thisClass) + "_" + evalMethodName,
                        List.of(), List.of(), stub, null, null, List.copyOf(ctx.unmappedReasons));
            }
            boolean isStaticTarget = (evalRef.method().accessFlags() & BytecodeSsaLifter.ACC_STATIC) != 0;
            List<SymVal> cap = new ArrayList<>(lc.capturedArgs());
            evalReceiver = isStaticTarget ? SymVal.NullRef.INSTANCE : cap.remove(0);
            prefixCapturedArgs = cap;
        } else if (compiledEval instanceof SymVal.LocalObjectRef lor) {
            evalConcreteCls = ctx.resolveClass(lor.internalClassName());
            evalRef = evalConcreteCls != null
                    ? BytecodeSsaLifter.findAnyMethodOwnerInHierarchy(
                            ctx.classesByName, evalConcreteCls, evalMethodName, evalDescriptor)
                    : null;
            evalReceiver = lor;
        } else if (compiledEval instanceof SymVal.InstanceRef ir) {
            InstanceNode evalNode = ctx.lookupInstance(ir.instanceId());
            evalConcreteCls = evalNode != null ? ctx.resolveClass(evalNode.internalClassName()) : null;
            evalRef = evalConcreteCls != null
                    ? BytecodeSsaLifter.findAnyMethodOwnerInHierarchy(
                            ctx.classesByName, evalConcreteCls, evalMethodName, evalDescriptor)
                    : null;
            evalReceiver = ir;
        } else {
            ctx.recordUnmapped(
                    rootCls.thisClass,
                    evalMethodName,
                    0,
                    0,
                    "unsupported factory evaluator result: " + compiledEval.getClass().getSimpleName());
            ConceptFunction stub = new ConceptFunction(
                    rootCls.thisClass, evalMethodName, "()I", List.of(), ConceptType.I32, Map.of(), List.of(), false);
            return new ConceptModule(
                    BytecodeSsaLifter.simpleClassName(rootCls.thisClass) + "_" + evalMethodName,
                    List.of(), List.of(), stub, null, null, List.copyOf(ctx.unmappedReasons));
        }

        if (evalConcreteCls == null || evalRef == null || evalRef.method().code() == null) {
            ctx.recordUnmapped(
                    evalConcreteCls != null ? evalConcreteCls.thisClass : rootCls.thisClass,
                    evalMethodName,
                    0,
                    0,
                    "eval method not found or abstract: " + evalMethodName);
            ConceptFunction stub = new ConceptFunction(
                    rootCls.thisClass, evalMethodName, "()I", List.of(), ConceptType.I32, Map.of(), List.of(), false);
            return new ConceptModule(
                    BytecodeSsaLifter.simpleClassName(rootCls.thisClass) + "_" + evalMethodName,
                    List.of(), List.of(), stub, null, null, List.copyOf(ctx.unmappedReasons));
        }

        RawMethod evalMethod = evalRef.method();
        String evalActualDesc = evalMethod.descriptor();
        List<String> evalRawParamDescs = splitMethodParamDescriptors(evalActualDesc);
        String evalRawRetDesc = returnDescriptorSuffix(evalActualDesc);
        ConceptType evalLiftedRetType = evalRawRetDesc.startsWith("L")
                ? ConceptType.I32
                : BytecodeSsaLifter.parseReturnType(evalActualDesc);
        if (evalRawRetDesc.startsWith("L")) {
            ctx.internedReturnDescriptors.add(evalRawRetDesc);
        }

        List<ContextParamBinding> seededPrimaryCtx = orderedCtxBindings.containsKey(0)
                ? new ArrayList<>(orderedCtxBindings.get(0).values())
                : List.of();
        List<ConceptParam> explicitParams = new ArrayList<>();
        List<SymVal> evalArgs = new ArrayList<>(evalRawParamDescs.size());
        evalArgs.addAll(prefixCapturedArgs);

        boolean evalIsStatic = (evalMethod.accessFlags() & BytecodeSsaLifter.ACC_STATIC) != 0;
        int jvmSlot = evalIsStatic ? 0 : 1;
        for (int i = 0; i < prefixCapturedArgs.size() && i < evalRawParamDescs.size(); i++) {
            String pd = evalRawParamDescs.get(i);
            jvmSlot += ("J".equals(pd) || "D".equals(pd)) ? 2 : 1;
        }
        int dynParamIdx = 0;
        for (int i = prefixCapturedArgs.size(); i < evalRawParamDescs.size(); i++) {
            String pDesc = evalRawParamDescs.get(i);
            char prefix = pDesc.charAt(0);
            if (prefix == 'L') {
                evalArgs.add(new SymVal.ContextRef(dynParamIdx));
                jvmSlot += 1;
            } else if (prefix == '[') {
                evalArgs.add(new SymVal.UncapturedRef("array eval parameter p" + dynParamIdx));
                jvmSlot += 1;
            } else {
                ConceptType pType = ConceptType.fromDescriptorPrefix(pDesc);
                String pName;
                if (dynParamIdx < seededPrimaryCtx.size()
                        && seededPrimaryCtx.get(dynParamIdx).paramType() == pType) {
                    pName = seededPrimaryCtx.get(dynParamIdx).paramName();
                } else {
                    pName = resolveDebugParamName(evalMethod, jvmSlot, "p" + dynParamIdx);
                }
                explicitParams.add(new ConceptParam(pName, pType, explicitParams.size()));
                evalArgs.add(new SymVal.Scalar(new ConceptExpr.Var(pName, pType)));
                jvmSlot += pType.isWideJvmSlot() ? 2 : 1;
            }
            dynParamIdx++;
        }

        MethodLiftResult liftedEval = ctx.liftMethodBody(
                evalConcreteCls,
                evalRef.owner(),
                evalMethod,
                evalReceiver,
                evalArgs,
                evalLiftedRetType,
                evalRawRetDesc.startsWith("L"));

        for (IndexedContextBinding icb : factoryRes.usedContextBindings()) {
            orderedCtxBindings
                    .computeIfAbsent(icb.entryParamIndex(), k -> new LinkedHashMap<>())
                    .putIfAbsent(icb.binding().paramName(), icb.binding());
        }
        for (IndexedContextBinding icb : liftedEval.usedContextBindings()) {
            orderedCtxBindings
                    .computeIfAbsent(icb.entryParamIndex(), k -> new LinkedHashMap<>())
                    .putIfAbsent(icb.binding().paramName(), icb.binding());
        }

        List<ConceptParam> finalEntryParams = new ArrayList<>(explicitParams);
        Set<String> usedParamNames = new HashSet<>();
        for (ConceptParam ep : explicitParams) {
            usedParamNames.add(ep.name());
        }
        for (LinkedHashMap<String, ContextParamBinding> map : orderedCtxBindings.values()) {
            for (ContextParamBinding b : map.values()) {
                if (usedParamNames.add(b.paramName())) {
                    finalEntryParams.add(new ConceptParam(b.paramName(), b.paramType(), finalEntryParams.size()));
                }
            }
        }

        Map<String, ConceptType> entryLocalTypes = new LinkedHashMap<>();
        List<ConceptStmt> combinedEntryBody = new ArrayList<>();
        if (!factoryRes.body().isEmpty()) {
            // Preserve factory-phase LocalObjectRef / constant-anchor setup statements referenced by eval
            // (e.g. VerticalGradientCondition's resolved trueAtAndBelowY / falseAtAndAboveY).
            for (ConceptStmt fs : factoryRes.body()) {
                if (!(fs instanceof ConceptStmt.Return)) {
                    combinedEntryBody.add(fs);
                }
            }
            entryLocalTypes.putAll(factoryRes.localTypes());
        }
        entryLocalTypes.putAll(liftedEval.localTypes());
        combinedEntryBody.addAll(liftedEval.body());
        for (ConceptParam fp : finalEntryParams) {
            entryLocalTypes.remove(fp.name());
        }

        boolean pureModule = liftedEval.pureCompute() && ctx.unmappedReasons.isEmpty();
        for (ConceptFunction hf : ctx.emittedHelpers.values()) {
            if (!hf.pureCompute()) {
                pureModule = false;
                break;
            }
        }

        String entryFinalDesc = buildFlatJvmDescriptor(finalEntryParams, evalLiftedRetType);
        ConceptFunction entryFunction = new ConceptFunction(
                rootCls.thisClass,
                evalMethodName,
                entryFinalDesc,
                finalEntryParams,
                evalLiftedRetType,
                entryLocalTypes,
                combinedEntryBody,
                pureModule);

        List<ConceptLut> moduleLuts = new ArrayList<>(ctx.referencedLutsBySymbol.values());
        List<ConceptFunction> helpers = new ArrayList<>(ctx.emittedHelpers.values());
        String moduleName = BytecodeSsaLifter.simpleClassName(rootCls.thisClass) + "_" + evalMethodName;

        return new ConceptModule(
                moduleName,
                moduleLuts,
                helpers,
                entryFunction,
                null,
                null,
                List.copyOf(ctx.unmappedReasons),
                pureModule ? Set.copyOf(ctx.liftedMethodSymbols) : Set.of(),
                pureModule ? Set.copyOf(ctx.liftedFieldSymbols) : Set.of());
    }

    // -------------------------------------------------------------------------
    // Symbolic Value Domain
    // -------------------------------------------------------------------------

    sealed interface SymVal {
        record Scalar(ConceptExpr expr) implements SymVal {
        }

        record BoundDelegateRef(
                int targetInstanceId,
                String targetMethodName,
                String xParamName,
                String yParamNameOrNull,
                String zParamName) implements SymVal {
        }

        record InternedScalar(ConceptExpr expr) implements SymVal {
        }

        record StringConst(String value) implements SymVal {
        }

        record CompareTemp(ConceptExpr left, ConceptExpr right, boolean nanPositive) implements SymVal {
        }

        record InstanceRef(int instanceId) implements SymVal {
        }

        record ContextRef(int entryParamIndex, int backingInstanceId) implements SymVal {
            ContextRef(int entryParamIndex) {
                this(entryParamIndex, -1);
            }
        }

        enum NullRef implements SymVal {
            INSTANCE
        }

        record OptionalVal(boolean present, SymVal payload) implements SymVal {
        }

        record Array1DRef(ConceptLut lut, int length) implements SymVal {
        }

        record Array2DRef(ConceptLut lut, int outerLength, int rowWidth) implements SymVal {
        }

        record Array2DRowRef(ConceptLut lut, int rowWidth, ConceptExpr rowIndex) implements SymVal {
        }

        record CollectionRef(
                int instanceId,
                List<Integer> elementIds,
                Map<String, ConceptLut> soaLuts,
                String elementInternalClass
        ) implements SymVal {
        }

        record SoaElemRef(
                int arrayInstanceId,
                String elementInternalClass,
                Map<String, ConceptLut> soaLuts,
                ConceptExpr indexExpr
        ) implements SymVal {
        }

        record IteratorRef(List<Integer> elementIds, int cursorSlot) implements SymVal {
        }

        record LocalObjectRef(
                int allocId,
                String internalClassName,
                Map<String, SymVal> fields
        ) implements SymVal {
        }

        record LocalArrayRef(
                int allocId,
                ConceptType elemType,
                int length,
                List<String> slotVars
        ) implements SymVal {
        }

        record LocalObjectArrayRef(
                int allocId,
                String elementInternalClass,
                List<SymVal> elements
        ) implements SymVal {
        }

        record LocalListRef(
                int allocId,
                List<SymVal> elements
        ) implements SymVal {
        }

        record LocalIteratorRef(
                List<SymVal> elements,
                int cursorSlot
        ) implements SymVal {
        }

        record LambdaClosure(
                String samMethodName,
                String targetOwner,
                String targetMethodName,
                String targetDescriptor,
                int referenceKind,
                List<SymVal> capturedArgs
        ) implements SymVal {
        }

        record MuxInstanceRef(ConceptExpr indexExpr, List<Integer> elementIds) implements SymVal {
        }

        enum ThreadLocalRef implements SymVal {
            INSTANCE
        }

        enum ColdMemoCacheRef implements SymVal {
            INSTANCE
        }

        record ColdCacheTagVal(ConceptType type) implements SymVal {
        }

        record UncapturedRef(String reason) implements SymVal {
        }
    }

    record IndexedContextBinding(int entryParamIndex, ContextParamBinding binding) {
    }

    record MethodLiftResult(
            List<ConceptStmt> body,
            Map<String, ConceptType> localTypes,
            List<IndexedContextBinding> usedContextBindings,
            boolean pureCompute
    ) {
    }

    record SpecializedHelper(
            ConceptFunction function,
            List<IndexedContextBinding> usedContextBindings,
            boolean returnsInternedObject
    ) {
    }

    private record DelegatorStep(
            SymVal targetReceiver,
            RawClassFile concreteClass,
            RawClassFile methodOwner,
            RawMethod method,
            List<SymVal> args,
            ConceptType outerReturnCast
    ) {
    }

    // -------------------------------------------------------------------------
    // Whole-Graph Lifting Context
    // -------------------------------------------------------------------------

    static final class GraphLiftContext {
        final InstanceSnapshot snapshot;
        final GraphLiftOptions options;
        final ObjectInterner interner;
        final Map<String, RawClassFile> classesByName = new LinkedHashMap<>();
        final Map<String, ConceptLut> discoveredLuts = new LinkedHashMap<>();
        final Map<String, ConceptExpr> discoveredConstants = new LinkedHashMap<>();
        final Set<ClassLoader> activeLoaders = new LinkedHashSet<>();

        final Map<String, ConceptLut> referencedLutsBySymbol = new LinkedHashMap<>();
        final Map<String, SpecializedHelper> helpersBySpecKey = new LinkedHashMap<>();
        final Map<String, ConceptFunction> emittedHelpers = new LinkedHashMap<>();
        final Set<String> activeSpecStack = new HashSet<>();
        final List<UnmappedReason> unmappedReasons = new ArrayList<>();
        final Set<String> liftedMethodSymbols = new LinkedHashSet<>();
        final Set<String> liftedFieldSymbols = new LinkedHashSet<>();
        final BytecodeSsaLifter.SnapshotCollector dynamicCollector;
        final Map<String, SymVal> onDemandStaticCache = new HashMap<>();
        final Map<Integer, ConceptLut> boxedCollectionLuts = new HashMap<>();
        final Map<Integer, ConceptLut> internedArrayLuts = new HashMap<>();
        final Set<String> internedReturnDescriptors = new HashSet<>();
        int nextHelperId = 0;
        int nextLocalAllocId = 0;

        GraphLiftContext(InstanceSnapshot snapshot, GraphLiftOptions options) {
            this.snapshot = snapshot;
            this.options = options;
            this.interner = options.objectInterner();
            this.dynamicCollector = new BytecodeSsaLifter.SnapshotCollector(snapshot, options);
            this.discoveredLuts.putAll(snapshot.instanceLuts());
            this.discoveredConstants.putAll(snapshot.fieldConstants());
            this.activeLoaders.addAll(snapshot.classLoaders());
            ClassLoader ctxCl = Thread.currentThread().getContextClassLoader();
            if (ctxCl != null) {
                this.activeLoaders.add(ctxCl);
            }
            ClassLoader ownCl = ClosedWorldGraphLifter.class.getClassLoader();
            if (ownCl != null) {
                this.activeLoaders.add(ownCl);
            }

            // First register enum/constant classes, then all remaining snapshot classes so <clinit> switch maps see ordinals
            List<RawClassFile> parsedList = new ArrayList<>();
            for (byte[] bytes : snapshot.discoveredClassBytes()) {
                RawClassFile parsed = BytecodeSsaLifter.parseClassBytes(bytes);
                if (parsed != null) {
                    parsedList.add(parsed);
                    this.discoveredConstants.putAll(parsed.fieldConstants);
                }
            }
            for (RawClassFile parsed : parsedList) {
                BytecodeSsaLifter.registerParsedClassForGraph(
                        parsed, classesByName, discoveredLuts, discoveredConstants);
            }
            List<RawClassFile> initial = new ArrayList<>(classesByName.values());
            for (RawClassFile cls : initial) {
                loadHierarchy(cls);
            }
        }

        private void loadHierarchy(RawClassFile cls) {
            if (cls.superClass != null && !BytecodeSsaLifter.isSystemOwner(cls.superClass)) {
                BytecodeSsaLifter.ensureClassLoadedForGraph(
                        cls.superClass, classesByName, discoveredLuts, discoveredConstants, activeLoaders);
            }
            for (String iface : cls.interfaces) {
                if (!BytecodeSsaLifter.isSystemOwner(iface)) {
                    BytecodeSsaLifter.ensureClassLoadedForGraph(
                            iface, classesByName, discoveredLuts, discoveredConstants, activeLoaders);
                }
            }
        }

        RawClassFile resolveClass(String internalName) {
            if (internalName == null) {
                return null;
            }
            RawClassFile existing = classesByName.get(internalName);
            if (existing != null) {
                return existing;
            }
            return BytecodeSsaLifter.ensureClassLoadedForGraph(
                    internalName, classesByName, discoveredLuts, discoveredConstants, activeLoaders);
        }

        void registerLut(ConceptLut lut) {
            if (lut != null) {
                referencedLutsBySymbol.putIfAbsent(lut.symbolName(), lut);
            }
        }

        void recordUnmapped(String internalOrBinaryClass, String method, int pc, int opcode, String detail) {
            String binaryClass = internalOrBinaryClass == null ? "" : internalOrBinaryClass.replace('/', '.');
            UnmappedReason reason = new UnmappedReason(binaryClass, method, pc, opcode & 0xFF, detail);
            if (!unmappedReasons.contains(reason)) {
                unmappedReasons.add(reason);
            }
        }

        void recordLiftedMethod(String internalOrBinaryClass, String methodName) {
            if (internalOrBinaryClass == null || internalOrBinaryClass.isBlank()
                    || methodName == null || methodName.isBlank()) {
                return;
            }
            String bin = internalOrBinaryClass.replace('/', '.');
            if (!bin.startsWith("java.") && !bin.startsWith("jdk.") && !bin.startsWith("sun.")) {
                liftedMethodSymbols.add(bin + "#" + methodName);
            }
        }

        void recordLiftedField(String internalOrBinaryClass, String fieldName) {
            if (internalOrBinaryClass == null || internalOrBinaryClass.isBlank()
                    || fieldName == null || fieldName.isBlank()) {
                return;
            }
            String bin = internalOrBinaryClass.replace('/', '.');
            if (!bin.startsWith("java.") && !bin.startsWith("jdk.") && !bin.startsWith("sun.")) {
                liftedFieldSymbols.add(bin + "#" + fieldName);
            }
        }

        InstanceNode lookupInstance(int instanceId) {
            InstanceNode dyn = dynamicCollector.instancesById.get(instanceId);
            return dyn != null ? dyn : snapshot.instance(instanceId);
        }

        int captureLiveObjectOnDemand(Object obj) {
            if (obj == null) {
                return -1;
            }
            int id = dynamicCollector.captureObject(obj, 0);
            for (Map.Entry<String, byte[]> e : dynamicCollector.classBytesByName.entrySet()) {
                if (!classesByName.containsKey(e.getKey())) {
                    RawClassFile parsed = BytecodeSsaLifter.parseClassBytes(e.getValue());
                    if (parsed != null) {
                        discoveredConstants.putAll(parsed.fieldConstants);
                        BytecodeSsaLifter.registerParsedClassForGraph(
                                parsed, classesByName, discoveredLuts, discoveredConstants);
                        loadHierarchy(parsed);
                    }
                }
            }
            activeLoaders.addAll(dynamicCollector.loaders);
            return id;
        }

        SymVal instanceIdToSymVal(int instanceId) {
            if (instanceId == -1) {
                return SymVal.NullRef.INSTANCE;
            }
            if (instanceId < -1) {
                return new SymVal.UncapturedRef("snapshot depth/instance bound exceeded");
            }
            InstanceNode node = lookupInstance(instanceId);
            if (node == null) {
                return new SymVal.UncapturedRef("uncaptured instance id " + instanceId);
            }
            if (node.internedId() != null) {
                return new SymVal.InternedScalar(new ConceptExpr.IntConst(node.internedId()));
            }
            Object liveObjCheck = dynamicCollector.liveObjectsById.get(instanceId);
            if (liveObjCheck != null && interner.isBoundContextInstance(liveObjCheck)) {
                return new SymVal.ContextRef(-1, instanceId);
            }
            if (node.boxedPrimitiveValue() != null) {
                return new SymVal.Scalar(node.boxedPrimitiveValue());
            }
            if (liveObjCheck instanceof String strVal) {
                return new SymVal.StringConst(strVal);
            }
            if (node.directPrimitiveLut() != null) {
                if (node.directArrayRowWidth() > 0) {
                    return new SymVal.Array2DRef(
                            node.directPrimitiveLut(),
                            node.directArrayLength(),
                            node.directArrayRowWidth());
                }
                return new SymVal.Array1DRef(node.directPrimitiveLut(), node.directArrayLength());
            }
            if (node.collectionOrArray()) {
                String elemClass = inferHomogeneousElementClass(node);
                return new SymVal.CollectionRef(
                        instanceId,
                        node.elementInstanceIds(),
                        node.arrayLuts(),
                        elemClass);
            }
            String clsName = node.internalClassName();
            if ("java/util/Optional".equals(clsName)
                    || "java/util/OptionalDouble".equals(clsName)
                    || "java/util/OptionalInt".equals(clsName)
                    || "java/util/OptionalLong".equals(clsName)) {
                if (!node.optionalPresent()) {
                    return new SymVal.OptionalVal(false, SymVal.NullRef.INSTANCE);
                }
                if (node.optionalPrimitiveValue() != null) {
                    return new SymVal.OptionalVal(true, new SymVal.Scalar(node.optionalPrimitiveValue()));
                }
                if (node.optionalTargetId() != null) {
                    return new SymVal.OptionalVal(true, instanceIdToSymVal(node.optionalTargetId()));
                }
                return new SymVal.OptionalVal(false, SymVal.NullRef.INSTANCE);
            }
            return new SymVal.InstanceRef(instanceId);
        }

        ConceptLut tryBuildInternedElementLut(SymVal.CollectionRef coll) {
            ConceptLut cached = internedArrayLuts.get(coll.instanceId());
            if (cached != null) {
                return cached;
            }
            if (coll.elementIds().isEmpty()) {
                return null;
            }
            List<Number> vals = new ArrayList<>(coll.elementIds().size());
            int nullId = interner.nullObjectId();
            boolean anyInterned = false;
            for (int elemId : coll.elementIds()) {
                if (elemId == -1) {
                    vals.add(nullId);
                    continue;
                }
                InstanceNode en = lookupInstance(elemId);
                if (en == null || en.internedId() == null) {
                    return null;
                }
                vals.add(en.internedId());
                anyInterned = true;
            }
            if (!anyInterned) {
                return null;
            }
            ConceptLut lut = new ConceptLut(
                    "interned_array", "elements", "lut_interned_" + coll.instanceId(), ConceptType.I32, vals);
            internedArrayLuts.put(coll.instanceId(), lut);
            return lut;
        }

        private String inferHomogeneousElementClass(InstanceNode collNode) {
            for (int elemId : collNode.elementInstanceIds()) {
                if (elemId >= 0) {
                    InstanceNode elemNode = lookupInstance(elemId);
                    if (elemNode != null) {
                        return elemNode.internalClassName();
                    }
                }
            }
            for (String key : collNode.arrayLuts().keySet()) {
                int hash = key.indexOf('#');
                if (hash > 0) {
                    return key.substring(0, hash);
                }
            }
            return null;
        }

        SymVal resolveFieldValue(
                SymVal receiver,
                FieldRef fRef,
                String callerClass,
                String callerMethod,
                int pc,
                int opcode) {
            if (receiver instanceof SymVal.InstanceRef ir) {
                InstanceNode node = lookupInstance(ir.instanceId());
                if (node == null) {
                    recordUnmapped(callerClass, callerMethod, pc, opcode, "missing instance " + ir.instanceId());
                    return null;
                }
                return lookupFieldOnNode(node, fRef, callerClass, callerMethod, pc, opcode);
            }
            if (receiver instanceof SymVal.LocalObjectRef lor) {
                recordLiftedField(fRef.owner(), fRef.name());
                recordLiftedField(lor.internalClassName(), fRef.name());
                SymVal v = lor.fields().get(fRef.owner() + "#" + fRef.name());
                if (v == null) {
                    v = lor.fields().get(fRef.name());
                }
                if (v != null) {
                    return v;
                }
                char c0 = fRef.descriptor().charAt(0);
                if (c0 == 'L' || c0 == '[') {
                    return SymVal.NullRef.INSTANCE;
                }
                return new SymVal.Scalar(
                        BytecodeSsaLifter.defaultConstForType(ConceptType.fromDescriptorPrefix(fRef.descriptor())));
            }
            if (receiver instanceof SymVal.ColdMemoCacheRef) {
                return SymVal.ColdMemoCacheRef.INSTANCE;
            }
            if (receiver instanceof SymVal.SoaElemRef soa) {
                ConceptLut lut = lookupLutInMap(soa.soaLuts(), fRef.owner(), fRef.name());
                if (lut == null) {
                    recordUnmapped(
                            callerClass,
                            callerMethod,
                            pc,
                            opcode,
                            "missing SoA LUT for " + fRef.owner() + "#" + fRef.name());
                    return null;
                }
                registerLut(lut);
                ConceptExpr load = evalOrEmitLutLoad(lut, soa.indexExpr());
                ConceptType expected = ConceptType.fromDescriptorPrefix(fRef.descriptor());
                return new SymVal.Scalar(coerceScalar(load, expected));
            }
            if (receiver instanceof SymVal.NullRef) {
                recordUnmapped(callerClass, callerMethod, pc, opcode, "null receiver on getfield " + fRef.name());
                return null;
            }
            if (receiver instanceof SymVal.UncapturedRef ur) {
                recordUnmapped(callerClass, callerMethod, pc, opcode, ur.reason());
                return null;
            }
            recordUnmapped(callerClass, callerMethod, pc, opcode, "non-instance receiver on getfield " + fRef.name());
            return null;
        }

        private SymVal lookupFieldOnNode(
                InstanceNode node,
                FieldRef fRef,
                String callerClass,
                String callerMethod,
                int pc,
                int opcode) {
            String desc = fRef.descriptor();
            char c0 = desc.charAt(0);
            if (c0 != 'L' && c0 != '[') {
                ConceptExpr prim = node.lookupPrimitiveField(fRef.owner(), fRef.name());
                if (prim == null) {
                    recordUnmapped(
                            callerClass,
                            callerMethod,
                            pc,
                            opcode,
                            "uncaptured primitive field " + fRef.owner() + "#" + fRef.name());
                    return null;
                }
                recordLiftedField(fRef.owner(), fRef.name());
                recordLiftedField(node.internalClassName(), fRef.name());
                return new SymVal.Scalar(coerceScalar(prim, ConceptType.fromDescriptorPrefix(desc)));
            }
            ConceptLut arrLut = node.lookupArrayLut(fRef.owner(), fRef.name());
            if (arrLut != null) {
                recordLiftedField(fRef.owner(), fRef.name());
                recordLiftedField(node.internalClassName(), fRef.name());
                Integer rwObj = node.lookupArrayRowWidth(fRef.owner(), fRef.name());
                Integer lenObj = node.lookupArrayLength(fRef.owner(), fRef.name());
                int rw = rwObj != null ? rwObj : 0;
                int len = lenObj != null ? lenObj : arrLut.values().size();
                if (rw > 0) {
                    return new SymVal.Array2DRef(arrLut, len, rw);
                }
                return new SymVal.Array1DRef(arrLut, len);
            }
            Integer childId = node.lookupObjectFieldRef(fRef.owner(), fRef.name());
            if (childId != null) {
                recordLiftedField(fRef.owner(), fRef.name());
                recordLiftedField(node.internalClassName(), fRef.name());
                if (childId == -2) {
                    int expandedId = tryExpandTruncatedFieldOnDemand(node.instanceId(), fRef);
                    if (expandedId >= -1) {
                        return instanceIdToSymVal(expandedId);
                    }
                }
                return instanceIdToSymVal(childId);
            }
            recordUnmapped(
                    callerClass,
                    callerMethod,
                    pc,
                    opcode,
                    "uncaptured object field " + fRef.owner() + "#" + fRef.name());
            return null;
        }

        private int tryExpandTruncatedFieldOnDemand(int parentInstanceId, FieldRef fRef) {
            Object liveParent = dynamicCollector.liveObjectsById.get(parentInstanceId);
            if (liveParent == null) {
                return -2;
            }
            for (Class<?> cur = liveParent.getClass(); cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                for (Field f : cur.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && f.getName().equals(fRef.name())) {
                        try {
                            f.setAccessible(true);
                            Object val = f.get(liveParent);
                            return captureLiveObjectOnDemand(val);
                        } catch (ReflectiveOperationException | RuntimeException ignored) {
                            return -2;
                        }
                    }
                }
            }
            return -2;
        }

        private SymVal tryCaptureStaticFieldLive(FieldRef fRef) {
            String key = fRef.owner() + "#" + fRef.name();
            if (onDemandStaticCache.containsKey(key)) {
                return onDemandStaticCache.get(key);
            }
            String binName = fRef.owner().replace('/', '.');
            for (ClassLoader cl : activeLoaders) {
                try {
                    Class<?> cls = Class.forName(binName, true, cl);
                    for (Class<?> cur = cls; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                        for (Field f : cur.getDeclaredFields()) {
                            if (Modifier.isStatic(f.getModifiers()) && f.getName().equals(fRef.name())) {
                                f.setAccessible(true);
                                Object val = f.get(null);
                                SymVal sym;
                                if (val == null) {
                                    sym = SymVal.NullRef.INSTANCE;
                                } else if (val instanceof ThreadLocal<?>) {
                                    sym = SymVal.ThreadLocalRef.INSTANCE;
                                } else {
                                    String arrSym = "lut_static_" + BytecodeSsaLifter.sanitizeIdentifier(fRef.owner().replace('/', '_') + "_" + fRef.name());
                                    BytecodeSsaLifter.ArrayLutInfo lutInfo =
                                            BytecodeSsaLifter.extractPrimitiveArrayLut(fRef.owner(), fRef.name(), arrSym, val);
                                    if (lutInfo != null) {
                                        sym = lutInfo.rowWidth() > 0
                                                ? new SymVal.Array2DRef(lutInfo.lut(), lutInfo.length(), lutInfo.rowWidth())
                                                : new SymVal.Array1DRef(lutInfo.lut(), lutInfo.length());
                                    } else {
                                        int id = captureLiveObjectOnDemand(val);
                                        sym = instanceIdToSymVal(id);
                                    }
                                }
                                onDemandStaticCache.put(key, sym);
                                return sym;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            return null;
        }

        SymVal resolveStaticField(FieldRef fRef, String callerClass, String callerMethod, int pc) {
            char descC0 = fRef.descriptor().charAt(0);
            RawClassFile ownerCls = resolveClass(fRef.owner());
            if (ownerCls != null) {
                for (FieldRef df : ownerCls.declaredFields) {
                    if (df.name().equals(fRef.name()) && df.descriptor().equals(fRef.descriptor())) {
                        if ((df.accessFlags() & BytecodeSsaLifter.ACC_FINAL) == 0 && descC0 != 'L' && descC0 != '[') {
                            recordUnmapped(
                                    callerClass,
                                    callerMethod,
                                    pc,
                                    0xB2,
                                    "mutable non-final static field " + fRef.owner() + "#" + fRef.name());
                            return null;
                        }
                        break;
                    }
                }
            }

            String key = fRef.owner() + "#" + fRef.name();
            if ("Ljava/lang/ThreadLocal;".equals(fRef.descriptor())) {
                recordLiftedField(fRef.owner(), fRef.name());
                return SymVal.ThreadLocalRef.INSTANCE;
            }
            InstanceNode staticNode = lookupInstance(BytecodeSsaLifter.STATIC_FIELDS_INSTANCE_ID);
            if (staticNode != null) {
                char c0 = fRef.descriptor().charAt(0);
                if (c0 != 'L' && c0 != '[') {
                    ConceptExpr prim = staticNode.primitiveFields().get(key);
                    if (prim != null) {
                        recordLiftedField(fRef.owner(), fRef.name());
                        return new SymVal.Scalar(coerceScalar(prim, ConceptType.fromDescriptorPrefix(fRef.descriptor())));
                    }
                } else {
                    ConceptLut lut = staticNode.arrayLuts().get(key);
                    if (lut != null) {
                        recordLiftedField(fRef.owner(), fRef.name());
                        int rw = staticNode.arrayRowWidths().getOrDefault(key, 0);
                        int len = staticNode.arrayLengths().getOrDefault(key, lut.values().size());
                        return rw > 0 ? new SymVal.Array2DRef(lut, len, rw) : new SymVal.Array1DRef(lut, len);
                    }
                    Integer objId = staticNode.objectFieldRefs().get(key);
                    if (objId != null) {
                        recordLiftedField(fRef.owner(), fRef.name());
                        return instanceIdToSymVal(objId);
                    }
                }
            }

            ConceptExpr discConst = discoveredConstants.get(key);
            if (discConst != null) {
                recordLiftedField(fRef.owner(), fRef.name());
                return new SymVal.Scalar(discConst);
            }
            ConceptLut discLut = fRef.descriptor().startsWith("[[") ? null : discoveredLuts.get(key);
            if (discLut != null) {
                recordLiftedField(fRef.owner(), fRef.name());
                return new SymVal.Array1DRef(discLut, discLut.values().size());
            }

            ConceptExpr jdkConst = resolvePureJdkStaticConstant(fRef.owner(), fRef.name());
            if (jdkConst != null) {
                return new SymVal.Scalar(jdkConst);
            }
            if ("java/lang/Boolean".equals(fRef.owner())) {
                if ("TRUE".equals(fRef.name())) {
                    return new SymVal.Scalar(new ConceptExpr.BoolConst(true));
                }
                if ("FALSE".equals(fRef.name())) {
                    return new SymVal.Scalar(new ConceptExpr.BoolConst(false));
                }
            }

            if (descC0 == 'L' || descC0 == '[') {
                SymVal liveSym = tryCaptureStaticFieldLive(fRef);
                if (liveSym != null && !(liveSym instanceof SymVal.UncapturedRef)) {
                    recordLiftedField(fRef.owner(), fRef.name());
                    return liveSym;
                }
            }

            recordUnmapped(callerClass, callerMethod, pc, 0xB2, "unresolved static field " + key);
            return null;
        }

        MethodLiftResult liftMethodBody(
                RawClassFile concreteReceiverCls,
                RawClassFile methodOwnerCls,
                RawMethod method,
                SymVal receiverVal,
                List<SymVal> args,
                ConceptType expectedReturnType,
                boolean returnsInternedObject) {
            recordLiftedMethod(methodOwnerCls.thisClass, method.name());
            if (concreteReceiverCls != null) {
                recordLiftedMethod(concreteReceiverCls.thisClass, method.name());
            }
            MethodLiftEngine engine = new MethodLiftEngine(
                    this,
                    concreteReceiverCls != null ? concreteReceiverCls : methodOwnerCls,
                    methodOwnerCls,
                    method,
                    receiverVal,
                    args,
                    expectedReturnType,
                    returnsInternedObject);
            return engine.lift();
        }

        SpecializedHelper liftOrReuseHelper(
                RawClassFile concreteCls,
                RawClassFile methodOwner,
                RawMethod method,
                SymVal receiverVal,
                List<SymVal> callArgs,
                String callerClass,
                String callerMethod,
                int callPc,
                int callOpcode) {
            String specKey = buildSpecializationKey(concreteCls, methodOwner, method, receiverVal, callArgs);
            SpecializedHelper cached = helpersBySpecKey.get(specKey);
            if (cached != null) {
                return cached;
            }
            if (activeSpecStack.contains(specKey)) {
                recordUnmapped(callerClass, callerMethod, callPc, callOpcode, "recursive cycle on " + specKey);
                return null;
            }
            if (helpersBySpecKey.size() >= options.maxHelperSpecializations()) {
                recordUnmapped(
                        callerClass,
                        callerMethod,
                        callPc,
                        callOpcode,
                        "exceeded maxHelperSpecializations=" + options.maxHelperSpecializations());
                return null;
            }

            activeSpecStack.add(specKey);
            try {
                boolean isStatic = (method.accessFlags() & BytecodeSsaLifter.ACC_STATIC) != 0;
                List<String> rawParamDescs = splitMethodParamDescriptors(method.descriptor());
                String rawRetDesc = returnDescriptorSuffix(method.descriptor());
                boolean retInternedObj = rawRetDesc.startsWith("L");
                ConceptType liftedRetType = retInternedObj
                        ? ConceptType.I32
                        : BytecodeSsaLifter.parseReturnType(method.descriptor());

                List<ConceptParam> helperParams = new ArrayList<>();
                List<SymVal> calleeArgs = new ArrayList<>(callArgs.size());
                int jvmSlot = isStatic ? 0 : 1;
                for (int i = 0; i < rawParamDescs.size(); i++) {
                    String pDesc = rawParamDescs.get(i);
                    SymVal callerArg = callArgs.get(i);
                    if (callerArg instanceof SymVal.Scalar || callerArg instanceof SymVal.CompareTemp) {
                        ConceptType pType = ConceptType.fromDescriptorPrefix(pDesc);
                        String pName = resolveDebugParamName(method, jvmSlot, "p" + i);
                        pName = ensureUniqueParamName(helperParams, pName);
                        helperParams.add(new ConceptParam(pName, pType, helperParams.size()));
                        calleeArgs.add(new SymVal.Scalar(new ConceptExpr.Var(pName, pType)));
                    } else if (callerArg instanceof SymVal.InternedScalar) {
                        String pName = ensureUniqueParamName(helperParams, resolveDebugParamName(method, jvmSlot, "p" + i));
                        helperParams.add(new ConceptParam(pName, ConceptType.I32, helperParams.size()));
                        calleeArgs.add(new SymVal.InternedScalar(new ConceptExpr.Var(pName, ConceptType.I32)));
                    } else if (callerArg instanceof SymVal.Array2DRowRef row) {
                        String pName = ensureUniqueParamName(helperParams, "_row_idx_" + i);
                        helperParams.add(new ConceptParam(pName, ConceptType.I32, helperParams.size()));
                        calleeArgs.add(new SymVal.Array2DRowRef(
                                row.lut(), row.rowWidth(), new ConceptExpr.Var(pName, ConceptType.I32)));
                    } else if (callerArg instanceof SymVal.SoaElemRef soa) {
                        String pName = ensureUniqueParamName(helperParams, "_soa_idx_" + i);
                        helperParams.add(new ConceptParam(pName, ConceptType.I32, helperParams.size()));
                        calleeArgs.add(new SymVal.SoaElemRef(
                                soa.arrayInstanceId(),
                                soa.elementInternalClass(),
                                soa.soaLuts(),
                                new ConceptExpr.Var(pName, ConceptType.I32)));
                    } else if (callerArg instanceof SymVal.LocalObjectRef lor && isImmutableScalarLocalObject(lor)) {
                        calleeArgs.add(rebuildImmutableLocalObjectParams(lor, "_lo" + i, helperParams));
                    } else {
                        calleeArgs.add(callerArg);
                    }
                    jvmSlot += jvmSlotWidth(pDesc);
                }

                SymVal calleeReceiver = receiverVal;
                if (receiverVal instanceof SymVal.SoaElemRef soaRecv) {
                    String pName = ensureUniqueParamName(helperParams, "_soa_recv_idx");
                    helperParams.add(new ConceptParam(pName, ConceptType.I32, helperParams.size()));
                    calleeReceiver = new SymVal.SoaElemRef(
                            soaRecv.arrayInstanceId(),
                            soaRecv.elementInternalClass(),
                            soaRecv.soaLuts(),
                            new ConceptExpr.Var(pName, ConceptType.I32));
                }

                MethodLiftResult bodyRes = liftMethodBody(
                        concreteCls,
                        methodOwner,
                        method,
                        calleeReceiver,
                        calleeArgs,
                        liftedRetType,
                        retInternedObj);

                List<IndexedContextBinding> uniqueCtx = deduplicateContextBindings(bodyRes.usedContextBindings());
                Set<String> existingNames = new HashSet<>();
                for (ConceptParam hp : helperParams) {
                    existingNames.add(hp.name());
                }
                for (IndexedContextBinding icb : uniqueCtx) {
                    String cName = icb.binding().paramName();
                    if (existingNames.add(cName)) {
                        helperParams.add(new ConceptParam(cName, icb.binding().paramType(), helperParams.size()));
                    }
                }

                Map<String, ConceptType> localTypes = new LinkedHashMap<>(bodyRes.localTypes());
                for (ConceptParam hp : helperParams) {
                    localTypes.remove(hp.name());
                }

                String ownerInternal = concreteCls != null ? concreteCls.thisClass : methodOwner.thisClass;
                String helperName = BytecodeSsaLifter.simpleClassName(ownerInternal)
                        + "_" + BytecodeSsaLifter.sanitizeIdentifier(method.name())
                        + "_g" + (nextHelperId++);
                String helperDesc = buildFlatJvmDescriptor(helperParams, liftedRetType);

                ConceptFunction helperFn = new ConceptFunction(
                        ownerInternal,
                        helperName,
                        helperDesc,
                        helperParams,
                        liftedRetType,
                        localTypes,
                        bodyRes.body(),
                        bodyRes.pureCompute());

                SpecializedHelper sh = new SpecializedHelper(helperFn, uniqueCtx, retInternedObj);
                helpersBySpecKey.put(specKey, sh);
                emittedHelpers.put(ownerInternal + "#" + helperName + helperDesc, helperFn);
                return sh;
            } finally {
                activeSpecStack.remove(specKey);
            }
        }

        private String buildSpecializationKey(
                RawClassFile concreteCls,
                RawClassFile methodOwner,
                RawMethod method,
                SymVal receiver,
                List<SymVal> args) {
            StringBuilder sb = new StringBuilder();
            sb.append(concreteCls != null ? concreteCls.thisClass : methodOwner.thisClass)
                    .append("::")
                    .append(methodOwner.thisClass)
                    .append('#')
                    .append(method.name())
                    .append(method.descriptor())
                    .append("|recv=")
                    .append(symValSpecTag(receiver));
            for (int i = 0; i < args.size(); i++) {
                sb.append("|a").append(i).append('=').append(symValSpecTag(args.get(i)));
            }
            return sb.toString();
        }

        private String symValSpecTag(SymVal val) {
            if (val instanceof SymVal.Scalar s) {
                return "S:" + s.expr().type();
            }
            if (val instanceof SymVal.InternedScalar) {
                return "IS";
            }
            if (val instanceof SymVal.CompareTemp) {
                return "CMP";
            }
            if (val instanceof SymVal.InstanceRef ir) {
                return "I:" + ir.instanceId();
            }
            if (val instanceof SymVal.ContextRef cr) {
                return "CTX:" + cr.entryParamIndex();
            }
            if (val instanceof SymVal.NullRef) {
                return "NULL";
            }
            if (val instanceof SymVal.OptionalVal ov) {
                return ov.present() ? ("OPT1:" + symValSpecTag(ov.payload())) : "OPT0";
            }
            if (val instanceof SymVal.Array1DRef a1) {
                return "A1:" + a1.lut().symbolName();
            }
            if (val instanceof SymVal.Array2DRef a2) {
                return "A2:" + a2.lut().symbolName() + ":" + a2.rowWidth();
            }
            if (val instanceof SymVal.Array2DRowRef r2) {
                return "R2:" + r2.lut().symbolName() + ":" + r2.rowWidth();
            }
            if (val instanceof SymVal.CollectionRef cr) {
                return "COLL:" + cr.instanceId();
            }
            if (val instanceof SymVal.SoaElemRef soa) {
                return "SOA:" + soa.arrayInstanceId() + ":" + soa.elementInternalClass();
            }
            if (val instanceof SymVal.IteratorRef) {
                return "ITER";
            }
            if (val instanceof SymVal.LocalObjectRef lor) {
                if (isImmutableScalarLocalObject(lor)) {
                    List<String> sortedKeys = new ArrayList<>(lor.fields().keySet());
                    Collections.sort(sortedKeys);
                    StringBuilder sb = new StringBuilder("ILO:");
                    sb.append(lor.internalClassName()).append('(');
                    for (int idx = 0; idx < sortedKeys.size(); idx++) {
                        if (idx > 0) {
                            sb.append(',');
                        }
                        String fk = sortedKeys.get(idx);
                        sb.append(fk).append('=').append(symValSpecTag(lor.fields().get(fk)));
                    }
                    sb.append(')');
                    return sb.toString();
                }
                return "LO:" + lor.allocId() + ":" + lor.internalClassName();
            }
            if (val instanceof SymVal.LocalObjectArrayRef loar) {
                return "LOA:" + loar.allocId();
            }
            if (val instanceof SymVal.LocalListRef llr) {
                return "LL:" + llr.allocId();
            }
            if (val instanceof SymVal.LocalIteratorRef) {
                return "LITER";
            }
            if (val instanceof SymVal.LambdaClosure lc) {
                StringBuilder lsb = new StringBuilder("LC:");
                lsb.append(lc.targetOwner()).append('#').append(lc.targetMethodName());
                for (SymVal cap : lc.capturedArgs()) {
                    lsb.append(',').append(symValSpecTag(cap));
                }
                return lsb.toString();
            }
            return "UNCAP";
        }

        boolean isImmutableScalarLocalObject(SymVal.LocalObjectRef lor) {
            return isImmutableScalarLocalObject(lor, 0);
        }

        private boolean isImmutableScalarLocalObject(SymVal.LocalObjectRef lor, int depth) {
            if (lor == null || lor.fields().isEmpty() || depth > 4) {
                return false;
            }
            RawClassFile cls = resolveClass(lor.internalClassName());
            if (cls == null || !"java/lang/Record".equals(cls.superClass)) {
                return false;
            }
            for (SymVal fv : lor.fields().values()) {
                if (fv instanceof SymVal.LocalObjectRef nested) {
                    if (!isImmutableScalarLocalObject(nested, depth + 1)) {
                        return false;
                    }
                } else if (!(fv instanceof SymVal.Scalar
                        || fv instanceof SymVal.CompareTemp
                        || fv instanceof SymVal.InternedScalar
                        || fv instanceof SymVal.InstanceRef
                        || fv instanceof SymVal.ContextRef
                        || fv instanceof SymVal.NullRef)) {
                    return false;
                }
            }
            return true;
        }

        private SymVal.LocalObjectRef rebuildImmutableLocalObjectParams(
                SymVal.LocalObjectRef lor, String prefix, List<ConceptParam> helperParams) {
            List<String> sortedKeys = new ArrayList<>(lor.fields().keySet());
            Collections.sort(sortedKeys);
            Map<String, SymVal> calleeFields = new LinkedHashMap<>();
            for (String fk : sortedKeys) {
                SymVal fv = lor.fields().get(fk);
                String fieldPrefix = prefix + "_" + BytecodeSsaLifter.sanitizeIdentifier(fk);
                if (fv instanceof SymVal.LocalObjectRef nested) {
                    calleeFields.put(fk, rebuildImmutableLocalObjectParams(nested, fieldPrefix, helperParams));
                } else if (fv instanceof SymVal.Scalar sc) {
                    ConceptType fType = sc.expr().type();
                    String pName = ensureUniqueParamName(helperParams, fieldPrefix);
                    helperParams.add(new ConceptParam(pName, fType, helperParams.size()));
                    calleeFields.put(fk, new SymVal.Scalar(new ConceptExpr.Var(pName, fType)));
                } else if (fv instanceof SymVal.CompareTemp) {
                    String pName = ensureUniqueParamName(helperParams, fieldPrefix);
                    helperParams.add(new ConceptParam(pName, ConceptType.I32, helperParams.size()));
                    calleeFields.put(fk, new SymVal.Scalar(new ConceptExpr.Var(pName, ConceptType.I32)));
                } else if (fv instanceof SymVal.InternedScalar) {
                    String pName = ensureUniqueParamName(helperParams, fieldPrefix);
                    helperParams.add(new ConceptParam(pName, ConceptType.I32, helperParams.size()));
                    calleeFields.put(fk, new SymVal.InternedScalar(new ConceptExpr.Var(pName, ConceptType.I32)));
                } else {
                    calleeFields.put(fk, fv);
                }
            }
            return new SymVal.LocalObjectRef(-1, lor.internalClassName(), calleeFields);
        }

        RawMethodRef findMethodByNameAndParamDescriptor(
                RawClassFile startCls, String name, String fullDescriptor) {
            int closeParen = fullDescriptor.indexOf(')');
            if (closeParen < 0) {
                return null;
            }
            String paramPrefix = fullDescriptor.substring(0, closeParen + 1);
            RawClassFile cur = startCls;
            int depth = 0;
            while (cur != null && depth++ < 16) {
                RawMethod matched = null;
                for (RawMethod m : cur.methods) {
                    if (m.name().equals(name)
                            && m.descriptor().startsWith(paramPrefix)
                            && m.descriptor().length() > paramPrefix.length()
                            && m.descriptor().charAt(paramPrefix.length()) == 'L'
                            && m.code() != null) {
                        if (matched != null) {
                            return null;
                        }
                        matched = m;
                    }
                }
                if (matched != null) {
                    return new RawMethodRef(cur, matched);
                }
                cur = cur.superClass != null ? classesByName.get(cur.superClass) : null;
            }
            return null;
        }

        /**
         * Follows pure structural delegator wrapper chains by bytecode shape alone:
         * {@code aload_0 -> (getfield | zeroArgGetter)+ -> load_all_args_identically -> invoke -> return}.
         */
        DelegatorStep unwrapDelegatorChain(
                SymVal initialReceiver,
                RawClassFile initialConcreteCls,
                RawClassFile initialOwner,
                RawMethod initialMethod,
                List<SymVal> initialArgs) {
            SymVal curRecv = initialReceiver;
            RawClassFile curConcrete = initialConcreteCls;
            RawClassFile curOwner = initialOwner;
            RawMethod curMethod = initialMethod;
            List<SymVal> curArgs = initialArgs;
            ConceptType outerCast = null;

            Set<String> visited = new HashSet<>();
            for (int depth = 0; depth < options.maxGraphDepth(); depth++) {
                if (!(curRecv instanceof SymVal.InstanceRef ir)) {
                    break;
                }
                String visitKey = ir.instanceId() + "#" + curOwner.thisClass + "#" + curMethod.name() + curMethod.descriptor();
                if (!visited.add(visitKey)) {
                    break;
                }
                DelegatorStep next = tryMatchSingleDelegator(ir, curOwner, curMethod, curArgs, outerCast);
                if (next == null) {
                    break;
                }
                recordLiftedMethod(curOwner.thisClass, curMethod.name());
                if (curConcrete != null) {
                    recordLiftedMethod(curConcrete.thisClass, curMethod.name());
                }
                curRecv = next.targetReceiver();
                curConcrete = next.concreteClass();
                curOwner = next.methodOwner();
                curMethod = next.method();
                curArgs = next.args();
                outerCast = next.outerReturnCast();
            }
            return new DelegatorStep(curRecv, curConcrete, curOwner, curMethod, curArgs, outerCast);
        }

        private DelegatorStep tryMatchSingleDelegator(
                SymVal.InstanceRef recvRef,
                RawClassFile methodOwner,
                RawMethod method,
                List<SymVal> callArgs,
                ConceptType existingOuterCast) {
            if ((method.accessFlags() & BytecodeSsaLifter.ACC_STATIC) != 0 || method.code() == null) {
                return null;
            }
            byte[] code = method.code();
            int pc = 0;
            if (code.length < 5) {
                return null;
            }
            int op0 = code[0] & 0xFF;
            if (op0 == 0x2A) {
                pc = 1;
            } else if (op0 == 0x19 && (code[1] & 0xFF) == 0) {
                pc = 2;
            } else {
                return null;
            }

            InstanceNode curNode = lookupInstance(recvRef.instanceId());
            if (curNode == null) {
                return null;
            }
            int hops = 0;
            while (pc < code.length) {
                int op = code[pc] & 0xFF;
                if (op == 0x00) {
                    pc += 1;
                } else if (op == 0xC0) {
                    pc += 3;
                } else if (op == 0xB4) {
                    FieldRef fRef = methodOwner.resolveFieldRef(BytecodeSsaLifter.readUnsignedShort(code, pc + 1));
                    Integer childId = curNode.lookupObjectFieldRef(fRef.owner(), fRef.name());
                    if (childId == null) {
                        return null;
                    }
                    if (childId == -2) {
                        childId = tryExpandTruncatedFieldOnDemand(curNode.instanceId(), fRef);
                    }
                    if (childId < 0) {
                        return null;
                    }
                    InstanceNode nextNode = lookupInstance(childId);
                    if (nextNode == null) {
                        return null;
                    }
                    recordLiftedField(fRef.owner(), fRef.name());
                    recordLiftedField(curNode.internalClassName(), fRef.name());
                    curNode = nextNode;
                    hops++;
                    pc += 3;
                } else if (op == 0xB6 || op == 0xB9) {
                    MethodRef mRef = methodOwner.resolveMethodRef(BytecodeSsaLifter.readUnsignedShort(code, pc + 1));
                    if (mRef.descriptor().startsWith("()L")) {
                        RawClassFile nodeCls = resolveClass(curNode.internalClassName());
                        if (nodeCls == null) {
                            return null;
                        }
                        RawMethodRef getterRef = BytecodeSsaLifter.findMethodOwnerInHierarchy(
                                classesByName, nodeCls, mRef.name(), mRef.descriptor());
                        if (getterRef == null) {
                            return null;
                        }
                        FieldRef gf = tryExtractZeroArgFieldGetter(getterRef.owner(), getterRef.method());
                        if (gf == null) {
                            return null;
                        }
                        Integer childId = curNode.lookupObjectFieldRef(gf.owner(), gf.name());
                        if (childId == null) {
                            return null;
                        }
                        if (childId == -2) {
                            childId = tryExpandTruncatedFieldOnDemand(curNode.instanceId(), gf);
                        }
                        if (childId < 0) {
                            return null;
                        }
                        InstanceNode nextNode = lookupInstance(childId);
                        if (nextNode == null) {
                            return null;
                        }
                        recordLiftedMethod(getterRef.owner().thisClass, mRef.name());
                        recordLiftedMethod(curNode.internalClassName(), mRef.name());
                        recordLiftedField(gf.owner(), gf.name());
                        recordLiftedField(curNode.internalClassName(), gf.name());
                        curNode = nextNode;
                        hops++;
                        pc += (op == 0xB9) ? 5 : 3;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
            if (hops == 0) {
                return null;
            }

            List<String> paramDescs = splitMethodParamDescriptors(method.descriptor());
            if (paramDescs.size() != callArgs.size()) {
                return null;
            }
            List<SymVal> forwardedArgs = new ArrayList<>(callArgs.size());
            int expectedSlot = 1;
            for (int i = 0; i < paramDescs.size(); i++) {
                String pDesc = paramDescs.get(i);
                ConceptType pType = ConceptType.fromDescriptorPrefix(pDesc);
                int slotRead = matchLocalLoadSlot(code, pc);
                if (slotRead != expectedSlot) {
                    return null;
                }
                pc += BytecodeSsaLifter.instructionLength(code, pc);
                SymVal argVal = callArgs.get(i);
                while (pc < code.length) {
                    int castOp = code[pc] & 0xFF;
                    if (castOp == 0xC0) {
                        pc += 3;
                    } else if (castOp == 0x8D && argVal instanceof SymVal.Scalar sc) {
                        argVal = new SymVal.Scalar(coerceScalar(sc.expr(), ConceptType.F64));
                        pc += 1;
                    } else if (castOp == 0x90 && argVal instanceof SymVal.Scalar sc) {
                        argVal = new SymVal.Scalar(coerceScalar(sc.expr(), ConceptType.F32));
                        pc += 1;
                    } else {
                        break;
                    }
                }
                forwardedArgs.add(argVal);
                expectedSlot += jvmSlotWidth(pDesc);
            }

            if (pc >= code.length) {
                return null;
            }
            int invokeOp = code[pc] & 0xFF;
            if (invokeOp != 0xB6 && invokeOp != 0xB9 && invokeOp != 0xB7) {
                return null;
            }
            MethodRef delRef = methodOwner.resolveMethodRef(BytecodeSsaLifter.readUnsignedShort(code, pc + 1));
            List<String> delParamDescs = splitMethodParamDescriptors(delRef.descriptor());
            if (delParamDescs.size() != forwardedArgs.size()) {
                return null;
            }
            pc += (invokeOp == 0xB9) ? 5 : 3;

            ConceptType postCast = null;
            while (pc < code.length) {
                int postOp = code[pc] & 0xFF;
                if (postOp == 0xC0 || postOp == 0x00) {
                    pc += BytecodeSsaLifter.instructionLength(code, pc);
                } else if (postOp == 0x90) {
                    postCast = ConceptType.F32;
                    pc += 1;
                } else if (postOp == 0x8D) {
                    postCast = ConceptType.F64;
                    pc += 1;
                } else {
                    break;
                }
            }
            if (pc >= code.length) {
                return null;
            }
            int retOp = code[pc] & 0xFF;
            if (retOp < 0xAC || retOp > 0xB1) {
                return null;
            }

            RawClassFile targetCls = resolveClass(curNode.internalClassName());
            if (targetCls == null) {
                return null;
            }
            RawMethodRef targetMethod = invokeOp == 0xB7
                    ? findSpecialTarget(delRef, targetCls)
                    : BytecodeSsaLifter.findAnyMethodOwnerInHierarchy(
                            classesByName, targetCls, delRef.name(), delRef.descriptor());
            if (targetMethod == null) {
                return null;
            }

            ConceptType combinedOuterCast = existingOuterCast != null ? existingOuterCast : postCast;
            return new DelegatorStep(
                    instanceIdToSymVal(curNode.instanceId()),
                    targetCls,
                    targetMethod.owner(),
                    targetMethod.method(),
                    forwardedArgs,
                    combinedOuterCast);
        }

        boolean hasInternedInstanceOfDescriptor(String retDesc) {
            if (retDesc == null
                    || !retDesc.startsWith("L")
                    || !retDesc.endsWith(";")
                    || "Ljava/lang/Object;".equals(retDesc)) {
                return false;
            }
            if (internedReturnDescriptors.contains(retDesc)) {
                return true;
            }
            String internal = retDesc.substring(1, retDesc.length() - 1);
            for (InstanceNode n : snapshot.instancesById().values()) {
                if (n.internedId() != null) {
                    if (internal.equals(n.internalClassName()) || "java/lang/Object".equals(internal)) {
                        return true;
                    }
                    RawClassFile rc = resolveClass(n.internalClassName());
                    if (rc != null && (internal.equals(rc.superClass) || rc.interfaces.contains(internal))) {
                        return true;
                    }
                }
            }
            return false;
        }

        RawMethodRef findSpecialTarget(MethodRef mRef, RawClassFile fallbackReceiverCls) {
            RawClassFile declaredOwner = resolveClass(mRef.owner());
            RawClassFile start = declaredOwner != null ? declaredOwner : fallbackReceiverCls;
            return BytecodeSsaLifter.findAnyMethodOwnerInHierarchy(
                    classesByName, start, mRef.name(), mRef.descriptor());
        }
    }

    // -------------------------------------------------------------------------
    // Per-Method Structured Symbolic Lifter + Constant Loop Unroller
    // -------------------------------------------------------------------------

    private static final class SymFrame {
        final Map<Integer, SymVal> locals;
        final List<SymVal> stack;
        final Set<Integer> coldMemoInstances;
        final Map<String, SymVal> coldMemoFields;
        boolean terminated;

        SymFrame() {
            this.locals = new LinkedHashMap<>();
            this.stack = new ArrayList<>();
            this.coldMemoInstances = new HashSet<>();
            this.coldMemoFields = new HashMap<>();
            this.terminated = false;
        }

        SymFrame(
                Map<Integer, SymVal> locals,
                List<SymVal> stack,
                Set<Integer> coldMemoInstances,
                Map<String, SymVal> coldMemoFields,
                boolean terminated) {
            this.locals = new LinkedHashMap<>(locals);
            this.stack = new ArrayList<>(stack);
            this.coldMemoInstances = new HashSet<>(coldMemoInstances);
            this.coldMemoFields = new HashMap<>(coldMemoFields);
            this.terminated = terminated;
        }

        SymFrame copy() {
            return new SymFrame(
                    this.locals, this.stack, this.coldMemoInstances, this.coldMemoFields, this.terminated);
        }

        void push(SymVal val) {
            stack.add(Objects.requireNonNull(val));
        }

        SymVal pop() {
            if (stack.isEmpty()) {
                return new SymVal.Scalar(new ConceptExpr.IntConst(0));
            }
            return stack.remove(stack.size() - 1);
        }

        SymVal peek() {
            if (stack.isEmpty()) {
                return new SymVal.Scalar(new ConceptExpr.IntConst(0));
            }
            return stack.get(stack.size() - 1);
        }
    }

    private static final class SharedMethodState {
        final Map<String, ConceptType> localTypes = new LinkedHashMap<>();
        final List<IndexedContextBinding> usedContextBindings = new ArrayList<>();
        final Set<String> inlineCallStack = new HashSet<>();
        int nextTempId = 0;
        int nextCursorSlot = 10_000;
    }

    private static final class ReturnInterceptor {
        final String doneVar;
        final String retVar;
        final ConceptType retType;
        final List<SymVal> objectReturns = new ArrayList<>();
        boolean sawReturn;

        ReturnInterceptor(String doneVar, String retVar, ConceptType retType) {
            this.doneVar = doneVar;
            this.retVar = retVar;
            this.retType = retType;
        }
    }

    private static final class MethodLiftEngine {
        final GraphLiftContext ctx;
        final RawClassFile concreteCls;
        final RawClassFile methodOwner;
        final RawMethod method;
        final SymVal receiverVal;
        final List<SymVal> args;
        final ConceptType expectedReturnType;
        final boolean returnsInternedObject;

        final NavigableMap<Integer, BasicBlock> blocks;
        final Map<Integer, Set<Integer>> reach;
        final Map<Integer, Integer> ipdom;
        final SharedMethodState shared;
        final Map<String, ConceptType> localTypes;
        final List<IndexedContextBinding> usedContextBindings;
        final Set<Integer> activeStructuredWhileHeaders = new HashSet<>();
        ReturnInterceptor activeReturnInterceptor;
        boolean pure = true;
        final String varPrefix;

        MethodLiftEngine(
                GraphLiftContext ctx,
                RawClassFile concreteCls,
                RawClassFile methodOwner,
                RawMethod method,
                SymVal receiverVal,
                List<SymVal> args,
                ConceptType expectedReturnType,
                boolean returnsInternedObject) {
            this(
                    ctx,
                    concreteCls,
                    methodOwner,
                    method,
                    receiverVal,
                    args,
                    expectedReturnType,
                    returnsInternedObject,
                    new SharedMethodState(),
                    "");
        }

        MethodLiftEngine(
                GraphLiftContext ctx,
                RawClassFile concreteCls,
                RawClassFile methodOwner,
                RawMethod method,
                SymVal receiverVal,
                List<SymVal> args,
                ConceptType expectedReturnType,
                boolean returnsInternedObject,
                SharedMethodState shared,
                String varPrefix) {
            this.ctx = ctx;
            this.concreteCls = concreteCls;
            this.methodOwner = methodOwner;
            this.method = method;
            this.receiverVal = receiverVal;
            this.args = args;
            this.expectedReturnType = expectedReturnType;
            this.returnsInternedObject = returnsInternedObject;
            this.shared = shared;
            this.localTypes = shared.localTypes;
            this.usedContextBindings = shared.usedContextBindings;
            this.varPrefix = varPrefix;

            this.blocks = BytecodeSsaLifter.buildCfgBlocks(method.code());
            this.reach = BytecodeSsaLifter.computeForwardReachability(this.blocks);
            this.ipdom = BytecodeSsaLifter.computeImmediatePostDominators(this.blocks);
        }

        MethodLiftResult lift() {
            SymFrame initFrame = new SymFrame();
            boolean isStatic = (method.accessFlags() & BytecodeSsaLifter.ACC_STATIC) != 0;
            int slot = 0;
            if (!isStatic) {
                initFrame.locals.put(0, receiverVal);
                slot = 1;
            }
            List<String> rawDescs = splitMethodParamDescriptors(method.descriptor());
            for (int i = 0; i < rawDescs.size() && i < args.size(); i++) {
                SymVal arg = args.get(i);
                initFrame.locals.put(slot, arg);
                if (arg instanceof SymVal.Scalar sc && sc.expr() instanceof ConceptExpr.Var v) {
                    localTypes.putIfAbsent(v.name(), v.type());
                } else if (arg instanceof SymVal.InternedScalar is && is.expr() instanceof ConceptExpr.Var v) {
                    localTypes.putIfAbsent(v.name(), ConceptType.I32);
                }
                slot += jvmSlotWidth(rawDescs.get(i));
            }

            List<ConceptStmt> body = new ArrayList<>();
            liftRegion(0, BytecodeSsaLifter.EXIT_PC, initFrame, new HashMap<>(), body);

            if (activeReturnInterceptor == null
                    && expectedReturnType != ConceptType.VOID
                    && !endsWithDefiniteReturn(body)) {
                body.add(new ConceptStmt.Return(BytecodeSsaLifter.defaultConstForType(expectedReturnType)));
            }
            return new MethodLiftResult(body, localTypes, usedContextBindings, pure);
        }

        private void markImpure(int pc, int opcode, String detail) {
            pure = false;
            String reportOwner = concreteCls != null ? concreteCls.thisClass : methodOwner.thisClass;
            ctx.recordUnmapped(reportOwner, method.name(), pc, opcode, detail);
        }

        private void liftRegion(
                int startPc,
                int stopPc,
                SymFrame frame,
                Map<Integer, Integer> loopCounts,
                List<ConceptStmt> out) {
            int pc = startPc;
            Map<Integer, Integer> activeLoops = new HashMap<>(loopCounts);

            while (pc != stopPc
                    && !activeStructuredWhileHeaders.contains(pc)
                    && pc != BytecodeSsaLifter.EXIT_PC
                    && pc >= 0
                    && !frame.terminated) {
                BasicBlock block = blocks.get(pc);
                if (block == null) {
                    markImpure(pc, 0, "missing basic block at pc " + pc);
                    frame.terminated = true;
                    return;
                }

                if ( !activeStructuredWhileHeaders.contains(pc) ) {
                    Integer latchPc = findNaturalLoopLatch(pc, stopPc);
                    if (latchPc != null && shouldLiftAsStructuredWhile(pc, latchPc, frame)) {
                        int exitAfterWhile = liftStructuredWhileLoop(pc, latchPc, stopPc, frame, activeLoops, out);
                        if (frame.terminated || exitAfterWhile == BytecodeSsaLifter.EXIT_PC) {
                            return;
                        }
                        pc = advanceForwardJoin(pc, exitAfterWhile, activeLoops);
                        continue;
                    }
                }

                // Execute all non-terminator instructions in block
                byte[] code = method.code();
                int insnPc = block.startPc;
                while (insnPc < block.lastInsnPc && !frame.terminated) {
                    stepNonBranchInstruction(code, insnPc, frame, out);
                    insnPc += BytecodeSsaLifter.instructionLength(code, insnPc);
                }
                if (frame.terminated) {
                    return;
                }

                int lastPc = block.lastInsnPc;
                int lastOp = code[lastPc] & 0xFF;

                if (BytecodeSsaLifter.isConditionalBranch(lastOp)) {
                    ConceptExpr cond = evaluateBranchCondition(lastOp, lastPc, frame);
                    if (frame.terminated) {
                        return;
                    }
                    Boolean folded = tryEvalConstBool(cond);
                    if (folded != null) {
                        int chosen = folded ? block.branchTargetPc : block.fallthroughPc;
                        pc = advanceEdge(pc, chosen, lastPc, lastOp, activeLoops, frame);
                        continue;
                    }
                    if (block.branchTargetPc <= pc || block.fallthroughPc <= pc) {
                        markImpure(lastPc, lastOp, "non-constant loop condition");
                        frame.terminated = true;
                        return;
                    }

                    int joinPc = resolveStructuredJoin(pc, stopPc);
                    SymFrame thenFrame = frame.copy();
                    SymFrame elseFrame = frame.copy();
                    List<ConceptStmt> thenStmts = new ArrayList<>();
                    List<ConceptStmt> elseStmts = new ArrayList<>();

                    if (block.branchTargetPc != joinPc) {
                        Map<Integer, Integer> thenLoops = copyLoopsForForwardEdge(activeLoops, pc, block.branchTargetPc);
                        liftRegion(block.branchTargetPc, joinPc, thenFrame, thenLoops, thenStmts);
                    }
                    if (block.fallthroughPc != joinPc) {
                        Map<Integer, Integer> elseLoops = copyLoopsForForwardEdge(activeLoops, pc, block.fallthroughPc);
                        liftRegion(block.fallthroughPc, joinPc, elseFrame, elseLoops, elseStmts);
                    }

                    mergeConditionalArms(cond, lastPc, joinPc, frame, thenFrame, elseFrame, thenStmts, elseStmts, out);
                    if (frame.terminated) {
                        return;
                    }
                    pc = advanceForwardJoin(pc, joinPc, activeLoops);
                } else if (lastOp == 0xA7 || lastOp == 0xC8) {
                    int target = block.unconditionalTargetPc;
                    pc = advanceEdge(pc, target, lastPc, lastOp, activeLoops, frame);
                } else if (lastOp == 0xAA || lastOp == 0xAB) {
                    SymVal keyVal = frame.pop();
                    ConceptExpr keyExpr = asScalarExpr(keyVal, ConceptType.I32, lastPc, lastOp);
                    Integer constKey = tryEvalConstInt(keyExpr);
                    if (constKey != null) {
                        int target = block.switchCases.getOrDefault(constKey, block.switchDefaultPc);
                        pc = advanceEdge(pc, target, lastPc, lastOp, activeLoops, frame);
                        continue;
                    }
                    int joinPc = resolveStructuredJoin(pc, stopPc);
                    liftRuntimeSwitch(pc, lastPc, joinPc, keyExpr, block, frame, activeLoops, out);
                    if (frame.terminated) {
                        return;
                    }
                    pc = advanceForwardJoin(pc, joinPc, activeLoops);
                } else if (lastOp >= 0xAC && lastOp <= 0xB1) {
                    emitReturnInstruction(lastOp, lastPc, frame, out);
                    frame.terminated = true;
                    return;
                } else if (lastOp == 0xBF) {
                    markImpure(lastPc, lastOp, "athrow on reachable path");
                    frame.terminated = true;
                    return;
                } else {
                    stepNonBranchInstruction(code, lastPc, frame, out);
                    if (frame.terminated) {
                        return;
                    }
                    pc = advanceForwardJoin(pc, block.fallthroughPc, activeLoops);
                }
            }
        }

        private Integer findNaturalLoopLatch(int headerPc, int stopPc) {
            BasicBlock header = blocks.get(headerPc);
            if (header == null) {
                return null;
            }
            int lastOp = method.code()[header.lastInsnPc] & 0xFF;
            if (!BytecodeSsaLifter.isConditionalBranch(lastOp)) {
                return null;
            }
            int maxLatch = -1;
            Collection<BasicBlock> candidates = (stopPc == BytecodeSsaLifter.EXIT_PC || stopPc <= headerPc)
                    ? blocks.tailMap(headerPc, true).values()
                    : blocks.subMap(headerPc, true, stopPc, false).values();
            for (BasicBlock b : candidates) {
                if (b.successors.contains(headerPc) && BytecodeSsaLifter.canReach(reach, headerPc, b.startPc)) {
                    if (b.startPc > maxLatch) {
                        maxLatch = b.startPc;
                    }
                }
            }
            if (maxLatch < headerPc) {
                return null;
            }
            boolean jumpExits = header.branchTargetPc > maxLatch;
            boolean fallExits = header.fallthroughPc > maxLatch;
            return (jumpExits ^ fallExits) ? maxLatch : null;
        }

        private boolean shouldLiftAsStructuredWhile(int headerPc, int latchPc, SymFrame entryFrame) {
            byte[] code = method.code();
            Set<Integer> scalarWrittenSlots = new LinkedHashSet<>();
            boolean hasObjectArrayLoad = false;
            for (BasicBlock b : blocks.subMap(headerPc, true, latchPc, true).values()) {
                for (int p = b.startPc; p < b.endPc; p += BytecodeSsaLifter.instructionLength(code, p)) {
                    int op = code[p] & 0xFF;
                    if (op == 0x32) { // AALOAD (e.g. ImprovedNoise[] or NoiseStack$Layer[] constant unroll)
                        hasObjectArrayLoad = true;
                    } else if (op == 0x3A || (op >= 0x4B && op <= 0x4E)) {
                        int slot = op == 0x3A ? (code[p + 1] & 0xFF) : (op - 0x4B);
                        SymVal existing = entryFrame.locals.get(slot);
                        if (existing != null
                                && !(existing instanceof SymVal.LocalObjectRef)
                                && !(existing instanceof SymVal.ColdMemoCacheRef)
                                && !(existing instanceof SymVal.NullRef)) {
                            return false;
                        }
                    } else if (op == 0xB9 || op == 0xB6) {
                        MethodRef mr = methodOwner.resolveMethodRef(BytecodeSsaLifter.readUnsignedShort(code, p + 1));
                        if (("hasNext".equals(mr.name()) || "next".equals(mr.name())) && mr.owner().contains("Iterator")) {
                            return false;
                        }
                        if (("getDouble".equals(mr.name()) || "get".equals(mr.name())) && mr.owner().contains("List")) {
                            hasObjectArrayLoad = true;
                        }
                    } else {
                        int sSlot = extractScalarWriteSlot(code, p);
                        if (sSlot >= 0) {
                            scalarWrittenSlots.add(sSlot);
                        }
                    }
                }
            }
            if (hasObjectArrayLoad) {
                // Probe whether header condition is compile-time constant: if so, let constant unroller specialize elements!
                SymFrame probeFrame = entryFrame.copy();
                List<ConceptStmt> discard = new ArrayList<>();
                BasicBlock hb = blocks.get(headerPc);
                int savedReasons = ctx.unmappedReasons.size();
                boolean savedPure = this.pure;
                for (int p = hb.startPc; p < hb.lastInsnPc && !probeFrame.terminated;
                        p += BytecodeSsaLifter.instructionLength(code, p)) {
                    stepNonBranchInstruction(code, p, probeFrame, discard);
                }
                ConceptExpr probeCond = !probeFrame.terminated
                        ? evaluateBranchCondition(code[hb.lastInsnPc] & 0xFF, hb.lastInsnPc, probeFrame)
                        : null;
                while (ctx.unmappedReasons.size() > savedReasons) {
                    ctx.unmappedReasons.remove(ctx.unmappedReasons.size() - 1);
                }
                this.pure = savedPure;
                if (probeCond != null && tryEvalConstBool(probeCond) != null) {
                    return false;
                }
            }
            // Also let very short constant loops (<= 16 iterations) unroll directly when condition is constant
            SymFrame probeFrame = entryFrame.copy();
            List<ConceptStmt> discard = new ArrayList<>();
            BasicBlock hb = blocks.get(headerPc);
            int savedReasons = ctx.unmappedReasons.size();
            boolean savedPure = this.pure;
            for (int p = hb.startPc; p < hb.lastInsnPc && !probeFrame.terminated;
                    p += BytecodeSsaLifter.instructionLength(code, p)) {
                stepNonBranchInstruction(code, p, probeFrame, discard);
            }
            ConceptExpr probeCond = !probeFrame.terminated
                    ? evaluateBranchCondition(code[hb.lastInsnPc] & 0xFF, hb.lastInsnPc, probeFrame)
                    : null;
            while (ctx.unmappedReasons.size() > savedReasons) {
                ctx.unmappedReasons.remove(ctx.unmappedReasons.size() - 1);
            }
            this.pure = savedPure;
            if (probeCond != null && tryEvalConstBool(probeCond) != null) {
                // Only unroll very short constant loops (<= 16 iterations); larger loops use structured While
                Integer estTrips = estimateSimpleConstantTripCount(hb, entryFrame, scalarWrittenSlots);
                if (estTrips != null && estTrips <= 16) {
                    return false;
                }
            }
            return true;
        }

        private Integer estimateSimpleConstantTripCount(
                BasicBlock header,
                SymFrame entryFrame,
                Set<Integer> writtenSlots) {
            if (writtenSlots.isEmpty()) {
                return null;
            }
            byte[] code = method.code();
            int lastOp = code[header.lastInsnPc] & 0xFF;
            if (lastOp >= 0x9F && lastOp <= 0xA4 && header.endPc - header.startPc <= 12) {
                SymFrame pf = entryFrame.copy();
                List<ConceptStmt> tmp = new ArrayList<>();
                for (int p = header.startPc; p < header.lastInsnPc; p += BytecodeSsaLifter.instructionLength(code, p)) {
                    stepNonBranchInstruction(code, p, pf, tmp);
                }
                if (pf.stack.size() >= 2) {
                    SymVal r = pf.pop();
                    SymVal l = pf.pop();
                    if (l instanceof SymVal.Scalar ls && r instanceof SymVal.Scalar rs) {
                        Integer li = tryEvalConstInt(ls.expr());
                        Integer ri = tryEvalConstInt(rs.expr());
                        if (li != null && ri != null) {
                            return Math.abs(ri - li) + 1;
                        }
                    }
                }
            }
            return null;
        }

        private static int extractScalarWriteSlot(byte[] code, int pc) {
            int op = code[pc] & 0xFF;
            return switch (op) {
                case 0x36, 0x37, 0x38, 0x39, 0x84 -> code[pc + 1] & 0xFF;
                case 0x3B, 0x3C, 0x3D, 0x3E -> op - 0x3B;
                case 0x3F, 0x40, 0x41, 0x42 -> op - 0x3F;
                case 0x43, 0x44, 0x45, 0x46 -> op - 0x43;
                case 0x47, 0x48, 0x49, 0x4A -> op - 0x47;
                case 0xC4 -> {
                    int sub = code[pc + 1] & 0xFF;
                    yield (sub >= 0x36 && sub <= 0x39) || sub == 0x84
                            ? BytecodeSsaLifter.readUnsignedShort(code, pc + 2)
                            : -1;
                }
                default -> -1;
            };
        }

        private int liftStructuredWhileLoop(
                int headerPc,
                int latchPc,
                int outerStopPc,
                SymFrame frame,
                Map<Integer, Integer> activeLoops,
                List<ConceptStmt> out) {
            byte[] code = method.code();
            Set<Integer> writtenSlots = new LinkedHashSet<>();
            for (BasicBlock b : blocks.subMap(headerPc, true, latchPc, true).values()) {
                for (int p = b.startPc; p < b.endPc; p += BytecodeSsaLifter.instructionLength(code, p)) {
                    int sSlot = extractScalarWriteSlot(code, p);
                    if (sSlot >= 0) {
                        writtenSlots.add(sSlot);
                    }
                }
            }

            // Widen loop-carried scalar locals to canonical mutable variables before header evaluation
            for (int slot : writtenSlots) {
                SymVal cur = frame.locals.get(slot);
                if (cur instanceof SymVal.Scalar sc) {
                    ConceptType t = sc.expr().type();
                    String varName = resolveLocalSlotVarName(slot, headerPc, t);
                    if (!(sc.expr() instanceof ConceptExpr.Var v && v.name().equals(varName))) {
                        out.add(new ConceptStmt.Assign(varName, t, sc.expr()));
                        frame.locals.put(slot, new SymVal.Scalar(new ConceptExpr.Var(varName, t)));
                    }
                }
            }

            BasicBlock header = blocks.get(headerPc);
            SymFrame headerFrame = frame.copy();
            List<ConceptStmt> headerPre = new ArrayList<>();
            for (int p = header.startPc; p < header.lastInsnPc && !headerFrame.terminated;
                    p += BytecodeSsaLifter.instructionLength(code, p)) {
                stepNonBranchInstruction(code, p, headerFrame, headerPre);
            }
            if (headerFrame.terminated) {
                frame.terminated = true;
                return BytecodeSsaLifter.EXIT_PC;
            }
            int lastOp = code[header.lastInsnPc] & 0xFF;
            ConceptExpr rawCond = evaluateBranchCondition(lastOp, header.lastInsnPc, headerFrame);
            boolean jumpExits = header.branchTargetPc > latchPc;
            ConceptExpr whileCond = jumpExits ? BytecodeSsaLifter.negateBooleanExpr(rawCond) : rawCond;
            int bodyEntryPc = jumpExits ? header.fallthroughPc : header.branchTargetPc;
            int exitPc = jumpExits ? header.branchTargetPc : header.fallthroughPc;

            String loopDoneVar = allocTemp("_loop_done_" + headerPc, ConceptType.BOOL);
            ConceptType captureType = activeReturnInterceptor != null
                    ? activeReturnInterceptor.retType
                    : expectedReturnType;
            String loopRetVar = captureType != ConceptType.VOID
                    ? allocTemp("_loop_ret_" + headerPc, captureType)
                    : null;

            ReturnInterceptor prevInterceptor = this.activeReturnInterceptor;
            ReturnInterceptor loopInterceptor = new ReturnInterceptor(loopDoneVar, loopRetVar, captureType);
            this.activeReturnInterceptor = loopInterceptor;
            activeStructuredWhileHeaders.add(headerPc);

            SymFrame bodyFrame = headerFrame.copy();
            List<ConceptStmt> bodyStmts = new ArrayList<>();
            try {
                liftRegion(bodyEntryPc, headerPc, bodyFrame, new HashMap<>(activeLoops), bodyStmts);
            } finally {
                activeStructuredWhileHeaders.remove(headerPc);
                this.activeReturnInterceptor = prevInterceptor;
            }

            // Ensure any loop-modified scalar slot whose expression changed in bodyFrame is written back to its canonical var
            for (int slot : writtenSlots) {
                SymVal initSym = headerFrame.locals.get(slot);
                SymVal endSym = bodyFrame.locals.get(slot);
                if (initSym instanceof SymVal.Scalar initSc
                        && initSc.expr() instanceof ConceptExpr.Var canonVar
                        && endSym instanceof SymVal.Scalar endSc
                        && !(endSc.expr() instanceof ConceptExpr.Var ev && ev.name().equals(canonVar.name()))
                        && !bodyFrame.terminated) {
                    bodyStmts.add(new ConceptStmt.Assign(
                            canonVar.name(), canonVar.type(), coerceScalar(endSc.expr(), canonVar.type())));
                }
            }

            if (loopInterceptor.sawReturn) {
                out.add(new ConceptStmt.Assign(loopDoneVar, ConceptType.BOOL, new ConceptExpr.BoolConst(false)));
                if (loopRetVar != null) {
                    out.add(new ConceptStmt.Assign(
                            loopRetVar, captureType, BytecodeSsaLifter.defaultConstForType(captureType)));
                }
                out.addAll(headerPre);
                ConceptExpr guardedCond = new ConceptExpr.BinaryOp(
                        BinaryOperator.LOGICAL_AND,
                        new ConceptExpr.UnaryOp(
                                UnaryOperator.LOGICAL_NOT,
                                new ConceptExpr.Var(loopDoneVar, ConceptType.BOOL),
                                ConceptType.BOOL),
                        whileCond,
                        ConceptType.BOOL);
                if (!headerPre.isEmpty()) {
                    bodyStmts.add(new ConceptStmt.If(
                            new ConceptExpr.UnaryOp(
                                    UnaryOperator.LOGICAL_NOT,
                                    new ConceptExpr.Var(loopDoneVar, ConceptType.BOOL),
                                    ConceptType.BOOL),
                            new ArrayList<>(headerPre),
                            List.of()));
                }
                out.add(new ConceptStmt.While(guardedCond, bodyStmts));

                List<ConceptStmt> onEarlyReturn = new ArrayList<>();
                SymFrame retFrame = headerFrame.copy();
                if (loopRetVar != null) {
                    retFrame.push(new SymVal.Scalar(new ConceptExpr.Var(loopRetVar, captureType)));
                    int retOpcode = switch (captureType) {
                        case I64 -> 0xAD;
                        case F32 -> 0xAE;
                        case F64 -> 0xAF;
                        default -> 0xAC;
                    };
                    emitReturnInstruction(retOpcode, header.lastInsnPc, retFrame, onEarlyReturn);
                } else {
                    emitReturnInstruction(0xB1, header.lastInsnPc, retFrame, onEarlyReturn);
                }

                List<ConceptStmt> afterLoopStmts = new ArrayList<>();
                SymFrame afterFrame = headerFrame.copy();
                liftRegion(exitPc, outerStopPc, afterFrame, new HashMap<>(activeLoops), afterLoopStmts);
                out.add(new ConceptStmt.If(
                        new ConceptExpr.Var(loopDoneVar, ConceptType.BOOL),
                        onEarlyReturn,
                        afterLoopStmts));
                adoptFrame(frame, afterFrame);
                if (afterFrame.terminated) {
                    frame.terminated = true;
                }
                return outerStopPc;
            }

            localTypes.remove(loopDoneVar);
            if (loopRetVar != null) {
                localTypes.remove(loopRetVar);
            }
            out.addAll(headerPre);
            if (!headerPre.isEmpty()) {
                bodyStmts.addAll(headerPre);
            }
            out.add(new ConceptStmt.While(whileCond, bodyStmts));
            adoptFrame(frame, headerFrame);
            return exitPc;
        }

        private int resolveStructuredJoin(int branchBlockPc, int stopPc) {
            int joinPc = ipdom.getOrDefault(branchBlockPc, BytecodeSsaLifter.EXIT_PC);
            if (stopPc != BytecodeSsaLifter.EXIT_PC && joinPc != stopPc) {
                if (joinPc == BytecodeSsaLifter.EXIT_PC
                        || !BytecodeSsaLifter.canReach(reach, joinPc, stopPc)) {
                    // Only clamp to stopPc if stopPc is reachable from branchBlockPc
                    if (BytecodeSsaLifter.canReach(reach, branchBlockPc, stopPc)) {
                        return stopPc;
                    }
                }
            }
            return joinPc;
        }

        private int advanceEdge(
                int fromPc,
                int targetPc,
                int insnPc,
                int opcode,
                Map<Integer, Integer> activeLoops,
                SymFrame frame) {
            if (targetPc <= fromPc) {
                int count = activeLoops.getOrDefault(targetPc, 0) + 1;
                if (count > ctx.options.maxLoopUnrollLength()) {
                    markImpure(
                            insnPc,
                            opcode,
                            "loop exceeded maxLoopUnrollLength=" + ctx.options.maxLoopUnrollLength());
                    frame.terminated = true;
                    return BytecodeSsaLifter.EXIT_PC;
                }
                activeLoops.put(targetPc, count);
                return targetPc;
            }
            return advanceForwardJoin(fromPc, targetPc, activeLoops);
        }

        private static int advanceForwardJoin(int fromPc, int targetPc, Map<Integer, Integer> activeLoops) {
            if (targetPc > fromPc && !activeLoops.isEmpty()) {
                activeLoops.keySet().removeIf(headerPc -> headerPc > fromPc && headerPc < targetPc);
            }
            return targetPc;
        }

        private static Map<Integer, Integer> copyLoopsForForwardEdge(
                Map<Integer, Integer> activeLoops,
                int fromPc,
                int targetPc) {
            Map<Integer, Integer> copy = new HashMap<>(activeLoops);
            advanceForwardJoin(fromPc, targetPc, copy);
            return copy;
        }

        private void mergeConditionalArms(
                ConceptExpr cond,
                int branchPc,
                int joinPc,
                SymFrame parentFrame,
                SymFrame thenFrame,
                SymFrame elseFrame,
                List<ConceptStmt> thenStmts,
                List<ConceptStmt> elseStmts,
                List<ConceptStmt> out) {
            if (thenFrame.terminated && elseFrame.terminated) {
                out.add(new ConceptStmt.If(cond, thenStmts, elseStmts));
                parentFrame.terminated = true;
                return;
            }
            if (thenFrame.terminated) {
                out.add(new ConceptStmt.If(cond, thenStmts, List.of()));
                adoptFrame(parentFrame, elseFrame);
                out.addAll(elseStmts);
                return;
            }
            if (elseFrame.terminated) {
                out.add(new ConceptStmt.If(BytecodeSsaLifter.negateBooleanExpr(cond), elseStmts, List.of()));
                adoptFrame(parentFrame, thenFrame);
                out.addAll(thenStmts);
                return;
            }

            SymFrame merged = mergeLiveFrames(branchPc, joinPc, thenFrame, elseFrame, thenStmts, elseStmts);
            if (!thenStmts.isEmpty() || !elseStmts.isEmpty()) {
                if (thenStmts.isEmpty()) {
                    out.add(new ConceptStmt.If(BytecodeSsaLifter.negateBooleanExpr(cond), elseStmts, List.of()));
                } else {
                    out.add(new ConceptStmt.If(cond, thenStmts, elseStmts));
                }
            }
            adoptFrame(parentFrame, merged);
        }

        private SymFrame mergeLiveFrames(
                int branchPc,
                int joinPc,
                SymFrame a,
                SymFrame b,
                List<ConceptStmt> stmtsA,
                List<ConceptStmt> stmtsB) {
            SymFrame out = new SymFrame();
            int stackDepth = Math.min(a.stack.size(), b.stack.size());
            for (int i = 0; i < stackDepth; i++) {
                out.stack.add(mergeSymVal("stk" + i + "_" + branchPc, joinPc, a.stack.get(i), b.stack.get(i), stmtsA, stmtsB));
            }
            Set<Integer> allSlots = new LinkedHashSet<>(a.locals.keySet());
            allSlots.addAll(b.locals.keySet());
            for (int slot : allSlots) {
                SymVal va = a.locals.get(slot);
                SymVal vb = b.locals.get(slot);
                if (va == null || vb == null) {
                    continue;
                }
                out.locals.put(slot, mergeSymVal("loc" + slot + "_" + branchPc, joinPc, va, vb, stmtsA, stmtsB));
            }
            return out;
        }

        private SymVal mergeSymVal(
                String tag,
                int joinPc,
                SymVal va,
                SymVal vb,
                List<ConceptStmt> stmtsA,
                List<ConceptStmt> stmtsB) {
            if (Objects.equals(va, vb)) {
                return va;
            }
            if ((va instanceof SymVal.InternedScalar || va instanceof SymVal.NullRef)
                    && (vb instanceof SymVal.InternedScalar || vb instanceof SymVal.NullRef)) {
                ConceptExpr ea = toInternedExpr(va);
                ConceptExpr eb = toInternedExpr(vb);
                String phi = allocTemp("_phi_" + tag, ConceptType.I32);
                stmtsA.add(new ConceptStmt.Assign(phi, ConceptType.I32, ea));
                stmtsB.add(new ConceptStmt.Assign(phi, ConceptType.I32, eb));
                return new SymVal.InternedScalar(new ConceptExpr.Var(phi, ConceptType.I32));
            }
            if (va instanceof SymVal.Scalar sa && vb instanceof SymVal.Scalar sb) {
                ConceptType mergedType = widenScalarTypes(sa.expr().type(), sb.expr().type());
                ConceptExpr ea = coerceScalar(sa.expr(), mergedType);
                ConceptExpr eb = coerceScalar(sb.expr(), mergedType);
                String phi = allocTemp("_phi_" + tag, mergedType);
                stmtsA.add(new ConceptStmt.Assign(phi, mergedType, ea));
                stmtsB.add(new ConceptStmt.Assign(phi, mergedType, eb));
                return new SymVal.Scalar(new ConceptExpr.Var(phi, mergedType));
            }
            return new SymVal.UncapturedRef("dynamic object merge at pc " + joinPc);
        }

        private void liftRuntimeSwitch(
                int switchBlockPc,
                int switchInsnPc,
                int joinPc,
                ConceptExpr keyExpr,
                BasicBlock block,
                SymFrame frame,
                Map<Integer, Integer> activeLoops,
                List<ConceptStmt> out) {
            ConceptExpr keyRef = ensureSimpleExpr(keyExpr, switchInsnPc, out);
            Map<Integer, List<Integer>> keysByTarget = new LinkedHashMap<>();
            for (Map.Entry<Integer, Integer> e : block.switchCases.entrySet()) {
                keysByTarget.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
            }

            List<Map.Entry<Integer, List<Integer>>> targetGroups = new ArrayList<>(keysByTarget.entrySet());
            boolean defaultIsThrow = isSyntheticThrowBlock(block.switchDefaultPc);

            SymFrame currentElseFrame = frame.copy();
            List<ConceptStmt> defaultStmts = new ArrayList<>();
            if (!defaultIsThrow && block.switchDefaultPc != joinPc) {
                liftRegion(
                        block.switchDefaultPc,
                        joinPc,
                        currentElseFrame,
                        copyLoopsForForwardEdge(activeLoops, switchBlockPc, block.switchDefaultPc),
                        defaultStmts);
            } else if (defaultIsThrow && !targetGroups.isEmpty()) {
                // Exhaustive enum/sealed switch where default is unreachable compiler throw
                Map.Entry<Integer, List<Integer>> lastGroup = targetGroups.remove(targetGroups.size() - 1);
                if (lastGroup.getKey() != joinPc) {
                    liftRegion(
                            lastGroup.getKey(),
                            joinPc,
                            currentElseFrame,
                            copyLoopsForForwardEdge(activeLoops, switchBlockPc, lastGroup.getKey()),
                            defaultStmts);
                }
            } else if (defaultIsThrow) {
                markImpure(switchInsnPc, 0xAA, "switch with only throw target");
                frame.terminated = true;
                return;
            }

            List<ConceptStmt> accumStmts = defaultStmts;
            SymFrame accumFrame = currentElseFrame;

            for (int i = targetGroups.size() - 1; i >= 0; i--) {
                int targetPc = targetGroups.get(i).getKey();
                List<Integer> matchVals = targetGroups.get(i).getValue();
                ConceptExpr matchCond = buildSwitchCaseCondition(keyRef, matchVals);

                SymFrame armFrame = frame.copy();
                List<ConceptStmt> armStmts = new ArrayList<>();
                if (targetPc != joinPc) {
                    liftRegion(
                            targetPc,
                            joinPc,
                            armFrame,
                            copyLoopsForForwardEdge(activeLoops, switchBlockPc, targetPc),
                            armStmts);
                }

                List<ConceptStmt> mergedStep = new ArrayList<>();
                SymFrame stepParent = frame.copy();
                mergeConditionalArms(
                        matchCond,
                        switchInsnPc + i + 1,
                        joinPc,
                        stepParent,
                        armFrame,
                        accumFrame,
                        armStmts,
                        accumStmts,
                        mergedStep);
                accumStmts = mergedStep;
                accumFrame = stepParent;
            }

            out.addAll(accumStmts);
            adoptFrame(frame, accumFrame);
        }

        private boolean isSyntheticThrowBlock(int targetPc) {
            BasicBlock b = blocks.get(targetPc);
            if (b == null) {
                return false;
            }
            byte[] code = method.code();
            return (code[b.lastInsnPc] & 0xFF) == 0xBF;
        }

        private static ConceptExpr buildSwitchCaseCondition(ConceptExpr keyRef, List<Integer> vals) {
            ConceptExpr cond = new ConceptExpr.BinaryOp(
                    BinaryOperator.EQ, keyRef, new ConceptExpr.IntConst(vals.get(0)), ConceptType.BOOL);
            for (int i = 1; i < vals.size(); i++) {
                ConceptExpr next = new ConceptExpr.BinaryOp(
                        BinaryOperator.EQ, keyRef, new ConceptExpr.IntConst(vals.get(i)), ConceptType.BOOL);
                cond = new ConceptExpr.BinaryOp(BinaryOperator.LOGICAL_OR, cond, next, ConceptType.BOOL);
            }
            return cond;
        }

        private static void adoptFrame(SymFrame dst, SymFrame src) {
            dst.locals.clear();
            dst.locals.putAll(src.locals);
            dst.stack.clear();
            dst.stack.addAll(src.stack);
            dst.terminated = src.terminated;
        }

        // ---------------------------------------------------------------------
        // Instruction Simulation
        // ---------------------------------------------------------------------

        private void stepNonBranchInstruction(
                byte[] code,
                int pc,
                SymFrame frame,
                List<ConceptStmt> out) {
            int opcode = code[pc] & 0xFF;
            switch (opcode) {
                case 0x00 -> { /* NOP */ }
                case 0x01 -> frame.push(SymVal.NullRef.INSTANCE);
                case 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 ->
                        frame.push(new SymVal.Scalar(new ConceptExpr.IntConst(opcode - 0x03)));
                case 0x09, 0x0A ->
                        frame.push(new SymVal.Scalar(new ConceptExpr.LongConst(opcode - 0x09)));
                case 0x0B, 0x0C, 0x0D ->
                        frame.push(new SymVal.Scalar(new ConceptExpr.FloatConst((float) (opcode - 0x0B))));
                case 0x0E, 0x0F ->
                        frame.push(new SymVal.Scalar(new ConceptExpr.DoubleConst((double) (opcode - 0x0E))));
                case 0x10 -> frame.push(new SymVal.Scalar(new ConceptExpr.IntConst(code[pc + 1])));
                case 0x11 -> frame.push(new SymVal.Scalar(new ConceptExpr.IntConst(BytecodeSsaLifter.readSignedShort(code, pc + 1))));
                case 0x12 -> frame.push(pushLdcConstant(code[pc + 1] & 0xFF));
                case 0x13, 0x14 -> frame.push(pushLdcConstant(BytecodeSsaLifter.readUnsignedShort(code, pc + 1)));

                // Loads
                case 0x15, 0x16, 0x17, 0x18, 0x19 -> loadLocal(frame, code[pc + 1] & 0xFF, opcode, pc);
                case 0x1A, 0x1B, 0x1C, 0x1D -> loadLocal(frame, opcode - 0x1A, 0x15, pc);
                case 0x1E, 0x1F, 0x20, 0x21 -> loadLocal(frame, opcode - 0x1E, 0x16, pc);
                case 0x22, 0x23, 0x24, 0x25 -> loadLocal(frame, opcode - 0x22, 0x17, pc);
                case 0x26, 0x27, 0x28, 0x29 -> loadLocal(frame, opcode - 0x26, 0x18, pc);
                case 0x2A, 0x2B, 0x2C, 0x2D -> loadLocal(frame, opcode - 0x2A, 0x19, pc);

                // Array loads
                case 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35 -> stepPrimitiveArrayLoad(opcode, pc, frame);
                case 0x32 -> stepObjectArrayLoad(pc, frame);

                // Stores
                case 0x36, 0x37, 0x38, 0x39, 0x3A -> storeLocal(frame, code[pc + 1] & 0xFF, opcode, pc, out);
                case 0x3B, 0x3C, 0x3D, 0x3E -> storeLocal(frame, opcode - 0x3B, 0x36, pc, out);
                case 0x3F, 0x40, 0x41, 0x42 -> storeLocal(frame, opcode - 0x3F, 0x37, pc, out);
                case 0x43, 0x44, 0x45, 0x46 -> storeLocal(frame, opcode - 0x43, 0x38, pc, out);
                case 0x47, 0x48, 0x49, 0x4A -> storeLocal(frame, opcode - 0x47, 0x39, pc, out);
                case 0x4B, 0x4C, 0x4D, 0x4E -> storeLocal(frame, opcode - 0x4B, 0x3A, pc, out);

                // Array stores (pure when targeting non-escaping LocalArrayRef or ColdMemoCacheRef)
                case 0x4F, 0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56 ->
                        stepArrayStore(opcode, pc, frame, out);

                // Stack manipulation
                case 0x57 -> frame.pop();
                case 0x58 -> {
                    SymVal top = frame.pop();
                    if (!isWideSymVal(top)) {
                        frame.pop();
                    }
                }
                case 0x59 -> {
                    SymVal top = materializeForDup(frame.pop(), pc, out);
                    frame.push(top);
                    frame.push(top);
                }
                case 0x5A -> {
                    SymVal v1 = materializeForDup(frame.pop(), pc, out);
                    SymVal v2 = frame.pop();
                    frame.push(v1);
                    frame.push(v2);
                    frame.push(v1);
                }
                case 0x5B -> {
                    SymVal v1 = materializeForDup(frame.pop(), pc, out);
                    SymVal v2 = frame.pop();
                    if (isWideSymVal(v2)) {
                        frame.push(v1);
                        frame.push(v2);
                        frame.push(v1);
                    } else {
                        SymVal v3 = frame.pop();
                        frame.push(v1);
                        frame.push(v3);
                        frame.push(v2);
                        frame.push(v1);
                    }
                }
                case 0x5C -> {
                    SymVal v1 = materializeForDup(frame.pop(), pc, out);
                    if (isWideSymVal(v1)) {
                        frame.push(v1);
                        frame.push(v1);
                    } else {
                        SymVal v2 = materializeForDup(frame.pop(), pc, out);
                        frame.push(v2);
                        frame.push(v1);
                        frame.push(v2);
                        frame.push(v1);
                    }
                }
                case 0x5F -> {
                    SymVal v1 = frame.pop();
                    SymVal v2 = frame.pop();
                    frame.push(v1);
                    frame.push(v2);
                }

                // Arithmetic & bitwise
                case 0x60, 0x61, 0x62, 0x63 -> stepBinaryArith(BinaryOperator.ADD, BytecodeSsaLifter.typeForArithOpcode(opcode - 0x60), pc, opcode, frame);
                case 0x64, 0x65, 0x66, 0x67 -> stepBinaryArith(BinaryOperator.SUB, BytecodeSsaLifter.typeForArithOpcode(opcode - 0x64), pc, opcode, frame);
                case 0x68, 0x69, 0x6A, 0x6B -> stepBinaryArith(BinaryOperator.MUL, BytecodeSsaLifter.typeForArithOpcode(opcode - 0x68), pc, opcode, frame);
                case 0x6C, 0x6D, 0x6E, 0x6F -> stepBinaryArith(BinaryOperator.DIV, BytecodeSsaLifter.typeForArithOpcode(opcode - 0x6C), pc, opcode, frame);
                case 0x70, 0x71, 0x72, 0x73 -> stepBinaryArith(BinaryOperator.REM, BytecodeSsaLifter.typeForArithOpcode(opcode - 0x70), pc, opcode, frame);
                case 0x74, 0x75, 0x76, 0x77 -> {
                    ConceptType t = BytecodeSsaLifter.typeForArithOpcode(opcode - 0x74);
                    ConceptExpr operand = asScalarExpr(frame.pop(), t, pc, opcode);
                    frame.push(new SymVal.Scalar(foldSimpleUnary(UnaryOperator.NEG, operand, t)));
                }
                case 0x78 -> stepShift(BinaryOperator.SHL, ConceptType.I32, pc, opcode, frame);
                case 0x79 -> stepShift(BinaryOperator.SHL, ConceptType.I64, pc, opcode, frame);
                case 0x7A -> stepShift(BinaryOperator.SHR, ConceptType.I32, pc, opcode, frame);
                case 0x7B -> stepShift(BinaryOperator.SHR, ConceptType.I64, pc, opcode, frame);
                case 0x7C -> stepShift(BinaryOperator.USHR, ConceptType.I32, pc, opcode, frame);
                case 0x7D -> stepShift(BinaryOperator.USHR, ConceptType.I64, pc, opcode, frame);
                case 0x7E -> stepBinaryArith(BinaryOperator.BIT_AND, ConceptType.I32, pc, opcode, frame);
                case 0x7F -> stepBinaryArith(BinaryOperator.BIT_AND, ConceptType.I64, pc, opcode, frame);
                case 0x80 -> stepBinaryArith(BinaryOperator.BIT_OR, ConceptType.I32, pc, opcode, frame);
                case 0x81 -> stepBinaryArith(BinaryOperator.BIT_OR, ConceptType.I64, pc, opcode, frame);
                case 0x82 -> stepBinaryArith(BinaryOperator.BIT_XOR, ConceptType.I32, pc, opcode, frame);
                case 0x83 -> stepBinaryArith(BinaryOperator.BIT_XOR, ConceptType.I64, pc, opcode, frame);
                case 0x84 -> {
                    int slot = code[pc + 1] & 0xFF;
                    int delta = code[pc + 2];
                    stepIinc(slot, delta, pc, frame, out);
                }

                // Primitive conversions
                case 0x85 -> stepCast(ConceptType.I32, ConceptType.I64, pc, opcode, frame);
                case 0x86 -> stepCast(ConceptType.I32, ConceptType.F32, pc, opcode, frame);
                case 0x87 -> stepCast(ConceptType.I32, ConceptType.F64, pc, opcode, frame);
                case 0x88 -> stepCast(ConceptType.I64, ConceptType.I32, pc, opcode, frame);
                case 0x89 -> stepCast(ConceptType.I64, ConceptType.F32, pc, opcode, frame);
                case 0x8A -> stepCast(ConceptType.I64, ConceptType.F64, pc, opcode, frame);
                case 0x8B -> stepCast(ConceptType.F32, ConceptType.I32, pc, opcode, frame);
                case 0x8C -> stepCast(ConceptType.F32, ConceptType.I64, pc, opcode, frame);
                case 0x8D -> stepCast(ConceptType.F32, ConceptType.F64, pc, opcode, frame);
                case 0x8E -> stepCast(ConceptType.F64, ConceptType.I32, pc, opcode, frame);
                case 0x8F -> stepCast(ConceptType.F64, ConceptType.I64, pc, opcode, frame);
                case 0x90 -> stepCast(ConceptType.F64, ConceptType.F32, pc, opcode, frame);
                case 0x91 -> { // I2B
                    ConceptExpr v = asScalarExpr(frame.pop(), ConceptType.I32, pc, opcode);
                    ConceptExpr shl = foldSimpleBinary(BinaryOperator.SHL, v, new ConceptExpr.IntConst(24), ConceptType.I32);
                    frame.push(new SymVal.Scalar(foldSimpleBinary(BinaryOperator.SHR, shl, new ConceptExpr.IntConst(24), ConceptType.I32)));
                }
                case 0x92 -> { // I2C
                    ConceptExpr v = asScalarExpr(frame.pop(), ConceptType.I32, pc, opcode);
                    frame.push(new SymVal.Scalar(foldSimpleBinary(BinaryOperator.BIT_AND, v, new ConceptExpr.IntConst(0xFFFF), ConceptType.I32)));
                }
                case 0x93 -> { // I2S
                    ConceptExpr v = asScalarExpr(frame.pop(), ConceptType.I32, pc, opcode);
                    ConceptExpr shl = foldSimpleBinary(BinaryOperator.SHL, v, new ConceptExpr.IntConst(16), ConceptType.I32);
                    frame.push(new SymVal.Scalar(foldSimpleBinary(BinaryOperator.SHR, shl, new ConceptExpr.IntConst(16), ConceptType.I32)));
                }

                // Comparisons
                case 0x94 -> {
                    SymVal rSym = frame.pop();
                    SymVal lSym = frame.pop();
                    if (lSym instanceof SymVal.ColdCacheTagVal || rSym instanceof SymVal.ColdCacheTagVal) {
                        // Cold memoization probe: tag never equals runtime key
                        frame.push(new SymVal.Scalar(new ConceptExpr.IntConst(1)));
                    } else {
                        ConceptExpr r = asScalarExpr(rSym, ConceptType.I64, pc, opcode);
                        ConceptExpr l = asScalarExpr(lSym, ConceptType.I64, pc, opcode);
                        frame.push(new SymVal.CompareTemp(l, r, false));
                    }
                }
                case 0x95, 0x96 -> {
                    ConceptExpr r = asScalarExpr(frame.pop(), ConceptType.F32, pc, opcode);
                    ConceptExpr l = asScalarExpr(frame.pop(), ConceptType.F32, pc, opcode);
                    frame.push(new SymVal.CompareTemp(l, r, opcode == 0x96));
                }
                case 0x97, 0x98 -> {
                    ConceptExpr r = asScalarExpr(frame.pop(), ConceptType.F64, pc, opcode);
                    ConceptExpr l = asScalarExpr(frame.pop(), ConceptType.F64, pc, opcode);
                    frame.push(new SymVal.CompareTemp(l, r, opcode == 0x98));
                }

                // Field accesses
                case 0xB2 -> {
                    FieldRef fRef = methodOwner.resolveFieldRef(BytecodeSsaLifter.readUnsignedShort(code, pc + 1));
                    SymVal resolved = ctx.resolveStaticField(
                            fRef,
                            concreteCls != null ? concreteCls.thisClass : methodOwner.thisClass,
                            method.name(),
                            pc);
                    if (resolved == null) {
                        pure = false;
                        frame.terminated = true;
                    } else {
                        frame.push(resolved);
                    }
                }
                case 0xB3 -> {
                    markImpure(pc, opcode, "field mutation side effect");
                    frame.terminated = true;
                }
                case 0xB5 -> stepPutField(code, pc, frame, out);
                case 0xB4 -> stepGetField(code, pc, frame);

                // Method invocations
                case 0xB6, 0xB7, 0xB8, 0xB9 -> stepInvoke(opcode, pc, frame, out);
                case 0xBA -> stepInvokeDynamic(code, pc, frame, out);

                // Allocations, arraylength, casts
                case 0xBB -> {
                    int cpIdx = BytecodeSsaLifter.readUnsignedShort(code, pc + 1);
                    String targetInternal = methodOwner.resolveClassName(cpIdx);
                    if ("java/util/ArrayList".equals(targetInternal)) {
                        frame.push(new SymVal.LocalListRef(ctx.nextLocalAllocId++, new ArrayList<>()));
                    } else if ("java/util/concurrent/atomic/AtomicLong".equals(targetInternal)) {
                        Map<String, SymVal> fields = new LinkedHashMap<>();
                        fields.put("value", new SymVal.Scalar(new ConceptExpr.LongConst(0L)));
                        frame.push(new SymVal.LocalObjectRef(ctx.nextLocalAllocId++, targetInternal, fields));
                    } else if ("java/util/concurrent/atomic/AtomicInteger".equals(targetInternal)) {
                        Map<String, SymVal> fields = new LinkedHashMap<>();
                        fields.put("value", new SymVal.Scalar(new ConceptExpr.IntConst(0)));
                        frame.push(new SymVal.LocalObjectRef(ctx.nextLocalAllocId++, targetInternal, fields));
                    } else if (targetInternal == null || BytecodeSsaLifter.isSystemOwner(targetInternal)) {
                        markImpure(pc, opcode, "heap allocation inside compute kernel");
                        frame.terminated = true;
                    } else {
                        ctx.resolveClass(targetInternal);
                        frame.push(new SymVal.LocalObjectRef(
                                ctx.nextLocalAllocId++, targetInternal, new LinkedHashMap<>()));
                    }
                }
                case 0xBC -> stepNewPrimitiveArray(code, pc, frame, out);
                case 0xBD -> stepNewObjectArray(code, pc, frame);
                case 0xC5 -> {
                    markImpure(pc, opcode, "heap allocation inside compute kernel");
                    frame.terminated = true;
                }
                case 0xBE -> stepArrayLength(pc, frame);
                case 0xC0 -> { /* CHECKCAST: concrete types tracked symbolically */ }
                case 0xC1 -> stepInstanceOf(code, pc, frame);
                case 0xC2, 0xC3 -> {
                    markImpure(pc, opcode, "monitor synchronization");
                    frame.terminated = true;
                }
                case 0xC4 -> stepWideInstruction(code, pc, frame, out);
                default -> {
                    markImpure(pc, opcode, "unsupported opcode 0x" + Integer.toHexString(opcode));
                    frame.terminated = true;
                }
            }
        }

        private SymVal pushLdcConstant(int cpIdx) {
            Object raw = BytecodeSsaLifter.resolveConstantValue(methodOwner, cpIdx);
            if (raw instanceof String s) {
                return new SymVal.StringConst(s);
            }
            return new SymVal.Scalar(BytecodeSsaLifter.constantToExpr(methodOwner, cpIdx));
        }

        private void stepWideInstruction(byte[] code, int pc, SymFrame frame, List<ConceptStmt> out) {
            int subOp = code[pc + 1] & 0xFF;
            int slot = BytecodeSsaLifter.readUnsignedShort(code, pc + 2);
            if (subOp == 0x84) {
                int delta = BytecodeSsaLifter.readSignedShort(code, pc + 4);
                stepIinc(slot, delta, pc, frame, out);
            } else if (subOp >= 0x15 && subOp <= 0x19) {
                loadLocal(frame, slot, subOp, pc);
            } else if (subOp >= 0x36 && subOp <= 0x3A) {
                storeLocal(frame, slot, subOp, pc, out);
            } else {
                markImpure(pc, 0xC4, "unsupported wide opcode");
                frame.terminated = true;
            }
        }

        private void loadLocal(SymFrame frame, int slot, int loadFamilyOp, int pc) {
            SymVal val = frame.locals.get(slot);
            if (val == null) {
                ConceptType fallbackType = switch (loadFamilyOp) {
                    case 0x16 -> ConceptType.I64;
                    case 0x17 -> ConceptType.F32;
                    case 0x18 -> ConceptType.F64;
                    default -> ConceptType.I32;
                };
                frame.push(new SymVal.Scalar(BytecodeSsaLifter.defaultConstForType(fallbackType)));
                return;
            }
            frame.push(val);
        }

        private void storeLocal(SymFrame frame, int slot, int storeFamilyOp, int pc, List<ConceptStmt> out) {
            SymVal val = frame.pop();
            if (storeFamilyOp == 0x3A) {
                if (val instanceof SymVal.InternedScalar is && !isConstOrVar(is.expr())) {
                    String localName = resolveLocalSlotVarName(slot, pc, ConceptType.I32);
                    out.add(new ConceptStmt.Assign(localName, ConceptType.I32, is.expr()));
                    frame.locals.put(slot, new SymVal.InternedScalar(new ConceptExpr.Var(localName, ConceptType.I32)));
                } else {
                    frame.locals.put(slot, val);
                }
                return;
            }

            ConceptType targetType = switch (storeFamilyOp) {
                case 0x37 -> ConceptType.I64;
                case 0x38 -> ConceptType.F32;
                case 0x39 -> ConceptType.F64;
                default -> ConceptType.I32;
            };
            ConceptExpr expr = asScalarExpr(val, targetType, pc, storeFamilyOp);
            if (isCompileTimeScalarConst(expr)) {
                // Keep exact constant in frame AND emit local assignment if needed by downstream readers
                String localName = resolveLocalSlotVarName(slot, pc, targetType);
                out.add(new ConceptStmt.Assign(localName, targetType, expr));
                frame.locals.put(slot, new SymVal.Scalar(expr));
            } else {
                String localName = resolveLocalSlotVarName(slot, pc, targetType);
                out.add(new ConceptStmt.Assign(localName, targetType, expr));
                frame.locals.put(slot, new SymVal.Scalar(new ConceptExpr.Var(localName, targetType)));
            }
        }

        private void stepIinc(int slot, int delta, int pc, SymFrame frame, List<ConceptStmt> out) {
            SymVal cur = frame.locals.getOrDefault(slot, new SymVal.Scalar(new ConceptExpr.IntConst(0)));
            ConceptExpr curExpr = asScalarExpr(cur, ConceptType.I32, pc, 0x84);
            ConceptExpr nextExpr = foldSimpleBinary(
                    BinaryOperator.ADD, curExpr, new ConceptExpr.IntConst(delta), ConceptType.I32);
            String localName = resolveLocalSlotVarName(slot, pc, ConceptType.I32);
            out.add(new ConceptStmt.Assign(localName, ConceptType.I32, nextExpr));
            if (isCompileTimeScalarConst(nextExpr)) {
                frame.locals.put(slot, new SymVal.Scalar(nextExpr));
            } else {
                frame.locals.put(slot, new SymVal.Scalar(new ConceptExpr.Var(localName, ConceptType.I32)));
            }
        }

        private void stepGetField(byte[] code, int pc, SymFrame frame) {
            FieldRef fRef = methodOwner.resolveFieldRef(BytecodeSsaLifter.readUnsignedShort(code, pc + 1));
            SymVal recv = frame.pop();
            if (recv instanceof SymVal.ContextRef cr) {
                if (ctx.interner.isColdMemoContextField(
                        cr.entryParamIndex(), fRef.owner(), fRef.name(), fRef.descriptor())) {
                    frame.push(new SymVal.ColdCacheTagVal(ConceptType.fromDescriptorPrefix(fRef.descriptor())));
                    return;
                }
                ContextParamBinding binding = ctx.interner.resolveContextField(
                        cr.entryParamIndex(), fRef.owner(), fRef.name(), fRef.descriptor());
                if (binding != null) {
                    recordContextUse(cr.entryParamIndex(), binding);
                    localTypes.putIfAbsent(binding.paramName(), binding.paramType());
                    ConceptExpr varExpr = new ConceptExpr.Var(binding.paramName(), binding.paramType());
                    if (fRef.descriptor().startsWith("L")) {
                        frame.push(new SymVal.InternedScalar(varExpr));
                    } else {
                        frame.push(new SymVal.Scalar(
                                coerceScalar(varExpr, ConceptType.fromDescriptorPrefix(fRef.descriptor()))));
                    }
                    return;
                }
                Object resolvedObj = ctx.interner.resolveContextCallResult(
                        cr.entryParamIndex(), fRef.owner(), fRef.name(), fRef.descriptor(), new Object[0]);
                if (resolvedObj != null) {
                    frame.push(captureResolvedContextObject(resolvedObj));
                    return;
                }
                if (cr.backingInstanceId() >= 0) {
                    SymVal fallback = ctx.resolveFieldValue(
                            new SymVal.InstanceRef(cr.backingInstanceId()),
                            fRef,
                            concreteCls != null ? concreteCls.thisClass : methodOwner.thisClass,
                            method.name(),
                            pc,
                            0xB4);
                    if (fallback != null) {
                        frame.push(fallback);
                        return;
                    }
                    pure = false;
                    frame.terminated = true;
                    return;
                }
                markImpure(pc, 0xB4, "unmapped context field " + fRef.owner() + "#" + fRef.name());
                frame.terminated = true;
                return;
            }
            if (recv instanceof SymVal.InstanceRef ir && frame.coldMemoInstances.contains(ir.instanceId())) {
                SymVal cached = frame.coldMemoFields.get(ir.instanceId() + "#" + fRef.name());
                if (cached != null) {
                    frame.push(cached);
                    return;
                }
            }
            SymVal val = ctx.resolveFieldValue(
                    recv,
                    fRef,
                    concreteCls != null ? concreteCls.thisClass : methodOwner.thisClass,
                    method.name(),
                    pc,
                    0xB4);
            if (val == null) {
                pure = false;
                frame.terminated = true;
            } else {
                frame.push(val);
            }
        }

        private SymVal captureResolvedContextObject(Object resolvedObj) {
            if (resolvedObj == BytecodeSsaLifter.ObjectInterner.NULL_CONTEXT_VALUE) {
                return SymVal.NullRef.INSTANCE;
            }
            if (resolvedObj instanceof BytecodeSsaLifter.ContextBoundMethodDelegate del) {
                SymVal capturedTarget = captureResolvedContextObject(del.targetInstance());
                if (capturedTarget instanceof SymVal.InstanceRef ir) {
                    return new SymVal.BoundDelegateRef(
                            ir.instanceId(),
                            del.targetMethodName(),
                            del.xParamName(),
                            del.yParamNameOrNull(),
                            del.zParamName());
                }
            }
            if (resolvedObj instanceof SymVal sv) {
                return sv;
            }
            ConceptExpr prim = BytecodeSsaLifter.primitiveToConstExpr(resolvedObj);
            if (prim != null) {
                return new SymVal.Scalar(prim);
            }
            if (resolvedObj instanceof String s) {
                return new SymVal.StringConst(s);
            }
            int capturedId = ctx.captureLiveObjectOnDemand(resolvedObj);
            return ctx.instanceIdToSymVal(capturedId);
        }

        private void stepPutField(byte[] code, int pc, SymFrame frame, List<ConceptStmt> out) {
            FieldRef fRef = methodOwner.resolveFieldRef(BytecodeSsaLifter.readUnsignedShort(code, pc + 1));
            SymVal val = frame.pop();
            SymVal recv = frame.pop();
            if (recv instanceof SymVal.LocalObjectRef lor) {
                if (val instanceof SymVal.ColdCacheTagVal) {
                    lor.fields().put(fRef.owner() + "#" + fRef.name(), val);
                    lor.fields().put(fRef.name(), val);
                    return;
                }
                char c0 = fRef.descriptor().charAt(0);
                SymVal stored = val;
                if (c0 != 'L' && c0 != '[') {
                    ConceptType fType = ConceptType.fromDescriptorPrefix(fRef.descriptor());
                    ConceptExpr expr = asScalarExpr(val, fType, pc, 0xB5);
                    if (isCompileTimeScalarConst(expr)) {
                        stored = new SymVal.Scalar(expr);
                    } else {
                        String varName = "_loc_obj_" + lor.allocId() + "_"
                                + BytecodeSsaLifter.sanitizeIdentifier(fRef.name());
                        localTypes.putIfAbsent(varName, fType);
                        out.add(new ConceptStmt.Assign(varName, fType, expr));
                        stored = new SymVal.Scalar(new ConceptExpr.Var(varName, fType));
                    }
                }
                lor.fields().put(fRef.owner() + "#" + fRef.name(), stored);
                lor.fields().put(fRef.name(), stored);
                return;
            }
            if (recv instanceof SymVal.ColdMemoCacheRef) {
                return;
            }
            if (recv instanceof SymVal.InstanceRef ir) {
                if (val instanceof SymVal.ColdCacheTagVal) {
                    frame.coldMemoInstances.add(ir.instanceId());
                    frame.coldMemoFields.put(ir.instanceId() + "#" + fRef.name(), val);
                    return;
                }
                if (frame.coldMemoInstances.contains(ir.instanceId())) {
                    frame.coldMemoFields.put(ir.instanceId() + "#" + fRef.name(), val);
                    return;
                }
            }
            markImpure(pc, 0xB5, "field mutation side effect");
            frame.terminated = true;
        }

        private void stepNewObjectArray(byte[] code, int pc, SymFrame frame) {
            int cpIdx = BytecodeSsaLifter.readUnsignedShort(code, pc + 1);
            String elemInternal = methodOwner.resolveClassName(cpIdx);
            ConceptExpr lenExpr = asScalarExpr(frame.pop(), ConceptType.I32, pc, 0xBD);
            Integer constLen = tryEvalConstInt(lenExpr);
            if (constLen == null || constLen < 0 || constLen > 256) {
                markImpure(pc, 0xBD, "non-constant or oversized local object array allocation");
                frame.terminated = true;
                return;
            }
            List<SymVal> elems = new ArrayList<>(constLen);
            for (int i = 0; i < constLen; i++) {
                elems.add(SymVal.NullRef.INSTANCE);
            }
            frame.push(new SymVal.LocalObjectArrayRef(ctx.nextLocalAllocId++, elemInternal, elems));
        }

        private void stepNewPrimitiveArray(byte[] code, int pc, SymFrame frame, List<ConceptStmt> out) {
            int atype = code[pc + 1] & 0xFF;
            ConceptExpr lenExpr = asScalarExpr(frame.pop(), ConceptType.I32, pc, 0xBC);
            Integer constLen = tryEvalConstInt(lenExpr);
            if (constLen == null || constLen < 0 || constLen > 256) {
                markImpure(pc, 0xBC, "non-constant or oversized local array allocation");
                frame.terminated = true;
                return;
            }
            ConceptType elemType = switch (atype) {
                case 4 -> ConceptType.BOOL;
                case 6 -> ConceptType.F32;
                case 7 -> ConceptType.F64;
                case 11 -> ConceptType.I64;
                default -> ConceptType.I32;
            };
            int allocId = ctx.nextLocalAllocId++;
            List<String> slotVars = new ArrayList<>(constLen);
            ConceptExpr defVal = BytecodeSsaLifter.defaultConstForType(elemType);
            for (int i = 0; i < constLen; i++) {
                String slotName = "_loc_arr_" + allocId + "_" + i;
                localTypes.put(slotName, elemType);
                out.add(new ConceptStmt.Assign(slotName, elemType, defVal));
                slotVars.add(slotName);
            }
            frame.push(new SymVal.LocalArrayRef(allocId, elemType, constLen, slotVars));
        }

        private void stepArrayStore(int opcode, int pc, SymFrame frame, List<ConceptStmt> out) {
            SymVal valSym = frame.pop();
            ConceptExpr idxExpr = asScalarExpr(frame.pop(), ConceptType.I32, pc, opcode);
            SymVal arrSym = frame.pop();
            if (arrSym instanceof SymVal.ColdMemoCacheRef) {
                return;
            }
            if (arrSym instanceof SymVal.LocalObjectArrayRef loar && opcode == 0x53) {
                Integer constIdx = tryEvalConstInt(idxExpr);
                if (constIdx != null && constIdx >= 0 && constIdx < loar.elements().size()) {
                    loar.elements().set(constIdx, valSym);
                    return;
                }
                markImpure(pc, opcode, "non-constant or out-of-bounds local object array store");
                frame.terminated = true;
                return;
            }
            if (arrSym instanceof SymVal.LocalArrayRef lar && opcode != 0x53) {
                ConceptExpr valExpr = asScalarExpr(valSym, lar.elemType(), pc, opcode);
                Integer constIdx = tryEvalConstInt(idxExpr);
                if (constIdx != null) {
                    if (constIdx >= 0 && constIdx < lar.length()) {
                        out.add(new ConceptStmt.Assign(lar.slotVars().get(constIdx), lar.elemType(), valExpr));
                        return;
                    }
                    markImpure(pc, opcode, "local array store index out of bounds: " + constIdx);
                    frame.terminated = true;
                    return;
                }
                if (lar.length() <= 64) {
                    ConceptExpr idxSimple = ensureSimpleExpr(idxExpr, pc, out);
                    ConceptExpr valSimple = ensureSimpleExpr(valExpr, pc, out);
                    List<ConceptStmt> elseChain = List.of();
                    for (int i = lar.length() - 1; i >= 0; i--) {
                        ConceptStmt assign = new ConceptStmt.Assign(
                                lar.slotVars().get(i), lar.elemType(), valSimple);
                        if (i == lar.length() - 1 && elseChain.isEmpty()) {
                            elseChain = List.of(assign);
                        } else {
                            ConceptExpr eq = new ConceptExpr.BinaryOp(
                                    BinaryOperator.EQ, idxSimple, new ConceptExpr.IntConst(i), ConceptType.BOOL);
                            elseChain = List.of(new ConceptStmt.If(eq, List.of(assign), elseChain));
                        }
                    }
                    out.addAll(elseChain);
                    return;
                }
            }
            markImpure(pc, opcode, "array element store side effect");
            frame.terminated = true;
        }

        private void stepInvokeDynamic(byte[] code, int pc, SymFrame frame, List<ConceptStmt> out) {
            int cpIdx = BytecodeSsaLifter.readUnsignedShort(code, pc + 1);
            InvokeDynamicEntry indy = methodOwner.resolveInvokeDynamic(cpIdx);
            if (indy != null
                    && indy.bootstrapMethodAttrIndex() >= 0
                    && indy.bootstrapMethodAttrIndex() < methodOwner.bootstrapMethods.size()) {
                BootstrapMethodEntry bsm = methodOwner.bootstrapMethods.get(indy.bootstrapMethodAttrIndex());
                MethodHandleEntry bsmMh = (bsm.methodHandleCpIdx() > 0
                        && bsm.methodHandleCpIdx() < methodOwner.methodHandles.length)
                        ? methodOwner.methodHandles[bsm.methodHandleCpIdx()]
                        : null;
                if (bsmMh != null) {
                    MethodRef bsmRef = methodOwner.resolveMethodRef(bsmMh.referenceIndex());
                    List<String> paramDescs = splitMethodParamDescriptors(indy.descriptor());
                    List<SymVal> dynArgs = new ArrayList<>(paramDescs.size());
                    for (int i = 0; i < paramDescs.size(); i++) {
                        dynArgs.add(frame.pop());
                    }
                    Collections.reverse(dynArgs);

                    if ("java/lang/invoke/LambdaMetafactory".equals(bsmRef.owner())
                            && ("metafactory".equals(bsmRef.name()) || "altMetafactory".equals(bsmRef.name()))
                            && bsm.argumentCpIndices().length >= 2) {
                        int implMhIdx = bsm.argumentCpIndices()[1];
                        MethodHandleEntry implMh = (implMhIdx > 0 && implMhIdx < methodOwner.methodHandles.length)
                                ? methodOwner.methodHandles[implMhIdx]
                                : null;
                        if (implMh != null) {
                            MethodRef implRef = methodOwner.resolveMethodRef(implMh.referenceIndex());
                            frame.push(new SymVal.LambdaClosure(
                                    indy.name(),
                                    implRef.owner(),
                                    implRef.name(),
                                    implRef.descriptor(),
                                    implMh.referenceKind(),
                                    dynArgs));
                            return;
                        }
                    }

                    if ("java/lang/runtime/SwitchBootstraps".equals(bsmRef.owner())
                            && "typeSwitch".equals(bsmRef.name())
                            && dynArgs.size() == 2) {
                        SymVal selector = dynArgs.get(0);
                        Integer restartIdx = tryEvalConstInt(asScalarExpr(dynArgs.get(1), ConceptType.I32, pc, 0xBA));
                        int startIdx = restartIdx != null ? Math.max(0, restartIdx) : 0;
                        if (selector instanceof SymVal.NullRef) {
                            frame.push(new SymVal.Scalar(new ConceptExpr.IntConst(-1)));
                            return;
                        }
                        String selClass = null;
                        if (selector instanceof SymVal.InstanceRef ir) {
                            InstanceNode n = ctx.lookupInstance(ir.instanceId());
                            if (n != null) {
                                selClass = n.internalClassName();
                            }
                        } else if (selector instanceof SymVal.LocalObjectRef lor) {
                            selClass = lor.internalClassName();
                        }
                        if (selClass != null) {
                            int matched = bsm.argumentCpIndices().length;
                            for (int k = startIdx; k < bsm.argumentCpIndices().length; k++) {
                                String caseClass = methodOwner.resolveClassName(bsm.argumentCpIndices()[k]);
                                if (caseClass != null && isSubtypeOf(selClass, caseClass)) {
                                    matched = k;
                                    break;
                                }
                            }
                            frame.push(new SymVal.Scalar(new ConceptExpr.IntConst(matched)));
                            return;
                        }
                    }
                }
            }
            markImpure(pc, 0xBA, "invokedynamic dispatch");
            frame.terminated = true;
        }

        private void stepPrimitiveArrayLoad(int opcode, int pc, SymFrame frame) {
            ConceptExpr idx = asScalarExpr(frame.pop(), ConceptType.I32, pc, opcode);
            SymVal arrVal = frame.pop();
            ConceptType elemType = switch (opcode) {
                case 0x2F -> ConceptType.I64;
                case 0x30 -> ConceptType.F32;
                case 0x31 -> ConceptType.F64;
                default -> ConceptType.I32;
            };
            if (arrVal instanceof SymVal.Array1DRef a1) {
                ConceptExpr loaded = evalOrRegisterLutLoad(a1.lut(), idx);
                frame.push(new SymVal.Scalar(coerceScalar(loaded, elemType)));
            } else if (arrVal instanceof SymVal.Array2DRowRef row) {
                ConceptExpr strideExpr = foldSimpleBinary(
                        BinaryOperator.MUL,
                        row.rowIndex(),
                        new ConceptExpr.IntConst(row.rowWidth()),
                        ConceptType.I32);
                ConceptExpr flatIdx = foldSimpleBinary(BinaryOperator.ADD, strideExpr, idx, ConceptType.I32);
                ConceptExpr loaded = evalOrRegisterLutLoad(row.lut(), flatIdx);
                frame.push(new SymVal.Scalar(coerceScalar(loaded, elemType)));
            } else if (arrVal instanceof SymVal.LocalArrayRef lar) {
                Integer constIdx = tryEvalConstInt(idx);
                if (constIdx != null && constIdx >= 0 && constIdx < lar.length()) {
                    ConceptExpr slotExpr = new ConceptExpr.Var(lar.slotVars().get(constIdx), lar.elemType());
                    frame.push(new SymVal.Scalar(coerceScalar(slotExpr, elemType)));
                } else if (constIdx == null && lar.length() > 0 && lar.length() <= 64) {
                    ConceptExpr muxExpr = new ConceptExpr.Var(lar.slotVars().get(lar.length() - 1), lar.elemType());
                    for (int i = lar.length() - 2; i >= 0; i--) {
                        ConceptExpr eq = new ConceptExpr.BinaryOp(
                                BinaryOperator.EQ, idx, new ConceptExpr.IntConst(i), ConceptType.BOOL);
                        muxExpr = new ConceptExpr.Ternary(
                                eq,
                                new ConceptExpr.Var(lar.slotVars().get(i), lar.elemType()),
                                muxExpr,
                                lar.elemType());
                    }
                    frame.push(new SymVal.Scalar(coerceScalar(muxExpr, elemType)));
                } else {
                    markImpure(pc, opcode, "local array load out of bounds");
                    frame.terminated = true;
                }
            } else if (arrVal instanceof SymVal.ColdMemoCacheRef) {
                frame.push(new SymVal.ColdCacheTagVal(elemType));
            } else {
                markImpure(pc, opcode, "primitive array load on non-snapshotted array");
                frame.terminated = true;
            }
        }

        private void stepObjectArrayLoad(int pc, SymFrame frame) {
            ConceptExpr idx = asScalarExpr(frame.pop(), ConceptType.I32, pc, 0x32);
            SymVal arrVal = frame.pop();
            if (arrVal instanceof SymVal.Array2DRef a2) {
                frame.push(new SymVal.Array2DRowRef(a2.lut(), a2.rowWidth(), idx));
                return;
            }
            if (arrVal instanceof SymVal.LocalObjectArrayRef loar) {
                Integer constIdx = tryEvalConstInt(idx);
                if (constIdx != null && constIdx >= 0 && constIdx < loar.elements().size()) {
                    frame.push(loar.elements().get(constIdx));
                    return;
                }
                markImpure(pc, 0x32, "non-constant index into local Object[]");
                frame.terminated = true;
                return;
            }
            if (arrVal instanceof SymVal.CollectionRef coll) {
                Integer constIdx = tryEvalConstInt(idx);
                if (constIdx != null && constIdx >= 0 && constIdx < coll.elementIds().size()) {
                    frame.push(ctx.instanceIdToSymVal(coll.elementIds().get(constIdx)));
                    return;
                }
                ConceptLut internedLut = ctx.tryBuildInternedElementLut(coll);
                if (internedLut != null) {
                    ConceptExpr loaded = evalOrRegisterLutLoad(internedLut, idx);
                    frame.push(new SymVal.InternedScalar(coerceScalar(loaded, ConceptType.I32)));
                    return;
                }
                if (!coll.soaLuts().isEmpty()
                        && coll.elementInternalClass() != null
                        && !hasNonScalarElementFields(coll)) {
                    frame.push(new SymVal.SoaElemRef(
                            coll.instanceId(), coll.elementInternalClass(), coll.soaLuts(), idx));
                    return;
                }
                if (!coll.elementIds().isEmpty() && coll.elementIds().size() <= ctx.options.maxLoopUnrollLength()) {
                    if (coll.elementIds().size() == 1) {
                        frame.push(ctx.instanceIdToSymVal(coll.elementIds().get(0)));
                    } else {
                        frame.push(new SymVal.MuxInstanceRef(idx, coll.elementIds()));
                    }
                    return;
                }
                markImpure(pc, 0x32, "dynamic index into heterogeneous Object[]");
                frame.terminated = true;
                return;
            }
            markImpure(pc, 0x32, "aaload on uncaptured array reference");
            frame.terminated = true;
        }

        private boolean hasNonScalarElementFields(SymVal.CollectionRef coll) {
            for (int id : coll.elementIds()) {
                if (id < 0) {
                    continue;
                }
                InstanceNode elem = ctx.lookupInstance(id);
                if (elem != null && (!elem.arrayLuts().isEmpty() || !elem.objectFieldRefs().isEmpty())) {
                    return true;
                }
            }
            return false;
        }

        private void stepArrayLength(int pc, SymFrame frame) {
            SymVal arrVal = frame.pop();
            if (arrVal instanceof SymVal.Array1DRef a1) {
                frame.push(new SymVal.Scalar(new ConceptExpr.IntConst(a1.length())));
            } else if (arrVal instanceof SymVal.Array2DRef a2) {
                frame.push(new SymVal.Scalar(new ConceptExpr.IntConst(a2.outerLength())));
            } else if (arrVal instanceof SymVal.Array2DRowRef r2) {
                frame.push(new SymVal.Scalar(new ConceptExpr.IntConst(r2.rowWidth())));
            } else if (arrVal instanceof SymVal.CollectionRef coll) {
                frame.push(new SymVal.Scalar(new ConceptExpr.IntConst(coll.elementIds().size())));
            } else if (arrVal instanceof SymVal.LocalArrayRef lar) {
                frame.push(new SymVal.Scalar(new ConceptExpr.IntConst(lar.length())));
            } else if (arrVal instanceof SymVal.LocalObjectArrayRef loar) {
                frame.push(new SymVal.Scalar(new ConceptExpr.IntConst(loar.elements().size())));
            } else {
                markImpure(pc, 0xBE, "arraylength on uncaptured array");
                frame.terminated = true;
            }
        }

        private void stepInstanceOf(byte[] code, int pc, SymFrame frame) {
            int cpIdx = BytecodeSsaLifter.readUnsignedShort(code, pc + 1);
            String targetInternal = (cpIdx > 0 && cpIdx < methodOwner.classNameIdx.length)
                    ? methodOwner.utf8Pool[methodOwner.classNameIdx[cpIdx]]
                    : null;
            SymVal obj = frame.pop();
            if (obj instanceof SymVal.NullRef) {
                frame.push(new SymVal.Scalar(new ConceptExpr.BoolConst(false)));
                return;
            }
            if (obj instanceof SymVal.InstanceRef ir) {
                InstanceNode node = ctx.lookupInstance(ir.instanceId());
                if (node != null && isSubtypeOf(node.internalClassName(), targetInternal)) {
                    frame.push(new SymVal.Scalar(new ConceptExpr.BoolConst(true)));
                    return;
                }
                if (node != null && ctx.resolveClass(targetInternal) != null) {
                    frame.push(new SymVal.Scalar(new ConceptExpr.BoolConst(false)));
                    return;
                }
            }
            if (obj instanceof SymVal.LocalObjectRef lor) {
                if (isSubtypeOf(lor.internalClassName(), targetInternal)) {
                    frame.push(new SymVal.Scalar(new ConceptExpr.BoolConst(true)));
                    return;
                }
                if (ctx.resolveClass(targetInternal) != null) {
                    frame.push(new SymVal.Scalar(new ConceptExpr.BoolConst(false)));
                    return;
                }
            }
            markImpure(pc, 0xC1, "unresolved instanceof " + targetInternal);
            frame.terminated = true;
        }

        private boolean isSubtypeOf(String subInternal, String targetInternal) {
            if (subInternal == null || targetInternal == null) {
                return false;
            }
            if (subInternal.equals(targetInternal) || "java/lang/Object".equals(targetInternal)) {
                return true;
            }
            RawClassFile cls = ctx.resolveClass(subInternal);
            if (cls == null) {
                return false;
            }
            if (cls.superClass != null && isSubtypeOf(cls.superClass, targetInternal)) {
                return true;
            }
            for (String iface : cls.interfaces) {
                if (isSubtypeOf(iface, targetInternal)) {
                    return true;
                }
            }
            return false;
        }

        private void stepInvoke(int opcode, int pc, SymFrame frame, List<ConceptStmt> out) {
            MethodRef mRef = methodOwner.resolveMethodRef(BytecodeSsaLifter.readUnsignedShort(method.code(), pc + 1));
            List<String> paramDescs = splitMethodParamDescriptors(mRef.descriptor());
            String retDesc = returnDescriptorSuffix(mRef.descriptor());
            ConceptType retType = ConceptType.fromDescriptorPrefix(retDesc);

            List<SymVal> callArgs = new ArrayList<>(paramDescs.size());
            for (int i = 0; i < paramDescs.size(); i++) {
                callArgs.add(frame.pop());
            }
            Collections.reverse(callArgs);

            if (opcode == 0xB8) {
                stepStaticInvoke(mRef, paramDescs, retDesc, retType, callArgs, pc, frame, out);
                return;
            }

            SymVal recv = frame.pop();
            stepInstanceInvoke(opcode, mRef, recv, paramDescs, retDesc, retType, callArgs, pc, frame, out);
        }

        private void stepStaticInvoke(
                MethodRef mRef,
                List<String> paramDescs,
                String retDesc,
                ConceptType retType,
                List<SymVal> callArgs,
                int pc,
                SymFrame frame,
                List<ConceptStmt> out) {
            // 1. java.util.Objects pure guards
            if ("java/util/Objects".equals(mRef.owner())) {
                if ("requireNonNull".equals(mRef.name()) && !callArgs.isEmpty()) {
                    SymVal arg0 = callArgs.get(0);
                    if (arg0 instanceof SymVal.NullRef || arg0 instanceof SymVal.UncapturedRef) {
                        markImpure(pc, 0xB8, "Objects.requireNonNull on null or uncaptured reference");
                        frame.terminated = true;
                        return;
                    }
                    frame.push(arg0);
                    return;
                }
                if ("nonNull".equals(mRef.name()) && callArgs.size() == 1) {
                    frame.push(new SymVal.Scalar(evaluateNullCheck(callArgs.get(0), false, pc, 0xB8)));
                    return;
                }
                if ("isNull".equals(mRef.name()) && callArgs.size() == 1) {
                    frame.push(new SymVal.Scalar(evaluateNullCheck(callArgs.get(0), true, pc, 0xB8)));
                    return;
                }
            }

            // 2. Primitive wrapper valueOf(...) boxing
            if (callArgs.size() == 1 && isPrimitiveWrapperOwner(mRef.owner()) && "valueOf".equals(mRef.name())) {
                ConceptType primType = ConceptType.fromDescriptorPrefix(paramDescs.get(0));
                frame.push(new SymVal.Scalar(asScalarExpr(callArgs.get(0), primType, pc, 0xB8)));
                return;
            }

            // 2b. Pure local immutable list / builder constructors
            if ("com/google/common/collect/ImmutableList".equals(mRef.owner())
                    && ("builder".equals(mRef.name()) || "builderWithExpectedSize".equals(mRef.name()))) {
                frame.push(new SymVal.LocalListRef(ctx.nextLocalAllocId++, new ArrayList<>()));
                return;
            }
            if ("java/util/List".equals(mRef.owner()) && "of".equals(mRef.name())) {
                frame.push(new SymVal.LocalListRef(ctx.nextLocalAllocId++, new ArrayList<>(callArgs)));
                return;
            }

            if ("java/util/Arrays".equals(mRef.owner()) && "fill".equals(mRef.name())
                    && (callArgs.size() == 2 || callArgs.size() == 4)
                    && callArgs.get(0) instanceof SymVal.LocalArrayRef lar) {
                int fromIdx = 0;
                int toIdx = lar.length();
                SymVal valSym;
                if (callArgs.size() == 4) {
                    Integer cFrom = tryEvalConstInt(asScalarExpr(callArgs.get(1), ConceptType.I32, pc, 0xB8));
                    Integer cTo = tryEvalConstInt(asScalarExpr(callArgs.get(2), ConceptType.I32, pc, 0xB8));
                    if (cFrom == null || cTo == null) {
                        markImpure(pc, 0xB8, "non-constant Arrays.fill range on local array");
                        frame.terminated = true;
                        return;
                    }
                    fromIdx = Math.max(0, cFrom);
                    toIdx = Math.min(lar.length(), cTo);
                    valSym = callArgs.get(3);
                } else {
                    valSym = callArgs.get(1);
                }
                ConceptExpr fillVal = ensureSimpleExpr(
                        coerceScalar(asScalarExpr(valSym, lar.elemType(), pc, 0xB8), lar.elemType()), pc, out);
                for (int i = fromIdx; i < toIdx; i++) {
                    out.add(new ConceptStmt.Assign(lar.slotVars().get(i), lar.elemType(), fillVal));
                }
                return;
            }

            // 3. Pure Math / StrictMath / Integer / Long / Float / Double intrinsics
            if (BytecodeSsaLifter.isSystemOwner(mRef.owner())) {
                List<ConceptExpr> scalarArgs = new ArrayList<>(callArgs.size());
                boolean allScalar = true;
                for (int i = 0; i < callArgs.size(); i++) {
                    if (callArgs.get(i) instanceof SymVal.Scalar || callArgs.get(i) instanceof SymVal.CompareTemp) {
                        scalarArgs.add(asScalarExpr(callArgs.get(i), ConceptType.fromDescriptorPrefix(paramDescs.get(i)), pc, 0xB8));
                    } else {
                        allScalar = false;
                        break;
                    }
                }
                if (allScalar) {
                    ConceptExpr intrinsic = BytecodeSsaLifter.tryMatchIntrinsic(
                            mRef.owner(), mRef.name(), scalarArgs, retType);
                    if (intrinsic != null) {
                        frame.push(new SymVal.Scalar(foldIfConstIntrinsic(intrinsic)));
                        return;
                    }
                }
                markImpure(pc, 0xB8, "unmapped JDK/system call " + mRef.owner() + "#" + mRef.name());
                frame.terminated = true;
                return;
            }

            // 4. User static method
            RawClassFile targetOwner = ctx.resolveClass(mRef.owner());
            if (targetOwner == null) {
                markImpure(pc, 0xB8, "missing class bytes for " + mRef.owner());
                frame.terminated = true;
                return;
            }
            RawMethodRef resolved = BytecodeSsaLifter.findAnyMethodOwnerInHierarchy(
                    ctx.classesByName, targetOwner, mRef.name(), mRef.descriptor());
            if (resolved == null) {
                markImpure(pc, 0xB8, "unresolved static method " + mRef.owner() + "#" + mRef.name());
                frame.terminated = true;
                return;
            }
            if ((resolved.method().accessFlags() & BytecodeSsaLifter.ACC_NATIVE) != 0 || resolved.method().code() == null) {
                markImpure(pc, 0xB8, "native or abstract static method " + mRef.owner() + "#" + mRef.name());
                frame.terminated = true;
                return;
            }
            for (int i = 0; i < callArgs.size(); i++) {
                if (callArgs.get(i) instanceof SymVal.MuxInstanceRef mux) {
                    stepMuxStaticInvoke(mRef, paramDescs, retDesc, retType, callArgs, i, mux, pc, frame, out);
                    return;
                }
            }
            boolean hasInlineArg = false;
            for (SymVal a : callArgs) {
                if ((a instanceof SymVal.LocalObjectRef lor && !ctx.isImmutableScalarLocalObject(lor))
                        || a instanceof SymVal.LocalObjectArrayRef
                        || a instanceof SymVal.LocalListRef
                        || a instanceof SymVal.LocalIteratorRef
                        || a instanceof SymVal.LambdaClosure) {
                    hasInlineArg = true;
                    break;
                }
            }
            if (hasInlineArg
                    || ((retDesc.startsWith("L") || retDesc.startsWith("["))
                            && !ctx.hasInternedInstanceOfDescriptor(retDesc))) {
                inlineMethodCall(
                        resolved.owner(),
                        resolved.owner(),
                        resolved.method(),
                        SymVal.NullRef.INSTANCE,
                        callArgs,
                        retDesc,
                        pc,
                        0xB8,
                        frame,
                        out);
                return;
            }
            emitSpecializedCall(
                    resolved.owner(),
                    resolved.owner(),
                    resolved.method(),
                    SymVal.NullRef.INSTANCE,
                    callArgs,
                    null,
                    pc,
                    0xB8,
                    frame,
                    out);
        }

        private void stepInstanceInvoke(
                int opcode,
                MethodRef mRef,
                SymVal recv,
                List<String> paramDescs,
                String retDesc,
                ConceptType retType,
                List<SymVal> callArgs,
                int pc,
                SymFrame frame,
                List<ConceptStmt> out) {
            // 0. Generic context-bound 3D delegate (e.g. DoubleSupplier wrapping noise.getValue(x, y, z))
            if (recv instanceof SymVal.BoundDelegateRef bdr && callArgs.isEmpty()) {
                InstanceNode targetNode = ctx.lookupInstance(bdr.targetInstanceId());
                RawClassFile targetCls = targetNode != null
                        ? ctx.resolveClass(targetNode.concreteClass().getName().replace('.', '/'))
                        : null;
                RawMethodRef targetMethodRef = targetCls != null
                        ? BytecodeSsaLifter.findAnyMethodOwnerInHierarchy(
                                ctx.classesByName, targetCls, bdr.targetMethodName(), "(DDD)D")
                        : null;
                if (targetCls != null && targetMethodRef != null && targetMethodRef.method().code() != null) {
                    ContextParamBinding xBind = new ContextParamBinding(bdr.xParamName(), ConceptType.I32);
                    recordContextUse(0, xBind);
                    localTypes.putIfAbsent(xBind.paramName(), ConceptType.I32);
                    ConceptExpr xExpr = new ConceptExpr.Cast(
                            ConceptType.F64, new ConceptExpr.Var(xBind.paramName(), ConceptType.I32));
                    ConceptExpr yExpr;
                    if (bdr.yParamNameOrNull() != null) {
                        ContextParamBinding yBind = new ContextParamBinding(bdr.yParamNameOrNull(), ConceptType.I32);
                        recordContextUse(0, yBind);
                        localTypes.putIfAbsent(yBind.paramName(), ConceptType.I32);
                        yExpr = new ConceptExpr.Cast(
                                ConceptType.F64, new ConceptExpr.Var(yBind.paramName(), ConceptType.I32));
                    } else {
                        yExpr = new ConceptExpr.DoubleConst(0.0);
                    }
                    ContextParamBinding zBind = new ContextParamBinding(bdr.zParamName(), ConceptType.I32);
                    recordContextUse(0, zBind);
                    localTypes.putIfAbsent(zBind.paramName(), ConceptType.I32);
                    ConceptExpr zExpr = new ConceptExpr.Cast(
                            ConceptType.F64, new ConceptExpr.Var(zBind.paramName(), ConceptType.I32));
                    inlineMethodCall(
                            targetCls,
                            targetMethodRef.owner(),
                            targetMethodRef.method(),
                            new SymVal.InstanceRef(bdr.targetInstanceId()),
                            List.of(new SymVal.Scalar(xExpr), new SymVal.Scalar(yExpr), new SymVal.Scalar(zExpr)),
                            "D",
                            pc,
                            opcode,
                            frame,
                            out);
                    return;
                }
            }
            // 1. Read-only context parameter getter / compile-time factory binding via ObjectInterner
            if (recv instanceof SymVal.ContextRef cr) {
                if (callArgs.isEmpty()) {
                    ContextParamBinding binding = ctx.interner.resolveContextGetter(
                            cr.entryParamIndex(), mRef.owner(), mRef.name(), mRef.descriptor());
                    if (binding != null) {
                        recordContextUse(cr.entryParamIndex(), binding);
                        localTypes.putIfAbsent(binding.paramName(), binding.paramType());
                        ConceptExpr varExpr = new ConceptExpr.Var(binding.paramName(), binding.paramType());
                        if (retDesc.startsWith("L")) {
                            frame.push(new SymVal.InternedScalar(varExpr));
                        } else {
                            ConceptType mRet = BytecodeSsaLifter.parseReturnType(mRef.descriptor());
                            frame.push(new SymVal.Scalar(coerceScalar(varExpr, mRet)));
                        }
                        return;
                    }
                }
                Object[] constArgs = tryResolveConstantCallArgs(callArgs);
                if (constArgs != null) {
                    Object resolvedObj = ctx.interner.resolveContextCallResult(
                            cr.entryParamIndex(), mRef.owner(), mRef.name(), mRef.descriptor(), constArgs);
                    if (resolvedObj != null) {
                        frame.push(captureResolvedContextObject(resolvedObj));
                        return;
                    }
                }
                if (cr.backingInstanceId() >= 0) {
                    stepInstanceInvoke(
                            opcode,
                            mRef,
                            new SymVal.InstanceRef(cr.backingInstanceId()),
                            paramDescs,
                            retDesc,
                            retType,
                            callArgs,
                            pc,
                            frame,
                            out);
                    return;
                }
                RawClassFile ctxCls = ctx.resolveClass(mRef.owner());
                if (ctxCls != null) {
                    RawMethodRef ctxMethodRef = opcode == 0xB7
                            ? ctx.findSpecialTarget(mRef, ctxCls)
                            : BytecodeSsaLifter.findAnyMethodOwnerInHierarchy(
                                    ctx.classesByName, ctxCls, mRef.name(), mRef.descriptor());
                    if (ctxMethodRef != null
                            && (ctxMethodRef.method().accessFlags() & BytecodeSsaLifter.ACC_NATIVE) == 0
                            && ctxMethodRef.method().code() != null) {
                        inlineMethodCall(
                                ctxCls,
                                ctxMethodRef.owner(),
                                ctxMethodRef.method(),
                                cr,
                                callArgs,
                                retDesc,
                                pc,
                                opcode,
                                frame,
                                out);
                        return;
                    }
                }
                markImpure(
                        pc,
                        opcode,
                        "unmapped context call " + mRef.owner() + "#" + mRef.name() + mRef.descriptor());
                frame.terminated = true;
                return;
            }

            // 1b. Interned terminal scalar methods (Supplier#get, Holder#value, Holder#is, equals, caller hook)
            if (recv instanceof SymVal.InternedScalar is) {
                if (("get".equals(mRef.name()) || "value".equals(mRef.name()))
                        && callArgs.isEmpty()
                        && retDesc.startsWith("L")) {
                    frame.push(is);
                    return;
                }
                if ("equals".equals(mRef.name()) && callArgs.size() == 1) {
                    SymVal other = callArgs.get(0);
                    if (other instanceof SymVal.InternedScalar otherIs) {
                        frame.push(new SymVal.Scalar(
                                foldSimpleComparison(BinaryOperator.EQ, is.expr(), otherIs.expr())));
                        return;
                    }
                    if (other instanceof SymVal.NullRef) {
                        frame.push(new SymVal.Scalar(new ConceptExpr.BoolConst(false)));
                        return;
                    }
                }
                if ("is".equals(mRef.name()) && callArgs.size() == 1) {
                    SymVal predArg = callArgs.get(0);
                    if (predArg instanceof SymVal.InternedScalar otherIs) {
                        frame.push(new SymVal.Scalar(
                                foldSimpleComparison(BinaryOperator.EQ, is.expr(), otherIs.expr())));
                        return;
                    }
                    Object livePred = resolveLiveObject(predArg);
                    if (livePred != null) {
                        int[] matchedIds = ctx.interner.matchingInternedIds(livePred);
                        if (matchedIds != null) {
                            frame.push(new SymVal.Scalar(emitFiniteInternedMembership(is.expr(), matchedIds)));
                            return;
                        }
                    }
                }
                List<ConceptExpr> scalarArgs = new ArrayList<>(callArgs.size());
                for (int i = 0; i < callArgs.size(); i++) {
                    SymVal a = callArgs.get(i);
                    if (a instanceof SymVal.Scalar || a instanceof SymVal.CompareTemp) {
                        scalarArgs.add(asScalarExpr(a, ConceptType.fromDescriptorPrefix(paramDescs.get(i)), pc, opcode));
                    } else if (a instanceof SymVal.InternedScalar ais) {
                        scalarArgs.add(ais.expr());
                    } else {
                        scalarArgs.add(new ConceptExpr.IntConst(0));
                    }
                }
                ConceptExpr resolvedCall = ctx.interner.resolveInternedInstanceCall(
                        is.expr(), mRef.owner(), mRef.name(), mRef.descriptor(), scalarArgs);
                if (resolvedCall != null) {
                    frame.push(new SymVal.Scalar(coerceScalar(resolvedCall, retType)));
                    return;
                }
                if ("set".equals(mRef.name()) && retDesc.startsWith("L")) {
                    frame.push(is);
                    return;
                }
                markImpure(pc, opcode, "unmapped interned instance call " + mRef.owner() + "#" + mRef.name());
                frame.terminated = true;
                return;
            }

            // 1c. Local non-escaping List / ImmutableList.Builder methods
            if (recv instanceof SymVal.LocalListRef llr) {
                stepLocalListInvoke(opcode, mRef, llr, retDesc, callArgs, pc, frame);
                return;
            }
            if (recv instanceof SymVal.LocalIteratorRef liter) {
                stepLocalIteratorInvoke(opcode, mRef, liter, pc, frame);
                return;
            }

            // 2. Unwrapped Optional / OptionalDouble / OptionalInt / OptionalLong
            if (recv instanceof SymVal.OptionalVal opt) {
                stepOptionalInvoke(opcode, mRef, opt, paramDescs, callArgs, pc, frame);
                return;
            }

            // 3. Snapshotted List / Collection / Object[] methods
            if (recv instanceof SymVal.CollectionRef coll) {
                stepCollectionInvoke(opcode, mRef, coll, callArgs, pc, frame, out);
                return;
            }

            // 4. Snapshotted Iterator methods
            if (recv instanceof SymVal.IteratorRef iter) {
                stepIteratorInvoke(opcode, mRef, iter, pc, frame, out);
                return;
            }

            // 5. Boxed primitive unboxing (Number#intValue, floatValue, doubleValue, Boolean#booleanValue, etc.)
            if (recv instanceof SymVal.Scalar sc && callArgs.isEmpty() && isUnboxingMethod(mRef.name(), mRef.descriptor())) {
                ConceptType targetT = BytecodeSsaLifter.parseReturnType(mRef.descriptor());
                frame.push(new SymVal.Scalar(coerceScalar(sc.expr(), targetT)));
                return;
            }

            // 6. Homogeneous Record[] / struct Object[] SoA element receiver
            if (recv instanceof SymVal.SoaElemRef soa) {
                RawClassFile elemCls = ctx.resolveClass(soa.elementInternalClass());
                if (elemCls == null) {
                    markImpure(pc, opcode, "missing SoA element class " + soa.elementInternalClass());
                    frame.terminated = true;
                    return;
                }
                RawMethodRef resolved = opcode == 0xB7
                        ? ctx.findSpecialTarget(mRef, elemCls)
                        : BytecodeSsaLifter.findAnyMethodOwnerInHierarchy(
                                ctx.classesByName, elemCls, mRef.name(), mRef.descriptor());
                if (resolved == null || (resolved.method().accessFlags() & BytecodeSsaLifter.ACC_NATIVE) != 0
                        || resolved.method().code() == null) {
                    markImpure(pc, opcode, "unresolved or native method on SoA element " + mRef.name());
                    frame.terminated = true;
                    return;
                }
                if (callArgs.isEmpty()) {
                    FieldRef getterField = tryExtractZeroArgFieldGetter(resolved.owner(), resolved.method());
                    if (getterField != null) {
                        SymVal fv = ctx.resolveFieldValue(
                                soa,
                                getterField,
                                concreteCls != null ? concreteCls.thisClass : methodOwner.thisClass,
                                method.name(),
                                pc,
                                opcode);
                        if (fv == null) {
                            pure = false;
                            frame.terminated = true;
                        } else {
                            ConceptType mRet = BytecodeSsaLifter.parseReturnType(mRef.descriptor());
                            if (fv instanceof SymVal.Scalar fvs && mRet != ConceptType.VOID && !retDesc.startsWith("L")) {
                                frame.push(new SymVal.Scalar(coerceScalar(fvs.expr(), mRet)));
                            } else {
                                frame.push(fv);
                            }
                        }
                        return;
                    }
                }
                emitSpecializedCall(
                        elemCls,
                        resolved.owner(),
                        resolved.method(),
                        soa,
                        callArgs,
                        null,
                        pc,
                        opcode,
                        frame,
                        out);
                return;
            }

            // 6b. ThreadLocal#get() -> cold memo cache reference (always miss, recompute pure body)
            if (recv instanceof SymVal.ThreadLocalRef && "get".equals(mRef.name()) && callArgs.isEmpty()) {
                frame.push(SymVal.ColdMemoCacheRef.INSTANCE);
                return;
            }
            if (recv instanceof SymVal.ColdMemoCacheRef
                    && ("computeIfAbsent".equals(mRef.name()) || "get".equals(mRef.name()))) {
                frame.push(SymVal.ColdMemoCacheRef.INSTANCE);
                return;
            }

            // 6b2. LambdaClosure SAM invocation (e.g. IntPredicate#test in Mth.binarySearch)
            if (recv instanceof SymVal.LambdaClosure lc) {
                RawClassFile targetCls = ctx.resolveClass(lc.targetOwner());
                if (targetCls == null) {
                    markImpure(pc, opcode, "missing class bytes for lambda target " + lc.targetOwner());
                    frame.terminated = true;
                    return;
                }
                RawMethodRef resolved = BytecodeSsaLifter.findAnyMethodOwnerInHierarchy(
                        ctx.classesByName, targetCls, lc.targetMethodName(), lc.targetDescriptor());
                if (resolved == null || (resolved.method().accessFlags() & BytecodeSsaLifter.ACC_NATIVE) != 0
                        || resolved.method().code() == null) {
                    markImpure(pc, opcode, "unresolved lambda target " + lc.targetOwner() + "#" + lc.targetMethodName());
                    frame.terminated = true;
                    return;
                }
                boolean isStaticTarget = (resolved.method().accessFlags() & BytecodeSsaLifter.ACC_STATIC) != 0;
                List<SymVal> combined = new ArrayList<>(lc.capturedArgs().size() + callArgs.size());
                combined.addAll(lc.capturedArgs());
                combined.addAll(callArgs);
                SymVal targetRecv = isStaticTarget ? SymVal.NullRef.INSTANCE : combined.remove(0);
                String targetRetDesc = returnDescriptorSuffix(resolved.method().descriptor());
                inlineMethodCall(
                        targetCls,
                        resolved.owner(),
                        resolved.method(),
                        targetRecv,
                        combined,
                        targetRetDesc,
                        pc,
                        opcode,
                        frame,
                        out);
                return;
            }

            // 6b. Non-escaping stack-allocated LocalObjectRef (<init> and instance methods)
            if (recv instanceof SymVal.LocalObjectRef lor) {
                if ("java/util/concurrent/atomic/AtomicLong".equals(lor.internalClassName())
                        || "java/util/concurrent/atomic/AtomicInteger".equals(lor.internalClassName())) {
                    boolean isLong = "java/util/concurrent/atomic/AtomicLong".equals(lor.internalClassName());
                    ConceptType cellType = isLong ? ConceptType.I64 : ConceptType.I32;
                    String mName = mRef.name();
                    if ("<init>".equals(mName)) {
                        if (callArgs.isEmpty()) {
                            lor.fields().put("value", new SymVal.Scalar(
                                    isLong ? new ConceptExpr.LongConst(0L) : new ConceptExpr.IntConst(0)));
                        } else {
                            ConceptExpr initExpr = asScalarExpr(callArgs.get(0), cellType, pc, opcode);
                            if (isCompileTimeScalarConst(initExpr)) {
                                lor.fields().put("value", new SymVal.Scalar(initExpr));
                            } else {
                                String vn = "_loc_atomic_" + lor.allocId();
                                localTypes.putIfAbsent(vn, cellType);
                                out.add(new ConceptStmt.Assign(vn, cellType, initExpr));
                                lor.fields().put("value", new SymVal.Scalar(new ConceptExpr.Var(vn, cellType)));
                            }
                        }
                        return;
                    }
                    if ("get".equals(mName) && callArgs.isEmpty()) {
                        SymVal cur = lor.fields().get("value");
                        frame.push(cur != null ? cur : new SymVal.Scalar(
                                isLong ? new ConceptExpr.LongConst(0L) : new ConceptExpr.IntConst(0)));
                        return;
                    }
                    if (("set".equals(mName) || "lazySet".equals(mName)) && callArgs.size() == 1) {
                        ConceptExpr nextExpr = asScalarExpr(callArgs.get(0), cellType, pc, opcode);
                        if (isCompileTimeScalarConst(nextExpr)) {
                            lor.fields().put("value", new SymVal.Scalar(nextExpr));
                        } else {
                            String vn = "_loc_atomic_" + lor.allocId();
                            localTypes.putIfAbsent(vn, cellType);
                            out.add(new ConceptStmt.Assign(vn, cellType, nextExpr));
                            lor.fields().put("value", new SymVal.Scalar(new ConceptExpr.Var(vn, cellType)));
                        }
                        return;
                    }
                    if (("compareAndSet".equals(mName) || "weakCompareAndSet".equals(mName)) && callArgs.size() == 2) {
                        SymVal curVal = lor.fields().get("value");
                        ConceptExpr curExpr = curVal != null
                                ? asScalarExpr(curVal, cellType, pc, opcode)
                                : (isLong ? new ConceptExpr.LongConst(0L) : new ConceptExpr.IntConst(0));
                        ConceptExpr expExpr = asScalarExpr(callArgs.get(0), cellType, pc, opcode);
                        ConceptExpr updExpr = asScalarExpr(callArgs.get(1), cellType, pc, opcode);
                        String vn = "_loc_atomic_" + lor.allocId();
                        localTypes.putIfAbsent(vn, cellType);
                        boolean guaranteedMatch = curExpr.equals(expExpr);
                        if (!guaranteedMatch && expExpr instanceof ConceptExpr.Var expVar) {
                            for (int bi = out.size() - 1; bi >= 0; bi--) {
                                if (out.get(bi) instanceof ConceptStmt.Assign ba && expVar.name().equals(ba.name())) {
                                    guaranteedMatch = curExpr.equals(ba.value());
                                    break;
                                }
                            }
                        }
                        if (guaranteedMatch) {
                            if (isCompileTimeScalarConst(updExpr)) {
                                lor.fields().put("value", new SymVal.Scalar(updExpr));
                            } else {
                                out.add(new ConceptStmt.Assign(vn, cellType, updExpr));
                                lor.fields().put("value", new SymVal.Scalar(new ConceptExpr.Var(vn, cellType)));
                            }
                            frame.push(new SymVal.Scalar(new ConceptExpr.IntConst(1)));
                        } else {
                            ConceptExpr eq = new ConceptExpr.BinaryOp(
                                    BinaryOperator.EQ, curExpr, expExpr, ConceptType.BOOL);
                            ConceptExpr nextCell = new ConceptExpr.Ternary(eq, updExpr, curExpr, cellType);
                            out.add(new ConceptStmt.Assign(vn, cellType, nextCell));
                            lor.fields().put("value", new SymVal.Scalar(new ConceptExpr.Var(vn, cellType)));
                            frame.push(new SymVal.Scalar(new ConceptExpr.Ternary(
                                    eq, new ConceptExpr.IntConst(1), new ConceptExpr.IntConst(0), ConceptType.I32)));
                        }
                        return;
                    }
                    markImpure(pc, opcode, "unsupported local atomic method " + lor.internalClassName() + "#" + mName);
                    frame.terminated = true;
                    return;
                }
                if ("<init>".equals(mRef.name())
                        && ("java/lang/Object".equals(mRef.owner()) || "java/lang/Record".equals(mRef.owner()))
                        && callArgs.isEmpty()) {
                    return;
                }
                RawClassFile lorCls = ctx.resolveClass(lor.internalClassName());
                if (lorCls == null) {
                    markImpure(pc, opcode, "missing class bytes for local object " + lor.internalClassName());
                    frame.terminated = true;
                    return;
                }
                RawMethodRef resolved = opcode == 0xB7
                        ? ctx.findSpecialTarget(mRef, lorCls)
                        : BytecodeSsaLifter.findAnyMethodOwnerInHierarchy(
                                ctx.classesByName, lorCls, mRef.name(), mRef.descriptor());
                if (resolved == null || (resolved.method().accessFlags() & BytecodeSsaLifter.ACC_NATIVE) != 0
                        || resolved.method().code() == null) {
                    markImpure(
                            pc,
                            opcode,
                            "unresolved or native method on local object "
                                    + lor.internalClassName()
                                    + "#"
                                    + mRef.name()
                                    + mRef.descriptor());
                    frame.terminated = true;
                    return;
                }
                inlineMethodCall(
                        lorCls,
                        resolved.owner(),
                        resolved.method(),
                        lor,
                        callArgs,
                        retDesc,
                        pc,
                        opcode,
                        frame,
                        out);
                return;
            }

            // 6c. Bounded dynamic MuxInstanceRef dispatch (e.g. ImprovedNoise[] octave selection)
            if (recv instanceof SymVal.MuxInstanceRef mux) {
                stepMuxInstanceInvoke(opcode, mRef, mux, paramDescs, retDesc, retType, callArgs, pc, frame, out);
                return;
            }

            // 7. Known snapshotted InstanceRef
            if (recv instanceof SymVal.InstanceRef ir) {
                InstanceNode node = ctx.lookupInstance(ir.instanceId());
                if (node == null) {
                    markImpure(pc, opcode, "missing instance node " + ir.instanceId());
                    frame.terminated = true;
                    return;
                }
                // Enum#ordinal()
                if ("ordinal".equals(mRef.name()) && "()I".equals(mRef.descriptor()) && node.enumOrdinal() != null) {
                    ctx.recordLiftedMethod(mRef.owner(), "ordinal");
                    ctx.recordLiftedMethod(node.internalClassName(), "ordinal");
                    frame.push(new SymVal.Scalar(new ConceptExpr.IntConst(node.enumOrdinal())));
                    return;
                }
                // Snapshotted immutable/live java.util.Map constant-key lookup (e.g. ContextMap#params)
                Object liveRecvObj = ctx.dynamicCollector != null
                        ? ctx.dynamicCollector.liveObjectsById.get(ir.instanceId())
                        : null;
                if (liveRecvObj != null && callArgs.size() == 1 && callArgs.get(0) instanceof SymVal.InternedScalar argIs) {
                    if ("contains".equals(mRef.name())) {
                        int[] matchedIds = ctx.interner.matchingInternedIds(liveRecvObj);
                        if (matchedIds == null && liveRecvObj instanceof Iterable<?> iterable) {
                            matchedIds = collectInternedIdsFromIterable(iterable);
                        }
                        if (matchedIds != null) {
                            frame.push(new SymVal.Scalar(emitFiniteInternedMembership(argIs.expr(), matchedIds)));
                            return;
                        }
                    } else if ("test".equals(mRef.name())) {
                        int[] matchedIds = ctx.interner.matchingInternedIds(liveRecvObj);
                        if (matchedIds != null) {
                            frame.push(new SymVal.Scalar(emitFiniteInternedMembership(argIs.expr(), matchedIds)));
                            return;
                        }
                    }
                }
                if (liveRecvObj instanceof Map<?, ?> liveMap) {
                    if ("isEmpty".equals(mRef.name()) && callArgs.isEmpty()) {
                        frame.push(new SymVal.Scalar(new ConceptExpr.BoolConst(liveMap.isEmpty())));
                        return;
                    }
                    if ("size".equals(mRef.name()) && callArgs.isEmpty()) {
                        frame.push(new SymVal.Scalar(new ConceptExpr.IntConst(liveMap.size())));
                        return;
                    }
                    if (("get".equals(mRef.name()) || "containsKey".equals(mRef.name())) && callArgs.size() == 1) {
                        if (liveMap.isEmpty()) {
                            frame.push("containsKey".equals(mRef.name())
                                    ? new SymVal.Scalar(new ConceptExpr.BoolConst(false))
                                    : SymVal.NullRef.INSTANCE);
                            return;
                        }
                        Object liveKey = resolveLiveConstantKey(callArgs.get(0));
                        if (liveKey != null || callArgs.get(0) instanceof SymVal.NullRef) {
                            if ("containsKey".equals(mRef.name())) {
                                frame.push(new SymVal.Scalar(new ConceptExpr.BoolConst(liveMap.containsKey(liveKey))));
                                return;
                            }
                            Object val = liveMap.get(liveKey);
                            frame.push(val == null
                                    ? SymVal.NullRef.INSTANCE
                                    : ctx.instanceIdToSymVal(ctx.captureLiveObjectOnDemand(val)));
                            return;
                        }
                    }
                    if ("getOrDefault".equals(mRef.name()) && callArgs.size() == 2) {
                        if (liveMap.isEmpty()) {
                            frame.push(callArgs.get(1));
                            return;
                        }
                        Object liveKey = resolveLiveConstantKey(callArgs.get(0));
                        if (liveKey != null || callArgs.get(0) instanceof SymVal.NullRef) {
                            Object val = liveMap.get(liveKey);
                            frame.push(val != null
                                    ? ctx.instanceIdToSymVal(ctx.captureLiveObjectOnDemand(val))
                                    : callArgs.get(1));
                            return;
                        }
                    }
                }
                RawClassFile recvCls = ctx.resolveClass(node.internalClassName());
                if (recvCls == null) {
                    markImpure(
                            pc,
                            opcode,
                            "uncaptured or opaque receiver class " + node.internalClassName() + "#" + mRef.name());
                    frame.terminated = true;
                    return;
                }
                RawMethodRef initResolved = opcode == 0xB7
                        ? ctx.findSpecialTarget(mRef, recvCls)
                        : BytecodeSsaLifter.findAnyMethodOwnerInHierarchy(
                                ctx.classesByName, recvCls, mRef.name(), mRef.descriptor());
                if (initResolved == null && opcode != 0xB7 && retDesc.startsWith("L")) {
                    initResolved = ctx.findMethodByNameAndParamDescriptor(recvCls, mRef.name(), mRef.descriptor());
                }
                if (initResolved == null) {
                    markImpure(
                            pc,
                            opcode,
                            "unresolved method " + node.internalClassName() + "#" + mRef.name() + mRef.descriptor());
                    frame.terminated = true;
                    return;
                }

                // Zero-arg field/record accessor erasure
                if (callArgs.isEmpty()) {
                    FieldRef getterField = tryExtractZeroArgFieldGetter(initResolved.owner(), initResolved.method());
                    if (getterField != null) {
                        SymVal fv = ctx.resolveFieldValue(
                                ir,
                                getterField,
                                concreteCls != null ? concreteCls.thisClass : methodOwner.thisClass,
                                method.name(),
                                pc,
                                opcode);
                        if (fv == null) {
                            pure = false;
                            frame.terminated = true;
                        } else {
                            ConceptType mRet = BytecodeSsaLifter.parseReturnType(mRef.descriptor());
                            if (fv instanceof SymVal.Scalar fvs && mRet != ConceptType.VOID && !retDesc.startsWith("L")) {
                                frame.push(new SymVal.Scalar(coerceScalar(fvs.expr(), mRet)));
                            } else {
                                frame.push(fv);
                            }
                        }
                        return;
                    }
                }

                // Pure structural delegator wrapper erasure by bytecode shape
                DelegatorStep unwrapped = ctx.unwrapDelegatorChain(
                        ir, recvCls, initResolved.owner(), initResolved.method(), callArgs);
                RawMethod finalMethod = unwrapped.method();
                if ((finalMethod.accessFlags() & BytecodeSsaLifter.ACC_NATIVE) != 0 || finalMethod.code() == null) {
                    markImpure(
                            pc,
                            opcode,
                            "native or abstract method " + unwrapped.concreteClass().thisClass + "#" + finalMethod.name());
                    frame.terminated = true;
                    return;
                }

                boolean hasLocalObjArg = false;
                for (SymVal a : unwrapped.args()) {
                    if ((a instanceof SymVal.LocalObjectRef lor && !ctx.isImmutableScalarLocalObject(lor))
                            || a instanceof SymVal.LocalObjectArrayRef
                            || a instanceof SymVal.LocalListRef
                            || a instanceof SymVal.LocalIteratorRef
                            || a instanceof SymVal.MuxInstanceRef
                            || a instanceof SymVal.LambdaClosure) {
                        hasLocalObjArg = true;
                        break;
                    }
                }
                if (hasLocalObjArg
                        || ((retDesc.startsWith("L") || retDesc.startsWith("["))
                                && !ctx.hasInternedInstanceOfDescriptor(retDesc))) {
                    inlineMethodCall(
                            unwrapped.concreteClass(),
                            unwrapped.methodOwner(),
                            finalMethod,
                            unwrapped.targetReceiver(),
                            unwrapped.args(),
                            retDesc,
                            pc,
                            opcode,
                            frame,
                            out);
                    return;
                }

                emitSpecializedCall(
                        unwrapped.concreteClass(),
                        unwrapped.methodOwner(),
                        finalMethod,
                        unwrapped.targetReceiver(),
                        unwrapped.args(),
                        unwrapped.outerReturnCast(),
                        pc,
                        opcode,
                        frame,
                        out);
                return;
            }

            if (recv instanceof SymVal.StringConst sc) {
                if ("equals".equals(mRef.name()) && callArgs.size() == 1) {
                    SymVal other = callArgs.get(0);
                    if (other instanceof SymVal.StringConst otherStr) {
                        frame.push(new SymVal.Scalar(new ConceptExpr.BoolConst(sc.value().equals(otherStr.value()))));
                        return;
                    }
                    if (other instanceof SymVal.NullRef) {
                        frame.push(new SymVal.Scalar(new ConceptExpr.BoolConst(false)));
                        return;
                    }
                }
                if ("hashCode".equals(mRef.name()) && callArgs.isEmpty()) {
                    frame.push(new SymVal.Scalar(new ConceptExpr.IntConst(sc.value().hashCode())));
                    return;
                }
                if ("isEmpty".equals(mRef.name()) && callArgs.isEmpty()) {
                    frame.push(new SymVal.Scalar(new ConceptExpr.BoolConst(sc.value().isEmpty())));
                    return;
                }
                if ("length".equals(mRef.name()) && callArgs.isEmpty()) {
                    frame.push(new SymVal.Scalar(new ConceptExpr.IntConst(sc.value().length())));
                    return;
                }
            }

            if (recv instanceof SymVal.NullRef) {
                markImpure(pc, opcode, "null receiver on invoke " + mRef.name());
                frame.terminated = true;
                return;
            }
            if (recv instanceof SymVal.UncapturedRef ur) {
                markImpure(pc, opcode, "dynamic dispatch on " + ur.reason());
                frame.terminated = true;
                return;
            }

            markImpure(pc, opcode, "non-constant receiver on invoke " + mRef.owner() + "#" + mRef.name());
            frame.terminated = true;
        }

        private void emitSpecializedCall(
                RawClassFile targetConcreteCls,
                RawClassFile targetMethodOwner,
                RawMethod targetMethod,
                SymVal targetReceiver,
                List<SymVal> targetArgs,
                ConceptType outerReturnCast,
                int pc,
                int opcode,
                SymFrame frame,
                List<ConceptStmt> out) {
            String reportOwner = concreteCls != null ? concreteCls.thisClass : methodOwner.thisClass;
            SpecializedHelper helper = ctx.liftOrReuseHelper(
                    targetConcreteCls,
                    targetMethodOwner,
                    targetMethod,
                    targetReceiver,
                    targetArgs,
                    reportOwner,
                    method.name(),
                    pc,
                    opcode);
            if (helper == null) {
                pure = false;
                frame.terminated = true;
                return;
            }
            if (!helper.function().pureCompute()) {
                pure = false;
            }

            List<String> rawParamDescs = splitMethodParamDescriptors(targetMethod.descriptor());
            List<ConceptExpr> exprArgs = new ArrayList<>();
            int paramCursor = 0;
            for (int i = 0; i < targetArgs.size(); i++) {
                SymVal a = targetArgs.get(i);
                if (a instanceof SymVal.Scalar || a instanceof SymVal.CompareTemp) {
                    ConceptType expected = helper.function().parameters().get(paramCursor++).type();
                    exprArgs.add(asScalarExpr(a, expected, pc, opcode));
                } else if (a instanceof SymVal.InternedScalar is) {
                    paramCursor++;
                    exprArgs.add(is.expr());
                } else if (a instanceof SymVal.Array2DRowRef row) {
                    paramCursor++;
                    exprArgs.add(coerceScalar(row.rowIndex(), ConceptType.I32));
                } else if (a instanceof SymVal.SoaElemRef soa) {
                    paramCursor++;
                    exprArgs.add(coerceScalar(soa.indexExpr(), ConceptType.I32));
                } else if (a instanceof SymVal.LocalObjectRef lor && ctx.isImmutableScalarLocalObject(lor)) {
                    paramCursor = collectImmutableLocalObjectExprArgs(lor, helper, paramCursor, exprArgs, pc, opcode);
                }
            }
            if (targetReceiver instanceof SymVal.SoaElemRef soaRecv) {
                paramCursor++;
                exprArgs.add(coerceScalar(soaRecv.indexExpr(), ConceptType.I32));
            }
            for (IndexedContextBinding icb : helper.usedContextBindings()) {
                recordContextUse(icb.entryParamIndex(), icb.binding());
                localTypes.putIfAbsent(icb.binding().paramName(), icb.binding().paramType());
                exprArgs.add(new ConceptExpr.Var(icb.binding().paramName(), icb.binding().paramType()));
            }

            ConceptFunction fn = helper.function();
            ConceptExpr callExpr = new ConceptExpr.Call(
                    fn.ownerClass(),
                    fn.name(),
                    fn.descriptor(),
                    exprArgs,
                    fn.returnType());

            if (fn.returnType() == ConceptType.VOID) {
                String tmp = allocTemp("_void_call", ConceptType.I32);
                out.add(new ConceptStmt.Assign(tmp, ConceptType.I32, callExpr));
                return;
            }
            if (outerReturnCast != null && !helper.returnsInternedObject()) {
                callExpr = coerceScalar(callExpr, outerReturnCast);
            }
            if (helper.returnsInternedObject()) {
                frame.push(new SymVal.InternedScalar(callExpr));
            } else {
                frame.push(new SymVal.Scalar(callExpr));
            }
        }

        private int collectImmutableLocalObjectExprArgs(
                SymVal.LocalObjectRef lor,
                SpecializedHelper helper,
                int paramCursor,
                List<ConceptExpr> exprArgs,
                int pc,
                int opcode) {
            List<String> sortedKeys = new ArrayList<>(lor.fields().keySet());
            Collections.sort(sortedKeys);
            for (String fk : sortedKeys) {
                SymVal fv = lor.fields().get(fk);
                if (fv instanceof SymVal.LocalObjectRef nested) {
                    paramCursor = collectImmutableLocalObjectExprArgs(nested, helper, paramCursor, exprArgs, pc, opcode);
                } else if (fv instanceof SymVal.Scalar || fv instanceof SymVal.CompareTemp) {
                    ConceptType expected = helper.function().parameters().get(paramCursor++).type();
                    exprArgs.add(asScalarExpr(fv, expected, pc, opcode));
                } else if (fv instanceof SymVal.InternedScalar is) {
                    paramCursor++;
                    exprArgs.add(is.expr());
                }
            }
            return paramCursor;
        }

        private void stepOptionalInvoke(
                int opcode,
                MethodRef mRef,
                SymVal.OptionalVal opt,
                List<String> paramDescs,
                List<SymVal> callArgs,
                int pc,
                SymFrame frame) {
            String name = mRef.name();
            if ("isPresent".equals(name) && callArgs.isEmpty()) {
                frame.push(new SymVal.Scalar(new ConceptExpr.BoolConst(opt.present())));
                return;
            }
            if ("isEmpty".equals(name) && callArgs.isEmpty()) {
                frame.push(new SymVal.Scalar(new ConceptExpr.BoolConst(!opt.present())));
                return;
            }
            if (("get".equals(name) || "orElseThrow".equals(name)
                    || "getAsDouble".equals(name) || "getAsInt".equals(name) || "getAsLong".equals(name))
                    && callArgs.isEmpty()) {
                if (!opt.present()) {
                    markImpure(pc, opcode, "Optional." + name + "() on empty Optional");
                    frame.terminated = true;
                    return;
                }
                frame.push(opt.payload());
                return;
            }
            if ("orElse".equals(name) && callArgs.size() == 1) {
                if (!opt.present()) {
                    frame.push(callArgs.get(0));
                    return;
                }
                if (opt.payload() instanceof SymVal.Scalar ps && !paramDescs.get(0).startsWith("L")) {
                    ConceptType t = ConceptType.fromDescriptorPrefix(paramDescs.get(0));
                    frame.push(new SymVal.Scalar(coerceScalar(ps.expr(), t)));
                } else {
                    frame.push(opt.payload());
                }
                return;
            }
            markImpure(pc, opcode, "unsupported Optional method " + name + mRef.descriptor());
            frame.terminated = true;
        }

        private void stepCollectionInvoke(
                int opcode,
                MethodRef mRef,
                SymVal.CollectionRef coll,
                List<SymVal> callArgs,
                int pc,
                SymFrame frame,
                List<ConceptStmt> out) {
            String name = mRef.name();
            if ("size".equals(name) && callArgs.isEmpty()) {
                frame.push(new SymVal.Scalar(new ConceptExpr.IntConst(coll.elementIds().size())));
                return;
            }
            if ("isEmpty".equals(name) && callArgs.isEmpty()) {
                frame.push(new SymVal.Scalar(new ConceptExpr.BoolConst(coll.elementIds().isEmpty())));
                return;
            }
            if ("getFirst".equals(name) && callArgs.isEmpty() && !coll.elementIds().isEmpty()) {
                frame.push(ctx.instanceIdToSymVal(coll.elementIds().get(0)));
                return;
            }
            if ("getLast".equals(name) && callArgs.isEmpty() && !coll.elementIds().isEmpty()) {
                frame.push(ctx.instanceIdToSymVal(coll.elementIds().get(coll.elementIds().size() - 1)));
                return;
            }
            if (("get".equals(name)
                    || "getDouble".equals(name)
                    || "getFloat".equals(name)
                    || "getInt".equals(name)
                    || "getLong".equals(name))
                    && callArgs.size() == 1) {
                ConceptExpr idx = asScalarExpr(callArgs.get(0), ConceptType.I32, pc, opcode);
                Integer constIdx = tryEvalConstInt(idx);
                if (constIdx != null && constIdx >= 0 && constIdx < coll.elementIds().size()) {
                    SymVal elem = ctx.instanceIdToSymVal(coll.elementIds().get(constIdx));
                    if (elem instanceof SymVal.UncapturedRef && ctx.dynamicCollector != null) {
                        Object liveColl = ctx.dynamicCollector.liveObjectsById.get(coll.instanceId());
                        if (liveColl instanceof List<?> liveList && constIdx < liveList.size()) {
                            Object liveElem = liveList.get(constIdx);
                            ConceptExpr primConst = BytecodeSsaLifter.primitiveToConstExpr(liveElem);
                            if (primConst != null) {
                                elem = new SymVal.Scalar(primConst);
                            } else if (liveElem != null) {
                                elem = ctx.instanceIdToSymVal(ctx.captureLiveObjectOnDemand(liveElem));
                            }
                        }
                    }
                    if (elem instanceof SymVal.Scalar sc && !"get".equals(name)) {
                        ConceptType retT = BytecodeSsaLifter.parseReturnType(mRef.descriptor());
                        frame.push(new SymVal.Scalar(coerceScalar(sc.expr(), retT)));
                    } else {
                        frame.push(elem);
                    }
                    return;
                }
                if (!coll.elementIds().isEmpty()) {
                    ConceptLut primLut = tryBuildPrimitiveCollectionLut(coll, mRef.descriptor());
                    if (primLut != null) {
                        ConceptExpr loaded = evalOrRegisterLutLoad(primLut, idx);
                        ConceptType retT = BytecodeSsaLifter.parseReturnType(mRef.descriptor());
                        frame.push(new SymVal.Scalar(coerceScalar(loaded, retT)));
                        return;
                    }
                }
                if (!coll.soaLuts().isEmpty()
                        && coll.elementInternalClass() != null
                        && !hasNonScalarElementFields(coll)) {
                    frame.push(new SymVal.SoaElemRef(
                            coll.instanceId(), coll.elementInternalClass(), coll.soaLuts(), idx));
                    return;
                }
                if (!coll.elementIds().isEmpty() && coll.elementIds().size() <= ctx.options.maxLoopUnrollLength()) {
                    frame.push(new SymVal.MuxInstanceRef(idx, coll.elementIds()));
                    return;
                }
                markImpure(pc, opcode, "non-constant List." + name + " index into heterogeneous collection");
                frame.terminated = true;
                return;
            }
            if (("iterator".equals(name) || "listIterator".equals(name)) && callArgs.isEmpty()) {
                int cursorSlot = shared.nextCursorSlot++;
                frame.locals.put(cursorSlot, new SymVal.Scalar(new ConceptExpr.IntConst(0)));
                frame.push(new SymVal.IteratorRef(coll.elementIds(), cursorSlot));
                return;
            }
            if ("contains".equals(name) && callArgs.size() == 1 && callArgs.get(0) instanceof SymVal.InternedScalar argIs) {
                Object liveColl = ctx.dynamicCollector != null
                        ? ctx.dynamicCollector.liveObjectsById.get(coll.instanceId())
                        : null;
                int[] matchedIds = liveColl != null ? ctx.interner.matchingInternedIds(liveColl) : null;
                if (matchedIds == null) {
                    List<Integer> ids = new ArrayList<>();
                    for (int elemId : coll.elementIds()) {
                        if (elemId >= 0) {
                            InstanceNode en = ctx.lookupInstance(elemId);
                            if (en != null && en.internedId() != null) {
                                ids.add(en.internedId());
                            }
                        }
                    }
                    matchedIds = ids.stream().mapToInt(Integer::intValue).toArray();
                }
                frame.push(new SymVal.Scalar(emitFiniteInternedMembership(argIs.expr(), matchedIds)));
                return;
            }
            markImpure(pc, opcode, "unsupported Collection method " + name + mRef.descriptor());
            frame.terminated = true;
        }

        private void stepLocalListInvoke(
                int opcode,
                MethodRef mRef,
                SymVal.LocalListRef llr,
                String retDesc,
                List<SymVal> callArgs,
                int pc,
                SymFrame frame) {
            String name = mRef.name();
            if ("<init>".equals(name)) {
                return;
            }
            if ("add".equals(name) && callArgs.size() == 1) {
                llr.elements().add(callArgs.get(0));
                if ("Z".equals(retDesc)) {
                    frame.push(new SymVal.Scalar(new ConceptExpr.BoolConst(true)));
                } else if (retDesc.startsWith("L")) {
                    frame.push(llr);
                }
                return;
            }
            if ("build".equals(name) && callArgs.isEmpty()) {
                frame.push(llr);
                return;
            }
            if ("size".equals(name) && callArgs.isEmpty()) {
                frame.push(new SymVal.Scalar(new ConceptExpr.IntConst(llr.elements().size())));
                return;
            }
            if ("isEmpty".equals(name) && callArgs.isEmpty()) {
                frame.push(new SymVal.Scalar(new ConceptExpr.BoolConst(llr.elements().isEmpty())));
                return;
            }
            if ("getFirst".equals(name) && callArgs.isEmpty() && !llr.elements().isEmpty()) {
                frame.push(llr.elements().get(0));
                return;
            }
            if ("getLast".equals(name) && callArgs.isEmpty() && !llr.elements().isEmpty()) {
                frame.push(llr.elements().get(llr.elements().size() - 1));
                return;
            }
            if ("get".equals(name) && callArgs.size() == 1) {
                Integer idx = tryEvalConstInt(asScalarExpr(callArgs.get(0), ConceptType.I32, pc, opcode));
                if (idx != null && idx >= 0 && idx < llr.elements().size()) {
                    frame.push(llr.elements().get(idx));
                    return;
                }
            }
            if (("iterator".equals(name) || "listIterator".equals(name)) && callArgs.isEmpty()) {
                int cursorSlot = shared.nextCursorSlot++;
                frame.locals.put(cursorSlot, new SymVal.Scalar(new ConceptExpr.IntConst(0)));
                frame.push(new SymVal.LocalIteratorRef(llr.elements(), cursorSlot));
                return;
            }
            markImpure(pc, opcode, "unsupported local List method " + name + mRef.descriptor());
            frame.terminated = true;
        }

        private void stepLocalIteratorInvoke(
                int opcode,
                MethodRef mRef,
                SymVal.LocalIteratorRef iter,
                int pc,
                SymFrame frame) {
            SymVal cursorSym = frame.locals.getOrDefault(
                    iter.cursorSlot(), new SymVal.Scalar(new ConceptExpr.IntConst(0)));
            Integer cursorConst = (cursorSym instanceof SymVal.Scalar sc) ? tryEvalConstInt(sc.expr()) : null;
            if ("hasNext".equals(mRef.name())) {
                if (cursorConst != null) {
                    frame.push(new SymVal.Scalar(new ConceptExpr.BoolConst(cursorConst < iter.elements().size())));
                    return;
                }
                markImpure(pc, opcode, "non-constant local Iterator.hasNext cursor");
                frame.terminated = true;
                return;
            }
            if ("next".equals(mRef.name())) {
                if (cursorConst != null && cursorConst >= 0 && cursorConst < iter.elements().size()) {
                    frame.locals.put(iter.cursorSlot(), new SymVal.Scalar(new ConceptExpr.IntConst(cursorConst + 1)));
                    frame.push(iter.elements().get(cursorConst));
                    return;
                }
                markImpure(pc, opcode, "non-constant or out-of-bounds local Iterator.next");
                frame.terminated = true;
                return;
            }
            markImpure(pc, opcode, "unsupported local Iterator method " + mRef.name());
            frame.terminated = true;
        }

        private Object[] tryResolveConstantCallArgs(List<SymVal> callArgs) {
            Object[] out = new Object[callArgs.size()];
            for (int i = 0; i < callArgs.size(); i++) {
                SymVal a = callArgs.get(i);
                if (a instanceof SymVal.NullRef) {
                    out[i] = null;
                } else if (a instanceof SymVal.StringConst sc) {
                    out[i] = sc.value();
                } else if (a instanceof SymVal.Scalar sc) {
                    ConceptExpr e = sc.expr();
                    if (e instanceof ConceptExpr.IntConst ic) {
                        out[i] = ic.value();
                    } else if (e instanceof ConceptExpr.BoolConst bc) {
                        out[i] = bc.value();
                    } else if (e instanceof ConceptExpr.LongConst lc) {
                        out[i] = lc.value();
                    } else if (e instanceof ConceptExpr.FloatConst fc) {
                        out[i] = fc.value();
                    } else if (e instanceof ConceptExpr.DoubleConst dc) {
                        out[i] = dc.value();
                    } else {
                        return null;
                    }
                } else if (a instanceof SymVal.InstanceRef ir) {
                    Object live = ctx.dynamicCollector != null
                            ? ctx.dynamicCollector.liveObjectsById.get(ir.instanceId())
                            : null;
                    if (live == null) {
                        return null;
                    }
                    out[i] = live;
                } else {
                    return null;
                }
            }
            return out;
        }

        private Object resolveLiveObject(SymVal val) {
            if (val instanceof SymVal.InstanceRef ir && ctx.dynamicCollector != null) {
                return ctx.dynamicCollector.liveObjectsById.get(ir.instanceId());
            }
            if (val instanceof SymVal.CollectionRef cr && ctx.dynamicCollector != null) {
                return ctx.dynamicCollector.liveObjectsById.get(cr.instanceId());
            }
            if (val instanceof SymVal.StringConst sc) {
                return sc.value();
            }
            return null;
        }

        private int[] collectInternedIdsFromIterable(Iterable<?> iterable) {
            List<Integer> ids = new ArrayList<>();
            for (Object elem : iterable) {
                if (elem == null) {
                    continue;
                }
                Integer id = ctx.interner.internObject(elem);
                if (id != null) {
                    ids.add(id);
                }
            }
            return ids.stream().mapToInt(Integer::intValue).toArray();
        }

        private ConceptExpr emitFiniteInternedMembership(ConceptExpr idExpr, int[] rawIds) {
            if (rawIds == null || rawIds.length == 0) {
                return new ConceptExpr.BoolConst(false);
            }
            int[] sorted = rawIds.clone();
            java.util.Arrays.sort(sorted);
            ConceptExpr acc = null;
            int prev = Integer.MIN_VALUE;
            boolean hasPrev = false;
            for (int id : sorted) {
                if (hasPrev && id == prev) {
                    continue;
                }
                prev = id;
                hasPrev = true;
                ConceptExpr eq = foldSimpleComparison(BinaryOperator.EQ, idExpr, new ConceptExpr.IntConst(id));
                if (eq instanceof ConceptExpr.BoolConst bc && bc.value()) {
                    return eq;
                }
                if (acc == null) {
                    acc = eq;
                } else {
                    acc = new ConceptExpr.BinaryOp(BinaryOperator.LOGICAL_OR, acc, eq, ConceptType.BOOL);
                }
            }
            return acc != null ? acc : new ConceptExpr.BoolConst(false);
        }

        private void stepIteratorInvoke(
                int opcode,
                MethodRef mRef,
                SymVal.IteratorRef iter,
                int pc,
                SymFrame frame,
                List<ConceptStmt> out) {
            SymVal cursorSym = frame.locals.getOrDefault(
                    iter.cursorSlot(), new SymVal.Scalar(new ConceptExpr.IntConst(0)));
            Integer cursorConst = (cursorSym instanceof SymVal.Scalar sc) ? tryEvalConstInt(sc.expr()) : null;
            if ("hasNext".equals(mRef.name())) {
                if (cursorConst != null) {
                    frame.push(new SymVal.Scalar(new ConceptExpr.BoolConst(cursorConst < iter.elementIds().size())));
                    return;
                }
                markImpure(pc, opcode, "non-constant Iterator.hasNext cursor");
                frame.terminated = true;
                return;
            }
            if ("next".equals(mRef.name())) {
                if (cursorConst != null && cursorConst >= 0 && cursorConst < iter.elementIds().size()) {
                    frame.locals.put(iter.cursorSlot(), new SymVal.Scalar(new ConceptExpr.IntConst(cursorConst + 1)));
                    frame.push(ctx.instanceIdToSymVal(iter.elementIds().get(cursorConst)));
                    return;
                }
                markImpure(pc, opcode, "non-constant or out-of-bounds Iterator.next");
                frame.terminated = true;
                return;
            }
            markImpure(pc, opcode, "unsupported Iterator method " + mRef.name());
            frame.terminated = true;
        }

        // ---------------------------------------------------------------------
        // Arithmetic, Cast, Branch & Return Lowering
        // ---------------------------------------------------------------------

        private void stepBinaryArith(BinaryOperator op, ConceptType type, int pc, int opcode, SymFrame frame) {
            ConceptExpr right = asScalarExpr(frame.pop(), type, pc, opcode);
            ConceptExpr left = asScalarExpr(frame.pop(), type, pc, opcode);
            frame.push(new SymVal.Scalar(foldSimpleBinary(op, left, right, type)));
        }

        private void stepShift(BinaryOperator op, ConceptType valueType, int pc, int opcode, SymFrame frame) {
            ConceptExpr shiftAmt = asScalarExpr(frame.pop(), ConceptType.I32, pc, opcode);
            ConceptExpr val = asScalarExpr(frame.pop(), valueType, pc, opcode);
            frame.push(new SymVal.Scalar(foldSimpleBinary(op, val, shiftAmt, valueType)));
        }

        private void stepCast(ConceptType srcType, ConceptType dstType, int pc, int opcode, SymFrame frame) {
            ConceptExpr operand = asScalarExpr(frame.pop(), srcType, pc, opcode);
            frame.push(new SymVal.Scalar(coerceScalar(operand, dstType)));
        }

        private ConceptExpr evaluateBranchCondition(int opcode, int pc, SymFrame frame) {
            if (opcode == 0xC6 || opcode == 0xC7) {
                SymVal ref = frame.pop();
                return evaluateNullCheck(ref, opcode == 0xC6, pc, opcode);
            }
            if (opcode == 0xA5 || opcode == 0xA6) {
                SymVal r = frame.pop();
                SymVal l = frame.pop();
                return evaluateRefEq(l, r, opcode == 0xA5, pc, opcode);
            }
            if (opcode >= 0x99 && opcode <= 0x9E) {
                SymVal top = frame.pop();
                BinaryOperator relOp = switch (opcode) {
                    case 0x99 -> BinaryOperator.EQ;
                    case 0x9A -> BinaryOperator.NE;
                    case 0x9B -> BinaryOperator.LT;
                    case 0x9C -> BinaryOperator.GE;
                    case 0x9D -> BinaryOperator.GT;
                    default -> BinaryOperator.LE;
                };
                if (top instanceof SymVal.CompareTemp cmp) {
                    return foldSimpleComparison(relOp, cmp.left(), cmp.right());
                }
                if (top instanceof SymVal.Scalar sc && sc.expr().type() == ConceptType.BOOL) {
                    if (opcode == 0x99) {
                        return foldSimpleUnary(UnaryOperator.LOGICAL_NOT, sc.expr(), ConceptType.BOOL);
                    }
                    if (opcode == 0x9A) {
                        return sc.expr();
                    }
                }
                ConceptExpr v = asScalarExpr(top, ConceptType.I32, pc, opcode);
                return foldSimpleComparison(relOp, v, new ConceptExpr.IntConst(0));
            }
            // 0x9F..0xA4 (IF_ICMPEQ..IF_ICMPLE)
            ConceptExpr right = asScalarExpr(frame.pop(), ConceptType.I32, pc, opcode);
            ConceptExpr left = asScalarExpr(frame.pop(), ConceptType.I32, pc, opcode);
            BinaryOperator relOp = switch (opcode) {
                case 0x9F -> BinaryOperator.EQ;
                case 0xA0 -> BinaryOperator.NE;
                case 0xA1 -> BinaryOperator.LT;
                case 0xA2 -> BinaryOperator.GE;
                case 0xA3 -> BinaryOperator.GT;
                default -> BinaryOperator.LE;
            };
            return foldSimpleComparison(relOp, left, right);
        }

        private ConceptExpr evaluateNullCheck(SymVal ref, boolean checkIsNull, int pc, int opcode) {
            if (ref instanceof SymVal.NullRef) {
                return new ConceptExpr.BoolConst(checkIsNull);
            }
            if (ref instanceof SymVal.InstanceRef
                    || ref instanceof SymVal.OptionalVal
                    || ref instanceof SymVal.Array1DRef
                    || ref instanceof SymVal.Array2DRef
                    || ref instanceof SymVal.Array2DRowRef
                    || ref instanceof SymVal.CollectionRef
                    || ref instanceof SymVal.SoaElemRef
                    || ref instanceof SymVal.ContextRef
                    || ref instanceof SymVal.IteratorRef
                    || ref instanceof SymVal.Scalar) {
                return new ConceptExpr.BoolConst(!checkIsNull);
            }
            if (ref instanceof SymVal.MuxInstanceRef mux) {
                List<Integer> nullIndices = new ArrayList<>();
                for (int i = 0; i < mux.elementIds().size(); i++) {
                    if (mux.elementIds().get(i) < 0) {
                        nullIndices.add(i);
                    }
                }
                if (nullIndices.isEmpty()) {
                    return new ConceptExpr.BoolConst(!checkIsNull);
                }
                if (nullIndices.size() == mux.elementIds().size()) {
                    return new ConceptExpr.BoolConst(checkIsNull);
                }
                ConceptExpr isNullExpr = buildSwitchCaseCondition(mux.indexExpr(), nullIndices);
                return checkIsNull
                        ? isNullExpr
                        : foldSimpleUnary(UnaryOperator.LOGICAL_NOT, isNullExpr, ConceptType.BOOL);
            }
            if (ref instanceof SymVal.LocalObjectRef
                    || ref instanceof SymVal.LocalObjectArrayRef
                    || ref instanceof SymVal.LocalListRef
                    || ref instanceof SymVal.LocalIteratorRef
                    || ref instanceof SymVal.LambdaClosure
                    || ref instanceof SymVal.StringConst) {
                return new ConceptExpr.BoolConst(!checkIsNull);
            }
            if (ref instanceof SymVal.InternedScalar is) {
                BinaryOperator op = checkIsNull ? BinaryOperator.EQ : BinaryOperator.NE;
                return foldSimpleComparison(op, is.expr(), new ConceptExpr.IntConst(ctx.interner.nullObjectId()));
            }
            markImpure(pc, opcode, "null check on uncaptured reference");
            return new ConceptExpr.BoolConst(false);
        }

        private ConceptExpr evaluateRefEq(SymVal l, SymVal r, boolean eq, int pc, int opcode) {
            if (l instanceof SymVal.NullRef) {
                return evaluateNullCheck(r, eq, pc, opcode);
            }
            if (r instanceof SymVal.NullRef) {
                return evaluateNullCheck(l, eq, pc, opcode);
            }
            if (l instanceof SymVal.InstanceRef il && r instanceof SymVal.InstanceRef ir) {
                boolean same = il.instanceId() == ir.instanceId();
                return new ConceptExpr.BoolConst(eq == same);
            }
            if ((l instanceof SymVal.InternedScalar || l instanceof SymVal.NullRef)
                    && (r instanceof SymVal.InternedScalar || r instanceof SymVal.NullRef)) {
                BinaryOperator op = eq ? BinaryOperator.EQ : BinaryOperator.NE;
                return foldSimpleComparison(op, toInternedExpr(l), toInternedExpr(r));
            }
            markImpure(pc, opcode, "reference equality on uncaptured objects");
            return new ConceptExpr.BoolConst(false);
        }

        private void emitReturnInstruction(int opcode, int pc, SymFrame frame, List<ConceptStmt> out) {
            if (activeReturnInterceptor != null) {
                activeReturnInterceptor.sawReturn = true;
                if (opcode == 0xB1) {
                    if (activeReturnInterceptor.doneVar != null) {
                        out.add(new ConceptStmt.Assign(
                                activeReturnInterceptor.doneVar, ConceptType.BOOL, new ConceptExpr.BoolConst(true)));
                    }
                    return;
                }
                SymVal retVal = frame.pop();
                if (opcode == 0xB0 && activeReturnInterceptor.retVar == null) {
                    activeReturnInterceptor.objectReturns.add(retVal);
                    if (activeReturnInterceptor.doneVar != null) {
                        out.add(new ConceptStmt.Assign(
                                activeReturnInterceptor.doneVar, ConceptType.BOOL, new ConceptExpr.BoolConst(true)));
                    }
                    return;
                }
                if (activeReturnInterceptor.retVar != null) {
                    ConceptExpr expr;
                    if (opcode == 0xB0 && returnsInternedObject) {
                        if (retVal instanceof SymVal.NullRef) {
                            expr = new ConceptExpr.IntConst(ctx.interner.nullObjectId());
                        } else if (retVal instanceof SymVal.InternedScalar is) {
                            expr = is.expr();
                        } else if (retVal instanceof SymVal.InstanceRef ir) {
                            InstanceNode n = ctx.lookupInstance(ir.instanceId());
                            if (n != null && n.internedId() != null) {
                                expr = new ConceptExpr.IntConst(n.internedId());
                            } else {
                                markImpure(pc, opcode, "areturn of non-interned object reference");
                                expr = new ConceptExpr.IntConst(ctx.interner.nullObjectId());
                            }
                        } else {
                            markImpure(pc, opcode, "areturn of non-interned object reference");
                            expr = new ConceptExpr.IntConst(ctx.interner.nullObjectId());
                        }
                    } else {
                        expr = asScalarExpr(retVal, activeReturnInterceptor.retType, pc, opcode);
                    }
                    out.add(new ConceptStmt.Assign(
                            activeReturnInterceptor.retVar,
                            activeReturnInterceptor.retType,
                            coerceScalar(expr, activeReturnInterceptor.retType)));
                }
                if (activeReturnInterceptor.doneVar != null) {
                    out.add(new ConceptStmt.Assign(
                            activeReturnInterceptor.doneVar, ConceptType.BOOL, new ConceptExpr.BoolConst(true)));
                }
                return;
            }
            if (opcode == 0xB1) {
                out.add(new ConceptStmt.Return(null));
                return;
            }
            SymVal retVal = frame.pop();
            if (opcode == 0xB0) { // ARETURN
                if (returnsInternedObject) {
                    if (retVal instanceof SymVal.NullRef) {
                        out.add(new ConceptStmt.Return(new ConceptExpr.IntConst(ctx.interner.nullObjectId())));
                        return;
                    }
                    if (retVal instanceof SymVal.InternedScalar is) {
                        out.add(new ConceptStmt.Return(is.expr()));
                        return;
                    }
                    if (retVal instanceof SymVal.InstanceRef ir) {
                        InstanceNode n = ctx.lookupInstance(ir.instanceId());
                        if (n != null && n.internedId() != null) {
                            out.add(new ConceptStmt.Return(new ConceptExpr.IntConst(n.internedId())));
                            return;
                        }
                    }
                }
                if (retVal instanceof SymVal.Scalar sc && expectedReturnType != ConceptType.VOID) {
                    out.add(new ConceptStmt.Return(coerceScalar(sc.expr(), expectedReturnType)));
                    return;
                }
                markImpure(pc, opcode, "areturn of non-interned object reference");
                out.add(new ConceptStmt.Return(BytecodeSsaLifter.defaultConstForType(expectedReturnType)));
                return;
            }
            ConceptExpr expr = asScalarExpr(retVal, expectedReturnType, pc, opcode);
            out.add(new ConceptStmt.Return(coerceScalar(expr, expectedReturnType)));
        }

        // ---------------------------------------------------------------------
        // Helpers
        // ---------------------------------------------------------------------

        private void inlineMethodCall(
                RawClassFile targetConcreteCls,
                RawClassFile targetMethodOwner,
                RawMethod targetMethod,
                SymVal targetReceiver,
                List<SymVal> targetArgs,
                String retDesc,
                int pc,
                int opcode,
                SymFrame frame,
                List<ConceptStmt> out) {
            String callKey = ctx.buildSpecializationKey(
                    targetConcreteCls, targetMethodOwner, targetMethod, targetReceiver, targetArgs);
            if (!shared.inlineCallStack.add(callKey) || shared.inlineCallStack.size() > ctx.options.maxGraphDepth() * 4) {
                markImpure(pc, opcode, "recursive inline call " + callKey);
                frame.terminated = true;
                return;
            }
            try {
                ctx.recordLiftedMethod(targetMethodOwner.thisClass, targetMethod.name());
                ctx.recordLiftedMethod(targetConcreteCls.thisClass, targetMethod.name());
                boolean returnsRef = retDesc.startsWith("L") || retDesc.startsWith("[");
                boolean returnsInterned = returnsRef
                        && !"Ljava/lang/Object;".equals(retDesc)
                        && ctx.hasInternedInstanceOfDescriptor(retDesc);
                boolean isVoid = "V".equals(retDesc);
                ConceptType retType = isVoid
                        ? ConceptType.VOID
                        : (returnsInterned
                                ? ConceptType.I32
                                : (returnsRef ? ConceptType.VOID : ConceptType.fromDescriptorPrefix(retDesc)));
                String retVar = (!isVoid && (!returnsRef || returnsInterned))
                        ? allocTemp("_inl_ret_" + pc, retType)
                        : null;
                ReturnInterceptor interceptor = new ReturnInterceptor(null, retVar, retType);
                if (retVar != null) {
                    ConceptExpr initExpr = returnsInterned
                            ? new ConceptExpr.IntConst(ctx.interner.nullObjectId())
                            : BytecodeSsaLifter.defaultConstForType(retType);
                    out.add(new ConceptStmt.Assign(retVar, retType, initExpr));
                }
                String nestedPrefix = varPrefix + "i" + (shared.nextTempId++) + "_";
                MethodLiftEngine nested = new MethodLiftEngine(
                        ctx,
                        targetConcreteCls,
                        targetMethodOwner,
                        targetMethod,
                        targetReceiver,
                        targetArgs,
                        retType,
                        returnsInterned,
                        shared,
                        nestedPrefix);
                nested.activeReturnInterceptor = interceptor;
                MethodLiftResult res = nested.lift();
                if (!res.pureCompute()) {
                    pure = false;
                    frame.terminated = true;
                    return;
                }
                if (retVar != null
                        && res.body().size() == 1
                        && res.body().get(0) instanceof ConceptStmt.Assign singleAssign
                        && singleAssign.name().equals(retVar)
                        && isConstOrVar(singleAssign.value())) {
                    // Remove dead default init of retVar and propagate scalar directly (preserves constant array sizes!)
                    if (!out.isEmpty()
                            && out.get(out.size() - 1) instanceof ConceptStmt.Assign initA
                            && initA.name().equals(retVar)) {
                        out.remove(out.size() - 1);
                    }
                    if (returnsInterned) {
                        frame.push(new SymVal.InternedScalar(singleAssign.value()));
                    } else {
                        frame.push(new SymVal.Scalar(singleAssign.value()));
                    }
                    return;
                }
                out.addAll(res.body());
                if (isVoid) {
                    return;
                }
                if (returnsInterned) {
                    frame.push(new SymVal.InternedScalar(new ConceptExpr.Var(retVar, ConceptType.I32)));
                } else if (returnsRef) {
                    if (interceptor.objectReturns.isEmpty()) {
                        frame.push(SymVal.NullRef.INSTANCE);
                    } else if (interceptor.objectReturns.size() == 1) {
                        frame.push(interceptor.objectReturns.get(0));
                    } else {
                        SymVal first = interceptor.objectReturns.get(0);
                        boolean allSame = true;
                        for (int i = 1; i < interceptor.objectReturns.size(); i++) {
                            if (!Objects.equals(first, interceptor.objectReturns.get(i))) {
                                allSame = false;
                                break;
                            }
                        }
                        if (allSame) {
                            frame.push(first);
                        } else {
                            markImpure(pc, opcode, "multiple distinct object returns from " + callKey);
                            frame.terminated = true;
                        }
                    }
                } else {
                    frame.push(new SymVal.Scalar(new ConceptExpr.Var(retVar, retType)));
                }
            } finally {
                shared.inlineCallStack.remove(callKey);
            }
        }

        private void stepMuxStaticInvoke(
                MethodRef mRef,
                List<String> paramDescs,
                String retDesc,
                ConceptType retType,
                List<SymVal> callArgs,
                int muxArgIdx,
                SymVal.MuxInstanceRef mux,
                int pc,
                SymFrame frame,
                List<ConceptStmt> out) {
            boolean isVoid = "V".equals(retDesc);
            boolean returnsRef = retDesc.startsWith("L") || retDesc.startsWith("[");
            if (isVoid || returnsRef) {
                markImpure(pc, 0xB8, "mux static dispatch requires scalar return on " + mRef.name());
                frame.terminated = true;
                return;
            }
            ConceptExpr idxRef = ensureSimpleExpr(mux.indexExpr(), pc, out);
            String muxRetVar = allocTemp("_mux_sret_" + pc, retType);
            out.add(new ConceptStmt.Assign(
                    muxRetVar, retType, BytecodeSsaLifter.defaultConstForType(retType)));

            List<ConceptStmt> accElse = List.of();
            for (int i = mux.elementIds().size() - 1; i >= 0; i--) {
                int elemId = mux.elementIds().get(i);
                if (elemId < 0) {
                    continue;
                }
                List<SymVal> armArgs = new ArrayList<>(callArgs);
                armArgs.set(muxArgIdx, ctx.instanceIdToSymVal(elemId));
                SymFrame armFrame = frame.copy();
                List<ConceptStmt> armStmts = new ArrayList<>();
                stepStaticInvoke(mRef, paramDescs, retDesc, retType, armArgs, pc, armFrame, armStmts);
                if (!pure || armFrame.terminated) {
                    frame.terminated = true;
                    return;
                }
                SymVal armRes = armFrame.pop();
                ConceptExpr armExpr = asScalarExpr(armRes, retType, pc, 0xB8);
                armStmts.add(new ConceptStmt.Assign(muxRetVar, retType, coerceScalar(armExpr, retType)));
                ConceptExpr cond = new ConceptExpr.BinaryOp(
                        BinaryOperator.EQ, idxRef, new ConceptExpr.IntConst(i), ConceptType.BOOL);
                accElse = List.of(new ConceptStmt.If(cond, armStmts, accElse));
            }
            out.addAll(accElse);
            frame.push(new SymVal.Scalar(new ConceptExpr.Var(muxRetVar, retType)));
        }

        private void stepMuxInstanceInvoke(
                int opcode,
                MethodRef mRef,
                SymVal.MuxInstanceRef mux,
                List<String> paramDescs,
                String retDesc,
                ConceptType retType,
                List<SymVal> callArgs,
                int pc,
                SymFrame frame,
                List<ConceptStmt> out) {
            boolean isVoid = "V".equals(retDesc);
            boolean returnsRef = retDesc.startsWith("L") || retDesc.startsWith("[");
            if (isVoid || returnsRef) {
                markImpure(pc, opcode, "mux instance dispatch requires scalar return on " + mRef.name());
                frame.terminated = true;
                return;
            }
            ConceptExpr idxRef = ensureSimpleExpr(mux.indexExpr(), pc, out);
            String muxRetVar = allocTemp("_mux_ret_" + pc, retType);
            out.add(new ConceptStmt.Assign(
                    muxRetVar, retType, BytecodeSsaLifter.defaultConstForType(retType)));

            List<ConceptStmt> accElse = List.of();
            for (int i = mux.elementIds().size() - 1; i >= 0; i--) {
                int elemId = mux.elementIds().get(i);
                if (elemId < 0) {
                    continue;
                }
                SymVal elemRecv = ctx.instanceIdToSymVal(elemId);
                SymFrame armFrame = frame.copy();
                List<ConceptStmt> armStmts = new ArrayList<>();
                stepInstanceInvoke(opcode, mRef, elemRecv, paramDescs, retDesc, retType, callArgs, pc, armFrame, armStmts);
                if (!pure || armFrame.terminated) {
                    frame.terminated = true;
                    return;
                }
                SymVal armRes = armFrame.pop();
                ConceptExpr armExpr = asScalarExpr(armRes, retType, pc, opcode);
                armStmts.add(new ConceptStmt.Assign(muxRetVar, retType, coerceScalar(armExpr, retType)));
                ConceptExpr cond = new ConceptExpr.BinaryOp(
                        BinaryOperator.EQ, idxRef, new ConceptExpr.IntConst(i), ConceptType.BOOL);
                accElse = List.of(new ConceptStmt.If(cond, armStmts, accElse));
            }
            out.addAll(accElse);
            frame.push(new SymVal.Scalar(new ConceptExpr.Var(muxRetVar, retType)));
        }

        private ConceptLut tryBuildPrimitiveCollectionLut(SymVal.CollectionRef coll, String methodDesc) {
            ConceptType retT = BytecodeSsaLifter.parseReturnType(methodDesc);
            if (retT != ConceptType.F64 && retT != ConceptType.F32 && retT != ConceptType.I32 && retT != ConceptType.I64) {
                return null;
            }
            ConceptType lutElemType = (retT == ConceptType.F32) ? ConceptType.F64 : retT;
            List<Number> vals = new ArrayList<>(coll.elementIds().size());
            for (int id : coll.elementIds()) {
                InstanceNode elemNode = ctx.lookupInstance(id);
                if (elemNode == null || elemNode.boxedPrimitiveValue() == null) {
                    return null;
                }
                ConceptExpr ce = elemNode.boxedPrimitiveValue();
                if (ce instanceof ConceptExpr.DoubleConst dc) {
                    vals.add(dc.value());
                } else if (ce instanceof ConceptExpr.FloatConst fc) {
                    vals.add((double) fc.value());
                } else if (ce instanceof ConceptExpr.IntConst ic) {
                    vals.add(ic.value());
                } else if (ce instanceof ConceptExpr.LongConst lc) {
                    vals.add(lc.value());
                } else {
                    return null;
                }
            }
            String sym = "lut_coll_" + coll.instanceId();
            ConceptLut lut = new ConceptLut("collection", "elements", sym, lutElemType, vals);
            ctx.registerLut(lut);
            return lut;
        }

        private Object resolveLiveConstantKey(SymVal keySym) {
            if (keySym instanceof SymVal.InstanceRef kir && ctx.dynamicCollector != null) {
                return ctx.dynamicCollector.liveObjectsById.get(kir.instanceId());
            }
            if (keySym instanceof SymVal.StringConst sc) {
                return sc.value();
            }
            return null;
        }

        private ConceptExpr toInternedExpr(SymVal v) {
            if (v instanceof SymVal.InternedScalar is) {
                return is.expr();
            }
            return new ConceptExpr.IntConst(ctx.interner.nullObjectId());
        }

        private ConceptExpr asScalarExpr(SymVal val, ConceptType targetType, int pc, int opcode) {
            if (val instanceof SymVal.Scalar sc) {
                return coerceScalar(sc.expr(), targetType);
            }
            if (val instanceof SymVal.InternedScalar is) {
                return coerceScalar(is.expr(), targetType);
            }
            if (val instanceof SymVal.ColdCacheTagVal) {
                return BytecodeSsaLifter.defaultConstForType(targetType);
            }
            if (val instanceof SymVal.CompareTemp cmp) {
                ConceptExpr lt = foldSimpleComparison(BinaryOperator.LT, cmp.left(), cmp.right());
                ConceptExpr eq = foldSimpleComparison(BinaryOperator.EQ, cmp.left(), cmp.right());
                ConceptExpr inner = new ConceptExpr.Ternary(
                        eq, new ConceptExpr.IntConst(0), new ConceptExpr.IntConst(1), ConceptType.I32);
                ConceptExpr tri = new ConceptExpr.Ternary(lt, new ConceptExpr.IntConst(-1), inner, ConceptType.I32);
                return coerceScalar(tri, targetType);
            }
            markImpure(pc, opcode, "expected scalar value, got non-scalar reference");
            return BytecodeSsaLifter.defaultConstForType(targetType);
        }

        private SymVal materializeForDup(SymVal val, int pc, List<ConceptStmt> out) {
            if (val instanceof SymVal.Scalar sc && !isConstOrVar(sc.expr())) {
                String tmp = allocTemp("_dup_" + pc, sc.expr().type());
                out.add(new ConceptStmt.Assign(tmp, sc.expr().type(), sc.expr()));
                return new SymVal.Scalar(new ConceptExpr.Var(tmp, sc.expr().type()));
            }
            if (val instanceof SymVal.InternedScalar is && !isConstOrVar(is.expr())) {
                String tmp = allocTemp("_dup_" + pc, ConceptType.I32);
                out.add(new ConceptStmt.Assign(tmp, ConceptType.I32, is.expr()));
                return new SymVal.InternedScalar(new ConceptExpr.Var(tmp, ConceptType.I32));
            }
            return val;
        }

        private ConceptExpr ensureSimpleExpr(ConceptExpr expr, int pc, List<ConceptStmt> out) {
            if (isConstOrVar(expr)) {
                return expr;
            }
            String tmp = allocTemp("_tmp_" + pc, expr.type());
            out.add(new ConceptStmt.Assign(tmp, expr.type(), expr));
            return new ConceptExpr.Var(tmp, expr.type());
        }

        private String allocTemp(String prefix, ConceptType type) {
            String name = prefix + "_" + (shared.nextTempId++);
            localTypes.put(name, type);
            return name;
        }

        private String resolveLocalSlotVarName(int slot, int pc, ConceptType type) {
            String base = null;
            int afterPc = pc + BytecodeSsaLifter.instructionLength(method.code(), pc);
            for (LocalVarEntry entry : method.localVariables()) {
                if (entry.slot() == slot && afterPc >= entry.startPc() && afterPc <= entry.startPc() + entry.length()) {
                    base = BytecodeSsaLifter.sanitizeIdentifier(entry.name());
                    break;
                }
            }
            String candidate = (base != null ? base : "v" + slot) + "_s" + slot + "_" + type.name().toLowerCase();
            localTypes.putIfAbsent(candidate, type);
            return candidate;
        }

        private void recordContextUse(int entryParamIndex, ContextParamBinding binding) {
            for (IndexedContextBinding existing : usedContextBindings) {
                if (existing.entryParamIndex() == entryParamIndex
                        && existing.binding().paramName().equals(binding.paramName())) {
                    return;
                }
            }
            usedContextBindings.add(new IndexedContextBinding(entryParamIndex, binding));
        }

        private ConceptExpr evalOrRegisterLutLoad(ConceptLut lut, ConceptExpr index) {
            Integer constIdx = tryEvalConstInt(index);
            if (constIdx != null && constIdx >= 0 && constIdx < lut.values().size()) {
                return numberToConstExpr(lut.values().get(constIdx), lut.elementType());
            }
            ctx.registerLut(lut);
            return new ConceptExpr.LutLoad(lut.symbolName(), index, lut.elementType());
        }
    }

    // -------------------------------------------------------------------------
    // Pure Bytecode Shape Helpers & Constant Evaluation
    // -------------------------------------------------------------------------

    static FieldRef tryExtractZeroArgFieldGetter(RawClassFile owner, RawMethod method) {
        if ((method.accessFlags() & BytecodeSsaLifter.ACC_STATIC) != 0 || method.code() == null) {
            return null;
        }
        if (!method.descriptor().startsWith("()")) {
            return null;
        }
        byte[] code = method.code();
        int pc = 0;
        if (code.length < 5) {
            return null;
        }
        int op0 = code[0] & 0xFF;
        if (op0 == 0x2A) {
            pc = 1;
        } else if (op0 == 0x19 && (code[1] & 0xFF) == 0) {
            pc = 2;
        } else {
            return null;
        }
        if (pc >= code.length || (code[pc] & 0xFF) != 0xB4) {
            return null;
        }
        FieldRef fRef = owner.resolveFieldRef(BytecodeSsaLifter.readUnsignedShort(code, pc + 1));
        pc += 3;
        while (pc < code.length) {
            int op = code[pc] & 0xFF;
            if (op == 0xC0 || op == 0x8D || op == 0x90 || op == 0x00) {
                pc += BytecodeSsaLifter.instructionLength(code, pc);
            } else {
                break;
            }
        }
        if (pc >= code.length) {
            return null;
        }
        int retOp = code[pc] & 0xFF;
        return (retOp >= 0xAC && retOp <= 0xB0) ? fRef : null;
    }

    static int matchLocalLoadSlot(byte[] code, int pc) {
        if (pc >= code.length) {
            return -1;
        }
        int op = code[pc] & 0xFF;
        return switch (op) {
            case 0x15, 0x16, 0x17, 0x18, 0x19 -> (pc + 1 < code.length) ? (code[pc + 1] & 0xFF) : -1;
            case 0x1A, 0x1B, 0x1C, 0x1D -> op - 0x1A;
            case 0x1E, 0x1F, 0x20, 0x21 -> op - 0x1E;
            case 0x22, 0x23, 0x24, 0x25 -> op - 0x22;
            case 0x26, 0x27, 0x28, 0x29 -> op - 0x26;
            case 0x2A, 0x2B, 0x2C, 0x2D -> op - 0x2A;
            default -> -1;
        };
    }

    static ConceptLut lookupLutInMap(Map<String, ConceptLut> map, String owner, String name) {
        if (owner != null) {
            ConceptLut exact = map.get(owner + "#" + name);
            if (exact != null) {
                return exact;
            }
        }
        return map.get(name);
    }

    static ConceptExpr evalOrEmitLutLoad(ConceptLut lut, ConceptExpr index) {
        Integer constIdx = tryEvalConstInt(index);
        if (constIdx != null && constIdx >= 0 && constIdx < lut.values().size()) {
            return numberToConstExpr(lut.values().get(constIdx), lut.elementType());
        }
        return new ConceptExpr.LutLoad(lut.symbolName(), index, lut.elementType());
    }

    static ConceptExpr numberToConstExpr(Number num, ConceptType type) {
        return switch (type) {
            case I64 -> new ConceptExpr.LongConst(num.longValue());
            case F32 -> new ConceptExpr.FloatConst(num.floatValue());
            case F64 -> new ConceptExpr.DoubleConst(num.doubleValue());
            case BOOL -> new ConceptExpr.BoolConst(num.intValue() != 0);
            default -> new ConceptExpr.IntConst(num.intValue());
        };
    }

    static ConceptExpr coerceScalar(ConceptExpr expr, ConceptType targetType) {
        if (targetType == null || targetType == ConceptType.VOID || expr.type() == targetType) {
            return expr;
        }
        if (expr instanceof ConceptExpr.BoolConst bc && targetType == ConceptType.I32) {
            return new ConceptExpr.IntConst(bc.value() ? 1 : 0);
        }
        if (expr instanceof ConceptExpr.IntConst ic) {
            return switch (targetType) {
                case BOOL -> new ConceptExpr.BoolConst(ic.value() != 0);
                case I64 -> new ConceptExpr.LongConst((long) ic.value());
                case F32 -> new ConceptExpr.FloatConst((float) ic.value());
                case F64 -> new ConceptExpr.DoubleConst((double) ic.value());
                default -> ic;
            };
        }
        if (expr instanceof ConceptExpr.LongConst lc) {
            return switch (targetType) {
                case I32 -> new ConceptExpr.IntConst((int) lc.value());
                case F32 -> new ConceptExpr.FloatConst((float) lc.value());
                case F64 -> new ConceptExpr.DoubleConst((double) lc.value());
                default -> lc;
            };
        }
        if (expr instanceof ConceptExpr.FloatConst fc) {
            return switch (targetType) {
                case I32 -> new ConceptExpr.IntConst((int) fc.value());
                case I64 -> new ConceptExpr.LongConst((long) fc.value());
                case F64 -> new ConceptExpr.DoubleConst((double) fc.value());
                default -> fc;
            };
        }
        if (expr instanceof ConceptExpr.DoubleConst dc) {
            return switch (targetType) {
                case I32 -> new ConceptExpr.IntConst((int) dc.value());
                case I64 -> new ConceptExpr.LongConst((long) dc.value());
                case F32 -> new ConceptExpr.FloatConst((float) dc.value());
                default -> dc;
            };
        }
        if (expr.type() == ConceptType.BOOL && targetType == ConceptType.I32) {
            return new ConceptExpr.Ternary(
                    expr, new ConceptExpr.IntConst(1), new ConceptExpr.IntConst(0), ConceptType.I32);
        }
        if (expr.type() == ConceptType.I32 && targetType == ConceptType.BOOL) {
            return new ConceptExpr.BinaryOp(BinaryOperator.NE, expr, new ConceptExpr.IntConst(0), ConceptType.BOOL);
        }
        return new ConceptExpr.Cast(targetType, expr);
    }

    static ConceptExpr foldSimpleUnary(UnaryOperator op, ConceptExpr operand, ConceptType type) {
        if (op == UnaryOperator.LOGICAL_NOT && operand instanceof ConceptExpr.BoolConst bc) {
            return new ConceptExpr.BoolConst(!bc.value());
        }
        if (op == UnaryOperator.NEG) {
            if (operand instanceof ConceptExpr.IntConst ic) {
                return new ConceptExpr.IntConst(-ic.value());
            }
            if (operand instanceof ConceptExpr.LongConst lc) {
                return new ConceptExpr.LongConst(-lc.value());
            }
            if (operand instanceof ConceptExpr.FloatConst fc) {
                return new ConceptExpr.FloatConst(-fc.value());
            }
            if (operand instanceof ConceptExpr.DoubleConst dc) {
                return new ConceptExpr.DoubleConst(-dc.value());
            }
        }
        return new ConceptExpr.UnaryOp(op, operand, type);
    }

    static ConceptExpr foldSimpleBinary(BinaryOperator op, ConceptExpr l, ConceptExpr r, ConceptType type) {
        if (l instanceof ConceptExpr.IntConst li && r instanceof ConceptExpr.IntConst ri) {
            int lv = li.value();
            int rv = ri.value();
            return switch (op) {
                case ADD -> new ConceptExpr.IntConst(lv + rv);
                case SUB -> new ConceptExpr.IntConst(lv - rv);
                case MUL -> new ConceptExpr.IntConst(lv * rv);
                case DIV -> rv != 0 ? new ConceptExpr.IntConst(lv / rv) : new ConceptExpr.BinaryOp(op, l, r, type);
                case REM -> rv != 0 ? new ConceptExpr.IntConst(lv % rv) : new ConceptExpr.BinaryOp(op, l, r, type);
                case BIT_AND -> new ConceptExpr.IntConst(lv & rv);
                case BIT_OR -> new ConceptExpr.IntConst(lv | rv);
                case BIT_XOR -> new ConceptExpr.IntConst(lv ^ rv);
                case SHL -> new ConceptExpr.IntConst(lv << (rv & 31));
                case SHR -> new ConceptExpr.IntConst(lv >> (rv & 31));
                case USHR -> new ConceptExpr.IntConst(lv >>> (rv & 31));
                default -> new ConceptExpr.BinaryOp(op, l, r, type);
            };
        }
        if (l instanceof ConceptExpr.LongConst ll && r instanceof ConceptExpr.LongConst rl) {
            long lv = ll.value();
            long rv = rl.value();
            return switch (op) {
                case ADD -> new ConceptExpr.LongConst(lv + rv);
                case SUB -> new ConceptExpr.LongConst(lv - rv);
                case MUL -> new ConceptExpr.LongConst(lv * rv);
                case BIT_AND -> new ConceptExpr.LongConst(lv & rv);
                case BIT_OR -> new ConceptExpr.LongConst(lv | rv);
                case BIT_XOR -> new ConceptExpr.LongConst(lv ^ rv);
                default -> new ConceptExpr.BinaryOp(op, l, r, type);
            };
        }
        if (l instanceof ConceptExpr.LongConst ll && r instanceof ConceptExpr.IntConst ri) {
            long lv = ll.value();
            int rv = ri.value() & 63;
            return switch (op) {
                case SHL -> new ConceptExpr.LongConst(lv << rv);
                case SHR -> new ConceptExpr.LongConst(lv >> rv);
                case USHR -> new ConceptExpr.LongConst(lv >>> rv);
                default -> new ConceptExpr.BinaryOp(op, l, r, type);
            };
        }
        if (l instanceof ConceptExpr.FloatConst lf && r instanceof ConceptExpr.FloatConst rf) {
            float lv = lf.value();
            float rv = rf.value();
            return switch (op) {
                case ADD -> new ConceptExpr.FloatConst(lv + rv);
                case SUB -> new ConceptExpr.FloatConst(lv - rv);
                case MUL -> new ConceptExpr.FloatConst(lv * rv);
                case DIV -> new ConceptExpr.FloatConst(lv / rv);
                default -> new ConceptExpr.BinaryOp(op, l, r, type);
            };
        }
        if (l instanceof ConceptExpr.DoubleConst ld && r instanceof ConceptExpr.DoubleConst rd) {
            double lv = ld.value();
            double rv = rd.value();
            return switch (op) {
                case ADD -> new ConceptExpr.DoubleConst(lv + rv);
                case SUB -> new ConceptExpr.DoubleConst(lv - rv);
                case MUL -> new ConceptExpr.DoubleConst(lv * rv);
                case DIV -> new ConceptExpr.DoubleConst(lv / rv);
                default -> new ConceptExpr.BinaryOp(op, l, r, type);
            };
        }
        return new ConceptExpr.BinaryOp(op, l, r, type);
    }

    static ConceptExpr foldSimpleComparison(BinaryOperator op, ConceptExpr l, ConceptExpr r) {
        if (l instanceof ConceptExpr.IntConst li && r instanceof ConceptExpr.IntConst ri) {
            int lv = li.value();
            int rv = ri.value();
            boolean res = switch (op) {
                case EQ -> lv == rv;
                case NE -> lv != rv;
                case LT -> lv < rv;
                case LE -> lv <= rv;
                case GT -> lv > rv;
                default -> lv >= rv;
            };
            return new ConceptExpr.BoolConst(res);
        }
        if (l instanceof ConceptExpr.LongConst ll && r instanceof ConceptExpr.LongConst rl) {
            long lv = ll.value();
            long rv = rl.value();
            boolean res = switch (op) {
                case EQ -> lv == rv;
                case NE -> lv != rv;
                case LT -> lv < rv;
                case LE -> lv <= rv;
                case GT -> lv > rv;
                default -> lv >= rv;
            };
            return new ConceptExpr.BoolConst(res);
        }
        if (l instanceof ConceptExpr.FloatConst lf && r instanceof ConceptExpr.FloatConst rf) {
            float lv = lf.value();
            float rv = rf.value();
            boolean res = switch (op) {
                case EQ -> lv == rv;
                case NE -> lv != rv;
                case LT -> lv < rv;
                case LE -> lv <= rv;
                case GT -> lv > rv;
                default -> lv >= rv;
            };
            return new ConceptExpr.BoolConst(res);
        }
        if (l instanceof ConceptExpr.DoubleConst ld && r instanceof ConceptExpr.DoubleConst rd) {
            double lv = ld.value();
            double rv = rd.value();
            boolean res = switch (op) {
                case EQ -> lv == rv;
                case NE -> lv != rv;
                case LT -> lv < rv;
                case LE -> lv <= rv;
                case GT -> lv > rv;
                default -> lv >= rv;
            };
            return new ConceptExpr.BoolConst(res);
        }
        return new ConceptExpr.BinaryOp(op, l, r, ConceptType.BOOL);
    }

    static ConceptExpr foldIfConstIntrinsic(ConceptExpr expr) {
        if (expr instanceof ConceptExpr.MathIntrinsic mi && mi.arguments().size() == 1
                && mi.arguments().get(0) instanceof ConceptExpr.DoubleConst dc) {
            double v = dc.value();
            return switch (mi.kind()) {
                case FLOOR -> new ConceptExpr.DoubleConst(Math.floor(v));
                case CEIL -> new ConceptExpr.DoubleConst(Math.ceil(v));
                case ABS -> new ConceptExpr.DoubleConst(Math.abs(v));
                case SQRT -> new ConceptExpr.DoubleConst(Math.sqrt(v));
                default -> expr;
            };
        }
        return expr;
    }

    static Boolean tryEvalConstBool(ConceptExpr expr) {
        if (expr instanceof ConceptExpr.BoolConst bc) {
            return bc.value();
        }
        return null;
    }

    static Integer tryEvalConstInt(ConceptExpr expr) {
        if (expr instanceof ConceptExpr.IntConst ic) {
            return ic.value();
        }
        return null;
    }

    static boolean isCompileTimeScalarConst(ConceptExpr expr) {
        return expr instanceof ConceptExpr.IntConst
                || expr instanceof ConceptExpr.LongConst
                || expr instanceof ConceptExpr.FloatConst
                || expr instanceof ConceptExpr.DoubleConst
                || expr instanceof ConceptExpr.BoolConst;
    }

    static boolean isConstOrVar(ConceptExpr expr) {
        return expr instanceof ConceptExpr.Var || isCompileTimeScalarConst(expr);
    }

    static boolean isWideSymVal(SymVal v) {
        return v instanceof SymVal.Scalar sc && sc.expr().type().isWideJvmSlot();
    }

    static ConceptType widenScalarTypes(ConceptType a, ConceptType b) {
        if (a == b) {
            return a;
        }
        if (a == ConceptType.F64 || b == ConceptType.F64) {
            return ConceptType.F64;
        }
        if (a == ConceptType.F32 || b == ConceptType.F32) {
            return ConceptType.F32;
        }
        if (a == ConceptType.I64 || b == ConceptType.I64) {
            return ConceptType.I64;
        }
        return ConceptType.I32;
    }

    static boolean isPrimitiveWrapperOwner(String owner) {
        return "java/lang/Integer".equals(owner)
                || "java/lang/Long".equals(owner)
                || "java/lang/Float".equals(owner)
                || "java/lang/Double".equals(owner)
                || "java/lang/Boolean".equals(owner)
                || "java/lang/Byte".equals(owner)
                || "java/lang/Short".equals(owner)
                || "java/lang/Character".equals(owner);
    }

    static boolean isUnboxingMethod(String name, String desc) {
        return ("intValue".equals(name) && "()I".equals(desc))
                || ("longValue".equals(name) && "()J".equals(desc))
                || ("floatValue".equals(name) && "()F".equals(desc))
                || ("doubleValue".equals(name) && "()D".equals(desc))
                || ("booleanValue".equals(name) && "()Z".equals(desc))
                || ("byteValue".equals(name) && "()B".equals(desc))
                || ("shortValue".equals(name) && "()S".equals(desc))
                || ("charValue".equals(name) && "()C".equals(desc));
    }

    static ConceptExpr resolvePureJdkStaticConstant(String owner, String name) {
        if ("java/lang/Math".equals(owner) || "java/lang/StrictMath".equals(owner)) {
            if ("PI".equals(name)) {
                return new ConceptExpr.DoubleConst(Math.PI);
            }
            if ("E".equals(name)) {
                return new ConceptExpr.DoubleConst(Math.E);
            }
        }
        if ("java/lang/Double".equals(owner)) {
            return switch (name) {
                case "NaN" -> new ConceptExpr.DoubleConst(Double.NaN);
                case "POSITIVE_INFINITY" -> new ConceptExpr.DoubleConst(Double.POSITIVE_INFINITY);
                case "NEGATIVE_INFINITY" -> new ConceptExpr.DoubleConst(Double.NEGATIVE_INFINITY);
                case "MAX_VALUE" -> new ConceptExpr.DoubleConst(Double.MAX_VALUE);
                case "MIN_VALUE" -> new ConceptExpr.DoubleConst(Double.MIN_VALUE);
                case "MIN_NORMAL" -> new ConceptExpr.DoubleConst(Double.MIN_NORMAL);
                default -> null;
            };
        }
        if ("java/lang/Float".equals(owner)) {
            return switch (name) {
                case "NaN" -> new ConceptExpr.FloatConst(Float.NaN);
                case "POSITIVE_INFINITY" -> new ConceptExpr.FloatConst(Float.POSITIVE_INFINITY);
                case "NEGATIVE_INFINITY" -> new ConceptExpr.FloatConst(Float.NEGATIVE_INFINITY);
                case "MAX_VALUE" -> new ConceptExpr.FloatConst(Float.MAX_VALUE);
                case "MIN_VALUE" -> new ConceptExpr.FloatConst(Float.MIN_VALUE);
                case "MIN_NORMAL" -> new ConceptExpr.FloatConst(Float.MIN_NORMAL);
                default -> null;
            };
        }
        if ("java/lang/Integer".equals(owner)) {
            if ("MAX_VALUE".equals(name)) {
                return new ConceptExpr.IntConst(Integer.MAX_VALUE);
            }
            if ("MIN_VALUE".equals(name)) {
                return new ConceptExpr.IntConst(Integer.MIN_VALUE);
            }
        }
        if ("java/lang/Long".equals(owner)) {
            if ("MAX_VALUE".equals(name)) {
                return new ConceptExpr.LongConst(Long.MAX_VALUE);
            }
            if ("MIN_VALUE".equals(name)) {
                return new ConceptExpr.LongConst(Long.MIN_VALUE);
            }
        }
        return null;
    }

    static int jvmSlotWidth(String paramDescriptor) {
        if (paramDescriptor != null && !paramDescriptor.isEmpty()) {
            char c = paramDescriptor.charAt(0);
            if (c == 'J' || c == 'D') {
                return 2;
            }
        }
        return 1;
    }

    static List<String> splitMethodParamDescriptors(String descriptor) {
        List<String> out = new ArrayList<>();
        int idx = 1;
        while (idx < descriptor.length() && descriptor.charAt(idx) != ')') {
            int start = idx;
            char c = descriptor.charAt(idx);
            if (c == 'L') {
                idx = descriptor.indexOf(';', idx) + 1;
            } else if (c == '[') {
                while (idx < descriptor.length() && descriptor.charAt(idx) == '[') {
                    idx++;
                }
                if (idx < descriptor.length() && descriptor.charAt(idx) == 'L') {
                    idx = descriptor.indexOf(';', idx) + 1;
                } else {
                    idx++;
                }
            } else {
                idx++;
            }
            out.add(descriptor.substring(start, idx));
        }
        return out;
    }

    static String returnDescriptorSuffix(String descriptor) {
        int close = descriptor.indexOf(')');
        return (close >= 0 && close + 1 < descriptor.length()) ? descriptor.substring(close + 1) : "V";
    }

    static String resolveDebugParamName(RawMethod method, int slot, String fallback) {
        for (LocalVarEntry entry : method.localVariables()) {
            if (entry.slot() == slot && entry.startPc() == 0) {
                return BytecodeSsaLifter.sanitizeIdentifier(entry.name());
            }
        }
        return fallback;
    }

    static String ensureUniqueParamName(Collection<ConceptParam> existing, String candidate) {
        Set<String> names = new HashSet<>();
        for (ConceptParam p : existing) {
            names.add(p.name());
        }
        if (!names.contains(candidate)) {
            return candidate;
        }
        int k = 1;
        while (names.contains(candidate + "_" + k)) {
            k++;
        }
        return candidate + "_" + k;
    }

    static List<IndexedContextBinding> deduplicateContextBindings(List<IndexedContextBinding> list) {
        List<IndexedContextBinding> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (IndexedContextBinding item : list) {
            if (seen.add(item.entryParamIndex() + ":" + item.binding().paramName())) {
                out.add(item);
            }
        }
        return out;
    }

    static String buildFlatJvmDescriptor(List<ConceptParam> params, ConceptType retType) {
        StringBuilder sb = new StringBuilder("(");
        for (ConceptParam p : params) {
            sb.append(toJvmReturnDesc(p.type()));
        }
        sb.append(')').append(toJvmReturnDesc(retType));
        return sb.toString();
    }

    static String toJvmReturnDesc(ConceptType type) {
        return switch (type) {
            case VOID -> "V";
            case BOOL -> "Z";
            case I32 -> "I";
            case I64 -> "J";
            case F32 -> "F";
            case F64 -> "D";
            case I32_ARRAY -> "[I";
            case I64_ARRAY -> "[J";
            case F64_ARRAY -> "[D";
            default -> "J";
        };
    }

    static boolean endsWithDefiniteReturn(List<ConceptStmt> stmts) {
        if (stmts.isEmpty()) {
            return false;
        }
        ConceptStmt last = stmts.get(stmts.size() - 1);
        if (last instanceof ConceptStmt.Return) {
            return true;
        }
        if (last instanceof ConceptStmt.If ifStmt) {
            return !ifStmt.elseBody().isEmpty()
                    && endsWithDefiniteReturn(ifStmt.thenBody())
                    && endsWithDefiniteReturn(ifStmt.elseBody());
        }
        return false;
    }
}
