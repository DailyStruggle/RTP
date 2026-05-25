package io.github.dailystruggle.rtp.api.menu;

import java.util.UUID;

/**
 * Renders a {@link MenuModel} to a specific player on a specific platform.
 *
 * <p>Implementations live in the adapter layer (e.g. {@code BookMenuRenderer}
 * in {@code rtp-paper-common}, future {@code ChatMenuRenderer} variants in
 * {@code rtp-spigot}/{@code rtp-fabric}). The interface itself carries no
 * platform types so {@code rtp-api} stays platform-free
 * (see {@code AGENTS.md} → Architecture Boundaries).
 *
 * <p><b>Lifecycle.</b> Calling {@link #render} before {@code rtp-core} has
 * loaded must throw {@link IllegalStateException} per REQ-RTP-S-006 — never
 * silently no-op.
 *
 * <p><b>Click round-trip (ADR-050).</b> Each clickable {@link MenuFragment}
 * resolves to a concrete, self-documenting {@code /rtp menu ...} command.
 * Permission gating remains the security boundary, enforced server-side by
 * the dispatch helpers on {@code MenuRedeemSubcommand}.
 */
public interface MenuRenderer {

    /**
     * Render {@code model} to the given player.
     *
     * @param playerId target player UUID; never {@code null}.
     * @param model    the menu to render; never {@code null}.
     * @throws IllegalStateException if invoked before {@code rtp-core} is loaded
     *                               (REQ-RTP-S-006).
     */
    void render(UUID playerId, MenuModel model);
}
