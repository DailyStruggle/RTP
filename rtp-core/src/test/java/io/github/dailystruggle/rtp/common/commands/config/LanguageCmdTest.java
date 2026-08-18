package io.github.dailystruggle.rtp.common.commands.config;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.LanguageBootstrap;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for LanguageCmd - /rtp config language &lt;locale&gt;
 *
 * <p>Covers REQ-RTP-F-013 (user-facing messages configurable) and S-004 (no silent failures).
 */
public class LanguageCmdTest {

    @TempDir
    Path tempDir;

    private LanguageCmd cmd;

    @BeforeEach
    void setUp() throws IOException {
        RTPTestSetup.install(tempDir.toFile());
        RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();

        Configs configs = new Configs(tempDir.toFile());
        RTP.configs = configs;

        ConfigParser lang = mock(ConfigParser.class);
        lang.language_mapping = new java.util.concurrent.ConcurrentHashMap<>();
        lang.reverse_language_mapping = new java.util.concurrent.ConcurrentHashMap<>();
        lang.name = "messages";
        when(lang.getConfigValue(any(), any())).thenReturn("");
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.PlaceholderMessages.class, lang);
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.class, lang);
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.NetworkMessages.class, lang);
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages.class, lang);
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.SystemMessages.class, lang);

        RTP.baseCommand = mock(io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand.class);
        Map<String, CommandsAPICommand> commandLookup = new HashMap<>();
        commandLookup.put("reload", mock(CommandsAPICommand.class));
        when(RTP.baseCommand.getCommandLookup()).thenReturn(commandLookup);

        cmd = new LanguageCmd(null);
    }

    @Test
    void languageCmd_name_isLanguage() {
        assertEquals("language", cmd.name());
    }

    @Test
    void languageCmd_permission_isRtpConfig() {
        assertEquals("rtp.config", cmd.permission());
    }

    @Test
    void languageCmd_description_isNotNull() {
        assertNotNull(cmd.description());
        assertFalse(cmd.description().isEmpty());
    }

    @Test
    void languageCmd_onCommand_delegatesToNextCommandWhenNonNull() {
        CommandsAPICommand next = mock(CommandsAPICommand.class);
        when(next.onCommand(any(), any(), any())).thenReturn(true);
        Map<String, List<String>> params = new HashMap<>();
        params.put("locale", Collections.singletonList("es"));
        boolean result = cmd.onCommand(UUID.randomUUID(), params, next);
        assertTrue(result);
        verify(next).onCommand(any(), any(), isNull());
    }

    @Test
    void languageCmd_onCommand_returnsFalseWhenNoLocaleProvided() {
        boolean result = cmd.onCommand(UUID.randomUUID(), new HashMap<>(), null);
        assertFalse(result);
    }

    @Test
    void languageCmd_onCommand_returnsFalseForInvalidLocale() {
        Map<String, List<String>> params = new HashMap<>();
        params.put("locale", Collections.singletonList("../../etc/passwd"));
        boolean result = cmd.onCommand(UUID.randomUUID(), params, null);
        assertFalse(result);
    }

    @Test
    void languageCmd_onCommand_writesLocaleToLanguageYml() throws Exception {
        Map<String, List<String>> params = new HashMap<>();
        params.put("locale", Collections.singletonList("es"));

        boolean result = cmd.onCommand(UUID.randomUUID(), params, null);
        assertTrue(result);

        // Allow async task to complete
        Thread.sleep(500);

        File langFile = new File(tempDir.toFile(), LanguageBootstrap.FILE_NAME);
        assertTrue(langFile.exists(), "language.yml should be created");

        RtpYamlConfig yaml = new RtpYamlConfig(langFile.getPath());
        yaml.loadWithComments();
        assertEquals("es", yaml.getString(LanguageBootstrap.KEY));
    }

    @Test
    void languageCmd_hasLocaleParameter() {
        assertFalse(cmd.getParameterLookup().isEmpty(), "LanguageCmd should expose a locale parameter");
        assertTrue(cmd.getParameterLookup().containsKey("locale"));
    }

    @Test
    void languageCmd_localeParameter_includesBundledLocales() {
        Set<String> values = cmd.getParameterLookup().get("locale").values();
        assertTrue(values.contains("en"));
        assertTrue(values.contains("es"));
        assertTrue(values.contains("de"));
        assertTrue(values.contains("fr"));
        assertTrue(values.contains("nl"));
    }
}
