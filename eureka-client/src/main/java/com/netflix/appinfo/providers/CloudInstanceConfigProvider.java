package com.netflix.appinfo.providers;

import javax.inject.Provider;

import java.util.concurrent.locks.ReentrantLock;

import com.google.inject.Inject;
import com.netflix.appinfo.CloudInstanceConfig;
import com.netflix.discovery.DiscoveryManager;
import com.netflix.discovery.EurekaNamespace;

/**
 * This provider is necessary because the namespace is optional.
 * @author elandau
 */
public class CloudInstanceConfigProvider implements Provider<CloudInstanceConfig> {
    @Inject(optional = true)
    @EurekaNamespace
    private String namespace;

    private CloudInstanceConfig config;

    /**
     * Guards lazy initialization of {@link #config}. Replaces the previous
     * {@code synchronized} modifier on {@link #get()} so that virtual threads
     * racing on the first access do not pin their carrier threads.
     */
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public CloudInstanceConfig get() {
        lock.lock();
        try {
            if (config == null) {
                if (namespace == null) {
                    config = new CloudInstanceConfig();
                } else {
                    config = new CloudInstanceConfig(namespace);
                }

                // TODO: Remove this when DiscoveryManager is finally no longer used
                DiscoveryManager.getInstance().setEurekaInstanceConfig(config);
            }
            return config;
        } finally {
            lock.unlock();
        }
    }

}
