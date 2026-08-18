package io.github.dailystruggle.rtp.api.schematic;

import java.util.Objects;

/**
 * Immutable paste tuning passed to {@link SchematicPaster#paste}.
 *
 * <p>See ADR-058 section 1.
 */
public final class PasteOptions {
  private final PasteAnchor anchor;
  private final boolean pasteAir;
  private final boolean claimAware;

  /**
   * @param anchor     how to position the schematic relative to the arrival block;
   *                   never {@code null}
   * @param pasteAir   {@code true} to write air blocks from the schematic, {@code false}
   *                   to skip air (leave existing terrain where the schematic is empty)
   * @param claimAware {@code true} when the core footprint claim check (S-003) is active
   */
  public PasteOptions(PasteAnchor anchor, boolean pasteAir, boolean claimAware) {
    this.anchor = Objects.requireNonNull(anchor, "anchor");
    this.pasteAir = pasteAir;
    this.claimAware = claimAware;
  }

  /** @return default options: {@link PasteAnchor#BOTTOM_CENTER}, skip air, claim-aware. */
  public static PasteOptions defaults() {
    return new PasteOptions(PasteAnchor.BOTTOM_CENTER, false, true);
  }

  /** @return the anchor policy. */
  public PasteAnchor anchor() {
    return anchor;
  }

  /** @return whether air blocks are written. */
  public boolean pasteAir() {
    return pasteAir;
  }

  /** @return whether the footprint claim check is active. */
  public boolean claimAware() {
    return claimAware;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof PasteOptions)) return false;
    PasteOptions that = (PasteOptions) o;
    return pasteAir == that.pasteAir
        && claimAware == that.claimAware
        && anchor == that.anchor;
  }

  @Override
  public int hashCode() {
    return Objects.hash(anchor, pasteAir, claimAware);
  }

  @Override
  public String toString() {
    return "PasteOptions[anchor=" + anchor + ", pasteAir=" + pasteAir
        + ", claimAware=" + claimAware + ']';
  }
}
