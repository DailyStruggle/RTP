package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuOpenRequest;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Dispatch surface the {@code /rtp menu} leaves and mirrors call back into (ADR-050). The leaves
 * depend on this type, not on the concrete {@code MenuRedeemSubcommand} that registers them, so
 * the registering command and its leaves do not form a class cycle.
 */
abstract class MenuDispatchTarget extends BaseRTPCmdImpl {

    /**
     * Parameter name used when the user (or a renderer's pagination click)
     * types {@code /rtp menu page:<n>}. Values are 1-indexed on the wire and
     * translated to a zero-based {@link MenuOpenRequest#pageIndex()} before
     * reaching the page builder. Default (parameter absent or unparseable) is
     * page 1 / index 0.
     */
    public static final String PARAM_PAGE = "page";

    MenuDispatchTarget(@Nullable CommandsAPICommand parent) {
        super(parent);
    }

    /**
     * Extracts the zero-based page index from the parsed {@code page} parameter.
     * Wire format is 1-indexed ({@code page:1} = first page); we subtract one
     * before returning. Missing, empty, non-numeric, or {@code < 1} values
     * collapse to {@code 0} (first page) so a malformed click degrades
     * gracefully - the parameter-level predicate already rejects clearly
     * invalid inputs at the commands-api parser, this is a defensive backstop.
     */
    static int extractPageIndex(Map<String, List<String>> parameterValues) {
        if (parameterValues == null) return 0;
        List<String> vs = parameterValues.get(PARAM_PAGE);
        if (vs == null || vs.isEmpty() || vs.get(0) == null) return 0;
        try {
            int oneBased = Integer.parseInt(vs.get(0));
            return oneBased < 1 ? 0 : oneBased - 1;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    abstract Function<UUID, Predicate<String>> permissionProbeFactory();

    abstract boolean openPage(UUID senderId, @Nullable CommandsAPICommand nextCommand, int pageIndex,
                              @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchOpen(UUID senderId, MenuAction.OpenMenu open, @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchOpenParamPicker(UUID senderId, MenuAction.OpenParamPicker picker,
                                             @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchSelectionMenu(UUID senderId, String[] parentPath, String paramName, String displayName,
                                           Function<String, String> colorSupplier, boolean executeOnClick,
                                           @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchSelectionMenu(UUID senderId, String[] parentPath, String paramName, String displayName,
                                           Function<String, String> colorSupplier, boolean executeOnClick,
                                           @Nullable MenuAction backAction, @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchPromptAnvilInput(UUID senderId, MenuAction.PromptAnvilInput prompt,
                                              @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchOpenConfigSelector(UUID senderId, @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchOpenConfigSelector(UUID senderId, MenuAction.OpenConfigSelector open,
                                                @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchOpenConfigFile(UUID senderId, MenuAction.OpenConfigFile open,
                                            @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchOpenConfigKey(UUID senderId, MenuAction.OpenConfigKey open,
                                           @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchOpenConfigSearchPrompt(UUID senderId, @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchOpenConfigSearchResults(UUID senderId, MenuAction.OpenConfigSearchResults search,
                                                     @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchOpenAdminPanel(UUID senderId, @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchOpenVisualizations(UUID senderId, @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchOpenVisualizationRegions(UUID senderId, ChartSpec.Kind kind,
                                                      @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchOpenFrontPage(UUID senderId, @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchOpenInfo(UUID senderId, MenuAction.OpenInfo action, @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchSwitchInfoToText(UUID senderId, MenuAction.SwitchInfoToText action,
                                              @Nullable Consumer<String> messageMethod);

    abstract boolean renderForPath(UUID senderId, TreeCommand target, List<String> assembledPath,
                                   Map<String, List<String>> parameterValues, int pageIndex,
                                   @Nullable Consumer<String> messageMethod);

    abstract boolean reopenAfterCartOp(UUID senderId, String fileName, @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchStageConfigValue(UUID senderId, MenuAction.StageConfigValue stage,
                                              @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchUnstageConfigValue(UUID senderId, MenuAction.UnstageConfigValue unstage,
                                                @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchApplyStagedConfig(UUID senderId, MenuAction.ApplyStagedConfig apply,
                                               @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchDiscardStagedConfig(UUID senderId, MenuAction.DiscardStagedConfig discard,
                                                 @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchOpenMultiConfigSelector(UUID senderId, MenuAction.OpenMultiConfigSelector open,
                                                     @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchOpenMultiConfigEntry(UUID senderId, MenuAction.OpenMultiConfigEntry open,
                                                  @Nullable Consumer<String> messageMethod);

    abstract boolean dispatchMultiConfigMutate(UUID senderId, MenuAction.MultiConfigMutate mutate,
                                               @Nullable Consumer<String> messageMethod);

    /** Rejects a malformed leaf input (S-004 WARN plus configurable message) before a record is built. */
    abstract void rejectMenuInvalid(@Nullable UUID senderId, String logMessage,
                                    @Nullable Consumer<String> messageMethod);
}
