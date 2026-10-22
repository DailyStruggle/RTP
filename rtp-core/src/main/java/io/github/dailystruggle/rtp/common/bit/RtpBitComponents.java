package io.github.dailystruggle.rtp.common.bit;

import io.github.dailystruggle.bit.BitCodes;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.bit.BuiltInTestable;
import io.github.dailystruggle.bit.PbitResult;
import io.github.dailystruggle.rtp.common.action.ActionConfigLoader;
import io.github.dailystruggle.rtp.common.action.ActionCbitCounters;
import io.github.dailystruggle.rtp.common.action.ActionDefinitionValidator;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;

/** Default SAFE PBIT components owned by RTP core (ADR-121). */
public final class RtpBitComponents {
    private static final long COMPONENT_TIMEOUT_MILLIS = 5_000;

    private static final BitComponent ACTION_DEFINITIONS = new BitComponent(
            "action-definitions", "action", BitTier.PBIT, BitSideEffect.SAFE,
            COMPONENT_TIMEOUT_MILLIS, RtpBitComponents::checkActionDefinitions);
    private static final BitComponent ACTION_CBIT = new BitComponent(
            "action-cbit", "action", BitTier.CBIT, BitSideEffect.SAFE,
            COMPONENT_TIMEOUT_MILLIS, ActionCbitCounters::report);
    private static final BitComponent ANVIL_ZSTD = anvilComponent("anvil-zstd",
            () -> bitReport("anvil-zstd",
                    io.github.dailystruggle.rtp.anvil.DynamicZstdBinding.INSTANCE));
    private static final BitComponent ANVIL_LINEAR = anvilComponent("anvil-linear",
            () -> bitReport("anvil-linear",
                    io.github.dailystruggle.rtp.anvil.LinearRegionReader.INSTANCE));
    private static final BitComponent ANVIL_FORMAT_DETECTOR = anvilComponent("anvil-format-detector",
            () -> bitReport("anvil-format-detector",
                    io.github.dailystruggle.rtp.anvil.RegionFormatDetector.INSTANCE));

    private static final List<BitComponent> DEFAULTS = List.of(
            ACTION_DEFINITIONS, ANVIL_ZSTD, ANVIL_LINEAR, ANVIL_FORMAT_DETECTOR);

    private RtpBitComponents() {}

    /** Registers RTP-owned PBIT components once; repeated calls preserve existing registrations. */
    public static void registerDefaults() {
        for (BitComponent component : DEFAULTS) RtpBit.RUNNER.register(component);
        RtpBit.RUNNER.register(ACTION_CBIT);
        io.github.dailystruggle.rtp.common.bit.config.ConfigPbit.registerComponents();
    }

    static List<BitComponent> defaults() {
        return DEFAULTS;
    }

    static BitComponent actionDefinitions() {
        return ACTION_DEFINITIONS;
    }

    private static BitComponent anvilComponent(String id, java.util.concurrent.Callable<BitReport> check) {
        return new BitComponent(id, "anvil", BitTier.PBIT, BitSideEffect.SAFE,
                COMPONENT_TIMEOUT_MILLIS, check);
    }

    private static BitReport checkActionDefinitions() {
        List<BitFinding> findings = new ArrayList<>();
        for (ActionConfigLoader.LoadedDefinition definition : ActionConfigLoader.loadedDefinitions()) {
            for (BitFinding finding : ActionDefinitionValidator.validate(
                    definition.id(), definition.values(), definition.source()).findings()) {
                BitSeverityPolicy.effectiveFinding(finding).ifPresent(findings::add);
            }
        }
        for (ActionConfigLoader.LoadFailure failure : ActionConfigLoader.loadFailures()) {
            findings.add(BitFinding.of(BitCodes.PBIT_FAILED, Level.ERROR, failure.source(), "",
                    failure.message(), "valid action definition YAML", BitCodes.PBIT_FAILED.remediation()));
        }
        return BitReport.of(findings);
    }

    static BitReport bitReport(String id, BuiltInTestable component) {
        PbitResult pbit = component.executePbit();
        BitHealth health = component.getHealthStatus();
        if (health == null) health = BitHealth.FAILED;

        List<BitFinding> findings = new ArrayList<>(1);
        if (!pbit.passed()) {
            Level severity = health == BitHealth.DEGRADED ? Level.WARNING : Level.ERROR;
            findings.add(BitFinding.of(BitCodes.PBIT_FAILED, severity, id, "",
                    pbit.diagnosticMessage(), "PBIT passes", BitCodes.PBIT_FAILED.remediation()));
        } else if (health == BitHealth.DEGRADED) {
            findings.add(BitFinding.of(BitCodes.HEALTH_DEGRADED, id, "",
                    "The component reports degraded health.", "HEALTHY", BitCodes.HEALTH_DEGRADED.remediation()));
        } else if (health == BitHealth.FAILED) {
            findings.add(BitFinding.of(BitCodes.HEALTH_FAILED, id, "",
                    "The component reports failed health despite a passing PBIT.", "HEALTHY",
                    BitCodes.HEALTH_FAILED.remediation()));
        }

        BitHealth reportHealth = health;
        if (!pbit.passed() && health != BitHealth.DEGRADED) reportHealth = BitHealth.FAILED;
        return new BitReport(BitReport.CURRENT_SCHEMA, reportHealth, findings);
    }
}
