package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /rtp admin setup next}
 * Advances the Setup Wizard to the next stage.
 */
public class SetupNextCmd extends AbstractSetupStepCmd {

    public SetupNextCmd(
            @Nullable CommandsAPICommand parent,
            SetupSessionRegistry sessionRegistry,
            SetupBookMenuBuilder bookBuilder,
            @Nullable MenuRenderer menuRenderer) {
        super(parent, sessionRegistry, bookBuilder, menuRenderer);
    }

    @Override
    public String name() {
        return "next";
    }

    @Override
    public String description() {
        return "advance to the next setup wizard stage";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
        return updateStageAndRender(callerId, session -> session.setCurrentStage(session.currentStage().next()));
    }
}
