package com.quant.core.indicator;

import com.quant.common.model.Kline;

import java.util.List;

/**
 * 技术指标计算工具类
 */
public class IndicatorUtils {

    /**
     * 计算 ATR（Average True Range，平均真实波幅）
     *
     * ATR 用于衡量市场波动性，是海龟策略的核心指标：
     * - 决定仓位大小（单笔风险 / ATR = 单位头寸）
     * - 决定止损距离（跌破 2 ATR 止损）
     * - 决定加仓间距（每涨 0.5 ATR 加仓）
     *
     * @param klines  K线数据（时间升序）
     * @param period  计算周期（通常为 20）
     * @return ATR 值
     */
    public static double calcATR(List<Kline> klines, int period) {
        if (klines.size() < period + 1) {
            return 0;
        }

        double sumTR = 0;

        for (int i = klines.size() - period; i < klines.size(); i++) {
            Kline curr = klines.get(i);
            Kline prev = klines.get(i - 1);

            // True Range = max(H-L, |H-Cp|, |L-Cp|)
            double highLow = curr.getHigh() - curr.getLow();
            double highClose = Math.abs(curr.getHigh() - prev.getClose());//应该是针对的跳空的情况产生的波动
            double lowClose = Math.abs(curr.getLow() - prev.getClose());

            double tr = Math.max(highLow, Math.max(highClose, lowClose));
            sumTR += tr;
        }

        return sumTR / period;
    }

    /**
     * 计算 EMA（Exponential Moving Average，指数移动平均）
     *
     * EMA 对近期价格赋予更高权重，比 SMA 更灵敏。常用于趋势判断：
     * - 价格上穿 EMA → 趋势转多
     * - 价格下穿 EMA → 趋势转空
     *
     * 算法：α = 2/(period+1)，EMA_t = price_t × α + EMA_{t-1} × (1-α)
     * 初始值用前 period 根的 SMA。
     *
     * @param klines  K线数据（时间升序）
     * @param period  计算周期（如 20）
     * @return 最新一根的 EMA 值，数据不足返回 0
     */
    public static double calcEMA(List<Kline> klines, int period) {
        if (klines == null || klines.size() < period) {
            return 0;
        }
        double alpha = 2.0 / (period + 1);

        // 初始 EMA = 前 period 根的 SMA
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += klines.get(i).getClose();
        }
        double ema = sum / period;

        // 从第 period 根开始递推
        for (int i = period; i < klines.size(); i++) {
            ema = klines.get(i).getClose() * alpha + ema * (1 - alpha);
        }
        return ema;
    }
}
