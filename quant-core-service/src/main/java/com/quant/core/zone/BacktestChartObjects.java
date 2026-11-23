package com.quant.core.zone;

import com.quant.common.market.MarketSelection;
import com.quant.core.mapper.MarketZoneMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 一次批量读取所选对象及修订，先校验作用域，再固化到任务参数。 */
@Service
public class BacktestChartObjects {
    private final MarketZoneMapper mapper;
    private final boolean enabled;

    public BacktestChartObjects(MarketZoneMapper mapper, @Value("${market-zones.enabled:false}") boolean enabled) {
        this.mapper = mapper;
        this.enabled = enabled;
    }

    @Transactional(readOnly = true)
    public ChartObjectHistory.Snapshot freeze(MarketSelection selection, List<Long> ids, long cutoff) {
        if (!enabled) throw new IllegalArgumentException("共享图表对象尚未启用");
        if (ids == null || ids.isEmpty() || ids.size() > 20 || ids.stream().anyMatch(id -> id == null || id <= 0)
                || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("请选择 1..20 个不同的共享区域或通道");
        }
        List<MarketZoneRow> rows = mapper.selectBacktestDefinitions(selection.marketType(), selection.symbol(),
                selection.interval(), ids, cutoff);
        if (ids.stream().anyMatch(id -> rows.stream().noneMatch(row -> id.equals(row.getId())))) {
            throw new IllegalArgumentException("所选区域已归档、不存在或不属于当前作用域，请刷新区域选择");
        }
        List<ChartObjectHistory.Revision> revisions = rows.stream().map(r -> new ChartObjectHistory.Revision(
                r.getId(), r.getRevision(), r.getLowerPrice().doubleValue(), r.getUpperPrice().doubleValue(),
                r.getEffectiveFrom(), r.getEffectiveUntil(), r.getUpdatedAt(), r.getLifecycle(), r.getEndLowerPrice() != null,
                r.getEndLowerPrice() == null ? null : r.getEndLowerPrice().doubleValue())).toList();
        // 当前归档、缺失及跨作用域的 ID 均缺少查询结果，不能进入新任务。
        return new ChartObjectHistory.Snapshot(3, ids, revisions);
    }
}
