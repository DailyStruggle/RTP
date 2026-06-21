package io.github.dailystruggle.rtp.riftaddon;

import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.rtp.api.addon.RTPAddon;
import io.github.dailystruggle.rtp.common.RTP;
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

  @Override
  public void onLoad() {
    // addEffect uses putIfAbsent, so a second call (or a reload) is a no-op.
    EffectFactory.addEffect(RiftEffect.NAME, new RiftEffect());
    RTP.log(Level.INFO, "[LeafRTPRiftAddon] registered the RIFT world-deconstruction effect.");
  }

  @Override
  public void onUnload() {
    // Detach the prototype so the addon can come and go without leaving RTP in a half-state.
    EffectFactory.removeEffect(RiftEffect.NAME);
  }
}
