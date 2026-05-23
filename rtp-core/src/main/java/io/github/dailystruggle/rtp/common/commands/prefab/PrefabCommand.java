package io.github.dailystruggle.rtp.common.commands.prefab;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * The {@code prefab} subtree under {@code /rtp admin}. Per the locked design
 * decision (2026-05-20, {@code PROPOSAL-admin-panel-prefabs.md} v3.1) it
 * exposes four verbs: {@code list}, {@code apply &lt;id&gt;},
 * {@code confirm &lt;id&gt; &lt;token&gt;}, {@code rollback &lt;id&gt;}.
 *
 * <p><strong>Session 4a scope:</strong> {@code list} and {@code apply} are
 * fully wired - {@code apply} computes the diff via {@link PrefabApplier},
 * mints a nonce via {@link PrefabNonceStore}, and surfaces the diff + token
 * over chat. {@code confirm} and {@code rollback} are stubs that audit-log
 * and reject with an explicit "on-disk write lands in Session 4b" notice;
 * they validate / consume the nonce so the security shape is testable today,
 * but they do not yet touch disk or invoke reload. See
 * {@code CHECKLIST-admin-panel-prefabs.md} step 4 for the full split.
 *
 * <p>Permission: every verb gates on {@code rtp.admin.prefab}. The parent
 * {@code /rtp admin} verb additionally gates on {@code rtp.menu.admin} for
 * its bare form (see {@code AdminCmd}).
 */
public class PrefabCommand extends BaseRTPCmdImpl {

    /** Permission key for the {@code prefab} subtree (every verb shares it). */
    public static final String PERMISSION = "rtp.admin.prefab";

    private final PrefabNonceStore nonceStore;

    public PrefabCommand(@Nullable CommandsAPICommand parent) {
        this(parent, new PrefabNonceStore());
    }

    /** Test constructor: inject a deterministic nonce store. */
    public PrefabCommand(@Nullable CommandsAPICommand parent, PrefabNonceStore nonceStore) {
        super(parent);
        this.nonceStore = Objects.requireNonNull(nonceStore, "nonceStore");
        addSubCommand(new PrefabListCmd(this));
        addSubCommand(new PrefabApplyCmd(this, this.nonceStore));
        addSubCommand(new PrefabConfirmCmd(this, this.nonceStore));
        addSubCommand(new PrefabRollbackCmd(this));
    }

    /** Exposed for tests and for the menu wiring in Session 5. */
    public PrefabNonceStore nonceStore() {
        return nonceStore;
    }

    @Override
    public String name() {
        return "prefab";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public String description() {
        return "list, preview, apply, and roll back bundled config prefabs";
    }

    @Override
    public boolean onCommand(UUID callerId,
                             Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand) {
        // CommandsAPI already drives the next command in the sequence; this
        // bare verb has no work of its own when called as a tree node.
        return true;
    }
}
