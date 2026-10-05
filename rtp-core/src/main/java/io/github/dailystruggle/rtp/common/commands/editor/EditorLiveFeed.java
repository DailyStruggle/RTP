package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorChannel;
import io.github.dailystruggle.rtp.common.metrics.LandingHeatmap;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zero-listener live channel for the {@code file://} web editor export (ADR-104 §4.6).
 *
 * <p>An async {@code RTP.scheduler} timer rewrites {@code <editor>/live/feed.js} every
 * {@link #PERIOD_MILLIS} with telemetry, progress and the version of every tile group, and writes
 * location-keyed tiles the page loads only for its viewport:
 * <ul>
 *   <li>world land ({@link WorldLandSurvey} into {@link WorldBiomeStore}):
 *       {@code land-<w>_<y>_<gx>_<gz>.js}, one group of 16 x 16 region-file bins;</li>
 *   <li>per region: hazards ({@link HazardTiles}) and landings ({@link LandingHeatmap}) as
 *       {@code hazard-<r>_<gx>_<gz>.js} / {@code heat-<r>_<gx>_<gz>.js} groups, and the full walk
 *       path ({@link WalkPathTiles}) as {@code path-<r>_<tx>_<tz>.js} plus immutable {@code path-N.js}
 *       index batches.</li>
 * </ul>
 * Bins carry {@link BiomeBinCodec} runs as base64, the form the store persists and the page keeps.
 * {@code r} is a session-unique id per region shape: a new or reshaped region gets a new id and its
 * old tiles are deleted, so the page drops ids that leave {@code feed.js}. Tiles are written before
 * the feed head lists them; writes are tmp-file + atomic move so the page never reads a torn file.
 * An optional push sink (the signed {@link EditorChannel}) receives each head, and viewport requests
 * arrive through {@link #offerClientMessage}; walk paths of edited geometry are answered per client
 * through {@link #offerWalkPathRequest} ({@link EditorWalkPathPreview}). The feed expires after
 * {@link #DEFAULT_TTL_MILLIS}; repeated write failures stop it with a logged reason (S-004).
 *
 * <p>Over the session's signed channel (ADR-106 §5.3, local and hosted alike) the feed also pushes
 * {@code curve} when a region's helper inputs change (settings, or state such as {@code expand}'s
 * effective radius), {@code hazard-delta} add / remove runs against the previous hazard version
 * (a reset when the page reports another version, through bytebin by key when a reset exceeds a
 * frame) and focus-driven {@code land} bins. Regions the page reports verified (drawn from their
 * helper) get no walk-path or hazard tiles. A hosted feed ({@link #startHosted}) writes no files:
 * the channel is its only output, and it stops when the channel closes.
 */
public final class EditorLiveFeed {

    public static final String LIVE_DIR = "live";
    public static final String FEED_FILE = "feed.js";
    static final String LAND_PREFIX = "land-";
    static final String PATH_PREFIX = "path-";
    static final String HAZARD_PREFIX = "hazard-";
    static final String HEAT_PREFIX = "heat-";
    public static final long PERIOD_TICKS = 40L;
    public static final long PERIOD_MILLIS = PERIOD_TICKS * 50L;
    public static final long DEFAULT_TTL_MILLIS = 30L * 60L * 1000L;
    /** Region-file bins per tile group edge. */
    static final int GROUP_BINS = 16;
    /** Walk-path tiles per tick: 16k pure-math curve inversions, no world access. */
    static final int PATH_TILES_PER_TICK = 16;
    /** Hazard bins rescanned per tick and region: 32k memory lookups. */
    static final int HAZARD_BINS_PER_TICK = 32;
    /** Ticks between region-set checks (new, removed or reshaped regions). */
    static final int REGION_CHECK_TICKS = 5;
    /** Ticks between per-region learned-biome histograms. */
    static final int REGION_BIOME_TICKS = 15;
    /** Ticks between biome store saves. */
    static final int SAVE_TICKS = 15;
    /** Curve samples per learned-biome histogram. */
    static final int REGION_BIOME_SAMPLES = 4_096;
    static final int MAX_CONSECUTIVE_WRITE_FAILURES = 5;
    /** Walk-path previews answered per tick (2,048 curve samples each); queued beyond, newest kept. */
    static final int WALK_PATHS_PER_TICK = 4;
    static final int MAX_PENDING_WALK_PATHS = 16;
    /** {@code land} frames per tick (one frame is about {@link #LAND_FRAME_TARGET_CHARS}). */
    static final int LAND_FRAMES_PER_TICK = 2;
    static final int LAND_FRAME_TARGET_CHARS = 24 * 1024;
    /** Edge of the focus rectangle whose bins are pushed, in region-file bins (clamped around its centre). */
    static final int MAX_FOCUS_EDGE = 64;
    static final int MAX_PAGE_MESSAGE_CHARS = 16 * 1024;

    private static final Pattern OWN_FILE = Pattern.compile("(feed|(land|path|hazard|heat)-[0-9_\\-]+)\\.js(\\.tmp)?");
    private static final Pattern FOCUS_TYPE = Pattern.compile("\"type\"\\s*:\\s*\"focus\"");
    private static final Pattern WORLD_FIELD = Pattern.compile("\"world\"\\s*:\\s*\"([^\"\\\\]{1,128})\"");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object LOCK = new Object();
    /** Volatile: read lock-free by channel handlers on the socket's poll thread (no lock-order cycle). */
    private static volatile EditorLiveFeed active;
    /** The local export's signed channel; closed with the feed that owns it, never under {@link #LOCK}. */
    private static volatile EditorChannel channel;

    /** One configured region as tracked by the feed; replaced when its shape changes. */
    static final class RegionTrack {
        final String name;
        final int r;
        final MemoryShape<?> shape;
        final long range;
        final WalkPathTiles path;
        final HazardTiles hazards;
        /** Curve helper source, {@code null} without one (no curve / hazard pushes). */
        final String js;
        long heatVersion;
        Map<String, Integer> biomes = Map.of();
        /** Last {@code curve} block sent; {@code null} sends it on the next tick. */
        String curveJson;
        /** Hazard version the pages should hold: 0 before the first push. */
        long hazardVersion;
        byte[] hazardBytes;
        List<long[]> hazardRuns = List.of();
        boolean hazardReset;
        /** Over the run cap (the page keeps the 2D grid) or a reset hand-off in flight. */
        boolean hazardOff;
        /** Set and cleared from the upload's completion thread. */
        volatile boolean handoffBusy;
        boolean handoffWarned;

        RegionTrack(String name, int r, MemoryShape<?> shape, WalkPathTiles path, HazardTiles hazards) {
            this.name = name;
            this.r = r;
            this.shape = shape;
            this.range = (shape == null) ? -1L : shape.getRange();
            this.path = path;
            this.hazards = hazards;
            this.js = helperOf(shape);
        }
    }

    private static String helperOf(MemoryShape<?> shape) {
        if (shape == null) return null;
        try {
            return shape.toJavaScript();
        } catch (RuntimeException e) {
            RTP.log(Level.FINE, "[editor] curve helper lookup failed for " + shape.getClass().getSimpleName(), e);
            return null;
        }
    }

    /** The page's view (region-file bins) and the survey layer serving it. */
    record Focus(String world, int layerY, int minRx, int minRz, int maxRx, int maxRz) {
        boolean contains(int rx, int rz) {
            return rx >= minRx && rx <= maxRx && rz >= minRz && rz <= maxRz;
        }
    }

    private final Path liveDir;
    /** {@code false} for a hosted feed: no live directory, the channel is the only output. */
    private final boolean files;
    private final List<WorldLandSurvey> surveys = new ArrayList<>();
    private final Map<String, Integer> worldIds = new LinkedHashMap<>();
    private final Map<String, Long> storeVersions = new HashMap<>();
    private final List<RegionTrack> tracks = new ArrayList<>();
    /** Region name to its curve-backed shape; {@code null} disables region tracking. */
    private final Supplier<Map<String, MemoryShape<?>>> regionSource;
    private final BiFunction<String, Integer, WorldLandSurvey> surveyFactory;
    private final Supplier<String> telemetryJson;
    private final LongSupplier clock;
    private final long expiresAt;
    private final String sessionId;
    private final AtomicBoolean ticking = new AtomicBoolean();
    private final Queue<String> clientMessages = new ConcurrentLinkedQueue<>();
    /** One page request: the client it came from (identity), where the reply goes, the raw JSON. */
    record WalkPathJob(Object client, Consumer<String> replyTo, String json) {
    }
    private final Queue<WalkPathJob> walkPathJobs = new ConcurrentLinkedQueue<>();
    /** {@code "<kind>|<layer>|<gx>|<gz>"} to version (feed seq it was written at). */
    private final Map<String, Long> groupVersions = new LinkedHashMap<>();
    private volatile Consumer<String> push;
    private volatile EditorChannel ownChannel;
    /** Uploads text to bytebin, completing with its key; {@code null} without a byte store (local). */
    private volatile Function<String, CompletableFuture<String>> handoff;
    /** Regions the page draws from a verified helper; tick thread only. */
    private final Set<String> verified = new HashSet<>();
    private Focus focus;
    /** {@code "rx,rz"} of focus bins still to push; tick thread only. */
    private final Set<String> landQueue = new LinkedHashSet<>();
    private int landPaletteSent = -1;
    private volatile boolean stopped;
    private volatile String stopReason;
    private volatile Object taskHandle;
    private long seq;
    private int pathBatches;
    private int nextRegionId;
    private int consecutiveFailures;

    /** Test constructor: fixed surveys and walk paths, no region tracking. */
    EditorLiveFeed(Path liveDir, List<WorldLandSurvey> surveys, List<WalkPathTiles> paths,
                   Supplier<String> telemetryJson, long ttlMillis, LongSupplier clock) {
        this(liveDir, surveys, null, null, telemetryJson, ttlMillis, clock);
        for (WalkPathTiles p : paths) tracks.add(new RegionTrack(p.region(), nextRegionId++, null, p, null));
    }

    EditorLiveFeed(Path liveDir, List<WorldLandSurvey> surveys, Supplier<Map<String, MemoryShape<?>>> regionSource,
                   BiFunction<String, Integer, WorldLandSurvey> surveyFactory,
                   Supplier<String> telemetryJson, long ttlMillis, LongSupplier clock) {
        this(liveDir, true, surveys, regionSource, surveyFactory, telemetryJson, ttlMillis, clock);
    }

    EditorLiveFeed(Path liveDir, boolean files, List<WorldLandSurvey> surveys, Supplier<Map<String, MemoryShape<?>>> regionSource,
                   BiFunction<String, Integer, WorldLandSurvey> surveyFactory,
                   Supplier<String> telemetryJson, long ttlMillis, LongSupplier clock) {
        this.liveDir = Objects.requireNonNull(liveDir, "liveDir");
        this.files = files;
        for (WorldLandSurvey s : surveys) addSurvey(s);
        this.regionSource = regionSource;
        this.surveyFactory = surveyFactory;
        this.telemetryJson = Objects.requireNonNull(telemetryJson, "telemetryJson");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.expiresAt = clock.getAsLong() + ttlMillis;
        byte[] id = new byte[6];
        RANDOM.nextBytes(id);
        this.sessionId = HexFormat.of().formatHex(id);
    }

    /**
     * Starts (or restarts) the feed beside an exported {@code index.html}. Replaces any running
     * feed. Call off the main thread: it lists region directories, loads biome stores and clears old
     * feed files.
     *
     * @param editorDir directory containing the exported page
     * @param push      receives each feed head as JSON (the loopback channel), or {@code null}
     * @throws IllegalStateException when the scheduler is not initialised (S-006)
     */
    public static EditorLiveFeed start(Path editorDir, Consumer<String> push) throws IOException {
        Objects.requireNonNull(editorDir, "editorDir");
        if (RTP.scheduler == null) {
            throw new IllegalStateException("RTP scheduler not initialized yet (S-006 fail-closed)");
        }
        EditorLiveFeed feed = new EditorLiveFeed(editorDir.resolve(LIVE_DIR), planSurveys(),
                EditorLiveFeed::configuredShapes, EditorLiveFeed::liveSurvey,
                () -> EditorSessionManager.getInstance().buildTelemetrySnapshotJson(),
                DEFAULT_TTL_MILLIS, System::currentTimeMillis);
        feed.push = push;
        synchronized (LOCK) {
            if (active != null) active.stop("superseded by a newer export", false);
            feed.prepareDirectory();
            active = feed;
            feed.taskHandle = RTP.scheduler.runTaskTimerAsynchronously(feed::tick, 0L, PERIOD_TICKS);
        }
        return feed;
    }

    public static EditorLiveFeed start(Path editorDir) throws IOException {
        return start(editorDir, null);
    }

    private static WorldLandSurvey liveSurvey(String world, Integer y) {
        RTPWorld<?> w = regionWorlds().get(world);
        return (w == null) ? null : WorldLandSurvey.of(w, y);
    }

    /**
     * Hosted session producers (ADR-106 §5.3): a file-less feed whose only output is {@code ch}
     * ({@code curve}, {@code hazard-delta}, focus-driven {@code land}, and the walk-path / curve-state
     * replies of {@link EditorChannelWiring}). It lives as long as the channel, at most
     * {@link #DEFAULT_TTL_MILLIS}, and replaces any running feed. Off the main thread only.
     *
     * @param handoff uploads a message too large for one frame to bytebin, completing with its key
     * @throws IllegalStateException when the scheduler is not initialised (S-006)
     */
    public static EditorLiveFeed startHosted(EditorChannel ch, Function<String, CompletableFuture<String>> handoff) {
        Objects.requireNonNull(ch, "ch");
        if (RTP.scheduler == null) {
            throw new IllegalStateException("RTP scheduler not initialized yet (S-006 fail-closed)");
        }
        EditorLiveFeed feed = new EditorLiveFeed(EditorChannelWiring.editorDir().resolve(LIVE_DIR), false, planSurveys(),
                EditorLiveFeed::configuredShapes, EditorLiveFeed::liveSurvey,
                () -> EditorSessionManager.getInstance().buildTelemetrySnapshotJson(),
                DEFAULT_TTL_MILLIS, System::currentTimeMillis);
        feed.attachChannel(ch, handoff);
        EditorLiveFeed old;
        synchronized (LOCK) {
            old = active;
            active = feed;
            feed.taskHandle = RTP.scheduler.runTaskTimerAsynchronously(feed::tick, 0L, PERIOD_TICKS);
        }
        // Stopping closes the old feed's channel: never under LOCK
        if (old != null) old.stop("superseded by a newer editor session", false);
        return feed;
    }

    /** The signed channel this feed pushes to (and owns), and its bytebin hand-off. */
    void attachChannel(EditorChannel ch, Function<String, CompletableFuture<String>> handoff) {
        this.ownChannel = ch;
        this.handoff = handoff;
    }

    public static EditorLiveFeed exportAndStart(Path indexHtml, Map<String, String> configs) throws IOException {
        return exportAndStart(indexHtml, configs, null);
    }

    /**
     * Local export entry point: (re)opens the signed editor channel over a loopback socket, writes
     * {@code indexHtml} with its {@code channel} block, then starts the feed pushing heads over it.
     * Page messages (focus, walk-path / curve-state previews, Hot-Apply) are verified and routed by
     * {@link EditorChannelWiring}; trust prompts go to {@code creator}. The page still works from
     * {@code feed.js} alone when the channel cannot open (logged, S-004) or the browser runs on
     * another machine. Off the main thread only.
     */
    public static EditorLiveFeed exportAndStart(Path indexHtml, Map<String, String> configs, UUID creator) throws IOException {
        Objects.requireNonNull(indexHtml, "indexHtml");
        EditorChannel old;
        synchronized (LOCK) {
            old = channel;
            channel = null;
        }
        // Closing stops a socket whose poll thread may be inside a handler: never under LOCK
        if (old != null) old.close("superseded by a newer export");
        EditorChannel ch = null;
        try {
            ch = EditorChannelWiring.ready(EditorChannelWiring.openLocal(creator, EditorLiveFeed::active));
        } catch (IOException | RuntimeException e) {
            RTP.log(Level.WARNING, "[editor] editor channel unavailable; the page uses live/feed.js only: " + e.getMessage(), e);
        }
        synchronized (LOCK) {
            old = channel;
            channel = ch;
        }
        if (old != null) old.close("superseded by a newer export");
        EditorChannel sink = ch;
        try {
            EditorSessionManager.getInstance().exportLocalEditorHtml(indexHtml, configs, true, sink == null ? null : sink.snapshotBlock());
            EditorLiveFeed feed = start(indexHtml.toAbsolutePath().getParent(), sink == null ? null : sink::send);
            feed.ownChannel = sink;
            return feed;
        } catch (IOException | RuntimeException e) {
            if (sink != null) {
                synchronized (LOCK) {
                    if (channel == sink) channel = null;
                }
                sink.close("export failed: " + e.getMessage());
            }
            throw e;
        }
    }

    /** The local export's open channel, or {@code null}. */
    static EditorChannel channel() {
        return channel;
    }

    /** The running feed, or {@code null}. */
    public static EditorLiveFeed active() {
        return active;
    }

    /** Stops the running feed, if any, without further feed writes (plugin disable path). */
    public static void stopActive(String reason) {
        EditorLiveFeed feed;
        synchronized (LOCK) {
            feed = active;
        }
        if (feed != null) feed.stop(reason, false);
        EditorChannel ch;
        synchronized (LOCK) {
            ch = channel;
            channel = null;
        }
        if (ch != null) ch.close(reason);
    }

    /** One world-wide survey at the default sample Y per distinct live world with a configured region. */
    static List<WorldLandSurvey> planSurveys() {
        List<WorldLandSurvey> out = new ArrayList<>();
        for (RTPWorld<?> w : regionWorlds().values()) out.add(WorldLandSurvey.of(w, WorldBiomeStore.DEFAULT_Y));
        return out;
    }

    static Map<String, Region> configuredRegions() {
        if (RTP.selectionAPI == null || RTP.selectionAPI.permRegionLookup == null) return Map.of();
        return new LinkedHashMap<>(RTP.selectionAPI.permRegionLookup);
    }

    /** Curve-backed shapes of the configured regions, by region name. */
    static Map<String, MemoryShape<?>> configuredShapes() {
        Map<String, MemoryShape<?>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Region> e : configuredRegions().entrySet()) {
            if (e.getValue() != null && e.getValue().shape instanceof MemoryShape<?> ms) out.put(e.getKey(), ms);
        }
        return out;
    }

    /** Live worlds of the configured regions, by name, in region order. */
    static Map<String, RTPWorld<?>> regionWorlds() {
        Map<String, RTPWorld<?>> worlds = new LinkedHashMap<>();
        for (Region r : configuredRegions().values()) {
            if (r == null) continue;
            RTPWorld<?> w;
            try {
                w = r.getWorld();
            } catch (RuntimeException e) {
                w = null;
            }
            if (w == null) {
                RTP.log(Level.FINE, "[editor] live feed skips region '" + r.name + "': world not loaded");
                continue;
            }
            worlds.putIfAbsent(w.name(), w);
        }
        return worlds;
    }

    private void addSurvey(WorldLandSurvey s) {
        surveys.add(s);
        worldIds.computeIfAbsent(s.world(), k -> worldIds.size());
    }

    /** Creates the live directory and removes feed files left by an earlier session (ours only). */
    void prepareDirectory() throws IOException {
        if (!files) return;
        Files.createDirectories(liveDir);
        deleteOwnFiles(null);
    }

    /** Deletes feed files; {@code prefixes} limits it to names starting with one of them. */
    private void deleteOwnFiles(List<String> prefixes) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(liveDir)) {
            for (Path p : files) {
                String n = p.getFileName().toString();
                if (!Files.isRegularFile(p) || !OWN_FILE.matcher(n).matches()) continue;
                if (prefixes != null && prefixes.stream().noneMatch(n::startsWith)) continue;
                Files.deleteIfExists(p);
            }
        }
    }

    /** Sets the sink receiving each feed head (e.g. the loopback channel's broadcast). */
    public void setPush(Consumer<String> push) {
        this.push = push;
    }

    /**
     * Queues a page message for the next tick. Handled: {@code {"type":"focus","world":..,"y":..,
     * "minRx":..,"minRz":..,"maxRx":..,"maxRz":..}} - the visible region-file rectangle, whose bins
     * the matching survey takes to full detail first. Bounded: older unhandled messages are dropped.
     */
    public void offerClientMessage(String json) {
        if (json == null || json.length() > MAX_PAGE_MESSAGE_CHARS) return;
        while (clientMessages.size() >= 16) clientMessages.poll();
        clientMessages.offer(json);
    }

    /**
     * Queues a walk-path or curve-state preview request ({@link EditorWalkPathPreview}) for the next
     * tick; the reply goes to {@code replyTo}. Per client, type and region only the newest request is
     * answered; both types share the {@link #WALK_PATHS_PER_TICK} budget.
     */
    public void offerWalkPathRequest(Object client, Consumer<String> replyTo, String json) {
        if (replyTo == null || json == null || json.length() > EditorWalkPathPreview.MAX_MESSAGE_CHARS) return;
        while (walkPathJobs.size() >= MAX_PENDING_WALK_PATHS) walkPathJobs.poll();
        walkPathJobs.offer(new WalkPathJob(client, replyTo, json));
    }

    private void handleWalkPathRequests() {
        if (walkPathJobs.isEmpty()) return;
        Map<List<Object>, Map.Entry<WalkPathJob, Object>> newest = new LinkedHashMap<>();
        WalkPathJob job;
        while ((job = walkPathJobs.poll()) != null) {
            Object req = EditorWalkPathPreview.parse(job.json());
            String region;
            if (req instanceof EditorWalkPathPreview.Request walk) {
                region = walk.region();
            } else {
                EditorWalkPathPreview.CurveStateRequest cs = EditorWalkPathPreview.parseCurveState(job.json());
                if (cs == null) continue;
                req = cs;
                region = cs.region();
            }
            List<Object> key = Arrays.asList(job.client(), req.getClass(), region);
            newest.remove(key);
            newest.put(key, Map.entry(job, req));
        }
        int answered = 0;
        for (Map.Entry<WalkPathJob, Object> e : newest.values()) {
            if (answered++ >= WALK_PATHS_PER_TICK) {
                // The rest wait for the next tick, still newest-only
                walkPathJobs.offer(e.getKey());
                continue;
            }
            try {
                e.getKey().replyTo().accept(e.getValue() instanceof EditorWalkPathPreview.Request walk
                        ? EditorWalkPathPreview.reply(walk, EditorChannel::fitsFrame)
                        : EditorWalkPathPreview.replyCurveState((EditorWalkPathPreview.CurveStateRequest) e.getValue()));
            } catch (RuntimeException ex) {
                RTP.log(Level.FINE, "[editor] walk path reply failed: " + ex.getMessage());
            }
        }
    }

    /**
     * Advances surveys and layers by one budgeted step and rewrites the feed head. Re-entrant calls
     * are skipped.
     *
     * @return {@code false} once the feed has stopped
     */
    public boolean tick() {
        if (stopped) return false;
        if (!ticking.compareAndSet(false, true)) return true;
        try {
            long now = clock.getAsLong();
            if (now >= expiresAt) {
                WorldBiomeStore.saveAll();
                stop("expired after " + (DEFAULT_TTL_MILLIS / 60_000L)
                        + " minutes; run /rtp editor " + (files ? "local " : "") + "again to resume", files);
                return false;
            }
            EditorChannel own = ownChannel;
            if (!files && own != null && !own.isOpen()) {
                stop("editor channel closed: " + own.closeReason(), false);
                return false;
            }
            seq++;
            handleClientMessages();
            handleWalkPathRequests();
            if (regionSource != null && (seq == 1 || seq % REGION_CHECK_TICKS == 0)) syncRegions();
            publishCurves();
            publishHazards();

            // One survey per tick: the one serving a viewport request, else the first unfinished
            for (WorldLandSurvey survey : surveys) {
                if (survey.isDone()) continue;
                survey.nextBatch();
                break;
            }
            writeLandGroups();
            publishLand();

            for (RegionTrack t : tracks) {
                // A verified region's path is drawn from its helper on the page
                if (t.path == null || t.path.isDone() || verified.contains(t.name)) continue;
                WalkPathTiles.Batch batch = t.path.nextBatch(PATH_TILES_PER_TICK);
                if (batch != null && !batch.tiles().isEmpty()) {
                    for (WalkPathTiles.Tile tile : batch.tiles()) {
                        write(liveDir.resolve(PATH_PREFIX + t.r + "_" + tile.tx() + "_" + tile.tz() + ".js"),
                                pathTileScript(t.r, tile));
                    }
                    write(liveDir.resolve(PATH_PREFIX + pathBatches + ".js"), pathIndexScript(pathBatches, t.r, batch));
                    pathBatches++;
                }
                break;
            }
            writeOverlayGroups();
            if (seq == 1 || seq % REGION_BIOME_TICKS == 0) refreshRegionBiomes();
            if (seq % SAVE_TICKS == 0) WorldBiomeStore.saveAll();

            Consumer<String> sink = push;
            // A hosted page has no live directory: the head (tile versions, telemetry) is local-only
            String head = (files || sink != null) ? feedJson(now, telemetryJson.get()) : null;
            if (files) write(liveDir.resolve(FEED_FILE), feedScript(head));
            if (sink != null) {
                try {
                    sink.accept("{\"type\":\"feed\",\"data\":" + head + "}");
                } catch (RuntimeException e) {
                    RTP.log(Level.FINE, "[editor] live feed push failed: " + e.getMessage());
                }
            }
            consecutiveFailures = 0;
            return true;
        } catch (IOException e) {
            // Windows refuses to replace a file another process is reading; retry next tick
            if (++consecutiveFailures >= MAX_CONSECUTIVE_WRITE_FAILURES) {
                RTP.log(Level.WARNING, "[editor] live feed stopped after " + consecutiveFailures
                        + " consecutive write failures in " + liveDir + ": " + e.getMessage(), e);
                stop("write failures: " + e.getMessage(), false);
                return false;
            }
            RTP.log(Level.FINE, "[editor] live feed write retry " + consecutiveFailures + ": " + e.getMessage());
            return true;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING, "[editor] live feed tick failed; stopping: " + e.getMessage(), e);
            stop("internal error: " + e.getMessage(), false);
            return false;
        } finally {
            ticking.set(false);
        }
    }

    private void handleClientMessages() {
        String latestFocus = null;
        String msg;
        while ((msg = clientMessages.poll()) != null) {
            if (FOCUS_TYPE.matcher(msg).find()) latestFocus = msg;
        }
        if (latestFocus == null) return;
        pageState(latestFocus);
        Matcher wm = WORLD_FIELD.matcher(latestFocus);
        Integer minRx = intField(latestFocus, "minRx");
        Integer minRz = intField(latestFocus, "minRz");
        Integer maxRx = intField(latestFocus, "maxRx");
        Integer maxRz = intField(latestFocus, "maxRz");
        if (!wm.find() || minRx == null || minRz == null || maxRx == null || maxRz == null) return;
        String world = wm.group(1);
        Integer yField = intField(latestFocus, "y");
        int y = (yField == null) ? WorldBiomeStore.DEFAULT_Y : yField;
        WorldLandSurvey target = surveyFor(world, y);
        if (target == null) return;
        target.prioritize(minRx, minRz, maxRx, maxRz);
        // Serve the request first: move its survey to the front
        surveys.remove(target);
        surveys.add(0, target);
        if (ownChannel != null) focusLand(target, minRx, minRz, maxRx, maxRz);
    }

    /**
     * {@code verified: [region]} (the page draws these from their helper: no tiles) and
     * {@code hazardVersions: {region: v}} (another version than ours: resend the curve and a hazard reset).
     */
    private void pageState(String focusJson) {
        Object root;
        try {
            root = EditorLoopbackJson.parse(focusJson);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!(root instanceof Map<?, ?> m)) return;
        if (m.get("verified") instanceof List<?> list) {
            verified.clear();
            for (Object o : list) {
                if (o instanceof String s && s.length() <= 128 && verified.size() < 256) verified.add(s);
            }
        }
        if (m.get("hazardVersions") instanceof Map<?, ?> versions) {
            for (RegionTrack t : tracks) {
                if (t.hazardVersion > 0 && versions.get(t.name) instanceof Number n && n.longValue() != t.hazardVersion) {
                    t.hazardReset = true;
                    t.curveJson = null;
                }
            }
        }
    }

    /** A new view: push every stored bin of it (newest view wins), centre-clamped to {@link #MAX_FOCUS_EDGE}. */
    private void focusLand(WorldLandSurvey target, int minRx, int minRz, int maxRx, int maxRz) {
        int x0 = Math.min(minRx, maxRx), x1 = Math.max(minRx, maxRx);
        int z0 = Math.min(minRz, maxRz), z1 = Math.max(minRz, maxRz);
        if (x1 - x0 + 1 > MAX_FOCUS_EDGE) {
            int c = x0 + (x1 - x0) / 2;
            x0 = c - MAX_FOCUS_EDGE / 2;
            x1 = x0 + MAX_FOCUS_EDGE - 1;
        }
        if (z1 - z0 + 1 > MAX_FOCUS_EDGE) {
            int c = z0 + (z1 - z0) / 2;
            z0 = c - MAX_FOCUS_EDGE / 2;
            z1 = z0 + MAX_FOCUS_EDGE - 1;
        }
        Focus f = new Focus(target.world(), target.y(), x0, z0, x1, z1);
        if (f.equals(focus)) return;
        focus = f;
        landQueue.clear();
        WorldBiomeStore store = target.store();
        for (int rz = z0; rz <= z1; rz++) {
            for (int rx = x0; rx <= x1; rx++) {
                if (landRow(store, rx, rz, f.layerY()) != null) landQueue.add(rx + "," + rz);
            }
        }
    }

    /** {@code [rx, rz, level, base64Runs]} of a read bin layer, or {@code null}. */
    private static String landRow(WorldBiomeStore store, int rx, int rz, int y) {
        WorldBiomeStore.BinView b = store == null ? null : store.bin(rx, rz);
        if (b == null) return null;
        for (WorldBiomeStore.Layer l : b.layers()) {
            if (l.y() == y && l.level() >= 0 && l.runs() != null) {
                return "[" + rx + "," + rz + "," + l.level() + ",\"" + b64(l.runs()) + "\"]";
            }
        }
        return null;
    }

    private WorldBiomeStore storeOf(String world) {
        for (WorldLandSurvey s : surveys) if (s.world().equals(world)) return s.store();
        return null;
    }

    /**
     * Pushes queued focus bins as {@code land {world, y, palette?, bins: [[rx, rz, level, runs]]}},
     * at most {@link #LAND_FRAMES_PER_TICK} frames; the palette rides along whenever it grew. A frame
     * the channel skips (outbound budget, relay down) stays queued.
     */
    private void publishLand() {
        EditorChannel ch = ownChannel;
        Focus f = focus;
        if (ch == null || f == null || landQueue.isEmpty()) return;
        WorldBiomeStore store = storeOf(f.world());
        if (store == null) {
            landQueue.clear();
            return;
        }
        List<String> palette = store.palette();
        StringBuilder paletteJson = new StringBuilder("[");
        for (int i = 0; i < palette.size(); i++) {
            if (i > 0) paletteJson.append(',');
            paletteJson.append(EditorLoopbackJson.quote(palette.get(i)));
        }
        paletteJson.append(']');
        List<String> keys = new ArrayList<>(landQueue);
        int i = 0;
        for (int frames = 0; frames < LAND_FRAMES_PER_TICK && i < keys.size(); frames++) {
            boolean withPalette = palette.size() != landPaletteSent;
            int budget = Math.max(2048, LAND_FRAME_TARGET_CHARS - (withPalette ? 2 * paletteJson.length() : 0));
            StringBuilder rows = new StringBuilder();
            List<String> taken = new ArrayList<>();
            while (i < keys.size()) {
                String key = keys.get(i);
                int comma = key.indexOf(',');
                String row = landRow(store, Integer.parseInt(key.substring(0, comma)), Integer.parseInt(key.substring(comma + 1)), f.layerY());
                if (row == null || row.length() > budget) {
                    landQueue.remove(key);
                    i++;
                    continue;
                }
                if (rows.length() > 0 && rows.length() + row.length() + 1 > budget) break;
                if (rows.length() > 0) rows.append(',');
                rows.append(row);
                taken.add(key);
                i++;
            }
            if (taken.isEmpty()) break;
            String msg = "{\"type\":\"land\",\"world\":" + EditorLoopbackJson.quote(f.world()) + ",\"y\":" + f.layerY()
                    + (withPalette ? ",\"palette\":" + paletteJson : "") + ",\"bins\":[" + rows + "]}";
            if (!EditorChannel.fitsFrame(msg)) {
                taken.forEach(landQueue::remove);
                RTP.log(Level.FINE, "[editor] land batch over the frame cap dropped (" + taken.size() + " bins)");
                continue;
            }
            if (!ch.send(msg)) break;
            if (withPalette) landPaletteSent = palette.size();
            taken.forEach(landQueue::remove);
        }
    }

    /**
     * {@code curve {region, curve: {shape, params, state, hash}}} for every helper region whose
     * block changed (checked every {@link #REGION_CHECK_TICKS} ticks, at once after a resync).
     */
    private void publishCurves() {
        EditorChannel ch = ownChannel;
        if (ch == null) return;
        boolean due = seq == 1 || seq % REGION_CHECK_TICKS == 0;
        for (RegionTrack t : tracks) {
            if (t.js == null || t.shape == null || (!due && t.curveJson != null)) continue;
            String json;
            try {
                json = EditorSessionManager.mapToJson(EditorCurveModel.curve(t.shape, t.js));
            } catch (RuntimeException e) {
                RTP.log(Level.FINE, "[editor] curve block for region '" + t.name + "' failed", e);
                continue;
            }
            if (json.equals(t.curveJson)) continue;
            if (ch.send("{\"type\":\"curve\",\"region\":" + EditorLoopbackJson.quote(t.name) + ",\"curve\":" + json + "}")) {
                t.curveJson = json;
            }
        }
    }

    /**
     * Hazard runs in curve space ({@link EditorCurveModel#encodeHazardRuns}, spacing marks left out),
     * every tick: a change becomes {@code hazard-delta {region, from, to, add, remove}}; the first
     * push, a delta over the frame cap and a resync become {@code {region, to, reset: true, runs}},
     * by bytebin key ({@code bytebinKey, sha256}) when that exceeds a frame too.
     */
    private void publishHazards() {
        EditorChannel ch = ownChannel;
        if (ch == null) return;
        for (RegionTrack t : tracks) {
            if (t.js == null || t.shape == null || t.hazardOff) continue;
            byte[] now;
            try {
                now = EditorCurveModel.encodeHazardRuns(t.shape);
            } catch (RuntimeException e) {
                RTP.log(Level.FINE, "[editor] hazard runs for region '" + t.name + "' failed", e);
                continue;
            }
            if (now == null) {
                t.hazardOff = true;
                RTP.log(Level.INFO, "[editor] region '" + t.name + "': hazard runs over the "
                        + (EditorCurveModel.MAX_HAZARD_RUN_BYTES >> 10) + " KiB cap; no live hazard updates");
                continue;
            }
            if (t.hazardBytes == null || !Arrays.equals(now, t.hazardBytes)) {
                List<long[]> next = EditorCurveModel.decodeHazardRuns(now);
                long from = t.hazardVersion;
                String delta = null;
                if (from > 0 && !t.hazardReset) {
                    List<long[]> add = new ArrayList<>();
                    List<long[]> remove = new ArrayList<>();
                    EditorCurveModel.diffRuns(t.hazardRuns, next, add, remove);
                    delta = "{\"type\":\"hazard-delta\",\"region\":" + EditorLoopbackJson.quote(t.name) + ",\"from\":" + from
                            + ",\"to\":" + (from + 1) + ",\"add\":\"" + b64(EditorCurveModel.encodeRuns(add))
                            + "\",\"remove\":\"" + b64(EditorCurveModel.encodeRuns(remove)) + "\"}";
                    if (!EditorChannel.fitsFrame(delta)) delta = null;
                }
                t.hazardBytes = now;
                t.hazardRuns = next;
                t.hazardVersion = from + 1;
                // A delta that can't go out (frame cap, outbound budget, relay down) becomes a reset
                if (delta == null || !ch.send(delta)) t.hazardReset = true;
            }
            if (t.hazardReset && !t.handoffBusy) t.hazardReset = !sendHazardReset(ch, t);
        }
    }

    /** @return whether the reset went out (or its hand-off started, or can never go) */
    private boolean sendHazardReset(EditorChannel ch, RegionTrack t) {
        String region = EditorLoopbackJson.quote(t.name);
        long version = t.hazardVersion;
        String runs = b64(t.hazardBytes);
        String inline = "{\"type\":\"hazard-delta\",\"region\":" + region + ",\"to\":" + version
                + ",\"reset\":true,\"runs\":\"" + runs + "\"}";
        if (EditorChannel.fitsFrame(inline)) return ch.send(inline);
        Function<String, CompletableFuture<String>> upload = handoff;
        if (upload == null) {
            if (!t.handoffWarned) {
                t.handoffWarned = true;
                RTP.log(Level.INFO, "[editor] region '" + t.name + "': hazard reset of " + runs.length()
                        + " characters exceeds one channel frame and this session has no byte store; the page keeps its snapshot runs");
            }
            return true;
        }
        String content = "{\"runs\":\"" + runs + "\"}";
        String sha = EditorHttpTransport.computeSha256(content);
        t.handoffBusy = true;
        CompletableFuture<String> f;
        try {
            f = Objects.requireNonNull(upload.apply(content), "hand-off returned null");
        } catch (RuntimeException e) {
            f = CompletableFuture.failedFuture(e);
        }
        f.whenComplete((key, err) -> {
            t.handoffBusy = false;
            if (err != null || key == null) {
                RTP.log(Level.WARNING, "[editor] hazard reset hand-off for region '" + t.name + "' failed: "
                        + (err == null ? "no key" : err.getMessage()), err);
                return;
            }
            ch.send("{\"type\":\"hazard-delta\",\"region\":" + region + ",\"to\":" + version
                    + ",\"reset\":true,\"bytebinKey\":" + EditorLoopbackJson.quote(key) + ",\"sha256\":\"" + sha + "\"}");
        });
        return true;
    }

    /** Survey of {@code world} whose sample Y is within the store tolerance of {@code y}; created when allowed. */
    private WorldLandSurvey surveyFor(String world, int y) {
        WorldLandSurvey best = null;
        int count = 0;
        for (WorldLandSurvey s : surveys) {
            if (!s.world().equals(world)) continue;
            count++;
            int d = Math.abs(s.y() - y);
            if (d <= WorldBiomeStore.Y_TOLERANCE && (best == null || d < Math.abs(best.y() - y))) best = s;
        }
        if (best != null || surveyFactory == null || count == 0 || count >= WorldBiomeStore.MAX_LAYERS) return best;
        WorldLandSurvey created = surveyFactory.apply(world, y);
        if (created != null) addSurvey(created);
        return created;
    }

    private static Integer intField(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d{1,9})(?![\\d.])").matcher(json);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** Adds, replaces (new shape object or range) and drops region tracks to match the live config. */
    void syncRegions() throws IOException {
        Map<String, MemoryShape<?>> live = regionSource.get();
        if (live == null) live = Map.of();
        Set<Integer> retired = new HashSet<>();
        Map<String, RegionTrack> byName = new HashMap<>();
        for (RegionTrack t : tracks) byName.put(t.name, t);
        List<RegionTrack> next = new ArrayList<>();
        for (Map.Entry<String, MemoryShape<?>> e : live.entrySet()) {
            MemoryShape<?> shape = e.getValue();
            RegionTrack old = byName.remove(e.getKey());
            if (old != null && old.shape == shape && (shape == null || old.range == shape.getRange())) {
                next.add(old);
                continue;
            }
            if (old != null) retired.add(old.r);
            if (shape == null) continue;
            // Hosted pages load no tiles: they draw from the helper or the snapshot sketch
            next.add(new RegionTrack(e.getKey(), nextRegionId++, shape,
                    files ? WalkPathTiles.of(e.getKey(), shape) : null, files ? HazardTiles.of(shape) : null));
        }
        for (RegionTrack gone : byName.values()) retired.add(gone.r);
        tracks.clear();
        tracks.addAll(next);
        if (retired.isEmpty() || !files) return;
        List<String> prefixes = new ArrayList<>();
        for (int r : retired) {
            prefixes.add(PATH_PREFIX + r + "_");
            prefixes.add(HAZARD_PREFIX + r + "_");
            prefixes.add(HEAT_PREFIX + r + "_");
            groupVersions.keySet().removeIf(k -> k.startsWith("hazard|" + r + "|") || k.startsWith("heat|" + r + "|"));
        }
        deleteOwnFiles(prefixes);
    }

    /** Rewrites every land group holding a bin that changed since the last tick. */
    private void writeLandGroups() throws IOException {
        Map<String, WorldBiomeStore> stores = new LinkedHashMap<>();
        for (WorldLandSurvey s : surveys) stores.putIfAbsent(s.world(), s.store());
        for (Map.Entry<String, WorldBiomeStore> e : stores.entrySet()) {
            WorldBiomeStore store = e.getValue();
            int w = worldIds.get(e.getKey());
            long since = storeVersions.getOrDefault(e.getKey(), 0L);
            long upTo = store.version();
            if (upTo == since) continue;
            Set<String> dirty = new LinkedHashSet<>();
            Focus f = focus;
            boolean toPage = ownChannel != null && f != null && f.world().equals(e.getKey());
            for (WorldBiomeStore.BinView b : store.changedSince(since)) {
                for (WorldBiomeStore.Layer l : b.layers()) {
                    dirty.add(l.y() + "|" + Math.floorDiv(b.rx(), GROUP_BINS) + "|" + Math.floorDiv(b.rz(), GROUP_BINS));
                    // Sharper bins in view go to the page as they are read
                    if (toPage && l.y() == f.layerY() && f.contains(b.rx(), b.rz())) landQueue.add(b.rx() + "," + b.rz());
                }
            }
            if (!files) dirty.clear();
            for (String g : dirty) {
                String[] p = g.split("\\|");
                int y = Integer.parseInt(p[0]);
                int gx = Integer.parseInt(p[1]);
                int gz = Integer.parseInt(p[2]);
                List<Object> bins = new ArrayList<>();
                for (int rz = gz * GROUP_BINS; rz < (gz + 1) * GROUP_BINS; rz++) {
                    for (int rx = gx * GROUP_BINS; rx < (gx + 1) * GROUP_BINS; rx++) {
                        WorldBiomeStore.BinView b = store.bin(rx, rz);
                        if (b == null) continue;
                        for (WorldBiomeStore.Layer l : b.layers()) {
                            if (l.y() == y) bins.add(List.of(rx, rz, Math.max(-1, l.level()), b64(l.runs())));
                        }
                    }
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sessionId", sessionId);
                m.put("bins", bins);
                write(liveDir.resolve(LAND_PREFIX + w + "_" + y + "_" + gx + "_" + gz + ".js"),
                        "window.__rtpLiveLandGroup&&window.__rtpLiveLandGroup(" + w + "," + y + "," + gx + "," + gz + ","
                                + scriptSafe(EditorSessionManager.mapToJson(m)) + ");\n");
                groupVersions.put("land|" + w + "_" + y + "|" + gx + "|" + gz, seq);
            }
            storeVersions.put(e.getKey(), upTo);
        }
    }

    /** Rescans hazards, collects new landings and rewrites the changed overlay groups. */
    private void writeOverlayGroups() throws IOException {
        if (!files) return;
        for (RegionTrack t : tracks) {
            // A verified region's hazards reach the page as curve-space runs
            if (t.hazards != null && !verified.contains(t.name)) {
                Set<String> dirty = new LinkedHashSet<>();
                for (int[] b : t.hazards.nextBatch(HAZARD_BINS_PER_TICK)) {
                    dirty.add(Math.floorDiv(b[0], GROUP_BINS) + "|" + Math.floorDiv(b[1], GROUP_BINS));
                }
                for (String g : dirty) {
                    String[] p = g.split("\\|");
                    int gx = Integer.parseInt(p[0]);
                    int gz = Integer.parseInt(p[1]);
                    writeOverlayGroup("hazard", HAZARD_PREFIX, t.r, gx, gz, (rx, rz) -> t.hazards.runs(rx, rz));
                }
            }
            long v = LandingHeatmap.version();
            if (v != t.heatVersion) {
                Set<String> dirty = new LinkedHashSet<>();
                for (int[] b : LandingHeatmap.changedBins(t.name, t.heatVersion)) {
                    dirty.add(Math.floorDiv(b[0], GROUP_BINS) + "|" + Math.floorDiv(b[1], GROUP_BINS));
                }
                t.heatVersion = v;
                for (String g : dirty) {
                    String[] p = g.split("\\|");
                    int gx = Integer.parseInt(p[0]);
                    int gz = Integer.parseInt(p[1]);
                    writeOverlayGroup("heat", HEAT_PREFIX, t.r, gx, gz, (rx, rz) -> heatRuns(t.name, rx, rz));
                }
            }
        }
    }

    /** Heat bucket per chunk: {@code 1 + floor(log2(count))}, capped at 15; {@code 0} = no landing. */
    static byte[] heatRuns(String region, int rx, int rz) {
        int[] cells = null;
        for (int d = 0; d < BiomeBinCodec.CELLS; d++) {
            int n = LandingHeatmap.count(region, (rx << 5) + BiomeBinCodec.d2x(d), (rz << 5) + BiomeBinCodec.d2z(d));
            if (n <= 0) continue;
            if (cells == null) cells = new int[BiomeBinCodec.CELLS];
            cells[d] = Math.min(15, 1 + (31 - Integer.numberOfLeadingZeros(n)));
        }
        return (cells == null) ? null : BiomeBinCodec.encode(cells);
    }

    @FunctionalInterface
    private interface BinSource {
        byte[] runs(int rx, int rz);
    }

    private void writeOverlayGroup(String kind, String prefix, int r, int gx, int gz, BinSource source) throws IOException {
        List<Object> bins = new ArrayList<>();
        for (int rz = gz * GROUP_BINS; rz < (gz + 1) * GROUP_BINS; rz++) {
            for (int rx = gx * GROUP_BINS; rx < (gx + 1) * GROUP_BINS; rx++) {
                byte[] runs = source.runs(rx, rz);
                if (runs != null) bins.add(List.of(rx, rz, b64(runs)));
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("bins", bins);
        write(liveDir.resolve(prefix + r + "_" + gx + "_" + gz + ".js"),
                "window.__rtpLiveOverlayGroup&&window.__rtpLiveOverlayGroup(\"" + kind + "\"," + r + "," + gx + "," + gz + ","
                        + scriptSafe(EditorSessionManager.mapToJson(m)) + ");\n");
        groupVersions.put(kind + "|" + r + "|" + gx + "|" + gz, seq);
    }

    /** Learned biome histogram per region from its selection memory ({@link MemoryShape#biomeAt(long)}). */
    private void refreshRegionBiomes() {
        for (RegionTrack t : tracks) {
            if (t.shape == null) continue;
            long range = t.shape.getRange();
            if (range <= 0) continue;
            Map<String, Integer> counts = new TreeMap<>();
            long step = Math.max(1L, range / REGION_BIOME_SAMPLES);
            for (long i = 0; i < range; i += step) {
                String biome;
                try {
                    biome = t.shape.biomeAt(i);
                } catch (RuntimeException e) {
                    biome = null;
                }
                if (biome != null) counts.merge(WorldLandSurvey.namespacedBiome(biome), 1, Integer::sum);
            }
            t.biomes = counts;
        }
    }

    void stop(String reason, boolean writeFinal) {
        if (stopped) return;
        stopped = true;
        stopReason = reason;
        Object handle = taskHandle;
        if (handle != null && RTP.scheduler != null) {
            try {
                RTP.scheduler.cancelTask(handle);
            } catch (RuntimeException e) {
                RTP.log(Level.WARNING, "[editor] failed to cancel live feed task: " + e.getMessage(), e);
            }
        }
        WorldBiomeStore.saveAll();
        if (writeFinal && files) {
            try {
                write(liveDir.resolve(FEED_FILE), feedScript(feedJson(clock.getAsLong(), null)));
            } catch (IOException e) {
                RTP.log(Level.WARNING, "[editor] failed to write final live feed state: " + e.getMessage(), e);
            }
        }
        RTP.log(Level.INFO, "[editor] live feed stopped: " + reason);
        EditorChannel own = ownChannel;
        synchronized (LOCK) {
            if (active == this) active = null;
            if (own != null && channel == own) channel = null;
        }
        // The channel lives exactly as long as its feed (expiry, write failures, disable)
        if (own != null) own.close(reason);
    }

    private String feedJson(long now, String telemetry) {
        Map<String, Object> palettes = new LinkedHashMap<>();
        List<Object> surveyRows = new ArrayList<>();
        Set<String> seenWorlds = new HashSet<>();
        for (WorldLandSurvey s : surveys) {
            int w = worldIds.get(s.world());
            if (seenWorlds.add(s.world())) palettes.put(String.valueOf(w), s.store().palette());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("world", s.world());
            row.put("w", w);
            row.put("y", s.y());
            row.put("listed", s.listed());
            row.put("level", s.level());
            row.put("progress", Math.round(s.progress() * 1000) / 1000.0);
            row.put("filesRead", s.filesRead());
            row.put("fileTotal", s.fileTotal());
            row.put("bins", s.store().binCount());
            row.put("truncated", s.truncated());
            row.put("done", s.isDone());
            surveyRows.add(row);
        }
        List<Object> regionRows = new ArrayList<>();
        for (RegionTrack t : tracks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("region", t.name);
            row.put("r", t.r);
            if (t.path != null) {
                row.put("tilesDone", t.path.tilesDone());
                row.put("tilesTotal", t.path.tilesTotal());
                row.put("truncated", t.path.truncated());
                row.put("done", t.path.isDone());
            }
            row.put("landings", LandingHeatmap.total(t.name));
            if (!t.biomes.isEmpty()) row.put("biomes", t.biomes);
            regionRows.add(row);
        }
        Map<String, List<Object>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, Long> g : groupVersions.entrySet()) {
            String[] p = g.getKey().split("\\|");
            groups.computeIfAbsent(p[0], k -> new ArrayList<>()).add(List.of(p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]), g.getValue()));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("seq", seq);
        m.put("timestamp", now);
        m.put("intervalMs", PERIOD_MILLIS);
        m.put("expiresAt", expiresAt);
        m.put("stopped", stopped);
        m.put("reason", stopReason);
        m.put("groupBins", GROUP_BINS);
        m.put("pathBatches", pathBatches);
        m.put("palettes", palettes);
        m.put("surveys", surveyRows);
        m.put("regions", regionRows);
        m.put("groups", groups);
        String json = EditorSessionManager.mapToJson(m);
        return json.substring(0, json.length() - 1) + ",\"telemetry\":" + (telemetry == null ? "null" : telemetry) + "}";
    }

    private static String feedScript(String json) {
        return "window.__rtpLiveFeed&&window.__rtpLiveFeed(" + scriptSafe(json) + ");\n";
    }

    private static String b64(byte[] runs) {
        return Base64.getEncoder().encodeToString(runs);
    }

    /** Index batch: {@code tiles} rows are {@code [tx, tz, cellsOnCurve]}. */
    private String pathIndexScript(int index, int r, WalkPathTiles.Batch batch) {
        List<int[]> tiles = new ArrayList<>();
        for (WalkPathTiles.Tile t : batch.tiles()) tiles.add(new int[]{t.tx(), t.tz(), t.cells()});
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("region", batch.region());
        m.put("r", r);
        m.put("tileChunks", WalkPathTiles.TILE_CHUNKS);
        m.put("tiles", tiles);
        m.put("tilesDone", batch.tilesDone());
        m.put("tilesTotal", batch.tilesTotal());
        m.put("done", batch.done());
        return "window.__rtpLivePath&&window.__rtpLivePath(" + index + ","
                + scriptSafe(EditorSessionManager.mapToJson(m)) + ");\n";
    }

    /** Path tile: {@code locs[lz * 32 + lx]}, {@code -1} off the curve. */
    private String pathTileScript(int r, WalkPathTiles.Tile tile) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("locs", tile.locs());
        return "window.__rtpLivePathTile&&window.__rtpLivePathTile(" + r + "," + tile.tx() + "," + tile.tz() + ","
                + scriptSafe(EditorSessionManager.mapToJson(m)) + ");\n";
    }

    /** {@code <} only occurs inside JSON strings, so unicode-escaping it is lossless. */
    private static String scriptSafe(String json) {
        return json.replace("<", "\\u003c");
    }

    /** Feed file write; nothing for a hosted feed. */
    private void write(Path target, String content) throws IOException {
        if (files) writeAtomically(target, content);
    }

    static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Path liveDir() {
        return liveDir;
    }

    public long seq() {
        return seq;
    }

    public int pathBatches() {
        return pathBatches;
    }

    /** Tile groups written so far, all kinds. */
    public int groupCount() {
        return groupVersions.size();
    }

    List<WorldLandSurvey> surveys() {
        return List.copyOf(surveys);
    }

    List<RegionTrack> tracks() {
        return List.copyOf(tracks);
    }

    public boolean isStopped() {
        return stopped;
    }

    public String stopReason() {
        return stopReason;
    }

    String sessionId() {
        return sessionId;
    }
}
