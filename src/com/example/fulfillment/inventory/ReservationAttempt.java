package com.example.fulfillment.inventory;

import com.example.fulfillment.domain.ReservationAllocation;

import java.util.List;

public record ReservationAttempt(boolean reserved, List<ReservationAllocation> allocations) {
    public ReservationAttempt {
        allocations = List.copyOf(allocations);
    }

    public static ReservationAttempt failed() {
        return new ReservationAttempt(false, List.of());
    }
}
