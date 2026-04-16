package io.github.dailystruggle.rtp.common.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisManagerTest {

    @Mock
    private JedisPool mockPool;

    @Mock
    private Jedis mockJedis;

    private AutoCloseable mocks;
    private RedisManager manager;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(mockPool.getResource()).thenReturn(mockJedis);
        manager = new RedisManager(mockPool);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    // --- Pool initialization via package-private constructor ---

    @Test
    void testPoolInjection_notNull() {
        assertNotNull(manager);
    }

    // --- publish ---

    @Test
    void testPublish_callsJedisPublishWithCorrectArgs() {
        String channel = "rtp:rpc";
        String payload = "{\"action\":\"teleport\"}";

        manager.publish(channel, payload);

        verify(mockJedis).publish(channel, payload);
    }

    @Test
    void testPublish_closesJedisResource() {
        manager.publish("chan", "msg");

        verify(mockJedis).close();
    }

    // --- setCooldown ---

    @Test
    void testSetCooldown_correctKeyFormat() {
        UUID id = UUID.randomUUID();
        manager.setCooldown(id, 300L);

        String expectedKey = "rtp:cooldown:" + id;
        verify(mockJedis).setex(expectedKey, 300L, "true");
    }

    @Test
    void testSetCooldown_closesJedisResource() {
        manager.setCooldown(UUID.randomUUID(), 60L);
        verify(mockJedis).close();
    }

    // --- getCooldown ---

    @Test
    void testGetCooldown_correctKeyFormat() {
        UUID id = UUID.randomUUID();
        when(mockJedis.ttl(anyString())).thenReturn(120L);

        long result = manager.getCooldown(id);

        String expectedKey = "rtp:cooldown:" + id;
        verify(mockJedis).ttl(expectedKey);
        assertEquals(120L, result);
    }

    @Test
    void testGetCooldown_closesJedisResource() {
        when(mockJedis.ttl(anyString())).thenReturn(0L);
        manager.getCooldown(UUID.randomUUID());
        verify(mockJedis).close();
    }

    // --- initializeAsync / onMessage ---

    @Test
    void testInitializeAsync_subscribesToRpcChannel() throws InterruptedException {
        doAnswer(invocation -> {
            JedisPubSub pubSub = invocation.getArgument(0);
            pubSub.onMessage("rtp:rpc", "{\"action\":\"test\"}");
            return null;
        }).when(mockJedis).subscribe(any(JedisPubSub.class), eq("rtp:rpc"));

        manager.initializeAsync();

        Thread.sleep(300);

        verify(mockJedis).subscribe(any(JedisPubSub.class), eq("rtp:rpc"));
    }

    @Test
    void testInitializeAsync_exceptionIsCaughtGracefully() throws InterruptedException {
        doThrow(new RuntimeException("connection lost"))
                .when(mockJedis).subscribe(any(JedisPubSub.class), anyString());

        assertDoesNotThrow(() -> manager.initializeAsync());

        Thread.sleep(300);

        verify(mockJedis).subscribe(any(JedisPubSub.class), eq("rtp:rpc"));
    }

    // --- connection failure fallback (getResource throws) ---

    @Test
    void testPublish_getResourceThrows_propagatesException() {
        when(mockPool.getResource()).thenThrow(new RuntimeException("pool exhausted"));
        assertThrows(RuntimeException.class, () -> manager.publish("chan", "msg"));
    }

    @Test
    void testSetCooldown_getResourceThrows_propagatesException() {
        when(mockPool.getResource()).thenThrow(new RuntimeException("pool exhausted"));
        assertThrows(RuntimeException.class, () -> manager.setCooldown(UUID.randomUUID(), 60L));
    }

    @Test
    void testGetCooldown_getResourceThrows_propagatesException() {
        when(mockPool.getResource()).thenThrow(new RuntimeException("pool exhausted"));
        assertThrows(RuntimeException.class, () -> manager.getCooldown(UUID.randomUUID()));
    }

    @Test
    void testInitializeAsync_getResourceThrows_exceptionCaught() throws InterruptedException {
        when(mockPool.getResource()).thenThrow(new RuntimeException("pool exhausted"));
        assertDoesNotThrow(() -> manager.initializeAsync());
        Thread.sleep(300);
    }

    // --- shutdown ---

    @Test
    void testShutdown_closesPool() {
        manager.shutdown();
        verify(mockPool).close();
    }

    @Test
    void testShutdown_nullPool_doesNotThrow() throws Exception {
        var field = RedisManager.class.getDeclaredField("pool");
        field.setAccessible(true);
        RedisManager nullPoolManager = new RedisManager(mockPool);
        field.set(nullPoolManager, null);
        assertDoesNotThrow(nullPoolManager::shutdown);
    }

    // --- channel string correctness ---

    @Test
    void testPublish_rpcChannelString() {
        ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        manager.publish("rtp:rpc", "payload");

        verify(mockJedis).publish(channelCaptor.capture(), payloadCaptor.capture());
        assertEquals("rtp:rpc", channelCaptor.getValue());
        assertEquals("payload", payloadCaptor.getValue());
    }
}
