package io.github.dailystruggle.server.api.http;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reusable zero-dependency embedded HTTP, REST, binary tile, and Server-Sent Events (SSE)
 * server substrate ({@code ADR-115}, {@code ADR-116}, {@code leafrtp-ADR-067}, {@code leafmap-ADR-001}).
 *
 * <p>Consolidates HTTP socket binding, bounded daemon worker pools, route matching, query-string
 * parsing, CORS / cache-control headers, and deterministic in-memory request dispatch so sibling
 * products ({@code LeafMap}, {@code LeafConfig}, {@code LeafSleuth}, {@code LeafRTP}) do not
 * duplicate HTTP server boilerplate.
 *
 * @governedBy ADR-115
 * @governedBy ADR-116
 * @governedBy leafrtp-ADR-067
 * @governedBy leafmap-ADR-001
 * @trace REQ-RTP-MAP-001
 * @trace REQ-RTP-MAP-002
 * @trace S-004
 * @trace S-005
 */
public final class EmbeddedHttpServer implements AutoCloseable {

    /**
     * Parsed immutable HTTP request context passed to registered {@link RouteHandler}s.
     *
     * @param method          uppercase HTTP method (e.g. {@code "GET"})
     * @param rawPathAndQuery raw URI path and query string
     * @param path            normalized URI path without query string
     * @param queryParams     parsed query key-value pairs
     * @param pathSegments    non-empty slash-separated path segments
     */
    public record HttpRequestContext(
            String method,
            String rawPathAndQuery,
            String path,
            Map<String, String> queryParams,
            List<String> pathSegments
    ) {
        public HttpRequestContext {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(rawPathAndQuery, "rawPathAndQuery");
            Objects.requireNonNull(path, "path");
            queryParams = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(queryParams, "queryParams")
            ));
            pathSegments = List.copyOf(Objects.requireNonNull(pathSegments, "pathSegments"));
        }

        public String queryParam(String key, String defaultValue) {
            return queryParams.getOrDefault(key, defaultValue);
        }

        public int queryInt(String key, int defaultValue) {
            return parseInt(queryParams.get(key), defaultValue);
        }
    }

    /**
     * Immutable HTTP response payload returned by {@link RouteHandler} and
     * {@link #dispatchRequest(String, String)}.
     */
    public record HttpResponsePayload(int statusCode, String contentType, byte[] body) {
        public HttpResponsePayload {
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(body, "body");
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof HttpResponsePayload that)) return false;
            return statusCode == that.statusCode
                    && contentType.equals(that.contentType)
                    && java.util.Arrays.equals(body, that.body);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(statusCode, contentType);
            return 31 * result + java.util.Arrays.hashCode(body);
        }

        @Override
        public String toString() {
            return "HttpResponsePayload[statusCode=" + statusCode
                    + ", contentType=" + contentType
                    + ", body=" + java.util.Arrays.toString(body)
                    + "]";
        }

        public String bodyUtf8() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public static HttpResponsePayload json(int statusCode, String jsonBody) {
            return new HttpResponsePayload(
                    statusCode,
                    "application/json; charset=utf-8",
                    Objects.requireNonNullElse(jsonBody, "{}").getBytes(StandardCharsets.UTF_8)
            );
        }

        public static HttpResponsePayload html(int statusCode, String htmlBody) {
            return new HttpResponsePayload(
                    statusCode,
                    "text/html; charset=utf-8",
                    Objects.requireNonNullElse(htmlBody, "").getBytes(StandardCharsets.UTF_8)
            );
        }

        public static HttpResponsePayload binary(int statusCode, String contentType, byte[] data) {
            return new HttpResponsePayload(statusCode, contentType, Objects.requireNonNullElse(data, new byte[0]));
        }

        public static HttpResponsePayload sse(String eventName, String dataJson) {
            String ev = (eventName == null || eventName.isBlank()) ? "message" : eventName.trim();
            String payload = "event: " + ev + "\ndata: " + Objects.requireNonNullElse(dataJson, "{}") + "\n\n";
            return new HttpResponsePayload(
                    200,
                    "text/event-stream; charset=utf-8",
                    payload.getBytes(StandardCharsets.UTF_8)
            );
        }
    }

    /**
     * Functional route handler for servicing HTTP requests off the main server thread.
     */
    @FunctionalInterface
    public interface RouteHandler {
        @SuppressWarnings("java:S112")
        HttpResponsePayload handle(HttpRequestContext request) throws Exception;
    }

    private record RouteEntry(String prefix, boolean exactMatch, RouteHandler handler) {
    }

    private final String threadPoolPrefix;
    private final List<RouteEntry> routes = new CopyOnWriteArrayList<>();
    private HttpServer httpServer;
    private ExecutorService httpExecutor;
    private int boundPort = -1;

    public EmbeddedHttpServer(String threadPoolPrefix) {
        this.threadPoolPrefix = (threadPoolPrefix == null || threadPoolPrefix.isBlank())
                ? "LeafHttp"
                : threadPoolPrefix.trim();
    }

    /**
     * Registers an exact-path route handler (e.g. {@code "/api/worlds"}).
     */
    public EmbeddedHttpServer onExact(String exactPath, RouteHandler handler) {
        Objects.requireNonNull(exactPath, "exactPath");
        Objects.requireNonNull(handler, "handler");
        routes.add(new RouteEntry(exactPath, true, handler));
        return this;
    }

    /**
     * Registers a path-prefix route handler (e.g. {@code "/api/world/"}).
     */
    public EmbeddedHttpServer onPrefix(String pathPrefix, RouteHandler handler) {
        Objects.requireNonNull(pathPrefix, "pathPrefix");
        Objects.requireNonNull(handler, "handler");
        routes.add(new RouteEntry(pathPrefix, false, handler));
        return this;
    }

    /**
     * Starts the underlying JDK {@link HttpServer} on {@code host:port} (`port = 0` selects an
     * ephemeral loopback port).
     */
    public synchronized int start(String host, int port, int workerThreads) throws IOException {
        if (httpServer != null) {
            return boundPort;
        }
        String bindHost = (host == null || host.isBlank()) ? "127.0.0.1" : host.trim();
        int threads = Math.max(1, Math.min(8, workerThreads));
        AtomicInteger threadId = new AtomicInteger(1);
        this.httpExecutor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, threadPoolPrefix + "-" + threadId.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        this.httpServer = HttpServer.create(new InetSocketAddress(bindHost, Math.max(0, port)), 32);
        this.httpServer.setExecutor(this.httpExecutor);
        this.httpServer.createContext("/", exchange -> {
            try {
                URI uri = exchange.getRequestURI();
                String pathAndQuery = uri.getRawPath()
                        + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
                HttpResponsePayload resp = dispatchRequest(exchange.getRequestMethod(), pathAndQuery);
                byte[] bodyBytes = resp.body;
                exchange.getResponseHeaders().set("Content-Type", resp.contentType());
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(resp.statusCode(), bodyBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bodyBytes);
                }
            } finally {
                exchange.close();
            }
        });
        this.httpServer.start();
        this.boundPort = this.httpServer.getAddress().getPort();
        return this.boundPort;
    }

    public synchronized boolean isRunning() {
        return httpServer != null;
    }

    public synchronized int boundPort() {
        return boundPort;
    }

    @Override
    public synchronized void close() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
        boundPort = -1;
    }

    /**
     * Dispatches an HTTP request against the registered route table in memory (used by both live
     * socket connections and deterministic socket-free unit/PBIT tests).
     */
    public HttpResponsePayload dispatchRequest(String method, String pathAndQuery) {
        String reqMethod = (method == null || method.isBlank()) ? "GET" : method.toUpperCase(Locale.ROOT);
        if (!"GET".equals(reqMethod) && !"HEAD".equals(reqMethod)) {
            return HttpResponsePayload.json(405, "{\"error\":\"Method Not Allowed\"}");
        }
        String raw = Objects.requireNonNullElse(pathAndQuery, "/");
        int qIdx = raw.indexOf('?');
        String path = qIdx >= 0 ? raw.substring(0, qIdx) : raw;
        if (path.isEmpty()) {
            path = "/";
        }
        Map<String, String> query = qIdx >= 0 ? parseQuery(raw.substring(qIdx + 1)) : Map.of();
        List<String> segments = splitSegments(path);
        HttpRequestContext ctx = new HttpRequestContext(reqMethod, raw, path, query, segments);

        try {
            for (RouteEntry route : routes) {
                if (route.exactMatch && route.prefix.equals(path)) {
                    return route.handler.handle(ctx);
                }
            }
            for (RouteEntry route : routes) {
                if (!route.exactMatch && path.startsWith(route.prefix)) {
                    return route.handler.handle(ctx);
                }
            }
            return HttpResponsePayload.json(404, "{\"error\":\"Not Found\",\"path\":\"" + escapeJson(path) + "\"}");
        } catch (Exception ex) {
            return HttpResponsePayload.json(500, "{\"error\":\"" + escapeJson(String.valueOf(ex.getMessage())) + "\"}");
        }
    }

    public static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            } else if (!pair.isBlank()) {
                out.put(pair.trim(), "");
            }
        }
        return out;
    }

    public static List<String> splitSegments(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : path.split("/")) {
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    public static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    public static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
