package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.schematic.SchematicSource;
import io.github.dailystruggle.rtp.common.RTP;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the region-specific arrival schematic for a teleport (ADR-058, Amendment 1).
 *
 * <p>There is deliberately <em>no</em> per-region config knob: the presence of a file at
 * {@code <pluginDir>/schematics/<region>.schem} (or {@code .schematic}) is the entire knob.
 * Drop a file named after the region into the {@code schematics} directory and that region's
 * teleports paste it at the arrival location; remove the file and the behavior reverts to the
 * default emergency platform. Resolution lives in {@code rtp-core} so the path policy stays
 * platform-neutral; each platform's {@code SchematicPaster} only consumes the resolved
 * {@link SchematicSource}.
 */
public final class RegionSchematicService {

  /** Sub-directory of the plugin data folder holding region schematic files. */
  public static final String SCHEMATICS_DIRNAME = "schematics";

  /** Recognised file extensions, in resolution-precedence order (Sponge {@code .schem} first). */
  private static final String[] EXTENSIONS = {"schem", "schematic"};

  private RegionSchematicService() {
  }

  /**
   * Resolves the schematic source for a region by file presence.
   *
   * @param regionName the region's name; may be {@code null}
   * @return a {@link SchematicSource} when a matching file exists, or {@code null} when there
   *     is no schematics directory, no plugin directory, or no file for this region
   */
  public static SchematicSource resolveSource(String regionName) {
    if (regionName == null || regionName.isEmpty()) {
      return null;
    }
    if (RTP.serverAccessor == null) {
      return null;
    }
    File pluginDir = RTP.serverAccessor.getPluginDirectory();
    if (pluginDir == null) {
      return null;
    }
    Path base = pluginDir.toPath().resolve(SCHEMATICS_DIRNAME);
    if (!Files.isDirectory(base)) {
      return null;
    }
    for (String ext : EXTENSIONS) {
      Path candidate = base.resolve(regionName + "." + ext);
      if (Files.isRegularFile(candidate)) {
        return new SchematicSource(regionName, candidate, ext);
      }
    }
    return null;
  }
}
