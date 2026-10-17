package io.github.dailystruggle.rtp.guiaddon.common;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class MenuActionsTest {

    private RTPServerAccessor originalServerAccessor;
    private RTPScheduler originalScheduler;
    private RTPServerAccessor originalApiServerAccessor;

    private final List<String> dispatchedCommands = new ArrayList<>();
    private final List<String> sentMessages = new ArrayList<>();
    private final Set<String> permissions = new HashSet<>();

    @BeforeEach
    void setUp() {
        originalServerAccessor = RTP.serverAccessor;
        originalScheduler = RTP.scheduler;
        originalApiServerAccessor = RTPAPI.serverAccessor;

        dispatchedCommands.clear();
        sentMessages.clear();
        permissions.clear();

        // Grant master admin permission by default
        permissions.add("rtp.admin");

        RTPCommandSender mockSender = (RTPCommandSender) Proxy.newProxyInstance(
                RTPCommandSender.class.getClassLoader(),
                new Class<?>[]{RTPCommandSender.class},
                (proxy, method, args) -> {
                    if ("hasPermission".equals(method.getName())) {
                        return permissions.contains(args[0]);
                    }
                    return null;
                });

        RTPPlayer mockPlayer = (RTPPlayer) Proxy.newProxyInstance(
                RTPPlayer.class.getClassLoader(),
                new Class<?>[]{RTPPlayer.class},
                (proxy, method, args) -> null);

        RTPServerAccessor mockAccessor = (RTPServerAccessor) Proxy.newProxyInstance(
                RTPServerAccessor.class.getClassLoader(),
                new Class<?>[]{RTPServerAccessor.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getSender".equals(name)) {
                        return mockSender;
                    }
                    if ("getPlayer".equals(name)) {
                        return mockPlayer;
                    }
                    if ("executeCommand".equals(name)) {
                        dispatchedCommands.add((String) args[1]);
                        return true;
                    }
                    if ("sendMessage".equals(name)) {
                        sentMessages.add((String) args[1]);
                        return null;
                    }
                    return null;
                });

        RTPScheduler mockScheduler = (RTPScheduler) Proxy.newProxyInstance(
                RTPScheduler.class.getClassLoader(),
                new Class<?>[]{RTPScheduler.class},
                (proxy, method, args) -> {
                    if ("runTask".equals(method.getName()) && args.length > 0 && args[0] instanceof Runnable r) {
                        r.run();
                        return null;
                    }
                    return null;
                });

        RTP.serverAccessor = mockAccessor;
        RTP.scheduler = mockScheduler;
        RTPAPI.serverAccessor = mockAccessor;
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = originalServerAccessor;
        RTP.scheduler = originalScheduler;
        RTPAPI.serverAccessor = originalApiServerAccessor;
        GuiRenderers.clear();
    }

    private MenuRenderer createDummyRenderer() {
        return (MenuRenderer) Proxy.newProxyInstance(
                MenuRenderer.class.getClassLoader(),
                new Class<?>[]{MenuRenderer.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("isAvailable".equals(name)) {
                        return true;
                    }
                    if ("key".equals(name)) {
                        return "chest";
                    }
                    return null;
                });
    }

    @ParameterizedTest
    @CsvSource({
            "action:operator:setup, rtp:rtp admin setup",
            "action:operator:import, rtp:rtp config import",
            "action:operator:config, rtp:rtp menu config",
            "action:operator:visualizations, rtp:rtp visualization",
            "action:operator:editor, rtp:rtp editor",
            "action:operator:status, rtp:rtp menu info",
            "action:operator:adminbook, rtp:rtp menu admin",
            "action:operator:reload, rtp:rtp reload"
    })
    void operatorActions_dispatchNamespacedRtpCommand(String action, String expectedCommand) {
        UUID playerId = UUID.randomUUID();
        GuiRenderers.register(createDummyRenderer());

        MenuActions.submit(playerId, io.github.dailystruggle.rtp.api.RtpTarget.action(action));

        assertEquals(1, dispatchedCommands.size(), "Expected exactly one command dispatched for " + action);
        String actualCommand = dispatchedCommands.get(0);
        assertEquals(expectedCommand, actualCommand, "Command for action " + action + " must be namespaced rtp:rtp");
        assertFalse(actualCommand.startsWith("rtp ") && !actualCommand.startsWith("rtp:rtp "),
                "Command must not start with unqualified 'rtp '");
    }

    @Test
    void operatorAction_withoutPermission_doesNotDispatchCommand() {
        permissions.clear(); // remove rtp.admin and all permissions

        UUID playerId = UUID.randomUUID();
        GuiMenuConfig config = new GuiMenuConfig();
        GuiRenderers.register(createDummyRenderer());

        MenuActions.submit(playerId, io.github.dailystruggle.rtp.api.RtpTarget.action("action:operator:setup"));

        assertTrue(dispatchedCommands.isEmpty(), "No command should be dispatched when player lacks permission");
        assertFalse(sentMessages.isEmpty(), "Unavailable message should be sent to the player");
        assertEquals(config.textUnavailable(), sentMessages.get(0));
    }
}
