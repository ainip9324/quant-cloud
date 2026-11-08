package com.quant.core.monitor;

import com.quant.common.market.LiveMarketSnapshot;
import com.quant.common.market.LiveMarketSnapshot.Status;
import com.quant.core.mapper.MarketMonitorMapper;
import com.quant.core.mapper.MarketZoneMapper;
import com.quant.core.zone.MarketZoneRow;
import com.quant.core.zone.ZoneLifecycle;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns baseline, watermark and event consistency behind one acceptance operation. */
public class ZoneMonitoringService {
    private final MarketZoneMapper zones;
    private final MarketMonitorMapper mapper;
    private final MarketMonitorProperties properties;
    private final Clock clock;
    private final TransactionTemplate writes;
    private final Set<String> configured;
    private final Set<String> ready = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;

    public ZoneMonitoringService(MarketZoneMapper zones, MarketMonitorMapper mapper, PlatformTransactionManager transactions,
                                  MarketMonitorProperties properties) {
        this(zones, mapper, transactions, properties, Clock.systemUTC());
    }

    ZoneMonitoringService(MarketZoneMapper zones, MarketMonitorMapper mapper, PlatformTransactionManager transactions,
                          MarketMonitorProperties properties, Clock clock) {
        this.zones = zones; this.mapper = mapper; this.properties = properties; this.clock = clock;
        configured = properties.scopes().stream().map(MonitorScope::key).collect(Collectors.toUnmodifiableSet());
        writes = new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(10);
    }

    public void accept(MonitorScope scope, LiveMarketSnapshot snapshot) {
        requireConfigured(scope);
        if (snapshot == null || snapshot.marketType() != scope.marketType() || !scope.symbol().equals(snapshot.symbol())
                || !scope.interval().equals(snapshot.interval())) {
            unavailable(scope); return;
        }
        if (snapshot.status() != Status.LIVE) {
            unavailable(scope, snapshot.status() == null ? Status.DEGRADED : snapshot.status()); return;
        }
        if (!LiveSnapshotPolicy.valid(scope, snapshot, clock, properties.getStaleMillis())) { unavailable(scope); return; }
        boolean baseline = !ready.contains(scope.key());
        try {
            boolean accepted = Boolean.TRUE.equals(writes.execute(transaction -> process(scope, snapshot, baseline)));
            // execute returns only after commit, including when callers already have a transaction.
            if (accepted) ready.add(scope.key()); else ready.remove(scope.key());
        } catch (RuntimeException exception) {
            ready.remove(scope.key());
            throw exception;
        }
    }

    private boolean process(MonitorScope scope, LiveMarketSnapshot snapshot, boolean baseline) {
        lockScope(scope);
        MonitorCursor previous = mapper.selectCursorForUpdate(scope);
        if (previous != null && previous.getPriceTime() != null) {
            if (snapshot.priceTime() < previous.getPriceTime()) return false;
            if (Objects.equals(previous.getFeedId(), snapshot.feedId()) && previous.getSourceSequence() != null) {
                if (snapshot.sequence() < previous.getSourceSequence()) return false;
                if (snapshot.sequence() == previous.getSourceSequence()
                        && (snapshot.priceTime().longValue() != previous.getPriceTime()
                        || snapshot.lastPrice().compareTo(previous.getLastPrice()) != 0)) {
                    degradeLocked(scope, previous, Status.DEGRADED); return false;
                }
            }
        }
        boolean newPriceTime = previous == null || previous.getPriceTime() == null || snapshot.priceTime() > previous.getPriceTime();
        boolean correction = previous != null && previous.getPriceTime() != null
                && snapshot.priceTime().equals(previous.getPriceTime()) && snapshot.lastPrice().compareTo(previous.getLastPrice()) != 0;
        baseline |= previous == null || previous.isBaselineRequired() || previous.getStatus() != Status.LIVE
                || !Objects.equals(previous.getFeedId(), snapshot.feedId()) || correction
                || previous.getPriceTime() == null || snapshot.priceTime() - previous.getPriceTime() > properties.getMaxGapMillis();

        List<MarketZoneRow> definitions = zones.selectReservedForUpdate(scope.marketType(), scope.symbol(), scope.interval());
        Map<Long, ZoneObservationRow> observed = new HashMap<>();
        mapper.selectObservations(scope).forEach(row -> observed.put(row.getZoneId(), row));
        long now = clock.millis();
        for (MarketZoneRow zone : definitions) {
            if (zone.getLifecycle() != ZoneLifecycle.ACTIVE || snapshot.priceTime() < zone.getEffectiveFrom()) continue;
            ZoneObservationRow old = observed.get(zone.getId());
            boolean initialize = baseline || old == null || !Objects.equals(old.getZoneRevision(), zone.getRevision());
            ObservationState next = ObservationState.at(snapshot.lastPrice(), zone.getLowerPrice(), zone.getUpperPrice());
            if (!initialize && (!newPriceTime || old.getState() == next)) continue;
            if (!initialize) {
                ZoneEvent event = event(scope, zone, old.getState(), next, snapshot, now);
                if (mapper.insertEvent(event) != 1) throw new IllegalStateException("State event was not persisted");
            }
            ZoneObservationRow row = new ZoneObservationRow();
            row.setZoneId(zone.getId()); row.setZoneRevision(zone.getRevision()); row.setState(next);
            row.setStatePrice(snapshot.lastPrice()); row.setStateMarketTime(snapshot.priceTime());
            row.setFeedId(snapshot.feedId()); row.setSourceSequence(snapshot.sequence()); row.setRecordedAt(now);
            mapper.saveObservation(row);
        }
        MonitorCursor cursor = copy(scope, previous);
        cursor.setFeedId(snapshot.feedId()); cursor.setSourceSequence(snapshot.sequence());
        cursor.setPriceTime(snapshot.priceTime()); cursor.setPriceReceivedAt(snapshot.priceReceivedAt());
        cursor.setSourceEmittedAt(snapshot.emittedAt()); cursor.setLastPrice(snapshot.lastPrice());
        cursor.setStatus(Status.LIVE); cursor.setBaselineRequired(false); cursor.setRecordedAt(now);
        mapper.saveCursor(cursor);
        return true;
    }

    public void unavailable(MonitorScope scope) { unavailable(scope, Status.DEGRADED); }

    private void unavailable(MonitorScope scope, Status status) {
        requireConfigured(scope);
        ready.remove(scope.key());
        writes.executeWithoutResult(transaction -> {
            lockScope(scope);
            degradeLocked(scope, mapper.selectCursorForUpdate(scope), status);
        });
    }

    private void degradeLocked(MonitorScope scope, MonitorCursor previous, Status status) {
        if (previous != null && previous.getStatus() == status && previous.isBaselineRequired()) return;
        MonitorCursor cursor = copy(scope, previous);
        cursor.setStatus(status); cursor.setBaselineRequired(true); cursor.setRecordedAt(clock.millis());
        mapper.saveCursor(cursor);
    }

    private void lockScope(MonitorScope scope) {
        zones.ensureScope(scope.marketType(), scope.symbol(), scope.interval());
        if (zones.lockScope(scope.marketType(), scope.symbol(), scope.interval()) != scope.marketType()) {
            throw new IllegalStateException("Monitor scope lock is unavailable");
        }
    }

    @Transactional(readOnly = true)
    public MarketMonitorView observations(String marketType, String symbol, String interval) {
        MonitorScope scope = MonitorScope.of(marketType, symbol, interval);
        requireConfigured(scope);
        MonitorCursor cursor = mapper.selectCursor(scope);
        Status status = currentStatus(scope, cursor);
        Map<Long, ZoneObservationRow> observed = new HashMap<>();
        mapper.selectObservations(scope).forEach(row -> observed.put(row.getZoneId(), row));
        List<MarketMonitorView.Observation> result = zones.selectListed(scope.marketType(), scope.symbol(), scope.interval(), ZoneLifecycle.ACTIVE, 100)
                .stream().map(zone -> view(zone, observed.get(zone.getId()), cursor, status)).toList();
        return new MarketMonitorView(scope.marketType(), scope.symbol(), scope.interval(), status,
                cursor == null ? null : cursor.getLastPrice(), cursor == null ? null : cursor.getPriceTime(),
                cursor == null ? null : cursor.getPriceReceivedAt(), cursor == null ? null : cursor.getFeedId(),
                cursor == null ? null : cursor.getSourceSequence(), result);
    }

    @Transactional(readOnly = true)
    public ZoneEventPage events(String marketType, String symbol, String interval, Long zoneId, int page, int size) {
        MonitorScope scope = MonitorScope.of(marketType, symbol, interval);
        requireConfigured(scope);
        if (zoneId != null && zoneId <= 0 || page < 1 || page > 1000 || size < 1 || size > 200) {
            throw MarketMonitorException.invalid("Invalid zoneId, page or size");
        }
        long total = mapper.countEvents(scope, zoneId);
        return new ZoneEventPage(List.copyOf(mapper.selectEvents(scope, zoneId, size, (page - 1) * size)), total, page, size);
    }

    private MarketMonitorView.Observation view(MarketZoneRow zone, ZoneObservationRow row, MonitorCursor cursor, Status status) {
        boolean current = status == Status.LIVE && row != null && Objects.equals(row.getZoneRevision(), zone.getRevision())
                && cursor != null && cursor.getPriceTime() >= zone.getEffectiveFrom();
        return new MarketMonitorView.Observation(zone.getId(), zone.getRevision(), row == null ? null : row.getZoneRevision(),
                row == null ? null : row.getState(), row == null ? null : row.getState().role(), row == null ? null : row.getStatePrice(),
                row == null ? null : row.getStateMarketTime(), row == null ? null : row.getRecordedAt(), current);
    }

    private Status currentStatus(MonitorScope scope, MonitorCursor cursor) {
        if (stopped) return Status.STOPPED;
        if (cursor == null) return Status.RECOVERING;
        if (cursor.getStatus() != Status.LIVE) return cursor.getStatus();
        if (!fresh(cursor.getPriceTime(), properties.getStaleMillis())
                || !fresh(cursor.getPriceReceivedAt(), properties.getStaleMillis())
                || !fresh(cursor.getSourceEmittedAt(), properties.getStaleMillis())) return Status.DEGRADED;
        return !cursor.isBaselineRequired() && ready.contains(scope.key()) ? Status.LIVE : Status.RECOVERING;
    }

    private boolean fresh(Long time, long age) {
        return time != null && time > 0 && time <= clock.millis() + properties.getStaleMillis() && clock.millis() - time <= age;
    }

    private void requireConfigured(MonitorScope scope) {
        if (!configured.contains(scope.key())) throw new MarketMonitorException(503, "MARKET_MONITOR_UNAVAILABLE", "Market scope is not monitored");
    }

    public void restart() { ready.clear(); stopped = false; }
    public void stop() { stopped = true; ready.clear(); }
    public void invalidate() { ready.clear(); }

    private MonitorCursor copy(MonitorScope scope, MonitorCursor old) {
        MonitorCursor row = new MonitorCursor();
        row.setMarketType(scope.marketType()); row.setSymbol(scope.symbol()); row.setAnalysisInterval(scope.interval());
        row.setSourceSequence(old == null ? 0L : old.getSourceSequence());
        if (old != null) {
            row.setFeedId(old.getFeedId()); row.setLastPrice(old.getLastPrice()); row.setPriceTime(old.getPriceTime());
            row.setPriceReceivedAt(old.getPriceReceivedAt()); row.setSourceEmittedAt(old.getSourceEmittedAt());
        }
        return row;
    }

    private ZoneEvent event(MonitorScope scope, MarketZoneRow zone, ObservationState from, ObservationState to,
                            LiveMarketSnapshot snapshot, long now) {
        ZoneEvent row = new ZoneEvent();
        row.setZoneId(zone.getId()); row.setZoneRevision(zone.getRevision()); row.setMarketType(scope.marketType());
        row.setSymbol(scope.symbol()); row.setAnalysisInterval(scope.interval()); row.setFromState(from); row.setToState(to);
        row.setEventCode("ZONE_STATE_CHANGED"); row.setObservedPrice(snapshot.lastPrice()); row.setMarketTime(snapshot.priceTime());
        row.setRecordedAt(now); row.setFeedId(snapshot.feedId()); row.setSourceSequence(snapshot.sequence());
        return row;
    }
}
