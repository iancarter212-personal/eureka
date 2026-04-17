/*
 * Copyright 2015 Netflix, Inc.
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

package com.netflix.eureka;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A static holder for the server context for use in non-DI cases.
 *
 * @author David Liu
 */
public class EurekaServerContextHolder {

    private final EurekaServerContext serverContext;

    private EurekaServerContextHolder(EurekaServerContext serverContext) {
        this.serverContext = serverContext;
    }

    public EurekaServerContext getServerContext() {
        return this.serverContext;
    }

    private static EurekaServerContextHolder holder;

    /**
     * Guards mutation of the static {@link #holder}. Replaces the previous
     * {@code static synchronized} modifier on {@link #initialize(EurekaServerContext)}
     * so virtual threads invoking initialize do not pin their carrier
     * threads on the class-level intrinsic monitor.
     */
    private static final ReentrantLock LOCK = new ReentrantLock();

    public static void initialize(EurekaServerContext serverContext) {
        LOCK.lock();
        try {
            holder = new EurekaServerContextHolder(serverContext);
        } finally {
            LOCK.unlock();
        }
    }

    public static EurekaServerContextHolder getInstance() {
        return holder;
    }
}
