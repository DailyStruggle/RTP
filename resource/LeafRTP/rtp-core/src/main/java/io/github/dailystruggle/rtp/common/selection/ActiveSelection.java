package io.github.dailystruggle.rtp.common.selection;

/**
 * Process-wide slot for the live {@link SelectionAPI} (ADR-115 seam 4).
 *
 * <p>Replaces the former {@code ActiveSelection.get()} static. The slot is never {@code null} unless a
 * caller clears it: like the former field, it holds a {@link SelectionAPI} from first access, and
 * the composition root attaches the shape factory during its static initialization. Tests and
 * reload paths may replace the instance with {@link #set(SelectionAPI)}.
 */
public final class ActiveSelection {
  private static volatile SelectionAPI current = new SelectionAPI();

  private ActiveSelection() {
  }

  /** The live selection API. */
  public static SelectionAPI get() {
    return current;
  }

  /** Installs {@code selection} as current. */
  public static void set(SelectionAPI selection) {
    current = selection;
  }
}
