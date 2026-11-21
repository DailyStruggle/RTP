package io.github.dailystruggle.bytecode;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.dailystruggle.bytecode.PipelineCoverageGraphScanner.CoverageProvenanceKind;
import io.github.dailystruggle.bytecode.PipelineCoverageGraphScanner.CoverageProvenanceLedger;
import io.github.dailystruggle.bytecode.PipelineCoverageGraphScanner.EdgeKind;
import io.github.dailystruggle.bytecode.PipelineCoverageGraphScanner.ProvenanceBinding;
import io.github.dailystruggle.bytecode.PipelineCoverageGraphScanner.ProvenanceEvaluationPolicy;
import io.github.dailystruggle.bytecode.PipelineCoverageGraphScanner.StageCoverageGraph;
import io.github.dailystruggle.bytecode.PipelineCoverageGraphScanner.StageCoverageSlice;
import io.github.dailystruggle.bytecode.PipelineCoverageGraphScanner.StageCoverageVerdict;
import io.github.dailystruggle.bytecode.PipelineCoverageGraphScanner.StageRuntimeObservation;
import io.github.dailystruggle.bytecode.PipelineCoverageGraphScanner.StageSliceSpec;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit and boundary verification for {@link PipelineCoverageGraphScanner} and
 * {@link CoverageProvenanceLedger} ({@code ADR-115}, {@code ADR-125}, {@code ADR-126},
 * {@code leaffuzz-ADR-001}, {@code corpus-atlas-ADR-002}).
 */
@DisplayName("PipelineCoverageGraphScanner & CoverageProvenanceLedger Tests")
class PipelineCoverageGraphScannerTest {

    // =========================================================================
    // Synthetic Pipeline Carrier & Worldgen Fixtures (Pure Java)
    // =========================================================================

    static class BaseChunkCarrier {
        int writeIdx = 2;
        int[] sections = new int[16];
        int[][] sectionMatrix = new int[4][4];
        int[] biomes = new int[64];
        Object[] objectSlots = new Object[8];
        byte[] rawBytes = new byte[32];
        Map<String, long[]> heightmaps = new HashMap<>();
        Map<Long, Object> blockEntities = new HashMap<>();
        Object blendingData;
        long[] moonriseHeightmapRaw;
        AtomicReferenceArray<Object> stepFutures = new AtomicReferenceArray<>(8);
        AtomicReference<Object> lightState = new AtomicReference<>();
        AtomicInteger ticketLevel = new AtomicInteger();
        BitSet carvingBitSet = new BitSet(64);
        ByteBuffer sectionByteBuf = ByteBuffer.allocate(32);
        ThreadLocal<Object> workerScratch = new ThreadLocal<>();
        CompletableFuture<Object> fullChunkFuture = new CompletableFuture<>();

        AtomicReferenceArray<Object> getStepFutures() {
            return this.stepFutures;
        }

        Map<Long, Object> getBlockEntities() {
            return this.blockEntities;
        }

        int[] getSections() {
            return this.sections;
        }

        int[] selectOpaqueArray(int mode) {
            return mode == 0 ? new int[4] : new int[8];
        }
    }

    static final class ProtoChunkCarrier extends BaseChunkCarrier {
        int carvingMaskBits;
    }

    interface DensitySampler {
        double sample(int x, int y, int z);
    }

    abstract static class BasePerlinSampler implements DensitySampler {
        double scaleValue(double v) {
            return Math.max(-1.0, Math.min(1.0, v * 0.5));
        }
    }

    static final class ShiftedPerlinSampler extends BasePerlinSampler {
        @Override
        public double sample(int x, int y, int z) {
            return scaleValue((x * 31 + y * 17 + z) * 0.01);
        }
    }

    static final class UnmappedCustomDatapackSampler implements DensitySampler {
        @Override
        public double sample(int x, int y, int z) {
            return Math.sin(x + z) + y;
        }
    }

    interface SurfaceCondition {
        boolean test(BaseChunkCarrier chunk, int x, int z);
    }

    enum SteepSlopeCondition implements SurfaceCondition {
        INSTANCE;

        @Override
        public boolean test(BaseChunkCarrier chunk, int x, int z) {
            return (x ^ z) != 0 && chunk != null;
        }
    }

    interface UnimplementedExternalHook {
        void onAfterSurface(BaseChunkCarrier chunk);
    }

    static final class OakTreeStampPlacer {
        static void placeCanopy(ProtoChunkCarrier chunk) {
            chunk.getSections()[1] = 99;
            chunk.getBlockEntities().put(100L, "sign");
        }
    }

    static final class CaveCarverFunctor implements Runnable {
        private final ProtoChunkCarrier carrier;

        CaveCarverFunctor(ProtoChunkCarrier carrier) {
            this.carrier = Objects.requireNonNull(carrier);
        }

        @Override
        public void run() {
            this.carrier.carvingMaskBits |= 0xFF;
        }
    }

    static final class SneakyConstructorHelper {
        SneakyConstructorHelper(ProtoChunkCarrier carrier) {
            SneakyHook.mutateHiddenState(carrier);
        }

        int computeValue() {
            return 7;
        }
    }

    static final class SneakyHook {
        static void mutateHiddenState(ProtoChunkCarrier carrier) {
            carrier.blendingData = "sneaky";
        }
    }

    static final class CustomPaperSurfaceHook {
        static void injectCustomRule(ProtoChunkCarrier chunk) {
            chunk.blendingData = "custom-paper-hook";
        }
    }

    static final class SyntheticPipelineStages {
        private final DensitySampler sharedImmutableSampler;

        SyntheticPipelineStages(DensitySampler sampler) {
            this.sharedImmutableSampler = sampler;
        }

        CompletableFuture<ProtoChunkCarrier> dispatchRequest(int cx, int cz, Executor executor) {
            return CompletableFuture.supplyAsync(() -> createEmptyCarrier(cx, cz), executor)
                    .thenApply(this::scheduleBiomesStep);
        }

        ProtoChunkCarrier createEmptyCarrier(int cx, int cz) {
            ProtoChunkCarrier c = new ProtoChunkCarrier();
            if (cx == cz) {
                return c;
            }
            return c;
        }

        ProtoChunkCarrier scheduleBiomesStep(ProtoChunkCarrier chunk) {
            return populateBiomes(chunk);
        }

        ProtoChunkCarrier populateBiomes(ProtoChunkCarrier chunk) {
            chunk.biomes[0] = 4;
            return chunk;
        }

        CompletableFuture<ProtoChunkCarrier> runNoiseStage(ProtoChunkCarrier chunk, Executor executor) {
            double density = this.sharedImmutableSampler.sample(0, 64, 0);
            return CompletableFuture.supplyAsync(() -> fillNoiseSections(chunk, density), executor);
        }

        ProtoChunkCarrier fillNoiseSections(ProtoChunkCarrier chunk, double density) {
            chunk.sections[0] = density > 0.0 ? 1 : 0;
            chunk.heightmaps.put("WORLD_SURFACE_WG", new long[]{64L});
            return chunk;
        }

        CompletableFuture<ProtoChunkCarrier> runCleanSurfaceStage(
                ProtoChunkCarrier chunk,
                SurfaceCondition cond,
                Executor executor
        ) {
            return CompletableFuture.supplyAsync(() -> {
                if (cond.test(chunk, 0, 0)) {
                    applyTopSoil(chunk);
                }
                return chunk;
            }, executor);
        }

        static void applyTopSoil(ProtoChunkCarrier chunk) {
            chunk.sections[0] = 2;
        }

        CompletableFuture<ProtoChunkCarrier> runHookedSurfaceStage(
                ProtoChunkCarrier chunk,
                SurfaceCondition cond,
                Executor executor
        ) {
            // Fire-and-forget async supplier whose return value is popped (0x57) must still be traced!
            CompletableFuture.supplyAsync(() -> {
                CustomPaperSurfaceHook.injectCustomRule(chunk);
                return Boolean.TRUE;
            });
            return CompletableFuture.supplyAsync(() -> {
                if (cond.test(chunk, 1, 1)) {
                    applyTopSoil(chunk);
                }
                return chunk;
            }, executor);
        }

        void runCarversAndFeaturesStage(ProtoChunkCarrier chunk, Executor executor) {
            executor.execute(new CaveCarverFunctor(chunk));
            CompletableFuture.runAsync(() -> OakTreeStampPlacer.placeCanopy(chunk), executor);
        }

        byte[] serializeOutboundChunkResponse(BaseChunkCarrier chunk) {
            int s0 = chunk.sections[0];
            int b0 = chunk.biomes[0];
            long[] hm = chunk.heightmaps.get("WORLD_SURFACE_WG");
            Object be = chunk.blockEntities.get(100L);
            Object blend = chunk.blendingData;
            return new byte[]{(byte) (s0 + b0 + (hm != null ? 1 : 0) + (be != null ? 1 : 0) + (blend != null ? 1 : 0))};
        }
    }

    // Queue & Mutable-Field Handoff Fixtures
    static final class ChunkTaskMailbox {
        private final Queue<Runnable> pendingQueue = new ArrayDeque<>();
        private Runnable stagedCallback;

        void enqueueViaQueue(ProtoChunkCarrier chunk) {
            this.pendingQueue.offer(() -> QueueWorkerLogic.processQueuedChunk(chunk));
        }

        void drainQueueLoop() {
            Runnable next = this.pendingQueue.poll();
            if (next != null) {
                next.run();
            }
        }

        void publishMutableField(ProtoChunkCarrier chunk) {
            this.stagedCallback = () -> MutableFieldLogic.processFieldHandoff(chunk);
        }

        void consumeMutableField() {
            Runnable cb = this.stagedCallback;
            if (cb != null) {
                cb.run();
            }
        }
    }

    static final class QueueWorkerLogic {
        static void processQueuedChunk(ProtoChunkCarrier chunk) {
            chunk.sections[2] = 7;
        }
    }

    static final class MutableFieldLogic {
        static void processFieldHandoff(ProtoChunkCarrier chunk) {
            chunk.sections[3] = 8;
        }
    }

    static final class OrphanQueueProducer {
        private final Queue<Runnable> blackHoleQueue = new ArrayDeque<>();

        void offerWithoutAnyConsumer() {
            this.blackHoleQueue.offer(() -> {
            });
        }
    }

    static final class StageDriver {
        static void runQueueStage(ChunkTaskMailbox mailbox, ProtoChunkCarrier c) {
            mailbox.enqueueViaQueue(c);
        }

        static void runFieldStage(ChunkTaskMailbox mailbox, ProtoChunkCarrier c) {
            mailbox.publishMutableField(c);
        }

        static int runSneakyCtorStage(ProtoChunkCarrier c) {
            return new SneakyConstructorHelper(c).computeValue();
        }
    }

    static final class AbstractHookCaller {
        static void invokeExternal(UnimplementedExternalHook hook, BaseChunkCarrier c) {
            hook.onAfterSurface(c);
        }
    }

    // Deep >4-hop chain & cycle fixtures
    static final class DeepAndCyclicChain {
        static int hop0(int v) {
            return hop1(v + 1);
        }

        static int hop1(int v) {
            return hop2(v + 1);
        }

        static int hop2(int v) {
            return hop3(v + 1);
        }

        static int hop3(int v) {
            return hop4(v + 1);
        }

        static int hop4(int v) {
            return hop5(v + 1);
        }

        static int hop5(int v) {
            return hop6(v + 1);
        }

        static int hop6(int v) {
            return v * 2;
        }

        static int cycleA(int n) {
            return n <= 0 ? 0 : cycleB(n - 1);
        }

        static int cycleB(int n) {
            return n <= 0 ? 1 : cycleA(n - 1);
        }
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private static Function<String, byte[]> resolverFor(Class<?>... allowedClasses) {
        Map<String, byte[]> table = new LinkedHashMap<>();
        for (Class<?> cls : allowedClasses) {
            byte[] bytes = readClassBytes(cls);
            table.put(cls.getName(), bytes);
            table.put(cls.getName().replace('.', '/'), bytes);
        }
        return table::get;
    }

    private static byte[] readClassBytes(Class<?> cls) {
        String resourcePath = cls.getName().replace('.', '/') + ".class";
        try (InputStream in = cls.getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(in, "Missing .class resource for " + cls.getName());
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading .class bytes for " + cls.getName(), e);
        }
    }

    private static String sym(Class<?> owner, String member) {
        return owner.getName() + "#" + member;
    }

    private static void bind(
            Map<String, ProvenanceBinding> map,
            String symbolOrField,
            CoverageProvenanceKind kind,
            String ref
    ) {
        map.put(symbolOrField, new ProvenanceBinding(symbolOrField, kind, ref));
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("100% accounted connected pipeline enables stage patching and tracks carrier dataflow")
    void fullyAccountedPipelineEnablesStagePatching() {
        Function<String, byte[]> resolver = resolverFor(
                BaseChunkCarrier.class,
                ProtoChunkCarrier.class,
                DensitySampler.class,
                BasePerlinSampler.class,
                ShiftedPerlinSampler.class,
                SurfaceCondition.class,
                SteepSlopeCondition.class,
                OakTreeStampPlacer.class,
                CaveCarverFunctor.class,
                SyntheticPipelineStages.class
        );

        Set<String> carriers = Set.of(BaseChunkCarrier.class.getName(), ProtoChunkCarrier.class.getName());
        List<StageSliceSpec> stages = List.of(
                new StageSliceSpec(
                        "REQUEST_DISPATCH",
                        List.of(sym(SyntheticPipelineStages.class, "dispatchRequest")),
                        carriers,
                        Set.of(sym(SyntheticPipelineStages.class, "populateBiomes")),
                        Set.of()
                ),
                new StageSliceSpec(
                        "BIOMES",
                        List.of(sym(SyntheticPipelineStages.class, "populateBiomes")),
                        carriers
                ),
                new StageSliceSpec(
                        "NOISE",
                        List.of(sym(SyntheticPipelineStages.class, "runNoiseStage")),
                        carriers,
                        Set.of(),
                        Set.of(ShiftedPerlinSampler.class.getName())
                ),
                new StageSliceSpec(
                        "SURFACE",
                        List.of(sym(SyntheticPipelineStages.class, "runCleanSurfaceStage")),
                        carriers,
                        Set.of(),
                        Set.of(SteepSlopeCondition.class.getName())
                ),
                new StageSliceSpec(
                        "FEATURES",
                        List.of(sym(SyntheticPipelineStages.class, "runCarversAndFeaturesStage")),
                        carriers
                ),
                new StageSliceSpec(
                        "RESPONSE_SEND",
                        List.of(sym(SyntheticPipelineStages.class, "serializeOutboundChunkResponse")),
                        carriers
                )
        );

        Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                resolver,
                List.of(ShiftedPerlinSampler.class.getName(), SteepSlopeCondition.class.getName()),
                stages,
                12,
                256
        );

        // Verify polymorphic interface -> abstract base -> concrete subtype AND enum interface dispatch
        StageCoverageGraph noiseGraph = graphs.get("NOISE");
        assertTrue(noiseGraph.reachableMethods().contains(sym(ShiftedPerlinSampler.class, "sample")));
        assertTrue(noiseGraph.reachableMethods().contains(sym(BasePerlinSampler.class, "scaleValue")));
        assertTrue(noiseGraph.edges().stream().anyMatch(e -> e.kind() == EdgeKind.INTERFACE_SUBTYPE_CALL));
        assertTrue(noiseGraph.edges().stream().anyMatch(e -> e.kind() == EdgeKind.INDY_LAMBDA));
        assertTrue(noiseGraph.edges().stream().anyMatch(e -> e.kind() == EdgeKind.ASYNC_FUTURE_OR_SCHEDULER));

        StageCoverageGraph surfaceGraph = graphs.get("SURFACE");
        assertTrue(
                surfaceGraph.reachableMethods().contains(sym(SteepSlopeCondition.class, "test")),
                "Enum concrete subtype implementing interface must be resolved"
        );

        // Verify functor + array getter + collection getter carrier field mutations in FEATURES
        StageCoverageGraph featuresGraph = graphs.get("FEATURES");
        assertTrue(featuresGraph.reachableMethods().contains(sym(CaveCarverFunctor.class, "run")));
        assertTrue(featuresGraph.reachableMethods().contains(sym(OakTreeStampPlacer.class, "placeCanopy")));
        assertTrue(featuresGraph.edges().stream().anyMatch(e -> e.kind() == EdgeKind.FUNCTOR_TASK));
        assertTrue(featuresGraph.mutatedCarrierFields().contains(sym(ProtoChunkCarrier.class, "carvingMaskBits")));
        assertTrue(featuresGraph.mutatedCarrierFields().contains(sym(BaseChunkCarrier.class, "sections")));
        assertTrue(featuresGraph.mutatedCarrierFields().contains(sym(BaseChunkCarrier.class, "blockEntities")));

        // Verify cross-stage carrier field producer -> consumer edges from NOISE/BIOMES/FEATURES to RESPONSE_SEND
        StageCoverageGraph responseGraph = graphs.get("RESPONSE_SEND");
        assertFalse(responseGraph.crossStageDataflowEdges().isEmpty());
        assertTrue(responseGraph.readCarrierFields().contains(sym(BaseChunkCarrier.class, "sections")));
        assertTrue(responseGraph.readCarrierFields().contains(sym(BaseChunkCarrier.class, "heightmaps")));

        // Bind 100% of candidate stage methods & carrier fields
        Map<String, ProvenanceBinding> bindings = new LinkedHashMap<>();
        bind(bindings, "STAGE:REQUEST_DISPATCH", CoverageProvenanceKind.NATIVE_DELEGATED, "vanilla-chunk-scheduler");
        bind(bindings, "STAGE:RESPONSE_SEND", CoverageProvenanceKind.NATIVE_DELEGATED, "vanilla-chunk-sender");

        bind(bindings, sym(SyntheticPipelineStages.class, "populateBiomes"), CoverageProvenanceKind.IR_LIFTED, "BiomeLut");
        bind(bindings, sym(BaseChunkCarrier.class, "biomes"), CoverageProvenanceKind.IR_LIFTED, "BiomeHydrator");

        for (String m : noiseGraph.reachableMethods()) {
            if (!noiseGraph.abstractDispatchMethods().contains(m)) {
                bind(bindings, m, CoverageProvenanceKind.IR_LIFTED, "BytecodeSsaLifter#noise");
            }
        }
        bind(bindings, sym(BaseChunkCarrier.class, "sections"), CoverageProvenanceKind.IR_LIFTED, "SectionHydrator");
        bind(bindings, sym(BaseChunkCarrier.class, "heightmaps"), CoverageProvenanceKind.IR_LIFTED, "HeightmapHydrator");

        for (String m : surfaceGraph.reachableMethods()) {
            if (!surfaceGraph.abstractDispatchMethods().contains(m)) {
                bind(bindings, m, CoverageProvenanceKind.IR_LIFTED, "SurfaceRulesVm");
            }
        }

        for (String m : featuresGraph.reachableMethods()) {
            if (!featuresGraph.abstractDispatchMethods().contains(m)) {
                bind(bindings, m, CoverageProvenanceKind.STAMP_ATLAS, "CompiledGpuStampAtlas#oak_and_carver");
            }
        }
        bind(bindings, sym(ProtoChunkCarrier.class, "carvingMaskBits"), CoverageProvenanceKind.STAMP_ATLAS, "CarverMask");
        bind(bindings, sym(BaseChunkCarrier.class, "blockEntities"), CoverageProvenanceKind.STAMP_ATLAS, "StampBlockEntities");

        // Include live canary runtime frames with JVM $$Lambda/0x... hidden-class proxy names
        Map<String, StageRuntimeObservation> runtimeObs = Map.of(
                "NOISE",
                new StageRuntimeObservation(
                        "NOISE",
                        Set.of(
                                "app//" + SyntheticPipelineStages.class.getName() + "$$Lambda$42/0x00007f8a1c00#get",
                                "java.base@21.0.2/java.util.concurrent.CompletableFuture$AsyncSupply#run"
                        ),
                        Set.of(sym(BaseChunkCarrier.class, "sections")),
                        Set.of(),
                        Set.of("ForkJoinPool.commonPool-worker-1")
                )
        );

        Set<String> responseRequired = Set.of(
                sym(BaseChunkCarrier.class, "sections"),
                sym(BaseChunkCarrier.class, "biomes"),
                sym(BaseChunkCarrier.class, "heightmaps"),
                sym(BaseChunkCarrier.class, "blockEntities")
        );

        CoverageProvenanceLedger strictDefaultLedger = CoverageProvenanceLedger.reconcile(
                graphs,
                runtimeObs,
                bindings,
                responseRequired
        );
        for (String exactStageId : List.of("BIOMES", "NOISE", "SURFACE")) {
            assertTrue(
                    strictDefaultLedger.isStagePatchEligible(exactStageId),
                    "Expected IR_LIFTED stage eligible under STRICT_EXACT default: " + exactStageId
            );
        }
        assertFalse(
                strictDefaultLedger.isStagePatchEligible("FEATURES"),
                "STAMP_ATLAS stage must be rejected under STRICT_EXACT default"
        );
        assertTrue(
                strictDefaultLedger.unaccountedResponseRequiredFields()
                        .contains(sym(BaseChunkCarrier.class, "blockEntities"))
        );

        CoverageProvenanceLedger ledger = CoverageProvenanceLedger.reconcile(
                graphs,
                runtimeObs,
                bindings,
                responseRequired,
                ProvenanceEvaluationPolicy.STATISTICAL_VANILLA_LIKE
        );

        for (String stageId : List.of("BIOMES", "NOISE", "SURFACE", "FEATURES")) {
            StageCoverageVerdict v = ledger.verdictForStage(stageId);
            assertTrue(v.patchEligible(), "Expected patchEligible for " + stageId + " but got: " + v);
            assertEquals(0, v.unaccountedMethodCount());
            assertEquals(0, v.unaccountedFieldCount());
            assertTrue(v.unaccountedAsyncHops().isEmpty());
            assertTrue(v.witnessPathsToUnaccounted().isEmpty());
        }
        for (String delegatedStage : List.of("REQUEST_DISPATCH", "RESPONSE_SEND")) {
            StageCoverageVerdict dv = ledger.verdictForStage(delegatedStage);
            assertFalse(
                    dv.patchEligible(),
                    "NATIVE_DELEGATED stage must not be marked patchEligible: " + delegatedStage
            );
            assertTrue(dv.nativeDelegated(), "Expected nativeDelegated=true for " + delegatedStage);
            assertFalse(ledger.isStagePatchEligible(delegatedStage));
            assertTrue(ledger.isStageNativeDelegated(delegatedStage));
            assertEquals(0, dv.unaccountedMethodCount());
            assertEquals(0, dv.unaccountedFieldCount());
        }
        assertTrue(ledger.unaccountedResponseRequiredFields().isEmpty());
    }

    @Test
    @DisplayName("Unaccounted lambda hook or subtype blocks only the affected stage with witness path")
    void unaccountedHookBlocksAffectedStageWithWitnessPathWhileKeepingUpstreamEligible() {
        Function<String, byte[]> resolver = resolverFor(
                BaseChunkCarrier.class,
                ProtoChunkCarrier.class,
                SurfaceCondition.class,
                SteepSlopeCondition.class,
                CustomPaperSurfaceHook.class,
                SyntheticPipelineStages.class
        );
        Set<String> carriers = Set.of(BaseChunkCarrier.class.getName());

        List<StageSliceSpec> stages = List.of(
                new StageSliceSpec(
                        "BIOMES",
                        List.of(sym(SyntheticPipelineStages.class, "populateBiomes")),
                        carriers
                ),
                new StageSliceSpec(
                        "SURFACE",
                        List.of(sym(SyntheticPipelineStages.class, "runHookedSurfaceStage")),
                        carriers
                )
        );

        Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                resolver,
                List.of(SteepSlopeCondition.class.getName()),
                stages,
                10,
                128
        );

        // Notice fire-and-forget popped CompletableFuture.supplyAsync lambda WAS traced (not skipped as orphan)!
        StageCoverageGraph surfaceGraph = graphs.get("SURFACE");
        assertTrue(
                surfaceGraph.reachableMethods().contains(sym(CustomPaperSurfaceHook.class, "injectCustomRule")),
                "Fire-and-forget async lambda must be traced fail-closed"
        );

        Map<String, ProvenanceBinding> bindings = new LinkedHashMap<>();
        bind(bindings, sym(SyntheticPipelineStages.class, "populateBiomes"), CoverageProvenanceKind.IR_LIFTED, "BiomeLut");
        bind(bindings, sym(BaseChunkCarrier.class, "biomes"), CoverageProvenanceKind.IR_LIFTED, "BiomeHydrator");

        // Bind clean surface methods, leaving CustomPaperSurfaceHook + its lambda + blendingData unmapped
        bind(bindings, sym(SyntheticPipelineStages.class, "runHookedSurfaceStage"), CoverageProvenanceKind.IR_LIFTED, "SurfaceVm");
        bind(bindings, sym(SyntheticPipelineStages.class, "applyTopSoil"), CoverageProvenanceKind.IR_LIFTED, "SurfaceVm");
        bind(bindings, sym(SteepSlopeCondition.class, "test"), CoverageProvenanceKind.IR_LIFTED, "SurfaceVm");
        bind(bindings, sym(BaseChunkCarrier.class, "sections"), CoverageProvenanceKind.IR_LIFTED, "SectionHydrator");

        Map<String, StageCoverageVerdict> verdicts = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                graphs,
                Map.of(),
                bindings,
                Set.of(sym(BaseChunkCarrier.class, "sections"), sym(BaseChunkCarrier.class, "blendingData"))
        );

        // Upstream BIOMES remains 100% eligible
        assertTrue(verdicts.get("BIOMES").patchEligible());

        // SURFACE fails closed with witness chain root -> lambda$... -> CustomPaperSurfaceHook#injectCustomRule
        StageCoverageVerdict surfaceVerdict = verdicts.get("SURFACE");
        assertFalse(surfaceVerdict.patchEligible());
        assertTrue(surfaceVerdict.unaccountedMethods().contains(sym(CustomPaperSurfaceHook.class, "injectCustomRule")));
        assertTrue(surfaceVerdict.unaccountedFields().contains(sym(BaseChunkCarrier.class, "blendingData")));
        assertFalse(surfaceVerdict.unaccountedAsyncHops().isEmpty());
        assertFalse(surfaceVerdict.witnessPathsToUnaccounted().isEmpty());
        assertTrue(
                surfaceVerdict.witnessPathsToUnaccounted().stream()
                        .anyMatch(e -> e.toSymbol().equals(sym(CustomPaperSurfaceHook.class, "injectCustomRule"))),
                "Witness path must reach unaccounted CustomPaperSurfaceHook#injectCustomRule"
        );
    }

    @Test
    @DisplayName("Scoped concrete receiver filtering isolates dimension receivers from unused JAR-wide subtypes")
    void scopedConcreteClassesPreventsUnusedMegamorphicSubtypeFalsePositives() {
        Function<String, byte[]> resolver = resolverFor(
                BaseChunkCarrier.class,
                ProtoChunkCarrier.class,
                DensitySampler.class,
                BasePerlinSampler.class,
                ShiftedPerlinSampler.class,
                UnmappedCustomDatapackSampler.class,
                SyntheticPipelineStages.class
        );
        List<String> jarSeeds = List.of(
                ShiftedPerlinSampler.class.getName(),
                UnmappedCustomDatapackSampler.class.getName()
        );

        // 1. JAR-wide unscoped slice includes both ShiftedPerlinSampler and UnmappedCustomDatapackSampler
        Map<String, StageCoverageGraph> jarWide = PipelineCoverageGraphScanner.traceConnectedPipeline(
                resolver,
                jarSeeds,
                List.of(new StageSliceSpec(
                        "NOISE_JAR_WIDE",
                        List.of(sym(SyntheticPipelineStages.class, "runNoiseStage")),
                        Set.of(BaseChunkCarrier.class.getName())
                )),
                10,
                128
        );
        assertTrue(jarWide.get("NOISE_JAR_WIDE").reachableMethods()
                .contains(sym(UnmappedCustomDatapackSampler.class, "sample")));

        // 2. Dimension-scoped slice restricts polymorphic DensitySampler#sample to active ShiftedPerlinSampler
        Map<String, StageCoverageGraph> dimScoped = PipelineCoverageGraphScanner.traceConnectedPipeline(
                resolver,
                jarSeeds,
                List.of(new StageSliceSpec(
                        "NOISE_OVERWORLD",
                        List.of(sym(SyntheticPipelineStages.class, "runNoiseStage")),
                        Set.of(BaseChunkCarrier.class.getName()),
                        Set.of(),
                        Set.of(ShiftedPerlinSampler.class.getName())
                )),
                10,
                128
        );
        assertTrue(dimScoped.get("NOISE_OVERWORLD").reachableMethods()
                .contains(sym(ShiftedPerlinSampler.class, "sample")));
        assertFalse(dimScoped.get("NOISE_OVERWORLD").reachableMethods()
                .contains(sym(UnmappedCustomDatapackSampler.class, "sample")));
    }

    @Test
    @DisplayName("Queue producer-to-consumer, mutable field handoffs, and side-effecting constructors are connected")
    void tracesQueueDataflowMutableFieldHandoffsAndSideEffectingConstructors() {
        Function<String, byte[]> resolver = resolverFor(
                BaseChunkCarrier.class,
                ProtoChunkCarrier.class,
                ChunkTaskMailbox.class,
                QueueWorkerLogic.class,
                MutableFieldLogic.class,
                OrphanQueueProducer.class,
                SneakyConstructorHelper.class,
                SneakyHook.class,
                StageDriver.class
        );

        Set<String> carriers = Set.of(BaseChunkCarrier.class.getName());
        Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                resolver,
                List.of(
                        new StageSliceSpec("QUEUE_STAGE", List.of(sym(StageDriver.class, "runQueueStage")), carriers),
                        new StageSliceSpec("FIELD_STAGE", List.of(sym(StageDriver.class, "runFieldStage")), carriers),
                        new StageSliceSpec("ORPHAN_QUEUE", List.of(sym(OrphanQueueProducer.class, "offerWithoutAnyConsumer")), carriers),
                        new StageSliceSpec("SNEAKY_CTOR", List.of(sym(StageDriver.class, "runSneakyCtorStage")), carriers)
                ),
                10,
                128
        );

        // Queue producer -> drainQueueLoop -> lambda -> QueueWorkerLogic#processQueuedChunk
        StageCoverageGraph qGraph = graphs.get("QUEUE_STAGE");
        assertTrue(qGraph.edges().stream().anyMatch(e -> e.kind() == EdgeKind.QUEUE_DATAFLOW));
        assertTrue(qGraph.reachableMethods().contains(sym(ChunkTaskMailbox.class, "drainQueueLoop")));
        assertTrue(qGraph.reachableMethods().contains(sym(QueueWorkerLogic.class, "processQueuedChunk")));
        assertTrue(qGraph.mutatedCarrierFields().contains(sym(BaseChunkCarrier.class, "sections")));

        // Mutable field publish -> consumeMutableField -> lambda -> MutableFieldLogic#processFieldHandoff
        StageCoverageGraph fGraph = graphs.get("FIELD_STAGE");
        assertTrue(fGraph.edges().stream().anyMatch(
                e -> e.kind() == EdgeKind.FIELD_DATAFLOW
                        && e.toSymbol().equals(sym(ChunkTaskMailbox.class, "consumeMutableField"))
        ));
        assertTrue(fGraph.reachableMethods().contains(sym(MutableFieldLogic.class, "processFieldHandoff")));

        // Orphan queue with 0 consumers fails closed on unconsumed queue handoff
        Map<String, ProvenanceBinding> orphanBindings = new LinkedHashMap<>();
        for (String m : graphs.get("ORPHAN_QUEUE").reachableMethods()) {
            bind(orphanBindings, m, CoverageProvenanceKind.IR_LIFTED, "lifted");
        }
        StageCoverageVerdict orphanVerdict = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                Map.of("ORPHAN_QUEUE", graphs.get("ORPHAN_QUEUE")),
                Map.of(),
                orphanBindings,
                Set.of()
        ).get("ORPHAN_QUEUE");
        assertFalse(orphanVerdict.patchEligible(), "Queue handoff with zero consumers must fail closed");
        assertTrue(orphanVerdict.unaccountedAsyncHops().contains(sym(OrphanQueueProducer.class, "blackHoleQueue")));

        // Side-effecting <init> constructor exposes SneakyHook#mutateHiddenState and blendingData mutation
        StageCoverageGraph ctorGraph = graphs.get("SNEAKY_CTOR");
        assertTrue(ctorGraph.reachableMethods().contains(sym(SneakyHook.class, "mutateHiddenState")));
        assertTrue(ctorGraph.mutatedCarrierFields().contains(sym(BaseChunkCarrier.class, "blendingData")));
    }

    @Test
    @DisplayName("Traverses >4 hops without plugin command cap, handles cycles, and fails closed on bound truncation")
    void traversesDeepChainsHandlesCyclesAndFailsClosedOnTruncation() {
        Function<String, byte[]> resolver = resolverFor(DeepAndCyclicChain.class);

        // 1. 7-node chain (depth 6 > old 4-hop cap) completes cleanly when bounds allow
        Map<String, StageCoverageGraph> fullChain = PipelineCoverageGraphScanner.traceConnectedPipeline(
                resolver,
                List.of(new StageSliceSpec("DEEP_OK", List.of(sym(DeepAndCyclicChain.class, "hop0")), Set.of())),
                10,
                32
        );
        StageCoverageGraph okGraph = fullChain.get("DEEP_OK");
        assertFalse(okGraph.truncatedByBounds());
        assertEquals(6, okGraph.maxDepthReached());
        assertTrue(okGraph.reachableMethods().contains(sym(DeepAndCyclicChain.class, "hop6")));

        // 2. Hop bound truncation (maxHopsPerStage = 2) halts at hop2, flags hop3 on frontier, and fails closed
        Map<String, StageCoverageGraph> hopTruncated = PipelineCoverageGraphScanner.traceConnectedPipeline(
                resolver,
                List.of(new StageSliceSpec("HOP_CAP", List.of(sym(DeepAndCyclicChain.class, "hop0")), Set.of())),
                2,
                32
        );
        StageCoverageGraph hopGraph = hopTruncated.get("HOP_CAP");
        assertTrue(hopGraph.truncatedByBounds());
        assertTrue(hopGraph.reachableMethods().contains(sym(DeepAndCyclicChain.class, "hop2")));
        assertFalse(hopGraph.reachableMethods().contains(sym(DeepAndCyclicChain.class, "hop3")));
        assertTrue(hopGraph.unexpandedFrontierSymbols().contains(sym(DeepAndCyclicChain.class, "hop3")));

        Map<String, ProvenanceBinding> prefixBindings = new LinkedHashMap<>();
        bind(prefixBindings, sym(DeepAndCyclicChain.class, "hop0"), CoverageProvenanceKind.IR_LIFTED, "ir");
        bind(prefixBindings, sym(DeepAndCyclicChain.class, "hop1"), CoverageProvenanceKind.IR_LIFTED, "ir");
        bind(prefixBindings, sym(DeepAndCyclicChain.class, "hop2"), CoverageProvenanceKind.IR_LIFTED, "ir");
        StageCoverageVerdict hopVerdict = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                hopTruncated,
                Map.of(),
                prefixBindings,
                Set.of()
        ).get("HOP_CAP");
        assertFalse(hopVerdict.patchEligible(), "Truncated static graph must fail closed");
        assertTrue(hopVerdict.unaccountedMethods().contains(sym(DeepAndCyclicChain.class, "hop3")));

        // 3. Node budget truncation (maxNodesPerStage = 3) halts after 3 methods and fails closed
        Map<String, StageCoverageGraph> nodeTruncated = PipelineCoverageGraphScanner.traceConnectedPipeline(
                resolver,
                List.of(new StageSliceSpec("NODE_CAP", List.of(sym(DeepAndCyclicChain.class, "hop0")), Set.of())),
                10,
                3
        );
        assertEquals(3, nodeTruncated.get("NODE_CAP").reachableMethods().size());
        assertTrue(nodeTruncated.get("NODE_CAP").truncatedByBounds());

        // 4. Mutual recursion cycle (cycleA <-> cycleB) terminates cleanly without truncation
        Map<String, StageCoverageGraph> cyclic = PipelineCoverageGraphScanner.traceConnectedPipeline(
                resolver,
                List.of(new StageSliceSpec("CYCLIC", List.of(sym(DeepAndCyclicChain.class, "cycleA")), Set.of())),
                16,
                32
        );
        StageCoverageGraph cyclicGraph = cyclic.get("CYCLIC");
        assertFalse(cyclicGraph.truncatedByBounds());
        assertEquals(
                Set.of(sym(DeepAndCyclicChain.class, "cycleA"), sym(DeepAndCyclicChain.class, "cycleB")),
                cyclicGraph.reachableMethods()
        );
    }

    @Test
    @DisplayName("Runtime canary catches dynamic reflective methods, unmapped executors, and missing response fields")
    void runtimeCanaryAndResponseRequiredFieldsFailClosed() {
        Function<String, byte[]> resolver = resolverFor(
                BaseChunkCarrier.class,
                ProtoChunkCarrier.class,
                SyntheticPipelineStages.class,
                UnimplementedExternalHook.class,
                AbstractHookCaller.class
        );

        Set<String> carriers = Set.of(BaseChunkCarrier.class.getName());
        Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                resolver,
                List.of(
                        new StageSliceSpec("BIOMES", List.of(sym(SyntheticPipelineStages.class, "populateBiomes")), carriers),
                        new StageSliceSpec("EXT_HOOK", List.of(sym(AbstractHookCaller.class, "invokeExternal")), carriers)
                ),
                8,
                64
        );

        Map<String, ProvenanceBinding> bindings = new LinkedHashMap<>();
        bind(bindings, sym(SyntheticPipelineStages.class, "populateBiomes"), CoverageProvenanceKind.IR_LIFTED, "lut");
        // Mistakenly bind response-required field to BENIGN_INFRASTRUCTURE -> must be rejected!
        bind(bindings, sym(BaseChunkCarrier.class, "biomes"), CoverageProvenanceKind.BENIGN_INFRASTRUCTURE, "invalid");
        bind(bindings, sym(AbstractHookCaller.class, "invokeExternal"), CoverageProvenanceKind.IR_LIFTED, "lifted");

        Map<String, StageRuntimeObservation> runtimeObs = Map.of(
                "BIOMES",
                new StageRuntimeObservation(
                        "BIOMES",
                        Set.of("net.datapack.ReflectiveBiomeModifier#apply"),
                        Set.of(),
                        Set.of(),
                        Set.of("net.custom.PluginAsyncExecutor#execute")
                )
        );

        CoverageProvenanceLedger ledger = CoverageProvenanceLedger.reconcile(
                graphs,
                runtimeObs,
                bindings,
                Set.of(
                        sym(BaseChunkCarrier.class, "biomes"),
                        sym(BaseChunkCarrier.class, "moonriseHeightmapRaw")
                )
        );

        StageCoverageSlice biomesSlice = ledger.sliceForStage("BIOMES");
        StageCoverageVerdict biomesVerdict = biomesSlice.verdict();
        assertFalse(biomesVerdict.patchEligible());
        assertTrue(biomesVerdict.unaccountedMethods().contains("net.datapack.ReflectiveBiomeModifier#apply"));
        assertTrue(biomesVerdict.unaccountedAsyncHops().contains("net.custom.PluginAsyncExecutor#execute"));
        assertTrue(
                biomesVerdict.unaccountedFields().contains(sym(BaseChunkCarrier.class, "biomes")),
                "Response-required field bound to BENIGN_INFRASTRUCTURE must be rejected"
        );
        assertTrue(
                biomesVerdict.unaccountedFields().contains(sym(BaseChunkCarrier.class, "moonriseHeightmapRaw")),
                "Globally unhydrated response-required carrier field must fail closed"
        );

        // Abstract interface with zero concrete subtypes must also fail closed
        StageCoverageVerdict extHookVerdict = ledger.verdictForStage("EXT_HOOK");
        assertFalse(extHookVerdict.patchEligible());
        assertTrue(extHookVerdict.unaccountedMethods().contains(sym(UnimplementedExternalHook.class, "onAfterSurface")));
    }

    @Test
    @DisplayName("Logs Level.WARNING on unreadable .class bytes or resolver exceptions and fails closed (S-004)")
    void logsWarningOnResolverFaultOrMalformedClassBytesAndFailsClosed() {
        Logger scannerLogger = Logger.getLogger(PipelineCoverageGraphScanner.class.getName());
        List<LogRecord> capturedWarnings = new ArrayList<>();
        Handler captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record != null && record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    capturedWarnings.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        scannerLogger.addHandler(captureHandler);
        try {
            Function<String, byte[]> faultyResolver = name -> {
                if (name.contains("BrokenBytesStage")) {
                    return new byte[]{0x01, 0x02, 0x03, 0x04};
                }
                if (name.contains("ThrowingStage")) {
                    throw new IllegalStateException("Simulated archive I/O failure");
                }
                return null;
            };

            Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                    faultyResolver,
                    List.of(
                            new StageSliceSpec("BROKEN", List.of("pkg.BrokenBytesStage#run"), Set.of()),
                            new StageSliceSpec("THROWING", List.of("pkg.ThrowingStage#run"), Set.of())
                    ),
                    4,
                    32
            );

            assertTrue(
                    capturedWarnings.size() >= 2,
                    "Expected Level.WARNING diagnostics for malformed bytes and resolver exception (S-004)"
            );
            Map<String, StageCoverageVerdict> verdicts = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                    graphs,
                    Map.of(),
                    Map.of(),
                    Set.of()
            );
            assertFalse(verdicts.get("BROKEN").patchEligible());
            assertFalse(verdicts.get("THROWING").patchEligible());
            assertTrue(verdicts.get("BROKEN").unaccountedMethods().contains("pkg.BrokenBytesStage#run"));
            assertTrue(verdicts.get("THROWING").unaccountedMethods().contains("pkg.ThrowingStage#run"));
        } finally {
            scannerLogger.removeHandler(captureHandler);
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> PipelineCoverageGraphScanner.traceConnectedPipeline(n -> null, List.of(), -1, 16)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PipelineCoverageGraphScanner.traceConnectedPipeline(n -> null, List.of(), 4, 0)
        );
    }

    @Test
    @DisplayName("bytecode-api maintains strict TIER_1_SPI_PURE isolation from Minecraft, Paper, Anvil, and LeafGen")
    void enforcesStrictTier1BoundaryIsolation() {
        JavaClasses imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.dailystruggle.bytecode");

        noClasses()
                .that().resideInAPackage("io.github.dailystruggle.bytecode..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "net.minecraft..",
                        "org.bukkit..",
                        "io.papermc..",
                        "ca.spottedleaf..",
                        "net.fabricmc..",
                        "net.neoforged..",
                        "io.github.dailystruggle.anvil..",
                        "net.leaf.."
                )
                .check(imported);
    }

    // =========================================================================
    // Review-Blocker Fail-Closed Regression Fixtures & Tests
    // =========================================================================

    static final class ArrayStoreEdgeFixtures {
        static void writeIndexedByField(ProtoChunkCarrier c, int value) {
            c.sections[c.writeIdx] = value;
        }

        static void writeMixedFromOtherArray(ProtoChunkCarrier c) {
            c.sections[0] = c.biomes[1];
        }

        static void writeAliasedLocalAndArraycopy(ProtoChunkCarrier c, int flag) {
            int[] localAlias = c.sections;
            localAlias[flag & 3] = flag > 0 ? 11 : 22;
            int[] scratch = new int[4];
            System.arraycopy(c.biomes, 0, scratch, 0, 4);
            Arrays.fill(scratch, 1);
        }

        static void copyIntoCarrierArray(ProtoChunkCarrier c) {
            int[] src = new int[]{1, 2, 3, 4};
            System.arraycopy(src, 0, c.sections, 0, 4);
        }

        static void writeUnknownArrayParameter(ProtoChunkCarrier c, int[] unknownTarget) {
            if (c != null) {
                unknownTarget[0] = 77;
            }
        }

        static void writeOpaqueGetterArray(ProtoChunkCarrier c) {
            c.selectOpaqueArray(1)[0] = 88;
        }

        static void writeRaggedAliasedAnd2DArray(ProtoChunkCarrier c) {
            int[][] ragged = new int[2][];
            ragged[0] = c.sections;
            ragged[0][1] = 55;
            c.sectionMatrix[1][2] = 99;
        }
    }

    static final class SafeFunctionImpl implements Function<ProtoChunkCarrier, ProtoChunkCarrier> {
        @Override
        public ProtoChunkCarrier apply(ProtoChunkCarrier c) {
            return c;
        }
    }

    static final class EvilFunctionImpl implements Function<ProtoChunkCarrier, ProtoChunkCarrier> {
        @Override
        public ProtoChunkCarrier apply(ProtoChunkCarrier c) {
            c.blendingData = "evil-fn-mutation";
            return c;
        }
    }

    static final class EvilSupplierImpl implements Supplier<ProtoChunkCarrier> {
        @Override
        public ProtoChunkCarrier get() {
            ProtoChunkCarrier c = new ProtoChunkCarrier();
            c.blendingData = "evil-async-supplier";
            return c;
        }
    }

    static final class FunctionalDispatchFixtures {
        private static final Consumer<ProtoChunkCarrier> STATIC_HOOK =
                c -> CustomPaperSurfaceHook.injectCustomRule(c);

        static ProtoChunkCarrier invokeFunctionParam(
                Function<ProtoChunkCarrier, ProtoChunkCarrier> fn,
                ProtoChunkCarrier c
        ) {
            return fn.apply(c);
        }

        static void invokeStaticConsumerHook(ProtoChunkCarrier c) {
            STATIC_HOOK.accept(c);
        }

        static CompletableFuture<ProtoChunkCarrier> scheduleSupplierParam(
                Supplier<ProtoChunkCarrier> sup,
                Executor executor
        ) {
            return CompletableFuture.supplyAsync(sup, executor);
        }

        static void invokeOptionalIfPresentParam(
                ProtoChunkCarrier c,
                Consumer<ProtoChunkCarrier> hook
        ) {
            Optional.of(c).ifPresent(hook);
        }

        static void invokeUnresolvedBiConsumer(
                BiConsumer<ProtoChunkCarrier, Integer> hook,
                ProtoChunkCarrier c
        ) {
            hook.accept(c, 1);
        }

        static Object invokeReflectiveMethod(Method m, Object target) throws Exception {
            return m.invoke(target);
        }
    }

    static final class DomainSlopeTest {
        static final class NestedEntry {
            static void run(ProtoChunkCarrier c) {
                DomainSlopeTest.touchCarrier(c);
            }
        }

        static void touchCarrier(ProtoChunkCarrier c) {
            c.blendingData = "from-domain-test-suffixed-class";
        }
    }

    static final class BudgetPad00000 {
    }

    @Test
    @DisplayName("Indexed, mixed, aliased, arraycopy, and unknown array stores track target arrayref fail-closed")
    void indexedMixedAliasedAndUnknownArrayStoresFailClosed() {
        Function<String, byte[]> resolver = resolverFor(
                BaseChunkCarrier.class,
                ProtoChunkCarrier.class,
                ArrayStoreEdgeFixtures.class
        );
        Set<String> carriers = Set.of(BaseChunkCarrier.class.getName(), ProtoChunkCarrier.class.getName());

        Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                resolver,
                List.of(
                        new StageSliceSpec(
                                "INDEXED",
                                List.of(sym(ArrayStoreEdgeFixtures.class, "writeIndexedByField")),
                                carriers
                        ),
                        new StageSliceSpec(
                                "MIXED",
                                List.of(sym(ArrayStoreEdgeFixtures.class, "writeMixedFromOtherArray")),
                                carriers
                        ),
                        new StageSliceSpec(
                                "ALIASED",
                                List.of(sym(ArrayStoreEdgeFixtures.class, "writeAliasedLocalAndArraycopy")),
                                carriers
                        ),
                        new StageSliceSpec(
                                "ARRAYCOPY_DST",
                                List.of(sym(ArrayStoreEdgeFixtures.class, "copyIntoCarrierArray")),
                                carriers
                        ),
                        new StageSliceSpec(
                                "UNKNOWN_PARAM_ARR",
                                List.of(sym(ArrayStoreEdgeFixtures.class, "writeUnknownArrayParameter")),
                                carriers
                        ),
                        new StageSliceSpec(
                                "OPAQUE_GETTER_ARR",
                                List.of(sym(ArrayStoreEdgeFixtures.class, "writeOpaqueGetterArray")),
                                carriers
                        ),
                        new StageSliceSpec(
                                "RAGGED_AND_2D_ARR",
                                List.of(sym(ArrayStoreEdgeFixtures.class, "writeRaggedAliasedAnd2DArray")),
                                carriers
                        )
                ),
                8,
                64
        );

        String sectionsField = sym(BaseChunkCarrier.class, "sections");
        String sectionMatrixField = sym(BaseChunkCarrier.class, "sectionMatrix");
        String biomesField = sym(BaseChunkCarrier.class, "biomes");

        // 1. c.sections[c.writeIdx] = value must record sections as mutated
        assertTrue(
                graphs.get("INDEXED").mutatedCarrierFields().contains(sectionsField),
                "Indexed array store c.sections[c.writeIdx] = v lost sections mutation: "
                        + graphs.get("INDEXED").mutatedCarrierFields()
        );

        // 2. c.sections[0] = c.biomes[1] must record sections as mutated and biomes only as read
        assertTrue(
                graphs.get("MIXED").mutatedCarrierFields().contains(sectionsField),
                "Mixed array store c.sections[0] = c.biomes[1] missed sections mutation"
        );
        assertFalse(
                graphs.get("MIXED").mutatedCarrierFields().contains(biomesField),
                "Mixed array store c.sections[0] = c.biomes[1] falsely blamed biomes as mutated"
        );
        assertTrue(graphs.get("MIXED").readCarrierFields().contains(biomesField));

        // 3. Local alias + ternary + scratch copy/fill must mutate sections, not biomes
        assertTrue(graphs.get("ALIASED").mutatedCarrierFields().contains(sectionsField));
        assertFalse(graphs.get("ALIASED").mutatedCarrierFields().contains(biomesField));

        // 4. System.arraycopy(src, 0, c.sections, 0, 4) must mutate sections
        assertTrue(graphs.get("ARRAYCOPY_DST").mutatedCarrierFields().contains(sectionsField));

        // 5. Ragged local array aliasing ragged[0] = c.sections; ragged[0][1] = 55 & 2D c.sectionMatrix[1][2] = 99
        assertTrue(graphs.get("RAGGED_AND_2D_ARR").mutatedCarrierFields().contains(sectionsField));
        assertTrue(graphs.get("RAGGED_AND_2D_ARR").mutatedCarrierFields().contains(sectionMatrixField));

        // Bind all methods to IR_LIFTED, leaving sections & unknown arrays unbound -> ALL stages must block patching!
        Map<String, ProvenanceBinding> methodOnlyBindings = new LinkedHashMap<>();
        for (StageCoverageGraph g : graphs.values()) {
            for (String m : g.reachableMethods()) {
                bind(methodOnlyBindings, m, CoverageProvenanceKind.IR_LIFTED, "lifted-method");
            }
        }
        Map<String, StageCoverageVerdict> verdicts = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                graphs,
                Map.of(),
                methodOnlyBindings,
                Set.of()
        );
        for (String stageId : graphs.keySet()) {
            assertFalse(
                    verdicts.get(stageId).patchEligible(),
                    "Stage with unbound array store must not be patchEligible: " + stageId + " -> " + verdicts.get(stageId)
            );
            assertFalse(verdicts.get(stageId).unaccountedFields().isEmpty());
        }
    }

    @Test
    @DisplayName("JDK functional interface calls via parameter, static lambda, or higher-order helper resolve or fail closed")
    void jdkFunctionalInterfaceAndReflectiveCallsResolveOrFailClosed() {
        Function<String, byte[]> resolver = resolverFor(
                BaseChunkCarrier.class,
                ProtoChunkCarrier.class,
                CustomPaperSurfaceHook.class,
                SafeFunctionImpl.class,
                EvilFunctionImpl.class,
                EvilSupplierImpl.class,
                FunctionalDispatchFixtures.class,
                DomainSlopeTest.class,
                DomainSlopeTest.NestedEntry.class
        );
        Set<String> carriers = Set.of(BaseChunkCarrier.class.getName(), ProtoChunkCarrier.class.getName());

        Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                resolver,
                List.of(
                        SafeFunctionImpl.class.getName(),
                        EvilFunctionImpl.class.getName(),
                        EvilSupplierImpl.class.getName()
                ),
                List.of(
                        new StageSliceSpec(
                                "FN_PARAM",
                                List.of(sym(FunctionalDispatchFixtures.class, "invokeFunctionParam")),
                                carriers
                        ),
                        new StageSliceSpec(
                                "STATIC_HOOK",
                                List.of(sym(FunctionalDispatchFixtures.class, "invokeStaticConsumerHook")),
                                carriers
                        ),
                        new StageSliceSpec(
                                "SUPPLIER_ASYNC",
                                List.of(sym(FunctionalDispatchFixtures.class, "scheduleSupplierParam")),
                                carriers
                        ),
                        new StageSliceSpec(
                                "OPTIONAL_PARAM",
                                List.of(sym(FunctionalDispatchFixtures.class, "invokeOptionalIfPresentParam")),
                                carriers
                        ),
                        new StageSliceSpec(
                                "UNRESOLVED_FN",
                                List.of(sym(FunctionalDispatchFixtures.class, "invokeUnresolvedBiConsumer")),
                                carriers
                        ),
                        new StageSliceSpec(
                                "REFLECT_CALL",
                                List.of(sym(FunctionalDispatchFixtures.class, "invokeReflectiveMethod")),
                                carriers
                        ),
                        new StageSliceSpec(
                                "DOMAIN_TEST_SUFFIX",
                                List.of(sym(DomainSlopeTest.NestedEntry.class, "run")),
                                carriers
                        )
                ),
                10,
                128
        );

        String blendingField = sym(BaseChunkCarrier.class, "blendingData");

        // 1. fn.apply(c) resolves EvilFunctionImpl#apply and mutates blendingData
        assertTrue(graphs.get("FN_PARAM").reachableMethods().contains(sym(EvilFunctionImpl.class, "apply")));
        assertTrue(graphs.get("FN_PARAM").mutatedCarrierFields().contains(blendingField));

        // 2. STATIC_HOOK.accept(c) resolves static indy lambda -> CustomPaperSurfaceHook#injectCustomRule
        assertTrue(
                graphs.get("STATIC_HOOK")
                        .reachableMethods()
                        .contains(sym(CustomPaperSurfaceHook.class, "injectCustomRule"))
        );
        assertTrue(graphs.get("STATIC_HOOK").mutatedCarrierFields().contains(blendingField));

        // 3. CompletableFuture.supplyAsync(sup, executor) resolves EvilSupplierImpl#get
        assertTrue(graphs.get("SUPPLIER_ASYNC").reachableMethods().contains(sym(EvilSupplierImpl.class, "get")));
        assertTrue(graphs.get("SUPPLIER_ASYNC").mutatedCarrierFields().contains(blendingField));

        // 4. Optional.of(c).ifPresent(hook) resolves STATIC_HOOK lambda via Consumer#accept
        assertTrue(
                graphs.get("OPTIONAL_PARAM")
                        .reachableMethods()
                        .contains(sym(CustomPaperSurfaceHook.class, "injectCustomRule"))
        );

        // 5. Domain class ending in *Test is loaded normally when present in resolver
        assertTrue(graphs.get("DOMAIN_TEST_SUFFIX").reachableMethods().contains(sym(DomainSlopeTest.class, "touchCarrier")));
        assertTrue(graphs.get("DOMAIN_TEST_SUFFIX").mutatedCarrierFields().contains(blendingField));

        // Binding only the root methods must fail closed on ALL 7 stages!
        Map<String, ProvenanceBinding> rootOnlyBindings = new LinkedHashMap<>();
        bind(rootOnlyBindings, sym(FunctionalDispatchFixtures.class, "invokeFunctionParam"), CoverageProvenanceKind.IR_LIFTED, "root");
        bind(rootOnlyBindings, sym(FunctionalDispatchFixtures.class, "invokeStaticConsumerHook"), CoverageProvenanceKind.IR_LIFTED, "root");
        bind(rootOnlyBindings, sym(FunctionalDispatchFixtures.class, "scheduleSupplierParam"), CoverageProvenanceKind.IR_LIFTED, "root");
        bind(rootOnlyBindings, sym(FunctionalDispatchFixtures.class, "invokeOptionalIfPresentParam"), CoverageProvenanceKind.IR_LIFTED, "root");
        bind(rootOnlyBindings, sym(FunctionalDispatchFixtures.class, "invokeUnresolvedBiConsumer"), CoverageProvenanceKind.IR_LIFTED, "root");
        bind(rootOnlyBindings, sym(FunctionalDispatchFixtures.class, "invokeReflectiveMethod"), CoverageProvenanceKind.IR_LIFTED, "root");
        bind(rootOnlyBindings, sym(DomainSlopeTest.NestedEntry.class, "run"), CoverageProvenanceKind.IR_LIFTED, "root");

        Map<String, StageCoverageVerdict> verdicts = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                graphs,
                Map.of(),
                rootOnlyBindings,
                Set.of()
        );
        for (String stageId : graphs.keySet()) {
            assertFalse(
                    verdicts.get(stageId).patchEligible(),
                    "Expected fail-closed patchEligible=false for " + stageId + " but got: " + verdicts.get(stageId)
            );
        }
        assertTrue(verdicts.get("UNRESOLVED_FN").unaccountedMethods().contains("java.util.function.BiConsumer#accept"));
        assertTrue(verdicts.get("REFLECT_CALL").unaccountedMethods().contains("java.lang.reflect.Method#invoke"));
    }

    @Test
    @DisplayName("Non-delegated STAGE:<id> bindings are rejected with Level.WARNING and never blanket-account symbols")
    void rejectsNonDelegatedStageWideBindingsAndLogsWarning() {
        Function<String, byte[]> resolver = resolverFor(
                BaseChunkCarrier.class,
                ProtoChunkCarrier.class,
                CustomPaperSurfaceHook.class
        );
        Set<String> carriers = Set.of(BaseChunkCarrier.class.getName(), ProtoChunkCarrier.class.getName());
        Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                resolver,
                List.of(
                        new StageSliceSpec(
                                "HOOK_STAGE",
                                List.of(sym(CustomPaperSurfaceHook.class, "injectCustomRule")),
                                carriers
                        )
                ),
                6,
                32
        );

        Logger scannerLogger = Logger.getLogger(PipelineCoverageGraphScanner.class.getName());
        List<LogRecord> warnings = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record != null && record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        scannerLogger.addHandler(handler);
        try {
            for (CoverageProvenanceKind invalidStageKind : List.of(
                    CoverageProvenanceKind.IR_LIFTED,
                    CoverageProvenanceKind.APPROXIMATE_EQUIVALENT,
                    CoverageProvenanceKind.STAMP_ATLAS,
                    CoverageProvenanceKind.BENIGN_INFRASTRUCTURE
            )) {
                warnings.clear();
                Map<String, ProvenanceBinding> badBindings = new LinkedHashMap<>();
                bind(badBindings, "STAGE:HOOK_STAGE", invalidStageKind, "coarse-stage-allowlist");

                StageCoverageVerdict verdict = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                        graphs,
                        Map.of(),
                        badBindings,
                        Set.of(sym(BaseChunkCarrier.class, "blendingData"))
                ).get("HOOK_STAGE");

                assertFalse(
                        verdict.patchEligible(),
                        "STAGE:<id> -> " + invalidStageKind + " must not blanket-account unmapped stage items"
                );
                assertTrue(verdict.unaccountedMethods().contains(sym(CustomPaperSurfaceHook.class, "injectCustomRule")));
                assertTrue(verdict.unaccountedFields().contains(sym(BaseChunkCarrier.class, "blendingData")));
                assertFalse(
                        warnings.isEmpty(),
                        "Expected Level.WARNING when rejecting STAGE:<id> -> " + invalidStageKind
                );
            }
        } finally {
            scannerLogger.removeHandler(handler);
        }
    }

    @Test
    @DisplayName("Closed-world class budget exhaustion logs Level.WARNING, marks graphs truncated, and blocks patching")
    void closedWorldBudgetExhaustionLogsWarningAndFailsClosed() {
        byte[] padTemplate = readClassBytes(BudgetPad00000.class);
        byte[] templateToken = "BudgetPad00000".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> table = new LinkedHashMap<>();
        for (Class<?> baseCls : List.of(
                BaseChunkCarrier.class,
                ProtoChunkCarrier.class,
                SafeFunctionImpl.class,
                EvilFunctionImpl.class,
                FunctionalDispatchFixtures.class
        )) {
            byte[] b = readClassBytes(baseCls);
            table.put(baseCls.getName(), b);
            table.put(baseCls.getName().replace('.', '/'), b);
        }

        String padPkgPrefix = BudgetPad00000.class.getName().substring(
                0,
                BudgetPad00000.class.getName().indexOf("BudgetPad00000")
        );
        List<String> seeds = new ArrayList<>();
        seeds.add(SafeFunctionImpl.class.getName());
        for (int i = 1; i <= 4150; i++) {
            String suffix = String.format("BudgetPad%05d", i);
            byte[] cloned = cloneFixedLengthClassNameBytes(
                    padTemplate,
                    templateToken,
                    suffix.getBytes(StandardCharsets.UTF_8)
            );
            String fqcn = padPkgPrefix + suffix;
            table.put(fqcn, cloned);
            table.put(fqcn.replace('.', '/'), cloned);
            seeds.add(fqcn);
        }
        // EvilFunctionImpl sits beyond the 4096 default class budget!
        seeds.add(EvilFunctionImpl.class.getName());

        Logger scannerLogger = Logger.getLogger(PipelineCoverageGraphScanner.class.getName());
        List<LogRecord> warnings = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record != null && record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        scannerLogger.addHandler(handler);
        try {
            Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                    table::get,
                    seeds,
                    List.of(
                            new StageSliceSpec(
                                    "BUDGET_STAGE",
                                    List.of(sym(FunctionalDispatchFixtures.class, "invokeFunctionParam")),
                                    Set.of(BaseChunkCarrier.class.getName())
                            )
                    ),
                    8,
                    64
            );

            StageCoverageGraph graph = graphs.get("BUDGET_STAGE");
            assertFalse(warnings.isEmpty(), "Expected Level.WARNING on closed-world class budget exhaustion");
            assertTrue(graph.truncatedByBounds(), "Budget-exhausted closed-world scan must mark graph truncatedByBounds");

            Map<String, ProvenanceBinding> bindings = new LinkedHashMap<>();
            bind(bindings, sym(FunctionalDispatchFixtures.class, "invokeFunctionParam"), CoverageProvenanceKind.IR_LIFTED, "ir");
            bind(bindings, sym(SafeFunctionImpl.class, "apply"), CoverageProvenanceKind.IR_LIFTED, "ir");

            StageCoverageVerdict verdict = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                    graphs,
                    Map.of(),
                    bindings,
                    Set.of()
            ).get("BUDGET_STAGE");
            assertFalse(
                    verdict.patchEligible(),
                    "Incomplete closed-world hierarchy after budget exhaustion must fail closed"
            );
        } finally {
            scannerLogger.removeHandler(handler);
        }
    }

    static final class MissingBodyCallee {
        static void go(BaseChunkCarrier c) {
            c.blendingData = new Object();
        }
    }

    static final class MissingBodyDriver {
        static void run(BaseChunkCarrier c) {
            c.sections[0] = 1;
            MissingBodyCallee.go(c);
        }
    }

    private static Map<String, byte[]> classTable(Class<?>... classes) {
        Map<String, byte[]> table = new LinkedHashMap<>();
        for (Class<?> cls : classes) {
            byte[] b = readClassBytes(cls);
            table.put(cls.getName(), b);
            table.put(cls.getName().replace('.', '/'), b);
        }
        return table;
    }

    private static Handler warningCollector(List<LogRecord> sink) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record != null && record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    sink.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    @DisplayName("Stage with empty root set is truncated, logs Level.WARNING, and is never patch-eligible")
    void emptyRootSetFailsClosedWithWarning() {
        Logger scannerLogger = Logger.getLogger(PipelineCoverageGraphScanner.class.getName());
        List<LogRecord> warnings = new ArrayList<>();
        Handler handler = warningCollector(warnings);
        scannerLogger.addHandler(handler);
        try {
            Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                    classTable(BaseChunkCarrier.class)::get,
                    List.of(new StageSliceSpec("EMPTY_ROOTS", List.of(), Set.of(BaseChunkCarrier.class.getName()))),
                    8,
                    64
            );
            StageCoverageGraph graph = graphs.get("EMPTY_ROOTS");
            assertTrue(graph.truncatedByBounds(), "Nothing was inspected, so the graph must be incomplete");
            assertFalse(graph.unexpandedFrontierSymbols().isEmpty());
            assertFalse(warnings.isEmpty(), "Expected Level.WARNING for a stage without roots");

            StageCoverageVerdict verdict = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                    graphs,
                    Map.of(),
                    Map.of(),
                    Set.of()
            ).get("EMPTY_ROOTS");
            assertFalse(verdict.patchEligible(), "A stage with no inspected code must not count as 100% accounted");
        } finally {
            scannerLogger.removeHandler(handler);
        }
    }

    @Test
    @DisplayName("Unresolvable or renamed bound roots fail closed with Level.WARNING despite IR_LIFTED bindings")
    void unresolvableBoundRootsFailClosedWithWarning() {
        Logger scannerLogger = Logger.getLogger(PipelineCoverageGraphScanner.class.getName());
        List<LogRecord> warnings = new ArrayList<>();
        Handler handler = warningCollector(warnings);
        scannerLogger.addHandler(handler);
        try {
            String ghostRoot = "probe.Ghost#root";
            String renamedRoot = sym(MissingBodyDriver.class, "renamedAfterUpdate");
            Map<String, byte[]> table = classTable(BaseChunkCarrier.class, MissingBodyDriver.class, MissingBodyCallee.class);
            Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                    table::get,
                    List.of(
                            new StageSliceSpec("GHOST", List.of(ghostRoot), Set.of(BaseChunkCarrier.class.getName())),
                            new StageSliceSpec("RENAMED", List.of(renamedRoot), Set.of(BaseChunkCarrier.class.getName()))
                    ),
                    8,
                    64
            );
            Map<String, ProvenanceBinding> bindings = new LinkedHashMap<>();
            bind(bindings, ghostRoot, CoverageProvenanceKind.IR_LIFTED, "ir");
            bind(bindings, renamedRoot, CoverageProvenanceKind.IR_LIFTED, "ir");

            Map<String, StageCoverageVerdict> verdicts = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                    graphs,
                    Map.of(),
                    bindings,
                    Set.of()
            );
            for (String stage : List.of("GHOST", "RENAMED")) {
                assertTrue(graphs.get(stage).truncatedByBounds(), stage + " root body was never inspected");
                assertFalse(verdicts.get(stage).patchEligible(), stage + " must fail closed");
                assertFalse(verdicts.get(stage).unaccountedMethods().isEmpty(), stage + " must name the uninspected root");
            }
            assertTrue(verdicts.get("GHOST").unaccountedMethods().contains(ghostRoot));
            assertTrue(verdicts.get("RENAMED").unaccountedMethods().contains(renamedRoot));
            assertFalse(warnings.isEmpty(), "Expected Level.WARNING for roots whose bodies cannot be read");
        } finally {
            scannerLogger.removeHandler(handler);
        }
    }

    @Test
    @DisplayName("Bound callee whose class bytes are missing stays UNACCOUNTED, truncates the graph, and logs Level.WARNING")
    void missingBoundCalleeClassBytesFailClosedWithWarning() {
        Logger scannerLogger = Logger.getLogger(PipelineCoverageGraphScanner.class.getName());
        List<LogRecord> warnings = new ArrayList<>();
        Handler handler = warningCollector(warnings);
        scannerLogger.addHandler(handler);
        try {
            Map<String, byte[]> table = classTable(BaseChunkCarrier.class, MissingBodyDriver.class);
            String driverRoot = sym(MissingBodyDriver.class, "run");
            String missingCallee = sym(MissingBodyCallee.class, "go");
            Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                    table::get,
                    List.of(new StageSliceSpec("MISSING_CALLEE", List.of(driverRoot), Set.of(BaseChunkCarrier.class.getName()))),
                    8,
                    64
            );
            StageCoverageGraph graph = graphs.get("MISSING_CALLEE");
            assertTrue(graph.truncatedByBounds(), "Callee body was never inspected");
            assertTrue(graph.unexpandedFrontierSymbols().contains(missingCallee));
            assertFalse(warnings.isEmpty(), "Expected Level.WARNING for unreadable callee class");

            Map<String, ProvenanceBinding> bindings = new LinkedHashMap<>();
            bind(bindings, driverRoot, CoverageProvenanceKind.IR_LIFTED, "ir");
            bind(bindings, missingCallee, CoverageProvenanceKind.IR_LIFTED, "ir");
            bind(bindings, sym(BaseChunkCarrier.class, "sections"), CoverageProvenanceKind.IR_LIFTED, "ir");

            StageCoverageVerdict verdict = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                    graphs,
                    Map.of(),
                    bindings,
                    Set.of()
            ).get("MISSING_CALLEE");
            assertFalse(verdict.patchEligible(), "A binding must not forgive a body that could not be read");
            assertTrue(verdict.unaccountedMethods().contains(missingCallee));
        } finally {
            scannerLogger.removeHandler(handler);
        }
    }

    static final class MissingInstantiatedTask {
        MissingInstantiatedTask(BaseChunkCarrier c) {
            c.blendingData = "mutated-in-missing-ctor";
        }
    }

    static class MissingSuperBase {
        void inheritedStep(BaseChunkCarrier c) {
            c.blendingData = "mutated-in-missing-super";
        }
    }

    static final class PresentSubWithMissingSuper extends MissingSuperBase {
        void ownStep(BaseChunkCarrier c) {
            c.sections[0] = 1;
        }
    }

    static final class MissingCtorAndHierarchyDriver {
        static void instantiateMissing(BaseChunkCarrier c) {
            c.sections[0] = 1;
            new MissingInstantiatedTask(c);
        }

        static void callLoadedSubWithMissingSuper(PresentSubWithMissingSuper sub, BaseChunkCarrier c) {
            sub.ownStep(c);
        }
    }

    static final class MissingAsyncRunnable implements Runnable {
        private final BaseChunkCarrier carrier;

        MissingAsyncRunnable(BaseChunkCarrier carrier) {
            this.carrier = carrier;
        }

        @Override
        public void run() {
            this.carrier.blendingData = "mutated-in-missing-runnable";
        }
    }

    static final class MissingAsyncSupplier implements Supplier<Object> {
        static BaseChunkCarrier target;

        MissingAsyncSupplier() {
        }

        @Override
        public Object get() {
            if (target != null) {
                target.blendingData = "mutated-in-missing-supplier";
            }
            return Boolean.TRUE;
        }
    }

    static final class MissingAsyncFunctorDriver {
        static void executeMissingRunnable(BaseChunkCarrier c, Executor executor) {
            c.sections[0] = 1;
            executor.execute(new MissingAsyncRunnable(c));
        }

        static CompletableFuture<Object> supplyMissingFunctor(BaseChunkCarrier c) {
            c.sections[0] = 1;
            return CompletableFuture.supplyAsync(new MissingAsyncSupplier());
        }
    }

    static final class MissingCtorRefTask {
        MissingCtorRefTask(BaseChunkCarrier c) {
            c.blendingData = "mutated-in-missing-ctor-ref";
        }
    }

    static final class MissingCtorRefDriver {
        static void invokeMissingCtorRef(BaseChunkCarrier c) {
            c.sections[0] = 1;
            Consumer<BaseChunkCarrier> ctorRef = MissingCtorRefTask::new;
            ctorRef.accept(c);
        }

        static CompletableFuture<MissingAsyncSupplier> supplyMissingCtorRefAsync(BaseChunkCarrier c) {
            c.sections[0] = 1;
            return CompletableFuture.supplyAsync(MissingAsyncSupplier::new);
        }
    }

    static final class MissingAsyncMethodRefHelper {
        static BaseChunkCarrier hiddenTarget;

        static void tick() {
            if (hiddenTarget != null) {
                hiddenTarget.blendingData = "mutated-in-missing-method-ref";
            }
        }
    }

    static final class MissingAsyncMethodRefDriver {
        static CompletableFuture<Void> scheduleMissingMethodRef(BaseChunkCarrier c) {
            c.sections[0] = 1;
            return CompletableFuture.runAsync(MissingAsyncMethodRefHelper::tick);
        }
    }

    static final class MissingStaticHookHolder {
        static final Consumer<BaseChunkCarrier> HOOK = c -> c.blendingData = "mutated-in-missing-holder-hook";
    }

    static final class IsolatedSafeAndMissingHolderStage {
        static final Consumer<BaseChunkCarrier> SAFE = c -> c.sections[0] = 1;

        static void run(BaseChunkCarrier c) {
            SAFE.accept(c);
            MissingStaticHookHolder.HOOK.accept(c);
        }
    }

    static final class IndirectClinitMissingHolderStage {
        static final Consumer<BaseChunkCarrier> SAFE = c -> c.sections[0] = 1;
        static final Consumer<BaseChunkCarrier> INDIRECT = MissingStaticHookHolder.HOOK;

        static void run(BaseChunkCarrier c) {
            SAFE.accept(c);
            INDIRECT.accept(c);
        }
    }

    @Test
    @DisplayName("Missing-bytes instantiated constructor and missing superclass fail closed with Level.WARNING")
    void missingBytesInstantiatedConstructorFailsClosedWithWarning() {
        Logger scannerLogger = Logger.getLogger(PipelineCoverageGraphScanner.class.getName());
        List<LogRecord> warnings = new ArrayList<>();
        Handler handler = warningCollector(warnings);
        scannerLogger.addHandler(handler);
        try {
            Map<String, byte[]> table = classTable(
                    BaseChunkCarrier.class,
                    MissingCtorAndHierarchyDriver.class,
                    PresentSubWithMissingSuper.class
            );
            String ctorStageRoot = sym(MissingCtorAndHierarchyDriver.class, "instantiateMissing");
            String missingCtorSym = sym(MissingInstantiatedTask.class, "<init>");
            String hierarchyStageRoot = sym(MissingCtorAndHierarchyDriver.class, "callLoadedSubWithMissingSuper");

            Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                    table::get,
                    List.of(
                            new StageSliceSpec(
                                    "MISSING_CTOR",
                                    List.of(ctorStageRoot),
                                    Set.of(BaseChunkCarrier.class.getName())
                            ),
                            new StageSliceSpec(
                                    "MISSING_SUPER_ON_LOADED_SUB",
                                    List.of(hierarchyStageRoot),
                                    Set.of(BaseChunkCarrier.class.getName())
                            )
                    ),
                    8,
                    64
            );

            StageCoverageGraph ctorGraph = graphs.get("MISSING_CTOR");
            assertTrue(ctorGraph.truncatedByBounds(), "Instantiated class with missing bytes must truncate graph");
            assertTrue(ctorGraph.unexpandedFrontierSymbols().contains(missingCtorSym));
            assertTrue(
                    warnings.stream().anyMatch(r -> r.getMessage().contains(MissingInstantiatedTask.class.getName())),
                    "Expected Level.WARNING naming MissingInstantiatedTask"
            );

            Map<String, ProvenanceBinding> bindings = new LinkedHashMap<>();
            for (StageCoverageGraph g : graphs.values()) {
                for (String m : g.reachableMethods()) {
                    bind(bindings, m, CoverageProvenanceKind.IR_LIFTED, "ir");
                }
            }
            bind(bindings, missingCtorSym, CoverageProvenanceKind.IR_LIFTED, "ir");
            bind(bindings, sym(BaseChunkCarrier.class, "sections"), CoverageProvenanceKind.IR_LIFTED, "ir");

            Map<String, StageCoverageVerdict> verdicts = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                    graphs,
                    Map.of(),
                    bindings,
                    Set.of()
            );
            StageCoverageVerdict ctorVerdict = verdicts.get("MISSING_CTOR");
            assertFalse(ctorVerdict.patchEligible(), "Missing-bytes constructor must fail closed even if bound");
            assertTrue(ctorVerdict.unaccountedMethods().contains(missingCtorSym));

            StageCoverageGraph superGraph = graphs.get("MISSING_SUPER_ON_LOADED_SUB");
            StageCoverageVerdict superVerdict = verdicts.get("MISSING_SUPER_ON_LOADED_SUB");
            assertTrue(superGraph.truncatedByBounds(), "Loaded subclass with missing superclass bytes must truncate");
            assertFalse(superVerdict.patchEligible(), "Loaded subclass with missing superclass bytes must fail closed");
            assertTrue(
                    warnings.stream().anyMatch(r -> r.getMessage().contains(MissingSuperBase.class.getName())),
                    "Expected Level.WARNING naming MissingSuperBase"
            );
        } finally {
            scannerLogger.removeHandler(handler);
        }
    }

    @Test
    @DisplayName("Executor and supplyAsync functors with missing class bytes fail closed with UNACCOUNTED async hops")
    void missingBytesAsyncFunctorExecutorAndSupplyAsyncFailClosedWithUnaccountedHop() {
        Logger scannerLogger = Logger.getLogger(PipelineCoverageGraphScanner.class.getName());
        List<LogRecord> warnings = new ArrayList<>();
        Handler handler = warningCollector(warnings);
        scannerLogger.addHandler(handler);
        try {
            Map<String, byte[]> table = classTable(BaseChunkCarrier.class, MissingAsyncFunctorDriver.class);
            String execRoot = sym(MissingAsyncFunctorDriver.class, "executeMissingRunnable");
            String supplyRoot = sym(MissingAsyncFunctorDriver.class, "supplyMissingFunctor");
            String missingRunnableInit = sym(MissingAsyncRunnable.class, "<init>");
            String missingSupplierInit = sym(MissingAsyncSupplier.class, "<init>");

            Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                    table::get,
                    List.of(
                            new StageSliceSpec("EXEC_RUNNABLE", List.of(execRoot), Set.of(BaseChunkCarrier.class.getName())),
                            new StageSliceSpec("SUPPLY_ASYNC", List.of(supplyRoot), Set.of(BaseChunkCarrier.class.getName()))
                    ),
                    8,
                    64
            );

            Map<String, ProvenanceBinding> bindings = new LinkedHashMap<>();
            for (StageCoverageGraph g : graphs.values()) {
                for (String m : g.reachableMethods()) {
                    bind(bindings, m, CoverageProvenanceKind.IR_LIFTED, "ir");
                }
            }
            bind(bindings, missingRunnableInit, CoverageProvenanceKind.IR_LIFTED, "ir");
            bind(bindings, sym(MissingAsyncRunnable.class, "run"), CoverageProvenanceKind.IR_LIFTED, "ir");
            bind(bindings, missingSupplierInit, CoverageProvenanceKind.IR_LIFTED, "ir");
            bind(bindings, sym(MissingAsyncSupplier.class, "get"), CoverageProvenanceKind.IR_LIFTED, "ir");
            bind(bindings, sym(BaseChunkCarrier.class, "sections"), CoverageProvenanceKind.IR_LIFTED, "ir");

            Map<String, StageCoverageVerdict> verdicts = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                    graphs,
                    Map.of(),
                    bindings,
                    Set.of()
            );

            StageCoverageGraph execGraph = graphs.get("EXEC_RUNNABLE");
            StageCoverageVerdict execVerdict = verdicts.get("EXEC_RUNNABLE");
            assertTrue(execGraph.truncatedByBounds());
            assertTrue(execGraph.unexpandedFrontierSymbols().contains(missingRunnableInit));
            assertFalse(execVerdict.patchEligible());
            assertTrue(execVerdict.unaccountedMethods().contains(missingRunnableInit));
            assertTrue(
                    execVerdict.unaccountedAsyncHops().contains(missingRunnableInit),
                    "Executor functor with missing bytes must appear in unaccountedAsyncHops: " + execVerdict
            );

            StageCoverageGraph supplyGraph = graphs.get("SUPPLY_ASYNC");
            StageCoverageVerdict supplyVerdict = verdicts.get("SUPPLY_ASYNC");
            assertTrue(supplyGraph.truncatedByBounds());
            assertTrue(supplyGraph.unexpandedFrontierSymbols().contains(missingSupplierInit));
            assertFalse(supplyVerdict.patchEligible());
            assertTrue(supplyVerdict.unaccountedMethods().contains(missingSupplierInit));
            assertTrue(
                    supplyVerdict.unaccountedAsyncHops().contains(missingSupplierInit),
                    "supplyAsync functor with missing bytes must appear in unaccountedAsyncHops: " + supplyVerdict
            );

            assertTrue(warnings.stream().anyMatch(r -> r.getMessage().contains(MissingAsyncRunnable.class.getName())));
            assertTrue(warnings.stream().anyMatch(r -> r.getMessage().contains(MissingAsyncSupplier.class.getName())));
        } finally {
            scannerLogger.removeHandler(handler);
        }
    }

    @Test
    @DisplayName("Constructor method reference X::new on a class with missing bytes fails closed with Level.WARNING")
    void missingClassConstructorMethodReferenceFailsClosedWithWarning() {
        Logger scannerLogger = Logger.getLogger(PipelineCoverageGraphScanner.class.getName());
        List<LogRecord> warnings = new ArrayList<>();
        Handler handler = warningCollector(warnings);
        scannerLogger.addHandler(handler);
        try {
            Map<String, byte[]> table = classTable(BaseChunkCarrier.class, MissingCtorRefDriver.class);
            String syncRoot = sym(MissingCtorRefDriver.class, "invokeMissingCtorRef");
            String asyncRoot = sym(MissingCtorRefDriver.class, "supplyMissingCtorRefAsync");
            String missingCtorRefSym = sym(MissingCtorRefTask.class, "<init>");
            String missingAsyncCtorRefSym = sym(MissingAsyncSupplier.class, "<init>");

            Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                    table::get,
                    List.of(
                            new StageSliceSpec("CTOR_REF_SYNC", List.of(syncRoot), Set.of(BaseChunkCarrier.class.getName())),
                            new StageSliceSpec("CTOR_REF_ASYNC", List.of(asyncRoot), Set.of(BaseChunkCarrier.class.getName()))
                    ),
                    8,
                    64
            );

            Map<String, ProvenanceBinding> bindings = new LinkedHashMap<>();
            for (StageCoverageGraph g : graphs.values()) {
                for (String m : g.reachableMethods()) {
                    bind(bindings, m, CoverageProvenanceKind.IR_LIFTED, "ir");
                }
            }
            bind(bindings, missingCtorRefSym, CoverageProvenanceKind.IR_LIFTED, "ir");
            bind(bindings, missingAsyncCtorRefSym, CoverageProvenanceKind.IR_LIFTED, "ir");
            bind(bindings, sym(BaseChunkCarrier.class, "sections"), CoverageProvenanceKind.IR_LIFTED, "ir");

            Map<String, StageCoverageVerdict> verdicts = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                    graphs,
                    Map.of(),
                    bindings,
                    Set.of()
            );

            assertTrue(graphs.get("CTOR_REF_SYNC").truncatedByBounds());
            assertTrue(graphs.get("CTOR_REF_SYNC").unexpandedFrontierSymbols().contains(missingCtorRefSym));
            assertFalse(verdicts.get("CTOR_REF_SYNC").patchEligible());
            assertTrue(verdicts.get("CTOR_REF_SYNC").unaccountedMethods().contains(missingCtorRefSym));

            assertTrue(graphs.get("CTOR_REF_ASYNC").truncatedByBounds());
            assertTrue(graphs.get("CTOR_REF_ASYNC").unexpandedFrontierSymbols().contains(missingAsyncCtorRefSym));
            assertFalse(verdicts.get("CTOR_REF_ASYNC").patchEligible());
            assertTrue(verdicts.get("CTOR_REF_ASYNC").unaccountedMethods().contains(missingAsyncCtorRefSym));
            assertTrue(verdicts.get("CTOR_REF_ASYNC").unaccountedAsyncHops().contains(missingAsyncCtorRefSym));

            assertTrue(warnings.stream().anyMatch(r -> r.getMessage().contains(MissingCtorRefTask.class.getName())));
            assertTrue(warnings.stream().anyMatch(r -> r.getMessage().contains(MissingAsyncSupplier.class.getName())));
        } finally {
            scannerLogger.removeHandler(handler);
        }
    }

    @Test
    @DisplayName("Bound async method reference Missing::m with missing class bytes still fails closed with Level.WARNING")
    void missingClassBoundAsyncMethodReferenceFailsClosedWithWarning() {
        Logger scannerLogger = Logger.getLogger(PipelineCoverageGraphScanner.class.getName());
        List<LogRecord> warnings = new ArrayList<>();
        Handler handler = warningCollector(warnings);
        scannerLogger.addHandler(handler);
        try {
            Map<String, byte[]> table = classTable(BaseChunkCarrier.class, MissingAsyncMethodRefDriver.class);
            String rootSym = sym(MissingAsyncMethodRefDriver.class, "scheduleMissingMethodRef");
            String missingMethodRefSym = sym(MissingAsyncMethodRefHelper.class, "tick");

            Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                    table::get,
                    List.of(new StageSliceSpec("BOUND_MISSING_METHOD_REF", List.of(rootSym), Set.of(BaseChunkCarrier.class.getName()))),
                    8,
                    64
            );

            StageCoverageGraph graph = graphs.get("BOUND_MISSING_METHOD_REF");
            assertTrue(graph.truncatedByBounds(), "Missing method-ref target class must truncate stage graph");
            assertTrue(graph.unexpandedFrontierSymbols().contains(missingMethodRefSym));
            assertTrue(
                    warnings.stream().anyMatch(r -> r.getMessage().contains(MissingAsyncMethodRefHelper.class.getName())),
                    "Expected Level.WARNING naming MissingAsyncMethodRefHelper"
            );

            Map<String, ProvenanceBinding> bindings = new LinkedHashMap<>();
            for (String m : graph.reachableMethods()) {
                bind(bindings, m, CoverageProvenanceKind.IR_LIFTED, "ir");
            }
            bind(bindings, missingMethodRefSym, CoverageProvenanceKind.IR_LIFTED, "ir");
            bind(bindings, sym(BaseChunkCarrier.class, "sections"), CoverageProvenanceKind.IR_LIFTED, "ir");

            StageCoverageVerdict verdict = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                    graphs,
                    Map.of(),
                    bindings,
                    Set.of()
            ).get("BOUND_MISSING_METHOD_REF");
            assertFalse(verdict.patchEligible(), "Per-symbol binding must not forgive an unread method-ref target body");
            assertTrue(verdict.unaccountedMethods().contains(missingMethodRefSym));
            assertTrue(verdict.unaccountedAsyncHops().contains(missingMethodRefSym));
        } finally {
            scannerLogger.removeHandler(handler);
        }
    }

    @Test
    @DisplayName("Missing-holder static functional field next to bound safe lambda invalidates closed world with Level.WARNING")
    void missingHolderStaticFunctionalFieldInvalidatesClosedWorldDespiteBoundSafeLambda() {
        Logger scannerLogger = Logger.getLogger(PipelineCoverageGraphScanner.class.getName());
        List<LogRecord> warnings = new ArrayList<>();
        Handler handler = warningCollector(warnings);
        scannerLogger.addHandler(handler);
        try {
            Map<String, byte[]> table = classTable(
                    BaseChunkCarrier.class,
                    IsolatedSafeAndMissingHolderStage.class,
                    IndirectClinitMissingHolderStage.class
            );
            String rootSym = sym(IsolatedSafeAndMissingHolderStage.class, "run");
            String indirectRootSym = sym(IndirectClinitMissingHolderStage.class, "run");
            String missingHookField = sym(MissingStaticHookHolder.class, "HOOK");
            String consumerAcceptSym = "java.util.function.Consumer#accept";

            Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                    table::get,
                    List.of(
                            new StageSliceSpec("MISSING_STATIC_HOLDER", List.of(rootSym), Set.of(BaseChunkCarrier.class.getName())),
                            new StageSliceSpec("INDIRECT_CLINIT_HOLDER", List.of(indirectRootSym), Set.of(BaseChunkCarrier.class.getName()))
                    ),
                    8,
                    64
            );

            StageCoverageGraph graph = graphs.get("MISSING_STATIC_HOLDER");
            assertTrue(graph.truncatedByBounds(), "Missing static functional holder class must truncate stage graph");
            assertTrue(graph.unexpandedFrontierSymbols().contains(missingHookField));
            assertTrue(graphs.get("INDIRECT_CLINIT_HOLDER").truncatedByBounds());
            assertTrue(graphs.get("INDIRECT_CLINIT_HOLDER").unexpandedFrontierSymbols().contains(missingHookField));
            assertTrue(
                    warnings.stream().anyMatch(r -> r.getMessage().contains(MissingStaticHookHolder.class.getName())),
                    "Expected Level.WARNING naming MissingStaticHookHolder"
            );

            Map<String, ProvenanceBinding> bindings = new LinkedHashMap<>();
            for (StageCoverageGraph g : graphs.values()) {
                for (String m : g.reachableMethods()) {
                    bind(bindings, m, CoverageProvenanceKind.IR_LIFTED, "ir");
                }
            }
            bind(bindings, sym(BaseChunkCarrier.class, "sections"), CoverageProvenanceKind.IR_LIFTED, "ir");

            Map<String, StageCoverageVerdict> verdicts = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                    graphs,
                    Map.of(),
                    bindings,
                    Set.of()
            );
            for (String stageId : List.of("MISSING_STATIC_HOLDER", "INDIRECT_CLINIT_HOLDER")) {
                StageCoverageVerdict verdict = verdicts.get(stageId);
                assertFalse(verdict.patchEligible(), stageId + " must block eligibility despite bound SAFE lambda");
                assertTrue(verdict.unaccountedMethods().contains(missingHookField));
                assertTrue(
                        verdict.unaccountedMethods().contains(consumerAcceptSym),
                        "Consumer#accept must fail closed as incomplete-closed-world-hierarchy when a holder class is missing: "
                                + verdict.unaccountedMethods()
                );
            }
        } finally {
            scannerLogger.removeHandler(handler);
        }
    }

    private static byte[] cloneFixedLengthClassNameBytes(byte[] template, byte[] needle, byte[] replacement) {
        assertEquals(needle.length, replacement.length);
        byte[] copy = template.clone();
        for (int i = 0; i <= copy.length - needle.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (copy[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                System.arraycopy(replacement, 0, copy, i, replacement.length);
                i += needle.length - 1;
            }
        }
        return copy;
    }

    // =========================================================================
    // Review Regression Fixtures & Tests (Non-JDK SAM, Array/Atomic Mutators, Interpreter Aborts)
    // =========================================================================

    @FunctionalInterface
    interface CustomChunkStepTask {
        void doWork(ProtoChunkCarrier chunk);
    }

    @FunctionalInterface
    interface PureIndyChunkStepTask {
        void applyStep(ProtoChunkCarrier chunk);
    }

    @FunctionalInterface
    interface MapClearerSam {
        void clearMap(Map<String, long[]> target);
    }

    static final class SafeBoundChunkStepTask implements CustomChunkStepTask {
        @Override
        public void doWork(ProtoChunkCarrier chunk) {
            int ignored = chunk.writeIdx;
            if (ignored < 0) {
                throw new IllegalStateException();
            }
        }
    }

    static final class UnboundStepTargets {
        static void hiddenSurfaceWrite(ProtoChunkCarrier chunk) {
            chunk.blendingData = "mutated-via-method-ref";
        }

        static void pureIndyCarve(ProtoChunkCarrier chunk) {
            chunk.carvingMaskBits = 0xCAFE;
        }
    }

    static final class CustomSamStepRegistry {
        static final CustomChunkStepTask MIXED_STEP_REF = UnboundStepTargets::hiddenSurfaceWrite;
        static final PureIndyChunkStepTask PURE_INDY_REF = UnboundStepTargets::pureIndyCarve;
        static final MapClearerSam JDK_MUTATOR_METHOD_REF = Map::clear;

        static void runMixedStep(CustomChunkStepTask task, ProtoChunkCarrier chunk) {
            task.doWork(chunk);
        }

        static void runPureIndyStep(PureIndyChunkStepTask task, ProtoChunkCarrier chunk) {
            task.applyStep(chunk);
        }

        static void runJdkMutatorMethodRefStep(MapClearerSam task, ProtoChunkCarrier chunk) {
            task.clearMap(chunk.heightmaps);
        }
    }

    static final class ExtraIntGenerator implements IntFunction< Object > {
        @Override
        public Object apply(int value) {
            return Integer.valueOf(value);
        }
    }

    static final class JdkArrayMutatorFixtures {
        static void sortCarrierSections(BaseChunkCarrier c) {
            Arrays.sort(c.sections);
        }

        static void parallelSortCarrierSections(BaseChunkCarrier c) {
            Arrays.parallelSort(c.sections);
        }

        static void setAllCarrierSections(BaseChunkCarrier c) {
            Arrays.setAll(c.sections, idx -> idx + 1);
        }

        static void parallelSetAllCarrierSections(BaseChunkCarrier c) {
            Arrays.parallelSetAll(c.sections, idx -> idx * 2);
        }

        static void parallelPrefixCarrierSections(BaseChunkCarrier c) {
            Arrays.parallelPrefix(c.sections, Integer::sum);
        }

        static List<Object> wrapCarrierAsMutableList(BaseChunkCarrier c) {
            return Arrays.asList(c.objectSlots);
        }

        static ByteBuffer wrapCarrierBytesMutable(BaseChunkCarrier c) {
            return ByteBuffer.wrap(c.rawBytes);
        }

        static void reflectiveArraySetInt(BaseChunkCarrier c) {
            Array.setInt(c.sections, 0, 99);
        }

        static void sortUnknownArrayParam(int[] unknownArr) {
            Arrays.sort(unknownArr);
        }

        static void setAllScratchWithParamCallback(IntFunction<Object> generator) {
            Object[] localScratch = new Object[4];
            Arrays.setAll(localScratch, generator);
        }

        static int readOnlyArrayOperations(BaseChunkCarrier c) {
            int[] cloned = Arrays.copyOf(c.sections, c.sections.length);
            Arrays.sort(cloned);
            int idx = Arrays.binarySearch(cloned, 3);
            int hash = Arrays.hashCode(c.sections);
            boolean eq = Arrays.equals(c.sections, c.biomes);
            int sum = Arrays.stream(c.sections).sum();
            return idx + hash + (eq ? 1 : 0) + sum;
        }
    }

    static final class MutableHolderFixtures {
        static void mutateAtomicRefArray(BaseChunkCarrier c, Object v) {
            c.stepFutures.set(0, v);
        }

        static void mutateAtomicRefViaGetter(BaseChunkCarrier c, Object v) {
            c.getStepFutures().lazySet(1, v);
        }

        static void mutateAtomicReference(BaseChunkCarrier c, Object v) {
            c.lightState.set(v);
        }

        static void mutateAtomicInteger(BaseChunkCarrier c) {
            c.ticketLevel.incrementAndGet();
        }

        static void mutateBitSet(BaseChunkCarrier c) {
            c.carvingBitSet.set(5);
        }

        static void mutateByteBuffer(BaseChunkCarrier c) {
            c.sectionByteBuf.put(0, (byte) 42);
        }

        static void mutateThreadLocal(BaseChunkCarrier c, Object v) {
            c.workerScratch.set(v);
        }

        static void completeCarrierFuture(BaseChunkCarrier c, Object v) {
            c.fullChunkFuture.complete(v);
        }

        static void mutateSynchronizedMapView(BaseChunkCarrier c) {
            java.util.Collections.synchronizedMap(c.heightmaps).clear();
        }

        static void mutateUnknownAtomicParam(AtomicReference<Object> unknownRef, Object v) {
            unknownRef.set(v);
        }

        static int readOnlyHolderOperations(BaseChunkCarrier c) {
            Object f0 = c.stepFutures.get(0);
            int len = c.stepFutures.length();
            Object l = c.lightState.get();
            int lvl = c.ticketLevel.get();
            boolean bit = c.carvingBitSet.get(5);
            int card = c.carvingBitSet.cardinality();
            byte b0 = c.sectionByteBuf.get(0);
            int rem = c.sectionByteBuf.remaining();
            boolean done = c.fullChunkFuture.isDone();
            BitSet localBits = new BitSet(8);
            localBits.set(1);
            AtomicInteger localCounter = new AtomicInteger(0);
            localCounter.incrementAndGet();
            return len + lvl + card + b0 + rem + (f0 != null ? 1 : 0) + (l != null ? 1 : 0) + (bit ? 1 : 0) + (done ? 1 : 0);
        }
    }

    static final class InterpreterEdgeFixtures {
        static int validFloatToIntCast(float v) {
            return (int) (v * 2.5f);
        }

        static void templateForTruncatedInstruction(ProtoChunkCarrier c) {
            int x = c.writeIdx + 1;
            if (x < -9999) {
                c.carvingMaskBits = x;
            }
        }

        static void templateForUnsupportedOpcode(ProtoChunkCarrier c) {
            int x = c.writeIdx + 2;
            if (x < -9999) {
                c.carvingMaskBits = x;
            }
        }

        static void templateForStackUnderflow() {
        }

        static void freshArrayEscapeBeforeCatchHandler(BaseChunkCarrier c) {
            int[][] box = new int[1][];
            box[0] = c.sections;
            try {
                if (c.writeIdx < 0) {
                    throw new RuntimeException("boom");
                }
            } catch (RuntimeException ex) {
                box[0][1] = 77;
            }
        }

        static Object budgetOverflowBeforeCallback(
                BaseChunkCarrier c,
                Function<BaseChunkCarrier, Object> deferredHook,
                int n
        ) {
            int[] v0 = c.sections;
            int[] v1 = null, v2 = null, v3 = null, v4 = null, v5 = null, v6 = null, v7 = null, v8 = null, v9 = null, v10 = null;
            int[] v11 = null, v12 = null, v13 = null, v14 = null, v15 = null, v16 = null, v17 = null, v18 = null, v19 = null, v20 = null;
            int[] v21 = null, v22 = null, v23 = null, v24 = null, v25 = null, v26 = null, v27 = null, v28 = null, v29 = null, v30 = null;
            int[] v31 = null, v32 = null, v33 = null, v34 = null, v35 = null, v36 = null, v37 = null, v38 = null, v39 = null, v40 = null;
            int[] v41 = null, v42 = null, v43 = null, v44 = null, v45 = null, v46 = null, v47 = null, v48 = null, v49 = null, v50 = null;
            int[] v51 = null, v52 = null, v53 = null, v54 = null, v55 = null, v56 = null, v57 = null, v58 = null, v59 = null, v60 = null;
            for (int i = 0; i < n; i++) {
                v60 = v59; v59 = v58; v58 = v57; v57 = v56; v56 = v55; v55 = v54; v54 = v53; v53 = v52; v52 = v51; v51 = v50;
                v50 = v49; v49 = v48; v48 = v47; v47 = v46; v46 = v45; v45 = v44; v44 = v43; v43 = v42; v42 = v41; v41 = v40;
                v40 = v39; v39 = v38; v38 = v37; v37 = v36; v36 = v35; v35 = v34; v34 = v33; v33 = v32; v32 = v31; v31 = v30;
                v30 = v29; v29 = v28; v28 = v27; v27 = v26; v26 = v25; v25 = v24; v24 = v23; v23 = v22; v22 = v21; v21 = v20;
                v20 = v19; v19 = v18; v18 = v17; v17 = v16; v16 = v15; v15 = v14; v14 = v13; v13 = v12; v12 = v11; v11 = v10;
                v10 = v9; v9 = v8; v8 = v7; v7 = v6; v6 = v5; v5 = v4; v4 = v3; v3 = v2; v2 = v1; v1 = v0;
            }
            if (v60 != null && v60.length == 999) {
                return deferredHook.apply(c);
            }
            return null;
        }
    }

    @Test
    @DisplayName("Non-JDK functional interfaces union invokedynamic method references with concrete subtype implementers")
    void nonJdkSamMethodReferencesUnionWithConcreteSubtypesAndFailClosed() {
        Function<String, byte[]> resolver = resolverFor(
                BaseChunkCarrier.class,
                ProtoChunkCarrier.class,
                CustomChunkStepTask.class,
                PureIndyChunkStepTask.class,
                MapClearerSam.class,
                SafeBoundChunkStepTask.class,
                UnboundStepTargets.class,
                CustomSamStepRegistry.class
        );
        Set<String> carriers = Set.of(BaseChunkCarrier.class.getName(), ProtoChunkCarrier.class.getName());

        Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                resolver,
                List.of(
                        SafeBoundChunkStepTask.class.getName(),
                        CustomSamStepRegistry.class.getName()
                ),
                List.of(
                        new StageSliceSpec(
                                "MIXED_SAM",
                                List.of(sym(CustomSamStepRegistry.class, "runMixedStep")),
                                carriers
                        ),
                        new StageSliceSpec(
                                "PURE_INDY_SAM",
                                List.of(sym(CustomSamStepRegistry.class, "runPureIndyStep")),
                                carriers
                        ),
                        new StageSliceSpec(
                                "JDK_MUTATOR_INDY_SAM",
                                List.of(sym(CustomSamStepRegistry.class, "runJdkMutatorMethodRefStep")),
                                carriers
                        )
                ),
                10,
                128
        );

        StageCoverageGraph mixedGraph = graphs.get("MIXED_SAM");
        String doWorkSym = sym(CustomChunkStepTask.class, "doWork");
        String safeSym = sym(SafeBoundChunkStepTask.class, "doWork");
        String hiddenRefSym = sym(UnboundStepTargets.class, "hiddenSurfaceWrite");
        String blendingField = sym(BaseChunkCarrier.class, "blendingData");

        assertTrue(mixedGraph.reachableMethods().contains(safeSym));
        assertTrue(
                mixedGraph.reachableMethods().contains(hiddenRefSym),
                "Non-JDK SAM dispatch must union invokedynamic method-ref targets with class implementers"
        );
        assertTrue(mixedGraph.resolvedSubtypeTargets().getOrDefault(doWorkSym, Set.of()).contains(safeSym));
        assertTrue(mixedGraph.resolvedSubtypeTargets().getOrDefault(doWorkSym, Set.of()).contains(hiddenRefSym));
        assertTrue(mixedGraph.mutatedCarrierFields().contains(blendingField));
        assertTrue(mixedGraph.edges().stream().anyMatch(
                e -> e.kind() == EdgeKind.INDY_LAMBDA && e.fromSymbol().equals(doWorkSym) && e.toSymbol().equals(hiddenRefSym)
        ));
        assertTrue(mixedGraph.edges().stream().anyMatch(
                e -> e.kind() == EdgeKind.FUNCTOR_TASK && e.toSymbol().equals(doWorkSym)
        ));

        // Binding ONLY the concrete class implementer SafeBoundChunkStepTask must STILL fail closed!
        Map<String, ProvenanceBinding> partialBindings = new LinkedHashMap<>();
        bind(partialBindings, sym(CustomSamStepRegistry.class, "runMixedStep"), CoverageProvenanceKind.IR_LIFTED, "ir");
        bind(partialBindings, safeSym, CoverageProvenanceKind.IR_LIFTED, "ir");
        bind(partialBindings, sym(CustomSamStepRegistry.class, "runPureIndyStep"), CoverageProvenanceKind.IR_LIFTED, "ir");
        bind(partialBindings, sym(CustomSamStepRegistry.class, "runJdkMutatorMethodRefStep"), CoverageProvenanceKind.IR_LIFTED, "ir");

        Map<String, StageCoverageVerdict> partialVerdicts = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                graphs,
                Map.of(),
                partialBindings,
                Set.of()
        );
        assertFalse(partialVerdicts.get("MIXED_SAM").patchEligible());
        assertTrue(partialVerdicts.get("MIXED_SAM").unaccountedMethods().contains(hiddenRefSym));
        assertTrue(partialVerdicts.get("MIXED_SAM").unaccountedMethods().contains(doWorkSym));
        assertTrue(partialVerdicts.get("MIXED_SAM").unaccountedFields().contains(blendingField));

        StageCoverageGraph pureGraph = graphs.get("PURE_INDY_SAM");
        String applyStepSym = sym(PureIndyChunkStepTask.class, "applyStep");
        String pureTargetSym = sym(UnboundStepTargets.class, "pureIndyCarve");
        String carveField = sym(ProtoChunkCarrier.class, "carvingMaskBits");
        assertTrue(pureGraph.reachableMethods().contains(pureTargetSym));
        assertTrue(pureGraph.mutatedCarrierFields().contains(carveField));
        assertTrue(pureGraph.edges().stream().anyMatch(
                e -> e.kind() == EdgeKind.INDY_LAMBDA && e.fromSymbol().equals(applyStepSym) && e.toSymbol().equals(pureTargetSym)
        ));
        assertFalse(partialVerdicts.get("PURE_INDY_SAM").patchEligible());
        assertFalse(
                partialVerdicts.get("JDK_MUTATOR_INDY_SAM").patchEligible(),
                "Mutating JDK method reference (Map::clear) on a SAM must not pass as BENIGN_INFRASTRUCTURE"
        );
        assertTrue(partialVerdicts.get("JDK_MUTATOR_INDY_SAM").unaccountedMethods().contains("java.util.Map#clear"));

        // Binding the indy method-ref targets + mutated fields makes both stages eligible
        Map<String, ProvenanceBinding> completeBindings = new LinkedHashMap<>(partialBindings);
        bind(completeBindings, hiddenRefSym, CoverageProvenanceKind.IR_LIFTED, "ir");
        bind(completeBindings, blendingField, CoverageProvenanceKind.IR_LIFTED, "ir");
        bind(completeBindings, pureTargetSym, CoverageProvenanceKind.IR_LIFTED, "ir");
        bind(completeBindings, carveField, CoverageProvenanceKind.IR_LIFTED, "ir");

        Map<String, StageCoverageVerdict> completeVerdicts = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                graphs,
                Map.of(),
                completeBindings,
                Set.of()
        );
        assertTrue(completeVerdicts.get("MIXED_SAM").patchEligible(), () -> "Got: " + completeVerdicts.get("MIXED_SAM"));
        assertTrue(completeVerdicts.get("PURE_INDY_SAM").patchEligible(), () -> "Got: " + completeVerdicts.get("PURE_INDY_SAM"));
    }

    @Test
    @DisplayName("JDK in-place array mutators and mutable array views fail closed while read-only array helpers pass")
    void jdkInPlaceArrayMutatorsAndViewsFailClosedWhileReadOnlyCallsPass() {
        Function<String, byte[]> resolver = resolverFor(
                BaseChunkCarrier.class,
                ProtoChunkCarrier.class,
                ExtraIntGenerator.class,
                JdkArrayMutatorFixtures.class
        );
        Set<String> carriers = Set.of(BaseChunkCarrier.class.getName(), ProtoChunkCarrier.class.getName());

        List<String> mutatorMethods = List.of(
                "sortCarrierSections",
                "parallelSortCarrierSections",
                "setAllCarrierSections",
                "parallelSetAllCarrierSections",
                "parallelPrefixCarrierSections",
                "wrapCarrierAsMutableList",
                "wrapCarrierBytesMutable",
                "reflectiveArraySetInt",
                "sortUnknownArrayParam",
                "setAllScratchWithParamCallback"
        );
        List<StageSliceSpec> specs = new ArrayList<>();
        for (String m : mutatorMethods) {
            specs.add(new StageSliceSpec(m, List.of(sym(JdkArrayMutatorFixtures.class, m)), carriers));
        }
        specs.add(new StageSliceSpec(
                "readOnlyArrayOperations",
                List.of(sym(JdkArrayMutatorFixtures.class, "readOnlyArrayOperations")),
                carriers
        ));

        Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                resolver,
                List.of(ExtraIntGenerator.class.getName()),
                specs,
                8,
                64
        );

        String sectionsField = sym(BaseChunkCarrier.class, "sections");
        assertTrue(graphs.get("sortCarrierSections").mutatedCarrierFields().contains(sectionsField));
        assertTrue(graphs.get("parallelSortCarrierSections").mutatedCarrierFields().contains(sectionsField));
        assertTrue(graphs.get("setAllCarrierSections").mutatedCarrierFields().contains(sectionsField));
        assertTrue(graphs.get("parallelSetAllCarrierSections").mutatedCarrierFields().contains(sectionsField));
        assertTrue(graphs.get("parallelPrefixCarrierSections").mutatedCarrierFields().contains(sectionsField));
        assertTrue(graphs.get("wrapCarrierAsMutableList").mutatedCarrierFields().contains(sym(BaseChunkCarrier.class, "objectSlots")));
        assertTrue(graphs.get("wrapCarrierBytesMutable").mutatedCarrierFields().contains(sym(BaseChunkCarrier.class, "rawBytes")));
        assertTrue(graphs.get("reflectiveArraySetInt").mutatedCarrierFields().contains(sectionsField));
        assertFalse(graphs.get("sortUnknownArrayParam").mutatedCarrierFields().isEmpty());
        assertTrue(graphs.get("setAllScratchWithParamCallback").reachableMethods().contains(sym(ExtraIntGenerator.class, "apply")));
        assertTrue(graphs.get("readOnlyArrayOperations").mutatedCarrierFields().isEmpty());

        Map<String, ProvenanceBinding> rootOnlyBindings = new LinkedHashMap<>();
        for (StageSliceSpec spec : specs) {
            for (String r : spec.rootMethodSymbols()) {
                bind(rootOnlyBindings, r, CoverageProvenanceKind.IR_LIFTED, "root");
            }
        }
        // Bind inline lambdas inside setAll/parallelSetAll so ONLY the array mutation blocks those stages
        for (String stageId : List.of("setAllCarrierSections", "parallelSetAllCarrierSections")) {
            for (String rm : graphs.get(stageId).reachableMethods()) {
                if (rm.contains("lambda$")) {
                    bind(rootOnlyBindings, rm, CoverageProvenanceKind.IR_LIFTED, "lambda");
                }
            }
        }

        Map<String, StageCoverageVerdict> verdicts = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                graphs,
                Map.of(),
                rootOnlyBindings,
                Set.of()
        );
        for (String m : mutatorMethods) {
            assertFalse(
                    verdicts.get(m).patchEligible(),
                    "Expected fail-closed patchEligible=false for array mutator stage " + m + ", got: " + verdicts.get(m)
            );
        }
        assertTrue(
                verdicts.get("readOnlyArrayOperations").patchEligible(),
                () -> "Read-only array helpers should be eligible when root is bound: " + verdicts.get("readOnlyArrayOperations")
        );
    }

    @Test
    @DisplayName("Atomic, BitSet, ByteBuffer, ThreadLocal, and CompletableFuture carrier fields track in-place mutations")
    void atomicBitSetBufferAndMutableJdkHolderFieldsTrackMutations() {
        Function<String, byte[]> resolver = resolverFor(
                BaseChunkCarrier.class,
                ProtoChunkCarrier.class,
                MutableHolderFixtures.class
        );
        Set<String> carriers = Set.of(BaseChunkCarrier.class.getName(), ProtoChunkCarrier.class.getName());

        Map<String, String> methodToExpectedCarrierField = new LinkedHashMap<>();
        methodToExpectedCarrierField.put("mutateAtomicRefArray", sym(BaseChunkCarrier.class, "stepFutures"));
        methodToExpectedCarrierField.put("mutateAtomicRefViaGetter", sym(BaseChunkCarrier.class, "stepFutures"));
        methodToExpectedCarrierField.put("mutateAtomicReference", sym(BaseChunkCarrier.class, "lightState"));
        methodToExpectedCarrierField.put("mutateAtomicInteger", sym(BaseChunkCarrier.class, "ticketLevel"));
        methodToExpectedCarrierField.put("mutateBitSet", sym(BaseChunkCarrier.class, "carvingBitSet"));
        methodToExpectedCarrierField.put("mutateByteBuffer", sym(BaseChunkCarrier.class, "sectionByteBuf"));
        methodToExpectedCarrierField.put("mutateThreadLocal", sym(BaseChunkCarrier.class, "workerScratch"));
        methodToExpectedCarrierField.put("completeCarrierFuture", sym(BaseChunkCarrier.class, "fullChunkFuture"));
        methodToExpectedCarrierField.put("mutateSynchronizedMapView", sym(BaseChunkCarrier.class, "heightmaps"));

        List<StageSliceSpec> specs = new ArrayList<>();
        for (String m : methodToExpectedCarrierField.keySet()) {
            specs.add(new StageSliceSpec(m, List.of(sym(MutableHolderFixtures.class, m)), carriers));
        }
        specs.add(new StageSliceSpec(
                "mutateUnknownAtomicParam",
                List.of(sym(MutableHolderFixtures.class, "mutateUnknownAtomicParam")),
                carriers
        ));
        specs.add(new StageSliceSpec(
                "readOnlyHolderOperations",
                List.of(sym(MutableHolderFixtures.class, "readOnlyHolderOperations")),
                carriers
        ));

        Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                resolver,
                specs,
                6,
                64
        );

        Map<String, ProvenanceBinding> rootOnlyBindings = new LinkedHashMap<>();
        for (StageSliceSpec spec : specs) {
            bind(rootOnlyBindings, spec.rootMethodSymbols().get(0), CoverageProvenanceKind.IR_LIFTED, "root");
        }

        Map<String, StageCoverageVerdict> verdicts = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                graphs,
                Map.of(),
                rootOnlyBindings,
                Set.of()
        );

        for (Map.Entry<String, String> entry : methodToExpectedCarrierField.entrySet()) {
            String stage = entry.getKey();
            String expectedField = entry.getValue();
            assertTrue(
                    graphs.get(stage).mutatedCarrierFields().contains(expectedField),
                    "Stage " + stage + " must record carrier field mutation " + expectedField
                            + ", got: " + graphs.get(stage).mutatedCarrierFields()
            );
            assertFalse(verdicts.get(stage).patchEligible());
            assertTrue(verdicts.get(stage).unaccountedFields().contains(expectedField));
        }
        assertTrue(graphs.get("readOnlyHolderOperations").mutatedCarrierFields().isEmpty());
        assertFalse(graphs.get("mutateUnknownAtomicParam").mutatedCarrierFields().isEmpty());
        assertFalse(verdicts.get("mutateUnknownAtomicParam").patchEligible());
        assertTrue(
                verdicts.get("readOnlyHolderOperations").patchEligible(),
                () -> "Read-only holder getters should pass: " + verdicts.get("readOnlyHolderOperations")
        );
    }

    @Test
    @DisplayName("Malformed, unsupported-opcode, and budget-exceeded bytecode aborts with both callback and field markers")
    void malformedUnsupportedAndBudgetExceededBytecodeAbortsWithBothMarkersAndFreshArrayCatchReseeds() throws IOException {
        byte[] baseBytes = readClassBytes(InterpreterEdgeFixtures.class);
        // 1. Truncated instruction: overwrite final RETURN (0xB1) with INVOKEVIRTUAL (0xB6, requires 2 more bytes)
        byte[] truncatedClassBytes = patchMethodCodeByteFromEnd(
                baseBytes,
                "templateForTruncatedInstruction",
                0,
                (byte) 0xB6
        );
        // 2. Unsupported opcode: overwrite first instruction with unassigned JVM opcode 0xCB
        byte[] unsupportedOpBytes = patchMethodCodeByteFromStart(
                truncatedClassBytes,
                "templateForUnsupportedOpcode",
                0,
                (byte) 0xCB
        );
        // 3. Operand stack underflow: overwrite RETURN (0xB1) on empty void method with POP (0x57)
        byte[] allPatchedBytes = patchMethodCodeByteFromStart(
                unsupportedOpBytes,
                "templateForStackUnderflow",
                0,
                (byte) 0x57
        );

        Map<String, byte[]> customTable = new LinkedHashMap<>();
        for (Class<?> cls : List.of(
                BaseChunkCarrier.class,
                ProtoChunkCarrier.class,
                SafeFunctionImpl.class,
                EvilFunctionImpl.class
        )) {
            byte[] b = readClassBytes(cls);
            customTable.put(cls.getName(), b);
            customTable.put(cls.getName().replace('.', '/'), b);
        }
        customTable.put(InterpreterEdgeFixtures.class.getName(), allPatchedBytes);
        customTable.put(InterpreterEdgeFixtures.class.getName().replace('.', '/'), allPatchedBytes);

        Set<String> carriers = Set.of(BaseChunkCarrier.class.getName(), ProtoChunkCarrier.class.getName());
        Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                customTable::get,
                List.of(SafeFunctionImpl.class.getName(), EvilFunctionImpl.class.getName()),
                List.of(
                        new StageSliceSpec(
                                "TRUNCATED_CODE",
                                List.of(sym(InterpreterEdgeFixtures.class, "templateForTruncatedInstruction")),
                                carriers
                        ),
                        new StageSliceSpec(
                                "UNSUPPORTED_OPCODE",
                                List.of(sym(InterpreterEdgeFixtures.class, "templateForUnsupportedOpcode")),
                                carriers
                        ),
                        new StageSliceSpec(
                                "STACK_UNDERFLOW",
                                List.of(sym(InterpreterEdgeFixtures.class, "templateForStackUnderflow")),
                                carriers
                        ),
                        new StageSliceSpec(
                                "BUDGET_OVERFLOW",
                                List.of(sym(InterpreterEdgeFixtures.class, "budgetOverflowBeforeCallback")),
                                carriers
                        ),
                        new StageSliceSpec(
                                "VALID_F2I",
                                List.of(sym(InterpreterEdgeFixtures.class, "validFloatToIntCast")),
                                carriers
                        ),
                        new StageSliceSpec(
                                "FRESH_ARRAY_CATCH",
                                List.of(sym(InterpreterEdgeFixtures.class, "freshArrayEscapeBeforeCatchHandler")),
                                carriers
                        )
                ),
                8,
                64
        );

        // Fresh reference array escaped BEFORE try/catch must still re-seed catch handler and record sections write
        assertTrue(
                graphs.get("FRESH_ARRAY_CATCH")
                        .mutatedCarrierFields()
                        .contains(sym(BaseChunkCarrier.class, "sections")),
                "Catch handler store through escaped fresh array must be recorded after fresh-array restart"
        );

        Map<String, ProvenanceBinding> bindings = new LinkedHashMap<>();
        bind(bindings, sym(InterpreterEdgeFixtures.class, "templateForTruncatedInstruction"), CoverageProvenanceKind.IR_LIFTED, "ir");
        bind(bindings, sym(InterpreterEdgeFixtures.class, "templateForUnsupportedOpcode"), CoverageProvenanceKind.IR_LIFTED, "ir");
        bind(bindings, sym(InterpreterEdgeFixtures.class, "templateForStackUnderflow"), CoverageProvenanceKind.IR_LIFTED, "ir");
        bind(bindings, sym(InterpreterEdgeFixtures.class, "budgetOverflowBeforeCallback"), CoverageProvenanceKind.IR_LIFTED, "ir");
        bind(bindings, sym(InterpreterEdgeFixtures.class, "validFloatToIntCast"), CoverageProvenanceKind.IR_LIFTED, "ir");
        bind(bindings, sym(SafeFunctionImpl.class, "apply"), CoverageProvenanceKind.IR_LIFTED, "ir");

        Map<String, StageCoverageVerdict> verdicts = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                graphs,
                Map.of(),
                bindings,
                Set.of()
        );

        for (String abortedStage : List.of("TRUNCATED_CODE", "UNSUPPORTED_OPCODE", "STACK_UNDERFLOW", "BUDGET_OVERFLOW")) {
            StageCoverageVerdict v = verdicts.get(abortedStage);
            assertFalse(v.patchEligible(), "Aborted interpreter stage " + abortedStage + " must fail closed");
            assertFalse(
                    v.unaccountedMethods().isEmpty(),
                    "Aborted interpreter stage " + abortedStage + " must emit an UNACCOUNTED callback/dispatch marker, got: " + v
            );
            assertFalse(
                    v.unaccountedFields().isEmpty(),
                    "Aborted interpreter stage " + abortedStage + " must emit an UNACCOUNTED field mutation marker, got: " + v
            );
        }

        // Valid F2I (0x8B) opcode must model cleanly with zero abort markers
        assertTrue(verdicts.get("VALID_F2I").patchEligible(), () -> "Got: " + verdicts.get("VALID_F2I"));
    }

    @Test
    @DisplayName("Empty, stageId-mismatched, null, or error-marked StageRuntimeObservations fail closed")
    void emptyMismatchedOrErrorMarkedRuntimeObservationsFailClosed() {
        Function<String, byte[]> resolver = resolverFor(
                BaseChunkCarrier.class,
                ProtoChunkCarrier.class,
                SurfaceCondition.class,
                SteepSlopeCondition.class,
                SyntheticPipelineStages.class
        );
        Set<String> carriers = Set.of(BaseChunkCarrier.class.getName());
        Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                resolver,
                List.of(SteepSlopeCondition.class.getName()),
                List.of(
                        new StageSliceSpec("BIOMES", List.of(sym(SyntheticPipelineStages.class, "populateBiomes")), carriers),
                        new StageSliceSpec("SURFACE", List.of(sym(SyntheticPipelineStages.class, "runCleanSurfaceStage")), carriers, Set.of(), Set.of(SteepSlopeCondition.class.getName()))
                ),
                8,
                64
        );

        Map<String, ProvenanceBinding> bindings = new LinkedHashMap<>();
        bind(bindings, sym(SyntheticPipelineStages.class, "populateBiomes"), CoverageProvenanceKind.IR_LIFTED, "BiomeLut");
        bind(bindings, sym(BaseChunkCarrier.class, "biomes"), CoverageProvenanceKind.IR_LIFTED, "BiomeHydrator");
        for (String m : graphs.get("SURFACE").reachableMethods()) {
            if (!graphs.get("SURFACE").abstractDispatchMethods().contains(m)) {
                bind(bindings, m, CoverageProvenanceKind.IR_LIFTED, "SurfaceVm");
            }
        }
        bind(bindings, sym(BaseChunkCarrier.class, "sections"), CoverageProvenanceKind.IR_LIFTED, "SectionHydrator");

        // Baseline without runtime map entries is static-eligible
        CoverageProvenanceLedger staticBaseline = CoverageProvenanceLedger.reconcile(
                graphs, Map.of(), bindings, Set.of());
        assertTrue(staticBaseline.isStagePatchEligible("BIOMES"));
        assertTrue(staticBaseline.isStagePatchEligible("SURFACE"));

        // 1. Explicitly supplied empty observation must fail closed
        CoverageProvenanceLedger emptyObsLedger = CoverageProvenanceLedger.reconcile(
                graphs,
                Map.of("BIOMES", StageRuntimeObservation.empty("BIOMES")),
                bindings,
                Set.of());
        assertFalse(
                emptyObsLedger.isStagePatchEligible("BIOMES"),
                "Stage with empty StageRuntimeObservation must fail closed");
        assertFalse(emptyObsLedger.verdictForStage("BIOMES").unaccountedMethods().isEmpty());

        // 2. Mismatched map key vs StageRuntimeObservation#stageId() must fail closed on both stages
        CoverageProvenanceLedger mismatchedObsLedger = CoverageProvenanceLedger.reconcile(
                graphs,
                Map.of(
                        "BIOMES",
                        new StageRuntimeObservation(
                                "SURFACE",
                                Set.of(sym(SyntheticPipelineStages.class, "populateBiomes")),
                                Set.of(),
                                Set.of(),
                                Set.of())),
                bindings,
                Set.of());
        assertFalse(
                mismatchedObsLedger.isStagePatchEligible("BIOMES"),
                "Map key with mismatched StageRuntimeObservation#stageId() must fail closed");
        assertFalse(
                mismatchedObsLedger.isStagePatchEligible("SURFACE"),
                "Target stage referenced by mismatched StageRuntimeObservation#stageId() must also fail closed");

        // 3. Error marker inside StageRuntimeObservation must fail closed
        CoverageProvenanceLedger errorObsLedger = CoverageProvenanceLedger.reconcile(
                graphs,
                Map.of(
                        "BIOMES",
                        new StageRuntimeObservation(
                                "BIOMES",
                                Set.of(sym(SyntheticPipelineStages.class, "populateBiomes")),
                                Set.of(),
                                Set.of("<canary-error:TimeoutException>"),
                                Set.of())),
                bindings,
                Set.of());
        assertFalse(
                errorObsLedger.isStagePatchEligible("BIOMES"),
                "StageRuntimeObservation carrying error marker must fail closed");
    }

    @Test
    @DisplayName("STRICT_EXACT rejects APPROXIMATE_EQUIVALENT and STAMP_ATLAS while STATISTICAL_VANILLA_LIKE accepts both and still catches unmapped work")
    void provenancePolicyStrictExactVsStatisticalVanillaLikeEnforcesModeContracts() {
        Function<String, byte[]> resolver = resolverFor(
                BaseChunkCarrier.class,
                ProtoChunkCarrier.class,
                DensitySampler.class,
                BasePerlinSampler.class,
                ShiftedPerlinSampler.class,
                SurfaceCondition.class,
                SteepSlopeCondition.class,
                OakTreeStampPlacer.class,
                CaveCarverFunctor.class,
                CustomPaperSurfaceHook.class,
                SyntheticPipelineStages.class
        );
        Set<String> carriers = Set.of(BaseChunkCarrier.class.getName(), ProtoChunkCarrier.class.getName());
        List<StageSliceSpec> stages = List.of(
                new StageSliceSpec(
                        "BIOMES",
                        List.of(sym(SyntheticPipelineStages.class, "populateBiomes")),
                        carriers
                ),
                new StageSliceSpec(
                        "NOISE",
                        List.of(sym(SyntheticPipelineStages.class, "runNoiseStage")),
                        carriers,
                        Set.of(),
                        Set.of(ShiftedPerlinSampler.class.getName())
                ),
                new StageSliceSpec(
                        "SURFACE",
                        List.of(sym(SyntheticPipelineStages.class, "runCleanSurfaceStage")),
                        carriers,
                        Set.of(),
                        Set.of(SteepSlopeCondition.class.getName())
                ),
                new StageSliceSpec(
                        "FEATURES",
                        List.of(sym(SyntheticPipelineStages.class, "runCarversAndFeaturesStage")),
                        carriers
                )
        );
        Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                resolver,
                List.of(ShiftedPerlinSampler.class.getName(), SteepSlopeCondition.class.getName()),
                stages,
                10,
                128
        );

        Map<String, ProvenanceBinding> bindings = new LinkedHashMap<>();
        bind(bindings, sym(SyntheticPipelineStages.class, "populateBiomes"), CoverageProvenanceKind.IR_LIFTED, "BiomeLut");
        bind(bindings, sym(BaseChunkCarrier.class, "biomes"), CoverageProvenanceKind.IR_LIFTED, "BiomeHydrator");

        for (String m : graphs.get("NOISE").reachableMethods()) {
            if (!graphs.get("NOISE").abstractDispatchMethods().contains(m)) {
                bind(bindings, m, CoverageProvenanceKind.APPROXIMATE_EQUIVALENT, "Macro13Noise");
            }
        }
        bind(bindings, sym(BaseChunkCarrier.class, "sections"), CoverageProvenanceKind.APPROXIMATE_EQUIVALENT, "Macro13Sections");
        bind(bindings, sym(BaseChunkCarrier.class, "heightmaps"), CoverageProvenanceKind.APPROXIMATE_EQUIVALENT, "Macro13Heightmaps");

        for (String m : graphs.get("SURFACE").reachableMethods()) {
            if (!graphs.get("SURFACE").abstractDispatchMethods().contains(m)) {
                bind(bindings, m, CoverageProvenanceKind.APPROXIMATE_EQUIVALENT, "FastSkipSurfaceVm");
            }
        }

        for (String m : graphs.get("FEATURES").reachableMethods()) {
            if (!graphs.get("FEATURES").abstractDispatchMethods().contains(m)) {
                bind(bindings, m, CoverageProvenanceKind.STAMP_ATLAS, "CompiledGpuStampAtlas#oak");
            }
        }
        bind(bindings, sym(ProtoChunkCarrier.class, "carvingMaskBits"), CoverageProvenanceKind.STAMP_ATLAS, "StampCarver");
        bind(bindings, sym(BaseChunkCarrier.class, "blockEntities"), CoverageProvenanceKind.STAMP_ATLAS, "StampBlockEntities");

        Set<String> reqFields = Set.of(
                sym(BaseChunkCarrier.class, "biomes"),
                sym(BaseChunkCarrier.class, "sections"),
                sym(BaseChunkCarrier.class, "heightmaps"),
                sym(BaseChunkCarrier.class, "blockEntities")
        );

        Map<String, StageCoverageVerdict> strictVerdicts = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                graphs,
                Map.of(),
                bindings,
                reqFields,
                ProvenanceEvaluationPolicy.STRICT_EXACT
        );
        assertTrue(strictVerdicts.get("BIOMES").patchEligible(), "IR_LIFTED BIOMES must pass STRICT_EXACT");
        assertFalse(strictVerdicts.get("NOISE").patchEligible(), "APPROXIMATE_EQUIVALENT NOISE must fail STRICT_EXACT");
        assertFalse(strictVerdicts.get("NOISE").unaccountedMethods().isEmpty());
        assertFalse(strictVerdicts.get("NOISE").unaccountedFields().isEmpty());
        assertFalse(strictVerdicts.get("NOISE").unaccountedAsyncHops().isEmpty());
        assertFalse(strictVerdicts.get("SURFACE").patchEligible(), "APPROXIMATE_EQUIVALENT SURFACE must fail STRICT_EXACT");
        assertFalse(strictVerdicts.get("FEATURES").patchEligible(), "STAMP_ATLAS FEATURES must fail STRICT_EXACT");

        Map<String, StageCoverageVerdict> likeVerdicts = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                graphs,
                Map.of(),
                bindings,
                reqFields,
                ProvenanceEvaluationPolicy.STATISTICAL_VANILLA_LIKE
        );
        for (String stageId : List.of("BIOMES", "NOISE", "SURFACE", "FEATURES")) {
            assertTrue(
                    likeVerdicts.get(stageId).patchEligible(),
                    "Expected STATISTICAL_VANILLA_LIKE eligibility for " + stageId + ", got: " + likeVerdicts.get(stageId)
            );
        }

        // Unmapped method, carrier field, and async lambda must STILL fail under STATISTICAL_VANILLA_LIKE
        Map<String, ProvenanceBinding> incompleteBindings = new LinkedHashMap<>(bindings);
        incompleteBindings.remove(sym(ShiftedPerlinSampler.class, "sample"));
        incompleteBindings.remove(sym(BaseChunkCarrier.class, "heightmaps"));
        for (String asyncTarget : graphs.get("NOISE").scheduledAsyncTargets()) {
            incompleteBindings.remove(asyncTarget);
        }
        StageCoverageVerdict incompleteNoise = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                graphs,
                Map.of(),
                incompleteBindings,
                reqFields,
                ProvenanceEvaluationPolicy.STATISTICAL_VANILLA_LIKE
        ).get("NOISE");
        assertFalse(incompleteNoise.patchEligible());
        assertTrue(incompleteNoise.unaccountedMethods().contains(sym(ShiftedPerlinSampler.class, "sample")));
        assertTrue(incompleteNoise.unaccountedFields().contains(sym(BaseChunkCarrier.class, "heightmaps")));
        assertFalse(incompleteNoise.unaccountedAsyncHops().isEmpty());
    }

    @Test
    @DisplayName("Non-worldgen collection, crash, and chunk-holder sinks prune deterministically without skipping levelgen mutators or MoonriseAsyncFeatureTask")
    void nonWorldgenInfrastructureSinksPruneWithoutSkippingLevelgenOrMoonriseAsyncHooks() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
                "it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap",
                """
                package it.unimi.dsi.fastutil.longs;
                public class Long2ObjectOpenHashMap<V> {
                    public V get(long k) { return null; }
                }
                """
        );
        sources.put(
                "com.google.common.base.Preconditions",
                """
                package com.google.common.base;
                public final class Preconditions {
                    public static <T> T checkNotNull(T ref) { return ref; }
                }
                """
        );
        sources.put(
                "com.google.common.collect.ImmutableList",
                """
                package com.google.common.collect;
                public final class ImmutableList {
                    public static int sizeHint() { return 4; }
                }
                """
        );
        sources.put(
                "ca.spottedleaf.concurrentutil.util.ConcurrentUtil",
                """
                package ca.spottedleaf.concurrentutil.util;
                public final class ConcurrentUtil {
                    public static void backoff() {}
                }
                """
        );
        sources.put(
                "net.minecraft.CrashReportDetail",
                """
                package net.minecraft;
                @FunctionalInterface
                public interface CrashReportDetail<V> {
                    V call() throws Exception;
                }
                """
        );
        sources.put(
                "net.minecraft.CrashReportCategory",
                """
                package net.minecraft;
                public final class CrashReportCategory {
                    public void setDetail(String name, CrashReportDetail<String> detail) {}
                }
                """
        );
        sources.put(
                "net.minecraft.CrashReport",
                """
                package net.minecraft;
                public final class CrashReport {
                    public static CrashReport forThrowable(Throwable t, String msg) { return new CrashReport(); }
                    public CrashReportCategory addCategory(String title) { return new CrashReportCategory(); }
                }
                """
        );
        sources.put(
                "net.minecraft.world.entity.player.Player",
                """
                package net.minecraft.world.entity.player;
                public class Player {
                    public String debugInfo() { return "p"; }
                }
                """
        );
        sources.put(
                "ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemChunkHolder",
                """
                package ca.spottedleaf.moonrise.patches.chunk_system.level.chunk;
                import java.util.List;
                import net.minecraft.world.entity.player.Player;
                public class ChunkSystemChunkHolder {
                    public List<Player> moonrise$getPlayers(boolean onlyBorder) { return List.of(); }
                }
                """
        );
        sources.put(
                "ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder",
                """
                package ca.spottedleaf.moonrise.patches.chunk_system.scheduling;
                import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
                import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
                public class NewChunkHolder {
                    public final Long2ObjectOpenHashMap<Object> tasks = new Long2ObjectOpenHashMap<>();
                    public boolean saveEntities() { ConcurrentUtil.backoff(); return false; }
                }
                """
        );
        sources.put(
                "net.minecraft.world.level.chunk.ChunkAccess",
                """
                package net.minecraft.world.level.chunk;
                public class ChunkAccess {
                    public final int[] sections = new int[16];
                    public final int[] biomes = new int[16];
                    public final long[] heightmaps = new long[16];
                    public int lightCorrect;
                    public void setBlockVoxel(int idx, int state) {
                        this.sections[idx & 15] = state;
                        this.heightmaps[idx & 15] = state;
                        this.lightCorrect = 1;
                    }
                }
                """
        );
        sources.put(
                "net.minecraft.world.level.biome.BiomeSource",
                """
                package net.minecraft.world.level.biome;
                import net.minecraft.world.level.chunk.ChunkAccess;
                public class BiomeSource {
                    public void fillBiomes(ChunkAccess chunk) { chunk.biomes[0] = 7; }
                }
                """
        );
        sources.put(
                "net.minecraft.world.level.levelgen.SurfaceRules",
                """
                package net.minecraft.world.level.levelgen;
                import net.minecraft.world.level.chunk.ChunkAccess;
                public final class SurfaceRules {
                    public static void applyRules(ChunkAccess chunk) { chunk.setBlockVoxel(1, 2); }
                }
                """
        );
        sources.put(
                "net.minecraft.world.level.levelgen.carver.WorldCarver",
                """
                package net.minecraft.world.level.levelgen.carver;
                import net.minecraft.world.level.chunk.ChunkAccess;
                public class WorldCarver {
                    public void carve(ChunkAccess chunk) { chunk.setBlockVoxel(2, 0); }
                }
                """
        );
        sources.put(
                "net.minecraft.world.level.levelgen.feature.Feature",
                """
                package net.minecraft.world.level.levelgen.feature;
                import net.minecraft.world.level.chunk.ChunkAccess;
                public class Feature {
                    public void place(ChunkAccess chunk) { chunk.setBlockVoxel(3, 9); }
                }
                """
        );
        sources.put(
                "ca.spottedleaf.moonrise.patches.chunk_system.MoonriseAsyncFeatureTask",
                """
                package ca.spottedleaf.moonrise.patches.chunk_system;
                import net.minecraft.world.level.chunk.ChunkAccess;
                public final class MoonriseAsyncFeatureTask {
                    public static ChunkAccess decorateAsync(ChunkAccess chunk) {
                        chunk.setBlockVoxel(4, 99);
                        return chunk;
                    }
                }
                """
        );
        sources.put(
                "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator",
                """
                package net.minecraft.world.level.levelgen;
                import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
                import ca.spottedleaf.moonrise.patches.chunk_system.MoonriseAsyncFeatureTask;
                import ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemChunkHolder;
                import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
                import com.google.common.base.Preconditions;
                import com.google.common.collect.ImmutableList;
                import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
                import java.util.concurrent.CompletableFuture;
                import net.minecraft.CrashReport;
                import net.minecraft.CrashReportCategory;
                import net.minecraft.world.entity.player.Player;
                import net.minecraft.world.level.biome.BiomeSource;
                import net.minecraft.world.level.chunk.ChunkAccess;
                import net.minecraft.world.level.levelgen.carver.WorldCarver;
                import net.minecraft.world.level.levelgen.feature.Feature;

                public class NoiseBasedChunkGenerator {
                    public void generateTerrainWithInfra(
                            ChunkAccess chunk,
                            BiomeSource biomeSource,
                            WorldCarver carver,
                            ChunkSystemChunkHolder holder,
                            NewChunkHolder newHolder,
                            Player player
                    ) {
                        Preconditions.checkNotNull(chunk);
                        Long2ObjectOpenHashMap<Object> scratch = new Long2ObjectOpenHashMap<>();
                        scratch.get(ImmutableList.sizeHint());
                        ConcurrentUtil.backoff();
                        holder.moonrise$getPlayers(false);
                        newHolder.saveEntities();
                        player.debugInfo();
                        try {
                            biomeSource.fillBiomes(chunk);
                            SurfaceRules.applyRules(chunk);
                            carver.carve(chunk);
                        } catch (Throwable t) {
                            CrashReport report = CrashReport.forThrowable(t, "worldgen");
                            CrashReportCategory cat = report.addCategory("chunk");
                            cat.setDetail("pos", () -> player.debugInfo());
                        }
                    }

                    public CompletableFuture<ChunkAccess> generateFeaturesWithMoonriseHook(
                            ChunkAccess chunk,
                            Feature feature
                    ) {
                        feature.place(chunk);
                        return CompletableFuture.supplyAsync(() -> MoonriseAsyncFeatureTask.decorateAsync(chunk));
                    }
                }
                """
        );

        Map<String, byte[]> compiled = compileInMemorySources(sources);
        // Remove infrastructure and Player .class bytes from resolver except carriers/worldgen so any attempt to
        // expand into fastutil, Guava, concurrentutil, CrashReport*, or Player#debugInfo would fail-closed or exhaust budget!
        Map<String, byte[]> worldgenOnlyResolverMap = new LinkedHashMap<>(compiled);
        worldgenOnlyResolverMap.remove("it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap");
        worldgenOnlyResolverMap.remove("com.google.common.base.Preconditions");
        worldgenOnlyResolverMap.remove("com.google.common.collect.ImmutableList");
        worldgenOnlyResolverMap.remove("ca.spottedleaf.concurrentutil.util.ConcurrentUtil");
        worldgenOnlyResolverMap.remove("net.minecraft.CrashReport");
        worldgenOnlyResolverMap.remove("net.minecraft.CrashReportCategory");
        worldgenOnlyResolverMap.remove("net.minecraft.CrashReportDetail");
        worldgenOnlyResolverMap.remove("net.minecraft.world.entity.player.Player");

        Set<String> carriers = Set.of(
                "net.minecraft.world.level.chunk.ChunkAccess",
                "ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder"
        );
        Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                name -> worldgenOnlyResolverMap.get(name.replace('/', '.')),
                List.of(),
                List.of(
                        new StageSliceSpec(
                                "TERRAIN",
                                List.of("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator#generateTerrainWithInfra"),
                                carriers
                        ),
                        new StageSliceSpec(
                                "FEATURES",
                                List.of("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator#generateFeaturesWithMoonriseHook"),
                                carriers
                        )
                ),
                10,
                64,
                12
        );

        StageCoverageGraph terrain = graphs.get("TERRAIN");
        assertFalse(
                terrain.truncatedByBounds(),
                "Non-worldgen infrastructure pruning must prevent class-budget exhaustion and missing-body truncation: "
                        + terrain.unexpandedFrontierSymbols()
        );
        assertTrue(terrain.reachableMethods().contains("net.minecraft.world.level.biome.BiomeSource#fillBiomes"));
        assertTrue(terrain.reachableMethods().contains("net.minecraft.world.level.levelgen.SurfaceRules#applyRules"));
        assertTrue(terrain.reachableMethods().contains("net.minecraft.world.level.levelgen.carver.WorldCarver#carve"));
        assertTrue(terrain.reachableMethods().contains("net.minecraft.world.level.chunk.ChunkAccess#setBlockVoxel"));
        assertTrue(terrain.mutatedCarrierFields().contains("net.minecraft.world.level.chunk.ChunkAccess#sections"));
        assertTrue(terrain.mutatedCarrierFields().contains("net.minecraft.world.level.chunk.ChunkAccess#biomes"));
        assertTrue(terrain.mutatedCarrierFields().contains("net.minecraft.world.level.chunk.ChunkAccess#heightmaps"));
        assertTrue(terrain.mutatedCarrierFields().contains("net.minecraft.world.level.chunk.ChunkAccess#lightCorrect"));

        Map<String, ProvenanceBinding> bindings = new LinkedHashMap<>();
        for (String worldgenMethod : List.of(
                "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator#generateTerrainWithInfra",
                "net.minecraft.world.level.biome.BiomeSource#fillBiomes",
                "net.minecraft.world.level.levelgen.SurfaceRules#applyRules",
                "net.minecraft.world.level.levelgen.carver.WorldCarver#carve",
                "net.minecraft.world.level.chunk.ChunkAccess#setBlockVoxel",
                "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator#generateFeaturesWithMoonriseHook",
                "net.minecraft.world.level.levelgen.feature.Feature#place"
        )) {
            bind(bindings, worldgenMethod, CoverageProvenanceKind.IR_LIFTED, "lifted");
        }
        for (String field : List.of(
                "net.minecraft.world.level.chunk.ChunkAccess#sections",
                "net.minecraft.world.level.chunk.ChunkAccess#biomes",
                "net.minecraft.world.level.chunk.ChunkAccess#heightmaps",
                "net.minecraft.world.level.chunk.ChunkAccess#lightCorrect"
        )) {
            bind(bindings, field, CoverageProvenanceKind.IR_LIFTED, "lifted-field");
        }

        StageRuntimeObservation terrainObs = new StageRuntimeObservation(
                "TERRAIN",
                Set.of(
                        "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator#generateTerrainWithInfra",
                        "it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap#get",
                        "com.google.common.collect.ImmutableList#sizeHint",
                        "ca.spottedleaf.concurrentutil.util.ConcurrentUtil#backoff",
                        "net.minecraft.CrashReport#forThrowable",
                        "ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemChunkHolder#moonrise$getPlayers",
                        "ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder#saveEntities",
                        "net.minecraft.world.entity.player.Player#debugInfo"
                ),
                Set.of("net.minecraft.world.level.chunk.ChunkAccess#sections"),
                Set.of(),
                Set.of()
        );

        Map<String, StageCoverageVerdict> verdicts = PipelineCoverageGraphScanner.reconcileCoverageLedger(
                graphs,
                Map.of("TERRAIN", terrainObs),
                bindings,
                Set.of("net.minecraft.world.level.chunk.ChunkAccess#sections"),
                ProvenanceEvaluationPolicy.STRICT_EXACT
        );
        assertTrue(
                verdicts.get("TERRAIN").patchEligible(),
                "TERRAIN must be patchEligible when worldgen symbols are IR_LIFTED and infra sinks are pruned: "
                        + verdicts.get("TERRAIN")
        );

        StageCoverageVerdict featuresVerdict = verdicts.get("FEATURES");
        assertFalse(
                featuresVerdict.patchEligible(),
                "MoonriseAsyncFeatureTask#decorateAsync must NEVER be wildcard-skipped"
        );
        assertTrue(
                featuresVerdict.unaccountedMethods().contains(
                        "ca.spottedleaf.moonrise.patches.chunk_system.MoonriseAsyncFeatureTask#decorateAsync"
                ),
                "Expected MoonriseAsyncFeatureTask#decorateAsync in unaccountedMethods: " + featuresVerdict
        );
        assertFalse(
                featuresVerdict.unaccountedAsyncHops().isEmpty(),
                "Expected async supplyAsync lambda hop to remain unaccounted: " + featuresVerdict
        );
    }

    private static Map<String, byte[]> compileInMemorySources(Map<String, String> sourcesByFqcn) {
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "System JavaCompiler required for in-memory bytecode fixtures");
        Map<String, java.io.ByteArrayOutputStream> outputs = new LinkedHashMap<>();
        javax.tools.StandardJavaFileManager stdFm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        javax.tools.JavaFileManager memFm = new javax.tools.ForwardingJavaFileManager<>(stdFm) {
            @Override
            public javax.tools.JavaFileObject getJavaFileForOutput(
                    Location location,
                    String className,
                    javax.tools.JavaFileObject.Kind kind,
                    javax.tools.FileObject sibling
            ) {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                outputs.put(className.replace('/', '.'), baos);
                return new javax.tools.SimpleJavaFileObject(
                        java.net.URI.create("mem:///" + className.replace('.', '/') + kind.extension),
                        kind
                ) {
                    @Override
                    public java.io.OutputStream openOutputStream() {
                        return baos;
                    }
                };
            }
        };
        List<javax.tools.JavaFileObject> units = new ArrayList<>();
        for (Map.Entry<String, String> e : sourcesByFqcn.entrySet()) {
            String path = e.getKey().replace('.', '/') + ".java";
            String code = e.getValue();
            units.add(new javax.tools.SimpleJavaFileObject(
                    java.net.URI.create("string:///" + path),
                    javax.tools.JavaFileObject.Kind.SOURCE
            ) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return code;
                }
            });
        }
        java.io.StringWriter diagOut = new java.io.StringWriter();
        Boolean ok = compiler.getTask(diagOut, memFm, null, List.of("--release", "17"), null, units).call();
        assertTrue(Boolean.TRUE.equals(ok), () -> "In-memory fixture compilation failed:\n" + diagOut);
        Map<String, byte[]> outBytes = new LinkedHashMap<>();
        for (Map.Entry<String, java.io.ByteArrayOutputStream> e : outputs.entrySet()) {
            outBytes.put(e.getKey(), e.getValue().toByteArray());
        }
        return outBytes;
    }

    private static byte[] patchMethodCodeByteFromStart(
            byte[] classBytes,
            String targetMethodName,
            int offsetFromStart,
            byte newByte
    ) throws IOException {
        int[] span = locateMethodCodeSpan(classBytes, targetMethodName);
        byte[] copy = classBytes.clone();
        copy[span[0] + offsetFromStart] = newByte;
        return copy;
    }

    private static byte[] patchMethodCodeByteFromEnd(
            byte[] classBytes,
            String targetMethodName,
            int offsetFromEnd,
            byte newByte
    ) throws IOException {
        int[] span = locateMethodCodeSpan(classBytes, targetMethodName);
        byte[] copy = classBytes.clone();
        copy[span[0] + span[1] - 1 - offsetFromEnd] = newByte;
        return copy;
    }

    private static int[] locateMethodCodeSpan(byte[] classBytes, String targetMethodName) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes));
        int magic = in.readInt();
        assertEquals(0xCAFEBABE, magic);
        in.readUnsignedShort(); // minor
        in.readUnsignedShort(); // major
        int cpCount = in.readUnsignedShort();
        String[] utf8 = new String[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                case 5, 6 -> {
                    in.skipBytes(8);
                    i++;
                }
                case 7, 8, 16, 19, 20 -> in.skipBytes(2);
                case 15 -> in.skipBytes(3);
                default -> throw new IllegalStateException("Unexpected CP tag " + tag);
            }
        }
        in.skipBytes(6); // access, this, super
        int ifaces = in.readUnsignedShort();
        in.skipBytes(ifaces * 2);
        int fields = in.readUnsignedShort();
        for (int f = 0; f < fields; f++) {
            in.skipBytes(6);
            int fAttrs = in.readUnsignedShort();
            for (int a = 0; a < fAttrs; a++) {
                in.skipBytes(2);
                int len = in.readInt();
                in.skipBytes(len);
            }
        }
        int methods = in.readUnsignedShort();
        for (int m = 0; m < methods; m++) {
            in.skipBytes(2); // access
            int nameIdx = in.readUnsignedShort();
            in.skipBytes(2); // desc
            int mAttrs = in.readUnsignedShort();
            String mName = utf8[nameIdx];
            for (int a = 0; a < mAttrs; a++) {
                int attrNameIdx = in.readUnsignedShort();
                int attrLen = in.readInt();
                if (targetMethodName.equals(mName) && "Code".equals(utf8[attrNameIdx])) {
                    in.skipBytes(4); // max_stack, max_locals
                    int codeLen = in.readInt();
                    int consumed = classBytes.length - in.available();
                    return new int[]{consumed, codeLen};
                }
                in.skipBytes(attrLen);
            }
        }
        throw new IllegalStateException("Method Code attribute not found: " + targetMethodName);
    }

    @Test
    @DisplayName("StageSliceSpec sinkMethodSymbols stop subtype overrides, constructor functors, and internal sink field writes cleanly while preserving upstream mixin detection")
    void sinkMethodSymbolsIsolateDownstreamScaffoldingWhilePreservingUpstreamHooks() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
                "net.minecraft.world.level.chunk.ChunkAccess",
                """
                package net.minecraft.world.level.chunk;
                public class ChunkAccess {
                    public final int[] sections = new int[16];
                    public Object blendingData;
                }
                """
        );
        sources.put(
                "net.minecraft.server.level.WorldGenRegion",
                """
                package net.minecraft.server.level;
                import net.minecraft.world.level.chunk.ChunkAccess;
                public class WorldGenRegion implements Runnable {
                    private final int[] scratch = new int[4];
                    public WorldGenRegion(ChunkAccess c) {
                        this.scratch[0] = 1;
                    }
                    @Override
                    public void run() {
                        throw new UnsupportedOperationException();
                    }
                }
                """
        );
        sources.put(
                "net.minecraft.world.level.chunk.ChunkGenerator",
                """
                package net.minecraft.world.level.chunk;
                public abstract class ChunkGenerator {
                    public void addVanillaDecorations(ChunkAccess c) {
                        c.blendingData = "inside-sink-body";
                    }
                }
                """
        );
        sources.put(
                "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator",
                """
                package net.minecraft.world.level.levelgen;
                import net.minecraft.server.level.WorldGenRegion;
                import net.minecraft.world.level.chunk.ChunkAccess;
                import net.minecraft.world.level.chunk.ChunkGenerator;
                public class NoiseBasedChunkGenerator extends ChunkGenerator {
                    public enum Pass { SURFACE, UNDERGROUND }
                    @Override
                    public void addVanillaDecorations(ChunkAccess c) {
                        c.blendingData = "override-inside-sink";
                    }
                    public void applyStage(ChunkAccess c) {
                        Pass[] cloned = Pass.values().clone();
                        if (cloned.length > 0) {
                            new WorldGenRegion(c);
                            c.sections[0] = 42;
                            addVanillaDecorations(c);
                        }
                    }
                }
                """
        );
        Map<String, byte[]> compiled = compileInMemorySources(sources);
        Set<String> carriers = Set.of("net.minecraft.world.level.chunk.ChunkAccess");
        Set<String> sinks = Set.of(
                "net.minecraft.server.level.WorldGenRegion#<init>",
                "net.minecraft.world.level.chunk.ChunkGenerator#addVanillaDecorations",
                "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator#addVanillaDecorations"
        );
        Map<String, StageCoverageGraph> graphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                compiled::get,
                compiled.keySet(),
                List.of(new StageSliceSpec(
                        "FEATURES",
                        List.of("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator#applyStage"),
                        carriers,
                        sinks,
                        Set.of()
                )),
                10,
                128
        );
        StageCoverageGraph g = graphs.get("FEATURES");
        assertNotNull(g);
        assertFalse(g.truncatedByBounds());
        assertFalse(
                g.reachableMethods().contains("net.minecraft.server.level.WorldGenRegion#run"),
                "Constructor sink must not expand functor run() methods on WorldGenRegion: " + g.reachableMethods()
        );
        assertFalse(
                g.mutatedCarrierFields().contains("net.minecraft.world.level.chunk.ChunkAccess#blendingData"),
                "Declared sink body mutations must not leak into upstream stage carrier contract: "
                        + g.mutatedCarrierFields()
        );
        assertTrue(
                g.mutatedCarrierFields().contains("net.minecraft.world.level.chunk.ChunkAccess#sections"),
                "Upstream stage carrier mutation must still be extracted: " + g.mutatedCarrierFields()
        );
        assertTrue(
                PipelineCoverageGraphScanner.isVerifiedBenignInfrastructureSymbol(
                        "[Lnet.minecraft.world.level.levelgen.NoiseBasedChunkGenerator$Pass;#clone"),
                "Enum/array #clone must be recognized as benign Java infrastructure"
        );
        assertTrue(
                PipelineCoverageGraphScanner.isNonWorldgenInfrastructureClass("com.google.common.hash.Hashing"),
                "Guava hashing utility class must be classified as non-worldgen infrastructure"
        );
    }

    @Test
    @DisplayName("inspectClassBytecodeStructure distinguishes pure constant records from arithmetic, unconsumed-field, and side-effecting Trojan operators")
    void inspectClassBytecodeStructureDistinguishesPureAndTrojanOperators() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
                "test.checkb.SideEffect",
                """
                package test.checkb;
                public final class SideEffect {
                    public static void ping() {}
                }
                """
        );
        sources.put(
                "test.checkb.PureConstant",
                """
                package test.checkb;
                public record PureConstant(double value) {
                    public double compute() { return value; }
                }
                """
        );
        sources.put(
                "test.checkb.ScaledConstant",
                """
                package test.checkb;
                public record ScaledConstant(double value) {
                    public double compute() { return value * 2.0; }
                }
                """
        );
        sources.put(
                "test.checkb.TrojanRule",
                """
                package test.checkb;
                public record TrojanRule(String block, double hiddenBias) {
                    public String resultState() {
                        SideEffect.ping();
                        return block;
                    }
                }
                """
        );
        Map<String, byte[]> compiled = compileInMemorySources(sources);

        PipelineCoverageGraphScanner.PureClassBytecodeReport pure =
                PipelineCoverageGraphScanner.inspectClassBytecodeStructure(compiled.get("test.checkb.PureConstant"));
        assertNotNull(pure);
        assertEquals(Set.of("value"), pure.nonStaticFieldNames());
        assertFalse(pure.hasArithmeticOrBranchInMethod("compute"));
        assertTrue(pure.externalCalledMethods().isEmpty());

        PipelineCoverageGraphScanner.PureClassBytecodeReport scaled =
                PipelineCoverageGraphScanner.inspectClassBytecodeStructure(compiled.get("test.checkb.ScaledConstant"));
        assertNotNull(scaled);
        assertTrue(scaled.hasArithmeticOrBranchInMethod("compute"));

        PipelineCoverageGraphScanner.PureClassBytecodeReport trojan =
                PipelineCoverageGraphScanner.inspectClassBytecodeStructure(compiled.get("test.checkb.TrojanRule"));
        assertNotNull(trojan);
        assertEquals(Set.of("block", "hiddenBias"), trojan.nonStaticFieldNames());
        assertTrue(trojan.externalCalledMethods().contains("test.checkb.SideEffect#ping"));

        org.junit.jupiter.api.Assertions.assertNull(
                PipelineCoverageGraphScanner.inspectClassBytecodeStructure(new byte[]{1, 2, 3}));
    }

    @Test
    @DisplayName("verifyStageCarrierBoundary accepts signature-closed stage scaffolding and rejects third-party mixins, custom generator overrides, and novel hooks")
    void verifyStageCarrierBoundaryAcceptsCleanScaffoldingAndRejectsHooksMixinsAndCustomGenerators() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
                "test.checka.Carrier",
                """
                package test.checka;
                public class Carrier {
                    public final int[] sections = new int[16];
                    public Object customExtraState;
                }
                """
        );
        sources.put(
                "test.checka.MathUtil",
                """
                package test.checka;
                public final class MathUtil {
                    public static int floorDiv(int a, int b) {
                        return Math.floorDiv(a, b);
                    }
                }
                """
        );
        sources.put(
                "test.checka.BaseGen",
                """
                package test.checka;
                public abstract class BaseGen {
                    public abstract void buildSurface(Carrier c);
                }
                """
        );
        sources.put(
                "test.checka.StandardNoiseGen",
                """
                package test.checka;
                public final class StandardNoiseGen extends BaseGen {
                    @Override
                    public void buildSurface(Carrier c) {
                        c.sections[MathUtil.floorDiv(4, 2)] = 9;
                    }
                }
                """
        );
        sources.put(
                "test.checka.CustomPluginGen",
                """
                package test.checka;
                public final class CustomPluginGen extends BaseGen {
                    @Override
                    public void buildSurface(Carrier c) {
                        c.customExtraState = "plugin-override";
                    }
                }
                """
        );
        sources.put(
                "test.checka.NovelExternalHook",
                """
                package test.checka;
                public final class NovelExternalHook {
                    public static void onSurface(Carrier c) {
                        c.sections[0] += 1;
                    }
                }
                """
        );
        sources.put(
                "test.checka.Context",
                """
                package test.checka;
                public record Context(BaseGen generator) {}
                """
        );
        sources.put(
                "test.checka.StageTasks",
                """
                package test.checka;
                public final class StageTasks {
                    public static void cleanSurface(Context ctx, Carrier c) {
                        ctx.generator().buildSurface(c);
                    }
                    public static void hookedSurface(Context ctx, Carrier c) {
                        ctx.generator().buildSurface(c);
                        NovelExternalHook.onSurface(c);
                    }
                    public static void mixinInjectedSurface(Context ctx, Carrier c) {
                        ctx.generator().buildSurface(c);
                        handler$zbg000$onBuildSurface(c);
                    }
                    private static void handler$zbg000$onBuildSurface(Carrier c) {
                        c.sections[1] = 3;
                    }
                }
                """
        );

        Map<String, byte[]> compiled = compileInMemorySources(sources);
        Set<String> carriers = Set.of("test.checka.Carrier");

        // 1. Clean StandardNoiseGen stage boundary passes Check A
        Map<String, StageCoverageGraph> cleanGraphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                compiled::get,
                Set.of("test.checka.StandardNoiseGen"),
                List.of(new StageSliceSpec(
                        "SURFACE",
                        List.of("test.checka.StageTasks#cleanSurface"),
                        carriers
                )),
                10,
                128
        );
        PipelineCoverageGraphScanner.StageBoundaryScanResult cleanRes =
                PipelineCoverageGraphScanner.verifyStageCarrierBoundary(
                        compiled::get,
                        cleanGraphs.get("SURFACE"),
                        "test.checka.BaseGen",
                        Set.of("test.checka.StandardNoiseGen")
                );
        assertTrue(cleanRes.boundaryContractClean(), () -> "Expected clean boundary, hooks="
                + cleanRes.unexpectedHookOrMixinMethods() + ", customGen=" + cleanRes.customGeneratorOverrideMethods());
        assertTrue(cleanRes.verifiedReplacedScaffoldMethods().contains("test.checka.StandardNoiseGen#buildSurface"));
        assertTrue(cleanRes.verifiedReplacedScaffoldMethods().contains("test.checka.MathUtil#floorDiv"));

        // 2. CustomPluginGen subclass override fails closed
        Map<String, StageCoverageGraph> customGenGraphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                compiled::get,
                Set.of("test.checka.StandardNoiseGen", "test.checka.CustomPluginGen"),
                List.of(new StageSliceSpec(
                        "SURFACE",
                        List.of("test.checka.StageTasks#cleanSurface"),
                        carriers
                )),
                10,
                128
        );
        PipelineCoverageGraphScanner.StageBoundaryScanResult customGenRes =
                PipelineCoverageGraphScanner.verifyStageCarrierBoundary(
                        compiled::get,
                        customGenGraphs.get("SURFACE"),
                        "test.checka.BaseGen",
                        Set.of("test.checka.StandardNoiseGen")
                );
        assertFalse(customGenRes.boundaryContractClean());
        assertTrue(customGenRes.customGeneratorOverrideMethods().contains("test.checka.CustomPluginGen#buildSurface"));

        // 3. NovelExternalHook fails closed
        Map<String, StageCoverageGraph> hookedGraphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                compiled::get,
                Set.of("test.checka.StandardNoiseGen"),
                List.of(new StageSliceSpec(
                        "SURFACE",
                        List.of("test.checka.StageTasks#hookedSurface"),
                        carriers
                )),
                10,
                128
        );
        PipelineCoverageGraphScanner.StageBoundaryScanResult hookedRes =
                PipelineCoverageGraphScanner.verifyStageCarrierBoundary(
                        compiled::get,
                        hookedGraphs.get("SURFACE"),
                        "test.checka.BaseGen",
                        Set.of("test.checka.StandardNoiseGen")
                );
        assertFalse(hookedRes.boundaryContractClean());
        assertTrue(hookedRes.unexpectedHookOrMixinMethods().contains("test.checka.NovelExternalHook#onSurface"));

        // 4. Mixin handler$ injection fails closed
        Map<String, StageCoverageGraph> mixinGraphs = PipelineCoverageGraphScanner.traceConnectedPipeline(
                compiled::get,
                Set.of("test.checka.StandardNoiseGen"),
                List.of(new StageSliceSpec(
                        "SURFACE",
                        List.of("test.checka.StageTasks#mixinInjectedSurface"),
                        carriers
                )),
                10,
                128
        );
        PipelineCoverageGraphScanner.StageBoundaryScanResult mixinRes =
                PipelineCoverageGraphScanner.verifyStageCarrierBoundary(
                        compiled::get,
                        mixinGraphs.get("SURFACE"),
                        "test.checka.BaseGen",
                        Set.of("test.checka.StandardNoiseGen")
                );
        assertFalse(mixinRes.boundaryContractClean());
        assertTrue(mixinRes.unexpectedHookOrMixinMethods().contains(
                "test.checka.StageTasks#handler$zbg000$onBuildSurface"));

        // 5. collectVerifiedReplacedScaffoldBindings binds clean scaffold & internal mutations,
        // never masks concrete carrier fields, and returns empty on dirty boundary or UNACCOUNTED.
        Map<String, ProvenanceBinding> exactBindings =
                PipelineCoverageGraphScanner.collectVerifiedReplacedScaffoldBindings(
                        cleanRes,
                        CoverageProvenanceKind.IR_LIFTED,
                        "TestExactSurface"
                );
        assertFalse(exactBindings.isEmpty());
        assertTrue(exactBindings.containsKey("SURFACE:test.checka.StandardNoiseGen#buildSurface"));
        assertEquals(
                CoverageProvenanceKind.IR_LIFTED,
                exactBindings.get("SURFACE:test.checka.StandardNoiseGen#buildSurface").kind()
        );
        assertFalse(
                exactBindings.containsKey("SURFACE:test.checka.Carrier#sections"),
                "Concrete carrier field mutation must never be auto-bound by scaffold helper"
        );
        assertFalse(
                PipelineCoverageGraphScanner.isVerifiedReplacedCarrierMutation("test.checka.Carrier#sections", cleanRes),
                "Concrete carrier field must not be treated as internal scaffold mutation"
        );
        assertTrue(
                PipelineCoverageGraphScanner.isVerifiedReplacedCarrierMutation(
                        "UNKNOWN_ARRAY:test.checka.StandardNoiseGen#buildSurface->sections",
                        cleanRes
                )
        );
        assertFalse(
                PipelineCoverageGraphScanner.isVerifiedReplacedCarrierMutation(
                        "UNKNOWN_ARRAY:test.checka.NovelExternalHook#onSurface->sections",
                        cleanRes
                )
        );
        assertTrue(
                PipelineCoverageGraphScanner.collectVerifiedReplacedScaffoldBindings(
                        hookedRes,
                        CoverageProvenanceKind.IR_LIFTED,
                        "DirtyMustBeEmpty"
                ).isEmpty(),
                "Dirty Check A boundary must emit zero scaffold bindings"
        );
        assertTrue(
                PipelineCoverageGraphScanner.collectVerifiedReplacedScaffoldBindings(
                        cleanRes,
                        CoverageProvenanceKind.UNACCOUNTED,
                        "UnaccountedMustBeEmpty"
                ).isEmpty(),
                "UNACCOUNTED domain provenance must emit zero scaffold bindings"
        );
        assertTrue(
                PipelineCoverageGraphScanner.collectVerifiedReplacedScaffoldBindings(
                        cleanRes,
                        CoverageProvenanceKind.NATIVE_DELEGATED,
                        "NativeMustBeEmpty"
                ).isEmpty(),
                "NATIVE_DELEGATED domain provenance must emit zero scaffold bindings"
        );
    }
}
