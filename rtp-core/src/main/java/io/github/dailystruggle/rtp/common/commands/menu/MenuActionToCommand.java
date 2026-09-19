package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Translates a {@link MenuAction} into its concrete command wire form.
 * Single source of truth for menu action mapping shared across renderers.
 */
public final class MenuActionToCommand {

    private MenuActionToCommand() {}

    /**
     * Returns the literal {@code /rtp ...} command to dispatch on click for
     * the given {@link MenuAction}, or {@code null} for renderer-only
     * variants that do not map to a server-side command (see class Javadoc).
     *
     * @param action the menu action to convert; {@code null} returns {@code null}
     * @return the literal run-command string, or {@code null} for renderer-only actions
     */
    public static @Nullable String toRunCommand(@Nullable MenuAction action) {
        if (action == null) return null;
        return switch (action) {
            case MenuAction.RunRtpCommand run -> buildRunCommand(run);
            // Empty path = root; omit `path=` entirely because the
            // commands-api TreeCommand parameter parser rejects any
            // argument that ends in `=` (empty value) before the leaf's
            // onCommand runs (TreeCommand.java:224).
            case MenuAction.OpenMenu open ->
                    "/rtp menu open" + pathArg(open.path());
            case MenuAction.OpenParamPicker picker ->
                    "/rtp menu picker"
                            + pathArg(picker.parentPath())
                            + " param=" + picker.paramName();
            case MenuAction.PromptAnvilInput prompt -> buildAnvilCommand(prompt);
            // Renderer-only variants: no concrete command. ChangePage is
            // handled by the book renderer's Adventure changePage(int) click
            // type; the chat renderer translates it to /rtp menu page n=...
            // via a separate call to changePageCommand(int) below.
            case MenuAction.ChangePage ignored -> null;
            case MenuAction.SuggestInput ignored -> null;
            case MenuAction.OpenExternalUrl ignored -> null;
            case MenuAction.OpenConfigSelector sel ->
                    sel.subDir().isEmpty()
                            ? "/rtp menu config"
                            : "/rtp menu config dir=" + sel.subDir();
            case MenuAction.OpenConfigFile f ->
                    "/rtp menu config file=" + f.fileName();
            case MenuAction.OpenConfigKey k ->
                    "/rtp menu config"
                            + " file=" + k.fileName()
                            + " key=" + k.paramName();
            // No dedicated leaf in core today; ConfigCmd's parameter grammar
            // discards an unknown `type=` token and falls through to the key
            // page, which is the existing dispatch behaviour pre-ADR-050.
            case MenuAction.OpenConfigSubParamPage sp ->
                    "/rtp menu config"
                            + " file=" + sp.fileName()
                            + " key=" + sp.paramName()
                            + " type=" + sp.typeName();
            case MenuAction.OpenConfigSearchPrompt ignored ->
                    "/rtp menu config search";
            // ConfigSearchCmd accepts 1-indexed `page=`; record stores
            // 0-indexed page, so +1 on emit.
            case MenuAction.OpenConfigSearchResults sr ->
                    "/rtp menu config search"
                            + " query=" + sr.query()
                            + " page=" + (sr.page() + 1);
            case MenuAction.OpenAdminPanel ignored -> "/rtp menu admin";
            case MenuAction.OpenVisualizations ignored -> "/rtp menu visualizations";
            case MenuAction.OpenFrontPage ignored -> "/rtp menu front";
            case MenuAction.OpenInfo info ->
                    "/rtp menu info scope=" + scopeWire(info.scope());
            case MenuAction.SwitchInfoToText sw ->
                    "/rtp menu info"
                            + " scope=" + scopeWire(sw.scope())
                            + " text=true";
            case MenuAction.StageConfigValue st ->
                    "/rtp menu stage"
                            + " file=" + st.fileName()
                            + " key=" + st.paramName()
                            + " value=" + st.value();
            case MenuAction.UnstageConfigValue u ->
                    "/rtp menu unstage"
                            + " file=" + u.fileName()
                            + " key=" + u.paramName();
            case MenuAction.ApplyStagedConfig ap ->
                    "/rtp menu apply file=" + ap.fileName();
            case MenuAction.DiscardStagedConfig d ->
                    "/rtp menu discard file=" + d.fileName();
            case MenuAction.OpenMultiConfigSelector ms ->
                    "/rtp menu multi kind=" + ms.parserKind();
            case MenuAction.OpenMultiConfigEntry me ->
                    "/rtp menu multi"
                            + " kind=" + me.parserKind()
                            + " entry=" + me.entryName();
            case MenuAction.MultiConfigMutate mm ->
                    "/rtp menu multi"
                            + " kind=" + mm.parserKind()
                            + " entry=" + mm.entryName()
                            + " op=" + mm.op().name().toLowerCase(Locale.ROOT);
            // OpenMap dispatches to typed sub-command under /rtp visualization (ADR-050).
            case MenuAction.OpenMap openMap ->
                    "/rtp visualization " + visualizationKindLiteral(openMap.kind())
                            + " region=" + openMap.regionName();
        };
    }

    /**
     * Maps {@link MenuAction.ChangePage} to 1-indexed {@code /rtp menu page n=<n>}.
     *
     * @param change change-page action; never {@code null}
     * @return literal {@code /rtp menu page n=<n>} command
     */
    public static String changePageCommand(MenuAction.ChangePage change) {
        // 0-based -> 1-based wire form.
        return "/rtp menu page n=" + (change.pageIndex() + 1);
    }

    /**
     * Encodes a {@link MenuAction.RunRtpCommand} as a literal {@code /rtp}
     * invocation. The args are joined with single spaces; empty args degrade
     * to bare {@code /rtp}.
     *
     * @param run the run-command action; never {@code null}
     * @return the literal {@code /rtp ...} command string
     */
    public static String buildRunCommand(MenuAction.RunRtpCommand run) {
        String[] args = run.args();
        if (args == null || args.length == 0) return "/rtp";
        StringBuilder sb = new StringBuilder("/rtp");
        for (String arg : args) {
            sb.append(' ').append(arg);
        }
        return sb.toString();
    }

    /**
     * Encodes a {@link MenuAction.PromptAnvilInput} as the concrete
     * {@code /rtp menu anvil ...} command consumed by
     * {@code MenuConcreteCommandLeavesB.AnvilCmd}.
     *
     * @param prompt the anvil-input action; never {@code null}
     * @return the literal {@code /rtp menu anvil ...} command string
     */
    public static String buildAnvilCommand(MenuAction.PromptAnvilInput prompt) {
        StringBuilder sb = new StringBuilder("/rtp menu anvil")
                .append(pathArg(prompt.parentPath()))
                .append(" param=").append(prompt.paramName());
        String prefill = prompt.prefill();
        if (prefill != null && !prefill.isEmpty()) {
            sb.append(" prefill=").append(prefill);
        }
        sb.append(" mode=")
                .append(prompt.mode().name().toLowerCase(Locale.ROOT));
        return sb.toString();
    }

    /**
     * Joins a path array as a dot-separated string for the {@code path=}
     * parameter of the open / picker / anvil leaves. Empty arrays return
     * the empty string (the leaves treat an empty path as root).
     *
     * @param path the path segments; {@code null} or empty returns the empty string
     * @return dot-joined path string, or the empty string when path is null/empty
     */
    public static String dotted(String[] path) {
        if (path == null || path.length == 0) return "";
        StringBuilder sb = new StringBuilder(path[0]);
        for (int i = 1; i < path.length; i++) {
            sb.append('.').append(path[i]);
        }
        return sb.toString();
    }

    /**
     * Returns {@code " path=<dotted>"} or empty string if path is empty/null.
     * Prevents generating trailing {@code path=} arguments rejected by parser.
     *
     * @param path path segments
     * @return {@code " path=<dotted>"} or empty string
     */
    public static String pathArg(String[] path) {
        String d = dotted(path);
        return d.isEmpty() ? "" : " path=" + d;
    }

    /**
     * Encodes {@link MenuAction.InfoScopeToken} for the {@code scope=} parameter of {@code /rtp menu info}.
     *
     * @param scope scope token to encode; never {@code null}
     * @return wire-form scope string
     */
    public static String scopeWire(MenuAction.InfoScopeToken scope) {
        return switch (scope.kind()) {
            case GLOBAL -> "global";
            case WORLD -> "world:" + scope.name();
            case REGION -> "region:" + scope.name();
        };
    }

    /**
     * Maps a {@link io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind} to
     * its {@code /rtp visualization <literal>} sub-command word. Only
     * {@code REGION_BAD_LOCATIONS_SHAPE} currently has a registered leaf
     * ({@code bad-locations}); the other Kinds emit best-guess literals
     * matching their reserved sub-command names so click events stay
     * stable when those leaves land in Stage 3.
     */
    static String visualizationKindLiteral(
            io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind kind) {
        return switch (kind) {
            case REGION_BAD_LOCATIONS_SHAPE -> "bad-locations";
            case REGION_BIOMES             -> "biomes";
            case BAD_POINTS_HEATMAP        -> "bad-points-heatmap";
            case REGION_COVERAGE           -> "coverage";
            case FAIL_RATE_HEATMAP         -> "fail-rate";
            case CACHE_OCCUPANCY           -> "cache";
            case METRIC_SPARKLINE          -> "sparkline";
        };
    }
}
