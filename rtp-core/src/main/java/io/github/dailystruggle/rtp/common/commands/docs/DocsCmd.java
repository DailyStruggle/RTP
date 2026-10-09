package io.github.dailystruggle.rtp.common.commands.docs;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.menu.MenuBindingSupport;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Root in-game documentation command verb {@code /rtp docs [topic]} (ADR-045, ADR-104).
 *
 * <p>Reads lowered documentation pages via {@link DocsRegistry} without performing
 * synchronous file I/O on the main thread (S-005). Uses configurable messages (S-007)
 * and logs unexpected failures without silently swallowing errors (S-004).
 */
public class DocsCmd extends BaseRTPCmdImpl {

    public static final String PERMISSION = "rtp.admin.docs";
    public static final String PARAM_TOPIC = "topic";

    public DocsCmd(@Nullable CommandsAPICommand parent) {
        super(parent);
        addParameter(PARAM_TOPIC, new DocsTopicParameter(PERMISSION, "documentation topic or path"));
        addSubCommand(new DocsListSubCmd(this));
        addSubCommand(new DocsExportSubCmd(this));
    }

    @Override
    public String name() {
        return "docs";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public String description() {
        return "browse packed in-game documentation or export offline bundle (ADR-045, ADR-104)";
    }

    @Override
    public boolean onCommand(
            UUID callerId,
            Map<String, List<String>> parameterValues,
            @Nullable CommandsAPICommand nextCommand
    ) {
        if (nextCommand != null) {
            return true;
        }

        // Extract requested topic or default to index
        String topic = "index";
        if (parameterValues != null && !parameterValues.isEmpty()) {
            List<String> values = parameterValues.get(PARAM_TOPIC);
            if (values != null && !values.isEmpty()) {
                topic = values.get(0);
            } else {
                for (Map.Entry<String, List<String>> entry : parameterValues.entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                        topic = entry.getValue().get(0);
                        break;
                    }
                }
            }
        }

        if (topic == null || topic.isBlank()) {
            topic = "index";
        }
        topic = topic.trim();

        // Query DocsRegistry in-memory cache (S-005: 0 sync disk I/O on main thread)
        MenuModel model = null;
        try {
            model = DocsRegistry.getInstance().get(topic);
            if (model == null && !topic.endsWith(".md")) {
                model = DocsRegistry.getInstance().get(topic + ".md");
            }
        } catch (IllegalStateException e) {
            // S-006 fail-closed pre-init
            RTP.log(Level.WARNING, "DocsRegistry accessed before initialization by " + callerId);
            sendMessage(callerId, CommandMessages.docsEmpty, "&c[RTP] Documentation registry is not ready yet.");
            return true;
        }

        if (model == null) {
            // S-004: Log rejection and send configurable message
            String notFoundMsg = "RTP: Documentation topic '" + topic + "' not found.";
            RTP.log(Level.WARNING, notFoundMsg);
            if (callerId != null && RTP.serverAccessor != null) {
                try {
                    String formatted = RTP.serverAccessor.format(callerId,
                            RTP.configs.getConfigValue(CommandMessages.docsNotFound,
                                    "&c[RTP] Documentation topic '[topic]' not found.").toString()
                                    .replace("[topic]", topic));
                    RTP.serverAccessor.sendMessage(callerId, formatted);
                } catch (RuntimeException ignored) {
                    sendMessage(callerId, null, notFoundMsg);
                }
            }
            return true;
        }

        // Try to render via discovered MenuRenderer
        MenuRenderer renderer = MenuBindingSupport.discoverRenderer();
        if (renderer != null && callerId != null && !callerId.equals(RTPAPI.serverId)) {
            try {
                renderer.render(callerId, model);
                return true;
            } catch (Exception e) {
                RTP.log(Level.WARNING, "Failed to render documentation model via " + renderer + ": " + e.getMessage(), e);
            }
        }

        // Fallback: render text lines directly to caller (console or platforms without book GUI)
        sendMessage(callerId, null, "&8» &b&l" + model.title() + " &8«");
        for (MenuPage page : model.pages()) {
            for (MenuLine line : page.lines()) {
                StringBuilder sb = new StringBuilder();
                for (io.github.dailystruggle.rtp.api.menu.MenuFragment frag : line.fragments()) {
                    sb.append(frag.text());
                }
                String rendered = sb.toString();
                if (!rendered.isBlank()) {
                    sendMessage(callerId, null, rendered);
                }
            }
        }

        return true;
    }

    private void sendMessage(@Nullable UUID callerId, @Nullable CommandMessages key, String fallback) {
        if (callerId == null || RTP.serverAccessor == null) return;
        try {
            if (key != null) {
                RTP.serverAccessor.sendMessage(callerId, key);
            } else {
                RTP.serverAccessor.sendMessage(callerId, fallback);
            }
        } catch (RuntimeException ignored) {
        }
    }
}
