package io.github.dailystruggle.rtp.bukkit.tools.softdepends.hologram;

import io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Renders raw hologram lines through the {@link SendMessage} pipeline: RTP / numeric / PAPI
 * placeholders, MiniMessage, {@code &} and hex codes, flattened to section-code strings that
 * TextDisplay, DecentHolograms and HolographicDisplays all accept. Holograms are seen by every
 * nearby player, so placeholders resolve in the console (server) scope. Handles keep the raw
 * lines; only what reaches the entity is rendered.
 */
final class HologramText {
    private HologramText() {
    }

    static String render(String line) {
        if (line == null) return "";
        try {
            return SendMessage.toLegacyText(null, line);
        } catch (Throwable t) {
            RTP.log(Level.FINE, "[RTP] hologram line formatting failed, using color codes only: " + t.getMessage());
            return ChatColor.translateAlternateColorCodes('&', line);
        }
    }

    static List<String> render(List<String> lines) {
        if (lines == null || lines.isEmpty()) return new ArrayList<>();
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) out.add(render(line));
        return out;
    }
}
