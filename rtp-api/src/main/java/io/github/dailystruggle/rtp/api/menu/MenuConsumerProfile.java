package io.github.dailystruggle.rtp.api.menu;

import java.util.Deque;
import java.util.Objects;

/**
 * Per-consumer parameterization seam for the command-tree menu reflector
 * (ADR-044).
 *
 * <p>The reflector itself is consumer-agnostic — it walks
 * {@code commands-api}'s node graph filtered by permission. Each concrete
 * consumer (the {@code /rtp config} browser first, region picker and
 * {@code /rtpadmin} wizards later) supplies a {@code MenuConsumerProfile}
 * to control:
 *
 * <ul>
 *   <li>How a leaf parameter maps to a chat suggestion prefix
 *       ({@link #suggestPrefix}); e.g. for {@code /rtp config} the prefix is
 *       {@code "/rtp config <file> <key>="} matching CONFIG_COMMAND_SPEC §2.4.
 *   <li>Which {@link YamlCommentLookup} feeds the hover-text resolver
 *       ({@link #commentLookup}); consumers without YAML backing return
 *       {@link YamlCommentLookup#EMPTY} and the reflector falls back to
 *       declared type + bounds.</li>
 * </ul>
 *
 * <p>Hover text for <i>command</i> fragments always comes from
 * {@code CommandsAPICommand#description()} (resolved from {@code messages.yml})
 * — the profile is consulted only for <i>parameter</i> fragments.
 */
public interface MenuConsumerProfile {

    /**
     * Build the chat-suggestion prefix for a leaf parameter.
     *
     * @param commandPath   path of command-node names from root to the parent
     *                      command of {@code parameterName}, in order (root at
     *                      head). The deque is not modified by the callee.
     * @param parameterName the leaf parameter name as declared in
     *                      {@code commands-api}.
     * @return the prefix the renderer should emit as
     *         {@link MenuAction.SuggestInput}. Never {@code null}.
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
