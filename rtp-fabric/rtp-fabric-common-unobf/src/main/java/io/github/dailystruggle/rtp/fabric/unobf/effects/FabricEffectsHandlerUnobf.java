package io.github.dailystruggle.rtp.fabric.unobf.effects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.effectsapi.fabric_unobf.FabricEffectRuntimeUnobf;
import io.github.dailystruggle.effectsapi.fabric_unobf.FabricEffectsInitializer;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Fabric counterpart of
 * {@code io.github.dailystruggle.rtp.bukkit.effects.BukkitEffectsHandler}
 * per <em>effects-api-ADR-003</em>.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Bind the {@link MinecraftServer} into {@link FabricEffectRuntimeUnobf} so
 *       any Fabric-side effect scheduling has a server to dispatch onto.</li>
 *   <li>Invoke {@link FabricEffectsInitializer#registerAll()} (idempotent)
 *       which binds the {@code FabricValueCoercer} (ADR-004) and registers
 *       SOUND/PARTICLE/TITLE/POTION effect prototypes.</li>
 *   <li>Attach the seven {@code rtp.effect.*} lifecycle hooks
 *       (presetup / postsetup / preload / postload / preteleport /
 *       postteleport / queue-push / queue-pop / cancel) onto
 *       {@link TeleportPipelineTask} and {@link Region}, mirroring the
 *       Bukkit shape so admin-facing behavior stays parity.</li>
 * </ol>
 *
 * <p>Phase-1 limitation: Fabric servers without a permissions manager have
 * an empty {@code getEffectivePermissions()} {@link Set}, so 0 effects fire
 * per player. Operators get audible/visible feedback once either
 * (a) {@code fabric-permissions-api} is integrated and effect nodes are
 * granted, or (b) the deferred {@code effects.yml} from checklist step 11
 * lands. This is the same behavior the Bukkit side has when no permission
 * plugin is present.
 *
 * <p>S-005: all effect dispatch is funneled through {@link FabricEffectRuntimeUnobf#schedule}
 * (→ {@code MinecraftServer#execute}). Effect <em>resolution</em> (the
 * {@code EffectFactory.buildEffects} call) runs on the calling thread (which
 * is the pipeline thread on Fabric per ADR-022 §4) and is pure in-memory
 * lookup against {@code BuiltInRegistries} — no chunk I/O.
 */
public final class FabricEffectsHandlerUnobf {

    private FabricEffectsHandlerUnobf() {}

    /**
     * Wire the Fabric effects layer to the RTP pipeline. Must be called from
     * {@code ServerLifecycleEvents.SERVER_STARTED} (so {@code BuiltInRegistries}
     * is fully populated and {@link FabricEffectRuntimeUnobf#schedule} has a server
     * to dispatch onto).
     *
     * <p>Idempotent: a second call (e.g. after an integrated-server restart in
     * the same JVM) re-binds the server and is otherwise a no-op (the
     * lifecycle-hook lambdas are added once via the {@link AlreadyHooked} flag).
     */
    public static void setupEffects(MinecraftServer server) {
        RTP.log(Level.FINE, "[RTP][FX-trace] setupEffects ENTER server=" + (server == null ? "null" : server.getClass().getSimpleName()));
        FabricEffectRuntimeUnobf.bindServer(server);
        FabricEffectsInitializer.registerAll();

        if (!AlreadyHooked.flip()) {
            RTP.log(Level.FINE, "[RTP][FX-trace] setupEffects: hooks already attached on previous start, skipping re-attach");
            return; // already attached on a previous start
        }
        RTP.log(Level.FINE, "[RTP][FX-trace] setupEffects: attaching lifecycle hooks");

        Configs configs = RTP.configs;
        FactoryValue<PerformanceKeys> parser = configs.getParser(PerformanceKeys.class);
        FabricEffectRuntimeUnobf runtime = new FabricEffectRuntimeUnobf();

        TeleportPipelineTask.setupPreActions.add(task -> {
            if (!effectParsingEnabled(parser) || task.player() == null) return;
            dispatch("rtp.effect.presetup", task.player(), runtime);
        });
        TeleportPipelineTask.setupPostActions.add((task, ok) -> {
            if (!ok || !effectParsingEnabled(parser) || task.player() == null) return;
            dispatch("rtp.effect.postsetup", task.player(), runtime);
        });
        TeleportPipelineTask.loadPreActions.add(task -> {
            if (!effectParsingEnabled(parser) || task.player() == null) return;
            dispatch("rtp.effect.preload", task.player(), runtime);
        });
        TeleportPipelineTask.loadPostActions.add(task -> {
            if (!effectParsingEnabled(parser) || task.player() == null) return;
            dispatch("rtp.effect.postload", task.player(), runtime);
        });
        TeleportPipelineTask.teleportPreActions.add(task -> {
            if (!effectParsingEnabled(parser) || task.player() == null) return;
            dispatch("rtp.effect.preteleport", task.player(), runtime);
        });
        TeleportPipelineTask.teleportPostActions.add(task -> {
            boolean enabled = effectParsingEnabled(parser);
            boolean hasPlayer = task.player() != null;
            RTP.log(Level.FINE, "[RTP][FX-trace] hook postteleport fired enabled=" + enabled + " hasPlayer=" + hasPlayer);
            if (!enabled || !hasPlayer) return;
            dispatch("rtp.effect.postteleport", task.player(), runtime);
        });
        RTPTeleportCancel.postActions.add(cancel -> {
            if (!effectParsingEnabled(parser)) return;
            UUID uuid = cancel.getPlayerId();
            if (uuid == null) return;
            dispatchByUuid("rtp.effect.cancel", uuid, runtime);
        });
        Region.onPlayerQueuePush.add((region, uuid) -> {
            if (!effectParsingEnabled(parser) || uuid == null) return;
            dispatchByUuid("rtp.effect.queuepush", uuid, runtime);
        });
        Region.onPlayerQueuePop.add((region, uuid) -> {
            if (!effectParsingEnabled(parser) || uuid == null) return;
            dispatchByUuid("rtp.effect.queuepop", uuid, runtime);
        });
    }

    private static boolean effectParsingEnabled(FactoryValue<PerformanceKeys> parser) {
        return Boolean.parseBoolean(
                parser.getData().getOrDefault(PerformanceKeys.effectParsing, false).toString());
    }

    /**
     * Resolve and schedule effects for a given lifecycle stage.
     *
     * @param prefix    permission-node prefix (e.g. {@code "rtp.effect.presetup"})
     * @param player    the RTPPlayer for this stage; must expose {@code handle()} returning a
     *                  {@link ServerPlayer} and {@code getEffectivePermissions()} (structural typing).
     * @param runtime   scheduler chokepoint — funnels each effect run() onto the server thread
     */
    /** UUID-resolving variant for cancel / queue-push / queue-pop hooks. */
    private static void dispatchByUuid(String prefix, UUID uuid, FabricEffectRuntimeUnobf runtime) {
        try {
            if (RTP.serverAccessor == null) return;
            RTPPlayer rp = RTP.serverAccessor.getPlayer(uuid);
            if (rp == null) return;
            dispatch(prefix, rp, runtime);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP] Fabric effect dispatch (uuid) failed for " + prefix + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void dispatch(String prefix, RTPPlayer player, FabricEffectRuntimeUnobf runtime) {
        try {
            RTP.log(Level.FINE, "[RTP][FX-trace] dispatch ENTER prefix=" + prefix
                    + " playerClass=" + (player == null ? "null" : player.getClass().getSimpleName()));
            if (player == null) {
                RTP.log(Level.FINE, "[RTP][FX-trace] dispatch SKIP prefix=" + prefix + " reason=player-null");
                return;
            }
            // The platform-versioned RTPPlayer (e.g. V26_1_R1FabricRTPPlayer) does NOT
            // extend FabricRTPPlayerUnobf — both are `final implements RTPPlayer`. To
            // stay platform-agnostic and avoid forcing a class-hierarchy refactor,
            // resolve handle() + getEffectivePermissions() reflectively, mirroring the
            // existing reflective EffectsResolver dispatch below.
            ServerPlayer handle;
            Set<String> perms;
            try {
                java.lang.reflect.Method handleM = player.getClass().getMethod("handle");
                Object h = handleM.invoke(player);
                if (!(h instanceof ServerPlayer sp)) {
                    RTP.log(Level.FINE, "[RTP][FX-trace] dispatch SKIP prefix=" + prefix
                            + " reason=handle-not-ServerPlayer(actual=" + (h == null ? "null" : h.getClass().getName()) + ")");
                    return;
                }
                handle = sp;
                @SuppressWarnings("unchecked")
                Set<String> p = (Set<String>) player.getClass().getMethod("getEffectivePermissions").invoke(player);
                perms = (p == null) ? java.util.Collections.emptySet() : p;
            } catch (NoSuchMethodException nsme) {
                RTP.log(Level.FINE, "[RTP][FX-trace] dispatch SKIP prefix=" + prefix
                        + " reason=incompatible-RTPPlayer(class=" + player.getClass().getName() + ")");
                return;
            } catch (ReflectiveOperationException roe) {
                RTP.log(Level.WARNING, "[RTP][FX-trace] dispatch reflective access failed prefix=" + prefix
                        + " err=" + roe.getClass().getSimpleName() + ": " + roe.getMessage());
                return;
            }
            if (handle == null) {
                RTP.log(Level.FINE, "[RTP][FX-trace] dispatch SKIP prefix=" + prefix + " reason=handle-null(disconnected)");
                return; // disconnected
            }
            // Re-resolve every dispatch so /rtp reload (which atomically swaps
            // the multiConfigParserMap) is honored without local cache invalidation.
            // Union permission-derived nodes with the effects.yml-driven token list
            // (effects-api-ADR-005). On Fabric servers without fabric-permissions-api,
            // perms is empty and the union becomes effects/ only — which is the
            // primary path on this platform per ADR-005.
            RTPPlayer fp = player;
            String stage = stageOf(prefix);
            RTP.log(Level.FINE, "[RTP][FX-trace] resolving prefix=" + prefix + " stage=" + stage
                    + " permsCount=" + (perms == null ? -1 : perms.size()));
            // EffectsResolver lives in rtp-plugin (not on this module's classpath —
            // common-unobf must not depend on rtp-plugin). Reflective dispatch keeps
            // the dependency one-way: rtp-plugin pulls in common-unobf, not the
            // reverse. Same shape as Fabric perms-api access in FabricRTPPlayerUnobf.
            java.util.Collection<String> union;
            try {
                Class<?> resolverCls = Class.forName("io.github.dailystruggle.rtp.effects.EffectsResolver");
                java.lang.reflect.Method m = resolverCls.getMethod(
                        "resolveUnioned", String.class, RTPPlayer.class, String.class, java.util.Collection.class);
                @SuppressWarnings("unchecked")
                java.util.Collection<String> result =
                        (java.util.Collection<String>) m.invoke(null, stage, fp, prefix, perms);
                union = (result == null) ? java.util.Collections.emptyList() : result;
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                RTP.log(Level.WARNING, "[RTP][FX-trace] EffectsResolver lookup failed prefix=" + prefix
                        + " err=" + e.getClass().getSimpleName() + ": " + e.getMessage());
                return;
            }
            RTP.log(Level.FINE, "[RTP][FX-trace] resolved prefix=" + prefix + " unionSize=" + union.size()
                    + " tokens=" + union);
            if (union.isEmpty()) {
                RTP.log(Level.FINE, "[RTP][FX-trace] dispatch SKIP prefix=" + prefix + " reason=empty-union");
                return;
            }

            List<Effect<?>> effects = EffectFactory.buildEffects(prefix, union);
            RTP.log(Level.FINE, "[RTP][FX-trace] built effects prefix=" + prefix + " count=" + effects.size());
            for (Effect<?> effect : effects) {
                effect.setTarget(handle);
                runtime.schedule(effect, 0);
                RTP.log(Level.FINE, "[RTP][FX-trace] scheduled prefix=" + prefix
                        + " effectClass=" + effect.getClass().getSimpleName());
            }
            RTP.log(Level.FINE, "[RTP][FX-trace] dispatch DONE prefix=" + prefix + " scheduled=" + effects.size());
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP] Fabric effect dispatch failed for " + prefix + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
    }

    /**
     * Extract the pipeline stage token (e.g. {@code "postteleport"}) from a
     * permission prefix of the shape {@code "rtp.effect.<stage>"}. Returns the
     * input unchanged when no dotted prefix is present so the resolver still
     * runs (it will just produce an empty list for an unrecognised stage).
     */
    private static String stageOf(String prefix) {
        int dot = prefix.lastIndexOf('.');
        return (dot >= 0 && dot < prefix.length() - 1) ? prefix.substring(dot + 1) : prefix;
    }

    /**
     * Single-shot guard so {@link #setupEffects(MinecraftServer)} can be safely
     * re-invoked across integrated-server restarts in the same JVM (which would
     * otherwise duplicate every lifecycle hook).
     */
    private static final class AlreadyHooked {
        private static volatile boolean done = false;
        static synchronized boolean flip() {
            if (done) return false;
            done = true;
            return true;
        }
    }
}
