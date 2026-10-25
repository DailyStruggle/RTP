package io.github.dailystruggle.helpers.stresstestrtp;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Reads and resets the JaCoCo agent's execution data in this JVM and writes
 * the reached probes per class as JSON (ADR-111 section 2.6, ADR-122 Decision 1 & 5).
 * Probes are booleans, so the output records reached indices only, with no hit counts.
 */
public final class CoverageProbe {

    public static final String FILE_PREFIX = "coverage-";

    private static final Pattern UNSAFE_LABEL_CHARS = Pattern.compile("[^A-Za-z0-9._-]");

    private final Path outDir;

    public CoverageProbe(Path outDir) {
        this.outDir = outDir;
    }

    /** True when the -javaagent runtime API or JMX MBean is present on this JVM. */
    public static boolean agentAttached() {
        return getExecutionData(false).isPresent();
    }

    /**
     * Computes the JaCoCo {@code includes=} glob list (colon-separated, e.g.
     * {@code io.github.dailystruggle.rtp.*:com.betterrtp.*}) from the loaded
     * plugins or mods reported by {@link StressHost#loadedPlugins()} (ADR-122 Decision 5).
     */
    public static String includesForLoadedMods(StressHost host) {
        if (host == null) return "*";
        return includesForPlugins(host.loadedPlugins());
    }

    public static String includesForPlugins(Collection<StressHost.LoadedPlugin> plugins) {
        if (plugins == null || plugins.isEmpty()) return "*";
        Set<String> globs = new LinkedHashSet<>();
        for (StressHost.LoadedPlugin p : plugins) {
            if (p == null) continue;
            String nameLower = p.name().toLowerCase(java.util.Locale.ROOT);
            if (nameLower.contains("stresstest") || "minecraft".equals(nameLower)
                    || "java".equals(nameLower) || "fabricloader".equals(nameLower)
                    || nameLower.startsWith("fabric-")) {
                continue;
            }
            Set<String> prefixes = topPackagePrefixes(classesOfPlugin(p), p.fallbackPackage());
            for (String prefix : prefixes) {
                globs.add(prefix + ".*");
            }
        }
        return globs.isEmpty() ? "*" : String.join(":", globs);
    }

    static Set<String> classesOfPlugin(StressHost.LoadedPlugin p) {
        if (p == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (Path origin : p.originPaths()) {
            if (origin == null) continue;
            if (Files.isRegularFile(origin)) {
                try (ZipFile z = new ZipFile(origin.toFile())) {
                    z.stream().forEach(en -> {
                        String c = LeakProbe.jarEntryClass(en.getName());
                        if (c != null) out.add(c);
                    });
                } catch (IOException ignored) {
                }
            } else if (Files.isDirectory(origin)) {
                try (var walk = Files.walk(origin)) {
                    walk.filter(Files::isRegularFile).forEach(f -> {
                        String rel = origin.relativize(f).toString().replace('\\', '/');
                        String c = LeakProbe.jarEntryClass(rel);
                        if (c != null) out.add(c);
                    });
                } catch (IOException ignored) {
                }
            }
        }
        return out;
    }

    static Set<String> topPackagePrefixes(Set<String> classes, String fallbackPackage) {
        Set<String> out = new LinkedHashSet<>();
        for (String cls : classes) {
            int lastDot = cls.lastIndexOf('.');
            if (lastDot <= 0) continue;
            String pkg = cls.substring(0, lastDot);
            String[] parts = pkg.split("\\.");
            int maxDepth = (parts.length >= 4
                    && ("github".equals(parts[1]) || "gitlab".equals(parts[1]) || "bitbucket".equals(parts[1])))
                    ? 4 : 3;
            int depth = Math.min(parts.length, maxDepth);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < depth; i++) {
                if (i > 0) sb.append('.');
                sb.append(parts[i]);
            }
            out.add(sb.toString());
        }
        if (out.isEmpty() && fallbackPackage != null && !fallbackPackage.isBlank()) {
            out.add(fallbackPackage.trim());
        }
        return out;
    }

    /** Obtains execution data from the JaCoCo agent using multi-classloader
     *  reflection and JMX MBean fallback to avoid ClassLoader isolation issues
     *  in plugin containers. Empty when no agent is reachable. */
    static Optional<byte[]> getExecutionData(boolean reset) {
        ClassLoader[] loaders = new ClassLoader[] {
                ClassLoader.getSystemClassLoader(),
                ClassLoader.getPlatformClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                CoverageProbe.class.getClassLoader()
        };
        for (ClassLoader cl : loaders) {
            if (cl == null) continue;
            try {
                Class<?> rtClass = Class.forName("org.jacoco.agent.rt.RT", true, cl);
                Method getAgentMethod = rtClass.getMethod("getAgent");
                Object agent = getAgentMethod.invoke(null);
                if (agent != null) {
                    Method m = agent.getClass().getMethod("getExecutionData", boolean.class);
                    return Optional.ofNullable((byte[]) m.invoke(agent, reset));
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("org.jacoco:type=Runtime");
            if (mbs.isRegistered(name)) {
                return Optional.ofNullable((byte[]) mbs.invoke(name, "getExecutionData",
                        new Object[] { reset }, new String[] { "boolean" }));
            }
        } catch (Throwable ignored) {
        }

        return Optional.empty();
    }

    /** Dumps and resets the agent's execution data, writes
     *  {@code coverage-<label>.json} and returns its reached-probe totals. */
    public Result dumpAndReset(String label) throws IOException {
        String safe = UNSAFE_LABEL_CHARS.matcher(label).replaceAll("_");
        byte[] raw = getExecutionData(true).orElse(null);
        if (raw == null) {
            throw new IOException("JaCoCo execution data unavailable (agent not attached or not accessible)");
        }
        String json = toJson(safe, raw);
        Files.createDirectories(outDir);
        Path target = outDir.resolve(FILE_PREFIX + safe + ".json");
        Files.write(target, json.getBytes(StandardCharsets.UTF_8));
        return new Result(target, countProbes(raw));
    }

    /** Serialises one execution-data dump: {@code {"label":..,"classes":{name:[indices]}}}.
     *  Classes without any reached probe are omitted. */
    static String toJson(String label, byte[] raw) throws IOException {
        ExecutionDataStore store = new ExecutionDataStore();
        ExecutionDataReader reader = new ExecutionDataReader(new ByteArrayInputStream(raw));
        reader.setExecutionDataVisitor(store);
        reader.setSessionInfoVisitor(new SessionInfoStore());
        reader.read();

        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"label\":\"").append(label).append("\",\"classes\":{");
        boolean firstClass = true;
        for (ExecutionData ed : sortedByName(store.getContents())) {
            List<Integer> reached = reachedIndices(ed.getProbes());
            if (reached.isEmpty()) continue;
            if (!firstClass) sb.append(',');
            firstClass = false;
            sb.append('"').append(ed.getName().replace('/', '.')).append("\":[");
            for (int i = 0; i < reached.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(reached.get(i));
            }
            sb.append(']');
        }
        return sb.append("}}").toString();
    }

    private static int countProbes(byte[] raw) throws IOException {
        ExecutionDataStore store = new ExecutionDataStore();
        ExecutionDataReader reader = new ExecutionDataReader(new ByteArrayInputStream(raw));
        reader.setExecutionDataVisitor(store);
        reader.setSessionInfoVisitor(new SessionInfoStore());
        reader.read();
        int total = 0;
        for (ExecutionData ed : store.getContents()) {
            total += reachedIndices(ed.getProbes()).size();
        }
        return total;
    }

    private static List<ExecutionData> sortedByName(Collection<ExecutionData> contents) {
        List<ExecutionData> out = new ArrayList<>(contents);
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    private static List<Integer> reachedIndices(boolean[] probes) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < probes.length; i++) {
            if (probes[i]) out.add(i);
        }
        return out;
    }

    /** Outcome of one dump: the written file and the reached-probe total. */
    public record Result(Path file, int reachedProbes) {}
}
