package io.github.dailystruggle.effectsapi.common.effects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Common firework effect implementation.
 *
 * <p>This effect is platform-neutral: it resolves a {@link PlayerHandle} (or a
 * {@link LocationHandle}) for its target and delegates the actual spawn to
 * {@link PlayerHandle#spawnFirework}/{@link LocationHandle#spawnFirework}. All
 * platform-specific work (entity spawn, Folia region dispatch, safety listener
 * wiring) lives in the handle implementation, so no native firework/world
 * object ever leaks into this class.
 *
 * <p>Platform-typed default values ({@code TYPE}, {@code COLOR}, {@code FADE})
 * are injected by the platform initializer via the constructor, mirroring
 * {@link SoundEffect}.
 */
public class FireworkEffect extends Effect<FireworkEffect.FireworkKeys> {

    /** Positional / type-driven keys for {@code FireworkEffect}. */
    public enum FireworkKeys {
        TYPE,
        NUMBER,
        POWER,
        DX,
        DY,
        DZ,
        COLOR,
        FADE,
        FLICKER,
        TRAIL,
        SAFE
    }

    /**
     * @param defaultType  platform firework-shape default (e.g. Bukkit
     *                     {@code FireworkEffect.Type.BALL}).
     * @param defaultColor platform color default (e.g. Bukkit {@code Color.WHITE});
     *                     used for both {@code COLOR} and {@code FADE}.
     */
    public FireworkEffect(Object defaultType, Object defaultColor) {
        super(new EnumMap<>(FireworkKeys.class));
        EnumMap<FireworkKeys, Object> data = getData();
        data.put(FireworkKeys.TYPE, defaultType);
        data.put(FireworkKeys.NUMBER, 1);
        data.put(FireworkKeys.POWER, 0);
        data.put(FireworkKeys.DX, 0.0);
        data.put(FireworkKeys.DY, 1.0);
        data.put(FireworkKeys.DZ, 0.0);
        data.put(FireworkKeys.COLOR, defaultColor);
        data.put(FireworkKeys.FADE, defaultColor);
        data.put(FireworkKeys.FLICKER, false);
        data.put(FireworkKeys.TRAIL, false);
        data.put(FireworkKeys.SAFE, true);
        this.data = data;
        this.defaults = data.clone();
    }

    @Override
    public void run() {
        PlayerHandle ph = HandleRegistry.wrapPlayer(target);
        LocationHandle lh = (ph == null) ? HandleRegistry.wrapLocation(target) : null;

        Map<String, Object> dataMap = new HashMap<>();
        for (Map.Entry<FireworkKeys, Object> entry : data.entrySet()) {
            dataMap.put(entry.getKey().name(), entry.getValue());
        }

        if (ph != null) {
            ph.spawnFirework(dataMap);
        } else if (lh != null) {
            lh.spawnFirework(dataMap);
        }
    }

    /**
     * Positional / type-driven key order for {@link FireworkEffect}.
     * Mirrors the order documented in {@code LocalEffects/Readme.md} and
     * the per-position assignment used by the prior strict-positional
     * implementation.
     */
    private static final FireworkKeys[] KEY_ORDER = {
            FireworkKeys.TYPE,
            FireworkKeys.NUMBER,
            FireworkKeys.POWER,
            FireworkKeys.COLOR,
            FireworkKeys.FADE,
            FireworkKeys.FLICKER,
            FireworkKeys.TRAIL,
            FireworkKeys.SAFE,
            FireworkKeys.DX,
            FireworkKeys.DY,
            FireworkKeys.DZ
    };

    @Override
    public void setData(String... data) {
        applyByType(KEY_ORDER, data);
    }

    @Override
    public String toPermission() {
        return this.data.get(FireworkKeys.TYPE).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkKeys.NUMBER).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkKeys.POWER).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkKeys.COLOR).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkKeys.FADE).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkKeys.FLICKER).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkKeys.TRAIL).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkKeys.SAFE).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkKeys.DX).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkKeys.DY).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkKeys.DZ).toString().replaceAll("\\.*", "");
    }
}
