package io.github.dailystruggle.rtp.api.menu;

import java.net.URI;
import java.util.Arrays;
import java.util.Objects;

/**
 * Sealed action attached to a {@link MenuFragment}'s click.
 *
 * <p>Renderers translate each variant to the appropriate click event.
 * Raw command strings are not embedded directly (ADR-035).
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
                MenuAction.OpenVisualizations,
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
     * Discriminator for {@link PromptAnvilInput} confirm action:
     * {@link #RUN} executes {@code /rtp <parentPath...> <paramName>=<typed>},
     * {@link #STAGE} routes into config staging.
     */
    enum Mode {
        RUN,
        STAGE
    }

    /**
     * Invoke {@code /rtp <args>} as the clicking player.
     * {@code args} is the sub-command path below {@code /rtp} (no leading "rtp").
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
     * Open a menu page at the given path under {@code /rtp}.
     * Resolved server-side by {@code MenuRedeemSubcommand}. Empty path = root.
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
     * Open parameter-value picker sub-page for {@code paramName} on the
     * command reached by {@code parentPath} (ADR-044).
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
     * Open an anvil GUI for free-form value input. On confirmation, executes
     * or stages {@code /rtp <parentPath...> <paramName>:<typed>} (ADR-045).
     * Falls back to {@link SuggestInput} on unsupported renderers.
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
         * 3-arg constructor: defaults {@link #mode()} to {@link Mode#RUN}, where
         * every anvil confirm synthesizes and executes
         * {@code /rtp <parentPath...> <paramName>=<typed>}. STAGE-mode call sites
         * (config-key clicks routed through the staging cart) shall use the 4-arg
         * form explicitly.
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
     * Open the curated config-selector directory page (ADR-071).
     * {@code subDir} is a forward-slashed relative path; empty string is root.
     * Permission-gated by {@code rtp.config.view}.
     */
    record OpenConfigSelector(String subDir) implements MenuAction {
        public OpenConfigSelector {
            // Normalize: null/blank -> root (""); strip leading/trailing
            // slashes so "advanced", "/advanced", "advanced/" all canonicalize
            // to the same forward-slashed relative path.
            if (subDir == null) {
                subDir = "";
            } else {
                subDir = subDir.replace('\\', '/').trim();
                while (subDir.startsWith("/")) subDir = subDir.substring(1);
                while (subDir.endsWith("/")) subDir = subDir.substring(0, subDir.length() - 1);
            }
        }

        /** Convenience root-directory constructor (the empty subpath). */
        public OpenConfigSelector() {
            this("");
        }
    }

    /**
     * Open the per-file config page (page 2) for the named config file.
     * Rows enumerate the file's typed {@code CommandParameter} keys; each row
     * descends into {@link OpenConfigKey} for its key. Server-resolved by
     * {@code MenuRedeemSubcommand.dispatchOpenConfigFile}; permission-gated by
     * {@code rtp.config.view}.
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
     * type-then-sub-param page (stateless writes).
     * Server-resolved by {@code MenuRedeemSubcommand.dispatchOpenConfigKey};
     * permission-gated by {@code rtp.config.view}.
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
     * Open the anvil-input prompt for the cross-config search flow.
     * Permission-gated by {@code rtp.config.view}.
     */
    record OpenConfigSearchPrompt() implements MenuAction {
    }

    /**
     * Open page {@code page} of cross-config search results for {@code query}.
     * Server-resolved by {@code MenuRedeemSubcommand.dispatchOpenConfigSearchResults}.
     * Permission-gated by {@code rtp.config.view}.
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
     * Open the sub-parameter page for a shape/vert-typed config key
     * whose stored discriminator is {@code typeName}.
     * Permission-gated by {@code rtp.config.view}.
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
     * editor, diagnostics, lifecycle, browse).
     */
    record OpenAdminPanel() implements MenuAction {
    }

    /**
     * Open the curated admin Visualizations submenu (ADR-047).
     * Permission-gated by {@code rtp.menu.admin}.
     */
    record OpenVisualizations() implements MenuAction {
    }

    /**
     * Open the curated front page.
     * Resolved server-side by {@code MenuRedeemSubcommand.dispatchOpenFrontPage}.
     */
    record OpenFrontPage() implements MenuAction {
    }

    /**
     * Identifier for the scope of an {@link OpenInfo} or {@link SwitchInfoToText} action.
     * GLOBAL: unscoped; WORLD: specific world; REGION: specific region.
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
     * Open (or re-open) the {@code /rtp info} book at the given scope.
     * Server-resolved by
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
     * Switch the {@code /rtp info} presentation from book to chat.
     * Permission-gated by {@code rtp.info}.
     */
    record SwitchInfoToText(InfoScopeToken scope) implements MenuAction {
        public SwitchInfoToText {
            Objects.requireNonNull(scope, "scope");
        }
    }

    /**
     * Stage a single {@code key=value} edit into the player's staging cart for {@code fileName}.
     * Permission-gated by {@code rtp.config.view}.
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
     * Remove a previously-staged {@code paramName} from the player's cart for {@code fileName}.
     * Permission-gated by {@code rtp.config.view}.
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
     * Apply the player's current staging cart for {@code fileName} as a batched command.
     * Permission-gated by {@code rtp.config.view}.
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
     * Clear the staging cart for {@code fileName} and re-render the file config page.
     * Permission-gated by {@code rtp.config.view}.
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
     * Open a server-side rendered cartography map for {@code kind} + {@code regionName}
     * (ADR-047, REQ-RTP-MAP-006). Permission-gated by {@code rtp.admin}.
     */
    record OpenMap(io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind kind,
                   String regionName) implements MenuAction {
        public OpenMap {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(regionName, "regionName");
        }
    }

    /**
     * Regex applied to {@code entryName} in {@link OpenMultiConfigEntry} and
     * {@link MultiConfigMutate}. Rejects path traversal and whitespace.
     */
    String MULTICONFIG_ENTRY_NAME_REGEX = "[A-Za-z0-9_.\\-]+";

    /**
     * Open the list page for a {@code MultiConfigParser} kind (ADR-071).
     * Permission-gated by {@code rtp.config.view}.
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
     * Open the per-entry staging-cart page for one entry of a {@code MultiConfigParser} kind.
     * Permission-gated by {@code rtp.config.view}.
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
     * Server-resolved mutation against a {@code MultiConfigParser} kind (ADD/REMOVE).
     * Permission-gated by {@code rtp.config.view} and {@code rtp.config.edit}.
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
