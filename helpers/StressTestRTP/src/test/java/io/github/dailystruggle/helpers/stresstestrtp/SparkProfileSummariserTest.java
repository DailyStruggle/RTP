package io.github.dailystruggle.helpers.stresstestrtp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SparkProfileSummariserTest {

    @Test
    @DisplayName("JsonBuilder emits valid JSON with separating commas between array objects")
    void arrayObjectsSeparatedByCommas() {
        SparkProfileSummariser.JsonBuilder j = new SparkProfileSummariser.JsonBuilder();
        j.beginObj();
        j.kv("label", "test-phase");
        j.key("thread_cpu_top"); j.beginArr();
        j.beginObj();
        j.kv("thread", "Server thread");
        j.kv("weight", 12.5);
        j.endObj();
        j.beginObj();
        j.kv("thread", "RTP-Anvil-IO");
        j.kv("weight", 3.2);
        j.endObj();
        j.endArr();
        j.key("windows"); j.beginArr();
        j.beginObj();
        j.kv("window", 0);
        j.kv("tps", 20.0);
        j.endObj();
        j.beginObj();
        j.kv("window", 1);
        j.kv("tps", 19.8);
        j.endObj();
        j.endArr();
        j.endObj();

        String json = j.toString();
        assertFalse(json.contains("}{"), "JSON array objects must be separated by commas, not concatenated");

        JsonElement parsed = assertDoesNotThrow(() -> JsonParser.parseString(json));
        assertTrue(parsed.isJsonObject());
        JsonObject root = parsed.getAsJsonObject();
        assertEquals("test-phase", root.get("label").getAsString());

        JsonArray threads = root.getAsJsonArray("thread_cpu_top");
        assertEquals(2, threads.size());
        assertEquals("Server thread", threads.get(0).getAsJsonObject().get("thread").getAsString());
        assertEquals("RTP-Anvil-IO", threads.get(1).getAsJsonObject().get("thread").getAsString());

        JsonArray windows = root.getAsJsonArray("windows");
        assertEquals(2, windows.size());
        assertEquals(0, windows.get(0).getAsJsonObject().get("window").getAsInt());
        assertEquals(1, windows.get(1).getAsJsonObject().get("window").getAsInt());
    }

    private static SparkProfileSummariser.WindowStatistics window(long start, long end, double msptMedian) {
        SparkProfileSummariser.WindowStatistics w = new SparkProfileSummariser.WindowStatistics();
        w.startTime = start;
        w.endTime = end;
        w.duration = (int) (end - start);
        w.msptMedian = msptMedian;
        return w;
    }

    @Test
    @DisplayName("Window durations are seconds; MSPT aggregate covers only windows inside the phase")
    void windowDurationSecondsAndPhaseFilter() {
        SparkProfileSummariser.SamplerData d = new SparkProfileSummariser.SamplerData();
        d.metadata = new SparkProfileSummariser.SamplerMetadata();
        d.metadata.startTime = 100_000L;
        d.metadata.endTime = 220_000L;
        d.windows.put(1, window(40_000L, 100_000L, 5.0));   // idle, before the capture
        d.windows.put(2, window(100_000L, 160_000L, 30.0));
        d.windows.put(3, window(160_000L, 220_000L, 40.0));

        JsonObject root = JsonParser.parseString(SparkProfileSummariser.toJson(d, "p", 4)).getAsJsonObject();
        JsonArray windows = root.getAsJsonArray("windows");
        assertEquals(60.0, windows.get(0).getAsJsonObject().get("duration_s").getAsDouble(), 1e-9);
        assertFalse(windows.get(0).getAsJsonObject().get("in_phase").getAsBoolean());
        JsonObject agg = root.getAsJsonObject("mspt_window_aggregate");
        assertEquals(2, agg.get("windows").getAsInt(), "idle pre-phase window excluded");
        assertEquals(40.0, agg.get("max_of_medians").getAsDouble(), 1e-9);
        // Nearest-rank median: {30, 40} -> 40; with the idle window {5, 30, 40} it would be 30.
        assertEquals(40.0, agg.get("median_of_medians").getAsDouble(), 1e-9);

        // Explicit phase bounds narrow further.
        JsonObject narrowed = JsonParser.parseString(
                SparkProfileSummariser.toJson(d, "p", 4, 160_000L, 220_000L)).getAsJsonObject();
        assertEquals(1, narrowed.getAsJsonObject("mspt_window_aggregate").get("windows").getAsInt());
    }

    @Test
    @DisplayName("Windows without timestamps are kept by the phase filter")
    void untimedWindowsKept() {
        SparkProfileSummariser.WindowStatistics w = new SparkProfileSummariser.WindowStatistics();
        assertTrue(SparkProfileSummariser.inPhase(w, 0L, 1L));
    }

    @Test
    @DisplayName("JsonBuilder handles empty arrays and scalar values")
    void emptyArraysAndScalars() {
        SparkProfileSummariser.JsonBuilder j = new SparkProfileSummariser.JsonBuilder();
        j.beginObj();
        j.key("empty_list"); j.beginArr(); j.endArr();
        j.key("numbers"); j.beginArr();
        j.value(1);
        j.value(2);
        j.value(3);
        j.endArr();
        j.endObj();

        String json = j.toString();
        JsonElement parsed = assertDoesNotThrow(() -> JsonParser.parseString(json));
        JsonObject root = parsed.getAsJsonObject();
        assertEquals(0, root.getAsJsonArray("empty_list").size());
        JsonArray numbers = root.getAsJsonArray("numbers");
        assertEquals(3, numbers.size());
        assertEquals(1, numbers.get(0).getAsInt());
        assertEquals(2, numbers.get(1).getAsInt());
        assertEquals(3, numbers.get(2).getAsInt());
    }
}
