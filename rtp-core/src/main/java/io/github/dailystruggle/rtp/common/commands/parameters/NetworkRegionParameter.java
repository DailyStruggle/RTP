package io.github.dailystruggle.rtp.common.commands.parameters;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.network.RegionAvailabilityProvider;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-server region parameter (approved plugin-message proposal §7). A value
 * is either a bare local region name ({@code default}) or a peer-qualified
 * {@code server:region} entry ({@code backend-a:default}). Reads a pluggable
 * {@link RegionAvailabilityProvider} so the command layer stays transport-
 * agnostic: the same parameter works over the config overlay (lite), the
 * plugin-message snapshot (lite + Pro), or the SQL/Redis tiers (Pro).
 *
 * <p><b>Suggestion vs. validation split</b> (commands-api-ADR-001 addendum):</p>
 * <ul>
 *   <li>{@link #values()} / {@link #isSuggestionRelevant(UUID, String)} are
 *       <em>strict</em>: tab-completion only surfaces the local regions plus
 *       the currently-advertised {@code server:region} entries. A region that
 *       has drained out of the snapshot stops being suggested.</li>
 *   <li>{@link #isRelevant} (the execute-time gate) is <em>forgiving</em>: it
 *       rejects only when the provider <em>positively knows</em> the target
 *       server and that server is not advertising the region
 *       ({@link RegionAvailabilityProvider.Availability#KNOWN_UNAVAILABLE}).
 *       When the snapshot is unknown / stale (the common transport-lag case,
 *       and the only state the non-durable plugin-message tier can guarantee)
 *       it <em>accepts</em> and lets the destination backend decide on arrival.
 *       This is the load-bearing rule: transport lag must never become a
 *       command rejection (proposal §6.2).</li>
 * </ul>
 *
 * <p>Local region names (no {@code ':'}) are validated against
 * {@code RTP.selectionAPI.regionNames()} exactly as the legacy
 * {@link RegionParameter} would, so single-server behaviour is unchanged.</p>
 */
public class NetworkRegionParameter extends CommandParameter {

    private final RegionAvailabilityProvider availability;

    public NetworkRegionParameter(
            String permission,
            String description,
            RegionAvailabilityProvider availability) {
        super(permission, description, null);
        this.availability = availability == null
                ? RegionAvailabilityProvider.UNKNOWN_ALL : availability;
        // isRelevant is the execute-time gate; route it through our forgiving
        // network-aware check rather than a plain enum membership test.
        this.isRelevant = this::accepts;
        subParamMap.putIfAbsent("DEFAULT", new ConcurrentHashMap<>());
    }

    /** Local region names plus the live peer-qualified entries (strict suggest set). */
    @Override
    public Set<String> values() {
        Set<String> local;
        try {
            local = RTP.selectionAPI.regionNames();
        } catch (Throwable ignored) {
            local = Set.of();
        }
        Set<String> entries;
        try {
            entries = availability.availableEntries();
        } catch (Throwable ignored) {
            entries = Set.of();
        }
        if ((entries == null || entries.isEmpty())) {
            return local == null ? Set.of() : local;
        }
        Set<String> merged = new HashSet<>();
        if (local != null) merged.addAll(local);
        merged.addAll(entries);
        return merged;
    }

    /**
     * Execute-time validation. Accepts a bare local region that exists locally;
     * for a {@code server:region} value, rejects only on a positive
     * {@code KNOWN_UNAVAILABLE} verdict and otherwise accepts (unknown -&gt;
     * accept).
     */
    private boolean accepts(UUID senderId, String value) {
        if (value == null || value.isEmpty()) return false;
        int colon = value.indexOf(':');
        if (colon < 0) {
            // Bare local region name: defer to the local region set, but accept
            // if the local set cannot be read (degrade like RegionParameter).
            try {
                Set<String> local = RTP.selectionAPI.regionNames();
                return local == null || local.isEmpty() || local.contains(value);
            } catch (Throwable ignored) {
                return true;
            }
        }
        String serverId = value.substring(0, colon);
        String regionKey = value.substring(colon + 1);
        if (regionKey.isEmpty()) return false;
        RegionAvailabilityProvider.Availability verdict;
        try {
            verdict = availability.availabilityOf(serverId, regionKey);
        } catch (Throwable ignored) {
            // S-004 spirit: a flaky provider must never reject a valid value.
            return true;
        }
        // Only a positive "known and not available" rejects; UNKNOWN and
        // KNOWN_AVAILABLE both accept (the load-bearing rule).
        return verdict != RegionAvailabilityProvider.Availability.KNOWN_UNAVAILABLE;
    }

    /**
     * Tab-completion filter (strict). A value is suggested only when it is a
     * known-available {@code server:region} entry or a bare local region;
     * never when the provider positively knows it is unavailable.
     */
    @Override
    protected boolean isSuggestionRelevant(UUID senderId, String value) {
        if (value == null || value.isEmpty()) return false;
        int colon = value.indexOf(':');
        if (colon < 0) return true; // bare local region: values() already scoped it
        String serverId = value.substring(0, colon);
        String regionKey = value.substring(colon + 1);
        return availability.availabilityOf(serverId, regionKey)
                == RegionAvailabilityProvider.Availability.KNOWN_AVAILABLE;
    }

    @Override
    public Map<String, CommandParameter> subParams(String parameter) {
        return subParamMap.get("DEFAULT");
    }

    public void put(Map<String, CommandParameter> params) {
        subParamMap.put("DEFAULT", params);
    }

    public void put(String name, CommandParameter param) {
        subParamMap.get("DEFAULT").put(name, param);
    }
}
