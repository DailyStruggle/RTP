package io.github.dailystruggle.effectsapi;

import io.github.dailystruggle.commandsapi.bukkit.localCommands.BukkitTreeCommand;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.effectsapi.SpigotListeners.FireworkSafetyListener;
import io.github.dailystruggle.effectsapi.commands.EffectsAPIMainCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Pattern;

//reference point for
public final class EffectsAPI extends JavaPlugin {
    public static EffectsAPI instance = null;
    //referentiable listener, for updating
    public static FireworkSafetyListener fireworkSafetyListener = null;
    //server version checking
    private static String version = null;
    private static Integer intVersion = null;
    public EffectsAPI() {

    }

    public EffectsAPI(Plugin caller) {
        init(caller);
    }

    /**
     * @return instance of plugin if loaded, otherwise null
     */
    @Nullable
    public static EffectsAPI getInstance() {
        return instance;
    }

    private static final Pattern versionPattern = Pattern.compile( "[-+^.a-zA-Z]*",Pattern.CASE_INSENSITIVE );
    private static String getServerVersion() {
        if ( version == null ) {
            version = EffectsAPI.getInstance().getServer().getClass().getPackage().getName();
            if(!version.contains("1_")) {
                String bukkitVersion = EffectsAPI.getInstance().getServer().getBukkitVersion();

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
        if (fireworkSafetyListener == null) {
            //on first initialization, register firework safety events
            fireworkSafetyListener = new FireworkSafetyListener(caller);
            Bukkit.getPluginManager().registerEvents(fireworkSafetyListener, caller);
        }

        //construct commands
    }

    @Override
    public void onEnable() {
        init(this);

        //set up testCommand
        BukkitTreeCommand mainCommand = new EffectsAPIMainCommand(this);
        PluginCommand command = Objects.requireNonNull(getCommand("effectsapi"));
        command.setExecutor(mainCommand);
        command.setTabCompleter(mainCommand);

        Bukkit.getScheduler().runTaskTimer(this,() -> CommandsAPI.execute(Long.MAX_VALUE),20,1);

        //todo: set up command and tabcompleter
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        instance = null;
    }
}
