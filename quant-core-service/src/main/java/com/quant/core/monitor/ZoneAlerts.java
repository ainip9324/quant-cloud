package com.quant.core.monitor;

import com.quant.common.market.LiveMarketSnapshot.Status;
import com.quant.common.model.Kline;
import com.quant.core.feign.DataFeignClient;
import com.quant.core.mapper.MarketZoneMapper;
import com.quant.core.strategy.ChartZoneSignals;
import com.quant.core.strategy.TradeSignal;
import com.quant.core.zone.ChartObjectHistory;
import com.quant.core.zone.MarketZoneRow;
import com.quant.core.zone.ZoneLifecycle;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(name = "zone-alerts.enabled", havingValue = "true")
public class ZoneAlerts {
    private static final long MAX_ALERT_AGE = 120_000;
    private final JdbcTemplate db;
    private final MarketZoneMapper zones;
    private final RedisLiveMarketSource live;
    private final DataFeignClient history;
    private final MarketMonitorProperties monitor;
    private final ZoneAlertMail mail;
    private final Clock clock = Clock.systemUTC();
    // Only the confirmation worker accesses this watermark. The SQL unique key covers replica overlap.
    private final Map<MonitorScope, Long> checked = new HashMap<>();

    public ZoneAlerts(JdbcTemplate db, MarketZoneMapper zones, RedisLiveMarketSource live, DataFeignClient history,
                      MarketMonitorProperties monitor, ZoneAlertMail mail) {
        this.db = db;
        this.zones = zones;
        this.live = live;
        this.history = history;
        this.monitor = monitor;
        this.mail = mail;
    }

    public record Notice(long id, long zoneId, String symbol, String interval, String kind,
                         String direction, long marketTime, String emailStatus) { }
    public record NoticePage(List<Notice> records, long total, int page, int size) {}

    public NoticePage noticePage(long user, int page) {
        if (page < 1) throw new IllegalArgumentException("Page must be positive");
        int size = 5;
        long total = db.queryForObject("SELECT COUNT(*) FROM zone_alert_notice WHERE user_id=?", Long.class, user);
        List<Notice> records = db.query("""
                SELECT id, zone_id, symbol, analysis_interval, kind, direction, market_time, email_status
                FROM zone_alert_notice WHERE user_id=? ORDER BY market_time DESC, id DESC LIMIT ? OFFSET ?
                """, (rs, n) -> readNotice(rs), user, size, (page - 1L) * size);
        return new NoticePage(records, total, page, size);
    }
    public record Inbox(List<Long> subscriptions, List<Notice> notices, boolean emailEnabled) { }

    public Inbox inbox(long user) {
        return new Inbox(db.queryForList("SELECT zone_id FROM zone_alert_subscription WHERE user_id=? ORDER BY zone_id",
                Long.class, user), db.query("""
                SELECT id, zone_id, symbol, analysis_interval, kind, direction, market_time, email_status
                FROM zone_alert_notice WHERE user_id=? ORDER BY id DESC LIMIT 50
                """, (rs, n) -> readNotice(rs), user), mail.enabledFor(user));
    }

    public void subscribe(long user, long zoneId) {
        var zone = zones.selectById(zoneId);
        if (zone == null || zone.getLifecycle() != ZoneLifecycle.ACTIVE
                || zone.getEffectiveUntil() != null && clock.millis() >= zone.getEffectiveUntil()
                || !monitor.scopes().contains(new MonitorScope(zone.getMarketType(), zone.getSymbol(), zone.getAnalysisInterval()))) {
            throw MarketMonitorException.invalid("Cannot subscribe to this zone");
        }
        db.update("""
                INSERT INTO zone_alert_subscription (user_id, zone_id, created_at) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE created_at=created_at
                """, user, zoneId, clock.millis());
    }

    public void unsubscribe(long user, long zoneId) {
        db.update("DELETE FROM zone_alert_subscription WHERE user_id=? AND zone_id=?", user, zoneId);
    }

    /** Store approach notices in the same transaction as the observed transition. */
    void approach(MarketZoneRow zone, ObservationState previous, ObservationState current, long marketTime) {
        if (previous == ObservationState.ABOVE && current == ObservationState.APPROACHING_FROM_ABOVE) {
            enqueue(zone, "APPROACH", "LONG", marketTime);
        } else if (previous == ObservationState.BELOW && current == ObservationState.APPROACHING_FROM_BELOW) {
            enqueue(zone, "APPROACH", "SHORT", marketTime);
        }
    }

    @Scheduled(fixedDelay = 3000, scheduler = "zoneAlertScheduler")
    void confirmations() {
        for (MonitorScope scope : monitor.scopes()) {
            try { confirm(scope); }
            catch (RuntimeException e) { log.warn("Zone alert confirmation unavailable for {}", scope.key()); }
        }
    }

    private void confirm(MonitorScope scope) {
        var fresh = live.read(scope).filter(snapshot -> snapshot.status() == Status.LIVE
                && LiveSnapshotPolicy.valid(scope, snapshot, clock, monitor.getStaleMillis())).orElse(null);
        if (fresh == null) return;
        long target = fresh.lastClosedCandleTime(), period = scope.periodMillis(), close = target + period;
        Long previousTarget = checked.putIfAbsent(scope, target);
        if (previousTarget == null || previousTarget >= target || close > clock.millis()) return;
        if (clock.millis() - close > MAX_ALERT_AGE) {
            checked.put(scope, target);
            return;
        }
        var ids = db.queryForList("""
                SELECT DISTINCT s.zone_id FROM zone_alert_subscription s JOIN market_zone z ON z.id=s.zone_id
                WHERE z.market_type=? AND z.symbol=? AND z.analysis_interval=? AND z.lifecycle='ACTIVE'
                """, Long.class, scope.marketType().name(), scope.symbol(), scope.interval());
        if (!ids.isEmpty()) {
            List<Kline> bars = history.getKlines(scope.marketType(), scope.symbol(), scope.interval(), 3);
            Kline bar = bars.stream().filter(k -> Objects.equals(k.getTime(), target)).findFirst().orElse(null);
            if (bar == null || clock.millis() - close > MAX_ALERT_AGE) return;
            Kline previous = bars.stream().filter(k -> Objects.equals(k.getTime(), target - period)).findFirst().orElse(null);
            boolean needsPrevious = false;
            for (long id : ids) {
                var z = zones.selectById(id);
                if (z == null || z.getLifecycle() != ZoneLifecycle.ACTIVE || z.getUpdatedAt() > target
                        || z.getEffectiveUntil() != null && clock.millis() >= z.getEffectiveUntil()) continue;
                if (z.getEndLowerPrice() == null && previous == null) {
                    needsPrevious = true;
                    continue;
                }
                var revision = new ChartObjectHistory.Revision(id, z.getRevision(), z.getLowerPrice().doubleValue(),
                        z.getUpperPrice().doubleValue(), z.getEffectiveFrom(), z.getEffectiveUntil(), z.getUpdatedAt(),
                        z.getLifecycle(), z.getEndLowerPrice() != null,
                        z.getEndLowerPrice() == null ? null : z.getEndLowerPrice().doubleValue());
                TradeSignal action = ChartZoneSignals.liveSignal(revision, previous, bar, period);
                if (action == TradeSignal.OPEN_LONG || action == TradeSignal.OPEN_SHORT) {
                    enqueue(z, "CONFIRMED", action == TradeSignal.OPEN_LONG ? "LONG" : "SHORT", close);
                }
            }
            if (needsPrevious) return;
        }
        checked.put(scope, target);
    }

    private void enqueue(MarketZoneRow zone, String kind, String direction, long marketTime) {
        db.update("""
                INSERT INTO zone_alert_notice
                    (user_id, zone_id, zone_revision, symbol, analysis_interval, kind, direction, market_time, created_at)
                SELECT s.user_id, z.id, z.revision, z.symbol, z.analysis_interval, ?, ?, ?, ?
                FROM zone_alert_subscription s JOIN market_zone z ON z.id=s.zone_id
                LEFT JOIN zone_alert_preference p ON p.user_id=s.user_id
                WHERE z.id=? AND z.revision=? AND z.lifecycle='ACTIVE' AND s.created_at <= ?
                  AND (p.user_id IS NULL OR (p.enabled=TRUE AND p.enabled_since<=?))
                ON DUPLICATE KEY UPDATE email_status=zone_alert_notice.email_status
                """, kind, direction, marketTime, clock.millis(), zone.getId(), zone.getRevision(), marketTime, marketTime);
    }

    @Scheduled(fixedDelay = 3000, scheduler = "zoneAlertMailScheduler")
    void deliver() {
        long now = clock.millis();
        db.update("UPDATE zone_alert_notice SET email_status='EXPIRED' WHERE email_status='PENDING' AND created_at<?", now - MAX_ALERT_AGE);
        db.update("UPDATE zone_alert_notice SET email_status='FAILED' WHERE email_status='SENDING' AND claimed_at<?", now - MAX_ALERT_AGE);
        var pending = db.query("""
                SELECT id, user_id, zone_id, symbol, analysis_interval, kind, direction, market_time, email_status
                FROM zone_alert_notice WHERE email_status='PENDING' ORDER BY id LIMIT 10
                """, (rs, n) -> new Delivery(rs.getLong("user_id"), readNotice(rs)));
        for (Delivery task : pending) {
            long id = task.notice().id();
            if (db.update("""
                    UPDATE zone_alert_notice SET email_status='SENDING', claimed_at=?
                    WHERE id=? AND email_status='PENDING' AND created_at>=?
                    """, clock.millis(), id, clock.millis() - MAX_ALERT_AGE) != 1) continue;
            String status = stillEligible(id) ? sendEmail(task) : "SKIPPED";
            db.update("UPDATE zone_alert_notice SET email_status=? WHERE id=? AND email_status='SENDING'", status, id);
        }
    }

    private String sendEmail(Delivery task) {
        String configured = mail.configuredRecipient(task.user());
        String recipient = db.query("SELECT enabled AND email_enabled AND enabled_since<=?, email FROM zone_alert_preference WHERE user_id=?",
                rs -> {
                    if (!rs.next()) return configured;
                    if (!rs.getBoolean(1)) return null;
                    String custom = rs.getString(2);
                    return custom == null ? configured : custom;
                }, task.notice().marketTime(), task.user());
        return mail.sendTo(recipient, task.notice());
    }

    /** Recheck the subscription and the exact chart revision before contacting SMTP. */
    private boolean stillEligible(long noticeId) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM zone_alert_notice n
                    JOIN zone_alert_subscription s ON s.user_id=n.user_id AND s.zone_id=n.zone_id
                    JOIN market_zone z ON z.id=n.zone_id AND z.revision=n.zone_revision
                    WHERE n.id=? AND n.email_status='SENDING' AND s.created_at<=n.market_time
                        AND z.lifecycle='ACTIVE' AND (z.effective_until IS NULL OR z.effective_until>?)
                )
                """, Boolean.class, noticeId, clock.millis()));
    }

    private record Delivery(long user, Notice notice) { }

    private static Notice readNotice(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Notice(rs.getLong("id"), rs.getLong("zone_id"), rs.getString("symbol"),
                rs.getString("analysis_interval"), rs.getString("kind"), rs.getString("direction"),
                rs.getLong("market_time"), rs.getString("email_status"));
    }
}
