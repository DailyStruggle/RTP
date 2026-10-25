package io.github.dailystruggle.bit;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Live bytecode coverage provider that reflectively queries an attached JaCoCo agent
 * ({@code org.jacoco.agent.rt.RT.getAgent().getExecutionData(false)}) and parses its binary
 * {@code .exec} stream without external compile dependencies (ADR-121, ADR-103).
 */
public final class JacocoCoverageProvider implements BitCoverageProvider {
    public static final String ID = "jacoco";
    public static final byte BLOCK_HEADER = 0x01;
    public static final byte BLOCK_SESSIONINFO = 0x10;
    public static final byte BLOCK_EXECUTIONDATA = 0x11;
    public static final char MAGIC_NUMBER = 0xC0C0;
    public static final String TARGET_PACKAGE_PREFIX = "io/github/dailystruggle/";

    public static final List<String> SUBSYSTEM_KEYS = List.of(
            "core",
            "bukkit",
            "paper",
            "folia",
            "fabric",
            "neoforge",
            "anvil",
            "addons",
            "api");

    private final SurfaceCoverageProvider surfaceProvider;
    private final BooleanSupplier availabilityCheck;
    private final Supplier<byte[]> execDataSupplier;

    public JacocoCoverageProvider() {
        this(
                SurfaceCoverageProvider.INSTANCE,
                JacocoCoverageProvider::isAgentPresent,
                JacocoCoverageProvider::readAgentExecutionData);
    }

    public JacocoCoverageProvider(Supplier<byte[]> execDataSupplier) {
        this(
                SurfaceCoverageProvider.INSTANCE,
                () -> true,
                Objects.requireNonNull(execDataSupplier, "execDataSupplier must not be null"));
    }

    public JacocoCoverageProvider(
            SurfaceCoverageProvider surfaceProvider,
            BooleanSupplier availabilityCheck,
            Supplier<byte[]> execDataSupplier) {
        this.surfaceProvider = Objects.requireNonNull(surfaceProvider, "surfaceProvider must not be null");
        this.availabilityCheck = Objects.requireNonNull(availabilityCheck, "availabilityCheck must not be null");
        this.execDataSupplier = Objects.requireNonNull(execDataSupplier, "execDataSupplier must not be null");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isAvailable() {
        try {
            return availabilityCheck.getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public BitCoverageSnapshot snapshot(BitRunner runner) {
        Objects.requireNonNull(runner, "runner must not be null");
        BitCoverageSnapshot surface = surfaceProvider.snapshot(runner);
        boolean agentPresent = isAvailable();
        byte[] rawExec = agentPresent ? safeSupplyExecData() : new byte[0];
        ExecSummary parsed = parseExec(rawExec);
        return new BitCoverageSnapshot(
                ID,
                surface.executedComponents(),
                surface.registeredComponents(),
                surface.executedBySideEffect(),
                surface.registeredBySideEffect(),
                surface.observedCbitComponents(),
                surface.registeredCbitComponents(),
                agentPresent,
                parsed.hitProbes(),
                parsed.totalProbes(),
                parsed.subsystemProbes());
    }

    /** Returns true when {@code org.jacoco.agent.rt.RT.getAgent()} resolves a live agent in this JVM. */
    public static boolean isAgentPresent() {
        try {
            Class<?> rtClass = Class.forName("org.jacoco.agent.rt.RT");
            Method getAgent = rtClass.getMethod("getAgent");
            Object agent = getAgent.invoke(null);
            return agent != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Invokes {@code RT.getAgent().getExecutionData(false)} reflectively, or returns an empty array. */
    public static byte[] readAgentExecutionData() {
        try {
            Class<?> rtClass = Class.forName("org.jacoco.agent.rt.RT");
            Method getAgent = rtClass.getMethod("getAgent");
            Object agent = getAgent.invoke(null);
            if (agent == null) return new byte[0];
            Method getExecutionData = agent.getClass().getMethod("getExecutionData", boolean.class);
            Object result = getExecutionData.invoke(agent, false);
            return result instanceof byte[] bytes ? bytes : new byte[0];
        } catch (Throwable ignored) {
            return new byte[0];
        }
    }

    /**
     * Parses a raw JaCoCo {@code .exec} binary byte array and aggregates probe counts for classes
     * under {@code io/github/dailystruggle/}.
     */
    public static ExecSummary parseExec(byte[] execBytes) {
        Map<String, MutableProbeAccumulator> accumulators = new LinkedHashMap<>();
        for (String key : SUBSYSTEM_KEYS) {
            accumulators.put(key, new MutableProbeAccumulator());
        }
        if (execBytes == null || execBytes.length == 0) {
            return toSummary(accumulators);
        }

        Map<String, boolean[]> classProbes = new LinkedHashMap<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(execBytes))) {
            while (true) {
                int blockType = in.read();
                if (blockType < 0) break;
                switch ((byte) blockType) {
                    case BLOCK_HEADER -> {
                        char magic = in.readChar();
                        if (magic != MAGIC_NUMBER) {
                            throw new IllegalArgumentException(
                                    String.format("Invalid JaCoCo .exec magic header: 0x%04X", (int) magic));
                        }
                        in.readChar(); // format version
                    }
                    case BLOCK_SESSIONINFO -> {
                        in.readUTF(); // session id
                        in.readLong(); // start timestamp
                        in.readLong(); // dump timestamp
                    }
                    case BLOCK_EXECUTIONDATA -> {
                        long classId = in.readLong();
                        String className = in.readUTF();
                        boolean[] probes = readBooleanArray(in);
                        String normalized = normalizeClassName(className);
                        if (normalized.startsWith(TARGET_PACKAGE_PREFIX)) {
                            String recordKey = classId + ":" + normalized;
                            boolean[] existing = classProbes.get(recordKey);
                            if (existing == null || existing.length != probes.length) {
                                classProbes.put(recordKey, probes);
                            } else {
                                for (int i = 0; i < probes.length; i++) {
                                    existing[i] |= probes[i];
                                }
                            }
                        }
                    }
                    default -> throw new IllegalArgumentException(
                            String.format("Unsupported JaCoCo .exec block type: 0x%02X", blockType));
                }
            }
        } catch (EOFException truncated) {
            throw new IllegalArgumentException("Truncated JaCoCo .exec stream", truncated);
        } catch (IOException ioFailure) {
            throw new IllegalArgumentException("Failed to read JaCoCo .exec stream", ioFailure);
        }

        for (Map.Entry<String, boolean[]> entry : classProbes.entrySet()) {
            String key = entry.getKey();
            int colon = key.indexOf(':');
            String normalizedClass = colon >= 0 ? key.substring(colon + 1) : key;
            String subsystem = classifySubsystem(normalizedClass);
            if (subsystem == null) continue;
            boolean[] probes = entry.getValue();
            int hit = 0;
            for (boolean probe : probes) {
                if (probe) hit++;
            }
            MutableProbeAccumulator acc = accumulators.computeIfAbsent(subsystem, ignored -> new MutableProbeAccumulator());
            acc.hitProbes += hit;
            acc.totalProbes += probes.length;
            acc.loadedClasses += 1;
        }

        return toSummary(accumulators);
    }

    /**
     * Maps a class name under {@code io/github/dailystruggle/} to its canonical subsystem bucket,
     * or returns {@code null} when outside the project namespace.
     */
    public static String classifySubsystem(String className) {
        if (className == null || className.isBlank()) return null;
        String normalized = normalizeClassName(className);
        if (!normalized.startsWith(TARGET_PACKAGE_PREFIX)) return null;
        String rest = normalized.substring(TARGET_PACKAGE_PREFIX.length());
        if (rest.startsWith("rtp/bukkit/") || rest.startsWith("bukkit/")) return "bukkit";
        if (rest.startsWith("rtp/paper/") || rest.startsWith("paper/")) return "paper";
        if (rest.startsWith("rtp/folia/") || rest.startsWith("folia/")) return "folia";
        if (rest.startsWith("rtp/fabric/") || rest.startsWith("fabric/")) return "fabric";
        if (rest.startsWith("rtp/neoforge/") || rest.startsWith("neoforge/")) return "neoforge";
        if (rest.startsWith("rtp/anvil/") || rest.startsWith("anvil/")) return "anvil";
        if (rest.startsWith("rtp/addons/")
                || rest.startsWith("addons/")
                || rest.startsWith("rtp/actionaddon/")
                || rest.startsWith("rtp/claimaddon/")
                || rest.startsWith("rtp/guimenu/")
                || rest.contains("addon/")) {
            return "addons";
        }
        if (rest.startsWith("rtp/common/") || rest.startsWith("rtp/core/") || rest.startsWith("core/")) {
            return "core";
        }
        return "api";
    }

    /** Reads a JaCoCo variable-length integer (unsigned LEB128). */
    public static int readVarInt(DataInputStream in) throws IOException {
        int value = 0xFF & in.readByte();
        if ((value & 0x80) == 0) {
            return value;
        }
        return (value & 0x7F) | (readVarInt(in) << 7);
    }

    private static boolean[] readBooleanArray(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length < 0) {
            throw new IllegalArgumentException("Negative probe array length: " + length);
        }
        boolean[] probes = new boolean[length];
        int buffer = 0;
        for (int i = 0; i < length; i++) {
            if ((i % 8) == 0) {
                buffer = in.readUnsignedByte();
            }
            probes[i] = (buffer & 0x01) != 0;
            buffer >>>= 1;
        }
        return probes;
    }

    private byte[] safeSupplyExecData() {
        try {
            byte[] data = execDataSupplier.get();
            return data == null ? new byte[0] : data;
        } catch (Throwable ignored) {
            return new byte[0];
        }
    }

    private static String normalizeClassName(String className) {
        String trimmed = className.trim();
        if (trimmed.startsWith("L") && trimmed.endsWith(";") && trimmed.length() > 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed.replace('.', '/');
    }

    private static ExecSummary toSummary(Map<String, MutableProbeAccumulator> accumulators) {
        long totalHit = 0L;
        long totalCount = 0L;
        Map<String, BitCoverageSnapshot.ProbeRatio> ratios = new LinkedHashMap<>();
        for (Map.Entry<String, MutableProbeAccumulator> entry : accumulators.entrySet()) {
            MutableProbeAccumulator acc = entry.getValue();
            totalHit += acc.hitProbes;
            totalCount += acc.totalProbes;
            ratios.put(
                    entry.getKey(),
                    new BitCoverageSnapshot.ProbeRatio(acc.hitProbes, acc.totalProbes, acc.loadedClasses));
        }
        return new ExecSummary(totalHit, totalCount, Collections.unmodifiableMap(ratios));
    }

    private static final class MutableProbeAccumulator {
        long hitProbes;
        long totalProbes;
        int loadedClasses;
    }

    /** Aggregated bytecode probe statistics extracted from a JaCoCo {@code .exec} dump. */
    public record ExecSummary(
            long hitProbes,
            long totalProbes,
            Map<String, BitCoverageSnapshot.ProbeRatio> subsystemProbes) {
        public ExecSummary {
            subsystemProbes = subsystemProbes == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(subsystemProbes));
        }
    }
}
