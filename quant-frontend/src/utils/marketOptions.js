export const MARKET_OPTIONS = [
  { value: 'SPOT', label: '现货 / SPOT' },
  { value: 'USDT_FUTURES', label: 'USDT 永续 / USDT_FUTURES' }
]
const intervalOptions = values => values.map(value => ({ value, label: ({
  '1min': '1分钟', '5min': '5分钟', '15min': '15分钟', '1h': '1小时', '4h': '4小时', '1day': '1天'
})[value] }))
export const INTERVALS = {
  SPOT: intervalOptions(['1min', '5min', '15min', '1h', '4h', '1day']),
  USDT_FUTURES: intervalOptions(['15min', '1h', '4h', '1day'])
}
export const marketLabel = value => MARKET_OPTIONS.find(option => option.value === value)?.label || value
export function normalizeMarketSelection(marketType, symbol, interval, symbols = []) {
  const allowed = INTERVALS[marketType] || []
  return { symbol: symbols.includes(symbol) ? symbol : symbols[0] || '', interval: allowed.some(option => option.value === interval) ? interval : allowed[0]?.value || '' }
}
