package io.github.dailystruggle.effectsapi.common.effects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;

import java.util.EnumMap;

/**
 * Death effect.
 *
 * <p>Kills the target player, triggering the standard Minecraft death event,
 * death message, death screen, and respawn sequence.
 *
 * <p>Optionally accepts a boolean token to control whether the player's bed/respawn anchor
 * is set to the destination location: {@code DEATH [true|false]} or {@code DEATH.true} /
 * {@code DEATH.false}. Defaults to {@code true}.
 */
public class DeathEffect extends Effect<DeathEffect.DeathKeys> {

    public enum DeathKeys {
        SET_BED
    }

    public DeathEffect() {
        this(true);
    }

    public DeathEffect(boolean setBed) {
        super(new EnumMap<>(DeathKeys.class));
        EnumMap<DeathKeys, Object> data = getData();
        data.put(DeathKeys.SET_BED, setBed);
        this.data = data;
        this.defaults = data.clone();
    }

    @Override
    public void run() {
        PlayerHandle ph = HandleRegistry.wrapPlayer(target);
        LocationHandle respawnLoc = null;
        if (target instanceof io.github.dailystruggle.effectsapi.common.spi.EffectTarget et) {
            respawnLoc = et.destination() != null ? et.destination() : et.location();
        } else {
            respawnLoc = HandleRegistry.wrapLocation(target);
        }

        if (ph == null && respawnLoc != null) {
            ph = HandleRegistry.playerAt(respawnLoc);
        }
        if (ph == null) return;

        Object rawSetBed = data.get(DeathKeys.SET_BED);
        boolean setBed = rawSetBed == null || Boolean.parseBoolean(rawSetBed.toString());

        ph.kill(setBed, respawnLoc);
    }

    @Override
    public void setData(EnumMap<DeathKeys, Object> data) {
        if (data == null) return;
        this.data.putAll(data);
    }

    @Override
    public void setData(String... tokens) {
        if (tokens == null || tokens.length == 0) return;
        String first = tokens[0].trim();
        boolean setBed = first.isEmpty() || Boolean.parseBoolean(first);
        data.put(DeathKeys.SET_BED, setBed);
    }

    @Override
    public String toPermission() {
        Object rawSetBed = data.get(DeathKeys.SET_BED);
        boolean setBed = rawSetBed == null || Boolean.parseBoolean(rawSetBed.toString());
        return setBed ? "death" : "death.false";
    }
}
