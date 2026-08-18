package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.List;
import java.util.UUID;

/**
 * Platform-neutral chat renderer for {@link MenuModel} (ADR-035, ADR-050).
 * Emits chat lines for {@link MenuLine}s and sends clickable run-commands for interactive fragments.
 */
public final class ChatMenuRenderer implements MenuRenderer {

    /** Constructs a chat menu renderer. Stateless; a single instance may be shared. */
    public ChatMenuRenderer() {}

    @Override
    public void render(UUID playerId, MenuModel model) {
        if (playerId == null || model == null) return;
        RTPCommandSender target = RTP.serverAccessor.getSender(playerId);
        if (target == null) {
            // No live sender on this server for the viewer - drop the render.
            // The caller (MenuRedeemSubcommand) is responsible for sending
            // the configurable menuInvalid fallback when the renderer cannot
            // produce output (REQ-RTP-F-013 / REQ-RTP-S-007).
            return;
        }
        // Title line: visible heading so chat scrollback boundaries are
        // obvious. Decorative, non-clickable.
        String title = model.title();
        if (title != null && !title.isEmpty()) {
            sendPlain(target, title);
        }

        List<MenuPage> pages = model.pages();
        int pageCount = pages.size();
        for (int pageIdx = 0; pageIdx < pageCount; pageIdx++) {
            if (pageCount > 1) {
                // Lightweight page divider between pages so the scrollback
                // boundary is readable. Plain text (no click).
                sendPlain(target, "&8-- page " + (pageIdx + 1) + " of " + pageCount + " --");
            }
            renderPage(target, playerId, pages.get(pageIdx));
        }
    }

    private void renderPage(RTPCommandSender target, UUID playerId, MenuPage page) {
        for (MenuLine line : page.lines()) {
            renderLine(target, playerId, line);
        }
    }

    /**
     * Emits one chat message per fragment in the line. Chat has no native
     * "concatenate styled runs" surface that we expose through
     * {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor}, so
     * each fragment is its own message; the resulting visual is one fragment
     * per chat line. Decorative-only lines collapse onto a single message
     * containing the concatenation of all decorative fragments.
     */
    private void renderLine(RTPCommandSender target, UUID playerId, MenuLine line) {
        List<MenuFragment> fragments = line.fragments();
        if (fragments.isEmpty()) {
            // Blank-line marker: emit a single space so chat shows the gap.
            sendPlain(target, " ");
            return;
        }
        // Optimisation for the common case of purely decorative lines (no
        // click action on any fragment): coalesce into one message so the
        // chat output reads like the source line, not one-message-per-word.
        boolean anyAction = false;
        for (MenuFragment f : fragments) {
            if (f.action() != null) { anyAction = true; break; }
        }
        if (!anyAction) {
            StringBuilder sb = new StringBuilder();
            for (MenuFragment f : fragments) sb.append(f.text());
            sendPlain(target, sb.toString());
            return;
        }
        // Mixed/clickable line: one message per fragment so each click event
        // is scoped to its source fragment. Decorative fragments retain
        // their hover (if any) but have no click.
        for (MenuFragment fragment : fragments) {
            renderFragment(target, playerId, fragment);
        }
    }

    private void renderFragment(RTPCommandSender target, UUID playerId, MenuFragment fragment) {
        String text = fragment.text();
        if (text == null || text.isEmpty()) return;
        String hover = fragment.hover();
        MenuAction action = fragment.action();

        // Renderer-only variant: ChangePage maps to a concrete page command.
        // SuggestInput / OpenExternalUrl have no run-command analogue and
        // degrade to plain text (with hover preserved) per the class Javadoc.
        if (action instanceof MenuAction.ChangePage change) {
            sendRun(target, text, hover, MenuActionToCommand.changePageCommand(change));
            return;
        }
        if (action instanceof MenuAction.SuggestInput
                || action instanceof MenuAction.OpenExternalUrl) {
            sendPlain(target, text, hover);
            return;
        }

        String runCommand = MenuActionToCommand.toRunCommand(action);
        if (runCommand == null) {
            // Decorative fragment (or unknown action that yielded null).
            sendPlain(target, text, hover);
            return;
        }
        sendRun(target, text, hover, runCommand);
    }

    /** Plain (non-clickable) line. */
    private static void sendPlain(RTPCommandSender target, String text) {
        sendPlain(target, text, null);
    }

    /** Plain (non-clickable) line with optional hover tooltip. */
    private static void sendPlain(RTPCommandSender target, String text, String hover) {
        if (hover == null || hover.isEmpty()) {
            target.sendMessage(text);
        } else {
            // Reuse the existing rich-text sink with a null/empty click so
            // the line carries hover but no click action.
            RTP.serverAccessor.sendMessage(target, text, hover, "", null);
        }
    }

    /** Clickable line that auto-dispatches the supplied {@code /rtp ...} command. */
    private static void sendRun(RTPCommandSender target, String text,
                                String hover, String runCommand) {
        // Guard against console / server-id senders. The accessor itself
        // handles the offline-player fallback, but skipping the SPI call
        // here keeps the hot path (chat menus are operator-facing, sent
        // many times per session) free of the per-line throw/catch the
        // accessor would do for an offline UUID.
        if (target.uuid() == null || target.uuid().equals(RTPAPI.serverId)) {
            target.sendMessage(text);
            return;
        }
        RTP.serverAccessor.sendMessageWithRunCommand(target, text, hover, runCommand, null);
    }
}
