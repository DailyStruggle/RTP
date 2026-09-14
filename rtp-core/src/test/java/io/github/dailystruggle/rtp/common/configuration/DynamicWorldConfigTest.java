package io.github.dailystruggle.rtp.common.configuration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

public class DynamicWorldConfigTest {
    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private Configs configs;
    private MultiConfigParser<WorldKeys> mockMulti;
    private ConfigParser<WorldKeys> mockWorldParser;

    @BeforeEach
    void setUp() {
        // "world" is registered by default in MockRTPServerAccessor
        accessor = RTPTestSetup.install(tempDir.toFile());
        RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();

        // Initialize Configs
        configs = new Configs(tempDir.toFile());
        RTP.configs = configs;
        mockMulti = mock(MultiConfigParser.class);
        mockMulti.configParserFactory = new io.github.dailystruggle.rtp.common.factory.Factory<>();
        configs.multiConfigParserMap.put(WorldKeys.class, mockMulti);

        mockWorldParser = mock(ConfigParser.class);
        mockWorldParser.name = "default.yml";
        mockMulti.configParserFactory.add("default.yml", mockWorldParser);
        when(mockMulti.getParser(anyString())).thenReturn(mockWorldParser);
        doReturn(new java.util.EnumMap<>(WorldKeys.class)).when(mockWorldParser).getData();
        doReturn(0).when(mockWorldParser).getNumber(any(), any());
        doReturn(false).when(mockWorldParser).getConfigValue(any(), any());

        // Prevent real ConfigParser instantiation and disk creation in addParser
        doAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            if (arg instanceof ConfigParser<?> parser) {
                mockMulti.configParserFactory.add(parser.name, (ConfigParser<WorldKeys>) parser);
            } else if (arg instanceof String name) {
                mockMulti.configParserFactory.add(name + ".yml", mockWorldParser);
            }
            return null;
        }).when(mockMulti).addParser(any(ConfigParser.class));
        doAnswer(invocation -> {
            String name = invocation.getArgument(0);
            mockMulti.configParserFactory.add(name + ".yml", mockWorldParser);
            return null;
        }).when(mockMulti).addParser(anyString());
    }

    @Test
    void testDynamicWorldLoading() {
        String runtimeWorldName = "runtime_generated_dimension";

        // Ensure it doesn't exist yet in the accessor (not registered → returns null)
        assertNull(configs.getWorldParser(runtimeWorldName));

        // Register the world in the accessor to simulate it coming online at runtime
        accessor.addWorld(new MockRTPWorld(runtimeWorldName));

        // Invoke Configs.getWorldParser and assert it's successfully instantiated
        ConfigParser<WorldKeys> runtimeParser = mock(ConfigParser.class);
        runtimeParser.name = runtimeWorldName + ".yml";
        doReturn(new java.util.EnumMap<>(WorldKeys.class)).when(runtimeParser).getData();
        mockMulti.configParserFactory.add(runtimeWorldName + ".yml", runtimeParser);
        when(mockMulti.getParser(runtimeWorldName)).thenReturn(runtimeParser);

        ConfigParser<WorldKeys> parser = configs.getWorldParser(runtimeWorldName);
        assertNotNull(parser, "Parser should be dynamically created for new world");
        assertEquals(runtimeWorldName, parser.name.replace(".yml", ""));

        // Verify it's added to MultiConfigParser's internal factory
        MultiConfigParser<WorldKeys> multiConfigParser = (MultiConfigParser<WorldKeys>) configs.getParser(WorldKeys.class);
        assertNotNull(multiConfigParser);
        assertTrue(multiConfigParser.configParserFactory.contains(runtimeWorldName.toUpperCase() + ".YML"));

        // Assert it can be retrieved again
        assertSame(parser, configs.getWorldParser(runtimeWorldName));
    }

    @Test
    void testUnknownWorldReturnsNull() {
        assertNull(configs.getWorldParser("non_existent_world"));
        assertNull(configs.getWorldParserValue("non_existent_world", WorldKeys.requirePermission));
    }

    @Test
    void testGetWorldParserValueForRegisteredWorld() {
        // "world" is already registered in MockRTPServerAccessor by RTPTestSetup
        assertNotNull(accessor.getRTPWorld("world"));
        mockMulti.configParserFactory.add("world.yml", mockWorldParser);
        Object val = configs.getWorldParserValue("world", WorldKeys.requirePermission);
        assertNotNull(val);
    }

    @Test
    void testWorldRegistrationAndDeregistration() {
        String customWorld = "dynamic_nether";
        assertNull(configs.getWorldParser(customWorld));

        // Register
        MockRTPWorld worldObj = new MockRTPWorld(customWorld);
        accessor.addWorld(worldObj);
        assertNotNull(accessor.getRTPWorld(customWorld));

        mockMulti.configParserFactory.add(customWorld + ".yml", mockWorldParser);
        ConfigParser<WorldKeys> parser = configs.getWorldParser(customWorld);
        assertNotNull(parser);

        // Deregister (clear worlds from server accessor)
        accessor.clearWorlds();
        // After deregistration, getWorldParser returns null because world is not in server accessor
        assertNull(configs.getWorldParser(customWorld));
        assertNull(configs.getWorldParserValue(customWorld, WorldKeys.requirePermission));
    }

    @Test
    void testConcurrentWorldRegistration() throws InterruptedException {
        int threadCount = 4;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    String name = "concurrent_world_" + index;
                    synchronized (accessor) {
                        accessor.addWorld(new MockRTPWorld(name));
                    }
                    synchronized (mockMulti.configParserFactory) {
                        mockMulti.configParserFactory.add(name + ".yml", mockWorldParser);
                    }
                    ConfigParser<WorldKeys> p = configs.getWorldParser(name);
                    if (p == null) errors.incrementAndGet();
                    Object v = configs.getWorldParserValue(name, WorldKeys.requirePermission);
                    if (v == null) errors.incrementAndGet();
                } catch (Throwable t) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
        executor.shutdown();
        assertEquals(0, errors.get(), "No errors expected during concurrent world registration");
    }
}
