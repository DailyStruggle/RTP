package io.github.dailystruggle.effectsapi.common.hologram;

import io.github.dailystruggle.effectsapi.common.volumetric.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory / mock implementation of {@link HologramHandle} for testing and platforms without native display entities.
 */
public class VirtualHologramHandle implements HologramHandle {

    private final String id;
    private final String worldName;
    private volatile Vector3d position;
    private final List<String> lines = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public VirtualHologramHandle(String id, String worldName, Vector3d position, List<String> lines) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
        this.position = Objects.requireNonNull(position, "position must not be null");
        if (lines != null) {
            this.lines.addAll(lines);
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String worldName() {
        return worldName;
    }

    @Override
    public Vector3d position() {
        return position;
    }

    @Override
    public List<String> lines() {
        synchronized (this) {
            return Collections.unmodifiableList(new ArrayList<>(lines));
        }
    }

    @Override
    public void updateLines(List<String> lines) {
        synchronized (this) {
            this.lines.clear();
            if (lines != null) {
                this.lines.addAll(lines);
            }
        }
    }

    @Override
    public void teleport(Vector3d newPosition) {
        this.position = Objects.requireNonNull(newPosition, "newPosition must not be null");
    }

    @Override
    public void close() {
        closed.set(true);
        synchronized (this) {
            lines.clear();
        }
    }

    public boolean isClosed() {
        return closed.get();
    }
}
