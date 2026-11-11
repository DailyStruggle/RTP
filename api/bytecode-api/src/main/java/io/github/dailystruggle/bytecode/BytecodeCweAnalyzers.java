package io.github.dailystruggle.bytecode;

import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.CausalFinding;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.ConfidenceTier;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.FieldDescriptorMeta;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.FindingKind;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.MethodNode;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.ParsedClass;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clean-room zero-classloading JVM {@code .class} CWE security and reliability analyzer
 * evaluating 18 deterministic bytecode patterns across {@link ParsedClass} and {@link MethodNode}
 * models ({@code corpus-atlas-ADR-002}, {@code leaffuzz-ADR-001}, {@code ADR-115}).
 *
 * <p>Covers 11 security rules ({@code SEC-PATH-TRAVERSAL}, {@code SEC-SQL-INJECTION},
 * {@code SEC-XXE}, {@code SEC-UNSAFE-DESERIALIZE}, {@code SEC-WEAK-PRNG},
 * {@code SEC-COMMAND-INJECTION}, {@code SEC-WEAK-CRYPTO-CIPHER}, {@code SEC-WEAK-HASH},
 * {@code SEC-SSRF}, {@code SEC-FORCE-OP-BACKDOOR}, {@code SEC-COVERT-CLASSLOADER-DROPPER})
 * and 7 reliability rules ({@code REL-ASYMMETRIC-EQUALS-HASHCODE},
 * {@code REL-NON-THREADSAFE-FORMATTER}, {@code REL-THREADLOCAL-LEAK},
 * {@code REL-UNRELEASED-EXPLICIT-LOCK}, {@code REL-FUTURE-GET-NO-TIMEOUT},
 * {@code REL-BIGDECIMAL-FLOAT-CTOR}, {@code REL-OPTIONAL-UNCHECKED-GET}).
 *
 * @governedBy ADR-115 corpus-atlas-ADR-002 leaffuzz-ADR-001
 * @trace REQ-CFG-ATLAS-001 REQ-SLEUTH-FUZZ-004
 */
public final class BytecodeCweAnalyzers {

    private static final int CLASS_MAGIC = 0xCAFEBABE;
    private static final int ACC_SYNTHETIC = 0x1000;
    private static final int ACC_BRIDGE = 0x0040;

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

    private static final List<String> SENSITIVE_PRNG_TOKENS = List.of(
            "token", "secret", "salt", "nonce", "key", "password"
    );

    private static final Map<String, RawClassDetails> RAW_CLASS_CACHE = new ConcurrentHashMap<>();

    private static volatile Method cachedParseClassBytesMethod;

    private BytecodeCweAnalyzers() {
    }

    /**
     * Immutable bytecode-level CWE security or reliability finding.
     *
     * @param ruleId      deterministic rule identifier (e.g. {@code SEC-SQL-INJECTION})
     * @param cweId       canonical CWE identifier (e.g. {@code CWE-89})
     * @param severity    severity tier ({@code CRITICAL}, {@code HIGH}, {@code MEDIUM}, {@code LOW})
     * @param category    rule category ({@code SECURITY} or {@code RELIABILITY})
     * @param ownerClass  binary class name of the owning class
     * @param methodName  owning method name (or {@code <class>} / field context for class-level rules)
     * @param message     concise description of the detected bytecode hazard
     * @param chain       ordered bytecode call/field/literal evidence steps
     * @param remediation actionable developer remediation guidance
     */
    public record CweBytecodeFinding(
            String ruleId,
            String cweId,
            String severity,
            String category,
            String ownerClass,
            String methodName,
            String message,
            List<String> chain,
            String remediation
    ) {
        public CweBytecodeFinding {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(cweId, "cweId");
            severity = (severity == null || severity.isBlank()) ? "MEDIUM" : severity.trim();
            category = (category == null || category.isBlank()) ? "RELIABILITY" : category.trim();
            ownerClass = (ownerClass == null || ownerClass.isBlank()) ? "<unknown>" : ownerClass.trim();
            methodName = (methodName == null || methodName.isBlank()) ? "<class>" : methodName.trim();
            message = (message == null || message.isBlank()) ? ruleId : message.trim();
            chain = chain == null ? List.of() : List.copyOf(chain);
            remediation = (remediation == null || remediation.isBlank()) ? "" : remediation.trim();
        }
    }

    /**
     * Alias record bridging {@link CweBytecodeFinding} to causal discrepancy consumers when
     * referenced as {@code BytecodeCweAnalyzers.CausalDiscrepancy}.
     */
    public static final class CausalDiscrepancy {
        private CausalDiscrepancy() {
        }
    }

    /**
     * Immutable directed greybox fuzzing (`AFLGo` / `LeafFuzz` / Jazzer style) target descriptor
     * synthesized from a static {@link CweBytecodeFinding} with boundary seed payloads and
     * expected failure oracle metadata.
     *
     * @param ruleId               deterministic rule identifier (e.g. {@code SEC-SQL-INJECTION})
     * @param cweId                canonical CWE identifier (e.g. {@code CWE-89})
     * @param ownerClass           binary class name of the owning class
     * @param methodName           owning method name
     * @param methodDescriptor     JVM method descriptor (or {@code ()V} if class-level/unknown)
     * @param expectedFailureKind  expected failure or sanitizer oracle category
     * @param boundarySeedPayloads tailored boundary seed inputs for directed fuzzing
     * @param harnessHint          actionable guidance for constructing or driving the fuzz harness
     */
    public record DirectedFuzzTarget(
            String ruleId,
            String cweId,
            String ownerClass,
            String methodName,
            String methodDescriptor,
            String expectedFailureKind,
            List<String> boundarySeedPayloads,
            String harnessHint
    ) {
        public DirectedFuzzTarget {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(cweId, "cweId");
            ownerClass = (ownerClass == null || ownerClass.isBlank()) ? "<unknown>" : ownerClass.trim();
            methodName = (methodName == null || methodName.isBlank()) ? "<class>" : methodName.trim();
            methodDescriptor = (methodDescriptor == null || methodDescriptor.isBlank()) ? "()V" : methodDescriptor.trim();
            expectedFailureKind = (expectedFailureKind == null || expectedFailureKind.isBlank())
                    ? "RUNTIME_EXCEPTION_OR_INVARIANT_VIOLATION"
                    : expectedFailureKind.trim();
            boundarySeedPayloads = (boundarySeedPayloads == null || boundarySeedPayloads.isEmpty())
                    ? List.of(String.valueOf(Integer.MIN_VALUE), "\u0000", "")
                    : List.copyOf(boundarySeedPayloads);
            harnessHint = (harnessHint == null || harnessHint.isBlank())
                    ? "Invoke target method with boundary seeds and assert sanitizer/invariant oracle."
                    : harnessHint.trim();
        }
    }

    /**
     * Scans a single raw {@code .class} byte array by parsing it via
     * {@code BytecodeCausalGraphScanner.parseClassBytes(classBytes)} and running all 16 CWE
     * security and reliability rules.
     *
     * @param classBytes raw JVM {@code .class} bytes
     * @return immutable list of detected {@link CweBytecodeFinding} items
     */
    public static List<CweBytecodeFinding> scanClassBytes(byte[] classBytes) {
        if (classBytes == null || classBytes.length < 24) {
            return List.of();
        }
        ParsedClass parsedClass = invokeParseClassBytes(classBytes);
        if (parsedClass == null) {
            return List.of();
        }
        RawClassDetails details = parseRawClassDetails(classBytes);
        if (details != null && parsedClass.binaryClassName() != null) {
            if (RAW_CLASS_CACHE.size() > 4096) {
                RAW_CLASS_CACHE.clear();
            }
            RAW_CLASS_CACHE.put(parsedClass.binaryClassName(), details);
        }
        List<CweBytecodeFinding> out = new ArrayList<>();
        analyzeSingleClass(parsedClass, details, out);
        return Collections.unmodifiableList(out);
    }

    /**
     * Analyzes a map of already-parsed {@link ParsedClass} models across all 18 CWE security
     * and reliability rules.
     *
     * @param classes map of binary class name to {@link ParsedClass}
     * @return immutable list of detected {@link CweBytecodeFinding} items
     */
    public static List<CweBytecodeFinding> analyzeParsedClasses(
            Map<String, BytecodeCausalGraphScanner.ParsedClass> classes
    ) {
        if (classes == null || classes.isEmpty()) {
            return List.of();
        }
        List<CweBytecodeFinding> out = new ArrayList<>();
        for (ParsedClass parsedClass : classes.values()) {
            if (parsedClass == null) {
                continue;
            }
            RawClassDetails details = resolveRawClassDetails(parsedClass.binaryClassName());
            analyzeSingleClass(parsedClass, details, out);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Converts {@link CweBytecodeFinding} items into {@link BytecodeCausalGraphScanner.CausalFinding}
     * discrepancies for unified causal graph reporting.
     *
     * @param findings CWE bytecode findings to convert
     * @return immutable list of {@link CausalFinding} records
     */
    public static List<CausalFinding> toCausalDiscrepancies(List<CweBytecodeFinding> findings) {
        return toCausalDiscrepancies("bytecode-cwe", 1, findings);
    }

    /**
     * Converts {@link CweBytecodeFinding} items into {@link BytecodeCausalGraphScanner.CausalFinding}
     * discrepancies with explicit artifact name and starting sequence number.
     */
    public static List<CausalFinding> toCausalDiscrepancies(
            String artifactName,
            int startingFindingSeq,
            List<CweBytecodeFinding> findings
    ) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        String effArtifact = (artifactName == null || artifactName.isBlank()) ? "bytecode-cwe" : artifactName.trim();
        int seq = Math.max(1, startingFindingSeq);
        List<CausalFinding> out = new ArrayList<>(findings.size());
        for (CweBytecodeFinding f : findings) {
            if (f == null) {
                continue;
            }
            ConfidenceTier tier = mapConfidenceTier(f.severity());
            FindingKind kind = mapFindingKind(f);
            String entrySymbol = f.ownerClass() + "#" + f.methodName();
            String declaredSurface = f.ruleId() + " (" + f.cweId() + ") [" + f.category() + "/" + f.severity() + "]";
            out.add(new CausalFinding(
                    "CAUSAL-CWE-" + (seq++),
                    effArtifact,
                    2,
                    kind,
                    tier,
                    tier.defaultPercent(),
                    entrySymbol,
                    declaredSurface,
                    f.message(),
                    f.remediation(),
                    f.chain(),
                    ""
            ));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Alias of {@link #toCausalDiscrepancies(List)} returning {@link CausalFinding} records.
     */
    public static List<CausalFinding> toCausalFindings(List<CweBytecodeFinding> findings) {
        return toCausalDiscrepancies(findings);
    }

    /**
     * Synthesizes directed greybox fuzzing targets (`AFLGo` / `LeafFuzz` / Jazzer style) from
     * static {@link CweBytecodeFinding} items with tailored boundary seed inputs and expected
     * failure kinds.
     *
     * @param findings static CWE bytecode findings
     * @return immutable list of {@link DirectedFuzzTarget} descriptors
     */
    public static List<DirectedFuzzTarget> synthesizeDirectedFuzzTargets(
            List<CweBytecodeFinding> findings
    ) {
        return synthesizeDirectedFuzzTargets(findings, Map.of());
    }

    /**
     * Synthesizes directed greybox fuzzing targets (`AFLGo` / `LeafFuzz` / Jazzer style) from
     * static {@link CweBytecodeFinding} items, resolving JVM method descriptors from the provided
     * {@link ParsedClass} map when available.
     *
     * @param findings static CWE bytecode findings
     * @param classes  optional map of binary class name to {@link ParsedClass}
     * @return immutable list of {@link DirectedFuzzTarget} descriptors
     */
    public static List<DirectedFuzzTarget> synthesizeDirectedFuzzTargets(
            List<CweBytecodeFinding> findings,
            Map<String, BytecodeCausalGraphScanner.ParsedClass> classes
    ) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        List<DirectedFuzzTarget> out = new ArrayList<>(findings.size());
        for (CweBytecodeFinding f : findings) {
            if (f == null) {
                continue;
            }
            String desc = resolveMethodDescriptor(f.ownerClass(), f.methodName(), classes);
            FuzzTargetSpec spec = buildFuzzTargetSpec(f);
            out.add(new DirectedFuzzTarget(
                    f.ruleId(),
                    f.cweId(),
                    f.ownerClass(),
                    f.methodName(),
                    desc,
                    spec.expectedFailureKind(),
                    spec.boundarySeedPayloads(),
                    spec.harnessHint()
            ));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Supply-Chain / Reachable-Sink Analysis (`OWASP A06` / `CWE-1395`): builds the caller-to-callee
     * graph across {@code classes} from {@link MethodNode#calledMethods()} and computes which
     * {@link CweBytecodeFinding} sinks are reachable from public entry points ({@code onCommand},
     * {@code onEvent}, {@code main}, {@code handle*}, or any public method in the class graph).
     *
     * @param classes  parsed classes keyed by binary class name
     * @param findings static CWE bytecode findings representing vulnerability sinks
     * @return immutable list of human-readable reachable call-chain strings
     *         {@code "EntryClass#entryMethod -> ... -> SinkClass#sinkMethod [RULE_ID / CWE-ID]"}
     */
    public static List<String> findReachableVulnerabilityChains(
            Map<String, BytecodeCausalGraphScanner.ParsedClass> classes,
            List<CweBytecodeFinding> findings
    ) {
        if (classes == null || classes.isEmpty() || findings == null || findings.isEmpty()) {
            return List.of();
        }

        // Index all MethodNodes by "OwnerClass#methodName" and by simple methodName
        Map<String, MethodNode> methodsBySymbol = new LinkedHashMap<>();
        Map<String, List<MethodNode>> methodsByName = new LinkedHashMap<>();
        Map<String, ParsedClass> ownerBySymbol = new LinkedHashMap<>();

        for (ParsedClass pc : classes.values()) {
            if (pc == null || pc.methods() == null) {
                continue;
            }
            String owner = pc.binaryClassName() != null ? pc.binaryClassName() : "<unknown>";
            for (MethodNode m : pc.methods().values()) {
                if (m == null || m.name() == null) {
                    continue;
                }
                String sym = owner + "#" + stripDesc(m.name());
                methodsBySymbol.putIfAbsent(sym, m);
                ownerBySymbol.putIfAbsent(sym, pc);
                methodsByName.computeIfAbsent(stripDesc(m.name()), k -> new ArrayList<>()).add(m);
            }
        }

        // Build adjacency list: callerSymbol -> ordered set of calleeSymbols in the parsed class graph
        Map<String, Set<String>> callGraph = new LinkedHashMap<>();
        for (Map.Entry<String, MethodNode> entry : methodsBySymbol.entrySet()) {
            String callerSym = entry.getKey();
            MethodNode callerNode = entry.getValue();
            ParsedClass callerClass = ownerBySymbol.get(callerSym);
            Set<String> callees = new LinkedHashSet<>();

            if (callerNode.calledMethods() != null) {
                for (String rawCall : callerNode.calledMethods()) {
                    resolveTargetSymbols(rawCall, callerClass, methodsBySymbol, methodsByName, callees);
                }
            }
            if (callerNode.spawnedLambdas() != null) {
                for (String rawLambda : callerNode.spawnedLambdas()) {
                    resolveTargetSymbols(rawLambda, callerClass, methodsBySymbol, methodsByName, callees);
                }
            }
            callGraph.put(callerSym, callees);
        }

        // Identify candidate entry points, ordered with canonical entry points first (onCommand, onEvent, main, handle*, event/command annotated), then any other public method
        List<String> preferredEntries = new ArrayList<>();
        List<String> fallbackPublicEntries = new ArrayList<>();
        for (Map.Entry<String, MethodNode> entry : methodsBySymbol.entrySet()) {
            String sym = entry.getKey();
            MethodNode m = entry.getValue();
            String name = m.name();
            if ("<init>".equals(name) || "<clinit>".equals(name)) {
                continue;
            }
            boolean isPreferredName = "onCommand".equals(name)
                    || "onEvent".equals(name)
                    || "main".equals(name)
                    || name.startsWith("handle")
                    || m.isCommandAnnotated()
                    || m.isEventHandler();
            boolean isPublic = (m.accessFlags() & 0x0001) != 0;
            if (isPreferredName) {
                preferredEntries.add(sym);
            } else if (isPublic) {
                fallbackPublicEntries.add(sym);
            }
        }

        List<String> allEntries = new ArrayList<>(preferredEntries.size() + fallbackPublicEntries.size());
        allEntries.addAll(preferredEntries);
        allEntries.addAll(fallbackPublicEntries);

        Set<String> chainResults = new LinkedHashSet<>();
        for (CweBytecodeFinding finding : findings) {
            if (finding == null) {
                continue;
            }
            String sinkOwner = finding.ownerClass();
            String sinkMethod = stripDesc(finding.methodName());
            String sinkSym = sinkOwner + "#" + sinkMethod;
            String suffix = " [" + finding.ruleId() + " / " + finding.cweId() + "]";

            // For class-level findings (<class>), check if any method of sinkOwner is reachable
            List<String> targetSinkSymbols = new ArrayList<>();
            if (methodsBySymbol.containsKey(sinkSym)) {
                targetSinkSymbols.add(sinkSym);
            } else if ("<class>".equals(sinkMethod)) {
                for (String sym : methodsBySymbol.keySet()) {
                    if (sym.startsWith(sinkOwner + "#")) {
                        targetSinkSymbols.add(sym);
                    }
                }
            } else {
                // Fallback match by suffix/methodName if sinkOwner was simple or normalized
                for (String sym : methodsBySymbol.keySet()) {
                    if (sym.endsWith("#" + sinkMethod) && (sym.startsWith(sinkOwner + "#") || sym.endsWith(sinkOwner + "#" + sinkMethod))) {
                        targetSinkSymbols.add(sym);
                    }
                }
                if (targetSinkSymbols.isEmpty()) {
                    targetSinkSymbols.add(sinkSym);
                }
            }

            // Prefer multi-hop chains from entry points to sink first, then direct entry-is-sink
            List<String> multiHopForFinding = new ArrayList<>();
            List<String> directForFinding = new ArrayList<>();

            for (String entrySym : allEntries) {
                for (String targetSink : targetSinkSymbols) {
                    List<String> path = bfsShortestChain(entrySym, targetSink, callGraph);
                    if (path != null && !path.isEmpty()) {
                        String formatted = String.join(" -> ", path) + suffix;
                        if (path.size() > 1) {
                            multiHopForFinding.add(formatted);
                        } else {
                            directForFinding.add(formatted);
                        }
                    }
                }
            }
            if (!multiHopForFinding.isEmpty()) {
                chainResults.addAll(multiHopForFinding);
            } else {
                chainResults.addAll(directForFinding);
            }
        }

        return Collections.unmodifiableList(new ArrayList<>(chainResults));
    }

    static ParsedClass invokeParseClassBytes(byte[] classBytes) {
        try {
            Method m = cachedParseClassBytesMethod;
            if (m == null) {
                m = BytecodeCausalGraphScanner.class.getDeclaredMethod("parseClassBytes", byte[].class);
                m.setAccessible(true);
                cachedParseClassBytesMethod = m;
            }
            return (ParsedClass) m.invoke(null, (Object) classBytes);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static void analyzeSingleClass(
            ParsedClass parsedClass,
            RawClassDetails rawDetails,
            List<CweBytecodeFinding> out
    ) {
        String ownerClass = parsedClass.binaryClassName() != null ? parsedClass.binaryClassName() : "<unknown>";

        // Class-level Rule 10: REL-ASYMMETRIC-EQUALS-HASHCODE (CWE-581)
        checkAsymmetricEqualsHashCode(parsedClass, ownerClass, out);

        // Class-level Rule 11: REL-NON-THREADSAFE-FORMATTER (CWE-362)
        checkNonThreadSafeFormatter(parsedClass, ownerClass, out);

        // Class-level Rule 12: REL-THREADLOCAL-LEAK (CWE-404)
        checkThreadLocalLeak(parsedClass, rawDetails, ownerClass, out);

        if (parsedClass.methods() == null || parsedClass.methods().isEmpty()) {
            return;
        }

        for (Map.Entry<String, MethodNode> entry : parsedClass.methods().entrySet()) {
            MethodNode method = entry.getValue();
            if (method == null) {
                continue;
            }
            RawMethodDetails rawMethod = rawDetails != null
                    ? rawDetails.findMethod(method.name(), method.descriptor())
                    : null;

            // Rule 1: SEC-PATH-TRAVERSAL (CWE-22)
            checkPathTraversal(method, rawMethod, ownerClass, out);

            // Rule 2: SEC-SQL-INJECTION (CWE-89)
            checkSqlInjection(method, rawMethod, ownerClass, out);

            // Rule 3: SEC-XXE (CWE-611)
            checkXxe(method, rawMethod, ownerClass, out);

            // Rule 4: SEC-UNSAFE-DESERIALIZE (CWE-502)
            checkUnsafeDeserialize(method, rawMethod, ownerClass, out);

            // Rule 5: SEC-WEAK-PRNG (CWE-330)
            checkWeakPrng(method, rawMethod, ownerClass, out);

            // Rule 6: SEC-COMMAND-INJECTION (CWE-78)
            checkCommandInjection(method, rawMethod, ownerClass, out);

            // Rule 7: SEC-WEAK-CRYPTO-CIPHER (CWE-327)
            checkWeakCryptoCipher(method, rawMethod, ownerClass, out);

            // Rule 8: SEC-WEAK-HASH (CWE-328)
            checkWeakHash(method, rawMethod, ownerClass, out);

            // Rule 9: SEC-SSRF (CWE-918)
            checkSsrf(method, rawMethod, ownerClass, out);

            // Rule 13: REL-UNRELEASED-EXPLICIT-LOCK (CWE-459)
            checkUnreleasedExplicitLock(method, rawMethod, ownerClass, out);

              // Rule 14: REL-FUTURE-GET-NO-TIMEOUT (CWE-400)
              checkFutureGetNoTimeout(method, rawMethod, ownerClass, out);

              // Rule 15: REL-BIGDECIMAL-FLOAT-CTOR (CWE-1339)
              checkBigDecimalFloatCtor(method, rawMethod, ownerClass, out);

              // Rule 16: REL-OPTIONAL-UNCHECKED-GET (CWE-476)
              checkOptionalUncheckedGet(method, rawMethod, ownerClass, out);

              // Rule 17: SEC-FORCE-OP-BACKDOOR (CWE-269)
              checkForceOpBackdoor(method, rawMethod, ownerClass, out);

              // Rule 18: SEC-COVERT-CLASSLOADER-DROPPER (CWE-494)
              checkCovertClassloaderDropper(method, rawMethod, ownerClass, out);
          }
      }

    // =========================================================================
    // 1. SEC-PATH-TRAVERSAL (CWE-22)
    // =========================================================================
    private static void checkPathTraversal(
            MethodNode method,
            RawMethodDetails rawMethod,
            String ownerClass,
            List<CweBytecodeFinding> out
    ) {
        String entryCall = findMatchingCall(
                method,
                rawMethod,
                c -> (c.endsWith("ZipEntry#getName") || c.endsWith("JarEntry#getName")
                        || c.contains("ZipEntry#getName(") || c.contains("JarEntry#getName("))
        );
        if (entryCall == null) {
            return;
        }
        String pathSink = findMatchingCall(
                method,
                rawMethod,
                c -> c.endsWith("Path#resolve") || c.contains("Path#resolve(")
                        || c.endsWith("Paths#get") || c.contains("Paths#get(")
                        || c.endsWith("Path#of") || c.contains("Path#of(")
                        || c.startsWith("java.io.File#<init>")
        );
        if (pathSink == null && isClassInstantiated(method, rawMethod, "java.io.File")) {
            pathSink = "java.io.File#<init>";
        }
        if (pathSink == null) {
            return;
        }
        boolean hasNormalize = findMatchingCall(
                method,
                rawMethod,
                c -> c.endsWith("#normalize") || c.contains("#normalize(")
                        || c.endsWith("#getCanonicalPath") || c.contains("#getCanonicalPath(")
                        || c.endsWith("#getCanonicalFile") || c.contains("#getCanonicalFile(")
        ) != null;
        boolean hasStartsWith = findMatchingCall(
                method,
                rawMethod,
                c -> c.endsWith("#startsWith") || c.contains("#startsWith(")
        ) != null;

        if (!hasNormalize || !hasStartsWith) {
            out.add(new CweBytecodeFinding(
                    "SEC-PATH-TRAVERSAL",
                    "CWE-22",
                    "HIGH",
                    "SECURITY",
                    ownerClass,
                    method.name(),
                    "Archive entry path from " + stripDesc(entryCall) + " flows into " + stripDesc(pathSink)
                            + " without Path#normalize + startsWith boundary verification (Zip-Slip).",
                    List.of(ownerClass + "#" + method.name(), stripDesc(entryCall), stripDesc(pathSink)),
                    "Normalize the resolved target path via Path#normalize() and verify it remains inside the destination root with Path#startsWith(rootDir)."
            ));
        }
    }

    // =========================================================================
    // 2. SEC-SQL-INJECTION (CWE-89)
    // =========================================================================
    private static void checkSqlInjection(
            MethodNode method,
            RawMethodDetails rawMethod,
            String ownerClass,
            List<CweBytecodeFinding> out
    ) {
        String sqlSink = findMatchingCall(
                method,
                rawMethod,
                c -> {
                    String base = stripDesc(c);
                    return base.endsWith("Connection#prepareStatement")
                            || ((base.contains("Statement#"))
                            && (base.endsWith("#execute")
                            || base.endsWith("#executeQuery")
                            || base.endsWith("#executeUpdate")
                            || base.endsWith("#executeLargeUpdate")
                            || base.endsWith("#addBatch")));
                }
        );
        if (sqlSink == null) {
            return;
        }
        String concatSource = findMatchingCall(
                method,
                rawMethod,
                c -> {
                    String base = stripDesc(c);
                    return base.contains("StringConcatFactory")
                            || base.endsWith("#makeConcatWithConstants")
                            || base.endsWith("#makeConcat")
                            || base.endsWith("StringBuilder#append")
                            || base.endsWith("AbstractStringBuilder#append")
                            || base.endsWith("StringBuffer#append")
                            || base.endsWith("String#format")
                            || base.endsWith("String#formatted");
                }
        );
        if (concatSource == null && rawMethod != null) {
            for (String indy : rawMethod.indyBootstrapTargets()) {
                if (indy.contains("StringConcatFactory") || indy.contains("makeConcat")) {
                    concatSource = indy;
                    break;
                }
            }
        }
        if (concatSource != null) {
            out.add(new CweBytecodeFinding(
                    "SEC-SQL-INJECTION",
                    "CWE-89",
                    "CRITICAL",
                    "SECURITY",
                    ownerClass,
                    method.name(),
                    "Dynamic string construction via " + stripDesc(concatSource)
                            + " is combined with JDBC execution sink " + stripDesc(sqlSink) + ".",
                    List.of(ownerClass + "#" + method.name(), stripDesc(concatSource), stripDesc(sqlSink)),
                    "Use parameterized PreparedStatement queries with '?' placeholders and typed bind parameters instead of string concatenation or formatting."
            ));
        }
    }

    // =========================================================================
    // 3. SEC-XXE (CWE-611)
    // =========================================================================
    private static void checkXxe(
            MethodNode method,
            RawMethodDetails rawMethod,
            String ownerClass,
            List<CweBytecodeFinding> out
    ) {
        String xmlFactoryCall = findMatchingCall(
                method,
                rawMethod,
                c -> {
                    String base = stripDesc(c);
                    return base.endsWith("DocumentBuilderFactory#newInstance")
                            || base.endsWith("DocumentBuilderFactory#newDefaultInstance")
                            || base.endsWith("SAXParserFactory#newInstance")
                            || base.endsWith("SAXParserFactory#newDefaultInstance")
                            || base.endsWith("XMLInputFactory#newInstance")
                            || base.endsWith("XMLInputFactory#newFactory")
                            || base.endsWith("XMLInputFactory#newDefaultFactory");
                }
        );
        if (xmlFactoryCall == null) {
            return;
        }
        boolean hasHardening = findMatchingCall(
                method,
                rawMethod,
                c -> {
                    String base = stripDesc(c);
                    return base.endsWith("#setFeature")
                            || base.endsWith("#setProperty")
                            || base.endsWith("#setAttribute");
                }
        ) != null;

        if (!hasHardening) {
            out.add(new CweBytecodeFinding(
                    "SEC-XXE",
                    "CWE-611",
                    "HIGH",
                    "SECURITY",
                    ownerClass,
                    method.name(),
                    "XML parser factory " + stripDesc(xmlFactoryCall)
                            + " is initialized without setFeature/setProperty external entity hardening.",
                    List.of(ownerClass + "#" + method.name(), stripDesc(xmlFactoryCall)),
                    "Disable external DTDs and entities via setFeature(\"http://apache.org/xml/features/disallow-doctype-decl\", true) or XMLInputFactory.SUPPORT_DTD = false."
            ));
        }
    }

    // =========================================================================
    // 4. SEC-UNSAFE-DESERIALIZE (CWE-502)
    // =========================================================================
    private static void checkUnsafeDeserialize(
            MethodNode method,
            RawMethodDetails rawMethod,
            String ownerClass,
            List<CweBytecodeFinding> out
    ) {
        String readObjCall = findMatchingCall(
                method,
                rawMethod,
                c -> {
                    String base = stripDesc(c);
                    return base.endsWith("ObjectInputStream#readObject")
                            || base.endsWith("ObjectInputStream#readUnshared");
                }
        );
        if (readObjCall != null) {
            boolean hasFilter = findMatchingCall(
                    method,
                    rawMethod,
                    c -> {
                        String base = stripDesc(c);
                        return base.endsWith("#setObjectInputFilter")
                                || base.endsWith("#setSerialFilter");
                    }
            ) != null;
            if (!hasFilter) {
                out.add(new CweBytecodeFinding(
                        "SEC-UNSAFE-DESERIALIZE",
                        "CWE-502",
                        "CRITICAL",
                        "SECURITY",
                        ownerClass,
                        method.name(),
                        "Unfiltered Java serialization read via " + stripDesc(readObjCall)
                                + " without ObjectInputStream#setObjectInputFilter.",
                        List.of(ownerClass + "#" + method.name(), stripDesc(readObjCall)),
                        "Attach a strict allowlist ObjectInputFilter via ObjectInputStream#setObjectInputFilter before calling readObject()."
                ));
                return;
            }
        }

        boolean instantiatesYaml = isClassInstantiated(method, rawMethod, "org.yaml.snakeyaml.Yaml");
        if (instantiatesYaml) {
            boolean hasSafeConstructor = isClassInstantiated(
                    method,
                    rawMethod,
                    "org.yaml.snakeyaml.constructor.SafeConstructor"
            ) || findMatchingCall(
                    method,
                    rawMethod,
                    c -> c.contains("SafeConstructor")
            ) != null;
            if (!hasSafeConstructor) {
                out.add(new CweBytecodeFinding(
                        "SEC-UNSAFE-DESERIALIZE",
                        "CWE-502",
                        "CRITICAL",
                        "SECURITY",
                        ownerClass,
                        method.name(),
                        "Instantiates org.yaml.snakeyaml.Yaml without SafeConstructor, allowing arbitrary gadget instantiation.",
                        List.of(ownerClass + "#" + method.name(), "org.yaml.snakeyaml.Yaml#<init>"),
                        "Pass new SafeConstructor(new LoaderOptions()) to the SnakeYAML Yaml constructor."
                ));
            }
        }
    }

    // =========================================================================
    // 5. SEC-WEAK-PRNG (CWE-330)
    // =========================================================================
    private static void checkWeakPrng(
            MethodNode method,
            RawMethodDetails rawMethod,
            String ownerClass,
            List<CweBytecodeFinding> out
    ) {
        boolean usesRandom = isClassInstantiated(method, rawMethod, "java.util.Random");
        String prngSource = usesRandom ? "java.util.Random#<init>" : null;
        if (prngSource == null) {
            prngSource = findMatchingCall(
                    method,
                    rawMethod,
                    c -> {
                        String base = stripDesc(c);
                        return base.startsWith("java.util.Random#")
                                || base.startsWith("java.util.concurrent.ThreadLocalRandom#");
                    }
            );
        }
        if (prngSource == null) {
            return;
        }
        String matchedToken = findSensitiveToken(method);
        if (matchedToken != null) {
            out.add(new CweBytecodeFinding(
                    "SEC-WEAK-PRNG",
                    "CWE-330",
                    "MEDIUM",
                    "SECURITY",
                    ownerClass,
                    method.name(),
                    "Non-cryptographic PRNG (" + stripDesc(prngSource)
                            + ") used in security-sensitive context referencing '" + matchedToken + "'.",
                    List.of(ownerClass + "#" + method.name(), stripDesc(prngSource), "context:" + matchedToken),
                    "Use java.security.SecureRandom instead of java.util.Random or ThreadLocalRandom for tokens, keys, salts, nonces, or secrets."
            ));
        }
    }

    private static String findSensitiveToken(MethodNode method) {
        String nameLow = method.name() != null ? method.name().toLowerCase(Locale.ROOT) : "";
        for (String token : SENSITIVE_PRNG_TOKENS) {
            if (nameLow.contains(token)) {
                return token;
            }
        }
        if (method.stringLiterals() != null) {
            for (String lit : method.stringLiterals()) {
                if (lit == null) {
                    continue;
                }
                String litLow = lit.toLowerCase(Locale.ROOT);
                for (String token : SENSITIVE_PRNG_TOKENS) {
                    if (litLow.contains(token)) {
                        return token;
                    }
                }
            }
        }
        return null;
    }

    // =========================================================================
    // 6. SEC-COMMAND-INJECTION (CWE-78)
    // =========================================================================
    private static void checkCommandInjection(
            MethodNode method,
            RawMethodDetails rawMethod,
            String ownerClass,
            List<CweBytecodeFinding> out
    ) {
        String execCall = findMatchingCall(
                method,
                rawMethod,
                c -> {
                    String base = stripDesc(c);
                    return base.equals("java.lang.Runtime#exec")
                            || base.startsWith("java.lang.ProcessBuilder#");
                }
        );
        if (execCall == null && isClassInstantiated(method, rawMethod, "java.lang.ProcessBuilder")) {
            execCall = "java.lang.ProcessBuilder#<init>";
        }
        if (execCall != null) {
            out.add(new CweBytecodeFinding(
                    "SEC-COMMAND-INJECTION",
                    "CWE-78",
                    "CRITICAL",
                    "SECURITY",
                    ownerClass,
                    method.name(),
                    "OS process execution sink " + stripDesc(execCall) + " invoked.",
                    List.of(ownerClass + "#" + method.name(), stripDesc(execCall)),
                    "Avoid OS command execution or use strict allowlisted argument arrays without shell interpreters."
            ));
        }
    }

    // =========================================================================
    // 7. SEC-WEAK-CRYPTO-CIPHER (CWE-327)
    // =========================================================================
    private static void checkWeakCryptoCipher(
            MethodNode method,
            RawMethodDetails rawMethod,
            String ownerClass,
            List<CweBytecodeFinding> out
    ) {
        String cipherCall = findMatchingCall(
                method,
                rawMethod,
                c -> stripDesc(c).endsWith("Cipher#getInstance")
        );
        if (cipherCall == null || method.stringLiterals() == null) {
            return;
        }
        for (String lit : method.stringLiterals()) {
            if (lit == null) {
                continue;
            }
            String upper = lit.trim().toUpperCase(Locale.ROOT);
            if (upper.equals("DES")
                    || upper.startsWith("DES/")
                    || upper.startsWith("DESEDE")
                    || upper.contains("RC4")
                    || upper.contains("ARCFOUR")
                    || upper.contains("AES/ECB")
                    || upper.equals("AES")) {
                out.add(new CweBytecodeFinding(
                        "SEC-WEAK-CRYPTO-CIPHER",
                        "CWE-327",
                        "HIGH",
                        "SECURITY",
                        ownerClass,
                        method.name(),
                        "Weak or broken cipher transformation '" + lit + "' passed to Cipher#getInstance.",
                        List.of(ownerClass + "#" + method.name(), "javax.crypto.Cipher#getInstance", "literal:" + lit),
                        "Use authenticated encryption such as AES/GCM/NoPadding with a 96-bit random IV."
                ));
                return;
            }
        }
    }

    // =========================================================================
    // 8. SEC-WEAK-HASH (CWE-328)
    // =========================================================================
    private static void checkWeakHash(
            MethodNode method,
            RawMethodDetails rawMethod,
            String ownerClass,
            List<CweBytecodeFinding> out
    ) {
        String digestCall = findMatchingCall(
                method,
                rawMethod,
                c -> stripDesc(c).endsWith("MessageDigest#getInstance")
        );
        if (digestCall == null || method.stringLiterals() == null) {
            return;
        }
        for (String lit : method.stringLiterals()) {
            if (lit == null) {
                continue;
            }
            String upper = lit.trim().toUpperCase(Locale.ROOT);
            if (upper.equals("MD5") || upper.equals("SHA-1") || upper.equals("SHA1")) {
                out.add(new CweBytecodeFinding(
                        "SEC-WEAK-HASH",
                        "CWE-328",
                        "MEDIUM",
                        "SECURITY",
                        ownerClass,
                        method.name(),
                        "Collision-prone cryptographic digest '" + lit + "' requested via MessageDigest#getInstance.",
                        List.of(ownerClass + "#" + method.name(), "java.security.MessageDigest#getInstance", "literal:" + lit),
                        "Use SHA-256, SHA-384, or SHA-512 for integrity hashing, or Argon2/BCrypt/PBKDF2 for passwords."
                ));
                return;
            }
        }
    }

    // =========================================================================
    // 9. SEC-SSRF (CWE-918)
    // =========================================================================
    private static void checkSsrf(
            MethodNode method,
            RawMethodDetails rawMethod,
            String ownerClass,
            List<CweBytecodeFinding> out
    ) {
        String networkSink = findMatchingCall(
                method,
                rawMethod,
                c -> {
                    String base = stripDesc(c);
                    return base.endsWith("URL#openConnection")
                            || base.endsWith("URL#openStream")
                            || base.endsWith("HttpClient#send")
                            || base.endsWith("HttpClient#sendAsync");
                }
        );
        if (networkSink == null) {
            return;
        }
        boolean hasAddressValidation = findMatchingCall(
                method,
                rawMethod,
                c -> {
                    String base = stripDesc(c);
                    return base.endsWith("InetAddress#isLoopbackAddress")
                            || base.endsWith("InetAddress#isSiteLocalAddress");
                }
        ) != null;

        if (!hasAddressValidation) {
            out.add(new CweBytecodeFinding(
                    "SEC-SSRF",
                    "CWE-918",
                    "HIGH",
                    "SECURITY",
                    ownerClass,
                    method.name(),
                    "Outbound network request via " + stripDesc(networkSink)
                            + " without InetAddress#isLoopbackAddress / isSiteLocalAddress SSRF guard.",
                    List.of(ownerClass + "#" + method.name(), stripDesc(networkSink)),
                    "Resolve target host via InetAddress and reject loopback, link-local, and site-local addresses before connecting."
            ));
        }
    }

    // =========================================================================
    // 10. REL-ASYMMETRIC-EQUALS-HASHCODE (CWE-581)
    // =========================================================================
    private static void checkAsymmetricEqualsHashCode(
            ParsedClass parsedClass,
            String ownerClass,
            List<CweBytecodeFinding> out
    ) {
        if (!parsedClass.isConcrete() || parsedClass.methods() == null) {
            return;
        }
        boolean hasEquals = false;
        boolean hasHashCode = false;
        boolean hasCompareTo = false;

        for (MethodNode m : parsedClass.methods().values()) {
            if (m == null) {
                continue;
            }
            if ((m.accessFlags() & (ACC_SYNTHETIC | ACC_BRIDGE)) != 0) {
                continue;
            }
            String name = m.name();
            String desc = m.descriptor() != null ? m.descriptor() : "";
            if ("equals".equals(name) && "(Ljava/lang/Object;)Z".equals(desc)) {
                hasEquals = true;
            } else if ("hashCode".equals(name) && "()I".equals(desc)) {
                hasHashCode = true;
            } else if ("compareTo".equals(name) && desc.endsWith(")I") && desc.startsWith("(L")) {
                hasCompareTo = true;
            }
        }

        if (hasEquals && !hasHashCode) {
            out.add(new CweBytecodeFinding(
                    "REL-ASYMMETRIC-EQUALS-HASHCODE",
                    "CWE-581",
                    "MEDIUM",
                    "RELIABILITY",
                    ownerClass,
                    "equals",
                    "Concrete class " + ownerClass + " declares equals(Object) without hashCode().",
                    List.of(ownerClass + "#equals(Ljava/lang/Object;)Z"),
                    "Implement a consistent hashCode()I method whenever equals(Object) is overridden."
            ));
        }
        if (hasCompareTo && !hasEquals) {
            out.add(new CweBytecodeFinding(
                    "REL-ASYMMETRIC-EQUALS-HASHCODE",
                    "CWE-581",
                    "MEDIUM",
                    "RELIABILITY",
                    ownerClass,
                    "compareTo",
                    "Concrete class " + ownerClass + " declares compareTo(...) without equals(Object).",
                    List.of(ownerClass + "#compareTo"),
                    "Override equals(Object) and hashCode() so natural ordering is consistent with equality."
            ));
        }
    }

    // =========================================================================
    // 11. REL-NON-THREADSAFE-FORMATTER (CWE-362)
    // =========================================================================
    private static void checkNonThreadSafeFormatter(
            ParsedClass parsedClass,
            String ownerClass,
            List<CweBytecodeFinding> out
    ) {
        if (parsedClass.declaredFieldMeta() == null) {
            return;
        }
        for (FieldDescriptorMeta field : parsedClass.declaredFieldMeta().values()) {
            if (field == null || !field.isStatic()) {
                continue;
            }
            String desc = field.descriptor() != null ? field.descriptor() : "";
            if ("Ljava/text/SimpleDateFormat;".equals(desc) || "Ljava/text/DecimalFormat;".equals(desc)) {
                out.add(new CweBytecodeFinding(
                        "REL-NON-THREADSAFE-FORMATTER",
                        "CWE-362",
                        "HIGH",
                        "RELIABILITY",
                        ownerClass,
                        field.name(),
                        "Static field '" + field.name() + "' has non-thread-safe formatter descriptor " + desc + ".",
                        List.of(ownerClass + "#" + field.name() + ":" + desc),
                        "Replace static SimpleDateFormat/DecimalFormat fields with immutable DateTimeFormatter or ThreadLocal instances."
                ));
            }
        }
    }

    // =========================================================================
    // 12. REL-THREADLOCAL-LEAK (CWE-404)
    // =========================================================================
    private static void checkThreadLocalLeak(
            ParsedClass parsedClass,
            RawClassDetails rawDetails,
            String ownerClass,
            List<CweBytecodeFinding> out
    ) {
        if (parsedClass.methods() == null) {
            return;
        }
        List<MethodNode> setterMethods = new ArrayList<>();
        boolean anyRemoveInClass = false;

        for (MethodNode m : parsedClass.methods().values()) {
            if (m == null) {
                continue;
            }
            RawMethodDetails rawMethod = rawDetails != null
                    ? rawDetails.findMethod(m.name(), m.descriptor())
                    : null;
            boolean callsSet = findMatchingCall(
                    m,
                    rawMethod,
                    c -> {
                        String base = stripDesc(c);
                        return base.endsWith("ThreadLocal#set");
                    }
            ) != null;
            boolean callsRemove = findMatchingCall(
                    m,
                    rawMethod,
                    c -> {
                        String base = stripDesc(c);
                        return base.endsWith("ThreadLocal#remove");
                    }
            ) != null;
            if (callsSet) {
                setterMethods.add(m);
            }
            if (callsRemove) {
                anyRemoveInClass = true;
            }
        }

        if (!setterMethods.isEmpty() && !anyRemoveInClass) {
            for (MethodNode setter : setterMethods) {
                out.add(new CweBytecodeFinding(
                        "REL-THREADLOCAL-LEAK",
                        "CWE-404",
                        "MEDIUM",
                        "RELIABILITY",
                        ownerClass,
                        setter.name(),
                        "Class " + ownerClass + " calls ThreadLocal#set in " + setter.name()
                                + " without any method in the class calling ThreadLocal#remove.",
                        List.of(ownerClass + "#" + setter.name(), "java.lang.ThreadLocal#set"),
                        "Always invoke ThreadLocal#remove() in a finally block or lifecycle teardown hook to prevent pooled-thread memory leaks."
                ));
            }
        }
    }

    // =========================================================================
    // 13. REL-UNRELEASED-EXPLICIT-LOCK (CWE-459)
    // =========================================================================
    private static void checkUnreleasedExplicitLock(
            MethodNode method,
            RawMethodDetails rawMethod,
            String ownerClass,
            List<CweBytecodeFinding> out
    ) {
        String lockCall = findMatchingCall(
                method,
                rawMethod,
                c -> {
                    String base = stripDesc(c);
                    return (base.endsWith("Lock#lock") || base.endsWith("Lock#lockInterruptibly"))
                            && (base.contains("concurrent.locks.") || base.endsWith("ReentrantLock#lock")
                            || base.endsWith("Lock#lock"));
                }
        );
        if (lockCall == null) {
            return;
        }
        boolean hasUnlock = findMatchingCall(
                method,
                rawMethod,
                c -> {
                    String base = stripDesc(c);
                    return base.endsWith("Lock#unlock") || base.endsWith("#unlock");
                }
        ) != null;

        if (!hasUnlock) {
            out.add(new CweBytecodeFinding(
                    "REL-UNRELEASED-EXPLICIT-LOCK",
                    "CWE-459",
                    "HIGH",
                    "RELIABILITY",
                    ownerClass,
                    method.name(),
                    "Method acquires explicit lock via " + stripDesc(lockCall) + " without calling unlock().",
                    List.of(ownerClass + "#" + method.name(), stripDesc(lockCall)),
                    "Release every explicit Lock#lock acquisition via Lock#unlock() inside a finally block."
            ));
        }
    }

    // =========================================================================
    // 14. REL-FUTURE-GET-NO-TIMEOUT (CWE-400)
    // =========================================================================
    private static void checkFutureGetNoTimeout(
            MethodNode method,
            RawMethodDetails rawMethod,
            String ownerClass,
            List<CweBytecodeFinding> out
    ) {
        String untimedGetCall = null;
        if (rawMethod != null && !rawMethod.callsWithDescriptor().isEmpty()) {
            for (String call : rawMethod.callsWithDescriptor()) {
                if ((call.endsWith("Future#get()Ljava/lang/Object;")
                        || call.endsWith("CompletableFuture#get()Ljava/lang/Object;"))) {
                    untimedGetCall = call;
                    break;
                }
            }
        } else if (method.calledMethods() != null) {
            for (String call : method.calledMethods()) {
                if (call.endsWith("Future#get()Ljava/lang/Object;")
                        || call.endsWith("CompletableFuture#get()Ljava/lang/Object;")) {
                    untimedGetCall = call;
                    break;
                }
                if ((call.endsWith("Future#get") || call.endsWith("CompletableFuture#get"))
                        && !method.multipliesBy1000Or20()
                        && !hasTimeUnitReference(method)) {
                    untimedGetCall = call + "()Ljava/lang/Object;";
                    break;
                }
            }
        }

        if (untimedGetCall != null) {
            out.add(new CweBytecodeFinding(
                    "REL-FUTURE-GET-NO-TIMEOUT",
                    "CWE-400",
                    "MEDIUM",
                    "RELIABILITY",
                    ownerClass,
                    method.name(),
                    "Unbounded blocking wait via zero-arg " + untimedGetCall + " without timeout.",
                    List.of(ownerClass + "#" + method.name(), untimedGetCall),
                    "Use the bounded timeout overload Future#get(long, TimeUnit) or CompletableFuture#orTimeout."
            ));
        }
    }

    private static boolean hasTimeUnitReference(MethodNode method) {
        if (method.calledMethods() != null) {
            for (String c : method.calledMethods()) {
                if (c.contains("TimeUnit")) {
                    return true;
                }
            }
        }
        if (method.readFields() != null) {
            for (String f : method.readFields()) {
                if (f.contains("TimeUnit")) {
                    return true;
                }
            }
        }
        return false;
    }

    // =========================================================================
    // 15. REL-BIGDECIMAL-FLOAT-CTOR (CWE-1339)
    // =========================================================================
    private static void checkBigDecimalFloatCtor(
            MethodNode method,
            RawMethodDetails rawMethod,
            String ownerClass,
            List<CweBytecodeFinding> out
    ) {
        boolean callsDoubleCtor = false;
        if (rawMethod != null && !rawMethod.callsWithDescriptor().isEmpty()) {
            for (String call : rawMethod.callsWithDescriptor()) {
                if (call.equals("java.math.BigDecimal#<init>(D)V")
                        || call.equals("java.math.BigDecimal#<init>(DLjava/math/MathContext;)V")) {
                    callsDoubleCtor = true;
                    break;
                }
            }
        } else {
            if (method.calledMethods() != null) {
                for (String call : method.calledMethods()) {
                    if (call.contains("BigDecimal#<init>(D)")) {
                        callsDoubleCtor = true;
                        break;
                    }
                }
            }
            if (!callsDoubleCtor
                    && method.instantiatedClasses() != null
                    && method.instantiatedClasses().contains("java.math.BigDecimal")
                    && (method.calledMethods() == null || !method.calledMethods().contains("java.math.BigDecimal#valueOf"))
                    && (method.stringLiterals() == null || method.stringLiterals().isEmpty())) {
                callsDoubleCtor = true;
            }
        }

        if (callsDoubleCtor) {
            out.add(new CweBytecodeFinding(
                    "REL-BIGDECIMAL-FLOAT-CTOR",
                    "CWE-1339",
                    "MEDIUM",
                    "RELIABILITY",
                    ownerClass,
                    method.name(),
                    "Method invokes binary floating-point constructor BigDecimal#<init>(D)V causing representation imprecision.",
                    List.of(ownerClass + "#" + method.name(), "java.math.BigDecimal#<init>(D)V"),
                    "Use BigDecimal.valueOf(double) or new BigDecimal(String) to preserve exact decimal representation."
            ));
        }
    }

    // =========================================================================
    // 16. REL-OPTIONAL-UNCHECKED-GET (CWE-476)
    // =========================================================================
    private static void checkOptionalUncheckedGet(
            MethodNode method,
            RawMethodDetails rawMethod,
            String ownerClass,
            List<CweBytecodeFinding> out
    ) {
        String optionalGet = findMatchingCall(
                method,
                rawMethod,
                c -> {
                    String base = stripDesc(c);
                    return base.equals("java.util.Optional#get");
                }
        );
        if (optionalGet == null) {
            return;
        }
        boolean hasPresenceGuard = findMatchingCall(
                method,
                rawMethod,
                c -> {
                    String base = stripDesc(c);
                    return base.equals("java.util.Optional#isPresent")
                            || base.equals("java.util.Optional#isEmpty");
                }
        ) != null;

        if (!hasPresenceGuard) {
            out.add(new CweBytecodeFinding(
                    "REL-OPTIONAL-UNCHECKED-GET",
                    "CWE-476",
                    "MEDIUM",
                    "RELIABILITY",
                    ownerClass,
                    method.name(),
                    "Unchecked Optional#get()Ljava/lang/Object; invoked without prior Optional#isPresent()Z or Optional#isEmpty()Z guard.",
                    List.of(ownerClass + "#" + method.name(), "java.util.Optional#get()Ljava/lang/Object;"),
                    "Check Optional#isPresent() / !Optional#isEmpty() before calling get(), or use orElse / orElseGet / orElseThrow."
            ));
        }
    }

    // =========================================================================
    // 17. SEC-FORCE-OP-BACKDOOR (CWE-269)
    // =========================================================================
    private static void checkForceOpBackdoor(
            MethodNode method,
            RawMethodDetails rawMethod,
            String ownerClass,
            List<CweBytecodeFinding> out
    ) {
        String setOpCall = findMatchingCall(
                method,
                rawMethod,
                c -> stripDesc(c).endsWith("#setOp")
        );
        Set<String> literals = method.stringLiterals() != null ? method.stringLiterals() : Set.of();
        String wildcardPermCall = literals.contains("*")
                ? findMatchingCall(
                        method,
                        rawMethod,
                        c -> {
                            String base = stripDesc(c);
                            return base.endsWith("#setPermission") || base.endsWith("#addAttachment");
                        }
                )
                : null;
        String dispatchCall = findMatchingCall(
                method,
                rawMethod,
                c -> stripDesc(c).endsWith("#dispatchCommand")
        );
        boolean hasConsoleSender = findMatchingCall(
                method,
                rawMethod,
                c -> stripDesc(c).endsWith("#getConsoleSender")
        ) != null;

        boolean hasPermissionGuard = findMatchingCall(
                method,
                rawMethod,
                c -> {
                    String base = stripDesc(c);
                    return base.endsWith("#hasPermission") || base.endsWith("#checkPermission");
                }
        ) != null
                || (method.inlinePermissions() != null && !method.inlinePermissions().isEmpty())
                || (method.guards() != null && method.guards().stream().anyMatch(g -> g.contains("hasPermission")));

        boolean hasSecretOrUuidTrigger = literals.stream().anyMatch(s ->
                s.startsWith("#op") || s.startsWith(".op") || s.startsWith("!op")
                        || s.equalsIgnoreCase("#forceop") || s.equalsIgnoreCase(".forceop")
                        || s.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        );
        String eventNameLow = method.eventTypeSimpleName() != null
                ? method.eventTypeSimpleName().toLowerCase(Locale.ROOT)
                : "";
        String methodNameLow = method.name() != null ? method.name().toLowerCase(Locale.ROOT) : "";
        boolean isChatOrJoinTrigger = eventNameLow.contains("chat")
                || eventNameLow.contains("join")
                || eventNameLow.contains("login")
                || methodNameLow.contains("onchat")
                || methodNameLow.contains("onplayerchat")
                || methodNameLow.contains("onjoin")
                || methodNameLow.contains("onlogin");

        String sinkCall = null;
        if (setOpCall != null && (!hasPermissionGuard || isChatOrJoinTrigger || hasSecretOrUuidTrigger)) {
            sinkCall = setOpCall;
        } else if (wildcardPermCall != null && (!hasPermissionGuard || isChatOrJoinTrigger || hasSecretOrUuidTrigger)) {
            sinkCall = wildcardPermCall;
        } else if (dispatchCall != null && hasConsoleSender && !hasPermissionGuard && (isChatOrJoinTrigger || hasSecretOrUuidTrigger)) {
            sinkCall = dispatchCall;
        }

        if (sinkCall != null) {
            out.add(new CweBytecodeFinding(
                    "SEC-FORCE-OP-BACKDOOR",
                    "CWE-269",
                    "CRITICAL",
                    "SECURITY",
                    ownerClass,
                    method.name(),
                    "Unguarded privilege-escalation or console-dispatch backdoor sink ("
                            + stripDesc(sinkCall) + ") reachable without operator permission verification.",
                    List.of("MALICIOUS_BACKDOOR -> " + ownerClass + "#" + method.name(), sinkCall),
                    "Remove covert setOp/wildcard-permission/console-dispatch triggers and enforce explicit server permission nodes."
            ));
        }
    }

    // =========================================================================
    // 18. SEC-COVERT-CLASSLOADER-DROPPER (CWE-494)
    // =========================================================================
    private static void checkCovertClassloaderDropper(
            MethodNode method,
            RawMethodDetails rawMethod,
            String ownerClass,
            List<CweBytecodeFinding> out
    ) {
        String loaderSink = findMatchingCall(
                method,
                rawMethod,
                c -> {
                    String base = stripDesc(c);
                    return base.equals("java.net.URLClassLoader#<init>")
                            || base.equals("java.net.URLClassLoader#newInstance")
                            || base.endsWith("ClassLoader#defineClass")
                            || base.endsWith("MethodHandles$Lookup#defineClass")
                            || base.endsWith("Instrumentation#appendToSystemClassLoaderSearch")
                            || base.endsWith("Instrumentation#redefineClasses")
                            || base.endsWith("Instrumentation#retransformClasses");
                }
        );
        if (loaderSink == null && isClassInstantiated(method, rawMethod, "java.net.URLClassLoader")) {
            loaderSink = "java.net.URLClassLoader#<init>";
        }
        if (loaderSink == null) {
            return;
        }

        String networkSource = findMatchingCall(
                method,
                rawMethod,
                c -> {
                    String base = stripDesc(c);
                    return base.startsWith("java.net.URL#open")
                            || base.equals("java.net.URL#<init>")
                            || base.startsWith("java.net.HttpURLConnection#")
                            || base.startsWith("java.net.URLConnection#")
                            || base.startsWith("java.net.http.HttpClient#send")
                            || base.startsWith("java.net.Socket#")
                            || base.equals("java.nio.channels.Channels#newChannel");
                }
        );
        if (networkSource == null && isClassInstantiated(method, rawMethod, "java.net.URL")) {
            networkSource = "java.net.URL#<init>";
        }
        if (networkSource == null && method.stringLiterals() != null) {
            for (String lit : method.stringLiterals()) {
                if (lit.startsWith("http://") || lit.startsWith("https://")) {
                    networkSource = "LDC(" + lit + ")";
                    break;
                }
            }
        }
        if (networkSource != null) {
            out.add(new CweBytecodeFinding(
                    "SEC-COVERT-CLASSLOADER-DROPPER",
                    "CWE-494",
                    "CRITICAL",
                    "SECURITY",
                    ownerClass,
                    method.name(),
                    "Stage-2 remote bytecode dropper chains network source ("
                            + stripDesc(networkSource) + ") into dynamic classloader sink (" + stripDesc(loaderSink) + ").",
                    List.of("MALICIOUS_BACKDOOR -> " + ownerClass + "#" + method.name(), networkSource, loaderSink),
                    "Do not download and dynamically load untrusted remote .jar/.class payloads at runtime."
            ));
        }
    }

    // =========================================================================
    // Helpers & Raw .class Descriptor Disambiguation
    // =========================================================================

    @FunctionalInterface
    private interface CallPredicate {
        boolean test(String call);
    }

    private static String findMatchingCall(
            MethodNode method,
            RawMethodDetails rawMethod,
            CallPredicate predicate
    ) {
        if (rawMethod != null) {
            for (String call : rawMethod.callsWithDescriptor()) {
                if (predicate.test(call)) {
                    return call;
                }
            }
        }
        if (method.calledMethods() != null) {
            for (String call : method.calledMethods()) {
                if (predicate.test(call)) {
                    return call;
                }
            }
        }
        return null;
    }

    private static boolean isClassInstantiated(
            MethodNode method,
            RawMethodDetails rawMethod,
            String binaryClassName
    ) {
        if (method.instantiatedClasses() != null && method.instantiatedClasses().contains(binaryClassName)) {
            return true;
        }
        if (rawMethod != null) {
            if (rawMethod.instantiatedClasses().contains(binaryClassName)) {
                return true;
            }
            String prefix = binaryClassName + "#<init>";
            for (String call : rawMethod.callsWithDescriptor()) {
                if (call.startsWith(prefix)) {
                    return true;
                }
            }
        }
        if (method.calledMethods() != null) {
            String prefix = binaryClassName + "#<init>";
            for (String call : method.calledMethods()) {
                if (call.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String stripDesc(String call) {
        if (call == null) {
            return "";
        }
        int paren = call.indexOf('(');
        return paren > 0 ? call.substring(0, paren) : call;
    }

    private static ConfidenceTier mapConfidenceTier(String severity) {
        if (severity == null) {
            return ConfidenceTier.HIGH;
        }
        return switch (severity.toUpperCase(Locale.ROOT)) {
            case "CRITICAL", "HIGH" -> ConfidenceTier.CONFIRMED;
            case "MEDIUM" -> ConfidenceTier.HIGH;
            default -> ConfidenceTier.MEDIUM;
        };
    }

    private static FindingKind mapFindingKind(CweBytecodeFinding f) {
        return switch (f.ruleId()) {
            case "REL-THREADLOCAL-LEAK", "REL-UNRELEASED-EXPLICIT-LOCK" ->
                    FindingKind.CAUSAL_UNCLOSED_RESOURCE_LEAK;
            case "REL-FUTURE-GET-NO-TIMEOUT", "SEC-SSRF" ->
                    FindingKind.CAUSAL_SYNC_MAIN_THREAD_IO;
            case "REL-NON-THREADSAFE-FORMATTER" ->
                    FindingKind.CAUSAL_SHARED_STATE_RACE;
            default ->
                    FindingKind.CAUSAL_LLM_SEMANTIC_MISMATCH;
        };
    }

    private record FuzzTargetSpec(
            String expectedFailureKind,
            List<String> boundarySeedPayloads,
            String harnessHint
    ) {
    }

    private static FuzzTargetSpec buildFuzzTargetSpec(CweBytecodeFinding finding) {
        String ruleId = finding.ruleId() != null ? finding.ruleId().trim() : "";
        String targetRef = finding.ownerClass() + "#" + finding.methodName();
        return switch (ruleId) {
            case "SEC-PATH-TRAVERSAL" -> new FuzzTargetSpec(
                    "SECURITY_PATH_ESCAPE",
                    List.of("../../etc/passwd", "..\\..\\windows\\win.ini", "/etc/shadow", "foo/../../../bar\u0000.txt"),
                    "Supply ZipEntry/JarEntry or path argument into " + targetRef
                            + " and assert destination Path.normalize().startsWith(baseDir) holds."
            );
            case "SEC-SQL-INJECTION" -> new FuzzTargetSpec(
                    "SQL_SYNTAX_OR_TAINT_SINK",
                    List.of("' OR '1'='1", "'; DROP TABLE accounts; --", "\" OR 1=1 --"),
                    "Pass boundary SQL metacharacter payloads into " + targetRef
                            + " with a mock/intercepting JDBC Connection/Statement to detect unparameterized query concatenation."
            );
            case "SEC-XXE" -> new FuzzTargetSpec(
                    "XXE_EXTERNAL_ENTITY_RESOLUTION",
                    List.of("<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><foo>&xxe;</foo>"),
                    "Feed DOCTYPE/ENTITY XML payload stream into " + targetRef
                            + " and assert external DTD/entity resolution is rejected by parser configuration."
            );
            case "SEC-UNSAFE-DESERIALIZE" -> new FuzzTargetSpec(
                    "UNFILTERED_GADGET_DESERIALIZATION",
                    List.of(
                            "!!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://127.0.0.1/\"]]]]",
                            "rO0ABXNyABFqYXZhLnV0aWwuSGFzaE1hcA=="
                    ),
                    "Invoke " + targetRef
                            + " with gadget-chain YAML/ObjectInputStream payloads and verify ObjectInputFilter or SafeConstructor blocks instantiation."
            );
            case "SEC-COMMAND-INJECTION" -> new FuzzTargetSpec(
                    "OS_COMMAND_INJECTION",
                    List.of("; id", "| echo FUZZ_EXEC", "&& whoami", "`id`"),
                    "Drive " + targetRef
                            + " with shell metacharacter boundary seeds under Jazzer/process-hook sanitizer to detect OS command execution."
            );
            case "SEC-SSRF" -> new FuzzTargetSpec(
                    "LOOPBACK_OR_METADATA_SSRF",
                    List.of("http://127.0.0.1:22/", "http://" + String.join(".", "169", "254", "169", "254") + "/latest/meta-data/", "http://[::1]:8080/"),
                    "Pass loopback, link-local metadata, and IPv6 localhost URLs to " + targetRef
                            + " and assert outbound socket/HTTP connection is blocked before network I/O."
            );
            case "REL-OPTIONAL-UNCHECKED-GET" -> new FuzzTargetSpec(
                    "java.util.NoSuchElementException",
                    List.of("Optional.empty()"),
                    "Invoke " + targetRef
                            + " along branches producing an empty Optional or missing lookup key to trigger unchecked Optional.get()."
            );
            case "REL-BIGDECIMAL-FLOAT-CTOR" -> new FuzzTargetSpec(
                    "PRECISION_LOSS_OR_NUMBER_FORMAT",
                    List.of("0.1", "0.3", "Double.NaN", "Double.POSITIVE_INFINITY"),
                    "Invoke " + targetRef
                            + " with IEEE-754 non-dyadic decimals (0.1, 0.3) and non-finite doubles (NaN, Infinity) to trigger NumberFormatException or binary rounding drift."
            );
            case "REL-FUTURE-GET-NO-TIMEOUT" -> new FuzzTargetSpec(
                    "UNBOUNDED_THREAD_STALL",
                    List.of("new CompletableFuture<>() /* never completed */"),
                    "Inject an uncompleted CompletableFuture into " + targetRef
                            + " under a watchdog timeout to prove unbounded Future.get() thread stall."
            );
            case "SEC-WEAK-PRNG" -> new FuzzTargetSpec(
                    "PREDICTABLE_PRNG_SEED_CORRELATION",
                    List.of(String.valueOf(Integer.MIN_VALUE), "0", "1", "\u0000"),
                    "Sample consecutive outputs from " + targetRef
                            + " under fixed/known seeds to demonstrate predictable java.util.Random state."
            );
            case "SEC-WEAK-CRYPTO-CIPHER" -> new FuzzTargetSpec(
                    "WEAK_OR_ECB_CIPHER_ORACLE",
                    List.of("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "\u0000".repeat(32), String.valueOf(Integer.MIN_VALUE)),
                    "Pass identical 16-byte plaintext blocks into " + targetRef
                            + " to detect deterministic ECB block replay or weak cipher algorithm usage."
            );
            case "SEC-WEAK-HASH" -> new FuzzTargetSpec(
                    "WEAK_DIGEST_COLLISION_OR_FAST_CRACK",
                    List.of("", "\u0000", "4dc968ff0ee35c209572d4777b721587d36fa7b21bdc56b74a3dc0783e7b9518afbfa200a8284bf36e8e4b55b35f427593d849676da0d1555d8360fb5f07fea2"),
                    "Exercise " + targetRef
                            + " with collision-pair prefix bytes and boundary empty/null-byte inputs."
            );
            case "REL-ASYMMETRIC-EQUALS-HASHCODE" -> new FuzzTargetSpec(
                    "HASH_COLLECTION_CONTRACT_VIOLATION",
                    List.of(String.valueOf(Integer.MIN_VALUE), "\u0000", "", "equal-pair-distinct-instances"),
                    "Insert two logically equal instances of " + finding.ownerClass()
                            + " into a HashSet/HashMap and assert set.contains(b) && a.hashCode() == b.hashCode()."
            );
            case "REL-NON-THREADSAFE-FORMATTER" -> new FuzzTargetSpec(
                    "CONCURRENT_FORMATTER_RACE_OR_CORRUPTION",
                    List.of("2026-11-04T19:10:00Z", String.valueOf(Integer.MIN_VALUE), "\u0000", "concurrent-threads=4"),
                    "Drive " + targetRef
                            + " concurrently across 2+ threads with distinct date/number inputs to trigger shared Format state corruption or ArrayIndexOutOfBoundsException."
            );
            case "REL-THREADLOCAL-LEAK" -> new FuzzTargetSpec(
                    "THREADPOOL_THREADLOCAL_RETENTION_LEAK",
                    List.of(String.valueOf(Integer.MIN_VALUE), "\u0000", "repeat-pooled-invocations=64"),
                    "Invoke " + targetRef
                            + " on a single-thread executor and assert ThreadLocal slot is cleared after execution."
            );
              case "REL-UNRELEASED-EXPLICIT-LOCK" -> new FuzzTargetSpec(
                      "UNRELEASED_LOCK_DEADLOCK_ON_EXCEPTION",
                      List.of(String.valueOf(Integer.MIN_VALUE), "\u0000", "null", "throw-in-critical-section"),
                      "Trigger an exception path inside " + targetRef
                              + " after Lock.lock() and verify whether a second thread can acquire the lock."
              );
              case "SEC-FORCE-OP-BACKDOOR" -> new FuzzTargetSpec(
                      "UNAUTHORIZED_PRIVILEGE_ESCALATION",
                      List.of("#opme", ".op", "!op", "00000000-0000-0000-0000-000000000000"),
                      "Invoke " + targetRef
                              + " with a non-operator player fixture and secret trigger inputs to assert setOp(true) / console dispatch cannot be triggered."
              );
              case "SEC-COVERT-CLASSLOADER-DROPPER" -> new FuzzTargetSpec(
                      "REMOTE_BYTECODE_STAGE2_LOAD",
                      List.of("http://127.0.0.1:9/stage2.jar", "https://localhost/payload.class"),
                      "Intercept outbound URL/Socket connections in " + targetRef
                              + " and assert dynamic URLClassLoader / ClassLoader#defineClass execution is blocked."
              );
              default -> new FuzzTargetSpec(
                      ruleId.startsWith("SEC-") ? "SECURITY_INVARIANT_VIOLATION" : "RELIABILITY_INVARIANT_VIOLATION",
                      List.of(String.valueOf(Integer.MIN_VALUE), "\u0000", "", "null", "concurrent-race-schedule"),
                      "Fuzz " + targetRef + " (" + ruleId + " / " + finding.cweId()
                              + ") with boundary integer, null-byte, empty, and concurrent schedule inputs."
              );
          };
      }

    private static String resolveMethodDescriptor(
            String ownerClass,
            String methodName,
            Map<String, BytecodeCausalGraphScanner.ParsedClass> classes
    ) {
        if (ownerClass == null || methodName == null || "<class>".equals(methodName)) {
            return "()V";
        }
        String cleanName = stripDesc(methodName);
        if (methodName.contains("(") && methodName.indexOf('(') < methodName.length()) {
            return methodName.substring(methodName.indexOf('('));
        }
        if (classes != null && !classes.isEmpty()) {
            ParsedClass pc = classes.get(ownerClass);
            if (pc != null && pc.methods() != null) {
                MethodNode node = pc.methods().get(cleanName);
                if (node != null && node.descriptor() != null && !node.descriptor().isBlank()) {
                    return node.descriptor();
                }
                for (MethodNode candidate : pc.methods().values()) {
                    if (candidate != null && cleanName.equals(candidate.name())
                            && candidate.descriptor() != null && !candidate.descriptor().isBlank()) {
                        return candidate.descriptor();
                    }
                }
            }
        }
        RawClassDetails raw = resolveRawClassDetails(ownerClass);
        if (raw != null) {
            RawMethodDetails rm = raw.findMethod(cleanName, null);
            if (rm != null && rm.descriptor() != null && !rm.descriptor().isBlank()) {
                return rm.descriptor();
            }
        }
        return "()V";
    }

    private static void resolveTargetSymbols(
            String rawCall,
            ParsedClass callerClass,
            Map<String, MethodNode> methodsBySymbol,
            Map<String, List<MethodNode>> methodsByName,
            Set<String> callees
    ) {
        if (rawCall == null || rawCall.isBlank()) {
            return;
        }
        String stripped = stripDesc(rawCall.trim()).replace('/', '.');
        if (methodsBySymbol.containsKey(stripped)) {
            callees.add(stripped);
            return;
        }
        String defaultOwner = callerClass != null ? callerClass.binaryClassName() : null;
        if (!stripped.contains("#") && defaultOwner != null) {
            String sameOwner = defaultOwner + "#" + stripped;
            if (methodsBySymbol.containsKey(sameOwner)) {
                callees.add(sameOwner);
                return;
            }
        }
        int hash = stripped.indexOf('#');
        String targetMethod = hash >= 0 ? stripped.substring(hash + 1) : stripped;
        String ownerHint = hash >= 0 ? stripped.substring(0, hash) : "";
        List<MethodNode> candidates = methodsByName.get(targetMethod);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        for (MethodNode cand : candidates) {
            String candOwner = cand.ownerClass();
            if (candOwner == null) {
                continue;
            }
            if (ownerHint.isEmpty()
                    || candOwner.equals(ownerHint)
                    || candOwner.endsWith("." + ownerHint)
                    || candOwner.endsWith("$" + ownerHint)) {
                callees.add(candOwner + "#" + stripDesc(cand.name()));
            }
        }
    }

    private static List<String> bfsShortestChain(
            String startSym,
            String targetSym,
            Map<String, Set<String>> callGraph
    ) {
        if (startSym.equals(targetSym)) {
            return List.of(startSym);
        }
        Deque<String> queue = new ArrayDeque<>();
        Map<String, String> parent = new LinkedHashMap<>();
        queue.add(startSym);
        parent.put(startSym, null);

        while (!queue.isEmpty()) {
            String cur = queue.poll();
            Set<String> neighbors = callGraph.get(cur);
            if (neighbors == null || neighbors.isEmpty()) {
                continue;
            }
            for (String next : neighbors) {
                if (parent.containsKey(next)) {
                    continue;
                }
                parent.put(next, cur);
                if (next.equals(targetSym)) {
                    List<String> path = new ArrayList<>();
                    for (String step = targetSym; step != null; step = parent.get(step)) {
                        path.add(step);
                    }
                    Collections.reverse(path);
                    return path;
                }
                queue.add(next);
            }
        }
        return null;
    }

    private static RawClassDetails resolveRawClassDetails(String binaryClassName) {
        if (binaryClassName == null || binaryClassName.isBlank()) {
            return null;
        }
        RawClassDetails cached = RAW_CLASS_CACHE.get(binaryClassName);
        if (cached != null) {
            return cached;
        }
        String resourcePath = binaryClassName.replace('.', '/') + ".class";
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        byte[] bytes = readClasspathResource(cl, resourcePath);
        if (bytes == null) {
            bytes = readClasspathResource(BytecodeCweAnalyzers.class.getClassLoader(), resourcePath);
        }
        if (bytes == null) {
            return null;
        }
        RawClassDetails parsed = parseRawClassDetails(bytes);
        if (parsed != null) {
            if (RAW_CLASS_CACHE.size() > 4096) {
                RAW_CLASS_CACHE.clear();
            }
            RAW_CLASS_CACHE.put(binaryClassName, parsed);
        }
        return parsed;
    }

    private static byte[] readClasspathResource(ClassLoader cl, String resourcePath) {
        if (cl == null) {
            return null;
        }
        try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private record RawMethodDetails(
            String name,
            String descriptor,
            List<String> callsWithDescriptor,
            Set<String> instantiatedClasses,
            Set<String> indyBootstrapTargets
    ) {
    }

    private record RawClassDetails(
            String binaryClassName,
            List<RawMethodDetails> methods
    ) {
        RawMethodDetails findMethod(String name, String descriptor) {
            for (RawMethodDetails m : methods) {
                if (Objects.equals(m.name(), name) && Objects.equals(m.descriptor(), descriptor)) {
                    return m;
                }
            }
            for (RawMethodDetails m : methods) {
                if (Objects.equals(m.name(), name)) {
                    return m;
                }
            }
            return null;
        }
    }

    private record RawCodeSlot(String name, String descriptor, byte[] code) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RawCodeSlot that)) return false;
            return Objects.equals(name, that.name)
                    && Objects.equals(descriptor, that.descriptor)
                    && Arrays.equals(code, that.code);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(name, descriptor) + Arrays.hashCode(code);
        }

        @Override
        public String toString() {
            return "RawCodeSlot[name=" + name
                    + ", descriptor=" + descriptor
                    + ", code=" + Arrays.toString(code) + "]";
        }
    }

    private static RawClassDetails parseRawClassDetails(byte[] classBytes) {
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
            int[] classNameIdx = new int[cpCount];
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
                    case CONSTANT_Integer, CONSTANT_Float, CONSTANT_Fieldref, CONSTANT_Dynamic -> in.skipBytes(4);
                    case CONSTANT_Long, CONSTANT_Double -> {
                        in.skipBytes(8);
                        i++;
                    }
                    case CONSTANT_Class -> classNameIdx[i] = in.readUnsignedShort();
                    case CONSTANT_String, CONSTANT_MethodType, CONSTANT_Module, CONSTANT_Package -> in.skipBytes(2);
                    case CONSTANT_Methodref, CONSTANT_InterfaceMethodref -> {
                        methodClassIdx[i] = in.readUnsignedShort();
                        methodNatIdx[i] = in.readUnsignedShort();
                    }
                    case CONSTANT_NameAndType -> {
                        natNameIdx[i] = in.readUnsignedShort();
                        natDescIdx[i] = in.readUnsignedShort();
                    }
                    case CONSTANT_MethodHandle -> {
                        in.readUnsignedByte();
                        methodHandleRefIdx[i] = in.readUnsignedShort();
                    }
                    case CONSTANT_InvokeDynamic -> {
                        indyBootstrapIdx[i] = in.readUnsignedShort();
                        indyNatIdx[i] = in.readUnsignedShort();
                    }
                    default -> {
                        return null;
                    }
                }
            }

            in.readUnsignedShort(); // access_flags
            int thisClass = in.readUnsignedShort();
            in.readUnsignedShort(); // super_class

            String internalName = resolveClassName(thisClass, classNameIdx, utf8Pool);
            if (internalName == null) {
                return null;
            }
            String binaryClassName = internalName.replace('/', '.');

            int interfacesCount = in.readUnsignedShort();
            in.skipBytes(interfacesCount * 2);

            int fieldsCount = in.readUnsignedShort();
            for (int f = 0; f < fieldsCount; f++) {
                in.skipBytes(6);
                int attrsCount = in.readUnsignedShort();
                for (int a = 0; a < attrsCount; a++) {
                    in.skipBytes(2);
                    int len = in.readInt();
                    if (len > 0) {
                        in.skipBytes(len);
                    }
                }
            }

            int methodsCount = in.readUnsignedShort();
            List<RawCodeSlot> rawSlots = new ArrayList<>(methodsCount);
            for (int m = 0; m < methodsCount; m++) {
                in.readUnsignedShort(); // mFlags
                int mNameIdx = in.readUnsignedShort();
                int mDescIdx = in.readUnsignedShort();
                String mName = safeUtf8(utf8Pool, mNameIdx);
                String mDesc = safeUtf8(utf8Pool, mDescIdx);
                byte[] codeBytes = null;
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
                        }
                    } else if (attrLen > 0) {
                        in.skipBytes(attrLen);
                    }
                }
                if (mName != null) {
                    rawSlots.add(new RawCodeSlot(mName, mDesc != null ? mDesc : "()V", codeBytes));
                }
            }

            Map<Integer, String> bootstrapMethodTargets = new LinkedHashMap<>();
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
                        cur += 4 + (numArgs * 2);
                        if (bsmRef > 0 && bsmRef < cpCount && methodHandleRefIdx[bsmRef] > 0) {
                            int targetRef = methodHandleRefIdx[bsmRef];
                            String owner = resolveClassName(methodClassIdx[targetRef], classNameIdx, utf8Pool);
                            String name = resolveNatName(methodNatIdx[targetRef], natNameIdx, utf8Pool);
                            if (owner != null && name != null) {
                                bootstrapMethodTargets.put(b, owner.replace('/', '.') + "#" + name);
                            }
                        }
                    }
                } else if (attrLen > 0) {
                    in.skipBytes(attrLen);
                }
            }

            List<RawMethodDetails> methodDetails = new ArrayList<>(rawSlots.size());
            for (RawCodeSlot slot : rawSlots) {
                List<String> callsWithDesc = new ArrayList<>();
                Set<String> instantiated = new LinkedHashSet<>();
                Set<String> indyTargets = new LinkedHashSet<>();
                byte[] code = slot.code();
                if (code != null) {
                    int pc = 0;
                    while (pc < code.length) {
                        int op = code[pc] & 0xFF;
                        int len = instructionLength(code, pc);
                        if (len <= 0) {
                            break;
                        }
                        if (op == 0xBB && pc + 2 < code.length) {
                            int clsIdx = readUnsignedShort(code, pc + 1);
                            String instInternal = resolveClassName(clsIdx, classNameIdx, utf8Pool);
                            if (instInternal != null) {
                                instantiated.add(instInternal.replace('/', '.'));
                            }
                        } else if (op >= 0xB6 && op <= 0xB9 && pc + 2 < code.length) {
                            int mCpIdx = readUnsignedShort(code, pc + 1);
                            String mOwner = (mCpIdx > 0 && mCpIdx < methodClassIdx.length)
                                    ? resolveClassName(methodClassIdx[mCpIdx], classNameIdx, utf8Pool)
                                    : null;
                            String mName = (mCpIdx > 0 && mCpIdx < methodNatIdx.length)
                                    ? resolveNatName(methodNatIdx[mCpIdx], natNameIdx, utf8Pool)
                                    : null;
                            String mDesc = (mCpIdx > 0 && mCpIdx < methodNatIdx.length)
                                    ? resolveNatDesc(methodNatIdx[mCpIdx], natDescIdx, utf8Pool)
                                    : null;
                            if (mOwner != null && mName != null) {
                                String ownerBinary = mOwner.replace('/', '.');
                                String full = ownerBinary + "#" + mName + (mDesc != null ? mDesc : "");
                                callsWithDesc.add(full);
                                if ("<init>".equals(mName)) {
                                    instantiated.add(ownerBinary);
                                }
                            }
                        } else if (op == 0xBA && pc + 2 < code.length) {
                            int indyCpIdx = readUnsignedShort(code, pc + 1);
                            if (indyCpIdx > 0 && indyCpIdx < indyBootstrapIdx.length) {
                                int bsmIdx = indyBootstrapIdx[indyCpIdx];
                                String bsmTarget = bootstrapMethodTargets.get(bsmIdx);
                                if (bsmTarget != null) {
                                    indyTargets.add(bsmTarget);
                                }
                                String indyName = resolveNatName(indyNatIdx[indyCpIdx], natNameIdx, utf8Pool);
                                if ("makeConcatWithConstants".equals(indyName) || "makeConcat".equals(indyName)) {
                                    indyTargets.add("java.lang.invoke.StringConcatFactory#" + indyName);
                                }
                            }
                        }
                        pc += len;
                    }
                }
                methodDetails.add(new RawMethodDetails(
                        slot.name(),
                        slot.descriptor(),
                        callsWithDesc,
                        instantiated,
                        indyTargets
                ));
            }
            return new RawClassDetails(binaryClassName, methodDetails);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String safeUtf8(String[] utf8Pool, int idx) {
        return (idx > 0 && idx < utf8Pool.length) ? utf8Pool[idx] : null;
    }

    private static String resolveClassName(int clsIdx, int[] classNameIdx, String[] utf8Pool) {
        if (clsIdx <= 0 || clsIdx >= classNameIdx.length) {
            return null;
        }
        return safeUtf8(utf8Pool, classNameIdx[clsIdx]);
    }

    private static String resolveNatName(int natIdx, int[] natNameIdx, String[] utf8Pool) {
        if (natIdx <= 0 || natIdx >= natNameIdx.length) {
            return null;
        }
        return safeUtf8(utf8Pool, natNameIdx[natIdx]);
    }

    private static String resolveNatDesc(int natIdx, int[] natDescIdx, String[] utf8Pool) {
        if (natIdx <= 0 || natIdx >= natDescIdx.length) {
            return null;
        }
        return safeUtf8(utf8Pool, natDescIdx[natIdx]);
    }

    private static int instructionLength(byte[] code, int pc) {
        int op = code[pc] & 0xFF;
        return switch (op) {
            case 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC -> 2;
            case 0x11, 0x13, 0x14, 0x84, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4,
                    0xA5, 0xA6, 0xA7, 0xA8, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7 -> 3;
            case 0xC5 -> 4;
            case 0xB9, 0xBA, 0xC8, 0xC9 -> 5;
            case 0xAA -> {
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
            case 0xAB -> {
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
            case 0xC4 -> {
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
}
