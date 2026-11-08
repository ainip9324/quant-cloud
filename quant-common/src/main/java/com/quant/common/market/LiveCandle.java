package com.quant.common.market;

import java.math.BigDecimal;
import java.util.Objects;

/** Current, unclosed candle. It is never written directly to historical storage. */
public record LiveCandle(long time, BigDecimal open, BigDecimal high, BigDecimal low,
                         BigDecimal close, BigDecimal volume) {
    public LiveCandle {
        Objects.requireNonNull(open);
        Objects.requireNonNull(high);
        Objects.requireNonNull(low);
        Objects.requireNonNull(close);
        Objects.requireNonNull(volume);
        if (time <= 0 || low.signum() <= 0 || open.compareTo(low) < 0 || close.compareTo(low) < 0
                || high.compareTo(open) < 0 || high.compareTo(close) < 0 || volume.signum() < 0) {
            throw new IllegalArgumentException("Invalid live candle");
        }
    }
}
