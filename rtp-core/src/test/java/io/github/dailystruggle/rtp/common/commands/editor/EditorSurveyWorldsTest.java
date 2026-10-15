package io.github.dailystruggle.rtp.common.commands.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Land map world coverage (ADR-104 §4.6): every live world is surveyed and embedded, not only the
 * worlds a region already uses, so the editor can show the nether and the end before any region
 * targets them. Region worlds keep the front of the order (surveyed first).
 */
@DisplayName("ADR-104: editor land map covers every live world, region worlds first")
class EditorSurveyWorldsTest {

    private RTPServerAccessor savedAccessor;

    @BeforeEach
    void setUp() {
        savedAccessor = RTP.serverAccessor;
        WorldBiomeStore.clearForTests();
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = savedAccessor;
        WorldBiomeStore.clearForTests();
    }

    private static RTPWorld<?> world(String name) {
        RTPWorld<?> w = mock(RTPWorld.class);
        when(w.name()).thenReturn(name);
        return w;
    }

    private static void liveWorlds(RTPWorld<?>... worlds) {
        RTPServerAccessor accessor = mock(RTPServerAccessor.class);
        when(accessor.getRTPWorlds()).thenReturn(new ArrayList<>(Arrays.asList(worlds)));
        RTP.serverAccessor = accessor;
    }

    @Test
    @DisplayName("withWorlds keeps region worlds first, appends the rest once and skips unnamed worlds")
    void withWorldsOrder() {
        RTPWorld<?> nether = world("world_nether");
        Map<String, RTPWorld<?>> regions = new LinkedHashMap<>();
        regions.put("world_nether", nether);
        List<RTPWorld<?>> live = new ArrayList<>(Arrays.asList(world("world"), world("world_nether"), null,
                world(""), world("world_the_end")));

        Map<String, RTPWorld<?>> out = EditorLiveFeed.withWorlds(regions, live);

        assertEquals(List.of("world_nether", "world", "world_the_end"), new ArrayList<>(out.keySet()));
        assertSame(nether, out.get("world_nether"), "the region's own world instance is kept");
        assertEquals(List.of("world_nether"), new ArrayList<>(EditorLiveFeed.withWorlds(regions, null).keySet()));
    }

    @Test
    @DisplayName("surveyWorlds lists the nether and the end with no region configured")
    void surveyWorldsListsEveryLiveWorld() {
        liveWorlds(world("world"), world("world_nether"), world("world_the_end"));

        List<String> all = List.of("world", "world_nether", "world_the_end");
        assertTrue(EditorLiveFeed.surveyWorlds().keySet().containsAll(all), EditorLiveFeed.surveyWorlds().keySet().toString());
        List<String> surveyed = EditorLiveFeed.planSurveys().stream().map(WorldLandSurvey::world).toList();
        assertTrue(surveyed.containsAll(all), surveyed.toString());
    }

    @Test
    @DisplayName("surveyWorlds survives a failing world list (region worlds only)")
    void surveyWorldsFailingAccessor() {
        RTPServerAccessor accessor = mock(RTPServerAccessor.class);
        when(accessor.getRTPWorlds()).thenThrow(new IllegalStateException("not ready"));
        RTP.serverAccessor = accessor;

        assertDoesNotThrow(EditorLiveFeed::surveyWorlds);
    }

    @Test
    @DisplayName("the session snapshot embeds land for every live world")
    void worldBiomesJsonCoversEveryWorld() {
        liveWorlds(world("world"), world("world_nether"), world("world_the_end"));

        JsonObject worlds = JsonParser.parseString(new EditorSessionManager().buildWorldBiomesJson(true)).getAsJsonObject();

        assertTrue(worlds.has("world_nether"), worlds.toString());
        assertTrue(worlds.has("world_the_end"), worlds.toString());
        assertTrue(worlds.getAsJsonObject("world_nether").has("palette"), worlds.toString());
    }
}
