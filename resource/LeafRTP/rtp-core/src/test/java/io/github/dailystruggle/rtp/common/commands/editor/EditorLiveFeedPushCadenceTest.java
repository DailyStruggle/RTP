package io.github.dailystruggle.rtp.common.commands.editor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-106 §5.5: the live feed spends a channel frame on a head only when the page would see
 * something new (seq / timestamp alone do not count, telemetry gauges only beyond their jitter) or
 * as a heartbeat every {@link EditorLiveFeed#FEED_HEARTBEAT_MILLIS}; the local feed.js file is still
 * rewritten every tick.
 */
@DisplayName("ADR-106 §5.5: live feed pushes a head only on visible change or heartbeat")
class EditorLiveFeedPushCadenceTest {

    @TempDir Path tempDir;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<String> pushed = new ArrayList<>();
    private int players;
    private double tps = 20.0;
    private double mspt = 0.3;
    private double heap = 3.30;
    private EditorLiveFeed feed;
    private Path liveDir;

    private String telemetry() {
        return String.format(Locale.ROOT, "{\"players\":%d,\"tps1m\":%.2f,\"msptMean\":%.1f,\"heapUsedGb\":%.2f}",
                players, tps, mspt, heap);
    }

    @BeforeEach
    void setUp() throws IOException {
        liveDir = tempDir.resolve("editor").resolve(EditorLiveFeed.LIVE_DIR);
        feed = new EditorLiveFeed(liveDir, List.of(), List.of(), this::telemetry, 3_600_000L, clock::get);
        feed.setPush(pushed::add);
        feed.prepareDirectory();
        assertTrue(feed.tick());
        assertEquals(1, pushed.size(), "the first head is always pushed");
    }

    private void tickAfter(long millis) {
        clock.addAndGet(millis);
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
    }

    @Test
    @DisplayName("Two idle minutes with jittering TPS / MSPT / heap cost only heartbeats, not 60 heads")
    void idleJitterOnlyHeartbeats() throws IOException {
        for (int i = 1; i < 60; i++) {
            tps = (i % 2 == 0) ? 19.98 : 19.71;
            mspt = (i % 3 == 0) ? 0.6 : 0.2;
            heap = 3.30 + 0.01 * (i % 10);
            tickAfter(EditorLiveFeed.PERIOD_MILLIS);
        }
        long heartbeats = 59 * EditorLiveFeed.PERIOD_MILLIS / EditorLiveFeed.FEED_HEARTBEAT_MILLIS;
        assertEquals(1 + heartbeats, pushed.size(), "first head + one per " + EditorLiveFeed.FEED_HEARTBEAT_MILLIS + " ms heartbeat");
        assertTrue(pushed.size() <= 4, "an idle feed stays a small share of the relay budget: " + pushed.size());
        String file = Files.readString(liveDir.resolve(EditorLiveFeed.FEED_FILE), StandardCharsets.UTF_8);
        assertTrue(file.contains("\"seq\":" + feed.seq() + ","), "feed.js still rewritten every tick");
    }

    @Test
    @DisplayName("A visible change (players, TPS beyond 0.5) is pushed on the next tick")
    void visibleChangePushes() {
        tickAfter(EditorLiveFeed.PERIOD_MILLIS);
        assertEquals(1, pushed.size(), "nothing new");
        players = 1;
        tickAfter(EditorLiveFeed.PERIOD_MILLIS);
        assertEquals(2, pushed.size(), "a player joined");
        assertTrue(pushed.get(1).contains("\"players\":1"), pushed.get(1));
        tps = 18.9;
        tickAfter(EditorLiveFeed.PERIOD_MILLIS);
        assertEquals(3, pushed.size(), "TPS fell by more than 0.5");
        tps = 18.7;
        tickAfter(EditorLiveFeed.PERIOD_MILLIS);
        assertEquals(3, pushed.size(), "0.2 TPS is jitter");
    }

    @Test
    @DisplayName("Small moves add up: a gauge is compared with the last pushed value, not the previous tick")
    void driftAccumulates() {
        for (int i = 0; i < 4; i++) {
            heap += 0.1;
            tickAfter(EditorLiveFeed.PERIOD_MILLIS);
        }
        assertEquals(2, pushed.size(), "0.4 GB over four ticks crosses the 0.25 GB tolerance once");
    }

    @Test
    @DisplayName("A page focus message (sent first after (re)connecting) gets the current head at once")
    void focusForcesHead() {
        tickAfter(EditorLiveFeed.PERIOD_MILLIS);
        assertEquals(1, pushed.size());
        feed.offerClientMessage("{\"type\":\"focus\",\"world\":\"world\",\"minRx\":0,\"minRz\":0,\"maxRx\":0,\"maxRz\":0}");
        tickAfter(EditorLiveFeed.PERIOD_MILLIS);
        assertEquals(2, pushed.size(), "head re-sent for the (re)connected page");
    }

    @Test
    @DisplayName("Signature ignores seq / timestamp and gauge jitter, never other fields")
    void signature() {
        String a = "{\"sessionId\":\"s\",\"seq\":1,\"timestamp\":10,\"x\":1,\"telemetry\":{\"tps1m\":19.98,\"heapUsedGb\":3.30}}";
        String b = "{\"sessionId\":\"s\",\"seq\":7,\"timestamp\":99,\"x\":1,\"telemetry\":{\"tps1m\":19.80,\"heapUsedGb\":3.40}}";
        String c = "{\"sessionId\":\"s\",\"seq\":8,\"timestamp\":99,\"x\":2,\"telemetry\":{\"tps1m\":19.80,\"heapUsedGb\":3.40}}";
        EditorLiveFeed.FeedSignature sa = EditorLiveFeed.FeedSignature.of(a);
        assertFalse(EditorLiveFeed.FeedSignature.of(b).changedFrom(sa));
        assertTrue(EditorLiveFeed.FeedSignature.of(c).changedFrom(sa), "a non-gauge field changed");
        assertTrue(sa.changedFrom(null), "nothing pushed yet");
    }
}
