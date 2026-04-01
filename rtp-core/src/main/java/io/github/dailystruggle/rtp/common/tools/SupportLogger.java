package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.common.RTP;

import java.util.logging.Level;

public class SupportLogger {
    public static void logException(Level level, String context, Throwable throwable) {
        String report = "[RTP ERROR REPORT]\n" +
                "Context: " + context + "\n" +
                "Support Signature: " + SupportInfo.getSupportSignature() + "\n" +
                "Stack Trace:";
        RTP.log(level, report, throwable);
    }
}
