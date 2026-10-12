package io.github.dailystruggle.rtp.common.playerData;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Persists and conditionally purges offline player teleport cooldown records across rotating disk file bins.
 * Each epoch interval writes to {@code bin_<binId>.dat}; expired bins are purged as whole file deletions.
 */
public class FileBinStore {
  private static final Pattern BIN_FILE_PATTERN = Pattern.compile("^bin_(\\d+)\\.dat$");
  private static final int MAGIC = 0x52545042; // "RTPB"

  private final File baseDir;
  private final long binWidthMs;
  private volatile long retentionMs;
  private final Object lock = new Object();

  public FileBinStore(File baseDir, long binWidthMs, long retentionMs) {
    this.baseDir = Objects.requireNonNull(baseDir, "baseDir must not be null");
    if (binWidthMs <= 0) throw new IllegalArgumentException("binWidthMs must be positive: " + binWidthMs);
    if (retentionMs <= 0) throw new IllegalArgumentException("retentionMs must be positive: " + retentionMs);
    this.binWidthMs = binWidthMs;
    this.retentionMs = retentionMs;
    if (!baseDir.exists()) {
      baseDir.mkdirs();
    }
  }

  public File getBaseDir() {
    return baseDir;
  }

  public long getBinWidthMs() {
    return binWidthMs;
  }

  public long getRetentionMs() {
    return retentionMs;
  }

  public void setRetentionMs(long retentionMs) {
    if (retentionMs > 0) {
      this.retentionMs = retentionMs;
    }
  }

  public long getBinId(long timestamp) {
    return timestamp / binWidthMs;
  }

  /**
   * Appends a teleport data record to the appropriate disk file bin.
   *
   * @param data the teleport data to persist
   */
  public void save(TeleportData data) {
    if (data == null || data.sender == null) return;
    long binId = getBinId(data.time);
    File binFile = new File(baseDir, "bin_" + binId + ".dat");

    synchronized (lock) {
      try {
        if (!baseDir.exists()) baseDir.mkdirs();
        boolean isNew = !binFile.exists() || binFile.length() == 0;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(binFile, true)))) {
          if (isNew) {
            out.writeInt(MAGIC);
          }
          UUID uuid = data.sender.uuid();
          out.writeLong(uuid.getMostSignificantBits());
          out.writeLong(uuid.getLeastSignificantBits());
          String name = (data.sender.name() != null) ? data.sender.name() : uuid.toString();
          out.writeUTF(name);
          out.writeLong(data.time);
          out.writeLong(data.sender.cooldown());
          out.writeLong(data.sender.delay());

          String regionName = (data.targetRegionName != null)
              ? data.targetRegionName
              : (data.targetRegion != null ? data.targetRegion.name : "");
          out.writeUTF(regionName != null ? regionName : "");
          out.writeDouble(data.cost);
          out.writeLong(data.attempts);
          out.writeBoolean(data.completed);

          // Original coords
          if (data.originalCoords != null) {
            out.writeBoolean(true);
            out.writeUTF(data.originalCoords.worldName() != null ? data.originalCoords.worldName() : "");
            out.writeInt(data.originalCoords.x());
            out.writeInt(data.originalCoords.y());
            out.writeInt(data.originalCoords.z());
          } else {
            out.writeBoolean(false);
          }

          // Selected coords
          if (data.selectedCoords != null) {
            out.writeBoolean(true);
            out.writeUTF(data.selectedCoords.worldName() != null ? data.selectedCoords.worldName() : "");
            out.writeInt(data.selectedCoords.x());
            out.writeInt(data.selectedCoords.y());
            out.writeInt(data.selectedCoords.z());
          } else {
            out.writeBoolean(false);
          }
          out.flush();
        }
      } catch (IOException e) {
        RTP.log(Level.WARNING, "[RTP] Failed to write offline player bin file: " + binFile.getName(), e);
      }
    }
  }

  /**
   * Loads the most recent unexpired teleport record for the given player UUID from disk file bins.
   *
   * @param uuid the player's unique identifier
   * @return the loaded {@link TeleportData}, or {@code null} if none found
   */
  public @Nullable TeleportData load(UUID uuid) {
    if (uuid == null) return null;
    long now = System.currentTimeMillis();
    long currentBin = getBinId(now);
    long minBin = (now - retentionMs) / binWidthMs;

    synchronized (lock) {
      for (long binId = currentBin; binId >= minBin; binId--) {
        File binFile = new File(baseDir, "bin_" + binId + ".dat");
        if (!binFile.exists() || binFile.length() < 4) continue;

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(binFile)))) {
          int magic = in.readInt();
          if (magic != MAGIC) continue;

          TeleportData found = null;
          while (in.available() > 0) {
            long most = in.readLong();
            long least = in.readLong();
            UUID recordUuid = new UUID(most, least);
            String name = in.readUTF();
            long time = in.readLong();
            long cooldown = in.readLong();
            long delay = in.readLong();
            String regionName = in.readUTF();
            double cost = in.readDouble();
            long attempts = in.readLong();
            boolean completed = in.readBoolean();

            RTPCoords orig = null;
            if (in.readBoolean()) {
              String w = in.readUTF();
              int x = in.readInt();
              int y = in.readInt();
              int z = in.readInt();
              orig = new RTPCoords(w, x, y, z);
            }

            RTPCoords sel = null;
            if (in.readBoolean()) {
              String w = in.readUTF();
              int x = in.readInt();
              int y = in.readInt();
              int z = in.readInt();
              sel = new RTPCoords(w, x, y, z);
            }

            if (recordUuid.equals(uuid)) {
              TeleportData data = new TeleportData();
              data.sender = new PureDataRTPCommandSender(uuid, name, cooldown, delay, Collections.emptySet());
              data.time = time;
              data.targetRegionName = regionName.isEmpty() ? null : regionName;
              data.cost = cost;
              data.attempts = attempts;
              data.completed = completed;
              data.originalCoords = orig;
              data.selectedCoords = sel;
              found = data;
            }
          }
          if (found != null) {
            return found;
          }
        } catch (EOFException ignored) {
          // Normal end of file
        } catch (IOException e) {
          RTP.log(Level.WARNING, "[RTP] Failed to read bin file: " + binFile.getName(), e);
        }
      }
    }
    return null;
  }

  /**
   * Purges all bin files older than the retention threshold from disk.
   *
   * @param now the current epoch timestamp in milliseconds
   * @return the number of deleted bin files
   */
  public int purgeExpired(long now) {
    long cutoffBin = (now - retentionMs) / binWidthMs;
    File[] files = baseDir.listFiles();
    if (files == null || files.length == 0) return 0;

    int deleted = 0;
    synchronized (lock) {
      for (File file : files) {
        Matcher matcher = BIN_FILE_PATTERN.matcher(file.getName());
        if (!matcher.matches()) continue;
        try {
          long binId = Long.parseLong(matcher.group(1));
          if (binId < cutoffBin) {
            if (Files.deleteIfExists(file.toPath())) {
              deleted++;
            }
          }
        } catch (Exception e) {
          RTP.log(Level.FINE, "[RTP] Failed to evaluate or purge bin file: " + file.getName(), e);
        }
      }
    }
    return deleted;
  }
}
