package com.quant.core.mapper;

import com.quant.core.monitor.MonitorCursor;
import com.quant.core.monitor.MonitorScope;
import com.quant.core.monitor.ZoneEvent;
import com.quant.core.monitor.ZoneObservationRow;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MarketMonitorMapper {
    String CURSOR_FIELDS = "market_type,symbol,analysis_interval,feed_id,source_sequence,price_time,price_received_at,"
            + "source_emitted_at,last_price,status,baseline_required,recorded_at";

    @Select("SELECT " + CURSOR_FIELDS + " FROM market_monitor_cursor WHERE market_type=#{marketType} "
            + "AND symbol=#{symbol} AND analysis_interval=#{interval}")
    MonitorCursor selectCursor(MonitorScope scope);

    @Select("SELECT " + CURSOR_FIELDS + " FROM market_monitor_cursor WHERE market_type=#{marketType} "
            + "AND symbol=#{symbol} AND analysis_interval=#{interval} FOR UPDATE")
    MonitorCursor selectCursorForUpdate(MonitorScope scope);

    @Insert("INSERT INTO market_monitor_cursor(" + CURSOR_FIELDS + ") VALUES(#{marketType},#{symbol},#{analysisInterval},"
            + "#{feedId},#{sourceSequence},#{priceTime},#{priceReceivedAt},#{sourceEmittedAt},#{lastPrice},#{status},#{baselineRequired},#{recordedAt}) "
            + "ON DUPLICATE KEY UPDATE feed_id=VALUES(feed_id),source_sequence=VALUES(source_sequence),price_time=VALUES(price_time),"
            + "price_received_at=VALUES(price_received_at),source_emitted_at=VALUES(source_emitted_at),last_price=VALUES(last_price),"
            + "status=VALUES(status),baseline_required=VALUES(baseline_required),recorded_at=VALUES(recorded_at)")
    int saveCursor(MonitorCursor cursor);

    @Select("SELECT o.zone_id,o.zone_revision,o.state,o.state_price,o.state_market_time,o.feed_id,o.source_sequence,o.recorded_at "
            + "FROM market_zone_observation o JOIN market_zone z ON z.id=o.zone_id WHERE z.market_type=#{marketType} "
            + "AND z.symbol=#{symbol} AND z.analysis_interval=#{interval} AND z.lifecycle='ACTIVE'")
    List<ZoneObservationRow> selectObservations(MonitorScope scope);

    @Insert("INSERT INTO market_zone_observation(zone_id,zone_revision,state,state_price,state_market_time,feed_id,source_sequence,recorded_at) "
            + "VALUES(#{zoneId},#{zoneRevision},#{state},#{statePrice},#{stateMarketTime},#{feedId},#{sourceSequence},#{recordedAt}) "
            + "ON DUPLICATE KEY UPDATE zone_revision=VALUES(zone_revision),state=VALUES(state),state_price=VALUES(state_price),"
            + "state_market_time=VALUES(state_market_time),feed_id=VALUES(feed_id),source_sequence=VALUES(source_sequence),recorded_at=VALUES(recorded_at)")
    int saveObservation(ZoneObservationRow observation);

    @Insert("INSERT INTO market_zone_event(zone_id,zone_revision,market_type,symbol,analysis_interval,from_state,to_state,event_code,"
            + "observed_price,market_time,recorded_at,feed_id,source_sequence) VALUES(#{zoneId},#{zoneRevision},#{marketType},#{symbol},"
            + "#{analysisInterval},#{fromState},#{toState},#{eventCode},#{observedPrice},#{marketTime},#{recordedAt},#{feedId},#{sourceSequence})")
    int insertEvent(ZoneEvent event);

    @Select("<script>SELECT COUNT(*) FROM market_zone_event e WHERE e.market_type=#{scope.marketType} AND e.symbol=#{scope.symbol} "
            + "AND e.analysis_interval=#{scope.interval}<if test='zoneId != null'> AND e.zone_id=#{zoneId}</if></script>")
    long countEvents(@Param("scope") MonitorScope scope, @Param("zoneId") Long zoneId);

    @Select("<script>SELECT e.*,r.lower_price,r.upper_price,r.effective_from,r.note FROM market_zone_event e "
            + "JOIN market_zone_revision r ON r.zone_id=e.zone_id AND r.revision=e.zone_revision "
            + "WHERE e.market_type=#{scope.marketType} AND e.symbol=#{scope.symbol} AND e.analysis_interval=#{scope.interval} "
            + "<if test='zoneId != null'>AND e.zone_id=#{zoneId} </if>ORDER BY e.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<ZoneEvent> selectEvents(@Param("scope") MonitorScope scope, @Param("zoneId") Long zoneId,
            @Param("size") int size, @Param("offset") int offset);
}
