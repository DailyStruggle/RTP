package io.github.dailystruggle.rtp.anvil;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated executor for blocking Anvil region-file I/O (probe reads,
 * full-chunk decodes, etc.).
 *
 * <p>Rationale: {@link java.util.concurrent.ForkJoinPool#commonPool()} sizes
 * itself to {@code availableProcessors - 1} and is designed for short,
 * non-blocking, CPU-bound work. Blocking it on {@link
 * java.nio.file.Files#readAllBytes} starves every other common-pool task and
 * caps effective I/O parallelism at ~CPU count. ScanTask/PregenTask probe
 * workloads want higher I/O concurrency than that - disk read + zlib inflate
 * parallelizes well past CPU count when there's any disk-queue capacity.</p>
 *
 * <p>Sized at {@code max(8, 2 * availableProcessors)} daemon threads. Small
 * enough to avoid thrashing, large enough to keep the probe pipeline full
 * during scan/pregen bursts. Daemon threads so pool lifetime doesn't block
 * JVM shutdown.</p>
 *
 * <p>Single shared instance; callers get it via {@link #get()}.</p>
 */
public final class AnvilIoPool {

    private static final ExecutorService INSTANCE = create();

    private AnvilIoPool() {}

    private static ExecutorService create() {
        int parallelism = Math.max(8, 2 * Runtime.getRuntime().availableProcessors());
        return Executors.newFixedThreadPool(parallelism, new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "RTP-Anvil-IO-" + seq.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        });
    }

    /** Returns the shared blocking-I/O executor for Anvil region-file reads. */
    public static ExecutorService get() {
        return INSTANCE;
    }
}
