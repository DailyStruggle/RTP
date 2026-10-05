package io.github.dailystruggle.rtp.proxy.common.transport.direct;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * TLS settings for the {@code proxy-direct} socket (rtp-proxy-ADR-017), shared
 * by the proxy listener (server) and the backend binding (client).
 *
 * <p>{@link Mode#UNSET} vs {@link Mode#DISABLED} is significant: the listener
 * refuses a non-loopback plain-TCP bind unless {@code tls: false} is explicit.
 * Store passwords come from env vars ({@code *PasswordEnv}, preferred) with a
 * YAML fallback; values are never logged.</p>
 *
 * <p>Server: keystore required; a configured truststore enables client-cert
 * auth (mTLS, {@code requireClientAuth} default true). Client: truststore
 * optional (JVM default trust otherwise); keystore optional (client cert);
 * hostname verification on by default.</p>
 */
public final class ProxyDirectTlsConfig {

    /** TLS intent as configured. */
    public enum Mode { UNSET, ENABLED, DISABLED }

    public static final String DEFAULT_KEYSTORE_PASSWORD_ENV = "RTP_DIRECT_KEYSTORE_PASSWORD";
    public static final String DEFAULT_TRUSTSTORE_PASSWORD_ENV = "RTP_DIRECT_TRUSTSTORE_PASSWORD";
    public static final String DEFAULT_STORE_TYPE = "PKCS12";

    private static final String[] PREFERRED_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private final Mode mode;
    private final String keystorePath;
    private final char[] keystorePassword;
    private final String keystoreType;
    private final String truststorePath;
    private final char[] truststorePassword;
    private final String truststoreType;
    private final boolean requireClientAuth;
    private final boolean verifyHostname;

    private volatile SSLContext context;

    private ProxyDirectTlsConfig(Mode mode,
                                 String keystorePath, char[] keystorePassword, String keystoreType,
                                 String truststorePath, char[] truststorePassword, String truststoreType,
                                 boolean requireClientAuth, boolean verifyHostname) {
        this.mode = mode;
        this.keystorePath = blankToNull(keystorePath);
        this.keystorePassword = keystorePassword;
        this.keystoreType = keystoreType == null || keystoreType.isBlank() ? DEFAULT_STORE_TYPE : keystoreType;
        this.truststorePath = blankToNull(truststorePath);
        this.truststorePassword = truststorePassword;
        this.truststoreType = truststoreType == null || truststoreType.isBlank() ? DEFAULT_STORE_TYPE : truststoreType;
        this.requireClientAuth = requireClientAuth;
        this.verifyHostname = verifyHostname;
    }

    /** No TLS keys configured at all. */
    public static ProxyDirectTlsConfig unset() {
        return new ProxyDirectTlsConfig(Mode.UNSET, null, null, null, null, null, null, false, true);
    }

    /** Explicit {@code tls: false}. */
    public static ProxyDirectTlsConfig disabled() {
        return new ProxyDirectTlsConfig(Mode.DISABLED, null, null, null, null, null, null, false, true);
    }

    /**
     * Programmatic TLS config (tests / embedders). Either store may be null.
     * Client-cert auth is required on the server iff a truststore is given.
     */
    public static ProxyDirectTlsConfig enabled(String keystorePath, char[] keystorePassword,
                                               String truststorePath, char[] truststorePassword,
                                               boolean verifyHostname) {
        return new ProxyDirectTlsConfig(Mode.ENABLED,
                keystorePath, keystorePassword, DEFAULT_STORE_TYPE,
                truststorePath, truststorePassword, DEFAULT_STORE_TYPE,
                blankToNull(truststorePath) != null, verifyHostname);
    }

    /** Parse from the {@code transport.direct} section using real env vars. */
    public static ProxyDirectTlsConfig fromMap(Map<String, Object> section, Consumer<String> warn) {
        return fromMap(section, System::getenv, warn);
    }

    /**
     * Parse from a {@code transport.direct}-shaped map. Keys: {@code tls}
     * (boolean), {@code keystore}, {@code keystoreType}, {@code keystorePasswordEnv},
     * {@code keystorePassword}, {@code truststore}, {@code truststoreType},
     * {@code truststorePasswordEnv}, {@code truststorePassword},
     * {@code requireClientAuth}, {@code verifyHostname}.
     */
    public static ProxyDirectTlsConfig fromMap(Map<String, Object> section,
                                               Function<String, String> env,
                                               Consumer<String> warn) {
        if (section == null) return unset();
        Object tlsRaw = section.get("tls");
        Mode mode;
        if (tlsRaw == null || String.valueOf(tlsRaw).isBlank()) {
            mode = Mode.UNSET;
        } else {
            mode = parseBool(tlsRaw, "tls") ? Mode.ENABLED : Mode.DISABLED;
        }
        if (mode != Mode.ENABLED) {
            return mode == Mode.DISABLED ? disabled() : unset();
        }
        String ks = str(section, "keystore");
        String ts = str(section, "truststore");
        char[] ksPass = password(section, "keystorePassword", "keystorePasswordEnv",
                DEFAULT_KEYSTORE_PASSWORD_ENV, env, warn, ks != null);
        char[] tsPass = password(section, "truststorePassword", "truststorePasswordEnv",
                DEFAULT_TRUSTSTORE_PASSWORD_ENV, env, warn, ts != null);
        boolean requireClientAuth = section.containsKey("requireClientAuth")
                ? parseBool(section.get("requireClientAuth"), "requireClientAuth")
                : ts != null;
        boolean verifyHostname = !section.containsKey("verifyHostname")
                || parseBool(section.get("verifyHostname"), "verifyHostname");
        return new ProxyDirectTlsConfig(Mode.ENABLED,
                ks, ksPass, str(section, "keystoreType"),
                ts, tsPass, str(section, "truststoreType"),
                requireClientAuth, verifyHostname);
    }

    public Mode mode() { return mode; }
    public boolean enabled() { return mode == Mode.ENABLED; }
    public boolean explicitlyDisabled() { return mode == Mode.DISABLED; }
    public boolean requireClientAuth() { return requireClientAuth; }
    public boolean verifyHostname() { return verifyHostname; }
    public boolean hasKeystore() { return keystorePath != null; }

    /**
     * Unbound TLS server socket. Requires a keystore.
     *
     * @throws IllegalStateException when TLS is not enabled or no keystore is set
     */
    public SSLServerSocket createServerSocket() throws IOException, GeneralSecurityException {
        if (!enabled()) throw new IllegalStateException("proxy-direct TLS not enabled");
        if (keystorePath == null) {
            throw new IllegalStateException("proxy-direct tls: true requires transport.direct.keystore on the listener");
        }
        SSLServerSocket ss = (SSLServerSocket) context().getServerSocketFactory().createServerSocket();
        ss.setEnabledProtocols(filterProtocols(ss.getSupportedProtocols()));
        if (requireClientAuth) ss.setNeedClientAuth(true);
        return ss;
    }

    /**
     * Layer TLS over an already-connected socket and complete the handshake.
     * {@code host} drives SNI and (when enabled) hostname verification.
     */
    public SSLSocket wrapClient(Socket connected, String host, int port) throws IOException, GeneralSecurityException {
        if (!enabled()) throw new IllegalStateException("proxy-direct TLS not enabled");
        SSLSocket ssl = (SSLSocket) context().getSocketFactory().createSocket(connected, host, port, true);
        ssl.setEnabledProtocols(filterProtocols(ssl.getSupportedProtocols()));
        if (verifyHostname) {
            SSLParameters p = ssl.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(p);
        }
        ssl.startHandshake();
        return ssl;
    }

    private SSLContext context() throws IOException, GeneralSecurityException {
        SSLContext c = context;
        if (c != null) return c;
        synchronized (this) {
            if (context != null) return context;
            KeyManagerFactory kmf = null;
            if (keystorePath != null) {
                KeyStore ks = load(keystorePath, keystoreType, keystorePassword);
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, keystorePassword);
            }
            TrustManagerFactory tmf = null;
            if (truststorePath != null) {
                KeyStore ts = load(truststorePath, truststoreType, truststorePassword);
                tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ts);
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf == null ? null : kmf.getKeyManagers(),
                    tmf == null ? null : tmf.getTrustManagers(), null);
            context = ctx;
            return ctx;
        }
    }

    private static KeyStore load(String path, String type, char[] password)
            throws IOException, GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            ks.load(in, password);
        }
        return ks;
    }

    private static String[] filterProtocols(String[] supported) {
        List<String> out = new ArrayList<>(2);
        for (String want : PREFERRED_PROTOCOLS) {
            for (String s : supported) {
                if (s.equals(want)) out.add(s);
            }
        }
        if (out.isEmpty()) throw new IllegalStateException("no TLSv1.2+ protocol available");
        return out.toArray(new String[0]);
    }

    private static char[] password(Map<String, Object> section, String yamlKey, String envKey,
                                   String defaultEnv, Function<String, String> env,
                                   Consumer<String> warn, boolean storeConfigured) {
        String envName = str(section, envKey);
        if (envName == null) envName = defaultEnv;
        String fromEnv = env.apply(envName);
        if (fromEnv != null && !fromEnv.isEmpty()) return fromEnv.toCharArray();
        String fromYaml = str(section, yamlKey);
        if (fromYaml != null) {
            if (warn != null) {
                warn.accept("transport.direct." + yamlKey + " is set in YAML; prefer env var '"
                        + envName + "' (transport.direct." + envKey + ") to keep secrets out of config files.");
            }
            return fromYaml.toCharArray();
        }
        return storeConfigured ? new char[0] : null;
    }

    private static boolean parseBool(Object v, String key) {
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.equals("true")) return true;
        if (s.equals("false")) return false;
        throw new IllegalArgumentException("transport.direct." + key + ": expected true/false, got '" + v + "'");
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : blankToNull(String.valueOf(v));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
