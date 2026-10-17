package io.github.dailystruggle.rtp.api.editor;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import java.util.Objects;

/**
 * Tier 2 addon UI (ADR-107 §6.2): a classpath resource in the addon jar (at most 64 KiB) that the
 * page runs only inside an opaque-origin sandboxed iframe. {@code sha256} is the lowercase hex
 * digest of its UTF-8 source, or {@code null} to let core compute it.
 */
@PublicApi
public record EditorFrame(String resource, String sha256) {

  public EditorFrame {
    Objects.requireNonNull(resource, "resource");
  }
}
