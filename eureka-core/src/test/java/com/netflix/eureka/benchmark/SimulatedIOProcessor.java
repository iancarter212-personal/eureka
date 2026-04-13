package com.netflix.eureka.benchmark;

import com.netflix.eureka.util.batcher.TaskProcessor;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link TaskProcessor} that simulates network I/O latency via {@link Thread#sleep}.
 * Used in benchmarks to model realistic replication workloads where each task
 * involves a remote call (e.g., peer replication, AWS API lookup).
 *
 * <p>Virtual threads yield the carrier thread during {@code Thread.sleep},
 * so this processor is ideal for comparing platform vs virtual thread throughput
 * under I/O-bound conditions.</p>
 */
public class SimulatedIOProcessor implements TaskProcessor<String> {

    private final long ioLatencyMs;
    private final AtomicLong processedCount = new AtomicLong();

    public SimulatedIOProcessor(long ioLatencyMs) {
        this.ioLatencyMs = ioLatencyMs;
    }

    @Override
    public ProcessingResult process(String task) {
        try {
            Thread.sleep(ioLatencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProcessingResult.TransientError;
        }
        processedCount.incrementAndGet();
        return ProcessingResult.Success;
    }

    @Override
    public ProcessingResult process(List<String> tasks) {
        try {
            Thread.sleep(ioLatencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProcessingResult.TransientError;
        }
        processedCount.addAndGet(tasks.size());
        return ProcessingResult.Success;
    }

    public long getProcessedCount() {
        return processedCount.get();
    }

    public void resetCount() {
        processedCount.set(0);
    }
}
