package com.quant.core.mapper;

import com.quant.common.market.MarketType;
import com.quant.core.zone.MarketZoneRow;
import com.quant.core.zone.ZoneLifecycle;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MarketZoneMapper {
    String FIELDS = "market_type,symbol,analysis_interval,lower_price,upper_price,effective_from,note,"
            + "lifecycle,revision,created_by,updated_by,created_at,updated_at";

    @Insert("INSERT INTO market_zone_scope(market_type,symbol,analysis_interval) VALUES(#{marketType},#{symbol},#{interval}) "
            + "ON DUPLICATE KEY UPDATE market_type=VALUES(market_type)")
    void ensureScope(@Param("marketType") MarketType marketType, @Param("symbol") String symbol, @Param("interval") String interval);

    @Select("SELECT market_type FROM market_zone_scope WHERE market_type=#{marketType} AND symbol=#{symbol} "
            + "AND analysis_interval=#{interval} FOR UPDATE")
    MarketType lockScope(@Param("marketType") MarketType marketType, @Param("symbol") String symbol, @Param("interval") String interval);

    @Select("SELECT id," + FIELDS + " FROM market_zone WHERE market_type=#{marketType} AND symbol=#{symbol} "
            + "AND analysis_interval=#{interval} AND lifecycle!='ARCHIVED' ORDER BY id FOR UPDATE")
    List<MarketZoneRow> selectReservedForUpdate(@Param("marketType") MarketType marketType,
            @Param("symbol") String symbol, @Param("interval") String interval);

    @Select("SELECT id," + FIELDS + " FROM market_zone WHERE market_type=#{marketType} AND symbol=#{symbol} "
            + "AND analysis_interval=#{interval} AND lifecycle=#{lifecycle} ORDER BY id LIMIT #{limit}")
    List<MarketZoneRow> selectListed(@Param("marketType") MarketType marketType, @Param("symbol") String symbol,
            @Param("interval") String interval, @Param("lifecycle") ZoneLifecycle lifecycle, @Param("limit") int limit);

    @Select("SELECT id," + FIELDS + " FROM market_zone WHERE id=#{id}")
    MarketZoneRow selectById(@Param("id") long id);

    @Select("SELECT id," + FIELDS + " FROM market_zone WHERE id=#{id} FOR UPDATE")
    MarketZoneRow selectByIdForUpdate(@Param("id") long id);

    @Insert("INSERT INTO market_zone(" + FIELDS + ") VALUES(#{marketType},#{symbol},#{analysisInterval},#{lowerPrice},"
            + "#{upperPrice},#{effectiveFrom},#{note},#{lifecycle},#{revision},#{createdBy},#{updatedBy},#{createdAt},#{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MarketZoneRow row);

    @Update("UPDATE market_zone SET lower_price=#{zone.lowerPrice},upper_price=#{zone.upperPrice},"
            + "effective_from=#{zone.effectiveFrom},note=#{zone.note},lifecycle=#{zone.lifecycle},revision=#{zone.revision},"
            + "updated_by=#{zone.updatedBy},updated_at=#{zone.updatedAt} "
            + "WHERE id=#{zone.id} AND revision=#{expectedVersion} AND lifecycle!='ARCHIVED'")
    int updateVersion(@Param("zone") MarketZoneRow zone, @Param("expectedVersion") long expectedVersion);

    @Insert("INSERT INTO market_zone_revision(zone_id," + FIELDS + ") SELECT id," + FIELDS + " FROM market_zone WHERE id=#{id}")
    int appendRevision(@Param("id") long id);

    @Select("SELECT zone_id AS id," + FIELDS + " FROM market_zone_revision WHERE zone_id=#{id} ORDER BY revision DESC LIMIT #{limit}")
    List<MarketZoneRow> selectRevisions(@Param("id") long id, @Param("limit") int limit);

    @Select("SELECT zone_id AS id," + FIELDS + " FROM market_zone_revision WHERE zone_id=#{id} AND revision=#{revision}")
    MarketZoneRow selectRevision(@Param("id") long id, @Param("revision") long revision);
}
