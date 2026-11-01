package io.github.dailystruggle.server.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Platform-neutral specification for an item stack carrying display text, lore lines, and custom
 * string metadata tags (ADR-049, ADR-115).
 *
 * <p>Allows core and sibling plugins to hand out or drop custom-tagged items via
 * {@link ServerAccessor#giveItem} and {@link ServerAccessor#dropItem} without constructing
 * platform-specific {@code ItemStack}, {@code PersistentDataContainer}, or {@code DataComponents}
 * instances.
 *
 * @governedBy ADR-049
 * @governedBy ADR-115
 */
public final class TaggedItemSpec {
  private final String itemKey;
  private final int amount;
  private final String displayName;
  private final List<String> lore;
  private final Map<String, String> customTags;

  /**
   * Constructs a platform-neutral tagged item specification.
   *
   * @param itemKey material or registry identifier (e.g. {@code "minecraft:oak_sapling"})
   * @param amount stack count (clamped to {@code >= 1})
   * @param displayName optional custom display name, or empty string
   * @param lore optional lore lines
   * @param customTags custom string metadata key-value pairs stamped onto the stack
   */
  public TaggedItemSpec(
      String itemKey,
      int amount,
      String displayName,
      List<String> lore,
      Map<String, String> customTags) {
    this.itemKey = Objects.requireNonNull(itemKey, "itemKey");
    this.amount = Math.max(1, amount);
    this.displayName = displayName == null ? "" : displayName;
    this.lore =
        lore == null || lore.isEmpty() ? Collections.emptyList() : List.copyOf(lore);
    this.customTags =
        customTags == null || customTags.isEmpty()
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(customTags);
  }

  public String itemKey() {
    return itemKey;
  }

  public int amount() {
    return amount;
  }

  public String displayName() {
    return displayName;
  }

  public List<String> lore() {
    return lore;
  }

  public Map<String, String> customTags() {
    return customTags;
  }
}
