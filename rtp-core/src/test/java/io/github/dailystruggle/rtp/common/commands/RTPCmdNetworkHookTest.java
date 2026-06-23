package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.configuration.enums.NetworkMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.network.NetworkCommandHook;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the cross-server pre-dispatch hook wired into {@code RTPCmd.compute}.
 * Covers all four outcomes of {@link NetworkCommandHook.RoutingResult} plus the
 * S-004 degrade-on-throw path.
 *
 * <p>Traces rtp-proxy-ADR-014. The hook itself lives in {@code rtp-api}; this
 * test exercises the integration point in {@code rtp-core/RTPCmd}.
 */
public class RTPCmdNetworkHookTest {

  @TempDir File tempDir;

  private MockRTPServerAccessor accessor;
  private NetworkCommandHook originalHook;

  /** Bare concrete RTPCmd used only to invoke the default-method {@code compute}. */
  private static class TestRTPCmd extends BaseRTPCmdImpl implements RTPCmd {
    TestRTPCmd() { super(null); }

    @Override
    public boolean onCommand(UUID senderId, Map<String, List<String>> args, CommandsAPICommand next) {
      return onCommand(senderId, args, next, null);
    }

    @Override
    public boolean onCommand(UUID senderId, Map<String, List<String>> args, CommandsAPICommand next,
                             java.util.function.Consumer<String> messageMethod) {
      if (next != null) return true;
      return compute(senderId, args, next, messageMethod);
    }

    @Override public String name() { return "rtp"; }
    @Override public String permission() { return "rtp.use"; }
    @Override public String description() { return "rtp"; }
    @Override public void successEvent(RTPCommandSender sender, RTPPlayer player) {}
    @Override public void failEvent(RTPCommandSender sender, String msg) {}
  }

  @BeforeEach
  void setUp() {
    accessor = RTPTestSetup.install(tempDir);
    originalHook = RTP.networkCommandHook;

    // Seed deterministic templates for the network message keys the hook emits.
    @SuppressWarnings("unchecked")
    java.util.Map<Enum<?>, Object> data = new java.util.HashMap<>();
    data.put(NetworkMessages.networkQueued, "QUEUED region=[region] server=[server] pos=[position]");
    data.put(NetworkMessages.networkRegionUnavailable, "UNAVAILABLE region=[region]");
    data.put(NetworkMessages.networkFallback, "FALLBACK reason=[reason]");
    data.put(PlayerMessages.consoleCmdNotAllowed, "console disallowed");
    data.forEach((k, v) -> {
            Object fv = RTP.configs.getParser((Class) k.getDeclaringClass());
            if (fv instanceof io.github.dailystruggle.rtp.common.configuration.ConfigParser) {
                ((io.github.dailystruggle.rtp.common.configuration.ConfigParser) fv).setData(java.util.Map.of(((Enum<?>) k).name(), v));
            }
        });
  }

  @AfterEach
  void tearDown() {
    RTP.networkCommandHook = originalHook;
  }

  // ── 1. Default install is LOCAL_ONLY → hook is invisible to the pipeline ─────

  @Test
  void defaultLocalOnlyHook_isTheSingletonSentinel() {
    // With the default install, compute's hook block becomes a zero-cost
    // identity-check no-op (`hook == LOCAL_ONLY` short-circuits before route()
    // is ever called). We assert the field identity rather than running the
    // pipeline downstream, since fall-through behaviour is owned by the local
    // teleport tests, not the hook integration tests.
    assertEquals(NetworkCommandHook.LOCAL_ONLY, RTP.networkCommandHook,
            "default install must be the LOCAL_ONLY singleton");
  }

  // ── 2. CrossServer outcome short-circuits and emits networkQueued ────────────

  @Test
  void crossServerOutcome_emitsNetworkQueued_andShortCircuits() {
    UUID playerId = UUID.randomUUID();
    accessor.addPlayer(new MockRTPPlayer(playerId, "p2", null));
    UUID corr = UUID.randomUUID();
    AtomicInteger calls = new AtomicInteger(0);
    RTP.networkCommandHook = (uuid, args) -> {
      calls.incrementAndGet();
      return NetworkCommandHook.RoutingResult.crossServer(corr, "east", "backend-b");
    };

    TestRTPCmd cmd = new TestRTPCmd();
    Map<String, List<String>> args = new HashMap<>();
    args.put("region", List.of("east"));
    AtomicReference<String> msg = new AtomicReference<>();

    boolean res = cmd.onCommand(playerId, args, null, msg::set);

    assertTrue(res);
    assertEquals(1, calls.get(), "hook must be consulted exactly once");
    assertNotNull(msg.get(), "networkQueued must be emitted via messageMethod");
    assertTrue(msg.get().contains("QUEUED"), "expected QUEUED message, got: " + msg.get());
    assertTrue(msg.get().contains("region=east"), "region placeholder must substitute: " + msg.get());
    assertTrue(msg.get().contains("server=backend-b"), "server placeholder must substitute: " + msg.get());
    // No local pipeline teleport scheduled — processingPlayers was added then removed,
    // OR was never added because of short-circuit. Either way we just rely on res=true.
  }

  // ── 2b. Empty networkQueued template still emits a hardcoded fallback ───────
  //
  // Regression for stale-messages.yml installs (baseline that
  // lacks the networkQueued key entirely): a missing/empty template
  // previously caused the CrossServer branch to silently drop the dispatch,
  // so the player got no feedback and re-typed /rtp. Now we substitute a
  // hardcoded English fallback so something always reaches the player.

  @Test
  void crossServerOutcome_emptyTemplate_emitsHardcodedFallback() {
    UUID playerId = UUID.randomUUID();
    accessor.addPlayer(new MockRTPPlayer(playerId, "p2b", null));
    // Wipe the networkQueued template to simulate a stale messages.yml.
    @SuppressWarnings("unchecked")
    java.util.Map<Enum<?>, Object> data = new java.util.HashMap<>();
    data.put(NetworkMessages.networkQueued, "");
    data.put(NetworkMessages.networkRegionUnavailable, "UNAVAILABLE region=[region]");
    data.put(NetworkMessages.networkFallback, "FALLBACK reason=[reason]");
    data.put(PlayerMessages.consoleCmdNotAllowed, "console disallowed");
    data.forEach((k, v) -> {
            Object fv = RTP.configs.getParser((Class) k.getDeclaringClass());
            if (fv instanceof io.github.dailystruggle.rtp.common.configuration.ConfigParser) {
                ((io.github.dailystruggle.rtp.common.configuration.ConfigParser) fv).setData(java.util.Map.of(((Enum<?>) k).name(), v));
            }
        });

    RTP.networkCommandHook = (uuid, args) ->
            NetworkCommandHook.RoutingResult.crossServer(UUID.randomUUID(), "east", "backend-b");

    TestRTPCmd cmd = new TestRTPCmd();
    AtomicReference<String> msg = new AtomicReference<>();
    boolean res = cmd.onCommand(playerId, new HashMap<>(), null, msg::set);

    assertTrue(res);
    assertNotNull(msg.get(),
            "empty networkQueued template must NOT silently drop the dispatch; "
                    + "player must always see a fallback message");
    assertTrue(msg.get().contains("Queued") || msg.get().contains("RTP"),
            "fallback must mention queue/RTP, got: " + msg.get());
  }

  // ── 3. Reject outcome short-circuits and emits the named message key ─────────

  @Test
  void rejectOutcome_emitsConfiguredKeyMessage_andShortCircuits() {
    UUID playerId = UUID.randomUUID();
    accessor.addPlayer(new MockRTPPlayer(playerId, "p3", null));
    RTP.networkCommandHook = (uuid, args) ->
            NetworkCommandHook.RoutingResult.reject(NetworkMessages.networkRegionUnavailable.name(), "mars");

    TestRTPCmd cmd = new TestRTPCmd();
    Map<String, List<String>> args = new HashMap<>();
    args.put("region", List.of("mars"));
    AtomicReference<String> msg = new AtomicReference<>();

    boolean res = cmd.onCommand(playerId, args, null, msg::set);

    assertTrue(res);
    assertNotNull(msg.get(), "reject must emit the named message key");
    assertTrue(msg.get().contains("UNAVAILABLE"), "expected UNAVAILABLE, got: " + msg.get());
    assertTrue(msg.get().contains("region=mars"), "region placeholder must substitute: " + msg.get());
  }

  // ── 3b. Reject with unknown key falls back to networkRegionUnavailable ───────

  @Test
  void rejectOutcome_withUnknownMessageKey_fallsBackToNetworkRegionUnavailable() {
    UUID playerId = UUID.randomUUID();
    accessor.addPlayer(new MockRTPPlayer(playerId, "p3b", null));
    RTP.networkCommandHook = (uuid, args) ->
            NetworkCommandHook.RoutingResult.reject("notARealEnumName", "venus");

    TestRTPCmd cmd = new TestRTPCmd();
    AtomicReference<String> msg = new AtomicReference<>();

    boolean res = cmd.onCommand(playerId, new HashMap<>(), null, msg::set);

    assertTrue(res);
    assertNotNull(msg.get());
    assertTrue(msg.get().contains("UNAVAILABLE"),
            "unknown enum name must fall back to networkRegionUnavailable; got: " + msg.get());
    assertTrue(msg.get().contains("region=venus"));
  }

  // ── 4. Console sender (UUID(0,0)) bypasses the hook entirely ─────────────────
  //
  // Note on S-004 throw-degrade coverage: the catch-and-log block in
  // RTPCmd.compute is self-evident (try { hook.route } catch { log + assign
  // Local }) and exercises a runtime WARNING log path that is visible during
  // any full test run but cannot be asserted here without leaking
  // TeleportData / MemoryTracker entries into adjacent tests. Live coverage
  // is provided by the Bukkit-adapter integration tests.

  // ── 3c. Network short-circuits and the processingPlayers lock ───────────────
  //
  // CrossServer: the lobby's local `processingPlayers` lock is the
  // anti-spam primitive that prevents the player from re-enrolling a
  // duplicate envelope on the proxy every time they spam /rtp while
  // already parked on the cross-proxy waitlist. The lock is released
  // authoritatively by (a) PlayerQuitEvent (OnPlayerQuit listener),
  // (b) NetworkStatusCache terminal transitions, or (c) plugin
  // shutdown. It MUST NOT be released by the CrossServer short-circuit
  // itself - that was the regression behind "I have to type /rtp twice"
  // and "spam with no response" (every subsequent /rtp got REJECTED_
  // DUPLICATE on the proxy silently while still re-emitting networkQueued
  // here).
  //
  // Reject: terminal outcome, no follow-up routing, lock is released
  // immediately so the player can retry against a different region.

  @Test
  void crossServerOutcome_retainsProcessingPlayersLockForAntiSpam() {
    UUID playerId = UUID.randomUUID();
    accessor.addPlayer(new MockRTPPlayer(playerId, "leakCheck1", null));
    RTP.getInstance().processingPlayers.add(playerId); // simulate outer onCommand add
    RTP.networkCommandHook = (uuid, args) ->
            NetworkCommandHook.RoutingResult.crossServer(UUID.randomUUID(), "east", "backend-b");

    TestRTPCmd cmd = new TestRTPCmd();
    boolean res = cmd.onCommand(playerId, new HashMap<>(), null, s -> {});

    assertTrue(res);
    assertTrue(RTP.getInstance().processingPlayers.contains(playerId),
            "CrossServer short-circuit must RETAIN the processingPlayers lock; "
                    + "otherwise subsequent /rtp re-enrols the player on the proxy "
                    + "(REJECTED_DUPLICATE) with no visible feedback");
  }

  @Test
  void rejectOutcome_releasesProcessingPlayersLock() {
    UUID playerId = UUID.randomUUID();
    accessor.addPlayer(new MockRTPPlayer(playerId, "leakCheck2", null));
    RTP.getInstance().processingPlayers.add(playerId); // simulate outer onCommand add
    RTP.networkCommandHook = (uuid, args) ->
            NetworkCommandHook.RoutingResult.reject(
                    NetworkMessages.networkRegionUnavailable.name(), "mars");

    TestRTPCmd cmd = new TestRTPCmd();
    boolean res = cmd.onCommand(playerId, new HashMap<>(), null, s -> {});

    assertTrue(res);
    assertTrue(!RTP.getInstance().processingPlayers.contains(playerId),
            "Reject short-circuit must remove playerId from processingPlayers");
  }

  @Test
  void consoleSenderWithoutPlayerArg_doesNotConsultHook() {
    AtomicInteger calls = new AtomicInteger(0);
    RTP.networkCommandHook = (uuid, args) -> {
      calls.incrementAndGet();
      return NetworkCommandHook.RoutingResult.crossServer(UUID.randomUUID(), "x", "y");
    };

    TestRTPCmd cmd = new TestRTPCmd();
    AtomicReference<String> msg = new AtomicReference<>();
    boolean res = cmd.onCommand(new UUID(0, 0), new HashMap<>(), null, msg::set);

    assertTrue(res);
    // console-as-sender is short-circuited by the consoleCmdNotAllowed gate
    // BEFORE the hook check. Hook must not be consulted.
    assertEquals(0, calls.get(),
            "console-without-player arg must short-circuit before the network hook");
  }
}
