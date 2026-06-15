package io.github.dailystruggle.rtp.common.commands.menu.multiconfig;

import io.github.dailystruggle.rtp.common.RTP;

/**
 * Factory for the two default {@link MultiConfigRemovalGuard} bodies wired on
 * plugin enable for the {@code regions} and {@code worlds} multi-config kinds.
 *
 * <p>CHECKLIST-multiconfig-menu step 12 / PROPOSAL §3.2:
 *
 * <ul>
 *   <li><b>regions</b>: locks the entry named {@code default} (case-insensitive).
 *       The default region is the seed clone source for {@code MultiConfigMutate.ADD}
 *       and must not be deletable, mirroring the existing {@code /rtp region remove}
 *       CLI guard.
 *   <li><b>worlds</b>: locks any entry whose name resolves to a live world on the
 *       server via {@code RTP.serverAccessor.getRTPWorld(name)}. Orphan world
 *       config entries (no longer loaded by the server) remain removable. Tolerates
 *       a null {@code RTP.serverAccessor} and a throwing accessor by reporting
 *       "not locked" (safer to allow removal than to NPE the dispatcher).
 * </ul>
 *
 * <p>These bodies match the anonymous classes pinned by
 * {@code MultiConfigRemovalGuardsTest} (step 9) verbatim, so any behavioral drift
 * here will be caught by that test.
 */
public final class DefaultMultiConfigRemovalGuards {

    private DefaultMultiConfigRemovalGuards() {
        // utility class
    }

    /**
     * Returns the default-region guard (locks {@code default} case-insensitively).
     *
     * @return a guard that locks the entry named {@code default}
     */
    public static MultiConfigRemovalGuard regions() {
        return new MultiConfigRemovalGuard() {
            @Override
            public boolean isLocked(String entryName) {
                return entryName != null && entryName.equalsIgnoreCase("default");
            }

            @Override
            public String reason(String entryName) {
                return "default region cannot be removed";
            }
        };
    }

    /**
     * Returns the default-world guard (locks any name the server reports as loaded).
     *
     * @return a guard that locks entries whose world is currently loaded on the server
     */
    public static MultiConfigRemovalGuard worlds() {
        return new MultiConfigRemovalGuard() {
            @Override
            public boolean isLocked(String entryName) {
                if (entryName == null) return false;
                if (RTP.serverAccessor == null) return false;
                try {
                    return RTP.serverAccessor.getRTPWorld(entryName) != null;
                } catch (RuntimeException ignored) {
                    return false;
                }
            }

            @Override
            public String reason(String entryName) {
                return "world is loaded on the server";
            }
        };
    }

    /**
     * Convenience wiring helper: registers both default guards under the
     * {@code regions} and {@code worlds} kinds on the process-wide
     * {@link MultiConfigRemovalGuards} registry. Idempotent (last-write-wins
     * per the registry's semantics).
     */
    public static void registerDefaults() {
        MultiConfigRemovalGuards.register("regions", regions());
        MultiConfigRemovalGuards.register("worlds", worlds());
    }
}
