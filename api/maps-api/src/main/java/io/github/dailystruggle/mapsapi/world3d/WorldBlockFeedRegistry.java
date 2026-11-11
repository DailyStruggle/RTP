package io.github.dailystruggle.mapsapi.world3d;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe service registry for {@link WorldBlockFeed} providers ({@code maps-api-ADR-003},
 * {@code anvil-api-ADR-003}, {@code ADR-115}).
 *
 * <p>Allows {@code LeafMap} to register its {@code .lca} / {@code .dlsh} 3D block and
 * client-renderer feed at runtime so {@code LeafConfig} (and other sibling tools) can consume
 * live 3D sub-chunk slices and drawing code without a compile-time dependency on {@code LeafMap}.
 *
 * @governedBy maps-api-ADR-003
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-MAP-001
 * @trace REQ-RTP-MAP-002
 * @trace S-004
 * @trace S-006
 */
public final class WorldBlockFeedRegistry {

    private static final Map<String, WorldBlockFeed> FEEDS = new ConcurrentHashMap<>();
    private static final AtomicReference<WorldBlockFeed> PRIMARY = new AtomicReference<>();

    private WorldBlockFeedRegistry() {
    }

    /**
     * Registers a {@link WorldBlockFeed} provider and promotes it as the active primary feed.
     *
     * @param feed non-null world block feed implementation
     */
    public static void register(WorldBlockFeed feed) {
        Objects.requireNonNull(feed, "feed must not be null");
        String id = Objects.requireNonNull(feed.providerId(), "providerId must not be null").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        FEEDS.put(id, feed);
        PRIMARY.set(feed);
    }

    /**
     * Unregisters a previously registered provider by its ID.
     *
     * @param providerId provider ID to remove
     */
    public static void unregister(String providerId) {
        if (providerId == null) {
            return;
        }
        WorldBlockFeed removed = FEEDS.remove(providerId.trim());
        if (removed != null) {
            PRIMARY.compareAndSet(removed, FEEDS.values().stream().findFirst().orElse(null));
        }
    }

    /**
     * Clears all registered providers (used during shutdown and test isolation).
     */
    public static void clear() {
        FEEDS.clear();
        PRIMARY.set(null);
    }

    /**
     * Returns the active primary {@link WorldBlockFeed}, if any provider is registered.
     */
    public static Optional<WorldBlockFeed> activeFeed() {
        WorldBlockFeed p = PRIMARY.get();
        if (p != null) {
            return Optional.of(p);
        }
        return FEEDS.values().stream().findFirst();
    }

    /**
     * Returns a specific {@link WorldBlockFeed} by provider ID.
     */
    public static Optional<WorldBlockFeed> find(String providerId) {
        if (providerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(FEEDS.get(providerId.trim()));
    }

    /**
     * Returns an immutable snapshot of all registered {@link WorldBlockFeed} providers.
     */
    public static Collection<WorldBlockFeed> allFeeds() {
        return List.copyOf(FEEDS.values());
    }
}
