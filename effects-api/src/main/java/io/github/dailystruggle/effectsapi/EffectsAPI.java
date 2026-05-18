package io.github.dailystruggle.effectsapi;

import io.github.dailystruggle.effectsapi.bukkit.BukkitListeners.FireworkSafetyListener;
import io.github.dailystruggle.effectsapi.bukkit.BukkitListeners.GlideSafetyListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;
import java.util.regex.Pattern;

//reference point for
public final class EffectsAPI {
    public static Plugin instance = null;
    //referentiable listener, for updating
    public static FireworkSafetyListener fireworkSafetyListener = null;
    public static GlideSafetyListener glideSafetyListener = null;
    //server version checking
    private static String version = null;
    private static Integer intVersion = null;

    private EffectsAPI() {

    }

    /**
     * @return instance of plugin set via {@link #init(Plugin)}; never null.
     * @throws IllegalStateException if {@link #init(Plugin)} has not been called yet (S-006).
     */
    @NotNull
    public static Plugin getInstance() {
        Plugin local = instance;
        if (local == null) {
            throw new IllegalStateException(
                    "EffectsAPI.getInstance() called before EffectsAPI.init(Plugin)");
        }
        return local;
    }

    /**
     * Self-populating variant of {@link #getInstance()}. If {@link #init(Plugin)}
     * has not been called yet, the supplied {@code fallback} is used to
     * initialize the API (idempotent) and is then returned. Lets in-repo
     * callers that already hold a {@link Plugin} reference (e.g. the RTP
     * plugin instance) avoid the {@link IllegalStateException} thrown by the
     * no-arg overload during early lifecycle windows, without {@code effects-api}
     * having to take a hard dependency on RTP.
     *
     * @param fallback plugin to initialize with if {@code instance} is unset; may be {@code null}
     * @return the resolved {@link Plugin} instance
     * @throws IllegalStateException if {@code instance} is unset and {@code fallback} is {@code null}
     */
    @NotNull
    public static Plugin getInstance(Plugin fallback) {
        Plugin local = instance;
        if (local != null) return local;
        if (fallback == null) {
            throw new IllegalStateException(
                    "EffectsAPI.getInstance(fallback) called before init(Plugin) with null fallback");
        }
        init(fallback);
        return instance;
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

    public static void init(Plugin caller) {
        // Plugin startup logic, in case of standalone usage
        if (instance == null) {
            instance = caller;
        }
        if (fireworkSafetyListener == null) {
            //on first initialization, register firework safety events
            fireworkSafetyListener = new FireworkSafetyListener(caller);
            Bukkit.getPluginManager().registerEvents(fireworkSafetyListener, caller);
        }
        if (glideSafetyListener == null) {
            // GlideEffect (effects-api-ADR-001): tracks gliders, suppresses
            // firework rockets when configured, and places players on
            // shutdown via placeAllOnShutdown().
            glideSafetyListener = new GlideSafetyListener(caller);
            Bukkit.getPluginManager().registerEvents(glideSafetyListener, caller);
        }
    }

    /**
     * Plugin-disable hook. Caller is expected to invoke this from its
     * {@code onDisable()} so still-gliding players can be placed at the
     * highest safe block below them (or on a synthesized platform) before
     * the server stops. Idempotent.
     */
    public static void disable() {
        if (glideSafetyListener != null) {
            try {
                glideSafetyListener.placeAllOnShutdown();
            } catch (Throwable t) {
                Bukkit.getLogger().log(Level.WARNING,
                        "EffectsAPI.disable: GlideEffect shutdown placement threw", t);
            }
        }
    }
}
