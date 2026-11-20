package com.quant.core.androidtrial;

import com.quant.common.auth.AuthenticatedPrincipal;
import com.quant.common.auth.JwtV2Verifier;
import com.quant.core.config.AuthProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Bounded active streams, each with at most one outstanding write task. */
@Service
@ConditionalOnProperty(name = "android-synthetic.enabled", havingValue = "true")
public class ZoneAlertAndroidStreams implements AutoCloseable {
    private static final long SESSION_MS = 60_000;
    private final AndroidSyntheticEvents events;
    private final JwtV2Verifier verifier;
    private final Clock clock;
    private final Executor sends;
    private final Supplier<SseEmitter> emitters;
    private final int maxConnections;
    private final Map<Long, Client> active = new ConcurrentHashMap<>();
    private ScheduledExecutorService timer;
    private volatile boolean closed;

    @Autowired
    ZoneAlertAndroidStreams(AndroidSyntheticEvents events, AuthProperties auth,
                            @Value("${android-synthetic.max-connections:2}") int maxConnections) {
        this(events, auth.verifier(), Clock.systemUTC(), maxConnections, sendPool(maxConnections),
                () -> new SseEmitter(SESSION_MS + 5000));
    }

    ZoneAlertAndroidStreams(AndroidSyntheticEvents events, JwtV2Verifier verifier, Clock clock,
                            int maxConnections, Executor sends, Supplier<SseEmitter> emitters) {
        this.events = events;
        this.verifier = verifier;
        this.clock = clock;
        this.maxConnections = maxConnections;
        this.sends = sends;
        this.emitters = emitters;
    }

    private static Executor sendPool(int maxConnections) {
        if (maxConnections < 1 || maxConnections > 8) {
            throw new IllegalArgumentException("Android stream connection limit must be between 1 and 8");
        }
        return new ThreadPoolExecutor(maxConnections, maxConnections, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxConnections), task -> daemon(task, "android-alert-send"));
    }

    @PostConstruct
    synchronized void start() {
        if (closed || timer != null) return;
        timer = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "android-alert-tick"));
        timer.scheduleWithFixedDelay(this::tick, 0, 2000, TimeUnit.MILLISECONDS);
    }

    public synchronized SseEmitter open(String token, AuthenticatedPrincipal principal) {
        if (!authorized(token, principal)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!events.allows(principal.userId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (closed || active.containsKey(principal.userId()) || active.size() >= maxConnections) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }

        Client client = new Client(token, principal, emitters.get(), clock.millis());
        client.emitter.onCompletion(() -> unregister(client));
        client.emitter.onTimeout(() -> unregister(client));
        client.emitter.onError(error -> unregister(client));
        try {
            // Before MVC attaches its transport, this becomes the first buffered item.
            client.emitter.send(SseEmitter.event().comment("ready"));
        } catch (IOException fault) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        active.put(principal.userId(), client);
        return client.emitter;
    }

    void tick() {
        if (closed) return;
        active.values().forEach(this::scheduleSend);
    }

    private void scheduleSend(Client client) {
        if (!client.sending.compareAndSet(false, true)) return;
        try {
            sends.execute(() -> send(client));
        } catch (RejectedExecutionException busy) {
            client.sending.set(false);
            finish(client);
        }
    }

    private void send(Client client) {
        try {
            if (!registered(client)) return;
            if (closed || expired(client)) { finish(client); return; }
            client.emitter.send(SseEmitter.event().comment("heartbeat"));
            long now = clock.millis();
            client.sent.values().removeIf(expiresAt -> expiresAt <= now);
            var frame = events.next(client.principal.userId(), client.sent.keySet());
            if (frame == null) return;
            if (expired(client)) { finish(client); return; }
            if (frame.expiresAt() <= clock.millis() || closed || !registered(client)) return;
            client.emitter.send(SseEmitter.event().data(frame, MediaType.APPLICATION_JSON));
            client.sent.put(Long.valueOf(frame.id()), frame.expiresAt());
        } catch (IOException disconnected) {
            // The servlet container already owns cleanup after the write fails.
            unregister(client);
        } catch (RuntimeException unavailable) {
            finish(client);
        } finally {
            client.sending.set(false);
            if (closed) finish(client);
        }
    }

    private boolean expired(Client client) {
        return clock.millis() - client.openedAt >= SESSION_MS || !authorized(client.token, client.principal);
    }

    private boolean authorized(String token, AuthenticatedPrincipal principal) {
        try { return principal != null && principal.equals(verifier.verify(token)); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private boolean registered(Client client) { return active.get(client.principal.userId()) == client; }

    private boolean unregister(Client client) { return active.remove(client.principal.userId(), client); }

    private void finish(Client client) { if (unregister(client)) client.emitter.complete(); }

    @PreDestroy
    @Override public synchronized void close() {
        closed = true;
        if (timer != null) timer.shutdownNow();
        // Do not contend for the emitter lock with a blocking servlet write.
        for (Client client : active.values()) {
            if (client.sending.compareAndSet(false, true)) finish(client);
        }
        if (sends instanceof ExecutorService owned) owned.shutdown();
    }

    private static Thread daemon(Runnable task, String name) {
        var thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static final class Client {
        final String token;
        final AuthenticatedPrincipal principal;
        final SseEmitter emitter;
        final long openedAt;
        final AtomicBoolean sending = new AtomicBoolean();
        final Map<Long, Long> sent = new HashMap<>();

        Client(String token, AuthenticatedPrincipal principal, SseEmitter emitter, long openedAt) {
            this.token = token;
            this.principal = principal;
            this.emitter = emitter;
            this.openedAt = openedAt;
        }
    }
}
