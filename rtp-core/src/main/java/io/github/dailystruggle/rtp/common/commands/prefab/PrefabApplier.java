package io.github.dailystruggle.rtp.common.commands.prefab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Pure-function applier that merges a {@link Prefab} into current parsed YAML trees
 * and produces the updated trees alongside a per-file diff.
 * Inputs are treated as read-only; maps are deep-copied.
 */
public final class PrefabApplier {

    private PrefabApplier() {
    }

    /**
     * A single change inside one file. {@code keyPath} is dot-delimited from
     * the file root (e.g. {@code "queue.maxSize"}). {@code oldValue} is
     * {@code null} when the key was previously absent.
     *
     * @param keyPath  dot-delimited key path from the file root
     * @param oldValue previous value, or {@code null} if the key was absent
     * @param newValue new value after the overlay
     */
    public record Change(String keyPath, Object oldValue, Object newValue) {
    }

    /**
     * Result of {@link #apply}. {@code newTrees} mirrors the structure of the
     * input {@code currentTrees} (same file-id keys), with overlays merged in.
     * {@code perFileDiff} is keyed by file id and lists every {@link Change}
     * in stable iteration order (overlay-key order, then nested recursively).
     *
     * @param newTrees    merged YAML trees keyed by file id
     * @param perFileDiff per-file list of changes in stable iteration order
     */
    public record Result(
            Map<String, Map<String, Object>> newTrees,
            Map<String, List<Change>> perFileDiff
    ) {
    }

    /**
     * Apply {@code prefab} to {@code currentTrees} and produce the new trees
     * plus a per-file diff. Pure; does not mutate {@code currentTrees}.
     *
     * @param currentTrees parsed YAML trees keyed by file id. Never {@code null};
     *                     a file id named in the prefab but absent here is
     *                     treated as an empty base tree.
     * @param prefab       the prefab to apply. Never {@code null}.
     * @return a {@link Result} with the merged trees and the per-file diff.
     */
    public static Result apply(Map<String, Map<String, Object>> currentTrees, Prefab prefab) {
        Objects.requireNonNull(currentTrees, "currentTrees");
        Objects.requireNonNull(prefab, "prefab");

        Map<String, Map<String, Object>> newTrees = new LinkedHashMap<>();
        Map<String, List<Change>> diff = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : currentTrees.entrySet()) {
            newTrees.put(e.getKey(), deepCopy(e.getValue()));
        }

        // performance.yml
        Map<String, Object> perfOverlay = prefab.performanceOverlay();
        if (!perfOverlay.isEmpty()) {
            Map<String, Object> base = newTrees.computeIfAbsent("performance", k -> new LinkedHashMap<>());
            List<Change> fileDiff = new ArrayList<>();
            mergeInto(base, perfOverlay, "", fileDiff);
            if (!fileDiff.isEmpty()) {
                diff.put("performance", fileDiff);
            }
        }

        // safety.yml
        Map<String, Object> safetyOverlay = prefab.safetyOverlay();
        if (!safetyOverlay.isEmpty()) {
            Map<String, Object> base = newTrees.computeIfAbsent("safety", k -> new LinkedHashMap<>());
            List<Change> fileDiff = new ArrayList<>();
            mergeInto(base, safetyOverlay, "", fileDiff);
            if (!fileDiff.isEmpty()) {
                diff.put("safety", fileDiff);
            }
        }

        // regions/<id>.yml
        for (Map.Entry<String, Map<String, Object>> reg : prefab.regionOverlays().entrySet()) {
            String fileId = "regions/" + reg.getKey();
            Map<String, Object> overlay = reg.getValue();
            if (overlay.isEmpty()) {
                continue;
            }
            Map<String, Object> base = newTrees.computeIfAbsent(fileId, k -> new LinkedHashMap<>());
            List<Change> fileDiff = new ArrayList<>();
            mergeInto(base, overlay, "", fileDiff);
            if (!fileDiff.isEmpty()) {
                diff.put(fileId, fileDiff);
            }
        }

        return new Result(newTrees, diff);
    }

    /**
     * Overload wiring {@link MultiWorldExpander} before merging.
     * For prefabs with {@code expandPerWorld = true}, synthesises per-world region overlays.
     */
    public static Result apply(
            Map<String, Map<String, Object>> currentTrees,
            Prefab prefab,
            List<String> worldNames
    ) {
        return apply(currentTrees, prefab, worldNames, null);
    }

    /**
     * Variant of {@link #apply(Map, Prefab, List)} accepting an optional
     * {@link MultiWorldExpander.RegionOverlayAmender} for per-world dimension adjustments.
     */
    public static Result apply(
            Map<String, Map<String, Object>> currentTrees,
            Prefab prefab,
            List<String> worldNames,
            MultiWorldExpander.RegionOverlayAmender amender
    ) {
        Objects.requireNonNull(currentTrees, "currentTrees");
        Objects.requireNonNull(prefab, "prefab");
        Objects.requireNonNull(worldNames, "worldNames");
        if (!prefab.expandPerWorld()) {
            return apply(currentTrees, prefab);
        }
        Map<String, Map<String, Object>> currentRegions = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : currentTrees.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("regions/")) {
                currentRegions.put(key.substring("regions/".length()), e.getValue());
            }
        }
        Map<String, Map<String, Object>> expandedOverlays =
                MultiWorldExpander.expand(prefab, currentRegions, worldNames, amender);
        Prefab effective = new Prefab(
                prefab.id(),
                prefab.displayKey(),
                prefab.hoverKey(),
                prefab.description(),
                prefab.performanceOverlay(),
                prefab.safetyOverlay(),
                expandedOverlays,
                false
        );
        Result base = apply(currentTrees, effective);

        // A synthesised per-world region (regions/<world>.yml with
        // world: "<world>") is inert unless the matching worlds/<world>.yml
        // points its "region" field at it. Mirror every synthesised region
        // into a worlds/<world>.yml overlay that sets region: "<world>" so the
        // world actually uses its own region rather than the shared default.
        // The merge is sparse and idempotent: a world file already pointing at
        // its own region yields no diff.
        Map<String, Map<String, Object>> newTrees = base.newTrees();
        Map<String, List<Change>> diff = new LinkedHashMap<>(base.perFileDiff());
        for (String world : expandedOverlays.keySet()) {
            String fileId = "worlds/" + world;
            Map<String, Object> overlay = new LinkedHashMap<>();
            overlay.put("region", world);
            Map<String, Object> worldBase = newTrees.computeIfAbsent(fileId, k -> new LinkedHashMap<>());
            List<Change> fileDiff = new ArrayList<>();
            mergeInto(worldBase, overlay, "", fileDiff);
            if (!fileDiff.isEmpty()) {
                diff.put(fileId, fileDiff);
            }
        }
        return new Result(newTrees, diff);
    }

    @SuppressWarnings("unchecked")
    private static void mergeInto(
            Map<String, Object> base,
            Map<String, Object> overlay,
            String prefix,
            List<Change> diff
    ) {
        for (Map.Entry<String, Object> e : overlay.entrySet()) {
            String key = e.getKey();
            Object overlayVal = e.getValue();
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object baseVal = base.get(key);
            boolean keyPresent = base.containsKey(key);

            if (overlayVal instanceof Map && baseVal instanceof Map) {
                Map<String, Object> baseChild = (Map<String, Object>) baseVal;
                Map<String, Object> overlayChild = (Map<String, Object>) overlayVal;
                mergeInto(baseChild, overlayChild, path, diff);
            } else {
                if (!keyPresent || !Objects.equals(baseVal, overlayVal)) {
                    base.put(key, deepCopyValue(overlayVal));
                    diff.add(new Change(path, baseVal, overlayVal));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> in) {
        if (in == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> out = (in instanceof TreeMap) ? new TreeMap<>() : new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            out.put(e.getKey(), deepCopyValue(e.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object v) {
        if (v instanceof Map) {
            return deepCopy((Map<String, Object>) v);
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
