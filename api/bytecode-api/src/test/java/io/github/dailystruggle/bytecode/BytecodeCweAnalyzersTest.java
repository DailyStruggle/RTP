package io.github.dailystruggle.bytecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.CausalFinding;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.MethodNode;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.ParsedAnnotations;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.ParsedClass;
import io.github.dailystruggle.bytecode.BytecodeCweAnalyzers.CweBytecodeFinding;
import io.github.dailystruggle.bytecode.BytecodeCweAnalyzers.DirectedFuzzTarget;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.JarEntry;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import javax.crypto.Cipher;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLInputFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link BytecodeCweAnalyzers} detects all 16 bytecode CWE security and
 * reliability rules on vulnerable fixtures and produces zero findings on safe fixtures.
 *
 * @governedBy ADR-115 corpus-atlas-ADR-002 leaffuzz-ADR-001
 * @trace REQ-CFG-ATLAS-001 REQ-SLEUTH-FUZZ-004
 */
@DisplayName("BytecodeCweAnalyzers 16-Rule Security & Reliability Suite (REQ-CFG-ATLAS-001, REQ-SLEUTH-FUZZ-004)")
@SuppressWarnings("PMD")
class BytecodeCweAnalyzersTest {

    // =========================================================================
    // 1. SEC-PATH-TRAVERSAL (CWE-22) Fixtures
    // =========================================================================
    static final class VulnerablePathTraversal {
        Path extract(Path root, ZipEntry entry) {
            return root.resolve(entry.getName());
        }
    }

    static final class SafePathTraversal {
        Path extract(Path root, ZipEntry entry) {
            Path target = root.resolve(entry.getName()).normalize();
            if (!target.startsWith(root)) {
                throw new SecurityException("Zip-Slip prevented");
            }
            return target;
        }
    }

    // =========================================================================
    // 2. SEC-SQL-INJECTION (CWE-89) Fixtures
    // =========================================================================
    static final class VulnerableSqlInjection {
        PreparedStatement queryConcat(Connection conn, String user) throws Exception {
            return conn.prepareStatement("SELECT * FROM players WHERE name = '" + user + "'");
        }

        void queryBuilder(Statement stmt, String table) throws Exception {
            StringBuilder sb = new StringBuilder("DELETE FROM ");
            sb.append(table);
            stmt.executeUpdate(sb.toString());
        }
    }

    static final class SafeSqlParameterized {
        PreparedStatement querySafe(Connection conn, String user) throws Exception {
            PreparedStatement ps = conn.prepareStatement("SELECT * FROM players WHERE name = ?");
            ps.setString(1, user);
            return ps;
        }
    }

    // =========================================================================
    // 3. SEC-XXE (CWE-611) Fixtures
    // =========================================================================
    static final class VulnerableXxe {
        DocumentBuilderFactory createFactory() {
            return DocumentBuilderFactory.newInstance();
        }
    }

    static final class SafeXxe {
        DocumentBuilderFactory createFactory() throws Exception {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return dbf;
        }
    }

    // =========================================================================
    // 4. SEC-UNSAFE-DESERIALIZE (CWE-502) Fixtures
    // =========================================================================
    static final class VulnerableDeserialize {
        Object readPayload(ObjectInputStream ois) throws Exception {
            return ois.readObject();
        }
    }

    static final class SafeDeserialize {
        Object readPayload(ObjectInputStream ois, ObjectInputFilter filter) throws Exception {
            ois.setObjectInputFilter(filter);
            return ois.readObject();
        }
    }

    // =========================================================================
    // 5. SEC-WEAK-PRNG (CWE-330) Fixtures
    // =========================================================================
    static final class VulnerableWeakPrng {
        String generateSessionToken() {
            Random rng = new Random();
            return Long.toHexString(rng.nextLong());
        }

        int nextPasswordSalt() {
            return ThreadLocalRandom.current().nextInt();
        }
    }

    static final class SafeStrongPrng {
        String generateSessionToken() {
            SecureRandom rng = new SecureRandom();
            return Long.toHexString(rng.nextLong());
        }

        int pickRandomBiomeOffset() {
            return ThreadLocalRandom.current().nextInt(16);
        }
    }

    // =========================================================================
    // 6. SEC-COMMAND-INJECTION (CWE-78) Fixtures
    // =========================================================================
    static final class VulnerableCommandInjection {
        @SuppressWarnings("deprecation")
        Process runShell(String arg) throws Exception {
            return Runtime.getRuntime().exec("sh -c " + arg);
        }

        Process runBuilder(String cmd) throws Exception {
            return new ProcessBuilder(cmd).start();
        }
    }

    static final class SafeNoProcessExec {
        String sanitizeInput(String arg) {
            return arg == null ? "" : arg.trim();
        }
    }

    // =========================================================================
    // 7. SEC-WEAK-CRYPTO-CIPHER (CWE-327) Fixtures
    // =========================================================================
    static final class VulnerableWeakCipher {
        Cipher createCipher() throws Exception {
            return Cipher.getInstance("AES/ECB/PKCS5Padding");
        }
    }

    static final class SafeStrongCipher {
        Cipher createCipher() throws Exception {
            return Cipher.getInstance("AES/GCM/NoPadding");
        }
    }

    // =========================================================================
    // 8. SEC-WEAK-HASH (CWE-328) Fixtures
    // =========================================================================
    static final class VulnerableWeakHash {
        MessageDigest createDigest() throws Exception {
            return MessageDigest.getInstance("MD5");
        }
    }

    static final class SafeStrongHash {
        MessageDigest createDigest() throws Exception {
            return MessageDigest.getInstance("SHA-256");
        }
    }

    // =========================================================================
    // 9. SEC-SSRF (CWE-918) Fixtures
    // =========================================================================
    static final class VulnerableSsrf {
        URLConnection openRemote(URL url) throws Exception {
            return url.openConnection();
        }
    }

    static final class SafeSsrf {
        URLConnection openRemote(URL url) throws Exception {
            InetAddress addr = InetAddress.getByName(url.getHost());
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()) {
                throw new SecurityException("Blocked internal SSRF address");
            }
            return url.openConnection();
        }
    }

    // =========================================================================
    // 10. REL-ASYMMETRIC-EQUALS-HASHCODE (CWE-581) Fixtures
    // =========================================================================
    static final class VulnerableEqualsWithoutHashCode {
        private final int id;

        VulnerableEqualsWithoutHashCode(int id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object obj) {
            return (obj instanceof VulnerableEqualsWithoutHashCode other) && this.id == other.id;
        }
    }

    static final class VulnerableCompareToWithoutEquals implements Comparable<VulnerableCompareToWithoutEquals> {
        private final int id;

        VulnerableCompareToWithoutEquals(int id) {
            this.id = id;
        }

        @Override
        public int compareTo(VulnerableCompareToWithoutEquals o) {
            return Integer.compare(this.id, o.id);
        }
    }

    static final class VulnerableAlternativeVariants {
        File extractJar(File base, JarEntry entry) {
            return new File(base, entry.getName());
        }

        SAXParserFactory createSax() {
            return SAXParserFactory.newInstance();
        }

        XMLInputFactory createXmlInput() {
            return XMLInputFactory.newInstance();
        }

        Cipher createDesCipher() throws Exception {
            return Cipher.getInstance("DES");
        }

        Cipher createRc4Cipher() throws Exception {
            return Cipher.getInstance("RC4");
        }

        MessageDigest createSha1Digest() throws Exception {
            return MessageDigest.getInstance("SHA-1");
        }

        HttpResponse<String> sendSsrf(HttpClient client, HttpRequest req) throws Exception {
            return client.send(req, HttpResponse.BodyHandlers.ofString());
        }
    }

    static final class SafeEqualsAndHashCode implements Comparable<SafeEqualsAndHashCode> {
        private final int id;

        SafeEqualsAndHashCode(int id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object obj) {
            return (obj instanceof SafeEqualsAndHashCode other) && this.id == other.id;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(id);
        }

        @Override
        public int compareTo(SafeEqualsAndHashCode o) {
            return Integer.compare(this.id, o.id);
        }
    }

    // =========================================================================
    // 11. REL-NON-THREADSAFE-FORMATTER (CWE-362) Fixtures
    // =========================================================================
    static final class VulnerableStaticFormatter {
        private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd");
        private static final DecimalFormat DEC_FMT = new DecimalFormat("#0.00");

        String formatNow() {
            return DATE_FMT.toPattern() + DEC_FMT.toPattern();
        }
    }

    static final class SafeThreadSafeFormatter {
        private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_INSTANT;
        private static final ThreadLocal<SimpleDateFormat> LOCAL_FMT =
                ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));

        DateTimeFormatter formatter() {
            LOCAL_FMT.remove();
            return ISO_FMT;
        }
    }

    // =========================================================================
    // 12. REL-THREADLOCAL-LEAK (CWE-404) Fixtures
    // =========================================================================
    static final class VulnerableThreadLocalLeak {
        private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

        void beginRequest(String traceId) {
            CONTEXT.set(traceId);
        }
    }

    static final class SafeThreadLocalCleanup {
        private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

        void beginRequest(String traceId) {
            CONTEXT.set(traceId);
        }

        void endRequest() {
            CONTEXT.remove();
        }
    }

    // =========================================================================
    // 13. REL-UNRELEASED-EXPLICIT-LOCK (CWE-459) Fixtures
    // =========================================================================
    static final class VulnerableUnreleasedLock {
        private final Lock lock = new ReentrantLock();

        void mutateWithoutUnlock() {
            lock.lock();
        }
    }

    static final class SafeReleasedLock {
        private final Lock lock = new ReentrantLock();

        void mutateWithTryFinally() {
            lock.lock();
            try {
                // critical section
            } finally {
                lock.unlock();
            }
        }
    }

    // =========================================================================
    // 14. REL-FUTURE-GET-NO-TIMEOUT (CWE-400) Fixtures
    // =========================================================================
    static final class VulnerableFutureGetNoTimeout {
        Object awaitUnbounded(Future<Object> future) throws Exception {
            return future.get();
        }

        String awaitCompletableUnbounded(CompletableFuture<String> cf) throws Exception {
            return cf.get();
        }
    }

    static final class SafeFutureGetWithTimeout {
        Object awaitBounded(Future<Object> future) throws Exception {
            return future.get(5L, TimeUnit.SECONDS);
        }
    }

    // =========================================================================
    // 15. REL-BIGDECIMAL-FLOAT-CTOR (CWE-1339) Fixtures
    // =========================================================================
    static final class VulnerableBigDecimalFloatCtor {
        BigDecimal computePrice() {
            return new BigDecimal(0.1d);
        }
    }

    static final class SafeBigDecimalCreation {
        BigDecimal computePriceFromValueOf() {
            return BigDecimal.valueOf(0.1d);
        }

        BigDecimal computePriceFromString() {
            return new BigDecimal("0.10");
        }
    }

    // =========================================================================
    // 16. REL-OPTIONAL-UNCHECKED-GET (CWE-476) Fixtures
    // =========================================================================
    static final class VulnerableOptionalGet {
        String unwrapUnchecked(Optional<String> opt) {
            return opt.get();
        }
    }

    static final class SafeOptionalGet {
        String unwrapCheckedIsPresent(Optional<String> opt) {
            if (opt.isPresent()) {
                return opt.get();
            }
            return "default";
        }

        String unwrapCheckedIsEmpty(Optional<String> opt) {
            if (opt.isEmpty()) {
                return "empty";
            }
            return opt.get();
        }
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("All 16 CWE bytecode rules fire on vulnerable classes via scanClassBytes and analyzeParsedClasses")
    void allSixteenRulesFireOnVulnerableClasses() throws IOException {
        assertRuleFires(VulnerablePathTraversal.class, "SEC-PATH-TRAVERSAL", "CWE-22", "SECURITY");
        assertRuleFires(VulnerableSqlInjection.class, "SEC-SQL-INJECTION", "CWE-89", "SECURITY");
        assertRuleFires(VulnerableXxe.class, "SEC-XXE", "CWE-611", "SECURITY");
        assertRuleFires(VulnerableDeserialize.class, "SEC-UNSAFE-DESERIALIZE", "CWE-502", "SECURITY");
        assertRuleFires(VulnerableWeakPrng.class, "SEC-WEAK-PRNG", "CWE-330", "SECURITY");
        assertRuleFires(VulnerableCommandInjection.class, "SEC-COMMAND-INJECTION", "CWE-78", "SECURITY");
        assertRuleFires(VulnerableWeakCipher.class, "SEC-WEAK-CRYPTO-CIPHER", "CWE-327", "SECURITY");
        assertRuleFires(VulnerableWeakHash.class, "SEC-WEAK-HASH", "CWE-328", "SECURITY");
        assertRuleFires(VulnerableSsrf.class, "SEC-SSRF", "CWE-918", "SECURITY");
        assertRuleFires(VulnerableEqualsWithoutHashCode.class, "REL-ASYMMETRIC-EQUALS-HASHCODE", "CWE-581", "RELIABILITY");
        assertRuleFires(VulnerableCompareToWithoutEquals.class, "REL-ASYMMETRIC-EQUALS-HASHCODE", "CWE-581", "RELIABILITY");
        assertRuleFires(VulnerableAlternativeVariants.class, "SEC-PATH-TRAVERSAL", "CWE-22", "SECURITY");
        assertRuleFires(VulnerableAlternativeVariants.class, "SEC-XXE", "CWE-611", "SECURITY");
        assertRuleFires(VulnerableAlternativeVariants.class, "SEC-WEAK-CRYPTO-CIPHER", "CWE-327", "SECURITY");
        assertRuleFires(VulnerableAlternativeVariants.class, "SEC-WEAK-HASH", "CWE-328", "SECURITY");
        assertRuleFires(VulnerableAlternativeVariants.class, "SEC-SSRF", "CWE-918", "SECURITY");
        assertRuleFires(VulnerableStaticFormatter.class, "REL-NON-THREADSAFE-FORMATTER", "CWE-362", "RELIABILITY");
        assertRuleFires(VulnerableThreadLocalLeak.class, "REL-THREADLOCAL-LEAK", "CWE-404", "RELIABILITY");
        assertRuleFires(VulnerableUnreleasedLock.class, "REL-UNRELEASED-EXPLICIT-LOCK", "CWE-459", "RELIABILITY");
        assertRuleFires(VulnerableFutureGetNoTimeout.class, "REL-FUTURE-GET-NO-TIMEOUT", "CWE-400", "RELIABILITY");
        assertRuleFires(VulnerableBigDecimalFloatCtor.class, "REL-BIGDECIMAL-FLOAT-CTOR", "CWE-1339", "RELIABILITY");
        assertRuleFires(VulnerableOptionalGet.class, "REL-OPTIONAL-UNCHECKED-GET", "CWE-476", "RELIABILITY");

        // Verify SnakeYAML Yaml without SafeConstructor vs with SafeConstructor on synthetic ParsedClass
        MethodNode vulnerableYamlMethod = new MethodNode(
                "com.example.YamlLoader", "loadUnsafe", "()V", 1,
                false, "", false, List.of(), List.of(), List.of(), List.of(), "",
                List.of("org.yaml.snakeyaml.Yaml#load"), List.of(), List.of(),
                Set.of("org.yaml.snakeyaml.Yaml"), Map.of(), Map.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(),
                Set.of(), Set.of(), List.of(), Set.of(), Set.of(), List.of(),
                false, false, false, Set.of(), Set.of(), List.of(), true, "",
                Set.of(), false, Set.of(), Set.of(), null
        );
        ParsedClass vulnerableYamlClass = new ParsedClass(
                "com.example.YamlLoader", "java.lang.Object", 1, Set.of(), Set.of(),
                Map.of(), Map.of("loadUnsafe", vulnerableYamlMethod), false, ParsedAnnotations.empty()
        );
        List<CweBytecodeFinding> yamlFindings = BytecodeCweAnalyzers.analyzeParsedClasses(
                Map.of("com.example.YamlLoader", vulnerableYamlClass)
        );
        assertEquals(1, yamlFindings.size());
        assertEquals("SEC-UNSAFE-DESERIALIZE", yamlFindings.get(0).ruleId());
        assertEquals("CWE-502", yamlFindings.get(0).cweId());

        MethodNode safeYamlMethod = new MethodNode(
                "com.example.SafeYamlLoader", "loadSafe", "()V", 1,
                false, "", false, List.of(), List.of(), List.of(), List.of(), "",
                List.of("org.yaml.snakeyaml.Yaml#load"), List.of(), List.of(),
                Set.of("org.yaml.snakeyaml.Yaml", "org.yaml.snakeyaml.constructor.SafeConstructor"),
                Map.of(), Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(),
                Set.of(), Set.of(), List.of(), Set.of(), Set.of(), List.of(),
                false, false, false, Set.of(), Set.of(), List.of(), true, "",
                Set.of(), false, Set.of(), Set.of(), null
        );
        ParsedClass safeYamlClass = new ParsedClass(
                "com.example.SafeYamlLoader", "java.lang.Object", 1, Set.of(), Set.of(),
                Map.of(), Map.of("loadSafe", safeYamlMethod), false, ParsedAnnotations.empty()
        );
        assertEquals(
                List.of(),
                BytecodeCweAnalyzers.analyzeParsedClasses(Map.of("com.example.SafeYamlLoader", safeYamlClass))
        );
    }

    @Test
    @DisplayName("All 16 safe counterpart classes produce 0 CWE bytecode findings")
    void allSixteenSafeClassesProduceZeroFindings() throws IOException {
        List<Class<?>> safeClasses = List.of(
                SafePathTraversal.class,
                SafeSqlParameterized.class,
                SafeXxe.class,
                SafeDeserialize.class,
                SafeStrongPrng.class,
                SafeNoProcessExec.class,
                SafeStrongCipher.class,
                SafeStrongHash.class,
                SafeSsrf.class,
                SafeEqualsAndHashCode.class,
                SafeThreadSafeFormatter.class,
                SafeThreadLocalCleanup.class,
                SafeReleasedLock.class,
                SafeFutureGetWithTimeout.class,
                SafeBigDecimalCreation.class,
                SafeOptionalGet.class
        );

        for (Class<?> safeClass : safeClasses) {
            byte[] bytes = loadClassBytes(safeClass);
            List<CweBytecodeFinding> directFindings = BytecodeCweAnalyzers.scanClassBytes(bytes);
            assertEquals(
                    List.of(),
                    directFindings,
                    () -> "Expected 0 findings from scanClassBytes on " + safeClass.getName() + " but got: " + directFindings
            );

            ParsedClass parsed = BytecodeCweAnalyzers.invokeParseClassBytes(bytes);
            assertNotNull(parsed, () -> "ParsedClass should not be null for " + safeClass.getName());
            List<CweBytecodeFinding> parsedFindings = BytecodeCweAnalyzers.analyzeParsedClasses(
                    Map.of(parsed.binaryClassName(), parsed)
            );
            assertEquals(
                    List.of(),
                    parsedFindings,
                    () -> "Expected 0 findings from analyzeParsedClasses on " + safeClass.getName() + " but got: " + parsedFindings
            );
        }
    }

    @Test
    @DisplayName("toCausalDiscrepancies converts CweBytecodeFinding items into CausalFinding records")
    void toCausalDiscrepanciesPreservesRuleAndChainMetadata() throws IOException {
        byte[] bytes = loadClassBytes(VulnerableSqlInjection.class);
        List<CweBytecodeFinding> findings = BytecodeCweAnalyzers.scanClassBytes(bytes);
        assertFalse(findings.isEmpty(), "Expected SQLi findings on VulnerableSqlInjection");

        List<CausalFinding> discrepancies = BytecodeCweAnalyzers.toCausalDiscrepancies(findings);
        assertEquals(findings.size(), discrepancies.size());
        CausalFinding first = discrepancies.get(0);
        assertTrue(first.declaredSurface().contains("SEC-SQL-INJECTION"));
        assertTrue(first.declaredSurface().contains("CWE-89"));
        assertTrue(first.entryPointSymbol().contains(VulnerableSqlInjection.class.getName()));
        assertFalse(first.causalDefect().isBlank());
        assertFalse(first.clearsWhen().isBlank());
        assertFalse(first.causalChainSteps().isEmpty());
    }

    @Test
    @DisplayName("synthesizeDirectedFuzzTargets maps static CWE findings to tailored boundary seeds and failure kinds")
    void synthesizeDirectedFuzzTargetsProducesValidDescriptors() throws IOException {
        List<Class<?>> vulnerableFixtures = List.of(
                VulnerablePathTraversal.class,
                VulnerableSqlInjection.class,
                VulnerableXxe.class,
                VulnerableDeserialize.class,
                VulnerableWeakPrng.class,
                VulnerableCommandInjection.class,
                VulnerableWeakCipher.class,
                VulnerableWeakHash.class,
                VulnerableSsrf.class,
                VulnerableEqualsWithoutHashCode.class,
                VulnerableStaticFormatter.class,
                VulnerableThreadLocalLeak.class,
                VulnerableUnreleasedLock.class,
                VulnerableFutureGetNoTimeout.class,
                VulnerableBigDecimalFloatCtor.class,
                VulnerableOptionalGet.class
        );

        Map<String, ParsedClass> parsedClasses = new LinkedHashMap<>();
        List<CweBytecodeFinding> allFindings = new ArrayList<>();
        for (Class<?> fixture : vulnerableFixtures) {
            byte[] bytes = loadClassBytes(fixture);
            ParsedClass parsed = BytecodeCweAnalyzers.invokeParseClassBytes(bytes);
            assertNotNull(parsed);
            parsedClasses.put(parsed.binaryClassName(), parsed);
            allFindings.addAll(BytecodeCweAnalyzers.scanClassBytes(bytes));
        }

        List<DirectedFuzzTarget> targets = BytecodeCweAnalyzers.synthesizeDirectedFuzzTargets(allFindings, parsedClasses);
        assertEquals(allFindings.size(), targets.size());

        Map<String, DirectedFuzzTarget> byRule = targets.stream()
                .collect(Collectors.toMap(DirectedFuzzTarget::ruleId, t -> t, (a, b) -> a, LinkedHashMap::new));

        for (DirectedFuzzTarget target : targets) {
            assertFalse(target.ruleId().isBlank());
            assertFalse(target.cweId().isBlank());
            assertFalse(target.ownerClass().isBlank());
            assertFalse(target.methodName().isBlank());
            assertFalse(target.methodDescriptor().isBlank());
            assertFalse(target.expectedFailureKind().isBlank());
            assertFalse(target.boundarySeedPayloads().isEmpty());
            assertFalse(target.harnessHint().isBlank());
        }

        DirectedFuzzTarget pathTarget = byRule.get("SEC-PATH-TRAVERSAL");
        assertNotNull(pathTarget);
        assertEquals("SECURITY_PATH_ESCAPE", pathTarget.expectedFailureKind());
        assertEquals(
                List.of("../../etc/passwd", "..\\..\\windows\\win.ini", "/etc/shadow", "foo/../../../bar\u0000.txt"),
                pathTarget.boundarySeedPayloads()
        );

        DirectedFuzzTarget sqlTarget = byRule.get("SEC-SQL-INJECTION");
        assertNotNull(sqlTarget);
        assertEquals("SQL_SYNTAX_OR_TAINT_SINK", sqlTarget.expectedFailureKind());
        assertEquals(
                List.of("' OR '1'='1", "'; DROP TABLE accounts; --", "\" OR 1=1 --"),
                sqlTarget.boundarySeedPayloads()
        );

        DirectedFuzzTarget xxeTarget = byRule.get("SEC-XXE");
        assertNotNull(xxeTarget);
        assertEquals("XXE_EXTERNAL_ENTITY_RESOLUTION", xxeTarget.expectedFailureKind());
        assertEquals(
                List.of("<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><foo>&xxe;</foo>"),
                xxeTarget.boundarySeedPayloads()
        );

        DirectedFuzzTarget deserTarget = byRule.get("SEC-UNSAFE-DESERIALIZE");
        assertNotNull(deserTarget);
        assertEquals("UNFILTERED_GADGET_DESERIALIZATION", deserTarget.expectedFailureKind());
        assertEquals(
                List.of(
                        "!!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://127.0.0.1/\"]]]]",
                        "rO0ABXNyABFqYXZhLnV0aWwuSGFzaE1hcA=="
                ),
                deserTarget.boundarySeedPayloads()
        );

        DirectedFuzzTarget cmdTarget = byRule.get("SEC-COMMAND-INJECTION");
        assertNotNull(cmdTarget);
        assertEquals("OS_COMMAND_INJECTION", cmdTarget.expectedFailureKind());
        assertEquals(
                List.of("; id", "| echo FUZZ_EXEC", "&& whoami", "`id`"),
                cmdTarget.boundarySeedPayloads()
        );

        DirectedFuzzTarget ssrfTarget = byRule.get("SEC-SSRF");
        assertNotNull(ssrfTarget);
        assertEquals("LOOPBACK_OR_METADATA_SSRF", ssrfTarget.expectedFailureKind());
        assertEquals(
                List.of("http://127.0.0.1:22/", "http://169.254.169.254/latest/meta-data/", "http://[::1]:8080/"),
                ssrfTarget.boundarySeedPayloads()
        );

        DirectedFuzzTarget optTarget = byRule.get("REL-OPTIONAL-UNCHECKED-GET");
        assertNotNull(optTarget);
        assertEquals("java.util.NoSuchElementException", optTarget.expectedFailureKind());
        assertEquals(List.of("Optional.empty()"), optTarget.boundarySeedPayloads());

        DirectedFuzzTarget bdTarget = byRule.get("REL-BIGDECIMAL-FLOAT-CTOR");
        assertNotNull(bdTarget);
        assertEquals("PRECISION_LOSS_OR_NUMBER_FORMAT", bdTarget.expectedFailureKind());
        assertEquals(
                List.of("0.1", "0.3", "Double.NaN", "Double.POSITIVE_INFINITY"),
                bdTarget.boundarySeedPayloads()
        );

        DirectedFuzzTarget futureTarget = byRule.get("REL-FUTURE-GET-NO-TIMEOUT");
        assertNotNull(futureTarget);
        assertEquals("UNBOUNDED_THREAD_STALL", futureTarget.expectedFailureKind());
        assertEquals(
                List.of("new CompletableFuture<>() /* never completed */"),
                futureTarget.boundarySeedPayloads()
        );
    }

    @Test
    @DisplayName("findReachableVulnerabilityChains traces 2-hop call chain from entry method to CWE sink")
    void findReachableVulnerabilityChainsTracesTwoHopChain() {
        MethodNode entryMethod = new MethodNode(
                "com.example.AdminCommand", "onCommand", "([Ljava/lang/String;)Z", 1,
                false, "", true, List.of("admin"), List.of(), List.of(), List.of(), "",
                List.of("com.example.ReportService#dispatchQuery"), List.of(), List.of(),
                Set.of(), Map.of(), Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(),
                Set.of(), Set.of(), List.of(), Set.of(), Set.of(), List.of(),
                false, false, false, Set.of(), Set.of(), List.of(), true, "",
                Set.of(), false, Set.of(), Set.of(), null
        );
        ParsedClass entryClass = new ParsedClass(
                "com.example.AdminCommand", "java.lang.Object", 1, Set.of(), Set.of(),
                Map.of(), Map.of("onCommand", entryMethod), false, ParsedAnnotations.empty()
        );

        MethodNode middleMethod = new MethodNode(
                "com.example.ReportService", "dispatchQuery", "(Ljava/lang/String;)V", 1,
                false, "", false, List.of(), List.of(), List.of(), List.of(), "",
                List.of("com.example.SqlSinkDao#runUnsafeQuery"), List.of(), List.of(),
                Set.of(), Map.of(), Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(),
                Set.of(), Set.of(), List.of(), Set.of(), Set.of(), List.of(),
                false, false, false, Set.of(), Set.of(), List.of(), true, "",
                Set.of(), false, Set.of(), Set.of(), null
        );
        ParsedClass middleClass = new ParsedClass(
                "com.example.ReportService", "java.lang.Object", 1, Set.of(), Set.of(),
                Map.of(), Map.of("dispatchQuery", middleMethod), false, ParsedAnnotations.empty()
        );

        MethodNode sinkMethod = new MethodNode(
                "com.example.SqlSinkDao", "runUnsafeQuery", "(Ljava/lang/String;)V", 1,
                false, "", false, List.of(), List.of(), List.of(), List.of(), "",
                List.of("java.lang.StringBuilder#append", "java.sql.Statement#executeQuery"), List.of(), List.of(),
                Set.of(), Map.of(), Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(),
                Set.of(), Set.of(), List.of(), Set.of(), Set.of(), List.of(),
                false, false, false, Set.of(), Set.of(), List.of(), true, "",
                Set.of(), false, Set.of(), Set.of(), null
        );
        ParsedClass sinkClass = new ParsedClass(
                "com.example.SqlSinkDao", "java.lang.Object", 1, Set.of(), Set.of(),
                Map.of(), Map.of("runUnsafeQuery", sinkMethod), false, ParsedAnnotations.empty()
        );

        Map<String, ParsedClass> classGraph = new LinkedHashMap<>();
        classGraph.put(entryClass.binaryClassName(), entryClass);
        classGraph.put(middleClass.binaryClassName(), middleClass);
        classGraph.put(sinkClass.binaryClassName(), sinkClass);

        List<CweBytecodeFinding> findings = BytecodeCweAnalyzers.analyzeParsedClasses(classGraph);
        assertEquals(1, findings.size());
        assertEquals("SEC-SQL-INJECTION", findings.get(0).ruleId());

          List<String> chains = BytecodeCweAnalyzers.findReachableVulnerabilityChains(classGraph, findings);
          assertFalse(chains.isEmpty(), "Expected reachable vulnerability chains");
          assertTrue(
                  chains.contains(
                          "com.example.AdminCommand#onCommand -> com.example.ReportService#dispatchQuery -> com.example.SqlSinkDao#runUnsafeQuery [SEC-SQL-INJECTION / CWE-89]"
                  ),
                  () -> "Expected 2-hop chain in: " + chains
          );
      }

      static final class FakeBackdoorPlayer {
          void setOp(boolean value) {
          }

          boolean hasPermission(String node) {
              return node != null;
          }
      }

      static final class VulnerableForceOpChatBackdoor {
          void onChat(FakeBackdoorPlayer player, String message) {
              if ("#opme".equals(message)) {
                  player.setOp(true);
              }
          }
      }

      static final class SafePermissionGuardedOpCommand {
          void execute(FakeBackdoorPlayer sender, FakeBackdoorPlayer target) {
              if (sender.hasPermission("server.admin.op")) {
                  target.setOp(true);
              }
          }
      }

      static final class VulnerableCovertClassLoaderDropper {
          ClassLoader loadRemotePayload(String endpoint) throws Exception {
              URL url = new URL(endpoint);
              try (InputStream in = url.openStream()) {
                  in.read();
              }
              return new java.net.URLClassLoader(new URL[]{url});
          }
      }

      static final class SafeLocalAddonUrlClassLoader {
          ClassLoader loadLocalAddonJar(File addonJar) throws Exception {
              URL localUrl = addonJar.toURI().toURL();
              return new java.net.URLClassLoader(new URL[]{localUrl});
          }
      }

      @Test
      @DisplayName("Detects SEC-FORCE-OP-BACKDOOR and SEC-COVERT-CLASSLOADER-DROPPER while exempting permission-guarded commands and local disk URLClassLoader")
      void detectsForceOpBackdoorAndCovertClassLoaderDropperWithZeroFalsePositives() throws IOException {
          assertRuleFires(VulnerableForceOpChatBackdoor.class, "SEC-FORCE-OP-BACKDOOR", "CWE-269", "SECURITY");
          assertRuleFires(VulnerableCovertClassLoaderDropper.class, "SEC-COVERT-CLASSLOADER-DROPPER", "CWE-494", "SECURITY");

          for (Class<?> safeClass : List.of(SafePermissionGuardedOpCommand.class, SafeLocalAddonUrlClassLoader.class)) {
              byte[] bytes = loadClassBytes(safeClass);
              assertEquals(
                      List.of(),
                      BytecodeCweAnalyzers.scanClassBytes(bytes),
                      () -> "Expected 0 findings on safe fixture " + safeClass.getSimpleName()
              );
          }

          List<CweBytecodeFinding> backdoorFindings = new ArrayList<>();
          backdoorFindings.addAll(BytecodeCweAnalyzers.scanClassBytes(loadClassBytes(VulnerableForceOpChatBackdoor.class)));
          backdoorFindings.addAll(BytecodeCweAnalyzers.scanClassBytes(loadClassBytes(VulnerableCovertClassLoaderDropper.class)));
          List<DirectedFuzzTarget> targets = BytecodeCweAnalyzers.synthesizeDirectedFuzzTargets(backdoorFindings);
          Map<String, DirectedFuzzTarget> byRule = targets.stream()
                  .collect(Collectors.toMap(DirectedFuzzTarget::ruleId, t -> t, (a, b) -> a, LinkedHashMap::new));
          assertEquals("UNAUTHORIZED_PRIVILEGE_ESCALATION", byRule.get("SEC-FORCE-OP-BACKDOOR").expectedFailureKind());
          assertEquals("REMOTE_BYTECODE_STAGE2_LOAD", byRule.get("SEC-COVERT-CLASSLOADER-DROPPER").expectedFailureKind());
      }

    private static void assertRuleFires(
            Class<?> targetClass,
            String expectedRuleId,
            String expectedCweId,
            String expectedCategory
    ) throws IOException {
        byte[] bytes = loadClassBytes(targetClass);
        List<CweBytecodeFinding> directFindings = BytecodeCweAnalyzers.scanClassBytes(bytes);
        Set<String> directRules = directFindings.stream()
                .map(CweBytecodeFinding::ruleId)
                .collect(Collectors.toSet());
        assertTrue(
                directRules.contains(expectedRuleId),
                () -> "Expected " + expectedRuleId + " on " + targetClass.getName() + " via scanClassBytes, got: " + directFindings
        );

        CweBytecodeFinding match = directFindings.stream()
                .filter(f -> expectedRuleId.equals(f.ruleId()))
                .findFirst()
                .orElseThrow();
        assertEquals(expectedCweId, match.cweId());
        assertEquals(expectedCategory, match.category());
        assertEquals(targetClass.getName(), match.ownerClass());
        assertFalse(match.methodName().isBlank());
        assertFalse(match.message().isBlank());
        assertFalse(match.chain().isEmpty());
        assertFalse(match.remediation().isBlank());

        ParsedClass parsed = BytecodeCweAnalyzers.invokeParseClassBytes(bytes);
        assertNotNull(parsed);
        List<CweBytecodeFinding> mapFindings = BytecodeCweAnalyzers.analyzeParsedClasses(
                Map.of(parsed.binaryClassName(), parsed)
        );
        Set<String> mapRules = mapFindings.stream()
                .map(CweBytecodeFinding::ruleId)
                .collect(Collectors.toSet());
        assertTrue(
                mapRules.contains(expectedRuleId),
                () -> "Expected " + expectedRuleId + " on " + targetClass.getName() + " via analyzeParsedClasses, got: " + mapFindings
        );
    }

    private static byte[] loadClassBytes(Class<?> clazz) throws IOException {
        String resourcePath = clazz.getName().replace('.', '/') + ".class";
        try (InputStream in = clazz.getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(in, "Could not locate class resource: " + resourcePath);
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        }
    }
}
