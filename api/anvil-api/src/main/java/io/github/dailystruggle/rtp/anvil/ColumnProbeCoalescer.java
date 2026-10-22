package io.github.dailystruggle.rtp.anvil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Group-commit coalescing of center-column probes per region file. The first request for a file
 * opens a group and submits one drain task; requests for the same file arriving before that task
 * starts join the group. The drain resolves the file once, takes the resident byte-cache lease once,
 * and serves the group through one {@link AnvilSectorReader.Session} (one channel open).
 *
 * <p>No timer: an idle pool drains a single request at today's latency; a busy pool grows groups.
 * A group closes at {@link #MAX_GROUP} requests so one file cannot pin a pool thread. Futures complete
 * after the session is closed, so dependent callbacks never run while a handle is open. A failed chunk
 * completes only its own future exceptionally (S-004). Blocking I/O stays on the executor (S-005).</p>
 *
 * <p>Square jobs (ADR-120) join the same groups. A job runs inside the drain, after the group's
 * probes, while the lease or session is held, and reads its {@link ChunkSquare}: each same-file
 * chunk it asks for is decoded on first access and shared by the drain's jobs over the same Y
 * window. Chunks a job never asks for are never decoded. A job must be pure, bounded and
 * non-blocking; its result completes its future after the session is closed.</p>
 */
final class ColumnProbeCoalescer {

  static final int MAX_GROUP = 64;

  private static final Logger LOG = Logger.getLogger(ColumnProbeCoalescer.class.getName());

  private record Key(Path worldFolder, String dim, int regionX, int regionZ) {}

  /** {@code job == null}: a column probe; otherwise a square job whose result completes the future. */
  private record Request(int cx, int cz, int minY, int maxY, Function<ChunkSquare, ?> job,
                         CompletableFuture<Object> future) {}

  /** A job's result (may be null). */
  private record Done(Object value) {}

  /** Region-local chunk and Y window of a square decode. */
  private record ChunkKey(int lcx, int lcz, int minY, int maxY) {}

  /** Absent / undecodable chunk marker, so a drain does not retry it. */
  private static final ChunkColumnProbe ABSENT = new AnvilColumnProbeAdapter(
      new ColumnProbe(0, 0, Integer.MIN_VALUE, List.of(), List.of()), 0, 0);

  private static final class Group {
    final Key key;
    final List<Request> requests = new ArrayList<>();
    boolean closed;

    Group(Key key) {
      this.key = key;
    }

    /** Closes the group and snapshots its requests under the same monitor {@code submit} joins on. */
    synchronized List<Request> closeAndSnapshot() {
      closed = true;
      return new ArrayList<>(requests);
    }
  }

  private final ConcurrentHashMap<Key, Group> open = new ConcurrentHashMap<>();
  private final Executor executor;
  private final AtomicLong groupsDrained = new AtomicLong();
  private final AtomicLong requestsServed = new AtomicLong();
  private final AtomicLong squareJobs = new AtomicLong();
  private final AtomicLong squareDecodes = new AtomicLong();

  ColumnProbeCoalescer(Executor executor) {
    this.executor = executor;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  CompletableFuture<ColumnProbe> submit(Path worldFolder, String dim, int cx, int cz, int minY, int maxY) {
    return (CompletableFuture) enqueue(worldFolder, dim, new Request(cx, cz, minY, maxY, null, new CompletableFuture<>()));
  }

  /**
   * Runs {@code job} in a drain of chunk (cx, cz)'s region file with a lazily decoded
   * {@link ChunkSquare} over world-Y {@code [minY, maxY]}. Completes with the job's result,
   * exceptionally with its failure, or {@code null} without running it when the file is not an
   * Anvil region file.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  <T> CompletableFuture<T> submitSquare(Path worldFolder, String dim, int cx, int cz, int minY, int maxY,
                                        Function<ChunkSquare, T> job) {
    if (job == null) throw new NullPointerException("job");
    return (CompletableFuture) enqueue(worldFolder, dim, new Request(cx, cz, minY, maxY, job, new CompletableFuture<>()));
  }

  private CompletableFuture<Object> enqueue(Path worldFolder, String dim, Request req) {
    int cx = req.cx(), cz = req.cz();
    Key key = new Key(worldFolder, dim, cx >> 5, cz >> 5);
    for (;;) {
      Group g = open.get(key);
      if (g != null) {
        synchronized (g) {
          if (!g.closed) {
            g.requests.add(req);
            if (g.requests.size() >= MAX_GROUP) {
              g.closed = true;
              open.remove(key, g);
            }
            return req.future();
          }
        }
        open.remove(key, g);
        continue;
      }
      Group fresh = new Group(key);
      fresh.requests.add(req);
      if (open.putIfAbsent(key, fresh) != null) continue;
      try {
        executor.execute(() -> drain(fresh));
      } catch (RejectedExecutionException e) {
        List<Request> stranded = close(fresh);
        for (Request r : stranded) r.future().completeExceptionally(e);
      }
      return req.future();
    }
  }

  private List<Request> close(Group g) {
    List<Request> batch = g.closeAndSnapshot();
    open.remove(g.key, g);
    return batch;
  }

  private void drain(Group g) {
    long drainStart = System.nanoTime();
    List<Request> batch = close(g);
    groupsDrained.incrementAndGet();
    requestsServed.addAndGet(batch.size());
    Object[] results = new Object[batch.size()];
    try {
      serve(g.key, batch, results);
    } catch (Throwable t) {
      for (int i = 0; i < results.length; i++) {
        if (results[i] == null) results[i] = t;
      }
    }
    long elapsedNanos = System.nanoTime() - drainStart;
    long perChunkNanos = batch.isEmpty() ? 0L : Math.max(1L, elapsedNanos / batch.size());
    for (int i = 0; i < results.length; i++) {
      CompletableFuture<Object> f = batch.get(i).future();
      Object r = results[i];
      if (r instanceof Throwable t) {
        f.completeExceptionally(t);
      } else if (r instanceof Done d) {
        f.complete(d.value());
      } else if (r instanceof ColumnProbe p) {
        f.complete(p.withDrain(perChunkNanos, batch.size()));
      } else {
        f.complete(null);
      }
    }
  }

  private static final Object NULL_PROBE = new Object();

  /** Fills {@code results[i]} with a probe, a job's {@link Done}, {@link #NULL_PROBE}, or the failure. */
  private void serve(Key key, List<Request> batch, Object[] results) {
    Request first = batch.get(0);
    RegionFileResolver.ResolvedRegion resolved =
        RegionFileResolver.resolveExisting(key.worldFolder(), key.dim(), first.cx(), first.cz());
    if (resolved == null || resolved.reader() != AnvilReader.INSTANCE) {
      java.util.Arrays.fill(results, NULL_PROBE);
      return;
    }
    Path regionFile = resolved.path();
    try (AnvilRegionByteCache.Lease lease = AnvilPrefilter.residentLease(regionFile)) {
      AnvilSectorReader.Session session = (lease == null) ? AnvilSectorReader.openSession(regionFile) : null;
      try {
        for (int i = 0; i < results.length; i++) {
          Request r = batch.get(i);
          if (r.job() != null) continue;
          try {
            ColumnProbe p = (lease != null)
                ? AnvilReader.readColumnProbe(lease.buffer(), lease.length(),
                    Math.floorMod(r.cx(), 32), Math.floorMod(r.cz(), 32), r.minY(), r.maxY())
                : session.readColumnProbe(r.cx(), r.cz(), r.minY(), r.maxY());
            results[i] = (p == null) ? NULL_PROBE : p;
          } catch (Throwable t) {
            results[i] = t;
          }
        }
        runJobs(batch, results, lease, session);
      } finally {
        if (session != null) {
          try {
            session.close();
          } catch (IOException e) {
            LOG.log(Level.FINE, e, () -> "[RTP] column probe session close failed: " + regionFile);
          }
        }
      }
    }
  }

  /** Runs the batch's square jobs with one decode map shared across them. */
  private void runJobs(List<Request> batch, Object[] results, AnvilRegionByteCache.Lease lease,
                       AnvilSectorReader.Session session) {
    Map<ChunkKey, ChunkColumnProbe> decoded = null;
    for (int i = 0; i < results.length; i++) {
      Request r = batch.get(i);
      if (r.job() == null) continue;
      if (decoded == null) decoded = new HashMap<>();
      squareJobs.incrementAndGet();
      DrainSquare square = new DrainSquare(r, lease, session, decoded);
      try {
        results[i] = new Done(r.job().apply(square));
      } catch (Throwable t) {
        results[i] = t;
      } finally {
        square.closed = true;
      }
    }
  }

  /** One job's view; region-local offsets outside [0, 31] are another file and never read. */
  private final class DrainSquare implements ChunkSquare {
    final Request r;
    final AnvilRegionByteCache.Lease lease;
    final AnvilSectorReader.Session session;
    final Map<ChunkKey, ChunkColumnProbe> decoded;
    boolean closed;

    DrainSquare(Request r, AnvilRegionByteCache.Lease lease, AnvilSectorReader.Session session,
                Map<ChunkKey, ChunkColumnProbe> decoded) {
      this.r = r;
      this.lease = lease;
      this.session = session;
      this.decoded = decoded;
    }

    @Override
    public ChunkColumnProbe chunk(int dx, int dz) {
      if (closed || Math.abs(dx) > MAX_RADIUS || Math.abs(dz) > MAX_RADIUS) return null;
      int lcx = Math.floorMod(r.cx(), 32) + dx, lcz = Math.floorMod(r.cz(), 32) + dz;
      if (lcx < 0 || lcz < 0 || lcx > 31 || lcz > 31) return null;
      ChunkKey k = new ChunkKey(lcx, lcz, r.minY(), r.maxY());
      ChunkColumnProbe hit = decoded.get(k);
      if (hit == null) {
        hit = decode(lcx, lcz, r.cx() + dx, r.cz() + dz);
        decoded.put(k, hit);
      }
      return (hit == ABSENT) ? null : hit;
    }

    private ChunkColumnProbe decode(int lcx, int lcz, int cx, int cz) {
      squareDecodes.incrementAndGet();
      try {
        // Lean decode (ADR-120): window sections only, no biomes or heightmaps.
        ColumnProbe p = (lease != null)
            ? AnvilReader.readTerrainProbe(lease.buffer(), lease.length(), lcx, lcz, r.minY(), r.maxY())
            : session.readTerrainProbe(cx, cz, r.minY(), r.maxY());
        return (p == null) ? ABSENT : new AnvilColumnProbeAdapter(p, cx, cz);
      } catch (Throwable t) {
        LOG.log(Level.FINE, t, () -> "[RTP] terrain square decode failed: chunk (" + cx + "," + cz + ")");
        return ABSENT;
      }
    }
  }

  /** Square jobs run so far. */
  long squareJobs() {
    return squareJobs.get();
  }

  /** Chunks decoded for square jobs (first access per drain and Y window). */
  long squareDecodes() {
    return squareDecodes.get();
  }

  /** Drain tasks run so far (each one region-file group). */
  long groupsDrained() {
    return groupsDrained.get();
  }

  /** Requests served by drain tasks so far. */
  long requestsServed() {
    return requestsServed.get();
  }
}
