package io.github.dailystruggle.rtp.common.visualization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.mock.BitLandingBins;
import io.github.dailystruggle.rtp.common.mock.MockTeleportRecord;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-F-009 teleport heatmap writes CSV and PNG")
class BitHeatmapExportTest {
    @Test
    void writesAllLandingFieldsAndAHeadlessAdr119Heatmap() throws Exception {
        Path output = Path.of("build/reports/teleport-heatmap-test");
        MockTeleportRecord landing =
                new MockTeleportRecord(UUID.randomUUID(), "world,one", -12, 73, 24, "default", 4, 178, 1);
        BitLandingBins.Bins bins = BitLandingBins.bin(List.of(landing), 8, 8);

        BitHeatmapExport.ExportedFiles files =
                BitHeatmapExport.export(output, "default", List.of(landing), bins);

        assertTrue(Files.isRegularFile(files.csv()));
        assertTrue(Files.isRegularFile(files.png()));
        List<String> csv = Files.readAllLines(files.csv());
        assertEquals("world,x,y,z,region,attempts,durationMillis", csv.get(0));
        assertTrue(csv.get(1).startsWith("\"world,one\",-12,73,24,default,4,178"));
        BufferedImage png = ImageIO.read(files.png().toFile());
        assertEquals(8, png.getWidth());
        assertEquals(8, png.getHeight());
        assertTrue(png.getRGB(0, 0) != png.getRGB(7, 7));
    }
}
