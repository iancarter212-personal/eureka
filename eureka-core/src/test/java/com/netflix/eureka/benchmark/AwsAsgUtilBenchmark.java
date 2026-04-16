package com.netflix.eureka.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * JMH benchmark simulating the pattern from {@code AwsAsgUtil} where a bounded
 * platform-thread pool (max 10 threads) or unbounded virtual-thread executor
 * performs concurrent AWS API-style lookups.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code executorType}: PLATFORM_POOL_10 or VIRTUAL</li>
 *   <li>{@code concurrentLookups}: number of concurrent lookup tasks</li>
 *   <li>{@code apiLatencyMs}: simulated per-lookup API latency</li>
 * </ul>
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {"-Djdk.tracePinnedThreads=full"})
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
public class AwsAsgUtilBenchmark {

    @Param({"PLATFORM_POOL_10", "VIRTUAL"})
    private String executorType;

    @Param({"10", "50", "100", "500"})
    private int concurrentLookups;

    @Param({"50", "200", "500"})
    private long apiLatencyMs;

    private ExecutorService executor;
    private ThreadMXBean threadMXBean;

    @Setup(Level.Trial)
    public void setup() {
        threadMXBean = ManagementFactory.getThreadMXBean();
        if ("VIRTUAL".equals(executorType)) {
            executor = Executors.newVirtualThreadPerTaskExecutor();
        } else {
            executor = new ThreadPoolExecutor(
                    1, 10, 60, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "Benchmark-ASG-Lookup");
                        t.setDaemon(true);
                        return t;
                    }
            );
        }
    }

    @TearDown(Level.Trial)
    public void teardown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Benchmark
    public void concurrentLookups(Blackhole bh) throws InterruptedException {
        List<Future<Boolean>> futures = new ArrayList<>(concurrentLookups);
        for (int i = 0; i < concurrentLookups; i++) {
            futures.add(executor.submit(() -> {
                Thread.sleep(apiLatencyMs);
                return Boolean.TRUE;
            }));
        }

        int completed = 0;
        for (Future<Boolean> f : futures) {
            try {
                f.get(apiLatencyMs * 2 + 5000, TimeUnit.MILLISECONDS);
                completed++;
            } catch (ExecutionException | TimeoutException e) {
                // count as failed
            }
        }
        bh.consume(completed);
    }

    @Benchmark
    public void measureThreadCount(Blackhole bh) {
        bh.consume(threadMXBean.getThreadCount());
    }

    @Benchmark
    public void measureMemoryFootprint(Blackhole bh) {
        Runtime rt = Runtime.getRuntime();
        bh.consume(rt.totalMemory() - rt.freeMemory());
    }
}
