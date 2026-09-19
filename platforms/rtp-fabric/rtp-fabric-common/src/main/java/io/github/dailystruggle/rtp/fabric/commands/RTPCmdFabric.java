package io.github.dailystruggle.rtp.fabric.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.dailystruggle.commandsapi.brigadier.BrigadierBridgeContext;
import io.github.dailystruggle.commandsapi.brigadier.BrigadierCommandAdapter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import org.jetbrains.annotations.NotNull;

/**
 * commands-api-ADR-001 - Brigadier Bridge: Fabric-side registration shim.
 *
 * <p>Thin wrapper that turns a {@code commands-api} root command into a
 * Brigadier {@link LiteralArgumentBuilder} and registers it with a Fabric
 * {@link CommandDispatcher}. The actual conversion lives in
 * {@link BrigadierCommandAdapter}; this class exists so the
 * Fabric mod entrypoint can call a single method.
 *
 * <p>This class intentionally does not import any
 * {@code net.minecraft.*} / {@code net.fabricmc.*} types: keeping the
 * source type generic ({@code <S>}) means {@code rtp-fabric-common}
 * stays buildable without a working Loom toolchain, and the eventual
 * version-specific Fabric module supplies the concrete
 * {@code ServerCommandSource} when registering against
 * {@code CommandRegistrationCallback}.
 */
public final class RTPCmdFabric {

    private RTPCmdFabric() {
        // static utility
    }

    /**
     * Build the Brigadier tree for {@code root} using {@code ctx} and register it
     * with {@code dispatcher}.
     *
     * @param dispatcher the Brigadier dispatcher (typically Fabric's
     *                   {@code CommandRegistrationCallback}-supplied dispatcher).
     * @param root       the {@code commands-api} root command (e.g. the RTP root).
     * @param ctx        platform bridge supplying source-to-UUID, permission, and
     *                   message lambdas (built by the Fabric entrypoint with the
     *                   appropriate {@code ServerCommandSource} accessors).
     * @param <S>        Brigadier source type ({@code ServerCommandSource} on Fabric).
     */
    public static <S> void register(@NotNull CommandDispatcher<S> dispatcher,
                                    @NotNull CommandsAPICommand root,
                                    @NotNull BrigadierBridgeContext<S> ctx) {
        LiteralArgumentBuilder<S> builder = BrigadierCommandAdapter.toBrigadier(root, ctx);
        dispatcher.register(builder);
    }
}
