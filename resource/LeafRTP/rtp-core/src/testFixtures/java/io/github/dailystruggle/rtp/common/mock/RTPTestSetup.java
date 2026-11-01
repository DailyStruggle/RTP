package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.Configs;

import io.github.dailystruggle.rtp.common.database.ActiveDatabase;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.tasks.tick.MiscTasks;
import java.io.File;
import java.lang.reflect.Field;

/**
 * Test harness wiring {@link MockRTPServerAccessor} into static singletons for tests.
 * Installs mock server accessor, scheduler, configs, and RTP singleton.
 */
public final class RTPTestSetup {

    private RTPTestSetup() { }

    /**
     * Install a fresh {@link MockRTPServerAccessor} using {@code pluginDir} as
     * the plugin directory and wire it into all static singletons.
     *
     * @param pluginDir directory to expose as the plugin data folder (use a JUnit {@code @TempDir})
     * @return the installed accessor so tests can call {@link MockRTPServerAccessor#addPlayer} etc.
     */
    public static MockRTPServerAccessor install(File pluginDir) {
        // Pending arrival holds belong to the previous test's scheduler and would never fire.
        io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask.flushArrivalReservations();
        MockRTPServerAccessor accessor = new MockRTPServerAccessor(pluginDir);

        // Clear any accessor registered by a previous test so that the write-once
        // guard in RTPAPI.setServerAccessor() does not reject the new instance.
        RTPAPI.serverAccessor = null;

        // Wire RTP static fields
        RTPRuntime.setServerAccessor(accessor);
        RTPRuntime.setScheduler(accessor.getMockScheduler());
        if (ActiveSelection.get() == null) {
            ActiveSelection.set(new io.github.dailystruggle.rtp.common.selection.SelectionAPI());
        }

        // Ensure RTP.getInstance() returns a valid instance
        ensureRTPInstance();

        // Guarantee the task pipes on the shared RTP.getInstance() are real
        // TimeBoundTaskPipe instances, not whatever a previous test class may
        // have installed (e.g. Mockito mocks that return `false` from
        // execute(...) and silently no-op add(...), causing any later test
        // that queues and drains the pipe to fail).
        // Rebuilding per-install also resets any `stop=true`/`avgTime` drift
        // left by shutdown-path tests.
        io.github.dailystruggle.rtp.common.RTP rtpInstance = RTP.getInstance();
        if (rtpInstance != null) {
            ActiveDatabase.setAccessor(null);
            MiscTasks.setCancel((io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe) accessor.createTaskPipe());
            MiscTasks.setSync((io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe) accessor.createTaskPipe());
            MiscTasks.setBackgroundSync((io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe) accessor.createTaskPipe());
            MiscTasks.setAsync((io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe) accessor.createTaskPipe());
            MiscTasks.setStartup((io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe) accessor.createTaskPipe());
            if (MiscTasks.cancel() instanceof io.github.dailystruggle.rtp.common.tasks.TimeBoundTaskPipe timeBoundCancel) {
                timeBoundCancel.setAvailableTime(Long.MAX_VALUE);
            }
        }

        // Guarantee RTPAPI.serverAccessor is always set to the current test's accessor.
        // ensureRTPInstance() reuses an existing RTP instance when one is present, so the
        // RTP constructor (which calls RTPAPI.setServerAccessor) may not run on subsequent
        // installs - leaving RTPAPI.serverAccessor null after the null-clear above.
        RTPAPI.serverAccessor = accessor;

        // Always refresh Configs to point at the current test's pluginDir and register all
        // core parsers (SafetyKeys, PerformanceKeys, LoggingKeys, etc.).  Without this,
        // LocationGenerator.getLocation() and RegionCacheTask NPE because the parsers are
        // only registered by Configs.reloadConfigs(), never by the RTP constructor itself.
        ActiveConfigs.set(new Configs(pluginDir));
        ActiveConfigs.get().reloadConfigs();

        return accessor;
    }

    /**
     * Tear down all test singletons and disconnect all databases initialized by {@link #install(File)}.
     * This ensures no open file handles linger on Windows preventing {@code @TempDir} deletion.
     */
    public static void cleanUp() {
        if (ActiveConfigs.get() != null) {
            if (ActiveConfigs.get().fileDatabase != null) {
                ActiveConfigs.get().fileDatabase.processQueries(Long.MAX_VALUE);
                ActiveConfigs.get().fileDatabase.disconnect(java.util.Collections.emptyMap());
            }
            if (ActiveConfigs.get().configParserMap != null) {
                for (io.github.dailystruggle.rtp.common.configuration.ConfigParser<?> cp : ActiveConfigs.get().configParserMap.values()) {
                    if (cp != null && cp.fileDatabase != null && cp.fileDatabase != ActiveConfigs.get().fileDatabase) {
                        cp.fileDatabase.processQueries(Long.MAX_VALUE);
                        cp.fileDatabase.disconnect(java.util.Collections.emptyMap());
                    }
                }
            }
            if (ActiveConfigs.get().multiConfigParserMap != null) {
                for (io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<?> mcp : ActiveConfigs.get().multiConfigParserMap.values()) {
                    if (mcp != null) {
                        if (mcp.fileDatabase != null) {
                            mcp.fileDatabase.processQueries(Long.MAX_VALUE);
                            mcp.fileDatabase.disconnect(java.util.Collections.emptyMap());
                        }
                        if (mcp.configParserFactory != null && mcp.configParserFactory.map != null) {
                            for (io.github.dailystruggle.rtp.common.configuration.ConfigParser<?> cp : mcp.configParserFactory.map.values()) {
                                if (cp != null && cp.fileDatabase != null && cp.fileDatabase != mcp.fileDatabase) {
                                    cp.fileDatabase.processQueries(Long.MAX_VALUE);
                                    cp.fileDatabase.disconnect(java.util.Collections.emptyMap());
                                }
                            }
                        }
                    }
                }
            }
        }
        if (RTP.getInstance() != null) {
            ActiveDatabase.setAccessor(null);
        }
        ActiveConfigs.set(null);
        RTPRuntime.setServerAccessor(null);
        RTPRuntime.setScheduler(null);
        RTPAPI.serverAccessor = null;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Creates an anonymous {@link RTP} subclass and injects it into the private
     * static {@code instance} field so that {@link RTP#getInstance()} never
     * returns {@code null} during tests.
     */
    private static void ensureRTPInstance() {
        try {
            Field instanceField = RTP.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            if (instanceField.get(null) == null) {
                RTP rtp = new RTP() { };
                instanceField.set(null, rtp);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to initialise RTP instance for tests", e);
        }
    }
}
