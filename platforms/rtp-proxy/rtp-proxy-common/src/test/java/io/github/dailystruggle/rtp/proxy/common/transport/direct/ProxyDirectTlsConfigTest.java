package io.github.dailystruggle.rtp.proxy.common.transport.direct;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * proxy-direct TLS settings (rtp-proxy-ADR-017): UNSET vs DISABLED intent,
 * env-first store passwords, mTLS default, TLSv1.2+ only, hostname checks.
 */
class ProxyDirectTlsConfigTest {

    private static final String PASS = "changeit";
    private static final Function<String, String> NO_ENV = k -> null;

    private static Map<String, Object> section(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: absent or blank tls is UNSET, explicit false is DISABLED, junk throws")
    void modeParsing() {
        assertEquals(ProxyDirectTlsConfig.Mode.UNSET, ProxyDirectTlsConfig.fromMap(null, NO_ENV, null).mode());
        assertEquals(ProxyDirectTlsConfig.Mode.UNSET, ProxyDirectTlsConfig.fromMap(section(), NO_ENV, null).mode());
        assertEquals(ProxyDirectTlsConfig.Mode.UNSET,
                ProxyDirectTlsConfig.fromMap(section("tls", " "), NO_ENV, null).mode());

        ProxyDirectTlsConfig off = ProxyDirectTlsConfig.fromMap(section("tls", "FALSE"), NO_ENV, null);
        assertTrue(off.explicitlyDisabled());
        assertFalse(off.enabled());
        assertTrue(ProxyDirectTlsConfig.fromMap(section("tls", false), NO_ENV, null).explicitlyDisabled());
        assertFalse(ProxyDirectTlsConfig.unset().explicitlyDisabled());

        assertThrows(IllegalArgumentException.class,
                () -> ProxyDirectTlsConfig.fromMap(section("tls", "yes"), NO_ENV, null));
        assertThrows(IllegalArgumentException.class,
                () -> ProxyDirectTlsConfig.fromMap(section("tls", true, "verifyHostname", "nope"), NO_ENV, null));
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: a truststore turns on client-cert auth unless requireClientAuth overrides it")
    void clientAuthAndHostnameDefaults() {
        ProxyDirectTlsConfig withTrust = ProxyDirectTlsConfig.fromMap(
                section("tls", true, "keystore", "ks.p12", "truststore", "ts.p12"), NO_ENV, null);
        assertTrue(withTrust.enabled());
        assertTrue(withTrust.hasKeystore());
        assertTrue(withTrust.requireClientAuth());
        assertTrue(withTrust.verifyHostname());

        ProxyDirectTlsConfig overridden = ProxyDirectTlsConfig.fromMap(section("tls", "true",
                "truststore", "ts.p12", "requireClientAuth", "false", "verifyHostname", false), NO_ENV, null);
        assertFalse(overridden.requireClientAuth());
        assertFalse(overridden.verifyHostname());
        assertFalse(overridden.hasKeystore());

        ProxyDirectTlsConfig noTrust = ProxyDirectTlsConfig.fromMap(
                section("tls", true, "keystore", " ks.p12 ", "truststore", "  "), NO_ENV, null);
        assertFalse(noTrust.requireClientAuth());
        assertTrue(noTrust.hasKeystore());

        assertTrue(ProxyDirectTlsConfig.enabled(null, null, "ts.p12", null, true).requireClientAuth());
        assertFalse(ProxyDirectTlsConfig.enabled("ks.p12", null, " ", null, false).requireClientAuth());
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: store passwords come from the env var first; a YAML password warns without echoing it")
    void passwordSources() {
        List<String> warnings = new ArrayList<>();
        Map<String, String> env = Map.of("MY_KS_PASS", "from-env");
        ProxyDirectTlsConfig.fromMap(section("tls", true, "keystore", "ks.p12",
                "keystorePasswordEnv", "MY_KS_PASS", "keystorePassword", "yaml-secret"), env::get, warnings::add);
        assertTrue(warnings.isEmpty(), "env var wins, no warning: " + warnings);

        ProxyDirectTlsConfig.fromMap(section("tls", true, "truststore", "ts.p12",
                "truststorePassword", "yaml-secret"), NO_ENV, warnings::add);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains(ProxyDirectTlsConfig.DEFAULT_TRUSTSTORE_PASSWORD_ENV));
        assertFalse(warnings.get(0).contains("yaml-secret"), "password must never be logged");

        // Null warn sink is tolerated.
        assertTrue(ProxyDirectTlsConfig.fromMap(section("tls", true, "keystorePassword", "x"), NO_ENV, null).enabled());
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: socket factories refuse to run without TLS enabled or without a listener keystore")
    void socketGuards() throws Exception {
        assertThrows(IllegalStateException.class, () -> ProxyDirectTlsConfig.disabled().createServerSocket());
        assertThrows(IllegalStateException.class, () -> ProxyDirectTlsConfig.unset().wrapClient(new Socket(), "h", 1));
        assertThrows(IllegalStateException.class,
                () -> ProxyDirectTlsConfig.enabled(null, null, null, null, true).createServerSocket());
    }

    private static void keytool(String... args) throws Exception {
        String exe = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), "keytool failed: " + cmd);
    }

    /** Self-signed localhost key pair in {@code ks} and its certificate in trust store {@code ts}. */
    private static void makeStores(Path dir, Path ks, Path ts) throws Exception {
        Path cert = dir.resolve("rtp.cer");
        keytool("-genkeypair", "-alias", "rtp", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", ks.toString(),
                "-storepass", PASS, "-keypass", PASS);
        keytool("-exportcert", "-alias", "rtp", "-keystore", ks.toString(), "-storepass", PASS,
                "-file", cert.toString());
        keytool("-importcert", "-noprompt", "-alias", "rtp", "-file", cert.toString(),
                "-storetype", "PKCS12", "-keystore", ts.toString(), "-storepass", PASS);
    }

    /** Accepts {@code count} connections, handshakes, and echoes one byte; handshake failures are expected in some cases. */
    private static Thread serve(SSLServerSocket ss, int count) {
        Thread t = new Thread(() -> {
            for (int i = 0; i < count; i++) {
                try (SSLSocket s = (SSLSocket) ss.accept()) {
                    s.setSoTimeout(5_000);
                    s.startHandshake();
                    s.getOutputStream().write(s.getInputStream().read());
                    s.getOutputStream().flush();
                } catch (IOException expected) {
                    // Client-side assertions decide pass/fail.
                }
            }
        }, "tls-config-test-server");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static Socket connect(int port) throws IOException {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000);
        raw.setSoTimeout(5_000);
        return raw;
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: mTLS listener and client complete a TLSv1.2+ handshake; hostname mismatch fails only when verified")
    void mutualTlsHandshakeAndHostnameVerification(@TempDir Path dir) throws Exception {
        Path ks = dir.resolve("rtp.p12");
        Path ts = dir.resolve("trust.p12");
        makeStores(dir, ks, ts);

        ProxyDirectTlsConfig server = ProxyDirectTlsConfig.fromMap(section("tls", true,
                "keystore", ks.toString(), "keystorePasswordEnv", "T_KS",
                "truststore", ts.toString(), "truststorePasswordEnv", "T_TS"),
                Map.of("T_KS", PASS, "T_TS", PASS)::get, null);
        assertTrue(server.requireClientAuth());
        ProxyDirectTlsConfig client = ProxyDirectTlsConfig.enabled(
                ks.toString(), PASS.toCharArray(), ts.toString(), PASS.toCharArray(), true);
        ProxyDirectTlsConfig clientNoVerify = ProxyDirectTlsConfig.enabled(
                ks.toString(), PASS.toCharArray(), ts.toString(), PASS.toCharArray(), false);

        try (SSLServerSocket ss = server.createServerSocket()) {
            assertTrue(ss.getNeedClientAuth());
            for (String p : ss.getEnabledProtocols()) {
                assertTrue(p.equals("TLSv1.3") || p.equals("TLSv1.2"), p);
            }
            ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            ss.setSoTimeout(10_000);
            Thread t = serve(ss, 3);

            try (SSLSocket s = client.wrapClient(connect(ss.getLocalPort()), "localhost", ss.getLocalPort())) {
                assertTrue(s.getSession().getProtocol().startsWith("TLSv1."));
                s.getOutputStream().write(42);
                s.getOutputStream().flush();
                assertEquals(42, s.getInputStream().read());
            }

            try (Socket rawSocket = connect(ss.getLocalPort())) {
                assertThrows(IOException.class,
                        () -> client.wrapClient(rawSocket, "wrong.invalid", ss.getLocalPort()));
            }

            try (SSLSocket s = clientNoVerify.wrapClient(connect(ss.getLocalPort()), "wrong.invalid", ss.getLocalPort())) {
                s.getOutputStream().write(7);
                s.getOutputStream().flush();
                assertEquals(7, s.getInputStream().read());
            }
            t.join(10_000);
        }
    }
}
