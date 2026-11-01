package io.github.dailystruggle.rtp.common.usability;

import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.common.mock.MockRTPCommandSender;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Headless test harness simulating a user interacting with a command tree
 * through typing and tab-discovery cycles.
 *
 * <p>Tracks:
 * <ul>
 *   <li>{@code totalInputTokens}: distinct tokens/segments typed or accepted.</li>
 *   <li>{@code tabHops}: index/depth of the discovered candidate in suggestion lists.</li>
 *   <li>{@code errorsIncurred}: number of failed execution attempts requiring retry.</li>
 * </ul>
 */
public class UserJourneyAuditor {

    public record UsabilityScore(
            int totalTokens,
            int tabHops,
            int errorsIncurred,
            double titScore
    ) {
        @Override
        public String toString() {
            return String.format(
                    "Score[tokens=%d, tabHops=%d, errors=%d => TIT=%.1f]",
                    totalTokens, tabHops, errorsIncurred, titScore
            );
        }
    }

    private final TreeCommand command;
    private final RTPCommandSender sender;
    private final List<String> currentTokens = new ArrayList<>();
    private int totalInputTokens = 0;
    private int tabHops = 0;
    private int errorsIncurred = 0;

    public UserJourneyAuditor(TreeCommand command, RTPCommandSender sender) {
        this.command = Objects.requireNonNull(command, "command");
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    /**
     * Types a token directly without tab completion.
     */
    public UserJourneyAuditor type(String token) {
        if (token != null && !token.isEmpty()) {
            totalInputTokens++;
            currentTokens.add(token);
        }
        return this;
    }

    /**
     * Simulates the user typing a prefix token and pressing TAB to discover the target candidate.
     *
     * @param prefix the partial string typed before pressing TAB
     * @param targetExpected the expected candidate selected from suggestions
     * @return this auditor
     */
    public UserJourneyAuditor tabDiscover(String prefix, String targetExpected) {
        totalInputTokens++; // Typing prefix token

        List<String> queryArgs = new ArrayList<>(currentTokens);
        queryArgs.add(prefix);

        List<String> suggestions = command.onTabComplete(
                sender.uuid(),
                sender::hasPermission,
                queryArgs.toArray(new String[0])
        );

        if (suggestions == null) {
            suggestions = Collections.emptyList();
        }

        int index = suggestions.indexOf(targetExpected);
        if (index == -1) {
            throw new AssertionError(
                    "Target '" + targetExpected + "' was not suggested for prefix '" + prefix +
                            "'. Available suggestions: " + suggestions
            );
        }

        tabHops += (index + 1); // 1-based index in carousel
        currentTokens.add(targetExpected);
        return this;
    }

    /**
     * Executes the current token sequence against the command tree.
     *
     * @param expectSuccess whether execution is expected to succeed
     * @return this auditor
     */
    public UserJourneyAuditor execute(boolean expectSuccess) {
        String[] args = currentTokens.toArray(new String[0]);
        boolean success;
        try {
            CompletableFuture<Boolean> future = command.onCommand(
                    sender.uuid(),
                    sender::hasPermission,
                    sender::sendMessage,
                    args
            );
            io.github.dailystruggle.commandsapi.common.CommandsAPI.execute();
            Boolean res = future.getNow(Boolean.FALSE);
            success = res != null && res;
        } catch (Exception e) {
            success = false;
        }

        if (success != expectSuccess) {
            errorsIncurred++;
        }

        // Check if any error message was dispatched to sender
        if (sender instanceof MockRTPCommandSender mockSender) {
            boolean hasErrorMessage = mockSender.sentMessages.stream()
                    .anyMatch(m -> m.toLowerCase().contains("invalid")
                            || m.toLowerCase().contains("bad parameter")
                            || m.toLowerCase().contains("error")
                            || m.toLowerCase().contains("unknown"));
            if (hasErrorMessage && expectSuccess) {
                errorsIncurred++;
            }
        }

        return this;
    }

    /**
     * Resets the current command line for the next retry or step.
     */
    public UserJourneyAuditor clearTokens() {
        currentTokens.clear();
        return this;
    }

    /**
     * Calculates the composite Total Input Tokens (TIT) score.
     *
     * <p>Formula: {@code totalTokens + (tabHops * 0.5) + (errorsIncurred * 3.0)}
     */
    public UsabilityScore score() {
        double tit = totalInputTokens + (tabHops * 0.5) + (errorsIncurred * 3.0);
        return new UsabilityScore(totalInputTokens, tabHops, errorsIncurred, tit);
    }

    public List<String> currentTokens() {
        return Collections.unmodifiableList(currentTokens);
    }
}
