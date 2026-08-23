package com.example.fulfillment.domain;

import java.util.List;

public record FulfillmentResult(
        OrderStatus status,
        List<ReservationAllocation> allocations,
        List<OrderLine> backorderedLines,
        List<DeadLetterLine> deadLetterLines) {
    public FulfillmentResult {
        allocations = List.copyOf(allocations);
        backorderedLines = List.copyOf(backorderedLines);
        deadLetterLines = List.copyOf(deadLetterLines);
    }
}
