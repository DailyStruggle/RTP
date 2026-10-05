package io.github.dailystruggle.bstats.api;

import java.util.concurrent.Callable;

/** Single categorical value per server. */
public final class SimplePie extends CustomChart {

    private final Callable<String> callable;

    public SimplePie(String chartId, Callable<String> callable) {
        super(chartId);
        this.callable = callable;
    }

    @Override
    protected String getChartData() throws Exception {
        String value = callable.call();
        if (value == null || value.isEmpty()) return null;
        return "{\"value\":" + BStatsJson.quote(value) + "}";
    }
}
