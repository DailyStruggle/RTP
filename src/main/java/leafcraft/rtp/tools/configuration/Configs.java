package leafcraft.rtp.tools.configuration;

import leafcraft.rtp.API.selection.SelectionAPI;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.softdepends.*;
import org.bukkit.Location;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;

//route for all config classes
public class Configs {
    public static Config config;
    public static Lang lang;
    public static Regions regions;
    public static Worlds worlds;

    public Configs() {
        refresh();
    }

    public void refresh() {
        RTP plugin = RTP.getPlugin();
        lang = new Lang(plugin);
        config = new Config(plugin,lang);
        worlds = new Worlds(plugin,lang);
        regions = new Regions(plugin,lang);
        initDefaultVerifiers();
    }

    private static void initDefaultSelectors() {

    }

    private static void initDefaultVerifiers() {
        SelectionAPI.clearGlobalRegionVerifiers();

        final MutableCallSite callSite = new MutableCallSite(MethodType.methodType(boolean.class, Location.class));
        final MethodHandle methodHandle = callSite.dynamicInvoker();

        MethodHandle mutableMethodHandle;

        if(config.rerollFactions) {
            try {
                mutableMethodHandle = MethodHandles.publicLookup().findStatic(FactionsChecker.class, "isInClaim", MethodType.methodType(boolean.class, Location.class));
                callSite.setTarget(mutableMethodHandle);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                e.printStackTrace();
            }
            SelectionAPI.addGlobalRegionVerifier(methodHandle);
        }

        if(config.rerollRedProtect) {
            try {
                mutableMethodHandle = MethodHandles.publicLookup().findStatic(RedProtectChecker.class, "isInClaim", MethodType.methodType(boolean.class, Location.class));
                callSite.setTarget(mutableMethodHandle);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                e.printStackTrace();
            }
            SelectionAPI.addGlobalRegionVerifier(methodHandle);
        }

        if(config.rerollGriefDefender) {
            try {
                mutableMethodHandle = MethodHandles.publicLookup().findStatic(GriefDefenderChecker.class, "isInClaim", MethodType.methodType(boolean.class, Location.class));
                callSite.setTarget(mutableMethodHandle);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                e.printStackTrace();
            }
            SelectionAPI.addGlobalRegionVerifier(methodHandle);
        }

        if(config.rerollGriefPrevention) {
            try {
                mutableMethodHandle = MethodHandles.publicLookup().findStatic(GriefPreventionChecker.class, "isInClaim", MethodType.methodType(boolean.class, Location.class));
                callSite.setTarget(mutableMethodHandle);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                e.printStackTrace();
            }
            SelectionAPI.addGlobalRegionVerifier(methodHandle);
        }

        if(config.rerollLands) {
            try {
                mutableMethodHandle = MethodHandles.publicLookup().findStatic(LandsChecker.class, "isInClaim", MethodType.methodType(boolean.class, Location.class));
                callSite.setTarget(mutableMethodHandle);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                e.printStackTrace();
            }
            SelectionAPI.addGlobalRegionVerifier(methodHandle);
        }

        if(config.rerollHuskTowns) {
            try {
                mutableMethodHandle = MethodHandles.publicLookup().findStatic(HuskTownsChecker.class, "isInClaim", MethodType.methodType(boolean.class, Location.class));
                callSite.setTarget(mutableMethodHandle);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                e.printStackTrace();
            }
            SelectionAPI.addGlobalRegionVerifier(methodHandle);
        }

        if(config.rerollTownyAdvanced) {
            try {
                mutableMethodHandle = MethodHandles.publicLookup().findStatic(TownyAdvancedChecker.class, "isInClaim", MethodType.methodType(boolean.class, Location.class));
                callSite.setTarget(mutableMethodHandle);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                e.printStackTrace();
            }
            SelectionAPI.addGlobalRegionVerifier(methodHandle);
        }

        if(config.rerollWorldGuard) {
            try {
                mutableMethodHandle = MethodHandles.publicLookup().findStatic(WorldGuardChecker.class, "isInClaim", MethodType.methodType(boolean.class, Location.class));
                callSite.setTarget(mutableMethodHandle);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                e.printStackTrace();
            }
            SelectionAPI.addGlobalRegionVerifier(methodHandle);
        }
    }
}
