package io.github.dailystruggle.rtp.groupaddon;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An unverified candidate subspace entry in the Group L3 backlog buffer.
 * Holds candidate anchor coordinates, profile, footprint, and candidate slots
 * to be screened off-tick against region file data or candidate validators.
 */
public final class GroupBacklogEntry {
  public enum Validity {
    UNVERIFIED,
    VALIDATED,
    INVALIDATED
  }

  private final RTPCoords anchor;
  private final int blockRadius;
  private final GroupProfile profile;
  private final List<RTPCoords> candidateSlots;
  private final AtomicReference<Validity> validity = new AtomicReference<>(Validity.UNVERIFIED);
  private final List<RTPLocation> validatedSlots = Collections.synchronizedList(new ArrayList<>());

  public GroupBacklogEntry(
      RTPCoords anchor,
      int blockRadius,
      GroupProfile profile,
      List<RTPCoords> candidateSlots) {
    this.anchor = Objects.requireNonNull(anchor, "anchor cannot be null");
    this.blockRadius = blockRadius;
    this.profile = Objects.requireNonNull(profile, "profile cannot be null");
    this.candidateSlots =
        (candidateSlots == null)
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(candidateSlots));
  }

  public RTPCoords anchor() {
    return anchor;
  }

  public int blockRadius() {
    return blockRadius;
  }

  public GroupProfile profile() {
    return profile;
  }

  public List<RTPCoords> candidateSlots() {
    return candidateSlots;
  }

  public Validity validity() {
    return validity.get();
  }

  public void setValidity(Validity newValidity) {
    validity.set(newValidity);
  }

  public boolean compareAndSetValidity(Validity expect, Validity update) {
    return validity.compareAndSet(expect, update);
  }

  public List<RTPLocation> validatedSlots() {
    return Collections.unmodifiableList(new ArrayList<>(validatedSlots));
  }

  public void addValidatedSlot(RTPLocation location) {
    if (location != null) {
      validatedSlots.add(location);
    }
  }

  public void setValidatedSlots(List<RTPLocation> slots) {
    validatedSlots.clear();
    if (slots != null) {
      validatedSlots.addAll(slots);
    }
  }
}
