package com.universium.webcraft.core;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/** Samples the optional Spark API on the server thread. */
final class SparkPerformance {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Api api;
    private final long interval;
    private Long lastSampleAt;
    private JsonObject sample;
    private boolean warned;

    SparkPerformance(Api api, int intervalSeconds) {
        this.api = api;
        interval = TimeUnit.SECONDS.toNanos(intervalSeconds);
    }

    static SparkPerformance open(int intervalSeconds) {
        if (!ModList.get().isLoaded("spark")) return null;
        try {
            return new SparkPerformance(Api.load(SparkPerformance.class.getClassLoader()), intervalSeconds);
        } catch (ReflectiveOperationException | LinkageError exception) {
            LOGGER.warn("Could not access the installed Spark API for WebCraft server information", exception);
            return null;
        }
    }

    JsonObject sample(long now) {
        if (lastSampleAt != null && now - lastSampleAt < interval) return sample;
        lastSampleAt = now;
        try {
            sample = api.sample();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            sample = null;
            if (!warned) {
                LOGGER.warn("Could not sample Spark performance for WebCraft server information", exception);
                warned = true;
            }
        }
        return sample;
    }

    private static void addMeasurement(JsonObject sample, String name, Object value) {
        if (value instanceof Number number) {
            double measurement = number.doubleValue();
            if (Double.isFinite(measurement) && measurement >= 0) {
                sample.addProperty(name, Math.round(measurement * 100) / 100.0);
            }
        }
    }

    // Resolve only the public interfaces so Spark is neither needed at build time nor bundled in WebCraft.
    record Api(Method provider, Method tps, Method mspt, Method pollTps, Method pollMspt,
               Object tpsWindow, Object msptWindow, Method mean, Method percentile95th) {
        static Api load(ClassLoader loader) throws ReflectiveOperationException {
            var spark = loader.loadClass("me.lucko.spark.api.Spark");
            var stats = "me.lucko.spark.api.statistic.";
            var averages = loader.loadClass(stats + "misc.DoubleAverageInfo");
            return new Api(loader.loadClass("me.lucko.spark.api.SparkProvider").getMethod("get"),
                    spark.getMethod("tps"), spark.getMethod("mspt"),
                    loader.loadClass(stats + "types.DoubleStatistic").getMethod("poll", Enum.class),
                    loader.loadClass(stats + "types.GenericStatistic").getMethod("poll", Enum.class),
                    loader.loadClass(stats + "StatisticWindow$TicksPerSecond").getField("MINUTES_1").get(null),
                    loader.loadClass(stats + "StatisticWindow$MillisPerTick").getField("MINUTES_1").get(null),
                    averages.getMethod("mean"), averages.getMethod("percentile95th"));
        }

        JsonObject sample() throws ReflectiveOperationException {
            Object spark;
            try {
                spark = provider.invoke(null);
            } catch (InvocationTargetException exception) {
                // Spark may initialize after the WebCraft listener.
                if (exception.getCause() instanceof IllegalStateException) return null;
                throw exception;
            }
            var sample = new JsonObject();
            var tickRate = tps.invoke(spark);
            if (tickRate != null) addMeasurement(sample, "tps", pollTps.invoke(tickRate, tpsWindow));
            var tickTimes = mspt.invoke(spark);
            if (tickTimes != null) {
                var average = pollMspt.invoke(tickTimes, msptWindow);
                if (average != null) {
                    addMeasurement(sample, "mspt", mean.invoke(average));
                    addMeasurement(sample, "msptP95", percentile95th.invoke(average));
                }
            }
            return sample.isEmpty() ? null : sample;
        }
    }
}
