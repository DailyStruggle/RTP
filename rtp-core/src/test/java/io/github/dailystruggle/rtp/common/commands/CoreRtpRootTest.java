package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CoreRtpRoot branch coverage tests")
class CoreRtpRootTest {

    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir);
        RTP.selectionAPI = new SelectionAPI();
    }

    @Test
    void testConstructorsAndSenderChecks() {
        AtomicReference<String> renderedMessage = new AtomicReference<>();
        CoreRtpRoot rootWithRenderer = new CoreRtpRoot(uuid -> msg -> renderedMessage.set(msg));
        assertNotNull(rootWithRenderer);

        MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "senderCheckPlayer", null);
        accessor.addPlayer(player);

        AtomicBoolean senderCheckCalled = new AtomicBoolean(false);
        rootWithRenderer.addSenderCheck(sender -> {
            senderCheckCalled.set(true);
            return true;
        });

        // Test dispatchString with subcommand
        boolean subResult = rootWithRenderer.dispatchString(player.uuid(), "rtp", new String[]{"help"});
        assertTrue(subResult);

        // Test dispatchString without subcommand (invoking senderCheck)
        rootWithRenderer.dispatchString(player.uuid(), "rtp", new String[0]);
        assertTrue(senderCheckCalled.get());

        // Test failing senderCheck
        rootWithRenderer.addSenderCheck(sender -> false);
        boolean blocked = rootWithRenderer.dispatchString(player.uuid(), "rtp", new String[0]);
        assertFalse(blocked);

        // Test onCommand with failing sender check
        boolean onCommandBlocked = rootWithRenderer.onCommand(player.uuid(), Collections.emptyMap(), null);
        assertFalse(onCommandBlocked);
    }

    @Test
    void testSuccessAndFailEvents() {
        CoreRtpRoot root = new CoreRtpRoot();
        MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "eventPlayer", null);
        accessor.addPlayer(player);

        AtomicBoolean successFired = new AtomicBoolean(false);
        AtomicBoolean failFired = new AtomicBoolean(false);

        RTPCommandEvents.onSuccess((sender, p) -> successFired.set(true));
        RTPCommandEvents.onFail((sender, msg) -> failFired.set(true));

        root.successEvent(player, player);
        assertTrue(successFired.get());

        root.failEvent(player, "test fail");
        assertTrue(failFired.get());
    }
}
