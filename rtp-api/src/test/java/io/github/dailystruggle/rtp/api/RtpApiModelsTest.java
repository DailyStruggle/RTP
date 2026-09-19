package io.github.dailystruggle.rtp.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.network.RtpTriggerSource;
import io.github.dailystruggle.rtp.api.platform.BlockDelta;
import io.github.dailystruggle.rtp.api.platform.PendingPlatformRestore;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtpApiModelsTest {

  @Test
  @DisplayName("RtpTarget factories, kinds, equality, and validation")
  void testRtpTarget() {
    RtpTarget def = RtpTarget.defaultRegion();
    assertEquals(RtpTarget.Kind.DEFAULT, def.kind());
    assertNull(def.name());
    assertNull(def.serverId());
    assertEquals(def, RtpTarget.defaultRegion());

    RtpTarget reg = RtpTarget.region("custom-region");
    assertEquals(RtpTarget.Kind.REGION, reg.kind());
    assertEquals("custom-region", reg.name());
    assertNull(reg.serverId());
    assertNotEquals(def, reg);
    assertEquals(reg, RtpTarget.region("custom-region"));
    assertEquals(reg.hashCode(), RtpTarget.region("custom-region").hashCode());

    RtpTarget world = RtpTarget.world("world_nether");
    assertEquals(RtpTarget.Kind.WORLD, world.kind());
    assertEquals("world_nether", world.name());
    assertNull(world.serverId());

    RtpTarget net = RtpTarget.network("survival-1", "wild");
    assertEquals(RtpTarget.Kind.NETWORK, net.kind());
    assertEquals("wild", net.name());
    assertEquals("survival-1", net.serverId());

    assertTrue(net.toString().contains("survival-1"));

    // Equals and hashcode branches
    RtpTarget netSame = RtpTarget.network("survival-1", "wild");
    RtpTarget netDiffServer = RtpTarget.network("survival-2", "wild");
    RtpTarget netDiffRegion = RtpTarget.network("survival-1", "other");
    assertEquals(net, net);
    assertEquals(net, netSame);
    assertEquals(net.hashCode(), netSame.hashCode());
    assertFalse(net.equals(null));
    assertFalse(net.equals("diff"));
    assertFalse(net.equals(netDiffServer));
    assertFalse(net.equals(netDiffRegion));
    assertFalse(net.equals(world));

    // Validation
    assertThrows(IllegalArgumentException.class, () -> RtpTarget.region(null));
    assertThrows(IllegalArgumentException.class, () -> RtpTarget.region("  "));
    assertThrows(IllegalArgumentException.class, () -> RtpTarget.world((String) null));
    assertThrows(IllegalArgumentException.class, () -> RtpTarget.world("  "));
    assertThrows(IllegalArgumentException.class, () -> RtpTarget.world((RTPWorld<?>) null));
    assertThrows(IllegalArgumentException.class, () -> RtpTarget.network(null, "r"));
    assertThrows(IllegalArgumentException.class, () -> RtpTarget.network("s", null));
    assertThrows(IllegalArgumentException.class, () -> RtpTarget.network("  ", "r"));
    assertThrows(IllegalArgumentException.class, () -> RtpTarget.network("s", "  "));
  }

  @Test
  @DisplayName("RTPResult success, queued, failure, and enums")
  void testRtpResult() {
    StubWorld stubWorld = new StubWorld();
    RTPLocation loc = new RTPLocation(stubWorld, 100, 64, 200);
    RTPResult success = RTPResult.success(loc);
    assertTrue(success.isSuccess());
    assertFalse(success.isQueued());
    assertEquals(RTPResult.Reason.SUCCESS, success.reason());
    assertEquals(loc, success.location());
    assertEquals("ok", success.message());
    assertTrue(success.toString().contains("SUCCESS"));

    RTPResult queued = RTPResult.queued("waiting for backend");
    assertFalse(queued.isSuccess());
    assertTrue(queued.isQueued());
    assertEquals(RTPResult.Reason.QUEUED, queued.reason());
    assertNull(queued.location());
    assertEquals("waiting for backend", queued.message());

    RTPResult fail = RTPResult.failure(RTPResult.Reason.COOLDOWN, "wait 10s");
    assertFalse(fail.isSuccess());
    assertFalse(fail.isQueued());
    assertEquals(RTPResult.Reason.COOLDOWN, fail.reason());
    assertNull(fail.location());
    assertEquals("wait 10s", fail.message());

    // Validation
    assertThrows(IllegalArgumentException.class, () -> RTPResult.failure(null, "msg"));
    assertThrows(IllegalArgumentException.class, () -> RTPResult.failure(RTPResult.Reason.SUCCESS, "msg"));

    // Check all Reason enum values
    for (RTPResult.Reason r : RTPResult.Reason.values()) {
      assertNotNull(r.name());
    }
  }

  @Test
  @DisplayName("RtpTargetStatus availability, cost, cooldown, display hints, and equality")
  void testRtpTargetStatus() {
    RtpTargetStatus s1 = new RtpTargetStatus(RtpTargetStatus.Availability.READY, 0L, 50.0);
    assertTrue(s1.isReady());
    assertEquals(0L, s1.remainingCooldownMillis());
    assertEquals(50.0, s1.cost());
    assertNull(s1.iconBlock());
    assertNull(s1.environment());
    assertNull(s1.label());

    RtpTargetStatus s2 = new RtpTargetStatus(
        RtpTargetStatus.Availability.ON_COOLDOWN, 5000L, -10.0, "DIRT", "NORMAL", "Wilderness");
    assertFalse(s2.isReady());
    assertEquals(5000L, s2.remainingCooldownMillis());
    assertEquals(0.0, s2.cost()); // negative clamped to 0
    assertEquals("DIRT", s2.iconBlock());
    assertEquals("NORMAL", s2.environment());
    assertEquals("Wilderness", s2.label());

    RtpTargetStatus s3 = new RtpTargetStatus(
        RtpTargetStatus.Availability.ON_COOLDOWN, 5000L, 0.0, "DIRT", "NORMAL", "Wilderness");
    assertEquals(s2, s3);
    assertEquals(s2.hashCode(), s3.hashCode());
    assertNotEquals(s1, s2);
    assertTrue(s2.toString().contains("DIRT"));

    // Equals edge cases on RtpTargetStatus
    RtpTargetStatus sDiffAvail = new RtpTargetStatus(
        RtpTargetStatus.Availability.READY, 5000L, 0.0, "DIRT", "NORMAL", "Wilderness");
    RtpTargetStatus sDiffCd = new RtpTargetStatus(
        RtpTargetStatus.Availability.ON_COOLDOWN, 6000L, 0.0, "DIRT", "NORMAL", "Wilderness");
    RtpTargetStatus sDiffCost = new RtpTargetStatus(
        RtpTargetStatus.Availability.ON_COOLDOWN, 5000L, 10.0, "DIRT", "NORMAL", "Wilderness");
    RtpTargetStatus sDiffIcon = new RtpTargetStatus(
        RtpTargetStatus.Availability.ON_COOLDOWN, 5000L, 0.0, "STONE", "NORMAL", "Wilderness");
    RtpTargetStatus sDiffEnv = new RtpTargetStatus(
        RtpTargetStatus.Availability.ON_COOLDOWN, 5000L, 0.0, "DIRT", "NETHER", "Wilderness");
    RtpTargetStatus sDiffLabel = new RtpTargetStatus(
        RtpTargetStatus.Availability.ON_COOLDOWN, 5000L, 0.0, "DIRT", "NORMAL", "Other");

    assertEquals(s2, s2);
    assertFalse(s2.equals(null));
    assertFalse(s2.equals("other"));
    assertFalse(s2.equals(sDiffAvail));
    assertFalse(s2.equals(sDiffCd));
    assertFalse(s2.equals(sDiffCost));
    assertFalse(s2.equals(sDiffIcon));
    assertFalse(s2.equals(sDiffEnv));
    assertFalse(s2.equals(sDiffLabel));

    RtpTargetStatus sNaN = new RtpTargetStatus(
        RtpTargetStatus.Availability.READY, 0L, Double.NaN, " ", " ", " ");
    assertEquals(0.0, sNaN.cost());
    assertNull(sNaN.iconBlock());
    assertNull(sNaN.environment());
    assertNull(sNaN.label());

    // Validation
    assertThrows(IllegalArgumentException.class, () -> new RtpTargetStatus(null, 0L, 0.0));
  }

  @Test
  @DisplayName("RtpTriggerSource kinds, triggers, and values")
  void testRtpTriggerSource() {
    UUID playerId = UUID.randomUUID();
    RtpTriggerSource.Trigger t1 = RtpTriggerSource.Trigger.ofCommand(playerId);
    assertEquals(RtpTriggerSource.Kind.COMMAND, t1.kind());
    assertEquals(playerId, t1.playerId());
    assertNull(t1.regionKey());
    assertNull(t1.worldKey());

    RtpTriggerSource.Trigger t2 = RtpTriggerSource.Trigger.ofJoin(playerId);
    assertEquals(RtpTriggerSource.Kind.JOIN, t2.kind());

    RtpTriggerSource.Trigger t3 = new RtpTriggerSource.Trigger(playerId, RtpTriggerSource.Kind.EVENT, "r", "w");
    assertEquals("r", t3.regionKey());
    assertEquals("w", t3.worldKey());

    for (RtpTriggerSource.Kind k : RtpTriggerSource.Kind.values()) {
      assertNotNull(k.name());
    }
  }

  @Test
  @DisplayName("DownloadInfo and sources")
  void testDownloadInfo() throws Exception {
    java.lang.reflect.Constructor<DownloadInfo> ctor = DownloadInfo.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    DownloadInfo instance = ctor.newInstance();
    assertNotNull(instance);

    assertEquals(DownloadInfo.Source.DEV, DownloadInfo.source());
    assertEquals(DownloadInfo.SPIGOT_BBB_USER, DownloadInfo.userId());
    assertEquals(DownloadInfo.SPIGOT_BBB_NONCE, DownloadInfo.nonce());
    assertEquals("", DownloadInfo.resourceId());
    assertEquals("", DownloadInfo.timestamp());

    for (DownloadInfo.Source s : DownloadInfo.Source.values()) {
      assertNotNull(s.name());
    }
  }

  @Test
  @DisplayName("BlockDelta and PendingPlatformRestore lifecycle")
  void testBlockDeltaAndPlatformRestore() {
    BlockDelta delta1 = new BlockDelta(10, 64, 20, "minecraft:stone");
    BlockDelta delta2 = new BlockDelta(10, 64, 20, "minecraft:stone");
    assertEquals(10, delta1.x());
    assertEquals(64, delta1.y());
    assertEquals(20, delta1.z());
    assertEquals("minecraft:stone", delta1.token());
    assertEquals(delta1, delta2);
    assertEquals(delta1.hashCode(), delta2.hashCode());
    assertTrue(delta1.toString().contains("BlockDelta"));

    BlockDelta deltaDiffX = new BlockDelta(11, 64, 20, "minecraft:stone");
    BlockDelta deltaDiffY = new BlockDelta(10, 65, 20, "minecraft:stone");
    BlockDelta deltaDiffZ = new BlockDelta(10, 64, 21, "minecraft:stone");
    BlockDelta deltaDiffToken = new BlockDelta(10, 64, 20, "minecraft:dirt");
    assertFalse(delta1.equals(null));
    assertFalse(delta1.equals("diff"));
    assertFalse(delta1.equals(deltaDiffX));
    assertFalse(delta1.equals(deltaDiffY));
    assertFalse(delta1.equals(deltaDiffZ));
    assertFalse(delta1.equals(deltaDiffToken));

    UUID id = UUID.randomUUID();
    PendingPlatformRestore restore = new PendingPlatformRestore(
        id, "world", 0, 1, List.of(delta1), 2);
    assertEquals(id, restore.id());
    assertEquals("world", restore.worldName());
    assertEquals(0, restore.cx());
    assertEquals(1, restore.cz());
    assertEquals(1, restore.blocks().size());
    assertEquals(delta1, restore.blocks().get(0));
    assertEquals(2, restore.remainingSeconds());

    assertEquals(1, restore.decrement());
    assertEquals(0, restore.decrement());
    assertEquals(0, restore.decrement()); // floored at 0
    assertTrue(restore.toString().contains("PendingPlatformRestore"));
  }

  @Test
  @DisplayName("NetworkCommandHook routing results and triggers")
  void testNetworkCommandHook() {
    UUID pid = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.network.NetworkCommandHook hook = io.github.dailystruggle.rtp.api.network.NetworkCommandHook.LOCAL_ONLY;
    assertEquals(io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.local(), hook.route(pid, java.util.Map.of()));

    UUID corr = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.CrossServer cs1 =
        io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.crossServer(corr, "wild", "node-1");
    assertEquals(corr, cs1.correlationId());
    assertEquals("wild", cs1.regionKey().orElse(null));
    assertEquals("node-1", cs1.serverHint().orElse(null));

    io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.CrossServer csEmpty =
        io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.crossServer(corr, null, "");
    assertTrue(csEmpty.regionKey().isEmpty());
    assertTrue(csEmpty.serverHint().isEmpty());

    io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.Reject rej =
        io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.reject("msg.invalid", "foo");
    assertEquals("msg.invalid", rej.messageKey());
    assertEquals("foo", rej.placeholder());

    io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.Reject rejNull =
        io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.reject("msg.invalid", null);
    assertEquals("", rejNull.placeholder());

    assertThrows(NullPointerException.class, () -> io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.crossServer(null, "a", "b"));
    assertThrows(NullPointerException.class, () -> io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.reject(null, "a"));

    // Trigger equals & hashcode
    RtpTriggerSource.Trigger trig1 = new RtpTriggerSource.Trigger(pid, RtpTriggerSource.Kind.COMMAND, "r", "w");
    RtpTriggerSource.Trigger trigSame = new RtpTriggerSource.Trigger(pid, RtpTriggerSource.Kind.COMMAND, "r", "w");
    RtpTriggerSource.Trigger trigDiff = new RtpTriggerSource.Trigger(pid, RtpTriggerSource.Kind.JOIN, "r", "w");
    assertEquals(trig1, trig1);
    assertEquals(trig1, trigSame);
    assertEquals(trig1.hashCode(), trigSame.hashCode());
    assertFalse(trig1.equals(null));
    assertFalse(trig1.equals("other"));
    assertFalse(trig1.equals(trigDiff));
  }

  @Test
  @DisplayName("GenerationContext and GenerationResult models")
  void testGenerationContextAndResult() {
    StubPlayer player = new StubPlayer();
    io.github.dailystruggle.rtp.api.selection.GenerationContext ctx =
        new io.github.dailystruggle.rtp.api.selection.GenerationContext(player, player, java.util.Set.of("plains"));
    assertEquals(player, ctx.sender());
    assertEquals(player, ctx.player());
    assertEquals(java.util.Set.of("plains"), ctx.biomeNames());

    StubWorld w = new StubWorld();
    io.github.dailystruggle.rtp.api.world.ChunkSet cs = new io.github.dailystruggle.rtp.api.world.ChunkSet(
        w, 0, 0, java.util.List.of(), new java.util.concurrent.CompletableFuture<>());
    io.github.dailystruggle.rtp.api.world.RTPCoords coords = new io.github.dailystruggle.rtp.api.world.RTPCoords("world", 1, 2, 3);
    io.github.dailystruggle.rtp.api.selection.GenerationResult res1 =
        new io.github.dailystruggle.rtp.api.selection.GenerationResult(coords, 5, cs);
    assertEquals(coords, res1.coords());
    assertEquals(5L, res1.attempts());
    assertEquals(cs, res1.verifiedChunks());
    assertNull(res1.reservation());
  }

  @Test
  @DisplayName("RTPPlayer default interface methods")
  void testRtpPlayerDefaults() {
    StubPlayer p = new StubPlayer();
    StubWorld w = new StubWorld();
    RTPLocation loc = new RTPLocation(w, 1, 2, 3);

    p.setRespawnLocation(loc);
    assertNull(p.getClientBlock(loc));
    p.sendClientBlockChange(loc, "minecraft:stone");
    p.sendClientBlockChanges(null);
    p.sendClientBlockChanges(java.util.Map.of(loc, "minecraft:stone"));
    p.showProgressBar("id", "title", 0.5);
    p.clearProgressBar("id");
    assertEquals(-1, p.getViewDistance());
    p.setViewDistance(10);
    assertEquals(-1, p.getSendViewDistance());
    p.setSendViewDistance(10);
  }

  @Test
  @DisplayName("AnvilPrefilterRegistry, RegionVerifierRegistry, and PvPCombatAction")
  void testHookModels() {
    for (io.github.dailystruggle.rtp.api.hooks.PvPCombatAction a : io.github.dailystruggle.rtp.api.hooks.PvPCombatAction.values()) {
      assertNotNull(a.name());
    }
    assertEquals(io.github.dailystruggle.rtp.api.hooks.PvPCombatAction.DENY,
        io.github.dailystruggle.rtp.api.hooks.PvPCombatAction.valueOf("DENY"));

    for (io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry.Provider.Decision d :
        io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry.Provider.Decision.values()) {
      assertNotNull(d.name());
    }
  }

  private static final class StubPlayer implements io.github.dailystruggle.rtp.api.entity.RTPPlayer {
    @Override public java.util.concurrent.CompletableFuture<Boolean> setLocation(RTPLocation to) { return java.util.concurrent.CompletableFuture.completedFuture(true); }
    @Override public RTPLocation getLocation() { return null; }
    @Override public boolean isOnline() { return true; }
    @Override public String name() { return "test"; }
    @Override public UUID uuid() { return UUID.randomUUID(); }
    @Override public boolean hasPermission(String perm) { return true; }
    @Override public void sendMessage(String msg) { }
    @Override public long cooldown() { return 0; }
    @Override public long delay() { return 0; }
    @Override public java.util.Set<String> getEffectivePermissions() { return java.util.Set.of(); }
    @Override public void performCommand(io.github.dailystruggle.rtp.api.entity.RTPPlayer player, String command) { }
    @Override public StubPlayer clone() { return new StubPlayer(); }
  }

  private static final class StubWorld extends RTPWorld<String> {
    StubWorld() { super("stub"); }
    @Override protected java.util.concurrent.CompletableFuture<Void> setForceLoadedImpl(int cx, int cz, boolean f) {
      return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
    @Override public String name() { return "stub"; }
    @Override public UUID id() { return new UUID(0L, 0L); }
    @Override public java.util.concurrent.CompletableFuture<Long> getChunkAt(int cx, int cz) { return java.util.concurrent.CompletableFuture.completedFuture(0L); }
    @Override public java.util.concurrent.CompletableFuture<io.github.dailystruggle.rtp.api.world.ChunkSet> getChunkAtAsync(int cx, int cz) { return java.util.concurrent.CompletableFuture.completedFuture(null); }
    @Override public java.util.concurrent.CompletableFuture<Integer> getServerForceLoadedCount() { return java.util.concurrent.CompletableFuture.completedFuture(0); }
    @Override public io.github.dailystruggle.rtp.api.world.RTPChunk<?> getCachedChunk(long key) { return null; }
    @Override public void keepChunkAt(int cx, int cz) { }
    @Override public void forgetChunkAt(int cx, int cz) { }
    @Override public void forgetChunks() { }
    @Override public String getBiome(int x, int y, int z) { return ""; }
    @Override public void platform(RTPLocation location) { }
    @Override public boolean isInactive() { return false; }
    @Override public void save() { }
    @Override public int getMaxHeight() { return 320; }
    @Override public int getMinHeight() { return -64; }
    @Override public int getCacheSize() { return 0; }
    @Override public long getSeed() { return 0L; }
  }
}
