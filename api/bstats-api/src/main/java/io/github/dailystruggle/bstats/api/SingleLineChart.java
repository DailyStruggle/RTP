package io.github.dailystruggle.bstats.api;

import java.util.concurrent.Callable;

/** One summed line series; a zero value is not sent. */
public final class SingleLineChart extends CustomChart {

    private final Callable<Integer> callable;

    public SingleLineChart(String chartId, Callable<Integer> callable) {
        super(chartId);
        this.callable = callable;
    }

    @Override
    protected String getChartData() throws Exception {
        Integer value = callable.call();
        if (value == null || value == 0) return null;
        return "{\"value\":" + value.intValue() + "}";
    }
}
