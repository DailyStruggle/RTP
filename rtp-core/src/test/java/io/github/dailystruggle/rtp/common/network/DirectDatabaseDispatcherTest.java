package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.network.pluginmessage.NetworkBridge;
import io.github.dailystruggle.rtp.proxy.common.selector.ServerRegion;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectDatabaseDispatcherTest {

    private NetworkTransport mockTransport;
    private NetworkBridge mockBridge;
    private RTPServerAccessor mockAccessor;
    private RTPPlayer mockPlayer;
    private PeerRegionRegistry mockRegistry;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        playerId = UUID.randomUUID();
        mockTransport = mock(NetworkTransport.class);
        mockBridge = mock(NetworkBridge.class);
        mockAccessor = mock(RTPServerAccessor.class);
        mockPlayer = mock(RTPPlayer.class);
        mockRegistry = mock(PeerRegionRegistry.class);

        when(mockPlayer.isOnline()).thenReturn(true);
        when(mockAccessor.getPlayer(playerId)).thenReturn(mockPlayer);
        RTP.serverAccessor = mockAccessor;
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
    }

    @Test
    void dispatch_withServerHint_successfulClaimAndConnect() {
        ReservationToken token = new ReservationToken("tok-1", "backend-dest", playerId, 5000L, ReservationToken.State.CLAIMED, "nether");
        when(mockTransport.claim(eq("backend-dest"), eq(playerId), any(Duration.class), eq(Optional.of("nether"))))
                .thenReturn(CompletableFuture.completedFuture(token));

        AtomicBoolean failureReported = new AtomicBoolean(false);
        DirectDatabaseDispatcher dispatcher = new DirectDatabaseDispatcher(
                mockTransport,
                () -> NetworkSnapshot.empty(100L),
                mockRegistry,
                mockBridge,
                "backend-origin",
                Duration.ofSeconds(30),
                uuid -> failureReported.set(true)
        );

        CompletableFuture<DirectDatabaseDispatcher.DispatchResult> future =
                dispatcher.dispatch(playerId, Optional.of("nether"), Optional.of("backend-dest"));

        DirectDatabaseDispatcher.DispatchResult result = future.join();
        assertEquals(DirectDatabaseDispatcher.ResultStatus.SUCCESS, result.status());
        assertEquals("backend-dest", result.targetServerId());
        assertEquals("nether", result.targetRegionKey());

        verify(mockTransport).claim(eq("backend-dest"), eq(playerId), any(Duration.class), eq(Optional.of("nether")));
        verify(mockRegistry).recordDispatch("backend-dest", "nether");
        verify(mockBridge).connect(playerId, "backend-dest");
        assertFalse(failureReported.get());
    }

    @Test
    void dispatch_withoutServerHint_usesPeerRegionRegistryPick() {
        when(mockRegistry.pickMostKept()).thenReturn(Optional.of(new ServerRegion("backend-chosen", "survival")));
        ReservationToken token = new ReservationToken("tok-2", "backend-chosen", playerId, 5000L, ReservationToken.State.CLAIMED, "survival");
        when(mockTransport.claim(eq("backend-chosen"), eq(playerId), any(Duration.class), eq(Optional.of("survival"))))
                .thenReturn(CompletableFuture.completedFuture(token));

        DirectDatabaseDispatcher dispatcher = new DirectDatabaseDispatcher(
                mockTransport,
                () -> NetworkSnapshot.empty(100L),
                mockRegistry,
                mockBridge,
                "backend-origin",
                Duration.ofSeconds(30),
                uuid -> {}
        );

        CompletableFuture<DirectDatabaseDispatcher.DispatchResult> future =
                dispatcher.dispatch(playerId, Optional.empty(), Optional.empty());

        DirectDatabaseDispatcher.DispatchResult result = future.join();
        assertEquals(DirectDatabaseDispatcher.ResultStatus.SUCCESS, result.status());
        assertEquals("backend-chosen", result.targetServerId());
        assertEquals("survival", result.targetRegionKey());

        verify(mockBridge).connect(playerId, "backend-chosen");
    }

    @Test
    void dispatch_playerOffline_failsImmediately() {
        when(mockPlayer.isOnline()).thenReturn(false);
        AtomicBoolean failureReported = new AtomicBoolean(false);

        DirectDatabaseDispatcher dispatcher = new DirectDatabaseDispatcher(
                mockTransport,
                () -> NetworkSnapshot.empty(100L),
                mockRegistry,
                mockBridge,
                "backend-origin",
                Duration.ofSeconds(30),
                uuid -> failureReported.set(true)
        );

        CompletableFuture<DirectDatabaseDispatcher.DispatchResult> future =
                dispatcher.dispatch(playerId, Optional.of("nether"), Optional.of("backend-dest"));

        DirectDatabaseDispatcher.DispatchResult result = future.join();
        assertEquals(DirectDatabaseDispatcher.ResultStatus.PLAYER_OFFLINE, result.status());
        assertTrue(failureReported.get());
        verifyNoInteractions(mockTransport);
        verifyNoInteractions(mockBridge);
    }

    @Test
    void dispatch_noCandidateAvailable_failsWithoutClaim() {
        when(mockRegistry.pickMostKept()).thenReturn(Optional.empty());
        AtomicBoolean failureReported = new AtomicBoolean(false);

        DirectDatabaseDispatcher dispatcher = new DirectDatabaseDispatcher(
                mockTransport,
                () -> NetworkSnapshot.empty(100L),
                mockRegistry,
                mockBridge,
                "backend-origin",
                Duration.ofSeconds(30),
                uuid -> failureReported.set(true)
        );

        CompletableFuture<DirectDatabaseDispatcher.DispatchResult> future =
                dispatcher.dispatch(playerId, Optional.empty(), Optional.empty());

        DirectDatabaseDispatcher.DispatchResult result = future.join();
        assertEquals(DirectDatabaseDispatcher.ResultStatus.NO_CANDIDATE, result.status());
        assertTrue(failureReported.get());
        verifyNoInteractions(mockTransport);
        verifyNoInteractions(mockBridge);
    }

    @Test
    void dispatch_claimFails_handlesErrorAndNotifies() {
        when(mockTransport.claim(anyString(), eq(playerId), any(Duration.class), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("SQL timeout")));

        AtomicBoolean failureReported = new AtomicBoolean(false);
        DirectDatabaseDispatcher dispatcher = new DirectDatabaseDispatcher(
                mockTransport,
                () -> NetworkSnapshot.empty(100L),
                mockRegistry,
                mockBridge,
                "backend-origin",
                Duration.ofSeconds(30),
                uuid -> failureReported.set(true)
        );

        CompletableFuture<DirectDatabaseDispatcher.DispatchResult> future =
                dispatcher.dispatch(playerId, Optional.of("nether"), Optional.of("backend-dest"));

        DirectDatabaseDispatcher.DispatchResult result = future.join();
        assertEquals(DirectDatabaseDispatcher.ResultStatus.CLAIM_FAILED, result.status());
        assertTrue(failureReported.get());
        verifyNoInteractions(mockBridge);
    }

    @Test
    void dispatch_claimReturnsNullToken_failsGracefully() {
        when(mockTransport.claim(anyString(), eq(playerId), any(Duration.class), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        AtomicBoolean failureReported = new AtomicBoolean(false);
        DirectDatabaseDispatcher dispatcher = new DirectDatabaseDispatcher(
                mockTransport,
                () -> NetworkSnapshot.empty(100L),
                mockRegistry,
                mockBridge,
                "backend-origin",
                Duration.ofSeconds(30),
                uuid -> failureReported.set(true)
        );

        CompletableFuture<DirectDatabaseDispatcher.DispatchResult> future =
                dispatcher.dispatch(playerId, Optional.of("nether"), Optional.of("backend-dest"));

        DirectDatabaseDispatcher.DispatchResult result = future.join();
        assertEquals(DirectDatabaseDispatcher.ResultStatus.CLAIM_FAILED, result.status());
        assertTrue(failureReported.get());
        verifyNoInteractions(mockBridge);
    }

    @Test
    void dispatch_bridgeNull_failsGracefully() {
        AtomicBoolean failureReported = new AtomicBoolean(false);
        DirectDatabaseDispatcher dispatcher = new DirectDatabaseDispatcher(
                mockTransport,
                () -> NetworkSnapshot.empty(100L),
                mockRegistry,
                null,
                "backend-origin",
                Duration.ofSeconds(30),
                uuid -> failureReported.set(true)
        );

        CompletableFuture<DirectDatabaseDispatcher.DispatchResult> future =
                dispatcher.dispatch(playerId, Optional.of("nether"), Optional.of("backend-dest"));

        DirectDatabaseDispatcher.DispatchResult result = future.join();
        assertEquals(DirectDatabaseDispatcher.ResultStatus.BRIDGE_UNAVAILABLE, result.status());
        assertTrue(failureReported.get());
    }
}
