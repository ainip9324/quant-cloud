/** 两个矩形锚点或三个通道锚点，转换为可保存的时间、价格定义。 */
export function drawnZone(points, period, channel = false) {
  if (points.length < (channel ? 3 : 2)) return null
  const [a, b] = points
  const [left, right] = a.time <= b.time ? [a, b] : [b, a]
  const effectiveFrom = left.time
  const effectiveUntil = right.time + period
  let lower = Math.min(a.price, b.price), upper = Math.max(a.price, b.price)
  let endLower
  if (channel) {
    if (a.time === b.time) return null
    const slope = (right.price - left.price) / (effectiveUntil - effectiveFrom)
    const third = points[2]
    const offset = third.price - (left.price + slope * (third.time - effectiveFrom))
    lower = left.price + Math.min(0, offset)
    upper = left.price + Math.max(0, offset)
    endLower = right.price + Math.min(0, offset)
  }
  const lowerPrice = lower.toFixed(2), upperPrice = upper.toFixed(2)
  if (Number(lowerPrice) <= 0 || Number(upperPrice) <= Number(lowerPrice) ||
      (channel && Number(endLower.toFixed(2)) <= 0)) return null
  return { lowerPrice, upperPrice, effectiveFrom, effectiveUntil,
    ...(channel ? { endLowerPrice: endLower.toFixed(2) } : {}) }
}

/** 根据拖动量更新几何。时间按周期移动，价格变化保留原定义的小数精度。 */
export function adjustZone(zone, handle, timeDelta, priceDelta, period) {
  const shiftTime = Math.round(timeDelta / period) * period
  const shiftPrice = Math.round(priceDelta * 100)
  const result = { ...zone }
  function movePrice(key) {
    if (!shiftPrice) return
    const [whole, fraction = ''] = String(zone[key]).split('.')
    const value = BigInt(whole + fraction.padEnd(8, '0')) + BigInt(shiftPrice) * 1000000n
    const digits = value.toString().padStart(9, '0')
    result[key] = value <= 0n ? '0' : digits.slice(0, -8) + '.' + digits.slice(-8)
  }
  if (handle === 'move' || handle.startsWith('start')) result.effectiveFrom += shiftTime
  if (result.effectiveUntil != null && (handle === 'move' || handle.startsWith('end'))) result.effectiveUntil += shiftTime
  if (handle === 'move' || handle === 'start-channel') {
    movePrice('lowerPrice')
    movePrice('upperPrice')
  }
  if (handle.endsWith('lower')) movePrice('lowerPrice')
  if (handle.endsWith('upper') || handle === 'width') movePrice('upperPrice')
  if (result.endLowerPrice != null && (handle === 'move' || handle === 'end-channel')) movePrice('endLowerPrice')
  if (result.effectiveFrom <= 0 || (result.effectiveUntil != null && result.effectiveUntil <= result.effectiveFrom)
      || Number(result.lowerPrice) <= 0 || Number(result.upperPrice) <= Number(result.lowerPrice)
      || (result.endLowerPrice != null && Number(result.endLowerPrice) <= 0)) return null
  return result
}
