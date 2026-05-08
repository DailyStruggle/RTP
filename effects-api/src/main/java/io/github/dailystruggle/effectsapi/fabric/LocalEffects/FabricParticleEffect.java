package io.github.dailystruggle.effectsapi.fabric.LocalEffects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.fabric.LocalEffects.enums.FabricParticleKeys;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.EnumMap;

/**
 * Fabric counterpart of
 * {@code io.github.dailystruggle.effectsapi.bukkit.LocalEffects.ParticleEffect}.
 * Spawns particles at the target {@link ServerPlayer}'s position via
 * {@code ServerLevel#sendParticles}.
 */
public class FabricParticleEffect extends Effect<FabricParticleKeys> {

    public FabricParticleEffect() throws IllegalArgumentException {
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
        if (!(target instanceof ServerPlayer)) {
            System.err.println("[effects-api] [FabricParticleEffect] skip: target is not ServerPlayer (got "
                    + (target == null ? "null" : target.getClass().getName()) + ")");
            return;
        }
        ServerPlayer player = (ServerPlayer) target;
        ServerLevel level = player.serverLevel();
        if (level == null) {
            System.err.println("[effects-api] [FabricParticleEffect] skip: ServerPlayer.serverLevel() returned null");
            return;
        }

        Object typeObj = data.get(FabricParticleKeys.TYPE);
        ParticleOptions opts;
        if (typeObj instanceof ParticleOptions) {
            opts = (ParticleOptions) typeObj;
        } else if (typeObj instanceof SimpleParticleType) {
            opts = (SimpleParticleType) typeObj;
        } else if (typeObj instanceof ParticleType<?>) {
            // Non-simple particle types require additional config we don't have;
            // fall back to a known-safe default rather than crashing.
            System.err.println("[effects-api] [FabricParticleEffect] non-Simple ParticleType '" + typeObj
                    + "' has no configured options; falling back to HAPPY_VILLAGER");
            opts = ParticleTypes.HAPPY_VILLAGER;
        } else {
            System.err.println("[effects-api] [FabricParticleEffect] skip: TYPE is not a ParticleType (got "
                    + (typeObj == null ? "null" : typeObj.getClass().getName()) + "=" + typeObj + ")");
            return;
        }

        int count = numAsInt(data.get(FabricParticleKeys.COUNT), 32);
        double dx = numAsDouble(data.get(FabricParticleKeys.DX), 1.0);
        double dy = numAsDouble(data.get(FabricParticleKeys.DY), 1.0);
        double dz = numAsDouble(data.get(FabricParticleKeys.DZ), 1.0);
        double speed = numAsDouble(data.get(FabricParticleKeys.SPEED), 0.0);

        // Prefer the targeted (ServerPlayer-recipient) overload of
        // sendParticles so the packet bypasses the chunk tracker. After a
        // long teleport the player is briefly outside the destination
        // chunk's tracker, so the broadcast overload's packets are dropped
        // client-side and particles are silently invisible (matches the
        // user-reported 1.21.11 behavior, 2026-05-07: resolved
        // sendParticles = method_14199 but no visible particles).
        if (!invokeSendParticlesTargeted(level, player, opts,
                player.getX(), player.getY() + 1.0, player.getZ(),
                count, dx, dy, dz, speed)) {
            invokeSendParticles(level, opts,
                    player.getX(), player.getY() + 1.0, player.getZ(),
                    count, dx, dy, dz, speed);
        }
    }

    // Targeted overload of ServerLevel#sendParticles that takes a
    // ServerPlayer recipient as p[0]. Signatures across versions:
    //   (ServerPlayer, ParticleOptions, boolean overrideLimiter, x,y,z, count, dx,dy,dz, speed)             [≤1.21.4: 11 args, 1 bool]
    //   (ServerPlayer, ParticleOptions, boolean overrideLimiter, boolean alwaysShow, x,y,z, count, dx,dy,dz, speed) [1.21.5+: 12 args, 2 bool]
    private static volatile Method SEND_PARTICLES_TARGETED;
    private static volatile int SEND_PARTICLES_TARGETED_BOOL_PREFIX;
    private static volatile boolean SEND_PARTICLES_TARGETED_RESOLVED;

    private static boolean targetedSigMatches(Class<?>[] p, int boolPrefix) {
        int expected = 1 + 1 + boolPrefix + 3 + 1 + 4; // recipient + opts + bools + xyz + count + dx,dy,dz,speed
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
            synchronized (FabricParticleEffect.class) {
                if (!SEND_PARTICLES_TARGETED_RESOLVED) {
                    Method found = null;
                    int boolPrefix = 0;
                    for (int candPrefix = 2; candPrefix >= 0 && found == null; candPrefix--) {
                        for (Method m : ServerLevel.class.getMethods()) {
                            if (!targetedSigMatches(m.getParameterTypes(), candPrefix)) continue;
                            found = m;
                            boolPrefix = candPrefix;
                            break;
                        }
                    }
                    SEND_PARTICLES_TARGETED = found;
                    SEND_PARTICLES_TARGETED_BOOL_PREFIX = boolPrefix;
                    SEND_PARTICLES_TARGETED_RESOLVED = true;
                    if (found == null) {
                        System.err.println("[effects-api] [FabricParticleEffect] no targeted sendParticles"
                                + " (ServerPlayer,ParticleOptions,[bool[,bool]],xyz,count,4d) on "
                                + ServerLevel.class.getName() + "; falling back to broadcast");
                    } else {
                        System.err.println("[effects-api] [FabricParticleEffect] resolved targeted sendParticles: "
                                + found.getName() + " arity=" + found.getParameterCount()
                                + " boolPrefix=" + boolPrefix);
                    }
                }
            }
        }
        Method m = SEND_PARTICLES_TARGETED;
        if (m == null) return false;
        try {
            switch (SEND_PARTICLES_TARGETED_BOOL_PREFIX) {
                case 2:
                    // overrideLimiter=true so the client's particle limiter
                    // (e.g. "Decreased" particles setting) doesn't drop the
                    // packet; alwaysShow=true so the packet renders even if
                    // the recipient is briefly far from (x,y,z) right after
                    // a long teleport.
                    m.invoke(level, recipient, opts, true, true, x, y, z, count, dx, dy, dz, speed);
                    break;
                case 1:
                    m.invoke(level, recipient, opts, true, x, y, z, count, dx, dy, dz, speed);
                    break;
                default:
                    m.invoke(level, recipient, opts, x, y, z, count, dx, dy, dz, speed);
                    break;
            }
            return true;
        } catch (ReflectiveOperationException e) {
            System.err.println("[effects-api] [FabricParticleEffect] targeted sendParticles invoke failed via "
                    + m.getName() + ": " + (e.getCause() != null ? e.getCause() : e));
            return false;
        }
    }

    // Cross-version reflective dispatch for ServerLevel#sendParticles.
    // 1.21.x added a `boolean overrideLimiter` parameter, breaking the
    // 9-arg signature this module was compiled against (NoSuchMethodError
    // at runtime on newer servers).
    // Number of leading boolean params in the resolved overload:
    //   0 = (ParticleOptions, d,d,d, int, d,d,d,d)                            [legacy 9-arg]
    //   1 = (ParticleOptions, boolean overrideLimiter, d,d,d, int, d,d,d,d)   [1.21.x 10-arg]
    //   2 = (ParticleOptions, boolean overrideLimiter, boolean alwaysShow, d,d,d, int, d,d,d,d) [1.21.5+ 11-arg]
    // Resolved by signature (not by method name) so the scan succeeds on
    // intermediary-mapped runtimes where the method is e.g. method_14199.
    private static volatile Method SEND_PARTICLES;
    private static volatile int SEND_PARTICLES_BOOL_PREFIX;
    private static volatile boolean SEND_PARTICLES_RESOLVED;

    private static boolean particleSigMatches(Class<?>[] p, int boolPrefix) {
        int expected = 1 + boolPrefix + 3 + 1 + 4; // opts + bools + xyz + count + dx,dy,dz,speed
        if (p.length != expected) return false;
        if (!ParticleOptions.class.isAssignableFrom(p[0])) return false;
        int i = 1;
        for (int b = 0; b < boolPrefix; b++) {
            if (p[i++] != boolean.class) return false;
        }
        if (p[i++] != double.class || p[i++] != double.class || p[i++] != double.class) return false;
        if (p[i++] != int.class) return false;
        if (p[i++] != double.class || p[i++] != double.class || p[i++] != double.class || p[i] != double.class) return false;
        return true;
    }

    private static void resolveSendParticles() {
        if (SEND_PARTICLES_RESOLVED) return;
        synchronized (FabricParticleEffect.class) {
            if (SEND_PARTICLES_RESOLVED) return;
            Method found = null;
            int boolPrefix = 0;
            // Prefer the longest matching shape (newest API) so we route through
            // the canonical entry point on the running version.
            for (int candPrefix = 2; candPrefix >= 0 && found == null; candPrefix--) {
                for (Method m : ServerLevel.class.getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (!particleSigMatches(p, candPrefix)) continue;
                    found = m;
                    boolPrefix = candPrefix;
                    break;
                }
            }
            SEND_PARTICLES = found;
            SEND_PARTICLES_BOOL_PREFIX = boolPrefix;
            SEND_PARTICLES_RESOLVED = true;
            if (found == null) {
                StringBuilder avail = new StringBuilder();
                for (Method m : ServerLevel.class.getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length < 8 || p.length > 11) continue;
                    if (!ParticleOptions.class.isAssignableFrom(p[0])) continue;
                    avail.append(m.getName()).append('(');
                    for (int i = 0; i < p.length; i++) {
                        if (i > 0) avail.append(',');
                        avail.append(p[i].getSimpleName());
                    }
                    avail.append(")->").append(m.getReturnType().getSimpleName()).append("; ");
                }
                System.err.println("[effects-api] [FabricParticleEffect] no sendParticles overload matched on "
                        + ServerLevel.class.getName() + "; ParticleOptions-bearing candidates: " + avail);
            } else {
                System.err.println("[effects-api] [FabricParticleEffect] resolved sendParticles: "
                        + found.getName() + " arity=" + found.getParameterCount()
                        + " boolPrefix=" + boolPrefix);
            }
        }
    }

    private static void invokeSendParticles(ServerLevel level, ParticleOptions opts,
                                            double x, double y, double z, int count,
                                            double dx, double dy, double dz, double speed) {
        resolveSendParticles();
        Method m = SEND_PARTICLES;
        if (m == null) {
            System.err.println("[effects-api] [FabricParticleEffect] cannot send '" + opts
                    + "': no resolved sendParticles overload (see resolveSendParticles diagnostic above)");
            return;
        }
        try {
            switch (SEND_PARTICLES_BOOL_PREFIX) {
                case 2:
                    // overrideLimiter=false (don't bypass particle limiter), alwaysShow=true
                    // (ensure visibility regardless of distance settings).
                    m.invoke(level, opts, false, true, x, y, z, count, dx, dy, dz, speed);
                    break;
                case 1:
                    m.invoke(level, opts, false, x, y, z, count, dx, dy, dz, speed);
                    break;
                default:
                    m.invoke(level, opts, x, y, z, count, dx, dy, dz, speed);
                    break;
            }
        } catch (ReflectiveOperationException e) {
            // Particles are cosmetic; never break teleport. Log once with full
            // context so the operator can see why a resolved overload refused.
            System.err.println("[effects-api] [FabricParticleEffect] sendParticles invoke failed via "
                    + m.getName() + " arity=" + m.getParameterCount()
                    + " boolPrefix=" + SEND_PARTICLES_BOOL_PREFIX
                    + ": " + (e.getCause() != null ? e.getCause() : e));
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
