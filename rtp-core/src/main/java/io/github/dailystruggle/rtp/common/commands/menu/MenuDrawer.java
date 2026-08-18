package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.jetbrains.annotations.Nullable;
/**
 * Render boundary for the menu subsystem (ADR-050).
 * Consolidated S-004 error handling wrapper around {@link MenuRenderer#render}.
 */
final class MenuDrawer {

    private MenuDrawer() {
        // Static helper; no instances.
    }

    /**
     * Renders {@code model} to {@code viewer} via {@code renderer}, applying S-004 rejection on failure.
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
                rejecter.reject(viewer, CommandMessages.menuInvalid,
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
                    Enum<?> key,
                    String auditDiagnostic,
                    @Nullable Consumer<String> messageMethod);
    }
}
