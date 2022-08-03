package io.github.dailystruggle.rtp.bukkit.server.substitutions;

import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPBlock;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import org.bukkit.block.Block;

public record BukkitRTPBlock(Block block) implements RTPBlock {

    @Override
    public RTPLocation getLocation() {
        return new RTPLocation(new BukkitRTPWorld(block.getWorld()), block.getX(), block.getY(), block.getZ());
    }

    @Override
    public boolean isAir() {
        return !(block.isLiquid() || block.getType().isSolid());
    }

    @Override
    public int x() {
        return block.getX();
    }

    @Override
    public int y() {
        return block.getY();
    }

    @Override
    public int z() {
        return block.getZ();
    }

    @Override
    public RTPWorld world() {
        return new BukkitRTPWorld(block.getWorld());
    }

    @Override
    public int skyLight() {
        return block.getLightFromSky();
    }
}
