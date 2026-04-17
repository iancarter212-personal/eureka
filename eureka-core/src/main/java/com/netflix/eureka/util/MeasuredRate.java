/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.netflix.eureka.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for getting a count in last X milliseconds.
 *
 * @author Karthik Ranganathan,Greg Kim
 */
public class MeasuredRate {
    private static final Logger logger = LoggerFactory.getLogger(MeasuredRate.class);
    private final AtomicLong lastBucket = new AtomicLong(0);
    private final AtomicLong currentBucket = new AtomicLong(0);

    private final long sampleInterval;
    /**
     * Uses a {@link ScheduledExecutorService} rather than {@link java.util.Timer}
     * because {@code Timer} silently terminates its single worker thread if a
     * task throws an unchecked exception, whereas the scheduled executor keeps
     * running subsequent executions. It also aligns better with the virtual
     * thread strategy used elsewhere in Eureka.
     */
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> scheduledTask;

    /**
     * Guards lifecycle transitions of {@link #scheduledTask}. Previously the
     * {@code start}/{@code stop} methods were {@code synchronized}; a
     * {@link ReentrantLock} is used instead to avoid pinning carrier threads
     * when called from virtual threads.
     */
    private final ReentrantLock lock = new ReentrantLock();

    private volatile boolean isActive;

    /**
     * @param sampleInterval in milliseconds
     */
    public MeasuredRate(long sampleInterval) {
        this.sampleInterval = sampleInterval;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Eureka-MeasureRateTimer");
            t.setDaemon(true);
            return t;
        });
        this.isActive = false;
    }

    public void start() {
        lock.lock();
        try {
            if (!isActive) {
                scheduledTask = executor.scheduleWithFixedDelay(() -> {
                    try {
                        // Zero out the current bucket.
                        lastBucket.set(currentBucket.getAndSet(0));
                    } catch (Throwable e) {
                        logger.error("Cannot reset the Measured Rate", e);
                    }
                }, sampleInterval, sampleInterval, TimeUnit.MILLISECONDS);

                isActive = true;
            }
        } finally {
            lock.unlock();
        }
    }

    public void stop() {
        lock.lock();
        try {
            if (isActive) {
                if (scheduledTask != null) {
                    scheduledTask.cancel(false);
                    scheduledTask = null;
                }
                executor.shutdownNow();
                isActive = false;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the count in the last sample interval.
     */
    public long getCount() {
        return lastBucket.get();
    }

    /**
     * Increments the count in the current sample interval.
     */
    public void increment() {
        currentBucket.incrementAndGet();
    }
}
