package io.github.dailystruggle.rtp_domain;

import leafcraft.rtp.RTP;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;
import work.torp.domain.helper.Check;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;

public final class RTP_Domain extends JavaPlugin {
    private static final MutableCallSite callSite = new MutableCallSite(
            MethodType.methodType(boolean.class, Location.class));
    private static final MethodHandle methodHandle = callSite.dynamicInvoker();

    public MethodHandle mh;

    @Override
    public void onEnable() {
        // Plugin startup logic
        try {
            mh = MethodHandles.publicLookup().findStatic(Check.class, "inField", MethodType.methodType(boolean.class, Location.class));
            callSite.setTarget(mh);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            e.printStackTrace();
        }
        RTP.addLocationCheck(methodHandle);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
