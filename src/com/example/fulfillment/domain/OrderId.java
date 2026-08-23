package com.example.fulfillment.domain;

import java.util.Objects;

public record OrderId(String value) implements Comparable<OrderId> {
    public OrderId {
        Objects.requireNonNull(value, "value");
        if (!value.matches("ORD-[0-9]{6}")) {
            throw new IllegalArgumentException("Invalid order id: " + value);
        }
    }

    @Override
    public int compareTo(OrderId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
