package com.example.fulfillment.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record Order(
        OrderId id,
        OrderTier tier,
        boolean partialAllowed,
        List<OrderLine> lines,
        Instant submittedAt,
        long ingestionSequence) {
    public Order {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(submittedAt, "submittedAt");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one line");
        }
        if (ingestionSequence < 0) {
            throw new IllegalArgumentException("Ingestion sequence cannot be negative");
        }
        lines = List.copyOf(lines);
    }
}
