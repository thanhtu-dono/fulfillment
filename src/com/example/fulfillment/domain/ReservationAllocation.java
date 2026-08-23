package com.example.fulfillment.domain;

import java.util.Objects;

public record ReservationAllocation(OrderLine line, FulfillmentCenter center, double unitPrice) {
    public ReservationAllocation {
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(center, "center");
        if (unitPrice < 0 || Double.isNaN(unitPrice) || Double.isInfinite(unitPrice)) {
            throw new IllegalArgumentException("Unit price must be finite and non-negative");
        }
    }
}
