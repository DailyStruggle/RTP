package io.github.dailystruggle.anvil.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Multi-threaded contention proof for {@link LcaBoundedSlabPool} verifying that L2 disk reloads
 * and spills parked on slow filesystem paths never block concurrent L1 RAM hits or disjoint L2
 * region stripes.
 *
 * @governedBy leafanvil-ADR-005
 * @trace REQ-MAP-LRU-001
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
class LcaBoundedSlabPoolConcurrencyTest {

    @TempDir
    Path tempDir;

    private static LcaRemoteStreamCodec.EntryFullChunk makeFullChunk(int cx, int cz, int seedByte) {
        List<LcaRemoteStreamCodec.SectionWireSlice> sections = new ArrayList<>(24);
        byte[] payload = new byte[512];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((seedByte + i * 13) & 0xFF);
        }
        sections.add(LcaRemoteStreamCodec.SectionWireSlice.nonUniform(0, payload, new int[0]));
        for (int s = 1; s < 24; s++) {
            sections.add(LcaRemoteStreamCodec.SectionWireSlice.uniform(s, 15, 0, 0));
        }
        return LcaRemoteStreamCodec.EntryFullChunk.createWithComputedSyndrome(
                cx, cz, 100L + seedByte, sections, new byte[] {1, 2, 3, 4});
    }

    private static void ingestSingleChunk(
            LcaBoundedSlabPool pool, String srv, String world, int cx, int cz, int seedByte, boolean pin) {
        LcaRemoteStreamCodec.EntryFullChunk entry = makeFullChunk(cx, cz, seedByte);
        LcaRemoteStreamCodec.StreamChunkBatchPayload batch =
                new LcaRemoteStreamCodec.StreamChunkBatchPayload(world, cx >> 5, cz >> 5, List.of(entry));
        pool.ingestStreamBatch(srv, batch, pin);
    }

    @Test
    void concurrentL1HitsAndDisjointStripeReadsCompleteWhileL2ReloadParked() throws Exception {
        LatchedFsHarness fsHarness = new LatchedFsHarness(tempDir, "r.10.10");
        Path latchedRoot = fsHarness.wrap(tempDir.resolve("l2-cache"));

        LcaRemoteStreamCodec.EntryFullChunk sample = makeFullChunk(0, 0, 1);
        int oneSlabBytes = sample.toCanonicalSlabBytes().length;
        // Budget holds ~2 chunks in L1 so unpinned chunks spill cleanly to L2 disk.
        long l1Budget = oneSlabBytes * 2L + 256L;
        long l2Budget = 16L * 1024L * 1024L;

        LcaBoundedSlabPool.PoolConfig cfg = new LcaBoundedSlabPool.PoolConfig(
                l1Budget, l2Budget, latchedRoot, false, false);

        ExecutorService exec = Executors.newFixedThreadPool(3);
        try (LcaBoundedSlabPool pool = new LcaBoundedSlabPool(cfg)) {
            // Pin (0, 0) in L1 so it stays resident in RAM.
            ingestSingleChunk(pool, "srv", "world", 0, 0, 11, true);
            // Ingest region (10, 10) chunk (320, 320), region (5, 5) chunk (160, 160), and region (2, 2) chunk (64, 64)
            // so (320, 320) and (160, 160) spill to L2 disk while (0, 0) + (64, 64) stay in L1.
            ingestSingleChunk(pool, "srv", "world", 320, 320, 22, false);
            ingestSingleChunk(pool, "srv", "world", 160, 160, 33, false);
            ingestSingleChunk(pool, "srv", "world", 64, 64, 44, false);

            assertTrue(pool.snapshotTelemetry().l2ChunkCount() >= 2, "Expected spilled chunks in L2");

            // Arm the filesystem latch on region r.10.10 only.
            CountDownLatch enteredIo = new CountDownLatch(1);
            CountDownLatch releaseIo = new CountDownLatch(1);
            fsHarness.armLatch(enteredIo, releaseIo);

            Future<Optional<LcaBoundedSlabPool.PooledChunkSlab>> coldReloadFuture =
                    exec.submit(() -> pool.getChunkSlabEntry("srv", "world", 320, 320));

            assertTrue(enteredIo.await(2, TimeUnit.SECONDS), "Cold L2 reload should enter disk I/O");

            // While r.10.10 is parked inside Files.readAllBytes, concurrent L1 hits on (0, 0)
            // AND disjoint L2 stripe reads on r.5.5 must complete without blocking!
            CompletableFuture<Void> concurrentWork = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 64; i++) {
                    Optional<LcaBoundedSlabPool.PooledChunkSlab> hit =
                            pool.getChunkSlabEntry("srv", "world", 0, 0);
                    assertTrue(hit.isPresent(), "Pinned L1 chunk (0,0) must remain immediately readable");
                }
                Optional<LcaBoundedSlabPool.PooledChunkSlab> disjointL2 =
                        pool.getChunkSlabEntry("srv", "world", 160, 160);
                assertTrue(disjointL2.isPresent(), "Disjoint L2 stripe (5,5) must reload without blocking on (10,10)");
            }, exec);

            concurrentWork.get(400, TimeUnit.MILLISECONDS);

            releaseIo.countDown();
            Optional<LcaBoundedSlabPool.PooledChunkSlab> reloaded = coldReloadFuture.get(2, TimeUnit.SECONDS);
            assertTrue(reloaded.isPresent(), "Parked L2 reload for (320,320) must succeed once released");
            assertEquals(320, reloaded.get().chunkX());
            assertEquals(320, reloaded.get().chunkZ());
            assertArrayEquals(makeFullChunk(320, 320, 22).toCanonicalSlabBytes(), reloaded.get().slabBytes());
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void concurrentL1HitsCompleteWhileVictimSpillParkedOnL2DiskWrite() throws Exception {
        LatchedFsHarness fsHarness = new LatchedFsHarness(tempDir, "r.10.10");
        Path latchedRoot = fsHarness.wrap(tempDir.resolve("l2-spill-cache"));

        int oneSlabBytes = makeFullChunk(0, 0, 1).toCanonicalSlabBytes().length;
        long l1Budget = oneSlabBytes * 2L + 256L;
        long l2Budget = 16L * 1024L * 1024L;

        LcaBoundedSlabPool.PoolConfig cfg = new LcaBoundedSlabPool.PoolConfig(
                l1Budget, l2Budget, latchedRoot, false, false);

        ExecutorService exec = Executors.newFixedThreadPool(2);
        try (LcaBoundedSlabPool pool = new LcaBoundedSlabPool(cfg)) {
            ingestSingleChunk(pool, "srv", "world", 0, 0, 11, true);
            ingestSingleChunk(pool, "srv", "world", 320, 320, 22, false);

            CountDownLatch enteredSpillIo = new CountDownLatch(1);
            CountDownLatch releaseSpillIo = new CountDownLatch(1);
            fsHarness.armLatch(enteredSpillIo, releaseSpillIo);

            // Ingesting (96, 96) exceeds L1 budget and evicts (320, 320) -> spills to r.10.10 on disk.
            Future<?> spillTriggerFuture = exec.submit(
                    () -> ingestSingleChunk(pool, "srv", "world", 96, 96, 55, false));

            assertTrue(enteredSpillIo.await(2, TimeUnit.SECONDS), "L2 eviction spill should enter disk write");

            CompletableFuture<Void> l1Reader = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 64; i++) {
                    Optional<LcaBoundedSlabPool.PooledChunkSlab> hit =
                            pool.getChunkSlabEntry("srv", "world", 0, 0);
                    assertTrue(hit.isPresent(), "Concurrent L1 hit must not block on L2 spill");
                }
            }, exec);

            l1Reader.get(400, TimeUnit.MILLISECONDS);

            releaseSpillIo.countDown();
            spillTriggerFuture.get(2, TimeUnit.SECONDS);

            fsHarness.disarm();
            Optional<LcaBoundedSlabPool.PooledChunkSlab> fromL2 =
                    pool.getChunkSlabEntry("srv", "world", 320, 320);
            assertNotNull(fromL2);
            assertTrue(fromL2.isPresent(), "Spilled victim (320,320) must be recoverable from L2");
        } finally {
            exec.shutdownNow();
        }
    }

    /**
     * Minimal delegating NIO {@link FileSystemProvider} / {@link Path} wrapper that parks I/O
     * operations matching a target path substring on deterministic {@link CountDownLatch}es.
     */
    private static final class LatchedFsHarness {
        private final FileSystem delegateFs;
        private final FileSystemProvider delegateProvider;
        private final String targetSubstring;
        private final LatchedProvider provider;
        private final LatchedFs fs;
        private final AtomicBoolean armed = new AtomicBoolean(false);
        private volatile CountDownLatch enteredLatch;
        private volatile CountDownLatch releaseLatch;

        LatchedFsHarness(Path baseDir, String targetSubstring) {
            this.delegateFs = baseDir.getFileSystem();
            this.delegateProvider = delegateFs.provider();
            this.targetSubstring = targetSubstring;
            this.provider = new LatchedProvider();
            this.fs = new LatchedFs();
        }

        Path wrap(Path real) {
            return new LatchedPath(real);
        }

        void armLatch(CountDownLatch entered, CountDownLatch release) {
            this.enteredLatch = entered;
            this.releaseLatch = release;
            this.armed.set(true);
        }

        void disarm() {
            this.armed.set(false);
            CountDownLatch rel = this.releaseLatch;
            if (rel != null) {
                rel.countDown();
            }
        }

        private void maybePause(Path p) {
            if (armed.get() && p.toString().contains(targetSubstring)) {
                CountDownLatch ent = this.enteredLatch;
                CountDownLatch rel = this.releaseLatch;
                if (ent != null) {
                    ent.countDown();
                }
                if (rel != null) {
                    try {
                        rel.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        private static Path unwrap(Path p) {
            return p instanceof LatchedPath lp ? lp.delegate : p;
        }

        private final class LatchedProvider extends FileSystemProvider {
            @Override
            public String getScheme() {
                return "latched";
            }

            @Override
            public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
                return fs;
            }

            @Override
            public FileSystem getFileSystem(URI uri) {
                return fs;
            }

            @Override
            @NotNull
            public Path getPath(@NotNull URI uri) {
                return wrap(delegateProvider.getPath(uri));
            }

            @Override
            public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
                Path real = unwrap(path);
                maybePause(real);
                return delegateProvider.newInputStream(real, options);
            }

            @Override
            public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
                Path real = unwrap(path);
                maybePause(real);
                return delegateProvider.newOutputStream(real, options);
            }

            @Override
            public FileChannel newFileChannel(
                    Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
                Path real = unwrap(path);
                maybePause(real);
                return delegateProvider.newFileChannel(real, options, attrs);
            }

            @Override
            public AsynchronousFileChannel newAsynchronousFileChannel(
                    Path path,
                    Set<? extends OpenOption> options,
                    ExecutorService executor,
                    FileAttribute<?>... attrs) throws IOException {
                return delegateProvider.newAsynchronousFileChannel(unwrap(path), options, executor, attrs);
            }

            @Override
            public SeekableByteChannel newByteChannel(
                    Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
                Path real = unwrap(path);
                maybePause(real);
                return delegateProvider.newByteChannel(real, options, attrs);
            }

            @Override
            public DirectoryStream<Path> newDirectoryStream(
                    Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
                DirectoryStream<Path> ds = delegateProvider.newDirectoryStream(unwrap(dir), entry -> filter.accept(wrap(entry)));
                return new DirectoryStream<>() {
                    @Override
                    @NotNull
                    public Iterator<Path> iterator() {
                        Iterator<Path> it = ds.iterator();
                        return new Iterator<>() {
                            @Override
                            public boolean hasNext() {
                                return it.hasNext();
                            }

                            @Override
                            public Path next() {
                                return wrap(it.next());
                            }
                        };
                    }

                    @Override
                    public void close() throws IOException {
                        ds.close();
                    }
                };
            }

            @Override
            public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
                delegateProvider.createDirectory(unwrap(dir), attrs);
            }

            @Override
            public void delete(Path path) throws IOException {
                delegateProvider.delete(unwrap(path));
            }

            @Override
            public boolean deleteIfExists(Path path) throws IOException {
                return delegateProvider.deleteIfExists(unwrap(path));
            }

            @Override
            public void copy(Path source, Path target, CopyOption... options) throws IOException {
                delegateProvider.copy(unwrap(source), unwrap(target), options);
            }

            @Override
            public void move(Path source, Path target, CopyOption... options) throws IOException {
                delegateProvider.move(unwrap(source), unwrap(target), options);
            }

            @Override
            public boolean isSameFile(Path path, Path path2) throws IOException {
                return delegateProvider.isSameFile(unwrap(path), unwrap(path2));
            }

            @Override
            public boolean isHidden(Path path) throws IOException {
                return delegateProvider.isHidden(unwrap(path));
            }

            @Override
            public FileStore getFileStore(Path path) throws IOException {
                return delegateProvider.getFileStore(unwrap(path));
            }

            @Override
            public void checkAccess(Path path, AccessMode... modes) throws IOException {
                delegateProvider.checkAccess(unwrap(path), modes);
            }

            @Override
            public <V extends FileAttributeView> V getFileAttributeView(
                    Path path, Class<V> type, LinkOption... options) {
                return delegateProvider.getFileAttributeView(unwrap(path), type, options);
            }

            @Override
            public <A extends BasicFileAttributes> A readAttributes(
                    Path path, Class<A> type, LinkOption... options) throws IOException {
                return delegateProvider.readAttributes(unwrap(path), type, options);
            }

            @Override
            public Map<String, Object> readAttributes(
                    Path path, String attributes, LinkOption... options) throws IOException {
                return delegateProvider.readAttributes(unwrap(path), attributes, options);
            }

            @Override
            public void setAttribute(
                    Path path, String attribute, Object value, LinkOption... options) throws IOException {
                delegateProvider.setAttribute(unwrap(path), attribute, value, options);
            }
        }

        private final class LatchedFs extends FileSystem {
            @Override
            public FileSystemProvider provider() {
                return provider;
            }

            @Override
            public void close() throws IOException {}

            @Override
            public boolean isOpen() {
                return delegateFs.isOpen();
            }

            @Override
            public boolean isReadOnly() {
                return delegateFs.isReadOnly();
            }

            @Override
            public String getSeparator() {
                return delegateFs.getSeparator();
            }

            @Override
            public Iterable<Path> getRootDirectories() {
                List<Path> out = new ArrayList<>();
                for (Path r : delegateFs.getRootDirectories()) {
                    out.add(wrap(r));
                }
                return out;
            }

            @Override
            public Iterable<FileStore> getFileStores() {
                return delegateFs.getFileStores();
            }

            @Override
            public Set<String> supportedFileAttributeViews() {
                return delegateFs.supportedFileAttributeViews();
            }

            @Override
            @NotNull
            public Path getPath(@NotNull String first, @NotNull String... more) {
                return wrap(delegateFs.getPath(first, more));
            }

            @Override
            public PathMatcher getPathMatcher(String syntaxAndPattern) {
                PathMatcher pm = delegateFs.getPathMatcher(syntaxAndPattern);
                return p -> pm.matches(unwrap(p));
            }

            @Override
            public UserPrincipalLookupService getUserPrincipalLookupService() {
                return delegateFs.getUserPrincipalLookupService();
            }

            @Override
            public WatchService newWatchService() throws IOException {
                return delegateFs.newWatchService();
            }
        }

        private final class LatchedPath implements Path {
            final Path delegate;

            LatchedPath(Path delegate) {
                this.delegate = delegate;
            }

            @Override
            @NotNull
            public FileSystem getFileSystem() {
                return fs;
            }

            @Override
            public boolean isAbsolute() {
                return delegate.isAbsolute();
            }

            @Override
            public Path getRoot() {
                Path r = delegate.getRoot();
                return r == null ? null : wrap(r);
            }

            @Override
            public Path getFileName() {
                Path f = delegate.getFileName();
                return f == null ? null : wrap(f);
            }

            @Override
            public Path getParent() {
                Path p = delegate.getParent();
                return p == null ? null : wrap(p);
            }

            @Override
            public int getNameCount() {
                return delegate.getNameCount();
            }

            @Override
            @NotNull
            public Path getName(int index) {
                return wrap(delegate.getName(index));
            }

            @Override
            @NotNull
            public Path subpath(int beginIndex, int endIndex) {
                return wrap(delegate.subpath(beginIndex, endIndex));
            }

            @Override
            public boolean startsWith(@NotNull Path other) {
                return delegate.startsWith(unwrap(other));
            }

            @Override
            public boolean endsWith(@NotNull Path other) {
                return delegate.endsWith(unwrap(other));
            }

            @Override
            @NotNull
            public Path normalize() {
                return wrap(delegate.normalize());
            }

            @Override
            @NotNull
            public Path resolve(@NotNull Path other) {
                return wrap(delegate.resolve(unwrap(other)));
            }

            @Override
            @NotNull
            public Path resolve(@NotNull String other) {
                return wrap(delegate.resolve(other));
            }

            @Override
            @NotNull
            public Path resolveSibling(@NotNull Path other) {
                return wrap(delegate.resolveSibling(unwrap(other)));
            }

            @Override
            @NotNull
            public Path resolveSibling(@NotNull String other) {
                return wrap(delegate.resolveSibling(other));
            }

            @Override
            @NotNull
            public Path relativize(@NotNull Path other) {
                return wrap(delegate.relativize(unwrap(other)));
            }

            @Override
            @NotNull
            public URI toUri() {
                return delegate.toUri();
            }

            @Override
            @NotNull
            public Path toAbsolutePath() {
                return wrap(delegate.toAbsolutePath());
            }

            @Override
            @NotNull
            public Path toRealPath(@NotNull LinkOption... options) throws IOException {
                return wrap(delegate.toRealPath(options));
            }

            @Override
            @NotNull
            public File toFile() {
                return delegate.toFile();
            }

            @Override
            @NotNull
            public WatchKey register(
                    @NotNull WatchService watcher,
                    @NotNull WatchEvent.Kind<?>[] events,
                    WatchEvent.Modifier... modifiers) throws IOException {
                return delegate.register(watcher, events, modifiers);
            }

            @Override
            public int compareTo(@NotNull Path other) {
                return delegate.compareTo(unwrap(other));
            }

            @Override
            public boolean equals(Object obj) {
                return obj instanceof LatchedPath lp && delegate.equals(lp.delegate);
            }

            @Override
            public int hashCode() {
                return delegate.hashCode();
            }

            @Override
            @NotNull
            public String toString() {
                return delegate.toString();
            }
        }
    }
}
