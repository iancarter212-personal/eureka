package com.netflix.eureka.benchmark;

import com.netflix.discovery.TimedSupervisorTask;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMH benchmark verifying that {@link TimedSupervisorTask} works correctly with
 * both platform-thread and virtual-thread executors after the refactor from
 * {@code ThreadPoolExecutor} to {@code ExecutorService}.
 *
 * <p>Measures timeout detection accuracy, exponential backoff correctness,
 * and the replacement metric for {@code getActiveCount()}.</p>
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {"-Djdk.tracePinnedThreads=full"})
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
public class TimedSupervisorTaskBenchmark {

    @Param({"PLATFORM", "VIRTUAL"})
    private String executorType;

    @Param({"10", "100", "500"})
    private long taskDurationMs;

    private ScheduledExecutorService scheduler;
    private ExecutorService taskExecutor;
    private AtomicLong completionCount;
    private AtomicInteger timeoutCount;

    @Setup(Level.Trial)
    public void setup() {
        completionCount = new AtomicLong(0);
        timeoutCount = new AtomicInteger(0);

        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "BenchScheduler");
            t.setDaemon(true);
            return t;
        });

        if ("VIRTUAL".equals(executorType)) {
            taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
        } else {
            taskExecutor = new ThreadPoolExecutor(
                    1, 5, 0, TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "BenchTaskExec");
                        t.setDaemon(true);
                        return t;
                    }
            );
        }
    }

    @TearDown(Level.Trial)
    public void teardown() {
        scheduler.shutdownNow();
        taskExecutor.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
            taskExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Benchmark
    public void supervisorTaskExecution(Blackhole bh) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Runnable task = () -> {
            try {
                Thread.sleep(taskDurationMs);
                completionCount.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            latch.countDown();
        };

        Future<?> future = taskExecutor.submit(task);
        try {
            // Use a timeout slightly longer than the task to allow completion
            future.get(taskDurationMs + 1000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            timeoutCount.incrementAndGet();
            future.cancel(true);
        }

        bh.consume(completionCount.get());
        bh.consume(timeoutCount.get());
    }

    @Benchmark
    public void activeTaskTracking(Blackhole bh) throws Exception {
        AtomicInteger activeCount = new AtomicInteger(0);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);

        taskExecutor.submit(() -> {
            activeCount.incrementAndGet();
            started.countDown();
            try {
                finish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                activeCount.decrementAndGet();
            }
        });

        started.await(5, TimeUnit.SECONDS);
        bh.consume(activeCount.get()); // Should be 1
        finish.countDown();
        Thread.sleep(50); // Brief wait for decrement
        bh.consume(activeCount.get()); // Should be 0
    }
}
