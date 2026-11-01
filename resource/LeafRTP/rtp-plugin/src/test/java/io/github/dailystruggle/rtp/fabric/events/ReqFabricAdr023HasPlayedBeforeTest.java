package io.github.dailystruggle.rtp.fabric.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-FABRIC-F-013 - ADR-023 Login Reserve Cache port to Fabric:
 * {@code FabricOnEventTeleports.hasPlayedBefore(...)} must return
 * {@code true} iff the player's {@code playerdata/<uuid>.dat} file exists
 * under the world root. This drives the first-join branch of the join-time
 * RTP path (and therefore which permission node - {@code rtp.onevent.firstjoin}
 * vs. {@code rtp.onevent.join} - gates the consumption of the login reserve
 * cache for that join).
 *
 * <p>Validates against a temp directory so the test does not require a live
 * {@code MinecraftServer}. The path-overload exists for exactly this reason
 * (see Javadoc on the overload).
 */
@DisplayName("REQ-FABRIC-F-013 / ADR-023 — hasPlayedBefore probes <worldRoot>/playerdata/<uuid>.dat")
final class ReqFabricAdr023HasPlayedBeforeTest {

    @Test
    @DisplayName("missing playerdata/<uuid>.dat → first join (returns false)")
    void missingFile_returnsFalse(@TempDir Path worldRoot) {
        UUID uuid = UUID.randomUUID();
        // No playerdata directory at all: must return false.
        assertFalse(FabricOnEventTeleports.hasPlayedBefore(worldRoot, uuid),
                "fresh world root with no playerdata/ folder must be treated as first join");
    }

    @Test
    @DisplayName("playerdata/ exists but file is absent → first join (returns false)")
    void emptyPlayerdataDir_returnsFalse(@TempDir Path worldRoot) throws IOException {
        UUID uuid = UUID.randomUUID();
        Files.createDirectories(worldRoot.resolve("playerdata"));
        assertFalse(FabricOnEventTeleports.hasPlayedBefore(worldRoot, uuid),
                "empty playerdata/ folder must still be treated as first join");
    }

    @Test
    @DisplayName("playerdata/<uuid>.dat exists → subsequent join (returns true)")
    void fileExists_returnsTrue(@TempDir Path worldRoot) throws IOException {
        UUID uuid = UUID.randomUUID();
        Path playerdata = worldRoot.resolve("playerdata");
        Files.createDirectories(playerdata);
        Files.createFile(playerdata.resolve(uuid + ".dat"));
        assertTrue(FabricOnEventTeleports.hasPlayedBefore(worldRoot, uuid),
                "presence of playerdata/<uuid>.dat must indicate a returning player");
    }

    @Test
    @DisplayName("a different player's .dat file does not flag this player as returning")
    void differentUuidPresent_returnsFalse(@TempDir Path worldRoot) throws IOException {
        UUID present = UUID.randomUUID();
        UUID queried = UUID.randomUUID();
        Path playerdata = worldRoot.resolve("playerdata");
        Files.createDirectories(playerdata);
        Files.createFile(playerdata.resolve(present + ".dat"));
        assertFalse(FabricOnEventTeleports.hasPlayedBefore(worldRoot, queried),
                "presence of an unrelated UUID's .dat must not flag the queried UUID as returning");
    }

    @Test
    @DisplayName("playerdata/<uuid>.dat is a directory, not a file → first join (returns false)")
    void datIsDirectory_returnsFalse(@TempDir Path worldRoot) throws IOException {
        UUID uuid = UUID.randomUUID();
        Path playerdata = worldRoot.resolve("playerdata");
        Files.createDirectories(playerdata);
        Files.createDirectories(playerdata.resolve(uuid + ".dat"));
        assertFalse(FabricOnEventTeleports.hasPlayedBefore(worldRoot, uuid),
                "must require a regular file at playerdata/<uuid>.dat, not a directory");
    }

    @Test
    @DisplayName("null inputs are safely handled — returns false")
    void nullInputs_returnFalse(@TempDir Path worldRoot) {
        assertFalse(FabricOnEventTeleports.hasPlayedBefore((Path) null, UUID.randomUUID()),
                "null worldRoot must return false (fail-soft, do not throw)");
        assertFalse(FabricOnEventTeleports.hasPlayedBefore(worldRoot, null),
                "null uuid must return false (fail-soft, do not throw)");
    }
}
