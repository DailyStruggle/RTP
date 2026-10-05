package io.github.dailystruggle.rtp.api.editor;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An addon's contribution to the web editor (ADR-107): snapshot data, live state, declarative tabs,
 * its own namespaced message types and addon YAML edited through Hot-Apply.
 *
 * <p>Core owns the schedule and the transport, as it does for the GUI addon's typed API objects:
 * it calls these methods, prefixes types with {@code <id>.}, signs, paces and budgets. Extensions
 * never see keys, channel ids or other extensions' traffic.
 *
 * <p>Threading (S-005): no method is called on a main or region thread. Getters and
 * {@link #snapshotJson()} run on the async payload build, {@link #stateJson()} on the live feed's
 * async tick, {@link #onMessage} and the session callbacks on the transport thread (must not
 * block), {@link #validate} / {@link #applied} on the Hot-Apply pipeline. Schedule world access
 * through {@code RTP.scheduler} and answer later. Every call is wrapped: exceptions are logged and
 * three failures mark the extension failed for that session.
 *
 * <p>JSON strings are JSON texts; a value that does not parse is refused and logged.
 */
@PublicApi
public interface EditorExtension {

  /** Namespace id, {@code [a-z][a-z0-9-]{1,23}}, stable across releases; see the registry for reserved ids. */
  String id();

  /** The addon's own descriptor and message version, {@code >= 1}. */
  int version();

  /** Plain-text name for tab tooltips and error badges. */
  String displayName();

  /** Tier 1 declarative tabs (ADR-107 §6.1); empty for a data-only extension. */
  default List<EditorTab> tabs() {
    return List.of();
  }

  /** Local push types and their delivery class; {@code state} is reserved and always {@link EditorDelivery#LATEST}. */
  default Map<String, EditorDelivery> pushTypes() {
    return Map.of();
  }

  /** Local types the page may send; anything else for this extension is dropped by core. */
  default Set<String> inboundTypes() {
    return Set.of();
  }

  /** Addon YAML paths relative to the plugin data folder, edited only through Hot-Apply (ADR-107 §7). */
  default List<String> configFiles() {
    return List.of();
  }

  /** Tier 2 sandboxed frame (ADR-107 §6.2), or {@code null}. */
  default EditorFrame frame() {
    return null;
  }

  /** JSON value carried in the session snapshot under {@code extensions[].snapshot} (cap 64 KiB), or {@code null}. */
  default String snapshotJson() {
    return null;
  }

  /** Current live state as a JSON value (cap 16 KiB), or {@code null}; pushed as {@code <id>.state} when it changes. */
  default String stateJson() {
    return null;
  }

  /** A verified message of a declared inbound type. Must not block. */
  default void onMessage(EditorMessage message) {
  }

  /** Errors for {@code yaml} staged for {@code path}; any error fails the whole Hot-Apply. */
  default List<String> validate(String path, String yaml) {
    return List.of();
  }

  /** {@code path} was written by Hot-Apply; the addon reloads it. */
  default void applied(String path) {
  }

  default void sessionOpened(EditorSession session) {
  }

  default void sessionClosed(EditorSession session) {
  }
}
