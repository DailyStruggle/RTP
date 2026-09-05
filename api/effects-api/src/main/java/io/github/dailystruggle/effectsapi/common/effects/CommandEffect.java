package io.github.dailystruggle.effectsapi.common.effects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Command execution effect (effects-api-ADR-007).
 *
 * <p>Executes a console command or player command across any teleport lifecycle stage
 * (or other stage such as death). Supports quotes, backslash escapes, and dynamic placeholders:
 * <ul>
 *     <li>{@code [player]} or {@code {player}} - player name</li>
 *     <li>{@code [uuid]} or {@code {uuid}} - player unique id</li>
 *     <li>{@code [world]} or {@code {world}} - world name</li>
 *     <li>{@code [x]}, {@code [y]}, {@code [z]} or {@code {x}}, {@code {y}}, {@code {z}} - coordinates</li>
 * </ul>
 */
public class CommandEffect extends Effect<CommandEffect.CommandKeys> {

    public enum Mode {
        CONSOLE,
        PLAYER
    }

    public enum CommandKeys {
        MODE,
        COMMAND
    }

    public CommandEffect() {
        super(new EnumMap<>(CommandKeys.class));
        EnumMap<CommandKeys, Object> data = getData();
        data.put(CommandKeys.MODE, Mode.CONSOLE);
        data.put(CommandKeys.COMMAND, "");
        this.data = data;
        this.defaults = data.clone();
    }

    public CommandEffect(Mode mode, String command) {
        super(new EnumMap<>(CommandKeys.class));
        EnumMap<CommandKeys, Object> data = getData();
        data.put(CommandKeys.MODE, mode != null ? mode : Mode.CONSOLE);
        data.put(CommandKeys.COMMAND, command != null ? command : "");
        this.data = data;
        this.defaults = data.clone();
    }

    @Override
    public void run() {
        Object rawMode = data.get(CommandKeys.MODE);
        Mode mode = Mode.CONSOLE;
        if (rawMode instanceof Mode m) mode = m;
        else if (rawMode != null) {
            try {
                mode = Mode.valueOf(rawMode.toString().toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        Object rawCmd = data.get(CommandKeys.COMMAND);
        if (rawCmd == null) return;
        String cmd = rawCmd.toString().trim();
        if (cmd.isEmpty()) return;

        PlayerHandle ph = HandleRegistry.wrapPlayer(target);
        LocationHandle lh = HandleRegistry.wrapLocation(target);
        if (ph == null && lh != null) {
            ph = HandleRegistry.playerAt(lh);
        }

        // Placeholder substitution
        if (ph != null) {
            cmd = cmd.replace("[player]", ph.name()).replace("{player}", ph.name());
            cmd = cmd.replace("[uuid]", ph.uuid().toString()).replace("{uuid}", ph.uuid().toString());
        }
        if (lh != null) {
            cmd = cmd.replace("[world]", lh.worldName()).replace("{world}", lh.worldName());
            cmd = cmd.replace("[x]", String.valueOf(lh.x())).replace("{x}", String.valueOf(lh.x()));
            cmd = cmd.replace("[y]", String.valueOf(lh.y())).replace("{y}", String.valueOf(lh.y()));
            cmd = cmd.replace("[z]", String.valueOf(lh.z())).replace("{z}", String.valueOf(lh.z()));
        }

        if (mode == Mode.PLAYER && ph != null) {
            HandleRegistry.dispatchPlayerCommand(ph, cmd);
        } else {
            HandleRegistry.dispatchConsoleCommand(cmd);
        }
    }

    @Override
    public void setData(EnumMap<CommandKeys, Object> data) {
        if (data == null) return;
        this.data.putAll(data);
    }

    @Override
    public void setData(String... tokens) {
        if (tokens == null || tokens.length == 0) return;

        int startIndex = 0;
        Mode mode = Mode.CONSOLE;

        String first = tokens[0].trim();
        if (first.equalsIgnoreCase("PLAYER")) {
            mode = Mode.PLAYER;
            startIndex = 1;
        } else if (first.equalsIgnoreCase("CONSOLE")) {
            mode = Mode.CONSOLE;
            startIndex = 1;
        }

        data.put(CommandKeys.MODE, mode);

        if (startIndex >= tokens.length) {
            data.put(CommandKeys.COMMAND, "");
            return;
        }

        // Reconstruct command from remaining tokens with quotes and backslash unescaping
        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i < tokens.length; i++) {
            if (i > startIndex) sb.append(' ');
            sb.append(tokens[i]);
        }

        String rawCommand = sb.toString().trim();
        String parsedCommand = parseHumanReadableCommand(rawCommand);
        data.put(CommandKeys.COMMAND, parsedCommand);
    }

    /**
     * Parse human-readable command string handling quotes, escapes, etc.
     */
    public static String parseHumanReadableCommand(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";

        // Strip enclosing quotes if the whole string was quoted
        if ((s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2)
                || (s.startsWith("'") && s.endsWith("'") && s.length() >= 2)) {
            s = s.substring(1, s.length() - 1);
        }

        StringBuilder out = new StringBuilder(s.length());
        boolean escaping = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaping) {
                out.append(c);
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else {
                out.append(c);
            }
        }
        if (escaping) {
            out.append('\\');
        }

        return out.toString();
    }

    @Override
    public String toPermission() {
        Mode mode = (Mode) data.getOrDefault(CommandKeys.MODE, Mode.CONSOLE);
        String cmd = (String) data.getOrDefault(CommandKeys.COMMAND, "");
        // Escape '.' and ' ' for permission node representation
        StringBuilder escapedCmd = new StringBuilder();
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (c == '.' || c == ' ') {
                escapedCmd.append('\\').append(c);
            } else {
                escapedCmd.append(c);
            }
        }
        return mode.name().toLowerCase() + "." + escapedCmd;
    }
}
