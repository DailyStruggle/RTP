package io.github.dailystruggle.rtp.neoforge.commands;

import io.github.dailystruggle.rtp.common.commands.CoreRtpRoot;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;

/**
 * NeoForge root {@code /rtp} command.
 *
 * <p>Thin platform shell over the platform-neutral {@link CoreRtpRoot}: the
 * entire command tree (parameters, subcommands, the {@code player} / {@code world}
 * parameter sources backed by {@code RTP.serverAccessor}, the teleport dispatch,
 * the menu permission probe and the no-op success / fail events) lives in
 * {@code rtp-core}. The {@code /rtp menu} renderer and chat-prompt input opener
 * are resolved by {@code rtp-core}'s {@code MenuBindingSupport} through the
 * {@code MenuRendererProvider} / {@code AnvilInputOpenerProvider}
 * {@link java.util.ServiceLoader} SPIs - the NeoForge adapter ships
 * {@code NeoForgeBookMenuRendererProvider} ({@code "book"}) and
 * {@code NeoForgeAnvilInputOpenerProvider}, discovered exactly as the Paper
 * providers are on the Bukkit family - so this class no longer names a renderer
 * directly (ADR-070).</p>
 *
 * <p>This class exists only so the Brigadier adapter has a concrete, named
 * {@link io.github.dailystruggle.commandsapi.common.CommandsAPICommand} to
 * convert; all behaviour is inherited from {@link CoreRtpRoot}.</p>
 */
public final class RTPCmdNeoForgeRoot extends CoreRtpRoot implements RTPCmd {

    public RTPCmdNeoForgeRoot() {
        super();
    }
}
