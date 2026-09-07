package io.github.dailystruggle.rtp.common.benchmark;

/**
 * Seeded noise terrain standing in for a real save's pass/fail geometry, at chunk granularity.
 *
 * <p>Why this exists: every figure in the ADR-083/084/085 line of work was measured against one
 * hand-copied save, tiled outward to reach border scale. Tiling repeats, so it cannot produce new
 * coastline, and {@link DensityTargetedOccupancyMask} only adds square blocks of ground - both
 * were flagged in those ADRs as the reason an absolute run count on that mask is not a claim about
 * any real world. A generated world removes both limits: it is unbounded, its usable share is an
 * input, and its boundary between usable and unusable ground is produced by noise rather than by a
 * repetition artefact or a square patch.
 *
 * <p>Unusable ground is composed from four features rather than one threshold, because run-length
 * cost depends on the <i>shape</i> of the unusable set and the four have very different shapes:
 *
 * <ul>
 *   <li><b>Ocean</b> - low-frequency fBm below a sea level. Large, compact, long boundaries.
 *   <li><b>River</b> - a narrow band either side of one fBm contour. Thin, winding, and the worst
 *       case for any 1D curve, because a one-chunk-wide feature crossing the domain cuts every
 *       stretch of key space it touches.
 *   <li><b>Pond / pool</b> - high-frequency fBm above a threshold, on land only. Small blobs.
 *   <li><b>Speckle</b> - a threshold on the raw lattice, so the feature is exactly one chunk wide.
 *       This is the "dotting" a real save shows and no interpolated field can produce: value noise
 *       is continuous, so its smallest feature is a few lattice cells across, never one.
 *   <li><b>Lava pool</b> - one chunk, never more, and rare. Hashed per chunk rather than drawn from
 *       a field, which is what bounds it to a single chunk by construction.
 * </ul>
 *
 * <p>A usable chunk whose whole eight-neighbourhood is unusable is reported unusable, because a lone
 * island in open water is not a viable starting position in practice - a player dropped there is
 * stranded. The filter is one pass over base classifications, so it never cascades: removing an
 * island does not strand its neighbour.</p>
 *
 * <p>The sea level is <b>calibrated</b>, not configured: the constructor bisects it against a
 * strided sample of the requested window until the realised usable share lands on the target. A
 * fixed threshold would give a share that drifts with radius, and a comparison at a stated density
 * needs density as an input.
 *
 * <p>Noise is value noise over a hashed integer lattice with smoothstep interpolation, seeded by
 * SplitMix64. Deliberately not gradient noise: value noise is enough to produce clustered features
 * with ragged edges, and it is short enough to read, which matters more here than spectral purity.
 * Nothing is stateful and nothing is cached, so a mask is reproducible from its seed alone.
 *
 * <p>Feature constants were tuned against measured statistics rather than by eye. At a
 * density-matched usable share on the same window, this mask reaches a boundary fraction of
 * <b>1.35x</b> the real save's, a region-aligned mixed-cell fraction of <b>0.97x</b>, and a mean
 * unusable patch size of <b>0.77x</b>. The first tuning attempt was 0.14x on boundary fraction and
 * 86x on patch size, which is what a mask that merely hits the right density looks like, and it
 * would have flattered every curve measured on it. The second reached 1.06x and 1.93x - right on
 * boundary length but too smooth by patch size, because value noise cannot produce a one-chunk
 * feature; the speckle term below is what closed that axis, at the cost of running slightly rough
 * on boundary fraction.
 *
 * <p><b>Caveat to carry with any figure measured on this mask:</b> it is not Minecraft's generator.
 * Feature wavelengths were chosen to reproduce those statistics, not derived from world generation,
 * so a run count here is a claim about a world with this mask's clustering - which is why
 * {@code MockWorldFidelityBenchmarkTest} measures the same clustering statistics on both this mask
 * and a real save rather than asserting the resemblance.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier.
 */
public final class NoiseWorldMask {

  /** What a chunk is, for drawing and for reporting the composition of the unusable set. */
  public enum Terrain {
    /** Usable ground - a viable starting position. */
    LAND,
    /** Below sea level. */
    OCEAN,
    /** Narrow water band. */
    RIVER,
    /** Small water body on land. */
    POND,
    /** Small lava body on land. */
    LAVA,
    /** Land by feature, but every one of its eight neighbours is unusable - a stranded island. */
    ISOLATED
  }

  /** Ocean wavelength, in chunks. About 8 km, so a continent spans a large-border domain. */
  private static final double OCEAN_WAVELENGTH = 512.0d;

  /**
   * Octaves in the elevation field.
   *
   * <p>Deep rather than smooth. Five octaves produced a coastline far straighter than the real
   * save's - measured boundary fraction 0.032 against 0.229 - and a straight coast is the friendly
   * case for a run-length table, so a mock that smooth would flatter every curve measured on it.
   * Eight octaves carry detail down to a two-chunk wavelength, which is where real coastline lives.
   */
  private static final int OCEAN_OCTAVES = 8;

  /**
   * Amplitude decay per octave.
   *
   * <p>Above the usual 0.5: the fine octaves are what fragment the coast, and at 0.5 they are too
   * weak to cross the sea level. This is the single constant that sets how ragged the shore is.
   */
  private static final double OCEAN_PERSISTENCE = 0.70d;

  /** River wavelength. Long enough that a river wanders rather than wiggles. */
  private static final double RIVER_WAVELENGTH = 224.0d;

  private static final int RIVER_OCTAVES = 3;

  /** Half-width of the river band in noise units; sets the river's chunk width indirectly. */
  private static final double RIVER_HALF_WIDTH = 0.012d;

  /**
   * Pond wavelength, in chunks. Short on purpose.
   *
   * <p>The real save carries about 1 200 separate unusable patches in a 384-chunk window, almost
   * all of them small, against one ocean. A mock with only large features produced 14. Ponds are
   * what supply that count, so their wavelength is a few chunks rather than a few dozen.
   */
  private static final double POND_WAVELENGTH = 3.0d;

  private static final int POND_OCTAVES = 1;

  private static final double POND_THRESHOLD = 0.42d;

  /**
   * Share of otherwise-usable chunks turned into a one-chunk water dot.
   *
   * <p>Taken from the raw lattice, one draw per chunk, so a dot cannot grow: an interpolated field
   * at any wavelength produces blobs of a few chunks at minimum, and the real save's unusable set is
   * dominated in <i>count</i> by single chunks - about 1 200 patches in a 384-chunk window against
   * one ocean. Without this term the mock's mean unusable patch measured 1.93x the save's, i.e. too
   * smooth, which is the direction that flatters a run-length table.
   *
   * <p>Rate tuned, not guessed: 0.045 over-dotted at 0.32x the save's patch size and 0.015 reached
   * 0.62x, where 0.008 reads 0.77x. Over-dotting is the opposite error and equally misleading - it
   * manufactures short runs.
   */
  private static final double SPECKLE_RATE = 0.008d;

  /** Default temperature wavelength, in chunks. Long enough for broad regional climate zones. */
  private static final double TEMP_WAVELENGTH = 384.0d;

  private static final int TEMP_OCTAVES = 4;

  /** Default humidity wavelength, in chunks. Crosses temperature to form 2D Whittaker biomes. */
  private static final double HUMID_WAVELENGTH = 384.0d;

  private static final int HUMID_OCTAVES = 4;

  /**
   * Share of inland chunks that are a lava pool.
   *
   * <p>Deliberately tiny: the real save measured a lava share of 0.000 at chunk-centre granularity,
   * so lava is present for shape rather than for area. Hashed per chunk, so a pool is never larger
   * than one chunk.
   */
  private static final double LAVA_RATE = 0.0015d;

  /**
   * Elevation above sea level a lava pool needs. Keeps lava off coastline, where in a real world it
   * would not survive contact with water, and stops it inflating the coastal boundary length.
   */
  private static final double LAVA_INLAND_MARGIN = 0.10d;

  /**
   * The knobs that decide terrain <i>shape</i> once density is calibrated away.
   *
   * <p>These were static constants, tuned by hand against one save. They are inputs now for one
   * reason: a mock is only useful as a stand-in if it can be matched to <b>whichever</b> world is
   * in front of it, and a set of constants fitted to one save is a set of constants fitted to one
   * save. {@code MockWorldFidelityBenchmarkTest} searches this space against each real save it
   * finds and reports the best match per save, so the same mock can be re-matched after the source
   * data is gone.
   *
   * <p>Sea level is deliberately <b>not</b> here: it is calibrated from the requested usable share,
   * so exposing it would let a search trade density for shape and produce a good fidelity score on
   * the wrong world.
   *
   * @param oceanOctaves octaves in the elevation field; more octaves means a more ragged coast
   * @param oceanPersistence amplitude decay per octave; higher means the fine octaves bite
   * @param riverHalfWidth half-width of the river band in noise units
   * @param pondThreshold pond field level above which inland ground floods
   * @param speckleRate share of inland chunks turned into a one-chunk dot
   */
  public record Params(
      int oceanOctaves,
      double oceanPersistence,
      double riverHalfWidth,
      double pondThreshold,
      double speckleRate,
      double tempWavelength,
      int tempOctaves,
      double humidWavelength,
      int humidOctaves) {

    public Params(
        int oceanOctaves,
        double oceanPersistence,
        double riverHalfWidth,
        double pondThreshold,
        double speckleRate) {
      this(
          oceanOctaves,
          oceanPersistence,
          riverHalfWidth,
          pondThreshold,
          speckleRate,
          TEMP_WAVELENGTH,
          TEMP_OCTAVES,
          HUMID_WAVELENGTH,
          HUMID_OCTAVES);
    }

    /** @return the constants tuned in ADR-085 section 10 against the first save examined */
    public static Params defaults() {
      return new Params(
          OCEAN_OCTAVES,
          OCEAN_PERSISTENCE,
          RIVER_HALF_WIDTH,
          POND_THRESHOLD,
          SPECKLE_RATE,
          TEMP_WAVELENGTH,
          TEMP_OCTAVES,
          HUMID_WAVELENGTH,
          HUMID_OCTAVES);
    }

    @Override
    public String toString() {
      return String.format(
          "octaves=%d, persistence=%.2f, riverHalf=%.4f, pond=%.3f, speckle=%.4f, tempW=%.1f, tempO=%d, humidW=%.1f, humidO=%d",
          oceanOctaves,
          oceanPersistence,
          riverHalfWidth,
          pondThreshold,
          speckleRate,
          tempWavelength,
          tempOctaves,
          humidWavelength,
          humidOctaves);
    }
  }

  private final long seed;
  private final Params params;
  private final double seaLevel;
  private final double targetUsableShare;
  private final double calibratedShare;
  private final int calibrationIterations;

  /**
   * @param seed reproducibility seed; every feature derives its own sub-seed from it
   * @param radiusChunks half-edge of the square window the calibration samples
   * @param targetUsableShare usable share to aim for, in {@code (0, 1)}
   */
  public NoiseWorldMask(long seed, int radiusChunks, double targetUsableShare) {
    this(seed, radiusChunks, targetUsableShare, Params.defaults());
  }

  /**
   * @param seed reproducibility seed; every feature derives its own sub-seed from it
   * @param radiusChunks half-edge of the square window the calibration samples
   * @param targetUsableShare usable share to aim for, in {@code (0, 1)}
   * @param params terrain shape knobs; density is still calibrated, not configured
   */
  public NoiseWorldMask(long seed, int radiusChunks, double targetUsableShare, Params params) {
    this.seed = seed;
    this.params = params;
    this.targetUsableShare = targetUsableShare;

    // Bisect sea level rather than solve it. The share is monotone in sea level - raising it can
    // only flood chunks, never drain them - so bisection converges, and the other three features
    // do not need to be inverted analytically. 24 halvings of [-1, 1] is well past the sampling
    // error of the strided estimate, so the residual is the sample, not the search.
    double lo = -1.0d;
    double hi = 1.0d;
    double share = 0.0d;
    int iterations = 0;
    for (; iterations < 24; iterations++) {
      double mid = 0.5d * (lo + hi);
      share = sampledUsableShare(radiusChunks, mid);
      if (Math.abs(share - targetUsableShare) < 0.002d) {
        lo = mid;
        hi = mid;
        break;
      }
      if (share > targetUsableShare) {
        lo = mid; // Too much land; flood more.
      } else {
        hi = mid;
      }
    }
    this.seaLevel = 0.5d * (lo + hi);
    this.calibratedShare = share;
    this.calibrationIterations = iterations;
  }

  /** @return the terrain shape knobs in force */
  public Params params() {
    return params;
  }

  /** @return the calibrated sea level in noise units */
  public double seaLevel() {
    return seaLevel;
  }

  /** @return the share the calibration was asked for */
  public double targetUsableShare() {
    return targetUsableShare;
  }

  /** @return the share the calibration reached on its strided sample */
  public double calibratedUsableShare() {
    return calibratedShare;
  }

  /** @return bisection steps taken; 24 means the target was not reached within tolerance */
  public int calibrationIterations() {
    return calibrationIterations;
  }

  /**
   * @param cx chunk x, unbounded
   * @param cz chunk z, unbounded
   * @return true when the chunk is usable ground
   */
  public boolean isOccupied(int cx, int cz) {
    return classify(cx, cz) == Terrain.LAND;
  }

  /**
   * @param cx chunk x, unbounded
   * @param cz chunk z, unbounded
   * @return which feature the chunk belongs to
   */
  public Terrain classify(int cx, int cz) {
    return classify(cx, cz, seaLevel);
  }

  /**
   * Temperature noise field in roughly {@code [-1, 1]}.
   *
   * @param cx chunk x
   * @param cz chunk z
   * @return temperature value
   */
  public double temperature(int cx, int cz) {
    return fbm(cx, cz, params.tempWavelength(), params.tempOctaves(), 0.5d, 0x7E3D129482A1B5L);
  }

  /**
   * Humidity noise field in roughly {@code [-1, 1]}.
   *
   * @param cx chunk x
   * @param cz chunk z
   * @return humidity value
   */
  public double humidity(int cx, int cz) {
    return fbm(cx, cz, params.humidWavelength(), params.humidOctaves(), 0.5d, 0x4A6B89E19F30C7L);
  }

  /**
   * Whittaker-style coarse biome classification for the chunk.
   *
   * @param cx chunk x
   * @param cz chunk z
   * @return coarse biome class
   */
  public BiomeClass biomeAt(int cx, int cz) {
    Terrain t = classify(cx, cz);
    if (t == Terrain.OCEAN || t == Terrain.RIVER || t == Terrain.POND) {
      return BiomeClass.AQUATIC;
    }

    double temp = temperature(cx, cz);
    double humid = humidity(cx, cz);

    if (temp < -0.25d) {
      return BiomeClass.COLD;
    }
    if (temp > 0.20d && humid < -0.10d) {
      return BiomeClass.BARREN;
    }
    return BiomeClass.LUSH;
  }

  /** Base classification plus the stranded-island filter. */
  private Terrain classify(int cx, int cz, double level) {
    Terrain base = classifyBase(cx, cz, level);
    if (base != Terrain.LAND) return base;
    for (int dz = -1; dz <= 1; dz++) {
      for (int dx = -1; dx <= 1; dx++) {
        if (dx == 0 && dz == 0) continue;
        if (classifyBase(cx + dx, cz + dz, level) == Terrain.LAND) return Terrain.LAND;
      }
    }
    return Terrain.ISOLATED;
  }

  /** Feature classification, before the stranded-island filter. Never recurses. */
  private Terrain classifyBase(int cx, int cz, double level) {
    double elevation =
        fbm(
            cx,
            cz,
            OCEAN_WAVELENGTH,
            params.oceanOctaves(),
            params.oceanPersistence(),
            0x51ED270BL);
    if (elevation < level) return Terrain.OCEAN;

    // River contour is taken around zero of its own field, so its path is independent of sea level
    // and does not move when the calibration runs. A river crossing ocean is simply ocean.
    double river = fbm(cx, cz, RIVER_WAVELENGTH, RIVER_OCTAVES, 0.5d, 0x2545F4914F6CDD1DL);
    if (Math.abs(river) < params.riverHalfWidth()) return Terrain.RIVER;

    if (fbm(cx, cz, POND_WAVELENGTH, POND_OCTAVES, 0.5d, 0x9E3779B97F4A7C15L)
        > params.pondThreshold()) {
      return Terrain.POND;
    }
    // Raw lattice, not fbm: one independent draw per chunk is what makes the feature one chunk wide.
    // Lattice is in [-1, 1], so a rate r is a threshold at 1 - 2r.
    if (lattice(cx, cz, 0x14057B7EF767814FL) > 1.0d - 2.0d * params.speckleRate()) {
      return Terrain.POND;
    }
    if (elevation > level + LAVA_INLAND_MARGIN
        && lattice(cx, cz, 0xBF58476D1CE4E5B9L) > 1.0d - 2.0d * LAVA_RATE) {
      return Terrain.LAVA;
    }
    return Terrain.LAND;
  }

  /**
   * Usable share over a strided sample of the window, at a candidate sea level.
   *
   * <p>Strided rather than exhaustive: the calibration runs 24 times and a border-scale window is
   * hundreds of millions of chunks. The stride is prime relative to every feature wavelength above,
   * so the sample does not land in phase with any of them.
   */
  private double sampledUsableShare(int radiusChunks, double level) {
    int stride = primeStride(Math.max(1, radiusChunks / 96));
    long land = 0L;
    long total = 0L;
    for (int cz = -radiusChunks; cz < radiusChunks; cz += stride) {
      for (int cx = -radiusChunks; cx < radiusChunks; cx += stride) {
        total++;
        if (classify(cx, cz, level) == Terrain.LAND) land++;
      }
    }
    return total == 0L ? 0.0d : (double) land / total;
  }

  /**
   * Smallest prime at or above {@code want}, so the sample cannot land in phase with a feature.
   *
   * <p>Not cosmetic: at pond wavelength 2.5 and stride 5 the strided estimate read 0.649 where the
   * exhaustive sweep read 0.693, because every sample fell at the same phase of the pond field.
   * That is a four-point calibration error caused entirely by the stride.
   */
  private static int primeStride(int want) {
    for (int n = Math.max(1, want); ; n++) {
      if (n < 4) return n == 1 ? 1 : n;
      boolean prime = (n & 1) == 1;
      for (int d = 3; prime && (long) d * d <= n; d += 2) prime = n % d != 0;
      if (prime) return n;
    }
  }

  /** Fractional Brownian motion over {@link #valueNoise}, normalised to roughly {@code [-1, 1]}. */
  private double fbm(
      int cx, int cz, double wavelength, int octaves, double persistence, long salt) {
    double sum = 0.0d;
    double amplitude = 1.0d;
    double norm = 0.0d;
    double frequency = 1.0d / wavelength;
    for (int o = 0; o < octaves; o++) {
      sum += amplitude * valueNoise(cx * frequency, cz * frequency, salt + o * 0x9E3779B9L);
      norm += amplitude;
      amplitude *= persistence;
      frequency *= 2.0d;
    }
    return sum / norm;
  }

  /** Bilinear value noise with smoothstep weights, in {@code [-1, 1]}. */
  private double valueNoise(double x, double z, long salt) {
    int x0 = (int) Math.floor(x);
    int z0 = (int) Math.floor(z);
    double fx = smoothstep(x - x0);
    double fz = smoothstep(z - z0);
    double n00 = lattice(x0, z0, salt);
    double n10 = lattice(x0 + 1, z0, salt);
    double n01 = lattice(x0, z0 + 1, salt);
    double n11 = lattice(x0 + 1, z0 + 1, salt);
    double a = n00 + (n10 - n00) * fx;
    double b = n01 + (n11 - n01) * fx;
    return a + (b - a) * fz;
  }

  private static double smoothstep(double t) {
    return t * t * (3.0d - 2.0d * t);
  }

  /** Lattice value in {@code [-1, 1]}, from SplitMix64 over the two coordinates and a salt. */
  private double lattice(int x, int z, long salt) {
    long h = (((long) x) * 0x9E3779B97F4A7C15L) ^ (((long) z) * 0xC2B2AE3D27D4EB4FL) ^ seed ^ salt;
    h += 0x9E3779B97F4A7C15L;
    h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
    h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
    h ^= h >>> 31;
    // Top 53 bits as a uniform double, then centred - the same construction Random#nextDouble uses.
    return ((h >>> 11) * 0x1.0p-53d) * 2.0d - 1.0d;
  }
}
