package com.universium.webcraft.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class SparkPerformanceTest {
    @Test
    void retriesAfterSparkStartsAndKeepsOneSamplePerInterval() throws ReflectiveOperationException {
        Source.current = null;
        var performance = new SparkPerformance(api(), 15);
        long interval = TimeUnit.SECONDS.toNanos(15);
        assertNull(performance.sample(0));

        Source.current = new Source(19.987, new Durations(18.346, 40.129));
        assertNull(performance.sample(interval - 1));
        var first = performance.sample(interval);
        assertEquals(19.99, first.get("tps").getAsDouble());
        assertEquals(18.35, first.get("mspt").getAsDouble());
        assertEquals(40.13, first.get("msptP95").getAsDouble());

        Source.current = new Source(12.25, null);
        assertSame(first, performance.sample(interval * 2 - 1));
        var partial = performance.sample(interval * 2);
        assertEquals(12.25, partial.get("tps").getAsDouble());
        assertFalse(partial.has("mspt"));

        Source.current = new Source(Double.NaN, new Durations(-1, Double.POSITIVE_INFINITY));
        assertNull(performance.sample(interval * 3));
    }

    private static SparkPerformance.Api api() throws ReflectiveOperationException {
        return new SparkPerformance.Api(Source.class.getMethod("get"), Source.class.getMethod("tps"),
                Source.class.getMethod("mspt"), Rate.class.getMethod("poll", String.class),
                Times.class.getMethod("poll", String.class), "MINUTES_1", "MINUTES_1",
                Durations.class.getMethod("mean"), Durations.class.getMethod("percentile95th"));
    }

    record Source(double rate, Durations durations) {
        private static Source current;

        public static Source get() {
            if (current == null) throw new IllegalStateException("Spark is starting");
            return current;
        }

        public Rate tps() {
            return new Rate(rate);
        }

        public Times mspt() {
            return durations == null ? null : new Times(durations);
        }
    }

    record Rate(double value) {
        public double poll(String window) {
            assertEquals("MINUTES_1", window);
            return value;
        }
    }

    record Times(Durations value) {
        public Durations poll(String window) {
            assertEquals("MINUTES_1", window);
            return value;
        }
    }

    record Durations(double mean, double percentile95th) {}
}
