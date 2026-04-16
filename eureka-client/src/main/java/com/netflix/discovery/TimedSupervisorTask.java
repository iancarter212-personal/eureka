package com.netflix.discovery;

import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.LongGauge;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A supervisor task that schedules subtasks while enforce a timeout.
 * Wrapped subtasks must be thread safe.
 *
 * @author David Qiang Liu
 */
public class TimedSupervisorTask extends TimerTask {
    private static final Logger logger = LoggerFactory.getLogger(TimedSupervisorTask.class);

    private final Counter successCounter;
    private final Counter timeoutCounter;
    private final Counter rejectedCounter;
    private final Counter throwableCounter;
    private final LongGauge threadPoolLevelGauge;

    private final String name;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService executor;
    /**
     * When the executor is a {@link ThreadPoolExecutor} we can expose its active count
     * as a gauge. For {@link Executors#newVirtualThreadPerTaskExecutor() virtual-thread
     * per-task} executors, there is no bounded pool so we track inflight tasks ourselves
     * via {@link #inflightTasks}.
     */
    private final ThreadPoolExecutor threadPoolExecutor;
    private final AtomicInteger inflightTasks = new AtomicInteger();
    private final long timeoutMillis;
    private final Runnable task;

    private final AtomicLong delay;
    private final long maxDelay;

    public TimedSupervisorTask(String name, ScheduledExecutorService scheduler, ThreadPoolExecutor executor,
                               int timeout, TimeUnit timeUnit, int expBackOffBound, Runnable task) {
        this(name, scheduler, (ExecutorService) executor, executor, timeout, timeUnit, expBackOffBound, task);
    }

    /**
     * Virtual-thread friendly constructor. Accepts any {@link ExecutorService} (e.g. one
     * returned by {@link Executors#newVirtualThreadPerTaskExecutor()}), so callers can
     * opt out of the bounded platform-thread pool entirely.
     *
     * <p>When the supplied executor is not a {@link ThreadPoolExecutor}, the
     * {@code threadPoolUsed} gauge reflects the number of supervised tasks currently
     * in-flight instead of the pool's active count.
     */
    public TimedSupervisorTask(String name, ScheduledExecutorService scheduler, ExecutorService executor,
                               int timeout, TimeUnit timeUnit, int expBackOffBound, Runnable task) {
        this(name, scheduler, executor,
                executor instanceof ThreadPoolExecutor ? (ThreadPoolExecutor) executor : null,
                timeout, timeUnit, expBackOffBound, task);
    }

    private TimedSupervisorTask(String name, ScheduledExecutorService scheduler, ExecutorService executor,
                                ThreadPoolExecutor threadPoolExecutor,
                                int timeout, TimeUnit timeUnit, int expBackOffBound, Runnable task) {
        this.name = name;
        this.scheduler = scheduler;
        this.executor = executor;
        this.threadPoolExecutor = threadPoolExecutor;
        this.timeoutMillis = timeUnit.toMillis(timeout);
        this.task = task;
        this.delay = new AtomicLong(timeoutMillis);
        this.maxDelay = timeoutMillis * expBackOffBound;

        // Initialize the counters and register.
        successCounter = Monitors.newCounter("success");
        timeoutCounter = Monitors.newCounter("timeouts");
        rejectedCounter = Monitors.newCounter("rejectedExecutions");
        throwableCounter = Monitors.newCounter("throwables");
        threadPoolLevelGauge = new LongGauge(MonitorConfig.builder("threadPoolUsed").build());
        Monitors.registerObject(name, this);
    }

    private long currentActiveCount() {
        return threadPoolExecutor != null ? threadPoolExecutor.getActiveCount() : inflightTasks.get();
    }

    @Override
    public void run() {
        Future<?> future = null;
        boolean inflightIncremented = false;
        try {
            // Increment the inflight counter BEFORE submitting so it is guaranteed
            // to be visible to the wrapped Runnable's finally block. Virtual
            // threads can start executing the submitted task immediately on the
            // current carrier; if we incremented after submit() the decrement in
            // the finally block could fire first and drive the counter negative.
            if (threadPoolExecutor == null) {
                inflightTasks.incrementAndGet();
                inflightIncremented = true;
            }
            try {
                future = executor.submit(() -> {
                    try {
                        task.run();
                    } finally {
                        if (threadPoolExecutor == null) {
                            inflightTasks.decrementAndGet();
                        }
                    }
                });
            } catch (Throwable submitError) {
                // The executor rejected the task (or any other submit-time
                // failure). The wrapping Runnable never ran, so we must undo
                // the speculative increment ourselves.
                if (inflightIncremented) {
                    inflightTasks.decrementAndGet();
                    inflightIncremented = false;
                }
                throw submitError;
            }
            threadPoolLevelGauge.set(currentActiveCount());
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);  // block until done or timeout
            delay.set(timeoutMillis);
            threadPoolLevelGauge.set(currentActiveCount());
            successCounter.increment();
        } catch (TimeoutException e) {
            logger.warn("task supervisor timed out", e);
            timeoutCounter.increment();

            long currentDelay = delay.get();
            long newDelay = Math.min(maxDelay, currentDelay * 2);
            delay.compareAndSet(currentDelay, newDelay);

        } catch (RejectedExecutionException e) {
            if (executor.isShutdown() || scheduler.isShutdown()) {
                logger.warn("task supervisor shutting down, reject the task", e);
            } else {
                logger.warn("task supervisor rejected the task", e);
            }

            rejectedCounter.increment();
        } catch (Throwable e) {
            if (executor.isShutdown() || scheduler.isShutdown()) {
                logger.warn("task supervisor shutting down, can't accept the task");
            } else {
                logger.warn("task supervisor threw an exception", e);
            }

            throwableCounter.increment();
        } finally {
            if (future != null) {
                future.cancel(true);
            }

            if (!scheduler.isShutdown()) {
                scheduler.schedule(this, delay.get(), TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    public boolean cancel() {
        Monitors.unregisterObject(name, this);
        return super.cancel();
    }
}
