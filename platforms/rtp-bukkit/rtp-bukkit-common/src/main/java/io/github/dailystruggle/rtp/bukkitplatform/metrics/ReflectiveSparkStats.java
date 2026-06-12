package io.github.dailystruggle.rtp.bukkitplatform.metrics;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;

import java.lang.reflect.Method;

/**
 * Reflective accessor for the spark public API
 * ({@code me.lucko.spark.api.*}). Resolves every required {@link Method} and
 * statistic-window enum constant once at construction; on any linkage failure
 * ({@link ClassNotFoundException} / {@link NoClassDefFoundError} /
 * {@link NoSuchMethodException}) it enters a permanent disabled state
 * ({@link #available()} returns {@code false}) so callers fall back to the
 * native binding. Reflection rather than a compile dependency keeps spark a
 * true soft-dependency with no added jar.
 *
 * <p>spark API shape used:
 * <pre>
 *   me.lucko.spark.api.SparkProvider#get() -&gt; Spark
 *   Spark#tps()  -&gt; DoubleStatistic&lt;TicksPerSecond&gt;
 *   Spark#mspt() -&gt; GenericStatistic&lt;DoubleAverageInfo, MillisPerTick&gt; (nullable)
 *   DoubleStatistic#poll(StatisticWindow) -&gt; double
 *   GenericStatistic#poll(StatisticWindow) -&gt; DoubleAverageInfo
 *   DoubleAverageInfo#mean() -&gt; double
 *   StatisticWindow.TicksPerSecond: MINUTES_1 / MINUTES_5 / MINUTES_15
 *   StatisticWindow.MillisPerTick:  MINUTES_1
 * </pre>
 *
 * <p>Each poll obtains the {@code Spark} instance lazily via
 * {@code SparkProvider.get()} so a transient pre-init state (where {@code get()}
 * throws {@code IllegalStateException}) self-heals once spark is ready; any such
 * failure is swallowed and reported as {@link MetricsSnapshot#UNSAMPLED}.
 */
final class ReflectiveSparkStats implements SparkMetricsBinding.SparkStats {

    private final boolean available;

    private final Method providerGet;
    private final Method tpsMethod;
    private final Method msptMethod;
    private final Method tpsPoll;
    private final Method msptPoll;
    private final Method meanMethod;
    private final Object[] tpsWindows; // [1m, 5m, 15m]
    private final Object msptWindow;

    ReflectiveSparkStats() {
        boolean ok = false;
        Method providerGet0 = null, tpsMethod0 = null, msptMethod0 = null,
                tpsPoll0 = null, msptPoll0 = null, meanMethod0 = null;
        Object[] tpsWindows0 = null;
        Object msptWindow0 = null;
        try {
            Class<?> providerClass = Class.forName("me.lucko.spark.api.SparkProvider");
            Class<?> sparkClass = Class.forName("me.lucko.spark.api.Spark");
            Class<?> windowClass = Class.forName("me.lucko.spark.api.statistic.StatisticWindow");
            Class<?> doubleStatClass =
                    Class.forName("me.lucko.spark.api.statistic.types.DoubleStatistic");
            Class<?> genericStatClass =
                    Class.forName("me.lucko.spark.api.statistic.types.GenericStatistic");
            Class<?> avgInfoClass =
                    Class.forName("me.lucko.spark.api.statistic.misc.DoubleAverageInfo");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<? extends Enum> tpsWindowEnum = (Class<? extends Enum>)
                    Class.forName("me.lucko.spark.api.statistic.StatisticWindow$TicksPerSecond");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<? extends Enum> msptWindowEnum = (Class<? extends Enum>)
                    Class.forName("me.lucko.spark.api.statistic.StatisticWindow$MillisPerTick");

            providerGet0 = providerClass.getMethod("get");
            tpsMethod0 = sparkClass.getMethod("tps");
            msptMethod0 = sparkClass.getMethod("mspt");
            tpsPoll0 = doubleStatClass.getMethod("poll", windowClass);
            msptPoll0 = genericStatClass.getMethod("poll", windowClass);
            meanMethod0 = avgInfoClass.getMethod("mean");

            tpsWindows0 = new Object[] {
                    enumConst(tpsWindowEnum, "MINUTES_1"),
                    enumConst(tpsWindowEnum, "MINUTES_5"),
                    enumConst(tpsWindowEnum, "MINUTES_15"),
            };
            msptWindow0 = enumConst(msptWindowEnum, "MINUTES_1");
            ok = true;
        } catch (ClassNotFoundException | NoClassDefFoundError | NoSuchMethodException
                 | RuntimeException e) {
            ok = false;
        }
        this.available = ok;
        this.providerGet = providerGet0;
        this.tpsMethod = tpsMethod0;
        this.msptMethod = msptMethod0;
        this.tpsPoll = tpsPoll0;
        this.msptPoll = msptPoll0;
        this.meanMethod = meanMethod0;
        this.tpsWindows = tpsWindows0;
        this.msptWindow = msptWindow0;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConst(Class<? extends Enum> enumClass, String name) {
        return Enum.valueOf(enumClass, name);
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public double tps(int windowIdx) {
        if (!available || windowIdx < 0 || windowIdx >= tpsWindows.length) {
            return MetricsSnapshot.UNSAMPLED;
        }
        try {
            Object spark = providerGet.invoke(null);
            if (spark == null) return MetricsSnapshot.UNSAMPLED;
            Object stat = tpsMethod.invoke(spark);
            if (stat == null) return MetricsSnapshot.UNSAMPLED;
            Object value = tpsPoll.invoke(stat, tpsWindows[windowIdx]);
            return (value instanceof Number) ? ((Number) value).doubleValue()
                    : MetricsSnapshot.UNSAMPLED;
        } catch (Throwable t) {
            return MetricsSnapshot.UNSAMPLED;
        }
    }

    @Override
    public double mspt() {
        if (!available) return MetricsSnapshot.UNSAMPLED;
        try {
            Object spark = providerGet.invoke(null);
            if (spark == null) return MetricsSnapshot.UNSAMPLED;
            Object stat = msptMethod.invoke(spark);
            if (stat == null) return MetricsSnapshot.UNSAMPLED; // mspt unsupported on this platform
            Object info = msptPoll.invoke(stat, msptWindow);
            if (info == null) return MetricsSnapshot.UNSAMPLED;
            Object value = meanMethod.invoke(info);
            return (value instanceof Number) ? ((Number) value).doubleValue()
                    : MetricsSnapshot.UNSAMPLED;
        } catch (Throwable t) {
            return MetricsSnapshot.UNSAMPLED;
        }
    }
}
