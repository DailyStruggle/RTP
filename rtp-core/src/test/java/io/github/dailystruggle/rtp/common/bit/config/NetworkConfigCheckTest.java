package io.github.dailystruggle.rtp.common.bit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ADR-121 config PBIT network owner (advanced/network.yml, REQ-RTP-NET-002)")
class NetworkConfigCheckTest {
    private static final String ENABLED = """
            network:
              enabled: true
              serverId: "survival"
            transport:
              type: "redis"
              redis:
                host: "redis.internal"
                port: 6379
            heartbeat:
              intervalMs: 1000
              staleAfterMs: 5000
            routing:
              mode: "auto"
              dispatchMode: "direct-db"
            regionCollision:
              policy: "warn"
            version: 1.1
            """;

    @AfterEach
    void resetPolicy() {
        BitSeverityPolicy.resetDefaults();
    }

    private static List<BitFinding> raw(String text) {
        return NetworkConfigCheck.INSTANCE.check(ConfigSnapshot.of(Map.of(NetworkConfigCheck.PATH, text)),
                ConfigEnvironment.HEADLESS);
    }

    private static List<BitFinding> withCode(List<BitFinding> findings, String code) {
        return findings.stream().filter(finding -> finding.code().equals(code)).toList();
    }

    @Test
    @DisplayName("ADR-121 a valid enabled redis network.yml raises no RTP_NETWORK_* finding")
    void validEnabledConfigIsClean() {
        List<BitFinding> findings = raw(ENABLED);
        assertTrue(findings.isEmpty(), findings.toString());
    }

    @Test
    @DisplayName("ADR-121 shipped default network.yml raises no RTP_NETWORK_* finding")
    void shippedDefaultIsClean() throws IOException {
        Path shipped = repositoryRoot().resolve("rtp-plugin/src/main/resources/advanced/network.yml");
        List<BitFinding> findings = raw(Files.readString(shipped, StandardCharsets.UTF_8));
        assertTrue(findings.isEmpty(), findings.toString());
    }

    @Test
    @DisplayName("ADR-121 absent network.yml raises nothing")
    void absentFileIsSilent() {
        assertTrue(NetworkConfigCheck.INSTANCE.check(ConfigSnapshot.of(Map.of("config.yml", "a: 1\n")),
                ConfigEnvironment.HEADLESS).isEmpty());
    }

    @Test
    @DisplayName("ADR-121 RTP_NETWORK_KEY_UNKNOWN fires for an unknown top-level key with a suggestion")
    void unknownTopLevelKey() {
        List<BitFinding> findings = withCode(raw(ENABLED + "heartbeet:\n  intervalMs: 1\n"), "RTP_NETWORK_KEY_UNKNOWN");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("heartbeet", findings.get(0).path());
        assertTrue(findings.get(0).suggestion().contains("heartbeat"), findings.get(0).suggestion());
        assertTrue(withCode(raw(ENABLED + "servers:\n  hub:\n    permission: \"\"\n"), "RTP_NETWORK_KEY_UNKNOWN").isEmpty());
    }

    @Test
    @DisplayName("ADR-121 RTP_NETWORK_SECTION_NOT_MAP fires when a section is a scalar")
    void sectionNotMap() {
        List<BitFinding> findings = withCode(raw("network:\n  enabled: false\nqueue: 50\n"), "RTP_NETWORK_SECTION_NOT_MAP");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("queue", findings.get(0).path());
        assertTrue(withCode(raw("version: 1.1\n"), "RTP_NETWORK_SECTION_NOT_MAP").isEmpty());
    }

    @Test
    @DisplayName("ADR-121 RTP_NETWORK_SERVER_ID_MISSING fires only when enabled; staged to WARNING unless strictConfig")
    void serverIdMissing() {
        String text = ENABLED.replace("serverId: \"survival\"", "serverId: \"\"");
        List<BitFinding> findings = withCode(raw(text), "RTP_NETWORK_SERVER_ID_MISSING");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals(Level.ERROR, findings.get(0).severity());
        assertTrue(withCode(raw(text.replace("enabled: true", "enabled: false")), "RTP_NETWORK_SERVER_ID_MISSING").isEmpty());

        ConfigSnapshot snapshot = ConfigSnapshot.of(Map.of(NetworkConfigCheck.PATH, text));
        var report = ConfigPbit.run(NetworkConfigCheck.INSTANCE, snapshot, ConfigEnvironment.HEADLESS);
        assertEquals(Level.WARNING, withCode(report.findings(), "RTP_NETWORK_SERVER_ID_MISSING").get(0).severity());
        BitSeverityPolicy.load(false, true, Map.of());
        report = ConfigPbit.run(NetworkConfigCheck.INSTANCE, snapshot, ConfigEnvironment.HEADLESS);
        assertEquals(Level.ERROR, withCode(report.findings(), "RTP_NETWORK_SERVER_ID_MISSING").get(0).severity());
        assertEquals(BitHealth.FAILED, report.health());
    }

    @Test
    @DisplayName("ADR-121 RTP_NETWORK_TRANSPORT_UNKNOWN fires with a suggestion; ERROR when enabled, WARNING when disabled")
    void transportUnknown() {
        List<BitFinding> findings = withCode(raw(ENABLED.replace("type: \"redis\"", "type: \"rediss\"")),
                "RTP_NETWORK_TRANSPORT_UNKNOWN");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals(Level.ERROR, findings.get(0).severity());
        assertTrue(findings.get(0).suggestion().contains("redis"), findings.get(0).suggestion());

        findings = withCode(raw("network:\n  enabled: false\ntransport:\n  type: \"carrier-pigeon\"\n"),
                "RTP_NETWORK_TRANSPORT_UNKNOWN");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals(Level.WARNING, findings.get(0).severity());
        assertTrue(withCode(raw(ENABLED.replace("type: \"redis\"", "type: \"Plugin-Message\"")),
                "RTP_NETWORK_TRANSPORT_UNKNOWN").isEmpty());
    }

    @Test
    @DisplayName("ADR-121 RTP_NETWORK_REDIS_HOST_MISSING fires for redis with an empty host")
    void redisHostMissing() {
        List<BitFinding> findings = withCode(raw(ENABLED.replace("host: \"redis.internal\"", "host: \"\"")),
                "RTP_NETWORK_REDIS_HOST_MISSING");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("transport.redis.host", findings.get(0).path());
        assertTrue(withCode(raw(ENABLED.replace("    host: \"redis.internal\"\n", "")),
                "RTP_NETWORK_REDIS_HOST_MISSING").isEmpty(), "absent host defaults to localhost");
    }

    @Test
    @DisplayName("ADR-121 RTP_NETWORK_PROXIES_MISSING fires for proxy-direct without transport.proxies")
    void proxiesMissing() {
        String text = ENABLED.replace("type: \"redis\"", "type: \"proxy-direct\"");
        List<BitFinding> findings = withCode(raw(text), "RTP_NETWORK_PROXIES_MISSING");
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(withCode(raw(text.replace("  redis:\n", "  proxies:\n    - \"proxy:25577\"\n  redis:\n")),
                "RTP_NETWORK_PROXIES_MISSING").isEmpty());
    }

    @Test
    @DisplayName("ADR-121 RTP_NETWORK_PORT_RANGE fires for a redis port outside 1..65535")
    void portRange() {
        List<BitFinding> findings = withCode(raw(ENABLED.replace("port: 6379", "port: 70000")), "RTP_NETWORK_PORT_RANGE");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals(Level.ERROR, findings.get(0).severity());
        assertEquals(1, withCode(raw(ENABLED.replace("port: 6379", "port: \"abc\"")), "RTP_NETWORK_PORT_RANGE").size());
        assertTrue(withCode(raw(ENABLED.replace("port: 6379", "port: 6380")), "RTP_NETWORK_PORT_RANGE").isEmpty());
    }

    @Test
    @DisplayName("ADR-121 RTP_NETWORK_DURATION_INVALID fires for an unparsable duration; unit durations pass")
    void durationInvalid() {
        List<BitFinding> findings = withCode(raw(ENABLED + "queue:\n  flushIntervalMs: \"soon\"\n"),
                "RTP_NETWORK_DURATION_INVALID");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("queue.flushIntervalMs", findings.get(0).path());
        assertTrue(withCode(raw(ENABLED + "queue:\n  flushIntervalMs: \"2s\"\n"), "RTP_NETWORK_DURATION_INVALID").isEmpty());
    }

    @Test
    @DisplayName("ADR-121 RTP_NETWORK_STALE_BEFORE_INTERVAL fires when staleAfterMs is not above intervalMs")
    void staleBeforeInterval() {
        List<BitFinding> findings = withCode(raw(ENABLED.replace("staleAfterMs: 5000", "staleAfterMs: 1000")),
                "RTP_NETWORK_STALE_BEFORE_INTERVAL");
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(withCode(raw(ENABLED.replace("staleAfterMs: 5000", "staleAfterMs: \"5s\"")),
                "RTP_NETWORK_STALE_BEFORE_INTERVAL").isEmpty());
    }

    @Test
    @DisplayName("ADR-121 RTP_NETWORK_VALUE_UNKNOWN fires for routing.mode, dispatchMode, role and policy fallbacks")
    void valueUnknown() {
        List<BitFinding> findings = withCode(raw(ENABLED.replace("mode: \"auto\"", "mode: \"alway\"")),
                "RTP_NETWORK_VALUE_UNKNOWN");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("routing.mode", findings.get(0).path());
        assertEquals(Level.WARNING, findings.get(0).severity());
        assertTrue(withCode(raw(ENABLED.replace("mode: \"auto\"", "mode: \"always\"")),
                "RTP_NETWORK_VALUE_UNKNOWN").isEmpty());

        assertEquals(1, withCode(raw(ENABLED.replace("dispatchMode: \"direct-db\"", "dispatchMode: \"direkt\"")),
                "RTP_NETWORK_VALUE_UNKNOWN").size());
        assertEquals(1, withCode(raw(ENABLED.replace("policy: \"warn\"", "policy: \"reject-startup\"")),
                "RTP_NETWORK_VALUE_UNKNOWN").size());
        assertEquals(1, withCode(raw(ENABLED.replace("serverId: \"survival\"", "serverId: \"survival\"\n  role: \"lobby\"")),
                "RTP_NETWORK_VALUE_UNKNOWN").size());
        assertTrue(withCode(raw(ENABLED.replace("mode: \"auto\"", "mode: \"cross-server\"")),
                "RTP_NETWORK_VALUE_UNKNOWN").isEmpty());
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("could not locate repository root");
        return current;
    }
}
