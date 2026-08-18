package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.common.RTP;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Permission gates shared by menu leaves and the legacy redeem dispatch chain (ADR-050).
 * Denies by default on null probe; logs WARN on probe exception (REQ-RTP-S-007).
 */
final class MenuPermissionGates {

    // Mirrors of MenuRedeemSubcommand permission constants.
    static final String CONFIG_VIEW_PERMISSION = MenuRedeemSubcommand.CONFIG_VIEW_PERMISSION;
    static final String ADMIN_MENU_PERMISSION = MenuRedeemSubcommand.ADMIN_MENU_PERMISSION;
    static final String INFO_PERMISSION = "rtp.info";

    private final Function<UUID, Predicate<String>> probeFactory;

    MenuPermissionGates(Function<UUID, Predicate<String>> probeFactory) {
        this.probeFactory = Objects.requireNonNull(probeFactory, "probeFactory");
    }

    boolean hasConfigView(UUID senderId) {
        return test(senderId, CONFIG_VIEW_PERMISSION, "config-view");
    }

    boolean hasAdminMenu(UUID senderId) {
        return test(senderId, ADMIN_MENU_PERMISSION, "admin-panel");
    }

    boolean hasInfo(UUID senderId) {
        return test(senderId, INFO_PERMISSION, "info");
    }

    private boolean test(UUID senderId, String perm, String label) {
        Predicate<String> probe = probeFactory.apply(senderId);
        if (probe == null) return false;
        try {
            return probe.test(perm);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu " + label + " permission probe threw for " + senderId
                            + ": " + e.getMessage(), e);
            return false;
        }
    }
}
