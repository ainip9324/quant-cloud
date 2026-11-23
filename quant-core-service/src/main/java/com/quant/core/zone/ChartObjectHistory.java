package com.quant.core.zone;

import com.alibaba.fastjson2.JSON;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 回测专用的冻结历史；每次任务建立时间索引，决策时不访问数据库。 */
public final class ChartObjectHistory {
    public record Revision(long id, long revision, double lower, double upper, long effectiveFrom,
                           Long effectiveUntil, long recordedAt, ZoneLifecycle lifecycle, boolean channel, Double endLower) {
        /** v1 没有终点价格，兼容旧水平对象及不可交易通道标记。 */
        public Revision(long id, long revision, double lower, double upper, long effectiveFrom,
                        Long effectiveUntil, long recordedAt, ZoneLifecycle lifecycle, boolean channel) {
            this(id, revision, lower, upper, effectiveFrom, effectiveUntil, recordedAt, lifecycle, channel, null);
        }

        public Revision {
            if (id <= 0 || revision <= 0 || !Double.isFinite(lower) || !Double.isFinite(upper)
                    || lower <= 0 || upper <= lower || effectiveFrom <= 0 || recordedAt <= 0 || lifecycle == null
                    || (effectiveUntil != null && effectiveUntil <= effectiveFrom)) {
                throw new IllegalArgumentException("图表对象修订无效");
            }
            if (endLower != null && (!channel || effectiveUntil == null || !Double.isFinite(endLower) || endLower <= 0)) {
                throw new IllegalArgumentException("通道终点参数无效");
            }
        }

        /** 按时间插值，不向右延伸；调用方先检查修订有效期。 */
        public double lowerAt(long time) {
            if (!channel) return lower;
            if (endLower == null) throw new IllegalArgumentException("旧通道快照缺少终点价格");
            return lower + (endLower - lower) * ((double) (time - effectiveFrom) / (effectiveUntil - effectiveFrom));
        }

        public double upperAt(long time) { return channel ? lowerAt(time) + (upper - lower) : upper; }
    }
    public record Snapshot(int version, List<Long> selectedIds, List<Revision> revisions) {
        public Snapshot {
            List<Long> allowedIds = selectedIds;
            if ((version < 1 || version > 3) || selectedIds == null || selectedIds.isEmpty() || selectedIds.size() > 20
                    || selectedIds.stream().anyMatch(id -> id == null || id <= 0)
                    || selectedIds.stream().distinct().count() != selectedIds.size()
                    || revisions == null || revisions.isEmpty() || revisions.size() > 2000
                    || revisions.stream().anyMatch(r -> !allowedIds.contains(r.id()))) {
                throw new IllegalArgumentException("图表对象历史快照无效");
            }
            selectedIds = List.copyOf(selectedIds);
            revisions = List.copyOf(revisions);
            for (Revision r : revisions) {
                if ((version == 1 && r.endLower() != null) || (version >= 2 && r.channel() && r.endLower() == null)) {
                    throw new IllegalArgumentException("几何数据与快照版本不匹配");
                }
            }
            for (Long id : selectedIds) {
                if (revisions.stream().noneMatch(r -> r.id() == id)) {
                    throw new IllegalArgumentException("所选对象没有可读取的历史修订: " + id);
                }
                if (version == 3 && revisions.stream().filter(r -> r.id() == id).count() != 1) {
                    throw new IllegalArgumentException("生效时间快照每个对象只能有一个冻结定义");
                }
            }
        }
    }

    private final Map<Long, TreeMap<Long, Revision>> timeline = new TreeMap<>();
    private final boolean channelsEnabled;
    private final boolean effectiveTimeMode;

    public ChartObjectHistory(Snapshot snapshot) {
        channelsEnabled = snapshot.version() >= 2;
        effectiveTimeMode = snapshot.version() == 3;
        snapshot.revisions().stream().sorted(Comparator.comparingLong(Revision::revision)).forEach(r ->
                timeline.computeIfAbsent(r.id(), ignored -> new TreeMap<>())
                        .put(effectiveTimeMode ? r.effectiveFrom() : r.recordedAt(), r));
    }

    /** 取全根有效且未换版的对象；v1 继续排除通道。 */
    public List<Revision> covering(long open, long close) {
        var result = new ArrayList<Revision>();
        for (var history : timeline.values()) {
            var start = history.floorEntry(open);
            var end = history.floorEntry(close);
            if (start == null || end == null || start.getValue().revision() != end.getValue().revision()) continue;
            Revision r = start.getValue();
            boolean eligible = effectiveTimeMode ? r.lifecycle() != ZoneLifecycle.ARCHIVED : r.lifecycle() == ZoneLifecycle.ACTIVE;
            if ((!r.channel() || channelsEnabled) && eligible && r.effectiveFrom() <= open
                    && (r.effectiveUntil() == null || close < r.effectiveUntil())) result.add(r);
        }
        return result;
    }

    /** 未知或损坏快照必须失败，不回读当前对象补齐。 */
    public static Snapshot snapshot(Map<String, Object> params) {
        try {
            Snapshot value = JSON.parseObject(JSON.toJSONString(params.get("chartObjects")), Snapshot.class);
            if (value == null) throw new IllegalArgumentException("缺少图表对象历史快照");
            return value;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("图表对象历史快照无效", error);
        }
    }
}
