package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Shared parameter extraction and factory utilities for menu command leaves (ADR-050).
 */
final class MenuCommandUtils {

    private MenuCommandUtils() {}

    public static String first(@Nullable Map<String, List<String>> parameterValues, String key) {
        if (parameterValues == null) return null;
        List<String> values = parameterValues.get(key);
        if (values == null || values.isEmpty()) return null;
        return values.get(0);
    }

    public static String[] splitDots(@Nullable String dotted) {
        if (dotted == null || dotted.isEmpty()) return new String[0];
        String[] parts = dotted.split("\\.");
        int kept = 0;
        for (String s : parts) {
            if (s != null && !s.isEmpty()) kept++;
        }
        String[] out = new String[kept];
        int i = 0;
        for (String s : parts) {
            if (s != null && !s.isEmpty()) out[i++] = s;
        }
        return out;
    }

    public static CommandParameter freeParam(String perm, String desc) {
        return new CommandParameter(perm, desc, (uuid, value) -> true) {
            @Override
            public Set<String> values() {
                return Collections.emptySet();
            }
        };
    }

    public static CommandParameter posIntParam(String perm, String desc) {
        return new CommandParameter(perm, desc, (uuid, value) -> {
            if (value == null) return false;
            try {
                return Integer.parseInt(value) >= 1;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }) {
            @Override
            public Set<String> values() {
                return Collections.emptySet();
            }
        };
    }
}
