package io.github.dailystruggle.rtp.common.effects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.mock.SyntheticSideEffects;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Shared failure handling, reload-aware effect-flag lookup, and post-teleport title/command
 * dispatch for platform effect handlers.
 *
 * @governedBy ADR-115 (Package DAG Disentanglement and Finite Decision-Preservation Hypergraph)
 * @governedBy ADR-121 (Runtime Built-In Test Suite and Mock Isolation)
 * @trace REQ-CORE-ARCH-003
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
@SuppressWarnings("JavadocDeclaration") // ADR traceability tags
public final class EffectDispatch {
    private EffectDispatch() {}

    /** Resolved post-teleport title, subtitle, timing, and actionbar payload from {@code messages.yml}. */
    public record PostTeleportTitle(
            String title,
            String subtitle,
            int fadeIn,
            int stay,
            int fadeOut,
            String actionbar) {
        public boolean isEmpty() {
            return (title == null || title.isEmpty())
                    && (subtitle == null || subtitle.isEmpty())
                    && (actionbar == null || actionbar.isEmpty());
        }
    }

    /** Resolved post-teleport console and player commands from {@code config.yml}. */
    public record PostTeleportCommands(
            List<String> consoleCommands,
            List<String> playerCommands) {
        public boolean isEmpty() {
            return (consoleCommands == null || consoleCommands.isEmpty())
                    && (playerCommands == null || playerCommands.isEmpty());
        }
    }

    /**
     * Runs each effect action independently, logging runtime failures and continuing the group.
     *
     * @param prefix lifecycle prefix identifying the effect group
     * @param effects effects to dispatch; must not be {@code null}
     * @param action per-effect operation, including target assignment and scheduling; must not be {@code null}
     * @throws NullPointerException if {@code prefix}, {@code effects}, or {@code action} is {@code null}
     */
    public static void forEachIsolated(
            String prefix, List<? extends Effect<?>> effects, Consumer<? super Effect<?>> action) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(effects, "effects");
        Objects.requireNonNull(action, "action");
        for (Effect<?> effect : effects) {
            String effectType = effect == null ? "<null>" : effect.getClass().getSimpleName();
            if (effectType.isEmpty()) effectType = effect.getClass().getName();
            try {
                action.accept(effect);
            } catch (RuntimeException failure) {
                RTP.log(
                        Level.WARNING,
                        "[RTP][effects] " + prefix + " dispatch failed for " + effectType
                                + ": " + failure.getMessage(),
                        failure);
            }
        }
    }

    /** Installs the RTP logger as the warning sink used by {@link Effect}. */
    public static void installWarnSink() {
        Effect.setDefaultWarn(message -> RTP.log(Level.WARNING, "[RTP][effects-api] " + message));
    }

    /**
     * Extracts the pipeline stage token (e.g. {@code "postteleport"}) from a permission prefix
     * of the shape {@code "rtp.effect.<stage>"}.
     */
    public static String stageOf(String prefix) {
        if (prefix == null) return "";
        int dot = prefix.lastIndexOf('.');
        return (dot >= 0 && dot < prefix.length() - 1) ? prefix.substring(dot + 1) : prefix;
    }

    /**
     * Checks whether {@link PerformanceKeys#effectParsing} is enabled, consulting
     * {@link ActiveConfigs#get()} first so {@code /rtp reload} takes effect immediately
     * and falling back to {@code fallbackParser} when active configs are absent.
     */
    public static boolean isEffectParsingEnabled(FactoryValue<PerformanceKeys> fallbackParser) {
        Configs configs = ActiveConfigs.get();
        FactoryValue<PerformanceKeys> active =
                (configs != null) ? configs.getParser(PerformanceKeys.class) : fallbackParser;
        if (active == null) {
            active = fallbackParser;
        }
        if (active == null) return false;
        Object val = active.getData().get(PerformanceKeys.effectParsing);
        if (val instanceof Boolean b) return b;
        if (val == null) return false;
        boolean parsed = Boolean.parseBoolean(val.toString());
        active.set(PerformanceKeys.effectParsing, parsed);
        return parsed;
    }

    /**
     * Resolves configured post-teleport title/subtitle/actionbar values for {@code task},
     * returning {@code null} when the task/player is absent, the player is a synthetic BIT mock
     * ({@link SyntheticSideEffects#publishesEvents}), or {@code messages.yml} is unavailable.
     */
    @SuppressWarnings("unchecked")
    public static PostTeleportTitle resolvePostTeleportTitle(TeleportPipelineTask task) {
        if (task == null || task.player() == null) return null;
        if (!SyntheticSideEffects.publishesEvents(task.player())) {
            RTPRuntime.log(
                    Level.FINER,
                    "[RTP] skipping post-teleport title for synthetic player " + task.player().uuid());
            return null;
        }
        Configs configs = ActiveConfigs.get();
        if (configs == null) return null;
        ConfigParser<PlayerMessages> lang =
                (ConfigParser<PlayerMessages>) configs.getParser(PlayerMessages.class);
        if (lang == null) return null;
        String title = String.valueOf(configs.getConfigValue(PlayerMessages.title, ""));
        String subtitle = String.valueOf(configs.getConfigValue(PlayerMessages.subtitle, ""));
        int fadeIn = lang.getNumber(PlayerMessages.fadeIn, 0).intValue();
        int stay = lang.getNumber(PlayerMessages.stay, 0).intValue();
        int fadeOut = lang.getNumber(PlayerMessages.fadeOut, 0).intValue();
        String actionbar = String.valueOf(configs.getConfigValue(PlayerMessages.actionbar, ""));
        return new PostTeleportTitle(title, subtitle, fadeIn, stay, fadeOut, actionbar);
    }

    /**
     * Resolves configured {@code consoleCommands} and {@code playerCommands} for {@code task},
     * substituting {@code [player]} and skipping synthetic BIT mock players (ADR-121).
     */
    @SuppressWarnings("unchecked")
    public static PostTeleportCommands resolvePostTeleportCommands(
            TeleportPipelineTask task, String playerNameOverride, boolean stripLeadingSlash) {
        if (task == null || task.player() == null) return null;
        if (!SyntheticSideEffects.publishesEvents(task.player())) {
            RTPRuntime.log(
                    Level.FINER,
                    "[RTP] skipping post-teleport commands for synthetic player " + task.player().uuid());
            return null;
        }
        String playerName = (playerNameOverride != null && !playerNameOverride.isBlank())
                ? playerNameOverride
                : task.player().name();
        if (playerName == null || playerName.isBlank()) return null;

        Configs configs = ActiveConfigs.get();
        if (configs == null) return null;
        ConfigParser<ConfigKeys> configParser =
                (ConfigParser<ConfigKeys>) configs.getParser(ConfigKeys.class);
        if (configParser == null) return null;

        List<String> consoleCommandsToRun = new ArrayList<>();
        Object consoleObj = configParser.getConfigValue(ConfigKeys.consoleCommands, new ArrayList<>());
        if (consoleObj instanceof List<?> consoleList) {
            for (Object cmd : consoleList) {
                if (cmd == null) continue;
                String c = cmd.toString().replace("[player]", playerName);
                if (c.isBlank()) continue;
                if (stripLeadingSlash && c.startsWith("/")) c = c.substring(1);
                if (!c.isBlank()) consoleCommandsToRun.add(c);
            }
        }

        List<String> playerCommandsToRun = new ArrayList<>();
        Object playerObj = configParser.getConfigValue(ConfigKeys.playerCommands, new ArrayList<>());
        if (playerObj instanceof List<?> playerList) {
            for (Object cmd : playerList) {
                if (cmd == null) continue;
                String c = cmd.toString().replace("[player]", playerName);
                if (c.isBlank()) continue;
                if (stripLeadingSlash && c.startsWith("/")) c = c.substring(1);
                if (!c.isBlank()) playerCommandsToRun.add(c);
            }
        }

        PostTeleportCommands resolved =
                new PostTeleportCommands(List.copyOf(consoleCommandsToRun), List.copyOf(playerCommandsToRun));
        return resolved.isEmpty() ? null : resolved;
    }

    /**
     * Resolves and dispatches post-teleport title, subtitle, and actionbar to {@code task.player()}
     * on the main/global scheduler via {@link RTPPlayer#sendTitle} and {@link RTPPlayer#sendActionbar}.
     */
    public static void dispatchPostTeleportTitle(TeleportPipelineTask task) {
        PostTeleportTitle titleSpec = resolvePostTeleportTitle(task);
        if (titleSpec == null || titleSpec.isEmpty() || RTPRuntime.scheduler() == null) return;
        final RTPPlayer playerRef = task.player();
        final String title = titleSpec.title() == null ? "" : titleSpec.title();
        final String subtitle = titleSpec.subtitle() == null ? "" : titleSpec.subtitle();
        final String actionbar = titleSpec.actionbar() == null ? "" : titleSpec.actionbar();
        RTPRuntime.log(Level.FINER, "[RTP] scheduling post-teleport title for player " + playerRef.uuid());
        RTPRuntime.scheduler().runTask(() -> {
            if (!title.isEmpty() || !subtitle.isEmpty()) {
                playerRef.sendTitle(
                        title,
                        subtitle,
                        titleSpec.fadeIn(),
                        titleSpec.stay(),
                        titleSpec.fadeOut());
            }
            if (!actionbar.isEmpty()) {
                playerRef.sendActionbar(actionbar);
            }
        });
    }

    /**
     * Resolves and dispatches post-teleport {@code consoleCommands} and {@code playerCommands}
     * on the main/global scheduler via {@link RTPCommandSender#performCommand}.
     */
    public static void dispatchPostTeleportCommands(TeleportPipelineTask task) {
        PostTeleportCommands commands = resolvePostTeleportCommands(task, null, true);
        if (commands == null || RTPRuntime.scheduler() == null) return;
        final RTPPlayer playerRef = task.player();
        RTPRuntime.scheduler().runTask(() -> {
            if (!commands.consoleCommands().isEmpty() && RTPRuntime.serverAccessor() != null) {
                RTPCommandSender console = RTPRuntime.serverAccessor().getSender(RTPAPI.serverId);
                if (console != null) {
                    for (String c : commands.consoleCommands()) {
                        console.performCommand(playerRef, c);
                    }
                }
            }
            for (String c : commands.playerCommands()) {
                playerRef.performCommand(playerRef, c);
            }
        });
    }
}
