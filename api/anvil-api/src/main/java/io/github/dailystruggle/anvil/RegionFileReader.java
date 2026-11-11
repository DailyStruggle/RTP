package io.github.dailystruggle.anvil;

import java.io.IOException;

/**
 * Common SPI for format-specific region-file chunk decoders (ADR-077).
 *
 * <p>Implementations unpack format-specific layouts (Anvil {@code .mca} 4 KiB sectors built in;
 * other formats such as Linear {@code .linear} come from addons via {@link RegionFormatRegistry})
 * and return the decoded chunk entry containing the uncompressed NBT root compound. Readers receive
 * untrusted on-disk bytes and shall fail closed: bound every length and offset, cap decompressed
 * sizes, and throw {@link CorruptRegionEntryException} rather than read out of range.</p>
 *
 * @governedBy ADR-016
 * @governedBy ADR-077
 * @governedBy ADR-115
 */
public interface RegionFileReader {

    /**
     * Reads and decodes the chunk at region-local coordinates {@code (rx, rz)}.
     *
     * @param regionBytes the raw bytes of the region file
     * @param rx          region-local chunk x coordinate, 0..31
     * @param rz          region-local chunk z coordinate, 0..31
     * @return decoded chunk entry containing the root NBT compound, or {@code null} if the chunk is not present/unallocated
     * @throws CorruptRegionEntryException if the chunk header or payload structure is malformed
     * @throws IOException                 on decompression or I/O failure
     */
    AnvilReader.ChunkEntry readChunk(byte[] regionBytes, int rx, int rz) throws IOException;

    /**
     * As {@link #readChunk(byte[], int, int)} over {@code regionBytes[0, regionLength)}. Pooled
     * buffers may be longer than the file; bytes past {@code regionLength} are stale and must not
     * be read. The default copies when the lengths differ; built-in readers override without copying.
     */
    default AnvilReader.ChunkEntry readChunk(byte[] regionBytes, int regionLength, int rx, int rz) throws IOException {
        return readChunk(exact(regionBytes, regionLength), rx, rz);
    }

    /**
     * Fast check to determine whether the chunk at {@code (rx, rz)} is allocated and generated
     * in the region file without performing full decompression or NBT parsing.
     *
     * @param regionBytes the raw bytes of the region file
     * @param rx          region-local chunk x coordinate, 0..31
     * @param rz          region-local chunk z coordinate, 0..31
     * @return true if the chunk entry is present and non-empty in the region file
     */
    boolean isChunkGenerated(byte[] regionBytes, int rx, int rz);

    /** As {@link #isChunkGenerated(byte[], int, int)} over {@code regionBytes[0, regionLength)}. */
    default boolean isChunkGenerated(byte[] regionBytes, int regionLength, int rx, int rz) {
        if (regionBytes == null || regionLength < 0 || regionLength > regionBytes.length) return false;
        return isChunkGenerated(exact(regionBytes, regionLength), rx, rz);
    }

    /**
     * Record of a single world- or chunk-local block coordinate and its target canonical block
     * state (e.g. {@code "minecraft:oak_leaves[distance=1,persistent=false]"}).
     */
    record BlockPatch(int x, int y, int z, String blockState) {}

    /**
     * Reads, modifies, and writes chunk {@code (rx, rz)} in {@code regionFile}, applying all
     * {@code patches} whose existing block state in the saved chunk satisfies
     * {@code isReplaceable} (or whose target state is {@code "minecraft:void_air"}).
     *
     * @param regionFile    the on-disk region file path
     * @param rx            region-local chunk X coordinate, {@code 0..31}
     * @param rz            region-local chunk Z coordinate, {@code 0..31}
     * @param patches       block updates to apply inside chunk {@code (rx, rz)}
     * @param isReplaceable predicate testing whether an existing canonical block state in the
     *                      saved chunk may be overwritten (may be {@code null} to allow all)
     * @return {@code true} if the chunk exists in {@code regionFile} (and any applicable patches
     *         were committed), or {@code false} if the chunk is absent/ungenerated or the format
     *         does not support in-place patching
     * @throws IOException on I/O or malformed region/NBT structure
     */
    default boolean patchChunkBlocks(
            java.nio.file.Path regionFile,
            int rx,
            int rz,
            java.util.Collection<BlockPatch> patches,
            java.util.function.Predicate<String> isReplaceable) throws IOException {
        return false;
    }

    /**
     * Returns {@code true} if the decoded chunk root compound represents a completed worldgen
     * chunk ({@code Status == "minecraft:full"} / {@code "full"} / {@code "postprocessed"} or a
     * synthetic chunk root with no {@code Status} tag), or {@code false} if it is an in-pipeline
     * proto-chunk that has not yet finished world generation.
     */
    static boolean isFullChunkRoot(java.util.Map<String, Object> root) {
        if (root == null) {
            return false;
        }
        Object statusObj = root.get("Status");
        if (statusObj == null && root.get("Level") instanceof java.util.Map<?, ?> lvl) {
            statusObj = lvl.get("Status");
        }
        if (statusObj instanceof String status && !status.isEmpty()) {
            String norm = status.toLowerCase(java.util.Locale.ROOT);
            return norm.equals("minecraft:full")
                    || norm.equals("full")
                    || norm.equals("postprocessed")
                    || norm.equals("minecraft:postprocessed");
        }
        return true;
    }

    /**
     * Applies {@code patches} to the decoded Mojang chunk root compound {@code root} (`sections`
     * list of `block_states.palette` and packed `long[] data`), returning {@code true} if at
     * least one voxel in {@code root} was modified.
     */
    static boolean applyPatchesToChunkRoot(
            java.util.Map<String, Object> root,
            java.util.Collection<BlockPatch> patches,
            java.util.function.Predicate<String> isReplaceable) {
        if (!isFullChunkRoot(root) || patches == null || patches.isEmpty()) {
            return false;
        }
        java.util.Map<Integer, java.util.List<BlockPatch>> bySection = groupPatchesBySection(patches);
        if (bySection.isEmpty()) {
            return false;
        }
        java.util.List<java.util.Map<String, Object>> sectionList = extractSectionList(root);
        if (sectionList == null) {
            return false;
        }

        int dataVersion = (root.get("DataVersion") instanceof Number dv) ? dv.intValue() : 0;
        boolean anyModified = false;
        for (java.util.Map.Entry<Integer, java.util.List<BlockPatch>> secEntry : bySection.entrySet()) {
            java.util.Map<String, Object> section =
                    findOrCreateSection(sectionList, secEntry.getKey(), dataVersion);
            if (applyPatchesToSection(section, secEntry.getValue(), isReplaceable, dataVersion)) {
                anyModified = true;
            }
        }
        if (anyModified) {
            root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, new java.util.ArrayList<>(sectionList)));
        }
        return anyModified;
    }

    private static java.util.Map<Integer, java.util.List<BlockPatch>> groupPatchesBySection(
            java.util.Collection<BlockPatch> patches) {
        java.util.Map<Integer, java.util.List<BlockPatch>> bySection = new java.util.LinkedHashMap<>();
        for (BlockPatch p : patches) {
            if (p == null || p.blockState() == null || p.blockState().isBlank()) continue;
            bySection.computeIfAbsent(p.y() >> 4, k -> new java.util.ArrayList<>()).add(p);
        }
        return bySection;
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<java.util.Map<String, Object>> extractSectionList(
            java.util.Map<String, Object> root) {
        Object sectionsObj = root.get("sections");
        if (sectionsObj == null && root.get("Level") instanceof java.util.Map<?, ?> levelMap) {
            sectionsObj = levelMap.get("Sections");
        }
        java.util.List<?> rawItems = null;
        if (sectionsObj instanceof Nbt.NbtList nbtList) {
            rawItems = nbtList.items;
        } else if (sectionsObj instanceof java.util.List<?> rawList) {
            rawItems = rawList;
        }
        if (rawItems == null) {
            return null;
        }
        java.util.List<java.util.Map<String, Object>> sectionList = new java.util.ArrayList<>(rawItems.size());
        for (Object item : rawItems) {
            if (item instanceof java.util.Map<?, ?> m) {
                sectionList.add((java.util.Map<String, Object>) m);
            }
        }
        return sectionList;
    }

    private static java.util.Map<String, Object> findOrCreateSection(
            java.util.List<java.util.Map<String, Object>> sectionList, int sectionY, int dataVersion) {
        for (java.util.Map<String, Object> candidate : sectionList) {
            if (candidate.get("Y") instanceof Number yn && yn.intValue() == sectionY) {
                return candidate;
            }
        }
        java.util.Map<String, Object> section = new java.util.LinkedHashMap<>();
        section.put("Y", (byte) sectionY);
        java.util.Map<String, Object> bsInit = new java.util.LinkedHashMap<>();
        java.util.Map<String, Object> airMap =
                LcaSectionCodec.parseCanonicalStateToNbtCompound("minecraft:air", dataVersion);
        bsInit.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, java.util.List.of(airMap)));
        section.put("block_states", bsInit);
        sectionList.add(section);
        return section;
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<java.util.Map<String, Object>> extractPaletteList(
            java.util.Map<String, Object> blockStates, int dataVersion) {
        java.util.List<java.util.Map<String, Object>> paletteList = new java.util.ArrayList<>();
        Object palObj = blockStates.get("palette");
        java.util.List<?> raw = palObj instanceof Nbt.NbtList nl
                ? nl.items
                : (palObj instanceof java.util.List<?> l ? l : java.util.List.of());
        for (Object el : raw) {
            if (el instanceof java.util.Map<?, ?> pm) {
                paletteList.add(LcaSectionCodec.parseCanonicalStateToNbtCompound(
                        formatPaletteEntryCanonical((java.util.Map<String, Object>) pm), dataVersion));
            } else if (el instanceof String s) {
                paletteList.add(LcaSectionCodec.parseCanonicalStateToNbtCompound(
                        normalizeCanonicalState(s), dataVersion));
            }
        }
        if (paletteList.isEmpty()) {
            paletteList.add(LcaSectionCodec.parseCanonicalStateToNbtCompound("minecraft:air", dataVersion));
        }
        return paletteList;
    }

    @SuppressWarnings("unchecked")
    private static boolean applyPatchesToSection(
            java.util.Map<String, Object> section,
            java.util.List<BlockPatch> patches,
            java.util.function.Predicate<String> isReplaceable,
            int dataVersion) {
        if (!(section.get("block_states") instanceof java.util.Map<?, ?> rawBs)) {
            return false;
        }
        java.util.Map<String, Object> blockStates = (java.util.Map<String, Object>) rawBs;
        java.util.List<java.util.Map<String, Object>> paletteList = extractPaletteList(blockStates, dataVersion);
        int[] yzx = new int[4096];
        if (paletteList.size() > 1 && blockStates.get("data") instanceof long[] data && data.length > 0) {
            for (int i = 0; i < 4096; i++) {
                yzx[i] = PackedPaletteDecoder.decode(data, paletteList.size(), i);
            }
        }

        java.util.Map<String, Integer> indexByCanonical = new java.util.LinkedHashMap<>();
        for (int i = 0; i < paletteList.size(); i++) {
            indexByCanonical.putIfAbsent(formatPaletteEntryCanonical(paletteList.get(i)), i);
        }

        boolean sectionModified = false;
        for (BlockPatch p : patches) {
            String rawTarget = p.blockState().trim();
            boolean forceClear = "minecraft:void_air".equalsIgnoreCase(rawTarget)
                    || "void_air".equalsIgnoreCase(rawTarget);
            String targetCanonical = forceClear ? "minecraft:air" : normalizeCanonicalState(rawTarget);
            int voxelIdx = PackedPaletteDecoder.entryIndex(p.x() & 15, p.y() & 15, p.z() & 15);
            int curPalIdx = yzx[voxelIdx];
            String currentCanonical = (curPalIdx >= 0 && curPalIdx < paletteList.size())
                    ? formatPaletteEntryCanonical(paletteList.get(curPalIdx))
                    : "minecraft:air";
            if (!forceClear && isReplaceable != null && !isReplaceable.test(currentCanonical)) {
                continue;
            }
            if (currentCanonical.equals(targetCanonical)) {
                continue;
            }
            Integer targetPalIdx = indexByCanonical.get(targetCanonical);
            if (targetPalIdx == null) {
                targetPalIdx = paletteList.size();
                paletteList.add(LcaSectionCodec.parseCanonicalStateToNbtCompound(targetCanonical, dataVersion));
                indexByCanonical.put(targetCanonical, targetPalIdx);
            }
            yzx[voxelIdx] = targetPalIdx;
            sectionModified = true;
        }

        if (sectionModified) {
            blockStates.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, new java.util.ArrayList<>(paletteList)));
            if (paletteList.size() <= 1) {
                blockStates.remove("data");
            } else {
                blockStates.put("data", LcaSectionCodec.packBlockLongs(yzx, paletteList.size()));
            }
        }
        return sectionModified;
    }

    private static String normalizeCanonicalState(String raw) {
        int bracket = raw.indexOf('[');
        String name = bracket >= 0 ? raw.substring(0, bracket) : raw;
        if (!name.contains(":")) {
            name = "minecraft:" + name;
        }
        return bracket >= 0 ? name + raw.substring(bracket) : name;
    }

    private static String formatPaletteEntryCanonical(java.util.Map<String, Object> entry) {
        if (entry == null) return "minecraft:air";
        Object nameObj = entry.get("Name");
        if (nameObj == null) nameObj = entry.get("id");
        if (nameObj == null) nameObj = entry.get("");
        String name = nameObj != null ? nameObj.toString() : "minecraft:air";
        if (!name.contains(":")) {
            name = "minecraft:" + name;
        }
        Object propsObj = entry.get("Properties");
        if (propsObj == null) propsObj = entry.get("properties");
        if (!(propsObj instanceof java.util.Map<?, ?> props) || props.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (java.util.Map.Entry<?, ?> e : props.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }

    private static byte[] exact(byte[] regionBytes, int regionLength) {
        if (regionBytes == null || regionLength == regionBytes.length) return regionBytes;
        if (regionLength < 0 || regionLength > regionBytes.length) {
            throw new IllegalArgumentException("regionLength " + regionLength + " outside buffer " + regionBytes.length);
        }
        return java.util.Arrays.copyOf(regionBytes, regionLength);
    }
}
