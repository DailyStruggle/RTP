package io.github.dailystruggle.effectsapi.common.effects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.EffectFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TitleEffectArgsTest {

    @Test
    @DisplayName("RTP-78: String-typed effect args survive fixData (TITLE.<title>.<subtitle> is not reset to empty)")
    void dottedTokenFillsTitleAndSubtitle() {
        EffectFactory.setCoercer(new io.github.dailystruggle.effectsapi.bukkit.BukkitValueCoercer());
        EffectFactory.addEffect("TITLE", new TitleEffect());
        List<Effect<?>> built = EffectFactory.buildEffects("rtp.effect.postteleport",
                List.of("rtp.effect.postteleport.TITLE.LiveCheck.Arrived"));
        assertEquals(1, built.size());
        Effect<?> fx = built.get(0);
        assertEquals("LiveCheck", String.valueOf(fx.getData().get(TitleEffect.TitleKeys.TITLE)));
        assertEquals("Arrived", String.valueOf(fx.getData().get(TitleEffect.TitleKeys.SUBTITLE)));
    }
}
