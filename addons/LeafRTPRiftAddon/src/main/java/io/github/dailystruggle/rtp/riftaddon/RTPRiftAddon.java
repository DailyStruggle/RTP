package io.github.dailystruggle.rtp.riftaddon;

import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.effectsapi.common.EffectsGroupKeys;
import io.github.dailystruggle.rtp.api.addon.RTPAddon;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.logging.Level;

/**
 * LeafRTP "Virtual Rift" demo addon - the reference example of <b>effect registration</b>.
 *
 * <p>Registers a single new effect prototype, {@code RIFT}, with the {@code effects-api}
 * registry. {@code RIFT} is a "world deconstruction" warmup effect: while a player stands
 * through the teleport warmup, the terrain around them appears to dissolve into thin air, then
 * snaps back when the warmup ends. The dissolve is delivered entirely as binned client-side
 * block changes through the RTP SPI (no physical block updates, no physics checks, no chunk
 * loads on any region thread), so it can never compromise destination safety (S-001..S-007);
 * the worst a buggy effect can do is fail to render.
 *
 * <p>Once registered, {@code RIFT} is usable exactly like the built-in effects (FIREWORK,
 * PARTICLE, SOUND, ...) - by name in an {@code effects/} group token or a
 * {@code rtp.effect.<stage>.rift.<radius>.<seconds>} permission. See {@code README.md} for a
 * walkthrough and example configuration.
 *
 * <p><b>Platform-neutral.</b> {@link RiftEffect} is written entirely against {@code rtp-api} and
 * {@code effects-api} - no {@code org.bukkit.*}, no platform probing. It resolves its target
 * through {@code effects-api}'s {@code HandleRegistry} and drives the animation through the
 * {@code RTPPlayer} client block-change SPI, so the same class loads and runs on every platform
 * whose adapter implements that SPI (Bukkit / Paper / Folia today; a no-op where the SPI is
 * unimplemented). The addon is discovered by {@code rtp-core} through
 * {@link java.util.ServiceLoader} (see
 * {@code META-INF/services/io.github.dailystruggle.rtp.api.addon.RTPAddon}).
 */
public final class RTPRiftAddon implements RTPAddon {

  /** Resource name (addon jar root) and on-disk group name for the demo effects group. */
  private static final String DEMO_GROUP = "rift";

  @Override
  public void onLoad() {
    // addEffect uses putIfAbsent, so a second call (or a reload) is a no-op.
    EffectFactory.addEffect(RiftEffect.NAME, new RiftEffect());
    RTP.log(Level.INFO, "[LeafRTPRiftAddon] registered the RIFT world-deconstruction effect.");
    seedDemoGroup();
  }

  /**
   * Install a ready-to-use {@code effects/rift.yml} group so RIFT is demonstrable out of the box.
   *
   * <p>The addon ships the group as its own resource rather than having {@code rtp-core} or
   * {@code rtp-plugin} reference it - core must never know about an addon-provided effect, or it
   * becomes a bad example for independent addon authors. The file is copied to
   * {@code <pluginDir>/effects/rift.yml} on first run only ({@link Files#copy} is skipped when the
   * file already exists), so operator edits and deletions are respected.
   *
   * <p>This addon loads after the initial config load, so the live effects
   * {@link MultiConfigParser} is registered with the freshly-copied file directly; otherwise the
   * group would not be active until the next {@code /rtp reload}.
   */
  private void seedDemoGroup() {
    try {
      if (RTP.serverAccessor == null || RTP.configs == null) return;
      Object raw = RTP.configs.multiConfigParserMap.get(EffectsGroupKeys.class);
      if (!(raw instanceof MultiConfigParser)) return;
      @SuppressWarnings("unchecked")
      MultiConfigParser<EffectsGroupKeys> effectsGroups = (MultiConfigParser<EffectsGroupKeys>) raw;

      File effectsDir = new File(effectsGroups.getMainDirectory(), "effects");
      File target = new File(effectsDir, DEMO_GROUP + ".yml");
      if (!target.exists()) {
        if (!effectsDir.exists() && !effectsDir.mkdirs()) {
          RTP.log(Level.WARNING, "[LeafRTPRiftAddon] could not create effects directory; skipping demo group");
          return;
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(DEMO_GROUP + ".yml")) {
          if (in == null) {
            RTP.log(Level.WARNING, "[LeafRTPRiftAddon] bundled rift.yml resource missing; skipping demo group");
            return;
          }
          Files.copy(in, target.toPath());
        }
      }

      if (!effectsGroups.listParsers().contains(DEMO_GROUP)) {
        // Clones the effects schema and re-reads the on-disk file we just wrote.
        effectsGroups.addParser(DEMO_GROUP);
        RTP.log(Level.INFO, "[LeafRTPRiftAddon] installed default effects/rift.yml demo group (when: preload).");
      }
    } catch (Exception e) {
      // A failed demo install must never break addon load or the teleport pipeline.
      RTP.log(Level.WARNING, "[LeafRTPRiftAddon] failed to install rift.yml demo group", e);
    }
  }

  @Override
  public void onUnload() {
    // Detach the prototype so the addon can come and go without leaving RTP in a half-state.
    EffectFactory.removeEffect(RiftEffect.NAME);
  }
}
