package io.github.dailystruggle.rtp.api.menu;

import java.net.URI;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

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
 *   <li>{@link PromptAnvilInput} — on supported platforms (Paper/Folia), open
 *       an anvil GUI so the player can type a free-form value into the rename
 *       field without leaving the menu flow. Falls back to {@link SuggestInput}
 *       semantics on renderers that do not support anvil input (ADR-045).</li>
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
                MenuAction.PromptAnvilInput,
                MenuAction.OpenExternalUrl,
                MenuAction.OpenConfigSelector,
                MenuAction.OpenConfigFile,
                MenuAction.OpenConfigKey,
                MenuAction.OpenConfigSearchPrompt,
                MenuAction.OpenConfigSearchResults,
                MenuAction.OpenConfigSubParamPage,
                MenuAction.OpenAdminPanel,
                MenuAction.OpenFrontPage,
                MenuAction.OpenInfo,
                MenuAction.SwitchInfoToText,
                MenuAction.StageConfigValue,
                MenuAction.UnstageConfigValue,
                MenuAction.ApplyStagedConfig,
                MenuAction.DiscardStagedConfig,
                MenuAction.OpenMap,
                MenuAction.OpenMultiConfigSelector,
                MenuAction.OpenMultiConfigEntry,
                MenuAction.MultiConfigMutate {

    /**
     * Discriminator for {@link PromptAnvilInput}: controls what the platform
     * anvil renderer does with the typed value on confirm.
     *
     * <ul>
     *   <li>{@link #RUN} (legacy default) - synthesize and execute
     *       {@code /rtp <parentPath...> <paramName>=<typed>} as the player.
     *       Used by the parameter-picker free-form "type a value" row.</li>
     *   <li>{@link #STAGE} - do not execute; instead the typed value is
     *       routed into the player's config-menu staging cart on this
     *       backend so the curated {@code /rtp config <file>} page can
     *       surface it under "Pending changes" and an explicit Apply row
     *       runs the batched command. The renderer reads {@code parentPath}
     *       as {@code ["config", "<fileName>", ...]}; {@code paramName} is
     *       the staged key. See the config staging-cart redesign tracked
     *       at {@code docs/dev/scratch/CHECKLIST-config-staging-cart.md}.</li>
     * </ul>
     */
    enum Mode {
        RUN,
        STAGE
    }

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

    /**
     * Open an anvil GUI on the clicking player so they can type a free-form
     * value into the anvil rename field. On confirmation (clicking the right
     * output slot) the platform synthesizes
     * {@code /rtp <parentPath...> <paramName>:<typed>} as the player and runs
     * it through the normal command pipeline. Closing the inventory without
     * confirming cancels the input.
     *
     * <p>{@code parentPath} is the args from the {@code /rtp} root down to (and
     * including) the parent {@code TreeCommand} node that owns the parameter,
     * matching the convention used by {@link OpenParamPicker#parentPath()}.
     * {@code prefill} is the initial text shown in the anvil's left slot item
     * name; empty string is allowed.
     *
     * <p>Resolved entirely on the renderer side (Paper/Folia); never enters
     * the {@code MenuRedeemSubcommand} dispatch path. Renderers that cannot
     * open an anvil GUI (Spigot without Adventure, Fabric) shall fall back to
     * the {@link SuggestInput} chat-prefill behavior. See ADR-045.
     */
    record PromptAnvilInput(String[] parentPath, String paramName, String prefill, Mode mode) implements MenuAction {
        public PromptAnvilInput {
            Objects.requireNonNull(parentPath, "parentPath");
            Objects.requireNonNull(paramName, "paramName");
            Objects.requireNonNull(prefill, "prefill");
            Objects.requireNonNull(mode, "mode");
            parentPath = parentPath.clone();
            for (int i = 0; i < parentPath.length; i++) {
                Objects.requireNonNull(parentPath[i], "parentPath[" + i + "]");
            }
            if (paramName.isEmpty()) {
                throw new IllegalArgumentException("paramName must not be empty");
            }
        }

        /**
         * Back-compatible 3-arg constructor: defaults {@link #mode()} to
         * {@link Mode#RUN}, matching the historical behavior where every
         * anvil confirm synthesized and executed
         * {@code /rtp <parentPath...> <paramName>=<typed>}. New STAGE-mode
         * call sites (config-key clicks routed through the staging cart)
         * shall use the 4-arg form explicitly.
         */
        public PromptAnvilInput(String[] parentPath, String paramName, String prefill) {
            this(parentPath, paramName, prefill, Mode.RUN);
        }

        @Override
        public String[] parentPath() {
            return parentPath.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PromptAnvilInput other
                    && mode == other.mode
                    && paramName.equals(other.paramName)
                    && prefill.equals(other.prefill)
                    && Arrays.equals(parentPath, other.parentPath);
        }

        @Override
        public int hashCode() {
            int h = Arrays.hashCode(parentPath);
            h = 31 * h + paramName.hashCode();
            h = 31 * h + prefill.hashCode();
            h = 31 * h + mode.hashCode();
            return h;
        }

        @Override
        public String toString() {
            return "PromptAnvilInput[" + mode + " " + paramName + "=" + prefill
                    + "@" + Arrays.toString(parentPath) + "]";
        }
    }

    /** Open an external URL on the client. */
    record OpenExternalUrl(URI uri) implements MenuAction {
        public OpenExternalUrl {
            Objects.requireNonNull(uri, "uri");
        }
    }

    /**
     * Open the curated config-selector page (page 1 of the 3-page config
     * subtree: selector -> per-file key list -> per-key picker).
     * Server-resolved by {@code MenuRedeemSubcommand.dispatchOpenConfigSelector};
     * rows list every known config file. Permission-gated by
     * {@code rtp.config.view}. See PROPOSAL-config-view-as-book.md v3.7.
     */
    record OpenConfigSelector() implements MenuAction {
    }

    /**
     * Open the per-file config page (page 2) for the named config file.
     * Rows enumerate the file's typed {@code CommandParameter} keys; each row
     * descends into {@link OpenConfigKey} for its key. Server-resolved by
     * {@code MenuRedeemSubcommand.dispatchOpenConfigFile}; permission-gated by
     * {@code rtp.config.view}. See PROPOSAL-config-view-as-book.md v3.7.
     */
    record OpenConfigFile(String fileName) implements MenuAction {
        public OpenConfigFile {
            Objects.requireNonNull(fileName, "fileName");
            if (fileName.isEmpty()) {
                throw new IllegalArgumentException("fileName must not be empty");
            }
        }
    }

    /**
     * Open the per-key picker page (page 3) for {@code paramName} on the named
     * config file. Delegates to the existing {@code buildParamPicker} flow over
     * the typed {@code CommandParameter}; shape/vert keys expand to a two-step
     * type-then-sub-param page (stateless writes per Q13).
     * Server-resolved by {@code MenuRedeemSubcommand.dispatchOpenConfigKey};
     * permission-gated by {@code rtp.config.view}. See
     * PROPOSAL-config-view-as-book.md v3.7.
     */
    record OpenConfigKey(String fileName, String paramName) implements MenuAction {
        public OpenConfigKey {
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(paramName, "paramName");
            if (fileName.isEmpty()) {
                throw new IllegalArgumentException("fileName must not be empty");
            }
            if (paramName.isEmpty()) {
                throw new IllegalArgumentException("paramName must not be empty");
            }
        }
    }

    /**
     * Open the anvil-input prompt for the cross-config search flow. Resolved
     * renderer-side by translating to
     * {@link PromptAnvilInput}{@code (["menu","config","search"], "query", "")};
     * on submit, the synthesized {@code /rtp menu config search query:<text>}
     * command is parsed by the {@code search} {@code TreeCommand} leaf under
     * the {@code menu config} branch, which routes to the same builder that
     * {@link OpenConfigSearchResults} uses. Permission-gated by
     * {@code rtp.config.view}. See
     * docs/dev/scratch/PROPOSAL-rtp-menu-config-search.md §6 Q4 Decision (A).
     */
    record OpenConfigSearchPrompt() implements MenuAction {
    }

    /**
     * Open page {@code page} (0-indexed) of cross-config search results for
     * {@code query}. Server-resolved by
     * {@code MenuRedeemSubcommand.dispatchOpenConfigSearchResults}; walks every
     * loaded {@code ConfigParser} and {@code MultiConfigParser}, color-strips
     * keys and values via {@code LegacyColorStrip}, performs a case-insensitive
     * substring match, and renders one row per hit with the raw (literal,
     * un-rendered) value text and an off-blue highlight projected onto the
     * matched substring. Each row carries an {@link OpenConfigKey} action so
     * clicking jumps to the per-key page that already manages that file/key.
     * Permission-gated by {@code rtp.config.view}. See
     * docs/dev/scratch/PROPOSAL-rtp-menu-config-search.md.
     */
    record OpenConfigSearchResults(String query, int page) implements MenuAction {
        public OpenConfigSearchResults {
            Objects.requireNonNull(query, "query");
            if (page < 0) {
                throw new IllegalArgumentException("page must be >= 0");
            }
        }
    }

    /**
     * Open the sub-parameter page (page 3b) for a shape/vert-typed config key
     * whose currently-stored discriminator is {@code typeName} (e.g.
     * {@code "SQUARE"} for a {@code shape} key). The page renders the activated
     * type's {@code name} plus its sub-parameters as clickable rows; writes are
     * stateless and target flat keys per Q13 (the parser's stored type
     * discriminates). Server-resolved by
     * {@code MenuRedeemSubcommand.dispatchOpenConfigSubParamPage};
     * permission-gated by {@code rtp.config.view}. See
     * PROPOSAL-config-view-as-book.md v3.7.5.
     */
    record OpenConfigSubParamPage(String fileName, String paramName, String typeName) implements MenuAction {
        public OpenConfigSubParamPage {
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(paramName, "paramName");
            Objects.requireNonNull(typeName, "typeName");
            if (fileName.isEmpty()) {
                throw new IllegalArgumentException("fileName must not be empty");
            }
            if (paramName.isEmpty()) {
                throw new IllegalArgumentException("paramName must not be empty");
            }
            if (typeName.isEmpty()) {
                throw new IllegalArgumentException("typeName must not be empty");
            }
        }
    }

    /**
     * Open the curated admin panel page. Server-resolved by
     * {@code MenuRedeemSubcommand.dispatchOpenAdminPanel}; permission-gated by
     * {@code rtp.menu.admin}. The panel hosts operator-facing rows (config
     * editor, diagnostics, lifecycle, browse) previously inlined onto the
     * front page. See PROPOSAL-admin-panel.md v2.
     */
    record OpenAdminPanel() implements MenuAction {
    }

    /**
     * Open the curated front page built by {@code FrontPageBuilder}. Used by
     * the admin panel's back row and wherever a curated-front-page return is
     * needed. Distinct from {@link OpenMenu} with an empty path, which targets
     * the reflected {@code TreeCommand} root rather than the curated builder.
     * Server-resolved by {@code MenuRedeemSubcommand.dispatchOpenFrontPage};
     * no permission gate (the front page is the default landing for any menu
     * viewer). See PROPOSAL-admin-panel.md v2.
     */
    record OpenFrontPage() implements MenuAction {
    }

    /**
     * Identifier for the scope of an {@link OpenInfo} or
     * {@link SwitchInfoToText} action (PROPOSAL-info-as-book.md §4.4).
     *
     * <ul>
     *   <li>{@link Kind#GLOBAL} — render the unscoped {@code /rtp info}
     *       output. {@link #name()} must be {@code ""}.</li>
     *   <li>{@link Kind#WORLD} — render the {@code worldInfo} block for a
     *       specific world. {@link #name()} carries the world name.</li>
     *   <li>{@link Kind#REGION} — render the {@code regionInfo} block for
     *       a specific region. {@link #name()} carries the region name.</li>
     * </ul>
     *
     * <p>{@code name} is never {@code null}; for {@link Kind#GLOBAL} it is
     * the empty string (validated in the canonical constructor).
     */
    record InfoScopeToken(Kind kind, String name) {
        public enum Kind { GLOBAL, WORLD, REGION }

        public InfoScopeToken {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(name, "name");
            if (kind == Kind.GLOBAL && !name.isEmpty()) {
                throw new IllegalArgumentException("GLOBAL scope must have empty name");
            }
            if (kind != Kind.GLOBAL && name.isEmpty()) {
                throw new IllegalArgumentException(kind + " scope requires non-empty name");
            }
        }

        /** Convenience: the unscoped {@code /rtp info} target. */
        public static InfoScopeToken global() {
            return new InfoScopeToken(Kind.GLOBAL, "");
        }

        /** Convenience: a {@code /rtp info world:<name>} target. */
        public static InfoScopeToken world(String name) {
            return new InfoScopeToken(Kind.WORLD, Objects.requireNonNull(name, "name"));
        }

        /** Convenience: a {@code /rtp info region:<name>} target. */
        public static InfoScopeToken region(String name) {
            return new InfoScopeToken(Kind.REGION, Objects.requireNonNull(name, "name"));
        }
    }

    /**
     * Open (or re-open) the {@code /rtp info} book at the given scope
     * (PROPOSAL-info-as-book.md §4.4). Server-resolved by
     * {@code MenuRedeemSubcommand.dispatchOpenInfo}; permission-gated by
     * {@code rtp.info}. Used by the {@code 🔄 Refresh} row and by per-world
     * / per-region drill-down rows inside the info book.
     */
    record OpenInfo(InfoScopeToken scope) implements MenuAction {
        public OpenInfo {
            Objects.requireNonNull(scope, "scope");
        }
    }

    /**
     * Switch the {@code /rtp info} presentation from book to chat for the
     * remainder of the current invocation (PROPOSAL-info-as-book.md §4.4).
     * Server-resolved by {@code MenuRedeemSubcommand.dispatchSwitchInfoToText};
     * permission-gated by {@code rtp.info}. The chat path is invoked with
     * the supplied scope; no new book token is minted.
     *
     * <p>The session-only viewer pref flip described in the proposal is
     * deferred to v2 (the minimal cut keeps presentation per-invocation,
     * not per-viewer). See {@code PROPOSAL-info-as-book.md} §9 "Notes".
     */
    record SwitchInfoToText(InfoScopeToken scope) implements MenuAction {
        public SwitchInfoToText {
            Objects.requireNonNull(scope, "scope");
        }
    }

    /**
     * Stage a single {@code key=value} edit into the player's per-backend
     * config-menu staging cart for {@code fileName}. The cart is scoped to
     * one config file at a time: dispatching {@code StageConfigValue} for a
     * {@code fileName} that does not match the player's existing cart file
     * replaces the cart entirely. The cart is dropped when the player
     * returns to the file-selector page (via {@link OpenConfigSelector}) or
     * disconnects; it is never persisted across sessions or backends.
     *
     * <p>Server-resolved by
     * {@code MenuRedeemSubcommand.dispatchStageConfigValue}; permission-gated
     * by {@code rtp.config.view}. Re-renders the curated
     * {@link OpenConfigFile} page so the staged entry surfaces under the
     * "Pending changes" list. See
     * {@code docs/dev/scratch/CHECKLIST-config-staging-cart.md}.
     */
    record StageConfigValue(String fileName, String paramName, String value) implements MenuAction {
        public StageConfigValue {
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(paramName, "paramName");
            Objects.requireNonNull(value, "value");
            if (fileName.isEmpty()) {
                throw new IllegalArgumentException("fileName must not be empty");
            }
            if (paramName.isEmpty()) {
                throw new IllegalArgumentException("paramName must not be empty");
            }
        }
    }

    /**
     * Remove a previously-staged {@code paramName} from the player's cart
     * for {@code fileName}. No-op when the cart is empty, the cart is
     * scoped to a different file, or {@code paramName} is not staged.
     * Server-resolved by
     * {@code MenuRedeemSubcommand.dispatchUnstageConfigValue}; permission-
     * gated by {@code rtp.config.view}. Re-renders the curated
     * {@link OpenConfigFile} page.
     */
    record UnstageConfigValue(String fileName, String paramName) implements MenuAction {
        public UnstageConfigValue {
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(paramName, "paramName");
            if (fileName.isEmpty()) {
                throw new IllegalArgumentException("fileName must not be empty");
            }
            if (paramName.isEmpty()) {
                throw new IllegalArgumentException("paramName must not be empty");
            }
        }
    }

    /**
     * Apply the player's current staging cart for {@code fileName} as a
     * single batched command:
     * {@code /rtp config <fileName> <k1>=<v1> <k2>=<v2> ...}, then clear the
     * cart and close the menu. No-op (with a warning log) when the cart is
     * empty or scoped to a different file. Server-resolved by
     * {@code MenuRedeemSubcommand.dispatchApplyStagedConfig}; permission-
     * gated by {@code rtp.config.view}.
     */
    record ApplyStagedConfig(String fileName) implements MenuAction {
        public ApplyStagedConfig {
            Objects.requireNonNull(fileName, "fileName");
            if (fileName.isEmpty()) {
                throw new IllegalArgumentException("fileName must not be empty");
            }
        }
    }

    /**
     * Clear the player's current staging cart for {@code fileName} without
     * applying any edits, then re-render the curated {@link OpenConfigFile}
     * page. No-op when the cart is empty or scoped to a different file.
     * Server-resolved by
     * {@code MenuRedeemSubcommand.dispatchDiscardStagedConfig}; permission-
     * gated by {@code rtp.config.view}.
     */
    record DiscardStagedConfig(String fileName) implements MenuAction {
        public DiscardStagedConfig {
            Objects.requireNonNull(fileName, "fileName");
            if (fileName.isEmpty()) {
                throw new IllegalArgumentException("fileName must not be empty");
            }
        }
    }

    /**
     * Open a server-side rendered cartography map for the chart described by
     * the {@code chartSpecToken} (ADR-047, REQ-RTP-MAP-006). The token is a
     * single-use, TTL-bound handle into the {@code rtp-core}
     * {@code ChartSpecTokens} registry, minted at fragment-build time and
     * consumed exactly once by
     * {@code MenuRedeemSubcommand.dispatchOpenMap}.
     *
     * <p>The renderer never sees the {@code ChartSpec} itself; the token is
     * the only authority over which chart the click paints. This mirrors the
     * ADR-035 {@code MenuTokenRegistry} security boundary: a malicious or
     * buggy renderer cannot smuggle a different chart through the click.
     *
     * <p>Server-resolved by {@code MenuRedeemSubcommand.dispatchOpenMap};
     * permission-gated by {@code rtp.admin} (matching the {@code /rtp info}
     * admin gate) and additionally gated on the installed {@code MapBinding}
     * not being the {@code NoopMapBinding} placeholder.
     */
    record OpenMap(UUID chartSpecToken) implements MenuAction {
        public OpenMap {
            Objects.requireNonNull(chartSpecToken, "chartSpecToken");
        }
    }

    /**
     * Regex applied to {@code entryName} in
     * {@link OpenMultiConfigEntry} and {@link MultiConfigMutate}. Rejects
     * YAML path traversal, slashes, whitespace, and anything that could
     * destabilise the on-disk {@code <kind>/<entryName>.yml} layout
     * managed by {@code MultiConfigParser}. Kept conservative on purpose:
     * letters, digits, underscore, dash, dot. See
     * {@code docs/dev/scratch/PROPOSAL-multiconfig-menu.md} §3.1.
     */
    String MULTICONFIG_ENTRY_NAME_REGEX = "[A-Za-z0-9_.\\-]+";

    /**
     * Open the list page for a {@code MultiConfigParser} kind (today:
     * {@code "regions"}, {@code "worlds"}; tomorrow: {@code "effects"} and
     * any further kinds). Rows enumerate the parser's existing entries
     * plus an Add row and a remove-mode toggle row; locked entries (per
     * the kind's registered {@code MultiConfigRemovalGuard}) appear as
     * grayed, non-clickable rows in remove mode. Server-resolved by
     * {@code MenuRedeemSubcommand.dispatchOpenMultiConfigSelector};
     * permission-gated by {@code rtp.config.view}.
     *
     * <p>{@code parserKind} is normalised to lowercase on construction and
     * must be non-empty. The remove-mode toggle is per-viewer state held
     * by {@code MenuRedeemSubcommand}; it is intentionally not encoded
     * into the sealed action so that a token cannot smuggle ephemeral UI
     * state across viewers. See
     * {@code docs/dev/scratch/PROPOSAL-multiconfig-menu.md} §3.1.
     */
    record OpenMultiConfigSelector(String parserKind) implements MenuAction {
        public OpenMultiConfigSelector {
            Objects.requireNonNull(parserKind, "parserKind");
            parserKind = parserKind.toLowerCase(java.util.Locale.ROOT);
            if (parserKind.isEmpty()) {
                throw new IllegalArgumentException("parserKind must not be empty");
            }
        }
    }

    /**
     * Open the per-entry staging-cart page for one entry of a
     * {@code MultiConfigParser} kind. Same UX as
     * {@link OpenConfigFile} (Changeable + Pending + Apply + Discard) but
     * scoped to {@code <kind>/<entryName>.yml}. Server-resolved by
     * {@code MenuRedeemSubcommand.dispatchOpenMultiConfigEntry};
     * permission-gated by {@code rtp.config.view}.
     *
     * <p>{@code parserKind} is normalised to lowercase on construction;
     * {@code entryName} is trimmed and validated against
     * {@link #MULTICONFIG_ENTRY_NAME_REGEX} so that a forged token cannot
     * smuggle a path-traversal or whitespace-bearing name into the
     * dispatcher. See
     * {@code docs/dev/scratch/PROPOSAL-multiconfig-menu.md} §3.1.
     */
    record OpenMultiConfigEntry(String parserKind, String entryName) implements MenuAction {
        public OpenMultiConfigEntry {
            Objects.requireNonNull(parserKind, "parserKind");
            Objects.requireNonNull(entryName, "entryName");
            parserKind = parserKind.toLowerCase(java.util.Locale.ROOT);
            entryName = entryName.trim();
            if (parserKind.isEmpty()) {
                throw new IllegalArgumentException("parserKind must not be empty");
            }
            if (entryName.isEmpty()) {
                throw new IllegalArgumentException("entryName must not be empty");
            }
            if (!entryName.matches(MULTICONFIG_ENTRY_NAME_REGEX)) {
                throw new IllegalArgumentException(
                        "entryName must match " + MULTICONFIG_ENTRY_NAME_REGEX
                                + " (got '" + entryName + "')");
            }
        }
    }

    /**
     * Server-resolved mutation against a {@code MultiConfigParser} kind:
     * either ADD a new entry (clone {@code default}, with nether/end
     * amendment) or REMOVE an existing entry (subject to the kind's
     * {@code MultiConfigRemovalGuard} re-check). Dispatched by
     * {@code MenuRedeemSubcommand.dispatchMultiConfigMutate};
     * permission-gated by {@code rtp.config.view} <i>and</i>
     * {@code rtp.config.edit}. On REMOVE the guard is re-checked
     * server-side: row suppression in the builder is UX hygiene, this
     * dispatch-side check is the actual invariant per the S-001 /
     * D-005 "never trust the client" pattern.
     *
     * <p>{@code parserKind} is normalised to lowercase on construction;
     * {@code entryName} is trimmed and validated against
     * {@link #MULTICONFIG_ENTRY_NAME_REGEX}. See
     * {@code docs/dev/scratch/PROPOSAL-multiconfig-menu.md} §3.1.
     */
    record MultiConfigMutate(String parserKind, String entryName, Op op) implements MenuAction {
        /** ADD clones {@code default}; REMOVE deletes the entry's YAML file. */
        public enum Op {
            ADD,
            REMOVE
        }

        public MultiConfigMutate {
            Objects.requireNonNull(parserKind, "parserKind");
            Objects.requireNonNull(entryName, "entryName");
            Objects.requireNonNull(op, "op");
            parserKind = parserKind.toLowerCase(java.util.Locale.ROOT);
            entryName = entryName.trim();
            if (parserKind.isEmpty()) {
                throw new IllegalArgumentException("parserKind must not be empty");
            }
            if (entryName.isEmpty()) {
                throw new IllegalArgumentException("entryName must not be empty");
            }
            if (!entryName.matches(MULTICONFIG_ENTRY_NAME_REGEX)) {
                throw new IllegalArgumentException(
                        "entryName must match " + MULTICONFIG_ENTRY_NAME_REGEX
                                + " (got '" + entryName + "')");
            }
        }
    }
}
