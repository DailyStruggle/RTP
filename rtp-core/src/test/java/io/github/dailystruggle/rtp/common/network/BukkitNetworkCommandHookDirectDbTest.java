package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.api.network.NetworkCommandHook;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BukkitNetworkCommandHookDirectDbTest {

    private NetworkRouter router;
    private NetworkEnrolmentBuffer buffer;
    private DirectDatabaseDispatcher mockDispatcher;
    private BukkitNetworkCommandHook hook;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        playerId = UUID.randomUUID();
        BackendHeartbeat peer = new BackendHeartbeat(
                "backend-a", 1, BackendHeartbeat.PluginState.READY, true,
                System.currentTimeMillis(), 5.0, 0, 100, 0L, 1L, 0,
                List.of(), List.of(),
                false, 0, 0, Set.of("default"), Map.of());
        NetworkSnapshot snap = new NetworkSnapshot(System.currentTimeMillis(), Map.of("backend-a", peer));
        router = new NetworkRouter(
                "local-1", NetworkRouter.Mode.CROSS_SERVER,
                () -> snap,
                () -> 0, () -> 0, 50,
                100, 100,
                System::currentTimeMillis);

        buffer = new NetworkEnrolmentBuffer(batch -> {}, 100);
        mockDispatcher = mock(DirectDatabaseDispatcher.class);
        hook = new BukkitNetworkCommandHook(router, buffer);
    }

    @Test
    void route_withoutDirectDispatcher_offersToEnrolmentBuffer() {
        var result = hook.route(playerId, Map.of("region", List.of("backend-a:default")));
        assertInstanceOf(NetworkCommandHook.RoutingResult.CrossServer.class, result);

        assertEquals(1, buffer.pendingDepth());
        verifyNoInteractions(mockDispatcher);
    }

    @Test
    void route_withDirectDispatcher_dispatchesDirectlyBypassingEnrolmentBuffer() {
        hook.setDirectDatabaseDispatcher(mockDispatcher);
        assertSame(mockDispatcher, hook.directDatabaseDispatcher());

        when(mockDispatcher.dispatch(eq(playerId), eq(Optional.of("default")), eq(Optional.of("backend-a"))))
                .thenReturn(CompletableFuture.completedFuture(DirectDatabaseDispatcher.DispatchResult.success("backend-a", "default")));

        var result = hook.route(playerId, Map.of("region", List.of("backend-a:default")));
        assertInstanceOf(NetworkCommandHook.RoutingResult.CrossServer.class, result);

        verify(mockDispatcher).dispatch(playerId, Optional.of("default"), Optional.of("backend-a"));
        assertEquals(0, buffer.pendingDepth());
    }
}
