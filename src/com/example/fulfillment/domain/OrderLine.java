package com.example.fulfillment.domain;

import java.util.Objects;

public record OrderLine(Sku sku, int quantity) {
    public OrderLine {
        Objects.requireNonNull(sku, "sku");
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
    }
}
