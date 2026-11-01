package io.github.dailystruggle.rtp.common.benchmark;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Replaceable persistence seam and format codec for learned state tables across curve changes
 * (ADR-085 criterion C9).
 *
 * <p>Why a replaceable seam: the same key space is what a shared cross-server database would key on,
 * and the curve must not know which backend (local file, redis, sql, or network peer) it is talking
 * to. Note the database-sharing implication: servers sharing the same seed and region configuration
 * address identical chunk sets with identical keys, enabling shared learned state without key
 * translation.
 *
 * <h2>Header specification (Version 5)</h2>
 * <pre>
 * magic (int, 0x52545031 "RTP1")
 * version (int, 5)
 * curve (int len + UTF-8 bytes, e.g. "SPIRAL" or "SPIRAL_HILBERT")
 * P (int, point edge in chunks; 1 for spiral, 16/32/etc for hybrid)
 * keyWidth (int, 4 or 8 bytes)
 * worldName (int len + UTF-8 bytes)
 * scanStride (long)
 * badSize (int)
 * entries:
 *   [key (keyWidth bytes) + delta (keyWidth bytes) + cause (1 byte) + expiresAt (8 bytes)] * badSize
 * </pre>
 *
 * <h2>Unit mismatch handling</h2>
 * <ul>
 *   <li>When the stored P divides the new P (new P is a multiple of stored P), the table is
 *       <b>folded upward losslessly</b>.
 *   <li>Otherwise, the table cannot be mapped without destroying learned state or misinterpreting
 *       spatial keys, so it is discarded and relearned.
 * </ul>
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier; excluded from {@code build}. D-005 gate closed.
 */
public final class CurvePersistenceSeam {

  public static final int BIN_MAGIC = 0x52545031;
  public static final int BIN_VERSION = 3;

  public static final String CURVE_SPIRAL = "SPIRAL";
  public static final String CURVE_SPIRAL_HILBERT = "SPIRAL_HILBERT";

  public record TableHeader(
      int version,
      String curve,
      int p,
      int keyWidth,
      String worldName,
      long scanStride,
      int runCount) {}

  public record StoredRun(long key, long length, byte cause, long expiresAt) {}

  public record DeserializationResult(
      TableHeader header,
      List<StoredRun> runs,
      boolean foldedUpward,
      boolean discardedAndRelearned,
      String logMessage) {}

  /** Replaceable backend seam interface. */
  public interface PersistenceBackend {
    void store(String identifier, byte[] data);
    byte[] load(String identifier);
  }

  /** In-memory replaceable backend implementation. */
  public static class MemoryBackend implements PersistenceBackend {
    private final java.util.Map<String, byte[]> store = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void store(String identifier, byte[] data) {
      store.put(identifier, data.clone());
    }

    @Override
    public byte[] load(String identifier) {
      byte[] b = store.get(identifier);
      return b != null ? b.clone() : null;
    }
  }

  /** Derives key width in bytes from total range: fits 32-bit int out to a 100 km border. */
  public static int deriveKeyWidth(long range) {
    return (range <= Integer.MAX_VALUE) ? 4 : 8;
  }

  /** Serializes a table to bytes with the modern (Version 3) header. */
  public static byte[] serialize(
      String curve,
      int p,
      String worldName,
      long scanStride,
      long[] keys,
      long[] deltas,
      byte[] causes,
      long[] expiries,
      int totalRuns,
      long maxRange) {
    int keyWidth = deriveKeyWidth(maxRange);
    byte[] curveBytes = curve.getBytes(StandardCharsets.UTF_8);
    byte[] worldBytes = worldName.getBytes(StandardCharsets.UTF_8);

    int entrySize = keyWidth * 2 + 1 + 8; // key + delta + cause + expiresAt
    int totalBytes = 4 + 4 // magic + version
        + 4 + curveBytes.length // curve
        + 4 // P
        + 4 // keyWidth
        + 4 + worldBytes.length // worldName
        + 8 // scanStride
        + 4 // badSize
        + totalRuns * entrySize;

    ByteBuffer buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.BIG_ENDIAN);
    buf.putInt(BIN_MAGIC);
    buf.putInt(BIN_VERSION);
    buf.putInt(curveBytes.length);
    buf.put(curveBytes);
    buf.putInt(p);
    buf.putInt(keyWidth);
    buf.putInt(worldBytes.length);
    buf.put(worldBytes);
    buf.putLong(scanStride);
    buf.putInt(totalRuns);

    for (int i = 0; i < totalRuns; i++) {
      if (keyWidth == 4) {
        buf.putInt((int) keys[i]);
        buf.putInt((int) deltas[i]);
      } else {
        buf.putLong(keys[i]);
        buf.putLong(deltas[i]);
      }
      buf.put(causes[i]);
      buf.putLong(expiries[i]);
    }

    return buf.array();
  }

  /**
   * Deserializes binary payload, applying unit mismatch and upward folding rules.
   *
   * @param data binary payload
   * @param expectedCurve curve of the runtime shape
   * @param targetP point edge P of the runtime shape
   * @param expectedWorld expected world name
   * @param runtimeShape runtime shape for coordinate folding if needed
   * @return deserialization result
   */
  public static DeserializationResult deserialize(
      byte[] data,
      String expectedCurve,
      int targetP,
      String expectedWorld,
      MemoryShape<?> runtimeShape) {
    if (data == null || data.length < 16) {
      return new DeserializationResult(null, List.of(), false, true, "data truncated or absent");
    }

    ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
    int magic = buf.getInt();
    if (magic != BIN_MAGIC) {
      return new DeserializationResult(null, List.of(), false, true, "invalid binary magic");
    }

    int version = buf.getInt();
    if (version != 3) {
      // Legacy versions (1/2) lack the curve/P header and cannot be safely reinterpreted
      // across a curve change; retired intermediate tags (4/5) never shipped.
      return new DeserializationResult(null, List.of(), false, true, "non-v3 format discarded and relearned");
    }

    int curveLen = buf.getInt();
    byte[] cBytes = new byte[curveLen];
    buf.get(cBytes);
    String storedCurve = new String(cBytes, StandardCharsets.UTF_8);

    int storedP = buf.getInt();
    int keyWidth = buf.getInt();

    int wLen = buf.getInt();
    byte[] wBytes = new byte[wLen];
    buf.get(wBytes);
    String worldName = new String(wBytes, StandardCharsets.UTF_8);

    if (!worldName.equals(expectedWorld)) {
      return new DeserializationResult(null, List.of(), false, true, "world mismatch: " + worldName + " != " + expectedWorld);
    }

    long scanStride = buf.getLong();
    int badSize = buf.getInt();

    TableHeader header = new TableHeader(version, storedCurve, storedP, keyWidth, worldName, scanStride, badSize);

    // Read stored runs
    List<StoredRun> storedRuns = new ArrayList<>(badSize);
    for (int i = 0; i < badSize; i++) {
      long key = (keyWidth == 4) ? (buf.getInt() & 0xFFFFFFFFL) : buf.getLong();
      long delta = (keyWidth == 4) ? (buf.getInt() & 0xFFFFFFFFL) : buf.getLong();
      byte cause = buf.get();
      long exp = buf.getLong();
      storedRuns.add(new StoredRun(key, delta, cause, exp));
    }

    // Check curve agreement
    if (!storedCurve.equals(expectedCurve)) {
      return new DeserializationResult(
          header,
          List.of(),
          false,
          true,
          "curve mismatch (" + storedCurve + " -> " + expectedCurve + "): discarded and relearned");
    }

    // Check P agreement
    if (storedP == targetP) {
      return new DeserializationResult(header, storedRuns, false, false, "exact match loaded: P=" + targetP);
    }

    // Upward fold check: targetP must be a multiple of storedP
    if (storedP > 0 && targetP > storedP && (targetP % storedP == 0)) {
      List<StoredRun> folded = foldUpward(storedRuns, storedCurve, storedP, targetP, runtimeShape);
      return new DeserializationResult(
          header,
          folded,
          true,
          false,
          "lossless upward fold P=" + storedP + " -> " + targetP);
    }

    // Non-multiple unit mismatch
    return new DeserializationResult(
        header,
        List.of(),
        false,
        true,
        "incompatible P (" + storedP + " -> " + targetP + " not a multiple): discarded and relearned");
  }

  /**
   * Folds runs upward from storedP to targetP when targetP is a multiple of storedP.
   */
  private static List<StoredRun> foldUpward(
      List<StoredRun> storedRuns,
      String curve,
      int storedP,
      int targetP,
      MemoryShape<?> targetShape) {
    if (targetShape == null) {
      return storedRuns;
    }

    // Re-map keys into chunk space using stored curve vehicle then map into targetShape
    MemoryShape<?> storedShape;
    long radius = (long) Math.sqrt((double) targetShape.getRange()) / 2L;
    if (radius < 1L) radius = 256L;
    if (CURVE_SPIRAL.equals(curve) || storedP == 1) {
      Square sq = new Square("TEMP_STORED_SPIRAL");
      sq.set(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams.radius, radius);
      storedShape = sq;
    } else {
      storedShape = new SpiralHilbertSquare((int) radius, storedP, true);
    }

    MutableRTPCoords coords = new MutableRTPCoords(0, 0);
    List<Long> remappedKeys = new ArrayList<>();
    for (StoredRun run : storedRuns) {
      for (long k = run.key(); k < run.key() + run.length(); k++) {
        storedShape.locationToXZ(k, coords);
        long targetKey = targetShape.xzToLocation(coords.x, coords.z);
        remappedKeys.add(targetKey);
      }
    }

    long[] kArr = new long[remappedKeys.size()];
    for (int i = 0; i < kArr.length; i++) kArr[i] = remappedKeys.get(i);
    Arrays.sort(kArr);

    KeyRunTable coalesced = KeyRunTable.exact(kArr, kArr.length);
    List<StoredRun> out = new ArrayList<>();
    for (int r = 0; r < coalesced.runs(); r++) {
      out.add(new StoredRun(coalesced.start(r), coalesced.length(r), (byte) 0, 0L));
    }
    return out;
  }
}
