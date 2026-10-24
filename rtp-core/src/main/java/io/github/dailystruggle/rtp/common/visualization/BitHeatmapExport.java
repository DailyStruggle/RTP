package io.github.dailystruggle.rtp.common.visualization;

import io.github.dailystruggle.mapsapi.image.ImageMapCanvas;
import io.github.dailystruggle.mapsapi.model.Heatmap2D;
import io.github.dailystruggle.mapsapi.render.HeatmapRenderer;
import io.github.dailystruggle.rtp.common.mock.BitLandingBins;
import io.github.dailystruggle.rtp.common.mock.MockTeleportRecord;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Writes BIT mock landing records and their ADR-121 heatmap to standalone files.
 */
public final class BitHeatmapExport {
    private static final String CSV_HEADER = "world,x,y,z,region,attempts,durationMillis";
    private static final Pattern UNSAFE_REGION_CHARACTERS = Pattern.compile("[^A-Za-z0-9._-]");

    private BitHeatmapExport() {
    }

    public static ExportedFiles export(
            Path directory, String region, List<MockTeleportRecord> records, BitLandingBins.Bins bins)
            throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(bins, "bins");
        Files.createDirectories(directory);

        String safeRegion = safeRegion(region);
        String baseName = "teleport-heatmap-" + safeRegion + "-" + System.currentTimeMillis()
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path csv = directory.resolve(baseName + ".csv");
        Path png = directory.resolve(baseName + ".png");
        writeCsv(csv, records);
        writePng(png, bins);
        return new ExportedFiles(csv, png);
    }

    private static void writeCsv(Path file, List<MockTeleportRecord> records) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(CSV_HEADER);
            writer.newLine();
            for (MockTeleportRecord record : records) {
                Objects.requireNonNull(record, "record");
                writer.write(csv(record.world()));
                writer.write(',');
                writer.write(Integer.toString(record.x()));
                writer.write(',');
                writer.write(Integer.toString(record.y()));
                writer.write(',');
                writer.write(Integer.toString(record.z()));
                writer.write(',');
                writer.write(csv(record.region()));
                writer.write(',');
                writer.write(Long.toString(record.attempts()));
                writer.write(',');
                writer.write(Long.toString(record.durationMillis()));
                writer.newLine();
            }
        }
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (safe.indexOf(',') < 0
                && safe.indexOf('"') < 0
                && safe.indexOf('\r') < 0
                && safe.indexOf('\n') < 0
                && !safe.startsWith(" ")
                && !safe.endsWith(" ")) {
            return safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static void writePng(Path file, BitLandingBins.Bins bins) throws IOException {
        long maxCount = 0;
        for (long count : bins.counts()) maxCount = Math.max(maxCount, count);
        Heatmap2D model =
                new Heatmap2D(bins.width(), bins.height(), bins.values(), 0.0, Math.max(1.0, maxCount));
        ImageMapCanvas canvas = new ImageMapCanvas(bins.width(), bins.height());
        HeatmapRenderer.INSTANCE.render(canvas, model);
        canvas.commit();
        canvas.writeToFile(file.toFile(), "png");
    }

    private static String safeRegion(String region) {
        String safe = UNSAFE_REGION_CHARACTERS.matcher(Objects.requireNonNull(region, "region")).replaceAll("_");
        return safe.isBlank() ? "region" : safe;
    }

    public record ExportedFiles(Path csv, Path png) {
        public ExportedFiles {
            Objects.requireNonNull(csv, "csv");
            Objects.requireNonNull(png, "png");
        }
    }
}
