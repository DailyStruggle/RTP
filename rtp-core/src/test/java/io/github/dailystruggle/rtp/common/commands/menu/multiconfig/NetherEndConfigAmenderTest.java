package io.github.dailystruggle.rtp.common.commands.menu.multiconfig;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("NetherEndConfigAmender unit tests")
class NetherEndConfigAmenderTest {

    @Test
    @DisplayName("amend for nether world sets LINEAR, restricts maxY to 128, and disables requireskylight")
    void amend_netherWorld() {
        MockRTPWorld world = new MockRTPWorld("world_nether");
        ConfigParser<RegionKeys> parser = mock(ConfigParser.class);
        when(parser.getConfigValue(RegionKeys.vert, null)).thenReturn(null);

        Map<String, List<String>> params = new HashMap<>();
        NetherEndConfigAmender.amend(params, parser, world);

        assertEquals(List.of("LINEAR"), params.get("vert"));
        assertEquals(List.of("false"), params.get("requireskylight"));
        assertEquals(List.of("0"), params.get("miny"));
        assertEquals(List.of("128"), params.get("maxy"));
    }

    @Test
    @DisplayName("amend for the_end world sets LINEAR and disables requireskylight")
    void amend_endWorld() {
        MockRTPWorld world = new MockRTPWorld("world_the_end");
        ConfigParser<RegionKeys> parser = mock(ConfigParser.class);
        when(parser.getConfigValue(RegionKeys.vert, null)).thenReturn(null);

        Map<String, List<String>> params = new HashMap<>();
        NetherEndConfigAmender.amend(params, parser, world);

        assertEquals(List.of("LINEAR"), params.get("vert"));
        assertEquals(List.of("false"), params.get("requireskylight"));
        assertEquals(List.of("0"), params.get("miny"));
        assertEquals(List.of("255"), params.get("maxy"));
    }

    @Test
    @DisplayName("amend with RtpYamlSection preserves section parameters or overrides")
    void amend_withYamlSection() {
        MockRTPWorld world = new MockRTPWorld("world_nether");
        ConfigParser<RegionKeys> parser = mock(ConfigParser.class);
        RtpYamlSection section = mock(RtpYamlSection.class);
        when(section.getString("name")).thenReturn("RADIAL");
        when(section.getString("maxY")).thenReturn("120,5");
        when(section.getString("minY")).thenReturn("10,0");
        when(parser.getConfigValue(RegionKeys.vert, null)).thenReturn(section);

        Map<String, List<String>> params = new HashMap<>();
        NetherEndConfigAmender.amend(params, parser, world);

        assertEquals(List.of("RADIAL"), params.get("vert"));
        assertEquals(List.of("10"), params.get("miny"));
        assertEquals(List.of("120"), params.get("maxy"));
    }

    @Test
    @DisplayName("amend with VerticalAdjustor object reads bounds directly")
    void amend_withVerticalAdjustor() {
        MockRTPWorld world = new MockRTPWorld("world");
        ConfigParser<RegionKeys> parser = mock(ConfigParser.class);
        VerticalAdjustor<?> vert = mock(VerticalAdjustor.class);
        vert.name = "CUSTOM_VERT";
        when(vert.maxY()).thenReturn(200);
        when(vert.minY()).thenReturn(20);
        when(parser.getConfigValue(RegionKeys.vert, null)).thenReturn(vert);

        Map<String, List<String>> params = new HashMap<>();
        NetherEndConfigAmender.amend(params, parser, world);

        assertEquals(List.of("CUSTOM_VERT"), params.get("vert"));
        assertEquals(List.of("20"), params.get("miny"));
        assertEquals(List.of("200"), params.get("maxy"));
    }

    @Test
    @DisplayName("amend clamps minY if maxY < minY when miny was not present in parameterValues")
    void amend_maxYLessThanMinY() {
        RTPWorld world = mock(RTPWorld.class);
        when(world.name()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        ConfigParser<RegionKeys> parser = mock(ConfigParser.class);

        Map<String, List<String>> params = new HashMap<>();
        RtpYamlSection section = mock(RtpYamlSection.class);
        when(section.getString("name")).thenReturn("JUMP");
        when(section.getString("maxY")).thenReturn("-100");
        when(section.getString("minY")).thenReturn("50");
        when(parser.getConfigValue(RegionKeys.vert, null)).thenReturn(section);

        NetherEndConfigAmender.amend(params, parser, world);

        assertEquals(List.of("-64"), params.get("miny"));
        assertEquals(List.of("-100"), params.get("maxy"));
    }
}
