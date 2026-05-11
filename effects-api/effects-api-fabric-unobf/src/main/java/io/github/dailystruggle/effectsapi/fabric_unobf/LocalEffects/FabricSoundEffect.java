package io.github.dailystruggle.effectsapi.fabric_unobf.LocalEffects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.fabric_unobf.FabricEffectRuntimeUnobf;
import io.github.dailystruggle.effectsapi.fabric_unobf.LocalEffects.enums.FabricSoundKeys;
import io.github.dailystruggle.effectsapi.fabric_unobf.FabricRegistryCompat;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;
import java.util.EnumMap;

/**
 * Fabric counterpart of
 * {@code io.github.dailystruggle.effectsapi.bukkit.LocalEffects.SoundEffect}
 * per <em>effects-api-ADR-003</em>. Plays a Mojmap {@link SoundEvent} at the
 * target {@link ServerPlayer}'s current position via {@code ServerLevel#playSound}.
 */
public class FabricSoundEffect extends Effect<FabricSoundKeys> {

    private static final SoundEvent DEFAULT_SOUND = FabricRegistryCompat.resolve(
            BuiltInRegistries.SOUND_EVENT,
            Identifier.tryParse("minecraft:entity.player.levelup"));

    /**
     * One-shot guard so the first-run diagnostic in {@link #run()} prints
     * exactly once per JVM, not on every teleport. Volatile is sufficient —
     * a duplicate line under a startup race is harmless.
     */
    private static volatile boolean LOGGED_FIRST_RUN = false;

    public FabricSoundEffect() throws IllegalArgumentException {
        super(new EnumMap<>(FabricSoundKeys.class));
        EnumMap<FabricSoundKeys, Object> d = getData();
        d.put(FabricSoundKeys.TYPE, DEFAULT_SOUND);
        d.put(FabricSoundKeys.VOLUME, 100);
        d.put(FabricSoundKeys.PITCH, 100);
        d.put(FabricSoundKeys.DX, 0.0);
        d.put(FabricSoundKeys.DY, 0.0);
        d.put(FabricSoundKeys.DZ, 0.0);
        this.data = d;
        this.defaults = d.clone();
    }

    @Override
    public void run() {
        // One-shot diagnostic: prove run() is being entered at all and
        // whether a per-version SoundDispatcher is currently registered.
        // User-reported "no logs, no audio, no particles" on 1.21.11 is
        // ambiguous between (a) run() never invoked and (b) dispatcher
        // path silently succeeding without producing audio. This line
        // disambiguates the two on the next deploy. Suppress repeats per
        // teleport burst by gating on a static volatile flag.
        if (!LOGGED_FIRST_RUN) {
            LOGGED_FIRST_RUN = true;
            FabricEffectRuntimeUnobf.SoundDispatcher d = FabricEffectRuntimeUnobf.getSoundDispatcher();
            System.err.println("[effects-api] [FabricSoundEffect] first run() — dispatcher="
                    + (d == null ? "<none, will use reflective fallback>" : d.getClass().getName())
                    + " target=" + (target == null ? "null" : target.getClass().getName()));
        }
        if (!(target instanceof ServerPlayer)) {
            System.err.println("[effects-api] [FabricSoundEffect] skip: target is not ServerPlayer (got "
                    + (target == null ? "null" : target.getClass().getName()) + ")");
            return;
        }
        ServerPlayer player = (ServerPlayer) target;
        // NOTE: do NOT resolve a ServerLevel here. On MC 1.21.11 mojmap,
        // Entity#level() (method_37908) was removed/renamed, and any
        // call to it from this effects-api/-1.21.1-compiled bytecode
        // throws NoSuchMethodError BEFORE we reach the dispatcher path,
        // which is exactly the "no audio, no particles" silent failure
        // the per-version dispatcher SPI exists to avoid. The registered
        // dispatcher (rtp-fabric-v*) uses Loom-mapped vanilla calls and
        // resolves the level itself; we only need the level for the
        // reflective fallback further below.

        Object typeObj = data.get(FabricSoundKeys.TYPE);
        if (!(typeObj instanceof SoundEvent)) {
            System.err.println("[effects-api] [FabricSoundEffect] skip: TYPE is not SoundEvent (got "
                    + (typeObj == null ? "null" : typeObj.getClass().getName()) + "=" + typeObj + ")");
            return;
        }
        SoundEvent sound = (SoundEvent) typeObj;

        float volume = numAsFloat(data.get(FabricSoundKeys.VOLUME), 100) / 100f;
        float pitch  = numAsFloat(data.get(FabricSoundKeys.PITCH), 100) / 100f;
        double dx = numAsDouble(data.get(FabricSoundKeys.DX), 0.0);
        double dy = numAsDouble(data.get(FabricSoundKeys.DY), 0.0);
        double dz = numAsDouble(data.get(FabricSoundKeys.DZ), 0.0);

        // Preferred path: a per-version Loom adapter (rtp-fabric-v*) has
        // registered a SoundDispatcher that calls the mapped vanilla API
        // directly (no reflection, version-stable). When nothing is
        // registered we fall through to the in-tree reflective resolvers
        // below, which preserves bug-for-bug behavior on un-adapted
        // runtimes (e.g. brand-new MC version before a v* module ships).
        FabricEffectRuntimeUnobf.SoundDispatcher dispatcher = FabricEffectRuntimeUnobf.getSoundDispatcher();
        if (dispatcher != null) {
            try {
                dispatcher.play(player, sound, SoundSource.MASTER,
                        player.getX() + dx, player.getY() + dy, player.getZ() + dz,
                        volume, pitch);
                return;
            } catch (Throwable e) {
                // Sound is cosmetic; never break teleport. Catch Throwable
                // (not just RuntimeException) so a LinkageError /
                // NoSuchMethodError / NoClassDefFoundError thrown from a
                // Loom-mapped per-version dispatcher (e.g. an Entity#level()
                // intermediary missing on a brand-new MC patch) is logged
                // and falls back instead of propagating up uncaught and
                // silently aborting all subsequent dispatch attempts on
                // this teleport — the user-reported "no logs, particles
                // worked once then never again" symptom on 1.21.11.
                System.err.println("[effects-api] [FabricSoundEffect] registered SoundDispatcher threw "
                        + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + "; falling back to reflective dispatch");
            }
        }

        // Send the sound DIRECTLY to this player's connection rather than
        // broadcasting via ServerLevel#playSound. After a long teleport the
        // player is briefly outside the destination chunk's tracker, so
        // tracker-broadcast packets are dropped client-side and the sound
        // is silently inaudible (matches user-reported 1.21.11 behavior,
        // 2026-05-07: resolved playSound = method_8465 but no audio).
        // ServerPlayer#playNotifySound (Yarn `playSoundToPlayer`) is the
        // stable, version-agnostic targeted entry point.
        if (invokePlayNotifySound(player, sound, SoundSource.MASTER, volume, pitch)) return;
        // Fallback A: send a ClientboundSoundPacket directly to the
        // player's connection. This bypasses the chunk tracker, which
        // drops broadcast packets when the player is briefly outside
        // the destination chunk's tracking radius right after a long
        // teleport (user-confirmed 1.21.11 symptom: particles via the
        // targeted ServerLevel#sendParticles overload work, but
        // ServerLevel#playSound is silently inaudible).
        if (sendSoundPacket(player, sound, SoundSource.MASTER,
                player.getX() + dx, player.getY() + dy, player.getZ() + dz,
                volume, pitch)) return;
        // Fallback B: level broadcast (legacy path; may still be
        // dropped post-teleport but it's the last resort). Resolve the
        // level lazily here, guarded against the 1.21.11
        // NoSuchMethodError described above — failing this lookup just
        // skips the legacy broadcast, which is acceptable since the
        // dispatcher and packet paths above have already had their
        // chance.
        ServerLevel level;
        try {
            level = (ServerLevel) player.level();
        } catch (NoSuchMethodError | ClassCastException e) {
            System.err.println("[effects-api] [FabricSoundEffect] reflective fallback B skipped: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        if (level == null) return;
        invokePlaySound(level, player.getX() + dx, player.getY() + dy, player.getZ() + dz,
                sound, SoundSource.MASTER, volume, pitch);
    }

    // Reflectively-resolved direct-to-connection ClientboundSoundPacket
    // fallback. Constructor signature evolved across MC versions:
    //   ≤1.20.1: (SoundEvent, SoundSource, double x, double y, double z, float vol, float pitch)
    //   1.20.2+: (Holder<SoundEvent>, SoundSource, double x, double y, double z, float vol, float pitch, long seed)
    // Resolve by parameter shape so intermediary-mapped runtimes bind too.
    private static volatile java.lang.reflect.Constructor<?> SOUND_PACKET_CTOR;
    private static volatile boolean SOUND_PACKET_HOLDER;
    private static volatile boolean SOUND_PACKET_HAS_SEED;
    private static volatile boolean SOUND_PACKET_RESOLVED;
    private static volatile java.lang.reflect.Method CONNECTION_SEND;
    private static volatile java.lang.reflect.Field CONNECTION_FIELD;

    private static boolean sendSoundPacket(ServerPlayer player, SoundEvent sound, SoundSource source,
                                           double x, double y, double z, float volume, float pitch) {
        if (!SOUND_PACKET_RESOLVED) {
            synchronized (FabricSoundEffect.class) {
                if (!SOUND_PACKET_RESOLVED) {
                    try {
                        Class<?> packetCls = null;
                        // Try Mojmap name first, then intermediary, then yarn (just in case).
                        String[] candidates = {
                                "net.minecraft.network.protocol.game.ClientboundSoundPacket",
                                "net.minecraft.class_2767",
                                "net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket"
                        };
                        Throwable lastErr = null;
                        for (String name : candidates) {
                            try { packetCls = Class.forName(name); break; }
                            catch (ClassNotFoundException cnfe) { lastErr = cnfe; }
                        }
                        if (packetCls == null) {
                            throw (lastErr != null) ? lastErr
                                    : new ClassNotFoundException("ClientboundSoundPacket");
                        }
                        java.lang.reflect.Constructor<?> best = null;
                        boolean bestHolder = false;
                        boolean bestSeed = false;
                        int bestLen = 0;
                        for (java.lang.reflect.Constructor<?> c : packetCls.getConstructors()) {
                            Class<?>[] p = c.getParameterTypes();
                            if (p.length != 7 && p.length != 8) continue;
                            boolean holder;
                            if (SoundEvent.class.isAssignableFrom(p[0])) holder = false;
                            else if (Holder.class.isAssignableFrom(p[0])) holder = true;
                            else continue;
                            if (!SoundSource.class.isAssignableFrom(p[1])) continue;
                            if (p[2] != double.class || p[3] != double.class || p[4] != double.class) continue;
                            if (p[5] != float.class || p[6] != float.class) continue;
                            if (p.length == 8 && p[7] != long.class) continue;
                            // Prefer the longest signature available.
                            if (best == null || p.length > bestLen) {
                                best = c;
                                bestLen = p.length;
                                bestHolder = holder;
                                bestSeed = (p.length == 8);
                            }
                        }
                        SOUND_PACKET_CTOR = best;
                        SOUND_PACKET_HOLDER = bestHolder;
                        SOUND_PACKET_HAS_SEED = bestSeed;
                        if (best != null) {
                            System.err.println("[effects-api] [FabricSoundEffect] resolved ClientboundSoundPacket"
                                    + " ctor: arity=" + bestLen + " holder=" + bestHolder
                                    + " hasSeed=" + bestSeed);
                        } else {
                            System.err.println("[effects-api] [FabricSoundEffect] no ClientboundSoundPacket"
                                    + " ctor matched (Holder|SoundEvent,SoundSource,d,d,d,f,f[,long])");
                        }
                        // Resolve ServerPlayer.connection field + send(Packet) method
                        // by signature, since on intermediary runtimes the
                        // field name is e.g. field_xxxxx.
                        for (java.lang.reflect.Field f : ServerPlayer.class.getFields()) {
                            String n = f.getType().getName();
                            if (n.endsWith("ServerGamePacketListenerImpl")
                                    || n.endsWith("class_3244")) {
                                CONNECTION_FIELD = f;
                                break;
                            }
                        }
                        if (CONNECTION_FIELD == null) {
                            // Fall back to scanning declared fields (private + walking up).
                            Class<?> cls = ServerPlayer.class;
                            outer:
                            while (cls != null) {
                                for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                                    String n = f.getType().getName();
                                    if (n.endsWith("ServerGamePacketListenerImpl")
                                            || n.endsWith("class_3244")) {
                                        f.setAccessible(true);
                                        CONNECTION_FIELD = f;
                                        break outer;
                                    }
                                }
                                cls = cls.getSuperclass();
                            }
                        }
                        if (CONNECTION_FIELD != null) {
                            for (java.lang.reflect.Method mm : CONNECTION_FIELD.getType().getMethods()) {
                                if (mm.getParameterCount() != 1) continue;
                                if (!"send".equals(mm.getName())
                                        && !mm.getName().startsWith("method_")) continue;
                                Class<?> pt = mm.getParameterTypes()[0];
                                String pn = pt.getName();
                                if (pn.endsWith("Packet") || pn.endsWith("class_2596")) {
                                    CONNECTION_SEND = mm;
                                    if ("send".equals(mm.getName())) break;
                                }
                            }
                        }
                        if (CONNECTION_FIELD == null || CONNECTION_SEND == null) {
                            System.err.println("[effects-api] [FabricSoundEffect] could not resolve"
                                    + " ServerPlayer.connection / send(Packet); packet fallback disabled");
                        }
                    } catch (Throwable t) {
                        System.err.println("[effects-api] [FabricSoundEffect] sound-packet resolve failed: " + t);
                    } finally {
                        SOUND_PACKET_RESOLVED = true;
                    }
                }
            }
        }
        java.lang.reflect.Constructor<?> ctor = SOUND_PACKET_CTOR;
        if (ctor == null || CONNECTION_FIELD == null || CONNECTION_SEND == null) return false;
        try {
            // Use a registry-keyed reference Holder (not Holder.direct) when
            // the constructor expects Holder<SoundEvent>. Vanilla clients
            // decode direct holders as one-off SoundEvents whose Identifier
            // is treated as a custom sound path; for built-in sounds this
            // produces a silent miss because the client tries to load it as
            // a resource-pack file rather than dispatching the registered
            // SoundEvent (user-reported 1.21.11 symptom: ctor resolves,
            // packet sends, no audio). wrapAsHolder returns the registered
            // reference when available and falls back to direct otherwise.
            Object soundArg = SOUND_PACKET_HOLDER
                    ? BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound)
                    : sound;
            Object packet = SOUND_PACKET_HAS_SEED
                    ? ctor.newInstance(soundArg, source, x, y, z, volume, pitch,
                            ((ServerLevel) player.level()).getRandom().nextLong())
                    : ctor.newInstance(soundArg, source, x, y, z, volume, pitch);
            Object conn = CONNECTION_FIELD.get(player);
            if (conn == null) return false;
            CONNECTION_SEND.invoke(conn, packet);
            return true;
        } catch (ReflectiveOperationException e) {
            System.err.println("[effects-api] [FabricSoundEffect] sound-packet send failed: "
                    + (e.getCause() != null ? e.getCause() : e));
            return false;
        }
    }

    // Direct-to-player sound dispatch via ServerPlayer#playNotifySound
    // (Mojmap; Yarn `playSoundToPlayer`). Stable signature
    // (SoundEvent, SoundSource, float, float)->void across 1.20–1.21.11.
    // Resolved by signature so intermediary-mapped runtimes (where the
    // method name is e.g. method_xxxxx) also bind.
    private static volatile Method NOTIFY_SOUND;
    private static volatile boolean NOTIFY_SOUND_HOLDER;
    private static volatile boolean NOTIFY_SOUND_RESOLVED;

    private static boolean invokePlayNotifySound(ServerPlayer player, SoundEvent sound,
                                                 SoundSource source, float volume, float pitch) {
        Method m = NOTIFY_SOUND;
        if (!NOTIFY_SOUND_RESOLVED) {
            synchronized (FabricSoundEffect.class) {
                if (!NOTIFY_SOUND_RESOLVED) {
                    Method found = null;
                    boolean foundHolder = false;
                    StringBuilder avail = new StringBuilder();
                    StringBuilder allFour = new StringBuilder();
                    // Walk the class hierarchy via getDeclaredMethods() so
                    // we also pick up methods that getMethods() may omit
                    // on intermediary-remapped runtimes (observed on
                    // 1.21.11: getMethods() returned no 4-arg SoundSource
                    // candidate even though playSoundToPlayer is inherited
                    // from PlayerEntity/class_1657).
                    java.util.LinkedHashSet<Method> all = new java.util.LinkedHashSet<>();
                    for (Method mm : ServerPlayer.class.getMethods()) all.add(mm);
                    for (Class<?> c = ServerPlayer.class; c != null && c != Object.class; c = c.getSuperclass()) {
                        for (Method mm : c.getDeclaredMethods()) {
                            int mod = mm.getModifiers();
                            if (java.lang.reflect.Modifier.isStatic(mod)) continue;
                            if (java.lang.reflect.Modifier.isPrivate(mod)) continue;
                            all.add(mm);
                        }
                    }
                    for (Method cand : all) {
                        Class<?>[] p = cand.getParameterTypes();
                        if (p.length != 4) continue;
                        if (cand.getReturnType() != void.class) continue;
                        // Diagnostic: track every 4-arg void method we see
                        // so the failure log shows what was actually present.
                        if (allFour.length() < 1024) {
                            allFour.append(cand.getDeclaringClass().getSimpleName()).append('#')
                                    .append(cand.getName()).append('(');
                            for (int i = 0; i < p.length; i++) {
                                if (i > 0) allFour.append(',');
                                allFour.append(p[i].getSimpleName());
                            }
                            allFour.append("); ");
                        }
                        if (!SoundSource.class.isAssignableFrom(p[1])) continue;
                        if (p[2] != float.class || p[3] != float.class) continue;
                        boolean holder;
                        if (SoundEvent.class.isAssignableFrom(p[0])) {
                            holder = false;
                        } else if (Holder.class.isAssignableFrom(p[0])) {
                            holder = true;
                        } else continue;
                        // Track candidates for diagnostic when nothing wins.
                        avail.append(cand.getName()).append('(').append(p[0].getSimpleName())
                                .append(",SoundSource,f,f); ");
                        // Prefer raw-SoundEvent overload when both exist
                        // (avoids unnecessary Holder wrapping); break early
                        // if found.
                        if (found == null || (foundHolder && !holder)) {
                            try { cand.setAccessible(true); } catch (Throwable ignored) {}
                            found = cand;
                            foundHolder = holder;
                            if (!holder) break;
                        }
                    }
                    NOTIFY_SOUND = found;
                    NOTIFY_SOUND_HOLDER = foundHolder;
                    NOTIFY_SOUND_RESOLVED = true;
                    if (found == null) {
                        System.err.println("[effects-api] [FabricSoundEffect] no playNotifySound"
                                + " overload (SoundEvent|Holder,SoundSource,f,f)->void on "
                                + ServerPlayer.class.getName()
                                + "; 4-arg SoundSource-bearing candidates: "
                                + (avail.length() == 0 ? "<none>" : avail.toString())
                                + "; all 4-arg void methods scanned: "
                                + (allFour.length() == 0 ? "<none>" : allFour.toString())
                                + "falling back to packet send");
                    } else {
                        System.err.println("[effects-api] [FabricSoundEffect] resolved playNotifySound: "
                                + found.getDeclaringClass().getSimpleName() + "#" + found.getName()
                                + " holder=" + foundHolder);
                    }
                    m = found;
                }
            }
        }
        if (m == null) m = NOTIFY_SOUND;
        if (m == null) return false;
        try {
            Object soundArg = NOTIFY_SOUND_HOLDER
                    ? BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound)
                    : sound;
            m.invoke(player, soundArg, source, volume, pitch);
            return true;
        } catch (ReflectiveOperationException e) {
            System.err.println("[effects-api] [FabricSoundEffect] playNotifySound invoke failed via "
                    + m.getName() + ": " + (e.getCause() != null ? e.getCause() : e));
            return false;
        }
    }

    // Cross-version reflective dispatch for ServerLevel#playSound. The
    // 8-arg shape (Player, double, double, double, SoundEvent, SoundSource,
    // float, float) was stable through 1.21.x but 1.21.11 (and some
    // intermediate snapshots) added/removed parameters — notably a trailing
    // `long seed`, and on certain lines a `Holder<SoundEvent>` overload.
    // Compiled bytecode bound to the 1.21.1 signature throws
    // NoSuchMethodError on those servers (see user-reported stack trace,
    // 2026-05-07: method_43128 missing on 1.21.11). Resolve by signature
    // among public `playSound`-named methods at first call.
    private static volatile Method PLAY_SOUND;
    private static volatile boolean PLAY_SOUND_HAS_SEED;
    private static volatile boolean PLAY_SOUND_HOLDER;
    private static volatile boolean PLAY_SOUND_RESOLVED;

    private static void resolvePlaySound() {
        if (PLAY_SOUND_RESOLVED) return;
        synchronized (FabricSoundEffect.class) {
            if (PLAY_SOUND_RESOLVED) return;
            // Track best candidate per (length, holder) combo. Param[4] may
            // be raw SoundEvent (≤1.21.4) or Holder<SoundEvent> (1.21.5+).
            // Param length is 8 (≤1.21.10) or 9 with trailing `long seed`
            // (1.21.11+). Filter strictly by signature so the scan also
            // succeeds on intermediary-mapped runtimes where the method
            // name is not "playSound".
            Method best = null;
            int bestLen = 0;
            boolean bestHolder = false;
            for (Method m : ServerLevel.class.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 8 && p.length != 9) continue;
                // First param: Entity (nullable; Player on ≤1.21.4, Entity on 1.21.5+).
                if (!Entity.class.isAssignableFrom(p[0])) continue;
                if (p[1] != double.class || p[2] != double.class || p[3] != double.class) continue;
                boolean holder;
                if (SoundEvent.class.isAssignableFrom(p[4])) {
                    holder = false;
                } else if (Holder.class.isAssignableFrom(p[4])) {
                    holder = true;
                } else continue;
                if (!SoundSource.class.isAssignableFrom(p[5])) continue;
                if (p[6] != float.class || p[7] != float.class) continue;
                if (p.length == 9 && p[8] != long.class) continue;
                // Prefer the longest signature available; this is the
                // canonical entry point on the running version (shorter
                // overloads typically forward into it).
                if (m.getReturnType() != void.class) continue;
                if (best == null || p.length > bestLen) {
                    best = m;
                    bestLen = p.length;
                    bestHolder = holder;
                }
            }
            PLAY_SOUND = best;
            PLAY_SOUND_HAS_SEED = (bestLen == 9);
            PLAY_SOUND_HOLDER = bestHolder;
            PLAY_SOUND_RESOLVED = true;
            if (best == null) {
                StringBuilder avail = new StringBuilder();
                for (Method m : ServerLevel.class.getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length < 7 || p.length > 10) continue;
                    if (!SoundSource.class.isAssignableFrom(p[Math.min(5, p.length - 1)])
                            && !(p.length >= 6 && SoundSource.class.isAssignableFrom(p[5]))) continue;
                    avail.append(m.getName()).append('(');
                    for (int i = 0; i < p.length; i++) {
                        if (i > 0) avail.append(',');
                        avail.append(p[i].getSimpleName());
                    }
                    avail.append(")->").append(m.getReturnType().getSimpleName()).append("; ");
                }
                System.err.println("[effects-api] [FabricSoundEffect] no playSound overload matched signature "
                        + "(Entity,d,d,d,SoundEvent|Holder,SoundSource,f,f[,long]) on "
                        + ServerLevel.class.getName()
                        + "; SoundSource-bearing candidates: " + avail);
            } else {
                System.err.println("[effects-api] [FabricSoundEffect] resolved playSound: "
                        + best.getName() + " arity=" + bestLen + " holder=" + bestHolder
                        + " hasSeed=" + (bestLen == 9));
            }
        }
    }

    private static void invokePlaySound(ServerLevel level, double x, double y, double z,
                                        SoundEvent sound, SoundSource source,
                                        float volume, float pitch) {
        resolvePlaySound();
        Method m = PLAY_SOUND;
        if (m == null) {
            System.err.println("[effects-api] [FabricSoundEffect] cannot play '" + sound
                    + "': no resolved playSound overload (see resolvePlaySound diagnostic above)");
            return;
        }
        Object soundArg = PLAY_SOUND_HOLDER ? Holder.direct(sound) : sound;
        try {
            if (PLAY_SOUND_HAS_SEED) {
                m.invoke(level, null, x, y, z, soundArg, source, volume, pitch, level.getRandom().nextLong());
            } else {
                m.invoke(level, null, x, y, z, soundArg, source, volume, pitch);
            }
        } catch (ReflectiveOperationException e) {
            // Sound is cosmetic; never break teleport. Log once with full
            // context so the operator can see why a resolved overload still
            // refused the call (e.g. unexpected Holder vs raw mismatch).
            System.err.println("[effects-api] [FabricSoundEffect] playSound invoke failed via "
                    + m.getName() + " arity=" + m.getParameterCount()
                    + " holder=" + PLAY_SOUND_HOLDER + " hasSeed=" + PLAY_SOUND_HAS_SEED
                    + ": " + (e.getCause() != null ? e.getCause() : e));
        }
    }

    @Override
    public String toPermission() {
        return String.valueOf(data.get(FabricSoundKeys.TYPE)) + "."
                + data.get(FabricSoundKeys.VOLUME) + "."
                + data.get(FabricSoundKeys.PITCH) + "."
                + data.get(FabricSoundKeys.DX) + "."
                + data.get(FabricSoundKeys.DY) + "."
                + data.get(FabricSoundKeys.DZ);
    }

    @Override
    public void setData(String... data) {
        applyByType(KEY_ORDER, data);
    }

    private static final FabricSoundKeys[] KEY_ORDER = {
            FabricSoundKeys.TYPE, FabricSoundKeys.VOLUME, FabricSoundKeys.PITCH,
            FabricSoundKeys.DX, FabricSoundKeys.DY, FabricSoundKeys.DZ
    };

    private static float numAsFloat(Object o, float fallback) {
        return (o instanceof Number) ? ((Number) o).floatValue() : fallback;
    }
    private static double numAsDouble(Object o, double fallback) {
        return (o instanceof Number) ? ((Number) o).doubleValue() : fallback;
    }
}
