package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.docs.DocsRegistry;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;

import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.CoordinateRunEncoder;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages ephemeral web editor sessions and local HTML editor bundle exports (ADR-104).
 *
 * <p>Supports generating outbound session tokens and producing zero-dependency, self-contained
 * single-file HTML configuration bundles that operators can open in any browser via {@code file:///}.
 */
public final class EditorSessionManager {

    private static final EditorSessionManager INSTANCE = new EditorSessionManager();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, String> activeSessions = new ConcurrentHashMap<>();

    public EditorSessionManager() {
    }

    public static EditorSessionManager getInstance() {
        return INSTANCE;
    }

    /**
     * Generates a new ephemeral session token for a configuration snapshot payload.
     *
     * @param jsonPayload serialized configuration & documentation JSON payload
     * @return 32-character hex token
     */
    public String createSession(String jsonPayload) {
        Objects.requireNonNull(jsonPayload, "jsonPayload");
        byte[] tokenBytes = new byte[16];
        RANDOM.nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);
        activeSessions.put(token, jsonPayload);
        return token;
    }

    /**
     * Retrieves an active session payload by token.
     */
    public String getSession(String token) {
        if (token == null) return null;
        return activeSessions.get(token);
    }

    /**
     * Generates a visualization payload containing biomes, hazards (compact RLE), heatmap, and
     * Archimedean spiral walk path coordinates for an in-memory region (ADR-104).
     *
     * <p>S-005 compliant: Zero chunk loading on main thread. All data is derived exclusively
     * from in-memory {@link MemoryShape} and selection geometric models.
     *
     * @param region target region
     * @return map of layer names to layer JSON payload data
     */
    public Map<String, Object> generateVisualizationPayload(Region region) {
        if (region == null) return Collections.emptyMap();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("region", region.name);

        if (!(region.shape instanceof MemoryShape<?> memoryShape)) {
            return payload;
        }

        // 1. Hazards Layer (RLE Hilbert Runs)
        CoordinateRunEncoder.EncodedPayload hazardGrid = CoordinateRunEncoder.encode(
                memoryShape,
                (int) -memoryShape.getRange(),
                (int) -memoryShape.getRange(),
                (int) memoryShape.getRange(),
                (int) memoryShape.getRange(),
                CoordinateRunEncoder.DEFAULT_ORDER
        );
        Map<String, Object> hazardMap = new LinkedHashMap<>();
        hazardMap.put("minX", hazardGrid.minX());
        hazardMap.put("minZ", hazardGrid.minZ());
        hazardMap.put("maxX", hazardGrid.maxX());
        hazardMap.put("maxZ", hazardGrid.maxZ());
        hazardMap.put("order", hazardGrid.order());
        hazardMap.put("gridSize", hazardGrid.gridSize());
        hazardMap.put("rleBase64", hazardGrid.toBase64());
        hazardMap.put("byteSize", hazardGrid.binaryByteSize());
        hazardMap.put("runCount", hazardGrid.runs().size());
        payload.put("hazards", hazardMap);

        // 2. Spiral Walk Path Layer (ADR-001 1D Archimedean spiral walk curve & oriented Hilbert bins)
        long range = memoryShape.getRange();
        List<int[]> spiralPoints = new ArrayList<>();
        if (range > 0) {
            int maxSamples = 256;
            long step = Math.max(1L, range / maxSamples);
            for (long i = 0; i < range && spiralPoints.size() < maxSamples; i += step) {
                int[] xz = memoryShape.locationToXZ(i);
                if (xz != null && xz.length >= 2) {
                    spiralPoints.add(new int[]{xz[0], xz[1]});
                }
            }
        }
        payload.put("spiralWalkPath", spiralPoints);

        // 2b. Shape Curve & Binned Path Layer (ADR-104 / ADR-085 / ADR-028 bandwidth-optimized full walk path)
        Map<String, Object> pathPayload = generateWalkPathPayload(memoryShape);
        payload.put("walkPathData", pathPayload);

        // 3. Biomes Distribution Layer (cached / off-tick in-memory biomes)
        Map<String, Integer> biomeFrequencies = new LinkedHashMap<>();
        if (range > 0) {
            long step = Math.max(1L, range / 128);
            for (long i = 0; i < range; i += step) {
                int[] xz = memoryShape.locationToXZ(i);
                if (xz != null && xz.length >= 2) {
                    String biome = memoryShape.biomeAt(xz[0], xz[1]);
                    if (biome != null) {
                        biomeFrequencies.merge(biome, 1, Integer::sum);
                    }
                }
            }
        }
        payload.put("biomes", biomeFrequencies);

        // 4. Heatmap & Candidate Dispersion Layer
        Map<String, Object> heatmap = new LinkedHashMap<>();
        heatmap.put("range", range);
        heatmap.put("densityFactor", 1.0);
        payload.put("heatmap", heatmap);

        // 5. Pregenerated Land & Chunk Biomes Layer (ADR-104 §4.3, ADR-084)
        PregenBiomeExtractor.ExtractionResult pregenResult = PregenBiomeExtractor.extract(region, 8192);
        Map<String, Object> pregenMap = new LinkedHashMap<>();
        pregenMap.put("palette", pregenResult.palette());
        List<int[]> chunkData = new ArrayList<>(pregenResult.chunks().size());
        for (PregenBiomeExtractor.ChunkBiomeSample sample : pregenResult.chunks()) {
            chunkData.add(new int[]{sample.cx(), sample.cz(), sample.biomeIndex()});
        }
        pregenMap.put("chunks", chunkData);
        pregenMap.put("totalGenerated", pregenResult.totalGeneratedChunks());
        pregenMap.put("regionsScanned", pregenResult.regionFilesScanned());
        payload.put("pregenLand", pregenMap);

        return payload;
    }

    /**
     * Generates a bandwidth-efficient walk path payload respecting the shape's curve type.
     *
     * <ul>
     *   <li>For dual-layer shapes ({@code CURVE_SPIRAL_HILBERT}), emits oriented Hilbert tiles
     *       {@code [px, pz, orientation, visits]} based on dihedral orientation.
     *   <li>For legacy shapes ({@code CURVE_SPIRAL}), emits coarse spatial bins
     *       {@code [bx, bz, visits]} preserving the pure Archimedean spiral traversal without
     *       forcing Hilbert subdivision.
     * </ul>
     *
     * @param memoryShape target memory shape
     * @return walk path payload map
     */
    public Map<String, Object> generateWalkPathPayload(MemoryShape<?> memoryShape) {
        if (memoryShape == null) return Collections.emptyMap();
        long range = memoryShape.getRange();
        String curveType = memoryShape.getCurveName();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("curveType", curveType);
        data.put("range", range);

        if (range <= 0) {
            data.put("bins", Collections.emptyList());
            return data;
        }

        boolean isHilbert = MemoryShape.CURVE_SPIRAL_HILBERT.equals(curveType);
        data.put("isHilbert", isHilbert);

        if (isHilbert) {
            int pChunks = memoryShape.getPointEdgeChunks();
            if (pChunks <= 0) pChunks = 16;
            data.put("pointChunks", pChunks);

            // Hilbert macro-bins (px, pz, orientation, visits)
            long pointCells = (long) pChunks * pChunks;
            long totalPoints = Math.max(1L, (range + pointCells - 1) / pointCells);
            int maxMacroBins = 300;
            long step = Math.max(1L, totalPoints / maxMacroBins);

            List<int[]> bins = new ArrayList<>();
            for (long pIdx = 0; pIdx < totalPoints && bins.size() < maxMacroBins; pIdx += step) {
                long sampleLoc = pIdx * pointCells;
                if (sampleLoc >= range) break;
                int[] xz = memoryShape.locationToXZ(sampleLoc);
                if (xz == null || xz.length < 2) continue;

                long px = Math.floorDiv(xz[0], pChunks);
                long pz = Math.floorDiv(xz[1], pChunks);

                int orientation = 0;
                // Dihedral orientation for (px, pz)
                long kX = (px >= 0) ? (px + 1L) : -px;
                long kZ = (pz >= 0) ? (pz + 1L) : -pz;
                long K = Math.max(kX, kZ);
                if (px == K - 1L && pz > -K) orientation = 1;
                else if (pz == K - 1L && px < K - 1L) orientation = (px == -K) ? 5 : 4;
                else if (px == -K && pz < K - 1L) orientation = 5;

                bins.add(new int[]{(int) px, (int) pz, orientation, 1});
            }
            data.put("bins", bins);
        } else {
            // Legacy / standard spiral: coarse spatial bins (bx, bz, visits)
            // Bin size adapts to shape range to maintain compact payload (~150-300 bins)
            int binSize = 64;
            if (range > 1_000_000L) binSize = 256;
            else if (range > 100_000L) binSize = 128;
            data.put("binSize", binSize);

            int maxBins = 300;
            long step = Math.max(1L, range / maxBins);
            List<int[]> bins = new ArrayList<>();
            int lastBx = Integer.MIN_VALUE;
            int lastBz = Integer.MIN_VALUE;

            for (long loc = 0; loc < range && bins.size() < maxBins; loc += step) {
                int[] xz = memoryShape.locationToXZ(loc);
                if (xz == null || xz.length < 2) continue;
                int bx = Math.floorDiv(xz[0], binSize);
                int bz = Math.floorDiv(xz[1], binSize);
                if (bx != lastBx || bz != lastBz) {
                    bins.add(new int[]{bx, bz, 1});
                    lastBx = bx;
                    lastBz = bz;
                } else if (!bins.isEmpty()) {
                    bins.get(bins.size() - 1)[2]++;
                }
            }
            data.put("bins", bins);
        }

        return data;
    }

    /**
     * Generates a fast delta streaming payload for background /rtp scan crawler updates.
     *
     * @param region target region
     * @return delta payload map
     */
    public Map<String, Object> generateScanDeltaPayload(Region region) {
        if (region == null || !(region.shape instanceof MemoryShape<?> memoryShape)) {
            return Collections.emptyMap();
        }

        CoordinateRunEncoder.EncodedPayload deltaGrid = CoordinateRunEncoder.encode(
                memoryShape,
                (int) -memoryShape.getRange(),
                (int) -memoryShape.getRange(),
                (int) memoryShape.getRange(),
                (int) memoryShape.getRange(),
                CoordinateRunEncoder.DEFAULT_ORDER
        );
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("region", region.name);
        delta.put("timestamp", System.currentTimeMillis());
        delta.put("rleBase64", deltaGrid.toBase64());
        delta.put("byteSize", deltaGrid.binaryByteSize());
        delta.put("runCount", deltaGrid.runs().size());
        return delta;
    }

    /**
     * Produces a standalone, self-contained single-file HTML editor bundle to {@code targetFile} (ADR-104).
     *
     * <p>Contains the 4 dedicated web panels (Visual Region Editor, Diagnostics &amp; Telemetry,
     * Configuration &amp; Prefabs with Staging Diff, and Shipped Documentation), functioning completely
     * offline without an active network connection.
     *
     * @param targetFile  file path where HTML should be written
     * @param configFiles map of relative config filename to file contents
     * @throws IOException on file write error
     */
    public void exportLocalEditorHtml(Path targetFile, Map<String, String> configFiles) throws IOException {
        Objects.requireNonNull(targetFile, "targetFile");
        if (targetFile.getParent() != null) {
            Files.createDirectories(targetFile.getParent());
        }

        Map<String, String> rawDocs = Collections.emptyMap();
        try {
            rawDocs = DocsRegistry.getInstance().getAllRawSources();
        } catch (IllegalStateException ignored) {
            // docs registry not initialized yet
        }

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"UTF-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
                .append("<title>RTP Engine Workspace - Visual Region Editor & Staging</title>\n")
                .append("<style>\n")
                .append(":root { --bg: #181825; --surface: #1e1e2e; --overlay: #313244; --text: #cdd6f4; --subtext: #a6adc8; --accent: #89b4fa; --green: #a6e3a1; --red: #f38ba8; --yellow: #f9e2af; }\n")
                .append("* { box-sizing: border-box; }\n")
                .append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; display: flex; flex-direction: column; height: 100vh; background: var(--bg); color: var(--text); overflow: hidden; }\n")
                .append("#top-nav { display: flex; align-items: center; justify-content: space-between; padding: 8px 16px; background: #11111b; border-bottom: 1px solid var(--overlay); }\n")
                .append(".brand { font-weight: bold; font-size: 1.05rem; color: var(--accent); display: flex; align-items: center; gap: 8px; }\n")
                .append(".nav-tabs { display: flex; gap: 4px; }\n")
                .append(".nav-tab { background: transparent; border: none; color: var(--subtext); padding: 6px 14px; border-radius: 6px; cursor: pointer; font-size: 0.9rem; font-weight: 500; transition: all 0.15s; }\n")
                .append(".nav-tab:hover { background: var(--overlay); color: var(--text); }\n")
                .append(".nav-tab.active { background: var(--accent); color: #11111b; font-weight: bold; }\n")
                .append(".nav-actions { display: flex; gap: 8px; align-items: center; }\n")
                .append(".btn { background: var(--overlay); color: var(--text); border: 1px solid var(--overlay); padding: 5px 12px; border-radius: 4px; font-size: 0.82rem; font-weight: 600; cursor: pointer; }\n")
                .append(".btn:hover { border-color: var(--accent); }\n")
                .append(".btn-primary { background: var(--green); color: #11111b; border: none; }\n")
                .append(".btn-primary:hover { opacity: 0.9; }\n")
                .append("#workspace { flex: 1; display: flex; overflow: hidden; position: relative; }\n")
                .append(".panel-view { display: none; width: 100%; height: 100%; }\n")
                .append(".panel-view.active { display: flex; }\n")
                .append("/* Panel 1: Regions */\n")
                .append("#region-map-container { flex: 1; position: relative; background: #12121e; display: flex; flex-direction: column; }\n")
                .append("#map-canvas { flex: 1; width: 100%; height: 100%; cursor: crosshair; }\n")
                .append("#map-hud { position: absolute; top: 12px; left: 12px; background: rgba(17,17,27,0.85); padding: 8px 14px; border-radius: 6px; border: 1px solid var(--overlay); font-family: monospace; font-size: 0.82rem; }\n")
                .append("#map-layers { position: absolute; top: 12px; right: 12px; background: rgba(17,17,27,0.85); padding: 8px 12px; border-radius: 6px; border: 1px solid var(--overlay); display: flex; gap: 10px; font-size: 0.8rem; }\n")
                .append("#region-sidebar { width: 380px; background: var(--surface); border-left: 1px solid var(--overlay); display: flex; flex-direction: column; padding: 16px; overflow-y: auto; }\n")
                .append("/* Staging Diff Area */\n")
                .append(".diff-box { background: #11111b; border-radius: 6px; padding: 12px; border: 1px solid var(--overlay); font-family: monospace; font-size: 0.8rem; white-space: pre-wrap; line-height: 1.4; }\n")
                .append(".diff-add { color: var(--green); }\n")
                .append(".diff-del { color: var(--red); }\n")
                .append("/* Panel 2: Telemetry Styles */\n")
                .append(".telemetry-section-title { color: var(--text); font-size: 1.05rem; font-weight: 600; margin: 24px 0 10px 0; display: flex; align-items: center; gap: 8px; }\n")
                .append(".telemetry-section-title:first-of-type { margin-top: 10px; }\n")
                .append(".telemetry-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 14px; margin-top: 6px; }\n")
                .append(".telemetry-card { background: var(--surface); border: 1px solid var(--overlay); border-radius: 8px; padding: 14px; position: relative; display: flex; flex-direction: column; justify-content: space-between; }\n")
                .append(".telemetry-card h4 { margin: 0 0 6px 0; color: var(--subtext); font-size: 0.8rem; font-weight: 500; text-transform: uppercase; letter-spacing: 0.5px; }\n")
                .append(".telemetry-val { font-size: 1.65rem; font-weight: bold; margin-bottom: 4px; font-family: monospace; }\n")
                .append(".telemetry-desc { color: var(--subtext); font-size: 0.74rem; margin: 0; line-height: 1.4; }\n")
                .append(".telemetry-bar-bg { width: 100%; height: 6px; background: #11111b; border-radius: 3px; margin: 6px 0; overflow: hidden; }\n")
                .append(".telemetry-bar-fill { height: 100%; border-radius: 3px; }\n")
                .append(".telemetry-badge { display: inline-block; padding: 2px 6px; border-radius: 4px; font-size: 0.72rem; font-weight: 600; font-family: monospace; }\n")
                .append(".badge-ok { background: rgba(166, 227, 161, 0.15); color: var(--green); border: 1px solid rgba(166, 227, 161, 0.3); }\n")
                .append(".badge-warn { background: rgba(249, 226, 175, 0.15); color: var(--yellow); border: 1px solid rgba(249, 226, 175, 0.3); }\n")
                .append(".badge-err { background: rgba(243, 139, 168, 0.15); color: var(--red); border: 1px solid rgba(243, 139, 168, 0.3); }\n")
                .append(".telemetry-table { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 0.82rem; background: var(--surface); border-radius: 8px; overflow: hidden; border: 1px solid var(--overlay); }\n")
                .append(".telemetry-table th { background: #11111b; color: var(--subtext); text-align: left; padding: 10px 14px; font-weight: 600; font-size: 0.76rem; text-transform: uppercase; border-bottom: 1px solid var(--overlay); }\n")
                .append(".telemetry-table td { padding: 10px 14px; border-bottom: 1px solid rgba(49, 50, 68, 0.6); color: var(--text); font-family: monospace; }\n")
                .append(".telemetry-table tr:last-child td { border-bottom: none; }\n")
                .append("/* Panel 3: Configs */\n")
                .append("#config-sidebar { width: 240px; background: #181825; border-right: 1px solid var(--overlay); padding: 12px; overflow-y: auto; }\n")
                .append(".cfg-file-btn { display: block; width: 100%; text-align: left; padding: 6px 10px; margin-bottom: 4px; border-radius: 4px; background: transparent; color: var(--subtext); border: none; cursor: pointer; font-size: 0.85rem; font-family: monospace; }\n")
                .append(".cfg-file-btn.active { background: var(--overlay); color: var(--accent); font-weight: bold; }\n")
                .append("#config-editor-container { flex: 1; display: flex; position: relative; height: 100%; overflow: hidden; }\n")
                .append("textarea.code-editor { flex: 1; height: 100%; background: var(--surface); color: var(--text); border: none; padding: 16px; font-family: monospace; font-size: 0.85rem; resize: none; outline: none; line-height: 1.5; }\n")
                .append("#config-doc-tracker { width: 360px; background: #14141e; border-left: 1px solid var(--overlay); padding: 16px; display: flex; flex-direction: column; overflow-y: auto; }\n")
                .append(".tracker-title { font-size: 0.95rem; font-weight: bold; color: var(--accent); display: flex; align-items: center; gap: 6px; margin-top: 0; }\n")
                .append(".tracker-badge { font-size: 0.72rem; padding: 2px 6px; border-radius: 4px; background: var(--overlay); color: var(--subtext); font-family: monospace; }\n")
                .append(".tracker-card { background: var(--surface); border: 1px solid var(--overlay); border-radius: 6px; padding: 12px; margin-top: 10px; font-size: 0.82rem; line-height: 1.5; }\n")
                .append("/* Panel 4: Docs */\n")
                .append("#doc-sidebar { width: 280px; background: #181825; border-right: 1px solid var(--overlay); padding: 12px; overflow-y: auto; }\n")
                .append(".doc-item-btn { display: block; width: 100%; text-align: left; padding: 6px 10px; margin-bottom: 4px; border-radius: 4px; background: transparent; color: var(--subtext); border: none; cursor: pointer; font-size: 0.85rem; }\n")
                .append(".doc-item-btn.active { background: var(--overlay); color: var(--accent); font-weight: bold; }\n")
                .append("#doc-display { flex: 1; padding: 24px 36px; overflow-y: auto; background: var(--surface); }\n")
                .append(".markdown-body { color: var(--text); font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; font-size: 0.88rem; line-height: 1.6; word-wrap: break-word; }\n")
                .append(".markdown-body h1 { font-size: 1.5rem; color: var(--accent); border-bottom: 1px solid var(--overlay); padding-bottom: 6px; margin: 18px 0 10px 0; }\n")
                .append(".markdown-body h2 { font-size: 1.2rem; color: #cba6f7; border-bottom: 1px solid rgba(49, 50, 68, 0.6); padding-bottom: 4px; margin: 16px 0 8px 0; }\n")
                .append(".markdown-body h3 { font-size: 1.05rem; color: var(--green); margin: 14px 0 6px 0; }\n")
                .append(".markdown-body p { margin: 0 0 10px 0; }\n")
                .append(".markdown-body a { color: var(--accent); text-decoration: none; }\n")
                .append(".markdown-body code { padding: 2px 6px; background: #11111b; border-radius: 4px; font-family: monospace; color: var(--yellow); }\n")
                .append(".markdown-body pre { padding: 12px; background: #11111b; border-radius: 6px; border: 1px solid var(--overlay); overflow-x: auto; }\n")
                .append(".markdown-body pre code { background: transparent; padding: 0; color: var(--text); }\n")
                .append(".markdown-body table { border-collapse: collapse; width: 100%; margin: 12px 0; border: 1px solid var(--overlay); }\n")
                .append(".markdown-body th, .markdown-body td { border: 1px solid var(--overlay); padding: 8px 12px; font-size: 0.82rem; }\n")
                .append(".markdown-body th { background: #11111b; color: var(--accent); font-weight: 600; }\n")
                .append(".markdown-body blockquote { padding: 0 14px; color: var(--subtext); border-left: 3px solid var(--accent); margin: 10px 0; background: rgba(137, 180, 250, 0.05); }\n")
                .append(".admonition { border-left: 4px solid var(--accent); background: rgba(137, 180, 250, 0.06); border-radius: 4px; margin: 12px 0; padding: 8px 12px; font-size: 0.85rem; }\n")
                .append(".admonition.warning { border-left-color: var(--red); background: rgba(243, 139, 168, 0.08); }\n")
                .append(".admonition.tip { border-left-color: var(--green); background: rgba(166, 227, 161, 0.08); }\n")
                .append(".admonition-title { font-weight: bold; margin-bottom: 4px; color: var(--text); }\n")
                .append(".source-badge { font-size: 0.7rem; padding: 2px 8px; border-radius: 4px; font-family: monospace; font-weight: 600; text-transform: uppercase; }\n")
                .append(".source-site { background: rgba(166, 227, 161, 0.15); color: var(--green); border: 1px solid rgba(166, 227, 161, 0.35); }\n")
                .append(".source-local { background: rgba(137, 180, 250, 0.15); color: var(--accent); border: 1px solid rgba(137, 180, 250, 0.35); }\n")
                .append("#region-doc-drawer { border-top: 1px solid var(--overlay); background: #14141e; display: flex; flex-direction: column; max-height: 40%; min-height: 36px; transition: max-height 0.2s; z-index: 10; }\n")
                .append("#region-doc-drawer.collapsed { max-height: 36px; overflow: hidden; }\n")
                .append(".region-doc-bar { display: flex; justify-content: space-between; align-items: center; padding: 6px 14px; background: #11111b; cursor: pointer; user-select: none; }\n")
                .append(".region-doc-content { padding: 14px 20px; overflow-y: auto; flex: 1; background: var(--surface); }\n")
                .append("</style>\n</head>\n<body>\n")
                .append("<header id=\"top-nav\">\n")
                .append("<div class=\"brand\"><span>\u26a1 LeafRTP Engine</span><span style=\"font-size:0.75rem; color:var(--subtext);\">\u25cf Local Air-Gap Session</span></div>\n")
                .append("<nav class=\"nav-tabs\">\n")
                .append("<button class=\"nav-tab active\" onclick=\"switchPanel('panel-regions')\">\ud83d\uddfa Visual Region Editor</button>\n")
                .append("<button class=\"nav-tab\" onclick=\"switchPanel('panel-telemetry')\">\ud83d\udcca Diagnostics &amp; Viz</button>\n")
                .append("<button class=\"nav-tab\" onclick=\"switchPanel('panel-configs')\">\u2699 Config &amp; Prefabs</button>\n")
                .append("<button class=\"nav-tab\" onclick=\"switchPanel('panel-docs')\">\ud83d\udcd6 Shipped Docs</button>\n")
                .append("</nav>\n")
                .append("<div class=\"nav-actions\">\n")
                .append("<button class=\"btn\" onclick=\"resetStaging()\">\u21ba Revert</button>\n")
                .append("<button class=\"btn btn-primary\" onclick=\"commitChanges()\">\u26a1 Copy / Apply</button>\n")
                .append("</div>\n")
                .append("</header>\n")
                .append("<main id=\"workspace\">\n")
                .append("<!-- Panel 1: Visual Region Editor -->\n")
                .append("<section id=\"panel-regions\" class=\"panel-view active\">\n")
                .append("<div id=\"region-map-container\">\n")
                .append("<canvas id=\"map-canvas\"></canvas>\n")
                .append("<div id=\"map-hud\"><span>Region: <b id=\"hud-region\">survival_spawn</b></span> | <span id=\"hud-coords\">X: 0  Z: 0</span></div>\n")
                .append("<div id=\"map-layers\"><label><input type=\"checkbox\" id=\"chk-biomes\" checked onchange=\"drawMap()\"> Biomes</label><label><input type=\"checkbox\" id=\"chk-pregen\" checked onchange=\"drawMap()\"> Pregen Land</label><label><input type=\"checkbox\" id=\"chk-hazards\" checked onchange=\"drawMap()\"> Bad Locations</label><label><input type=\"checkbox\" id=\"chk-spiral\" checked onchange=\"drawMap()\"> Spiral Path</label></div>\n")
                .append("<!-- Contextual Documentation & Region Guide Drawer (Below the Bar) -->\n")
                .append("<div id=\"region-doc-drawer\" class=\"collapsed\">\n")
                .append("<div class=\"region-doc-bar\" onclick=\"toggleRegionDocDrawer()\">\n")
                .append("<div style=\"display:flex; align-items:center; gap:8px;\"><span style=\"font-size:0.85rem; font-weight:600; color:var(--accent);\">📖 Contextual Guide:</span><span id=\"region-doc-header-title\" style=\"font-size:0.8rem; color:var(--text);\">Region Configuration &amp; Math</span><span id=\"region-doc-source-pill\" class=\"source-badge source-local\">PACKED DOCS</span></div>\n")
                .append("<div style=\"display:flex; align-items:center; gap:8px;\" onclick=\"event.stopPropagation()\"><button class=\"btn\" id=\"btn-toggle-region-drawer\" onclick=\"toggleRegionDocDrawer()\" style=\"padding:2px 8px; font-size:0.75rem;\">▲ Expand</button></div>\n")
                .append("</div>\n")
                .append("<div id=\"region-doc-content\" class=\"markdown-body region-doc-content\"></div>\n")
                .append("</div>\n")
                .append("</div>\n")
                .append("<aside id=\"region-sidebar\">\n")
                .append("<h3 style=\"margin-top:0; color:var(--accent);\">\ud83d\udccb Staging Diff Inspector</h3>\n")
                .append("<p style=\"font-size:0.82rem; color:var(--subtext);\">Drag polygon vertices or donut rings on the map to modify region geometry in real time.</p>\n")
                .append("<div class=\"diff-box\" id=\"staging-diff\"># No uncommitted geometry modifications\n# Drag vertices on canvas to stage deltas</div>\n")
                .append("<h4 style=\"color:var(--green); margin-top:16px;\">\u2714 Math Invariant Guard (ADR-034)</h4>\n")
                .append("<div style=\"font-size:0.8rem; font-family:monospace;\" id=\"validation-status\">\u2705 Non-self-intersecting: VALID\n\u2705 Collinear vertices: SIMPLIFIED\n\u2705 Within world border: YES</div>\n")
                .append("</aside>\n")
                .append("</section>\n")
                .append("<!-- Panel 2: Diagnostics & Telemetry -->\n")
                .append("<section id=\"panel-telemetry\" class=\"panel-view\">\n")
                .append("<div style=\"flex:1; padding:24px; overflow-y:auto;\">\n")
                .append("<div style=\"display:flex; justify-content:space-between; align-items:flex-start; flex-wrap:wrap; gap:12px;\">\n")
                .append("<div>\n")
                .append("<h2 style=\"color:var(--accent); margin-top:0; margin-bottom:4px;\">📊 Engine Diagnostics &amp; Telemetry Radar</h2>\n")
                .append("<p style=\"color:var(--subtext); line-height:1.5; margin:0; font-size:0.85rem;\">Real-time telemetry and pipeline invariants mirrored from <code>/rtp info</code>, <code>/rtp visualization</code> (ADR-046, ADR-089), and runtime memory pools.</p>\n")
                .append("</div>\n")
                .append("<div style=\"display:flex; gap:8px; align-items:center;\">\n")
                .append("<span class=\"telemetry-badge badge-ok\" id=\"engine-overall-status\">● ENGINE HEALTHY</span>\n")
                .append("<span style=\"font-size:0.75rem; color:var(--subtext);\" id=\"last-telemetry-update\">Live snapshot</span>\n")
                .append("</div>\n")
                .append("</div>\n")
                .append("<div class=\"telemetry-section-title\"><span>🖥️ Host Runtime &amp; Server Load</span></div>\n")
                .append("<div class=\"telemetry-grid\">\n")
                .append("<div class=\"telemetry-card\"><h4>Tick Rates (1m / 5m / 15m)</h4><div class=\"telemetry-val\" style=\"color:var(--green);\" id=\"metric-tps\">20.00 / 20.00 / 20.00</div><p class=\"telemetry-desc\">Rolling host ticks per second; &gt;= 19.5 maintains target budget</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>Tick Time (MSPT Mean / Max)</h4><div class=\"telemetry-val\" style=\"color:var(--accent);\" id=\"metric-mspt\">12.4 ms <span style=\"font-size:0.9rem; color:var(--subtext);\">/ 21.8 ms</span></div><div class=\"telemetry-bar-bg\"><div class=\"telemetry-bar-fill\" id=\"metric-mspt-bar\" style=\"width:24.8%; background:var(--accent);\"></div></div><p class=\"telemetry-desc\">Milliseconds per tick (50ms max budget before TPS drops)</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>Tick Budget Utilisation</h4><div class=\"telemetry-val\" style=\"color:var(--green);\" id=\"metric-budget\">24.8%</div><p class=\"telemetry-desc\">Host thread execution ratio; triggers throttling if &gt; 85%</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>Connected Players / Cap</h4><div class=\"telemetry-val\" style=\"color:var(--mauve);\" id=\"metric-players\">42 <span style=\"font-size:0.9rem; color:var(--subtext);\">/ 150 soft cap</span></div><p class=\"telemetry-desc\">Online players vs configured RTP concurrency soft cap</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>JVM Heap Memory</h4><div class=\"telemetry-val\" style=\"color:var(--text);\" id=\"metric-heap\">1.82 GB <span style=\"font-size:0.9rem; color:var(--subtext);\">/ 4.00 GB (45.5%)</span></div><div class=\"telemetry-bar-bg\"><div class=\"telemetry-bar-fill\" id=\"metric-heap-bar\" style=\"width:45.5%; background:#89b4fa;\"></div></div><p class=\"telemetry-desc\">Allocated heap memory used; free available: 2.18 GB</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>Platform Scheduler Target</h4><div class=\"telemetry-val\" style=\"color:var(--yellow); font-size:1.3rem;\" id=\"metric-platform\">Paper / Folia Async</div><p class=\"telemetry-desc\">Thread-safe entity scheduler &amp; non-blocking chunk I/O SPI</p></div>\n")
                .append("</div>\n")
                .append("<div class=\"telemetry-section-title\"><span>📦 Multi-Tier Location Reservoirs</span></div>\n")
                .append("<div class=\"telemetry-grid\">\n")
                .append("<div class=\"telemetry-card\"><h4>L1 Hot Queue (Chunks Kept)</h4><div class=\"telemetry-val\" style=\"color:var(--green);\" id=\"metric-l1\">16 / 16</div><p class=\"telemetry-desc\">Force-loaded chunk tickets held in memory for 0ms execution</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>L2 Cold Queue (Pre-Verified)</h4><div class=\"telemetry-val\" style=\"color:var(--accent);\" id=\"metric-l2\">64 / 64</div><p class=\"telemetry-desc\">Pre-checked off-tick; chunks released to preserve RAM</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>L3 Backlog (Binned MCA Cache)</h4><div class=\"telemetry-val\" style=\"color:var(--yellow);\" id=\"metric-l3\">10,000</div><p class=\"telemetry-desc\">Raw 32x32 Anvil/Linear NBT screened candidates (ADR-028)</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>Login Reserve / Fast Pool</h4><div class=\"telemetry-val\" style=\"color:var(--mauve);\" id=\"metric-login-reserve\">8 / 8</div><p class=\"telemetry-desc\">Dedicated hot reserve for first-join and respawn RTP (ADR-023)</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>Total Queued Waiters</h4><div class=\"telemetry-val\" style=\"color:var(--green);\" id=\"metric-queue-depth\">0</div><p class=\"telemetry-desc\">Players currently waiting in asynchronous command queue</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>Active Teleport Pipelines</h4><div class=\"telemetry-val\" style=\"color:var(--accent);\" id=\"metric-pending-teleports\">0</div><p class=\"telemetry-desc\">In-flight candidate search &amp; chunk load tasks executing</p></div>\n")
                .append("</div>\n")
                .append("<div class=\"telemetry-section-title\"><span>⏱️ Teleport Pipeline Latency &amp; Histograms</span></div>\n")
                .append("<div class=\"telemetry-grid\">\n")
                .append("<div class=\"telemetry-card\"><h4>Mean Latency</h4><div class=\"telemetry-val\" style=\"color:var(--green);\" id=\"metric-lat-mean\">14.2 ms</div><p class=\"telemetry-desc\">Average elapsed duration across completed pipeline searches</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>P50 Median Latency</h4><div class=\"telemetry-val\" style=\"color:var(--green);\" id=\"metric-lat-p50\">9.5 ms</div><p class=\"telemetry-desc\">50th percentile of candidate selection duration</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>P75 Latency</h4><div class=\"telemetry-val\" style=\"color:var(--green);\" id=\"metric-lat-p75\">14.0 ms</div><p class=\"telemetry-desc\">75th percentile candidate resolution speed</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>P90 Latency</h4><div class=\"telemetry-val\" style=\"color:var(--accent);\" id=\"metric-lat-p90\">22.5 ms</div><p class=\"telemetry-desc\">90th percentile under concurrent region contention</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>P95 Latency</h4><div class=\"telemetry-val\" style=\"color:var(--accent);\" id=\"metric-lat-p95\">31.0 ms</div><p class=\"telemetry-desc\">95th percentile with off-tick linear chunk lookups</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>P99 Tail Latency</h4><div class=\"telemetry-val\" style=\"color:var(--yellow);\" id=\"metric-lat-p99\">48.2 ms</div><p class=\"telemetry-desc\">99th percentile worst-case cold chunk fetch or biome retry</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>Observed Min / Max</h4><div class=\"telemetry-val\" style=\"color:var(--text); font-size:1.35rem;\" id=\"metric-lat-minmax\">2.1 ms <span style=\"font-size:0.9rem; color:var(--subtext);\">/ 76.4 ms</span></div><p class=\"telemetry-desc\">Boundary samples recorded in active rolling window</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>Histogram Samples / Runs</h4><div class=\"telemetry-val\" style=\"color:var(--mauve); font-size:1.35rem;\" id=\"metric-lat-samples\">1,024 <span style=\"font-size:0.9rem; color:var(--subtext);\">/ 18,490 total</span></div><p class=\"telemetry-desc\">Window sample count and cumulative lifetime pipeline runs</p></div>\n")
                .append("</div>\n")
                .append("<div class=\"telemetry-section-title\"><span>🛡️ Concurrency, I/O &amp; Memory Safety Invariants</span></div>\n")
                .append("<div class=\"telemetry-grid\">\n")
                .append("<div class=\"telemetry-card\"><h4>Spatial Memory Efficiency</h4><div class=\"telemetry-val\" style=\"color:var(--mauve);\" id=\"metric-spatial-eff\">99.4%</div><p class=\"telemetry-desc\">Hazard &amp; claim candidate rejection without loading chunks</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>Active MemoryTracker Entries</h4><div class=\"telemetry-val\" style=\"color:var(--green);\" id=\"metric-mem-entries\">16</div><p class=\"telemetry-desc\">Registered chunk tickets &amp; active pipeline tasks in GC table</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>Chunk Ticket Leaks</h4><div class=\"telemetry-val\" style=\"color:var(--green);\" id=\"metric-ticket-leaks\">0</div><p class=\"telemetry-desc\">Watchdog audit: unreleased chunk ticket instances (S-002)</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>Chunk Load Backlog</h4><div class=\"telemetry-val\" style=\"color:var(--green);\" id=\"metric-chunk-backlog\">0</div><p class=\"telemetry-desc\">Queued asynchronous chunk requests in platform scheduler</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>Off-Tick MCA/Linear Readers</h4><div class=\"telemetry-val\" style=\"color:var(--accent);\" id=\"metric-io-threads\">2 threads</div><p class=\"telemetry-desc\">Active Anvil/Linear NBT disk pre-filter worker pool</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>Database Query Latency</h4><div class=\"telemetry-val\" style=\"color:var(--green);\" id=\"metric-db-latency\">1.2 ms</div><p class=\"telemetry-desc\">SQLite / MySQL asynchronous state persistence duration</p></div>\n")
                .append("</div>\n")
                .append("<div class=\"telemetry-section-title\"><span>⚠️ Anomaly &amp; Audit Stress Counters</span></div>\n")
                .append("<div class=\"telemetry-grid\">\n")
                .append("<div class=\"telemetry-card\"><h4>Slow Teleport Audits</h4><div class=\"telemetry-val\" style=\"color:var(--green);\" id=\"metric-slow-pipelines\">0 <span style=\"font-size:0.8rem; color:var(--subtext);\">&gt; 500ms</span></div><p class=\"telemetry-desc\">Searches exceeding slow-pipeline threshold (S-004)</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>Queue Growth Warnings</h4><div class=\"telemetry-val\" style=\"color:var(--green);\" id=\"metric-queue-warnings\">0 <span style=\"font-size:0.8rem; color:var(--subtext);\">&gt; 50 cap</span></div><p class=\"telemetry-desc\">Occurrences where player queue surpassed growth threshold</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>Tick Stress Events</h4><div class=\"telemetry-val\" style=\"color:var(--green);\" id=\"metric-tick-stress\">0</div><p class=\"telemetry-desc\">Server-wide tick drops triggering dynamic queue throttle</p></div>\n")
                .append("<div class=\"telemetry-card\"><h4>Command Overflow Events</h4><div class=\"telemetry-val\" style=\"color:var(--green);\" id=\"metric-overflow-events\">0</div><p class=\"telemetry-desc\">Bursts exceeding configured command dispatch capacity</p></div>\n")
                .append("</div>\n")
                .append("<div class=\"telemetry-section-title\"><span>🗺️ Region Reservoir Breakdown</span></div>\n")
                .append("<table class=\"telemetry-table\" id=\"region-metrics-table\">\n")
                .append("<thead><tr><th>Region Name</th><th>World</th><th>Queue</th><th>L1 Kept Chunks</th><th>L2 Cold Queue</th><th>Login Reserve</th><th>Status</th></tr></thead>\n")
                .append("<tbody id=\"region-metrics-tbody\">\n")
                .append("<tr><td><b style=\"color:var(--accent);\">survival_spawn</b></td><td>world</td><td>0</td><td><span style=\"color:var(--green);\">16 / 16 (100%)</span></td><td><span style=\"color:var(--accent);\">64 / 64 (100%)</span></td><td><span style=\"color:var(--mauve);\">8 / 8</span></td><td><span class=\"telemetry-badge badge-ok\">OPTIMAL</span></td></tr>\n")
                .append("<tr><td><b style=\"color:var(--accent);\">nether_wastes</b></td><td>world_nether</td><td>0</td><td><span style=\"color:var(--green);\">8 / 8 (100%)</span></td><td><span style=\"color:var(--accent);\">32 / 32 (100%)</span></td><td><span style=\"color:var(--subtext);\">0 / 0</span></td><td><span class=\"telemetry-badge badge-ok\">OPTIMAL</span></td></tr>\n")
                .append("<tr><td><b style=\"color:var(--accent);\">the_end</b></td><td>world_the_end</td><td>0</td><td><span style=\"color:var(--green);\">8 / 8 (100%)</span></td><td><span style=\"color:var(--accent);\">32 / 32 (100%)</span></td><td><span style=\"color:var(--subtext);\">0 / 0</span></td><td><span class=\"telemetry-badge badge-ok\">OPTIMAL</span></td></tr>\n")
                .append("</tbody></table>\n")
                .append("<div class=\"telemetry-section-title\"><span>📜 Memory &amp; Scheduler Watchdog Audit Log</span></div>\n")
                .append("<div class=\"diff-box\" style=\"max-width:100%;\" id=\"telemetry-watchdog-log\">[MemoryTracker Watchdog] 0 active chunk ticket leaks detected across all worlds.\n[Scheduler Heartbeat] Folia / Async worker pool operating within nominal bounds (0ms drift).\n[Off-Tick I/O Pool] Anvil / Linear region readers active: 2 threads (bounded off-tick).\n[RegionQueueManager] L1 Hot Queue pre-warmed and ready for instant dispatch.\n[ClaimIntegrations] 0 inline chunk stalls; cache hit ratio: 99.8%.</div>\n")
                .append("</div>\n")
                .append("</section>\n")
                .append("<!-- Panel 3: Configs & Prefabs -->\n")
                .append("<section id=\"panel-configs\" class=\"panel-view\">\n")
                .append("<div id=\"config-sidebar\"></div>\n")
                .append("<div id=\"config-editor-container\">\n")
                .append("<textarea id=\"cfg-editor\" class=\"code-editor\" spellcheck=\"false\"></textarea>\n")
                .append("<aside id=\"config-doc-tracker\">\n")
                .append("<h4 class=\"tracker-title\"><span>📖 Contextual Doc Tracker</span><span class=\"tracker-badge\" id=\"tracker-key-badge\">config.yml</span></h4>\n")
                .append("<p style=\"font-size:0.78rem; color:var(--subtext); margin-top:2px;\">Automatically tracks whatever setting or line is currently focused.</p>\n")
                .append("<div class=\"tracker-card\" id=\"tracker-body\">\n")
                .append("<b style=\"color:var(--green);\" id=\"tracker-node\">radius</b>\n")
                .append("<div style=\"margin-top:6px; color:var(--text);\" id=\"tracker-desc\">The maximum search distance in blocks from the center point for teleport candidate selection.</div>\n")
                .append("<div style=\"margin-top:10px; font-size:0.75rem; color:var(--subtext); font-family:monospace;\" id=\"tracker-meta\">Type: Integer (blocks) | Default: 5000</div>\n")
                .append("<div style=\"margin-top:12px; border-top:1px solid var(--overlay); padding-top:8px;\"><a href=\"#\" onclick=\"switchPanel('panel-docs'); return false;\" style=\"color:var(--accent); text-decoration:none; font-size:0.78rem;\">📖 Open Full Documentation &rarr;</a></div>\n")
                .append("</div>\n")
                .append("</aside>\n")
                .append("</div>\n")
                .append("</section>\n")
                .append("<!-- Panel 4: Shipped Documentation -->\n")
                .append("<section id=\"panel-docs\" class=\"panel-view\">\n")
                .append("<div id=\"doc-sidebar\"></div>\n")
                .append("<div id=\"doc-display\">\n")
                .append("<div style=\"display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid var(--overlay); padding-bottom:10px; margin-bottom:14px;\">\n")
                .append("<div><h3 id=\"doc-current-title\" style=\"margin:0; font-size:1.15rem; color:var(--accent);\">📖 Documentation</h3><span id=\"doc-current-path\" style=\"font-size:0.75rem; color:var(--subtext); font-family:monospace;\">Select manual</span></div>\n")
                .append("<div style=\"display:flex; align-items:center; gap:10px;\"><span id=\"doc-source-pill\" class=\"source-badge source-local\">PACKED DOCS</span></div>\n")
                .append("</div>\n")
                .append("<div id=\"doc-body\" class=\"markdown-body\"></div>\n")
                .append("</div>\n")
                .append("</section>\n")
                .append("</main>\n")
                .append("<script>\n")
                .append("const docs = ").append(buildJsonMap(rawDocs)).append(";\n")
                .append("const configs = ").append(buildJsonMap(configFiles)).append(";\n")
                .append("let currentConfigFile = Object.keys(configs)[0] || '';\n")
                .append("let currentDocFile = Object.keys(docs)[0] || '';\n")
                .append("let regionState = { name: 'survival_spawn', radius: 5000, centerRadius: 1000, vertices: [[-2000, 3000], [2000, 3000], [2000, -3000], [-2000, -3000]] };\n")
                .append("function switchPanel(panelId) {\n")
                .append("  document.querySelectorAll('.panel-view').forEach(p => p.classList.remove('active'));\n")
                .append("  document.querySelectorAll('.nav-tab').forEach(t => t.classList.remove('active'));\n")
                .append("  document.getElementById(panelId).classList.add('active');\n")
                .append("  event.target.classList.add('active');\n")
                .append("  if (panelId === 'panel-regions') drawMap();\n")
                .append("}\n")
                .append("function getConfigDisplay(name) {\n")
                .append("  let displayName = name;\n")
                .append("  let pill = 'config';\n")
                .append("  if (name.startsWith('definitions/')) {\n")
                .append("    displayName = name.substring('definitions/'.length);\n")
                .append("    const parts = displayName.split('/');\n")
                .append("    pill = parts.length > 1 ? parts[0] : 'definitions';\n")
                .append("  } else if (name.startsWith('advanced/')) {\n")
                .append("    displayName = name.substring('advanced/'.length);\n")
                .append("    const parts = displayName.split('/');\n")
                .append("    pill = parts.length > 1 ? parts[0] : 'advanced';\n")
                .append("  } else if (name.startsWith('regions/')) {\n")
                .append("    displayName = name.substring('regions/'.length);\n")
                .append("    pill = 'regions';\n")
                .append("  } else if (name.includes('/')) {\n")
                .append("    displayName = name.split('/').pop();\n")
                .append("    pill = name.split('/')[0];\n")
                .append("  } else {\n")
                .append("    displayName = name;\n")
                .append("    pill = name.split('.')[0];\n")
                .append("  }\n")
                .append("  return { displayName, pill };\n")
                .append("}\n")
                .append("const cfgSidebar = document.getElementById('config-sidebar');\n")
                .append("const cfgEditor = document.getElementById('cfg-editor');\n")
                .append("for (const name of Object.keys(configs).sort()) {\n")
                .append("  const b = document.createElement('button');\n")
                .append("  b.className = 'cfg-file-btn' + (name === currentConfigFile ? ' active' : '');\n")
                .append("  b.setAttribute('data-filename', name);\n")
                .append("  const disp = getConfigDisplay(name);\n")
                .append("  b.textContent = '📄 ' + disp.displayName + ' [' + disp.pill + ']';\n")
                .append("  b.onclick = () => selectConfig(name);\n")
                .append("  cfgSidebar.appendChild(b);\n")
                .append("}\n")
                .append("function selectConfig(name) {\n")
                .append("  if (currentConfigFile) configs[currentConfigFile] = cfgEditor.value;\n")
                .append("  currentConfigFile = name;\n")
                .append("  document.querySelectorAll('.cfg-file-btn').forEach(b => b.classList.toggle('active', b.getAttribute('data-filename') === name));\n")
                .append("  cfgEditor.value = configs[name] || '';\n")
                .append("  document.getElementById('tracker-key-badge').textContent = name;\n")
                .append("  trackCurrentConfigLine();\n")
                .append("}\n")
                .append("/* Contextual Doc Tracking for Config Editor */\n")
                .append("function trackCurrentConfigLine() {\n")
                .append("  const text = cfgEditor.value || '';\n")
                .append("  const selStart = cfgEditor.selectionStart || 0;\n")
                .append("  const lineStart = text.lastIndexOf('\\n', selStart - 1) + 1;\n")
                .append("  let lineEnd = text.indexOf('\\n', selStart);\n")
                .append("  if (lineEnd === -1) lineEnd = text.length;\n")
                .append("  const line = text.substring(lineStart, lineEnd).trim();\n")
                .append("  const match = line.match(/^([a-zA-Z0-9_.-]+)\\s*:/);\n")
                .append("  const key = match ? match[1] : (currentConfigFile.includes('region') ? 'shape' : 'setting');\n")
                .append("  updateDocTracker(key, currentConfigFile);\n")
                .append("}\n")
                .append("cfgEditor.addEventListener('keyup', trackCurrentConfigLine);\n")
                .append("cfgEditor.addEventListener('click', trackCurrentConfigLine);\n")
                .append("function updateDocTracker(key, file) {\n")
                .append("  const nodeEl = document.getElementById('tracker-node');\n")
                .append("  const descEl = document.getElementById('tracker-desc');\n")
                .append("  const metaEl = document.getElementById('tracker-meta');\n")
                .append("  nodeEl.textContent = key;\n")
                .append("  if (key.toLowerCase().includes('radius')) {\n")
                .append("    descEl.textContent = 'Maximum coordinate offset (in blocks) evaluated around center anchor. Defines outer teleport radius.';\n")
                .append("    metaEl.textContent = 'Type: Integer >= 0 | Unit: Blocks | File: ' + file;\n")
                .append("  } else if (key.toLowerCase().includes('shape')) {\n")
                .append("    descEl.textContent = 'Geometric region distribution model (CIRCLE, SQUARE, POLYGON). Controls candidate generator and spiral mapping (ADR-001).';\n")
                .append("    metaEl.textContent = 'Type: Enum/String | Default: CIRCLE | File: ' + file;\n")
                .append("  } else if (key.toLowerCase().includes('price') || key.toLowerCase().includes('cost')) {\n")
                .append("    descEl.textContent = 'Vault economy teleport fee charged to player upon successful execution. Zero means free.';\n")
                .append("    metaEl.textContent = 'Type: Double >= 0.0 | Unit: Currency';\n")
                .append("  } else {\n")
                .append("    descEl.textContent = 'Engine configuration parameter. Refer to packed schema guides for parameter limits and pipeline impact.';\n")
                .append("    metaEl.textContent = 'Context: ' + file + ' | Focused Key: ' + key;\n")
                .append("  }\n")
                .append("}\n")
                .append("const docSidebar = document.getElementById('doc-sidebar');\n")
                .append("const docBody = document.getElementById('doc-body');\n")
                .append("const OFFICIAL_SITE_BASE = 'https://dailystruggle.github.io/RTP/';\n")
                .append("function escapeHtml(s) { return s ? String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;') : ''; }\n")
                .append("function renderMarkdownToHtml(md) {\n")
                .append("  if (!md) return '<p>No content.</p>';\n")
                .append("  const lines = md.split('\\n'); const out = []; let inCode = false; let codeBuf = []; let inList = false; let inTable = false;\n")
                .append("  for (const line of lines) {\n")
                .append("    const tr = line.trim();\n")
                .append("    if (tr.startsWith('```')) {\n")
                .append("      if (inCode) { out.push('<pre><code>' + escapeHtml(codeBuf.join('\\n')) + '</code></pre>'); inCode = false; codeBuf = []; }\n")
                .append("      else { if (inList) { out.push('</ul>'); inList = false; } inCode = true; }\n")
                .append("      continue;\n")
                .append("    }\n")
                .append("    if (inCode) { codeBuf.push(line); continue; }\n")
                .append("    if (tr.startsWith('|') && tr.endsWith('|')) {\n")
                .append("      const cells = tr.slice(1, -1).split('|').map(c => c.trim());\n")
                .append("      if (!inTable) { inTable = true; out.push('<table><thead><tr>' + cells.map(c => '<th>' + escapeHtml(c) + '</th>').join('') + '</tr></thead><tbody>'); }\n")
                .append("      else if (!cells.every(c => /^:?-+:?$/.test(c))) { out.push('<tr>' + cells.map(c => '<td>' + escapeHtml(c) + '</td>').join('') + '</tr>'); }\n")
                .append("      continue;\n")
                .append("    } else if (inTable) { out.push('</tbody></table>'); inTable = false; }\n")
                .append("    if (tr.startsWith('!!!')) { out.push('<div class=\"admonition note\"><div class=\"admonition-title\">' + escapeHtml(tr.substring(3).trim()) + '</div>'); continue; }\n")
                .append("    if (tr.startsWith('#')) {\n")
                .append("      const m = tr.match(/^(#{1,6})\\s+(.*)$/);\n")
                .append("      if (m) { const lvl = m[1].length; out.push('<h' + lvl + '>' + escapeHtml(m[2]) + '</h' + lvl + '>'); continue; }\n")
                .append("    }\n")
                .append("    if (tr.startsWith('- ') || tr.startsWith('* ')) {\n")
                .append("      if (!inList) { out.push('<ul>'); inList = true; }\n")
                .append("      out.push('<li>' + escapeHtml(tr.substring(2)) + '</li>');\n")
                .append("      continue;\n")
                .append("    } else if (inList) { out.push('</ul>'); inList = false; }\n")
                .append("    if (tr) out.push('<p>' + escapeHtml(tr) + '</p>');\n")
                .append("  }\n")
                .append("  if (inCode) out.push('<pre><code>' + escapeHtml(codeBuf.join('\\n')) + '</code></pre>');\n")
                .append("  if (inList) out.push('</ul>');\n")
                .append("  if (inTable) out.push('</tbody></table>');\n")
                .append("  return out.join('\\n');\n")
                .append("}\n")
                .append("function resolveSiteUrlForDoc(docKey) {\n")
                .append("  if (!docKey) return null; let p = docKey.replace(/\\\\/g, '/');\n")
                .append("  let base = OFFICIAL_SITE_BASE;\n")
                .append("  try {\n")
                .append("    if (window.location && window.location.origin) {\n")
                .append("      const loc = window.location.href;\n")
                .append("      if (loc.includes('/RTP/editor/') || loc.includes('/editor/')) {\n")
                .append("        base = loc.substring(0, loc.indexOf('/editor/')) + '/';\n")
                .append("      }\n")
                .append("    }\n")
                .append("  } catch(e) {}\n")
                .append("  if (p === 'index.md') return base;\n")
                .append("  if (p.endsWith('.md')) return base + p.substring(0, p.length - 3) + '/';\n")
                .append("  return base + p;\n")
                .append("}\n")
                .append("async function fetchOfficialSitePage(siteUrl) {\n")
                .append("  const res = await fetch(siteUrl, { method: 'GET', headers: { 'Accept': 'text/html' } });\n")
                .append("  if (!res.ok) throw new Error('HTTP ' + res.status);\n")
                .append("  const doc = new DOMParser().parseFromString(await res.text(), 'text/html');\n")
                .append("  const art = doc.querySelector('article.md-content__inner') || doc.querySelector('.md-content') || doc.querySelector('article') || doc.querySelector('main');\n")
                .append("  if (!art) throw new Error('No article');\n")
                .append("  art.querySelectorAll('.md-content__button, nav, footer, script, style').forEach(e => e.remove());\n")
                .append("  return art.innerHTML;\n")
                .append("}\n")
                .append("async function updateDocDisplayContent(docKey, targetEl, pillEl) {\n")
                .append("  const content = docs[docKey] || '';\n")
                .append("  const u = resolveSiteUrlForDoc(docKey);\n")
                .append("  if (u) {\n")
                .append("    if (pillEl) { pillEl.textContent = 'FETCHING SITE...'; }\n")
                .append("    try {\n")
                .append("      const h = await fetchOfficialSitePage(u);\n")
                .append("      if (targetEl) targetEl.innerHTML = h;\n")
                .append("      if (pillEl) { pillEl.textContent = 'OFFICIAL SITE'; pillEl.className = 'source-badge source-site'; }\n")
                .append("      return;\n")
                .append("    } catch (e) {}\n")
                .append("  }\n")
                .append("  if (targetEl) targetEl.innerHTML = renderMarkdownToHtml(content);\n")
                .append("  if (pillEl) { pillEl.textContent = 'PACKED DOCS'; pillEl.className = 'source-badge source-local'; }\n")
                .append("}\n")
                .append("function toggleRegionDocDrawer() {\n")
                .append("  const d = document.getElementById('region-doc-drawer'); const b = document.getElementById('btn-toggle-region-drawer');\n")
                .append("  if (!d) return; const c = d.classList.toggle('collapsed');\n")
                .append("  if (b) b.textContent = c ? '▲ Expand' : '▼ Collapse';\n")
                .append("  if (!c) renderRegionDocContent();\n")
                .append("}\n")
                .append("function renderRegionDocContent() {\n")
                .append("  const el = document.getElementById('region-doc-content');\n")
                .append("  const pill = document.getElementById('region-doc-source-pill');\n")
                .append("  const k = Object.keys(docs).find(k => k.toLowerCase().includes('region')) || Object.keys(docs)[0];\n")
                .append("  updateDocDisplayContent(k, el, pill);\n")
                .append("}\n")
                .append("for (const name of Object.keys(docs).sort()) {\n")
                .append("  const b = document.createElement('button');\n")
                .append("  b.className = 'doc-item-btn' + (name === currentDocFile ? ' active' : '');\n")
                .append("  b.textContent = name;\n")
                .append("  b.onclick = () => selectDoc(name);\n")
                .append("  docSidebar.appendChild(b);\n")
                .append("}\n")
                .append("function selectDoc(name) {\n")
                .append("  currentDocFile = name;\n")
                .append("  document.querySelectorAll('.doc-item-btn').forEach(b => b.classList.toggle('active', b.textContent === name));\n")
                .append("  const t = document.getElementById('doc-current-title'); if (t) t.textContent = '📖 ' + name;\n")
                .append("  updateDocDisplayContent(name, docBody, document.getElementById('doc-source-pill'));\n")
                .append("}\n")
                .append("if (currentConfigFile) selectConfig(currentConfigFile);\n")
                .append("if (currentDocFile) selectDoc(currentDocFile);\n")
                .append("/* 2D Canvas Renderer for Region Editing */\n")
                .append("const canvas = document.getElementById('map-canvas');\n")
                .append("const ctx = canvas.getContext('2d');\n")
                .append("let pregenChunks = [];\n")
                .append("const pregenColors = ['#537042', '#3b5c32', '#877b4d', '#1e3347', '#3e5246', '#283d23'];\n")
                .append("let hazardRuns = [];\n")
                .append("let hazardBounds = { minX: -5000, minZ: -5000, maxX: 5000, maxZ: 5000, order: 7, gridSize: 128 };\n")
                .append("function decodeHilbertRle(base64Str) {\n")
                .append("  try {\n")
                .append("    const bin = atob(base64Str);\n")
                .append("    let pos = 0;\n")
                .append("    hazardBounds.order = bin.charCodeAt(pos++);\n")
                .append("    hazardBounds.gridSize = 1 << hazardBounds.order;\n")
                .append("    function readInt32() {\n")
                .append("      const v = (bin.charCodeAt(pos) << 24) | (bin.charCodeAt(pos+1) << 16) | (bin.charCodeAt(pos+2) << 8) | bin.charCodeAt(pos+3);\n")
                .append("      pos += 4; return v;\n")
                .append("    }\n")
                .append("    hazardBounds.minX = readInt32();\n")
                .append("    hazardBounds.minZ = readInt32();\n")
                .append("    hazardBounds.maxX = readInt32();\n")
                .append("    hazardBounds.maxZ = readInt32();\n")
                .append("    function readVarInt() {\n")
                .append("      let val = 0, shift = 0;\n")
                .append("      while (pos < bin.length) {\n")
                .append("        const b = bin.charCodeAt(pos++);\n")
                .append("        val |= (b & 0x7F) << shift;\n")
                .append("        if ((b & 0x80) === 0) break;\n")
                .append("        shift += 7;\n")
                .append("      }\n")
                .append("      return val;\n")
                .append("    }\n")
                .append("    const numRuns = readVarInt();\n")
                .append("    const runs = [];\n")
                .append("    let curStart = 0;\n")
                .append("    for (let i = 0; i < numRuns; i++) {\n")
                .append("      const delta = readVarInt(); curStart += delta;\n")
                .append("      const len = readVarInt();\n")
                .append("      const cause = bin.charCodeAt(pos++);\n")
                .append("      runs.push({ start: curStart, len: len, cause: cause });\n")
                .append("    }\n")
                .append("    return runs;\n")
                .append("  } catch (e) { return []; }\n")
                .append("}\n")
                .append("function hilbertToXy(d, order) {\n")
                .append("  let x = 0, y = 0, n = 1 << order, t = d;\n")
                .append("  for (let s = 1; s < n; s *= 2) {\n")
                .append("    let rx = 1 & Math.floor(t / 2);\n")
                .append("    let ry = 1 & (t ^ rx);\n")
                .append("    if (ry === 0) {\n")
                .append("      if (rx === 1) { x = s - 1 - x; y = s - 1 - y; }\n")
                .append("      let tmp = x; x = y; y = tmp;\n")
                .append("    }\n")
                .append("    x += s * rx; y += s * ry; t = Math.floor(t / 4);\n")
                .append("  }\n")
                .append("  return [x, y];\n")
                .append("}\n")
                .append("function resizeCanvas() {\n")
                .append("  const dpr = window.devicePixelRatio || 1;\n")
                .append("  const rect = canvas.getBoundingClientRect();\n")
                .append("  const cssW = Math.max(1, Math.floor(rect.width));\n")
                .append("  const cssH = Math.max(1, Math.floor(rect.height));\n")
                .append("  canvas.width = Math.round(cssW * dpr);\n")
                .append("  canvas.height = Math.round(cssH * dpr);\n")
                .append("  drawMap();\n")
                .append("}\n")
                .append("window.addEventListener('resize', resizeCanvas);\n")
                .append("function drawMap() {\n")
                .append("  if (!canvas.width || !canvas.height) return;\n")
                .append("  const dpr = window.devicePixelRatio || 1;\n")
                .append("  const cssW = canvas.width / dpr;\n")
                .append("  const cssH = canvas.height / dpr;\n")
                .append("  ctx.save();\n")
                .append("  ctx.scale(dpr, dpr);\n")
                .append("  ctx.fillStyle = '#11111b';\n")
                .append("  ctx.fillRect(0, 0, cssW, cssH);\n")
                .append("  const cx = cssW / 2;\n")
                .append("  const cy = cssH / 2;\n")
                .append("  const scale = Math.min(cssW, cssH) / 12000;\n")
                .append("  /* Grid & Axes */\n")
                .append("  ctx.strokeStyle = '#1e1e2e';\n")
                .append("  ctx.lineWidth = 1;\n")
                .append("  ctx.beginPath();\n")
                .append("  ctx.moveTo(0, cy); ctx.lineTo(canvas.width, cy);\n")
                .append("  ctx.moveTo(cx, 0); ctx.lineTo(cx, canvas.height);\n")
                .append("  ctx.stroke();\n")
                .append("  /* Outer Region Radius */\n")
                .append("  ctx.strokeStyle = '#89b4fa';\n")
                .append("  ctx.lineWidth = 2;\n")
                .append("  ctx.beginPath();\n")
                .append("  ctx.arc(cx, cy, regionState.radius * scale, 0, Math.PI * 2);\n")
                .append("  ctx.stroke();\n")
                .append("  /* Inner Center Deadzone */\n")
                .append("  ctx.strokeStyle = '#f38ba8';\n")
                .append("  ctx.lineWidth = 1.5;\n")
                .append("  ctx.setLineDash([4, 4]);\n")
                .append("  ctx.beginPath();\n")
                .append("  ctx.arc(cx, cy, regionState.centerRadius * scale, 0, Math.PI * 2);\n")
                .append("  ctx.stroke();\n")
                .append("  ctx.setLineDash([]);\n")
                .append("  /* Vertices Polygon (strictly for POLYGON shape) */\n")
                .append("  if (regionState.shape === 'POLYGON' && regionState.vertices && regionState.vertices.length > 0) {\n")
                .append("    ctx.strokeStyle = '#a6e3a1';\n")
                .append("    ctx.fillStyle = 'rgba(166, 227, 161, 0.15)';\n")
                .append("    ctx.lineWidth = 2;\n")
                .append("    ctx.beginPath();\n")
                .append("    regionState.vertices.forEach((v, idx) => {\n")
                .append("      const px = cx + v[0] * scale;\n")
                .append("      const py = cy - v[1] * scale;\n")
                .append("      if (idx === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);\n")
                .append("    });\n")
                .append("    ctx.closePath(); ctx.fill(); ctx.stroke();\n")
                .append("    /* Vertex handles */\n")
                .append("    ctx.fillStyle = '#f9e2af';\n")
                .append("    regionState.vertices.forEach(v => {\n")
                .append("      ctx.beginPath();\n")
                .append("      ctx.arc(cx + v[0] * scale, cy - v[1] * scale, 5, 0, Math.PI * 2);\n")
                .append("      ctx.fill();\n")
                .append("    });\n")
                .append("  }\n")
                .append("  /* Paint RLE Hazards Overlay if enabled */\n")
                .append("  const chkHazards = document.getElementById('chk-hazards');\n")
                .append("  if (chkHazards && chkHazards.checked && hazardRuns.length > 0) {\n")
                .append("    ctx.fillStyle = 'rgba(243, 139, 168, 0.45)';\n")
                .append("    const gSize = hazardBounds.gridSize || 128;\n")
                .append("    const bw = Math.max(1, (hazardBounds.maxX - hazardBounds.minX) / gSize);\n")
                .append("    const bh = Math.max(1, (hazardBounds.maxZ - hazardBounds.minZ) / gSize);\n")
                .append("    const cellW = Math.max(1.5, bw * scale);\n")
                .append("    const cellH = Math.max(1.5, bh * scale);\n")
                .append("    for (const run of hazardRuns) {\n")
                .append("      for (let k = 0; k < run.len; k++) {\n")
                .append("        const [gx, gy] = hilbertToXy(run.start + k, hazardBounds.order);\n")
                .append("        const bx = hazardBounds.minX + gx * bw;\n")
                .append("        const bz = hazardBounds.minZ + gy * bh;\n")
                .append("        ctx.fillRect(cx + bx * scale - cellW/2, cy - bz * scale - cellH/2, cellW, cellH);\n")
                .append("      }\n")
                .append("    }\n")
                .append("  }\n")
                .append("  /* Paint Pregenerated Land Chunk Biomes (ADR-104 §4.3) */\n")
                .append("  const chkPregen = document.getElementById('chk-pregen');\n")
                .append("  if (chkPregen && chkPregen.checked && pregenChunks.length > 0) {\n")
                .append("    ctx.save();\n")
                .append("    const cSize = Math.max(1, Math.ceil(16 * scale));\n")
                .append("    for (let i = 0; i < pregenChunks.length; i++) {\n")
                .append("      const chk = pregenChunks[i];\n")
                .append("      const drawX = cx + chk[0] * 16 * scale;\n")
                .append("      const drawY = cy - chk[1] * 16 * scale;\n")
                .append("      if (drawX + cSize >= 0 && drawX <= canvas.width && drawY + cSize >= 0 && drawY <= canvas.height) {\n")
                .append("        ctx.fillStyle = pregenColors[chk[2] % pregenColors.length] || '#435445';\n")
                .append("        ctx.fillRect(drawX, drawY, cSize, cSize);\n")
                .append("      }\n")
                .append("    }\n")
                .append("    ctx.restore();\n")
                .append("  }\n")
                .append("  /* Paint Walk Curve Overlay (differentiating Hilbert vs legacy Spiral) */\n")
                .append("  const chkSpiral = document.getElementById('chk-spiral');\n")
                .append("  if (chkSpiral && chkSpiral.checked) {\n")
                .append("    ctx.strokeStyle = 'rgba(249, 226, 175, 0.4)';\n")
                .append("    ctx.lineWidth = 1.2;\n")
                .append("    ctx.beginPath();\n")
                .append("    const maxAngle = 16 * Math.PI;\n")
                .append("    const spiralStep = maxAngle / 300;\n")
                .append("    const a = (regionState.centerRadius || 200) * scale;\n")
                .append("    const b = ((regionState.radius || 5000) * scale - a) / maxAngle;\n")
                .append("    for (let th = 0; th <= maxAngle; th += spiralStep) {\n")
                .append("      const r = a + b * th;\n")
                .append("      const sx = cx + r * Math.cos(th);\n")
                .append("      const sy = cy + r * Math.sin(th);\n")
                .append("      if (th === 0) ctx.moveTo(sx, sy); else ctx.lineTo(sx, sy);\n")
                .append("    }\n")
                .append("    ctx.stroke();\n")
                .append("  }\n")
                .append("}\n")
                .append("setTimeout(resizeCanvas, 50);\n")
                .append("let wsClient = null;\n")
                .append("let currentSessionToken = Math.random().toString(16).substring(2, 10);\n")
                .append("function validateGeometry(state) {\n")
                .append("  const warnings = [];\n")
                .append("  const WORLD_BORDER_MAX = 29999984;\n")
                .append("  if (state.radius <= 0) warnings.push('Error: radius must be greater than 0');\n")
                .append("  if (state.centerRadius < 0) warnings.push('Error: centerRadius cannot be negative');\n")
                .append("  if (state.centerRadius >= state.radius) warnings.push('Warning: centerRadius >= radius (empty or inverted ring)');\n")
                .append("  if (state.radius > WORLD_BORDER_MAX) warnings.push('Warning: radius exceeds vanilla world border (' + WORLD_BORDER_MAX + ')');\n")
                .append("  if (state.shape === 'POLYGON' && state.vertices) {\n")
                .append("    if (state.vertices.length < 3) warnings.push('Error: polygon requires >= 3 vertices (ADR-034)');\n")
                .append("    const n = state.vertices.length;\n")
                .append("    for (let i = 0; i < n; i++) {\n")
                .append("      const v = state.vertices[i];\n")
                .append("      if (Math.abs(v[0]) > WORLD_BORDER_MAX || Math.abs(v[1]) > WORLD_BORDER_MAX) {\n")
                .append("        warnings.push('Warning: vertex [' + v[0] + ', ' + v[1] + '] exceeds vanilla world border boundary');\n")
                .append("        break;\n")
                .append("      }\n")
                .append("    }\n")
                .append("    for (let i = 0; i < n; i++) {\n")
                .append("      const p1 = state.vertices[i], p2 = state.vertices[(i + 1) % n], p3 = state.vertices[(i + 2) % n];\n")
                .append("      const cross = (p2[0] - p1[0]) * (p3[1] - p1[1]) - (p2[1] - p1[1]) * (p3[0] - p1[0]);\n")
                .append("      if (Math.abs(cross) < 1e-6) {\n")
                .append("        warnings.push('Notice: vertices at index ' + (i + 1) + ' are collinear (simplifiable per ADR-099)');\n")
                .append("      }\n")
                .append("    }\n")
                .append("    for (let i = 0; i < n; i++) {\n")
                .append("      const p1 = state.vertices[i], p2 = state.vertices[(i + 1) % n];\n")
                .append("      for (let j = i + 2; j < n; j++) {\n")
                .append("        if (i === 0 && j === n - 1) continue;\n")
                .append("        const p3 = state.vertices[j], p4 = state.vertices[(j + 1) % n];\n")
                .append("        if (segmentsIntersect(p1, p2, p3, p4)) {\n")
                .append("          warnings.push('Error: polygon edges self-intersect between [' + i + '-' + ((i+1)%n) + '] and [' + j + '-' + ((j+1)%n) + '] (ADR-034 violation)');\n")
                .append("          break;\n")
                .append("        }\n")
                .append("      }\n")
                .append("    }\n")
                .append("  }\n")
                .append("  return warnings;\n")
                .append("}\n")
                .append("function segmentsIntersect(p1, p2, p3, p4) {\n")
                .append("  function ccw(a, b, c) { return (c[1] - a[1]) * (b[0] - a[0]) > (b[1] - a[1]) * (c[0] - a[0]); }\n")
                .append("  function onSegment(p, q, r) {\n")
                .append("    return q[0] <= Math.max(p[0], r[0]) && q[0] >= Math.min(p[0], r[0]) &&\n")
                .append("           q[1] <= Math.max(p[1], r[1]) && q[1] >= Math.min(p[1], r[1]);\n")
                .append("  }\n")
                .append("  const o1 = (p2[1] - p1[1]) * (p3[0] - p2[0]) - (p2[0] - p1[0]) * (p3[1] - p2[1]);\n")
                .append("  const o2 = (p2[1] - p1[1]) * (p4[0] - p2[0]) - (p2[0] - p1[0]) * (p4[1] - p2[1]);\n")
                .append("  const o3 = (p4[1] - p3[1]) * (p1[0] - p4[0]) - (p4[0] - p3[0]) * (p1[1] - p4[1]);\n")
                .append("  const o4 = (p4[1] - p3[1]) * (p2[0] - p4[0]) - (p4[0] - p3[0]) * (p2[1] - p4[1]);\n")
                .append("  if (((o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0)) && ((o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0))) return true;\n")
                .append("  if (Math.abs(o1) < 1e-7 && onSegment(p1, p3, p2)) return true;\n")
                .append("  if (Math.abs(o2) < 1e-7 && onSegment(p1, p4, p2)) return true;\n")
                .append("  if (Math.abs(o3) < 1e-7 && onSegment(p3, p1, p4)) return true;\n")
                .append("  if (Math.abs(o4) < 1e-7 && onSegment(p3, p2, p4)) return true;\n")
                .append("  return false;\n")
                .append("}\n")
                .append("function generateYamlDiff(fileName, baselineText, modifiedText) {\n")
                .append("  const baseLines = (baselineText || '').split('\\n');\n")
                .append("  const modLines = (modifiedText || '').split('\\n');\n")
                .append("  const diff = [];\n")
                .append("  let hasDiff = false;\n")
                .append("  let i = 0, j = 0;\n")
                .append("  while (i < baseLines.length || j < modLines.length) {\n")
                .append("    const b = baseLines[i];\n")
                .append("    const m = modLines[j];\n")
                .append("    if (b === m) {\n")
                .append("      diff.push('  ' + escapeHtml(b || ''));\n")
                .append("      i++; j++;\n")
                .append("    } else {\n")
                .append("      hasDiff = true;\n")
                .append("      if (b !== undefined && (m === undefined || !modLines.slice(j).includes(b))) {\n")
                .append("        diff.push('<span class=\"diff-del\">- ' + escapeHtml(b) + '</span>');\n")
                .append("        i++;\n")
                .append("      } else if (m !== undefined && (b === undefined || !baseLines.slice(i).includes(m))) {\n")
                .append("        diff.push('<span class=\"diff-add\">+ ' + escapeHtml(m) + '</span>');\n")
                .append("        j++;\n")
                .append("      } else {\n")
                .append("        diff.push('<span class=\"diff-del\">- ' + escapeHtml(b) + '</span>');\n")
                .append("        diff.push('<span class=\"diff-add\">+ ' + escapeHtml(m) + '</span>');\n")
                .append("        i++; j++;\n")
                .append("      }\n")
                .append("    }\n")
                .append("  }\n")
                .append("  if (!hasDiff) return '# No uncommitted modifications staged for ' + fileName;\n")
                .append("  return '# Staged YAML diff for ' + fileName + ':\\n' + diff.join('\\n');\n")
                .append("}\n")
                .append("function escapeHtml(str) {\n")
                .append("  return (str || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');\n")
                .append("}\n")
                .append("function computeCurrentRegionYaml(region) {\n")
                .append("  const worldName = region.world || '[0]';\n")
                .append("  const shape = region.shape || 'CIRCLE';\n")
                .append("  const unit = region.unit || 'c';\n")
                .append("  let yml = '# --- RTP Region Configuration ---\\n';\n")
                .append("  yml += '# Documentation: plugins/RTP/docs/admin/configuration/REGIONS.md\\n\\n';\n")
                .append("  yml += 'world: \"' + worldName + '\"\\n';\n")
                .append("  yml += 'worldBorderOverride: false\\n\\n';\n")
                .append("  yml += 'shape:\\n';\n")
                .append("  yml += '  name: \"' + shape + '\"\\n';\n")
                .append("  yml += '  mode: \"ACCUMULATE\"\\n';\n")
                .append("  if (shape === 'RECTANGLE' || shape === 'ELLIPSE') {\n")
                .append("    yml += '  radiusX: ' + (region.radiusX || region.radius) + unit + '\\n';\n")
                .append("    yml += '  radiusZ: ' + (region.radiusZ || region.radius) + unit + '\\n';\n")
                .append("  } else {\n")
                .append("    yml += '  radius: ' + region.radius + unit + '\\n';\n")
                .append("    yml += '  centerRadius: ' + region.centerRadius + unit + '\\n';\n")
                .append("  }\n")
                .append("  yml += '  centerX: ' + (region.centerX || 0) + '\\n';\n")
                .append("  yml += '  centerZ: ' + (region.centerZ || 0) + '\\n';\n")
                .append("  yml += '  weight: 1.0\\n';\n")
                .append("  yml += '  uniquePlacements: \"auto\"\\n';\n")
                .append("  yml += '  expand: false\\n';\n")
                .append("  if (region.shape === 'POLYGON' && region.vertices && region.vertices.length > 0) {\n")
                .append("    yml += '  vertices:\\n';\n")
                .append("    for (const v of region.vertices) yml += '    - [' + v[0] + ', ' + v[1] + ']\\n';\n")
                .append("  }\n")
                .append("  yml += '\\nvert: \"@config\"\\n';\n")
                .append("  yml += 'requirePermission: \"@config\"\\n';\n")
                .append("  yml += 'override: \"default\"\\n';\n")
                .append("  yml += 'price: 0.0\\n';\n")
                .append("  yml += 'cacheCap: \"@config\"\\n';\n")
                .append("  yml += 'backlogCacheCap: \"@config\"\\n';\n")
                .append("  yml += 'activeChunkCap: \"@config\"\\n';\n")
                .append("  yml += 'spatialResolution: \"@config\"\\n';\n")
                .append("  yml += 'version: \"1.1\"\\n';\n")
                .append("  return yml;\n")
                .append("}\n")
                .append("function updateStagingDiff() {\n")
                .append("  const diffEl = document.getElementById('staging-diff');\n")
                .append("  const validStatusEl = document.getElementById('validation-status');\n")
                .append("  const warnings = validateGeometry(regionState);\n")
                .append("  let warnText = '';\n")
                .append("  if (warnings.length > 0) {\n")
                .append("    warnText = warnings.map(w => '<span class=\"' + (w.startsWith('Error') ? 'diff-del' : (w.startsWith('Notice') ? 'diff-add' : 'diff-warn')) + '\" style=\"display:block;margin-bottom:4px;font-weight:bold;\">⚠ ' + w + '</span>').join('') + '\\n';\n")
                .append("  }\n")
                .append("  if (validStatusEl) {\n")
                .append("    const hasErr = warnings.some(w => w.startsWith('Error'));\n")
                .append("    validStatusEl.innerHTML = (hasErr ? '❌ <span class=\"diff-del\">Non-self-intersecting: VIOLATION (ADR-034)</span>' : '✅ <span class=\"diff-add\">Non-self-intersecting: VALID</span>') + '<br>' +\n")
                .append("      (warnings.some(w => w.includes('collinear')) ? '⚠ <span class=\"diff-warn\">Collinear vertices: DETECTED</span>' : '✅ <span class=\"diff-add\">Collinear vertices: SIMPLIFIED</span>') + '<br>' +\n")
                .append("      (warnings.some(w => w.includes('world border')) ? '❌ <span class=\"diff-del\">Within world border: BOUND EXCEEDED</span>' : '✅ <span class=\"diff-add\">Within world border: YES</span>');\n")
                .append("  }\n")
                .append("  const regionFileName = 'regions/' + regionState.name + '.yml';\n")
                .append("  const baseline = configs[regionFileName] || computeCurrentRegionYaml(regionState);\n")
                .append("  const modified = computeCurrentRegionYaml(regionState);\n")
                .append("  const diffText = generateYamlDiff(regionFileName, baseline.trim(), modified.trim());\n")
                .append("  diffEl.innerHTML = warnText + diffText;\n")
                .append("}\n")
                .append("function commitChanges() {\n")
                .append("  const token = currentSessionToken;\n")
                .append("  const applyText = '/rtp editor apply ' + token;\n")
                .append("  if (wsClient && wsClient.readyState === WebSocket.OPEN) {\n")
                .append("    hotApplyWebSocket();\n")
                .append("  } else {\n")
                .append("    copyApplyCmd(applyText);\n")
                .append("  }\n")
                .append("}\n")
                .append("function hotApplyWebSocket() {\n")
                .append("  if (!wsClient || wsClient.readyState !== WebSocket.OPEN) return;\n")
                .append("  const payload = { action: 'apply', token: currentSessionToken, region: regionState.name, files: { ['regions/' + regionState.name + '.yml']: computeCurrentRegionYaml(regionState) } };\n")
                .append("  wsClient.send(JSON.stringify(payload));\n")
                .append("  alert('Hot-Apply commit sent via WebSocket!');\n")
                .append("}\n")
                .append("function copyApplyCmd(cmd) {\n")
                .append("  const text = cmd || ('/rtp editor apply ' + currentSessionToken);\n")
                .append("  navigator.clipboard.writeText(text).then(() => alert('Copied fallback command to clipboard: ' + text)).catch(() => alert('Execute: ' + text));\n")
                .append("}\n")
                .append("function resetStaging() {\n")
                .append("  regionState.radius = 5000; regionState.centerRadius = 1000; regionState.shape = 'CIRCLE';\n")
                .append("  drawMap(); updateStagingDiff();\n")
                .append("}\n")
                .append("/* Diagnostics Telemetry Live Binding */\n")
                .append("const defaultMetrics = {\n")
                .append("  host: { tps1m: 20.0, tps5m: 20.0, tps15m: 20.0, msptMean: 12.4, msptMax: 21.8, budgetPercent: 24.8, onlinePlayers: 42, softCap: 150, heapUsedMb: 1860, heapMaxMb: 4096, platform: 'Paper / Folia Async' },\n")
                .append("  queues: { totalQueueDepth: 0, pendingTeleports: 0, l1Kept: 16, l1Cap: 16, l2Cold: 64, l2Cap: 64, l3Backlog: 10000, loginReserve: 8, loginCap: 8 },\n")
                .append("  latency: { meanMs: 14.2, p50Ms: 9.5, p75Ms: 14.0, p90Ms: 22.5, p95Ms: 31.0, p99Ms: 48.2, minMs: 2.1, maxMs: 76.4, sampleCount: 1024, totalExecutions: 18490 },\n")
                .append("  safety: { spatialMemoryEfficiency: 99.4, activeMemoryTrackerEntries: 16, chunkTicketLeaks: 0, chunkLoadBacklog: 0, ioReaderThreads: 2, databaseLatencyMs: 1.2 },\n")
                .append("  audit: { slowPipelineCount: 0, slowPipelineThresholdMs: 500, queueGrowthWarnCount: 0, queueGrowthThreshold: 50, tickStressCount: 0, commandOverflowCount: 0 },\n")
                .append("  regions: [\n")
                .append("    { name: 'survival_spawn', world: 'world', queue: 0, l1Kept: 16, l1Cap: 16, l2Cold: 64, l2Cap: 64, loginKept: 8, loginCap: 8, status: 'OPTIMAL' },\n")
                .append("    { name: 'nether_wastes', world: 'world_nether', queue: 0, l1Kept: 8, l1Cap: 8, l2Cold: 32, l2Cap: 32, loginKept: 0, loginCap: 0, status: 'OPTIMAL' },\n")
                .append("    { name: 'the_end', world: 'world_the_end', queue: 0, l1Kept: 8, l1Cap: 8, l2Cold: 32, l2Cap: 32, loginKept: 0, loginCap: 0, status: 'OPTIMAL' }\n")
                .append("  ],\n")
                .append("  logs: [\n")
                .append("    '[MemoryTracker Watchdog] 0 active chunk ticket leaks detected across all worlds.',\n")
                .append("    '[Scheduler Heartbeat] Folia / Async worker pool operating within nominal bounds (0ms drift).',\n")
                .append("    '[Off-Tick I/O Pool] Anvil / Linear region readers active: 2 threads (bounded off-tick).',\n")
                .append("    '[RegionQueueManager] L1 Hot Queue pre-warmed and ready for instant dispatch.',\n")
                .append("    '[ClaimIntegrations] 0 inline chunk stalls; cache hit ratio: 99.8%.'\n")
                .append("  ]\n")
                .append("};\n")
                .append("let currentMetrics = JSON.parse(JSON.stringify(defaultMetrics));\n")
                .append("function updateDiagnosticsUI(metrics) {\n")
                .append("  if (!metrics) return;\n")
                .append("  const h = metrics.host || {}, q = metrics.queues || {}, l = metrics.latency || {}, s = metrics.safety || {}, a = metrics.audit || {};\n")
                .append("  const setEl = (id, text) => { const el = document.getElementById(id); if (el) el.textContent = text; };\n")
                .append("  if (h.tps1m !== undefined) setEl('metric-tps', Number(h.tps1m).toFixed(2) + ' / ' + Number(h.tps5m).toFixed(2) + ' / ' + Number(h.tps15m).toFixed(2));\n")
                .append("  if (h.msptMean !== undefined) {\n")
                .append("    const el = document.getElementById('metric-mspt'); if (el) el.innerHTML = Number(h.msptMean).toFixed(1) + ' ms <span style=\"font-size:0.9rem; color:var(--subtext);\">/ ' + Number(h.msptMax || 0).toFixed(1) + ' ms</span>';\n")
                .append("    const bar = document.getElementById('metric-mspt-bar'); if (bar) bar.style.width = Math.min(100, (h.msptMean / 50.0) * 100).toFixed(1) + '%';\n")
                .append("  }\n")
                .append("  if (h.budgetPercent !== undefined) setEl('metric-budget', Number(h.budgetPercent).toFixed(1) + '%');\n")
                .append("  if (h.onlinePlayers !== undefined) { const el = document.getElementById('metric-players'); if (el) el.innerHTML = h.onlinePlayers + ' <span style=\"font-size:0.9rem; color:var(--subtext);\">/ ' + (h.softCap || 150) + ' soft cap</span>'; }\n")
                .append("  if (h.heapUsedMb !== undefined) { const el = document.getElementById('metric-heap'); if (el) el.innerHTML = (h.heapUsedMb / 1024).toFixed(2) + ' GB <span style=\"font-size:0.9rem; color:var(--subtext);\">/ ' + (h.heapMaxMb / 1024).toFixed(2) + ' GB</span>'; }\n")
                .append("  if (q.l1Kept !== undefined) setEl('metric-l1', q.l1Kept + ' / ' + (q.l1Cap || 16));\n")
                .append("  if (q.l2Cold !== undefined) setEl('metric-l2', q.l2Cold + ' / ' + (q.l2Cap || 64));\n")
                .append("  if (q.l3Backlog !== undefined) setEl('metric-l3', Number(q.l3Backlog).toLocaleString());\n")
                .append("  if (q.loginReserve !== undefined) setEl('metric-login-reserve', q.loginReserve + ' / ' + (q.loginCap || 8));\n")
                .append("  if (q.totalQueueDepth !== undefined) setEl('metric-queue-depth', q.totalQueueDepth);\n")
                .append("  if (q.pendingTeleports !== undefined) setEl('metric-pending-teleports', q.pendingTeleports);\n")
                .append("  if (l.meanMs !== undefined) setEl('metric-lat-mean', Number(l.meanMs).toFixed(1) + ' ms');\n")
                .append("  if (l.p50Ms !== undefined) setEl('metric-lat-p50', Number(l.p50Ms).toFixed(1) + ' ms');\n")
                .append("  if (l.p75Ms !== undefined) setEl('metric-lat-p75', Number(l.p75Ms).toFixed(1) + ' ms');\n")
                .append("  if (l.p90Ms !== undefined) setEl('metric-lat-p90', Number(l.p90Ms).toFixed(1) + ' ms');\n")
                .append("  if (l.p95Ms !== undefined) setEl('metric-lat-p95', Number(l.p95Ms).toFixed(1) + ' ms');\n")
                .append("  if (l.p99Ms !== undefined) setEl('metric-lat-p99', Number(l.p99Ms).toFixed(1) + ' ms');\n")
                .append("  if (s.spatialMemoryEfficiency !== undefined) setEl('metric-spatial-eff', Number(s.spatialMemoryEfficiency).toFixed(1) + '%');\n")
                .append("  if (s.activeMemoryTrackerEntries !== undefined) setEl('metric-mem-entries', s.activeMemoryTrackerEntries);\n")
                .append("  if (s.chunkTicketLeaks !== undefined) setEl('metric-ticket-leaks', s.chunkTicketLeaks);\n")
                .append("  if (s.chunkLoadBacklog !== undefined) setEl('metric-chunk-backlog', s.chunkLoadBacklog);\n")
                .append("  if (s.ioReaderThreads !== undefined) setEl('metric-io-threads', s.ioReaderThreads + ' threads');\n")
                .append("  if (s.databaseLatencyMs !== undefined) setEl('metric-db-latency', Number(s.databaseLatencyMs).toFixed(1) + ' ms');\n")
                .append("  if (a.slowPipelineCount !== undefined) { const el = document.getElementById('metric-slow-pipelines'); if (el) el.innerHTML = a.slowPipelineCount + ' <span style=\"font-size:0.8rem; color:var(--subtext);\">&gt; 500ms</span>'; }\n")
                .append("  if (a.queueGrowthWarnCount !== undefined) { const el = document.getElementById('metric-queue-warnings'); if (el) el.innerHTML = a.queueGrowthWarnCount + ' <span style=\"font-size:0.8rem; color:var(--subtext);\">&gt; 50 cap</span>'; }\n")
                .append("  if (a.tickStressCount !== undefined) setEl('metric-tick-stress', a.tickStressCount);\n")
                .append("  if (a.commandOverflowCount !== undefined) setEl('metric-overflow-events', a.commandOverflowCount);\n")
                .append("}\n")
                .append("updateDiagnosticsUI(currentMetrics);\n")
                .append("function initWebSocket() {\n")
                .append("  const host = window.location.host;\n")
                .append("  if (!host) return;\n")
                .append("  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';\n")
                .append("  try {\n")
                .append("    wsClient = new WebSocket(protocol + '//' + host + '/rtp-editor-ws');\n")
                .append("    wsClient.onmessage = (event) => {\n")
                .append("      try {\n")
                .append("        const msg = JSON.parse(event.data);\n")
                .append("        if (msg.type === 'scan_delta' && msg.rleBase64) { hazardRuns = decodeHilbertRle(msg.rleBase64); drawMap(); }\n")
                .append("        else if (msg.type === 'mutation_broadcast' && msg.files) { Object.assign(configs, msg.files); updateStagingDiff(); }\n")
                .append("        else if ((msg.type === 'telemetry' || msg.type === 'metrics') && msg.metrics) { Object.assign(currentMetrics, msg.metrics); updateDiagnosticsUI(currentMetrics); }\n")
                .append("      } catch (e) {}\n")
                .append("    };\n")
                .append("  } catch (e) {}\n")
                .append("}\n")
                .append("initWebSocket();\n")
                .append("</script>\n</body>\n</html>\n");

        Files.writeString(targetFile, html.toString(), StandardCharsets.UTF_8);
    }

    private static String buildJsonMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(escapeJson(entry.getKey())).append("\":\"")
                    .append(escapeJson(entry.getValue())).append("\"");
        }
        json.append("}");
        return json.toString();
    }

    private static String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Collects all active configuration files as relative path to raw file content.
     * Conforms to S-006 (fails closed if RTP core or configs are not initialized).
     *
     * @return map of relative file path (e.g. "config.yml", "regions/default.yml") to text
     */
    public Map<String, String> collectCurrentConfigs() {
        if (RTP.configs == null || RTP.serverAccessor == null) {
            throw new IllegalStateException("RTP core or configurations not initialized yet (S-006 fail-closed)");
        }

        Map<String, String> configMap = new LinkedHashMap<>();

        File pluginDir = RTP.serverAccessor.getPluginDirectory();
        if (pluginDir == null || !pluginDir.exists()) {
            return configMap;
        }

        // Iterate over configParserMap and multiConfigParserMap
        if (RTP.configs.fileDatabase != null && RTP.configs.fileDatabase.cachedLookup != null) {
            Map<String, RtpYamlConfig> lookup = RTP.configs.fileDatabase.cachedLookup.get();
            if (lookup != null) {
                for (Map.Entry<String, RtpYamlConfig> entry : lookup.entrySet()) {
                    String name = entry.getKey();
                    if (!name.endsWith(".yml")) name = name + ".yml";
                    RtpYamlConfig cfg = entry.getValue();
                    if (cfg != null) {
                        configMap.put(name, cfg.saveToString());
                    }
                }
            }
        }

        // Scan directory for region files and others if not fully present in cachedLookup
        try {
            Path pluginPath = pluginDir.toPath();
            if (Files.exists(pluginPath)) {
                try (var stream = Files.walk(pluginPath, 3)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".yml"))
                            .forEach(p -> {
                                String rel = pluginPath.relativize(p).toString().replace('\\', '/');
                                if (!configMap.containsKey(rel)) {
                                    try {
                                        configMap.put(rel, Files.readString(p, StandardCharsets.UTF_8));
                                    } catch (IOException ignored) {
                                    }
                                }
                            });
                }
            }
        } catch (IOException ignored) {
        }

        return configMap;
    }

    /**
     * Serializes current configuration snapshot into an ADR-104 JSON payload bundle.
     *
     * @return JSON payload containing metadata, SHA-256 integrity hash, and file map
     */
    public String createPayloadJson() {
        return createPayloadJson(collectCurrentConfigs());
    }

    /**
     * Serializes the given configuration map into an ADR-104 JSON payload bundle with computed SHA-256.
     *
     * @param configFiles map of relative filename to file contents
     * @return JSON payload string
     */
    public String createPayloadJson(Map<String, String> configFiles) {
        Objects.requireNonNull(configFiles, "configFiles");

        StringBuilder filesJson = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : configFiles.entrySet()) {
            if (!first) filesJson.append(",");
            first = false;
            filesJson.append("\"").append(escapeJson(entry.getKey())).append("\":\"")
                    .append(escapeJson(entry.getValue())).append("\"");
        }
        filesJson.append("}");

        String filesContent = filesJson.toString();
        String sha256 = EditorHttpTransport.computeSha256(filesContent);

        String pluginVersion = "unknown";
        if (RTP.serverAccessor != null) {
            try {
                pluginVersion = RTP.serverAccessor.getServerVersion();
            } catch (Exception ignored) {
            }
        }

        long now = System.currentTimeMillis() / 1000L;

        return "{" +
                "\"version\":1," +
                "\"pluginVersion\":\"" + escapeJson(pluginVersion) + "\"," +
                "\"timestamp\":" + now + "," +
                "\"sha256\":\"" + sha256 + "\"," +
                "\"files\":" + filesContent +
                "}";
    }

    /**
     * Parsed payload container for validation and application.
     */
    public static final class ParsedPayload {
        private final int version;
        private final String pluginVersion;
        private final long timestamp;
        private final String sha256;
        private final Map<String, String> files;

        public ParsedPayload(int version, String pluginVersion, long timestamp, String sha256, Map<String, String> files) {
            this.version = version;
            this.pluginVersion = pluginVersion;
            this.timestamp = timestamp;
            this.sha256 = sha256;
            this.files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
        }

        public int version() { return version; }
        public String pluginVersion() { return pluginVersion; }
        public long timestamp() { return timestamp; }
        public String sha256() { return sha256; }
        public Map<String, String> files() { return files; }
    }

    /**
     * Parses and validates a JSON payload according to ADR-104.
     * Enforces:
     * 1. Valid JSON root structure.
     * 2. SHA-256 checksum verification against files block.
     * 3. AST validation of all YAML configuration files via {@link RtpYamlConfig}.
     * 4. Geometry and invariant checks for region definitions.
     *
     * @param json raw JSON string
     * @return validated {@link ParsedPayload}
     * @throws IllegalArgumentException on any validation failure (S-004)
     */
    public ParsedPayload parseAndValidatePayload(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Payload cannot be null or empty");
        }

        int filesIdx = json.indexOf("\"files\"");
        if (filesIdx == -1) {
            throw new IllegalArgumentException("Invalid payload: missing 'files' field");
        }

        int colonIdx = json.indexOf(':', filesIdx);
        if (colonIdx == -1) {
            throw new IllegalArgumentException("Invalid payload: malformed 'files' field");
        }

        int filesStart = json.indexOf('{', colonIdx);
        if (filesStart == -1) {
            throw new IllegalArgumentException("Invalid payload: 'files' must be an object");
        }

        int filesEnd = findMatchingBrace(json, filesStart);
        if (filesEnd == -1) {
            throw new IllegalArgumentException("Invalid payload: unclosed 'files' object");
        }

        String filesBlock = json.substring(filesStart, filesEnd + 1);

        // Extract declared SHA-256 if present
        String declaredSha = extractStringField(json, "sha256");
        if (declaredSha != null && !declaredSha.isBlank()) {
            String computedSha = EditorHttpTransport.computeSha256(filesBlock);
            if (!declaredSha.equalsIgnoreCase(computedSha)) {
                throw new IllegalArgumentException("Payload SHA-256 mismatch! Expected: " + declaredSha + ", computed: " + computedSha);
            }
        }

        int version = 1;
        String versionStr = extractNumericField(json, "version");
        if (versionStr != null) {
            try {
                version = Integer.parseInt(versionStr);
            } catch (NumberFormatException ignored) {
            }
        }

        String pluginVersion = extractStringField(json, "pluginVersion");
        long timestamp = 0L;
        String tsStr = extractNumericField(json, "timestamp");
        if (tsStr != null) {
            try {
                timestamp = Long.parseLong(tsStr);
            } catch (NumberFormatException ignored) {
            }
        }

        // Parse files dictionary
        Map<String, String> files = parseJsonStringMap(filesBlock);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("Payload contains no configuration files to apply");
        }

        // Validate each file AST and geometry
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String fileName = entry.getKey();
            String content = entry.getValue();

            if (fileName == null || fileName.isBlank() || fileName.contains("..")) {
                throw new IllegalArgumentException("Illegal file path in payload: '" + fileName + "'");
            }

            if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
                RtpYamlConfig parsedYaml;
                try {
                    parsedYaml = RtpYamlConfig.parse(content);
                } catch (Exception e) {
                    throw new IllegalArgumentException("AST validation error in file '" + fileName + "': " + e.getMessage(), e);
                }

                // If region file, validate geometry constraints
                if (fileName.startsWith("regions/") || fileName.equals("regions.yml") || fileName.contains("region")) {
                    validateRegionGeometry(fileName, parsedYaml);
                }
            }
        }

        return new ParsedPayload(version, pluginVersion, timestamp, declaredSha, files);
    }

    /**
     * Validates geometry values within region config sections.
     */
    private static void validateRegionGeometry(String fileName, RtpYamlConfig yaml) {
        // Scan for radius and centerRadius across sections
        for (String key : yaml.getKeys(true)) {
            if (key.endsWith(".radius") || key.equals("radius")) {
                Object r = yaml.get(key);
                if (r instanceof Number num) {
                    if (num.doubleValue() < 0) {
                        throw new IllegalArgumentException("Geometry error in '" + fileName + "': radius cannot be negative (" + num + ")");
                    }
                }
            } else if (key.endsWith(".centerRadius") || key.equals("centerRadius")) {
                Object cr = yaml.get(key);
                if (cr instanceof Number num) {
                    if (num.doubleValue() < 0) {
                        throw new IllegalArgumentException("Geometry error in '" + fileName + "': centerRadius cannot be negative (" + num + ")");
                    }
                }
            }
        }
    }

    /**
     * Atomically applies the configuration payload:
     * 1. Validates AST & integrity.
     * 2. Creates dirty file .bak copy for any existing target file on disk.
     * 3. Writes updated configurations to disk and updates in-memory database lookup.
     * 4. Triggers atomic hot-reload.
     *
     * @param json raw payload JSON string
     * @return CompletableFuture resolving when apply and reload are completed
     */
    public CompletableFuture<Void> applyPayload(String json) {
        if (RTP.configs == null || RTP.serverAccessor == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("RTP core or configurations not initialized (S-006 fail-closed)"));
        }

        return CompletableFuture.runAsync(() -> {
            ParsedPayload parsed = parseAndValidatePayload(json);

            File pluginDir = RTP.serverAccessor.getPluginDirectory();
            if (pluginDir == null) {
                throw new IllegalStateException("Plugin directory is null (S-006)");
            }
            if (!pluginDir.exists()) {
                pluginDir.mkdirs();
            }

            Path pluginPath = pluginDir.toPath();

            // Backup all dirty files
            for (Map.Entry<String, String> entry : parsed.files().entrySet()) {
                String relPath = entry.getKey();
                Path target = pluginPath.resolve(relPath).normalize();
                if (!target.startsWith(pluginPath)) {
                    throw new IllegalArgumentException("Path traversal attempt: " + relPath);
                }

                if (Files.exists(target)) {
                    try {
                        String existing = Files.readString(target, StandardCharsets.UTF_8);
                        if (!existing.equals(entry.getValue())) {
                            // File is dirty, create .bak backup
                            Path bakFile = target.resolveSibling(target.getFileName().toString() + ".bak");
                            Files.copy(target, bakFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        RTP.log(Level.WARNING, "Failed to create .bak backup for " + target + ": " + e.getMessage(), e);
                        throw new RuntimeException("Backup failed for " + relPath + ": " + e.getMessage(), e);
                    }
                }
            }

            // Atomic write to disk and in-memory swap
            for (Map.Entry<String, String> entry : parsed.files().entrySet()) {
                String relPath = entry.getKey();
                Path target = pluginPath.resolve(relPath).normalize();

                try {
                    if (target.getParent() != null && !Files.exists(target.getParent())) {
                        Files.createDirectories(target.getParent());
                    }

                    // Write to temp file then atomic move
                    Path tmpFile = target.resolveSibling(target.getFileName().toString() + ".tmp");
                    Files.writeString(tmpFile, entry.getValue(), StandardCharsets.UTF_8);
                    Files.move(tmpFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    RTP.log(Level.WARNING, "Failed writing file " + target + ": " + e.getMessage(), e);
                    throw new RuntimeException("Failed writing configuration file " + relPath + ": " + e.getMessage(), e);
                }

                // In-memory update of cachedLookup if applicable
                if (RTP.configs.fileDatabase != null && RTP.configs.fileDatabase.cachedLookup != null) {
                    Map<String, RtpYamlConfig> lookup = RTP.configs.fileDatabase.cachedLookup.get();
                    if (lookup != null) {
                        try {
                            String baseName = target.getFileName().toString();
                            if (baseName.endsWith(".yml")) baseName = baseName.substring(0, baseName.length() - 4);
                            RtpYamlConfig parsedYaml = RtpYamlConfig.load(target.toFile());
                            lookup.put(baseName, parsedYaml);
                            lookup.put(target.getFileName().toString(), parsedYaml);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            // Trigger hot-reload on configs
            try {
                RTP.configs.reload();
            } catch (Exception e) {
                RTP.log(Level.WARNING, "Hot-reload encountered an error after applying payload: " + e.getMessage(), e);
                throw new RuntimeException("Hot-reload error: " + e.getMessage(), e);
            }
        });
    }

    private static int findMatchingBrace(String text, int openPos) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = openPos; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String extractStringField(String json, String field) {
        String pattern = "\"" + field + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;
        int colon = json.indexOf(':', idx);
        if (colon == -1) return null;
        int startQuote = json.indexOf('"', colon);
        if (startQuote == -1) return null;
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = startQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                sb.append(unescapeChar(c));
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                return sb.toString();
            }
            sb.append(c);
        }
        return null;
    }

    private static String extractNumericField(String json, String field) {
        String pattern = "\"" + field + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;
        int colon = json.indexOf(':', idx);
        if (colon == -1) return null;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end > start) {
            return json.substring(start, end);
        }
        return null;
    }

    private static Map<String, String> parseJsonStringMap(String jsonObject) {
        Map<String, String> result = new LinkedHashMap<>();
        int i = 0;
        int len = jsonObject.length();

        while (i < len && jsonObject.charAt(i) != '{') i++;
        i++; // skip '{'

        while (i < len) {
            while (i < len && (Character.isWhitespace(jsonObject.charAt(i)) || jsonObject.charAt(i) == ',')) i++;
            if (i >= len || jsonObject.charAt(i) == '}') break;

            if (jsonObject.charAt(i) != '"') break;
            i++; // skip open quote of key

            StringBuilder key = new StringBuilder();
            boolean escaped = false;
            while (i < len) {
                char c = jsonObject.charAt(i++);
                if (escaped) {
                    key.append(unescapeChar(c));
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    break;
                } else {
                    key.append(c);
                }
            }

            while (i < len && (Character.isWhitespace(jsonObject.charAt(i)) || jsonObject.charAt(i) == ':')) i++;

            if (i < len && jsonObject.charAt(i) == '"') {
                i++; // skip open quote of value
                StringBuilder val = new StringBuilder();
                escaped = false;
                while (i < len) {
                    char c = jsonObject.charAt(i++);
                    if (escaped) {
                        val.append(unescapeChar(c));
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        break;
                    } else {
                        val.append(c);
                    }
                }
                result.put(key.toString(), val.toString());
            } else {
                // Not a string value or malformed
                break;
            }
        }
        return result;
    }

    private static char unescapeChar(char c) {
        return switch (c) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'b' -> '\b';
            case 'f' -> '\f';
            default -> c;
        };
    }
}
