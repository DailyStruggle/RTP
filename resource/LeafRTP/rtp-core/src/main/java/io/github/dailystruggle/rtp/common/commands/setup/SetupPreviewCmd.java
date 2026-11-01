package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /rtp admin setup preview}
 * Jumps to Stage 5 and computes the full dry-run diff preview before commit.
 */
public class SetupPreviewCmd extends AbstractSetupStepCmd {

    public SetupPreviewCmd(
            @Nullable CommandsAPICommand parent,
            SetupSessionRegistry sessionRegistry,
            SetupBookMenuBuilder bookBuilder,
            @Nullable MenuRenderer menuRenderer) {
        super(parent, sessionRegistry, bookBuilder, menuRenderer);
    }

    @Override
    public String name() {
        return "preview";
    }

    @Override
    public String description() {
        return "preview pending config diff before applying";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
        return updateStageAndRender(callerId, session -> session.setCurrentStage(SetupStage.PREVIEW));
    }
}
