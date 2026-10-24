package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitCodeNamespace;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Bukkit;

/** SAFE startup checks for the Bukkit-family claim addon integrations. */
public final class ClaimSetupBit {
    private static final BitCodeNamespace NAMESPACE = RtpBitCodes.REGISTRY.reserve("CLAIM", "LeafRTPClaimAddon");

    public static final BitCode NOT_INTEGRATED = NAMESPACE.register(
            1, "CLAIM_PLUGIN_NOT_INTEGRATED", Level.WARNING, BitTier.PBIT, "claim-integrations", "",
            "Enable the integration in addons/integrations.yml or use a bit.yml severity override if intentional.");
    public static final BitCode INTEGRATION_FAILED = NAMESPACE.register(
            2, "CLAIM_INTEGRATION_FAILED", Level.ERROR, BitTier.PBIT, "claim-integrations", "",
            "Update the claim plugin or disable its integration until its API is compatible.");
    public static final BitCode INTEGRATION_INACTIVE = NAMESPACE.register(
            3, "CLAIM_INTEGRATION_INACTIVE", Level.ERROR, BitTier.PBIT, "claim-integrations", "",
            "Reload RTP after enabling the claim plugin so its verifier can be registered.");
    public static final BitCode PLUGIN_ABSENT = NAMESPACE.register(
            4, "CLAIM_PLUGIN_ABSENT", Level.INFO, BitTier.PBIT, "claim-integrations", "",
            "Install and enable the claim plugin, or turn off its integration flag if it is not used.");

    public static final io.github.dailystruggle.bit.BitComponent COMPONENT = new io.github.dailystruggle.bit.BitComponent(
            "claim-integrations", "claims", BitTier.PBIT, BitSideEffect.SAFE, 5_000, ClaimSetupBit::check);

    private ClaimSetupBit() {}

    /** Evaluates captured integration outcomes against the current enabled and incompatible sets. */
    public static BitReport report(
            List<ClaimIntegrations.IntegrationStatus> statuses,
            Set<String> enabledPlugins,
            Set<String> incompatiblePlugins) {
        Objects.requireNonNull(statuses, "statuses must not be null");
        Set<String> enabled = normalized(enabledPlugins);
        Set<String> incompatible = normalized(incompatiblePlugins);
        List<BitFinding> findings = new ArrayList<>();
        for (ClaimIntegrations.IntegrationStatus status : statuses) {
            String plugin = status.plugin();
            String path = "integrations." + status.key().name();
            boolean isEnabled = enabled.contains(normalize(plugin));
            if (!status.flag()) {
                if (isEnabled) {
                    findings.add(finding(NOT_INTEGRATED, Level.WARNING, plugin, path,
                            "The claim plugin is enabled but its integration flag is off.",
                            "integration enabled or intentionally overridden"));
                }
                continue;
            }
            if (!isEnabled) {
                findings.add(finding(PLUGIN_ABSENT, Level.INFO, plugin, path,
                        "The integration is configured but its claim plugin is not enabled.",
                        "plugin enabled when integration is configured"));
                continue;
            }
            if (status.outcome() == ClaimIntegrations.Outcome.FAILED
                    || incompatible.contains(normalize(plugin))
                    || incompatible.contains(normalize(keyPluginName(status.key())))) {
                String detail = status.message().isBlank()
                        ? "The claim plugin API failed compatibility checks at runtime."
                        : status.message();
                findings.add(finding(INTEGRATION_FAILED, Level.ERROR, plugin, path,
                        "The enabled claim integration failed: " + detail,
                        "integration registers and its API remains compatible"));
            } else if (status.outcome() == ClaimIntegrations.Outcome.SKIPPED) {
                findings.add(finding(INTEGRATION_INACTIVE, Level.ERROR, plugin, path,
                        "The claim plugin became enabled after integration setup, so its verifier is not registered.",
                        "reload RTP after enabling the plugin"));
            }
        }
        return BitReport.of(findings.stream()
                .map(BitSeverityPolicy::effectiveFinding)
                .flatMap(java.util.Optional::stream)
                .toList());
    }

    private static BitReport check() {
        List<ClaimIntegrations.IntegrationStatus> statuses = ClaimIntegrations.integrationStatuses();
        Set<String> enabled = new java.util.HashSet<>();
        Set<String> incompatible = new java.util.HashSet<>();
        for (ClaimIntegrations.IntegrationStatus status : statuses) {
            if (Bukkit.getPluginManager().isPluginEnabled(status.plugin())) enabled.add(status.plugin());
            if (ClaimCheckFailure.incompatibilityReported(status.plugin())) incompatible.add(status.plugin());
            String keyName = keyPluginName(status.key());
            if (ClaimCheckFailure.incompatibilityReported(keyName)) incompatible.add(keyName);
        }
        return report(statuses, enabled, incompatible);
    }

    private static Set<String> normalized(Set<String> values) {
        if (values == null) return Set.of();
        return values.stream().filter(Objects::nonNull).map(ClaimSetupBit::normalize)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String keyPluginName(IntegrationsKeys key) {
        String value = key.name();
        return value.startsWith("reroll") ? value.substring("reroll".length()) : value;
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static BitFinding finding(BitCode code, Level severity, String source, String path,
                                      String message, String expected) {
        return BitFinding.of(code, severity, source, path, message, expected, code.remediation());
    }
}
