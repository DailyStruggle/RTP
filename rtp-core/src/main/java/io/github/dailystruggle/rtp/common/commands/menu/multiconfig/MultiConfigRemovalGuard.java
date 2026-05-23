package io.github.dailystruggle.rtp.common.commands.menu.multiconfig;

/**
 * Pluggable predicate consulted by the MultiConfig submenu to decide
 * whether an entry of a given {@code MultiConfigParser} kind may be
 * removed.
 *
 * <p>Each guard is registered for a single {@code parserKind} string
 * (e.g. {@code "regions"}, {@code "worlds"}) via
 * {@link MultiConfigRemovalGuards#register(String, MultiConfigRemovalGuard)}.
 * Both layers of the submenu consult it:
 *
 * <ul>
 *   <li>The builder ({@code MultiConfigMenuBuilder}) suppresses /
 *       grays-out the Remove row in remove-mode for any entry where
 *       {@link #isLocked(String)} returns {@code true}, and uses
 *       {@link #reason(String)} for the hover tooltip.</li>
 *   <li>The dispatcher
 *       ({@code MenuRedeemSubcommand.dispatchMultiConfigMutate}) re-checks
 *       {@link #isLocked(String)} server-side before honoring a
 *       {@code MultiConfigMutate.REMOVE} token; the builder-side
 *       suppression is UX only, the dispatcher-side re-check is the
 *       actual invariant (S-001 / D-005 "never trust the client").</li>
 * </ul>
 *
 * <p>Implementations must be safe to call from any thread (the
 * dispatcher path is reached from the platform's chat/menu callback
 * thread, which on Folia is a region thread). Reads against
 * {@code RTPServerAccessor} that may schedule work are still fine -
 * the registered guards in this project only read in-memory state.
 *
 * <p>See {@code docs/dev/scratch/PROPOSAL-multiconfig-menu.md} §3.2 for
 * the full rationale and the two default guards
 * ({@code regions -> "default"} locked, {@code worlds -> server-loaded}
 * locked).
 */
public interface MultiConfigRemovalGuard {

    /**
     * @return {@code true} if {@code entryName} must <i>not</i> be
     *         removed (in-use, mandatory seed, etc.); {@code false}
     *         otherwise.
     */
    boolean isLocked(String entryName);

    /**
     * Human-readable, locale-resolved reason explaining why
     * {@code entryName} is locked. Used as hover text on the grayed
     * Remove row in remove-mode. Implementations may return any
     * non-null string; an empty string suppresses the hover but still
     * locks the entry. Callers must already have resolved any
     * {@code messages.yml} key before delegating here, or implementers
     * may resolve themselves - either is acceptable.
     */
    String reason(String entryName);

    /**
     * Singleton guard that locks nothing. Returned by
     * {@link MultiConfigRemovalGuards#get(String)} for unregistered
     * kinds so callers never have to null-check.
     */
    MultiConfigRemovalGuard ALLOW_ALL = new MultiConfigRemovalGuard() {
        @Override
        public boolean isLocked(String entryName) {
            return false;
        }

        @Override
        public String reason(String entryName) {
            return "";
        }
    };
}
