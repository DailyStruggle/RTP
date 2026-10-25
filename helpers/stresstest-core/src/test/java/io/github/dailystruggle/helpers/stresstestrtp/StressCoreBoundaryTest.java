package io.github.dailystruggle.helpers.stresstestrtp;

import io.github.dailystruggle.server.api.ServerAccessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architectural boundary and SPI contract tests for {@code stresstest-core}
 * (ADR-122 Decisions 1, 2, 3, 4, 5).
 */
class StressCoreBoundaryTest {

    private static final List<Pattern> FORBIDDEN_IMPORTS = List.of(
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?org\\.bukkit\\..*", Pattern.MULTILINE),
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?io\\.papermc\\..*", Pattern.MULTILINE),
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?net\\.minecraft\\..*", Pattern.MULTILINE),
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?net\\.fabricmc\\..*", Pattern.MULTILINE),
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?net\\.neoforged\\..*", Pattern.MULTILINE),
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?io\\.github\\.dailystruggle\\.rtp\\..*", Pattern.MULTILINE)
    );

    @Test
    @DisplayName("ADR-122 Decision 1: stresstest-core main sources import no platform or rtp types")
    void coreSourcesImportNoPlatformOrRtpTypes() throws IOException {
        Path srcRoot = locateCoreMainJava();
        assertTrue(Files.isDirectory(srcRoot), "Expected stresstest-core main java dir: " + srcRoot);
        List<Path> javaFiles;
        try (var walk = Files.walk(srcRoot)) {
            javaFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertFalse(javaFiles.isEmpty(), "stresstest-core must contain Java source files");
        for (Path file : javaFiles) {
            String src = Files.readString(file, StandardCharsets.UTF_8);
            for (Pattern p : FORBIDDEN_IMPORTS) {
                assertFalse(p.matcher(src).find(),
                        () -> "Forbidden import matching " + p.pattern() + " in " + file);
            }
        }
    }

    @Test
    @DisplayName("ADR-122 Decision 2 & 3: StressHost extends ServerAccessor and reports unsupported censuses as 'not measured'")
    void stressHostExtendsServerAccessorAndReportsNotMeasured() {
        assertTrue(ServerAccessor.class.isAssignableFrom(StressHost.class),
                "StressHost must extend io.github.dailystruggle.server.api.ServerAccessor");
        StressHost.Census unmeasured = StressHost.Census.notMeasured();
        assertFalse(unmeasured.measured());
        assertEquals("not measured", unmeasured.status());
        assertEquals(StressHost.NOT_MEASURED, unmeasured.status());

        StressHost defaultHost = new MinimalHost();
        assertFalse(defaultHost.listenerCensus().measured());
        assertEquals("not measured", defaultHost.listenerCensus().status());
        assertFalse(defaultHost.pendingTaskCensus().measured());
        assertEquals("not measured", defaultHost.pendingTaskCensus().status());
        assertFalse(defaultHost.chunkTicketCensus().measured());
        assertEquals("not measured", defaultHost.chunkTicketCensus().status());
        assertEquals("listeners=not measured pending_tasks=not measured chunk_tickets=not measured",
                LeakProbe.describeHostCensuses(defaultHost));
    }

    @Test
    @DisplayName("ADR-122 Decision 5: CoverageProbe computes includes= from mod origin directories and jars")
    void coverageIncludesFromModOrigins(@TempDir Path tmp) throws IOException {
        Path modClassesDir = tmp.resolve("leafrtp-mod");
        Path classFile = modClassesDir.resolve("io/github/dailystruggle/rtp/fabric/FabricMod.class");
        Files.createDirectories(classFile.getParent());
        Files.writeString(classFile, "stub", StandardCharsets.UTF_8);

        StressHost.LoadedPlugin mod = new StressHost.LoadedPlugin(
                "leafrtp", "1.0.0", getClass().getClassLoader(),
                null, List.of(modClassesDir), tmp, true, "");
        StressHost.LoadedPlugin harness = new StressHost.LoadedPlugin(
                "stresstestrtp", "1.0.0", getClass().getClassLoader(),
                null, List.of(), tmp, true, "io.github.dailystruggle.helpers.stresstestrtp");

        String includes = CoverageProbe.includesForPlugins(List.of(mod, harness));
        assertEquals("io.github.dailystruggle.rtp.*", includes);
    }

    @Test
    @DisplayName("ADR-122 Decision 5: HarnessTeleporter routes direct teleport through StressHost")
    void harnessTeleporterRoutesThroughStressHost() {
        AtomicReference<String> lastCall = new AtomicReference<>();
        StressHost host = new MinimalHost() {
            @Override public boolean canTeleportDirect() { return true; }
            @Override
            public void teleportDirect(StressPlayer player, int x, int z, Consumer<String> onFail) {
                lastCall.set(player.name() + "@" + x + "," + z);
            }
        };
        HarnessTeleporter tp = new HarnessTeleporter(host, 64);
        assertTrue(tp.available());
        StressPlayer player = new StressPlayer() {
            private final UUID id = UUID.randomUUID();
            @Override public UUID uuid() { return id; }
            @Override public String name() { return "Bot_01"; }
            @Override public boolean isOnline() { return true; }
            @Override public StressLocation location() { return new StressLocation("world", 0, 64, 0); }
            @Override public boolean hasPermission(String node) { return true; }
        };
        tp.teleport(player, err -> {});
        assertEquals(1L, tp.issued());
        assertEquals(0L, tp.failed());
        assertNotNull(lastCall.get());
        assertTrue(lastCall.get().startsWith("Bot_01@"));
    }

    @Test
    @DisplayName("StressConfig parses default config.yml including target-commands, ramp stages, and nested sections")
    void stressConfigParsesDefaultConfigYml() {
        StressConfig cfg = StressConfig.fromResource(StressConfig.class, "/config.yml");
        List<Targets.Entry> targets = Targets.load(cfg);
        assertFalse(targets.isEmpty());
        assertEquals("rtp", targets.get(0).label);
        assertEquals(List.of(5.0, 10.0, 20.0, 40.0, 80.0, 160.0, 320.0), cfg.getDoubleList("ramp.stages"));
        assertEquals(0.95, cfg.getDouble("ramp.pass.min-achieved-fraction", 0.0), 1e-6);
        assertEquals(50.0, cfg.getDouble("ramp.pass.max-mspt-p95", 0.0), 1e-6);
    }

    private static Path locateCoreMainJava() {
        for (String rel : List.of(
                "src/main/java/io/github/dailystruggle/helpers/stresstestrtp",
                "../stresstest-core/src/main/java/io/github/dailystruggle/helpers/stresstestrtp",
                "helpers/stresstest-core/src/main/java/io/github/dailystruggle/helpers/stresstestrtp")) {
            Path p = Path.of(rel);
            if (Files.isDirectory(p)) return p;
        }
        return Path.of("src/main/java/io/github/dailystruggle/helpers/stresstestrtp");
    }

    private static class MinimalHost implements StressHost {
        @Override public Logger getLogger() { return Logger.getAnonymousLogger(); }
        @Override public StressConfig getStressConfig() { return new StressConfig(); }
        @Override public Path dataFolder() { return Path.of("."); }
        @Override public void dispatchConsoleCommand(String commandLine) {}
        @Override public Collection<StressPlayer> onlinePlayers() { return List.of(); }
        @Override public StressPlayer player(UUID uuid) { return null; }
        @Override public StressPlayer playerExact(String name) { return null; }
        @Override public List<StressWorld> worlds() { return List.of(); }
        @Override public StressWorld world(String name) { return null; }
        @Override public void runGlobal(Runnable task) { task.run(); }
        @Override public Object runGlobalLater(Runnable task, long delayTicks) { return null; }
        @Override public Object runGlobalTimer(Runnable task, long delayTicks, long periodTicks) { return null; }
        @Override public void runAsync(Runnable task) { task.run(); }
        @Override public boolean cancelTask(Object handle) { return false; }
        @Override public boolean isTickThread() { return true; }
        @Override public List<LoadedPlugin> loadedPlugins() { return List.of(); }
        @Override public Runner runner() { return null; }
        @Override public MetricsRecorder recorder() { return null; }
        @Override public TpsMsptHeapSampler sampler() { return null; }
        @Override public void beginRun() {}
    }
}
