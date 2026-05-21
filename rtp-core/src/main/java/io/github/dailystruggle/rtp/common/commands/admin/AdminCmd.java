package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.jetbrains.annotations.Nullable;

/**
 * Top-level {@code /rtp admin} verb. Per the locked design decision
 * (2026-05-20, {@code PROPOSAL-admin-panel-prefabs.md} v3.1), the bare form
 * dispatches {@link io.github.dailystruggle.rtp.api.menu.MenuAction.OpenAdminPanel}
 * - i.e. opens the same curated admin panel that the {@code Admin panel} entry
 * row in {@code /rtp menu} opens. The {@code prefab} child hosts the
 * prefab subtree ({@code list} / {@code apply} / {@code confirm} / {@code rollback}).
 *
 * <p>The actual panel-opening is delegated to a {@link Consumer Consumer&lt;UUID&gt;}
 * injected at registration time by the platform adapter (see {@code RTPCmdBukkit}'s
 * menu wiring): {@code rtp-core} does not depend on a renderer or on
 * {@code MenuRedeemSubcommand} directly. When the opener is {@code null}
 * (tests, headless wiring, console caller without a menu surface), the bare
 * form rejects with the configurable {@code menuInvalid} message and logs at
 * WARNING per REQ-RTP-S-004 / S-007.
 *
 * <p>Permission: the <strong>bare</strong> form gates on {@code rtp.menu.admin}
 * (same key as the {@code Admin panel} entry row); the {@code prefab} child
 * gates on {@code rtp.admin.prefab} (see {@code PrefabCommand}).
 */
public class AdminCmd extends BaseRTPCmdImpl {

    /** Permission key for the bare {@code /rtp admin} form. Mirrors {@code MenuRedeemSubcommand.ADMIN_MENU_PERMISSION}. */
    public static final String PERMISSION = "rtp.menu.admin";

    private final @Nullable Consumer<UUID> openAdminPanel;

    /**
     * Production constructor with an injected admin-panel opener.
     *
     * @param parent          the parent command tree (the {@code /rtp} root), or {@code null}.
     * @param openAdminPanel  callback invoked from the bare form to open the admin panel
     *                        for the given caller, or {@code null} to disable the bare form.
     */
    public AdminCmd(@Nullable CommandsAPICommand parent,
                    @Nullable Consumer<UUID> openAdminPanel) {
        super(parent);
        this.openAdminPanel = openAdminPanel;
    }

    /** Convenience constructor with no opener; bare form rejects until one is wired. */
    public AdminCmd(@Nullable CommandsAPICommand parent) {
        this(parent, null);
    }

    @Override
    public String name() {
        return "admin";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public String description() {
        return "open the admin panel; subcommands manage prefabs";
    }

    @Override
    public boolean onCommand(UUID callerId,
                             Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand) {
        if (nextCommand != null) {
            return nextCommand.onCommand(callerId, parameterValues, null);
        }
        if (openAdminPanel == null) {
            RTP.log(Level.WARNING,
                    "/rtp admin invoked by " + callerId
                            + " with no admin-panel opener wired; rejecting");
            rejectMenuInvalid(callerId);
            return false;
        }
        try {
            openAdminPanel.accept(callerId);
            return true;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "/rtp admin opener threw for " + callerId + ": " + e.getMessage(), e);
            rejectMenuInvalid(callerId);
            return false;
        }
    }

    private static void rejectMenuInvalid(UUID callerId) {
        if (callerId == null || RTP.serverAccessor == null) return;
        try {
            RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, MessagesKeys.menuInvalid);
        } catch (RuntimeException ignored) {
            // serverAccessor failures (test scaffolds without a sender) are not fatal.
        }
    }
}
