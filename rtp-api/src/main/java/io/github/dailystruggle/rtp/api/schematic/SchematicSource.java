package io.github.dailystruggle.rtp.api.schematic;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Value object describing where a schematic is loaded from. Resolution of the
 * on-disk path is owned by {@code rtp-core} (so the path policy and claim/footprint
 * logic stay platform-neutral); the paster only consumes this descriptor.
 *
 * <p>See ADR-058 §3.
 */
public final class SchematicSource {
  private final String name;
  private final Path path;
  private final String formatHint;

  /**
   * @param name       the configured logical name (typically the region name or the
   *                   value of the per-region {@code schematic} knob); never {@code null}
   * @param path       the resolved on-disk file path; never {@code null}
   * @param formatHint a lowercase format hint derived from the file extension
   *                   (e.g. {@code "schem"}, {@code "schematic"}, {@code "nbt"}), or
   *                   {@code ""} when unknown; never {@code null}
   */
  public SchematicSource(String name, Path path, String formatHint) {
    this.name = Objects.requireNonNull(name, "name");
    this.path = Objects.requireNonNull(path, "path");
    this.formatHint = (formatHint == null) ? "" : formatHint;
  }

  /** @return the configured logical name. */
  public String name() {
    return name;
  }

  /** @return the resolved on-disk file path. */
  public Path path() {
    return path;
  }

  /** @return a lowercase format hint, or {@code ""} when unknown. */
  public String formatHint() {
    return formatHint;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof SchematicSource)) return false;
    SchematicSource that = (SchematicSource) o;
    return name.equals(that.name)
        && path.equals(that.path)
        && formatHint.equals(that.formatHint);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, path, formatHint);
  }

  @Override
  public String toString() {
    return "SchematicSource[name=" + name + ", path=" + path + ", formatHint=" + formatHint + ']';
  }
}
