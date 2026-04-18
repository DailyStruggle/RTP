package io.github.dailystruggle.effectsapi;

import io.github.dailystruggle.effectsapi.SpigotListeners.FireworkSafetyListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.logging.Level;
import java.util.regex.Pattern;

//reference point for
public final class EffectsAPI {
    public static Plugin instance = null;
    //referentiable listener, for updating
    public static FireworkSafetyListener fireworkSafetyListener = null;
    //server version checking
    private static String version = null;
    private static Integer intVersion = null;

    private EffectsAPI() {

    }

    /**
     * @return instance of plugin if loaded, otherwise null
     */
    @Nullable
    public static Plugin getInstance() {
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
    }
}
