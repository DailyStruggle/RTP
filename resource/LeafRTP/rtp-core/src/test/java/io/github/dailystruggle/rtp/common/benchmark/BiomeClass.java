package io.github.dailystruggle.rtp.common.benchmark;

import java.util.Locale;

/**
 * Coarse grouping of vanilla biome identifiers, for measuring biome-constrained selection.
 *
 * <p>Grouped rather than kept per-identifier for two reasons. One byte per chunk keeps the biome
 * channel the same size as the safety channel, so adding it does not decide the benchmark's heap
 * figures. More importantly, the preference being modelled is not per-biome: operators and players
 * talk about <i>lush versus barren</i>, and the recurring complaints - "mesa and savanna are barren",
 * "desert burns a finite resource", "beach means island" - group cleanly into a handful of classes.
 * Measuring 60 identifiers separately would report noise about which particular forest a save
 * happens to hold.
 *
 * <p>The grouping is a stated preference, not a measurement, and it is the input a report should
 * name when quoting any biome-weighted figure. What is <i>not</i> a preference is the mapping from
 * identifier to class, which is mechanical and stated below.
 *
 * <p>Matching is by substring on the reconciled identifier, ordered most-specific first, because
 * vanilla ids compose modifiers onto stems ({@code snowy_taiga}, {@code old_growth_pine_taiga},
 * {@code eroded_badlands}). A prefix or exact match would need the full identifier list per
 * Minecraft version, which is precisely the coupling a benchmark should not take on.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier.
 */
public enum BiomeClass {

  /** Forest, jungle, plains, taiga, swamp - the classes players describe as useful early. */
  LUSH,
  /** Desert, badlands/mesa, savanna - habitable but barren, and sand is finite. */
  BARREN,
  /** Snowy and icy surface biomes. */
  COLD,
  /** Ocean, river, beach and shore. Land, if any, but not a destination in its own right. */
  AQUATIC,
  /** Cave biomes, and any underground-only classification. */
  SUBTERRANEAN,
  /** Nether and End. */
  OTHERWORLDLY,
  /** No biome container on disk, or an identifier this grouping does not recognise. */
  UNKNOWN;

  /**
   * Maps a namespaced or plain biome identifier onto a class.
   *
   * @param id raw identifier, e.g. {@code minecraft:old_growth_birch_forest}; null tolerated
   * @return the class, {@link #UNKNOWN} when the identifier is absent or unrecognised
   */
  public static BiomeClass classify(String id) {
    if (id == null) return UNKNOWN;
    String n = id.toLowerCase(Locale.ROOT);
    int colon = n.indexOf(':');
    if (colon >= 0) n = n.substring(colon + 1);

    // Otherworldly first: "warped_forest" and "crimson_forest" are Nether biomes whose stems would
    // otherwise match LUSH, and "end_barrens" would match BARREN.
    if (n.startsWith("nether")
        || n.contains("nether_wastes")
        || n.contains("soul_sand")
        || n.contains("crimson")
        || n.contains("warped")
        || n.contains("basalt_deltas")
        || n.startsWith("end_")
        || n.equals("the_end")
        || n.contains("the_void")) {
      return OTHERWORLDLY;
    }
    if (n.contains("cave") || n.contains("deep_dark") || n.contains("lush_caves")) {
      return SUBTERRANEAN;
    }
    if (n.contains("ocean")
        || n.contains("river")
        || n.contains("beach")
        || n.contains("shore")
        || n.contains("mushroom_fields")) {
      return AQUATIC;
    }
    // Cold before LUSH so "snowy_taiga" and "grove" are not counted as lush ground.
    if (n.contains("snowy")
        || n.contains("frozen")
        || n.contains("ice_spikes")
        || n.contains("glacier")
        || n.contains("grove")
        || n.contains("jagged_peaks")
        || n.contains("frozen_peaks")) {
      return COLD;
    }
    if (n.contains("desert") || n.contains("badlands") || n.contains("savanna") || n.contains("mesa")) {
      return BARREN;
    }
    if (n.contains("forest")
        || n.contains("jungle")
        || n.contains("taiga")
        || n.contains("plains")
        || n.contains("swamp")
        || n.contains("mangrove")
        || n.contains("meadow")
        || n.contains("cherry")
        || n.contains("birch")
        || n.contains("grove_meadow")
        || n.contains("flower")
        || n.contains("bamboo")) {
      return LUSH;
    }
    // Stony/windswept/peaks that survive to here are neither lush nor sand-bearing; barren is the
    // closer of the two available answers, and the alternative - a seventh class holding one case -
    // would not change any measured share.
    if (n.contains("stony") || n.contains("windswept") || n.contains("peaks") || n.contains("hills")) {
      return BARREN;
    }
    return UNKNOWN;
  }
}
