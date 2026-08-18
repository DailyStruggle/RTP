package io.github.dailystruggle.rtp.common.commands.config;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Subcommand {@code /rtp config search query:<text>}.
 * Receives anvil search input from the menu and delegates to the injected {@link Handler}.
 */
public class ConfigSearchSubCmd extends BaseRTPCmdImpl {

    /** Parameter name carried in {@link io.github.dailystruggle.rtp.api.menu.MenuAction.PromptAnvilInput#paramName()}. */
    public static final String PARAM_QUERY = "query";

    /**
     * SAM the production wiring (rtp-plugin) injects to actually run the
     * search and render the results book. Implementations must apply the
     * {@code rtp.config} permission gate (or stronger) before rendering.
     */
    @FunctionalInterface
    public interface Handler {
        /**
         * Runs the search and renders the results for the given player.
         *
         * @param callerId the submitting player's UUID
         * @param query    the raw query string typed in the anvil GUI
         */
        void onSearch(UUID callerId, String query);
    }

    private @Nullable Handler handler;

    /**
     * Constructs the subcommand with no handler wired.
     *
     * @param parent the parent command tree, or {@code null}
     */
    public ConfigSearchSubCmd(@Nullable CommandsAPICommand parent) {
        this(parent, null);
    }

    /**
     * Constructs the subcommand with an optional handler.
     *
     * @param parent  the parent command tree, or {@code null}
     * @param handler the search handler, or {@code null} to wire later
     */
    public ConfigSearchSubCmd(@Nullable CommandsAPICommand parent, @Nullable Handler handler) {
        super(parent);
        this.handler = handler;
        addParameter(
                PARAM_QUERY,
                new CommandParameter("rtp.config", "config search query text",
                        (uuid, s) -> s != null && !s.isEmpty()) {
                    @Override
                    public Set<String> values() {
                        return Collections.emptySet();
                    }
                });
    }

    /**
     * Replace the wired handler after construction.
     *
     * @param handler the new handler, or {@code null} to disable
     */
    public void setHandler(@Nullable Handler handler) {
        this.handler = handler;
    }

    @Override
    public String name() {
        return "search";
    }

    @Override
    public String permission() {
        return "rtp.config";
    }

    @Override
    public String description() {
        return "search across all config keys and values";
    }

    @Override
    public boolean onCommand(
            UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);
        List<String> qArgs = parameterValues.get(PARAM_QUERY);
        String query = (qArgs == null || qArgs.isEmpty()) ? "" : qArgs.get(0);
        if (handler == null) {
            RTP.log(Level.WARNING,
                    "config search invoked without a wired handler (renderer disabled?) for " + callerId);
            return false;
        }
        try {
            handler.onSearch(callerId, query);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "config search handler failed for " + callerId
                            + " query='" + query + "': " + e.getMessage(), e);
            return false;
        }
        return true;
    }
}
