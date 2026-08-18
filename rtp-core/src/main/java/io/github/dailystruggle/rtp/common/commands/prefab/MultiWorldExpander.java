package io.github.dailystruggle.rtp.common.commands.prefab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Synthesises per-world region overlays for {@link Prefab}s with {@link Prefab#expandPerWorld()}.
 * Clones the {@code default} region template for unmapped worlds in an idempotent, pure manner.
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
     * Expand an {@code expandPerWorld} prefab against the live world list.
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

        // Collect worlds already targeted by existing region "world" fields (or index placeholders).
        // Prevents generating duplicate overlays for already-mapped worlds.
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
