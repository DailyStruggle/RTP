package io.github.dailystruggle.rtp.common.bit;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.bit.CbitCounter;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;

/**
 * Platform and reflective-hook fallback PBIT component ({@code platform-hooks}) under the
 * {@code TEST} namespace (ADR-026, ADR-113, ADR-121).
 *
 * <p>Surfaces structured findings when optional or reflective platform hooks
 * ({@code AnvilInputOpenerProvider}, {@code MenuRenderer} / Adventure book renderer,
 * {@code WorldBorder} packet/view bridge, or {@code PaletteNormalizer} / registry mapping)
 * degrade to fallbacks on a server build.
 */
public final class PlatformHookBitComponent {
    private static final long COMPONENT_TIMEOUT_MILLIS = 5_000L;

    public static final BitCode ANVIL_INPUT_DEGRADED = RtpBitCodes.TEST.register(
            4021,
            "TEST_PLATFORM_ANVIL_INPUT_DEGRADED",
            Level.WARNING,
            BitTier.PBIT,
            "platform",
            "",
            "Check AnvilInputOpenerProvider linkage for this server version; chat/command fallback is active.");

    public static final BitCode MENU_RENDERER_DEGRADED = RtpBitCodes.TEST.register(
            4022,
            "TEST_PLATFORM_MENU_RENDERER_DEGRADED",
            Level.WARNING,
            BitTier.PBIT,
            "platform",
            "",
            "Check MenuRendererProvider / Adventure book support on this server; fallback rendering is active.");

    public static final BitCode WORLD_BORDER_DEGRADED = RtpBitCodes.TEST.register(
            4023,
            "TEST_PLATFORM_WORLD_BORDER_DEGRADED",
            Level.WARNING,
            BitTier.PBIT,
            "platform",
            "",
            "Verify WorldBorder packet or native border API compatibility for this server version.");

    public static final BitCode PALETTE_RECONCILER_DEGRADED = RtpBitCodes.TEST.register(
            4024,
            "TEST_PLATFORM_PALETTE_RECONCILER_DEGRADED",
            Level.WARNING,
            BitTier.PBIT,
            "platform",
            "",
            "Verify platform Material/Biome registry lookup compatibility; string normalization fallback is active.");

    public static final BitComponent PLATFORM_HOOKS = new BitComponent(
            "platform-hooks",
            "platform",
            BitTier.PBIT,
            BitSideEffect.SAFE,
            COMPONENT_TIMEOUT_MILLIS,
            PlatformHookBitComponent::report);

    private static final CbitCounter ANVIL_INPUT_FALLBACKS = new CbitCounter();
    private static final CbitCounter MENU_RENDERER_FALLBACKS = new CbitCounter();
    private static final CbitCounter WORLD_BORDER_FALLBACKS = new CbitCounter();
    private static final CbitCounter PALETTE_RECONCILER_FALLBACKS = new CbitCounter();

    private static volatile String lastAnvilInputDetail = "";
    private static volatile String lastMenuRendererDetail = "";
    private static volatile String lastWorldBorderDetail = "";
    private static volatile String lastPaletteReconcilerDetail = "";

    private PlatformHookBitComponent() {}

    public static void recordAnvilInputDegraded(String providerName, String detail) {
        ANVIL_INPUT_FALLBACKS.increment();
        lastAnvilInputDetail = formatDetail(providerName, detail);
    }

    public static void recordMenuRendererDegraded(String rendererId, String detail) {
        MENU_RENDERER_FALLBACKS.increment();
        lastMenuRendererDetail = formatDetail(rendererId, detail);
    }

    public static void recordWorldBorderDegraded(String surface, String detail) {
        WORLD_BORDER_FALLBACKS.increment();
        lastWorldBorderDetail = formatDetail(surface, detail);
    }

    public static void recordPaletteReconcilerDegraded(String identifier, String detail) {
        PALETTE_RECONCILER_FALLBACKS.increment();
        lastPaletteReconcilerDetail = formatDetail(identifier, detail);
    }

    public static BitReport report() {
        List<BitFinding> findings = new ArrayList<>();
        if (ANVIL_INPUT_FALLBACKS.count() > 0) {
            addFinding(
                    findings,
                    ANVIL_INPUT_DEGRADED,
                    "platform.hooks.anvilInputOpener",
                    "AnvilInputOpenerProvider degraded to fallback " + ANVIL_INPUT_FALLBACKS.count()
                            + " time(s); last: " + lastAnvilInputDetail + ".",
                    "AnvilInputOpenerProvider instantiates cleanly");
        }
        if (MENU_RENDERER_FALLBACKS.count() > 0) {
            addFinding(
                    findings,
                    MENU_RENDERER_DEGRADED,
                    "platform.hooks.menuRenderer",
                    "MenuRenderer / Adventure book dispatch degraded to fallback "
                            + MENU_RENDERER_FALLBACKS.count() + " time(s); last: "
                            + lastMenuRendererDetail + ".",
                    "Configured MenuRendererProvider resolves and instantiates cleanly");
        }
        if (WORLD_BORDER_FALLBACKS.count() > 0) {
            addFinding(
                    findings,
                    WORLD_BORDER_DEGRADED,
                    "platform.hooks.worldBorder",
                    "WorldBorder bridge degraded to fallback " + WORLD_BORDER_FALLBACKS.count()
                            + " time(s); last: " + lastWorldBorderDetail + ".",
                    "WorldBorder packet/view bridge links cleanly");
        }
        if (PALETTE_RECONCILER_FALLBACKS.count() > 0) {
            addFinding(
                    findings,
                    PALETTE_RECONCILER_DEGRADED,
                    "platform.hooks.paletteReconciler",
                    "PaletteNormalizer / registry reconciliation degraded to fallback "
                            + PALETTE_RECONCILER_FALLBACKS.count() + " time(s); last: "
                            + lastPaletteReconcilerDetail + ".",
                    "Platform Material/Biome registry lookup succeeds without throwing");
        }
        return BitReport.of(findings);
    }

    public static void reset() {
        ANVIL_INPUT_FALLBACKS.reset();
        MENU_RENDERER_FALLBACKS.reset();
        WORLD_BORDER_FALLBACKS.reset();
        PALETTE_RECONCILER_FALLBACKS.reset();
        lastAnvilInputDetail = "";
        lastMenuRendererDetail = "";
        lastWorldBorderDetail = "";
        lastPaletteReconcilerDetail = "";
    }

    private static void addFinding(
            List<BitFinding> findings, BitCode code, String path, String message, String expected) {
        BitFinding finding = BitFinding.of(
                code, code.defaultSeverity(), "platform-hooks", path, message, expected, code.remediation());
        BitSeverityPolicy.effectiveFinding(finding).ifPresent(findings::add);
    }

    private static String formatDetail(String subject, String detail) {
        String s = subject == null || subject.isBlank() ? "<unknown>" : subject.trim();
        String d = detail == null || detail.isBlank() ? "fallback" : detail.trim();
        return s + " (" + d + ")";
    }
}
