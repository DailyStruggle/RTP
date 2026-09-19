package io.github.dailystruggle.rtp.fabric.commands;

import io.github.dailystruggle.commandsapi.brigadier.BrigadierBridgeContext;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.logging.Level;

/**
 * Bridge helper that registers the /rtp Brigadier root with Fabric's
 * {@code CommandRegistrationCallback} from inside {@code rtp-fabric-common},
 * where {@code net.minecraft.*} imports are permitted.
 *
 * <p>Pulling this registration out of {@code RTPFabricMod} (which lives in
 * {@code rtp-plugin}) keeps {@code CommandBuildContext} (intermediary
 * {@code class_7157}), {@code CommandSelection}, and {@code CommandSourceStack}
 * out of the entrypoint class's constant pool. On MC 26.1's deobfuscated
 * runtime those intermediary names are not exposed, so the entrypoint would
 * otherwise fail JVM verification on load
 * (see rtp-fabric-ADR-002 / rtp-fabric-ADR-007).
 */
public final class FabricCommandRegistrar {

    private FabricCommandRegistrar() {
    }

    /**
     * Register a {@code CommandRegistrationCallback} that wires the supplied
     * Brigadier root + bridge context into the dispatcher each time the
     * server (re)builds its command tree.
     *
     * <p>Parameters are typed as {@link Object} so the calling entrypoint
     * never references {@code CommandSourceStack} / {@code BrigadierBridgeContext}
     * with a concrete NM source type.
     *
     * @param root      the {@link CommandsAPICommand} root produced by
     *                  {@code RTPCmdFabric.buildRoot()} (must be non-null).
     * @param bridgeCtx the {@link BrigadierBridgeContext} (raw / Object source);
     *                  must be non-null.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void registerRtpCommand(Object root, Object bridgeCtx) {
        if (root == null || bridgeCtx == null) {
            RTP.log(Level.WARNING,
                    "[RTP][Fabric] registerRtpCommand skipped: null root or bridgeCtx.");
            return;
        }
        // Reflective registration. A direct call to
        // CommandRegistrationCallback.EVENT.register((d, registry, env) -> ...)
        // emits a synthetic lambda whose method descriptor references
        // CommandBuildContext (intermediary class_7157) and CommandSelection
        // (class_5364) in the constant pool. On MC 26.1.2's deobfuscated
        // runtime those intermediary aliases are absent, so the JVM verifier
        // throws NoClassDefFoundError when the lambda class is loaded -
        // which happens at register() time, not at invocation.
        // Build the SAM via Proxy.newProxyInstance against the reflectively
        // loaded callback interface so no intermediary symbol enters this
        // class's bytecode.
        try {
            Class<?> callbackCls = Class.forName(
                    "net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback");
            Class<?> eventCls = Class.forName("net.fabricmc.fabric.api.event.Event");
            Object event = callbackCls.getField("EVENT").get(null);
            java.lang.reflect.Method registerOnEvent = eventCls.getMethod("register", Object.class);

            final BrigadierBridgeContext rawBridge = (BrigadierBridgeContext) bridgeCtx;
            final CommandsAPICommand rootCmd = (CommandsAPICommand) root;

            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    callbackCls.getClassLoader(),
                    new Class<?>[]{callbackCls},
                    (p, method, args) -> {
                        if (args == null || args.length < 1) return null;
                        Object dispatcher = args[0];
                        Object env = args.length >= 3 ? args[2] : null;
                        try {
                            RTP.log(Level.INFO,
                                    "[RTP] Registering /rtp Brigadier root with dispatcher (env=" + env + ").");
                            RTPCmdFabric.register(
                                    (com.mojang.brigadier.CommandDispatcher) dispatcher,
                                    rootCmd,
                                    rawBridge);
                        } catch (Throwable inner) {
                            RTP.log(Level.WARNING,
                                    "[RTP][Fabric] /rtp Brigadier registration handler failed: "
                                            + inner.getClass().getSimpleName() + ": " + inner.getMessage(),
                                    inner);
                        }
                        return null;
                    });
            registerOnEvent.invoke(event, proxy);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][Fabric] CommandRegistrationCallback registration failed: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
