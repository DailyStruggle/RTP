package net.leaf.sleuth.common.fuzz;

import io.github.dailystruggle.helpers.stresstestrtp.LeakProbe;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Off-thread plugin archive scanner mapping Java class names and stack trace frames to their
 * unique owning plugin {@code .jar} while filtering out multi-JAR shaded class collisions
 * ({@code ADR-125}, {@code leafsleuth-ADR-006}).
 *
 * <p>Classes present in more than one non-self plugin archive are recorded in
 * {@link #sharedClasses()} and excluded from {@link #ownerJarForClass(String)} and
 * {@link #firstSuspectJar(List)} so shaded libraries (such as Gson, Adventure, or bStats) are
 * never falsely blamed as a sole culprit.
 *
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy ADR-129
 * @governedBy leafsleuth-ADR-006
 * @trace REQ-SLEUTH-FUZZ-001
 * @trace S-005
 */
public final class JarClassOwnershipIndex {

    private static final Logger LOGGER = Logger.getLogger(JarClassOwnershipIndex.class.getName());
    private static final int MAX_NESTED_JAR_DEPTH = 3;
    private static final Pattern FABRIC_ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TOML_MOD_ID_PATTERN = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");

    /**
     * Metadata extracted from a scanned plugin {@code .jar} archive.
     *
     * @param jarName     archive file name (e.g. {@code "BetterRTP.jar"})
     * @param jarPath     resolved path to the archive (nullable for in-memory fixtures)
     * @param pluginId    resolved plugin identifier from {@code paper-plugin.yml}, {@code plugin.yml}, or stem
     * @param hardDepends declared hard dependencies
     * @param softDepends declared soft dependencies
     * @param classCount  number of distinct {@code .class} entries discovered in the archive
     */
    public record JarDescriptor(
            String jarName,
            Path jarPath,
            String pluginId,
            List<String> hardDepends,
            List<String> softDepends,
            int classCount
    ) {
        public JarDescriptor {
            Objects.requireNonNull(jarName, "jarName");
            pluginId = (pluginId == null || pluginId.isBlank()) ? stripJarExtension(jarName) : pluginId;
            hardDepends = hardDepends == null ? List.of() : List.copyOf(hardDepends);
            softDepends = softDepends == null ? List.of() : List.copyOf(softDepends);
            classCount = Math.max(0, classCount);
        }
    }

    private final Map<String, String> uniqueOwnerByClass;
    private final Map<String, Set<String>> allOwnersByClass;
    private final Set<String> sharedClasses;
    private final Map<String, JarDescriptor> descriptorsByJar;

    private JarClassOwnershipIndex(
            Map<String, String> uniqueOwnerByClass,
            Map<String, Set<String>> allOwnersByClass,
            Set<String> sharedClasses,
            Map<String, JarDescriptor> descriptorsByJar
    ) {
        this.uniqueOwnerByClass = Collections.unmodifiableMap(new LinkedHashMap<>(uniqueOwnerByClass));
        this.allOwnersByClass = Collections.unmodifiableMap(new LinkedHashMap<>(allOwnersByClass));
        this.sharedClasses = Collections.unmodifiableSet(new LinkedHashSet<>(sharedClasses));
        this.descriptorsByJar = Collections.unmodifiableMap(new LinkedHashMap<>(descriptorsByJar));
    }

    /**
     * Returns an empty ownership index with zero scanned archives.
     *
     * @return empty index
     */
    public static JarClassOwnershipIndex empty() {
        return new JarClassOwnershipIndex(Map.of(), Map.of(), Set.of(), Map.of());
    }

    /**
     * Scans all {@code *.jar} archives in {@code pluginsDir} without excluding any self prefix.
     *
     * @param pluginsDir directory containing plugin {@code .jar} archives
     * @return immutable ownership index
     */
    public static JarClassOwnershipIndex scanPluginsDirectory(Path pluginsDir) {
        return scanPluginsDirectory(pluginsDir, null);
    }

    /**
     * Scans all {@code *.jar} archives in {@code pluginsDir}, skipping archives matching
     * {@code selfJarPrefix} and gracefully ignoring corrupt or unreadable archives.
     *
     * @param pluginsDir    directory containing plugin {@code .jar} archives
     * @param selfJarPrefix optional prefix of the host diagnostic plugin JAR to exclude (e.g. {@code "LeafSleuth"})
     * @return immutable ownership index
     */
    public static JarClassOwnershipIndex scanPluginsDirectory(Path pluginsDir, String selfJarPrefix) {
        if (pluginsDir == null || !Files.isDirectory(pluginsDir)) {
            return empty();
        }
        List<String> prefixes = (selfJarPrefix == null || selfJarPrefix.isBlank())
                ? List.of()
                : List.of(selfJarPrefix);
        return scanArchiveDirectories(List.of(pluginsDir), prefixes);
    }

    /**
     * Scans all {@code *.jar} archives across multiple plugin/mod directories (such as
     * {@code plugins/} on Paper/Folia and {@code mods/} on Fabric/NeoForge), skipping archives
     * matching any prefix in {@code selfJarPrefixes}.
     *
     * @param archiveDirs     directories containing plugin or mod {@code .jar} archives
     * @param selfJarPrefixes prefixes of diagnostic plugin/mod JARs to exclude (e.g. {@code "LeafFuzz"}, {@code "LeafSleuth"})
     * @return immutable ownership index
     */
    public static JarClassOwnershipIndex scanArchiveDirectories(
            Collection<Path> archiveDirs,
            Collection<String> selfJarPrefixes
    ) {
        if (archiveDirs == null || archiveDirs.isEmpty()) {
            return empty();
        }
        List<String> normalizedSelfPrefixes = new ArrayList<>();
        if (selfJarPrefixes != null) {
            for (String pfx : selfJarPrefixes) {
                if (pfx != null && !pfx.isBlank()) {
                    normalizedSelfPrefixes.add(pfx.trim().toLowerCase(Locale.ROOT));
                }
            }
        }

        List<Path> jarPaths = new ArrayList<>();
        Set<Path> seenRealPaths = new LinkedHashSet<>();
        for (Path dir : archiveDirs) {
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
                for (Path p : stream) {
                    if (Files.isRegularFile(p) && seenRealPaths.add(p.toAbsolutePath().normalize())) {
                        jarPaths.add(p);
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "[LeafSleuth] Failed to list archive directory " + dir, e);
            }
        }
        if (jarPaths.isEmpty()) {
            return empty();
        }
        jarPaths.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));

        Map<String, Set<String>> classesByJar = new LinkedHashMap<>();
        Map<String, JarDescriptor> descriptors = new LinkedHashMap<>();

        for (Path jarPath : jarPaths) {
            String jarName = jarPath.getFileName().toString();
            if (startsWithAnyPrefix(jarName, normalizedSelfPrefixes)) {
                continue;
            }
            Set<String> discoveredClasses = new LinkedHashSet<>();
            List<String> hardDepends = new ArrayList<>();
            List<String> softDepends = new ArrayList<>();
            String pluginId = null;

            try (ZipFile zf = new ZipFile(jarPath.toFile())) {
                pluginId = scanZipFile(zf, discoveredClasses, hardDepends, softDepends);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[LeafSleuth] Skipping unreadable or corrupt plugin archive " + jarName + ": " + e.getMessage());
                continue;
            }

            if (pluginId != null && startsWithAnyPrefix(pluginId, normalizedSelfPrefixes)) {
                continue;
            }

            String resolvedId = (pluginId == null || pluginId.isBlank()) ? stripJarExtension(jarName) : pluginId;
            classesByJar.put(jarName, discoveredClasses);
            descriptors.put(jarName, new JarDescriptor(
                    jarName,
                    jarPath,
                    resolvedId,
                    hardDepends,
                    softDepends,
                    discoveredClasses.size()
            ));
        }

        return buildFromMap(classesByJar, descriptors);
    }

    private static boolean startsWithAnyPrefix(String candidate, List<String> lowerPrefixes) {
        if (candidate == null || lowerPrefixes == null || lowerPrefixes.isEmpty()) {
            return false;
        }
        String low = candidate.toLowerCase(Locale.ROOT);
        for (String pfx : lowerPrefixes) {
            if (low.startsWith(pfx)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds an ownership index from an explicit map of {@code jarName -> classNames}.
     *
     * @param classesByJar mapping from JAR file name to contained fully qualified class names
     * @return immutable ownership index
     */
    public static JarClassOwnershipIndex fromJarClassMap(Map<String, Set<String>> classesByJar) {
        if (classesByJar == null || classesByJar.isEmpty()) {
            return empty();
        }
        Map<String, JarDescriptor> descriptors = new LinkedHashMap<>();
        Map<String, Set<String>> normalizedMap = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : classesByJar.entrySet()) {
            String jarName = entry.getKey();
            if (jarName == null || jarName.isBlank()) {
                continue;
            }
            Set<String> normClasses = new LinkedHashSet<>();
            if (entry.getValue() != null) {
                for (String raw : entry.getValue()) {
                    String norm = normalizeClassName(raw);
                    if (norm != null) {
                        normClasses.add(norm);
                    }
                }
            }
            normalizedMap.put(jarName, normClasses);
            descriptors.put(jarName, new JarDescriptor(
                    jarName,
                    null,
                    stripJarExtension(jarName),
                    List.of(),
                    List.of(),
                    normClasses.size()
            ));
        }
        return buildFromMap(normalizedMap, descriptors);
    }

    private static JarClassOwnershipIndex buildFromMap(
            Map<String, Set<String>> classesByJar,
            Map<String, JarDescriptor> descriptors
    ) {
        Map<String, Set<String>> allOwners = new TreeMap<>();
        for (Map.Entry<String, Set<String>> entry : classesByJar.entrySet()) {
            String jarName = entry.getKey();
            for (String cls : entry.getValue()) {
                if (cls == null || cls.isBlank()) {
                    continue;
                }
                allOwners.computeIfAbsent(cls, k -> new TreeSet<>()).add(jarName);
            }
        }

        Map<String, String> uniqueOwners = new LinkedHashMap<>();
        Set<String> shared = new TreeSet<>();
        Map<String, Set<String>> immutableAllOwners = new LinkedHashMap<>();

        for (Map.Entry<String, Set<String>> entry : allOwners.entrySet()) {
            String cls = entry.getKey();
            Set<String> rawOwners = entry.getValue();
            if (rawOwners.size() == 1) {
                String soleOwner = rawOwners.iterator().next();
                uniqueOwners.put(cls, soleOwner);
                immutableAllOwners.put(cls, Set.of(soleOwner));
            } else if (rawOwners.size() > 1) {
                Set<String> owners = Collections.unmodifiableSet(new LinkedHashSet<>(rawOwners));
                immutableAllOwners.put(cls, owners);
                shared.add(cls);
            }
        }

        return new JarClassOwnershipIndex(uniqueOwners, immutableAllOwners, shared, descriptors);
    }

    /**
     * Resolves the unique owning plugin JAR for a class name, JVM histogram signature, or stack frame.
     *
     * <p>Returns {@link Optional#empty()} if the class belongs to the JDK/server or is shared across
     * multiple plugin JARs ({@link #sharedClasses()}).
     *
     * @param className fully qualified class name, histogram type descriptor, or stack trace frame
     * @return unique owning JAR file name if unambiguously owned by a single plugin JAR
     */
    public Optional<String> ownerJarForClass(String className) {
        String candidate = normalizeClassName(className);
        if (candidate == null || candidate.isEmpty()) {
            return Optional.empty();
        }
        String probe = candidate;
        while (probe != null && !probe.isEmpty()) {
            if (sharedClasses.contains(probe)) {
                return Optional.empty();
            }
            String owner = uniqueOwnerByClass.get(probe);
            if (owner != null) {
                return Optional.of(owner);
            }
            int dollar = probe.lastIndexOf('$');
            if (dollar > 0) {
                probe = probe.substring(0, dollar);
            } else {
                break;
            }
        }
        return Optional.empty();
    }

    /**
     * Scans an ordered sequence of stack trace frames or class names (top-to-bottom) and returns
     * the first unambiguous owning plugin JAR.
     *
     * @param stackFramesOrClassNames stack trace lines or class names
     * @return first uniquely attributed plugin JAR, or {@link Optional#empty()} if none match
     */
    public Optional<String> firstSuspectJar(List<String> stackFramesOrClassNames) {
        if (stackFramesOrClassNames == null || stackFramesOrClassNames.isEmpty()) {
            return Optional.empty();
        }
        for (String frame : stackFramesOrClassNames) {
            Optional<String> owner = ownerJarForClass(frame);
            if (owner.isPresent()) {
                return owner;
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the immutable set of classes present in {@code > 1} scanned plugin JARs.
     *
     * @return multi-JAR shared/shaded class names
     */
    public Set<String> sharedClasses() {
        return sharedClasses;
    }

    /**
     * Returns all JARs containing {@code className} (including shared/shaded classes).
     *
     * @param className class name or frame
     * @return immutable set of JAR names containing the class
     */
    public Set<String> allOwnersForClass(String className) {
        String candidate = normalizeClassName(className);
        if (candidate == null || candidate.isEmpty()) {
            return Set.of();
        }
        String probe = candidate;
        while (probe != null && !probe.isEmpty()) {
            Set<String> owners = allOwnersByClass.get(probe);
            if (owners != null && !owners.isEmpty()) {
                return owners;
            }
            int dollar = probe.lastIndexOf('$');
            if (dollar > 0) {
                probe = probe.substring(0, dollar);
            } else {
                break;
            }
        }
        return Set.of();
    }

    /**
     * Returns metadata descriptors for all indexed plugin JARs keyed by JAR file name.
     *
     * @return immutable map of JAR descriptors
     */
    public Map<String, JarDescriptor> descriptors() {
        return descriptorsByJar;
    }

    /**
     * Returns the number of indexed plugin JARs.
     *
     * @return indexed JAR count
     */
    public int indexedJarCount() {
        return descriptorsByJar.size();
    }

    /**
     * Returns the number of uniquely owned classes across all indexed JARs.
     *
     * @return unique class count
     */
    public int uniqueClassCount() {
        return uniqueOwnerByClass.size();
    }

    /**
     * Normalizes a raw class name, JVM array/lambda histogram token, or stack trace frame line
     * into a dotted binary class name.
     *
     * @param raw class name, histogram token, or stack frame
     * @return normalized binary class name, or {@code null} if not a valid class reference
     */
    public static String normalizeClassName(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.startsWith("at ")) {
            s = s.substring(3).trim();
        }
        // Strip module/classloader prefix in stack frames such as "Plugin.jar//com.example.Foo.bar(Foo.java:10)"
        int doubleSlash = s.indexOf("//");
        if (doubleSlash >= 0 && doubleSlash + 2 < s.length()) {
            s = s.substring(doubleSlash + 2);
        }
        // If it looks like a stack trace frame "pkg.ClassName.methodName(File.java:123)"
        int openParen = s.indexOf('(');
        if (openParen > 0) {
            String beforeParen = s.substring(0, openParen).trim();
            int lastDot = beforeParen.lastIndexOf('.');
            if (lastDot > 0) {
                s = beforeParen.substring(0, lastDot);
            } else {
                s = beforeParen;
            }
        }
        // Handle Method reference notation "pkg.ClassName#methodName" or "pkg.ClassName::methodName"
        int hash = s.indexOf('#');
        if (hash > 0) {
            s = s.substring(0, hash);
        }
        int colons = s.indexOf("::");
        if (colons > 0) {
            s = s.substring(0, colons);
        }
        if (s.endsWith(".class")) {
            String fromEntry = LeakProbe.jarEntryClass(s.replace('.', '/') .replace("/class", ".class"));
            if (fromEntry != null) {
                return fromEntry;
            }
        }
        String histo = LeakProbe.histogramClass(s);
        if (histo == null || histo.isEmpty()) {
            return null;
        }
        return histo.replace('/', '.');
    }

    private static String scanZipFile(
            ZipFile zf,
            Set<String> discoveredClasses,
            List<String> hardDepends,
            List<String> softDepends
    ) {
        String pluginId = null;
        List<ZipEntry> nestedJars = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry == null) {
                continue;
            }
            String name = entry.getName();
            String cls = LeakProbe.jarEntryClass(name);
            if (cls != null) {
                discoveredClasses.add(cls);
                continue;
            }
            String clean = name.startsWith("/") ? name.substring(1) : name;
            if ("paper-plugin.yml".equals(clean) || "plugin.yml".equals(clean)) {
                try (InputStream is = zf.getInputStream(entry)) {
                    String yaml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    String parsedId = parseYamlScalar(yaml, "name");
                    if (parsedId != null && (pluginId == null || "paper-plugin.yml".equals(clean))) {
                        pluginId = parsedId;
                    }
                    parseYamlDepends(yaml, "depend", hardDepends);
                    parseYamlDepends(yaml, "softdepend", softDepends);
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "[LeafSleuth] Failed to read descriptor entry " + name + " in " + zf.getName(), e);
                }
            } else if ("fabric.mod.json".equals(clean)) {
                try (InputStream is = zf.getInputStream(entry)) {
                    String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    Matcher m = FABRIC_ID_PATTERN.matcher(json);
                    if (m.find() && pluginId == null) {
                        pluginId = m.group(1).trim();
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "[LeafSleuth] Failed to read fabric.mod.json in " + zf.getName(), e);
                }
            } else if ("META-INF/neoforge.mods.toml".equals(clean) || "META-INF/mods.toml".equals(clean)) {
                try (InputStream is = zf.getInputStream(entry)) {
                    String toml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    Matcher m = TOML_MOD_ID_PATTERN.matcher(toml);
                    if (m.find() && pluginId == null) {
                        pluginId = m.group(1).trim();
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "[LeafSleuth] Failed to read mods.toml in " + zf.getName(), e);
                }
            } else if (clean.toLowerCase(Locale.ROOT).endsWith(".jar")
                    && (clean.startsWith("META-INF/jars/") || clean.startsWith("META-INF/jarjar/"))) {
                nestedJars.add(entry);
            }
        }

        for (ZipEntry nested : nestedJars) {
            try (InputStream is = zf.getInputStream(nested)) {
                byte[] bytes = is.readAllBytes();
                scanNestedJarBytes(bytes, discoveredClasses, 1);
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "[LeafSleuth] Failed to read nested archive " + nested.getName() + " in " + zf.getName(), e);
            }
        }
        return pluginId;
    }

    private static void scanNestedJarBytes(byte[] jarBytes, Set<String> discoveredClasses, int depth) {
        if (depth > MAX_NESTED_JAR_DEPTH || jarBytes == null || jarBytes.length == 0) {
            return;
        }
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                String cls = LeakProbe.jarEntryClass(name);
                if (cls != null) {
                    discoveredClasses.add(cls);
                } else {
                    String clean = name.startsWith("/") ? name.substring(1) : name;
                    if (clean.toLowerCase(Locale.ROOT).endsWith(".jar")
                            && (clean.startsWith("META-INF/jars/") || clean.startsWith("META-INF/jarjar/"))) {
                        byte[] nestedBytes = zis.readAllBytes();
                        scanNestedJarBytes(nestedBytes, discoveredClasses, depth + 1);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "[LeafSleuth] Skipping unreadable nested archive stream at depth " + depth, e);
        }
    }

    static String parseYamlScalar(String yamlText, String key) {
        if (yamlText == null || key == null) {
            return null;
        }
        String prefix = key + ":";
        for (String rawLine : yamlText.split("\\R")) {
            if (rawLine.isEmpty() || Character.isWhitespace(rawLine.charAt(0))) {
                continue;
            }
            String trimmed = rawLine.trim();
            if (trimmed.startsWith(prefix)) {
                String val = trimmed.substring(prefix.length()).trim();
                int comment = val.indexOf('#');
                if (comment >= 0) {
                    val = val.substring(0, comment).trim();
                }
                if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                    if (val.length() >= 2) {
                        val = val.substring(1, val.length() - 1).trim();
                    }
                }
                if (!val.isEmpty()) {
                    return val;
                }
            }
        }
        return null;
    }

    private static void parseYamlDepends(String yamlText, String key, List<String> target) {
        if (yamlText == null) {
            return;
        }
        String prefix = key + ":";
        String[] lines = yamlText.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            if (raw.isEmpty() || Character.isWhitespace(raw.charAt(0))) {
                continue;
            }
            String trimmed = raw.trim();
            if (!trimmed.startsWith(prefix)) {
                continue;
            }
            String rest = trimmed.substring(prefix.length()).trim();
            if (rest.startsWith("[") && rest.endsWith("]")) {
                String inner = rest.substring(1, rest.length() - 1);
                for (String item : inner.split(",")) {
                    String clean = stripQuotes(item.trim());
                    if (!clean.isEmpty() && !target.contains(clean)) {
                        target.add(clean);
                    }
                }
            } else if (rest.isEmpty()) {
                for (int j = i + 1; j < lines.length; j++) {
                    String sub = lines[j];
                    String subTrim = sub.trim();
                    if (subTrim.isEmpty() || subTrim.startsWith("#")) {
                        continue;
                    }
                    if (!Character.isWhitespace(sub.charAt(0)) && !subTrim.startsWith("-")) {
                        break;
                    }
                    if (subTrim.startsWith("-")) {
                        String clean = stripQuotes(subTrim.substring(1).trim());
                        if (!clean.isEmpty() && !target.contains(clean)) {
                            target.add(clean);
                        }
                    }
                }
            }
        }
    }

    private static String stripQuotes(String s) {
        if (s == null) {
            return "";
        }
        String out = s.trim();
        if ((out.startsWith("\"") && out.endsWith("\"")) || (out.startsWith("'") && out.endsWith("'"))) {
            if (out.length() >= 2) {
                return out.substring(1, out.length() - 1).trim();
            }
        }
        return out;
    }

    private static String stripJarExtension(String jarName) {
        if (jarName != null && jarName.toLowerCase(Locale.ROOT).endsWith(".jar") && jarName.length() > 4) {
            return jarName.substring(0, jarName.length() - 4);
        }
        return jarName == null ? "unknown" : jarName;
    }
}
