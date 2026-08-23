package com.example.fulfillment.domain;

import java.time.Instant;
import java.util.Objects;

public record DeadLetterLine(OrderLine line, String reason, Instant createdAt) {
    public DeadLetterLine {
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
