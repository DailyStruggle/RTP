package io.github.dailystruggle.bstats.api;

import java.util.Map;
import java.util.concurrent.Callable;

/** Weighted categorical values per server; zero-weight slices are dropped. */
public final class AdvancedPie extends CustomChart {

    private final Callable<Map<String, Integer>> callable;

    public AdvancedPie(String chartId, Callable<Map<String, Integer>> callable) {
        super(chartId);
        this.callable = callable;
    }

    @Override
    protected String getChartData() throws Exception {
        return valuesObject(callable.call());
    }
}
