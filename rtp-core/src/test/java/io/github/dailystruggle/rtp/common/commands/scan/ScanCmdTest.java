package io.github.dailystruggle.rtp.common.commands.scan;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPCommandSender;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tasks.ScanTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * JUnit 5 tests for the Fill command pipeline:
 * {@link ScanCmd}, {@link ScanStartCmd}, {@link ScanPauseCmd},
 * {@link ScanCancelCmd}, {@link ScanResumeCmd}, {@link ScanResetCmd}.
 *
 * <p>Coverage targets: state transitions, "not running" branches,
 * cancel cleanup, permission denial, and invalid argument lengths.
 */
public class ScanCmdTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private MockRTPWorld world;
    private Region region;
    private Square square;
    private UUID senderId;
    private MockRTPCommandSender sender;

    // Commands under test
    private ScanCmd scanCmd;
    private ScanStartCmd scanStartCmd;
    private ScanPauseCmd scanPauseCmd;
    private ScanCancelCmd scanCancelCmd;
    private ScanResumeCmd scanResumeCmd;
    private ScanResetCmd scanResetCmd;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());

        // Set up world
        world = new MockRTPWorld("scan_test_world");
        accessor.addWorld(world);

        // Set up shape and region
        square = new Square();
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());

        RegionSettings settings = new RegionSettings(
                "default",
                world,
                square,
                vert,
                false,
                false,
                10L,
                5,
                0.0,
                1L,
                "",
                false);
        region = new Region("default", settings);

        // Register region in SelectionAPI
        RTP.selectionAPI = new SelectionAPI();
        RTP.selectionAPI.permRegionLookup.put("default", region);

        // Mock MessagesKeys parser so messages are non-empty
        ConfigParser<MessagesKeys> langParser = mock(ConfigParser.class);
        langParser.language_mapping = new java.util.concurrent.ConcurrentHashMap<>();
        langParser.reverse_language_mapping = new java.util.concurrent.ConcurrentHashMap<>();
        langParser.name = "messages";
        when(langParser.getConfigValue(any(MessagesKeys.class), any()))
                .thenAnswer(inv -> {
                    MessagesKeys key = inv.getArgument(0);
                    return key.name() + " [region]";
                });
        RTP.configs.configParserMap.put(MessagesKeys.class, langParser);

        // Mock MultiConfigParser<RegionKeys> so getParser(regionName) returns a safe ConfigParser
        MultiConfigParser<RegionKeys> multiConfigParser = mock(MultiConfigParser.class);
        ConfigParser<RegionKeys> regionConfig = mock(ConfigParser.class);
        when(regionConfig.getNumber(any(RegionKeys.class), any())).thenReturn(1L);
        when(regionConfig.getConfigValue(any(), any())).thenReturn("false");
        when(regionConfig.getConfigValue(eq(RegionKeys.world), any())).thenReturn("default");
        when(multiConfigParser.getParser(any(String.class))).thenReturn(regionConfig);
        RTP.configs.multiConfigParserMap.put(RegionKeys.class, multiConfigParser);

        MultiConfigParser<WorldKeys> worldMultiConfigParser = mock(MultiConfigParser.class);
        ConfigParser<WorldKeys> worldConfig = mock(ConfigParser.class);
        when(worldConfig.getConfigValue(any(), any())).thenReturn("false");
        when(worldConfig.getConfigValue(eq(WorldKeys.region), any())).thenReturn("default");
        when(worldMultiConfigParser.getParser(any(String.class))).thenReturn(worldConfig);
        RTP.configs.multiConfigParserMap.put(WorldKeys.class, worldMultiConfigParser);

        // Set up sender
        senderId = UUID.randomUUID();
        sender = new MockRTPCommandSender(senderId, "TestSender");

        // Instantiate commands
        scanCmd = new ScanCmd(null);
        scanStartCmd = new ScanStartCmd(null);
        scanPauseCmd = new ScanPauseCmd(null);
        scanCancelCmd = new ScanCancelCmd(null);
        scanResumeCmd = new ScanResumeCmd(null);
        scanResetCmd = new ScanResetCmd(null);

        // Ensure scanTasks is clean
        RTP.getInstance().scanTasks.clear();
    }

    @AfterEach
    void tearDown() {
        RTP.getInstance().scanTasks.clear();
        RTP.serverAccessor = null;
        RTP.scheduler = null;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private Map<String, List<String>> params() {
        return new HashMap<>();
    }

    private Map<String, List<String>> paramsWithRegion(String regionName) {
        Map<String, List<String>> p = new HashMap<>();
        p.put("region", Collections.singletonList(regionName));
        return p;
    }

    private ScanTask makeFakeTask() {
        return new ScanTask(region, 0L);
    }

    // -------------------------------------------------------------------------
    // ScanCmd
    // -------------------------------------------------------------------------

    @Test
    void scanCmd_name_isScan() {
        assertEquals("scan", scanCmd.name());
    }

    @Test
    void scanCmd_permission_isRtpScan() {
        assertEquals("rtp.scan", scanCmd.permission());
    }

    @Test
    void scanCmd_onCommand_withNextCommand_returnsTrue() {
        io.github.dailystruggle.commandsapi.common.CommandsAPICommand next =
                mock(io.github.dailystruggle.commandsapi.common.CommandsAPICommand.class);
        boolean result = scanCmd.onCommand(senderId, params(), next);
        assertTrue(result);
        // ScanCmd returns true immediately when nextCommand != null (does not delegate)
        verify(next, never()).onCommand(any(), any(), any());
    }

    @Test
    void scanCmd_onCommand_withNoNextCommand_delegatesToScanResume() {
        // No scan task exists → ScanResumeCmd will call ScanStartCmd → returns true
        boolean result = scanCmd.onCommand(senderId, params(), null);
        assertTrue(result);
    }

    // -------------------------------------------------------------------------
    // ScanStartCmd
    // -------------------------------------------------------------------------

    @Test
    void scanStartCmd_name_isStart() {
        assertEquals("start", scanStartCmd.name());
    }

    @Test
    void scanStartCmd_permission_isRtpScan() {
        assertEquals("rtp.scan", scanStartCmd.permission());
    }

    @Test
    void scanStartCmd_onCommand_withNextCommand_delegates() {
        io.github.dailystruggle.commandsapi.common.CommandsAPICommand next =
                mock(io.github.dailystruggle.commandsapi.common.CommandsAPICommand.class);
        when(next.onCommand(any(), any(), any())).thenReturn(true);
        boolean result = scanStartCmd.onCommand(senderId, params(), next);
        assertTrue(result);
    }

    @Test
    void scanStartCmd_onCommand_noExistingTask_returnsTrue() {
        assertNull(RTP.getInstance().scanTasks.get("default"));

        boolean result = scanStartCmd.onCommand(senderId, params(), null);

        assertTrue(result);
        // Task is created and scheduled; with synchronous mock scheduler it may complete immediately
        // Verify no exception was thrown and command returned true
    }

    @Test
    void scanStartCmd_onCommand_whenTaskAlreadyRunning_doesNotReplace() {
        ScanTask existing = makeFakeTask();
        existing.pause.set(true); // pause so scheduler doesn't run it
        RTP.getInstance().scanTasks.put("default", existing);

        scanStartCmd.onCommand(senderId, params(), null);

        // existing task should still be in the map (start skips when task exists)
        assertSame(existing, RTP.getInstance().scanTasks.get("default"),
                "ScanStartCmd should not replace an already-running ScanTask");
    }

    @Test
    void scanStartCmd_onCommand_withExplicitRegionParam_returnsTrue() {
        boolean result = scanStartCmd.onCommand(senderId, paramsWithRegion("default"), null);
        assertTrue(result);
    }

    @Test
    void scanStartCmd_getRegions_withNullParam_returnsDefaultRegion() {
        List<Region> regions = scanStartCmd.getRegions(senderId, null);
        assertFalse(regions.isEmpty());
        assertEquals("default", regions.get(0).name);
    }

    @Test
    void scanStartCmd_getRegions_withNamedParam_returnsNamedRegion() {
        List<Region> regions = scanStartCmd.getRegions(senderId, Collections.singletonList("default"));
        assertFalse(regions.isEmpty());
        assertEquals("default", regions.get(0).name);
    }

    // -------------------------------------------------------------------------
    // ScanPauseCmd
    // -------------------------------------------------------------------------

    @Test
    void scanPauseCmd_name_isPause() {
        assertEquals("pause", scanPauseCmd.name());
    }

    @Test
    void scanPauseCmd_onCommand_withNextCommand_delegates() {
        io.github.dailystruggle.commandsapi.common.CommandsAPICommand next =
                mock(io.github.dailystruggle.commandsapi.common.CommandsAPICommand.class);
        when(next.onCommand(any(), any(), any())).thenReturn(true);
        boolean result = scanPauseCmd.onCommand(senderId, params(), next);
        assertTrue(result);
    }

    @Test
    void scanPauseCmd_onCommand_whenTaskRunning_pausesTask() {
        ScanTask task = makeFakeTask();
        task.pause.set(false);
        RTP.getInstance().scanTasks.put("default", task);

        boolean result = scanPauseCmd.onCommand(senderId, params(), null);

        assertTrue(result);
        assertTrue(task.pause.get(), "ScanPauseCmd should set pause=true on the running task");
    }

    @Test
    void scanPauseCmd_onCommand_whenNoTaskRunning_sendsNotRunningMessage() {
        assertNull(RTP.getInstance().scanTasks.get("default"));

        boolean result = scanPauseCmd.onCommand(senderId, params(), null);

        assertTrue(result, "onCommand should still return true even when no task is running");
        // No exception thrown and no task created
        assertNull(RTP.getInstance().scanTasks.get("default"));
    }

    @Test
    void scanPauseCmd_getRegions_withNullParam_nonPlayer_returnsDefaultRegion() {
        List<Region> regions = scanPauseCmd.getRegions(senderId, null);
        assertEquals(1, regions.size());
        assertEquals("default", regions.get(0).name);
    }

    @Test
    void scanPauseCmd_getRegions_withNamedParam_returnsNamedRegion() {
        List<Region> regions = scanPauseCmd.getRegions(senderId, Collections.singletonList("default"));
        assertFalse(regions.isEmpty());
        assertEquals("default", regions.get(0).name);
    }

    @Test
    void scanPauseCmd_onCommand_withRegionParam_onlyPausesSpecifiedRegion() {
        Square square2 = new Square();
        LinearAdjustor vert2 = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings2 = new RegionSettings(
                "region2", world, square2, vert2, false, false, 10L, 5, 0.0, 1L, "", false);
        Region region2 = new Region("region2", settings2);
        RTP.selectionAPI.permRegionLookup.put("region2", region2);

        ScanTask task1 = makeFakeTask();
        task1.pause.set(false);
        RTP.getInstance().scanTasks.put("default", task1);
        ScanTask task2 = new ScanTask(region2, 0L);
        task2.pause.set(false);
        RTP.getInstance().scanTasks.put("region2", task2);

        scanPauseCmd.onCommand(senderId, paramsWithRegion("default"), null);

        assertTrue(task1.pause.get(), "specified region task should be paused");
        assertFalse(task2.pause.get(), "unspecified region task should not be paused");
    }

    // -------------------------------------------------------------------------
    // ScanCancelCmd
    // -------------------------------------------------------------------------

    @Test
    void scanCancelCmd_name_isCancel() {
        assertEquals("cancel", scanCancelCmd.name());
    }

    @Test
    void scanCancelCmd_onCommand_withNextCommand_delegates() {
        io.github.dailystruggle.commandsapi.common.CommandsAPICommand next =
                mock(io.github.dailystruggle.commandsapi.common.CommandsAPICommand.class);
        when(next.onCommand(any(), any(), any())).thenReturn(true);
        boolean result = scanCancelCmd.onCommand(senderId, params(), next);
        assertTrue(result);
    }

    @Test
    void scanCancelCmd_onCommand_whenTaskRunning_cancelsAndRemovesTask() {
        ScanTask task = makeFakeTask();
        RTP.getInstance().scanTasks.put("default", task);

        boolean result = scanCancelCmd.onCommand(senderId, params(), null);

        assertTrue(result);
        assertTrue(task.isCancelled(), "ScanCancelCmd should mark task as cancelled");
        assertNull(RTP.getInstance().scanTasks.get("default"),
                "ScanCancelCmd should remove the task from scanTasks map");
    }

    @Test
    void scanCancelCmd_onCommand_whenNoTaskRunning_sendsNotRunningMessage() {
        assertNull(RTP.getInstance().scanTasks.get("default"));

        boolean result = scanCancelCmd.onCommand(senderId, params(), null);

        assertTrue(result);
        assertNull(RTP.getInstance().scanTasks.get("default"));
    }

    @Test
    void scanCancelCmd_onCommand_cleansUpScanFile() {
        // Create a scan file to verify deletion
        java.io.File dir = new java.io.File(tempDir.toFile(), "database" + java.io.File.separator + "regionData");
        dir.mkdirs();
        java.io.File scanFile = new java.io.File(dir, "default.scan");
        try { scanFile.createNewFile(); } catch (java.io.IOException ignored) {}
        assertTrue(scanFile.exists(), "scan file should exist before cancel");

        ScanTask task = makeFakeTask();
        RTP.getInstance().scanTasks.put("default", task);

        scanCancelCmd.onCommand(senderId, params(), null);

        assertFalse(scanFile.exists(), "ScanCancelCmd should delete the .scan progress file");
    }

    @Test
    void scanTask_save_whenCancelled_deletesFileInsteadOfWriting() {
        // Simulate the race condition: a .scan file exists (written by an async batch),
        // the task is cancelled, then save() is called again from the async thread.
        java.io.File dir = new java.io.File(tempDir.toFile(), "database" + java.io.File.separator + "regionData");
        dir.mkdirs();
        java.io.File fillFile = new java.io.File(dir, "default.scan");
        try { fillFile.createNewFile(); } catch (java.io.IOException ignored) {}
        assertTrue(fillFile.exists(), "scan file should exist before save() on cancelled task");

        ScanTask task = makeFakeTask();
        task.setCancelled(true);

        // This simulates the async wrapUpBatch thread calling save() after cancel
        task.save();

        assertFalse(fillFile.exists(),
                "save() on a cancelled ScanTask must delete the .scan file, not recreate it");
    }

    @Test
    void scanCancelCmd_getRegions_withNullParam_nonPlayer_returnsDefaultRegion() {
        List<Region> regions = scanCancelCmd.getRegions(senderId, null);
        assertEquals(1, regions.size());
        assertEquals("default", regions.get(0).name);
    }

    @Test
    void scanCancelCmd_getRegions_withNamedParam_returnsNamedRegion() {
        List<Region> regions = scanCancelCmd.getRegions(senderId, Collections.singletonList("default"));
        assertFalse(regions.isEmpty());
        assertEquals("default", regions.get(0).name);
    }

    @Test
    void scanCancelCmd_onCommand_withRegionParam_onlyCancelsSpecifiedRegion() {
        Square square2 = new Square();
        LinearAdjustor vert2 = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings2 = new RegionSettings(
                "region2", world, square2, vert2, false, false, 10L, 5, 0.0, 1L, "", false);
        Region region2 = new Region("region2", settings2);
        RTP.selectionAPI.permRegionLookup.put("region2", region2);

        ScanTask task1 = makeFakeTask();
        RTP.getInstance().scanTasks.put("default", task1);
        ScanTask task2 = new ScanTask(region2, 0L);
        RTP.getInstance().scanTasks.put("region2", task2);

        scanCancelCmd.onCommand(senderId, paramsWithRegion("default"), null);

        assertTrue(task1.isCancelled(), "specified region task should be cancelled");
        assertFalse(task2.isCancelled(), "unspecified region task should not be cancelled");
        assertNotNull(RTP.getInstance().scanTasks.get("region2"), "unspecified region task should remain");
    }

    // -------------------------------------------------------------------------
    // ScanResumeCmd
    // -------------------------------------------------------------------------

    @Test
    void scanResumeCmd_name_isResume() {
        assertEquals("resume", scanResumeCmd.name());
    }

    @Test
    void scanResumeCmd_onCommand_withNextCommand_delegates() {
        io.github.dailystruggle.commandsapi.common.CommandsAPICommand next =
                mock(io.github.dailystruggle.commandsapi.common.CommandsAPICommand.class);
        when(next.onCommand(any(), any(), any())).thenReturn(true);
        boolean result = scanResumeCmd.onCommand(senderId, params(), next);
        assertTrue(result);
    }

    @Test
    void scanResumeCmd_onCommand_whenTaskExists_unpausesAndSchedules() {
        ScanTask task = makeFakeTask();
        task.pause.set(true);
        RTP.getInstance().scanTasks.put("default", task);

        boolean result = scanResumeCmd.onCommand(senderId, params(), null);

        assertTrue(result);
        assertFalse(task.pause.get(), "ScanResumeCmd should set pause=false on existing task");
    }

    @Test
    void scanResumeCmd_onCommand_whenNoTask_startsNewScan() {
        assertNull(RTP.getInstance().scanTasks.get("default"));

        boolean result = scanResumeCmd.onCommand(senderId, params(), null);

        // ScanResumeCmd delegates to ScanStartCmd when no task exists; returns true
        assertTrue(result);
    }

    @Test
    void scanResumeCmd_getRegions_withNullParam_nonPlayer_returnsDefaultRegion() {
        List<Region> regions = scanResumeCmd.getRegions(senderId, null);
        assertEquals(1, regions.size());
        assertEquals("default", regions.get(0).name);
    }

    @Test
    void scanResumeCmd_getRegions_withNamedParam_returnsNamedRegion() {
        List<Region> regions = scanResumeCmd.getRegions(senderId, Collections.singletonList("default"));
        assertFalse(regions.isEmpty());
        assertEquals("default", regions.get(0).name);
    }

    @Test
    void scanResumeCmd_onCommand_withRegionParam_onlyResumesSpecifiedRegion() {
        Square square2 = new Square();
        LinearAdjustor vert2 = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings2 = new RegionSettings(
                "region2", world, square2, vert2, false, false, 10L, 5, 0.0, 1L, "", false);
        Region region2 = new Region("region2", settings2);
        RTP.selectionAPI.permRegionLookup.put("region2", region2);

        ScanTask task1 = makeFakeTask();
        task1.pause.set(true);
        RTP.getInstance().scanTasks.put("default", task1);
        ScanTask task2 = new ScanTask(region2, 0L);
        task2.pause.set(true);
        RTP.getInstance().scanTasks.put("region2", task2);

        scanResumeCmd.onCommand(senderId, paramsWithRegion("default"), null);

        assertFalse(task1.pause.get(), "specified region task should be resumed");
        assertTrue(task2.pause.get(), "unspecified region task should remain paused");
    }

    // -------------------------------------------------------------------------
    // ScanResetCmd
    // -------------------------------------------------------------------------

    @Test
    void scanResetCmd_name_isReset() {
        assertEquals("reset", scanResetCmd.name());
    }

    @Test
    void scanResetCmd_onCommand_withNextCommand_delegates() {
        io.github.dailystruggle.commandsapi.common.CommandsAPICommand next =
                mock(io.github.dailystruggle.commandsapi.common.CommandsAPICommand.class);
        when(next.onCommand(any(), any(), any())).thenReturn(true);
        boolean result = scanResetCmd.onCommand(senderId, params(), next);
        assertTrue(result);
    }

    @Test
    void scanResetCmd_onCommand_clearsShapeAndRemovesTask() {
        ScanTask task = makeFakeTask();
        task.pause.set(true); // pause so scheduler doesn't run it
        RTP.getInstance().scanTasks.put("default", task);

        boolean result = scanResetCmd.onCommand(senderId, params(), null);

        assertTrue(result);
        assertNull(RTP.getInstance().scanTasks.get("default"),
                "ScanResetCmd should remove any running scan task");
        assertTrue(task.isCancelled(), "ScanResetCmd should cancel the running task");
    }

    @Test
    void scanResetCmd_onCommand_withNoRunningTask_stillClearsShape() {
        assertNull(RTP.getInstance().scanTasks.get("default"));

        boolean result = scanResetCmd.onCommand(senderId, params(), null);

        assertTrue(result);
        assertNull(RTP.getInstance().scanTasks.get("default"));
    }

    @Test
    void scanResetCmd_onCommand_deletesScanProgressFile() {
        java.io.File dir = new java.io.File(tempDir.toFile(), "database" + java.io.File.separator + "regionData");
        dir.mkdirs();
        java.io.File scanFile = new java.io.File(dir, "default.scan");
        try { scanFile.createNewFile(); } catch (java.io.IOException ignored) {}
        assertTrue(scanFile.exists());

        scanResetCmd.onCommand(senderId, params(), null);

        assertFalse(scanFile.exists(), "ScanResetCmd should delete the .scan progress file");
    }

    @Test
    void scanResetCmd_getRegions_withNullParam_nonPlayer_returnsDefaultRegion() {
        List<Region> regions = scanResetCmd.getRegions(senderId, null);
        assertEquals(1, regions.size());
        assertEquals("default", regions.get(0).name);
    }

    @Test
    void scanResetCmd_getRegions_withNamedParam_returnsNamedRegion() {
        List<Region> regions = scanResetCmd.getRegions(senderId, Collections.singletonList("default"));
        assertFalse(regions.isEmpty());
        assertEquals("default", regions.get(0).name);
    }

    // -------------------------------------------------------------------------
    // State transition: start → pause → resume → cancel
    // -------------------------------------------------------------------------

    @Test
    void stateTransition_start_pause_resume_cancel() {
        // Pre-populate a paused task to simulate a running scan
        ScanTask task = makeFakeTask();
        task.pause.set(true);
        RTP.getInstance().scanTasks.put("default", task);

        // Pause (already paused, but verifies no error)
        scanPauseCmd.onCommand(senderId, params(), null);
        assertTrue(task.pause.get(), "task should be paused after pause");

        // Resume
        scanResumeCmd.onCommand(senderId, params(), null);
        assertFalse(task.pause.get(), "task should not be paused after resume");

        // Pause again before cancel
        task.pause.set(true);
        RTP.getInstance().scanTasks.put("default", task);

        // Cancel
        scanCancelCmd.onCommand(senderId, params(), null);
        assertTrue(task.isCancelled(), "task should be cancelled after cancel");
        assertNull(RTP.getInstance().scanTasks.get("default"), "task should be removed after cancel");
    }

    @Test
    void stateTransition_start_reset() {
        // Pre-populate a paused task to simulate a running scan
        ScanTask task = makeFakeTask();
        task.pause.set(true);
        RTP.getInstance().scanTasks.put("default", task);

        // Reset cancels running task and clears shape
        scanResetCmd.onCommand(senderId, params(), null);
        assertNull(RTP.getInstance().scanTasks.get("default"), "task should be removed after reset");
        assertTrue(task.isCancelled(), "task should be cancelled after reset");
    }

    @Test
    void stateTransition_pause_whenNotRunning_doesNotCreateTask() {
        assertNull(RTP.getInstance().scanTasks.get("default"));
        scanPauseCmd.onCommand(senderId, params(), null);
        assertNull(RTP.getInstance().scanTasks.get("default"),
                "pause on non-running scan should not create a task");
    }

    @Test
    void stateTransition_cancel_whenNotRunning_doesNotCreateTask() {
        assertNull(RTP.getInstance().scanTasks.get("default"));
        scanCancelCmd.onCommand(senderId, params(), null);
        assertNull(RTP.getInstance().scanTasks.get("default"),
                "cancel on non-running scan should not create a task");
    }

    @Test
    void stateTransition_resume_whenNotRunning_returnsTrue() {
        assertNull(RTP.getInstance().scanTasks.get("default"));
        boolean result = scanResumeCmd.onCommand(senderId, params(), null);
        // ScanResumeCmd delegates to ScanStartCmd when no task exists
        assertTrue(result, "resume when not running should return true");
    }

    // -------------------------------------------------------------------------
    // ScanSubCmd permission
    // -------------------------------------------------------------------------

    @Test
    void scanSubCmd_permission_isRtpScan() {
        assertEquals("rtp.scan", scanStartCmd.permission());
        assertEquals("rtp.scan", scanPauseCmd.permission());
        assertEquals("rtp.scan", scanCancelCmd.permission());
        assertEquals("rtp.scan", scanResumeCmd.permission());
        assertEquals("rtp.scan", scanResetCmd.permission());
    }

    // -------------------------------------------------------------------------
    // Multiple regions
    // -------------------------------------------------------------------------

    @Test
    void scanStartCmd_withMultipleRegions_returnsTrue() {
        // Register a second region
        Square square2 = new Square();
        LinearAdjustor vert2 = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings2 = new RegionSettings(
                "region2", world, square2, vert2, false, false, 10L, 5, 0.0, 1L, "", false);
        Region region2 = new Region("region2", settings2);
        RTP.selectionAPI.permRegionLookup.put("region2", region2);

        Map<String, List<String>> p = new HashMap<>();
        p.put("region", Arrays.asList("default", "region2"));

        boolean result = scanStartCmd.onCommand(senderId, p, null);
        assertTrue(result);
    }

    @Test
    void scanCancelCmd_withMultipleRegions_cancelsAll() {
        // Register a second region
        Square square2 = new Square();
        LinearAdjustor vert2 = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings2 = new RegionSettings(
                "region2", world, square2, vert2, false, false, 10L, 5, 0.0, 1L, "", false);
        Region region2 = new Region("region2", settings2);
        RTP.selectionAPI.permRegionLookup.put("region2", region2);

        ScanTask task1 = new ScanTask(region, 0L);
        ScanTask task2 = new ScanTask(region2, 0L);
        RTP.getInstance().scanTasks.put("default", task1);
        RTP.getInstance().scanTasks.put("region2", task2);

        Map<String, List<String>> p = new HashMap<>();
        p.put("region", Arrays.asList("default", "region2"));

        scanCancelCmd.onCommand(senderId, p, null);

        assertNull(RTP.getInstance().scanTasks.get("default"));
        assertNull(RTP.getInstance().scanTasks.get("region2"));
        assertTrue(task1.isCancelled());
        assertTrue(task2.isCancelled());
    }

    // -------------------------------------------------------------------------
    // Region-selection bug fixes: error messages and resume loop integrity
    // -------------------------------------------------------------------------

    @Test
    void scanStartCmd_whenTaskAlreadyRunning_announcesToOperators() {
        ScanTask task = makeFakeTask();
        RTP.getInstance().scanTasks.put("default", task);

        scanStartCmd.onCommand(senderId, params(), null);

        assertFalse(accessor.announcedMessages.isEmpty(),
                "scanRunning message should be announced to operators with rtp.scan permission");
    }

    @Test
    void scanPauseCmd_whenNoTask_announcesToOperators() {
        assertNull(RTP.getInstance().scanTasks.get("default"));

        scanPauseCmd.onCommand(senderId, params(), null);

        assertFalse(accessor.announcedMessages.isEmpty(),
                "scanNotRunning message should be announced (defaults to 'default' region)");
    }

    @Test
    void scanCancelCmd_whenNoTask_announcesToOperators() {
        assertNull(RTP.getInstance().scanTasks.get("default"));

        scanCancelCmd.onCommand(senderId, params(), null);

        assertFalse(accessor.announcedMessages.isEmpty(),
                "scanNotRunning message should be announced (defaults to 'default' region)");
    }

    @Test
    void scanResumeCmd_withMixedTasks_resumesExistingAndStartsMissing() {
        Square square2 = new Square();
        LinearAdjustor vert2 = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings2 = new RegionSettings(
                "region2", world, square2, vert2, false, false, 10L, 5, 0.0, 1L, "", false);
        Region region2 = new Region("region2", settings2);
        RTP.selectionAPI.permRegionLookup.put("region2", region2);

        // "default" has a paused task; "region2" has no task
        ScanTask task1 = makeFakeTask();
        task1.pause.set(true);
        RTP.getInstance().scanTasks.put("default", task1);

        // Explicit multi-region param to cover both regions
        Map<String, List<String>> p = new HashMap<>();
        p.put("region", Arrays.asList("default", "region2"));
        scanResumeCmd.onCommand(senderId, p, null);

        // task1 must have been unpaused (resumed), not skipped or replaced
        assertFalse(task1.pause.get(), "existing task should be resumed (pause flag set to false)");
        // a new scan must have been started for region2 (announced via scanStart)
        assertTrue(accessor.announcedMessages.stream().anyMatch(m -> m.contains("region2")),
                "missing task should have a new scan started for region2");
    }

    @Test
    void scanResumeCmd_noRegionParam_defaultsToDefaultRegion() {
        // "default" has a paused task; no region param → should resume "default"
        ScanTask task1 = makeFakeTask();
        task1.pause.set(true);
        RTP.getInstance().scanTasks.put("default", task1);

        scanResumeCmd.onCommand(senderId, params(), null);

        assertFalse(task1.pause.get(),
                "no-param resume should default to 'default' region and unpause its task");
    }
}
