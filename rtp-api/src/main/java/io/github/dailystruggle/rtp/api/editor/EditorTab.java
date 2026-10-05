package io.github.dailystruggle.rtp.api.editor;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import java.util.List;
import java.util.Objects;

/**
 * Declarative addon tab (ADR-107 §6.1). {@code id} follows the extension id grammar; the page
 * mounts it as {@code ext-<extensionId>-<id>} after the core tabs. {@code title} and {@code icon}
 * are plain text ({@code icon} may be {@code null}).
 */
@PublicApi
public record EditorTab(String id, String title, String icon, List<EditorWidget> widgets) {

  public EditorTab {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(title, "title");
    widgets = widgets == null ? List.of() : List.copyOf(widgets);
  }
}
