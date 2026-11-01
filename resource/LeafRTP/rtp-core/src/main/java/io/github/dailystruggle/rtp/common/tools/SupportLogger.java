package io.github.dailystruggle.rtp.common.tools;


import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.logging.Level;

public class SupportLogger {
    private SupportLogger() {}
    public static void logException(Level level, String context, Throwable throwable) {
        String report = "[RTP ERROR REPORT]\n" +
                "Context: " + context + "\n" +
                "Support Signature: " + SupportInfo.getSupportSignature() + "\n" +
                "Stack Trace:";
        RTPRuntime.log(level, report, throwable);
    }
}
