package io.github.dailystruggle.rtp.common.commands.setup;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe registry for {@link SetupSession} instances with automatic TTL eviction.
 */
public final class SetupSessionRegistry {

    public static final long DEFAULT_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);

    private final Map<UUID, SetupSession> sessions = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public SetupSessionRegistry() {
        this(DEFAULT_TTL_MILLIS);
    }

    public SetupSessionRegistry(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    public SetupSession getOrCreate(UUID callerId) {
        Objects.requireNonNull(callerId, "callerId");
        evictExpired();
        return sessions.compute(callerId, (id, existing) -> {
            if (existing == null || existing.isExpired(ttlMillis)) {
                return new SetupSession(id);
            }
            existing.touch();
            return existing;
        });
    }

    public Optional<SetupSession> get(UUID callerId) {
        if (callerId == null) return Optional.empty();
        evictExpired();
        SetupSession s = sessions.get(callerId);
        if (s != null && s.isExpired(ttlMillis)) {
            sessions.remove(callerId);
            return Optional.empty();
        }
        return Optional.ofNullable(s);
    }

    public Optional<SetupSession> remove(UUID callerId) {
        if (callerId == null) return Optional.empty();
        return Optional.ofNullable(sessions.remove(callerId));
    }

    public void clear() {
        sessions.clear();
    }

    public int activeSessionCount() {
        evictExpired();
        return sessions.size();
    }

    private void evictExpired() {
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired(ttlMillis));
    }
}
