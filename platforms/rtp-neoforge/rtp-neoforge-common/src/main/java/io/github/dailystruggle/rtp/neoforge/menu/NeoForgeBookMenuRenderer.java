package io.github.dailystruggle.rtp.neoforge.menu;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.menu.MenuActionToCommand;
import io.github.dailystruggle.rtp.neoforge.player.NeoForgeBookOpener;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Written-book renderer for {@link MenuModel} on NeoForge (NeoForge analogue of
 * {@code FabricBookMenuRenderer}). It opens an interactive multi-page written
 * book modal - the same UX Paper gets from {@code BookMenuRenderer} - instead of
 * the chat-stream rendering of {@code ChatMenuRenderer}.
 *
 * <p><b>Carrier capability gate.</b> This class carries <em>no</em>
 * {@code net.minecraft.*} binding: it builds a fully-formatted
 * {@link NeoForgeBookSpec} (placeholders + colour codes already resolved) and
 * hands it to the viewer's {@link NeoForgeBookOpener#openBookMenu} capability.
 * The active version carrier binds the spec to that version's
 * {@code WrittenBookContent} / {@code ClientboundOpenBookPacket} types; a carrier
 * that leaves the SPI default ({@code return false}) makes this renderer
 * transparently delegate to the injected {@code chatFallback}.
 *
 * <p><b>Click round-trip (ADR-050).</b> Every clickable fragment carries the
 * literal {@code /rtp menu ...} command produced by the shared
 * {@link MenuActionToCommand} helper (single source of truth with the Paper book
 * renderer and the chat renderer). Permission gating remains the server-side
 * security boundary on the matching leaf.
 *
 * <p><b>Book colour palette.</b> The {@code .junie/AGENTS.md} "Book Menu Color
 * Contrast" rule applies here exactly as it does on Paper: books render on
 * parchment, so the {@code MenuModel} producers' colour choices (shared with
 * Paper) carry over unchanged.
 */
public final class NeoForgeBookMenuRenderer implements MenuRenderer {

    private final MenuRenderer chatFallback;

    /**
     * @param chatFallback renderer used when the active carrier does not
     *                     support the written-book modal or when the book
     *                     dispatch fails; never {@code null}.
     */
    public NeoForgeBookMenuRenderer(MenuRenderer chatFallback) {
        this.chatFallback = Objects.requireNonNull(chatFallback, "chatFallback");
    }

    @Override
    public void render(UUID playerId, MenuModel model) {
        if (playerId == null || model == null) return;

        NeoForgeBookOpener opener = resolveOpener(playerId);
        if (opener == null) {
            // Offline / detached / non-NeoForge sender: nothing to open a book
            // on. Fall back to chat (which itself drops cleanly for an absent
            // sender); the caller emits the configurable menuInvalid fallback.
            chatFallback.render(playerId, model);
            return;
        }

        boolean opened = false;
        try {
            NeoForgeBookSpec spec = buildSpec(playerId, model);
            opened = opener.openBookMenu(spec);
        } catch (Throwable t) {
            // Mapping drift on a 1.21.x patch / runtime linkage failure must
            // not swallow the menu - degrade to chat and log once (S-004).
            RTP.log(Level.WARNING,
                    "[RTP][NeoForge] book menu render failed (" + t.getClass().getSimpleName()
                            + "): falling back to chat renderer - " + t.getMessage());
            opened = false;
        }
        if (!opened) {
            chatFallback.render(playerId, model);
        }
    }

    /**
     * Resolve the viewer's book-opening capability, or null. The common
     * {@code NeoForgeRTPPlayer} keeps its own {@code ServerPlayer} private and
     * exposes only {@link NeoForgeBookOpener#openBookMenu}.
     */
    private static NeoForgeBookOpener resolveOpener(UUID playerId) {
        try {
            RTPPlayer player = RTP.serverAccessor.getPlayer(playerId);
            if (player instanceof NeoForgeBookOpener opener) {
                return opener;
            }
        } catch (Throwable ignored) {
            // getPlayer may throw on a detached/offline UUID; treat as absent.
        }
        return null;
    }

    /**
     * Pure conversion {@link MenuModel} -&gt; {@link NeoForgeBookSpec}. Visible for
     * unit testing (no live server required). Text is formatted through the
     * server accessor so placeholders and colour codes match every other RTP
     * message surface.
     */
    public NeoForgeBookSpec buildSpec(UUID playerId, MenuModel model) {
        Objects.requireNonNull(model, "model");
        List<NeoForgeBookSpec.Page> pages = new ArrayList<>(model.pages().size());
        for (MenuPage page : model.pages()) {
            List<NeoForgeBookSpec.Line> lines = new ArrayList<>(page.lines().size());
            for (MenuLine line : page.lines()) {
                List<NeoForgeBookSpec.Fragment> frags = new ArrayList<>(line.fragments().size());
                for (MenuFragment fragment : line.fragments()) {
                    frags.add(toFragment(playerId, fragment));
                }
                lines.add(new NeoForgeBookSpec.Line(frags));
            }
            pages.add(new NeoForgeBookSpec.Page(lines));
        }
        return new NeoForgeBookSpec(format(playerId, model.title()), pages);
    }

    private static NeoForgeBookSpec.Fragment toFragment(UUID playerId, MenuFragment fragment) {
        String text = format(playerId, fragment.text());
        String hover = format(playerId, fragment.hover());
        String runCommand = toRunCommand(fragment.action());
        return new NeoForgeBookSpec.Fragment(text, hover, runCommand);
    }

    /**
     * Maps a fragment {@link MenuAction} to its literal click command, reusing
     * the shared {@link MenuActionToCommand} table. {@link MenuAction.ChangePage}
     * is mapped to the concrete {@code /rtp menu page n=<n>} command so the click
     * re-renders the whole menu at the requested page.
     */
    private static String toRunCommand(MenuAction action) {
        if (action == null) return null;
        if (action instanceof MenuAction.ChangePage change) {
            return MenuActionToCommand.changePageCommand(change);
        }
        return MenuActionToCommand.toRunCommand(action);
    }

    private static String format(UUID playerId, String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        try {
            UUID uuid = (playerId == null) ? RTPAPI.serverId : playerId;
            return RTP.serverAccessor.format(uuid, raw);
        } catch (Throwable t) {
            // Test / pre-boot contexts without a wired accessor: pass the raw
            // string through so colour codes still render downstream.
            return raw;
        }
    }
}
