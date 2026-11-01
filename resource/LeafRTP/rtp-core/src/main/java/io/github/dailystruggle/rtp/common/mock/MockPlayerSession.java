package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Owns one BIT mock player's lifetime (ADR-121 section 11): create, register, run, then
 * unregister and purge its session state.
 *
 * <p>Names are {@code rtp-bit-<8 hex>}; {@code -} is illegal in Minecraft names, so a mock never
 * shadows a real player in by-name lookups. Invariant: after {@link #close()} the registry and
 * {@link TeleportSessions} hold nothing for the mock's UUID. Real players are never touched.
 */
public final class MockPlayerSession implements AutoCloseable {

  public static final String NAME_PREFIX = "rtp-bit-";

  private final RuntimeMockRTPPlayer player;
  private final AtomicBoolean closed = new AtomicBoolean();

  private MockPlayerSession(RuntimeMockRTPPlayer player) {
    this.player = player;
  }

  /**
   * Creates an {@code rtp-bit-<8 hex>} mock at {@code initial} and registers it.
   *
   * @param initial starting location; must be non-null
   * @throws NullPointerException if {@code initial} is null
   */
  public static MockPlayerSession open(RTPLocation initial) {
    Objects.requireNonNull(initial, "initial");
    UUID id = UUID.randomUUID();
    String name = NAME_PREFIX + String.format("%08x", ThreadLocalRandom.current().nextInt());
    RuntimeMockRTPPlayer player = new RuntimeMockRTPPlayer(id, name, initial);
    MockPlayerRegistry.register(player);
    return new MockPlayerSession(player);
  }

  /** Opens a session, applies {@code body} to its mock, and closes the session in {@code finally}. */
  public static <T> T with(RTPLocation initial, Function<RuntimeMockRTPPlayer, T> body) {
    try (MockPlayerSession session = open(initial)) {
      return body.apply(session.player());
    }
  }

  public RuntimeMockRTPPlayer player() {
    return player;
  }

  /** Idempotent; unregisters and purges session state for the mock. Never throws. */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    UUID id = player.uuid();
    MockPlayerRegistry.unregister(id);
    try {
      TeleportSessions sessions = TeleportSessions.current();
      if (sessions == null) return;
      sessions.processingPlayers.remove(id);
      sessions.queuedPlayers.remove(id);
      sessions.invulnerablePlayers.remove(id);
      sessions.regionTeleportTimes.remove(id);
      sessions.priorTeleportData.remove(id);
      sessions.latestTeleportData.remove(id);
      sessions.usageCaps.reset(id);
    } catch (RuntimeException e) {
      // S-004: logged, not swallowed; close() must not mask the probe body's outcome.
      RTPRuntime.log(Level.WARNING, "[BIT] mock session purge failed for " + id, e);
    }
  }
}
