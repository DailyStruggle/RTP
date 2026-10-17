package io.github.dailystruggle.rtp.api.editor;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

/**
 * One web editor session as seen by an extension (ADR-107 §4.2). Core owns the transport: it
 * prefixes the type with {@code <extensionId>.}, signs, paces, bundles and budgets every push.
 *
 * <p>Pushes return {@code false} when the session is closed, the type is not declared in
 * {@link EditorExtension#pushTypes()}, the body is not a JSON object, exceeds a cap, or the
 * extension's budget refuses it. Core logs each refusal; the caller decides whether to retry.
 * Safe from any thread; never blocks.
 */
@PublicApi
public interface EditorSession {

  /** Opaque per-session id (not the relay channel id). */
  String id();

  boolean isOpen();

  /** Broadcasts {@code bodyJson}; a {@link EditorDelivery#LATEST} type coalesces by type alone. */
  boolean push(String localType, String bodyJson);

  /** Broadcasts {@code bodyJson}; a {@link EditorDelivery#LATEST} type coalesces by type and {@code coalesceKey}. */
  boolean push(String localType, String coalesceKey, String bodyJson);
}
