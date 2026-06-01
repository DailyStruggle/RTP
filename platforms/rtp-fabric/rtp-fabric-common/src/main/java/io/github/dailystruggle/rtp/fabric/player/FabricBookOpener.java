package io.github.dailystruggle.rtp.fabric.player;

import io.github.dailystruggle.rtp.fabric.menu.FabricBookSpec;

/**
 * Optional capability marker for an {@link io.github.dailystruggle.rtp.api.entity.RTPPlayer}
 * that can open a written-book modal for itself.
 *
 * <p><b>Why this exists.</b> {@code FabricBookMenuRenderer} (in
 * {@code rtp-fabric-common}) needs to open the book on the viewer's underlying
 * {@code net.minecraft.server.level.ServerPlayer}. Rather than expose that raw
 * {@code ServerPlayer} to the common module (which then has to hand it back to
 * a per-version adapter), the player object itself exposes a single
 * {@link #openBookMenu(FabricBookSpec)} method and keeps its {@code ServerPlayer}
 * private.
 *
 * <p>On 1.20.x / 1.21.x the common {@code FabricRTPPlayer} implements this by
 * delegating to the active {@code FabricVersionAdapter.openBookMenu}. On the
 * deobfuscated MC 26.x runtime the online player is a per-version sink
 * ({@code V26_1_R1FabricRTPPlayer} / {@code V26_2_R1FabricRTPPlayer}) that
 * implements {@code RTPPlayer} directly (not extending the common
 * {@code FabricRTPPlayer}); it implements this capability the same way, against
 * its own deobf-linkable adapter. The renderer therefore never touches a
 * {@code ServerPlayer}.
 */
public interface FabricBookOpener {

    /**
     * Open the given written-book modal for this player.
     *
     * @param spec the fully-formatted book pages (placeholders + colour codes
     *             already resolved); never {@code null}
     * @return {@code true} if the book modal was dispatched; {@code false} when
     *         the player is offline / detached, the active carrier does not
     *         support the modal, or the dispatch failed (caller falls back to
     *         the chat renderer)
     */
    boolean openBookMenu(FabricBookSpec spec);
}
