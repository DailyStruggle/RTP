package io.github.dailystruggle.rtp.groupaddon;

import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Group MultiConfigParser Loading & Presets Test")
public class GroupMultiConfigTest {

  @TempDir
  File tempDir;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir);
  }

  @Test
  @DisplayName("MultiConfigParser extracts bundled YAML presets and instantiates dynamic profiles")
  void testBundledPresetsExtraction() {
    MultiConfigParser<GroupKeys> parser =
        new MultiConfigParser<>(
            GroupKeys.class,
            "groups",
            "1.0",
            tempDir,
            this.getClass().getClassLoader(),
            "definitions/groups");

    Set<String> parsers = parser.listParsers();
    assertTrue(parsers.contains("default") || parsers.contains("DEFAULT"), "Must contain default profile");
    assertTrue(parsers.contains("party") || parsers.contains("PARTY"), "Must contain party preset");
    assertTrue(parsers.contains("duel") || parsers.contains("DUEL"), "Must contain duel preset");

    ConfigParser<GroupKeys> partyParser = parser.getParser("party");
    assertNotNull(partyParser);
    GroupProfile partyProfile = GroupProfile.fromConfig("party", partyParser);
    assertEquals("party", partyProfile.name());
    assertEquals("SQUARE", partyProfile.shapeName());
    assertEquals(24, partyProfile.radiusBlocks());
    assertEquals(2, partyProfile.spacing());

    ConfigParser<GroupKeys> duelParser = parser.getParser("duel");
    assertNotNull(duelParser);
    GroupProfile duelProfile = GroupProfile.fromConfig("duel", duelParser);
    assertEquals("duel", duelProfile.name());
    assertEquals("CIRCLE", duelProfile.shapeName());
    assertEquals(100, duelProfile.radiusBlocks());
    assertEquals(50, duelProfile.spacing());
    assertEquals(2, duelProfile.maxGroupSize());
  }
}
