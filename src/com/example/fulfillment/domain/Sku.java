package com.example.fulfillment.domain;

import java.util.Objects;

public record Sku(String value) implements Comparable<Sku> {
    public Sku {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9-]*")) {
            throw new IllegalArgumentException("Invalid SKU: " + value);
        }
    }

    @Override
    public int compareTo(Sku other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
