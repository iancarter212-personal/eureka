package com.netflix.discovery.providers;

import javax.inject.Provider;

import java.util.concurrent.locks.ReentrantLock;

import com.google.inject.Inject;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.DiscoveryManager;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.EurekaNamespace;

/**
 * This provider is necessary because the namespace is optional.
 * @author elandau
 */
public class DefaultEurekaClientConfigProvider implements Provider<EurekaClientConfig> {

    @Inject(optional = true)
    @EurekaNamespace
    private String namespace;

    private DefaultEurekaClientConfig config;

    /**
     * Guards lazy initialization of {@link #config}. Replaces the previous
     * {@code synchronized} modifier on {@link #get()} so virtual threads
     * racing on the first access do not pin their carrier threads.
     */
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public EurekaClientConfig get() {
        lock.lock();
        try {
            if (config == null) {
                config = (namespace == null)
                        ? new DefaultEurekaClientConfig()
                        : new DefaultEurekaClientConfig(namespace);

                // TODO: Remove this when DiscoveryManager is finally no longer used
                DiscoveryManager.getInstance().setEurekaClientConfig(config);
            }

            return config;
        } finally {
            lock.unlock();
        }
    }
}
