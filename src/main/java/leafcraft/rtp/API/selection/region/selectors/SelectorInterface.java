package leafcraft.rtp.API.selection.region.selectors;

import leafcraft.rtp.API.selection.worldborder.WorldBorder;
import org.bukkit.Location;
import org.bukkit.block.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Set;
import java.util.function.Predicate;

public interface SelectorInterface {
    String name();
    EnumMap<SelectorParams,Object> params();


    /**
     * add a local verification check
     * @param verify location verification checker
     *                     input: org.bukkit.Location
     *                     return: boolean, true if valid, false if invalid
     */
    void addVerifier(Predicate<Location> verify);

    default boolean isInBounds(int x, int z) {
        return false;
    }

    default boolean isInBounds(long location) {
        return false;
    }

    /**
     * @return target location, or null for no location found
     */
    @Nullable
    Location select();

    @Nullable
    Location select(@Nullable Set<Biome> biome);
}