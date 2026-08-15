package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import org.junit.jupiter.api.BeforeEach;
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
 * Regression test proving that a language switch propagates through
 * {@link MultiConfigParser} to the per-file child {@link ConfigParser}s it
 * builds, and that the child value files on disk are actually re-checked and
 * rewritten with the new locale's keys and comments after the change.
 *
 * <p>Before the fix, {@code MultiConfigParser} carried no locale and always
 * built children with {@link LanguageBootstrap#DEFAULT_LOCALE}, so the
 * region/world/effect definition files never honored an in-game language
 * switch even though the single-file parsers did.
 *
 * <p>The content-level assertions are backed by two rtp-core test resources:
 * {@code mcplocaletest.yml} (English baseline) and
 * {@code lang/es/mcplocaletest.yml} (Spanish: translated comment and renamed
 * keys). A child parser's name is a bare leaf, so its JAR prefix is empty and
 * these root resources resolve for it.
 */
public class MultiConfigParserLocaleSwitchTest {

    @TempDir
    Path tempDir;

    /** Matches the keys shipped in the {@code mcplocaletest.yml} test resources. */
    public enum TestKeys {
        alpha,
        beta,
        version
    }

    @BeforeEach
    void setUp() {
        RTPServerAccessor serverAccessor = mock(RTPServerAccessor.class);
        RTPScheduler scheduler = mock(RTPScheduler.class);
        when(serverAccessor.getPluginDirectory()).thenReturn(tempDir.toFile());
        when(serverAccessor.createTaskPipe()).thenReturn(
                mock(io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe.class));
        // No world registered, so getParser resolves from the factory directly.
        when(serverAccessor.getRTPWorld(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        RTP.serverAccessor = serverAccessor;
        RTP.scheduler = scheduler;

        RTPAPI.serverAccessor = null;

        RTP rtp = new RTP() {};
        try {
            java.lang.reflect.Field instanceField = RTP.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, rtp);
        } catch (Exception ignored) {
        }
    }

    /**
     * The child parsers a {@link MultiConfigParser} builds during its directory
     * scan must (1) inherit the active locale and (2) have their on-disk value
     * files re-checked and rewritten with the new locale's comment and keys.
     */
    @Test
    void multiConfigParserRewritesChildFilesWithNewLocaleContent() throws IOException {
        File defsDir = tempDir.resolve("defs").toFile();
        assertTrue(defsDir.mkdirs() || defsDir.isDirectory());

        // The shared folder rename map (definitions-style .<leaf>.lang.yml sibling),
        // mapping enum names to the Spanish key names shipped in lang/es/.
        Files.writeString(tempDir.resolve(".defs.lang.yml"),
                "alpha: alfa\nbeta: beta_es\nversion: version\n");

        // An authored region/world-style file left on disk from a previous English
        // run: a pristine copy of the English baseline JAR resource.
        File child = new File(defsDir, "mcplocaletest.yml");
        Files.writeString(child.toPath(),
                "# english baseline comment\nalpha: 1\nbeta: 2\nversion: \"1.0\"\n");

        // Switch language to Spanish.
        MultiConfigParser<TestKeys> multi = new MultiConfigParser<>(
                TestKeys.class, "defs", "1.0", tempDir.toFile(), "defs", "es");

        // (1) locale threaded into the parser and its children.
        assertEquals("es", multi.locale, "MultiConfigParser must retain the active locale");
        ConfigParser<TestKeys> parser = multi.getParser("mcplocaletest");
        assertEquals("es", parser.locale,
                "Child parser built by MultiConfigParser must inherit the active locale");

        // (2) the previous English file was backed up (the file WAS checked and changed).
        assertTrue(new File(defsDir, "mcplocaletest.yml.old1").exists(),
                "Foreign-locale child file should be backed up to .old1 after the switch");

        // (3) the on-disk file now carries the NEW locale's comment and keys,
        // not merely a flipped locale field.
        String onDisk = Files.readString(child.toPath());
        assertTrue(onDisk.contains("comentario base en espanol"),
                "Child file must be rewritten with the new locale's comments; found:\n" + onDisk);
        assertTrue(onDisk.contains("alfa:"),
                "Child file must be rewritten with the new locale's keys; found:\n" + onDisk);
        assertFalse(onDisk.contains("english baseline comment"),
                "Old English comment must not survive the switch; found:\n" + onDisk);

        // (4) values still resolve through the localized keys.
        assertEquals(1, parser.getData(TestKeys.alpha),
                "Alpha must resolve via the localized key after the switch");
        assertEquals(2, parser.getData(TestKeys.beta),
                "Beta must resolve via the localized key after the switch");
    }

    @Test
    void multiConfigParserDefaultsToEnglishWhenNoLocaleGiven() throws IOException {
        File defsDir = tempDir.resolve("defs").toFile();
        assertTrue(defsDir.mkdirs() || defsDir.isDirectory());
        Files.writeString(new File(defsDir, "default.yml").toPath(),
                "alpha: 1\nbeta: 2\nversion: 1.0\n");

        MultiConfigParser<TestKeys> multi = new MultiConfigParser<>(
                TestKeys.class, "defs", "1.0", tempDir.toFile(), "defs");

        assertEquals(LanguageBootstrap.DEFAULT_LOCALE, multi.locale);
        assertEquals(LanguageBootstrap.DEFAULT_LOCALE, multi.getParser("default").locale);
    }
}
