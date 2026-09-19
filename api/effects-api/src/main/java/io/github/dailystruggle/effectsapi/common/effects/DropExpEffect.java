package io.github.dailystruggle.effectsapi.common.effects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;

import java.util.EnumMap;

/**
 * Drop experience effect.
 *
 * <p>Drops the player's experience at their target/current location according to the
 * vanilla Minecraft player death experience formula (Math.min(level * 7, 100)) and
 * resets the player's level and experience points to 0.
 */
public class DropExpEffect extends Effect<DropExpEffect.DropExpKeys> {

    public enum DropExpKeys {
        DUMMY
    }

    public DropExpEffect() {
        super(new EnumMap<>(DropExpKeys.class));
        this.defaults = data.clone();
    }

    @Override
    public void run() {
        PlayerHandle ph = HandleRegistry.wrapPlayer(target);
        LocationHandle lh = HandleRegistry.wrapLocation(target);
        if (ph == null && lh != null) {
            ph = HandleRegistry.playerAt(lh);
        }
        if (ph == null) return;

        ph.dropExperience(lh);
    }

    @Override
    public void setData(EnumMap<DropExpKeys, Object> data) {
        if (data == null) return;
        this.data.putAll(data);
    }

    @Override
    public void setData(String... tokens) {
        // No required tokens for DROP_EXP
    }

    @Override
    public String toPermission() {
        return "drop_exp";
    }
}
