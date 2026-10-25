package io.github.dailystruggle.rtp.common.bit;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitCodes;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.bit.CbitCounter;
import io.github.dailystruggle.rtp.anvil.AnvilPrefilterMetrics;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Continuous Built-In Test (CBIT) counters and component ({@code pipeline-cbit}) for the
 * selection pipeline, Anvil prefilter, ADR-015 stale-chunk guard, and chunk-ticket high-water
 * marks (ADR-015, ADR-016, ADR-077, ADR-121).
 */
public final class PipelineCbitCounters {
    private static final long COMPONENT_TIMEOUT_MILLIS = 5_000L;

    public static final BitCode PREFILTER_COMPRESSION_UNSUPPORTED = RtpBitCodes.RTP.register(
            1801,
            "RTP_CBIT_PREFILTER_COMPRESSION_UNSUPPORTED",
            Level.WARNING,
            BitTier.CBIT,
            "pipeline",
            "",
            "Check region-file-compression in server configuration or update anvil-api for the new chunk compression mode.");

    public static final BitCode PREFILTER_SECTION_UNPARSEABLE = RtpBitCodes.RTP.register(
            1802,
            "RTP_CBIT_PREFILTER_SECTION_UNPARSEABLE",
            Level.WARNING,
            BitTier.CBIT,
            "pipeline",
            "",
            "Inspect the reported region file and chunk coordinate or update anvil-api for the new chunk NBT section layout.");

    public static final BitCode STALE_CHUNK_GUARD_TRIPPED = RtpBitCodes.RTP.register(
            1803,
            "RTP_CBIT_STALE_CHUNK_GUARD_TRIPPED",
            Level.WARNING,
            BitTier.CBIT,
            "pipeline",
            "",
            "Check region-thread backlog or tune staleChunkRetryLimit in safety.yml (ADR-015).");

    public static final BitCode CHUNK_TICKET_HIGH_WATER = RtpBitCodes.RTP.register(
            1804,
            "RTP_CBIT_CHUNK_TICKET_HIGH_WATER",
            Level.WARNING,
            BitTier.CBIT,
            "pipeline",
            "",
            "Inspect active chunk ticket counts and activeChunkCap / memory tracker diagnostics (S-002).");

    public static final BitComponent PIPELINE_CBIT = new BitComponent(
            "pipeline-cbit",
            "pipeline",
            BitTier.CBIT,
            BitSideEffect.SAFE,
            COMPONENT_TIMEOUT_MILLIS,
            PipelineCbitCounters::report);

    private static final CbitCounter CANDIDATE_EVALUATIONS = new CbitCounter();
    private static final CbitCounter STALE_CHUNK_TRIPS = new CbitCounter();
    private static final CbitCounter TICKET_HIGH_WATER_EVENTS = new CbitCounter();

    private static volatile String lastStaleChunkWorld = "";
    private static volatile String lastStaleChunkCoord = "";

    private static volatile String lastTicketHighWaterWorld = "";
    private static volatile long lastTicketHighWaterActive = 0L;
    private static volatile long lastTicketHighWaterCeiling = 0L;

    private PipelineCbitCounters() {}

    /** Records an evaluated candidate location sample on the selection pipeline. */
    public static void recordCandidateEvaluated() {
        CANDIDATE_EVALUATIONS.observe();
    }

    /** Records multiple evaluated candidate location samples on the selection pipeline. */
    public static void recordCandidateEvaluated(long samples) {
        CANDIDATE_EVALUATIONS.observe(samples);
    }

    public static long candidateEvaluations() {
        return CANDIDATE_EVALUATIONS.observations();
    }

    public static long observations() {
        long events = STALE_CHUNK_TRIPS.observations()
                + TICKET_HIGH_WATER_EVENTS.observations()
                + AnvilPrefilterMetrics.unsupportedCompressions.get()
                + AnvilPrefilterMetrics.unparseableSections.get();
        return Math.max(CANDIDATE_EVALUATIONS.observations(), CANDIDATE_EVALUATIONS.observations() + events);
    }

    public static boolean isObserved() {
        return observations() > 0L;
    }

    /**
     * Records an unrecognized compression scheme encountered by the Anvil prefilter.
     */
    public static void recordUnsupportedCompression(Path regionPath, int cx, int cz, String detail) {
        CANDIDATE_EVALUATIONS.observe();
        AnvilPrefilterMetrics.recordUnsupportedCompression(regionPath, cx, cz, detail);
    }

    /**
     * Records an unparseable chunk section or corrupt entry encountered by the Anvil prefilter.
     */
    public static void recordUnparseableSection(Path regionPath, int cx, int cz, String reason) {
        CANDIDATE_EVALUATIONS.observe();
        AnvilPrefilterMetrics.recordUnparseableSection(regionPath, cx, cz, reason);
    }

    /**
     * Records an ADR-015 stale-chunk guard trip on a candidate evaluation path.
     */
    public static void recordStaleChunkGuardTripped(String worldName, int cx, int cz) {
        STALE_CHUNK_TRIPS.increment();
        lastStaleChunkWorld = worldName == null || worldName.isBlank() ? "<unknown>" : worldName;
        lastStaleChunkCoord = "(" + cx + "," + cz + ")";
    }

    /**
     * Records a chunk-ticket high-water mark or positive ticket discrepancy event.
     */
    public static void recordChunkTicketHighWater(String worldName, long activeTickets, long ceiling) {
        TICKET_HIGH_WATER_EVENTS.increment();
        lastTicketHighWaterWorld = worldName == null || worldName.isBlank() ? "global" : worldName;
        lastTicketHighWaterActive = activeTickets;
        lastTicketHighWaterCeiling = ceiling;
    }

    /**
     * Builds the live CBIT report from the prefilter and pipeline counters.
     */
    public static BitReport report() {
        List<BitFinding> findings = new ArrayList<>();

        long unsupportedCompressions = AnvilPrefilterMetrics.unsupportedCompressions.get();
        if (unsupportedCompressions > 0) {
            String region = AnvilPrefilterMetrics.lastUnsupportedCompressionRegion();
            String chunk = AnvilPrefilterMetrics.lastUnsupportedCompressionChunk();
            String detail = AnvilPrefilterMetrics.lastUnsupportedCompressionDetail();
            addFinding(
                    findings,
                    PREFILTER_COMPRESSION_UNSUPPORTED,
                    region,
                    "pipeline.prefilter.compression." + chunk,
                    unsupportedCompressions + " prefilter compression bailout(s); last at region="
                            + region + " chunk=" + chunk
                            + (detail.isEmpty() ? "." : " (" + detail + ")."),
                    "supported chunk compression mode (1=GZip, 2=Zlib, 3=Raw, 4=LZ4, 5=Zstd)");
        }

        long unparseableSections = AnvilPrefilterMetrics.unparseableSections.get();
        if (unparseableSections > 0) {
            String region = AnvilPrefilterMetrics.lastUnparseableSectionRegion();
            String chunk = AnvilPrefilterMetrics.lastUnparseableSectionChunk();
            String reason = AnvilPrefilterMetrics.lastUnparseableSectionReason();
            addFinding(
                    findings,
                    PREFILTER_SECTION_UNPARSEABLE,
                    region,
                    "pipeline.prefilter.section." + chunk,
                    unparseableSections + " unparseable chunk section(s) in prefilter; last at region="
                            + region + " chunk=" + chunk
                            + (reason.isEmpty() ? "." : " (" + reason + ")."),
                    "parseable chunk NBT sections and heightmap");
        }

        long staleTrips = STALE_CHUNK_TRIPS.count();
        if (staleTrips > 0) {
            addFinding(
                    findings,
                    STALE_CHUNK_GUARD_TRIPPED,
                    lastStaleChunkWorld,
                    "pipeline.staleChunkGuard." + lastStaleChunkCoord,
                    "ADR-015 stale-chunk guard tripped " + staleTrips + " time(s); last at world="
                            + lastStaleChunkWorld + " chunk=" + lastStaleChunkCoord + ".",
                    "chunk remains loaded across region-thread evaluation");
        }

        long highWaterEvents = TICKET_HIGH_WATER_EVENTS.count();
        if (highWaterEvents > 0) {
            addFinding(
                    findings,
                    CHUNK_TICKET_HIGH_WATER,
                    lastTicketHighWaterWorld,
                    "pipeline.chunkTickets.highWater",
                    "Chunk-ticket high-water mark reached " + highWaterEvents
                            + " time(s); last active=" + lastTicketHighWaterActive
                            + " expectedCeiling=" + lastTicketHighWaterCeiling
                            + " in " + lastTicketHighWaterWorld + ".",
                    "active chunk tickets within expected cap");
        }

        if (findings.isEmpty() && !isObserved()) {
            addFinding(
                    findings,
                    BitCodes.CBIT_UNOBSERVED,
                    "pipeline-cbit",
                    "pipeline.observations",
                    "No pipeline candidate evaluations or continuous events have been observed yet.",
                    "at least 1 observed candidate evaluation");
        }

        return BitReport.of(findings);
    }

    /** Clears all pipeline and prefilter CBIT counters for test isolation. */
    public static void reset() {
        AnvilPrefilterMetrics.resetBailouts();
        CANDIDATE_EVALUATIONS.reset();
        STALE_CHUNK_TRIPS.reset();
        TICKET_HIGH_WATER_EVENTS.reset();
        lastStaleChunkWorld = "";
        lastStaleChunkCoord = "";
        lastTicketHighWaterWorld = "";
        lastTicketHighWaterActive = 0L;
        lastTicketHighWaterCeiling = 0L;
    }

    private static void addFinding(
            List<BitFinding> findings,
            BitCode code,
            String source,
            String path,
            String message,
            String expected) {
        BitFinding finding = BitFinding.of(
                code,
                code.defaultSeverity(),
                source == null || source.isBlank() ? "pipeline-cbit" : source,
                path,
                message,
                expected,
                code.remediation());
        BitSeverityPolicy.effectiveFinding(finding).ifPresent(findings::add);
    }
}
