package com.netflix.eureka.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * JMH benchmark focused on memory footprint and thread count at scale.
 *
 * <p>Measures:
 * <ul>
 *   <li>Peak thread count</li>
 *   <li>Heap usage before/after</li>
 *   <li>Time to create and start all threads</li>
 *   <li>Time to shut down all threads</li>
 * </ul>
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {"-Djdk.tracePinnedThreads=full", "-Xmx1g"})
@Warmup(iterations = 1)
@Measurement(iterations = 3)
public class ScalabilityBenchmark {

    @Param({"PLATFORM", "VIRTUAL"})
    private String threadType;

    @Param({"10", "50", "100", "500", "1000", "5000"})
    private int taskCount;

    private ThreadMXBean threadMXBean;

    @Setup(Level.Trial)
    public void setup() {
        threadMXBean = ManagementFactory.getThreadMXBean();
    }

    @Benchmark
    public void createAndRunThreads(Blackhole bh) throws InterruptedException {
        Runtime rt = Runtime.getRuntime();
        System.gc();
        long heapBefore = rt.totalMemory() - rt.freeMemory();
        int threadsBefore = threadMXBean.getThreadCount();

        boolean useVirtual = "VIRTUAL".equals(threadType);
        CountDownLatch startedLatch = new CountDownLatch(taskCount);
        CountDownLatch finishLatch = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>(taskCount);

        long createStartNs = System.nanoTime();

        for (int i = 0; i < taskCount; i++) {
            String name = "ScalabilityWorker-" + i;
            Runnable work = () -> {
                startedLatch.countDown();
                try {
                    finishLatch.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };

            Thread t;
            if (useVirtual) {
                t = Thread.ofVirtual().name(name).unstarted(work);
            } else {
                t = new Thread(work, name);
                t.setDaemon(true);
            }
            threads.add(t);
            t.start();
        }

        long createTimeNs = System.nanoTime() - createStartNs;

        // Wait for all threads to start
        startedLatch.await(30, TimeUnit.SECONDS);

        int peakThreadCount = threadMXBean.getThreadCount();
        long heapAfter = rt.totalMemory() - rt.freeMemory();

        // Signal all threads to finish
        long shutdownStartNs = System.nanoTime();
        finishLatch.countDown();

        for (Thread t : threads) {
            t.join(10000);
        }
        long shutdownTimeNs = System.nanoTime() - shutdownStartNs;

        bh.consume(createTimeNs);
        bh.consume(shutdownTimeNs);
        bh.consume(peakThreadCount - threadsBefore);
        bh.consume(heapAfter - heapBefore);
    }
}
