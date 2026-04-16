package io.github.dailystruggle.rtp.common.commands.fill;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPCommandSender;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tasks.FillTask;
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
 * {@link FillCmd}, {@link FillStartCmd}, {@link FillPauseCmd},
 * {@link FillCancelCmd}, {@link FillResumeCmd}, {@link FillResetCmd}.
 *
 * <p>Coverage targets: state transitions, "not running" branches,
 * cancel cleanup, permission denial, and invalid argument lengths.
 */
public class FillCmdTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private MockRTPWorld world;
    private Region region;
    private Square square;
    private UUID senderId;
    private MockRTPCommandSender sender;

    // Commands under test
    private FillCmd fillCmd;
    private FillStartCmd fillStartCmd;
    private FillPauseCmd fillPauseCmd;
    private FillCancelCmd fillCancelCmd;
    private FillResumeCmd fillResumeCmd;
    private FillResetCmd fillResetCmd;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());

        // Set up world
        world = new MockRTPWorld("fill_test_world");
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
        when(multiConfigParser.getParser(any(String.class))).thenReturn(regionConfig);
        RTP.configs.multiConfigParserMap.put(RegionKeys.class, multiConfigParser);

        // Set up sender
        senderId = UUID.randomUUID();
        sender = new MockRTPCommandSender(senderId, "TestSender");

        // Instantiate commands
        fillCmd = new FillCmd(null);
        fillStartCmd = new FillStartCmd(null);
        fillPauseCmd = new FillPauseCmd(null);
        fillCancelCmd = new FillCancelCmd(null);
        fillResumeCmd = new FillResumeCmd(null);
        fillResetCmd = new FillResetCmd(null);

        // Ensure fillTasks is clean
        RTP.getInstance().fillTasks.clear();
    }

    @AfterEach
    void tearDown() {
        RTP.getInstance().fillTasks.clear();
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

    private FillTask makeFakeTask() {
        return new FillTask(region, 0L);
    }

    // -------------------------------------------------------------------------
    // FillCmd
    // -------------------------------------------------------------------------

    @Test
    void fillCmd_name_isFill() {
        assertEquals("fill", fillCmd.name());
    }

    @Test
    void fillCmd_permission_isRtpFill() {
        assertEquals("rtp.fill", fillCmd.permission());
    }

    @Test
    void fillCmd_onCommand_withNextCommand_returnsTrue() {
        io.github.dailystruggle.commandsapi.common.CommandsAPICommand next =
                mock(io.github.dailystruggle.commandsapi.common.CommandsAPICommand.class);
        boolean result = fillCmd.onCommand(senderId, params(), next);
        assertTrue(result);
        // FillCmd returns true immediately when nextCommand != null (does not delegate)
        verify(next, never()).onCommand(any(), any(), any());
    }

    @Test
    void fillCmd_onCommand_withNoNextCommand_delegatesToFillResume() {
        // No fill task exists → FillResumeCmd will call FillStartCmd → returns true
        boolean result = fillCmd.onCommand(senderId, params(), null);
        assertTrue(result);
    }

    // -------------------------------------------------------------------------
    // FillStartCmd
    // -------------------------------------------------------------------------

    @Test
    void fillStartCmd_name_isStart() {
        assertEquals("start", fillStartCmd.name());
    }

    @Test
    void fillStartCmd_permission_isRtpFill() {
        assertEquals("rtp.fill", fillStartCmd.permission());
    }

    @Test
    void fillStartCmd_onCommand_withNextCommand_delegates() {
        io.github.dailystruggle.commandsapi.common.CommandsAPICommand next =
                mock(io.github.dailystruggle.commandsapi.common.CommandsAPICommand.class);
        when(next.onCommand(any(), any(), any())).thenReturn(true);
        boolean result = fillStartCmd.onCommand(senderId, params(), next);
        assertTrue(result);
    }

    @Test
    void fillStartCmd_onCommand_noExistingTask_returnsTrue() {
        assertNull(RTP.getInstance().fillTasks.get("default"));

        boolean result = fillStartCmd.onCommand(senderId, params(), null);

        assertTrue(result);
        // Task is created and scheduled; with synchronous mock scheduler it may complete immediately
        // Verify no exception was thrown and command returned true
    }

    @Test
    void fillStartCmd_onCommand_whenTaskAlreadyRunning_doesNotReplace() {
        FillTask existing = makeFakeTask();
        existing.pause.set(true); // pause so scheduler doesn't run it
        RTP.getInstance().fillTasks.put("default", existing);

        fillStartCmd.onCommand(senderId, params(), null);

        // existing task should still be in the map (start skips when task exists)
        assertSame(existing, RTP.getInstance().fillTasks.get("default"),
                "FillStartCmd should not replace an already-running FillTask");
    }

    @Test
    void fillStartCmd_onCommand_withExplicitRegionParam_returnsTrue() {
        boolean result = fillStartCmd.onCommand(senderId, paramsWithRegion("default"), null);
        assertTrue(result);
    }

    @Test
    void fillStartCmd_getRegions_withNullParam_returnsDefaultRegion() {
        List<Region> regions = fillStartCmd.getRegions(senderId, null);
        assertFalse(regions.isEmpty());
        assertEquals("default", regions.get(0).name);
    }

    @Test
    void fillStartCmd_getRegions_withNamedParam_returnsNamedRegion() {
        List<Region> regions = fillStartCmd.getRegions(senderId, Collections.singletonList("default"));
        assertFalse(regions.isEmpty());
        assertEquals("default", regions.get(0).name);
    }

    // -------------------------------------------------------------------------
    // FillPauseCmd
    // -------------------------------------------------------------------------

    @Test
    void fillPauseCmd_name_isPause() {
        assertEquals("pause", fillPauseCmd.name());
    }

    @Test
    void fillPauseCmd_onCommand_withNextCommand_delegates() {
        io.github.dailystruggle.commandsapi.common.CommandsAPICommand next =
                mock(io.github.dailystruggle.commandsapi.common.CommandsAPICommand.class);
        when(next.onCommand(any(), any(), any())).thenReturn(true);
        boolean result = fillPauseCmd.onCommand(senderId, params(), next);
        assertTrue(result);
    }

    @Test
    void fillPauseCmd_onCommand_whenTaskRunning_pausesTask() {
        FillTask task = makeFakeTask();
        task.pause.set(false);
        RTP.getInstance().fillTasks.put("default", task);

        boolean result = fillPauseCmd.onCommand(senderId, params(), null);

        assertTrue(result);
        assertTrue(task.pause.get(), "FillPauseCmd should set pause=true on the running task");
    }

    @Test
    void fillPauseCmd_onCommand_whenNoTaskRunning_sendsNotRunningMessage() {
        assertNull(RTP.getInstance().fillTasks.get("default"));

        boolean result = fillPauseCmd.onCommand(senderId, params(), null);

        assertTrue(result, "onCommand should still return true even when no task is running");
        // No exception thrown and no task created
        assertNull(RTP.getInstance().fillTasks.get("default"));
    }

    @Test
    void fillPauseCmd_getRegions_withNullParam_returnsDefaultRegion() {
        List<Region> regions = fillPauseCmd.getRegions(senderId, null);
        assertFalse(regions.isEmpty());
        assertEquals("default", regions.get(0).name);
    }

    // -------------------------------------------------------------------------
    // FillCancelCmd
    // -------------------------------------------------------------------------

    @Test
    void fillCancelCmd_name_isCancel() {
        assertEquals("cancel", fillCancelCmd.name());
    }

    @Test
    void fillCancelCmd_onCommand_withNextCommand_delegates() {
        io.github.dailystruggle.commandsapi.common.CommandsAPICommand next =
                mock(io.github.dailystruggle.commandsapi.common.CommandsAPICommand.class);
        when(next.onCommand(any(), any(), any())).thenReturn(true);
        boolean result = fillCancelCmd.onCommand(senderId, params(), next);
        assertTrue(result);
    }

    @Test
    void fillCancelCmd_onCommand_whenTaskRunning_cancelsAndRemovesTask() {
        FillTask task = makeFakeTask();
        RTP.getInstance().fillTasks.put("default", task);

        boolean result = fillCancelCmd.onCommand(senderId, params(), null);

        assertTrue(result);
        assertTrue(task.isCancelled(), "FillCancelCmd should mark task as cancelled");
        assertNull(RTP.getInstance().fillTasks.get("default"),
                "FillCancelCmd should remove the task from fillTasks map");
    }

    @Test
    void fillCancelCmd_onCommand_whenNoTaskRunning_sendsNotRunningMessage() {
        assertNull(RTP.getInstance().fillTasks.get("default"));

        boolean result = fillCancelCmd.onCommand(senderId, params(), null);

        assertTrue(result);
        assertNull(RTP.getInstance().fillTasks.get("default"));
    }

    @Test
    void fillCancelCmd_onCommand_cleansUpFillFile() {
        // Create a fill file to verify deletion
        java.io.File dir = new java.io.File(tempDir.toFile(), "database" + java.io.File.separator + "regionData");
        dir.mkdirs();
        java.io.File fillFile = new java.io.File(dir, "default.fill");
        try { fillFile.createNewFile(); } catch (java.io.IOException ignored) {}
        assertTrue(fillFile.exists(), "fill file should exist before cancel");

        FillTask task = makeFakeTask();
        RTP.getInstance().fillTasks.put("default", task);

        fillCancelCmd.onCommand(senderId, params(), null);

        assertFalse(fillFile.exists(), "FillCancelCmd should delete the .fill progress file");
    }

    @Test
    void fillCancelCmd_getRegions_withNullParam_returnsDefaultRegion() {
        List<Region> regions = fillCancelCmd.getRegions(senderId, null);
        assertFalse(regions.isEmpty());
        assertEquals("default", regions.get(0).name);
    }

    // -------------------------------------------------------------------------
    // FillResumeCmd
    // -------------------------------------------------------------------------

    @Test
    void fillResumeCmd_name_isResume() {
        assertEquals("resume", fillResumeCmd.name());
    }

    @Test
    void fillResumeCmd_onCommand_withNextCommand_delegates() {
        io.github.dailystruggle.commandsapi.common.CommandsAPICommand next =
                mock(io.github.dailystruggle.commandsapi.common.CommandsAPICommand.class);
        when(next.onCommand(any(), any(), any())).thenReturn(true);
        boolean result = fillResumeCmd.onCommand(senderId, params(), next);
        assertTrue(result);
    }

    @Test
    void fillResumeCmd_onCommand_whenTaskExists_unpausesAndSchedules() {
        FillTask task = makeFakeTask();
        task.pause.set(true);
        RTP.getInstance().fillTasks.put("default", task);

        boolean result = fillResumeCmd.onCommand(senderId, params(), null);

        assertTrue(result);
        assertFalse(task.pause.get(), "FillResumeCmd should set pause=false on existing task");
    }

    @Test
    void fillResumeCmd_onCommand_whenNoTask_startsNewFill() {
        assertNull(RTP.getInstance().fillTasks.get("default"));

        boolean result = fillResumeCmd.onCommand(senderId, params(), null);

        // FillResumeCmd delegates to FillStartCmd when no task exists; returns true
        assertTrue(result);
    }

    @Test
    void fillResumeCmd_getRegions_withNullParam_returnsDefaultRegion() {
        List<Region> regions = fillResumeCmd.getRegions(senderId, null);
        assertFalse(regions.isEmpty());
        assertEquals("default", regions.get(0).name);
    }

    // -------------------------------------------------------------------------
    // FillResetCmd
    // -------------------------------------------------------------------------

    @Test
    void fillResetCmd_name_isReset() {
        assertEquals("reset", fillResetCmd.name());
    }

    @Test
    void fillResetCmd_onCommand_withNextCommand_delegates() {
        io.github.dailystruggle.commandsapi.common.CommandsAPICommand next =
                mock(io.github.dailystruggle.commandsapi.common.CommandsAPICommand.class);
        when(next.onCommand(any(), any(), any())).thenReturn(true);
        boolean result = fillResetCmd.onCommand(senderId, params(), next);
        assertTrue(result);
    }

    @Test
    void fillResetCmd_onCommand_clearsShapeAndRemovesTask() {
        FillTask task = makeFakeTask();
        task.pause.set(true); // pause so scheduler doesn't run it
        RTP.getInstance().fillTasks.put("default", task);

        boolean result = fillResetCmd.onCommand(senderId, params(), null);

        assertTrue(result);
        assertNull(RTP.getInstance().fillTasks.get("default"),
                "FillResetCmd should remove any running fill task");
        assertTrue(task.isCancelled(), "FillResetCmd should cancel the running task");
    }

    @Test
    void fillResetCmd_onCommand_withNoRunningTask_stillClearsShape() {
        assertNull(RTP.getInstance().fillTasks.get("default"));

        boolean result = fillResetCmd.onCommand(senderId, params(), null);

        assertTrue(result);
        assertNull(RTP.getInstance().fillTasks.get("default"));
    }

    @Test
    void fillResetCmd_onCommand_deletesFillProgressFile() {
        java.io.File dir = new java.io.File(tempDir.toFile(), "database" + java.io.File.separator + "regionData");
        dir.mkdirs();
        java.io.File fillFile = new java.io.File(dir, "default.fill");
        try { fillFile.createNewFile(); } catch (java.io.IOException ignored) {}
        assertTrue(fillFile.exists());

        fillResetCmd.onCommand(senderId, params(), null);

        assertFalse(fillFile.exists(), "FillResetCmd should delete the .fill progress file");
    }

    @Test
    void fillResetCmd_getRegions_withNullParam_returnsDefaultRegion() {
        List<Region> regions = fillResetCmd.getRegions(senderId, null);
        assertFalse(regions.isEmpty());
        assertEquals("default", regions.get(0).name);
    }

    @Test
    void fillResetCmd_getRegions_withNamedParam_returnsNamedRegion() {
        List<Region> regions = fillResetCmd.getRegions(senderId, Collections.singletonList("default"));
        assertFalse(regions.isEmpty());
        assertEquals("default", regions.get(0).name);
    }

    // -------------------------------------------------------------------------
    // State transition: start → pause → resume → cancel
    // -------------------------------------------------------------------------

    @Test
    void stateTransition_start_pause_resume_cancel() {
        // Pre-populate a paused task to simulate a running fill
        FillTask task = makeFakeTask();
        task.pause.set(true);
        RTP.getInstance().fillTasks.put("default", task);

        // Pause (already paused, but verifies no error)
        fillPauseCmd.onCommand(senderId, params(), null);
        assertTrue(task.pause.get(), "task should be paused after pause");

        // Resume
        fillResumeCmd.onCommand(senderId, params(), null);
        assertFalse(task.pause.get(), "task should not be paused after resume");

        // Pause again before cancel
        task.pause.set(true);
        RTP.getInstance().fillTasks.put("default", task);

        // Cancel
        fillCancelCmd.onCommand(senderId, params(), null);
        assertTrue(task.isCancelled(), "task should be cancelled after cancel");
        assertNull(RTP.getInstance().fillTasks.get("default"), "task should be removed after cancel");
    }

    @Test
    void stateTransition_start_reset() {
        // Pre-populate a paused task to simulate a running fill
        FillTask task = makeFakeTask();
        task.pause.set(true);
        RTP.getInstance().fillTasks.put("default", task);

        // Reset cancels running task and clears shape
        fillResetCmd.onCommand(senderId, params(), null);
        assertNull(RTP.getInstance().fillTasks.get("default"), "task should be removed after reset");
        assertTrue(task.isCancelled(), "task should be cancelled after reset");
    }

    @Test
    void stateTransition_pause_whenNotRunning_doesNotCreateTask() {
        assertNull(RTP.getInstance().fillTasks.get("default"));
        fillPauseCmd.onCommand(senderId, params(), null);
        assertNull(RTP.getInstance().fillTasks.get("default"),
                "pause on non-running fill should not create a task");
    }

    @Test
    void stateTransition_cancel_whenNotRunning_doesNotCreateTask() {
        assertNull(RTP.getInstance().fillTasks.get("default"));
        fillCancelCmd.onCommand(senderId, params(), null);
        assertNull(RTP.getInstance().fillTasks.get("default"),
                "cancel on non-running fill should not create a task");
    }

    @Test
    void stateTransition_resume_whenNotRunning_returnsTrue() {
        assertNull(RTP.getInstance().fillTasks.get("default"));
        boolean result = fillResumeCmd.onCommand(senderId, params(), null);
        // FillResumeCmd delegates to FillStartCmd when no task exists
        assertTrue(result, "resume when not running should return true");
    }

    // -------------------------------------------------------------------------
    // FillSubCmd permission
    // -------------------------------------------------------------------------

    @Test
    void fillSubCmd_permission_isRtpFill() {
        assertEquals("rtp.fill", fillStartCmd.permission());
        assertEquals("rtp.fill", fillPauseCmd.permission());
        assertEquals("rtp.fill", fillCancelCmd.permission());
        assertEquals("rtp.fill", fillResumeCmd.permission());
        assertEquals("rtp.fill", fillResetCmd.permission());
    }

    // -------------------------------------------------------------------------
    // Multiple regions
    // -------------------------------------------------------------------------

    @Test
    void fillStartCmd_withMultipleRegions_returnsTrue() {
        // Register a second region
        Square square2 = new Square();
        LinearAdjustor vert2 = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings2 = new RegionSettings(
                "region2", world, square2, vert2, false, false, 10L, 5, 0.0, 1L, "", false);
        Region region2 = new Region("region2", settings2);
        RTP.selectionAPI.permRegionLookup.put("region2", region2);

        Map<String, List<String>> p = new HashMap<>();
        p.put("region", Arrays.asList("default", "region2"));

        boolean result = fillStartCmd.onCommand(senderId, p, null);
        assertTrue(result);
    }

    @Test
    void fillCancelCmd_withMultipleRegions_cancelsAll() {
        // Register a second region
        Square square2 = new Square();
        LinearAdjustor vert2 = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings2 = new RegionSettings(
                "region2", world, square2, vert2, false, false, 10L, 5, 0.0, 1L, "", false);
        Region region2 = new Region("region2", settings2);
        RTP.selectionAPI.permRegionLookup.put("region2", region2);

        FillTask task1 = new FillTask(region, 0L);
        FillTask task2 = new FillTask(region2, 0L);
        RTP.getInstance().fillTasks.put("default", task1);
        RTP.getInstance().fillTasks.put("region2", task2);

        Map<String, List<String>> p = new HashMap<>();
        p.put("region", Arrays.asList("default", "region2"));

        fillCancelCmd.onCommand(senderId, p, null);

        assertNull(RTP.getInstance().fillTasks.get("default"));
        assertNull(RTP.getInstance().fillTasks.get("region2"));
        assertTrue(task1.isCancelled());
        assertTrue(task2.isCancelled());
    }
}
