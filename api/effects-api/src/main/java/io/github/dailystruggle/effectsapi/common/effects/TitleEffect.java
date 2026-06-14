package io.github.dailystruggle.effectsapi.common.effects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;

import java.util.EnumMap;

/**
 * Common title effect implementation.
 */
public class TitleEffect extends Effect<TitleEffect.TitleKeys> {
    public enum TitleKeys { TITLE, SUBTITLE, FADE_IN, STAY, FADE_OUT }

    public TitleEffect() {
        super(new EnumMap<>(TitleKeys.class));
        data.put(TitleKeys.TITLE, "");
        data.put(TitleKeys.SUBTITLE, "");
        data.put(TitleKeys.FADE_IN, 10);
        data.put(TitleKeys.STAY, 70);
        data.put(TitleKeys.FADE_OUT, 20);
        this.defaults = data.clone();
    }

    @Override
    public void run() {
        String title = String.valueOf(data.getOrDefault(TitleKeys.TITLE, ""));
        String subtitle = String.valueOf(data.getOrDefault(TitleKeys.SUBTITLE, ""));
        int fadeIn = 10, stay = 70, fadeOut = 20;

        Object o = data.get(TitleKeys.FADE_IN);
        if (o instanceof Number) fadeIn = ((Number) o).intValue();
        o = data.get(TitleKeys.STAY);
        if (o instanceof Number) stay = ((Number) o).intValue();
        o = data.get(TitleKeys.FADE_OUT);
        if (o instanceof Number) fadeOut = ((Number) o).intValue();

        PlayerHandle ph = HandleRegistry.wrapPlayer(target);
        if (ph != null) {
            ph.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
        }
    }

    @Override
    public void setData(String... data) {
        applyByType(TitleKeys.values(), data);
    }

    @Override
    public String toPermission() {
        return data.get(TitleKeys.TITLE).toString().replaceAll("\\.*", "") +
               data.get(TitleKeys.SUBTITLE).toString().replaceAll("\\.*", "");
    }
}
