package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table;

import org.junit.jupiter.api.Test;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class LazyMcaProbingBenchmarkTest {

  @Test
  public void testLazyProbingVersusFullParsing() throws Exception {
    Path regionDir = Path.of("C:\\GameServers\\Minecraft\\testServer\\RTP-Folia\\26.1\\world\\dimensions\\minecraft\\overworld\\region");
    List<Path> mcaFiles = new ArrayList<>();

    try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionDir, "r.*.*.mca")) {
      for (Path p : ds) {
        mcaFiles.add(p);
        if (mcaFiles.size() >= 256) break;
      }
    }

    System.out.println("\n[DEBUG_LOG] === LAZY ON-DEMAND MCA CHUNK PROBING BENCHMARK ===");
    System.out.printf("[DEBUG_LOG] Region Files to Test: %d files%n", mcaFiles.size());

    // Target quota: harvest 3 safe candidates per region file
    int targetQuota = 3;
    int maxAttempts = 16; // Try up to 16 dyadic candidate probes before giving up on file

    // Dyadic bisection probe sequence across 1024 chunks
    int stride = 64; // Stride 64 -> 16 major probe points
    int[] probeOffsets = new int[maxAttempts];
    for (int i = 0; i < maxAttempts; i++) {
      int rev = Integer.reverse(i) >>> (32 - 4); // 4 bits = 16 positions
      probeOffsets[i] = rev * stride;
    }

    // Benchmark Lazy Probing
    int totalProbes = 0;
    int totalSafeHarvested = 0;
    int totalFilesMeetingQuota = 0;
    int totalExhaustedFiles = 0;

    byte[] chunkBuf = new byte[128 * 1024];
    byte[] inflateBuf = new byte[256 * 1024];
    Inflater inflater = new Inflater();

    long startLazy = System.nanoTime();

    for (Path file : mcaFiles) {
      int fileHarvested = 0;

      try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
           FileChannel ch = raf.getChannel()) {

        // Read 4KB header table into memory once
        ByteBuffer header = ByteBuffer.allocate(4096);
        ch.read(header, 0);
        header.flip();

        for (int a = 0; a < maxAttempts; a++) {
          int chunkIdx = probeOffsets[a];
          totalProbes++;

          int offsetEntry = header.getInt(chunkIdx * 4);
          int sectorOffset = (offsetEntry >>> 8) & 0xFFFFFF;
          int sectorCount = offsetEntry & 0xFF;

          if (sectorOffset == 0 || sectorCount == 0) {
            // Ungenerated void chunk -> bad
            continue;
          }

          // Read isolated compressed chunk stream (< 15 microseconds)
          long byteOffset = (long) sectorOffset * 4096L;
          ByteBuffer chunkMeta = ByteBuffer.allocate(5);
          ch.read(chunkMeta, byteOffset);
          chunkMeta.flip();

          int streamLength = chunkMeta.getInt();
          byte compType = chunkMeta.get();

          if (streamLength <= 0 || streamLength > chunkBuf.length) continue;

          ByteBuffer dataBuf = ByteBuffer.wrap(chunkBuf, 0, streamLength - 1);
          ch.read(dataBuf, byteOffset + 5);

          // Fast decompression of ONLY this single chunk
          inflater.reset();
          inflater.setInput(chunkBuf, 0, streamLength - 1);
          int inflatedBytes = inflater.inflate(inflateBuf);

          // Check block safety from NBT payload (quick payload scan)
          boolean isSafe = evaluateChunkPayload(inflateBuf, inflatedBytes);

          if (isSafe) {
            fileHarvested++;
            totalSafeHarvested++;
            if (fileHarvested >= targetQuota) {
              // EARLY EXIT! Quota met!
              break;
            }
          }
        }

        if (fileHarvested >= targetQuota) {
          totalFilesMeetingQuota++;
        } else {
          totalExhaustedFiles++;
        }
      }
    }

    long totalLazyNs = System.nanoTime() - startLazy;
    double totalLazyMs = totalLazyNs / 1_000_000.0;
    double msPerFile = totalLazyMs / mcaFiles.size();
    double usPerProbe = (totalLazyNs / 1000.0) / totalProbes;

    System.out.printf("[DEBUG_LOG] Total Duration: %.2f ms (%.2f ms / region file)%n", totalLazyMs, msPerFile);
    System.out.printf("[DEBUG_LOG] Probes Executed: %,d probes (Average %.1f probes / file)%n",
        totalProbes, (double) totalProbes / mcaFiles.size());
    System.out.printf("[DEBUG_LOG] Probe Latency: %.2f microseconds per single-chunk probe%n", usPerProbe);
    System.out.printf("[DEBUG_LOG] Safe Candidates Harvested: %,d safe landing zones%n", totalSafeHarvested);
    System.out.printf("[DEBUG_LOG] Quota Met Rate: %d / %d files (%.1f%% of region files hit quota of %d)%n",
        totalFilesMeetingQuota, mcaFiles.size(), (totalFilesMeetingQuota * 100.0) / mcaFiles.size(), targetQuota);
    System.out.println("[DEBUG_LOG] ==========================================================================");

    assertTrue(totalSafeHarvested > 0);
  }

  /**
   * Fast byte payload scan for top solid blocks / ocean indicator.
   */
  private static boolean evaluateChunkPayload(byte[] buf, int len) {
    // Quick search for water/ocean biome / palette indicator in NBT bytes
    // In Minecraft NBT, block names like "minecraft:water", "minecraft:lava" appear in byte stream
    String payload = new String(buf, 0, Math.min(len, 4096));
    if (payload.contains("water") || payload.contains("lava") || payload.contains("ocean")) {
      // If water is dominant, treat as hazard
      if (!payload.contains("grass_block") && !payload.contains("stone") && !payload.contains("dirt")) {
        return false;
      }
    }
    return true; // solid land found
  }
}
