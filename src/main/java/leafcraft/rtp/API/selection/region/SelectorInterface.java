package leafcraft.rtp.API.selection.region;

import org.bukkit.Location;
import org.bukkit.block.Biome;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;

public interface SelectorInterface {
    /**
     * add a local verification check
     * @param methodHandle location verification checker
     *                     input: org.bukkit.Location
     *                     return: boolean, true if valid, false if invalid
     */
    void addVerifier(MethodHandle methodHandle);

    boolean isInBounds(int x, int z);

    /**
     * @return target location, or null for no location found
     */
    @Nullable
    Location select();

    @Nullable
    Location select(@Nullable Biome biome);
}