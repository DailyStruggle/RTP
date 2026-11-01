package io.github.dailystruggle.rtp.common.commands.reload;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.SystemMessages;
import io.github.dailystruggle.rtp.api.schematic.AbstractFileSchematicPaster;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.bit.config.ConfigCandidateGate;
import io.github.dailystruggle.rtp.common.commands.docs.DocsRegistry;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/** Shared reload lifecycle and operator feedback for full and editor-triggered reloads. */
public final class ReloadSupport {
    private static final String REJECTION_FALLBACK =
            "&c[P0] Reload rejected: [count] config error(s); the running config is kept."
                    + " First: [code] in [source] at [path].";

    private ReloadSupport() {}

    /** Reloads configs while blocking teleports and refreshes caches after a successful swap. */
    public static boolean reloadAndRefresh(Configs configs) {
        Objects.requireNonNull(configs, "configs");
        boolean wasReloading = RTP.reloading.getAndSet(true);
        try {
            if (!configs.reload()) return false;
            AbstractFileSchematicPaster.clearCache();
            if (RTPRuntime.serverAccessor() == null
                    || RTPRuntime.serverAccessor().getPluginDirectory() == null) {
                throw new IllegalStateException("Plugin directory is unavailable after config reload");
            }
            DocsRegistry.rebuildFromDataFolder(RTPRuntime.serverAccessor().getPluginDirectory());
            return true;
        } finally {
            RTP.reloading.set(wasReloading);
        }
    }

    /** Sends the localized, path-aware rejection message for a PBIT-rejected candidate. */
    public static void sendReloadRejected(UUID senderId, ConfigCandidateGate.Result rejection) {
        if (senderId == null || RTPRuntime.serverAccessor() == null) {
            RTPRuntime.log(Level.WARNING, "Could not send reloadRejected: sender or server accessor unavailable");
            return;
        }

        String template = REJECTION_FALLBACK;
        try {
            Configs configs = ActiveConfigs.get();
            if (configs != null) {
                Object configured = configs.getConfigValue(SystemMessages.reloadRejected, REJECTION_FALLBACK);
                if (configured != null && !String.valueOf(configured).isBlank()) {
                    template = String.valueOf(configured);
                }
            }
        } catch (RuntimeException messageFailure) {
            RTPRuntime.log(Level.WARNING, "Could not read the reload rejection message", messageFailure);
        }

        try {
            RTPRuntime.serverAccessor().sendMessage(
                    RTPAPI.serverId, senderId, ConfigCandidateGate.format(template, rejection));
        } catch (RuntimeException sendFailure) {
            RTPRuntime.log(Level.WARNING, "Could not deliver the reload rejection message", sendFailure);
        }
    }
}
