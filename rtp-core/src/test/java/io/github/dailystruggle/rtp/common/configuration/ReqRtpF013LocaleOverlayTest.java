package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REQ-RTP-F-013 / ADR-020 — verifies lazy per-locale overlay behaviour for
 * {@link LocaleOverlay}:
 *
 * <ul>
 *   <li>English (default) leaves the baseline parser untouched.</li>
 *   <li>A provided locale file overwrites only the keys it declares;
 *       missing keys silently fall back to the baseline.</li>
 *   <li>Unknown locales are a safe no-op (no throw, baseline intact).</li>
 *   <li>Path-traversal attempts via the {@code language:} key are rejected.</li>
 * </ul>
 */
@DisplayName("REQ-RTP-F-013 / ADR-020 — Locale overlay applies lazily with fallback")
public class ReqRtpF013LocaleOverlayTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        RTPServerAccessor serverAccessor = mock(RTPServerAccessor.class);
        RTPScheduler scheduler = mock(RTPScheduler.class);
        when(serverAccessor.getPluginDirectory()).thenReturn(tempDir.toFile());
        when(serverAccessor.createTaskPipe())
                .thenReturn(mock(io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe.class));
        RTP.serverAccessor = serverAccessor;
        RTP.scheduler = scheduler;
        RTPAPI.serverAccessor = null;

        RTP rtp = new RTP() {};
        try {
            java.lang.reflect.Field instanceField = RTP.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, rtp);
        } catch (Exception ignored) {}
    }

    /** Build a minimal English baseline messages parser on disk. */
    private ConfigParser<MessagesKeys> buildBaseline() throws IOException {
        File baseline = tempDir.resolve("messages.yml").toFile();
        Files.writeString(baseline.toPath(),
                "teleportCancel: \"&e[P0] Teleport cancelled!\"\n"
              + "noPerms: \"&eNo permission.\"\n"
              + "busy: \"&c[P0] busy\"\n"
              + "version: \"1.0\"\n");
        YamlFileDatabase db = new YamlFileDatabase(tempDir.toFile());
        return new ConfigParser<>(MessagesKeys.class, "messages.yml", "1.0", tempDir.toFile(), db);
    }

    private void writeLocaleFile(String locale, String yaml) throws IOException {
        File dir = tempDir.resolve("lang").resolve(locale).toFile();
        assertTrue(dir.mkdirs() || dir.exists());
        Files.writeString(new File(dir, "messages.yml").toPath(), yaml);
    }

    @Test
    @DisplayName("locale=en is a no-op — baseline preserved")
    void englishLocaleIsNoOp() throws IOException {
        ConfigParser<MessagesKeys> baseline = buildBaseline();
        Object before = baseline.getConfigValue(MessagesKeys.teleportCancel, null);

        LocaleOverlay.apply(baseline, tempDir.toFile(), "en");

        assertEquals(before, baseline.getConfigValue(MessagesKeys.teleportCancel, null),
                "English locale must not modify the baseline parser");
    }

    @Test
    @DisplayName("null / blank locale is a no-op")
    void nullOrBlankLocaleIsNoOp() throws IOException {
        ConfigParser<MessagesKeys> baseline = buildBaseline();
        Object before = baseline.getConfigValue(MessagesKeys.teleportCancel, null);

        LocaleOverlay.apply(baseline, tempDir.toFile(), null);
        LocaleOverlay.apply(baseline, tempDir.toFile(), "");
        LocaleOverlay.apply(baseline, tempDir.toFile(), "   ");

        assertEquals(before, baseline.getConfigValue(MessagesKeys.teleportCancel, null));
    }

    @Test
    @DisplayName("partial locale file overrides declared keys; missing keys fall back")
    void partialLocaleAppliesOverlayAndFallsBackForMissingKeys() throws IOException {
        ConfigParser<MessagesKeys> baseline = buildBaseline();
        writeLocaleFile("es",
                "teleportCancel: \"&e[P0] ¡Teletransporte cancelado!\"\n");

        LocaleOverlay.apply(baseline, tempDir.toFile(), "es");

        assertEquals("&e[P0] ¡Teletransporte cancelado!",
                baseline.getConfigValue(MessagesKeys.teleportCancel, null),
                "Declared locale key must override baseline");
        assertEquals("&eNo permission.",
                baseline.getConfigValue(MessagesKeys.noPerms, null),
                "Missing locale key must fall back to baseline English");
    }

    @Test
    @DisplayName("unknown locale (no file on disk, no jar resource) is a safe no-op")
    void unknownLocaleIsNoOp() throws IOException {
        ConfigParser<MessagesKeys> baseline = buildBaseline();
        Object before = baseline.getConfigValue(MessagesKeys.teleportCancel, null);

        assertDoesNotThrow(() ->
                LocaleOverlay.apply(baseline, tempDir.toFile(), "zz_nonexistent"));

        assertEquals(before, baseline.getConfigValue(MessagesKeys.teleportCancel, null),
                "Unknown locale must leave baseline untouched");
    }

    @Test
    @DisplayName("path-traversal attempts in language key are rejected")
    void pathTraversalRejected() throws IOException {
        ConfigParser<MessagesKeys> baseline = buildBaseline();
        Object before = baseline.getConfigValue(MessagesKeys.teleportCancel, null);

        LocaleOverlay.apply(baseline, tempDir.toFile(), "../evil");
        LocaleOverlay.apply(baseline, tempDir.toFile(), "es/../../etc");
        LocaleOverlay.apply(baseline, tempDir.toFile(), "a b");

        assertEquals(before, baseline.getConfigValue(MessagesKeys.teleportCancel, null));
    }

    @Test
    @DisplayName("non-string YAML values are coerced to String (Norway-problem guard)")
    void nonStringLocaleValuesAreCoerced() throws IOException {
        ConfigParser<MessagesKeys> baseline = buildBaseline();
        // Without quotes, YAML 1.1 parses `no` as Boolean.FALSE.
        writeLocaleFile("es",
                "teleportCancel: no\n");

        LocaleOverlay.apply(baseline, tempDir.toFile(), "es");

        Object val = baseline.getConfigValue(MessagesKeys.teleportCancel, null);
        assertNotNull(val);
        assertTrue(val instanceof String,
                "Locale values must be coerced to String; got " + val.getClass());
    }
}
