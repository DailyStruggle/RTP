package io.github.dailystruggle.rtp.common.commands.help;

import io.github.dailystruggle.rtp.common.tools.SupportInfo;
import org.jetbrains.annotations.Nullable;

public class SendMessage {
    public static String tagMessage(String message, @Nullable String tag) {
        if (message == null || message.isEmpty()) return message;
        String sig = SupportInfo.getSig();
        if (tag == null || tag.isEmpty()) tag = "MSG";
        return message + " §8(" + sig + ":" + tag + ")";
    }
}
