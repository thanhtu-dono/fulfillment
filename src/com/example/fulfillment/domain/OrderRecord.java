package com.example.fulfillment.domain;

import java.util.Objects;

public record OrderRecord(Order order, OrderStatus status) {
    public OrderRecord {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(status, "status");
    }
}
