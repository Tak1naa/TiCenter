package com.exambank.chat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;

/** Bounded admission for long-lived upstream LLM streams. */
@Service
public class ChatAdmissionService {
    private final Semaphore active;
    private final int maxActive;
    private final int maxWaiting;
    private final Duration maxWait;
    private final AtomicInteger waiting = new AtomicInteger();
    private final ConcurrentHashMap<String, Semaphore> providers = new ConcurrentHashMap<>();
    private final int maxProviderActive;
    private final Counter rejected;

    public ChatAdmissionService(@Value("${app.chat.max-active:128}") int maxActive,
                                @Value("${app.chat.max-waiting:512}") int maxWaiting,
                                @Value("${app.chat.max-wait-seconds:30}") long maxWaitSeconds,
                                @Value("${app.chat.provider-max-active:32}") int maxProviderActive,
                                MeterRegistry registry) {
        this.maxActive = Math.max(1, maxActive);
        this.active = new Semaphore(this.maxActive, true);
        this.maxWaiting = Math.max(0, maxWaiting);
        this.maxWait = Duration.ofSeconds(Math.max(1, maxWaitSeconds));
        this.maxProviderActive = Math.max(1, maxProviderActive);
        this.rejected = Counter.builder("chat.admission.rejected").register(registry);
        Gauge.builder("chat.admission.active", this, ChatAdmissionService::activeCount).register(registry);
        Gauge.builder("chat.admission.waiting", waiting, AtomicInteger::get).register(registry);
    }

    public Lease reserve(String providerName) {
        String providerKey = providerName == null || providerName.isBlank() ? "default" : providerName;
        Semaphore provider = providers.computeIfAbsent(providerKey,
                ignored -> new Semaphore(maxProviderActive, true));
        boolean globalAcquired = active.tryAcquire();
        boolean providerAcquired = provider.tryAcquire();
        if (globalAcquired && providerAcquired)
            return new Lease(provider, true, true, false, System.nanoTime());
        if (globalAcquired) active.release();
        if (providerAcquired) provider.release();
        int queued = waiting.incrementAndGet();
        if (queued > maxWaiting) {
            waiting.decrementAndGet();
            rejected.increment();
            return null;
        }
        return new Lease(provider, false, false, true, System.nanoTime());
    }

    public int activeCount() { return maxActive - active.availablePermits(); }
    public int waitingCount() { return waiting.get(); }

    public final class Lease implements AutoCloseable {
        private final Semaphore provider;
        private boolean globalAcquired;
        private boolean providerAcquired;
        private boolean queued;
        private final long reservedAt;

        private Lease(Semaphore provider, boolean globalAcquired, boolean providerAcquired,
                      boolean queued, long reservedAt) {
            this.provider = provider;
            this.globalAcquired = globalAcquired;
            this.providerAcquired = providerAcquired;
            this.queued = queued;
            this.reservedAt = reservedAt;
        }

        public boolean await() throws InterruptedException {
            if (globalAcquired && providerAcquired) return true;
            try {
                long deadline = System.nanoTime() + maxWait.toNanos();
                globalAcquired = active.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
                if (!globalAcquired) return false;
                long remaining = Math.max(0, deadline - System.nanoTime());
                providerAcquired = provider.tryAcquire(remaining, TimeUnit.NANOSECONDS);
                if (!providerAcquired) {
                    active.release();
                    globalAcquired = false;
                }
                return providerAcquired;
            } finally {
                if (queued) {
                    waiting.decrementAndGet();
                    queued = false;
                }
            }
        }

        public long waitMillis() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - reservedAt);
        }

        @Override
        public void close() {
            if (queued) {
                waiting.decrementAndGet();
                queued = false;
            }
            if (providerAcquired) {
                provider.release();
                providerAcquired = false;
            }
            if (globalAcquired) {
                active.release();
                globalAcquired = false;
            }
        }
    }
}
