package com.netflix.appinfo.providers;

import javax.inject.Provider;

import java.util.concurrent.locks.ReentrantLock;

import com.google.inject.Inject;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.MyDataCenterInstanceConfig;
import com.netflix.discovery.DiscoveryManager;
import com.netflix.discovery.EurekaNamespace;

public class MyDataCenterInstanceConfigProvider implements Provider<EurekaInstanceConfig> {
    @Inject(optional = true)
    @EurekaNamespace
    private String namespace;

    private MyDataCenterInstanceConfig config;

    /**
     * Guards lazy initialization of {@link #config}. Replaces the previous
     * {@code synchronized} modifier on {@link #get()} so virtual threads
     * racing on the first access do not pin their carrier threads.
     */
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public MyDataCenterInstanceConfig get() {
        lock.lock();
        try {
            if (config == null) {
                if (namespace == null) {
                    config = new MyDataCenterInstanceConfig();
                } else {
                    config = new MyDataCenterInstanceConfig(namespace);
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
