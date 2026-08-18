package io.github.dailystruggle.rtp.fabric.effects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.effectsapi.fabric.FabricEffectRuntime;
import io.github.dailystruggle.effectsapi.fabric.FabricEffectsInitializer;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import io.github.dailystruggle.rtp.effects.EffectsResolver;
import io.github.dailystruggle.rtp.fabric.player.FabricRTPPlayer;
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
 *   <li>Bind the {@link MinecraftServer} into {@link FabricEffectRuntime} so
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
 * <p>S-005: all effect dispatch is funneled through {@link FabricEffectRuntime#schedule}
 * (→ {@code MinecraftServer#execute}). Effect <em>resolution</em> (the
 * {@code EffectFactory.buildEffects} call) runs on the calling thread (which
 * is the pipeline thread on Fabric per ADR-022 §4) and is pure in-memory
 * lookup against {@code BuiltInRegistries} - no chunk I/O.
 */
public final class FabricEffectsHandler {

    private FabricEffectsHandler() {}

    /**
     * Wire the Fabric effects layer to the RTP pipeline. Must be called from
     * {@code ServerLifecycleEvents.SERVER_STARTED} (so {@code BuiltInRegistries}
     * is fully populated and {@link FabricEffectRuntime#schedule} has a server
     * to dispatch onto).
     *
     * <p>Idempotent: a second call (e.g. after an integrated-server restart in
     * the same JVM) re-binds the server and is otherwise a no-op (the
     * lifecycle-hook lambdas are added once via the {@link AlreadyHooked} flag).
     */
    public static void setupEffects(MinecraftServer server) {
        FabricEffectRuntime.bindServer(server);
        FabricEffectsInitializer.registerAll();

        if (!AlreadyHooked.flip()) return; // already attached on a previous start

        Configs configs = RTP.configs;
        FactoryValue<PerformanceKeys> parser = configs.getParser(PerformanceKeys.class);
        FabricEffectRuntime runtime = new FabricEffectRuntime();

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
            if (!effectParsingEnabled(parser) || task.player() == null) return;
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
     * @param player    the RTPPlayer for this stage; expected to be a {@link FabricRTPPlayer}
     * @param runtime   scheduler chokepoint - funnels each effect run() onto the server thread
     */
    /** UUID-resolving variant for cancel / queue-push / queue-pop hooks. */
    private static void dispatchByUuid(String prefix, UUID uuid, FabricEffectRuntime runtime) {
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

    private static void dispatch(String prefix, RTPPlayer player, FabricEffectRuntime runtime) {
        try {
            if (!(player instanceof FabricRTPPlayer fp)) return;
            ServerPlayer handle = fp.handle();
            if (handle == null) return; // disconnected
            // Re-resolve every dispatch so /rtp reload (which atomically swaps
            // the multiConfigParserMap) is honored without local cache invalidation.
            // Union permission-derived nodes with the effects.yml-driven token list
            // (effects-api-ADR-005). On Fabric servers without fabric-permissions-api,
            // perms is empty and the union becomes effects/ only - which is the
            // primary path on this platform per ADR-005.
            Set<String> perms = fp.getEffectivePermissions();
            String stage = stageOf(prefix);
            java.util.Collection<String> union =
                    EffectsResolver.resolveUnioned(stage, fp, prefix, perms);
            if (union.isEmpty()) return;

            List<Effect<?>> effects = EffectFactory.buildEffects(prefix, union);
            for (Effect<?> effect : effects) {
                effect.setTarget(handle);
                runtime.schedule(effect, 0);
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP] Fabric effect dispatch failed for " + prefix + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
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
