package io.github.dailystruggle.rtp.guiaddon.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
   * <p>Primary destinations (regions/default) and submenus (actions/biome selectors)
   * are placed on distinct lines so they do not crowd each other. Within each row,
   * items are evenly spaced if count <= 50% of the inner column width (1..3 items),
   * and packed contiguously centered if count > 50% (4..7 items).
   *
   * @param model the pre-resolved, platform-neutral menu contents
   * @return an immutable layout; never {@code null}
   */
  public static MenuLayout compute(MenuModel model) {
    List<MenuEntry> destinations = new java.util.ArrayList<>();
    List<MenuEntry> submenus = new java.util.ArrayList<>();

    for (MenuEntry entry : model.entries()) {
      if (entry == null) continue;
      if (entry.target() != null && entry.target().kind() == io.github.dailystruggle.rtp.api.RtpTarget.Kind.ACTION) {
        submenus.add(entry);
      } else {
        destinations.add(entry);
      }
    }

    // How many rows needed for destinations and submenus
    int destRows = Math.max(0, (int) Math.ceil(destinations.size() / (double) ITEMS_PER_ROW));
    int submenuRows = Math.max(0, (int) Math.ceil(submenus.size() / (double) ITEMS_PER_ROW));

    int contentRows = destRows + submenuRows;
    if (contentRows == 0) {
      contentRows = 1;
    }

    // Total rows = inner content + top border + bottom border, clamped to [3, MAX_ROWS].
    int rows = Math.max(3, Math.min(MAX_ROWS, contentRows + 2));
    int innerRows = rows - 2;

    // Reserve submenu rows so that submenus are never crowded out by destination overflow
    if (!submenus.isEmpty() && destRows + submenuRows > innerRows) {
      int maxAllowedDestRows = Math.max(0, innerRows - submenuRows);
      if (destRows > maxAllowedDestRows) {
        destRows = maxAllowedDestRows;
        io.github.dailystruggle.rtp.common.RTP.log(
            java.util.logging.Level.WARNING,
            "[RTP-GUI] Menu layout exceeded inner row capacity; reserving submenu row and capping destinations");
      }
    }

    // Vertically centre the content block within the inner rows if spare rows exist.
    int topRow = 1 + Math.max(0, (innerRows - (destRows + submenuRows)) / 2);

    int dashboardSlot = model.showDashboard() ? (rows - 1) * COLUMNS + (COLUMNS / 2) : -1;
    Map<Integer, MenuEntry> slotEntries = new LinkedHashMap<>();

    // Place destinations on the top content row(s)
    int currentContentRow = topRow;
    if (!destinations.isEmpty()) {
      int placed = 0;
      for (int r = 0; r < destRows && currentContentRow <= innerRows; r++, currentContentRow++) {
        int remaining = destinations.size() - placed;
        int countInRow = Math.min(ITEMS_PER_ROW, remaining);
        for (int i = 0; i < countInRow; i++) {
          MenuEntry entry = destinations.get(placed++);
          int col = spreadColumn(i, countInRow);
          int slot = currentContentRow * COLUMNS + INNER_FIRST_COL + col;
          slotEntries.put(slot, entry);
        }
      }
    }

    // Place submenus on the subsequent content row(s)
    if (!submenus.isEmpty()) {
      int placed = 0;
      for (int r = 0; r < submenuRows && currentContentRow <= innerRows; r++, currentContentRow++) {
        int remaining = submenus.size() - placed;
        int countInRow = Math.min(ITEMS_PER_ROW, remaining);
        for (int i = 0; i < countInRow; i++) {
          MenuEntry entry = submenus.get(placed++);
          int col = spreadColumn(i, countInRow);
          int slot = currentContentRow * COLUMNS + INNER_FIRST_COL + col;
          slotEntries.put(slot, entry);
        }
      }
    }

    return new MenuLayout(rows, slotEntries, dashboardSlot);
  }

  /**
   * Maps the {@code index}-th of {@code count} items to an inner column in
   * {@code [0, ITEMS_PER_ROW)}.
   *
   * <p>When {@code count <= 50% of column width} (1..3 items), items are distributed
   * evenly with balanced spacing so destinations are not packed side-by-side.
   * When {@code count > 50% of column width} (4..7 items), items are packed
   * contiguously and centered in the row.
   */
  public static int spreadColumn(int index, int count) {
    int safeCount = Math.max(1, Math.min(ITEMS_PER_ROW, count));
    int safeIndex = Math.max(0, Math.min(index, safeCount - 1));

    // When count <= 50% column width (ITEMS_PER_ROW = 7, so <= 3 items):
    // Evenly space them:
    // 1 item -> inner col 3 (center)
    // 2 items -> inner cols 1, 5
    // 3 items -> inner cols 1, 3, 5
    if (safeCount <= ITEMS_PER_ROW / 2) {
      switch (safeCount) {
        case 1:
          return 3;
        case 2:
          return (safeIndex == 0) ? 1 : 5;
        case 3:
          return (safeIndex == 0) ? 1 : (safeIndex == 1 ? 3 : 5);
        default:
          break;
      }
    }

    // When count > 50% column width (4..7 items):
    // Pack contiguously centered in the row.
    int startCol = (ITEMS_PER_ROW - safeCount) / 2;
    return startCol + safeIndex;
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
