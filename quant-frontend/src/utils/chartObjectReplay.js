/** 与后端相同的通道插值，time 必须是要比较的实际时刻。 */
export function chartBoundsAt(revision, time) {
  if (!revision.channel) return { lower: revision.lower, upper: revision.upper }
  const lower = revision.lower + (revision.endLower - revision.lower)
    * ((time - revision.effectiveFrom) / (revision.effectiveUntil - revision.effectiveFrom))
  return { lower, upper: lower + (revision.upper - revision.lower) }
}

/** 仅从任务快照重建完整可见修订；v1 继续忽略通道，不读取当前对象。 */
export function historicalZoneSegments(snapshot, times, intervalMillis) {
  const histories = new Map()
  for (const revision of snapshot?.revisions || []) {
    if (!histories.has(revision.id)) histories.set(revision.id, [])
    histories.get(revision.id).push(revision)
  }
  const segments = []
  for (const [id, revisions] of histories) {
    const effectiveMode = snapshot.version === 3
    const key = r => effectiveMode ? r.effectiveFrom : r.recordedAt
    revisions.sort((a, b) => key(a) - key(b) || a.revision - b.revision)
    let cursor = -1, segment = null
    times.forEach((time, index) => {
      const open = Number(time), close = open + intervalMillis
      while (cursor + 1 < revisions.length && key(revisions[cursor + 1]) <= open) cursor++
      const r = revisions[cursor], next = revisions[cursor + 1]
      const geometryValid = r && (!r.channel || (snapshot.version >= 2 && Number.isFinite(r.endLower)
        && r.endLower > 0 && r.effectiveUntil > r.effectiveFrom))
      const enabled = r && (effectiveMode ? r.lifecycle !== 'ARCHIVED' : r.lifecycle === 'ACTIVE')
      const visible = geometryValid && enabled && r.effectiveFrom <= open
        && (r.effectiveUntil == null || close < r.effectiveUntil) && !(next && key(next) <= close)
      if (!visible) { segment = null; return }
      if (segment && segment.revision === r.revision && segment.to === index - 1
          && (!r.channel || open === Number(times[index - 1]) + intervalMillis)) {
        segment.to = index
        if (r.channel) {
          const end = chartBoundsAt(r, close)
          segment.lowerTo = end.lower
          segment.upperTo = end.upper
        }
      } else {
        segment = { zoneId: String(id), revision: r.revision, lower: r.lower, upper: r.upper,
          from: index, to: index, state: 'HISTORICAL' }
        if (r.channel) {
          const start = chartBoundsAt(r, open), end = chartBoundsAt(r, close)
          Object.assign(segment, { channel: true, lower: start.lower, upper: start.upper,
            lowerTo: end.lower, upperTo: end.upper })
        }
        segments.push(segment)
      }
    })
  }
  return segments
}
