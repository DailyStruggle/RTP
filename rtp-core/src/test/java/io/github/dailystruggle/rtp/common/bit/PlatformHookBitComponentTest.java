package io.github.dailystruggle.rtp.common.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link PlatformHookBitComponent} ({@code platform-hooks}) covering all 12
 * platform fallback sensors ({@code TEST 4021..4032}), first-occurrence warning logging,
 * {@link BitSeverityPolicy} filtering, {@link PlatformHookBitComponent#reset()}, and
 * {@link RtpBitCodeLock} release-lock parity (ADR-026, ADR-113, ADR-121, REQ-RTP-S-004).
 */
@DisplayName("REQ-API-F-009 & REQ-RTP-S-004 — PlatformHookBitComponent sensors (TEST 4021..4032)")
class PlatformHookBitComponentTest {

    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        BitSeverityPolicy.resetDefaults();
        PlatformHookBitComponent.reset();
        accessor = RTPTestSetup.install(tempDir);
        accessor.logMessages.clear();
    }

    @AfterEach
    void tearDown() {
        PlatformHookBitComponent.reset();
        BitSeverityPolicy.resetDefaults();
    }

    @Test
    @DisplayName("Records and emits all 12 platform-hooks PBIT findings (TEST 4021..4032)")
    void recordsAndEmitsAllTwelvePlatformHookSensors() {
        assertEquals(BitHealth.HEALTHY, PlatformHookBitComponent.report().health());
        assertTrue(PlatformHookBitComponent.report().findings().isEmpty());

        PlatformHookBitComponent.recordAnvilInputDegraded("PaperAnvilOpener", "LinkageError");
        PlatformHookBitComponent.recordMenuRendererDegraded("book", "NoSuchMethodError");
        PlatformHookBitComponent.recordWorldBorderDegraded("world", "null border");
        PlatformHookBitComponent.recordPaletteReconcilerDegraded("Tag.REGISTRY_BLOCKS", "empty");
        PlatformHookBitComponent.recordCustomItemDegraded("ItemsAdder", "ClassNotFoundException: CustomStack");
        PlatformHookBitComponent.recordBiomeColorDegraded("Biome#getMapColor", "NoSuchMethodException: getMapColor");
        PlatformHookBitComponent.recordCommandRegistrarDegraded("Server#syncCommands", "NoSuchMethodException: syncCommands");
        PlatformHookBitComponent.recordMessageFormatDegraded("MiniMessage", "IllegalArgumentException: unclosed tag");
        PlatformHookBitComponent.recordPlayerHookDegraded("Player#getViewDistance", "NoSuchMethodException: getViewDistance");
        PlatformHookBitComponent.recordWorldHookDegraded("World#getEnvironment", "IllegalStateException: unloaded");
        PlatformHookBitComponent.recordSamplerDegraded("BukkitBackendStateSampler#mspt", "IllegalStateException: unsampled");
        PlatformHookBitComponent.recordAddonHookDegraded("ClaimLocationResolver#toLocation", "NullPointerException: server null");

        BitReport report = PlatformHookBitComponent.report();
        assertEquals(BitHealth.DEGRADED, report.health());
        assertEquals(12, report.findings().size());

        Set<String> codes = Set.copyOf(report.findings().stream().map(BitFinding::code).toList());
        assertEquals(
                Set.of(
                        PlatformHookBitComponent.ANVIL_INPUT_DEGRADED.key(),
                        PlatformHookBitComponent.MENU_RENDERER_DEGRADED.key(),
                        PlatformHookBitComponent.WORLD_BORDER_DEGRADED.key(),
                        PlatformHookBitComponent.PALETTE_RECONCILER_DEGRADED.key(),
                        PlatformHookBitComponent.CUSTOM_ITEM_DEGRADED.key(),
                        PlatformHookBitComponent.BIOME_COLOR_DEGRADED.key(),
                        PlatformHookBitComponent.COMMAND_REGISTRAR_DEGRADED.key(),
                        PlatformHookBitComponent.MESSAGE_FORMAT_DEGRADED.key(),
                        PlatformHookBitComponent.PLAYER_HOOK_DEGRADED.key(),
                        PlatformHookBitComponent.WORLD_HOOK_DEGRADED.key(),
                        PlatformHookBitComponent.SAMPLER_DEGRADED.key(),
                        PlatformHookBitComponent.ADDON_HOOK_DEGRADED.key()),
                codes);

        PlatformHookBitComponent.reset();
        assertEquals(BitHealth.HEALTHY, PlatformHookBitComponent.report().health());
        assertTrue(PlatformHookBitComponent.report().findings().isEmpty());
    }

    @Test
    @DisplayName("Throwable overloads format detail and log WARNING only on the first occurrence per reset")
    void throwableOverloadsFormatDetailAndLogWarningOnFirstTripOnly() {
        accessor.logMessages.clear();

        IllegalStateException firstErr = new IllegalStateException("first failure");
        IllegalArgumentException secondErr = new IllegalArgumentException("second failure");

        PlatformHookBitComponent.recordCustomItemDegraded("Oraxen", firstErr);
        PlatformHookBitComponent.recordCustomItemDegraded("Oraxen", secondErr);

        PlatformHookBitComponent.recordBiomeColorDegraded("BukkitBiomeColorSource", firstErr);
        PlatformHookBitComponent.recordCommandRegistrarDegraded("BukkitCommandRegistrar", firstErr);
        PlatformHookBitComponent.recordMessageFormatDegraded("SendMessage", firstErr);
        PlatformHookBitComponent.recordPlayerHookDegraded("BukkitRTPPlayer", firstErr);
        PlatformHookBitComponent.recordWorldHookDegraded("BukkitRTPWorld", firstErr);
        PlatformHookBitComponent.recordSamplerDegraded("BukkitBackendStateSampler", firstErr);
        PlatformHookBitComponent.recordAddonHookDegraded("ClaimIntegrations", firstErr);

        long customItemWarnings = accessor.logMessages.stream()
                .filter(msg -> msg.startsWith("WARNING:") && msg.contains("TEST_PLATFORM_CUSTOM_ITEM_DEGRADED"))
                .count();
        assertEquals(1L, customItemWarnings, "Expected exactly one WARNING log on first occurrence");

        BitFinding customItemFinding = PlatformHookBitComponent.report().findings().stream()
                .filter(f -> f.code().equals(PlatformHookBitComponent.CUSTOM_ITEM_DEGRADED.key()))
                .findFirst()
                .orElseThrow();
        assertTrue(customItemFinding.message().contains("2 time(s)"), customItemFinding.message());
        assertTrue(
                customItemFinding.message().contains("Oraxen (IllegalArgumentException: second failure)"),
                customItemFinding.message());

        PlatformHookBitComponent.reset();
        PlatformHookBitComponent.recordCustomItemDegraded("Oraxen", firstErr);
        long customItemWarningsAfterReset = accessor.logMessages.stream()
                .filter(msg -> msg.startsWith("WARNING:") && msg.contains("TEST_PLATFORM_CUSTOM_ITEM_DEGRADED"))
                .count();
        assertEquals(2L, customItemWarningsAfterReset, "Expected first occurrence after reset() to log WARNING again");
    }

    @Test
    @DisplayName("BitSeverityPolicy filters suppressed codes and escalates overridden codes")
    void bitSeverityPolicyFiltersAndEscalatesFindings() {
        PlatformHookBitComponent.recordPlayerHookDegraded("FoliaRTPPlayer", "viewDistance unavailable");
        PlatformHookBitComponent.recordWorldHookDegraded("FoliaRTPWorld", "environment unavailable");

        BitSeverityPolicy.load(
                false,
                false,
                Map.of(
                        PlatformHookBitComponent.PLAYER_HOOK_DEGRADED.key(), "OFF",
                        PlatformHookBitComponent.WORLD_HOOK_DEGRADED.key(), "ERROR"));

        BitReport report = PlatformHookBitComponent.report();
        assertEquals(BitHealth.FAILED, report.health());
        assertEquals(1, report.findings().size());
        assertEquals(PlatformHookBitComponent.WORLD_HOOK_DEGRADED.key(), report.findings().get(0).code());
        assertFalse(report.findings().stream()
                .anyMatch(f -> f.code().equals(PlatformHookBitComponent.PLAYER_HOOK_DEGRADED.key())));
    }

    @Test
    @DisplayName("docs/dev/bit-codes.lock matches all registered PlatformHookBitComponent codes (4021..4032)")
    void lockFileMatchesRegisteredPlatformHookCodes() throws Exception {
        Path lockFile = repositoryRoot().resolve("docs/dev/bit-codes.lock");
        List<RtpBitCodeLock.Entry> lockEntries = RtpBitCodeLock.parse(Files.readString(lockFile));
        var registry = RtpBitCodes.initialiseOwners();

        List<RtpBitCodeLock.Entry> coreLockEntries = lockEntries.stream()
                .filter(e -> registry.byId(e.namespace(), e.id()).isPresent()
                        || (e.namespace().equals("TEST") && e.id() >= 4021 && e.id() <= 4032))
                .toList();
        List<String> violations = RtpBitCodeLock.violations(registry, coreLockEntries);
        assertTrue(violations.isEmpty(), () -> "Lock violations: " + violations);

        for (int id = 4021; id <= 4032; id++) {
            final int codeId = id;
            var registered = registry.byId("TEST", codeId).orElse(null);
            assertNotNull(registered, () -> "Missing registered code TEST:" + codeId);
            var locked = lockEntries.stream()
                    .filter(e -> e.namespace().equals("TEST") && e.id() == codeId)
                    .findFirst()
                    .orElse(null);
            assertNotNull(locked, () -> "Missing lock entry TEST:" + codeId);
            assertEquals(registered.key(), locked.key());
            assertEquals(registered.defaultSeverity(), locked.severity());
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("could not locate repository root");
        }
        return current;
    }
}
