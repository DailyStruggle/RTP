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
 * region entry in {@code currentRegions} is left untouched; only worlds
 * without an existing region get a synthesised entry. Re-running the
 * expander after a previous apply is therefore a no-op for the worlds it
 * has already covered (checklist session 3, item 3.2).
 *
 * <p>Pure function: does not mutate any input. The cloned template is a
 * deep copy so callers can safely mutate the returned overlays.
 */
public final class MultiWorldExpander {

    public static final String DEFAULT_REGION_ID = "default";

    private MultiWorldExpander() {
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

        for (String world : worldNames) {
            Objects.requireNonNull(world, "worldName");
            if (world.isEmpty()) {
                throw new IllegalArgumentException("worldName must not be empty");
            }
            if (currentRegions.containsKey(world)) {
                // Idempotent: an existing per-world region wins; do not overwrite.
                continue;
            }
            if (out.containsKey(world)) {
                continue;
            }
            Map<String, Object> overlay = deepCopy(template);
            overlay.put("world", world);
            out.put(world, overlay);
        }
        return out;
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
