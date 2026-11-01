package net.leaf.sleuth.common.fuzz;

import io.github.dailystruggle.helpers.stresstestrtp.ConsoleWatcher;
import io.github.dailystruggle.helpers.stresstestrtp.LeakProbe;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-JVM fault signature oracle combining {@link ConsoleWatcher#attachRootLoggerTap} JUL
 * root-logger exception/error interception and {@link LeakProbe#threadPrint()} / live in-flight
 * main-thread stack sampling on slow ticks ({@code > 100 ms}), resolving the deepest non-platform
 * plugin frame through {@link JarClassOwnershipIndex} into deduplicated fault signatures
 * ({@code exc:<ExceptionClass>:<TopPluginFrame>} and {@code stall:<TopPluginFrame>})
 * ({@code ADR-125}, {@code leafsleuth-ADR-006}).
 *
 * <p><b>Logging &amp; Threading Scope:</b> The in-JVM JUL tap intercepts {@code java.util.logging}
 * records at {@link Level#WARNING} or higher (or carrying a {@link Throwable}), covering plugin
 * loggers ({@code JavaPlugin#getLogger()}), {@code SimplePluginManager} {@code Could not pass event}
 * traces, and Bukkit scheduler task exceptions. Subsystem messages written exclusively to internal
 * Log4j2/SLF4J loggers without JUL forwarding can be fed via {@link #ingestLogText(String)}.
 * On Folia, live tick-thread stall sampling tracks the global region tick thread; regionized tick
 * threads can be analyzed via {@link #recordThreadDumpStall(String, double)} or
 * {@link #captureLiveThreadPrintStall(double)}.
 *
 * <p>Thread-safe and pure Java 17 with zero platform imports ({@code ADR-129}).
 *
 * @governedBy ADR-115
 * @governedBy ADR-122
 * @governedBy ADR-125
 * @governedBy ADR-129
 * @governedBy leafsleuth-ADR-006
 * @trace REQ-SLEUTH-FUZZ-003
 * @trace S-004
 * @trace S-005
 */
public final class LogAndStallSignatureOracle {

    /** Default slow-tick MSPT threshold ({@code 100.0 ms}) above which main-thread stall frames are attributed. */
    public static final double DEFAULT_SLOW_TICK_THRESHOLD_MS = 100.0;

    /** Default main-thread hazard MSPT threshold ({@code 15.0 ms}) for sync-I/O, sync-chunk, and blocking-wait traps. */
    public static final double DEFAULT_HAZARD_TICK_THRESHOLD_MS = 15.0;

    /** Default stuck-worker duration threshold ({@code 5,000 ms}) for blocked/parked plugin worker threads. */
    public static final long DEFAULT_STUCK_WORKER_THRESHOLD_MS = 5_000L;

    private static final Logger LOGGER = Logger.getLogger(LogAndStallSignatureOracle.class.getName());
    private static final int MAX_SIGNATURES = 512;

    private static final Pattern LOG_PREFIX = Pattern.compile("^\\[\\d{1,2}:\\d{2}:\\d{2}[^\\]]*\\]:?\\s?");
    private static final Pattern EVENT_ERROR = Pattern.compile("Could not pass event (\\S+) to (\\S+)");
    private static final Pattern EXC_TYPE = Pattern.compile(
            "(?<![\\w$.])((?:[a-z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error|Throwable))(?=$|[:\\s])"
    );
    private static final Pattern FRAME_LINE = Pattern.compile(
            "^\\s*at\\s+(?:[^\\s/(]+//)?(?:[\\w.@-]+/(?!0x[0-9a-fA-F]+\\.))?([\\w.$<>]+(?:/0x[0-9a-fA-F]+\\.[\\w$<>]+)?)\\("
    );
    private static final Pattern THREAD_HEADER = Pattern.compile("^\\s*\"([^\"]+)\"");
    private static final Pattern LAMBDA_CLASS_SUFFIX = Pattern.compile(
            "\\$\\$?Lambda(?:\\$\\d+)?(?:/(?:0x[0-9a-fA-F]+|\\d+))?"
    );
    private static final Pattern HIDDEN_CLASS_SUFFIX = Pattern.compile(
            "/(?:0x[0-9a-fA-F]+|\\d+)(?=\\.|\\$|$)"
    );
    private static final Pattern LAMBDA_METHOD_INDEX = Pattern.compile(
            "(?<=\\.|\\$)(lambda\\$[\\w$]*?\\$)\\d+(?=\\$|\\b)"
    );
    private static final Pattern ANON_INNER_CLASS = Pattern.compile(
            "\\$\\d+(?=\\$|\\.|$)"
    );
    private static final Pattern GENERATED_ACCESSOR = Pattern.compile(
            "(Generated(?:Method|Constructor|SerializationConstructor)Accessor)\\d+\\b"
    );

    private static final List<String> PLATFORM_PACKAGE_PREFIXES = List.of(
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "com.sun.",
            "kotlin.",
            "org.bukkit.",
            "org.spigotmc.",
            "io.papermc.",
            "net.minecraft.",
            "io.papermc.",
            "com.destroystokyo.",
            "net.md_5.",
            "io.netty.",
            "com.mojang.",
            "co.aikar.",
            "net.fabricmc.",
            "org.spongepowered.asm.",
            "net.neoforged.",
            "cpw.mods.",
            "org.slf4j.",
            "org.apache.",
            "com.google.common.",
            "it.unimi.dsi.",
            "net.leaf.sleuth.",
            "net.leaf.fuzz.",
            "io.github.dailystruggle.helpers.stresstestrtp."
    );

    private static final List<String> NON_MAIN_THREAD_TOKENS = List.of(
            "netty",
            "epoll",
            "nio",
            "io ",
            "io-",
            "worker",
            "async",
            "watchdog",
            "rcon",
            "query",
            "timer",
            "scheduler",
            "pool",
            "cleaner",
            "destroyjavavm"
    );

    /**
     * Deduplicated fault signature entry (`exc:<ExceptionClass>:<TopPluginFrame>` or `stall:<TopPluginFrame>`).
     *
     * @param signatureId       canonical deduplicated signature ID
     * @param kind              {@code "EXCEPTION"} or {@code "STALL"}
     * @param exceptionClass    root exception class name for {@code "EXCEPTION"} findings, or {@code null}
     * @param topPluginFrame    normalized top plugin frame (`pkg.Class.method`), or {@code "server"}
     * @param suspectJar        attributed plugin JAR from {@link JarClassOwnershipIndex} (or {@code "shared"} / {@code "server"})
     * @param occurrences       total occurrences recorded for this signature
     * @param maxDurationMs     peak slow-tick MSPT recorded for {@code "STALL"} signatures (0.0 for exceptions)
     * @param firstSeenEpochMs  epoch milliseconds when first observed
     * @param lastSeenEpochMs   epoch milliseconds when most recently observed
     * @param sampleMessage     representative log line or stall description
     */
    public record FaultSignature(
            String signatureId,
            String kind,
            String exceptionClass,
            String topPluginFrame,
            String suspectJar,
            long occurrences,
            double maxDurationMs,
            long firstSeenEpochMs,
            long lastSeenEpochMs,
            String sampleMessage
    ) {
        public FaultSignature {
            Objects.requireNonNull(signatureId, "signatureId");
            kind = (kind == null || kind.isBlank()) ? "EXCEPTION" : kind;
            topPluginFrame = (topPluginFrame == null || topPluginFrame.isBlank()) ? "server" : topPluginFrame;
            suspectJar = (suspectJar == null || suspectJar.isBlank()) ? "server" : suspectJar;
            occurrences = Math.max(1L, occurrences);
            maxDurationMs = Math.max(0.0, maxDurationMs);
            sampleMessage = sampleMessage == null ? "" : sampleMessage;
        }

        public boolean isPluginAttributed() {
            return !"server".equalsIgnoreCase(suspectJar)
                    && !"shared".equalsIgnoreCase(suspectJar)
                    && !"unknown".equalsIgnoreCase(suspectJar);
        }
    }

    private volatile JarClassOwnershipIndex ownershipIndex;
    private volatile double slowTickThresholdMs = DEFAULT_SLOW_TICK_THRESHOLD_MS;
    private final Map<String, FaultSignature> signaturesById = new ConcurrentHashMap<>();
    private final Map<Long, ParkedWorkerSighting> parkedWorkersByThreadId = new ConcurrentHashMap<>();
    private final AtomicLong totalExceptionsRecorded = new AtomicLong();
    private final AtomicLong totalStallsRecorded = new AtomicLong();
    private final AtomicLong totalHazardsRecorded = new AtomicLong();
    private final AtomicBoolean handlerInstalled = new AtomicBoolean(false);
    private volatile Handler rootLoggerHandler;

    private record ParkedWorkerSighting(String normalizedPluginFrame, String suspectJar, long firstSeenEpochMs) {
    }

    public LogAndStallSignatureOracle() {
        this(JarClassOwnershipIndex.empty());
    }

    public LogAndStallSignatureOracle(JarClassOwnershipIndex ownershipIndex) {
        this.ownershipIndex = ownershipIndex != null ? ownershipIndex : JarClassOwnershipIndex.empty();
    }

    /**
     * Updates the {@link JarClassOwnershipIndex} used to resolve stack frames to owning plugin JARs
     * and refreshes any previously recorded signatures whose frame now maps to an indexed JAR.
     *
     * @param ownershipIndex updated ownership index
     */
    public void setOwnershipIndex(JarClassOwnershipIndex ownershipIndex) {
        JarClassOwnershipIndex idx = ownershipIndex != null ? ownershipIndex : JarClassOwnershipIndex.empty();
        this.ownershipIndex = idx;
        for (Map.Entry<String, FaultSignature> entry : signaturesById.entrySet()) {
            FaultSignature prev = entry.getValue();
            if ("server".equals(prev.suspectJar()) && !"server".equals(prev.topPluginFrame())) {
                String refreshedJar = resolveJarForFrame(prev.topPluginFrame(), idx);
                if (!refreshedJar.equals(prev.suspectJar())) {
                    signaturesById.put(entry.getKey(), new FaultSignature(
                            prev.signatureId(),
                            prev.kind(),
                            prev.exceptionClass(),
                            prev.topPluginFrame(),
                            refreshedJar,
                            prev.occurrences(),
                            prev.maxDurationMs(),
                            prev.firstSeenEpochMs(),
                            prev.lastSeenEpochMs(),
                            prev.sampleMessage()
                    ));
                }
            }
        }
    }

    public JarClassOwnershipIndex ownershipIndex() {
        return ownershipIndex;
    }

    public double slowTickThresholdMs() {
        return slowTickThresholdMs;
    }

    public void setSlowTickThresholdMs(double slowTickThresholdMs) {
        if (Double.isFinite(slowTickThresholdMs) && slowTickThresholdMs > 0.0) {
            this.slowTickThresholdMs = slowTickThresholdMs;
        }
    }

    /**
     * Attaches a non-blocking JUL {@link Handler} to the root logger ({@code Logger.getLogger("")})
     * via {@link ConsoleWatcher#attachRootLoggerTap} to intercept uncaught exceptions and
     * {@code Could not pass event} traces at {@link Level#WARNING} or higher in-JVM.
     */
    public void startRootLoggerTap() {
        if (!handlerInstalled.compareAndSet(false, true)) {
            return;
        }
        try {
            this.rootLoggerHandler = ConsoleWatcher.attachRootLoggerTap(
                    Level.WARNING,
                    List.of("LeafSleuth", "StressTestRTP"),
                    this::handleRootLogRecord
            );
        } catch (SecurityException e) {
            handlerInstalled.set(false);
            LOGGER.log(Level.FINE, "[LeafSleuth] Unable to attach root logger handler", e);
        }
    }

    private void handleRootLogRecord(LogRecord record) {
        String rawMsg = ConsoleWatcher.formatRecordMessage(record);
        String msg = rawMsg != null ? ConsoleWatcher.stripFormatting(rawMsg) : null;
        Throwable thrown = record.getThrown();
        long epochMs = record.getMillis() > 0L ? record.getMillis() : System.currentTimeMillis();
        if (thrown != null) {
            recordThrowable(thrown, msg, epochMs);
        } else if (msg != null && !msg.isEmpty()) {
            if (msg.contains("Exception") || msg.contains("Error") || msg.contains("Could not pass event")) {
                ingestLogText(msg, epochMs);
            }
        }
    }

    /**
     * Detaches the root-logger handler if installed via {@link ConsoleWatcher#detachRootLoggerTap}.
     */
    public void stopRootLoggerTap() {
        if (!handlerInstalled.compareAndSet(true, false)) {
            return;
        }
        Handler tap = this.rootLoggerHandler;
        this.rootLoggerHandler = null;
        if (tap != null) {
            try {
                ConsoleWatcher.detachRootLoggerTap(tap);
            } catch (SecurityException e) {
                LOGGER.log(Level.FINE, "[LeafSleuth] Unable to detach root logger handler", e);
            }
        }
    }

    /**
     * Records a live {@link Throwable} and attributes its deepest/topmost plugin stack frame
     * via {@link JarClassOwnershipIndex}.
     *
     * @param throwable uncaught exception or logged error
     * @param context   optional log message context
     * @return recorded or updated {@link FaultSignature}
     */
    public FaultSignature recordThrowable(Throwable throwable, String context) {
        return recordThrowable(throwable, context, System.currentTimeMillis());
    }

    public FaultSignature recordThrowable(Throwable throwable, String context, long epochMs) {
        Objects.requireNonNull(throwable, "throwable");
        Throwable rootCause = unwrapRootCause(throwable);
        String excClass = rootCause.getClass().getName();

        List<String> candidateFrames = new ArrayList<>();
        Throwable cursor = rootCause;
        collectStackFrames(cursor.getStackTrace(), candidateFrames);
        if (rootCause != throwable) {
            collectStackFrames(throwable.getStackTrace(), candidateFrames);
        }

        JarClassOwnershipIndex idx = this.ownershipIndex;
        FrameAttribution attr = selectCulpritFrame(candidateFrames, idx, extractEventPluginHint(context));
        String sigId = "exc:" + excClass + ":" + attr.normalizedFrame();
        String summary = (context != null && !context.isBlank())
                ? firstLine(context)
                : (excClass + (rootCause.getMessage() != null ? ": " + rootCause.getMessage() : ""));
        totalExceptionsRecorded.incrementAndGet();
        return upsertSignature(
                sigId,
                "EXCEPTION",
                excClass,
                attr.normalizedFrame(),
                attr.suspectJar(),
                0.0,
                epochMs,
                summary
        );
    }

    /**
     * Parses multi-line console/server log text for uncaught exception blocks and
     * {@code Could not pass event} stack traces, attributing each to its owning plugin JAR.
     *
     * @param logText raw log text
     * @return list of deduplicated fault signatures extracted from {@code logText}
     */
    public List<FaultSignature> ingestLogText(String logText) {
        return ingestLogText(logText, System.currentTimeMillis());
    }

    public List<FaultSignature> ingestLogText(String logText, long epochMs) {
        if (logText == null || logText.isBlank()) {
            return List.of();
        }
        String[] lines = logText.split("\\R");
        List<FaultSignature> recorded = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.contains("StressTestRTP: leakcheck") || line.contains("[LeafSleuth]")) {
                i++;
                continue;
            }
            Matcher evMatcher = EVENT_ERROR.matcher(line);
            if (evMatcher.find()) {
                int j = i + 1;
                if (j < lines.length && isExceptionHeader(lines[j])) {
                    j++;
                }
                while (j < lines.length && isTraceContinuation(lines[j])) {
                    j++;
                }
                List<String> block = new ArrayList<>(j - i);
                for (int k = i; k < j; k++) {
                    block.add(lines[k]);
                }
                FaultSignature sig = recordExceptionBlock(block, evMatcher.group(2), epochMs);
                if (sig != null) {
                    recorded.add(sig);
                }
                i = Math.max(i + 1, j);
                continue;
            }
            if (isExceptionHeader(line) && i + 1 < lines.length && isTraceContinuation(lines[i + 1])) {
                int j = i + 1;
                while (j < lines.length && isTraceContinuation(lines[j])) {
                    j++;
                }
                List<String> block = new ArrayList<>(j - i);
                for (int k = i; k < j; k++) {
                    block.add(lines[k]);
                }
                FaultSignature sig = recordExceptionBlock(block, null, epochMs);
                if (sig != null) {
                    recorded.add(sig);
                }
                i = Math.max(i + 1, j);
                continue;
            }
            i++;
        }
        return recorded;
    }

    /**
     * Evaluates a main-thread stack trace captured on a slow tick; if {@code tickDurationMs}
     * exceeds {@link #slowTickThresholdMs()} and {@code !LeakProbe.inGcPauseWindow(nowNanos)},
     * records a deduplicated {@code stall:<TopPluginFrame>} signature.
     *
     * @param tickDurationMs measured tick duration in milliseconds
     * @param mainThreadStack stack trace elements of the server main thread
     * @param nowNanos       current monotonic nanoTime
     * @return recorded {@link FaultSignature} if a non-GC slow tick occurred, else {@link Optional#empty()}
     */
    public Optional<FaultSignature> recordSlowTickStack(
            double tickDurationMs,
            StackTraceElement[] mainThreadStack,
            long nowNanos
    ) {
        if (!Double.isFinite(tickDurationMs) || tickDurationMs <= this.slowTickThresholdMs) {
            return Optional.empty();
        }
        if (LeakProbe.inGcPauseWindow(nowNanos)) {
            return Optional.empty();
        }
        List<String> frames = new ArrayList<>();
        collectStackFrames(mainThreadStack, frames);
        if (frames.isEmpty()) {
            return Optional.empty();
        }
        FrameAttribution attr = selectCulpritFrame(frames, this.ownershipIndex, null);
        String sigId = "stall:" + attr.normalizedFrame();
        long nowMs = System.currentTimeMillis();
        String msg = String.format(
                Locale.ROOT,
                "Slow tick %.1f ms (> %.1f ms threshold) at %s [%s]",
                tickDurationMs,
                this.slowTickThresholdMs,
                attr.normalizedFrame(),
                attr.suspectJar()
        );
        totalStallsRecorded.incrementAndGet();
        return Optional.of(upsertSignature(
                sigId,
                "STALL",
                null,
                attr.normalizedFrame(),
                attr.suspectJar(),
                tickDurationMs,
                nowMs,
                msg
        ));
    }

    /**
     * Parses a HotSpot {@code Thread.print} dump (such as {@link LeakProbe#threadPrint()}),
     * extracts the server main thread's stack trace, and records a {@code stall:<TopPluginFrame>}
     * signature.
     *
     * @param threadDumpText HotSpot thread dump text
     * @param tickDurationMs associated slow-tick or stall duration in milliseconds
     * @return recorded {@link FaultSignature} if a main thread stack was found
     */
    public Optional<FaultSignature> recordThreadDumpStall(String threadDumpText, double tickDurationMs) {
        return recordThreadDumpStall(threadDumpText, tickDurationMs, System.currentTimeMillis());
    }

    public Optional<FaultSignature> recordThreadDumpStall(String threadDumpText, double tickDurationMs, long epochMs) {
        List<String> mainFrames = parseMainThreadFrames(threadDumpText);
        if (mainFrames.isEmpty()) {
            return Optional.empty();
        }
        FrameAttribution attr = selectCulpritFrame(mainFrames, this.ownershipIndex, null);
        String sigId = "stall:" + attr.normalizedFrame();
        double effMs = Double.isFinite(tickDurationMs) && tickDurationMs > 0.0
                ? tickDurationMs
                : this.slowTickThresholdMs;
        String msg = String.format(
                Locale.ROOT,
                "Main-thread stall %.1f ms at %s [%s]",
                effMs,
                attr.normalizedFrame(),
                attr.suspectJar()
        );
        totalStallsRecorded.incrementAndGet();
        return Optional.of(upsertSignature(
                sigId,
                "STALL",
                null,
                attr.normalizedFrame(),
                attr.suspectJar(),
                effMs,
                epochMs,
                msg
        ));
    }

    /**
     * Captures a live JVM thread dump via {@link LeakProbe#threadPrint()} and attributes the
     * main server thread stack if {@code tickDurationMs > slowTickThresholdMs} and outside any
     * {@link LeakProbe#inGcPauseWindow(long)} exclusion window.
     *
     * @param tickDurationMs slow-tick duration in milliseconds
     * @return recorded stall signature if captured
     */
    public Optional<FaultSignature> captureLiveThreadPrintStall(double tickDurationMs) {
        return captureLiveThreadPrintStall(tickDurationMs, System.nanoTime());
    }

    public Optional<FaultSignature> captureLiveThreadPrintStall(double tickDurationMs, long nowNanos) {
        if (!Double.isFinite(tickDurationMs) || tickDurationMs <= this.slowTickThresholdMs) {
            return Optional.empty();
        }
        if (LeakProbe.inGcPauseWindow(nowNanos)) {
            return Optional.empty();
        }
        try {
            String dump = LeakProbe.threadPrint();
            return recordThreadDumpStall(dump, tickDurationMs);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "[LeafSleuth] Failed to capture diagnostic threadPrint for stall attribution", e);
            return Optional.empty();
        }
    }

    /**
     * Extracts the ordered stack frames (`pkg.Class.method`) of the server main thread from
     * a HotSpot {@code Thread.print} text dump.
     *
     * @param threadDumpText raw `Thread.print` output
     * @return ordered list of `pkg.Class.method` frames on the main thread
     */
    public static List<String> parseMainThreadFrames(String threadDumpText) {
        if (threadDumpText == null || threadDumpText.isBlank()) {
            return List.of();
        }
        Map<String, List<String>> threads = new LinkedHashMap<>();
        String curName = null;
        List<String> curFrames = new ArrayList<>();

        for (String line : threadDumpText.split("\\R")) {
            Matcher hdr = THREAD_HEADER.matcher(line);
            if (hdr.find()) {
                if (curName != null) {
                    threads.putIfAbsent(curName, curFrames);
                }
                curName = hdr.group(1).trim();
                curFrames = new ArrayList<>();
                continue;
            }
            if (curName != null) {
                Matcher fr = FRAME_LINE.matcher(line);
                if (fr.find()) {
                    curFrames.add(fr.group(1).trim());
                }
            }
        }
        if (curName != null) {
            threads.putIfAbsent(curName, curFrames);
        }

        // Priority 1: exact "Server thread"
        for (Map.Entry<String, List<String>> e : threads.entrySet()) {
            if ("server thread".equalsIgnoreCase(e.getKey()) && !e.getValue().isEmpty()) {
                return Collections.unmodifiableList(e.getValue());
            }
        }
        // Priority 2: exact "main" with stack frames
        for (Map.Entry<String, List<String>> e : threads.entrySet()) {
            if ("main".equalsIgnoreCase(e.getKey()) && !e.getValue().isEmpty()) {
                return Collections.unmodifiableList(e.getValue());
            }
        }
        // Priority 3: thread name containing "server thread" or "region #" (Folia tick thread)
        for (Map.Entry<String, List<String>> e : threads.entrySet()) {
            String low = e.getKey().toLowerCase(Locale.ROOT);
            if ((low.contains("server thread") || low.contains("tick thread") || low.contains("region #"))
                    && !e.getValue().isEmpty()
                    && !containsNonMainToken(low)) {
                return Collections.unmodifiableList(e.getValue());
            }
        }
        // Priority 4: non-IO/non-worker server thread
        for (Map.Entry<String, List<String>> e : threads.entrySet()) {
            String low = e.getKey().toLowerCase(Locale.ROOT);
            if (low.contains("server") && !e.getValue().isEmpty() && !containsNonMainToken(low)) {
                return Collections.unmodifiableList(e.getValue());
            }
        }
        return List.of();
    }

    /**
     * Returns all deduplicated fault signatures ranked by plugin attribution, occurrence count,
     * and peak stall duration.
     *
     * @return immutable ranked list of fault signatures
     */
    public List<FaultSignature> rankedSignatures() {
        List<FaultSignature> list = new ArrayList<>(signaturesById.values());
        list.sort(Comparator
                .comparing((FaultSignature s) -> !s.isPluginAttributed())
                .thenComparing(Comparator.comparingLong(FaultSignature::occurrences).reversed())
                .thenComparing(Comparator.comparingDouble(FaultSignature::maxDurationMs).reversed())
                .thenComparing(FaultSignature::signatureId));
        return Collections.unmodifiableList(list);
    }

    public Optional<FaultSignature> signature(String signatureId) {
        if (signatureId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(signaturesById.get(signatureId));
    }

    public long totalExceptionsRecorded() {
        return totalExceptionsRecorded.get();
    }

    public long totalStallsRecorded() {
        return totalStallsRecorded.get();
    }

    public long totalHazardsRecorded() {
        return totalHazardsRecorded.get();
    }

    public void clear() {
        signaturesById.clear();
        parkedWorkersByThreadId.clear();
        totalExceptionsRecorded.set(0L);
        totalStallsRecorded.set(0L);
        totalHazardsRecorded.set(0L);
    }

    /**
     * Extracts zero-instrumentation grey-box code-coverage tokens
     * ({@code cov:<Jar>:<Class.method:lineBucket>}) from a sampled thread stack trace for classes
     * owned by indexed plugin/mod archives.
     *
     * @param stack sampled thread stack trace elements
     * @return ordered set of normalized coverage tokens
     */
    public Set<String> extractTargetCoverageTokens(StackTraceElement[] stack) {
        if (stack == null || stack.length == 0) {
            return Set.of();
        }
        JarClassOwnershipIndex idx = this.ownershipIndex;
        boolean hasIndex = idx != null && idx.indexedJarCount() > 0;
        Set<String> tokens = new LinkedHashSet<>();
        for (StackTraceElement el : stack) {
            if (el == null) {
                continue;
            }
            String cls = el.getClassName();
            String method = el.getMethodName();
            if (cls == null || cls.isEmpty() || method == null || method.isEmpty()) {
                continue;
            }
            if (cls.startsWith("net.leaf.fuzz.") || cls.startsWith("net.leaf.sleuth.")) {
                break;
            }
            if (isPlatformOrJdkClass(cls)) {
                continue;
            }
            String ownerJar = null;
            if (hasIndex) {
                ownerJar = idx.ownerJarForClass(cls).orElse(null);
                if (ownerJar == null) {
                    continue;
                }
            } else {
                ownerJar = "plugin";
            }
            String normFrame = normalizeFrameDigits(cls + "." + method);
            int lineBucket = el.getLineNumber() > 0 ? ((el.getLineNumber() / 8) * 8) : 0;
            tokens.add("cov:" + ownerJar + ":" + normFrame + ":" + lineBucket);
            if (tokens.size() >= 16) {
                break;
            }
        }
        return tokens;
    }

    /**
     * Samples the server main thread and active non-harness worker threads to collect grey-box
     * stack-frame coverage tokens ({@code cov:<Jar>:<Class.method:lineBucket>}) for
     * {@code NodeBandit} novelty guidance.
     *
     * @param mainThread server primary tick thread (may be {@code null})
     * @return set of coverage tokens observed across active threads
     */
    public Set<String> sampleActiveThreadCoverage(Thread mainThread) {
        Set<String> out = new LinkedHashSet<>();
        Thread current = Thread.currentThread();
        if (mainThread != null && mainThread != current) {
            out.addAll(extractTargetCoverageTokens(mainThread.getStackTrace()));
        }
        try {
            Map<Thread, StackTraceElement[]> allStacks = Thread.getAllStackTraces();
            for (Map.Entry<Thread, StackTraceElement[]> entry : allStacks.entrySet()) {
                if (out.size() >= 64) {
                    break;
                }
                Thread t = entry.getKey();
                if (t == null || t == current || t == mainThread) {
                    continue;
                }
                Thread.State state = t.getState();
                if (state == Thread.State.NEW || state == Thread.State.TERMINATED) {
                    continue;
                }
                String name = t.getName() != null ? t.getName().toLowerCase(Locale.ROOT) : "";
                if (name.startsWith("reference handler")
                        || name.startsWith("finalizer")
                        || name.startsWith("signal dispatcher")
                        || name.startsWith("common-cleaner")
                        || name.startsWith("notification thread")
                        || name.startsWith("attach listener")
                        || name.contains("netty")
                        || name.contains("jfr")) {
                    continue;
                }
                StackTraceElement[] stack = entry.getValue();
                if (stack != null && stack.length > 0) {
                    out.addAll(extractTargetCoverageTokens(stack));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * Inspects a main-thread stack trace for sub-100ms synchronous hazards ({@code sync-chunk}
     * {@code S-005} synchronous chunk loads, {@code sync-io} disk/network/SQL I/O, and
     * {@code blocking-wait} future/latch parks on the main thread) and records a deduplicated
     * {@code hazard:<kind>:<TopPluginFrame>} signature when attributed to a plugin/mod frame.
     *
     * @param tickDurationMs  elapsed main-thread tick duration in milliseconds
     * @param mainThreadStack stack trace elements of the main server thread
     * @param nowNanos        current monotonic nanoTime
     * @return recorded {@link FaultSignature} if a main-thread hazard was detected
     */
    public Optional<FaultSignature> recordMainThreadHazardStack(
            double tickDurationMs,
            StackTraceElement[] mainThreadStack,
            long nowNanos
    ) {
        if (!Double.isFinite(tickDurationMs)
                || tickDurationMs < 2.0
                || mainThreadStack == null
                || mainThreadStack.length == 0) {
            return Optional.empty();
        }
        if (LeakProbe.inGcPauseWindow(nowNanos)) {
            return Optional.empty();
        }
        List<String> frames = new ArrayList<>();
        collectStackFrames(mainThreadStack, frames);
        if (frames.isEmpty()) {
            return Optional.empty();
        }
        FrameAttribution attr = selectCulpritFrame(frames, this.ownershipIndex, null);
        if ("server".equalsIgnoreCase(attr.normalizedFrame()) || "server".equalsIgnoreCase(attr.suspectJar())) {
            return Optional.empty();
        }

        // Determine if classloading is occurring between the plugin frame and top of stack
        boolean insideClassLoader = false;
        String hazardKind = null;
        String triggerCall = null;
        for (StackTraceElement el : mainThreadStack) {
            if (el == null) {
                continue;
            }
            String cls = el.getClassName();
            String method = el.getMethodName();
            if (cls == null || method == null) {
                continue;
            }
            if (cls.startsWith("net.leaf.fuzz.") || cls.startsWith("net.leaf.sleuth.")) {
                break;
            }
            if ((cls.startsWith("java.lang.ClassLoader") && "loadClass".equals(method))
                    || ("java.lang.Class".equals(cls) && "forName0".equals(method))) {
                insideClassLoader = true;
            }
            if (hazardKind == null) {
                if (isSyncChunkHazardFrame(cls, method, tickDurationMs)) {
                    hazardKind = "sync-chunk";
                    triggerCall = simpleClass(cls) + "." + method;
                } else if (tickDurationMs >= DEFAULT_HAZARD_TICK_THRESHOLD_MS
                        && isSyncIoHazardFrame(cls, method)) {
                    hazardKind = "sync-io";
                    triggerCall = simpleClass(cls) + "." + method;
                } else if (tickDurationMs >= DEFAULT_HAZARD_TICK_THRESHOLD_MS
                        && isBlockingWaitHazardFrame(cls, method)) {
                    hazardKind = "blocking-wait";
                    triggerCall = simpleClass(cls) + "." + method;
                }
            }
        }
        if (hazardKind == null || ("sync-io".equals(hazardKind) && insideClassLoader)) {
            return Optional.empty();
        }

        String sigId = "hazard:" + hazardKind + ":" + attr.normalizedFrame();
        long nowMs = System.currentTimeMillis();
        String msg = String.format(
                Locale.ROOT,
                "Main-thread %s hazard (%.1f ms via %s) at %s [%s]",
                hazardKind,
                tickDurationMs,
                triggerCall,
                attr.normalizedFrame(),
                attr.suspectJar()
        );
        totalHazardsRecorded.incrementAndGet();
        return Optional.of(upsertSignature(
                sigId,
                "HAZARD",
                hazardKind,
                attr.normalizedFrame(),
                attr.suspectJar(),
                tickDurationMs,
                nowMs,
                msg
        ));
    }

    /**
     * Checks JVM threads for monitor/synchronizer deadlocks via {@link ThreadMXBean#findDeadlockedThreads()}
     * and worker threads parked on the same plugin stack frame for {@code >= 5,000 ms}.
     *
     * @param nowNanos current monotonic nanoTime
     * @return list of newly recorded or updated deadlock / stuck-worker signatures
     */
    public List<FaultSignature> detectDeadlocksAndStuckWorkers(long nowNanos) {
        if (LeakProbe.inGcPauseWindow(nowNanos)) {
            return List.of();
        }
        List<FaultSignature> recorded = new ArrayList<>();
        long nowMs = System.currentTimeMillis();
        try {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            long[] deadlockedIds = bean.findDeadlockedThreads();
            if (deadlockedIds != null && deadlockedIds.length > 0) {
                ThreadInfo[] infos = bean.getThreadInfo(deadlockedIds, 32);
                if (infos != null) {
                    for (ThreadInfo info : infos) {
                        if (info == null) {
                            continue;
                        }
                        List<String> frames = new ArrayList<>();
                        collectStackFrames(info.getStackTrace(), frames);
                        if (frames.isEmpty()) {
                            continue;
                        }
                        FrameAttribution attr = selectCulpritFrame(frames, this.ownershipIndex, null);
                        String sigId = "hazard:deadlock:" + attr.normalizedFrame();
                        String msg = String.format(
                                Locale.ROOT,
                                "JVM thread deadlock on '%s' at %s [%s]",
                                info.getThreadName(),
                                attr.normalizedFrame(),
                                attr.suspectJar()
                        );
                        totalHazardsRecorded.incrementAndGet();
                        recorded.add(upsertSignature(
                                sigId,
                                "HAZARD",
                                "deadlock",
                                attr.normalizedFrame(),
                                attr.suspectJar(),
                                DEFAULT_STUCK_WORKER_THRESHOLD_MS,
                                nowMs,
                                msg
                        ));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            recorded.addAll(detectStuckWorkerThreads(Thread.getAllStackTraces(), null, nowMs));
        } catch (Throwable ignored) {
        }
        return recorded;
    }

    /**
     * Evaluates a snapshot of thread stack traces for non-main worker threads that remain parked
     * in {@link Thread.State#BLOCKED} or {@link Thread.State#WAITING} on the exact same plugin
     * frame across observations separated by at least {@link #DEFAULT_STUCK_WORKER_THRESHOLD_MS}.
     *
     * @param allStacks  thread-to-stack snapshot map
     * @param mainThread server main thread to exclude (may be {@code null})
     * @param nowEpochMs current epoch timestamp in milliseconds
     * @return list of stuck-worker hazard signatures recorded
     */
    public List<FaultSignature> detectStuckWorkerThreads(
            Map<Thread, StackTraceElement[]> allStacks,
            Thread mainThread,
            long nowEpochMs
    ) {
        if (allStacks == null || allStacks.isEmpty()) {
            return List.of();
        }
        List<FaultSignature> recorded = new ArrayList<>();
        Set<Long> activeParkedIds = new LinkedHashSet<>();
        Thread current = Thread.currentThread();
        for (Map.Entry<Thread, StackTraceElement[]> entry : allStacks.entrySet()) {
            Thread t = entry.getKey();
            StackTraceElement[] stack = entry.getValue();
            if (t == null || t == current || t == mainThread || stack == null || stack.length == 0) {
                continue;
            }
            String tName = t.getName() != null ? t.getName().toLowerCase(Locale.ROOT) : "";
            if ("server thread".equals(tName) || "main".equals(tName)) {
                continue;
            }
            Thread.State state = t.getState();
            if (state != Thread.State.BLOCKED && state != Thread.State.WAITING) {
                continue;
            }
            if (isIdleWorkerPoolPark(stack)) {
                continue;
            }
            List<String> frames = new ArrayList<>();
            collectStackFrames(stack, frames);
            if (frames.isEmpty()) {
                continue;
            }
            FrameAttribution attr = selectCulpritFrame(frames, this.ownershipIndex, null);
            if ("server".equalsIgnoreCase(attr.suspectJar()) || "server".equalsIgnoreCase(attr.normalizedFrame())) {
                continue;
            }
            long tid = t.getId();
            activeParkedIds.add(tid);
            ParkedWorkerSighting prev = parkedWorkersByThreadId.get(tid);
            if (prev == null || !prev.normalizedPluginFrame().equals(attr.normalizedFrame())) {
                parkedWorkersByThreadId.put(
                        tid,
                        new ParkedWorkerSighting(attr.normalizedFrame(), attr.suspectJar(), nowEpochMs)
                );
            } else {
                long parkedDurationMs = nowEpochMs - prev.firstSeenEpochMs();
                if (parkedDurationMs >= DEFAULT_STUCK_WORKER_THRESHOLD_MS) {
                    String sigId = "hazard:stuck-worker:" + attr.normalizedFrame();
                    String msg = String.format(
                            Locale.ROOT,
                            "Stuck worker thread '%s' (%s for %d ms) at %s [%s]",
                            t.getName(),
                            state.name(),
                            parkedDurationMs,
                            attr.normalizedFrame(),
                            attr.suspectJar()
                    );
                    totalHazardsRecorded.incrementAndGet();
                    recorded.add(upsertSignature(
                            sigId,
                            "HAZARD",
                            "stuck-worker",
                            attr.normalizedFrame(),
                            attr.suspectJar(),
                            parkedDurationMs,
                            nowEpochMs,
                            msg
                    ));
                }
            }
        }
        parkedWorkersByThreadId.keySet().removeIf(id -> !activeParkedIds.contains(id));
        return recorded;
    }

    /**
     * Records a custom deterministic hazard signature (such as GUI menu item extraction or
     * post-campaign repeating-task MSPT creep).
     *
     * @param hazardKind     short hazard category (e.g. {@code "gui-item-leak"}, {@code "task-creep"})
     * @param topPluginFrame normalized culprit frame or symbol
     * @param suspectJar     owning plugin/mod archive
     * @param metricValue    associated duration or delta metric
     * @param sampleMessage  descriptive summary
     * @return recorded {@link FaultSignature}
     */
    public FaultSignature recordCustomHazard(
            String hazardKind,
            String topPluginFrame,
            String suspectJar,
            double metricValue,
            String sampleMessage
    ) {
        String kindSlug = (hazardKind == null || hazardKind.isBlank()) ? "custom" : hazardKind.trim();
        String normFrame = normalizeFrameDigits(topPluginFrame);
        String jar = (suspectJar == null || suspectJar.isBlank()) ? resolveJarForFrame(normFrame, this.ownershipIndex) : suspectJar;
        String sigId = "hazard:" + kindSlug + ":" + normFrame;
        long nowMs = System.currentTimeMillis();
        totalHazardsRecorded.incrementAndGet();
        return upsertSignature(
                sigId,
                "HAZARD",
                kindSlug,
                normFrame,
                jar,
                Math.max(0.0, metricValue),
                nowMs,
                sampleMessage
        );
    }

    /**
     * Evaluates post-campaign idle MSPT P95 against baseline idle MSPT P95 after all bots have
     * disconnected; if post-run idle MSPT P95 increased by more than {@code +12.0 ms}, records a
     * {@code hazard:task-creep:<targetJar>} signature indicating leaked repeating scheduler tasks.
     *
     * @param suspectJar          target plugin/mod archive identifier
     * @param baselineIdleMsptP95 pre-run idle MSPT P95 in milliseconds
     * @param postRunIdleMsptP95  post-run cooldown idle MSPT P95 in milliseconds
     * @return recorded {@link FaultSignature} if permanent MSPT creep was detected
     */
    public Optional<FaultSignature> recordRepeatingTaskMsptCreep(
            String suspectJar,
            double baselineIdleMsptP95,
            double postRunIdleMsptP95
    ) {
        if (!Double.isFinite(baselineIdleMsptP95)
                || !Double.isFinite(postRunIdleMsptP95)
                || baselineIdleMsptP95 <= 0.0) {
            return Optional.empty();
        }
        double deltaMs = postRunIdleMsptP95 - baselineIdleMsptP95;
        if (deltaMs <= 12.0) {
            return Optional.empty();
        }
        String jar = (suspectJar == null || suspectJar.isBlank() || "all".equalsIgnoreCase(suspectJar))
                ? "plugin"
                : suspectJar.trim();
        String frame = jar + ".ScheduledTaskCreep";
        String msg = String.format(
                Locale.ROOT,
                "Post-campaign idle MSPT P95 increased by +%.1f ms (%.1f -> %.1f mspt); possible leaked repeating task [%s]",
                deltaMs,
                baselineIdleMsptP95,
                postRunIdleMsptP95,
                jar
        );
        return Optional.of(recordCustomHazard("task-creep", frame, jar, deltaMs, msg));
    }

    private static boolean isSyncChunkHazardFrame(String cls, String method, double tickDurationMs) {
        if ("loadChunk".equals(method) || "getChunkFutureMainThread".equals(method) || "syncLoad".equals(method)) {
            return cls.contains("World") || cls.contains("Chunk") || cls.contains("ServerLevel");
        }
        if ("getChunkAt".equals(method)) {
            return (cls.contains("World") || cls.contains("Chunk")) && tickDurationMs >= 5.0;
        }
        if ("getChunk".equals(method) && tickDurationMs >= DEFAULT_HAZARD_TICK_THRESHOLD_MS) {
            return cls.contains("ServerChunkCache") || cls.contains("ServerLevel") || cls.contains("World");
        }
        return false;
    }

    private static boolean isSyncIoHazardFrame(String cls, String method) {
        if (cls.startsWith("java.io.FileInputStream")
                || cls.startsWith("java.io.FileOutputStream")
                || cls.startsWith("java.io.RandomAccessFile")
                || cls.startsWith("java.io.UnixFileSystem")
                || cls.startsWith("java.io.WinNTFileSystem")
                || cls.startsWith("sun.nio.ch.FileDispatcher")
                || cls.startsWith("sun.nio.ch.FileChannelImpl")
                || cls.startsWith("sun.nio.fs.")
                || cls.startsWith("java.nio.file.Files")
                || cls.startsWith("java.util.zip.ZipFile")) {
            return true;
        }
        if (cls.startsWith("java.net.SocketInputStream")
                || cls.startsWith("java.net.SocketOutputStream")
                || cls.startsWith("java.net.PlainSocketImpl")
                || cls.startsWith("sun.nio.ch.SocketChannelImpl")
                || cls.startsWith("sun.nio.ch.Net")
                || cls.startsWith("java.net.HttpURLConnection")
                || cls.startsWith("sun.net.www.")) {
            return true;
        }
        return cls.startsWith("java.sql.")
                || cls.startsWith("javax.sql.")
                || cls.startsWith("org.sqlite.")
                || cls.startsWith("com.mysql.")
                || cls.startsWith("org.mariadb.")
                || cls.startsWith("org.postgresql.")
                || cls.startsWith("com.zaxxer.hikari.");
    }

    private static boolean isBlockingWaitHazardFrame(String cls, String method) {
        if (cls.startsWith("java.util.concurrent.CompletableFuture")
                && ("get".equals(method) || "join".equals(method) || "waitingGet".equals(method) || "timedGet".equals(method))) {
            return true;
        }
        if (cls.startsWith("java.util.concurrent.FutureTask")
                && ("get".equals(method) || "awaitDone".equals(method))) {
            return true;
        }
        if (cls.startsWith("java.util.concurrent.CountDownLatch") && "await".equals(method)) {
            return true;
        }
        if (cls.startsWith("java.util.concurrent.Semaphore")
                && ("acquire".equals(method) || "tryAcquire".equals(method))) {
            return true;
        }
        return "java.lang.Thread".equals(cls) && "sleep".equals(method);
    }

    private static boolean isIdleWorkerPoolPark(StackTraceElement[] stack) {
        for (int i = 0; i < Math.min(6, stack.length); i++) {
            StackTraceElement el = stack[i];
            if (el == null) {
                continue;
            }
            String cls = el.getClassName();
            String m = el.getMethodName();
            if (cls == null || m == null) {
                continue;
            }
            if ((cls.contains("ThreadPoolExecutor") && "getTask".equals(m))
                    || (cls.contains("ForkJoinPool") && ("awaitWork".equals(m) || "runWorker".equals(m)))
                    || (cls.contains("ScheduledThreadPoolExecutor") && "take".equals(m))) {
                return true;
            }
        }
        return false;
    }

    private static String simpleClass(String fqcn) {
        if (fqcn == null) {
            return "";
        }
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }

    private FaultSignature recordExceptionBlock(List<String> blockLines, String rawPluginHint, long epochMs) {
        if (blockLines == null || blockLines.isEmpty()) {
            return null;
        }
        String excType = extractRootExceptionType(blockLines);
        if (excType == null || excType.isEmpty()) {
            excType = "java.lang.RuntimeException";
        }
        List<String> frames = extractTraceFrames(blockLines);
        if (frames.isEmpty() && rawPluginHint == null) {
            return null;
        }
        String cleanedHint = cleanPluginHint(rawPluginHint);
        FrameAttribution attr = selectCulpritFrame(frames, this.ownershipIndex, cleanedHint);
        String sigId = "exc:" + excType + ":" + attr.normalizedFrame();
        String first = unprefixed(blockLines.get(0)).trim();
        totalExceptionsRecorded.incrementAndGet();
        return upsertSignature(
                sigId,
                "EXCEPTION",
                excType,
                attr.normalizedFrame(),
                attr.suspectJar(),
                0.0,
                epochMs,
                first
        );
    }

    private FaultSignature upsertSignature(
            String sigId,
            String kind,
            String excClass,
            String topPluginFrame,
            String suspectJar,
            double durationMs,
            long epochMs,
            String sampleMessage
    ) {
        if (signaturesById.size() >= MAX_SIGNATURES && !signaturesById.containsKey(sigId)) {
            return new FaultSignature(
                    sigId,
                    kind,
                    excClass,
                    topPluginFrame,
                    suspectJar,
                    1L,
                    durationMs,
                    epochMs,
                    epochMs,
                    sampleMessage
            );
        }
        return signaturesById.compute(sigId, (k, prev) -> {
            if (prev == null) {
                return new FaultSignature(
                        sigId,
                        kind,
                        excClass,
                        topPluginFrame,
                        suspectJar,
                        1L,
                        durationMs,
                        epochMs,
                        epochMs,
                        sampleMessage
                );
            }
            String resolvedJar = !"server".equals(prev.suspectJar()) ? prev.suspectJar() : suspectJar;
            return new FaultSignature(
                    sigId,
                    kind,
                    prev.exceptionClass() != null ? prev.exceptionClass() : excClass,
                    prev.topPluginFrame(),
                    resolvedJar,
                    prev.occurrences() + 1L,
                    Math.max(prev.maxDurationMs(), durationMs),
                    Math.min(prev.firstSeenEpochMs(), epochMs),
                    Math.max(prev.lastSeenEpochMs(), epochMs),
                    prev.sampleMessage().isEmpty() ? sampleMessage : prev.sampleMessage()
            );
        });
    }

    private record FrameAttribution(String normalizedFrame, String suspectJar) {}

    private static FrameAttribution selectCulpritFrame(
            List<String> frames,
            JarClassOwnershipIndex idx,
            String pluginHint
    ) {
        if (frames != null && !frames.isEmpty()) {
            // Pass 1: First frame uniquely owned by an indexed plugin JAR
            if (idx != null && idx.indexedJarCount() > 0) {
                for (String f : frames) {
                    String cls = extractClassFromMethodFrame(f);
                    if (cls != null) {
                        Optional<String> owner = idx.ownerJarForClass(cls);
                        if (owner.isPresent()) {
                            return new FrameAttribution(normalizeFrameDigits(f), owner.get());
                        }
                    }
                }
            }
            // Pass 2: First non-platform frame (checking if it is a multi-JAR shared class or unindexed plugin)
            for (String f : frames) {
                String cls = extractClassFromMethodFrame(f);
                if (cls != null && !isPlatformOrJdkClass(cls)) {
                    String jar = resolveJarForFrame(f, idx);
                    if ("server".equals(jar) && pluginHint != null && !pluginHint.isEmpty()) {
                        jar = resolvePluginHintToJar(pluginHint, idx);
                    }
                    return new FrameAttribution(normalizeFrameDigits(f), jar);
                }
            }
            // Pass 3: Fallback to top stack frame
            String top = frames.get(0);
            String jar = pluginHint != null && !pluginHint.isEmpty()
                    ? resolvePluginHintToJar(pluginHint, idx)
                    : "server";
            return new FrameAttribution(normalizeFrameDigits(top), jar);
        }
        String fallbackJar = pluginHint != null && !pluginHint.isEmpty()
                ? resolvePluginHintToJar(pluginHint, idx)
                : "server";
        return new FrameAttribution("server", fallbackJar);
    }

    private static String resolveJarForFrame(String frame, JarClassOwnershipIndex idx) {
        if (frame == null || frame.isBlank() || "server".equals(frame)) {
            return "server";
        }
        if (idx != null) {
            String cls = extractClassFromMethodFrame(frame);
            String target = cls != null ? cls : frame;
            Optional<String> unique = idx.ownerJarForClass(target);
            if (unique.isPresent()) {
                return unique.get();
            }
            Set<String> allOwners = idx.allOwnersForClass(target);
            if (allOwners.size() > 1) {
                return "shared";
            }
        }
        return "server";
    }

    private static String resolvePluginHintToJar(String pluginHint, JarClassOwnershipIndex idx) {
        if (pluginHint == null || pluginHint.isBlank()) {
            return "server";
        }
        if (idx != null) {
            for (JarClassOwnershipIndex.JarDescriptor desc : idx.descriptors().values()) {
                if (desc.pluginId().equalsIgnoreCase(pluginHint)
                        || desc.jarName().equalsIgnoreCase(pluginHint)
                        || desc.jarName().toLowerCase(Locale.ROOT).startsWith(pluginHint.toLowerCase(Locale.ROOT))) {
                    return desc.jarName();
                }
            }
        }
        return pluginHint;
    }

    /**
     * Normalizes unstable JVM/compiler-generated indices in a stack frame ({@code $$Lambda$...},
     * {@code $Lambda/0x...}, hidden-class {@code /0x...}, {@code lambda$name$<n>}, and anonymous
     * inner-class {@code $<n>} segments) while preserving digits that are part of real package,
     * class, or method names (such as {@code v1_20_R3}, {@code Log4jHandler}, or {@code Handler1}).
     *
     * @param frame raw {@code pkg.Class.method} stack frame
     * @return normalized stack frame
     */
    public static String normalizeFrameDigits(String frame) {
        if (frame == null || frame.isBlank()) {
            return "server";
        }
        String trimmed = frame.trim();
        int doubleSlash = trimmed.indexOf("//");
        if (doubleSlash >= 0 && doubleSlash + 2 < trimmed.length()) {
            trimmed = trimmed.substring(doubleSlash + 2);
        }
        int openParen = trimmed.indexOf('(');
        if (openParen > 0) {
            trimmed = trimmed.substring(0, openParen).trim();
        }
        String norm = LAMBDA_CLASS_SUFFIX.matcher(trimmed).replaceAll("\\$\\$Lambda\\$#");
        norm = HIDDEN_CLASS_SUFFIX.matcher(norm).replaceAll("/0x#");
        norm = LAMBDA_METHOD_INDEX.matcher(norm).replaceAll("$1#");
        norm = ANON_INNER_CLASS.matcher(norm).replaceAll("\\$#");
        norm = GENERATED_ACCESSOR.matcher(norm).replaceAll("$1#");
        return norm;
    }

    private static String extractClassFromMethodFrame(String frame) {
        if (frame == null || frame.isBlank()) {
            return null;
        }
        String norm = JarClassOwnershipIndex.normalizeClassName("at " + frame + "(UnknownSource)");
        if (norm != null) {
            return norm;
        }
        int lastDot = frame.lastIndexOf('.');
        return lastDot > 0 ? frame.substring(0, lastDot) : frame;
    }

    public static boolean isPlatformOrJdkClass(String className) {
        if (className == null || className.isBlank()) {
            return true;
        }
        String norm = className.trim();
        for (String pfx : PLATFORM_PACKAGE_PREFIXES) {
            if (norm.startsWith(pfx)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsNonMainToken(String lowerThreadName) {
        for (String tok : NON_MAIN_THREAD_TOKENS) {
            if (lowerThreadName.contains(tok)) {
                return true;
            }
        }
        return false;
    }

    private static void collectStackFrames(StackTraceElement[] elements, List<String> target) {
        if (elements == null) {
            return;
        }
        for (StackTraceElement el : elements) {
            if (el == null) {
                continue;
            }
            String cls = el.getClassName();
            String method = el.getMethodName();
            if (cls != null && !cls.isEmpty() && method != null && !method.isEmpty()) {
                if (cls.startsWith("net.leaf.fuzz.")
                        || cls.startsWith("net.leaf.sleuth.paper.fuzz.")) {
                    break;
                }
                if (cls.startsWith("io.github.dailystruggle.rtp.bukkitplatform.server.BukkitPluginServerAccessor")
                        && (method.contains("runSync") || method.contains("schedule") || method.contains("execute"))) {
                    continue;
                }
                target.add(cls + "." + method);
            }
        }
    }

    private static Throwable unwrapRootCause(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur.getCause() != null && cur.getCause() != cur && depth < 16) {
            cur = cur.getCause();
            depth++;
        }
        return cur;
    }

    private static String unprefixed(String line) {
        if (line == null) {
            return "";
        }
        return LOG_PREFIX.matcher(line).replaceFirst("");
    }

    private static boolean isTraceContinuation(String line) {
        String s = unprefixed(line);
        String t = s.stripLeading();
        return FRAME_LINE.matcher(s).find()
                || t.startsWith("Caused by:")
                || t.startsWith("Suppressed:")
                || t.startsWith("... ");
    }

    private static boolean isExceptionHeader(String line) {
        String t = unprefixed(line).stripLeading();
        return EXC_TYPE.matcher(t).find()
                && !t.startsWith("at ")
                && !t.startsWith("Caused by:")
                && !t.startsWith("Suppressed:");
    }

    private static List<String> extractTraceFrames(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String ln : lines) {
            Matcher m = FRAME_LINE.matcher(unprefixed(ln));
            if (m.find()) {
                out.add(m.group(1).trim());
            }
        }
        return out;
    }

    private static String extractRootExceptionType(List<String> lines) {
        String typ = null;
        for (String ln : lines) {
            String t = unprefixed(ln).stripLeading();
            if (typ != null && !t.startsWith("Caused by:")) {
                continue;
            }
            Matcher m = EXC_TYPE.matcher(t);
            if (m.find()) {
                typ = m.group(1);
            }
        }
        return typ;
    }

    private static String extractEventPluginHint(String context) {
        if (context == null || context.isBlank()) {
            return null;
        }
        Matcher m = EVENT_ERROR.matcher(context);
        if (m.find()) {
            return cleanPluginHint(m.group(2));
        }
        return null;
    }

    private static String cleanPluginHint(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        int space = s.indexOf(' ');
        if (space > 0) {
            s = s.substring(0, space);
        }
        while (!s.isEmpty() && "v0123456789.-_".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? raw.trim() : s;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int nl = text.indexOf('\n');
        int cr = text.indexOf('\r');
        int cut = -1;
        if (nl >= 0 && cr >= 0) {
            cut = Math.min(nl, cr);
        } else if (nl >= 0) {
            cut = nl;
        } else if (cr >= 0) {
            cut = cr;
        }
        String line = cut >= 0 ? text.substring(0, cut) : text;
        return line.trim();
    }
}
