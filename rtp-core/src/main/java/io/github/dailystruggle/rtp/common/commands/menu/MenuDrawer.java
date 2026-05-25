package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.jetbrains.annotations.Nullable;

/**
 * Single render boundary for the menu subsystem.
 *
 * <p>Extracted from {@code MenuRedeemSubcommand} as part of the ADR-050
 * menu-package split (item 1c.4 of
 * {@code docs/dev/scratch/CHECKLIST-concrete-menu-commands.md}). Previously
 * every {@code dispatchOpenXxx} method inlined the same wrap:
 *
 * <pre>{@code
 * try {
 *     renderer.render(viewer, model);
 *     return true;
 * } catch (RuntimeException e) {
 *     RTP.log(Level.WARNING, "menu <context> render failed for " + viewer
 *             + ": " + e.getMessage(), e);
 *     reject(viewer, MessagesKeys.menuInvalid,
 *             "menu <context> rejected: renderer failure", messageMethod);
 *     return false;
 * }
 * }</pre>
 *
 * <p>That pattern is consolidated here. Callers pass the {@code MenuRenderer},
 * the viewer, the model, an optional player-facing message sink, an optional
 * S-004 audit message sink (from {@code MenuRedeemSubcommand.reject}), and a
 * short {@code context} label used to disambiguate WARN log lines (e.g.
 * {@code "param-picker"}, {@code "admin-panel"}).
 *
 * <p>Package-private: only the dispatcher and the menu leaves under this
 * package may call into the drawer.
 */
final class MenuDrawer {

    private MenuDrawer() {
        // Static helper; no instances.
    }

    /**
     * Render {@code model} to {@code viewer} via {@code renderer}, applying
     * the canonical S-004 reject path on any {@link RuntimeException} thrown
     * by the renderer.
     *
     * @param renderer the renderer to invoke; must be non-null. The caller
     *                 is responsible for the null-check on the configured
     *                 renderer field; passing {@code null} is a programming
     *                 error and is fast-failed via NPE rather than masked.
     * @param viewer   the clicking player's UUID.
     * @param model    the model to render; must be non-null.
     * @param messageMethod player-facing message sink for the {@code menuInvalid}
     *                      reject path on renderer failure; may be {@code null}.
     * @param rejecter audit hook invoked on renderer failure with a
     *                 short S-004 diagnostic string. Implemented by
     *                 {@code MenuRedeemSubcommand::reject} (curried over
     *                 {@code (viewer, MessagesKeys.menuInvalid, msg, messageMethod)});
     *                 may be {@code null} to suppress the audit (tests).
     * @param context  short label naming the dispatch site (e.g.
     *                 {@code "param-picker"}, {@code "admin-panel"}).
     *                 Included verbatim in the WARN log and the audit
     *                 diagnostic.
     * @return {@code true} on successful render; {@code false} on
     *         {@link RuntimeException} from the renderer.
     */
    static boolean draw(MenuRenderer renderer,
                        UUID viewer,
                        MenuModel model,
                        @Nullable Consumer<String> messageMethod,
                        @Nullable Rejecter rejecter,
                        String context) {
        return draw(renderer, viewer, model, messageMethod, rejecter, context, "");
    }

    /**
     * Overload that carries extra diagnostic fields (e.g. {@code "node=foo param=bar"})
     * to be appended verbatim to the WARN log line, preserving the
     * per-call-site context that the pre-extraction inline blocks logged.
     * The audit-diagnostic reject string is unaffected (the operator-facing
     * rejection message stays terse).
     */
    static boolean draw(MenuRenderer renderer,
                        UUID viewer,
                        MenuModel model,
                        @Nullable Consumer<String> messageMethod,
                        @Nullable Rejecter rejecter,
                        String context,
                        String extraWarnDiag) {
        try {
            renderer.render(viewer, model);
            return true;
        } catch (RuntimeException e) {
            String tail = (extraWarnDiag == null || extraWarnDiag.isEmpty())
                    ? "" : " " + extraWarnDiag;
            RTP.log(Level.WARNING,
                    "menu " + context + " render failed for " + viewer
                            + tail + ": " + e.getMessage(), e);
            if (rejecter != null) {
                rejecter.reject(viewer, MessagesKeys.menuInvalid,
                        "menu " + context + " rejected: renderer failure",
                        messageMethod);
            }
            return false;
        }
    }

    /**
     * SAM bridge for {@code MenuRedeemSubcommand.reject(UUID, MessagesKeys,
     * String, Consumer<String>)}. Declared here so {@link MenuDrawer} does
     * not depend on the dispatcher type.
     */
    @FunctionalInterface
    interface Rejecter {
        void reject(UUID viewer,
                    MessagesKeys key,
                    String auditDiagnostic,
                    @Nullable Consumer<String> messageMethod);
    }
}
