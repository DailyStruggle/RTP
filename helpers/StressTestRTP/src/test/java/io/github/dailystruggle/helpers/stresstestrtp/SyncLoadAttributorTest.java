package io.github.dailystruggle.helpers.stresstestrtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SyncLoadAttributorTest {

    private static final ClassLoader HARNESS = new ClassLoader() {};
    private static final ClassLoader SERVER = new ClassLoader() {};
    private static final ClassLoader OTHER_PLUGIN = new ClassLoader() {};
    private static final Function<ClassLoader, String> PLUGINS =
            Map.of(HARNESS, "StressTestRTP", OTHER_PLUGIN, "BetterRTP")::get;

    private static SyncLoadAttributor.Frame f(ClassLoader loader, String method) {
        return new SyncLoadAttributor.Frame(loader, method);
    }

    /** Innermost-first listener prefix: walker lambda, JDK walker frame, listener chain, event dispatch. */
    private static List<SyncLoadAttributor.Frame> listenerPrefix() {
        return List.of(
                f(HARNESS, "lambda$requester$0"),
                f(null, "walk"),
                f(HARNESS, "requester"),
                f(HARNESS, "onLoad"),
                f(HARNESS, "onChunkLoad"),
                f(SERVER, "callEvent"));
    }

    private static SyncLoadAttributor.Attribution attribute(List<SyncLoadAttributor.Frame> prefix,
                                                            SyncLoadAttributor.Frame... rest) {
        List<SyncLoadAttributor.Frame> all = new java.util.ArrayList<>(prefix);
        all.addAll(List.of(rest));
        return SyncLoadAttributor.attribute(all.iterator(), HARNESS, PLUGINS);
    }

    @Test
    @DisplayName("Self-test sync load from a harness frame below the event dispatch names the harness")
    void harnessCallerBelowListenerIsAttributed() {
        SyncLoadAttributor.Attribution a = attribute(listenerPrefix(),
                f(SERVER, "getChunkAt"),
                f(HARNESS, "lambda$tryCandidate$3"),
                f(SERVER, "executeTask"));
        assertNotNull(a);
        assertEquals("StressTestRTP", a.pluginName);
        assertTrue(a.blocking);
    }

    @Test
    @DisplayName("Another plugin's sync load below the harness listener names that plugin")
    void otherPluginCallerIsAttributed() {
        SyncLoadAttributor.Attribution a = attribute(listenerPrefix(),
                f(SERVER, "syncLoad"),
                f(OTHER_PLUGIN, "findLocation"));
        assertNotNull(a);
        assertEquals("BetterRTP", a.pluginName);
        assertTrue(a.blocking);
    }

    @Test
    @DisplayName("Async load completed by the server task loop names nobody, even with a harness frame past the boundary")
    void asyncLoadStopsAtServerBoundary() {
        assertNull(attribute(listenerPrefix(),
                f(SERVER, "pollNextChunkTask"),
                f(HARNESS, "run")));
    }

    @Test
    @DisplayName("Paper sync load: event fires inside the managedBlock task drain, caller further out is named")
    void syncLoadInsideManagedBlockDrainIsAttributed() {
        SyncLoadAttributor.Attribution a = attribute(listenerPrefix(),
                f(SERVER, "onFullLoad"),
                f(SERVER, "executeTask"),
                f(SERVER, "pollTask"),
                f(SERVER, "managedBlock"),
                f(SERVER, "syncLoad"),
                f(SERVER, "getChunkAt"),
                f(HARNESS, "lambda$tryCandidate$3"),
                f(SERVER, "mainThreadHeartbeat"),
                f(SERVER, "tickServer"));
        assertNotNull(a);
        assertEquals("StressTestRTP", a.pluginName);
        assertTrue(a.blocking);
    }

    @Test
    @DisplayName("Async load drained while the server idles until the next tick names nobody")
    void asyncLoadDuringIdleWaitIsNotAttributed() {
        assertNull(attribute(listenerPrefix(),
                f(SERVER, "executeTask"),
                f(SERVER, "pollTask"),
                f(SERVER, "managedBlock"),
                f(SERVER, "waitUntilNextTick"),
                f(SERVER, "runServer"),
                f(OTHER_PLUGIN, "unreachable")));
    }

    @Test
    @DisplayName("Async load drained in the tick's task pass names nobody even with a plugin frame further out")
    void drainedLoadBeforePluginFrameIsNotAttributed() {
        assertNull(attribute(listenerPrefix(),
                f(SERVER, "runAllTasks"),
                f(OTHER_PLUGIN, "run"),
                f(SERVER, "tickServer")));
    }

    @Test
    @DisplayName("Walk bound: a caller beyond maxFrames is not reached")
    void maxFramesBoundsTheWalk() {
        List<SyncLoadAttributor.Frame> all = new java.util.ArrayList<>(listenerPrefix());
        for (int i = 0; i < 40; i++) all.add(f(SERVER, "chunkSystem" + i));
        all.add(f(OTHER_PLUGIN, "findLocation"));
        assertNull(SyncLoadAttributor.attribute(all.iterator(), HARNESS, PLUGINS, 32));
        SyncLoadAttributor.Attribution a = SyncLoadAttributor.attribute(all.iterator(), HARNESS, PLUGINS, 96);
        assertNotNull(a);
        assertEquals("BetterRTP", a.pluginName);
    }

    @Test
    @DisplayName("Listener-only stack is not charged to the harness")
    void listenerPrefixAloneIsNotAttributed() {
        assertNull(attribute(listenerPrefix()));
        assertNull(SyncLoadAttributor.attribute(List.of(
                f(HARNESS, "onLoad"), f(null, "walk"), f(HARNESS, "onChunkLoad")).iterator(), HARNESS, PLUGINS));
    }

    @Test
    @DisplayName("isBlockingMethod correctly identifies synchronous chunk-loading calls")
    void blockingMethodsIdentified() {
        assertTrue(SyncLoadAttributor.isBlockingMethod("syncLoad"));
        assertTrue(SyncLoadAttributor.isBlockingMethod("getChunkFallback"));
        assertTrue(SyncLoadAttributor.isBlockingMethod("managedBlock"));
        assertTrue(SyncLoadAttributor.isBlockingMethod("getChunkAt"));
        assertTrue(SyncLoadAttributor.isBlockingMethod("loadChunk"));

        assertFalse(SyncLoadAttributor.isBlockingMethod("teleportAsync"));
        assertFalse(SyncLoadAttributor.isBlockingMethod("scheduleTickingState"));
        assertFalse(SyncLoadAttributor.isBlockingMethod("moonrise$loadChunksAsync"));
        assertFalse(SyncLoadAttributor.isBlockingMethod("addPluginChunkTicket"));
        assertFalse(SyncLoadAttributor.isBlockingMethod("getChunkAtAsync"));
    }

    @Test
    @DisplayName("Attribution holds pluginName and blocking flag")
    void attributionProperties() {
        SyncLoadAttributor.Attribution syncAttr = new SyncLoadAttributor.Attribution("BetterRTP", true);
        assertEquals("BetterRTP", syncAttr.pluginName);
        assertTrue(syncAttr.blocking);

        SyncLoadAttributor.Attribution inlineAttr = new SyncLoadAttributor.Attribution("LeafRTP", false);
        assertEquals("LeafRTP", inlineAttr.pluginName);
        assertFalse(inlineAttr.blocking);
    }

    @Test
    @DisplayName("isServerBoundaryMethod correctly identifies server loop boundaries")
    void serverBoundaryMethodsIdentified() {
        assertTrue(SyncLoadAttributor.isServerBoundaryMethod("tickServer"));
        assertTrue(SyncLoadAttributor.isServerBoundaryMethod("runServer"));
        assertTrue(SyncLoadAttributor.isServerBoundaryMethod("tickChildren"));
        assertTrue(SyncLoadAttributor.isServerBoundaryMethod("pollTask"));
        assertTrue(SyncLoadAttributor.isServerBoundaryMethod("executeTask"));
        assertTrue(SyncLoadAttributor.isServerBoundaryMethod("runAllTasks"));
        assertTrue(SyncLoadAttributor.isServerBoundaryMethod("pollNextChunkTask"));
        assertTrue(SyncLoadAttributor.isServerBoundaryMethod("mainThreadHeartbeat"));

        assertFalse(SyncLoadAttributor.isServerBoundaryMethod("getChunkAt"));
        assertFalse(SyncLoadAttributor.isServerBoundaryMethod("onChunkLoad"));
    }

    @Test
    @DisplayName("sampleStride clamps to >= 1")
    void sampleStrideClamped() {
        org.bukkit.plugin.Plugin mockPlugin = (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.plugin.Plugin.class.getClassLoader(),
                new Class<?>[]{org.bukkit.plugin.Plugin.class},
                (p, m, a) -> "getName".equals(m.getName()) ? "StressTestRTP" : null);
        SyncLoadAttributor attributor = new SyncLoadAttributor(mockPlugin);
        assertEquals(1, attributor.sampleStride());

        attributor.setSampleStride(5);
        assertEquals(5, attributor.sampleStride());

        attributor.setSampleStride(0);
        assertEquals(1, attributor.sampleStride());

        attributor.setSampleStride(-10);
        assertEquals(1, attributor.sampleStride());
    }
}
