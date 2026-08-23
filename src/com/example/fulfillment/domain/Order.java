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
        List<OrderLine> numberedLines = new java.util.ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            OrderLine line = lines.get(index);
            numberedLines.add(line.lineNumber() < 0
                ? new OrderLine(line.sku(), line.quantity(), index) : line);
        }
        lines = List.copyOf(numberedLines);
    }
}
