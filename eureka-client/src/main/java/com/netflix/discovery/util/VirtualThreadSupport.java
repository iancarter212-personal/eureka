package com.netflix.discovery.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Utility class for Java 21 virtual thread support across the Eureka codebase.
 * Virtual threads can be enabled/disabled via the system property
 * {@code eureka.virtualThreads.enabled}.
 *
 * <p>When enabled, executor services and worker threads will use virtual threads
 * instead of platform threads, providing better scalability for I/O-bound workloads
 * such as replication, heartbeat, and cache refresh operations.</p>
 */
public final class VirtualThreadSupport {

    private static final String PROPERTY_NAME = "eureka.virtualThreads.enabled";
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY_NAME);

    private VirtualThreadSupport() {
        // utility class
    }

    /**
     * Returns whether virtual threads are enabled via the system property
     * {@code eureka.virtualThreads.enabled}.
     */
    public static boolean isEnabled() {
        return ENABLED;
    }

    /**
     * Creates a new {@link ExecutorService} appropriate for the current configuration.
     * When virtual threads are enabled, returns {@link Executors#newVirtualThreadPerTaskExecutor()};
     * otherwise returns {@code null}, signaling that the caller should use its own
     * platform-thread-based executor.
     */
    public static ExecutorService newExecutorServiceOrNull() {
        return ENABLED ? Executors.newVirtualThreadPerTaskExecutor() : null;
    }

    /**
     * Creates a new thread with the given runnable and name. When virtual threads
     * are enabled, the returned thread is a virtual thread (which is always a daemon
     * thread). Otherwise, a platform daemon thread is returned.
     */
    public static Thread newThread(Runnable runnable, String name) {
        if (ENABLED) {
            return Thread.ofVirtual().name(name).unstarted(runnable);
        }
        Thread t = new Thread(runnable, name);
        t.setDaemon(true);
        return t;
    }
}
