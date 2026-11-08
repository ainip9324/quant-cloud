package com.quant.data.live;

import com.quant.common.market.InvalidMarketQueryException;
import com.quant.common.market.LiveCandle;
import com.quant.common.market.LiveMarketSnapshot;
import com.quant.common.market.LiveMarketSnapshot.Status;
import com.quant.common.market.MarketCatalog;
import com.quant.common.market.MarketSelection;
import com.quant.common.market.MarketType;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.springframework.context.SmartLifecycle;

/** One bounded worker owns the subscription, recovery and publication state. */
@Slf4j
public class BitgetLiveMarketFeed implements LiveMarketFeed, SmartLifecycle {
    private final LiveMarketProperties properties;
    private final OkHttpClient client;
    private final LiveMarketRecorder recorder;
    private final Clock clock;
    private final BitgetWebSocketProtocol protocol = new BitgetWebSocketProtocol();
    private final Map<String, Stream> streams = new LinkedHashMap<>();
    private final Map<String, LiveMarketSnapshot> views = new ConcurrentHashMap<>();
    private final AtomicBoolean overloaded = new AtomicBoolean();
    private final boolean ownsExecutors;
    private Executor worker;
    private ScheduledExecutorService scheduler;
    private volatile boolean running;
    private volatile boolean connected;
    private volatile long generation;
    private volatile WebSocket socket;
    private boolean connecting;
    private volatile boolean hasDisconnected;
    private boolean waitingForPong;
    private long connectedAttemptAt;
    private long lastPingAt;
    private long nextConnectAt;
    private long reconnectDelay;
    private String feedId;
    private long sequence;

    public BitgetLiveMarketFeed(LiveMarketProperties properties, OkHttpClient client, LiveMarketRecorder recorder) {
        this(properties, client, recorder, Clock.systemUTC(), null, null);
    }

    BitgetLiveMarketFeed(LiveMarketProperties properties, OkHttpClient client, LiveMarketRecorder recorder,
                         Clock clock, Executor worker, ScheduledExecutorService scheduler) {
        this.properties = properties;
        this.client = client;
        this.recorder = recorder;
        this.clock = clock;
        this.worker = worker;
        this.scheduler = scheduler;
        this.ownsExecutors = worker == null;
    }

    @Override public synchronized void start() {
        if (running || !properties.isEnabled()) return;
        List<MarketSelection> selections = properties.selections();
        if (ownsExecutors) {
            worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256),
                    task -> daemon(task, "live-market-worker"));
            scheduler = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "live-market-clock"));
        }
        streams.clear();
        views.clear();
        feedId = UUID.randomUUID().toString();
        sequence = 0;
        reconnectDelay = properties.getReconnectMinMillis();
        connected = false;
        connecting = false;
        hasDisconnected = false;
        overloaded.set(false);
        selections.forEach(selection -> streams.put(key(selection.symbol(), selection.interval()), new Stream(selection)));
        running = true;
        generation++;
        // Readers can see CONNECTING without a DB/Redis round trip.
        streams.forEach((key, state) -> views.put(key, snapshot(state, Status.CONNECTING)));
        submit(generation, this::connect);
        scheduler.scheduleWithFixedDelay(() -> submit(generation, this::maintain), 1, 1, TimeUnit.SECONDS);
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private void submit(long epoch, Runnable action) {
        if (!running) return;
        try {
            worker.execute(() -> {
                if (running && epoch == generation) action.run();
            });
        } catch (RejectedExecutionException exception) {
            overloaded.set(true);
        }
    }

    private void connect() {
        if (!running) return;
        long epoch = ++generation;
        connecting = true;
        connectedAttemptAt = clock.millis();
        try {
            WebSocket candidate = client.newWebSocket(new Request.Builder().url(properties.getWebsocketUrl()).build(), new WebSocketListener() {
                @Override public void onOpen(WebSocket webSocket, Response response) {
                    submit(epoch, () -> opened(webSocket));
                }
                @Override public void onMessage(WebSocket webSocket, String text) {
                    submit(epoch, () -> message(text));
                }
                @Override public void onFailure(WebSocket webSocket, Throwable failure, Response response) {
                    submit(epoch, () -> disconnect("transport failure"));
                }
                @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                    submit(epoch, () -> disconnect("remote close"));
                }
                @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                    submit(epoch, () -> disconnect("remote closed"));
                }
            });
            synchronized (this) {
                if (!running || epoch != generation) candidate.cancel();
                else socket = candidate;
            }
        } catch (RuntimeException exception) {
            disconnect("connect failure");
        }
    }

    private void opened(WebSocket webSocket) {
        // A new transport connection is a new continuity boundary, even if notices were missed.
        feedId = UUID.randomUUID().toString();
        sequence = 0;
        socket = webSocket;
        connecting = false;
        waitingForPong = false;
        lastPingAt = clock.millis();
        streams.values().forEach(state -> {
            state.priceSeen = false;
            state.candleSeen = false;
            state.needsRecovery = true;
            state.recoveryFailed = false;
            state.retryAt = 0;
            String key = key(state.selection.symbol(), state.selection.interval());
            views.computeIfPresent(key, (ignored, previous) -> previous.withStatus(Status.RECOVERING));
        });
        connected = true;
        if (!webSocket.send(protocol.subscribe(streams.values().stream().map(state -> state.selection).toList()))) {
            disconnect("subscription send failed");
            return;
        }
        streams.values().forEach(this::publish);
    }

    private void message(String text) {
        if (!connected) return;
        if ("pong".equals(text)) {
            waitingForPong = false;
            return;
        }
        try {
            List<BitgetWebSocketProtocol.Update> updates = protocol.parse(text);
            long now = clock.millis();
            for (BitgetWebSocketProtocol.Update update : updates) {
                if (update.marketTime() > now + properties.getStaleMillis()) {
                    throw new IllegalArgumentException("Future market timestamp");
                }
                if (update instanceof BitgetWebSocketProtocol.Ticker ticker) {
                    for (Stream state : streams.values()) {
                        if (!state.selection.symbol().equals(ticker.symbol())
                                || (state.priceTime != null && ticker.marketTime() < state.priceTime)) continue;
                        if (state.priceSeen && Long.valueOf(ticker.marketTime()).equals(state.priceTime)
                                && ticker.price().equals(state.price)) continue;
                        state.priceSeen = true;
                        state.price = ticker.price();
                        state.priceTime = ticker.marketTime();
                        state.priceReceivedAt = now;
                        publish(state);
                    }
                } else if (update instanceof BitgetWebSocketProtocol.Candle candle) {
                    Stream state = streams.get(key(candle.symbol(), candle.interval()));
                    if (state == null || (state.candle != null && (candle.bar().time() < state.candle.time()
                            || candle.marketTime() < state.candleMarketTime))) continue;
                    if (candle.bar().time() > now + properties.getStaleMillis()
                            || candle.bar().time() > candle.marketTime()) {
                        throw new IllegalArgumentException("Future candle timestamp");
                    }
                    if (state.candleSeen && candle.bar().equals(state.candle)
                            && Long.valueOf(candle.marketTime()).equals(state.candleMarketTime)) continue;
                    if (state.candle == null || candle.bar().time() > state.candle.time()) {
                        state.needsRecovery = true;
                        state.retryAt = 0;
                    }
                    state.candleSeen = true;
                    state.candle = candle.bar();
                    state.candleMarketTime = candle.marketTime();
                    state.candleReceivedAt = now;
                    if (state.needsRecovery) publish(state);
                    recover(state);
                    publish(state);
                }
            }
        } catch (RuntimeException exception) {
            // Never log exchange frames, URLs with credentials, or arbitrary upstream error text.
            disconnect("invalid market frame");
        }
    }

    private void recover(Stream state) {
        if (!state.needsRecovery || !state.candleSeen || clock.millis() < state.retryAt) return;
        try {
            state.lastClosed = recorder.recover(state.selection, state.candle.time(), state.lastClosed);
            state.needsRecovery = false;
            state.recoveryFailed = false;
        } catch (RuntimeException exception) {
            state.recoveryFailed = true;
            state.retryAt = clock.millis() + properties.getRecoveryRetryMillis();
            log.warn("Live recovery incomplete: {} {}", state.selection.symbol(), state.selection.interval());
        }
    }

    // Called by the scheduler on the worker; package visibility also allows deterministic clock tests.
    void maintain() {
        if (!running) return;
        long now = clock.millis();
        if (overloaded.getAndSet(false)) {
            disconnect("worker queue full");
            return;
        }
        if (!connected) {
            if (connecting && now - connectedAttemptAt >= 10_000) disconnect("connect timeout");
            else if (!connecting && now >= nextConnectAt) connect();
            return;
        }
        if (waitingForPong && now - lastPingAt >= properties.getPongTimeoutMillis()) {
            disconnect("pong timeout");
            return;
        }
        if (!waitingForPong && now - lastPingAt >= properties.getHeartbeatMillis()) {
            if (!socket.send("ping")) {
                disconnect("ping send failed");
                return;
            }
            waitingForPong = true;
            lastPingAt = now;
        }
        for (Stream state : streams.values()) {
            recover(state);
            LiveMarketSnapshot previous = views.get(key(state.selection.symbol(), state.selection.interval()));
            if (state.outputFailed || previous == null || previous.status() != status(state)) publish(state);
        }
    }

    private void disconnect(String reason) {
        connected = false;
        connecting = false;
        hasDisconnected = true;
        generation++;
        WebSocket previous = socket;
        socket = null;
        if (previous != null) previous.cancel();
        nextConnectAt = clock.millis() + reconnectDelay;
        reconnectDelay = Math.min(properties.getReconnectMaxMillis(), reconnectDelay * 2);
        log.warn("Live feed reconnecting: {}", reason);
        streams.values().forEach(state -> { state.needsRecovery = true; publish(state); });
    }

    private void publish(Stream state) {
        LiveMarketSnapshot next = snapshot(state, status(state));
        views.put(key(state.selection.symbol(), state.selection.interval()), next);
        try {
            recorder.publish(next);
            state.outputFailed = false;
            if (next.status() == Status.LIVE) reconnectDelay = properties.getReconnectMinMillis();
        } catch (RuntimeException exception) {
            state.outputFailed = true;
            views.put(key(state.selection.symbol(), state.selection.interval()), next.withStatus(status(state)));
            log.warn("Live snapshot output unavailable: {} {}", state.selection.symbol(), state.selection.interval());
        }
    }

    private Status status(Stream state) {
        if (!running) return Status.STOPPED;
        if (!connected) return hasDisconnected ? Status.RECONNECTING : Status.CONNECTING;
        if (state.recoveryFailed || state.outputFailed) return Status.DEGRADED;
        if (state.needsRecovery || !state.priceSeen || !state.candleSeen) return Status.RECOVERING;
        return state.candle.time() + LiveMarketProperties.periodMillis(state.selection.interval())
                >= clock.millis() - properties.getStaleMillis()
                && fresh(state.priceTime, state.priceReceivedAt, properties.getStaleMillis())
                && fresh(state.candleMarketTime, state.candleReceivedAt,
                LiveMarketProperties.periodMillis(state.selection.interval()) + properties.getStaleMillis())
                ? Status.LIVE : Status.DEGRADED;
    }

    private boolean fresh(Long marketTime, Long receivedAt, long maxAge) {
        long now = clock.millis();
        return marketTime != null && receivedAt != null && now - marketTime <= maxAge && now - receivedAt <= maxAge;
    }

    private LiveMarketSnapshot snapshot(Stream state, Status status) {
        return new LiveMarketSnapshot(feedId, ++sequence, state.selection.marketType(), state.selection.symbol(),
                state.selection.interval(), status, state.price, state.priceTime, state.priceReceivedAt,
                state.candle, state.candleMarketTime, state.candleReceivedAt, state.lastClosed, clock.millis());
    }

    @Override public Optional<LiveMarketSnapshot> currentSnapshot(String marketType, String symbol, String interval) {
        MarketSelection selection = MarketCatalog.requireSelection(marketType, symbol, interval);
        if (selection.marketType() != MarketType.USDT_FUTURES) throw new InvalidMarketQueryException("Live market requires USDT_FUTURES");
        LiveMarketSnapshot snapshot = views.get(key(symbol, interval));
        if (snapshot == null) return Optional.empty();
        Status status = snapshot.status();
        if (!running) status = Status.STOPPED;
        else if (!connected) status = hasDisconnected ? Status.RECONNECTING : Status.CONNECTING;
        else if (status == Status.LIVE && (snapshot.candle() == null
                || snapshot.candle().time() + LiveMarketProperties.periodMillis(interval) < clock.millis() - properties.getStaleMillis()
                || !fresh(snapshot.priceTime(), snapshot.priceReceivedAt(), properties.getStaleMillis())
                || !fresh(snapshot.candleMarketTime(), snapshot.candleReceivedAt(),
                LiveMarketProperties.periodMillis(interval) + properties.getStaleMillis()))) status = Status.DEGRADED;
        return Optional.of(snapshot.withStatus(status));
    }

    @Override public synchronized void stop() {
        running = false;
        connected = false;
        generation++;
        if (socket != null) socket.cancel();
        if (ownsExecutors && scheduler != null) scheduler.shutdownNow();
        if (ownsExecutors && worker instanceof ExecutorService executor) executor.shutdownNow();
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return properties.isEnabled(); }
    private static String key(String symbol, String interval) { return symbol + ":" + interval; }

    private static final class Stream {
        final MarketSelection selection;
        java.math.BigDecimal price;
        Long priceTime;
        Long priceReceivedAt;
        LiveCandle candle;
        Long candleMarketTime;
        Long candleReceivedAt;
        Long lastClosed;
        boolean priceSeen;
        boolean candleSeen;
        boolean needsRecovery = true;
        boolean recoveryFailed;
        boolean outputFailed;
        long retryAt;
        Stream(MarketSelection selection) { this.selection = selection; }
    }
}
