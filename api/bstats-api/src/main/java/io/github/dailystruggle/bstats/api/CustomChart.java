package io.github.dailystruggle.bstats.api;

import java.util.Map;
import java.util.logging.Level;

/**
 * One bStats custom chart. Mirrors the {@code org.bstats.charts} contract so chart
 * registration reads the same as with the upstream library: a supplier that
 * throws, or returns {@code null} / empty, drops the chart from that submission
 * only.
 */
public abstract class CustomChart {

    private final String chartId;

    protected CustomChart(String chartId) {
        if (chartId == null || chartId.isBlank()) {
            throw new IllegalArgumentException("chartId must not be null or blank");
        }
        this.chartId = chartId;
    }

    public final String getChartId() {
        return chartId;
    }

    /**
     * JSON object for the chart's {@code data} field, or {@code null} to skip the
     * chart for this submission.
     */
    protected abstract String getChartData() throws Exception;

    /** {@code {"chartId":..,"data":..}}, or {@code null} when skipped or the supplier failed. */
    public final String toJson() {
        return toJson(BStatsLog.NONE);
    }

    /** As {@link #toJson()}, reporting a failing supplier to {@code log} at {@code FINE}. */
    public final String toJson(BStatsLog log) {
        try {
            String data = getChartData();
            if (data == null) return null;
            return "{\"chartId\":" + BStatsJson.quote(chartId) + ",\"data\":" + data + "}";
        } catch (Throwable t) {
            log.log(Level.FINE, "[bStats] chart '" + chartId + "' skipped: " + t);
            return null;
        }
    }

    /** {@code {"values":{k:v,..}}} skipping zero values; {@code null} if nothing remains. */
    static String valuesObject(Map<String, Integer> map) {
        if (map == null || map.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(64).append("{\"values\":{");
        boolean any = false;
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() == 0) continue;
            if (any) sb.append(',');
            any = true;
            sb.append(BStatsJson.quote(e.getKey())).append(':').append(e.getValue().intValue());
        }
        return any ? sb.append("}}").toString() : null;
    }
}
