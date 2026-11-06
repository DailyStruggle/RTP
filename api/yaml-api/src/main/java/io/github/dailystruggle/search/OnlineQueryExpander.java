package io.github.dailystruggle.search;

import java.util.Set;

/**
 * Pure-Java thread-safe, file-cached and TTL-invalidated online query expansion engine ({@code :yaml-api}).
 *
 * @governedBy ADR-104, ADR-115
 */
public final class OnlineQueryExpander {

    private static final OnlineQueryExpander DEFAULT = new OnlineQueryExpander(
            io.github.dailystruggle.rtp.common.search.OnlineQueryExpander.defaultInstance());

    private final io.github.dailystruggle.rtp.common.search.OnlineQueryExpander delegate;

    private OnlineQueryExpander(io.github.dailystruggle.rtp.common.search.OnlineQueryExpander delegate) {
        this.delegate = delegate;
    }

    public static OnlineQueryExpander defaultInstance() {
        return DEFAULT;
    }

    io.github.dailystruggle.rtp.common.search.OnlineQueryExpander delegate() {
        return delegate;
    }

    public Set<String> getCachedOrScheduleAsync(String rawToken, long nowEpochMs) {
        return delegate.getCachedOrScheduleAsync(rawToken, nowEpochMs);
    }
}
