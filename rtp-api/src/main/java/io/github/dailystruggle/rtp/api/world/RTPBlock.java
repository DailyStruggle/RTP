package io.github.dailystruggle.rtp.api.world;

import java.util.Objects;

/**
 * Class representing a block in the world
 */
public abstract class RTPBlock<T> {
    protected final T block;

    protected RTPBlock(T block) {
        this.block = block;
    }

    public T block() {
        return block;
    }

    /**
     * Get the location of the block
     *
     * @return the location
     */
    public RTPLocation getLocation() {
        return new RTPLocation(world(), x(), y(), z());
    }

    /**
     * Check if the block is an air block
     *
     * @return true if air, false otherwise
     */
    public abstract boolean isAir();

    /**
     * Get the x coordinate of the block
     *
     * @return the x coordinate
     */
    public abstract int x();

    /**
     * Get the y coordinate of the block
     *
     * @return the y coordinate
     */
    public abstract int y();

    /**
     * Get the z coordinate of the block
     *
     * @return the z coordinate
     */
    public abstract int z();

    /**
     * Get the world the block is in
     *
     * @return the world
     */
    public abstract RTPWorld<?> world();

    /**
     * Get the sky light level of the block
     *
     * @return the sky light level
     */
    public abstract int skyLight();

    /**
     * Get the material name of the block
     *
     * @return the material name
     */
    public abstract String getMaterial();

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        RTPBlock<?> that = (RTPBlock<?>) obj;
        return Objects.equals(this.block, that.block);
    }

    @Override
    public int hashCode() {
        return Objects.hash(block);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" +
                "block=" + block + ']';
    }
}

