package com.example.fulfillment.inventory;

import com.example.fulfillment.domain.FulfillmentCenter;
import com.example.fulfillment.domain.InventoryKey;
import com.example.fulfillment.domain.Order;
import com.example.fulfillment.domain.Sku;
import com.example.fulfillment.protocol.InventorySeedReader.InventorySeed;

import java.util.Map;
import java.util.function.Consumer;

public interface InventoryRepository {
    ReservationAttempt tryReserve(Order order);
    ReservationAttempt tryReserve(Order order, Consumer<ReservationAttempt> completion);
    void restock(Sku sku, FulfillmentCenter center, int quantity);
    int companyCapacity(Sku sku);
    Map<InventoryKey, StockSnapshot> snapshot();

    record StockSnapshot(int quantity, double unitPrice) {
    }

    static InventoryRepository fromSeed(Map<InventoryKey, InventorySeed> seed) {
        return new InMemoryInventoryRepository(seed);
    }
}
