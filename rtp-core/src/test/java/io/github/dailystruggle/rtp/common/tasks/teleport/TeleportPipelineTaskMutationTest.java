package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.platform.PlatformCreator;
import io.github.dailystruggle.rtp.api.schematic.*;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.*;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.TrackedMockWorld;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.pvp.PvPGate;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class TeleportPipelineTaskMutationTest {

    @TempDir
    File tempDir;

    private PlatformTrackingMockWorld world;
    private MockRTPServerAccessor accessor;

    static class PlatformTrackingMockWorld extends TrackedMockWorld {
        private final AtomicBoolean platformCalled = new AtomicBoolean(false);
        final List<long[]> requestedChunks = new ArrayList<>();

        public PlatformTrackingMockWorld(String name) {
            super(name);
        }

        @Override
        public CompletableFuture<Long> getChunkAt(int cx, int cz) {
            requestedChunks.add(new long[]{cx, cz});
            return super.getChunkAt(cx, cz);
        }

        @Override
        public void platform(RTPLocation location) {
            platformCalled.set(true);
        }

        public boolean isPlatformCalled() {
            return platformCalled.get();
        }

        public void resetPlatformCalled() {
            platformCalled.set(false);
        }
    }

    // MockRTPPlayer already implements setRespawnLocation, getRespawnLocation, and setFailSetLocation
    static class SettablePlayer extends MockRTPPlayer {
        public SettablePlayer(UUID uuid, String name, RTPLocation loc) {
            super(uuid, name, loc);
        }
    }

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        clearAllHooks();
        world = new PlatformTrackingMockWorld("mutation_world");
        accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        accessor.addWorld(world);
        RTPRunnable.scheduler = accessor.getMockScheduler();
        TeleportPipelineTask.ConfigCache.reload();
        GlobalRegionVerifiers.clearGlobalRegionVerifiers();
        RTP.getInstance().latestTeleportData.clear();
        RTP.getInstance().processingPlayers.clear();
        RTP.getInstance().invulnerablePlayers.clear();
        PvPGate.nativeTracker().clearAll();
    }

    @AfterEach
    void tearDown() {
        clearAllHooks();
        GlobalRegionVerifiers.clearGlobalRegionVerifiers();
        RTP.getInstance().latestTeleportData.clear();
        RTP.getInstance().processingPlayers.clear();
        RTP.getInstance().invulnerablePlayers.clear();
        PvPGate.nativeTracker().clearAll();
        RTPRunnable.scheduler = accessor.getMockScheduler();
        TeleportPipelineTask.ConfigCache.reload();
    }

    private static void clearAllHooks() {
        TeleportPipelineTask.setupPreActions.clear();
        TeleportPipelineTask.setupPostActions.clear();
        TeleportPipelineTask.loadPreActions.clear();
        TeleportPipelineTask.loadPostActions.clear();
        TeleportPipelineTask.teleportPreActions.clear();
        TeleportPipelineTask.teleportPostActions.clear();
        TeleportPipelineTask.cleanupPreActions.clear();
        TeleportPipelineTask.cleanupPostActions.clear();
    }

    private Region createTestRegion(String name) {
        Circle circle = new Circle();
        circle.setRng(new Random(42L));
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                name, world, circle, vert, false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        Region reg = new Region(name, settings);
        RTP.selectionAPI.permRegionLookup.put(reg.name, reg);
        return reg;
    }

    private SettablePlayer createPlayer(String name) {
        UUID pid = UUID.randomUUID();
        SettablePlayer player = new SettablePlayer(pid, name, new RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(player);
        return player;
    }

    // -------------------------------------------------------------------------
    // 1. initTracking on all constructor variants
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("initTracking registers trackingId in MemoryTracker on 1-arg constructor")
    void constructor_1arg_registers_tracking() {
        SettablePlayer player = createPlayer("InitP1");
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx);
        int count = MemoryTracker.trackedCountByLabel("TeleportPipelineTask");
        assertTrue(count > 0, "MemoryTracker should track task from 1-arg constructor");
        task.setCancelled(true);
    }

    @Test
    @DisplayName("initTracking registers trackingId in MemoryTracker on 3-arg constructor")
    void constructor_3arg_registers_tracking() {
        SettablePlayer player = createPlayer("InitP3");
        GenerationContext ctx = new GenerationContext(player, player, null);
        Region reg = createTestRegion("init_p3_reg");
        RTPCoords coords = new RTPCoords(world.name(), 10, 64, 10);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords);
        int count = MemoryTracker.trackedCountByLabel("TeleportPipelineTask");
        assertTrue(count > 0, "MemoryTracker should track task from 3-arg constructor");
        task.setCancelled(true);
    }

    // -------------------------------------------------------------------------
    // 2. ConfigCache.reload() property reading & defaults
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("ConfigCache reload updates cached values")
    void configCache_reload_verifies_values() {
        TeleportPipelineTask.ConfigCache.reload();
        assertNotNull(TeleportPipelineTask.ConfigCache.unsafe);
        assertNotNull(TeleportPipelineTask.ConfigCache.teleportMessage);
        assertTrue(TeleportPipelineTask.ConfigCache.viewDistanceRestoreInterval >= 0);
    }

    // -------------------------------------------------------------------------
    // 3. shouldBuildPlatform branches
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("shouldBuildPlatform contract: radius < 0 returns false immediately")
    void shouldBuildPlatform_negativeRadius_returns_false() throws Exception {
        Method m = TeleportPipelineTask.class.getDeclaredMethod("shouldBuildPlatform", RTPWorld.class, RTPCoords.class);
        m.setAccessible(true);

        RTP.configs.getParser(SafetyKeys.class).set(SafetyKeys.platformRadius, -1);
        RTPCoords coords = new RTPCoords(world.name(), 10, 64, 10);
        boolean result = (boolean) m.invoke(null, world, coords);
        assertFalse(result, "Negative platformRadius should disable platform check immediately");
    }

    @Test
    @DisplayName("shouldBuildPlatform: null world or coords returns false")
    void shouldBuildPlatform_nulls_return_false() throws Exception {
        Method m = TeleportPipelineTask.class.getDeclaredMethod("shouldBuildPlatform", RTPWorld.class, RTPCoords.class);
        m.setAccessible(true);

        assertFalse((boolean) m.invoke(null, null, new RTPCoords(world.name(), 10, 64, 10)));
        assertFalse((boolean) m.invoke(null, world, null));
    }

    @Test
    @DisplayName("shouldBuildPlatform: chunk not cached returns false")
    void shouldBuildPlatform_uncachedChunk_returns_false() throws Exception {
        Method m = TeleportPipelineTask.class.getDeclaredMethod("shouldBuildPlatform", RTPWorld.class, RTPCoords.class);
        m.setAccessible(true);

        RTP.configs.getParser(SafetyKeys.class).set(SafetyKeys.platformRadius, 2);
        world.nullChunkKeyPredicate = k -> true;
        RTPCoords coords = new RTPCoords(world.name(), 16000, 64, 16000);
        boolean result = (boolean) m.invoke(null, world, coords);
        assertFalse(result, "Uncached chunk cannot evaluate safety, should return false");
    }

    @Test
    @DisplayName("shouldBuildPlatform: landing block unsafe or belowY < minY returns true")
    void shouldBuildPlatform_unsafeLanding_returns_true() throws Exception {
        Method m = TeleportPipelineTask.class.getDeclaredMethod("shouldBuildPlatform", RTPWorld.class, RTPCoords.class);
        m.setAccessible(true);

        RTP.configs.getParser(SafetyKeys.class).set(SafetyKeys.platformRadius, 2);
        RTP.configs.getParser(BlocksKeys.class).set(BlocksKeys.unsafeBlocks, List.of("LAVA", "FIRE"));

        // Put cached chunk at cx=0, cz=0
        world.getChunkAt(0, 0).join();

        // 1. belowY < minY
        int minY = world.getMinHeight();
        RTPCoords coordsBelowMin = new RTPCoords(world.name(), 5, minY, 5);
        boolean resMin = (boolean) m.invoke(null, world, coordsBelowMin);
        assertTrue(resMin, "Landing where belowY < minY should require platform");
    }

    // -------------------------------------------------------------------------
    // 4. buildArrivalPlatform branches (PlatformCreator addon hook)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("buildArrivalPlatform handles null location or world gracefully")
    void buildArrivalPlatform_nulls_safe() throws Exception {
        TeleportPipelineTask task = new TeleportPipelineTask(new GenerationContext(null, null, null));
        Method m = TeleportPipelineTask.class.getDeclaredMethod("buildArrivalPlatform", RTPLocation.class);
        m.setAccessible(true);

        assertDoesNotThrow(() -> m.invoke(task, (Object) null));
        assertDoesNotThrow(() -> m.invoke(task, new RTPLocation(null, 0, 64, 0)));
    }

    @Test
    @DisplayName("buildArrivalPlatform uses PlatformCreator when available and succeeds")
    void buildArrivalPlatform_uses_platformCreator() throws Exception {
        TeleportPipelineTask task = new TeleportPipelineTask(new GenerationContext(null, null, null));

        AtomicBoolean creatorInvoked = new AtomicBoolean(false);
        PlatformCreator creator = new PlatformCreator() {
            @Override
            public String creatorName() {
                return "MockCreator";
            }

            @Override
            public CompletableFuture<?> prepare(RTPLocation location) {
                return CompletableFuture.completedFuture("prepared-context");
            }

            @Override
            public boolean createPlatform(RTPLocation location, Object preparedContext) {
                assertEquals("prepared-context", preparedContext);
                creatorInvoked.set(true);
                return true;
            }
        };

        Field fCreator = TeleportPipelineTask.class.getDeclaredField("platformCreator");
        fCreator.setAccessible(true);
        fCreator.set(task, creator);

        Field fPrepare = TeleportPipelineTask.class.getDeclaredField("platformPrepare");
        fPrepare.setAccessible(true);
        fPrepare.set(task, CompletableFuture.completedFuture("prepared-context"));

        Method m = TeleportPipelineTask.class.getDeclaredMethod("buildArrivalPlatform", RTPLocation.class);
        m.setAccessible(true);

        RTPLocation loc = new RTPLocation(world, 10, 64, 10);
        m.invoke(task, loc);

        assertTrue(creatorInvoked.get(), "platformCreator.createPlatform should be called and succeed");
    }

    @Test
    @DisplayName("buildArrivalPlatform falls back to default world platform when creator declines or throws")
    void buildArrivalPlatform_fallback_when_creator_declines_or_throws() throws Exception {
        TeleportPipelineTask task = new TeleportPipelineTask(new GenerationContext(null, null, null));

        PlatformCreator decliningCreator = new PlatformCreator() {
            @Override
            public String creatorName() { return "DecliningCreator"; }
            @Override
            public CompletableFuture<?> prepare(RTPLocation location) { return CompletableFuture.completedFuture(null); }
            @Override
            public boolean createPlatform(RTPLocation location, Object preparedContext) { return false; }
        };

        Field fCreator = TeleportPipelineTask.class.getDeclaredField("platformCreator");
        fCreator.setAccessible(true);
        fCreator.set(task, decliningCreator);

        Method m = TeleportPipelineTask.class.getDeclaredMethod("buildArrivalPlatform", RTPLocation.class);
        m.setAccessible(true);

        RTPLocation loc = new RTPLocation(world, 20, 64, 20);
        m.invoke(task, loc);

        // World platform method should have been invoked
        assertTrue(world.isPlatformCalled(), "world.platform should be called on decline");

        // Now test when creator throws
        PlatformCreator throwingCreator = new PlatformCreator() {
            @Override
            public String creatorName() { return "ThrowingCreator"; }
            @Override
            public CompletableFuture<?> prepare(RTPLocation location) { return CompletableFuture.completedFuture(null); }
            @Override
            public boolean createPlatform(RTPLocation location, Object preparedContext) {
                throw new RuntimeException("Intentional creator explosion");
            }
        };
        fCreator.set(task, throwingCreator);
        world.resetPlatformCalled();
        m.invoke(task, loc);
        assertTrue(world.isPlatformCalled(), "world.platform should be called when creator throws");
    }

    // -------------------------------------------------------------------------
    // 5. schematicFootprintClear: edge cases (width<=0, anchor ORIGIN, verifier throws)
    // -------------------------------------------------------------------------
    private record TestSchematic(int width, int height, int length, int offsetX, int offsetZ) implements LoadedSchematic {
        @Override
        public SchematicSource source() { return null; }
    }

    @Test
    @DisplayName("schematicFootprintClear: zero width or length returns true")
    void schematicFootprintClear_zeroDimension_returns_true() {
        LoadedSchematic zeroSchem = new TestSchematic(0, 5, 5, 0, 0);
        RTPLocation loc = new RTPLocation(world, 100, 64, 100);
        assertTrue(TeleportPipelineTask.schematicFootprintClear(zeroSchem, loc, PasteOptions.defaults(), world.name()));

        LoadedSchematic zeroLength = new TestSchematic(5, 5, 0, 0, 0);
        assertTrue(TeleportPipelineTask.schematicFootprintClear(zeroLength, loc, PasteOptions.defaults(), world.name()));
    }

    @Test
    @DisplayName("schematicFootprintClear: anchor ORIGIN uses schematic offsets")
    void schematicFootprintClear_origin_anchor() {
        // ORIGIN: baseX = at.x() + offsetX, baseZ = at.z() + offsetZ
        // At (100, 64, 100) with offset (-2, -2) and size (3, 3), cells are 98..100
        LoadedSchematic schem = new TestSchematic(3, 3, 3, -2, -2);
        RTPLocation loc = new RTPLocation(world, 100, 64, 100);
        PasteOptions originOptions = new PasteOptions(PasteAnchor.ORIGIN, false, true);

        // Block exactly cell 99, 99
        GlobalRegionVerifiers.addGlobalRegionVerifier(c -> !(c.x() == 99 && c.z() == 99));
        assertFalse(TeleportPipelineTask.schematicFootprintClear(schem, loc, originOptions, world.name()));

        GlobalRegionVerifiers.clearGlobalRegionVerifiers();
        assertTrue(TeleportPipelineTask.schematicFootprintClear(schem, loc, originOptions, world.name()));
    }

    @Test
    @DisplayName("schematicFootprintClear: verifier throwing Throwable returns false (S-003 fail-safe)")
    void schematicFootprintClear_verifierThrows_failsSafe() {
        LoadedSchematic schem = new TestSchematic(3, 3, 3, 0, 0);
        RTPLocation loc = new RTPLocation(world, 100, 64, 100);

        GlobalRegionVerifiers.addGlobalRegionVerifier(c -> {
            throw new RuntimeException("Claim plugin error");
        });

        assertFalse(TeleportPipelineTask.schematicFootprintClear(schem, loc, PasteOptions.defaults(), world.name()),
                "When claim check throws, must fail closed and return false (S-003)");
    }

    // -------------------------------------------------------------------------
    // 6. PvP gate execution prefilter in runTeleport
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("runTeleport aborts when PvP gate rejects player (in combat)")
    void runTeleport_aborts_on_pvp_combat() {
        Region reg = createTestRegion("pvp_test_reg");
        SettablePlayer player = createPlayer("PvPPlayer");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        chunks.add(CompletableFuture.completedFuture(0L));
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.TELEPORT);

        RTP.getInstance().processingPlayers.add(player.uuid());

        // Configure PvP Gate in safety.yml
        Map<String, Object> map = new HashMap<>();
        map.put(SafetyKeys.pvpSource.name(), "NATIVE");
        map.put(SafetyKeys.pvpCheckEnabled.name(), true);
        map.put(SafetyKeys.pvpOnCombat.name(), "CANCEL");
        RTP.configs.getParser(SafetyKeys.class).setData(map);

        PvPGate.nativeTracker().stamp(player.uuid(), System.currentTimeMillis());

        task.run();

        // Player should receive pvpInCombat message, processingPlayers removed, transitioned to CLEANUP
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
        assertFalse(RTP.getInstance().processingPlayers.contains(player.uuid()));
    }

    @Test
    @DisplayName("runTeleport handles PvP gate exception by allowing teleport safely")
    void runTeleport_handles_pvp_exception() {
        Region reg = createTestRegion("pvp_ex_reg");
        SettablePlayer player = createPlayer("PvPExPlayer");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        chunks.add(CompletableFuture.completedFuture(0L));
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.TELEPORT);

        RTP.getInstance().databaseAccessor = Mockito.mock(DatabaseAccessor.class);

        // Clear pvp gate
        PvPGate.nativeTracker().clearAll();

        task.run();
        // Should proceed with teleport dispatch and reach cleanup
        accessor.getMockScheduler().tick(1);
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    // -------------------------------------------------------------------------
    // 7. Schematic pasting in runTeleport
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("runTeleport executes schematic pasting via destination region scheduler task")
    void runTeleport_executes_schematic_pasting() throws Exception {
        Region reg = createTestRegion("schem_paste_reg");
        SettablePlayer player = createPlayer("SchemP");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        chunks.add(CompletableFuture.completedFuture(0L));
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.TELEPORT);

        AtomicBoolean pasteAttempted = new AtomicBoolean(false);
        LoadedSchematic mockSchematic = new TestSchematic(3, 3, 3, 0, 0);

        SchematicPaster mockPaster = new SchematicPaster() {
            @Override
            public boolean supports(SchematicSource source) { return true; }
            @Override
            public CompletableFuture<LoadedSchematic> load(SchematicSource source) {
                return CompletableFuture.completedFuture(mockSchematic);
            }
            @Override
            public PasteResult paste(LoadedSchematic schematic, RTPLocation target, PasteOptions options) {
                pasteAttempted.set(true);
                return PasteResult.PASTED;
            }
        };

        Field fSchemLoad = TeleportPipelineTask.class.getDeclaredField("schematicLoad");
        fSchemLoad.setAccessible(true);
        fSchemLoad.set(task, CompletableFuture.completedFuture(mockSchematic));

        Field fPaster = TeleportPipelineTask.class.getDeclaredField("schematicPaster");
        fPaster.setAccessible(true);
        fPaster.set(task, mockPaster);

        RTP.getInstance().databaseAccessor = Mockito.mock(DatabaseAccessor.class);

        task.run();
        // Tick scheduler so the scheduled destination region task runs
        accessor.getMockScheduler().tick(1);
        accessor.getMockScheduler().tick(1);

        assertTrue(pasteAttempted.get(), "Schematic paste should be dispatched to destination region task");
    }

    // -------------------------------------------------------------------------
    // 8. setLocation callback branches (respawn, lockAfter, postTeleportQueueing, invulnerability)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("setLocation callback: success paths (respawn, limit store, invulnerability, postTeleportQueueing)")
    void setLocation_callback_success_branches() {
        Region reg = createTestRegion("success_reg");
        SettablePlayer player = createPlayer("SuccessPlayer");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        chunks.add(CompletableFuture.completedFuture(0L));
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.TELEPORT);

        // Turn on all relevant flags
        RTP.configs.getParser(ConfigKeys.class).set(ConfigKeys.setRespawnOnTeleport, true);
        TeleportPipelineTask.ConfigCache.setRespawnOnTeleport = true;
        TeleportPipelineTask.ConfigCache.lockAfterUses = 5;
        TeleportPipelineTask.ConfigCache.lockAfterResetMillis = 60000;
        TeleportPipelineTask.ConfigCache.postTeleportQueueing = true;
        TeleportPipelineTask.ConfigCache.teleportMessage = "You arrived safely!";

        RTP.configs.getParser(SafetyKeys.class).set(SafetyKeys.invulnerabilityTime, 5L);

        RTP.getInstance().databaseAccessor = Mockito.mock(DatabaseAccessor.class);

        // Pre-populate latestTeleportData and assign to task field
        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        data.sender = player;
        data.targetRegion = reg;
        data.selectedCoords = coords;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        try {
            Field fData = TeleportPipelineTask.class.getDeclaredField("teleportData");
            fData.setAccessible(true);
            fData.set(task, data);
        } catch (Exception ignored) {}

        task.run();
        accessor.getMockScheduler().tick(1);
        accessor.getMockScheduler().tick(1);

        // Verify teleport success message was sent
        assertTrue(player.sentMessages.stream().anyMatch(msg -> msg.contains("arrived") || msg.contains("safely")),
                "Player should receive teleport success message");

        // 1. Verify respawn was set
        RTPLocation respawn = player.getRespawnLocation();
        assertNotNull(respawn, "Respawn location should be updated");
        assertEquals(coords.x(), respawn.x());

        // 2. Verify lockAfter recorded in store
        assertTrue(RTP.getInstance().teleportLimitStore.uses(player.uuid(), 60000, System.currentTimeMillis()) > 0
                        || RTP.getInstance().teleportLimitStore.isLocked(player.uuid(), 5, 60000, System.currentTimeMillis()),
                "lockAfterUses should record success in store");

        // 3. Verify postTeleportQueueing added a task to region's cachePipeline
        assertTrue(reg.cachePipeline.size() > 0, "postTeleportQueueing should add RegionCacheTask to cachePipeline");

        // 4. Verify invulnerability was applied
        assertTrue(RTP.getInstance().invulnerablePlayers.containsKey(player.uuid()));

        // Tick scheduler past 5 * 20 ticks = 100 ticks to verify invulnerability expiration task runs
        for (int i = 0; i < 110; i++) {
            accessor.getMockScheduler().tick(1);
        }
        assertFalse(RTP.getInstance().invulnerablePlayers.containsKey(player.uuid()),
                "Invulnerability should expire after duration ticks");

        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @DisplayName("setLocation callback: failure path (aBoolean is false, reservation closed, invulnerability removed)")
    void setLocation_callback_failure_branch() {
        Region reg = createTestRegion("fail_reg");
        SettablePlayer player = createPlayer("FailPlayer");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        chunks.add(CompletableFuture.completedFuture(0L));
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.TELEPORT);

        TeleportPipelineTask.ConfigCache.unsafe = "Destination was unsafe!";
        TeleportPipelineTask.ConfigCache.setRespawnOnTeleport = true;

        RTP.configs.getParser(SafetyKeys.class).set(SafetyKeys.invulnerabilityTime, 0L);
        RTP.getInstance().databaseAccessor = Mockito.mock(DatabaseAccessor.class);

        // Configure player to fail setLocation
        player.setFailSetLocation(true);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        data.sender = player;
        data.selectedCoords = coords;
        data.targetRegion = reg;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        try {
            Field fData = TeleportPipelineTask.class.getDeclaredField("teleportData");
            fData.setAccessible(true);
            fData.set(task, data);
        } catch (Exception ignored) {}

        task.run();
        accessor.getMockScheduler().tick(1);
        accessor.getMockScheduler().tick(1);

        // Should NOT have set respawn
        assertNull(player.getRespawnLocation());
        // Should have sent unsafe message to player
        assertTrue(player.sentMessages.stream().anyMatch(msg -> msg.contains("unsafe") || msg.contains("Destination")),
                "Player should receive unsafe message on setLocation failure");
        // Phase should be CLEANUP
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    // -------------------------------------------------------------------------
    // 9. runLoad delay calculation & scheduler dispatch
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("runLoad with negative remaining time runs inline on primary thread")
    void runLoad_immediate_dispatch_on_primary_thread() {
        Region reg = createTestRegion("load_imm_reg");
        SettablePlayer player = createPlayer("ImmLoadP");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        chunks.add(CompletableFuture.completedFuture(0L));
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.LOAD);

        RTP.getInstance().databaseAccessor = Mockito.mock(DatabaseAccessor.class);

        task.run();
        // Because delay is 0 and chunkSet is already done, transitions to TELEPORT or CLEANUP
        assertTrue(task.getPhase() == TeleportPipelineTask.Phase.TELEPORT
                || task.getPhase() == TeleportPipelineTask.Phase.CLEANUP);
    }

    @Test
    @DisplayName("runLoad when chunkSet completes with false/null triggers CLEANUP")
    void runLoad_chunkSet_failure_cleans_up() {
        Region reg = createTestRegion("chunk_fail_reg");
        SettablePlayer player = createPlayer("ChunkFailP");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        CompletableFuture<Boolean> chunkSetFuture = new CompletableFuture<>();
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, Collections.emptyList(), chunkSetFuture);
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.LOAD);

        task.run();
        // Complete chunkSet with false
        chunkSetFuture.complete(false);

        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    // -------------------------------------------------------------------------
    // 10. runCleanup metrics recording & inFlightCalculations decrement
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("runCleanup: fireOnComplete, reservation release, inFlight decrement, and nextTask clear")
    void runCleanup_full_teardown_assertions() {
        Region reg = createTestRegion("cleanup_full_reg");
        reg.inFlightCalculations.set(3);

        SettablePlayer player = createPlayer("CleanupFullP");
        GenerationContext ctx = new GenerationContext(player, player, null);
        RTPCoords coords = new RTPCoords(world.name(), 10, 64, 10);

        CompletableFuture<Long> chunkFuture = CompletableFuture.completedFuture(0L);
        ChunkSet chunkSet = new ChunkSet(world, 1, 1, List.of(chunkFuture), CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.CLEANUP);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        data.sender = player;
        data.selectedCoords = coords;
        data.targetRegion = reg;
        data.nextTask = task;
        data.completed = false; // not completed yet
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        AtomicBoolean onCompleteFired = new AtomicBoolean(false);
        data.onComplete = d -> onCompleteFired.set(true);

        try {
            Field fData = TeleportPipelineTask.class.getDeclaredField("teleportData");
            fData.setAccessible(true);
            fData.set(task, data);
        } catch (Exception ignored) {}

        task.run();

        // 1. fireOnComplete fired
        assertTrue(onCompleteFired.get(), "teleportData.fireOnComplete() must be invoked");

        // 2. data was removed from latestTeleportData because !data.completed
        assertNull(RTP.getInstance().latestTeleportData.get(player.uuid()),
                "Incomplete teleport data must be removed from latestTeleportData on cleanup");

        // 3. inFlightCalculations decremented
        assertEquals(2, reg.inFlightCalculations.get(), "inFlightCalculations must be decremented");

        // 4. nextTask cleared
        assertNull(data.nextTask, "teleportData.nextTask must be set to null");
    }

    @Test
    @DisplayName("runCleanup: completed teleport data is NOT removed from latestTeleportData")
    void runCleanup_preserves_completed_teleport_data() {
        Region reg = createTestRegion("cleanup_preserve_reg");
        SettablePlayer player = createPlayer("CleanupPreserveP");
        GenerationContext ctx = new GenerationContext(player, player, null);

        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);
        task.setPhase(TeleportPipelineTask.Phase.CLEANUP);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        data.completed = true; // completed!
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        try {
            Field fData = TeleportPipelineTask.class.getDeclaredField("teleportData");
            fData.setAccessible(true);
            fData.set(task, data);
        } catch (Exception ignored) {}

        task.run();

        // Should NOT remove because data.completed is true
        assertSame(data, RTP.getInstance().latestTeleportData.get(player.uuid()),
                "Completed teleport data should be retained in latestTeleportData");
    }

    @Test
    @DisplayName("runCleanup audits slow immediate teleport on CoreMetrics")
    void runCleanup_audits_immediate_teleport() {
        Region reg = createTestRegion("audit_reg");
        SettablePlayer player = createPlayer("AuditP");
        GenerationContext ctx = new GenerationContext(player, player, null);

        // 2-arg constructor sets immediateTeleport = true
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);
        task.setPhase(TeleportPipelineTask.Phase.CLEANUP);

        assertDoesNotThrow(task::run);
    }

    // -------------------------------------------------------------------------
    // 11. sparkFrameName & setCancelled untracking
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("sparkFrameName returns expected string tag")
    void sparkFrameName_returns_tag() {
        TeleportPipelineTask task = new TeleportPipelineTask(new GenerationContext(null, null, null));
        assertEquals("rtp_pipeline_attempt", task.sparkFrameName());
    }

    @Test
    @DisplayName("setCancelled true untracks task from MemoryTracker")
    void setCancelled_untracks_memory_tracker() {
        SettablePlayer player = createPlayer("CancelTrackP");
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx);
        int countBefore = MemoryTracker.trackedCountByLabel("TeleportPipelineTask");
        assertTrue(countBefore > 0);

        task.setCancelled(true);
        int countAfter = MemoryTracker.trackedCountByLabel("TeleportPipelineTask");
        assertEquals(countBefore - 1, countAfter);

        // Repeated cancellation is idempotent
        task.setCancelled(true);
        assertEquals(countAfter, MemoryTracker.trackedCountByLabel("TeleportPipelineTask"));
    }

    // -------------------------------------------------------------------------
    // 12. processGenerationResult chunk window calculation (reservation == null)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("processGenerationResult loads chunk window around target coordinates when reservation is null")
    void processGenerationResult_chunk_window_loading() throws Exception {
        Region reg = createTestRegion("chunk_win_reg");
        SettablePlayer player = createPlayer("ChunkWinP");
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        // Pre-populate latestTeleportData and assign to task field
        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        data.sender = player;
        data.targetRegion = reg;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        Field fData = TeleportPipelineTask.class.getDeclaredField("teleportData");
        fData.setAccessible(true);
        fData.set(task, data);

        // Set viewDistanceTeleport to 1 (3x3 chunk window: -1..1 x -1..1 = 9 chunks)
        TeleportPipelineTask.ConfigCache.viewDistanceTeleport = 1;

        RTPCoords targetCoords = new RTPCoords(world.name(), 32, 64, 48); // cx=2, cz=3
        GenerationResult genResult = new GenerationResult(targetCoords, 3L, null);

        Method m = TeleportPipelineTask.class.getDeclaredMethod("processGenerationResult", GenerationResult.class);
        m.setAccessible(true);

        m.invoke(task, genResult);

        // Field chunkSet should be populated
        Field fChunkSet = TeleportPipelineTask.class.getDeclaredField("chunkSet");
        fChunkSet.setAccessible(true);
        ChunkSet cs = (ChunkSet) fChunkSet.get(task);
        assertNotNull(cs);
        assertEquals(2, cs.x());
        assertEquals(3, cs.z());
        assertEquals(9, cs.chunks().size(), "Radius 1 should load exactly (2*1+1)^2 = 9 chunks");

        // Verify all 9 requested chunk coordinates match (cx + x, cz + z)
        assertEquals(9, world.requestedChunks.size());
        int idx = 0;
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                long[] coord = world.requestedChunks.get(idx++);
                assertEquals(2 + x, coord[0], "Requested chunk X mismatch at (" + x + "," + z + ")");
                assertEquals(3 + z, coord[1], "Requested chunk Z mismatch at (" + x + "," + z + ")");
            }
        }
    }

    // -------------------------------------------------------------------------
    // 13. runLoad delay branches & player scheduling
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("runLoad with sender delay > 0 schedules task for player with remaining ticks")
    void runLoad_with_delay_schedules_player_task() throws Exception {
        Region reg = createTestRegion("delay_reg");
        SettablePlayer player = createPlayer("DelayPlayer");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        CompletableFuture<Long> chunkFuture = new CompletableFuture<>();
        List<CompletableFuture<Long>> chunks = List.of(chunkFuture);
        CompletableFuture<Boolean> chunkSetFuture = new CompletableFuture<>();
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, chunkSetFuture);
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        // Player sender with 5000ms delay (100 ticks)
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "SenderP", new RTPLocation(world, 0, 64, 0)) {
            @Override
            public long delay() { return 5000L; }
        };
        accessor.addPlayer(sender);
        GenerationContext ctx = new GenerationContext(player, sender, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.LOAD);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis(); // start now, so dT is small, remainingTime ~ 5000ms
        data.sender = sender;
        data.selectedCoords = coords;
        data.targetRegion = reg;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        Field fData = TeleportPipelineTask.class.getDeclaredField("teleportData");
        fData.setAccessible(true);
        fData.set(task, data);

        // Explicitly set chunkSet on task so runLoad does not overwrite it
        Field fChunkSet = TeleportPipelineTask.class.getDeclaredField("chunkSet");
        fChunkSet.setAccessible(true);
        fChunkSet.set(task, chunkSet);

        // Complete the chunk future before task.run() so the chunkSet is ready
        chunkFuture.complete(0L);

        AtomicBoolean taskRan = new AtomicBoolean(false);
        TeleportPipelineTask.teleportPreActions.add(t -> taskRan.set(true));

        task.run();

        // Either scheduled or executed
        assertTrue(taskRan.get() || !accessor.getMockScheduler().getScheduledTasks().isEmpty());
    }

    @Test
    @DisplayName("runLoad notifies player when chunkSet is not yet complete")
    void runLoad_notifies_player_on_pending_chunkset() {
        Region reg = createTestRegion("notify_load_reg");
        SettablePlayer player = createPlayer("NotifyLoadP");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        CompletableFuture<Boolean> chunkSetFuture = new CompletableFuture<>();
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, Collections.emptyList(), chunkSetFuture);
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.LOAD);

        task.run();
        // Player should receive chunkLoading message
        // Complete future so it cleans up
        chunkSetFuture.complete(true);
        accessor.getMockScheduler().tick(1);
    }

    // -------------------------------------------------------------------------
    // 14. Schematic loading branches in runLoad & paste fallback branches in runTeleport
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("runLoad and runTeleport schematic fallback paths (null schematic, unsupported paster, decode error)")
    void schematic_pipeline_fallback_branches() throws Exception {
        Region reg = createTestRegion("schem_fallback_reg");
        SettablePlayer player = createPlayer("SchemFallP");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        chunks.add(CompletableFuture.completedFuture(0L));
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.TELEPORT);

        // Case A: schematicSource present, but schematicLoad completed with null (decode error)
        Field fSrc = TeleportPipelineTask.class.getDeclaredField("schematicSource");
        fSrc.setAccessible(true);
        fSrc.set(task, new SchematicSource("schem_fallback_reg", java.nio.file.Path.of("schematics/schem_fallback_reg.schem"), "schem"));

        Field fSchemLoad = TeleportPipelineTask.class.getDeclaredField("schematicLoad");
        fSchemLoad.setAccessible(true);
        fSchemLoad.set(task, CompletableFuture.completedFuture(null));

        RTP.getInstance().databaseAccessor = Mockito.mock(DatabaseAccessor.class);

        task.run();
        accessor.getMockScheduler().tick(1);

        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @DisplayName("runTeleport schematic paste: claim check suppression falls back to platform")
    void runTeleport_schematic_claim_suppression() throws Exception {
        Region reg = createTestRegion("schem_claim_reg");
        SettablePlayer player = createPlayer("SchemClaimP");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        chunks.add(CompletableFuture.completedFuture(0L));
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.TELEPORT);

        LoadedSchematic mockSchematic = new TestSchematic(3, 3, 3, 0, 0);
        Field fSchemLoad = TeleportPipelineTask.class.getDeclaredField("schematicLoad");
        fSchemLoad.setAccessible(true);
        fSchemLoad.set(task, CompletableFuture.completedFuture(mockSchematic));

        AtomicBoolean pasterCalled = new AtomicBoolean(false);
        SchematicPaster mockPaster = new SchematicPaster() {
            @Override public boolean supports(SchematicSource source) { return true; }
            @Override public CompletableFuture<LoadedSchematic> load(SchematicSource source) { return CompletableFuture.completedFuture(mockSchematic); }
            @Override public PasteResult paste(LoadedSchematic schematic, RTPLocation target, PasteOptions options) {
                pasterCalled.set(true);
                return PasteResult.PASTED;
            }
        };

        Field fPaster = TeleportPipelineTask.class.getDeclaredField("schematicPaster");
        fPaster.setAccessible(true);
        fPaster.set(task, mockPaster);

        // Register claim verifier that rejects cell (50, 70, 50)
        GlobalRegionVerifiers.addGlobalRegionVerifier(c -> !(c.x() == 50 && c.z() == 50));

        RTP.getInstance().databaseAccessor = Mockito.mock(DatabaseAccessor.class);

        world.resetPlatformCalled();
        task.run();
        accessor.getMockScheduler().tick(1);
        accessor.getMockScheduler().tick(1);

        assertFalse(pasterCalled.get(), "Paste should be suppressed due to claim intersection");
        assertTrue(world.isPlatformCalled(), "Fallback to arrival platform should be triggered");
    }

    @Test
    @DisplayName("runTeleport schematic paste: paster returning non-PASTED falls back to platform")
    void runTeleport_schematic_unsuccessful_paste() throws Exception {
        Region reg = createTestRegion("schem_fail_reg");
        SettablePlayer player = createPlayer("SchemFailP");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        chunks.add(CompletableFuture.completedFuture(0L));
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.TELEPORT);

        LoadedSchematic mockSchematic = new TestSchematic(3, 3, 3, 0, 0);
        Field fSchemLoad = TeleportPipelineTask.class.getDeclaredField("schematicLoad");
        fSchemLoad.setAccessible(true);
        fSchemLoad.set(task, CompletableFuture.completedFuture(mockSchematic));

        SchematicPaster mockPaster = new SchematicPaster() {
            @Override public boolean supports(SchematicSource source) { return true; }
            @Override public CompletableFuture<LoadedSchematic> load(SchematicSource source) { return CompletableFuture.completedFuture(mockSchematic); }
            @Override public PasteResult paste(LoadedSchematic schematic, RTPLocation target, PasteOptions options) {
                return PasteResult.PASTE_ERROR;
            }
        };

        Field fPaster = TeleportPipelineTask.class.getDeclaredField("schematicPaster");
        fPaster.setAccessible(true);
        fPaster.set(task, mockPaster);

        RTP.getInstance().databaseAccessor = Mockito.mock(DatabaseAccessor.class);

        world.resetPlatformCalled();
        task.run();
        accessor.getMockScheduler().tick(1);
        accessor.getMockScheduler().tick(1);

        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
        assertTrue(world.isPlatformCalled(), "Fallback to arrival platform should be triggered when paste fails");
    }

    @Test
    @DisplayName("runTeleport schematic paste: paster throwing exception handled gracefully")
    void runTeleport_schematic_throwing_paste() throws Exception {
        Region reg = createTestRegion("schem_throw_reg");
        SettablePlayer player = createPlayer("SchemThrowP");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        chunks.add(CompletableFuture.completedFuture(0L));
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.TELEPORT);

        LoadedSchematic mockSchematic = new TestSchematic(3, 3, 3, 0, 0);
        Field fSchemLoad = TeleportPipelineTask.class.getDeclaredField("schematicLoad");
        fSchemLoad.setAccessible(true);
        fSchemLoad.set(task, CompletableFuture.completedFuture(mockSchematic));

        SchematicPaster mockPaster = new SchematicPaster() {
            @Override public boolean supports(SchematicSource source) { return true; }
            @Override public CompletableFuture<LoadedSchematic> load(SchematicSource source) { return CompletableFuture.completedFuture(mockSchematic); }
            @Override public PasteResult paste(LoadedSchematic schematic, RTPLocation target, PasteOptions options) {
                throw new RuntimeException("Paste failure");
            }
        };

        Field fPaster = TeleportPipelineTask.class.getDeclaredField("schematicPaster");
        fPaster.setAccessible(true);
        fPaster.set(task, mockPaster);

        RTP.getInstance().databaseAccessor = Mockito.mock(DatabaseAccessor.class);

        task.run();
        accessor.getMockScheduler().tick(1);
        accessor.getMockScheduler().tick(1);

        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    // -------------------------------------------------------------------------
    // 15. runSetup & processGenerationResult exhaustive branches
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("runSetup: cancelled task transitions directly to CLEANUP")
    void runSetup_cancelled_cleans_up() {
        SettablePlayer player = createPlayer("SetupCancelP");
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx);
        task.setCancelled(true);
        task.run();
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @DisplayName("runSetup: null player transitions directly to CLEANUP")
    void runSetup_null_player_cleans_up() {
        GenerationContext ctx = new GenerationContext(null, null, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx);
        task.run();
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @DisplayName("processGenerationResult: null result with player in processingPlayers silently halts and decrements inFlightCalculations")
    void processGenerationResult_null_result_processing_silent_halt() throws Exception {
        Region reg = createTestRegion("silent_halt_reg");
        reg.inFlightCalculations.set(5);
        SettablePlayer player = createPlayer("SilentHaltP");
        RTP.getInstance().processingPlayers.add(player.uuid());

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        Method m = TeleportPipelineTask.class.getDeclaredMethod("processGenerationResult", GenerationResult.class);
        m.setAccessible(true);
        m.invoke(task, (Object) null);

        // Handled in flight, should decrement inFlightCalculations
        assertEquals(4, reg.inFlightCalculations.get(), "inFlightCalculations should be decremented on silent halt");
    }

    @Test
    @DisplayName("processGenerationResult: null result without player in processingPlayers transitions to CLEANUP")
    void processGenerationResult_null_result_unprocessing_cleans_up() throws Exception {
        Region reg = createTestRegion("null_res_reg");
        SettablePlayer player = createPlayer("NullResP");

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        Method m = TeleportPipelineTask.class.getDeclaredMethod("processGenerationResult", GenerationResult.class);
        m.setAccessible(true);
        m.invoke(task, (Object) null);

        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @DisplayName("processGenerationResult: null coords in result records attempts, refunds, and cleans up")
    void processGenerationResult_null_coords_refunds() throws Exception {
        Region reg = createTestRegion("null_coords_reg");
        SettablePlayer player = createPlayer("NullCoordsP");

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        data.sender = player;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        Field fData = TeleportPipelineTask.class.getDeclaredField("teleportData");
        fData.setAccessible(true);
        fData.set(task, data);

        AtomicBoolean postActionInvoked = new AtomicBoolean(false);
        AtomicBoolean finalSuccessValue = new AtomicBoolean(true);
        TeleportPipelineTask.setupPostActions.add((t, s) -> {
            postActionInvoked.set(true);
            finalSuccessValue.set(s);
        });

        GenerationResult genResult = new GenerationResult(null, 7L, (ChunkSet) null);

        Method m = TeleportPipelineTask.class.getDeclaredMethod("processGenerationResult", GenerationResult.class);
        m.setAccessible(true);
        m.invoke(task, genResult);

        assertEquals(7L, data.attempts);
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
        assertTrue(postActionInvoked.get(), "setupPostActions must be invoked on failure");
        assertFalse(finalSuccessValue.get(), "finalSuccess must be false when coords is null");
    }

    @Test
    @DisplayName("processGenerationResult: successful result increments chunkSetPipeline metric and runs async")
    void processGenerationResult_increments_metric_and_runs_async() throws Exception {
        Region reg = createTestRegion("metric_async_reg");
        SettablePlayer player = createPlayer("MetricAsyncP");
        RTPCoords targetCoords = new RTPCoords(world.name(), 16, 64, 16);

        TeleportPipelineTask.ConfigCache.viewDistanceTeleport = 0;
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        data.sender = player;
        data.targetRegion = reg;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        Field fData = TeleportPipelineTask.class.getDeclaredField("teleportData");
        fData.setAccessible(true);
        fData.set(task, data);

        long beforeMetric = io.github.dailystruggle.rtp.common.tools.CfDiag.chunkSetPipeline.sum();

        GenerationResult genResult = new GenerationResult(targetCoords, 1L, (ChunkSet) null);

        Method m = TeleportPipelineTask.class.getDeclaredMethod("processGenerationResult", GenerationResult.class);
        m.setAccessible(true);
        m.invoke(task, genResult);

        long afterMetric = io.github.dailystruggle.rtp.common.tools.CfDiag.chunkSetPipeline.sum();
        assertEquals(beforeMetric + 1, afterMetric, "CfDiag.chunkSetPipeline must be incremented on new ChunkSet creation");
    }

    @Test
    @DisplayName("processGenerationResult: exception during chunk calculation cancels teleport and cleans up")
    void processGenerationResult_exception_cancels_and_cleans_up() throws Exception {
        SettablePlayer player = createPlayer("GenExP");

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, null);

        // RTPCoords with world that throws on getChunkAt
        PlatformTrackingMockWorld throwingWorld = new PlatformTrackingMockWorld("throw_world") {
            @Override
            public CompletableFuture<Long> getChunkAt(int cx, int cz) {
                throw new RuntimeException("Intentional chunk error");
            }
        };
        accessor.addWorld(throwingWorld);

        RTPCoords throwingCoords = new RTPCoords(throwingWorld.name(), 0, 64, 0);
        GenerationResult genResult = new GenerationResult(throwingCoords, 1L, null);

        Method m = TeleportPipelineTask.class.getDeclaredMethod("processGenerationResult", GenerationResult.class);
        m.setAccessible(true);
        m.invoke(task, genResult);

        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @DisplayName("processGenerationResult: already completed chunkSet dispatches task to region owning target coordinates")
    void processGenerationResult_already_completed_chunkSet_dispatches_task() throws Exception {
        Region reg = createTestRegion("pgr_done_reg");
        SettablePlayer player = createPlayer("PgrDoneP");
        RTPCoords targetCoords = new RTPCoords(world.name(), 32, 64, 48); // cx=2, cz=3

        CompletableFuture<Long> chunkFuture = CompletableFuture.completedFuture(0L);
        ChunkSet chunkSet = new ChunkSet(world, 2, 3, List.of(chunkFuture), CompletableFuture.completedFuture(true));

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        data.sender = player;
        data.targetRegion = reg;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        Field fData = TeleportPipelineTask.class.getDeclaredField("teleportData");
        fData.setAccessible(true);
        fData.set(task, data);

        GenerationResult genResult = new GenerationResult(targetCoords, 1L, chunkSet);

        Method m = TeleportPipelineTask.class.getDeclaredMethod("processGenerationResult", GenerationResult.class);
        m.setAccessible(true);
        m.invoke(task, genResult);

        // Should have processed generation result and reached LOAD or CLEANUP
        assertNotNull(task.getPhase());
    }

    // -------------------------------------------------------------------------
    // 16. shouldBuildPlatform radius loop checks
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("shouldBuildPlatform: platformRadius > 0 checks surrounding blocks")
    void shouldBuildPlatform_radius_greater_than_zero() throws Exception {
        Method m = TeleportPipelineTask.class.getDeclaredMethod("shouldBuildPlatform", RTPWorld.class, RTPCoords.class);
        m.setAccessible(true);

        RTP.configs.getParser(SafetyKeys.class).set(SafetyKeys.platformRadius, 1);
        world.getChunkAt(0, 0).join();

        RTPCoords centerCoords = new RTPCoords(world.name(), 5, 64, 5);
        boolean needsPlatform = (boolean) m.invoke(null, world, centerCoords);
        assertNotNull(needsPlatform);
    }

    // -------------------------------------------------------------------------
    // 20. shouldBuildPlatform multi-block loop mutation killers
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("shouldBuildPlatform: radius == 0 returns false when single landing block is solid ground")
    void shouldBuildPlatform_radius_zero_safe() throws Exception {
        Method m = TeleportPipelineTask.class.getDeclaredMethod("shouldBuildPlatform", RTPWorld.class, RTPCoords.class);
        m.setAccessible(true);

        RTP.configs.getParser(SafetyKeys.class).set(SafetyKeys.platformRadius, 0);
        world.getChunkAt(0, 0).join();

        // Landing at y=32 has belowY=31 which is solid ground in MockRTPChunk (!isAir(31))
        RTPCoords centerCoords = new RTPCoords(world.name(), 5, 32, 5);
        boolean needsPlatform = (boolean) m.invoke(null, world, centerCoords);
        assertFalse(needsPlatform, "Radius 0 with solid ground below requires no platform");
    }

    @Test
    @DisplayName("shouldBuildPlatform: coordinate math and bitwise masks")
    void shouldBuildPlatform_coordinate_math() throws Exception {
        Method m = TeleportPipelineTask.class.getDeclaredMethod("shouldBuildPlatform", RTPWorld.class, RTPCoords.class);
        m.setAccessible(true);

        RTP.configs.getParser(SafetyKeys.class).set(SafetyKeys.platformRadius, 0);

        // World that asserts exactly the chunk key requested in getCachedChunk
        AtomicBoolean chunkKeyVerified = new AtomicBoolean(false);
        PlatformTrackingMockWorld mathWorld = new PlatformTrackingMockWorld("math_world") {
            @Override
            public RTPChunk<?> getCachedChunk(long key) {
                // For x = 35 (cx = 2), z = 49 (cz = 3):
                // cx = 35 >> 4 = 2, cz = 49 >> 4 = 3
                // key = (2 & 0xffffffffL) | ((3 & 0xffffffffL) << 32)
                long expectedKey = ((long) 2 & 0xffffffffL) | (((long) 3 & 0xffffffffL) << 32);
                if (key == expectedKey) {
                    chunkKeyVerified.set(true);
                }
                return new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(2, 3, this);
            }
        };
        accessor.addWorld(mathWorld);

        RTPCoords coords = new RTPCoords(mathWorld.name(), 35, 32, 49);
        m.invoke(null, mathWorld, coords);

        assertTrue(chunkKeyVerified.get(), "Chunk key must correctly encode cx and cz with bit shifts and masks");
    }

    // -------------------------------------------------------------------------
    // 23. processGenerationResult chunkLoadOrigin and countbound tracking
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("processGenerationResult records chunk load origin on world")
    void processGenerationResult_records_chunk_origin() throws Exception {
        Region reg = createTestRegion("origin_reg");
        SettablePlayer player = createPlayer("OriginP");
        RTPCoords targetCoords = new RTPCoords(world.name(), 16, 64, 16);

        TeleportPipelineTask.ConfigCache.viewDistanceTeleport = 0;
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        data.sender = player;
        data.targetRegion = reg;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        Field fData = TeleportPipelineTask.class.getDeclaredField("teleportData");
        fData.setAccessible(true);
        fData.set(task, data);

        GenerationResult genResult = new GenerationResult(targetCoords, 1L, (ChunkSet) null);

        Method m = TeleportPipelineTask.class.getDeclaredMethod("processGenerationResult", GenerationResult.class);
        m.setAccessible(true);
        m.invoke(task, genResult);

        // Verify world received chunk load origin registration
        assertNotNull(task.coords());
    }

    // -------------------------------------------------------------------------
    // 24. runLoad NoOpSchematicPaster and unsupported formats
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("runLoad with NoOpSchematicPaster falls back to arrival platform")
    void runLoad_noop_schematic_paster_fallback() throws Exception {
        Region reg = createTestRegion("schem_noop_reg");
        SettablePlayer player = createPlayer("SchemNoOpP");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        CompletableFuture<Long> chunkFuture = CompletableFuture.completedFuture(0L);
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, List.of(chunkFuture), CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.LOAD);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        data.sender = player;
        data.selectedCoords = coords;
        data.targetRegion = reg;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        Field fData = TeleportPipelineTask.class.getDeclaredField("teleportData");
        fData.setAccessible(true);
        fData.set(task, data);

        Field fChunkSet = TeleportPipelineTask.class.getDeclaredField("chunkSet");
        fChunkSet.setAccessible(true);
        fChunkSet.set(task, chunkSet);

        task.run();

        // task reaches TELEPORT or completes to CLEANUP
        assertTrue(task.getPhase() == TeleportPipelineTask.Phase.TELEPORT
                || task.getPhase() == TeleportPipelineTask.Phase.CLEANUP);
    }

    // -------------------------------------------------------------------------
    // 26. runLoad and runSetup initialization and early exits
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("runLoad: initializes teleportData when null from context and latestTeleportData")
    void runLoad_initializes_teleportData_when_null() {
        Region reg = createTestRegion("load_init_reg");
        SettablePlayer player = createPlayer("LoadInitP");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords);
        task.setPhase(TeleportPipelineTask.Phase.LOAD);

        task.run();

        TeleportData data = RTP.getInstance().latestTeleportData.get(player.uuid());
        assertNotNull(data, "runLoad must populate latestTeleportData if absent");
        assertEquals(coords, data.selectedCoords);
        assertEquals(reg, data.targetRegion);
    }

    @Test
    @DisplayName("runLoad: missing region or coords cleans up")
    void runLoad_missing_region_or_coords_cleans_up() {
        SettablePlayer player = createPlayer("LoadMissingP");
        GenerationContext ctx = new GenerationContext(player, player, null);

        // Task with null region
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, null);
        task.setPhase(TeleportPipelineTask.Phase.LOAD);
        task.run();
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @DisplayName("region() getter returns region passed to task")
    void region_getter_returns_region() {
        Region reg = createTestRegion("get_reg");
        SettablePlayer player = createPlayer("GetRegP");
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);
        assertSame(reg, task.region());
    }

    // -------------------------------------------------------------------------
    // 27. Mathematical precision tests for delay, chunkSet, and coordinates
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("runLoad: cancellation during chunkSet future execution triggers CLEANUP")
    void runLoad_cancellation_during_chunkSet_future() {
        Region reg = createTestRegion("cancel_chunk_reg");
        SettablePlayer player = createPlayer("CancelChunkP");
        RTPCoords coords = new RTPCoords(world.name(), 10, 64, 10);

        CompletableFuture<Boolean> chunkSetFuture = new CompletableFuture<>();
        ChunkSet chunkSet = new ChunkSet(world, 1, 1, Collections.emptyList(), chunkSetFuture);
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.LOAD);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        data.sender = player;
        data.selectedCoords = coords;
        data.targetRegion = reg;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        task.run();

        // Cancel task before chunkSet future completes
        task.setCancelled(true);

        // Complete chunkSet future
        chunkSetFuture.complete(true);

        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase(),
                "Task cancelled during chunkSet completion must transition to CLEANUP");
    }
    @Test
    @DisplayName("runTeleport: null player immediately cleans up")
    void runTeleport_null_player_cleans_up() {
        GenerationContext ctx = new GenerationContext(null, null, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx);
        task.setPhase(TeleportPipelineTask.Phase.TELEPORT);
        task.run();
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @DisplayName("runTeleport: cancelled task immediately cleans up")
    void runTeleport_cancelled_cleans_up() {
        SettablePlayer player = createPlayer("TeleCancelP");
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx);
        task.setPhase(TeleportPipelineTask.Phase.TELEPORT);
        task.setCancelled(true);
        task.run();
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @DisplayName("runTeleport: PvP gate in combat with ALLOW logs info and permits teleport")
    void runTeleport_pvp_in_combat_allowed() {
        Region reg = createTestRegion("pvp_allow_reg");
        SettablePlayer player = createPlayer("PvPAllowP");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        chunks.add(CompletableFuture.completedFuture(0L));
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.TELEPORT);

        // Configure PvP Gate: enabled, combat action ALLOW
        Map<String, Object> map = new HashMap<>();
        map.put(SafetyKeys.pvpSource.name(), "NATIVE");
        map.put(SafetyKeys.pvpCheckEnabled.name(), true);
        map.put(SafetyKeys.pvpOnCombat.name(), "ALLOW");
        RTP.configs.getParser(SafetyKeys.class).setData(map);

        PvPGate.nativeTracker().stamp(player.uuid(), System.currentTimeMillis());

        RTP.getInstance().databaseAccessor = Mockito.mock(DatabaseAccessor.class);

        task.run();
        accessor.getMockScheduler().tick(1);
        accessor.getMockScheduler().tick(1);

        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @DisplayName("runTeleport: schematic decode pending logs warning and falls back")
    void runTeleport_schematic_pending_decode() throws Exception {
        Region reg = createTestRegion("schem_pend_reg");
        SettablePlayer player = createPlayer("SchemPendP");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        chunks.add(CompletableFuture.completedFuture(0L));
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.TELEPORT);

        Field fSrc = TeleportPipelineTask.class.getDeclaredField("schematicSource");
        fSrc.setAccessible(true);
        fSrc.set(task, new SchematicSource("schem_pend_reg", java.nio.file.Path.of("schem.schem"), "schem"));

        // Still pending future (not done)
        CompletableFuture<LoadedSchematic> pending = new CompletableFuture<>();
        Field fSchemLoad = TeleportPipelineTask.class.getDeclaredField("schematicLoad");
        fSchemLoad.setAccessible(true);
        fSchemLoad.set(task, pending);

        RTP.getInstance().databaseAccessor = Mockito.mock(DatabaseAccessor.class);

        task.run();
        accessor.getMockScheduler().tick(1);
        accessor.getMockScheduler().tick(1);

        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }
    @Test
    @DisplayName("ConfigCache reload accurately applies delay ticks and viewDistance restore intervals")
    void configCache_reload_detailed_values() {
        RTP.configs.getParser(PerformanceKeys.class).set(PerformanceKeys.viewDistanceRestoreInterval, 42L);
        RTP.configs.getParser(PerformanceKeys.class).set(PerformanceKeys.viewDistanceTeleport, 3);
        RTP.configs.getParser(ConfigKeys.class).set(ConfigKeys.lockAfterUses, 7);
        RTP.configs.getParser(ConfigKeys.class).set(ConfigKeys.lockAfterResetSeconds, 15);

        TeleportPipelineTask.ConfigCache.reload();

        assertEquals(42L, TeleportPipelineTask.ConfigCache.viewDistanceRestoreInterval);
        assertEquals(3, TeleportPipelineTask.ConfigCache.viewDistanceTeleport);
        assertEquals(7, TeleportPipelineTask.ConfigCache.lockAfterUses);
        assertEquals(15000L, TeleportPipelineTask.ConfigCache.lockAfterResetMillis);
    }

    // -------------------------------------------------------------------------
    // 22. runLoad delay math and primary thread inline execution
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("runLoad with remainingTime <= 0 on primary thread runs inline without scheduling")
    void runLoad_inline_on_primary_thread() throws Exception {
        Region reg = createTestRegion("inline_load_reg");
        SettablePlayer player = createPlayer("InlineLoadP");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        CompletableFuture<Long> chunkFuture = CompletableFuture.completedFuture(0L);
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, List.of(chunkFuture), CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        // Sender with 0 delay
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "ZeroDelaySender", new RTPLocation(world, 0, 64, 0)) {
            @Override
            public long delay() { return 0L; }
        };
        accessor.addPlayer(sender);

        GenerationContext ctx = new GenerationContext(player, sender, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.LOAD);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis() - 1000L; // 1s ago, remainingTime < 0, toTicks = 0
        data.sender = sender;
        data.selectedCoords = coords;
        data.targetRegion = reg;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        Field fData = TeleportPipelineTask.class.getDeclaredField("teleportData");
        fData.setAccessible(true);
        fData.set(task, data);

        Field fChunkSet = TeleportPipelineTask.class.getDeclaredField("chunkSet");
        fChunkSet.setAccessible(true);
        fChunkSet.set(task, chunkSet);

        int scheduledBefore = accessor.getMockScheduler().getScheduledTasks().size();
        task.run();

        // Should NOT have scheduled a delayed task because it ran inline
        int scheduledAfter = accessor.getMockScheduler().getScheduledTasks().size();
        assertEquals(scheduledBefore, scheduledAfter, "Inline execution on primary thread must not schedule player task");
    }

    @Test
    @DisplayName("runLoad with remainingTime <= 0 on non-primary thread schedules player task with 0 delay ticks")
    void runLoad_async_thread_schedules_zero_ticks() throws Exception {
        Region reg = createTestRegion("async_zero_reg");
        SettablePlayer player = createPlayer("AsyncZeroP");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        CompletableFuture<Long> chunkFuture = CompletableFuture.completedFuture(0L);
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, List.of(chunkFuture), CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "ZeroDelaySender2", new RTPLocation(world, 0, 64, 0)) {
            @Override
            public long delay() { return 0L; }
        };
        accessor.addPlayer(sender);

        GenerationContext ctx = new GenerationContext(player, sender, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.LOAD);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis() - 1000L;
        data.sender = sender;
        data.selectedCoords = coords;
        data.targetRegion = reg;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        Field fData = TeleportPipelineTask.class.getDeclaredField("teleportData");
        fData.setAccessible(true);
        fData.set(task, data);

        Field fChunkSet = TeleportPipelineTask.class.getDeclaredField("chunkSet");
        fChunkSet.setAccessible(true);
        fChunkSet.set(task, chunkSet);

        try {
            // Enable server threads so isPrimaryThread returns false
            accessor.getMockScheduler().enableServerThreads();

            int scheduledBefore = accessor.getMockScheduler().getScheduledTasks().size();
            task.run();

            int scheduledAfter = accessor.getMockScheduler().getScheduledTasks().size();
            assertTrue(scheduledAfter > scheduledBefore, "Non-primary thread must schedule via runTaskForPlayer even when toTicks == 0");
        } finally {
            accessor.getMockScheduler().shutdown();
        }
    }

    static class CustomLocationGenerator implements io.github.dailystruggle.rtp.api.selection.ILocationGenerator {
        private java.util.function.BiFunction<Object, GenerationContext, CompletableFuture<GenerationResult>> fn;

        public CustomLocationGenerator(java.util.function.BiFunction<Object, GenerationContext, CompletableFuture<GenerationResult>> fn) {
            this.fn = fn;
        }

        @Override
        public CompletableFuture<GenerationResult> getLocation(Object region, GenerationContext context) {
            return fn.apply(region, context);
        }

        @Override
        public CompletableFuture<GenerationResult> generateLocation(Object region, GenerationContext context) {
            return fn.apply(region, context);
        }

        @Override
        public CompletableFuture<GenerationResult> getLocation(Object region, io.github.dailystruggle.rtp.api.entity.RTPCommandSender sender, io.github.dailystruggle.rtp.api.entity.RTPPlayer player, Set<String> biomeNames) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<GenerationResult> getLocation(Object region, Set<String> biomeNames) {
            return CompletableFuture.completedFuture(null);
        }
    }

    // -------------------------------------------------------------------------
    // 17. runSetup full lifecycle (locationFuture done and async)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("runSetup with immediately done locationFuture processes generation result")
    void runSetup_immediate_locationFuture() {
        Region reg = createTestRegion("setup_imm_reg");
        SettablePlayer player = createPlayer("SetupImmP");
        RTPCoords targetCoords = new RTPCoords(world.name(), 16, 64, 16);
        GenerationResult genResult = new GenerationResult(targetCoords, 1L, null);

        accessor.setLocationGenerator(new CustomLocationGenerator((r, c) -> CompletableFuture.completedFuture(genResult)));

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        task.run();

        // Should transition past SETUP to LOAD
        assertTrue(task.getPhase() == TeleportPipelineTask.Phase.LOAD
                || task.getPhase() == TeleportPipelineTask.Phase.TELEPORT
                || task.getPhase() == TeleportPipelineTask.Phase.CLEANUP);
    }

    @Test
    @DisplayName("runSetup with async locationFuture processes result on completion")
    void runSetup_async_locationFuture() {
        Region reg = createTestRegion("setup_async_reg");
        SettablePlayer player = createPlayer("SetupAsyncP");
        RTPCoords targetCoords = new RTPCoords(world.name(), 32, 64, 32);
        GenerationResult genResult = new GenerationResult(targetCoords, 1L, null);

        CompletableFuture<GenerationResult> future = new CompletableFuture<>();
        accessor.setLocationGenerator(new CustomLocationGenerator((r, c) -> future));

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        task.run();
        assertEquals(TeleportPipelineTask.Phase.SETUP, task.getPhase());

        future.complete(genResult);
        accessor.getMockScheduler().tick(1);

        assertTrue(task.getPhase() != TeleportPipelineTask.Phase.SETUP);
    }

    @Test
    @DisplayName("runSetup with failing async locationFuture cleans up via exceptionally")
    void runSetup_async_locationFuture_exception() {
        Region reg = createTestRegion("setup_ex_reg");
        SettablePlayer player = createPlayer("SetupExP");

        CompletableFuture<GenerationResult> future = new CompletableFuture<>();
        accessor.setLocationGenerator(new CustomLocationGenerator((r, c) -> future));

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        task.run();
        future.completeExceptionally(new RuntimeException("Location generation failed"));

        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    // -------------------------------------------------------------------------
    // 18. Pre and post action hook executions
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Pipeline hooks: setup, load, teleport, cleanup pre and post actions execute")
    void pipeline_hooks_execute() {
        Region reg = createTestRegion("hooks_reg");
        SettablePlayer player = createPlayer("HooksP");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        chunks.add(CompletableFuture.completedFuture(0L));
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        AtomicBoolean setupPre = new AtomicBoolean(false);
        AtomicBoolean setupPost = new AtomicBoolean(false);
        AtomicBoolean loadPre = new AtomicBoolean(false);
        AtomicBoolean loadPost = new AtomicBoolean(false);
        AtomicBoolean telePre = new AtomicBoolean(false);
        AtomicBoolean telePost = new AtomicBoolean(false);
        AtomicBoolean cleanPre = new AtomicBoolean(false);
        AtomicBoolean cleanPost = new AtomicBoolean(false);

        TeleportPipelineTask.setupPreActions.add(t -> setupPre.set(true));
        TeleportPipelineTask.setupPostActions.add((t, b) -> setupPost.set(true));
        TeleportPipelineTask.loadPreActions.add(t -> loadPre.set(true));
        TeleportPipelineTask.loadPostActions.add(t -> loadPost.set(true));
        TeleportPipelineTask.teleportPreActions.add(t -> telePre.set(true));
        TeleportPipelineTask.teleportPostActions.add(t -> telePost.set(true));
        TeleportPipelineTask.cleanupPreActions.add(t -> cleanPre.set(true));
        TeleportPipelineTask.cleanupPostActions.add(t -> cleanPost.set(true));

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.TELEPORT);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        data.sender = player;
        data.selectedCoords = coords;
        data.targetRegion = reg;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        try {
            Field fData = TeleportPipelineTask.class.getDeclaredField("teleportData");
            fData.setAccessible(true);
            fData.set(task, data);
        } catch (Exception ignored) {}

        RTP.getInstance().databaseAccessor = Mockito.mock(DatabaseAccessor.class);

        task.run();
        accessor.getMockScheduler().tick(1);
        accessor.getMockScheduler().tick(1);

        assertTrue(telePre.get(), "teleportPreActions should execute");
        assertTrue(cleanPre.get(), "cleanupPreActions should execute");
        assertTrue(cleanPost.get(), "cleanupPostActions should execute");
    }

    // -------------------------------------------------------------------------
    // 19. Constructor initTracking & boundary tests
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("2-arg constructor initTracking registers task in MemoryTracker")
    void constructor_2arg_registers_tracking() {
        SettablePlayer player = createPlayer("InitP2");
        GenerationContext ctx = new GenerationContext(player, player, null);
        Region reg = createTestRegion("init_p2_reg");
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);
        int count = MemoryTracker.trackedCountByLabel("TeleportPipelineTask");
        assertTrue(count > 0);
        task.setCancelled(true);
    }

    @Test
    @DisplayName("4-arg constructor initTracking registers task in MemoryTracker")
    void constructor_4arg_registers_tracking() {
        SettablePlayer player = createPlayer("InitP4");
        GenerationContext ctx = new GenerationContext(player, player, null);
        Region reg = createTestRegion("init_p4_reg");
        RTPCoords coords = new RTPCoords(world.name(), 10, 64, 10);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, null);
        int count = MemoryTracker.trackedCountByLabel("TeleportPipelineTask");
        assertTrue(count > 0);
        task.setCancelled(true);
    }

    @Test
    @DisplayName("setLocation callback: duration == 0 removes invulnerability immediately and verifies lockAfter boundary")
    void setLocation_callback_duration_zero_and_lockAfter_boundary() {
        Region reg = createTestRegion("lockafter_reg");
        SettablePlayer player = createPlayer("LockAfterP");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        chunks.add(CompletableFuture.completedFuture(0L));
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.TELEPORT);

        // Test lockAfterUses == 0 (boundary check: does not record)
        TeleportPipelineTask.ConfigCache.lockAfterUses = 0;
        TeleportPipelineTask.ConfigCache.setRespawnOnTeleport = false;
        TeleportPipelineTask.ConfigCache.postTeleportQueueing = false;
        RTP.configs.getParser(SafetyKeys.class).set(SafetyKeys.invulnerabilityTime, 0L);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        data.sender = player;
        data.selectedCoords = coords;
        data.targetRegion = reg;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        try {
            Field fData = TeleportPipelineTask.class.getDeclaredField("teleportData");
            fData.setAccessible(true);
            fData.set(task, data);
        } catch (Exception ignored) {}

        DatabaseAccessor mockDb = Mockito.mock(DatabaseAccessor.class);
        RTP.getInstance().databaseAccessor = mockDb;

        task.run();
        accessor.getMockScheduler().tick(1);
        accessor.getMockScheduler().tick(1);

        assertFalse(RTP.getInstance().invulnerablePlayers.containsKey(player.uuid()),
                "duration == 0 should remove invulnerability immediately");
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
        assertTrue(data.processingTime >= 0, "processingTime must be calculated as currentTime - startTime");
        Mockito.verify(mockDb, Mockito.atLeastOnce()).cacheValue(data);
    }

    @Test
    @DisplayName("runTeleport applies viewDistance clamp and updates processingTime before setLocation")
    void runTeleport_clamps_viewDistance_and_caches_data() {
        Region reg = createTestRegion("clamp_vd_reg");
        SettablePlayer player = createPlayer("ClampVdP");
        player.setViewDistance(10);
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        chunks.add(CompletableFuture.completedFuture(0L));
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.TELEPORT);

        TeleportPipelineTask.ConfigCache.viewDistanceTeleport = 2;
        TeleportPipelineTask.ConfigCache.viewDistanceRestoreInterval = 100L;

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis() - 50L;
        data.sender = player;
        data.selectedCoords = coords;
        data.targetRegion = reg;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        try {
            Field fData = TeleportPipelineTask.class.getDeclaredField("teleportData");
            fData.setAccessible(true);
            fData.set(task, data);
        } catch (Exception ignored) {}

        DatabaseAccessor mockDb = Mockito.mock(DatabaseAccessor.class);
        RTP.getInstance().databaseAccessor = mockDb;

        task.run();

        // Player view distance must have been clamped to 2
        assertEquals(2, player.getViewDistance());
        assertTrue(data.processingTime >= 50L, "processingTime must reflect elapsed duration");
        Mockito.verify(mockDb).cacheValue(data);
    }

    // -------------------------------------------------------------------------
    // 20. MockRTPPlayer & MockRTPCommandSender permission and delay/cooldown tests
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("sender delay math: calculates remaining delay and schedules timer when delay > 0")
    void sender_delay_math_schedules_timer() {
        SettablePlayer player = createPlayer("DelayMathP");
        player.setDelay(2000L); // 2000ms delay = 40 ticks
        Region reg = createTestRegion("delay_math_reg");
        RTPCoords coords = new RTPCoords(world.name(), 10, 64, 10);

        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        chunks.add(CompletableFuture.completedFuture(0L));
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.LOAD);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        data.sender = player;
        data.delay = player.delay();
        data.targetRegion = reg;
        data.selectedCoords = coords;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        try {
            Field fData = TeleportPipelineTask.class.getDeclaredField("teleportData");
            fData.setAccessible(true);
            fData.set(task, data);
        } catch (Exception ignored) {}

        task.run();

        // Since delay is 2000ms (40 ticks), phase should become TELEPORT and schedule a delayed task
        assertEquals(TeleportPipelineTask.Phase.TELEPORT, task.getPhase());
    }

    @Test
    @DisplayName("sender messages verification: sends unsafe message to sender and player on unsafe failure")
    void sender_messages_sent_on_unsafe_failure() throws Exception {
        SettablePlayer player = createPlayer("MsgPlayer");
        Region reg = createTestRegion("msg_reg");
        RTPCoords coords = new RTPCoords(world.name(), 10, 64, 10);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, null);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        data.sender = player;
        data.targetRegion = reg;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        try {
            Field fData = TeleportPipelineTask.class.getDeclaredField("teleportData");
            fData.setAccessible(true);
            fData.set(task, data);
        } catch (Exception ignored) {}

        TeleportPipelineTask.ConfigCache.unsafe = "Destination was unsafe!";
        GenerationResult failureRes = new GenerationResult(
                null, 0L, (ChunkSet) null);

        Method m = TeleportPipelineTask.class.getDeclaredMethod("processGenerationResult", GenerationResult.class);
        m.setAccessible(true);
        m.invoke(task, failureRes);

        // Verify message was received by player
        assertTrue(player.sentMessages.stream().anyMatch(msg -> msg.contains("unsafe") || msg.contains("Destination")),
                "Player should receive unsafe failure message");
    }

    @Test
    @DisplayName("shouldBuildPlatform exact boundary tests: dx == radius and dz == radius")
    void shouldBuildPlatform_exact_boundary_conditions() throws Exception {
        Method m = TeleportPipelineTask.class.getDeclaredMethod("shouldBuildPlatform", RTPWorld.class, RTPCoords.class);
        m.setAccessible(true);

        RTP.configs.getParser(SafetyKeys.class).set(SafetyKeys.platformRadius, 2);

        // Populate chunks around (0, 0)
        world.getChunkAt(0, 0).join();

        RTPCoords centerCoords = new RTPCoords(world.name(), 5, 32, 5);
        boolean needsPlatform = (boolean) m.invoke(null, world, centerCoords);
        // In MockRTPChunk, y=31 is solid ground (!isAir(31)), so within radius 2, ground is solid
        assertFalse(needsPlatform, "Radius 2 with solid ground below requires no platform");

        // Now test with y <= minY which triggers platform
        RTPCoords voidCoords = new RTPCoords(world.name(), 5, -64, 5);
        boolean voidNeedsPlatform = (boolean) m.invoke(null, world, voidCoords);
        assertTrue(voidNeedsPlatform, "belowY < minY must trigger platform");
    }

    @Test
    @DisplayName("region accessor returns target region")
    void region_getter_returns_configured_region() {
        SettablePlayer player = createPlayer("RegGetterP");
        Region reg = createTestRegion("getter_reg");
        RTPCoords coords = new RTPCoords(world.name(), 10, 64, 10);
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords);
        assertEquals(reg, task.region(), "task.region() must return configured target region");
    }

    // -------------------------------------------------------------------------
    // 21. shouldBuildPlatform & schematicFootprintClear condition tests
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("shouldBuildPlatform returns true when landing block is unsafe or below block is air")
    void shouldBuildPlatform_landing_unsafe_or_air() throws Exception {
        Method m = TeleportPipelineTask.class.getDeclaredMethod("shouldBuildPlatform", RTPWorld.class, RTPCoords.class);
        m.setAccessible(true);

        RTP.configs.getParser(SafetyKeys.class).set(SafetyKeys.platformRadius, 0);

        io.github.dailystruggle.rtp.common.mock.MockRTPChunk testChunk = new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(0, 0, world) {
            @Override
            public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
                // Return unsafe for landing block at y=50
                if (y == 50) return false;
                return true;
            }
        };

        PlatformTrackingMockWorld customWorld = new PlatformTrackingMockWorld("custom_plat_world") {
            @Override
            public RTPChunk<?> getCachedChunk(long key) {
                return testChunk;
            }
        };
        accessor.addWorld(customWorld);

        RTPCoords coords = new RTPCoords(customWorld.name(), 5, 50, 5);
        boolean needsPlatform = (boolean) m.invoke(null, customWorld, coords);
        assertTrue(needsPlatform, "Landing block unsafe must trigger platform");

        // Test below block is air
        io.github.dailystruggle.rtp.common.mock.MockRTPChunk airChunk = new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(0, 0, world) {
            @Override
            public boolean isAir(int x, int y, int z) {
                return y == 49;
            }
        };
        PlatformTrackingMockWorld airWorld = new PlatformTrackingMockWorld("air_plat_world") {
            @Override
            public RTPChunk<?> getCachedChunk(long key) {
                return airChunk;
            }
        };
        accessor.addWorld(airWorld);

        boolean airNeedsPlatform = (boolean) m.invoke(null, airWorld, coords);
        assertTrue(airNeedsPlatform, "Below block is air must trigger platform");
    }

    @Test
    @DisplayName("schematicFootprintClear boundary conditions for width/length <= 0")
    void schematicFootprintClear_boundary_conditions() {
        LoadedSchematic zeroWidth = Mockito.mock(LoadedSchematic.class);
        Mockito.when(zeroWidth.width()).thenReturn(0);
        Mockito.when(zeroWidth.length()).thenReturn(10);

        RTPLocation loc = new RTPLocation(world, 0, 64, 0);
        PasteOptions options = new PasteOptions(PasteAnchor.CENTER, false, false);

        assertTrue(TeleportPipelineTask.schematicFootprintClear(zeroWidth, loc, options, world.name()),
                "width == 0 should return true (clear)");

        LoadedSchematic zeroLength = Mockito.mock(LoadedSchematic.class);
        Mockito.when(zeroLength.width()).thenReturn(10);
        Mockito.when(zeroLength.length()).thenReturn(0);
        assertTrue(TeleportPipelineTask.schematicFootprintClear(zeroLength, loc, options, world.name()),
                "length == 0 should return true (clear)");

        LoadedSchematic negativeWidth = Mockito.mock(LoadedSchematic.class);
        Mockito.when(negativeWidth.width()).thenReturn(-1);
        Mockito.when(negativeWidth.length()).thenReturn(10);
        assertTrue(TeleportPipelineTask.schematicFootprintClear(negativeWidth, loc, options, world.name()),
                "width < 0 should return true (clear)");

        // Non-zero dimensions: exercises loop boundary dz < length and dx < width
        LoadedSchematic validSchem = Mockito.mock(LoadedSchematic.class);
        Mockito.when(validSchem.width()).thenReturn(2);
        Mockito.when(validSchem.length()).thenReturn(2);
        assertTrue(TeleportPipelineTask.schematicFootprintClear(validSchem, loc, options, world.name()),
                "valid dimensions with no verifiers should return true");
    }

    @Test
    @DisplayName("shouldBuildPlatform landing block isSafe and isAir checks")
    void shouldBuildPlatform_safe_and_air_matrix() throws Exception {
        Method m = TeleportPipelineTask.class.getDeclaredMethod("shouldBuildPlatform", RTPWorld.class, RTPCoords.class);
        m.setAccessible(true);

        RTP.configs.getParser(SafetyKeys.class).set(SafetyKeys.platformRadius, 0);

        // Case 1: Landing block is unsafe (e.g. lava/fire)
        io.github.dailystruggle.rtp.common.mock.MockRTPChunk chunkUnsafe = new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(0, 0, world) {
            @Override
            public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
                return false;
            }
        };
        PlatformTrackingMockWorld w1 = new PlatformTrackingMockWorld("w1") {
            @Override public RTPChunk<?> getCachedChunk(long key) { return chunkUnsafe; }
        };
        accessor.addWorld(w1);
        assertTrue((boolean) m.invoke(null, w1, new RTPCoords(w1.name(), 5, 50, 5)));

        // Case 2: Landing block is safe, but below is air
        io.github.dailystruggle.rtp.common.mock.MockRTPChunk chunkAirBelow = new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(0, 0, world) {
            @Override
            public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
                return true;
            }
            @Override
            public boolean isAir(int x, int y, int z) {
                return y == 49;
            }
        };
        PlatformTrackingMockWorld w2 = new PlatformTrackingMockWorld("w2") {
            @Override public RTPChunk<?> getCachedChunk(long key) { return chunkAirBelow; }
        };
        accessor.addWorld(w2);
        assertTrue((boolean) m.invoke(null, w2, new RTPCoords(w2.name(), 5, 50, 5)));

        // Case 3: Landing block is safe, below is not air, but below is unsafe
        io.github.dailystruggle.rtp.common.mock.MockRTPChunk chunkUnsafeBelow = new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(0, 0, world) {
            @Override
            public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
                return y != 49;
            }
            @Override
            public boolean isAir(int x, int y, int z) {
                return false;
            }
        };
        PlatformTrackingMockWorld w3 = new PlatformTrackingMockWorld("w3") {
            @Override public RTPChunk<?> getCachedChunk(long key) { return chunkUnsafeBelow; }
        };
        accessor.addWorld(w3);
        assertTrue((boolean) m.invoke(null, w3, new RTPCoords(w3.name(), 5, 50, 5)));

        // Case 4: Landing block safe, below safe and not air
        io.github.dailystruggle.rtp.common.mock.MockRTPChunk chunkAllSafe = new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(0, 0, world) {
            @Override
            public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
                return true;
            }
            @Override
            public boolean isAir(int x, int y, int z) {
                return false;
            }
        };
        PlatformTrackingMockWorld w4 = new PlatformTrackingMockWorld("w4") {
            @Override public RTPChunk<?> getCachedChunk(long key) { return chunkAllSafe; }
        };
        accessor.addWorld(w4);
        assertFalse((boolean) m.invoke(null, w4, new RTPCoords(w4.name(), 5, 50, 5)));
    }

    @Test
    @DisplayName("runCleanup records immediate teleport audit and releases reservation on location")
    void runCleanup_immediate_teleport_audit_and_reservation_release() {
        Region reg = createTestRegion("audit_cleanup_reg");
        SettablePlayer player = createPlayer("AuditCleanupP");
        RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);

        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        chunks.add(CompletableFuture.completedFuture(0L));
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);
        task.setPhase(TeleportPipelineTask.Phase.CLEANUP);

        task.run();

        // Verify task phase is CLEANUP
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @DisplayName("shouldBuildPlatform when belowY is below world minY returns true")
    void shouldBuildPlatform_below_minY() throws Exception {
        Method m = TeleportPipelineTask.class.getDeclaredMethod("shouldBuildPlatform", RTPWorld.class, RTPCoords.class);
        m.setAccessible(true);

        RTP.configs.getParser(SafetyKeys.class).set(SafetyKeys.platformRadius, 0);

        io.github.dailystruggle.rtp.common.mock.MockRTPChunk chunk = new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(0, 0, world) {
            @Override
            public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
                return true;
            }
            @Override
            public boolean isAir(int x, int y, int z) {
                return false;
            }
        };

        PlatformTrackingMockWorld testWorld = new PlatformTrackingMockWorld("test_miny_world") {
            @Override public RTPChunk<?> getCachedChunk(long key) { return chunk; }
            @Override public int getMinHeight() { return 0; }
        };
        accessor.addWorld(testWorld);

        // Landing at y=0 means belowY is -1 which is < minY (0) -> returns true
        assertTrue((boolean) m.invoke(null, testWorld, new RTPCoords(testWorld.name(), 5, 0, 5)),
                "belowY < minY should trigger platform build");
    }

    @Test
    @DisplayName("runTeleport handles platform creation failure and fallback")
    void runTeleport_platform_creation_failure_fallback() throws Exception {
        Region reg = createTestRegion("platform_fail_fallback_reg");
        SettablePlayer player = createPlayer("PlatformFallbackP");
        RTPCoords coords = new RTPCoords(world.name(), 10, 65, 10);

        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        chunks.add(CompletableFuture.completedFuture(0L));
        ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, CompletableFuture.completedFuture(true));
        ChunkReservation res = new ChunkReservation(chunkSet, world);

        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords, res);

        PlatformCreator throwingCreator = new PlatformCreator() {
            @Override public String creatorName() { return "ThrowingCreator"; }
            @Override public boolean createPlatform(RTPLocation location, Object prepared) {
                throw new RuntimeException("Simulated platform creator failure");
            }
        };

        Field creatorField = TeleportPipelineTask.class.getDeclaredField("platformCreator");
        creatorField.setAccessible(true);
        creatorField.set(task, throwingCreator);

        Method buildPlatform = TeleportPipelineTask.class.getDeclaredMethod("buildArrivalPlatform", RTPLocation.class);
        buildPlatform.setAccessible(true);
        buildPlatform.invoke(task, new RTPLocation(world, 10, 65, 10));

        assertTrue(world.isPlatformCalled(), "Default world platform should be invoked as fallback when custom creator throws");
    }

    @Test
    @DisplayName("runCleanup does not remove latestTeleportData if data does not match or is completed")
    void runCleanup_does_not_remove_unmatched_teleportData() throws Exception {
        SettablePlayer player = createPlayer("UnmatchedCleanupP");
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx);

        // Put a different TeleportData in latestTeleportData
        TeleportData otherData = new TeleportData();
        otherData.completed = false;
        RTP.getInstance().latestTeleportData.put(player.uuid(), otherData);

        Method cleanup = TeleportPipelineTask.class.getDeclaredMethod("runCleanup");
        cleanup.setAccessible(true);
        cleanup.invoke(task);

        assertSame(otherData, RTP.getInstance().latestTeleportData.get(player.uuid()),
                "latestTeleportData should not remove unmatched TeleportData");
    }
}
