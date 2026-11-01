package io.github.dailystruggle.rtp.fabric.player;

/**
 * Optional capability marker for an {@link io.github.dailystruggle.rtp.api.entity.RTPPlayer}
 * that can render an RTP map chart onto a vanilla filled-map for itself.
 *
 * <p><b>Why this exists.</b> {@code FabricMapBinding} (in {@code rtp-fabric-common})
 * needs to hand a rendered ARGB snapshot to the viewer's underlying
 * {@code net.minecraft.server.level.ServerPlayer}. Rather than expose that raw
 * {@code ServerPlayer} to the common module, the player object itself owns the
 * handle and exposes {@link #renderMapChart(String, int[], boolean, boolean)} /
 * {@link #releaseMapChart(String)}, hopping to the server tick thread internally.
 *
 * <p>On 1.20.x / 1.21.x the common {@code FabricRTPPlayer} implements this by
 * delegating to the active {@code FabricVersionAdapter}. On the deobfuscated MC
 * 26.x runtime the online player is a per-version sink
 * ({@code V26_1_R1FabricRTPPlayer} / {@code V26_2_R1FabricRTPPlayer} /
 * {@code FabricRTPPlayerUnobf}) that implements {@code RTPPlayer} directly (not
 * extending the common {@code FabricRTPPlayer}); it implements this capability
 * the same way, against its own deobf-linkable adapter. {@code FabricMapBinding}
 * therefore resolves the viewer through this interface rather than the concrete
 * common {@code FabricRTPPlayer} class (which the per-version sinks do not
 * extend), so map charts work on every supported runtime.
 */
public interface FabricMapSink {

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
