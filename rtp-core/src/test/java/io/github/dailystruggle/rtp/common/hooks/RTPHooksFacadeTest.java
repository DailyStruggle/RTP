package io.github.dailystruggle.rtp.common.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.economy.RTPEconomy;
import io.github.dailystruggle.rtp.api.hooks.RTPHooks;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Smoke test for the unified external-hook facade introduced in ADR-026.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@code RTPAPI.hooks()} throws {@link IllegalStateException} when core
 *       has not registered the facade (REQ-RTP-S-006).</li>
 *   <li>The {@code RTP} class' static initialiser publishes a non-null facade.</li>
 *   <li>The verifier registry is bidirectionally compatible with the legacy
 *       {@link GlobalRegionVerifiers} static API (backward compat per ADR-026).</li>
 *   <li>Economy, placeholder, world-border and anvil-prefilter registries each
 *       round-trip a registration.</li>
 * </ul>
 */
class RTPHooksFacadeTest {

  private RTPHooks savedHooks;
  private RTPEconomy savedEconomy;

  /**
   * Force {@link RTP} class initialisation before any test saves RTPAPI state.
   * Class literal references do not trigger {@code <clinit>}; reading a static
   * field does. Same pattern as {@code RTPAPIGuardTest}.
   */
  @BeforeAll
  static void ensureRTPClassInitialised() {
    RTP.serverId.hashCode();
  }

  @BeforeEach
  void setUp() {
    savedHooks = RTPAPI.hooks;
    savedEconomy = RTP.economy;
    GlobalRegionVerifiers.clearGlobalRegionVerifiers();
  }

  @AfterEach
  void tearDown() {
    GlobalRegionVerifiers.clearGlobalRegionVerifiers();
    RTPAPI.hooks = savedHooks;
    RTP.economy = savedEconomy;
  }

  @Test
  @DisplayName("RTPAPI.hooks() throws when core delegate is not yet registered (S-006)")
  void hooks_throwsWhenUnregistered() {
    RTPAPI.hooks = null;
    try {
      assertThrows(IllegalStateException.class, RTPAPI::hooks);
    } finally {
      RTPAPI.hooks = savedHooks;
    }
  }

  @Test
  @DisplayName("RTP static block publishes a non-null DefaultRTPHooks facade")
  void hooks_publishedByCoreStaticInit() {
    // Reference RTP.class to force static init (the field is set there).
    Class<?> ignored = RTP.class;
    assertNotNull(ignored);
    assertNotNull(RTPAPI.hooks);
    RTPHooks h = RTPAPI.hooks();
    assertNotNull(h.verifiers());
    assertNotNull(h.economy());
    assertNotNull(h.placeholders());
    assertNotNull(h.worldBorder());
    assertNotNull(h.anvilPrefilter());
    assertNotNull(h.pvpCombatState());
    assertNotNull(h.platformCreator());
  }

  @Test
  @DisplayName("Verifier registry round-trips with GlobalRegionVerifiers static API")
  void verifierRegistry_backwardCompatible() {
    Class<?> ignored = RTP.class; // force init
    assertNotNull(ignored);
    RTPHooks hooks = RTPAPI.hooks();

    AtomicInteger calls = new AtomicInteger();
    java.util.function.Predicate<RTPCoords> p = c -> { calls.incrementAndGet(); return true; };

    // Register through the new API; legacy size() reflects it.
    hooks.verifiers().register(p);
    assertEquals(1, hooks.verifiers().size());

    // Register through the legacy static; new size() reflects it.
    GlobalRegionVerifiers.addGlobalRegionVerifier(c -> true);
    assertEquals(2, hooks.verifiers().size());

    // Clear via the new API; legacy state cleared.
    hooks.verifiers().clear();
    assertEquals(0, hooks.verifiers().size());
    assertEquals(0, GlobalRegionVerifiers.registeredCount());
  }

  @Test
  @DisplayName("Economy registry binds and unbinds RTP.economy")
  void economyRegistry_bindAndClear() {
    Class<?> ignored = RTP.class;
    assertNotNull(ignored);
    RTPHooks hooks = RTPAPI.hooks();

    RTPEconomy fake = new RTPEconomy() {
      @Override public void give(UUID p, double m) {}
      @Override public boolean take(UUID p, double m) { return true; }
      @Override public double bal(UUID p) { return 0; }
    };
    hooks.economy().bind(fake);
    assertSame(fake, RTP.economy);
    assertSame(fake, hooks.economy().current());
    hooks.economy().clear();
    assertNull(RTP.economy);
    assertThrows(IllegalArgumentException.class, () -> hooks.economy().bind(null));
  }

  @Test
  @DisplayName("Placeholder registry round-trips a resolver")
  void placeholderRegistry_resolves() {
    Class<?> ignored = RTP.class;
    assertNotNull(ignored);
    RTPHooks hooks = RTPAPI.hooks();
    UUID id = UUID.randomUUID();
    hooks.placeholders().register("hello", (uuid, key) -> "world:" + uuid);
    assertEquals("world:" + id, hooks.placeholders().resolve(id, "hello"));
    assertNull(hooks.placeholders().resolve(id, "missing"));
    assertTrue(hooks.placeholders().unregister("hello"));
    assertNull(hooks.placeholders().resolve(id, "hello"));
  }

  @Test
  @DisplayName("World-border and anvil pre-filter registries bind/clear")
  void singleBindingRegistries_roundTrip() {
    Class<?> ignored = RTP.class;
    assertNotNull(ignored);
    RTPHooks hooks = RTPAPI.hooks();

    hooks.worldBorder().bind((world, x, z) -> true);
    assertNotNull(hooks.worldBorder().current());
    hooks.worldBorder().clear();
    assertNull(hooks.worldBorder().current());

    hooks.anvilPrefilter().bind((world, cx, cz) ->
        io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry.Provider.Decision.UNKNOWN);
    assertNotNull(hooks.anvilPrefilter().current());
    hooks.anvilPrefilter().clear();
    assertNull(hooks.anvilPrefilter().current());
  }

  @Test
  @DisplayName("PvP combat-state registry binds, replaces native, and clears (null-guarded)")
  void pvpCombatStateRegistry_roundTrip() {
    Class<?> ignored = RTP.class;
    assertNotNull(ignored);
    RTPHooks hooks = RTPAPI.hooks();

    assertNull(hooks.pvpCombatState().current(), "no provider bound -> native fallback");
    UUID tagged = UUID.randomUUID();
    hooks.pvpCombatState().bind(uuid -> uuid.equals(tagged));
    assertNotNull(hooks.pvpCombatState().current());
    assertTrue(hooks.pvpCombatState().current().isInCombat(tagged));
    assertEquals(false, hooks.pvpCombatState().current().isInCombat(UUID.randomUUID()));
    hooks.pvpCombatState().clear();
    assertNull(hooks.pvpCombatState().current());
    assertThrows(IllegalArgumentException.class, () -> hooks.pvpCombatState().bind(null));
  }

  @Test
  @DisplayName("Platform-creator registry binds, reports the bound creator, and clears (null-guarded)")
  void platformCreatorRegistry_roundTrip() {
    Class<?> ignored = RTP.class;
    assertNotNull(ignored);
    RTPHooks hooks = RTPAPI.hooks();

    assertNull(hooks.platformCreator().current(), "no creator bound -> platform default");

    AtomicInteger built = new AtomicInteger();
    io.github.dailystruggle.rtp.api.platform.PlatformCreator creator =
        new io.github.dailystruggle.rtp.api.platform.PlatformCreator() {
          @Override public String creatorName() { return "TestPad"; }
          @Override public boolean createPlatform(
              io.github.dailystruggle.rtp.api.world.RTPLocation at) {
            built.incrementAndGet();
            return true;
          }
        };
    hooks.platformCreator().bind(creator);
    assertSame(creator, hooks.platformCreator().current());
    assertEquals("TestPad", hooks.platformCreator().current().creatorName());
    assertTrue(hooks.platformCreator().current().createPlatform(null));
    assertEquals(1, built.get());

    // The base interface's defaults decline: createPlatform(at) -> false, the two-phase
    // createPlatform(at, prepared) delegates to it, and prepare(at) yields a null handle.
    io.github.dailystruggle.rtp.api.platform.PlatformCreator base =
        new io.github.dailystruggle.rtp.api.platform.PlatformCreator() {};
    assertEquals(false, base.createPlatform(null));
    assertEquals(false, base.createPlatform(null, new Object()));
    assertNull(base.prepare(null).join(), "default prepare() completes with a null handle");

    // A two-phase creator: prepare() supplies the handle, createPlatform(at, prepared) consumes
    // it. This is the uniform path RTP drives for every creator (SchematicPaster included).
    AtomicInteger pasted = new AtomicInteger();
    io.github.dailystruggle.rtp.api.platform.PlatformCreator twoPhase =
        new io.github.dailystruggle.rtp.api.platform.PlatformCreator() {
          @Override public java.util.concurrent.CompletableFuture<?> prepare(
              io.github.dailystruggle.rtp.api.world.RTPLocation at) {
            return java.util.concurrent.CompletableFuture.completedFuture("handle");
          }
          @Override public boolean createPlatform(
              io.github.dailystruggle.rtp.api.world.RTPLocation at, Object prepared) {
            if (!"handle".equals(prepared)) return false;
            pasted.incrementAndGet();
            return true;
          }
        };
    assertEquals("handle", twoPhase.prepare(null).join());
    assertTrue(twoPhase.createPlatform(null, twoPhase.prepare(null).join()));
    assertEquals(1, pasted.get());
    // A null handle (failed/incomplete prepare) makes the two-phase creator decline.
    assertEquals(false, twoPhase.createPlatform(null, null));

    hooks.platformCreator().clear();
    assertNull(hooks.platformCreator().current());
    assertThrows(IllegalArgumentException.class, () -> hooks.platformCreator().bind(null));
  }
}
