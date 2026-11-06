package io.github.dailystruggle.bytecode;

import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.CausalChainSlice;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.CausalFinding;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.ClassSharedStateHazard;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.ConfidenceTier;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.EffectFrame;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.EntryPointKind;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.FieldDescriptorMeta;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.FindingKind;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.MethodNode;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.ParsedClass;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.ProofObligation;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.ProofStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Package-private bytecode parity analysis passes implementing {@code corpus-atlas-ADR-002}
 * (§1–§3 Hoare effect frames, semantic macros, and proof obligations; §8 whole-class Eraser
 * lock-set shared-state race detection {@code CAUSAL-RACE}; §9 $N$-hop synchronous main-thread
 * I/O contagion {@code CAUSAL-MAIN-IO} and unclosed resource/ticket leak detection {@code CAUSAL-LEAK}).
 *
 * @governedBy ADR-115, ADR-125, ADR-126, leaffuzz-ADR-001, corpus-atlas-ADR-002
 * @trace REQ-CFG-ATLAS-001, REQ-SLEUTH-FUZZ-004, S-002, S-004, S-005
 */
final class BytecodeParityAnalyzers {

    private static final int MAX_SYNC_IO_HOPS = 4;

    private static final Set<String> LIFECYCLE_METHODS = Set.of(
            "<init>", "<clinit>", "onLoad", "onEnable", "onDisable",
            "load", "start", "stop", "close", "shutdown", "bindPlugin",
            "boot", "init", "initialize", "onConstruct", "onProxyInitialize",
            "onProxyShutdown", "onServerStarting", "onServerStarted", "onServerStopping",
            "unbindServer", "openTransport", "reloadConfigs", "putParser",
            "loadTemplatesFromConfig", "enableLoginCache", "disableLoginCache", "shutDown",
            "attachOpenChannel", "bindBackingFile", "reconstructCorruptPointersOnOpen",
            "materializeFullBufferFromChannel", "connect", "saveChunkInitial"
    );

    private static final List<String> COLLECTION_MUTATOR_SUFFIXES = List.of(
            "#add", "#addAll", "#put", "#putAll", "#putIfAbsent",
            "#remove", "#removeAll", "#clear", "#compute", "#computeIfAbsent",
            "#computeIfPresent", "#merge", "#replace", "#offer", "#poll",
            "#push", "#pop"
    );
    private BytecodeParityAnalyzers() {
    }

    record SharedStateAnalysisResult(
            List<ClassSharedStateHazard> hazards,
            List<CausalFinding> findings
    ) {
    }

    /**
     * Computes the whole-class shared-state field access matrix and flags Eraser lock-set
     * mismatches, unsynchronized async/region mutations, unsafe concurrent collection access,
     * and unsynchronized access-ordered {@link LinkedHashMap} reads ({@code corpus-atlas-ADR-002} §8).
     */
    static SharedStateAnalysisResult computeClassSharedStateMatrix(
            String artifactName,
            int startingFindingSeq,
            Map<String, ParsedClass> classesByName,
            Map<String, MethodNode> methodsBySymbol,
            Set<String> allAsyncSpawnedLambdas
    ) {
        List<ClassSharedStateHazard> hazards = new ArrayList<>();
        List<CausalFinding> findings = new ArrayList<>();
        int seq = startingFindingSeq;

        // Build intra-class caller -> callee lock & async propagation map
        Set<String> transitiveAsyncMethods = new LinkedHashSet<>(allAsyncSpawnedLambdas);
        for (ParsedClass pc : classesByName.values()) {
            for (MethodNode m : pc.methods().values()) {
                if (m.isEventHandler() && isAsyncEventName(m.eventTypeSimpleName())) {
                    transitiveAsyncMethods.add(m.symbol());
                }
            }
        }
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 4) {
            changed = false;
            for ( String asyncSym : new ArrayList<>(transitiveAsyncMethods)) {
                MethodNode caller = methodsBySymbol.get(asyncSym);
                if (caller == null) {
                    continue;
                }
                for (String called : caller.calledMethods()) {
                    MethodNode callee = BytecodeCausalGraphScanner.findMethodNodeFlexible(
                            methodsBySymbol, null, caller.ownerClass(), called);
                    if (callee != null && transitiveAsyncMethods.add(callee.symbol())) {
                        changed = true;
                    }
                }
            }
        }

        for (ParsedClass pc : classesByName.values()) {
            if (!pc.isConcrete() || pc.declaredFieldMeta().isEmpty() || isStackConfinedHelperClass(pc)) {
                continue;
            }
            boolean classHasAccessOrderLru = false;
            boolean classHasConcurrencyOrAsync = false;
            for (MethodNode m : pc.methods().values()) {
                if (m.callsAccessOrderLinkedHashMap()) {
                    classHasAccessOrderLru = true;
                }
                if (!m.acquiredLocks().isEmpty()
                        || m.semanticMacros().contains("@scheduler_dispatch")
                        || m.semanticMacros().contains("@folia_region_guard")
                        || transitiveAsyncMethods.contains(m.symbol())) {
                    classHasConcurrencyOrAsync = true;
                }
            }

            // Compute effective lock sets per method in this class (including multi-hop helpers and nestmate callers)
            Map<String, Set<String>> effectiveLocksByMethod = computeEffectiveLocksForClass(pc, methodsBySymbol);
            Map<String, Set<String>> causalReachable = buildIntraClassCausalReachability(pc);

            for (FieldDescriptorMeta meta : pc.declaredFieldMeta().values()) {
                String fName = meta.name();
                if (fName.startsWith("$")) {
                    continue;
                }
                if (meta.isStatic() && meta.isFinal() && !meta.isUnsafeCollection()) {
                    continue;
                }
                if (meta.isFinal() && !meta.isUnsafeCollection()) {
                    continue;
                }

                String fullField = pc.binaryClassName() + "#" + fName;
                List<MethodNode> readers = new ArrayList<>();
                List<MethodNode> writers = new ArrayList<>();
                List<MethodNode> accessors = new ArrayList<>();

                for (MethodNode m : pc.methods().values()) {
                    String mName = m.name();
                    if (LIFECYCLE_METHODS.contains(mName)
                            || mName.endsWith("ForTesting")
                            || mName.startsWith("lambda$onEnable$")
                            || mName.startsWith("lambda$onLoad$")
                            || mName.startsWith("lambda$onDisable$")
                            || mName.startsWith("lambda$boot$")
                            || mName.startsWith("lambda$onProxyInitialize$")) {
                        continue;
                    }
                    boolean reads = m.readFields().contains(fullField);
                    boolean writes = m.writtenFields().contains(fullField)
                            || m.producedQueueFields().contains(fullField);
                    if (meta.isLinkedHashMap() && classHasAccessOrderLru && reads) {
                        // LinkedHashMap(accessOrder=true)#get mutates internal doubly-linked list order
                        writes = true;
                    }
                    if (!reads && !writes) {
                        continue;
                    }
                    accessors.add(m);
                    if (reads) {
                        readers.add(m);
                    }
                    if (writes) {
                        writers.add(m);
                    }
                }

                if (accessors.size() < 2 || writers.isEmpty()) {
                    continue;
                }

                boolean anyLocked = false;
                boolean anyWriterLocked = false;
                boolean anyIntrinsicThisLocked = false;
                boolean anyAsync = false;
                boolean anyFoliaGuarded = false;
                boolean allFoliaGuarded = true;
                Set<String> commonLocks = null;
                Set<String> writerCommonLocks = null;

                for (MethodNode acc : accessors) {
                    Set<String> locks = effectiveLocksByMethod.getOrDefault(acc.symbol(), acc.acquiredLocks());
                    if (!locks.isEmpty()) {
                        anyLocked = true;
                        if (locks.contains("this")) {
                            anyIntrinsicThisLocked = true;
                        }
                    }
                    if (commonLocks == null) {
                        commonLocks = new LinkedHashSet<>(locks);
                    } else {
                        commonLocks.retainAll(locks);
                    }
                    if (transitiveAsyncMethods.contains(acc.symbol())
                            || acc.semanticMacros().contains("@scheduler_dispatch")
                            || acc.directEffectSinks().contains("ASYNC_SCHEDULER")) {
                        anyAsync = true;
                    }
                    if (acc.semanticMacros().contains("@folia_region_guard")) {
                        anyFoliaGuarded = true;
                    } else {
                        allFoliaGuarded = false;
                    }
                }
                boolean anyUnlockedReadModifyWrite = false;
                boolean anyIntrinsicThisWriterLocked = false;
                boolean isNumericScalar = "I".equals(meta.descriptor())
                        || "J".equals(meta.descriptor())
                        || "D".equals(meta.descriptor())
                        || "F".equals(meta.descriptor())
                        || "S".equals(meta.descriptor())
                        || "B".equals(meta.descriptor());
                for (MethodNode w : writers) {
                    Set<String> locks = effectiveLocksByMethod.getOrDefault(w.symbol(), w.acquiredLocks());
                    if (!locks.isEmpty()) {
                        anyWriterLocked = true;
                        if (locks.contains("this")) {
                            anyIntrinsicThisWriterLocked = true;
                        }
                    } else if (w.readFields().contains(fullField) && (!meta.isVolatile() || isNumericScalar)) {
                        anyUnlockedReadModifyWrite = true;
                    }
                    if (writerCommonLocks == null) {
                        writerCommonLocks = new LinkedHashSet<>(locks);
                    } else {
                        writerCommonLocks.retainAll(locks);
                    }
                }

                if (commonLocks == null) {
                    commonLocks = Set.of();
                }
                if (writerCommonLocks == null) {
                    writerCommonLocks = Set.of();
                }

                boolean effectivelyUnsafeCollection = meta.isUnsafeCollection()
                        && (!meta.isVolatile() || hasInPlaceCollectionMutation(accessors));

                // Exemption 1: All accessors share a common lock
                if (!commonLocks.isEmpty()) {
                    continue;
                }
                // Exemption 2: All accessors are serialized by @folia_region_guard (DSN-1)
                if (allFoliaGuarded && anyFoliaGuarded) {
                    continue;
                }
                // Exemption 3: Copy-on-Write / Guarded-Write + Lock-Free Reader (JCIP §3.1.4 & RCU snapshot publication)
                boolean hasExplicitUnlockedTestReader = readers.stream().anyMatch(r -> r.name().endsWith("Unlocked"));
                boolean guardedWriteLockFreeReadSafe = !writerCommonLocks.isEmpty()
                        && !hasExplicitUnlockedTestReader
                        && (!effectivelyUnsafeCollection || writers.stream().noneMatch(w ->
                        BytecodeCausalGraphScanner.classifyEntryPoint(w, pc, allAsyncSpawnedLambdas) != null));
                if (guardedWriteLockFreeReadSafe) {
                    continue;
                }
                // Exemption 4: Causal Chaining / Single-Flight Pipeline Serialization
                if (!anyWriterLocked && areAccessorsCausallySerialized(
                        pc,
                        meta,
                        writers,
                        accessors,
                        causalReachable,
                        allAsyncSpawnedLambdas,
                        effectivelyUnsafeCollection
                )) {
                    continue;
                }

                String hazardKind = null;
                String hazardReason = null;

                if (meta.isLinkedHashMap() && classHasAccessOrderLru && (accessors.size() >= 2 || anyAsync)) {
                    hazardKind = "ACCESS_ORDERED_LRU_UNSYNCHRONIZED_GET";
                    hazardReason = "Access-ordered LinkedHashMap '" + fName
                            + "' mutates internal LRU node links on get()/put() across "
                            + formatMethodList(accessors) + " with empty common lock intersection.";
                } else if (accessors.size() >= 2 && anyLocked && (anyWriterLocked || anyIntrinsicThisLocked)
                        && (!meta.isVolatile() || (anyIntrinsicThisWriterLocked && writerCommonLocks.isEmpty()) || effectivelyUnsafeCollection)) {
                    hazardKind = "LOCK_SET_MISMATCH";
                    hazardReason = "Shared field '" + fName + "' has non-empty lock acquisition in some accessors but empty common lock intersection across writers "
                            + formatMethodList(writers) + " and readers " + formatMethodList(readers) + ".";
                } else if (!meta.isThreadSafeType()
                        && (anyAsync || (anyFoliaGuarded && !allFoliaGuarded))
                        && (!meta.isVolatile() || anyUnlockedReadModifyWrite || effectivelyUnsafeCollection)) {
                    hazardKind = "ASYNC_OR_REGION_UNSYNCHRONIZED";
                    hazardReason = "Shared field '" + fName + "' is accessed across async/scheduler or mixed-region methods "
                            + formatMethodList(accessors) + " without synchronization or common lock.";
                } else if (effectivelyUnsafeCollection && classHasConcurrencyOrAsync && (accessors.size() >= 2 || anyAsync)) {
                    hazardKind = "UNSAFE_COLLECTION_CONCURRENT_ACCESS";
                    hazardReason = "Non-thread-safe collection field '" + fName + "' (" + readableFieldType(meta.descriptor())
                            + ") is mutated by " + formatMethodList(writers)
                            + " and accessed by " + formatMethodList(accessors)
                            + " in a concurrent/scheduled class with no common lock.";
                }
                if (hazardKind != null) {
                    List<String> writerSyms = writers.stream().map(MethodNode::symbol).toList();
                    List<String> readerSyms = readers.stream().map(MethodNode::symbol).toList();
                    ClassSharedStateHazard hazard = new ClassSharedStateHazard(
                            pc.binaryClassName(),
                            fName,
                            readableFieldType(meta.descriptor()),
                            hazardKind,
                            hazardReason,
                            writerSyms,
                            readerSyms,
                            new ArrayList<>(commonLocks)
                    );
                    hazards.add(hazard);

                    String primarySym = writers.get(0).symbol();
                    List<String> steps = new ArrayList<>();
                    steps.add("FIELD " + BytecodeCausalGraphScanner.simpleClassName(pc.binaryClassName()) + "#" + fName
                            + " [" + hazardKind + "]");
                    for (MethodNode w : writers) {
                        steps.add("WRITER " + BytecodeCausalGraphScanner.simpleMethodRef(w.symbol())
                                + " locks=" + effectiveLocksByMethod.getOrDefault(w.symbol(), w.acquiredLocks()));
                    }
                    for (MethodNode r : readers) {
                        if (!writers.contains(r)) {
                            steps.add("READER " + BytecodeCausalGraphScanner.simpleMethodRef(r.symbol())
                                    + " locks=" + effectiveLocksByMethod.getOrDefault(r.symbol(), r.acquiredLocks()));
                        }
                    }

                    findings.add(new CausalFinding(
                            "CAUSAL-" + (seq++),
                            artifactName,
                            2,
                            FindingKind.CAUSAL_SHARED_STATE_RACE,
                            ConfidenceTier.CONFIRMED,
                            95,
                            primarySym,
                            BytecodeCausalGraphScanner.simpleClassName(pc.binaryClassName())
                                    + " maintains shared state field '" + fName + "' (" + readableFieldType(meta.descriptor()) + ")",
                            hazardReason,
                            "Guard all reads and writes to '" + fName + "' with a consistent lock, use a concurrent/atomic data structure, or confine access via @folia_region_guard.",
                            steps,
                            ""
                    ));
                }
            }
        }

        return new SharedStateAnalysisResult(hazards, findings);
    }

    /**
     * Propagates caller locks across multi-hop intra-class and nestmate call chains when every
     * caller of a helper holds a common lock (or when a helper is explicitly named {@code *Locked}).
     */
    private static Map<String, Set<String>> computeEffectiveLocksForClass(
            ParsedClass pc,
            Map<String, MethodNode> methodsBySymbol
    ) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Map<String, List<MethodNode>> callersByTarget = new LinkedHashMap<>();
        for (MethodNode m : pc.methods().values()) {
            out.computeIfAbsent(m.symbol(), k -> new LinkedHashSet<>()).addAll(m.acquiredLocks());
        }
        for (MethodNode caller : methodsBySymbol.values()) {
            if (!sameTopLevelClass(caller.ownerClass(), pc.binaryClassName())) {
                continue;
            }
            out.computeIfAbsent(caller.symbol(), k -> new LinkedHashSet<>()).addAll(caller.acquiredLocks());
            for (String called : caller.calledMethods()) {
                if (called.startsWith(pc.binaryClassName() + "#")) {
                    String targetName = called.substring(pc.binaryClassName().length() + 1);
                    MethodNode target = pc.methods().get(targetName);
                    if (target != null && !target.symbol().equals(caller.symbol())) {
                        callersByTarget.computeIfAbsent(target.symbol(), k -> new ArrayList<>()).add(caller);
                    }
                }
            }
        }
        boolean changed = true;
        int iter = 0;
        while (changed && iter++ < 6) {
            changed = false;
            for (MethodNode m : pc.methods().values()) {
                Set<String> curLocks = out.get(m.symbol());
                if (curLocks != null && !curLocks.isEmpty()) {
                    continue;
                }
                List<MethodNode> callers = callersByTarget.get(m.symbol());
                if (callers == null || callers.isEmpty()) {
                    continue;
                }
                if (m.name().endsWith("Locked")) {
                    Set<String> unionLocks = new LinkedHashSet<>();
                    for (MethodNode c : callers) {
                        unionLocks.addAll(out.getOrDefault(c.symbol(), c.acquiredLocks()));
                    }
                    if (!unionLocks.isEmpty()) {
                        out.get(m.symbol()).addAll(unionLocks);
                        changed = true;
                    }
                    continue;
                }
                Set<String> callerCommon = null;
                for (MethodNode c : callers) {
                    if (LIFECYCLE_METHODS.contains(c.name())) {
                        continue;
                    }
                    Set<String> rawLocks = out.getOrDefault(c.symbol(), c.acquiredLocks());
                    Set<String> cLocks;
                    if (!c.ownerClass().equals(pc.binaryClassName()) && !rawLocks.isEmpty()) {
                        cLocks = new LinkedHashSet<>(rawLocks);
                        cLocks.add("this");
                    } else {
                        cLocks = rawLocks;
                    }
                    if (callerCommon == null) {
                        callerCommon = new LinkedHashSet<>(cLocks);
                    } else {
                        callerCommon.retainAll(cLocks);
                    }
                }
                if (callerCommon != null && !callerCommon.isEmpty()) {
                    out.get(m.symbol()).addAll(callerCommon);
                    changed = true;
                }
            }
        }
        return out;
    }

    private static boolean sameTopLevelClass(String clsA, String clsB) {
        if (clsA == null || clsB == null) {
            return false;
        }
        if (clsA.equals(clsB)) {
            return true;
        }
        int dA = clsA.indexOf('$');
        int dB = clsB.indexOf('$');
        String topA = dA >= 0 ? clsA.substring(0, dA) : clsA;
        String topB = dB >= 0 ? clsB.substring(0, dB) : clsB;
        return topA.equals(topB);
    }

    /**
     * Builds the intra-class causal reachability map {@code u -> {v | u ->* v}} over direct
     * method calls and continuation lambda spawns within {@code pc}.
     */
    private static Map<String, Set<String>> buildIntraClassCausalReachability(ParsedClass pc) {
        Map<String, Set<String>> adj = new LinkedHashMap<>();
        String prefix = pc.binaryClassName() + "#";
        for (MethodNode m : pc.methods().values()) {
            Set<String> edges = adj.computeIfAbsent(m.symbol(), k -> new LinkedHashSet<>());
            for (String called : m.calledMethods()) {
                if (called.startsWith(prefix)) {
                    MethodNode target = pc.methods().get(called.substring(prefix.length()));
                    if (target != null && !target.symbol().equals(m.symbol())) {
                        edges.add(target.symbol());
                    }
                }
            }
            for (String spawned : m.spawnedLambdas()) {
                String lName = spawned.contains("#") ? spawned.substring(spawned.indexOf('#') + 1) : spawned;
                MethodNode target = pc.methods().get(lName);
                if (target != null && !target.symbol().equals(m.symbol())) {
                    edges.add(target.symbol());
                }
            }
        }
        Map<String, Set<String>> reach = new LinkedHashMap<>();
        for (MethodNode m : pc.methods().values()) {
            Set<String> visited = new LinkedHashSet<>();
            Deque<String> q = new ArrayDeque<>();
            q.add(m.symbol());
            visited.add(m.symbol());
            while (!q.isEmpty()) {
                String cur = q.poll();
                for (String nxt : adj.getOrDefault(cur, Set.of())) {
                    if (visited.add(nxt)) {
                        q.add(nxt);
                    }
                }
            }
            reach.put(m.symbol(), visited);
        }
        return reach;
    }

    /**
     * Determines whether accesses to {@code meta} are causally serialized (cannot spawn two
     * concurrent threads to mutate the same field on the same instance).
     */
    private static boolean areAccessorsCausallySerialized(
            ParsedClass pc,
            FieldDescriptorMeta meta,
            List<MethodNode> writers,
            List<MethodNode> accessors,
            Map<String, Set<String>> causalReachable,
            Set<String> allAsyncSpawnedLambdas,
            boolean effectivelyUnsafeCollection
    ) {
        if (meta.isStatic()) {
            return false;
        }
        // If any accessor is an unguarded COMMAND or EVENT handler on pc that spawns an async
        // writer lambda (e.g. onCommand -> runAsync(() -> unsafeScores.put(...))), multiple
        // invocations or parent-child overlap can race on the same singleton instance.
        Set<String> writerSymbols = new LinkedHashSet<>();
        for (MethodNode w : writers) {
            writerSymbols.add(w.symbol());
        }
        for (MethodNode acc : accessors) {
            EntryPointKind ep = BytecodeCausalGraphScanner.classifyEntryPoint(acc, pc, allAsyncSpawnedLambdas);
            if (ep == EntryPointKind.COMMAND || ep == EntryPointKind.EVENT) {
                Set<String> r = causalReachable.getOrDefault(acc.symbol(), Set.of());
                for (String wSym : writerSymbols) {
                    if (wSym.contains("lambda$") && r.contains(wSym)) {
                        return false;
                    }
                }
                if (effectivelyUnsafeCollection) {
                    return false;
                }
            }
        }
        // Single-writer producer edit (writers.size() == 1) where the writer does not fork
        // parallel async lambdas that also write the field
        if (writers.size() == 1 && !effectivelyUnsafeCollection) {
            return true;
        }
        // Multi-stage causal pipeline: all writers are reachable from a single pipeline root method
        // in pc (Stage k -> Stage k+1 via continuation handoff)
        if (!effectivelyUnsafeCollection) {
            for (Set<String> reachableFromRoot : causalReachable.values()) {
                if (reachableFromRoot.containsAll(writerSymbols)) {
                    return true;
                }
            }
            // Or writers are mutually ordered / share a common pipeline ancestor or non-concurrent setter
            boolean allNonLambdaOrChained = true;
            for (MethodNode w : writers) {
                if (w.name().startsWith("lambda$")) {
                    boolean chainedFromStage = false;
                    for (MethodNode other : pc.methods().values()) {
                        if (!other.symbol().equals(w.symbol())
                                && BytecodeCausalGraphScanner.classifyEntryPoint(other, pc, allAsyncSpawnedLambdas) != EntryPointKind.COMMAND
                                && BytecodeCausalGraphScanner.classifyEntryPoint(other, pc, allAsyncSpawnedLambdas) != EntryPointKind.EVENT
                                && causalReachable.getOrDefault(other.symbol(), Set.of()).contains(w.symbol())) {
                            chainedFromStage = true;
                            break;
                        }
                    }
                    if (!chainedFromStage) {
                        allNonLambdaOrChained = false;
                        break;
                    }
                }
            }
            if (allNonLambdaOrChained) {
                return true;
            }
        }
        return false;
    }

    static List<CausalFinding> detectSyncMainThreadIoContagion(
            String artifactName,
            int startingFindingSeq,
            Map<String, ParsedClass> classesByName,
            Map<String, MethodNode> methodsBySymbol,
            Map<String, List<MethodNode>> methodsByName,
            Set<String> allAsyncSpawnedLambdas
    ) {
        List<CausalFinding> findings = new ArrayList<>();
        int seq = startingFindingSeq;
        Set<String> reportedEntryPoints = new LinkedHashSet<>();

        for (ParsedClass pc : classesByName.values()) {
            if (!pc.isConcrete()) {
                continue;
            }
            for (MethodNode root : pc.methods().values()) {
                EntryPointKind epKind = BytecodeCausalGraphScanner.classifyEntryPoint(root, pc, allAsyncSpawnedLambdas);
                if (epKind != EntryPointKind.COMMAND && epKind != EntryPointKind.EVENT) {
                    continue;
                }
                if (epKind == EntryPointKind.EVENT && isAsyncEventName(root.eventTypeSimpleName())) {
                    continue;
                }
                if (isShutdownLifecycleHandler(root)) {
                    continue;
                }
                if (!reportedEntryPoints.add(root.symbol())) {
                    continue;
                }

                // BFS along synchronous direct method calls (do not follow spawned async lambdas)
                Deque<SyncIoHop> queue = new ArrayDeque<>();
                Set<String> visited = new LinkedHashSet<>();
                queue.add(new SyncIoHop(root, 0, List.of(root.symbol())));
                visited.add(root.symbol());

                List<String> winningChain = null;
                Set<String> reachedBlockingSinks = new LinkedHashSet<>();

                while (!queue.isEmpty()) {
                    SyncIoHop cur = queue.poll();
                    MethodNode node = cur.node();

                    if (!node.blockingIoCalls().isEmpty()) {
                        reachedBlockingSinks.addAll(node.blockingIoCalls());
                        if (winningChain == null) {
                            winningChain = cur.chain();
                        }
                    }

                    if (cur.depth() >= MAX_SYNC_IO_HOPS || visited.size() >= 128) {
                        continue;
                    }

                    for (String called : node.calledMethods()) {
                        if (isAsyncSchedulerDispatchCall(called)) {
                            continue;
                        }
                        MethodNode target = BytecodeCausalGraphScanner.findMethodNodeFlexible(
                                methodsBySymbol, methodsByName, node.ownerClass(), called);
                        if (target == null || allAsyncSpawnedLambdas.contains(target.symbol())) {
                            continue;
                        }
                        if (visited.add(target.symbol())) {
                            List<String> nextChain = new ArrayList<>(cur.chain().size() + 1);
                            nextChain.addAll(cur.chain());
                            nextChain.add(target.symbol());
                            queue.add(new SyncIoHop(target, cur.depth() + 1, nextChain));
                        }
                    }
                }

                if (!reachedBlockingSinks.isEmpty() && winningChain != null) {
                    List<String> hopSteps = new ArrayList<>(winningChain.size() + 1);
                    for (int i = 0; i < winningChain.size(); i++) {
                        hopSteps.add("Hop " + i + ": " + BytecodeCausalGraphScanner.simpleMethodRef(winningChain.get(i)));
                    }
                    hopSteps.add("BLOCKING_IO_SINK: " + reachedBlockingSinks);

                    String entryLabel = epKind == EntryPointKind.COMMAND
                            ? ("Synchronous command handler " + BytecodeCausalGraphScanner.simpleMethodRef(root.symbol()))
                            : ("Synchronous event handler " + BytecodeCausalGraphScanner.simpleMethodRef(root.symbol())
                            + " (" + root.eventTypeSimpleName() + ")");

                      findings.add(new CausalFinding(
                              "CAUSAL-" + (seq++),
                              artifactName,
                              2,
                              FindingKind.CAUSAL_SYNC_MAIN_THREAD_IO,
                              ConfidenceTier.CONFIRMED,
                              95,
                              root.symbol(),
                              entryLabel + " executes on the server main/region thread",
                              "Reaches synchronous blocking I/O sink " + reachedBlockingSinks
                                      + " within " + (winningChain.size() - 1) + " synchronous hop(s) without async scheduler dispatch (blocks the main server thread and causes tick lag spikes).",
                              "Offload blocking file/JDBC/Future I/O to AsyncScheduler / CompletableFuture.supplyAsync and replace synchronous getChunkAt/loadChunk with getChunkAtAsync.",
                              hopSteps,
                              ""
                      ));
                  }
              }
          }

          return findings;
      }

      private record SyncIoHop(MethodNode node, int depth, List<String> chain) {
      }

      /**
       * Detects intra-method {@code Closeable}, file stream/channel, and {@code ChunkTicket}
       * allocations that are neither returned to the caller nor closed/tracked across exit paths,
       * as well as session-entity ({@code Player} / {@code ServerPlayer}) collection fields whose
       * entries are inserted during runtime without any removal path reachable from a registered
       * disconnect/quit handler or whose delayed removal is guarded behind {@code Player#isOnline}
       * ({@code corpus-atlas-ADR-002} §9, {@code CAUSAL-LEAK}, {@code S-002}).
       */
      static List<CausalFinding> detectUnclosedResourceLeaks(
              String artifactName,
              int startingFindingSeq,
              Map<String, ParsedClass> classesByName
      ) {
          return detectUnclosedResourceLeaks(
                  artifactName,
                  startingFindingSeq,
                  classesByName,
                  Map.of(),
                  Map.of(),
                  Map.of()
          );
      }

      static List<CausalFinding> detectUnclosedResourceLeaks(
              String artifactName,
              int startingFindingSeq,
              Map<String, ParsedClass> classesByName,
              Map<String, MethodNode> methodsBySymbol,
              Map<String, List<MethodNode>> methodsByName,
              Map<String, BytecodeCausalGraphScanner.ListenerRegistrationMode> listenerRegistrations
      ) {
          List<CausalFinding> findings = new ArrayList<>();
          int seq = startingFindingSeq;

          for (ParsedClass pc : classesByName.values()) {
              if (!pc.isConcrete()) {
                  continue;
              }
              for (MethodNode m : pc.methods().values()) {
                  if (m.allocatedResources().isEmpty() || m.closesOrTracksResourcesOnAllPaths()) {
                      continue;
                  }
                  findings.add(new CausalFinding(
                          "CAUSAL-" + (seq++),
                          artifactName,
                          2,
                          FindingKind.CAUSAL_UNCLOSED_RESOURCE_LEAK,
                          ConfidenceTier.CONFIRMED,
                          95,
                          m.symbol(),
                          BytecodeCausalGraphScanner.simpleMethodRef(m.symbol())
                                  + " allocates closeable resource/ticket " + m.allocatedResources(),
                          m.resourceLeakReason(),
                          "Wrap " + m.allocatedResources() + " in try-with-resources, invoke close()/release()/removePluginChunkTicket() in a finally block, or register tickets with a lifecycle tracker.",
                          List.of(
                                  m.symbol() + " --[allocates " + m.allocatedResources() + "]--> [UNCLOSED_ON_EXIT]"
                          ),
                          ""
                  ));
              }
          }

        if (!methodsBySymbol.isEmpty()) {
            List<CausalFinding> sessionLeaks = detectSessionEntityCollectionLeaks(
                    artifactName,
                    seq,
                    classesByName,
                    methodsBySymbol,
                    methodsByName,
                    listenerRegistrations
            );
            findings.addAll(sessionLeaks);
        }

        return findings;
    }

    private static final List<String> SESSION_ENTITY_DESCRIPTORS = List.of(
            "Lorg/bukkit/entity/Player;",
            "Lorg/bukkit/entity/HumanEntity;",
            "Lorg/bukkit/command/CommandSender;",
            "Lnet/minecraft/server/level/ServerPlayer;",
            "Lnet/minecraft/world/entity/player/Player;",
            "Lcom/velocitypowered/api/proxy/Player;",
            "Lnet/md_5/bungee/api/connection/ProxiedPlayer;",
            "FakePlayer;"
    );

    private static List<CausalFinding> detectSessionEntityCollectionLeaks(
            String artifactName,
            int startingSeq,
            Map<String, ParsedClass> classesByName,
            Map<String, MethodNode> methodsBySymbol,
            Map<String, List<MethodNode>> methodsByName,
            Map<String, BytecodeCausalGraphScanner.ListenerRegistrationMode> listenerRegistrations
    ) {
        List<CausalFinding> findings = new ArrayList<>();
        int seq = startingSeq;

        // 1. Identify wrapper classes in the JAR that hold a strong reference to a Player/ServerPlayer
        //    (or an Object field assigned a Player/CommandSender in <init>) without WeakReference/SoftReference
        Set<String> playerHoldingWrapperDescriptors = new LinkedHashSet<>();
        for (ParsedClass pc : classesByName.values()) {
            if (!pc.isConcrete()) {
                continue;
            }
            boolean holdsStrongPlayer = false;
            for (FieldDescriptorMeta fm : pc.declaredFieldMeta().values()) {
                if (fm.isStatic()) {
                    continue;
                }
                String desc = fm.descriptor();
                String sig = fm.signature();
                if (isWeakOrBoundedContainer(desc, sig)) {
                    continue;
                }
                if (containsSessionEntityType(desc) || containsSessionEntityType(sig)) {
                    holdsStrongPlayer = true;
                    break;
                }
            }
            if (!holdsStrongPlayer && pc.binaryClassName().contains("$")) {
                // Check inner/ticket classes that store an Object field along with a payload and are constructed from a CommandSender/Player method
                boolean hasObjectField = pc.declaredFieldMeta().values().stream()
                        .anyMatch(f -> !f.isStatic() && "Ljava/lang/Object;".equals(f.descriptor())
                                && (f.name().toLowerCase(Locale.ROOT).contains("player")
                                || f.name().toLowerCase(Locale.ROOT).contains("sender")
                                || f.name().toLowerCase(Locale.ROOT).contains("holder")));
                if (hasObjectField) {
                    holdsStrongPlayer = true;
                }
            }
            if (holdsStrongPlayer) {
                playerHoldingWrapperDescriptors.add("L" + pc.binaryClassName().replace('.', '/') + ";");
            }
        }

        // 2. Map 1-hop collection getters (Method#symbol -> fullField)
        Map<String, String> getterToField = new LinkedHashMap<>();
        for (ParsedClass pc : classesByName.values()) {
            for (MethodNode m : pc.methods().values()) {
                if (m.returnedField() != null && !m.returnedField().isEmpty()) {
                    getterToField.put(m.symbol(), m.returnedField());
                }
            }
        }

        // 3. Identify candidate session-entity collection fields across all concrete classes
        Map<String, FieldDescriptorMeta> sessionCollectionFields = new LinkedHashMap<>();
        for (ParsedClass pc : classesByName.values()) {
            if (!pc.isConcrete()) {
                continue;
            }
            for (FieldDescriptorMeta fm : pc.declaredFieldMeta().values()) {
                String desc = fm.descriptor();
                String sig = fm.signature();
                if (!isCollectionOrMapDescriptor(desc) || isWeakOrBoundedContainer(desc, sig)) {
                    continue;
                }
                boolean holdsSessionEntity = containsSessionEntityType(sig);
                if (!holdsSessionEntity && ! sig.isEmpty()) {
                    for (String wrapperDesc : playerHoldingWrapperDescriptors) {
                        if (sig.contains(wrapperDesc)) {
                            holdsSessionEntity = true;
                            break;
                        }
                    }
                }
                if (holdsSessionEntity) {
                    sessionCollectionFields.put(pc.binaryClassName() + "#" + fm.name(), fm);
                }
            }
        }

        if (sessionCollectionFields.isEmpty()) {
            return findings;
        }

        // 4. Collect adder and remover methods for each candidate session collection field
        Map<String, Set<MethodNode>> addersByField = new LinkedHashMap<>();
        Map<String, Set<MethodNode>> removersByField = new LinkedHashMap<>();
        for (ParsedClass pc : classesByName.values()) {
            for (MethodNode m : pc.methods().values()) {
                for (String rawAdd : m.collectionAddedFields()) {
                    String resolved = resolveCollectionFieldTarget(rawAdd, m.ownerClass(), getterToField, methodsBySymbol, methodsByName);
                    if (resolved != null && sessionCollectionFields.containsKey(resolved)) {
                        addersByField.computeIfAbsent(resolved, k -> new LinkedHashSet<>()).add(m);
                    }
                }
                for (String rawRem : m.collectionRemovedFields()) {
                    String resolved = resolveCollectionFieldTarget(rawRem, m.ownerClass(), getterToField, methodsBySymbol, methodsByName);
                    if (resolved != null && sessionCollectionFields.containsKey(resolved)) {
                        removersByField.computeIfAbsent(resolved, k -> new LinkedHashSet<>()).add(m);
                    }
                }
            }
        }

        // 5. Compute all methods causally reachable from any registered disconnect / quit handler
        Set<String> quitReachableMethods = new LinkedHashSet<>();
        Deque<MethodNode> quitQueue = new ArrayDeque<>();
        for (ParsedClass pc : classesByName.values()) {
            if (listenerRegistrations.getOrDefault(pc.binaryClassName(), BytecodeCausalGraphScanner.ListenerRegistrationMode.REGISTERED_UNCONDITIONAL)
                    == BytecodeCausalGraphScanner.ListenerRegistrationMode.UNREGISTERED) {
                continue;
            }
            for (MethodNode m : pc.methods().values()) {
                if (isDisconnectOrQuitHandler(m)) {
                    if (quitReachableMethods.add(m.symbol())) {
                        quitQueue.add(m);
                    }
                }
            }
        }
        int bfsSteps = 0;
        while (!quitQueue.isEmpty() && bfsSteps++ < 1024) {
            MethodNode cur = quitQueue.poll();
            for (String called : cur.calledMethods()) {
                MethodNode next = BytecodeCausalGraphScanner.findMethodNodeFlexible(
                        methodsBySymbol, methodsByName, cur.ownerClass(), called);
                if (next != null && quitReachableMethods.add(next.symbol())) {
                    quitQueue.add(next);
                }
            }
            for (String lam : cur.spawnedLambdas()) {
                MethodNode next = BytecodeCausalGraphScanner.findMethodNodeFlexible(
                        methodsBySymbol, methodsByName, cur.ownerClass(), lam);
                if (next != null && quitReachableMethods.add(next.symbol())) {
                    quitQueue.add(next);
                }
            }
        }

        // 6. Check each populated session-entity collection field for:
        //    (a) delayed removal guarded behind Player#isOnline(), or
        //    (b) zero removal methods reachable from any disconnect/quit handler
        for (var entry : sessionCollectionFields.entrySet()) {
            String fullField = entry.getKey();
            Set<MethodNode> adders = addersByField.getOrDefault(fullField, Set.of());
            if (adders.isEmpty()) {
                continue;
            }
            Set<MethodNode> removers = removersByField.getOrDefault(fullField, Set.of());

            // Ignore removals that only occur inside onDisable / unloadAll / shutdown
            Set<MethodNode> runtimeRemovers = new LinkedHashSet<>();
            for (MethodNode rem : removers) {
                if (!isShutdownLifecycleHandler(rem) && !rem.name().equalsIgnoreCase("unloadAll")) {
                    runtimeRemovers.add(rem);
                }
            }

            boolean cleanedOnQuit = false;
            for (MethodNode rem : runtimeRemovers) {
                if (quitReachableMethods.contains(rem.symbol())) {
                    cleanedOnQuit = true;
                    break;
                }
            }

            MethodNode onlineGuardedRemover = null;
            for (MethodNode rem : runtimeRemovers) {
                if (rem.guards().contains("Player#isOnline") && rem.name().startsWith("lambda$")) {
                    onlineGuardedRemover = rem;
                    break;
                }
            }

              if (!cleanedOnQuit || onlineGuardedRemover != null) {
                  MethodNode primaryAdder = adders.iterator().next();
                  String shortField = BytecodeCausalGraphScanner.simpleMethodRef(fullField);
                  String reason;
                  List<String> chainSteps;
                  if (onlineGuardedRemover != null) {
                      reason = "Collection field '" + shortField + "' retains live Player/session references added in "
                              + BytecodeCausalGraphScanner.simpleMethodRef(primaryAdder.symbol())
                              + " because delayed cleanup in "
                              + BytecodeCausalGraphScanner.simpleMethodRef(onlineGuardedRemover.symbol())
                              + " is gated behind Player#isOnline() and no disconnect handler removes departed sessions (causes player session memory leaks after disconnect).";
                      chainSteps = List.of(
                              primaryAdder.symbol() + " --[inserts Player session into " + shortField + "]--> "
                                      + onlineGuardedRemover.symbol() + " [guarded by Player#isOnline -> LEAKED_ON_QUIT]"
                      );
                  } else {
                      String uncalledHelperNote = runtimeRemovers.isEmpty()
                              ? "0 runtime remove() callers exist in the JAR"
                              : ("removal method " + BytecodeCausalGraphScanner.simpleMethodRef(runtimeRemovers.iterator().next().symbol())
                              + " is never reached from any PlayerQuitEvent/disconnect handler");
                      reason = "Collection field '" + shortField + "' keys or stores live Player/session instances added by "
                              + BytecodeCausalGraphScanner.simpleMethodRef(primaryAdder.symbol())
                              + ", but " + uncalledHelperNote + " (retains departed player sessions across garbage collection).";
                      chainSteps = List.of(
                              primaryAdder.symbol() + " --[inserts Player into " + shortField + "]--> [NO_QUIT_CLEANUP_PATH]"
                      );
                  }
                  findings.add(new CausalFinding(
                          "CAUSAL-" + (seq++),
                          artifactName,
                          2,
                          FindingKind.CAUSAL_UNCLOSED_RESOURCE_LEAK,
                          ConfidenceTier.CONFIRMED,
                          95,
                          fullField,
                          "Session collection '" + shortField + "' stores live Player/session objects populated by "
                                  + BytecodeCausalGraphScanner.simpleMethodRef(primaryAdder.symbol()),
                          reason,
                          "Key session maps by UUID (or WeakReference<Player>) and unconditionally remove entries in a registered PlayerQuitEvent / disconnect handler.",
                          chainSteps,
                          ""
                  ));
              }
          }

          return findings;
      }
      private static String resolveCollectionFieldTarget(
              String rawTarget,
              String callerOwnerClass,
              Map<String, String> getterToField,
            Map<String, MethodNode> methodsBySymbol,
            Map<String, List<MethodNode>> methodsByName
    ) {
        if (rawTarget == null || rawTarget.isEmpty()) {
            return null;
        }
        if (!rawTarget.startsWith("GETTER:")) {
            return rawTarget;
        }
        String getterCall = rawTarget.substring("GETTER:".length());
        String direct = getterToField.get(getterCall);
        if (direct != null) {
            return direct;
        }
        MethodNode getterNode = BytecodeCausalGraphScanner.findMethodNodeFlexible(
                methodsBySymbol, methodsByName, callerOwnerClass, getterCall);
        if (getterNode != null && getterNode.returnedField() != null && !getterNode.returnedField().isEmpty()) {
            return getterNode.returnedField();
        }
        return null;
    }

    private static boolean isCollectionOrMapDescriptor(String desc) {
        if (desc == null || desc.isEmpty()) {
            return false;
        }
        return desc.contains("Map;")
                || desc.contains("Set;")
                || desc.contains("List;")
                || desc.contains("Collection;")
                || desc.contains("Queue;")
                || desc.contains("Deque;");
    }

    private static boolean isWeakOrBoundedContainer(String desc, String sig) {
        String combined = ((desc == null ? "" : desc) + " " + (sig == null ? "" : sig)).toLowerCase(Locale.ROOT);
        return combined.contains("weakhashmap")
                || combined.contains("weakreference")
                || combined.contains("softreference")
                || combined.contains("cache")
                || combined.contains("evict")
                || combined.contains("expir");
    }

    private static boolean containsSessionEntityType(String typeOrSig) {
        if (typeOrSig == null || typeOrSig.isEmpty()) {
            return false;
        }
        for (String sessDesc : SESSION_ENTITY_DESCRIPTORS) {
            if (typeOrSig.contains(sessDesc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDisconnectOrQuitHandler(MethodNode m) {
        String nameLow = m.name().toLowerCase(Locale.ROOT);
        String descLow = m.descriptor().toLowerCase(Locale.ROOT);
        String evLow = m.eventTypeSimpleName() == null ? "" : m.eventTypeSimpleName().toLowerCase(Locale.ROOT);
        return evLow.contains("quit")
                || evLow.contains("kick")
                || evLow.contains("disconnect")
                || evLow.contains("loggedout")
                || evLow.contains("playerleave")
                || descLow.contains("quitev")
                || descLow.contains("kickev")
                || descLow.contains("disconnect")
                || descLow.contains("loggedoutev")
                || (m.isEventHandler() && (nameLow.contains("quit") || nameLow.contains("leave") || nameLow.contains("disconnect") || nameLow.contains("kick")));
    }

    /**
     * Enriches each {@link CausalChainSlice} with its synthesized Hoare {@link EffectFrame},
     * semantic macros, and {@link ProofObligation} badges ({@code S-002}, {@code S-004}, {@code S-005}).
     */
    static List<CausalChainSlice> enrichSlicesWithHoareFramesAndProofs(
            List<CausalChainSlice> rawSlices,
            Map<String, MethodNode> methodsBySymbol,
            Map<String, List<MethodNode>> methodsByName,
            List<CausalFinding> allFindings
    ) {
        List<CausalChainSlice> enriched = new ArrayList<>(rawSlices.size());
        for (CausalChainSlice slice : rawSlices) {
            MethodNode root = methodsBySymbol.get(slice.entrySymbol());
            Set<String> locks = new LinkedHashSet<>();
            Set<String> macros = new LinkedHashSet<>();
            Set<String> reachableSymbols = new LinkedHashSet<>();
            reachableSymbols.add(slice.entrySymbol());

            if (root != null) {
                Deque<MethodNode> q = new ArrayDeque<>();
                q.add(root);
                int depth = 0;
                while (!q.isEmpty() && depth++ < 32) {
                    MethodNode cur = q.poll();
                    locks.addAll(cur.acquiredLocks());
                    macros.addAll(cur.semanticMacros());
                    for (String called : cur.calledMethods()) {
                        MethodNode next = BytecodeCausalGraphScanner.findMethodNodeFlexible(
                                methodsBySymbol, methodsByName, cur.ownerClass(), called);
                        if (next != null && reachableSymbols.add(next.symbol())) {
                            q.add(next);
                        }
                    }
                    for (String lam : cur.spawnedLambdas()) {
                        MethodNode next = BytecodeCausalGraphScanner.findMethodNodeFlexible(
                                methodsBySymbol, methodsByName, cur.ownerClass(), lam);
                        if (next != null && reachableSymbols.add(next.symbol())) {
                            q.add(next);
                        }
                    }
                }
            }

            List<String> requires = new ArrayList<>(slice.guards());
            if (macros.contains("@folia_region_guard") && !requires.contains("@folia_region_guard")) {
                requires.add("@folia_region_guard");
            }

            EffectFrame frame = new EffectFrame(
                    slice.mutatedFields(),
                    slice.readFields(),
                    new ArrayList<>(locks),
                    requires
            );

            List<ProofObligation> proofs = buildProofObligationsForSlice(slice, reachableSymbols, macros, allFindings);

            enriched.add(new CausalChainSlice(
                    slice.kind(),
                    slice.entrySymbol(),
                    slice.declaredBehavior(),
                    slice.registrationMode(),
                    slice.gatingConfigKey(),
                    slice.guards(),
                    slice.readConfigKeys(),
                    slice.readFields(),
                    slice.mutatedFields(),
                    slice.spawnedSuppliersAndLambdas(),
                    slice.hopTrace(),
                    slice.reachableEffectSinks(),
                    frame,
                    new ArrayList<>(macros),
                    proofs
            ));
        }
        return enriched;
    }

    private static List<ProofObligation> buildProofObligationsForSlice(
            CausalChainSlice slice,
            Set<String> reachableSymbols,
            Set<String> macros,
            List<CausalFinding> allFindings
    ) {
        String s002Violation = null;
        String s004Violation = null;
        String s005Violation = null;
        String entryOwner = extractOwnerClass(slice.entrySymbol());

        for (CausalFinding f : allFindings) {
            boolean matchesSlice = f.entryPointSymbol().equals(slice.entrySymbol())
                    || reachableSymbols.contains(f.entryPointSymbol())
                    || (f.kind() == FindingKind.CAUSAL_SHARED_STATE_RACE
                    && extractOwnerClass(f.entryPointSymbol()).equals(entryOwner));
            if (!matchesSlice) {
                continue;
            }
            switch (f.kind()) {
                case CAUSAL_UNCLOSED_RESOURCE_LEAK -> {
                    if (s002Violation == null) {
                        s002Violation = f.findingId() + ":" + f.kind().name();
                    }
                }
                case CAUSAL_SWALLOWED_FAILURE, CAUSAL_ORPHANED_SUPPLIER, CAUSAL_HOLLOW_COMMAND -> {
                    if (s004Violation == null) {
                        s004Violation = f.findingId() + ":" + f.kind().name();
                    }
                }
                case CAUSAL_SYNC_MAIN_THREAD_IO, CAUSAL_ASYNC_MAIN_THREAD_SINK, CAUSAL_SHARED_STATE_RACE -> {
                    if (s005Violation == null) {
                        s005Violation = f.findingId() + ":" + f.kind().name();
                    }
                }
                default -> {
                }
            }
        }

        List<ProofObligation> out = new ArrayList<>(3);
        if (s002Violation != null) {
            out.add(new ProofObligation("S-002", ProofStatus.VIOLATED, s002Violation));
        } else if (macros.contains("@tracked_ticket")) {
            out.add(new ProofObligation("S-002", ProofStatus.DISCHARGED, "@tracked_ticket"));
        } else {
            out.add(new ProofObligation("S-002", ProofStatus.DISCHARGED, "no_unclosed_resources"));
        }

        if (s004Violation != null) {
            out.add(new ProofObligation("S-004", ProofStatus.VIOLATED, s004Violation));
        } else {
            out.add(new ProofObligation("S-004", ProofStatus.DISCHARGED, "no_silent_swallow"));
        }

        if (s005Violation != null) {
            out.add(new ProofObligation("S-005", ProofStatus.VIOLATED, s005Violation));
        } else if (macros.contains("@folia_region_guard") || macros.contains("@async_chunk_io")) {
            out.add(new ProofObligation("S-005", ProofStatus.DISCHARGED, "async_or_region_guarded"));
        } else if (macros.contains("@scheduler_dispatch")) {
            out.add(new ProofObligation("S-005", ProofStatus.AUDIT_GUARDED, "@scheduler_dispatch"));
        } else {
            out.add(new ProofObligation("S-005", ProofStatus.DISCHARGED, "no_blocking_main_io"));
        }

        return Collections.unmodifiableList(out);
    }
    private static boolean isStackConfinedHelperClass(ParsedClass pc) {
        MethodNode init = pc.methods().get("<init>");
        if (init == null || (init.accessFlags() & 0x0002) == 0) {
            return false;
        }
        String selfDesc = "L" + pc.binaryClassName().replace('.', '/') + ";";
        for (FieldDescriptorMeta meta : pc.declaredFieldMeta().values()) {
            if (meta.isStatic() && (!meta.isFinal() || selfDesc.equals(meta.descriptor()))) {
                return false;
            }
        }
        for (MethodNode m : pc.methods().values()) {
            if (!m.spawnedLambdas().isEmpty() || m.descriptor().endsWith(")" + selfDesc)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isShutdownLifecycleHandler(MethodNode root) {
        String name = root.name();
        if ("onDisable".equals(name)
                || "onPluginDisable".equals(name)
                || "onServerStopping".equals(name)
                || "onServerStarting".equals(name)
                || "onServerStarted".equals(name)
                || "onRegisterCommands".equals(name)
                || "onProxyInitialize".equals(name)
                || "onProxyShutdown".equals(name)
                || "onWorldUnload".equals(name)) {
            return true;
        }
        String ev = root.eventTypeSimpleName();
        return ev != null && (ev.contains("Disable") || ev.contains("Stopping") || ev.contains("Shutdown")
                || ev.contains("ServerStarted") || ev.contains("ServerStarting")
                || ev.contains("RegisterCommands")
                || ev.contains("ProxyInitialize") || ev.contains("WorldUnload"));
    }

    private static boolean isAsyncEventName(String eventSimpleName) {
        if (eventSimpleName == null || eventSimpleName.isEmpty()) {
            return false;
        }
        String low = eventSimpleName.toLowerCase(Locale.ROOT);
        return low.startsWith("async")
                || low.contains("asyncplayerchat")
                || low.contains("asyncprelogin")
                || low.contains("serverpreconnect");
    }

    private static boolean isAsyncSchedulerDispatchCall(String callRef) {
        if (callRef == null) {
            return false;
        }
        String low = callRef.toLowerCase(Locale.ROOT);
        return low.contains("runtaskasynchronously")
                || low.contains("supplyasync")
                || low.contains("runasync")
                || low.contains("runbatchtaskasync")
                || low.contains("asyncscheduler");
    }

    private static String extractOwnerClass(String symbol) {
        if (symbol == null) {
            return "";
        }
        int hash = symbol.indexOf('#');
        return hash >= 0 ? symbol.substring(0, hash) : symbol;
    }

    private static String readableFieldType(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return "Object";
        }
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            String internal = descriptor.substring(1, descriptor.length() - 1);
            return BytecodeCausalGraphScanner.simpleClassName(internal);
        }
        return switch (descriptor) {
            case "I" -> "int";
            case "J" -> "long";
            case "Z" -> "boolean";
            case "D" -> "double";
            case "F" -> "float";
            default -> descriptor;
        };
    }
    private static boolean hasInPlaceCollectionMutation(List<MethodNode> accessors) {
        for (MethodNode acc : accessors) {
            if (!acc.producedQueueFields().isEmpty()) {
                return true;
            }
            for (String call : acc.calledMethods()) {
                for (String suffix : COLLECTION_MUTATOR_SUFFIXES) {
                    if (call.endsWith(suffix)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String formatMethodList(List<MethodNode> methods) {
        List<String> names = new ArrayList<>(methods.size());
        for (MethodNode m : methods) {
            names.add(BytecodeCausalGraphScanner.simpleMethodRef(m.symbol()));
        }
        return names.toString();
    }
}
