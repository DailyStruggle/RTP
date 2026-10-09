package io.github.dailystruggle.rtp.bukkitplatform.metrics;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

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
 *   Spark#registerMetadataProvider(MetadataProvider)
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

    private final Method registerMetadataMethod;
    private final Class<?> metadataProviderClass;
    private boolean metadataProviderRegistered = false;

    ReflectiveSparkStats() {
        boolean ok = false;
        Method providerGet0 = null, tpsMethod0 = null, msptMethod0 = null,
                tpsPoll0 = null, msptPoll0 = null, meanMethod0 = null;
        Object[] tpsWindows0 = null;
        Object msptWindow0 = null;
        Method registerMetadataMethod0 = null;
        Class<?> metadataProviderClass0 = null;
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

            // Look for registerMetadataProvider on Spark interface/class
            for (Method m : sparkClass.getMethods()) {
                if ("registerMetadataProvider".equals(m.getName()) && m.getParameterCount() == 1) {
                    registerMetadataMethod0 = m;
                    metadataProviderClass0 = m.getParameterTypes()[0];
                    break;
                }
            }

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
        this.registerMetadataMethod = registerMetadataMethod0;
        this.metadataProviderClass = metadataProviderClass0;

        if (ok) {
            registerMetadataProviderIfPossible();
        }
    }

    private void registerMetadataProviderIfPossible() {
        if (!available || metadataProviderRegistered || registerMetadataMethod == null
                || metadataProviderClass == null || !metadataProviderClass.isInterface()) {
            return;
        }
        try {
            Object spark = providerGet.invoke(null);
            if (spark == null) return;
            Object proxy = createMetadataProviderProxy(metadataProviderClass);
            registerMetadataMethod.invoke(spark, proxy);
            metadataProviderRegistered = true;
        } catch (Throwable ignored) {
            // Self-heal on subsequent calls (e.g. if spark is still initializing)
        }
    }

    /**
     * Creates a dynamic proxy implementing spark's {@code MetadataProvider} interface
     * (or any interface passed as the parameter to registerMetadataProvider).
     *
     * @param interfaceClass target interface
     * @return proxy instance
     */
    static Object createMetadataProviderProxy(Class<?> interfaceClass) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            Class<?> returnType = method.getReturnType();
            if ("equals".equals(name) && args != null && args.length == 1) {
                return proxy == args[0];
            }
            if ("hashCode".equals(name) && (args == null || args.length == 0)) {
                return System.identityHashCode(proxy);
            }
            if ("toString".equals(name) && (args == null || args.length == 0)) {
                return "RTPSparkMetadataProviderProxy";
            }
            if (Map.class.isAssignableFrom(returnType)) {
                return SparkMetricsBinding.collectTelemetry();
            }
            if (returnType.isPrimitive()) {
                if (returnType == boolean.class) return false;
                if (returnType == byte.class) return (byte) 0;
                if (returnType == short.class) return (short) 0;
                if (returnType == int.class) return 0;
                if (returnType == long.class) return 0L;
                if (returnType == float.class) return 0.0f;
                if (returnType == double.class) return 0.0;
                if (returnType == char.class) return '\0';
            }
            return null;
        };
        return Proxy.newProxyInstance(interfaceClass.getClassLoader(), new Class<?>[] { interfaceClass }, handler);
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
            if (!metadataProviderRegistered) {
                registerMetadataProviderIfPossible();
            }
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
            if (!metadataProviderRegistered) {
                registerMetadataProviderIfPossible();
            }
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
