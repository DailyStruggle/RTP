package io.github.dailystruggle.bytecode;

import io.github.dailystruggle.yaml.YamlConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Zero-classloading JVM {@code .class} causal graph mapper and two-order causal auditor
 * ({@code leaffuzz-ADR-001}, {@code ADR-125}, {@code corpus-atlas-ADR-002}).
 *
 * <p>Extracts three entry-point families from compiled plugin/mod archives without loading classes:
 * <ol>
 *   <li><b>Commands ({@link EntryPointKind#COMMAND}):</b> {@code onCommand}, {@code CommandExecutor},
 *       {@code CommandsAPICommand}, and Brigadier command lambdas.</li>
 *   <li><b>Events ({@link EntryPointKind#EVENT}):</b> {@code @EventHandler} and
 *       {@code @SubscribeEvent} listeners, distinguishing unconditional registration
 *       ({@link ListenerRegistrationMode#REGISTERED_UNCONDITIONAL}), generalized config/branch-gated
 *       registration ({@link ListenerRegistrationMode#REGISTERED_CONFIG_GATED} across inline
 *       {@code if} blocks, early-return guards, and gated helper methods), and unregistered dead
 *       listeners ({@link ListenerRegistrationMode#UNREGISTERED}).</li>
 *   <li><b>Suppliers &amp; Async Lambdas ({@link EntryPointKind#SUPPLIER}):</b> {@code Supplier#get},
 *       {@code Callable#call}, {@code Runnable#run}, {@code Consumer#accept}, and
 *       {@code invokedynamic} {@code BootstrapMethods} ({@code LambdaMetafactory}) edges.</li>
 * </ol>
 *
 * <p>Evaluates first-order (0-hop existence/registration wiring) and second-order ($k$-hop causal
 * chain to terminal effect sinks, $k \le 4$) invariants formatted as
 * {@code "<DeclaredSurface> -> <CausalDefect>"} with deterministic {@link ConfidenceTier}s.
 *
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy ADR-126
 * @governedBy leaffuzz-ADR-001
 * @governedBy corpus-atlas-ADR-002
 * @trace REQ-CFG-ATLAS-001
 * @trace REQ-SLEUTH-FUZZ-004
 * @trace S-004
 * @trace S-005
 */
public final class BytecodeCausalGraphScanner {

    private static final Logger LOGGER = Logger.getLogger(BytecodeCausalGraphScanner.class.getName());
    private static final java.util.regex.Pattern ANON_CLASS_RE = java.util.regex.Pattern.compile(".*\\$\\d+$");
    private static final java.util.regex.Pattern COMMENT_HASH_PREFIX_RE = java.util.regex.Pattern.compile("(?m)^\\s*#\\s*");
    private static final java.util.regex.Pattern FOLIA_SUPPORTED_TRUE_RE = java.util.regex.Pattern.compile("(?s).*\\bfolia-supported\\s*:\\s*true\\b.*");
    private static final java.util.regex.Pattern CMD_SUFFIX_RE = java.util.regex.Pattern.compile("command|cmd|executor|handler");
    private static final java.util.regex.Pattern PIPE_COMMA_WS_RE = java.util.regex.Pattern.compile("[|,\\s]+");
    private static final java.util.regex.Pattern WHITESPACE_RE = java.util.regex.Pattern.compile("\\s+");
    private static final java.util.regex.Pattern UUID_RE = java.util.regex.Pattern.compile("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final int MAX_LOGGED_ABORT_WARNINGS = 32;
    private static final Set<String> LOGGED_ABORT_WARNINGS =
            Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static final java.util.concurrent.atomic.AtomicBoolean ABORT_WARNING_CAP_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final int CLASS_MAGIC = 0xCAFEBABE;
    private static final int MAX_CAUSAL_HOPS = 4;
    private static final int MAX_RESOURCE_BYTES = 1024 * 1024;
    private static final int MAX_CLASSES_PER_JAR = 4096;

    // Constant pool tags (JVMS 4.4)
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

    // Access flags
    static final int ACC_PRIVATE = 0x0002;
    static final int ACC_STATIC = 0x0008;
    static final int ACC_FINAL = 0x0010;
    static final int ACC_SYNCHRONIZED = 0x0020;
    static final int ACC_VOLATILE = 0x0040;
    static final int ACC_INTERFACE = 0x0200;
    static final int ACC_ABSTRACT = 0x0400;
    static final int ACC_SYNTHETIC = 0x1000;
    static final int ACC_ENUM = 0x4000;

    private static final Set<String> CONFIG_GETTER_METHODS = Set.of(
            "getBoolean", "getInt", "getLong", "getDouble", "getString",
            "getStringList", "getIntegerList", "getDoubleList", "getFloatList",
            "getMapList", "getList", "getConfigurationSection", "getObject",
            "getConfigValue", "getNumber", "getOrDefault",
            "getDuration", "resolveScalar",
            "parseSecondsDurationMillis", "parseDurationSetting",
            "resolveCapacity", "resolveCapacityInt", "getProperty"
    );

    private static final Set<String> DYNAMIC_CONFIG_ITERATION_METHODS = Set.of(
            "getKeys", "getValues", "getConfigurationSection", "getMapList"
    );

    private static final Set<String> QUEUE_PRODUCER_METHODS = Set.of(
            "offer", "offerLast", "offerFirst", "add", "addLast", "addFirst",
            "push", "put", "putIfAbsent", "computeIfAbsent", "enqueue"
    );

    private static final Set<String> QUEUE_CONSUMER_METHODS = Set.of(
            "poll", "pollFirst", "pollLast", "take", "remove", "removeFirst",
            "pop", "drainTo", "iterator", "forEach", "get", "values", "entrySet"
    );

    private static final Set<String> EVENT_DISPATCH_METHODS = Set.of(
            "callEvent", "post", "fire", "fireEvent", "dispatchEvent"
    );

    private static final Set<String> STRING_COMPARE_METHODS = Set.of(
            "equals", "equalsIgnoreCase", "startsWith", "contentEquals", "matches"
    );

    private static final Set<String> NON_SUBCOMMAND_LITERALS = Set.of(
            "true", "false", "null", "none", "default", "console", "player",
            "utf-8", "utf8", "yes", "no", "on", "off", "all"
    );

    private static final Set<String> REFLECTIVE_SCANNER_MARKERS = Set.of(
            "org/reflections/Reflections",
            "com/google/common/reflect/ClassPath",
            "io/github/classgraph/ClassGraph",
            "com/google/inject/Injector",
            "co/aikar/commands/PaperCommandManager",
            "co/aikar/commands/BukkitCommandManager",
            "org/incendo/cloud/CommandManager",
            "cloud/commandframework/CommandManager",
            "dev/rollczi/litecommands/LiteCommands"
    );

    private static final Set<String> BENIGN_CAUGHT_EXCEPTIONS = Set.of(
            "java/lang/NumberFormatException",
            "java/lang/IllegalArgumentException",
            "java/lang/IllegalStateException",
            "java/lang/RuntimeException",
            "java/lang/InterruptedException",
            "java/lang/ClassNotFoundException",
            "java/lang/NoClassDefFoundError",
            "java/lang/UnsatisfiedLinkError",
            "java/lang/LinkageError",
            "java/lang/NoSuchMethodError",
            "java/lang/NoSuchMethodException",
            "java/lang/NoSuchFieldError",
            "java/lang/NoSuchFieldException",
            "java/lang/ReflectiveOperationException",
            "java/lang/IllegalAccessException",
            "java/lang/InvocationTargetException",
            "java/lang/InstantiationException",
            "java/lang/UnsupportedOperationException",
            "java/lang/IndexOutOfBoundsException",
            "java/lang/ClassCastException",
            "java/lang/NullPointerException",
            "java/lang/Throwable",
            "java/util/concurrent/CancellationException"
    );

    /** Entry-point family in the plugin/mod causal graph. */
    public enum EntryPointKind {
        COMMAND,
        EVENT,
        SUPPLIER
    }

    /** Registration wiring mode for an event listener class. */
    public enum ListenerRegistrationMode {
        /** Instantiated and registered unconditionally on the plugin/mod startup path. */
        REGISTERED_UNCONDITIONAL,
        /** Instantiated and registered conditionally behind a configuration flag, early-return guard, or branch. */
        REGISTERED_CONFIG_GATED,
        /** Registered via reflective package scanning, DI container, or SPI hook. */
        REGISTERED_REFLECTIVE_OR_SPI,
        /** Concrete listener class with {@code @EventHandler}/{@code @SubscribeEvent} and 0 instantiation/registration sites. */
        UNREGISTERED,
        /** Not an event listener entry point. */
        NOT_APPLICABLE
    }

    /** Deterministic confidence tier for a {@link CausalFinding}. */
    public enum ConfidenceTier {
        CONFIRMED(100, "CONFIRMED 100%"),
        HIGH(90, "HIGH 90%"),
        MEDIUM(75, "MEDIUM 75%"),
        ADVISORY(55, "ADVISORY 55%");

        private final int defaultPercent;
        private final String badge;

        ConfidenceTier(int defaultPercent, String badge) {
            this.defaultPercent = defaultPercent;
            this.badge = badge;
        }

        public int defaultPercent() {
            return defaultPercent;
        }

        public String badge() {
            return badge;
        }
    }

    /** Category of a first-order or second-order causal audit finding. */
    public enum FindingKind {
        /** Order 1: Concrete {@code @EventHandler}/{@code @SubscribeEvent} listener is never instantiated or registered. */
        CAUSAL_UNREGISTERED_LISTENER,
        /** Order 1: Command declared in {@code plugin.yml} is never bound in {@code onEnable} or handled in {@code onCommand}. */
        CAUSAL_UNWIRED_COMMAND,
        /** Order 1: Configuration key advertised in embedded {@code config.yml} has 0 bytecode readers in the JAR. */
        CAUSAL_DEAD_CONFIG_KEY,
        /** Order 2: Configuration key is read into a class field, but that field has 0 {@code getfield}/{@code getstatic} readers. */
        CAUSAL_UNREAD_CONFIG_FIELD,
        /** Order 2: {@code CompletableFuture.supplyAsync} or {@code Supplier} return value is immediately popped/discarded. */
        CAUSAL_ORPHANED_SUPPLIER,
        /** Order 2: Exception handler catches an operational exception and returns immediately with 0 logs or user feedback ({@code S-004}). */
        CAUSAL_SWALLOWED_FAILURE,
        /** Order 2: Async {@code Supplier}/{@code Runnable} invokes main-thread-only Bukkit world/chunk/entity mutators ({@code S-005}). */
        CAUSAL_ASYNC_MAIN_THREAD_SINK,
        /** Order 2: Config key in seconds is added/compared against {@code System.currentTimeMillis()} without {@code * 1000L} scaling. */
        CAUSAL_UNIT_MISMATCH,
        /** Order 2: Command advertises a world/teleport/GUI/economy action but its causal chain only sends chat messages. */
        CAUSAL_HOLLOW_COMMAND,
        /** Order 2: Shared mutable class field is accessed across methods/threads with mismatched locks or unsynchronized collection mutations ({@code CAUSAL-RACE}). */
        CAUSAL_SHARED_STATE_RACE,
        /** Order 2: Synchronous command or non-async event handler reaches blocking chunk/disk/JDBC/Future.get I/O within k<=4 hops ({@code CAUSAL-MAIN-IO}, {@code S-005}). */
        CAUSAL_SYNC_MAIN_THREAD_IO,
        /** Order 2: Intra-method Closeable/stream/ChunkTicket allocation lacks close()/release()/MemoryTracker across exit paths ({@code CAUSAL-LEAK}, {@code S-002}). */
        CAUSAL_UNCLOSED_RESOURCE_LEAK,
        /** Order 2 (Advisory): Subscribed LLM semantic/guard-polarity mismatch grounded in closed-world AST symbols. */
        CAUSAL_LLM_SEMANTIC_MISMATCH
    }

    /**
     * Proof status for a safety invariant obligation ({@code corpus-atlas-ADR-002} §3).
     */
    public enum ProofStatus {
        DISCHARGED,
        AUDIT_GUARDED,
        VIOLATED
    }

    /**
     * Proof obligation badge attached to a {@link CausalChainSlice} ({@code corpus-atlas-ADR-002} §3).
     *
     * @param ruleId   safety invariant identifier (e.g. {@code "S-002"}, {@code "S-004"}, {@code "S-005"})
     * @param status   verification status ({@link ProofStatus#DISCHARGED}, {@link ProofStatus#AUDIT_GUARDED}, or {@link ProofStatus#VIOLATED})
     * @param evidence concise bytecode proof or violation summary
     */
    public record ProofObligation(
            String ruleId,
            ProofStatus status,
            String evidence
    ) {
        public ProofObligation {
            ruleId = (ruleId == null || ruleId.isBlank()) ? "S-000" : ruleId.trim();
            status = status == null ? ProofStatus.AUDIT_GUARDED : status;
            evidence = evidence == null ? "" : evidence.trim();
        }
    }

    /**
     * Hoare-style effect frame summarizing mutated fields, read fields, acquired locks, and
     * entry guard preconditions across a causal chain ({@code corpus-atlas-ADR-002} §1).
     */
    public record EffectFrame(
            List<String> mutates,
            List<String> reads,
            List<String> locks,
            List<String> requires
    ) {
        public EffectFrame {
            mutates = mutates == null ? List.of() : List.copyOf(mutates);
            reads = reads == null ? List.of() : List.copyOf(reads);
            locks = locks == null ? List.of() : List.copyOf(locks);
            requires = requires == null ? List.of() : List.copyOf(requires);
        }

        public static EffectFrame empty() {
            return new EffectFrame(List.of(), List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return mutates.isEmpty() && reads.isEmpty() && locks.isEmpty() && requires.isEmpty();
        }
    }

    /**
     * Whole-class shared-state concurrency hazard detected via Eraser lock-set intersection and
     * field descriptor analysis ({@code corpus-atlas-ADR-002} §8, {@code CAUSAL-RACE}).
     */
    public record ClassSharedStateHazard(
            String ownerClass,
            String fieldName,
            String fieldType,
            String hazardKind,
            String hazardReason,
            List<String> writerMethods,
            List<String> readerMethods,
            List<String> commonLocks
    ) {
        public ClassSharedStateHazard {
            ownerClass = ownerClass == null ? "<unknown>" : ownerClass;
            fieldName = fieldName == null ? "<field>" : fieldName;
            fieldType = fieldType == null ? "Object" : fieldType;
            hazardKind = hazardKind == null ? "LOCK_SET_MISMATCH" : hazardKind;
            hazardReason = hazardReason == null ? "" : hazardReason;
            writerMethods = writerMethods == null ? List.of() : List.copyOf(writerMethods);
            readerMethods = readerMethods == null ? List.of() : List.copyOf(readerMethods);
            commonLocks = commonLocks == null ? List.of() : List.copyOf(commonLocks);
        }
    }

    record FieldDescriptorMeta(
            String name,
            int accessFlags,
            String descriptor,
            String signature,
            boolean isStatic,
            boolean isFinal,
            boolean isVolatile,
            boolean isThreadSafeType,
            boolean isUnsafeCollection,
            boolean isLinkedHashMap
    ) {
        FieldDescriptorMeta(
                String name,
                int accessFlags,
                String descriptor,
                boolean isStatic,
                boolean isFinal,
                boolean isVolatile,
                boolean isThreadSafeType,
                boolean isUnsafeCollection,
                boolean isLinkedHashMap
        ) {
            this(
                    name,
                    accessFlags,
                    descriptor,
                    "",
                    isStatic,
                    isFinal,
                    isVolatile,
                    isThreadSafeType,
                    isUnsafeCollection,
                    isLinkedHashMap
            );
        }

        static FieldDescriptorMeta fromBytecode(String name, int flags, String descriptor) {
            return fromBytecode(name, flags, descriptor, "");
        }

        static FieldDescriptorMeta fromBytecode(String name, int flags, String descriptor, String signature) {
            String desc = descriptor == null ? "" : descriptor;
            String sig = signature == null ? "" : signature;
            boolean isStatic = (flags & ACC_STATIC) != 0;
            boolean isFinal = (flags & ACC_FINAL) != 0;
            boolean isVolatile = (flags & ACC_VOLATILE) != 0;
            boolean isThreadSafe = desc.contains("java/util/concurrent/")
                    || desc.contains("Atomic")
                    || desc.contains("Concurrent")
                    || desc.contains("CopyOnWrite")
                    || desc.contains("LongAdder")
                    || desc.contains("StampedLock")
                    || desc.contains("ReentrantLock")
                    || desc.contains("ReadWriteLock")
                    || desc.contains("Semaphore");
            boolean isLinkedHashMap = desc.contains("java/util/LinkedHashMap");
            boolean isUnsafeColl = isLinkedHashMap
                    || desc.contains("java/util/HashMap")
                    || desc.contains("java/util/ArrayList")
                    || desc.contains("java/util/HashSet")
                    || desc.contains("java/util/LinkedList")
                    || desc.contains("java/util/TreeMap")
                    || desc.contains("java/util/TreeSet")
                    || desc.contains("java/util/ArrayDeque")
                    || desc.contains("java/util/WeakHashMap")
                    || desc.contains("java/util/IdentityHashMap");
            return new FieldDescriptorMeta(
                    name,
                    flags,
                    desc,
                    sig,
                    isStatic,
                    isFinal,
                    isVolatile,
                    isThreadSafe,
                    isUnsafeColl,
                    isLinkedHashMap
            );
        }
    }

    /**
     * Structured first-order or second-order causal audit finding formatted as
     * {@code "<DeclaredSurface> -> <CausalDefect>"}.
     *
     * @param findingId         stable finding identifier (e.g. {@code CAUSAL-1})
     * @param artifactName      owning plugin/mod or JAR name
     * @param order             investigation order ({@code 1} = 0-hop existence/wiring, {@code 2} = $k$-hop causal chain)
     * @param kind              finding category
     * @param confidenceTier    deterministic confidence tier
     * @param confidencePercent numeric confidence score ({@code 0..100})
     * @param entryPointSymbol  attributed {@code Class#method} or config key symbol
     * @param declaredSurface   declared behavior from {@code plugin.yml}, {@code config.yml}, or annotations
     * @param causalDefect      concrete 1st-order or 2nd-order causal defect proof
     * @param clearsWhen        exact developer remediation condition
     * @param causalChainSteps  extracted {@code Jminor}-style hop trace supporting the finding
     * @param llmExplanation    optional AST-grounded root-cause explanation from {@link LlmCausalChainAuditor}
     */
    public record CausalFinding(
            String findingId,
            String artifactName,
            int order,
            FindingKind kind,
            ConfidenceTier confidenceTier,
            int confidencePercent,
            String entryPointSymbol,
            String declaredSurface,
            String causalDefect,
            String clearsWhen,
            List<String> causalChainSteps,
            String llmExplanation
    ) {
        public CausalFinding {
            findingId = (findingId == null || findingId.isBlank()) ? "CAUSAL-0" : findingId.trim();
            artifactName = (artifactName == null || artifactName.isBlank()) ? "unknown" : artifactName.trim();
            order = order <= 1 ? 1 : 2;
            Objects.requireNonNull(kind, "kind");
            confidenceTier = confidenceTier != null ? confidenceTier : ConfidenceTier.HIGH;
            confidencePercent = Math.max(1, Math.min(100, confidencePercent));
            entryPointSymbol = (entryPointSymbol == null || entryPointSymbol.isBlank()) ? "<unknown>" : entryPointSymbol.trim();
            declaredSurface = (declaredSurface == null || declaredSurface.isBlank()) ? entryPointSymbol : declaredSurface.trim();
            causalDefect = (causalDefect == null || causalDefect.isBlank()) ? "No downstream delivery sink" : causalDefect.trim();
            clearsWhen = (clearsWhen == null || clearsWhen.isBlank()) ? "Wire the entry point to its downstream sink." : clearsWhen.trim();
            causalChainSteps = causalChainSteps == null ? List.of() : List.copyOf(causalChainSteps);
            llmExplanation = llmExplanation == null ? "" : llmExplanation.trim();
        }

        public CausalFinding withLlmExplanation(String explanation) {
            return new CausalFinding(
                    findingId,
                    artifactName,
                    order,
                    kind,
                    confidenceTier,
                    confidencePercent,
                    entryPointSymbol,
                    declaredSurface,
                    causalDefect,
                    clearsWhen,
                    causalChainSteps,
                    explanation
            );
        }

        public CausalFinding withRuntimeCorroboration(int executions, int teleports, int menus) {
            if (executions <= 0) {
                return this;
            }
            String runtimeNote = String.format(
                    Locale.ROOT,
                    " Corroborated by Stage 2 live fuzzing: %d execution(s) observed %d teleports and %d menus.",
                    executions,
                    teleports,
                    menus
            );
            ConfidenceTier boostedTier = (confidenceTier == ConfidenceTier.MEDIUM)
                    ? ConfidenceTier.HIGH
                    : confidenceTier;
            int boostedPct = Math.min(98, Math.max(confidencePercent, 92));
            return new CausalFinding(
                    findingId,
                    artifactName,
                    order,
                    kind,
                    boostedTier,
                    boostedPct,
                    entryPointSymbol,
                    declaredSurface,
                    causalDefect.contains("Corroborated by Stage 2") ? causalDefect : (causalDefect + runtimeNote),
                    clearsWhen,
                    causalChainSteps,
                    llmExplanation
            );
        }

        public String formatSummaryLine() {
            return String.format(
                    Locale.ROOT,
                    "[%s %d%%] (Order %d - %s) %s -> %s",
                    confidenceTier.name(),
                    confidencePercent,
                    order,
                    kind.name(),
                    declaredSurface,
                    causalDefect
            );
        }
    }

    /**
     * Method-scoped {@code Jminor}-style Hoare causal slice for an entry point ({@code corpus-atlas-ADR-002}).
     */
    public record CausalChainSlice(
            EntryPointKind kind,
            String entrySymbol,
            String declaredBehavior,
            ListenerRegistrationMode registrationMode,
            String gatingConfigKey,
            List<String> guards,
            List<String> readConfigKeys,
            List<String> readFields,
            List<String> mutatedFields,
            List<String> spawnedSuppliersAndLambdas,
            List<String> hopTrace,
            Set<String> reachableEffectSinks,
            EffectFrame effectFrame,
            List<String> macros,
            List<ProofObligation> proofObligations
    ) {
        public CausalChainSlice {
            Objects.requireNonNull(kind, "kind");
            entrySymbol = entrySymbol == null ? "<unknown>" : entrySymbol;
            declaredBehavior = declaredBehavior == null ? "" : declaredBehavior;
            registrationMode = registrationMode == null ? ListenerRegistrationMode.NOT_APPLICABLE : registrationMode;
            gatingConfigKey = gatingConfigKey == null ? "" : gatingConfigKey;
            guards = guards == null ? List.of() : List.copyOf(guards);
            readConfigKeys = readConfigKeys == null ? List.of() : List.copyOf(readConfigKeys);
            readFields = readFields == null ? List.of() : List.copyOf(readFields);
            mutatedFields = mutatedFields == null ? List.of() : List.copyOf(mutatedFields);
            spawnedSuppliersAndLambdas = spawnedSuppliersAndLambdas == null ? List.of() : List.copyOf(spawnedSuppliersAndLambdas);
            hopTrace = hopTrace == null ? List.of() : List.copyOf(hopTrace);
            reachableEffectSinks = reachableEffectSinks == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(reachableEffectSinks));
            effectFrame = effectFrame == null ? EffectFrame.empty() : effectFrame;
            macros = macros == null ? List.of() : List.copyOf(macros);
            proofObligations = proofObligations == null ? List.of() : List.copyOf(proofObligations);
        }

        public CausalChainSlice(
                EntryPointKind kind,
                String entrySymbol,
                String declaredBehavior,
                ListenerRegistrationMode registrationMode,
                String gatingConfigKey,
                List<String> guards,
                List<String> readConfigKeys,
                List<String> readFields,
                List<String> mutatedFields,
                List<String> spawnedSuppliersAndLambdas,
                List<String> hopTrace,
                Set<String> reachableEffectSinks
        ) {
            this(
                    kind,
                    entrySymbol,
                    declaredBehavior,
                    registrationMode,
                    gatingConfigKey,
                    guards,
                    readConfigKeys,
                    readFields,
                    mutatedFields,
                    spawnedSuppliersAndLambdas,
                    hopTrace,
                    reachableEffectSinks,
                    EffectFrame.empty(),
                    List.of(),
                    List.of()
            );
        }

        /**
         * Renders a compact {@code Jminor}-style causal slice suitable for LLM prompts (`~150-250` tokens).
         */
        public String toJminorPromptBlock() {
            StringBuilder sb = new StringBuilder();
            sb.append("CAUSAL_CHAIN [ENTRY_").append(kind.name()).append(": ")
                    .append(LlmConfigDocSynthesizer.stripPackagePrefixes(entrySymbol)).append("]:\n");
            if (!declaredBehavior.isBlank()) {
                sb.append("  DECLARED: ").append(declaredBehavior).append('\n');
            }
            if (kind == EntryPointKind.EVENT) {
                sb.append("  REGISTRATION: ").append(registrationMode.name());
                if (!gatingConfigKey.isBlank()) {
                    sb.append(" (gated by config: ").append(gatingConfigKey).append(')');
                }
                sb.append('\n');
            }
            if (!guards.isEmpty()) {
                sb.append("  GUARDS: ").append(String.join("; ", guards)).append('\n');
            }
            if (!readConfigKeys.isEmpty()) {
                sb.append("  READS_CONFIG: ").append(String.join(", ", readConfigKeys)).append('\n');
            }
            if (!readFields.isEmpty()) {
                sb.append("  READS_FIELDS: ").append(String.join(", ", readFields)).append('\n');
            }
            if (!mutatedFields.isEmpty()) {
                sb.append("  MUTATES_FIELDS: ").append(String.join(", ", mutatedFields)).append('\n');
            }
            if (!effectFrame.isEmpty()) {
                sb.append("  EFFECT_FRAME: mutates=").append(effectFrame.mutates())
                        .append(" reads=").append(effectFrame.reads())
                        .append(" locks=").append(effectFrame.locks())
                        .append(" requires=").append(effectFrame.requires()).append('\n');
            }
            if (!macros.isEmpty()) {
                sb.append("  MACROS: ").append(String.join(", ", macros)).append('\n');
            }
            if (!proofObligations.isEmpty()) {
                List<String> badges = new ArrayList<>(proofObligations.size());
                for (ProofObligation po : proofObligations) {
                    badges.add(po.ruleId() + ":" + po.status().name()
                            + (po.evidence().isEmpty() ? "" : ("(" + po.evidence() + ")")));
                }
                sb.append("  PROOF_OBLIGATIONS: ").append(String.join(", ", badges)).append('\n');
            }
            if (!spawnedSuppliersAndLambdas.isEmpty()) {
                sb.append("  SPAWNS_SUPPLIERS: ").append(String.join(" -> ", spawnedSuppliersAndLambdas)).append('\n');
            }
            if (!hopTrace.isEmpty()) {
                sb.append("  HOPS:\n");
                for (String hop : hopTrace) {
                    sb.append("    -> ").append(LlmConfigDocSynthesizer.stripPackagePrefixes(hop)).append('\n');
                }
            }
            sb.append("  REACHABLE_SINKS: ").append(reachableEffectSinks.isEmpty() ? "[NO_OP]" : reachableEffectSinks).append('\n');
            return sb.toString();
        }
    }

    /**
     * White-box command schema extracted from bytecode branches, argument parsers, and framework
     * annotations ({@code leaffuzz-ADR-001}, {@code ADR-125}).
     *
     * @param rootCommand        canonical root command literal (e.g. {@code "wild"}, {@code "rtp"})
     * @param handlerSymbol      bytecode method symbol handling the command
     * @param subcommandLiterals literal subcommand/branch tokens compared against {@code args[i]} or declared via {@code @Subcommand}/{@code @Execute}
     * @param argumentParsers    inferred Brigadier-compatible argument parser kinds (e.g. {@code "brigadier:integer"}, {@code "minecraft:dimension"})
     * @param inlinePermissions  permission node literals checked via {@code hasPermission("...")} or {@code @CommandPermission}/{@code @Permission}
     */
    public record BytecodeCommandSchema(
            String rootCommand,
            String handlerSymbol,
            List<String> subcommandLiterals,
            List<String> argumentParsers,
            List<String> inlinePermissions
    ) {
        public BytecodeCommandSchema {
            rootCommand = (rootCommand == null || rootCommand.isBlank()) ? "cmd" : rootCommand.trim().toLowerCase(Locale.ROOT);
            handlerSymbol = handlerSymbol == null ? "<unknown>" : handlerSymbol;
            subcommandLiterals = subcommandLiterals == null ? List.of() : List.copyOf(subcommandLiterals);
            argumentParsers = argumentParsers == null ? List.of() : List.copyOf(argumentParsers);
            inlinePermissions = inlinePermissions == null ? List.of() : List.copyOf(inlinePermissions);
        }
    }

    /**
     * Complete causal graph and first/second-order causal audit report for a plugin/mod artifact.
     */
    public record CausalGraphReport(
            String artifactId,
            String displayName,
            List<CausalChainSlice> entryChains,
            Map<String, ListenerRegistrationMode> listenerRegistrations,
            Map<String, String> configGatedListeners,
            List<CausalFinding> findings,
            Set<String> closedWorldSymbols,
            List<BytecodeCommandSchema> commandSchemas,
            List<ClassSharedStateHazard> classSharedStateHazards,
            Set<String> inferredEcosystemDependencies,
            Set<String> inferredExternalPackages
    ) {
        public CausalGraphReport {
            artifactId = (artifactId == null || artifactId.isBlank()) ? "plugin:unknown" : artifactId;
            displayName = (displayName == null || displayName.isBlank()) ? artifactId : displayName;
            entryChains = entryChains == null ? List.of() : List.copyOf(entryChains);
            listenerRegistrations = listenerRegistrations == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(listenerRegistrations));
            configGatedListeners = configGatedListeners == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(configGatedListeners));
            findings = findings == null ? List.of() : List.copyOf(findings);
            closedWorldSymbols = closedWorldSymbols == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(closedWorldSymbols));
            commandSchemas = commandSchemas == null ? List.of() : List.copyOf(commandSchemas);
            classSharedStateHazards = classSharedStateHazards == null ? List.of() : List.copyOf(classSharedStateHazards);
            inferredEcosystemDependencies = inferredEcosystemDependencies == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(inferredEcosystemDependencies));
            inferredExternalPackages = inferredExternalPackages == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(inferredExternalPackages));
        }

        public CausalGraphReport(
                String artifactId,
                String displayName,
                List<CausalChainSlice> entryChains,
                Map<String, ListenerRegistrationMode> listenerRegistrations,
                Map<String, String> configGatedListeners,
                List<CausalFinding> findings,
                Set<String> closedWorldSymbols,
                List<BytecodeCommandSchema> commandSchemas,
                List<ClassSharedStateHazard> classSharedStateHazards
        ) {
            this(
                    artifactId,
                    displayName,
                    entryChains,
                    listenerRegistrations,
                    configGatedListeners,
                    findings,
                    closedWorldSymbols,
                    commandSchemas,
                    classSharedStateHazards,
                    Set.of(),
                    Set.of()
            );
        }

        public CausalGraphReport(
                String artifactId,
                String displayName,
                List<CausalChainSlice> entryChains,
                Map<String, ListenerRegistrationMode> listenerRegistrations,
                Map<String, String> configGatedListeners,
                List<CausalFinding> findings,
                Set<String> closedWorldSymbols,
                List<BytecodeCommandSchema> commandSchemas
        ) {
            this(
                    artifactId,
                    displayName,
                    entryChains,
                    listenerRegistrations,
                    configGatedListeners,
                    findings,
                    closedWorldSymbols,
                    commandSchemas,
                    List.of(),
                    Set.of(),
                    Set.of()
            );
        }

        public CausalGraphReport(
                String artifactId,
                String displayName,
                List<CausalChainSlice> entryChains,
                Map<String, ListenerRegistrationMode> listenerRegistrations,
                Map<String, String> configGatedListeners,
                List<CausalFinding> findings,
                Set<String> closedWorldSymbols
        ) {
            this(
                    artifactId,
                    displayName,
                    entryChains,
                    listenerRegistrations,
                    configGatedListeners,
                    findings,
                    closedWorldSymbols,
                    List.of(),
                    List.of(),
                    Set.of(),
                    Set.of()
            );
        }

        public static CausalGraphReport empty(String name) {
            return new CausalGraphReport(
                    name,
                    name,
                    List.of(),
                    Map.of(),
                    Map.of(),
                    List.of(),
                    Set.of(),
                    List.of(),
                    List.of(),
                    Set.of(),
                    Set.of()
            );
        }
    }

    record ExceptionEntry(
            int startPc,
            int endPc,
            int handlerPc,
            String catchClassInternal
    ) {
    }

    record ParsedAnnotations(
            boolean isEventHandler,
            boolean isCommandEntry,
            List<String> commandRoots,
            List<String> subcommandLiterals,
            List<String> permissions,
            String description
    ) {
        static ParsedAnnotations empty() {
            return new ParsedAnnotations(false, false, List.of(), List.of(), List.of(), "");
        }

        ParsedAnnotations merge(ParsedAnnotations other) {
            if (other == null) {
                return this;
            }
            Set<String> roots = new LinkedHashSet<>(this.commandRoots);
            roots.addAll(other.commandRoots);
            Set<String> subs = new LinkedHashSet<>(this.subcommandLiterals);
            subs.addAll(other.subcommandLiterals);
            Set<String> perms = new LinkedHashSet<>(this.permissions);
            perms.addAll(other.permissions);
            String desc = !this.description.isBlank() ? this.description : other.description;
            return new ParsedAnnotations(
                    this.isEventHandler || other.isEventHandler,
                    this.isCommandEntry || other.isCommandEntry,
                    new ArrayList<>(roots),
                    new ArrayList<>(subs),
                    new ArrayList<>(perms),
                    desc
            );
        }
    }

    record IndyFunctionalSite(
            String functionalInterface,
            String samMethodName,
            String targetSymbol,
            String enclosingClass,
            String enclosingMethod
    ) {
    }

    record MethodNode(
            String ownerClass,
            String name,
            String descriptor,
            int accessFlags,
            boolean isEventHandler,
            String eventTypeSimpleName,
            boolean isCommandAnnotated,
            List<String> annotatedCommandRoots,
            List<String> subcommandLiterals,
            List<String> argumentParsers,
            List<String> inlinePermissions,
            String commandDescription,
            List<String> calledMethods,
            List<String> spawnedLambdas,
            List<String> orphanedSuppliers,
            Set<String> instantiatedClasses,
            Map<String, String> conditionalInstantiations,
            Map<String, String> conditionalMethodCalls,
            Set<String> readFields,
            Set<String> writtenFields,
            Set<String> producedQueueFields,
            Set<String> dispatchedEventTypes,
            Map<String, String> configKeyToAssignedField,
            Map<String, String> configKeyScalingHint,
            Set<String> referencedConfigKeys,
            Set<String> stringLiterals,
            List<String> guards,
            Set<String> directEffectSinks,
            Set<String> syncMainThreadCalls,
            List<String> swallowedExceptions,
            boolean callsCurrentTimeMillis,
            boolean multipliesBy1000Or20,
            boolean usesDynamicConfigIteration,
            Set<String> acquiredLocks,
            Set<String> blockingIoCalls,
            List<String> allocatedResources,
            boolean closesOrTracksResourcesOnAllPaths,
            String resourceLeakReason,
            Set<String> semanticMacros,
            boolean callsAccessOrderLinkedHashMap,
            Set<String> collectionAddedFields,
            Set<String> collectionRemovedFields,
            Set<String> arrayMutatedFields,
            Set<String> receiverMutatedFields,
            Set<String> dynamicFunctionalDispatches,
            Set<String> asyncFunctionalDispatches,
            String returnedField
    ) {
        MethodNode(
                String ownerClass,
                String name,
                String descriptor,
                int accessFlags,
                boolean isEventHandler,
                String eventTypeSimpleName,
                boolean isCommandAnnotated,
                List<String> annotatedCommandRoots,
                List<String> subcommandLiterals,
                List<String> argumentParsers,
                List<String> inlinePermissions,
                String commandDescription,
                List<String> calledMethods,
                List<String> spawnedLambdas,
                List<String> orphanedSuppliers,
                Set<String> instantiatedClasses,
                Map<String, String> conditionalInstantiations,
                Map<String, String> conditionalMethodCalls,
                Set<String> readFields,
                Set<String> writtenFields,
                Set<String> producedQueueFields,
                Set<String> dispatchedEventTypes,
                Map<String, String> configKeyToAssignedField,
                Map<String, String> configKeyScalingHint,
                Set<String> referencedConfigKeys,
                Set<String> stringLiterals,
                List<String> guards,
                Set<String> directEffectSinks,
                Set<String> syncMainThreadCalls,
                List<String> swallowedExceptions,
                boolean callsCurrentTimeMillis,
                boolean multipliesBy1000Or20,
                boolean usesDynamicConfigIteration,
                Set<String> acquiredLocks,
                Set<String> blockingIoCalls,
                List<String> allocatedResources,
                boolean closesOrTracksResourcesOnAllPaths,
                String resourceLeakReason,
                Set<String> semanticMacros,
                boolean callsAccessOrderLinkedHashMap,
                Set<String> collectionAddedFields,
                Set<String> collectionRemovedFields,
                Set<String> arrayMutatedFields,
                Set<String> dynamicFunctionalDispatches,
                Set<String> asyncFunctionalDispatches,
                String returnedField
        ) {
            this(
                    ownerClass,
                    name,
                    descriptor,
                    accessFlags,
                    isEventHandler,
                    eventTypeSimpleName,
                    isCommandAnnotated,
                    annotatedCommandRoots,
                    subcommandLiterals,
                    argumentParsers,
                    inlinePermissions,
                    commandDescription,
                    calledMethods,
                    spawnedLambdas,
                    orphanedSuppliers,
                    instantiatedClasses,
                    conditionalInstantiations,
                    conditionalMethodCalls,
                    readFields,
                    writtenFields,
                    producedQueueFields,
                    dispatchedEventTypes,
                    configKeyToAssignedField,
                    configKeyScalingHint,
                    referencedConfigKeys,
                    stringLiterals,
                    guards,
                    directEffectSinks,
                    syncMainThreadCalls,
                    swallowedExceptions,
                    callsCurrentTimeMillis,
                    multipliesBy1000Or20,
                    usesDynamicConfigIteration,
                    acquiredLocks,
                    blockingIoCalls,
                    allocatedResources,
                    closesOrTracksResourcesOnAllPaths,
                    resourceLeakReason,
                    semanticMacros,
                    callsAccessOrderLinkedHashMap,
                    collectionAddedFields,
                    collectionRemovedFields,
                    arrayMutatedFields,
                    Set.of(),
                    dynamicFunctionalDispatches,
                    asyncFunctionalDispatches,
                    returnedField
            );
        }
        MethodNode(
                String ownerClass,
                String name,
                String descriptor,
                int accessFlags,
                boolean isEventHandler,
                String eventTypeSimpleName,
                boolean isCommandAnnotated,
                List<String> annotatedCommandRoots,
                List<String> subcommandLiterals,
                List<String> argumentParsers,
                List<String> inlinePermissions,
                String commandDescription,
                List<String> calledMethods,
                List<String> spawnedLambdas,
                List<String> orphanedSuppliers,
                Set<String> instantiatedClasses,
                Map<String, String> conditionalInstantiations,
                Map<String, String> conditionalMethodCalls,
                Set<String> readFields,
                Set<String> writtenFields,
                Set<String> producedQueueFields,
                Set<String> dispatchedEventTypes,
                Map<String, String> configKeyToAssignedField,
                Map<String, String> configKeyScalingHint,
                Set<String> referencedConfigKeys,
                Set<String> stringLiterals,
                List<String> guards,
                Set<String> directEffectSinks,
                Set<String> syncMainThreadCalls,
                List<String> swallowedExceptions,
                boolean callsCurrentTimeMillis,
                boolean multipliesBy1000Or20,
                boolean usesDynamicConfigIteration,
                Set<String> acquiredLocks,
                Set<String> blockingIoCalls,
                List<String> allocatedResources,
                boolean closesOrTracksResourcesOnAllPaths,
                String resourceLeakReason,
                Set<String> semanticMacros,
                boolean callsAccessOrderLinkedHashMap,
                Set<String> collectionAddedFields,
                Set<String> collectionRemovedFields,
                Set<String> arrayMutatedFields,
                String returnedField
        ) {
            this(
                    ownerClass,
                    name,
                    descriptor,
                    accessFlags,
                    isEventHandler,
                    eventTypeSimpleName,
                    isCommandAnnotated,
                    annotatedCommandRoots,
                    subcommandLiterals,
                    argumentParsers,
                    inlinePermissions,
                    commandDescription,
                    calledMethods,
                    spawnedLambdas,
                    orphanedSuppliers,
                    instantiatedClasses,
                    conditionalInstantiations,
                    conditionalMethodCalls,
                    readFields,
                    writtenFields,
                    producedQueueFields,
                    dispatchedEventTypes,
                    configKeyToAssignedField,
                    configKeyScalingHint,
                    referencedConfigKeys,
                    stringLiterals,
                    guards,
                    directEffectSinks,
                    syncMainThreadCalls,
                    swallowedExceptions,
                    callsCurrentTimeMillis,
                    multipliesBy1000Or20,
                    usesDynamicConfigIteration,
                    acquiredLocks,
                    blockingIoCalls,
                    allocatedResources,
                    closesOrTracksResourcesOnAllPaths,
                    resourceLeakReason,
                    semanticMacros,
                    callsAccessOrderLinkedHashMap,
                    collectionAddedFields,
                    collectionRemovedFields,
                    arrayMutatedFields,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    returnedField
            );
        }

        MethodNode(
                String ownerClass,
                String name,
                String descriptor,
                int accessFlags,
                boolean isEventHandler,
                String eventTypeSimpleName,
                boolean isCommandAnnotated,
                List<String> annotatedCommandRoots,
                List<String> subcommandLiterals,
                List<String> argumentParsers,
                List<String> inlinePermissions,
                String commandDescription,
                List<String> calledMethods,
                List<String> spawnedLambdas,
                List<String> orphanedSuppliers,
                Set<String> instantiatedClasses,
                Map<String, String> conditionalInstantiations,
                Map<String, String> conditionalMethodCalls,
                Set<String> readFields,
                Set<String> writtenFields,
                Set<String> producedQueueFields,
                Set<String> dispatchedEventTypes,
                Map<String, String> configKeyToAssignedField,
                Map<String, String> configKeyScalingHint,
                Set<String> referencedConfigKeys,
                Set<String> stringLiterals,
                List<String> guards,
                Set<String> directEffectSinks,
                Set<String> syncMainThreadCalls,
                List<String> swallowedExceptions,
                boolean callsCurrentTimeMillis,
                boolean multipliesBy1000Or20,
                boolean usesDynamicConfigIteration,
                Set<String> acquiredLocks,
                Set<String> blockingIoCalls,
                List<String> allocatedResources,
                boolean closesOrTracksResourcesOnAllPaths,
                String resourceLeakReason,
                Set<String> semanticMacros,
                boolean callsAccessOrderLinkedHashMap,
                Set<String> collectionAddedFields,
                Set<String> collectionRemovedFields,
                String returnedField
        ) {
            this(
                    ownerClass,
                    name,
                    descriptor,
                    accessFlags,
                    isEventHandler,
                    eventTypeSimpleName,
                    isCommandAnnotated,
                    annotatedCommandRoots,
                    subcommandLiterals,
                    argumentParsers,
                    inlinePermissions,
                    commandDescription,
                    calledMethods,
                    spawnedLambdas,
                    orphanedSuppliers,
                    instantiatedClasses,
                    conditionalInstantiations,
                    conditionalMethodCalls,
                    readFields,
                    writtenFields,
                    producedQueueFields,
                    dispatchedEventTypes,
                    configKeyToAssignedField,
                    configKeyScalingHint,
                    referencedConfigKeys,
                    stringLiterals,
                    guards,
                    directEffectSinks,
                    syncMainThreadCalls,
                    swallowedExceptions,
                    callsCurrentTimeMillis,
                    multipliesBy1000Or20,
                    usesDynamicConfigIteration,
                    acquiredLocks,
                    blockingIoCalls,
                    allocatedResources,
                    closesOrTracksResourcesOnAllPaths,
                    resourceLeakReason,
                    semanticMacros,
                    callsAccessOrderLinkedHashMap,
                    collectionAddedFields,
                    collectionRemovedFields,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    returnedField
            );
        }

        String symbol() {
            return ownerClass + "#" + name;
        }
    }

    record ParsedClass(
            String binaryClassName,
            String superClassName,
            int accessFlags,
            Set<String> interfaces,
            Set<String> declaredFields,
            Map<String, FieldDescriptorMeta> declaredFieldMeta,
            Map<String, MethodNode> methods,
            boolean usesReflectiveScanner,
            ParsedAnnotations classAnnotations,
            Set<String> referencedClasses,
            List<IndyFunctionalSite> indyFunctionalSites
    ) {
        ParsedClass(
                String binaryClassName,
                String superClassName,
                int accessFlags,
                Set<String> interfaces,
                Set<String> declaredFields,
                Map<String, FieldDescriptorMeta> declaredFieldMeta,
                Map<String, MethodNode> methods,
                boolean usesReflectiveScanner,
                ParsedAnnotations classAnnotations,
                Set<String> referencedClasses
        ) {
            this(
                    binaryClassName,
                    superClassName,
                    accessFlags,
                    interfaces,
                    declaredFields,
                    declaredFieldMeta,
                    methods,
                    usesReflectiveScanner,
                    classAnnotations,
                    referencedClasses,
                    List.of()
            );
        }

        ParsedClass(
                String binaryClassName,
                String superClassName,
                int accessFlags,
                Set<String> interfaces,
                Set<String> declaredFields,
                Map<String, FieldDescriptorMeta> declaredFieldMeta,
                Map<String, MethodNode> methods,
                boolean usesReflectiveScanner,
                ParsedAnnotations classAnnotations
        ) {
            this(
                    binaryClassName,
                    superClassName,
                    accessFlags,
                    interfaces,
                    declaredFields,
                    declaredFieldMeta,
                    methods,
                    usesReflectiveScanner,
                    classAnnotations,
                    Set.of(),
                    List.of()
            );
        }
        boolean isConcrete() {
            return (accessFlags & (ACC_INTERFACE | ACC_ABSTRACT | ACC_ENUM)) == 0;
        }

        boolean implementsBukkitListener() {
            for (String iface : interfaces) {
                if (iface.endsWith("org.bukkit.event.Listener") || iface.endsWith("Listener")) {
                    return true;
                }
            }
            return false;
        }
    }

    private BytecodeCausalGraphScanner() {
    }

    /**
     * Scans a plugin or mod {@code .jar} file off-thread and produces its {@link CausalGraphReport}.
     *
     * @param jarPath path to the plugin/mod {@code .jar} archive
     * @return causal graph and first/second-order causal audit findings
     * @throws IOException if the archive cannot be read
     */
    public static CausalGraphReport scanJar(Path jarPath) throws IOException {
        Objects.requireNonNull(jarPath, "jarPath");
        return scanJar(jarPath, JarConfigScanner.scanJar(jarPath));
    }

    /**
     * Scans a plugin or mod {@code .jar} file off-thread using a pre-harvested
     * {@link JarConfigScanner.HarvestedArtifact} to avoid duplicate {@link ZipFile} passes.
     *
     * @param jarPath   path to the plugin/mod {@code .jar} archive
     * @param harvested pre-harvested descriptor and config metadata
     * @return causal graph and first/second-order causal audit findings
     * @throws IOException if the archive cannot be read
     */
    public static CausalGraphReport scanJar(Path jarPath, JarConfigScanner.HarvestedArtifact harvested) throws IOException {
        Objects.requireNonNull(jarPath, "jarPath");
        Objects.requireNonNull(harvested, "harvested");
        List<byte[]> classBytePayloads = new ArrayList<>();
        Map<String, String> allConfigs = new LinkedHashMap<>(harvested.embeddedConfigs());
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int count = 0;
            while (entries.hasMoreElements() && count < MAX_CLASSES_PER_JAR) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.endsWith(".class") && !isShadedLibraryClass(name)) {
                    byte[] bytes = readBoundedBytes(zip, entry);
                    if (bytes != null) {
                        classBytePayloads.add(bytes);
                        count++;
                    }
                } else if ("plugin.yml".equals(name) || "paper-plugin.yml".equals(name)) {
                    byte[] bytes = readBoundedBytes(zip, entry);
                    if (bytes != null) {
                        allConfigs.putIfAbsent(name, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            }
        }
        return analyzeClasses(
                harvested.artifactId(),
                harvested.displayName(),
                classBytePayloads,
                harvested.commandDescriptors(),
                allConfigs
        );
    }

    /**
     * Analyzes an in-memory collection of raw {@code .class} byte arrays alongside optional
     * {@code plugin.yml} command descriptors and embedded YAML configuration files.
     *
     * @param artifactId         canonical artifact ID
     * @param displayName        plugin/mod display name
     * @param classBytePayloads  raw {@code .class} byte arrays
     * @param commandDescriptors advertised commands from {@code plugin.yml} / {@code paper-plugin.yml}
     * @param embeddedConfigs    map of embedded config file name to raw YAML content
     * @return completed {@link CausalGraphReport}
     */
    public static CausalGraphReport analyzeClasses(
            String artifactId,
            String displayName,
            Collection<byte[]> classBytePayloads,
            List<JarConfigScanner.CommandDescriptor> commandDescriptors,
            Map<String, String> embeddedConfigs
    ) {
        String effId = (artifactId == null || artifactId.isBlank()) ? "plugin:unknown" : artifactId.trim();
        String effName = (displayName == null || displayName.isBlank()) ? effId : displayName.trim();
        List<JarConfigScanner.CommandDescriptor> cmds = commandDescriptors == null ? List.of() : commandDescriptors;
        Map<String, String> configs = embeddedConfigs == null ? Map.of() : embeddedConfigs;

        Map<String, ParsedClass> classesByName = new LinkedHashMap<>();
        Map<String, MethodNode> methodsBySymbol = new LinkedHashMap<>();
        Map<String, List<MethodNode>> methodsByName = new LinkedHashMap<>();
        List<MethodNode> allMethods = new ArrayList<>();
        Set<String> closedWorldSymbols = new LinkedHashSet<>();
        Set<String> allReferencedClasses = new LinkedHashSet<>();
        boolean jarUsesReflectiveScanner = false;
        boolean jarUsesDynamicConfigIteration = false;

        if (classBytePayloads != null) {
            for (byte[] bytes : classBytePayloads) {
                ParsedClass pc = parseClassBytes(bytes);
                if (pc == null) {
                    continue;
                }
                classesByName.put(pc.binaryClassName(), pc);
                closedWorldSymbols.add(pc.binaryClassName());
                closedWorldSymbols.add(simpleClassName(pc.binaryClassName()));
                allReferencedClasses.addAll(pc.referencedClasses());
                if (pc.usesReflectiveScanner()) {
                    jarUsesReflectiveScanner = true;
                }
                for (String field : pc.declaredFields()) {
                    closedWorldSymbols.add(pc.binaryClassName() + "#" + field);
                    closedWorldSymbols.add(simpleClassName(pc.binaryClassName()) + "#" + field);
                }
                for (var me : pc.methods().entrySet()) {
                    MethodNode m = me.getValue();
                    allMethods.add(m);
                    methodsBySymbol.putIfAbsent(m.symbol(), m);
                    methodsBySymbol.put(pc.binaryClassName() + "#" + me.getKey(), m);
                    methodsByName.computeIfAbsent(m.name(), k -> new ArrayList<>()).add(m);
                    closedWorldSymbols.add(m.symbol());
                    closedWorldSymbols.add(simpleClassName(pc.binaryClassName()) + "#" + m.name());
                    closedWorldSymbols.addAll(m.referencedConfigKeys());
                    if (m.usesDynamicConfigIteration()) {
                        jarUsesDynamicConfigIteration = true;
                    }
                }
            }
        }

        // Collect global sets across all methods in the JAR
        Set<String> allInstantiatedClasses = new LinkedHashSet<>();
        Map<String, String> conditionalInstantiatedClasses = new LinkedHashMap<>();
        Map<String, String> conditionallyCalledMethods = new LinkedHashMap<>();
        Set<String> allGloballyReadFields = new LinkedHashSet<>();
        Set<String> allGloballyReadFieldNames = new LinkedHashSet<>();
        Set<String> allReferencedConfigKeys = new LinkedHashSet<>();
        Set<String> allStringLiterals = new LinkedHashSet<>();
        Map<String, Set<String>> fieldToReaderMethods = new LinkedHashMap<>();
        Set<String> allAsyncSpawnedLambdas = new LinkedHashSet<>();

        for (MethodNode m : allMethods) {
            allInstantiatedClasses.addAll(m.instantiatedClasses());
            for (var ce : m.conditionalInstantiations().entrySet()) {
                conditionalInstantiatedClasses.putIfAbsent(ce.getKey(), ce.getValue());
            }
            for (var cm : m.conditionalMethodCalls().entrySet()) {
                conditionallyCalledMethods.putIfAbsent(cm.getKey(), cm.getValue());
            }
            for (String rf : m.readFields()) {
                allGloballyReadFields.add(rf);
                int hash = rf.indexOf('#');
                String shortField = hash >= 0 ? rf.substring(hash + 1) : rf;
                allGloballyReadFieldNames.add(shortField);
                if (!m.name().equals("<init>") && !m.name().equals("<clinit>")) {
                    fieldToReaderMethods.computeIfAbsent(rf, k -> new LinkedHashSet<>()).add(m.symbol());
                    fieldToReaderMethods.computeIfAbsent(shortField, k -> new LinkedHashSet<>()).add(m.symbol());
                }
            }
            allReferencedConfigKeys.addAll(m.referencedConfigKeys());
            allStringLiterals.addAll(m.stringLiterals());
            if (m.directEffectSinks().contains("ASYNC_SCHEDULER")
                    && !m.directEffectSinks().contains("SCHEDULER_TASK")
                    && !m.semanticMacros().contains("@folia_region_guard")) {
                allAsyncSpawnedLambdas.addAll(m.spawnedLambdas());
            }
        }

        // Propagate caller-gated helper methods (e.g. `if (cfgFlag) registerOptionalListeners();`)
        // to any listener classes instantiated inside that helper method
        for (var cm : conditionallyCalledMethods.entrySet()) {
            MethodNode targetHelper = findMethodNodeFlexible(methodsBySymbol, methodsByName, null, cm.getKey());
            if (targetHelper != null) {
                for (String instCls : targetHelper.instantiatedClasses()) {
                    conditionalInstantiatedClasses.putIfAbsent(instCls, cm.getValue());
                }
            }
        }

        // Also mark any superclass of an instantiated class as instantiated
        Set<String> transitiveInstantiated = new LinkedHashSet<>(allInstantiatedClasses);
        for (String inst : allInstantiatedClasses) {
            ParsedClass cur = classesByName.get(inst);
            int guard = 0;
            while (cur != null && cur.superClassName() != null && guard++ < 8) {
                transitiveInstantiated.add(cur.superClassName());
                cur = classesByName.get(cur.superClassName());
            }
        }

        List<CausalFinding> findings = new ArrayList<>();
        int findingSeq = 1;

        // =========================================================================
        // 1. Evaluate Event Listener Registration (Order 1) + Config-Gated Carve-Out
        // =========================================================================
        Map<String, ListenerRegistrationMode> listenerRegistrations = new LinkedHashMap<>();
        Map<String, String> configGatedListeners = new LinkedHashMap<>();

        for (ParsedClass pc : classesByName.values()) {
            if (!pc.isConcrete()) {
                continue;
            }
            List<MethodNode> eventMethods = new ArrayList<>();
            for (MethodNode m : pc.methods().values()) {
                if (m.isEventHandler()) {
                    eventMethods.add(m);
                }
            }
            if (eventMethods.isEmpty()) {
                continue;
            }

            boolean selfRegisters = pc.methods().values().stream().anyMatch(m ->
                    m.calledMethods().stream().anyMatch(c ->
                            c.endsWith("#registerEvents")
                                    || c.contains("EventBus#register")
                                    || c.endsWith("#register")
                                    || c.endsWith("#addListener")));
            boolean isInlineAnonymous = ANON_CLASS_RE.matcher(pc.binaryClassName()).matches();
            ListenerRegistrationMode regMode;
            String gatingKey = "";
            if (conditionalInstantiatedClasses.containsKey(pc.binaryClassName())) {
                regMode = ListenerRegistrationMode.REGISTERED_CONFIG_GATED;
                gatingKey = conditionalInstantiatedClasses.get(pc.binaryClassName());
                configGatedListeners.put(pc.binaryClassName(), gatingKey);
            } else if (transitiveInstantiated.contains(pc.binaryClassName()) || selfRegisters || isInlineAnonymous) {
                regMode = ListenerRegistrationMode.REGISTERED_UNCONDITIONAL;
            } else if (jarUsesReflectiveScanner) {
                regMode = ListenerRegistrationMode.REGISTERED_REFLECTIVE_OR_SPI;
            } else {
                regMode = ListenerRegistrationMode.UNREGISTERED;
            }
            listenerRegistrations.put(pc.binaryClassName(), regMode);

            if (regMode == ListenerRegistrationMode.UNREGISTERED) {
                MethodNode firstEv = eventMethods.get(0);
                String evLabel = firstEv.eventTypeSimpleName().isEmpty()
                        ? "@EventHandler"
                        : ("@EventHandler " + firstEv.eventTypeSimpleName());
                findings.add(new CausalFinding(
                        "CAUSAL-" + (findingSeq++),
                        effName,
                        1,
                        FindingKind.CAUSAL_UNREGISTERED_LISTENER,
                        ConfidenceTier.CONFIRMED,
                        100,
                        firstEv.symbol(),
                        simpleClassName(pc.binaryClassName()) + "#" + firstEv.name() + " handles " + evLabel,
                        simpleClassName(pc.binaryClassName())
                                + " is never instantiated or passed to PluginManager#registerEvents in " + effName + ".",
                        "Instantiate and register " + simpleClassName(pc.binaryClassName())
                                + " via getServer().getPluginManager().registerEvents(new "
                                + simpleClassName(pc.binaryClassName()) + "(...), this) in onEnable().",
                        List.of(firstEv.symbol() + " [UNREGISTERED]"),
                        ""
                ));
            }
        }

        // =========================================================================
        // 2. Evaluate Order 1: Advertised Commands in plugin.yml vs Bytecode Wiring
        // =========================================================================
        boolean hasAnyOnCommandOverride = false;
        boolean hasGetCommandCall = false;
        Set<String> annotatedCommandRoots = new LinkedHashSet<>();
        for (ParsedClass pc : classesByName.values()) {
            for (String r : pc.classAnnotations().commandRoots()) {
                annotatedCommandRoots.add(r.toLowerCase(Locale.ROOT));
            }
        }
        for (MethodNode m : allMethods) {
            if ("onCommand".equals(m.name()) || "execute".equals(m.name()) || m.isCommandAnnotated()) {
                hasAnyOnCommandOverride = true;
            }
            for (String r : m.annotatedCommandRoots()) {
                annotatedCommandRoots.add(r.toLowerCase(Locale.ROOT));
            }
            for (String call : m.calledMethods()) {
                if (call.endsWith("#getCommand") || call.endsWith("#registerCommand") || call.endsWith("#register")) {
                    hasGetCommandCall = true;
                }
            }
        }

        for (JarConfigScanner.CommandDescriptor cmd : cmds) {
            String cmdLower = cmd.name().toLowerCase(Locale.ROOT);
            closedWorldSymbols.add("/" + cmdLower);
            boolean wiredInLiterals = allStringLiterals.stream()
                    .anyMatch(s -> s.equalsIgnoreCase(cmd.name())
                            || cmd.aliases().stream().anyMatch(a -> a.equalsIgnoreCase(s)));
            boolean wiredInAnnotations = annotatedCommandRoots.contains(cmdLower)
                    || cmd.aliases().stream().anyMatch(a -> annotatedCommandRoots.contains(a.toLowerCase(Locale.ROOT)))
                    || !annotatedCommandRoots.isEmpty();
            boolean wired = wiredInLiterals
                    || wiredInAnnotations
                    || (cmds.size() == 1 && hasAnyOnCommandOverride && !hasGetCommandCall)
                    || jarUsesReflectiveScanner;
            if (!wired && (!hasAnyOnCommandOverride || hasGetCommandCall)) {
                String desc = cmd.description().isBlank() ? ("Executes /" + cmd.name()) : cmd.description();
                findings.add(new CausalFinding(
                        "CAUSAL-" + (findingSeq++),
                        effName,
                        1,
                        FindingKind.CAUSAL_UNWIRED_COMMAND,
                        ConfidenceTier.CONFIRMED,
                        100,
                        "/" + cmd.name(),
                        "plugin.yml advertises command '/" + cmd.name() + "' (\"" + desc + "\")",
                        "No getCommand(\"" + cmd.name() + "\").setExecutor(...) call or command branch found in " + effName + " bytecode.",
                        "Bind getCommand(\"" + cmd.name() + "\").setExecutor(...) in onEnable() or handle \"" + cmd.name() + "\" in onCommand().",
                        List.of("plugin.yml:/" + cmd.name() + " -> [UNWIRED]"),
                        ""
                ));
            }
        }

        // =========================================================================
        // 3. Evaluate Order 1: Dead Config Keys in Embedded config.yml
        // =========================================================================
        Map<String, String> advertisedConfigComments = new LinkedHashMap<>();
        Map<String, Object> advertisedConfigDefaults = new LinkedHashMap<>();
        for (var cfgEntry : configs.entrySet()) {
            String fileName = cfgEntry.getKey();
            String yamlBody = cfgEntry.getValue();
            if (!fileName.endsWith("config.yml") || yamlBody == null || yamlBody.isBlank()) {
                continue;
            }
            YamlConfig yaml;
            try {
                yaml = YamlConfig.parse(yamlBody);
            } catch (RuntimeException ignored) {
                continue;
            }
            for (String key : yaml.getKeys(true)) {
                Object val = yaml.get(key);
                if (val instanceof io.github.dailystruggle.yaml.YamlSection) {
                    continue;
                }
                closedWorldSymbols.add(key);
                advertisedConfigDefaults.put(key, val);
                String comment = yaml.getComment(key);
                if (comment != null && !comment.isBlank()) {
                    advertisedConfigComments.put(key, COMMENT_HASH_PREFIX_RE.matcher(comment).replaceAll("").replace('\n', ' ').trim());
                }
            }
        }

        boolean delegatesConfigToExternalOrParser = false;
        for (MethodNode m : allMethods) {
            for (String inst : m.instantiatedClasses()) {
                if (!classesByName.containsKey(inst)
                        && (inst.endsWith("Runtime") || inst.endsWith("Config") || inst.endsWith("Configs"))) {
                    delegatesConfigToExternalOrParser = true;
                    break;
                }
            }
            if (delegatesConfigToExternalOrParser) {
                break;
            }
            for (String call : m.calledMethods()) {
                if (call.contains("ConfigParser#") || call.endsWith("#getConfigParser")) {
                    delegatesConfigToExternalOrParser = true;
                    break;
                }
                int hash = call.indexOf('#');
                if (hash > 0) {
                    String calleeOwner = call.substring(0, hash);
                    String calleeMethod = call.substring(hash + 1);
                    if (!classesByName.containsKey(calleeOwner)
                            && (calleeOwner.endsWith("Runtime") || calleeOwner.endsWith("Config") || calleeOwner.endsWith("Configs"))
                            && ("load".equals(calleeMethod) || "reload".equals(calleeMethod)
                            || "reloadAndValidate".equals(calleeMethod) || "start".equals(calleeMethod)
                            || "parse".equals(calleeMethod))) {
                        delegatesConfigToExternalOrParser = true;
                        break;
                    }
                }
            }
            if (delegatesConfigToExternalOrParser) {
                break;
            }
        }

        if (!jarUsesDynamicConfigIteration && !delegatesConfigToExternalOrParser && !advertisedConfigDefaults.isEmpty()) {
            for (var entry : advertisedConfigDefaults.entrySet()) {
                String key = entry.getKey();
                String leaf = SchemaInferrer.extractLeaf(key);
                String camelLeaf = BytecodeConfigScanner.toCamelCaseIdentifier(leaf);
                boolean referenced = allReferencedConfigKeys.contains(key)
                        || allReferencedConfigKeys.contains(leaf)
                        || allStringLiterals.contains(key)
                        || allStringLiterals.contains(leaf)
                        || (!camelLeaf.isEmpty() && allGloballyReadFieldNames.contains(camelLeaf));
                // Also check if any prefix section is referenced
                if (!referenced && key.contains(".")) {
                    String prefix = key.substring(0, key.lastIndexOf('.'));
                    if (allStringLiterals.contains(prefix) && jarUsesDynamicConfigIteration) {
                        referenced = true;
                    }
                }
                if (!referenced) {
                    String commentDesc = advertisedConfigComments.getOrDefault(key, "");
                    String declaredStr = "config.yml key '" + key + ": " + entry.getValue() + "'"
                            + (commentDesc.isEmpty() ? "" : (" (\"" + commentDesc + "\")"));
                    findings.add(new CausalFinding(
                            "CAUSAL-" + (findingSeq++),
                            effName,
                            1,
                            FindingKind.CAUSAL_DEAD_CONFIG_KEY,
                            ConfidenceTier.CONFIRMED,
                            100,
                            key,
                            declaredStr,
                            "Key '" + key + "' has 0 getBoolean/getInt/getString/getDouble bytecode references in " + effName + ".",
                            "Read '" + key + "' via getConfig() and wire it to the target runtime behavior, or remove the dead key from config.yml.",
                            List.of("config.yml:" + key + " -> [0 BYTECODE READERS]"),
                            ""
                    ));
                }
            }
        }

        // =========================================================================
        // 4. Evaluate Order 2: Unread Config Fields, Unit Mismatch, Orphaned Suppliers,
        //    Swallowed Failures (S-004), and Async Main-Thread Sinks (S-005)
        // =========================================================================
        Set<String> reportedUnreadFields = new LinkedHashSet<>();
        for (MethodNode m : allMethods) {
            // 4a. Unread config field (Order 2)
            for (var kf : m.configKeyToAssignedField().entrySet()) {
                String configKey = kf.getKey();
                String fieldRef = kf.getValue();
                int hash = fieldRef.indexOf('#');
                String shortField = hash >= 0 ? fieldRef.substring(hash + 1) : fieldRef;
                Set<String> readers = new LinkedHashSet<>();
                readers.addAll(fieldToReaderMethods.getOrDefault(fieldRef, Set.of()));
                readers.addAll(fieldToReaderMethods.getOrDefault(shortField, Set.of()));
                readers.remove(m.symbol());
                if (readers.isEmpty() && reportedUnreadFields.add(fieldRef)) {
                    String commentDesc = advertisedConfigComments.getOrDefault(configKey, "");
                    String declaredStr = "config.yml key '" + configKey + "'"
                            + (commentDesc.isEmpty() ? "" : (" (\"" + commentDesc + "\")"))
                            + " loaded in " + simpleMethodRef(m.symbol());
                    findings.add(new CausalFinding(
                            "CAUSAL-" + (findingSeq++),
                            effName,
                            2,
                            FindingKind.CAUSAL_UNREAD_CONFIG_FIELD,
                            ConfidenceTier.CONFIRMED,
                            100,
                            fieldRef,
                            declaredStr,
                            "Assigned to field " + simpleMethodRef(fieldRef)
                                    + ", which has 0 getfield/getstatic read sites in any command, event, or supplier chain across " + effName + ".",
                            "Read " + simpleMethodRef(fieldRef) + " in the corresponding command or event handler.",
                            List.of(m.symbol() + " --[putfield " + shortField + "]--> [0 READERS]"),
                            ""
                    ));
                } else if (!readers.isEmpty()) {
                    // 4b. Check Unit Mismatch (seconds added/compared to currentTimeMillis without * 1000L)
                    String keyLower = configKey.toLowerCase(Locale.ROOT);
                    boolean looksLikeSeconds = (keyLower.contains("second") || keyLower.endsWith("-sec")
                            || keyLower.contains("warmup") || keyLower.contains("cooldown"))
                            && !keyLower.contains("ms") && !keyLower.contains("millis") && !keyLower.contains("tick");
                    String loadScaling = m.configKeyScalingHint().get(configKey);
                    if (looksLikeSeconds && loadScaling == null) {
                        for (String readerSym : readers) {
                            MethodNode readerNode = methodsBySymbol.get(readerSym);
                            if (readerNode != null
                                    && readerNode.callsCurrentTimeMillis()
                                    && !readerNode.multipliesBy1000Or20()) {
                                findings.add(new CausalFinding(
                                        "CAUSAL-" + (findingSeq++),
                                        effName,
                                        2,
                                        FindingKind.CAUSAL_UNIT_MISMATCH,
                                        ConfidenceTier.HIGH,
                                        90,
                                        readerNode.symbol(),
                                        "Config key '" + configKey + "' specifies a duration in seconds",
                                        simpleMethodRef(readerNode.symbol()) + " combines unscaled '" + shortField
                                                + "' directly with System.currentTimeMillis() without * 1000L conversion (treating seconds as milliseconds).",
                                        "Multiply " + shortField + " by 1000L (or use TimeUnit.SECONDS.toMillis) before comparing with System.currentTimeMillis().",
                                        List.of(
                                                m.symbol() + " [loads " + configKey + " -> " + shortField + " (scaling: NONE)]",
                                                readerNode.symbol() + " [System.currentTimeMillis() + unscaled " + shortField + "]"
                                        ),
                                        ""
                                ));
                            }
                        }
                    }
                }
            }

            // 4c. Direct in-method unit mismatch (key read directly in method that calls currentTimeMillis without * 1000)
            for (String configKey : m.referencedConfigKeys()) {
                if (m.configKeyToAssignedField().containsKey(configKey)) {
                    continue;
                }
                String keyLower = configKey.toLowerCase(Locale.ROOT);
                boolean looksLikeSeconds = (keyLower.contains("second") || keyLower.endsWith("-sec"))
                        && !keyLower.contains("ms") && !keyLower.contains("millis") && !keyLower.contains("tick");
                if (looksLikeSeconds
                        && m.configKeyScalingHint().get(configKey) == null
                        && m.callsCurrentTimeMillis()
                        && !m.multipliesBy1000Or20()) {
                    findings.add(new CausalFinding(
                            "CAUSAL-" + (findingSeq++),
                            effName,
                            2,
                            FindingKind.CAUSAL_UNIT_MISMATCH,
                            ConfidenceTier.HIGH,
                            90,
                            m.symbol(),
                            "Config key '" + configKey + "' specifies a duration in seconds",
                            simpleMethodRef(m.symbol()) + " reads '" + configKey
                                    + "' and combines it with System.currentTimeMillis() without * 1000L scaling.",
                            "Multiply '" + configKey + "' by 1000L before adding or comparing against System.currentTimeMillis().",
                            List.of(m.symbol() + " [getInt(\"" + configKey + "\") + System.currentTimeMillis() (unscaled)]"),
                            ""
                    ));
                }
            }

            // 4d. Orphaned Supplier / CompletableFuture continuation (Order 2)
            for (String orphaned : m.orphanedSuppliers()) {
                MethodNode targetLambda = findMethodNodeFlexible(methodsBySymbol, methodsByName, m.ownerClass(), orphaned);
                boolean lambdaSelfDelivers = targetLambda != null && hasTerminalDeliverySink(targetLambda.directEffectSinks());
                if (!lambdaSelfDelivers) {
                    findings.add(new CausalFinding(
                            "CAUSAL-" + (findingSeq++),
                            effName,
                            2,
                            FindingKind.CAUSAL_ORPHANED_SUPPLIER,
                            ConfidenceTier.CONFIRMED,
                            100,
                            m.symbol(),
                            simpleMethodRef(m.symbol()) + " spawns supplier/future " + simpleMethodRef(orphaned),
                            "Return value of " + simpleMethodRef(orphaned)
                                    + " is immediately popped off the stack (POP opcode) without chaining .thenAccept/.whenComplete or consuming the result.",
                            "Chain CompletableFuture#thenAccept / #whenComplete (or consume Supplier#get) to deliver the computed result to the player/world sink.",
                            List.of(m.symbol() + " --[spawns]--> " + orphaned + " --[POP return value]--> [DISCARDED]"),
                            ""
                    ));
                }
            }

            // 4e. Swallowed operational failure (S-004, Order 2)
            boolean isEntryOrIoHandler = classifyEntryPoint(m, classesByName.get(m.ownerClass()), allAsyncSpawnedLambdas) != null
                    || m.name().startsWith("lambda$")
                    || m.swallowedExceptions().stream().anyMatch(ex ->
                    ex.endsWith("IOException") || ex.endsWith("SQLException"));
            String mNameLow = m.name().toLowerCase(Locale.ROOT);
            if (!m.swallowedExceptions().isEmpty()
                    && isEntryOrIoHandler
                    && ((m.accessFlags() & 0x0002) == 0 || m.name().startsWith("lambda$"))
                    && m.descriptor().endsWith(")V")
                    && !m.name().equals("onDisable")
                    && !m.name().equals("close")
                    && !m.name().equals("<clinit>")
                    && !mNameLow.contains("shutdown")
                    && !mNameLow.contains("prune")
                    && !mNameLow.contains("paint")
                    && !mNameLow.contains("invalidate")
                    && !mNameLow.contains("close")
                    && !m.name().startsWith("safe")
                    && !m.name().startsWith("try")
                    && !m.name().startsWith("is")
                    && !m.name().startsWith("has")
                    && !m.name().startsWith("delete")
                    && !m.name().startsWith("cleanup")) {
                for (String excType : m.swallowedExceptions()) {
                      findings.add(new CausalFinding(
                              "CAUSAL-" + (findingSeq++),
                              effName,
                              2,
                              FindingKind.CAUSAL_SWALLOWED_FAILURE,
                              ConfidenceTier.CONFIRMED,
                              100,
                              m.symbol(),
                              simpleMethodRef(m.symbol()) + " executes operational pipeline with try/catch(" + simpleClassName(excType) + ")",
                              "Exception handler for " + simpleClassName(excType) + " in " + simpleMethodRef(m.symbol())
                                      + " executes an empty catch/return with 0 log calls, 0 user feedback messages, and 0 fallback actions (silently hides runtime failures from operators and players).",
                              "Log the exception with a warning and notify the caller or roll back state.",
                              List.of(m.symbol() + " --[catch (" + simpleClassName(excType) + ")]--> [SILENT_EMPTY_RETURN]"),
                              ""
                      ));
                  }
              }
          }

          // 4f. Async Supplier/Runnable calling synchronous main-thread Bukkit APIs (S-005, Order 2)
          for (String asyncLambdaSym : allAsyncSpawnedLambdas) {
              MethodNode asyncNode = findMethodNodeFlexible(methodsBySymbol, methodsByName, null, asyncLambdaSym);
              if (asyncNode == null
                      || asyncNode.semanticMacros().contains("@scheduler_dispatch")
                      || asyncNode.semanticMacros().contains("@folia_region_guard")) {
                  continue;
              }
              Set<String> mainThreadViolations = new LinkedHashSet<>(asyncNode.syncMainThreadCalls());
              for (String hop1 : asyncNode.calledMethods()) {
                  MethodNode hopNode = findMethodNodeFlexible(methodsBySymbol, methodsByName, asyncNode.ownerClass(), hop1);
                  if (hopNode != null
                          && !hopNode.semanticMacros().contains("@scheduler_dispatch")
                          && !hopNode.semanticMacros().contains("@folia_region_guard")) {
                      mainThreadViolations.addAll(hopNode.syncMainThreadCalls());
                  }
              }
              if (!mainThreadViolations.isEmpty()) {
                  findings.add(new CausalFinding(
                          "CAUSAL-" + (findingSeq++),
                          effName,
                          2,
                          FindingKind.CAUSAL_ASYNC_MAIN_THREAD_SINK,
                          ConfidenceTier.CONFIRMED,
                          100,
                          asyncNode.symbol(),
                          "Async task/supplier " + simpleMethodRef(asyncNode.symbol()) + " executes off the main server thread",
                          "Directly invokes synchronous main-thread-only API " + mainThreadViolations
                                  + " from an async thread (unsafe off-thread world access; can corrupt chunk state or crash regional threading).",
                          "Use asynchronous APIs (e.g. getChunkAtAsync / teleportAsync) or marshal world mutations back to the region/main thread scheduler.",
                          List.of("ASYNC_SPAWN -> " + asyncNode.symbol() + " -> " + mainThreadViolations),
                          ""
                  ));
              }
          }

          // 4g. Folia support declaration flag (evaluated causally in traceCausalFoliaRuntimeExceptions; F-001..F-003, S-005)
          boolean declaresFoliaSupported = false;
          for (var cfgEntry : configs.entrySet()) {
              String fn = cfgEntry.getKey() == null ? "" : cfgEntry.getKey().toLowerCase(Locale.ROOT);
              String body = cfgEntry.getValue();
              if ((fn.endsWith("plugin.yml") || fn.endsWith("paper-plugin.yml")) && body != null) {
                  if (FOLIA_SUPPORTED_TRUE_RE.matcher(body.toLowerCase(Locale.ROOT)).matches()) {
                      declaresFoliaSupported = true;
                      break;
                  }
              }
          }
        // =========================================================================
        // 5. Build Bounded k-Hop Causal Chains for Entry Points & Check Hollow Commands
        // =========================================================================
        Map<String, List<MethodNode>> registeredEventHandlersByEventType = new LinkedHashMap<>();
        for (MethodNode m : allMethods) {
            if (!m.isEventHandler() || m.eventTypeSimpleName().isEmpty()) {
                continue;
            }
            ListenerRegistrationMode rm = listenerRegistrations.get(m.ownerClass());
            if (rm == ListenerRegistrationMode.UNREGISTERED) {
                continue;
            }
            registeredEventHandlersByEventType
                    .computeIfAbsent(m.eventTypeSimpleName(), k -> new ArrayList<>())
                    .add(m);
        }

        List<CausalChainSlice> entryChains = new ArrayList<>();
        List<BytecodeCommandSchema> commandSchemas = new ArrayList<>();
        Set<String> evaluatedCommandSymbols = new LinkedHashSet<>();
        for (MethodNode m : allMethods) {
            if ((m.accessFlags() & (ACC_SYNTHETIC | 0x0040)) != 0 && !m.name().startsWith("lambda$")) {
                continue;
            }
            ParsedClass ownerPc = classesByName.get(m.ownerClass());
            EntryPointKind epKind = classifyEntryPoint(m, ownerPc, allAsyncSpawnedLambdas);
            if (epKind == null) {
                continue;
            }
            if (epKind == EntryPointKind.COMMAND && !evaluatedCommandSymbols.add(m.symbol())) {
                continue;
            }
            CausalChainSlice slice = buildCausalSlice(
                    epKind,
                    m,
                    ownerPc,
                    methodsBySymbol,
                    methodsByName,
                    fieldToReaderMethods,
                    registeredEventHandlersByEventType,
                    cmds,
                    listenerRegistrations,
                    configGatedListeners
            );
            entryChains.add(slice);

            if (epKind == EntryPointKind.COMMAND) {
                commandSchemas.addAll(extractCommandSchemasForEntry(m, ownerPc, methodsBySymbol, methodsByName, cmds));
            }

            if (epKind == EntryPointKind.COMMAND
                    && !jarUsesReflectiveScanner
                    && ownerPc != null
                    && ownerPc.isConcrete()) {
                boolean hasCosmeticOrOrphanedOnly = (!slice.reachableEffectSinks().isEmpty()
                        && isHollowMessageOnlySinkSet(slice.reachableEffectSinks()))
                        || (!m.orphanedSuppliers().isEmpty() && isHollowMessageOnlySinkSet(slice.reachableEffectSinks()));
                if (declaresActionableEffect(slice.declaredBehavior()) && hasCosmeticOrOrphanedOnly) {
                    findings.add(new CausalFinding(
                            "CAUSAL-" + (findingSeq++),
                            effName,
                            2,
                            FindingKind.CAUSAL_HOLLOW_COMMAND,
                            ConfidenceTier.HIGH,
                            85,
                            m.symbol(),
                            slice.declaredBehavior().isBlank()
                                    ? ("Command handler " + simpleMethodRef(m.symbol()))
                                    : slice.declaredBehavior(),
                            "Across the " + MAX_CAUSAL_HOPS + "-hop bytecode causal chain (including spawned lambdas/suppliers), "
                                    + "reachable sinks are "
                                    + (slice.reachableEffectSinks().isEmpty() ? "[NO_OP]" : slice.reachableEffectSinks())
                                    + " with 0 teleport, world, inventory, economy, storage, or state mutations.",
                            "Invoke the downstream domain effect sink (e.g. teleportAsync, openInventory, economy transaction, or state mutation) from "
                                    + simpleMethodRef(m.symbol()) + ".",
                            slice.hopTrace(),
                              ""
                      ));
                  }
              }
          }

          if (declaresFoliaSupported) {
              List<CausalFinding> foliaCausalFindings = traceCausalFoliaRuntimeExceptions(
                      effName,
                      findingSeq,
                      allMethods,
                      classesByName,
                      methodsBySymbol,
                      methodsByName,
                      listenerRegistrations,
                      allAsyncSpawnedLambdas,
                      findings
              );
              findings.addAll(foliaCausalFindings);
              findingSeq += foliaCausalFindings.size();
          }

          List<CausalFinding> backdoorFindings = traceCausalBackdoorAndDropperChains(
                  effName,
                  findingSeq,
                  allMethods,
                  classesByName,
                  methodsBySymbol,
                  methodsByName,
                  cmds,
                  allAsyncSpawnedLambdas
          );
          findings.addAll(backdoorFindings);
          findingSeq += backdoorFindings.size();
        // =========================================================================
        // 6. corpus-atlas-ADR-002 Parity Passes:
        //    - Whole-Class Eraser Lock-Set Shared-State Matrix (CAUSAL-RACE)
        //    - N-Hop Synchronous Main-Thread I/O Contagion (CAUSAL-MAIN-IO, S-005)
        //    - Unclosed Resource & ChunkTicket Leak Detection (CAUSAL-LEAK, S-002)
        //    - Hoare EffectFrame, Semantic Macros & ProofObligation Synthesis
        // =========================================================================
        BytecodeParityAnalyzers.SharedStateAnalysisResult sharedStateResult =
                BytecodeParityAnalyzers.computeClassSharedStateMatrix(
                        effName,
                        findingSeq,
                        classesByName,
                        methodsBySymbol,
                        allAsyncSpawnedLambdas
                );
        findings.addAll(sharedStateResult.findings());
        findingSeq += sharedStateResult.findings().size();

        List<CausalFinding> syncMainIoFindings =
                BytecodeParityAnalyzers.detectSyncMainThreadIoContagion(
                        effName,
                        findingSeq,
                        classesByName,
                        methodsBySymbol,
                        methodsByName,
                        allAsyncSpawnedLambdas
                );
        findings.addAll(syncMainIoFindings);
        findingSeq += syncMainIoFindings.size();

        List<CausalFinding> leakFindings =
                BytecodeParityAnalyzers.detectUnclosedResourceLeaks(
                        effName,
                        findingSeq,
                        classesByName,
                        methodsBySymbol,
                        methodsByName,
                        listenerRegistrations
                );
        findings.addAll(leakFindings);

        List<CausalChainSlice> enrichedChains =
                BytecodeParityAnalyzers.enrichSlicesWithHoareFramesAndProofs(
                        entryChains,
                        methodsBySymbol,
                        methodsByName,
                        findings
                );

        ExternalDependencyClassification depClassification =
                classifyExternalDependencies(allReferencedClasses, classesByName.keySet());

        return new CausalGraphReport(
                effId,
                effName,
                enrichedChains,
                listenerRegistrations,
                configGatedListeners,
                findings,
                closedWorldSymbols,
                commandSchemas,
                sharedStateResult.hazards(),
                depClassification.inferredEcosystemDependencies(),
                depClassification.inferredExternalPackages()
        );
    }

    static EntryPointKind classifyEntryPoint(
            MethodNode m,
            ParsedClass owner,
            Set<String> allAsyncSpawnedLambdas
    ) {
        if (m.isEventHandler()) {
            return EntryPointKind.EVENT;
        }
        if (m.isCommandAnnotated()
                || "onCommand".equals(m.name())
                || (owner != null && owner.interfaces().stream().anyMatch(i ->
                i.endsWith("CommandExecutor") || i.endsWith("CommandsAPICommand") || i.endsWith("TreeCommand"))
                && ("onCommand".equals(m.name()) || "execute".equals(m.name())))) {
            return EntryPointKind.COMMAND;
        }
        if (allAsyncSpawnedLambdas.contains(m.symbol())
                || (owner != null && owner.interfaces().stream().anyMatch(i ->
                i.endsWith("Supplier") || i.endsWith("Callable")) && ("get".equals(m.name()) || "call".equals(m.name())))) {
            return EntryPointKind.SUPPLIER;
        }
        return null;
    }

    private static CausalChainSlice buildCausalSlice(
            EntryPointKind kind,
            MethodNode root,
            ParsedClass rootOwner,
            Map<String, MethodNode> methodsBySymbol,
            Map<String, List<MethodNode>> methodsByName,
            Map<String, Set<String>> fieldToReaderMethods,
            Map<String, List<MethodNode>> registeredEventHandlersByEventType,
            List<JarConfigScanner.CommandDescriptor> commandDescriptors,
            Map<String, ListenerRegistrationMode> listenerRegistrations,
            Map<String, String> configGatedListeners
    ) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<MethodHop> queue = new ArrayDeque<>();
        queue.add(new MethodHop(root, 0));
        visited.add(root.symbol());

        List<String> guards = new ArrayList<>(root.guards());
        Set<String> readConfigs = new LinkedHashSet<>(root.referencedConfigKeys());
        Set<String> readFields = new LinkedHashSet<>();
        Set<String> mutatedFields = new LinkedHashSet<>();
        List<String> spawnedLambdas = new ArrayList<>();
        List<String> hopTrace = new ArrayList<>();
        Set<String> reachableSinks = new LinkedHashSet<>();

        while (!queue.isEmpty()) {
            MethodHop current = queue.poll();
            MethodNode node = current.node();
            int depth = current.depth();

            for (String rf : node.readFields()) {
                readFields.add(simpleMethodRef(rf));
            }
            for (String wf : node.writtenFields()) {
                mutatedFields.add(simpleMethodRef(wf));
                reachableSinks.add("STATE_MUTATION");
            }
            for (String qf : node.producedQueueFields()) {
                mutatedFields.add(simpleMethodRef(qf) + " [queue]");
            }
            readConfigs.addAll(node.referencedConfigKeys());
            for (String g : node.guards()) {
                if (!guards.contains(g)) {
                    guards.add(g);
                }
            }
            reachableSinks.addAll(node.directEffectSinks());

            String hopSummary = "Hop " + depth + ": " + simpleMethodRef(node.symbol())
                    + (node.directEffectSinks().isEmpty() ? "" : (" -> sinks=" + node.directEffectSinks()));
            hopTrace.add(hopSummary);

            if (depth >= MAX_CAUSAL_HOPS || visited.size() >= 256) {
                continue;
            }

            // Follow direct method calls
            for (String called : node.calledMethods()) {
                MethodNode target = findMethodNodeFlexible(methodsBySymbol, methodsByName, node.ownerClass(), called);
                if (target != null && visited.add(target.symbol())) {
                    queue.add(new MethodHop(target, depth + 1));
                }
            }

            // Follow invokedynamic spawned lambdas / suppliers
            for (String lambdaSym : node.spawnedLambdas()) {
                if (!spawnedLambdas.contains(simpleMethodRef(lambdaSym))) {
                    spawnedLambdas.add(simpleMethodRef(lambdaSym));
                }
                // Only traverse into a spawned lambda if its return value was not immediately popped!
                if (!node.orphanedSuppliers().contains(lambdaSym)) {
                    MethodNode target = findMethodNodeFlexible(methodsBySymbol, methodsByName, node.ownerClass(), lambdaSym);
                    if (target != null && visited.add(target.symbol())) {
                        queue.add(new MethodHop(target, depth + 1));
                    }
                }
            }

            // Follow instantiated Runnable/Supplier/Callable task classes when scheduled
            for (String instCls : node.instantiatedClasses()) {
                for (String taskEntry : List.of("run", "get", "call", "accept")) {
                    MethodNode taskMethod = methodsBySymbol.get(instCls + "#" + taskEntry);
                    if (taskMethod != null && visited.add(taskMethod.symbol())) {
                        queue.add(new MethodHop(taskMethod, depth + 1));
                    }
                }
            }

            // Follow decoupled custom event dispatch edges (NEW CustomEvent + callEvent/post -> registered @EventHandler)
            Set<String> candidateEvents = new LinkedHashSet<>(node.dispatchedEventTypes());
            if (node.directEffectSinks().contains("EVENT_DISPATCH")) {
                for (String instCls : node.instantiatedClasses()) {
                    candidateEvents.add(simpleClassName(instCls));
                }
            }
            for (String evSimple : candidateEvents) {
                for (MethodNode evHandler : registeredEventHandlersByEventType.getOrDefault(evSimple, List.of())) {
                    String handoffLabel = "event:" + evSimple + "->" + simpleMethodRef(evHandler.symbol());
                    if (!spawnedLambdas.contains(handoffLabel)) {
                        spawnedLambdas.add(handoffLabel);
                    }
                    if (visited.add(evHandler.symbol())) {
                        queue.add(new MethodHop(evHandler, depth + 1));
                    }
                }
            }

            // Follow 1-hop field dataflow (putfield -> reader method) and queue producer -> consumer dataflow
            Set<String> dataflowFields = new LinkedHashSet<>(node.writtenFields());
            for (String qf : node.producedQueueFields()) {
                dataflowFields.add(qf);
                int hash = qf.indexOf('#');
                if (hash >= 0) {
                    dataflowFields.add(qf.substring(hash + 1));
                }
            }
            for (String wf : dataflowFields) {
                for (String readerSym : fieldToReaderMethods.getOrDefault(wf, Set.of())) {
                    MethodNode readerNode = methodsBySymbol.get(readerSym);
                    if (readerNode != null && visited.add(readerNode.symbol())) {
                        queue.add(new MethodHop(readerNode, depth + 1));
                    }
                }
            }
        }

        String declared = resolveDeclaredBehavior(kind, root, rootOwner, commandDescriptors);
        ListenerRegistrationMode regMode = kind == EntryPointKind.EVENT
                ? listenerRegistrations.getOrDefault(root.ownerClass(), ListenerRegistrationMode.REGISTERED_UNCONDITIONAL)
                : ListenerRegistrationMode.NOT_APPLICABLE;
        String gatingKey = kind == EntryPointKind.EVENT
                ? configGatedListeners.getOrDefault(root.ownerClass(), "")
                : "";

        return new CausalChainSlice(
                kind,
                root.symbol(),
                declared,
                regMode,
                gatingKey,
                guards,
                new ArrayList<>(readConfigs),
                new ArrayList<>(readFields),
                new ArrayList<>(mutatedFields),
                spawnedLambdas,
                hopTrace,
                reachableSinks
        );
    }

    private record MethodHop(MethodNode node, int depth) {
    }

    private static List<BytecodeCommandSchema> extractCommandSchemasForEntry(
            MethodNode root,
            ParsedClass ownerPc,
            Map<String, MethodNode> methodsBySymbol,
            Map<String, List<MethodNode>> methodsByName,
            List<JarConfigScanner.CommandDescriptor> commandDescriptors
    ) {
        Set<String> roots = new LinkedHashSet<>(root.annotatedCommandRoots());
        if (ownerPc != null) {
            roots.addAll(ownerPc.classAnnotations().commandRoots());
        }
        String simpleCls = simpleClassName(root.ownerClass()).toLowerCase(Locale.ROOT);
        for (JarConfigScanner.CommandDescriptor cd : commandDescriptors) {
            String cmdLow = cd.name().toLowerCase(Locale.ROOT);
            if (simpleCls.contains(cmdLow) || root.stringLiterals().contains(cd.name()) || commandDescriptors.size() == 1) {
                roots.add(cmdLow);
            }
        }
        if (roots.isEmpty()) {
            String fallback = CMD_SUFFIX_RE.matcher(simpleCls).replaceAll("").trim();
            roots.add(fallback.isEmpty() ? "cmd" : fallback);
        }

        Set<String> subcommands = new LinkedHashSet<>(root.subcommandLiterals());
        Set<String> argParsers = new LinkedHashSet<>(root.argumentParsers());
        Set<String> inlinePerms = new LinkedHashSet<>();
        if (ownerPc != null) {
            inlinePerms.addAll(ownerPc.classAnnotations().permissions());
        }
        inlinePerms.addAll(root.inlinePermissions());

        // Merge 1-hop helper methods in the same plugin JAR
        for (String called : root.calledMethods()) {
            MethodNode hop1 = findMethodNodeFlexible(methodsBySymbol, methodsByName, root.ownerClass(), called);
            if (hop1 != null) {
                subcommands.addAll(hop1.subcommandLiterals());
                argParsers.addAll(hop1.argumentParsers());
                inlinePerms.addAll(hop1.inlinePermissions());
            }
        }

        List<BytecodeCommandSchema> out = new ArrayList<>(roots.size());
        for (String r : roots) {
            if (r == null || r.isBlank()) {
                continue;
            }
            out.add(new BytecodeCommandSchema(
                    r,
                    root.symbol(),
                    new ArrayList<>(subcommands),
                    new ArrayList<>(argParsers),
                    new ArrayList<>(inlinePerms)
            ));
        }
        return out;
    }

    private static String resolveDeclaredBehavior(
            EntryPointKind kind,
            MethodNode root,
            ParsedClass rootOwner,
            List<JarConfigScanner.CommandDescriptor> commandDescriptors
    ) {
        if (kind == EntryPointKind.COMMAND) {
            List<String> annRoots = new ArrayList<>(root.annotatedCommandRoots());
            if (annRoots.isEmpty() && rootOwner != null) {
                annRoots.addAll(rootOwner.classAnnotations().commandRoots());
            }
            String annDesc = !root.commandDescription().isBlank()
                    ? root.commandDescription()
                    : (rootOwner != null ? rootOwner.classAnnotations().description() : "");
            if (!annRoots.isEmpty()) {
                String primaryRoot = annRoots.get(0);
                String subSuffix = root.subcommandLiterals().isEmpty()
                        ? ""
                        : (" " + root.subcommandLiterals().get(0));
                for (JarConfigScanner.CommandDescriptor cd : commandDescriptors) {
                    if (cd.name().equalsIgnoreCase(primaryRoot)
                            || cd.aliases().stream().anyMatch(a -> a.equalsIgnoreCase(primaryRoot))) {
                        String desc = !annDesc.isBlank()
                                ? annDesc
                                : (cd.description().isBlank() ? ("Executes /" + primaryRoot + subSuffix) : cd.description());
                        return "/" + primaryRoot + subSuffix + " — \"" + desc + "\"";
                    }
                }
                String desc = !annDesc.isBlank() ? annDesc : ("Executes /" + primaryRoot + subSuffix);
                return "/" + primaryRoot + subSuffix + " — \"" + desc + "\"";
            }

            String simpleCls = simpleClassName(root.ownerClass()).toLowerCase(Locale.ROOT);
            for (JarConfigScanner.CommandDescriptor cd : commandDescriptors) {
                String cmdLow = cd.name().toLowerCase(Locale.ROOT);
                if (simpleCls.contains(cmdLow) || root.stringLiterals().contains(cd.name()) || commandDescriptors.size() == 1) {
                    String desc = cd.description().isBlank() ? ("Executes /" + cd.name()) : cd.description();
                    return "/" + cd.name() + " — \"" + desc + "\"";
                }
            }
            return "Command handler " + simpleMethodRef(root.symbol());
        }
        if (kind == EntryPointKind.EVENT) {
            return simpleMethodRef(root.symbol()) + " (@EventHandler " + root.eventTypeSimpleName() + ")";
        }
        return "Supplier/Lambda " + simpleMethodRef(root.symbol());
    }

    private static boolean declaresActionableEffect(String declaredBehavior) {
        if (declaredBehavior == null || declaredBehavior.isBlank()) {
            return false;
        }
        String low = declaredBehavior.toLowerCase(Locale.ROOT);
        return low.contains("teleport")
                || low.contains("wild")
                || low.contains("rtp")
                || low.contains("warp")
                || low.contains("spawn")
                || low.contains("home")
                || low.contains("menu")
                || low.contains("gui")
                || low.contains("inventory")
                || low.contains("shop")
                || low.contains("withdraw")
                || low.contains("deposit")
                || low.contains("economy")
                || low.contains("heal")
                || low.contains("feed");
    }

    private static boolean isHollowMessageOnlySinkSet(Set<String> sinks) {
        if (sinks == null || sinks.isEmpty()) {
            return true;
        }
        for (String s : sinks) {
            if (!"MESSAGE_ONLY".equals(s)
                    && !"SOUND_PARTICLE_ONLY".equals(s)
                    && !"ASYNC_SCHEDULER".equals(s)
                    && !"SCHEDULER_TASK".equals(s)
                    && !"EVENT_DISPATCH".equals(s)
                    && !"QUEUE_HANDOFF".equals(s)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasTerminalDeliverySink(Set<String> sinks) {
        if (sinks == null || sinks.isEmpty()) {
            return false;
        }
        for (String s : sinks) {
            if ("TELEPORT".equals(s)
                    || "INVENTORY_MENU".equals(s)
                    || "WORLD_MUTATION".equals(s)
                    || "ECONOMY".equals(s)
                    || "STORAGE_IO".equals(s)
                    || "STATE_MUTATION".equals(s)
                    || "EVENT_CANCEL".equals(s)) {
                return true;
            }
        }
        return false;
    }

    static MethodNode findMethodNodeFlexible(
            Map<String, MethodNode> methodsBySymbol,
            Map<String, List<MethodNode>> methodsByName,
            String defaultOwner,
            String ref
    ) {
        if (ref == null || ref.isEmpty()) {
            return null;
        }
        MethodNode exact = methodsBySymbol.get(ref);
        if (exact != null) {
            return exact;
        }
        if (!ref.contains("#") && defaultOwner != null) {
            MethodNode sameOwner = methodsBySymbol.get(defaultOwner + "#" + ref);
            if (sameOwner != null) {
                return sameOwner;
            }
        }
        int hash = ref.indexOf('#');
        String methodName = hash >= 0 ? ref.substring(hash + 1) : ref;
        String ownerSuffix = hash >= 0 ? ref.substring(0, hash) : "";
        if (methodsByName != null) {
            List<MethodNode> byName = methodsByName.get(methodName);
            if (byName != null) {
                for (MethodNode cand : byName) {
                    if (ownerSuffix.isEmpty() || cand.ownerClass().endsWith(ownerSuffix)) {
                        return cand;
                    }
                }
            }
            return null;
        }
        if (!ownerSuffix.isEmpty() && ownerSuffix.indexOf('.') >= 0) {
            return null;
        }
        for (var e : methodsBySymbol.entrySet()) {
            MethodNode cand = e.getValue();
            if (cand.name().equals(methodName)
                    && (ownerSuffix.isEmpty() || cand.ownerClass().endsWith(ownerSuffix))) {
                return cand;
            }
        }
        return null;
    }

    /**
     * Parses a raw {@code .class} byte array into a {@link ParsedClass} with constant pool,
     * method bodies, exception tables, annotations, and {@code BootstrapMethods} lambda links.
     */
    static ParsedClass parseClassBytes(byte[] classBytes) {
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
            String[] numericPool = new String[cpCount];
            int[] classNameIdx = new int[cpCount];
            int[] stringUtf8Idx = new int[cpCount];
            int[] fieldClassIdx = new int[cpCount];
            int[] fieldNatIdx = new int[cpCount];
            int[] methodClassIdx = new int[cpCount];
            int[] methodNatIdx = new int[cpCount];
            int[] natNameIdx = new int[cpCount];
            int[] natDescIdx = new int[cpCount];
            int[] methodHandleRefIdx = new int[cpCount];
            int[] indyBootstrapIdx = new int[cpCount];
            int[] indyNatIdx = new int[cpCount];

            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case CONSTANT_Utf8 -> utf8Pool[i] = in.readUTF();
                    case CONSTANT_Integer -> numericPool[i] = Integer.toString(in.readInt());
                    case CONSTANT_Float -> numericPool[i] = Float.toString(in.readFloat());
                    case CONSTANT_Long -> {
                        numericPool[i] = Long.toString(in.readLong());
                        i++;
                    }
                    case CONSTANT_Double -> {
                        numericPool[i] = Double.toString(in.readDouble());
                        i++;
                    }
                    case CONSTANT_Class -> classNameIdx[i] = in.readUnsignedShort();
                    case CONSTANT_String -> stringUtf8Idx[i] = in.readUnsignedShort();
                    case CONSTANT_Fieldref -> {
                        fieldClassIdx[i] = in.readUnsignedShort();
                        fieldNatIdx[i] = in.readUnsignedShort();
                    }
                    case CONSTANT_Methodref, CONSTANT_InterfaceMethodref -> {
                        methodClassIdx[i] = in.readUnsignedShort();
                        methodNatIdx[i] = in.readUnsignedShort();
                    }
                    case CONSTANT_NameAndType -> {
                        natNameIdx[i] = in.readUnsignedShort();
                        natDescIdx[i] = in.readUnsignedShort();
                    }
                    case CONSTANT_MethodHandle -> {
                        in.readUnsignedByte(); // reference_kind
                        methodHandleRefIdx[i] = in.readUnsignedShort();
                    }
                    case CONSTANT_MethodType, CONSTANT_Module, CONSTANT_Package -> in.skipBytes(2);
                    case CONSTANT_Dynamic -> in.skipBytes(4);
                    case CONSTANT_InvokeDynamic -> {
                        indyBootstrapIdx[i] = in.readUnsignedShort();
                        indyNatIdx[i] = in.readUnsignedShort();
                    }
                    default -> {
                        return null;
                    }
                }
            }

            int accessFlags = in.readUnsignedShort();
            int thisClass = in.readUnsignedShort();
            int superClass = in.readUnsignedShort();

            String internalName = resolveClassName(thisClass, classNameIdx, utf8Pool);
            if (internalName == null || internalName.isEmpty()) {
                return null;
            }
            String binaryClassName = internalName.replace('/', '.');
            String superInternal = resolveClassName(superClass, classNameIdx, utf8Pool);
            String superBinary = superInternal != null ? superInternal.replace('/', '.') : null;

            int interfacesCount = in.readUnsignedShort();
            Set<String> interfaces = new LinkedHashSet<>();
            for (int i = 0; i < interfacesCount; i++) {
                int ifaceIdx = in.readUnsignedShort();
                String ifaceInternal = resolveClassName(ifaceIdx, classNameIdx, utf8Pool);
                if (ifaceInternal != null) {
                    interfaces.add(ifaceInternal.replace('/', '.'));
                }
            }

            boolean usesReflectiveScanner = false;
            Set<String> referencedClasses = new LinkedHashSet<>();
            for (int i = 1; i < cpCount; i++) {
                String clsInternal = resolveClassName(i, classNameIdx, utf8Pool);
                if (clsInternal != null) {
                    if (REFLECTIVE_SCANNER_MARKERS.contains(clsInternal)) {
                        usesReflectiveScanner = true;
                    }
                    String normalizedRef = normalizeConstantPoolClassName(clsInternal);
                    if (normalizedRef != null && !normalizedRef.isEmpty()) {
                        referencedClasses.add(normalizedRef);
                    }
                }
            }

            int fieldsCount = in.readUnsignedShort();
            Set<String> declaredFields = new LinkedHashSet<>();
            Map<String, FieldDescriptorMeta> declaredFieldMeta = new LinkedHashMap<>();
            for (int f = 0; f < fieldsCount; f++) {
                int fFlags = in.readUnsignedShort();
                int fNameIdx = in.readUnsignedShort();
                int fDescIdx = in.readUnsignedShort();
                String fName = safeUtf8(utf8Pool, fNameIdx);
                String fDesc = safeUtf8(utf8Pool, fDescIdx);
                String fSig = "";
                int attrsCount = in.readUnsignedShort();
                for (int a = 0; a < attrsCount; a++) {
                    int attrNameIdx = in.readUnsignedShort();
                    int len = in.readInt();
                    String attrName = safeUtf8(utf8Pool, attrNameIdx);
                    if ("Signature".equals(attrName) && len == 2) {
                        int sigIdx = in.readUnsignedShort();
                        String resolvedSig = safeUtf8(utf8Pool, sigIdx);
                        if (resolvedSig != null) {
                            fSig = resolvedSig;
                        }
                    } else if (len > 0) {
                        in.skipBytes(len);
                    }
                }
                if (fName != null) {
                    declaredFields.add(fName);
                    declaredFieldMeta.put(fName, FieldDescriptorMeta.fromBytecode(fName, fFlags, fDesc, fSig));
                }
            }

            // Read raw methods first so we can parse class-level BootstrapMethods before analyzing Code
            int methodsCount = in.readUnsignedShort();
            List<RawMethodData> rawMethods = new ArrayList<>(methodsCount);
            for (int m = 0; m < methodsCount; m++) {
                int mFlags = in.readUnsignedShort();
                int mNameIdx = in.readUnsignedShort();
                int mDescIdx = in.readUnsignedShort();
                String mName = safeUtf8(utf8Pool, mNameIdx);
                String mDesc = safeUtf8(utf8Pool, mDescIdx);
                ParsedAnnotations methodAnnotations = ParsedAnnotations.empty();
                byte[] codeBytes = null;
                List<ExceptionEntry> exceptionEntries = new ArrayList<>();

                int attrsCount = in.readUnsignedShort();
                for (int a = 0; a < attrsCount; a++) {
                    int attrNameIdx = in.readUnsignedShort();
                    int attrLen = in.readInt();
                    String attrName = safeUtf8(utf8Pool, attrNameIdx);
                    if ("Code".equals(attrName) && attrLen >= 8 && attrLen <= 262_144) {
                        byte[] attrData = new byte[attrLen];
                        in.readFully(attrData);
                        int codeLen = readInt(attrData, 4);
                        if (codeLen > 0 && 8 + codeLen <= attrData.length) {
                            codeBytes = new byte[codeLen];
                            System.arraycopy(attrData, 8, codeBytes, 0, codeLen);
                            int exOffset = 8 + codeLen;
                            if (exOffset + 2 <= attrData.length) {
                                int exCount = readUnsignedShort(attrData, exOffset);
                                int cur = exOffset + 2;
                                for (int e = 0; e < exCount && cur + 8 <= attrData.length; e++) {
                                    int startPc = readUnsignedShort(attrData, cur);
                                    int endPc = readUnsignedShort(attrData, cur + 2);
                                    int handlerPc = readUnsignedShort(attrData, cur + 4);
                                    int catchTypeIdx = readUnsignedShort(attrData, cur + 6);
                                    String catchCls = catchTypeIdx == 0
                                            ? null
                                            : resolveClassName(catchTypeIdx, classNameIdx, utf8Pool);
                                    exceptionEntries.add(new ExceptionEntry(startPc, endPc, handlerPc, catchCls));
                                    cur += 8;
                                }
                            }
                        }
                    } else if (("RuntimeVisibleAnnotations".equals(attrName)
                            || "RuntimeInvisibleAnnotations".equals(attrName)) && attrLen > 0 && attrLen <= 65536) {
                        byte[] annData = new byte[attrLen];
                        in.readFully(annData);
                        methodAnnotations = methodAnnotations.merge(extractAnnotationsFromAttribute(annData, utf8Pool, true));
                    } else if (attrLen > 0) {
                        in.skipBytes(attrLen);
                    }
                }
                if (mName != null) {
                    rawMethods.add(new RawMethodData(
                            mName,
                            mDesc != null ? mDesc : "()V",
                            mFlags,
                            methodAnnotations,
                            codeBytes,
                            exceptionEntries
                    ));
                }
            }

            // Parse class-level BootstrapMethods and class-level annotations
            Map<Integer, String> bootstrapTargetMethod = new LinkedHashMap<>();
            Map<Integer, List<String>> bootstrapTargetMethods = new LinkedHashMap<>();
            Map<Integer, String> bootstrapFactoryOwner = new LinkedHashMap<>();
            ParsedAnnotations classAnnotations = ParsedAnnotations.empty();
            int classAttrsCount = in.readUnsignedShort();
            for (int a = 0; a < classAttrsCount; a++) {
                int attrNameIdx = in.readUnsignedShort();
                int attrLen = in.readInt();
                String attrName = safeUtf8(utf8Pool, attrNameIdx);
                if ("BootstrapMethods".equals(attrName) && attrLen >= 2 && attrLen <= 262_144) {
                    byte[] bsmBytes = new byte[attrLen];
                    in.readFully(bsmBytes);
                    int numBsm = readUnsignedShort(bsmBytes, 0);
                    int cur = 2;
                    for (int b = 0; b < numBsm && cur + 4 <= bsmBytes.length; b++) {
                        int bsmRef = readUnsignedShort(bsmBytes, cur);
                        int numArgs = readUnsignedShort(bsmBytes, cur + 2);
                        cur += 4;
                        if (bsmRef > 0 && bsmRef < cpCount && methodHandleRefIdx[bsmRef] > 0) {
                            int factoryRef = methodHandleRefIdx[bsmRef];
                            String fOwner = resolveClassName(methodClassIdx[factoryRef], classNameIdx, utf8Pool);
                            if (fOwner != null) {
                                bootstrapFactoryOwner.put(b, fOwner);
                            }
                        }
                        for (int argIdx = 0; argIdx < numArgs && cur + 2 <= bsmBytes.length; argIdx++) {
                            int argCpIdx = readUnsignedShort(bsmBytes, cur);
                            cur += 2;
                            if (argCpIdx > 0 && argCpIdx < cpCount && methodHandleRefIdx[argCpIdx] > 0) {
                                int targetRef = methodHandleRefIdx[argCpIdx];
                                String owner = resolveClassName(methodClassIdx[targetRef], classNameIdx, utf8Pool);
                                String name = resolveMethodName(targetRef, methodNatIdx, natNameIdx, utf8Pool);
                                if (owner != null && name != null) {
                                    String targetSym = owner.replace('/', '.') + "#" + name;
                                    bootstrapTargetMethod.putIfAbsent(b, targetSym);
                                    List<String> allHandles = bootstrapTargetMethods.computeIfAbsent(
                                            b,
                                            k -> new ArrayList<>()
                                    );
                                    if (!allHandles.contains(targetSym)) {
                                        allHandles.add(targetSym);
                                    }
                                }
                            }
                        }
                        // Unused variable warning prevention
                        if (bsmRef < 0) {
                            break;
                        }
                    }
                } else if (("RuntimeVisibleAnnotations".equals(attrName)
                        || "RuntimeInvisibleAnnotations".equals(attrName)) && attrLen > 0 && attrLen <= 65536) {
                    byte[] annData = new byte[attrLen];
                    in.readFully(annData);
                    classAnnotations = classAnnotations.merge(extractAnnotationsFromAttribute(annData, utf8Pool, false));
                } else if (attrLen > 0) {
                    in.skipBytes(attrLen);
                }
            }

            boolean classManagesCloseableFields = interfaces.stream().anyMatch(i -> i.endsWith("Closeable"))
                    || rawMethods.stream().anyMatch(m -> "close".equals(m.name())
                    || "shutdown".equals(m.name())
                    || "closeQuietly".equals(m.name()));
            Map<String, MethodNode> parsedMethods = new LinkedHashMap<>();
            List<IndyFunctionalSite> classIndySites = new ArrayList<>();
            for (RawMethodData rm : rawMethods) {
                MethodNode node = analyzeMethodBytecode(
                        binaryClassName,
                        rm,
                        classAnnotations,
                        classManagesCloseableFields,
                        utf8Pool,
                        numericPool,
                        classNameIdx,
                        stringUtf8Idx,
                        fieldClassIdx,
                        fieldNatIdx,
                        methodClassIdx,
                        methodNatIdx,
                        natNameIdx,
                        natDescIdx,
                        indyBootstrapIdx,
                        indyNatIdx,
                        bootstrapTargetMethod,
                        bootstrapTargetMethods,
                        bootstrapFactoryOwner,
                        classIndySites
                );
                String key = parsedMethods.containsKey(rm.name())
                        ? (rm.name() + rm.descriptor())
                        : rm.name();
                parsedMethods.put(key, node);
            }

            return new ParsedClass(
                    binaryClassName,
                    superBinary,
                    accessFlags,
                    interfaces,
                    declaredFields,
                    declaredFieldMeta,
                    parsedMethods,
                    usesReflectiveScanner,
                    classAnnotations,
                    referencedClasses,
                    List.copyOf(classIndySites)
            );
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "[LeafConfig] Skipping unreadable or non-standard .class payload", e);
            return null;
        }
    }

    private record RawMethodData(
            String name,
            String descriptor,
            int accessFlags,
            ParsedAnnotations annotations,
            byte[] code,
            List<ExceptionEntry> exceptionTable
    ) {
        boolean isEventHandler() {
            return annotations != null && annotations.isEventHandler();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RawMethodData that)) return false;
            return accessFlags == that.accessFlags
                    && Objects.equals(name, that.name)
                    && Objects.equals(descriptor, that.descriptor)
                    && Objects.equals(annotations, that.annotations)
                    && Arrays.equals(code, that.code)
                    && Objects.equals(exceptionTable, that.exceptionTable);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(name, descriptor, accessFlags, annotations, exceptionTable);
            return 31 * result + Arrays.hashCode(code);
        }

        @Override
        public String toString() {
            return "RawMethodData[name=" + name
                    + ", descriptor=" + descriptor
                    + ", accessFlags=" + accessFlags
                    + ", annotations=" + annotations
                    + ", code=" + Arrays.toString(code)
                    + ", exceptionTable=" + exceptionTable + "]";
        }
    }

    /**
     * Parses a JVMS 4.7.16 {@code RuntimeVisibleAnnotations} / {@code RuntimeInvisibleAnnotations}
     * attribute payload to extract {@code @EventHandler}/{@code @SubscribeEvent} markers and
     * annotation-driven command framework metadata ({@code ACF}, {@code Incendo Cloud},
     * {@code LiteCommands}, {@code Lamp}, {@code Triumph}).
     */
    private static ParsedAnnotations extractAnnotationsFromAttribute(
            byte[] annData,
            String[] utf8Pool,
            boolean isMethodTarget
    ) {
        if (annData == null || annData.length < 2) {
            return ParsedAnnotations.empty();
        }
        boolean isEventHandler = false;
        boolean isCommandEntry = false;
        Set<String> commandRoots = new LinkedHashSet<>();
        Set<String> subcommandLiterals = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        StringBuilder descBuilder = new StringBuilder();

        int numAnn = readUnsignedShort(annData, 0);
        int pos = 2;
        for (int a = 0; a < numAnn && pos + 4 <= annData.length; a++) {
            int typeIdx = readUnsignedShort(annData, pos);
            int numPairs = readUnsignedShort(annData, pos + 2);
            pos += 4;
            String typeDesc = safeUtf8(utf8Pool, typeIdx);
            String simpleAnn = extractSimpleAnnotationName(typeDesc);

            if ("EventHandler".equals(simpleAnn)
                    || "SubscribeEvent".equals(simpleAnn)
                    || "Subscribe".equals(simpleAnn)) {
                isEventHandler = true;
            }
            if (isMethodTarget && ("CommandAlias".equals(simpleAnn)
                    || "Subcommand".equals(simpleAnn)
                    || "SubCommand".equals(simpleAnn)
                    || "CommandMethod".equals(simpleAnn)
                    || "Command".equals(simpleAnn)
                    || "Execute".equals(simpleAnn)
                    || "Default".equals(simpleAnn)
                    || "CatchUnknown".equals(simpleAnn)
                    || "HelpCommand".equals(simpleAnn))) {
                isCommandEntry = true;
            }

            for (int p = 0; p < numPairs && pos + 3 <= annData.length; p++) {
                int elemNameIdx = readUnsignedShort(annData, pos);
                String elemName = safeUtf8(utf8Pool, elemNameIdx);
                pos += 2;
                List<String> values = new ArrayList<>();
                pos = parseAnnotationElementValue(annData, pos, utf8Pool, values);
                if (values.isEmpty()) {
                    continue;
                }
                if ("CommandAlias".equals(simpleAnn) || "RootCommand".equals(simpleAnn)) {
                    for (String val : values) {
                        parsePipeAndSpaceCommandTokens(val, commandRoots, subcommandLiterals, true);
                    }
                } else if ("Command".equals(simpleAnn) || "CommandMethod".equals(simpleAnn)) {
                    boolean isSubField = "subcommand".equalsIgnoreCase(elemName);
                    for (String val : values) {
                        parsePipeAndSpaceCommandTokens(val, commandRoots, subcommandLiterals, !isSubField);
                    }
                } else if ("Subcommand".equals(simpleAnn) || "SubCommand".equals(simpleAnn) || "Execute".equals(simpleAnn)) {
                    for (String val : values) {
                        parsePipeAndSpaceCommandTokens(val, commandRoots, subcommandLiterals, false);
                    }
                } else if ("CommandPermission".equals(simpleAnn) || "Permission".equals(simpleAnn)) {
                    for (String val : values) {
                        for (String part : PIPE_COMMA_WS_RE.split(val)) {
                            String trimmed = part.trim();
                            if (!trimmed.isEmpty()) {
                                permissions.add(trimmed);
                            }
                        }
                    }
                } else if ("CommandDescription".equals(simpleAnn) || "Description".equals(simpleAnn)) {
                    for (String val : values) {
                        if (!val.isBlank()) {
                            if (descBuilder.length() > 0) {
                                descBuilder.append(' ');
                            }
                            descBuilder.append(val.trim());
                        }
                    }
                }
            }
        }

        // Fallback constant-pool scan if bytecode used non-standard nested event annotations
        if (!isEventHandler) {
            for (int i = 0; i + 1 < annData.length; i++) {
                int idx = readUnsignedShort(annData, i);
                String s = safeUtf8(utf8Pool, idx);
                if (s != null && (s.contains("EventHandler") || s.contains("SubscribeEvent"))) {
                    isEventHandler = true;
                    break;
                }
            }
        }

        return new ParsedAnnotations(
                isEventHandler,
                isCommandEntry,
                new ArrayList<>(commandRoots),
                new ArrayList<>(subcommandLiterals),
                new ArrayList<>(permissions),
                descBuilder.toString()
        );
    }

    private static String extractSimpleAnnotationName(String typeDesc) {
        if (typeDesc == null || typeDesc.isEmpty()) {
            return "";
        }
        String s = typeDesc;
        if (s.startsWith("L") && s.endsWith(";")) {
            s = s.substring(1, s.length() - 1);
        }
        int slash = s.lastIndexOf('/');
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        int dollar = s.lastIndexOf('$');
        if (dollar >= 0) {
            s = s.substring(dollar + 1);
        }
        return s;
    }

    private static void parsePipeAndSpaceCommandTokens(
            String raw,
            Set<String> outRoots,
            Set<String> outSubcommands,
            boolean firstTokenIsRoot
    ) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String pipeBranch : raw.split("\\|")) {
            String trimmed = pipeBranch.trim();
            if (trimmed.startsWith("/")) {
                trimmed = trimmed.substring(1).trim();
            }
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] words = WHITESPACE_RE.split(trimmed);
            for (int i = 0; i < words.length; i++) {
                String tok = words[i].trim();
                if (tok.isEmpty() || tok.startsWith("<") || tok.startsWith("[") || tok.startsWith("-")) {
                    continue;
                }
                if (i == 0 && firstTokenIsRoot) {
                    outRoots.add(tok.toLowerCase(Locale.ROOT));
                } else if (isCandidateSubcommandLiteral(tok)) {
                    outSubcommands.add(tok.toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    private static int parseAnnotationElementValue(
            byte[] data,
            int pos,
            String[] utf8Pool,
            List<String> outStrings
    ) {
        if (pos >= data.length) {
            return data.length;
        }
        int tag = data[pos++] & 0xFF;
        return switch (tag) {
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 'c' -> Math.min(data.length, pos + 2);
            case 's' -> {
                if (pos + 2 > data.length) {
                    yield data.length;
                }
                String s = safeUtf8(utf8Pool, readUnsignedShort(data, pos));
                if (s != null && !s.isBlank()) {
                    outStrings.add(s.trim());
                }
                yield pos + 2;
            }
            case 'e' -> Math.min(data.length, pos + 4);
            case '@' -> {
                if (pos + 4 > data.length) {
                    yield data.length;
                }
                int nestedPairs = readUnsignedShort(data, pos + 2);
                int cur = pos + 4;
                for (int i = 0; i < nestedPairs && cur + 3 <= data.length; i++) {
                    cur += 2;
                    cur = parseAnnotationElementValue(data, cur, utf8Pool, outStrings);
                }
                yield cur;
            }
            case '[' -> {
                if (pos + 2 > data.length) {
                    yield data.length;
                }
                int numVals = readUnsignedShort(data, pos);
                int cur = pos + 2;
                for (int i = 0; i < numVals && cur < data.length; i++) {
                    cur = parseAnnotationElementValue(data, cur, utf8Pool, outStrings);
                }
                yield cur;
            }
            default -> data.length;
        };
    }

    private static MethodNode analyzeMethodBytecode(
            String binaryClassName,
            RawMethodData rm,
            ParsedAnnotations classAnnotations,
            boolean classManagesCloseableFields,
            String[] utf8Pool,
            String[] numericPool,
            int[] classNameIdx,
            int[] stringUtf8Idx,
            int[] fieldClassIdx,
            int[] fieldNatIdx,
            int[] methodClassIdx,
            int[] methodNatIdx,
            int[] natNameIdx,
            int[] natDescIdx,
            int[] indyBootstrapIdx,
            int[] indyNatIdx,
            Map<Integer, String> bootstrapTargetMethod,
            Map<Integer, List<String>> bootstrapTargetMethods,
            Map<Integer, String> bootstrapFactoryOwner,
            List<IndyFunctionalSite> classIndySitesOut
    ) {
        ParsedAnnotations methodAnn = rm.annotations() != null ? rm.annotations() : ParsedAnnotations.empty();
        ParsedAnnotations clsAnn = classAnnotations != null ? classAnnotations : ParsedAnnotations.empty();
        String eventTypeSimpleName = rm.isEventHandler() ? extractFirstParamSimpleType(rm.descriptor()) : "";
        Set<String> mergedCommandRoots = new LinkedHashSet<>(methodAnn.commandRoots());
        if (mergedCommandRoots.isEmpty()) {
            mergedCommandRoots.addAll(clsAnn.commandRoots());
        }
        Set<String> subcommandLiterals = new LinkedHashSet<>(methodAnn.subcommandLiterals());
        Set<String> argumentParsers = new LinkedHashSet<>();
        if (methodAnn.isCommandEntry()) {
            argumentParsers.addAll(extractDescriptorArgumentParsers(rm.descriptor()));
        }
        Set<String> inlinePermissions = new LinkedHashSet<>(clsAnn.permissions());
        inlinePermissions.addAll(methodAnn.permissions());
        String cmdDesc = !methodAnn.description().isBlank() ? methodAnn.description() : clsAnn.description();

        byte[] code = rm.code();
        if (code == null || code.length == 0) {
            Set<String> emptyLocks = new LinkedHashSet<>();
            Set<String> emptyMacros = new LinkedHashSet<>();
            if ((rm.accessFlags() & ACC_SYNCHRONIZED) != 0) {
                emptyLocks.add((rm.accessFlags() & ACC_STATIC) != 0 ? "class" : "this");
                emptyMacros.add("@synchronized_lock");
            }
            return new MethodNode(
                    binaryClassName,
                    rm.name(),
                    rm.descriptor(),
                    rm.accessFlags(),
                    rm.isEventHandler(),
                    eventTypeSimpleName,
                    methodAnn.isCommandEntry(),
                    new ArrayList<>(mergedCommandRoots),
                    new ArrayList<>(subcommandLiterals),
                    new ArrayList<>(argumentParsers),
                    new ArrayList<>(inlinePermissions),
                    cmdDesc,
                    List.of(),
                    List.of(),
                    List.of(),
                    Set.of(),
                    Map.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Map.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    List.of(),
                    Set.of(),
                    Set.of(),
                    List.of(),
                    false,
                    false,
                    false,
                    emptyLocks,
                    Set.of(),
                    List.of(),
                    true,
                    "",
                    emptyMacros,
                    false,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    ""
            );
        }

        List<String> calledMethods = new ArrayList<>();
        List<String> spawnedLambdas = new ArrayList<>();
        List<String> orphanedSuppliers = new ArrayList<>();
        Set<String> instantiatedClasses = new LinkedHashSet<>();
        Map<String, String> conditionalInstantiations = new LinkedHashMap<>();
        Map<String, String> conditionalMethodCalls = new LinkedHashMap<>();
        Set<String> readFields = new LinkedHashSet<>();
        Set<String> writtenFields = new LinkedHashSet<>();
        Set<String> producedQueueFields = new LinkedHashSet<>();
        Set<String> collectionAddedFields = new LinkedHashSet<>();
        Set<String> collectionRemovedFields = new LinkedHashSet<>();
        String returnedField = "";
        Set<String> dispatchedEventTypes = new LinkedHashSet<>();
        Map<String, String> configKeyToAssignedField = new LinkedHashMap<>();
        Map<String, String> configKeyScalingHint = new LinkedHashMap<>();
        Set<String> referencedConfigKeys = new LinkedHashSet<>();
        Set<String> stringLiterals = new LinkedHashSet<>();
        List<String> guards = new ArrayList<>();
        for (String annPerm : inlinePermissions) {
            guards.add("hasPermission(" + annPerm + ")");
        }
        Set<String> directEffectSinks = new LinkedHashSet<>();
        Set<String> syncMainThreadCalls = new LinkedHashSet<>();
        boolean callsCurrentTimeMillis = false;
        boolean multipliesBy1000Or20 = false;
        boolean usesDynamicConfigIteration = false;
        Set<String> acquiredLocks = new LinkedHashSet<>();
        Set<String> blockingIoCalls = new LinkedHashSet<>();
        List<String> allocatedResources = new ArrayList<>();
        Set<String> semanticMacros = new LinkedHashSet<>();
        boolean callsAccessOrderLinkedHashMap = false;
        int firstAllocPc = -1;
        List<Integer> closePcs = new ArrayList<>();
        List<Integer> earlyExitPcs = new ArrayList<>();
        boolean trackedByMemoryTracker = false;
        boolean storedInManagedClassField = false;
        boolean sawFutureIsDoneGuard = false;
        if ((rm.accessFlags() & ACC_SYNCHRONIZED) != 0) {
            acquiredLocks.add((rm.accessFlags() & ACC_STATIC) != 0 ? "class" : "this");
            semanticMacros.add("@synchronized_lock");
        }

        // Pass 1: Record conditional jump spans [branchPc -> targetPc] and early-return guard PCs
        // (`if (!flag) return;`) to detect generalized config/branch-gated listener registration.
        List<int[]> forwardConditionalSpans = new ArrayList<>();
        int firstEarlyReturnGuardPc = Integer.MAX_VALUE;
        int scanPc = 0;
        while (scanPc < code.length) {
            int op = code[scanPc] & 0xFF;
            int len = instructionLength(code, scanPc);
            if (len <= 0) {
                break;
            }
            if (((op >= 0x99 && op <= 0xA6) || op == 0xC6 || op == 0xC7) && scanPc + 2 < code.length) {
                short rel = (short) (((code[scanPc + 1] & 0xFF) << 8) | (code[scanPc + 2] & 0xFF));
                int targetPc = scanPc + rel;
                if (targetPc > scanPc) {
                    forwardConditionalSpans.add(new int[]{scanPc, targetPc});
                    // Check if the skipped block `[scanPc + len .. targetPc)` ends in an early `return` (0xB1)
                    int preTargetPc = targetPc - 1;
                    if (preTargetPc >= scanPc + len && preTargetPc < code.length && (code[preTargetPc] & 0xFF) == 0xB1) {
                        firstEarlyReturnGuardPc = Math.min(firstEarlyReturnGuardPc, preTargetPc);
                    }
                }
            }
            scanPc += len;
        }

        String recentConfigKey = null;
        String recentReadField = null;
        String recentReadFullField = null;
        String recentReadFieldDesc = null;
        String recentStringLiteral = null;
        String recentInstantiatedSimpleClass = null;
        String recentIndyLambdaTarget = null;
        boolean sawAload0SinceFieldRead = false;
        boolean recentLoadWasAload0 = false;
        int pc = 0;

        while (pc < code.length) {
            int op = code[pc] & 0xFF;
            int insLen = instructionLength(code, pc);
            if (insLen <= 0) {
                break;
            }
            if (op == 0x2A) { // ALOAD_0
                sawAload0SinceFieldRead = true;
                recentLoadWasAload0 = true;
            } else if (op == 0x19 || (op >= 0x2B && op <= 0x2D)) { // ALOAD local variable (slot >= 1)
                recentLoadWasAload0 = false;
                recentReadField = null;
            }
            if (op == 0xC2) { // MONITORENTER
                semanticMacros.add("@synchronized_lock");
                if (recentReadField != null && !sawAload0SinceFieldRead) {
                    acquiredLocks.add(recentReadField);
                } else if (recentLoadWasAload0) {
                    acquiredLocks.add("this");
                }
            }
            if ((op >= 0xAC && op <= 0xB1) || op == 0xBF) { // RETURN..ARETURN or ATHROW
                if (firstAllocPc >= 0 && pc > firstAllocPc) {
                    earlyExitPcs.add(pc);
                }
            }

            // Detect numeric constants 1000 or 20 used for unit scaling
            String pushedConst = extractPushedConstant(code, pc, numericPool, stringUtf8Idx, utf8Pool);
            if ("1000".equals(pushedConst) || "1000.0".equals(pushedConst)
                    || "20".equals(pushedConst) || "20.0".equals(pushedConst)) {
                multipliesBy1000Or20 = true;
            }

            // String literals, Class literals (ldc Class.class) & config keys
            String strLiteral = null;
            if (op == 0x12 && pc + 1 < code.length) { // ldc
                int idx = code[pc + 1] & 0xFF;
                if (idx > 0 && idx < stringUtf8Idx.length && stringUtf8Idx[idx] > 0) {
                    strLiteral = safeUtf8(utf8Pool, stringUtf8Idx[idx]);
                } else if (idx > 0 && idx < classNameIdx.length && classNameIdx[idx] > 0) {
                    String ldcCls = resolveClassName(idx, classNameIdx, utf8Pool);
                    if (ldcCls != null && !ldcCls.startsWith("[")) {
                        instantiatedClasses.add(ldcCls.replace('/', '.'));
                    }
                }
            } else if (op == 0x13 && pc + 2 < code.length) { // ldc_w
                int idx = readUnsignedShort(code, pc + 1);
                if (idx > 0 && idx < stringUtf8Idx.length && stringUtf8Idx[idx] > 0) {
                    strLiteral = safeUtf8(utf8Pool, stringUtf8Idx[idx]);
                } else if (idx > 0 && idx < classNameIdx.length && classNameIdx[idx] > 0) {
                    String ldcCls = resolveClassName(idx, classNameIdx, utf8Pool);
                    if (ldcCls != null && !ldcCls.startsWith("[")) {
                        instantiatedClasses.add(ldcCls.replace('/', '.'));
                    }
                }
            } else if (op == 0xB2 && pc + 2 < code.length) { // getstatic *Keys/*Params enum
                int fCpIdx = readUnsignedShort(code, pc + 1);
                String enumKey = resolveConfigEnumKey(fCpIdx, fieldClassIdx, fieldNatIdx, classNameIdx, natNameIdx, utf8Pool);
                if (enumKey != null) {
                    strLiteral = enumKey;
                }
            }

            if (strLiteral != null && !strLiteral.isBlank()) {
                stringLiterals.add(strLiteral);
                recentStringLiteral = strLiteral;
                if (isCandidateSubcommandLiteral(strLiteral)
                        && lookaheadStringComparison(
                        code,
                        pc + insLen,
                        methodClassIdx,
                        methodNatIdx,
                        classNameIdx,
                        natNameIdx,
                        utf8Pool
                )) {
                    subcommandLiterals.add(strLiteral.toLowerCase(Locale.ROOT));
                }
                if (BytecodeConfigScanner.isPlausibleConfigKeyLiteral(strLiteral)) {
                    LookaheadConfigRead look = lookaheadConfigGetter(
                            code,
                            pc + insLen,
                            numericPool,
                            stringUtf8Idx,
                            fieldClassIdx,
                            fieldNatIdx,
                            methodClassIdx,
                            methodNatIdx,
                            classNameIdx,
                            natNameIdx,
                            utf8Pool
                    );
                    if (look.invokedGetter != null) {
                        referencedConfigKeys.add(strLiteral);
                        recentConfigKey = strLiteral;
                        if (look.scalingHint != null) {
                            configKeyScalingHint.put(strLiteral, look.scalingHint);
                            multipliesBy1000Or20 = true;
                        }
                        if (look.assignedField != null) {
                            configKeyToAssignedField.put(strLiteral, binaryClassName + "#" + look.assignedField);
                        }
                    }
                }
            }

            // Object instantiation: NEW (0xBB)
            if (op == 0xBB && pc + 2 < code.length) {
                int clsIdx = readUnsignedShort(code, pc + 1);
                String instInternal = resolveClassName(clsIdx, classNameIdx, utf8Pool);
                if (instInternal != null) {
                    String instBinary = instInternal.replace('/', '.');
                    instantiatedClasses.add(instBinary);
                    recentInstantiatedSimpleClass = simpleClassName(instBinary);
                    if (isInsideConditionalSpan(pc, forwardConditionalSpans) || pc > firstEarlyReturnGuardPc) {
                        String gateLabel = resolveGateLabel(recentConfigKey, referencedConfigKeys, recentReadField);
                        conditionalInstantiations.put(instBinary, gateLabel);
                    }
                }
            }

            // Field accesses: GETSTATIC (0xB2), PUTSTATIC (0xB3), GETFIELD (0xB4), PUTFIELD (0xB5)
            if ((op >= 0xB2 && op <= 0xB5) && pc + 2 < code.length) {
                int fCpIdx = readUnsignedShort(code, pc + 1);
                String fOwner = (fCpIdx > 0 && fCpIdx < fieldClassIdx.length)
                        ? resolveClassName(fieldClassIdx[fCpIdx], classNameIdx, utf8Pool)
                        : null;
                String fName = resolveFieldName(fCpIdx, fieldNatIdx, natNameIdx, utf8Pool);
                String fDesc = resolveMethodDesc(fCpIdx, fieldNatIdx, natDescIdx, utf8Pool);
                if (fOwner != null && fName != null && !fName.startsWith("$")) {
                    String fullField = fOwner.replace('/', '.') + "#" + fName;
                    if (op == 0xB2 || op == 0xB4) {
                        if (!writtenFields.contains(fullField)) {
                            readFields.add(fullField);
                        }
                        recentReadField = fName;
                        boolean isCollectionOrQueueField = fDesc != null && (
                                fDesc.contains("Collection") || fDesc.contains("List")
                                || fDesc.contains("Set") || fDesc.contains("Map")
                                || fDesc.contains("Queue") || fDesc.contains("Deque")
                                || fDesc.contains("RingBuffer")
                        );
                        boolean isArrayField = fDesc != null && fDesc.startsWith("[");
                        recentReadFullField = isCollectionOrQueueField ? fullField : null;
                        recentReadFieldDesc = isCollectionOrQueueField ? fDesc : null;
                        sawAload0SinceFieldRead = false;
                        int nextFieldPc = pc + insLen;
                        if ((isCollectionOrQueueField || isArrayField)
                                && nextFieldPc < code.length
                                && (code[nextFieldPc] & 0xFF) == 0xB0) {
                            returnedField = fullField;
                        }
                    } else {
                        writtenFields.add(fullField);
                        if (classManagesCloseableFields && fDesc != null
                                && (fDesc.contains("Channel") || fDesc.contains("Stream")
                                || fDesc.contains("Closeable") || fDesc.contains("RandomAccessFile")
                                || fDesc.contains("Ticket") || fDesc.contains("Socket")
                                || fDesc.contains("Reader") || fDesc.contains("Writer"))) {
                            storedInManagedClassField = true;
                        }
                    }
                }
            }
            // Method invocations: INVOKEVIRTUAL (0xB6), INVOKESPECIAL (0xB7), INVOKESTATIC (0xB8), INVOKEINTERFACE (0xB9)
            if ((op >= 0xB6 && op <= 0xB9) && pc + 2 < code.length) {
                int mCpIdx = readUnsignedShort(code, pc + 1);
                String mOwnerInternal = (mCpIdx > 0 && mCpIdx < methodClassIdx.length)
                        ? resolveClassName(methodClassIdx[mCpIdx], classNameIdx, utf8Pool)
                        : null;
                String mName = resolveMethodName(mCpIdx, methodNatIdx, natNameIdx, utf8Pool);
                String mDesc = resolveMethodDesc(mCpIdx, methodNatIdx, natDescIdx, utf8Pool);
                if (mOwnerInternal != null && mName != null) {
                    String mOwnerBinary = mOwnerInternal.replace('/', '.');
                    String callRef = mOwnerBinary + "#" + mName;
                    if (!"<init>".equals(mName) && !calledMethods.contains(callRef)) {
                        calledMethods.add(callRef);
                    }
                    if (!"<init>".equals(mName)
                            && (isInsideConditionalSpan(pc, forwardConditionalSpans) || pc > firstEarlyReturnGuardPc)) {
                        String gateLabel = resolveGateLabel(recentConfigKey, referencedConfigKeys, recentReadField);
                        conditionalMethodCalls.putIfAbsent(callRef, gateLabel);
                    }
                    if ("<init>".equals(mName)) {
                        instantiatedClasses.add(mOwnerBinary);
                        recentInstantiatedSimpleClass = simpleClassName(mOwnerBinary);
                        if (isInsideConditionalSpan(pc, forwardConditionalSpans) || pc > firstEarlyReturnGuardPc) {
                            String gateLabel = resolveGateLabel(recentConfigKey, referencedConfigKeys, recentReadField);
                            conditionalInstantiations.putIfAbsent(mOwnerBinary, gateLabel);
                        }
                    }

                    // Track custom event dispatch (`callEvent`, `EventBus#post`, `fireEvent`)
                    if (EVENT_DISPATCH_METHODS.contains(mName)) {
                        directEffectSinks.add("EVENT_DISPATCH");
                        if (recentInstantiatedSimpleClass != null && !recentInstantiatedSimpleClass.isEmpty()) {
                            dispatchedEventTypes.add(recentInstantiatedSimpleClass);
                        }
                    }

                    // Track collection/queue producer writes (`getfield queue` + `offer`/`add`/`put`/`push`/`remove`/`clear`, or 1-hop getter `getX().put(...)`)
                    String effectiveCollectionTarget = recentReadFullField;
                    if (effectiveCollectionTarget == null && (QUEUE_PRODUCER_METHODS.contains(mName)
                            || "remove".equals(mName) || "clear".equals(mName)
                            || "compute".equals(mName) || "merge".equals(mName)
                            || "replace".equals(mName) || "putAll".equals(mName)
                            || "addAll".equals(mName) || "removeAll".equals(mName)
                            || "removeIf".equals(mName))
                            && !calledMethods.isEmpty()) {
                        for (int cIdx = calledMethods.size() - 1; cIdx >= Math.max(0, calledMethods.size() - 4); cIdx--) {
                            String prevCall = calledMethods.get(cIdx);
                            if (prevCall.equals(callRef)) {
                                continue;
                            }
                            if (prevCall.startsWith("java.lang.") || prevCall.startsWith("java.util.")) {
                                continue;
                            }
                            effectiveCollectionTarget = "GETTER:" + prevCall;
                            break;
                        }
                    }
                    if (effectiveCollectionTarget != null && (QUEUE_PRODUCER_METHODS.contains(mName)
                            || "remove".equals(mName) || "clear".equals(mName)
                            || "compute".equals(mName) || "merge".equals(mName)
                            || "replace".equals(mName) || "putAll".equals(mName)
                            || "addAll".equals(mName) || "removeAll".equals(mName)
                            || "removeIf".equals(mName) || "keySet".equals(mName)
                            || "values".equals(mName) || "entrySet".equals(mName)
                            || "iterator".equals(mName))) {
                        boolean isQueueLikeOwner = mOwnerInternal.contains("Queue")
                                || mOwnerInternal.contains("Deque")
                                || mOwnerInternal.contains("RingBuffer")
                                || (recentReadFieldDesc != null && (recentReadFieldDesc.contains("Queue")
                                || recentReadFieldDesc.contains("Deque")
                                || recentReadFieldDesc.contains("RingBuffer")));
                        if (recentReadFullField != null) {
                            if (isQueueLikeOwner && QUEUE_PRODUCER_METHODS.contains(mName)) {
                                producedQueueFields.add(recentReadFullField);
                                directEffectSinks.add("QUEUE_HANDOFF");
                            } else if (mOwnerInternal.startsWith("java/util/") || mOwnerInternal.contains("Map") || mOwnerInternal.contains("Collection") || mOwnerInternal.contains("List") || mOwnerInternal.contains("Set")) {
                                writtenFields.add(recentReadFullField);
                            }
                        }
                        if (QUEUE_PRODUCER_METHODS.contains(mName)
                                || "putAll".equals(mName)
                                || "addAll".equals(mName)
                                || "compute".equals(mName)
                                || "merge".equals(mName)) {
                            collectionAddedFields.add(effectiveCollectionTarget);
                        } else if ("remove".equals(mName)
                                || "clear".equals(mName)
                                || "removeAll".equals(mName)
                                || "removeIf".equals(mName)
                                || "poll".equals(mName)) {
                            collectionRemovedFields.add(effectiveCollectionTarget);
                        }
                    } else if (recentReadFullField != null
                            && ("remove".equals(mName) || "removeIf".equals(mName) || "clear".equals(mName))
                            && (mOwnerInternal.contains("Iterator") || mOwnerInternal.contains("Set") || mOwnerInternal.contains("Collection"))) {
                        collectionRemovedFields.add(recentReadFullField);
                    }
                    if (("isOnline".equals(mName) || "hasDisconnected".equals(mName) || "isRemoved".equals(mName))
                            && (mOwnerInternal.contains("Player") || mOwnerInternal.contains("Entity"))) {
                        if (!guards.contains("Player#isOnline")) {
                            guards.add("Player#isOnline");
                        }
                    }

                    // Track white-box argument parser calls
                    String inferredParser = classifyArgumentParserCall(mOwnerInternal, mName);
                    if (inferredParser != null) {
                        argumentParsers.add(inferredParser);
                    }

                    if ("java/lang/System".equals(mOwnerInternal) && "currentTimeMillis".equals(mName)) {
                        callsCurrentTimeMillis = true;
                    }
                    if (mOwnerInternal.contains("TimeUnit") || mOwnerInternal.contains("Duration")
                            || "parseSecondsDurationMillis".equals(mName) || "parseDurationSetting".equals(mName)) {
                        multipliesBy1000Or20 = true;
                    }
                    if (DYNAMIC_CONFIG_ITERATION_METHODS.contains(mName)) {
                        usesDynamicConfigIteration = true;
                    }
                    if ("hasPermission".equals(mName) || "checkPermission".equals(mName)) {
                        if (recentStringLiteral != null && isPlausiblePermissionLiteral(recentStringLiteral)) {
                            inlinePermissions.add(recentStringLiteral);
                            String permGuard = "hasPermission(" + recentStringLiteral + ")";
                            if (!guards.contains(permGuard)) {
                                guards.add(permGuard);
                            }
                        } else if (!guards.contains("hasPermission")) {
                            guards.add("hasPermission");
                        }
                    }

                    classifyEffectSinkAndThreadSafety(
                            mOwnerInternal,
                            mName,
                            mDesc,
                            directEffectSinks,
                            syncMainThreadCalls
                    );

                    // Track explicit Lock acquisitions, Folia region guards, scheduler dispatches,
                    // blocking I/O sinks (CAUSAL-MAIN-IO / S-005), and resource/ticket lifecycles (CAUSAL-LEAK / S-002)
                    if (("lock".equals(mName) || "tryLock".equals(mName) || "readLock".equals(mName) || "writeLock".equals(mName))
                            && (mOwnerInternal.contains("concurrent/locks/") || mOwnerInternal.endsWith("Lock"))) {
                        acquiredLocks.add(recentReadField != null ? recentReadField : "lock");
                        semanticMacros.add("@synchronized_lock");
                    }
                      if ("isOwnedByCurrentRegion".equals(mName)
                              || mOwnerInternal.contains("RegionScheduler")
                              || mOwnerInternal.contains("GlobalRegionScheduler")
                              || mOwnerInternal.contains("EntityScheduler")) {
                          semanticMacros.add("@folia_region_guard");
                          if ("isOwnedByCurrentRegion".equals(mName) && !guards.contains("Bukkit#isOwnedByCurrentRegion")) {
                              guards.add("Bukkit#isOwnedByCurrentRegion");
                          }
                      }
                      if (recentIndyLambdaTarget != null) {
                          if (mOwnerInternal.contains("GlobalRegionScheduler")
                                  && ("run".equals(mName) || "runDelayed".equals(mName)
                                  || "runAtFixedRate".equals(mName) || "execute".equals(mName))) {
                              semanticMacros.add("@folia_global_region_lambda:" + recentIndyLambdaTarget);
                          } else if (((mOwnerInternal.contains("RegionScheduler")
                                  && !mOwnerInternal.contains("GlobalRegionScheduler"))
                                  || mOwnerInternal.contains("EntityScheduler"))
                                  && ("run".equals(mName) || "runDelayed".equals(mName)
                                  || "runAtFixedRate".equals(mName) || "execute".equals(mName))) {
                              semanticMacros.add("@folia_owned_region_lambda:" + recentIndyLambdaTarget);
                          }
                      }
                      if ((mOwnerInternal.endsWith("BukkitScheduler")
                              || mOwnerInternal.toLowerCase(Locale.ROOT).contains("bukkitscheduler"))
                              && (mName.startsWith("runTask")
                              || mName.startsWith("scheduleSync")
                              || mName.startsWith("scheduleAsync")
                              || "callSyncMethod".equals(mName)
                              || "cancelTasks".equals(mName)
                              || "cancelTask".equals(mName))) {
                          semanticMacros.add("@bukkit_scheduler_task_sink:" + simpleClassName(mOwnerInternal) + "#" + mName);
                      }
                    String combinedCallLow = (mOwnerInternal + "#" + mName).toLowerCase(Locale.ROOT);
                    if (combinedCallLow.contains("runtask") || combinedCallLow.contains("schedule")
                            || combinedCallLow.contains("supplyasync") || combinedCallLow.contains("runasync")
                            || mOwnerInternal.contains("Scheduler")) {
                        semanticMacros.add("@scheduler_dispatch");
                    }
                    if ("java/util/LinkedHashMap".equals(mOwnerInternal)
                            && "<init>".equals(mName)
                            && "(IFZ)V".equals(mDesc)) {
                        callsAccessOrderLinkedHashMap = true;
                    }
                    // Blocking I/O detection (S-005 / CAUSAL-MAIN-IO)
                    boolean returnsAsyncFuture = mDesc != null && (
                            mDesc.endsWith(")Ljava/util/concurrent/CompletableFuture;")
                            || mDesc.endsWith(")Ljava/util/concurrent/Future;")
                            || mDesc.endsWith(")Ljava/util/concurrent/CompletionStage;")
                    );
                    if (("getChunkAt".equals(mName) || "loadChunk".equals(mName))
                            && !mName.toLowerCase(Locale.ROOT).contains("async")
                            && (mOwnerInternal.startsWith("org/bukkit/")
                            || mOwnerInternal.contains("World")
                            || mOwnerInternal.contains("ServerLevel"))) {
                        if (returnsAsyncFuture) {
                            semanticMacros.add("@async_chunk_io");
                        } else {
                            blockingIoCalls.add(simpleClassName(mOwnerInternal) + "#" + mName);
                            semanticMacros.add("@sync_chunk_hazard");
                        }
                    } else if (mName.toLowerCase(Locale.ROOT).contains("getchunkatasync")) {
                        semanticMacros.add("@async_chunk_io");
                    }
                    if ("java/nio/file/Files".equals(mOwnerInternal)
                            && ("readAllBytes".equals(mName) || "readString".equals(mName)
                            || "readAllLines".equals(mName) || "write".equals(mName)
                            || "writeString".equals(mName) || "newByteChannel".equals(mName)
                            || "newInputStream".equals(mName) || "newOutputStream".equals(mName)
                            || "lines".equals(mName) || "walk".equals(mName) || "copy".equals(mName))) {
                        blockingIoCalls.add("Files#" + mName);
                        directEffectSinks.add("STORAGE_IO");
                    } else if ("java/nio/channels/FileChannel".equals(mOwnerInternal) && "open".equals(mName)) {
                        blockingIoCalls.add("FileChannel#open");
                        directEffectSinks.add("STORAGE_IO");
                    } else if (("<init>".equals(mName))
                            && ("java/io/FileInputStream".equals(mOwnerInternal)
                            || "java/io/FileOutputStream".equals(mOwnerInternal)
                            || "java/io/RandomAccessFile".equals(mOwnerInternal))) {
                        blockingIoCalls.add(simpleClassName(mOwnerInternal) + "#<init>");
                        directEffectSinks.add("STORAGE_IO");
                    } else if ((mOwnerInternal.startsWith("java/sql/") || mOwnerInternal.contains("PreparedStatement") || mOwnerInternal.contains("Statement"))
                            && ("executeQuery".equals(mName) || "executeUpdate".equals(mName) || "execute".equals(mName))) {
                        blockingIoCalls.add(simpleClassName(mOwnerInternal) + "#" + mName);
                        directEffectSinks.add("STORAGE_IO");
                    } else if ((mOwnerInternal.endsWith("CompletableFuture") || mOwnerInternal.endsWith("Future"))
                            && "isDone".equals(mName)) {
                        sawFutureIsDoneGuard = true;
                    } else if ((mOwnerInternal.endsWith("CompletableFuture") || mOwnerInternal.endsWith("Future"))
                            && ("get".equals(mName) || "join".equals(mName))
                            && !sawFutureIsDoneGuard) {
                        blockingIoCalls.add(simpleClassName(mOwnerInternal) + "#" + mName);
                    }

                    // Resource & ChunkTicket allocation / release tracking (S-002 / CAUSAL-LEAK)
                    if (("<init>".equals(mName)
                            && ("java/io/FileInputStream".equals(mOwnerInternal)
                            || "java/io/FileOutputStream".equals(mOwnerInternal)
                            || "java/io/RandomAccessFile".equals(mOwnerInternal)))
                            || ("java/nio/channels/FileChannel".equals(mOwnerInternal) && "open".equals(mName))
                            || ("java/nio/file/Files".equals(mOwnerInternal)
                            && ("newByteChannel".equals(mName) || "newInputStream".equals(mName)
                            || "newOutputStream".equals(mName) || "lines".equals(mName) || "walk".equals(mName)))
                            || "acquireTicket".equals(mName)
                            || "reserveChunk".equals(mName)
                            || "addPluginChunkTicket".equals(mName)) {
                        String resLabel = "<init>".equals(mName)
                                ? simpleClassName(mOwnerInternal)
                                : (simpleClassName(mOwnerInternal) + "#" + mName);
                        if (!allocatedResources.contains(resLabel)) {
                            allocatedResources.add(resLabel);
                        }
                        if (firstAllocPc < 0) {
                            firstAllocPc = pc;
                        }
                    }
                    if (mOwnerInternal.contains("MemoryTracker")
                            || mName.startsWith("trackTicket")
                            || mName.startsWith("registerTicket")
                            || mName.startsWith("trackChunk")) {
                        trackedByMemoryTracker = true;
                        semanticMacros.add("@tracked_ticket");
                    }
                    if ("close".equals(mName)
                            || "release".equals(mName)
                            || "releaseTicket".equals(mName)
                            || "removePluginChunkTicket".equals(mName)
                            || "untrack".equals(mName)
                            || "closeQuietly".equals(mName)) {
                        closePcs.add(pc);
                        if ("removePluginChunkTicket".equals(mName) || "releaseTicket".equals(mName) || "untrack".equals(mName)) {
                            semanticMacros.add("@tracked_ticket");
                        }
                    }

                    // Check if CompletableFuture.supplyAsync, Supplier.get, or a CompletableFuture-returning call is immediately followed by POP (0x57) or POP2 (0x58)
                    int nextPc = pc + insLen;
                    if (nextPc < code.length) {
                        int nextOp = code[nextPc] & 0xFF;
                        if (nextOp == 0x57 || nextOp == 0x58) {
                            boolean isSupplyAsync = mOwnerInternal.endsWith("CompletableFuture") && "supplyAsync".equals(mName);
                            boolean isFutureReturn = mDesc != null && (
                                    mDesc.endsWith(")Ljava/util/concurrent/CompletableFuture;")
                                    || mDesc.endsWith(")Ljava/util/concurrent/CompletionStage;")
                            ) && (mName.startsWith("get")
                                    || mName.startsWith("supply")
                                    || mName.startsWith("compute")
                                    || mName.startsWith("fetch")
                                    || mName.startsWith("find")
                                    || mName.startsWith("query")
                                    || mName.startsWith("select")
                                    || mName.startsWith("pick"));
                            boolean isSupplierGet = ("get".equals(mName) && mOwnerInternal.toLowerCase(Locale.ROOT).contains("supplier"))
                                    || isFutureReturn;
                            if (isSupplyAsync || isSupplierGet) {
                                String orphanTarget = (isSupplyAsync && recentIndyLambdaTarget != null)
                                        ? recentIndyLambdaTarget
                                        : callRef;
                                if (!orphanedSuppliers.contains(orphanTarget)) {
                                    orphanedSuppliers.add(orphanTarget);
                                }
                            }
                        }
                    recentIndyLambdaTarget = null;
                }
            }
            }
            if (op == 0xBA && pc + 2 < code.length) {
                int indyCpIdx = readUnsignedShort(code, pc + 1);
                if (indyCpIdx > 0 && indyCpIdx < indyBootstrapIdx.length) {
                    int bsmIdx = indyBootstrapIdx[indyCpIdx];
                    List<String> targetLambdas = bootstrapTargetMethods.getOrDefault(bsmIdx, List.of());
                    if (targetLambdas.isEmpty()) {
                        String single = bootstrapTargetMethod.get(bsmIdx);
                        if (single != null) {
                            targetLambdas = List.of(single);
                        }
                    }
                    if (!targetLambdas.isEmpty()) {
                        int natIdx = indyCpIdx < indyNatIdx.length ? indyNatIdx[indyCpIdx] : 0;
                        String samName = (natIdx > 0 && natIdx < natNameIdx.length)
                                ? safeUtf8(utf8Pool, natNameIdx[natIdx])
                                : null;
                        String factoryDesc = (natIdx > 0 && natIdx < natDescIdx.length)
                                ? safeUtf8(utf8Pool, natDescIdx[natIdx])
                                : null;
                        String funcIface = extractReturnObjectBinaryName(factoryDesc);
                        for (String targetLambda : targetLambdas) {
                            if (!spawnedLambdas.contains(targetLambda)) {
                                spawnedLambdas.add(targetLambda);
                            }
                            recentIndyLambdaTarget = targetLambda;
                            if (funcIface != null && samName != null && classIndySitesOut != null) {
                                IndyFunctionalSite site = new IndyFunctionalSite(
                                        funcIface,
                                        samName,
                                        targetLambda,
                                        binaryClassName,
                                        rm.name()
                                );
                                if (!classIndySitesOut.contains(site)) {
                                    classIndySitesOut.add(site);
                                }
                            }
                        }
                    }
                }
            }

            pc += insLen;
        }

        // Check exception table for empty catch blocks (S-004 CAUSAL_SWALLOWED_FAILURE)
        List<String> swallowedExceptions = new ArrayList<>();
        for (ExceptionEntry ex : rm.exceptionTable()) {
            if (ex.catchClassInternal() == null || BENIGN_CAUGHT_EXCEPTIONS.contains(ex.catchClassInternal())) {
                continue;
            }
            if (isSilentCatchHandler(code, ex.handlerPc())) {
                String caughtBinary = ex.catchClassInternal().replace('/', '.');
                if (!swallowedExceptions.contains(caughtBinary)) {
                    swallowedExceptions.add(caughtBinary);
                }
            }
        }
        if (!swallowedExceptions.isEmpty()) {
            semanticMacros.add("@silent_catch_hazard");
        }

        boolean closesOrTracksResourcesOnAllPaths = true;
        String resourceLeakReason = "";
        if (!allocatedResources.isEmpty() && ! "<init>".equals(rm.name()) && !"<clinit>".equals(rm.name())) {
            String retDesc = rm.descriptor() != null && rm.descriptor().contains(")")
                    ? rm.descriptor().substring(rm.descriptor().indexOf(')') + 1)
                    : "V";
            boolean returnsResource = !retDesc.equals("V") && !retDesc.equals("Z") && !retDesc.equals("I")
                    && (retDesc.contains("Stream") || retDesc.contains("Channel") || retDesc.contains("Closeable")
                    || retDesc.contains("RandomAccessFile") || retDesc.contains("Ticket") || retDesc.contains("Reader")
                    || retDesc.contains("Writer") || rm.name().startsWith("open") || rm.name().startsWith("acquire")
                    || rm.name().startsWith("create"));
              if (!returnsResource && !trackedByMemoryTracker && !storedInManagedClassField) {
                  if (closePcs.isEmpty()) {
                      closesOrTracksResourcesOnAllPaths = false;
                      resourceLeakReason = "Allocates " + allocatedResources
                              + " without invoking close()/release()/removePluginChunkTicket() or registering with a lifecycle tracker (risks file-handle or force-loaded chunk memory leaks).";
                  } else {
                      boolean hasExceptionHandlerClose = false;
                      for (ExceptionEntry ex : rm.exceptionTable()) {
                          for (int cPc : closePcs) {
                              if (cPc >= ex.handlerPc() - 4) {
                                  hasExceptionHandlerClose = true;
                                  break;
                              }
                          }
                      }
                      if (!hasExceptionHandlerClose) {
                          int firstClose = closePcs.get(0);
                          for (int exitPc : earlyExitPcs) {
                              if (exitPc > firstAllocPc && exitPc < firstClose) {
                                  closesOrTracksResourcesOnAllPaths = false;
                                  resourceLeakReason = "Allocates " + allocatedResources
                                          + " at pc=" + firstAllocPc + " with an unguarded early return/throw at pc=" + exitPc
                                          + " before close()/release() at pc=" + firstClose
                                          + " and no try-with-resources/finally handler (risks file-handle or chunk-ticket leaks on early exit).";
                                  break;
                              }
                          }
                      }
                  }
              }
        }

        StackDataflowSummary stackSummary = analyzeStackArrayAndCallbackDataflow(
                binaryClassName,
                rm,
                code,
                utf8Pool,
                classNameIdx,
                fieldClassIdx,
                fieldNatIdx,
                methodClassIdx,
                methodNatIdx,
                natNameIdx,
                natDescIdx,
                indyBootstrapIdx,
                indyNatIdx,
                bootstrapTargetMethods,
                bootstrapFactoryOwner
        );
        collectionAddedFields.addAll(stackSummary.collectionAddedFields());
        collectionRemovedFields.addAll(stackSummary.collectionRemovedFields());
        writtenFields.addAll(stackSummary.collectionMutatedDirectFields());
        if (returnedField.isEmpty() && !stackSummary.returnedField().isEmpty()) {
            returnedField = stackSummary.returnedField();
        }

        return new MethodNode(
                binaryClassName,
                rm.name(),
                rm.descriptor(),
                rm.accessFlags(),
                rm.isEventHandler(),
                eventTypeSimpleName,
                methodAnn.isCommandEntry(),
                new ArrayList<>(mergedCommandRoots),
                new ArrayList<>(subcommandLiterals),
                new ArrayList<>(argumentParsers),
                new ArrayList<>(inlinePermissions),
                cmdDesc,
                calledMethods,
                spawnedLambdas,
                orphanedSuppliers,
                instantiatedClasses,
                conditionalInstantiations,
                conditionalMethodCalls,
                readFields,
                writtenFields,
                producedQueueFields,
                dispatchedEventTypes,
                configKeyToAssignedField,
                configKeyScalingHint,
                referencedConfigKeys,
                stringLiterals,
                guards,
                directEffectSinks,
                syncMainThreadCalls,
                swallowedExceptions,
                callsCurrentTimeMillis,
                multipliesBy1000Or20,
                usesDynamicConfigIteration,
                acquiredLocks,
                blockingIoCalls,
                allocatedResources,
                closesOrTracksResourcesOnAllPaths,
                resourceLeakReason,
                semanticMacros,
                callsAccessOrderLinkedHashMap,
                collectionAddedFields,
                collectionRemovedFields,
                stackSummary.arrayMutatedFields(),
                stackSummary.receiverMutatedFields(),
                stackSummary.dynamicFunctionalDispatches(),
                stackSummary.asyncFunctionalDispatches(),
                returnedField
        );
    }

    private record LookaheadConfigRead(
            String invokedGetter,
            String assignedField,
            String scalingHint
    ) {
    }

    private static LookaheadConfigRead lookaheadConfigGetter(
            byte[] code,
            int startPc,
            String[] numericPool,
            int[] stringUtf8Idx,
            int[] fieldClassIdx,
            int[] fieldNatIdx,
            int[] methodClassIdx,
            int[] methodNatIdx,
            int[] classNameIdx,
            int[] natNameIdx,
            String[] utf8Pool
    ) {
        int pc = startPc;
        int steps = 0;
        String getter = null;
        String assignedField = null;
        String scalingHint = null;

        while (pc < code.length && steps < 16) {
            int op = code[pc] & 0xFF;
            int len = instructionLength(code, pc);
            if (len <= 0) {
                break;
            }
            if (getter == null && (op == 0x12 || op == 0x13 || op == 0xB3 || op == 0xB5 || (op >= 0xAC && op <= 0xB1))) {
                break;
            }
            String c = extractPushedConstant(code, pc, numericPool, stringUtf8Idx, utf8Pool);
            if (getter != null && c != null) {
                if ("1000".equals(c) || "1000.0".equals(c)) {
                    scalingHint = "*1000_MS";
                } else if ("20".equals(c) || "20.0".equals(c)) {
                    scalingHint = "*20_TICKS";
                }
            }
            if ((op == 0xB6 || op == 0xB8 || op == 0xB9) && pc + 2 < code.length) {
                int mCpIdx = readUnsignedShort(code, pc + 1);
                String mName = resolveMethodName(mCpIdx, methodNatIdx, natNameIdx, utf8Pool);
                if (mName != null && CONFIG_GETTER_METHODS.contains(mName) && getter == null) {
                    getter = mName;
                    if ("parseSecondsDurationMillis".equals(mName) || "parseDurationSetting".equals(mName)) {
                        scalingHint = "*1000_MS";
                    }
                }
            } else if ((op == 0xB3 || op == 0xB5) && pc + 2 < code.length && getter != null) {
                int fCpIdx = readUnsignedShort(code, pc + 1);
                String candidateField = resolveFieldName(fCpIdx, fieldNatIdx, natNameIdx, utf8Pool);
                if (candidateField != null
                        && !(candidateField.equals(candidateField.toUpperCase(Locale.ROOT)) && candidateField.contains("_"))) {
                    assignedField = candidateField;
                }
                break;
            }
            pc += len;
            steps++;
        }
        return new LookaheadConfigRead(getter, assignedField, scalingHint);
    }

    private static String resolveGateLabel(
            String recentConfigKey,
            Set<String> referencedConfigKeys,
            String recentReadField
    ) {
        if (recentConfigKey != null && !recentConfigKey.isBlank()) {
            return recentConfigKey;
        }
        if (referencedConfigKeys != null && !referencedConfigKeys.isEmpty()) {
            return referencedConfigKeys.iterator().next();
        }
        if (recentReadField != null && !recentReadField.isBlank()) {
            return recentReadField;
        }
        return "conditional-branch";
    }

    private static boolean isInsideConditionalSpan(int pc, List<int[]> spans) {
        for (int[] span : spans) {
            if (pc > span[0] && pc < span[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a catch block at {@code handlerPc} immediately discards the exception and
     * returns (`RETURN` or `ACONST_NULL + ARETURN`) without invoking any
     * logging, messaging, or recovery method (`S-004`).
     */
    private static boolean isSilentCatchHandler(byte[] code, int handlerPc) {
        if (handlerPc < 0 || handlerPc >= code.length) {
            return false;
        }
        int pc = handlerPc;
        int op0 = code[pc] & 0xFF;
        int len0 = instructionLength(code, pc);
        if (len0 <= 0) {
            return false;
        }
        // Exception object on stack is stored (`astore` 0x3A or `astore_0..3` 0x4B..0x4E) or popped (`pop` 0x57)
        boolean storesOrPopsException = (op0 == 0x3A) || (op0 >= 0x4B && op0 <= 0x4E) || (op0 == 0x57);
        if (!storesOrPopsException) {
            return false;
        }
        int nextPc = pc + len0;
        if (nextPc >= code.length) {
            return false;
        }
        int op1 = code[nextPc] & 0xFF;
        // Immediate `return` (0xB1)
        if (op1 == 0xB1) {
            return true;
        }
        // Immediate `aconst_null` (0x01) + `areturn` (0xB0)
        if (op1 == 0x01 && nextPc + 1 < code.length) {
            int op2 = code[nextPc + 1] & 0xFF;
            return op2 == 0xB0;
        }
        return false;
    }

    private static void classifyEffectSinkAndThreadSafety(
            String ownerInternal,
            String methodName,
            String methodDesc,
            Set<String> directEffectSinks,
            Set<String> syncMainThreadCalls
    ) {
        String combined = (ownerInternal + "#" + methodName).toLowerCase(Locale.ROOT);
        boolean returnsFuture = methodDesc != null && (
                methodDesc.endsWith(")Ljava/util/concurrent/CompletableFuture;")
                || methodDesc.endsWith(")Ljava/util/concurrent/Future;")
                || methodDesc.endsWith(")Ljava/util/concurrent/CompletionStage;")
        );
        if (combined.contains("teleport")) {
            directEffectSinks.add("TELEPORT");
            if ("teleport".equals(methodName)
                    && !returnsFuture
                    && (ownerInternal.contains("org/bukkit/entity") || ownerInternal.contains("Player") || ownerInternal.contains("Entity"))) {
                syncMainThreadCalls.add(simpleClassName(ownerInternal) + "#teleport");
            }
        }
        if (combined.contains("openinventory") || combined.contains("openbook") || combined.contains("showmenu")
                || combined.contains("createinventory") || combined.contains("openmenu")
                || (ownerInternal.contains("menu") && ("open".equals(methodName) || "stage".equals(methodName) || "redeem".equals(methodName)))) {
            directEffectSinks.add("INVENTORY_MENU");
        }
          if (combined.contains("settype") || combined.contains("setblockdata") || combined.contains("breaknaturally")
                  || combined.contains("spawnentity") || combined.contains("createexplosion")
                  || combined.contains("sethealth") || combined.contains("setfoodlevel")) {
              directEffectSinks.add("WORLD_MUTATION");
              if ((ownerInternal.startsWith("org/bukkit/")
                      || ownerInternal.contains("Block")
                      || ownerInternal.contains("World")
                      || ownerInternal.contains("Entity")
                      || ownerInternal.contains("Player"))
                      && !ownerInternal.endsWith("/BlockState")
                      && !ownerInternal.endsWith("$BlockState")) {
                  syncMainThreadCalls.add(simpleClassName(ownerInternal) + "#" + methodName);
              }
          }
        if (("getchunkat".equals(methodName.toLowerCase(Locale.ROOT)) || "loadchunk".equals(methodName.toLowerCase(Locale.ROOT)))
                && !methodName.toLowerCase(Locale.ROOT).contains("async")
                && (ownerInternal.startsWith("org/bukkit/")
                || ownerInternal.contains("World")
                || ownerInternal.contains("ServerLevel"))) {
            directEffectSinks.add("CHUNK_IO");
            if (!returnsFuture) {
                syncMainThreadCalls.add(simpleClassName(ownerInternal) + "#" + methodName);
            }
        } else if (combined.contains("getchunkatasync")) {
            directEffectSinks.add("CHUNK_IO");
        }
        if (combined.contains("withdraw") || combined.contains("deposit") || combined.contains("economy")) {
            directEffectSinks.add("ECONOMY");
        }
        if (combined.contains("executeupdate") || combined.contains("executequery") || combined.contains("saveconfig")
                || combined.contains("writestring") || combined.contains("files#write")
                || combined.contains("export") || combined.contains("writebytes")
                || combined.contains("healandverifyworld") || combined.contains("convertworld")
                || combined.contains("compactopenregions")
                || ("flush".equals(methodName.toLowerCase(Locale.ROOT)) && ownerInternal.contains("StorageSession"))) {
            directEffectSinks.add("STORAGE_IO");
        }
        if ("setcancelled".equals(methodName.toLowerCase(Locale.ROOT))) {
            directEffectSinks.add("EVENT_CANCEL");
        }
        if (combined.contains("runtaskasynchronously") || combined.contains("supplyasync")
                || combined.contains("runasync") || combined.contains("runbatchtaskasync")
                || combined.contains("asyncscheduler")) {
            directEffectSinks.add("ASYNC_SCHEDULER");
        } else if (combined.contains("runtask") || combined.contains("schedule")) {
            directEffectSinks.add("SCHEDULER_TASK");
        }
        if ("sendmessage".equals(methodName.toLowerCase(Locale.ROOT)) || combined.contains("sendactionbar")
                || combined.contains("sendtitle")) {
            directEffectSinks.add("MESSAGE_ONLY");
        }
        if (combined.contains("playsound") || combined.contains("spawnparticle")) {
            directEffectSinks.add("SOUND_PARTICLE_ONLY");
        }
        boolean isQueueType = ownerInternal.contains("Queue")
                || ownerInternal.contains("Deque")
                || ownerInternal.contains("RingBuffer");
        if (!isQueueType && ((methodDesc != null && methodDesc.startsWith("()V") && methodName.startsWith("set") && methodName.length() > 3)
                || "put".equals(methodName) || "putIfAbsent".equals(methodName) || "computeIfAbsent".equals(methodName)
                || "add".equals(methodName) || "offer".equals(methodName) || "push".equals(methodName)
                || "remove".equals(methodName) || "clear".equals(methodName) || "complete".equals(methodName)
                || "submitCommand".equals(methodName) || "dispatchCommand".equals(methodName)
                || "performCommand".equals(methodName) || "accept".equals(methodName)
                || "apply".equals(methodName) || "execute".equals(methodName))) {
            directEffectSinks.add("STATE_MUTATION");
        }
    }

    private static boolean isCandidateSubcommandLiteral(String literal) {
        if (literal == null || literal.length() < 2 || literal.length() > 24) {
            return false;
        }
        String low = literal.toLowerCase(Locale.ROOT);
        if (NON_SUBCOMMAND_LITERALS.contains(low)) {
            return false;
        }
        char first = low.charAt(0);
        if (first < 'a' || first > 'z') {
            return false;
        }
        for (int i = 1; i < low.length(); i++) {
            char c = low.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPlausiblePermissionLiteral(String literal) {
        if (literal == null || literal.length() < 3 || literal.length() > 80 || !literal.contains(".")) {
            return false;
        }
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.'
                    || c == '_'
                    || c == '-'
                    || c == '*';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean lookaheadStringComparison(
            byte[] code,
            int startPc,
            int[] methodClassIdx,
            int[] methodNatIdx,
            int[] classNameIdx,
            int[] natNameIdx,
            String[] utf8Pool
    ) {
        int pc = startPc;
        int steps = 0;
        while (pc < code.length && steps < 6 && (pc - startPc) <= 16) {
            int op = code[pc] & 0xFF;
            int len = instructionLength(code, pc);
            if (len <= 0) {
                break;
            }
            if ((op >= 0xB6 && op <= 0xB9) && pc + 2 < code.length) {
                int mCpIdx = readUnsignedShort(code, pc + 1);
                String mOwner = resolveClassName(methodClassIdx[mCpIdx], classNameIdx, utf8Pool);
                String mName = resolveMethodName(mCpIdx, methodNatIdx, natNameIdx, utf8Pool);
                if (mOwner != null && mName != null) {
                    if ("java/lang/String".equals(mOwner) && STRING_COMPARE_METHODS.contains(mName)) {
                        return true;
                    }
                    break;
                }
            }
            pc += len;
            steps++;
        }
        return false;
    }

    private static String classifyArgumentParserCall(String ownerInternal, String methodName) {
        if (ownerInternal == null || methodName == null) {
            return null;
        }
        if ("java/lang/Integer".equals(ownerInternal)
                && ("parseInt".equals(methodName) || "valueOf".equals(methodName) || "decode".equals(methodName))) {
            return "brigadier:integer";
        }
        if ("java/lang/Long".equals(ownerInternal)
                && ("parseLong".equals(methodName) || "valueOf".equals(methodName))) {
            return "brigadier:long";
        }
        if ("java/lang/Double".equals(ownerInternal)
                && ("parseDouble".equals(methodName) || "valueOf".equals(methodName))) {
            return "brigadier:double";
        }
        if ("java/lang/Float".equals(ownerInternal)
                && ("parseFloat".equals(methodName) || "valueOf".equals(methodName))) {
            return "brigadier:float";
        }
        if ("java/lang/Boolean".equals(ownerInternal) && "parseBoolean".equals(methodName)) {
            return "brigadier:bool";
        }
        if ("java/util/UUID".equals(ownerInternal) && "fromString".equals(methodName)) {
            return "minecraft:uuid";
        }
        if ((ownerInternal.endsWith("Bukkit") || ownerInternal.endsWith("Server"))
                && ("getPlayer".equals(methodName) || "getPlayerExact".equals(methodName) || "getOfflinePlayer".equals(methodName))) {
            return "minecraft:game_profile";
        }
        if ((ownerInternal.endsWith("Bukkit") || ownerInternal.endsWith("Server")) && "getWorld".equals(methodName)) {
            return "minecraft:dimension";
        }
        if (ownerInternal.endsWith("Material")
                && ("matchMaterial".equals(methodName) || "getMaterial".equals(methodName) || "valueOf".equals(methodName))) {
            return "minecraft:item_stack";
        }
        return null;
    }

    private static List<String> extractDescriptorArgumentParsers(String desc) {
        if (desc == null || !desc.startsWith("(")) {
            return List.of();
        }
        int endParams = desc.indexOf(')');
        if (endParams <= 1) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        int idx = 1;
        int paramPos = 0;
        while (idx < endParams) {
            char c = desc.charAt(idx);
            if (c == '[') {
                idx++;
                continue;
            }
            if (c == 'L') {
                int semi = desc.indexOf(';', idx);
                if (semi < 0 || semi > endParams) {
                    break;
                }
                String internal = desc.substring(idx + 1, semi);
                idx = semi + 1;
                // Skip leading CommandSender/Player/Context parameter at slot 0
                if (paramPos == 0 && (internal.contains("CommandSender")
                        || internal.contains("Player")
                        || internal.contains("Context")
                        || internal.contains("CommandIssuer")
                        || internal.contains("CommandSource"))) {
                    paramPos++;
                    continue;
                }
                if ("java/lang/Integer".equals(internal)) {
                    out.add("brigadier:integer");
                } else if ("java/lang/Long".equals(internal)) {
                    out.add("brigadier:long");
                } else if ("java/lang/Double".equals(internal)) {
                    out.add("brigadier:double");
                } else if ("java/lang/Float".equals(internal)) {
                    out.add("brigadier:float");
                } else if ("java/lang/Boolean".equals(internal)) {
                    out.add("brigadier:bool");
                } else if (internal.endsWith("World")) {
                    out.add("minecraft:dimension");
                } else if (internal.endsWith("Player") || internal.endsWith("OfflinePlayer")) {
                    out.add("minecraft:game_profile");
                } else if (internal.endsWith("Material")) {
                    out.add("minecraft:item_stack");
                } else if ("java/util/UUID".equals(internal)) {
                    out.add("minecraft:uuid");
                }
                paramPos++;
            } else {
                idx++;
                if (c == 'I' || c == 'S' || c == 'B') {
                    out.add("brigadier:integer");
                } else if (c == 'J') {
                    out.add("brigadier:long");
                } else if (c == 'D') {
                    out.add("brigadier:double");
                } else if (c == 'F') {
                    out.add("brigadier:float");
                } else if (c == 'Z') {
                    out.add("brigadier:bool");
                }
                paramPos++;
            }
        }
        return out;
    }

    private static String extractFirstParamSimpleType(String desc) {
        if (desc == null || !desc.startsWith("(L")) {
            return "";
        }
        int semi = desc.indexOf(';');
        if (semi <= 2) {
            return "";
        }
        String internal = desc.substring(2, semi);
        return simpleClassName(internal);
    }

    static String simpleClassName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        String norm = name.replace('/', '.');
        int dot = norm.lastIndexOf('.');
        return dot >= 0 ? norm.substring(dot + 1) : norm;
    }

    static String simpleMethodRef(String symbol) {
        if (symbol == null || symbol.isEmpty()) {
            return "";
        }
        int hash = symbol.indexOf('#');
        if (hash < 0) {
            return simpleClassName(symbol);
        }
        return simpleClassName(symbol.substring(0, hash)) + symbol.substring(hash);
    }

    private static String resolveClassName(int classCpIdx, int[] classNameIdx, String[] utf8Pool) {
        if (classCpIdx <= 0 || classCpIdx >= classNameIdx.length) {
            return null;
        }
        int utfIdx = classNameIdx[classCpIdx];
        return safeUtf8(utf8Pool, utfIdx);
    }

    private static String resolveFieldName(int fieldCpIdx, int[] fieldNatIdx, int[] natNameIdx, String[] utf8Pool) {
        if (fieldCpIdx <= 0 || fieldCpIdx >= fieldNatIdx.length) {
            return null;
        }
        int nat = fieldNatIdx[fieldCpIdx];
        if (nat <= 0 || nat >= natNameIdx.length) {
            return null;
        }
        return safeUtf8(utf8Pool, natNameIdx[nat]);
    }

    private static String resolveMethodName(int methodCpIdx, int[] methodNatIdx, int[] natNameIdx, String[] utf8Pool) {
        if (methodCpIdx <= 0 || methodCpIdx >= methodNatIdx.length) {
            return null;
        }
        int nat = methodNatIdx[methodCpIdx];
        if (nat <= 0 || nat >= natNameIdx.length) {
            return null;
        }
        return safeUtf8(utf8Pool, natNameIdx[nat]);
    }

    private static String resolveMethodDesc(int methodCpIdx, int[] methodNatIdx, int[] natDescIdx, String[] utf8Pool) {
        if (methodCpIdx <= 0 || methodCpIdx >= methodNatIdx.length) {
            return null;
        }
        int nat = methodNatIdx[methodCpIdx];
        if (nat <= 0 || nat >= natDescIdx.length) {
            return null;
        }
        return safeUtf8(utf8Pool, natDescIdx[nat]);
    }

    private static String resolveConfigEnumKey(
            int fieldCpIdx,
            int[] fieldClassIdx,
            int[] fieldNatIdx,
            int[] classNameIdx,
            int[] natNameIdx,
            String[] utf8Pool
    ) {
        if (fieldCpIdx <= 0 || fieldCpIdx >= fieldNatIdx.length) {
            return null;
        }
        int nat = fieldNatIdx[fieldCpIdx];
        if (nat <= 0 || nat >= natNameIdx.length) {
            return null;
        }
        String ownerInternal = resolveClassName(fieldClassIdx[fieldCpIdx], classNameIdx, utf8Pool);
        String fieldName = safeUtf8(utf8Pool, natNameIdx[nat]);
        if (ownerInternal == null || fieldName == null || fieldName.startsWith("$")) {
            return null;
        }
        int slash = ownerInternal.lastIndexOf('/');
        String simpleOwner = slash >= 0 ? ownerInternal.substring(slash + 1) : ownerInternal;
        if ((simpleOwner.endsWith("Keys") || simpleOwner.endsWith("Params") || simpleOwner.endsWith("Settings"))
                && !simpleOwner.equals("Keys")) {
            return BytecodeConfigScanner.isPlausibleConfigKeyLiteral(fieldName) ? fieldName : null;
        }
        return null;
    }

    private static String safeUtf8(String[] utf8Pool, int index) {
        if (index <= 0 || index >= utf8Pool.length) {
            return null;
        }
        return utf8Pool[index];
    }

    private static String extractPushedConstant(
            byte[] code,
            int pc,
            String[] numericPool,
            int[] stringUtf8Idx,
            String[] utf8Pool
    ) {
        int op = code[pc] & 0xFF;
        return switch (op) {
            case 0x02 -> "-1";
            case 0x03, 0x09 -> "0";
            case 0x04, 0x0A -> "1";
            case 0x05 -> "2";
            case 0x06 -> "3";
            case 0x07 -> "4";
            case 0x08 -> "5";
            case 0x10 -> pc + 1 < code.length ? Integer.toString(code[pc + 1]) : null;
            case 0x11 -> pc + 2 < code.length ? Integer.toString((short) readUnsignedShort(code, pc + 1)) : null;
            case 0x12 -> {
                if (pc + 1 >= code.length) {
                    yield null;
                }
                int idx = code[pc + 1] & 0xFF;
                if (idx > 0 && idx < numericPool.length && numericPool[idx] != null) {
                    yield numericPool[idx];
                }
                if (idx > 0 && idx < stringUtf8Idx.length && stringUtf8Idx[idx] > 0) {
                    yield safeUtf8(utf8Pool, stringUtf8Idx[idx]);
                }
                yield null;
            }
            case 0x13, 0x14 -> {
                if (pc + 2 >= code.length) {
                    yield null;
                }
                int idx = readUnsignedShort(code, pc + 1);
                if (idx > 0 && idx < numericPool.length && numericPool[idx] != null) {
                    yield numericPool[idx];
                }
                if (op == 0x13 && idx > 0 && idx < stringUtf8Idx.length && stringUtf8Idx[idx] > 0) {
                    yield safeUtf8(utf8Pool, stringUtf8Idx[idx]);
                }
                yield null;
            }
            default -> null;
        };
    }

    private static int instructionLength(byte[] code, int pc) {
        int op = code[pc] & 0xFF;
        return switch (op) {
            case 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC -> 2;
            case 0x11, 0x13, 0x14, 0x84, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4,
                    0xA5, 0xA6, 0xA7, 0xA8, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7 -> 3;
            case 0xC5 -> 4;
            case 0xB9, 0xBA, 0xC8, 0xC9 -> 5;
            case 0xAA -> { // tableswitch
                int aligned = (pc + 4) & ~3;
                if (aligned + 12 > code.length) {
                    yield -1;
                }
                int low = readInt(code, aligned + 4);
                int high = readInt(code, aligned + 8);
                long count = (long) high - (long) low + 1L;
                if (count < 0 || count > 16384) {
                    yield -1;
                }
                yield (aligned - pc) + 12 + (int) (count * 4);
            }
            case 0xAB -> { // lookupswitch
                int aligned = (pc + 4) & ~3;
                if (aligned + 8 > code.length) {
                    yield -1;
                }
                int npairs = readInt(code, aligned + 4);
                if (npairs < 0 || npairs > 16384) {
                    yield -1;
                }
                yield (aligned - pc) + 8 + (npairs * 8);
            }
            case 0xC4 -> { // wide
                if (pc + 1 >= code.length) {
                    yield -1;
                }
                int nextOp = code[pc + 1] & 0xFF;
                yield nextOp == 0x84 ? 6 : 4;
            }
            default -> 1;
        };
    }

    private static int readUnsignedShort(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static byte[] readBoundedBytes(ZipFile zip, ZipEntry entry) throws IOException {
        long size = entry.getSize();
        if (size > MAX_RESOURCE_BYTES) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry);
             ByteArrayOutputStream out = new ByteArrayOutputStream(size > 0 ? (int) size : 4096)) {
            byte[] buf = new byte[8192];
            int total = 0;
            int r;
            while ((r = in.read(buf)) != -1) {
                total += r;
                if (total > MAX_RESOURCE_BYTES) {
                    return null;
                }
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        }
    }

    private static boolean isShadedLibraryClass(String entryName) {
        String low = entryName.toLowerCase(Locale.ROOT);
        return entryName.startsWith("META-INF/")
                || entryName.startsWith("kotlin/")
                || entryName.startsWith("kotlinx/")
                || entryName.startsWith("org/slf4j/")
                || entryName.startsWith("com/google/")
                || entryName.startsWith("io/netty/")
                || entryName.startsWith("org/yaml/snakeyaml/")
                || entryName.startsWith("com/zaxxer/hikari/")
                || entryName.startsWith("org/sqlite/")
                || entryName.startsWith("org/mariadb/")
                || entryName.startsWith("com/mysql/")
                || entryName.startsWith("org/bstats/")
                || entryName.startsWith("org/h2/")
                || entryName.startsWith("okhttp3/")
                || entryName.startsWith("org/apache/")
                || entryName.startsWith("org/incendo/cloud/")
                || entryName.startsWith("net/kyori/adventure/")
                || low.contains("/libs/")
                || low.contains("/libraries/")
                || low.contains("/shadow/")
                || low.contains("/shaded/")
                || low.contains("/external/")
                || low.contains("/relocated/")
                || low.contains("/bstats/")
                || low.contains("/hikari/")
                || low.contains("/hikaricp/")
                || low.contains("/kyori/adventure/")
                || low.contains("/paperlib/")
                || low.contains("/morepaperlib/")
                || low.contains("/xseries/");
    }

    private static final Map<String, String> KNOWN_ECOSYSTEM_PACKAGE_PREFIXES = createKnownEcosystemPrefixes();

    private static Map<String, String> createKnownEcosystemPrefixes() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("net.milkbowl.vault.", "Vault");
        map.put("me.clip.placeholderapi.", "PlaceholderAPI");
        map.put("com.sk89q.worldguard.", "WorldGuard");
        map.put("com.sk89q.worldedit.", "WorldEdit");
        map.put("com.comphenix.protocol.", "ProtocolLib");
        map.put("net.luckperms.api.", "LuckPerms");
        map.put("net.citizensnpcs.", "Citizens");
        map.put("io.lumine.mythic.", "MythicMobs");
        map.put("com.palmergames.bukkit.towny.", "Towny");
        map.put("me.ryanhamshire.GriefPrevention.", "GriefPrevention");
        map.put("me.angeschossen.lands.", "Lands");
        map.put("dev.lone.itemsadder.", "ItemsAdder");
        map.put("io.th0rgal.oraxen.", "Oraxen");
        map.put("de.tr7zw.changeme.nbtapi.", "NBTAPI");
        map.put("de.tr7zw.nbtapi.", "NBTAPI");
        map.put("fr.minuskube.inv.", "SmartInvs");
        map.put("fr.mrmicky.fastboard.", "FastBoard");
        map.put("com.bgsoftware.superiorskyblock.", "SuperiorSkyblock2");
        map.put("world.bentobox.bentobox.", "BentoBox");
        map.put("com.plotsquared.", "PlotSquared");
        map.put("com.massivecraft.factions.", "Factions");
        map.put("org.geysermc.floodgate.", "Floodgate");
        map.put("org.geysermc.geyser.", "Geyser");
        map.put("eu.decentsoftware.holograms.", "DecentHolograms");
        map.put("me.filoghost.holographicdisplays.", "HolographicDisplays");
        map.put("de.oliver.fancyholograms.", "FancyHolograms");
        return Collections.unmodifiableMap(map);
    }

    private static final List<String> STANDARD_OR_PLATFORM_CLASS_PREFIXES = List.of(
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "com.sun.",
            "org.w3c.",
            "org.xml.",
            "org.ietf.",
            "kotlin.",
            "kotlinx.",
            "scala.",
            "groovy.",
            "org.jetbrains.",
            "org.intellij.",
            "org.bukkit.",
            "org.spigotmc.",
            "io.papermc.",
            "com.destroystokyo.paper.",
            "net.md_5.",
            "com.mojang.",
            "net.minecraft.",
            "net.fabricmc.",
            "net.neoforged.",
            "net.minecraftforge.",
            "cpw.mods.",
            "com.velocitypowered.",
            "net.kyori.",
            "org.spongepowered.",
            "org.slf4j.",
            "org.apache.logging.",
            "com.google.",
            "io.netty.",
            "org.yaml.snakeyaml.",
            "com.zaxxer.hikari.",
            "org.sqlite.",
            "org.mariadb.",
            "com.mysql.",
            "org.bstats.",
            "org.junit.",
            "org.opentest4j.",
            "org.mockito."
    );

    private record ExternalDependencyClassification(
            Set<String> inferredEcosystemDependencies,
            Set<String> inferredExternalPackages
    ) {
    }

    private static String normalizeConstantPoolClassName(String rawInternal) {
        if (rawInternal == null || rawInternal.isBlank()) {
            return null;
        }
        String s = rawInternal.trim();
        int idx = 0;
        while (idx < s.length() && s.charAt(idx) == '[') {
            idx++;
        }
        if (idx > 0) {
            if (idx < s.length() && s.charAt(idx) == 'L' && s.endsWith(";")) {
                s = s.substring(idx + 1, s.length() - 1);
            } else {
                return null;
            }
        }
        if (s.indexOf('/') <= 0) {
            return null;
        }
        return s.replace('/', '.');
    }

    private static ExternalDependencyClassification classifyExternalDependencies(
            Set<String> allReferencedClasses,
            Set<String> intraJarClasses
    ) {
        if (allReferencedClasses == null || allReferencedClasses.isEmpty()) {
            return new ExternalDependencyClassification(Set.of(), Set.of());
        }
        Set<String> intraRoots = new LinkedHashSet<>();
        Set<String> intraPackages = new LinkedHashSet<>();
        for (String intra : intraJarClasses) {
            intraRoots.add(intra);
            int dollar = intra.indexOf('$');
            if (dollar > 0) {
                intraRoots.add(intra.substring(0, dollar));
            }
            int lastDot = intra.lastIndexOf('.');
            if (lastDot > 0) {
                intraPackages.add(intra.substring(0, lastDot));
            }
        }

        Set<String> ecosystemDeps = new java.util.TreeSet<>();
        Set<String> externalPackages = new java.util.TreeSet<>();
        for (String ref : allReferencedClasses) {
            if (intraRoots.contains(ref)) {
                continue;
            }
            int dollar = ref.indexOf('$');
            String outerRef = dollar > 0 ? ref.substring(0, dollar) : ref;
            if (intraRoots.contains(outerRef)) {
                continue;
            }

            String matchedEcosystem = null;
            for (var entry : KNOWN_ECOSYSTEM_PACKAGE_PREFIXES.entrySet()) {
                if (outerRef.startsWith(entry.getKey())) {
                    matchedEcosystem = entry.getValue();
                    break;
                }
            }
            if (matchedEcosystem != null) {
                ecosystemDeps.add(matchedEcosystem);
                continue;
            }

            int lastDot = outerRef.lastIndexOf('.');
            if (lastDot <= 0) {
                continue;
            }
            String pkg = outerRef.substring(0, lastDot);
            if (intraPackages.contains(pkg)) {
                continue;
            }

            boolean isStandardOrShaded = isShadedLibraryClass(outerRef.replace('.', '/') + ".class");
            if (!isStandardOrShaded) {
                for (String stdPrefix : STANDARD_OR_PLATFORM_CLASS_PREFIXES) {
                    if (outerRef.startsWith(stdPrefix)) {
                        isStandardOrShaded = true;
                        break;
                    }
                }
            }
            if (isStandardOrShaded) {
                continue;
            }

            externalPackages.add(extractThreeSegmentPackagePrefix(pkg));
        }
          return new ExternalDependencyClassification(ecosystemDeps, externalPackages);
      }

      private static String extractThreeSegmentPackagePrefix(String pkg) {
          if (pkg == null || pkg.isEmpty()) {
              return "";
          }
          int firstDot = pkg.indexOf('.');
          if (firstDot < 0) {
              return pkg;
          }
          int secondDot = pkg.indexOf('.', firstDot + 1);
          if (secondDot < 0) {
              return pkg;
          }
          int thirdDot = pkg.indexOf('.', secondDot + 1);
          return thirdDot < 0 ? pkg : pkg.substring(0, thirdDot);
      }

      private record FoliaHopState(
              MethodNode node,
              int depth,
              boolean onGlobalRegion,
              boolean onOwnedRegion,
              boolean onAsync,
              List<String> path
      ) {
      }

      private static List<CausalFinding> traceCausalFoliaRuntimeExceptions(
              String effName,
              int startingSeq,
              List<MethodNode> allMethods,
              Map<String, ParsedClass> classesByName,
              Map<String, MethodNode> methodsBySymbol,
              Map<String, List<MethodNode>> methodsByName,
              Map<String, ListenerRegistrationMode> listenerRegistrations,
              Set<String> allAsyncSpawnedLambdas,
              List<CausalFinding> existingFindings
      ) {
          List<CausalFinding> out = new ArrayList<>();
          int seq = startingSeq;
          Set<String> alreadyFlaggedSymbols = new LinkedHashSet<>();
          for (CausalFinding f : existingFindings) {
              if (f.kind() == FindingKind.CAUSAL_ASYNC_MAIN_THREAD_SINK
                      && f.declaredSurface().contains("folia-supported: true")) {
                  alreadyFlaggedSymbols.add(f.entryPointSymbol());
              }
          }

          List<MethodNode> roots = collectActiveFoliaRoots(
                  allMethods,
                  classesByName,
                  listenerRegistrations,
                  allAsyncSpawnedLambdas
          );
          for (MethodNode root : roots) {
              boolean rootAsync = root.isEventHandler()
                      && root.eventTypeSimpleName().toLowerCase(Locale.ROOT).startsWith("async");
              boolean rootOwned = root.isEventHandler()
                      && !rootAsync
                      && !isGlobalLifecycleEvent(root.eventTypeSimpleName());

              Deque<FoliaHopState> queue = new ArrayDeque<>();
              Set<String> visited = new LinkedHashSet<>();
              queue.add(new FoliaHopState(root, 0, false, rootOwned, rootAsync, List.of(root.symbol())));
              visited.add(root.symbol() + "|G=false|O=" + rootOwned + "|A=" + rootAsync);

              while (!queue.isEmpty()) {
                  FoliaHopState cur = queue.poll();
                  MethodNode node = cur.node();
                  boolean callsFoliaDetect = callsFoliaRuntimeDetection(node);
                  boolean callsFoliaSched = callsFoliaSchedulerApi(node);
                  boolean callsAsyncTp = node.calledMethods().stream().anyMatch(c -> c.endsWith("#teleportAsync"));
                  boolean callsAsyncChunk = node.calledMethods().stream().anyMatch(c ->
                          c.toLowerCase(Locale.ROOT).contains("getchunkatasync") || c.endsWith("#isChunkLoaded"))
                          || node.semanticMacros().contains("@async_chunk_io");

                  List<String> sinkViolations = collectFoliaExceptionSinksForNode(
                          node,
                          cur.onGlobalRegion(),
                          cur.onOwnedRegion(),
                          cur.onAsync(),
                          callsFoliaDetect,
                          callsFoliaSched,
                          callsAsyncTp,
                          callsAsyncChunk
                  );
                  if (!sinkViolations.isEmpty() && alreadyFlaggedSymbols.add(node.symbol())) {
                      alreadyFlaggedSymbols.add(root.symbol() + "->" + node.symbol());
                      List<String> steps = new ArrayList<>();
                      steps.add("FOLIA_CAUSAL_EXCEPTION -> "
                              + String.join(" -> ", cur.path())
                              + " -> " + sinkViolations);
                      out.add(new CausalFinding(
                              "CAUSAL-" + (seq++),
                              effName,
                              2,
                              FindingKind.CAUSAL_ASYNC_MAIN_THREAD_SINK,
                              ConfidenceTier.CONFIRMED,
                              100,
                              root.symbol(),
                              effName + " declares 'folia-supported: true' in plugin descriptor",
                              "Active entrypoint " + simpleMethodRef(root.symbol())
                                      + " causally reaches Folia runtime exception sink " + sinkViolations
                                      + " at " + simpleMethodRef(node.symbol())
                                      + " without a Folia branch guard or region scheduler handoff.",
                              "Dispatch region-owned mutations via Folia RegionScheduler/EntityScheduler and replace legacy BukkitScheduler/sync teleport with Folia schedulers and teleportAsync.",
                              steps,
                              ""
                      ));
                  }
                  expandFoliaHops(
                          cur,
                          callsFoliaDetect,
                          callsFoliaSched,
                          callsAsyncTp,
                          methodsBySymbol,
                          methodsByName,
                          allAsyncSpawnedLambdas,
                          visited,
                          queue
                  );
              }
          }
          return out;
      }

      private static List<MethodNode> collectActiveFoliaRoots(
              List<MethodNode> allMethods,
              Map<String, ParsedClass> classesByName,
              Map<String, ListenerRegistrationMode> listenerRegistrations,
              Set<String> allAsyncSpawnedLambdas
      ) {
          List<MethodNode> roots = new ArrayList<>();
          for (MethodNode m : allMethods) {
              if ((m.accessFlags() & (ACC_SYNTHETIC | 0x0040)) != 0 && !m.name().startsWith("lambda$")) {
                  continue;
              }
              if ("onEnable".equals(m.name()) || "onLoad".equals(m.name()) || "onDisable".equals(m.name())) {
                  roots.add(m);
                  continue;
              }
              ParsedClass ownerPc = classesByName.get(m.ownerClass());
              EntryPointKind kind = classifyEntryPoint(m, ownerPc, allAsyncSpawnedLambdas);
              if (kind == EntryPointKind.COMMAND) {
                  roots.add(m);
              } else if (kind == EntryPointKind.EVENT
                      && listenerRegistrations.get(m.ownerClass()) != ListenerRegistrationMode.UNREGISTERED) {
                  roots.add(m);
              }
          }
          if (roots.isEmpty()) {
              boolean hasDedicatedFoliaAdapter = classesByName.keySet().stream().anyMatch(c ->
                      c.contains(".folia.") || c.contains("FoliaScheduler") || c.contains("FoliaRTP"));
              if (!hasDedicatedFoliaAdapter) {
                  for (MethodNode m : allMethods) {
                      if ((m.accessFlags() & (ACC_SYNTHETIC | 0x0040)) != 0
                              || "<init>".equals(m.name())
                              || "<clinit>".equals(m.name())
                              || m.semanticMacros().contains("@folia_region_guard")) {
                          continue;
                      }
                      roots.add(m);
                  }
              }
          }
          return roots;
      }

      private static boolean isGlobalLifecycleEvent(String eventSimpleName) {
          if (eventSimpleName == null || eventSimpleName.isEmpty()) {
              return true;
          }
          return eventSimpleName.startsWith("Server")
                  || eventSimpleName.startsWith("Plugin")
                  || eventSimpleName.startsWith("RemoteServer")
                  || eventSimpleName.startsWith("Service");
      }

      private static boolean callsFoliaRuntimeDetection(MethodNode node) {
          for (String c : node.calledMethods()) {
              String low = c.toLowerCase(Locale.ROOT);
              if (low.endsWith("#isfolia")
                      || low.endsWith("#hasfolia")
                      || low.endsWith("#isfoliasupported")
                      || low.endsWith("#isregionizedserver")
                      || low.endsWith("#ispaperfolia")
                      || low.endsWith("#isownedbycurrentregion")) {
                  return true;
              }
          }
          for (String lit : node.stringLiterals()) {
              if (lit.contains("RegionizedServer")
                      || lit.contains("io.papermc.paper.threadedregions")
                      || lit.contains("GlobalRegionScheduler")
                      || lit.contains("RegionScheduler")) {
                  return true;
              }
          }
          return false;
      }

      private static boolean callsFoliaSchedulerApi(MethodNode node) {
          for (String c : node.calledMethods()) {
              if (c.contains("GlobalRegionScheduler")
                      || c.contains("RegionScheduler")
                      || c.contains("EntityScheduler")
                      || c.contains("AsyncScheduler")) {
                  return true;
              }
          }
          return false;
      }

      private static List<String> collectFoliaExceptionSinksForNode(
              MethodNode node,
              boolean onGlobalRegion,
              boolean onOwnedRegion,
              boolean onAsync,
              boolean callsFoliaDetect,
              boolean callsFoliaSched,
              boolean callsAsyncTp,
              boolean callsAsyncChunk
      ) {
          Set<String> sinks = new LinkedHashSet<>();
          if (!callsFoliaDetect && !callsFoliaSched) {
              for (String c : node.calledMethods()) {
                  if (c.endsWith("Bukkit#getScheduler") || c.endsWith("Server#getScheduler")) {
                      sinks.add(simpleMethodRef(c));
                  }
              }
              for (String macro : node.semanticMacros()) {
                  if (macro.startsWith("@bukkit_scheduler_task_sink:")) {
                      sinks.add(macro.substring("@bukkit_scheduler_task_sink:".length()));
                  }
              }
          }
          if (!callsFoliaDetect && !callsAsyncTp) {
              for (String syncCall : node.syncMainThreadCalls()) {
                  if (syncCall.endsWith("#teleport")) {
                      sinks.add(syncCall);
                  }
              }
          }
          if ((onGlobalRegion || onAsync) && !onOwnedRegion && !callsFoliaDetect) {
              for (String syncCall : node.syncMainThreadCalls()) {
                  sinks.add(syncCall);
              }
          } else if (!onOwnedRegion && !callsFoliaDetect && !callsAsyncChunk) {
              for (String syncCall : node.syncMainThreadCalls()) {
                  if (syncCall.endsWith("#getChunkAt") || syncCall.endsWith("#loadChunk")) {
                      sinks.add(syncCall);
                  }
              }
          }
          return new ArrayList<>(sinks);
      }

      private static void expandFoliaHops(
              FoliaHopState cur,
              boolean callsFoliaDetect,
              boolean callsFoliaSched,
              boolean callsAsyncTp,
              Map<String, MethodNode> methodsBySymbol,
              Map<String, List<MethodNode>> methodsByName,
              Set<String> allAsyncSpawnedLambdas,
              Set<String> visited,
              Deque<FoliaHopState> queue
      ) {
          if (cur.depth() >= MAX_CAUSAL_HOPS) {
              return;
          }
          MethodNode node = cur.node();
          Set<String> globalLambdaTargets = new LinkedHashSet<>();
          Set<String> ownedLambdaTargets = new LinkedHashSet<>();
          for (String macro : node.semanticMacros()) {
              if (macro.startsWith("@folia_global_region_lambda:")) {
                  globalLambdaTargets.add(macro.substring("@folia_global_region_lambda:".length()));
              } else if (macro.startsWith("@folia_owned_region_lambda:")) {
                  ownedLambdaTargets.add(macro.substring("@folia_owned_region_lambda:".length()));
              }
          }
          List<String> rawTargets = new ArrayList<>(node.calledMethods());
          for (String lam : node.spawnedLambdas()) {
              if (!rawTargets.contains(lam)) {
                  rawTargets.add(lam);
              }
          }
          for (String raw : rawTargets) {
              MethodNode callee = findMethodNodeFlexible(methodsBySymbol, methodsByName, node.ownerClass(), raw);
              if (callee == null) {
                  continue;
              }
              boolean isGlobalLam = globalLambdaTargets.contains(callee.symbol())
                      || globalLambdaTargets.contains(simpleMethodRef(callee.symbol()));
              boolean isOwnedLam = ownedLambdaTargets.contains(callee.symbol())
                      || ownedLambdaTargets.contains(simpleMethodRef(callee.symbol()));
              if (isDischargedFoliaFallbackCallee(callee, callsFoliaDetect, callsFoliaSched, callsAsyncTp, isGlobalLam, isOwnedLam)) {
                  continue;
              }
              boolean nextGlobal = isGlobalLam || (cur.onGlobalRegion() && !isOwnedLam);
              boolean nextOwned = isOwnedLam
                      || (cur.onOwnedRegion() && !nextGlobal && !allAsyncSpawnedLambdas.contains(callee.symbol()));
              boolean nextAsync = allAsyncSpawnedLambdas.contains(callee.symbol())
                      || (cur.onAsync() && !nextOwned && !nextGlobal);
              String stateKey = callee.symbol() + "|G=" + nextGlobal + "|O=" + nextOwned + "|A=" + nextAsync;
              if (visited.add(stateKey)) {
                  List<String> nextPath = new ArrayList<>(cur.path());
                  nextPath.add(callee.symbol());
                  queue.add(new FoliaHopState(callee, cur.depth() + 1, nextGlobal, nextOwned, nextAsync, nextPath));
              }
          }
      }

      private static boolean isDischargedFoliaFallbackCallee(
              MethodNode callee,
              boolean callerHasFoliaDetect,
              boolean callerHasFoliaSched,
              boolean callerHasAsyncTp,
              boolean isGlobalLam,
              boolean isOwnedLam
      ) {
          if (isGlobalLam || isOwnedLam) {
              return false;
          }
          String symLow = callee.symbol().toLowerCase(Locale.ROOT);
          boolean looksLikeLegacyFallback = symLow.contains("spigot")
                  || symLow.contains("legacy")
                  || symLow.contains("bukkit")
                  || symLow.contains("fallback");
          if (callerHasFoliaDetect && (looksLikeLegacyFallback || !callee.name().startsWith("lambda$"))) {
              return true;
          }
          if (callerHasFoliaSched && looksLikeLegacyFallback) {
              return true;
          }
          return callerHasAsyncTp && symLow.contains("teleport");
      }

      private record SecurityHopState(
              MethodNode node,
              int depth,
              boolean hasPermGuard,
              boolean isChatOrJoinRoot,
              String secretTriggerLiteral,
              String networkSourceStep,
              List<String> path
      ) {
      }

      private static List<CausalFinding> traceCausalBackdoorAndDropperChains(
              String effName,
              int startingSeq,
              List<MethodNode> allMethods,
              Map<String, ParsedClass> classesByName,
              Map<String, MethodNode> methodsBySymbol,
              Map<String, List<MethodNode>> methodsByName,
              List<JarConfigScanner.CommandDescriptor> cmds,
              Set<String> allAsyncSpawnedLambdas
      ) {
          List<CausalFinding> out = new ArrayList<>();
          int seq = startingSeq;
          Set<String> emittedSinks = new LinkedHashSet<>();

          Set<String> permissionedCommands = new LinkedHashSet<>();
          for (JarConfigScanner.CommandDescriptor cd : cmds) {
              if (cd.permission() != null && !cd.permission().isBlank()) {
                  permissionedCommands.add(cd.name().toLowerCase(Locale.ROOT));
              }
          }

          for (MethodNode root : allMethods) {
              if ((root.accessFlags() & (ACC_SYNTHETIC | 0x0040)) != 0 && !root.name().startsWith("lambda$")) {
                  continue;
              }
              ParsedClass ownerPc = classesByName.get(root.ownerClass());
              EntryPointKind epKind = classifyEntryPoint(root, ownerPc, allAsyncSpawnedLambdas);
              boolean isLifecycle = "onEnable".equals(root.name()) || "onLoad".equals(root.name());
              if (epKind == null && !isLifecycle) {
                  continue;
              }
              boolean rootPerm = hasMethodPermissionGuard(root)
                      || (epKind == EntryPointKind.COMMAND && !permissionedCommands.isEmpty()
                      && (root.annotatedCommandRoots().isEmpty()
                      || root.annotatedCommandRoots().stream().anyMatch(r ->
                      permissionedCommands.contains(r.toLowerCase(Locale.ROOT)))));
              boolean chatOrJoin = isChatOrJoinMethod(root);

              Deque<SecurityHopState> queue = new ArrayDeque<>();
              Set<String> visited = new LinkedHashSet<>();
              queue.add(new SecurityHopState(
                      root,
                      0,
                      rootPerm,
                      chatOrJoin,
                      findSecretOrUuidLiteral(root),
                      findNetworkSourceCall(root),
                      List.of(root.symbol())
              ));
              visited.add(root.symbol());

              while (!queue.isEmpty()) {
                  SecurityHopState cur = queue.poll();
                  MethodNode node = cur.node();
                  boolean permGuard = cur.hasPermGuard() || hasMethodPermissionGuard(node);
                  String secretLit = cur.secretTriggerLiteral() != null
                          ? cur.secretTriggerLiteral()
                          : findSecretOrUuidLiteral(node);
                  String netSrc = cur.networkSourceStep() != null
                          ? cur.networkSourceStep()
                          : findNetworkSourceCall(node);

                  String backdoorSink = findBackdoorPrivilegeSink(node, permGuard, cur.isChatOrJoinRoot(), secretLit);
                  if (backdoorSink != null) {
                      String sinkKey = "SEC-FORCE-OP-BACKDOOR:" + node.symbol();
                      String bareSinkKey = "SEC-FORCE-OP-BACKDOOR:" + node.ownerClass() + "#" + node.name();
                      if (emittedSinks.add(sinkKey)) {
                          emittedSinks.add(bareSinkKey);
                          String chainStep = "MALICIOUS_BACKDOOR -> "
                                  + String.join(" -> ", cur.path())
                                  + (secretLit != null ? " [trigger=" + secretLit + "]" : "")
                                  + " -> " + backdoorSink;
                          out.add(new CausalFinding(
                                  "CAUSAL-" + (seq++),
                                  effName,
                                  2,
                                  FindingKind.CAUSAL_LLM_SEMANTIC_MISMATCH,
                                  ConfidenceTier.CONFIRMED,
                                  100,
                                  root.symbol(),
                                  "[SEC-FORCE-OP-BACKDOOR | CWE-269] " + root.symbol(),
                                  "Entrypoint " + simpleMethodRef(root.symbol())
                                          + " causally reaches privilege-escalation sink " + backdoorSink
                                          + " at " + simpleMethodRef(node.symbol()) + ".",
                                  "Remove covert setOp/wildcard-permission/console-dispatch triggers and enforce explicit server permission nodes.",
                                  List.of(chainStep),
                                  ""
                          ));
                      }
                  }

                  String dropperSink = findDynamicClassLoaderSink(node, netSrc);
                  if (netSrc != null && dropperSink != null) {
                      String sinkKey = "SEC-COVERT-CLASSLOADER-DROPPER:" + node.symbol();
                      String bareSinkKey = "SEC-COVERT-CLASSLOADER-DROPPER:" + node.ownerClass() + "#" + node.name();
                      if (emittedSinks.add(sinkKey)) {
                          emittedSinks.add(bareSinkKey);
                          String chainStep = "MALICIOUS_BACKDOOR -> "
                                  + String.join(" -> ", cur.path())
                                  + " [net=" + netSrc + "] -> " + dropperSink;
                          out.add(new CausalFinding(
                                  "CAUSAL-" + (seq++),
                                  effName,
                                  2,
                                  FindingKind.CAUSAL_LLM_SEMANTIC_MISMATCH,
                                  ConfidenceTier.CONFIRMED,
                                  100,
                                  root.symbol(),
                                  "[SEC-COVERT-CLASSLOADER-DROPPER | CWE-494] " + root.symbol(),
                                  "Entrypoint " + simpleMethodRef(root.symbol())
                                          + " causally chains remote network source (" + netSrc
                                          + ") to dynamic classloader sink (" + dropperSink + ").",
                                  "Do not download and dynamically load untrusted remote .jar/.class payloads at runtime.",
                                  List.of(chainStep),
                                  ""
                          ));
                      }
                  }

                  if (cur.depth() >= MAX_CAUSAL_HOPS) {
                      continue;
                  }
                  List<String> nextTargets = new ArrayList<>(node.calledMethods());
                  for (String lam : node.spawnedLambdas()) {
                      if (!nextTargets.contains(lam)) {
                          nextTargets.add(lam);
                      }
                  }
                  for (String raw : nextTargets) {
                      MethodNode callee = findMethodNodeFlexible(methodsBySymbol, methodsByName, node.ownerClass(), raw);
                      if (callee != null && visited.add(callee.symbol())) {
                          List<String> nextPath = new ArrayList<>(cur.path());
                          nextPath.add(callee.symbol());
                          queue.add(new SecurityHopState(
                                  callee,
                                  cur.depth() + 1,
                                  permGuard,
                                  cur.isChatOrJoinRoot(),
                                  secretLit,
                                  netSrc,
                                  nextPath
                          ));
                      }
                  }
              }
          }

          List<BytecodeCweAnalyzers.CweBytecodeFinding> cweFindings =
                  BytecodeCweAnalyzers.analyzeParsedClasses(classesByName);
          for (BytecodeCweAnalyzers.CweBytecodeFinding cwe : cweFindings) {
              if ("SEC-FORCE-OP-BACKDOOR".equals(cwe.ruleId())
                      || "SEC-COVERT-CLASSLOADER-DROPPER".equals(cwe.ruleId())) {
                  String bareSinkKey = cwe.ruleId() + ":" + cwe.ownerClass() + "#" + cwe.methodName();
                  if (emittedSinks.add(bareSinkKey)) {
                      out.add(new CausalFinding(
                              "CAUSAL-" + (seq++),
                              effName,
                              2,
                              FindingKind.CAUSAL_LLM_SEMANTIC_MISMATCH,
                              ConfidenceTier.CONFIRMED,
                              100,
                              cwe.ownerClass() + "#" + cwe.methodName(),
                              "[" + cwe.ruleId() + " | " + cwe.cweId() + "] " + cwe.ownerClass() + "#" + cwe.methodName(),
                              cwe.message(),
                              cwe.remediation(),
                              cwe.chain(),
                              ""
                      ));
                  }
              }
          }
          return out;
      }

      private static boolean hasMethodPermissionGuard(MethodNode node) {
          if (!node.inlinePermissions().isEmpty()) {
              return true;
          }
          for (String g : node.guards()) {
              if (g.contains("hasPermission")) {
                  return true;
              }
          }
          for (String c : node.calledMethods()) {
              if (c.endsWith("#hasPermission") || c.endsWith("#checkPermission")) {
                  return true;
              }
          }
          return false;
      }

      private static boolean isChatOrJoinMethod(MethodNode node) {
          String evLow = node.eventTypeSimpleName().toLowerCase(Locale.ROOT);
          String nameLow = node.name().toLowerCase(Locale.ROOT);
          return evLow.contains("chat")
                  || evLow.contains("join")
                  || evLow.contains("login")
                  || nameLow.contains("onchat")
                  || nameLow.contains("onplayerchat")
                  || nameLow.contains("onjoin")
                  || nameLow.contains("onlogin");
      }

      private static String findSecretOrUuidLiteral(MethodNode node) {
          for (String s : node.stringLiterals()) {
              if (s.startsWith("#op")
                      || s.startsWith(".op")
                      || s.startsWith("!op")
                      || s.equalsIgnoreCase("#forceop")
                      || s.equalsIgnoreCase(".forceop")
                      || UUID_RE.matcher(s).matches()) {
                  return s;
              }
          }
          return null;
      }

      private static String findBackdoorPrivilegeSink(
              MethodNode node,
              boolean hasPermGuard,
              boolean isChatOrJoinRoot,
              String secretTriggerLiteral
      ) {
          boolean hasConsoleSender = false;
          String setOpCall = null;
          String permCall = null;
          String dispatchCall = null;
          for (String c : node.calledMethods()) {
              if (c.endsWith("#setOp")) {
                  setOpCall = c;
              } else if (c.endsWith("#setPermission") || c.endsWith("#addAttachment")) {
                  permCall = c;
              } else if (c.endsWith("#dispatchCommand")) {
                  dispatchCall = c;
              } else if (c.endsWith("#getConsoleSender")) {
                  hasConsoleSender = true;
              }
          }
          if (setOpCall != null && (!hasPermGuard || isChatOrJoinRoot || secretTriggerLiteral != null)) {
              return simpleMethodRef(setOpCall);
          }
          if (permCall != null && node.stringLiterals().contains("*")
                  && (!hasPermGuard || isChatOrJoinRoot || secretTriggerLiteral != null)) {
              return simpleMethodRef(permCall) + "(\"*\")";
          }
          if (dispatchCall != null && hasConsoleSender && !hasPermGuard
                  && (isChatOrJoinRoot || secretTriggerLiteral != null)) {
              return simpleMethodRef(dispatchCall);
          }
          return null;
      }

      private static String findNetworkSourceCall(MethodNode node) {
          for (String c : node.calledMethods()) {
              if (c.startsWith("java.net.URL#open")
                      || c.startsWith("java.net.HttpURLConnection#")
                      || c.startsWith("java.net.URLConnection#")
                      || c.startsWith("java.net.http.HttpClient#send")
                      || c.startsWith("java.net.Socket#")
                      || c.equals("java.nio.channels.Channels#newChannel")) {
                  return simpleMethodRef(c);
              }
          }
          if (node.instantiatedClasses().contains("java.net.Socket")) {
              return "Socket#<init>";
          }
          boolean hasLocalFileToUri = hasLocalFileUriConversion(node);
          boolean constructsRemoteUrlOrUri = node.instantiatedClasses().contains("java.net.URL")
                  || node.calledMethods().stream().anyMatch(c ->
                  c.equals("java.net.URL#<init>")
                          || c.equals("java.net.URI#create")
                          || c.equals("java.net.URI#<init>")
                          || c.startsWith("java.net.http.HttpRequest#newBuilder"));
          if (constructsRemoteUrlOrUri) {
              for (String lit : node.stringLiterals()) {
                  if (lit.startsWith("http://") || lit.startsWith("https://")) {
                      return "URL(" + lit + ")";
                  }
              }
              if (!hasLocalFileToUri && node.instantiatedClasses().contains("java.net.URL")) {
                  return "URL#<init>";
              }
          }
          return null;
      }

      private static boolean hasLocalFileUriConversion(MethodNode node) {
          for (String c : node.calledMethods()) {
              if (c.equals("java.io.File#toURI")
                      || c.equals("java.nio.file.Path#toUri")
                      || c.equals("java.io.File#toURL")) {
                  return true;
              }
          }
          return false;
      }

      private static String findDynamicClassLoaderSink(MethodNode node, String networkSourceStep) {
          for (String c : node.calledMethods()) {
              if (c.endsWith("ClassLoader#defineClass")
                      || c.endsWith("MethodHandles$Lookup#defineClass")
                      || c.endsWith("Instrumentation#appendToSystemClassLoaderSearch")
                      || c.endsWith("Instrumentation#redefineClasses")
                      || c.endsWith("Instrumentation#retransformClasses")) {
                  return simpleMethodRef(c);
              }
          }
          boolean usesUrlClassLoader = node.instantiatedClasses().contains("java.net.URLClassLoader")
                  || node.calledMethods().stream().anyMatch(c ->
                  c.equals("java.net.URLClassLoader#<init>")
                          || c.equals("java.net.URLClassLoader#newInstance"));
          if (usesUrlClassLoader) {
              boolean hasStreamDownload = networkSourceStep != null
                      && (networkSourceStep.contains("#open")
                      || networkSourceStep.contains("HttpClient#send")
                      || networkSourceStep.contains("Socket#")
                      || networkSourceStep.contains("Channels#newChannel")
                      || networkSourceStep.contains("HttpURLConnection#")
                      || networkSourceStep.contains("URLConnection#"));
              if (hasLocalFileUriConversion(node) && !hasStreamDownload) {
                  return null;
              }
              return "URLClassLoader#<init>";
          }
          return null;
      }

    // =========================================================================
    // CFG Operand-Stack & Local-Slot Array / Callback Dataflow Analyzer
    // =========================================================================

    private static final Map<String, String> JDK_FUNCTIONAL_SAM_METHODS = Map.ofEntries(
            Map.entry("java.lang.Runnable", "run"),
            Map.entry("java.util.concurrent.Callable", "call"),
            Map.entry("java.security.PrivilegedAction", "run"),
            Map.entry("java.security.PrivilegedExceptionAction", "run"),
            Map.entry("java.util.Comparator", "compare"),
            Map.entry("java.util.function.Supplier", "get"),
            Map.entry("java.util.function.BooleanSupplier", "getAsBoolean"),
            Map.entry("java.util.function.IntSupplier", "getAsInt"),
            Map.entry("java.util.function.LongSupplier", "getAsLong"),
            Map.entry("java.util.function.DoubleSupplier", "getAsDouble"),
            Map.entry("java.util.function.Consumer", "accept"),
            Map.entry("java.util.function.BiConsumer", "accept"),
            Map.entry("java.util.function.IntConsumer", "accept"),
            Map.entry("java.util.function.LongConsumer", "accept"),
            Map.entry("java.util.function.DoubleConsumer", "accept"),
            Map.entry("java.util.function.ObjIntConsumer", "accept"),
            Map.entry("java.util.function.ObjLongConsumer", "accept"),
            Map.entry("java.util.function.ObjDoubleConsumer", "accept"),
            Map.entry("java.util.function.Function", "apply"),
            Map.entry("java.util.function.BiFunction", "apply"),
            Map.entry("java.util.function.UnaryOperator", "apply"),
            Map.entry("java.util.function.BinaryOperator", "apply"),
            Map.entry("java.util.function.IntFunction", "apply"),
            Map.entry("java.util.function.LongFunction", "apply"),
            Map.entry("java.util.function.DoubleFunction", "apply"),
            Map.entry("java.util.function.ToIntFunction", "applyAsInt"),
            Map.entry("java.util.function.ToLongFunction", "applyAsLong"),
            Map.entry("java.util.function.ToDoubleFunction", "applyAsDouble"),
            Map.entry("java.util.function.ToIntBiFunction", "applyAsInt"),
            Map.entry("java.util.function.ToLongBiFunction", "applyAsLong"),
            Map.entry("java.util.function.ToDoubleBiFunction", "applyAsDouble"),
            Map.entry("java.util.function.IntUnaryOperator", "applyAsInt"),
            Map.entry("java.util.function.LongUnaryOperator", "applyAsLong"),
            Map.entry("java.util.function.DoubleUnaryOperator", "applyAsDouble"),
            Map.entry("java.util.function.IntBinaryOperator", "applyAsInt"),
            Map.entry("java.util.function.LongBinaryOperator", "applyAsLong"),
            Map.entry("java.util.function.DoubleBinaryOperator", "applyAsDouble"),
            Map.entry("java.util.function.IntToLongFunction", "applyAsLong"),
            Map.entry("java.util.function.IntToDoubleFunction", "applyAsDouble"),
            Map.entry("java.util.function.LongToIntFunction", "applyAsInt"),
            Map.entry("java.util.function.LongToDoubleFunction", "applyAsDouble"),
            Map.entry("java.util.function.DoubleToIntFunction", "applyAsInt"),
            Map.entry("java.util.function.DoubleToLongFunction", "applyAsLong"),
            Map.entry("java.util.function.Predicate", "test"),
            Map.entry("java.util.function.BiPredicate", "test"),
            Map.entry("java.util.function.IntPredicate", "test"),
            Map.entry("java.util.function.LongPredicate", "test"),
            Map.entry("java.util.function.DoublePredicate", "test")
    );

    static String samMethodForJdkFunctionalInterface(String binaryClassName) {
        return JDK_FUNCTIONAL_SAM_METHODS.get(binaryClassName);
    }

    private static String extractReturnObjectBinaryName(String methodDescriptor) {
        if (methodDescriptor == null) {
            return null;
        }
        int closeParen = methodDescriptor.indexOf(')');
        if (closeParen < 0 || closeParen + 2 >= methodDescriptor.length()) {
            return null;
        }
        String ret = methodDescriptor.substring(closeParen + 1);
        if (ret.startsWith("L") && ret.endsWith(";") && ret.length() > 2) {
            return ret.substring(1, ret.length() - 1).replace('/', '.');
        }
        return null;
    }

    private record StackDataflowSummary(
            Set<String> arrayMutatedFields,
            Set<String> receiverMutatedFields,
            Set<String> collectionAddedFields,
            Set<String> collectionRemovedFields,
            Set<String> collectionMutatedDirectFields,
            Set<String> dynamicFunctionalDispatches,
            Set<String> asyncFunctionalDispatches,
            String returnedField
    ) {
    }

    private record StackVal(
            int category,
            boolean isNullConst,
            boolean isFreshArray,
            boolean isFreshObject,
            boolean isInlineCallback,
            boolean isUnknownRef,
            Set<String> sources
    ) {
        static final StackVal OTHER_1 = new StackVal(1, false, false, false, false, false, Set.of());
        static final StackVal OTHER_2 = new StackVal(2, false, false, false, false, false, Set.of());
        static final StackVal NULL_1 = new StackVal(1, true, false, false, false, false, Set.of());
        static final StackVal NEW_ARRAY_1 = new StackVal(1, false, true, false, false, false, Set.of());
        static final StackVal NEW_OBJECT_1 = new StackVal(1, false, false, true, true, false, Set.of());
        static final StackVal INLINE_CB_1 = new StackVal(1, false, false, true, true, false, Set.of());
        static final StackVal UNKNOWN_1 = new StackVal(1, false, false, false, false, true, Set.of());

        static StackVal knownSource(String src) {
            return new StackVal(1, false, false, false, false, false, Set.of(src));
        }

        StackVal asArrayElementLoad() {
            if (isFreshArray) {
                return NEW_ARRAY_1;
            }
            if (!sources.isEmpty()) {
                return new StackVal(1, false, false, false, false, isUnknownRef, sources);
            }
            return UNKNOWN_1;
        }

        static StackVal merge(StackVal a, StackVal b) {
            if (a == null) {
                return b;
            }
            if (b == null || a.equals(b)) {
                return a;
            }
            if (a.isNullConst) {
                return b;
            }
            if (b.isNullConst) {
                return a;
            }
            int cat = Math.max(a.category, b.category);
            boolean freshArr = a.isFreshArray && b.isFreshArray;
            boolean freshObj = a.isFreshObject && b.isFreshObject;
            boolean inlineCb = a.isInlineCallback && b.isInlineCallback;
            Set<String> mergedSources;
            if (a.sources.isEmpty()) {
                mergedSources = b.sources;
            } else if (b.sources.isEmpty()) {
                mergedSources = a.sources;
            } else if (a.sources.equals(b.sources)) {
                mergedSources = a.sources;
            } else {
                Set<String> union = new LinkedHashSet<>(a.sources);
                union.addAll(b.sources);
                mergedSources = Collections.unmodifiableSet(union);
            }
            boolean aHasNonSourceRef = a.isUnknownRef
                    || (a.sources.isEmpty() && !a.isFreshArray && !a.isFreshObject && !a.isInlineCallback);
            boolean bHasNonSourceRef = b.isUnknownRef
                    || (b.sources.isEmpty() && !b.isFreshArray && !b.isFreshObject && !b.isInlineCallback);
            boolean unknown = a.isUnknownRef || b.isUnknownRef
                    || (!mergedSources.isEmpty() && (aHasNonSourceRef || bHasNonSourceRef))
                    || (a.isFreshArray != b.isFreshArray && mergedSources.isEmpty())
                    || (a.isFreshObject != b.isFreshObject && mergedSources.isEmpty());
            return new StackVal(cat, false, freshArr, freshObj, inlineCb, unknown, mergedSources);
        }
    }

    private static final class AbstractFrame {
        private final StackVal[] locals;
        private final List<StackVal> stack;
        private boolean underflowed;
        private boolean stackHeightMismatch;

        AbstractFrame(StackVal[] locals, List<StackVal> stack) {
            this.locals = locals;
            this.stack = stack;
        }

        AbstractFrame copy() {
            AbstractFrame dup = new AbstractFrame(locals.clone(), new ArrayList<>(stack));
            dup.underflowed = this.underflowed;
            dup.stackHeightMismatch = this.stackHeightMismatch;
            return dup;
        }

        void push(StackVal v) {
            stack.add(v != null ? v : StackVal.UNKNOWN_1);
        }

        StackVal pop() {
            if (stack.isEmpty()) {
                underflowed = true;
                return StackVal.UNKNOWN_1;
            }
            return stack.remove(stack.size() - 1);
        }

        StackVal getLocal(int slot) {
            if (slot < 0 || slot >= locals.length) {
                return StackVal.UNKNOWN_1;
            }
            StackVal v = locals[slot];
            return v != null ? v : StackVal.UNKNOWN_1;
        }

        void setLocal(int slot, StackVal v) {
            if (slot >= 0 && slot < locals.length) {
                locals[slot] = v;
                if (v != null && v.category == 2 && slot + 1 < locals.length) {
                    locals[slot + 1] = StackVal.OTHER_1;
                }
            }
        }

        boolean mergeFrom(AbstractFrame incoming) {
            boolean changed = false;
            int len = Math.min(this.locals.length, incoming.locals.length);
            for (int i = 0; i < len; i++) {
                StackVal merged = StackVal.merge(this.locals[i], incoming.locals[i]);
                if (!Objects.equals(this.locals[i], merged)) {
                    this.locals[i] = merged;
                    changed = true;
                }
            }
            if (this.stack.size() != incoming.stack.size()) {
                this.stackHeightMismatch = true;
                int min = Math.min(this.stack.size(), incoming.stack.size());
                for (int i = 0; i < min; i++) {
                    StackVal merged = StackVal.merge(this.stack.get(i), incoming.stack.get(i));
                    if (!Objects.equals(this.stack.get(i), merged)) {
                        this.stack.set(i, merged);
                        changed = true;
                    }
                }
                for (int i = this.stack.size(); i < incoming.stack.size(); i++) {
                    this.stack.add(incoming.stack.get(i));
                    changed = true;
                }
            } else {
                for (int i = 0; i < this.stack.size(); i++) {
                    StackVal merged = StackVal.merge(this.stack.get(i), incoming.stack.get(i));
                    if (!Objects.equals(this.stack.get(i), merged)) {
                        this.stack.set(i, merged);
                        changed = true;
                    }
                }
            }
            return changed;
        }
    }

    private static StackDataflowSummary analyzeStackArrayAndCallbackDataflow(
            String binaryClassName,
            RawMethodData rm,
            byte[] code,
            String[] utf8Pool,
            int[] classNameIdx,
            int[] fieldClassIdx,
            int[] fieldNatIdx,
            int[] methodClassIdx,
            int[] methodNatIdx,
            int[] natNameIdx,
            int[] natDescIdx,
            int[] indyBootstrapIdx,
            int[] indyNatIdx,
            Map<Integer, List<String>> bootstrapTargetMethods,
            Map<Integer, String> bootstrapFactoryOwner
    ) {
        String methodSym = binaryClassName + "#" + rm.name();
        Set<String> arrayMutated = new LinkedHashSet<>();
        Set<String> receiverMutated = new LinkedHashSet<>();
        Set<String> collAdded = new LinkedHashSet<>();
        Set<String> collRemoved = new LinkedHashSet<>();
        Set<String> collDirectWritten = new LinkedHashSet<>();
        Set<String> dynamicFnDispatches = new LinkedHashSet<>();
        Set<String> asyncFnDispatches = new LinkedHashSet<>();
        Set<String> returnedFieldSources = new LinkedHashSet<>();
        boolean[] sawOpaqueReturn = new boolean[]{false};
        Set<String> freshRefArrayEscapedSources = new LinkedHashSet<>();
        boolean[] freshRefArrayEscapedUnknown = new boolean[]{false};

        String abortReason = validateLinearCodeWellFormedness(code, indyBootstrapIdx);

        List<String> paramDescs = parseMethodParamDescriptors(rm.descriptor());
        int maxLocals = computeMaxLocalSlots(rm.accessFlags(), paramDescs, code);
        StackVal[] entryLocals = new StackVal[maxLocals];
        int slotCursor = 0;
        if ((rm.accessFlags() & ACC_STATIC) == 0 && slotCursor < maxLocals) {
            entryLocals[slotCursor++] = StackVal.UNKNOWN_1;
        }
        for (String pDesc : paramDescs) {
            boolean cat2 = "J".equals(pDesc) || "D".equals(pDesc);
            boolean ref = pDesc.startsWith("L") || pDesc.startsWith("[");
            if (slotCursor < maxLocals) {
                entryLocals[slotCursor] = ref ? StackVal.UNKNOWN_1 : (cat2 ? StackVal.OTHER_2 : StackVal.OTHER_1);
            }
            slotCursor += cat2 ? 2 : 1;
        }
        for (int i = slotCursor; i < maxLocals; i++) {
            entryLocals[i] = StackVal.OTHER_1;
        }

        AbstractFrame[] frames = new AbstractFrame[code.length];
        boolean[] queued = new boolean[code.length];
        Deque<Integer> worklist = new ArrayDeque<>();
        if (abortReason == null) {
            for (ExceptionEntry ex : rm.exceptionTable()) {
                if (ex.handlerPc() < 0 || ex.handlerPc() >= code.length) {
                    abortReason = "invalid-exception-handler-pc=" + ex.handlerPc();
                    break;
                }
            }
        }
        if (abortReason == null && code.length > 0) {
            frames[0] = new AbstractFrame(entryLocals, new ArrayList<>());
            queued[0] = true;
            worklist.add(0);
        }

        int stepBudget = Math.max(4096, code.length * 10);
        int steps = 0;
        int freshArrayResets = 0;
        boolean seededUnreachedHandlers = false;

        while (abortReason == null) {
            if (worklist.isEmpty()) {
                if (!seededUnreachedHandlers) {
                    seededUnreachedHandlers = true;
                    seedUnreachedExceptionHandlers(rm, code.length, entryLocals, frames, queued, worklist);
                    if (!worklist.isEmpty()) {
                        continue;
                    }
                }
                break;
            }
            int pc = worklist.poll();
            queued[pc] = false;
            if (++steps > stepBudget) {
                abortReason = "step-budget-exceeded(limit=" + stepBudget + ")";
                break;
            }
            int op = code[pc] & 0xFF;
            int insLen = instructionLength(code, pc);
            if (insLen <= 0 || pc + insLen > code.length) {
                abortReason = "malformed-or-truncated-instruction@pc=" + pc;
                break;
            }
            if (!propagateExceptionEdgesFromPc(pc, frames[pc], rm, code.length, frames, queued, worklist)) {
                abortReason = "invalid-exception-handler-merge@pc=" + pc;
                break;
            }
            AbstractFrame cur = frames[pc].copy();
            int nextPc = pc + insLen;
            boolean fallsThrough = true;

            switch (op) {
                case 0x00, 0x84 -> { // NOP, IINC
                }
                case 0x01 -> cur.push(StackVal.NULL_1);
                case 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                        0x0B, 0x0C, 0x0D, 0x10, 0x11, 0x12, 0x13,
                        0x15, 0x17, 0x1A, 0x1B, 0x1C, 0x1D,
                        0x22, 0x23, 0x24, 0x25 -> cur.push(StackVal.OTHER_1);
                case 0x09, 0x0A, 0x0E, 0x0F, 0x14,
                        0x16, 0x18, 0x1E, 0x1F, 0x20, 0x21,
                        0x26, 0x27, 0x28, 0x29 -> cur.push(StackVal.OTHER_2);
                case 0x19 -> cur.push(cur.getLocal(code[pc + 1] & 0xFF));
                case 0x2A, 0x2B, 0x2C, 0x2D -> cur.push(cur.getLocal(op - 0x2A));
                case 0x2E, 0x30, 0x33, 0x34, 0x35 -> { // IALOAD, FALOAD, BALOAD, CALOAD, SALOAD
                    cur.pop();
                    cur.pop();
                    cur.push(StackVal.OTHER_1);
                }
                case 0x2F, 0x31 -> { // LALOAD, DALOAD
                    cur.pop();
                    cur.pop();
                    cur.push(StackVal.OTHER_2);
                }
                case 0x32 -> { // AALOAD
                    cur.pop();
                    StackVal arr = cur.pop();
                    if (arr.isFreshArray
                            && (freshRefArrayEscapedUnknown[0] || !freshRefArrayEscapedSources.isEmpty())) {
                        cur.push(new StackVal(
                                1,
                                false,
                                false,
                                false,
                                false,
                                freshRefArrayEscapedUnknown[0],
                                Set.copyOf(freshRefArrayEscapedSources)
                        ));
                    } else {
                        cur.push(arr.asArrayElementLoad());
                    }
                }
                case 0x36, 0x38 -> cur.setLocal(code[pc + 1] & 0xFF, popAndReturn(cur, StackVal.OTHER_1));
                case 0x3B, 0x3C, 0x3D, 0x3E -> cur.setLocal(op - 0x3B, popAndReturn(cur, StackVal.OTHER_1));
                case 0x43, 0x44, 0x45, 0x46 -> cur.setLocal(op - 0x43, popAndReturn(cur, StackVal.OTHER_1));
                case 0x37, 0x39 -> cur.setLocal(code[pc + 1] & 0xFF, popAndReturn(cur, StackVal.OTHER_2));
                case 0x3F, 0x40, 0x41, 0x42 -> cur.setLocal(op - 0x3F, popAndReturn(cur, StackVal.OTHER_2));
                case 0x47, 0x48, 0x49, 0x4A -> cur.setLocal(op - 0x47, popAndReturn(cur, StackVal.OTHER_2));
                case 0x3A -> cur.setLocal(code[pc + 1] & 0xFF, cur.pop());
                case 0x4B, 0x4C, 0x4D, 0x4E -> cur.setLocal(op - 0x4B, cur.pop());
                case 0x4F, 0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56 -> { // IASTORE..SASTORE
                    StackVal val = cur.pop(); // value
                    cur.pop(); // index
                    StackVal arr = cur.pop(); // arrayref
                    if (op == 0x53 && arr.isFreshArray && !val.isNullConst && !val.isFreshArray) {
                        boolean added = freshRefArrayEscapedSources.addAll(val.sources);
                        if ((val.isUnknownRef || val.sources.isEmpty()) && !freshRefArrayEscapedUnknown[0]) {
                            freshRefArrayEscapedUnknown[0] = true;
                            added = true;
                        }
                        if (added && !queued[0]) {
                            if (++freshArrayResets > 8) {
                                abortReason = "excessive-fresh-array-escape-resets";
                                break;
                            }
                            // Re-propagate AALOAD consumers of fresh reference arrays and exception edges
                            for (int resetPc = 1; resetPc < frames.length; resetPc++) {
                                frames[resetPc] = null;
                                queued[resetPc] = false;
                            }
                            worklist.clear();
                            seededUnreachedHandlers = false;
                            queued[0] = true;
                            worklist.add(0);
                            fallsThrough = false;
                        }
                    }
                    recordArrayMutationFromSlot(arr, methodSym, arrayMutated);
                }
                case 0x57 -> cur.pop(); // POP
                case 0x58 -> { // POP2
                    StackVal v1 = cur.pop();
                    if (v1.category == 1) {
                        cur.pop();
                    }
                }
                case 0x59 -> { // DUP
                    StackVal v1 = cur.pop();
                    cur.push(v1);
                    cur.push(v1);
                }
                case 0x5A -> { // DUP_X1
                    StackVal v1 = cur.pop();
                    StackVal v2 = cur.pop();
                    cur.push(v1);
                    cur.push(v2);
                    cur.push(v1);
                }
                case 0x5B -> { // DUP_X2
                    StackVal v1 = cur.pop();
                    StackVal v2 = cur.pop();
                    if (v2.category == 2) {
                        cur.push(v1);
                        cur.push(v2);
                        cur.push(v1);
                    } else {
                        StackVal v3 = cur.pop();
                        cur.push(v1);
                        cur.push(v3);
                        cur.push(v2);
                        cur.push(v1);
                    }
                }
                case 0x5C -> { // DUP2
                    StackVal v1 = cur.pop();
                    if (v1.category == 2) {
                        cur.push(v1);
                        cur.push(v1);
                    } else {
                        StackVal v2 = cur.pop();
                        cur.push(v2);
                        cur.push(v1);
                        cur.push(v2);
                        cur.push(v1);
                    }
                }
                case 0x5D -> { // DUP2_X1
                    StackVal v1 = cur.pop();
                    if (v1.category == 2) {
                        StackVal v2 = cur.pop();
                        cur.push(v1);
                        cur.push(v2);
                        cur.push(v1);
                    } else {
                        StackVal v2 = cur.pop();
                        StackVal v3 = cur.pop();
                        cur.push(v2);
                        cur.push(v1);
                        cur.push(v3);
                        cur.push(v2);
                        cur.push(v1);
                    }
                }
                case 0x5E -> { // DUP2_X2
                    StackVal v1 = cur.pop();
                    if (v1.category == 2) {
                        StackVal v2 = cur.pop();
                        if (v2.category == 2) {
                            cur.push(v1);
                            cur.push(v2);
                            cur.push(v1);
                        } else {
                            StackVal v3 = cur.pop();
                            cur.push(v1);
                            cur.push(v3);
                            cur.push(v2);
                            cur.push(v1);
                        }
                    } else {
                        StackVal v2 = cur.pop();
                        StackVal v3 = cur.pop();
                        if (v3.category == 2) {
                            cur.push(v2);
                            cur.push(v1);
                            cur.push(v3);
                            cur.push(v2);
                            cur.push(v1);
                        } else {
                            StackVal v4 = cur.pop();
                            cur.push(v2);
                            cur.push(v1);
                            cur.push(v4);
                            cur.push(v3);
                            cur.push(v2);
                            cur.push(v1);
                        }
                    }
                }
                case 0x5F -> { // SWAP
                    StackVal v1 = cur.pop();
                    StackVal v2 = cur.pop();
                    cur.push(v1);
                    cur.push(v2);
                }
                case 0x60, 0x62, 0x64, 0x66, 0x68, 0x6A, 0x6C, 0x6E, 0x70, 0x72,
                        0x78, 0x7A, 0x7C, 0x7E, 0x80, 0x82,
                        0x94, 0x95, 0x96, 0x97, 0x98 -> {
                    cur.pop();
                    cur.pop();
                    cur.push(StackVal.OTHER_1);
                }
                case 0x61, 0x63, 0x65, 0x67, 0x69, 0x6B, 0x6D, 0x6F, 0x71, 0x73,
                        0x79, 0x7B, 0x7D, 0x7F, 0x81, 0x83 -> {
                    cur.pop();
                    cur.pop();
                    cur.push(StackVal.OTHER_2);
                }
                case 0x74, 0x76, 0x86, 0x88, 0x89, 0x8B, 0x8E, 0x90, 0x91, 0x92, 0x93 -> {
                    cur.pop();
                    cur.push(StackVal.OTHER_1);
                }
                case 0x75, 0x77, 0x85, 0x87, 0x8A, 0x8C, 0x8D, 0x8F -> {
                    cur.pop();
                    cur.push(StackVal.OTHER_2);
                }
                case 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0xC6, 0xC7 -> { // IFEQ..IFLE, IFNULL, IFNONNULL
                    cur.pop();
                    short rel = (short) (((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF));
                    if (!propagateFrameToTarget(pc + rel, cur, code.length, frames, queued, worklist)) {
                        abortReason = "invalid-branch-or-ambiguous-stack@pc=" + pc;
                    }
                }
                case 0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6 -> { // IF_ICMP*, IF_ACMP*
                    cur.pop();
                    cur.pop();
                    short rel = (short) (((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF));
                    if (!propagateFrameToTarget(pc + rel, cur, code.length, frames, queued, worklist)) {
                        abortReason = "invalid-branch-or-ambiguous-stack@pc=" + pc;
                    }
                }
                case 0xA7 -> { // GOTO
                    fallsThrough = false;
                    short rel = (short) (((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF));
                    if (!propagateFrameToTarget(pc + rel, cur, code.length, frames, queued, worklist)) {
                        abortReason = "invalid-goto-or-ambiguous-stack@pc=" + pc;
                    }
                }
                case 0xC8 -> { // GOTO_W
                    fallsThrough = false;
                    int rel = readInt(code, pc + 1);
                    if (!propagateFrameToTarget(pc + rel, cur, code.length, frames, queued, worklist)) {
                        abortReason = "invalid-gotow-or-ambiguous-stack@pc=" + pc;
                    }
                }
                case 0xA8, 0xA9, 0xC9 -> { // Legacy pre-Java-7 JSR / RET / JSR_W
                    fallsThrough = false;
                    abortReason = "unsupported-legacy-jsr-ret-opcode:0x" + Integer.toHexString(op);
                }
                case 0xB1 -> fallsThrough = false; // RETURN
                case 0xAC, 0xAD, 0xAE, 0xAF, 0xBF -> { // IRETURN..DRETURN, ATHROW
                    cur.pop();
                    fallsThrough = false;
                }
                case 0xB0 -> { // ARETURN
                    StackVal ret = cur.pop();
                    fallsThrough = false;
                    if (!ret.isNullConst) {
                        if (!ret.sources.isEmpty() && !ret.isUnknownRef && !ret.isFreshArray) {
                            returnedFieldSources.addAll(ret.sources);
                        } else {
                            sawOpaqueReturn[0] = true;
                        }
                    }
                }
                case 0xAA -> { // TABLESWITCH
                    cur.pop();
                    fallsThrough = false;
                    int aligned = (pc + 4) & ~3;
                    int defOff = readInt(code, aligned);
                    int low = readInt(code, aligned + 4);
                    int high = readInt(code, aligned + 8);
                    if (!propagateFrameToTarget(pc + defOff, cur, code.length, frames, queued, worklist)) {
                        abortReason = "invalid-tableswitch-default@pc=" + pc;
                        break;
                    }
                    int count = high - low + 1;
                    for (int idx = 0; idx < count; idx++) {
                        int caseOff = readInt(code, aligned + 12 + idx * 4);
                        if (!propagateFrameToTarget(pc + caseOff, cur, code.length, frames, queued, worklist)) {
                            abortReason = "invalid-tableswitch-target@pc=" + pc;
                            break;
                        }
                    }
                }
                case 0xAB -> { // LOOKUPSWITCH
                    cur.pop();
                    fallsThrough = false;
                    int aligned = (pc + 4) & ~3;
                    int defOff = readInt(code, aligned);
                    int npairs = readInt(code, aligned + 4);
                    if (!propagateFrameToTarget(pc + defOff, cur, code.length, frames, queued, worklist)) {
                        abortReason = "invalid-lookupswitch-default@pc=" + pc;
                        break;
                    }
                    for (int idx = 0; idx < npairs; idx++) {
                        int caseOff = readInt(code, aligned + 12 + idx * 8);
                        if (!propagateFrameToTarget(pc + caseOff, cur, code.length, frames, queued, worklist)) {
                            abortReason = "invalid-lookupswitch-target@pc=" + pc;
                            break;
                        }
                    }
                }
                case 0xB2, 0xB4 -> { // GETSTATIC, GETFIELD
                    if (op == 0xB4) {
                        cur.pop(); // receiver
                    }
                    int fCpIdx = readUnsignedShort(code, pc + 1);
                    String fOwner = (fCpIdx > 0 && fCpIdx < fieldClassIdx.length)
                            ? resolveClassName(fieldClassIdx[fCpIdx], classNameIdx, utf8Pool)
                            : null;
                    String fName = resolveFieldName(fCpIdx, fieldNatIdx, natNameIdx, utf8Pool);
                    String fDesc = resolveMethodDesc(fCpIdx, fieldNatIdx, natDescIdx, utf8Pool);
                    cur.push(classifyLoadedFieldStackVal(fOwner, fName, fDesc));
                }
                case 0xB3 -> cur.pop(); // PUTSTATIC
                case 0xB5 -> { // PUTFIELD
                    cur.pop(); // value
                    cur.pop(); // receiver
                }
                case 0xB6, 0xB7, 0xB8, 0xB9 -> { // INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC, INVOKEINTERFACE
                    int mCpIdx = readUnsignedShort(code, pc + 1);
                    String mOwnerInternal = (mCpIdx > 0 && mCpIdx < methodClassIdx.length)
                            ? resolveClassName(methodClassIdx[mCpIdx], classNameIdx, utf8Pool)
                            : null;
                    String mName = resolveMethodName(mCpIdx, methodNatIdx, natNameIdx, utf8Pool);
                    String mDesc = resolveMethodDesc(mCpIdx, methodNatIdx, natDescIdx, utf8Pool);
                    if (mDesc == null || !mDesc.startsWith("(") || mDesc.indexOf(')') < 0) {
                        abortReason = "malformed-invoke-descriptor@pc=" + pc;
                        break;
                    }
                    List<String> callArgDescs = parseMethodParamDescriptors(mDesc);
                    StackVal[] callArgs = new StackVal[callArgDescs.size()];
                    for (int a = callArgDescs.size() - 1; a >= 0; a--) {
                        callArgs[a] = cur.pop();
                    }
                    StackVal receiver = (op != 0xB8) ? cur.pop() : null;
                    inspectStackInvocationEffects(
                            methodSym,
                            mOwnerInternal,
                            mName,
                            mDesc,
                            receiver,
                            callArgDescs,
                            callArgs,
                            arrayMutated,
                            receiverMutated,
                            collAdded,
                            collRemoved,
                            collDirectWritten,
                            dynamicFnDispatches,
                            asyncFnDispatches
                    );
                    StackVal retVal = classifyMethodReturnStackVal(mOwnerInternal, mName, mDesc, receiver, callArgs);
                    if (retVal != null) {
                        cur.push(retVal);
                    }
                }
                case 0xBA -> { // INVOKEDYNAMIC
                    int indyCpIdx = readUnsignedShort(code, pc + 1);
                    int natIdx = (indyCpIdx > 0 && indyCpIdx < indyNatIdx.length) ? indyNatIdx[indyCpIdx] : 0;
                    String indyDesc = (natIdx > 0 && natIdx < natDescIdx.length)
                            ? safeUtf8(utf8Pool, natDescIdx[natIdx])
                            : null;
                    if (indyDesc == null || !indyDesc.startsWith("(") || indyDesc.indexOf(')') < 0) {
                        abortReason = "malformed-invokedynamic-descriptor@pc=" + pc;
                        break;
                    }
                    List<String> indyArgs = parseMethodParamDescriptors(indyDesc);
                    for (int a = 0; a < indyArgs.size(); a++) {
                        cur.pop();
                    }
                    int bsmIdx = (indyCpIdx > 0 && indyCpIdx < indyBootstrapIdx.length)
                            ? indyBootstrapIdx[indyCpIdx]
                            : -1;
                    boolean hasLambdaTarget = bsmIdx >= 0
                            && !bootstrapTargetMethods.getOrDefault(bsmIdx, List.of()).isEmpty();
                    if (!hasLambdaTarget) {
                        String bsmOwner = bsmIdx >= 0 ? bootstrapFactoryOwner.get(bsmIdx) : null;
                        if (!isPureJdkCompilerBootstrapFactory(bsmOwner)) {
                            dynamicFnDispatches.add("UNKNOWN_INDY:" + methodSym);
                        }
                    }
                    String retPart = indyDesc.substring(indyDesc.indexOf(')') + 1);
                    if (!"V".equals(retPart)) {
                        if ("J".equals(retPart) || "D".equals(retPart)) {
                            cur.push(StackVal.OTHER_2);
                        } else if (hasLambdaTarget) {
                            cur.push(StackVal.INLINE_CB_1);
                        } else if (retPart.startsWith("L") || retPart.startsWith("[")) {
                            cur.push(StackVal.UNKNOWN_1);
                        } else {
                            cur.push(StackVal.OTHER_1);
                        }
                    }
                }
                case 0xBB -> cur.push(StackVal.NEW_OBJECT_1); // NEW
                case 0xBC, 0xBD -> { // NEWARRAY, ANEWARRAY
                    cur.pop();
                    cur.push(StackVal.NEW_ARRAY_1);
                }
                case 0xBE, 0xC1 -> { // ARRAYLENGTH, INSTANCEOF
                    cur.pop();
                    cur.push(StackVal.OTHER_1);
                }
                case 0xC0 -> { // CHECKCAST preserves underlying origin
                    StackVal castTarget = cur.pop();
                    cur.push(castTarget);
                }
                case 0xC2, 0xC3 -> cur.pop(); // MONITORENTER, MONITOREXIT
                case 0xC4 -> { // WIDE
                    int subOp = code[pc + 1] & 0xFF;
                    int wideSlot = readUnsignedShort(code, pc + 2);
                    switch (subOp) {
                        case 0x19 -> cur.push(cur.getLocal(wideSlot));
                        case 0x15, 0x17 -> cur.push(StackVal.OTHER_1);
                        case 0x16, 0x18 -> cur.push(StackVal.OTHER_2);
                        case 0x3A -> cur.setLocal(wideSlot, cur.pop());
                        case 0x36, 0x38 -> cur.setLocal(wideSlot, popAndReturn(cur, StackVal.OTHER_1));
                        case 0x37, 0x39 -> cur.setLocal(wideSlot, popAndReturn(cur, StackVal.OTHER_2));
                        case 0x84 -> { // WIDE IINC
                        }
                        default -> abortReason = "unsupported-wide-subopcode:0x" + Integer.toHexString(subOp);
                    }
                }
                case 0xC5 -> { // MULTIANEWARRAY
                    int dims = code[pc + 3] & 0xFF;
                    for (int d = 0; d < dims; d++) {
                        cur.pop();
                    }
                    cur.push(StackVal.NEW_ARRAY_1);
                }
                default -> abortReason = "unsupported-opcode:0x" + Integer.toHexString(op) + "@pc=" + pc;
            }

            if (cur.underflowed) {
                abortReason = "operand-stack-underflow@pc=" + pc;
                break;
            }

            if (abortReason == null && fallsThrough && nextPc < code.length) {
                if (!propagateFrameToTarget(nextPc, cur, code.length, frames, queued, worklist)) {
                    abortReason = "invalid-fallthrough-or-ambiguous-stack@pc=" + nextPc;
                    break;
                }
            }
        }

        if (abortReason != null) {
            String warnKey = methodSym + "|" + abortReason;
            if (LOGGED_ABORT_WARNINGS.size() < MAX_LOGGED_ABORT_WARNINGS && LOGGED_ABORT_WARNINGS.add(warnKey)) {
                LOGGER.log(
                        Level.WARNING,
                        "[BytecodeCausalGraphScanner] Aborting stack/callback dataflow in "
                                + methodSym + " (" + abortReason
                                + "); emitting fail-closed field and callback markers."
                );
            } else if (LOGGED_ABORT_WARNINGS.size() >= MAX_LOGGED_ABORT_WARNINGS
                    && ABORT_WARNING_CAP_LOGGED.compareAndSet(false, true)) {
                LOGGER.log(
                        Level.WARNING,
                        "[BytecodeCausalGraphScanner] Further stack/callback dataflow abort warnings suppressed after "
                                + MAX_LOGGED_ABORT_WARNINGS
                                + " unique entries (fail-closed markers remain active)."
                );
            }
            String fieldMarker = "UNKNOWN_ARRAY:" + methodSym;
            String callbackMarker = "UNRESOLVED_CALLBACK:" + methodSym;
            arrayMutated.add(fieldMarker);
            dynamicFnDispatches.add(callbackMarker);
            asyncFnDispatches.add(callbackMarker);
            sawOpaqueReturn[0] = true;
        }

        String resolvedReturnedField = (!sawOpaqueReturn[0] && returnedFieldSources.size() == 1)
                ? returnedFieldSources.iterator().next()
                : "";
        if (resolvedReturnedField.startsWith("GETTER:")) {
            resolvedReturnedField = "";
        }

        return new StackDataflowSummary(
                arrayMutated,
                receiverMutated,
                collAdded,
                collRemoved,
                collDirectWritten,
                dynamicFnDispatches,
                asyncFnDispatches,
                resolvedReturnedField
        );
    }

    private static boolean propagateExceptionEdgesFromPc(
            int pc,
            AbstractFrame preInstructionFrame,
            RawMethodData rm,
            int codeLength,
            AbstractFrame[] frames,
            boolean[] queued,
            Deque<Integer> worklist
    ) {
        if (rm.exceptionTable().isEmpty() || preInstructionFrame == null) {
            return true;
        }
        AbstractFrame handlerIncoming = null;
        for (ExceptionEntry ex : rm.exceptionTable()) {
            if (pc >= ex.startPc() && pc < ex.endPc()) {
                if (handlerIncoming == null) {
                    List<StackVal> exStack = new ArrayList<>(1);
                    exStack.add(StackVal.UNKNOWN_1);
                    handlerIncoming = new AbstractFrame(preInstructionFrame.locals.clone(), exStack);
                }
                if (!propagateFrameToTarget(ex.handlerPc(), handlerIncoming, codeLength, frames, queued, worklist)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void seedUnreachedExceptionHandlers(
            RawMethodData rm,
            int codeLength,
            StackVal[] entryLocals,
            AbstractFrame[] frames,
            boolean[] queued,
            Deque<Integer> worklist
    ) {
        for (ExceptionEntry ex : rm.exceptionTable()) {
            int hPc = ex.handlerPc();
            if (hPc >= 0 && hPc < codeLength && frames[hPc] == null) {
                StackVal[] handlerLocals = entryLocals.clone();
                for (int i = 0; i < handlerLocals.length; i++) {
                    handlerLocals[i] = StackVal.UNKNOWN_1;
                }
                List<StackVal> handlerStack = new ArrayList<>(1);
                handlerStack.add(StackVal.UNKNOWN_1);
                frames[hPc] = new AbstractFrame(handlerLocals, handlerStack);
                queued[hPc] = true;
                worklist.add(hPc);
            }
        }
    }

    private static String validateLinearCodeWellFormedness(byte[] code, int[] indyBootstrapIdx) {
        int pc = 0;
        while (pc < code.length) {
            int op = code[pc] & 0xFF;
            int insLen = instructionLength(code, pc);
            if (insLen <= 0 || pc + insLen > code.length) {
                return "malformed-or-truncated-instruction@pc=" + pc;
            }
            if (op >= 0xCA || op == 0xA8 || op == 0xA9 || op == 0xC9) {
                return "unsupported-opcode:0x" + Integer.toHexString(op) + "@pc=" + pc;
            }
            if (op == 0xC4) {
                int subOp = code[pc + 1] & 0xFF;
                if (!((subOp >= 0x15 && subOp <= 0x19) || (subOp >= 0x36 && subOp <= 0x3A) || subOp == 0x84)) {
                    return "unsupported-wide-subopcode:0x" + Integer.toHexString(subOp) + "@pc=" + pc;
                }
            } else if (op == 0xBA) {
                int indyCpIdx = readUnsignedShort(code, pc + 1);
                if (indyCpIdx <= 0 || indyCpIdx >= indyBootstrapIdx.length) {
                    return "invalid-invokedynamic-cp-index@pc=" + pc;
                }
            }
            pc += insLen;
        }
        return null;
    }

    private static boolean isPureJdkCompilerBootstrapFactory(String bsmOwnerInternal) {
        return "java/lang/invoke/StringConcatFactory".equals(bsmOwnerInternal)
                || "java/lang/runtime/ObjectMethods".equals(bsmOwnerInternal)
                || "java/lang/runtime/SwitchBootstraps".equals(bsmOwnerInternal);
    }

    private static StackVal popAndReturn(AbstractFrame cur, StackVal replacement) {
        cur.pop();
        return replacement;
    }

    private static boolean propagateFrameToTarget(
            int targetPc,
            AbstractFrame outgoing,
            int codeLength,
            AbstractFrame[] frames,
            boolean[] queued,
            Deque<Integer> worklist
    ) {
        if (targetPc < 0 || targetPc >= codeLength) {
            return false;
        }
        AbstractFrame existing = frames[targetPc];
        if (existing == null) {
            frames[targetPc] = outgoing.copy();
            if (!queued[targetPc]) {
                queued[targetPc] = true;
                worklist.add(targetPc);
            }
            return true;
        }
        boolean changed = existing.mergeFrom(outgoing);
        if (existing.stackHeightMismatch) {
            return false;
        }
        if (changed && !queued[targetPc]) {
            queued[targetPc] = true;
            worklist.add(targetPc);
        }
        return true;
    }

    private static void recordArrayMutationFromSlot(
            StackVal arrSlot,
            String methodSym,
            Set<String> arrayMutatedOut
    ) {
        if (arrSlot == null) {
            arrayMutatedOut.add("UNKNOWN_ARRAY:" + methodSym);
            return;
        }
        if (arrSlot.isNullConst || (arrSlot.isFreshArray && arrSlot.sources.isEmpty() && !arrSlot.isUnknownRef)) {
            return;
        }
        arrayMutatedOut.addAll(arrSlot.sources);
        if (arrSlot.isUnknownRef || arrSlot.sources.isEmpty()) {
            arrayMutatedOut.add("UNKNOWN_ARRAY:" + methodSym);
        }
    }

    private static void recordReceiverContainerMutation(
            StackVal receiverSlot,
            String methodSym,
            Set<String> receiverMutatedOut
    ) {
        if (receiverSlot == null) {
            receiverMutatedOut.add("UNKNOWN_MUTATION:" + methodSym);
            return;
        }
        if (receiverSlot.isNullConst
                || ((receiverSlot.isFreshObject || receiverSlot.isFreshArray)
                && receiverSlot.sources.isEmpty()
                && !receiverSlot.isUnknownRef)) {
            return;
        }
        receiverMutatedOut.addAll(receiverSlot.sources);
        if (receiverSlot.isUnknownRef || receiverSlot.sources.isEmpty()) {
            receiverMutatedOut.add("UNKNOWN_MUTATION:" + methodSym);
        }
    }

    private static StackVal classifyLoadedFieldStackVal(String fOwnerInternal, String fName, String fDesc) {
        if (fDesc == null || fDesc.isEmpty()) {
            return StackVal.UNKNOWN_1;
        }
        if ("J".equals(fDesc) || "D".equals(fDesc)) {
            return StackVal.OTHER_2;
        }
        if (fOwnerInternal != null && fName != null && !fName.startsWith("$")) {
            if ((fDesc.startsWith("[") || fDesc.startsWith("L")) && !isDeeplyImmutableJdkValueDescriptor(fDesc)) {
                return StackVal.knownSource(fOwnerInternal.replace('/', '.') + "#" + fName);
            }
        }
        if (fDesc.startsWith("L") || fDesc.startsWith("[")) {
            return isDeeplyImmutableJdkValueDescriptor(fDesc) ? StackVal.OTHER_1 : StackVal.UNKNOWN_1;
        }
        return StackVal.OTHER_1;
    }

    private static boolean isDeeplyImmutableJdkValueDescriptor(String desc) {
        return "Ljava/lang/String;".equals(desc)
                || "Ljava/lang/Integer;".equals(desc)
                || "Ljava/lang/Long;".equals(desc)
                || "Ljava/lang/Double;".equals(desc)
                || "Ljava/lang/Float;".equals(desc)
                || "Ljava/lang/Short;".equals(desc)
                || "Ljava/lang/Byte;".equals(desc)
                || "Ljava/lang/Boolean;".equals(desc)
                || "Ljava/lang/Character;".equals(desc)
                || "Ljava/lang/Class;".equals(desc);
    }

    private static final Set<String> READ_ONLY_ARRAYS_METHODS = Set.of(
            "copyOf", "copyOfRange",
            "equals", "deepEquals",
            "hashCode", "deepHashCode",
            "toString", "deepToString",
            "binarySearch", "compare", "compareUnsigned", "mismatch",
            "stream", "spliterator"
    );

    private static final Set<String> READ_ONLY_COLLECTION_AND_HOLDER_METHODS = Set.of(
            "getClass", "hashCode", "equals", "toString", "clone",
            // Atomic* / Adder / Accumulator read-only queries
            "get", "getOpaque", "getAcquire", "getPlain", "getReference", "getStamp", "isMarked",
            "length", "sum", "intValue", "longValue", "floatValue", "doubleValue", "byteValue", "shortValue",
            // BitSet read-only queries
            "size", "cardinality", "isEmpty", "nextSetBit", "nextClearBit",
            "previousSetBit", "previousClearBit", "intersects", "toByteArray", "toLongArray",
            // Collection / Map / Queue / Iterator read-only queries & view accessors
            "getOrDefault", "getFirst", "getLast", "getProperty", "propertyNames", "stringPropertyNames",
            "elementAt", "firstElement", "lastElement", "peek", "peekFirst", "peekLast", "element",
            "contains", "containsKey", "containsValue", "containsAll", "indexOf", "lastIndexOf",
            "iterator", "listIterator", "spliterator", "stream", "parallelStream", "elements", "keys",
            "keySet", "values", "entrySet", "subList", "reversed",
            "navigableKeySet", "descendingKeySet", "descendingMap", "descendingSet",
            "headMap", "tailMap", "subMap", "headSet", "tailSet", "comparator",
            "first", "last", "lower", "floor", "ceiling", "higher",
            "lowerEntry", "floorEntry", "ceilingEntry", "higherEntry", "firstEntry", "lastEntry",
            "lowerKey", "floorKey", "ceilingKey", "higherKey", "firstKey", "lastKey",
            "toArray", "forEach", "forEachRemaining",
            "hasNext", "hasPrevious", "next", "previous", "nextIndex", "previousIndex", "hasMoreElements", "nextElement",
            // ThreadLocal read-only
            "withInitial"
    );

    private static final Set<String> COMPLETABLE_FUTURE_MUTATOR_METHODS = Set.of(
            "complete", "completeAsync", "completeExceptionally", "completeOnTimeout",
            "orTimeout", "obtrudeValue", "obtrudeException", "cancel"
    );

    private static boolean isJdkOrSkippedInfrastructureOwner(String ownerInternal) {
        return ownerInternal.startsWith("java/")
                || ownerInternal.startsWith("javax/")
                || ownerInternal.startsWith("jdk/")
                || ownerInternal.startsWith("sun/")
                || ownerInternal.startsWith("com/sun/")
                || ownerInternal.startsWith("org/slf4j/")
                || ownerInternal.startsWith("org/apache/logging/");
    }

    private static boolean isKnownReadOnlyJdkArrayCall(String ownerInternal, String methodName) {
        if ("java/util/Arrays".equals(ownerInternal)) {
            return READ_ONLY_ARRAYS_METHODS.contains(methodName);
        }
        if ("java/util/Objects".equals(ownerInternal)) {
            return "equals".equals(methodName)
                    || "deepEquals".equals(methodName)
                    || "hashCode".equals(methodName)
                    || "hash".equals(methodName)
                    || "toString".equals(methodName)
                    || "requireNonNull".equals(methodName)
                    || "isNull".equals(methodName)
                    || "nonNull".equals(methodName);
        }
        if ("java/lang/String".equals(ownerInternal)) {
            return "<init>".equals(methodName)
                    || "valueOf".equals(methodName)
                    || "copyValueOf".equals(methodName)
                    || "format".equals(methodName)
                    || "join".equals(methodName);
        }
        if ("java/util/List".equals(ownerInternal) || "java/util/Set".equals(ownerInternal)) {
            return "of".equals(methodName) || "copyOf".equals(methodName);
        }
        if (ownerInternal.startsWith("java/util/stream/")) {
            return "of".equals(methodName) || "concat".equals(methodName);
        }
        if (ownerInternal.startsWith("org/slf4j/") || ownerInternal.startsWith("java/util/logging/")
                || ownerInternal.startsWith("org/apache/logging/")) {
            return true;
        }
        return false;
    }

    private static boolean isJdkCollectionOrMapOwner(String ownerInternal) {
        return ownerInternal.contains("Collection")
                || ownerInternal.contains("List")
                || ownerInternal.contains("Set")
                || ownerInternal.contains("Map")
                || ownerInternal.contains("Queue")
                || ownerInternal.contains("Deque")
                || "java/util/Vector".equals(ownerInternal)
                || "java/util/Stack".equals(ownerInternal)
                || "java/util/Hashtable".equals(ownerInternal)
                || "java/util/Dictionary".equals(ownerInternal)
                || "java/util/Properties".equals(ownerInternal)
                || "java/util/Iterator".equals(ownerInternal)
                || "java/util/ListIterator".equals(ownerInternal);
    }

    static boolean isPotentiallyMutatingJdkMethod(String ownerBinary, String methodName) {
        if (ownerBinary == null || methodName == null) {
            return false;
        }
        String ownerInternal = ownerBinary.replace('.', '/');
        if ("java/util/Arrays".equals(ownerInternal)) {
            return !READ_ONLY_ARRAYS_METHODS.contains(methodName);
        }
        if ("java/util/Collections".equals(ownerInternal)) {
            return "fill".equals(methodName)
                    || "addAll".equals(methodName)
                    || "replaceAll".equals(methodName)
                    || "sort".equals(methodName)
                    || "reverse".equals(methodName)
                    || "shuffle".equals(methodName)
                    || "swap".equals(methodName)
                    || "copy".equals(methodName);
        }
        if ("java/lang/System".equals(ownerInternal)) {
            return "arraycopy".equals(methodName)
                    || "exit".equals(methodName)
                    || "setProperty".equals(methodName)
                    || "clearProperty".equals(methodName);
        }
        if ("java/lang/Runtime".equals(ownerInternal)) {
            return "exec".equals(methodName) || "exit".equals(methodName) || "halt".equals(methodName);
        }
        if (ownerInternal.startsWith("java/nio/") && ownerInternal.endsWith("Buffer") && "wrap".equals(methodName)) {
            return true;
        }
        return isMutableJdkHolderOrContainerOwner(ownerInternal)
                && !isKnownReadOnlyJdkReceiverMethod(ownerInternal, methodName, null);
    }

    private static boolean isMutableJdkHolderOrContainerOwner(String ownerInternal) {
        if (ownerInternal.startsWith("java/util/concurrent/atomic/")) {
            return !ownerInternal.endsWith("FieldUpdater");
        }
        if ("java/util/BitSet".equals(ownerInternal)
                || "java/lang/ThreadLocal".equals(ownerInternal)
                || "java/lang/InheritableThreadLocal".equals(ownerInternal)
                || "java/lang/StringBuilder".equals(ownerInternal)
                || "java/lang/StringBuffer".equals(ownerInternal)
                || "java/util/concurrent/CompletableFuture".equals(ownerInternal)) {
            return true;
        }
        if (ownerInternal.startsWith("java/nio/") && ownerInternal.endsWith("Buffer")) {
            return true;
        }
        return isJdkCollectionOrMapOwner(ownerInternal);
    }

    private static boolean isKnownReadOnlyJdkReceiverMethod(String ownerInternal, String methodName, String methodDesc) {
        if ("java/util/concurrent/CompletableFuture".equals(ownerInternal)) {
            return !COMPLETABLE_FUTURE_MUTATOR_METHODS.contains(methodName);
        }
        if (ownerInternal.startsWith("java/nio/") && ownerInternal.endsWith("Buffer")) {
            if ("get".equals(methodName)) {
                // Absolute indexed get(int) is read-only; relative get() / get(byte[]) advances position
                return "(I)B".equals(methodDesc)
                        || "(I)C".equals(methodDesc)
                        || "(I)S".equals(methodDesc)
                        || "(I)I".equals(methodDesc)
                        || "(I)J".equals(methodDesc)
                        || "(I)F".equals(methodDesc)
                        || "(I)D".equals(methodDesc);
            }
            if (methodName.startsWith("get") && methodDesc != null && methodDesc.startsWith("(I)")) {
                return true;
            }
            if ("position".equals(methodName) || "limit".equals(methodName)) {
                return "()I".equals(methodDesc);
            }
            if ("order".equals(methodName)) {
                return methodDesc != null && methodDesc.startsWith("()");
            }
            return "capacity".equals(methodName)
                    || "remaining".equals(methodName)
                    || "hasRemaining".equals(methodName)
                    || "isDirect".equals(methodName)
                    || "isReadOnly".equals(methodName)
                    || "hasArray".equals(methodName)
                    || "arrayOffset".equals(methodName)
                    || "asReadOnlyBuffer".equals(methodName)
                    || "duplicate".equals(methodName)
                    || "slice".equals(methodName)
                    || "equals".equals(methodName)
                    || "hashCode".equals(methodName)
                    || "toString".equals(methodName)
                    || "compareTo".equals(methodName)
                    || "mismatch".equals(methodName);
        }
        if ("java/lang/StringBuilder".equals(ownerInternal) || "java/lang/StringBuffer".equals(ownerInternal)) {
            return "length".equals(methodName)
                    || "capacity".equals(methodName)
                    || "charAt".equals(methodName)
                    || "codePointAt".equals(methodName)
                    || "codePointBefore".equals(methodName)
                    || "codePointCount".equals(methodName)
                    || "indexOf".equals(methodName)
                    || "lastIndexOf".equals(methodName)
                    || "substring".equals(methodName)
                    || "subSequence".equals(methodName)
                    || "isEmpty".equals(methodName)
                    || "chars".equals(methodName)
                    || "codePoints".equals(methodName)
                    || "compareTo".equals(methodName)
                    || "toString".equals(methodName)
                    || "hashCode".equals(methodName)
                    || "equals".equals(methodName);
        }
        return READ_ONLY_COLLECTION_AND_HOLDER_METHODS.contains(methodName);
    }

    private static void inspectStackInvocationEffects(
            String callerMethodSym,
            String mOwnerInternal,
            String mName,
            String mDesc,
            StackVal receiver,
            List<String> callArgDescs,
            StackVal[] callArgs,
            Set<String> arrayMutatedOut,
            Set<String> receiverMutatedOut,
            Set<String> collAddedOut,
            Set<String> collRemovedOut,
            Set<String> collDirectWrittenOut,
            Set<String> dynamicFnDispatchesOut,
            Set<String> asyncFnDispatchesOut
    ) {
        if (mOwnerInternal == null || mName == null) {
            return;
        }
        if ("java/lang/System".equals(mOwnerInternal) && "arraycopy".equals(mName) && callArgs.length >= 3) {
            recordArrayMutationFromSlot(callArgs[2], callerMethodSym, arrayMutatedOut);
        } else if ("java/lang/reflect/Array".equals(mOwnerInternal)
                && mName.startsWith("set")
                && callArgs.length >= 1) {
            recordArrayMutationFromSlot(callArgs[0], callerMethodSym, arrayMutatedOut);
        } else if (isJdkOrSkippedInfrastructureOwner(mOwnerInternal)) {
            for (int idx = 0; idx < callArgDescs.size(); idx++) {
                if (callArgDescs.get(idx).startsWith("[")
                        && !isKnownReadOnlyJdkArrayCall(mOwnerInternal, mName)) {
                    recordArrayMutationFromSlot(callArgs[idx], callerMethodSym, arrayMutatedOut);
                }
            }
        }

        if ("java/util/Collections".equals(mOwnerInternal)
                && callArgs.length >= 1
                && ("fill".equals(mName) || "addAll".equals(mName) || "replaceAll".equals(mName)
                || "sort".equals(mName) || "reverse".equals(mName) || "shuffle".equals(mName)
                || "swap".equals(mName) || "copy".equals(mName))) {
            StackVal targetColl = callArgs[0];
            if (targetColl != null && !targetColl.sources.isEmpty()) {
                collAddedOut.addAll(targetColl.sources);
            }
            recordReceiverContainerMutation(targetColl, callerMethodSym, receiverMutatedOut);
        }

        if (receiver != null && !"<init>".equals(mName)) {
            if (!receiver.sources.isEmpty() && isJdkCollectionOrMapOwner(mOwnerInternal)) {
                if (QUEUE_PRODUCER_METHODS.contains(mName)
                        || "set".equals(mName)
                        || "putAll".equals(mName)
                        || "addAll".equals(mName)
                        || "compute".equals(mName)
                        || "computeIfPresent".equals(mName)
                        || "merge".equals(mName)
                        || "replace".equals(mName)
                        || "replaceAll".equals(mName)
                        || "sort".equals(mName)) {
                    collAddedOut.addAll(receiver.sources);
                    for (String s : receiver.sources) {
                        if (!s.startsWith("GETTER:")) {
                            collDirectWrittenOut.add(s);
                        }
                    }
                } else if ("remove".equals(mName)
                        || "clear".equals(mName)
                        || "removeAll".equals(mName)
                        || "retainAll".equals(mName)
                        || "removeIf".equals(mName)
                        || "poll".equals(mName)) {
                    collRemovedOut.addAll(receiver.sources);
                    for (String s : receiver.sources) {
                        if (!s.startsWith("GETTER:")) {
                            collDirectWrittenOut.add(s);
                        }
                    }
                }
            }
            if (isMutableJdkHolderOrContainerOwner(mOwnerInternal)
                    && !isKnownReadOnlyJdkReceiverMethod(mOwnerInternal, mName, mDesc)) {
                recordReceiverContainerMutation(receiver, callerMethodSym, receiverMutatedOut);
            }
        }

        if (isJdkOrSkippedInfrastructureOwner(mOwnerInternal)) {
            boolean isAsyncScheduler = isJdkAsyncSchedulerCall(mOwnerInternal, mName);
            for (int idx = 0; idx < callArgDescs.size(); idx++) {
                String argDesc = callArgDescs.get(idx);
                if (!argDesc.startsWith("L") || !argDesc.endsWith(";")) {
                    continue;
                }
                String argBinary = argDesc.substring(1, argDesc.length() - 1).replace('/', '.');
                String sam = JDK_FUNCTIONAL_SAM_METHODS.get(argBinary);
                if (sam == null) {
                    continue;
                }
                StackVal argVal = callArgs[idx];
                if (argVal == null || !argVal.isInlineCallback) {
                    String fnDispatchSym = argBinary + "#" + sam;
                    dynamicFnDispatchesOut.add(fnDispatchSym);
                    if (isAsyncScheduler) {
                        asyncFnDispatchesOut.add(fnDispatchSym);
                    }
                }
            }
        }
    }

    private static boolean isJdkAsyncSchedulerCall(String mOwnerInternal, String mName) {
        if ("java/util/concurrent/CompletableFuture".equals(mOwnerInternal)
                || "java/util/concurrent/CompletionStage".equals(mOwnerInternal)) {
            return true;
        }
        return mOwnerInternal.startsWith("java/util/concurrent/")
                && (mOwnerInternal.endsWith("Executor")
                || mOwnerInternal.endsWith("ExecutorService")
                || mOwnerInternal.endsWith("Pool")
                || mOwnerInternal.endsWith("ForkJoinTask"))
                && ("execute".equals(mName)
                || "submit".equals(mName)
                || mName.startsWith("schedule")
                || mName.startsWith("invoke"));
    }

    private static StackVal classifyMethodReturnStackVal(
            String mOwnerInternal,
            String mName,
            String mDesc,
            StackVal receiver,
            StackVal[] callArgs
    ) {
        if (mDesc == null) {
            return StackVal.UNKNOWN_1;
        }
        int closeParen = mDesc.indexOf(')');
        if (closeParen < 0 || closeParen + 1 >= mDesc.length()) {
            return null;
        }
        String retDesc = mDesc.substring(closeParen + 1);
        if ("V".equals(retDesc)) {
            return null;
        }
        if ("J".equals(retDesc) || "D".equals(retDesc)) {
            return StackVal.OTHER_2;
        }
        if (mOwnerInternal != null) {
            if (mOwnerInternal.startsWith("[") && "clone".equals(mName)) {
                return StackVal.NEW_ARRAY_1;
            }
            if ("java/util/Arrays".equals(mOwnerInternal)
                    && ("copyOf".equals(mName) || "copyOfRange".equals(mName))) {
                return StackVal.NEW_ARRAY_1;
            }
            if (mOwnerInternal.startsWith("java/nio/") && mOwnerInternal.endsWith("Buffer")
                    && ("allocate".equals(mName) || "allocateDirect".equals(mName))) {
                return StackVal.NEW_OBJECT_1;
            }
            if ("java/util/BitSet".equals(mOwnerInternal) && "valueOf".equals(mName)) {
                return StackVal.NEW_OBJECT_1;
            }
            if ("java/util/concurrent/CompletableFuture".equals(mOwnerInternal)
                    && ("completedFuture".equals(mName) || "failedFuture".equals(mName))) {
                return StackVal.NEW_OBJECT_1;
            }
            if ("java/util/Collections".equals(mOwnerInternal)
                    && callArgs.length >= 1
                    && (mName.startsWith("synchronized")
                    || mName.startsWith("checked")
                    || "newSetFromMap".equals(mName)
                    || "asLifoQueue".equals(mName))) {
                return callArgs[0];
            }
            if (receiver != null && (!receiver.sources.isEmpty() || receiver.isFreshObject)) {
                if (isMutableViewPropagatorMethod(mOwnerInternal, mName)) {
                    return receiver;
                }
            }
            if (!"<init>".equals(mName) && !isJdkOrSkippedInfrastructureOwner(mOwnerInternal) && callArgs.length == 0) {
                if ((retDesc.startsWith("[") || retDesc.startsWith("L"))
                        && !isDeeplyImmutableJdkValueDescriptor(retDesc)) {
                    return StackVal.knownSource("GETTER:" + mOwnerInternal.replace('/', '.') + "#" + mName);
                }
            }
        }
        if (retDesc.startsWith("L") || retDesc.startsWith("[")) {
            return isDeeplyImmutableJdkValueDescriptor(retDesc) ? StackVal.OTHER_1 : StackVal.UNKNOWN_1;
        }
        return StackVal.OTHER_1;
    }

    private static boolean isMutableViewPropagatorMethod(String ownerInternal, String methodName) {
        if ("java/lang/StringBuilder".equals(ownerInternal) || "java/lang/StringBuffer".equals(ownerInternal)) {
            return methodName.startsWith("append")
                    || methodName.startsWith("insert")
                    || methodName.startsWith("delete")
                    || "replace".equals(methodName)
                    || "reverse".equals(methodName);
        }
        if (ownerInternal.startsWith("java/nio/") && ownerInternal.endsWith("Buffer")) {
            return "slice".equals(methodName)
                    || "duplicate".equals(methodName)
                    || "array".equals(methodName)
                    || (methodName.startsWith("as")
                    && methodName.endsWith("Buffer")
                    && !"asReadOnlyBuffer".equals(methodName));
        }
        if (isJdkCollectionOrMapOwner(ownerInternal)) {
            return "keySet".equals(methodName)
                    || "values".equals(methodName)
                    || "entrySet".equals(methodName)
                    || "subList".equals(methodName)
                    || "iterator".equals(methodName)
                    || "listIterator".equals(methodName)
                    || "reversed".equals(methodName)
                    || "navigableKeySet".equals(methodName)
                    || "descendingKeySet".equals(methodName)
                    || "descendingMap".equals(methodName)
                    || "descendingSet".equals(methodName)
                    || "headMap".equals(methodName)
                    || "tailMap".equals(methodName)
                    || "subMap".equals(methodName)
                    || "headSet".equals(methodName)
                    || "tailSet".equals(methodName);
        }
        return false;
    }

    private static List<String> parseMethodParamDescriptors(String mDesc) {
        if (mDesc == null || !mDesc.startsWith("(")) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        int idx = 1;
        while (idx < mDesc.length() && mDesc.charAt(idx) != ')') {
            int start = idx;
            while (idx < mDesc.length() && mDesc.charAt(idx) == '[') {
                idx++;
            }
            if (idx >= mDesc.length()) {
                break;
            }
            char ch = mDesc.charAt(idx);
            if (ch == 'L') {
                int semi = mDesc.indexOf(';', idx);
                if (semi < 0) {
                    break;
                }
                idx = semi + 1;
            } else {
                idx++;
            }
            out.add(mDesc.substring(start, idx));
        }
        return out;
    }

    private static int computeMaxLocalSlots(int accessFlags, List<String> paramDescs, byte[] code) {
        int maxSlot = (accessFlags & ACC_STATIC) == 0 ? 1 : 0;
        for (String p : paramDescs) {
            maxSlot += ("J".equals(p) || "D".equals(p)) ? 2 : 1;
        }
        int pc = 0;
        while (pc < code.length) {
            int op = code[pc] & 0xFF;
            int len = instructionLength(code, pc);
            if (len <= 0) {
                break;
            }
            int slot = -1;
            if ((op >= 0x15 && op <= 0x19) || (op >= 0x36 && op <= 0x3A) || op == 0x84 || op == 0xA9) {
                if (pc + 1 < code.length) {
                    slot = code[pc + 1] & 0xFF;
                }
            } else if (op >= 0x1A && op <= 0x2D) {
                slot = (op - 0x1A) & 3;
            } else if (op >= 0x3B && op <= 0x4E) {
                slot = (op - 0x3B) & 3;
            } else if (op == 0xC4 && pc + 3 < code.length) {
                slot = readUnsignedShort(code, pc + 2);
            }
            if (slot >= maxSlot) {
                maxSlot = slot + 2;
            }
            pc += len;
        }
        return Math.max(maxSlot + 2, 8);
    }
}
