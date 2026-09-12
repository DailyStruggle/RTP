package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.configuration.enums.SystemMessages;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MessageTagger} (ENTERPRISE_READINESS item 19, {@code tools} package).
 *
 * <p>{@link MessageTagger#tagMessage} appends a diagnostic {@code (sig:tag)} suffix
 * only when {@link SystemMessages#showDevTag} is enabled in {@code messages.yml};
 * otherwise the message is returned untouched. The build signature comes from
 * {@link SupportInfo#getSig()} which is {@code "Dev"} in a JUnit build.
 */
public class MessageTaggerTest {

    @TempDir
    File pluginDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(pluginDir);
    }

    @AfterEach
    void tearDown() {
        // Leave the shared config in the disabled default so no sibling test inherits the tag.
        // Guard against tests that deliberately null out RTP.configs.
        if (RTP.configs != null) {
            setDevTag(false);
        }
    }

    @SuppressWarnings("unchecked")
    private void setDevTag(boolean enabled) {
        ConfigParser<SystemMessages> parser =
                (ConfigParser<SystemMessages>) RTP.configs.getParser(SystemMessages.class);
        parser.setConfigValue("showDevTag", enabled);
    }

    // ---- null / empty short-circuits ----

    @Test
    void nullMessageIsReturnedAsNull() {
        assertNull(MessageTagger.tagMessage(null, "TAG"));
    }

    @Test
    void emptyMessageIsReturnedVerbatim() {
        assertEquals("", MessageTagger.tagMessage("", "TAG"));
    }

    // ---- config not loaded => suppressed ----

    @Test
    void nullConfigsSuppressesTheTag() {
        RTP.configs = null;
        assertEquals("hello", MessageTagger.tagMessage("hello", "TAG"),
                "with no config loaded the tag must be suppressed by default");
    }

    // ---- disabled (default) => passthrough ----

    @Test
    void disabledDevTagLeavesMessageUnchanged() {
        setDevTag(false);
        assertEquals("hello", MessageTagger.tagMessage("hello", "TAG"));
    }

    // ---- enabled => explicit tag appended ----

    @Test
    void enabledDevTagAppendsProvidedTag() {
        setDevTag(true);
        String out = MessageTagger.tagMessage("hello", "MYTAG");
        assertEquals("hello \u00a78(Dev:MYTAG)", out);
    }

    @Test
    void enabledDevTagInfersTagFromCallStackWhenOmitted() {
        setDevTag(true);
        String out = MessageTagger.tagMessage("hello", null);
        // The first non-Thread, non-MessageTagger, non-accessor frame is this test class.
        assertTrue(out.startsWith("hello \u00a78(Dev:"), out);
        assertTrue(out.contains("MessageTaggerTest"),
                "inferred tag should be this caller's simple class name: " + out);
        assertTrue(out.endsWith(")"), out);
    }

    @Test
    void enabledDevTagWithEmptyTagAlsoInfers() {
        setDevTag(true);
        String out = MessageTagger.tagMessage("hello", "");
        assertTrue(out.startsWith("hello \u00a78(Dev:"), out);
        assertTrue(out.endsWith(")"), out);
    }
}
