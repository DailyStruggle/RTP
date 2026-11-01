package io.github.dailystruggle.server.api;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * Platform-neutral context for a player right-clicking a block (ADR-049, ADR-115).
 *
 * <p>Carries pre-extracted block coordinates, item identifiers, custom item tags, liquid state,
 * permission checks, item consumption, and synchronous event cancellation so subscribers in core or
 * sibling modules never import platform event or item classes.
 *
 * @governedBy ADR-049
 * @governedBy ADR-115
 */
public final class PlayerInteractBlockContext {
  private final UUID playerId;
  private final String worldName;
  private final int clickedX;
  private final int clickedY;
  private final int clickedZ;
  private final int targetX;
  private final int targetY;
  private final int targetZ;
  private final boolean mainHand;
  private final boolean creativeMode;
  private final boolean clickedOrTargetLiquid;
  private final String itemKey;
  private final Map<String, String> customTags;
  private final Predicate<String> permissionPredicate;
  private final IntConsumer itemConsumer;
  private boolean cancelled;

  /**
   * Constructs a platform-neutral block interaction context.
   *
   * @param playerId interacting player's UUID; must not be {@code null}
   * @param worldName canonical world or level name; must not be {@code null}
   * @param clickedX clicked block X
   * @param clickedY clicked block Y
   * @param clickedZ clicked block Z
   * @param targetX adjacent target block X (placement position)
   * @param targetY adjacent target block Y (placement position)
   * @param targetZ adjacent target block Z (placement position)
   * @param mainHand {@code true} if the interaction used the main hand
   * @param creativeMode {@code true} if the player is in creative mode
   * @param clickedOrTargetLiquid {@code true} if either clicked or target block is liquid
   * @param itemKey held item identifier, or empty string if none
   * @param customTags custom string metadata tags attached to the held item
   * @param permissionPredicate evaluates whether the player holds a permission node
   * @param itemConsumer decrements the held item stack by the given amount
   */
  public PlayerInteractBlockContext(
      UUID playerId,
      String worldName,
      int clickedX,
      int clickedY,
      int clickedZ,
      int targetX,
      int targetY,
      int targetZ,
      boolean mainHand,
      boolean creativeMode,
      boolean clickedOrTargetLiquid,
      String itemKey,
      Map<String, String> customTags,
      Predicate<String> permissionPredicate,
      IntConsumer itemConsumer) {
    this.playerId = Objects.requireNonNull(playerId, "playerId");
    this.worldName = Objects.requireNonNull(worldName, "worldName");
    this.clickedX = clickedX;
    this.clickedY = clickedY;
    this.clickedZ = clickedZ;
    this.targetX = targetX;
    this.targetY = targetY;
    this.targetZ = targetZ;
    this.mainHand = mainHand;
    this.creativeMode = creativeMode;
    this.clickedOrTargetLiquid = clickedOrTargetLiquid;
    this.itemKey = itemKey == null ? "" : itemKey;
    this.customTags =
        customTags == null || customTags.isEmpty()
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(customTags);
    this.permissionPredicate = permissionPredicate != null ? permissionPredicate : perm -> true;
    this.itemConsumer = itemConsumer != null ? itemConsumer : amt -> {};
  }

  public UUID playerId() {
    return playerId;
  }

  public String worldName() {
    return worldName;
  }

  public int clickedX() {
    return clickedX;
  }

  public int clickedY() {
    return clickedY;
  }

  public int clickedZ() {
    return clickedZ;
  }

  public int targetX() {
    return targetX;
  }

  public int targetY() {
    return targetY;
  }

  public int targetZ() {
    return targetZ;
  }

  public boolean mainHand() {
    return mainHand;
  }

  public boolean creativeMode() {
    return creativeMode;
  }

  public boolean clickedOrTargetLiquid() {
    return clickedOrTargetLiquid;
  }

  public String itemKey() {
    return itemKey;
  }

  public Map<String, String> customTags() {
    return customTags;
  }

  public String customTag(String key) {
    return customTags.get(key);
  }

  public boolean hasPermission(String permission) {
    if (permission == null || permission.isEmpty()) {
      return true;
    }
    return permissionPredicate.test(permission);
  }

  public void consumeItem(int amount) {
    if (amount > 0 && !creativeMode) {
      itemConsumer.accept(amount);
    }
  }

  public void cancel() {
    this.cancelled = true;
  }

  public boolean isCancelled() {
    return cancelled;
  }
}
