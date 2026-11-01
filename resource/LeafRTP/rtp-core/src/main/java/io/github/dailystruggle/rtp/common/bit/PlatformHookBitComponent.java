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
 * Platform and reflective-hook fallback PBIT component ({@code platform-hooks}) under the
 * {@code TEST} namespace (ADR-026, ADR-113, ADR-121).
 *
 * <p>Surfaces structured findings when optional or reflective platform hooks
 * ({@code AnvilInputOpenerProvider}, {@code MenuRenderer} / Adventure book renderer,
 * {@code WorldBorder} packet/view bridge, {@code PaletteNormalizer} / registry mapping,
 * custom item resolvers, biome color sources, command registrars, message formatters,
 * player/world hooks, state samplers, or addon hooks) degrade to fallbacks on a server build.
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

    public static final BitCode CUSTOM_ITEM_DEGRADED = RtpBitCodes.TEST.register(
            4025,
            "TEST_PLATFORM_CUSTOM_ITEM_DEGRADED",
            Level.WARNING,
            BitTier.PBIT,
            "platform",
            "",
            "Check custom item hook or skull profile API compatibility; material fallback is active.");

    public static final BitCode BIOME_COLOR_DEGRADED = RtpBitCodes.TEST.register(
            4026,
            "TEST_PLATFORM_BIOME_COLOR_DEGRADED",
            Level.WARNING,
            BitTier.PBIT,
            "platform",
            "",
            "Check Biome map-color or region biome sampling compatibility; categorical palette fallback is active.");

    public static final BitCode COMMAND_REGISTRAR_DEGRADED = RtpBitCodes.TEST.register(
            4027,
            "TEST_PLATFORM_COMMAND_REGISTRAR_DEGRADED",
            Level.WARNING,
            BitTier.PBIT,
            "platform",
            "",
            "Check command map or Brigadier tree sync compatibility for this server version.");

    public static final BitCode MESSAGE_FORMAT_DEGRADED = RtpBitCodes.TEST.register(
            4028,
            "TEST_PLATFORM_MESSAGE_FORMAT_DEGRADED",
            Level.WARNING,
            BitTier.PBIT,
            "platform",
            "",
            "Check PlaceholderAPI, MiniMessage, or Adventure component serializer compatibility on this server.");

    public static final BitCode PLAYER_HOOK_DEGRADED = RtpBitCodes.TEST.register(
            4029,
            "TEST_PLATFORM_PLAYER_HOOK_DEGRADED",
            Level.WARNING,
            BitTier.PBIT,
            "platform",
            "",
            "Check platform player hook compatibility (view distance, permissions, teleport, or client block changes) for this server version.");

    public static final BitCode WORLD_HOOK_DEGRADED = RtpBitCodes.TEST.register(
            4030,
            "TEST_PLATFORM_WORLD_HOOK_DEGRADED",
            Level.WARNING,
            BitTier.PBIT,
            "platform",
            "",
            "Check platform world, chunk, scoreboard, or server accessor hook compatibility for this server version.");

    public static final BitCode SAMPLER_DEGRADED = RtpBitCodes.TEST.register(
            4031,
            "TEST_PLATFORM_SAMPLER_DEGRADED",
            Level.WARNING,
            BitTier.PBIT,
            "platform",
            "",
            "Check backend state sampler or tick-time probe compatibility for this server version.");

    public static final BitCode ADDON_HOOK_DEGRADED = RtpBitCodes.TEST.register(
            4032,
            "TEST_PLATFORM_ADDON_HOOK_DEGRADED",
            Level.WARNING,
            BitTier.PBIT,
            "platform",
            "",
            "Check addon integration or third-party plugin hook compatibility for this server version.");

    public static final BitComponent PLATFORM_HOOKS = new BitComponent(
            "platform-hooks",
            "platform",
            BitTier.PBIT,
            BitSideEffect.SAFE,
            COMPONENT_TIMEOUT_MILLIS,
            PlatformHookBitComponent::report);

    private static final CbitCounter HOOK_OBSERVATIONS = new CbitCounter();
    private static final CbitCounter ANVIL_INPUT_FALLBACKS = new CbitCounter();
    private static final CbitCounter MENU_RENDERER_FALLBACKS = new CbitCounter();
    private static final CbitCounter WORLD_BORDER_FALLBACKS = new CbitCounter();
    private static final CbitCounter PALETTE_RECONCILER_FALLBACKS = new CbitCounter();
    private static final CbitCounter CUSTOM_ITEM_FALLBACKS = new CbitCounter();
    private static final CbitCounter BIOME_COLOR_FALLBACKS = new CbitCounter();
    private static final CbitCounter COMMAND_REGISTRAR_FALLBACKS = new CbitCounter();
    private static final CbitCounter MESSAGE_FORMAT_FALLBACKS = new CbitCounter();
    private static final CbitCounter PLAYER_HOOK_FALLBACKS = new CbitCounter();
    private static final CbitCounter WORLD_HOOK_FALLBACKS = new CbitCounter();
    private static final CbitCounter SAMPLER_FALLBACKS = new CbitCounter();
    private static final CbitCounter ADDON_HOOK_FALLBACKS = new CbitCounter();

    private static volatile String lastAnvilInputDetail = "";
    private static volatile String lastMenuRendererDetail = "";
    private static volatile String lastWorldBorderDetail = "";
    private static volatile String lastPaletteReconcilerDetail = "";
    private static volatile String lastCustomItemDetail = "";
    private static volatile String lastBiomeColorDetail = "";
    private static volatile String lastCommandRegistrarDetail = "";
    private static volatile String lastMessageFormatDetail = "";
    private static volatile String lastPlayerHookDetail = "";
    private static volatile String lastWorldHookDetail = "";
    private static volatile String lastSamplerDetail = "";
    private static volatile String lastAddonHookDetail = "";

    private PlatformHookBitComponent() {}

    /** Records an observed platform-hook check sample. */
    public static void observe() {
        HOOK_OBSERVATIONS.observe();
    }

    /** Records multiple observed platform-hook check samples. */
    public static void observe(long samples) {
        HOOK_OBSERVATIONS.observe(samples);
    }

    public static long observations() {
        return HOOK_OBSERVATIONS.observations()
                + ANVIL_INPUT_FALLBACKS.observations()
                + MENU_RENDERER_FALLBACKS.observations()
                + WORLD_BORDER_FALLBACKS.observations()
                + PALETTE_RECONCILER_FALLBACKS.observations()
                + CUSTOM_ITEM_FALLBACKS.observations()
                + BIOME_COLOR_FALLBACKS.observations()
                + COMMAND_REGISTRAR_FALLBACKS.observations()
                + MESSAGE_FORMAT_FALLBACKS.observations()
                + PLAYER_HOOK_FALLBACKS.observations()
                + WORLD_HOOK_FALLBACKS.observations()
                + SAMPLER_FALLBACKS.observations()
                + ADDON_HOOK_FALLBACKS.observations();
    }

    public static boolean isObserved() {
        return observations() > 0L;
    }

    public static long anvilInputFallbackCount() {
        return ANVIL_INPUT_FALLBACKS.count();
    }

    public static long menuRendererFallbackCount() {
        return MENU_RENDERER_FALLBACKS.count();
    }

    public static long worldBorderFallbackCount() {
        return WORLD_BORDER_FALLBACKS.count();
    }

    public static long paletteReconcilerFallbackCount() {
        return PALETTE_RECONCILER_FALLBACKS.count();
    }

    public static long customItemFallbackCount() {
        return CUSTOM_ITEM_FALLBACKS.count();
    }

    public static long biomeColorFallbackCount() {
        return BIOME_COLOR_FALLBACKS.count();
    }

    public static long commandRegistrarFallbackCount() {
        return COMMAND_REGISTRAR_FALLBACKS.count();
    }

    public static long messageFormatFallbackCount() {
        return MESSAGE_FORMAT_FALLBACKS.count();
    }

    public static long playerHookFallbackCount() {
        return PLAYER_HOOK_FALLBACKS.count();
    }

    public static long worldHookFallbackCount() {
        return WORLD_HOOK_FALLBACKS.count();
    }

    public static long samplerFallbackCount() {
        return SAMPLER_FALLBACKS.count();
    }

    public static long addonHookFallbackCount() {
        return ADDON_HOOK_FALLBACKS.count();
    }

    public static String lastSamplerDetail() {
        return lastSamplerDetail;
    }

    public static void recordAnvilInputDegraded(String providerName, String detail) {
        ANVIL_INPUT_FALLBACKS.increment();
        lastAnvilInputDetail = formatDetail(providerName, detail);
    }

    public static void recordAnvilInputDegraded(String providerName, Throwable error) {
        recordAnvilInputDegraded(providerName, formatError(error));
        logFirstDegradation(ANVIL_INPUT_DEGRADED, ANVIL_INPUT_FALLBACKS, lastAnvilInputDetail, error);
    }

    public static void recordMenuRendererDegraded(String rendererId, String detail) {
        MENU_RENDERER_FALLBACKS.increment();
        lastMenuRendererDetail = formatDetail(rendererId, detail);
    }

    public static void recordMenuRendererDegraded(String rendererId, Throwable error) {
        recordMenuRendererDegraded(rendererId, formatError(error));
        logFirstDegradation(MENU_RENDERER_DEGRADED, MENU_RENDERER_FALLBACKS, lastMenuRendererDetail, error);
    }

    public static void recordWorldBorderDegraded(String surface, String detail) {
        WORLD_BORDER_FALLBACKS.increment();
        lastWorldBorderDetail = formatDetail(surface, detail);
    }

    public static void recordWorldBorderDegraded(String surface, Throwable error) {
        recordWorldBorderDegraded(surface, formatError(error));
        logFirstDegradation(WORLD_BORDER_DEGRADED, WORLD_BORDER_FALLBACKS, lastWorldBorderDetail, error);
    }

    public static void recordPaletteReconcilerDegraded(String identifier, String detail) {
        PALETTE_RECONCILER_FALLBACKS.increment();
        lastPaletteReconcilerDetail = formatDetail(identifier, detail);
    }

    public static void recordPaletteReconcilerDegraded(String identifier, Throwable error) {
        recordPaletteReconcilerDegraded(identifier, formatError(error));
        logFirstDegradation(
                PALETTE_RECONCILER_DEGRADED, PALETTE_RECONCILER_FALLBACKS, lastPaletteReconcilerDetail, error);
    }

    public static void recordCustomItemDegraded(String subject, String detail) {
        CUSTOM_ITEM_FALLBACKS.increment();
        lastCustomItemDetail = formatDetail(subject, detail);
    }

    public static void recordCustomItemDegraded(String subject, Throwable error) {
        recordCustomItemDegraded(subject, formatError(error));
        logFirstDegradation(CUSTOM_ITEM_DEGRADED, CUSTOM_ITEM_FALLBACKS, lastCustomItemDetail, error);
    }

    public static void recordBiomeColorDegraded(String subject, String detail) {
        BIOME_COLOR_FALLBACKS.increment();
        lastBiomeColorDetail = formatDetail(subject, detail);
    }

    public static void recordBiomeColorDegraded(String subject, Throwable error) {
        recordBiomeColorDegraded(subject, formatError(error));
        logFirstDegradation(BIOME_COLOR_DEGRADED, BIOME_COLOR_FALLBACKS, lastBiomeColorDetail, error);
    }

    public static void recordCommandRegistrarDegraded(String subject, String detail) {
        COMMAND_REGISTRAR_FALLBACKS.increment();
        lastCommandRegistrarDetail = formatDetail(subject, detail);
    }

    public static void recordCommandRegistrarDegraded(String subject, Throwable error) {
        recordCommandRegistrarDegraded(subject, formatError(error));
        logFirstDegradation(
                COMMAND_REGISTRAR_DEGRADED, COMMAND_REGISTRAR_FALLBACKS, lastCommandRegistrarDetail, error);
    }

    public static void recordMessageFormatDegraded(String subject, String detail) {
        MESSAGE_FORMAT_FALLBACKS.increment();
        lastMessageFormatDetail = formatDetail(subject, detail);
    }

    public static void recordMessageFormatDegraded(String subject, Throwable error) {
        recordMessageFormatDegraded(subject, formatError(error));
        logFirstDegradation(MESSAGE_FORMAT_DEGRADED, MESSAGE_FORMAT_FALLBACKS, lastMessageFormatDetail, error);
    }

    public static void recordPlayerHookDegraded(String subject, String detail) {
        PLAYER_HOOK_FALLBACKS.increment();
        lastPlayerHookDetail = formatDetail(subject, detail);
    }

    public static void recordPlayerHookDegraded(String subject, Throwable error) {
        recordPlayerHookDegraded(subject, formatError(error));
        logFirstDegradation(PLAYER_HOOK_DEGRADED, PLAYER_HOOK_FALLBACKS, lastPlayerHookDetail, error);
    }

    public static void recordWorldHookDegraded(String subject, String detail) {
        WORLD_HOOK_FALLBACKS.increment();
        lastWorldHookDetail = formatDetail(subject, detail);
    }

    public static void recordWorldHookDegraded(String subject, Throwable error) {
        recordWorldHookDegraded(subject, formatError(error));
        logFirstDegradation(WORLD_HOOK_DEGRADED, WORLD_HOOK_FALLBACKS, lastWorldHookDetail, error);
    }

    public static void recordSamplerDegraded(String subject, String detail) {
        SAMPLER_FALLBACKS.increment();
        lastSamplerDetail = formatDetail(subject, detail);
    }

    public static void recordSamplerDegraded(String subject, Throwable error) {
        recordSamplerDegraded(subject, formatError(error));
        logFirstDegradation(SAMPLER_DEGRADED, SAMPLER_FALLBACKS, lastSamplerDetail, error);
    }

    public static void recordStateSamplerDegraded(String subject, String detail) {
        recordSamplerDegraded(subject, detail);
    }

    public static void recordStateSamplerDegraded(String subject, Throwable error) {
        recordSamplerDegraded(subject, error);
    }

    public static void recordAddonHookDegraded(String subject, String detail) {
        ADDON_HOOK_FALLBACKS.increment();
        lastAddonHookDetail = formatDetail(subject, detail);
    }

    public static void recordAddonHookDegraded(String subject, Throwable error) {
        recordAddonHookDegraded(subject, formatError(error));
        logFirstDegradation(ADDON_HOOK_DEGRADED, ADDON_HOOK_FALLBACKS, lastAddonHookDetail, error);
    }

    public static BitReport report() {
        HOOK_OBSERVATIONS.observe();
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
        if (CUSTOM_ITEM_FALLBACKS.count() > 0) {
            addFinding(
                    findings,
                    CUSTOM_ITEM_DEGRADED,
                    "platform.hooks.customItem",
                    "Custom item resolver degraded to fallback " + CUSTOM_ITEM_FALLBACKS.count()
                            + " time(s); last: " + lastCustomItemDetail + ".",
                    "Custom item hook or skull profile resolves without throwing");
        }
        if (BIOME_COLOR_FALLBACKS.count() > 0) {
            addFinding(
                    findings,
                    BIOME_COLOR_DEGRADED,
                    "platform.hooks.biomeColor",
                    "Biome color lookup degraded to fallback " + BIOME_COLOR_FALLBACKS.count()
                            + " time(s); last: " + lastBiomeColorDetail + ".",
                    "Biome map color and region biome sampling resolve without throwing");
        }
        if (COMMAND_REGISTRAR_FALLBACKS.count() > 0) {
            addFinding(
                    findings,
                    COMMAND_REGISTRAR_DEGRADED,
                    "platform.hooks.commandRegistrar",
                    "Command registrar / Brigadier sync degraded to fallback "
                            + COMMAND_REGISTRAR_FALLBACKS.count() + " time(s); last: "
                            + lastCommandRegistrarDetail + ".",
                    "Command registration and Brigadier sync complete without throwing");
        }
        if (MESSAGE_FORMAT_FALLBACKS.count() > 0) {
            addFinding(
                    findings,
                    MESSAGE_FORMAT_DEGRADED,
                    "platform.hooks.messageFormat",
                    "Message formatting / component bridge degraded to fallback "
                            + MESSAGE_FORMAT_FALLBACKS.count() + " time(s); last: "
                            + lastMessageFormatDetail + ".",
                    "Message placeholder and component formatting complete without throwing");
        }
        if (PLAYER_HOOK_FALLBACKS.count() > 0) {
            addFinding(
                    findings,
                    PLAYER_HOOK_DEGRADED,
                    "platform.hooks.player",
                    "Player platform hook degraded to fallback " + PLAYER_HOOK_FALLBACKS.count()
                            + " time(s); last: " + lastPlayerHookDetail + ".",
                    "Platform player hooks resolve and invoke without throwing");
        }
        if (WORLD_HOOK_FALLBACKS.count() > 0) {
            addFinding(
                    findings,
                    WORLD_HOOK_DEGRADED,
                    "platform.hooks.world",
                    "World / server accessor platform hook degraded to fallback "
                            + WORLD_HOOK_FALLBACKS.count() + " time(s); last: "
                            + lastWorldHookDetail + ".",
                    "Platform world and server accessor hooks resolve without throwing");
        }
        if (SAMPLER_FALLBACKS.count() > 0) {
            addFinding(
                    findings,
                    SAMPLER_DEGRADED,
                    "platform.hooks.sampler",
                    "Backend state / tick sampler degraded to fallback " + SAMPLER_FALLBACKS.count()
                            + " time(s); last: " + lastSamplerDetail + ".",
                    "Backend state and tick-time samplers read without throwing");
        }
        if (ADDON_HOOK_FALLBACKS.count() > 0) {
            addFinding(
                    findings,
                    ADDON_HOOK_DEGRADED,
                    "platform.hooks.addon",
                    "Addon platform hook degraded to fallback " + ADDON_HOOK_FALLBACKS.count()
                            + " time(s); last: " + lastAddonHookDetail + ".",
                    "Addon integration hooks resolve and invoke without throwing");
        }
        return BitReport.of(findings);
    }

    public static void reset() {
        HOOK_OBSERVATIONS.reset();
        ANVIL_INPUT_FALLBACKS.reset();
        MENU_RENDERER_FALLBACKS.reset();
        WORLD_BORDER_FALLBACKS.reset();
        PALETTE_RECONCILER_FALLBACKS.reset();
        CUSTOM_ITEM_FALLBACKS.reset();
        BIOME_COLOR_FALLBACKS.reset();
        COMMAND_REGISTRAR_FALLBACKS.reset();
        MESSAGE_FORMAT_FALLBACKS.reset();
        PLAYER_HOOK_FALLBACKS.reset();
        WORLD_HOOK_FALLBACKS.reset();
        SAMPLER_FALLBACKS.reset();
        ADDON_HOOK_FALLBACKS.reset();
        lastAnvilInputDetail = "";
        lastMenuRendererDetail = "";
        lastWorldBorderDetail = "";
        lastPaletteReconcilerDetail = "";
        lastCustomItemDetail = "";
        lastBiomeColorDetail = "";
        lastCommandRegistrarDetail = "";
        lastMessageFormatDetail = "";
        lastPlayerHookDetail = "";
        lastWorldHookDetail = "";
        lastSamplerDetail = "";
        lastAddonHookDetail = "";
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

    private static String formatError(Throwable error) {
        if (error == null) {
            return "fallback";
        }
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }

    private static void logFirstDegradation(
            BitCode code, CbitCounter counter, String formattedDetail, Throwable error) {
        if (counter.count() == 1L) {
            String message = "[RTP] Platform hook degraded (" + code.key() + "): " + formattedDetail;
            if (error != null) {
                RTPRuntime.log(java.util.logging.Level.WARNING, message, error);
            } else {
                RTPRuntime.log(java.util.logging.Level.WARNING, message);
            }
        }
    }
}
