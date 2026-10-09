package io.github.dailystruggle.rtp.common.selection.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.anvil.ChunkCoord;
import io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry.Provider;
import io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry.Provider.Decision;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** REQ-RTP-S-005 / S-004: batched backlog bin verification off the pulse thread (ADR-016, ADR-028). */
class BacklogBinVerifierTest {

  private static final String WORLD = "bin_verifier_world";

  /** Runs submitted tasks only when the test says so, standing in for AnvilIoPool. */
  private static final class ManualExecutor implements Executor {
    final ArrayDeque<Runnable> queue = new ArrayDeque<>();

    @Override
    public void execute(Runnable r) {
      queue.addLast(r);
    }

    void runAll() {
      while (!queue.isEmpty()) queue.pollFirst().run();
    }
  }

  /** Records one call per batch; rejects chunks with even X. */
  private static final class RecordingProvider implements Provider {
    final List<List<ChunkCoord>> batches = new ArrayList<>();
    final List<String> threads = new ArrayList<>();

    @Override
    public Decision classify(RTPWorld world, int cx, int cz) {
      throw new AssertionError("single-chunk classify must not be used by the bin verifier");
    }

    @Override
    public synchronized Map<ChunkCoord, Decision> classifyBatch(RTPWorld world, List<ChunkCoord> chunks) {
      batches.add(List.copyOf(chunks));
      threads.add(Thread.currentThread().getName());
      Map<ChunkCoord, Decision> out = new LinkedHashMap<>();
      for (ChunkCoord c : chunks) out.put(c, (c.x() & 1) == 0 ? Decision.REJECT : Decision.ACCEPT);
      return out;
    }
  }

  private String savedDepth;
  private BacklogLocationBuffer backlog;
  private WorldBacklogBinIndex index;

  @BeforeEach
  void setUp() {
    savedDepth = System.getProperty(BacklogBinVerifier.DEPTH_PROPERTY);
    backlog = new BacklogLocationBuffer(64);
    index = new WorldBacklogBinIndex();
  }

  @AfterEach
  void restore() {
    if (savedDepth == null) System.clearProperty(BacklogBinVerifier.DEPTH_PROPERTY);
    else System.setProperty(BacklogBinVerifier.DEPTH_PROPERTY, savedDepth);
  }

  /** Stages {@code n} chunks of region file (rx, rz); chunk X runs rx*32 .. rx*32+n-1. */
  private List<BacklogLocationBuffer.BacklogEntry> stage(int rx, int rz, int n) {
    List<BacklogLocationBuffer.BacklogEntry> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      int cx = (rx << 5) + i;
      int cz = rz << 5;
      RTPCoords coords = new RTPCoords(WORLD, (cx << 4) + 7, 64, (cz << 4) + 7);
      BacklogLocationBuffer.BacklogEntry e = backlog.offerUnverified(new RTPLocation(coords, 0L));
      index.insert(RegionFileCoord.of(coords), e);
      out.add(e);
    }
    return out;
  }

  private static long count(List<BacklogLocationBuffer.BacklogEntry> entries, BacklogLocationBuffer.Validity v) {
    return entries.stream().filter(e -> e.validity() == v).count();
  }

  @Test
  @DisplayName("REQ-RTP-S-005: depth 2 submits two region files without blocking; results land on a later pulse")
  void depthTwo_prefetchesNextFile_andAppliesOnLaterPulse() {
    System.setProperty(BacklogBinVerifier.DEPTH_PROPERTY, "2");
    List<BacklogLocationBuffer.BacklogEntry> a = stage(0, 0, 4);
    List<BacklogLocationBuffer.BacklogEntry> b = stage(1, 0, 3);
    List<BacklogLocationBuffer.BacklogEntry> c = stage(2, 0, 2);
    ManualExecutor io = new ManualExecutor();
    RecordingProvider provider = new RecordingProvider();
    BacklogBinVerifier v = new BacklogBinVerifier(io);
    List<BacklogLocationBuffer.BacklogEntry> rejected = new ArrayList<>();

    v.pulse(null, backlog, index, provider, rejected::add);
    assertEquals(2, v.inFlight(), "oldest bin plus the next file are in flight");
    assertEquals(2, io.queue.size());
    assertEquals(4, count(a, BacklogLocationBuffer.Validity.UNVERIFIED), "pulse never waits for I/O");

    v.pulse(null, backlog, index, provider, rejected::add);
    assertEquals(2, io.queue.size(), "no third file while both slots are busy");

    io.runAll();
    assertEquals(2, provider.batches.size(), "one classifyBatch call per region file");
    assertEquals(4, provider.batches.get(0).size(), "whole bin in one call");
    assertEquals(3, provider.batches.get(1).size());
    assertEquals(4, count(a, BacklogLocationBuffer.Validity.UNVERIFIED), "results are applied only by a pulse");

    v.pulse(null, backlog, index, provider, rejected::add);
    assertEquals(2, count(a, BacklogLocationBuffer.Validity.INVALIDATED));
    assertEquals(2, count(a, BacklogLocationBuffer.Validity.VALIDATED));
    assertEquals(0, count(b, BacklogLocationBuffer.Validity.UNVERIFIED));
    assertEquals(2, count(b, BacklogLocationBuffer.Validity.INVALIDATED));
    assertEquals(4, rejected.size(), "onReject fires once per invalidated entry");
    assertEquals(1, v.inFlight(), "freed slot takes the next file");
    io.runAll();
    v.pulse(null, backlog, index, provider, rejected::add);
    assertEquals(0, count(c, BacklogLocationBuffer.Validity.UNVERIFIED));
    assertEquals(0, v.inFlight());
  }

  @Test
  @DisplayName("Bins are claimed world-wide: a second region never re-reads a file in flight")
  void claimedBin_isSkippedByOtherRegion() {
    System.setProperty(BacklogBinVerifier.DEPTH_PROPERTY, "1");
    List<BacklogLocationBuffer.BacklogEntry> a = stage(0, 0, 2);
    ManualExecutor io = new ManualExecutor();
    RecordingProvider provider = new RecordingProvider();
    BacklogBinVerifier first = new BacklogBinVerifier(io);
    BacklogBinVerifier second = new BacklogBinVerifier(io);

    first.pulse(null, backlog, index, provider, e -> { });
    second.pulse(null, backlog, index, provider, e -> { });
    assertEquals(1, io.queue.size(), "the shared bin is read once");
    assertEquals(0, second.inFlight());

    first.close();
    assertFalse(index.isClaimed(RegionFileCoord.of(a.get(0).location().coords()), System.nanoTime(),
        BacklogBinVerifier.TIMEOUT_NANOS), "close releases the claim");
    assertEquals(2, count(a, BacklogLocationBuffer.Validity.UNVERIFIED), "cancelled bins stay re-pickable");
    second.pulse(null, backlog, index, provider, e -> { });
    assertEquals(1, second.inFlight());
  }

  @Test
  @DisplayName("S-004: a failing or empty batch result fails closed to live load (VALIDATED), never INVALIDATED")
  void failingBatch_validatesForLiveLoad() {
    System.setProperty(BacklogBinVerifier.DEPTH_PROPERTY, "2");
    List<BacklogLocationBuffer.BacklogEntry> a = stage(0, 0, 3);
    ManualExecutor io = new ManualExecutor();
    Provider throwing = new Provider() {
      @Override
      public Decision classify(RTPWorld world, int cx, int cz) {
        return Decision.REJECT;
      }

      @Override
      public Map<ChunkCoord, Decision> classifyBatch(RTPWorld world, List<ChunkCoord> chunks) {
        throw new IllegalStateException("disk gone");
      }
    };
    BacklogBinVerifier v = new BacklogBinVerifier(io);
    v.pulse(null, backlog, index, throwing, e -> { });
    io.runAll();
    v.pulse(null, backlog, index, throwing, e -> { });
    assertEquals(3, count(a, BacklogLocationBuffer.Validity.VALIDATED));
  }

  @Test
  @DisplayName("Depth 0 classifies inline on the pulse thread, one bin per pulse")
  void depthZero_classifiesInline() {
    System.setProperty(BacklogBinVerifier.DEPTH_PROPERTY, "0");
    List<BacklogLocationBuffer.BacklogEntry> a = stage(0, 0, 2);
    List<BacklogLocationBuffer.BacklogEntry> b = stage(1, 0, 2);
    ManualExecutor io = new ManualExecutor();
    RecordingProvider provider = new RecordingProvider();
    BacklogBinVerifier v = new BacklogBinVerifier(io);

    v.pulse(null, backlog, index, provider, e -> { });
    assertTrue(io.queue.isEmpty(), "no pool hand-off at depth 0");
    assertEquals(Thread.currentThread().getName(), provider.threads.get(0));
    assertEquals(0, count(a, BacklogLocationBuffer.Validity.UNVERIFIED));
    assertEquals(2, count(b, BacklogLocationBuffer.Validity.UNVERIFIED), "one bin per pulse");
  }

  @Test
  @DisplayName("Shared-bin rejections route to the owning region's sink, not the verifying region's")
  void sharedBinReject_routesToOwner() {
    System.setProperty(BacklogBinVerifier.DEPTH_PROPERTY, "0");
    List<BacklogLocationBuffer.BacklogEntry> mine = stage(0, 0, 2);
    List<BacklogLocationBuffer.BacklogEntry> theirs = new ArrayList<>();
    List<BacklogLocationBuffer.BacklogEntry> ownerSeen = new ArrayList<>();
    BacklogLocationBuffer otherBacklog = new BacklogLocationBuffer(8);
    for (int cx = 2; cx < 4; cx++) {
      RTPCoords coords = new RTPCoords(WORLD, (cx << 4) + 7, 64, 7);
      BacklogLocationBuffer.BacklogEntry e = otherBacklog.offerUnverified(new RTPLocation(coords, 0L));
      e.setOwnerOnReject(ownerSeen::add);
      index.insert(RegionFileCoord.of(coords), e);
      theirs.add(e);
    }
    List<BacklogLocationBuffer.BacklogEntry> verifierSeen = new ArrayList<>();

    new BacklogBinVerifier(new ManualExecutor())
        .pulse(null, backlog, index, new RecordingProvider(), verifierSeen::add);

    assertEquals(List.of(theirs.get(0)), ownerSeen, "other region's reject goes to its owner");
    assertEquals(List.of(mine.get(0)), verifierSeen, "unowned entries fall back to the pulse sink");
    assertEquals(BacklogLocationBuffer.Validity.INVALIDATED, theirs.get(0).validity());
  }

  @Test
  @DisplayName("Without a provider the oldest bin validates without disk reads")
  void noProvider_validatesOldestBin() {
    List<BacklogLocationBuffer.BacklogEntry> a = stage(0, 0, 2);
    List<BacklogLocationBuffer.BacklogEntry> b = stage(1, 0, 2);
    new BacklogBinVerifier(new ManualExecutor()).pulse(null, backlog, index, null, e -> { });
    assertEquals(2, count(a, BacklogLocationBuffer.Validity.VALIDATED));
    assertEquals(2, count(b, BacklogLocationBuffer.Validity.UNVERIFIED));
  }
}
