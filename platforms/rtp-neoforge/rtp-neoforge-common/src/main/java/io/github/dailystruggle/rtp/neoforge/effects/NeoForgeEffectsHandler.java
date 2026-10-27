package io.github.dailystruggle.rtp.neoforge.effects;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.effects.EffectDispatch;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.teleport.DeathTeleports;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import io.github.dailystruggle.rtp.effects.EffectsResolver;
import io.github.dailystruggle.rtp.neoforge.effects.local.NeoForgeEffectsInitializer;
import io.github.dailystruggle.rtp.neoforge.effects.local.NeoForgeHandles;
import io.github.dailystruggle.rtp.neoforge.player.NeoForgeRTPPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import io.github.dailystruggle.rtp.common.configuration.Messages;

/**
 * NeoForge counterpart of
 * {@code io.github.dailystruggle.rtp.fabric.effects.FabricEffectsHandler}
 * (effects-api-ADR-003 / ADR-005).
 *
 * <p>NeoForge shares no code with the Fabric effects carrier (which is
 * Loom-remapped to Fabric intermediary and does not link on the Mojmap
 * NeoForge runtime). It ships its own Mojmap effect prototypes in
 * {@code io.github.dailystruggle.rtp.neoforge.effects.local.*}
 * ({@link NeoForgeEffectsInitializer}), which never type
 * {@code ResourceLocation} (renamed to {@code Identifier} in 1.21.11) and
 * dispatch every drift-prone surface reflectively. Only the platform-neutral
 * {@code effectsapi.common.*} SPI is reused.</p>
 *
 * <p>Responsibilities:</p>
 * <ol>
 *   <li>Bind the {@link MinecraftServer} into {@link NeoForgeHandles}, the
 *       effect scheduler chokepoint.</li>
 *   <li>Invoke {@link NeoForgeEffectsInitializer#registerAll()} (idempotent) to
 *       bind the NeoForge value coercer + SOUND/PARTICLE/TITLE/POTION
 *       prototypes.</li>
 *   <li>Attach the {@code rtp.effect.*} lifecycle hooks onto
 *       {@link TeleportPipelineTask} / {@link Region} / {@link RTPTeleportCancel}.</li>
 * </ol>
 *
 * <p>S-005: effect dispatch funnels through {@link NeoForgeHandles#execute}
 * ({@code MinecraftServer#execute}); effect resolution is pure in-memory parser
 * lookup, no chunk I/O.</p>
 */
public final class NeoForgeEffectsHandler {

    private NeoForgeEffectsHandler() {}

    /**
     * Wire the NeoForge effects layer to the RTP pipeline. Call from
     * {@code ServerStartedEvent} (so {@code BuiltInRegistries} is fully
     * populated and {@link NeoForgeHandles#execute} has a server to
     * dispatch onto). Idempotent: re-binds the server and registers the
     * lifecycle-hook lambdas exactly once.
     */
    public static void setupEffects(MinecraftServer server) {
        EffectDispatch.installWarnSink();
        NeoForgeHandles.setServer(server);
        NeoForgeEffectsInitializer.registerAll();

        if (!AlreadyHooked.flip()) return; // already attached on a previous start

        Configs configs = ActiveConfigs.get();
        FactoryValue<PerformanceKeys> parser = configs.getParser(PerformanceKeys.class);

        TeleportPipelineTask.setupPreActions.add(task -> {
            if (!effectParsingEnabled(parser) || task.player() == null) return;
            dispatch("rtp.effect.presetup", task.player());
        });
        TeleportPipelineTask.setupPostActions.add((task, ok) -> {
            if (!ok || !effectParsingEnabled(parser) || task.player() == null) return;
            dispatch("rtp.effect.postsetup", task.player());
        });
        TeleportPipelineTask.loadPreActions.add(task -> {
            if (!effectParsingEnabled(parser) || task.player() == null) return;
            dispatch("rtp.effect.preload", task.player());
        });
        TeleportPipelineTask.loadPostActions.add(task -> {
            if (!effectParsingEnabled(parser) || task.player() == null) return;
            dispatch("rtp.effect.postload", task.player());
        });
        TeleportPipelineTask.teleportPreActions.add(task -> {
            if (!effectParsingEnabled(parser) || task.player() == null) return;
            net.minecraft.world.phys.Vec3 originPos = null;
            if (task.player() instanceof NeoForgeRTPPlayer np && np.handle() != null) {
                originPos = np.handle().position();
            }
            net.minecraft.world.phys.Vec3 destPos = null;
            if (task.coords() != null) {
                destPos = new net.minecraft.world.phys.Vec3(task.coords().x(), task.coords().y(), task.coords().z());
            }
            dispatch("rtp.effect.preteleport", task.player(), originPos, destPos, task.coords() != null ? task.coords().worldName() : null, task);
        });
        TeleportPipelineTask.teleportPostActions.add(task -> {
            if (!effectParsingEnabled(parser) || task.player() == null) return;
            dispatch("rtp.effect.postteleport", task.player());
        });
        RTPTeleportCancel.postActions.add(cancel -> {
            if (!effectParsingEnabled(parser)) return;
            UUID uuid = cancel.getPlayerId();
            if (uuid == null) return;
            dispatchByUuid("rtp.effect.cancel", uuid);
        });
        Region.onPlayerQueuePush.add((region, uuid) -> {
            if (!effectParsingEnabled(parser) || uuid == null) return;
            dispatchByUuid("rtp.effect.queuepush", uuid);
        });
        Region.onPlayerQueuePop.add((region, uuid) -> {
            if (!effectParsingEnabled(parser) || uuid == null) return;
            dispatchByUuid("rtp.effect.queuepop", uuid);
        });

        // ----------------------------------------------------------------
        // Post-teleport title / subtitle / actionbar - `messages.yml`
        // parity with BukkitEffectsHandler / RTPFabricMod. This is NOT an
        // `effects/<group>.yml` effect and is intentionally NOT gated on
        // performance.yml `effectParsing`: the `title`, `subtitle`,
        // `fadeIn`, `stay`, `fadeOut`, and `actionbar` keys are the
        // baseline "successfully teleported" splash that admins configure
        // on Bukkit. Without this hook those keys are silent no-ops on
        // NeoForge. Packets are sent on the server tick thread via
        // RTPRuntime.scheduler().runTask; sendTitle / sendActionbar handle empty
        // values internally so a partially-configured messages.yml works.
        // ----------------------------------------------------------------
        TeleportPipelineTask.teleportPostActions.add(NeoForgeEffectsHandler::sendConfiguredTitle);
    }

    public static void onPlayerDeath(ServerPlayer player) {
        if (player == null) return;
        Configs configs = ActiveConfigs.get();
        if (configs == null) return;
        FactoryValue<PerformanceKeys> parser = configs.getParser(PerformanceKeys.class);
        if (!effectParsingEnabled(parser)) return;

        if (RTPRuntime.serverAccessor() == null) return;
        RTPPlayer rp = RTPRuntime.serverAccessor().getPlayer(player.getUUID());
        if (rp == null) return;

        dispatch("rtp.effect.death", rp);
    }

    private static void sendConfiguredTitle(TeleportPipelineTask task) {
        try {
            if (task == null || task.player() == null) return;
            if (!(task.player() instanceof NeoForgeRTPPlayer np)) return;

            @SuppressWarnings("unchecked")
            io.github.dailystruggle.rtp.common.configuration.ConfigParser<PlayerMessages> lang =
                    (io.github.dailystruggle.rtp.common.configuration.ConfigParser<PlayerMessages>)
                            ActiveConfigs.get().getParser(PlayerMessages.class);
            if (lang == null) return;

            final String title = ActiveConfigs.get().getConfigValue(
                    io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.title, "").toString();
            final String subtitle = ActiveConfigs.get().getConfigValue(
                    io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.subtitle, "").toString();
            final int fadeIn = lang.getNumber(
                    io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.fadeIn, 0).intValue();
            final int stay = lang.getNumber(
                    io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.stay, 0).intValue();
            final int fadeOut = lang.getNumber(
                    io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.fadeOut, 0).intValue();
            final String actionbar = ActiveConfigs.get().getConfigValue(
                    io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.actionbar, "").toString();

            if ((title == null || title.isEmpty())
                    && (subtitle == null || subtitle.isEmpty())
                    && (actionbar == null || actionbar.isEmpty())) {
                return;
            }

            RTPRuntime.scheduler().runTask(() -> {
                try {
                    np.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
                    np.sendActionbar(actionbar);
                } catch (Throwable t) {
                    RTP.log(Level.WARNING,
                            "[RTP][NeoForge] post-teleport title dispatch failed: "
                                    + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][NeoForge] post-teleport title hook failed: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static boolean effectParsingEnabled(FactoryValue<PerformanceKeys> parser) {
        if (parser == null || parser.getData() == null) return false;
        Object val = parser.getData().get(PerformanceKeys.effectParsing);
        if (val instanceof Boolean b) return b;
        return val != null && Boolean.parseBoolean(val.toString());
    }

    /** UUID-resolving variant for cancel / queue-push / queue-pop hooks. */
    private static void dispatchByUuid(String prefix, UUID uuid) {
        try {
            if (RTPRuntime.serverAccessor() == null) return;
            RTPPlayer rp = RTPRuntime.serverAccessor().getPlayer(uuid);
            if (rp == null) return;
            dispatch(prefix, rp);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][NeoForge] effect dispatch (uuid) failed for " + prefix + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void dispatch(String prefix, RTPPlayer player) {
        dispatch(prefix, player, null, null, null, null);
    }

    private static void dispatch(String prefix, RTPPlayer player,
                                 net.minecraft.world.phys.Vec3 explicitLocation,
                                 net.minecraft.world.phys.Vec3 destinationLocation,
                                 String destWorldName,
                                 TeleportPipelineTask pipelineTask) {
        try {
            if (!(player instanceof NeoForgeRTPPlayer np)) return;
            ServerPlayer handle = np.handle();
            if (handle == null) return; // disconnected
            // Re-resolve every dispatch so /rtp reload (which atomically swaps
            // the multiConfigParserMap) is honored without local cache
            // invalidation. Union permission-derived nodes with the
            // effects/<group>.yml-driven token list (effects-api-ADR-005).
            Set<String> perms = np.getEffectivePermissions();
            String stage = stageOf(prefix);
            Collection<String> union = EffectsResolver.resolveUnioned(stage, np, prefix, perms);
            if (union.isEmpty()) return;

            Object target;
            if (explicitLocation != null) {
                Object destHandle = null;
                if (destinationLocation != null && destWorldName != null) {
                    destHandle = NeoForgeHandles.wrap(destinationLocation, destWorldName);
                }
                target = new io.github.dailystruggle.effectsapi.common.spi.EffectTarget(
                        NeoForgeHandles.wrap(handle),
                        explicitLocation,
                        destHandle);
            } else {
                target = handle;
            }

            List<Effect<?>> effects = EffectFactory.buildEffects(prefix, union);
            EffectDispatch.forEachIsolated(prefix, effects, effect -> {
                effect.setTarget(target);
                if (effect instanceof io.github.dailystruggle.effectsapi.common.effects.DeathEffect) {
                    DeathTeleports.effectInFlight().add(handle.getUUID());
                    if (pipelineTask != null) {
                        pipelineTask.deathEffectTriggered = true;
                    }
                }
                NeoForgeHandles.execute(effect);
            });
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][NeoForge] effect dispatch failed for " + prefix + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static String stageOf(String prefix) {
        int dot = prefix.lastIndexOf('.');
        return (dot >= 0 && dot < prefix.length() - 1) ? prefix.substring(dot + 1) : prefix;
    }

    private static final class AlreadyHooked {
        private static volatile boolean done = false;
        static boolean flip() {
            synchronized (AlreadyHooked.class) {
                if (done) return false;
                done = true;
                return true;
            }
        }
    }
}
