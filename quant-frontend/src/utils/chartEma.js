const EMA_PERIOD = 20
const ALPHA = 2 / (EMA_PERIOD + 1)

/** 与回测引擎一致，以前 20 根收盘价的均值初始化，随后逐根递推。 */
export function ema20(closes) {
  let sum = 0
  let ema
  return closes.map((close, index) => {
    if (index < EMA_PERIOD - 1) {
      sum += Number(close)
      return null
    }
    ema = index === EMA_PERIOD - 1 ? (sum + Number(close)) / EMA_PERIOD
      : Number(close) * ALPHA + ema * (1 - ALPHA)
    return ema
  })
}

export function ema20Series(data) {
  return {
    id: 'ema20', name: 'EMA20', type: 'line', data, showSymbol: false, z: 3,
    lineStyle: { width: 1.5, color: '#a855f7' }, itemStyle: { color: '#a855f7' }
  }
}
