package io.github.dailystruggle.rtp.common.commands.menu.multiconfig;

/**
 * Pluggable predicate consulted by the MultiConfig submenu to decide
 * whether an entry of a given {@code MultiConfigParser} kind may be removed.
 *
 * <p>Checked by {@code MultiConfigMenuBuilder} for UI display and re-verified by
 * {@code MenuRedeemSubcommand} on dispatch. Must be safe across threads.
 */
public interface MultiConfigRemovalGuard {

    /**
     * @return {@code true} if {@code entryName} must <i>not</i> be
     *         removed (in-use, mandatory seed, etc.); {@code false}
     *         otherwise.
     */
    boolean isLocked(String entryName);

    /**
     * Human-readable, locale-resolved reason why {@code entryName} is locked.
     * Used as hover text on disabled remove rows. Empty string suppresses hover.
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
