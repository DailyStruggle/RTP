package io.github.dailystruggle.rtp.common.selection.region;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class LockFreeLocationBuffer {
    private final AtomicReferenceArray<CachedLocation> buffer;
    private final int mask;
    private final AtomicLong head = new AtomicLong(0);
    private final AtomicLong tail = new AtomicLong(0);

    public LockFreeLocationBuffer(int capacity) {
        int actualCapacity = 1;
        while (actualCapacity < capacity) actualCapacity <<= 1;
        this.buffer = new AtomicReferenceArray<>(actualCapacity);
        this.mask = actualCapacity - 1;
    }

    public boolean offer(CachedLocation location) {
        if (location == null) return false;
        long currentTail;
        long currentHead;
        do {
            currentTail = tail.get();
            currentHead = head.get();
            if (currentTail - currentHead >= buffer.length()) {
                return false;
            }
        } while (!tail.compareAndSet(currentTail, currentTail + 1));

        buffer.set((int) (currentTail & mask), location);
        return true;
    }

    public void add(CachedLocation location) {
        if (!offer(location)) {
            throw new IllegalStateException("Buffer is full");
        }
    }

    public void clear() {
        while (poll() != null);
    }

    public CachedLocation poll() {
        long currentHead;
        long currentTail;
        CachedLocation location;
        do {
            currentHead = head.get();
            currentTail = tail.get();
            if (currentHead >= currentTail) {
                return null;
            }
            location = buffer.get((int) (currentHead & mask));
        } while (location == null || !head.compareAndSet(currentHead, currentHead + 1));

        buffer.set((int) (currentHead & mask), null);
        return location;
    }

    public CachedLocation get(int index) {
        long currentHead = head.get();
        long currentTail = tail.get();
        if (index < 0 || currentHead + index >= currentTail) {
            return null;
        }
        return buffer.get((int) ((currentHead + index) & mask));
    }

    public int size() {
        return (int) (tail.get() - head.get());
    }

    public boolean isEmpty() {
        return tail.get() == head.get();
    }

    public CachedLocation peek() {
        long currentHead;
        long currentTail;
        CachedLocation location;

        do {
            currentHead = head.get();
            currentTail = tail.get();

            // If head has caught up to tail, the queue is genuinely empty
            if (currentHead >= currentTail) {
                return null;
            }

            location = buffer.get((int) (currentHead & mask));
        } while (location == null);

        return location;
    }
}
