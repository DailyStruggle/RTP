package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorChannel;
import io.github.dailystruggle.rtp.common.commands.editor.channel.TrustedEditors;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Subcommand {@code /rtp editor untrust key=<fingerprint prefix|all>} (ADR-106 §5.2): revokes
 * trusted browser keys, both in {@code <dataFolder>/editor/trusted-editors.json} and in every open
 * channel, so a revoked page's next signed message is refused. The prefix is the start of the
 * fingerprint shown in the trust prompt (4-64 hex characters). Off the main thread; every outcome is a
 * configurable message.
 */
public class UntrustCmd extends BaseRTPCmdImpl {

    public static final String PERMISSION = EditorCmd.PERMISSION;

    public UntrustCmd(@Nullable CommandsAPICommand parent) {
        super(parent);
        addParameter("key", new CommandParameter(PERMISSION, "trusted editor key fingerprint prefix, or all",
                (uuid, val) -> val != null && TrustedEditors.isSelector(val.trim().toLowerCase(Locale.ROOT))) {
            @Override
            public Set<String> values() {
                return Set.of("all");
            }
        });
    }

    @Override
    public String name() {
        return "untrust";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public String description() {
        return "revoke trusted web editor browsers by key fingerprint prefix, or all (ADR-106)";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand) {
        if (nextCommand != null) return true;
        String selector = null;
        if (parameterValues != null) {
            List<String> v = parameterValues.get("key");
            if (v != null && !v.isEmpty() && v.get(0) != null) selector = v.get(0).trim().toLowerCase(Locale.ROOT);
        }
        if (!TrustedEditors.isSelector(selector)) {
            EditorChannelWiring.tell(callerId, EditorChannelWiring.message("editorUntrustUsage",
                    "[P0] Usage: /rtp editor untrust key=<fingerprint prefix, at least 4 characters | all>"));
            return true;
        }
        final String sel = selector;
        EditorSecurity.supplyAsync(() -> untrust(sel)).whenComplete((removed, t) -> {
            if (t != null) {
                RTP.log(Level.WARNING, "[editor] untrust command failed: " + t.getMessage(), t);
                EditorChannelWiring.tell(callerId, EditorChannelWiring.message("editorUntrustFailed",
                        "[P0] Could not update the trusted editor list: [reason]").replace("[reason]", String.valueOf(t.getMessage())));
                return;
            }
            if (removed.isEmpty()) {
                EditorChannelWiring.tell(callerId, EditorChannelWiring.message("editorUntrustNone",
                        "[P0] No trusted editor browser matches [key].").replace("[key]", sel));
            } else {
                RTP.log(Level.INFO, "[editor] " + removed.size() + " editor key(s) untrusted (" + sel + ")");
                EditorChannelWiring.tell(callerId, EditorChannelWiring.message("editorUntrustRemoved",
                        "[P0] Untrusted [count] web editor browser(s); they must be trusted again to preview or Hot-Apply.")
                        .replace("[count]", String.valueOf(removed.size())));
            }
        });
        return true;
    }

    /** Revokes in open channels first, then the file; the union of both. Disk I/O. */
    static Set<String> untrust(String selector) {
        Set<String> removed = new LinkedHashSet<>(EditorChannel.untrustAny(selector));
        try {
            removed.addAll(TrustedEditors.load(EditorChannelWiring.editorDir().resolve(EditorChannelWiring.TRUSTED_FILE))
                    .remove(selector));
        } catch (IOException e) {
            throw new UncheckedIOException(e.getMessage(), e);
        }
        return removed;
    }
}
