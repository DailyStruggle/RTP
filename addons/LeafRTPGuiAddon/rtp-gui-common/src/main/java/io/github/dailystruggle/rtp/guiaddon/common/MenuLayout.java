package io.github.dailystruggle.rtp.guiaddon.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Platform-neutral slot placement for a {@link MenuModel}.
 *
 * <p>This is the single source of truth for <em>where</em> each destination icon,
 * the optional server-health dashboard tile, and the chest size land - the layout
 * that every renderer (Bukkit chest, Fabric / NeoForge container screen, ...) must
 * share so the menu looks identical on every platform. Previously this math lived
 * only in the Bukkit renderer while the Fabric / NeoForge screens placed icons
 * linearly from slot 0; centralising it here keeps the polished centred layout
 * generic and removes the per-platform drift.
 *
 * <p>The menu is framed like a polished server menu: a one-cell border wraps a
 * centred inner content block. Within each content row the destinations are spread
 * <em>evenly</em> across the inner width rather than packed shoulder-to-shoulder:
 * a single entry lands dead centre, two or more entries are distributed with even
 * gaps (and padding around the edges), and a row that fills the inner width falls
 * back to a contiguous run. The optional dashboard tile sits centred on the bottom
 * border row, costing no extra inner row.
 */
public final class MenuLayout {

  /** Slots per chest row (fixed by the vanilla container grid). */
  public static final int COLUMNS = 9;

  /** Destination icons per content row (column 0 and 8 are always border). */
  private static final int ITEMS_PER_ROW = 7;
  private static final int MAX_ROWS = 6;
  /** First usable inner column (column 0 is always border). */
  private static final int INNER_FIRST_COL = 1;

  private final int rows;
  private final Map<Integer, MenuEntry> slotEntries;
  private final int dashboardSlot;

  private MenuLayout(int rows, Map<Integer, MenuEntry> slotEntries, int dashboardSlot) {
    this.rows = rows;
    this.slotEntries = Collections.unmodifiableMap(slotEntries);
    this.dashboardSlot = dashboardSlot;
  }

  /**
   * Computes the centred, border-framed placement for {@code model}.
   *
   * @param model the pre-resolved, platform-neutral menu contents
   * @return an immutable layout; never {@code null}
   */
  public static MenuLayout compute(MenuModel model) {
    int entryCount = model.entries().size();

    // Content lives in the inner area, framed by a one-cell border on every edge.
    int contentRows = Math.max(1, (int) Math.ceil(entryCount / (double) ITEMS_PER_ROW));

    // Total rows = inner content + top border + bottom border, clamped so the
    // frame is always present (minimum 3 rows -> at least one inner row).
    int rows = Math.max(3, Math.min(MAX_ROWS, contentRows + 2));
    int innerRows = rows - 2;
    contentRows = Math.min(contentRows, innerRows);

    // Vertically centre the content block within the inner rows.
    int topRow = 1 + Math.max(0, (innerRows - contentRows) / 2);

    int dashboardSlot = model.showDashboard() ? (rows - 1) * COLUMNS + (COLUMNS / 2) : -1;

    Map<Integer, MenuEntry> slotEntries = new LinkedHashMap<>();
    int maxEntries = contentRows * ITEMS_PER_ROW;
    int placed = 0;
    for (MenuEntry entry : model.entries()) {
      if (placed >= maxEntries) break;
      int rowIndex = placed / ITEMS_PER_ROW;
      int rowCount = Math.min(ITEMS_PER_ROW, entryCount - rowIndex * ITEMS_PER_ROW);
      int colInRow = placed % ITEMS_PER_ROW;
      // Spread this row's items evenly across the 7-wide inner area, preferring
      // padding around the edges. A single item lands dead centre; a full row
      // falls back to a contiguous run.
      int col = spreadColumn(colInRow, rowCount);
      int slot = (topRow + rowIndex) * COLUMNS + INNER_FIRST_COL + col;
      slotEntries.put(slot, entry);
      placed++;
    }

    return new MenuLayout(rows, slotEntries, dashboardSlot);
  }

  /**
   * Maps the {@code index}-th of {@code count} items to an inner column in
   * {@code [0, ITEMS_PER_ROW)}, spread evenly with symmetric edge padding.
   *
   * <p>The midpoint formula {@code floor((index + 0.5) * width / count)} centres a
   * lone item, distributes 2+ items with even gaps, and degenerates to a contiguous
   * run once {@code count} reaches the inner width.
   */
  private static int spreadColumn(int index, int count) {
    int safeCount = Math.max(1, count);
    int col = (int) Math.floor((index + 0.5) * ITEMS_PER_ROW / safeCount);
    if (col < 0) return 0;
    if (col > ITEMS_PER_ROW - 1) return ITEMS_PER_ROW - 1;
    return col;
  }

  /** Number of chest rows. */
  public int rows() {
    return rows;
  }

  /** Total slot count ({@code rows * 9}). */
  public int size() {
    return rows * COLUMNS;
  }

  /** Immutable map of chest slot index to the destination entry placed there. */
  public Map<Integer, MenuEntry> slotEntries() {
    return slotEntries;
  }

  /** Slot index of the dashboard tile, or {@code -1} when the dashboard is disabled. */
  public int dashboardSlot() {
    return dashboardSlot;
  }

  /** Whether a dashboard tile should be drawn. */
  public boolean hasDashboard() {
    return dashboardSlot >= 0;
  }
}
