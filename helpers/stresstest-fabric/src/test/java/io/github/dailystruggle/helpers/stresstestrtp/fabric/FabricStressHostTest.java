package io.github.dailystruggle.helpers.stresstestrtp.fabric;

import com.mojang.brigadier.CommandDispatcher;
import io.github.dailystruggle.helpers.stresstestrtp.CoverageProbe;
import io.github.dailystruggle.helpers.stresstestrtp.LeakProbe;
import io.github.dailystruggle.helpers.stresstestrtp.StressHost;
import io.github.dailystruggle.server.api.PlatformFamily;
import io.github.dailystruggle.server.api.ServerAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code stresstest-fabric} carrier contracts (ADR-122 Decisions 1, 2, 3, 5).
 */
class FabricStressHostTest {

    @Test
    @DisplayName("StressTestFabricMod implements StressHost and ServerAccessor with FABRIC family")
    void implementsStressHostAndServerAccessor(@TempDir Path tmp) {
        StressTestFabricMod mod = new StressTestFabricMod(tmp);
        assertTrue(StressHost.class.isAssignableFrom(StressTestFabricMod.class));
        assertTrue(ServerAccessor.class.isAssignableFrom(StressTestFabricMod.class));
        assertEquals(PlatformFamily.FABRIC, mod.getPlatformFamily());
        assertEquals("Fabric", mod.getPlatform());
        assertFalse(mod.isFolia());
    }

    @Test
    @DisplayName("Fabric carrier reports listener, pending-task, and chunk-ticket censuses as 'not measured', never 0")
    void reportsUnsupportedCensusesAsNotMeasured(@TempDir Path tmp) {
        StressTestFabricMod mod = new StressTestFabricMod(tmp);
        assertFalse(mod.listenerCensus().measured());
        assertEquals("not measured", mod.listenerCensus().status());
        assertFalse(mod.pendingTaskCensus().measured());
        assertEquals("not measured", mod.pendingTaskCensus().status());
        assertFalse(mod.chunkTicketCensus().measured());
        assertEquals("not measured", mod.chunkTicketCensus().status());
        assertEquals("listeners=not measured pending_tasks=not measured chunk_tickets=not measured",
                LeakProbe.describeHostCensuses(mod));
    }

    @Test
    @DisplayName("discoverFabricMods extracts FabricLoader mod origins for jar ownership and coverage includes=")
    void discoversFabricModOriginsAndCoverageIncludes(@TempDir Path tmp) throws IOException {
        Path modOriginDir = tmp.resolve("leafrtp-classes");
        Path cls = modOriginDir.resolve("io/github/dailystruggle/rtp/fabric/RTPFabricMod.class");
        Files.createDirectories(cls.getParent());
        Files.writeString(cls, "stub", StandardCharsets.UTF_8);

        ModContainer fakeMod = mockModContainer("leafrtp", "3.3.0", List.of(modOriginDir));
        FabricLoader fakeLoader = (FabricLoader) Proxy.newProxyInstance(
                FabricLoader.class.getClassLoader(),
                new Class<?>[]{FabricLoader.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getAllMods" -> List.of(fakeMod);
                    case "getConfigDir" -> tmp;
                    default -> null;
                });

        List<StressHost.LoadedPlugin> loaded = StressTestFabricMod.discoverFabricMods(fakeLoader);
        assertEquals(1, loaded.size());
        assertEquals("leafrtp", loaded.get(0).name());
        assertEquals("3.3.0", loaded.get(0).version());
        assertEquals(List.of(modOriginDir), loaded.get(0).originPaths());
        assertEquals("io.github.dailystruggle.rtp.*", CoverageProbe.includesForPlugins(loaded));
    }

    @Test
    @DisplayName("registerBrigadierCommand registers /rtpstress on a Brigadier CommandDispatcher")
    void registersRtpstressOnBrigadierDispatcher(@TempDir Path tmp) {
        StressTestFabricMod mod = new StressTestFabricMod(tmp);
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        mod.registerBrigadierCommand(dispatcher);
        assertNotNull(dispatcher.getRoot().getChild("rtpstress"));
    }

    @Test
    @DisplayName("ADR-122 Decision 3: stresstest-fabric shadowJar relocates server-api into harness namespace")
    void shadowJarRelocatesServerApi() throws Exception {
        Path jar = Path.of("build/libs/stresstest-fabric-1.0-SNAPSHOT.jar");
        assertTrue(Files.isRegularFile(jar), "Expected shadow jar: " + jar);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            assertNotNull(zip.getEntry("io/github/dailystruggle/helpers/stresstestrtp/shaded/server/api/ServerAccessor.class"),
                    "server-api must be shaded and relocated into harness namespace");
            assertNull(zip.getEntry("io/github/dailystruggle/server/api/ServerAccessor.class"),
                    "unrelocated server-api must not exist in shadow jar");
            assertNotNull(zip.getEntry("fabric.mod.json"));
            assertNotNull(zip.getEntry("config.yml"));
        }
    }

    private static ModContainer mockModContainer(String id, String version, List<Path> origins) {
        Version ver = (Version) Proxy.newProxyInstance(
                Version.class.getClassLoader(),
                new Class<?>[]{Version.class},
                (p, m, a) -> "getFriendlyString".equals(m.getName()) ? version : null);
        ModMetadata meta = (ModMetadata) Proxy.newProxyInstance(
                ModMetadata.class.getClassLoader(),
                new Class<?>[]{ModMetadata.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getId" -> id;
                    case "getVersion" -> ver;
                    default -> null;
                });
        ModOrigin origin = (ModOrigin) Proxy.newProxyInstance(
                ModOrigin.class.getClassLoader(),
                new Class<?>[]{ModOrigin.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getKind" -> ModOrigin.Kind.PATH;
                    case "getPaths" -> origins;
                    default -> null;
                });
        return (ModContainer) Proxy.newProxyInstance(
                ModContainer.class.getClassLoader(),
                new Class<?>[]{ModContainer.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getMetadata" -> meta;
                    case "getOrigin" -> origin;
                    case "getRootPaths" -> origins;
                    default -> null;
                });
    }
}
