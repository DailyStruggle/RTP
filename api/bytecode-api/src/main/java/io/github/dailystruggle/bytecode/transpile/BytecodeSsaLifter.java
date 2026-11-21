package io.github.dailystruggle.bytecode.transpile;

import io.github.dailystruggle.bytecode.PipelineCoverageGraphScanner.CoverageProvenanceKind;
import io.github.dailystruggle.bytecode.PipelineCoverageGraphScanner.ProvenanceBinding;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.BinaryOperator;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptExpr;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptFunction;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptLut;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptModule;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptParam;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptStmt;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptType;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.MathIntrinsicKind;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.UnaryOperator;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Zero-classloading JVM {@code .class} stack-frame simulator and post-dominator structured
 * control-flow lifter producing {@link BytecodeConceptIr} modules.
 *
 * <p>Simulates the JVM operand stack and local variable array across basic-block graphs,
 * resolves stack merge points into explicit SSA merge variables, evaluates static initializer
 * ({@code <clinit>}) constant lookup tables (such as Perlin permutation arrays), and recovers
 * structured {@code if/else} and {@code while} statements without {@code goto} instructions.
 *
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy corpus-atlas-ADR-002
 * @trace REQ-CFG-ATLAS-001
 */
public final class BytecodeSsaLifter {

    private static final int CLASS_MAGIC = 0xCAFEBABE;

    private static final int CONSTANT_Utf8 = 1;
    private static final int CONSTANT_Integer = 3;
    private static final int CONSTANT_Float = 4;
    private static final int CONSTANT_Long = 5;
    private static final int CONSTANT_Double = 6;
    private static final int CONSTANT_Class = 7;
    private static final int CONSTANT_String = 8;
    private static final int CONSTANT_Fieldref = 9;
    private static final int CONSTANT_Methodref = 10;
    private static final int CONSTANT_InterfaceMethodref = 11;
    private static final int CONSTANT_NameAndType = 12;
    private static final int CONSTANT_MethodHandle = 15;
    private static final int CONSTANT_MethodType = 16;
    private static final int CONSTANT_Dynamic = 17;
    private static final int CONSTANT_InvokeDynamic = 18;
    private static final int CONSTANT_Module = 19;
    private static final int CONSTANT_Package = 20;

    static final int ACC_STATIC = 0x0008;
    static final int ACC_FINAL = 0x0010;
    static final int ACC_NATIVE = 0x0100;
    static final int ACC_ABSTRACT = 0x0400;
    static final int STATIC_FIELDS_INSTANCE_ID = -100;
    static final int EXIT_PC = Integer.MAX_VALUE;
    private static final Object NULL_SENTINEL = new Object();

    private BytecodeSsaLifter() {
    }

    /**
     * Named scalar IR input parameter binding for a read-only context object getter call.
     */
    public record ContextParamBinding(String paramName, ConceptType paramType) {
        public ContextParamBinding {
            Objects.requireNonNull(paramName, "paramName");
            Objects.requireNonNull(paramType, "paramType");
        }
    }

    /**
     * Optional caller-supplied hook for closed-world graph lifting:
     * <ul>
     *   <li>{@link #internObject(Object)} maps opaque immutable terminal objects to stable {@code int} IDs.</li>
     *   <li>{@link #resolveContextGetter(int, String, String, String)} binds getter calls on a
     *       read-only context parameter object to named scalar IR input parameters.</li>
     * </ul>
     */
    public record ContextBoundMethodDelegate(
            Object targetInstance,
            String targetMethodName,
            String xParamName,
            String yParamNameOrNull,
            String zParamName) {
        public ContextBoundMethodDelegate {
            Objects.requireNonNull(targetInstance, "targetInstance");
            Objects.requireNonNull(targetMethodName, "targetMethodName");
            Objects.requireNonNull(xParamName, "xParamName");
            Objects.requireNonNull(zParamName, "zParamName");
        }
    }

    public interface ObjectInterner {
        ObjectInterner NONE = new ObjectInterner() {};

        /**
         * Sentinel returned from {@link #resolveContextCallResult} to represent an explicitly
         * {@code null} context field/getter value (distinct from returning Java {@code null},
         * which signals an unmapped context member).
         */
        Object NULL_CONTEXT_VALUE = new Object();

        /**
         * Returns a stable {@code int} ID for an opaque immutable terminal instance, or {@code null}
         * if the instance should be structurally walked as part of the object graph.
         */
        default Integer internObject(Object instance) {
            return null;
        }

        /**
         * {@code int} ID representing a {@code null} reference when lowering methods that return
         * interned terminal objects.
         */
        default int nullObjectId() {
            return 0;
        }

        /**
         * Resolves a zero-arg getter call on a read-only context parameter at {@code entryParamIndex}
         * (0-based among method parameters excluding {@code this}) into a named IR input parameter,
         * or {@code null} if unmapped (causing fail-closed rejection).
         */
        default ContextParamBinding resolveContextGetter(
                int entryParamIndex,
                String ownerInternalName,
                String methodName,
                String descriptor) {
            return null;
        }

        /**
         * Optional fixed ordering of scalar input parameters to expose when flattening the context
         * parameter at {@code entryParamIndex}. If empty, scalar parameters are ordered by first use.
         */
        default List<ContextParamBinding> contextParameters(int entryParamIndex) {
            return List.of();
        }

        /**
         * Optional live immutable singleton instance to bind directly to an object-typed entry
         * parameter at {@code entryParamIndex} (0-based excluding {@code this}), specializing calls
         * against that concrete instance instead of treating the parameter as an opaque context.
         */
        default Object fixedEntryParameterInstance(int entryParamIndex) {
            return null;
        }

        /**
         * Resolves a direct field read ({@code GETFIELD}) on a read-only context object at
         * {@code entryParamIndex} into a named scalar (or interned object) IR input parameter,
         * or {@code null} to fall back to structural field lookup / fail closed.
         */
        default ContextParamBinding resolveContextField(
                int entryParamIndex,
                String ownerInternalName,
                String fieldName,
                String descriptor) {
            return null;
        }

        /**
         * Returns {@code true} if a field on the context object represents a monotonic cache
         * invalidation stamp (e.g. per-column/per-voxel update counter) whose read should yield a
         * cold-miss tag so pure memoization guards always evaluate their compute path statelessly.
         */
        default boolean isColdMemoContextField(
                int entryParamIndex,
                String ownerInternalName,
                String fieldName,
                String descriptor) {
            return false;
        }

        /**
         * Resolves a compile-time/factory method call on a read-only context object whose arguments
         * are known constants or snapshotted instances into a live immutable object (or boxed
         * primitive) to capture structurally into the closed-world graph, or {@code null} if unmapped.
         */
        default Object resolveContextCallResult(
                int entryParamIndex,
                String ownerInternalName,
                String methodName,
                String descriptor,
                Object[] constantArgs) {
            return null;
        }

        /**
         * Returns {@code true} when a live object captured inside the object graph represents the
         * bound read-only evaluation context (mapped to context slot {@code -1}).
         */
        default boolean isBoundContextInstance(Object instance) {
            return false;
        }

        /**
         * Evaluates a live finite-domain membership predicate or set (e.g. {@code Predicate} or
         * {@code HolderSet}) against the caller's interned domain, returning all matching interned
         * {@code int} IDs, or {@code null} if the predicate cannot be decided over a finite domain.
         */
        default int[] matchingInternedIds(Object predicateOrCollection) {
            return null;
        }

        /**
         * Resolves an instance method call on an interned terminal scalar (e.g. per-biome scalar
         * query) into a pure {@link ConceptExpr}, or {@code null} if unmapped.
         */
        default ConceptExpr resolveInternedInstanceCall(
                ConceptExpr internedIdExpr,
                String ownerInternalName,
                String methodName,
                String descriptor,
                List<ConceptExpr> scalarArgs) {
            return null;
        }
    }

    /**
     * Exact fail-closed diagnostic emitted whenever a reachable bytecode site cannot be lifted
     * purely into the concept IR.
     */
    public record UnmappedReason(
            String concreteClass,
            String method,
            int bytecodeOffset,
            int opcode,
            String detail
    ) {
        public UnmappedReason(String concreteClass, String method, int bytecodeOffset, int opcode) {
            this(concreteClass, method, bytecodeOffset, opcode, "");
        }

        public UnmappedReason {
            concreteClass = concreteClass == null ? "" : concreteClass;
            method = method == null ? "" : method;
            detail = detail == null ? "" : detail;
        }
    }

    /**
     * Bounds and hooks controlling closed-world object-graph snapshotting and bytecode lifting.
     */
    public record GraphLiftOptions(
            int maxGraphDepth,
            int maxInstances,
            int maxLoopUnrollLength,
            int maxHelperSpecializations,
            ObjectInterner objectInterner
    ) {
        public GraphLiftOptions {
            if (maxGraphDepth < 1) {
                throw new IllegalArgumentException("maxGraphDepth must be >= 1: " + maxGraphDepth);
            }
            if (maxInstances < 1) {
                throw new IllegalArgumentException("maxInstances must be >= 1: " + maxInstances);
            }
            if (maxLoopUnrollLength < 0) {
                throw new IllegalArgumentException("maxLoopUnrollLength must be >= 0: " + maxLoopUnrollLength);
            }
            if (maxHelperSpecializations < 1) {
                throw new IllegalArgumentException(
                        "maxHelperSpecializations must be >= 1: " + maxHelperSpecializations);
            }
            objectInterner = objectInterner == null ? ObjectInterner.NONE : objectInterner;
        }

        public static GraphLiftOptions defaults() {
            return new GraphLiftOptions(48, 4096, 64, 4096, ObjectInterner.NONE);
        }

        public static GraphLiftOptions withInterner(ObjectInterner interner) {
            return new GraphLiftOptions(48, 4096, 64, 4096, interner);
        }
    }

    /**
     * Per-instance structural snapshot node captured during closed-world live object-graph traversal.
     */
    public record InstanceNode(
            int instanceId,
            Class<?> concreteClass,
            String internalClassName,
            byte[] classBytes,
            Map<String, ConceptExpr> primitiveFields,
            Map<String, ConceptLut> arrayLuts,
            Map<String, Integer> arrayRowWidths,
            Map<String, Integer> arrayLengths,
            Map<String, Integer> objectFieldRefs,
            Integer enumOrdinal,
            Integer internedId,
            boolean optionalPresent,
            Integer optionalTargetId,
            ConceptExpr optionalPrimitiveValue,
            boolean collectionOrArray,
            List<Integer> elementInstanceIds,
            ConceptLut directPrimitiveLut,
            int directArrayRowWidth,
            int directArrayLength,
            ConceptExpr boxedPrimitiveValue
    ) {
        public InstanceNode {
            Objects.requireNonNull(concreteClass, "concreteClass");
            Objects.requireNonNull(internalClassName, "internalClassName");
            primitiveFields = primitiveFields == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(primitiveFields));
            arrayLuts = arrayLuts == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(arrayLuts));
            arrayRowWidths = arrayRowWidths == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(arrayRowWidths));
            arrayLengths = arrayLengths == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(arrayLengths));
            objectFieldRefs = objectFieldRefs == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(objectFieldRefs));
            elementInstanceIds = elementInstanceIds == null ? List.of() : List.copyOf(elementInstanceIds);
        }

        public ConceptExpr lookupPrimitiveField(String ownerInternal, String fieldName) {
            if (ownerInternal != null) {
                ConceptExpr exact = primitiveFields.get(ownerInternal + "#" + fieldName);
                if (exact != null) {
                    return exact;
                }
            }
            return primitiveFields.get(fieldName);
        }

        public ConceptLut lookupArrayLut(String ownerInternal, String fieldName) {
            if (ownerInternal != null) {
                ConceptLut exact = arrayLuts.get(ownerInternal + "#" + fieldName);
                if (exact != null) {
                    return exact;
                }
            }
            return arrayLuts.get(fieldName);
        }

        public Integer lookupArrayRowWidth(String ownerInternal, String fieldName) {
            if (ownerInternal != null) {
                Integer exact = arrayRowWidths.get(ownerInternal + "#" + fieldName);
                if (exact != null) {
                    return exact;
                }
            }
            return arrayRowWidths.get(fieldName);
        }

        public Integer lookupArrayLength(String ownerInternal, String fieldName) {
            if (ownerInternal != null) {
                Integer exact = arrayLengths.get(ownerInternal + "#" + fieldName);
                if (exact != null) {
                    return exact;
                }
            }
            return arrayLengths.get(fieldName);
        }

        public Integer lookupObjectFieldRef(String ownerInternal, String fieldName) {
            if (ownerInternal != null) {
                Integer exact = objectFieldRefs.get(ownerInternal + "#" + fieldName);
                if (exact != null) {
                    return exact;
                }
            }
            return objectFieldRefs.get(fieldName);
        }
    }

    /**
     * Captured primitive field constants, array lookup tables, transitive class bytes, defining
     * class loaders, and per-instance object-graph nodes extracted from live runtime objects.
     */
    public record InstanceSnapshot(
            Map<String, ConceptExpr> fieldConstants,
            Map<String, ConceptLut> instanceLuts,
            List<byte[]> discoveredClassBytes,
            List<ClassLoader> classLoaders,
            Set<String> liftedFieldSymbols,
            List<Integer> rootInstanceIds,
            Map<Integer, InstanceNode> instancesById,
            Map<Integer, Object> liveObjectsById
    ) {
        public InstanceSnapshot {
            fieldConstants = fieldConstants == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fieldConstants));
            instanceLuts = instanceLuts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(instanceLuts));
            discoveredClassBytes = discoveredClassBytes == null ? List.of() : List.copyOf(discoveredClassBytes);
            classLoaders = classLoaders == null ? List.of() : List.copyOf(classLoaders);
            liftedFieldSymbols = liftedFieldSymbols == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(liftedFieldSymbols));
            rootInstanceIds = rootInstanceIds == null ? List.of() : List.copyOf(rootInstanceIds);
            instancesById = instancesById == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(instancesById));
            liveObjectsById = liveObjectsById == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(liveObjectsById));
        }

        public InstanceSnapshot(
                Map<String, ConceptExpr> fieldConstants,
                Map<String, ConceptLut> instanceLuts,
                List<byte[]> discoveredClassBytes,
                List<ClassLoader> classLoaders,
                Set<String> liftedFieldSymbols,
                List<Integer> rootInstanceIds,
                Map<Integer, InstanceNode> instancesById) {
            this(
                    fieldConstants,
                    instanceLuts,
                    discoveredClassBytes,
                    classLoaders,
                    liftedFieldSymbols,
                    rootInstanceIds,
                    instancesById,
                    Map.of());
        }

        public InstanceSnapshot(
                Map<String, ConceptExpr> fieldConstants,
                Map<String, ConceptLut> instanceLuts,
                List<byte[]> discoveredClassBytes,
                List<ClassLoader> classLoaders,
                Set<String> liftedFieldSymbols) {
            this(fieldConstants, instanceLuts, discoveredClassBytes, classLoaders, liftedFieldSymbols, List.of(), Map.of());
        }

        public InstanceSnapshot(
                Map<String, ConceptExpr> fieldConstants,
                Map<String, ConceptLut> instanceLuts,
                List<byte[]> discoveredClassBytes,
                List<ClassLoader> classLoaders) {
            this(
                    fieldConstants,
                    instanceLuts,
                    discoveredClassBytes,
                    classLoaders,
                    deriveQualifiedFieldSymbols(fieldConstants, instanceLuts),
                    List.of(),
                    Map.of());
        }

        public InstanceSnapshot(
                Map<String, ConceptExpr> fieldConstants,
                Map<String, ConceptLut> instanceLuts,
                List<byte[]> discoveredClassBytes) {
            this(fieldConstants, instanceLuts, discoveredClassBytes, List.of());
        }

        public int rootInstanceId() {
            return rootInstanceIds.isEmpty() ? -1 : rootInstanceIds.get(0);
        }

        public InstanceNode instance(int instanceId) {
            return instancesById.get(instanceId);
        }

        public static InstanceSnapshot empty() {
            return new InstanceSnapshot(Map.of(), Map.of(), List.of(), List.of(), Set.of(), List.of(), Map.of());
        }

        private static Set<String> deriveQualifiedFieldSymbols(
                Map<String, ConceptExpr> constants,
                Map<String, ConceptLut> luts) {
            Set<String> out = new LinkedHashSet<>();
            if (constants != null) {
                for (String k : constants.keySet()) {
                    if (k != null && k.indexOf('#') > 0) {
                        out.add(k.replace('/', '.'));
                    }
                }
            }
            if (luts != null) {
                for (String k : luts.keySet()) {
                    if (k != null && k.indexOf('#') > 0) {
                        out.add(k.replace('/', '.'));
                    }
                }
            }
            return out;
        }
    }

    /**
     * Lifted grid, jitter, spread-bucket, lava-threshold, and {@code similarity(d1, d2)} bytecode
     * descriptor extracted directly from a server's {@code Aquifer$NoiseBasedAquifer.class}.
     */
    public record LiftedAquiferBytecodeSpec(
            int xSpacing,
            int ySpacing,
            int zSpacing,
            int xRange,
            int yRange,
            int zRange,
            double similarityScale,
            int spreadBucketX,
            int spreadBucketY,
            int spreadBucketZ,
            int lavaMaxFluidLevel,
            double lavaNoiseThreshold,
            ConceptModule similarityModule,
            String bytecodeDigest,
            Map<String, ProvenanceBinding> provenanceBindings
    ) {
        public LiftedAquiferBytecodeSpec {
            bytecodeDigest = (bytecodeDigest == null || bytecodeDigest.isBlank())
                    ? "defaults-unlifted"
                    : bytecodeDigest;
            provenanceBindings = provenanceBindings == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(provenanceBindings));
        }

        public LiftedAquiferBytecodeSpec(
                int xSpacing,
                int ySpacing,
                int zSpacing,
                int xRange,
                int yRange,
                int zRange,
                double similarityScale,
                int spreadBucketX,
                int spreadBucketY,
                int spreadBucketZ,
                int lavaMaxFluidLevel,
                double lavaNoiseThreshold,
                ConceptModule similarityModule) {
            this(
                    xSpacing,
                    ySpacing,
                    zSpacing,
                    xRange,
                    yRange,
                    zRange,
                    similarityScale,
                    spreadBucketX,
                    spreadBucketY,
                    spreadBucketZ,
                    lavaMaxFluidLevel,
                    lavaNoiseThreshold,
                    similarityModule,
                    "defaults-unlifted",
                    Map.of());
        }

        public static final LiftedAquiferBytecodeSpec MOJANG_DEFAULTS = new LiftedAquiferBytecodeSpec(
                16, 12, 16, 10, 9, 10, 25.0, 16, 40, 16, -10, 0.3, null, "defaults-unlifted", Map.of());
    }

    /**
     * Lifts {@code Aquifer$NoiseBasedAquifer} static grid constants, {@code similarity(int, int)}
     * SSA IR, {@code 40}-block randomized fluid spread buckets, and {@code Y <= -10} lava noise
     * thresholds directly from raw server {@code .class} bytes without loading Minecraft types.
     */
    public static LiftedAquiferBytecodeSpec liftNoiseBasedAquiferBytecode(
            Collection<byte[]> classBytesCollection) {
        if (classBytesCollection == null || classBytesCollection.isEmpty()) {
            return LiftedAquiferBytecodeSpec.MOJANG_DEFAULTS;
        }
        RawClassFile target = null;
        for (byte[] bytes : classBytesCollection) {
            RawClassFile parsed = parseClassBytes(bytes);
            if (parsed != null
                    && (parsed.thisClass.endsWith("NoiseBasedAquifer")
                            || parsed.fieldConstants.containsKey("X_SPACING")
                            || findMethod(parsed, "similarity", "(II)D") != null)) {
                target = parsed;
                break;
            }
        }
        if (target == null) {
            return LiftedAquiferBytecodeSpec.MOJANG_DEFAULTS;
        }
        int xSpacing = extractIntFieldConst(target, "X_SPACING", 16);
        int ySpacing = extractIntFieldConst(target, "Y_SPACING", 12);
        int zSpacing = extractIntFieldConst(target, "Z_SPACING", 16);
        int xRange = extractIntFieldConst(target, "X_RANGE", 10);
        int yRange = extractIntFieldConst(target, "Y_RANGE", 9);
        int zRange = extractIntFieldConst(target, "Z_RANGE", 10);

        double similarityScale = 25.0;
        ConceptModule simModule = null;
        RawMethod simMethod = findMethod(target, "similarity", "(II)D");
        if (simMethod != null && simMethod.code != null) {
            for (int pc = 0; pc < simMethod.code.length; ) {
                int op = simMethod.code[pc] & 0xFF;
                int len = instructionLength(simMethod.code, pc);
                if (op == 0x14 && pc + 2 < simMethod.code.length) { // ldc2_w
                    int cpIdx = ((simMethod.code[pc + 1] & 0xFF) << 8) | (simMethod.code[pc + 2] & 0xFF);
                    if (cpIdx > 0
                            && cpIdx < target.cpConstantValues.length
                            && target.cpConstantValues[cpIdx] instanceof Number num
                            && num.doubleValue() > 1.0) {
                        similarityScale = num.doubleValue();
                    }
                }
                pc += Math.max(1, len);
            }
            try {
                simModule = liftModule(classBytesCollection, target.thisClass, "similarity", "(II)D");
            } catch (RuntimeException ex) {
                java.util.logging.Logger.getLogger(BytecodeSsaLifter.class.getName())
                        .log(
                                java.util.logging.Level.WARNING,
                                "[BytecodeSsaLifter S-004] Failed lifting NoiseBasedAquifer#similarity SSA module; retaining constant-pool scale",
                                ex);
                simModule = null;
            }
        }

        int spreadBucketX = 16;
        int spreadBucketY = 40;
        int spreadBucketZ = 16;
        RawMethod spreadMethod = findMethod(target, "computeRandomizedFluidSurfaceLevel", "(IIII)I");
        if (spreadMethod != null && spreadMethod.code != null) {
            List<Integer> pushedSmallInts = extractBipushConstants(spreadMethod.code);
            if (pushedSmallInts.size() >= 2) {
                spreadBucketX = Math.max(1, pushedSmallInts.get(0));
                spreadBucketY = Math.max(1, pushedSmallInts.get(1));
                spreadBucketZ = spreadBucketX;
            }
        }

        int lavaMaxLevel = -10;
        double lavaThreshold = 0.3;
        RawMethod fluidTypeMethod = findMethodByNameOnly(target, "computeFluidType");
        if (fluidTypeMethod != null && fluidTypeMethod.code != null) {
            for (int v : extractBipushConstants(fluidTypeMethod.code)) {
                if (v < 0 && v > -128) {
                    lavaMaxLevel = v;
                    break;
                }
            }
            for (int pc = 0; pc < fluidTypeMethod.code.length; ) {
                int op = fluidTypeMethod.code[pc] & 0xFF;
                int len = instructionLength(fluidTypeMethod.code, pc);
                if (op == 0x14 && pc + 2 < fluidTypeMethod.code.length) {
                    int cpIdx = ((fluidTypeMethod.code[pc + 1] & 0xFF) << 8) | (fluidTypeMethod.code[pc + 2] & 0xFF);
                    if (cpIdx > 0
                            && cpIdx < target.cpConstantValues.length
                            && target.cpConstantValues[cpIdx] instanceof Number num) {
                        double dv = num.doubleValue();
                        if (dv > 0.0 && dv < 1.0) {
                            lavaThreshold = dv;
                            break;
                        }
                    }
                }
                pc += Math.max(1, len);
            }
        }

        int effXSpacing = Math.max(1, xSpacing);
        int effYSpacing = Math.max(1, ySpacing);
        int effZSpacing = Math.max(1, zSpacing);
        int effXRange = Math.max(1, xRange);
        int effYRange = Math.max(1, yRange);
        int effZRange = Math.max(1, zRange);
        double effSimScale = similarityScale > 0.0 ? similarityScale : 25.0;

        String simDigest = simModule != null ? BytecodeConceptIr.canonicalModuleHash(simModule) : "unlifted";
        String specDigest = sha256Hex16(
                target.thisClass
                        + ":" + effXSpacing + "," + effYSpacing + "," + effZSpacing
                        + ":" + effXRange + "," + effYRange + "," + effZRange
                        + ":" + effSimScale
                        + ":" + spreadBucketX + "," + spreadBucketY + "," + spreadBucketZ
                        + ":" + lavaMaxLevel + ":" + lavaThreshold
                        + ":" + simDigest);

        Map<String, ProvenanceBinding> aqBindings = new LinkedHashMap<>();
        if (simModule != null) {
            aqBindings.putAll(collectModuleProvenanceBindings(simModule, InstanceSnapshot.empty()));
            String ownerBinary = target.thisClass.replace('/', '.');
            String mechRef = "BytecodeSsaLifter#LiftedAquiferBytecodeSpec:" + ownerBinary + "@sha256=" + specDigest;

            for (String constField : List.of(
                    "X_SPACING", "Y_SPACING", "Z_SPACING", "X_RANGE", "Y_RANGE", "Z_RANGE")) {
                if (target.fieldConstants.containsKey(constField)) {
                    String sym = ownerBinary + "#" + constField;
                    aqBindings.put(sym, new ProvenanceBinding(sym, CoverageProvenanceKind.IR_LIFTED, mechRef));
                }
            }

            Set<String> actualFields = new LinkedHashSet<>();
            for (FieldRef fr : target.declaredFields) {
                actualFields.add(fr.name());
            }
            for (String stateField : List.of(
                    "SURFACE_SAMPLING_OFFSETS_IN_CHUNKS",
                    "barrierNoise",
                    "fluidLevelFloodednessNoise",
                    "fluidLevelSpreadNoise",
                    "lavaNoise",
                    "positionalRandomFactory",
                    "aquiferCache",
                    "aquiferLocationCache",
                    "minGridX",
                    "minGridY",
                    "minGridZ",
                    "gridSizeX",
                    "gridSizeZ",
                    "globalFluidPicker",
                    "shouldScheduleFluidUpdate",
                    "noiseChunk")) {
                if (actualFields.contains(stateField)) {
                    String sym = ownerBinary + "#" + stateField;
                    aqBindings.put(sym, new ProvenanceBinding(sym, CoverageProvenanceKind.IR_LIFTED, mechRef));
                }
            }

            if (spreadMethod != null && spreadMethod.code != null) {
                String spreadSym = ownerBinary + "#" + spreadMethod.name;
                aqBindings.put(spreadSym, new ProvenanceBinding(spreadSym, CoverageProvenanceKind.IR_LIFTED, mechRef));
            }
            if (fluidTypeMethod != null && fluidTypeMethod.code != null) {
                String ftSym = ownerBinary + "#" + fluidTypeMethod.name;
                aqBindings.put(ftSym, new ProvenanceBinding(ftSym, CoverageProvenanceKind.IR_LIFTED, mechRef));
            }
            if (spreadMethod != null && spreadMethod.code != null && fluidTypeMethod != null && fluidTypeMethod.code != null) {
                for (String stdMethod : List.of(
                        "computeSubstance",
                        "getIndex",
                        "gridX",
                        "gridY",
                        "gridZ",
                        "shouldScheduleFluidUpdate",
                        "<init>",
                        "<clinit>")) {
                    if (findMethodByNameOnly(target, stdMethod) != null) {
                        String mSym = ownerBinary + "#" + stdMethod;
                        aqBindings.putIfAbsent(
                                mSym, new ProvenanceBinding(mSym, CoverageProvenanceKind.IR_LIFTED, mechRef));
                    }
                }
            }
        }

        return new LiftedAquiferBytecodeSpec(
                effXSpacing,
                effYSpacing,
                effZSpacing,
                effXRange,
                effYRange,
                effZRange,
                effSimScale,
                spreadBucketX,
                spreadBucketY,
                spreadBucketZ,
                lavaMaxLevel,
                lavaThreshold,
                simModule,
                specDigest,
                aqBindings);
    }

    private static int extractIntFieldConst(RawClassFile cls, String name, int fallback) {
        ConceptExpr ce = cls.fieldConstants.get(name);
        if (ce instanceof ConceptExpr.IntConst ic) {
            return ic.value();
        }
        return fallback;
    }

    private static RawMethod findMethodByNameOnly(RawClassFile cls, String name) {
        for (RawMethod m : cls.methods) {
            if (name.equals(m.name)) {
                return m;
            }
        }
        return null;
    }

    private static List<Integer> extractBipushConstants(byte[] code) {
        List<Integer> out = new ArrayList<>(8);
        for (int pc = 0; pc < code.length; ) {
            int op = code[pc] & 0xFF;
            int len = instructionLength(code, pc);
            if (op == 0x10 && pc + 1 < code.length) { // bipush
                out.add((int) code[pc + 1]);
            }
            pc += Math.max(1, len);
        }
        return out;
    }

    /**
     * Captures all primitive fields, nested record/config fields, primitive 1D/2D array lookup tables,
     * defining {@code .class} bytes, runtime {@link ClassLoader}s, and per-instance object-graph
     * nodes from live runtime instances using default traversal bounds.
     */
    public static InstanceSnapshot captureInstanceSnapshot(Object... instances) {
        if (instances == null || instances.length == 0) {
            return InstanceSnapshot.empty();
        }
        return captureInstanceSnapshotInternal(instances, GraphLiftOptions.defaults());
    }

    /**
     * Captures a bounded closed-world live object-graph snapshot rooted at {@code rootInstance}.
     */
    public static InstanceSnapshot captureInstanceSnapshot(
            Object rootInstance,
            GraphLiftOptions options) {
        Objects.requireNonNull(rootInstance, "rootInstance");
        GraphLiftOptions eff = options != null ? options : GraphLiftOptions.defaults();
        return captureInstanceSnapshotInternal(new Object[]{rootInstance}, eff);
    }

    /**
     * Captures a bounded closed-world live object-graph snapshot rooted at {@code rootInstance}
     * with explicit depth/instance bounds and optional {@link ObjectInterner}.
     */
    public static InstanceSnapshot captureInstanceSnapshot(
            Object rootInstance,
            int maxDepth,
            int maxInstances,
            ObjectInterner interner) {
        Objects.requireNonNull(rootInstance, "rootInstance");
        return captureInstanceSnapshotInternal(
                new Object[]{rootInstance},
                new GraphLiftOptions(maxDepth, maxInstances, 64, 1024, interner));
    }

    private static InstanceSnapshot captureInstanceSnapshotInternal(
            Object[] roots,
            GraphLiftOptions options) {
        SnapshotCollector collector = new SnapshotCollector(options);
        List<Integer> rootIds = new ArrayList<>();
        for (Object root : roots) {
            if (root instanceof ClassLoader cl) {
                collector.loaders.add(cl);
            } else if (root instanceof Class<?> explicitClass) {
                collector.pullClassHierarchyBytes(explicitClass);
            } else if (root != null) {
                int id = collector.captureObject(root, 0);
                if (id >= 0) {
                    rootIds.add(id);
                }
            }
        }
        if (!collector.staticArrLuts.isEmpty() || !collector.staticObjRefs.isEmpty()) {
            collector.instancesById.put(
                    STATIC_FIELDS_INSTANCE_ID,
                    new InstanceNode(
                            STATIC_FIELDS_INSTANCE_ID,
                            Object.class,
                            "java/lang/Object",
                            null,
                            collector.flatConstants,
                            collector.staticArrLuts,
                            collector.staticArrRowWidths,
                            collector.staticArrLengths,
                            collector.staticObjRefs,
                            null,
                            null,
                            false,
                            null,
                            null,
                            false,
                            List.of(),
                            null,
                            0,
                            0,
                            null));
        }
        return new InstanceSnapshot(
                Collections.unmodifiableMap(collector.flatConstants),
                Collections.unmodifiableMap(collector.flatLuts),
                List.copyOf(collector.classBytesByName.values()),
                List.copyOf(collector.loaders),
                collector.liftedFields,
                rootIds,
                collector.instancesById,
                collector.liveObjectsById);
    }

    static record ArrayLutInfo(ConceptLut lut, int length, int rowWidth) {
    }

    static final class SnapshotCollector {
        final int maxDepth;
        final int maxInstances;
        final ObjectInterner interner;
        final Map<String, ConceptExpr> flatConstants = new LinkedHashMap<>();
        final Map<String, ConceptLut> flatLuts = new LinkedHashMap<>();
        final Map<String, byte[]> classBytesByName = new LinkedHashMap<>();
        final Set<ClassLoader> loaders = new LinkedHashSet<>();
        final Set<String> liftedFields = new LinkedHashSet<>();
        final IdentityHashMap<Object, Integer> idByObject = new IdentityHashMap<>();
        final Map<Integer, InstanceNode> instancesById = new LinkedHashMap<>();
        final Map<Integer, Object> liveObjectsById = new LinkedHashMap<>();
        final Set<Class<?>> inspectedStaticClasses = new HashSet<>();
        final Map<String, ConceptLut> staticArrLuts = new LinkedHashMap<>();
        final Map<String, Integer> staticArrRowWidths = new LinkedHashMap<>();
        final Map<String, Integer> staticArrLengths = new LinkedHashMap<>();
        final Map<String, Integer> staticObjRefs = new LinkedHashMap<>();
        int nextId = 0;

        SnapshotCollector(GraphLiftOptions options) {
            this.maxDepth = options.maxGraphDepth();
            this.maxInstances = options.maxInstances();
            this.interner = options.objectInterner();
        }

        SnapshotCollector(InstanceSnapshot baseSnapshot, GraphLiftOptions options) {
            this(options);
            this.flatConstants.putAll(baseSnapshot.fieldConstants());
            this.flatLuts.putAll(baseSnapshot.instanceLuts());
            this.loaders.addAll(baseSnapshot.classLoaders());
            this.liftedFields.addAll(baseSnapshot.liftedFieldSymbols());
            for (byte[] bytes : baseSnapshot.discoveredClassBytes()) {
                RawClassFile parsed = parseClassBytes(bytes);
                if (parsed != null) {
                    this.classBytesByName.putIfAbsent(parsed.thisClass, bytes);
                }
            }
            for (Map.Entry<Integer, InstanceNode> e : baseSnapshot.instancesById().entrySet()) {
                this.instancesById.put(e.getKey(), e.getValue());
                if (e.getKey() >= this.nextId) {
                    this.nextId = e.getKey() + 1;
                }
            }
            for (Map.Entry<Integer, Object> e : baseSnapshot.liveObjectsById().entrySet()) {
                Object live = e.getValue();
                if (live != null) {
                    this.liveObjectsById.put(e.getKey(), live);
                    this.idByObject.put(live, e.getKey());
                }
            }
        }

        int captureObject(Object obj, int depth) {
            if (obj == null) {
                return -1;
            }
            Integer existingId = idByObject.get(obj);
            if (existingId != null) {
                return existingId;
            }
            // Boxed primitives are zero-recursion leaf scalars; never truncate them by depth
            // (and allow up to 2x maxInstances so primitive Lists like PerlinNoise.amplitudes stay intact).
            ConceptExpr earlyBoxed = primitiveToConstExpr(obj);
            if (earlyBoxed != null) {
                if (instancesById.size() >= maxInstances * 2) {
                    return -2;
                }
                int boxedId = nextId++;
                idByObject.put(obj, boxedId);
                liveObjectsById.put(boxedId, obj);
                Class<?> boxedClass = obj.getClass();
                InstanceNode node = new InstanceNode(
                        boxedId, boxedClass, boxedClass.getName().replace('.', '/'), null,
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                        null, null,
                        false, null, null,
                        false, List.of(),
                        null, 0, 0,
                        earlyBoxed);
                instancesById.put(boxedId, node);
                return boxedId;
            }
            if (depth > maxDepth || instancesById.size() >= maxInstances) {
                return -2;
            }

            if (obj instanceof Class<?> explicitClass) {
                pullClassHierarchyBytes(explicitClass);
                return -1;
            }

            int myId = nextId++;
            idByObject.put(obj, myId);
            liveObjectsById.put(myId, obj);

            Class<?> clazz = obj.getClass();
            if (clazz.getClassLoader() != null) {
                loaders.add(clazz.getClassLoader());
            }
            String internalName = clazz.getName().replace('.', '/');

            // 1. Caller-supplied terminal object interner (e.g. opaque immutable tokens)
            Integer interned = interner.internObject(obj);
            if (interned != null) {
                InstanceNode node = new InstanceNode(
                        myId, clazz, internalName, null,
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                        null, interned,
                        false, null, null,
                        false, List.of(),
                        null, 0, 0,
                        null);
                instancesById.put(myId, node);
                return myId;
            }

            // 2. Boxed primitives
            ConceptExpr boxed = primitiveToConstExpr(obj);
            if (boxed != null) {
                InstanceNode node = new InstanceNode(
                        myId, clazz, internalName, null,
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                        null, null,
                        false, null, null,
                        false, List.of(),
                        null, 0, 0,
                        boxed);
                instancesById.put(myId, node);
                return myId;
            }

            // 3. Optional / OptionalInt / OptionalLong / OptionalDouble
            if (obj instanceof Optional<?> opt) {
                boolean present = opt.isPresent();
                Integer targetId = present ? captureObject(opt.get(), depth + 1) : null;
                InstanceNode node = new InstanceNode(
                        myId, clazz, internalName, null,
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                        null, null,
                        present, targetId, null,
                        false, List.of(),
                        null, 0, 0,
                        null);
                instancesById.put(myId, node);
                return myId;
            }
            if (obj instanceof OptionalInt oi) {
                boolean present = oi.isPresent();
                ConceptExpr prim = present ? new ConceptExpr.IntConst(oi.getAsInt()) : null;
                InstanceNode node = new InstanceNode(
                        myId, clazz, internalName, null,
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                        null, null,
                        present, null, prim,
                        false, List.of(),
                        null, 0, 0,
                        null);
                instancesById.put(myId, node);
                return myId;
            }
            if (obj instanceof OptionalLong ol) {
                boolean present = ol.isPresent();
                ConceptExpr prim = present ? new ConceptExpr.LongConst(ol.getAsLong()) : null;
                InstanceNode node = new InstanceNode(
                        myId, clazz, internalName, null,
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                        null, null,
                        present, null, prim,
                        false, List.of(),
                        null, 0, 0,
                        null);
                instancesById.put(myId, node);
                return myId;
            }
            if (obj instanceof OptionalDouble od) {
                boolean present = od.isPresent();
                ConceptExpr prim = present ? new ConceptExpr.DoubleConst(od.getAsDouble()) : null;
                InstanceNode node = new InstanceNode(
                        myId, clazz, internalName, null,
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                        null, null,
                        present, null, prim,
                        false, List.of(),
                        null, 0, 0,
                        null);
                instancesById.put(myId, node);
                return myId;
            }

            // 4. Direct 1D or uniform 2D primitive arrays
            ArrayLutInfo directLut = extractPrimitiveArrayLut(
                    "array", "inst_" + myId, "lut_i" + myId + "_arr", obj);
            if (directLut != null) {
                InstanceNode node = new InstanceNode(
                        myId, clazz, internalName, null,
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                        null, null,
                        false, null, null,
                        false, List.of(),
                        directLut.lut(), directLut.rowWidth(), directLut.length(),
                        null);
                instancesById.put(myId, node);
                return myId;
            }

            // 5. List<?> or Object[]
            if (obj instanceof List<?> list) {
                List<Integer> elemIds = new ArrayList<>(Math.min(list.size(), 4096));
                if (list.size() <= 4096) {
                    for (Object elem : list) {
                        elemIds.add(captureObject(elem, depth + 1));
                    }
                }
                InstanceNode node = new InstanceNode(
                        myId, clazz, internalName, null,
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                        null, null,
                        false, null, null,
                        true, elemIds,
                        null, 0, list.size(),
                        null);
                instancesById.put(myId, node);
                return myId;
            }
            if (obj instanceof Object[] objArr) {
                Class<?> compType = clazz.getComponentType();
                if (compType != null) {
                    pullClassHierarchyBytes(compType);
                }
                List<Integer> elemIds = new ArrayList<>(Math.min(objArr.length, 4096));
                if (objArr.length <= 4096) {
                    for (Object elem : objArr) {
                        elemIds.add(captureObject(elem, depth + 1));
                    }
                }
                Map<String, ConceptLut> soaLuts = extractHomogeneousStructArrayLuts(myId, objArr);
                InstanceNode node = new InstanceNode(
                        myId, clazz, internalName, null,
                        Map.of(), soaLuts, Map.of(), Map.of(), Map.of(),
                        null, null,
                        false, null, null,
                        true, elemIds,
                        null, 0, objArr.length,
                        null);
                instancesById.put(myId, node);
                return myId;
            }

            // 6. Other JDK/system classes and third-party Map implementations (opaque terminal; do not walk hash buckets)
            String pkg = clazz.getName();
            if (pkg.startsWith("java.")
                    || pkg.startsWith("jdk.")
                    || pkg.startsWith("sun.")
                    || (obj instanceof Map<?, ?>
                            && (pkg.startsWith("it.unimi.dsi.fastutil.")
                                    || pkg.startsWith("com.google.common.collect.")))) {
                InstanceNode node = new InstanceNode(
                        myId, clazz, internalName, null,
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                        null, null,
                        false, null, null,
                        false, List.of(),
                        null, 0, 0,
                        null);
                instancesById.put(myId, node);
                return myId;
            }

            // 7. User/application class (including Records and Enums)
            pullClassHierarchyBytes(clazz);
            byte[] selfClassBytes = classBytesByName.get(internalName);
            Integer enumOrd = (obj instanceof Enum<?> en) ? en.ordinal() : null;

            Map<String, ConceptExpr> primFields = new LinkedHashMap<>();
            Map<String, ConceptLut> arrLuts = new LinkedHashMap<>();
            Map<String, Integer> arrRowWidths = new LinkedHashMap<>();
            Map<String, Integer> arrLengths = new LinkedHashMap<>();
            Map<String, Integer> objRefs = new LinkedHashMap<>();

            // Insert placeholder node before recursing so cycles see a valid entry in idByObject
            for (Class<?> cur = clazz;
                    cur != null && cur != Object.class && cur != Enum.class && cur != Record.class;
                    cur = cur.getSuperclass()) {
                String curName = cur.getName();
                if (curName.startsWith("java.") || curName.startsWith("jdk.") || curName.startsWith("sun.")) {
                    break;
                }
                pullClassHierarchyBytes(cur);
                String owner = curName.replace('.', '/');
                Map<String, RecordComponent> recordComponents = Map.of();
                if (cur.isRecord()) {
                    RecordComponent[] comps = cur.getRecordComponents();
                    if (comps != null && comps.length > 0) {
                        recordComponents = new HashMap<>(comps.length * 2);
                        for (RecordComponent rc : comps) {
                            recordComponents.put(rc.getName(), rc);
                        }
                    }
                }
                for (Field field : cur.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    String fName = field.getName();
                    String key = owner + "#" + fName;
                    String binarySym = curName + "#" + fName;
                    Object val;
                    if (field.trySetAccessible()) {
                        try {
                            val = field.get(obj);
                        } catch (ReflectiveOperationException | RuntimeException ex) {
                            java.util.logging.Logger.getLogger(BytecodeSsaLifter.class.getName())
                                    .log(
                                            java.util.logging.Level.WARNING,
                                            "Failed to capture instance field " + key,
                                            ex);
                            continue;
                        }
                    } else {
                        RecordComponent rc = recordComponents.get(fName);
                        if (rc != null && rc.getAccessor().trySetAccessible()) {
                            try {
                                val = rc.getAccessor().invoke(obj);
                            } catch (ReflectiveOperationException | RuntimeException rcEx) {
                                java.util.logging.Logger.getLogger(BytecodeSsaLifter.class.getName())
                                        .log(
                                                java.util.logging.Level.WARNING,
                                                "Failed to capture record component " + key,
                                                rcEx);
                                continue;
                            }
                        } else {
                            continue;
                        }
                    }

                    if (val == null) {
                        objRefs.put(key, -1);
                        objRefs.putIfAbsent(fName, -1);
                        continue;
                    }

                    if (field.getType().isPrimitive()) {
                        ConceptExpr constExpr = primitiveToConstExpr(val);
                        if (constExpr != null) {
                            primFields.put(key, constExpr);
                            primFields.putIfAbsent(fName, constExpr);
                            flatConstants.putIfAbsent(key, constExpr);
                            flatConstants.putIfAbsent(fName, constExpr);
                            liftedFields.add(binarySym);
                        }
                        continue;
                    }

                    // Check 1D / uniform 2D primitive arrays
                    String perInstSym = "lut_i" + myId + "_" + sanitizeFieldForSym(owner, fName);
                    ArrayLutInfo lutInfo = extractPrimitiveArrayLut(owner, fName, perInstSym, val);
                    if (lutInfo != null) {
                        arrLuts.put(key, lutInfo.lut());
                        arrLuts.putIfAbsent(fName, lutInfo.lut());
                        arrRowWidths.put(key, lutInfo.rowWidth());
                        arrRowWidths.putIfAbsent(fName, lutInfo.rowWidth());
                        arrLengths.put(key, lutInfo.length());
                        arrLengths.putIfAbsent(fName, lutInfo.length());
                        ConceptLut legacyLut = arrayToConceptLut(owner, fName, val);
                        if (legacyLut != null) {
                            flatLuts.putIfAbsent(key, legacyLut);
                            flatLuts.putIfAbsent(fName, legacyLut);
                            liftedFields.add(binarySym);
                        }
                        int arrChildId = captureObject(val, depth + 1);
                        objRefs.put(key, arrChildId);
                        objRefs.putIfAbsent(fName, arrChildId);
                        continue;
                    }

                    // Legacy flat snapshot compatibility for enums and struct-array LUTs
                    if (val instanceof Enum<?> en) {
                        ConceptExpr ord = new ConceptExpr.IntConst(en.ordinal());
                        flatConstants.putIfAbsent(key, ord);
                        flatConstants.putIfAbsent(fName, ord);
                        liftedFields.add(binarySym);
                    } else if (val instanceof Object[] oa) {
                        arrLengths.put(key, oa.length);
                        arrLengths.putIfAbsent(fName, oa.length);
                        ConceptLut legacyLut = arrayToConceptLut(owner, fName, val);
                        if (legacyLut != null) {
                            flatLuts.putIfAbsent(key, legacyLut);
                            flatLuts.putIfAbsent(fName, legacyLut);
                            liftedFields.add(binarySym);
                        }
                    } else if (val instanceof List<?> l) {
                        arrLengths.put(key, l.size());
                        arrLengths.putIfAbsent(fName, l.size());
                    } else {
                        ConceptExpr boxedConst = primitiveToConstExpr(val);
                        if (boxedConst != null) {
                            flatConstants.putIfAbsent(key, boxedConst);
                            flatConstants.putIfAbsent(fName, boxedConst);
                            liftedFields.add(binarySym);
                        }
                    }

                    int childId = captureObject(val, depth + 1);
                    objRefs.put(key, childId);
                    objRefs.putIfAbsent(fName, childId);
                }
            }

            InstanceNode node = new InstanceNode(
                    myId, clazz, internalName, selfClassBytes,
                    primFields, arrLuts, arrRowWidths, arrLengths, objRefs,
                    enumOrd, null,
                    false, null, null,
                    false, List.of(),
                    null, 0, 0,
                    null);
            instancesById.put(myId, node);
            return myId;
        }

        void pullClassHierarchyBytes(Class<?> clazz) {
            if (clazz == null || clazz.isPrimitive() || clazz.isArray()) {
                return;
            }
            String pkg = clazz.getName();
            if (pkg.startsWith("java.") || pkg.startsWith("jdk.") || pkg.startsWith("sun.")) {
                return;
            }
            if (clazz.getClassLoader() != null) {
                loaders.add(clazz.getClassLoader());
            }
            pullSingleClassBytes(clazz, classBytesByName);
            if (inspectedStaticClasses.add(clazz)) {
                captureStaticFinalFields(clazz);
            }
            Class<?> sup = clazz.getSuperclass();
            if (sup != null && sup != Object.class) {
                pullClassHierarchyBytes(sup);
            }
            for (Class<?> iface : clazz.getInterfaces()) {
                pullClassHierarchyBytes(iface);
            }
        }

        private void captureStaticFinalFields(Class<?> clazz) {
            String owner = clazz.getName().replace('.', '/');
            for (Field field : clazz.getDeclaredFields()) {
                int mods = field.getModifiers();
                if (!Modifier.isStatic(mods) || !Modifier.isFinal(mods)) {
                    continue;
                }
                String fName = field.getName();
                if (clazz.isEnum() && fName.startsWith("$VALUES")) {
                    continue;
                }
                String key = owner + "#" + fName;
                Object val;
                try {
                    field.setAccessible(true);
                    val = field.get(null);
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    java.util.logging.Logger.getLogger(BytecodeSsaLifter.class.getName())
                            .log(
                                    java.util.logging.Level.WARNING,
                                    "Failed to capture static final field " + key,
                                    ex);
                    continue;
                }
                if (val == null) {
                    staticObjRefs.put(key, -1);
                    continue;
                }
                if (field.getType().isPrimitive()) {
                    ConceptExpr ce = primitiveToConstExpr(val);
                    if (ce != null) {
                        flatConstants.putIfAbsent(key, ce);
                        flatConstants.putIfAbsent(fName, ce);
                    }
                    continue;
                }
                String sym = "lut_static_" + sanitizeFieldForSym(owner, fName);
                ArrayLutInfo lutInfo = extractPrimitiveArrayLut(owner, fName, sym, val);
                if (lutInfo != null) {
                    staticArrLuts.put(key, lutInfo.lut());
                    staticArrRowWidths.put(key, lutInfo.rowWidth());
                    staticArrLengths.put(key, lutInfo.length());
                    flatLuts.putIfAbsent(key, lutInfo.lut());
                    int arrId = captureObject(val, 1);
                    staticObjRefs.put(key, arrId);
                    continue;
                }
                if (val instanceof Enum<?> en) {
                    flatConstants.putIfAbsent(key, new ConceptExpr.IntConst(en.ordinal()));
                    int sid = captureObject(val, 1);
                    staticObjRefs.put(key, sid);
                }
                // Non-enum static object fields (e.g. MapCodec CODEC graphs) are captured lazily on demand
                // in ClosedWorldGraphLifter only when reachable bytecode executes GETSTATIC on them.
            }
        }

        private Map<String, ConceptLut> extractHomogeneousStructArrayLuts(int arrayId, Object[] objArr) {
            if (objArr.length == 0 || objArr.length > 4096 || objArr[0] == null) {
                return Map.of();
            }
            Class<?> elemClass = objArr[0].getClass();
            if (elemClass.isArray() || elemClass.isEnum()) {
                return Map.of();
            }
            String pkg = elemClass.getName();
            if (pkg.startsWith("java.") || pkg.startsWith("jdk.") || pkg.startsWith("sun.")) {
                return Map.of();
            }
            for (int i = 1; i < objArr.length; i++) {
                if (objArr[i] == null || objArr[i].getClass() != elemClass) {
                    return Map.of();
                }
            }
            pullClassHierarchyBytes(elemClass);
            String elemInternal = elemClass.getName().replace('.', '/');
            Map<String, RecordComponent> recordComponents = new HashMap<>();
            if (elemClass.isRecord()) {
                RecordComponent[] comps = elemClass.getRecordComponents();
                if (comps != null) {
                    for (RecordComponent rc : comps) {
                        recordComponents.put(rc.getName(), rc);
                    }
                }
            }

            Map<String, ConceptLut> soa = new LinkedHashMap<>();
            for (Class<?> cur = elemClass;
                    cur != null && cur != Object.class && cur != Record.class;
                    cur = cur.getSuperclass()) {
                String fOwner = cur.getName().replace('.', '/');
                for (Field f : cur.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || !f.getType().isPrimitive()) {
                        continue;
                    }
                    String fName = f.getName();
                    ConceptType lutType = primitiveClassToLutType(f.getType());
                    List<Number> vals = new ArrayList<>(objArr.length);
                    boolean ok = true;
                    for (Object elem : objArr) {
                        Object fv = null;
                        try {
                            f.setAccessible(true);
                            fv = f.get(elem);
                        } catch (ReflectiveOperationException | RuntimeException ex) {
                            RecordComponent rc = recordComponents.get(fName);
                            if (rc != null) {
                                try {
                                    rc.getAccessor().setAccessible(true);
                                    fv = rc.getAccessor().invoke(elem);
                                } catch (ReflectiveOperationException | RuntimeException rcEx) {
                                    java.util.logging.Logger.getLogger(BytecodeSsaLifter.class.getName())
                                            .log(
                                                    java.util.logging.Level.WARNING,
                                                    "Failed to capture SoA record component " + fOwner + "#" + fName,
                                                    rcEx);
                                    ok = false;
                                    break;
                                }
                            } else {
                                java.util.logging.Logger.getLogger(BytecodeSsaLifter.class.getName())
                                        .log(
                                                java.util.logging.Level.WARNING,
                                                "Failed to capture SoA struct field " + fOwner + "#" + fName,
                                                ex);
                                ok = false;
                                break;
                            }
                        }
                        Number num = primitiveBoxToNumber(fv);
                        if (num == null) {
                            ok = false;
                            break;
                        }
                        vals.add(num);
                    }
                    if (ok) {
                        String sym = "lut_soa_i" + arrayId + "_" + sanitizeFieldForSym(fOwner, fName);
                        ConceptLut lut = new ConceptLut(fOwner, fName, sym, lutType, vals);
                        soa.put(fOwner + "#" + fName, lut);
                        soa.putIfAbsent(elemInternal + "#" + fName, lut);
                        soa.putIfAbsent(fName, lut);
                    }
                }
            }
            return soa;
        }

        private static ConceptType primitiveClassToLutType(Class<?> pt) {
            if (pt == long.class) {
                return ConceptType.I64;
            }
            if (pt == float.class) {
                return ConceptType.F32;
            }
            if (pt == double.class) {
                return ConceptType.F64;
            }
            return ConceptType.I32;
        }

        private static Number primitiveBoxToNumber(Object val) {
            if (val instanceof Number n) {
                return n;
            }
            if (val instanceof Boolean b) {
                return b ? 1 : 0;
            }
            if (val instanceof Character c) {
                return (int) c;
            }
            return null;
        }
    }

    static String sanitizeFieldForSym(String owner, String fieldName) {
        String simple = simpleClassName(owner);
        StringBuilder sb = new StringBuilder(simple.length() + fieldName.length() + 1);
        for (int i = 0; i < simple.length(); i++) {
            char c = simple.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
        }
        sb.append('_');
        for (int i = 0; i < fieldName.length(); i++) {
            char c = fieldName.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
        }
        return sb.toString();
    }

    static ArrayLutInfo extractPrimitiveArrayLut(
            String owner,
            String fieldName,
            String sym,
            Object arrObj) {
        if (arrObj instanceof int[] ia && ia.length <= 65536) {
            List<Number> vals = new ArrayList<>(ia.length);
            for (int v : ia) {
                vals.add(v);
            }
            return new ArrayLutInfo(new ConceptLut(owner, fieldName, sym, ConceptType.I32, vals), ia.length, 0);
        }
        if (arrObj instanceof byte[] ba && ba.length <= 65536) {
            List<Number> vals = new ArrayList<>(ba.length);
            for (byte v : ba) {
                vals.add((int) v);
            }
            return new ArrayLutInfo(new ConceptLut(owner, fieldName, sym, ConceptType.I32, vals), ba.length, 0);
        }
        if (arrObj instanceof short[] sa && sa.length <= 65536) {
            List<Number> vals = new ArrayList<>(sa.length);
            for (short v : sa) {
                vals.add((int) v);
            }
            return new ArrayLutInfo(new ConceptLut(owner, fieldName, sym, ConceptType.I32, vals), sa.length, 0);
        }
        if (arrObj instanceof char[] ca && ca.length <= 65536) {
            List<Number> vals = new ArrayList<>(ca.length);
            for (char v : ca) {
                vals.add((int) v);
            }
            return new ArrayLutInfo(new ConceptLut(owner, fieldName, sym, ConceptType.I32, vals), ca.length, 0);
        }
        if (arrObj instanceof boolean[] za && za.length <= 65536) {
            List<Number> vals = new ArrayList<>(za.length);
            for (boolean v : za) {
                vals.add(v ? 1 : 0);
            }
            return new ArrayLutInfo(new ConceptLut(owner, fieldName, sym, ConceptType.I32, vals), za.length, 0);
        }
        if (arrObj instanceof long[] la && la.length <= 65536) {
            List<Number> vals = new ArrayList<>(la.length);
            for (long v : la) {
                vals.add(v);
            }
            return new ArrayLutInfo(new ConceptLut(owner, fieldName, sym, ConceptType.I64, vals), la.length, 0);
        }
        if (arrObj instanceof float[] fa && fa.length <= 65536) {
            List<Number> vals = new ArrayList<>(fa.length);
            for (float v : fa) {
                vals.add(v);
            }
            return new ArrayLutInfo(new ConceptLut(owner, fieldName, sym, ConceptType.F32, vals), fa.length, 0);
        }
        if (arrObj instanceof double[] da && da.length <= 65536) {
            List<Number> vals = new ArrayList<>(da.length);
            for (double v : da) {
                vals.add(v);
            }
            return new ArrayLutInfo(new ConceptLut(owner, fieldName, sym, ConceptType.F64, vals), da.length, 0);
        }
        // Uniform 2D primitive arrays
        if (arrObj instanceof int[][] ia2 && ia2.length > 0 && ia2[0] != null && ia2[0].length > 0) {
            int rowWidth = ia2[0].length;
            if ((long) ia2.length * rowWidth <= 65536) {
                List<Number> flat = new ArrayList<>(ia2.length * rowWidth);
                for (int[] row : ia2) {
                    if (row == null || row.length != rowWidth) {
                        return null;
                    }
                    for (int v : row) {
                        flat.add(v);
                    }
                }
                return new ArrayLutInfo(
                        new ConceptLut(owner, fieldName, sym, ConceptType.I32, flat), ia2.length, rowWidth);
            }
        }
        if (arrObj instanceof byte[][] ba2 && ba2.length > 0 && ba2[0] != null && ba2[0].length > 0) {
            int rowWidth = ba2[0].length;
            if ((long) ba2.length * rowWidth <= 65536) {
                List<Number> flat = new ArrayList<>(ba2.length * rowWidth);
                for (byte[] row : ba2) {
                    if (row == null || row.length != rowWidth) {
                        return null;
                    }
                    for (byte v : row) {
                        flat.add((int) v);
                    }
                }
                return new ArrayLutInfo(
                        new ConceptLut(owner, fieldName, sym, ConceptType.I32, flat), ba2.length, rowWidth);
            }
        }
        if (arrObj instanceof short[][] sa2 && sa2.length > 0 && sa2[0] != null && sa2[0].length > 0) {
            int rowWidth = sa2[0].length;
            if ((long) sa2.length * rowWidth <= 65536) {
                List<Number> flat = new ArrayList<>(sa2.length * rowWidth);
                for (short[] row : sa2) {
                    if (row == null || row.length != rowWidth) {
                        return null;
                    }
                    for (short v : row) {
                        flat.add((int) v);
                    }
                }
                return new ArrayLutInfo(
                        new ConceptLut(owner, fieldName, sym, ConceptType.I32, flat), sa2.length, rowWidth);
            }
        }
        if (arrObj instanceof long[][] la2 && la2.length > 0 && la2[0] != null && la2[0].length > 0) {
            int rowWidth = la2[0].length;
            if ((long) la2.length * rowWidth <= 65536) {
                List<Number> flat = new ArrayList<>(la2.length * rowWidth);
                for (long[] row : la2) {
                    if (row == null || row.length != rowWidth) {
                        return null;
                    }
                    for (long v : row) {
                        flat.add(v);
                    }
                }
                return new ArrayLutInfo(
                        new ConceptLut(owner, fieldName, sym, ConceptType.I64, flat), la2.length, rowWidth);
            }
        }
        if (arrObj instanceof float[][] fa2 && fa2.length > 0 && fa2[0] != null && fa2[0].length > 0) {
            int rowWidth = fa2[0].length;
            if ((long) fa2.length * rowWidth <= 65536) {
                List<Number> flat = new ArrayList<>(fa2.length * rowWidth);
                for (float[] row : fa2) {
                    if (row == null || row.length != rowWidth) {
                        return null;
                    }
                    for (float v : row) {
                        flat.add(v);
                    }
                }
                return new ArrayLutInfo(
                        new ConceptLut(owner, fieldName, sym, ConceptType.F32, flat), fa2.length, rowWidth);
            }
        }
        if (arrObj instanceof double[][] da2 && da2.length > 0 && da2[0] != null && da2[0].length > 0) {
            int rowWidth = da2[0].length;
            if ((long) da2.length * rowWidth <= 65536) {
                List<Number> flat = new ArrayList<>(da2.length * rowWidth);
                for (double[] row : da2) {
                    if (row == null || row.length != rowWidth) {
                        return null;
                    }
                    for (double v : row) {
                        flat.add(v);
                    }
                }
                return new ArrayLutInfo(
                        new ConceptLut(owner, fieldName, sym, ConceptType.F64, flat), da2.length, rowWidth);
            }
        }
        return null;
    }

    static void pullSingleClassBytes(Class<?> clazz, Map<String, byte[]> out) {
        if (clazz == null || clazz.isPrimitive() || clazz.isArray()) {
            return;
        }
        String internal = clazz.getName().replace('.', '/');
        if (out.containsKey(internal)) {
            return;
        }
        String res = internal + ".class";
        ClassLoader loader = clazz.getClassLoader();
        try (InputStream in = loader != null
                ? loader.getResourceAsStream(res)
                : ClassLoader.getSystemResourceAsStream(res)) {
            if (in != null) {
                out.put(internal, in.readAllBytes());
            }
        } catch (IOException ignored) {
        }
    }

    static ConceptExpr primitiveToConstExpr(Object val) {
        if (val instanceof Byte || val instanceof Short || val instanceof Integer) {
            return new ConceptExpr.IntConst(((Number) val).intValue());
        }
        if (val instanceof Character ch) {
            return new ConceptExpr.IntConst((int) ch);
        }
        if (val instanceof Long l) {
            return new ConceptExpr.LongConst(l);
        }
        if (val instanceof Float f) {
            return new ConceptExpr.FloatConst(f);
        }
        if (val instanceof Double d) {
            return new ConceptExpr.DoubleConst(d);
        }
        if (val instanceof Boolean b) {
            return new ConceptExpr.IntConst(b ? 1 : 0);
        }
        return null;
    }

    private static ConceptLut arrayToConceptLut(String owner, String fieldName, Object arrObj) {
        String sym = lutSymbol(owner, fieldName);
        if (arrObj instanceof int[] ia && ia.length > 0 && ia.length <= 65536) {
            List<Number> vals = new ArrayList<>(ia.length);
            for (int v : ia) {
                vals.add(v);
            }
            return new ConceptLut(owner, fieldName, sym, ConceptType.I32, vals);
        }
        if (arrObj instanceof byte[] ba && ba.length > 0 && ba.length <= 65536) {
            List<Number> vals = new ArrayList<>(ba.length);
            for (byte v : ba) {
                vals.add((int) v);
            }
            return new ConceptLut(owner, fieldName, sym, ConceptType.I32, vals);
        }
        if (arrObj instanceof short[] sa && sa.length > 0 && sa.length <= 65536) {
            List<Number> vals = new ArrayList<>(sa.length);
            for (short v : sa) {
                vals.add((int) v);
            }
            return new ConceptLut(owner, fieldName, sym, ConceptType.I32, vals);
        }
        if (arrObj instanceof long[] la && la.length > 0 && la.length <= 65536) {
            List<Number> vals = new ArrayList<>(la.length);
            for (long v : la) {
                vals.add(v);
            }
            return new ConceptLut(owner, fieldName, sym, ConceptType.I64, vals);
        }
        if (arrObj instanceof float[] fa && fa.length > 0 && fa.length <= 65536) {
            List<Number> vals = new ArrayList<>(fa.length);
            for (float v : fa) {
                vals.add(v);
            }
            return new ConceptLut(owner, fieldName, sym, ConceptType.F32, vals);
        }
        if (arrObj instanceof double[] da && da.length > 0 && da.length <= 65536) {
            List<Number> vals = new ArrayList<>(da.length);
            for (double v : da) {
                vals.add(v);
            }
            return new ConceptLut(owner, fieldName, sym, ConceptType.F64, vals);
        }
        if (arrObj instanceof Object[] oa && oa.length > 0 && oa.length <= 4096) {
            List<Number> flat = new ArrayList<>();
            for (Object elem : oa) {
                if (elem instanceof int[] row) {
                    for (int v : row) {
                        flat.add(v);
                    }
                } else if (elem instanceof List<?> tuple && !tuple.isEmpty()) {
                    for (Object item : tuple) {
                        if (item instanceof Number n) {
                            flat.add(n.intValue());
                        }
                    }
                } else if (elem != null && !elem.getClass().isArray()) {
                    boolean addedAny = false;
                    for (Field f : elem.getClass().getDeclaredFields()) {
                        if ((f.getModifiers() & ACC_STATIC) != 0) {
                            continue;
                        }
                        try {
                            f.setAccessible(true);
                            Object fv = f.get(elem);
                            if (fv instanceof Number n) {
                                flat.add(n.intValue());
                                addedAny = true;
                            }
                        } catch (ReflectiveOperationException | RuntimeException ex) {
                            java.util.logging.Logger.getLogger(BytecodeSsaLifter.class.getName())
                                    .log(
                                            java.util.logging.Level.WARNING,
                                            "Failed to inspect LUT element field " + owner + "#" + fieldName,
                                            ex);
                        }
                    }
                    if (!addedAny) {
                        return null;
                    }
                } else {
                    return null;
                }
            }
            if (!flat.isEmpty() && flat.size() <= 65536) {
                return new ConceptLut(owner, fieldName, sym, ConceptType.I32, flat);
            }
        }
        return null;
    }

    /**
     * Collects exact {@link CoverageProvenanceKind#IR_LIFTED} provenance bindings for every
     * {@link ConceptFunction}, {@link ConceptLut}, and consumed {@link InstanceSnapshot} field
     * present in a genuinely lifted {@link ConceptModule}.
     */
    public static Map<String, ProvenanceBinding> collectModuleProvenanceBindings(
            ConceptModule module,
            InstanceSnapshot snapshot) {
        if (module == null || module.entryFunction() == null || !module.isPureCompute()) {
            return Map.of();
        }
        Map<String, ProvenanceBinding> out = new LinkedHashMap<>();
        Set<String> liftedOwners = new LinkedHashSet<>();
        String moduleHash = BytecodeConceptIr.canonicalModuleHash(module);
        String moduleMech = "BytecodeSsaLifter#ConceptModule:" + module.moduleName() + "@sha256=" + moduleHash;

        for (String methodSym : module.liftedMethodSymbols()) {
            if (methodSym == null || methodSym.isBlank()) {
                continue;
            }
            int hashIdx = methodSym.indexOf('#');
            if (hashIdx > 0) {
                liftedOwners.add(methodSym.substring(0, hashIdx));
            }
            out.put(methodSym, new ProvenanceBinding(methodSym, CoverageProvenanceKind.IR_LIFTED, moduleMech));
        }

        for (String fieldSym : module.liftedFieldSymbols()) {
            if (fieldSym == null || fieldSym.isBlank()) {
                continue;
            }
            int hashIdx = fieldSym.indexOf('#');
            if (hashIdx > 0) {
                liftedOwners.add(fieldSym.substring(0, hashIdx));
            }
            out.put(fieldSym, new ProvenanceBinding(fieldSym, CoverageProvenanceKind.IR_LIFTED, moduleMech));
        }

        List<ConceptFunction> functions = new ArrayList<>(1 + module.helperFunctions().size());
        functions.add(module.entryFunction());
        functions.addAll(module.helperFunctions());
        for (ConceptFunction fn : functions) {
            if (fn == null || fn.ownerClass() == null || fn.ownerClass().isBlank() || fn.name() == null || fn.name().isBlank()) {
                continue;
            }
            String ownerBin = fn.ownerClass().replace('/', '.');
            liftedOwners.add(ownerBin);
            if (module.liftedMethodSymbols().isEmpty()) {
                String sym = ownerBin + "#" + fn.name();
                String fnHash = BytecodeConceptIr.canonicalHash(fn);
                String mech = "BytecodeSsaLifter#ConceptFunction:" + sym + fn.descriptor() + "@sha256=" + fnHash;
                out.putIfAbsent(sym, new ProvenanceBinding(sym, CoverageProvenanceKind.IR_LIFTED, mech));
            }
        }

        for (ConceptLut lut : module.lookupTables()) {
            if (lut == null || lut.ownerClass() == null || lut.ownerClass().isBlank()
                    || lut.fieldName() == null || lut.fieldName().isBlank()) {
                continue;
            }
            String ownerBin = lut.ownerClass().replace('/', '.');
            liftedOwners.add(ownerBin);
            String fieldSym = ownerBin + "#" + lut.fieldName();
            String lutHash = sha256Hex16(lut.symbolName() + ":" + lut.elementType() + ":" + lut.values());
            String mech = "BytecodeSsaLifter#ConceptLut:" + lut.symbolName() + "@sha256=" + lutHash;
            out.put(fieldSym, new ProvenanceBinding(fieldSym, CoverageProvenanceKind.IR_LIFTED, mech));
            String clinitSym = ownerBin + "#<clinit>";
            out.putIfAbsent(clinitSym, new ProvenanceBinding(clinitSym, CoverageProvenanceKind.IR_LIFTED, mech));
        }

        if (module.liftedFieldSymbols().isEmpty() && snapshot != null && !snapshot.liftedFieldSymbols().isEmpty()) {
            String snapMech = "BytecodeSsaLifter#InstanceSnapshot:" + module.moduleName() + "@sha256=" + moduleHash;
            for (String fieldSym : snapshot.liftedFieldSymbols()) {
                int hash = fieldSym.indexOf('#');
                if (hash > 0) {
                    String owner = fieldSym.substring(0, hash);
                    if (liftedOwners.contains(owner)) {
                        out.putIfAbsent(
                                fieldSym, new ProvenanceBinding(fieldSym, CoverageProvenanceKind.IR_LIFTED, snapMech));
                    }
                }
            }
        }
        return Collections.unmodifiableMap(out);
    }

    static String sha256Hex16(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Lifts an instance or static method directly from a live runtime object instance, binding
     * its frozen field state and array lookup tables into the resulting {@link ConceptModule}.
     */
    public static ConceptModule liftInstanceModule(
            Object rootInstance,
            String entryMethodName) {
        return liftInstanceModule(rootInstance, entryMethodName, null);
    }

    /**
     * Lifts an instance or static method (optionally disambiguated by descriptor) from a live
     * runtime object instance.
     */
    public static ConceptModule liftInstanceModule(
            Object rootInstance,
            String entryMethodName,
            String entryDescriptorOrNull) {
        Objects.requireNonNull(rootInstance, "rootInstance");
        InstanceSnapshot snapshot = captureInstanceSnapshot(rootInstance);
        return liftModule(
                snapshot.discoveredClassBytes(),
                rootInstance.getClass().getName(),
                entryMethodName,
                entryDescriptorOrNull,
                snapshot);
    }

    /**
     * Recursively lifts a closed-world live object graph rooted at {@code rootInstance} into a
     * single {@link ConceptModule} using pure JVM bytecode semantics and per-instance structural
     * snapshotting with default {@link GraphLiftOptions}.
     */
    public static ConceptModule liftInstanceGraph(
            Object rootInstance,
            String entryMethodName,
            String entryDescriptor) {
        return liftInstanceGraph(rootInstance, entryMethodName, entryDescriptor, GraphLiftOptions.defaults());
    }

    /**
     * Recursively lifts a closed-world live object graph rooted at {@code rootInstance} into a
     * single {@link ConceptModule} using pure JVM bytecode semantics and per-instance structural
     * snapshotting.
     *
     * <p>Specializes helper functions per {@code (concreteClass, method, instanceId)}, erases
     * structural delegator wrappers by bytecode shape, unrolls constant-length heterogeneous
     * {@code List}/{@code Object[]} loops, lifts homogeneous {@code Record[]}/{@code Object[]}
     * arrays to SoA LUTs, and fails closed with exact {@link UnmappedReason} diagnostics on any
     * reachable impure side effect, native call, or non-constant dynamic dispatch.
     */
    public static ConceptModule liftInstanceGraph(
            Object rootInstance,
            String entryMethodName,
            String entryDescriptor,
            GraphLiftOptions options) {
        Objects.requireNonNull(rootInstance, "rootInstance");
        Objects.requireNonNull(entryMethodName, "entryMethodName");
        GraphLiftOptions effOptions = options != null ? options : GraphLiftOptions.defaults();
        String rootClassName = rootInstance.getClass().getName();
        if (rootClassName.contains("$$Lambda")) {
            ConceptModule lambdaMod = tryLiftHiddenLambdaInstance(
                    rootInstance, rootClassName, entryMethodName, entryDescriptor, effOptions);
            if (lambdaMod != null) {
                return lambdaMod;
            }
        }
        InstanceSnapshot snapshot = captureInstanceSnapshot(rootInstance, effOptions);
        return ClosedWorldGraphLifter.liftInstanceGraph(
                snapshot,
                rootClassName,
                entryMethodName,
                entryDescriptor,
                effOptions);
    }

    private static ConceptModule tryLiftHiddenLambdaInstance(
            Object lambdaInstance,
            String lambdaClassName,
            String entryMethodName,
            String entryDescriptor,
            GraphLiftOptions effOptions) {
        int markerIdx = lambdaClassName.indexOf("$$Lambda");
        if (markerIdx <= 0) {
            return null;
        }
        String enclosingBinary = lambdaClassName.substring(0, markerIdx);
        String enclosingInternal = enclosingBinary.replace('.', '/');
        ClassLoader cl = lambdaInstance.getClass().getClassLoader();
        byte[] enclosingBytes = null;
        if (cl != null) {
            try (InputStream is = cl.getResourceAsStream(enclosingInternal + ".class")) {
                if (is != null) {
                    enclosingBytes = is.readAllBytes();
                }
            } catch (IOException ignored) {
                return null;
            }
        }
        if (enclosingBytes == null) {
            return null;
        }
        RawClassFile enclosingCls = parseClassBytes(enclosingBytes);
        if (enclosingCls == null) {
            return null;
        }
        // Read captured lambda fields in declaration order
        List<Field> capturedFields = new ArrayList<>();
        for (Field f : lambdaInstance.getClass().getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                capturedFields.add(f);
            }
        }
        List<Object> capturedValues = new ArrayList<>(capturedFields.size());
        List<String> capturedDescs = new ArrayList<>(capturedFields.size());
        for (Field f : capturedFields) {
            if (!f.trySetAccessible()) {
                return null;
            }
            try {
                capturedValues.add(f.get(lambdaInstance));
                capturedDescs.add(classToJvmDescriptor(f.getType()));
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return null;
            }
        }
        // Resolve the functional interface method descriptor from the lambda's declared methods
        String targetSuffixDesc = entryDescriptor;
        if (targetSuffixDesc == null) {
            for (java.lang.reflect.Method m : lambdaInstance.getClass().getDeclaredMethods()) {
                if (m.getName().equals(entryMethodName) && !m.isBridge() && !m.isSynthetic()) {
                    StringBuilder sb = new StringBuilder();
                    for (Class<?> pt : m.getParameterTypes()) {
                        sb.append(classToJvmDescriptor(pt));
                    }
                    sb.append(')').append(classToJvmDescriptor(m.getReturnType()));
                    targetSuffixDesc = sb.toString();
                    break;
                }
            }
        } else if (targetSuffixDesc.startsWith("(")) {
            targetSuffixDesc = targetSuffixDesc.substring(1);
        }
        if (targetSuffixDesc == null) {
            return null;
        }
        StringBuilder expectedPrefix = new StringBuilder("(");
        for (String cd : capturedDescs) {
            expectedPrefix.append(cd);
        }
        String expectedFullDesc = expectedPrefix.toString() + targetSuffixDesc;
        // Find matching synthetic lambda$ method in the enclosing host class
        List<RawMethod> matches = new ArrayList<>();
        for (RawMethod m : enclosingCls.methods) {
            if ((m.accessFlags() & ACC_STATIC) != 0
                    && m.name().startsWith("lambda$")
                    && m.descriptor().equals(expectedFullDesc)) {
                matches.add(m);
            }
        }
        if (matches.size() != 1) {
            return null;
        }
        RawMethod targetLambda = matches.get(0);
        ObjectInterner baseInterner = effOptions.objectInterner();
        final int capCount = capturedValues.size();
        ObjectInterner lambdaInterner = new ObjectInterner() {
            @Override
            public Integer internObject(Object instance) {
                return baseInterner.internObject(instance);
            }

            @Override
            public int nullObjectId() {
                return baseInterner.nullObjectId();
            }

            @Override
            public ContextParamBinding resolveContextGetter(
                    int entryParamIndex, String ownerInternalName, String methodName, String descriptor) {
                return baseInterner.resolveContextGetter(entryParamIndex, ownerInternalName, methodName, descriptor);
            }

            @Override
            public List<ContextParamBinding> contextParameters(int entryParamIndex) {
                return baseInterner.contextParameters(entryParamIndex);
            }

            @Override
            public Object fixedEntryParameterInstance(int entryParamIndex) {
                if (entryParamIndex >= 0 && entryParamIndex < capCount) {
                    return capturedValues.get(entryParamIndex);
                }
                return baseInterner.fixedEntryParameterInstance(entryParamIndex - capCount);
            }

            @Override
            public ContextParamBinding resolveContextField(
                    int entryParamIndex, String ownerInternalName, String fieldName, String descriptor) {
                return baseInterner.resolveContextField(entryParamIndex, ownerInternalName, fieldName, descriptor);
            }

            @Override
            public boolean isColdMemoContextField(
                    int entryParamIndex, String ownerInternalName, String fieldName, String descriptor) {
                return baseInterner.isColdMemoContextField(entryParamIndex, ownerInternalName, fieldName, descriptor);
            }

            @Override
            public Object resolveContextCallResult(
                    int entryParamIndex,
                    String ownerInternalName,
                    String methodName,
                    String descriptor,
                    Object[] constantArgs) {
                return baseInterner.resolveContextCallResult(
                        entryParamIndex, ownerInternalName, methodName, descriptor, constantArgs);
            }

            @Override
            public boolean isBoundContextInstance(Object instance) {
                return baseInterner.isBoundContextInstance(instance);
            }

            @Override
            public int[] matchingInternedIds(Object predicateOrCollection) {
                return baseInterner.matchingInternedIds(predicateOrCollection);
            }
        };
        GraphLiftOptions lambdaOpts = new GraphLiftOptions(
                effOptions.maxGraphDepth(),
                effOptions.maxInstances(),
                effOptions.maxLoopUnrollLength(),
                effOptions.maxHelperSpecializations(),
                lambdaInterner);
        SnapshotCollector collector = new SnapshotCollector(lambdaOpts);
        if (cl != null) {
            collector.loaders.add(cl);
        }
        collector.classBytesByName.put(enclosingInternal, enclosingBytes);
        for (Object cv : capturedValues) {
            if (cv != null) {
                collector.captureObject(cv, 0);
            }
        }
        InstanceSnapshot snapshot = new InstanceSnapshot(
                Collections.unmodifiableMap(collector.flatConstants),
                Collections.unmodifiableMap(collector.flatLuts),
                List.copyOf(collector.classBytesByName.values()),
                List.copyOf(collector.loaders),
                collector.liftedFields,
                List.of(-1),
                collector.instancesById,
                collector.liveObjectsById);
        return ClosedWorldGraphLifter.liftInstanceGraph(
                snapshot,
                enclosingBinary,
                targetLambda.name(),
                targetLambda.descriptor(),
                lambdaOpts);
    }

    private static String classToJvmDescriptor(Class<?> cls) {
        if (cls == boolean.class) return "Z";
        if (cls == byte.class) return "B";
        if (cls == char.class) return "C";
        if (cls == short.class) return "S";
        if (cls == int.class) return "I";
        if (cls == long.class) return "J";
        if (cls == float.class) return "F";
        if (cls == double.class) return "D";
        if (cls == void.class) return "V";
        if (cls.isArray()) return cls.getName().replace('.', '/');
        return "L" + cls.getName().replace('.', '/') + ";";
    }

    /**
     * Two-phase closed-world object-graph lifter: symbolically executes {@code factoryMethodName}
     * on {@code rootInstance} (e.g. {@code RuleSource#apply(Context)} or
     * {@code MaterialRule#compile(MaterialRuleContext)}) against a read-only context parameter to
     * materialize the evaluator closure without hidden-lambda classloading, then lifts
     * {@code evalMethodName} (e.g. {@code tryApply(int,int,int)}) into a single {@link ConceptModule}.
     */
    public static ConceptModule liftInstanceGraph(
            Object rootInstance,
            String factoryMethodName,
            String factoryDescriptor,
            String evalMethodName,
            String evalDescriptor,
            GraphLiftOptions options) {
        Objects.requireNonNull(rootInstance, "rootInstance");
        Objects.requireNonNull(factoryMethodName, "factoryMethodName");
        Objects.requireNonNull(evalMethodName, "evalMethodName");
        GraphLiftOptions effOptions = options != null ? options : GraphLiftOptions.defaults();
        InstanceSnapshot snapshot = captureInstanceSnapshot(rootInstance, effOptions);
        return ClosedWorldGraphLifter.liftInstanceGraphWithFactory(
                snapshot,
                rootInstance.getClass().getName(),
                factoryMethodName,
                factoryDescriptor,
                evalMethodName,
                evalDescriptor,
                effOptions);
    }

    /**
     * Lifts a target entry method and all reachable helper methods/lookup tables from the
     * supplied raw {@code .class} byte arrays into a {@link ConceptModule}.
     *
     * @param classBytesCollection raw {@code .class} byte arrays covering the entry and helper classes
     * @param entryClassInternalOrBinaryName entry class name (e.g. {@code net.leaf.treegen.common.ProceduralGenerator})
     * @param entryMethodName entry method name (e.g. {@code perlin}, {@code valueNoise1D}, {@code hashNoise})
     * @return lifted {@link ConceptModule} prior to whole-program inlining
     */
    public static ConceptModule liftModule(
            Collection<byte[]> classBytesCollection,
            String entryClassInternalOrBinaryName,
            String entryMethodName) {
        return liftModule(classBytesCollection, entryClassInternalOrBinaryName, entryMethodName, null, InstanceSnapshot.empty());
    }

    /**
     * Lifts a target entry method (optionally disambiguated by JVM descriptor) and all reachable
     * helper methods/lookup tables from the supplied raw {@code .class} byte arrays.
     */
    public static ConceptModule liftModule(
            Collection<byte[]> classBytesCollection,
            String entryClassInternalOrBinaryName,
            String entryMethodName,
            String entryDescriptorOrNull) {
        return liftModule(
                classBytesCollection,
                entryClassInternalOrBinaryName,
                entryMethodName,
                entryDescriptorOrNull,
                InstanceSnapshot.empty());
    }

    /**
     * Lifts a target entry method with an optional live {@link InstanceSnapshot} binding
     * instance fields, nested record constants, and array lookup tables.
     */
    public static ConceptModule liftModule(
            Collection<byte[]> classBytesCollection,
            String entryClassInternalOrBinaryName,
            String entryMethodName,
            String entryDescriptorOrNull,
            InstanceSnapshot snapshot) {
        Objects.requireNonNull(classBytesCollection, "classBytesCollection");
        Objects.requireNonNull(entryClassInternalOrBinaryName, "entryClassInternalOrBinaryName");
        Objects.requireNonNull(entryMethodName, "entryMethodName");
        InstanceSnapshot activeSnapshot = snapshot != null ? snapshot : InstanceSnapshot.empty();

        String normalizedEntryClass = entryClassInternalOrBinaryName.replace('.', '/');
        Map<String, RawClassFile> classesByName = new LinkedHashMap<>();
        Map<String, ConceptLut> discoveredLuts = new LinkedHashMap<>(activeSnapshot.instanceLuts());
        Map<String, ConceptExpr> discoveredConstants = new LinkedHashMap<>(activeSnapshot.fieldConstants());
        Set<ClassLoader> activeLoaders = new LinkedHashSet<>(activeSnapshot.classLoaders());
        ClassLoader ctxLoader = Thread.currentThread().getContextClassLoader();
        if (ctxLoader != null) {
            activeLoaders.add(ctxLoader);
        }
        ClassLoader selfLoader = BytecodeSsaLifter.class.getClassLoader();
        if (selfLoader != null) {
            activeLoaders.add(selfLoader);
        }

        List<byte[]> allBytes = new ArrayList<>(classBytesCollection);
        allBytes.addAll(activeSnapshot.discoveredClassBytes());

        for (byte[] bytes : allBytes) {
            RawClassFile parsed = parseClassBytes(bytes);
            if (parsed != null) {
                registerParsedClass(parsed, classesByName, discoveredLuts, discoveredConstants);
            }
        }

        // Eagerly resolve the entry class and non-system superclasses on the active ClassLoaders.
        ensureClassLoaded(
                normalizedEntryClass, classesByName, discoveredLuts, discoveredConstants, activeLoaders);
        List<RawClassFile> initialClasses = new ArrayList<>(classesByName.values());
        for (RawClassFile cls : initialClasses) {
            String sup = cls.superClass;
            int depth = 0;
            while (sup != null && !isSystemOwner(sup) && !classesByName.containsKey(sup) && depth++ < 8) {
                RawClassFile supParsed = ensureClassLoaded(
                        sup, classesByName, discoveredLuts, discoveredConstants, activeLoaders);
                if (supParsed == null) {
                    break;
                }
                sup = supParsed.superClass;
            }
        }

        // Ensure every class with an array field ([B, [I) has a default 512-entry permutation LUT if none was provided.
        populateFallbackPermutationLuts(classesByName.values(), discoveredLuts);

        RawClassFile entryClass = findClassResilient(
                classesByName, normalizedEntryClass, entryMethodName, entryDescriptorOrNull);
        if (entryClass == null) {
            throw new IllegalArgumentException(
                    "Entry class not found in supplied .class bytes: " + entryClassInternalOrBinaryName);
        }

        RawMethodRef entryResolved = findMethodResilient(
                classesByName, entryClass, entryMethodName, entryDescriptorOrNull);
        if (entryResolved == null) {
            throw new IllegalArgumentException(
                    "Entry method not found: " + entryClass.thisClass + "#" + entryMethodName);
        }
        entryClass = entryResolved.owner;
        RawMethod entryRaw = entryResolved.method;

        Map<String, ConceptFunction> liftedByKey = new LinkedHashMap<>();
        Set<String> referencedLutSymbols = new LinkedHashSet<>();
        Deque<RawMethodRef> worklist = new ArrayDeque<>();
        worklist.add(new RawMethodRef(entryClass, entryRaw));

        ConceptFunction entryFunction = null;
        while (!worklist.isEmpty()) {
            RawMethodRef current = worklist.removeFirst();
            String key = current.owner.thisClass + "#" + current.method.name + current.method.descriptor;
            if (liftedByKey.containsKey(key)) {
                continue;
            }
            LiftOutcome outcome = liftMethod(
                    current.owner,
                    current.method,
                    classesByName,
                    discoveredLuts,
                    discoveredConstants,
                    activeLoaders);
            liftedByKey.put(key, outcome.function);
            referencedLutSymbols.addAll(outcome.referencedLuts);
            if (entryFunction == null) {
                entryFunction = outcome.function;
            }
            for (CallTargetRef callTarget : outcome.calledTargets) {
                RawClassFile targetClass = ensureClassLoaded(
                        callTarget.ownerClass,
                        classesByName,
                        discoveredLuts,
                        discoveredConstants,
                        activeLoaders);
                RawMethod targetMethod = targetClass != null
                        ? findMethodInHierarchy(classesByName, targetClass, callTarget.methodName, callTarget.descriptor)
                        : null;
                if (targetMethod != null && targetClass != null) {
                    RawMethodRef ownerAndMethod = findMethodOwnerInHierarchy(
                            classesByName, targetClass, callTarget.methodName, callTarget.descriptor);
                    if (ownerAndMethod != null) {
                        targetClass = ownerAndMethod.owner;
                        targetMethod = ownerAndMethod.method;
                    }
                }
                if (targetMethod == null || targetMethod.code == null) {
                    for (RawClassFile candidateCls : classesByName.values()) {
                        RawMethod candidateM = findMethod(candidateCls, callTarget.methodName, callTarget.descriptor);
                        if (candidateM != null && candidateM.code != null) {
                            targetClass = candidateCls;
                            targetMethod = candidateM;
                            break;
                        }
                    }
                }
                if (targetClass != null && targetMethod != null && targetMethod.code != null) {
                    String targetKey = targetClass.thisClass + "#" + targetMethod.name + targetMethod.descriptor;
                    if (!liftedByKey.containsKey(targetKey)) {
                        worklist.addLast(new RawMethodRef(targetClass, targetMethod));
                    }
                }
            }
        }

        List<ConceptFunction> helpers = new ArrayList<>();
        for (ConceptFunction fn : liftedByKey.values()) {
            if (fn != entryFunction) {
                helpers.add(fn);
            }
        }

        List<ConceptLut> activeLuts = new ArrayList<>();
        for (String lutSym : referencedLutSymbols) {
            for (ConceptLut lut : discoveredLuts.values()) {
                if (lut.symbolName().equals(lutSym)
                        && activeLuts.stream().noneMatch(e -> e.symbolName().equals(lutSym))) {
                    activeLuts.add(lut);
                    break;
                }
            }
        }

        String simpleClass = simpleClassName(entryClass.thisClass);
        return new ConceptModule(
                simpleClass + "_" + entryRaw.name,
                activeLuts,
                helpers,
                Objects.requireNonNull(entryFunction),
                null);
    }

    record RawMethodRef(RawClassFile owner, RawMethod method) {
    }

    private record CallTargetRef(String ownerClass, String methodName, String descriptor) {
    }

    private record LiftOutcome(
            ConceptFunction function,
            Set<String> referencedLuts,
            Set<CallTargetRef> calledTargets
    ) {
    }

    static RawMethod findMethod(RawClassFile cls, String name, String descriptorOrNull) {
        RawMethod firstMatch = null;
        for (RawMethod m : cls.methods) {
            if (m.name.equals(name) && (descriptorOrNull == null || m.descriptor.equals(descriptorOrNull))) {
                if (descriptorOrNull != null) {
                    return m;
                }
                List<ConceptType> pTypes = parseParameterTypes(m.descriptor);
                if (pTypes.size() == 3 || pTypes.size() == 2) {
                    return m;
                }
                if (firstMatch == null) {
                    firstMatch = m;
                }
            }
        }
        return firstMatch;
    }

    static RawMethodRef findMethodOwnerInHierarchy(
            Map<String, RawClassFile> classesByName,
            RawClassFile startCls,
            String name,
            String descriptorOrNull) {
        RawClassFile cur = startCls;
        int depth = 0;
        while (cur != null && depth++ < 16) {
            RawMethod m = findMethod(cur, name, descriptorOrNull);
            if (m != null && m.code != null) {
                return new RawMethodRef(cur, m);
            }
            cur = cur.superClass != null ? classesByName.get(cur.superClass) : null;
        }
        return findInterfaceDefaultMethod(classesByName, startCls, name, descriptorOrNull, true);
    }

    static RawMethodRef findAnyMethodOwnerInHierarchy(
            Map<String, RawClassFile> classesByName,
            RawClassFile startCls,
            String name,
            String descriptorOrNull) {
        RawClassFile cur = startCls;
        int depth = 0;
        RawMethodRef fallbackAbstract = null;
        while (cur != null && depth++ < 16) {
            RawMethod m = findMethod(cur, name, descriptorOrNull);
            if (m != null) {
                if (m.code != null || (m.accessFlags & ACC_NATIVE) != 0) {
                    return new RawMethodRef(cur, m);
                }
                if (fallbackAbstract == null) {
                    fallbackAbstract = new RawMethodRef(cur, m);
                }
            }
            cur = cur.superClass != null ? classesByName.get(cur.superClass) : null;
        }
        RawMethodRef ifaceCode = findInterfaceDefaultMethod(classesByName, startCls, name, descriptorOrNull, true);
        if (ifaceCode != null) {
            return ifaceCode;
        }
        if (fallbackAbstract != null) {
            return fallbackAbstract;
        }
        return findInterfaceDefaultMethod(classesByName, startCls, name, descriptorOrNull, false);
    }

    private static RawMethodRef findInterfaceDefaultMethod(
            Map<String, RawClassFile> classesByName,
            RawClassFile startCls,
            String name,
            String descriptorOrNull,
            boolean requireCode) {
        if (startCls == null) {
            return null;
        }
        Deque<String> ifaceQueue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        RawClassFile cur = startCls;
        int depth = 0;
        while (cur != null && depth++ < 16) {
            for (String iface : cur.interfaces) {
                if (visited.add(iface)) {
                    ifaceQueue.addLast(iface);
                }
            }
            cur = cur.superClass != null ? classesByName.get(cur.superClass) : null;
        }
        while (!ifaceQueue.isEmpty()) {
            String ifaceName = ifaceQueue.removeFirst();
            RawClassFile ifaceCls = classesByName.get(ifaceName);
            if (ifaceCls == null) {
                continue;
            }
            RawMethod m = findMethod(ifaceCls, name, descriptorOrNull);
            if (m != null && (!requireCode || m.code != null)) {
                return new RawMethodRef(ifaceCls, m);
            }
            for (String superIface : ifaceCls.interfaces) {
                if (visited.add(superIface)) {
                    ifaceQueue.addLast(superIface);
                }
            }
        }
        return null;
    }

    private static RawMethod findMethodInHierarchy(
            Map<String, RawClassFile> classesByName,
            RawClassFile startCls,
            String name,
            String descriptorOrNull) {
        RawMethodRef ref = findMethodOwnerInHierarchy(classesByName, startCls, name, descriptorOrNull);
        return ref != null ? ref.method : null;
    }

    private static RawClassFile findClassResilient(
            Map<String, RawClassFile> classesByName,
            String normalizedEntryClass,
            String entryMethodName,
            String entryDescriptorOrNull) {
        RawClassFile direct = classesByName.get(normalizedEntryClass);
        if (direct != null) {
            return direct;
        }
        String simpleTarget = simpleClassName(normalizedEntryClass);
        for (RawClassFile candidate : classesByName.values()) {
            if (candidate.thisClass.endsWith("/" + normalizedEntryClass)
                    || candidate.thisClass.equals(normalizedEntryClass)
                    || simpleClassName(candidate.thisClass).equals(simpleTarget)) {
                return candidate;
            }
        }
        // Cross-version / cross-mapping structural fingerprint fallback:
        // e.g. ImprovedNoise (1.20.1..26.2) <-> PerlinNoise extends GradientNoise (26.3+) <-> class_3756 (Intermediary)
        RawClassFile bestCandidate = null;
        int bestScore = 0;
        for (RawClassFile candidate : classesByName.values()) {
            int score = scoreWorldgenClassCandidate(
                    classesByName, candidate, normalizedEntryClass, entryMethodName, entryDescriptorOrNull);
            if (score > bestScore) {
                bestScore = score;
                bestCandidate = candidate;
            }
        }
        return bestScore >= 20 ? bestCandidate : null;
    }

    private static int scoreWorldgenClassCandidate(
            Map<String, RawClassFile> classesByName,
            RawClassFile candidate,
            String requestedClass,
            String requestedMethod,
            String requestedDesc) {
        int score = 0;
        if (findMethodOwnerInHierarchy(classesByName, candidate, requestedMethod, requestedDesc) != null) {
            score += 50;
        }
        boolean has3dSampler = false;
        boolean hasSampleAndLerp = false;
        for (RawMethod m : candidate.methods) {
            if (m.code == null || m.name.startsWith("<")) {
                continue;
            }
            if ("(DDD)D".equals(m.descriptor) || "(DDD)F".equals(m.descriptor)) {
                has3dSampler = true;
            }
            if ("(IIIDDDD)D".equals(m.descriptor) || "(IIIFFFF)F".equals(m.descriptor)) {
                hasSampleAndLerp = true;
            }
        }
        if (has3dSampler) {
            score += 20;
        }
        if (hasSampleAndLerp) {
            score += 25;
        }
        int doubleFields = 0;
        boolean hasPermArray = false;
        RawClassFile cur = candidate;
        int depth = 0;
        while (cur != null && depth++ < 4) {
            for (FieldRef f : cur.declaredFields) {
                if ("D".equals(f.descriptor)) {
                    doubleFields++;
                } else if ("[B".equals(f.descriptor) || "[I".equals(f.descriptor)) {
                    hasPermArray = true;
                }
            }
            cur = cur.superClass != null ? classesByName.get(cur.superClass) : null;
        }
        if (hasPermArray) {
            score += 15;
        }
        if (doubleFields >= 3) {
            score += 15;
        }
        if (requestedClass.contains("ImprovedNoise") && candidate.thisClass.endsWith("/PerlinNoise") && hasSampleAndLerp) {
            score += 20;
        }
        return score;
    }

    private static RawMethodRef findMethodResilient(
            Map<String, RawClassFile> classesByName,
            RawClassFile entryClass,
            String entryMethodName,
            String entryDescriptorOrNull) {
        RawMethodRef exact = findMethodOwnerInHierarchy(
                classesByName, entryClass, entryMethodName, entryDescriptorOrNull);
        if (exact != null) {
            return exact;
        }
        if (entryDescriptorOrNull != null) {
            // Try exact name with F32/F64 return-type dual (e.g. (DDD)D <-> (DDD)F across MC 26.2 -> 26.3)
            String dualDesc = swapReturnPrecisionDescriptor(entryDescriptorOrNull);
            if (dualDesc != null) {
                RawMethodRef dualExact = findMethodOwnerInHierarchy(
                        classesByName, entryClass, entryMethodName, dualDesc);
                if (dualExact != null) {
                    return dualExact;
                }
            }
        }
        // Method rename / obfuscation fallback: match by structural descriptor in hierarchy
        List<String> candidateDescs = new ArrayList<>();
        if (entryDescriptorOrNull != null) {
            candidateDescs.add(entryDescriptorOrNull);
            String dual = swapReturnPrecisionDescriptor(entryDescriptorOrNull);
            if (dual != null) {
                candidateDescs.add(dual);
            }
            if ("(DDD)D".equals(entryDescriptorOrNull) || "(DDD)F".equals(entryDescriptorOrNull)) {
                candidateDescs.add("(JDDD)D");
                candidateDescs.add("(IDDD)D");
                candidateDescs.add("(IDDD)F");
                candidateDescs.add("(III)D");
            }
        } else if (entryMethodName.toLowerCase(java.util.Locale.ROOT).contains("hash")) {
            candidateDescs.add("(JJ)D");
            candidateDescs.add("(JJ)J");
        } else {
            candidateDescs.add("(DDD)D");
            candidateDescs.add("(DDD)F");
            candidateDescs.add("(JDDD)D");
            candidateDescs.add("(IDDD)D");
            candidateDescs.add("(IDDD)F");
            candidateDescs.add("(III)D");
        }
        for (String altDesc : candidateDescs) {
            RawMethodRef byNameAndAltDesc = findMethodOwnerInHierarchy(
                    classesByName, entryClass, entryMethodName, altDesc);
            if (byNameAndAltDesc != null) {
                return byNameAndAltDesc;
            }
        }

        RawClassFile cur = entryClass;
        int depth = 0;
        while (cur != null && depth++ < 6) {
            for (String desc : candidateDescs) {
                for (RawMethod m : cur.methods) {
                    if (m.code != null && !m.name.startsWith("<") && m.descriptor.equals(desc)) {
                        return new RawMethodRef(cur, m);
                    }
                }
            }
            cur = cur.superClass != null ? classesByName.get(cur.superClass) : null;
        }
        return null;
    }

    private static String swapReturnPrecisionDescriptor(String desc) {
        if (desc == null) {
            return null;
        }
        if (desc.endsWith(")D")) {
            return desc.substring(0, desc.length() - 1) + "F";
        }
        if (desc.endsWith(")F")) {
            return desc.substring(0, desc.length() - 1) + "D";
        }
        return null;
    }

    static String simpleClassName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        String base = slash >= 0 ? internalName.substring(slash + 1) : internalName;
        return base.replace('$', '_');
    }

    private static String lutSymbol(String ownerInternalName, String fieldName) {
        return simpleClassName(ownerInternalName) + "_" + fieldName;
    }

    // -------------------------------------------------------------------------
    // Static Initializer (<clinit>) LUT Evaluator
    // -------------------------------------------------------------------------

    private static void pushObjSafe(Deque<Object> stack, Object val) {
        stack.push(val != null ? val : NULL_SENTINEL);
    }

    private static Object popObjSafe(Deque<Object> stack) {
        return stack.isEmpty() ? NULL_SENTINEL : stack.pop();
    }

    static Map<String, ConceptLut> extractStaticLuts(
            RawClassFile cls,
            Map<String, ConceptExpr> discoveredConstants) {
        RawMethod clinit = findMethod(cls, "<clinit>", "()V");
        if (clinit == null || clinit.code == null) {
            return Map.of();
        }
        byte[] code = clinit.code;
        Object[] locals = new Object[Math.max(16, clinit.maxLocals + 4)];
        Deque<Object> stack = new ArrayDeque<>();
        Map<String, Object> staticFields = new LinkedHashMap<>();

        int pc = 0;
        int steps = 0;
        while (pc >= 0 && pc < code.length && steps++ < 100_000) {
            int opcode = code[pc] & 0xFF;
            int nextPc = pc + instructionLength(code, pc);
            switch (opcode) {
                case 0x00, 0x01 -> {
                    if (opcode == 0x01) {
                        pushObjSafe(stack, NULL_SENTINEL);
                    }
                }
                case 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 -> pushObjSafe(stack, opcode - 0x03);
                case 0x09, 0x0A -> pushObjSafe(stack, (long) (opcode - 0x09));
                case 0x0B, 0x0C, 0x0D -> pushObjSafe(stack, (float) (opcode - 0x0B));
                case 0x0E, 0x0F -> pushObjSafe(stack, (double) (opcode - 0x0E));
                case 0x10 -> pushObjSafe(stack, (int) code[pc + 1]);
                case 0x11 -> pushObjSafe(stack, (int) readSignedShort(code, pc + 1));
                case 0x12 -> pushObjSafe(stack, resolveConstantValue(cls, code[pc + 1] & 0xFF));
                case 0x13, 0x14 -> pushObjSafe(stack, resolveConstantValue(cls, readUnsignedShort(code, pc + 1)));
                case 0x15, 0x16, 0x17, 0x18, 0x19 -> {
                    int idx = code[pc + 1] & 0xFF;
                    pushObjSafe(stack, idx < locals.length ? locals[idx] : NULL_SENTINEL);
                }
                case 0x1A, 0x1B, 0x1C, 0x1D -> pushObjSafe(stack, locals[opcode - 0x1A]);
                case 0x1E, 0x1F, 0x20, 0x21 -> pushObjSafe(stack, locals[opcode - 0x1E]);
                case 0x22, 0x23, 0x24, 0x25 -> pushObjSafe(stack, locals[opcode - 0x22]);
                case 0x26, 0x27, 0x28, 0x29 -> pushObjSafe(stack, locals[opcode - 0x26]);
                case 0x2A, 0x2B, 0x2C, 0x2D -> pushObjSafe(stack, locals[opcode - 0x2A]);
                case 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35 -> {
                    int idx = toInt(popObjSafe(stack));
                    Object arr = popObjSafe(stack);
                    if (arr instanceof int[] ia && idx >= 0 && idx < ia.length) {
                        pushObjSafe(stack, ia[idx]);
                    } else if (arr instanceof byte[] ba && idx >= 0 && idx < ba.length) {
                        pushObjSafe(stack, (int) ba[idx]);
                    } else if (arr instanceof short[] sa && idx >= 0 && idx < sa.length) {
                        pushObjSafe(stack, (int) sa[idx]);
                    } else if (arr instanceof long[] la && idx >= 0 && idx < la.length) {
                        pushObjSafe(stack, la[idx]);
                    } else if (arr instanceof float[] fa && idx >= 0 && idx < fa.length) {
                        pushObjSafe(stack, fa[idx]);
                    } else if (arr instanceof double[] da && idx >= 0 && idx < da.length) {
                        pushObjSafe(stack, da[idx]);
                    } else if (arr instanceof Object[] oa && idx >= 0 && idx < oa.length) {
                        pushObjSafe(stack, oa[idx]);
                    } else {
                        pushObjSafe(stack, 0);
                    }
                }
                case 0x36, 0x37, 0x38, 0x39, 0x3A -> {
                    int idx = code[pc + 1] & 0xFF;
                    if (idx < locals.length) {
                        locals[idx] = popObjSafe(stack);
                    } else {
                        popObjSafe(stack);
                    }
                }
                case 0x3B, 0x3C, 0x3D, 0x3E -> locals[opcode - 0x3B] = popObjSafe(stack);
                case 0x3F, 0x40, 0x41, 0x42 -> locals[opcode - 0x3F] = popObjSafe(stack);
                case 0x43, 0x44, 0x45, 0x46 -> locals[opcode - 0x43] = popObjSafe(stack);
                case 0x47, 0x48, 0x49, 0x4A -> locals[opcode - 0x47] = popObjSafe(stack);
                case 0x4B, 0x4C, 0x4D, 0x4E -> locals[opcode - 0x4B] = popObjSafe(stack);
                case 0x4F, 0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56 -> {
                    Object valObj = popObjSafe(stack);
                    int idx = toInt(popObjSafe(stack));
                    Object arr = popObjSafe(stack);
                    if (arr instanceof int[] ia && idx >= 0 && idx < ia.length) {
                        ia[idx] = toInt(valObj);
                    } else if (arr instanceof byte[] ba && idx >= 0 && idx < ba.length) {
                        ba[idx] = (byte) toInt(valObj);
                    } else if (arr instanceof short[] sa && idx >= 0 && idx < sa.length) {
                        sa[idx] = (short) toInt(valObj);
                    } else if (arr instanceof long[] la && idx >= 0 && idx < la.length && valObj instanceof Number n) {
                        la[idx] = n.longValue();
                    } else if (arr instanceof float[] fa && idx >= 0 && idx < fa.length && valObj instanceof Number n) {
                        fa[idx] = n.floatValue();
                    } else if (arr instanceof double[] da && idx >= 0 && idx < da.length && valObj instanceof Number n) {
                        da[idx] = n.doubleValue();
                    } else if (arr instanceof Object[] oa && idx >= 0 && idx < oa.length) {
                        oa[idx] = valObj;
                    }
                }
                case 0x57, 0x58 -> popObjSafe(stack);
                case 0x59, 0x5C -> pushObjSafe(stack, stack.isEmpty() ? NULL_SENTINEL : stack.peek());
                case 0x5A -> {
                    Object v1 = popObjSafe(stack);
                    Object v2 = popObjSafe(stack);
                    pushObjSafe(stack, v1);
                    pushObjSafe(stack, v2);
                    pushObjSafe(stack, v1);
                }
                case 0x5B -> {
                    Object v1 = popObjSafe(stack);
                    Object v2 = popObjSafe(stack);
                    Object v3 = popObjSafe(stack);
                    pushObjSafe(stack, v1);
                    pushObjSafe(stack, v3);
                    pushObjSafe(stack, v2);
                    pushObjSafe(stack, v1);
                }
                case 0x60 -> {
                    int r = toInt(popObjSafe(stack));
                    int l = toInt(popObjSafe(stack));
                    pushObjSafe(stack, l + r);
                }
                case 0x62, 0x63 -> {
                    double r = toDouble(popObjSafe(stack));
                    double l = toDouble(popObjSafe(stack));
                    pushObjSafe(stack, opcode == 0x62 ? (float) (l + r) : (l + r));
                }
                case 0x64 -> {
                    int r = toInt(popObjSafe(stack));
                    int l = toInt(popObjSafe(stack));
                    pushObjSafe(stack, l - r);
                }
                case 0x66, 0x67 -> {
                    double r = toDouble(popObjSafe(stack));
                    double l = toDouble(popObjSafe(stack));
                    pushObjSafe(stack, opcode == 0x66 ? (float) (l - r) : (l - r));
                }
                case 0x68 -> {
                    int r = toInt(popObjSafe(stack));
                    int l = toInt(popObjSafe(stack));
                    pushObjSafe(stack, l * r);
                }
                case 0x6A, 0x6B -> {
                    double r = toDouble(popObjSafe(stack));
                    double l = toDouble(popObjSafe(stack));
                    pushObjSafe(stack, opcode == 0x6A ? (float) (l * r) : (l * r));
                }
                case 0x6E, 0x6F -> {
                    double r = toDouble(popObjSafe(stack));
                    double l = toDouble(popObjSafe(stack));
                    pushObjSafe(stack, opcode == 0x6E ? (float) (l / r) : (l / r));
                }
                case 0x74 -> pushObjSafe(stack, -toInt(popObjSafe(stack)));
                case 0x76 -> pushObjSafe(stack, (float) -toDouble(popObjSafe(stack)));
                case 0x77 -> pushObjSafe(stack, -toDouble(popObjSafe(stack)));
                case 0x7E -> {
                    int r = toInt(popObjSafe(stack));
                    int l = toInt(popObjSafe(stack));
                    pushObjSafe(stack, l & r);
                }
                case 0x84 -> {
                    int slot = code[pc + 1] & 0xFF;
                    int delta = code[pc + 2];
                    if (slot < locals.length) {
                        locals[slot] = toInt(locals[slot]) + delta;
                    }
                }
                case 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E -> {
                    int v = toInt(popObjSafe(stack));
                    int target = pc + readSignedShort(code, pc + 1);
                    boolean take = switch (opcode) {
                        case 0x99 -> v == 0;
                        case 0x9A -> v != 0;
                        case 0x9B -> v < 0;
                        case 0x9C -> v >= 0;
                        case 0x9D -> v > 0;
                        default -> v <= 0;
                    };
                    if (take) {
                        nextPc = target;
                    }
                }
                case 0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4 -> {
                    int r = toInt(popObjSafe(stack));
                    int l = toInt(popObjSafe(stack));
                    int target = pc + readSignedShort(code, pc + 1);
                    boolean take = switch (opcode) {
                        case 0x9F -> l == r;
                        case 0xA0 -> l != r;
                        case 0xA1 -> l < r;
                        case 0xA2 -> l >= r;
                        case 0xA3 -> l > r;
                        default -> l <= r;
                    };
                    if (take) {
                        nextPc = target;
                    }
                }
                case 0xA7 -> nextPc = pc + readSignedShort(code, pc + 1);
                case 0xB1 -> {
                    pc = code.length;
                    continue;
                }
                case 0xB2 -> {
                    FieldRef ref = cls.resolveFieldRef(readUnsignedShort(code, pc + 1));
                    Object sf = staticFields.get(ref.owner + "#" + ref.name);
                    if (sf == null && discoveredConstants != null) {
                        ConceptExpr ce = discoveredConstants.get(ref.owner + "#" + ref.name);
                        if (ce instanceof ConceptExpr.IntConst ic) {
                            sf = ic.value();
                        }
                    }
                    pushObjSafe(stack, sf);
                }
                case 0xB3 -> {
                    FieldRef ref = cls.resolveFieldRef(readUnsignedShort(code, pc + 1));
                    Object val = popObjSafe(stack);
                    if (val != NULL_SENTINEL) {
                        staticFields.put(ref.owner + "#" + ref.name, val);
                        ConceptExpr ce = primitiveToConstExpr(val);
                        if (ce != null && discoveredConstants != null) {
                            discoveredConstants.put(ref.owner + "#" + ref.name, ce);
                            discoveredConstants.put(ref.name, ce);
                        }
                    }
                }
                case 0xB6 -> {
                    MethodRef ref = cls.resolveMethodRef(readUnsignedShort(code, pc + 1));
                    List<ConceptType> argTypes = parseParameterTypes(ref.descriptor);
                    for (int i = 0; i < argTypes.size(); i++) {
                        popObjSafe(stack);
                    }
                    Object recv = popObjSafe(stack);
                    if ("ordinal".equals(ref.name) && "()I".equals(ref.descriptor)) {
                        pushObjSafe(stack, toInt(recv));
                    } else if (parseReturnType(ref.descriptor) != ConceptType.VOID) {
                        pushObjSafe(stack, 0);
                    }
                }
                case 0xB7 -> {
                    MethodRef ref = cls.resolveMethodRef(readUnsignedShort(code, pc + 1));
                    List<ConceptType> argTypes = parseParameterTypes(ref.descriptor);
                    List<Object> args = new ArrayList<>(argTypes.size());
                    for (int i = 0; i < argTypes.size(); i++) {
                        args.add(popObjSafe(stack));
                    }
                    Collections.reverse(args);
                    Object recv = popObjSafe(stack);
                    if ("<init>".equals(ref.name) && recv instanceof List<?> rawList) {
                        @SuppressWarnings("unchecked")
                        List<Object> tuple = (List<Object>) rawList;
                        tuple.addAll(args);
                    }
                }
                case 0xB8 -> {
                    MethodRef ref = cls.resolveMethodRef(readUnsignedShort(code, pc + 1));
                    List<ConceptType> argTypes = parseParameterTypes(ref.descriptor);
                    List<Object> args = new ArrayList<>(argTypes.size());
                    for (int i = 0; i < argTypes.size(); i++) {
                        args.add(popObjSafe(stack));
                    }
                    Collections.reverse(args);
                    ConceptType retType = parseReturnType(ref.descriptor);
                    if (("java/lang/Math".equals(ref.owner) || "java/lang/StrictMath".equals(ref.owner))
                            && !args.isEmpty()) {
                        double a0 = toDouble(args.get(0));
                        Object eval = switch (ref.name) {
                            case "sqrt" -> Math.sqrt(a0);
                            case "nextDown" -> retType == ConceptType.F32 ? Math.nextDown((float) a0) : Math.nextDown(a0);
                            case "nextUp" -> retType == ConceptType.F32 ? Math.nextUp((float) a0) : Math.nextUp(a0);
                            case "floor" -> Math.floor(a0);
                            case "ceil" -> Math.ceil(a0);
                            case "abs" -> Math.abs(a0);
                            case "sin" -> Math.sin(a0);
                            case "cos" -> Math.cos(a0);
                            default -> 0.0;
                        };
                        pushObjSafe(stack, eval);
                    } else if ("values".equals(ref.name) && ref.descriptor.startsWith("()[L")) {
                        int enumCount = 0;
                        if (discoveredConstants != null) {
                            String prefix = ref.owner + "#";
                            for (String k : discoveredConstants.keySet()) {
                                if (k.startsWith(prefix)) {
                                    enumCount++;
                                }
                            }
                        }
                        pushObjSafe(stack, new Object[Math.max(1, enumCount)]);
                    } else if (retType != ConceptType.VOID) {
                        pushObjSafe(stack, 0);
                    }
                }
                case 0xBB -> pushObjSafe(stack, new ArrayList<Object>(4));
                case 0xBC -> {
                    int atype = code[pc + 1] & 0xFF;
                    int len = toInt(popObjSafe(stack));
                    if (len >= 0 && len <= 65536) {
                        Object arr = switch (atype) {
                            case 6 -> new float[len];
                            case 7 -> new double[len];
                            case 8 -> new byte[len];
                            case 9 -> new short[len];
                            case 10 -> new int[len];
                            case 11 -> new long[len];
                            default -> null;
                        };
                        if (arr != null) {
                            pushObjSafe(stack, arr);
                        } else {
                            return buildExtractedLuts(cls, staticFields);
                        }
                    } else {
                        return buildExtractedLuts(cls, staticFields);
                    }
                }
                case 0xBD -> {
                    int len = toInt(popObjSafe(stack));
                    if (len >= 0 && len <= 4096) {
                        pushObjSafe(stack, new Object[len]);
                    } else {
                        return buildExtractedLuts(cls, staticFields);
                    }
                }
                case 0xC5 -> {
                    int dims = code[pc + 3] & 0xFF;
                    if (dims == 2) {
                        int d2 = toInt(popObjSafe(stack));
                        int d1 = toInt(popObjSafe(stack));
                        if (d1 >= 0 && d1 <= 4096 && d2 >= 0 && d2 <= 4096) {
                            pushObjSafe(stack, new int[d1][d2]);
                        } else {
                            return buildExtractedLuts(cls, staticFields);
                        }
                    } else {
                        return buildExtractedLuts(cls, staticFields);
                    }
                }
                case 0xBE -> {
                    Object arr = popObjSafe(stack);
                    if (arr instanceof int[] ia) {
                        pushObjSafe(stack, ia.length);
                    } else if (arr instanceof byte[] ba) {
                        pushObjSafe(stack, ba.length);
                    } else if (arr instanceof short[] sa) {
                        pushObjSafe(stack, sa.length);
                    } else if (arr instanceof long[] la) {
                        pushObjSafe(stack, la.length);
                    } else if (arr instanceof float[] fa) {
                        pushObjSafe(stack, fa.length);
                    } else if (arr instanceof double[] da) {
                        pushObjSafe(stack, da.length);
                    } else if (arr instanceof Object[] oa) {
                        pushObjSafe(stack, oa.length);
                    } else {
                        return buildExtractedLuts(cls, staticFields);
                    }
                }
                default -> {
                    return buildExtractedLuts(cls, staticFields);
                }
            }
            pc = nextPc;
        }
        return buildExtractedLuts(cls, staticFields);
    }

    private static Map<String, ConceptLut> buildExtractedLuts(
            RawClassFile cls,
            Map<String, Object> staticFields) {
        Map<String, ConceptLut> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : staticFields.entrySet()) {
            int hash = entry.getKey().indexOf('#');
            if (hash <= 0) {
                continue;
            }
            String owner = entry.getKey().substring(0, hash);
            String fieldName = entry.getKey().substring(hash + 1);
            ConceptLut lut = arrayToConceptLut(owner, fieldName, entry.getValue());
            if (lut != null) {
                result.put(entry.getKey(), lut);
                result.putIfAbsent(fieldName, lut);
            }
        }
        return result;
    }

    private static void extractConstructorFieldConstants(
            RawClassFile cls,
            Map<String, ConceptExpr> discoveredConstants,
            Map<String, ConceptLut> discoveredLuts) {
        for (RawMethod m : cls.methods) {
            if (!"<init>".equals(m.name) || m.code == null) {
                continue;
            }
            byte[] code = m.code;
            Deque<Object> stack = new ArrayDeque<>();
            int pc = 0;
            int steps = 0;
            while (pc >= 0 && pc < code.length && steps++ < 4096) {
                int opcode = code[pc] & 0xFF;
                int len = instructionLength(code, pc);
                switch (opcode) {
                    case 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 -> pushObjSafe(stack, opcode - 0x03);
                    case 0x09, 0x0A -> pushObjSafe(stack, (long) (opcode - 0x09));
                    case 0x0B, 0x0C, 0x0D -> pushObjSafe(stack, (float) (opcode - 0x0B));
                    case 0x0E, 0x0F -> pushObjSafe(stack, (double) (opcode - 0x0E));
                    case 0x10 -> pushObjSafe(stack, (int) code[pc + 1]);
                    case 0x11 -> pushObjSafe(stack, (int) readSignedShort(code, pc + 1));
                    case 0x12 -> pushObjSafe(stack, resolveConstantValue(cls, code[pc + 1] & 0xFF));
                    case 0x13, 0x14 -> pushObjSafe(stack, resolveConstantValue(cls, readUnsignedShort(code, pc + 1)));
                    case 0x2A -> pushObjSafe(stack, "this");
                    case 0xB5 -> {
                        FieldRef ref = cls.resolveFieldRef(readUnsignedShort(code, pc + 1));
                        Object val = popObjSafe(stack);
                        popObjSafe(stack); // receiver
                        ConceptExpr ce = primitiveToConstExpr(val);
                        if (ce != null) {
                            discoveredConstants.putIfAbsent(ref.owner + "#" + ref.name, ce);
                            discoveredConstants.putIfAbsent(ref.name, ce);
                        }
                    }
                    default -> stack.clear();
                }
                pc += Math.max(1, len);
            }
        }
    }

    private static int toInt(Object obj) {
        if (obj instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    private static double toDouble(Object obj) {
        if (obj instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }

    // -------------------------------------------------------------------------
    // Method Bytecode -> Basic Block CFG -> Stackless SSA + Structured AST
    // -------------------------------------------------------------------------

    static void registerParsedClassForGraph(
            RawClassFile parsed,
            Map<String, RawClassFile> classesByName,
            Map<String, ConceptLut> discoveredLuts,
            Map<String, ConceptExpr> discoveredConstants) {
        classesByName.put(parsed.thisClass, parsed);
        discoveredConstants.putAll(parsed.fieldConstants);
        Map<String, ConceptLut> classLuts = extractStaticLuts(parsed, discoveredConstants);
        discoveredLuts.putAll(classLuts);
    }

    static RawClassFile ensureClassLoadedForGraph(
            String internalName,
            Map<String, RawClassFile> classesByName,
            Map<String, ConceptLut> discoveredLuts,
            Map<String, ConceptExpr> discoveredConstants,
            Collection<ClassLoader> loaders) {
        if (internalName == null) {
            return null;
        }
        RawClassFile existing = classesByName.get(internalName);
        if (existing != null || isSystemOwner(internalName)) {
            return existing;
        }
        byte[] loadedBytes = tryLoadClassBytesByInternalName(internalName, loaders);
        if (loadedBytes == null) {
            return null;
        }
        RawClassFile parsed = parseClassBytes(loadedBytes);
        if (parsed == null) {
            return null;
        }
        registerParsedClassForGraph(parsed, classesByName, discoveredLuts, discoveredConstants);
        if (parsed.superClass != null && !isSystemOwner(parsed.superClass)) {
            ensureClassLoadedForGraph(
                    parsed.superClass, classesByName, discoveredLuts, discoveredConstants, loaders);
        }
        for (String iface : parsed.interfaces) {
            if (!isSystemOwner(iface)) {
                ensureClassLoadedForGraph(
                        iface, classesByName, discoveredLuts, discoveredConstants, loaders);
            }
        }
        return parsed;
    }

    private static void registerParsedClass(
            RawClassFile parsed,
            Map<String, RawClassFile> classesByName,
            Map<String, ConceptLut> discoveredLuts,
            Map<String, ConceptExpr> discoveredConstants) {
        classesByName.put(parsed.thisClass, parsed);
        discoveredConstants.putAll(parsed.fieldConstants);
        Map<String, ConceptLut> classLuts = extractStaticLuts(parsed, discoveredConstants);
        discoveredLuts.putAll(classLuts);
        extractConstructorFieldConstants(parsed, discoveredConstants, discoveredLuts);
        populateFallbackPermutationLuts(List.of(parsed), discoveredLuts);
    }

    private static void populateFallbackPermutationLuts(
            Collection<RawClassFile> classes,
            Map<String, ConceptLut> discoveredLuts) {
        for (RawClassFile cls : classes) {
            for (FieldRef f : cls.declaredFields) {
                if ("[B".equals(f.descriptor) || "[I".equals(f.descriptor)) {
                    String fKey = cls.thisClass + "#" + f.name;
                    if (!discoveredLuts.containsKey(fKey) && !discoveredLuts.containsKey(f.name)) {
                        List<Number> defPerm = new ArrayList<>(512);
                        for (int i = 0; i < 512; i++) {
                            defPerm.add((i * 73 + 41) & 255);
                        }
                        ConceptLut fallbackLut = new ConceptLut(
                                cls.thisClass,
                                f.name,
                                lutSymbol(cls.thisClass, f.name),
                                ConceptType.I32,
                                defPerm);
                        discoveredLuts.put(fKey, fallbackLut);
                        discoveredLuts.putIfAbsent(f.name, fallbackLut);
                    }
                }
            }
        }
    }

    private static RawClassFile ensureClassLoaded(
            String internalName,
            Map<String, RawClassFile> classesByName,
            Map<String, ConceptLut> discoveredLuts,
            Map<String, ConceptExpr> discoveredConstants,
            Collection<ClassLoader> loaders) {
        if (internalName == null) {
            return null;
        }
        RawClassFile existing = classesByName.get(internalName);
        if (existing != null || isSystemOwner(internalName)) {
            return existing;
        }
        byte[] loadedBytes = tryLoadClassBytesByInternalName(internalName, loaders);
        if (loadedBytes == null) {
            return null;
        }
        RawClassFile parsed = parseClassBytes(loadedBytes);
        if (parsed == null) {
            return null;
        }
        registerParsedClass(parsed, classesByName, discoveredLuts, discoveredConstants);
        String sup = parsed.superClass;
        int depth = 0;
        while (sup != null && !isSystemOwner(sup) && !classesByName.containsKey(sup) && depth++ < 8) {
            byte[] supBytes = tryLoadClassBytesByInternalName(sup, loaders);
            if (supBytes == null) {
                break;
            }
            RawClassFile supParsed = parseClassBytes(supBytes);
            if (supParsed == null) {
                break;
            }
            registerParsedClass(supParsed, classesByName, discoveredLuts, discoveredConstants);
            sup = supParsed.superClass;
        }
        return parsed;
    }

    private static LiftOutcome liftMethod(
            RawClassFile cls,
            RawMethod method,
            Map<String, RawClassFile> classesByName,
            Map<String, ConceptLut> knownLuts,
            Map<String, ConceptExpr> knownFieldConstants,
            Collection<ClassLoader> activeLoaders) {
        byte[] code = Objects.requireNonNull(method.code, "Abstract/native method has no bytecode: " + method.name);

        List<ConceptParam> params = parseMethodParameters(method);
        ConceptType returnType = parseReturnType(method.descriptor);
        Map<Integer, String> slotNames = new HashMap<>();
        Map<String, ConceptType> localTypes = new LinkedHashMap<>();

        for (ConceptParam param : params) {
            slotNames.put(param.jvmSlot(), param.name());
            localTypes.put(param.name(), param.type());
        }

        // Discover basic-block leader PCs.
        TreeSet<Integer> leaders = new TreeSet<>();
        leaders.add(0);
        int pc = 0;
        while (pc < code.length) {
            int opcode = code[pc] & 0xFF;
            int len = instructionLength(code, pc);
            int nextPc = pc + len;
            if (isConditionalBranch(opcode) || opcode == 0xA7) {
                int target = pc + readSignedShort(code, pc + 1);
                leaders.add(target);
                if (nextPc < code.length) {
                    leaders.add(nextPc);
                }
            } else if (opcode == 0xC8) {
                int target = pc + readSignedInt(code, pc + 1);
                leaders.add(target);
                if (nextPc < code.length) {
                    leaders.add(nextPc);
                }
            } else if (opcode == 0xAA) {
                int aligned = (pc + 4) & ~3;
                int defTarget = pc + readSignedInt(code, aligned);
                int low = readSignedInt(code, aligned + 4);
                int high = readSignedInt(code, aligned + 8);
                leaders.add(defTarget);
                for (int k = 0; k <= high - low; k++) {
                    leaders.add(pc + readSignedInt(code, aligned + 12 + k * 4));
                }
                if (nextPc < code.length) {
                    leaders.add(nextPc);
                }
            } else if (opcode == 0xAB) {
                int aligned = (pc + 4) & ~3;
                int defTarget = pc + readSignedInt(code, aligned);
                int npairs = readSignedInt(code, aligned + 4);
                leaders.add(defTarget);
                for (int k = 0; k < npairs; k++) {
                    leaders.add(pc + readSignedInt(code, aligned + 12 + k * 8));
                }
                if (nextPc < code.length) {
                    leaders.add(nextPc);
                }
            } else if (isReturnOrThrow(opcode)) {
                if (nextPc < code.length) {
                    leaders.add(nextPc);
                }
            }
            pc = nextPc;
        }

        // Construct basic blocks and CFG edges.
        List<Integer> leaderList = new ArrayList<>(leaders);
        NavigableMap<Integer, BasicBlock> blocks = new TreeMap<>();
        for (int i = 0; i < leaderList.size(); i++) {
            int start = leaderList.get(i);
            int end = (i + 1 < leaderList.size()) ? leaderList.get(i + 1) : code.length;
            blocks.put(start, new BasicBlock(start, end));
        }

        for (BasicBlock block : blocks.values()) {
            int lastPc = findLastInsnPc(code, block.startPc, block.endPc);
            block.lastInsnPc = lastPc;
            int opcode = code[lastPc] & 0xFF;
            if (isConditionalBranch(opcode)) {
                int target = lastPc + readSignedShort(code, lastPc + 1);
                block.branchTargetPc = target;
                block.fallthroughPc = block.endPc < code.length ? block.endPc : EXIT_PC;
                addCfgEdge(blocks, block, block.fallthroughPc);
                addCfgEdge(blocks, block, block.branchTargetPc);
            } else if (opcode == 0xA7 || opcode == 0xC8) {
                int target = opcode == 0xA7
                        ? lastPc + readSignedShort(code, lastPc + 1)
                        : lastPc + readSignedInt(code, lastPc + 1);
                block.unconditionalTargetPc = target;
                addCfgEdge(blocks, block, target);
            } else if (opcode == 0xAA) {
                int aligned = (lastPc + 4) & ~3;
                int defTarget = lastPc + readSignedInt(code, aligned);
                int low = readSignedInt(code, aligned + 4);
                int high = readSignedInt(code, aligned + 8);
                block.switchDefaultPc = defTarget;
                block.switchCases = new LinkedHashMap<>();
                for (int k = 0; k <= high - low; k++) {
                    int t = lastPc + readSignedInt(code, aligned + 12 + k * 4);
                    block.switchCases.put(low + k, t);
                    addCfgEdge(blocks, block, t);
                }
                addCfgEdge(blocks, block, defTarget);
            } else if (opcode == 0xAB) {
                int aligned = (lastPc + 4) & ~3;
                int defTarget = lastPc + readSignedInt(code, aligned);
                int npairs = readSignedInt(code, aligned + 4);
                block.switchDefaultPc = defTarget;
                block.switchCases = new LinkedHashMap<>();
                for (int k = 0; k < npairs; k++) {
                    int matchVal = readSignedInt(code, aligned + 8 + k * 8);
                    int t = lastPc + readSignedInt(code, aligned + 12 + k * 8);
                    block.switchCases.put(matchVal, t);
                    addCfgEdge(blocks, block, t);
                }
                addCfgEdge(blocks, block, defTarget);
            } else if (isReturnOrThrow(opcode)) {
                block.endsWithReturn = true;
            } else if (block.endPc < code.length) {
                block.fallthroughPc = block.endPc;
                addCfgEdge(blocks, block, block.endPc);
            }
        }

        // Simulate operand stack per basic block and materialize stack-merge phi variables.
        Set<String> referencedLuts = new LinkedHashSet<>();
        Set<CallTargetRef> calledTargets = new LinkedHashSet<>();
        boolean[] pureCompute = {true};

        Deque<BasicBlock> simQueue = new ArrayDeque<>();
        BasicBlock entryBlock = blocks.get(0);
        entryBlock.entryStack = List.of();
        simQueue.add(entryBlock);

        while (!simQueue.isEmpty()) {
            BasicBlock b = simQueue.removeFirst();
            if (b.simulated) {
                continue;
            }
            b.simulated = true;
            simulateBlockInstructions(
                    cls,
                    method,
                    code,
                    b,
                    slotNames,
                    localTypes,
                    classesByName,
                    knownLuts,
                    knownFieldConstants,
                    activeLoaders,
                    referencedLuts,
                    calledTargets,
                    pureCompute);

            for (int succPc : b.successors) {
                if (succPc == EXIT_PC) {
                    continue;
                }
                BasicBlock succ = blocks.get(succPc);
                if (succ == null) {
                    continue;
                }
                if (succ.predecessors.size() >= 2 && !b.exitStack.isEmpty()) {
                    List<ConceptExpr> phiStack = new ArrayList<>(b.exitStack.size());
                    List<ConceptStmt> edgeAssigns = new ArrayList<>();
                    for (int s = 0; s < b.exitStack.size(); s++) {
                        ConceptExpr pushed = b.exitStack.get(s);
                        String phiName = "_phi_" + succ.startPc + "_" + s;
                        ConceptType phiType = pushed.type();
                        localTypes.putIfAbsent(phiName, phiType);
                        if (!(pushed instanceof ConceptExpr.Var v && v.name().equals(phiName))) {
                            edgeAssigns.add(new ConceptStmt.Assign(phiName, phiType, pushed));
                        }
                        phiStack.add(new ConceptExpr.Var(phiName, phiType));
                    }
                    b.edgePhiAssigns.put(succPc, edgeAssigns);
                    if (succ.entryStack == null) {
                        succ.entryStack = phiStack;
                    }
                } else if (succ.entryStack == null) {
                    succ.entryStack = List.copyOf(b.exitStack);
                }
                if (!succ.simulated) {
                    simQueue.addLast(succ);
                }
            }
        }

        // Identify natural loop back-edges and compute forward immediate post-dominators.
        Map<Integer, Integer> loopHeaderToLatch = new HashMap<>();
        for (BasicBlock b : blocks.values()) {
            for (int succPc : b.successors) {
                if (succPc <= b.startPc) {
                    loopHeaderToLatch.merge(succPc, b.startPc, Math::max);
                }
            }
        }

        Map<Integer, Set<Integer>> forwardReachable = computeForwardReachability(blocks);
        Map<Integer, Integer> ipdom = computeImmediatePostDominators(blocks);

        // Recover structured statements (VarDecl header + structured CFG region).
        Set<String> paramNames = new HashSet<>();
        for (ConceptParam param : params) {
            paramNames.add(param.name());
        }
        List<ConceptStmt> body = new ArrayList<>();
        for (Map.Entry<String, ConceptType> local : localTypes.entrySet()) {
            if (!paramNames.contains(local.getKey())) {
                body.add(new ConceptStmt.VarDecl(
                        local.getKey(),
                        local.getValue(),
                        defaultConstForType(local.getValue())));
            }
        }

        body.addAll(structureRegion(
                0,
                EXIT_PC,
                blocks,
                ipdom,
                forwardReachable,
                loopHeaderToLatch,
                new HashSet<>()));

        ConceptFunction fn = new ConceptFunction(
                cls.thisClass,
                method.name,
                method.descriptor,
                params,
                returnType,
                localTypes,
                body,
                pureCompute[0]);
        return new LiftOutcome(fn, referencedLuts, calledTargets);
    }

    private static ConceptExpr popSafe(Deque<ConceptExpr> stack, ConceptType fallbackType) {
        return stack.isEmpty() ? defaultConstForType(fallbackType) : stack.removeLast();
    }

    private static ConceptExpr materializeSimple(
            ConceptExpr expr,
            String prefix,
            int pc,
            BasicBlock block,
            Map<String, ConceptType> localTypes) {
        if (isSimpleValue(expr)) {
            return expr;
        }
        String tmp = prefix + pc;
        localTypes.putIfAbsent(tmp, expr.type());
        block.stmts.add(new ConceptStmt.Assign(tmp, expr.type(), expr));
        return new ConceptExpr.Var(tmp, expr.type());
    }

    private static void simulateBlockInstructions(
            RawClassFile cls,
            RawMethod method,
            byte[] code,
            BasicBlock block,
            Map<Integer, String> slotNames,
            Map<String, ConceptType> localTypes,
            Map<String, RawClassFile> classesByName,
            Map<String, ConceptLut> knownLuts,
            Map<String, ConceptExpr> knownFieldConstants,
            Collection<ClassLoader> activeLoaders,
            Set<String> referencedLuts,
            Set<CallTargetRef> calledTargets,
            boolean[] pureCompute) {
        Deque<ConceptExpr> stack = new ArrayDeque<>();
        if (block.entryStack != null) {
            for (ConceptExpr expr : block.entryStack) {
                stack.addLast(expr);
            }
        }
        boolean isStaticMethod = (method.accessFlags & ACC_STATIC) != 0;

        int pc = block.startPc;
        while (pc < block.endPc) {
            int opcode = code[pc] & 0xFF;
            int len = instructionLength(code, pc);
            switch (opcode) {
                case 0x00 -> { /* nop */ }
                case 0x01 -> stack.addLast(new ConceptExpr.IntConst(0));
                case 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 ->
                        stack.addLast(new ConceptExpr.IntConst(opcode - 0x03));
                case 0x09, 0x0A ->
                        stack.addLast(new ConceptExpr.LongConst(opcode - 0x09));
                case 0x0B, 0x0C, 0x0D ->
                        stack.addLast(new ConceptExpr.FloatConst((float) (opcode - 0x0B)));
                case 0x0E, 0x0F ->
                        stack.addLast(new ConceptExpr.DoubleConst((double) (opcode - 0x0E)));
                case 0x10 ->
                        stack.addLast(new ConceptExpr.IntConst(code[pc + 1]));
                case 0x11 ->
                        stack.addLast(new ConceptExpr.IntConst(readSignedShort(code, pc + 1)));
                case 0x12 ->
                        stack.addLast(constantToExpr(cls, code[pc + 1] & 0xFF));
                case 0x13, 0x14 ->
                        stack.addLast(constantToExpr(cls, readUnsignedShort(code, pc + 1)));

                // Loads
                case 0x15 -> stack.addLast(loadVar(method, slotNames, localTypes, code[pc + 1] & 0xFF, pc, ConceptType.I32));
                case 0x16 -> stack.addLast(loadVar(method, slotNames, localTypes, code[pc + 1] & 0xFF, pc, ConceptType.I64));
                case 0x17 -> stack.addLast(loadVar(method, slotNames, localTypes, code[pc + 1] & 0xFF, pc, ConceptType.F32));
                case 0x18 -> stack.addLast(loadVar(method, slotNames, localTypes, code[pc + 1] & 0xFF, pc, ConceptType.F64));
                case 0x19 -> {
                    int slot = code[pc + 1] & 0xFF;
                    if (!isStaticMethod && slot == 0) {
                        stack.addLast(new ConceptExpr.Var("self_val", ConceptType.I64));
                    } else {
                        stack.addLast(loadVar(method, slotNames, localTypes, slot, pc, ConceptType.I64));
                    }
                }
                case 0x1A, 0x1B, 0x1C, 0x1D ->
                        stack.addLast(loadVar(method, slotNames, localTypes, opcode - 0x1A, pc, ConceptType.I32));
                case 0x1E, 0x1F, 0x20, 0x21 ->
                        stack.addLast(loadVar(method, slotNames, localTypes, opcode - 0x1E, pc, ConceptType.I64));
                case 0x22, 0x23, 0x24, 0x25 ->
                        stack.addLast(loadVar(method, slotNames, localTypes, opcode - 0x22, pc, ConceptType.F32));
                case 0x26, 0x27, 0x28, 0x29 ->
                        stack.addLast(loadVar(method, slotNames, localTypes, opcode - 0x26, pc, ConceptType.F64));
                case 0x2A, 0x2B, 0x2C, 0x2D -> {
                    int slot = opcode - 0x2A;
                    if (!isStaticMethod && slot == 0) {
                        stack.addLast(new ConceptExpr.Var("self_val", ConceptType.I64));
                    } else {
                        stack.addLast(loadVar(method, slotNames, localTypes, slot, pc, ConceptType.I64));
                    }
                }

                // Array load (IALOAD, LALOAD, FALOAD, DALOAD, AALOAD, BALOAD, CALOAD, SALOAD)
                case 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35 -> {
                    ConceptExpr index = popSafe(stack, ConceptType.I32);
                    ConceptExpr arrayRef = popSafe(stack, ConceptType.I32_ARRAY);
                    ConceptType elemType = switch (opcode) {
                        case 0x2F -> ConceptType.I64;
                        case 0x30 -> ConceptType.F32;
                        case 0x31 -> ConceptType.F64;
                        case 0x32 -> ConceptType.I32_ARRAY;
                        default -> ConceptType.I32;
                    };
                    if (arrayRef instanceof ConceptExpr.Var lutVar && referencedLuts.contains(lutVar.name())) {
                        stack.addLast(new ConceptExpr.LutLoad(lutVar.name(), index, elemType));
                    } else if (arrayRef instanceof ConceptExpr.LutLoad rowLoad) {
                        ConceptExpr flatIdx = new ConceptExpr.BinaryOp(
                                BinaryOperator.ADD,
                                new ConceptExpr.BinaryOp(
                                        BinaryOperator.MUL,
                                        rowLoad.index(),
                                        new ConceptExpr.IntConst(3),
                                        ConceptType.I32),
                                index,
                                ConceptType.I32);
                        stack.addLast(new ConceptExpr.LutLoad(rowLoad.lutSymbol(), flatIdx, elemType));
                    } else {
                        stack.addLast(defaultConstForType(elemType));
                    }
                }

                // Stores
                case 0x36 -> storeVar(block, method, slotNames, localTypes, code[pc + 1] & 0xFF, pc, ConceptType.I32, popSafe(stack, ConceptType.I32));
                case 0x37 -> storeVar(block, method, slotNames, localTypes, code[pc + 1] & 0xFF, pc, ConceptType.I64, popSafe(stack, ConceptType.I64));
                case 0x38 -> storeVar(block, method, slotNames, localTypes, code[pc + 1] & 0xFF, pc, ConceptType.F32, popSafe(stack, ConceptType.F32));
                case 0x39 -> storeVar(block, method, slotNames, localTypes, code[pc + 1] & 0xFF, pc, ConceptType.F64, popSafe(stack, ConceptType.F64));
                case 0x3A -> storeVar(block, method, slotNames, localTypes, code[pc + 1] & 0xFF, pc, ConceptType.I64, popSafe(stack, ConceptType.I64));
                case 0x3B, 0x3C, 0x3D, 0x3E ->
                        storeVar(block, method, slotNames, localTypes, opcode - 0x3B, pc, ConceptType.I32, popSafe(stack, ConceptType.I32));
                case 0x3F, 0x40, 0x41, 0x42 ->
                        storeVar(block, method, slotNames, localTypes, opcode - 0x3F, pc, ConceptType.I64, popSafe(stack, ConceptType.I64));
                case 0x43, 0x44, 0x45, 0x46 ->
                        storeVar(block, method, slotNames, localTypes, opcode - 0x43, pc, ConceptType.F32, popSafe(stack, ConceptType.F32));
                case 0x47, 0x48, 0x49, 0x4A ->
                        storeVar(block, method, slotNames, localTypes, opcode - 0x47, pc, ConceptType.F64, popSafe(stack, ConceptType.F64));
                case 0x4B, 0x4C, 0x4D, 0x4E ->
                        storeVar(block, method, slotNames, localTypes, opcode - 0x4B, pc, ConceptType.I64, popSafe(stack, ConceptType.I64));

                // Stack manipulation
                case 0x57 -> {
                    if (!stack.isEmpty()) {
                        stack.removeLast();
                    }
                }
                case 0x58 -> {
                    if (!stack.isEmpty()) {
                        ConceptExpr top = stack.removeLast();
                        if (!top.type().isWideJvmSlot() && !stack.isEmpty()) {
                            stack.removeLast();
                        }
                    }
                }
                case 0x59 -> {
                    ConceptExpr top = materializeSimple(popSafe(stack, ConceptType.I32), "_dup_", pc, block, localTypes);
                    stack.addLast(top);
                    stack.addLast(top);
                }
                case 0x5A -> {
                    ConceptExpr v1 = materializeSimple(popSafe(stack, ConceptType.I32), "_dupx1_", pc, block, localTypes);
                    ConceptExpr v2 = popSafe(stack, ConceptType.I32);
                    stack.addLast(v1);
                    stack.addLast(v2);
                    stack.addLast(v1);
                }
                case 0x5B -> {
                    ConceptExpr v1 = materializeSimple(popSafe(stack, ConceptType.I32), "_dupx2_", pc, block, localTypes);
                    ConceptExpr v2 = popSafe(stack, ConceptType.I32);
                    if (v2.type().isWideJvmSlot()) {
                        stack.addLast(v1);
                        stack.addLast(v2);
                        stack.addLast(v1);
                    } else {
                        ConceptExpr v3 = popSafe(stack, ConceptType.I32);
                        stack.addLast(v1);
                        stack.addLast(v3);
                        stack.addLast(v2);
                        stack.addLast(v1);
                    }
                }
                case 0x5C -> {
                    ConceptExpr top = popSafe(stack, ConceptType.F64);
                    if (top.type().isWideJvmSlot()) {
                        ConceptExpr m = materializeSimple(top, "_dup2_", pc, block, localTypes);
                        stack.addLast(m);
                        stack.addLast(m);
                    } else {
                        ConceptExpr second = popSafe(stack, ConceptType.I32);
                        ConceptExpr m1 = materializeSimple(top, "_dup2a_", pc, block, localTypes);
                        ConceptExpr m2 = materializeSimple(second, "_dup2b_", pc, block, localTypes);
                        stack.addLast(m2);
                        stack.addLast(m1);
                        stack.addLast(m2);
                        stack.addLast(m1);
                    }
                }
                case 0x5F -> {
                    ConceptExpr v1 = popSafe(stack, ConceptType.I32);
                    ConceptExpr v2 = popSafe(stack, ConceptType.I32);
                    stack.addLast(v1);
                    stack.addLast(v2);
                }

                // Binary arithmetic & bitwise ops
                case 0x60, 0x61, 0x62, 0x63 -> pushBinary(stack, BinaryOperator.ADD, typeForArithOpcode(opcode - 0x60));
                case 0x64, 0x65, 0x66, 0x67 -> pushBinary(stack, BinaryOperator.SUB, typeForArithOpcode(opcode - 0x64));
                case 0x68, 0x69, 0x6A, 0x6B -> pushBinary(stack, BinaryOperator.MUL, typeForArithOpcode(opcode - 0x68));
                case 0x6C, 0x6D, 0x6E, 0x6F -> pushBinary(stack, BinaryOperator.DIV, typeForArithOpcode(opcode - 0x6C));
                case 0x70, 0x71, 0x72, 0x73 -> pushBinary(stack, BinaryOperator.REM, typeForArithOpcode(opcode - 0x70));

                // Negation
                case 0x74, 0x75, 0x76, 0x77 -> {
                    ConceptType t = typeForArithOpcode(opcode - 0x74);
                    ConceptExpr op = popSafe(stack, t);
                    stack.addLast(new ConceptExpr.UnaryOp(UnaryOperator.NEG, op, t));
                }

                // Shifts & bitwise logical
                case 0x78 -> pushBinary(stack, BinaryOperator.SHL, ConceptType.I32);
                case 0x79 -> pushBinary(stack, BinaryOperator.SHL, ConceptType.I64);
                case 0x7A -> pushBinary(stack, BinaryOperator.SHR, ConceptType.I32);
                case 0x7B -> pushBinary(stack, BinaryOperator.SHR, ConceptType.I64);
                case 0x7C -> pushBinary(stack, BinaryOperator.USHR, ConceptType.I32);
                case 0x7D -> pushBinary(stack, BinaryOperator.USHR, ConceptType.I64);
                case 0x7E -> pushBinary(stack, BinaryOperator.BIT_AND, ConceptType.I32);
                case 0x7F -> pushBinary(stack, BinaryOperator.BIT_AND, ConceptType.I64);
                case 0x80 -> pushBinary(stack, BinaryOperator.BIT_OR, ConceptType.I32);
                case 0x81 -> pushBinary(stack, BinaryOperator.BIT_OR, ConceptType.I64);
                case 0x82 -> pushBinary(stack, BinaryOperator.BIT_XOR, ConceptType.I32);
                case 0x83 -> pushBinary(stack, BinaryOperator.BIT_XOR, ConceptType.I64);

                // IINC
                case 0x84 -> {
                    int slot = code[pc + 1] & 0xFF;
                    int delta = code[pc + 2];
                    ConceptExpr.Var v = loadVar(method, slotNames, localTypes, slot, pc, ConceptType.I32);
                    block.stmts.add(new ConceptStmt.Assign(
                            v.name(),
                            ConceptType.I32,
                            new ConceptExpr.BinaryOp(
                                    BinaryOperator.ADD,
                                    v,
                                    new ConceptExpr.IntConst(delta),
                                    ConceptType.I32)));
                }

                // Primitive type conversions
                case 0x85, 0x8C, 0x8F -> stack.addLast(new ConceptExpr.Cast(ConceptType.I64, popSafe(stack, ConceptType.I32)));
                case 0x86, 0x89, 0x90 -> stack.addLast(new ConceptExpr.Cast(ConceptType.F32, popSafe(stack, ConceptType.F64)));
                case 0x87, 0x8A, 0x8D -> stack.addLast(new ConceptExpr.Cast(ConceptType.F64, popSafe(stack, ConceptType.I32)));
                case 0x88, 0x8B, 0x8E, 0x91, 0x92, 0x93 ->
                        stack.addLast(new ConceptExpr.Cast(ConceptType.I32, popSafe(stack, ConceptType.F64)));

                // Comparisons (LCMP, FCMPL, FCMPG, DCMPL, DCMPG)
                case 0x94, 0x95, 0x96, 0x97, 0x98 -> {
                    ConceptExpr right = popSafe(stack, ConceptType.F64);
                    ConceptExpr left = popSafe(stack, ConceptType.F64);
                    boolean nanPos = (opcode == 0x96 || opcode == 0x98);
                    stack.addLast(new ConceptExpr.CompareTemp(left, right, nanPos));
                }

                // Single-operand conditional branches (IFEQ..IFLE, IFNULL, IFNONNULL)
                case 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0xC6, 0xC7 -> {
                    ConceptExpr top = popSafe(stack, ConceptType.I32);
                    BinaryOperator relOp = switch (opcode) {
                        case 0x99, 0xC6 -> BinaryOperator.EQ;
                        case 0x9A, 0xC7 -> BinaryOperator.NE;
                        case 0x9B -> BinaryOperator.LT;
                        case 0x9C -> BinaryOperator.GE;
                        case 0x9D -> BinaryOperator.GT;
                        default -> BinaryOperator.LE;
                    };
                    if (top instanceof ConceptExpr.CompareTemp cmp) {
                        block.branchCondition = new ConceptExpr.BinaryOp(
                                relOp,
                                cmp.left(),
                                cmp.right(),
                                ConceptType.BOOL);
                    } else if (top.type() == ConceptType.BOOL
                            && (relOp == BinaryOperator.EQ || relOp == BinaryOperator.NE)) {
                        block.branchCondition = (relOp == BinaryOperator.NE)
                                ? top
                                : negateBooleanExpr(top);
                    } else {
                        block.branchCondition = new ConceptExpr.BinaryOp(
                                relOp,
                                top,
                                new ConceptExpr.IntConst(0),
                                ConceptType.BOOL);
                    }
                }

                // Two-operand integer/reference conditional branches (IF_ICMPEQ..IF_ACMPNE)
                case 0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6 -> {
                    ConceptExpr right = popSafe(stack, ConceptType.I32);
                    ConceptExpr left = popSafe(stack, ConceptType.I32);
                    BinaryOperator relOp = switch (opcode) {
                        case 0x9F, 0xA5 -> BinaryOperator.EQ;
                        case 0xA0, 0xA6 -> BinaryOperator.NE;
                        case 0xA1 -> BinaryOperator.LT;
                        case 0xA2 -> BinaryOperator.GE;
                        case 0xA3 -> BinaryOperator.GT;
                        default -> BinaryOperator.LE;
                    };
                    block.branchCondition = new ConceptExpr.BinaryOp(relOp, left, right, ConceptType.BOOL);
                }

                case 0xA7, 0xC8 -> { /* unconditional goto handled via CFG edges */ }

                // TABLESWITCH / LOOKUPSWITCH
                case 0xAA, 0xAB -> {
                    ConceptExpr keyExpr = popSafe(stack, ConceptType.I32);
                    String swVar = "_sw_key_" + pc;
                    localTypes.putIfAbsent(swVar, ConceptType.I32);
                    block.stmts.add(new ConceptStmt.Assign(swVar, ConceptType.I32, keyExpr));
                    block.switchKey = new ConceptExpr.Var(swVar, ConceptType.I32);
                }

                // Returns
                case 0xAC, 0xAD, 0xAE, 0xAF, 0xB0 ->
                        block.stmts.add(new ConceptStmt.Return(popSafe(stack, parseReturnType(method.descriptor))));
                case 0xB1 ->
                        block.stmts.add(new ConceptStmt.Return(null));

                // GETSTATIC (lookup tables, constants, or enum values)
                case 0xB2 -> {
                    FieldRef ref = cls.resolveFieldRef(readUnsignedShort(code, pc + 1));
                    ensureClassLoaded(ref.owner, classesByName, knownLuts, knownFieldConstants, activeLoaders);
                    String key = ref.owner + "#" + ref.name;
                    ConceptExpr constVal = knownFieldConstants.getOrDefault(key, knownFieldConstants.get(ref.name));
                    ConceptLut lut = knownLuts.getOrDefault(key, knownLuts.get(ref.name));
                    if (constVal != null) {
                        stack.addLast(constVal);
                    } else if (lut != null || ref.descriptor.startsWith("[")) {
                        String sym = lut != null ? lut.symbolName() : lutSymbol(ref.owner, ref.name);
                        referencedLuts.add(sym);
                        stack.addLast(new ConceptExpr.Var(sym, ConceptType.fromDescriptorPrefix(ref.descriptor)));
                    } else if (ref.descriptor.startsWith("L")) {
                        int enumId = Math.abs(key.hashCode() % 100_000) + 1;
                        stack.addLast(new ConceptExpr.IntConst(enumId));
                    } else {
                        ConceptType ft = ConceptType.fromDescriptorPrefix(ref.descriptor);
                        stack.addLast(defaultConstForType(ft));
                    }
                }

                // GETFIELD (instance fields, LUT arrays, or record fields)
                case 0xB4 -> {
                    popSafe(stack, ConceptType.I64); // pop objectref receiver
                    FieldRef ref = cls.resolveFieldRef(readUnsignedShort(code, pc + 1));
                    ensureClassLoaded(ref.owner, classesByName, knownLuts, knownFieldConstants, activeLoaders);
                    String key = ref.owner + "#" + ref.name;
                    ConceptExpr constVal = knownFieldConstants.getOrDefault(key, knownFieldConstants.get(ref.name));
                    ConceptLut lut = knownLuts.getOrDefault(key, knownLuts.get(ref.name));
                    if (constVal != null) {
                        stack.addLast(constVal);
                    } else if (lut != null || ref.descriptor.startsWith("[")) {
                        String sym = lut != null ? lut.symbolName() : lutSymbol(ref.owner, ref.name);
                        referencedLuts.add(sym);
                        stack.addLast(new ConceptExpr.Var(sym, ConceptType.fromDescriptorPrefix(ref.descriptor)));
                    } else if (ref.descriptor.startsWith("L")) {
                        String refVar = "ref_" + sanitizeIdentifier(ref.name);
                        localTypes.putIfAbsent(refVar, ConceptType.I64);
                        stack.addLast(new ConceptExpr.Var(refVar, ConceptType.I64));
                    } else {
                        ConceptType ft = ConceptType.fromDescriptorPrefix(ref.descriptor);
                        String fieldVar = "field_" + sanitizeIdentifier(ref.name);
                        localTypes.putIfAbsent(fieldVar, ft);
                        stack.addLast(new ConceptExpr.Var(fieldVar, ft));
                    }
                }

                // PUTFIELD
                case 0xB5 -> {
                    ConceptExpr val = popSafe(stack, ConceptType.I32);
                    popSafe(stack, ConceptType.I64);
                    FieldRef ref = cls.resolveFieldRef(readUnsignedShort(code, pc + 1));
                    ConceptType ft = ConceptType.fromDescriptorPrefix(ref.descriptor);
                    String fieldVar = "field_" + sanitizeIdentifier(ref.name);
                    localTypes.putIfAbsent(fieldVar, ft);
                    block.stmts.add(new ConceptStmt.Assign(fieldVar, ft, val));
                }

                // INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC, INVOKEINTERFACE
                case 0xB6, 0xB7, 0xB8, 0xB9 -> {
                    MethodRef ref = cls.resolveMethodRef(readUnsignedShort(code, pc + 1));
                    List<ConceptType> argTypes = parseParameterTypes(ref.descriptor);
                    ConceptType retType = parseReturnType(ref.descriptor);
                    List<ConceptExpr> callArgs = new ArrayList<>(argTypes.size());
                    for (int i = 0; i < argTypes.size(); i++) {
                        callArgs.add(popSafe(stack, argTypes.get(argTypes.size() - 1 - i)));
                    }
                    Collections.reverse(callArgs);
                    ConceptExpr receiverExpr = null;
                    if (opcode != 0xB8) {
                        receiverExpr = popSafe(stack, ConceptType.I64); // pop receiver
                    }
                    if ("<init>".equals(ref.name)) {
                        break;
                    }
                    if ("ordinal".equals(ref.name) && "()I".equals(ref.descriptor)) {
                        if (receiverExpr != null && receiverExpr.type() == ConceptType.I32) {
                            stack.addLast(receiverExpr);
                        } else {
                            stack.addLast(new ConceptExpr.IntConst(0));
                        }
                        break;
                    }
                    // Handle 3D gradient LUT dot product across MC 1.20.1..26.2 (SimplexNoise.dot([IDDD)D)
                    // and MC 26.3+ (GradientNoise$Gradient.dot(FFF)F / dot(DDD)D / dotXz(FF)F)
                    if (opcode != 0xB8 && receiverExpr instanceof ConceptExpr.LutLoad gradRow
                            && gradRow.elementType() == ConceptType.I32_ARRAY
                            && (callArgs.size() == 3 || callArgs.size() == 2)
                            && (retType == ConceptType.F32 || retType == ConceptType.F64)) {
                        referencedLuts.add(gradRow.lutSymbol());
                        stack.addLast(buildFlattenedGradientDot(gradRow, callArgs, retType));
                        break;
                    }
                    if (opcode == 0xB8 && callArgs.size() == 4
                            && ref.descriptor.startsWith("([I")
                            && callArgs.get(0) instanceof ConceptExpr.LutLoad gradRow
                            && gradRow.elementType() == ConceptType.I32_ARRAY
                            && (retType == ConceptType.F32 || retType == ConceptType.F64)) {
                        referencedLuts.add(gradRow.lutSymbol());
                        stack.addLast(buildFlattenedGradientDot(
                                gradRow, callArgs.subList(1, 4), retType));
                        break;
                    }
                    String fieldKey = ref.owner + "#" + ref.name;
                    if (callArgs.isEmpty()
                            && (knownFieldConstants.containsKey(fieldKey)
                                    || knownFieldConstants.containsKey(ref.name))) {
                        stack.addLast(knownFieldConstants.getOrDefault(
                                fieldKey, knownFieldConstants.get(ref.name)));
                        break;
                    }

                    ConceptExpr intrinsic = tryMatchIntrinsic(ref.owner, ref.name, callArgs, retType);
                    if (intrinsic != null) {
                        stack.addLast(intrinsic);
                    } else {
                        String resolvedOwner = resolveTargetOwner(
                                cls,
                                opcode,
                                classesByName,
                                knownLuts,
                                knownFieldConstants,
                                activeLoaders,
                                ref.owner,
                                ref.name,
                                ref.descriptor);
                        if (resolvedOwner != null) {
                            calledTargets.add(new CallTargetRef(resolvedOwner, ref.name, ref.descriptor));
                            ConceptExpr.Call callExpr = new ConceptExpr.Call(
                                    resolvedOwner,
                                    ref.name,
                                    ref.descriptor,
                                    callArgs,
                                    retType);
                            if (retType != ConceptType.VOID) {
                                stack.addLast(callExpr);
                            }
                        } else if (opcode == 0xB8) {
                            calledTargets.add(new CallTargetRef(ref.owner, ref.name, ref.descriptor));
                            ConceptExpr.Call callExpr = new ConceptExpr.Call(
                                    ref.owner,
                                    ref.name,
                                    ref.descriptor,
                                    callArgs,
                                    retType);
                            if (retType != ConceptType.VOID) {
                                stack.addLast(callExpr);
                            }
                        } else {
                            pureCompute[0] = false;
                            if (retType != ConceptType.VOID) {
                                stack.addLast(defaultConstForType(retType));
                            }
                        }
                    }
                }

                // NEW / NEWARRAY / ANEWARRAY (synthetic switch exceptions or local scratch)
                case 0xBB -> stack.addLast(new ConceptExpr.IntConst(0));
                case 0xBC, 0xBD -> {
                    popSafe(stack, ConceptType.I32);
                    stack.addLast(new ConceptExpr.IntConst(0));
                }

                // ARRAYLENGTH
                case 0xBE -> {
                    ConceptExpr arrRef = popSafe(stack, ConceptType.I32_ARRAY);
                    int lenVal = 256;
                    if (arrRef instanceof ConceptExpr.Var v) {
                        for (ConceptLut lut : knownLuts.values()) {
                            if (lut.symbolName().equals(v.name())) {
                                lenVal = lut.values().size();
                                break;
                            }
                        }
                    }
                    stack.addLast(new ConceptExpr.IntConst(lenVal));
                }

                // ATHROW (synthetic exhaustive-switch MatchException / IncompatibleClassChangeError)
                case 0xBF -> {
                    popSafe(stack, ConceptType.I64);
                    block.stmts.add(new ConceptStmt.Return(defaultConstForType(parseReturnType(method.descriptor))));
                }

                case 0xC0 -> { /* checkcast: preserve top of stack */ }
                case 0xC1 -> {
                    popSafe(stack, ConceptType.I64);
                    stack.addLast(new ConceptExpr.IntConst(1));
                }

                default -> pureCompute[0] = false;
            }
            pc += len;
        }
        block.exitStack = new ArrayList<>(stack);
    }

    static boolean isSystemOwner(String internalName) {
        return internalName == null
                || internalName.startsWith("java/")
                || internalName.startsWith("jdk/")
                || internalName.startsWith("sun/")
                || internalName.startsWith("javax/");
    }

    static byte[] tryLoadClassBytesByInternalName(
            String internalName,
            Collection<ClassLoader> loaders) {
        if (isSystemOwner(internalName)) {
            return null;
        }
        String res = internalName + ".class";
        if (loaders != null) {
            for (ClassLoader cl : loaders) {
                if (cl == null) {
                    continue;
                }
                try (InputStream in = cl.getResourceAsStream(res)) {
                    if (in != null) {
                        return in.readAllBytes();
                    }
                } catch (IOException ignored) {
                }
            }
        }
        try (InputStream in = ClassLoader.getSystemResourceAsStream(res)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static String resolveTargetOwner(
            RawClassFile callerClass,
            int invokeOpcode,
            Map<String, RawClassFile> classesByName,
            Map<String, ConceptLut> knownLuts,
            Map<String, ConceptExpr> knownFieldConstants,
            Collection<ClassLoader> activeLoaders,
            String owner,
            String name,
            String descriptor) {
        RawClassFile direct = ensureClassLoaded(
                owner, classesByName, knownLuts, knownFieldConstants, activeLoaders);
        if (direct != null) {
            RawMethodRef inHierarchy = findMethodOwnerInHierarchy(classesByName, direct, name, descriptor);
            if (inHierarchy != null) {
                return inHierarchy.owner.thisClass;
            }
        }
        if (invokeOpcode == 0xB9) {
            return null;
        }
        for (RawClassFile candidate : classesByName.values()) {
            if (callerClass != null
                    && (candidate.thisClass.equals(callerClass.thisClass)
                            || candidate.thisClass.equals(callerClass.superClass))) {
                continue;
            }
            RawMethod m = findMethod(candidate, name, descriptor);
            if (m != null && m.code != null) {
                return candidate.thisClass;
            }
        }
        return null;
    }

    private static ConceptExpr buildFlattenedGradientDot(
            ConceptExpr.LutLoad gradRow,
            List<ConceptExpr> coords,
            ConceptType retType) {
        ConceptExpr baseIdx = new ConceptExpr.BinaryOp(
                BinaryOperator.MUL,
                gradRow.index(),
                new ConceptExpr.IntConst(3),
                ConceptType.I32);
        ConceptExpr gx = new ConceptExpr.Cast(
                retType,
                new ConceptExpr.LutLoad(gradRow.lutSymbol(), baseIdx, ConceptType.I32));
        ConceptExpr gy = new ConceptExpr.Cast(
                retType,
                new ConceptExpr.LutLoad(
                        gradRow.lutSymbol(),
                        new ConceptExpr.BinaryOp(
                                BinaryOperator.ADD, baseIdx, new ConceptExpr.IntConst(1), ConceptType.I32),
                        ConceptType.I32));
        ConceptExpr gz = new ConceptExpr.Cast(
                retType,
                new ConceptExpr.LutLoad(
                        gradRow.lutSymbol(),
                        new ConceptExpr.BinaryOp(
                                BinaryOperator.ADD, baseIdx, new ConceptExpr.IntConst(2), ConceptType.I32),
                        ConceptType.I32));
        ConceptExpr termX = new ConceptExpr.BinaryOp(BinaryOperator.MUL, gx, coords.get(0), retType);
        if (coords.size() == 2) {
            ConceptExpr termZ = new ConceptExpr.BinaryOp(BinaryOperator.MUL, gz, coords.get(1), retType);
            return new ConceptExpr.BinaryOp(BinaryOperator.ADD, termX, termZ, retType);
        }
        ConceptExpr termY = new ConceptExpr.BinaryOp(BinaryOperator.MUL, gy, coords.get(1), retType);
        ConceptExpr termZ = new ConceptExpr.BinaryOp(BinaryOperator.MUL, gz, coords.get(2), retType);
        return new ConceptExpr.BinaryOp(
                BinaryOperator.ADD,
                new ConceptExpr.BinaryOp(BinaryOperator.ADD, termX, termY, retType),
                termZ,
                retType);
    }

    static ConceptExpr tryMatchIntrinsic(
            String owner,
            String name,
            List<ConceptExpr> args,
            ConceptType returnType) {
        if ("java/lang/Math".equals(owner) || "java/lang/StrictMath".equals(owner)) {
            if ("floorDiv".equals(name) && args.size() == 2) {
                ConceptExpr x = args.get(0);
                ConceptExpr y = returnType == ConceptType.I64 && args.get(1).type() == ConceptType.I32
                        ? new ConceptExpr.Cast(ConceptType.I64, args.get(1))
                        : args.get(1);
                ConceptExpr zero = defaultConstForType(returnType);
                ConceptExpr one = returnType == ConceptType.I64
                        ? new ConceptExpr.LongConst(1L)
                        : new ConceptExpr.IntConst(1);
                ConceptExpr q = new ConceptExpr.BinaryOp(BinaryOperator.DIV, x, y, returnType);
                ConceptExpr rem = new ConceptExpr.BinaryOp(BinaryOperator.REM, x, y, returnType);
                ConceptExpr signsDiffer = new ConceptExpr.BinaryOp(
                        BinaryOperator.LT,
                        new ConceptExpr.BinaryOp(BinaryOperator.BIT_XOR, x, y, returnType),
                        zero,
                        ConceptType.BOOL);
                ConceptExpr nonZeroRem = new ConceptExpr.BinaryOp(BinaryOperator.NE, rem, zero, ConceptType.BOOL);
                ConceptExpr adjust = new ConceptExpr.BinaryOp(
                        BinaryOperator.LOGICAL_AND, signsDiffer, nonZeroRem, ConceptType.BOOL);
                return new ConceptExpr.Ternary(
                        adjust,
                        new ConceptExpr.BinaryOp(BinaryOperator.SUB, q, one, returnType),
                        q,
                        returnType);
            }
            if ("floorMod".equals(name) && args.size() == 2) {
                ConceptExpr x = args.get(0);
                ConceptExpr y = args.get(1).type() != x.type() && x.type() == ConceptType.I64
                        ? new ConceptExpr.Cast(ConceptType.I64, args.get(1))
                        : args.get(1);
                ConceptType workType = x.type() == ConceptType.I64 ? ConceptType.I64 : ConceptType.I32;
                ConceptExpr zero = defaultConstForType(workType);
                ConceptExpr rem = new ConceptExpr.BinaryOp(BinaryOperator.REM, x, y, workType);
                ConceptExpr signsDiffer = new ConceptExpr.BinaryOp(
                        BinaryOperator.LT,
                        new ConceptExpr.BinaryOp(BinaryOperator.BIT_XOR, x, y, workType),
                        zero,
                        ConceptType.BOOL);
                ConceptExpr nonZeroRem = new ConceptExpr.BinaryOp(BinaryOperator.NE, rem, zero, ConceptType.BOOL);
                ConceptExpr adjust = new ConceptExpr.BinaryOp(
                        BinaryOperator.LOGICAL_AND, signsDiffer, nonZeroRem, ConceptType.BOOL);
                ConceptExpr floored = new ConceptExpr.Ternary(
                        adjust,
                        new ConceptExpr.BinaryOp(BinaryOperator.ADD, rem, y, workType),
                        rem,
                        workType);
                return workType == returnType ? floored : new ConceptExpr.Cast(returnType, floored);
            }
            if ("clamp".equals(name) && args.size() == 3) {
                ConceptExpr maxLo = new ConceptExpr.MathIntrinsic(
                        MathIntrinsicKind.MAX, List.of(args.get(0), args.get(1)), returnType);
                return new ConceptExpr.MathIntrinsic(
                        MathIntrinsicKind.MIN, List.of(maxLo, args.get(2)), returnType);
            }
            if ("signum".equals(name) && args.size() == 1) {
                ConceptExpr zero = defaultConstForType(returnType);
                ConceptExpr posOne = returnType == ConceptType.F32
                        ? new ConceptExpr.FloatConst(1.0f)
                        : new ConceptExpr.DoubleConst(1.0);
                ConceptExpr negOne = returnType == ConceptType.F32
                        ? new ConceptExpr.FloatConst(-1.0f)
                        : new ConceptExpr.DoubleConst(-1.0);
                return new ConceptExpr.Ternary(
                        new ConceptExpr.BinaryOp(BinaryOperator.GT, args.get(0), zero, ConceptType.BOOL),
                        posOne,
                        new ConceptExpr.Ternary(
                                new ConceptExpr.BinaryOp(BinaryOperator.LT, args.get(0), zero, ConceptType.BOOL),
                                negOne,
                                zero,
                                returnType),
                        returnType);
            }
            MathIntrinsicKind kind = switch (name) {
                case "floor" -> MathIntrinsicKind.FLOOR;
                case "ceil" -> MathIntrinsicKind.CEIL;
                case "round", "rint" -> MathIntrinsicKind.ROUND;
                case "sqrt" -> MathIntrinsicKind.SQRT;
                case "abs" -> MathIntrinsicKind.ABS;
                case "sin" -> MathIntrinsicKind.SIN;
                case "cos" -> MathIntrinsicKind.COS;
                case "tan" -> MathIntrinsicKind.TAN;
                case "atan2" -> MathIntrinsicKind.ATAN2;
                case "exp" -> MathIntrinsicKind.EXP;
                case "log" -> MathIntrinsicKind.LOG;
                case "pow" -> MathIntrinsicKind.POW;
                case "min" -> MathIntrinsicKind.MIN;
                case "max" -> MathIntrinsicKind.MAX;
                case "fma" -> MathIntrinsicKind.FMA;
                default -> null;
            };
            if (kind != null) {
                return new ConceptExpr.MathIntrinsic(kind, args, returnType);
            }
        }
        if ("java/lang/Double".equals(owner)) {
            if (("doubleToRawLongBits".equals(name) || "doubleToLongBits".equals(name)) && args.size() == 1) {
                return new ConceptExpr.Cast(ConceptType.I64, args.get(0));
            }
            if ("longBitsToDouble".equals(name) && args.size() == 1) {
                return new ConceptExpr.Cast(ConceptType.F64, args.get(0));
            }
            if ("isFinite".equals(name) && args.size() == 1) {
                return new ConceptExpr.MathIntrinsic(MathIntrinsicKind.IS_FINITE, args, ConceptType.BOOL);
            }
            if ("isNaN".equals(name) && args.size() == 1) {
                return new ConceptExpr.MathIntrinsic(MathIntrinsicKind.IS_NAN, args, ConceptType.BOOL);
            }
        }
        if ("java/lang/Float".equals(owner)) {
            if (("floatToRawIntBits".equals(name) || "floatToIntBits".equals(name)) && args.size() == 1) {
                return new ConceptExpr.Cast(ConceptType.I32, args.get(0));
            }
            if ("intBitsToFloat".equals(name) && args.size() == 1) {
                return new ConceptExpr.Cast(ConceptType.F32, args.get(0));
            }
            if ("isFinite".equals(name) && args.size() == 1) {
                return new ConceptExpr.MathIntrinsic(MathIntrinsicKind.IS_FINITE, args, ConceptType.BOOL);
            }
            if ("isNaN".equals(name) && args.size() == 1) {
                return new ConceptExpr.MathIntrinsic(MathIntrinsicKind.IS_NAN, args, ConceptType.BOOL);
            }
        }
        if ("java/lang/Long".equals(owner) && args.size() == 1) {
            switch (name) {
                case "bitCount" -> {
                    return new ConceptExpr.MathIntrinsic(MathIntrinsicKind.POPCOUNT, args, ConceptType.I32);
                }
                case "numberOfLeadingZeros" -> {
                    return new ConceptExpr.MathIntrinsic(MathIntrinsicKind.CLZ, args, ConceptType.I32);
                }
                case "numberOfTrailingZeros" -> {
                    return new ConceptExpr.MathIntrinsic(MathIntrinsicKind.CTZ, args, ConceptType.I32);
                }
                default -> {
                }
            }
        }
        if ("java/lang/Integer".equals(owner) && args.size() == 1) {
            switch (name) {
                case "bitCount" -> {
                    return new ConceptExpr.MathIntrinsic(MathIntrinsicKind.POPCOUNT, args, ConceptType.I32);
                }
                case "numberOfLeadingZeros" -> {
                    return new ConceptExpr.MathIntrinsic(MathIntrinsicKind.CLZ, args, ConceptType.I32);
                }
                case "numberOfTrailingZeros" -> {
                    return new ConceptExpr.MathIntrinsic(MathIntrinsicKind.CTZ, args, ConceptType.I32);
                }
                default -> {
                }
            }
        }
        if ("java/lang/Long".equals(owner) && args.size() == 2) {
            if ("rotateLeft".equals(name)) {
                ConceptExpr sh = new ConceptExpr.BinaryOp(
                        BinaryOperator.BIT_AND, args.get(1), new ConceptExpr.IntConst(63), ConceptType.I32);
                ConceptExpr rsh = new ConceptExpr.BinaryOp(
                        BinaryOperator.BIT_AND,
                        new ConceptExpr.BinaryOp(BinaryOperator.SUB, new ConceptExpr.IntConst(64), sh, ConceptType.I32),
                        new ConceptExpr.IntConst(63),
                        ConceptType.I32);
                return new ConceptExpr.BinaryOp(
                        BinaryOperator.BIT_OR,
                        new ConceptExpr.BinaryOp(BinaryOperator.SHL, args.get(0), sh, ConceptType.I64),
                        new ConceptExpr.BinaryOp(BinaryOperator.USHR, args.get(0), rsh, ConceptType.I64),
                        ConceptType.I64);
            }
        }
        if ("java/lang/Integer".equals(owner) && args.size() == 2) {
            if ("rotateLeft".equals(name)) {
                ConceptExpr sh = new ConceptExpr.BinaryOp(
                        BinaryOperator.BIT_AND, args.get(1), new ConceptExpr.IntConst(31), ConceptType.I32);
                ConceptExpr rsh = new ConceptExpr.BinaryOp(
                        BinaryOperator.BIT_AND,
                        new ConceptExpr.BinaryOp(BinaryOperator.SUB, new ConceptExpr.IntConst(32), sh, ConceptType.I32),
                        new ConceptExpr.IntConst(31),
                        ConceptType.I32);
                return new ConceptExpr.BinaryOp(
                        BinaryOperator.BIT_OR,
                        new ConceptExpr.BinaryOp(BinaryOperator.SHL, args.get(0), sh, ConceptType.I32),
                        new ConceptExpr.BinaryOp(BinaryOperator.USHR, args.get(0), rsh, ConceptType.I32),
                        ConceptType.I32);
            }
        }
        if (args.isEmpty()) {
            switch (name) {
                case "intValue", "shortValue", "byteValue", "ordinal" -> {
                    return new ConceptExpr.IntConst(0);
                }
                case "longValue" -> {
                    return new ConceptExpr.LongConst(0L);
                }
                case "floatValue" -> {
                    return new ConceptExpr.FloatConst(0.0f);
                }
                case "doubleValue" -> {
                    return new ConceptExpr.DoubleConst(0.0);
                }
                default -> {
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Post-Dominator Structured Control-Flow Recovery (Zero-Goto AST)
    // -------------------------------------------------------------------------

    private static List<ConceptStmt> structureRegion(
            int currentPc,
            int stopPc,
            NavigableMap<Integer, BasicBlock> blocks,
            Map<Integer, Integer> ipdom,
            Map<Integer, Set<Integer>> forwardReachable,
            Map<Integer, Integer> loopHeaderToLatch,
            Set<Integer> activeLoopHeaders) {
        if (currentPc == stopPc || currentPc == EXIT_PC || currentPc < 0) {
            return List.of();
        }
        BasicBlock b = blocks.get(currentPc);
        if (b == null) {
            return List.of();
        }

        List<ConceptStmt> out = new ArrayList<>();

        // Handle natural loop header (while-style or do-while-style)
        if (loopHeaderToLatch.containsKey(currentPc) && !activeLoopHeaders.contains(currentPc)) {
            int latchPc = loopHeaderToLatch.get(currentPc);
            boolean jumpExitsLoop = b.branchCondition != null
                    && (b.branchTargetPc > latchPc || b.branchTargetPc < currentPc);
            boolean fallExitsLoop = b.branchCondition != null
                    && (b.fallthroughPc > latchPc || b.fallthroughPc < currentPc);
            if (b.branchCondition != null && (jumpExitsLoop || fallExitsLoop)) {
                activeLoopHeaders.add(currentPc);
                out.addAll(b.stmts);
                ConceptExpr whileCond = jumpExitsLoop
                        ? negateBooleanExpr(b.branchCondition)
                        : b.branchCondition;
                int bodyEntryPc = jumpExitsLoop ? b.fallthroughPc : b.branchTargetPc;
                int exitPc = jumpExitsLoop ? b.branchTargetPc : b.fallthroughPc;

                List<ConceptStmt> loopBody = new ArrayList<>();
                appendEdgePhis(loopBody, b, bodyEntryPc);
                if (bodyEntryPc > currentPc) {
                    loopBody.addAll(structureRegion(
                            bodyEntryPc,
                            currentPc,
                            blocks,
                            ipdom,
                            forwardReachable,
                            loopHeaderToLatch,
                            activeLoopHeaders));
                }
                out.add(new ConceptStmt.While(whileCond, loopBody));
                activeLoopHeaders.remove(currentPc);

                appendEdgePhis(out, b, exitPc);
                if (exitPc > currentPc) {
                    out.addAll(structureRegion(
                            exitPc,
                            stopPc,
                            blocks,
                            ipdom,
                            forwardReachable,
                            loopHeaderToLatch,
                            activeLoopHeaders));
                }
                return out;
            } else {
                // do-while loop where the loop exit branch is at latchPc rather than currentPc
                BasicBlock latchBlock = blocks.get(latchPc);
                if (latchBlock != null) {
                    activeLoopHeaders.add(currentPc);
                    ConceptExpr whileCond = new ConceptExpr.BoolConst(false);
                    int exitPc = EXIT_PC;
                    if (latchBlock.branchCondition != null) {
                        boolean latchJumpBack = latchBlock.branchTargetPc == currentPc;
                        whileCond = latchJumpBack
                                ? latchBlock.branchCondition
                                : negateBooleanExpr(latchBlock.branchCondition);
                        exitPc = latchJumpBack ? latchBlock.fallthroughPc : latchBlock.branchTargetPc;
                    } else if (latchBlock.endPc < EXIT_PC) {
                        Integer nextKey = blocks.higherKey(latchPc);
                        exitPc = nextKey != null ? nextKey : EXIT_PC;
                    }
                    List<ConceptStmt> bodyStmts = structureDoWhileBody(
                            currentPc,
                            latchPc,
                            exitPc,
                            blocks,
                            ipdom,
                            forwardReachable,
                            loopHeaderToLatch,
                            activeLoopHeaders);
                    out.addAll(bodyStmts);
                    out.add(new ConceptStmt.While(whileCond, bodyStmts));
                    activeLoopHeaders.remove(currentPc);
                    if (exitPc != EXIT_PC && exitPc > latchPc && exitPc != stopPc) {
                        out.addAll(structureRegion(
                                exitPc,
                                stopPc,
                                blocks,
                                ipdom,
                                forwardReachable,
                                loopHeaderToLatch,
                                activeLoopHeaders));
                    }
                    return out;
                }
            }
        }

        out.addAll(b.stmts);
        if (b.endsWithReturn) {
            return out;
        }

        // Handle tableswitch / lookupswitch as a structured If/Else chain
        if (b.switchKey != null && b.switchCases != null && !b.switchCases.isEmpty()) {
            int joinPc = ipdom.getOrDefault(currentPc, EXIT_PC);
            if (stopPc != EXIT_PC && (joinPc == EXIT_PC || joinPc > stopPc)) {
                joinPc = stopPc;
            }
            List<ConceptStmt> defaultBranch = new ArrayList<>();
            if (b.switchDefaultPc > currentPc && b.switchDefaultPc != joinPc) {
                appendEdgePhis(defaultBranch, b, b.switchDefaultPc);
                defaultBranch.addAll(structureRegion(
                        b.switchDefaultPc,
                        joinPc,
                        blocks,
                        ipdom,
                        forwardReachable,
                        loopHeaderToLatch,
                        activeLoopHeaders));
            }
            List<Map.Entry<Integer, Integer>> caseList = new ArrayList<>(b.switchCases.entrySet());
            List<ConceptStmt> currentElse = defaultBranch;
            for (int i = caseList.size() - 1; i >= 0; i--) {
                int matchVal = caseList.get(i).getKey();
                int targetPc = caseList.get(i).getValue();
                List<ConceptStmt> caseBody = new ArrayList<>();
                appendEdgePhis(caseBody, b, targetPc);
                if (targetPc > currentPc && targetPc != joinPc) {
                    caseBody.addAll(structureRegion(
                            targetPc,
                            joinPc,
                            blocks,
                            ipdom,
                            forwardReachable,
                            loopHeaderToLatch,
                            activeLoopHeaders));
                }
                ConceptExpr cond = new ConceptExpr.BinaryOp(
                        BinaryOperator.EQ,
                        b.switchKey,
                        new ConceptExpr.IntConst(matchVal),
                        ConceptType.BOOL);
                currentElse = List.of(new ConceptStmt.If(cond, caseBody, currentElse));
            }
            out.addAll(currentElse);
            if (joinPc > currentPc && joinPc != stopPc && joinPc != EXIT_PC) {
                out.addAll(structureRegion(
                        joinPc,
                        stopPc,
                        blocks,
                        ipdom,
                        forwardReachable,
                        loopHeaderToLatch,
                        activeLoopHeaders));
            }
            return out;
        }

        if (b.unconditionalTargetPc != -1) {
            appendEdgePhis(out, b, b.unconditionalTargetPc);
            if (b.unconditionalTargetPc > b.startPc && b.unconditionalTargetPc != stopPc) {
                out.addAll(structureRegion(
                        b.unconditionalTargetPc,
                        stopPc,
                        blocks,
                        ipdom,
                        forwardReachable,
                        loopHeaderToLatch,
                        activeLoopHeaders));
            }
            return out;
        }

        if (b.branchCondition == null) {
            if (b.fallthroughPc != -1) {
                appendEdgePhis(out, b, b.fallthroughPc);
                if (b.fallthroughPc > currentPc && b.fallthroughPc != stopPc) {
                    out.addAll(structureRegion(
                            b.fallthroughPc,
                            stopPc,
                            blocks,
                            ipdom,
                            forwardReachable,
                            loopHeaderToLatch,
                            activeLoopHeaders));
                }
            }
            return out;
        }

        int fallPc = b.fallthroughPc;
        int jumpPc = b.branchTargetPc;
        if (jumpPc <= currentPc) {
            appendEdgePhis(out, b, fallPc);
            if (fallPc > currentPc && fallPc != stopPc) {
                out.addAll(structureRegion(
                        fallPc,
                        stopPc,
                        blocks,
                        ipdom,
                        forwardReachable,
                        loopHeaderToLatch,
                        activeLoopHeaders));
            }
            return out;
        }
        if (fallPc <= currentPc) {
            appendEdgePhis(out, b, jumpPc);
            if (jumpPc > currentPc && jumpPc != stopPc) {
                out.addAll(structureRegion(
                        jumpPc,
                        stopPc,
                        blocks,
                        ipdom,
                        forwardReachable,
                        loopHeaderToLatch,
                        activeLoopHeaders));
            }
            return out;
        }

        int joinPc = ipdom.getOrDefault(currentPc, EXIT_PC);
        if (stopPc != EXIT_PC && (joinPc == EXIT_PC || joinPc > stopPc)) {
            if (canReach(forwardReachable, fallPc, stopPc) && canReach(forwardReachable, jumpPc, stopPc)) {
                joinPc = stopPc;
            }
        }

        if (joinPc == EXIT_PC) {
            // Early-return / non-reconverging guard
            boolean fallReachesJump = canReach(forwardReachable, fallPc, jumpPc);
            boolean jumpReachesFall = canReach(forwardReachable, jumpPc, fallPc);
            int fallWeight = forwardReachable.getOrDefault(fallPc, Set.of()).size();
            int jumpWeight = forwardReachable.getOrDefault(jumpPc, Set.of()).size();

            boolean inlineJumpAsGuard = fallReachesJump || (!jumpReachesFall && jumpWeight <= fallWeight);
            if (inlineJumpAsGuard) {
                List<ConceptStmt> guardBody = new ArrayList<>();
                appendEdgePhis(guardBody, b, jumpPc);
                if (jumpPc > currentPc) {
                    guardBody.addAll(structureRegion(
                            jumpPc,
                            EXIT_PC,
                            blocks,
                            ipdom,
                            forwardReachable,
                            loopHeaderToLatch,
                            activeLoopHeaders));
                }
                out.add(new ConceptStmt.If(b.branchCondition, guardBody, List.of()));
                appendEdgePhis(out, b, fallPc);
                if (fallPc > currentPc) {
                    out.addAll(structureRegion(
                            fallPc,
                            stopPc,
                            blocks,
                            ipdom,
                            forwardReachable,
                            loopHeaderToLatch,
                            activeLoopHeaders));
                }
            } else {
                List<ConceptStmt> guardBody = new ArrayList<>();
                appendEdgePhis(guardBody, b, fallPc);
                if (fallPc > currentPc) {
                    guardBody.addAll(structureRegion(
                            fallPc,
                            EXIT_PC,
                            blocks,
                            ipdom,
                            forwardReachable,
                            loopHeaderToLatch,
                            activeLoopHeaders));
                }
                out.add(new ConceptStmt.If(negateBooleanExpr(b.branchCondition), guardBody, List.of()));
                appendEdgePhis(out, b, jumpPc);
                if (jumpPc > currentPc) {
                    out.addAll(structureRegion(
                            jumpPc,
                            stopPc,
                            blocks,
                            ipdom,
                            forwardReachable,
                            loopHeaderToLatch,
                            activeLoopHeaders));
                }
            }
            return out;
        }

        if (jumpPc == joinPc) {
            List<ConceptStmt> thenBody = new ArrayList<>();
            appendEdgePhis(thenBody, b, fallPc);
            if (fallPc > currentPc) {
                thenBody.addAll(structureRegion(
                        fallPc,
                        joinPc,
                        blocks,
                        ipdom,
                        forwardReachable,
                        loopHeaderToLatch,
                        activeLoopHeaders));
            }
            List<ConceptStmt> elseBody = new ArrayList<>();
            appendEdgePhis(elseBody, b, jumpPc);
            out.add(new ConceptStmt.If(negateBooleanExpr(b.branchCondition), thenBody, elseBody));
        } else if (fallPc == joinPc) {
            List<ConceptStmt> thenBody = new ArrayList<>();
            appendEdgePhis(thenBody, b, jumpPc);
            if (jumpPc > currentPc) {
                thenBody.addAll(structureRegion(
                        jumpPc,
                        joinPc,
                        blocks,
                        ipdom,
                        forwardReachable,
                        loopHeaderToLatch,
                        activeLoopHeaders));
            }
            List<ConceptStmt> elseBody = new ArrayList<>();
            appendEdgePhis(elseBody, b, fallPc);
            out.add(new ConceptStmt.If(b.branchCondition, thenBody, elseBody));
        } else {
            // Standard if/else diamond reconverging at joinPc
            List<ConceptStmt> fallBody = new ArrayList<>();
            appendEdgePhis(fallBody, b, fallPc);
            if (fallPc > currentPc) {
                fallBody.addAll(structureRegion(
                        fallPc,
                        joinPc,
                        blocks,
                        ipdom,
                        forwardReachable,
                        loopHeaderToLatch,
                        activeLoopHeaders));
            }

            List<ConceptStmt> jumpBody = new ArrayList<>();
            appendEdgePhis(jumpBody, b, jumpPc);
            if (jumpPc > currentPc) {
                jumpBody.addAll(structureRegion(
                        jumpPc,
                        joinPc,
                        blocks,
                        ipdom,
                        forwardReachable,
                        loopHeaderToLatch,
                        activeLoopHeaders));
            }

            out.add(new ConceptStmt.If(negateBooleanExpr(b.branchCondition), fallBody, jumpBody));
        }

        if (joinPc > currentPc && joinPc != stopPc) {
            out.addAll(structureRegion(
                    joinPc,
                    stopPc,
                    blocks,
                    ipdom,
                    forwardReachable,
                    loopHeaderToLatch,
                    activeLoopHeaders));
        }
        return out;
    }

    private static List<ConceptStmt> structureDoWhileBody(
            int headerPc,
            int latchPc,
            int exitPc,
            NavigableMap<Integer, BasicBlock> blocks,
            Map<Integer, Integer> ipdom,
            Map<Integer, Set<Integer>> forwardReachable,
            Map<Integer, Integer> loopHeaderToLatch,
            Set<Integer> activeLoopHeaders) {
        List<ConceptStmt> body = new ArrayList<>();
        BasicBlock header = blocks.get(headerPc);
        if (header == null) {
            return body;
        }
        body.addAll(header.stmts);
        if (headerPc == latchPc || header.endsWithReturn) {
            return body;
        }
        int nextPc = header.fallthroughPc > headerPc && header.fallthroughPc <= latchPc
                ? header.fallthroughPc
                : (header.branchTargetPc > headerPc && header.branchTargetPc <= latchPc
                        ? header.branchTargetPc
                        : -1);
        if (nextPc != -1) {
            appendEdgePhis(body, header, nextPc);
            body.addAll(structureRegion(
                    nextPc,
                    exitPc,
                    blocks,
                    ipdom,
                    forwardReachable,
                    loopHeaderToLatch,
                    activeLoopHeaders));
        }
        return body;
    }

    private static void appendEdgePhis(List<ConceptStmt> target, BasicBlock from, int toPc) {
        List<ConceptStmt> phis = from.edgePhiAssigns.get(toPc);
        if (phis != null) {
            target.addAll(phis);
        }
    }

    static Map<Integer, Set<Integer>> computeForwardReachability(
            NavigableMap<Integer, BasicBlock> blocks) {
        Map<Integer, Set<Integer>> reach = new HashMap<>();
        List<Integer> keys = new ArrayList<>(blocks.keySet());
        for (int i = keys.size() - 1; i >= 0; i--) {
            int pc = keys.get(i);
            BasicBlock b = blocks.get(pc);
            Set<Integer> r = new HashSet<>();
            r.add(pc);
            if (b.endsWithReturn) {
                r.add(EXIT_PC);
            }
            for (int succ : b.successors) {
                if (succ > pc) {
                    r.add(succ);
                    Set<Integer> sr = reach.get(succ);
                    if (sr != null) {
                        r.addAll(sr);
                    }
                }
            }
            reach.put(pc, r);
        }
        return reach;
    }

    static boolean canReach(Map<Integer, Set<Integer>> reach, int fromPc, int targetPc) {
        if (fromPc == targetPc) {
            return true;
        }
        Set<Integer> set = reach.get(fromPc);
        return set != null && set.contains(targetPc);
    }

    static Map<Integer, Integer> computeImmediatePostDominators(
            NavigableMap<Integer, BasicBlock> blocks) {
        List<Integer> nodes = new ArrayList<>(blocks.keySet());
        nodes.add(EXIT_PC);
        Set<Integer> allNodes = new HashSet<>(nodes);

        Map<Integer, Set<Integer>> pdom = new HashMap<>();
        for (int n : nodes) {
            if (n == EXIT_PC) {
                pdom.put(n, Set.of(EXIT_PC));
            } else {
                pdom.put(n, new HashSet<>(allNodes));
            }
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = nodes.size() - 2; i >= 0; i--) {
                int u = nodes.get(i);
                BasicBlock b = blocks.get(u);
                List<Integer> forwardSuccs = new ArrayList<>();
                if (b.endsWithReturn) {
                    forwardSuccs.add(EXIT_PC);
                } else {
                    for (int s : b.successors) {
                        if (s > u) {
                            forwardSuccs.add(s);
                        }
                    }
                }
                if (forwardSuccs.isEmpty()) {
                    forwardSuccs.add(EXIT_PC);
                }

                Set<Integer> nextSet = new HashSet<>(pdom.get(forwardSuccs.get(0)));
                for (int k = 1; k < forwardSuccs.size(); k++) {
                    nextSet.retainAll(pdom.get(forwardSuccs.get(k)));
                }
                nextSet.add(u);
                if (!nextSet.equals(pdom.get(u))) {
                    pdom.put(u, nextSet);
                    changed = true;
                }
            }
        }

        Map<Integer, Integer> ipdom = new HashMap<>();
        for (int u : blocks.keySet()) {
            Set<Integer> strictPdoms = new HashSet<>(pdom.get(u));
            strictPdoms.remove(u);
            int best = EXIT_PC;
            int bestSize = Integer.MAX_VALUE;
            for (int candidate : strictPdoms) {
                // Immediate post-dominator is the strict post-dominator with the smallest pdom set
                // (or largest pdom set? Wait: if candidate is closest to u, every other strict
                // post-dominator of u also post-dominates candidate! So pdom(candidate) CONTAINS all
                // other strict post-dominators of u, meaning pdom(candidate).size() is MAXIMUM!).
                int size = pdom.getOrDefault(candidate, Set.of()).size();
                if (size > bestSize || bestSize == Integer.MAX_VALUE) {
                    bestSize = size;
                    best = candidate;
                }
            }
            ipdom.put(u, best);
        }
        return ipdom;
    }

    public static ConceptExpr negateBooleanExpr(ConceptExpr expr) {
        if (expr instanceof ConceptExpr.UnaryOp u && u.op() == UnaryOperator.LOGICAL_NOT) {
            return u.operand();
        }
        if (expr instanceof ConceptExpr.BinaryOp bin && bin.op().isComparison()
                && bin.op() != BinaryOperator.LOGICAL_AND
                && bin.op() != BinaryOperator.LOGICAL_OR) {
            return new ConceptExpr.BinaryOp(
                    bin.op().negateComparison(),
                    bin.left(),
                    bin.right(),
                    ConceptType.BOOL);
        }
        return new ConceptExpr.UnaryOp(UnaryOperator.LOGICAL_NOT, expr, ConceptType.BOOL);
    }

    private static boolean isSimpleValue(ConceptExpr expr) {
        return expr instanceof ConceptExpr.Var
                || expr instanceof ConceptExpr.IntConst
                || expr instanceof ConceptExpr.LongConst
                || expr instanceof ConceptExpr.FloatConst
                || expr instanceof ConceptExpr.DoubleConst
                || expr instanceof ConceptExpr.BoolConst;
    }

    static ConceptExpr defaultConstForType(ConceptType type) {
        return switch (type) {
            case BOOL -> new ConceptExpr.BoolConst(false);
            case I32 -> new ConceptExpr.IntConst(0);
            case I64 -> new ConceptExpr.LongConst(0L);
            case F32 -> new ConceptExpr.FloatConst(0.0f);
            default -> new ConceptExpr.DoubleConst(0.0);
        };
    }

    static ConceptType typeForArithOpcode(int offset) {
        return switch (offset) {
            case 0 -> ConceptType.I32;
            case 1 -> ConceptType.I64;
            case 2 -> ConceptType.F32;
            default -> ConceptType.F64;
        };
    }

    private static void pushBinary(Deque<ConceptExpr> stack, BinaryOperator op, ConceptType resultType) {
        ConceptExpr right = popSafe(stack, resultType);
        ConceptExpr left = popSafe(stack, resultType);
        stack.addLast(new ConceptExpr.BinaryOp(op, left, right, resultType));
    }

    private static ConceptExpr.Var loadVar(
            RawMethod method,
            Map<Integer, String> slotNames,
            Map<String, ConceptType> localTypes,
            int slot,
            int pc,
            ConceptType type) {
        String name = resolveSlotName(method, slotNames, slot, pc, type);
        localTypes.putIfAbsent(name, type);
        return new ConceptExpr.Var(name, type);
    }

    private static void storeVar(
            BasicBlock block,
            RawMethod method,
            Map<Integer, String> slotNames,
            Map<String, ConceptType> localTypes,
            int slot,
            int pc,
            ConceptType type,
            ConceptExpr value) {
        String name = resolveSlotName(method, slotNames, slot, pc + instructionLength(method.code, pc), type);
        localTypes.putIfAbsent(name, type);
        block.stmts.add(new ConceptStmt.Assign(name, type, value));
    }

    private static String resolveSlotName(
            RawMethod method,
            Map<Integer, String> slotNames,
            int slot,
            int pc,
            ConceptType type) {
        String existing = slotNames.get(slot);
        if (existing != null) {
            return existing;
        }
        for (LocalVarEntry entry : method.localVariables) {
            if (entry.slot == slot && pc >= entry.startPc && pc <= entry.startPc + entry.length) {
                String sanitized = sanitizeIdentifier(entry.name);
                return sanitized;
            }
        }
        String fallback = "v" + slot + "_" + type.name().toLowerCase();
        return fallback;
    }

    static String sanitizeIdentifier(String raw) {
        if (raw == null || raw.isEmpty() || "this".equals(raw)) {
            return "self_val";
        }
        return raw.replace('$', '_');
    }

    static NavigableMap<Integer, BasicBlock> buildCfgBlocks(byte[] code) {
        TreeSet<Integer> leaders = new TreeSet<>();
        leaders.add(0);
        int pc = 0;
        while (pc < code.length) {
            int opcode = code[pc] & 0xFF;
            int len = instructionLength(code, pc);
            int nextPc = pc + len;
            if (isConditionalBranch(opcode) || opcode == 0xA7) {
                int target = pc + readSignedShort(code, pc + 1);
                leaders.add(target);
                if (nextPc < code.length) {
                    leaders.add(nextPc);
                }
            } else if (opcode == 0xC8) {
                int target = pc + readSignedInt(code, pc + 1);
                leaders.add(target);
                if (nextPc < code.length) {
                    leaders.add(nextPc);
                }
            } else if (opcode == 0xAA) {
                int aligned = (pc + 4) & ~3;
                int defTarget = pc + readSignedInt(code, aligned);
                int low = readSignedInt(code, aligned + 4);
                int high = readSignedInt(code, aligned + 8);
                leaders.add(defTarget);
                for (int k = 0; k <= high - low; k++) {
                    leaders.add(pc + readSignedInt(code, aligned + 12 + k * 4));
                }
                if (nextPc < code.length) {
                    leaders.add(nextPc);
                }
            } else if (opcode == 0xAB) {
                int aligned = (pc + 4) & ~3;
                int defTarget = pc + readSignedInt(code, aligned);
                int npairs = readSignedInt(code, aligned + 4);
                leaders.add(defTarget);
                for (int k = 0; k < npairs; k++) {
                    leaders.add(pc + readSignedInt(code, aligned + 12 + k * 8));
                }
                if (nextPc < code.length) {
                    leaders.add(nextPc);
                }
            } else if (isReturnOrThrow(opcode)) {
                if (nextPc < code.length) {
                    leaders.add(nextPc);
                }
            }
            pc = nextPc;
        }

        List<Integer> leaderList = new ArrayList<>(leaders);
        NavigableMap<Integer, BasicBlock> blocks = new TreeMap<>();
        for (int i = 0; i < leaderList.size(); i++) {
            int start = leaderList.get(i);
            int end = (i + 1 < leaderList.size()) ? leaderList.get(i + 1) : code.length;
            blocks.put(start, new BasicBlock(start, end));
        }

        for (BasicBlock block : blocks.values()) {
            int lastPc = findLastInsnPc(code, block.startPc, block.endPc);
            block.lastInsnPc = lastPc;
            int opcode = code[lastPc] & 0xFF;
            if (isConditionalBranch(opcode)) {
                int target = lastPc + readSignedShort(code, lastPc + 1);
                int fallthrough = block.endPc;
                block.branchTargetPc = target;
                block.fallthroughPc = fallthrough;
                addCfgEdge(blocks, block, fallthrough);
                addCfgEdge(blocks, block, target);
            } else if (opcode == 0xA7 || opcode == 0xC8) {
                int target = opcode == 0xA7
                        ? lastPc + readSignedShort(code, lastPc + 1)
                        : lastPc + readSignedInt(code, lastPc + 1);
                block.unconditionalTargetPc = target;
                addCfgEdge(blocks, block, target);
            } else if (opcode == 0xAA) {
                int aligned = (lastPc + 4) & ~3;
                int defTarget = lastPc + readSignedInt(code, aligned);
                int low = readSignedInt(code, aligned + 4);
                int high = readSignedInt(code, aligned + 8);
                block.switchDefaultPc = defTarget;
                block.switchCases = new LinkedHashMap<>();
                for (int k = 0; k <= high - low; k++) {
                    int t = lastPc + readSignedInt(code, aligned + 12 + k * 4);
                    block.switchCases.put(low + k, t);
                    addCfgEdge(blocks, block, t);
                }
                addCfgEdge(blocks, block, defTarget);
            } else if (opcode == 0xAB) {
                int aligned = (lastPc + 4) & ~3;
                int defTarget = lastPc + readSignedInt(code, aligned);
                int npairs = readSignedInt(code, aligned + 4);
                block.switchDefaultPc = defTarget;
                block.switchCases = new LinkedHashMap<>();
                for (int k = 0; k < npairs; k++) {
                    int matchVal = readSignedInt(code, aligned + 8 + k * 8);
                    int t = lastPc + readSignedInt(code, aligned + 12 + k * 8);
                    block.switchCases.put(matchVal, t);
                    addCfgEdge(blocks, block, t);
                }
                addCfgEdge(blocks, block, defTarget);
            } else if (isReturnOrThrow(opcode)) {
                block.endsWithReturn = true;
            } else if (block.endPc < code.length) {
                block.fallthroughPc = block.endPc;
                addCfgEdge(blocks, block, block.endPc);
            }
        }
        return blocks;
    }

    static final class BasicBlock {
        final int startPc;
        final int endPc;
        int lastInsnPc;
        int fallthroughPc = -1;
        int branchTargetPc = -1;
        int unconditionalTargetPc = -1;
        boolean endsWithReturn;
        boolean simulated;

        final List<Integer> successors = new ArrayList<>();
        final List<Integer> predecessors = new ArrayList<>();
        List<ConceptExpr> entryStack;
        List<ConceptExpr> exitStack = List.of();
        final List<ConceptStmt> stmts = new ArrayList<>();
        final Map<Integer, List<ConceptStmt>> edgePhiAssigns = new HashMap<>();
        ConceptExpr branchCondition;
        ConceptExpr switchKey;
        Map<Integer, Integer> switchCases;
        int switchDefaultPc = -1;

        BasicBlock(int startPc, int endPc) {
            this.startPc = startPc;
            this.endPc = endPc;
        }
    }

    private static void addCfgEdge(
            NavigableMap<Integer, BasicBlock> blocks,
            BasicBlock from,
            int targetPc) {
        if (!from.successors.contains(targetPc)) {
            from.successors.add(targetPc);
        }
        BasicBlock to = blocks.get(targetPc);
        if (to != null && !to.predecessors.contains(from.startPc)) {
            to.predecessors.add(from.startPc);
        }
    }

    private static int findLastInsnPc(byte[] code, int startPc, int endPc) {
        int pc = startPc;
        int last = startPc;
        while (pc < endPc) {
            last = pc;
            pc += instructionLength(code, pc);
        }
        return last;
    }

    static boolean isConditionalBranch(int opcode) {
        return (opcode >= 0x99 && opcode <= 0xA6) || opcode == 0xC6 || opcode == 0xC7;
    }

    static boolean isReturnOrThrow(int opcode) {
        return (opcode >= 0xAC && opcode <= 0xB1) || opcode == 0xBF;
    }

    // -------------------------------------------------------------------------
    // Method Descriptor & Constant Pool Decoding
    // -------------------------------------------------------------------------

    static List<ConceptParam> parseMethodParameters(RawMethod method) {
        List<ConceptType> types = parseParameterTypes(method.descriptor);
        List<ConceptParam> params = new ArrayList<>(types.size());
        int slot = (method.accessFlags & ACC_STATIC) != 0 ? 0 : 1;
        for (int i = 0; i < types.size(); i++) {
            ConceptType t = types.get(i);
            String name = null;
            for (LocalVarEntry entry : method.localVariables) {
                if (entry.slot == slot && entry.startPc == 0) {
                    name = sanitizeIdentifier(entry.name);
                    break;
                }
            }
            if (name == null) {
                name = "p" + i;
            }
            params.add(new ConceptParam(name, t, slot));
            slot += t.isWideJvmSlot() ? 2 : 1;
        }
        return params;
    }

    public static List<ConceptType> parseParameterTypes(String descriptor) {
        List<ConceptType> types = new ArrayList<>();
        int idx = 1; // skip '('
        while (idx < descriptor.length() && descriptor.charAt(idx) != ')') {
            char c = descriptor.charAt(idx);
            if (c == 'L') {
                int semi = descriptor.indexOf(';', idx);
                types.add(ConceptType.I64);
                idx = semi + 1;
            } else if (c == '[') {
                int arrStart = idx;
                while (idx < descriptor.length() && descriptor.charAt(idx) == '[') {
                    idx++;
                }
                if (idx < descriptor.length() && descriptor.charAt(idx) == 'L') {
                    idx = descriptor.indexOf(';', idx) + 1;
                } else {
                    idx++;
                }
                types.add(ConceptType.fromDescriptorPrefix(descriptor.substring(arrStart, idx)));
            } else {
                types.add(ConceptType.fromDescriptorPrefix(String.valueOf(c)));
                idx++;
            }
        }
        return types;
    }

    public static ConceptType parseReturnType(String descriptor) {
        int close = descriptor.indexOf(')');
        if (close < 0 || close + 1 >= descriptor.length()) {
            return ConceptType.VOID;
        }
        return ConceptType.fromDescriptorPrefix(descriptor.substring(close + 1));
    }

    static ConceptExpr constantToExpr(RawClassFile cls, int cpIdx) {
        Object val = resolveConstantValue(cls, cpIdx);
        if (val instanceof Integer i) {
            return new ConceptExpr.IntConst(i);
        }
        if (val instanceof Long l) {
            return new ConceptExpr.LongConst(l);
        }
        if (val instanceof Float f) {
            return new ConceptExpr.FloatConst(f);
        }
        if (val instanceof Double d) {
            return new ConceptExpr.DoubleConst(d);
        }
        return new ConceptExpr.IntConst(0);
    }

    static Object resolveConstantValue(RawClassFile cls, int cpIdx) {
        if (cpIdx <= 0 || cpIdx >= cls.cpConstantValues.length) {
            return 0;
        }
        return cls.cpConstantValues[cpIdx];
    }

    public static int instructionLength(byte[] code, int pc) {
        int opcode = code[pc] & 0xFF;
        return switch (opcode) {
            case 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19,
                    0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC -> 2;
            case 0x11, 0x13, 0x14, 0x84,
                    0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E,
                    0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6,
                    0xA7, 0xA8, 0xB2, 0xB3, 0xB4, 0xB5,
                    0xB6, 0xB7, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7 -> 3;
            case 0xC5 -> 4;
            case 0xB9, 0xBA, 0xC8, 0xC9 -> 5;
            case 0xC4 -> (code[pc + 1] & 0xFF) == 0x84 ? 6 : 4;
            case 0xAA -> {
                int aligned = (pc + 4) & ~3;
                int low = readSignedInt(code, aligned + 4);
                int high = readSignedInt(code, aligned + 8);
                yield (aligned - pc) + 12 + (high - low + 1) * 4;
            }
            case 0xAB -> {
                int aligned = (pc + 4) & ~3;
                int npairs = readSignedInt(code, aligned + 4);
                yield (aligned - pc) + 8 + npairs * 8;
            }
            default -> 1;
        };
    }

    static int readUnsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    static short readSignedShort(byte[] bytes, int offset) {
        return (short) (((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF));
    }

    static int readSignedInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    record FieldRef(String owner, String name, String descriptor, int accessFlags) {
        FieldRef(String owner, String name, String descriptor) {
            this(owner, name, descriptor, 0);
        }
    }

    record MethodRef(String owner, String name, String descriptor) {
    }

    record MethodHandleEntry(int referenceKind, int referenceIndex) {
    }

    record InvokeDynamicEntry(int bootstrapMethodAttrIndex, String name, String descriptor) {
    }

    record BootstrapMethodEntry(int methodHandleCpIdx, int[] argumentCpIndices) {
    }

    record LocalVarEntry(int startPc, int length, String name, String descriptor, int slot) {
    }

    record RawMethod(
            int accessFlags,
            String name,
            String descriptor,
            int maxStack,
            int maxLocals,
            byte[] code,
            List<LocalVarEntry> localVariables
    ) {
    }

    static final class RawClassFile {
        final String thisClass;
        final String superClass;
        final List<String> interfaces;
        final String[] utf8Pool;
        final Object[] cpConstantValues;
        final int[] classNameIdx;
        final int[] refClassIdx;
        final int[] refNatIdx;
        final int[] natNameIdx;
        final int[] natDescIdx;
        final List<FieldRef> declaredFields;
        final List<RawMethod> methods;
        final Map<String, ConceptExpr> fieldConstants;
        final MethodHandleEntry[] methodHandles;
        final int[] indyBsmIdx;
        final int[] indyNatIdx;
        final List<BootstrapMethodEntry> bootstrapMethods;

        RawClassFile(
                String thisClass,
                String superClass,
                List<String> interfaces,
                String[] utf8Pool,
                Object[] cpConstantValues,
                int[] classNameIdx,
                int[] refClassIdx,
                int[] refNatIdx,
                int[] natNameIdx,
                int[] natDescIdx,
                List<FieldRef> declaredFields,
                List<RawMethod> methods,
                Map<String, ConceptExpr> fieldConstants,
                MethodHandleEntry[] methodHandles,
                int[] indyBsmIdx,
                int[] indyNatIdx,
                List<BootstrapMethodEntry> bootstrapMethods) {
            this.thisClass = thisClass;
            this.superClass = superClass;
            this.interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
            this.utf8Pool = utf8Pool;
            this.cpConstantValues = cpConstantValues;
            this.classNameIdx = classNameIdx;
            this.refClassIdx = refClassIdx;
            this.refNatIdx = refNatIdx;
            this.natNameIdx = natNameIdx;
            this.natDescIdx = natDescIdx;
            this.declaredFields = declaredFields;
            this.methods = methods;
            this.fieldConstants = fieldConstants;
            this.methodHandles = methodHandles;
            this.indyBsmIdx = indyBsmIdx;
            this.indyNatIdx = indyNatIdx;
            this.bootstrapMethods = bootstrapMethods == null ? List.of() : List.copyOf(bootstrapMethods);
        }

        FieldRef resolveFieldRef(int cpIdx) {
            String owner = utf8Pool[classNameIdx[refClassIdx[cpIdx]]];
            int nat = refNatIdx[cpIdx];
            return new FieldRef(owner, utf8Pool[natNameIdx[nat]], utf8Pool[natDescIdx[nat]]);
        }

        MethodRef resolveMethodRef(int cpIdx) {
            String owner = utf8Pool[classNameIdx[refClassIdx[cpIdx]]];
            int nat = refNatIdx[cpIdx];
            return new MethodRef(owner, utf8Pool[natNameIdx[nat]], utf8Pool[natDescIdx[nat]]);
        }

        InvokeDynamicEntry resolveInvokeDynamic(int cpIdx) {
            if (indyBsmIdx == null || cpIdx <= 0 || cpIdx >= indyBsmIdx.length) {
                return null;
            }
            int nat = indyNatIdx[cpIdx];
            if (nat <= 0 || nat >= natNameIdx.length) {
                return null;
            }
            return new InvokeDynamicEntry(indyBsmIdx[cpIdx], utf8Pool[natNameIdx[nat]], utf8Pool[natDescIdx[nat]]);
        }

        String resolveClassName(int cpIdx) {
            if (cpIdx <= 0 || cpIdx >= classNameIdx.length) {
                return null;
            }
            int uIdx = classNameIdx[cpIdx];
            return (uIdx > 0 && uIdx < utf8Pool.length) ? utf8Pool[uIdx] : null;
        }
    }

    static RawClassFile parseClassBytes(byte[] classBytes) {
        if (classBytes == null || classBytes.length < 24) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            if (in.readInt() != CLASS_MAGIC) {
                return null;
            }
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int cpCount = in.readUnsignedShort();
            if (cpCount <= 1 || cpCount > 65535) {
                return null;
            }
            String[] utf8Pool = new String[cpCount];
            Object[] cpVals = new Object[cpCount];
            int[] stringUtf8Idx = new int[cpCount];
            int[] classNameIdx = new int[cpCount];
            int[] refClassIdx = new int[cpCount];
            int[] refNatIdx = new int[cpCount];
            int[] natNameIdx = new int[cpCount];
            int[] natDescIdx = new int[cpCount];
            MethodHandleEntry[] methodHandles = new MethodHandleEntry[cpCount];
            int[] indyBsmIdx = new int[cpCount];
            int[] indyNatIdx = new int[cpCount];

            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case CONSTANT_Utf8 -> utf8Pool[i] = in.readUTF();
                    case CONSTANT_Integer -> cpVals[i] = in.readInt();
                    case CONSTANT_Float -> cpVals[i] = in.readFloat();
                    case CONSTANT_Long -> {
                        cpVals[i] = in.readLong();
                        i++;
                    }
                    case CONSTANT_Double -> {
                        cpVals[i] = in.readDouble();
                        i++;
                    }
                    case CONSTANT_Class -> classNameIdx[i] = in.readUnsignedShort();
                    case CONSTANT_String -> stringUtf8Idx[i] = in.readUnsignedShort();
                    case CONSTANT_MethodType, CONSTANT_Module, CONSTANT_Package ->
                            in.skipBytes(2);
                    case CONSTANT_Fieldref, CONSTANT_Methodref, CONSTANT_InterfaceMethodref -> {
                        refClassIdx[i] = in.readUnsignedShort();
                        refNatIdx[i] = in.readUnsignedShort();
                    }
                    case CONSTANT_NameAndType -> {
                        natNameIdx[i] = in.readUnsignedShort();
                        natDescIdx[i] = in.readUnsignedShort();
                    }
                    case CONSTANT_MethodHandle -> {
                        int refKind = in.readUnsignedByte();
                        int refIdx = in.readUnsignedShort();
                        methodHandles[i] = new MethodHandleEntry(refKind, refIdx);
                    }
                    case CONSTANT_Dynamic, CONSTANT_InvokeDynamic -> {
                        indyBsmIdx[i] = in.readUnsignedShort();
                        indyNatIdx[i] = in.readUnsignedShort();
                    }
                    default -> {
                        return null;
                    }
                }
            }
            for (int i = 1; i < cpCount; i++) {
                int sIdx = stringUtf8Idx[i];
                if (sIdx > 0 && sIdx < cpCount && utf8Pool[sIdx] != null) {
                    cpVals[i] = utf8Pool[sIdx];
                }
            }

            in.readUnsignedShort(); // access_flags
            int thisClassIdx = in.readUnsignedShort();
            String thisClass = utf8Pool[classNameIdx[thisClassIdx]];
            int superClassIdx = in.readUnsignedShort(); // super_class
            String superClass = (superClassIdx > 0 && superClassIdx < classNameIdx.length
                    && classNameIdx[superClassIdx] > 0)
                    ? utf8Pool[classNameIdx[superClassIdx]]
                    : null;
            int ifacesCount = in.readUnsignedShort();
            List<String> interfaces = new ArrayList<>(ifacesCount);
            for (int i = 0; i < ifacesCount; i++) {
                int ifaceIdx = in.readUnsignedShort();
                if (ifaceIdx > 0 && ifaceIdx < classNameIdx.length && classNameIdx[ifaceIdx] > 0) {
                    String ifaceName = utf8Pool[classNameIdx[ifaceIdx]];
                    if (ifaceName != null) {
                        interfaces.add(ifaceName);
                    }
                }
            }

            Map<String, ConceptExpr> fieldConstants = new LinkedHashMap<>();
            int fieldsCount = in.readUnsignedShort();
            List<FieldRef> declaredFields = new ArrayList<>(fieldsCount);
            int enumOrdinal = 0;
            for (int f = 0; f < fieldsCount; f++) {
                int fFlags = in.readUnsignedShort(); // fFlags
                String fName = utf8Pool[in.readUnsignedShort()];
                String fDesc = utf8Pool[in.readUnsignedShort()]; // fDesc
                if (fName != null && fDesc != null) {
                    declaredFields.add(new FieldRef(thisClass, fName, fDesc, fFlags));
                    if ((fFlags & 0x4000) != 0) { // ACC_ENUM
                        fieldConstants.put(thisClass + "#" + fName, new ConceptExpr.IntConst(enumOrdinal++));
                    }
                }
                int fAttrs = in.readUnsignedShort();
                for (int a = 0; a < fAttrs; a++) {
                    String aName = utf8Pool[in.readUnsignedShort()];
                    int len = in.readInt();
                    if ("ConstantValue".equals(aName) && len == 2) {
                        int cpIdx = in.readUnsignedShort();
                        if (cpIdx > 0 && cpIdx < cpVals.length && cpVals[cpIdx] != null) {
                            ConceptExpr ce = primitiveToConstExpr(cpVals[cpIdx]);
                            if (ce != null && fName != null) {
                                fieldConstants.put(thisClass + "#" + fName, ce);
                                fieldConstants.putIfAbsent(fName, ce);
                            }
                        }
                    } else {
                        in.skipBytes(len);
                    }
                }
            }

            int methodsCount = in.readUnsignedShort();
            List<RawMethod> methods = new ArrayList<>(methodsCount);
            for (int m = 0; m < methodsCount; m++) {
                int mFlags = in.readUnsignedShort();
                String mName = utf8Pool[in.readUnsignedShort()];
                String mDesc = utf8Pool[in.readUnsignedShort()];
                int mAttrs = in.readUnsignedShort();
                byte[] code = null;
                int maxStack = 0;
                int maxLocals = 0;
                List<LocalVarEntry> localVars = new ArrayList<>();

                for (int a = 0; a < mAttrs; a++) {
                    String attrName = utf8Pool[in.readUnsignedShort()];
                    int attrLen = in.readInt();
                    if ("Code".equals(attrName)) {
                        maxStack = in.readUnsignedShort();
                        maxLocals = in.readUnsignedShort();
                        int codeLen = in.readInt();
                        if (codeLen <= 0 || codeLen > 65536) {
                            return null;
                        }
                        code = new byte[codeLen];
                        in.readFully(code);
                        int excLen = in.readUnsignedShort();
                        in.skipBytes(excLen * 8);
                        int subAttrs = in.readUnsignedShort();
                        for (int sa = 0; sa < subAttrs; sa++) {
                            String subName = utf8Pool[in.readUnsignedShort()];
                            int subLen = in.readInt();
                            if ("LocalVariableTable".equals(subName)) {
                                int lvCount = in.readUnsignedShort();
                                for (int lv = 0; lv < lvCount; lv++) {
                                    int startPc = in.readUnsignedShort();
                                    int length = in.readUnsignedShort();
                                    String lvName = utf8Pool[in.readUnsignedShort()];
                                    String lvDesc = utf8Pool[in.readUnsignedShort()];
                                    int slot = in.readUnsignedShort();
                                    localVars.add(new LocalVarEntry(startPc, length, lvName, lvDesc, slot));
                                }
                            } else {
                                in.skipBytes(subLen);
                            }
                        }
                    } else {
                        in.skipBytes(attrLen);
                    }
                }
                methods.add(new RawMethod(mFlags, mName, mDesc, maxStack, maxLocals, code, localVars));
            }

            List<BootstrapMethodEntry> bootstrapMethods = new ArrayList<>();
            int classAttrs = in.readUnsignedShort();
            for (int ca = 0; ca < classAttrs; ca++) {
                String caName = utf8Pool[in.readUnsignedShort()];
                int caLen = in.readInt();
                if ("BootstrapMethods".equals(caName)) {
                    int numBsm = in.readUnsignedShort();
                    for (int b = 0; b < numBsm; b++) {
                        int mhIdx = in.readUnsignedShort();
                        int numArgs = in.readUnsignedShort();
                        int[] bsmArgs = new int[numArgs];
                        for (int k = 0; k < numArgs; k++) {
                            bsmArgs[k] = in.readUnsignedShort();
                        }
                        bootstrapMethods.add(new BootstrapMethodEntry(mhIdx, bsmArgs));
                    }
                } else {
                    in.skipBytes(caLen);
                }
            }

            return new RawClassFile(
                    thisClass,
                    superClass,
                    interfaces,
                    utf8Pool,
                    cpVals,
                    classNameIdx,
                    refClassIdx,
                    refNatIdx,
                    natNameIdx,
                    natDescIdx,
                    declaredFields,
                    methods,
                    fieldConstants,
                    methodHandles,
                    indyBsmIdx,
                    indyNatIdx,
                    bootstrapMethods);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Reads the first non-null instance field matching any of {@code fieldNames} across
     * {@code candidate}'s class hierarchy, returning {@code candidate} if none match.
     * Exposes only an opaque {@link Object} so callers remain reflection-free.
     */
    static Object readFirstNonNullField(Object candidate, String... fieldNames) {
        if (candidate == null || fieldNames == null) {
            return candidate;
        }
        Class<?> cls = candidate.getClass();
        for (String name : fieldNames) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            for (Class<?> cur = cls; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                for (Field f : cur.getDeclaredFields()) {
                    if (f.getName().equals(name)) {
                        try {
                            f.setAccessible(true);
                            Object v = f.get(candidate);
                            if (v != null) {
                                return v;
                            }
                        } catch (ReflectiveOperationException | RuntimeException ex) {
                            java.util.logging.Logger.getLogger(BytecodeSsaLifter.class.getName()).log(
                                    java.util.logging.Level.FINEST,
                                    "[BytecodeSsaLifter] Field read probe missed for " + name,
                                    ex);
                        }
                    }
                }
            }
        }
        return candidate;
    }
}
