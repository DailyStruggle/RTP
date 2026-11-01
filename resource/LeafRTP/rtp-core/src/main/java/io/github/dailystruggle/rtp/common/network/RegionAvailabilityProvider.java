package io.github.dailystruggle.rtp.common.network;

import java.util.Set;

/**
 * Transport-agnostic seam for cross-network {@code server:region} destination availability.
 * Consulted by {@link io.github.dailystruggle.rtp.common.commands.parameters.NetworkRegionParameter}.
 * Unknown availability yields {@link Availability#UNKNOWN} and accepts at validation.
 */
public interface RegionAvailabilityProvider {

    /** Tri-state availability verdict for a single {@code (server, region)} pair. */
    enum Availability {
        /** The server is known and is currently advertising this region. */
        KNOWN_AVAILABLE,
        /** The server is known and is <em>not</em> advertising this region. */
        KNOWN_UNAVAILABLE,
        /** No live information for this server (accept-at-execute applies). */
        UNKNOWN
    }

    /**
     * Every currently-servable {@code server:region} entry across the network,
     * for tab-completion. Never {@code null}; empty when no snapshot is known.
     * Mirrors the format produced by {@code PeerRegionRegistry.peerEntries()}.
     */
    Set<String> availableEntries();

    /**
     * Tri-state availability verdict for a destination.
     */
    Availability availabilityOf(String serverId, String regionKey);

    /**
     * Whether any live information at all is currently held for {@code serverId}.
     * {@code false} means {@link #availabilityOf(String, String)} will return
     * {@link Availability#UNKNOWN} for that server, so the parameter accepts.
     */
    boolean isServerKnown(String serverId);

    /** A provider that knows nothing - every query is {@link Availability#UNKNOWN}. */
    RegionAvailabilityProvider UNKNOWN_ALL = new RegionAvailabilityProvider() {
        @Override public Set<String> availableEntries() { return Set.of(); }
        @Override public Availability availabilityOf(String serverId, String regionKey) { return Availability.UNKNOWN; }
        @Override public boolean isServerKnown(String serverId) { return false; }
    };
}
