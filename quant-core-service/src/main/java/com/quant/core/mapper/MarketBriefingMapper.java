package com.quant.core.mapper;

import com.quant.core.briefing.MarketBriefing;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MarketBriefingMapper {
    @Select("SELECT outlook, `events`, risks, revision, updated_at FROM market_briefing WHERE id = 1")
    @ConstructorArgs({
            @Arg(column = "outlook", javaType = String.class),
            @Arg(column = "events", javaType = String.class),
            @Arg(column = "risks", javaType = String.class),
            @Arg(column = "revision", javaType = long.class),
            @Arg(column = "updated_at", javaType = Long.class)
    })
    MarketBriefing selectCurrent();

    @Update("""
            UPDATE market_briefing
            SET outlook = #{briefing.outlook}, `events` = #{briefing.events}, risks = #{briefing.risks},
                revision = #{briefing.revision}, updated_at = #{briefing.updatedAt}, updated_by = #{actorId}
            WHERE id = 1 AND revision = #{expectedRevision}
            """)
    int updateVersion(@Param("briefing") MarketBriefing briefing, @Param("actorId") long actorId,
                      @Param("expectedRevision") long expectedRevision);
}
