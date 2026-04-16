package com.netflix.eureka.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JMH benchmark that exercises code paths with {@link ReentrantLock} (post-conversion
 * from {@code synchronized}) to verify zero virtual thread pinning events.
 *
 * <p>Run with {@code -Djdk.tracePinnedThreads=full} and inspect JFR event
 * {@code jdk.VirtualThreadPinned} to confirm no pinning occurs after the
 * {@code synchronized} &rarr; {@code ReentrantLock} conversion.</p>
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Concurrent access using {@code ReentrantLock} (post-conversion)</li>
 *   <li>Concurrent access using {@code synchronized} (pre-conversion baseline)</li>
 *   <li>Throughput comparison between both approaches</li>
 * </ol>
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {"-Djdk.tracePinnedThreads=full"})
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
public class PinningDetectionBenchmark {

    @Param({"REENTRANT_LOCK", "SYNCHRONIZED"})
    private String lockType;

    @Param({"10", "50", "100"})
    private int concurrentTasks;

    private ExecutorService virtualExecutor;
    private ReentrantLock reentrantLock;
    private final Object syncLock = new Object();
    private final AtomicLong sharedCounter = new AtomicLong(0);

    @Setup(Level.Trial)
    public void setup() {
        virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        reentrantLock = new ReentrantLock();
        sharedCounter.set(0);
    }

    @TearDown(Level.Trial)
    public void teardown() {
        virtualExecutor.shutdownNow();
        try {
            virtualExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Benchmark
    public void concurrentLockAccess(Blackhole bh) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(concurrentTasks);
        List<Future<?>> futures = new ArrayList<>(concurrentTasks);

        for (int i = 0; i < concurrentTasks; i++) {
            futures.add(virtualExecutor.submit(() -> {
                try {
                    if ("REENTRANT_LOCK".equals(lockType)) {
                        reentrantLock.lock();
                        try {
                            // Simulate the work done inside Application.addInstance() or
                            // AbstractInstanceRegistry.register() critical sections
                            sharedCounter.incrementAndGet();
                            Thread.sleep(1); // Brief I/O simulation
                        } finally {
                            reentrantLock.unlock();
                        }
                    } else {
                        synchronized (syncLock) {
                            sharedCounter.incrementAndGet();
                            Thread.sleep(1); // Brief I/O simulation — will pin with synchronized
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            }));
        }

        latch.await(30, TimeUnit.SECONDS);
        bh.consume(sharedCounter.get());
    }
}
