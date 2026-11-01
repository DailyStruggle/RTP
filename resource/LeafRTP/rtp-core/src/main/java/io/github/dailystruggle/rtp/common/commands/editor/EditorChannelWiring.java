package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.common.commands.editor.channel.BytesocksTransport;
import io.github.dailystruggle.rtp.common.commands.editor.channel.ChannelTransport;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorChannel;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorKeys;
import io.github.dailystruggle.rtp.common.commands.editor.channel.LoopbackTransport;
import io.github.dailystruggle.rtp.common.commands.editor.channel.TrustedEditors;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;

import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.region.util.DurationParser;
import io.github.dailystruggle.rtp.common.selection.region.util.TemporalUnit;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Builds the session's {@link EditorChannel} and registers the editor's message handlers on it
 * (ADR-106 §5.3): {@code focus} goes to the live feed's bounded queue (read on its async tick),
 * {@code apply} to the {@code /rtp editor apply} pipeline through
 * {@link EditorLoopbackApply#handleAuthorized}. Nothing else is accepted from the page: walk paths
 * and curve state flow server to page only, for applied geometry. Operator notices are the
 * configurable {@link CommandMessages} {@code editor*} keys, sent to the session's creator.
 */
final class EditorChannelWiring {

    static final String KEYS_DIR = "keys";
    static final String TRUSTED_FILE = "trusted-editors.json";

    private EditorChannelWiring() {
    }

    /**
     * Opens the local channel over a fresh loopback socket. Off the main thread: loads (or creates)
     * the key pair and the trusted list from {@code <dataFolder>/editor/}. The loopback opens
     * synchronously, so the result is already complete; {@link #ready} reads it without blocking.
     */
    static CompletableFuture<EditorChannel> openLocal(UUID creator, Supplier<EditorLiveFeed> feed) {
        Path editorDir = editorDir();
        return open(new LoopbackTransport(), pluginKeys(editorDir), trusted(editorDir),
                new OperatorNotices(creator), feed, EditorLoopbackApply.forChannel());
    }

    /**
     * Opens the hosted channel on {@code http}'s relay (ADR-106 §5.4, §5.6): creates a relay channel,
     * joins it and registers the handlers; referenced applies are fetched from {@code http}'s bytebin.
     * Never blocks (S-005): one future chain, bounded by the create and connect timeouts.
     *
     * @return completes with the open channel, or with {@code null} when the relay can't be created
     * or joined - logged and told to {@code creator} (S-004), the session then continues
     * snapshot-only. Never completes exceptionally.
     */
    static CompletableFuture<EditorChannel> openHosted(UUID creator, EditorHttpTransport http, Supplier<EditorLiveFeed> feed) {
        OperatorNotices notices = new OperatorNotices(creator);
        String relay = http.getRelayUrl();
        CompletableFuture<BytesocksTransport.CreatedChannel> created;
        try {
            created = BytesocksTransport.createChannelNegotiated(
                    http.httpClient(), relay, http.getFallbackRelayUrl(), http.serviceHeaders());
        } catch (RuntimeException e) {
            created = CompletableFuture.failedFuture(e);
        }
        return created
                .orTimeout(BytesocksTransport.CREATE_TIMEOUT_MILLIS + 5_000L, TimeUnit.MILLISECONDS)
                .thenCompose(desc -> {
                    try {
                        Path editorDir = editorDir();
                        BytesocksTransport transport = new BytesocksTransport(http.httpClient(), desc.relayUrl(), desc.id(),
                                desc.ttlMillis(), desc.framesPerWindow(), System::currentTimeMillis,
                                BytesocksTransport.schedulerTimer());
                        EditorChannel ch = new EditorChannel(desc.id(), pluginKeys(editorDir),
                                trusted(editorDir), transport, notices, System::currentTimeMillis);
                        register(ch, feed, EditorLoopbackApply.forChannel(http));
                        return ch.start();
                    } catch (RuntimeException e) {
                        return CompletableFuture.<EditorChannel>failedFuture(e);
                    }
                })
                .handle((ch, t) -> {
                    if (t == null) return ch;
                    Throwable c = cause(t);
                    return snapshotOnly(notices, relay, String.valueOf(c.getMessage()), c);
                });
    }

    /**
     * The channel of an already completed {@link #open} / {@link #openLocal} result, read with
     * {@code getNow} (never waits). A transport that has not opened yet is closed once it does.
     *
     * @throws IOException when opening failed or did not finish synchronously
     */
    static EditorChannel ready(CompletableFuture<EditorChannel> opening) throws IOException {
        if (!opening.isDone()) {
            opening.thenAccept(ch -> ch.close("opened too late"));
            throw new IOException("editor channel did not open synchronously");
        }
        try {
            return opening.getNow(null);
        } catch (CompletionException | CancellationException e) {
            Throwable c = cause(e);
            throw c instanceof IOException io ? io : new IOException(String.valueOf(c.getMessage()), c);
        }
    }

    /** The failure inside {@link CompletionException} wrappers. */
    static Throwable cause(Throwable t) {
        Throwable c = t;
        while (c instanceof CompletionException && c.getCause() != null) c = c.getCause();
        return c;
    }

    private static EditorChannel snapshotOnly(OperatorNotices notices, String relay, String reason, Throwable t) {
        String msg = "[editor] relay channel at " + relay + " unavailable (" + reason
                + "); this editor session is snapshot-only";
        if (BytesocksTransport.isNetworkOrStatusFailure(t)) {
            RTPRuntime.log(Level.WARNING, msg);
        } else {
            RTPRuntime.log(Level.WARNING, msg, t);
        }
        notices.closed("relay unavailable: " + reason);
        return null;
    }

    /** The server's persisted key pair, or a session-only one when the key directory is not writable. */
    private static EditorKeys pluginKeys(Path editorDir) {
        try {
            return EditorKeys.loadOrCreate(editorDir.resolve(KEYS_DIR));
        } catch (IOException e) {
            RTPRuntime.log(Level.WARNING, "[editor] channel key directory not writable; using a key for this session only: "
                    + e.getMessage(), e);
            return EditorKeys.generate();
        }
    }

    /** The persisted trusted list under the configured max age. Disk I/O: off the main thread. */
    static TrustedEditors trusted(Path editorDir) {
        return TrustedEditors.load(editorDir.resolve(TRUSTED_FILE), trustMaxAgeMillis());
    }

    /**
     * {@code advanced/network.yml editor.trust.maxAgeDays} in milliseconds; {@code 0} (never expire)
     * when unset, negative or unreadable (logged). Reads the file: off the main thread.
     */
    static long trustMaxAgeMillis() {
        File dir = RTPRuntime.serverAccessor() == null ? null : RTPRuntime.serverAccessor().getPluginDirectory();
        File file = dir == null ? null : new File(dir, "advanced" + File.separator + "network.yml");
        if (file == null || !file.isFile()) return 0L;
        try {
            RtpYamlSection editor = RtpYamlConfig.load(file).getConfigurationSection("editor");
            RtpYamlSection trust = editor == null ? null : editor.getConfigurationSection("trust");
            Object rawAge = trust == null ? null : trust.get("maxAgeDays");
            if (rawAge == null) return 0L;
            DurationParser.ParsedDuration parsed =
                    DurationParser.parse(rawAge.toString(), TemporalUnit.DAY);
            double ageMillis = parsed == null ? Double.NaN : parsed.toMillis();
            if (!Double.isFinite(ageMillis) || ageMillis < 0.0 || ageMillis >= 0x1.0p63) {
                RTPRuntime.log(Level.WARNING, "[editor] " + file + " editor.trust.maxAgeDays "
                        + rawAge + " is invalid or negative; trust never expires");
                return 0L;
            }
            return Math.round(ageMillis);
        } catch (IOException | RuntimeException e) {
            RTPRuntime.log(Level.WARNING, "[editor] could not read " + file + " editor.trust; trust never expires: " + e.getMessage(), e);
            return 0L;
        }
    }

    /** Any transport, explicit collaborators (tests drive this with the in-memory pair). */
    static CompletableFuture<EditorChannel> open(ChannelTransport transport, EditorKeys keys, TrustedEditors trusted,
                                                 EditorChannel.Notifier notifier, Supplier<EditorLiveFeed> feed,
                                                 EditorLoopbackApply apply) {
        EditorChannel ch = new EditorChannel(EditorChannel.newChannelId(), keys, trusted, transport, notifier,
                System::currentTimeMillis);
        register(ch, feed, apply);
        return ch.start();
    }

    static void register(EditorChannel ch, Supplier<EditorLiveFeed> feed, EditorLoopbackApply apply) {
        ch.register("focus", in -> {
            EditorLiveFeed f = feed.get();
            if (f != null) f.offerClientMessage(in.json());
        });
        ch.register(EditorLoopbackApply.ACTION, in -> apply.handleAuthorized(in.body()).whenComplete((ack, t) -> {
            if (ack == null || !in.reply(ack)) {
                RTPRuntime.log(Level.WARNING, "[editor] apply_ack undeliverable to editor key " + in.from().substring(0, 16)
                        + (ack == null ? "" : ": " + ack), t);
            }
        }));
        // Addon namespaces, push types and the trusted hello-reply's protocol members (ADR-107)
        EditorExtensions.attach(ch);
    }

    static Path editorDir() {
        File pluginDir = RTPRuntime.serverAccessor() == null ? null : RTPRuntime.serverAccessor().getPluginDirectory();
        return (pluginDir == null ? new File(".") : pluginDir).toPath().resolve("editor");
    }

    /** Configurable value of {@code key}, or {@code fallback} before the configs load. */
    static String message(CommandMessages key, String fallback) {
        try {
            if (ActiveConfigs.get() == null) return fallback;
            Object v = ActiveConfigs.get().getConfigValue(key, fallback);
            return v == null ? fallback : v.toString();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * The {@code advanced/messages/commands.yml} value of {@code key} read by name (editor keys without
     * a {@link CommandMessages} constant), or {@code fallback} before the configs load.
     */
    static String message(String key, String fallback) {
        try {
            if (ActiveConfigs.get() == null) return fallback;
            if (!(ActiveConfigs.get().getParser(CommandMessages.class) instanceof ConfigParser<?> parser)) return fallback;
            RtpYamlSection root = parser.getYamlRoot();
            String v = root == null ? null : root.getString(key);
            return v == null ? fallback : v;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Sends to the session's operator (the console when it ran the command); empty templates are skipped. */
    static void tell(UUID target, String text) {
        if (target == null || text == null || text.isBlank() || RTPRuntime.serverAccessor() == null) return;
        try {
            RTPRuntime.serverAccessor().sendMessage(RTPAPI.serverId, target, text);
        } catch (RuntimeException e) {
            RTPRuntime.log(Level.FINE, "[editor] channel notice not delivered: " + e.getMessage());
        }
    }

    /** Trust prompt and channel notices for the operator who created the session. */
    static final class OperatorNotices implements EditorChannel.Notifier {
        private final UUID creator;

        OperatorNotices(UUID creator) {
            this.creator = creator;
        }

        @Override
        public void trustPrompt(String nonce, String fingerprint) {
            if (creator == null || RTPRuntime.serverAccessor() == null) return;
            // Full fingerprint: a 16-hex prefix is cheap to collide for a look-alike key
            String text = message(CommandMessages.editorTrustPrompt,
                    "[P0] A web editor page asks for access with code [nonce]. If your page shows the same code, click here.")
                    .replace("[nonce]", nonce).replace("[fingerprint]", fingerprint);
            String hover = message(CommandMessages.editorTrustPromptHover, "/rtp editor trust nonce=[nonce] (key [fingerprint])")
                    .replace("[nonce]", nonce).replace("[fingerprint]", fingerprint);
            if (text.isBlank()) return;
            try {
                RTPCommandSender sender = RTPRuntime.serverAccessor().getSender(creator);
                if (sender != null) {
                    RTPRuntime.serverAccessor().sendMessage(sender, text, hover, "/rtp editor trust nonce=" + nonce);
                    return;
                }
            } catch (RuntimeException e) {
                RTPRuntime.log(Level.FINE, "[editor] clickable trust prompt failed, sending plain text: " + e.getMessage());
            }
            tell(creator, text);
        }

        @Override
        public void opened() {
            tell(creator, message(CommandMessages.editorChannelOpened,
                    "[P0] Web editor channel open: once its browser is trusted, the page can preview and Hot-Apply."));
        }

        @Override
        public void closed(String reason) {
            String r = reason == null ? "" : reason;
            if (r.startsWith("superseded")) return;
            if (r.startsWith("expired")) {
                tell(creator, message(CommandMessages.editorChannelExpired,
                        "[P0] Web editor channel expired after [minutes] minutes. Run the editor command again to resume.")
                        .replace("[minutes]", String.valueOf(EditorLiveFeed.DEFAULT_TTL_MILLIS / 60_000L)));
            } else {
                tell(creator, message(CommandMessages.editorChannelLost,
                        "[P0] Web editor channel lost: [reason]. The page keeps working from its snapshot.")
                        .replace("[reason]", r));
            }
        }
    }
}
