package com.netflix.eureka.benchmark;

import com.netflix.discovery.util.VirtualThreadSupport;
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
 * JMH benchmark that measures {@code TaskExecutors} worker throughput with
 * platform vs virtual threads under varying I/O latency and worker counts.
 *
 * <p>Parameters to vary:
 * <ul>
 *   <li>{@code threadType}: PLATFORM or VIRTUAL</li>
 *   <li>{@code workerCount}: number of worker threads</li>
 *   <li>{@code ioLatencyMs}: simulated I/O latency per task</li>
 * </ul>
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {"-Djdk.tracePinnedThreads=full"})
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
public class TaskExecutorsBenchmark {

    @Param({"PLATFORM", "VIRTUAL"})
    private String threadType;

    @Param({"5", "10", "50", "100"})
    private int workerCount;

    @Param({"5", "50", "200"})
    private long ioLatencyMs;

    private ExecutorService executorService;
    private List<Thread> workerThreads;
    private BlockingQueue<Runnable> taskQueue;
    private AtomicBoolean isShutdown;
    private AtomicLong completedTasks;
    private ThreadMXBean threadMXBean;

    @Setup(Level.Trial)
    public void setup() {
        threadMXBean = ManagementFactory.getThreadMXBean();
        isShutdown = new AtomicBoolean(false);
        completedTasks = new AtomicLong(0);
        taskQueue = new LinkedBlockingQueue<>();
        workerThreads = new ArrayList<>();

        boolean useVirtual = "VIRTUAL".equals(threadType);

        for (int i = 0; i < workerCount; i++) {
            String name = "BenchmarkWorker-" + i;
            Runnable workerLoop = () -> {
                while (!isShutdown.get()) {
                    try {
                        Runnable task = taskQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (task != null) {
                            task.run();
                            completedTasks.incrementAndGet();
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
            workerThreads.add(t);
            t.start();
        }
    }

    @TearDown(Level.Trial)
    public void teardown() {
        isShutdown.set(true);
        for (Thread t : workerThreads) {
            t.interrupt();
        }
        for (Thread t : workerThreads) {
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Benchmark
    public void submitAndProcessTask(Blackhole bh) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        taskQueue.put(() -> {
            try {
                Thread.sleep(ioLatencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            latch.countDown();
        });
        latch.await(ioLatencyMs + 5000, TimeUnit.MILLISECONDS);
        bh.consume(completedTasks.get());
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
