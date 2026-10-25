package io.github.dailystruggle.helpers.stresstestrtp;

import java.util.Locale;
import java.util.Set;

/**
 * Classifies the block column a player was teleported into (ADR-122 Decision 1).
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
        static Landing unchecked(StressLocation loc, Verdict v) {
            String w = loc != null ? loc.world() : "";
            double y = loc != null ? loc.y() : Double.NaN;
            return new Landing(w, y, v, "", "", "");
        }
    }

    private static final Set<String> HAZARD_NAMES = Set.of(
            "FIRE", "SOUL_FIRE", "MAGMA_BLOCK", "CACTUS", "CAMPFIRE", "SOUL_CAMPFIRE",
            "SWEET_BERRY_BUSH", "POWDER_SNOW", "WITHER_ROSE", "POINTED_DRIPSTONE",
            "COBWEB", "END_PORTAL", "NETHER_PORTAL");

    private static final Set<String> STANDABLE_NAMES = Set.of("LILY_PAD", "SNOW", "SCAFFOLDING");

    private static final Set<String> THIN_NAMES = Set.of("LILY_PAD");

    private static final Set<String> NON_SOLID_NAMES = Set.of(
            "AIR", "CAVE_AIR", "VOID_AIR", "WATER", "LAVA", "BUBBLE_COLUMN",
            "FIRE", "SOUL_FIRE", "COBWEB", "POWDER_SNOW", "SWEET_BERRY_BUSH",
            "WITHER_ROSE", "SHORT_GRASS", "GRASS", "TALL_GRASS", "FERN", "LARGE_FERN",
            "DEAD_BUSH", "VINE", "KELP", "SEAGRASS", "TALL_SEAGRASS", "TORCH",
            "WALL_TORCH", "REDSTONE_WIRE", "TRIPWIRE", "STRING", "LEVER",
            "STRUCTURE_VOID", "LIGHT", "LILY_PAD", "SNOW", "SCAFFOLDING");

    private LandingInspector() {}

    public static Landing inspect(StressLocation loc) {
        return inspect(Sched.boundHost(), loc);
    }

    public static Landing inspect(StressHost host, StressLocation loc) {
        if (loc == null || loc.world().isEmpty()) {
            return Landing.unchecked(loc, Verdict.UNCHECKED_NO_LOCATION);
        }
        if (host == null) {
            return Landing.unchecked(loc, Verdict.UNCHECKED_NO_LOCATION);
        }
        StressWorld w = host.world(loc.world());
        if (w == null) {
            return Landing.unchecked(loc, Verdict.UNCHECKED_NO_LOCATION);
        }
        int bx = loc.blockX(), bz = loc.blockZ();
        double y = loc.y();
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
        if (floorY < w.minHeight()) {
            return new Landing(w.name(), loc.y(), Verdict.VOID, "", "", "");
        }
        try {
            StressWorld.BlockSample floor = w.blockAt(bx, floorY, bz);
            StressWorld.BlockSample feet = w.blockAt(bx, feetY, bz);
            StressWorld.BlockSample head = w.blockAt(bx, headY, bz);
            if (floor == null || feet == null || head == null) {
                return Landing.unchecked(loc, Verdict.UNCHECKED_THREAD);
            }
            Verdict v = classify(floor.typeName(), floor.solid(),
                    feet.typeName(), head.typeName(),
                    feet.passable(), head.passable());
            return new Landing(w.name(), loc.y(), v,
                    name(floor.typeName()), name(feet.typeName()), name(head.typeName()));
        } catch (Throwable t) {
            return Landing.unchecked(loc, Verdict.UNCHECKED_THREAD);
        }
    }

    /**
     * Pure rule over block type names (or enums via {@code Enum#name()}).
     */
    static Verdict classify(Enum<?> floor, Enum<?> feet, Enum<?> head,
                            boolean feetPassable, boolean headPassable) {
        return classify(
                floor != null ? floor.name() : "AIR",
                feet != null ? feet.name() : "AIR",
                head != null ? head.name() : "AIR",
                feetPassable, headPassable);
    }

    public static Verdict classify(String floor, String feet, String head,
                                   boolean feetPassable, boolean headPassable) {
        String fl = norm(floor);
        boolean solid = !fl.isEmpty() && !NON_SOLID_NAMES.contains(fl) && !isThin(fl);
        return classify(fl, solid, feet, head, feetPassable, headPassable);
    }

    public static Verdict classify(String floor, boolean floorSolid,
                                   String feet, String head,
                                   boolean feetPassable, boolean headPassable) {
        String fl = norm(floor);
        String ft = norm(feet);
        String hd = norm(head);
        if (isLava(fl) || isLava(ft) || isLava(hd)) return Verdict.LAVA;
        boolean feetThin = isThin(ft);
        if (((!feetPassable && !feetThin) || !headPassable)
                && !isWater(ft) && !isWater(hd)) {
            return Verdict.SUFFOCATING;
        }
        if (isWater(ft) || isWater(hd)) return Verdict.WATER;
        if (isHazard(fl) || isHazard(ft) || isHazard(hd)) return Verdict.HAZARD;
        if (feetThin) return Verdict.SAFE;
        if (!floorSolid && !isStandable(fl)) {
            return isWater(fl) ? Verdict.WATER : Verdict.NO_FLOOR;
        }
        return Verdict.SAFE;
    }

    static boolean isThin(String m) {
        String n = norm(m);
        return n.endsWith("_CARPET") || THIN_NAMES.contains(n);
    }

    static boolean isStandable(String m) {
        String n = norm(m);
        return isThin(n) || STANDABLE_NAMES.contains(n);
    }

    private static boolean isLava(String m) {
        String n = norm(m);
        return "LAVA".equals(n) || "LAVA_CAULDRON".equals(n);
    }

    private static boolean isWater(String m) {
        String n = norm(m);
        return "WATER".equals(n) || "BUBBLE_COLUMN".equals(n);
    }

    private static boolean isHazard(String m) {
        return HAZARD_NAMES.contains(norm(m));
    }

    private static String norm(String m) {
        return m == null ? "" : m.trim().toUpperCase(Locale.ROOT);
    }

    private static String name(String m) {
        return m == null ? "" : m.trim().toLowerCase(Locale.ROOT);
    }
}
