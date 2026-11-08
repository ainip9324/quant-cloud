package com.quant.core.workspace;

import com.quant.common.market.LiveMarketSnapshot;
import com.quant.common.market.LiveMarketSnapshot.Status;
import com.quant.common.model.Kline;
import com.quant.core.feign.DataFeignClient;
import com.quant.core.monitor.*;
import com.quant.core.zone.MarketZone;
import com.quant.core.zone.MarketZoneService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** One read interface; private history caching never becomes a substitute for live Redis data. */
public class MarketWorkspaceService {
    private static final int HISTORY_LIMIT = 1000;
    private static final long SHORT_HISTORY_REFRESH_MILLIS = 60_000;
    private final ZoneMonitoringService monitor;
    private final MarketZoneService zones;
    private final RedisLiveMarketSource source;
    private final DataFeignClient history;
    private final Clock clock;
    private final long staleMillis;
    private final Set<MonitorScope> scopes;
    private final Map<MonitorScope, History> cache = new ConcurrentHashMap<>();
    private final String streamId = UUID.randomUUID().toString();
    private final AtomicLong version = new AtomicLong();

    public MarketWorkspaceService(ZoneMonitoringService monitor, MarketZoneService zones, RedisLiveMarketSource source,
            DataFeignClient history, MarketMonitorProperties properties) { this(monitor, zones, source, history, properties, Clock.systemUTC()); }
    MarketWorkspaceService(ZoneMonitoringService monitor, MarketZoneService zones, RedisLiveMarketSource source,
            DataFeignClient history, MarketMonitorProperties properties, Clock clock) {
        this.monitor = monitor; this.zones = zones; this.source = source; this.history = history; this.clock = clock;
        staleMillis = properties.getStaleMillis(); scopes = Set.copyOf(properties.scopes());
    }
    public MonitorScope scope(String marketType, String symbol, String interval) {
        try {
            MonitorScope scope = MonitorScope.of(marketType, symbol, interval);
            if (!scopes.contains(scope)) throw MarketWorkspaceException.invalid();
            return scope;
        } catch (MarketMonitorException exception) { throw MarketWorkspaceException.invalid(); }
    }
    public MarketWorkspaceSnapshot get(MonitorScope scope) {
        scope(scope.marketType().name(), scope.symbol(), scope.interval());
        long transportVersion = version.incrementAndGet();
        var observed = monitor.observations(scope.marketType().name(), scope.symbol(), scope.interval());
        List<MarketZone> definitions = new ArrayList<>(zones.list(scope.marketType().name(), scope.symbol(), scope.interval(), "ACTIVE", 100));
        definitions.addAll(zones.list(scope.marketType().name(), scope.symbol(), scope.interval(), "DISABLED", 100));
        definitions = definitions.stream().collect(Collectors.toMap(MarketZone::id, zone -> zone,
                (a, b) -> a.revision() >= b.revision() ? a : b)).values().stream().sorted(Comparator.comparingLong(MarketZone::id)).toList();
        var events = monitor.events(scope.marketType().name(), scope.symbol(), scope.interval(), null, 1, 20).records();
        LiveMarketSnapshot live = null;
        try { live = source.read(scope).filter(s -> LiveSnapshotPolicy.valid(scope, s, clock, staleMillis)).orElse(null); }
        catch (RuntimeException ignored) { /* Redis failures degrade only the live read model. */ }
        Status status = live == null || live.status() == null ? Status.DEGRADED
                : live.status() == Status.LIVE ? observed.status() : live.status();
        if (status == Status.LIVE && !Objects.equals(live.feedId(), observed.feedId())) status = Status.RECOVERING;
        long closed = live == null ? clock.millis() / scope.periodMillis() * scope.periodMillis() - scope.periodMillis() : live.lastClosedCandleTime();
        History bars = cache.computeIfAbsent(scope, ignored -> new History());
        List<Kline> candles;
        String historyStatus;
        synchronized (bars) {
            // READY describes the closed tail, not whether an older short response has grown.
            // The upstream limit may include one unclosed candle, so 999 closed bars is complete.
            boolean shortHistoryDue = bars.ready && bars.candles.size() < HISTORY_LIMIT - 1
                    && clock.millis() >= bars.refreshAt;
            if ((bars.target != closed || !bars.ready || shortHistoryDue) && clock.millis() >= bars.retryAt) {
                try {
                    TreeMap<Long, Kline> ordered = new TreeMap<>();
                    for (Kline bar : history.getKlines(scope.marketType(), scope.symbol(), scope.interval(), HISTORY_LIMIT)) {
                        if (!valid(bar)) throw new IllegalStateException("Invalid historical candle");
                        if (bar.getTime() <= closed) ordered.put(bar.getTime(), bar);
                    }
                    bars.candles = ordered.values().stream().skip(Math.max(0, ordered.size() - HISTORY_LIMIT)).toList();
                    bars.target = closed; bars.ready = !bars.candles.isEmpty() && bars.candles.get(bars.candles.size() - 1).getTime() == closed;
                    bars.refreshAt = clock.millis() + SHORT_HISTORY_REFRESH_MILLIS;
                } catch (RuntimeException ignored) { bars.ready = false; }
                bars.retryAt = bars.ready ? 0 : clock.millis() + 15_000;
            }
            candles = bars.candles;
            historyStatus = bars.ready && bars.target == closed ? "READY" : candles.isEmpty() ? "UNAVAILABLE" : "STALE";
        }
        if (!"READY".equals(historyStatus) && status == Status.LIVE) status = Status.DEGRADED;
        if (status == Status.LIVE && (!LiveSnapshotPolicy.valid(scope, live, clock, staleMillis) || observed.marketTime() == null
                || observed.receivedAt() == null || clock.millis() - observed.marketTime() > staleMillis
                || clock.millis() - observed.receivedAt() > staleMillis)) status = Status.DEGRADED;
        // Definitions may change between read transactions. A mismatched observation is never current.
        Map<Long, MarketMonitorView.Observation> states = observed.zones().stream().collect(Collectors.toMap(MarketMonitorView.Observation::zoneId, s -> s));
        List<MarketWorkspaceSnapshot.Zone> result = new ArrayList<>();
        for (MarketZone zone : definitions) {
            var state = states.get(zone.id());
            if (state != null && (status != Status.LIVE || state.zoneRevision() != zone.revision())) {
                state = new MarketMonitorView.Observation(state.zoneId(), zone.revision(), state.stateRevision(), state.state(), state.role(),
                        state.statePrice(), state.stateMarketTime(), state.recordedAt(), false);
            }
            result.add(MarketWorkspaceSnapshot.Zone.from(zone, state));
        }
        return new MarketWorkspaceSnapshot(streamId, transportVersion, clock.millis(), scope.marketType(), scope.symbol(), scope.interval(),
                status, MarketWorkspaceSnapshot.decimal(observed.lastPrice()), observed.marketTime(), observed.receivedAt(), historyStatus, candles,
                live == null ? null : MarketWorkspaceSnapshot.Candle.from(live.candle()), List.copyOf(result), List.copyOf(events));
    }
    private static boolean valid(Kline b) {
        return b != null && b.getTime() != null && b.getTime() > 0 && Double.isFinite(b.getOpen()) && Double.isFinite(b.getHigh())
                && Double.isFinite(b.getLow()) && Double.isFinite(b.getClose()) && Double.isFinite(b.getVolume())
                && b.getLow() > 0 && b.getLow() <= b.getOpen() && b.getLow() <= b.getClose()
                && b.getHigh() >= b.getOpen() && b.getHigh() >= b.getClose() && b.getVolume() >= 0;
    }
    private static final class History { long target; long retryAt; long refreshAt; boolean ready; List<Kline> candles = List.of(); }
}
