package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused unit coverage for the per-viewer staging-cart helpers of
 * {@link MenuRedeemSubcommand} (ADR-050 config-staging cart). Exercises the
 * file-name normalization, cross-file scope replacement, last-key clearing,
 * defensive snapshot copies, and the {@link MenuRedeemSubcommand.CartSink}
 * seam. Part of the ENTERPRISE_READINESS.md coverage push against the single
 * worst-covered class in rtp-core.
 */
public class MenuRedeemSubcommandCartTest {

    @TempDir Path tempDir;

    private MenuRedeemSubcommand redeem;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        redeem = new MenuRedeemSubcommand(new TestableRoot(), uuid -> perm -> true);
    }

    @Test
    void stageThenSnapshotReturnsEntry() {
        UUID viewer = UUID.randomUUID();
        redeem.stageInCart(viewer, "config", "radius", "100");
        LinkedHashMap<String, String> snap = redeem.snapshotCart(viewer, "config");
        assertEquals(1, snap.size());
        assertEquals("100", snap.get("radius"));
    }

    @Test
    void fileNameNormalizationMatchesSuffixedAndBareAndCaseVariants() {
        UUID viewer = UUID.randomUUID();
        redeem.stageInCart(viewer, "config", "radius", "100");
        // Suffixed and upper-case variants must resolve to the same bucket.
        assertEquals("100", redeem.snapshotCart(viewer, "config.yml").get("radius"));
        assertEquals("100", redeem.snapshotCart(viewer, "CONFIG.YML").get("radius"));
        assertEquals("100", redeem.snapshotCart(viewer, "Config").get("radius"));
    }

    @Test
    void stagingDifferentFileReplacesEntireCart() {
        UUID viewer = UUID.randomUUID();
        redeem.stageInCart(viewer, "config", "radius", "100");
        redeem.stageInCart(viewer, "worlds", "shape", "SQUARE");
        // Old file scope dropped entirely.
        assertTrue(redeem.snapshotCart(viewer, "config").isEmpty());
        LinkedHashMap<String, String> worlds = redeem.snapshotCart(viewer, "worlds");
        assertEquals(1, worlds.size());
        assertEquals("SQUARE", worlds.get("shape"));
    }

    @Test
    void stagingSameFileAccumulatesAndReplacesKeys() {
        UUID viewer = UUID.randomUUID();
        redeem.stageInCart(viewer, "config", "radius", "100");
        redeem.stageInCart(viewer, "config", "centerRadius", "0");
        redeem.stageInCart(viewer, "config", "radius", "250"); // replace existing key
        LinkedHashMap<String, String> snap = redeem.snapshotCart(viewer, "config");
        assertEquals(2, snap.size());
        assertEquals("250", snap.get("radius"));
        assertEquals("0", snap.get("centerRadius"));
    }

    @Test
    void unstageRemovesKeyAndClearsCartOnLastKey() {
        UUID viewer = UUID.randomUUID();
        redeem.stageInCart(viewer, "config", "radius", "100");
        redeem.stageInCart(viewer, "config", "shape", "SQUARE");
        redeem.unstageInCart(viewer, "config", "radius");
        assertEquals(1, redeem.snapshotCart(viewer, "config").size());
        // Removing the last key empties the cart entirely.
        redeem.unstageInCart(viewer, "config", "shape");
        assertTrue(redeem.snapshotCart(viewer, "config").isEmpty());
    }

    @Test
    void unstageIsNoOpForCrossFileScopeAndMissingKey() {
        UUID viewer = UUID.randomUUID();
        redeem.stageInCart(viewer, "config", "radius", "100");
        redeem.unstageInCart(viewer, "worlds", "radius"); // wrong file scope
        redeem.unstageInCart(viewer, "config", "absent");  // missing key
        assertEquals("100", redeem.snapshotCart(viewer, "config").get("radius"));
    }

    @Test
    void unstageIsNoOpWhenNoCartExists() {
        UUID viewer = UUID.randomUUID();
        redeem.unstageInCart(viewer, "config", "radius");
        assertTrue(redeem.snapshotCart(viewer, "config").isEmpty());
    }

    @Test
    void clearCartDropsEntriesAndToleratesNull() {
        UUID viewer = UUID.randomUUID();
        redeem.stageInCart(viewer, "config", "radius", "100");
        redeem.clearCart(viewer);
        assertTrue(redeem.snapshotCart(viewer, "config").isEmpty());
        redeem.clearCart(null); // must not throw
    }

    @Test
    void snapshotIsEmptyForMissingCartAndDifferentFileScope() {
        UUID viewer = UUID.randomUUID();
        assertTrue(redeem.snapshotCart(viewer, "config").isEmpty());
        redeem.stageInCart(viewer, "config", "radius", "100");
        assertTrue(redeem.snapshotCart(viewer, "worlds").isEmpty());
    }

    @Test
    void snapshotIsDefensiveCopy() {
        UUID viewer = UUID.randomUUID();
        redeem.stageInCart(viewer, "config", "radius", "100");
        LinkedHashMap<String, String> snap = redeem.snapshotCart(viewer, "config");
        snap.put("radius", "mutated");
        snap.put("extra", "x");
        // Mutating the snapshot must not affect the live cart.
        LinkedHashMap<String, String> fresh = redeem.snapshotCart(viewer, "config");
        assertEquals(1, fresh.size());
        assertEquals("100", fresh.get("radius"));
    }

    @Test
    void cartSinkStagesIntoTheSameCart() {
        UUID viewer = UUID.randomUUID();
        MenuRedeemSubcommand.CartSink sink = redeem.cartSink();
        sink.stage(viewer, "config.yml", "radius", "500");
        assertEquals("500", redeem.snapshotCart(viewer, "config").get("radius"));
    }

    @Test
    void stageRejectsNullArguments() {
        UUID viewer = UUID.randomUUID();
        assertThrows(NullPointerException.class,
                () -> redeem.stageInCart(null, "config", "radius", "100"));
        assertThrows(NullPointerException.class,
                () -> redeem.stageInCart(viewer, null, "radius", "100"));
        assertThrows(NullPointerException.class,
                () -> redeem.stageInCart(viewer, "config", null, "100"));
        assertThrows(NullPointerException.class,
                () -> redeem.stageInCart(viewer, "config", "radius", null));
    }

    @Test
    void unstageRejectsNullArguments() {
        UUID viewer = UUID.randomUUID();
        assertThrows(NullPointerException.class,
                () -> redeem.unstageInCart(null, "config", "radius"));
        assertThrows(NullPointerException.class,
                () -> redeem.unstageInCart(viewer, null, "radius"));
        assertThrows(NullPointerException.class,
                () -> redeem.unstageInCart(viewer, "config", null));
    }

    @Test
    void identityAccessorsReportMenuNameAndPermission() {
        assertEquals("menu", redeem.name());
        assertEquals(MenuRedeemSubcommand.PERMISSION, redeem.permission());
    }

    @Test
    void cartsAreIsolatedPerViewer() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        redeem.stageInCart(a, "config", "radius", "100");
        redeem.stageInCart(b, "config", "radius", "200");
        assertEquals("100", redeem.snapshotCart(a, "config").get("radius"));
        assertEquals("200", redeem.snapshotCart(b, "config").get("radius"));
    }

    /** Minimal TreeCommand root fixture (mirrors MenuStageTwoTest). */
    private static final class TestableRoot extends BaseRTPCmdImpl {
        TestableRoot() { super(null); }

        @Override public String name() { return "rtp"; }
        @Override public String permission() { return ""; }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 CommandsAPICommand nextCommand) {
            return true;
        }

        @Override
        public CompletableFuture<Boolean> onCommand(UUID callerId,
                                                    Predicate<String> permissionCheckMethod,
                                                    Consumer<String> messageMethod,
                                                    String[] args,
                                                    int i,
                                                    Map<String, CommandParameter> tempParameters) {
            return CompletableFuture.completedFuture(true);
        }
    }
}
