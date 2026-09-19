package io.github.dailystruggle.effectsapi.common.effects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;

import java.util.EnumMap;

/**
 * Drop inventory effect.
 *
 * <p>Drops the player's inventory contents at their target/current location and clears their
 * inventory, providing a clean survival restart mechanic at any pipeline stage (e.g.
 * {@code preteleport} or {@code presetup}) without forcing them through the Minecraft
 * death screen.
 */
public class DropInventoryEffect extends Effect<DropInventoryEffect.DropInventoryKeys> {

    public enum DropInventoryKeys {
        DUMMY
    }

    public DropInventoryEffect() {
        super(new EnumMap<>(DropInventoryKeys.class));
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

        ph.dropInventory(lh);
    }

    @Override
    public void setData(EnumMap<DropInventoryKeys, Object> data) {
        if (data == null) return;
        this.data.putAll(data);
    }

    @Override
    public void setData(String... tokens) {
        // No required tokens for DROP_INVENTORY
    }

    @Override
    public String toPermission() {
        return "drop_inventory";
    }
}
