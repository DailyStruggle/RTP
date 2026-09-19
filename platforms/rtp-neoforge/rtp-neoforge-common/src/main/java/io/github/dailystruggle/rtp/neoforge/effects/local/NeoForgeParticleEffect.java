package io.github.dailystruggle.rtp.neoforge.effects.local;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.fabric.LocalEffects.enums.FabricParticleKeys;
import io.github.dailystruggle.rtp.neoforge.player.NeoForgeRTPPlayer;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
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
public class NeoForgeParticleEffect extends Effect<FabricParticleKeys> {

    public NeoForgeParticleEffect() throws IllegalArgumentException {
        super(new EnumMap<>(FabricParticleKeys.class));
        EnumMap<FabricParticleKeys, Object> d = getData();
        d.put(FabricParticleKeys.TYPE, ParticleTypes.HAPPY_VILLAGER);
        d.put(FabricParticleKeys.COUNT, 32);
        d.put(FabricParticleKeys.DX, 1.0);
        d.put(FabricParticleKeys.DY, 1.0);
        d.put(FabricParticleKeys.DZ, 1.0);
        d.put(FabricParticleKeys.SPEED, 0.0);
        this.data = d;
        this.defaults = d.clone();
    }

    @Override
    public void run() {
        if (!(target instanceof ServerPlayer)) return;
        ServerPlayer player = (ServerPlayer) target;

        Object typeObj = data.get(FabricParticleKeys.TYPE);
        ParticleOptions opts;
        if (typeObj instanceof ParticleOptions) {
            opts = (ParticleOptions) typeObj;
        } else if (typeObj instanceof SimpleParticleType) {
            opts = (SimpleParticleType) typeObj;
        } else if (typeObj instanceof ParticleType<?>) {
            opts = ParticleTypes.HAPPY_VILLAGER; // non-simple type with no config -> safe default
        } else {
            return;
        }

        int count = numAsInt(data.get(FabricParticleKeys.COUNT), 32);
        double dx = numAsDouble(data.get(FabricParticleKeys.DX), 1.0);
        double dy = numAsDouble(data.get(FabricParticleKeys.DY), 1.0);
        double dz = numAsDouble(data.get(FabricParticleKeys.DZ), 1.0);
        double speed = numAsDouble(data.get(FabricParticleKeys.SPEED), 0.0);

        double px = player.getX();
        double py = player.getY() + 1.0;
        double pz = player.getZ();

        ServerLevel level = NeoForgeRTPPlayer.resolveServerLevel(player);
        if (level == null) return;
        if (!invokeSendParticlesTargeted(level, player, opts, px, py, pz, count, dx, dy, dz, speed)) {
            invokeSendParticles(level, opts, px, py, pz, count, dx, dy, dz, speed);
        }
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
        if (p[i++] != double.class || p[i++] != double.class || p[i++] != double.class) return false;
        if (p[i++] != int.class) return false;
        if (p[i++] != double.class || p[i++] != double.class || p[i++] != double.class || p[i] != double.class) return false;
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
        if (p[i++] != double.class || p[i++] != double.class || p[i++] != double.class) return false;
        if (p[i++] != int.class) return false;
        if (p[i++] != double.class || p[i++] != double.class || p[i++] != double.class || p[i] != double.class) return false;
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
        return String.valueOf(data.get(FabricParticleKeys.TYPE)) + "."
                + data.get(FabricParticleKeys.COUNT) + "."
                + data.get(FabricParticleKeys.DX) + "."
                + data.get(FabricParticleKeys.DY) + "."
                + data.get(FabricParticleKeys.DZ) + "."
                + data.get(FabricParticleKeys.SPEED);
    }

    @Override
    public void setData(String... data) {
        applyByType(KEY_ORDER, data);
    }

    private static final FabricParticleKeys[] KEY_ORDER = {
            FabricParticleKeys.TYPE, FabricParticleKeys.COUNT,
            FabricParticleKeys.DX, FabricParticleKeys.DY, FabricParticleKeys.DZ,
            FabricParticleKeys.SPEED
    };

    private static int numAsInt(Object o, int fallback) {
        return (o instanceof Number) ? ((Number) o).intValue() : fallback;
    }
    private static double numAsDouble(Object o, double fallback) {
        return (o instanceof Number) ? ((Number) o).doubleValue() : fallback;
    }
}
