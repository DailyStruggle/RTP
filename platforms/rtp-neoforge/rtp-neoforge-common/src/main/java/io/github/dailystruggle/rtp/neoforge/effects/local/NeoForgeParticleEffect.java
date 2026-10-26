package io.github.dailystruggle.rtp.neoforge.effects.local;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.rtp.neoforge.player.NeoForgeRTPPlayer;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.EnumMap;

/**
 * NeoForge PARTICLE effect (effects-api-ADR-003), compiled Mojmap in
 * {@code rtp-neoforge-common}. Spawns particles at the target
 * {@link ServerPlayer}'s position via a signature-resolved
 * {@code ServerLevel#sendParticles} overload (the boolean-prefix arity drifts
 * across 1.21.x). Prefers the targeted (recipient) overload so the packet
 * bypasses the chunk tracker right after a long teleport. S-004 cosmetic.
 */
public class NeoForgeParticleEffect extends Effect<NeoForgeParticleKeys> {

    public NeoForgeParticleEffect() throws IllegalArgumentException {
        super(new EnumMap<>(NeoForgeParticleKeys.class));
        EnumMap<NeoForgeParticleKeys, Object> d = getData();
        d.put(NeoForgeParticleKeys.TYPE, defaultParticle());
        d.put(NeoForgeParticleKeys.COUNT, 32);
        d.put(NeoForgeParticleKeys.DX, 1.0);
        d.put(NeoForgeParticleKeys.DY, 1.0);
        d.put(NeoForgeParticleKeys.DZ, 1.0);
        d.put(NeoForgeParticleKeys.SPEED, 0.0);
        this.data = d;
        this.defaults = d.clone();
    }

    private static Object defaultParticle() {
        try {
            return ParticleTypes.HAPPY_VILLAGER;
        } catch (Throwable ignored) {
            return "minecraft:happy_villager";
        }
    }

    @Override
    public void run() {
        ServerPlayer player = NeoForgeHandles.unwrapPlayer(target);
        if (player == null) return;

        Object typeObj = data.get(NeoForgeParticleKeys.TYPE);
        int count = numAsInt(data.get(NeoForgeParticleKeys.COUNT), 32);
        double dx = numAsDouble(data.get(NeoForgeParticleKeys.DX), 1.0);
        double dy = numAsDouble(data.get(NeoForgeParticleKeys.DY), 1.0);
        double dz = numAsDouble(data.get(NeoForgeParticleKeys.DZ), 1.0);
        double speed = numAsDouble(data.get(NeoForgeParticleKeys.SPEED), 0.0);

        spawnForPlayer(player, typeObj, count, dx, dy, dz, speed);
    }

    static ParticleOptions resolveParticleOptions(Object typeObj) {
        if (typeObj instanceof ParticleOptions opts) return opts;
        if (typeObj instanceof ParticleType<?>) return (ParticleOptions) defaultParticle();
        if (typeObj != null) {
            try {
                ParticleType<?> resolved = NeoForgeRegistryResolver.resolve(
                        net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE, typeObj.toString());
                if (resolved instanceof ParticleOptions opts) return opts;
                if (resolved != null) return (ParticleOptions) defaultParticle();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    public static boolean spawnForPlayer(ServerPlayer player, Object typeObj, int count,
                                         double dx, double dy, double dz, double speed) {
        if (player == null) return false;
        ParticleOptions opts = resolveParticleOptions(typeObj);
        if (opts == null) return false;

        int clampedCount = Math.max(1, Math.min(256, count));
        double px = player.getX();
        double py = player.getY() + 1.0;
        double pz = player.getZ();

        ServerLevel level = NeoForgeRTPPlayer.resolveServerLevel(player);
        if (level == null) return false;
        if (!invokeSendParticlesTargeted(level, player, opts, px, py, pz, clampedCount, dx, dy, dz, speed)) {
            invokeSendParticles(level, opts, px, py, pz, clampedCount, dx, dy, dz, speed);
        }
        return SEND_PARTICLES_TARGETED != null || SEND_PARTICLES != null;
    }

    public static boolean spawnAtLevel(ServerLevel level, double x, double y, double z,
                                       Object typeObj, int count,
                                       double dx, double dy, double dz, double speed) {
        if (level == null) return false;
        ParticleOptions opts = resolveParticleOptions(typeObj);
        if (opts == null) return false;
        int clampedCount = Math.max(1, Math.min(256, count));
        invokeSendParticles(level, opts, x, y, z, clampedCount, dx, dy, dz, speed);
        return SEND_PARTICLES != null;
    }

    // ---- targeted (recipient) overload ------------------------------------
    private static volatile Method SEND_PARTICLES_TARGETED;
    private static volatile int SEND_PARTICLES_TARGETED_BOOL_PREFIX;
    private static volatile boolean SEND_PARTICLES_TARGETED_RESOLVED;

    private static boolean targetedSigMatches(Class<?>[] p, int boolPrefix) {
        int expected = 1 + 1 + boolPrefix + 3 + 1 + 4;
        if (p.length != expected) return false;
        if (!ServerPlayer.class.isAssignableFrom(p[0])) return false;
        if (!ParticleOptions.class.isAssignableFrom(p[1])) return false;
        int i = 2;
        for (int b = 0; b < boolPrefix; b++) if (p[i++] != boolean.class) return false;
        for (int d = 0; d < 3; d++) if (p[i++] != double.class) return false;
        if (p[i++] != int.class) return false;
        for (int d = 0; d < 4; d++) if (p[i++] != double.class) return false;
        return true;
    }

    private static boolean invokeSendParticlesTargeted(ServerLevel level, ServerPlayer recipient,
                                                       ParticleOptions opts, double x, double y, double z,
                                                       int count, double dx, double dy, double dz, double speed) {
        if (!SEND_PARTICLES_TARGETED_RESOLVED) {
            synchronized (NeoForgeParticleEffect.class) {
                if (!SEND_PARTICLES_TARGETED_RESOLVED) {
                    Method found = null;
                    int boolPrefix = 0;
                    for (int candPrefix = 2; candPrefix >= 0 && found == null; candPrefix--) {
                        for (Method m : ServerLevel.class.getMethods()) {
                            if (!targetedSigMatches(m.getParameterTypes(), candPrefix)) continue;
                            found = m; boolPrefix = candPrefix; break;
                        }
                    }
                    SEND_PARTICLES_TARGETED = found;
                    SEND_PARTICLES_TARGETED_BOOL_PREFIX = boolPrefix;
                    SEND_PARTICLES_TARGETED_RESOLVED = true;
                }
            }
        }
        Method m = SEND_PARTICLES_TARGETED;
        if (m == null) return false;
        try {
            switch (SEND_PARTICLES_TARGETED_BOOL_PREFIX) {
                case 2: m.invoke(level, recipient, opts, true, true, x, y, z, count, dx, dy, dz, speed); break;
                case 1: m.invoke(level, recipient, opts, true, x, y, z, count, dx, dy, dz, speed); break;
                default: m.invoke(level, recipient, opts, x, y, z, count, dx, dy, dz, speed); break;
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ---- broadcast overload -----------------------------------------------
    private static volatile Method SEND_PARTICLES;
    private static volatile int SEND_PARTICLES_BOOL_PREFIX;
    private static volatile boolean SEND_PARTICLES_RESOLVED;

    private static boolean particleSigMatches(Class<?>[] p, int boolPrefix) {
        int expected = 1 + boolPrefix + 3 + 1 + 4;
        if (p.length != expected) return false;
        if (!ParticleOptions.class.isAssignableFrom(p[0])) return false;
        int i = 1;
        for (int b = 0; b < boolPrefix; b++) if (p[i++] != boolean.class) return false;
        for (int d = 0; d < 3; d++) if (p[i++] != double.class) return false;
        if (p[i++] != int.class) return false;
        for (int d = 0; d < 4; d++) if (p[i++] != double.class) return false;
        return true;
    }

    private static void resolveSendParticles() {
        if (SEND_PARTICLES_RESOLVED) return;
        synchronized (NeoForgeParticleEffect.class) {
            if (SEND_PARTICLES_RESOLVED) return;
            Method found = null;
            int boolPrefix = 0;
            for (int candPrefix = 2; candPrefix >= 0 && found == null; candPrefix--) {
                for (Method m : ServerLevel.class.getMethods()) {
                    if (!particleSigMatches(m.getParameterTypes(), candPrefix)) continue;
                    found = m; boolPrefix = candPrefix; break;
                }
            }
            SEND_PARTICLES = found;
            SEND_PARTICLES_BOOL_PREFIX = boolPrefix;
            SEND_PARTICLES_RESOLVED = true;
        }
    }

    private static void invokeSendParticles(ServerLevel level, ParticleOptions opts,
                                            double x, double y, double z, int count,
                                            double dx, double dy, double dz, double speed) {
        resolveSendParticles();
        Method m = SEND_PARTICLES;
        if (m == null) return;
        try {
            switch (SEND_PARTICLES_BOOL_PREFIX) {
                case 2: m.invoke(level, opts, false, true, x, y, z, count, dx, dy, dz, speed); break;
                case 1: m.invoke(level, opts, false, x, y, z, count, dx, dy, dz, speed); break;
                default: m.invoke(level, opts, x, y, z, count, dx, dy, dz, speed); break;
            }
        } catch (Throwable ignored) {
            // cosmetic; never break teleport
        }
    }

    @Override
    public String toPermission() {
        return data.get(NeoForgeParticleKeys.TYPE) + "."
                + data.get(NeoForgeParticleKeys.COUNT) + "."
                + data.get(NeoForgeParticleKeys.DX) + "."
                + data.get(NeoForgeParticleKeys.DY) + "."
                + data.get(NeoForgeParticleKeys.DZ) + "."
                + data.get(NeoForgeParticleKeys.SPEED);
    }

    @Override
    public void setData(String... data) {
        applyByType(KEY_ORDER, data);
    }

    private static final NeoForgeParticleKeys[] KEY_ORDER = {
            NeoForgeParticleKeys.TYPE, NeoForgeParticleKeys.COUNT,
            NeoForgeParticleKeys.DX, NeoForgeParticleKeys.DY, NeoForgeParticleKeys.DZ,
            NeoForgeParticleKeys.SPEED
    };

    private static int numAsInt(Object o, int fallback) {
        return (o instanceof Number) ? ((Number) o).intValue() : fallback;
    }
    private static double numAsDouble(Object o, double fallback) {
        return (o instanceof Number) ? ((Number) o).doubleValue() : fallback;
    }
}
