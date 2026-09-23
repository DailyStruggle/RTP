package io.github.dailystruggle.rtp.api.action;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Caller-provided invocation context when triggering a scripted action (ADR-093).
 *
 * @param metadata custom contextual parameters or tag bindings passed to the script execution
 */
@PublicApi
public record ActionContext(Map<String, Object> metadata) {
  public static final ActionContext EMPTY = new ActionContext(Collections.emptyMap());

  public ActionContext {
    metadata = (metadata == null) ? Collections.emptyMap() : Map.copyOf(metadata);
  }

  public static ActionContext empty() {
    return EMPTY;
  }

  public static ActionContext of(String key, Object value) {
    Objects.requireNonNull(key, "key must not be null");
    return new ActionContext(Map.of(key, value));
  }

  public static ActionContext of(Map<String, Object> metadata) {
    return new ActionContext(metadata);
  }
}
