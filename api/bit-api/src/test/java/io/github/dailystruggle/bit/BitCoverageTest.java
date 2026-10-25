package io.github.dailystruggle.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitCoverageSnapshot.ProbeRatio;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("REQ-API-F-009 BIT live coverage SPI, SurfaceCoverageProvider, and JacocoCoverageProvider")
class BitCoverageTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearDiscoveryFindings() {
        BitCoverageDiscovery.clearFindings();
    }

    @Test
    void bitCodesRegistersCoverageAndCbitObservationCodes() {
        assertEquals(10, BitCodes.COVERAGE_AGENT_UNAVAILABLE.id());
        assertEquals("BIT_COVERAGE_AGENT_UNAVAILABLE", BitCodes.COVERAGE_AGENT_UNAVAILABLE.key());
        assertEquals(BitTier.PBIT, BitCodes.COVERAGE_AGENT_UNAVAILABLE.tier());
        assertEquals(11, BitCodes.CBIT_UNOBSERVED.id());
        assertEquals("BIT_CBIT_UNOBSERVED", BitCodes.CBIT_UNOBSERVED.key());
        assertEquals(BitTier.CBIT, BitCodes.CBIT_UNOBSERVED.tier());
        assertSame(BitCodes.COVERAGE_AGENT_UNAVAILABLE, BitCodes.BIT_COVERAGE_AGENT_UNAVAILABLE);
        assertSame(BitCodes.CBIT_UNOBSERVED, BitCodes.BIT_CBIT_UNOBSERVED);
        assertTrue(BitCodes.all().contains(BitCodes.COVERAGE_AGENT_UNAVAILABLE));
        assertTrue(BitCodes.all().contains(BitCodes.CBIT_UNOBSERVED));
    }

    @Test
    void surfaceCoverageProviderBreaksDownSideEffectsAndCbitObservations() {
        BitRunner runner = new BitRunner(SurfaceCoverageProvider.INSTANCE);
        BitComponent safePbit = new BitComponent(
                "safe-pbit", "config", BitTier.PBIT, BitSideEffect.SAFE, 1_000, () -> BitReport.of(List.of()));
        BitComponent worldIbit = new BitComponent(
                "world-ibit", "test", BitTier.IBIT, BitSideEffect.NEEDS_WORLD, 1_000, () -> BitReport.of(List.of()));
        BitComponent playerIbit = new BitComponent(
                "player-ibit", "test", BitTier.IBIT, BitSideEffect.NEEDS_PLAYER, 1_000, () -> BitReport.of(List.of()));
        BitComponent mutatingIbit = new BitComponent(
                "mutating-ibit", "test", BitTier.IBIT, BitSideEffect.MUTATING, 1_000, () -> BitReport.of(List.of()));
        BitComponent heavyIbit = new BitComponent(
                "heavy-ibit", "test", BitTier.IBIT, BitSideEffect.HEAVY, 1_000, () -> BitReport.of(List.of()));
        BitComponent observedCbit = new BitComponent(
                "observed-cbit", "pipeline", BitTier.CBIT, BitSideEffect.SAFE, 1_000, () -> BitReport.of(List.of()));
        BitComponent unobservedCbit = new BitComponent(
                "unobserved-cbit",
                "action",
                BitTier.CBIT,
                BitSideEffect.SAFE,
                1_000,
                () -> BitReport.of(List.of(BitFinding.of(
                        BitCodes.CBIT_UNOBSERVED,
                        "unobserved-cbit",
                        "action",
                        "No observations yet.",
                        "at least 1 observation",
                        BitCodes.CBIT_UNOBSERVED.remediation()))));
        BitComponent notRunCbit = new BitComponent(
                "not-run-cbit", "governor", BitTier.CBIT, BitSideEffect.SAFE, 1_000, () -> BitReport.of(List.of()));

        runner.register(safePbit);
        runner.register(worldIbit);
        runner.register(playerIbit);
        runner.register(mutatingIbit);
        runner.register(heavyIbit);
        runner.register(observedCbit);
        runner.register(unobservedCbit);
        runner.register(notRunCbit);

        runner.run(safePbit, Runnable::run);
        runner.run(heavyIbit, Runnable::run);
        runner.run(observedCbit, Runnable::run);
        runner.run(unobservedCbit, Runnable::run);

        BitCoverageSnapshot snapshot = runner.coverageSnapshot();
        assertEquals("surface", snapshot.providerId());
        assertTrue(snapshot.isEnabled());
        assertEquals(4, snapshot.executedComponents());
        assertEquals(8, snapshot.registeredComponents());
        assertEquals(3, snapshot.executedBySideEffect().get(BitSideEffect.SAFE));
        assertEquals(4, snapshot.registeredBySideEffect().get(BitSideEffect.SAFE));
        assertEquals(0, snapshot.executedBySideEffect().get(BitSideEffect.NEEDS_WORLD));
        assertEquals(1, snapshot.registeredBySideEffect().get(BitSideEffect.NEEDS_WORLD));
        assertEquals(1, snapshot.executedBySideEffect().get(BitSideEffect.HEAVY));
        assertEquals(1, snapshot.registeredBySideEffect().get(BitSideEffect.HEAVY));
        assertEquals(1, snapshot.observedCbitComponents());
        assertEquals(3, snapshot.registeredCbitComponents());
        assertEquals(2, snapshot.unobservedCbitComponents());
        assertFalse(snapshot.bytecodeAgentPresent());
        assertTrue(runner.hasExecuted("safe-pbit"));
        assertFalse(runner.hasExecuted("world-ibit"));
        assertFalse(runner.hasExecuted(null));
        assertTrue(runner.executedComponentIds().contains("safe-pbit"));

        List<BitFinding> unobservedFindings = SurfaceCoverageProvider.unobservedCbitFindings(runner);
        assertEquals(2, unobservedFindings.size());
        assertTrue(unobservedFindings.stream().allMatch(f -> "BIT_CBIT_UNOBSERVED".equals(f.code())));
    }

    @Test
    void jacocoCoverageProviderParsesExecBinaryStreamAndClassifiesAllSubsystems() throws IOException {
        byte[] execBytes = buildSampleExecStream();
        JacocoCoverageProvider provider = new JacocoCoverageProvider(() -> execBytes);
        BitRunner runner = new BitRunner(provider);
        BitComponent safePbit = new BitComponent(
                "safe-pbit", "config", BitTier.PBIT, BitSideEffect.SAFE, 1_000, () -> BitReport.of(List.of()));
        runner.register(safePbit);
        runner.run(safePbit, Runnable::run);

        BitCoverageSnapshot snapshot = runner.coverageSnapshot();
        assertEquals("jacoco", snapshot.providerId());
        assertTrue(snapshot.bytecodeAgentPresent());
        assertEquals(1, snapshot.executedComponents());
        assertEquals(1, snapshot.registeredComponents());

        Map<String, ProbeRatio> subsystems = snapshot.subsystemProbes();
        assertEquals(new ProbeRatio(3, 4, 1), subsystems.get("core"));
        assertEquals(new ProbeRatio(1, 2, 1), subsystems.get("bukkit"));
        assertEquals(new ProbeRatio(2, 2, 1), subsystems.get("paper"));
        assertEquals(new ProbeRatio(1, 3, 1), subsystems.get("folia"));
        assertEquals(new ProbeRatio(2, 4, 1), subsystems.get("fabric"));
        assertEquals(new ProbeRatio(1, 1, 1), subsystems.get("neoforge"));
        assertEquals(new ProbeRatio(10, 16, 1), subsystems.get("anvil"));
        assertEquals(new ProbeRatio(2, 3, 1), subsystems.get("addons"));
        assertEquals(new ProbeRatio(2, 2, 1), subsystems.get("api"));
        assertEquals(24L, snapshot.hitProbes());
        assertEquals(37L, snapshot.totalProbes());
        assertTrue(snapshot.summary().contains("probes=24/37"));
        assertTrue(snapshot.toJson().contains("\"providerId\":\"jacoco\""));
        assertTrue(snapshot.toJson().contains("\"core\":{\"hitProbes\":3,\"totalProbes\":4,\"loadedClasses\":1}"));
    }

    @Test
    void jacocoCoverageProviderValidatesCorruptStreamsAndHandlesAgentPresence() throws IOException {
        assertNull(JacocoCoverageProvider.classifySubsystem(null));
        assertNull(JacocoCoverageProvider.classifySubsystem(" "));
        assertNull(JacocoCoverageProvider.classifySubsystem("java/lang/String"));
        assertEquals("api", JacocoCoverageProvider.classifySubsystem("Lio/github/dailystruggle/bit/BitRunner;"));
        assertEquals("addons", JacocoCoverageProvider.classifySubsystem("io/github/dailystruggle/rtp/guimenu/GuiBootstrap"));
        assertEquals("addons", JacocoCoverageProvider.classifySubsystem("io/github/dailystruggle/rtp/claimaddon/ClaimTest"));
        assertEquals("core", JacocoCoverageProvider.classifySubsystem("io/github/dailystruggle/rtp/core/Sample"));

        JacocoCoverageProvider.ExecSummary empty = JacocoCoverageProvider.parseExec(null);
        assertEquals(0L, empty.totalProbes());

        ByteArrayOutputStream badMagic = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(badMagic)) {
            out.writeByte(JacocoCoverageProvider.BLOCK_HEADER);
            out.writeChar(0x1234);
            out.writeChar(0x1007);
        }
        assertThrows(IllegalArgumentException.class, () -> JacocoCoverageProvider.parseExec(badMagic.toByteArray()));

        ByteArrayOutputStream badBlock = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(badBlock)) {
            out.writeByte(0x7F);
        }
        assertThrows(IllegalArgumentException.class, () -> JacocoCoverageProvider.parseExec(badBlock.toByteArray()));

        ByteArrayOutputStream truncated = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(truncated)) {
            out.writeByte(JacocoCoverageProvider.BLOCK_HEADER);
            out.writeByte(0xC0);
        }
        assertThrows(IllegalArgumentException.class, () -> JacocoCoverageProvider.parseExec(truncated.toByteArray()));

        JacocoCoverageProvider throwingAvailability = new JacocoCoverageProvider(
                SurfaceCoverageProvider.INSTANCE,
                () -> {
                    throw new IllegalStateException("unavailable");
                },
                () -> new byte[0]);
        assertFalse(throwingAvailability.isAvailable());

        JacocoCoverageProvider throwingExec = new JacocoCoverageProvider(
                SurfaceCoverageProvider.INSTANCE,
                () -> true,
                () -> {
                    throw new IllegalStateException("read failed");
                });
        assertEquals(0L, throwingExec.snapshot(new BitRunner(throwingExec)).totalProbes());

        JacocoCoverageProvider defaultProvider = new JacocoCoverageProvider();
        assertEquals("jacoco", defaultProvider.id());
        if (JacocoCoverageProvider.isAgentPresent()) {
            assertTrue(defaultProvider.isAvailable());
            assertTrue(JacocoCoverageProvider.readAgentExecutionData().length > 0);
            BitCoverageSnapshot liveSnapshot = defaultProvider.snapshot(new BitRunner(defaultProvider));
            assertTrue(liveSnapshot.totalProbes() > 0);
        }
    }

    @Test
    void bitCoverageDiscoveryHandlesAutoSurfaceJacocoFallbackOffAndServiceLoader() throws Exception {
        List<BitFinding> findings = new ArrayList<>();
        BitCoverageProvider autoWithAgent = BitCoverageDiscovery.discover(
                "auto", getClass().getClassLoader(), () -> true, findings::add);
        assertEquals("jacoco", autoWithAgent.id());
        assertTrue(findings.isEmpty());

        BitCoverageProvider autoWithoutAgent = BitCoverageDiscovery.discover(
                "auto", getClass().getClassLoader(), () -> false, findings::add);
        assertEquals("surface", autoWithoutAgent.id());
        assertTrue(findings.isEmpty());

        BitCoverageProvider jacocoFallback = BitCoverageDiscovery.discover(
                "jacoco", getClass().getClassLoader(), () -> false, findings::add);
        assertEquals("surface", jacocoFallback.id());
        assertEquals(1, findings.size());
        assertEquals(BitCodes.COVERAGE_AGENT_UNAVAILABLE.key(), findings.get(0).code());
        assertEquals(1, BitCoverageDiscovery.findings().size());

        BitCoverageProvider jacocoAvailable = BitCoverageDiscovery.discover(
                "jacoco", getClass().getClassLoader(), () -> true, findings::add);
        assertEquals("jacoco", jacocoAvailable.id());

        BitCoverageProvider offProvider = BitCoverageDiscovery.discover("off");
        assertEquals("off", offProvider.id());
        assertTrue(offProvider.isAvailable());
        assertFalse(offProvider.snapshot(new BitRunner(offProvider)).isEnabled());

        BitCoverageProvider defaultAuto = BitCoverageDiscovery.discover(null);
        assertNotNull(defaultAuto);
        BitCoverageProvider unknown = BitCoverageDiscovery.discover(
                "unknown-provider", getClass().getClassLoader(), () -> false, null);
        assertEquals("surface", unknown.id());

        Path servicesDir = Files.createDirectories(tempDir.resolve("META-INF/services"));
        Files.writeString(
                servicesDir.resolve(BitCoverageProvider.class.getName()),
                CustomServiceProvider.class.getName() + "\ncom.example.NonExistentProvider\n",
                StandardCharsets.UTF_8);
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            BitCoverageProvider custom = BitCoverageDiscovery.discover("custom", loader, () -> false, null);
            assertEquals("custom", custom.id());
        }
    }

    @Test
    void bitTableRendersCoverageFooterWhenEnabledAndOmitsWhenOff() {
        BitComponent component = new BitComponent(
                "accessor", "test.accessor", BitTier.IBIT, BitSideEffect.SAFE, 1_000, () -> BitReport.of(List.of()));
        BitRunner runner = new BitRunner(SurfaceCoverageProvider.INSTANCE);
        runner.register(component);
        BitResult result = runner.run(component, Runnable::run);
        List<BitTable.Row> rows = BitTable.rows(component, result);

        List<String> withoutFooter = BitTable.render(rows, BitTable.COLUMNS, BitTable.Decorator.NONE);
        assertEquals(2, withoutFooter.size());

        List<String> withFooter = BitTable.render(
                rows, BitTable.COLUMNS, BitTable.Decorator.NONE, runner.coverageSnapshot());
        assertEquals(3, withFooter.size());
        assertTrue(withFooter.get(2).contains("coverage=surface components=1/1"));

        List<String> withOff = BitTable.render(
                rows, BitTable.COLUMNS, BitTable.Decorator.NONE, BitCoverageSnapshot.off());
        assertEquals(2, withOff.size());
        assertEquals("", BitTable.coverageFooter(null));
        assertEquals("", BitTable.coverageFooter(BitCoverageSnapshot.off()));
    }

    @Test
    void snapshotAndProbeRatioValidateArguments() {
        assertEquals(0.0, new ProbeRatio(0, 0, 0).ratio());
        assertEquals(0.5, new ProbeRatio(2, 4, 1).ratio());
        assertThrows(IllegalArgumentException.class, () -> new ProbeRatio(-1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProbeRatio(0, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProbeRatio(0, 1, -1));

        BitCoverageSnapshot off = BitCoverageSnapshot.off();
        assertEquals("coverage=off", off.summary());
        assertEquals(0.0, off.componentRatio());
        assertEquals(0.0, off.cbitRatio());
        assertEquals(0.0, off.probeRatio());

        EnumMap<BitSideEffect, Integer> negativeMap = new EnumMap<>(BitSideEffect.class);
        negativeMap.put(BitSideEffect.SAFE, -1);
        assertThrows(IllegalArgumentException.class,
                () -> new BitCoverageSnapshot("surface", -1, 0, Map.of(), Map.of(), 0, 0, false, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new BitCoverageSnapshot("surface", 0, -1, Map.of(), Map.of(), 0, 0, false, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new BitCoverageSnapshot("surface", 0, 0, negativeMap, Map.of(), 0, 0, false, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new BitCoverageSnapshot("surface", 0, 0, Map.of(), Map.of(), -1, 0, false, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new BitCoverageSnapshot("surface", 0, 0, Map.of(), Map.of(), 0, -1, false, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new BitCoverageSnapshot("surface", 0, 0, Map.of(), Map.of(), 0, 0, false, -1, 0, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new BitCoverageSnapshot("surface", 0, 0, Map.of(), Map.of(), 0, 0, false, 0, -1, Map.of()));
    }

    private static byte[] buildSampleExecStream() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(JacocoCoverageProvider.BLOCK_HEADER);
            out.writeChar(JacocoCoverageProvider.MAGIC_NUMBER);
            out.writeChar(0x1007);

            out.writeByte(JacocoCoverageProvider.BLOCK_SESSIONINFO);
            out.writeUTF("bit-test-session");
            out.writeLong(100L);
            out.writeLong(200L);

            writeExecRecord(out, 1L, "io/github/dailystruggle/rtp/common/RTP", new boolean[] {true, false, true, false});
            writeExecRecord(out, 1L, "io/github/dailystruggle/rtp/common/RTP", new boolean[] {false, true, false, false});
            writeExecRecord(out, 2L, "io/github/dailystruggle/rtp/bukkit/BukkitRTP", new boolean[] {true, false});
            writeExecRecord(out, 3L, "io/github/dailystruggle/rtp/paper/PaperRTP", new boolean[] {true, true});
            writeExecRecord(out, 4L, "io/github/dailystruggle/rtp/folia/FoliaRTP", new boolean[] {false, true, false});
            writeExecRecord(out, 5L, "io/github/dailystruggle/rtp/fabric/FabricRTP", new boolean[] {true, false, true, false});
            writeExecRecord(out, 6L, "io/github/dailystruggle/rtp/neoforge/NeoForgeRTP", new boolean[] {true});
            boolean[] sixteenProbes = new boolean[16];
            for (int i = 0; i < 10; i++) sixteenProbes[i] = true;
            writeExecRecord(out, 7L, "io/github/dailystruggle/rtp/anvil/AnvilReader", sixteenProbes);
            writeExecRecord(out, 8L, "io/github/dailystruggle/rtp/actionaddon/ActionAddon", new boolean[] {true, true, false});
            writeExecRecord(out, 9L, "io/github/dailystruggle/bit/BitRunner", new boolean[] {true, true});
            writeExecRecord(out, 10L, "org/yaml/snakeyaml/Yaml", new boolean[] {true, true, true});
        }
        return raw.toByteArray();
    }

    private static void writeExecRecord(
            DataOutputStream out, long classId, String className, boolean[] probes) throws IOException {
        out.writeByte(JacocoCoverageProvider.BLOCK_EXECUTIONDATA);
        out.writeLong(classId);
        out.writeUTF(className);
        writeVarInt(out, probes.length);
        int buffer = 0;
        int bit = 0;
        for (boolean probe : probes) {
            if (probe) buffer |= (1 << bit);
            if (++bit == 8) {
                out.writeByte(buffer);
                buffer = 0;
                bit = 0;
            }
        }
        if (bit > 0) {
            out.writeByte(buffer);
        }
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        if ((value & ~0x7F) == 0) {
            out.writeByte(value);
        } else {
            out.writeByte(0x80 | (value & 0x7F));
            writeVarInt(out, value >>> 7);
        }
    }

    public static final class CustomServiceProvider implements BitCoverageProvider {
        @Override
        public String id() {
            return "custom";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public BitCoverageSnapshot snapshot(BitRunner runner) {
            return SurfaceCoverageProvider.INSTANCE.snapshot(runner);
        }
    }
}
