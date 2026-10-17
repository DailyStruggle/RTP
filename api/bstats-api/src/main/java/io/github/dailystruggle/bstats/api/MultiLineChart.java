package io.github.dailystruggle.bstats.api;

import java.util.Map;
import java.util.concurrent.Callable;

/** Several summed line series; zero-valued lines are dropped. */
public final class MultiLineChart extends CustomChart {

    private final Callable<Map<String, Integer>> callable;

    public MultiLineChart(String chartId, Callable<Map<String, Integer>> callable) {
        super(chartId);
        this.callable = callable;
    }

    @Override
    protected String getChartData() throws Exception {
        return valuesObject(callable.call());
    }
}
