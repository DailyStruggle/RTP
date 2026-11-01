package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionGateContext;
import io.github.dailystruggle.rtp.api.action.ActionService;
import io.github.dailystruggle.rtp.api.action.ActionSession;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch coverage for declarative gate evaluation (ADR-093 Section 3).
 */
class GateEvaluatorBranchesTest {

  private static final String REGION_KEY = "gate_branches_region";

  private RTPServerAccessor savedAccessor;
  private ActionService savedActionService;
  private RTPServerAccessor accessor;

  @BeforeEach
  void setUp() {
    savedAccessor = RTPRuntime.serverAccessor();
    savedActionService = RTPAPI.actionService;
    RTPRuntime.setServerAccessor(null);
    RTPAPI.actionService = null;
    accessor = mock(RTPServerAccessor.class);
  }

  @AfterEach
  void tearDown() {
    RTPRuntime.setServerAccessor(savedAccessor);
    RTPAPI.actionService = savedActionService;
    ActiveSelection.get().permRegionLookup.remove(REGION_KEY);
  }

  private static ActionGateContext ctx() {
    return new ActionGateContext(UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 0, true, 16.0);
  }

  private static ActionGateContext ctxAt(Double x, Double y, Double z, Double anchorY, UUID participant) {
    return new ActionGateContext(UUID.randomUUID(), "test", participant, 10L, 290L, 0, true, 16.0,
        x, y, z, 0.0, anchorY, 0.0, 1, ActionContext.EMPTY);
  }

  private static RTPPlayer player(UUID id, RTPLocation loc, boolean online) {
    RTPPlayer p = mock(RTPPlayer.class);
    when(p.uuid()).thenReturn(id);
    when(p.getLocation()).thenReturn(loc);
    when(p.isOnline()).thenReturn(online);
    return p;
  }

  private static RTPWorld<?> world(String name) {
    RTPWorld<?> w = mock(RTPWorld.class);
    when(w.name()).thenReturn(name);
    return w;
  }

  @Test
  @DisplayName("requiresMultipleParticipants skips null/empty blocks and non-participant keys")
  void requiresMultipleParticipantsGuards() {
    assertFalse(GateEvaluator.requiresMultipleParticipants(null));
    assertFalse(GateEvaluator.requiresMultipleParticipants(List.of()));

    List<Map<String, Object>> gates = new ArrayList<>();
    gates.add(null);
    gates.add(Map.of());
    gates.add(Map.of("time", Map.of("elapsed", ">= 5s")));
    assertFalse(GateEvaluator.requiresMultipleParticipants(gates));
  }

  @Test
  @DisplayName("requiresMultipleParticipants reads matches/range/count maps and scalar expressions")
  void requiresMultipleParticipantsExpressions() {
    assertTrue(GateEvaluator.requiresMultipleParticipants(List.of(Map.of("players", Map.of("matches", ">= 2")))));
    assertTrue(GateEvaluator.requiresMultipleParticipants(List.of(Map.of("participants", Map.of("range", "2..4")))));
    assertTrue(GateEvaluator.requiresMultipleParticipants(List.of(Map.of("queue", Map.of("count", "> 1")))));
    assertTrue(GateEvaluator.requiresMultipleParticipants(List.of(Map.of("participantcount", ">= 2"))));
    assertTrue(GateEvaluator.requiresMultipleParticipants(List.of(Map.of("PLAYERS", "2"))));

    assertFalse(GateEvaluator.requiresMultipleParticipants(List.of(Map.of("participantcount", Map.of()))));
    assertFalse(GateEvaluator.requiresMultipleParticipants(List.of(Map.of("players", ">= 1"))));
    assertFalse(GateEvaluator.requiresMultipleParticipants(List.of(Map.of("players", "   "))));

    Map<String, Object> nullValue = new HashMap<>();
    nullValue.put("players", null);
    assertFalse(GateEvaluator.requiresMultipleParticipants(List.of(nullValue)));
  }

  @Test
  @DisplayName("evaluateAll handles null context, passing validators and failing gate blocks")
  void evaluateAllBranches() {
    assertTrue(GateEvaluator.evaluateAll(null, null, null, null));
    assertTrue(GateEvaluator.evaluateAll(List.of(), null, null, null));

    ActionGateContext passing = new ActionGateContext(UUID.randomUUID(), "test", UUID.randomUUID(),
        10L, 290L, 0, true, 0.0, null, null, null, null, null, null, 1,
        ActionContext.ofValidators(List.of(c -> true)));
    assertTrue(GateEvaluator.evaluateAll(null, passing, null, null));
    assertTrue(GateEvaluator.evaluateAll(List.of(Map.of("players", "1")), passing, null, null));
    assertFalse(GateEvaluator.evaluateAll(List.of(Map.of("players", "1"), Map.of("players", "5")), passing, null, null));

    ActionGateContext failing = new ActionGateContext(UUID.randomUUID(), "test", UUID.randomUUID(),
        10L, 290L, 0, true, 0.0, null, null, null, null, null, null, 1,
        ActionContext.ofValidators(List.of(c -> false)));
    assertFalse(GateEvaluator.evaluateAll(null, failing, null, null));
  }

  @Test
  @DisplayName("Null validator entries and a null action context are tolerated")
  void nullValidatorEntriesAndNullActionContext() {
    ActionContext actionContext = mock(ActionContext.class);
    List<Predicate<ActionGateContext>> validators = Arrays.asList(null, c -> true);
    when(actionContext.gateValidators()).thenReturn(validators);
    ActionGateContext withNulls = mock(ActionGateContext.class);
    when(withNulls.context()).thenReturn(actionContext);
    when(withNulls.participantCount()).thenReturn(1);

    assertTrue(GateEvaluator.evaluateAll(List.of(Map.of("players", "1")), withNulls, null, null));
    assertTrue(GateEvaluator.evaluate(Map.of("players", "1"), withNulls, null, null));

    ActionGateContext noActionContext = mock(ActionGateContext.class);
    when(noActionContext.context()).thenReturn(null);
    assertTrue(GateEvaluator.evaluateAll(null, noActionContext, null, null));
    assertTrue(GateEvaluator.evaluate(Map.of("players", "0"), noActionContext, null, null));
  }

  @Test
  @DisplayName("evaluate runs passing validators before gates and supports a null gate context for counts")
  void evaluatePassingValidatorsAndNullContext() {
    ActionGateContext passing = new ActionGateContext(UUID.randomUUID(), "test", UUID.randomUUID(),
        10L, 290L, 0, true, 0.0, null, null, null, null, null, null, 1,
        ActionContext.ofValidators(List.of(c -> true, c -> true)));
    assertTrue(GateEvaluator.evaluate(Map.of("players", "1"), passing, null));

    assertTrue(GateEvaluator.evaluate(Map.of("players", "0"), null, null));
    assertFalse(GateEvaluator.evaluate(Map.of("players", "1"), null, null));
  }

  @Test
  @DisplayName("Scoreboard gate rejects malformed blocks and accepts the range alias")
  void scoreboardMalformedBlocks() {
    ActionGateContext c = ctx();
    assertFalse(GateEvaluator.evaluate(Map.of("scoreboard", "rtp_violations"), c, null));
    assertFalse(GateEvaluator.evaluate(Map.of("scoreboard", Map.of("matches", "0")), c, null));
    assertFalse(GateEvaluator.evaluate(Map.of("scoreboard", Map.of("objective", "rtp_violations")), c, null));
    assertTrue(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_violations", "range", "0..1")), c, null));
  }

  @Test
  @DisplayName("rtp_in_bounds reports 0 when out of bounds")
  void scoreboardInBoundsFalse() {
    ActionGateContext out = new ActionGateContext(UUID.randomUUID(), "test", UUID.randomUUID(), 1L, 1L, 0, false, 0.0);
    assertTrue(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_in_bounds", "matches", "0")), out, null));
  }

  @Test
  @DisplayName("rtp_session_id clamps Integer.MIN_VALUE hashes and treats a null session as 0")
  void scoreboardSessionIdEdgeCases() {
    UUID minHash = new UUID(0x8000000000000000L, 0L);
    assertEquals(Integer.MIN_VALUE, minHash.hashCode());
    ActionGateContext c = new ActionGateContext(minHash, "test", UUID.randomUUID(), 1L, 1L, 0, true, 0.0);
    assertTrue(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_session_id", "matches", "== " + Integer.MAX_VALUE)), c, null));

    ActionGateContext noSession = mock(ActionGateContext.class);
    when(noSession.sessionId()).thenReturn(null);
    assertTrue(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_session_id", "matches", "0")), noSession, null));
  }

  @Test
  @DisplayName("rtp_alive counts online participants, falls back to session size, and defaults to 1")
  void scoreboardAliveWithActionService() {
    UUID online = UUID.randomUUID();
    UUID offline = UUID.randomUUID();
    UUID missing = UUID.randomUUID();
    RTPPlayer onlinePlayer = player(online, null, true);
    RTPPlayer offlinePlayer = player(offline, null, false);
    when(accessor.getPlayer(online)).thenReturn(onlinePlayer);
    when(accessor.getPlayer(offline)).thenReturn(offlinePlayer);
    when(accessor.getPlayer(missing)).thenReturn(null);

    ActionSession mixed = mock(ActionSession.class);
    when(mixed.participants()).thenReturn(List.of(online, offline, missing));
    ActionSession allOffline = mock(ActionSession.class);
    when(allOffline.participants()).thenReturn(List.of(offline, missing));

    ActionGateContext mixedCtx = ctx();
    ActionGateContext offlineCtx = ctx();
    ActionGateContext unknownCtx = ctx();
    ActionService service = mock(ActionService.class);
    when(service.getSession(mixedCtx.sessionId())).thenReturn(Optional.of(mixed));
    when(service.getSession(offlineCtx.sessionId())).thenReturn(Optional.of(allOffline));
    when(service.getSession(unknownCtx.sessionId())).thenReturn(Optional.empty());

    RTPRuntime.setServerAccessor(accessor);
    RTPAPI.actionService = service;
    Map<String, Object> eq1 = Map.of("scoreboard", Map.of("objective", "rtp_alive", "matches", "== 1"));
    Map<String, Object> eq2 = Map.of("scoreboard", Map.of("objective", "rtp_alive", "matches", "== 2"));
    assertTrue(GateEvaluator.evaluate(eq1, mixedCtx, null));
    assertTrue(GateEvaluator.evaluate(eq2, offlineCtx, null));
    assertTrue(GateEvaluator.evaluate(eq1, unknownCtx, null));

    RTPRuntime.setServerAccessor(null);
    assertTrue(GateEvaluator.evaluate(eq1, mixedCtx, null));

    RTPRuntime.setServerAccessor(accessor);
    RTPAPI.actionService = null;
    assertTrue(GateEvaluator.evaluate(eq1, mixedCtx, null));
  }

  @Test
  @DisplayName("Custom scoreboard objectives read the live score when accessor and participant are present")
  void scoreboardCustomObjective() {
    UUID pid = UUID.randomUUID();
    when(accessor.getScoreboardScore(pid, "kills")).thenReturn(7);
    when(accessor.getScoreboardScore(any(UUID.class), eq("deaths"))).thenReturn(null);
    RTPRuntime.setServerAccessor(accessor);

    ActionGateContext c = new ActionGateContext(UUID.randomUUID(), "test", pid, 1L, 1L, 0, true, 0.0);
    assertTrue(GateEvaluator.evaluate(Map.of("scoreboard", Map.of("objective", "kills", "matches", "== 7")), c, null));
    assertTrue(GateEvaluator.evaluate(Map.of("scoreboard", Map.of("objective", "deaths", "matches", "0")), c, null));

    ActionGateContext noParticipant = new ActionGateContext(UUID.randomUUID(), "test", null, 1L, 1L, 0, true, 0.0);
    assertTrue(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "kills", "matches", "0")), noParticipant, null));
  }

  @Test
  @DisplayName("Spatial gate rejects non-map values and resolves anchor and two-part coordinate targets")
  void spatialTargets() {
    ActionGateContext c = ctxAt(103.0, 64.0, 204.0, 64.0, UUID.randomUUID());
    assertFalse(GateEvaluator.evaluate(Map.of("spatial", "near"), c, null));
    assertTrue(GateEvaluator.evaluate(Map.of("spatial", Map.of("target", "ANCHOR", "distance", "== 4")), c, null));
    assertTrue(GateEvaluator.evaluate(Map.of("spatial", Map.of("target", "100 200", "distance", "== 5")), c, null));

    ActionGateContext noCoords = ctx();
    assertFalse(GateEvaluator.evaluate(Map.of("spatial", Map.of("target", "1,2,3", "distance", "< 5")), noCoords, null));
    ActionGateContext noZ = ctxAt(1.0, 64.0, null, 64.0, UUID.randomUUID());
    assertFalse(GateEvaluator.evaluate(Map.of("spatial", Map.of("target", "1,2,3", "distance", "< 5")), noZ, null));
  }

  @Test
  @DisplayName("Spatial elevationDelta defaults to 0 when current or anchor Y is unknown")
  void spatialElevationDeltaDefaults() {
    Map<String, Object> zero = Map.of("spatial", Map.of("elevationDelta", "== 0"));
    assertTrue(GateEvaluator.evaluate(zero, ctx(), null));
    assertTrue(GateEvaluator.evaluate(zero, ctxAt(0.0, 70.0, 0.0, null, UUID.randomUUID()), null));
  }

  @Test
  @DisplayName("Spatial shape that is not a map fails closed; shape map without coordinates passes")
  void spatialShapeVariants() {
    assertFalse(GateEvaluator.evaluate(Map.of("spatial", Map.of("shape", "CIRCLE")), ctx(), null));
    assertTrue(GateEvaluator.evaluate(
        Map.of("spatial", Map.of("shape", Map.of("name", "CIRCLE", "radius", 100, "centerradius", 0))), ctx(), null));
  }

  @Test
  @DisplayName("Spatial region reference: missing region passes alone and fails with playerCount")
  void spatialMissingRegion() {
    assertTrue(GateEvaluator.evaluate(Map.of("spatial", Map.of("region", "no_such_region_xyz")), ctx(), null));
    assertFalse(GateEvaluator.evaluate(
        Map.of("spatial", Map.of("region", "no_such_region_xyz", "playerCount", ">= 0")), ctx(), null));
  }

  @Test
  @DisplayName("Spatial region playerCount counts online players in the shape, filtered by world")
  void spatialRegionPlayerCount() {
    Region region = mock(Region.class);
    Shape<?> shape = mock(Shape.class);
    when(shape.contains(anyInt(), anyInt())).thenReturn(false);
    when(shape.contains(0, 0)).thenReturn(true);
    doReturn(shape).when(region).getShape();
    ActiveSelection.get().permRegionLookup.put(REGION_KEY, region);

    // Partial coordinates skip the containment check; no accessor means zero players.
    ActionGateContext partial = ctxAt(5.0, 64.0, null, 64.0, UUID.randomUUID());
    assertTrue(GateEvaluator.evaluate(
        Map.of("spatial", Map.of("region", REGION_KEY, "playerCount", "0")), partial, null));
    assertFalse(GateEvaluator.evaluate(
        Map.of("spatial", Map.of("region", REGION_KEY, "playerCount", ">= 1")), partial, null));

    RTPWorld<?> overworld = world("world");
    RTPWorld<?> nether = world("nether");
    List<RTPPlayer> online = Arrays.asList(
        null,
        player(UUID.randomUUID(), null, true),
        player(UUID.randomUUID(), new RTPLocation(null, 1, 64, 1), true),
        player(UUID.randomUUID(), new RTPLocation(nether, 2, 64, 2), true),
        player(UUID.randomUUID(), new RTPLocation(overworld, 3, 64, 3), true),
        player(UUID.randomUUID(), new RTPLocation(overworld, 1600, 64, 1600), true));
    doReturn(online).when(accessor).getOnlinePlayers();
    RTPRuntime.setServerAccessor(accessor);

    assertTrue(GateEvaluator.evaluate(
        Map.of("spatial", Map.of("region", REGION_KEY, "world", "WORLD", "playerCount", "== 1")), partial, null));
    assertTrue(GateEvaluator.evaluate(
        Map.of("spatial", Map.of("region", REGION_KEY, "playerCount", "== 2")), partial, null));

    ActionGateContext outside = ctxAt(500.0, 64.0, 500.0, 64.0, UUID.randomUUID());
    assertFalse(GateEvaluator.evaluate(Map.of("spatial", Map.of("region", REGION_KEY)), outside, null));
  }

  @Test
  @DisplayName("Time and predicate gates fail closed on malformed values or a missing registry")
  void timeAndPredicateFailClosed() {
    assertFalse(GateEvaluator.evaluate(Map.of("time", ">= 5s"), ctx(), null));
    assertFalse(GateEvaluator.evaluate(Map.of("predicate", "anything"), ctx(), null));
  }

  @Test
  @DisplayName("Participant count gate fails closed on maps without an expression, null and blank values")
  void participantGateFailsClosed() {
    assertFalse(GateEvaluator.evaluate(Map.of("players", Map.of("other", "1")), ctx(), null));
    assertFalse(GateEvaluator.evaluate(Map.of("players", "  "), ctx(), null));
    Map<String, Object> nullValue = new HashMap<>();
    nullValue.put("participants", null);
    assertFalse(GateEvaluator.evaluate(nullValue, ctx(), null));
  }

  @Test
  @DisplayName("Command gate fails closed without commands or without an accessor")
  void commandGateFailsClosed() {
    assertFalse(GateEvaluator.evaluate(Map.of("command", 42), ctx(), null));
    assertFalse(GateEvaluator.evaluate(Map.of("command", "say hi"), ctx(), null));
  }

  @Test
  @DisplayName("Command gate dispatches list entries, skipping null and blank commands")
  void commandGateListsSkipNullAndBlank() {
    when(accessor.executeCommand(any(UUID.class), anyString())).thenReturn(true);
    RTPRuntime.setServerAccessor(accessor);

    Map<String, Object> execMap = new HashMap<>();
    execMap.put("execute", Arrays.asList("cmd_a", null));
    assertTrue(GateEvaluator.evaluate(Map.of("command", execMap), ctx(), null, null));
    assertTrue(GateEvaluator.evaluate(Map.of("command", Arrays.asList(null, "  ", "cmd_b")), ctx(), null,
        Map.of("extra", "value")));

    verify(accessor).executeCommand(any(UUID.class), eq("cmd_a"));
    verify(accessor).executeCommand(any(UUID.class), eq("cmd_b"));
  }

  @Test
  @DisplayName("Command gate substitutes tokens, skips wildcard targets and fails on dispatch failure")
  void commandGateSubstitutionAndFailure() {
    when(accessor.executeCommand(any(UUID.class), anyString())).thenReturn(false);
    RTPRuntime.setServerAccessor(accessor);

    ActionGateContext noParticipant = new ActionGateContext(UUID.randomUUID(), "test", null, 1L, 1L, 0, true, 0.0);
    assertTrue(GateEvaluator.evaluate(Map.of("command", "msg [target_name] hi"), noParticipant, null));
    verify(accessor, never()).executeCommand(any(UUID.class), anyString());

    assertFalse(GateEvaluator.evaluate(Map.of("command", "say [extra]"), noParticipant, null, Map.of("extra", "ok")));
    verify(accessor).executeCommand(any(UUID.class), eq("say ok"));
  }

  @Test
  @DisplayName("Command gate skips a target placeholder composed by substitution instead of dispatching it")
  void commandGateSkipsComposedTargetPlaceholder() {
    when(accessor.executeCommand(any(UUID.class), anyString())).thenReturn(false);
    RTPRuntime.setServerAccessor(accessor);

    ActionGateContext noParticipant = new ActionGateContext(UUID.randomUUID(), "test", null, 1L, 1L, 0, true, 0.0);
    assertTrue(GateEvaluator.evaluate(Map.of("command", "msg [target_[n]] hi"), noParticipant, null, Map.of("n", "1")));
    verify(accessor, never()).executeCommand(any(UUID.class), anyString());
  }

  @Test
  @DisplayName("Entity predicates without a usable tag fall back to command dispatch")
  void entityPredicateFallsBackWithoutTag() {
    when(accessor.executeCommand(any(UUID.class), anyString())).thenReturn(true);
    RTPRuntime.setServerAccessor(accessor);

    assertTrue(GateEvaluator.evaluate(Map.of("command", "execute if entity @a"), ctx(), null));
    assertTrue(GateEvaluator.evaluate(Map.of("command", "execute if entity @a[]"), ctx(), null));
    assertTrue(GateEvaluator.evaluate(Map.of("command", "execute if entity @a[limit=1,sort,tag=]"), ctx(), null));

    verify(accessor).executeCommand(any(UUID.class), eq("execute if entity @a"));
    verify(accessor).executeCommand(any(UUID.class), eq("execute if entity @a[]"));
    verify(accessor).executeCommand(any(UUID.class), eq("execute if entity @a[limit=1,sort,tag=]"));
  }

  @Test
  @DisplayName("Named entity predicates check the named player's scoreboard tags")
  void entityPredicateWithName() {
    UUID bobId = UUID.randomUUID();
    RTPPlayer bob = player(bobId, null, true);
    when(accessor.getPlayer("Bob")).thenReturn(bob);
    when(accessor.getPlayer("Ghost")).thenReturn(null);
    RTPRuntime.setServerAccessor(accessor);

    assertFalse(GateEvaluator.evaluate(Map.of("command", "execute if entity @a[name=Ghost,tag=t]"), ctx(), null));

    when(accessor.getScoreboardTags(bobId)).thenReturn(null);
    assertFalse(GateEvaluator.evaluate(Map.of("command", "execute if entity @a[name=Bob,tag=t]"), ctx(), null));

    when(accessor.getScoreboardTags(bobId)).thenReturn(Set.of("other"));
    assertFalse(GateEvaluator.evaluate(Map.of("command", "execute if entity @p[name=Bob,tag=t]"), ctx(), null));

    when(accessor.getScoreboardTags(bobId)).thenReturn(Set.of("t"));
    assertTrue(GateEvaluator.evaluate(Map.of("command", "execute if entity @p[name=Bob,tag=t]"), ctx(), null));
    assertFalse(GateEvaluator.evaluate(Map.of("command", "execute unless entity @p[name=Bob,tag=t]"), ctx(), null));
    verify(accessor, never()).executeCommand(any(UUID.class), anyString());
  }

  @Test
  @DisplayName("Unnamed entity predicates scan online players, skipping nulls and untagged players")
  void entityPredicateScansOnlinePlayers() {
    UUID untaggedId = UUID.randomUUID();
    UUID nullTagsId = UUID.randomUUID();
    UUID taggedId = UUID.randomUUID();
    RTPPlayer untagged = player(untaggedId, null, true);
    RTPPlayer nullTags = player(nullTagsId, null, true);
    RTPPlayer tagged = player(taggedId, null, true);
    when(accessor.getScoreboardTags(untaggedId)).thenReturn(Collections.emptySet());
    when(accessor.getScoreboardTags(nullTagsId)).thenReturn(null);
    when(accessor.getScoreboardTags(taggedId)).thenReturn(Set.of("t"));
    RTPRuntime.setServerAccessor(accessor);

    doReturn(Arrays.asList(null, untagged, nullTags)).when(accessor).getOnlinePlayers();
    assertFalse(GateEvaluator.evaluate(Map.of("command", "execute if entity @a[name=,tag=t]"), ctx(), null));

    doReturn(Arrays.asList(null, untagged, nullTags, tagged)).when(accessor).getOnlinePlayers();
    assertTrue(GateEvaluator.evaluate(Map.of("command", "execute if entity @a[name=,tag=t]"), ctx(), null));
    verify(accessor, never()).executeCommand(any(UUID.class), anyString());
  }
}
