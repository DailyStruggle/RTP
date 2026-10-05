package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.tools.ChartOutputHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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

        // Region Outer Circle (Radius 6000)
        g.setColor(accent);
        g.setStroke(new BasicStroke(2.5f));
        int r = 210;
        g.drawOval(cx - r, cy - r, r * 2, r * 2);

        // Region Inner Deadzone (CenterRadius 1200)
        g.setColor(red);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{6.0f, 6.0f}, 0.0f));
        int cr = 50;
        g.drawOval(cx - cr, cy - cr, cr * 2, cr * 2);

        // Custom Polygon Boundary (Overworld Mining Zone)
        int[] polyX = {cx - 160, cx + 90, cx + 180, cx + 40, cx - 110};
        int[] polyY = {cy - 120, cy - 140, cy + 80, cy + 160, cy + 100};
        g.setColor(new Color(166, 227, 161, 45));
        g.fillPolygon(polyX, polyY, polyX.length);
        g.setColor(green);
        g.setStroke(new BasicStroke(2.2f));
        g.drawPolygon(polyX, polyY, polyX.length);

        // Polygon Vertex Handles
        g.setColor(yellow);
        for (int i = 0; i < polyX.length; i++) {
            g.fillOval(polyX[i] - 5, polyY[i] - 5, 10, 10);
            g.setColor(dark);
            g.drawOval(polyX[i] - 5, polyY[i] - 5, 10, 10);
            g.setColor(yellow);
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

        g.setFont(new Font("Monospaced", Font.PLAIN, 12));
        int textY = diffY + 24;
        g.setColor(subtext);
        g.drawString("--- definitions/regions/survival_spawn.yml", sideX + 32, textY);
        textY += 18;
        g.drawString("+++ definitions/regions/survival_spawn.yml", sideX + 32, textY);
        textY += 24;
        g.setColor(accent);
        g.drawString("@@ -14,4 +14,5 @@ shape:", sideX + 32, textY);
        textY += 20;
        g.setColor(red);
        g.drawString("-   radius: 5000", sideX + 32, textY);
        textY += 18;
        g.setColor(green);
        g.drawString("+   radius: 6000", sideX + 32, textY);
        textY += 18;
        g.setColor(red);
        g.drawString("-   centerRadius: 1000", sideX + 32, textY);
        textY += 18;
        g.setColor(green);
        g.drawString("+   centerRadius: 1200", sideX + 32, textY);
        textY += 24;
        g.setColor(text);
        g.drawString("    price: \"@economy\"", sideX + 32, textY);
        textY += 18;
        g.setColor(green);
        g.drawString("+   requirePermission: true", sideX + 32, textY);
        textY += 24;
        g.setColor(subtext);
        g.drawString("# 2 files modified | 1 polygon created", sideX + 32, textY);

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
