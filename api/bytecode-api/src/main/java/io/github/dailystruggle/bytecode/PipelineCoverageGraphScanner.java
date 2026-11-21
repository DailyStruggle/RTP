package io.github.dailystruggle.bytecode;

import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.FieldDescriptorMeta;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.IndyFunctionalSite;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.MethodNode;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.ParsedClass;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pure-Java connected-pipeline causal, state-mutation, and async-scheduler bytecode slicer and
 * fail-closed provenance ledger ({@code ADR-115}, {@code ADR-125}, {@code ADR-126},
 * {@code leaffuzz-ADR-001}, {@code corpus-atlas-ADR-002}).
 *
 * <p>Traces request-to-response stage graphs directly from raw {@code .class} byte streams without
 * loading classes, combining direct calls ({@code 0xB6..0xB9}), closed-world interface/subtype
 * overrides ({@code implements}/{@code extends}), {@code BootstrapMethods} {@code invokedynamic}
 * lambdas/method-references ({@code 0xBA}), functor task classes, carrier field/collection/array
 * mutations, queue producer-to-consumer handoffs, and {@code CompletableFuture}/executor/mailbox
 * scheduling edges. Reconciles static slices with runtime canary observations so stages become
 * eligible for interception only when 100% of reachable and observed symbols, carrier fields, and
 * async hops are accounted for.
 *
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy ADR-126
 * @governedBy leafgen-ADR-007
 * @governedBy leaffuzz-ADR-001
 * @governedBy corpus-atlas-ADR-002
 * @trace REQ-CFG-ATLAS-001
 * @trace REQ-SLEUTH-FUZZ-004
 * @trace S-004
 */
public final class PipelineCoverageGraphScanner {

    private static final Logger LOGGER = Logger.getLogger(PipelineCoverageGraphScanner.class.getName());
    private static final int DEFAULT_CLOSED_WORLD_CLASS_BUDGET = 4096;
    static final String CLOSED_WORLD_BUDGET_EXHAUSTED_SYMBOL = "<closed-world-class-budget-exhausted>";
    static final String MISSING_CLOSED_WORLD_SEED_PREFIX = "<missing-closed-world-seed:";
    static final String EMPTY_ROOT_SET_SYMBOL_PREFIX = "<empty-root-set:";
    private static final String CLASS_UNAVAILABLE_EDGE_MARKER = ":class-unavailable:";

    private static final Map<String, Set<String>> JDK_FUNCTIONAL_SUBINTERFACES = Map.of(
            "java.util.function.Function", Set.of("java.util.function.UnaryOperator"),
            "java.util.function.BiFunction", Set.of("java.util.function.BinaryOperator"),
            "java.lang.Runnable", Set.of(
                    "java.util.concurrent.RunnableFuture",
                    "java.util.concurrent.RunnableScheduledFuture"
            )
    );

    private static final Set<String> ZERO_ARG_FUNCTOR_METHODS = Set.of(
            "run", "call", "get", "getAsInt", "getAsLong", "getAsDouble", "getAsBoolean"
    );

    private static final Set<String> POLYMORPHIC_FUNCTOR_METHODS = Set.of(
            "accept", "apply", "applyAsInt", "applyAsLong", "applyAsDouble",
            "test", "doWork", "compute", "execute"
    );

    private static final Set<String> COMPLETABLE_FUTURE_COMBINATORS = Set.of(
            "supplyAsync", "runAsync",
            "thenApply", "thenApplyAsync",
            "thenAccept", "thenAcceptAsync",
            "thenRun", "thenRunAsync",
            "thenCombine", "thenCombineAsync",
            "thenCompose", "thenComposeAsync",
            "applyToEither", "applyToEitherAsync",
            "acceptEither", "acceptEitherAsync",
            "runAfterBoth", "runAfterBothAsync",
            "runAfterEither", "runAfterEitherAsync",
            "handle", "handleAsync",
            "whenComplete", "whenCompleteAsync",
            "exceptionally", "exceptionallyAsync",
            "exceptionallyCompose", "exceptionallyComposeAsync"
    );

    private static final Set<String> EXECUTOR_DISPATCH_METHODS = Set.of(
            "execute", "submit", "schedule", "scheduleAtFixedRate", "scheduleWithFixedDelay",
            "invokeAll", "invokeAny", "fork"
    );

    private static final Set<String> MAILBOX_OR_SCHEDULER_METHODS = Set.of(
            "tell", "ask", "schedule", "scheduleChunkLoad", "scheduleChunkTask",
            "scheduleGenerationTask", "execute", "submit", "enqueue", "post",
            "dispatch", "runTask", "runTaskAsynchronously", "runAsync",
            "runDelayed", "runAtFixedRate"
    );

    private static final Map<byte[], ParsedClass> PARSED_CLASS_BY_BYTES_CACHE =
            Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static ParsedClass parseClassBytesCached(byte[] classBytes) {
        if (classBytes == null || classBytes.length < 24) {
            return null;
        }
        ParsedClass hit = PARSED_CLASS_BY_BYTES_CACHE.get(classBytes);
        if (hit != null) {
            return hit;
        }
        ParsedClass parsed = BytecodeCausalGraphScanner.parseClassBytes(classBytes);
        if (parsed != null) {
            PARSED_CLASS_BY_BYTES_CACHE.put(classBytes, parsed);
        }
        return parsed;
    }

    private PipelineCoverageGraphScanner() {
    }

    /**
     * Connected edge classification across direct calls, polymorphic subtype dispatch,
     * {@code invokedynamic} lambdas, functor tasks, state/queue dataflow, and async schedulers.
     */
    public enum EdgeKind {
        DIRECT_CALL,
        INTERFACE_SUBTYPE_CALL,
        INDY_LAMBDA,
        FUNCTOR_TASK,
        FIELD_DATAFLOW,
        QUEUE_DATAFLOW,
        ASYNC_FUTURE_OR_SCHEDULER
    }

    /**
     * Provenance accounting classification for static and runtime-observed symbols, fields, and
     * async handoffs.
     */
    public enum CoverageProvenanceKind {
        IR_LIFTED,
        APPROXIMATE_EQUIVALENT,
        STAMP_ATLAS,
        NATIVE_DELEGATED,
        BENIGN_INFRASTRUCTURE,
        UNACCOUNTED;

        public boolean isAccounted() {
            return isAccounted(ProvenanceEvaluationPolicy.STRICT_EXACT);
        }

        public boolean isAccounted(ProvenanceEvaluationPolicy policy) {
            ProvenanceEvaluationPolicy effective =
                    policy == null ? ProvenanceEvaluationPolicy.STRICT_EXACT : policy;
            return effective.isAccounted(this);
        }
    }

    /**
     * Mode-aware provenance accounting policy distinguishing strict SSA-proven parity from
     * statistical {@code VANILLA_LIKE} equivalence.
     */
    public enum ProvenanceEvaluationPolicy {
        /**
         * Counts only {@link CoverageProvenanceKind#IR_LIFTED},
         * {@link CoverageProvenanceKind#NATIVE_DELEGATED}, and
         * {@link CoverageProvenanceKind#BENIGN_INFRASTRUCTURE} as accounted; treats
         * {@link CoverageProvenanceKind#APPROXIMATE_EQUIVALENT} and
         * {@link CoverageProvenanceKind#STAMP_ATLAS} as unaccounted for stage patching.
         */
        STRICT_EXACT,

        /**
         * Counts {@link CoverageProvenanceKind#IR_LIFTED},
         * {@link CoverageProvenanceKind#APPROXIMATE_EQUIVALENT},
         * {@link CoverageProvenanceKind#STAMP_ATLAS},
         * {@link CoverageProvenanceKind#NATIVE_DELEGATED}, and
         * {@link CoverageProvenanceKind#BENIGN_INFRASTRUCTURE} as accounted.
         */
        STATISTICAL_VANILLA_LIKE;

        public boolean isAccounted(CoverageProvenanceKind kind) {
            if (kind == null || kind == CoverageProvenanceKind.UNACCOUNTED) {
                return false;
            }
            return switch (this) {
                case STRICT_EXACT -> kind == CoverageProvenanceKind.IR_LIFTED
                        || kind == CoverageProvenanceKind.NATIVE_DELEGATED
                        || kind == CoverageProvenanceKind.BENIGN_INFRASTRUCTURE;
                case STATISTICAL_VANILLA_LIKE -> true;
            };
        }
    }

    /**
     * Directed causal, dataflow, or scheduler edge within or across pipeline stages.
     */
    public record PipelineEdge(
            String fromSymbol,
            String toSymbol,
            EdgeKind kind,
            String detail
    ) {
        public PipelineEdge {
            Objects.requireNonNull(fromSymbol, "fromSymbol");
            Objects.requireNonNull(toSymbol, "toSymbol");
            Objects.requireNonNull(kind, "kind");
            detail = detail == null ? "" : detail;
        }
    }

    /**
     * Root, carrier, sink, and optional receiver-type specification for a single pipeline stage.
     */
    public record StageSliceSpec(
            String stageId,
            List<String> rootMethodSymbols,
            Set<String> stateCarrierClasses,
            Set<String> sinkMethodSymbols,
            Set<String> scopedConcreteClasses
    ) {
        public StageSliceSpec {
            Objects.requireNonNull(stageId, "stageId");
            if (stageId.isBlank()) {
                throw new IllegalArgumentException("stageId must not be blank");
            }
            rootMethodSymbols = rootMethodSymbols == null
                    ? List.of()
                    : List.copyOf(normalizeSymbolList(rootMethodSymbols));
            stateCarrierClasses = stateCarrierClasses == null
                    ? Set.of()
                    : Collections.unmodifiableSet(normalizeClassSet(stateCarrierClasses));
            sinkMethodSymbols = sinkMethodSymbols == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(normalizeSymbolList(sinkMethodSymbols)));
            scopedConcreteClasses = scopedConcreteClasses == null
                    ? Set.of()
                    : Collections.unmodifiableSet(normalizeClassSet(scopedConcreteClasses));
        }

        public StageSliceSpec(
                String stageId,
                List<String> rootMethodSymbols,
                Set<String> stateCarrierClasses
        ) {
            this(stageId, rootMethodSymbols, stateCarrierClasses, Set.of(), Set.of());
        }
    }

    /**
     * Static connected reachability, carrier field dataflow, and async handoff graph for a stage.
     */
    public record StageCoverageGraph(
            String stageId,
            List<String> rootMethodSymbols,
            Set<String> reachableMethods,
            Set<String> abstractDispatchMethods,
            Map<String, Set<String>> resolvedSubtypeTargets,
            Set<String> expandedStateCarrierClasses,
            Set<String> mutatedCarrierFields,
            Set<String> readCarrierFields,
            Set<String> scheduledAsyncTargets,
            List<PipelineEdge> edges,
            List<PipelineEdge> crossStageDataflowEdges,
            boolean truncatedByBounds,
            Set<String> unexpandedFrontierSymbols,
            int maxDepthReached
    ) {
        public StageCoverageGraph {
            Objects.requireNonNull(stageId, "stageId");
            rootMethodSymbols = rootMethodSymbols == null ? List.of() : List.copyOf(rootMethodSymbols);
            reachableMethods = reachableMethods == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(reachableMethods));
            abstractDispatchMethods = abstractDispatchMethods == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(abstractDispatchMethods));
            resolvedSubtypeTargets = copyNestedSetMap(resolvedSubtypeTargets);
            expandedStateCarrierClasses = expandedStateCarrierClasses == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(expandedStateCarrierClasses));
            mutatedCarrierFields = mutatedCarrierFields == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(mutatedCarrierFields));
            readCarrierFields = readCarrierFields == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(readCarrierFields));
            scheduledAsyncTargets = scheduledAsyncTargets == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(scheduledAsyncTargets));
            edges = edges == null ? List.of() : List.copyOf(edges);
            crossStageDataflowEdges = crossStageDataflowEdges == null ? List.of() : List.copyOf(crossStageDataflowEdges);
            unexpandedFrontierSymbols = unexpandedFrontierSymbols == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(unexpandedFrontierSymbols));
        }

        boolean hasIncompleteClosedWorld() {
            if (unexpandedFrontierSymbols.contains(CLOSED_WORLD_BUDGET_EXHAUSTED_SYMBOL)) {
                return true;
            }
            for (String sym : unexpandedFrontierSymbols) {
                if (sym.startsWith(MISSING_CLOSED_WORLD_SEED_PREFIX)) {
                    return true;
                }
            }
            for (PipelineEdge edge : edges) {
                if (edge.detail().contains(CLASS_UNAVAILABLE_EDGE_MARKER)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Live canary execution frames, carrier field mutations/reads, and async worker observations.
     */
    public record StageRuntimeObservation(
            String stageId,
            Set<String> executedMethodSymbols,
            Set<String> mutatedCarrierFields,
            Set<String> readCarrierFields,
            Set<String> observedAsyncThreadsOrExecutors
    ) {
        public StageRuntimeObservation {
            Objects.requireNonNull(stageId, "stageId");
            executedMethodSymbols = executedMethodSymbols == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(executedMethodSymbols));
            mutatedCarrierFields = mutatedCarrierFields == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(mutatedCarrierFields));
            readCarrierFields = readCarrierFields == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(readCarrierFields));
            observedAsyncThreadsOrExecutors = observedAsyncThreadsOrExecutors == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(observedAsyncThreadsOrExecutors));
        }

        public static StageRuntimeObservation empty(String stageId) {
            return new StageRuntimeObservation(stageId, Set.of(), Set.of(), Set.of(), Set.of());
        }
    }

    /**
     * Provenance binding linking a method symbol, carrier field, or async hop to its implementation
     * mechanism.
     */
    public record ProvenanceBinding(
            String symbolOrField,
            CoverageProvenanceKind kind,
            String mechanismRef
    ) {
        public ProvenanceBinding {
            Objects.requireNonNull(symbolOrField, "symbolOrField");
            Objects.requireNonNull(kind, "kind");
            mechanismRef = mechanismRef == null ? "" : mechanismRef;
        }
    }

    /**
     * Fail-closed coverage accounting verdict for a single pipeline stage.
     */
    public record StageCoverageVerdict(
            String stageId,
            boolean patchEligible,
            int accountedMethodCount,
            int unaccountedMethodCount,
            int accountedFieldCount,
            int unaccountedFieldCount,
            List<String> unaccountedMethods,
            List<String> unaccountedFields,
            List<String> unaccountedAsyncHops,
            List<PipelineEdge> witnessPathsToUnaccounted,
            boolean nativeDelegated
    ) {
        public StageCoverageVerdict(
                String stageId,
                boolean patchEligible,
                int accountedMethodCount,
                int unaccountedMethodCount,
                int accountedFieldCount,
                int unaccountedFieldCount,
                List<String> unaccountedMethods,
                List<String> unaccountedFields,
                List<String> unaccountedAsyncHops,
                List<PipelineEdge> witnessPathsToUnaccounted
        ) {
            this(
                    stageId,
                    patchEligible,
                    accountedMethodCount,
                    unaccountedMethodCount,
                    accountedFieldCount,
                    unaccountedFieldCount,
                    unaccountedMethods,
                    unaccountedFields,
                    unaccountedAsyncHops,
                    witnessPathsToUnaccounted,
                    false
            );
        }

        public StageCoverageVerdict {
            Objects.requireNonNull(stageId, "stageId");
            if (nativeDelegated) {
                patchEligible = false;
            }
            unaccountedMethods = unaccountedMethods == null ? List.of() : List.copyOf(unaccountedMethods);
            unaccountedFields = unaccountedFields == null ? List.of() : List.copyOf(unaccountedFields);
            unaccountedAsyncHops = unaccountedAsyncHops == null ? List.of() : List.copyOf(unaccountedAsyncHops);
            witnessPathsToUnaccounted = witnessPathsToUnaccounted == null
                    ? List.of()
                    : List.copyOf(witnessPathsToUnaccounted);
        }
    }

    /**
     * Reconciled static and runtime provenance slice for a single pipeline stage.
     */
    public record StageCoverageSlice(
            String stageId,
            StageCoverageGraph staticGraph,
            StageRuntimeObservation runtimeObservation,
            Map<String, ProvenanceBinding> methodProvenance,
            Map<String, ProvenanceBinding> fieldProvenance,
            Map<String, ProvenanceBinding> asyncHopProvenance,
            StageCoverageVerdict verdict
    ) {
        public StageCoverageSlice {
            Objects.requireNonNull(stageId, "stageId");
            Objects.requireNonNull(verdict, "verdict");
            methodProvenance = methodProvenance == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(methodProvenance));
            fieldProvenance = fieldProvenance == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(fieldProvenance));
            asyncHopProvenance = asyncHopProvenance == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(asyncHopProvenance));
        }
    }

    /**
     * Immutable provenance ledger reconciling static connected stage graphs, runtime canary
     * observations, explicit provenance bindings, and outbound response carrier field requirements.
     */
    public static final class CoverageProvenanceLedger {
        private final Map<String, StageCoverageSlice> stageSlices;
        private final Map<String, StageCoverageVerdict> stageVerdicts;
        private final Set<String> unaccountedResponseRequiredFields;
        private final ProvenanceEvaluationPolicy evaluationPolicy;

        private CoverageProvenanceLedger(
                Map<String, StageCoverageSlice> stageSlices,
                Map<String, StageCoverageVerdict> stageVerdicts,
                Set<String> unaccountedResponseRequiredFields,
                ProvenanceEvaluationPolicy evaluationPolicy
        ) {
            this.stageSlices = Collections.unmodifiableMap(new LinkedHashMap<>(stageSlices));
            this.stageVerdicts = Collections.unmodifiableMap(new LinkedHashMap<>(stageVerdicts));
            this.unaccountedResponseRequiredFields = Collections.unmodifiableSet(
                    new LinkedHashSet<>(unaccountedResponseRequiredFields)
            );
            this.evaluationPolicy = evaluationPolicy == null
                    ? ProvenanceEvaluationPolicy.STRICT_EXACT
                    : evaluationPolicy;
        }

        public static CoverageProvenanceLedger reconcile(
                Map<String, StageCoverageGraph> staticGraphs,
                Map<String, StageRuntimeObservation> runtimeObservations,
                Map<String, ProvenanceBinding> provenanceBindings,
                Set<String> responseRequiredCarrierFields
        ) {
            return reconcile(
                    staticGraphs,
                    runtimeObservations,
                    provenanceBindings,
                    responseRequiredCarrierFields,
                    ProvenanceEvaluationPolicy.STRICT_EXACT
            );
        }

        public static CoverageProvenanceLedger reconcile(
                Map<String, StageCoverageGraph> staticGraphs,
                Map<String, StageRuntimeObservation> runtimeObservations,
                Map<String, ProvenanceBinding> provenanceBindings,
                Set<String> responseRequiredCarrierFields,
                ProvenanceEvaluationPolicy policy
        ) {
            ProvenanceEvaluationPolicy effectivePolicy =
                    policy == null ? ProvenanceEvaluationPolicy.STRICT_EXACT : policy;
            Map<String, StageCoverageGraph> graphs = staticGraphs == null ? Map.of() : staticGraphs;
            Map<String, StageRuntimeObservation> runtimeMap = runtimeObservations == null
                    ? Map.of()
                    : runtimeObservations;
            Map<String, Set<String>> validationErrorsByStage = collectObservationAndGraphErrors(graphs, runtimeMap);
            Map<String, ProvenanceBinding> normalizedBindings = normalizeBindingMap(provenanceBindings);
            Set<String> normalizedResponseFields = normalizeSymbolSet(responseRequiredCarrierFields);

            Set<String> orderedStageIds = new LinkedHashSet<>();
            for (String k : graphs.keySet()) {
                if (k != null && !k.isBlank()) {
                    orderedStageIds.add(k);
                }
            }
            for (String k : runtimeMap.keySet()) {
                if (k != null && !k.isBlank()) {
                    orderedStageIds.add(k);
                }
            }
            orderedStageIds.addAll(validationErrorsByStage.keySet());

            // Pre-scan which response-required carrier fields are mutated by any stage or satisfied by provenance
            Set<String> stagesMutatingAnyField = new LinkedHashSet<>();
            Set<String> allObservedMutatedFields = new LinkedHashSet<>();
            for (String stageId : orderedStageIds) {
                StageCoverageGraph g = graphs.get(stageId);
                StageRuntimeObservation r = runtimeMap.get(stageId);
                if (g != null) {
                    for (String mf : g.mutatedCarrierFields()) {
                        String norm = normalizeRuntimeSymbol(mf);
                        allObservedMutatedFields.add(norm);
                        stagesMutatingAnyField.add(stageId + "|" + norm);
                    }
                }
                if (r != null) {
                    for (String mf : r.mutatedCarrierFields()) {
                        String norm = normalizeRuntimeSymbol(mf);
                        allObservedMutatedFields.add(norm);
                        stagesMutatingAnyField.add(stageId + "|" + norm);
                    }
                }
            }

            Set<String> globallyUnproducedResponseFields = new LinkedHashSet<>();
            for (String reqField : normalizedResponseFields) {
                ProvenanceBinding reqBinding = lookupBinding(null, reqField, normalizedBindings);
                boolean accountedByBinding = reqBinding != null
                        && effectivePolicy.isAccounted(reqBinding.kind())
                        && (reqBinding.kind() != CoverageProvenanceKind.BENIGN_INFRASTRUCTURE
                                || "PrePyramidCarrierStatePreserved:<clinit>".equals(reqBinding.mechanismRef())
                                || "StaticCarrierConstantField".equals(reqBinding.mechanismRef()));
                if (!accountedByBinding && !allObservedMutatedFields.contains(reqField)) {
                    globallyUnproducedResponseFields.add(reqField);
                }
            }

            Map<String, StageCoverageSlice> slices = new LinkedHashMap<>();
            Map<String, StageCoverageVerdict> verdicts = new LinkedHashMap<>();
            Set<String> unaccountedResponseFields = new LinkedHashSet<>(globallyUnproducedResponseFields);

            for (String stageId : orderedStageIds) {
                StageCoverageGraph rawGraph = graphs.get(stageId);
                StageCoverageGraph graph = (rawGraph != null && stageId.equals(rawGraph.stageId()))
                        ? rawGraph
                        : emptyStageGraph(stageId);
                StageRuntimeObservation rawRuntime = runtimeMap.get(stageId);
                StageRuntimeObservation runtime = (rawRuntime != null && stageId.equals(rawRuntime.stageId()))
                        ? rawRuntime
                        : StageRuntimeObservation.empty(stageId);

                StageCoverageSlice slice = reconcileSingleStage(
                        stageId,
                        graph,
                        runtime,
                        validationErrorsByStage.getOrDefault(stageId, Set.of()),
                        normalizedBindings,
                        normalizedResponseFields,
                        globallyUnproducedResponseFields,
                        unaccountedResponseFields,
                        effectivePolicy
                );
                slices.put(stageId, slice);
                verdicts.put(stageId, slice.verdict());
            }

            return new CoverageProvenanceLedger(slices, verdicts, unaccountedResponseFields, effectivePolicy);
        }

        public ProvenanceEvaluationPolicy evaluationPolicy() {
            return evaluationPolicy;
        }

        public Map<String, StageCoverageSlice> stageSlices() {
            return stageSlices;
        }

        public Map<String, StageCoverageVerdict> stageVerdicts() {
            return stageVerdicts;
        }

        public StageCoverageSlice sliceForStage(String stageId) {
            return stageSlices.get(stageId);
        }

        public StageCoverageVerdict verdictForStage(String stageId) {
            return stageVerdicts.get(stageId);
        }

        public boolean isStagePatchEligible(String stageId) {
            StageCoverageVerdict verdict = stageVerdicts.get(stageId);
            return verdict != null && verdict.patchEligible();
        }

        public boolean isStageNativeDelegated(String stageId) {
            StageCoverageVerdict verdict = stageVerdicts.get(stageId);
            return verdict != null && verdict.nativeDelegated();
        }

        public Set<String> unaccountedResponseRequiredFields() {
            return unaccountedResponseRequiredFields;
        }
    }

    /**
     * Traces connected pipeline stages from request roots to response sinks across raw {@code .class}
     * bytes without loading classes.
     */
    public static Map<String, StageCoverageGraph> traceConnectedPipeline(
            Function<String, byte[]> classByteResolver,
            List<StageSliceSpec> stages,
            int maxHopsPerStage,
            int maxNodesPerStage
    ) {
        return traceConnectedPipeline(
                classByteResolver,
                List.of(),
                stages,
                maxHopsPerStage,
                maxNodesPerStage
        );
    }

    /**
     * Traces connected pipeline stages with additional closed-world concrete class seeds (for
     * registry/codec-instantiated receiver types or JAR-wide audits).
     */
    public static Map<String, StageCoverageGraph> traceConnectedPipeline(
            Function<String, byte[]> classByteResolver,
            Collection<String> closedWorldClassSeeds,
            List<StageSliceSpec> stages,
            int maxHopsPerStage,
            int maxNodesPerStage
    ) {
        int defaultClassBudget = Math.max(
                DEFAULT_CLOSED_WORLD_CLASS_BUDGET,
                Math.max(1, maxNodesPerStage) * 8
        );
        return traceConnectedPipeline(
                classByteResolver,
                closedWorldClassSeeds,
                stages,
                maxHopsPerStage,
                maxNodesPerStage,
                defaultClassBudget
        );
    }

    /**
     * Traces connected pipeline stages with an explicit closed-world class budget.
     */
    public static Map<String, StageCoverageGraph> traceConnectedPipeline(
            Function<String, byte[]> classByteResolver,
            Collection<String> closedWorldClassSeeds,
            List<StageSliceSpec> stages,
            int maxHopsPerStage,
            int maxNodesPerStage,
            int maxClosedWorldClasses
    ) {
        Objects.requireNonNull(classByteResolver, "classByteResolver");
        Objects.requireNonNull(stages, "stages");
        if (maxHopsPerStage < 0) {
            throw new IllegalArgumentException("maxHopsPerStage must be >= 0: " + maxHopsPerStage);
        }
        if (maxNodesPerStage <= 0) {
            throw new IllegalArgumentException("maxNodesPerStage must be > 0: " + maxNodesPerStage);
        }
        if (maxClosedWorldClasses <= 0) {
            throw new IllegalArgumentException("maxClosedWorldClasses must be > 0: " + maxClosedWorldClasses);
        }
        if (stages.isEmpty()) {
            return Map.of();
        }

        ClosedWorldClassIndex classIndex = new ClosedWorldClassIndex(
                classByteResolver,
                maxClosedWorldClasses
        );

        // Seed closed-world hierarchy from explicit seeds and all stage specifications
        if (closedWorldClassSeeds != null) {
            for (String seedCls : closedWorldClassSeeds) {
                String norm = unwrapElementClassIfArray(seedCls);
                if (!norm.isEmpty() && !isBootstrapJdkOrLoggingClass(norm)) {
                    String missingSeed = classIndex.requireHierarchyLoaded(norm);
                    if (missingSeed != null && !classIndex.isBudgetExhausted()) {
                        classIndex.recordMissingSeedType(missingSeed);
                    }
                }
            }
        }

        Set<String> allStageRootSymbols = new LinkedHashSet<>();
        Map<String, Set<String>> rootOwnersByStage = new LinkedHashMap<>();
        for (StageSliceSpec spec : stages) {
            Set<String> stageOwners = new LinkedHashSet<>();
            for (String rootSym : spec.rootMethodSymbols()) {
                allStageRootSymbols.add(stripMethodDescriptor(rootSym));
                String owner = extractOwnerClass(rootSym);
                if (!owner.isEmpty()) {
                    stageOwners.add(owner);
                    classIndex.ensureLoadedWithNeighborhood(owner);
                }
            }
            for (String sinkSym : spec.sinkMethodSymbols()) {
                String owner = extractOwnerClass(sinkSym);
                if (!owner.isEmpty()) {
                    classIndex.ensureLoaded(owner);
                }
            }
            for (String carrierCls : spec.stateCarrierClasses()) {
                classIndex.ensureLoaded(carrierCls);
            }
            for (String concreteCls : spec.scopedConcreteClasses()) {
                classIndex.ensureLoaded(concreteCls);
            }
            rootOwnersByStage.put(spec.stageId(), stageOwners);
        }

        // First pass: trace each stage's connected slice
        Map<String, StageTraceResult> rawResults = new LinkedHashMap<>();
        for (StageSliceSpec spec : stages) {
            Set<String> otherStageRoots = new LinkedHashSet<>(allStageRootSymbols);
            for (String ownRoot : spec.rootMethodSymbols()) {
                otherStageRoots.remove(stripMethodDescriptor(ownRoot));
            }
            StageTraceResult traced = traceSingleStage(
                    spec,
                    classIndex,
                    otherStageRoots,
                    maxHopsPerStage,
                    maxNodesPerStage
            );
            rawResults.put(spec.stageId(), traced);
        }

        // If the closed-world class budget was exhausted or explicit seeds had missing bytes, mark every stage graph truncated fail-closed
        if (classIndex.isBudgetExhausted()) {
            String witnessDetail = "closed-world-budget-exhausted:"
                    + String.join(",", classIndex.budgetDroppedWitnesses());
            for (StageSliceSpec spec : stages) {
                StageTraceResult r = rawResults.get(spec.stageId());
                if (r != null) {
                    r.truncatedByBounds = true;
                    r.unexpandedFrontierSymbols.add(CLOSED_WORLD_BUDGET_EXHAUSTED_SYMBOL);
                    String fromRoot = r.rootSymbols.isEmpty()
                            ? ("STAGE:" + spec.stageId())
                            : r.rootSymbols.get(0);
                    r.addEdge(
                            fromRoot,
                            CLOSED_WORLD_BUDGET_EXHAUSTED_SYMBOL,
                            EdgeKind.INTERFACE_SUBTYPE_CALL,
                            witnessDetail
                    );
                }
            }
        }
        if (!classIndex.missingSeedTypes().isEmpty()) {
            for (StageSliceSpec spec : stages) {
                StageTraceResult r = rawResults.get(spec.stageId());
                if (r != null) {
                    r.truncatedByBounds = true;
                    String fromRoot = r.rootSymbols.isEmpty()
                            ? ("STAGE:" + spec.stageId())
                            : r.rootSymbols.get(0);
                    for (String missingSeed : classIndex.missingSeedTypes()) {
                        String seedSym = MISSING_CLOSED_WORLD_SEED_PREFIX + missingSeed + ">";
                        r.unexpandedFrontierSymbols.add(seedSym);
                        r.addEdge(
                                fromRoot,
                                seedSym,
                                EdgeKind.INTERFACE_SUBTYPE_CALL,
                                "closed-world-seed" + CLASS_UNAVAILABLE_EDGE_MARKER + missingSeed
                        );
                    }
                }
            }
        }

        // Second pass: wire cross-stage carrier field producer -> consumer dataflow edges
        Map<String, List<PipelineEdge>> crossStageEdgesByStage = new LinkedHashMap<>();
        for (StageSliceSpec spec : stages) {
            crossStageEdgesByStage.put(spec.stageId(), new ArrayList<>());
        }
        List<StageSliceSpec> stageList = List.copyOf(stages);
        for (int i = 0; i < stageList.size(); i++) {
            StageSliceSpec producerSpec = stageList.get(i);
            StageTraceResult producer = rawResults.get(producerSpec.stageId());
            if (producer == null || producer.fieldWriters.isEmpty()) {
                continue;
            }
            for (int j = i + 1; j < stageList.size(); j++) {
                StageSliceSpec consumerSpec = stageList.get(j);
                StageTraceResult consumer = rawResults.get(consumerSpec.stageId());
                if (consumer == null || consumer.fieldReaders.isEmpty()) {
                    continue;
                }
                for (Map.Entry<String, Set<String>> writeEntry : producer.fieldWriters.entrySet()) {
                    String fieldSym = writeEntry.getKey();
                    Set<String> readers = consumer.fieldReaders.get(fieldSym);
                    if (readers == null || readers.isEmpty()) {
                        continue;
                    }
                    String detail = producerSpec.stageId() + "->" + consumerSpec.stageId() + ":" + fieldSym;
                    for (String writerMethod : writeEntry.getValue()) {
                        for (String readerMethod : readers) {
                            PipelineEdge crossEdge = new PipelineEdge(
                                    writerMethod,
                                    readerMethod,
                                    EdgeKind.FIELD_DATAFLOW,
                                    detail
                            );
                            addUniqueEdge(crossStageEdgesByStage.get(producerSpec.stageId()), crossEdge);
                            addUniqueEdge(crossStageEdgesByStage.get(consumerSpec.stageId()), crossEdge);
                        }
                    }
                }
            }
        }

        Map<String, StageCoverageGraph> finalGraphs = new LinkedHashMap<>();
        for (StageSliceSpec spec : stages) {
            StageTraceResult r = rawResults.get(spec.stageId());
            finalGraphs.put(
                    spec.stageId(),
                    new StageCoverageGraph(
                            spec.stageId(),
                            r.rootSymbols,
                            r.reachableMethods,
                            r.abstractDispatchMethods,
                            r.resolvedSubtypeTargets,
                            r.expandedCarrierClasses,
                            r.mutatedCarrierFields,
                            r.readCarrierFields,
                            r.scheduledAsyncTargets,
                            r.edges,
                            crossStageEdgesByStage.get(spec.stageId()),
                            r.truncatedByBounds,
                            r.unexpandedFrontierSymbols,
                            r.maxDepthReached
                    )
            );
        }
        return Collections.unmodifiableMap(finalGraphs);
    }

    /**
     * Reconciles static connected stage graphs and live canary observations against provenance
     * bindings and response-required carrier fields.
     */
    public static Map<String, StageCoverageVerdict> reconcileCoverageLedger(
            Map<String, StageCoverageGraph> staticGraphs,
            Map<String, StageRuntimeObservation> runtimeObservations,
            Map<String, ProvenanceBinding> provenanceBindings,
            Set<String> responseRequiredCarrierFields
    ) {
        return reconcileCoverageLedger(
                staticGraphs,
                runtimeObservations,
                provenanceBindings,
                responseRequiredCarrierFields,
                ProvenanceEvaluationPolicy.STRICT_EXACT
        );
    }

    /**
     * Reconciles static connected stage graphs and live canary observations against provenance
     * bindings and response-required carrier fields under the supplied {@link ProvenanceEvaluationPolicy}.
     */
    public static Map<String, StageCoverageVerdict> reconcileCoverageLedger(
            Map<String, StageCoverageGraph> staticGraphs,
            Map<String, StageRuntimeObservation> runtimeObservations,
            Map<String, ProvenanceBinding> provenanceBindings,
            Set<String> responseRequiredCarrierFields,
            ProvenanceEvaluationPolicy policy
    ) {
        return CoverageProvenanceLedger.reconcile(
                staticGraphs,
                runtimeObservations,
                provenanceBindings,
                responseRequiredCarrierFields,
                policy
        ).stageVerdicts();
    }

    /**
     * Normalizes JVM runtime stack frame or bytecode symbols by stripping module/loader prefixes,
     * hidden-class hex suffixes ({@code /0x...}), and converting {@code ::} or {@code /} separators
     * to {@code BinaryClassName#member}.
     */
    public static String normalizeRuntimeSymbol(String rawSymbol) {
        if (rawSymbol == null) {
            return "";
        }
        String trimmed = rawSymbol.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String stagePrefix = "";
        int colonIdx = trimmed.indexOf(':');
        int doubleColonIdx = trimmed.indexOf("::");
        if (colonIdx > 0 && colonIdx != doubleColonIdx) {
            String candidateStage = trimmed.substring(0, colonIdx);
            if (candidateStage.indexOf('.') < 0 && candidateStage.indexOf('/') < 0 && candidateStage.indexOf('#') < 0) {
                stagePrefix = candidateStage + ":";
                trimmed = trimmed.substring(colonIdx + 1).trim();
            }
        }

        if (doubleColonIdx >= 0) {
            trimmed = trimmed.replace("::", "#");
        }
        int hashIdx = trimmed.indexOf('#');
        String ownerPart = hashIdx >= 0 ? trimmed.substring(0, hashIdx) : trimmed;
        String memberPart = hashIdx >= 0 ? trimmed.substring(hashIdx + 1) : "";

        // Strip classloader/module prefix like "app//pkg.Foo" or "java.base@21.0.2/java.lang.Math"
        int doubleSlash = ownerPart.indexOf("//");
        if (doubleSlash >= 0) {
            ownerPart = ownerPart.substring(doubleSlash + 2);
        } else {
            int atIdx = ownerPart.indexOf('@');
            int slashAfterModule = ownerPart.indexOf('/');
            if (atIdx > 0 && slashAfterModule > atIdx) {
                ownerPart = ownerPart.substring(slashAfterModule + 1);
            } else if (ownerPart.startsWith("java.") && slashAfterModule > 0
                    && ownerPart.substring(slashAfterModule + 1).startsWith("java")) {
                ownerPart = ownerPart.substring(slashAfterModule + 1);
            }
        }

        // Strip JVM hidden-class / LambdaMetafactory hex suffix "/0x0000..."
        int hiddenSlash = ownerPart.indexOf("/0x");
        if (hiddenSlash > 0) {
            int endHex = hiddenSlash + 3;
            while (endHex < ownerPart.length() && isHexOrDotChar(ownerPart.charAt(endHex))) {
                endHex++;
            }
            ownerPart = ownerPart.substring(0, hiddenSlash) + ownerPart.substring(endHex);
        }

        ownerPart = ownerPart.replace('/', '.').trim();
        if (memberPart.isEmpty()) {
            return stagePrefix + ownerPart;
        }
        // Strip optional bytecode descriptor from member unless explicitly preserving overloads elsewhere
        int parenIdx = memberPart.indexOf('(');
        if (parenIdx > 0) {
            memberPart = memberPart.substring(0, parenIdx);
        }
        return stagePrefix + ownerPart + "#" + memberPart.trim();
    }

    // =========================================================================
    // Stage Slicing Implementation
    // =========================================================================

    private record WorkItem(
            String canonicalSymbol,
            MethodNode methodNode,
            int depth
    ) {
    }

    private static final class StageTraceResult {
        final List<String> rootSymbols = new ArrayList<>();
        final Set<String> reachableMethods = new LinkedHashSet<>();
        final Set<String> abstractDispatchMethods = new LinkedHashSet<>();
        final Map<String, Set<String>> resolvedSubtypeTargets = new LinkedHashMap<>();
        final Set<String> expandedCarrierClasses = new LinkedHashSet<>();
        final Set<String> mutatedCarrierFields = new LinkedHashSet<>();
        final Set<String> readCarrierFields = new LinkedHashSet<>();
        final Set<String> scheduledAsyncTargets = new LinkedHashSet<>();
        final List<PipelineEdge> edges = new ArrayList<>();
        final Set<String> edgeDedup = new LinkedHashSet<>();
        final Map<String, Set<String>> fieldWriters = new LinkedHashMap<>();
        final Map<String, Set<String>> fieldReaders = new LinkedHashMap<>();
        final Set<String> unexpandedFrontierSymbols = new LinkedHashSet<>();
        final Set<String> stageReferencedOwners = new LinkedHashSet<>();
        final Set<String> verifiedClinitOwners = new LinkedHashSet<>();
        boolean truncatedByBounds = false;
        int maxDepthReached = 0;

        void addEdge(String from, String to, EdgeKind kind, String detail) {
            String key = from + "->" + to + "|" + kind.name() + "|" + detail;
            if (edgeDedup.add(key)) {
                edges.add(new PipelineEdge(from, to, kind, detail));
            }
        }
    }

    private static StageTraceResult traceSingleStage(
            StageSliceSpec spec,
            ClosedWorldClassIndex classIndex,
            Set<String> otherStageRoots,
            int maxHopsPerStage,
            int maxNodesPerStage
    ) {
        StageTraceResult out = new StageTraceResult();

        if (spec.rootMethodSymbols().isEmpty()) {
            String emptySym = EMPTY_ROOT_SET_SYMBOL_PREFIX + spec.stageId() + ">";
            out.truncatedByBounds = true;
            out.unexpandedFrontierSymbols.add(emptySym);
            out.addEdge("STAGE:" + spec.stageId(), emptySym, EdgeKind.DIRECT_CALL, "no-root-methods");
            LOGGER.log(
                    Level.WARNING,
                    "[PipelineCoverageGraphScanner] Stage " + spec.stageId()
                            + " has no root method symbols; no code was inspected, so the stage graph is truncated (fail-closed)."
            );
        }

        // Expand state carrier hierarchy (subclasses + superclasses of declared carriers)
        String stageOriginSym = "STAGE:" + spec.stageId();
        for (String baseCarrier : spec.stateCarrierClasses()) {
            requireStageType(
                    stageOriginSym,
                    baseCarrier,
                    baseCarrier,
                    EdgeKind.FIELD_DATAFLOW,
                    "state-carrier",
                    "",
                    classIndex,
                    out
            );
            out.expandedCarrierClasses.addAll(classIndex.expandCarrierHierarchy(baseCarrier));
        }

        // Compute scoped concrete type filter if non-empty
        Set<String> allowedConcreteTypes = new LinkedHashSet<>();
        boolean hasConcreteScopeFilter = !spec.scopedConcreteClasses().isEmpty();
        if (hasConcreteScopeFilter) {
            for (String c : spec.scopedConcreteClasses()) {
                requireStageType(
                        stageOriginSym,
                        c,
                        c,
                        EdgeKind.INTERFACE_SUBTYPE_CALL,
                        "scoped-concrete-class",
                        "",
                        classIndex,
                        out
                );
                allowedConcreteTypes.add(c);
                allowedConcreteTypes.addAll(classIndex.allSupertypesOf(c));
            }
        }

        Set<String> normalizedSinks = new LinkedHashSet<>();
        for (String s : spec.sinkMethodSymbols()) {
            normalizedSinks.add(stripMethodDescriptor(s));
        }

        Deque<WorkItem> queue = new ArrayDeque<>();
        Set<String> expandedNodeKeys = new LinkedHashSet<>();
        Set<String> countedMethodSymbols = new LinkedHashSet<>();
        Set<DeferredPolymorphicSite> polymorphicSites = new LinkedHashSet<>();
        Map<DeferredPolymorphicSite, Integer> siteLastCheckedCount = new LinkedHashMap<>();

        seedRootWorkItems(
                spec.rootMethodSymbols(),
                classIndex,
                expandedNodeKeys,
                countedMethodSymbols,
                maxNodesPerStage,
                queue,
                out
        );

        int lastCheckedLoadedClassCount = -1;
        do {
            while (!queue.isEmpty()) {
                WorkItem current = queue.poll();
                String callerSym = current.canonicalSymbol();
                MethodNode node = current.methodNode();
                int depth = current.depth();
                if (depth > out.maxDepthReached) {
                    out.maxDepthReached = depth;
                }
                requireStageType(
                        callerSym,
                        callerSym,
                        node.ownerClass(),
                        EdgeKind.DIRECT_CALL,
                        "method-owner",
                        "",
                        classIndex,
                        out
                );

                if (normalizedSinks.contains(callerSym) && !out.rootSymbols.contains(callerSym)) {
                    continue;
                }
                out.stageReferencedOwners.add(node.ownerClass());

                for (IndyFunctionalSite encSite : classIndex.indySitesInMethod(node.ownerClass(), node.name())) {
                    requireStageType(
                            callerSym,
                            encSite.functionalInterface() + "#" + encSite.samMethodName(),
                            encSite.functionalInterface(),
                            EdgeKind.INDY_LAMBDA,
                            "indy-sam-interface",
                            "",
                            classIndex,
                            out
                    );
                }

                registerInstantiatedConcreteTypes(node, hasConcreteScopeFilter, allowedConcreteTypes, classIndex);
                recordMethodFieldEffects(
                        callerSym,
                        node,
                        hasConcreteScopeFilter,
                        allowedConcreteTypes,
                        normalizedSinks,
                        classIndex,
                        out
                );

                Set<String> concreteFilterOrNull = hasConcreteScopeFilter ? allowedConcreteTypes : null;
                if (isAbstractOrInterfaceNoBody(node)) {
                    expandRootAbstractSubtypes(
                            callerSym,
                            node,
                            depth,
                            concreteFilterOrNull,
                            normalizedSinks,
                            expandedNodeKeys,
                            countedMethodSymbols,
                            maxHopsPerStage,
                            maxNodesPerStage,
                            classIndex,
                            queue,
                            polymorphicSites,
                            out
                    );
                }

                String asyncViaDetail = buildAsyncCombinatorDetail(node);

                expandDirectAndPolymorphicCalls(
                        callerSym,
                        node,
                        depth,
                        concreteFilterOrNull,
                        normalizedSinks,
                        expandedNodeKeys,
                        countedMethodSymbols,
                        maxHopsPerStage,
                        maxNodesPerStage,
                        classIndex,
                        queue,
                        polymorphicSites,
                        out
                );

                for (String dynFnSym : node.dynamicFunctionalDispatches()) {
                    boolean isAsyncFn = node.asyncFunctionalDispatches().contains(dynFnSym);
                    if (dynFnSym.startsWith("UNRESOLVED_CALLBACK:") || dynFnSym.startsWith("UNKNOWN_INDY:")) {
                        String markerDetail = dynFnSym.startsWith("UNKNOWN_INDY:")
                                ? "opaque-invokedynamic"
                                : "interpreter-aborted-callback-analysis";
                        out.reachableMethods.add(dynFnSym);
                        out.addEdge(callerSym, dynFnSym, EdgeKind.FUNCTOR_TASK, markerDetail);
                        if (isAsyncFn) {
                            out.scheduledAsyncTargets.add(dynFnSym);
                            out.addEdge(callerSym, dynFnSym, EdgeKind.ASYNC_FUTURE_OR_SCHEDULER, markerDetail);
                        }
                        continue;
                    }
                    expandJdkFunctionalInterfaceDispatch(
                            callerSym,
                            dynFnSym,
                            extractOwnerClass(dynFnSym),
                            extractMemberName(dynFnSym),
                            isAsyncFn,
                            depth,
                            concreteFilterOrNull,
                            normalizedSinks,
                            expandedNodeKeys,
                            countedMethodSymbols,
                            maxHopsPerStage,
                            maxNodesPerStage,
                            classIndex,
                            queue,
                            polymorphicSites,
                            out
                    );
                }

                expandSpawnedLambdas(
                        callerSym,
                        node,
                        asyncViaDetail,
                        depth,
                        concreteFilterOrNull,
                        normalizedSinks,
                        expandedNodeKeys,
                        countedMethodSymbols,
                        maxHopsPerStage,
                        maxNodesPerStage,
                        classIndex,
                        queue,
                        polymorphicSites,
                        out
                );

                for (String instCls : node.instantiatedClasses()) {
                    if (!isBootstrapJdkOrLoggingClass(instCls)) {
                        inspectInstantiatedClassConstructorsAndFunctors(
                                callerSym,
                                instCls,
                                EdgeKind.DIRECT_CALL,
                                "constructor",
                                asyncViaDetail,
                                depth,
                                concreteFilterOrNull,
                                normalizedSinks,
                                expandedNodeKeys,
                                countedMethodSymbols,
                                maxHopsPerStage,
                                maxNodesPerStage,
                                classIndex,
                                queue,
                                out
                        );
                    }
                }

                expandQueueAndMutableFieldHandoffs(
                        callerSym,
                        node,
                        depth,
                        otherStageRoots,
                        normalizedSinks,
                        expandedNodeKeys,
                        countedMethodSymbols,
                        maxHopsPerStage,
                        maxNodesPerStage,
                        classIndex,
                        queue,
                        out
                );
            }

            if (classIndex.loadedClassCount() != lastCheckedLoadedClassCount) {
                lastCheckedLoadedClassCount = classIndex.loadedClassCount();
                Set<String> concreteFilterOrNull = hasConcreteScopeFilter ? allowedConcreteTypes : null;
                List<DeferredPolymorphicSite> snapshot = List.copyOf(polymorphicSites);
                for (DeferredPolymorphicSite site : snapshot) {
                    int currentLoaded = classIndex.loadedClassCount();
                    Integer prevLoaded = siteLastCheckedCount.get(site);
                    if (prevLoaded != null && prevLoaded == currentLoaded) {
                        continue;
                    }
                    siteLastCheckedCount.put(site, currentLoaded);
                    expandPolymorphicSubtypesAndIndyTargets(
                            site.dispatchSym(),
                            site.invokedOwner(),
                            site.declaredOwner(),
                            site.methodName(),
                            site.excludeOwnerOrNull(),
                            site.excludeDescriptorOrNull(),
                            site.isAsync(),
                            site.nextDepth(),
                            concreteFilterOrNull,
                            normalizedSinks,
                            expandedNodeKeys,
                            countedMethodSymbols,
                            maxHopsPerStage,
                            maxNodesPerStage,
                            classIndex,
                            queue,
                            polymorphicSites,
                            out
                    );
                }
            }
        } while (!queue.isEmpty());

        return out;
    }

    private record DeferredPolymorphicSite(
            String dispatchSym,
            String invokedOwner,
            String declaredOwner,
            String methodName,
            String excludeOwnerOrNull,
            String excludeDescriptorOrNull,
            boolean isAsync,
            int nextDepth
    ) {
        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof DeferredPolymorphicSite other)) {
                return false;
            }
            return isAsync == other.isAsync
                    && Objects.equals(dispatchSym, other.dispatchSym)
                    && Objects.equals(invokedOwner, other.invokedOwner)
                    && Objects.equals(declaredOwner, other.declaredOwner)
                    && Objects.equals(methodName, other.methodName)
                    && Objects.equals(excludeOwnerOrNull, other.excludeOwnerOrNull)
                    && Objects.equals(excludeDescriptorOrNull, other.excludeDescriptorOrNull);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    dispatchSym,
                    invokedOwner,
                    declaredOwner,
                    methodName,
                    excludeOwnerOrNull,
                    excludeDescriptorOrNull,
                    isAsync
            );
        }
    }

    /**
     * Centralized fail-closed gate for uninspected callable bodies and unavailable non-bootstrap
     * class hierarchies. Marks the stage graph truncated, logs a deduplicated {@code Level.WARNING},
     * invalidates closed-world completeness when a class is missing, and propagates async hops.
     */
    private static boolean recordUninspectedBody(
            String fromSym,
            String targetSym,
            String owner,
            EdgeKind kind,
            String detail,
            String missingTypeOrNull,
            String asyncDetailOrEmpty,
            ClosedWorldClassIndex classIndex,
            StageTraceResult out
    ) {
        String unwrappedOwner = unwrapElementClassIfArray(owner);
        if (unwrappedOwner.isEmpty() || isBootstrapJdkOrLoggingClass(unwrappedOwner)) {
            return false;
        }
        String missing = (missingTypeOrNull != null && !missingTypeOrNull.isEmpty())
                ? missingTypeOrNull
                : unwrappedOwner;
        String unavailabilityTag = missingTypeOrNull != null
                ? (CLASS_UNAVAILABLE_EDGE_MARKER + missing)
                : (":body-unavailable:" + missing);
        classIndex.warnMissingBody(targetSym, missing);
        out.addEdge(fromSym, targetSym, kind, detail + unavailabilityTag);
        if (asyncDetailOrEmpty != null && !asyncDetailOrEmpty.isEmpty()) {
            out.scheduledAsyncTargets.add(targetSym);
            if (kind != EdgeKind.ASYNC_FUTURE_OR_SCHEDULER) {
                out.addEdge(
                        fromSym,
                        targetSym,
                        EdgeKind.ASYNC_FUTURE_OR_SCHEDULER,
                        asyncDetailOrEmpty + unavailabilityTag
                );
            }
        }
        out.truncatedByBounds = true;
        out.unexpandedFrontierSymbols.add(targetSym);
        return true;
    }

    private static boolean requireStageType(
            String fromSym,
            String frontierSymbolOnMissing,
            String rawClassName,
            EdgeKind kind,
            String detail,
            String asyncDetailOrEmpty,
            ClosedWorldClassIndex classIndex,
            StageTraceResult out
    ) {
        String unwrapped = unwrapElementClassIfArray(rawClassName);
        if (unwrapped.isEmpty() || isBootstrapJdkOrLoggingClass(unwrapped)) {
            return true;
        }
        String missingType = classIndex.requireHierarchyLoaded(unwrapped);
        if (missingType != null) {
            recordUninspectedBody(
                    fromSym,
                    frontierSymbolOnMissing,
                    unwrapped,
                    kind,
                    detail,
                    missingType,
                    asyncDetailOrEmpty,
                    classIndex,
                    out
            );
            return false;
        }
        return true;
    }

    private static List<MethodNode> resolveCallableOrRecordUninspected(
            String fromSym,
            String rawLookupRef,
            String canonicalTargetSym,
            String targetOwner,
            String targetMemberName,
            EdgeKind kind,
            String detail,
            String asyncDetailOrEmpty,
            boolean alwaysFailOnMissingMethod,
            ClosedWorldClassIndex classIndex,
            StageTraceResult out
    ) {
        String unwrappedOwner = unwrapElementClassIfArray(targetOwner);
        if (unwrappedOwner.isEmpty() || isBootstrapJdkOrLoggingClass(unwrappedOwner)) {
            return List.of();
        }
        String missingType = classIndex.requireHierarchyLoaded(unwrappedOwner);
        List<MethodNode> resolved = "<init>".equals(targetMemberName)
                ? classIndex.findConstructors(unwrappedOwner)
                : classIndex.resolveMethodsForInvocation(rawLookupRef);
        if (missingType != null) {
            recordUninspectedBody(
                    fromSym,
                    canonicalTargetSym,
                    unwrappedOwner,
                    kind,
                    detail,
                    missingType,
                    asyncDetailOrEmpty,
                    classIndex,
                    out
            );
            return resolved;
        }
        if (resolved.isEmpty()
                && (alwaysFailOnMissingMethod
                        || !classIndex.canInheritFromBootstrap(unwrappedOwner, targetMemberName))) {
            recordUninspectedBody(
                    fromSym,
                    canonicalTargetSym,
                    unwrappedOwner,
                    kind,
                    detail,
                    null,
                    asyncDetailOrEmpty,
                    classIndex,
                    out
            );
        }
        return resolved;
    }

    private static void seedRootWorkItems(
            List<String> rootMethodSymbols,
            ClosedWorldClassIndex classIndex,
            Set<String> expandedNodeKeys,
            Set<String> countedMethodSymbols,
            int maxNodesPerStage,
            Deque<WorkItem> queue,
            StageTraceResult out
    ) {
        for (String rawRoot : rootMethodSymbols) {
            String canonicalRoot = stripMethodDescriptor(rawRoot);
            out.rootSymbols.add(canonicalRoot);
            String owner = extractOwnerClass(rawRoot);
            String member = extractMemberName(canonicalRoot);
            if (isBootstrapJdkOrLoggingClass(owner)) {
                out.reachableMethods.add(canonicalRoot);
                countedMethodSymbols.add(canonicalRoot);
                continue;
            }
            List<MethodNode> rootNodes = resolveCallableOrRecordUninspected(
                    "STAGE:" + canonicalRoot,
                    rawRoot,
                    canonicalRoot,
                    owner,
                    member,
                    EdgeKind.DIRECT_CALL,
                    "root",
                    "",
                    true,
                    classIndex,
                    out
            );
            if (rootNodes.isEmpty()) {
                continue;
            }
            for (MethodNode rn : rootNodes) {
                String sym = rn.symbol();
                if (countedMethodSymbols.size() >= maxNodesPerStage && !countedMethodSymbols.contains(sym)) {
                    out.truncatedByBounds = true;
                    out.unexpandedFrontierSymbols.add(sym);
                    continue;
                }
                out.reachableMethods.add(sym);
                if (isAbstractOrInterfaceNoBody(rn)) {
                    out.abstractDispatchMethods.add(sym);
                } else {
                    countedMethodSymbols.add(sym);
                }
                String visitKey = rn.ownerClass() + "#" + rn.name() + rn.descriptor();
                if (expandedNodeKeys.add(visitKey)) {
                    queue.add(new WorkItem(sym, rn, 0));
                }
            }
        }
    }

    private static void registerInstantiatedConcreteTypes(
            MethodNode node,
            boolean hasConcreteScopeFilter,
            Set<String> allowedConcreteTypes,
            ClosedWorldClassIndex classIndex
    ) {
        for (String instCls : node.instantiatedClasses()) {
            if (!isBootstrapJdkOrLoggingClass(instCls)) {
                classIndex.ensureLoaded(instCls);
                if (hasConcreteScopeFilter) {
                    allowedConcreteTypes.add(instCls);
                    allowedConcreteTypes.addAll(classIndex.allSupertypesOf(instCls));
                }
            }
        }
    }

    private static void expandRootAbstractSubtypes(
            String callerSym,
            MethodNode node,
            int depth,
            Set<String> concreteFilterOrNull,
            Set<String> normalizedSinks,
            Set<String> expandedNodeKeys,
            Set<String> countedMethodSymbols,
            int maxHopsPerStage,
            int maxNodesPerStage,
            ClosedWorldClassIndex classIndex,
            Deque<WorkItem> queue,
            Set<DeferredPolymorphicSite> polymorphicSites,
            StageTraceResult out
    ) {
        registerDeferredPolymorphicSite(
                polymorphicSites,
                new DeferredPolymorphicSite(
                        callerSym,
                        node.ownerClass(),
                        node.ownerClass(),
                        node.name(),
                        null,
                        null,
                        false,
                        depth
                )
        );
        expandPolymorphicSubtypesAndIndyTargets(
                callerSym,
                node.ownerClass(),
                node.ownerClass(),
                node.name(),
                null,
                null,
                false,
                depth,
                concreteFilterOrNull,
                normalizedSinks,
                expandedNodeKeys,
                countedMethodSymbols,
                maxHopsPerStage,
                maxNodesPerStage,
                classIndex,
                queue,
                polymorphicSites,
                out
        );
    }

    private static String buildAsyncCombinatorDetail(MethodNode node) {
        List<String> combinators = new ArrayList<>();
        for (String calledRef : node.calledMethods()) {
            if (isAsyncOrSchedulerCall(extractOwnerClass(calledRef), extractMemberName(calledRef))) {
                combinators.add(simpleMethodRef(calledRef));
            }
        }
        return combinators.isEmpty() ? "" : "via " + String.join(",", combinators);
    }

    private static void expandDirectAndPolymorphicCalls(
            String callerSym,
            MethodNode node,
            int depth,
            Set<String> concreteFilterOrNull,
            Set<String> normalizedSinks,
            Set<String> expandedNodeKeys,
            Set<String> countedMethodSymbols,
            int maxHopsPerStage,
            int maxNodesPerStage,
            ClosedWorldClassIndex classIndex,
            Deque<WorkItem> queue,
            Set<DeferredPolymorphicSite> polymorphicSites,
            StageTraceResult out
    ) {
        for (String calledRef : node.calledMethods()) {
            String canonicalCalled = stripMethodDescriptor(calledRef);
            String targetOwner = extractOwnerClass(canonicalCalled);
            String targetName = extractMemberName(canonicalCalled);
            boolean isAsyncCall = isAsyncOrSchedulerCall(targetOwner, targetName);

            String expectedJdkSam = BytecodeCausalGraphScanner.samMethodForJdkFunctionalInterface(targetOwner);
            if (expectedJdkSam != null && expectedJdkSam.equals(targetName)) {
                expandJdkFunctionalInterfaceDispatch(
                        callerSym,
                        canonicalCalled,
                        targetOwner,
                        targetName,
                        isAsyncCall,
                        depth,
                        concreteFilterOrNull,
                        normalizedSinks,
                        expandedNodeKeys,
                        countedMethodSymbols,
                        maxHopsPerStage,
                        maxNodesPerStage,
                        classIndex,
                        queue,
                        polymorphicSites,
                        out
                );
                continue;
            }

            if (isOpaqueReflectiveOrUnsafeCall(targetOwner, targetName)) {
                enqueueUnresolvedSymbol(
                        callerSym,
                        canonicalCalled,
                        EdgeKind.DIRECT_CALL,
                        "opaque-reflective-or-unsafe-call",
                        depth + 1,
                        countedMethodSymbols,
                        maxHopsPerStage,
                        maxNodesPerStage,
                        out
                );
                continue;
            }

            if (isBootstrapJdkOrLoggingClass(targetOwner)) {
                if (isAsyncCall
                        && node.spawnedLambdas().isEmpty()
                        && node.instantiatedClasses().isEmpty()
                        && node.asyncFunctionalDispatches().isEmpty()) {
                    out.scheduledAsyncTargets.add(canonicalCalled);
                    out.addEdge(callerSym, canonicalCalled, EdgeKind.ASYNC_FUTURE_OR_SCHEDULER, canonicalCalled);
                }
                continue;
            }

            if (isNonWorldgenInfrastructureSinkMethod(targetOwner, targetName)) {
                out.reachableMethods.add(canonicalCalled);
                out.addEdge(callerSym, canonicalCalled, EdgeKind.DIRECT_CALL, "benign-infrastructure-sink");
                continue;
            }

            EdgeKind primaryKind = isAsyncCall ? EdgeKind.ASYNC_FUTURE_OR_SCHEDULER : EdgeKind.DIRECT_CALL;
            String primaryDetail = isAsyncCall ? "scheduler-dispatch" : "invoke";
            String asyncHopDetail = isAsyncCall ? primaryDetail : "";

            List<MethodNode> resolvedTargets = resolveCallableOrRecordUninspected(
                    callerSym,
                    canonicalCalled,
                    canonicalCalled,
                    targetOwner,
                    targetName,
                    primaryKind,
                    primaryDetail,
                    asyncHopDetail,
                    false,
                    classIndex,
                    out
            );
            if (resolvedTargets.isEmpty()) {
                if (isAsyncCall) {
                    out.scheduledAsyncTargets.add(canonicalCalled);
                }
                if (!out.unexpandedFrontierSymbols.contains(canonicalCalled)) {
                    enqueueUnresolvedSymbol(
                            callerSym,
                            canonicalCalled,
                            primaryKind,
                            primaryDetail,
                            depth + 1,
                            countedMethodSymbols,
                            maxHopsPerStage,
                            maxNodesPerStage,
                            out
                    );
                }
                continue;
            }

            for (MethodNode targetNode : resolvedTargets) {
                expandSingleResolvedCallTarget(
                        callerSym,
                        canonicalCalled,
                        targetOwner,
                        targetName,
                        targetNode,
                        primaryKind,
                        primaryDetail,
                        isAsyncCall,
                        depth,
                        concreteFilterOrNull,
                        normalizedSinks,
                        expandedNodeKeys,
                        countedMethodSymbols,
                        maxHopsPerStage,
                        maxNodesPerStage,
                        classIndex,
                        queue,
                        polymorphicSites,
                        out
                );
            }
        }
    }

    private static void registerDeferredPolymorphicSite(
            Set<DeferredPolymorphicSite> polymorphicSites,
            DeferredPolymorphicSite site
    ) {
        polymorphicSites.add(site);
    }

    private static void expandJdkFunctionalInterfaceDispatch(
            String callerSym,
            String canonicalFnSym,
            String fnInterface,
            String samMethodName,
            boolean isAsyncFn,
            int depth,
            Set<String> concreteFilterOrNull,
            Set<String> normalizedSinks,
            Set<String> expandedNodeKeys,
            Set<String> countedMethodSymbols,
            int maxHopsPerStage,
            int maxNodesPerStage,
            ClosedWorldClassIndex classIndex,
            Deque<WorkItem> queue,
            Set<DeferredPolymorphicSite> polymorphicSites,
            StageTraceResult out
    ) {
        EdgeKind callKind = isAsyncFn ? EdgeKind.ASYNC_FUTURE_OR_SCHEDULER : EdgeKind.INTERFACE_SUBTYPE_CALL;
        String callDetail = isAsyncFn ? "async-functional-dispatch" : "functional-interface-dispatch";
        out.addEdge(callerSym, canonicalFnSym, callKind, callDetail);

        if (depth + 1 > maxHopsPerStage) {
            if (isAsyncFn) {
                out.scheduledAsyncTargets.add(canonicalFnSym);
            }
            if (!out.reachableMethods.contains(canonicalFnSym) && !normalizedSinks.contains(canonicalFnSym)) {
                out.truncatedByBounds = true;
                out.unexpandedFrontierSymbols.add(canonicalFnSym);
            }
            return;
        }
        out.reachableMethods.add(canonicalFnSym);
        out.abstractDispatchMethods.add(canonicalFnSym);
        if (isAsyncFn) {
            out.scheduledAsyncTargets.add(canonicalFnSym);
        }

            DeferredPolymorphicSite deferredSite = new DeferredPolymorphicSite(
                    canonicalFnSym,
                    fnInterface,
                    fnInterface,
                    samMethodName,
                    null,
                    null,
                    isAsyncFn,
                    depth + 1
            );
            if (polymorphicSites.add(deferredSite)) {
                expandPolymorphicSubtypesAndIndyTargets(
                        canonicalFnSym,
                        fnInterface,
                        fnInterface,
                        samMethodName,
                        null,
                        null,
                        isAsyncFn,
                        depth + 1,
                        concreteFilterOrNull,
                        normalizedSinks,
                        expandedNodeKeys,
                        countedMethodSymbols,
                        maxHopsPerStage,
                        maxNodesPerStage,
                        classIndex,
                        queue,
                        polymorphicSites,
                        out
                );
            }
    }

    private static boolean expandPolymorphicSubtypesAndIndyTargets(
            String dispatchSym,
            String invokedOwner,
            String declaredOwner,
            String methodName,
            String excludeOwnerOrNull,
            String excludeDescriptorOrNull,
            boolean isAsyncDispatch,
            int nextDepth,
            Set<String> concreteFilterOrNull,
            Set<String> normalizedSinks,
            Set<String> expandedNodeKeys,
            Set<String> countedMethodSymbols,
            int maxHopsPerStage,
            int maxNodesPerStage,
            ClosedWorldClassIndex classIndex,
            Deque<WorkItem> queue,
            Set<DeferredPolymorphicSite> polymorphicSites,
            StageTraceResult out
    ) {
        Set<String> baseTypes = new LinkedHashSet<>();
        if (invokedOwner != null && !invokedOwner.isEmpty()) {
            baseTypes.add(invokedOwner);
        }
        if (declaredOwner != null && !declaredOwner.isEmpty()) {
            baseTypes.add(declaredOwner);
        }

        Set<String> candidateTypes = new LinkedHashSet<>();
        boolean includeIndySites = false;
        for (String base : baseTypes) {
            requireStageType(
                    dispatchSym,
                    base + "#" + methodName,
                    base,
                    EdgeKind.INTERFACE_SUBTYPE_CALL,
                    "polymorphic-base-type",
                    isAsyncDispatch ? "polymorphic-base-type" : "",
                    classIndex,
                    out
            );
            candidateTypes.add(base);
            Set<String> jdkSubIfaces = JDK_FUNCTIONAL_SUBINTERFACES.getOrDefault(base, Set.of());
            if (!jdkSubIfaces.isEmpty()
                    || BytecodeCausalGraphScanner.samMethodForJdkFunctionalInterface(base) != null
                    || classIndex.isSamType(base)) {
                includeIndySites = true;
            }
            candidateTypes.addAll(jdkSubIfaces);
            if (includeIndySites) {
                candidateTypes.addAll(classIndex.allSubtypesOf(base));
            }
        }

        boolean matchedAnyTarget = false;
        for (String base : baseTypes) {
            List<MethodNode> subtypeImpls = classIndex.resolveSubtypeImplementations(
                    base,
                    methodName,
                    concreteFilterOrNull
            );
            for (MethodNode subImpl : subtypeImpls) {
                if (excludeOwnerOrNull != null
                        && subImpl.ownerClass().equals(excludeOwnerOrNull)
                        && (excludeDescriptorOrNull == null || subImpl.descriptor().equals(excludeDescriptorOrNull))) {
                    continue;
                }
                matchedAnyTarget = true;
                out.resolvedSubtypeTargets
                        .computeIfAbsent(dispatchSym, k -> new LinkedHashSet<>())
                        .add(subImpl.symbol());
                if (isAsyncDispatch) {
                    out.scheduledAsyncTargets.add(subImpl.symbol());
                }
                enqueueTargetMethod(
                        dispatchSym,
                        subImpl.symbol(),
                        subImpl,
                        EdgeKind.INTERFACE_SUBTYPE_CALL,
                        "subtype-dispatch",
                        nextDepth,
                        normalizedSinks,
                        expandedNodeKeys,
                        countedMethodSymbols,
                        maxHopsPerStage,
                        maxNodesPerStage,
                        queue,
                        out
                );
            }
        }

        List<IndyFunctionalSite> indySites = includeIndySites
                ? classIndex.resolveIndyFunctionalSites(
                        candidateTypes,
                        methodName,
                        concreteFilterOrNull,
                        out.stageReferencedOwners
                )
                : List.of();
        for (IndyFunctionalSite site : indySites) {
            String targetSym = stripMethodDescriptor(site.targetSymbol());
            String targetOwner = extractOwnerClass(targetSym);
            String targetMember = extractMemberName(targetSym);
            String targetJdkSam = BytecodeCausalGraphScanner.samMethodForJdkFunctionalInterface(targetOwner);
            if (targetJdkSam != null && targetJdkSam.equals(targetMember)) {
                matchedAnyTarget = true;
                out.resolvedSubtypeTargets
                        .computeIfAbsent(dispatchSym, k -> new LinkedHashSet<>())
                        .add(targetSym);
                if (!targetSym.equals(dispatchSym)) {
                    expandJdkFunctionalInterfaceDispatch(
                            dispatchSym,
                            targetSym,
                            targetOwner,
                            targetMember,
                            isAsyncDispatch,
                            nextDepth,
                            concreteFilterOrNull,
                            normalizedSinks,
                            expandedNodeKeys,
                            countedMethodSymbols,
                            maxHopsPerStage,
                            maxNodesPerStage,
                            classIndex,
                            queue,
                            polymorphicSites,
                            out
                    );
                }
                continue;
            }
            if (isBootstrapJdkOrLoggingClass(targetOwner)) {
                matchedAnyTarget = true;
                out.resolvedSubtypeTargets
                        .computeIfAbsent(dispatchSym, k -> new LinkedHashSet<>())
                        .add(targetSym);
                if (isAsyncDispatch) {
                    out.scheduledAsyncTargets.add(targetSym);
                }
                enqueueUnresolvedSymbol(
                        dispatchSym,
                        targetSym,
                        EdgeKind.INDY_LAMBDA,
                        "indy-jdk-method-ref",
                        nextDepth,
                        countedMethodSymbols,
                        maxHopsPerStage,
                        maxNodesPerStage,
                        out
                );
                continue;
            }
            matchedAnyTarget = true;
            String asyncIndyDetail = isAsyncDispatch ? "functional-indy-dispatch" : "";
            List<MethodNode> targetNodes = resolveCallableOrRecordUninspected(
                    dispatchSym,
                    targetSym,
                    targetSym,
                    targetOwner,
                    targetMember,
                    EdgeKind.INDY_LAMBDA,
                    "functional-indy-dispatch",
                    asyncIndyDetail,
                    false,
                    classIndex,
                    out
            );
            if (targetNodes.isEmpty()) {
                out.resolvedSubtypeTargets
                        .computeIfAbsent(dispatchSym, k -> new LinkedHashSet<>())
                        .add(targetSym);
                if (isAsyncDispatch) {
                    out.scheduledAsyncTargets.add(targetSym);
                }
                if (!out.unexpandedFrontierSymbols.contains(targetSym)) {
                    enqueueUnresolvedSymbol(
                            dispatchSym,
                            targetSym,
                            EdgeKind.INDY_LAMBDA,
                            "functional-indy-dispatch",
                            nextDepth,
                            countedMethodSymbols,
                            maxHopsPerStage,
                            maxNodesPerStage,
                            out
                    );
                }
            } else {
                for (MethodNode tn : targetNodes) {
                    out.resolvedSubtypeTargets
                            .computeIfAbsent(dispatchSym, k -> new LinkedHashSet<>())
                            .add(tn.symbol());
                    if (isAsyncDispatch) {
                        out.scheduledAsyncTargets.add(tn.symbol());
                    }
                    boolean enq = enqueueTargetMethod(
                            dispatchSym,
                            tn.symbol(),
                            tn,
                            EdgeKind.INDY_LAMBDA,
                            "functional-indy-dispatch",
                            nextDepth,
                            normalizedSinks,
                            expandedNodeKeys,
                            countedMethodSymbols,
                            maxHopsPerStage,
                            maxNodesPerStage,
                            queue,
                            out
                    );
                    if (isAbstractOrInterfaceNoBody(tn)) {
                        out.abstractDispatchMethods.add(tn.symbol());
                        if ((tn.accessFlags()
                                & (BytecodeCausalGraphScanner.ACC_STATIC
                                        | BytecodeCausalGraphScanner.ACC_PRIVATE
                                        | BytecodeCausalGraphScanner.ACC_FINAL)) == 0
                                && (enq || nextDepth <= maxHopsPerStage)
                                && !tn.symbol().equals(dispatchSym)) {
                            registerDeferredPolymorphicSite(
                                    polymorphicSites,
                                    new DeferredPolymorphicSite(
                                            tn.symbol(),
                                            targetOwner,
                                            tn.ownerClass(),
                                            targetMember,
                                            tn.ownerClass(),
                                            tn.descriptor(),
                                            isAsyncDispatch,
                                            nextDepth + 1
                                    )
                            );
                        }
                    }
                }
            }
        }
        return matchedAnyTarget;
    }

    private static void expandSingleResolvedCallTarget(
            String callerSym,
            String canonicalCalled,
            String targetOwner,
            String targetName,
            MethodNode targetNode,
            EdgeKind primaryKind,
            String primaryDetail,
            boolean isAsyncCall,
            int depth,
            Set<String> concreteFilterOrNull,
            Set<String> normalizedSinks,
            Set<String> expandedNodeKeys,
            Set<String> countedMethodSymbols,
            int maxHopsPerStage,
            int maxNodesPerStage,
            ClosedWorldClassIndex classIndex,
            Deque<WorkItem> queue,
            Set<DeferredPolymorphicSite> polymorphicSites,
            StageTraceResult out
    ) {
        String resolvedSym = targetNode.symbol();
        if (isAsyncCall) {
            out.scheduledAsyncTargets.add(resolvedSym);
        }
        String edgeDetail = resolvedSym.equals(canonicalCalled) ? primaryDetail : ("inherited via " + targetOwner);
        if ((normalizedSinks.contains(resolvedSym) || normalizedSinks.contains(canonicalCalled))
                && !out.rootSymbols.contains(resolvedSym)) {
            out.addEdge(callerSym, resolvedSym, primaryKind, edgeDetail);
            if (depth + 1 > maxHopsPerStage) {
                return;
            }
            if (!countedMethodSymbols.contains(resolvedSym) && countedMethodSymbols.size() >= maxNodesPerStage) {
                out.truncatedByBounds = true;
                out.unexpandedFrontierSymbols.add(resolvedSym);
                return;
            }
            out.reachableMethods.add(resolvedSym);
            if (!isAbstractOrInterfaceNoBody(targetNode)) {
                countedMethodSymbols.add(resolvedSym);
            } else {
                out.abstractDispatchMethods.add(resolvedSym);
            }
            return;
        }
        boolean enqueued = enqueueTargetMethod(
                callerSym,
                resolvedSym,
                targetNode,
                primaryKind,
                edgeDetail,
                depth + 1,
                normalizedSinks,
                expandedNodeKeys,
                countedMethodSymbols,
                maxHopsPerStage,
                maxNodesPerStage,
                queue,
                out
        );
        if (isAbstractOrInterfaceNoBody(targetNode)) {
            out.abstractDispatchMethods.add(resolvedSym);
            if (POLYMORPHIC_FUNCTOR_METHODS.contains(targetName)
                    || ZERO_ARG_FUNCTOR_METHODS.contains(targetName)
                    || classIndex.isSamType(targetNode.ownerClass())) {
                out.addEdge(callerSym, resolvedSym, EdgeKind.FUNCTOR_TASK, "sam-task-dispatch");
            }
        }
        if (!enqueued) {
            return;
        }
        if ((targetNode.accessFlags()
                & (BytecodeCausalGraphScanner.ACC_STATIC
                        | BytecodeCausalGraphScanner.ACC_PRIVATE
                        | BytecodeCausalGraphScanner.ACC_FINAL)) != 0) {
            return;
        }
        ParsedClass ownerPc = classIndex.loadedClassOrNull(targetNode.ownerClass());
        if (ownerPc != null
                && (ownerPc.accessFlags() & BytecodeCausalGraphScanner.ACC_FINAL) != 0
                && !isAbstractOrInterfaceNoBody(targetNode)
                && targetOwner.equals(targetNode.ownerClass())) {
            return;
        }
        if ((normalizedSinks.contains(resolvedSym) || normalizedSinks.contains(canonicalCalled))
                && !out.rootSymbols.contains(resolvedSym)) {
            return;
        }
        DeferredPolymorphicSite deferredSite = new DeferredPolymorphicSite(
                resolvedSym,
                targetOwner,
                targetNode.ownerClass(),
                targetName,
                targetNode.ownerClass(),
                targetNode.descriptor(),
                isAsyncCall,
                depth + 1
        );
        if (polymorphicSites.add(deferredSite)) {
            expandPolymorphicSubtypesAndIndyTargets(
                    resolvedSym,
                    targetOwner,
                    targetNode.ownerClass(),
                    targetName,
                    targetNode.ownerClass(),
                    targetNode.descriptor(),
                    isAsyncCall,
                    depth + 1,
                    concreteFilterOrNull,
                    normalizedSinks,
                    expandedNodeKeys,
                    countedMethodSymbols,
                    maxHopsPerStage,
                    maxNodesPerStage,
                    classIndex,
                    queue,
                    polymorphicSites,
                    out
            );
        }
    }

    private static void expandSpawnedLambdas(
            String callerSym,
            MethodNode node,
            String asyncViaDetail,
            int depth,
            Set<String> concreteFilterOrNull,
            Set<String> normalizedSinks,
            Set<String> expandedNodeKeys,
            Set<String> countedMethodSymbols,
            int maxHopsPerStage,
            int maxNodesPerStage,
            ClosedWorldClassIndex classIndex,
            Deque<WorkItem> queue,
            Set<DeferredPolymorphicSite> polymorphicSites,
            StageTraceResult out
    ) {
        for (String rawLambdaSym : node.spawnedLambdas()) {
            String lambdaSym = stripMethodDescriptor(rawLambdaSym);
            String lambdaOwner = extractOwnerClass(lambdaSym);
            String lambdaName = extractMemberName(lambdaSym);
            if (isBootstrapJdkOrLoggingClass(lambdaOwner)
                    || isExclusivelyCrashReportDetailLambda(node.ownerClass(), node.name(), lambdaSym, classIndex)) {
                continue;
            }
            if ("<init>".equals(lambdaName)) {
                inspectInstantiatedClassConstructorsAndFunctors(
                        callerSym,
                        lambdaOwner,
                        EdgeKind.INDY_LAMBDA,
                        "method-ref-constructor",
                        asyncViaDetail,
                        depth,
                        concreteFilterOrNull,
                        normalizedSinks,
                        expandedNodeKeys,
                        countedMethodSymbols,
                        maxHopsPerStage,
                        maxNodesPerStage,
                        classIndex,
                        queue,
                        out
                );
                continue;
            }

            List<MethodNode> lambdaTargets = resolveCallableOrRecordUninspected(
                    callerSym,
                    rawLambdaSym,
                    lambdaSym,
                    lambdaOwner,
                    lambdaName,
                    EdgeKind.INDY_LAMBDA,
                    "invokedynamic",
                    asyncViaDetail,
                    false,
                    classIndex,
                    out
            );
            if (!asyncViaDetail.isEmpty()) {
                out.scheduledAsyncTargets.add(lambdaSym);
            }
            if (lambdaTargets.isEmpty()) {
                if (!out.unexpandedFrontierSymbols.contains(lambdaSym)) {
                    enqueueUnresolvedSymbol(
                            callerSym,
                            lambdaSym,
                            EdgeKind.INDY_LAMBDA,
                            "invokedynamic",
                            depth + 1,
                            countedMethodSymbols,
                            maxHopsPerStage,
                            maxNodesPerStage,
                            out
                    );
                    if (!asyncViaDetail.isEmpty()) {
                        out.addEdge(callerSym, lambdaSym, EdgeKind.ASYNC_FUTURE_OR_SCHEDULER, asyncViaDetail);
                    }
                }
                continue;
            }

            for (MethodNode lt : lambdaTargets) {
                expandSingleLambdaTarget(
                        callerSym,
                        lambdaOwner,
                        lambdaName,
                        lt,
                        asyncViaDetail,
                        depth,
                        concreteFilterOrNull,
                        normalizedSinks,
                        expandedNodeKeys,
                        countedMethodSymbols,
                        maxHopsPerStage,
                        maxNodesPerStage,
                        classIndex,
                        queue,
                        polymorphicSites,
                        out
                );
            }
        }
    }

    private static void expandSingleLambdaTarget(
            String callerSym,
            String lambdaOwner,
            String lambdaName,
            MethodNode lt,
            String asyncViaDetail,
            int depth,
            Set<String> concreteFilterOrNull,
            Set<String> normalizedSinks,
            Set<String> expandedNodeKeys,
            Set<String> countedMethodSymbols,
            int maxHopsPerStage,
            int maxNodesPerStage,
            ClosedWorldClassIndex classIndex,
            Deque<WorkItem> queue,
            Set<DeferredPolymorphicSite> polymorphicSites,
            StageTraceResult out
    ) {
        String resolvedLambdaSym = lt.symbol();
        if (!asyncViaDetail.isEmpty()) {
            out.scheduledAsyncTargets.add(resolvedLambdaSym);
            out.addEdge(callerSym, resolvedLambdaSym, EdgeKind.ASYNC_FUTURE_OR_SCHEDULER, asyncViaDetail);
        }
        enqueueTargetMethod(
                callerSym,
                resolvedLambdaSym,
                lt,
                EdgeKind.INDY_LAMBDA,
                "invokedynamic",
                depth + 1,
                normalizedSinks,
                expandedNodeKeys,
                countedMethodSymbols,
                maxHopsPerStage,
                maxNodesPerStage,
                queue,
                out
        );
        if (isAbstractOrInterfaceNoBody(lt)) {
            out.abstractDispatchMethods.add(resolvedLambdaSym);
            if ((lt.accessFlags()
                    & (BytecodeCausalGraphScanner.ACC_STATIC
                            | BytecodeCausalGraphScanner.ACC_PRIVATE
                            | BytecodeCausalGraphScanner.ACC_FINAL)) == 0) {
                registerDeferredPolymorphicSite(
                        polymorphicSites,
                        new DeferredPolymorphicSite(
                                resolvedLambdaSym,
                                lambdaOwner,
                                lt.ownerClass(),
                                lambdaName,
                                lt.ownerClass(),
                                lt.descriptor(),
                                !asyncViaDetail.isEmpty(),
                                depth + 1
                        )
                );
                expandPolymorphicSubtypesAndIndyTargets(
                        resolvedLambdaSym,
                        lambdaOwner,
                        lt.ownerClass(),
                        lambdaName,
                        lt.ownerClass(),
                        lt.descriptor(),
                        !asyncViaDetail.isEmpty(),
                        depth + 1,
                        concreteFilterOrNull,
                        normalizedSinks,
                        expandedNodeKeys,
                        countedMethodSymbols,
                        maxHopsPerStage,
                        maxNodesPerStage,
                        classIndex,
                        queue,
                        polymorphicSites,
                        out
                );
            }
        }
    }

    private static void expandQueueAndMutableFieldHandoffs(
            String callerSym,
            MethodNode node,
            int depth,
            Set<String> otherStageRoots,
            Set<String> normalizedSinks,
            Set<String> expandedNodeKeys,
            Set<String> countedMethodSymbols,
            int maxHopsPerStage,
            int maxNodesPerStage,
            ClosedWorldClassIndex classIndex,
            Deque<WorkItem> queue,
            StageTraceResult out
    ) {
        for (String rawQueueField : node.producedQueueFields()) {
            String queueOwner = extractOwnerClass(rawQueueField);
            requireStageType(
                    callerSym,
                    normalizeRuntimeSymbol(rawQueueField),
                    queueOwner,
                    EdgeKind.QUEUE_DATAFLOW,
                    "queue-field-owner",
                    "queue-handoff",
                    classIndex,
                    out
            );
            String canonQueueField = classIndex.canonicalizeFieldSymbol(rawQueueField);
            List<MethodNode> consumers = classIndex.findFieldReaderMethods(canonQueueField, rawQueueField);
            boolean matchedAnyConsumer = false;
            for (MethodNode consumer : consumers) {
                if (consumer.symbol().equals(callerSym) || otherStageRoots.contains(consumer.symbol())) {
                    continue;
                }
                matchedAnyConsumer = true;
                out.scheduledAsyncTargets.add(consumer.symbol());
                enqueueTargetMethod(
                        callerSym,
                        consumer.symbol(),
                        consumer,
                        EdgeKind.QUEUE_DATAFLOW,
                        canonQueueField,
                        depth + 1,
                        normalizedSinks,
                        expandedNodeKeys,
                        countedMethodSymbols,
                        maxHopsPerStage,
                        maxNodesPerStage,
                        queue,
                        out
                );
            }
            if (!matchedAnyConsumer) {
                out.scheduledAsyncTargets.add(canonQueueField);
                out.addEdge(callerSym, canonQueueField, EdgeKind.QUEUE_DATAFLOW, "unconsumed-queue-handoff");
            }
        }

        if ("<init>".equals(node.name()) || "<clinit>".equals(node.name())) {
            return;
        }
        for (String rawWritten : combineMutatedFields(node)) {
            if (rawWritten.startsWith("UNKNOWN_ARRAY:")
                    || rawWritten.startsWith("UNKNOWN_MUTATION:")
                    || rawWritten.startsWith("GETTER:")) {
                continue;
            }
            String canonField = classIndex.canonicalizeFieldSymbol(rawWritten);
            String fieldOwner = extractOwnerClass(canonField);
            if (out.expandedCarrierClasses.contains(fieldOwner)
                    || isBootstrapJdkOrLoggingClass(fieldOwner)
                    || classIndex.isFieldFinal(canonField)) {
                continue;
            }
            for (MethodNode reader : classIndex.findFieldReaderMethods(canonField, rawWritten)) {
                if (!reader.symbol().equals(callerSym) && !otherStageRoots.contains(reader.symbol())) {
                    enqueueTargetMethod(
                            callerSym,
                            reader.symbol(),
                            reader,
                            EdgeKind.FIELD_DATAFLOW,
                            canonField,
                            depth + 1,
                            normalizedSinks,
                            expandedNodeKeys,
                            countedMethodSymbols,
                            maxHopsPerStage,
                            maxNodesPerStage,
                            queue,
                            out
                    );
                }
            }
        }
    }

    private static void inspectInstantiatedClassConstructorsAndFunctors(
            String callerSym,
            String instCls,
            EdgeKind ctorEdgeKind,
            String ctorDetail,
            String asyncViaDetail,
            int depth,
            Set<String> allowedConcreteTypesOrNull,
            Set<String> normalizedSinks,
            Set<String> expandedNodeKeys,
            Set<String> countedMethodSymbols,
            int maxHopsPerStage,
            int maxNodesPerStage,
            ClosedWorldClassIndex classIndex,
            Deque<WorkItem> queue,
            StageTraceResult out
    ) {
        String unwrappedInst = unwrapElementClassIfArray(instCls);
        if (unwrappedInst.isEmpty() || isBootstrapJdkOrLoggingClass(unwrappedInst)) {
            return;
        }
        requireStageType(
                callerSym,
                unwrappedInst + "#<init>",
                unwrappedInst,
                ctorEdgeKind,
                ctorDetail,
                asyncViaDetail,
                classIndex,
                out
        );
        ParsedClass pc = classIndex.loadedClassOrNull(unwrappedInst);
        if (pc == null) {
            return;
        }

        // Inspect <init> constructors: traverse side-effecting constructors without penalizing trivial field initializers
        String initSym = unwrappedInst + "#<init>";
        boolean ctorSink = normalizedSinks.contains(initSym) && !out.rootSymbols.contains(initSym);
        for (MethodNode ctor : classIndex.findConstructors(unwrappedInst)) {
            if (!ctorSink && isTrivialInitializer(ctor, out.expandedCarrierClasses)) {
                continue;
            }
            enqueueTargetMethod(
                    callerSym,
                    ctor.symbol(),
                    ctor,
                    ctorEdgeKind,
                    ctorDetail,
                    depth + 1,
                    normalizedSinks,
                    expandedNodeKeys,
                    countedMethodSymbols,
                    maxHopsPerStage,
                    maxNodesPerStage,
                    queue,
                    out
            );
        }
        if (ctorSink) {
            return;
        }

        // Inspect task/functor entry points on instantiated class (and inherited functor methods)
        boolean hasPolymorphicSuper = !pc.interfaces().isEmpty()
                || (pc.superClassName() != null && !"java.lang.Object".equals(pc.superClassName())
                && !"java.lang.Record".equals(pc.superClassName())
                && !"java.lang.Enum".equals(pc.superClassName()));

        for (MethodNode m : pc.methods().values()) {
            if ((m.accessFlags() & (BytecodeCausalGraphScanner.ACC_STATIC
                    | BytecodeCausalGraphScanner.ACC_PRIVATE
                    | BytecodeCausalGraphScanner.ACC_ABSTRACT)) != 0) {
                continue;
            }
            boolean zeroArgFunctor = ZERO_ARG_FUNCTOR_METHODS.contains(m.name())
                    && m.descriptor().startsWith("()");
            boolean polyFunctor = hasPolymorphicSuper && POLYMORPHIC_FUNCTOR_METHODS.contains(m.name());
            if (!zeroArgFunctor && !polyFunctor) {
                continue;
            }
            String functorSym = m.symbol();
            if (!asyncViaDetail.isEmpty()) {
                out.scheduledAsyncTargets.add(functorSym);
                out.addEdge(callerSym, functorSym, EdgeKind.ASYNC_FUTURE_OR_SCHEDULER, asyncViaDetail);
            }
            enqueueTargetMethod(
                    callerSym,
                    functorSym,
                    m,
                    EdgeKind.FUNCTOR_TASK,
                    "functor:" + m.name(),
                    depth + 1,
                    normalizedSinks,
                    expandedNodeKeys,
                    countedMethodSymbols,
                    maxHopsPerStage,
                    maxNodesPerStage,
                    queue,
                    out
            );
        }
    }

    private static boolean isTrivialInitializer(MethodNode ctor, Set<String> carrierClasses) {
        if (!"<init>".equals(ctor.name())) {
            return false;
        }
        if (!ctor.spawnedLambdas().isEmpty()
                || !ctor.producedQueueFields().isEmpty()
                || !ctor.collectionAddedFields().isEmpty()
                || !ctor.collectionRemovedFields().isEmpty()
                || !ctor.arrayMutatedFields().isEmpty()
                || !ctor.receiverMutatedFields().isEmpty()
                || !ctor.dynamicFunctionalDispatches().isEmpty()
                || !ctor.asyncFunctionalDispatches().isEmpty()) {
            return false;
        }
        for (String called : ctor.calledMethods()) {
            String owner = extractOwnerClass(called);
            String member = extractMemberName(called);
            if (!isPureTrivialCtorHelperCall(owner, member)) {
                return false;
            }
        }
        for (String inst : ctor.instantiatedClasses()) {
            if (!inst.equals(ctor.ownerClass()) && !isPureTrivialCtorInstantiation(inst)) {
                return false;
            }
        }
        String ownPrefix = ctor.ownerClass() + "#";
        for (String wf : combineMutatedFields(ctor)) {
            if (!wf.startsWith(ownPrefix) || carrierClasses.contains(ctor.ownerClass())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPureTrivialCtorHelperCall(String owner, String member) {
        if (isOpaqueReflectiveOrUnsafeCall(owner, member) || isAsyncOrSchedulerCall(owner, member)) {
            return false;
        }
        String sam = BytecodeCausalGraphScanner.samMethodForJdkFunctionalInterface(owner);
        if (sam != null && sam.equals(member)) {
            return false;
        }
        return "java.lang.Object".equals(owner)
                || "java.lang.Record".equals(owner)
                || "java.lang.Enum".equals(owner)
                || "java.util.Objects".equals(owner)
                || "java.lang.Math".equals(owner)
                || "java.lang.StrictMath".equals(owner)
                || "java.lang.String".equals(owner)
                || "java.lang.Integer".equals(owner)
                || "java.lang.Long".equals(owner)
                || "java.lang.Double".equals(owner)
                || "java.lang.Float".equals(owner)
                || "java.lang.Boolean".equals(owner);
    }

    private static boolean isPureTrivialCtorInstantiation(String binaryClassName) {
        return "java.lang.Object".equals(binaryClassName)
                || "java.lang.Record".equals(binaryClassName)
                || "java.lang.Enum".equals(binaryClassName)
                || "java.util.ArrayList".equals(binaryClassName)
                || "java.util.HashMap".equals(binaryClassName)
                || "java.util.LinkedHashMap".equals(binaryClassName)
                || "java.util.HashSet".equals(binaryClassName)
                || "java.util.LinkedHashSet".equals(binaryClassName)
                || "java.util.ArrayDeque".equals(binaryClassName)
                || "java.util.concurrent.ConcurrentHashMap".equals(binaryClassName);
    }

    private static void recordMethodFieldEffects(
            String callerSym,
            MethodNode node,
            boolean hasConcreteScopeFilter,
            Set<String> allowedConcreteTypes,
            Set<String> normalizedSinks,
            ClosedWorldClassIndex classIndex,
            StageTraceResult out
    ) {
        for (String rawRf : node.readFields()) {
            String rawOwner = extractOwnerClass(rawRf);
            if (!rawOwner.isEmpty() && !isBootstrapJdkOrLoggingClass(rawOwner)) {
                requireStageType(
                        callerSym,
                        normalizeRuntimeSymbol(rawRf),
                        rawOwner,
                        EdgeKind.FIELD_DATAFLOW,
                        "field-read-owner",
                        "",
                        classIndex,
                        out
                );
                ParsedClass ownerPc = classIndex.loadedClassOrNull(rawOwner);
                if (!isWholeClassBoundarySinkOwner(ownerPc, normalizedSinks)
                        && (rawOwner.equals(node.ownerClass())
                                || isStaticFunctionalOrSamFieldRead(rawRf, ownerPc, classIndex)
                                || (!rawRf.startsWith("GETTER:")
                                        && ownerPc != null
                                        && !rawOwner.startsWith("net.minecraft.")
                                        && !rawOwner.startsWith("ca.spottedleaf.")
                                        && !rawOwner.startsWith("org.bukkit.")
                                        && !rawOwner.startsWith("io.papermc.")))) {
                    out.stageReferencedOwners.add(rawOwner);
                    if (ownerPc != null) {
                        verifyClinitReferencedTypes(
                                callerSym,
                                ownerPc,
                                hasConcreteScopeFilter,
                                allowedConcreteTypes,
                                classIndex,
                                out
                        );
                    }
                }
            }
            String canonRf = resolveFieldOrGetterTarget(rawRf, classIndex);
            String fOwner = extractOwnerClass(canonRf);
            if (out.expandedCarrierClasses.contains(fOwner)) {
                out.readCarrierFields.add(canonRf);
                out.fieldReaders.computeIfAbsent(canonRf, k -> new LinkedHashSet<>()).add(callerSym);
                out.addEdge(canonRf, callerSym, EdgeKind.FIELD_DATAFLOW, "carrier-read");
            }
        }

        boolean methodAbortedAnalysis = node.dynamicFunctionalDispatches()
                .contains("UNRESOLVED_CALLBACK:" + callerSym);

        for (String rawWf : combineMutatedFields(node)) {
            if (rawWf.startsWith("UNKNOWN_ARRAY:") || rawWf.startsWith("UNKNOWN_MUTATION:")) {
                if (methodAbortedAnalysis
                        || (!out.expandedCarrierClasses.isEmpty()
                                && methodMayTouchCarrierState(callerSym, node, out.expandedCarrierClasses, out.rootSymbols))) {
                    String detail = rawWf.startsWith("UNKNOWN_MUTATION:")
                            ? "unknown-container-mutation"
                            : (methodAbortedAnalysis ? "interpreter-aborted-field-mutation" : "unknown-array-write");
                    out.mutatedCarrierFields.add(rawWf);
                    out.fieldWriters.computeIfAbsent(rawWf, k -> new LinkedHashSet<>()).add(callerSym);
                    out.addEdge(callerSym, rawWf, EdgeKind.FIELD_DATAFLOW, detail);
                }
                continue;
            }
            if (!rawWf.startsWith("GETTER:")) {
                String rawWriteOwner = extractOwnerClass(rawWf);
                if (!rawWriteOwner.isEmpty() && !isBootstrapJdkOrLoggingClass(rawWriteOwner)) {
                    requireStageType(
                            callerSym,
                            normalizeRuntimeSymbol(rawWf),
                            rawWriteOwner,
                            EdgeKind.FIELD_DATAFLOW,
                            "field-write-owner",
                            "",
                            classIndex,
                            out
                    );
                    ParsedClass writeOwnerPc = classIndex.loadedClassOrNull(rawWriteOwner);
                    if (!isWholeClassBoundarySinkOwner(writeOwnerPc, normalizedSinks)
                            && isStaticFunctionalOrSamFieldRead(rawWf, writeOwnerPc, classIndex)) {
                        out.stageReferencedOwners.add(rawWriteOwner);
                    }
                }
            }
            String canonWf = resolveFieldOrGetterTarget(rawWf, classIndex);
            if (canonWf.startsWith("GETTER:")) {
                String getterTarget = canonWf.substring("GETTER:".length());
                if (!out.expandedCarrierClasses.isEmpty()
                        && getterMayAliasCarrierState(getterTarget, out.expandedCarrierClasses, classIndex)) {
                    String opaqueSym = "UNKNOWN_ARRAY:" + callerSym + "->" + getterTarget;
                    out.mutatedCarrierFields.add(opaqueSym);
                    out.fieldWriters.computeIfAbsent(opaqueSym, k -> new LinkedHashSet<>()).add(callerSym);
                    out.addEdge(callerSym, opaqueSym, EdgeKind.FIELD_DATAFLOW, "opaque-getter-array-write");
                }
                continue;
            }
            String fOwner = extractOwnerClass(canonWf);
            if (out.expandedCarrierClasses.contains(fOwner)) {
                out.mutatedCarrierFields.add(canonWf);
                out.fieldWriters.computeIfAbsent(canonWf, k -> new LinkedHashSet<>()).add(callerSym);
                out.addEdge(callerSym, canonWf, EdgeKind.FIELD_DATAFLOW, "carrier-write");
            }
        }
    }

    private static boolean isStaticFunctionalOrSamFieldRead(
            String rawFieldSymbol,
            ParsedClass ownerPc,
            ClosedWorldClassIndex classIndex
    ) {
        if (ownerPc == null || rawFieldSymbol == null || rawFieldSymbol.startsWith("GETTER:")) {
            return false;
        }
        String fieldName = extractMemberName(rawFieldSymbol);
        FieldDescriptorMeta meta = ownerPc.declaredFieldMeta().get(fieldName);
        if (meta == null || !meta.isStatic()) {
            return false;
        }
        String desc = meta.descriptor();
        if (desc == null || !desc.startsWith("L") || !desc.endsWith(";")) {
            return false;
        }
        String fieldType = desc.substring(1, desc.length() - 1).replace('/', '.');
        if (BytecodeCausalGraphScanner.samMethodForJdkFunctionalInterface(fieldType) != null) {
            return true;
        }
        return !isBootstrapJdkOrLoggingClass(fieldType)
                && !isNonWorldgenInfrastructureClass(fieldType)
                && classIndex.isSamType(fieldType);
    }

    private static boolean isWholeClassBoundarySinkOwner(ParsedClass ownerPc, Set<String> normalizedSinks) {
        if (ownerPc == null || normalizedSinks == null || normalizedSinks.isEmpty()) {
            return false;
        }
        String owner = ownerPc.binaryClassName();
        boolean sawCallable = false;
        for (MethodNode m : ownerPc.methods().values()) {
            String name = m.name();
            if ("<clinit>".equals(name)
                    || "<init>".equals(name)
                    || (m.accessFlags() & BytecodeCausalGraphScanner.ACC_SYNTHETIC) != 0
                    || isStandardJavaObjectOverride(name, m.descriptor())) {
                continue;
            }
            sawCallable = true;
            if (!normalizedSinks.contains(owner + "#" + name)) {
                return false;
            }
        }
        return sawCallable;
    }

    private static boolean methodMayTouchCarrierState(
            String callerSym,
            MethodNode node,
            Set<String> expandedCarrierClasses,
            List<String> rootSymbols
    ) {
        if (expandedCarrierClasses.contains(node.ownerClass()) || rootSymbols.contains(callerSym)) {
            return true;
        }
        if (descriptorMentionsAnyClass(node.descriptor(), expandedCarrierClasses)) {
            return true;
        }
        for (String rf : node.readFields()) {
            if (expandedCarrierClasses.contains(extractOwnerClass(rf))) {
                return true;
            }
        }
        for (String wf : node.writtenFields()) {
            if (expandedCarrierClasses.contains(extractOwnerClass(wf))) {
                return true;
            }
        }
        return false;
    }

    private static boolean getterMayAliasCarrierState(
            String getterTarget,
            Set<String> expandedCarrierClasses,
            ClosedWorldClassIndex classIndex
    ) {
        String getterOwner = extractOwnerClass(getterTarget);
        String getterName = extractMemberName(getterTarget);
        if (getterOwner.isEmpty() || expandedCarrierClasses.contains(getterOwner)) {
            return true;
        }
        if (isBootstrapJdkOrLoggingClass(getterOwner) || isNonWorldgenInfrastructureClass(getterOwner)) {
            return false;
        }
        ParsedClass ownerPc = classIndex.loadedClassOrNull(getterOwner);
        if (ownerPc == null) {
            return true;
        }
        for (MethodNode m : ownerPc.methods().values()) {
            if (!getterName.equals(m.name())) {
                continue;
            }
            if (descriptorMentionsAnyClass(m.descriptor(), expandedCarrierClasses)) {
                return true;
            }
            for (String rf : m.readFields()) {
                if (expandedCarrierClasses.contains(extractOwnerClass(rf))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean descriptorMentionsAnyClass(String descriptor, Set<String> candidateClasses) {
        if (descriptor == null || descriptor.isEmpty() || candidateClasses.isEmpty()) {
            return false;
        }
        int idx = 0;
        while (idx < descriptor.length()) {
            int lPos = descriptor.indexOf('L', idx);
            if (lPos < 0) {
                break;
            }
            int semi = descriptor.indexOf(';', lPos + 1);
            if (semi < 0) {
                break;
            }
            String cls = descriptor.substring(lPos + 1, semi).replace('/', '.');
            if (candidateClasses.contains(cls)) {
                return true;
            }
            idx = semi + 1;
        }
        return false;
    }

    private static void verifyClinitReferencedTypes(
            String triggerSym,
            ParsedClass ownerPc,
            boolean hasConcreteScopeFilter,
            Set<String> allowedConcreteTypes,
            ClosedWorldClassIndex classIndex,
            StageTraceResult out
    ) {
        if (!out.verifiedClinitOwners.add(ownerPc.binaryClassName())) {
            return;
        }
        MethodNode clinit = ownerPc.methods().get("<clinit>");
        if (clinit == null) {
            clinit = ownerPc.methods().get("<clinit>()V");
        }
        if (clinit == null) {
            return;
        }
        String clinitSym = clinit.symbol();
        if (hasConcreteScopeFilter) {
            registerInstantiatedConcreteTypes(clinit, true, allowedConcreteTypes, classIndex);
        }
        for (String instCls : clinit.instantiatedClasses()) {
            requireStageType(
                    triggerSym,
                    unwrapElementClassIfArray(instCls) + "#<init>",
                    instCls,
                    EdgeKind.DIRECT_CALL,
                    "clinit-constructor",
                    "",
                    classIndex,
                    out
            );
        }
        for (String calledRef : clinit.calledMethods()) {
            String canon = stripMethodDescriptor(calledRef);
            requireStageType(
                    triggerSym,
                    canon,
                    extractOwnerClass(canon),
                    EdgeKind.DIRECT_CALL,
                    "clinit-call",
                    "",
                    classIndex,
                    out
            );
        }
        for (String lambdaRef : clinit.spawnedLambdas()) {
            String canon = stripMethodDescriptor(lambdaRef);
            requireStageType(
                    triggerSym,
                    canon,
                    extractOwnerClass(canon),
                    EdgeKind.INDY_LAMBDA,
                    "clinit-lambda",
                    "",
                    classIndex,
                    out
            );
        }
        for (String rf : clinit.readFields()) {
            String rfOwner = extractOwnerClass(rf);
            if (requireStageType(
                    triggerSym,
                    normalizeRuntimeSymbol(rf),
                    rfOwner,
                    EdgeKind.FIELD_DATAFLOW,
                    "clinit-field-read:" + clinitSym,
                    "",
                    classIndex,
                    out
            ) && !rfOwner.startsWith("net.minecraft.")) {
                ParsedClass nextPc = classIndex.loadedClassOrNull(rfOwner);
                if (nextPc != null) {
                    out.stageReferencedOwners.add(rfOwner);
                    verifyClinitReferencedTypes(
                            triggerSym,
                            nextPc,
                            hasConcreteScopeFilter,
                            allowedConcreteTypes,
                            classIndex,
                            out
                    );
                }
            }
        }
    }

    private static Set<String> combineMutatedFields(MethodNode node) {
        Set<String> all = new LinkedHashSet<>(node.writtenFields());
        all.addAll(node.producedQueueFields());
        all.addAll(node.collectionAddedFields());
        all.addAll(node.collectionRemovedFields());
        all.addAll(node.arrayMutatedFields());
        all.addAll(node.receiverMutatedFields());
        return all;
    }

    private static String resolveFieldOrGetterTarget(String rawFieldOrGetter, ClosedWorldClassIndex classIndex) {
        if (rawFieldOrGetter.startsWith("GETTER:")) {
            String getterRef = rawFieldOrGetter.substring("GETTER:".length());
            for (MethodNode getterNode : classIndex.resolveMethodsForInvocation(getterRef)) {
                if (!getterNode.returnedField().isEmpty()) {
                    return classIndex.canonicalizeFieldSymbol(getterNode.returnedField());
                }
            }
            return rawFieldOrGetter;
        }
        return classIndex.canonicalizeFieldSymbol(rawFieldOrGetter);
    }

    private static boolean enqueueTargetMethod(
            String fromSym,
            String targetSym,
            MethodNode targetNode,
            EdgeKind kind,
            String detail,
            int nextDepth,
            Set<String> normalizedSinks,
            Set<String> expandedNodeKeys,
            Set<String> countedMethodSymbols,
            int maxHopsPerStage,
            int maxNodesPerStage,
            Deque<WorkItem> queue,
            StageTraceResult out
    ) {
        out.addEdge(fromSym, targetSym, kind, detail);
        String visitKey = targetNode.ownerClass() + "#" + targetNode.name() + targetNode.descriptor();

        if (nextDepth > maxHopsPerStage) {
            if (!expandedNodeKeys.contains(visitKey) && !normalizedSinks.contains(targetSym)) {
                out.truncatedByBounds = true;
                out.unexpandedFrontierSymbols.add(targetSym);
            }
            return false;
        }
        if (!countedMethodSymbols.contains(targetSym) && countedMethodSymbols.size() >= maxNodesPerStage) {
            out.truncatedByBounds = true;
            out.unexpandedFrontierSymbols.add(targetSym);
            return false;
        }

        out.reachableMethods.add(targetSym);
        if (!isAbstractOrInterfaceNoBody(targetNode)) {
            countedMethodSymbols.add(targetSym);
        }
        if (expandedNodeKeys.add(visitKey)) {
            queue.add(new WorkItem(targetSym, targetNode, nextDepth));
            return true;
        }
        return false;
    }

    private static void enqueueUnresolvedSymbol(
            String fromSym,
            String targetSym,
            EdgeKind kind,
            String detail,
            int nextDepth,
            Set<String> countedMethodSymbols,
            int maxHopsPerStage,
            int maxNodesPerStage,
            StageTraceResult out
    ) {
        out.addEdge(fromSym, targetSym, kind, detail);
        if (nextDepth > maxHopsPerStage || (!countedMethodSymbols.contains(targetSym)
                && countedMethodSymbols.size() >= maxNodesPerStage)) {
            out.truncatedByBounds = true;
            out.unexpandedFrontierSymbols.add(targetSym);
            return;
        }
        out.reachableMethods.add(targetSym);
        countedMethodSymbols.add(targetSym);
        if (nextDepth > out.maxDepthReached) {
            out.maxDepthReached = nextDepth;
        }
    }

    // =========================================================================
    // Ledger Reconciliation Implementation
    // =========================================================================

    private static Map<String, Set<String>> collectObservationAndGraphErrors(
            Map<String, StageCoverageGraph> graphs,
            Map<String, StageRuntimeObservation> runtimeMap
    ) {
        Map<String, Set<String>> errors = new LinkedHashMap<>();
        for (Map.Entry<String, StageCoverageGraph> e : graphs.entrySet()) {
            validateStaticGraphEntry(e.getKey(), e.getValue(), errors);
        }
        for (Map.Entry<String, StageRuntimeObservation> e : runtimeMap.entrySet()) {
            validateRuntimeObservationEntry(e.getKey(), e.getValue(), errors);
        }
        return errors;
    }

    private static void validateStaticGraphEntry(
            String key,
            StageCoverageGraph graph,
            Map<String, Set<String>> errors
    ) {
        if (key == null || key.isBlank()) {
            LOGGER.log(Level.WARNING, "[PipelineCoverageGraphScanner] Ignoring blank staticGraphs stage key");
            return;
        }
        if (graph == null) {
            LOGGER.log(Level.WARNING, "[PipelineCoverageGraphScanner] Null StageCoverageGraph for stage " + key);
            recordStageValidationMarker(errors, key, "<null-stage-graph:" + key + ">");
            return;
        }
        if (!key.equals(graph.stageId())) {
            String marker = "<mismatched-stage-graph-id:" + key + "!=" + graph.stageId() + ">";
            LOGGER.log(Level.WARNING, "[PipelineCoverageGraphScanner] Mismatched StageCoverageGraph stageId: " + marker);
            recordStageValidationMarker(errors, key, marker);
            if (!graph.stageId().isBlank()) {
                recordStageValidationMarker(errors, graph.stageId(), marker);
            }
        }
    }

    private static void validateRuntimeObservationEntry(
            String key,
            StageRuntimeObservation obs,
            Map<String, Set<String>> errors
    ) {
        if (key == null || key.isBlank()) {
            LOGGER.log(Level.WARNING, "[PipelineCoverageGraphScanner] Ignoring blank runtimeObservations stage key");
            return;
        }
        if (obs == null) {
            LOGGER.log(Level.WARNING, "[PipelineCoverageGraphScanner] Null StageRuntimeObservation for stage " + key);
            recordStageValidationMarker(errors, key, "<null-runtime-observation:" + key + ">");
            return;
        }
        if (!key.equals(obs.stageId())) {
            String marker = "<mismatched-runtime-observation-stage-id:" + key + "!=" + obs.stageId() + ">";
            LOGGER.log(Level.WARNING, "[PipelineCoverageGraphScanner] Mismatched StageRuntimeObservation stageId: " + marker);
            recordStageValidationMarker(errors, key, marker);
            if (!obs.stageId().isBlank()) {
                recordStageValidationMarker(errors, obs.stageId(), marker);
            }
        }
        if (!hasValidExecutedMethodSymbol(obs)) {
            LOGGER.log(
                    Level.WARNING,
                    "[PipelineCoverageGraphScanner] Empty StageRuntimeObservation executedMethodSymbols for stage " + key
            );
            recordStageValidationMarker(errors, key, "<empty-runtime-observation:" + key + ">");
        }
        String errToken = firstObservationErrorToken(obs);
        if (errToken != null) {
            LOGGER.log(
                    Level.WARNING,
                    "[PipelineCoverageGraphScanner] Error marker in StageRuntimeObservation for stage " + key + ": " + errToken
            );
            recordStageValidationMarker(errors, key, "<runtime-observation-error:" + key + ">");
        }
    }

    private static void recordStageValidationMarker(
            Map<String, Set<String>> errors,
            String stageId,
            String marker
    ) {
        errors.computeIfAbsent(stageId, k -> new LinkedHashSet<>()).add(marker);
    }

    private static boolean hasValidExecutedMethodSymbol(StageRuntimeObservation obs) {
        for (String sym : obs.executedMethodSymbols()) {
            if (!isObservationErrorToken(sym) && !normalizeRuntimeSymbol(sym).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the first error/fault marker present in any of the observation's symbol sets, or
     * {@code null} when clean.
     */
    public static String firstObservationErrorToken(StageRuntimeObservation obs) {
        if (obs == null) {
            return "<null-observation>";
        }
        for (Set<String> bucket : List.of(
                obs.executedMethodSymbols(),
                obs.mutatedCarrierFields(),
                obs.readCarrierFields(),
                obs.observedAsyncThreadsOrExecutors()
        )) {
            for (String token : bucket) {
                if (isObservationErrorToken(token)) {
                    return token == null ? "<null-token>" : token.trim();
                }
            }
        }
        return null;
    }

    /**
     * Returns {@code true} when {@code rawToken} is blank or carries a synthetic fault/truncation
     * marker ({@code <...>}, {@code ERROR:...}, {@code CANARY_ERROR:...}, {@code TIMEOUT:...},
     * {@code EXCEPTION:...}).
     */
    public static boolean isObservationErrorToken(String rawToken) {
        if (rawToken == null) {
            return true;
        }
        String t = rawToken.trim();
        return t.isEmpty()
                || t.startsWith("<")
                || t.startsWith("ERROR:")
                || t.startsWith("CANARY_ERROR:")
                || t.startsWith("TIMEOUT:")
                || t.startsWith("EXCEPTION:");
    }

    /**
     * Returns {@code true} only when {@code rawSymbol} normalizes to verified non-mutating
     * bootstrap JDK/logging plumbing or a benign JDK worker executor identifier.
     */
    public static boolean isVerifiedBenignInfrastructureSymbol(String rawSymbol) {
        String norm = normalizeRuntimeSymbol(rawSymbol);
        if (norm.isEmpty() || norm.startsWith("STAGE:")) {
            return false;
        }
        int colon = norm.indexOf(':');
        if (colon > 0 && norm.substring(0, colon).indexOf('.') < 0) {
            norm = norm.substring(colon + 1);
        }
        return isBenignInfrastructureSymbol(norm) || isBenignAsyncExecutorIdentifier(norm);
    }

    private static StageCoverageSlice reconcileSingleStage(
            String stageId,
            StageCoverageGraph graph,
            StageRuntimeObservation runtime,
            Set<String> validationErrorMarkers,
            Map<String, ProvenanceBinding> normalizedBindings,
            Set<String> responseRequiredCarrierFields,
            Set<String> globallyUnproducedResponseFields,
            Set<String> unaccountedResponseFieldsOut,
            ProvenanceEvaluationPolicy policy
    ) {
        ProvenanceBinding stageLevelBinding = resolveValidStageDelegationBinding(stageId, normalizedBindings);

        Map<String, ProvenanceBinding> methodProvenance = classifyStageMethods(
                stageId,
                graph,
                runtime,
                validationErrorMarkers,
                stageLevelBinding,
                normalizedBindings,
                policy
        );

        int accountedMethodCount = 0;
        List<String> unaccountedMethods = new ArrayList<>();
        for (Map.Entry<String, ProvenanceBinding> e : methodProvenance.entrySet()) {
            if (policy.isAccounted(e.getValue().kind())) {
                accountedMethodCount++;
            } else {
                unaccountedMethods.add(e.getKey());
            }
        }

        Map<String, ProvenanceBinding> fieldProvenance = classifyStageCarrierFields(
                stageId,
                graph,
                runtime,
                stageLevelBinding,
                normalizedBindings,
                responseRequiredCarrierFields,
                globallyUnproducedResponseFields
        );

        int accountedFieldCount = 0;
        List<String> unaccountedFields = new ArrayList<>();
        for (Map.Entry<String, ProvenanceBinding> e : fieldProvenance.entrySet()) {
            if (policy.isAccounted(e.getValue().kind())) {
                accountedFieldCount++;
            } else {
                unaccountedFields.add(e.getKey());
                if (responseRequiredCarrierFields.contains(e.getKey())) {
                    unaccountedResponseFieldsOut.add(e.getKey());
                }
            }
        }

        List<String> unaccountedAsyncHops = new ArrayList<>();
        Map<String, ProvenanceBinding> asyncHopProvenance = classifyStageAsyncHops(
                stageId,
                graph,
                runtime,
                stageLevelBinding,
                methodProvenance,
                normalizedBindings,
                unaccountedAsyncHops,
                policy
        );

        List<PipelineEdge> witnessEdges = buildWitnessPaths(
                stageId,
                graph,
                unaccountedMethods,
                unaccountedFields,
                unaccountedAsyncHops
        );

        boolean nativeDelegated = (stageLevelBinding != null
                && stageLevelBinding.kind() == CoverageProvenanceKind.NATIVE_DELEGATED)
                || containsNativeDelegated(methodProvenance.values())
                || containsNativeDelegated(fieldProvenance.values())
                || containsNativeDelegated(asyncHopProvenance.values());

        boolean patchEligible = !nativeDelegated
                && !graph.truncatedByBounds()
                && unaccountedMethods.isEmpty()
                && unaccountedFields.isEmpty()
                && unaccountedAsyncHops.isEmpty();

        StageCoverageVerdict verdict = new StageCoverageVerdict(
                stageId,
                patchEligible,
                accountedMethodCount,
                unaccountedMethods.size(),
                accountedFieldCount,
                unaccountedFields.size(),
                unaccountedMethods,
                unaccountedFields,
                unaccountedAsyncHops,
                witnessEdges,
                nativeDelegated
        );

        return new StageCoverageSlice(
                stageId,
                graph,
                runtime,
                methodProvenance,
                fieldProvenance,
                asyncHopProvenance,
                verdict
        );
    }

    private static Map<String, ProvenanceBinding> classifyStageMethods(
            String stageId,
            StageCoverageGraph graph,
            StageRuntimeObservation runtime,
            Set<String> validationErrorMarkers,
            ProvenanceBinding stageLevelBinding,
            Map<String, ProvenanceBinding> normalizedBindings,
            ProvenanceEvaluationPolicy policy
    ) {
        Set<String> candidateMethods = new LinkedHashSet<>(graph.reachableMethods());
        candidateMethods.addAll(graph.unexpandedFrontierSymbols());
        candidateMethods.addAll(validationErrorMarkers);
        for (String rtMethod : runtime.executedMethodSymbols()) {
            String norm = normalizeRuntimeSymbol(rtMethod);
            if (!norm.isEmpty()) {
                candidateMethods.add(norm);
            }
        }

        Map<String, ProvenanceBinding> methodProvenance = new LinkedHashMap<>();
        List<String> deferredProxies = new ArrayList<>();

        boolean incompleteClosedWorld = graph.hasIncompleteClosedWorld();
        for (String methodSym : candidateMethods) {
            if (validationErrorMarkers.contains(methodSym)) {
                methodProvenance.put(
                        methodSym,
                        new ProvenanceBinding(
                                methodSym,
                                CoverageProvenanceKind.UNACCOUNTED,
                                "invalid-runtime-or-graph-observation"
                        )
                );
                continue;
            }
            if (graph.unexpandedFrontierSymbols().contains(methodSym)) {
                methodProvenance.put(
                        methodSym,
                        classifyTruncatedFrontierMethod(stageId, methodSym, stageLevelBinding, normalizedBindings)
                );
                continue;
            }
            boolean isDispatchProxy = graph.abstractDispatchMethods().contains(methodSym)
                    || isJvmLambdaProxyFrame(methodSym);
            if (incompleteClosedWorld && isDispatchProxy) {
                if (stageLevelBinding != null && stageLevelBinding.kind() == CoverageProvenanceKind.NATIVE_DELEGATED) {
                    methodProvenance.put(methodSym, stageLevelBinding);
                } else {
                    deferredProxies.add(methodSym);
                }
                continue;
            }
            ProvenanceBinding explicit = lookupBinding(stageId, methodSym, normalizedBindings);
            if (explicit != null) {
                methodProvenance.put(methodSym, explicit);
            } else if (stageLevelBinding != null && stageLevelBinding.kind() == CoverageProvenanceKind.NATIVE_DELEGATED) {
                methodProvenance.put(methodSym, stageLevelBinding);
            } else if (isDispatchProxy) {
                deferredProxies.add(methodSym);
            } else if (isBenignInfrastructureSymbol(methodSym)) {
                methodProvenance.put(
                        methodSym,
                        new ProvenanceBinding(
                                methodSym,
                                CoverageProvenanceKind.BENIGN_INFRASTRUCTURE,
                                "jdk-or-logging-plumbing"
                        )
                );
            } else {
                methodProvenance.put(
                        methodSym,
                        new ProvenanceBinding(methodSym, CoverageProvenanceKind.UNACCOUNTED, "unmapped-method")
                );
            }
        }

        resolveDeferredProxyMethods(deferredProxies, graph, candidateMethods, methodProvenance, policy);
        return methodProvenance;
    }

    private static ProvenanceBinding resolveValidStageDelegationBinding(
            String stageId,
            Map<String, ProvenanceBinding> normalizedBindings
    ) {
        ProvenanceBinding raw = normalizedBindings.get("STAGE:" + stageId);
        if (raw == null) {
            return null;
        }
        if (raw.kind() == CoverageProvenanceKind.NATIVE_DELEGATED) {
            return raw;
        }
        LOGGER.log(
                Level.WARNING,
                "[PipelineCoverageGraphScanner] Rejecting non-NATIVE_DELEGATED stage-wide provenance binding STAGE:"
                        + stageId + " -> " + raw.kind() + " (" + raw.mechanismRef()
                        + "); IR_LIFTED, STAMP_ATLAS, and BENIGN_INFRASTRUCTURE must bind per symbol or field."
        );
        return null;
    }

    private static boolean containsNativeDelegated(Collection<ProvenanceBinding> bindings) {
        for (ProvenanceBinding b : bindings) {
            if (b != null && b.kind() == CoverageProvenanceKind.NATIVE_DELEGATED) {
                return true;
            }
        }
        return false;
    }

    private static ProvenanceBinding classifyTruncatedFrontierMethod(
            String stageId,
            String methodSym,
            ProvenanceBinding stageLevelBinding,
            Map<String, ProvenanceBinding> normalizedBindings
    ) {
        if (stageLevelBinding != null && stageLevelBinding.kind() == CoverageProvenanceKind.NATIVE_DELEGATED) {
            return stageLevelBinding;
        }
        // Unexpanded frontier symbols never had their transitive callees or field mutations scanned,
        // so non-delegated stages must always treat them as UNACCOUNTED regardless of per-symbol bindings.
        return new ProvenanceBinding(methodSym, CoverageProvenanceKind.UNACCOUNTED, "truncated-at-traversal-bound");
    }

    private static void resolveDeferredProxyMethods(
            List<String> deferredProxies,
            StageCoverageGraph graph,
            Set<String> candidateMethods,
            Map<String, ProvenanceBinding> methodProvenance,
            ProvenanceEvaluationPolicy policy
    ) {
        boolean incompleteClosedWorld = graph.hasIncompleteClosedWorld();
        for (String proxySym : deferredProxies) {
            if (incompleteClosedWorld) {
                methodProvenance.put(
                        proxySym,
                        new ProvenanceBinding(
                                proxySym,
                                CoverageProvenanceKind.UNACCOUNTED,
                                "incomplete-closed-world-hierarchy"
                        )
                );
                continue;
            }
            if (isJvmLambdaProxyFrame(proxySym)) {
                String enclosingOwner = proxySym.substring(0, proxySym.indexOf("$$Lambda"));
                List<String> lambdaTargets = findLambdaTargetsForEnclosingOwner(enclosingOwner, graph, candidateMethods);
                boolean accounted = areAllTargetsAccounted(lambdaTargets, methodProvenance, policy);
                methodProvenance.put(
                        proxySym,
                        new ProvenanceBinding(
                                proxySym,
                                accounted
                                        ? CoverageProvenanceKind.BENIGN_INFRASTRUCTURE
                                        : CoverageProvenanceKind.UNACCOUNTED,
                                accounted ? "lambda-metafactory-proxy" : "unaccounted-lambda-proxy"
                        )
                );
            } else {
                Set<String> subTargets = graph.resolvedSubtypeTargets().getOrDefault(proxySym, Set.of());
                boolean accounted = areAllTargetsAccounted(subTargets, methodProvenance, policy);
                boolean isJdkFn = BytecodeCausalGraphScanner
                        .samMethodForJdkFunctionalInterface(extractOwnerClass(proxySym)) != null;
                String reason = accounted
                        ? "resolved-polymorphic-dispatch"
                        : (subTargets.isEmpty()
                                ? (isJdkFn
                                        ? "unresolved-functional-dispatch"
                                        : "unresolved-abstract-or-interface-dispatch")
                                : "subtype-target-unaccounted");
                methodProvenance.put(
                        proxySym,
                        new ProvenanceBinding(
                                proxySym,
                                accounted
                                        ? CoverageProvenanceKind.BENIGN_INFRASTRUCTURE
                                        : CoverageProvenanceKind.UNACCOUNTED,
                                reason
                        )
                );
            }
        }
    }

    private static boolean areAllTargetsAccounted(
            Collection<String> targets,
            Map<String, ProvenanceBinding> methodProvenance,
            ProvenanceEvaluationPolicy policy
    ) {
        if (targets.isEmpty()) {
            return false;
        }
        for (String t : targets) {
            ProvenanceBinding b = methodProvenance.get(t);
            if (b == null || !policy.isAccounted(b.kind())) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, ProvenanceBinding> classifyStageCarrierFields(
            String stageId,
            StageCoverageGraph graph,
            StageRuntimeObservation runtime,
            ProvenanceBinding stageLevelBinding,
            Map<String, ProvenanceBinding> normalizedBindings,
            Set<String> responseRequiredCarrierFields,
            Set<String> globallyUnproducedResponseFields
    ) {
        Set<String> candidateFields = new LinkedHashSet<>();
        for (String mf : graph.mutatedCarrierFields()) {
            candidateFields.add(normalizeRuntimeSymbol(mf));
        }
        for (String rmf : runtime.mutatedCarrierFields()) {
            String norm = normalizeRuntimeSymbol(rmf);
            if (!norm.isEmpty()) {
                candidateFields.add(norm);
            }
        }

        boolean stageDelegated = stageLevelBinding != null
                && stageLevelBinding.kind() == CoverageProvenanceKind.NATIVE_DELEGATED;
        if (!stageDelegated) {
            for (String unproducedReq : globallyUnproducedResponseFields) {
                String reqOwner = extractOwnerClass(unproducedReq);
                if (graph.expandedStateCarrierClasses().contains(reqOwner)
                        || graph.readCarrierFields().contains(unproducedReq)
                        || runtime.readCarrierFields().contains(unproducedReq)) {
                    candidateFields.add(unproducedReq);
                }
            }
        }

        Map<String, ProvenanceBinding> fieldProvenance = new LinkedHashMap<>();
        for (String fieldSym : candidateFields) {
            boolean requiredByResponse = responseRequiredCarrierFields.contains(fieldSym);
            ProvenanceBinding explicit = lookupBinding(stageId, fieldSym, normalizedBindings);
            fieldProvenance.put(
                    fieldSym,
                    resolveSingleFieldProvenance(fieldSym, requiredByResponse, explicit, stageLevelBinding)
            );
        }
        return fieldProvenance;
    }

    private static ProvenanceBinding resolveSingleFieldProvenance(
            String fieldSym,
            boolean requiredByResponse,
            ProvenanceBinding explicit,
            ProvenanceBinding stageLevelBinding
    ) {
        if (explicit != null) {
            if (requiredByResponse && explicit.kind() == CoverageProvenanceKind.BENIGN_INFRASTRUCTURE) {
                return new ProvenanceBinding(
                        fieldSym,
                        CoverageProvenanceKind.UNACCOUNTED,
                        "response-required-field-cannot-be-benign"
                );
            }
            return explicit;
        }
        if (stageLevelBinding != null && stageLevelBinding.kind() == CoverageProvenanceKind.NATIVE_DELEGATED) {
            return stageLevelBinding;
        }
        return new ProvenanceBinding(
                fieldSym,
                CoverageProvenanceKind.UNACCOUNTED,
                requiredByResponse ? "unaccounted-response-required-carrier-field" : "unmapped-carrier-field"
        );
    }

    private static Map<String, ProvenanceBinding> classifyStageAsyncHops(
            String stageId,
            StageCoverageGraph graph,
            StageRuntimeObservation runtime,
            ProvenanceBinding stageLevelBinding,
            Map<String, ProvenanceBinding> methodProvenance,
            Map<String, ProvenanceBinding> normalizedBindings,
            List<String> unaccountedAsyncHopsOut,
            ProvenanceEvaluationPolicy policy
    ) {
        Map<String, ProvenanceBinding> asyncHopProvenance = new LinkedHashMap<>();

        for (String asyncTarget : graph.scheduledAsyncTargets()) {
            String normTarget = normalizeRuntimeSymbol(asyncTarget);
            ProvenanceBinding resolved = resolveStaticAsyncTargetProvenance(
                    stageId,
                    normTarget,
                    stageLevelBinding,
                    methodProvenance,
                    normalizedBindings
            );
            asyncHopProvenance.put(normTarget, resolved);
            if (!policy.isAccounted(resolved.kind()) && !unaccountedAsyncHopsOut.contains(normTarget)) {
                unaccountedAsyncHopsOut.add(normTarget);
            }
        }

        for (String runtimeAsync : runtime.observedAsyncThreadsOrExecutors()) {
            String normAsync = normalizeRuntimeSymbol(runtimeAsync);
            if (normAsync.isEmpty()) {
                continue;
            }
            ProvenanceBinding resolved = resolveRuntimeAsyncExecutorProvenance(
                    stageId,
                    normAsync,
                    stageLevelBinding,
                    normalizedBindings
            );
            asyncHopProvenance.put(normAsync, resolved);
            if (!policy.isAccounted(resolved.kind()) && !unaccountedAsyncHopsOut.contains(normAsync)) {
                unaccountedAsyncHopsOut.add(normAsync);
            }
        }
        return asyncHopProvenance;
    }

    private static ProvenanceBinding resolveStaticAsyncTargetProvenance(
            String stageId,
            String normTarget,
            ProvenanceBinding stageLevelBinding,
            Map<String, ProvenanceBinding> methodProvenance,
            Map<String, ProvenanceBinding> normalizedBindings
    ) {
        ProvenanceBinding methodBinding = methodProvenance.get(normTarget);
        if (methodBinding != null) {
            return methodBinding;
        }
        ProvenanceBinding explicit = lookupBinding(stageId, normTarget, normalizedBindings);
        if (explicit != null) {
            return explicit;
        }
        if (stageLevelBinding != null && stageLevelBinding.kind() == CoverageProvenanceKind.NATIVE_DELEGATED) {
            return stageLevelBinding;
        }
        return new ProvenanceBinding(normTarget, CoverageProvenanceKind.UNACCOUNTED, "unmapped-async-target");
    }

    private static ProvenanceBinding resolveRuntimeAsyncExecutorProvenance(
            String stageId,
            String normAsync,
            ProvenanceBinding stageLevelBinding,
            Map<String, ProvenanceBinding> normalizedBindings
    ) {
        ProvenanceBinding explicit = lookupBinding(stageId, normAsync, normalizedBindings);
        if (explicit != null) {
            return explicit;
        }
        if (stageLevelBinding != null && stageLevelBinding.kind() == CoverageProvenanceKind.NATIVE_DELEGATED) {
            return stageLevelBinding;
        }
        if (isBenignAsyncExecutorIdentifier(normAsync)) {
            return new ProvenanceBinding(
                    normAsync,
                    CoverageProvenanceKind.BENIGN_INFRASTRUCTURE,
                    "jdk-executor-worker"
            );
        }
        return new ProvenanceBinding(
                normAsync,
                CoverageProvenanceKind.UNACCOUNTED,
                "unmapped-runtime-async-executor"
        );
    }

    private static List<PipelineEdge> buildWitnessPaths(
            String stageId,
            StageCoverageGraph graph,
            List<String> unaccountedMethods,
            List<String> unaccountedFields,
            List<String> unaccountedAsyncHops
    ) {
        if (unaccountedMethods.isEmpty() && unaccountedFields.isEmpty() && unaccountedAsyncHops.isEmpty()) {
            return List.of();
        }

        Map<String, PipelineEdge> parentEdgeBySymbol = computeShortestParentEdges(graph);
        Map<String, PipelineEdge> firstIncomingEdgeByTarget = new LinkedHashMap<>();
        for (PipelineEdge e : graph.edges()) {
            firstIncomingEdgeByTarget.putIfAbsent(e.toSymbol(), e);
        }
        Set<PipelineEdge> witnessOut = new LinkedHashSet<>();
        String fallbackRoot = graph.rootMethodSymbols().isEmpty()
                ? ("STAGE:" + stageId)
                : graph.rootMethodSymbols().get(0);

        Set<String> rootMethodSet = new LinkedHashSet<>(graph.rootMethodSymbols());
        Set<String> unaccountedFieldSet = new LinkedHashSet<>(unaccountedFields);
        Set<String> unaccountedAsyncSet = new LinkedHashSet<>(unaccountedAsyncHops);

        Set<String> targets = new LinkedHashSet<>();
        targets.addAll(unaccountedMethods);
        targets.addAll(unaccountedFields);
        targets.addAll(unaccountedAsyncHops);

        for (String target : targets) {
            appendWitnessForTarget(
                    stageId,
                    target,
                    fallbackRoot,
                    rootMethodSet,
                    parentEdgeBySymbol,
                    firstIncomingEdgeByTarget,
                    unaccountedFieldSet,
                    unaccountedAsyncSet,
                    witnessOut
            );
        }
        return List.copyOf(witnessOut);
    }

    private static Map<String, PipelineEdge> computeShortestParentEdges(StageCoverageGraph graph) {
        Map<String, List<PipelineEdge>> adj = new LinkedHashMap<>();
        for (PipelineEdge edge : graph.edges()) {
            adj.computeIfAbsent(edge.fromSymbol(), k -> new ArrayList<>()).add(edge);
        }
        Map<String, PipelineEdge> parentEdgeBySymbol = new LinkedHashMap<>();
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> bfs = new ArrayDeque<>();
        for (String root : graph.rootMethodSymbols()) {
            if (visited.add(root)) {
                bfs.add(root);
            }
        }
        while (!bfs.isEmpty()) {
            String cur = bfs.poll();
            for (PipelineEdge outEdge : adj.getOrDefault(cur, List.of())) {
                if (visited.add(outEdge.toSymbol())) {
                    parentEdgeBySymbol.put(outEdge.toSymbol(), outEdge);
                    bfs.add(outEdge.toSymbol());
                }
            }
        }
        return parentEdgeBySymbol;
    }

    private static void appendWitnessForTarget(
            String stageId,
            String target,
            String fallbackRoot,
            Set<String> rootMethodSet,
            Map<String, PipelineEdge> parentEdgeBySymbol,
            Map<String, PipelineEdge> firstIncomingEdgeByTarget,
            Set<String> unaccountedFieldSet,
            Set<String> unaccountedAsyncSet,
            Set<PipelineEdge> witnessOut
    ) {
        if (rootMethodSet.contains(target)) {
            witnessOut.add(
                    new PipelineEdge("STAGE:" + stageId, target, EdgeKind.DIRECT_CALL, "unaccounted-stage-root")
            );
            return;
        }
        if (!parentEdgeBySymbol.containsKey(target)) {
            PipelineEdge directMatch = firstIncomingEdgeByTarget.get(target);
            if (directMatch != null) {
                witnessOut.add(directMatch);
            } else {
                EdgeKind synthKind = unaccountedFieldSet.contains(target)
                        ? EdgeKind.FIELD_DATAFLOW
                        : (unaccountedAsyncSet.contains(target)
                                ? EdgeKind.ASYNC_FUTURE_OR_SCHEDULER
                                : EdgeKind.DIRECT_CALL);
                witnessOut.add(
                        new PipelineEdge(fallbackRoot, target, synthKind, "runtime-canary-observed")
                );
            }
            return;
        }

        List<PipelineEdge> chain = new ArrayList<>();
        Set<String> guard = new LinkedHashSet<>();
        String cursor = target;
        while (cursor != null && guard.add(cursor)) {
            PipelineEdge pe = parentEdgeBySymbol.get(cursor);
            if (pe == null) {
                break;
            }
            chain.add(pe);
            cursor = pe.fromSymbol();
        }
        Collections.reverse(chain);
        witnessOut.addAll(chain);
    }

    private static PipelineEdge findDirectIncomingEdge(List<PipelineEdge> edges, String target) {
        for (PipelineEdge e : edges) {
            if (e.toSymbol().equals(target)) {
                return e;
            }
        }
        return null;
    }

    private static List<String> findLambdaTargetsForEnclosingOwner(
            String enclosingOwner,
            StageCoverageGraph graph,
            Set<String> candidateMethods
    ) {
        List<String> matches = new ArrayList<>();
        String ownerPrefix = enclosingOwner + "#";
        for (PipelineEdge edge : graph.edges()) {
            if (edge.kind() == EdgeKind.INDY_LAMBDA && edge.fromSymbol().startsWith(ownerPrefix)) {
                matches.add(edge.toSymbol());
            }
        }
        String synthPrefix = enclosingOwner + "#lambda$";
        for (String cand : candidateMethods) {
            if (cand.startsWith(synthPrefix) && !matches.contains(cand)) {
                matches.add(cand);
            }
        }
        return matches;
    }

    private static ProvenanceBinding lookupBinding(
            String stageIdOrNull,
            String normalizedSymbol,
            Map<String, ProvenanceBinding> normalizedBindings
    ) {
        if (stageIdOrNull != null) {
            ProvenanceBinding stageScoped = normalizedBindings.get(stageIdOrNull + ":" + normalizedSymbol);
            if (stageScoped != null) {
                return stageScoped;
            }
        }
        return normalizedBindings.get(normalizedSymbol);
    }

    // =========================================================================
    // Closed-World Class Hierarchy & Constant-Pool Index
    // =========================================================================

    private static final class ClosedWorldClassIndex {
        private final Function<String, byte[]> classByteResolver;
        private final int maxClassesBudget;
        private final Map<String, ParsedClass> classesByName = new LinkedHashMap<>();
        private final Set<String> negativeCache = new LinkedHashSet<>();
        private final Set<String> missingBodyWarned = new LinkedHashSet<>();
        private final Set<String> neighborhoodExpanded = new LinkedHashSet<>();
        private final Map<String, Set<String>> directSubtypesBySuper = new LinkedHashMap<>();
        private final Map<String, List<MethodNode>> methodsByOwnerAndName = new LinkedHashMap<>();
        private final Map<String, MethodNode> methodsByExactKey = new LinkedHashMap<>();
        private final Map<String, List<MethodNode>> fieldReadersByField = new LinkedHashMap<>();
        private final Map<String, List<IndyFunctionalSite>> indySitesByIfaceAndSam = new LinkedHashMap<>();
        private final Set<String> budgetDroppedWitnesses = new LinkedHashSet<>();
        private final Set<String> missingSeedTypes = new LinkedHashSet<>();
        private final Map<String, String> hierarchyMissingCache = new LinkedHashMap<>();
        private final Map<String, Boolean> samTypeCache = new LinkedHashMap<>();
        private boolean budgetExhausted = false;

        ClosedWorldClassIndex(Function<String, byte[]> classByteResolver, int maxClassesBudget) {
            this.classByteResolver = classByteResolver;
            this.maxClassesBudget = maxClassesBudget;
        }

        boolean isBudgetExhausted() {
            return budgetExhausted;
        }

        int loadedClassCount() {
            return classesByName.size();
        }

        Set<String> budgetDroppedWitnesses() {
            return budgetDroppedWitnesses;
        }

        Set<String> missingSeedTypes() {
            return missingSeedTypes;
        }

        void recordMissingSeedType(String missingSeed) {
            if (missingSeedTypes.add(missingSeed)) {
                warnMissingBody(MISSING_CLOSED_WORLD_SEED_PREFIX + missingSeed + ">", missingSeed);
            }
        }

        ParsedClass loadedClassOrNull(String binaryClassName) {
            return classesByName.get(unwrapElementClassIfArray(binaryClassName));
        }

        List<IndyFunctionalSite> indySitesInMethod(String ownerClass, String methodName) {
            ParsedClass pc = classesByName.get(normalizeClassName(ownerClass));
            if (pc == null || pc.indyFunctionalSites().isEmpty()) {
                return List.of();
            }
            List<IndyFunctionalSite> matches = new ArrayList<>();
            for (IndyFunctionalSite site : pc.indyFunctionalSites()) {
                if (methodName.equals(site.enclosingMethod())) {
                    matches.add(site);
                }
            }
            return matches;
        }

        private void markBudgetExhausted(String droppedClass) {
            if (budgetDroppedWitnesses.size() < 8) {
                budgetDroppedWitnesses.add(droppedClass);
            }
            if (!budgetExhausted) {
                budgetExhausted = true;
                LOGGER.log(
                        Level.WARNING,
                        "[PipelineCoverageGraphScanner] Closed-world class budget exhausted (limit="
                                + maxClassesBudget + ") before loading " + droppedClass
                                + "; marking pipeline stage graphs truncated (fail-closed)."
                );
            }
        }

        ParsedClass ensureLoaded(String binaryClassName) {
            String norm = normalizeClassName(binaryClassName);
            if (norm.isEmpty() || isBootstrapJdkOrLoggingClass(norm)) {
                return null;
            }
            ParsedClass existing = classesByName.get(norm);
            if (existing != null) {
                return existing;
            }
            if (negativeCache.contains(norm)) {
                return null;
            }
            if (classesByName.size() >= maxClassesBudget) {
                markBudgetExhausted(norm);
                return null;
            }

            byte[] rawBytes;
            try {
                rawBytes = classByteResolver.apply(norm);
                if (rawBytes == null) {
                    rawBytes = classByteResolver.apply(norm.replace('.', '/'));
                }
            } catch (RuntimeException e) {
                LOGGER.log(
                        Level.WARNING,
                        "[PipelineCoverageGraphScanner] classByteResolver threw while loading " + norm,
                        e
                );
                negativeCache.add(norm);
                return null;
            }

            if (rawBytes == null) {
                negativeCache.add(norm);
                return null;
            }

            ParsedClass parsed = parseClassBytesCached(rawBytes);
            if (parsed == null) {
                LOGGER.log(
                        Level.WARNING,
                        "[PipelineCoverageGraphScanner] Unreadable or malformed .class bytes for " + norm
                );
                negativeCache.add(norm);
                return null;
            }

            registerParsedClass(parsed);
            if (!parsed.binaryClassName().equals(norm)) {
                classesByName.putIfAbsent(norm, parsed);
            }
            return parsed;
        }

        /**
         * Loads {@code owner} and verifies that {@code owner} and every non-bootstrap
         * supertype/interface in its hierarchy has readable {@code .class} bytes. Returns the first
         * unavailable non-bootstrap type name, or {@code null} when complete.
         */
        String requireHierarchyLoaded(String owner) {
            String rootNorm = unwrapElementClassIfArray(owner);
            if (rootNorm.isEmpty() || isBootstrapJdkOrLoggingClass(rootNorm)) {
                return null;
            }
            String cached = hierarchyMissingCache.get(rootNorm);
            if (cached != null) {
                return cached.isEmpty() ? null : cached;
            }
            int dollar = rootNorm.indexOf('$');
            if (dollar > 0) {
                ensureLoaded(rootNorm.substring(0, dollar));
            }
            Deque<String> work = new ArrayDeque<>();
            Set<String> seen = new LinkedHashSet<>();
            work.add(rootNorm);
            while (!work.isEmpty()) {
                String type = work.poll();
                if (type.isEmpty() || !seen.add(type) || isBootstrapJdkOrLoggingClass(type)) {
                    continue;
                }
                ParsedClass pc = ensureLoaded(type);
                if (pc == null) {
                    hierarchyMissingCache.put(rootNorm, type);
                    return type;
                }
                if (pc.superClassName() != null) {
                    work.add(normalizeClassName(pc.superClassName()));
                }
                for (String iface : pc.interfaces()) {
                    work.add(normalizeClassName(iface));
                }
            }
            hierarchyMissingCache.put(rootNorm, "");
            return null;
        }

        boolean canInheritFromBootstrap(String owner, String methodName) {
            if (isJavaLangObjectMethodName(methodName)) {
                return true;
            }
            Deque<String> work = new ArrayDeque<>();
            Set<String> seen = new LinkedHashSet<>();
            work.add(unwrapElementClassIfArray(owner));
            while (!work.isEmpty()) {
                String type = work.poll();
                if (type.isEmpty() || !seen.add(type)) {
                    continue;
                }
                if (isBootstrapJdkOrLoggingClass(type)) {
                    if (!"java.lang.Object".equals(type)) {
                        return true;
                    }
                    continue;
                }
                ParsedClass pc = classesByName.get(type);
                if (pc == null) {
                    continue;
                }
                if (pc.superClassName() != null) {
                    work.add(normalizeClassName(pc.superClassName()));
                }
                for (String iface : pc.interfaces()) {
                    work.add(normalizeClassName(iface));
                }
            }
            return false;
        }

        void warnMissingBody(String symbol, String missingType) {
            if (missingBodyWarned.add(symbol + "|" + missingType)) {
                LOGGER.log(
                        Level.WARNING,
                        "[PipelineCoverageGraphScanner] Body or hierarchy of " + symbol
                                + " was not inspected (class bytes or method unavailable in "
                                + missingType + "); the stage graph is truncated (fail-closed)."
                );
            }
        }

        ParsedClass ensureLoadedWithNeighborhood(String binaryClassName) {
            ParsedClass pc = ensureLoaded(binaryClassName);
            if (pc == null || !neighborhoodExpanded.add(pc.binaryClassName())) {
                return pc;
            }
            int dollar = pc.binaryClassName().indexOf('$');
            if (dollar > 0) {
                ensureLoaded(pc.binaryClassName().substring(0, dollar));
            }
            // Load 1-hop constant-pool referenced domain classes
            for (String refCls : pc.referencedClasses()) {
                if (isBootstrapJdkOrLoggingClass(refCls)) {
                    continue;
                }
                ensureLoaded(refCls);
                if (budgetExhausted) {
                    break;
                }
            }
            return pc;
        }

        private void registerParsedClass(ParsedClass pc) {
            String clsName = pc.binaryClassName();
            if (classesByName.putIfAbsent(clsName, pc) != null) {
                return;
            }

            if (pc.superClassName() != null && !isBootstrapJdkOrLoggingClass(pc.superClassName())) {
                directSubtypesBySuper
                        .computeIfAbsent(pc.superClassName(), k -> new LinkedHashSet<>())
                        .add(clsName);
                ensureLoaded(pc.superClassName());
            }
            for (String iface : pc.interfaces()) {
                directSubtypesBySuper
                        .computeIfAbsent(iface, k -> new LinkedHashSet<>())
                        .add(clsName);
                if (!isBootstrapJdkOrLoggingClass(iface)) {
                    ensureLoaded(iface);
                }
            }

            for (IndyFunctionalSite site : pc.indyFunctionalSites()) {
                List<IndyFunctionalSite> bucket = indySitesByIfaceAndSam.computeIfAbsent(
                        site.functionalInterface() + "#" + site.samMethodName(),
                        k -> new ArrayList<>()
                );
                if (!bucket.contains(site)) {
                    bucket.add(site);
                }
            }

            for (MethodNode m : pc.methods().values()) {
                methodsByOwnerAndName
                        .computeIfAbsent(clsName + "#" + m.name(), k -> new ArrayList<>())
                        .add(m);
                methodsByExactKey.put(clsName + "#" + m.name() + m.descriptor(), m);

                if (!"<init>".equals(m.name())
                        && !"<clinit>".equals(m.name())
                        && !isNonWorldgenInfrastructureSinkMethod(clsName, m.name())) {
                    for (String rf : m.readFields()) {
                        fieldReadersByField
                                .computeIfAbsent(rf, k -> new ArrayList<>())
                                .add(m);
                    }
                }
            }
        }

        List<MethodNode> findConstructors(String binaryClassName) {
            ensureLoaded(binaryClassName);
            List<MethodNode> ctors = methodsByOwnerAndName.get(binaryClassName + "#<init>");
            return ctors == null ? List.of() : ctors;
        }

        List<MethodNode> resolveMethodsForInvocation(String rawRef) {
            if (rawRef == null || rawRef.isBlank()) {
                return List.of();
            }
            String trimmed = rawRef.trim().replace("::", "#");
            int hash = trimmed.indexOf('#');
            if (hash <= 0) {
                return List.of();
            }
            String owner = normalizeClassName(trimmed.substring(0, hash));
            String memberAndDesc = trimmed.substring(hash + 1);
            int paren = memberAndDesc.indexOf('(');
            String methodName = paren > 0 ? memberAndDesc.substring(0, paren) : memberAndDesc;
            String descriptorOrNull = paren > 0 ? memberAndDesc.substring(paren) : null;

            ensureLoaded(owner);

            // Exact owner lookup first
            List<MethodNode> direct = lookupDeclaredMethods(owner, methodName, descriptorOrNull);
            if (!direct.isEmpty()) {
                return direct;
            }

            // Walk superclass hierarchy and default interface methods for inherited call sites
            Deque<String> supers = new ArrayDeque<>();
            Set<String> visitedTypes = new LinkedHashSet<>();
            ParsedClass start = classesByName.get(owner);
            if (start != null) {
                if (start.superClassName() != null) {
                    supers.add(start.superClassName());
                }
                supers.addAll(start.interfaces());
            }
            while (!supers.isEmpty()) {
                String sup = supers.poll();
                if (!visitedTypes.add(sup) || isBootstrapJdkOrLoggingClass(sup)) {
                    continue;
                }
                ParsedClass supPc = ensureLoaded(sup);
                if (supPc == null) {
                    continue;
                }
                List<MethodNode> inherited = lookupDeclaredMethods(sup, methodName, descriptorOrNull);
                if (!inherited.isEmpty()) {
                    return inherited;
                }
                if (supPc.superClassName() != null) {
                    supers.add(supPc.superClassName());
                }
                supers.addAll(supPc.interfaces());
            }
            return List.of();
        }

        private List<MethodNode> lookupDeclaredMethods(String owner, String methodName, String descriptorOrNull) {
            if (descriptorOrNull != null) {
                MethodNode exact = methodsByExactKey.get(owner + "#" + methodName + descriptorOrNull);
                return exact != null ? List.of(exact) : List.of();
            }
            List<MethodNode> list = methodsByOwnerAndName.get(owner + "#" + methodName);
            return list == null ? List.of() : list;
        }

        List<MethodNode> resolveSubtypeImplementations(
                String baseOwner,
                String methodName,
                Set<String> allowedConcreteTypesOrNull
        ) {
            List<MethodNode> out = new ArrayList<>();
            Set<String> visitedSubtypes = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>(directSubtypesBySuper.getOrDefault(baseOwner, Set.of()));

            while (!queue.isEmpty()) {
                String subCls = queue.poll();
                if (!visitedSubtypes.add(subCls)) {
                    continue;
                }
                queue.addAll(directSubtypesBySuper.getOrDefault(subCls, Set.of()));

                if (allowedConcreteTypesOrNull != null && !allowedConcreteTypesOrNull.contains(subCls)) {
                    continue;
                }

                ParsedClass subPc = classesByName.get(subCls);
                if (subPc == null) {
                    continue;
                }
                // Note: enum constants (ACC_ENUM) and records ARE valid concrete subtypes
                List<MethodNode> candidates = methodsByOwnerAndName.getOrDefault(
                        subCls + "#" + methodName,
                        List.of()
                );
                for (MethodNode cand : candidates) {
                    if ((cand.accessFlags() & (BytecodeCausalGraphScanner.ACC_ABSTRACT
                            | BytecodeCausalGraphScanner.ACC_PRIVATE
                            | BytecodeCausalGraphScanner.ACC_STATIC)) == 0) {
                        out.add(cand);
                    }
                }
            }
            return out;
        }

        Set<String> allSubtypesOf(String baseOwner) {
            Set<String> visited = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>(directSubtypesBySuper.getOrDefault(baseOwner, Set.of()));
            while (!queue.isEmpty()) {
                String subCls = queue.poll();
                if (visited.add(subCls)) {
                    queue.addAll(directSubtypesBySuper.getOrDefault(subCls, Set.of()));
                }
            }
            return visited;
        }

        boolean isSamType(String binaryClassName) {
            String norm = normalizeClassName(binaryClassName);
            Boolean cached = samTypeCache.get(norm);
            if (cached != null) {
                return cached;
            }
            ParsedClass pc = ensureLoaded(norm);
            if (pc == null
                    || (pc.accessFlags() & (BytecodeCausalGraphScanner.ACC_INTERFACE
                    | BytecodeCausalGraphScanner.ACC_ABSTRACT)) == 0) {
                samTypeCache.put(norm, false);
                return false;
            }
            Set<String> abstractSignatures = new LinkedHashSet<>();
            Set<String> concreteSignatures = new LinkedHashSet<>();
            Deque<String> hierarchy = new ArrayDeque<>();
            Set<String> seen = new LinkedHashSet<>();
            hierarchy.add(pc.binaryClassName());
            while (!hierarchy.isEmpty()) {
                String cur = hierarchy.poll();
                if (!seen.add(cur) || isBootstrapJdkOrLoggingClass(cur)) {
                    continue;
                }
                ParsedClass curPc = ensureLoaded(cur);
                if (curPc == null) {
                    continue;
                }
                for (MethodNode m : curPc.methods().values()) {
                    if ((m.accessFlags() & (BytecodeCausalGraphScanner.ACC_STATIC
                            | BytecodeCausalGraphScanner.ACC_PRIVATE)) != 0
                            || "<init>".equals(m.name())
                            || "<clinit>".equals(m.name())
                            || isJavaLangObjectPublicSignature(m.name(), m.descriptor())) {
                        continue;
                    }
                    String sig = m.name() + m.descriptor();
                    if ((m.accessFlags() & BytecodeCausalGraphScanner.ACC_ABSTRACT) != 0) {
                        abstractSignatures.add(sig);
                    } else {
                        concreteSignatures.add(sig);
                    }
                }
                if (curPc.superClassName() != null) {
                    hierarchy.add(curPc.superClassName());
                }
                hierarchy.addAll(curPc.interfaces());
            }
            abstractSignatures.removeAll(concreteSignatures);
            boolean result = abstractSignatures.size() == 1;
            samTypeCache.put(norm, result);
            return result;
        }

        private static boolean isJavaLangObjectPublicSignature(String name, String desc) {
            return ("equals".equals(name) && "(Ljava/lang/Object;)Z".equals(desc))
                    || ("hashCode".equals(name) && "()I".equals(desc))
                    || ("toString".equals(name) && "()Ljava/lang/String;".equals(desc));
        }

        List<IndyFunctionalSite> resolveIndyFunctionalSites(
                Set<String> candidateInterfaces,
                String samMethodName,
                Set<String> allowedConcreteTypesOrNull,
                Set<String> stageReferencedOwners
        ) {
            Set<IndyFunctionalSite> out = new LinkedHashSet<>();
            for (String iface : candidateInterfaces) {
                List<IndyFunctionalSite> sites = indySitesByIfaceAndSam.getOrDefault(
                        iface + "#" + samMethodName,
                        List.of()
                );
                for (IndyFunctionalSite site : sites) {
                    String targetOwner = extractOwnerClass(site.targetSymbol());
                    boolean allowed = allowedConcreteTypesOrNull == null
                            || allowedConcreteTypesOrNull.contains(site.enclosingClass())
                            || allowedConcreteTypesOrNull.contains(targetOwner)
                            || stageReferencedOwners.contains(site.enclosingClass());
                    if (allowed) {
                        out.add(site);
                    }
                }
            }
            return List.copyOf(out);
        }

        Set<String> allSupertypesOf(String binaryClassName) {
            Set<String> out = new LinkedHashSet<>();
            Deque<String> stack = new ArrayDeque<>();
            stack.add(binaryClassName);
            while (!stack.isEmpty()) {
                String cur = stack.poll();
                ParsedClass pc = ensureLoaded(cur);
                if (pc == null) {
                    continue;
                }
                if (pc.superClassName() != null && !isBootstrapJdkOrLoggingClass(pc.superClassName())
                        && out.add(pc.superClassName())) {
                    stack.add(pc.superClassName());
                }
                for (String iface : pc.interfaces()) {
                    if (!isBootstrapJdkOrLoggingClass(iface) && out.add(iface)) {
                        stack.add(iface);
                    }
                }
            }
            return out;
        }

        Set<String> expandCarrierHierarchy(String carrierClass) {
            String norm = normalizeClassName(carrierClass);
            Set<String> family = new LinkedHashSet<>();
            if (norm.isEmpty()) {
                return family;
            }
            family.add(norm);
            family.addAll(allSupertypesOf(norm));

            Deque<String> subQueue = new ArrayDeque<>(directSubtypesBySuper.getOrDefault(norm, Set.of()));
            while (!subQueue.isEmpty()) {
                String sub = subQueue.poll();
                if (family.add(sub)) {
                    subQueue.addAll(directSubtypesBySuper.getOrDefault(sub, Set.of()));
                }
            }
            return family;
        }

        String canonicalizeFieldSymbol(String rawFieldSymbol) {
            String norm = normalizeRuntimeSymbol(rawFieldSymbol);
            String owner = extractOwnerClass(norm);
            String fieldName = extractMemberName(norm);
            if (owner.isEmpty() || fieldName.isEmpty()) {
                return norm;
            }
            ParsedClass pc = ensureLoaded(owner);
            if (pc == null || pc.declaredFields().contains(fieldName)) {
                return norm;
            }
            Set<String> visited = new LinkedHashSet<>();
            String curSuper = pc.superClassName();
            while (curSuper != null && !isBootstrapJdkOrLoggingClass(curSuper) && visited.add(curSuper)) {
                ParsedClass supPc = ensureLoaded(curSuper);
                if (supPc == null) {
                    break;
                }
                if (supPc.declaredFields().contains(fieldName)) {
                    return supPc.binaryClassName() + "#" + fieldName;
                }
                curSuper = supPc.superClassName();
            }
            return norm;
        }

        boolean isFieldFinal(String canonicalFieldSymbol) {
            String owner = extractOwnerClass(canonicalFieldSymbol);
            String fieldName = extractMemberName(canonicalFieldSymbol);
            ParsedClass pc = classesByName.get(owner);
            if (pc == null) {
                return false;
            }
            FieldDescriptorMeta meta = pc.declaredFieldMeta().get(fieldName);
            return meta != null && meta.isFinal();
        }

        List<MethodNode> findFieldReaderMethods(String canonicalFieldSymbol, String rawFieldSymbol) {
            List<MethodNode> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (MethodNode m : fieldReadersByField.getOrDefault(canonicalFieldSymbol, List.of())) {
                if (seen.add(m.ownerClass() + "#" + m.name() + m.descriptor())) {
                    out.add(m);
                }
            }
            if (!canonicalFieldSymbol.equals(rawFieldSymbol)) {
                for (MethodNode m : fieldReadersByField.getOrDefault(rawFieldSymbol, List.of())) {
                    if (seen.add(m.ownerClass() + "#" + m.name() + m.descriptor())) {
                        out.add(m);
                    }
                }
            }
            // Also check subtype field-access aliases (e.g. reader accessed SubClass#inheritedField)
            String canonOwner = extractOwnerClass(canonicalFieldSymbol);
            String fieldName = extractMemberName(canonicalFieldSymbol);
            Deque<String> subs = new ArrayDeque<>(directSubtypesBySuper.getOrDefault(canonOwner, Set.of()));
            Set<String> visitedSubs = new LinkedHashSet<>();
            while (!subs.isEmpty()) {
                String sub = subs.poll();
                if (!visitedSubs.add(sub)) {
                    continue;
                }
                subs.addAll(directSubtypesBySuper.getOrDefault(sub, Set.of()));
                for (MethodNode m : fieldReadersByField.getOrDefault(sub + "#" + fieldName, List.of())) {
                    if (seen.add(m.ownerClass() + "#" + m.name() + m.descriptor())) {
                        out.add(m);
                    }
                }
            }
            return out;
        }
    }

    // =========================================================================
    // Structural Classification & Normalization Helpers
    // =========================================================================

    private static boolean isAbstractOrInterfaceNoBody(MethodNode node) {
        return (node.accessFlags() & BytecodeCausalGraphScanner.ACC_ABSTRACT) != 0;
    }

    private static boolean isAsyncOrSchedulerCall(String ownerBinary, String methodName) {
        if (ownerBinary == null || methodName == null) {
            return false;
        }
        if ((ownerBinary.endsWith(".CompletableFuture") || ownerBinary.endsWith(".CompletionStage"))
                && COMPLETABLE_FUTURE_COMBINATORS.contains(methodName)) {
            return true;
        }
        if (ownerBinary.startsWith("java.util.concurrent.")
                && (ownerBinary.endsWith("Executor")
                        || ownerBinary.endsWith("ExecutorService")
                        || ownerBinary.endsWith("Pool")
                        || ownerBinary.endsWith("ForkJoinTask"))
                && EXECUTOR_DISPATCH_METHODS.contains(methodName)) {
            return true;
        }
        boolean schedulerLikeOwner = ownerBinary.contains("Scheduler")
                || ownerBinary.contains("ProcessorHandle")
                || ownerBinary.contains("Mailbox")
                || ownerBinary.contains("TaskQueue")
                || ownerBinary.contains("EventLoop")
                || ownerBinary.endsWith("Executor");
        return schedulerLikeOwner && (MAILBOX_OR_SCHEDULER_METHODS.contains(methodName)
                || methodName.startsWith("schedule")
                || methodName.startsWith("runTask"));
    }

    private static boolean isJavaLangObjectMethodName(String name) {
        return "equals".equals(name)
                || "hashCode".equals(name)
                || "toString".equals(name)
                || "getClass".equals(name)
                || "clone".equals(name)
                || "notify".equals(name)
                || "notifyAll".equals(name)
                || "wait".equals(name)
                || "finalize".equals(name);
    }

    private static String unwrapElementClassIfArray(String rawClass) {
        if (rawClass == null) {
            return "";
        }
        String s = rawClass.trim();
        int idx = 0;
        while (idx < s.length() && s.charAt(idx) == '[') {
            idx++;
        }
        if (idx > 0) {
            String elem = s.substring(idx);
            if (elem.length() <= 1) {
                return "";
            }
            return normalizeClassName(elem);
        }
        return normalizeClassName(s);
    }

    /**
     * Returns {@code true} when {@code binaryClassName} belongs to deterministic non-worldgen
     * collection, concurrency, or crash-formatting runtime infrastructure whose internal class
     * hierarchy must not expand the worldgen closed-world budget.
     */
    public static boolean isNonWorldgenInfrastructureClass(String binaryClassName) {
        if (binaryClassName == null || binaryClassName.isEmpty()) {
            return false;
        }
        if (binaryClassName.startsWith("[")) {
            String elem = unwrapElementClassIfArray(binaryClassName);
            return !elem.isEmpty() && isNonWorldgenInfrastructureClass(elem);
        }
        String norm = normalizeClassName(binaryClassName);
        return norm.startsWith("it.unimi.dsi.fastutil.")
                || norm.startsWith("com.google.common.base.")
                || norm.startsWith("com.google.common.collect.")
                || norm.startsWith("com.google.common.hash.")
                || norm.startsWith("com.google.common.math.")
                || norm.startsWith("com.google.common.primitives.")
                || norm.startsWith("com.google.gson.")
                || norm.startsWith("com.mojang.serialization.")
                || norm.startsWith("com.mojang.datafixers.")
                || norm.startsWith("com.mojang.brigadier.")
                || norm.startsWith("com.mojang.authlib.")
                || norm.startsWith("io.netty.")
                || norm.startsWith("org.joml.")
                || norm.startsWith("joptsimple.")
                || norm.startsWith("org.apache.commons.")
                || norm.startsWith("org.apache.logging.")
                || norm.startsWith("ca.spottedleaf.concurrentutil.")
                || norm.startsWith("org.slf4j.")
                || norm.startsWith("com.mojang.logging.")
                || norm.startsWith("net.minecraft.CrashReport");
    }

    /**
     * Returns {@code true} when {@code ownerBinary#methodName} is a verified non-worldgen
     * chunk-holder ticket/player-tracking/entity-save or player diagnostic sink.
     */
    public static boolean isNonWorldgenInfrastructureSinkMethod(String ownerBinary, String methodName) {
        if (ownerBinary == null || ownerBinary.isEmpty() || methodName == null || methodName.isEmpty()) {
            return false;
        }
        String normOwner = normalizeClassName(ownerBinary);
        if ("moonrise$getPlayers".equals(methodName)) {
            return "ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemChunkHolder".equals(normOwner)
                    || normOwner.endsWith(".ChunkSystemChunkHolder")
                    || "net.minecraft.server.level.ChunkHolder".equals(normOwner)
                    || normOwner.endsWith(".ChunkHolder");
        }
        if ("saveEntities".equals(methodName)) {
            return "ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder".equals(normOwner)
                    || normOwner.endsWith(".NewChunkHolder");
        }
        if ("debugInfo".equals(methodName)) {
            return "net.minecraft.world.entity.player.Player".equals(normOwner)
                    || "net.minecraft.server.level.ServerPlayer".equals(normOwner)
                    || normOwner.endsWith(".Player")
                    || normOwner.endsWith(".ServerPlayer");
        }
        return false;
    }

    private static boolean isExclusivelyCrashReportDetailLambda(
            String enclosingOwner,
            String enclosingMethod,
            String canonicalLambdaSym,
            ClosedWorldClassIndex classIndex
    ) {
        List<IndyFunctionalSite> sites = classIndex.indySitesInMethod(enclosingOwner, enclosingMethod);
        if (sites.isEmpty()) {
            return false;
        }
        boolean matchedCrashDetail = false;
        for (IndyFunctionalSite site : sites) {
            if (stripMethodDescriptor(site.targetSymbol()).equals(canonicalLambdaSym)) {
                String iface = normalizeClassName(site.functionalInterface());
                if (iface.startsWith("net.minecraft.CrashReport")) {
                    matchedCrashDetail = true;
                } else {
                    return false;
                }
            }
        }
        return matchedCrashDetail;
    }

    private static boolean isMutatingNonWorldgenCollectionMethod(String ownerBinary, String methodName) {
        if (ownerBinary == null || methodName == null) {
            return false;
        }
        if (!(ownerBinary.startsWith("it.unimi.dsi.fastutil.")
                || ownerBinary.startsWith("com.google.common.collect.")
                || ownerBinary.startsWith("ca.spottedleaf.concurrentutil."))) {
            return false;
        }
        return "put".equals(methodName)
                || "putIfAbsent".equals(methodName)
                || "putAll".equals(methodName)
                || "add".equals(methodName)
                || "addAll".equals(methodName)
                || "push".equals(methodName)
                || "offer".equals(methodName)
                || "enqueue".equals(methodName)
                || "remove".equals(methodName)
                || "removeAll".equals(methodName)
                || "clear".equals(methodName)
                || "merge".equals(methodName)
                || methodName.startsWith("compute")
                || methodName.startsWith("replace");
    }

    private static boolean isBootstrapJdkOrLoggingClass(String binaryClassName) {
        if (binaryClassName == null || binaryClassName.isEmpty()) {
            return false;
        }
        if (binaryClassName.startsWith("[")) {
            String elem = unwrapElementClassIfArray(binaryClassName);
            return elem.isEmpty() || isBootstrapJdkOrLoggingClass(elem);
        }
        return binaryClassName.startsWith("java.")
                || binaryClassName.startsWith("javax.")
                || binaryClassName.startsWith("jdk.")
                || binaryClassName.startsWith("sun.")
                || binaryClassName.startsWith("com.sun.")
                || binaryClassName.startsWith("org.slf4j.")
                || binaryClassName.startsWith("org.apache.logging.log4j.")
                || binaryClassName.startsWith("org.jetbrains.annotations.")
                || binaryClassName.startsWith("kotlin.jvm.internal.")
                || isNonWorldgenInfrastructureClass(binaryClassName);
    }

    private static boolean isOpaqueReflectiveOrUnsafeCall(String ownerBinary, String methodName) {
        if (ownerBinary == null || ownerBinary.isEmpty()) {
            return false;
        }
        if ("sun.misc.Unsafe".equals(ownerBinary) || "jdk.internal.misc.Unsafe".equals(ownerBinary)) {
            return true;
        }
        if ("java.lang.reflect.Method".equals(ownerBinary) && "invoke".equals(methodName)) {
            return true;
        }
        if ("java.lang.reflect.Constructor".equals(ownerBinary) && "newInstance".equals(methodName)) {
            return true;
        }
        if ("java.lang.reflect.Field".equals(ownerBinary) && methodName.startsWith("set")) {
            return true;
        }
        if ("java.lang.reflect.Array".equals(ownerBinary) && methodName.startsWith("set")) {
            return true;
        }
        if ("java.lang.Class".equals(ownerBinary)
                && ("newInstance".equals(methodName) || "forName".equals(methodName))) {
            return true;
        }
        if ("java.lang.invoke.MethodHandle".equals(ownerBinary) && methodName.startsWith("invoke")) {
            return true;
        }
        if ("java.lang.invoke.MethodHandles".equals(ownerBinary)
                && ("arrayElementSetter".equals(methodName)
                || "arrayElementVarHandle".equals(methodName)
                || "privateLookupIn".equals(methodName))) {
            return true;
        }
        if (ownerBinary.startsWith("java.util.concurrent.atomic.") && ownerBinary.endsWith("FieldUpdater")) {
            return methodName.startsWith("set")
                    || "lazySet".equals(methodName)
                    || methodName.startsWith("compareAnd")
                    || methodName.startsWith("weakCompareAnd")
                    || methodName.startsWith("getAnd")
                    || methodName.endsWith("AndGet");
        }
        return "java.lang.invoke.VarHandle".equals(ownerBinary)
                && (methodName.startsWith("set")
                || methodName.startsWith("compareAnd")
                || methodName.startsWith("weakCompareAnd")
                || methodName.startsWith("getAnd"));
    }

    private static boolean isMutatingJdkMethodReferenceTarget(String ownerBinary, String methodName) {
        if ("java.util.Arrays".equals(ownerBinary)) {
            return "sort".equals(methodName)
                    || "parallelSort".equals(methodName)
                    || "fill".equals(methodName)
                    || "setAll".equals(methodName)
                    || "parallelSetAll".equals(methodName)
                    || "parallelPrefix".equals(methodName)
                    || "asList".equals(methodName);
        }
        if ("java.util.Collections".equals(ownerBinary)) {
            return "sort".equals(methodName)
                    || "fill".equals(methodName)
                    || "reverse".equals(methodName)
                    || "shuffle".equals(methodName)
                    || "swap".equals(methodName)
                    || "replaceAll".equals(methodName)
                    || "addAll".equals(methodName)
                    || "copy".equals(methodName);
        }
        if ("java.lang.System".equals(ownerBinary)) {
            return "arraycopy".equals(methodName) || "exit".equals(methodName);
        }
        if ("java.lang.Runtime".equals(ownerBinary)) {
            return "exec".equals(methodName) || "exit".equals(methodName) || "halt".equals(methodName);
        }
        if (ownerBinary.startsWith("java.nio.") && ownerBinary.endsWith("Buffer")) {
            return "wrap".equals(methodName) || methodName.startsWith("put") || "clear".equals(methodName);
        }
        if (ownerBinary.startsWith("java.util.concurrent.atomic.")) {
            return methodName.startsWith("set")
                    || "lazySet".equals(methodName)
                    || methodName.startsWith("compareAnd")
                    || methodName.startsWith("weakCompareAnd")
                    || methodName.startsWith("getAnd")
                    || methodName.endsWith("AndGet")
                    || "increment".equals(methodName)
                    || "decrement".equals(methodName)
                    || "add".equals(methodName)
                    || "reset".equals(methodName);
        }
        if ("java.util.BitSet".equals(ownerBinary)) {
            return "set".equals(methodName)
                    || "clear".equals(methodName)
                    || "flip".equals(methodName)
                    || "and".equals(methodName)
                    || "or".equals(methodName)
                    || "xor".equals(methodName)
                    || "andNot".equals(methodName);
        }
        return false;
    }

    private static boolean isBenignInfrastructureSymbol(String normalizedSymbol) {
        if (normalizedSymbol == null
                || normalizedSymbol.isEmpty()
                || CLOSED_WORLD_BUDGET_EXHAUSTED_SYMBOL.equals(normalizedSymbol)
                || normalizedSymbol.startsWith("UNKNOWN_ARRAY:")
                || normalizedSymbol.startsWith("UNKNOWN_MUTATION:")
                || normalizedSymbol.startsWith("UNRESOLVED_CALLBACK:")
                || normalizedSymbol.startsWith("UNKNOWN_INDY:")) {
            return false;
        }
        String owner = extractOwnerClass(normalizedSymbol);
        String member = extractMemberName(normalizedSymbol);
        if (isNonWorldgenInfrastructureSinkMethod(owner, member)) {
            return true;
        }
        if (isOpaqueReflectiveOrUnsafeCall(owner, member)
                || isAsyncOrSchedulerCall(owner, member)
                || isMutatingJdkMethodReferenceTarget(owner, member)) {
            return false;
        }
        if (isNonWorldgenInfrastructureClass(owner)) {
            return !isMutatingNonWorldgenCollectionMethod(owner, member);
        }
        if (BytecodeCausalGraphScanner.isPotentiallyMutatingJdkMethod(owner, member)) {
            return false;
        }
        String sam = BytecodeCausalGraphScanner.samMethodForJdkFunctionalInterface(owner);
        if (sam != null && sam.equals(member)) {
            return false;
        }
        if (isBootstrapJdkOrLoggingClass(owner)) {
            return true;
        }
        if (owner.startsWith("[") && "clone".equals(member)) {
            return true;
        }
        return "$values".equals(member) || "$jacocoInit".equals(member);
    }

    private static boolean isBenignAsyncExecutorIdentifier(String normalizedAsync) {
        if (normalizedAsync == null || normalizedAsync.isEmpty()) {
            return false;
        }
        String low = normalizedAsync.toLowerCase(Locale.ROOT);
        return low.startsWith("forkjoinpool")
                || low.startsWith("java.util.concurrent.forkjoinpool")
                || low.startsWith("java.util.concurrent.threadpoolexecutor")
                || low.startsWith("java.util.concurrent.completablefuture$threadpertaskexecutor")
                || low.startsWith("java.util.concurrent.completablefuture$delayer");
    }

    private static boolean isJvmLambdaProxyFrame(String symbol) {
        int hash = symbol.indexOf('#');
        String owner = hash > 0 ? symbol.substring(0, hash) : symbol;
        return owner.contains("$$Lambda");
    }

    private static String normalizeClassName(String rawClass) {
        if (rawClass == null) {
            return "";
        }
        String s = rawClass.trim();
        if (s.startsWith("L") && s.endsWith(";") && s.length() > 2) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace('/', '.');
    }

    private static String stripMethodDescriptor(String rawSymbol) {
        return normalizeRuntimeSymbol(rawSymbol);
    }

    private static String extractOwnerClass(String symbol) {
        if (symbol == null) {
            return "";
        }
        int hash = symbol.indexOf('#');
        return hash > 0 ? symbol.substring(0, hash) : symbol;
    }

    private static String extractMemberName(String symbol) {
        if (symbol == null) {
            return "";
        }
        int hash = symbol.indexOf('#');
        return hash >= 0 ? symbol.substring(hash + 1) : "";
    }

    private static String simpleMethodRef(String fullSymbol) {
        String owner = extractOwnerClass(fullSymbol);
        String member = extractMemberName(fullSymbol);
        int dot = owner.lastIndexOf('.');
        String shortOwner = dot >= 0 ? owner.substring(dot + 1) : owner;
        return member.isEmpty() ? shortOwner : (shortOwner + "#" + member);
    }

    private static boolean isHexOrDotChar(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F');
    }

    private static List<String> normalizeSymbolList(Collection<String> raw) {
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            String norm = normalizeRuntimeSymbol(s);
            if (!norm.isEmpty() && !out.contains(norm)) {
                out.add(norm);
            }
        }
        return out;
    }

    private static Set<String> normalizeSymbolSet(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            String norm = normalizeRuntimeSymbol(s);
            if (!norm.isEmpty()) {
                out.add(norm);
            }
        }
        return out;
    }

    private static Set<String> normalizeClassSet(Collection<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            String norm = normalizeClassName(s);
            if (!norm.isEmpty()) {
                out.add(norm);
            }
        }
        return out;
    }

    private static Map<String, ProvenanceBinding> normalizeBindingMap(Map<String, ProvenanceBinding> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, ProvenanceBinding> out = new LinkedHashMap<>();
        for (Map.Entry<String, ProvenanceBinding> e : raw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            String key = e.getKey().startsWith("STAGE:")
                    ? e.getKey().trim()
                    : normalizeRuntimeSymbol(e.getKey());
            out.put(key, e.getValue());
            if (!isStageScopedBindingKey(key)) {
                String bindingTarget = e.getValue().symbolOrField().startsWith("STAGE:")
                        ? e.getValue().symbolOrField().trim()
                        : normalizeRuntimeSymbol(e.getValue().symbolOrField());
                out.putIfAbsent(bindingTarget, e.getValue());
            }
        }
        return out;
    }

    private static boolean isStageScopedBindingKey(String key) {
        if (key == null
                || key.startsWith("STAGE:")
                || key.startsWith("UNKNOWN_MUTATION:")
                || key.startsWith("UNKNOWN_ARRAY:")) {
            return false;
        }
        int colon = key.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        String prefix = key.substring(0, colon);
        return prefix.indexOf('.') < 0 && prefix.indexOf('/') < 0 && prefix.indexOf('#') < 0;
    }

    private static Map<String, Set<String>> copyNestedSetMap(Map<String, Set<String>> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : raw.entrySet()) {
            copy.put(
                    e.getKey(),
                    e.getValue() == null
                            ? Set.of()
                            : Collections.unmodifiableSet(new LinkedHashSet<>(e.getValue()))
            );
        }
        return Collections.unmodifiableMap(copy);
    }

    private static void addUniqueEdge(List<PipelineEdge> list, PipelineEdge edge) {
        if (!list.contains(edge)) {
            list.add(edge);
        }
    }

    /**
     * Discovers declared non-{@code Object} callable method symbols ({@code Owner#method}) directly
     * from a raw {@code .class} byte stream without loading classes.
     */
    public static List<String> discoverNonObjectCallableMethods(byte[] classBytes) {
        ParsedClass parsed = parseClassBytesCached(classBytes);
        if (parsed == null) {
            return List.of();
        }
        String owner = parsed.binaryClassName();
        Set<String> symbols = new LinkedHashSet<>();
        boolean hasInit = false;
        for (MethodNode m : parsed.methods().values()) {
            String name = m.name();
            if ("<clinit>".equals(name)) {
                continue;
            }
            if ("<init>".equals(name)) {
                hasInit = true;
                continue;
            }
            if ((m.accessFlags() & BytecodeCausalGraphScanner.ACC_SYNTHETIC) != 0) {
                continue;
            }
            if (isStandardJavaObjectOverride(name, m.descriptor())) {
                continue;
            }
            symbols.add(owner + "#" + name);
        }
        if (symbols.isEmpty() && hasInit) {
            symbols.add(owner + "#<init>");
        }
        return List.copyOf(symbols);
    }

    /**
     * Verifies from raw {@code .class} bytecode that every overload of {@code methodName} declared on
     * the target class performs zero instance mutations/async dispatches and reads only instance
     * fields included in {@code consumedFieldNames}.
     */
    public static boolean verifyMethodReadsOnlyConsumedInstanceFieldsAndZeroMutations(
            byte[] classBytes,
            String methodName,
            Set<String> consumedFieldNames
    ) {
        if (classBytes == null || methodName == null || methodName.isEmpty()) {
            return false;
        }
        ParsedClass parsed = parseClassBytesCached(classBytes);
        if (parsed == null) {
            return false;
        }
        String owner = parsed.binaryClassName();
        Set<String> staticFieldNames = new LinkedHashSet<>();
        for (Map.Entry<String, FieldDescriptorMeta> e : parsed.declaredFieldMeta().entrySet()) {
            if ((e.getValue().accessFlags() & BytecodeCausalGraphScanner.ACC_STATIC) != 0) {
                staticFieldNames.add(e.getKey());
            }
        }
        boolean matchedAny = false;
        for (MethodNode m : parsed.methods().values()) {
            if (!methodName.equals(m.name())) {
                continue;
            }
            matchedAny = true;
            if (!m.producedQueueFields().isEmpty() || !m.asyncFunctionalDispatches().isEmpty()) {
                return false;
            }
            if ("<init>".equals(methodName)) {
                continue;
            }
            if (containsFieldOwnedBy(m.writtenFields(), owner)
                    || containsFieldOwnedBy(m.collectionAddedFields(), owner)
                    || containsFieldOwnedBy(m.collectionRemovedFields(), owner)
                    || containsFieldOwnedBy(m.arrayMutatedFields(), owner)
                    || containsFieldOwnedBy(m.receiverMutatedFields(), owner)) {
                return false;
            }
            for (String rf : m.readFields()) {
                String fOwner = extractOwnerClass(rf);
                String fName = extractMemberName(rf);
                if (owner.equals(fOwner)
                        && !fName.startsWith("$")
                        && !staticFieldNames.contains(fName)
                        && (consumedFieldNames == null || !consumedFieldNames.contains(fName))) {
                    return false;
                }
            }
        }
        return matchedAny;
    }

    /**
     * Pure-bytecode structural summary of a single {@code .class} file inspected without loading
     * the class ({@code S-004}, {@code leafgen-ADR-007}).
     */
    public record PureClassBytecodeReport(
            String binaryClassName,
            String superClassName,
            List<String> interfaces,
            Set<String> nonStaticFieldNames,
            List<String> callableMethodSymbols,
            Set<String> externalCalledMethods,
            Set<String> mutatedOwnerFields,
            Map<String, Boolean> methodHasArithmeticOrBranchOps
    ) {
        public PureClassBytecodeReport {
            Objects.requireNonNull(binaryClassName, "binaryClassName");
            superClassName = superClassName == null ? "" : superClassName;
            interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
            nonStaticFieldNames = nonStaticFieldNames == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(nonStaticFieldNames));
            callableMethodSymbols = callableMethodSymbols == null
                    ? List.of()
                    : List.copyOf(callableMethodSymbols);
            externalCalledMethods = externalCalledMethods == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(externalCalledMethods));
            mutatedOwnerFields = mutatedOwnerFields == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(mutatedOwnerFields));
            methodHasArithmeticOrBranchOps = methodHasArithmeticOrBranchOps == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(methodHasArithmeticOrBranchOps));
        }

        public boolean hasArithmeticOrBranchInMethod(String methodName) {
            return Boolean.TRUE.equals(methodHasArithmeticOrBranchOps.get(methodName));
        }
    }

    /**
     * Inspects raw {@code .class} bytes without classloading and extracts declared non-static
     * fields, callable operational method symbols, external callees, instance mutations, and
     * per-method arithmetic/branch presence for Check B domain operator verification.
     */
    public static PureClassBytecodeReport inspectClassBytecodeStructure(byte[] classBytes) {
        if (classBytes == null || classBytes.length < 24) {
            return null;
        }
        ParsedClass parsed = parseClassBytesCached(classBytes);
        if (parsed == null) {
            return null;
        }
        String owner = parsed.binaryClassName();
        Set<String> nonStaticFields = new LinkedHashSet<>();
        for (Map.Entry<String, FieldDescriptorMeta> e : parsed.declaredFieldMeta().entrySet()) {
            String fName = e.getKey();
            int flags = e.getValue().accessFlags();
            if ((flags & (BytecodeCausalGraphScanner.ACC_STATIC | BytecodeCausalGraphScanner.ACC_SYNTHETIC)) == 0
                    && !fName.startsWith("$")) {
                nonStaticFields.add(fName);
            }
        }
        List<String> callableSymbols = discoverNonObjectCallableMethods(classBytes);
        Set<String> externalCalls = new LinkedHashSet<>();
        Set<String> mutatedSelfFields = new LinkedHashSet<>();
        for (MethodNode m : parsed.methods().values()) {
            String mName = m.name();
            if ("<clinit>".equals(mName)
                    || (m.accessFlags() & BytecodeCausalGraphScanner.ACC_SYNTHETIC) != 0
                    || isStandardJavaObjectOverride(mName, m.descriptor())) {
                continue;
            }
            if (!"<init>".equals(mName)) {
                for (String wf : combineMutatedFields(m)) {
                    if (owner.equals(extractOwnerClass(wf))) {
                        mutatedSelfFields.add(wf);
                    }
                }
            }
            for (String called : m.calledMethods()) {
                String normCalled = stripMethodDescriptor(called);
                String calleeOwner = extractOwnerClass(normCalled);
                String calleeMember = extractMemberName(normCalled);
                if (calleeOwner.isEmpty()
                        || owner.equals(calleeOwner)
                        || ("<init>".equals(mName)
                                && "<init>".equals(calleeMember)
                                && (calleeOwner.equals(parsed.superClassName())
                                        || "java.lang.Object".equals(calleeOwner)
                                        || "java.lang.Record".equals(calleeOwner)))) {
                    continue;
                }
                externalCalls.add(normCalled);
            }
        }
        Map<String, Boolean> arithByMethod = scanMethodArithmeticOrBranchFlags(classBytes);
        if (arithByMethod == null) {
            return null;
        }
        return new PureClassBytecodeReport(
                owner,
                parsed.superClassName(),
                new ArrayList<>(parsed.interfaces()),
                nonStaticFields,
                callableSymbols,
                externalCalls,
                mutatedSelfFields,
                arithByMethod
        );
    }

    private static Map<String, Boolean> scanMethodArithmeticOrBranchFlags(byte[] b) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        try {
            if (b.length < 10 || readU32(b, 0) != 0xCAFEBABE) {
                return null;
            }
            int cpCount = readU16(b, 8);
            String[] utf8 = new String[cpCount];
            int pos = 10;
            for (int i = 1; i < cpCount; i++) {
                int tag = b[pos++] & 0xFF;
                switch (tag) {
                    case 1 -> {
                        int len = readU16(b, pos);
                        pos += 2;
                        utf8[i] = new String(b, pos, len, java.nio.charset.StandardCharsets.UTF_8);
                        pos += len;
                    }
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> pos += 4;
                    case 5, 6 -> {
                        pos += 8;
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> pos += 2;
                    case 15 -> pos += 3;
                    default -> {
                        return null;
                    }
                }
            }
            pos += 6; // access, this, super
            int ifaces = readU16(b, pos);
            pos += 2 + ifaces * 2;
            int fields = readU16(b, pos);
            pos += 2;
            for (int f = 0; f < fields; f++) {
                pos += 6;
                int attrs = readU16(b, pos);
                pos += 2;
                for (int a = 0; a < attrs; a++) {
                    int aLen = readU32(b, pos + 2);
                    pos += 6 + aLen;
                }
            }
            int methods = readU16(b, pos);
            pos += 2;
            for (int m = 0; m < methods; m++) {
                int nameIdx = readU16(b, pos + 2);
                String mName = (nameIdx > 0 && nameIdx < cpCount) ? utf8[nameIdx] : null;
                int attrs = readU16(b, pos + 6);
                pos += 8;
                boolean hasArith = false;
                for (int a = 0; a < attrs; a++) {
                    int aNameIdx = readU16(b, pos);
                    int aLen = readU32(b, pos + 2);
                    int attrDataStart = pos + 6;
                    if (mName != null && aNameIdx > 0 && aNameIdx < cpCount && "Code".equals(utf8[aNameIdx])) {
                        int codeLen = readU32(b, attrDataStart + 4);
                        int codeStart = attrDataStart + 8;
                        hasArith |= codeContainsArithmeticOrBranch(b, codeStart, codeStart + codeLen);
                    }
                    pos = attrDataStart + aLen;
                }
                if (mName != null) {
                    out.merge(mName, hasArith, Boolean::logicalOr);
                }
            }
        } catch (RuntimeException ex) {
            LOGGER.log(
                    Level.WARNING,
                    "[PipelineCoverageGraphScanner] Malformed .class Code attribute during pure bytecode structure inspection",
                    ex
            );
            return null;
        }
        return out;
    }

    private static boolean codeContainsArithmeticOrBranch(byte[] b, int start, int end) {
        int pc = start;
        while (pc < end) {
            int op = b[pc] & 0xFF;
            if ((op >= 0x60 && op <= 0x85)
                    || (op >= 0x94 && op <= 0xAB)
                    || op == 0xC6
                    || op == 0xC7) {
                return true;
            }
            pc += instructionByteLength(b, start, pc, op);
        }
        return false;
    }

    private static int instructionByteLength(byte[] b, int codeStart, int pc, int op) {
        return switch (op) {
            case 0xAA -> { // tableswitch
                int rel = pc - codeStart;
                int pad = (4 - ((rel + 1) & 3)) & 3;
                int base = pc + 1 + pad;
                int low = readU32(b, base + 4);
                int high = readU32(b, base + 8);
                yield 1 + pad + 12 + (high - low + 1) * 4;
            }
            case 0xAB -> { // lookupswitch
                int rel = pc - codeStart;
                int pad = (4 - ((rel + 1) & 3)) & 3;
                int base = pc + 1 + pad;
                int npairs = readU32(b, base + 4);
                yield 1 + pad + 8 + npairs * 8;
            }
            case 0xC4 -> ((b[pc + 1] & 0xFF) == 0x84) ? 6 : 4; // wide
            case 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19,
                    0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC -> 2;
            case 0x11, 0x13, 0x14, 0x84, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E,
                    0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8,
                    0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB, 0xBD, 0xC0,
                    0xC1, 0xC6, 0xC7 -> 3;
            case 0xC5 -> 4; // multianewarray
            case 0xB9, 0xBA, 0xC8, 0xC9 -> 5;
            default -> 1;
        };
    }

    private static int readU16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int readU32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static boolean containsFieldOwnedBy(Set<String> fieldSymbols, String ownerBinary) {
        if (fieldSymbols == null || fieldSymbols.isEmpty()) {
            return false;
        }
        for (String sym : fieldSymbols) {
            if (ownerBinary.equals(extractOwnerClass(sym))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStandardJavaObjectOverride(String name, String descriptor) {
        return ("equals".equals(name) && "(Ljava/lang/Object;)Z".equals(descriptor))
                || ("hashCode".equals(name) && "()I".equals(descriptor))
                || ("toString".equals(name) && "()Ljava/lang/String;".equals(descriptor))
                || ("clone".equals(name) && "()Ljava/lang/Object;".equals(descriptor))
                || ("finalize".equals(name) && "()V".equals(descriptor));
    }

    /**
     * Check A result for a single stage carrier boundary scan ({@code leafgen-ADR-007}, {@code S-004}).
     */
    public record StageBoundaryScanResult(
            String stageId,
            StageCoverageGraph boundaryGraph,
            Set<String> verifiedReplacedScaffoldMethods,
            Set<String> verifiedInternalAsyncTargets,
            Set<String> unexpectedHookOrMixinMethods,
            Set<String> customGeneratorOverrideMethods,
            boolean boundaryContractClean
    ) {
        public StageBoundaryScanResult {
            Objects.requireNonNull(stageId, "stageId");
            Objects.requireNonNull(boundaryGraph, "boundaryGraph");
            verifiedReplacedScaffoldMethods = verifiedReplacedScaffoldMethods == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(verifiedReplacedScaffoldMethods));
            verifiedInternalAsyncTargets = verifiedInternalAsyncTargets == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(verifiedInternalAsyncTargets));
            unexpectedHookOrMixinMethods = unexpectedHookOrMixinMethods == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(unexpectedHookOrMixinMethods));
            customGeneratorOverrideMethods = customGeneratorOverrideMethods == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(customGeneratorOverrideMethods));
        }
    }

    /**
     * Evaluates Check A stage carrier boundary integrity directly from raw {@code .class} byte streams
     * without loading classes ({@code S-004}, {@code leafgen-ADR-007}).
     *
     * <p>Computes the descriptor/field type closure starting from the {@code boundaryGraph} root task
     * method parameters, carrier hierarchy, and {@code allowedGeneratorClasses}, flagging any Mixin
     * injector handler, custom generator subtype override outside {@code allowedGeneratorClasses}, or
     * unexpected external hook class not present in the stage signature closure.
     */
    public static StageBoundaryScanResult verifyStageCarrierBoundary(
            Function<String, byte[]> classByteResolver,
            StageCoverageGraph boundaryGraph,
            String generatorBaseClassOrNull,
            Set<String> allowedGeneratorClasses
    ) {
        Objects.requireNonNull(classByteResolver, "classByteResolver");
        Objects.requireNonNull(boundaryGraph, "boundaryGraph");

        if (boundaryGraph.truncatedByBounds()
                || boundaryGraph.hasIncompleteClosedWorld()
                || boundaryGraph.rootMethodSymbols().isEmpty()) {
            return new StageBoundaryScanResult(
                    boundaryGraph.stageId(),
                    boundaryGraph,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    false
            );
        }

        Set<String> allowedGen = allowedGeneratorClasses == null
                ? Set.of()
                : normalizeClassSet(allowedGeneratorClasses);
        String normGenBase = generatorBaseClassOrNull == null
                ? ""
                : normalizeClassName(generatorBaseClassOrNull);

        Set<String> customGenOverrides = new LinkedHashSet<>();
        if (!normGenBase.isEmpty() && !allowedGen.isEmpty()) {
            for (Map.Entry<String, Set<String>> subEntry : boundaryGraph.resolvedSubtypeTargets().entrySet()) {
                String dispatchOwner = extractOwnerClass(subEntry.getKey());
                if (normGenBase.equals(dispatchOwner) || allowedGen.contains(dispatchOwner)) {
                    for (String targetSym : subEntry.getValue()) {
                        String targetOwner = extractOwnerClass(targetSym);
                        if (!targetOwner.equals(normGenBase) && !allowedGen.contains(targetOwner)) {
                            customGenOverrides.add(targetSym);
                        }
                    }
                }
            }
        }

        Set<String> signatureTypeClosure = computeStageSignatureTypeClosure(
                classByteResolver,
                boundaryGraph,
                normGenBase,
                allowedGen
        );

        Set<String> verifiedScaffold = new LinkedHashSet<>();
        Set<String> unexpectedHooks = new LinkedHashSet<>();
        for (String methodSym : boundaryGraph.reachableMethods()) {
            String normSym = normalizeRuntimeSymbol(methodSym);
            if (normSym.isEmpty()
                    || boundaryGraph.abstractDispatchMethods().contains(normSym)
                    || isJvmLambdaProxyFrame(normSym)
                    || isBenignInfrastructureSymbol(normSym)) {
                continue;
            }
            if (customGenOverrides.contains(normSym)) {
                continue;
            }
            String owner = extractOwnerClass(normSym);
            String member = extractMemberName(normSym);
            if (isMixinOrSyntheticHookMethod(classByteResolver, owner, member)) {
                unexpectedHooks.add(normSym);
                continue;
            }
            if (signatureTypeClosure.contains(owner)
                    || enclosingClassInClosure(owner, signatureTypeClosure)
                    || isPureSideEffectFreePrimitiveLeaf(classByteResolver, owner, member)) {
                verifiedScaffold.add(normSym);
            } else {
                unexpectedHooks.add(normSym);
            }
        }

        Set<String> verifiedAsync = new LinkedHashSet<>();
        for (String asyncTarget : boundaryGraph.scheduledAsyncTargets()) {
            String normAsync = normalizeRuntimeSymbol(asyncTarget);
            String asyncOwner = extractOwnerClass(normAsync);
            String asyncMember = extractMemberName(normAsync);
            String sam = BytecodeCausalGraphScanner.samMethodForJdkFunctionalInterface(asyncOwner);
            if ((sam != null && sam.equals(asyncMember))
                    || verifiedScaffold.contains(normAsync)
                    || isBenignInfrastructureSymbol(normAsync)) {
                verifiedAsync.add(normAsync);
            } else {
                unexpectedHooks.add(normAsync);
            }
        }

        boolean clean = customGenOverrides.isEmpty() && unexpectedHooks.isEmpty();
        return new StageBoundaryScanResult(
                boundaryGraph.stageId(),
                boundaryGraph,
                verifiedScaffold,
                verifiedAsync,
                unexpectedHooks,
                customGenOverrides,
                clean
        );
    }

    /**
     * Returns {@code true} strictly when {@code mutationSymbol} is an internal container/array write
     * marker ({@code UNKNOWN_MUTATION:<writer>} or {@code UNKNOWN_ARRAY:<writer>-><target>}) whose
     * writer method was verified as clean replaced scaffolding by Check A
     * ({@link StageBoundaryScanResult#boundaryContractClean()}).
     *
     * <p>Concrete carrier field symbols ({@code OwnerClass#fieldName}) always return {@code false}
     * so unmodeled carrier field mutations can never be masked by replaced scaffold verification.
     */
    public static boolean isVerifiedReplacedCarrierMutation(
            String mutationSymbol,
            StageBoundaryScanResult boundaryScan
    ) {
        if (boundaryScan == null
                || !boundaryScan.boundaryContractClean()
                || mutationSymbol == null
                || mutationSymbol.isEmpty()) {
            return false;
        }
        String writer;
        if (mutationSymbol.startsWith("UNKNOWN_MUTATION:")) {
            writer = normalizeRuntimeSymbol(mutationSymbol.substring("UNKNOWN_MUTATION:".length()));
        } else if (mutationSymbol.startsWith("UNKNOWN_ARRAY:")) {
            String rest = mutationSymbol.substring("UNKNOWN_ARRAY:".length());
            int arrow = rest.indexOf("->");
            writer = normalizeRuntimeSymbol(arrow > 0 ? rest.substring(0, arrow) : rest);
        } else {
            return false;
        }
        return !writer.isEmpty()
                && (boundaryScan.verifiedReplacedScaffoldMethods().contains(writer)
                        || isVerifiedBenignInfrastructureSymbol(writer));
    }

    /**
     * Emits stage-scoped {@link ProvenanceBinding} entries for internal stage scaffolding methods,
     * internal async targets, and internal container mutations verified by Check A
     * ({@code boundaryScan.boundaryContractClean() == true}), tagged with the Check B domain
     * fidelity kind ({@code IR_LIFTED} or {@code APPROXIMATE_EQUIVALENT} / {@code STAMP_ATLAS}).
     *
     * <p>Returns an empty map when Check A failed ({@code !boundaryContractClean}) or when Check B
     * did not establish an accounted domain provenance kind, and never emits bindings for concrete
     * {@code OwnerClass#fieldName} carrier field mutations.
     */
    public static Map<String, ProvenanceBinding> collectVerifiedReplacedScaffoldBindings(
            StageBoundaryScanResult boundaryScan,
            CoverageProvenanceKind domainStageKind,
            String mechanismPrefix
    ) {
        if (boundaryScan == null
                || !boundaryScan.boundaryContractClean()
                || domainStageKind == null
                || domainStageKind == CoverageProvenanceKind.UNACCOUNTED
                || domainStageKind == CoverageProvenanceKind.NATIVE_DELEGATED) {
            return Map.of();
        }
        String stageId = boundaryScan.stageId();
        String prefix = (mechanismPrefix == null || mechanismPrefix.isBlank())
                ? "VerifiedReplacedStageScaffold#" + stageId
                : mechanismPrefix;
        Map<String, ProvenanceBinding> out = new LinkedHashMap<>();
        for (String sym : boundaryScan.verifiedReplacedScaffoldMethods()) {
            String norm = normalizeRuntimeSymbol(sym);
            if (!norm.isEmpty()) {
                out.put(stageId + ":" + norm, new ProvenanceBinding(norm, domainStageKind, prefix + ":" + norm));
            }
        }
        for (String asyncSym : boundaryScan.verifiedInternalAsyncTargets()) {
            String norm = normalizeRuntimeSymbol(asyncSym);
            if (!norm.isEmpty()) {
                out.putIfAbsent(
                        stageId + ":" + norm,
                        new ProvenanceBinding(norm, domainStageKind, prefix + ":async:" + norm)
                );
            }
        }
        for (String mut : boundaryScan.boundaryGraph().mutatedCarrierFields()) {
            if (isVerifiedReplacedCarrierMutation(mut, boundaryScan)) {
                out.putIfAbsent(
                        stageId + ":" + mut,
                        new ProvenanceBinding(mut, domainStageKind, prefix + ":internal-mutation:" + mut)
                );
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static Set<String> computeStageSignatureTypeClosure(
            Function<String, byte[]> classByteResolver,
            StageCoverageGraph boundaryGraph,
            String normGenBase,
            Set<String> allowedGen
    ) {
        Set<String> closure = new LinkedHashSet<>(boundaryGraph.expandedStateCarrierClasses());
        if (!normGenBase.isEmpty()) {
            closure.add(normGenBase);
        }
        closure.addAll(allowedGen);
        for (String rootSym : boundaryGraph.rootMethodSymbols()) {
            String rootOwner = extractOwnerClass(rootSym);
            if (!rootOwner.isEmpty()) {
                closure.add(rootOwner);
                int dollar = rootOwner.indexOf('$');
                if (dollar > 0) {
                    closure.add(rootOwner.substring(0, dollar));
                }
            }
        }

        Deque<String> work = new ArrayDeque<>(closure);
        Set<String> expanded = new LinkedHashSet<>();
        int maxHops = 5;
        int maxExpandedClasses = 4096;
        Map<String, Integer> depthMap = new LinkedHashMap<>();
        for (String seed : closure) {
            depthMap.put(seed, 0);
        }

        while (!work.isEmpty() && expanded.size() < maxExpandedClasses) {
            String cls = work.poll();
            int depth = depthMap.getOrDefault(cls, 0);
            if (!expanded.add(cls)
                    || isBootstrapJdkOrLoggingClass(cls)
                    || isNonWorldgenInfrastructureClass(cls)) {
                continue;
            }
            byte[] bytes = classByteResolver.apply(cls);
            if (bytes == null) {
                continue;
            }
            ParsedClass parsed = parseClassBytesCached(bytes);
            if (parsed == null) {
                continue;
            }
            Set<String> neighbors = new LinkedHashSet<>();
            if (parsed.superClassName() != null && !parsed.superClassName().isEmpty()) {
                neighbors.add(parsed.superClassName());
            }
            neighbors.addAll(parsed.interfaces());
            if (depth < maxHops) {
                for (FieldDescriptorMeta fm : parsed.declaredFieldMeta().values()) {
                    collectDescriptorClassNames(fm.descriptor(), neighbors);
                }
                for (MethodNode m : parsed.methods().values()) {
                    if ((m.accessFlags() & BytecodeCausalGraphScanner.ACC_SYNTHETIC) == 0) {
                        collectDescriptorClassNames(m.descriptor(), neighbors);
                    }
                }
            }
            for (String n : neighbors) {
                if (!n.isEmpty()
                        && !isBootstrapJdkOrLoggingClass(n)
                        && !isNonWorldgenInfrastructureClass(n)
                        && closure.add(n)) {
                    depthMap.put(n, depth + 1);
                    work.add(n);
                }
            }
        }
        return closure;
    }

    private static void collectDescriptorClassNames(String descriptor, Set<String> out) {
        if (descriptor == null || descriptor.isEmpty()) {
            return;
        }
        int idx = 0;
        while (idx < descriptor.length()) {
            int lPos = descriptor.indexOf('L', idx);
            if (lPos < 0) {
                break;
            }
            int semi = descriptor.indexOf(';', lPos + 1);
            if (semi < 0) {
                break;
            }
            String cls = descriptor.substring(lPos + 1, semi).replace('/', '.');
            if (!cls.isEmpty()) {
                out.add(cls);
            }
            idx = semi + 1;
        }
    }

    private static boolean enclosingClassInClosure(String owner, Set<String> closure) {
        int dollar = owner.lastIndexOf('$');
        while (dollar > 0) {
            String outer = owner.substring(0, dollar);
            if (closure.contains(outer)) {
                return true;
            }
            dollar = outer.lastIndexOf('$');
        }
        return false;
    }

    private static boolean isMixinOrSyntheticHookMethod(
            Function<String, byte[]> classByteResolver,
            String owner,
            String member
    ) {
        if (owner.startsWith("[")) {
            return false;
        }
        String lowMember = member.toLowerCase(Locale.ROOT);
        if (lowMember.startsWith("handler$")
                || lowMember.startsWith("redirect$")
                || lowMember.startsWith("modify$")
                || lowMember.startsWith("modifyarg$")
                || lowMember.startsWith("modifyvar$")
                || lowMember.startsWith("wrapoperation$")
                || lowMember.startsWith("wrapmethod$")
                || lowMember.startsWith("md$")
                || lowMember.contains("$mixin$")) {
            return true;
        }
        String lowOwner = owner.toLowerCase(Locale.ROOT);
        if (lowOwner.contains(".mixin.") || lowOwner.endsWith("mixin")) {
            return true;
        }
        byte[] bytes = classByteResolver.apply(owner);
        if (bytes == null) {
            return true;
        }
        String latin = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        return latin.contains("org/spongepowered/asm/mixin/");
    }

    private static boolean isPureSideEffectFreePrimitiveLeaf(
            Function<String, byte[]> classByteResolver,
            String owner,
            String member
    ) {
        if ("<init>".equals(member) || "<clinit>".equals(member)) {
            return false;
        }
        byte[] bytes = classByteResolver.apply(owner);
        if (bytes == null) {
            return false;
        }
        ParsedClass parsed = parseClassBytesCached(bytes);
        if (parsed == null) {
            return false;
        }
        boolean matched = false;
        for (MethodNode m : parsed.methods().values()) {
            if (!member.equals(m.name())) {
                continue;
            }
            matched = true;
            String desc = m.descriptor();
            int rparen = desc.lastIndexOf(')');
            if (rparen < 0 || rparen + 1 >= desc.length()) {
                return false;
            }
            char retChar = desc.charAt(rparen + 1);
            if (retChar != 'Z'
                    && retChar != 'B'
                    && retChar != 'S'
                    && retChar != 'C'
                    && retChar != 'I'
                    && retChar != 'J'
                    && retChar != 'F'
                    && retChar != 'D') {
                return false;
            }
            if (!combineMutatedFields(m).isEmpty()
                    || !m.producedQueueFields().isEmpty()
                    || !m.dynamicFunctionalDispatches().isEmpty()
                    || !m.asyncFunctionalDispatches().isEmpty()) {
                return false;
            }
            for (String called : m.calledMethods()) {
                String calleeOwner = extractOwnerClass(stripMethodDescriptor(called));
                if (!calleeOwner.equals(owner) && !isBootstrapJdkOrLoggingClass(calleeOwner)) {
                    return false;
                }
            }
        }
        return matched;
    }

    private static StageCoverageGraph emptyStageGraph(String stageId) {
        return new StageCoverageGraph(
                stageId,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                List.of(),
                List.of(),
                false,
                Set.of(),
                0
        );
    }
}
