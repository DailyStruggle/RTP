package io.github.dailystruggle.rtp.common.mock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Bounded in-memory log of BIT mock landings (ADR-121 section 11), kept apart from the ADR-104
 * {@code LandingHeatmap} so probes never pollute production data. Oldest entries are evicted past
 * {@link #CAPACITY}. Thread-safe; lost on restart. Exposes data only.
 */
public final class BitTeleportLog {

  public static final int CAPACITY = 4096;

  private static final ArrayDeque<MockTeleportRecord> RECORDS = new ArrayDeque<>();

  private BitTeleportLog() {}

  public static void append(MockTeleportRecord record) {
    Objects.requireNonNull(record, "record");
    synchronized (RECORDS) {
      if (RECORDS.size() >= CAPACITY) RECORDS.pollFirst();
      RECORDS.addLast(record);
    }
  }

  /** Immutable copy, oldest first. */
  public static List<MockTeleportRecord> snapshot() {
    synchronized (RECORDS) {
      return Collections.unmodifiableList(new ArrayList<>(RECORDS));
    }
  }

  /** Immutable copy of the records for {@code region}, oldest first. */
  public static List<MockTeleportRecord> forRegion(String region) {
    List<MockTeleportRecord> out = new ArrayList<>();
    synchronized (RECORDS) {
      for (MockTeleportRecord r : RECORDS) {
        if (Objects.equals(region, r.region())) out.add(r);
      }
    }
    return Collections.unmodifiableList(out);
  }

  public static void clear() {
    synchronized (RECORDS) {
      RECORDS.clear();
    }
  }
}
