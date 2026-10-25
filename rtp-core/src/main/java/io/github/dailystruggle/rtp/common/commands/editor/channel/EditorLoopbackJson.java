package io.github.dailystruggle.rtp.common.commands.editor.channel;

import io.github.dailystruggle.rtp.common.text.JsonText;

/**
 * Strict minimal JSON reader/writer for {@link EditorLoopbackChannel} messages (ADR-104).
 *
 * <p>Delegates to {@link JsonText} in {@code rtp.common.text} (ADR-115 Tier 1).
 */
public final class EditorLoopbackJson {

    static final int MAX_DEPTH = JsonText.MAX_DEPTH;

    private EditorLoopbackJson() {}

    /** Parses one complete JSON document. */
    public static Object parse(String json) {
        return JsonText.parse(json);
    }

    /** JSON string literal with every control character escaped. */
    public static String quote(String raw) {
        return JsonText.quote(raw);
    }
}
