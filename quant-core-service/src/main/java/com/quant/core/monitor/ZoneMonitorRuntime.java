package com.quant.core.monitor;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Coalesces Pub/Sub hints; a periodic authoritative GET also recovers missed notifications. */
@Slf4j
public class ZoneMonitorRuntime implements MessageListener, SmartLifecycle {
    private final RedisLiveMarketSource source;
    private final ZoneMonitoringService service;
    private final MarketMonitorProperties properties;
    private final List<MonitorScope> scopes;
    private final Clock clock;
    private final boolean ownsWorker;
    private final AtomicBoolean hintQueued = new AtomicBoolean();
    private ScheduledExecutorService worker;
    private volatile boolean running;
    private boolean hasPolled;
    private long lastPoll;
    private RedisMessageListenerContainer subscription;
    private boolean hasAttemptedSubscription;
    private long lastSubscriptionAttempt;

    public ZoneMonitorRuntime(RedisLiveMarketSource source, ZoneMonitoringService service, MarketMonitorProperties properties) {
        this(source, service, properties, Clock.systemUTC(), null);
    }
    ZoneMonitorRuntime(RedisLiveMarketSource source, ZoneMonitoringService service, MarketMonitorProperties properties,
                       Clock clock, ScheduledExecutorService worker) {
        this.source = source; this.service = service; this.properties = properties; this.clock = clock;
        this.worker = worker; ownsWorker = worker == null; scopes = properties.scopes();
    }

    @Override public synchronized void start() {
        if (running || !properties.isEnabled()) return;
        if (ownsWorker) worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "zone-monitor-worker"); thread.setDaemon(true); return thread;
        });
        hasPolled = false; hasAttemptedSubscription = false; hintQueued.set(false); service.restart(); running = true;
        worker.scheduleWithFixedDelay(this::poll, 0, properties.getPollMillis(), TimeUnit.MILLISECONDS);
    }

    @Override public void onMessage(Message message, byte[] pattern) {
        // Ignore notification content. A delayed or malformed hint cannot overwrite canonical data.
        if (!running || !hintQueued.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> { try { poll(); } finally { hintQueued.set(false); } });
        } catch (RejectedExecutionException exception) { hintQueued.set(false); }
    }

    void poll() {
        if (!running) return;
        long now = clock.millis();
        if (hasPolled && now >= lastPoll && now - lastPoll < properties.getPollMillis()) return;
        hasPolled = true; lastPoll = now;
        ensureSubscription(now);
        for (MonitorScope scope : scopes) {
            if (!running) return;
            try {
                var snapshot = source.read(scope);
                if (snapshot.isPresent()) service.accept(scope, snapshot.get()); else service.unavailable(scope);
            } catch (RuntimeException failure) {
                try { service.unavailable(scope); }
                catch (RuntimeException unavailable) { service.invalidate(); }
                log.warn("Zone monitor input/storage unavailable: {}", scope.key());
            }
        }
    }

    void subscriptionError() { service.invalidate(); onMessage(null, null); }

    void manageSubscription(RedisMessageListenerContainer subscription) { this.subscription = subscription; }

    private synchronized void ensureSubscription(long now) {
        if (!running || subscription == null || subscription.isRunning()) return;
        if (hasAttemptedSubscription && now >= lastSubscriptionAttempt && now - lastSubscriptionAttempt < 5_000) return;
        hasAttemptedSubscription = true; lastSubscriptionAttempt = now;
        try { subscription.start(); }
        catch (RuntimeException failure) {
            service.invalidate();
            try { subscription.stop(); } catch (RuntimeException ignored) { /* retry on the next bounded attempt */ }
            log.warn("Zone monitor subscription unavailable; periodic reads remain active");
        }
    }

    @Override public synchronized void stop() {
        running = false; service.stop(); hintQueued.set(false);
        if (worker != null) worker.shutdownNow();
        if (subscription != null) subscription.stop();
    }
    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return properties.isEnabled(); }
}
