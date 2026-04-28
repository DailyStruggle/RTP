package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.DownloadInfo;
import io.github.dailystruggle.rtp.common.RTP;

public class SupportInfo {
    public static String getSig() {
        DownloadInfo.Source source = DownloadInfo.source();
        if (source == DownloadInfo.Source.DEV) {
            return "Dev";
        }
        return source.name() + ":" + DownloadInfo.userId();
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
