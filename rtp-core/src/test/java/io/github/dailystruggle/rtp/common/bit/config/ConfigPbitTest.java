package io.github.dailystruggle.rtp.common.bit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ADR-121 section 6: config PBIT registry, policy and components")
class ConfigPbitTest {
    @AfterEach
    void resetPolicy() {
        BitSeverityPolicy.resetDefaults();
    }

    @Test
    @DisplayName("RTP_YAML_UNPARSABLE fires for a file that does not parse")
    void unparsableYamlIsAnError() {
        ConfigSnapshot snapshot = ConfigSnapshot.of(Map.of("config.yml", ConfigSnapshotTest.BROKEN_YAML));

        BitReport report = ConfigPbit.run(ConfigPbit.YAML_SYNTAX, snapshot, ConfigEnvironment.HEADLESS);

        assertEquals(1, report.findings().size());
        BitFinding finding = report.findings().get(0);
        assertEquals("RTP_YAML_UNPARSABLE", finding.code());
        assertEquals(1001, finding.codeId());
        assertEquals(Level.ERROR, finding.severity());
        assertEquals("config.yml", finding.source());
        assertEquals(BitHealth.FAILED, report.health());
    }

    @Test
    @DisplayName("RTP namespace 1000-1999 is reserved and owns RTP_YAML_UNPARSABLE")
    void rtpNamespaceIsReserved() {
        assertTrue(RtpBitCodes.RTP.containsId(1000));
        assertTrue(RtpBitCodes.RTP.containsId(1999));
        assertFalse(RtpBitCodes.RTP.containsId(2000));
        assertTrue(RtpBitCodes.RTP.ownsKey(ConfigPbit.YAML_UNPARSABLE.key()));
        assertTrue(RtpBitCodes.OWNERS.contains(ConfigPbit.class));
    }

    @Test
    @DisplayName("a throwing check becomes BIT_COMPONENT_THROWN instead of being swallowed")
    void thrownCheckIsReported() {
        ConfigOwnerCheck throwing = new ConfigOwnerCheck() {
            @Override
            public String id() {
                return "throwing-test";
            }

            @Override
            public List<BitFinding> check(ConfigSnapshot snapshot, ConfigEnvironment environment) {
                throw new IllegalStateException("boom");
            }
        };

        BitReport report = ConfigPbit.run(throwing, ConfigSnapshot.of(Map.of()), null);

        assertEquals("BIT_COMPONENT_THROWN", report.findings().get(0).code());
        assertEquals("config-throwing-test", report.findings().get(0).source());
        assertEquals(Level.ERROR, report.findings().get(0).severity());
    }

    @Test
    @DisplayName("staged config codes are WARNING until strictConfig is true")
    void stagedCodesFollowStrictConfig() {
        BitCode staged = BitSeverityPolicy.stagedConfig(new BitCode(1999, "RTP_TEST_STAGED", Level.ERROR,
                BitTier.PBIT, "config", "", "test"));
        BitFinding finding = BitFinding.of(staged, "config.yml", "radius", "m", "e", "");

        assertEquals(Level.WARNING, BitSeverityPolicy.effective(finding).orElseThrow());
        BitSeverityPolicy.load(false, true, Map.of());
        assertTrue(BitSeverityPolicy.strictConfig());
        assertEquals(Level.ERROR, BitSeverityPolicy.effective(finding).orElseThrow());
        assertEquals(Level.ERROR, BitSeverityPolicy.effective(BitFinding.of(ConfigPbit.YAML_UNPARSABLE,
                "x", "", "m", "e", "")).orElseThrow());
    }

    @Test
    @DisplayName("owner checks register once and appear as SAFE PBIT config-<id> components")
    void componentsAreRegistered() {
        assertFalse(ConfigPbit.register(ConfigPbit.YAML_SYNTAX), "duplicate ids are refused");
        ConfigPbit.registerComponents();

        var component = RtpBit.RUNNER.components().stream()
                .filter(candidate -> candidate.id().equals("config-yaml"))
                .findFirst().orElseThrow();
        assertEquals(ConfigPbit.SUBSYSTEM, component.subsystem());
        assertEquals(BitTier.PBIT, component.tier());
        assertEquals(BitSideEffect.SAFE, component.sideEffect());
        assertTrue(ConfigPbit.checks().contains(ConfigPbit.YAML_SYNTAX));
    }
}
