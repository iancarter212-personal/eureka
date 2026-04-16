# Eureka Virtual Threads Benchmark Results

## Test Environment

| Property | Value |
|----------|-------|
| JDK Version | 21 |
| OS | (fill in) |
| CPU | (fill in) |
| RAM | (fill in) |
| JVM Flags | `-Djdk.tracePinnedThreads=full` |

## Configuration

Virtual threads are enabled via the system property:
```
-Deureka.virtualThreads.enabled=true
```

## Benchmark 1: TaskExecutors Worker Throughput

Measures replication worker throughput with platform vs virtual threads under varying I/O latency and worker counts.

| Thread Type | Worker Count | I/O Latency (ms) | Throughput (ops/s) | Avg Latency (ms) | P99 Latency (ms) |
|-------------|-------------|-------------------|--------------------|--------------------|-------------------|
| PLATFORM | 5 | 5 | | | |
| VIRTUAL | 5 | 5 | | | |
| PLATFORM | 50 | 50 | | | |
| VIRTUAL | 50 | 50 | | | |
| PLATFORM | 100 | 200 | | | |
| VIRTUAL | 100 | 200 | | | |

## Benchmark 2: AwsAsgUtil Concurrent Lookups

Simulates concurrent AWS API-style lookups with a bounded platform-thread pool (max 10) vs unbounded virtual threads.

| Executor Type | Concurrent Lookups | API Latency (ms) | Wall-Clock Time (ms) | Throughput (ops/s) |
|---------------|-------------------|-------------------|----------------------|--------------------|
| PLATFORM_POOL_10 | 50 | 200 | | |
| VIRTUAL | 50 | 200 | | |
| PLATFORM_POOL_10 | 500 | 200 | | |
| VIRTUAL | 500 | 200 | | |

## Benchmark 3: TimedSupervisorTask Compatibility

Verifies that `TimedSupervisorTask` works correctly with both executor types after the `ThreadPoolExecutor` → `ExecutorService` refactor.

| Executor Type | Task Duration (ms) | Timeout Accuracy | Active Count Accuracy |
|---------------|-------------------|------------------|-----------------------|
| PLATFORM | 100 | | |
| VIRTUAL | 100 | | |
| PLATFORM | 500 | | |
| VIRTUAL | 500 | | |

## Benchmark 4: End-to-End Replication Pipeline

Full pipeline test: tasks submitted → dispatched to workers → processed by SimulatedIOProcessor.

| Thread Type | Peer Count | Peer Latency (ms) | End-to-End Latency (ms) | Queue Overflow Rate | Task Expiry Rate |
|-------------|-----------|--------------------|--------------------------|--------------------|------------------|
| PLATFORM | 5 | 100 | | | |
| VIRTUAL | 5 | 100 | | | |
| PLATFORM | 10 | 500 | | | |
| VIRTUAL | 10 | 500 | | | |

## Benchmark 5: Pinning Detection

Verifies that the `synchronized` → `ReentrantLock` conversion eliminates virtual thread pinning.

| Lock Type | Concurrent Tasks | Pinning Events | Throughput (ops/ms) |
|-----------|-----------------|----------------|---------------------|
| SYNCHRONIZED | 50 | (expected: >0) | |
| REENTRANT_LOCK | 50 | (expected: 0) | |
| SYNCHRONIZED | 100 | (expected: >0) | |
| REENTRANT_LOCK | 100 | (expected: 0) | |

## Benchmark 6: Memory and Scalability

Memory footprint and thread creation/shutdown times at scale.

| Thread Type | Task Count | Peak Threads | Heap Delta (MB) | Create Time (ms) | Shutdown Time (ms) |
|-------------|-----------|--------------|-----------------|-------------------|---------------------|
| PLATFORM | 100 | | | | |
| VIRTUAL | 100 | | | | |
| PLATFORM | 1000 | | | | |
| VIRTUAL | 1000 | | | | |
| PLATFORM | 5000 | | | | |
| VIRTUAL | 5000 | | | | |

## Pinning Analysis

Run with `-Djdk.tracePinnedThreads=full` and check for `jdk.VirtualThreadPinned` JFR events.

- **Before conversion** (`synchronized` blocks): Pinning events expected in `Application.java` and `AbstractInstanceRegistry.java`
- **After conversion** (`ReentrantLock`): Zero pinning events expected

## Recommendations

1. **Enable virtual threads** for I/O-bound executor pools (`TaskExecutors` workers, `AwsAsgUtil` cache reload, `DiscoveryClient` heartbeat/cache refresh)
2. **Keep platform threads** for scheduled executors and CPU-bound single-thread pools
3. **Use `ReentrantLock`** instead of `synchronized` in code paths exercised by virtual threads
4. **Monitor** thread counts and memory via JMX/Servo metrics after enabling virtual threads in production
