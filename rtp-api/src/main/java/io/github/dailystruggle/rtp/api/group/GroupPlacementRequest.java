package io.github.dailystruggle.rtp.api.group;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable description of a multi-participant group teleport, resolved against a named region.
 *
 * <p>A request carries three things: the target {@link #regionName() region name}, the required
 * placement parameters as a {@link GroupProfileSpec}, and the ordered list of participant UUIDs.
 *
 * <p>The destination is keyed by <b>region name</b> (not world name) so a request targets the exact
 * region, matching {@code RtpTarget.region(...)} semantics.
 */
@PublicApi
public final class GroupPlacementRequest {

  private final String regionName;
  private final GroupProfileSpec profileSpec;
  private final List<UUID> participants;

  private GroupPlacementRequest(
      String regionName, GroupProfileSpec profileSpec, List<UUID> participants) {
    this.regionName = regionName;
    this.profileSpec = profileSpec;
    this.participants = Collections.unmodifiableList(new ArrayList<>(participants));
  }

  /**
   * Builds a request from the supplied placement parameters.
   *
   * @param regionName target region name; must not be {@code null} or blank
   * @param profileSpec required placement parameters; must not be {@code null}
   * @param participants ordered participant UUIDs; must be non-null and non-empty
   * @return an immutable request
   * @throws IllegalArgumentException if {@code regionName} is null/blank or {@code participants}
   *     is null/empty
   * @throws NullPointerException if {@code profileSpec} is {@code null}
   */
  public static GroupPlacementRequest of(
      String regionName, GroupProfileSpec profileSpec, List<UUID> participants) {
    requireText(regionName, "regionName");
    Objects.requireNonNull(profileSpec, "profileSpec must not be null");
    requireParticipants(participants);
    return new GroupPlacementRequest(regionName, profileSpec, participants);
  }

  private static void requireText(String value, String field) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(field + " must not be null or blank");
    }
  }

  private static void requireParticipants(List<UUID> participants) {
    if (participants == null || participants.isEmpty()) {
      throw new IllegalArgumentException("participants must not be null or empty");
    }
    if (participants.contains(null)) {
      throw new IllegalArgumentException("participants must not contain null entries");
    }
  }

  /**
   * @return the target region name; never {@code null} or blank
   */
  public String regionName() {
    return regionName;
  }

  /**
   * @return the supplied placement parameters; never {@code null}
   */
  public GroupProfileSpec profileSpec() {
    return profileSpec;
  }

  /**
   * @return an unmodifiable, ordered view of the participant UUIDs; never empty
   */
  public List<UUID> participants() {
    return participants;
  }

  /**
   * @return the number of participants to place
   */
  public int participantCount() {
    return participants.size();
  }

  @Override
  public String toString() {
    return "GroupPlacementRequest[region=" + regionName
        + ", profile=" + profileSpec
        + ", participants=" + participants.size() + ']';
  }
}
