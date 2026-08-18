package io.github.dailystruggle.rtp.api.menu;

import java.util.Deque;
import java.util.Objects;

/**
 * Per-consumer parameterization seam for command-tree menu reflector (ADR-044).
 *
 * <p>Controls chat suggestion prefix building ({@link #suggestPrefix}) and
 * YAML comment hover resolution ({@link #commentLookup}).
 */
public interface MenuConsumerProfile {

    /**
     * Builds the chat-suggestion prefix for a leaf parameter.
     *
     * @param commandPath   root-to-parent command path (unmodified)
     * @param parameterName leaf parameter name
     * @return prefix for {@link MenuAction.SuggestInput}; never {@code null}
     */
    String suggestPrefix(Deque<String> commandPath, String parameterName);

    /**
     * @return the YAML comment lookup used for parameter hover text. Consumers
     *         without YAML backing return {@link YamlCommentLookup#EMPTY}.
     *         Never {@code null}.
     */
    YamlCommentLookup commentLookup();

    /**
     * Trivial profile: prefixes are {@code "/rtp <path…> <param>="} and the
     * comment lookup is empty. Useful for tests and for command surfaces that
     * are not YAML-backed. The {@code =} separator matches commands-api's
     * parameter parser (which accepts only {@code =} for {@code k=v} pairs).
     */
    static MenuConsumerProfile defaultProfile() {
        return new MenuConsumerProfile() {
            @Override
            public String suggestPrefix(Deque<String> commandPath, String parameterName) {
                Objects.requireNonNull(commandPath, "commandPath");
                Objects.requireNonNull(parameterName, "parameterName");
                StringBuilder sb = new StringBuilder("/");
                boolean first = true;
                for (String segment : commandPath) {
                    if (!first) sb.append(' ');
                    sb.append(segment);
                    first = false;
                }
                if (!first) sb.append(' ');
                sb.append(parameterName).append('=');
                return sb.toString();
            }

            @Override
            public YamlCommentLookup commentLookup() {
                return YamlCommentLookup.EMPTY;
            }
        };
    }
}
