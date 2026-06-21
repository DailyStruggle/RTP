package io.github.dailystruggle.rtp.riftaddon;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * "World deconstruction" warmup effect: makes the terrain around a player appear to dissolve
 * into thin air for a few seconds, then snap back.
 *
 * <p><b>Platform-neutral.</b> This effect contains no {@code org.bukkit.*} (or any platform)
 * references. It resolves a platform-neutral {@link PlayerHandle} for its target exactly like
 * the built-in {@code GlideEffect} does, then drives the whole animation through the RTP SPI:
 * {@link RTPPlayer#getClientBlock(RTPLocation)} to snapshot what the client currently sees and
 * {@link RTPPlayer#sendClientBlockChanges(Map)} to push the dissolve (and later the restore) as
 * <b>binned</b> client-side block changes - one multi-block packet per chunk section, not one
 * packet per block.
 *
 * <p><b>Presentation only.</b> The dissolve is delivered with client-side fake block changes -
 * the server's real blocks are never touched. There are no physical block updates, no physics
 * checks, and no chunk loads on any region thread: only blocks already loaded around the
 * standing player are read (the SPI skips unloaded positions). The original client-side state is
 * restored after {@code SECONDS} via {@code RTP.scheduler}, and because the fakes are purely
 * visual the client also self-corrects on the next real chunk update, so a missed restore can
 * never corrupt the world.
 *
 * <p>Token / permission grammar (the {@code .} separator splits arguments, so all arguments are
 * whole numbers): {@code RIFT.<radius>.<seconds>}, e.g. {@code RIFT.4.3} dissolves a 4-block
 * cube radius for 3 seconds.
 */
public final class RiftEffect extends Effect<RiftEffect.Key> {

  /** Argument keys, in positional order for the {@code RIFT.<radius>.<seconds>} grammar. */
  public enum Key {
    /** Cube "radius" in blocks around the player (clamped to [1, 8]). */
    RADIUS,
    /** How long the dissolve is shown before the real blocks are restored, in seconds. */
    SECONDS
  }

  private static final int DEFAULT_RADIUS = 4;
  private static final int DEFAULT_SECONDS = 3;
  private static final int MAX_RADIUS = 8;

  /** Platform-neutral block-data string for the hollow "void" core of the rift. */
  private static final String AIR = "minecraft:air";

  /**
   * Dark block-data strings the rift's rim is dressed in, to theme the dissolve as a tear opening
   * into a void rather than a plain hole. A mix of sculk, obsidian, and black concrete; all are
   * vanilla blocks so the client-side fake renders identically everywhere, and one is picked
   * deterministically per position.
   */
  private static final String[] RIM_BLOCKS = {
    "minecraft:sculk",
    "minecraft:obsidian",
    "minecraft:black_concrete"
  };

  public RiftEffect() {
    super(new EnumMap<>(Key.class));
    data.put(Key.RADIUS, DEFAULT_RADIUS);
    data.put(Key.SECONDS, DEFAULT_SECONDS);
    this.defaults = data.clone();
  }

  @Override
  public void run() {
    // Resolve a platform-neutral player handle for the target (same pattern as GlideEffect).
    PlayerHandle ph = HandleRegistry.wrapPlayer(target);
    if (ph == null) {
      LocationHandle lh = HandleRegistry.wrapLocation(target);
      if (lh != null) ph = HandleRegistry.playerAt(lh);
    }
    if (ph == null) return;

    if (RTP.serverAccessor == null) return;
    RTPPlayer player = RTP.serverAccessor.getPlayer(ph.uuid());
    if (player == null || !player.isOnline()) return;

    int radius = clamp(asInt(data.get(Key.RADIUS), DEFAULT_RADIUS), 1, MAX_RADIUS);
    int seconds = Math.max(1, asInt(data.get(Key.SECONDS), DEFAULT_SECONDS));

    RTPLocation center = player.getLocation();
    RTPWorld<?> world = center.world();

    // Snapshot the real (already-loaded) blocks the client currently sees, and build the AIR
    // overlay. The SPI returns null for unloaded / unreadable positions, which we skip.
    Map<RTPLocation, String> originals = new LinkedHashMap<>();
    Map<RTPLocation, String> dissolved = new LinkedHashMap<>();
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dy = -radius; dy <= radius; dy++) {
        for (int dz = -radius; dz <= radius; dz++) {
          double dist = Math.sqrt((double) (dx * dx + dy * dy + dz * dz));
          if (dist > radius) continue; // carve a sphere, not a boxy cube
          RTPLocation loc = new RTPLocation(
              world, center.x() + dx, center.y() + dy, center.z() + dz);
          String shown = player.getClientBlock(loc);
          if (shown == null || isAir(shown)) continue;
          originals.put(loc, shown);
          // Hollow void core, dark rim: themes the dissolve as a rift tearing open into a void.
          boolean rim = dist >= radius - 1.0;
          dissolved.put(loc, rim ? rimBlock(loc) : AIR);
        }
      }
    }

    if (dissolved.isEmpty()) return;

    // Push the dissolve as one binned send. run() is already dispatched on a thread that owns
    // the player, so sending client packets here is legal.
    player.sendClientBlockChanges(dissolved);

    // Themed flair: spawn a burst of dark particles where the rift tears open. We reuse the
    // built-in PARTICLE effect (resolved by name) so this stays platform-neutral - no platform
    // particle type is referenced here. Particles are purely optional decoration, so a missing
    // PARTICLE registration or an unknown token degrades to nothing rather than failing the run.
    castDarkParticles(radius);

    // Restore the real client-side state when the warmup ends. runTaskForPlayer hops onto a
    // thread that legally owns the player (Folia entity scheduler), so re-sending packets is
    // safe there. Never swallow a failure silently (S-004).
    UUID id = ph.uuid();
    Runnable restore = () -> {
      RTPPlayer viewer = (RTP.serverAccessor != null) ? RTP.serverAccessor.getPlayer(id) : null;
      if (viewer == null || !viewer.isOnline()) return; // logged off; nothing to restore
      viewer.sendClientBlockChanges(originals);
    };

    if (RTP.scheduler != null) {
      try {
        RTP.scheduler.runTaskForPlayer(player, new RTPRunnable(restore), seconds * 20L);
        return;
      } catch (Throwable t) {
        RTP.log(Level.WARNING,
            "[LeafRTPRiftAddon] RIFT restore scheduling failed; restoring immediately", t);
      }
    }
    // Fallback: no scheduler (or it threw) - restore now rather than leaking the fakes.
    restore.run();
  }

  @Override
  public String toPermission() {
    return RiftEffect.NAME + "." + data.get(Key.RADIUS) + "." + data.get(Key.SECONDS);
  }

  @Override
  public void setData(String... data) {
    applyByType(Key.values(), data);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Registry name (matched case-insensitively, stored upper-cased by the factory). */
  static final String NAME = "RIFT";

  /** Pick a dark rim block for a position, deterministically (stable across the restore). */
  private static String rimBlock(RTPLocation loc) {
    int h = (loc.x() * 31 + loc.y()) * 31 + loc.z();
    return RIM_BLOCKS[Math.floorMod(h, RIM_BLOCKS.length)];
  }

  private static boolean isAir(String blockData) {
    // Treat every air variant as "nothing to dissolve". The block-data string is namespaced
    // (e.g. "minecraft:cave_air"); match the bare block id after the ':' / before any '['.
    String s = blockData.toLowerCase();
    int colon = s.indexOf(':');
    if (colon >= 0) s = s.substring(colon + 1);
    int bracket = s.indexOf('[');
    if (bracket >= 0) s = s.substring(0, bracket);
    return s.equals("air") || s.equals("cave_air") || s.equals("void_air");
  }

  private static int asInt(Object o, int fallback) {
    if (o instanceof Number) return ((Number) o).intValue();
    if (o != null) {
      try {
        return Integer.parseInt(o.toString().trim());
      } catch (NumberFormatException ignored) {
        // fall through to default
      }
    }
    return fallback;
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  /**
   * Spawn dark, void-themed particles around the player to dress the rift: a swirl of ender/portal
   * particles (the rift's purple glow) plus squid-ink particles (the dark void itself). Delivered
   * by composing the registered {@code PARTICLE} effect, so no platform particle type is named
   * here. Both {@code portal} and {@code squid_ink} resolve on every platform's coercer.
   */
  private void castDarkParticles(int radius) {
    int portalCount = Math.max(24, radius * 14);
    int inkCount = Math.max(10, radius * 5);
    // Ender/portal purple - the rift's glow, swirled through the column.
    spawnBurst("portal", portalCount, 0.0, radius * 0.5, 0.0, 0.5);
    spawnBurst("portal", portalCount / 2, 0.0, radius * 1.0, 0.0, 0.4);
    // Squid ink - the dark void itself.
    spawnBurst("squid_ink", inkCount, 0.0, radius * 0.5, 0.0, 0.15);
  }

  /**
   * Build, aim, and fire one burst of the registered {@code PARTICLE} effect at this effect's
   * target. Failures are logged (never swallowed, S-004) but never abort the rift - particles are
   * decoration on top of the (already-sent) block dissolve.
   */
  private void spawnBurst(String type, int count, double dx, double dy, double dz, double speed) {
    Effect<?> fx = EffectFactory.buildEffect(NAME_PARTICLE);
    if (fx == null) return; // PARTICLE not registered on this platform; flair is optional
    try {
      fx.setTarget(target);
      // PARTICLE grammar is TYPE.NUMBER.DX.DY.DZ.SPEED; the coercer divides DOUBLE tokens by 100
      // (legacy percent encoding), so coordinate/speed tokens are the real value times 100.
      fx.setData(type, Integer.toString(count), token(dx), token(dy), token(dz), token(speed));
      fx.run();
    } catch (Throwable t) {
      RTP.log(Level.FINE,
          "[LeafRTPRiftAddon] dark '" + type + "' particle burst skipped: " + t.getMessage());
    }
  }

  /** Encode a real coordinate/speed as the legacy "/100" double token the effect coercer expects. */
  private static String token(double value) {
    return Integer.toString((int) Math.round(value * 100.0));
  }

  /** Registry name of the built-in particle effect this effect composes for its flair. */
  private static final String NAME_PARTICLE = "PARTICLE";
}
