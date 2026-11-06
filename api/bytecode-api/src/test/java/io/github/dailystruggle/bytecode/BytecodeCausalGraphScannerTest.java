package io.github.dailystruggle.bytecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.CausalFinding;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.CausalGraphReport;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.ConfidenceTier;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.FindingKind;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.ListenerRegistrationMode;
import io.github.dailystruggle.bytecode.LlmConfigDocSynthesizer.LlmHookConfig;
import io.github.dailystruggle.bytecode.LlmConfigDocSynthesizer.Provider;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@link BytecodeCausalGraphScanner} first-order and second-order causal
 * auditing, config-gated listener registration recognition ({@code onEventParsing}), and
 * {@link LlmCausalChainAuditor} closed-world AST symbol grounding ({@code leaffuzz-ADR-001},
 * {@code ADR-125}, {@code corpus-atlas-ADR-002}).
 */
@DisplayName("BytecodeCausalGraphScanner & LlmCausalChainAuditor Tests (REQ-CFG-ATLAS-001, REQ-SLEUTH-FUZZ-004)")
class BytecodeCausalGraphScannerTest {

    /** Synthetic EventHandler annotation whose bytecode name matches Bukkit's @EventHandler. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface EventHandler {
    }

    static final class PlayerMoveEvent {
    }

    static final class PlayerJoinEvent {
    }

    static final class PlayerTeleportEvent {
    }

    static final class FakeWorld {
        void getChunkAt(int x, int z) {
        }
    }

    static final class FakePlayer {
        void sendMessage(String msg) {
        }

        void teleportAsync(String destination) {
        }
    }

    /**
     * Order 1 Unregistered Listener fixture: has @EventHandler but is never instantiated anywhere.
     */
    static final class UnregisteredMoveCancelListener {
        @EventHandler
        public void onMove(PlayerMoveEvent event) {
            if (event == null) {
                return;
            }
        }
    }

    /**
     * Unconditionally registered listener fixture: instantiated on startup.
     */
    static final class ActiveJoinListener {
        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            if (event == null) {
                return;
            }
        }
    }

    /**
     * Inline config-gated listener fixture: instantiated inside an {@code if (flag)} block.
     */
    static final class InlineConfigGatedListener {
        @EventHandler
        public void onTeleport(PlayerTeleportEvent event) {
            if (event == null) {
                return;
            }
        }
    }

    /**
     * Early-return config-gated listener fixture: instantiated after {@code if (!flag) return;}.
     */
    static final class EarlyReturnConfigGatedListener {
        @EventHandler
        public void onTeleport(PlayerTeleportEvent event) {
            if (event == null) {
                return;
            }
        }
    }

    /**
     * Helper-method config-gated listener fixture: instantiated inside a helper method that is
     * only invoked when a config condition is true.
     */
    static final class HelperMethodConfigGatedListener {
        @EventHandler
        public void onTeleport(PlayerTeleportEvent event) {
            if (event == null) {
                return;
            }
        }
    }

    /**
     * Synthetic plugin main &amp; config loader class testing:
     * <ul>
     *   <li>Unconditional listener registration ({@link ActiveJoinListener})</li>
     *   <li>Generalized config-gated listener registration across all 3 bytecode patterns:
     *       inline {@code if}, early-return guard, and caller-gated helper method</li>
     *   <li>Unread config field ({@code cancelOnDamage} loaded from {@code warmup.cancel-on-damage} with 0 readers)</li>
     *   <li>Unscaled seconds config field ({@code cooldownSeconds} loaded from {@code cooldown.seconds} and added to {@code System.currentTimeMillis()})</li>
     * </ul>
     */
    @SuppressWarnings("unused")
    static final class SyntheticVibecodedPlugin {
        private boolean cancelOnDamage;
        private int cooldownSeconds;

        boolean getBoolean(String key) {
            return false;
        }

        int getInt(String key) {
            return 5;
        }

        void registerEvents(Object listener) {
        }

        void getCommand(String name) {
        }

        void runTaskAsynchronously(Runnable task) {
            task.run();
        }

        public void onEnable() {
            getCommand("wild");
            loadConfig();
            setupListeners();
            registerEarlyGuardListener();
        }

        public void loadConfig() {
            this.cancelOnDamage = getBoolean("warmup.cancel-on-damage");
            this.cooldownSeconds = getInt("cooldown.seconds");
        }

        private void setupListeners() {
            boolean inlineGate = getBoolean("features.inline-event-gate");
            if (inlineGate) {
                registerEvents(new InlineConfigGatedListener());
            }
            if (getBoolean("features.helper-event-gate")) {
                registerOptionalHelperListener();
            }
            registerEvents(new ActiveJoinListener());
        }

        private void registerEarlyGuardListener() {
            if (!getBoolean("features.early-return-gate")) {
                return;
            }
            registerEvents(new EarlyReturnConfigGatedListener());
        }

        private void registerOptionalHelperListener() {
            registerEvents(new HelperMethodConfigGatedListener());
        }

        public long computeNextAllowedTimestamp() {
            // Bug: adds unscaled cooldownSeconds directly to System.currentTimeMillis() without * 1000L
            return System.currentTimeMillis() + this.cooldownSeconds;
        }

        public void spawnAsyncChunkLoad(FakeWorld world) {
            // Bug (S-005): calls synchronous World#getChunkAt inside an async lambda
            runTaskAsynchronously(() -> world.getChunkAt(0, 0));
        }

        public void swallowDatabaseException() {
            try {
                mightThrowIo();
            } catch (IOException ignored) {
                // Bug (S-004): empty catch handler returning silently
                return;
            }
        }

        private void mightThrowIo() throws IOException {
            throw new IOException("disk full");
        }
    }

    /**
     * Order 2 Hollow + Orphaned Supplier command fixture:
     * <ul>
     *   <li>Spawns {@code CompletableFuture.supplyAsync} and immediately drops the return value ({@code POP}).</li>
     *   <li>Only calls {@code player.sendMessage(...)} with zero teleport/world/inventory mutations.</li>
     * </ul>
     */
    static final class HollowOrphanedWildCommand {
        public boolean onCommand(FakePlayer player) {
            CompletableFuture.supplyAsync(() -> "world:100,64,100");
            player.sendMessage("Teleporting you to the wild...");
            return true;
        }
    }

    @Test
    @DisplayName("Order 1 & Order 2: Detects unregistered listener, dead config key, unwired command, unread field, unit mismatch, orphaned supplier, S-004 swallowed exception, S-005 async chunk load, and hollow command while respecting config-gated listeners")
    void detectsFirstAndSecondOrderBugsAndRespectsConfigGatedListeners() throws IOException {
        List<byte[]> classes = List.of(
                readClassBytes(UnregisteredMoveCancelListener.class),
                readClassBytes(ActiveJoinListener.class),
                readClassBytes(InlineConfigGatedListener.class),
                readClassBytes(EarlyReturnConfigGatedListener.class),
                readClassBytes(HelperMethodConfigGatedListener.class),
                readClassBytes(SyntheticVibecodedPlugin.class),
                readClassBytes(HollowOrphanedWildCommand.class)
        );

        List<JarConfigScanner.CommandDescriptor> commands = List.of(
                new JarConfigScanner.CommandDescriptor(
                        "wild",
                        "VibecodedWild",
                        "Teleports player to a safe random location",
                        "/wild",
                        List.of(),
                        "wild.use",
                        "true"
                ),
                new JarConfigScanner.CommandDescriptor(
                        "unwiredwarp",
                        "VibecodedWild",
                        "Teleports player to a warp",
                        "/unwiredwarp",
                        List.of(),
                        "wild.warp",
                        "op"
                )
        );

        String configYml = """
                features:
                  inline-event-gate: false
                  early-return-gate: false
                  helper-event-gate: false
                warmup:
                  # Cancel warmup when player takes damage
                  cancel-on-damage: true
                  # Completely dead key never referenced in bytecode
                  phantom-particle-spiral: true
                cooldown:
                  seconds: 30
                """;

        CausalGraphReport report = BytecodeCausalGraphScanner.analyzeClasses(
                "plugin:vibecodedwild",
                "VibecodedWild",
                classes,
                commands,
                Map.of("config.yml", configYml)
        );

        assertNotNull(report);

        // 1. Verify Generalized Listener Registration Modes across all 3 config-gating shapes
        Map<String, ListenerRegistrationMode> regMap = report.listenerRegistrations();
        assertEquals(
                ListenerRegistrationMode.UNREGISTERED,
                regMap.get(UnregisteredMoveCancelListener.class.getName()),
                "UnregisteredMoveCancelListener must be flagged as UNREGISTERED"
        );
        assertEquals(
                ListenerRegistrationMode.REGISTERED_UNCONDITIONAL,
                regMap.get(ActiveJoinListener.class.getName()),
                "ActiveJoinListener must be recognized as REGISTERED_UNCONDITIONAL"
        );
        assertEquals(
                ListenerRegistrationMode.REGISTERED_CONFIG_GATED,
                regMap.get(InlineConfigGatedListener.class.getName()),
                "Inline if(config) listener must be recognized as REGISTERED_CONFIG_GATED"
        );
        assertEquals(
                ListenerRegistrationMode.REGISTERED_CONFIG_GATED,
                regMap.get(EarlyReturnConfigGatedListener.class.getName()),
                "Early-return if(!config) return; listener must be recognized as REGISTERED_CONFIG_GATED"
        );
        assertEquals(
                ListenerRegistrationMode.REGISTERED_CONFIG_GATED,
                regMap.get(HelperMethodConfigGatedListener.class.getName()),
                "Caller-gated helper method listener must be recognized as REGISTERED_CONFIG_GATED"
        );
        assertEquals(
                "features.inline-event-gate",
                report.configGatedListeners().get(InlineConfigGatedListener.class.getName())
        );
        assertEquals(
                "features.early-return-gate",
                report.configGatedListeners().get(EarlyReturnConfigGatedListener.class.getName())
        );
        assertEquals(
                "features.helper-event-gate",
                report.configGatedListeners().get(HelperMethodConfigGatedListener.class.getName())
        );

        // Ensure ConfigGatedOnEventTeleports does NOT appear in CAUSAL_UNREGISTERED_LISTENER findings
        List<CausalFinding> unregFindings = report.findings().stream()
                .filter(f -> f.kind() == FindingKind.CAUSAL_UNREGISTERED_LISTENER)
                .toList();
        assertEquals(1, unregFindings.size());
        assertTrue(unregFindings.get(0).entryPointSymbol().contains("UnregisteredMoveCancelListener"));
        assertEquals(ConfidenceTier.CONFIRMED, unregFindings.get(0).confidenceTier());

        // 2. Verify all expected Order 1 and Order 2 FindingKinds are detected
        Set<FindingKind> detectedKinds = report.findings().stream()
                .map(CausalFinding::kind)
                .collect(Collectors.toSet());

        assertTrue(detectedKinds.contains(FindingKind.CAUSAL_UNREGISTERED_LISTENER), "Must detect Order 1 unregistered listener");
        assertTrue(detectedKinds.contains(FindingKind.CAUSAL_UNWIRED_COMMAND), "Must detect Order 1 unwired command /unwiredwarp");
        assertTrue(detectedKinds.contains(FindingKind.CAUSAL_DEAD_CONFIG_KEY), "Must detect Order 1 dead config.yml key warmup.phantom-particle-spiral");
        assertTrue(detectedKinds.contains(FindingKind.CAUSAL_UNREAD_CONFIG_FIELD), "Must detect Order 2 unread field cancelOnDamage");
        assertTrue(detectedKinds.contains(FindingKind.CAUSAL_UNIT_MISMATCH), "Must detect Order 2 seconds + currentTimeMillis unit mismatch");
        assertTrue(detectedKinds.contains(FindingKind.CAUSAL_ORPHANED_SUPPLIER), "Must detect Order 2 popped CompletableFuture.supplyAsync");
        assertTrue(detectedKinds.contains(FindingKind.CAUSAL_SWALLOWED_FAILURE), "Must detect Order 2 S-004 empty catch block");
        assertTrue(detectedKinds.contains(FindingKind.CAUSAL_ASYNC_MAIN_THREAD_SINK), "Must detect Order 2 S-005 World#getChunkAt in async lambda");
        assertTrue(detectedKinds.contains(FindingKind.CAUSAL_HOLLOW_COMMAND), "Must detect Order 2 hollow command sending message only");

        // Verify runtime corroboration upgrades hollow command confidence
        CausalFinding hollow = report.findings().stream()
                .filter(f -> f.kind() == FindingKind.CAUSAL_HOLLOW_COMMAND)
                .findFirst()
                .orElseThrow();
        CausalFinding corroborated = hollow.withRuntimeCorroboration(18, 0, 0);
        assertTrue(corroborated.confidencePercent() >= 92);
        assertTrue(corroborated.causalDefect().contains("Corroborated by Stage 2 live fuzzing: 18 execution(s)"));
        assertTrue(corroborated.formatSummaryLine().contains("CAUSAL_HOLLOW_COMMAND"));
        assertTrue(corroborated.formatSummaryLine().contains("->"));
    }

    @Test
    @DisplayName("LlmCausalChainAuditor: Closed-world AST symbol gate accepts grounded symbols and rejects hallucinated Class#method references")
    void llmAuditorEnforcesClosedWorldSymbolGate() throws IOException {
        List<byte[]> classes = List.of(
                readClassBytes(HollowOrphanedWildCommand.class),
                readClassBytes(SyntheticVibecodedPlugin.class),
                readClassBytes(FakePlayer.class)
        );
        CausalGraphReport report = BytecodeCausalGraphScanner.analyzeClasses(
                "plugin:vibecodedwild",
                "VibecodedWild",
                classes,
                List.of(),
                Map.of()
        );

        assertTrue(
                LlmCausalChainAuditor.passesClosedWorldSymbolGate(
                        "HollowOrphanedWildCommand#onCommand spawns CompletableFuture#supplyAsync but drops the result before calling Player#teleportAsync.",
                        report.closedWorldSymbols()
                ),
                "Explanation citing real class/method symbols from the JAR and standard Bukkit/JDK sinks must pass"
        );

        assertFalse(
                LlmCausalChainAuditor.passesClosedWorldSymbolGate(
                        "The crash occurs because HallucinatedTeleportManager#dispatchAsync returns null.",
                        report.closedWorldSymbols()
                ),
                "Explanation citing a hallucinated Class#method outside the JAR must be rejected"
        );

        // Verify LlmCausalChainAuditor mock transport enrichment
        LlmHookConfig cfg = new LlmHookConfig(
                Provider.GEMINI,
                "gemini-2.5-flash",
                "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
                "",
                11434,
                "test-api-key",
                "GEMINI_API_KEY",
                3000,
                220
        );
        LlmCausalChainAuditor groundedAuditor = new LlmCausalChainAuditor(
                cfg,
                (url, headers, body, timeout) -> "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"explanation\\\":\\\"HollowOrphanedWildCommand#onCommand pops the CompletableFuture#supplyAsync result without chaining Player#teleportAsync.\\\"}\"}]}}]}"
        );

        List<CausalFinding> enriched = groundedAuditor.enrichFindingsWithExplanations(report, 2);
        assertTrue(
                enriched.stream().anyMatch(f -> f.llmExplanation().contains("HollowOrphanedWildCommand#onCommand")),
                "Grounded LLM explanation must be attached to the finding"
        );

        String crashExplanation = groundedAuditor.explainProvenFuzzFault(
                "exc:NullPointerException@HollowOrphanedWildCommand#onCommand:42",
                "HollowOrphanedWildCommand#onCommand:42",
                List.of("wild -999999"),
                "quit_lt1s",
                report
        );
        assertTrue(crashExplanation.contains("HollowOrphanedWildCommand#onCommand"));

        // Hallucinating transport must be rejected cleanly (returns empty string)
        LlmCausalChainAuditor hallucinatingAuditor = new LlmCausalChainAuditor(
                cfg,
                (url, headers, body, timeout) -> "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"explanation\\\":\\\"FakeClass#fakeMethod threw an exception.\\\"}\"}]}}]}"
        );
        assertEquals(
                "",
                hallucinatingAuditor.explainProvenFuzzFault(
                        "exc:NullPointerException",
                        "HollowOrphanedWildCommand#onCommand:42",
                        List.of("wild"),
                        "quit_lt1s",
                        report
                ),
                "Hallucinated symbol in LLM explanation must be rejected by closed-world AST gate"
        );

        // Verify auditCausalChainsWithLlm across OPENAI_COMPATIBLE and ANTHROPIC providers
        LlmHookConfig openAiCfg = new LlmHookConfig(
                Provider.OPENAI_COMPATIBLE,
                "gpt-4o-mini",
                "https://api.openai.com/v1/chat/completions",
                "",
                11434,
                "sk-test",
                "OPENAI_API_KEY",
                3000,
                220
        );
        LlmCausalChainAuditor openAiAuditor = new LlmCausalChainAuditor(
                openAiCfg,
                (url, headers, body, timeout) -> "{\"choices\":[{\"message\":{\"content\":\"{\\\"status\\\":\\\"BUG\\\",\\\"declaredSurface\\\":\\\"HollowOrphanedWildCommand#onCommand teleports player\\\",\\\"causalDefect\\\":\\\"HollowOrphanedWildCommand#onCommand only calls FakePlayer#sendMessage\\\",\\\"clearsWhen\\\":\\\"Call FakePlayer#teleportAsync\\\"}\"}}]}"
        );
        List<CausalFinding> advisoryFindings = openAiAuditor.auditCausalChainsWithLlm(report, 4);
        assertFalse(advisoryFindings.isEmpty());
        assertEquals(ConfidenceTier.ADVISORY, advisoryFindings.get(0).confidenceTier());
        assertEquals(FindingKind.CAUSAL_LLM_SEMANTIC_MISMATCH, advisoryFindings.get(0).kind());

        LlmHookConfig anthropicCfg = new LlmHookConfig(
                Provider.ANTHROPIC,
                "claude-3-5-haiku-latest",
                "https://api.anthropic.com/v1/messages",
                "",
                11434,
                "sk-ant-test",
                "ANTHROPIC_API_KEY",
                3000,
                220
        );
        LlmCausalChainAuditor anthropicAuditor = new LlmCausalChainAuditor(
                anthropicCfg,
                (url, headers, body, timeout) -> "{\"content\":[{\"text\":\"{\\\"explanation\\\":\\\"HollowOrphanedWildCommand#onCommand drops the future.\\\"}\"}]}"
        );
        assertTrue(
                anthropicAuditor.explainProvenFuzzFault(
                        "exc:NPE",
                        "HollowOrphanedWildCommand#onCommand:10",
                        List.of("wild"),
                        "repeat",
                        report
                ).contains("HollowOrphanedWildCommand#onCommand")
        );

        // Verify Provider.OFF returns immediately with zero HTTP calls
        LlmHookConfig offCfg = new LlmHookConfig(
                Provider.OFF,
                "none",
                "",
                "",
                11434,
                "",
                "GEMINI_API_KEY",
                1000,
                200
        );
        LlmCausalChainAuditor offAuditor = new LlmCausalChainAuditor(offCfg);
        assertEquals("", offAuditor.explainProvenFuzzFault("exc:NPE", "frame", List.of(), "repeat", report));
        assertTrue(offAuditor.auditCausalChainsWithLlm(report, 4).isEmpty());
        assertEquals(report.findings().size(), offAuditor.enrichFindingsWithExplanations(report, 4).size());
    }

    @Test
    @DisplayName("scanJar: Scans synthetic plugin .jar archive from disk and renders Jminor prompt blocks")
    void scanJarExtractsCausalChainsAndFindingsFromArchive(@TempDir Path tempDir) throws IOException {
        Path jarFile = tempDir.resolve("VibecodedWild.jar");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jarFile.toFile()))) {
            zos.putNextEntry(new ZipEntry("plugin.yml"));
            zos.write("""
                    name: VibecodedWild
                    version: 1.0.0
                    main: io.github.dailystruggle.bytecode.BytecodeCausalGraphScannerTest$SyntheticVibecodedPlugin
                    commands:
                      wild:
                        description: Teleports player to a safe random location
                      unwiredwarp:
                        description: Teleports player to warp
                    """.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("config.yml"));
            zos.write("""
                    features:
                      inline-event-gate: false
                      early-return-gate: false
                      helper-event-gate: false
                    warmup:
                      cancel-on-damage: true
                      dead-key: 42
                    cooldown:
                      seconds: 15
                    """.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            for (Class<?> cls : List.of(
                    UnregisteredMoveCancelListener.class,
                    ActiveJoinListener.class,
                    InlineConfigGatedListener.class,
                    EarlyReturnConfigGatedListener.class,
                    HelperMethodConfigGatedListener.class,
                    SyntheticVibecodedPlugin.class,
                    HollowOrphanedWildCommand.class
            )) {
                String entryName = cls.getName().replace('.', '/') + ".class";
                zos.putNextEntry(new ZipEntry(entryName));
                zos.write(readClassBytes(cls));
                zos.closeEntry();
            }
        }

        CausalGraphReport scanned = BytecodeCausalGraphScanner.scanJar(jarFile);
        assertNotNull(scanned);
        assertEquals("VibecodedWild", scanned.displayName());
        assertFalse(scanned.entryChains().isEmpty());
        assertFalse(scanned.findings().isEmpty());
        for (var chain : scanned.entryChains()) {
            String block = chain.toJminorPromptBlock();
            assertTrue(block.contains("CAUSAL_CHAIN [ENTRY_"));
            assertTrue(block.contains("REACHABLE_SINKS:"));
        }
        assertNotNull(CausalGraphReport.empty("EmptyTest"));
        assertEquals(100, ConfidenceTier.CONFIRMED.defaultPercent());
        assertEquals("CONFIRMED 100%", ConfidenceTier.CONFIRMED.badge());
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface CommandAlias {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Subcommand {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface CommandPermission {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface Description {
        String value();
    }

    static final class CustomTeleportRequestEvent {
        final FakePlayer player;
        final String destination;

        CustomTeleportRequestEvent(FakePlayer player, String destination) {
            this.player = player;
            this.destination = destination;
        }
    }

    static final class FakePluginManager {
        void callEvent(Object event) {
        }
    }

    static final class DecoupledTeleportSubscriber {
        @EventHandler
        public void onCustomTeleport(CustomTeleportRequestEvent event) {
            if (event != null && event.player != null) {
                event.player.teleportAsync(event.destination);
            }
        }
    }

    @CommandAlias("acfwild|awild")
    @CommandPermission("acfwild.use")
    static final class AnnotatedAndDecoupledCommandFixture {
        private final java.util.Queue<Runnable> pendingJobs = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final FakePluginManager pluginManager = new FakePluginManager();

        public void registerAll() {
            DecoupledTeleportSubscriber sub = new DecoupledTeleportSubscriber();
            if (sub.hashCode() == -1) {
                pollBackgroundQueue();
            }
        }

        @Subcommand("confirm|force")
        @Description("Teleports player after confirmation")
        public void onConfirm(FakePlayer player, int radius) {
            pluginManager.callEvent(new CustomTeleportRequestEvent(player, "world:" + radius));
        }

        public boolean onCommand(FakePlayer sender, String cmd, String label, String[] args) {
            if (args.length > 0 && args[0].equalsIgnoreCase("confirm")) {
                int dist = args.length > 1 ? Integer.parseInt(args[1]) : 100;
                pendingJobs.offer(() -> sender.teleportAsync("queued:" + dist));
                return true;
            }
            if (args.length > 0 && args[0].equals("reload")) {
                if (hasPermission(sender, "acfwild.admin.reload")) {
                    sender.sendMessage("Reloaded!");
                }
                return true;
            }
            return false;
        }

        private boolean hasPermission(FakePlayer sender, String node) {
            return sender != null && node != null && !node.isEmpty();
        }

        public void pollBackgroundQueue() {
            Runnable job = pendingJobs.poll();
            if (job != null) {
                job.run();
                new FakePlayer().teleportAsync("worker-drain");
            }
        }
    }

    @Test
    @DisplayName("Decoupled dispatch & white-box schemas: Traces callEvent -> @EventHandler, Queue offer -> poll, ACF/Cloud annotations, and BytecodeCommandSchema literals/parsers/permissions")
    void tracesDecoupledEventAndQueueEdgesAndExtractsWhiteBoxCommandSchemas() throws IOException {
        List<byte[]> classes = List.of(
                readClassBytes(CustomTeleportRequestEvent.class),
                readClassBytes(FakePluginManager.class),
                readClassBytes(DecoupledTeleportSubscriber.class),
                readClassBytes(AnnotatedAndDecoupledCommandFixture.class),
                readClassBytes(FakePlayer.class)
        );
        List<JarConfigScanner.CommandDescriptor> declaredCommands = List.of(
                new JarConfigScanner.CommandDescriptor(
                        "acfwild",
                        "AcfWild",
                        "Teleports player via ACF command",
                        "/acfwild confirm <radius>",
                        List.of("awild"),
                        "acfwild.use",
                        "true"
                ),
                new JarConfigScanner.CommandDescriptor(
                        "queuedwild",
                        "AcfWild",
                        "Teleports player via background queue",
                        "/queuedwild confirm <dist>",
                        List.of(),
                        "queuedwild.use",
                        "true"
                )
        );

        CausalGraphReport report = BytecodeCausalGraphScanner.analyzeClasses(
                "plugin:acfwild",
                "AcfWild",
                classes,
                declaredCommands,
                Map.of()
        );

        // 1. Verify @Subcommand("confirm|force") on @CommandAlias("acfwild|awild") is discovered as COMMAND entry point
        //    and traces through CustomTeleportRequestEvent -> DecoupledTeleportSubscriber#onCustomTeleport -> teleportAsync
        var acfChain = report.entryChains().stream()
                .filter(c -> c.entrySymbol().contains("onConfirm"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected annotated @Subcommand method onConfirm to be harvested as COMMAND root"));
        assertTrue(
                acfChain.declaredBehavior().contains("/acfwild confirm")
                        && acfChain.declaredBehavior().contains("Teleports player after confirmation"),
                "Expected @CommandAlias + @Subcommand + @Description declared behavior synthesis, got: " + acfChain.declaredBehavior()
        );
        assertTrue(
                acfChain.reachableEffectSinks().contains("EVENT_DISPATCH") && acfChain.reachableEffectSinks().contains("TELEPORT"),
                "Expected callEvent(new CustomTeleportRequestEvent) to link to @EventHandler subscriber's TELEPORT sink, got: " + acfChain.reachableEffectSinks()
        );

        // 2. Verify onCommand queue producer (pendingJobs.offer) links to pollBackgroundQueue consumer (pendingJobs.poll) -> TELEPORT
        var onCommandChain = report.entryChains().stream()
                .filter(c -> c.entrySymbol().contains("onCommand"))
                .findFirst()
                .orElseThrow();
        assertTrue(
                onCommandChain.reachableEffectSinks().contains("QUEUE_HANDOFF") && onCommandChain.reachableEffectSinks().contains("TELEPORT"),
                "Expected queue producer to trace through QUEUE_HANDOFF into consumer's TELEPORT sink, got: " + onCommandChain.reachableEffectSinks()
        );

        // 3. Neither command should be flagged as CAUSAL_HOLLOW_COMMAND or CAUSAL_UNWIRED_COMMAND
        assertFalse(
                report.findings().stream().anyMatch(f -> f.kind() == FindingKind.CAUSAL_HOLLOW_COMMAND),
                "Decoupled event and queue commands delivering TELEPORT via subscriber/worker must not be flagged as hollow: " + report.findings()
        );
        assertFalse(
                report.findings().stream().anyMatch(f -> f.kind() == FindingKind.CAUSAL_UNWIRED_COMMAND && f.declaredSurface().contains("acfwild")),
                "@CommandAlias(\"acfwild\") must satisfy plugin.yml command wiring check"
        );

        // 4. Verify white-box BytecodeCommandSchema extraction (subcommand literals, argument parsers, inline permissions)
        assertFalse(report.commandSchemas().isEmpty(), "Expected BytecodeCommandSchema list to be populated");
        boolean foundConfirmAndReloadLiterals = report.commandSchemas().stream()
                .anyMatch(s -> s.subcommandLiterals().contains("confirm") && s.subcommandLiterals().contains("reload"));
        assertTrue(foundConfirmAndReloadLiterals, "Expected bytecode string comparisons args[0].equalsIgnoreCase(\"confirm\") and \"reload\" to be extracted: " + report.commandSchemas());

        boolean foundIntegerParser = report.commandSchemas().stream()
                .anyMatch(s -> s.argumentParsers().contains("brigadier:integer"));
        assertTrue(foundIntegerParser, "Expected Integer.parseInt / int parameter to extract brigadier:integer parser: " + report.commandSchemas());

        boolean foundInlinePermission = report.commandSchemas().stream()
                .anyMatch(s -> s.inlinePermissions().contains("acfwild.admin.reload") || s.inlinePermissions().contains("acfwild.use"));
        assertTrue(foundInlinePermission, "Expected inline hasPermission / @CommandPermission to be extracted: " + report.commandSchemas());
    }

    static final class AsyncRtpWorld {
        CompletableFuture<Long> getChunkAt(int x, int z) {
            return CompletableFuture.completedFuture(((long) x << 32) | (z & 0xFFFFFFFFL));
        }
    }

    static final class DescriptorAwareChunkAndFoliaFixture {
        private static void runTaskAsynchronously(Runnable task) {
            task.run();
        }

        public void validAsyncRtpWorldChunkCall(AsyncRtpWorld rtpWorld, FakePlayer player) {
            runTaskAsynchronously(() -> rtpWorld.getChunkAt(4, 8).thenAccept(key -> player.teleportAsync("chunk:" + key)));
        }

        public void droppedAsyncFutureCall(AsyncRtpWorld rtpWorld) {
            // Drops CompletableFuture<Long> on the bytecode stack via POP (S-004)
            rtpWorld.getChunkAt(1, 2);
        }

        public void syncChunkInFoliaDeclaredPlugin(FakeWorld syncWorld) {
            syncWorld.getChunkAt(0, 0);
        }
    }

    @Test
    @DisplayName("Descriptor-aware bytecode checks: Distinguishes CompletableFuture getChunkAt from sync getChunkAt, flags POP-ped futures, and audits folia-supported declarations")
    void distinguishesAsyncFutureChunkDescriptorFlagsPoppedFuturesAndAuditsFoliaSupport() throws IOException {
        List<byte[]> classes = List.of(
                readClassBytes(AsyncRtpWorld.class),
                readClassBytes(FakeWorld.class),
                readClassBytes(FakePlayer.class),
                readClassBytes(DescriptorAwareChunkAndFoliaFixture.class)
        );

        CausalGraphReport report = BytecodeCausalGraphScanner.analyzeClasses(
                "plugin:foliatest",
                "FoliaTestPlugin",
                classes,
                List.of(),
                Map.of("paper-plugin.yml", "name: FoliaTestPlugin\nfolia-supported: true\n")
        );

        // 1. validAsyncRtpWorldChunkCall calls AsyncRtpWorld#getChunkAt returning CompletableFuture<Long> -> must NOT be flagged as S-005
        assertFalse(
                report.findings().stream().anyMatch(f ->
                        f.kind() == FindingKind.CAUSAL_ASYNC_MAIN_THREAD_SINK
                                && f.causalDefect().contains("AsyncRtpWorld#getChunkAt")),
                "AsyncRtpWorld#getChunkAt(II)Ljava/util/concurrent/CompletableFuture; must not be flagged as synchronous main-thread chunk I/O: " + report.findings()
        );

        // 2. droppedAsyncFutureCall pops CompletableFuture<Long> -> must be flagged as CAUSAL_ORPHANED_SUPPLIER
        assertTrue(
                report.findings().stream().anyMatch(f ->
                        f.kind() == FindingKind.CAUSAL_ORPHANED_SUPPLIER
                                && f.entryPointSymbol().contains("droppedAsyncFutureCall")),
                "Popped CompletableFuture return value must be flagged as CAUSAL_ORPHANED_SUPPLIER: " + report.findings()
        );

        // 3. syncChunkInFoliaDeclaredPlugin invokes synchronous FakeWorld#getChunkAt while paper-plugin.yml declares folia-supported: true
        assertTrue(
                report.findings().stream().anyMatch(f ->
                        f.kind() == FindingKind.CAUSAL_ASYNC_MAIN_THREAD_SINK
                                && f.entryPointSymbol().contains("syncChunkInFoliaDeclaredPlugin")
                                && f.declaredSurface().contains("folia-supported: true")),
                "Synchronous FakeWorld#getChunkAt in a plugin declaring folia-supported: true must be flagged: " + report.findings()
        );
    }

    static final class FakeBukkitRegionGuard {
        static boolean isOwnedByCurrentRegion(Object target) {
            return target != null;
        }
    }

    static final class FakeMemoryTracker {
        static void trackTicket(String ticketId) {
        }
    }

    static final class ParityHazardsAndExemptionsFixture {
        // 1. CAUSAL-RACE: Lock-set mismatch (synchronized writer vs unlocked reader/writer)
        private int sharedCounter = 0;

        // 2. CAUSAL-RACE: Unsafe HashMap mutated concurrently without common lock
        private final java.util.HashMap<String, Integer> unsafeScores = new java.util.HashMap<>();

        // 3. Exemption: JCIP §3.1.4 volatile field with synchronized writers and unlocked reader
        private volatile int jcipSafeVolatile = 0;

        // 4. Exemption: @folia_region_guard serialized accessors
        private int regionConfinedState = 0;

        public synchronized void incrementCounterSync() {
            sharedCounter++;
        }

        public int readCounterUnlocked() {
            return sharedCounter;
        }

        public synchronized void writeJcipSafe(int val) {
            jcipSafeVolatile = val;
        }

        public int readJcipSafeUnlocked() {
            return jcipSafeVolatile;
        }

        public void mutateRegionConfined(Object entity) {
            if (FakeBukkitRegionGuard.isOwnedByCurrentRegion(entity)) {
                regionConfinedState++;
            }
        }

        public int readRegionConfined(Object entity) {
            if (FakeBukkitRegionGuard.isOwnedByCurrentRegion(entity)) {
                return regionConfinedState;
            }
            return 0;
        }

        private static void runTaskAsynchronously(Runnable r) {
            r.run();
        }

        @CommandAlias("racecmd")
        public boolean onCommand(FakePlayer sender, String cmd, String label, String[] args) {
            incrementCounterSync();
            unsafeScores.put("p1", readCounterUnlocked());
            runTaskAsynchronously(() -> unsafeScores.put("async", 99));
            // 2-hop synchronous main-thread I/O contagion (S-005 / CAUSAL-MAIN-IO)
            hopOneSyncIoHelper(new FakeWorld());
            return true;
        }

        private void hopOneSyncIoHelper(FakeWorld world) {
            hopTwoSyncChunkAndDiskIo(world);
        }

        private void hopTwoSyncChunkAndDiskIo(FakeWorld world) {
            world.getChunkAt(10, 20);
        }

        @CommandAlias("safeasync")
        public void onSafeAsyncIoCommand( java.nio.file.Path path) {
            CompletableFuture.supplyAsync(() -> {
                try {
                    return java.nio.file.Files.readAllBytes(path);
                } catch (IOException e) {
                    return new byte[0];
                }
            }).thenAccept(bytes -> {
                if (bytes != null) {
                    writeJcipSafe(bytes.length);
                }
            });
        }

        @EventHandler
        public void onLeakyResourceEvent(PlayerMoveEvent event) throws IOException {
            java.io.FileOutputStream leakedOut = new java.io.FileOutputStream("leaked.bin");
            leakedOut.write(1);
        }

        @EventHandler
        public void onSafeTryWithResourcesEvent(PlayerMoveEvent event) throws IOException {
            try (java.io.FileOutputStream safeOut = new java.io.FileOutputStream("safe.bin")) {
                safeOut.write(2);
            }
            acquireAndTrackTicket();
            try (java.io.FileOutputStream fromFactory = openStreamFactory("factory.bin")) {
                fromFactory.write(3);
            }
        }

        private void acquireAndTrackTicket() {
            acquireTicket("chunk:0,0");
            FakeMemoryTracker.trackTicket("chunk:0,0");
        }

        private void acquireTicket(String id) {
        }

        private java.io.FileOutputStream openStreamFactory(String name) throws IOException {
            return new java.io.FileOutputStream(name);
        }
    }

    @Test
    @DisplayName("corpus-atlas-ADR-002 Parity: Detects CAUSAL-RACE, CAUSAL-MAIN-IO, CAUSAL-LEAK, synthesizes Hoare EffectFrame/ProofObligations, and honors JCIP/Folia/try-with-resources exemptions")
    void detectsSharedStateRacesSyncMainIoResourceLeaksAndSynthesizesHoareFrames() throws IOException {
        List<byte[]> classes = List.of(
                readClassBytes(FakePlayer.class),
                readClassBytes(FakeWorld.class),
                readClassBytes(PlayerMoveEvent.class),
                readClassBytes(FakeBukkitRegionGuard.class),
                readClassBytes(FakeMemoryTracker.class),
                readClassBytes(ParityHazardsAndExemptionsFixture.class)
        );

        CausalGraphReport report = BytecodeCausalGraphScanner.analyzeClasses(
                "plugin:paritytest",
                "ParityTestPlugin",
                classes,
                List.of(new JarConfigScanner.CommandDescriptor("racecmd", "Runs race command", "/racecmd", "", List.of(), "", "plugin.yml")),
                Map.of()
        );

        // 1. CAUSAL-RACE: sharedCounter (LOCK_SET_MISMATCH) and unsafeScores (UNSAFE_COLLECTION_CONCURRENT_ACCESS / ASYNC_OR_REGION_UNSYNCHRONIZED)
        assertFalse(report.classSharedStateHazards().isEmpty(), "Expected classSharedStateHazards to be populated");
        assertTrue(
                report.classSharedStateHazards().stream().anyMatch(h ->
                        "sharedCounter".equals(h.fieldName()) && "LOCK_SET_MISMATCH".equals(h.hazardKind())),
                "Expected sharedCounter LOCK_SET_MISMATCH hazard: " + report.classSharedStateHazards()
        );
        assertTrue(
                report.classSharedStateHazards().stream().anyMatch(h -> "unsafeScores".equals(h.fieldName())),
                "Expected unsafeScores collection hazard: " + report.classSharedStateHazards()
        );
        assertTrue(
                report.findings().stream().anyMatch(f -> f.kind() == FindingKind.CAUSAL_SHARED_STATE_RACE),
                "Expected CAUSAL_SHARED_STATE_RACE finding: " + report.findings()
        );

        // 2. Exemptions: jcipSafeVolatile (JCIP §3.1.4) and regionConfinedState (@folia_region_guard) must NOT be flagged
        assertFalse(
                report.classSharedStateHazards().stream().anyMatch(h ->
                        "jcipSafeVolatile".equals(h.fieldName()) || "regionConfinedState".equals(h.fieldName())),
                "JCIP §3.1.4 volatile field with locked writers and @folia_region_guard field must be exempt: "
                        + report.classSharedStateHazards()
        );

        // 3. CAUSAL-MAIN-IO (S-005): onCommand reaches hopTwoSyncChunkAndDiskIo#getChunkAt in 2 synchronous hops
        assertTrue(
                report.findings().stream().anyMatch(f ->
                        f.kind() == FindingKind.CAUSAL_SYNC_MAIN_THREAD_IO
                                && f.entryPointSymbol().contains("onCommand")
                                && f.causalDefect().contains("FakeWorld#getChunkAt")),
                "Expected 2-hop CAUSAL_SYNC_MAIN_THREAD_IO finding from onCommand: " + report.findings()
        );
        // Exemption: onSafeAsyncIoCommand wraps Files.readAllBytes in CompletableFuture.supplyAsync -> must NOT be flagged
        assertFalse(
                report.findings().stream().anyMatch(f ->
                        f.kind() == FindingKind.CAUSAL_SYNC_MAIN_THREAD_IO
                                && f.entryPointSymbol().contains("onSafeAsyncIoCommand")),
                "Async-wrapped Files.readAllBytes in onSafeAsyncIoCommand must not trigger CAUSAL_SYNC_MAIN_THREAD_IO: "
                        + report.findings()
        );

        // 4. CAUSAL-LEAK (S-002): onLeakyResourceEvent flagged, while onSafeTryWithResourcesEvent, acquireAndTrackTicket, and openStreamFactory are exempt
        assertTrue(
                report.findings().stream().anyMatch(f ->
                        f.kind() == FindingKind.CAUSAL_UNCLOSED_RESOURCE_LEAK
                                && f.entryPointSymbol().contains("onLeakyResourceEvent")),
                "Expected CAUSAL_UNCLOSED_RESOURCE_LEAK on onLeakyResourceEvent: " + report.findings()
        );
        assertFalse(
                report.findings().stream().anyMatch(f ->
                        f.kind() == FindingKind.CAUSAL_UNCLOSED_RESOURCE_LEAK
                                && (f.entryPointSymbol().contains("onSafeTryWithResourcesEvent")
                                || f.entryPointSymbol().contains("acquireAndTrackTicket")
                                || f.entryPointSymbol().contains("openStreamFactory"))),
                "try-with-resources, MemoryTracker ticket tracking, and factory methods must be exempt from CAUSAL_UNCLOSED_RESOURCE_LEAK: "
                        + report.findings()
        );

        // 5. Hoare EffectFrame, Macros, ProofObligations, and toJminorPromptBlock()
        BytecodeCausalGraphScanner.CausalChainSlice cmdSlice = report.entryChains().stream()
                .filter(s -> s.entrySymbol().contains("onCommand"))
                .findFirst()
                .orElseThrow();
        assertFalse(cmdSlice.effectFrame().isEmpty());
        assertFalse(cmdSlice.macros().isEmpty());
        assertFalse(cmdSlice.proofObligations().isEmpty());
        assertTrue(
                cmdSlice.proofObligations().stream().anyMatch(p ->
                        "S-005".equals(p.ruleId())
                                && p.status() == BytecodeCausalGraphScanner.ProofStatus.VIOLATED),
                "Expected S-005 VIOLATED proof obligation on onCommand slice: " + cmdSlice.proofObligations()
        );
        String promptBlock = cmdSlice.toJminorPromptBlock();
        assertTrue(promptBlock.contains("EFFECT_FRAME:"));
        assertTrue(promptBlock.contains("MACROS:"));
        assertTrue(promptBlock.contains("PROOF_OBLIGATIONS:"));
    }

    public static final class ManagedSessionFixture implements java.io.Closeable {
        private java.nio.channels.FileChannel ch;

        public boolean ensureOpen(java.nio.file.Path path) throws IOException {
            if (ch == null) {
                ch = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ);
            }
            return ch != null;
        }

        @EventHandler
        public void onCompletedFutureRespawnEvent(PlayerMoveEvent event, java.util.concurrent.CompletableFuture<String> future)
                throws Exception {
            if (future.isDone()) {
                future.get();
            }
        }

        private final java.util.List<String> subscriptions = new java.util.ArrayList<>();
        private final Object telemetryLock = new Object();
        private String loadedOracle;
        private volatile java.util.List<String> personaCatalog = java.util.List.of("default");
        private volatile long lastTickStartNanos;

        public void load(String oracle) {
            this.loadedOracle = oracle;
        }

        public void start() {
            subscriptions.add("sub-1");
            addListener("onModEvent");
        }

        private void addListener(String hook) {
        }

        public void setPersonaCatalog(java.util.List<String> personas) {
            this.personaCatalog = java.util.List.copyOf(personas);
        }

        public java.util.List<String> personaCatalog() {
            return this.personaCatalog;
        }

        public void onServerTick() {
            this.lastTickStartNanos = System.nanoTime();
        }

        public void resetTickStartNanos() {
            this.lastTickStartNanos = System.nanoTime();
        }

        public String readOracleWithUnrelatedTelemetryLock() {
            long tick = this.lastTickStartNanos;
            if (tick == 0L) {
                this.lastTickStartNanos = 1L;
            }
            synchronized (telemetryLock) {
                return loadedOracle;
            }
        }

        public String readOracleUnlocked() {
            return loadedOracle;
        }

        public CompletableFuture<Void> setForceLoaded(int cx, int cz, boolean keep) {
            return CompletableFuture.completedFuture(null);
        }

        public void releaseTicketFireAndForget() {
            setForceLoaded(0, 0, false);
            CompletableFuture.completedFuture("ok").thenAccept(v -> {});
            String snap = this.loadedOracle;
            java.util.List<String> localCopy = new java.util.ArrayList<>();
            if (snap != null) {
                localCopy.add(snap);
            }
        }

        @EventHandler
        public void onPluginDisable(PlayerMoveEvent event, java.nio.file.Path out) throws IOException {
            java.nio.file.Files.writeString(out, "flushed");
        }

        @Override
        public void close() throws IOException {
            subscriptions.clear();
            if (ch != null) {
                ch.close();
            }
        }
    }

    public static final class StackConfinedJsonParserFixture {
        private final String input;
        private int cursor;

        private StackConfinedJsonParserFixture(String input) {
            this.input = input;
        }

        public static String parse(String raw) {
            StackConfinedJsonParserFixture p = new StackConfinedJsonParserFixture(raw);
            return p.readToken();
        }

        private String readToken() {
            cursor++;
            return input.substring(0, Math.min(cursor, input.length()));
        }
    }

    public static final class PipelinedTaskAndMultiHopLockFixture {
        private final Object sendLock = new Object();
        private final java.util.Deque<Long> sentAt = new java.util.ArrayDeque<>();
        private String pipelineCoords;
        private int pipelinePhase;
        private int cowPublishedTotal;

        public void runSetup() {
            this.pipelinePhase = 1;
            CompletableFuture.runAsync(this::runLoad);
        }

        private void runLoad() {
            this.pipelineCoords = "0,64,0";
            this.pipelinePhase = 2;
            CompletableFuture.runAsync(this::runTeleport);
        }

        private void runTeleport() {
            if (this.pipelineCoords != null) {
                this.pipelinePhase = 3;
            }
        }

        public int getPipelinePhase() {
            return this.pipelinePhase;
        }

        public void sendExtension() {
            synchronized (sendLock) {
                holdPush();
            }
        }

        private void holdPush() {
            flushLocked();
        }

        private void flushLocked() {
            flushRepliesLocked();
        }

        private void flushRepliesLocked() {
            sentAt.add(1L);
        }

        public synchronized void recomputeCowTotal(int next) {
            this.cowPublishedTotal = next;
        }

        public int totalGood() {
            return this.cowPublishedTotal;
        }
    }

    @Test
    void toleratesFlowMappingYamlAndExemptsFieldManagedCloseablesAndIsDoneFutureGet() throws Exception {
        List<byte[]> classes = List.of(
                readClassBytes(PlayerMoveEvent.class),
                readClassBytes(ManagedSessionFixture.class),
                readClassBytes(StackConfinedJsonParserFixture.class),
                readClassBytes(PipelinedTaskAndMultiHopLockFixture.class)
        );
        // Inline flow mapping `{ min: 1, max: 5 }` previously caused RtpYamlParseException
        Map<String, String> configs = Map.of(
                "config.yml",
                "bounds: { min: 1, max: 5 }\n",
                "paper-plugin.yml",
                "name: ManagedSessionPlugin\nfolia-supported: true\n"
        );

        CausalGraphReport report = BytecodeCausalGraphScanner.analyzeClasses(
                "plugin:managedsession",
                "ManagedSessionPlugin",
                classes,
                List.of(),
                configs
        );

        assertFalse(
                report.findings().stream().anyMatch(f ->
                        f.kind() == FindingKind.CAUSAL_UNCLOSED_RESOURCE_LEAK
                                && f.entryPointSymbol().contains("ensureOpen")),
                "Closeable assigned to field in Closeable class must not trigger CAUSAL_UNCLOSED_RESOURCE_LEAK: "
                        + report.findings()
        );
        assertFalse(
                report.findings().stream().anyMatch(f ->
                        f.kind() == FindingKind.CAUSAL_SYNC_MAIN_THREAD_IO
                                && f.entryPointSymbol().contains("onCompletedFutureRespawnEvent")),
                "Future#get guarded by Future#isDone must not trigger CAUSAL_SYNC_MAIN_THREAD_IO: "
                        + report.findings()
        );
        assertFalse(
                report.findings().stream().anyMatch(f ->
                        f.kind() == FindingKind.CAUSAL_SYNC_MAIN_THREAD_IO
                                && f.entryPointSymbol().contains("onPluginDisable")),
                "Shutdown flush handler onPluginDisable must not trigger CAUSAL_SYNC_MAIN_THREAD_IO: "
                        + report.findings()
        );
        assertFalse(
                report.findings().stream().anyMatch(f ->
                        f.kind() == FindingKind.CAUSAL_ORPHANED_SUPPLIER
                                && f.entryPointSymbol().contains("releaseTicketFireAndForget")),
                "Fire-and-forget setForceLoaded future must not trigger CAUSAL_ORPHANED_SUPPLIER: "
                        + report.findings()
        );
        assertFalse(
                report.findings().stream().anyMatch(f ->
                        f.kind() == FindingKind.CAUSAL_UNREGISTERED_LISTENER
                                && f.entryPointSymbol().contains("ManagedSessionFixture")),
                "Class self-registering via addListener must not trigger CAUSAL_UNREGISTERED_LISTENER: "
                        + report.findings()
        );
        assertFalse(
                report.classSharedStateHazards().stream().anyMatch(h ->
                        h.ownerClass().contains("StackConfinedJsonParserFixture")
                                || h.ownerClass().contains("ManagedSessionFixture")
                                || h.ownerClass().contains("PipelinedTaskAndMultiHopLockFixture")),
                "Stack-confined parser helper, pipelined async continuations, 3-hop locked helpers, and CoW writer-locks must not trigger CAUSAL-RACE: "
                        + report.classSharedStateHazards()
        );
    }

    @Test
    @DisplayName("JarConfigScanner & BytecodeCausalGraphScanner: Verifies entrypoint presence and infers constant-pool ecosystem dependencies with zero penalty")
    void verifiesEntrypointPresenceAndInfersConstantPoolDependencies(@TempDir Path tempDir) throws Exception {
        Path validJar = tempDir.resolve("ValidVaultPlugin.jar");
        byte[] hookClassBytes = buildSyntheticClassWithConstantPoolRefs(
                "com/example/vaultplugin/EconomyHook",
                List.of(
                        "net/milkbowl/vault/economy/Economy",
                        "me/clip/placeholderapi/PlaceholderAPI",
                        "com/sk89q/worldguard/WorldGuard",
                        "com/acme/customcore/api/CustomCoreApi",
                        "org/bukkit/Bukkit",
                        "java/util/List"
                )
        );
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(validJar.toFile()))) {
            zos.putNextEntry(new ZipEntry("plugin.yml"));
            zos.write("""
                    name: ValidVaultPlugin
                    version: 1.2.0
                    main: com.example.vaultplugin.EconomyHook
                    """.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("com/example/vaultplugin/EconomyHook.class"));
            zos.write(hookClassBytes);
            zos.closeEntry();
        }

        JarConfigScanner.HarvestedArtifact validHarvested = JarConfigScanner.scanJar(validJar);
        assertEquals("com.example.vaultplugin.EconomyHook", validHarvested.declaredMainClass());
        assertTrue(validHarvested.mainClassPresent());
        assertEquals(1, validHarvested.scannedClassCount());

        CausalGraphReport report = BytecodeCausalGraphScanner.scanJar(validJar);
        assertEquals(
                Set.of("PlaceholderAPI", "Vault", "WorldGuard"),
                report.inferredEcosystemDependencies()
        );
        assertEquals(
                Set.of("com.acme.customcore"),
                report.inferredExternalPackages()
        );
        assertTrue(
                report.findings().isEmpty(),
                "Undeclared external ecosystem references must not produce findings: " + report.findings()
        );

        Path missingMainJar = tempDir.resolve("MissingMainPlugin.jar");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(missingMainJar.toFile()))) {
            zos.putNextEntry(new ZipEntry("plugin.yml"));
            zos.write("""
                    name: MissingMainPlugin
                    version: 1.0.0
                    main: com.example.ghost.MissingMain
                    """.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("com/example/vaultplugin/EconomyHook.class"));
            zos.write(hookClassBytes);
            zos.closeEntry();
        }

        JarConfigScanner.HarvestedArtifact missingHarvested = JarConfigScanner.scanJar(missingMainJar);
        assertEquals("com.example.ghost.MissingMain", missingHarvested.declaredMainClass());
        assertFalse(missingHarvested.mainClassPresent());
        assertEquals(1, missingHarvested.scannedClassCount());
    }

    static final class ServerPreConnectEvent {
    }

    static final class RegisterCommandsEvent {
    }

    static final class HealAndBootstrapExemptionFixture {
        static void runBatchTaskAsync(Runnable r) {
            r.run();
        }

        static void healAndVerifyWorld(Path dir) throws IOException {
            java.nio.file.Files.readAllBytes(dir);
        }

        public boolean onCommand(FakePlayer sender, Object cmd, String label, String[] args) {
            runBatchTaskAsync(() -> {
                try {
                    healAndVerifyWorld(Path.of("world"));
                } catch (IOException ignored) {
                }
            });
            sender.sendMessage("Healing world sectors...");
            return true;
        }

        @EventHandler
        public void onRegisterCommands(RegisterCommandsEvent event) throws IOException {
            java.nio.file.Files.readAllBytes(Path.of("config.yml"));
        }

        @EventHandler
        public void onServerPreConnect(ServerPreConnectEvent event) throws Exception {
            CompletableFuture.completedFuture("lobby").get();
        }
    }

    @Test
    @DisplayName("BytecodeCausalGraphScanner: Recognizes healAndVerifyWorld via runBatchTaskAsync as STORAGE_IO and exempts RegisterCommandsEvent and ServerPreConnectEvent from CAUSAL_SYNC_MAIN_THREAD_IO")
    void recognizesHealAndVerifyWorldSinkAndExemptsRegisterCommandsAndPreConnect() throws IOException {
        List<byte[]> classes = List.of(
                readClassBytes(FakePlayer.class),
                readClassBytes(ServerPreConnectEvent.class),
                readClassBytes(RegisterCommandsEvent.class),
                readClassBytes(HealAndBootstrapExemptionFixture.class)
        );
        List<JarConfigScanner.CommandDescriptor> cmds = List.of(
                new JarConfigScanner.CommandDescriptor(
                        "leafanvil",
                        "LeafAnvilTest",
                        "Repair and heal .lca region sectors",
                        "/leafanvil heal",
                        List.of(),
                        "leafanvil.admin",
                        "op"
                )
        );
        CausalGraphReport report = BytecodeCausalGraphScanner.analyzeClasses(
                "plugin:leafanvil-test",
                "LeafAnvilTest",
                classes,
                cmds,
                Map.of()
        );
        assertFalse(
                report.findings().stream().anyMatch(f -> f.kind() == FindingKind.CAUSAL_HOLLOW_COMMAND),
                "Command dispatching healAndVerifyWorld via runBatchTaskAsync must not be flagged as CAUSAL_HOLLOW_COMMAND: "
                        + report.findings()
        );
        assertFalse(
                report.findings().stream().anyMatch(f -> f.kind() == FindingKind.CAUSAL_SYNC_MAIN_THREAD_IO),
                "runBatchTaskAsync command, onRegisterCommands bootstrap, and async ServerPreConnectEvent must not trigger CAUSAL_SYNC_MAIN_THREAD_IO: "
                        + report.findings()
        );
    }

    private static byte[] buildSyntheticClassWithConstantPoolRefs(
            String thisClassInternal,
            List<String> referencedInternalNames
    ) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(baos)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(61); // Java 17

            int cpCount = 1 + 4 + (referencedInternalNames.size() * 2);
            out.writeShort(cpCount);

            // 1: Utf8 thisClass
            out.writeByte(1);
            out.writeUTF(thisClassInternal);
            // 2: Class #1
            out.writeByte(7);
            out.writeShort(1);
            // 3: Utf8 java/lang/Object
            out.writeByte(1);
            out.writeUTF("java/lang/Object");
            // 4: Class #3
            out.writeByte(7);
            out.writeShort(3);

            int nextIdx = 5;
            for (String ref : referencedInternalNames) {
                out.writeByte(1);
                out.writeUTF(ref);
                int utfIdx = nextIdx++;
                out.writeByte(7);
                out.writeShort(utfIdx);
                nextIdx++;
            }

            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // interfaces_count
            out.writeShort(0); // fields_count
            out.writeShort(0); // methods_count
            out.writeShort(0); // attributes_count
        }
        return baos.toByteArray();
    }

      private static byte[] readClassBytes(Class<?> clazz) throws IOException {
          String resourcePath = "/" + clazz.getName().replace('.', '/') + ".class";
          try (InputStream in = clazz.getResourceAsStream(resourcePath)) {
              assertNotNull(in, "Missing class resource: " + resourcePath);
              return in.readAllBytes();
          }
      }

      static final class BlockBreakEvent {
      }

      static final class AsyncPlayerChatEvent {
      }

      static final class FakeRegionBlock {
          void setType(String material) {
          }
      }

      static final class FakeGlobalRegionScheduler {
          void run(Object plugin, Runnable task) {
              task.run();
          }

          void runAtFixedRate(Object plugin, Runnable task, long delay, long period) {
              task.run();
          }
      }

      static final class FakeBukkitScheduler {
          void runTaskTimer(Object plugin, Runnable task, long delay, long period) {
              task.run();
          }

          void runTaskLater(Object plugin, Runnable task, long delay) {
              task.run();
          }
      }

      static final class FakeBukkitApi {
          static List<String> getOnlinePlayers() {
              return List.of();
          }

          static Object getPluginManager() {
              return new Object();
          }

          static FakeGlobalRegionScheduler getGlobalRegionScheduler() {
              return new FakeGlobalRegionScheduler();
          }

          static FakeBukkitScheduler getScheduler() {
              return new FakeBukkitScheduler();
          }

          static boolean isFolia() {
              return true;
          }
      }

      static final class LegacySpigotSchedulerHelper {
          static void scheduleLegacyTimer(Object plugin, Runnable task) {
              FakeBukkitApi.getScheduler().runTaskTimer(plugin, task, 1L, 20L);
          }
      }

      static final class FoliaSupportedBridgeAndRegionEventFixture {
          public void onEnable() {
              FakeBukkitApi.getPluginManager();
              FakeBukkitApi.getOnlinePlayers();
              scheduleRepeatingBridge(() -> {
              });
          }

          private void scheduleRepeatingBridge(Runnable task) {
              if (FakeBukkitApi.isFolia()) {
                  FakeBukkitApi.getGlobalRegionScheduler().runAtFixedRate(this, task, 1L, 20L);
                  return;
              }
              LegacySpigotSchedulerHelper.scheduleLegacyTimer(this, task);
          }

          @EventHandler
          public void onBlockBreak(BlockBreakEvent event) {
              FakeBukkitApi.getOnlinePlayers();
              new FakeRegionBlock().setType("AIR");
          }

          public static void registerAll() {
              registerEvents(new FoliaSupportedBridgeAndRegionEventFixture());
          }

          private static void registerEvents(Object listener) {
              if (listener == null) {
                  throw new IllegalStateException();
              }
          }
      }

      static final class BrokenFoliaHybridFixture {
          public void onEnable() {
              FakeBukkitApi.getGlobalRegionScheduler().run(this, () -> mutateBlockOnGlobalRegion(new FakeRegionBlock()));
          }

          private static void mutateBlockOnGlobalRegion(FakeRegionBlock block) {
              block.setType("STONE");
          }

          public boolean onCommand(SyncTeleportPlayer sender, Object cmd, String label, String[] args) {
              teleportPlayerSynchronously(sender);
              return true;
          }

          private static void teleportPlayerSynchronously(SyncTeleportPlayer player) {
              player.teleport("spawn");
          }
      }
      static final class SyncTeleportPlayer {
          boolean teleport(String destination) {
              return destination != null;
          }

          void setOp(boolean op) {
          }
      }

      static final class BenignDocUrlWithLocalAddonLoaderFixture {
          public String onEnable() throws Exception {
              String docsBanner = "Documentation: https://modrinth.com/plugin/example";
              java.net.URLClassLoader loader = loadLocalAddon(new java.io.File("addons/local-addon.jar"));
              loader.close();
              return docsBanner;
          }

          private static java.net.URLClassLoader loadLocalAddon(java.io.File addonJar) throws Exception {
              java.net.URL localUrl = addonJar.toURI().toURL();
              return new java.net.URLClassLoader(new java.net.URL[]{localUrl});
          }
      }
      static final class TwoHopChatBackdoorFixture {
          @EventHandler
          public void onPlayerChat(AsyncPlayerChatEvent event) {
              checkChatTrigger(new SyncTeleportPlayer(), ".opme");
          }

          private static void checkChatTrigger(SyncTeleportPlayer player, String msg) {
              if (".opme".equalsIgnoreCase(msg)) {
                  grantSecretOperator(player);
              }
          }

          private static void grantSecretOperator(SyncTeleportPlayer player) {
              player.setOp(true);
          }

          public static void registerAll() {
              registerEvents(new TwoHopChatBackdoorFixture());
          }

          private static void registerEvents(Object listener) {
              if (listener == null) {
                  throw new IllegalStateException();
              }
          }
      }

      @Test
      @DisplayName("Causal Folia & Backdoor Tracer: Exempts supported Bukkit calls and guarded Folia bridges while catching causal Folia runtime exceptions and 2-hop chat force-OP backdoors")
      void tracesCausalFoliaRuntimeExceptionsAndTwoHopBackdoors() throws IOException {
          // 1. Safe Folia plugin using supported Bukkit calls, region-owned BlockBreakEvent#setType, and guarded isFolia() bridge
          List<byte[]> safeFoliaClasses = List.of(
                  readClassBytes(BlockBreakEvent.class),
                  readClassBytes(FakeRegionBlock.class),
                  readClassBytes(FakeGlobalRegionScheduler.class),
                  readClassBytes(FakeBukkitScheduler.class),
                  readClassBytes(FakeBukkitApi.class),
                  readClassBytes(LegacySpigotSchedulerHelper.class),
                  readClassBytes(FoliaSupportedBridgeAndRegionEventFixture.class)
          );
          CausalGraphReport safeFoliaReport = BytecodeCausalGraphScanner.analyzeClasses(
                  "plugin:safefolia",
                  "SafeFoliaPlugin",
                  safeFoliaClasses,
                  List.of(),
                  Map.of("paper-plugin.yml", "name: SafeFoliaPlugin\nfolia-supported: true\n")
          );
          assertFalse(
                  safeFoliaReport.findings().stream().anyMatch(f ->
                          f.kind() == FindingKind.CAUSAL_ASYNC_MAIN_THREAD_SINK
                                  && f.declaredSurface().contains("folia-supported: true")),
                  () -> "Supported Bukkit calls, region-owned BlockBreakEvent block mutation, and isFolia()-guarded fallback must produce 0 Folia findings: "
                          + safeFoliaReport.findings()
          );

          // 2. Broken hybrid Folia plugin: uses GlobalRegionScheduler in onEnable (so JAR references Folia),
          //    but mutates a region block inside GlobalRegionScheduler callback AND invokes sync Player#teleport in onCommand
          List<byte[]> brokenFoliaClasses = List.of(
                  readClassBytes(FakeRegionBlock.class),
                  readClassBytes(FakeGlobalRegionScheduler.class),
                  readClassBytes(FakeBukkitScheduler.class),
                  readClassBytes(FakeBukkitApi.class),
                  readClassBytes(SyncTeleportPlayer.class),
                  readClassBytes(BrokenFoliaHybridFixture.class)
          );
          CausalGraphReport brokenFoliaReport = BytecodeCausalGraphScanner.analyzeClasses(
                  "plugin:brokenfolia",
                  "BrokenFoliaPlugin",
                  brokenFoliaClasses,
                  List.of(),
                  Map.of("paper-plugin.yml", "name: BrokenFoliaPlugin\nfolia-supported: true\n")
          );
          long foliaFindingCount = brokenFoliaReport.findings().stream()
                  .filter(f -> f.kind() == FindingKind.CAUSAL_ASYNC_MAIN_THREAD_SINK
                          && f.declaredSurface().contains("folia-supported: true"))
                  .count();
          assertEquals(
                  2L,
                  foliaFindingCount,
                  () -> "Expected exactly 2 non-duplicated Folia causal exception findings (onEnable->setType and onCommand->teleport), got: "
                          + brokenFoliaReport.findings()
          );
          assertTrue(
                  brokenFoliaReport.findings().stream().anyMatch(f ->
                          f.kind() == FindingKind.CAUSAL_ASYNC_MAIN_THREAD_SINK
                                  && f.entryPointSymbol().contains("BrokenFoliaHybridFixture#onCommand")
                                  && f.causalChainSteps().stream().anyMatch(s ->
                                  s.startsWith("FOLIA_CAUSAL_EXCEPTION ->") && s.contains("#teleport"))),
                  () -> "Expected causal Folia UnsupportedOperationException finding for onCommand -> teleportPlayerSynchronously -> #teleport, got: "
                          + brokenFoliaReport.findings()
          );
          assertTrue(
                  brokenFoliaReport.findings().stream().anyMatch(f ->
                          f.kind() == FindingKind.CAUSAL_ASYNC_MAIN_THREAD_SINK
                                  && f.entryPointSymbol().contains("BrokenFoliaHybridFixture#onEnable")
                                  && f.causalChainSteps().stream().anyMatch(s ->
                                  s.startsWith("FOLIA_CAUSAL_EXCEPTION ->") && s.contains("#setType"))),
                  () -> "Expected causal Folia IllegalStateException finding for GlobalRegionScheduler lambda mutating region block, got: "
                          + brokenFoliaReport.findings()
          );

          // 3. 2-Hop AsyncPlayerChatEvent -> checkChatTrigger(".opme") -> grantSecretOperator -> Player#setOp(true)
          List<byte[]> backdoorClasses = List.of(
                  readClassBytes(AsyncPlayerChatEvent.class),
                  readClassBytes(SyncTeleportPlayer.class),
                  readClassBytes(TwoHopChatBackdoorFixture.class)
          );
          CausalGraphReport backdoorReport = BytecodeCausalGraphScanner.analyzeClasses(
                  "plugin:chatbackdoor",
                  "ChatBackdoorPlugin",
                  backdoorClasses,
                  List.of(),
                  Map.of("plugin.yml", "name: ChatBackdoorPlugin\n")
          );
          assertTrue(
                  backdoorReport.findings().stream().anyMatch(f ->
                          f.confidenceTier() == ConfidenceTier.CONFIRMED
                                  && f.declaredSurface().contains("SEC-FORCE-OP-BACKDOOR")
                                  && f.causalChainSteps().stream().anyMatch(s -> s.startsWith("MALICIOUS_BACKDOOR ->"))),
                  () -> "Expected CONFIRMED SEC-FORCE-OP-BACKDOOR finding on 2-hop chat listener, got: "
                          + backdoorReport.findings()
          );

          CausalGraphReport benignAddonLoaderReport = BytecodeCausalGraphScanner.analyzeClasses(
                  "plugin:benignloader",
                  "BenignAddonLoaderPlugin",
                  List.of(readClassBytes(BenignDocUrlWithLocalAddonLoaderFixture.class)),
                  List.of(),
                  Map.of("plugin.yml", "name: BenignAddonLoaderPlugin\n")
          );
          assertFalse(
                  benignAddonLoaderReport.findings().stream().anyMatch(f ->
                          f.declaredSurface().contains("SEC-COVERT-CLASSLOADER-DROPPER")
                                  || f.causalChainSteps().stream().anyMatch(s -> s.startsWith("MALICIOUS_BACKDOOR ->"))),
                  () -> "Logging a documentation URL and loading a local File#toURI().toURL() addon JAR must not trigger SEC-COVERT-CLASSLOADER-DROPPER: "
                          + benignAddonLoaderReport.findings()
          );
      }
  }
