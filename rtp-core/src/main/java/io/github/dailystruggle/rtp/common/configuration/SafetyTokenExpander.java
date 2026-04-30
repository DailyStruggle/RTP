package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Eager, one-shot expansion of {@link SafetyKeys#airBlocks} and
 * {@link SafetyKeys#unsafeBlocks} at config-load time.
 *
 * <p>Historically, tag expansion ({@code #minecraft:leaves} → {@code OAK_LEAVES,
 * BIRCH_LEAVES, ACACIA_LEAVES, ...}) was performed lazily on the first
 * {@code JumpAdjustor.refreshSafetySets()} invocation, throttled to every 5
 * seconds. That left a window where the very first scan / probe / live
 * {@code BukkitRTPChunk.isAir} call after plugin enable saw the raw
 * {@code "#minecraft:leaves"} string, which never matches a palette /
 * {@code Material.name()} id — producing the user-visible "placed on top of
 * birch and acacia leaves" symptom.
 *
 * <p>This utility resolves all {@code #namespace:tag} tokens in the safety
 * lists immediately after the safety parser is built (in
 * {@code Configs.reloadConfigs}), and writes the
 * flattened set back to the in-memory parser value via
 * {@link ConfigParser#setConfigValue(String, Object)}. The on-disk
 * {@code safety.yml} is not touched — operator-authored tag tokens survive a
 * round-trip through the live config object only.
 *
 * <p>The resolved lists are logged at {@link Level#INFO} so operators can
 * verify which materials each tag expanded to (e.g.,
 * {@code [RTP] safety.airBlocks resolved to: AIR, CAVE_AIR, ..., OAK_LEAVES,
 * BIRCH_LEAVES, ACACIA_LEAVES, ...}). State-predicated tokens
 * ({@code MATERIAL[prop=val]}) are preserved verbatim because the compiled-form
 * consumer ({@code SafetyCompilationCache}) needs them.
 */
public final class SafetyTokenExpander {
    private SafetyTokenExpander() {}

    /**
     * Expand tag tokens in {@code airBlocks} and {@code unsafeBlocks}, write the
     * flattened lists back to the parser, and log the resolved sets.
     *
     * <p>If {@code RTP.serverAccessor} is null or returns an empty/null tag
     * snapshot, tag tokens are preserved as-is so that a later refresh (e.g.,
     * {@code JumpAdjustor.refreshSafetySets}) can resolve them once the server
     * tag registry is populated. This keeps the early-bootstrap path safe.
     */
    public static void expandAndApply(ConfigParser<SafetyKeys> safety) {
        expandAndApply(safety, 0);
    }

    /**
     * Maximum number of deferred retries scheduled when {@link #expandAndApply}
     * detects unresolved {@code #namespace:tag} tokens — symptomatic of the
     * Bukkit tag registry not yet being populated when {@code reloadConfigs}
     * runs (early plugin enable). Each retry forces a
     * {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#rebuildBlockTagSnapshot()}
     * before re-running expansion.
     */
    private static final int MAX_DEFERRED_RETRIES = 5;

    /** Delay between deferred retries, in server ticks (~2 s @ 20 TPS). */
    private static final long RETRY_DELAY_TICKS = 40L;

    private static void expandAndApply(ConfigParser<SafetyKeys> safety, int attempt) {
        if (safety == null) return;

        // Force a fresh read of the Bukkit tag registry. At plugin-enable time the
        // registry may not be populated yet; rebuilding here gives the freshest
        // possible snapshot and is cheap (one pass over Tag.REGISTRY_BLOCKS).
        Map<String, Set<String>> tagSnapshot = Collections.emptyMap();
        if (RTP.serverAccessor == null) {
            RTP.log(Level.WARNING,
                    "[RTP] SafetyTokenExpander: RTP.serverAccessor is null — tag tokens cannot be resolved");
        } else {
            try {
                RTP.serverAccessor.rebuildBlockTagSnapshot();
                Map<String, Set<String>> s = RTP.serverAccessor.blockTagSnapshot();
                if (s != null) tagSnapshot = s;
            } catch (Throwable ex) {
                RTP.log(Level.WARNING,
                        "[RTP] SafetyTokenExpander: tag snapshot failed ("
                                + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ")");
            }
        }

        int unresolved = 0;
        unresolved += applyOne(safety, SafetyKeys.airBlocks, tagSnapshot, attempt);
        unresolved += applyOne(safety, SafetyKeys.unsafeBlocks, tagSnapshot, attempt);

        if (unresolved > 0 && attempt < MAX_DEFERRED_RETRIES) {
            scheduleRetry(safety, attempt + 1);
        }
    }

    private static void scheduleRetry(ConfigParser<SafetyKeys> safety, int nextAttempt) {
        try {
            if (RTP.scheduler == null) return;
            RTP.scheduler.runTaskLater(() -> {
                try {
                    RTP.log(Level.FINE,
                            "[RTP] SafetyTokenExpander: deferred retry #" + nextAttempt
                                    + " for unresolved tag tokens");
                    expandAndApply(safety, nextAttempt);
                } catch (Throwable ex) {
                    RTP.log(Level.FINE,
                            "[RTP] SafetyTokenExpander: deferred retry #" + nextAttempt
                                    + " failed: " + ex.getClass().getSimpleName()
                                    + ": " + ex.getMessage());
                }
            }, RETRY_DELAY_TICKS);
        } catch (Throwable ex) {
            // Scheduler may not be available in test fixtures; non-fatal.
            RTP.log(Level.FINE,
                    "[RTP] SafetyTokenExpander: could not schedule deferred retry ("
                            + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ")");
        }
    }

    private static int applyOne(
            ConfigParser<SafetyKeys> safety,
            SafetyKeys key,
            Map<String, Set<String>> tagSnapshot,
            int attempt) {
        Object raw = safety.getConfigValue(key, new ArrayList<>());
        if (!(raw instanceof Collection<?>)) return 0;

        List<String> reapply = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int tagsExpanded = 0;
        int tagsUnresolved = 0;

        for (Object item : (Collection<?>) raw) {
            if (item == null) continue;
            String token = item.toString().trim();
            if (token.isEmpty()) continue;

            // State-predicated tokens (MATERIAL[prop=val] or *[prop=val]):
            // preserve verbatim for the compiled-form path.
            if (token.indexOf('[') >= 0) {
                if (seen.add(token)) reapply.add(token);
                continue;
            }

            if (token.charAt(0) == '#') {
                String tagId = token.substring(1);
                if (tagId.indexOf(':') < 0) tagId = "minecraft:" + tagId;
                tagId = tagId.toLowerCase(Locale.ROOT);
                Set<String> members = tagSnapshot.get(tagId);
                if (members != null && !members.isEmpty()) {
                    tagsExpanded++;
                    for (String m : members) {
                        if (m == null) continue;
                        String canonical = canonicalise(m);
                        if (seen.add(canonical)) reapply.add(canonical);
                    }
                } else {
                    // Snapshot doesn't have it yet — preserve original token so
                    // a later refresh can resolve it. Log at FINE so operators
                    // configuring custom tags get a hint.
                    tagsUnresolved++;
                    if (seen.add(token)) reapply.add(token);
                }
                continue;
            }

            // Bare MATERIAL token.
            String canonical = canonicalise(token);
            if (seen.add(canonical)) reapply.add(canonical);
        }

        try {
            safety.setConfigValue(key.name(), reapply);
        } catch (Throwable ex) {
            // Common in test fixtures where the parser has no backing yaml file;
            // also possible during early-bootstrap reload races. Non-fatal: the
            // lazy refresh in JumpAdjustor.refreshSafetySets is still wired and
            // will retry on the next adjustor invocation.
            RTP.log(Level.FINE,
                    "[RTP] SafetyTokenExpander: skipped reapply for "
                            + key.name() + " (" + ex.getClass().getSimpleName()
                            + ": " + ex.getMessage() + ")");
            return tagsUnresolved;
        }

        // Operator-visible summary on the initial expansion only; deferred
        // retries are logged at FINE so the console isn't spammed every 2 s.
        Level level = (attempt == 0) ? Level.INFO : Level.FINE;
        StringBuilder sb = new StringBuilder();
        sb.append("[RTP] safety.").append(key.name())
                .append(" resolved to ").append(reapply.size())
                .append(" entries");
        if (tagsExpanded > 0) sb.append(" (").append(tagsExpanded).append(" tag(s) expanded");
        if (tagsUnresolved > 0) {
            sb.append(tagsExpanded > 0 ? ", " : " (")
                    .append(tagsUnresolved).append(" tag(s) unresolved");
        }
        if (tagsExpanded > 0 || tagsUnresolved > 0) sb.append(")");
        RTP.log(level, sb.toString());
        return tagsUnresolved;
    }

    /**
     * Canonicalise to upper-case, namespace-stripped — matches
     * {@code Material.name()} / {@code PaletteIdentifierNormalizer.normalize}.
     */
    private static String canonicalise(String raw) {
        String trimmed = raw.trim();
        int colon = trimmed.indexOf(':');
        String local = (colon >= 0) ? trimmed.substring(colon + 1) : trimmed;
        return local.toUpperCase(Locale.ROOT);
    }
}
