package io.github.dailystruggle.bstats.api;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;

/**
 * Dependency-free bStats v2 client; a drop-in for the upstream {@code org.bstats}
 * library on any platform in {@link BStatsPlatform}.
 *
 * <p>Wire contract mirrors upstream {@code MetricsBase}: platform fields at the
 * root, {@code service{pluginVersion,id,customCharts}}, {@code serverUUID},
 * {@code metricsVersion}; gzip POST to {@code /api/v2/data/<platform>}. bStats
 * bans services that drop the opt-out or alter cadence, so both are kept as
 * upstream: first send after a random 3-6 min, then every 30 min from a random
 * 0-30 min offset.
 *
 * <p>Threading: all work runs on the host {@link BStatsScheduler}; no threads are
 * created. Charts and {@link ServerInfo} are collected on the main thread when
 * {@code collectOnMainThread} is set (suppliers that touch a main-thread-only
 * server API), otherwise off-thread; the HTTP send is always off-thread.
 */
public final class BStatsService {

    /** Upstream library version whose wire format this client reproduces. */
    public static final String METRICS_VERSION = "3.0.3";
    static final String REPORT_URL = "https://bStats.org/api/v2/data/%s";
    /** 30 minutes at 20 tps. */
    static final long PERIOD_TICKS = 36_000L;

    /** HTTP seam; returns the response status code. */
    @FunctionalInterface
    public interface Transport {
        int post(String url, byte[] gzippedJson) throws IOException;
    }

    private final BStatsPlatform platform;
    private final int serviceId;
    private final Supplier<String> pluginVersion;
    private final File pluginDirectory;
    private final BStatsConfig config;
    private final Supplier<ServerInfo> serverInfo;
    private final BStatsScheduler scheduler;
    private final BStatsLog log;
    private final boolean collectOnMainThread;
    private final Transport transport;
    private final DoubleSupplier random;
    private final List<CustomChart> charts = new CopyOnWriteArrayList<>();

    private volatile boolean running;
    private volatile Object timerTask;

    private BStatsService(Builder b) {
        this.platform = b.platform;
        this.serviceId = b.serviceId;
        this.pluginVersion = b.pluginVersion;
        this.pluginDirectory = b.pluginDirectory;
        this.serverInfo = b.serverInfo;
        this.scheduler = b.scheduler;
        this.log = b.log;
        this.collectOnMainThread = b.collectOnMainThread;
        this.transport = b.transport;
        this.random = b.random;
        BStatsConfig.Format format = (b.configFormat != null) ? b.configFormat : b.platform.defaultConfigFormat();
        this.config = BStatsConfig.load(BStatsConfig.bStatsDirectory(b.pluginDirectory), format);
    }

    public static Builder builder(BStatsPlatform platform, int serviceId) {
        return new Builder(platform, serviceId);
    }

    public void addCustomChart(CustomChart chart) {
        charts.add(Objects.requireNonNull(chart, "chart"));
    }

    public List<CustomChart> charts() {
        return Collections.unmodifiableList(charts);
    }

    public BStatsPlatform platform() {
        return platform;
    }

    public int serviceId() {
        return serviceId;
    }

    public BStatsConfig config() {
        return config;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Schedules submissions. Returns {@code false} (nothing scheduled) when the
     * operator opted out. Idempotent.
     */
    public synchronized boolean start() {
        if (running) return true;
        if (!config.enabled() || BStatsConfig.isOptedOut(pluginDirectory)) {
            log.log(Level.INFO, "[bStats] Metrics collection is disabled by operator configuration.");
            return false;
        }
        long initial = initialDelayTicks(random.getAsDouble());
        long second = secondDelayTicks(random.getAsDouble());
        running = true;
        scheduler.runLater(this::trigger, initial);
        timerTask = scheduler.runAsyncTimer(this::trigger, initial + second, PERIOD_TICKS);
        log.log(Level.FINE, "[bStats] Scheduled submissions (platform=" + platform.endpoint()
                + ", id=" + serviceId + ").");
        return true;
    }

    /** Stops future submissions; safe to call repeatedly. */
    public synchronized void shutdown() {
        running = false;
        Object task = timerTask;
        timerTask = null;
        if (task != null) {
            try {
                scheduler.cancel(task);
            } catch (Throwable t) {
                log.log(Level.FINE, "[bStats] cancel failed: " + t);
            }
        }
    }

    /** Upstream initial delay: 3-6 minutes, in ticks. */
    static long initialDelayTicks(double r) {
        return (long) (1000 * 60 * (3 + r * 3)) / 50L;
    }

    /** Upstream offset of the periodic timer: 0-30 minutes, in ticks. */
    static long secondDelayTicks(double r) {
        return (long) (1000 * 60 * (r * 30)) / 50L;
    }

    private void trigger() {
        if (!running) return;
        try {
            if (collectOnMainThread) {
                scheduler.runOnMainThread(() -> {
                    if (!running) return;
                    String payload = buildPayload();
                    scheduler.runAsync(() -> {
                        if (running) send(payload);
                    });
                });
            } else {
                scheduler.runAsync(() -> {
                    if (running) send(buildPayload());
                });
            }
        } catch (Throwable t) {
            log.log(Level.FINE, "[bStats] submission scheduling failed: " + t);
        }
    }

    /** Builds the complete payload. Never throws; failing charts are omitted. */
    public String buildPayload() {
        Map<String, Object> root = new LinkedHashMap<>();
        try {
            root.putAll(platform.rootFields(serverInfo.get()));
        } catch (Throwable t) {
            log.log(Level.FINE, "[bStats] server info skipped: " + t);
        }
        root.put("javaVersion", System.getProperty("java.version", "unknown"));
        root.put("osName", System.getProperty("os.name", "unknown"));
        root.put("osArch", System.getProperty("os.arch", "unknown"));
        root.put("osVersion", System.getProperty("os.version", "unknown"));
        root.put("coreCount", Runtime.getRuntime().availableProcessors());

        List<String> chartJson = new ArrayList<>(charts.size());
        for (CustomChart chart : charts) {
            String json = chart.toJson(log);
            if (json != null) chartJson.add(json);
        }

        String version = "unknown";
        try {
            String v = pluginVersion.get();
            if (v != null && !v.isBlank()) version = v;
        } catch (Throwable ignored) {
            // keep "unknown"
        }

        StringBuilder sb = new StringBuilder(4096).append('{');
        BStatsJson.appendFields(sb, root);
        sb.append(",\"service\":{\"pluginVersion\":").append(BStatsJson.quote(version))
                .append(",\"id\":").append(serviceId)
                .append(",\"customCharts\":[").append(String.join(",", chartJson)).append("]}");
        sb.append(",\"serverUUID\":").append(BStatsJson.quote(config.serverUuid()));
        sb.append(",\"metricsVersion\":").append(BStatsJson.quote(METRICS_VERSION));
        return sb.append('}').toString();
    }

    /** Gzips and posts {@code payload}; re-checks the opt-out first. Never throws. */
    void send(String payload) {
        if (BStatsConfig.isOptedOut(pluginDirectory)) return;
        String url = String.format(REPORT_URL, platform.endpoint());
        try {
            int status = transport.post(url, gzip(payload));
            if (status < 200 || status >= 300) {
                log.log(config.logFailedRequests() ? Level.WARNING : Level.FINE,
                        "[bStats] Submission returned HTTP " + status);
            } else {
                log.log(Level.FINER, "[bStats] Submitted (HTTP " + status + ").");
            }
        } catch (Throwable t) {
            log.log(config.logFailedRequests() ? Level.WARNING : Level.FINE,
                    "[bStats] Could not submit bStats metrics data: " + t);
        }
    }

    static byte[] gzip(String s) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return baos.toByteArray();
    }

    /** Blocking POST on the calling (async) thread; same headers as upstream. */
    static int defaultPost(String url, byte[] body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            c.setConnectTimeout(15_000);
            c.setReadTimeout(20_000);
            c.setRequestMethod("POST");
            c.addRequestProperty("Accept", "application/json");
            c.addRequestProperty("Connection", "close");
            c.addRequestProperty("Content-Encoding", "gzip");
            c.addRequestProperty("Content-Length", String.valueOf(body.length));
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "Metrics-Service/1");
            c.setDoOutput(true);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int status = c.getResponseCode();
            InputStream in = (status >= 400) ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                try (InputStream drain = in) {
                    drain.readAllBytes();
                }
            }
            return status;
        } finally {
            c.disconnect();
        }
    }

    public static final class Builder {
        private final BStatsPlatform platform;
        private final int serviceId;
        private Supplier<String> pluginVersion = () -> null;
        private File pluginDirectory;
        private BStatsConfig.Format configFormat;
        private Supplier<ServerInfo> serverInfo = () -> null;
        private BStatsScheduler scheduler;
        private BStatsLog log = BStatsLog.NONE;
        private boolean collectOnMainThread;
        private Transport transport = BStatsService::defaultPost;
        private DoubleSupplier random = Math::random;

        private Builder(BStatsPlatform platform, int serviceId) {
            this.platform = Objects.requireNonNull(platform, "platform");
            if (serviceId <= 0) throw new IllegalArgumentException("serviceId must be positive");
            this.serviceId = serviceId;
        }

        public Builder pluginVersion(Supplier<String> pluginVersion) {
            this.pluginVersion = Objects.requireNonNull(pluginVersion);
            return this;
        }

        /** The plugin's own data folder; the shared config lives in its sibling {@code bStats/}. */
        public Builder pluginDirectory(File pluginDirectory) {
            this.pluginDirectory = pluginDirectory;
            return this;
        }

        /** Format used only when no shared bStats config exists yet; defaults to the platform's. */
        public Builder configFormat(BStatsConfig.Format configFormat) {
            this.configFormat = Objects.requireNonNull(configFormat);
            return this;
        }

        /** Server facts for the platform root fields; collected per submission. */
        public Builder serverInfo(Supplier<ServerInfo> serverInfo) {
            this.serverInfo = Objects.requireNonNull(serverInfo);
            return this;
        }

        /** Required. */
        public Builder scheduler(BStatsScheduler scheduler) {
            this.scheduler = Objects.requireNonNull(scheduler);
            return this;
        }

        public Builder log(BStatsLog log) {
            this.log = Objects.requireNonNull(log);
            return this;
        }

        public Builder collectOnMainThread(boolean collectOnMainThread) {
            this.collectOnMainThread = collectOnMainThread;
            return this;
        }

        public Builder transport(Transport transport) {
            this.transport = Objects.requireNonNull(transport);
            return this;
        }

        Builder random(DoubleSupplier random) {
            this.random = Objects.requireNonNull(random);
            return this;
        }

        /** @throws IllegalStateException when no scheduler was set */
        public BStatsService build() {
            if (scheduler == null) throw new IllegalStateException("scheduler is required");
            return new BStatsService(this);
        }
    }
}
