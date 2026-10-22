package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.bit.BitFinding;
import java.util.List;

/**
 * PBIT check owned by one configuration owner (ADR-121 section 6).
 *
 * <p>Contract: {@link #check} is pure over its inputs. It shall not write files, load chunks
 * (S-005), mutate static state or block on the main/region thread, and it returns findings built
 * from registered {@code RTP_*} (or the owner's namespace) codes. A thrown exception is reported by
 * {@link ConfigPbit} as {@code BIT_COMPONENT_THROWN}, never swallowed.
 */
public interface ConfigOwnerCheck {
    /** Stable lower-case owner id, e.g. {@code regions}; the runner component is {@code config-<id>}. */
    String id();

    List<BitFinding> check(ConfigSnapshot snapshot, ConfigEnvironment environment);

    /**
     * True when RTP loads the snapshot-relative {@code path} on behalf of this owner. A YAML file
     * no owner claims cannot break a reload, so its parse failure is a WARNING, not a rejection.
     */
    default boolean owns(String path) {
        return false;
    }
}
