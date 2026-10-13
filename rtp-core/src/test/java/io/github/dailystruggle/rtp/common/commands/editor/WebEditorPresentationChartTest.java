package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.tools.ChartOutputHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

@DisplayName("Visual render test producing Web Editor presentation assets for FRONT_PAGE.md")
public class WebEditorPresentationChartTest {

    @Test
    @DisplayName("Renders web editor workspace preview into docs/assets/img/ for FRONT_PAGE.md")
    void renderWebEditorPreview() throws Exception {
        int width = 1200;
        int height = 675;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Palette (Catppuccin Mocha themed)
        Color bg = new Color(24, 24, 37);
        Color surface = new Color(30, 30, 46);
        Color overlay = new Color(49, 50, 68);
        Color text = new Color(205, 214, 244);
        Color subtext = new Color(166, 173, 200);
        Color accent = new Color(137, 180, 250);
        Color green = new Color(166, 227, 161);
        Color red = new Color(243, 139, 168);
        Color yellow = new Color(249, 226, 175);
        Color dark = new Color(17, 17, 27);

        // Background
        g.setColor(bg);
        g.fillRect(0, 0, width, height);

        // Window Frame / Header
        g.setColor(dark);
        g.fillRect(0, 0, width, 54);
        g.setColor(overlay);
        g.drawLine(0, 54, width, 54);

        // Brand & Title
        g.setColor(accent);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.drawString("⚡ LeafRTP Web Workspace", 20, 33);

        g.setColor(green);
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.drawString("● Connected (Bi-directional Live Sync)", 250, 33);

        // Top Navigation Tabs
        int tabX = 560;
        String[] tabs = {"🗺 Visual Region Editor", "📊 Diagnostics & Viz", "⚙ Config & Prefabs", "📖 Shipped Docs"};
        for (int i = 0; i < tabs.length; i++) {
            boolean active = (i == 0);
            if (active) {
                g.setColor(accent);
                g.fillRoundRect(tabX, 14, 150, 28, 6, 6);
                g.setColor(dark);
                g.setFont(new Font("SansSerif", Font.BOLD, 11));
            } else {
                g.setColor(overlay);
                g.drawRoundRect(tabX, 14, 130, 28, 6, 6);
                g.setColor(subtext);
                g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            }
            g.drawString(tabs[i], tabX + 12, 32);
            tabX += (active ? 158 : 138);
        }

        // Action Buttons on Top Right
        g.setColor(green);
        g.fillRoundRect(width - 110, 14, 90, 28, 4, 4);
        g.setColor(dark);
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.drawString("⚡ Hot-Apply", width - 98, 33);

        // Left Pane: Map Canvas
        int mapW = 760;
        int mapH = height - 54;
        g.setColor(new Color(15, 15, 24));
        g.fillRect(0, 54, mapW, mapH);

        // Grid Lines
        g.setColor(new Color(30, 30, 48));
        g.setStroke(new BasicStroke(1));
        for (int x = 0; x < mapW; x += 60) g.drawLine(x, 54, x, height);
        for (int y = 54; y < height; y += 60) g.drawLine(0, y, mapW, y);

        // Center Crosshair
        int cx = mapW / 2;
        int cy = 54 + mapH / 2;
        g.setColor(new Color(60, 60, 90));
        g.setStroke(new BasicStroke(2));
        g.drawLine(cx, 54, cx, height);
        g.drawLine(0, cy, mapW, cy);

        // POLYGON region as docs/editor/index.html drawMap() renders it: vertices are chunk
        // coordinates drawn at chunk centres; no radius / centerRadius rings (Polygon derives its
        // bounding square from the vertices); dashed AABB; outside shaded. P3 was dragged, P5 added.
        int[][] vertices = {{-200, -150}, {112, -175}, {225, 100}, {50, 200}, {-138, 125}};
        int selectedVertex = 2;
        double pxPerChunk = 0.8;
        Path2D.Double poly = new Path2D.Double();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < vertices.length; i++) {
            double vx = cx + (vertices[i][0] + 0.5) * pxPerChunk;
            double vy = cy + (vertices[i][1] + 0.5) * pxPerChunk;
            if (i == 0) poly.moveTo(vx, vy);
            else poly.lineTo(vx, vy);
            minX = Math.min(minX, vertices[i][0]);
            maxX = Math.max(maxX, vertices[i][0]);
            minZ = Math.min(minZ, vertices[i][1]);
            maxZ = Math.max(maxZ, vertices[i][1]);
        }
        poly.closePath();

        // Outside-region shading with the polygon interior cut out
        Area outside = new Area(new Rectangle2D.Double(0, 54, mapW, mapH));
        outside.subtract(new Area(poly));
        g.setColor(new Color(17, 17, 27, 166));
        g.fill(outside);

        // Bounding box of the vertices in chunk extents (Square base of Polygon)
        g.setColor(new Color(137, 180, 250, 102));
        g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{4.0f, 4.0f}, 0.0f));
        g.draw(new Rectangle2D.Double(cx + minX * pxPerChunk, cy + minZ * pxPerChunk,
                (maxX - minX + 1) * pxPerChunk, (maxZ - minZ + 1) * pxPerChunk));

        // Polygon boundary stroke
        g.setColor(accent);
        g.setStroke(new BasicStroke(2.2f));
        g.draw(poly);

        // Vertex drag handles (selected / dragged vertex highlighted)
        for (int i = 0; i < vertices.length; i++) {
            double vx = cx + (vertices[i][0] + 0.5) * pxPerChunk;
            double vy = cy + (vertices[i][1] + 0.5) * pxPerChunk;
            double hr = i == selectedVertex ? 7 : 6;
            Ellipse2D.Double handle = new Ellipse2D.Double(vx - hr, vy - hr, hr * 2, hr * 2);
            g.setColor(i == selectedVertex ? yellow : green);
            g.fill(handle);
            g.setColor(dark);
            g.setStroke(new BasicStroke(1.5f));
            g.draw(handle);
            g.setColor(i == selectedVertex ? yellow : subtext);
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.drawString("P" + (i + 1), (int) Math.round(vx + 10), (int) Math.round(vy - 8));
        }

        // HUD Overlay on Map
        g.setColor(new Color(17, 17, 27, 220));
        g.fillRoundRect(20, 74, 380, 44, 8, 8);
        g.setColor(overlay);
        g.drawRoundRect(20, 74, 380, 44, 8, 8);
        g.setColor(text);
        g.setFont(new Font("Monospaced", Font.BOLD, 12));
        g.drawString("World: overworld | Region: survival_spawn", 32, 94);
        g.setColor(subtext);
        g.setFont(new Font("Monospaced", Font.PLAIN, 11));
        g.drawString("Cursor: X: 1,420  Z: -850 (Chunk: [88, -54] Plains)", 32, 110);

        // Layers HUD
        g.setColor(new Color(17, 17, 27, 220));
        g.fillRoundRect(mapW - 250, 74, 230, 44, 8, 8);
        g.setColor(overlay);
        g.drawRoundRect(mapW - 250, 74, 230, 44, 8, 8);
        g.setColor(green);
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        g.drawString("✔ Biomes   ✔ Bad Chunks   ✔ Spiral", mapW - 240, 100);

        // Divider
        g.setColor(overlay);
        g.drawLine(mapW, 54, mapW, height);

        // Right Pane: Staging Diff & Validation Inspector
        int sideX = mapW;
        int sideW = width - mapW;
        g.setColor(surface);
        g.fillRect(sideX, 54, sideW, height - 54);

        g.setColor(accent);
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        g.drawString("📋 Configuration Staging Diff", sideX + 20, 84);

        g.setColor(subtext);
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.drawString("Live delta preview before atomic commit:", sideX + 20, 104);

        // Staging Diff Box
        int diffY = 120;
        int diffH = 260;
        g.setColor(dark);
        g.fillRoundRect(sideX + 20, diffY, sideW - 40, diffH, 8, 8);
        g.setColor(overlay);
        g.drawRoundRect(sideX + 20, diffY, sideW - 40, diffH, 8, 8);

        // Same line format as generateYamlDiff(): vertices staged in chunks ([xc, zc]) by
        // regionGeometryValues(); the drag replaces P3, the added point appends P5
        g.setFont(new Font("Monospaced", Font.PLAIN, 12));
        int textY = diffY + 22;
        g.setColor(subtext);
        g.drawString("# Staged YAML diff for", sideX + 32, textY);
        textY += 16;
        g.drawString("# definitions/regions/survival_spawn.yml:", sideX + 32, textY);
        textY += 20;
        String[] diffLines = {
                "  shape:",
                "    name: \"POLYGON\"",
                "    mode: \"ACCUMULATE\"",
                "    vertices:",
                "      - [-200c, -150c]",
                "      - [112c, -175c]",
                "-     - [210c, 120c]",
                "+     - [225c, 100c]",
                "      - [50c, 200c]",
                "+     - [-138c, 125c]",
                "    weight: 1.0"
        };
        for (String line : diffLines) {
            g.setColor(line.startsWith("-") ? red : line.startsWith("+") ? green : text);
            g.drawString(line, sideX + 32, textY);
            textY += 17;
        }

        // Validation Invariants Card (ADR-034, ADR-099)
        int valY = diffY + diffH + 20;
        g.setColor(dark);
        g.fillRoundRect(sideX + 20, valY, sideW - 40, 160, 8, 8);
        g.setColor(overlay);
        g.drawRoundRect(sideX + 20, valY, sideW - 40, 160, 8, 8);

        g.setColor(green);
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.drawString("✔ Mathematical Invariant Guards", sideX + 32, valY + 28);

        g.setFont(new Font("Monospaced", Font.PLAIN, 12));
        g.setColor(text);
        g.drawString("• Non-self-intersecting: VALID (ADR-034)", sideX + 32, valY + 54);
        g.drawString("• Collinear vertices: SIMPLIFIED (ADR-099)", sideX + 32, valY + 76);
        g.drawString("• World border bounds: 100% CONTAINED", sideX + 32, valY + 98);
        g.setColor(subtext);
        g.drawString("• Candidate yield: ~81.4% safe terrain", sideX + 32, valY + 120);
        g.setColor(accent);
        g.drawString("Token: /rtp editor apply token=8f3c4e19 (or click Hot-Apply)", sideX + 32, valY + 142);

        g.dispose();

        // Write directly to canonical docs and reports
        ChartOutputHelper.writeChart(img, "web_editor", "web_editor_region_staging_preview.png");
    }
}
