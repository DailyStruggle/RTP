package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.common.RTP;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Permission gates shared by every menu leaf and the legacy redeem dispatch
 * chain. Extracted from {@code MenuRedeemSubcommand} as part of the ADR-050
 * menu-package split (see {@code CHECKLIST-concrete-menu-commands.md}).
 *
 * <p>Each gate:
 * <ul>
 *   <li>Returns {@code false} (deny-by-default) when the probe factory yields
 *       a {@code null} probe.</li>
 *   <li>Returns {@code false} and logs WARN when the probe throws
 *       {@link RuntimeException} — REQ-RTP-S-007 (exception in a permission
 *       probe is a denial, never a silent allow).</li>
 * </ul>
 *
 * <p>Package-private: only the menu leaves and {@code MenuRedeemSubcommand}
 * may hold an instance.
 */
final class MenuPermissionGates {

    // Mirrors of the public constants on MenuRedeemSubcommand (kept in sync;
    // tests and AdminPanelBuilder still reference the latter directly).
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
