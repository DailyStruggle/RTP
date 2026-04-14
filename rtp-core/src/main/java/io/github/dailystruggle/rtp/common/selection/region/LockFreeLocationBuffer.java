package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.RTP;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import java.util.logging.Level;

public class LockFreeLocationBuffer {
    private final AtomicReferenceArray<RTPLocation> buffer;
    private final int mask;
    private final AtomicLong head = new AtomicLong(0);
    private final AtomicLong tail = new AtomicLong(0);

    private Consumer<RTPLocation> onAdd = null;
    private Consumer<RTPLocation> onRemove = null;

    public LockFreeLocationBuffer(int capacity) {
        int actualCapacity = 1;
        while (actualCapacity < capacity) actualCapacity <<= 1;
        this.buffer = new AtomicReferenceArray<>(actualCapacity);
        this.mask = actualCapacity - 1;
    }

    public void setCallbacks(Consumer<RTPLocation> onAdd, Consumer<RTPLocation> onRemove) {
        this.onAdd = onAdd;
        this.onRemove = onRemove;
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
        if (onAdd != null) onAdd.accept(location);
        return true;
    }

    public void add(RTPLocation location) {
        if (!offer(location)) {
            RTP.serverAccessor.log(Level.SEVERE,"Buffer is full");
        }
    }

    public void clear() {
        RTPLocation location;
        while ((location = poll()) != null) {
            if (location.reservation() != null) {
                location.reservation().close();
            }
        }
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
        if (onRemove != null) onRemove.accept(location);
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
