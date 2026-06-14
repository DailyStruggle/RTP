package io.github.dailystruggle.rtp.common.commands.prefab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Pure-function applier: given the current parsed YAML trees of the affected
 * config files and a {@link Prefab}, returns the new trees plus a per-file
 * diff suitable for both the confirmation menu and the audit log.
 *
 * <p>No disk I/O lives here; the on-disk write happens in session 4
 * ({@code PrefabCommand.confirm}). All input maps are treated as read-only;
 * the returned trees are fresh {@link LinkedHashMap} copies safe for the
 * caller to mutate or serialise.
 *
 * <p><strong>Merge semantics</strong> (locked by {@code PROPOSAL} §3.1):
 * <ul>
 *   <li>Keys present in the overlay overwrite the matching key in the base.</li>
 *   <li>Keys absent from the overlay are preserved untouched.</li>
 *   <li>If both the overlay value and the base value are {@link Map}s, the
 *       merge recurses into them.</li>
 *   <li>{@link List}s and any other non-map values are replaced wholesale -
 *       no element-wise merging.</li>
 * </ul>
 *
 * <p><strong>File-key convention</strong> for {@code currentTrees}: top-level
 * keys are file ids - {@code "performance"} for {@code performance.yml} and
 * {@code "regions/<regionId>"} for each {@code regions/<regionId>.yml}.
 * {@link #apply} synthesises any region file id present in
 * {@link Prefab#regionOverlays()} but missing from {@code currentTrees}
 * (a new-region prefab) by treating the base as an empty map; the regional
 * overlay is then responsible for carrying the complete required shape.
 *
 * <p>Per the locked design decision (2026-05-20), <strong>no</strong> prefab
 * overlay touches {@code backlogCacheCap}. {@code PrefabRegistryTest} guards
 * this at the registry level, and {@code PrefabApplierTest} re-asserts it
 * survives the merge.
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
     * Overload that wires {@link MultiWorldExpander} in front of the merge.
     * For prefabs with {@link Prefab#expandPerWorld()} {@code false} this is
     * equivalent to {@link #apply(Map, Prefab)} (the {@code worldNames}
     * argument is ignored). For prefabs with the flag set (in v1, only
     * {@code MultiWorld.INSTANCE}), the expander synthesises a per-world
     * region overlay from the {@code regions/default} tree, the resulting
     * effective prefab is built with {@code expandPerWorld = false}, and the
     * merge proceeds normally.
     *
     * @param currentTrees parsed YAML trees keyed by file id; see
     *                     {@link #apply(Map, Prefab)}.
     * @param prefab       the prefab to apply.
     * @param worldNames   names of currently enabled worlds; ignored when
     *                     {@code prefab.expandPerWorld()} is {@code false}.
     * @return merged trees + per-file diff.
     */
    public static Result apply(
            Map<String, Map<String, Object>> currentTrees,
            Prefab prefab,
            List<String> worldNames
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
                MultiWorldExpander.expand(prefab, currentRegions, worldNames);
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
        return apply(currentTrees, effective);
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
