package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.DownloadInfo;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;

public class SupportInfo {
    private SupportInfo() {}
    public static String getSig() {
        DownloadInfo.Source source = DownloadInfo.source();
        if (source == DownloadInfo.Source.DEV) {
            return "Dev";
        }
        return source.name() + ":" + DownloadInfo.userId();
    }

    public static String getSupportSignature() {
        String pluginVersion = RTPRuntime.serverAccessor().getPluginVersion();
        String platform = RTPRuntime.serverAccessor().getPlatform();
        String serverVersion = RTPRuntime.serverAccessor().getServerVersion();
        String buildSignature = getSig();

        return String.format("Plugin Version: %s, Server Platform: %s, Server Version: %s, Build Signature: %s",
                pluginVersion, platform, serverVersion, buildSignature);
    }
}
