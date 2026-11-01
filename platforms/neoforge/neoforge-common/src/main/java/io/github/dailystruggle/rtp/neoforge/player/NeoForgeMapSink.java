package io.github.dailystruggle.rtp.neoforge.player;

/**
 * Optional capability marker for an {@link io.github.dailystruggle.rtp.api.entity.RTPPlayer}
 * that can render an RTP map chart onto a vanilla filled-map for itself
 * (NeoForge analogue of {@code FabricMapSink}).
 *
 * <p><b>Why this exists.</b> {@code NeoForgeMapBinding} needs to hand a rendered
 * ARGB snapshot to the viewer's underlying {@code net.minecraft.server.level.ServerPlayer}.
 * Rather than expose that raw {@code ServerPlayer} to the binding, the player
 * object itself owns the handle and exposes
 * {@link #renderMapChart(String, int[], boolean, boolean)} /
 * {@link #releaseMapChart(String)}, hopping to the server tick thread internally.
 * {@code NeoForgeMapBinding} resolves the viewer through this interface rather
 * than the concrete {@code NeoForgeRTPPlayer} class.
 */
public interface NeoForgeMapSink {

    /**
     * Render the given ARGB snapshot onto a vanilla filled-map for this player
     * and ship it. Implementations must hop to the server tick thread before
     * touching map / inventory / connection state.
     *
     * @param chartKey    stable per-chart key (carrier reuses one map id across
     *                    live-refresh frames); never {@code null}
     * @param argb        row-major 128x128 ARGB pixel buffer; never {@code null}
     * @param locked      request a write-locked map
     * @param deliverItem place a {@code FILLED_MAP} item into the inventory
     * @return {@code true} if the request was accepted (dispatched onto the
     *         server thread); {@code false} if the player is offline or the
     *         active carrier does not support map charts
     */
    boolean renderMapChart(String chartKey, int[] argb, boolean locked, boolean deliverItem);

    /**
     * Release any per-chart cache the active carrier holds for {@code chartKey}
     * (REQ-RTP-MAP-003). Best-effort server-side cleanup.
     */
    void releaseMapChart(String chartKey);
}
