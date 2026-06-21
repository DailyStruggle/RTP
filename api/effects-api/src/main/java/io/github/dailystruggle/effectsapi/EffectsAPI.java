package io.github.dailystruggle.effectsapi;

import io.github.dailystruggle.effectsapi.bukkit.BukkitListeners.FireworkSafetyListener;
import io.github.dailystruggle.effectsapi.bukkit.BukkitListeners.GlideSafetyListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Bukkit-platform entry point for effects-api. <strong>This class is
 * Bukkit-only, not a backend-neutral facade.</strong> Despite living at the
 * module's root package and carrying the {@code EffectsAPI} name, it imports
 * {@code org.bukkit.*} and registers Bukkit event listeners, so it can only be
 * loaded on a Bukkit-family runtime (Spigot / Paper / Folia). It is not used by
 * the Fabric platform and will not load there.
 *
 * <p>The backend-independent surface of this module is
 * {@code io.github.dailystruggle.effectsapi.common.*} (the platform-neutral SPI
 * and base types: {@code Effect}, {@code EffectFactory}, {@code spi.EffectRuntime},
 * {@code PlayerHandle}/{@code LocationHandle}, etc.), which carries no
 * {@code org.bukkit.*} or {@code net.minecraft.*} imports. Per-platform
 * initialization lives in the sibling subpackages: Bukkit uses this class plus
 * {@code effectsapi.bukkit.BukkitEffectsInitializer}; Fabric uses
 * {@code effectsapi.fabric.FabricEffectsInitializer} (see effects-api-ADR-003).
 * The root-package placement and generic name of this class are retained for
 * back-compat with {@code compileOnly} addons that already link against
 * {@code io.github.dailystruggle.effectsapi.EffectsAPI}; treat its true home as
 * the {@code bukkit} subpackage.
 *
 * <p>This class is shaded into the host plugin under its real package
 * ({@code io.github.dailystruggle.effectsapi.*}, un-relocated) so that
 * {@code compileOnly} addons can link against it at runtime. Because the
 * package is no longer relocated, two distinct plugins on the same server may
 * end up sharing a single copy of this class (whichever classloader resolves
 * it first wins). To stay correct under that condition the per-plugin state
 * (the firework / glide safety listeners and the owning {@link Plugin}) is
 * keyed by the owning plugin in {@link #STATES} rather than held in a single
 * global static. The "owning plugin" of a given call is resolved from the call
 * stack via {@link JavaPlugin#getProvidingPlugin(Class)} so callers do not have
 * to thread a {@link Plugin} reference through every API entry point.
 */
public final class EffectsAPI {

    /**
     * Per-owning-plugin listener state. Keyed by the {@link Plugin} passed to
     * {@link #init(Plugin)} so that two plugins sharing one shaded copy of this
     * class each register (and tear down) their own Bukkit listeners.
     */
    private static final Map<Plugin, PluginState> STATES = new ConcurrentHashMap<>();

    private static final StackWalker STACK_WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /** Listeners owned by a single plugin. */
    private static final class PluginState {
        final FireworkSafetyListener fireworkSafetyListener;
        final GlideSafetyListener glideSafetyListener;

        PluginState(FireworkSafetyListener fireworkSafetyListener,
                    GlideSafetyListener glideSafetyListener) {
            this.fireworkSafetyListener = fireworkSafetyListener;
            this.glideSafetyListener = glideSafetyListener;
        }
    }

    //server version checking
    private static String version = null;
    private static Integer intVersion = null;

    private EffectsAPI() {

    }

    /**
     * @return the {@link Plugin} that owns the current call (resolved from the
     *         call stack), the sole initialized plugin if the caller cannot be
     *         attributed, or some initialized plugin as a best-effort fallback
     *         when several are registered; never null.
     * @throws IllegalStateException if {@link #init(Plugin)} has not been called
     *         by any plugin yet (S-006).
     */
    @NotNull
    public static Plugin getInstance() {
        Plugin local = resolveOwner();
        if (local == null) {
            throw new IllegalStateException(
                    "EffectsAPI.getInstance() called before EffectsAPI.init(Plugin)");
        }
        return local;
    }

    /**
     * Self-populating variant of {@link #getInstance()}. If no plugin has
     * called {@link #init(Plugin)} yet, the supplied {@code fallback} is used to
     * initialize the API (idempotent) and is then returned. Lets in-repo
     * callers that already hold a {@link Plugin} reference (e.g. the RTP
     * plugin instance) avoid the {@link IllegalStateException} thrown by the
     * no-arg overload during early lifecycle windows, without {@code effects-api}
     * having to take a hard dependency on RTP.
     *
     * @param fallback plugin to initialize with if nothing is registered; may be {@code null}
     * @return the resolved {@link Plugin} instance
     * @throws IllegalStateException if nothing is registered and {@code fallback} is {@code null}
     */
    @NotNull
    public static Plugin getInstance(Plugin fallback) {
        Plugin local = resolveOwner();
        if (local != null) return local;
        if (fallback == null) {
            throw new IllegalStateException(
                    "EffectsAPI.getInstance(fallback) called before init(Plugin) with null fallback");
        }
        init(fallback);
        return fallback;
    }

    /**
     * @return the {@link FireworkSafetyListener} of the resolved owning plugin
     *         (see {@link #getInstance()}), or {@code null} if none is registered.
     */
    @Nullable
    public static FireworkSafetyListener getFireworkSafetyListener() {
        Plugin owner = resolveOwner();
        if (owner == null) return null;
        PluginState state = STATES.get(owner);
        return state == null ? null : state.fireworkSafetyListener;
    }

    /**
     * @return the {@link GlideSafetyListener} of the resolved owning plugin
     *         (see {@link #getInstance()}), or {@code null} if none is registered.
     */
    @Nullable
    public static GlideSafetyListener getGlideSafetyListener() {
        Plugin owner = resolveOwner();
        if (owner == null) return null;
        PluginState state = STATES.get(owner);
        return state == null ? null : state.glideSafetyListener;
    }

    /**
     * Resolve the {@link Plugin} that owns the current call. Walks the stack for
     * the first frame outside {@code io.github.dailystruggle.effectsapi} and maps
     * its class to the providing plugin; falls back to the sole registered
     * plugin, then to any registered plugin, then to {@code null}.
     */
    @Nullable
    private static Plugin resolveOwner() {
        Plugin resolved = resolveCaller();
        if (resolved != null) return resolved;
        // Best-effort fallback: a single initialized plugin is unambiguous.
        if (STATES.size() == 1) return STATES.keySet().iterator().next();
        // Several plugins share this copy and the caller could not be attributed
        // (e.g. mid-dispatch where every frame is effects-api's own). Return one
        // so scheduling still has a valid plugin handle; per-plugin listener
        // registration / teardown remains correct regardless.
        for (Plugin p : STATES.keySet()) return p;
        return null;
    }

    @Nullable
    private static Plugin resolveCaller() {
        try {
            Class<?> caller = STACK_WALKER.walk(frames -> frames
                    .map(StackWalker.StackFrame::getDeclaringClass)
                    .filter(c -> !c.getName().startsWith("io.github.dailystruggle.effectsapi"))
                    .findFirst()
                    .orElse(null));
            if (caller == null) return null;
            Plugin providing = JavaPlugin.getProvidingPlugin(caller);
            if (providing != null && STATES.containsKey(providing)) return providing;
        } catch (Throwable ignored) {
            // getProvidingPlugin throws for classes not loaded by a plugin
            // classloader (core server / test classes); treat as unresolved.
        }
        return null;
    }

    private static final Pattern versionPattern = Pattern.compile( "[-+^.a-zA-Z]*",Pattern.CASE_INSENSITIVE );
    private static String getServerVersion() {
        if ( version == null ) {
            version = Bukkit.getServer().getClass().getPackage().getName();
            if(!version.contains("1_")) {
                String bukkitVersion = Bukkit.getServer().getBukkitVersion();

                int end = bukkitVersion.indexOf("-R");
                if(end < 0) return "1_13_2";

                bukkitVersion = bukkitVersion.substring(0,end).replaceAll("\\.","_");
                return bukkitVersion;
            }
            else version = versionPattern.matcher( version ).replaceAll( "" );
        }

        return version;
    }

    public static Integer getServerIntVersion() {
        if ( intVersion == null ) {
            String[] splitVersion = getServerVersion().split( "_" );
            if ( splitVersion.length == 0 ) {
                intVersion = 1;
            } else if ( splitVersion.length == 1 ) {
                try {
                    intVersion = Integer.valueOf( splitVersion[0] );
                } catch (NumberFormatException e) {
                    Bukkit.getLogger().log(Level.SEVERE, "expected number, received - " + splitVersion[0]);
                    Bukkit.getLogger().log(Level.SEVERE, "full string - " + getServerVersion());
                    e.printStackTrace();
                    intVersion = 1;
                }
            } else {
                try {
                    intVersion = Integer.valueOf( splitVersion[1] );
                } catch (NumberFormatException e) {
                    Bukkit.getLogger().log(Level.SEVERE, "expected number, received - " + splitVersion[1]);
                    Bukkit.getLogger().log(Level.SEVERE, "full string - " + getServerVersion());
                    e.printStackTrace();
                    intVersion = 1;
                }
            }
        }
        return intVersion;
    }

    /**
     * Initialize effects-api for {@code caller}. Registers that plugin's own
     * firework / glide safety listeners. Idempotent per plugin: calling again
     * for the same plugin is a no-op, and distinct plugins each get their own
     * listeners (so two plugins sharing one shaded copy of this class do not
     * clobber each other).
     */
    public static void init(Plugin caller) {
        if (caller == null) return;
        STATES.computeIfAbsent(caller, c -> {
            FireworkSafetyListener firework = new FireworkSafetyListener(c);
            Bukkit.getPluginManager().registerEvents(firework, c);
            // GlideEffect (effects-api-ADR-001): tracks gliders, suppresses
            // firework rockets when configured, and places players on
            // shutdown via placeAllOnShutdown().
            GlideSafetyListener glide = new GlideSafetyListener(c);
            Bukkit.getPluginManager().registerEvents(glide, c);
            return new PluginState(firework, glide);
        });
    }

    /**
     * Plugin-disable hook. Expected to be called from a plugin's
     * {@code onDisable()} so still-gliding players can be placed at the highest
     * safe block below them before the server stops. Tears down only the
     * resolved caller's state; if the caller cannot be attributed, every
     * registered plugin's state is disabled (server shutdown). Idempotent.
     */
    public static void disable() {
        Plugin caller = resolveCaller();
        if (caller != null) {
            disable(caller);
            return;
        }
        for (Plugin p : new ArrayList<>(STATES.keySet())) {
            disable(p);
        }
    }

    /**
     * Plugin-disable hook for an explicit {@code caller}. Places that plugin's
     * still-gliding players and removes its state. Idempotent.
     */
    public static void disable(Plugin caller) {
        if (caller == null) return;
        PluginState state = STATES.remove(caller);
        if (state == null || state.glideSafetyListener == null) return;
        try {
            state.glideSafetyListener.placeAllOnShutdown();
        } catch (Throwable t) {
            Bukkit.getLogger().log(Level.WARNING,
                    "EffectsAPI.disable: GlideEffect shutdown placement threw", t);
        }
    }
}
