package io.github.dailystruggle.rtp.anvil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
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
 */
final class ColumnProbeCoalescer {

  static final int MAX_GROUP = 64;

  private static final Logger LOG = Logger.getLogger(ColumnProbeCoalescer.class.getName());

  private record Key(Path worldFolder, String dim, int regionX, int regionZ) {}

  private record Request(int cx, int cz, int minY, int maxY, CompletableFuture<ColumnProbe> future) {}

  private static final class Group {
    final Key key;
    final List<Request> requests = new ArrayList<>();
    boolean closed;

    Group(Key key) {
      this.key = key;
    }
  }

  private final ConcurrentHashMap<Key, Group> open = new ConcurrentHashMap<>();
  private final Executor executor;
  private final AtomicLong groupsDrained = new AtomicLong();
  private final AtomicLong requestsServed = new AtomicLong();

  ColumnProbeCoalescer(Executor executor) {
    this.executor = executor;
  }

  CompletableFuture<ColumnProbe> submit(Path worldFolder, String dim, int cx, int cz, int minY, int maxY) {
    Request req = new Request(cx, cz, minY, maxY, new CompletableFuture<>());
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
    List<Request> batch;
    synchronized (g) {
      g.closed = true;
      batch = new ArrayList<>(g.requests);
    }
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
      CompletableFuture<ColumnProbe> f = batch.get(i).future();
      Object r = results[i];
      if (r instanceof Throwable t) {
        f.completeExceptionally(t);
      } else if (r instanceof ColumnProbe p) {
        f.complete(p.withDrain(perChunkNanos, batch.size()));
      } else {
        f.complete(null);
      }
    }
  }

  private static final Object NULL_PROBE = new Object();

  /** Fills {@code results[i]} with a probe, {@link #NULL_PROBE}, or the request's failure. */
  private static void serve(Key key, List<Request> batch, Object[] results) {
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
      } finally {
        if (session != null) {
          try {
            session.close();
          } catch (IOException e) {
            LOG.log(Level.FINE, "[RTP] column probe session close failed: " + regionFile, e);
          }
        }
      }
    }
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
