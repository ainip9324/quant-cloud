package com.quant.core.workspace;

import com.quant.common.auth.AuthenticatedPrincipal;
import com.quant.common.auth.JwtV2Verifier;
import com.quant.core.monitor.MonitorScope;
import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Bounded connections and writes; slow clients never accumulate frame queues. */
public class MarketWorkspaceStreams implements AutoCloseable {
    private final MarketWorkspaceService workspace;
    private final JwtV2Verifier verifier;
    private final Clock clock;
    private final Executor sends;
    private final Supplier<SseEmitter> emitters;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private ScheduledExecutorService timer;
    private volatile boolean closed;

    public MarketWorkspaceStreams(MarketWorkspaceService workspace, JwtV2Verifier verifier) {
        this(workspace, verifier, Clock.systemUTC(), new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64), task -> daemon(task, "workspace-send")), () -> new SseEmitter(65_000L));
    }
    MarketWorkspaceStreams(MarketWorkspaceService workspace, JwtV2Verifier verifier, Clock clock, Executor sends, Supplier<SseEmitter> emitters) {
        this.workspace = workspace; this.verifier = verifier; this.clock = clock; this.sends = sends; this.emitters = emitters;
    }
    public synchronized void start() {
        if (closed || timer != null) return;
        timer = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "workspace-sample"));
        timer.scheduleWithFixedDelay(this::tick, 0, 2_000, TimeUnit.MILLISECONDS);
    }
    public synchronized SseEmitter open(MonitorScope scope, String token, AuthenticatedPrincipal principal) {
        if (!authorized(token, principal)) throw new MarketWorkspaceException(401, "UNAUTHORIZED", "Authentication required or token expired");
        if (closed) throw new MarketWorkspaceException(503, "MARKET_WORKSPACE_UNAVAILABLE", "Market workspace is unavailable");
        if (clients.size() >= 64 || clients.values().stream().filter(c -> c.principal.userId() == principal.userId()).count() >= 4) {
            throw new MarketWorkspaceException(429, "MARKET_WORKSPACE_STREAM_LIMIT", "Too many workspace connections");
        }
        Client client = new Client(scope, token, principal, emitters.get(), clock.millis());
        clients.put(client.id, client);
        // Servlet callbacks can hold the response lock while send holds the emitter lock.
        client.emitter.onCompletion(() -> unregister(client));
        client.emitter.onTimeout(() -> unregister(client));
        client.emitter.onError(error -> unregister(client));
        return client.emitter;
    }
    void tick() {
        if (closed) return;
        Map<MonitorScope, Object> frames = new HashMap<>();
        for (Client client : clients.values()) {
            if (expired(client) || client.sending.get() && clock.millis() - client.sendStarted > 10_000) { finish(client); continue; }
            if (!client.sending.compareAndSet(false, true)) continue;
            client.sendStarted = clock.millis();
            Object frame = frames.computeIfAbsent(client.scope, scope -> {
                try { return workspace.get(scope); }
                catch (RuntimeException failure) { return Map.of("code", "MARKET_WORKSPACE_UNAVAILABLE"); }
            });
            try { sends.execute(() -> send(client, frame)); }
            catch (RuntimeException full) { client.sending.set(false); finish(client); }
        }
    }
    private void send(Client client, Object frame) {
        try {
            if (!clients.containsKey(client.id)) return;
            if (expired(client)) { finish(client); return; }
            client.emitter.send(SseEmitter.event().name(frame instanceof MarketWorkspaceSnapshot ? "workspace" : "unavailable")
                    .data(frame, MediaType.APPLICATION_JSON));
        } catch (IOException disconnected) {
            // Spring MVC completes the async response when the transport write fails.
            unregister(client);
        } catch (RuntimeException failure) {
            finish(client);
        } finally {
            client.sending.set(false);
        }
    }
    private boolean expired(Client client) { return clock.millis() - client.openedAt >= 60_000 || !authorized(client.token, client.principal); }
    private boolean authorized(String token, AuthenticatedPrincipal principal) {
        try { return principal != null && principal.equals(verifier.verify(token)); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private boolean unregister(Client client) { return clients.remove(client.id, client); }
    private void finish(Client client) { if (unregister(client)) client.emitter.complete(); }
    @Override public synchronized void close() {
        closed = true;
        if (timer != null) timer.shutdownNow();
        clients.values().forEach(this::finish);
        if (sends instanceof ExecutorService owned) owned.shutdownNow();
    }
    private static Thread daemon(Runnable task, String name) { Thread t = new Thread(task, name); t.setDaemon(true); return t; }
    private static final class Client {
        final String id = UUID.randomUUID().toString(); final MonitorScope scope; final String token;
        final AuthenticatedPrincipal principal; final SseEmitter emitter; final long openedAt;
        final AtomicBoolean sending = new AtomicBoolean(); volatile long sendStarted;
        Client(MonitorScope scope, String token, AuthenticatedPrincipal principal, SseEmitter emitter, long openedAt) {
            this.scope = scope; this.token = token; this.principal = principal; this.emitter = emitter; this.openedAt = openedAt;
        }
    }
}
