package io.github.dailystruggle.rtp.common.bit;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.bit.CbitCounter;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;

/**
 * Continuous Built-In Test (CBIT) counters and component ({@code core-fallback-cbit}) for degraded
 * runtime fallback catches across {@code rtp-core} (ADR-121, REQ-RTP-S-004).
 */
public final class CoreFallbackCbitCounters {
    private static final long COMPONENT_TIMEOUT_MILLIS = 5_000L;

    public static final BitCode LIFECYCLE_CLEANUP_DEGRADED = RtpBitCodes.RTP.register(
            1811,
            "RTP_CBIT_LIFECYCLE_CLEANUP_DEGRADED",
            Level.WARNING,
            BitTier.CBIT,
            "core",
            "",
            "Inspect server logs for the first-trip stack trace during RTP startup, reload, migration, or shutdown.");

    public static final BitCode ACTION_DISPATCH_DEGRADED = RtpBitCodes.RTP.register(
            1812,
            "RTP_CBIT_ACTION_DISPATCH_DEGRADED",
            Level.WARNING,
            BitTier.CBIT,
            "action",
            "",
            "Check action definition commands, reservation lifecycle, and hook bindings in server logs.");

    public static final BitCode MENU_REDEEM_DEGRADED = RtpBitCodes.RTP.register(
            1813,
            "RTP_CBIT_MENU_REDEEM_DEGRADED",
            Level.WARNING,
            BitTier.CBIT,
            "menu",
            "",
            "Verify menu command tree synchronization and active configuration parsers.");

    public static final BitCode EDITOR_SESSION_DEGRADED = RtpBitCodes.RTP.register(
            1814,
            "RTP_CBIT_EDITOR_SESSION_DEGRADED",
            Level.WARNING,
            BitTier.CBIT,
            "editor",
            "",
            "Inspect web editor session telemetry, schema introspection, or cached config refresh logs.");

    public static final BitCode SETUP_WIZARD_DEGRADED = RtpBitCodes.RTP.register(
            1815,
            "RTP_CBIT_SETUP_WIZARD_DEGRADED",
            Level.WARNING,
            BitTier.CBIT,
            "setup",
            "",
            "Verify setup wizard world enumeration, diff preview, and configuration reload permissions.");

    public static final BitCode PREGEN_EXTRACTOR_DEGRADED = RtpBitCodes.RTP.register(
            1816,
            "RTP_CBIT_PREGEN_EXTRACTOR_DEGRADED",
            Level.WARNING,
            BitTier.CBIT,
            "fill",
            "",
            "Inspect region file format readers and world folder resolution for pregenerated chunk extraction.");

    public static final BitCode CONFIG_LOOKUP_DEGRADED = RtpBitCodes.RTP.register(
            1817,
            "RTP_CBIT_CONFIG_LOOKUP_DEGRADED",
            Level.WARNING,
            BitTier.CBIT,
            "config",
            "",
            "Check active configuration parsers and platform registry hooks for unexpected runtime lookup errors.");

    public static final BitCode TEST_PROBE_CLEANUP_DEGRADED = RtpBitCodes.RTP.register(
            1818,
            "RTP_CBIT_TEST_PROBE_CLEANUP_DEGRADED",
            Level.WARNING,
            BitTier.CBIT,
            "test",
            "",
            "Inspect IBIT test probe teardown and resource release paths (REQ-RTP-S-002, REQ-RTP-S-004).");

    public static final BitComponent CORE_FALLBACK_CBIT = new BitComponent(
            "core-fallback-cbit",
            "core",
            BitTier.CBIT,
            BitSideEffect.SAFE,
            COMPONENT_TIMEOUT_MILLIS,
            CoreFallbackCbitCounters::report);

    private static final CbitCounter FALLBACK_OBSERVATIONS = new CbitCounter();
    private static final CbitCounter LIFECYCLE_CLEANUP = new CbitCounter();
    private static final CbitCounter ACTION_DISPATCH = new CbitCounter();
    private static final CbitCounter MENU_REDEEM = new CbitCounter();
    private static final CbitCounter EDITOR_SESSION = new CbitCounter();
    private static final CbitCounter SETUP_WIZARD = new CbitCounter();
    private static final CbitCounter PREGEN_EXTRACTOR = new CbitCounter();
    private static final CbitCounter CONFIG_LOOKUP = new CbitCounter();
    private static final CbitCounter TEST_PROBE_CLEANUP = new CbitCounter();

    private static volatile String lastLifecycleCleanupDetail = "";
    private static volatile String lastActionDispatchDetail = "";
    private static volatile String lastMenuRedeemDetail = "";
    private static volatile String lastEditorSessionDetail = "";
    private static volatile String lastSetupWizardDetail = "";
    private static volatile String lastPregenExtractorDetail = "";
    private static volatile String lastConfigLookupDetail = "";
    private static volatile String lastTestProbeCleanupDetail = "";

    private CoreFallbackCbitCounters() {}

    /** Records an observed core fallback check sample. */
    public static void observe() {
        FALLBACK_OBSERVATIONS.observe();
    }

    /** Records multiple observed core fallback check samples. */
    public static void observe(long samples) {
        FALLBACK_OBSERVATIONS.observe(samples);
    }

    public static long observations() {
        return FALLBACK_OBSERVATIONS.observations()
                + LIFECYCLE_CLEANUP.observations()
                + ACTION_DISPATCH.observations()
                + MENU_REDEEM.observations()
                + EDITOR_SESSION.observations()
                + SETUP_WIZARD.observations()
                + PREGEN_EXTRACTOR.observations()
                + CONFIG_LOOKUP.observations()
                + TEST_PROBE_CLEANUP.observations();
    }

    public static boolean isObserved() {
        return observations() > 0L;
    }

    public static void recordLifecycleCleanupDegraded(String subject, Throwable error) {
        String formatted = formatDetail(subject, error);
        lastLifecycleCleanupDetail = formatted;
        LIFECYCLE_CLEANUP.increment();
        logFirstTrip(LIFECYCLE_CLEANUP, LIFECYCLE_CLEANUP_DEGRADED, formatted, error);
    }

    public static void recordLifecycleCleanupDegraded(String subject, String detail) {
        String formatted = formatDetail(subject, detail);
        lastLifecycleCleanupDetail = formatted;
        LIFECYCLE_CLEANUP.increment();
        logFirstTrip(LIFECYCLE_CLEANUP, LIFECYCLE_CLEANUP_DEGRADED, formatted, null);
    }

    public static void recordActionDispatchDegraded(String subject, Throwable error) {
        String formatted = formatDetail(subject, error);
        lastActionDispatchDetail = formatted;
        ACTION_DISPATCH.increment();
        logFirstTrip(ACTION_DISPATCH, ACTION_DISPATCH_DEGRADED, formatted, error);
    }

    public static void recordActionDispatchDegraded(String subject, String detail) {
        String formatted = formatDetail(subject, detail);
        lastActionDispatchDetail = formatted;
        ACTION_DISPATCH.increment();
        logFirstTrip(ACTION_DISPATCH, ACTION_DISPATCH_DEGRADED, formatted, null);
    }

    public static void recordMenuRedeemDegraded(String subject, Throwable error) {
        String formatted = formatDetail(subject, error);
        lastMenuRedeemDetail = formatted;
        MENU_REDEEM.increment();
        logFirstTrip(MENU_REDEEM, MENU_REDEEM_DEGRADED, formatted, error);
    }

    public static void recordMenuRedeemDegraded(String subject, String detail) {
        String formatted = formatDetail(subject, detail);
        lastMenuRedeemDetail = formatted;
        MENU_REDEEM.increment();
        logFirstTrip(MENU_REDEEM, MENU_REDEEM_DEGRADED, formatted, null);
    }

    public static void recordEditorSessionDegraded(String subject, Throwable error) {
        String formatted = formatDetail(subject, error);
        lastEditorSessionDetail = formatted;
        EDITOR_SESSION.increment();
        logFirstTrip(EDITOR_SESSION, EDITOR_SESSION_DEGRADED, formatted, error);
    }

    public static void recordEditorSessionDegraded(String subject, String detail) {
        String formatted = formatDetail(subject, detail);
        lastEditorSessionDetail = formatted;
        EDITOR_SESSION.increment();
        logFirstTrip(EDITOR_SESSION, EDITOR_SESSION_DEGRADED, formatted, null);
    }

    public static void recordSetupWizardDegraded(String subject, Throwable error) {
        String formatted = formatDetail(subject, error);
        lastSetupWizardDetail = formatted;
        SETUP_WIZARD.increment();
        logFirstTrip(SETUP_WIZARD, SETUP_WIZARD_DEGRADED, formatted, error);
    }

    public static void recordSetupWizardDegraded(String subject, String detail) {
        String formatted = formatDetail(subject, detail);
        lastSetupWizardDetail = formatted;
        SETUP_WIZARD.increment();
        logFirstTrip(SETUP_WIZARD, SETUP_WIZARD_DEGRADED, formatted, null);
    }

    public static void recordPregenExtractorDegraded(String subject, Throwable error) {
        String formatted = formatDetail(subject, error);
        lastPregenExtractorDetail = formatted;
        PREGEN_EXTRACTOR.increment();
        logFirstTrip(PREGEN_EXTRACTOR, PREGEN_EXTRACTOR_DEGRADED, formatted, error);
    }

    public static void recordPregenExtractorDegraded(String subject, String detail) {
        String formatted = formatDetail(subject, detail);
        lastPregenExtractorDetail = formatted;
        PREGEN_EXTRACTOR.increment();
        logFirstTrip(PREGEN_EXTRACTOR, PREGEN_EXTRACTOR_DEGRADED, formatted, null);
    }

    public static void recordConfigLookupDegraded(String subject, Throwable error) {
        String formatted = formatDetail(subject, error);
        lastConfigLookupDetail = formatted;
        CONFIG_LOOKUP.increment();
        logFirstTrip(CONFIG_LOOKUP, CONFIG_LOOKUP_DEGRADED, formatted, error);
    }

    public static void recordConfigLookupDegraded(String subject, String detail) {
        String formatted = formatDetail(subject, detail);
        lastConfigLookupDetail = formatted;
        CONFIG_LOOKUP.increment();
        logFirstTrip(CONFIG_LOOKUP, CONFIG_LOOKUP_DEGRADED, formatted, null);
    }

    public static void recordTestProbeCleanupDegraded(String subject, Throwable error) {
        String formatted = formatDetail(subject, error);
        lastTestProbeCleanupDetail = formatted;
        TEST_PROBE_CLEANUP.increment();
        logFirstTrip(TEST_PROBE_CLEANUP, TEST_PROBE_CLEANUP_DEGRADED, formatted, error);
    }

    public static void recordTestProbeCleanupDegraded(String subject, String detail) {
        String formatted = formatDetail(subject, detail);
        lastTestProbeCleanupDetail = formatted;
        TEST_PROBE_CLEANUP.increment();
        logFirstTrip(TEST_PROBE_CLEANUP, TEST_PROBE_CLEANUP_DEGRADED, formatted, null);
    }

    public static long lifecycleCleanupCount() {
        return LIFECYCLE_CLEANUP.count();
    }

    public static long actionDispatchCount() {
        return ACTION_DISPATCH.count();
    }

    public static long menuRedeemCount() {
        return MENU_REDEEM.count();
    }

    public static long editorSessionCount() {
        return EDITOR_SESSION.count();
    }

    public static long setupWizardCount() {
        return SETUP_WIZARD.count();
    }

    public static long pregenExtractorCount() {
        return PREGEN_EXTRACTOR.count();
    }

    public static long configLookupCount() {
        return CONFIG_LOOKUP.count();
    }

    public static long testProbeCleanupCount() {
        return TEST_PROBE_CLEANUP.count();
    }

    public static String lastLifecycleCleanupDetail() {
        return lastLifecycleCleanupDetail;
    }

    public static String lastActionDispatchDetail() {
        return lastActionDispatchDetail;
    }

    public static String lastMenuRedeemDetail() {
        return lastMenuRedeemDetail;
    }

    public static String lastEditorSessionDetail() {
        return lastEditorSessionDetail;
    }

    public static String lastSetupWizardDetail() {
        return lastSetupWizardDetail;
    }

    public static String lastPregenExtractorDetail() {
        return lastPregenExtractorDetail;
    }

    public static String lastConfigLookupDetail() {
        return lastConfigLookupDetail;
    }

    public static String lastTestProbeCleanupDetail() {
        return lastTestProbeCleanupDetail;
    }

    /** Builds the live CBIT report from all core fallback counters. */
    public static BitReport report() {
        FALLBACK_OBSERVATIONS.observe();
        List<BitFinding> findings = new ArrayList<>();

        long lifecycleCount = LIFECYCLE_CLEANUP.count();
        if (lifecycleCount > 0) {
            addFinding(
                    findings,
                    LIFECYCLE_CLEANUP_DEGRADED,
                    "core.fallback.lifecycle",
                    "Core lifecycle or task cleanup degraded " + lifecycleCount
                            + " time(s); last: " + lastLifecycleCleanupDetail + ".",
                    "clean lifecycle task execution and shutdown");
        }

        long actionCount = ACTION_DISPATCH.count();
        if (actionCount > 0) {
            addFinding(
                    findings,
                    ACTION_DISPATCH_DEGRADED,
                    "core.fallback.action",
                    "Action dispatch or reservation cleanup degraded " + actionCount
                            + " time(s); last: " + lastActionDispatchDetail + ".",
                    "action commands and reservation releases complete without throwing");
        }

        long menuCount = MENU_REDEEM.count();
        if (menuCount > 0) {
            addFinding(
                    findings,
                    MENU_REDEEM_DEGRADED,
                    "core.fallback.menu",
                    "Menu redeem or option lookup degraded " + menuCount
                            + " time(s); last: " + lastMenuRedeemDetail + ".",
                    "menu command and config queries resolve without throwing");
        }

        long editorCount = EDITOR_SESSION.count();
        if (editorCount > 0) {
            addFinding(
                    findings,
                    EDITOR_SESSION_DEGRADED,
                    "core.fallback.editor",
                    "Editor session serialization or cache refresh degraded " + editorCount
                            + " time(s); last: " + lastEditorSessionDetail + ".",
                    "editor session payload and cache updates succeed cleanly");
        }

        long setupCount = SETUP_WIZARD.count();
        if (setupCount > 0) {
            addFinding(
                    findings,
                    SETUP_WIZARD_DEGRADED,
                    "core.fallback.setup",
                    "Setup wizard probe or apply fallback tripped " + setupCount
                            + " time(s); last: " + lastSetupWizardDetail + ".",
                    "setup wizard world lookup, preview, and reload complete cleanly");
        }

        long pregenCount = PREGEN_EXTRACTOR.count();
        if (pregenCount > 0) {
            addFinding(
                    findings,
                    PREGEN_EXTRACTOR_DEGRADED,
                    "core.fallback.pregen",
                    "Pregenerated biome/chunk extractor fallback tripped " + pregenCount
                            + " time(s); last: " + lastPregenExtractorDetail + ".",
                    "region folder and column probes read without throwing");
        }

        long configCount = CONFIG_LOOKUP.count();
        if (configCount > 0) {
            addFinding(
                    findings,
                    CONFIG_LOOKUP_DEGRADED,
                    "core.fallback.config",
                    "Runtime config or target lookup fallback tripped " + configCount
                            + " time(s); last: " + lastConfigLookupDetail + ".",
                    "config parser and target status queries succeed without throwing");
        }

        long testCleanupCount = TEST_PROBE_CLEANUP.count();
        if (testCleanupCount > 0) {
            addFinding(
                    findings,
                    TEST_PROBE_CLEANUP_DEGRADED,
                    "core.fallback.testCleanup",
                    "Test probe cleanup or teardown degraded " + testCleanupCount
                            + " time(s); last: " + lastTestProbeCleanupDetail + ".",
                    "test probe finally/cleanup blocks release all resources cleanly");
        }

        return BitReport.of(findings);
    }

    /** Clears all core fallback CBIT counters and details for test isolation. */
    public static void reset() {
        FALLBACK_OBSERVATIONS.reset();
        LIFECYCLE_CLEANUP.reset();
        ACTION_DISPATCH.reset();
        MENU_REDEEM.reset();
        EDITOR_SESSION.reset();
        SETUP_WIZARD.reset();
        PREGEN_EXTRACTOR.reset();
        CONFIG_LOOKUP.reset();
        TEST_PROBE_CLEANUP.reset();
        lastLifecycleCleanupDetail = "";
        lastActionDispatchDetail = "";
        lastMenuRedeemDetail = "";
        lastEditorSessionDetail = "";
        lastSetupWizardDetail = "";
        lastPregenExtractorDetail = "";
        lastConfigLookupDetail = "";
        lastTestProbeCleanupDetail = "";
    }

    private static void logFirstTrip(
            CbitCounter counter, BitCode code, String formattedDetail, Throwable error) {
        if (counter.count() == 1L) {
            String msg = "[RTP cbit] " + code.key() + " first trip: " + formattedDetail;
            if (error != null) {
                RTPRuntime.log(java.util.logging.Level.WARNING, msg, error);
            } else {
                RTPRuntime.log(java.util.logging.Level.WARNING, msg);
            }
        }
    }

    private static void addFinding(
            List<BitFinding> findings,
            BitCode code,
            String path,
            String message,
            String expected) {
        BitFinding finding = BitFinding.of(
                code,
                code.defaultSeverity(),
                "core-fallback-cbit",
                path,
                message,
                expected,
                code.remediation());
        BitSeverityPolicy.effectiveFinding(finding).ifPresent(findings::add);
    }

    private static String formatDetail(String subject, Throwable error) {
        String s = subject == null || subject.isBlank() ? "<unknown>" : subject.trim();
        if (error == null) {
            return s + " (fallback)";
        }
        return s + " (" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")";
    }

    private static String formatDetail(String subject, String detail) {
        String s = subject == null || subject.isBlank() ? "<unknown>" : subject.trim();
        String d = detail == null || detail.isBlank() ? "fallback" : detail.trim();
        return s + " (" + d + ")";
    }
}
