package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.AbstractVerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import java.util.ArrayList;
import java.util.List;

/**
 * Ticket radius required by enabled mountainside checks.
 *
 * @governedBy ADR-120 (Mountainside and Cliff-Edge Check over the Resident 3x3 Square)
 * @trace REQ-RTP-F-015
 * @invariant S-005: the selected footprint only pins chunks; it never synchronously loads them.
 * @prohibition Do not widen tickets when the hill check is disabled.
 */
@SuppressWarnings("JavadocDeclaration") // ADR traceability tags
public final class TicketFootprint {
  private static final long CACHE_NANOS = 1_000_000_000L;
  private static volatile Snapshot snapshot = new Snapshot(0L, -1);

  private TicketFootprint() {}

  /** Returns the cached radius needed by the current permanent and per-world regions. */
  public static int ticketDistance() {
    long now = System.nanoTime();
    Snapshot current = snapshot;
    if (current.distance >= 0 && now - current.createdNanos < CACHE_NANOS) return current.distance;

    // Lock-free: concurrent refreshes compute the same value from the same region maps, so a
    // racing publish is harmless (last writer wins).
    List<Double> hillAngles = new ArrayList<>();
    SelectionAPI selection = ActiveSelection.get();
    if (selection != null) {
      selection.permRegionLookup.values().forEach(region -> addHillAngle(region, hillAngles));
      selection.tempRegions.values().forEach(region -> addHillAngle(region, hillAngles));
    }
    int distance = requiredDistance(hillAngles);
    snapshot = new Snapshot(now, distance);
    return distance;
  }

  private static void addHillAngle(Region region, List<Double> hillAngles) {
    VerticalAdjustor<?> adjustor = region.getVert();
    if (adjustor instanceof AbstractVerticalAdjustor<?> vertical) {
      hillAngles.add(vertical.maxHillAngle());
    }
  }

  static int requiredDistance(Iterable<Double> maxHillAngles) {
    for (Double maxHillAngle : maxHillAngles) {
      if (maxHillAngle != null && maxHillAngle >= 0.0d) return 1;
    }
    return 0;
  }

  static void resetForTesting() {
    snapshot = new Snapshot(0L, -1);
  }

  private record Snapshot(long createdNanos, int distance) {}
}
