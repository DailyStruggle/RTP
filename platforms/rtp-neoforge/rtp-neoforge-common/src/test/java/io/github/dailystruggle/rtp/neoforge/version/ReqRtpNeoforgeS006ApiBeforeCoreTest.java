package io.github.dailystruggle.rtp.neoforge.version;

import io.github.dailystruggle.rtp.neoforge.server.NeoForgeServerAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * REQ-RTP-S-006 regression guard for the NeoForge adapter: version-sensitive
 * and core-backed API entry points must fail loud (throw
 * {@link IllegalStateException}) when invoked before {@code RTPNeoForgeMod} has
 * bootstrapped {@code rtp-core} / the version adapter, rather than silently
 * no-op or NPE.
 *
 * <p>Two surfaces are pinned:</p>
 * <ol>
 *   <li>{@link NeoForgeVersionAdapterRegistry#require()} — the process-wide
 *       holder of the active per-MC carrier. A lookup before
 *       {@code RTPNeoForgeMod} installs one throws.</li>
 *   <li>{@link NeoForgeServerAccessor#getLocationGenerator()} — the public
 *       {@code RTPServerAccessor} entry point used by addons; it throws when
 *       {@code rtp-core} has not been constructed.</li>
 * </ol>
 *
 * <p>NeoForge ships Mojang-mapped names at runtime, so there is no obf carrier
 * split; the registry's fail-loud contract is the NeoForge equivalent of the
 * Fabric {@code FabricVersionAdapterRegistry} guard. This test runs without a
 * live {@code MinecraftServer} or a constructed {@code RTP} instance, exactly
 * the "API called before core loads" condition S-006 governs.</p>
 */
@DisplayName("REQ-RTP-S-006 — NeoForge API-before-core fails loud")
class ReqRtpNeoforgeS006ApiBeforeCoreTest {

    @BeforeEach
    void coldBootstrap() {
        // Ensure no adapter is installed (defensive — other tests in the same
        // JVM must not leak an installed carrier into this guard).
        NeoForgeVersionAdapterRegistry.clearForTesting();
    }

    @AfterEach
    void resetBootstrap() {
        NeoForgeVersionAdapterRegistry.clearForTesting();
    }

    @Test
    @DisplayName("version registry reports not-installed before bootstrap")
    void registryNotInstalledBeforeBootstrap() {
        assertFalse(NeoForgeVersionAdapterRegistry.isInstalled(),
                "no carrier may be installed before RTPNeoForgeMod runs server-start");
    }

    @Test
    @DisplayName("version registry require() throws before a carrier is installed")
    void registryRequireThrowsBeforeBootstrap() {
        assertThrows(IllegalStateException.class,
                NeoForgeVersionAdapterRegistry::require,
                "require() must fail loud (S-006), not return null");
    }

    @Test
    @DisplayName("getLocationGenerator() throws when rtp-core is not initialized")
    void locationGeneratorThrowsBeforeCore() {
        NeoForgeServerAccessor accessor = new NeoForgeServerAccessor();
        assertThrows(IllegalStateException.class,
                accessor::getLocationGenerator,
                "getLocationGenerator() must fail loud (S-006) before rtp-core is loaded");
    }
}
