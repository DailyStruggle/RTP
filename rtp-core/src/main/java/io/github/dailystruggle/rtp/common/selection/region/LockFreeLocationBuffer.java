package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.RTP;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Level;

public class LockFreeLocationBuffer {
    private final AtomicReferenceArray<RTPLocation> buffer;
    private final int mask;
    private final AtomicLong head = new AtomicLong(0);
    private final AtomicLong tail = new AtomicLong(0);

    public LockFreeLocationBuffer(int capacity) {
        int actualCapacity = 1;
        while (actualCapacity < capacity) actualCapacity <<= 1;
        this.buffer = new AtomicReferenceArray<>(actualCapacity);
        this.mask = actualCapacity - 1;
    }

    public boolean offer(RTPLocation location) {
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

    public void add(RTPLocation location) {
        if (!offer(location)) {
            RTP.serverAccessor.log(Level.SEVERE,"Buffer is full");
        }
    }

    public void clear() {
        while (poll() != null);
    }

    public RTPLocation poll() {
        long currentHead;
        long currentTail;
        RTPLocation location;
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

    public RTPLocation get(int index) {
        long currentHead = head.get();
        long currentTail = tail.get();
        if (index < 0 || currentHead + index >= currentTail) {
            return null;
        }
        return buffer.get((int) ((currentHead + index) & mask));
    }

    public void set(int index, RTPLocation location) {
        long currentHead = head.get();
        long currentTail = tail.get();
        if (index < 0 || currentHead + index >= currentTail) {
            return;
        }
        buffer.set((int) ((currentHead + index) & mask), location);
    }

    public int size() {
        return (int) (tail.get() - head.get());
    }

    public boolean isEmpty() {
        return tail.get() == head.get();
    }

    public RTPLocation peek() {
        long currentHead;
        long currentTail;
        RTPLocation location;

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
