package io.github.dailystruggle.rtp.api.editor;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

/**
 * Delivery class of an extension push type (ADR-107 §5.3). Replies are always ordered and never
 * dropped; this enum covers broadcast pushes only.
 */
@PublicApi
public enum EditorDelivery {
  /** The next push with the same coalescing key replaces a held one; dropped first over budget. */
  LATEST,
  /** Held in order and never coalesced; refused ({@code push} returns {@code false}) when full. */
  ORDERED
}
