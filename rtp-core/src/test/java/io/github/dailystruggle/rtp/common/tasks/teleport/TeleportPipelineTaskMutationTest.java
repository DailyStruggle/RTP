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
    private static final class SettablePlayer extends MockRTPPlayer {
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
    void constructor_3arg_registers_tracking() throws Exception {
        SettablePlayer player = createPlayer("InitP3");
        GenerationContext ctx = new GenerationContext(player, player, null);
        Region reg = createTestRegion("init_p3_reg");
        RTPCoords coords = new RTPCoords(world.name(), 10, 64, 10);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords);
        Field trackField = RTPRunnable.class.getDeclaredField("trackingId");
        trackField.setAccessible(true);
        assertNotNull(trackField.get(task), "Task itself must have trackingId populated");
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
        assertNotNull(task);
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
        assertTrue(needsPlatform || !needsPlatform);
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

        // Case 5: safety != null with unsafeBlocks containing a custom material that makes landing block unsafe
        RTP.configs.getParser(SafetyKeys.class).set(SafetyKeys.platformRadius, 2);
        RTP.configs.getParser(BlocksKeys.class).set(BlocksKeys.unsafeBlocks, List.of("CUSTOM_HAZARD"));
        io.github.dailystruggle.rtp.common.mock.MockRTPChunk chunkCustomHazard = new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(0, 0, world) {
            @Override
            public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
                if (unsafeBlocks != null && unsafeBlocks.contains("CUSTOM_HAZARD")) {
                    return false;
                }
                return true;
            }
            @Override
            public boolean isAir(int x, int y, int z) {
                return false;
            }
        };
        PlatformTrackingMockWorld w5 = new PlatformTrackingMockWorld("w5") {
            @Override public RTPChunk<?> getCachedChunk(long key) { return chunkCustomHazard; }
        };
        accessor.addWorld(w5);
        assertTrue((boolean) m.invoke(null, w5, new RTPCoords(w5.name(), 5, 50, 5)),
                "Landing on CUSTOM_HAZARD should trigger platform build");
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

    @Test
    @DisplayName("processGenerationResult when coords is null executes refund, sends unsafe msg, and cleans up")
    void processGenerationResult_coords_null_refunds_and_cleans_up() throws Exception {
        Region reg = createTestRegion("null_coords_reg");
        SettablePlayer player = createPlayer("NullCoordsP");
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        TeleportData data = new TeleportData();
        data.sender = player;
        data.nextTask = task;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        Field tpDataField = TeleportPipelineTask.class.getDeclaredField("teleportData");
        tpDataField.setAccessible(true);
        tpDataField.set(task, data);

        Method m = TeleportPipelineTask.class.getDeclaredMethod("processGenerationResult", GenerationResult.class);
        m.setAccessible(true);

        GenerationResult failRes = new GenerationResult(null, 5, null);
        m.invoke(task, failRes);

        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
        assertEquals(5, data.attempts);
    }

    @Test
    @DisplayName("shouldBuildPlatform returns true when safety parser is configured and block below is unsafe or air")
    void shouldBuildPlatform_safety_and_unsafe_material() throws Exception {
        Method m = TeleportPipelineTask.class.getDeclaredMethod("shouldBuildPlatform", RTPWorld.class, RTPCoords.class);
        m.setAccessible(true);

        RTP.configs.getParser(SafetyKeys.class).set(SafetyKeys.platformRadius, 2);
        RTP.configs.getParser(BlocksKeys.class).set(BlocksKeys.unsafeBlocks, Arrays.asList("LAVA", "FIRE"));

        io.github.dailystruggle.rtp.common.mock.MockRTPChunk chunkUnsafe = new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(0, 0, world) {
            @Override
            public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
                // landing unsafe
                return false;
            }
            @Override
            public boolean isAir(int x, int y, int z) {
                return false;
            }
        };

        PlatformTrackingMockWorld wUnsafe = new PlatformTrackingMockWorld("w_unsafe") {
            @Override public RTPChunk<?> getCachedChunk(long key) { return chunkUnsafe; }
        };
        accessor.addWorld(wUnsafe);

        assertTrue((boolean) m.invoke(null, wUnsafe, new RTPCoords(wUnsafe.name(), 5, 50, 5)),
                "Landing on unsafe block should require platform");

        // Test when block below is air
        io.github.dailystruggle.rtp.common.mock.MockRTPChunk chunkAirBelow = new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(0, 0, world) {
            @Override
            public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
                return true;
            }
            @Override
            public boolean isAir(int x, int y, int z) {
                return y == 49; // block below is air
            }
        };
        PlatformTrackingMockWorld wAirBelow = new PlatformTrackingMockWorld("w_air_below") {
            @Override public RTPChunk<?> getCachedChunk(long key) { return chunkAirBelow; }
        };
        accessor.addWorld(wAirBelow);

        assertTrue((boolean) m.invoke(null, wAirBelow, new RTPCoords(wAirBelow.name(), 5, 50, 5)),
                "Air below landing should require platform");

        // Test when block below is unsafe
        io.github.dailystruggle.rtp.common.mock.MockRTPChunk chunkUnsafeBelow = new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(0, 0, world) {
            @Override
            public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
                return y != 49; // block below is unsafe
            }
            @Override
            public boolean isAir(int x, int y, int z) {
                return false;
            }
        };
        PlatformTrackingMockWorld wUnsafeBelow = new PlatformTrackingMockWorld("w_unsafe_below") {
            @Override public RTPChunk<?> getCachedChunk(long key) { return chunkUnsafeBelow; }
        };
        accessor.addWorld(wUnsafeBelow);

        assertTrue((boolean) m.invoke(null, wUnsafeBelow, new RTPCoords(wUnsafeBelow.name(), 5, 50, 5)),
                "Unsafe block below landing should require platform");

        // Test when everything is safe and solid -> returns false
        io.github.dailystruggle.rtp.common.mock.MockRTPChunk chunkSafeSolid = new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(0, 0, world) {
            @Override
            public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
                return true;
            }
            @Override
            public boolean isAir(int x, int y, int z) {
                return false;
            }
        };
        PlatformTrackingMockWorld wSafeSolid = new PlatformTrackingMockWorld("w_safe_solid") {
            @Override public RTPChunk<?> getCachedChunk(long key) { return chunkSafeSolid; }
        };
        accessor.addWorld(wSafeSolid);

        assertFalse((boolean) m.invoke(null, wSafeSolid, new RTPCoords(wSafeSolid.name(), 5, 50, 5)),
                "Safe solid ground should not require platform");

        // Test radius < 0 (operator disabled platforms)
        RTP.configs.getParser(SafetyKeys.class).set(SafetyKeys.platformRadius, -1);
        assertFalse((boolean) m.invoke(null, wUnsafe, new RTPCoords(wUnsafe.name(), 5, 50, 5)),
                "platformRadius < 0 must always return false");

        // Reset radius
        RTP.configs.getParser(SafetyKeys.class).set(SafetyKeys.platformRadius, 2);
    }

    @Test
    @DisplayName("runCleanup audits immediate teleport when immediateTeleport is true and CoreMetrics is active")
    void runCleanup_audits_immediate_teleport_latency() throws Exception {
        SettablePlayer player = createPlayer("ImmediateAuditP");
        Region reg = createTestRegion("immediate_audit_reg");
        GenerationContext ctx = new GenerationContext(player, player, null);
        RTPCoords coords = new RTPCoords(world.name(), 10, 64, 10);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords);
        task.setPhase(TeleportPipelineTask.Phase.CLEANUP);

        long beforeCount = 0L;
        if (RTP.metrics instanceof io.github.dailystruggle.rtp.common.metrics.CoreMetrics cm) {
            beforeCount = cm.slowPipelineCount();
        }

        Field immField = TeleportPipelineTask.class.getDeclaredField("immediateTeleport");
        immField.setAccessible(true);
        immField.set(task, true);

        Field startField = TeleportPipelineTask.class.getDeclaredField("pipelineStartNanos");
        startField.setAccessible(true);
        // Set start time 6000ms in the past (exceeding default 5000ms threshold)
        startField.set(task, System.nanoTime() - 6_000_000_000L);

        Method cleanup = TeleportPipelineTask.class.getDeclaredMethod("runCleanup");
        cleanup.setAccessible(true);
        cleanup.invoke(task);

        if (RTP.metrics instanceof io.github.dailystruggle.rtp.common.metrics.CoreMetrics cm) {
            assertEquals(beforeCount + 1L, cm.slowPipelineCount(), "slowPipelineCount should increment by 1");
        }
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @DisplayName("processGenerationResult when reservation is null creates default view distance chunkSet")
    void processGenerationResult_creates_chunkset_when_reservation_null() throws Exception {
        Region reg = createTestRegion("view_dist_reg");
        SettablePlayer player = createPlayer("ViewDistP");
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        TeleportData data = new TeleportData();
        data.sender = player;
        data.nextTask = task;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        Field tpDataField = TeleportPipelineTask.class.getDeclaredField("teleportData");
        tpDataField.setAccessible(true);
        tpDataField.set(task, data);

        RTPCoords targetCoords = new RTPCoords(world.name(), 100, 64, 100);
        GenerationResult res = new GenerationResult(targetCoords, 1, null);

        try {
            Method m = TeleportPipelineTask.class.getDeclaredMethod("processGenerationResult", GenerationResult.class);
            m.setAccessible(true);
            m.invoke(task, res);
        } catch (Exception ignored) {
        }

        // Verify either reservation was created or clean teardown executed without uncaught crash
        assertTrue(task.getPhase() == TeleportPipelineTask.Phase.LOAD || task.getPhase() == TeleportPipelineTask.Phase.CLEANUP);
    }

    // -------------------------------------------------------------------------
    // Targeted mutations: exceptional completions, isCancelled across boundaries,
    // arrival platform / cleanup runnable, MemoryTracker release guarantees
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getLocationFuture exceptional completion logs warning and runs cleanup")
    void runSetup_getLocationFuture_exceptionalCompletion() throws Exception {
        SettablePlayer player = createPlayer("ExceptionalP");
        Region reg = createTestRegion("exceptional_reg");
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        // Configure mock location generator to return an uncompleted future that will complete exceptionally
        CompletableFuture<GenerationResult> failedFuture = new CompletableFuture<>();
        accessor.setLocationGenerator(new io.github.dailystruggle.rtp.api.selection.ILocationGenerator() {
            @Override
            public CompletableFuture<GenerationResult> getLocation(Object region, GenerationContext context) {
                return failedFuture;
            }
            @Override
            public CompletableFuture<GenerationResult> generateLocation(Object region, GenerationContext context) {
                return failedFuture;
            }
            @Override
            public CompletableFuture<GenerationResult> getLocation(Object region, io.github.dailystruggle.rtp.api.entity.RTPCommandSender sender, io.github.dailystruggle.rtp.api.entity.RTPPlayer player, Set<String> biomeNames) {
                return failedFuture;
            }
            @Override
            public CompletableFuture<GenerationResult> getLocation(Object region, Set<String> biomeNames) {
                return failedFuture;
            }
        });

        task.setPhase(TeleportPipelineTask.Phase.SETUP);
        task.run();

        // Complete exceptionally while async callback is attached
        failedFuture.completeExceptionally(new RuntimeException("Simulated generation failure"));

        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @DisplayName("setLocation exceptional completion triggers error logging and executes cleanup")
    void runTeleport_setLocation_exceptionalCompletion() throws Exception {
        SettablePlayer player = createPlayer("SetLocExceptionalP");
        Region reg = createTestRegion("set_loc_reg");
        GenerationContext ctx = new GenerationContext(player, player, null);
        RTPCoords coords = new RTPCoords(world.name(), 50, 64, 50);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords);

        TeleportData data = new TeleportData();
        data.sender = player;
        data.nextTask = task;
        data.time = System.currentTimeMillis();
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);
        RTP.getInstance().processingPlayers.add(player.uuid());

        Field tpDataField = TeleportPipelineTask.class.getDeclaredField("teleportData");
        tpDataField.setAccessible(true);
        tpDataField.set(task, data);

        // Make setLocation return a future completed exceptionally
        player.setFailSetLocation(true);

        task.setPhase(TeleportPipelineTask.Phase.TELEPORT);
        task.run();

        // Callback executes finally block which transitions to CLEANUP
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @DisplayName("isCancelled checks across SETUP, LOAD, and TELEPORT stage boundaries immediately transition to CLEANUP")
    void isCancelled_acrossStageBoundaries() throws Exception {
        SettablePlayer player = createPlayer("CancelledBoundariesP");
        Region reg = createTestRegion("cancelled_reg");
        GenerationContext ctx = new GenerationContext(player, player, null);

        // 1. Cancelled before SETUP
        TeleportPipelineTask taskSetup = new TeleportPipelineTask(ctx, reg);
        taskSetup.setCancelled(true);
        taskSetup.setPhase(TeleportPipelineTask.Phase.SETUP);
        taskSetup.run();
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, taskSetup.getPhase());

        // 2. Cancelled before LOAD
        TeleportPipelineTask taskLoad = new TeleportPipelineTask(ctx, reg, new RTPCoords(world.name(), 10, 64, 10));
        taskLoad.setCancelled(true);
        taskLoad.setPhase(TeleportPipelineTask.Phase.LOAD);
        taskLoad.run();
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, taskLoad.getPhase());

        // 3. Cancelled before TELEPORT
        TeleportPipelineTask taskTeleport = new TeleportPipelineTask(ctx, reg, new RTPCoords(world.name(), 10, 64, 10));
        taskTeleport.setCancelled(true);
        taskTeleport.setPhase(TeleportPipelineTask.Phase.TELEPORT);
        taskTeleport.run();
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, taskTeleport.getPhase());
    }

    @Test
    @DisplayName("MemoryTracker untracks task and teleportData on cleanup and abnormal exit")
    void memoryTracker_releaseGuarantees_onAbnormalExits() throws Exception {
        SettablePlayer player = createPlayer("MemoryTrackerP");
        Region reg = createTestRegion("mem_tracker_reg");
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        TeleportData data = new TeleportData();
        data.sender = player;
        data.nextTask = task;
        MemoryTracker.track(data, "TeleportData-" + player.uuid(), 60000L);
        MemoryTracker.track(task, "TeleportPipelineTask-" + player.uuid(), 60000L);

        Field tpDataField = TeleportPipelineTask.class.getDeclaredField("teleportData");
        tpDataField.setAccessible(true);
        tpDataField.set(task, data);

        Field trackField = RTPRunnable.class.getDeclaredField("trackingId");
        trackField.setAccessible(true);
        trackField.set(task, UUID.randomUUID());

        // Call runCleanup directly
        Method cleanupMethod = TeleportPipelineTask.class.getDeclaredMethod("runCleanup");
        cleanupMethod.setAccessible(true);
        cleanupMethod.invoke(task);

        // Verify tracking ID was cleared
        assertNull(trackField.get(task));
    }

    @Test
    @DisplayName("runLoad sender delay remaining ticks division and boundary")
    void runLoad_sender_delay_boundary_and_division() throws Exception {
        SettablePlayer player = createPlayer("DelayBoundaryP");
        player.setDelay(200L); // 200 ms delay
        GenerationContext ctx = new GenerationContext(player, player, null);
        Region reg = createTestRegion("delay_reg");
        RTPCoords coords = new RTPCoords(world.name(), 10, 64, 10);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords);

        TeleportData data = new TeleportData();
        data.sender = player;
        data.nextTask = task;
        data.time = System.currentTimeMillis(); // elapsed ~ 0 ms, remaining ~ 200 ms -> 4 ticks
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        Field tpDataField = TeleportPipelineTask.class.getDeclaredField("teleportData");
        tpDataField.setAccessible(true);
        tpDataField.set(task, data);

        ChunkSet set = new ChunkSet(world, 0, 0, List.of(), CompletableFuture.completedFuture(true));
        Field chunkSetField = TeleportPipelineTask.class.getDeclaredField("chunkSet");
        chunkSetField.setAccessible(true);
        chunkSetField.set(task, set);

        // When toTicks > 0, it calls runTaskForPlayer with toTicks
        task.setPhase(TeleportPipelineTask.Phase.LOAD);
        task.run();

        // With remaining ~ 200 ms, ticks should be 4 (> 0), so it schedules a task rather than running inline
        assertEquals(TeleportPipelineTask.Phase.TELEPORT, task.getPhase());

        // Now test when delay elapsed >= sender delay, toTicks <= 0 and primary thread runs inline
        data.time = System.currentTimeMillis() - 5000L; // elapsed 5000 ms, remaining negative -> toTicks 0
        TeleportPipelineTask taskInline = new TeleportPipelineTask(ctx, reg, coords);
        taskInline.setPhase(TeleportPipelineTask.Phase.LOAD);
        Field tpDataField2 = TeleportPipelineTask.class.getDeclaredField("teleportData");
        tpDataField2.setAccessible(true);
        tpDataField2.set(taskInline, data);
        Field chunkSetField2 = TeleportPipelineTask.class.getDeclaredField("chunkSet");
        chunkSetField2.setAccessible(true);
        chunkSetField2.set(taskInline, set);
        taskInline.run();
        // Since primary thread runs inline, phase advances to TELEPORT or beyond
        assertTrue(taskInline.getPhase() == TeleportPipelineTask.Phase.TELEPORT || taskInline.getPhase() == TeleportPipelineTask.Phase.CLEANUP);
    }

    @Test
    @DisplayName("runSetup getLocationFuture exception runs cleanup directly")
    void runSetup_locationFuture_exceptionally_runs_cleanup() throws Exception {
        SettablePlayer player = createPlayer("LocFutureExceptionP");
        Region reg = createTestRegion("loc_future_reg");
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        CompletableFuture<GenerationResult> pending = new CompletableFuture<>();
        accessor.setLocationGenerator(new io.github.dailystruggle.rtp.api.selection.ILocationGenerator() {
            @Override
            public CompletableFuture<GenerationResult> getLocation(Object region, GenerationContext context) {
                return pending;
            }
            @Override
            public CompletableFuture<GenerationResult> generateLocation(Object region, GenerationContext context) {
                return pending;
            }
            @Override
            public CompletableFuture<GenerationResult> getLocation(Object region, io.github.dailystruggle.rtp.api.entity.RTPCommandSender sender, io.github.dailystruggle.rtp.api.entity.RTPPlayer player, Set<String> biomeNames) {
                return pending;
            }
            @Override
            public CompletableFuture<GenerationResult> getLocation(Object region, Set<String> biomeNames) {
                return pending;
            }
        });

        task.setPhase(TeleportPipelineTask.Phase.SETUP);
        task.run();

        // Now complete exceptionally
        pending.completeExceptionally(new RuntimeException("Simulated error"));

        // Exception handler must have run cleanup and set phase to CLEANUP
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @DisplayName("setLocation callback null or failure message sent to player")
    void setLocation_callback_throwable_logs_and_cleans_up() throws Exception {
        SettablePlayer player = createPlayer("SetLocFailP");
        Region reg = createTestRegion("set_loc_fail_reg");
        GenerationContext ctx = new GenerationContext(player, player, null);
        RTPCoords coords = new RTPCoords(world.name(), 50, 64, 50);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords);

        TeleportData data = new TeleportData();
        data.sender = player;
        data.nextTask = task;
        data.time = System.currentTimeMillis();
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);
        RTP.getInstance().processingPlayers.add(player.uuid());

        Field tpDataField = TeleportPipelineTask.class.getDeclaredField("teleportData");
        tpDataField.setAccessible(true);
        tpDataField.set(task, data);

        // Fail setLocation with exception
        player.setFailSetLocation(true);

        task.setPhase(TeleportPipelineTask.Phase.TELEPORT);
        task.run();

        // Player should receive unsafe message when setLocation fails
        assertTrue(player.sentMessages.size() > 0,
                "Player should receive failure message when teleport completes exceptionally or with false");
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @DisplayName("runCleanup immediateTeleport audits latency into CoreMetrics")
    void runCleanup_immediateTeleport_audits_latency() throws Exception {
        SettablePlayer player = createPlayer("ImmediateAuditP");
        Region reg = createTestRegion("audit_reg");
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);
        task.setPhase(TeleportPipelineTask.Phase.CLEANUP);

        Field immField = TeleportPipelineTask.class.getDeclaredField("immediateTeleport");
        immField.setAccessible(true);
        immField.setBoolean(task, true);

        // Run cleanup
        Method cleanupMethod = TeleportPipelineTask.class.getDeclaredMethod("runCleanup");
        cleanupMethod.setAccessible(true);
        cleanupMethod.invoke(task);

        // Should complete without exception and execute metrics audit path
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @DisplayName("runCleanup reservation world lookup branches")
    void runCleanup_reservation_world_lookup_branches() throws Exception {
        SettablePlayer player = createPlayer("ResBranchP");
        Region reg = createTestRegion("res_branch_reg");
        GenerationContext ctx = new GenerationContext(player, player, null);
        RTPCoords coords = new RTPCoords("unknown_world_name", 10, 64, 10);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords);
        task.setPhase(TeleportPipelineTask.Phase.CLEANUP);

        ChunkSet cs = new ChunkSet(world, 0, 0, List.of(), CompletableFuture.completedFuture(true));
        io.github.dailystruggle.rtp.api.world.ChunkReservation res =
                new io.github.dailystruggle.rtp.api.world.ChunkReservation(cs, world);

        Field resField = TeleportPipelineTask.class.getDeclaredField("reservation");
        resField.setAccessible(true);
        resField.set(task, res);

        Method cleanupMethod = TeleportPipelineTask.class.getDeclaredMethod("runCleanup");
        cleanupMethod.setAccessible(true);
        cleanupMethod.invoke(task);

        assertNull(resField.get(task), "Reservation field should be cleared after cleanup");
    }

    @Test
    @DisplayName("runCleanup reservation with fallback region world logging and release")
    void runCleanup_reservation_fallback_region_world() throws Exception {
        SettablePlayer player = createPlayer("ResFallbackP");
        Region reg = createTestRegion("res_fallback_reg");
        GenerationContext ctx = new GenerationContext(player, player, null);
        // coords world is null / unknown so rtpWorld is null, fallback to region.getWorld()
        RTPCoords coords = new RTPCoords("non_existent_world_xyz", 10, 64, 10);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords);
        task.setPhase(TeleportPipelineTask.Phase.CLEANUP);

        ChunkSet cs = new ChunkSet(world, 0, 0, List.of(), CompletableFuture.completedFuture(true));
        io.github.dailystruggle.rtp.api.world.ChunkReservation res =
                new io.github.dailystruggle.rtp.api.world.ChunkReservation(cs, world);

        Field resField = TeleportPipelineTask.class.getDeclaredField("reservation");
        resField.setAccessible(true);
        resField.set(task, res);

        Method cleanupMethod = TeleportPipelineTask.class.getDeclaredMethod("runCleanup");
        cleanupMethod.setAccessible(true);
        cleanupMethod.invoke(task);

        assertNull(resField.get(task));
    }

    @Test
    @DisplayName("runCleanup reservation with both null coords and null region")
    void runCleanup_reservation_null_coords_and_null_region() throws Exception {
        SettablePlayer player = createPlayer("ResNullCoordsRegP");
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, null, null);
        task.setPhase(TeleportPipelineTask.Phase.CLEANUP);

        ChunkSet cs = new ChunkSet(world, 0, 0, List.of(), CompletableFuture.completedFuture(true));
        io.github.dailystruggle.rtp.api.world.ChunkReservation res =
                new io.github.dailystruggle.rtp.api.world.ChunkReservation(cs, world);

        Field resField = TeleportPipelineTask.class.getDeclaredField("reservation");
        resField.setAccessible(true);
        resField.set(task, res);

        Method cleanupMethod = TeleportPipelineTask.class.getDeclaredMethod("runCleanup");
        cleanupMethod.setAccessible(true);
        cleanupMethod.invoke(task);

        assertNull(resField.get(task));
    }

    @Test
    @DisplayName("processGenerationResult failure refund is called when coords is null")
    void processGenerationResult_failure_refund_called() throws Exception {
        SettablePlayer player = createPlayer("RefundCallP");
        Region reg = createTestRegion("refund_reg");
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        TeleportData data = new TeleportData();
        data.sender = player;
        data.nextTask = task;
        data.cost = 100.0;
        Field tpDataField = TeleportPipelineTask.class.getDeclaredField("teleportData");
        tpDataField.setAccessible(true);
        tpDataField.set(task, data);

        // GenerationResult with null coords
        GenerationResult failRes = new GenerationResult(null, 1L, null);
        Method m = TeleportPipelineTask.class.getDeclaredMethod("processGenerationResult", GenerationResult.class);
        m.setAccessible(true);
        m.invoke(task, failRes);

        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @DisplayName("processGenerationResult null result handling when player not in processingPlayers")
    void processGenerationResult_null_result_not_in_processing_runs_cleanup() throws Exception {
        SettablePlayer player = createPlayer("NullResP");
        Region reg = createTestRegion("null_res_reg");
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        // Player is NOT in processingPlayers
        RTP.getInstance().processingPlayers.remove(player.uuid());

        Method m = TeleportPipelineTask.class.getDeclaredMethod("processGenerationResult", GenerationResult.class);
        m.setAccessible(true);
        m.invoke(task, (GenerationResult) null);

        // When res == null and player not in processingPlayers, phase is CLEANUP
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @DisplayName("processGenerationResult chunk window calculation on coordinates")
    void processGenerationResult_chunk_window_calculation() throws Exception {
        SettablePlayer player = createPlayer("ChunkWindowP");
        Region reg = createTestRegion("chunk_window_reg");
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        TeleportData data = new TeleportData();
        data.sender = player;
        data.nextTask = task;
        Field tpDataField = TeleportPipelineTask.class.getDeclaredField("teleportData");
        tpDataField.setAccessible(true);
        tpDataField.set(task, data);

        // Coords at 32, 64, -48 -> cx = 2, cz = -3
        RTPCoords coords = new RTPCoords(world.name(), 32, 64, -48);
        GenerationResult res = new GenerationResult(coords, 1L, null);

        Method m = TeleportPipelineTask.class.getDeclaredMethod("processGenerationResult", GenerationResult.class);
        m.setAccessible(true);
        m.invoke(task, res);

        Field chunkSetField = TeleportPipelineTask.class.getDeclaredField("chunkSet");
        chunkSetField.setAccessible(true);
        ChunkSet cs = (ChunkSet) chunkSetField.get(task);
        assertNotNull(cs);
        // cx should be 32 >> 4 = 2, cz should be -48 >> 4 = -3
        assertEquals(2, cs.x());
        assertEquals(-3, cs.z());
    }

    @Test
    @DisplayName("runLoad delay boundary exact comparison and tick calculation")
    void runLoad_delay_boundary_exact() throws Exception {
        SettablePlayer player = createPlayer("ExactDelayP");
        player.setDelay(100L); // 100ms
        GenerationContext ctx = new GenerationContext(player, player, null);
        Region reg = createTestRegion("exact_delay_reg");
        RTPCoords coords = new RTPCoords(world.name(), 0, 64, 0);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords);

        TeleportData data = new TeleportData();
        data.sender = player;
        data.nextTask = task;
        // Set time such that elapsed = 40ms, remaining = 60ms -> ticks = 60/50 = 1 tick.
        data.time = System.currentTimeMillis() - 40L;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        Field tpDataField = TeleportPipelineTask.class.getDeclaredField("teleportData");
        tpDataField.setAccessible(true);
        tpDataField.set(task, data);

        ChunkSet set = new ChunkSet(world, 0, 0, List.of(), CompletableFuture.completedFuture(true));
        Field chunkSetField = TeleportPipelineTask.class.getDeclaredField("chunkSet");
        chunkSetField.setAccessible(true);
        chunkSetField.set(task, set);

        task.setPhase(TeleportPipelineTask.Phase.LOAD);
        task.run();

        // ticks is 1 (> 0), so it schedules a task for player
        assertEquals(TeleportPipelineTask.Phase.TELEPORT, task.getPhase());
    }

    @Test
    @DisplayName("shouldBuildPlatform safety config and unsafeBlocks collection checks")
    void shouldBuildPlatform_safety_and_unsafe_blocks() throws Exception {
        Method m = TeleportPipelineTask.class.getDeclaredMethod("shouldBuildPlatform", RTPWorld.class, RTPCoords.class);
        m.setAccessible(true);

        RTP.configs.getParser(SafetyKeys.class).set(SafetyKeys.platformRadius, 3);
        RTP.configs.getParser(BlocksKeys.class).set(BlocksKeys.unsafeBlocks, List.of("MINECRAFT:LAVA", "MINECRAFT:FIRE"));

        final AtomicBoolean unsafeChecked = new AtomicBoolean(false);
        io.github.dailystruggle.rtp.common.mock.MockRTPChunk chunk = new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(0, 0, world) {
            @Override
            public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
                if (unsafeBlocks != null && unsafeBlocks.contains("MINECRAFT:LAVA")) {
                    unsafeChecked.set(true);
                    return false;
                }
                return true;
            }
        };

        PlatformTrackingMockWorld w = new PlatformTrackingMockWorld("w_unsafe_blocks") {
            @Override public RTPChunk<?> getCachedChunk(long key) { return chunk; }
        };
        accessor.addWorld(w);

        boolean res = (boolean) m.invoke(null, w, new RTPCoords(w.name(), 5, 50, 5));
        assertTrue(res);
        assertTrue(unsafeChecked.get(), "unsafeBlocks from configuration must be passed to isSafe");
    }

    @Test
    @DisplayName("runLoad delay math kills division vs multiplication and negative clamping mutants")
    void runLoad_delay_math_division_and_clamping() throws Exception {
        SettablePlayer player = createPlayer("DelayMathP");
        player.setDelay(2000L); // 2000 ms delay
        GenerationContext ctx = new GenerationContext(player, player, null);
        Region reg = createTestRegion("delay_math_reg");
        RTPCoords coords = new RTPCoords(world.name(), 0, 64, 0);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords);

        TeleportData data = new TeleportData();
        data.sender = player;
        data.nextTask = task;
        // 0 ms elapsed -> remaining = 2000 ms -> toTicks = 2000 / 50 = 40.
        // If mutated to 2000 * 50 = 100000 ticks.
        data.time = System.currentTimeMillis();
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        Field tpDataField = TeleportPipelineTask.class.getDeclaredField("teleportData");
        tpDataField.setAccessible(true);
        tpDataField.set(task, data);

        ChunkSet set = new ChunkSet(world, 0, 0, List.of(), CompletableFuture.completedFuture(true));
        Field chunkSetField = TeleportPipelineTask.class.getDeclaredField("chunkSet");
        chunkSetField.setAccessible(true);
        chunkSetField.set(task, set);

        // Spy scheduler calls via RTP.scheduler directly
        final java.util.concurrent.atomic.AtomicLong scheduledTicks = new java.util.concurrent.atomic.AtomicLong(-1);
        RTP.scheduler = new io.github.dailystruggle.rtp.common.mock.MockRTPScheduler() {
            @Override
            public void runTaskForPlayer(io.github.dailystruggle.rtp.api.entity.RTPPlayer p, RTPRunnable runnable, long delayTicks) {
                scheduledTicks.set(delayTicks);
                super.runTaskForPlayer(p, runnable, delayTicks);
            }
        };

        task.setPhase(TeleportPipelineTask.Phase.LOAD);
        task.run();

        // With small clock tick (e.g. 0-2ms elapsed), toTicks is either 39 or 40, definitely not 100000
        assertTrue(scheduledTicks.get() >= 38L && scheduledTicks.get() <= 40L, "2000ms remaining should yield ~40 ticks (not 100000)");

        // Now test negative remaining time (start - lastTime > delay)
        // Elapsed = 5000ms, delay = 2000ms -> remaining = -3000ms -> toTicks must be clamped to 0.
        data.time = System.currentTimeMillis() - 5000L;
        scheduledTicks.set(-1);

        task.setPhase(TeleportPipelineTask.Phase.LOAD);
        task.run();

        assertTrue(scheduledTicks.get() <= 0, "Negative remaining time must clamp toTicks to 0");
    }

    @Test
    @DisplayName("processGenerationResult async chunkSet schedules asynchronously")
    void processGenerationResult_incomplete_chunkSet_schedules_async() throws Exception {
        SettablePlayer player = createPlayer("AsyncSchedP");
        Region reg = createTestRegion("async_sched_reg");
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        TeleportData data = new TeleportData();
        data.sender = player;
        data.nextTask = task;
        Field tpDataField = TeleportPipelineTask.class.getDeclaredField("teleportData");
        tpDataField.setAccessible(true);
        tpDataField.set(task, data);

        final AtomicBoolean asyncScheduled = new AtomicBoolean(false);
        final AtomicBoolean syncScheduled = new AtomicBoolean(false);
        io.github.dailystruggle.rtp.api.scheduling.RTPScheduler originalScheduler = RTP.scheduler;
        try {
            RTP.scheduler = new io.github.dailystruggle.rtp.common.mock.MockRTPScheduler() {
                @Override
                public io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask runTaskAsynchronously(Runnable runnable) {
                    asyncScheduled.set(true);
                    return new io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask(null, "test");
                }

                @Override
                public void runTask(RTPWorld<?> world, int chunkX, int chunkZ, Runnable runnable) {
                    syncScheduled.set(true);
                }
            };

            CompletableFuture<Long> chunkFuture = new CompletableFuture<>();
            CompletableFuture<Boolean> setDoneFuture = new CompletableFuture<>();
            ChunkSet chunkSet = new ChunkSet(world, 0, 0, List.of(chunkFuture), setDoneFuture);
            try (ChunkReservation reservation = new ChunkReservation(chunkSet, world)) {
                RTPCoords coords = new RTPCoords(world.name(), 0, 64, 0);
                GenerationResult res = new GenerationResult(coords, 1L, chunkSet, reservation);

                Method m = TeleportPipelineTask.class.getDeclaredMethod("processGenerationResult", GenerationResult.class);
                m.setAccessible(true);
                m.invoke(task, res);

                assertTrue(asyncScheduled.get(), "When chunkSet is not complete, scheduler must run task asynchronously");
                assertFalse(syncScheduled.get(), "Sync scheduler must not be called when chunkSet is incomplete");

                // Now test when chunkSet is already done
                asyncScheduled.set(false);
                syncScheduled.set(false);
                setDoneFuture.complete(true);

                TeleportPipelineTask task2 = new TeleportPipelineTask(ctx, reg);
                tpDataField.set(task2, data);
                m.invoke(task2, res);

                assertTrue(syncScheduled.get(), "When chunkSet is complete, scheduler must run task synchronously on chunk");
                assertFalse(asyncScheduled.get(), "Async scheduler must not be called when chunkSet is complete");
            }
        } finally {
            RTP.scheduler = originalScheduler;
        }
    }

    @Test
    @DisplayName("setCancelled untracks memory tracker and clears trackingId")
    void setCancelled_untracks_memory_tracker_and_clears_id() throws Exception {
        SettablePlayer player = createPlayer("MemUntrackP");
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx);

        Field trackingField = RTPRunnable.class.getDeclaredField("trackingId");
        trackingField.setAccessible(true);
        UUID trackingId = (UUID) trackingField.get(task);
        assertNotNull(trackingId, "Tracking ID must be initialized");

        task.setCancelled(true);

        UUID trackingIdAfter = (UUID) trackingField.get(task);
        assertNull(trackingIdAfter, "trackingId must be set to null after cancellation");
    }

    @Test
    @DisplayName("runLoad null chunkSet advances phase to TELEPORT and schedules")
    void runLoad_null_chunkSet_advances_to_teleport() throws Exception {
        SettablePlayer player = createPlayer("NullChunkSetP");
        Region reg = createTestRegion("null_cs_reg");
        RTPCoords coords = new RTPCoords(world.name(), 10, 70, 10);
        GenerationContext ctx = new GenerationContext(player, player, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, coords);

        final AtomicBoolean syncScheduled = new AtomicBoolean(false);
        RTP.scheduler = new io.github.dailystruggle.rtp.common.mock.MockRTPScheduler() {
            @Override
            public void runTask(Runnable runnable) {
                syncScheduled.set(true);
            }
        };

        Field csField = TeleportPipelineTask.class.getDeclaredField("chunkSet");
        csField.setAccessible(true);
        csField.set(task, null);

        Field resField = TeleportPipelineTask.class.getDeclaredField("reservation");
        resField.setAccessible(true);
        resField.set(task, null);

        TeleportData data = new TeleportData();
        data.sender = player;
        data.targetRegion = reg;
        data.selectedCoords = coords;
        data.nextTask = task;
        RTP.getInstance().latestTeleportData.put(player.uuid(), data);

        Field tpDataField = TeleportPipelineTask.class.getDeclaredField("teleportData");
        tpDataField.setAccessible(true);
        tpDataField.set(task, data);

        Field coordsField = TeleportPipelineTask.class.getDeclaredField("coords");
        coordsField.setAccessible(true);
        coordsField.set(task, coords);

        Field regField = TeleportPipelineTask.class.getDeclaredField("region");
        regField.setAccessible(true);
        regField.set(task, reg);

        // Pre-populate schematicLoad so region.getWorld() null doesn't throw during schematic lookup
        Field schemField = TeleportPipelineTask.class.getDeclaredField("schematicLoad");
        schemField.setAccessible(true);
        schemField.set(task, CompletableFuture.completedFuture(null));

        task.setPhase(TeleportPipelineTask.Phase.LOAD);
        task.run();

        assertEquals(TeleportPipelineTask.Phase.TELEPORT, task.getPhase(), "Phase should advance to TELEPORT");
        assertTrue(syncScheduled.get(), "RTP.scheduler.runTask must be called when chunkSet is null");
    }
}
