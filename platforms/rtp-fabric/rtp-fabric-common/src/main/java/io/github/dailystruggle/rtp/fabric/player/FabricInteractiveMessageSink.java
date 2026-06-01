package io.github.dailystruggle.rtp.fabric.player;

/**
 * Optional capability marker for an {@link io.github.dailystruggle.rtp.api.entity.RTPPlayer}
 * sink that can render an interactive (hover + click) chat line itself.
 *
 * <p><b>Why this exists.</b> The common {@code FabricRTPPlayer} lives in
 * {@code rtp-fabric-common} and is compiled by Loom against intermediary
 * mappings; its {@code net.minecraft.*} references do not link on the
 * deobfuscated MC 26.x runtime. The deobf carriers therefore ship their own
 * per-version {@code RTPPlayer} (e.g. {@code V26_1_R1FabricRTPPlayer}) whose
 * bytecode references only Mojang names. {@code FabricServerAccessor}'s rich
 * text path ({@code sendMessageWithRunCommand} / {@code sendMessage(hover,
 * click)} / {@code sendMessageAndSuggest}) can only build an interactive
 * {@code Component} for the common {@code FabricRTPPlayer}; for a per-version
 * sink it previously degraded to a plain, non-clickable message - which made
 * the chat menu renderer (and {@code /rtp admin}, {@code /rtp config}, …)
 * render dead, unclickable text on 26.x.
 *
 * <p>A per-version sink that implements this interface advertises that it can
 * build and deliver the hover/click {@code Component} on its own runtime, so
 * the accessor routes the menu/info clicks through it instead of the plain
 * fallback. Sinks that cannot (or choose not to) simply do not implement it
 * and keep the plain-text behaviour.
 */
public interface FabricInteractiveMessageSink {

    /**
     * Deliver a single interactive chat line carrying an optional hover
     * tooltip and an optional click command.
     *
     * @param message   the already-formatted (placeholders + legacy colour
     *                  codes resolved) primary text
     * @param hover     optional hover-tooltip text ({@code null}/empty disables hover)
     * @param click     optional literal command target ({@code null}/empty disables click)
     * @param run       {@code true} to fire the click as {@code RUN_COMMAND}
     *                  (auto-dispatch, used by the menu renderer), {@code false}
     *                  for {@code SUGGEST_COMMAND} (prefill the chat input)
     */
    void sendInteractive(String message, String hover, String click, boolean run);
}
