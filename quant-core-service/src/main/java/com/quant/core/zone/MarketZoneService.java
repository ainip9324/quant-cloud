package com.quant.core.zone;

import com.quant.common.auth.AuthenticatedPrincipal;
import com.quant.common.auth.UserRole;
import com.quant.common.market.InvalidMarketQueryException;
import com.quant.common.market.MarketCatalog;
import com.quant.common.market.MarketSelection;
import com.quant.common.market.MarketType;
import com.quant.core.mapper.MarketZoneMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "market-zones.enabled", havingValue = "true")
public class MarketZoneService {
    private static final int MAX_RESERVED = 100;
    private final MarketZoneMapper mapper;
    private final Clock clock;

    @Autowired public MarketZoneService(MarketZoneMapper mapper) { this(mapper, Clock.systemUTC()); }
    MarketZoneService(MarketZoneMapper mapper, Clock clock) { this.mapper = mapper; this.clock = clock; }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public MarketZone create(ZoneDefinition command, AuthenticatedPrincipal actor) {
        requireAdmin(actor);
        Definition definition = definition(command);
        if (command.expectedVersion() != null) throw MarketZoneException.invalid("Create does not accept expectedVersion");
        lockScope(definition.selection());
        List<MarketZoneRow> reserved = reserved(definition.selection());
        if (reserved.size() >= MAX_RESERVED) throw MarketZoneException.conflict("MARKET_ZONE_LIMIT", "Scope has 100 unarchived zones");
        ensureNoOverlap(reserved, null, definition.lower(), definition.upper());
        MarketZoneRow row = new MarketZoneRow();
        row.setMarketType(definition.selection().marketType());
        row.setSymbol(definition.selection().symbol());
        row.setAnalysisInterval(definition.selection().interval());
        apply(row, definition);
        row.setLifecycle(ZoneLifecycle.ACTIVE);
        row.setRevision(1L);
        row.setCreatedBy(actor.userId()); row.setUpdatedBy(actor.userId());
        row.setCreatedAt(clock.millis()); row.setUpdatedAt(row.getCreatedAt());
        if (mapper.insert(row) != 1 || row.getId() == null || row.getId() <= 0) throw MarketZoneException.storage();
        appendRevision(row);
        return row.snapshot();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public MarketZone update(long id, ZoneDefinition command, AuthenticatedPrincipal actor) {
        requireAdmin(actor);
        Definition definition = definition(command);
        long expected = version(command.expectedVersion());
        MarketZoneRow row = lockedZone(id);
        requireVersion(row, expected);
        requireMutable(row);
        if (row.getMarketType() != definition.selection().marketType()
                || !row.getSymbol().equals(definition.selection().symbol())
                || !row.getAnalysisInterval().equals(definition.selection().interval())) {
            throw MarketZoneException.invalid("Market identity is immutable; create a new zone instead");
        }
        ensureNoOverlap(reserved(definition.selection()), row.getId(), definition.lower(), definition.upper());
        apply(row, definition);
        persistChange(row, expected, actor.userId());
        return row.snapshot();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public MarketZone changeLifecycle(long id, ZoneVersion command, ZoneLifecycle target, AuthenticatedPrincipal actor) {
        requireAdmin(actor);
        if (target == null || command == null) throw MarketZoneException.invalid("Lifecycle and expectedVersion are required");
        long expected = version(command.expectedVersion());
        MarketZoneRow row = lockedZone(id);
        requireVersion(row, expected);
        if (row.getLifecycle() == target) return row.snapshot();
        requireMutable(row);
        if (target == ZoneLifecycle.ACTIVE) {
            ensureNoOverlap(reserved(selection(row)), row.getId(), row.getLowerPrice(), row.getUpperPrice());
        }
        row.setLifecycle(target);
        persistChange(row, expected, actor.userId());
        return row.snapshot();
    }

    @Transactional(readOnly = true)
    public List<MarketZone> list(String marketType, String symbol, String interval, String rawLifecycle, int limit) {
        MarketSelection selection = selection(marketType, symbol, interval);
        ZoneLifecycle lifecycle;
        try { lifecycle = rawLifecycle == null ? ZoneLifecycle.ACTIVE : ZoneLifecycle.valueOf(rawLifecycle); }
        catch (IllegalArgumentException exception) { throw MarketZoneException.invalid("Invalid lifecycle"); }
        requireLimit(limit);
        return mapper.selectListed(selection.marketType(), selection.symbol(), selection.interval(), lifecycle, limit)
                .stream().map(MarketZoneRow::snapshot).toList();
    }

    @Transactional(readOnly = true)
    public MarketZone get(long id) { return required(mapper.selectById(requireId(id))).snapshot(); }

    @Transactional(readOnly = true)
    public List<MarketZone> revisions(long id, int limit, AuthenticatedPrincipal actor) {
        requireAdmin(actor);
        requireLimit(limit);
        required(mapper.selectById(requireId(id)));
        return mapper.selectRevisions(id, limit).stream().map(MarketZoneRow::snapshot).toList();
    }

    @Transactional(readOnly = true)
    public MarketZone revision(long id, long revision, AuthenticatedPrincipal actor) {
        requireAdmin(actor);
        if (revision <= 0) throw MarketZoneException.invalid("Revision must be positive");
        required(mapper.selectById(requireId(id)));
        MarketZoneRow row = mapper.selectRevision(id, revision);
        if (row == null) throw new MarketZoneException(404, "MARKET_ZONE_REVISION_NOT_FOUND", "Market zone revision not found");
        return row.snapshot();
    }

    private MarketZoneRow lockedZone(long id) {
        MarketZoneRow initial = required(mapper.selectById(requireId(id)));
        lockScope(selection(initial));
        // Re-read after the scope lock: the initial lookup is not an optimistic-lock check.
        return required(mapper.selectByIdForUpdate(id));
    }

    private void lockScope(MarketSelection selection) {
        mapper.ensureScope(selection.marketType(), selection.symbol(), selection.interval());
        if (mapper.lockScope(selection.marketType(), selection.symbol(), selection.interval()) != selection.marketType()) {
            throw MarketZoneException.storage();
        }
    }

    private List<MarketZoneRow> reserved(MarketSelection selection) {
        return mapper.selectReservedForUpdate(selection.marketType(), selection.symbol(), selection.interval());
    }

    private void persistChange(MarketZoneRow row, long expected, long actorId) {
        row.setRevision(expected + 1);
        row.setUpdatedBy(actorId); row.setUpdatedAt(clock.millis());
        if (mapper.updateVersion(row, expected) != 1) throw versionConflict();
        appendRevision(row);
    }

    private void appendRevision(MarketZoneRow row) {
        if (mapper.appendRevision(row.getId()) != 1) throw MarketZoneException.storage();
    }

    private static void ensureNoOverlap(List<MarketZoneRow> existing, Long ownId, BigDecimal lower, BigDecimal upper) {
        for (MarketZoneRow zone : existing) {
            if (!Objects.equals(ownId, zone.getId()) && lower.compareTo(zone.getUpperPrice()) < 0
                    && zone.getLowerPrice().compareTo(upper) < 0) {
                throw MarketZoneException.conflict("MARKET_ZONE_OVERLAP", "Zone overlaps an unarchived zone");
            }
        }
    }

    private static Definition definition(ZoneDefinition command) {
        if (command == null) throw MarketZoneException.invalid("Zone definition is required");
        MarketSelection selection = selection(command.marketType(), command.symbol(), command.interval());
        BigDecimal lower = price(command.lowerPrice());
        BigDecimal upper = price(command.upperPrice());
        if (lower.compareTo(upper) >= 0) throw MarketZoneException.invalid("lowerPrice must be less than upperPrice");
        if (command.effectiveFrom() == null || command.effectiveFrom() <= 0 || command.effectiveFrom() > 253402300799999L) {
            throw MarketZoneException.invalid("effectiveFrom must be positive epoch milliseconds");
        }
        String note = command.note() == null ? "" : command.note();
        if (note.length() > 500) throw MarketZoneException.invalid("note must not exceed 500 characters");
        return new Definition(selection, lower, upper, command.effectiveFrom(), note);
    }

    private static BigDecimal price(BigDecimal value) {
        if (value == null || value.signum() <= 0) throw MarketZoneException.invalid("Prices must be positive");
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() > 8 || (long) normalized.precision() - normalized.scale() > 16) {
            throw MarketZoneException.invalid("Price exceeds DECIMAL(24,8) precision");
        }
        return value.setScale(8, RoundingMode.UNNECESSARY);
    }

    private static void apply(MarketZoneRow row, Definition definition) {
        row.setLowerPrice(definition.lower()); row.setUpperPrice(definition.upper());
        row.setEffectiveFrom(definition.effectiveFrom()); row.setNote(definition.note());
    }

    private static MarketSelection selection(MarketZoneRow row) {
        return selection(row.getMarketType().name(), row.getSymbol(), row.getAnalysisInterval());
    }

    private static MarketSelection selection(String marketType, String symbol, String interval) {
        try {
            MarketSelection selection = MarketCatalog.requireSelection(marketType, symbol, interval);
            if (selection.marketType() != MarketType.USDT_FUTURES) throw MarketZoneException.invalid("Zones require USDT_FUTURES");
            return selection;
        } catch (InvalidMarketQueryException exception) {
            throw MarketZoneException.invalid("Invalid market, symbol or interval");
        }
    }

    private static long requireId(long id) {
        if (id <= 0) throw MarketZoneException.invalid("Zone id must be positive");
        return id;
    }
    private static long version(Long version) {
        if (version == null || version <= 0 || version == Long.MAX_VALUE) throw MarketZoneException.invalid("expectedVersion must be a positive integer");
        return version;
    }
    private static void requireVersion(MarketZoneRow row, long expected) {
        if (row.getRevision() != expected) throw versionConflict();
    }
    private static MarketZoneException versionConflict() {
        return MarketZoneException.conflict("MARKET_ZONE_VERSION_CONFLICT", "Zone changed; reload before editing");
    }
    private static void requireMutable(MarketZoneRow row) {
        if (row.getLifecycle() == ZoneLifecycle.ARCHIVED) throw MarketZoneException.conflict("MARKET_ZONE_ARCHIVED", "Archived zones cannot be changed");
    }
    private static MarketZoneRow required(MarketZoneRow row) {
        if (row == null) throw new MarketZoneException(404, "MARKET_ZONE_NOT_FOUND", "Market zone not found");
        return row;
    }
    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 200) throw MarketZoneException.invalid("limit must be between 1 and 200");
    }
    private static void requireAdmin(AuthenticatedPrincipal actor) {
        if (actor == null) throw new MarketZoneException(401, "UNAUTHORIZED", "Authentication required or token expired");
        if (actor.role() != UserRole.ADMIN) throw new MarketZoneException(403, "FORBIDDEN", "Access denied");
    }

    private record Definition(MarketSelection selection, BigDecimal lower, BigDecimal upper, long effectiveFrom, String note) { }
}
