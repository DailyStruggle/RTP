package io.github.dailystruggle.search;

import java.util.Set;

/**
 * Pure-Java thread-safe, file-cached and TTL-invalidated online query expansion engine ({@code :yaml-api}).
 *
 * @governedBy ADR-104, ADR-115
 */
public final class OnlineQueryExpander {

    private static final OnlineQueryExpander DEFAULT = new OnlineQueryExpander(
            net.leaf.yaml.search.OnlineQueryExpander.defaultInstance());

    private final net.leaf.yaml.search.OnlineQueryExpander delegate;

    private OnlineQueryExpander(net.leaf.yaml.search.OnlineQueryExpander delegate) {
        this.delegate = delegate;
    }

    public static OnlineQueryExpander defaultInstance() {
        return DEFAULT;
    }

    net.leaf.yaml.search.OnlineQueryExpander delegate() {
        return delegate;
    }

    public Set<String> getCachedOrScheduleAsync(String rawToken, long nowEpochMs) {
        return delegate.getCachedOrScheduleAsync(rawToken, nowEpochMs);
    }
}
