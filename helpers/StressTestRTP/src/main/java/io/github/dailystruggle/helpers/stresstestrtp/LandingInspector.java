package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Locale;
import java.util.Set;

/**
 * Classifies the block column a player was teleported into.
 *
 * <p>"Success" in the attempt CSV means a teleport was observed; it says
 * nothing about where the player ended up. This reads the floor, feet and
 * head blocks at the landing so a row can say whether the destination was
 * safe. It is equal across arms and independent of every plugin's own
 * safety rules: the criteria are fixed here, not taken from any config.
 *
 * <p>Reads only a chunk that is already loaded, and only on the thread that
 * owns it (the single tick thread on Spigot/Paper, the owning region thread
 * on Folia). Otherwise the landing is reported {@code UNCHECKED_*} rather
 * than forcing a load or racing a region: the inspector must not add chunk
 * work to the arm it measures.
 */
public final class LandingInspector {

    /** Fixed verdicts, written literally to {@code landing_class}. */
    public enum Verdict {
        SAFE, LAVA, WATER, SUFFOCATING, NO_FLOOR, HAZARD, VOID,
        UNCHECKED_THREAD, UNCHECKED_UNLOADED, UNCHECKED_NO_LOCATION
    }

    /** Result of one inspection; block names are empty when unchecked. */
    public record Landing(String world, double y, Verdict verdict,
                          String floor, String feet, String head) {
        static Landing unchecked(Location loc, Verdict v) {
            String w = loc != null && loc.getWorld() != null ? loc.getWorld().getName() : "";
            double y = loc != null ? loc.getY() : Double.NaN;
            return new Landing(w, y, v, "", "", "");
        }
    }

    /** Blocks that hurt or trap a player standing in or on them. Matched by
     *  name so the set links against any API version. */
    private static final Set<String> HAZARD_NAMES = Set.of(
            "FIRE", "SOUL_FIRE", "MAGMA_BLOCK", "CACTUS", "CAMPFIRE", "SOUL_CAMPFIRE",
            "SWEET_BERRY_BUSH", "POWDER_SNOW", "WITHER_ROSE", "POINTED_DRIPSTONE",
            "COBWEB", "END_PORTAL", "NETHER_PORTAL");

    private LandingInspector() {}

    /** Inspects {@code loc} if the calling thread may read it. */
    public static Landing inspect(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return Landing.unchecked(loc, Verdict.UNCHECKED_NO_LOCATION);
        }
        World w = loc.getWorld();
        int bx = loc.getBlockX(), bz = loc.getBlockZ();
        // Sample points, not loc.getBlockY(): a player standing on a slab or
        // snow layer has a fractional Y inside the floor block, and reading
        // that block as "feet" would report every slab landing as suffocating.
        double y = loc.getY();
        int floorY = (int) Math.floor(y - 1.0e-3);
        int feetY = (int) Math.floor(y + 0.5);
        int headY = (int) Math.floor(y + 1.5);
        int cx = bx >> 4, cz = bz >> 4;
        try {
            if (!w.isChunkLoaded(cx, cz)) return Landing.unchecked(loc, Verdict.UNCHECKED_UNLOADED);
        } catch (Throwable t) {
            return Landing.unchecked(loc, Verdict.UNCHECKED_THREAD);
        }
        if (!TickThreadDetector.ownsChunk(w, cx, cz)) {
            return Landing.unchecked(loc, Verdict.UNCHECKED_THREAD);
        }
        if (floorY < w.getMinHeight()) {
            return new Landing(w.getName(), loc.getY(), Verdict.VOID, "", "", "");
        }
        try {
            Block floor = w.getBlockAt(bx, floorY, bz);
            Block feet = w.getBlockAt(bx, feetY, bz);
            Block head = w.getBlockAt(bx, headY, bz);
            Verdict v = classify(floor.getType(), feet.getType(), head.getType(),
                    feet.isPassable(), head.isPassable());
            return new Landing(w.getName(), loc.getY(), v,
                    name(floor.getType()), name(feet.getType()), name(head.getType()));
        } catch (Throwable t) {
            // Folia throws when a read is off-region; treat it as unchecked.
            return Landing.unchecked(loc, Verdict.UNCHECKED_THREAD);
        }
    }

    /**
     * Pure rule, ordered by severity: lava anywhere in the column, then
     * suffocation, water at feet or head, a hazard block, and finally a
     * floor that cannot be stood on.
     */
    static Verdict classify(Material floor, Material feet, Material head,
                            boolean feetPassable, boolean headPassable) {
        if (isLava(floor) || isLava(feet) || isLava(head)) return Verdict.LAVA;
        if (!feetPassable || !headPassable) {
            if (!isWater(feet) && !isWater(head)) return Verdict.SUFFOCATING;
        }
        if (isWater(feet) || isWater(head)) return Verdict.WATER;
        if (isHazard(floor) || isHazard(feet) || isHazard(head)) return Verdict.HAZARD;
        if (!floor.isSolid()) return isWater(floor) ? Verdict.WATER : Verdict.NO_FLOOR;
        return Verdict.SAFE;
    }

    private static boolean isLava(Material m) {
        return m == Material.LAVA || "LAVA_CAULDRON".equals(m.name());
    }

    private static boolean isWater(Material m) {
        return m == Material.WATER || "BUBBLE_COLUMN".equals(m.name());
    }

    private static boolean isHazard(Material m) {
        return HAZARD_NAMES.contains(m.name());
    }

    private static String name(Material m) {
        return m.name().toLowerCase(Locale.ROOT);
    }
}
