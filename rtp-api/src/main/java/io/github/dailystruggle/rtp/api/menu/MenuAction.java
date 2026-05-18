package io.github.dailystruggle.rtp.api.menu;

import java.net.URI;
import java.util.Arrays;
import java.util.Objects;

/**
 * Sealed action attached to a {@link MenuFragment}'s click.
 *
 * <p>Renderers translate each variant to the platform-appropriate click event;
 * the menu surface itself never embeds a raw command string, so a malicious or
 * buggy renderer cannot smuggle arbitrary commands into a click (ADR-035 §Security
 * boundary).
 *
 * <ul>
 *   <li>{@link RunRtpCommand} — invoke the {@code /rtp …} pipeline with the
 *       given argument tail. Renderers always indirect this through the
 *       single-use token mechanism (ADR-035 §3); the raw args are stored only
 *       in the {@code rtp-core} side of the token registry.</li>
 *   <li>{@link OpenMenu} — open a menu page at the given path under the
 *       {@code /rtp} root (used for back and forward-descend navigation).
 *       The path is resolved server-side against the live command tree by
 *       {@code MenuRedeemSubcommand}; it never re-enters the commands-api
 *       parser, so navigation rows are not constrained by parameter-vs-subcommand
 *       grammar at the {@code menu} subcommand. Empty path = root menu page.</li>
 *   <li>{@link OpenParamPicker} — open a parameter-value picker sub-page for a
 *       named parameter on the {@code TreeCommand} reached by the given path.
 *       Server-resolved; value rows execute the assembled command with
 *       {@code paramName:value} appended (Stage A.2).</li>
 *   <li>{@link ChangePage} — move to another page of the same model.</li>
 *   <li>{@link SuggestInput} — pre-fill the caller's chat with the given
 *       prefix (e.g. {@code "/rtp config performance ASYNC:"}); the player
 *       types the value (CONFIG_COMMAND_SPEC §2.4).</li>
 *   <li>{@link OpenExternalUrl} — open a URL on the client; useful for help
 *       links. Renderers may refuse this variant in restricted environments.</li>
 * </ul>
 */
public sealed interface MenuAction
        permits MenuAction.RunRtpCommand,
                MenuAction.OpenMenu,
                MenuAction.OpenParamPicker,
                MenuAction.ChangePage,
                MenuAction.SuggestInput,
                MenuAction.OpenExternalUrl {

    /**
     * Invoke {@code /rtp <args>} as the clicking player. {@code args} is the
     * sub-command path <i>below</i> the {@code /rtp} root, mirroring the
     * convention used by {@link OpenMenu#path()}: do not include a leading
     * {@code "rtp"} token. An empty array executes the bare {@code /rtp}
     * (root) command. Server-side dispatch feeds {@code args} directly into
     * the root {@code TreeCommand} starting at index {@code 0}, so a leading
     * {@code "rtp"} would be parsed as a non-existent sub-command and rejected
     * via the configurable {@code msgInvalidCommand} path. The argument array
     * is defensively copied; callers cannot mutate the stored action.
     */
    record RunRtpCommand(String[] args) implements MenuAction {
        public RunRtpCommand {
            Objects.requireNonNull(args, "args");
            args = args.clone();
            for (int i = 0; i < args.length; i++) {
                Objects.requireNonNull(args[i], "args[" + i + "]");
            }
        }

        @Override
        public String[] args() {
            return args.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof RunRtpCommand other && Arrays.equals(args, other.args);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(args);
        }

        @Override
        public String toString() {
            return "RunRtpCommand" + Arrays.toString(args);
        }
    }

    /**
     * Open a menu page at the given path under the {@code /rtp} root. Used for
     * back ({@code path} = parent path) and forward-descend ({@code path} = child
     * path) navigation. {@code path} is the args from the {@code /rtp} root down
     * to (and including) the target node, e.g. {@code ["config", "performance"]}
     * for {@code /rtp config performance}. An empty path opens the root menu page.
     *
     * <p>The path is resolved server-side by {@code MenuRedeemSubcommand} against
     * the live {@code TreeCommand} graph; it does not re-enter the commands-api
     * parser, so {@code OpenMenu} navigation is independent of the
     * parameter-vs-subcommand grammar at the {@code menu} subcommand
     * (which only knows the {@code token} parameter).
     */
    record OpenMenu(String[] path) implements MenuAction {
        public OpenMenu {
            Objects.requireNonNull(path, "path");
            path = path.clone();
            for (int i = 0; i < path.length; i++) {
                Objects.requireNonNull(path[i], "path[" + i + "]");
            }
        }

        @Override
        public String[] path() {
            return path.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof OpenMenu other && Arrays.equals(path, other.path);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(path);
        }

        @Override
        public String toString() {
            return "OpenMenu" + Arrays.toString(path);
        }
    }

    /**
     * Open a parameter-value picker sub-page for the parameter {@code paramName}
     * declared on the {@code TreeCommand} (commands-api) reached by
     * {@code parentPath}. The picker page lists the parameter's
     * suggested values (sourced from
     * {@code CommandParameter.relevantValues(senderId)} server-side); clicking
     * a value executes the assembled command with {@code paramName:value}
     * appended, while a "type a value" row falls back to {@link SuggestInput}
     * for unsuggested input.
     *
     * <p>Resolved server-side by {@code MenuRedeemSubcommand}; never re-enters
     * the commands-api parser. See Stage A.2 of
     * {@code CHECKLIST-menu-navigation.md} and ADR-044.
     */
    record OpenParamPicker(String[] parentPath, String paramName) implements MenuAction {
        public OpenParamPicker {
            Objects.requireNonNull(parentPath, "parentPath");
            Objects.requireNonNull(paramName, "paramName");
            parentPath = parentPath.clone();
            for (int i = 0; i < parentPath.length; i++) {
                Objects.requireNonNull(parentPath[i], "parentPath[" + i + "]");
            }
            if (paramName.isEmpty()) {
                throw new IllegalArgumentException("paramName must not be empty");
            }
        }

        @Override
        public String[] parentPath() {
            return parentPath.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof OpenParamPicker other
                    && paramName.equals(other.paramName)
                    && Arrays.equals(parentPath, other.parentPath);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(parentPath) + paramName.hashCode();
        }

        @Override
        public String toString() {
            return "OpenParamPicker[" + paramName + "@" + Arrays.toString(parentPath) + "]";
        }
    }

    /** Switch to {@code pageIndex} within the current {@link MenuModel}. Zero-based. */
    record ChangePage(int pageIndex) implements MenuAction {
        public ChangePage {
            if (pageIndex < 0) {
                throw new IllegalArgumentException("pageIndex must be >= 0, got " + pageIndex);
            }
        }
    }

    /** Pre-fill the player's chat input with {@code prefix} (no auto-send). */
    record SuggestInput(String prefix) implements MenuAction {
        public SuggestInput {
            Objects.requireNonNull(prefix, "prefix");
        }
    }

    /** Open an external URL on the client. */
    record OpenExternalUrl(URI uri) implements MenuAction {
        public OpenExternalUrl {
            Objects.requireNonNull(uri, "uri");
        }
    }
}
