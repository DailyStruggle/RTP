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
 * Subcommand: {@code /rtp config search query:<text>}.
 *
 * <p>Implements the submit-landing leaf for the {@code rtp menu} config search
 * feature (PROPOSAL-rtp-menu-config-search.md §6 Q4 Decision A). The menu
 * renderer mints a {@link io.github.dailystruggle.rtp.api.menu.MenuAction.PromptAnvilInput}
 * with parent path {@code ["menu","config","search"]} and parameter
 * {@code "query"}; the anvil opener submits {@code /rtp menu config search query:&lt;typed&gt;}
 * as the player which lands here (after walking through the menu mirror
 * subtree). This leaf delegates to the injected {@link Handler} which is
 * responsible for running the cross-parser search and rendering the results
 * book.
 *
 * <p>When no {@link Handler} is wired (e.g. a platform without a renderer)
 * the leaf accepts the command silently with a WARN log; the menu prompt
 * never reaches this code path in that configuration because the prompt
 * dispatch arm gates on the same builder being present.
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
     * Replace the wired handler after construction. Used by platform wiring
     * (e.g. {@code RTPCmdBukkit}) so the leaf can be registered eagerly by
     * {@link ConfigCmd#addCommands()} (5-tick deferred) and have its handler
     * filled in once the renderer + token registry + search builder are
     * available.
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
