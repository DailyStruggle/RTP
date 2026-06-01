package io.github.dailystruggle.effectsapi.bukkit.LocalEffects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.EffectsAPI;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.enums.FireworkTypeNames;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.enums.PotionTypeNames;
import io.github.dailystruggle.effectsapi.bukkit.BukkitListeners.FireworkSafetyListener;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.Vector;

import java.util.EnumMap;
import java.util.Objects;

public class FireworkEffect extends Effect<FireworkTypeNames> {
    public FireworkEffect() {
        super(new EnumMap<>(FireworkTypeNames.class));
        EnumMap<FireworkTypeNames, Object> data = getData();
        data.put(FireworkTypeNames.TYPE, org.bukkit.FireworkEffect.Type.BALL);
        data.put(FireworkTypeNames.NUMBER, 1);
        data.put(FireworkTypeNames.POWER, 0);
        data.put(FireworkTypeNames.DX, 0.0);
        data.put(FireworkTypeNames.DY, 1.0);
        data.put(FireworkTypeNames.DZ, 0.0);
        data.put(FireworkTypeNames.COLOR, Color.WHITE);
        data.put(FireworkTypeNames.FADE, Color.WHITE);
        data.put(FireworkTypeNames.FLICKER, false);
        data.put(FireworkTypeNames.TRAIL, false);
        data.put(FireworkTypeNames.SAFE, true);
        this.data = data;
        this.defaults = data.clone();
    }

    /**
     * Test seam: dispatch a region-bound task at {@code location}. Returns
     * {@code true} when the task was dispatched (or executed) by a Folia-style
     * region scheduler; {@code false} otherwise. Default implementation
     * resolves {@code Bukkit.getRegionScheduler()} reflectively so
     * {@code effects-api} keeps zero compile-time dependency on the Folia API.
     */
    @FunctionalInterface
    interface RegionDispatcher {
        boolean dispatch(org.bukkit.plugin.Plugin caller, Location location, Runnable task);
    }

    static volatile RegionDispatcher regionDispatcher = (caller, loc, task) -> {
        if (!isFolia()) return false;
        try {
            Object regionScheduler = Bukkit.class
                    .getMethod("getRegionScheduler").invoke(null);
            regionScheduler.getClass()
                    .getMethod("run", org.bukkit.plugin.Plugin.class, Location.class,
                            java.util.function.Consumer.class)
                    .invoke(regionScheduler, caller, loc,
                            (java.util.function.Consumer<Object>) t -> task.run());
            return true;
        } catch (Throwable t) {
            return false;
        }
    };

    @Override
    public void run() {
        if (target instanceof Entity) target = ((Entity) target).getLocation();
        // Folia: there is no global "primary thread"; chunk/region scheduling is
        // required. Use the RegionScheduler keyed on the spawn location so the
        // firework spawn happens on the owning region thread. On Spigot/Paper
        // Bukkit.isPrimaryThread() works as before.
        org.bukkit.plugin.Plugin caller;
        if (EffectsAPI.fireworkSafetyListener != null) {
            caller = EffectsAPI.fireworkSafetyListener.caller;
        } else {
            try {
                caller = EffectsAPI.getInstance();
            } catch (IllegalStateException pre) {
                // S-006: getInstance() throws before init(). Dispatcher seam
                // must still be consulted (Folia path / tests), so degrade to
                // a null caller and let downstream guards handle it.
                caller = null;
            }
        }
        Location loc = (Location) target;
        if (regionDispatcher.dispatch(caller, loc, () -> spawnFirework(loc))) {
            return;
        }
        if(!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(caller, this);
            return;
        }
        spawnFirework(loc);
    }

    /**
     * Class-probe for Folia. Independent of any plugin instance and safe to
     * evaluate from {@code effects-api} (which has no compile-time dependency
     * on Folia API).
     */
    static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void spawnFirework(Location location) {

        int numFireworks = 1;
        double dx=0, dy=1, dz=0;
        Color color = Color.WHITE, fade = Color.WHITE;
        boolean flicker=false, trail=false, safe=true;

        Object o = data.get(FireworkTypeNames.NUMBER);
        if(o instanceof Number) numFireworks = ((Number) o).intValue();


        o = data.get(FireworkTypeNames.DX);
        if(o instanceof Number) dx = ((Number)o).doubleValue();
        o = data.get(FireworkTypeNames.DY);
        if(o instanceof Number) dy = ((Number)o).doubleValue();
        o = data.get(FireworkTypeNames.DZ);
        if(o instanceof Number) dz = ((Number)o).doubleValue();

        o = data.get(FireworkTypeNames.COLOR);
        if(o instanceof Color) color = (Color) o;

        o = data.get(FireworkTypeNames.FADE);
        if(o instanceof Color) fade = (Color) o;

        o = data.get(FireworkTypeNames.FLICKER);
        if(o instanceof Boolean) flicker = (Boolean) o;

        o = data.get(FireworkTypeNames.TRAIL);
        if(o instanceof Boolean) trail = (Boolean) o;

        o = data.get(FireworkTypeNames.SAFE);
        if(o instanceof Boolean) safe = (Boolean) o;

        //start with one firework
        Firework f = (Firework) Objects.requireNonNull(location.getWorld())
                .spawnEntity(location.clone().add(0, 1, 0), EntityType.FIREWORK);
        if (dx != 0.0 || dy != 1.0 || dz != 0.0) {
            f.setShotAtAngle(true);
            f.setVelocity(new Vector(dx, dy, dz));
        }
        FireworkMeta fwm = f.getFireworkMeta();

        org.bukkit.FireworkEffect fireworkEffect = org.bukkit.FireworkEffect.builder()
                .with((org.bukkit.FireworkEffect.Type) data.get(FireworkTypeNames.TYPE))
                .withColor(color)
                .withFade(fade)
                .flicker(flicker)
                .trail(trail)
                .build();
        fwm.addEffect(fireworkEffect);
        fwm.setPower((Integer) data.get(FireworkTypeNames.POWER));
        f.setFireworkMeta(fwm);

        //if more than one, add to the list to duplicate the explosion (cheaper than multiple firework entities)
        if (numFireworks > 1) FireworkSafetyListener.addFirework(f.getEntityId(), numFireworks, safe);
        if (fwm.getPower() == 0) f.detonate();
    }

    /**
     * Positional / type-driven key order for {@link FireworkEffect}.
     * Mirrors the order documented in {@code LocalEffects/Readme.md} and
     * the per-position assignment used by the prior strict-positional
     * implementation. Promoted to a constant so {@link #setData(String...)}
     * can hand it to {@link Effect#applyByType(Enum[], String[])}.
     */
    private static final FireworkTypeNames[] KEY_ORDER = {
            FireworkTypeNames.TYPE,
            FireworkTypeNames.NUMBER,
            FireworkTypeNames.POWER,
            FireworkTypeNames.COLOR,
            FireworkTypeNames.FADE,
            FireworkTypeNames.FLICKER,
            FireworkTypeNames.TRAIL,
            FireworkTypeNames.SAFE,
            FireworkTypeNames.DX,
            FireworkTypeNames.DY,
            FireworkTypeNames.DZ
    };

    @Override
    public void setData(String... data) {
        applyByType(KEY_ORDER, data);
    }

    @Override
    public String toPermission() {
        return this.data.get(FireworkTypeNames.TYPE).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.NUMBER).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.POWER).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.COLOR).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.FADE).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.FLICKER).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.TRAIL).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.SAFE).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.DX).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.DY).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.DZ).toString().replaceAll("\\.*", "");

    }
}
