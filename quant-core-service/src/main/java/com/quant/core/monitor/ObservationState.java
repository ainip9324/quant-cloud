package com.quant.core.monitor;

import java.math.BigDecimal;

public enum ObservationState {
    ABOVE, APPROACHING_FROM_ABOVE, INSIDE, APPROACHING_FROM_BELOW, BELOW;

    public static ObservationState at(BigDecimal price, BigDecimal lower, BigDecimal upper) {
        if (price == null || lower == null || upper == null || price.signum() <= 0 || lower.signum() <= 0 || lower.compareTo(upper) >= 0) {
            throw new IllegalArgumentException("Invalid observation prices");
        }
        BigDecimal width = upper.subtract(lower);
        if (price.compareTo(upper.add(width)) > 0) return ABOVE;
        if (price.compareTo(upper) > 0) return APPROACHING_FROM_ABOVE;
        if (price.compareTo(lower) >= 0) return INSIDE;
        if (price.compareTo(lower.subtract(width)) >= 0) return APPROACHING_FROM_BELOW;
        return BELOW;
    }

    public String role() {
        return switch (this) {
            case ABOVE, APPROACHING_FROM_ABOVE -> "SUPPORT";
            case INSIDE -> "INSIDE";
            case BELOW, APPROACHING_FROM_BELOW -> "RESISTANCE";
        };
    }
}
