package io.github.dailystruggle.rtp.riftaddon;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.platform.BlockDelta;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * "World deconstruction" warmup effect: tears the terrain around a player open into a real void
 * for a few seconds so the player genuinely falls into the rift, then snaps the world back.
 *
 * <p><b>Platform-neutral.</b> This effect contains no {@code org.bukkit.*} (or any platform)
 * references. It resolves a platform-neutral {@link PlayerHandle} for its target exactly like
 * the built-in {@code GlideEffect} does, then drives the whole animation through the RTP SPI:
 * {@link RTPPlayer#getClientBlock(RTPLocation)} to snapshot the real blocks that are currently
 * loaded around the player and {@link RTPWorld#setBlocks(java.util.List)} to carve them out (and
 * later put them back).
 *
 * <p><b>A real carve for the fall, packets for the looks.</b> A purely client-side fake desynced
 * the client from the server: the client saw air under its feet and predicted a fall, the server
 * still had solid ground and rejected the move, and the player rubber-banded - floating for the
 * whole warmup. So the void itself is carved into <b>real</b> air (via the adapter's bulk writer)
 * and the server agrees there is a hole, letting the player genuinely fall in. The themed dark
 * rim, by contrast, is presentation only: it is pushed as <b>client-side</b> ({@code
 * sendClientBlockChanges}) fake blocks layered over the carved air, so it never becomes a real,
 * collidable, mineable block - the player still falls straight through it.
 *
 * <p><b>Gravity blocks never fall.</b> Sand, gravel, concrete powder, anvils, etc. are <b>not</b>
 * carved for real - removing their support (or the support of a gravity block resting on the
 * sphere) would turn them into falling-block entities. Instead each gravity block is shown as
 * client-side fake air (so it vanishes into the rift visually) while its real block stays put, so
 * nothing ever drops.
 *
 * <p><b>Nothing is obtainable, nothing leaks.</b> The real carve only ever replaces blocks with
 * air via a no-physics, no-drop write (the Bukkit-family writer uses {@code setBlockData(data,
 * false)}), so no item is ever dropped and the dark rim is a client-only fake that introduces no
 * real block a player could mine or collect. The original blocks are always written back after
 * {@code SECONDS} - even if the player logs off mid-fall - so a missed restore can never leave a
 * permanent hole. No chunk is loaded on any region thread: only blocks already loaded around the
 * standing player are read (the SPI skips unloaded positions) and only those are touched (S-005).
 *
 * <p>Token / permission grammar (the {@code .} separator splits arguments, so all arguments are
 * whole numbers): {@code RIFT.<radius>.<seconds>}, e.g. {@code RIFT.4.3} opens a 4-block sphere
 * radius for 3 seconds.
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
   * Real block the rift's lower shell is carved INTO (not air): a solid obsidian bowl that catches
   * the falling player so they cannot drop straight through the rift into a cave (or the void)
   * underneath it. Obsidian is used because it is effectively un-minable in the brief warmup
   * window, so the player can't dig out of (or loot) the catch-floor before it is reverted. The
   * starry {@code end_gateway} ({@link #FLOOR_BLOCK}) is layered over it as a client-side fake, so
   * the player sees the starfield they fall toward while really landing on the obsidian. Reverted
   * with everything else when the warmup ends.
   */
  private static final String FLOOR_REAL_BLOCK = "minecraft:obsidian";

  /**
   * Dark block-data strings the rift's rim is dressed in (client-side only), to theme the void as
   * a tear opening into darkness rather than a plain hole. A mix of sculk, obsidian, and black
   * concrete; all are vanilla blocks so the client-side fake renders identically everywhere, and
   * one is picked deterministically per position so it is stable across the restore.
   */
  private static final String[] RIM_BLOCKS = {
    "minecraft:sculk",
    "minecraft:obsidian",
    "minecraft:black_concrete"
  };

  /**
   * Client-side-only block the rift's lower shell is painted with: an end gateway, which renders
   * the deep, parallaxed starfield on every face (unlike {@code end_portal}, whose star texture
   * only draws on the top face and so would be invisible from below as the player falls). Sent as
   * a fake, so the server never has a gateway there - it cannot teleport, be mined, or persist; it
   * just gives the void floor a starry-sky look the player falls toward.
   */
  private static final String FLOOR_BLOCK = "minecraft:end_gateway";

  public RiftEffect() {
    super(new EnumMap<>(Key.class));
    data.put(Key.RADIUS, DEFAULT_RADIUS);
    data.put(Key.SECONDS, DEFAULT_SECONDS);
    this.defaults = data.clone();
  }

  @Override
  public void run() {
    RTP.log(Level.FINE, "[LeafRTPRiftAddon] RIFT.run() invoked; target=" + target);
    // Resolve a platform-neutral player handle for the target (same pattern as GlideEffect).
    PlayerHandle ph = HandleRegistry.wrapPlayer(target);
    if (ph == null) {
      LocationHandle lh = HandleRegistry.wrapLocation(target);
      if (lh != null) ph = HandleRegistry.playerAt(lh);
    }
    if (ph == null) {
      RTP.log(Level.WARNING,
          "[LeafRTPRiftAddon] RIFT aborted: could not resolve a PlayerHandle for target=" + target);
      return;
    }

    if (RTP.serverAccessor == null) {
      RTP.log(Level.WARNING, "[LeafRTPRiftAddon] RIFT aborted: RTP.serverAccessor is null");
      return;
    }
    RTPPlayer player = RTP.serverAccessor.getPlayer(ph.uuid());
    if (player == null || !player.isOnline()) {
      RTP.log(Level.WARNING, "[LeafRTPRiftAddon] RIFT aborted: player " + ph.uuid()
          + " is null or offline (player=" + player + ")");
      return;
    }

    // Never open the rift when there is no real teleport warmup to fall through. With an instant
    // teleport (effective delay < 1s) the player would be whisked away the same tick the rift is
    // torn open; the preload effect dispatch is async, so by the time this runs the player may
    // already be AT the destination, where the carve then opens a hole and the restore closes it
    // back on them - suffocating them. The effective delay is read from the authoritative
    // RTPCommandSender#delay() (milliseconds), which honors rtp.nodelay/rtp.noDelay (force 0) and
    // an rtp.delay.<n> permission before falling back to config.yml's teleportDelay - so a player
    // with rtp.nodelay is correctly treated as an instant teleport, not a config-default delay.
    long teleportDelayMillis = resolveTeleportDelayMillis(ph.uuid());
    if (teleportDelayMillis < 1000L) {
      RTP.log(Level.FINE, "[LeafRTPRiftAddon] RIFT skipped: effective teleport delay ("
          + teleportDelayMillis + "ms) is below 1s, so there is no warmup window to fall through "
          + "(instant teleport)");
      return;
    }

    int radius = clamp(asInt(data.get(Key.RADIUS), DEFAULT_RADIUS), 1, MAX_RADIUS);
    int seconds = Math.max(1, asInt(data.get(Key.SECONDS), DEFAULT_SECONDS));
    RTP.log(Level.FINE, "[LeafRTPRiftAddon] RIFT params resolved: radius=" + radius
        + ", seconds=" + seconds);

    RTPLocation center = player.getLocation();
    RTPWorld<?> world = center.world();

    // Walk the sphere once and sort each loaded, non-air block into one of two buckets:
    //   * carve  - real blocks we replace with real air on the server, so the player truly falls.
    //   * fakes  - client-side-only overlays: the dark rim (themed look), plus fake air for any
    //              gravity block (sand/gravel/...) we deliberately leave real so it cannot fall.
    // getClientBlock returns the real block-data token, or null for unloaded / unreadable
    // positions, which we skip (no chunk load, S-005).
    List<BlockDelta> originals = new ArrayList<>();
    List<BlockDelta> carve = new ArrayList<>();
    Map<RTPLocation, String> fakes = new LinkedHashMap<>();
    Map<RTPLocation, String> fakeRestore = new LinkedHashMap<>();
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dy = -radius; dy <= radius; dy++) {
        for (int dz = -radius; dz <= radius; dz++) {
          double dist = Math.sqrt((double) (dx * dx + dy * dy + dz * dz));
          if (dist > radius) continue; // carve a sphere, not a boxy cube
          RTPLocation loc = new RTPLocation(
              world, center.x() + dx, center.y() + dy, center.z() + dz);
          String shown = player.getClientBlock(loc);
          // shown == null means the position is unloaded / unreadable; never touch it (no chunk
          // load, S-005). Unlike the rest of the sphere we do NOT skip plain air here for the
          // floor: a cave mouth (air) right under the rift is exactly what we must seal.
          if (shown == null) continue;
          // The lower shell of the sphere becomes a REAL obsidian bowl, not air: it catches the
          // falling player so they can't drop through the rift into a cave (or the void) below it.
          // We seal it even where the existing block is air (a cave opening), place it for real
          // (overriding the gravity rule - we're adding a solid block, not removing support, so
          // nothing falls) and dress it with the starry end-gateway fake, so the player sees the
          // starfield they fall toward while landing on solid obsidian. Reverted with everything
          // else.
          if (dy < 0 && dist >= radius - 1.0) {
            originals.add(new BlockDelta(loc.x(), loc.y(), loc.z(), shown));
            carve.add(new BlockDelta(loc.x(), loc.y(), loc.z(), FLOOR_REAL_BLOCK));
            fakes.put(loc, FLOOR_BLOCK);
            continue;
          }
          // Everywhere else, air is nothing to dissolve - skip it.
          if (isAir(shown)) continue;
          if (isGravity(shown)) {
            // Never carve a gravity block for real - that would drop it (or the sand resting on
            // the rim) as a falling entity. Vanish it client-side only; the real block stays.
            fakes.put(loc, AIR);
            fakeRestore.put(loc, shown);
            continue;
          }
          // Real carve to air so the server agrees there is a hole and the player falls in.
          originals.add(new BlockDelta(loc.x(), loc.y(), loc.z(), shown));
          carve.add(new BlockDelta(loc.x(), loc.y(), loc.z(), AIR));
          // Dress the rest of the outer shell with the dark sculk/obsidian client-only rim.
          if (dist >= radius - 1.0) {
            fakes.put(loc, rimBlock(loc));
          }
        }
      }
    }

    if (carve.isEmpty() && fakes.isEmpty()) {
      RTP.log(Level.WARNING, "[LeafRTPRiftAddon] RIFT aborted: no carvable blocks found around "
          + "the player (all positions were air or unreadable via getClientBlock); radius="
          + radius);
      return;
    }

    // Open the rift for real: replace the void with air on the server (no physics, no drops - the
    // bulk writer uses setBlockData(data, false)). Because the server now agrees there is a hole,
    // the player genuinely falls into it instead of the client predicting a fall the server
    // rejects (which previously left them floating). run() is already dispatched on a thread that
    // owns the player / footprint, so the write is legal here.
    int placed = world.setBlocks(carve);
    RTP.log(Level.FINE, "[LeafRTPRiftAddon] RIFT carved " + placed + " block(s) into air");

    // Layer the presentation-only fakes on top: the dark rim and the vanished gravity blocks.
    // These are client-side packets only - the server keeps real air (rim) or the real gravity
    // block (so it can't fall), and the player still falls straight through the rim.
    //
    // The fakes MUST be sent one tick AFTER the real carve, not in the same tick. The carve's
    // setBlockData(data, false) writes are batched by the server and flushed to clients as block
    // updates at the END of the current tick; a fake sent now (during run()) would be clobbered
    // by that end-of-tick air update and the dark rim would never appear. Sending the fakes on
    // the next tick lets them land after the real broadcast, so they win on the client.
    if (!fakes.isEmpty()) {
      final UUID fakeViewerId = ph.uuid();
      final Map<RTPLocation, String> toShow = fakes;
      final RTPWorld<?> fakeWorld = world;
      Runnable sendFakes = () -> {
        if (RTP.serverAccessor == null) return;
        RTPPlayer viewer = RTP.serverAccessor.getPlayer(fakeViewerId);
        if (viewer == null || !viewer.isOnline()) return;
        viewer.sendClientBlockChanges(toShow);
        RTP.log(Level.FINE, "[LeafRTPRiftAddon] RIFT sent " + toShow.size()
            + " client-side fake block change(s) (dark rim + vanished gravity blocks)");
      };
      boolean scheduled = false;
      if (RTP.scheduler != null) {
        try {
          RTP.scheduler.runTaskLater(
              fakeWorld, center.x() >> 4, center.z() >> 4, sendFakes, 1L);
          scheduled = true;
        } catch (Throwable t) {
          RTP.log(Level.WARNING,
              "[LeafRTPRiftAddon] RIFT fake-block scheduling failed; sending immediately", t);
        }
      }
      // No scheduler (or it threw): send now. The rim may be clobbered by the end-of-tick real
      // update, but never swallow the work silently (S-004) - a visible-now attempt beats nothing.
      if (!scheduled) sendFakes.run();
    }

    // Themed flair: spawn a burst of dark particles where the rift tears open. We reuse the
    // built-in PARTICLE effect (resolved by name) so this stays platform-neutral - no platform
    // particle type is referenced here. Particles are purely optional decoration, so a missing
    // PARTICLE registration or an unknown token degrades to nothing rather than failing the run.
    castDarkParticles(radius);

    // Pull the player's vision into the void: apply the warden's DARKNESS effect (a smooth,
    // pulsing screen-dimming) for the warmup so the world doesn't just open beneath them, it goes
    // dark as they fall in. Composed by building the registered POTION effect by name, so no
    // platform potion type is referenced here; if DARKNESS doesn't resolve on this server version
    // the POTION default (BLINDNESS) is used instead, which is also a fitting dark veil.
    castDarkness(seconds);

    // Always close the rift when the warmup ends - even if the player logged off mid-fall - so a
    // missed restore can never leave a permanent hole. The restore runs on the thread that owns
    // the footprint chunk (Folia region thread; main thread elsewhere) so the block write is
    // legal. Never swallow a failure silently (S-004).
    final RTPWorld<?> riftWorld = world;
    final List<BlockDelta> toRestore = originals;
    final Map<RTPLocation, String> clientRestore = fakeRestore;
    final UUID viewerId = ph.uuid();
    Runnable restore = () -> {
      // Put the real carved blocks back. This also broadcasts a real block update at each rim
      // position, which clears the dark-rim client fake there, so the rim needs no separate undo.
      int back = riftWorld.setBlocks(toRestore);
      RTP.log(Level.FINE, "[LeafRTPRiftAddon] RIFT restored " + back + " block(s)");
      // Clear the client-only fake air over the gravity blocks (their real block never changed,
      // so setBlocks did not touch them). Only meaningful while the player is online.
      if (!clientRestore.isEmpty() && RTP.serverAccessor != null) {
        RTPPlayer viewer = RTP.serverAccessor.getPlayer(viewerId);
        if (viewer != null && viewer.isOnline()) viewer.sendClientBlockChanges(clientRestore);
      }
    };

    if (RTP.scheduler != null) {
      try {
        RTP.scheduler.runTaskLater(world, center.x() >> 4, center.z() >> 4, restore, seconds * 20L);
        return;
      } catch (Throwable t) {
        RTP.log(Level.WARNING,
            "[LeafRTPRiftAddon] RIFT restore scheduling failed; restoring immediately", t);
      }
    }
    // Fallback: no scheduler (or it threw) - close the rift now rather than leaving a hole.
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

  /**
   * Whether a block-data token names a gravity-affected block that would fall as an entity if its
   * support were removed. We never carve these for real - they are vanished client-side only so
   * no sand/gravel/etc. ever drops. The token is namespaced (e.g. {@code "minecraft:sand"}); we
   * match the bare id after the ':' and before any '['.
   */
  private static boolean isGravity(String blockData) {
    String s = blockData.toLowerCase();
    int colon = s.indexOf(':');
    if (colon >= 0) s = s.substring(colon + 1);
    int bracket = s.indexOf('[');
    if (bracket >= 0) s = s.substring(0, bracket);
    if (s.endsWith("concrete_powder") || s.endsWith("anvil")) return true;
    switch (s) {
      case "sand":
      case "red_sand":
      case "gravel":
      case "suspicious_sand":
      case "suspicious_gravel":
      case "dragon_egg":
      case "pointed_dripstone":
        return true;
      default:
        return false;
    }
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
   * Resolve the effective teleport-warmup delay (in milliseconds) for this specific teleport. This
   * is read from the authoritative {@link RTPCommandSender#delay()} - the same value the core uses
   * to schedule the actual warmup - so it accounts for <b>every</b> factor that shortens the delay,
   * not just {@code config.yml}'s {@code teleportDelay}: the {@code rtp.nodelay}/{@code rtp.noDelay}
   * permissions force {@code 0}, an {@code rtp.delay.<n>} permission overrides the config, and only
   * then does the config default apply. Earlier this re-derived the delay from config plus the
   * {@code rtp.delay.} permission alone, which silently missed {@code rtp.nodelay} - a player with
   * that permission got an instant teleport the rift treated as delayed, so it carved a hole at the
   * destination and suffocated them on restore. Returns {@code 0} when the server accessor or the
   * sender is unavailable, which (being {@code < 1000}) suppresses the rift rather than carving
   * blocks the player can never fall through.
   */
  private static long resolveTeleportDelayMillis(UUID uuid) {
    try {
      RTPCommandSender sender =
          (RTP.serverAccessor != null) ? RTP.serverAccessor.getSender(uuid) : null;
      return (sender != null) ? sender.delay() : 0L;
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[LeafRTPRiftAddon] RIFT delay resolution failed: " + t.getMessage());
      return 0L;
    }
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
    // Squid ink - the dark void itself, dense at the player's feet where the floor gives way.
    spawnBurst("squid_ink", inkCount, 0.0, radius * 0.2, 0.0, 0.15);
    spawnBurst("squid_ink", inkCount, 0.0, radius * -0.5, 0.0, 0.2);
    // Reverse portal - inward-pulling motes that read as the rift sucking the world in.
    spawnBurst("reverse_portal", portalCount, 0.0, radius * 0.5, 0.0, 0.3);
    // A veil of dark smoke clinging to the rim.
    spawnBurst("large_smoke", inkCount, 0.0, radius * 0.4, 0.0, 0.05);
  }

  /**
   * Veil the player's screen in darkness for the duration of the fall. Composes the registered
   * {@code POTION} effect by name (so no platform potion type is referenced here) and applies
   * {@code DARKNESS} for {@code seconds} (plus a short tail so it covers the actual teleport).
   * If {@code DARKNESS} does not resolve on this server version the POTION default ({@code
   * BLINDNESS}) is used instead. Purely decorative: a missing {@code POTION} registration or an
   * unknown token degrades to nothing rather than failing the teleport (S-004-safe logging).
   */
  private void castDarkness(int seconds) {
    Effect<?> fx = EffectFactory.buildEffect(NAME_POTION);
    if (fx == null) return; // POTION not registered on this platform; the veil is optional
    try {
      fx.setTarget(target);
      // POTION grammar is TYPE.DURATION.AMPLIFIER.AMBIENT.PARTICLES.ICON; duration is in ticks.
      int durationTicks = seconds * 20 + 40;
      fx.setData("DARKNESS", Integer.toString(durationTicks), "0", "false", "false", "false");
      fx.run();
    } catch (Throwable t) {
      RTP.log(Level.FINE,
          "[LeafRTPRiftAddon] darkness veil skipped: " + t.getMessage());
    }
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

  /** Registry name of the built-in potion effect this effect composes for the darkness veil. */
  private static final String NAME_POTION = "POTION";
}
