package io.github.dailystruggle.rtp.fabric.menu;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.menu.MenuRedeemSubcommand;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Fabric substitute for the Paper anvil-input modal (ADR-045) per
 * {@code rtp-fabric-ADR-012 section 3}. Implements
 * {@link MenuRedeemSubcommand.AnvilInputOpener} by registering the viewer
 * for an inline chat prompt: the next chat message the viewer sends is
 * consumed (cancelled so it does not broadcast) and dispatched as the
 * intended {@code /rtp ...} command, exactly as the Paper anvil GUI would.
 *
 * <p><b>Lifecycle.</b>
 * <ul>
 *   <li>{@link #open(UUID, List, String, String, MenuAction.Mode,
 *       MenuRedeemSubcommand.CartSink)} registers a pending entry in the
 *       per-UUID {@link #pending} map and sends a chat hint to the viewer
 *       describing the requested input. Returns {@code true} when the
 *       prompt was queued.</li>
 *   <li>{@link ChatMessageInterceptor} - a callback installed once at
 *       construction-time on {@code ServerMessageEvents.ALLOW_CHAT_MESSAGE}
 *       (reflectively, to keep this class compile-time-portable across the
 *       1.20.x / 1.21.x / 26.x carriers) - inspects every chat message and,
 *       when it matches a pending UUID, cancels the broadcast, drains the
 *       entry, and submits the assembled command via the viewer's
 *       {@link RTPCommandSender#performCommand}.</li>
 *   <li>A reaper task runs every {@link #REAP_PERIOD_TICKS} ticks via
 *       {@code RTP.scheduler.runTaskTimerAsynchronously} (AGENTS.md
 *       <i>Scheduler Usage</i> - no raw {@code Executors.*}) and drops any
 *       entry older than {@link #TTL_MILLIS}.</li>
 * </ul>
 *
 * <p><b>S-004 attribution.</b> If the chat listener cannot be registered
 * (Fabric API mismatch or absent on this runtime), the constructor logs a
 * WARNING and the {@link #open} path then returns {@code false} so the
 * caller (the {@code dispatch*} helper on {@link MenuRedeemSubcommand})
 * falls through to the configurable {@code menuInvalid} reject. The
 * fallback is the documented contract per the
 * {@link MenuRedeemSubcommand.AnvilInputOpener#open} Javadoc.
 *
 * <p><b>Thread-safety.</b> {@link #pending} is a {@link ConcurrentHashMap};
 * the chat callback may be invoked from any server-thread context. The
 * dispatch back into rtp-core (the reopen / RUN command submit) is wrapped
 * in {@code RTP.scheduler.runTask(...)} so it lands on the server tick
 * thread regardless of which thread fired the chat event.
 */
public final class FabricChatPromptCallback implements MenuRedeemSubcommand.AnvilInputOpener {

    /** TTL after which a queued prompt is forgotten if the viewer never types. */
    static final long TTL_MILLIS = 30_000L;

    /** Reaper period (server ticks, 20 ticks = 1 second). */
    private static final long REAP_PERIOD_TICKS = 200L;

    private final ConcurrentHashMap<UUID, PendingPrompt> pending = new ConcurrentHashMap<>();

    private @Nullable MenuRedeemSubcommand.CartSink cartSink;

    /**
     * Whether the chat-listener registration succeeded. When {@code false}
     * the open path immediately returns false so the redeem subcommand can
     * fall through to {@code menuInvalid}.
     */
    private final boolean listenerInstalled;

    public FabricChatPromptCallback() {
        boolean installed = false;
        try {
            installed = registerChatListener();
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][Fabric] failed to install chat-prompt listener ("
                            + t.getClass().getSimpleName() + "): " + t.getMessage());
        }
        this.listenerInstalled = installed;
        if (installed) {
            // Schedule the reaper. RTP.scheduler is wired before the command
            // tree is built (RTPFabricMod.onInitialize ordering), so this is
            // safe at construction time.
            try {
                RTP.scheduler.runTaskTimerAsynchronously(
                        this::reap, REAP_PERIOD_TICKS, REAP_PERIOD_TICKS);
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[RTP][Fabric] chat-prompt reaper schedule failed ("
                                + t.getClass().getSimpleName() + "): " + t.getMessage());
            }
        }
    }

    @Override
    public void setCartSink(@Nullable MenuRedeemSubcommand.CartSink sink) {
        this.cartSink = sink;
    }

    /**
     * Legacy 4-arg SAM target: equivalent to the RUN-mode overload with no
     * cart sink. Implementations that wire {@code AnvilInputSession} would
     * have called this before the staging-cart redesign; we preserve the
     * shape so the {@link MenuRedeemSubcommand.AnvilInputOpener} contract
     * holds.
     */
    @Override
    public boolean open(UUID viewer, List<String> parentPath, String paramName, String prefill) {
        return open(viewer, parentPath, paramName, prefill, MenuAction.Mode.RUN, null);
    }

    @Override
    public boolean open(UUID viewer, List<String> parentPath, String paramName,
                        String prefill, MenuAction.Mode mode,
                        @Nullable MenuRedeemSubcommand.CartSink sink) {
        if (!listenerInstalled) return false;
        if (viewer == null || paramName == null || paramName.isEmpty()) return false;
        RTPCommandSender target = RTP.serverAccessor.getSender(viewer);
        if (target == null) return false;
        // Replace any earlier pending prompt for the same viewer; the latest
        // menu interaction is the one the player is actually looking at.
        pending.put(viewer, new PendingPrompt(
                parentPath, paramName, prefill, mode, sink, System.currentTimeMillis()));
        // Send a chat hint so the viewer knows we're waiting on their input.
        // Locale-keyed wording is a follow-up - the wire phrasing here
        // matches ADR-012 section 3 step 1.
        String prefillSuffix = (prefill == null || prefill.isEmpty())
                ? ""
                : " (current: " + prefill + ")";
        target.sendMessage("&7Type the new value for &f" + paramName + prefillSuffix
                + "&7 in chat. Type &fcancel&7 to abort.");
        return true;
    }

    /**
     * Server-thread entry point invoked by the chat listener once a queued
     * prompt is matched. The listener cancels the broadcast and then routes
     * here so any command dispatch lands on the tick thread.
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
                    "[RTP][Fabric] chat-prompt dispatch failed for " + viewer
                            + ": " + t.getMessage(), t);
        }
    }

    private void dispatchPrompt(UUID viewer, PendingPrompt prompt, String typed) {
        // Mirror Paper AnvilInputSession's STAGE/RUN branch.
        if (prompt.mode() == MenuAction.Mode.STAGE) {
            // STAGE path: route the typed value into the cart, then re-open
            // the curated config-file page so the staged entry surfaces
            // under "Pending changes".
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
            // No cart sink wired: fall through to RUN-mode dispatch so the
            // player at least gets the command executed.
        }
        // RUN path: assemble `/rtp <parentPath...> <paramName>=<typed>`.
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
     * Registers the chat-message listener with the Fabric API.
     * Reflective dispatch so this class stays compile-portable across the
     * 1.20.x / 1.21.x / 26.x carriers (the {@code ServerMessageEvents.ALLOW_CHAT_MESSAGE}
     * symbol exists in all three but lives behind {@code modCompileOnly}).
     *
     * @return {@code true} when the listener was attached; {@code false}
     *         when the Fabric event type is not on the runtime classpath.
     */
    private boolean registerChatListener() throws ReflectiveOperationException {
        Class<?> eventsClass;
        try {
            eventsClass = Class.forName(
                    "net.fabricmc.fabric.api.message.v1.ServerMessageEvents");
        } catch (ClassNotFoundException missing) {
            RTP.log(Level.WARNING,
                    "[RTP][Fabric] ServerMessageEvents not available on this runtime; "
                            + "menu anvil-input substitute disabled.");
            return false;
        }
        Object event;
        try {
            event = eventsClass.getField("ALLOW_CHAT_MESSAGE").get(null);
        } catch (NoSuchFieldException missing) {
            RTP.log(Level.WARNING,
                    "[RTP][Fabric] ServerMessageEvents.ALLOW_CHAT_MESSAGE missing on this "
                            + "runtime; menu anvil-input substitute disabled.");
            return false;
        }
        Class<?> allowChatType = Class.forName(
                "net.fabricmc.fabric.api.message.v1.ServerMessageEvents$AllowChatMessage");
        Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                allowChatType.getClassLoader(),
                new Class<?>[] { allowChatType },
                new ChatMessageInterceptor());
        // Event<T>.register(T) erases to register(Object) at runtime, so a
        // direct getMethod("register", allowChatType) lookup fails with
        // NoSuchMethodException. Resolve the single-arg register(..) method by
        // name and assignability instead of by the specific listener type.
        java.lang.reflect.Method register = findRegisterMethod(event.getClass(), allowChatType);
        if (register == null) {
            RTP.log(Level.WARNING,
                    "[RTP][Fabric] no single-argument register(..) method found on "
                            + event.getClass().getName()
                            + "; menu anvil-input substitute disabled.");
            return false;
        }
        register.setAccessible(true);
        register.invoke(event, proxy);
        return true;
    }

    /**
     * Locates the single-argument {@code register(..)} method on the Fabric
     * {@code Event} runtime class whose parameter type can accept the listener
     * proxy. The generic {@code Event<T>.register(T)} erases to
     * {@code register(Object)}, so the lookup must be by name and arity rather
     * than by the concrete {@code AllowChatMessage} type.
     */
    private static java.lang.reflect.@Nullable Method findRegisterMethod(
            Class<?> eventClass, Class<?> allowChatType) {
        java.lang.reflect.Method fallback = null;
        Class<?> cursor = eventClass;
        while (cursor != null) {
            for (java.lang.reflect.Method m : cursor.getDeclaredMethods()) {
                if (!"register".equals(m.getName())) continue;
                if (m.getParameterCount() != 1) continue;
                Class<?> param = m.getParameterTypes()[0];
                if (param.isAssignableFrom(allowChatType)) return m;
                if (fallback == null) fallback = m;
            }
            cursor = cursor.getSuperclass();
        }
        return fallback;
    }

    /**
     * Reflective handler bound to {@code ServerMessageEvents.AllowChatMessage}.
     * The single SAM method is {@code allowChatMessage(SignedMessage,
     * ServerPlayer, MessageType.Parameters)}; we only inspect the player UUID
     * and message text and return {@code false} (cancel broadcast) when the
     * player is mid-prompt.
     */
    private final class ChatMessageInterceptor implements java.lang.reflect.InvocationHandler {
        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                throws Throwable {
            String name = method.getName();
            if ("toString".equals(name)) return "FabricChatPromptCallback$Interceptor";
            if ("hashCode".equals(name)) return System.identityHashCode(this);
            if ("equals".equals(name)) return proxy == args[0];
            if (!"allowChatMessage".equals(name)) {
                // Default-method on the interface (none today, but be safe).
                return method.getDefaultValue();
            }
            try {
                UUID viewer = extractPlayerUuid(args);
                if (viewer == null || pending.get(viewer) == null) return Boolean.TRUE;
                String typed = extractMessageText(args);
                if (typed == null) return Boolean.TRUE;
                // Dispatch the completion on the server tick thread to
                // satisfy S-005 and match the Paper AnvilInputSession path.
                RTP.scheduler.runTask(() -> completePromptOnServerThread(viewer, typed));
                // Cancel the broadcast - typed value should not appear in chat.
                return Boolean.FALSE;
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[RTP][Fabric] chat-prompt interceptor threw "
                                + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
                return Boolean.TRUE;
            }
        }

        /** Extract the player UUID from the (SignedMessage, ServerPlayer, ...) tuple. */
        private @Nullable UUID extractPlayerUuid(Object[] args) {
            if (args == null || args.length < 2 || args[1] == null) return null;
            try {
                Object player = args[1];
                java.lang.reflect.Method getUuid = player.getClass().getMethod("getUUID");
                Object uuid = getUuid.invoke(player);
                return (uuid instanceof UUID u) ? u : null;
            } catch (Throwable t) {
                // 1.20.x mojmap used `getUUID()`; intermediary `getUuid()` is
                // also a candidate on obf carriers.
                try {
                    Object player = args[1];
                    java.lang.reflect.Method m = player.getClass().getMethod("getUuid");
                    Object uuid = m.invoke(player);
                    return (uuid instanceof UUID u) ? u : null;
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }

        /** Extract the typed text from the {@code SignedMessage} arg. */
        private @Nullable String extractMessageText(Object[] args) {
            if (args == null || args.length < 1 || args[0] == null) return null;
            Object signed = args[0];
            // SignedMessage exposes signedContent() returning the raw String
            // (1.20+). Best-effort reflective access.
            for (String accessor : new String[] { "signedContent", "decoratedContent" }) {
                try {
                    java.lang.reflect.Method m = signed.getClass().getMethod(accessor);
                    Object out = m.invoke(signed);
                    if (out instanceof String s) return s;
                    if (out != null) return out.toString();
                } catch (Throwable ignored) { /* try next accessor */ }
            }
            return null;
        }
    }

    /** Visible for tests. */
    int pendingSize() { return pending.size(); }

    /** Visible for tests. */
    boolean isListenerInstalled() { return listenerInstalled; }

    /**
     * One queued chat prompt. Bound to {@link FabricChatPromptCallback#pending}
     * by viewer UUID; consumed (removed) when the viewer types or when the
     * reaper drops it for age.
     */
    private record PendingPrompt(
            @Nullable List<String> parentPath,
            String paramName,
            @Nullable String prefill,
            MenuAction.Mode mode,
            @Nullable MenuRedeemSubcommand.CartSink sink,
            long createdAtMillis) {
        PendingPrompt {
            if (mode == null) mode = MenuAction.Mode.RUN;
        }
    }
}
