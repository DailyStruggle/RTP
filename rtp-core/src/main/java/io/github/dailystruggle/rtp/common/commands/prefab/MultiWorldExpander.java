package io.github.dailystruggle.rtp.common.commands.prefab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Synthesises per-world region overlays for {@link Prefab}s whose
 * {@link Prefab#expandPerWorld()} flag is {@code true} (in v1, only
 * {@code MultiWorld.INSTANCE}).
 *
 * <p>Given the current {@code regions/} subtree (keyed by region id, with a
 * {@code default} entry acting as the template) and the live world list,
 * {@link #expand} returns a {@code regionId -> overlay} map ready to feed
 * into {@link PrefabApplier#apply(Map, Prefab)}.
 *
 * <p><strong>Idempotency contract</strong>: a world that already has a
 * region - either keyed by the world name in {@code currentRegions} or
 * targeted by an existing region's {@code world} field (including the
 * {@code default} template's own world) - is left untouched; only worlds
 * without an existing region get a synthesised entry. Because the default
 * template usually maps to the overworld, that world is left alone rather
 * than cloned into a redundant {@code regions/world} duplicate. Re-running
 * the expander after a previous apply is therefore a no-op for the worlds
 * it has already covered.
 *
 * <p>Pure function: does not mutate any input. The cloned template is a
 * deep copy so callers can safely mutate the returned overlays.
 *
 * <p><strong>Per-dimension vert repair (injected)</strong>: the
 * {@code regions/default} template is authored for the overworld. Cloning its
 * {@code vert} block verbatim into the nether or the end yields an invalid
 * configuration - those dimensions never report sky light, and the nether has
 * a solid bedrock ceiling - so every vertical probe fails
 * {@code vert/no-stand-y}. The dimension rules themselves are not duplicated
 * here: the caller may supply a {@link RegionOverlayAmender} that is invoked
 * on each freshly-synthesised overlay, letting the impure shell route the
 * repair through the canonical {@code NetherEndConfigAmender} (the same helper
 * {@code /rtp config region} and the MultiConfig menu use). When no amender is
 * supplied (e.g. unit tests of the pure transform) the overlays are returned
 * as cloned, unrepaired.
 */
public final class MultiWorldExpander {

    public static final String DEFAULT_REGION_ID = "default";

    private MultiWorldExpander() {
    }

    /**
     * Hook invoked on each freshly-synthesised per-world region overlay so the
     * caller can repair it for the destination world's dimension without the
     * expander knowing the dimension rules. The implementation is expected to
     * mutate {@code regionOverlay} in place (e.g. drop the sky-light
     * requirement and clamp {@code maxY} for a nether world). Never called for
     * worlds that already have a region.
     */
    @FunctionalInterface
    public interface RegionOverlayAmender {
        /**
         * @param world         the destination world name
         * @param regionOverlay the mutable cloned region overlay for that world
         */
        void amend(String world, Map<String, Object> regionOverlay);
    }

    /**
     * Expand a {@code expandPerWorld} prefab against the live world list.
     *
     * @param prefab         the prefab to expand. Never {@code null}. If
     *                       {@link Prefab#expandPerWorld()} is {@code false}
     *                       the prefab's own {@code regionOverlays} are
     *                       returned verbatim (defensive copy).
     * @param currentRegions parsed {@code regions/<id>.yml} trees keyed by
     *                       region id. Must contain a {@code "default"}
     *                       entry when expansion is requested - that entry
     *                       is the per-world template.
     * @param worldNames     names of currently enabled worlds. Never
     *                       {@code null}; entries must be non-null and
     *                       non-empty.
     * @return a fresh {@code regionId -> overlay} map; iteration order is
     * the prefab's own overlays first, then world names in input order.
     * Empty when {@code worldNames} is empty and the prefab carries no
     * baked overlays.
     * @throws IllegalStateException    if expansion is requested but no
     *                                  {@code "default"} region exists in
     *                                  {@code currentRegions}.
     * @throws IllegalArgumentException if any {@code worldNames} entry is
     *                                  empty.
     */
    public static Map<String, Map<String, Object>> expand(
            Prefab prefab,
            Map<String, Map<String, Object>> currentRegions,
            List<String> worldNames
    ) {
        return expand(prefab, currentRegions, worldNames, null);
    }

    /**
     * Variant of {@link #expand(Prefab, Map, List)} that invokes
     * {@code amender} on each freshly-synthesised per-world overlay so the
     * caller can repair it for the destination world's dimension (see
     * {@link RegionOverlayAmender}). A {@code null} amender leaves the cloned
     * overlays untouched.
     */
    public static Map<String, Map<String, Object>> expand(
            Prefab prefab,
            Map<String, Map<String, Object>> currentRegions,
            List<String> worldNames,
            RegionOverlayAmender amender
    ) {
        Objects.requireNonNull(prefab, "prefab");
        Objects.requireNonNull(currentRegions, "currentRegions");
        Objects.requireNonNull(worldNames, "worldNames");

        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : prefab.regionOverlays().entrySet()) {
            out.put(e.getKey(), deepCopy(e.getValue()));
        }

        if (!prefab.expandPerWorld()) {
            return out;
        }

        Map<String, Object> template = currentRegions.get(DEFAULT_REGION_ID);
        if (template == null) {
            throw new IllegalStateException(
                    "MultiWorldExpander requires a '" + DEFAULT_REGION_ID
                            + "' region in currentRegions to use as the per-world template"
            );
        }

        // Collect every world already targeted by an existing region's "world"
        // field (including the default template's own world). A world that
        // already has a region mapped within it must not get a duplicate
        // synthesised overlay - e.g. the default region usually maps to the
        // overworld, so "world" is left alone rather than cloned into a
        // redundant regions/world entry.
        //
        // The "world" field may be an index placeholder such as "[0]" (main
        // world), "[1]" (second loaded world), etc. - the same notation
        // RegionConfigLoader resolves against the live world list. Resolve it
        // against worldNames (which is in the same load order) so the
        // placeholder-targeted world is recognised as mapped and not
        // duplicated.
        Map<String, Boolean> mappedWorlds = new LinkedHashMap<>();
        for (Map<String, Object> region : currentRegions.values()) {
            if (region == null) {
                continue;
            }
            Object mappedWorld = region.get("world");
            if (mappedWorld instanceof String s && !s.isEmpty()) {
                String resolved = resolveWorldRef(s, worldNames);
                if (resolved != null && !resolved.isEmpty()) {
                    mappedWorlds.put(resolved, Boolean.TRUE);
                }
            }
        }

        for (String world : worldNames) {
            Objects.requireNonNull(world, "worldName");
            if (world.isEmpty()) {
                throw new IllegalArgumentException("worldName must not be empty");
            }
            if (currentRegions.containsKey(world)) {
                // Idempotent: an existing per-world region wins; do not overwrite.
                continue;
            }
            if (mappedWorlds.containsKey(world)) {
                // An existing region already targets this world; leave it alone.
                continue;
            }
            if (out.containsKey(world)) {
                continue;
            }
            Map<String, Object> overlay = deepCopy(template);
            overlay.put("world", world);
            if (amender != null) {
                amender.amend(world, overlay);
            }
            out.put(world, overlay);
        }
        return out;
    }

    /**
     * Resolve a region "world" reference to a concrete world name. An index
     * placeholder of the form {@code "[N]"} resolves to {@code worldNames.get(N)}
     * (matching {@code RegionConfigLoader}'s index-based world selection against
     * the live world list). A literal name is returned verbatim. Returns
     * {@code null} when an index placeholder is out of range.
     */
    private static String resolveWorldRef(String ref, List<String> worldNames) {
        if (ref.length() > 2 && ref.charAt(0) == '[' && ref.charAt(ref.length() - 1) == ']') {
            try {
                int num = Integer.parseInt(ref.substring(1, ref.length() - 1).trim());
                if (num >= 0 && num < worldNames.size()) {
                    return worldNames.get(num);
                }
                return null;
            } catch (NumberFormatException ignored) {
                // Not a numeric index placeholder; treat as a literal name.
            }
        }
        return ref;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : in.entrySet()) {
            out.put(e.getKey(), deepCopyValue(e.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), deepCopyValue(e.getValue()));
            }
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object item : l) {
                out.add(deepCopyValue(item));
            }
            return out;
        }
        return v;
    }
}
