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

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper that installs a virtual-thread-per-task executor on an embedded servlet
 * container so every inbound REST request runs on its own virtual thread.
 *
 * <p>Eureka Server targets Java 21 and is typically deployed as a WAR inside an
 * embedded servlet container chosen by the operator (Tomcat, Jetty, etc.). This
 * helper intentionally uses reflection so that {@code eureka-core} does not take a
 * hard dependency on any particular container. Callers who bundle Tomcat 10.1.19+
 * (or Jetty 12+) can invoke {@link #configure(Object)} from their bootstrap code
 * and pass the container's {@code ProtocolHandler} (Tomcat) or {@code Server}
 * (Jetty); for any other container that exposes a {@code setExecutor(Executor)}
 * or {@code setThreadPool(Executor)} method, this helper will also work.
 *
 * <p>Using virtual threads here replaces the traditional thread-per-request model,
 * which was previously capped by the container's worker pool size (typically 200
 * threads). With virtual threads, each request gets its own cheap virtual thread;
 * during blocking I/O (registry reads/writes, replication fan-out) the JVM
 * unmounts the virtual thread from its carrier, freeing the carrier to serve
 * another request. This eliminates the platform-thread ceiling and yields a
 * substantial increase in peak concurrent request handling capacity.
 *
 * <p>Example (Tomcat):
 * <pre>{@code
 * Connector connector = tomcat.getConnector();
 * VirtualThreadServletContainerCustomizer.configure(connector.getProtocolHandler());
 * }</pre>
 *
 * <p>Example (Jetty):
 * <pre>{@code
 * Server server = new Server();
 * VirtualThreadServletContainerCustomizer.configure(server);
 * }</pre>
 */
public final class VirtualThreadServletContainerCustomizer {

    private static final Logger logger = LoggerFactory.getLogger(VirtualThreadServletContainerCustomizer.class);

    private VirtualThreadServletContainerCustomizer() {
    }

    /**
     * Install a fresh virtual-thread-per-task executor on the supplied container
     * component. The component is expected to expose one of
     * {@code setExecutor(java.util.concurrent.Executor)} or
     * {@code setThreadPool(java.util.concurrent.Executor)}; these cover Tomcat's
     * {@code ProtocolHandler} and Jetty's {@code Server} respectively.
     *
     * @param containerComponent typically a Tomcat {@code ProtocolHandler} or a
     *                           Jetty {@code Server}. Must not be {@code null}.
     * @return {@code true} if a virtual-thread executor was successfully installed,
     *         {@code false} if no compatible setter was found.
     */
    public static boolean configure(Object containerComponent) {
        if (containerComponent == null) {
            throw new IllegalArgumentException("containerComponent must not be null");
        }
        Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        return applySetter(containerComponent, "setExecutor", virtualThreadExecutor)
                || applySetter(containerComponent, "setThreadPool", virtualThreadExecutor);
    }

    private static boolean applySetter(Object target, String methodName, Executor executor) {
        Class<?> current = target.getClass();
        while (current != null) {
            for (Method method : current.getMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length != 1) {
                    continue;
                }
                if (!parameterTypes[0].isAssignableFrom(Executor.class)
                        && !parameterTypes[0].isInstance(executor)) {
                    continue;
                }
                try {
                    method.invoke(target, executor);
                    logger.info("Installed virtual-thread executor on {} via {}.{}(Executor)",
                            target.getClass().getName(), current.getName(), methodName);
                    return true;
                } catch (ReflectiveOperationException e) {
                    logger.warn("Failed to install virtual-thread executor via {}.{}(Executor)",
                            current.getName(), methodName, e);
                    return false;
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }
}
