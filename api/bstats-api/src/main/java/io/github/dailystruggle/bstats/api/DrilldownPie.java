package io.github.dailystruggle.bstats.api;

import java.util.Map;
import java.util.concurrent.Callable;

/** Two-level categorical values; outer slices with no inner entries are dropped. */
public final class DrilldownPie extends CustomChart {

    private final Callable<Map<String, Map<String, Integer>>> callable;

    public DrilldownPie(String chartId, Callable<Map<String, Map<String, Integer>>> callable) {
        super(chartId);
        this.callable = callable;
    }

    @Override
    protected String getChartData() throws Exception {
        Map<String, Map<String, Integer>> map = callable.call();
        if (map == null || map.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(128).append("{\"values\":{");
        boolean anyOuter = false;
        for (Map.Entry<String, Map<String, Integer>> outer : map.entrySet()) {
            Map<String, Integer> inner = outer.getValue();
            if (outer.getKey() == null || inner == null || inner.isEmpty()) continue;
            StringBuilder innerSb = new StringBuilder(32);
            boolean anyInner = false;
            for (Map.Entry<String, Integer> e : inner.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                if (anyInner) innerSb.append(',');
                anyInner = true;
                innerSb.append(BStatsJson.quote(e.getKey())).append(':').append(e.getValue().intValue());
            }
            if (!anyInner) continue;
            if (anyOuter) sb.append(',');
            anyOuter = true;
            sb.append(BStatsJson.quote(outer.getKey())).append(":{").append(innerSb).append('}');
        }
        return anyOuter ? sb.append("}}").toString() : null;
    }
}
