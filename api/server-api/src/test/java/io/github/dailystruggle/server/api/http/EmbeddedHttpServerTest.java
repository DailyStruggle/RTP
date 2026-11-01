package io.github.dailystruggle.server.api.http;

import io.github.dailystruggle.server.api.http.EmbeddedHttpServer.HttpResponsePayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link EmbeddedHttpServer} route dispatch, query/path segment parsing, SSE framing,
 * and JSON escaping ({@code ADR-115}, {@code ADR-116}, {@code leafrtp-ADR-067}).
 */
@DisplayName("EmbeddedHttpServer shared library tests")
class EmbeddedHttpServerTest {

    @Test
    @DisplayName("EmbeddedHttpServer dispatches exact and prefix routes, parses queries, and frames JSON/HTML/SSE responses")
    void routeDispatchAndFraming() {
        try (EmbeddedHttpServer server = new EmbeddedHttpServer("TestHttp")
                .onExact("/api/status", req -> HttpResponsePayload.json(
                        200,
                        "{\"world\":\"" + EmbeddedHttpServer.escapeJson(req.queryParam("world", "world"))
                                + "\",\"lod\":" + req.queryInt("lod", 3) + "}"
                ))
                .onExact("/api/events", req -> HttpResponsePayload.sse("live", "{\"ok\":true}"))
                .onPrefix("/api/world/", req -> HttpResponsePayload.html(200, String.join("/", req.pathSegments())))) {

            HttpResponsePayload status = server.dispatchRequest("GET", "/api/status?world=world_nether&lod=2");
            assertEquals(200, status.statusCode());
            assertTrue(status.contentType().startsWith("application/json"));
            assertEquals("{\"world\":\"world_nether\",\"lod\":2}", status.bodyUtf8());

            HttpResponsePayload sse = server.dispatchRequest("GET", "/api/events");
            assertEquals(200, sse.statusCode());
            assertTrue(sse.contentType().startsWith("text/event-stream"));
            assertTrue(sse.bodyUtf8().contains("event: live\ndata: {\"ok\":true}\n\n"));

            HttpResponsePayload prefix = server.dispatchRequest("GET", "/api/world/world/tile/2/0/0.png");
            assertEquals(200, prefix.statusCode());
            assertEquals("api/world/world/tile/2/0/0.png", prefix.bodyUtf8());

            HttpResponsePayload notFound = server.dispatchRequest("GET", "/unknown");
            assertEquals(404, notFound.statusCode());

            HttpResponsePayload methodNotAllowed = server.dispatchRequest("POST", "/api/status");
            assertEquals(405, methodNotAllowed.statusCode());
        }
    }
}
