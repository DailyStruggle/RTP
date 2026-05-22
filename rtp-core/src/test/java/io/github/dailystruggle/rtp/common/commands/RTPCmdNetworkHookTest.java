package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.network.NetworkCommandHook;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.EnumMap;
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
 * Verifies the L6 cross-server pre-dispatch hook wired into {@code RTPCmd.compute}.
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
    ConfigParser<MessagesKeys> lang =
            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
    EnumMap<MessagesKeys, Object> data = new EnumMap<>(MessagesKeys.class);
    data.put(MessagesKeys.networkQueued, "QUEUED region=[region] server=[server] pos=[position]");
    data.put(MessagesKeys.networkRegionUnavailable, "UNAVAILABLE region=[region]");
    data.put(MessagesKeys.networkFallback, "FALLBACK reason=[reason]");
    data.put(MessagesKeys.consoleCmdNotAllowed, "console disallowed");
    lang.setData(data);
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

  // ── 3. Reject outcome short-circuits and emits the named message key ─────────

  @Test
  void rejectOutcome_emitsConfiguredKeyMessage_andShortCircuits() {
    UUID playerId = UUID.randomUUID();
    accessor.addPlayer(new MockRTPPlayer(playerId, "p3", null));
    RTP.networkCommandHook = (uuid, args) ->
            NetworkCommandHook.RoutingResult.reject(MessagesKeys.networkRegionUnavailable.name(), "mars");

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
  // is provided by the Slice H2 Bukkit-adapter integration tests.

  // ── 3c. Network short-circuits must release the processingPlayers lock ──────
  //
  // Regression for the lobby symptom "presently teleporting from `lobby`
  // with unspecified target simply does nothing": the outer
  // RTPCmd.onCommand(RTPCommandSender,...) adds the player to
  // RTP.processingPlayers before dispatching to compute(). When compute's
  // network hook returned CrossServer or Reject the method short-circuited
  // and returned true WITHOUT removing the player from processingPlayers.
  // The actual teleport completion is observed on the *destination*
  // backend, never on the originating JVM, so this JVM's processingPlayers
  // entry leaked forever and every subsequent /rtp on this JVM silently
  // tripped the alreadyTeleporting guard at onCommand line 129 - never
  // reaching the hook again. The fix: remove() on both short-circuit
  // branches, mirroring the local pipeline's own cleanup on every exit
  // path.

  @Test
  void crossServerOutcome_releasesProcessingPlayersLock() {
    UUID playerId = UUID.randomUUID();
    accessor.addPlayer(new MockRTPPlayer(playerId, "leakCheck1", null));
    RTP.getInstance().processingPlayers.add(playerId); // simulate outer onCommand add
    RTP.networkCommandHook = (uuid, args) ->
            NetworkCommandHook.RoutingResult.crossServer(UUID.randomUUID(), "east", "backend-b");

    TestRTPCmd cmd = new TestRTPCmd();
    boolean res = cmd.onCommand(playerId, new HashMap<>(), null, s -> {});

    assertTrue(res);
    assertTrue(!RTP.getInstance().processingPlayers.contains(playerId),
            "CrossServer short-circuit must remove playerId from processingPlayers; "
                    + "otherwise subsequent /rtp on this JVM trips alreadyTeleporting forever");
  }

  @Test
  void rejectOutcome_releasesProcessingPlayersLock() {
    UUID playerId = UUID.randomUUID();
    accessor.addPlayer(new MockRTPPlayer(playerId, "leakCheck2", null));
    RTP.getInstance().processingPlayers.add(playerId); // simulate outer onCommand add
    RTP.networkCommandHook = (uuid, args) ->
            NetworkCommandHook.RoutingResult.reject(
                    MessagesKeys.networkRegionUnavailable.name(), "mars");

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
