package io.github.dailystruggle.rtp.common.network;

import java.util.Set;

/**
 * Transport-agnostic seam over "which {@code server:region} destinations are
 * currently servable across the network" (approved plugin-message proposal §7,
 * §6.2). The command layer ({@link io.github.dailystruggle.rtp.common.commands.parameters.NetworkRegionParameter})
 * reads this and never knows which transport backs it: the same interface is
 * satisfied by a config overlay (lite, zero-infra), the plugin-message snapshot
 * (lite + Pro), or the SQL/Redis durable tiers (Pro). Only the implementation
 * swaps per edition/tier; the parameter's suggestion-vs-validation split stays
 * unchanged.
 *
 * <p><b>The load-bearing rule (proposal §6.2):</b> when availability for a
 * target server is <em>unknown</em> (no snapshot yet, transport not wired, or
 * the last heartbeat aged out) {@link #availabilityOf(String, String)} returns
 * {@link Availability#UNKNOWN}, and {@code NetworkRegionParameter#isRelevant}
 * must therefore <em>accept</em> the value and let the destination backend
 * decide on arrival. Transport lag must never become a user-visible command
 * rejection. Suggestion filtering may stay strict (only surface
 * {@link #availableEntries()}); validation must stay forgiving.</p>
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
     * Verdict for a specific destination.
     *
     * @param serverId  the target backend's network id; may be {@code null}
     *                  or empty to mean "any server" (treated as
     *                  {@link Availability#UNKNOWN} unless the region is
     *                  advertised by at least one known server)
     * @param regionKey the region name (unqualified)
     * @return the tri-state verdict; {@link Availability#UNKNOWN} whenever the
     *         server is not currently known
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
