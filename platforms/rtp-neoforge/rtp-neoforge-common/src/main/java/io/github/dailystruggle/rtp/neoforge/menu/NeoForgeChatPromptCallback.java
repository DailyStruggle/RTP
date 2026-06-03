package io.github.dailystruggle.rtp.neoforge.menu;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.menu.MenuRedeemSubcommand;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * NeoForge substitute for the Paper anvil-input modal (ADR-045), the NeoForge
 * analogue of {@code FabricChatPromptCallback}. Implements
 * {@link MenuRedeemSubcommand.AnvilInputOpener} by registering the viewer for an
 * inline chat prompt: the next chat message the viewer sends is consumed
 * (cancelled so it does not broadcast) and dispatched as the intended
 * {@code /rtp ...} command, exactly as the Paper anvil GUI would.
 *
 * <p><b>Mojmap-at-runtime simplification.</b> Unlike Fabric (which reflectively
 * proxies {@code ServerMessageEvents.ALLOW_CHAT_MESSAGE} to stay portable across
 * its obf/deobf carriers), NeoForge ships a typed, cancelable
 * {@link ServerChatEvent} on {@link NeoForge#EVENT_BUS}; this class subscribes a
 * plain {@code @SubscribeEvent} handler and cancels the event directly. No
 * reflection, no proxies.
 *
 * <p><b>Lifecycle.</b>
 * <ul>
 *   <li>{@link #open(UUID, List, String, String, MenuAction.Mode,
 *       MenuRedeemSubcommand.CartSink)} registers a pending entry in the
 *       per-UUID {@link #pending} map and sends a chat hint. Returns
 *       {@code true} when the prompt was queued.</li>
 *   <li>{@link #onServerChat(ServerChatEvent)} inspects every chat message and,
 *       when it matches a pending UUID, cancels the broadcast, drains the entry,
 *       and submits the assembled command via the viewer's
 *       {@link RTPCommandSender#performCommand}.</li>
 *   <li>A reaper task runs every {@link #REAP_PERIOD_TICKS} ticks via
 *       {@code RTP.scheduler.runTaskTimerAsynchronously} and drops any entry
 *       older than {@link #TTL_MILLIS}.</li>
 * </ul>
 *
 * <p><b>Thread-safety.</b> {@link #pending} is a {@link ConcurrentHashMap}. The
 * command dispatch is wrapped in {@code RTP.scheduler.runTask(...)} so it lands
 * on the server tick thread regardless of which thread fired the chat event.
 */
public final class NeoForgeChatPromptCallback implements MenuRedeemSubcommand.AnvilInputOpener {

    /** TTL after which a queued prompt is forgotten if the viewer never types. */
    static final long TTL_MILLIS = 30_000L;

    /** Reaper period (server ticks, 20 ticks = 1 second). */
    private static final long REAP_PERIOD_TICKS = 200L;

    private final ConcurrentHashMap<UUID, PendingPrompt> pending = new ConcurrentHashMap<>();

    private @Nullable MenuRedeemSubcommand.CartSink cartSink;

    /**
     * Whether the chat-listener registration succeeded. When {@code false} the
     * open path immediately returns false so the redeem subcommand can fall
     * through to {@code menuInvalid}.
     */
    private final boolean listenerInstalled;

    public NeoForgeChatPromptCallback() {
        boolean installed = false;
        try {
            NeoForge.EVENT_BUS.register(this);
            installed = true;
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][NeoForge] failed to install chat-prompt listener ("
                            + t.getClass().getSimpleName() + "): " + t.getMessage());
        }
        this.listenerInstalled = installed;
        if (installed) {
            try {
                RTP.scheduler.runTaskTimerAsynchronously(
                        this::reap, REAP_PERIOD_TICKS, REAP_PERIOD_TICKS);
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[RTP][NeoForge] chat-prompt reaper schedule failed ("
                                + t.getClass().getSimpleName() + "): " + t.getMessage());
            }
        }
    }

    @Override
    public void setCartSink(@Nullable MenuRedeemSubcommand.CartSink sink) {
        this.cartSink = sink;
    }

    /** Legacy 4-arg SAM target: equivalent to the RUN-mode overload with no cart sink. */
    @Override
    public boolean open(UUID viewer, List<String> parentPath, String paramName, String prefill) {
        return open(viewer, parentPath, paramName, prefill,
                io.github.dailystruggle.rtp.api.menu.MenuAction.Mode.RUN, null);
    }

    @Override
    public boolean open(UUID viewer, List<String> parentPath, String paramName,
                        String prefill, io.github.dailystruggle.rtp.api.menu.MenuAction.Mode mode,
                        @Nullable MenuRedeemSubcommand.CartSink sink) {
        if (!listenerInstalled) return false;
        if (viewer == null || paramName == null || paramName.isEmpty()) return false;
        RTPCommandSender target = RTP.serverAccessor.getSender(viewer);
        if (target == null) return false;
        // Replace any earlier pending prompt for the same viewer.
        pending.put(viewer, new PendingPrompt(
                parentPath, paramName, prefill, mode, sink, System.currentTimeMillis()));
        String prefillSuffix = (prefill == null || prefill.isEmpty())
                ? ""
                : " (current: " + prefill + ")";
        target.sendMessage("&7Type the new value for &f" + paramName + prefillSuffix
                + "&7 in chat. Type &fcancel&7 to abort.");
        return true;
    }

    /**
     * Server-thread entry point invoked once a queued prompt is matched. The
     * listener cancels the broadcast and then routes here so any command
     * dispatch lands on the tick thread.
     */
    void completePromptOnServerThread(UUID viewer, String typed) {
        PendingPrompt prompt = pending.remove(viewer);
        if (prompt == null) return;
        if (typed != null && typed.equalsIgnoreCase("cancel")) {
            RTPCommandSender sender = RTP.serverAccessor.getSender(viewer);
            if (sender != null) sender.sendMessage("&7menu prompt cancelled.");
            return;
        }
        try {
            dispatchPrompt(viewer, prompt, typed);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][NeoForge] chat-prompt dispatch failed for " + viewer
                            + ": " + t.getMessage(), t);
        }
    }

    private void dispatchPrompt(UUID viewer, PendingPrompt prompt, String typed) {
        if (prompt.mode() == io.github.dailystruggle.rtp.api.menu.MenuAction.Mode.STAGE) {
            String fileName = (prompt.parentPath() != null
                    && prompt.parentPath().size() >= 2
                    && "config".equalsIgnoreCase(prompt.parentPath().get(0)))
                    ? prompt.parentPath().get(1)
                    : null;
            MenuRedeemSubcommand.CartSink sink = (prompt.sink() != null)
                    ? prompt.sink()
                    : this.cartSink;
            if (sink != null && fileName != null) {
                sink.stage(viewer, fileName, prompt.paramName(), typed);
                submitCommand(viewer, "/rtp menu config file=" + fileName);
                return;
            }
            // No cart sink wired: fall through to RUN-mode dispatch.
        }
        StringBuilder sb = new StringBuilder("/rtp");
        if (prompt.parentPath() != null) {
            for (String segment : prompt.parentPath()) {
                if (segment == null || segment.isEmpty()) continue;
                sb.append(' ').append(segment);
            }
        }
        sb.append(' ').append(prompt.paramName()).append('=').append(typed);
        submitCommand(viewer, sb.toString());
    }

    private static void submitCommand(UUID viewer, String command) {
        RTPCommandSender sender = RTP.serverAccessor.getSender(viewer);
        if (sender == null) return;
        RTPPlayer player = RTP.serverAccessor.getPlayer(viewer);
        sender.performCommand(player, command);
    }

    /** Drops entries older than {@link #TTL_MILLIS}. */
    private void reap() {
        if (pending.isEmpty()) return;
        long cutoff = System.currentTimeMillis() - TTL_MILLIS;
        pending.entrySet().removeIf(e -> e.getValue().createdAtMillis() < cutoff);
    }

    /**
     * Typed NeoForge chat listener: when the speaking player is mid-prompt,
     * cancel the broadcast and route the typed value back onto the server tick
     * thread for command dispatch.
     */
    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        try {
            ServerPlayer player = event.getPlayer();
            if (player == null) return;
            UUID viewer = player.getUUID();
            if (viewer == null || pending.get(viewer) == null) return;
            String typed = event.getRawText();
            if (typed == null) return;
            // Cancel the broadcast - the typed value must not appear in chat.
            event.setCanceled(true);
            // Dispatch the completion on the server tick thread (S-005 / parity
            // with the Paper AnvilInputSession path).
            RTP.scheduler.runTask(() -> completePromptOnServerThread(viewer, typed));
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][NeoForge] chat-prompt listener threw "
                            + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
    }

    /** Visible for tests. */
    int pendingSize() { return pending.size(); }

    /** Visible for tests. */
    boolean isListenerInstalled() { return listenerInstalled; }

    /**
     * One queued chat prompt. Bound to {@link NeoForgeChatPromptCallback#pending}
     * by viewer UUID; consumed (removed) when the viewer types or when the
     * reaper drops it for age.
     */
    private record PendingPrompt(
            @Nullable List<String> parentPath,
            String paramName,
            @Nullable String prefill,
            io.github.dailystruggle.rtp.api.menu.MenuAction.Mode mode,
            @Nullable MenuRedeemSubcommand.CartSink sink,
            long createdAtMillis) {
        PendingPrompt {
            if (mode == null) mode = io.github.dailystruggle.rtp.api.menu.MenuAction.Mode.RUN;
        }
    }
}
