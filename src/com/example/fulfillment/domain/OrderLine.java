package com.example.fulfillment.domain;

import java.util.Objects;

public record OrderLine(Sku sku, int quantity, int lineNumber) {
    public OrderLine(Sku sku, int quantity) {
        this(sku, quantity, -1);
    }

    public OrderLine {
        Objects.requireNonNull(sku, "sku");
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (lineNumber < -1) {
            throw new IllegalArgumentException("Line number cannot be less than -1");
        }
    }
}
