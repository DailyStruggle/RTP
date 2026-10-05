package io.github.dailystruggle.rtp.api.editor;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import java.util.Map;

/**
 * A verified page message for one extension (ADR-107 §5.5): signed by a trusted browser key,
 * of a type the extension declared in {@link EditorExtension#inboundTypes()}.
 */
@PublicApi
public interface EditorMessage {

  /** Local type, without the {@code <extensionId>.} prefix. */
  String type();

  /** Parsed JSON body without the channel header fields; unmodifiable. */
  Map<String, Object> body();

  /** Fingerprint of the (trusted) browser key that sent it. */
  String sender();

  EditorSession session();

  /** Sends {@code bodyJson} as {@code <extensionId>.<localType>} to the sender only; ordered, never dropped. */
  boolean reply(String localType, String bodyJson);
}
