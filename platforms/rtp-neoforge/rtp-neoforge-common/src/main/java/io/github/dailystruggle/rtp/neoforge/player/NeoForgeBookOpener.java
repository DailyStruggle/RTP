package io.github.dailystruggle.rtp.neoforge.player;

import io.github.dailystruggle.rtp.neoforge.menu.NeoForgeBookSpec;

/**
 * Optional capability marker for an {@link io.github.dailystruggle.rtp.api.entity.RTPPlayer}
 * that can open a written-book modal for itself (NeoForge analogue of
 * {@code FabricBookOpener}).
 *
 * <p><b>Why this exists.</b> {@code NeoForgeBookMenuRenderer} (in
 * {@code rtp-neoforge-common}) needs to open the book on the viewer's underlying
 * {@code net.minecraft.server.level.ServerPlayer}. Rather than expose that raw
 * {@code ServerPlayer} to the renderer, the player object itself exposes a single
 * {@link #openBookMenu(NeoForgeBookSpec)} method and keeps its {@code ServerPlayer}
 * private. The renderer resolves the viewer through this interface, so it never
 * touches a {@code ServerPlayer}.
 *
 * <p>Unlike Fabric (which needs the capability marker to span its obf/deobf
 * carrier split), NeoForge is Mojmap-at-runtime with a single common player
 * ({@code NeoForgeRTPPlayer}); the marker is retained purely so the renderer
 * stays free of the raw handle and the version-typed book build lives in the
 * carrier.
 */
public interface NeoForgeBookOpener {

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
    boolean openBookMenu(NeoForgeBookSpec spec);
}
