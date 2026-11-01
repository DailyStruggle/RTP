package io.github.dailystruggle.rtp.common.bit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import java.lang.System.Logger.Level;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-121 section 7: editor-authored YAML remains safe for config PBIT candidates. */
@DisplayName("ADR-121: config PBIT handles editor vault YAML")
class ConfigSnapshotVaultYamlTest {

    static final String VAULT = String.join("\n",
            "# credentials for tests",
            "database:",
            "  host: \"db.example\"",
            "  password: \"hunter2\"   # keep me",
            "  url: \"jdbc:mysql://rtp:s3cr3t@db.example:3306/rtp?useSSL=true\"",
            "  pool:",
            "    size: 4",
            "api-key: abcdef123",
            "secretEnv: \"RTP_NET_SECRET\"",
            "token: |",
            "  line-one-secret",
            "  line-two-secret",
            "servers:",
            "  - name: a",
            "    authToken: \"tok-aaa\"",
            "  - name: b",
            "    authToken: 'tok-bbb'",
            "jdbc: \"jdbc:postgresql://db/rtp?user=rtp&password=pg-secret\"",
            "");

    @Test
    @DisplayName("Editor vault YAML parses without a candidate finding")
    void vaultYamlParsesWithoutFinding() {
        ConfigSnapshot snapshot = ConfigSnapshot.of(Map.of("advanced/vault.yml", VAULT));

        BitReport report = ConfigPbit.run(ConfigPbit.YAML_SYNTAX, snapshot, ConfigEnvironment.HEADLESS);
        ConfigCandidateGate.Result gate = ConfigCandidateGate.evaluate(snapshot, ConfigEnvironment.HEADLESS);

        assertTrue(snapshot.unparsable().isEmpty());
        assertTrue(snapshot.document("advanced/vault.yml").isPresent());
        assertTrue(report.findings().isEmpty());
        assertFalse(gate.rejected());
    }

    @Test
    @DisplayName("Editor vault YAML also parses at an RTP-owned path")
    void vaultYamlParsesAtOwnedPath() {
        ConfigSnapshot snapshot = ConfigSnapshot.of(Map.of("config.yml", VAULT));

        BitReport report = ConfigPbit.run(ConfigPbit.YAML_SYNTAX, snapshot, ConfigEnvironment.HEADLESS);

        assertTrue(snapshot.unparsable().isEmpty());
        assertTrue(report.findings().isEmpty());
    }

    @Test
    @DisplayName("Unsupported YAML in an unowned addon file warns without rejecting the candidate")
    void unsupportedUnownedYamlIsNonBlocking() {
        ConfigSnapshot snapshot = ConfigSnapshot.of(Map.of("advanced/vault.yml", "secret: &secret value\n"));

        BitReport report = ConfigPbit.run(ConfigPbit.YAML_SYNTAX, snapshot, ConfigEnvironment.HEADLESS);
        ConfigCandidateGate.Result gate = ConfigCandidateGate.evaluate(snapshot, ConfigEnvironment.HEADLESS);

        assertEquals("rtpYaml.unsupported.anchor", snapshot.unparsableKeys().get("advanced/vault.yml"));
        assertEquals(1, report.findings().size());
        assertEquals("RTP_YAML_UNSUPPORTED_UNOWNED", report.findings().get(0).code());
        assertEquals(1002, report.findings().get(0).codeId());
        assertEquals(Level.WARNING, report.findings().get(0).severity());
        assertEquals(BitHealth.DEGRADED, report.health());
        assertFalse(gate.rejected());
    }

    @Test
    @DisplayName("Unsupported YAML in an RTP-owned file remains a blocking error")
    void unsupportedOwnedYamlRemainsError() {
        ConfigSnapshot snapshot = ConfigSnapshot.of(Map.of("config.yml", "secret: &secret value\n"));

        BitReport report = ConfigPbit.run(ConfigPbit.YAML_SYNTAX, snapshot, ConfigEnvironment.HEADLESS);

        assertEquals("rtpYaml.unsupported.anchor", snapshot.unparsableKeys().get("config.yml"));
        assertEquals(1, report.findings().size());
        assertEquals("RTP_YAML_UNPARSABLE", report.findings().get(0).code());
        assertEquals(1001, report.findings().get(0).codeId());
        assertEquals(Level.ERROR, report.findings().get(0).severity());
        assertEquals(BitHealth.FAILED, report.health());
    }

    @Test
    @DisplayName("A syntax error in an unowned addon file remains a blocking error")
    void malformedUnownedYamlRemainsError() {
        ConfigSnapshot snapshot = ConfigSnapshot.of(Map.of("advanced/vault.yml", "token: [\n"));

        BitReport report = ConfigPbit.run(ConfigPbit.YAML_SYNTAX, snapshot, ConfigEnvironment.HEADLESS);

        assertTrue(snapshot.unparsableKeys().get("advanced/vault.yml").startsWith("rtpYaml.syntax."));
        assertEquals("RTP_YAML_UNPARSABLE", report.findings().get(0).code());
        assertEquals(Level.ERROR, report.findings().get(0).severity());
    }
}
