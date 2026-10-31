package io.github.dailystruggle.rtp.neoforge.v1_21_R1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.neoforge.version.NeoForgeVersionAdapter;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-RTP-S-002 / RTP-80 regression guard: on 1.21.1 {@code DistanceManager} declares
 * {@code addTicket(TicketType, ChunkPos, int level, T)} and
 * {@code removeTicket(TicketType, ChunkPos, int level, T)} with the same 4-arg signature before
 * {@code addRegionTicket(TicketType, ChunkPos, int distance, T)} and
 * {@code removeRegionTicket(TicketType, ChunkPos, int distance, T)}. Binding {@code addTicket}
 * passes {@code ticketDistance} (0 or 1) as raw ticket level (0 or 1), triggering a radius 32-33
 * generation cascade (~4,225 chunks) per ticket.
 */
class V1_21_R1TicketMethodSelectionTest {

    static final class StubTicketType<T> {}

    static final class StubChunkPos {}

    @SuppressWarnings("unused")
    static final class ShapedLikeDistanceManager {
        public <T> void addTicket(StubTicketType<T> type, StubChunkPos pos, int level, T value) {}

        public <T> void removeTicket(StubTicketType<T> type, StubChunkPos pos, int level, T value) {}

        public <T> void addRegionTicket(StubTicketType<T> type, StubChunkPos pos, int distance, T value) {}

        public <T> void removeRegionTicket(StubTicketType<T> type, StubChunkPos pos, int distance, T value) {}
    }

    @Test
    @DisplayName("REQ-RTP-S-002 / RTP-80: legacy ticket scan binds only addRegionTicket and removeRegionTicket")
    void bindsOnlyRegionTicketMethods() throws Exception {
        List<String> addMatches = new ArrayList<>();
        List<String> removeMatches = new ArrayList<>();
        int fourArgTicketMethods = 0;
        for (Method m : ShapedLikeDistanceManager.class.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 4 && p[0] == StubTicketType.class && p[1] == StubChunkPos.class && p[2] == int.class) {
                fourArgTicketMethods++;
            }
            if (NeoForgeVersionAdapter.isLegacyRegionTicketMethod(
                    m, true, StubTicketType.class, StubChunkPos.class)) {
                addMatches.add(m.getName());
            }
            if (NeoForgeVersionAdapter.isLegacyRegionTicketMethod(
                    m, false, StubTicketType.class, StubChunkPos.class)) {
                removeMatches.add(m.getName());
            }
        }
        assertEquals(4, fourArgTicketMethods, "fixture must declare both addTicket/removeTicket and addRegionTicket/removeRegionTicket");
        assertEquals(List.of("addRegionTicket"), addMatches);
        assertEquals(List.of("removeRegionTicket"), removeMatches);

        // Verify V1_21_R1NeoForgeVersionAdapter compiled bytecode delegates to isLegacyRegionTicketMethod
        // and allocates a distinct rtp_load ticket type.
        String resource = "io/github/dailystruggle/rtp/neoforge/v1_21_R1/V1_21_R1NeoForgeVersionAdapter.class";
        URL url = V1_21_R1TicketMethodSelectionTest.class.getClassLoader().getResource(resource);
        if (url != null && "file".equals(url.getProtocol())) {
            Path classFile = Paths.get(url.toURI());
            String classBytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            assertTrue(classBytes.contains("isLegacyRegionTicketMethod"),
                    "V1_21_R1NeoForgeVersionAdapter must filter legacy ticket methods via isLegacyRegionTicketMethod");
            assertTrue(classBytes.contains("rtp_load"),
                    "V1_21_R1NeoForgeVersionAdapter must allocate a separate rtp_load ticket type for temporary loads");
        }
    }
}
