package io.papermc.paper.threadedregions;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Task handles shaped like Folia's: the classes are package-private in a package other than the caller's, so
 * {@code handle.getClass().getMethod("cancel").invoke(handle)} throws IllegalAccessException from any other package.
 */
public final class FoliaStyleTasks {

    /** Like Folia's EntityScheduledTask: non-public class behind the public ScheduledTask interface. */
    static final class Task implements ScheduledTask {
        final AtomicInteger cancels = new AtomicInteger();

        @Override
        public void cancel() { cancels.incrementAndGet(); }
    }

    /** Non-public, no public interface: only setAccessible reaches cancel(). */
    static final class Opaque {
        final AtomicInteger cancels = new AtomicInteger();

        public void cancel() { cancels.incrementAndGet(); }
    }

    /** Non-public ScheduledTask whose cancel() throws. */
    static final class Broken implements ScheduledTask {
        @Override
        public void cancel() { throw new IllegalStateException("boom"); }
    }

    public static Object task() { return new Task(); }

    public static Object opaque() { return new Opaque(); }

    public static Object broken() { return new Broken(); }

    public static int cancels(Object handle) {
        if (handle instanceof Task t) return t.cancels.get();
        if (handle instanceof Opaque o) return o.cancels.get();
        return -1;
    }

    private FoliaStyleTasks() {}
}
