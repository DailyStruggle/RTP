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
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Reads and resets the JaCoCo agent's execution data in this JVM and writes
 *  the reached probes per class as JSON (ADR-111 section 2.6). Probes are
 *  booleans, so the output records reached indices only, with no hit counts. */
final class CoverageProbe {

    static final String FILE_PREFIX = "coverage-";

    private static final Pattern UNSAFE_LABEL_CHARS = Pattern.compile("[^A-Za-z0-9._-]");

    private final Path outDir;

    CoverageProbe(Path outDir) {
        this.outDir = outDir;
    }

    /** True when the -javaagent runtime API or JMX MBean is present on this JVM. */
    static boolean agentAttached() {
        return getExecutionData(false).isPresent();
    }

    /** Obtains execution data from the JaCoCo agent using multi-classloader
     *  reflection and JMX MBean fallback to avoid ClassLoader isolation issues
     *  in plugin containers. Empty when no agent is reachable. */
    static Optional<byte[]> getExecutionData(boolean reset) {
        // First attempt: reflectively load org.jacoco.agent.rt.RT across available classloaders
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
                // agent not visible from this loader; try the next one
            }
        }

        // Second attempt: query JaCoCo MBean if JMX is active (jmx=true)
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("org.jacoco:type=Runtime");
            if (mbs.isRegistered(name)) {
                return Optional.ofNullable((byte[]) mbs.invoke(name, "getExecutionData",
                        new Object[] { reset }, new String[] { "boolean" }));
            }
        } catch (Throwable ignored) {
            // JMX unavailable or MBean not registered: treat as no agent
        }

        return Optional.empty();
    }

    /** Dumps and resets the agent's execution data, writes
     *  {@code coverage-<label>.json} and returns its reached-probe totals. */
    Result dumpAndReset(String label) throws IOException {
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
    record Result(Path file, int reachedProbes) {}
}
