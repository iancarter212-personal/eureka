package com.netflix.eureka.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMH benchmark modeling the full Eureka replication pipeline:
 * tasks submitted &rarr; dispatched to workers &rarr; processed by {@link SimulatedIOProcessor}.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code threadType}: PLATFORM or VIRTUAL</li>
 *   <li>{@code peerNodeCount}: simulated peer count (= worker count)</li>
 *   <li>{@code peerLatencyMs}: simulated peer response latency</li>
 * </ul>
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {"-Djdk.tracePinnedThreads=full"})
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
public class ReplicationPipelineBenchmark {

    @Param({"PLATFORM", "VIRTUAL"})
    private String threadType;

    @Param({"3", "5", "10"})
    private int peerNodeCount;

    @Param({"10", "100", "500"})
    private long peerLatencyMs;

    private BlockingQueue<Runnable> taskQueue;
    private List<Thread> workers;
    private AtomicBoolean isShutdown;
    private AtomicLong processedCount;
    private AtomicLong expiredCount;
    private ThreadMXBean threadMXBean;

    @Setup(Level.Trial)
    public void setup() {
        threadMXBean = ManagementFactory.getThreadMXBean();
        isShutdown = new AtomicBoolean(false);
        processedCount = new AtomicLong(0);
        expiredCount = new AtomicLong(0);
        taskQueue = new LinkedBlockingQueue<>(10000);
        workers = new ArrayList<>();

        boolean useVirtual = "VIRTUAL".equals(threadType);

        for (int i = 0; i < peerNodeCount; i++) {
            String name = "ReplicationWorker-" + i;
            Runnable workerLoop = () -> {
                while (!isShutdown.get()) {
                    try {
                        Runnable task = taskQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (task != null) {
                            task.run();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            };

            Thread t;
            if (useVirtual) {
                t = Thread.ofVirtual().name(name).unstarted(workerLoop);
            } else {
                t = new Thread(workerLoop, name);
                t.setDaemon(true);
            }
            workers.add(t);
            t.start();
        }
    }

    @TearDown(Level.Trial)
    public void teardown() {
        isShutdown.set(true);
        for (Thread t : workers) {
            t.interrupt();
        }
        for (Thread t : workers) {
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Benchmark
    public void replicationTask(Blackhole bh) throws InterruptedException {
        long submitTime = System.nanoTime();
        CountDownLatch latch = new CountDownLatch(1);

        boolean offered = taskQueue.offer(() -> {
            try {
                Thread.sleep(peerLatencyMs);
                processedCount.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            latch.countDown();
        }, 1, TimeUnit.SECONDS);

        if (!offered) {
            expiredCount.incrementAndGet();
            bh.consume(-1L);
            return;
        }

        boolean completed = latch.await(peerLatencyMs + 5000, TimeUnit.MILLISECONDS);
        long endToEndNs = System.nanoTime() - submitTime;

        if (!completed) {
            expiredCount.incrementAndGet();
        }

        bh.consume(endToEndNs);
        bh.consume(processedCount.get());
    }

    @Benchmark
    public void measureQueueMetrics(Blackhole bh) {
        bh.consume(taskQueue.size());
        bh.consume(processedCount.get());
        bh.consume(expiredCount.get());
        bh.consume(threadMXBean.getThreadCount());
    }
}
