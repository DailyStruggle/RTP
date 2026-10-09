package io.github.dailystruggle.rtp.api.editor;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One declarative widget of an addon editor tab (ADR-107 §6.1), rendered by the page itself.
 *
 * <p>{@code kind} selects the widget ({@code markdown}, {@code status}, {@code keyValue},
 * {@code table}, {@code form}, {@code button}); a page shows unknown kinds as a placeholder.
 * {@code props} holds JSON values only: {@code String}, {@code Number}, {@code Boolean},
 * {@code List}, {@code Map} or {@code null}. Core validates both when building a session.
 */
@PublicApi
public record EditorWidget(String kind, Map<String, Object> props) {

  public EditorWidget {
    Objects.requireNonNull(kind, "kind");
    props = props == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(props));
  }
}
