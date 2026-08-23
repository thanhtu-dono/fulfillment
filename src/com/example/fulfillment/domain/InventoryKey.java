package com.example.fulfillment.domain;

import java.util.Objects;

public record InventoryKey(Sku sku, FulfillmentCenter center) implements Comparable<InventoryKey> {
    public InventoryKey {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(center, "center");
    }

    @Override
    public int compareTo(InventoryKey other) {
        int centerOrder = center.compareTo(other.center);
        return centerOrder != 0 ? centerOrder : sku.compareTo(other.sku);
    }
}
