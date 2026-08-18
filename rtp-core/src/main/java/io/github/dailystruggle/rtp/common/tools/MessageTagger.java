package io.github.dailystruggle.rtp.common.tools; // Moved out of commands/help

import io.github.dailystruggle.rtp.api.configuration.enums.SystemMessages;
import io.github.dailystruggle.rtp.common.RTP;
import org.jetbrains.annotations.Nullable;

public class MessageTagger {
    public static String tagMessage(String message, @Nullable String tag) {
        if (message == null || message.isEmpty()) return message;

        // Check whether the dev tag is enabled in messages.yml
        if (RTP.configs != null) {
            Object showDevTag = RTP.configs.getConfigValue(SystemMessages.showDevTag, false);
            boolean enabled = (showDevTag instanceof Boolean)
                    ? (Boolean) showDevTag
                    : Boolean.parseBoolean(showDevTag.toString());
            if (!enabled) return message;
        } else {
            // Config not yet loaded - suppress the tag by default
            return message;
        }

        String sig = SupportInfo.getSig();

        // Infer tag from the execution stack if omitted
        if (tag == null || tag.isEmpty()) {
            tag = "MSG"; // Fallback

            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

            for (StackTraceElement element : stackTrace) {
                String className = element.getClassName();

                // Bypass JVM threads, this utility, and the routing accessor interfaces
                if (!className.equals("java.lang.Thread") &&
                        !className.equals(MessageTagger.class.getName()) &&
                        !className.contains("ServerAccessor") &&
                        !className.contains("SendMessage")) { // Skips the Spigot implementation class

                    // Isolate the simple class name
                    String[] parts = className.split("\\.");
                    tag = parts[parts.length - 1];
                    break;
                }
            }
        }

        return message + " §8(" + sig + ":" + tag + ")";
    }
}
