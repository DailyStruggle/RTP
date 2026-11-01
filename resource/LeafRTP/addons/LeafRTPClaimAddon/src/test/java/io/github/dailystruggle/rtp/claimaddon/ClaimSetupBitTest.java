package io.github.dailystruggle.rtp.claimaddon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitReport;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-F-009 Claim addon setup PBIT")
class ClaimSetupBitTest {
    @Test
    void integratedAndAbsentAndDisabledStatesAreHealthy() {
        List<ClaimIntegrations.IntegrationStatus> statuses = List.of(
                status("WorldGuard", IntegrationsKeys.rerollWorldGuard, true,
                        ClaimIntegrations.Outcome.REGISTERED),
                status("GriefPrevention", IntegrationsKeys.rerollGriefPrevention, false,
                        ClaimIntegrations.Outcome.SKIPPED));

        assertTrue(ClaimSetupBit.report(statuses, Set.of("WorldGuard"), Set.of()).findings().isEmpty());
    }

    @Test
    void reportsConfiguredPluginAbsenceAsInformation() {
        BitReport report = ClaimSetupBit.report(
                List.of(status("GriefPrevention", IntegrationsKeys.rerollGriefPrevention, true,
                        ClaimIntegrations.Outcome.SKIPPED)), Set.of(), Set.of());

        assertHasCode(report, ClaimSetupBit.PLUGIN_ABSENT);
    }

    @Test
    void reportsEnabledButDisabledIntegrationAndPluginEnabledAfterSetup() {
        List<ClaimIntegrations.IntegrationStatus> statuses = List.of(
                status("WorldGuard", IntegrationsKeys.rerollWorldGuard, false,
                        ClaimIntegrations.Outcome.SKIPPED),
                status("GriefPrevention", IntegrationsKeys.rerollGriefPrevention, true,
                        ClaimIntegrations.Outcome.SKIPPED));
        BitReport report = ClaimSetupBit.report(statuses,
                Set.of("WorldGuard", "GriefPrevention"), Set.of());

        assertEquals(1, report.findings().stream()
                .filter(finding -> finding.code().equals(ClaimSetupBit.NOT_INTEGRATED.key())).count());
        assertEquals(1, report.findings().stream()
                .filter(finding -> finding.code().equals(ClaimSetupBit.INTEGRATION_INACTIVE.key())).count());
    }

    @Test
    void reportsRegistrationAndRuntimeApiFailures() {
        List<ClaimIntegrations.IntegrationStatus> statuses = List.of(
                status("Lands", IntegrationsKeys.rerollLands, true, ClaimIntegrations.Outcome.FAILED),
                status("WorldGuard", IntegrationsKeys.rerollWorldGuard, true,
                        ClaimIntegrations.Outcome.REGISTERED));
        BitReport report = ClaimSetupBit.report(statuses,
                Set.of("Lands", "WorldGuard"), Set.of("WorldGuard"));

        assertEquals(2, report.findings().stream()
                .filter(finding -> finding.code().equals(ClaimSetupBit.INTEGRATION_FAILED.key())).count());
    }

    private static ClaimIntegrations.IntegrationStatus status(
            String plugin, IntegrationsKeys key, boolean flag, ClaimIntegrations.Outcome outcome) {
        return new ClaimIntegrations.IntegrationStatus(plugin, key, flag, outcome,
                outcome == ClaimIntegrations.Outcome.FAILED ? "incompatible API" : "");
    }

    private static void assertHasCode(BitReport report, io.github.dailystruggle.bit.BitCode code) {
        assertTrue(report.findings().stream().anyMatch(finding -> finding.code().equals(code.key())),
                () -> "missing " + code.key() + " in " + report.findings());
    }
}
