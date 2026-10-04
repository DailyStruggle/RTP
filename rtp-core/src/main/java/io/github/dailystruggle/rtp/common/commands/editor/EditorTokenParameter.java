package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.commandsapi.common.CommandParameter;

import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parameter providing validation and tab-completion for editor session tokens (ADR-104).
 *
 * <p>Conforms to commands-api design: parameter named {@code token} on wire as {@code token=<token>}.
 */
public class EditorTokenParameter extends CommandParameter {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{4,64}$");

    public EditorTokenParameter(String permission, String description) {
        super(permission, description, (uuid, val) -> val != null && TOKEN_PATTERN.matcher(val.trim()).matches());
    }

    @Override
    public Set<String> values() {
        try {
            return EditorSessionManager.getInstance().getActiveTokens();
        } catch (Exception ignored) {
            return Collections.emptySet();
        }
    }
}
