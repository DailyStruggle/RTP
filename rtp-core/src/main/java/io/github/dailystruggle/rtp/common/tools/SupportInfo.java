package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;

public class SupportInfo {
    public static String getSig() {
        String buildSignature = RTPAPI.DOWNLOADER_ID;
        if (buildSignature.startsWith("%%")) {
            buildSignature = "Dev";
        }
        return buildSignature;
    }

    public static String getSupportSignature() {
        String pluginVersion = RTP.serverAccessor.getPluginVersion();
        String platform = RTP.serverAccessor.getPlatform();
        String serverVersion = RTP.serverAccessor.getServerVersion();
        String buildSignature = getSig();

        return String.format("Plugin Version: %s, Server Platform: %s, Server Version: %s, Build Signature: %s",
                pluginVersion, platform, serverVersion, buildSignature);
    }
}
