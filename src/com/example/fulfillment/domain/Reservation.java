package com.example.fulfillment.domain;

import java.util.List;
import java.util.Objects;

public record Reservation(OrderId orderId, List<ReservationAllocation> allocations) {
    public Reservation {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(allocations, "allocations");
        allocations = List.copyOf(allocations);
    }
}
